package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.cwmp.model.EventStruct;
import com.tr069.acs.cwmp.model.InformRequest;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.mapper.CpeDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备纳管：收到 Inform 后把 ACS 的上报地址与周期上报间隔写回设备。
 *
 * <p>出厂固化的 ACS 地址多半指向原厂平台，不改的话设备下一轮就报到别处去了；上报间隔
 * 也常被设成几十秒，设备一多就是无谓的压力。所以 Inform 之后立刻下发一条
 * SetParameterValues，把这几项改成 ACS 自己的配置。</p>
 *
 * <p>连接请求凭据（{@code ManagementServer.ConnectionRequestUsername/Password}）也一并
 * 改写：各批次光猫出厂的 CR 密码互不相同，ACS 拿不到正确的那对就唤不醒设备，只能干等
 * 它周期上报。统一写成 ACS 认得的值并登记进台账，反连唤醒才走得通。</p>
 *
 * <p>与能力采集一样必须跑在回复 InformResponse 之前：指令要先进队列，
 * 才能被 CPE 紧接着的空 POST 捎带下去。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InformProvisionService {

    /** TR-098 与 TR-181 同名，挂在各自的 ManagementServer 下。 */
    private static final String URL_LEAF = "ManagementServer.URL";

    private static final String INTERVAL_LEAF = "ManagementServer.PeriodicInformInterval";

    private static final String CR_USERNAME_LEAF = "ManagementServer.ConnectionRequestUsername";

    private static final String CR_PASSWORD_LEAF = "ManagementServer.ConnectionRequestPassword";

    private static final String BOOTSTRAP = "0 BOOTSTRAP";

    private static final String DEFAULT_ROOT = "InternetGatewayDevice.";

    /** 连接请求凭据的兜底值：电信 ITMS 现场通用，各运营商现场可在配置里改。 */
    private static final String DEFAULT_CR_USER = "itms";

    private static final String DEFAULT_CR_PASSWORD = "itms";

    private static final String AUTH_AUTO = "auto";

    private final CwmpRpcService cwmpRpcService;

    private final CpeDeviceMapper deviceMapper;

    /** 总开关。 */
    @Value("${acs.device.provision.enabled:true}")
    private boolean enabled;

    /** 下发给设备的周期上报间隔(秒)，0 表示不改这一项。 */
    @Value("${acs.device.provision.inform-interval-seconds:300}")
    private long informIntervalSeconds;

    /** 下发给设备的 ACS 上报地址；留空按 public-host 与上报端口拼。 */
    @Value("${acs.device.provision.acs-url:}")
    private String acsUrl;

    @Value("${acs.public-host:127.0.0.1}")
    private String publicHost;

    @Value("${acs.cwmp.listen-ports:9090}")
    private String listenPorts;

    @Value("${acs.cwmp.cpe-path:/ACS-server/ACS}")
    private String cpePath;

    /** 距上次下发超过该小时数才重发。 */
    @Value("${acs.device.provision.reprovision-interval-hours:24}")
    private long reprovisionIntervalHours;

    /** BOOTSTRAP 是否强制重发。 */
    @Value("${acs.device.provision.force-on-bootstrap:true}")
    private boolean forceOnBootstrap;

    /** 下发给设备的连接请求账号；留空或 auto 时按 itms 处理。 */
    @Value("${acs.device.provision.connection-request-username:}")
    private String crUsername;

    @Value("${acs.device.provision.connection-request-password:}")
    private String crPassword;

    /** 每台设备上次纳管的时间戳，进程内记忆：重启后重新纳管一次。 */
    private final Map<String, Long> lastProvision = new ConcurrentHashMap<>();

    /**
     * Inform 之后调用，必要时下发一条 SetParameterValues。
     *
     * <p>已经对得上的项不再下发：Inform 常带着 {@code ManagementServer.URL}，
     * 比对得上就说明这台设备已是纳管状态，不必每轮都写一遍。</p>
     */
    public void onInform(CpeSession session, InformRequest inform) {
        if (!enabled || session == null) {
            return;
        }
        String sn = session.getSn();
        boolean bootstrap = hasBootstrap(inform);
        if (!due(sn) && !(bootstrap && forceOnBootstrap)) {
            return;
        }
        String url = acsUrl();
        String root = rootOf(session);
        List<ParameterValueStruct> values = new ArrayList<>();
        if (!url.equals(reportedValue(inform, URL_LEAF))) {
            values.add(ParameterValueStruct.of(root + URL_LEAF, url));
        }
        String interval = String.valueOf(informIntervalSeconds);
        if (informIntervalSeconds > 0 && !interval.equals(reportedValue(inform, INTERVAL_LEAF))) {
            values.add(ParameterValueStruct.of(root + INTERVAL_LEAF, interval));
        }
        // 连接请求凭据：设备只在要求认证时才校验它，写错了不会有任何提示，
        // 表现就是「唤醒永远失败」。统一写成 ACS 认得的值，才能反连唤醒这台设备
        String user = crUser();
        String password = crPassword();
        if (!user.equals(reportedValue(inform, CR_USERNAME_LEAF))) {
            values.add(ParameterValueStruct.of(root + CR_USERNAME_LEAF, user));
        }
        if (!password.equals(reportedValue(inform, CR_PASSWORD_LEAF))) {
            values.add(ParameterValueStruct.of(root + CR_PASSWORD_LEAF, password));
        }
        if (values.isEmpty()) {
            lastProvision.put(sn, System.currentTimeMillis());
            return;
        }
        lastProvision.put(sn, System.currentTimeMillis());
        log.info("下发纳管参数: sn={}, 参数={}", sn, describe(values));
        cwmpRpcService.setParameterValues(sn, values, null)
                .whenComplete((resp, ex) -> {
                    logResult(sn, resp, ex);
                    // 写成功才登记：设备收下这对凭据后，唤醒就能用台账上的值去认证
                    if (ex == null && !CwmpRpcService.isSessionFault(resp)) {
                        rememberCredentials(sn, user, password);
                    }
                });
    }

    /**
     * 纳管改写了连接请求凭据，同步登记进台账。
     *
     * <p>采集读回的是改写前的值——不同批次出厂密码又各不相同，台账留着旧值，
     * 唤醒就会拿一个设备已经不认的密码去认证，只能退化成等它自己上报。</p>
     */
    private void rememberCredentials(String sn, String user, String password) {
        try {
            CpeDevice device = new CpeDevice();
            device.setAcsToCpeName(user);
            device.setAcsToCpePassword(password);
            deviceMapper.update(device,
                    new LambdaUpdateWrapper<CpeDevice>().eq(CpeDevice::getSn, sn));
            log.info("已登记设备连接请求凭据: sn={}, 账号={}", sn, user);
        } catch (Exception e) {
            log.warn("登记设备连接请求凭据失败: sn={}, err={}", sn, e.getMessage());
        }
    }

    private String crUser() {
        return isAuto(crUsername) ? DEFAULT_CR_USER : crUsername.trim();
    }

    private String crPassword() {
        return isAuto(crPassword) ? DEFAULT_CR_PASSWORD : crPassword.trim();
    }

    private static boolean isAuto(String value) {
        return value == null || value.isBlank() || AUTH_AUTO.equalsIgnoreCase(value.trim());
    }

    /**
     * 距离上次纳管是否已超过重发间隔。
     *
     * <p>只按「最近下发过」判断，不看下发结果：设备回了 fault（参数不可写）时按周期
     * 重试一次就够了，每次上报都重试只会把下发窗口占住，采集反而排不进去。</p>
     */
    private boolean due(String sn) {
        if (reprovisionIntervalHours <= 0) {
            return true;
        }
        Long last = lastProvision.get(sn);
        return last == null
                || System.currentTimeMillis() - last > reprovisionIntervalHours * 3_600_000L;
    }

    /** ACS 上报地址：配了就用配的，没配按对外地址 + 首个上报端口 + 上报路径拼。 */
    private String acsUrl() {
        if (acsUrl != null && !acsUrl.isBlank()) {
            return acsUrl.trim();
        }
        String host = publicHost == null || publicHost.isBlank() ? "127.0.0.1" : publicHost.trim();
        String path = path();
        // 已带协议就只补路径，现场常直接写 https://acs.example.com 这种
        return host.contains("://") ? host + path : "http://" + host + ":" + firstPort() + path;
    }

    /** CPE 上报端口取清单里的第一个：它是主监听端口，其余是兼容厂商固化端口用的。 */
    private String firstPort() {
        if (listenPorts == null || listenPorts.isBlank()) {
            return "9090";
        }
        for (String part : listenPorts.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                return part.trim();
            }
        }
        return "9090";
    }

    private String path() {
        if (cpePath == null || cpePath.isBlank()) {
            return "/ACS-server/ACS";
        }
        String trimmed = cpePath.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    /**
     * 取 Inform 上报的当前值，用来跳过已经对得上的项。
     *
     * <p>直接从 Inform 报文里取，不从会话取：会话属性是上报入队后由消费者填的，
     * 队列开启时本方法跑在填充之前，读会话永远是空。</p>
     */
    private static String reportedValue(InformRequest inform, String suffix) {
        if (inform == null || inform.getParameterList() == null) {
            return null;
        }
        for (ParameterValueStruct p : inform.getParameterList()) {
            if (p != null && p.getName() != null && p.getName().endsWith(suffix)) {
                return p.getValue();
            }
        }
        return null;
    }

    private static String rootOf(CpeSession session) {
        String root = session.getRootNode();
        if (root == null || root.isBlank()) {
            return DEFAULT_ROOT;
        }
        return root.endsWith(".") ? root : root + ".";
    }

    private static boolean hasBootstrap(InformRequest inform) {
        if (inform == null || inform.getEventList() == null) {
            return false;
        }
        for (EventStruct event : inform.getEventList()) {
            if (event.getEventCode() != null && event.getEventCode().startsWith(BOOTSTRAP)) {
                return true;
            }
        }
        return false;
    }

    private void logResult(String sn, SoapMessage resp, Throwable ex) {
        if (ex != null) {
            log.warn("纳管参数下发异常: sn={}, err={}", sn, ex.getMessage());
            return;
        }
        if (CwmpRpcService.isSessionFault(resp)) {
            log.warn("纳管参数下发失败: sn={}, err={}", sn, CwmpRpcService.faultText(resp));
            return;
        }
        log.info("纳管参数下发完成: sn={}", sn);
    }

    private static String describe(List<ParameterValueStruct> values) {
        StringBuilder text = new StringBuilder();
        for (ParameterValueStruct value : values) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(value.getName()).append("=").append(value.getValue());
        }
        return text.toString();
    }
}

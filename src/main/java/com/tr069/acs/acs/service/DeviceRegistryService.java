package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.acs.model.Isp;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.worklist.service.ModelMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import com.tr069.acs.common.log.AcsCoreLog;

/**
 * 设备台账落库。
 *
 * <p>会话只活在内存里，而管理端的设备列表读的是 {@code cpe_device} 表：设备注册或
 * 上报时必须同步落库，会话关闭或超时后要标记离线，否则列表永远空、重启后设备就丢了。</p>
 *
 * <p>落库失败不影响 CWMP 交互——台账是管理端视图，不该把设备挡在门外。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceRegistryService implements ApplicationRunner {

    private static final String STATUS_ONLINE = "online";

    private static final String STATUS_OFFLINE = "offline";

    private final CpeDeviceMapper cpeDeviceMapper;

    /**
     * 在线判定窗口（秒）：最近一次上报落在窗口内就算在线。
     *
     * <p>取值要覆盖常见的周期性 Inform 间隔（多为 300~1800 秒）。
     * 与首页概览的 {@code acs.dashboard.online-window-seconds} 保持一致。</p>
     */
    @Value("${acs.session.online-window-seconds:900}")
    private long onlineWindowSeconds;
    private final ModelMappingService modelMappingService;

    private final AcsCoreLog coreLog;

    /**
     * 启动后重算在线标记。
     *
     * <p>原先是把在线记录一律清成离线——理由是「会话在内存里，重启了就没有在线的」。
     * 但台账在线与否是按最近上报时间判的，跟本进程有没有会话无关：一次普通重启
     * 不该让刚报过的设备集体掉线。这里改成套同样的窗口规则扫一遍。</p>
     */
    @Override
    public void run(ApplicationArguments args) {
        sweepExpiredOnline();
    }

    /**
     * 设备上线或信息变更时落库：已存在则更新，否则新建。
     */
    public void upsertOnline(CpeSession session) {
        upsertOnline(session, List.of());
    }

    /**
     * 设备上线或信息变更时落库：已存在则更新，否则新建。
     *
     * @param informPaths Inform 上报的参数名，用来顺手认出运营商——
     *                    电信定制机的 {@code InternetGatewayDevice.X_CT-COM_UserInfo} 之类
     *                    是随 Inform 一起上来的，不必等能力树采完
     */
    public void upsertOnline(CpeSession session, List<String> informPaths) {
        try {
            LocalDateTime now = LocalDateTime.now();
            CpeDevice exist = cpeDeviceMapper.selectOne(
                    new LambdaQueryWrapper<CpeDevice>().eq(CpeDevice::getSn, session.getSn()));
            if (exist == null) {
                CpeDevice device = new CpeDevice();
                fill(device, session);
                device.setIsp(detectIsp(session.getSn(), informPaths, null));
                device.setStatus(STATUS_ONLINE);
                device.setLastContactTime(now);
                cpeDeviceMapper.insert(device);
                log.info("设备入库: sn={}, oui={}", session.getSn(), session.getOui());
                coreLog.online(session.getSn(), "首次上报入库");
                registerModel(session, device);
                return;
            }
            if (!STATUS_ONLINE.equals(exist.getStatus())) {
                // 从离线恢复：单独记一条，便于和设备掉线时间对上
                coreLog.online(session.getSn(), "离线后重新上报");
            }
            CpeDevice update = new CpeDevice();
            update.setId(exist.getId());
            fill(update, session);
            // 填上才更新：updateById 不动 null 字段，认不出来时保持库里原样
            update.setIsp(detectIsp(session.getSn(), informPaths, exist.getIsp()));
            update.setStatus(STATUS_ONLINE);
            update.setLastContactTime(now);
            cpeDeviceMapper.updateById(update);
        } catch (Exception e) {
            log.error("设备落库失败: sn={}", session.getSn(), e);
        }
    }

    /**
     * 按 Inform 上报的私有参数前缀认运营商，只补台账上还没有运营商的设备。
     *
     * <p>认出来有什么用：采集标准是按运营商分的（电信档逐层下钻、绕开 X_CTC_）。
     * 等采完能力树再认，第一轮只能按通用档先试递归——电信定制机对递归必回 9002，
     * 白等一整轮。Inform 里就带着前缀，进来就认，第一轮就能按对的标准采。</p>
     *
     * @param current 库里现有的运营商，非空表示人工申报或早先已认出，以它为准
     * @return 要写入的运营商代码，{@code null} 表示保持原样
     */
    private String detectIsp(String sn, List<String> informPaths, String current) {
        if (current != null && !current.isBlank()) {
            return null;
        }
        Isp detected = Isp.detect(informPaths);
        if (detected == null || detected == Isp.STANDARD) {
            return null;
        }
        log.info("按 Inform 私有前缀登记设备运营商: sn={}, isp={}", sn, detected.getCode());
        return detected.getCode();
    }

    /**
     * 按上报窗口清扫：超过窗口还没再上报的设备才置离线。
     *
     * <p>台账在线与否只能看最近上报时间。CWMP 是短连接，光猫报完一轮就把 TCP 断了，
     * 早先在连接关闭那条路径上直接标记离线，结果周期性上报的设备刚报完就被判离线，
     * 台账上在线时长几乎为零。这里统一按窗口判定，与服务重启、多实例部署都无关。</p>
     */
    @Scheduled(fixedDelayString = "${acs.session.offline-sweep-interval-millis:60000}")
    public void sweepExpiredOnline() {
        try {
            LocalDateTime deadline = LocalDateTime.now().minusSeconds(onlineWindowSeconds);
            List<CpeDevice> expired = cpeDeviceMapper.selectList(
                    new LambdaQueryWrapper<CpeDevice>()
                            .eq(CpeDevice::getStatus, STATUS_ONLINE)
                            .lt(CpeDevice::getLastContactTime, deadline));
            for (CpeDevice device : expired) {
                markOffline(device.getSn());
            }
        } catch (Exception e) {
            log.warn("按上报窗口清扫离线失败，忽略: {}", e.getMessage());
        }
    }

    /**
     * 标记离线；本就离线或尚未入库的设备直接跳过，避免无谓写库。
     */
    public void markOffline(String sn) {
        try {
            CpeDevice exist = cpeDeviceMapper.selectOne(
                    new LambdaQueryWrapper<CpeDevice>().eq(CpeDevice::getSn, sn));
            if (exist == null || STATUS_OFFLINE.equals(exist.getStatus())) {
                return;
            }
            CpeDevice update = new CpeDevice();
            update.setId(exist.getId());
            update.setStatus(STATUS_OFFLINE);
            cpeDeviceMapper.updateById(update);
            log.info("设备离线: sn={}", sn);
            coreLog.offline(sn, "会话结束或超时未上报");
        } catch (Exception e) {
            log.error("标记设备离线失败: sn={}", sn, e);
        }
    }

    /**
     * 新设备首次上线：按「运营商 + 型号 + 固件」登记型号档案。
     *
     * <p>同型号同固件只在库里存在一条记录：已登记的直接复用其适配结果（免人工适配），
     * 没登记的建一条待适配记录，等人工在型号映射页补齐一次映射。</p>
     *
     * <p>运营商只认设备自己上报的：光猫适配档案已迁到 SQLite 模板库，
     * 不再有 YAML 档案可供反推，没带就记 standard，后续可在型号档案上改。</p>
     */
    private void registerModel(CpeSession session, CpeDevice device) {
        try {
            String isp = (device.getIsp() == null || device.getIsp().isBlank())
                    ? "standard"
                    : device.getIsp().trim();
            modelMappingService.resolveOrRegister(isp, session.getManufacturer(), session.getProductClass(),
                    session.getSoftwareVersion(), session.getHardwareVersion(), session.getSn());
        } catch (Exception e) {
            log.warn("型号登记失败，不影响设备上线: sn={}, err={}", session.getSn(), e.getMessage());
        }
    }

    private void fill(CpeDevice device, CpeSession session) {
        device.setSn(session.getSn());
        device.setOui(session.getOui());
        device.setManufacturer(session.getManufacturer());
        device.setProductClass(session.getProductClass());
        device.setSoftwareVersion(session.getSoftwareVersion());
        device.setHardwareVersion(session.getHardwareVersion());
        device.setRootNode(session.getRootNode());
        device.setConnectionRequestUrl(session.getConnectionRequestUrl());
        device.setCwmpVersion(session.getCwmpVersion());
    }
}

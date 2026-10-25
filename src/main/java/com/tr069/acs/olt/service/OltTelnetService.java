package com.tr069.acs.olt.service;

import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.telnet.OltCharset;
import com.tr069.acs.olt.telnet.OltCliSession;
import com.tr069.acs.olt.telnet.OltSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

/**
 * OLT 命令行采集入口。
 *
 * <p>负责两件事：按配置超时打开会话、字符集自动识别失败时换 GBK 重试一次。
 * 命令与解析细节在 {@link OltCliSession} / {@code OltInfoParser} 里。</p>
 */
@Slf4j
@Service
public class OltTelnetService {

    @Value("${acs.olt.connect-timeout-ms:8000}")
    private int connectTimeoutMs;

    @Value("${acs.olt.login-timeout-ms:15000}")
    private int loginTimeoutMs;

    @Value("${acs.olt.command-timeout-ms:20000}")
    private int commandTimeoutMs;

    @Value("${acs.olt.max-raw-output-chars:60000}")
    private int maxRawOutputChars;

    /**
     * 登录并采集。
     *
     * @param device     连接参数（密码已还原为明文）
     * @param withBoards 是否采集板卡
     */
    public OltSnapshot collect(OltDevice device, boolean withBoards) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltSnapshot snapshot = doCollect(device, charset, withBoards);

        // 「自动」编码：回显里出现替换符说明按 UTF-8 解错了，用 GBK 再连一次
        if (OltCharset.isAuto(device.getCharset()) && garbled(snapshot)) {
            log.debug("OLT {} 回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                OltSnapshot retried = doCollect(device, OltCharset.fallback(), withBoards);
                if (!garbled(retried)) {
                    return retried;
                }
            } catch (IOException e) {
                log.debug("OLT {} GBK 重试失败，保留首次结果: {}", device.getHost(), e.getMessage());
            }
        }
        return snapshot;
    }

    private OltSnapshot doCollect(OltDevice device, Charset charset, boolean withBoards) throws IOException {
        try (OltCliSession session = OltCliSession.open(
                device, charset, connectTimeoutMs, loginTimeoutMs, commandTimeoutMs)) {
            return session.collect(withBoards);
        }
    }

    /**
     * 单条 ONT 详情查询（光信息 / 版本 / 注册历史）：登录一次、进 GPON 端口视图发命令。
     * 页面上点「光信息」「注册历史」这类图标时才触发，不进定时采集。
     */
    public String queryOnt(OltDevice device, int slot, int port, int ontId, String subcommand) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String out = doQueryOnt(device, charset, slot, port, ontId, subcommand);
        // 「自动」编码：回显里出现替换符说明按 UTF-8 解错了，用 GBK 再连一次
        if (OltCharset.isAuto(device.getCharset()) && out.indexOf('\uFFFD') >= 0) {
            return doQueryOnt(device, OltCharset.fallback(), slot, port, ontId, subcommand);
        }
        return out;
    }

    private String doQueryOnt(OltDevice device, Charset charset, int slot, int port,
                              int ontId, String subcommand) throws IOException {
        try (OltCliSession session = OltCliSession.open(
                device, charset, connectTimeoutMs, loginTimeoutMs, commandTimeoutMs)) {
            return session.queryOntDetail(slot, port, ontId, subcommand);
        }
    }

    /**
     * 单条 ONT 的配置摘录 + 业务流表格 + 详情，返回 {配置摘录, 业务流回显, 详情回显}，点序列号时触发。
     */
    public String[] queryOntConfigAndServicePorts(OltDevice device, int slot, int port, int ontId) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String[] outs = doQueryOntConfig(device, charset, slot, port, ontId);
        if (OltCharset.isAuto(device.getCharset())
                && (outs[0].indexOf('\uFFFD') >= 0 || outs[1].indexOf('\uFFFD') >= 0
                        || outs[2].indexOf('\uFFFD') >= 0)) {
            return doQueryOntConfig(device, OltCharset.fallback(), slot, port, ontId);
        }
        return outs;
    }

    private String[] doQueryOntConfig(OltDevice device, Charset charset, int slot, int port, int ontId)
            throws IOException {
        try (OltCliSession session = OltCliSession.open(
                device, charset, connectTimeoutMs, loginTimeoutMs, commandTimeoutMs)) {
            return session.queryOntConfigAndServicePorts(slot, port, ontId);
        }
    }

    /**
     * 单条 ONT 的业务流表格（display service-port port 0/x/x ont n），点业务流列「查看」时触发。
     */
    public String queryOntServicePorts(OltDevice device, int slot, int port, int ontId) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String out = doQueryOntServicePorts(device, charset, slot, port, ontId);
        if (OltCharset.isAuto(device.getCharset()) && out.indexOf('\uFFFD') >= 0) {
            return doQueryOntServicePorts(device, OltCharset.fallback(), slot, port, ontId);
        }
        return out;
    }

    private String doQueryOntServicePorts(OltDevice device, Charset charset, int slot, int port, int ontId)
            throws IOException {
        try (OltCliSession session = OltCliSession.open(
                device, charset, connectTimeoutMs, loginTimeoutMs, commandTimeoutMs)) {
            return session.queryServicePorts(slot, port, ontId);
        }
    }

    /**
     * 下发配置命令（DBA 模板增删这类写操作）：登录 → config 视图逐条执行。
     * 「自动」编码时回显解出替换符就用备用编码重发一遍，避免中文错误提示读不懂。
     */
    public List<String> sendConfig(OltDevice device, List<String> commands) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        List<String> outputs = doSendConfig(device, charset, commands);
        if (OltCharset.isAuto(device.getCharset())
                && outputs.stream().anyMatch(out -> out != null && out.indexOf('\uFFFD') >= 0)) {
            return doSendConfig(device, OltCharset.fallback(), commands);
        }
        return outputs;
    }

    private List<String> doSendConfig(OltDevice device, Charset charset, List<String> commands) throws IOException {
        try (OltCliSession session = OltCliSession.open(
                device, charset, connectTimeoutMs, loginTimeoutMs, commandTimeoutMs)) {
            return session.sendConfigCommands(commands);
        }
    }

    private boolean garbled(OltSnapshot snapshot) {
        return containsReplacement(snapshot.getVersionText())
                || containsReplacement(snapshot.getBoardText())
                || containsReplacement(snapshot.getTranscript());
    }

    private boolean containsReplacement(String text) {
        return text != null && text.indexOf('\uFFFD') >= 0;
    }

    /**
     * 回显落库前截断，避免一条异常输出撑爆记录表。
     */
    public String truncateRaw(String raw) {
        if (raw == null) {
            return null;
        }
        int limit = maxRawOutputChars > 0 ? maxRawOutputChars : 60000;
        return raw.length() <= limit ? raw : raw.substring(0, limit) + "\n...[已截断，原长 " + raw.length() + " 字符]";
    }
}

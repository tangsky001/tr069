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

/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.cpe.redis;

import com.PilotACS.cpe.server.CwmpService;
import com.PilotACS.common.log.Logs;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 会话 TTL 过期事件监听：cpe:session:{sn} 在 DB1 过期时由 Redis 推送事件， */
@Component
public class SessionExpiryListener implements MessageListener {

    private final CwmpService cwmpService;

    public SessionExpiryListener(CwmpService cwmpService) {
        this.cwmpService = cwmpService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String key = new String(message.getBody(), StandardCharsets.UTF_8);
            String sn = CpeRedisKeys.snOfSessionKey(key);
            if (sn != null) {
                cwmpService.endSessionBySn(sn, "Redis 会话 TTL 过期");
            }
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE, "处理会话过期事件失败", "原因", e.toString());
        }
    }
}

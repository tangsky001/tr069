/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.cpe.redis;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.common.log.Logs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/** 设备基础档案缓存 cpe:info:{sn}（DB1）：台账行 JSON，低频更新、长 TTL， */
@Component
public class DeviceInfoCache {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StringRedisTemplate template;
    private final CpeProperties props;

    public DeviceInfoCache(@Qualifier("sessionStringRedisTemplate") StringRedisTemplate template,
                           CpeProperties props) {
        this.template = template;
        this.props = props;
    }

    /** 读缓存，未命中/坏数据返回 null */
    public Map<String, Object> get(String sn) {
        if (sn == null || sn.isBlank()) {
            return null;
        }
        try {
            String v = template.opsForValue().get(CpeRedisKeys.info(sn));
            if (v == null || v.isBlank()) {
                return null;
            }
            return JSON.readValue(v, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Inform 刷新台账后同步覆写缓存 */
    public void put(String sn, Map<String, Object> deviceRow) {
        if (sn == null || sn.isBlank() || deviceRow == null) {
            return;
        }
        try {
            template.opsForValue().set(CpeRedisKeys.info(sn),
                    JSON.writeValueAsString(deviceRow),
                    Duration.ofSeconds(Math.max(60, props.getDeviceInfoTtlSeconds())));
        } catch (Exception e) {
            Logs.debug(Logs.Module.CPE, "设备档案缓存写入失败", "sn", sn, "原因", e.getMessage());
        }
    }

    /** 台账结构变化时失效（保守清理） */
    public void evict(String sn) {
        try {
            template.delete(CpeRedisKeys.info(sn));
        } catch (Exception ignore) {
            // TTL 兜底
        }
    }
}

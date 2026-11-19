/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.cpe.redis;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.common.log.Logs;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 设备存量参数树缓存 cpe:params:tree:{sn}（DB1）：
 * 每次 Inform 都要从 SQLite 全量灌树（几千节点），周期上报场景下是热点读。
 * 缓存以"落库时主动失效"为一致性手段（{@code CpeStore.saveDeviceParams} 是
 * cpe_device_param 的唯一写入点），TTL 只作兜底；Redis 异常时降级直读 DB。
 */
@Component
public class ParamTreeCache {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, CpeParam>> TREE_TYPE =
            new TypeReference<>() {
            };

    private final StringRedisTemplate template;
    private final CpeProperties props;

    public ParamTreeCache(@Qualifier("sessionStringRedisTemplate") StringRedisTemplate template,
                          CpeProperties props) {
        this.template = template;
        this.props = props;
    }

    /** 读缓存，未命中/Redis 异常时回源 loader（SQLite 全量查询）并回填 */
    public Map<String, CpeParam> getOrLoad(String sn, Function<String, Map<String, CpeParam>> loader) {
        String key = CpeRedisKeys.paramTree(sn);
        try {
            String v = template.opsForValue().get(key);
            if (v != null) {
                if (v.isBlank()) {
                    // 空树也缓存，避免"设备没采集过参数"反复打 DB
                    return new LinkedHashMap<>();
                }
                return JSON.readValue(v, TREE_TYPE);
            }
        } catch (Exception e) {
            Logs.debug(Logs.Module.CPE, "参数树缓存读取失败，回源 DB", "sn", sn, "原因", e.getMessage());
        }
        Map<String, CpeParam> loaded = loader.apply(sn);
        if (loaded == null) {
            loaded = new LinkedHashMap<>();
        }
        put(sn, key, loaded);
        return loaded;
    }

    /** 参数树落库后失效（saveDeviceParams 是唯一写入点，删缓存即回源最新树） */
    public void evict(String sn) {
        try {
            template.delete(CpeRedisKeys.paramTree(sn));
        } catch (Exception ignore) {
            // TTL 兜底
        }
    }

    private void put(String sn, String key, Map<String, CpeParam> tree) {
        try {
            template.opsForValue().set(key, JSON.writeValueAsString(tree),
                    Duration.ofSeconds(Math.max(60, props.getParamTreeTtlSeconds())));
        } catch (Exception e) {
            Logs.debug(Logs.Module.CPE, "参数树缓存写入失败（下次回源 DB）", "sn", sn, "原因", e.getMessage());
        }
    }
}

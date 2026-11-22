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

/** 设备存量参数树缓存 cpe:params:tree:{sn} */
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

    /** 读缓存，未命中时回源并回填 */
    public Map<String, CpeParam> getOrLoad(String sn, Function<String, Map<String, CpeParam>> loader) {
        String key = CpeRedisKeys.paramTree(sn);
        try {
            String v = template.opsForValue().get(key);
            if (v != null) {
                if (v.isBlank()) {
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

    /** 参数树落库后失效 */
    public void evict(String sn) {
        try {
            template.delete(CpeRedisKeys.paramTree(sn));
        } catch (Exception ignore) {
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

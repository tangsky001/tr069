package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 适配管理：识别运营商并分发到对应运营商的适配实现，新增运营商加一个 IspAdaptation 实现即可 */
@Component
public class AdaptationManager {

    private final List<IspAdaptation> adaptations;

    public AdaptationManager(List<IspAdaptation> adaptations) {
        this.adaptations = adaptations.stream()
                .sorted(Comparator.comparingInt(a -> a.isp().ordinal()))
                .toList();
    }

    /** 按运营商代码取适配实现，写法随意（CM/CMCC/移动都收敛到枚举） */
    public IspAdaptation of(String ispCode) {
        Isp isp = Isp.of(ispCode);
        return adaptations.stream()
                .filter(a -> a.isp() == isp)
                .findFirst()
                .orElseThrow();
    }

    /** 从能力树 / Inform 参数里识别运营商，认不出返回 null */
    public IspAdaptation detect(List<String> paths) {
        Isp isp = Isp.detect(paths);
        return isp == null || isp == Isp.STANDARD ? null : of(isp.getCode());
    }

    public List<IspAdaptation> all() {
        return adaptations;
    }

    /** 运营商代码 -> 中文名 */
    public Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (IspAdaptation a : adaptations) {
            labels.put(a.code(), a.label());
        }
        return labels;
    }
}

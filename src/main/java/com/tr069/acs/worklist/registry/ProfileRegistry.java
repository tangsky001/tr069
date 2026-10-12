package com.tr069.acs.worklist.registry;

import com.tr069.acs.worklist.loader.LoadedItem;
import com.tr069.acs.worklist.model.DeviceProfile;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 光猫适配档案注册表。
 */
@Component
public class ProfileRegistry extends ConfigRegistry<DeviceProfile> {

    /**
     * 返回全部有效档案，按 priority 升序（数值小的优先匹配）。
     */
    public List<DeviceProfile> orderedProfiles() {
        return validValues().stream()
                .sorted(Comparator.comparingInt(DeviceProfile::getPriority))
                .collect(Collectors.toList());
    }

    /**
     * 返回档案简要视图，供管理端列表展示。
     */
    public List<LoadedItem<DeviceProfile>> ordersByKey() {
        return all().stream()
                .sorted(Comparator.comparing(i -> i.getValue() == null
                        ? i.getRelativePath() : i.getValue().getKey()))
                .collect(Collectors.toList());
    }
}

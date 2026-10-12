package com.tr069.acs.worklist.registry;

import com.tr069.acs.worklist.loader.LoadedItem;
import com.tr069.acs.worklist.model.WorklistTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 工单模板注册表。
 */
@Component
public class WorklistRegistry extends ConfigRegistry<WorklistTemplate> {

    /**
     * 按运营商查询工单模板。
     */
    public List<WorklistTemplate> byIsp(String isp) {
        List<WorklistTemplate> result = new ArrayList<>();
        for (WorklistTemplate t : validValues()) {
            if (isp == null || isp.isBlank() || isp.equalsIgnoreCase(t.getIsp())) {
                result.add(t);
            }
        }
        return result;
    }

    /**
     * 按运营商 + 规范版本 + 业务域 + 设备类型查询。
     */
    public List<WorklistTemplate> find(String isp, String version, String domain, String deviceType) {
        return validValues().stream()
                .filter(t -> matches(t.getIsp(), isp))
                .filter(t -> matches(t.getVersion(), version))
                .filter(t -> matches(t.getDomain(), domain))
                .filter(t -> matches(t.getDeviceType(), deviceType))
                .sorted(Comparator.comparingInt(WorklistTemplate::getPriority))
                .toList();
    }

    /**
     * 返回全部条目并按 key 排序，供管理端展示。
     */
    public List<LoadedItem<WorklistTemplate>> ordersByKey() {
        return all().stream()
                .sorted(Comparator.comparing(i -> i.getValue() == null
                        ? i.getRelativePath() : i.getValue().getKey()))
                .toList();
    }

    private boolean matches(String actual, String expect) {
        return expect == null || expect.isBlank() || expect.equalsIgnoreCase(actual);
    }
}

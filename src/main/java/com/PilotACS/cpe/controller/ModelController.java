/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.controller;

import com.PilotACS.cpe.model.IspVariant;
import com.PilotACS.cpe.service.ModelProfileService;
import com.PilotACS.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机型模板库接口（前端 /models/**）
 */
@RestController
@RequestMapping("/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelProfileService modelProfileService;

    @GetMapping("/adapt-status")
    public Result<Map<String, Object>> adaptStatus(@RequestParam(required = false) String sns) {
        List<String> list = sns == null || sns.isBlank()
                ? List.of() : Arrays.asList(sns.split(","));
        return Result.ok(modelProfileService.adaptStatus(list));
    }

    /** 能力申报候选值（运营商/网口数/网口速率/WIFI 规格），取值口径由后端统一 */
    @GetMapping("/capability-options")
    public Result<Map<String, Object>> capabilityOptions() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> isps = new ArrayList<>();
        for (IspVariant v : IspVariant.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("v", v.code());
            item.put("t", v.label());
            isps.add(item);
        }
        out.put("isp", isps);
        out.put("ports", List.of("1", "2", "4", "8"));
        out.put("speeds", List.of("10/100", "10/100/1000", "10/100/1000/2500"));
        // 只保留能实测到的频段口径：检测端只识别 2.4G / 5G，给 6G 必判不满足
        out.put("wifiSpecs", List.of("2.4G", "2.4G+5G"));
        return Result.ok(out);
    }
}

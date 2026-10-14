package com.tr069.acs.worklist.controller;

import com.tr069.acs.common.Result;
import com.tr069.acs.worklist.service.ModelAdaptationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 机型适配接口。
 *
 * <p>设备列表按 sn 批量查库里有没有该型号的能力树 XML——有 = 已适配（直接调库），
 * 没有 = 列表上出「适配」入口，申报 运营商/网口/速率/SIP/WIFI 后向光猫下发采集
 * 做能力测试，通过才导出型号 XML 并落 {@code cpe_model}。</p>
 */
@RestController
@RequiredArgsConstructor
public class ModelAdaptationController {

    private final ModelAdaptationService service;

    /** 单台设备的适配状态。 */
    @GetMapping("/api/device/{sn}/adaptation/status")
    public Result<Map<String, Object>> status(@PathVariable String sn) {
        return Result.success(service.status(sn));
    }

    /** 整页设备批量适配状态，sns 逗号分隔。 */
    @GetMapping("/api/models/adapt-status")
    public Result<Map<String, Map<String, Object>>> statusBatch(@RequestParam String sns) {
        List<String> list = Arrays.stream(sns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return Result.success(service.statusBatch(list));
    }

    /**
     * 能力测试：向光猫下发采集 RPC，解析实测能力并与申报比对。
     * 申报单为 运营商 / 网口数量 / 网口速率 / SIP 数量 / WIFI 规格。
     */
    @PostMapping("/api/device/{sn}/adaptation/test")
    public Result<Map<String, Object>> test(@PathVariable String sn,
                                            @RequestBody ModelAdaptationService.AdaptRequest request) {
        return Result.success(service.test(sn, request));
    }

    /**
     * 测试通过后保存：导出型号能力树 XML（models/{运营商}/{型号}.xml）并写库，
     * 下次同运营商同型号不再适配，直接调库。
     */
    @PostMapping("/api/device/{sn}/adaptation/save")
    public Result<Map<String, Object>> save(@PathVariable String sn,
                                            @RequestBody ModelAdaptationService.AdaptRequest request) {
        try {
            return Result.success(service.save(sn, request));
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }
}

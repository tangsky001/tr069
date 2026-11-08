package com.tr069.acs.api.controller;

import com.tr069.acs.api.service.OpsEnvironmentService;
import com.tr069.acs.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 运维管理 */
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsController {

    private final OpsEnvironmentService service;

    /** 运维环境总览：系统版本、服务器、数据库、Redis、Git */
    @GetMapping("/environment")
    public Result<Map<String, List<Map<String, String>>>> environment() {
        return Result.success(service.environment());
    }
}

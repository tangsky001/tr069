package com.tr069.acs.api.controller;

import com.tr069.acs.acs.service.DeviceBusinessService;
import com.tr069.acs.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 设备业务信息：WAN 连接、WLAN、SIP 语音 */
@Slf4j
@RestController
@RequestMapping("/api/device/{sn}/business")
@RequiredArgsConstructor
public class DeviceBusinessController {

    private final DeviceBusinessService businessService;

    /** 业务信息汇总：wan / wifi / sip 三个分区，各分区内按实例对象分组 */
    @GetMapping
    public Result<Map<String, Object>> summary(@PathVariable String sn) {
        return Result.success(businessService.summary(sn));
    }

    /** 重新采集：向在线设备下发 GetParameterValues 读取业务参数后回库解析 */
    @PostMapping("/collect")
    public Result<Map<String, Object>> collect(@PathVariable String sn) {
        return Result.success(businessService.collect(sn));
    }
}

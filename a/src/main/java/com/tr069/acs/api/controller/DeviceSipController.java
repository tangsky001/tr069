package com.tr069.acs.api.controller;

import com.tr069.acs.acs.service.DeviceSipService;
import com.tr069.acs.common.Result;
import com.tr069.acs.worklist.model.SipEdit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** SIP 语音：可编辑线路清单 + 下发 */
@Slf4j
@RestController
@RequestMapping("/api/device/{sn}/sip")
@RequiredArgsConstructor
public class DeviceSipController {

    private final DeviceSipService sipService;

    /** 可编辑的语音线路清单：每路带当前生效的账号、服务器、端口（没配的按 5060） */
    @GetMapping("/lines")
    public Result<List<Map<String, Object>>> lines(@PathVariable String sn) {
        return Result.success(sipService.lines(sn));
    }

    /** 按路下发语音配置，返回逐路结果：submitted / failed / worklists / failures */
    @PostMapping
    public Result<Map<String, Object>> submit(@PathVariable String sn,
                                              @RequestBody(required = false) SipEdit body) {
        try {
            return Result.success(sipService.submit(sn, body, body == null ? null : body.getUsername()));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("SIP 下发异常 sn={}", sn, e);
            return Result.failure("SIP 下发失败：" + e.getMessage());
        }
    }
}

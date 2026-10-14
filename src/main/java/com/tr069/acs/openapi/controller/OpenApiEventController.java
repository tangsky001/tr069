package com.tr069.acs.openapi.controller;

import com.tr069.acs.common.Result;
import com.tr069.acs.openapi.entity.WebhookDelivery;
import com.tr069.acs.openapi.entity.WebhookSubscription;
import com.tr069.acs.openapi.model.NorthboundEvent;
import com.tr069.acs.openapi.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 北向事件订阅管理。
 *
 * <p>订阅一旦生效，设备上下线、下发任务结束都会 POST 到回调地址，
 * 带 {@code X-ACS-Event-Id}（接收方据此去重）与 {@code X-ACS-Signature}（校验来源）。</p>
 */
@Slf4j
@RestController
@RequestMapping("${acs.openapi.path-prefix:/openapi/v1}/events")
@RequiredArgsConstructor
public class OpenApiEventController {

    private final WebhookService webhookService;

    /**
     * 支持的事件类型清单，新增订阅时按需挑选。
     */
    @GetMapping("/types")
    public Result<List<String>> types() {
        return Result.success(List.of(
                NorthboundEvent.DEVICE_ONLINE,
                NorthboundEvent.DEVICE_OFFLINE,
                NorthboundEvent.DELIVERY_FINISHED));
    }

    /**
     * 订阅列表。
     */
    @GetMapping("/subscriptions")
    public Result<List<WebhookSubscription>> subscriptions() {
        return Result.success(webhookService.subscriptions());
    }

    /**
     * 新增或修改订阅。
     */
    @PostMapping("/subscriptions")
    public Result<WebhookSubscription> save(@RequestBody WebhookSubscription subscription) {
        return Result.success(webhookService.save(subscription));
    }

    /**
     * 删除订阅。
     */
    @DeleteMapping("/subscriptions/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        webhookService.delete(id);
        return Result.success(true);
    }

    /**
     * 投递记录：查某个订阅最近推过什么、对方回了什么状态码。
     */
    @GetMapping("/subscriptions/{id}/deliveries")
    public Result<List<WebhookDelivery>> deliveries(@PathVariable Long id,
                                                    @RequestParam(defaultValue = "20") int limit) {
        return Result.success(webhookService.deliveries(id, limit));
    }

    /**
     * 全部投递记录，排障时全局看一眼。
     */
    @GetMapping("/deliveries")
    public Result<List<WebhookDelivery>> recent(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(webhookService.deliveries(null, limit));
    }

    /**
     * 发一条测试事件，方便对接方验证回调地址与签名。
     */
    @PostMapping("/test")
    public Result<Map<String, Object>> test() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "这是一条测试事件");
        webhookService.publish(NorthboundEvent.of("event.test", data));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", true);
        result.put("note", "推送结果见 /events/deliveries");
        return Result.success(result);
    }
}

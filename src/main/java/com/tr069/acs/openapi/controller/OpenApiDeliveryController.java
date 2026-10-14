package com.tr069.acs.openapi.controller;

import com.tr069.acs.common.Result;
import com.tr069.acs.exec.IntentDeliveryService;
import com.tr069.acs.exec.entity.DeviceTask;
import com.tr069.acs.exec.model.DeliveryRequest;
import com.tr069.acs.exec.service.OpenApiDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 北向下发接口：查意图模板、提交开通任务、查执行结果。
 *
 * <p>任务是异步的：ACS 只能在设备主动上报后的空 POST 里捎带指令，什么时候能发出去
 * 取决于设备的心跳周期。接口返回 {@code PENDING/RUNNING} 属于正常，最终结果靠
 * 轮询 {@code GET /deliveries/{id}} 或订阅 Webhook 获取。</p>
 */
@Slf4j
@RestController
@RequestMapping("${acs.openapi.path-prefix:/openapi/v1}/deliveries")
@RequiredArgsConstructor
public class OpenApiDeliveryController {

    private final OpenApiDeliveryService deliveryService;

    private final IntentDeliveryService intentDeliveryService;

    /**
     * 可用业务类型与对应的业务对象，便于调用方核对 category 取值。
     */
    @GetMapping("/meta")
    public Result<Map<String, Object>> meta() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("bizObjects", intentDeliveryService.bizObjects());
        data.put("templates", deliveryService.templates());
        return Result.success(data);
    }

    /**
     * 提交下发任务。
     *
     * <p>{@code taskNo} 由调用方生成并带过来，同一 {@code taskNo} 重复提交只会受理一次，
     * 网络重试不会导到设备上重复开业务。</p>
     */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        DeliveryRequest request = deliveryService.parse(body);
        DeviceTask task = intentDeliveryService.deliver(request);
        return Result.success(intentDeliveryService.view(task));
    }

    /**
     * 按任务 id 查执行结果。
     */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> get(@PathVariable Long id) {
        DeviceTask task = intentDeliveryService.get(id);
        if (task == null) {
            return Result.failure("任务不存在: " + id);
        }
        return Result.success(intentDeliveryService.view(task));
    }

    /**
     * 按外部单号查执行结果，调用方拿不到内部 id 时用这个。
     */
    @GetMapping(params = "taskNo")
    public Result<Map<String, Object>> getByTaskNo(@RequestParam String taskNo) {
        DeviceTask task = intentDeliveryService.findByTaskNo(
                DeviceTask.SOURCE_OPENAPI, taskNo);
        if (task == null) {
            return Result.failure("任务不存在: " + taskNo);
        }
        return Result.success(intentDeliveryService.view(task));
    }

    /**
     * 最近的下发任务，支持按状态与设备过滤。
     */
    @GetMapping
    public Result<List<Map<String, Object>>> list(
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(deliveryService.list(sn, status, limit));
    }
}

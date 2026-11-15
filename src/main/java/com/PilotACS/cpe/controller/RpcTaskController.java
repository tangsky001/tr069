/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.controller;

import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下发任务进度查询（前端轮询 /rpc/{taskId}）
 */
@RestController
@RequestMapping("/rpc")
@RequiredArgsConstructor
public class RpcTaskController {

    private final CpeStore store;

    @GetMapping("/{taskId}")
    public Result<Map<String, Object>> task(@PathVariable long taskId) {
        Map<String, Object> row = store.getTask(taskId);
        if (row == null) {
            throw new BusinessException(404, "任务不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", row.get("id"));
        out.put("taskNo", row.get("task_no"));
        String status = String.valueOf(row.get("status"));
        out.put("status", status);
        out.put("statusText", switch (status) {
            case "queued" -> "排队中";
            case "running" -> "执行中";
            case "success" -> "已完成";
            case "fail" -> "失败";
            default -> status;
        });
        out.put("statusLevel", switch (status) {
            case "success" -> "success";
            case "fail" -> "error";
            default -> "info";
        });
        out.put("resultContent", row.get("result_content"));
        out.put("faultCode", row.get("fault_code"));
        out.put("faultString", row.get("fault_string"));
        out.put("createdAt", row.get("created_at"));
        out.put("finishedAt", row.get("finished_at"));
        return Result.ok(out);
    }
}

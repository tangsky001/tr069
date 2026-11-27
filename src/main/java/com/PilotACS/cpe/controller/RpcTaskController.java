/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.controller;

import com.PilotACS.acl.DeviceAclService;
import com.PilotACS.cpe.server.CwmpService;
import com.PilotACS.cpe.server.TaskWaitRegistry;
import com.PilotACS.cpe.session.CwmpSession;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 下发任务进度查询（前端轮询 /rpc/{taskId}） */
@RestController
@RequestMapping("/rpc")
@RequiredArgsConstructor
public class RpcTaskController {

    private final CpeStore store;
    private final CwmpService cwmpService;
    private final TaskWaitRegistry taskWaitRegistry;
    private final DeviceAclService aclService;

    /** 任务归属权限校验 */
    private void requireOnu(String sn, String action) {
        if (!aclService.allowedOnu(UserContext.get(), sn, action)) {
            throw new BusinessException(403, "无该光猫的操作权限");
        }
    }

    @GetMapping("/{taskId}")
    public Result<Map<String, Object>> task(@PathVariable long taskId) {
        Map<String, Object> row = store.getTask(taskId);
        if (row == null) {
            throw new BusinessException(404, "任务不存在");
        }
        requireOnu(String.valueOf(row.get("sn")), "view");
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

    /** 取消任务（仅排队中可取消） */
    @PostMapping("/{taskId}/cancel")
    public Result<Void> cancel(@PathVariable long taskId) {
        Map<String, Object> row = store.getTask(taskId);
        if (row == null) {
            throw new BusinessException(404, "任务不存在");
        }
        String sn = String.valueOf(row.get("sn"));
        requireOnu(sn, "operate");
        var lock = cwmpService.lockFor(sn);
        lock.lock();
        try {
            row = store.getTask(taskId);
            if (row == null) {
                throw new BusinessException(404, "任务不存在");
            }
            String status = String.valueOf(row.get("status"));
            if ("success".equals(status) || "fail".equals(status)) {
                throw new BusinessException("任务已结束，无法取消");
            }
            CwmpSession s = cwmpService.findSessionBySn(sn);
            if (s != null && !s.isEnded()) {
                if (s.getCurrent() != null && s.getCurrent().getTaskId() == taskId) {
                    throw new BusinessException("任务正在执行中，无法取消");
                }
                s.getQueue().removeIf(r -> r.getTaskId() == taskId);
            }
            store.updateTask(taskId, "fail", "cancel", "人工取消", null);
            taskWaitRegistry.signalDone(taskId);
        } finally {
            lock.unlock();
        }
        return Result.ok();
    }
}

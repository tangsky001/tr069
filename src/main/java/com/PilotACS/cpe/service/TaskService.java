/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.model.TaskRun;
import com.PilotACS.cpe.rpc.TaskPlanner;
import com.PilotACS.cpe.server.CwmpService;
import com.PilotACS.cpe.session.CwmpSession;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务编排入口：前端下发 → cpe_task 落库 → 设备在线即执行（离线先反向唤醒）
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    private final CpeStore store;
    private final CwmpService cwmpService;
    private final TaskPlanner planner;
    private final ConnectionRequestService connectionRequestService;
    private final CpeProperties props;

    /** 建任务。设备在会话中直接插队执行；离线则先发 Connection Request 唤醒 */
    public long createTask(String sn, String type, Map<String, Object> params, String createdBy) {
        long id = store.insertTask(sn, type, params == null ? Map.of() : params, createdBy);
        CwmpSession session = cwmpService.findSessionBySn(sn);
        if (session != null && !session.isEnded()) {
            // 会话活着：组装成步骤直接入队（state 1 空 POST / state 2 收尾时被取走）
            Map<String, Object> row = store.getTask(id);
            String root = BusinessPaths.detectRoot(session.getData(), props.getDefaultRootNode());
            TaskRun run = planner.build(row, session.getData(), root, session.getModelParams());
            session.getQueue().add(run);
            Logs.info(Logs.Module.CPE, "任务入会话队列", "sn", sn, "任务", id, "类型", type);
        } else {
            // 离线/无会话：等设备下次 Inform 时装载；顺手反向连接唤醒一次
            Map<String, Object> device = null;
            try {
                device = store.findDevice(sn);
            } catch (Exception ignore) {
                // 设备还没上报过
            }
            String crUrl = device == null ? null : str(device.get("connection_request_url"));
            if (crUrl != null && !crUrl.isBlank()) {
                // 只用配置账密唤醒；设备上报的 CR 用户名（密码不可知）不再参与猜配。
                // 带重试退避（异步），不再在 REST 线程里同步等 3s 超时
                connectionRequestService.wakeWithRetry(sn, crUrl,
                        paramEndingWith(sn, "ManagementServer.ConnectionRequestUsername"),
                        paramEndingWith(sn, "ManagementServer.ConnectionRequestPassword"));
            }
            Logs.info(Logs.Module.CPE, "任务排队等待设备上线", "sn", sn, "任务", id, "类型", type);
        }
        return id;
    }

    /** 轮询等任务完成（同步采集类接口用） */
    public Map<String, Object> awaitTask(long taskId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> task = store.getTask(taskId);
            if (task == null) {
                return null;
            }
            String status = str(task.get("status"));
            if ("success".equals(status) || "fail".equals(status)) {
                return task;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return store.getTask(taskId);
    }

    /** 任务结果 JSON → Map */
    public Map<String, Object> resultOf(Map<String, Object> task) {
        Object rc = task.get("result_content");
        if (rc instanceof String s && !s.isBlank()) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Map.class);
            } catch (Exception ignore) {
                // 结果不是 JSON
            }
        }
        return new LinkedHashMap<>();
    }

    /** 参数树里按后缀取值（如 ManagementServer.ConnectionRequestUsername） */
    private String paramEndingWith(String sn, String suffix) {
        try {
            for (Map.Entry<String, com.PilotACS.cpe.model.CpeParam> e : store.loadParams(sn).entrySet()) {
                if (!e.getKey().endsWith(suffix) || e.getValue() == null) {
                    continue;
                }
                String v = e.getValue().value();
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        } catch (Exception ignore) {
            // 设备还没采集参数树：退回配置里的默认账号
        }
        return null;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

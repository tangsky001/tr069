/**
 * @author: tangsky001
 * @date:   2026年11月16日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.model.TaskRun;
import com.PilotACS.cpe.rpc.TaskPlanner;
import com.PilotACS.cpe.session.CwmpSession;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 首次上报初始化下发 */
@Service
@RequiredArgsConstructor
public class InitProvisionService {

    /** 系统自动任务创建人标识 */
    public static final String CREATED_BY = "system-init";
    /** 任务参数里的初始化标记 */
    public static final String PARAM_FLAG = "initProvision";

    /** 已排过初始化的 SN */
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
    /** 下发失败次数 */
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();

    private static final int MAX_TRACKED_SNS = 50_000;

    private void trackScheduled(String sn) {
        if (scheduled.size() >= MAX_TRACKED_SNS) {
            scheduled.clear();
        }
        scheduled.add(sn);
    }

    private final CpeStore store;
    private final CpeProperties props;
    private final TaskPlanner planner;

    /** 未下发过则排初始化 SPV 到会话队首 */
    public void provisionIfNeeded(CwmpSession s) {
        String sn = s.getSn();
        if (sn == null || scheduled.contains(sn)) {
            return;
        }
        try {
            if (store.isInitProvisioned(sn)) {
                trackScheduled(sn);
                return;
            }
            if (store.countActiveTasks(sn, TaskPlanner.TYPE_SPV, CREATED_BY) > 0) {
                return;
            }
            long taskId = schedule(s);
            trackScheduled(sn);
            Logs.info(Logs.Module.CPE_PUSH, "首次上报，下发反向连接账密与周期上报",
                    "sn", sn, "任务", taskId,
                    "反向连接账号", props.getCrUsername(),
                    "周期上报秒", props.getInitPeriodicInformSeconds());
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE_PUSH, "首次下发初始化参数失败", "sn", sn, "原因", e.toString());
        }
    }

    /** 设备恢复出厂后清去重与失败计数 */
    public void resetForBootstrap(String sn) {
        if (sn == null) {
            return;
        }
        scheduled.remove(sn);
        failures.remove(sn);
    }

    /** 下发失败后放开去重，连续 3 次失败则放弃 */
    public void retryLater(String sn) {
        if (sn == null) {
            return;
        }
        int n = failures.merge(sn, 1, Integer::sum);
        if (n >= 3) {
            trackScheduled(sn);
            Logs.warn(Logs.Module.CPE_PUSH, "首次初始化参数多次下发失败，停止自动重试", "sn", sn, "失败次数", n);
        } else {
            scheduled.remove(sn);
        }
    }

    private long schedule(CwmpSession s) {
        String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
        List<Map<String, Object>> values = new ArrayList<>();
        values.add(nv(root + "ManagementServer.ConnectionRequestUsername", props.getCrUsername(), "string"));
        values.add(nv(root + "ManagementServer.ConnectionRequestPassword", props.getCrPassword(), "string"));
        values.add(nv(root + "ManagementServer.PeriodicInformInterval",
                String.valueOf(props.getInitPeriodicInformSeconds()), "unsignedInt"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("values", values);
        params.put(PARAM_FLAG, true);

        long taskId = store.insertTask(s.getSn(), TaskPlanner.TYPE_SPV, params, CREATED_BY);
        Map<String, Object> row = store.getTask(taskId);
        TaskRun run = planner.build(row, s.getData(), root, s.getModelParams());
        s.getQueue().addFirst(run);
        return taskId;
    }

    private Map<String, Object> nv(String name, String value, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value == null ? "" : value);
        m.put("type", type);
        return m;
    }
}

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

/** 首次上报初始化下发（以 SN 为标识，只下发一次）： */
@Service
@RequiredArgsConstructor
public class InitProvisionService {

    /** 系统自动任务的创建人标识（用于防重复排队） */
    public static final String CREATED_BY = "system-init";
    /** 任务参数里的初始化标记：SPV 成功后据此回写台账 */
    public static final String PARAM_FLAG = "initProvision";

    /** 本次启动内已排过初始化的 SN（同一会话/重复 Inform 不重复排）。 */
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
    /** 下发失败次数：达到上限后不再自动重试，避免设备不支持时每轮刷一次 */
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();

    /** 内存护栏：进程内缓存最大条目数（按 SN 记录，正常设备规模远小于该值） */
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

    /** Inform 时调用：查库判断是否已下发，未下发则排 SPV 到当前会话队首 */
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
                // 已有在队/在跑的初始化任务：等它执行，避免重复下发
                return;
            }
            long taskId = schedule(s);
            trackScheduled(sn);
            Logs.info(Logs.Module.CPE, "首次上报，下发反向连接账密与周期上报",
                    "sn", sn, "任务", taskId,
                    "反向连接账号", props.getCrUsername(),
                    "周期上报秒", props.getInitPeriodicInformSeconds());
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE, "首次下发初始化参数失败", "sn", sn, "原因", e.toString());
        }
    }

    /** 设备恢复出厂（0 BOOTSTRAP）：清掉内存去重与失败计数，允许初始化参数重新下发 */
    public void resetForBootstrap(String sn) {
        if (sn == null) {
            return;
        }
        scheduled.remove(sn);
        failures.remove(sn);
    }

    /** 下发失败（设备 Fault）：放开去重等下次 Inform 重试；连续失败 3 次则放弃自动下发 */
    public void retryLater(String sn) {
        if (sn == null) {
            return;
        }
        int n = failures.merge(sn, 1, Integer::sum);
        if (n >= 3) {
            trackScheduled(sn);
            Logs.warn(Logs.Module.CPE, "首次初始化参数多次下发失败，停止自动重试", "sn", sn, "失败次数", n);
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
        // 插队首：首次上报先把管理通道参数落好，再做整树探测这类重活
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

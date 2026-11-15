/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.rpc;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.DeviceData;
import com.PilotACS.cpe.model.RpcRequest;
import com.PilotACS.cpe.model.TaskRun;
import com.PilotACS.cpe.service.BusinessPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务 → RPC 步骤展开器（对应 genieacs 把 task 翻译成内置 provision 的那层）
 */
@Component
@RequiredArgsConstructor
public class TaskPlanner {

    public static final String TYPE_GPV = "GetParameterValues";
    public static final String TYPE_GPN = "GetParameterNames";
    public static final String TYPE_SPV = "SetParameterValues";
    public static final String TYPE_ADD = "AddObject";
    public static final String TYPE_DEL = "DeleteObject";
    public static final String TYPE_REBOOT = "Reboot";
    public static final String TYPE_FACTORY_RESET = "FactoryReset";
    public static final String TYPE_DOWNLOAD = "Download";
    public static final String TYPE_METHODS = "GetRPCMethods";
    public static final String TYPE_CUSTOM = "Custom";
    /** 业务参数刷新：有模板直接按模板 GPV，无模板先 GPN 扫描再 GPV */
    public static final String TYPE_REFRESH_BIZ = "RefreshBusiness";
    /** 机型适配探测：GPN 全树 + 关键参数 GPV */
    public static final String TYPE_ADAPT_PROBE = "AdaptProbe";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CpeProperties props;

    @SuppressWarnings("unchecked")
    public TaskRun build(Map<String, Object> taskRow, DeviceData data, String root,
                         Map<String, CpeParam> modelParams) {
        TaskRun run = new TaskRun();
        run.setTaskId(((Number) taskRow.get("id")).longValue());
        Object no = taskRow.get("task_no");
        run.setTaskNo(no == null ? "" : String.valueOf(no));
        run.setType(String.valueOf(taskRow.get("type")));
        Object pj = taskRow.get("params_json");
        if (pj instanceof String s && !s.isBlank()) {
            try {
                run.setParams(JSON.readValue(s, Map.class));
            } catch (Exception ignore) {
                run.setParams(new LinkedHashMap<>());
            }
        }
        String key = "task-" + run.getTaskId();
        Map<String, Object> params = run.getParams();
        switch (run.getType()) {
            case TYPE_GPV -> {
                List<String> names = strList(params.get("names"));
                if (names.isEmpty()) {
                    names = strList(params.get("paths"));
                }
                appendGpv(run, names, "collect");
            }
            case TYPE_GPN -> {
                String path = str(params.get("path"));
                if (path == null || path.isBlank()) {
                    path = root;
                }
                boolean nextLevel = Boolean.TRUE.equals(params.get("nextLevel"));
                run.getSteps().add(new TaskRun.RpcStep(RpcRequest.gpn(path, nextLevel, key), "collect"));
            }
            case TYPE_SPV -> {
                List<RpcRequest.NameValue> values = nameValues(params);
                run.getSteps().add(new TaskRun.RpcStep(RpcRequest.spv(values, key), "apply"));
            }
            case TYPE_ADD -> {
                RpcRequest r = RpcRequest.of(RpcRequest.Kind.ADD_OBJECT, key);
                r.setPath(str(params.get("path")));
                run.getSteps().add(new TaskRun.RpcStep(r, "apply"));
            }
            case TYPE_DEL -> {
                RpcRequest r = RpcRequest.of(RpcRequest.Kind.DELETE_OBJECT, key);
                r.setPath(str(params.get("path")));
                run.getSteps().add(new TaskRun.RpcStep(r, "apply"));
            }
            case TYPE_REBOOT -> run.getSteps().add(new TaskRun.RpcStep(
                    RpcRequest.of(RpcRequest.Kind.REBOOT, key), "apply"));
            case TYPE_FACTORY_RESET -> run.getSteps().add(new TaskRun.RpcStep(
                    RpcRequest.of(RpcRequest.Kind.FACTORY_RESET, key), "apply"));
            case TYPE_DOWNLOAD -> run.getSteps().add(new TaskRun.RpcStep(
                    RpcRequest.download(key, str(params.get("fileType")), str(params.get("url")),
                            str(params.get("fileName")), num(params.get("fileSize"))), "apply"));
            case TYPE_METHODS -> run.getSteps().add(new TaskRun.RpcStep(
                    RpcRequest.of(RpcRequest.Kind.GET_RPC_METHODS, key), "apply"));
            case TYPE_CUSTOM -> run.getSteps().add(new TaskRun.RpcStep(
                    RpcRequest.custom(str(params.get("method")), castMap(params.get("args")), key), "apply"));
            case TYPE_REFRESH_BIZ -> {
                List<String> leaves = modelLeaves(modelParams, root);
                if (!leaves.isEmpty()) {
                    // 模板快速路径：机型模板里登记过业务叶子，直接 GPV
                    appendGpv(run, leaves, "collect");
                } else {
                    // 无模板：先 GPN 扫业务子树（响应回来后由会话引擎补 GPV）
                    for (String br : BusinessPaths.businessRoots(root)) {
                        run.getSteps().add(new TaskRun.RpcStep(RpcRequest.gpn(br, false, key), "scan"));
                    }
                }
            }
            case TYPE_ADAPT_PROBE -> run.getSteps().add(new TaskRun.RpcStep(
                    RpcRequest.gpn(root, false, key), "probe"));
            default -> run.getSteps().add(new TaskRun.RpcStep(
                    RpcRequest.custom(run.getType(), params, key), "apply"));
        }
        return run;
    }

    /** GPN 扫描完成后：把业务子树里探到的叶子补成 GPV 步骤 */
    public void appendBizGpv(TaskRun run, DeviceData data, String root) {
        List<String> leaves = new ArrayList<>();
        for (String br : BusinessPaths.businessRoots(root)) {
            leaves.addAll(data.leaves(br));
        }
        appendGpv(run, leaves, "collect");
    }

    public void appendGpv(TaskRun run, List<String> names, String purpose) {
        int batch = Math.max(1, props.getGpvBatchSize());
        for (int i = 0; i < names.size(); i += batch) {
            run.getSteps().add(new TaskRun.RpcStep(
                    RpcRequest.gpv(new ArrayList<>(names.subList(i, Math.min(i + batch, names.size()))),
                            "task-" + run.getTaskId()), purpose));
        }
    }

    /** 任务队列里是否还有某个用途的步骤 */
    public boolean hasRemainingPurpose(TaskRun run, String purpose) {
        return run.getSteps().stream().anyMatch(s -> s.purpose().equals(purpose));
    }

    /** 模板里业务域下的叶子路径（组装 GPV 用） */
    private List<String> modelLeaves(Map<String, CpeParam> modelParams, String root) {
        List<String> out = new ArrayList<>();
        if (modelParams == null || modelParams.isEmpty()) {
            return out;
        }
        for (String br : BusinessPaths.businessRoots(root)) {
            for (String p : modelParams.keySet()) {
                if (p.startsWith(br) && !p.endsWith(".")) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<RpcRequest.NameValue> nameValues(Map<String, Object> params) {
        List<RpcRequest.NameValue> out = new ArrayList<>();
        List<Map<String, Object>> items = (List<Map<String, Object>>) (List<?>) params.getOrDefault("values",
                params.getOrDefault("items", List.of()));
        for (Map<String, Object> it : items) {
            out.add(new RpcRequest.NameValue(
                    str(it.get("name")),
                    it.get("value") == null ? "" : String.valueOf(it.get("value")),
                    str(it.get("type"))));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> strList(Object o) {
        if (o instanceof List<?> l) {
            return (List<String>) l;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}

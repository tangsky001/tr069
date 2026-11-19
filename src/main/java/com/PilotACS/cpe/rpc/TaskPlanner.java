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
import com.PilotACS.common.log.Logs;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务 → RPC 步骤展开器 */
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

    /** 写操作类任务：重发等于真实二次执行（再下发一遍/再重启一次/再下载一次）， */
    public static final java.util.Set<String> WRITE_TYPES = java.util.Set.of(
            TYPE_SPV, TYPE_REBOOT, TYPE_FACTORY_RESET, TYPE_DOWNLOAD, TYPE_ADD, TYPE_DEL, TYPE_CUSTOM);

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
                // 续跑（会话中断回队）：跳过参数树里"已经采到值"的名字，只补缺口。
                if (Boolean.TRUE.equals(params.get("resume")) && !names.isEmpty()) {
                    List<String> remaining = names.stream().filter(n -> data.value(n) == null).toList();
                    Logs.info(Logs.Module.CPE, "任务断点续跑", "任务", run.getTaskId(),
                            "总数", names.size(), "已采", names.size() - remaining.size(),
                            "待采", remaining.size());
                    names = new ArrayList<>(remaining);
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
                if (props.isSpvReadback() && !values.isEmpty()) {
                    // 回读校验（参考实现：下发后回读写下的参数确认设备实际生效值）：
                    appendGpv(run, values.stream().map(RpcRequest.NameValue::name).toList(), "verify");
                }
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
            case TYPE_DOWNLOAD -> {
                RpcRequest r = RpcRequest.download(key, str(params.get("fileType")), str(params.get("url")),
                        str(params.get("fileName")), num(params.get("fileSize")));
                // 下载服务器账密透传：此前写死空串，设备下载 401 时无线索
                r.setUsername(str(params.get("username")));
                r.setPassword(str(params.get("password")));
                run.getSteps().add(new TaskRun.RpcStep(r, "apply"));
            }
            case TYPE_METHODS -> run.getSteps().add(new TaskRun.RpcStep(
                    RpcRequest.of(RpcRequest.Kind.GET_RPC_METHODS, key), "apply"));
            case TYPE_CUSTOM -> run.getSteps().add(new TaskRun.RpcStep(
                    RpcRequest.custom(str(params.get("method")), castMap(params.get("args")), key), "apply"));
            case TYPE_REFRESH_BIZ -> {
                if (Boolean.TRUE.equals(params.get("resume"))) {
                    // 会话中断回队：不再 GPN 重扫，直接补采业务域里还没取到值的叶子
                    appendGpv(run, uncollectedBizLeaves(data, root), "collect");
                } else {
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
            }
            case TYPE_ADAPT_PROBE -> {
                if (Boolean.TRUE.equals(params.get("resume"))) {
                    // 会话中断回队：树已经在会话里（GPN 全量做过了），只补采没取到值的业务叶子。
                    List<String> missing = uncollectedBizLeaves(data, root);
                    Logs.info(Logs.Module.CPE, "探测任务断点续跑", "任务", run.getTaskId(),
                            "待采", missing.size());
                    appendGpv(run, missing, "collect");
                } else {
                    run.getSteps().add(new TaskRun.RpcStep(
                            RpcRequest.gpn(root, false, key), "probe"));
                }
            }
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

    /** 业务域里还没取到值的叶子（GPN 只登记路径 value=null，GPV 采完才有值）。 */
    private List<String> uncollectedBizLeaves(DeviceData data, String root) {
        List<String> out = new ArrayList<>();
        for (String br : BusinessPaths.businessRoots(root)) {
            for (String p : data.leaves(br)) {
                if (data.value(p) == null) {
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
            // JSON 里的数字/布尔元素不能强转 String（CCE）：统一 String.valueOf
            return l.stream().map(x -> x == null ? "" : String.valueOf(x)).toList();
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
        if (o instanceof Number n) {
            return n.longValue();
        }
        // 前端表单经常把 fileSize 传成字符串（"209715200"）：解析成数值，
        if (o instanceof String s && !s.isBlank()) {
            try {
                return (long) Double.parseDouble(s.trim());
            } catch (NumberFormatException ignore) {
                // 非数值字符串
            }
        }
        return 0;
    }
}

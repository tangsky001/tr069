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
    /** 业务参数刷新 */
    public static final String TYPE_REFRESH_BIZ = "RefreshBusiness";
    /** 机型适配探测 */
    public static final String TYPE_ADAPT_PROBE = "AdaptProbe";

    /** 写操作类任务 */
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
                if (Boolean.TRUE.equals(params.get("resume")) && !names.isEmpty()) {
                    List<String> remaining = names.stream().filter(n -> data.value(n) == null).toList();
                    Logs.info(Logs.Module.CPE_PUSH, "任务断点续跑", "任务", run.getTaskId(),
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
                List<RpcRequest.NameValue> values = nameValues(params, data, modelParams);
                validateSpv(values, data, modelParams);
                run.getSteps().add(new TaskRun.RpcStep(RpcRequest.spv(values, key), "apply"));
                if (props.isSpvReadback() && !values.isEmpty()) {
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
                    appendGpv(run, uncollectedBizLeaves(data, root), "collect");
                } else {
                    List<String> leaves = modelLeaves(modelParams, root);
                    if (!leaves.isEmpty()) {
                        appendGpv(run, leaves, "collect");
                    } else {
                        for (String br : BusinessPaths.businessRoots(root)) {
                            run.getSteps().add(new TaskRun.RpcStep(RpcRequest.gpn(br, false, key), "scan"));
                        }
                    }
                }
            }
            case TYPE_ADAPT_PROBE -> {
                if (Boolean.TRUE.equals(params.get("resume"))) {
                    List<String> missing = uncollectedBizLeaves(data, root);
                    Logs.info(Logs.Module.CPE_PUSH, "探测任务断点续跑", "任务", run.getTaskId(),
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

    /** GPN 扫描完成后补 GPV 业务叶子 */
    public void appendBizGpv(TaskRun run, DeviceData data, String root) {
        List<String> leaves = new ArrayList<>();
        for (String br : BusinessPaths.businessRoots(root)) {
            leaves.addAll(data.leaves(br));
        }
        leaves.addAll(BusinessPaths.vendorWifiLeaves(data));
        leaves.addAll(BusinessPaths.blindWifiLeaves(data));
        appendGpv(run, leaves, "collect");
    }

    public void appendGpv(TaskRun run, List<String> names, String purpose) {
        List<String> valid = names == null ? List.of() : names.stream()
                .filter(n -> n != null && !n.isBlank() && !n.endsWith("."))
                .distinct()
                .toList();
        if (valid.isEmpty()) {
            return;
        }
        int batch = Math.max(1, props.getGpvBatchSize());
        for (int i = 0; i < valid.size(); i += batch) {
            run.getSteps().add(new TaskRun.RpcStep(
                    RpcRequest.gpv(new ArrayList<>(valid.subList(i, Math.min(i + batch, valid.size()))),
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

    /** 业务域里还没取到值的叶子 */
    private List<String> uncollectedBizLeaves(DeviceData data, String root) {
        List<String> out = new ArrayList<>();
        for (String br : BusinessPaths.businessRoots(root)) {
            for (String p : data.leaves(br)) {
                if (data.value(p) == null) {
                    out.add(p);
                }
            }
        }
        out.addAll(BusinessPaths.vendorWifiLeaves(data));
        for (String p : BusinessPaths.blindWifiLeaves(data)) {
            if (data.value(p) == null) {
                out.add(p);
            }
        }
        return out;
    }

    /** SPV 下发前置校验 */
    private void validateSpv(List<RpcRequest.NameValue> values, DeviceData data,
                             Map<String, CpeParam> modelParams) {
        for (RpcRequest.NameValue nv : values) {
            if (nv.name() == null || nv.name().isBlank()) {
                throw new IllegalArgumentException("参数名不能为空");
            }
            CpeParam known = data.get(nv.name());
            if (known == null && modelParams != null) {
                known = modelParams.get(nv.name());
            }
            if (known == null) {
                continue;
            }
            if (known.object()) {
                throw new IllegalArgumentException("参数路径是对象节点，不可直接赋值: " + nv.name());
            }
            if (!known.writable()) {
                // 只读标记降级为告警仍下发：不少机型参数库把可写参数误标只读，交由设备裁决
                Logs.debug(Logs.Module.CPE_PUSH, "参数库标记为只读，仍尝试下发交由设备裁决", "参数", nv.name());
            }
            checkValueType(nv, known.type());
        }
    }

    /** 已知参数的基础类型校验 */
    private void checkValueType(RpcRequest.NameValue nv, String type) {
        if (type == null || nv.value() == null || nv.value().isBlank()) {
            return;
        }
        String t = type.toLowerCase();
        String v = nv.value().trim();
        try {
            switch (t) {
                case "unsignedint" -> {
                    long n = Long.parseLong(v);
                    if (n < 0) {
                        throw new NumberFormatException();
                    }
                }
                case "int" -> Long.parseLong(v);
                case "boolean" -> {
                    if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")
                            && !"0".equals(v) && !"1".equals(v)) {
                        throw new IllegalArgumentException();
                    }
                }
                default -> {
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("参数类型不匹配(" + type + "): " + nv.name() + " = " + v);
        }
    }

    /** SPV 参数构建：支持 Map/List 两种形态；类型三路解析（会话参数树→机型参数库→显式声明），boolean 值归一化为 1/0 */
    @SuppressWarnings("unchecked")
    private List<RpcRequest.NameValue> nameValues(Map<String, Object> params, DeviceData data,
                                                  Map<String, CpeParam> modelParams) {
        boolean strictType = Boolean.TRUE.equals(params.get("strictType"));
        Map<String, RpcRequest.NameValue> dedup = new LinkedHashMap<>();
        Object rawValues = params.getOrDefault("values", params.getOrDefault("items", List.of()));
        if (rawValues instanceof Map<?, ?> rawMap) {
            rawMap.forEach((k, v) -> {
                String name = str(k);
                if (name != null && !name.isBlank()) {
                    String val = v == null ? "" : String.valueOf(v);
                    String resolvedType = resolveParamType(name, null, val, data, modelParams);
                    dedup.put(name, new RpcRequest.NameValue(
                            name, normalizeValueForType(val, resolvedType), resolvedType));
                }
            });
            return new ArrayList<>(dedup.values());
        }
        if (!(rawValues instanceof List<?> rawList)) {
            return List.of();
        }
        for (Object o : rawList) {
            if (!(o instanceof Map<?, ?> it)) {
                continue;
            }
            String name = str(it.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            String val = it.get("value") == null ? "" : String.valueOf(it.get("value"));
            String explicitType = str(it.get("type"));
            if (strictType && explicitType != null && !explicitType.isBlank()) {
                dedup.put(name, new RpcRequest.NameValue(name, val, explicitType));
                continue;
            }
            String resolvedType = resolveParamType(name, explicitType, val, data, modelParams);
            dedup.put(name, new RpcRequest.NameValue(
                    name, normalizeValueForType(val, resolvedType), resolvedType));
        }
        return new ArrayList<>(dedup.values());
    }

    /** 类型三路解析：会话参数树 → 机型参数库 → 显式声明（缺省 string） */
    private String resolveParamType(String path, String explicitType, String value,
                                    DeviceData data, Map<String, CpeParam> modelParams) {
        CpeParam known = data == null ? null : data.get(path);
        if ((known == null || known.type() == null || known.type().isBlank()) && modelParams != null) {
            known = modelParams.get(path);
        }
        if (known != null && known.type() != null && !known.type().isBlank()) {
            return known.type();
        }
        if (explicitType != null && !explicitType.isBlank()
                && !"string".equalsIgnoreCase(explicitType) && !"xsd:string".equalsIgnoreCase(explicitType)) {
            return explicitType;
        }
        return explicitType == null || explicitType.isBlank() ? "string" : explicitType;
    }

    /** boolean 类型值归一化 true/false → 1/0（部分设备只认数字形态） */
    private String normalizeValueForType(String val, String type) {
        if (val == null || type == null) {
            return val == null ? "" : val;
        }
        if (type.toLowerCase().endsWith("boolean")) {
            String v = val.trim();
            if ("true".equalsIgnoreCase(v) || "1".equals(v)) {
                return "1";
            }
            if ("false".equalsIgnoreCase(v) || "0".equals(v)) {
                return "0";
            }
        }
        return val;
    }

    @SuppressWarnings("unchecked")
    private List<String> strList(Object o) {
        if (o instanceof List<?> l) {
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
        if (o instanceof String s && !s.isBlank()) {
            try {
                return (long) Double.parseDouble(s.trim());
            } catch (NumberFormatException ignore) {
            }
        }
        return 0;
    }
}

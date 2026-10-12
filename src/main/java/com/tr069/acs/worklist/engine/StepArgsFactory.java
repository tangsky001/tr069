package com.tr069.acs.worklist.engine;

import com.tr069.acs.cwmp.RpcMethod;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.worklist.model.WorklistStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把工单中的一个步骤转换为 {@link com.tr069.acs.cwmp.SoapEnvelopeBuilder} 所需的参数 Map。
 *
 * <p>每个 key 的写法与 SOAP 报文中同名元素一一对应，
 * 便于现场参考抓包结果直接写工单模板。</p>
 */
public final class StepArgsFactory {

    private StepArgsFactory() {
    }

    /**
     * 构造该步骤的 RPC 参数。
     */
    public static Map<String, Object> build(WorklistStep step, ExecutionContext ctx) {
        Map<String, Object> args = new LinkedHashMap<>();
        RpcMethod rpc = step.resolveRpc();
        if (rpc == null) {
            throw new IllegalStateException("未知 CWMP 方法: " + step.getRpc());
        }
        String opt = null;
        switch (rpc) {
            case SET_PARAMETER_VALUES -> {
                args.put("ParameterList", buildParameterList(step, ctx));
                args.put("ParameterKey", str(ctx.resolve(option(step, "ParameterKey"))));
            }
            case GET_PARAMETER_VALUES, GET_PARAMETER_ATTRIBUTES -> {
                List<String> names = step.getParameters() == null || step.getParameters().isEmpty()
                        ? stringOptionList(ctx, step, "ParameterNames")
                        : step.getParameters().stream()
                                .map(p -> ctx.resolve(p.getName()))
                                .toList();
                args.put("ParameterNames", names);
            }
            case GET_PARAMETER_NAMES -> {
                args.put("ParameterPath", str(ctx.resolve(step.getParameterPath())));
                args.put("NextLevel", String.valueOf(step.isNextLevel()));
            }
            case SET_PARAMETER_ATTRIBUTES ->
                    args.put("ParameterList", buildAttributeList(step, ctx));
            case ADD_OBJECT, DELETE_OBJECT -> {
                args.put("ObjectName", str(ctx.resolve(step.getObjectName())));
                args.put("ParameterKey", str(ctx.resolve(option(step, "ParameterKey"))));
            }
            case DOWNLOAD -> {
                putAllResolved(args, ctx, step.getOptions(),
                        "CommandKey", "FileType", "URL", "Username", "Password",
                        "FileSize", "TargetFileName", "DelaySeconds",
                        "SuccessURL", "FailureURL");
            }
            case UPLOAD -> {
                putAllResolved(args, ctx, step.getOptions(),
                        "CommandKey", "FileType", "URL", "Username", "Password",
                        "DelaySeconds");
            }
            case SCHEDULE_INFORM -> {
                putAllResolved(args, ctx, step.getOptions(), "DelaySeconds", "CommandKey");
            }
            case SCHEDULE_DOWNLOAD -> {
                putAllResolved(args, ctx, step.getOptions(),
                        "CommandKey", "FileType", "URL", "Username", "Password",
                        "FileSize", "TargetFileName", "TimeWindowList");
            }
            case REBOOT, CANCEL_TRANSFER, FACTORY_RESET, GET_RPC_METHODS,
                    GET_QUEUED_TRANSFERS, GET_ALL_QUEUED_TRANSFERS -> {
                opt = option(step, "CommandKey");
                if (opt != null) {
                    args.put("CommandKey", str(ctx.resolve(opt)));
                }
            }
            case SET_VOUCHERS -> {
                putAllResolved(args, ctx, step.getOptions(), "VoucherList");
            }
            case GET_OPTIONS -> {
                putAllResolved(args, ctx, step.getOptions(), "OptionName");
            }
            default -> {
                // ChangeDUState 等结构化程度高的方法，由 options 直接透传
                if (step.getOptions() != null) {
                    args.putAll(step.getOptions());
                }
            }
        }
        return args;
    }

    /**
     * SetParameterValues 的参数列表。
     */
    private static List<ParameterValueStruct> buildParameterList(WorklistStep step, ExecutionContext ctx) {
        List<ParameterValueStruct> list = new ArrayList<>();
        if (step.getParameters() == null) {
            return list;
        }
        for (WorklistStep.StepParameter p : step.getParameters()) {
            String value = ctx.resolve(p.getValue());
            list.add(ParameterValueStruct.builder()
                    .name(ctx.resolve(p.getName()))
                    .value(value)
                    .valueType(inferValueType(ctx.resolve(p.getValue())))
                    .build());
        }
        return list;
    }

    /**
     * SetParameterAttributes 的参数列表，字段顺序与 SOAP 中的结构一致。
     */
    private static List<Map<String, Object>> buildAttributeList(WorklistStep step, ExecutionContext ctx) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (step.getParameters() == null) {
            return list;
        }
        for (WorklistStep.StepParameter p : step.getParameters()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("Name", ctx.resolve(p.getName()));
            item.put("NotificationChange", str(ctx.resolve(option(step, "NotificationChange"))));
            item.put("Notification", ctx.resolve(p.getValue()));
            item.put("AccessListChange", str(ctx.resolve(option(step, "AccessListChange"))));
            item.put("AccessList", List.of());
            list.add(item);
        }
        return list;
    }

    /**
     * 逐个解析 options 中的指定 key。
     */
    private static void putAllResolved(Map<String, Object> args, ExecutionContext ctx,
                                       Map<String, Object> options, String... keys) {
        for (String key : keys) {
            Object v = options == null ? null : options.get(key);
            if (v != null) {
                args.put(key, ctx.resolve(String.valueOf(v)));
            }
        }
    }

    /**
     * 从 options 读取字符串列表。
     */
    @SuppressWarnings("unchecked")
    private static List<String> stringOptionList(ExecutionContext ctx, WorklistStep step, String key) {
        Object raw = step.getOptions() == null ? null : step.getOptions().get(key);
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                result.add(ctx.resolve(String.valueOf(o)));
            }
        } else if (raw != null) {
            result.add(ctx.resolve(String.valueOf(raw)));
        }
        return result;
    }

    private static String option(WorklistStep step, String key) {
        if (step.getOptions() == null) {
            return null;
        }
        Object v = step.getOptions().get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    /**
     * 依据字面值推断 xsi:type，避免所有值都按 string 下发导致部分设备拒绝。
     */
    private static String inferValueType(String value) {
        if (value == null || value.isEmpty()) {
            return "string";
        }
        if ("true".equals(value) || "false".equals(value)) {
            return "boolean";
        }
        if (value.matches("-?\\d+")) {
            return "int";
        }
        if (value.matches("-?\\d+\\.\\d+")) {
            return "double";
        }
        return "string";
    }
}

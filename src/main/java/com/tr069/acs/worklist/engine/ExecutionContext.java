package com.tr069.acs.worklist.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工单执行上下文。
 *
 * <p>负责把模板中的 {@code ${...}} 占位符替换为运行期真实取值，
 * 取值优先级依次为：</p>
 * <ol>
 *   <li>{@code ${ctx.变量}} - 前序步骤通过 saveAs 存入的结果</li>
 *   <li>{@code ${参数名}}   - 本次工单的入参（含默认值）</li>
 *   <li>{@code ${内置变量}} - sn / oui / productClass 等设备属性</li>
 *   <li>{@code ${别名的键}} - 光猫适配档案中的 parameterAliases（标准名 -> 真实路径）</li>
 * </ol>
 * 找不到的占位符保持原样，方便在日志和结果里直接看出是哪个参数没给。
 */
public class ExecutionContext {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^{}]+)\\}");

    private static final String CTX_PREFIX = "ctx.";

    /** 工单入参（已合并默认值）。 */
    private final Map<String, String> params;

    /** 光猫适配档案中的参数别名 -> 真实路径。 */
    private final Map<String, String> aliases;

    /** 设备内置属性。 */
    private final Map<String, String> deviceVars;

    /** 运行期上下文，供 ${ctx.xxx} 引用。 */
    private final Map<String, Object> context = new ConcurrentHashMap<>();

    public ExecutionContext(Map<String, String> params,
                            Map<String, String> aliases,
                            Map<String, String> deviceVars) {
        this.params = params == null ? Map.of() : params;
        this.aliases = aliases == null ? Map.of() : aliases;
        this.deviceVars = deviceVars == null ? Map.of() : deviceVars;
    }

    /**
     * 替换字符串中的全部占位符。
     */
    public String resolve(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf("${") < 0) {
            return raw;
        }
        Matcher matcher = PLACEHOLDER.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(lookup(key)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 按优先级查找单个占位符的取值。
     */
    private String lookup(String key) {
        if (key.startsWith(CTX_PREFIX)) {
            Object v = context.get(key.substring(CTX_PREFIX.length()));
            return v == null ? "" : String.valueOf(v);
        }
        String v = params.get(key);
        if (v != null) {
            return v;
        }
        v = deviceVars.get(key);
        if (v != null) {
            return v;
        }
        v = aliases.get(key);
        if (v != null) {
            return v;
        }
        // 未解析时保留原占位符，便于定位问题
        return "${" + key + "}";
    }

    /**
     * 把前序步骤的结果存入上下文。
     */
    public void put(String name, Object value) {
        if (name != null && !name.isBlank()) {
            context.put(name, value);
        }
    }

    /**
     * 取出上下文变量。
     */
    public Object get(String name) {
        return context.get(name);
    }

    /**
     * 是否存在仍未替换的占位符，用于执行前的快速校验。
     */
    public boolean hasUnresolved(String raw) {
        return raw != null && PLACEHOLDER.matcher(raw).find();
    }

    /**
     * 返回上下文快照，便于写入执行结果。
     */
    public Map<String, Object> snapshot() {
        return new LinkedHashMap<>(context);
    }
}

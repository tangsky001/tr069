package com.tr069.acs.worklist.service;

import com.tr069.acs.common.exception.BizException;
import com.tr069.acs.worklist.loader.LoadedItem;
import com.tr069.acs.worklist.loader.WorklistLoader;
import com.tr069.acs.worklist.model.WorklistArg;
import com.tr069.acs.worklist.model.WorklistStep;
import com.tr069.acs.worklist.model.WorklistTemplate;
import com.tr069.acs.worklist.registry.WorklistRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 配置模板（工单模板）的落盘读写。
 *
 * <p>模板以文件形式存在于配置根目录：
 * <pre>
 *   operators/{isp}/{version}/{domain}/{deviceType}/{name}.yaml | .xml
 * </pre>
 * 存量模板是 xml（由原 Python 版 data.py 批量转换），手工新增用 yaml——yaml 能完整表达
 * 步骤的 options（Download 的 URL/FileType 等）与参数枚举，xml 表达不了这两项。</p>
 *
 * <p>写完立即触发单文件重载，页面保存后无需再点「重新加载配置」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorklistTemplateStore {

    /** 层级合法字符，挡住路径穿越（../）与文件名里的分隔符。 */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._+\\-]+");

    private final WorklistConfigService configService;
    private final WorklistRegistry worklistRegistry;

    /**
     * 新建模板。
     *
     * @param template 模板内容，必须带齐 isp / version / domain / deviceType / name
     * @param format   yaml 或 xml，为空时按需自动选择
     * @return 落盘并重载后的模板
     */
    public synchronized WorklistTemplate create(WorklistTemplate template, String format) {
        requireSegment("isp", template.getIsp());
        requireSegment("version", template.getVersion());
        requireSegment("domain", template.getDomain());
        requireSegment("deviceType", template.getDeviceType());
        requireSegment("name", template.getName());

        String ext = resolveFormat(template, format, false);
        Path file = resolveFile(template, ext);
        if (Files.exists(file) || worklistRegistry.get(buildKey(template)).isPresent()) {
            throw new BizException("同名模板已存在: " + buildKey(template));
        }
        write(file, template, ext);
        return reload(file, buildKey(template));
    }

    /**
     * 更新模板。改名或改层级时，旧文件随之删除。
     *
     * @param key      原模板 key
     * @param template 新内容
     * @return 落盘并重载后的模板
     */
    public synchronized WorklistTemplate update(String key, WorklistTemplate template) {
        LoadedItem<WorklistTemplate> item = worklistRegistry.get(key)
                .orElseThrow(() -> new BizException("模板不存在: " + key));
        Path oldFile = item.getFile();

        requireSegment("isp", template.getIsp());
        requireSegment("version", template.getVersion());
        requireSegment("domain", template.getDomain());
        requireSegment("deviceType", template.getDeviceType());
        requireSegment("name", template.getName());

        String ext = resolveFormat(template, extension(oldFile), isXml(oldFile));
        Path file = resolveFile(template, ext);
        String newKey = buildKey(template);
        if (!key.equals(newKey) && worklistRegistry.get(newKey).isPresent()) {
            throw new BizException("目标位置已存在同名模板: " + newKey);
        }
        write(file, template, ext);

        // 改名/改格式后旧文件必须清掉，否则同一份模板会被加载两次
        if (!file.equals(oldFile)) {
            deleteFile(oldFile);
        }
        return reload(file, newKey);
    }

    /**
     * 复制一个已有模板另存。
     *
     * @param sourceKey 源模板 key
     * @param target    目标定位字段（isp / version / domain / deviceType / name），缺省沿用源值
     * @param format    目标格式，为空时按需自动选择
     * @return 新模板
     */
    public synchronized WorklistTemplate copyAs(String sourceKey, WorklistTemplate target, String format) {
        WorklistTemplate source = worklistRegistry.get(sourceKey)
                .map(LoadedItem::getValue)
                .orElseThrow(() -> new BizException("模板不存在: " + sourceKey));
        if (source == null) {
            throw new BizException("源模板解析失败，无法复制: " + sourceKey);
        }

        WorklistTemplate copy = new WorklistTemplate();
        copy.setIsp(pick(target.getIsp(), source.getIsp()));
        copy.setVersion(pick(target.getVersion(), source.getVersion()));
        copy.setDomain(pick(target.getDomain(), source.getDomain()));
        copy.setDeviceType(pick(target.getDeviceType(), source.getDeviceType()));
        copy.setName(pick(target.getName(), source.getName()));
        copy.setDoc(target.getDoc() != null ? target.getDoc() : source.getDoc());
        copy.setGroup(target.getGroup() != null ? target.getGroup() : source.getGroup());
        copy.setCategory(pick(target.getCategory(), source.getCategory()));
        copy.setEnabled(target.isEnabled());
        copy.setPriority(target.getPriority() == 0 ? source.getPriority() : target.getPriority());
        copy.setArgs(copyArgs(source.getArgs()));
        copy.setSteps(copySteps(source.getSteps()));
        return create(copy, format);
    }

    /**
     * 删除模板文件并从注册表移除。
     */
    public synchronized void delete(String key) {
        LoadedItem<WorklistTemplate> item = worklistRegistry.get(key)
                .orElseThrow(() -> new BizException("模板不存在: " + key));
        deleteFile(item.getFile());
    }

    // ==================== 落盘 ====================

    private void write(Path file, WorklistTemplate template, String ext) {
        try {
            Files.createDirectories(file.getParent());
            String content = "xml".equals(ext) ? toXml(template) : toYaml(template);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            log.info("已写入配置模板: {}", file);
        } catch (IOException e) {
            throw new BizException("写入模板文件失败: " + e.getMessage());
        }
    }

    private WorklistTemplate reload(Path file, String key) {
        configService.reloadFile(file);
        LoadedItem<WorklistTemplate> item = worklistRegistry.get(key).orElse(null);
        if (item == null || !item.isValid() || item.getValue() == null) {
            throw new BizException("模板已写入但解析失败，请检查内容: "
                    + (item == null ? key : item.getError()));
        }
        return item.getValue();
    }

    private void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new BizException("删除模板文件失败: " + e.getMessage());
        }
        configService.removeFile(file);
        log.info("已删除配置模板: {}", file);
    }

    private Path resolveFile(WorklistTemplate template, String ext) {
        Path root = configService.getRoot();
        if (root == null) {
            throw new BizException("配置根目录尚未就绪");
        }
        return root.resolve(WorklistLoader.OPERATORS_DIR)
                .resolve(template.getIsp())
                .resolve(template.getVersion())
                .resolve(template.getDomain())
                .resolve(template.getDeviceType())
                .resolve(template.getName() + "." + ext)
                .normalize();
    }

    /**
     * 决定落盘格式：xml 表达不了步骤 options 与参数枚举，这类模板只能写 yaml。
     */
    private String resolveFormat(WorklistTemplate template, String requested, boolean sourceIsXml) {
        boolean needYaml = needsYaml(template);
        String req = requested == null || requested.isBlank() ? null : requested.trim().toLowerCase();
        if ("xml".equals(req)) {
            if (needYaml) {
                throw new BizException("该模板含步骤 options 或参数枚举，XML 无法表达，请改用 YAML 格式");
            }
            return "xml";
        }
        if ("yaml".equals(req) || "yml".equals(req)) {
            return "yaml";
        }
        if (sourceIsXml && !needYaml) {
            // 存量 xml 模板原地编辑，保持原格式，避免同目录下出现两份同名模板
            return "xml";
        }
        return "yaml";
    }

    private boolean needsYaml(WorklistTemplate template) {
        if (template.getSteps() != null) {
            for (WorklistStep step : template.getSteps()) {
                if (step != null && step.getOptions() != null && !step.getOptions().isEmpty()) {
                    return true;
                }
            }
        }
        if (template.getArgs() != null) {
            for (WorklistArg arg : template.getArgs().values()) {
                if (arg != null && arg.getEnumValues() != null && !arg.getEnumValues().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== 序列化 ====================

    /**
     * 序列化为 YAML。只写业务字段，运行期的 sourceFile / valid / stepsMigrated 等不落盘。
     */
    private String toYaml(WorklistTemplate template) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", template.getName());
        root.put("isp", template.getIsp());
        root.put("version", template.getVersion());
        root.put("domain", template.getDomain());
        root.put("deviceType", template.getDeviceType());
        putIfNotBlank(root, "category", template.getDeclaredCategory());
        putIfNotBlank(root, "group", template.getGroup());
        root.put("enabled", template.isEnabled());
        root.put("priority", template.getPriority());
        putIfNotBlank(root, "doc", template.getDoc());

        if (template.getArgs() != null && !template.getArgs().isEmpty()) {
            Map<String, Object> args = new LinkedHashMap<>();
            int order = 1;
            for (Map.Entry<String, WorklistArg> entry : template.getArgs().entrySet()) {
                WorklistArg arg = entry.getValue();
                if (arg == null) {
                    continue;
                }
                Map<String, Object> node = new LinkedHashMap<>();
                putIfNotNull(node, "defaultValue", arg.getDefaultValue());
                node.put("order", arg.getOrder() == 0 ? order : arg.getOrder());
                node.put("required", arg.isRequired());
                putIfNotBlank(node, "type", arg.getType());
                if (arg.getEnumValues() != null && !arg.getEnumValues().isEmpty()) {
                    node.put("enumValues", arg.getEnumValues());
                }
                putIfNotBlank(node, "description", arg.getDescription());
                args.put(entry.getKey(), node);
                order++;
            }
            root.put("args", args);
        }

        if (template.getSteps() != null && !template.getSteps().isEmpty()) {
            List<Map<String, Object>> steps = new ArrayList<>();
            for (WorklistStep step : template.getSteps()) {
                steps.add(stepNode(step));
            }
            root.put("steps", steps);
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(200);
        options.setIndent(2);
        return new Yaml(options).dump(root);
    }

    private Map<String, Object> stepNode(WorklistStep step) {
        Map<String, Object> node = new LinkedHashMap<>();
        putIfNotBlank(node, "comment", step.getComment());
        node.put("rpc", step.getRpc());
        putIfNotBlank(node, "objectName", step.getObjectName());
        putIfNotBlank(node, "parameterPath", step.getParameterPath());
        if (step.isNextLevel()) {
            node.put("nextLevel", true);
        }
        putIfNotBlank(node, "saveAs", step.getSaveAs());
        node.put("abortOnError", step.isAbortOnError());
        if (step.getTimeoutSeconds() > 0) {
            node.put("timeoutSeconds", step.getTimeoutSeconds());
        }
        if (step.getOptions() != null && !step.getOptions().isEmpty()) {
            node.put("options", step.getOptions());
        }
        if (step.getParameters() != null && !step.getParameters().isEmpty()) {
            List<Map<String, String>> params = new ArrayList<>();
            for (WorklistStep.StepParameter p : step.getParameters()) {
                Map<String, String> pair = new LinkedHashMap<>();
                pair.put("name", p.getName());
                pair.put("value", p.getValue());
                params.add(pair);
            }
            node.put("parameters", params);
        }
        return node;
    }

    /**
     * 序列化为 XML，保持与存量模板一致的格式，便于现场用同一套方式查看与修改。
     */
    private String toXml(WorklistTemplate template) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<worklist");
        attr(sb, "name", template.getName());
        attr(sb, "isp", template.getIsp());
        attr(sb, "version", template.getVersion());
        attr(sb, "domain", template.getDomain());
        attr(sb, "deviceType", template.getDeviceType());
        attr(sb, "category", template.getDeclaredCategory());
        attr(sb, "group", template.getGroup());
        attr(sb, "enabled", String.valueOf(template.isEnabled()));
        attr(sb, "priority", String.valueOf(template.getPriority()));
        sb.append(">\n");

        if (notBlank(template.getDoc())) {
            sb.append("  <doc>").append(escapeXml(template.getDoc())).append("</doc>\n");
        }

        if (template.getArgs() != null && !template.getArgs().isEmpty()) {
            sb.append("  <args>\n");
            int order = 1;
            for (Map.Entry<String, WorklistArg> entry : template.getArgs().entrySet()) {
                WorklistArg arg = entry.getValue();
                if (arg == null) {
                    continue;
                }
                sb.append("    <arg");
                attr(sb, "name", entry.getKey());
                attr(sb, "defaultValue", arg.getDefaultValue());
                attr(sb, "order", String.valueOf(arg.getOrder() == 0 ? order : arg.getOrder()));
                attr(sb, "required", String.valueOf(arg.isRequired()));
                attr(sb, "type", arg.getType());
                attr(sb, "description", arg.getDescription());
                sb.append("/>\n");
                order++;
            }
            sb.append("  </args>\n");
        }

        if (template.getSteps() != null && !template.getSteps().isEmpty()) {
            sb.append("  <steps>\n");
            for (WorklistStep step : template.getSteps()) {
                sb.append("    <step");
                attr(sb, "comment", step.getComment());
                attr(sb, "rpc", step.getRpc());
                attr(sb, "objectName", step.getObjectName());
                attr(sb, "parameterPath", step.getParameterPath());
                attr(sb, "nextLevel", String.valueOf(step.isNextLevel()));
                attr(sb, "saveAs", step.getSaveAs());
                attr(sb, "abortOnError", String.valueOf(step.isAbortOnError()));
                attr(sb, "timeoutSeconds", String.valueOf(step.getTimeoutSeconds()));
                if (step.getParameters() == null || step.getParameters().isEmpty()) {
                    sb.append("/>\n");
                    continue;
                }
                sb.append(">\n");
                for (WorklistStep.StepParameter p : step.getParameters()) {
                    sb.append("      <parameter");
                    attr(sb, "name", p.getName());
                    attr(sb, "value", p.getValue());
                    sb.append("/>\n");
                }
                sb.append("    </step>\n");
            }
            sb.append("  </steps>\n");
        }

        sb.append("</worklist>\n");
        return sb.toString();
    }

    // ==================== 工具 ====================

    private static Map<String, WorklistArg> copyArgs(Map<String, WorklistArg> source) {
        Map<String, WorklistArg> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        for (Map.Entry<String, WorklistArg> entry : source.entrySet()) {
            WorklistArg src = entry.getValue();
            if (src == null) {
                continue;
            }
            WorklistArg arg = new WorklistArg();
            arg.setName(src.getName());
            arg.setDefaultValue(src.getDefaultValue());
            arg.setOrder(src.getOrder());
            arg.setRequired(src.isRequired());
            arg.setType(src.getType());
            arg.setEnumValues(src.getEnumValues() == null ? null : new ArrayList<>(src.getEnumValues()));
            arg.setDescription(src.getDescription());
            copy.put(entry.getKey(), arg);
        }
        return copy;
    }

    private static List<WorklistStep> copySteps(List<WorklistStep> source) {
        List<WorklistStep> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (WorklistStep src : source) {
            if (src == null) {
                continue;
            }
            WorklistStep step = new WorklistStep();
            step.setComment(src.getComment());
            step.setRpc(src.getRpc());
            step.setObjectName(src.getObjectName());
            step.setParameterPath(src.getParameterPath());
            step.setNextLevel(src.isNextLevel());
            step.setSaveAs(src.getSaveAs());
            step.setAbortOnError(src.isAbortOnError());
            step.setTimeoutSeconds(src.getTimeoutSeconds());
            if (src.getOptions() != null) {
                step.setOptions(new LinkedHashMap<>(src.getOptions()));
            }
            List<WorklistStep.StepParameter> params = new ArrayList<>();
            if (src.getParameters() != null) {
                for (WorklistStep.StepParameter p : src.getParameters()) {
                    WorklistStep.StepParameter np = new WorklistStep.StepParameter();
                    np.setName(p.getName());
                    np.setValue(p.getValue());
                    params.add(np);
                }
            }
            step.setParameters(params);
            copy.add(step);
        }
        return copy;
    }

    private static String buildKey(WorklistTemplate t) {
        return String.join("/", t.getIsp(), t.getVersion(), t.getDomain(), t.getDeviceType(), t.getName());
    }

    private static void requireSegment(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(field + " 不能为空");
        }
        if (!SEGMENT.matcher(value).matches()) {
            throw new BizException(field + " 含非法字符，仅允许字母、数字、._+- : " + value);
        }
    }

    private static String pick(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static boolean isXml(Path file) {
        return file.getFileName().toString().toLowerCase().endsWith(".xml");
    }

    private static void attr(StringBuilder sb, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(' ').append(name).append("=\"").append(escapeXml(value)).append('"');
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (notBlank(value)) {
            map.put(key, value);
        }
    }

    private static void putIfNotNull(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}

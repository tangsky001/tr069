package com.tr069.acs.worklist.loader;

import com.tr069.acs.common.exception.ConfigLoadException;
import com.tr069.acs.worklist.model.WorklistArg;
import com.tr069.acs.worklist.model.WorklistStep;
import com.tr069.acs.worklist.model.WorklistTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单模板解析器。
 *
 * <p>工单模板在配置根目录下的布局为：
 * <pre>
 *   operators/{isp}/{version}/{domain}/{deviceType}/{name}.yaml
 * </pre>
 * isp / version / domain / deviceType 由目录层级推导，也可在文件内显式覆盖。</p>
 */
@Slf4j
@Component
public class WorklistLoader {

    /**
     * 工单模板所在的顶层目录名。
     */
    public static final String OPERATORS_DIR = "operators";

    private static final String PROFILES_DIR = "profiles";

    private final Yaml yaml = new Yaml();

    /**
     * 解析单个工单模板文件。
     *
     * @param file          yaml 文件
     * @param rootPath      配置根目录，用于推导相对层级
     * @return 加载结果
     */
    public LoadedItem<WorklistTemplate> load(Path file, Path rootPath) {
        String relativePath = relativize(file, rootPath);
        long lastModified = safeLastModified(file);
        try {
            WorklistTemplate template = parse(file);
            applyPathInfo(template, file, relativePath, lastModified);
            validate(template);
            return LoadedItem.ok(file, relativePath, template, lastModified);
        } catch (ConfigLoadException e) {
            log.warn("工单模板解析失败: {} -> {}", relativePath, e.getMessage());
            return LoadedItem.fail(file, relativePath, e.getMessage(), lastModified);
        } catch (Exception e) {
            log.warn("工单模板读取异常: {}", relativePath, e);
            return LoadedItem.fail(file, relativePath, String.valueOf(e.getMessage()), lastModified);
        }
    }

    /**
     * 按扩展名分派解析器：xml 为存量工单（由原 Python 版 data.py 转换而来），
     * yaml 为手工新增模板。
     */
    private WorklistTemplate parse(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        return fileName.endsWith(".xml") ? parseXml(file) : parseYaml(file);
    }

    private WorklistTemplate parseYaml(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            WorklistTemplate template = yaml.loadAs(in, WorklistTemplate.class);
            if (template == null) {
                throw new ConfigLoadException(String.valueOf(file), "文件内容为空");
            }
            return template;
        } catch (YAMLException e) {
            throw new ConfigLoadException(file.toString(), "YAML 格式错误: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ConfigLoadException(file.toString(), "文件读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 由目录层级填充运营商、版本、业务域与设备类型。
     */
    private void applyPathInfo(WorklistTemplate template, Path file,
                               String relativePath, long lastModified) {
        template.setSourceFile(file.toString());
        template.setLastModified(lastModified);
        template.setValid(true);

        String fileName = file.getFileName().toString();
        String baseName = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        String[] parts = relativePath.replace('\\', '/').split("/");
        for (int i = 0; i < parts.length; i++) {
            if (OPERATORS_DIR.equals(parts[i]) && parts.length - i >= 5) {
                if (isBlank(template.getIsp())) {
                    template.setIsp(parts[i + 1]);
                }
                if (isBlank(template.getVersion())) {
                    template.setVersion(parts[i + 2]);
                }
                if (isBlank(template.getDomain())) {
                    template.setDomain(parts[i + 3]);
                }
                if (isBlank(template.getDeviceType())) {
                    template.setDeviceType(parts[i + 4]);
                }
                break;
            }
        }
        if (isBlank(template.getIsp()) || isBlank(template.getVersion())
                || isBlank(template.getDomain()) || isBlank(template.getDeviceType())) {
            throw new ConfigLoadException(relativePath,
                    "工单模板必须位于 operators/{isp}/{version}/{domain}/{deviceType}/ 之下");
        }
        if (template.getName() == null || template.getName().isBlank()) {
            template.setName(baseName);
        }
        template.setKey(String.join("/", template.getIsp(), template.getVersion(),
                template.getDomain(), template.getDeviceType(), template.getName()));

        // 参数定义缺省补齐：map 的 key 即参数名，order 缺省按出现顺序
        int idx = 1;
        if (template.getArgs() != null) {
            for (Map.Entry<String, WorklistArg> entry : template.getArgs().entrySet()) {
                WorklistArg arg = entry.getValue();
                if (arg == null) {
                    arg = new WorklistArg();
                    entry.setValue(arg);
                }
                if (isBlank(arg.getName())) {
                    arg.setName(entry.getKey());
                }
                if (arg.getOrder() == 0) {
                    arg.setOrder(idx);
                }
                idx++;
            }
        }
    }

    private void validate(WorklistTemplate template) {
        // 存量工单由 Python 版 data.py 转换而来，执行步骤尚未从 script.py 迁移，
        // 这里允许 steps 为空：工单仍可被查询与选择，只是标记为未迁移，执行时由引擎拒绝。
        if (template.getSteps() == null) {
            template.setSteps(new ArrayList<>());
        }
        template.setStepsMigrated(!template.getSteps().isEmpty());
        // 存量模板没有类型标注，这里按名称推断一次并回填，管理端列表即可按类型筛选
        if (isBlank(template.getCategory())) {
            template.setCategory(template.resolveCategory().getCode());
        }
        for (WorklistStep step : template.getSteps()) {
            if (step.resolveRpc() == null) {
                throw new ConfigLoadException(template.getKey(),
                        "未知 CWMP 方法: " + step.getRpc());
            }
        }
    }

    // ==================== XML 解析 ====================

    /**
     * 解析存量工单 XML。文件由 tools/DataPyToXmlMigrator 从原 Python 版 data.py 转换生成。
     */
    private WorklistTemplate parseXml(Path file) {
        try {
            Document doc = readXml(file);
            Element root = doc.getDocumentElement();
            if (!"worklist".equals(root.getTagName())) {
                throw new ConfigLoadException(file.toString(), "根节点必须是 <worklist>");
            }
            WorklistTemplate t = new WorklistTemplate();
            t.setName(attr(root, "name"));
            t.setIsp(attr(root, "isp"));
            t.setVersion(attr(root, "version"));
            t.setDomain(attr(root, "domain"));
            t.setDeviceType(attr(root, "deviceType"));
            t.setEnabled(parseBool(attr(root, "enabled"), true));
            t.setPriority(parseInt(attr(root, "priority"), 100));
            t.setCategory(attr(root, "category"));
            t.setGroup(attr(root, "group"));
            t.setDoc(childText(root, "doc"));

            Element migration = firstChild(root, "migration");
            if (migration != null) {
                t.setSourceScript(attr(migration, "sourceScript"));
            }

            Map<String, WorklistArg> args = new LinkedHashMap<>();
            Element argsEl = firstChild(root, "args");
            if (argsEl != null) {
                for (Element argEl : children(argsEl, "arg")) {
                    WorklistArg a = new WorklistArg();
                    a.setName(attr(argEl, "name"));
                    a.setDefaultValue(attr(argEl, "defaultValue"));
                    a.setOrder(parseInt(attr(argEl, "order"), 0));
                    a.setRequired(parseBool(attr(argEl, "required"), false));
                    a.setType(attr(argEl, "type"));
                    a.setDescription(attr(argEl, "description"));
                    if (!isBlank(a.getName())) {
                        args.put(a.getName(), a);
                    }
                }
            }
            t.setArgs(args);

            List<WorklistStep> steps = new ArrayList<>();
            Element stepsEl = firstChild(root, "steps");
            if (stepsEl != null) {
                for (Element stepEl : children(stepsEl, "step")) {
                    steps.add(parseStep(stepEl));
                }
            }
            t.setSteps(steps);
            return t;
        } catch (ConfigLoadException e) {
            throw e;
        } catch (ParserConfigurationException | SAXException e) {
            throw new ConfigLoadException(file.toString(), "XML 格式错误: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ConfigLoadException(file.toString(), "文件读取失败: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ConfigLoadException(file.toString(), "XML 内容非法: " + e.getMessage(), e);
        }
    }

    private Document readXml(Path file)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 模板是现场可编辑的外部文件，禁用外部 DTD 与外部实体，避免 XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream in = Files.newInputStream(file)) {
            return builder.parse(in);
        }
    }

    private WorklistStep parseStep(Element el) {
        WorklistStep s = new WorklistStep();
        s.setComment(attr(el, "comment"));
        s.setRpc(attr(el, "rpc"));
        s.setObjectName(attr(el, "objectName"));
        s.setParameterPath(attr(el, "parameterPath"));
        s.setNextLevel(parseBool(attr(el, "nextLevel"), false));
        s.setSaveAs(attr(el, "saveAs"));
        s.setAbortOnError(parseBool(attr(el, "abortOnError"), true));
        s.setTimeoutSeconds(parseInt(attr(el, "timeoutSeconds"), 0));

        List<WorklistStep.StepParameter> params = new ArrayList<>();
        for (Element p : children(el, "parameter")) {
            WorklistStep.StepParameter sp = new WorklistStep.StepParameter();
            sp.setName(attr(p, "name"));
            sp.setValue(attr(p, "value"));
            params.add(sp);
        }
        if (!params.isEmpty()) {
            s.setParameters(params);
        }
        return s;
    }

    private String attr(Element el, String name) {
        return el.hasAttribute(name) ? el.getAttribute(name) : null;
    }

    private String childText(Element parent, String tag) {
        Element child = firstChild(parent, tag);
        return child == null ? null : child.getTextContent();
    }

    private Element firstChild(Element parent, String tag) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element e && tag.equals(e.getTagName())) {
                return e;
            }
        }
        return null;
    }

    private List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element e && tag.equals(e.getTagName())) {
                result.add(e);
            }
        }
        return result;
    }

    private boolean parseBool(String value, boolean defaultValue) {
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String relativize(Path file, Path rootPath) {
        try {
            Path root = rootPath.toAbsolutePath().normalize();
            Path target = file.toAbsolutePath().normalize();
            return root.relativize(target).toString();
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private long safeLastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 光猫适配档案所在的顶层目录名。
     */
    public static String getProfilesDir() {
        return PROFILES_DIR;
    }
}

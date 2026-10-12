package com.tr069.acs.worklist.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张工单的执行模板，对应 Python 版每个工单目录下的 {@code data.py} + {@code script.py}。
 *
 * <p>模板由 {@code operators/{isp}/{version}/{domain}/{deviceType}/{name}.yaml} 描述，
 * 支持运行时热加载，新增光猫工单无需重启服务。</p>
 */
@Data
public class WorklistTemplate {

    /** 业务唯一键，格式：{isp}/{version}/{domain}/{deviceType}/{name}。 */
    private String key;

    /** 运营商，如 CT / CU / standard。 */
    private String isp;

    /** 规范版本，如 v3.0 / v1.0。 */
    private String version;

    /** 业务域：business（业务类）/ system（系统类）。 */
    private String domain;

    /** 设备类型，如 ADSL_4+1。 */
    private String deviceType;

    /** 工单名，如 WAN_ADD / WLAN_ADD。 */
    private String name;

    /** 工单说明，对应原 data.py 的 WORKLIST_DOC。 */
    private String doc;

    /**
     * 模板业务类型，取值见 {@link TemplateCategory}。
     *
     * <p>存量模板未标注类型，加载时按模板名推断，管理端可显式指定后写回文件。</p>
     */
    private String category;

    /** 业务分组，管理端按分组归类展示，可空。 */
    private String group;

    /** 是否启用。 */
    private boolean enabled = true;

    /** 执行优先级，数值越小越先执行。 */
    private int priority = 100;

    /** 工单参数定义，对应原 WORKLIST_ARGS。 */
    private Map<String, WorklistArg> args = new LinkedHashMap<>();

    /** 执行步骤，按顺序串行执行。 */
    private List<WorklistStep> steps = new ArrayList<>();

    /** 模板来源文件，热加载时用于定位。 */
    private String sourceFile;

    /** 文件最后修改时间，用于判断是否变化。 */
    private long lastModified;

    /** 最近一次加载是否成功。 */
    private volatile boolean valid = true;

    /** 解析错误信息。 */
    private String error;

    /**
     * 原 Python 版工单脚本的相对路径，如 CT/v3.0/business/ADSL_4+1/WAN_PPPoE/script.py。
     * 存量工单由 data.py 自动转换而来，执行步骤需人工参照该脚本翻译，保留路径便于定位。
     */
    private String sourceScript;

    /**
     * 执行步骤是否已从原 Python 脚本迁移完成。
     * 未迁移的工单可以被查询与选择，但不允许下发执行。
     */
    private boolean stepsMigrated = true;

    /**
     * 全限定名。
     */
    public String fullName() {
        return key;
    }

    /**
     * 模板类型，未标注时按名称推断。
     */
    public TemplateCategory resolveCategory() {
        return category == null || category.isBlank()
                ? TemplateCategory.inferFromName(name)
                : TemplateCategory.of(category);
    }

    /**
     * 显式标注的类型，未标注返回 null（区别于 {@link #resolveCategory()} 的推断结果）。
     */
    public String getDeclaredCategory() {
        return category == null || category.isBlank() ? null : category;
    }
}

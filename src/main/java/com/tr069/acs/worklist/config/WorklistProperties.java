package com.tr069.acs.worklist.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外部配置根目录的属性。
 *
 * <p>配置根目录位于 jar 外部，因此现场改文件即时生效，
 * 也确保了运行时配置不属于构建产物。</p>
 */
@Data
@ConfigurationProperties(prefix = "acs.worklist")
public class WorklistProperties {

    /** 是否启用工单配置模块。 */
    private boolean enabled = true;

    /**
     * 配置根目录。相对路径以进程工作目录为基准。
     * 目录下约定：
     * <pre>
     *   models/      型号能力树 {isp}/{型号标识}.xml，设备上报的参数树按型号导出的归档
     * </pre>
     * 光猫适配档案已迁到 SQLite 模板库（{@code acs.tr069tpl}），不再以 YAML 文件存放；
     * 配置模板也已改为入库的意图模板（config_template）。
     */
    private String rootPath = "./target/configuration";
}

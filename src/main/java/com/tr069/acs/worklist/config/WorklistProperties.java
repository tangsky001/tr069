package com.tr069.acs.worklist.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 外部配置根目录的属性 */
@Data
@ConfigurationProperties(prefix = "acs.worklist")
public class WorklistProperties {

    /** 是否启用工单配置模块 */
    private boolean enabled = true;

    /** 配置根目录。相对路径以进程工作目录为基准 */
    private String rootPath = "./target/configuration";
}

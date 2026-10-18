package com.tr069.acs.worklist.tpl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模板库配置（光猫已适配数据的便携化 SQLite 文件）。
 *
 * <p>对应 {@code application.yml} 的 {@code tr069tpl} 顶层段（与 {@code worklist} 平级，
 * 不在 {@code acs} 下）。该库只装型号模板、实例配置与下发路径，跟设备实时参数值无关——
 * 实时参数仍在 MySQL 的 {@code cpe_parameter} 里。</p>
 */
@ConfigurationProperties("tr069tpl")
public class Tr069TplProperties {

    /**
     * 模板库 SQLite 文件路径。相对路径不按进程工作目录解析，而是与
     * {@code acs.worklist.root-path} 同源（见 WorklistRootResolver）：
     * 换目录起 jar 也连到同一个文件，拷走即用，部署机不必重新适配光猫。
     */
    private String dbPath = "./configuration/tr069_tpl.pack";

    /**
     * 雪花 ID 节点号：多节点部署时手动分配避免主键撞车。
     * 取值范围 0~1023。
     */
    private long snowflakeNodeId = 1;

    public String getDbPath() {
        return dbPath;
    }

    public void setDbPath(String dbPath) {
        this.dbPath = dbPath;
    }

    public long getSnowflakeNodeId() {
        return snowflakeNodeId;
    }

    public void setSnowflakeNodeId(long snowflakeNodeId) {
        this.snowflakeNodeId = snowflakeNodeId;
    }
}

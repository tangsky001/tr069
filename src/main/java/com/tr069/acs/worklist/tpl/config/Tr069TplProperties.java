package com.tr069.acs.worklist.tpl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 模板库配置（光猫已适配数据的便携化 SQLite 文件） */
@ConfigurationProperties("tr069tpl")
public class Tr069TplProperties {

    /** 模板库 SQLite 文件路径。相对路径不按进程工作目录解析，而是与 */
    private String dbPath = "./configuration/tr069_tpl.pack";

    /** 雪花 ID 节点号：多节点部署时手动分配避免主键撞车 */
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

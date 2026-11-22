/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.156
 *
 */

package com.PilotACS.report.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 上报给 hserver 的实例信息，空字段不序列化 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatisticsInfo {

    /** 版本号取不到时的兜底值 */
    public static final String FALLBACK_VERSION = "0.0.0";

    /** 服务端归组标识 */
    private String serverId;

    /** 实例唯一标识 */
    private String deviceId;

    private String branch;

    private String gitCommitId;

    private String gitUrl;

    /** ACS 版本号 */
    private String version;

    private String osName;

    private Boolean docker;

    private String arch;

    private String jdkVersion;

    private String redisVersion;

    private String sqlVersion;

    private String sqlType;

    /** 上报时间，格式 yyyy-MM-dd HH:mm:ss */
    private String time;

    private String license;
}

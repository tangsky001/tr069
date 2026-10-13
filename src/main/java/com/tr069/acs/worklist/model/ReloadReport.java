package com.tr069.acs.worklist.model;

import lombok.Builder;
import lombok.Data;

/**
 * 一次配置重载的结果报告。
 */
@Data
@Builder
public class ReloadReport {

    /** 是否为全量重载。 */
    private boolean fullReload;

    /** 光猫适配档案总数。 */
    private int profileTotal;

    /** 解析失败的光猫档案数。 */
    private int profileFailed;

    /** 重载耗时(毫秒)。 */
    private long durationMs;

    /** 重载后配置版本号。 */
    private long version;

    /** 失败明细。 */
    private java.util.List<String> errors;

    /**
     * 是否全部成功。
     */
    public boolean isSuccess() {
        return profileFailed == 0;
    }
}

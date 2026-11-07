package com.tr069.acs.olt.model;

import com.tr069.acs.olt.vendor.hw.HuaweiQuickSetupPlanner;

/** 华为一键开局表单：参数当前值 + 各项取值约束 */
public record OltHuaweiSetupForm(HuaweiQuickSetupRequest request,
                                 int minBandwidthMbps,
                                 int maxBandwidthMbps,
                                 int minProfileId,
                                 int maxProfileId,
                                 int minTr069ProfileId,
                                 int maxTr069ProfileId) {

    /** 取值约束按设备当前认的区间给 */
    public static OltHuaweiSetupForm of(HuaweiQuickSetupRequest request) {
        return new OltHuaweiSetupForm(request,
                HuaweiQuickSetupPlanner.MIN_BANDWIDTH_MBPS,
                HuaweiQuickSetupPlanner.MAX_BANDWIDTH_MBPS,
                HuaweiQuickSetupPlanner.MIN_PROFILE_ID,
                HuaweiQuickSetupPlanner.MAX_PROFILE_ID,
                HuaweiQuickSetupPlanner.MIN_TR069_PROFILE_ID,
                HuaweiQuickSetupPlanner.MAX_TR069_PROFILE_ID);
    }
}

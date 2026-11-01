package com.tr069.acs.olt.model;

import com.tr069.acs.olt.vendor.HuaweiQuickSetupPlanner;

/**
 * 华为一键开局表单：参数当前值 + 各项取值约束。
 *
 * <p>页面不自己准备默认值，也不自己定输入框上下限——这些都从后端拿。
 * 带宽上限取 {@code HuaweiQuickSetupPlanner.MAX_BANDWIDTH_MBPS}（设备只认这么多），
 * 模板 ID 区间取设备的 {@code profile-id<U><10,512>} 与 {@code <1,32>}，
 * 前端抄一份就会出现「页面让填到 1000、设备其实能到 1024」这种对不上的情况。</p>
 *
 * @param request            当前参数（存过的就返回存过的，没存过返回默认值）
 * @param minBandwidthMbps   带宽下限
 * @param maxBandwidthMbps   带宽上限
 * @param minProfileId       三套模板（DBA / 线路 / 业务）ID 下限
 * @param maxProfileId       三套模板 ID 上限
 * @param minTr069ProfileId  TR-069 模板 ID 下限（比另外三套窄）
 * @param maxTr069ProfileId  TR-069 模板 ID 上限
 */
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

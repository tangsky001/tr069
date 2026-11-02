package com.tr069.acs.olt.model;

import java.util.List;

/**
 * 线路模板三个弹窗（新增 T-CONT / GEM Port / GEM 映射）的表单元数据。
 *
 * <p>默认值、可用区间、提示文字全由后端按库里的快照算出来：页面只管渲染和提交，
 * 不再自己数「哪些索引用过了」——以前前端算一份、后端校验一份，两边不一致时
 * 就会出现「页面提示 3 可用、下发被设备拒」。</p>
 *
 * @param profileId   线路模板 ID
 * @param profileName 模板名
 * @param profileLabel 「100（FTTH-GPON-1000）」这种页面直接显示的写法
 * @param ponType     gpon / epon
 * @param supported   这台设备的厂商接没接入线路模板下发
 * @param tcont       T-CONT 索引：{@code value} 是新增 GEM Port 时默认挂的那条，
 *                    {@code availableFrom} 是新增 T-CONT 时默认填的空号
 * @param gemport     GEM Port 索引
 * @param mapping     GEM 内映射序号（按 {@code gemportId} 那一条算）
 * @param dbaOptions  新增 T-CONT 时可选的 DBA 模板
 * @param defaultDbaId 新增 T-CONT 时默认选中的 DBA 模板
 */
public record OltLineProfileForm(Integer profileId, String profileName, String profileLabel, String ponType,
                                 boolean supported,
                                 IndexForm tcont, IndexForm gemport, IndexForm mapping,
                                 List<DbaOption> dbaOptions, Integer defaultDbaId) {

    /**
     * 一个索引框的约束与建议值。
     *
     * @param min           取值范围下限
     * @param max           取值范围上限
     * @param value         默认填到框里的值（没有建议就 null，页面留空）
     * @param used          已经占用的索引
     * @param availableFrom 第一个没被占用的索引；用满了是 null
     * @param hint          灰字提示，例如「可用的 GEM Port: 3-1023」
     */
    public record IndexForm(int min, int max, Integer value, List<Integer> used,
                            Integer availableFrom, String hint) {
    }

    /** 新增 T-CONT 时可引用的 DBA 模板：{@code label} 是「DBA 100 · 1000M」这种写法 */
    public record DbaOption(Integer profileId, String label) {
    }
}

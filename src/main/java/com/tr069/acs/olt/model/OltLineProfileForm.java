package com.tr069.acs.olt.model;

import java.util.List;

/** 线路模板三个弹窗（新增 T-CONT / GEM Port / GEM 映射）的表单元数据 */
public record OltLineProfileForm(Integer profileId, String profileName, String profileLabel, String ponType,
                                 boolean supported,
                                 IndexForm tcont, IndexForm gemport, IndexForm mapping,
                                 List<DbaOption> dbaOptions, Integer defaultDbaId) {

    /** 一个索引框的约束与建议值 */
    public record IndexForm(int min, int max, Integer value, List<Integer> used,
                            Integer availableFrom, String hint) {
    }

    /** 新增 T-CONT 时可引用的 DBA 模板：{@code label} 是「DBA 100 · 1000M」这种写法 */
    public record DbaOption(Integer profileId, String label) {
    }
}

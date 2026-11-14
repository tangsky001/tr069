package com.tr069.acs.olt.model;

import lombok.Data;

/** 线路模板里的一个 T-CONT（{@code display ont-lineprofile gpon all} 的 T-CONT 行） */
@Data
public class OltTcont {

    /** T-CONT 编号 */
    private Integer tcontId;

    /** 这条 T-CONT 引用的 DBA 模板 ID */
    private Integer dbaProfileId;

    /** DBA 模板名（detail 组装时按 ID 查好，查不到为空串），页面直接显示 */
    private String dbaName;
}

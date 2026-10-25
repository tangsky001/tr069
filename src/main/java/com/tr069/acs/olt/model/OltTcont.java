package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 线路模板里的一个 T-CONT（{@code display ont-lineprofile gpon all} 的 T-CONT 行）。
 *
 * <p>T-CONT 是 ONT 上行的容器，它引用一条 DBA 模板——所以「这条线路模板用了哪档带宽」
 * 是顺着 T-CONT → DBA 模板一路看出来的，页面要把两边对上。</p>
 */
@Data
public class OltTcont {

    /** T-CONT 编号 */
    private Integer tcontId;

    /** 这条 T-CONT 引用的 DBA 模板 ID */
    private Integer dbaProfileId;
}

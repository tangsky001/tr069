package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条 GPON 线路模板（{@code display ont-lineprofile gpon all}）。
 *
 * <p>线路模板是 ONT 的上行通道模型：底下挂若干 T-CONT（各自引用一条 DBA 模板），
 * 每个 T-CONT 上再挂若干 GEM port 承载业务流。</p>
 */
@Data
public class OltLineProfile {

    /** 模板 ID，ONT 注册时按它下发 */
    private Integer profileId;

    /** 模板名 */
    private String name;

    /** 模板类型（ONT 等） */
    private String type;

    /** 回显里的 T-CONT 个数（Number of T-CONT） */
    private Integer tcontCount;

    /** 回显里的 GEM port 个数（Number of GEM port） */
    private Integer gemportCount;

    /** 每个 T-CONT 引用的 DBA 模板 */
    private List<OltTcont> tconts = new ArrayList<>();

    /** 每个 GEM port 挂在哪个 T-CONT 上、怎么映射 */
    private List<OltGemport> gemports = new ArrayList<>();
}

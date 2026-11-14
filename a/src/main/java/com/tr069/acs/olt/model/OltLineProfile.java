package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 一条 GPON 线路模板（{@code display ont-lineprofile gpon all}） */
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

    /** 是不是 TR-069 用的线路模板：设备侧没有专门标记位，按模板名判断 */
    private Boolean tr069Bound;

    /** 映射关系表：一行一个 GEM port，T-CONT 的 DBA 模板顺带对上 */
    private List<OltLineMappingRow> mappingRows = new ArrayList<>();
}

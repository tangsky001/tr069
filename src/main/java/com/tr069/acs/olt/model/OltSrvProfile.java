package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一条 ONT 能力模板（{@code display ont-srvprofile gpon all} 的一块） */
@Data
public class OltSrvProfile {

    /** Profile-ID */
    private Integer profileId;

    /** Profile-name */
    private String name;

    /** PON 类型：gpon / epon（命令里的类型关键字跟着 PON 板走，采集时探测填入） */
    private String ponType;

    /** 网口数量：{@code ont-port eth adaptive 8} 里的上限，"自适应,最大 8" 就是这么来的 */
    private Integer ethPorts;

    /** 电话口（POTS）数量上限：{@code ont-port pots adaptive 32} */
    private Integer potsPorts;

    /** 能力项，按回显顺序保留 */
    private Map<String, String> items = new LinkedHashMap<>();

    /** TR069 管理参数（键名含 TR069 / ACS 的那几项） */
    private Map<String, String> tr069 = new LinkedHashMap<>();
}

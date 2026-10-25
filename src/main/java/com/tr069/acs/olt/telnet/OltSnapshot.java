package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.model.OltTemplateInfo;
import com.tr069.acs.olt.model.OltVlanInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次命令行采集的结果。
 */
@Data
public class OltSnapshot {

    private OltVendor vendor = OltVendor.GENERIC;

    /** 命令风格：华为 display，第三方 show；没探测出来为 null */
    private OltVendor.CliStyle cliStyle;

    /** 型号，解析不出时为 null */
    private String model;

    /** 软件版本，解析不出时为 null */
    private String softwareVersion;

    /** 补丁版本，如 SPC107 */
    private String patchVersion;

    /** 背板/主板型号，如 H802MABO */
    private String backplane;

    /** 主控 MAC，回显里有才有 */
    private String macAddress;

    /** 运行时长（中文），如 59天18小时21分11秒 */
    private String uptime;

    /** 登录后看到的提示符，排查登录权限用 */
    private String prompt;

    /** 板卡列表，未采集时为空 */
    private List<OltBoard> boards = new ArrayList<>();

    /** 版本命令回显 */
    private String versionText;

    /** 板卡命令回显 */
    private String boardText;

    /** VLAN 快照（VLAN 列表 + 上行口放通情况），没采到为 null */
    private OltVlanInfo vlanInfo;

    /** DBA 模板与线路模板快照，没采到为 null */
    private OltTemplateInfo templateInfo;

    /** 本次交互的完整回显，落库留档 */
    private String transcript;

    /** 采集过程中的问题说明（命令无回显、回显无法解析等），直接拼进同步摘要给页面看 */
    private List<String> notes = new ArrayList<>();
}

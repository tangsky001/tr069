/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.248
 *
 */

package com.PilotACS.olt.telnet;

import com.PilotACS.olt.entity.OltBoard;
import com.PilotACS.olt.model.OltTemplateInfo;
import com.PilotACS.olt.model.OltVlanInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 一次命令行采集的结果 */
@Data
public class OltSnapshot {

    private OltVendor vendor = OltVendor.GENERIC;

    private OltVendor.CliStyle cliStyle;

    private String model;

    private String softwareVersion;

    private String patchVersion;

    private String backplane;

    private String macAddress;

    private String uptime;

    private String prompt;

    private List<OltBoard> boards = new ArrayList<>();

    /** 机框槽位数 */
    private Integer slotCount;

    /** 版本命令回显 */
    private String versionText;

    /** 板卡命令回显 */
    private String boardText;

    /** VLAN 快照 */
    private OltVlanInfo vlanInfo;

    /** 模板快照 */
    private OltTemplateInfo templateInfo;

    /** 本次交互完整回显 */
    private String transcript;

    /** 采集过程中的问题说明 */
    private List<String> notes = new ArrayList<>();
}

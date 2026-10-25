package com.tr069.acs.olt.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * DBA 模板与线路模板的快照，随同步整体覆盖，JSON 存在 olt_device.template_json。
 *
 * <p>这两条命令只在华为 config 视图下认，其它厂商的版本不适用；采不到就整份不写，
 * 页面上按「未采集」提示，而不是拿空表冒充采集成功。</p>
 */
@Data
public class OltTemplateInfo {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** display dba-profile all */
    private List<OltDbaProfile> dbaProfiles = new ArrayList<>();

    /** display ont-lineprofile gpon all */
    private List<OltLineProfile> lineProfiles = new ArrayList<>();

    /** display service-port all：业务流（ONT 用户侧 VLAN ↔ 端口 ↔ GEM port） */
    private List<OltServicePort> servicePorts = new ArrayList<>();

    /** display ont-srvprofile gpon all：ONT 能力模板（含 TR069 管理参数） */
    private List<OltSrvProfile> srvProfiles = new ArrayList<>();

    /** display ont tr069-server-profile all：TR069 服务器模板（ACS 地址与账号） */
    private List<OltTr069Profile> tr069Profiles = new ArrayList<>();

    /** display traffic table ip all：流量限速模板（CIR 承诺 / PIR 峰值） */
    private List<OltTrafficTable> trafficTables = new ArrayList<>();

    /** 采集时间，页面显示「模板快照时间」 */
    private String collectedAt;

    public void stamp() {
        this.collectedAt = LocalDateTime.now().format(FORMAT);
    }

    public boolean isEmpty() {
        return dbaProfiles.isEmpty() && lineProfiles.isEmpty()
                && servicePorts.isEmpty() && srvProfiles.isEmpty()
                && tr069Profiles.isEmpty() && trafficTables.isEmpty();
    }
}

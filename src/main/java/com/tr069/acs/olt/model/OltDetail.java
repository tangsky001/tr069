package com.tr069.acs.olt.model;

import com.tr069.acs.olt.entity.OltBoard;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * OLT 详情：台账 + 板卡 + 最近采集记录。
 */
@Data
public class OltDetail {

    private OltDeviceView device;

    private List<OltBoard> boards = new ArrayList<>();

    private List<OltSyncRecordView> records = new ArrayList<>();

    /** VLAN 快照，还没采过为 null */
    private OltVlanInfo vlan;

    /** DBA 模板与线路模板快照，还没采过为 null */
    private OltTemplateInfo template;
}

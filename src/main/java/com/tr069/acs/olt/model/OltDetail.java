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

    /**
     * 后台正在补采慢数据（端口状态 / ONT / VLAN / 模板）。
     *
     * <p>板卡已经采回来先给页面出机框，这几个要几十条命令，后台跑完写库，
     * 页面看到这个标记就提示「正在补充采集」并轮询等它结束。</p>
     */
    private boolean deepSyncing;
}

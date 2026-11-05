package com.tr069.acs.olt.model;

import com.tr069.acs.olt.entity.OltBoard;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** OLT 详情：台账 + 板卡 + 最近采集记录 */
@Data
public class OltDetail {

    private OltDeviceView device;

    private List<OltBoard> boards = new ArrayList<>();

    private List<OltSyncRecordView> records = new ArrayList<>();

    /** VLAN 快照，还没采过为 null */
    private OltVlanInfo vlan;

    /** DBA 模板与线路模板快照，还没采过为 null */
    private OltTemplateInfo template;

    /** 后台正在补采慢数据（端口状态 / ONT / VLAN / 模板） */
    private boolean deepSyncing;

    // ---------------- 以下由后端算好下发，页面只渲染 ----------------

    /** ONT 全局汇总：total / online / offline 三个数 */
    private Map<String, Integer> ontSummary;

    /** 板卡类别统计文本：共 7 块板卡（业务板 2 / 主控板 2 / 上联板 2 / 电源板 1） */
    private String boardSummary;

    /** 机框要画几个槽位：设备回了就用它，没回按板卡最大槽位 + 1、再按码表兜底 */
    private Integer chassisSlotCount;

    /** 有板卡停在 auto-find（插上了没确认）时为 true，页面据此显示「确认所有业务板」 */
    private boolean boardsNeedConfirm;

    /** ONT 页汇总句里的端口总数（有端口的业务板端口之和） */
    private Integer ontPortTotal;

    /** ONT 页汇总句里的业务板块数（有端口的板卡） */
    private Integer ontBoardCount;

    /** VLAN 按类型分好的行：type / label / text（压缩后的 ID 串）/ std / serv，末行是二层互通 VLAN */
    private List<Map<String, Object>> vlanGroups;

    /** 有 ONT 的业务板（「删除业务板 ONU」用）：slotNo + total，按槽位升序 */
    private List<Map<String, Object>> deletableBoards = new ArrayList<>();

    /** 这些业务板上的 ONT 总数：0 表示没有可删的，页面按钮置灰 */
    private Integer deletableOntTotal;

    /** ONT 摊平行：板卡 × 端口 × ONT，一条一行 */
    private List<Map<String, Object>> ontRows = new ArrayList<>();

    /** ONT 列表的「板卡端口」筛选下拉：value 是 {@code 槽位/端口}，label 是「槽位 2 - 0/2/1」 */
    private List<Map<String, Object>> ontPortOptions = new ArrayList<>();

    /** ONT 按板卡分表的视图：no / type / onuSummary / ports[{no,name,iface,onuTotal,onuOnline */
    private List<Map<String, Object>> ontCards = new ArrayList<>();
}

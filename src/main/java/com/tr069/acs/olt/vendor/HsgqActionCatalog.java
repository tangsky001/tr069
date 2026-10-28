package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.model.OltActionParam;

import java.util.ArrayList;
import java.util.List;

import static com.tr069.acs.olt.model.OltAction.config;
import static com.tr069.acs.olt.model.OltAction.in;
import static com.tr069.acs.olt.model.OltAction.query;
import static com.tr069.acs.olt.model.OltAction.queryIn;
import static com.tr069.acs.olt.model.OltAction.queryWith;

/**
 * 鸿升光 EPON OLT 的命令动作全集，逐条对照《EPON 光纤接入局端设备 4 口&amp;8 口系列命令行操作手册 V3.0.2》。
 *
 * <p><b>怎么加一条命令：</b>在对应章节的方法里加一行 {@code config(...) / in(...) / query(...)}，
 * 命令模板照抄手册那一格，参数用 {@code {key}} 占位并加进 {@code params}。
 * 页面会自动出现这个功能，不用改前端。</p>
 *
 * <p>视图规则（命令在哪个提示符下执行）：</p>
 * <ul>
 *   <li>{@link OltAction.View#CONFIG}：{@code OLT(config)#}，直接下发；</li>
 *   <li>{@link OltAction.View#VLAN}：先 {@code vlan standard <vlanId>} 进 vlan 视图，执行完 {@code exit}；</li>
 *   <li>{@link OltAction.View#PORT}：先 {@code interface <portType> <portNo>} 进端口视图；</li>
 *   <li>{@link OltAction.View#VLANIF}：先 {@code interface vlanif <vlanId>} 进三层接口视图。</li>
 * </ul>
 *
 * <p>已经录入的章节见 {@link #actions()}；手册里还剩 3.6~3.11（配置管理/升级/诊断/时间/用户/系统服务）、
 * 5~12 章，按同样格式继续往这里加即可。</p>
 */
public final class HsgqActionCatalog {

    private static final String CH_VLAN = "4.1 全局 VLAN 配置";

    private static final String CH_PORT_VLAN = "4.2 端口 VLAN 配置";

    private static final String CH_TRANSLATION = "4.3 VLAN 转换配置";

    private static final String CH_QINQ = "4.4 VLAN QinQ 配置";

    private static final String CH_HOSTNAME = "3.1 配置设备名称";

    private static final String CH_VERSION = "3.2 查看系统版本信息";

    private static final String CH_CPU = "3.3 查看系统 CPU 利用率";

    private static final String CH_MEMORY = "3.4 查看系统内存利用率";

    private static final String CH_HISTORY = "3.5 查看历史输入命令";

    private static final String CH_ROUTE = "3.12 配置缺省路由";

    private static final String CH_DNS = "3.13 DNS 配置";

    private static final String CH_ACCESS = "3.14 设备访问控制";

    private static final String CH_SAVE = "3.6.1 保存系统配置";

    private static final String CH_RUNNING = "3.6.2 查看系统配置";

    private static final String CH_FACTORY = "3.6.3 设备恢复出厂";

    private static final String CH_PING = "3.8 系统诊断";

    private static final String CH_TIMEZONE = "3.9.1 配置时区";

    private static final String CH_TIME = "3.9.2 设置系统时间";

    private static final String CH_NTP = "3.9.3 NTP 配置";

    private static final String CH_USER = "3.10 用户管理";

    private static final String CH_SERVICE = "3.11 系统服务";

    private static final String CH_MGMT = "2.3.2 带内管理口配置";

    private static final String CH_PORT_STATE = "5.1 端口状态管理";

    private static final String CH_PORT_DESC = "5.2 端口描述信息配置";

    private static final String CH_PORT_DUPLEX = "5.3 端口双工模式配置";

    private static final String CH_PORT_SPEED = "5.4 端口速率配置";

    private static final String CH_PORT_MTU = "5.5 端口 MTU 配置";

    private static final String CH_PORT_STORM = "5.6 广播/组播/未知单播风暴抑制";

    private static final String CH_PORT_RATE = "5.7 端口限速及流量控制";

    private static final String CH_PORT_STAT = "5.8 流量统计";

    private static final String CH_PORT_MIRROR = "5.9 端口镜像";

    private static final String CH_LOOP = "5.10 环路检测";

    private static final String CH_PORT_GROUP = "5.11 端口组";

    private static final String CH_PON_AUTH = "6.1 ONU 的注册及认证";

    private static final String CH_PON_OPTICAL = "6.2 PON 发光开关";

    private static final String CH_PON_DBA = "6.3 DBA 模板";

    private static final String CH_PON_VERSION = "6.4 查看 ONU 版本";

    private static final String CH_PON_MODULE = "6.5 查看 PON 光模块";

    private static final String CH_PON_RSSI = "6.6 查看 ONU 光功率";

    private static final String CH_PON_STATE = "6.7 查看 PON 光模块状态";

    private static final String CH_ONU_UPGRADE = "6.8 ONU 升级";

    private static final String CH_ONU_LOOP = "6.9 ONU 环路检测";

    private static final String CH_ONU_MGMT = "7.1-7.4 ONU 管理（重注册/描述/名字/fec）";

    private static final String CH_ONU_PORT = "7.5 ONU 端口配置";

    private static final String CH_MAC = "8 MAC 地址管理";

    private static final String CH_MAC_EPON = "8.2 PON 侧 MAC 地址表";

    private static final String CH_STP = "9.1 RSTP";

    private static final String CH_MSTP = "9.2 MSTP";

    private static final String CH_IGMP = "9.3.1-9.3.2 IGMP 全局配置";

    private static final String CH_MCAST_VLAN = "9.3.3 组播 VLAN 配置";

    private static final String CH_IGMP_ENTRY = "9.3.4 组播表项";

    private static final String CH_ONU_MCAST = "9.3.5 ONU 组播配置";

    private static final String CH_DHCP = "9.4 DHCP";

    private static final String CH_TIMERANGE = "10.1 时间段（Time-range）";

    private static final String CH_ACL = "10.2 ACL 管理";

    private static final String CH_FILTER = "10.3 包过滤（Packet Filter）";

    private static final String CH_QOS = "10.4 QOS";

    private static final String CH_CONFIG_FTP = "3.6.4 配置文件导入导出";

    private static final String CH_UPGRADE = "3.7 系统升级";

    private static final String CH_ROUTE2 = "11 路由配置";

    private static final String CH_LAG = "12 链路聚合";

    private HsgqActionCatalog() {
    }

    /** 这台设备支持的全部动作（页面按 chapter 分组渲染） */
    public static List<OltAction> actions() {
        List<OltAction> actions = new ArrayList<>();
        actions.addAll(vlan());
        actions.addAll(system());
        actions.addAll(port());
        actions.addAll(pon());
        actions.addAll(ponExt());
        actions.addAll(onu());
        actions.addAll(mac());
        actions.addAll(protocol());
        actions.addAll(aclQos());
        actions.addAll(routeAgg());
        return actions;
    }

    /** —— 第 4 章 VLAN 管理（手册 p34-39） —— */
    private static List<OltAction> vlan() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam vlanId = OltActionParam.number("vlanId", "VLAN ID", 1, 4094, "取值 1-4094");
        OltActionParam start = OltActionParam.number("start", "起始 VLAN ID", 1, 4094, "取值 1-4094");
        OltActionParam end = OltActionParam.number("end", "终止 VLAN ID", 1, 4094, "要大于等于起始 ID");
        OltActionParam portType = OltActionParam.portType();
        OltActionParam portList = OltActionParam.ports("ports", "端口列表", "数值格式，如 1,3,4,5-8");
        OltActionParam memberMode = OltActionParam.choice("mode", "加入方式", List.of("tagged", "untagged"),
                "untagged", "tagged 带标签透传，untagged 不打标签");

        // 4.1 全局 VLAN
        list.add(config("vlan.create", CH_VLAN, "创建单个 VLAN", "端口 VLAN，取值 1-4094",
                List.of(vlanId), "vlan standard {vlanId}"));
        list.add(config("vlan.delete", CH_VLAN, "删除单个 VLAN", null,
                List.of(vlanId), "no vlan {vlanId}"));
        list.add(config("vlan.createBatch", CH_VLAN, "批量创建连续 VLAN", "起止都含",
                List.of(start, end), "vlan standard {start} {end}"));
        list.add(config("vlan.deleteBatch", CH_VLAN, "批量删除连续 VLAN", "起止都含",
                List.of(start, end), "no vlan {start} {end}"));
        list.add(config("vlan.deleteAll", CH_VLAN, "删除所有 VLAN", "设备上所有 VLAN 一起删，谨慎",
                List.of(), "no vlan all").dangerous());
        list.add(in(OltAction.View.VLAN, "vlan.member.add", CH_VLAN, "添加成员端口到 VLAN",
                "仅端口模式为 hybrid 时可用", List.of(vlanId, portType, portList, memberMode),
                "port {portType} {ports} {mode}"));
        list.add(in(OltAction.View.VLAN, "vlan.member.default", CH_VLAN, "配置 VLAN 为指定端口的缺省 VLAN",
                null, List.of(vlanId, portType, portList), "port {portType} {ports} default-vlan"));
        list.add(in(OltAction.View.VLAN, "vlan.member.remove", CH_VLAN, "从 VLAN 删除成员端口", null,
                List.of(vlanId, portType, portList), "no port {portType} {ports}"));
        list.add(queryIn(OltAction.View.VLAN, "vlan.show", CH_VLAN, "查看当前 VLAN",
                "回显 Tagged / Untagged / Default Vlan Ports", List.of(vlanId), "show vlan"));

        // 4.2 端口 VLAN
        OltActionParam portNo = OltActionParam.portNo("端口号");
        OltActionParam vlanList = OltActionParam.ports("vlanList", "VLAN 列表", "如 100-200,300");
        list.add(in(OltAction.View.PORT, "port.vlan.mode", CH_PORT_VLAN, "配置端口 VLAN 模式",
                "端口默认 hybrid", List.of(portType, portNo,
                        OltActionParam.choice("mode", "VLAN 模式", List.of("access", "hybrid", "trunk"),
                                "hybrid", "access 只允许一个 VLAN；trunk 允许多个，默认 pvid；hybrid 自由配")),
                "vlan mode {mode}"));
        list.add(in(OltAction.View.PORT, "port.vlan.access", CH_PORT_VLAN, "配置端口 VLAN（access）",
                "只允许一个 VLAN，该 VLAN 即 pvid", List.of(portType, portNo, vlanId), "vlan access {vlanId}"));
        list.add(in(OltAction.View.PORT, "port.vlan.trunk", CH_PORT_VLAN, "配置端口 VLAN（trunk）",
                "多个 VLAN，默认 pvid", List.of(portType, portNo, vlanList), "vlan trunk {vlanList}"));
        list.add(in(OltAction.View.PORT, "port.vlan.hybrid", CH_PORT_VLAN, "配置端口 VLAN（hybrid）",
                "自由配置，配合 4.1 的成员端口命令", List.of(portType, portNo, vlanList,
                        OltActionParam.choice("tagMode", "标签方式", List.of("tagged", "untagged"),
                                "untagged", null)),
                "vlan hybrid {vlanList} {tagMode}"));
        list.add(in(OltAction.View.PORT, "port.vlan.default", CH_PORT_VLAN, "配置端口缺省 VLAN", null,
                List.of(portType, portNo, vlanId), "port default-vlan {vlanId}"));
        list.add(queryIn(OltAction.View.PORT, "port.vlan.show", CH_PORT_VLAN, "查看端口 VLAN",
                "回显 Port type / Port default vlan / Tagged vlan / Untagged vlan",
                List.of(portType, portNo), "show vlan"));

        // 4.3 VLAN 转换
        list.add(in(OltAction.View.PORT, "vlan.translation.add", CH_TRANSLATION, "配置 VLAN 转换",
                "VLAN 转换与 QinQ 共用资源，合计 1024 条",
                List.of(portType, portNo,
                        OltActionParam.number("oldVlan", "原 VLAN", 1, 4094, "转换之前"),
                        OltActionParam.number("newVlan", "新 VLAN", 1, 4094, "转换之后")),
                "vlan translation {oldVlan} {newVlan}"));
        list.add(in(OltAction.View.PORT, "vlan.translation.remove", CH_TRANSLATION, "删除 VLAN 转换", null,
                List.of(portType, portNo, OltActionParam.number("oldVlan", "原 VLAN", 1, 4094, null)),
                "no vlan translation {oldVlan}"));
        list.add(queryIn(OltAction.View.PORT, "vlan.translation.show", CH_TRANSLATION, "查看 VLAN 转换", null,
                List.of(portType, portNo), "show vlan translation"));

        // 4.4 VLAN QinQ
        OltActionParam cvlan = OltActionParam.number("cvlan", "用户侧 VLAN", 1, 4094, "CVLAN");
        OltActionParam svlan = OltActionParam.number("svlan", "服务侧 VLAN", 1, 4094, "SVLAN");
        OltActionParam startCvlan = OltActionParam.number("startCvlan", "起始 CVLAN", 1, 4094, null);
        OltActionParam endCvlan = OltActionParam.number("endCvlan", "终止 CVLAN", 1, 4094, null);
        list.add(in(OltAction.View.PORT, "vlan.qinq.add", CH_QINQ, "配置 VLAN QinQ（单个内层）",
                "与 VLAN 转换共用 1024 条资源", List.of(portType, portNo, cvlan, svlan),
                "vlan qinq {cvlan} {svlan}"));
        list.add(in(OltAction.View.PORT, "vlan.qinq.batch", CH_QINQ, "配置 VLAN QinQ（连续内层）",
                "一段 CVLAN 加同一个外层", List.of(portType, portNo, startCvlan, endCvlan, svlan),
                "vlan qinq batch {startCvlan} {endCvlan} {svlan}"));
        list.add(in(OltAction.View.PORT, "vlan.qinq.remove", CH_QINQ, "删除 VLAN QinQ（单个内层）", null,
                List.of(portType, portNo, cvlan), "no vlan qinq {cvlan}"));
        list.add(in(OltAction.View.PORT, "vlan.qinq.removeBatch", CH_QINQ, "删除 VLAN QinQ（连续内层）", null,
                List.of(portType, portNo, startCvlan, endCvlan),
                "no vlan qinq batch {startCvlan} {endCvlan}"));
        list.add(queryIn(OltAction.View.PORT, "vlan.qinq.show", CH_QINQ, "查看 VLAN QinQ", null,
                List.of(portType, portNo), "show vlan qinq"));
        return list;
    }

    /** —— 第 5 章 端口管理（手册 p40-53） —— */
    private static List<OltAction> port() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam portType = OltActionParam.portType();
        OltActionParam portNo = OltActionParam.portNo("端口号");
        OltActionParam portList = OltActionParam.ports("ports", "端口列表", "如 1-4,5");

        // 5.1 端口状态
        list.add(in(OltAction.View.PORT, "port.state", CH_PORT_STATE, "端口状态配置",
                "disable 后该端口不再收发业务", List.of(portType, portNo,
                        OltActionParam.choice("state", "状态", List.of("enable", "disable"), "enable", null)),
                "port {state}"));
        list.add(queryIn(OltAction.View.PORT, "port.state.show", CH_PORT_STATE, "查看本端口状态",
                "回显 Port Name / Port State / Link speed / MTU / Link status",
                List.of(portType, portNo), "show state"));
        list.add(query("port.state.all", CH_PORT_STATE, "查看所有端口状态", null, "show port state all"));

        // 5.2 ~ 5.6
        list.add(in(OltAction.View.PORT, "port.desc", CH_PORT_DESC, "配置端口描述",
                "描述信息可写中文", List.of(portType, portNo,
                        OltActionParam.text("desc", "描述信息", "如 上联核心交换机")),
                "port desc {desc}"));
        list.add(in(OltAction.View.PORT, "port.duplex", CH_PORT_DUPLEX, "配置端口双工模式", null,
                List.of(portType, portNo,
                        OltActionParam.choice("mode", "双工模式", List.of("full", "half"), "full", null)),
                "port duplex {mode}"));
        list.add(in(OltAction.View.PORT, "port.speed", CH_PORT_SPEED, "配置端口速率", null,
                List.of(portType, portNo,
                        OltActionParam.choice("speed", "速率", List.of("10", "100", "1000", "10g", "auto"),
                                "auto", null)),
                "port speed {speed}"));
        list.add(in(OltAction.View.PORT, "port.mtu", CH_PORT_MTU, "配置端口 MTU", "取值 128-2000",
                List.of(portType, portNo, OltActionParam.number("mtu", "MTU", 128, 2000, "如 1522")),
                "port mtu {mtu}"));
        OltActionParam pps = OltActionParam.number("pps", "限制包数(pps)", 0, 10000000,
                "每秒允许的包数，越大限制越弱");
        list.add(in(OltAction.View.PORT, "port.storm.broadcast", CH_PORT_STORM, "配置广播风暴抑制", null,
                List.of(portType, portNo, pps), "port storm-control broadcast pps {pps}"));
        list.add(in(OltAction.View.PORT, "port.storm.multicast", CH_PORT_STORM, "配置组播风暴抑制", null,
                List.of(portType, portNo, pps), "port storm-control multicast pps {pps}"));
        list.add(in(OltAction.View.PORT, "port.storm.unicast", CH_PORT_STORM, "配置未知单播风暴抑制", null,
                List.of(portType, portNo, pps), "port storm-control unicast pps {pps}"));
        list.add(queryIn(OltAction.View.PORT, "port.storm.show", CH_PORT_STORM, "查看风暴抑制配置", null,
                List.of(portType, portNo), "show storm-control"));

        // 5.7 限速与流控
        list.add(in(OltAction.View.PORT, "port.flowcontrol", CH_PORT_RATE, "使能/去使能端口流控", null,
                List.of(portType, portNo,
                        OltActionParam.choice("state", "状态", List.of("enable", "disable"), "enable", null)),
                "port flow-control {state}"));
        OltActionParam direction = OltActionParam.choice("direction", "方向",
                List.of("egress", "ingress"), "egress", "egress 出方向，ingress 入方向");
        list.add(in(OltAction.View.PORT, "port.ratelimit", CH_PORT_RATE, "配置端口限速",
                "速率 64-10240000 kbps", List.of(portType, portNo, direction,
                        OltActionParam.number("rate", "限速值(kbps)", 64, 10240000, "如 1024")),
                "port rate-limit {direction} {rate}"));
        list.add(in(OltAction.View.PORT, "port.ratelimit.del", CH_PORT_RATE, "取消端口限速", null,
                List.of(portType, portNo, direction), "no port rate-limit {direction}"));

        // 5.8 流量统计
        list.add(queryIn(OltAction.View.PORT, "port.stat.show", CH_PORT_STAT, "查看本端口流量统计",
                "收发字节/帧数、各长度分档、错误帧", List.of(portType, portNo), "show statistic"));
        list.add(in(OltAction.View.PORT, "port.stat.clear", CH_PORT_STAT, "清除端口流量统计", null,
                List.of(portType, portNo), "no statistic"));
        list.add(query("port.stat.all", CH_PORT_STAT, "查看所有端口流量统计", null, "show statistic all"));

        // 5.9 端口镜像
        list.add(in(OltAction.View.PORT, "port.mirror", CH_PORT_MIRROR, "配置端口镜像",
                "把本端口的数据镜像到指定的 ge 口",
                List.of(portType, portNo,
                        OltActionParam.number("dstPort", "目的 GE 口", 1, 128, "如 1"),
                        OltActionParam.choice("direction", "镜像方向",
                                List.of("all", "egress", "ingress"), "all", null)),
                "mirror dst-port ge {dstPort} {direction}"));
        list.add(in(OltAction.View.PORT, "port.mirror.del", CH_PORT_MIRROR, "删除端口镜像", null,
                List.of(portType, portNo), "no mirror"));

        // 5.10 环路检测
        OltActionParam interval = OltActionParam.number("interval", "报文间隔(秒)", 3, 3600, "默认 5");
        OltActionParam recoverTime = OltActionParam.number("seconds", "自动恢复时间(秒)", 3, 3600, "默认 30");
        OltActionParam recoverMode = OltActionParam.choice("mode", "恢复模式",
                List.of("auto", "manual"), "auto", "auto 自动恢复，manual 手动恢复");
        list.add(config("loop.pon.enable", CH_LOOP, "开启/关闭 PON 环路检测", "默认开启",
                List.of(OltActionParam.choice("state", "状态", List.of("enable", "disable"), "enable", null)),
                "loop-detect pon {state}"));
        list.add(config("loop.pon.interval", CH_LOOP, "配置 PON 环路报文间隔", null,
                List.of(interval), "loop-detect pon packet-send interval {interval}"));
        list.add(config("loop.pon.mode", CH_LOOP, "配置 PON 环路恢复模式", null,
                List.of(recoverMode), "loop-detect pon recover-mode {mode}"));
        list.add(config("loop.pon.recover", CH_LOOP, "PON 环路手动恢复", null, List.of(),
                "loop-detect pon recover manual"));
        list.add(config("loop.pon.time", CH_LOOP, "配置 PON 环路自动恢复时间", null,
                List.of(recoverTime), "loop-detect pon recover-time {seconds}"));
        list.add(query("loop.pon.show", CH_LOOP, "查看 PON 环路配置", null, "show loop-detect pon info"));
        list.add(config("loop.uplink.enable", CH_LOOP, "开启/关闭上联口环路检测",
                "生成树协议开启时不生效", List.of(OltActionParam.choice("state", "状态",
                        List.of("enable", "disable"), "enable", null)), "loop-detect uplink {state}"));
        list.add(config("loop.uplink.interval", CH_LOOP, "配置上联口环路报文间隔", null,
                List.of(interval), "loop-detect uplink packet-send interval {interval}"));
        list.add(config("loop.uplink.mode", CH_LOOP, "配置上联口环路恢复模式", null,
                List.of(recoverMode), "loop-detect uplink recover-mode {mode}"));
        list.add(config("loop.uplink.recover", CH_LOOP, "上联口环路手动恢复", null, List.of(),
                "loop-detect uplink recover manual"));
        list.add(config("loop.uplink.time", CH_LOOP, "配置上联口环路自动恢复时间", null,
                List.of(recoverTime), "loop-detect uplink recover-time {seconds}"));
        list.add(query("loop.uplink.show", CH_LOOP, "查看上联口环路配置", null, "show loop-detect uplink info"));

        // 5.11 端口组
        list.add(config("portgroup.mode", CH_PORT_GROUP, "配置端口组模式",
                "isolate 隔离，inter-working 互通；切换后回到该模式的缺省配置",
                List.of(OltActionParam.choice("mode", "模式",
                        List.of("isolate", "inter-working"), "isolate", null)),
                "port-group mode {mode}"));
        list.add(config("portgroup.isolate", CH_PORT_GROUP, "配置端口隔离", null,
                List.of(portType, portList), "port-group isolate {portType} {ports}"));
        list.add(config("portgroup.isolate.del", CH_PORT_GROUP, "删除端口隔离成员", null,
                List.of(portType, portList), "no port-group isolate {portType} {ports}"));
        list.add(config("portgroup.interwork", CH_PORT_GROUP, "配置互通组",
                "先选入端口，再选出端口，只有组成员之间互通",
                List.of(portType, OltActionParam.number("inPort", "入端口号", 1, 128, "如 1"),
                        OltActionParam.choice("outType", "出端口类型",
                                List.of("epon", "ge", "xge"), "ge", null),
                        OltActionParam.number("outPort", "出端口号", 1, 128, "如 1")),
                "port-group inter-working ingress-port {portType} {inPort} egress {outType} {outPort}"));
        list.add(config("portgroup.interwork.reset", CH_PORT_GROUP, "恢复互通组为默认配置", null,
                List.of(), "no port-group inter-working ingress-port all"));
        list.add(query("portgroup.show", CH_PORT_GROUP, "查看端口组配置", null, "show port-group"));
        return list;
    }

    /** —— 第 6 章 PON 管理（手册 p54 起） —— */
    private static List<OltAction> pon() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam ponId = OltActionParam.number("ponId", "PON 口号", 1, 16, "如 1");

        list.add(queryIn(OltAction.View.PON, "pon.authorize.show", CH_PON_AUTH, "查看 PON 口认证方式",
                "回显 AUTH-MODE / AUTH-TYPE", List.of(ponId), "onu-authorize"));
        list.add(in(OltAction.View.PON, "pon.authorize.type", CH_PON_AUTH, "设置 PON 口认证类型",
                "auto 自动注册，manual 手动注册；有 ONU 在线时不能改", List.of(ponId,
                        OltActionParam.choice("type", "认证类型", List.of("auto", "manual"), "auto", null)),
                "onu-authorize type {type}"));
        list.add(in(OltAction.View.PON, "pon.authorize.mode", CH_PON_AUTH, "设置 PON 口认证模式",
                "mac / loid / loid-password / hybrid 四选一", List.of(ponId,
                        OltActionParam.choice("mode", "认证模式",
                                List.of("mac", "loid", "loid-password", "hybrid"), "mac", null)),
                "onu-authorize mode {mode}"));
        list.add(queryIn(OltAction.View.PON, "pon.onu.list", CH_PON_AUTH, "查看 PON 口下 ONU 列表",
                "按 MAC / 状态 / 授权状态 / 注册时间列出", List.of(ponId), "show onu-info all"));
        list.add(queryIn(OltAction.View.PON, "pon.onu.detail", CH_PON_AUTH, "查看指定 ONU 详情",
                "ONU ID 取值 1-64", List.of(ponId,
                        OltActionParam.number("onuId", "ONU ID", 1, 64, null)), "show onu-info onu-id {onuId}"));
        return list;
    }

    /** —— 第 6 章剩余：发光开关 / DBA / 光功率 / 升级 / ONU 环路 —— */
    private static List<OltAction> ponExt() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam ponId = OltActionParam.number("ponId", "PON 口号", 1, 16, "如 1");
        OltActionParam onuId = OltActionParam.number("onuId", "ONU ID", 1, 64, "ONU 在该 PON 口下的编号");

        // 6.1.1 MAC 认证（PON 视图）
        OltActionParam mac = OltActionParam.text("mac", "ONU MAC", "格式 AA:BB:CC:DD:EE:FF，即 ONU 标签上的 SN");
        list.add(in(OltAction.View.PON, "pon.mac.bind", CH_PON_AUTH, "MAC 认证：添加白名单并绑定 ONU",
                "不填 ONU ID 时由设备自动分配",
                List.of(ponId, OltActionParam.optionalNumber("onuId", "ONU ID", 1, 64, "可选"),
                        mac), "bind-onu[{ onuId}] mac {mac}"));
        list.add(in(OltAction.View.PON, "pon.mac.confirm", CH_PON_AUTH, "MAC 认证：确认手动注册的 ONU",
                "PON 口下没有在线 ONU 时，把手动注册的 ONU 正式加入", List.of(ponId, mac),
                "onu confirm mac {mac}"));
        list.add(in(OltAction.View.PON, "pon.mac.unbind", CH_PON_AUTH, "MAC 认证：删除白名单", null,
                List.of(ponId, mac), "no bind-onu mac {mac}"));

        // 6.1.2 LOID 认证（PON 视图）
        OltActionParam loid = OltActionParam.text("loid", "LOID", "ONU 的 LOID 标识");
        list.add(in(OltAction.View.PON, "pon.loid.add", CH_PON_AUTH, "LOID 认证：添加 LOID",
                "先切到 LOID 认证方式（onu-authorize mode loid）再添加",
                List.of(ponId, loid), "loid add {loid}"));
        list.add(in(OltAction.View.PON, "pon.loid.delete", CH_PON_AUTH, "LOID 认证：删除 LOID", null,
                List.of(ponId, loid), "loid delete {loid}"));

        // 6.1.3 黑名单（全局配置视图）
        list.add(query("pon.blacklist.show", CH_PON_AUTH, "查看 ONU 黑名单",
                "默认白名单模式，拒绝注册的 ONU 进黑名单，最多 32 条", "show blacklist onu-info all"));
        list.add(config("pon.blacklist.add", CH_PON_AUTH, "添加黑名单 MAC", "黑名单里的 ONU 不能注册",
                List.of(mac, OltActionParam.text("reason", "原因", "如 非法ONU")),
                "blacklist add mac {mac} {reason}"));
        list.add(config("pon.blacklist.delete", CH_PON_AUTH, "删除黑名单 MAC", null, List.of(mac),
                "blacklist delete mac {mac}"));

        // 6.2 发光开关（PON 视图）
        list.add(in(OltAction.View.PON, "pon.optical", CH_PON_OPTICAL, "开启/关闭 PON 口发光", null,
                List.of(ponId, OltActionParam.choice("state", "状态",
                        List.of("enable", "disable"), "enable", null)), "port optical-state {state}"));

        // 6.3 DBA（PON 视图）
        OltActionParam fix = OltActionParam.number("fix", "固定带宽", 1, 10240000, "单位由设备决定");
        OltActionParam assure = OltActionParam.number("assure", "保证带宽", 1, 10240000, null);
        OltActionParam max = OltActionParam.number("max", "最大带宽", 1, 10240000, null);
        list.add(in(OltAction.View.PON, "pon.dba.type1", CH_PON_DBA, "DBA Type1（固定带宽）", null,
                List.of(ponId, onuId, fix), "sla-up {onuId} type1 fix {fix}"));
        list.add(in(OltAction.View.PON, "pon.dba.type2", CH_PON_DBA, "DBA Type2（保证带宽）", null,
                List.of(ponId, onuId, assure), "sla-up {onuId} type2 assure {assure}"));
        list.add(in(OltAction.View.PON, "pon.dba.type3", CH_PON_DBA, "DBA Type3（保证 + 最大）", null,
                List.of(ponId, onuId, assure, max), "sla-up {onuId} type3 assure {assure} max {max}"));
        list.add(in(OltAction.View.PON, "pon.dba.type4", CH_PON_DBA, "DBA Type4（最大带宽）", null,
                List.of(ponId, onuId, max), "sla-up {onuId} type4 max {max}"));
        list.add(in(OltAction.View.PON, "pon.dba.type5", CH_PON_DBA, "DBA Type5（固定 + 保证 + 最大）", null,
                List.of(ponId, onuId, fix, assure, max),
                "sla-up {onuId} type5 fix {fix} assure {assure} max {max}"));

        // 6.4 ~ 6.7 查询（PON 视图）
        list.add(queryIn(OltAction.View.PON, "pon.onu.version", CH_PON_VERSION, "查看 PON 口下 ONU 版本", null,
                List.of(ponId), "show onu-version all"));
        list.add(queryIn(OltAction.View.PON, "pon.module.info", CH_PON_MODULE, "查看 PON 光模块信息",
                "温度/电压/电流/收发光功率", List.of(ponId), "show optical-info"));
        list.add(queryIn(OltAction.View.PON, "pon.onu.rssi", CH_PON_RSSI, "查看 ONU 光功率",
                "ONU 收发功率，判断线路质量", List.of(ponId, onuId), "show optical-rssi {onuId}"));
        list.add(queryIn(OltAction.View.PON, "pon.module.state", CH_PON_STATE, "查看 PON 光模块状态", null,
                List.of(ponId), "show optical-state"));

        // 6.8 ONU 升级
        OltActionParam typeStr = OltActionParam.text("typeStr", "ONU 型号", "如 HSGQ-ONU-1G");
        list.add(config("pon.upgrade.type.add", CH_ONU_UPGRADE, "添加升级模式", null, List.of(typeStr),
                "onu-upgrade type add {typeStr}"));
        list.add(config("pon.upgrade.type.del", CH_ONU_UPGRADE, "删除升级模式", null, List.of(typeStr),
                "onu-upgrade type del {typeStr}"));
        list.add(query("pon.upgrade.type.show", CH_ONU_UPGRADE, "查看已注册的升级模式", null,
                "show onu-upgrade type"));
        list.add(config("pon.upgrade.load.ftp", CH_ONU_UPGRADE, "从 FTP 加载升级文件",
                "文件放在 FTP 根目录下",
                List.of(OltActionParam.text("ip", "FTP 服务器 IP", "如 192.168.100.100"),
                        OltActionParam.text("file", "文件名", "如 onu.bin")),
                "onu-upgrade load ftp {ip} {file}"));
        list.add(config("pon.upgrade.load.tftp", CH_ONU_UPGRADE, "从 TFTP 加载升级文件", null,
                List.of(OltActionParam.text("ip", "TFTP 服务器 IP", null),
                        OltActionParam.text("name", "用户名", "TFTP 一般填匿名"),
                        OltActionParam.text("password", "密码", null),
                        OltActionParam.text("file", "文件名", null)),
                "onu-upgrade load tftp {ip} {name} {password} {file}"));
        list.add(query("pon.upgrade.file.show", CH_ONU_UPGRADE, "查看升级文件", null, "show onu-upgrade file"));
        list.add(in(OltAction.View.PON, "pon.upgrade.bind.all", CH_ONU_UPGRADE, "给 PON 口所有 ONU 绑定升级模式",
                null, List.of(ponId, typeStr), "bind-onu upgrade-type {typeStr} all"));
        list.add(in(OltAction.View.PON, "pon.upgrade.bind.list", CH_ONU_UPGRADE, "给指定 ONU 绑定升级模式",
                "ONU 列表用逗号分隔，如 1,3,5",
                List.of(ponId, typeStr, OltActionParam.ports("onuList", "ONU 列表", "如 1,3,5")),
                "bind-onu upgrade-type {typeStr} onu-list {onuList}"));
        list.add(in(OltAction.View.PON, "pon.upgrade.start", CH_ONU_UPGRADE, "升级指定 ONU", null,
                List.of(ponId, onuId), "onu-upgrade onu-id {onuId}"));
        list.add(in(OltAction.View.PON, "pon.upgrade.start.all", CH_ONU_UPGRADE, "升级 PON 口下所有 ONU",
                "整口一起升级，业务会中断", List.of(ponId, typeStr),
                "onu-upgrade all-pon-port upgrade-type {typeStr}"));

        // 6.9 ONU 环路
        list.add(in(OltAction.View.PON, "pon.loop.reject", CH_ONU_LOOP, "开启/关闭 ONU 环路自动拒绝",
                "检测到 ONU 下挂环路时自动拒绝该 ONU", List.of(ponId,
                        OltActionParam.choice("state", "状态", List.of("enable", "disable"), "enable", null)),
                "loopback-onu auto-reject {state}"));
        return list;
    }

    /** —— 第 7 章 ONU 管理（手册 p69-76）：命令都在 ONU 管理视图，进视图用 interface onu 1/1 —— */
    private static List<OltAction> onu() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam ponId = OltActionParam.number("ponId", "PON 口号", 1, 16, "如 1");
        OltActionParam onuId = OltActionParam.number("onuId", "ONU ID", 1, 64, "如 1");
        OltActionParam portId = OltActionParam.number("portId", "ONU 端口号", 1, 26,
                "ONU 上的 UNI 口，1-26");
        OltActionParam state = OltActionParam.choice("state", "状态",
                List.of("enable", "disable"), "enable", null);

        list.add(in(OltAction.View.ONU, "onu.deregister", CH_ONU_MGMT, "ONU 重新注册",
                "让 ONU 掉线重注册，业务短暂中断", List.of(ponId, onuId), "deregister").dangerous());
        list.add(in(OltAction.View.ONU, "onu.description", CH_ONU_MGMT, "配置 ONU 描述",
                "描述可写中文，命令里用双引号包起来", List.of(ponId, onuId,
                        OltActionParam.text("desc", "描述", "如 3栋2单元")), "description \"{desc}\""));
        list.add(in(OltAction.View.ONU, "onu.name", CH_ONU_MGMT, "配置 ONU 名称", null,
                List.of(ponId, onuId, OltActionParam.text("name", "名称", "如 test")), "name {name}"));
        list.add(in(OltAction.View.ONU, "onu.fec", CH_ONU_MGMT, "配置 ONU FEC 功能", null,
                List.of(ponId, onuId, state), "fec-mode mode {state}"));

        // 7.5 ONU 端口配置
        list.add(in(OltAction.View.ONU, "onu.port.enable", CH_ONU_PORT, "打开 ONU 端口", null,
                List.of(ponId, onuId, portId), "no port-shutdown {portId}"));
        list.add(in(OltAction.View.ONU, "onu.port.disable", CH_ONU_PORT, "关闭 ONU 端口", null,
                List.of(ponId, onuId, portId), "port-shutdown {portId}"));
        list.add(queryIn(OltAction.View.ONU, "onu.port.link", CH_ONU_PORT, "查看 ONU 端口链路状态", null,
                List.of(ponId, onuId), "show onu-link-staus"));
        list.add(in(OltAction.View.ONU, "onu.port.ratelimit.egress", CH_ONU_PORT, "ONU 端口出口限速",
                "CIR 保证速率、PIR 峰值速率，128-10240000 kbps", List.of(ponId, onuId, portId,
                        OltActionParam.number("cir", "CIR(kbps)", 128, 10240000, "如 1000"),
                        OltActionParam.number("pir", "PIR(kbps)", 128, 10240000, "如 100000")),
                "port-rate-limit {portId} egress cir {cir} pir {pir}"));
        list.add(in(OltAction.View.ONU, "onu.port.ratelimit.ingress", CH_ONU_PORT, "ONU 端口入口限速",
                "128-10240000 kbps", List.of(ponId, onuId, portId,
                        OltActionParam.number("bandwidth", "限速(kbps)", 128, 10240000, null)),
                "port-rate-limit {portId} ingress bandwidth {bandwidth}"));
        list.add(in(OltAction.View.ONU, "onu.port.loop", CH_ONU_PORT, "ONU 端口环路检测", null,
                List.of(ponId, onuId, portId, state), "port-loop-detect {portId} {state}"));
        list.add(in(OltAction.View.ONU, "onu.port.autoneg", CH_ONU_PORT, "ONU 端口自适应", null,
                List.of(ponId, onuId, portId, state), "port-auto-neg {portId} {state}"));
        list.add(in(OltAction.View.ONU, "onu.port.flowcontrol", CH_ONU_PORT, "ONU 端口流控", null,
                List.of(ponId, onuId, portId, state), "port-flow-control {portId} {state}"));

        // 7.5.6 ONU 端口 VLAN
        OltActionParam vlanId = OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null);
        OltActionParam priority = OltActionParam.number("priority", "优先级", 0, 7, "0-7，值越大优先级越高");
        OltActionParam vlanList = OltActionParam.ports("vlanList", "VLAN 列表", "如 100-200,300");
        list.add(in(OltAction.View.ONU, "onu.vlan.transparent", CH_ONU_PORT, "端口 VLAN 模式：透传",
                "对上下行报文不做任何 VLAN 处理", List.of(ponId, onuId, portId),
                "port-vlan {portId} mode transparent"));
        list.add(in(OltAction.View.ONU, "onu.vlan.tag", CH_ONU_PORT, "端口 VLAN 模式：标记",
                "上行打上指定 VLAN，下行剥掉", List.of(ponId, onuId, portId, vlanId, priority),
                "port-vlan {portId} mode tag {vlanId} pri {priority}"));
        list.add(in(OltAction.View.ONU, "onu.vlan.trunk", CH_ONU_PORT, "端口 VLAN 模式：Trunk",
                "配好后再用 trunk add 加允许通过的 VLAN", List.of(ponId, onuId, portId, vlanId, priority),
                "port-vlan {portId} mode trunk {vlanId} pri {priority}"));
        list.add(in(OltAction.View.ONU, "onu.vlan.trunk.add", CH_ONU_PORT, "Trunk 增加 VLAN", null,
                List.of(ponId, onuId, portId, vlanList), "port-vlan {portId} trunk add {vlanList}"));
        list.add(in(OltAction.View.ONU, "onu.vlan.trunk.del", CH_ONU_PORT, "Trunk 删除 VLAN", null,
                List.of(ponId, onuId, portId, vlanList), "port-vlan {portId} trunk delete {vlanList}"));
        list.add(in(OltAction.View.ONU, "onu.vlan.translation.mode", CH_ONU_PORT, "端口 VLAN 模式：转换",
                "配好后再用 translation add 加转换条目", List.of(ponId, onuId, portId, vlanId, priority),
                "port-vlan {portId} mode translation {vlanId} pri {priority}"));
        list.add(in(OltAction.View.ONU, "onu.vlan.translation.add", CH_ONU_PORT, "增加 VLAN 转换条目", null,
                List.of(ponId, onuId, portId,
                        OltActionParam.number("oldVlan", "原 VLAN", 1, 4094, null),
                        OltActionParam.number("newVlan", "新 VLAN", 1, 4094, null), priority),
                "port-vlan {portId} translation add oldvlan {oldVlan} newvlan {newVlan} pri {priority}"));
        list.add(in(OltAction.View.ONU, "onu.vlan.translation.del", CH_ONU_PORT, "删除单个 VLAN 转换条目", null,
                List.of(ponId, onuId, portId, OltActionParam.number("oldVlan", "原 VLAN", 1, 4094, null)),
                "port-vlan {portId} translation delete oldvlan {oldVlan}"));
        list.add(in(OltAction.View.ONU, "onu.vlan.translation.clean", CH_ONU_PORT, "批量删除 VLAN 转换条目",
                null, List.of(ponId, onuId, portId, vlanList),
                "port-vlan {portId} translation no_entry {vlanList}"));
        return list;
    }

    /** —— 第 8 章 MAC 地址管理（手册 p77-84）：全在全局配置视图 —— */
    private static List<OltAction> mac() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam mac = OltActionParam.text("mac", "MAC 地址", "格式 AA:BB:CC:DD:EE:FF");
        OltActionParam vlanId = OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null);
        OltActionParam portType = OltActionParam.portType();
        OltActionParam portList = OltActionParam.ports("ports", "端口列表", "如 1,3,4-8");

        list.add(query("mac.age.show", CH_MAC, "查看 MAC 地址表老化时间", null, "show mac address-table age"));
        list.add(config("mac.age", CH_MAC, "配置 MAC 地址表老化时间",
                "1-65535 秒，默认 300", List.of(OltActionParam.number("times", "老化时间(秒)", 1, 65535, "如 300")),
                "mac address-table age {times}"));
        list.add(config("mac.age.noage", CH_MAC, "配置 MAC 地址表不老化", null, List.of(),
                "mac address-table age no-age"));
        list.add(query("mac.show.all", CH_MAC, "查看所有 MAC 地址", null, "show mac-address all"));
        list.add(query("mac.show.dynamic", CH_MAC, "查看动态学习的 MAC", null, "show mac-address dynamic"));
        list.add(query("mac.show.static", CH_MAC, "查看静态 MAC", null, "show mac-address static"));
        list.add(query("mac.show.blackhole", CH_MAC, "查看黑洞 MAC", null, "show mac-address black-hole"));
        list.add(queryWith("mac.show.mac", CH_MAC, "查看指定 MAC", null, List.of(mac),
                "show mac-address mac {mac}"));
        list.add(queryWith("mac.show.port", CH_MAC, "查看端口下的 MAC", null, List.of(portType, portList),
                "show mac-address port {portType} {ports}"));
        list.add(queryWith("mac.show.vlan", CH_MAC, "查看 VLAN 下的 MAC", null, List.of(vlanId),
                "show mac-address vlan {vlanId}"));
        list.add(config("mac.blackhole.add", CH_MAC, "添加黑洞 MAC",
                "该 MAC 的报文直接丢弃", List.of(mac, vlanId),
                "mac address-table black-hole {mac} vlan {vlanId}"));
        list.add(config("mac.static.add", CH_MAC, "添加静态 MAC", null,
                List.of(mac, vlanId, portType, portList),
                "mac address-table static {mac} vlan {vlanId} port {portType} {ports}"));

        OltActionParam flushType = OltActionParam.choice("type", "MAC 类型",
                List.of("all", "dynamic", "static"), "all", null);
        list.add(config("mac.flush.all", CH_MAC, "清除所有 MAC 地址", null, List.of(),
                "mac address-table flush all").dangerous());
        list.add(config("mac.flush.dynamic", CH_MAC, "清除动态学习的 MAC", null, List.of(),
                "mac address-table flush dynamic"));
        list.add(config("mac.flush.static", CH_MAC, "清除静态 MAC", null, List.of(),
                "mac address-table flush static"));
        list.add(config("mac.flush.blackhole", CH_MAC, "清除黑洞 MAC", null, List.of(),
                "mac address-table flush black-hole"));
        list.add(config("mac.flush.group", CH_MAC, "清除聚合组学习的 MAC", "聚合组 ID 1-8",
                List.of(OltActionParam.number("groupId", "聚合组 ID", 1, 8, null)),
                "mac address-table flush link-aggregation group {groupId}"));
        list.add(config("mac.flush.port", CH_MAC, "清除指定端口的 MAC", null,
                List.of(portType, portList, flushType),
                "mac address-table flush port {portType} {ports} {type}"));
        list.add(config("mac.flush.vlan", CH_MAC, "清除指定 VLAN 的 MAC", null,
                List.of(vlanId, OltActionParam.choice("type", "MAC 类型",
                        List.of("all", "black-hole", "dynamic", "static"), "all", null)),
                "mac address-table flush vlan {vlanId} {type}"));

        // 8.2 PON 侧 MAC 地址表
        OltActionParam ponId = OltActionParam.number("ponId", "PON 口号", 1, 16, "如 1");
        OltActionParam onuId = OltActionParam.number("onuId", "ONU ID", 1, 64, null);
        list.add(query("mac.epon.age.show", CH_MAC_EPON, "查看 PON 侧 MAC 老化时间", null,
                "show mac address-table epon age"));
        list.add(config("mac.epon.age", CH_MAC_EPON, "配置 PON 侧 MAC 老化时间", "1-65535 秒",
                List.of(OltActionParam.number("times", "老化时间(秒)", 1, 65535, null)),
                "mac address-table epon age {times}"));
        list.add(config("mac.epon.noage", CH_MAC_EPON, "配置 PON 侧 MAC 不老化", null, List.of(),
                "mac address-table epon no-age"));
        list.add(query("mac.epon.show.all", CH_MAC_EPON, "查看 PON 侧所有 MAC", null,
                "show mac-address epon all"));
        list.add(queryWith("mac.epon.show.pon", CH_MAC_EPON, "查看 PON 口下 MAC", null,
                List.of(ponId), "show mac-address epon {ponId}"));
        list.add(queryWith("mac.epon.show.onu", CH_MAC_EPON, "查看 ONU 下 MAC", null,
                List.of(ponId, onuId), "show mac-address epon onu {ponId} {onuId}"));
        list.add(config("mac.epon.del.blackhole", CH_MAC_EPON, "删除 PON 侧黑洞 MAC", null,
                List.of(mac, vlanId), "no mac address-table black-hole {mac} vlan {vlanId}"));
        list.add(config("mac.epon.del.dynamic", CH_MAC_EPON, "删除 PON 侧动态 MAC", null,
                List.of(mac, vlanId), "no mac address-table dynamic {mac} vlan {vlanId}"));
        list.add(config("mac.epon.del.static", CH_MAC_EPON, "删除 PON 侧静态 MAC", null,
                List.of(mac, vlanId, portType, portList),
                "no mac address-table static {mac} vlan {vlanId} port {portType} {ports}"));
        return list;
    }

    /** —— 第 9 章 协议管理（手册 p85-116）：RSTP / MSTP / IGMP / DHCP —— */
    private static List<OltAction> protocol() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam portType = OltActionParam.portType();
        OltActionParam portNo = OltActionParam.portNo("端口号");
        OltActionParam ports = OltActionParam.ports("ports", "端口列表", "如 1,3,4-8");
        OltActionParam vlanId = OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null);
        OltActionParam state = OltActionParam.choice("state", "状态",
                List.of("enable", "disable"), "enable", null);

        // 9.1 RSTP
        list.add(config("stp.enable", CH_STP, "开启/关闭 RSTP", "全局开关", List.of(state),
                "spanning-tree {state}"));
        list.add(config("stp.priority", CH_STP, "配置桥优先级", "0-61440，步长 4096",
                List.of(OltActionParam.number("priority", "桥优先级", 0, 61440, "如 4096")),
                "spanning-tree priority {priority}"));
        list.add(config("stp.forward-delay", CH_STP, "配置 Forward Delay", "4-30 秒",
                List.of(OltActionParam.number("seconds", "Forward Delay(秒)", 4, 30, null)),
                "spanning-tree timer forward-delay {seconds}"));
        list.add(config("stp.hello", CH_STP, "配置 Hello 时间", "1-10 秒",
                List.of(OltActionParam.number("seconds", "Hello(秒)", 1, 10, null)),
                "spanning-tree timer hello {seconds}"));
        list.add(config("stp.max-age", CH_STP, "配置 max-age", "6-40 秒",
                List.of(OltActionParam.number("seconds", "max-age(秒)", 6, 40, null)),
                "spanning-tree timer max-age {seconds}"));
        list.add(query("stp.show", CH_STP, "查看 RSTP 配置", "含根桥、定时器、各端口状态",
                "show spanning-tree info"));
        list.add(in(OltAction.View.PORT, "stp.cost", CH_STP, "配置端口路径开销", "0-200000000",
                List.of(portType, portNo, OltActionParam.number("cost", "路径开销", 0, 200000000, "如 20000")),
                "spanning-tree cost {cost}"));
        list.add(in(OltAction.View.PORT, "stp.edged", CH_STP, "配置端口为边界端口", null,
                List.of(portType, portNo, state), "spanning-tree edged-port {state}"));
        list.add(in(OltAction.View.PORT, "stp.p2p", CH_STP, "配置端口点到点模式", null,
                List.of(portType, portNo, OltActionParam.choice("mode", "模式",
                        List.of("auto", "force-true", "force-false"), "auto", null)),
                "spanning-tree point-to-point {mode}"));
        list.add(in(OltAction.View.PORT, "stp.port-priority", CH_STP, "配置端口优先级", "0-240",
                List.of(portType, portNo,
                        OltActionParam.number("priority", "端口优先级", 0, 240, "如 144")),
                "spanning-tree port-priority {priority}"));

        // 9.2 MSTP
        OltActionParam instance = OltActionParam.number("instance", "实例 ID", 0, 15, null);
        list.add(config("mstp.enable", CH_MSTP, "开启/关闭 MSTP", null, List.of(state),
                "spanning-tree mstp {state}"));
        list.add(config("mstp.region", CH_MSTP, "配置 MST 域名", "最长 31 个字符",
                List.of(OltActionParam.text("name", "域名", "如 test")),
                "spanning-tree mstp region_name {name}"));
        list.add(config("mstp.region.reset", CH_MSTP, "恢复缺省 MST 域名", "缺省为设备 MAC", List.of(),
                "no spanning-tree mstp region_name"));
        list.add(config("mstp.revision", CH_MSTP, "配置 MSTP 修订级别", "0-65535",
                List.of(OltActionParam.number("revision", "修订级别", 0, 65535, "如 10")),
                "spanning-tree mstp revision {revision}"));
        list.add(config("mstp.revision.reset", CH_MSTP, "恢复缺省修订级别", "缺省 0", List.of(),
                "no spanning-tree mstp revision"));
        list.add(config("mstp.hello", CH_MSTP, "配置 MSTP Hello 时间", "1-10 秒",
                List.of(OltActionParam.number("seconds", "Hello(秒)", 1, 10, null)),
                "spanning-tree mstp hello-time {seconds}"));
        list.add(config("mstp.forward-delay", CH_MSTP, "配置 MSTP Forward Delay", "4-30 秒",
                List.of(OltActionParam.number("seconds", "Forward Delay(秒)", 4, 30, null)),
                "spanning-tree mstp forward-delay {seconds}"));
        list.add(config("mstp.max-age", CH_MSTP, "配置 MSTP max-age", "6-40 秒",
                List.of(OltActionParam.number("seconds", "max-age(秒)", 6, 40, null)),
                "spanning-tree mstp max-age {seconds}"));
        list.add(config("mstp.max-hop", CH_MSTP, "配置 MSTP max-hop", "1-40",
                List.of(OltActionParam.number("hop", "max-hop", 1, 40, "如 20")),
                "spanning-tree mstp max-hop {hop}"));
        list.add(config("mstp.instance.vlan", CH_MSTP, "配置实例与 VLAN 映射", null,
                List.of(instance, OltActionParam.ports("vlanList", "VLAN 列表", "如 100-200,300")),
                "spanning-tree mstp instance {instance} vlan {vlanList}"));
        list.add(config("mstp.instance.vlan.del", CH_MSTP, "删除实例与 VLAN 映射", null,
                List.of(instance, OltActionParam.ports("vlanList", "VLAN 列表", null)),
                "no spanning-tree mstp instance {instance} vlan {vlanList}"));
        list.add(config("mstp.instance.priority", CH_MSTP, "配置实例优先级", "0-15，每级 4096",
                List.of(instance, OltActionParam.number("priority", "实例优先级", 0, 15, "如 1")),
                "spanning-tree mstp instance {instance} priority {priority}"));
        list.add(config("mstp.instance.priority.reset", CH_MSTP, "恢复实例默认优先级", null,
                List.of(instance), "no spanning-tree mstp instance {instance} priority"));
        list.add(config("mstp.port.bpduguard", CH_MSTP, "配置端口 BPDU 保护",
                "收到 BPDU 就关端口，边缘端口才建议开",
                List.of(portType, portNo, state), "spanning-tree mstp {portType} {portNo} bpduguard {state}"));
        list.add(config("mstp.port.loopback", CH_MSTP, "配置端口环路保护", null,
                List.of(portType, portNo, state),
                "spanning-tree mstp {portType} {portNo} loopback-detect {state}"));
        list.add(config("mstp.port.mcheck", CH_MSTP, "触发协议迁移（mcheck）", null,
                List.of(portType, portNo), "spanning-tree mstp {portType} {portNo} migration-check"));
        list.add(config("mstp.port.p2p", CH_MSTP, "配置端口 P2P", null,
                List.of(portType, portNo, OltActionParam.choice("mode", "P2P 模式",
                        List.of("auto", "force-true", "force-false"), "auto", null)),
                "spanning-tree mstp {portType} {portNo} point-to-point {mode}"));
        list.add(config("mstp.port.edge", CH_MSTP, "配置端口为边缘端口", null,
                List.of(portType, portNo, OltActionParam.choice("mode", "边缘模式",
                        List.of("auto", "force-true", "force-false"), "auto", null)),
                "spanning-tree mstp {portType} {portNo} edge {mode}"));
        list.add(config("mstp.port.cost", CH_MSTP, "配置端口实例开销", null,
                List.of(portType, portNo, instance,
                        OltActionParam.number("cost", "开销", 0, 200000000, "如 20000")),
                "spanning-tree mstp {portType} {portNo} {instance} cost {cost}"));
        list.add(config("mstp.port.priority", CH_MSTP, "配置端口实例优先级", "0-240，步长 16",
                List.of(portType, portNo, instance,
                        OltActionParam.number("priority", "端口优先级", 0, 240, "如 16")),
                "spanning-tree mstp {portType} {portNo} {instance} port-priority {priority}"));

        // 9.3 IGMP 全局
        list.add(config("igmp.mode", CH_IGMP, "配置组播模式", "缺省 disable",
                List.of(OltActionParam.choice("mode", "组播模式",
                        List.of("disable", "snooping", "proxy"), "snooping", null)),
                "igmp mode {mode}"));
        list.add(config("igmp.fast-leave", CH_IGMP, "配置快速离开", null, List.of(state),
                "igmp fast-leave {state}"));
        list.add(config("igmp.policy", CH_IGMP, "配置组播上行协议报文策略",
                "discard 只放行节目库内的节目，pass 允许未知组播通过",
                List.of(OltActionParam.choice("policy", "策略",
                        List.of("discard", "pass"), "pass", null)),
                "igmp protocol-packets policy {policy}"));
        list.add(config("igmp.aging", CH_IGMP, "配置组播成员老化时间",
                "仅 snooping 模式可配，1-3000 秒，缺省 260",
                List.of(OltActionParam.number("seconds", "老化时间(秒)", 1, 3000, null)),
                "igmp snooping group-aging-time {seconds}"));
        list.add(config("igmp.proxy.gen-interval", CH_IGMP, "proxy：通用组查询间隔", "2-3000 秒，缺省 125",
                List.of(OltActionParam.number("seconds", "查询间隔(秒)", 2, 3000, null)),
                "igmp proxy gen-query-interval {seconds}"));
        list.add(config("igmp.proxy.gen-response", CH_IGMP, "proxy：通用组最大响应时间", "1-25 秒，缺省 10",
                List.of(OltActionParam.number("seconds", "响应时间(秒)", 1, 25, null)),
                "igmp proxy gen-response-time {seconds}"));
        list.add(config("igmp.proxy.robustness", CH_IGMP, "proxy：系统健壮性系数", "1-10，缺省 2",
                List.of(OltActionParam.number("value", "健壮性系数", 1, 10, null)),
                "igmp proxy robustness {value}"));
        list.add(config("igmp.proxy.sp-interval", CH_IGMP, "proxy：特定组查询间隔",
                "100-10000 毫秒，缺省 1000",
                List.of(OltActionParam.number("millis", "查询间隔(毫秒)", 100, 10000, null)),
                "igmp proxy sp-query-interval {millis}"));
        list.add(config("igmp.proxy.sp-number", CH_IGMP, "proxy：特定组查询次数", "1-10，缺省 2",
                List.of(OltActionParam.number("times", "查询次数", 1, 10, null)),
                "igmp proxy sp-query-number {times}"));
        list.add(config("igmp.proxy.sp-response", CH_IGMP, "proxy：特定组最大响应时间",
                "100-10000 毫秒，缺省 800",
                List.of(OltActionParam.number("millis", "响应时间(毫秒)", 100, 10000, null)),
                "igmp proxy sp-response-time {millis}"));
        list.add(config("igmp.proxy.src-ip", CH_IGMP, "proxy：查询报文源 IP", "缺省 192.168.100.1",
                List.of(OltActionParam.text("ip", "源 IP", "如 192.168.1.1")),
                "igmp proxy src-ip {ip}"));
        list.add(config("igmp.proxy.src-mac", CH_IGMP, "proxy：查询报文源 MAC", "缺省设备带内地址",
                List.of(OltActionParam.text("mac", "源 MAC", "如 00:01:02:03:04:05")),
                "igmp proxy src-mac {mac}"));
        list.add(query("igmp.show", CH_IGMP, "查看组播配置", null, "show igmp config"));

        // 9.3.3 组播 VLAN（multicast-vlan 视图）
        list.add(in(OltAction.View.MCAST, "igmp.mvlan.unknown", CH_MCAST_VLAN, "未知组播流抑制策略",
                "缺省 transparent", List.of(vlanId,
                        OltActionParam.choice("policy", "策略",
                                List.of("discard", "transparent"), "transparent", null)),
                "igmp multicast-unknown policy {policy}"));
        list.add(in(OltAction.View.MCAST, "igmp.mvlan.program", CH_MCAST_VLAN, "添加单个组播节目", null,
                List.of(vlanId, OltActionParam.text("ip", "组播 IP", "如 224.1.1.1"),
                        OltActionParam.optionalText("desc", "描述", "可选")),
                "igmp program ip {ip}[ {desc}]"));
        list.add(in(OltAction.View.MCAST, "igmp.mvlan.program.batch", CH_MCAST_VLAN, "批量添加组播节目",
                null, List.of(vlanId, OltActionParam.text("startIp", "起始 IP", "如 224.1.1.1"),
                        OltActionParam.text("endIp", "终止 IP", "如 234.1.1.1"),
                        OltActionParam.optionalText("desc", "描述", "可选")),
                "igmp program ip batch {startIp} {endIp}[ {desc}]"));
        list.add(in(OltAction.View.MCAST, "igmp.mvlan.router-port", CH_MCAST_VLAN, "配置组播路由端口",
                "只能配上联口", List.of(vlanId, portType, ports),
                "igmp router-port {portType} {ports}"));
        list.add(queryWith("igmp.mvlan.show", CH_MCAST_VLAN, "查看组播 VLAN 配置",
                "填 all 或具体 VLAN ID", List.of(OltActionParam.text("target", "查询对象", "all 或 100")),
                "show multicast-vlan {target}"));

        // 9.3.4 组播表项
        list.add(config("igmp.entry.add", CH_IGMP_ENTRY, "添加静态组播表项", null,
                List.of(OltActionParam.text("ip", "组播 IP", "如 224.1.1.1"), portType, ports, vlanId),
                "igmp static ip {ip} {portType} {ports} vlan {vlanId}"));
        list.add(config("igmp.entry.del", CH_IGMP_ENTRY, "删除静态组播表项", null,
                List.of(OltActionParam.text("ip", "组播 IP", null), portType, ports, vlanId),
                "no igmp static ip {ip} {portType} {ports} vlan {vlanId}"));
        list.add(queryWith("igmp.entry.show", CH_IGMP_ENTRY, "查询组播表项",
                "填 all / dynamic / static / multi-ip 224.1.1.1 / vlan 100 之一",
                List.of(OltActionParam.text("target", "查询对象", "all")), "show igmp entry {target}"));

        // 9.3.5 ONU 组播（ONU 视图）
        OltActionParam ponId = OltActionParam.number("ponId", "PON 口号", 1, 16, null);
        OltActionParam onuId = OltActionParam.number("onuId", "ONU ID", 1, 64, null);
        OltActionParam portId = OltActionParam.number("portId", "ONU 端口号", 1, 26, null);
        list.add(in(OltAction.View.ONU, "onu.mcast.mode", CH_ONU_MCAST, "配置 ONU 组播模式",
                "缺省 snooping，可选 ctc", List.of(ponId, onuId,
                        OltActionParam.choice("mode", "组播模式", List.of("snooping", "ctc"), "snooping", null)),
                "multicast mode {mode}"));
        list.add(queryIn(OltAction.View.ONU, "onu.mcast.mode.show", CH_ONU_MCAST, "查看 ONU 组播模式", null,
                List.of(ponId, onuId), "show multicast mode"));
        list.add(in(OltAction.View.ONU, "onu.mcast.fastleave", CH_ONU_MCAST, "配置 ONU 快速离开", null,
                List.of(ponId, onuId, state), "multicast fast-leave-admin state {state}"));
        list.add(queryIn(OltAction.View.ONU, "onu.mcast.fastleave.show", CH_ONU_MCAST,
                "查看 ONU 快速离开状态", null, List.of(ponId, onuId), "show multicast fast-leave-state"));
        list.add(in(OltAction.View.ONU, "onu.mcast.groupmax", CH_ONU_MCAST, "配置 ONU 端口最大组播组数",
                "仅 igmp-snooping 模式可配，0-255，缺省 64", List.of(ponId, onuId, portId,
                        OltActionParam.number("max", "最大组播组数", 0, 255, "如 32")),
                "multicast {portId} group_num_max {max}"));
        list.add(queryIn(OltAction.View.ONU, "onu.mcast.groupmax.show", CH_ONU_MCAST,
                "查看 ONU 端口最大组播组数", null, List.of(ponId, onuId, portId),
                "show multicast {portId} group-num-max"));
        list.add(in(OltAction.View.ONU, "onu.mcast.vlan.add", CH_ONU_MCAST, "配置 ONU 端口组播 VLAN",
                "每个端口最多 8 个组播 VLAN", List.of(ponId, onuId, portId, vlanId),
                "multicast {portId} multicast-vlan {vlanId}"));
        list.add(in(OltAction.View.ONU, "onu.mcast.vlan.del", CH_ONU_MCAST, "删除 ONU 端口组播 VLAN", null,
                List.of(ponId, onuId, portId, vlanId), "no multicast {portId} multicast-vlan {vlanId}"));
        list.add(in(OltAction.View.ONU, "onu.mcast.vlan.clear", CH_ONU_MCAST, "清除 ONU 端口全部组播 VLAN",
                null, List.of(ponId, onuId, portId), "multicast {portId} multicast-vlan clear"));
        list.add(queryIn(OltAction.View.ONU, "onu.mcast.vlan.show", CH_ONU_MCAST,
                "查看 ONU 端口组播 VLAN", null, List.of(ponId, onuId, portId),
                "show multicast {portId} multicast-vlan"));
        list.add(in(OltAction.View.ONU, "onu.mcast.tagmode", CH_ONU_MCAST, "配置 ONU 端口组播 VLAN 模式",
                "tag 保留标签，untag 剥掉标签", List.of(ponId, onuId, portId,
                        OltActionParam.choice("mode", "模式", List.of("tag", "untag"), "untag", null)),
                "multicast {portId} tagmode {mode}"));
        list.add(in(OltAction.View.ONU, "onu.mcast.tagmode.translate", CH_ONU_MCAST,
                "配置 ONU 端口组播 VLAN 转换", "最多 8 组转换", List.of(ponId, onuId, portId,
                        OltActionParam.number("svlan", "转换前 VLAN", 1, 4094, null),
                        OltActionParam.number("cvlan", "转换后 VLAN", 1, 4094, null)),
                "multicast {portId} tagmode translate {svlan} {cvlan}"));
        list.add(queryIn(OltAction.View.ONU, "onu.mcast.tagmode.show", CH_ONU_MCAST,
                "查看 ONU 端口组播 VLAN 模式", null, List.of(ponId, onuId, portId),
                "show multicast {portId} tagmode"));

        // 9.4 DHCP
        list.add(config("dhcp.enable", CH_DHCP, "开启/关闭 DHCP 功能", "缺省关闭", List.of(state),
                "ip dhcp {state}"));
        list.add(query("dhcp.show", CH_DHCP, "查看 DHCP 全局配置", "含 Snooping/Relay/Option82/Server 状态",
                "show ip dhcp config"));
        list.add(config("dhcp.snooping", CH_DHCP, "开启/关闭 DHCP Snooping",
                "要先开全局 DHCP", List.of(state), "ip dhcp snooping {state}"));
        list.add(config("dhcp.snooping.trust", CH_DHCP, "配置 Snooping 信任端口", "仅上联口可配",
                List.of(portType, ports), "ip dhcp snooping trust {portType} {ports}"));
        list.add(config("dhcp.snooping.trust.del", CH_DHCP, "取消 Snooping 信任端口", null,
                List.of(portType, ports), "no ip dhcp snooping trust {portType} {ports}"));
        list.add(config("dhcp.snooping.chaddr", CH_DHCP, "配置客户端地址检查",
                "校验报文源 MAC 与 CHADDR 是否一致", List.of(state),
                "ip dhcp snooping chaddr-check {state}"));
        list.add(config("dhcp.snooping.wait", CH_DHCP, "配置 Snooping 等待响应时间",
                "3-3600 秒，缺省 60",
                List.of(OltActionParam.number("seconds", "等待时间(秒)", 3, 3600, null)),
                "ip dhcp snooping table response-wait-time {seconds}"));
        list.add(query("dhcp.entry", CH_DHCP, "查看 DHCP 监听表", null, "show ip dhcp entry"));
        list.add(config("dhcp.relay", CH_DHCP, "开启/关闭 DHCP Relay",
                "要先开全局 DHCP 和路由功能", List.of(state), "ip dhcp relay {state}"));
        list.add(config("dhcp.relay.policy", CH_DHCP, "配置 DHCP Relay 策略",
                "切换策略会清掉另一种策略的服务器表",
                List.of(OltActionParam.choice("policy", "策略",
                        List.of("standard", "option60"), "standard", null)),
                "ip dhcp relay policy {policy}"));
        list.add(config("dhcp.relay.server.vlanif", CH_DHCP, "配置 Relay 服务器（Standard 策略）", null,
                List.of(OltActionParam.text("ip", "服务器 IP", "如 192.168.1.1"), vlanId),
                "ip dhcp relay server {ip} vlanif {vlanId}"));
        list.add(config("dhcp.relay.server.option60", CH_DHCP, "配置 Relay 服务器（Option60 策略）", null,
                List.of(OltActionParam.text("ip", "服务器 IP", null),
                        OltActionParam.text("word", "Option60 字段", "如 test")),
                "ip dhcp relay server {ip} option60 {word}"));
        list.add(config("dhcp.option82", CH_DHCP, "开启/关闭 DHCP Option82", "缺省关闭", List.of(state),
                "ip dhcp option82 {state}"));
        list.add(config("dhcp.option82.policy", CH_DHCP, "配置 Option82 策略",
                "drop 丢弃 / replace 替换 / reserve 保留",
                List.of(OltActionParam.choice("policy", "策略",
                        List.of("drop", "replace", "reserve"), "reserve", null)),
                "ip dhcp option82 policy {policy}"));
        list.add(config("dhcp.server", CH_DHCP, "开启/关闭 DHCP Server",
                "要先关掉 Snooping / Relay / Option82", List.of(state), "ip dhcp server {state}"));
        list.add(config("dhcp.server.vlanif", CH_DHCP, "配置 DHCP Server 接口",
                "带内 VLANIF 必须已存在", List.of(vlanId), "ip dhcp server interface vlanif {vlanId}"));
        list.add(config("dhcp.server.lease", CH_DHCP, "配置 DHCP Server 租约时长",
                "60-864000 秒，缺省 86400",
                List.of(OltActionParam.number("seconds", "租约(秒)", 60, 864000, null)),
                "ip dhcp server lease-time {seconds}"));
        list.add(config("dhcp.server.pool", CH_DHCP, "配置 DHCP Server 地址池", null,
                List.of(OltActionParam.text("startIp", "起始 IP", "如 192.168.99.100"),
                        OltActionParam.text("endIp", "终止 IP", "如 192.168.99.200")),
                "ip dhcp server ip-pool {startIp} {endIp}"));
        list.add(config("dhcp.server.gateway", CH_DHCP, "配置 DHCP Server 网关", null,
                List.of(OltActionParam.text("gateway", "网关", null)),
                "ip dhcp server gateway {gateway}"));
        list.add(config("dhcp.server.netmask", CH_DHCP, "配置 DHCP Server 掩码", null,
                List.of(OltActionParam.text("netmask", "掩码", "如 255.255.255.0")),
                "ip dhcp server netmask {netmask}"));
        list.add(config("dhcp.server.dns", CH_DHCP, "配置 DHCP Server DNS",
                "首选必填，备用可选", List.of(OltActionParam.text("master", "首选 DNS", null),
                        OltActionParam.optionalText("backup", "备用 DNS", "可选")),
                "ip dhcp server dns {master}[ {backup}]"));
        list.add(config("dhcpv6.enable", CH_DHCP, "开启/关闭 DHCPv6", "缺省关闭", List.of(state),
                "dhcpv6 {state}"));
        list.add(config("dhcpv6.option18", CH_DHCP, "开启/关闭 DHCPv6 Option18", "要先开 DHCPv6",
                List.of(state), "dhcpv6 option18 {state}"));
        list.add(config("dhcpv6.option37", CH_DHCP, "开启/关闭 DHCPv6 Option37", "要先开 DHCPv6",
                List.of(state), "dhcpv6 option37 {state}"));
        list.add(query("dhcpv6.show", CH_DHCP, "查看 DHCPv6 配置", null, "show ip dhcpv6 config"));
        return list;
    }

    /** —— 第 10 章 ACL/QOS（手册 p117-125） —— */
    private static List<OltAction> aclQos() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam portType = OltActionParam.portType();
        OltActionParam portNo = OltActionParam.portNo("端口号");
        OltActionParam aclId = OltActionParam.number("aclId", "ACL ID", 2000, 5999,
                "基本 2000-2999 / 高级 3000-4999 / 链路 5000-5999");
        OltActionParam ruleId = OltActionParam.number("ruleId", "规则 ID", 1, 16, "1-16，值越大优先级越高");
        OltActionParam action = OltActionParam.choice("action", "动作", List.of("permit", "deny"), "permit", null);
        OltActionParam timeRange = OltActionParam.optionalText("timeRange", "生效时间段", "可选，先建 time-range");

        // 10.1 时间段
        list.add(config("timerange.relative", CH_TIMERANGE, "增加相对时间段（周期）", "时间格式 HH:MM",
                List.of(OltActionParam.text("name", "时间段名", "如 test"),
                        OltActionParam.text("start", "起始时间", "如 9:00"),
                        OltActionParam.text("end", "结束时间", "如 18:00"),
                        OltActionParam.choice("day", "生效日", List.of("daily", "mon", "tue", "wed", "thu",
                                "fri", "sat", "sun", "weekend", "working-day"), "daily", null)),
                "time-range {name} {start} to {end} {day}"));
        list.add(config("timerange.relative.del", CH_TIMERANGE, "删除相对时间段", null,
                List.of(OltActionParam.text("name", "时间段名", null),
                        OltActionParam.text("start", "起始时间", null)),
                "no time-range {name} relative {start}"));
        list.add(config("timerange.absolute", CH_TIMERANGE, "增加绝对时间段",
                "日期格式 YYYY/MM/DD，时间格式 HH:MM",
                List.of(OltActionParam.text("name", "时间段名", null),
                        OltActionParam.text("start", "起始时间", "如 9:00"),
                        OltActionParam.text("startDay", "起始日期", "如 2019/4/29"),
                        OltActionParam.text("end", "结束时间", "如 18:00"),
                        OltActionParam.text("endDay", "结束日期", "如 2019/4/29")),
                "time-range {name} from {start} {startDay} to {end} {endDay}"));
        list.add(config("timerange.absolute.del", CH_TIMERANGE, "删除绝对时间段", null,
                List.of(OltActionParam.text("name", "时间段名", null),
                        OltActionParam.text("start", "起始时间", null),
                        OltActionParam.text("startDay", "起始日期", null)),
                "no time-range {name} absolute {start} {startDay}"));
        list.add(config("timerange.clear", CH_TIMERANGE, "删除所有时间段", null, List.of(),
                "no time-range all").dangerous());
        list.add(queryWith("timerange.show", CH_TIMERANGE, "查看时间段", "填 all 或时间段名",
                List.of(OltActionParam.text("target", "查询对象", "all")), "show time-range {target}"));

        // 10.2 ACL
        list.add(queryWith("acl.show", CH_ACL, "查看 ACL", "填 all 或 ACL ID",
                List.of(OltActionParam.text("target", "查询对象", "all")), "show acl {target}"));
        list.add(queryIn(OltAction.View.ACL, "acl.rule.show", CH_ACL, "查看 ACL 下的规则", null,
                List.of(aclId), "show rule"));
        list.add(in(OltAction.View.ACL, "acl.basic.rule.any", CH_ACL, "基本 ACL 规则（任意源）", null,
                List.of(aclId, ruleId, action, timeRange),
                "rule {ruleId} {action} source any[ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "acl.basic.rule.src", CH_ACL, "基本 ACL 规则（指定源 IP）", null,
                List.of(aclId, ruleId, action,
                        OltActionParam.text("ip", "源 IP", "如 192.168.0.1"),
                        OltActionParam.text("wildcard", "反掩码", "如 0.0.0.255"), timeRange),
                "rule {ruleId} {action} source {ip} {wildcard}[ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "acl.adv.rule.port", CH_ACL, "高级 ACL 规则（协议 + 端口）", null,
                List.of(aclId, ruleId, action,
                        OltActionParam.choice("protocol", "协议",
                                List.of("icmp", "igmp", "ip", "ipinip", "tcp", "udp"), "tcp", null),
                        OltActionParam.number("destPort", "目的端口", 0, 65535, null),
                        OltActionParam.optionalNumber("srcPort", "源端口", 0, 65535, "可选"), timeRange),
                "rule {ruleId} {action} {protocol} dest-port {destPort}[ src-port {srcPort}]"
                        + "[ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "acl.adv.rule.dest", CH_ACL, "高级 ACL 规则（目的 IP）", null,
                List.of(aclId, ruleId, action,
                        OltActionParam.choice("protocol", "协议",
                                List.of("icmp", "igmp", "ip", "ipinip", "tcp", "udp"), "ip", null),
                        OltActionParam.text("destIp", "目的 IP", "如 192.168.0.1"),
                        OltActionParam.text("destWildcard", "反掩码", "如 0.0.0.255"),
                        OltActionParam.optionalNumber("destPort", "目的端口", 0, 65535, "可选"), timeRange),
                "rule {ruleId} {action} {protocol} dest-ip {destIp} {destWildcard}"
                        + "[ dest-port {destPort}][ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "acl.link.rule.vlan", CH_ACL, "链路 ACL 规则（VLAN）", null,
                List.of(aclId, ruleId, action, OltActionParam.number("vlanId", "VLAN ID", 0, 4094, null),
                        timeRange),
                "rule {ruleId} {action} vlan {vlanId}[ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "acl.link.rule.mac", CH_ACL, "链路 ACL 规则（MAC）", null,
                List.of(aclId, ruleId, action,
                        OltActionParam.optionalNumber("cos", "VLAN 优先级", 0, 7, "可选"),
                        OltActionParam.text("destMac", "目的 MAC", "如 AABB-CCDD-EEFF"),
                        OltActionParam.text("destWildcard", "目的 MAC 反掩码", "如 FFFF-FFFF-FFFF"),
                        OltActionParam.optionalText("srcMac", "源 MAC", "可选"),
                        OltActionParam.optionalText("srcWildcard", "源 MAC 反掩码", "可选"), timeRange),
                "rule {ruleId} {action}[ cos {cos}] destination {destMac} {destWildcard}"
                        + "[ source {srcMac} {srcWildcard}][ time-range {timeRange}]"));
        list.add(in(OltAction.View.ACL, "acl.rule.up", CH_ACL, "规则 ID 上移", null,
                List.of(aclId, ruleId), "rule {ruleId} up"));
        list.add(in(OltAction.View.ACL, "acl.rule.down", CH_ACL, "规则 ID 下移", null,
                List.of(aclId, ruleId), "rule {ruleId} down"));
        list.add(in(OltAction.View.ACL, "acl.rule.move", CH_ACL, "规则 ID 迁移", null,
                List.of(aclId, ruleId, OltActionParam.number("newRuleId", "目标规则 ID", 1, 16, null)),
                "rule {ruleId} move to {newRuleId}"));

        // 10.3 包过滤
        list.add(config("filter.add", CH_FILTER, "增加包过滤", "把 ACL 规则应用到端口",
                List.of(aclId, ruleId, portType, portNo),
                "packet-filter inbound {aclId} rule-id {ruleId} port {portType} {portNo}"));
        list.add(config("filter.del", CH_FILTER, "删除包过滤", null,
                List.of(aclId, ruleId, portType, portNo),
                "no packet-filter inbound {aclId} rule-id {ruleId} port {portType} {portNo}"));
        list.add(query("filter.show", CH_FILTER, "查看所有包过滤条目", null, "show packet-filter all"));
        list.add(queryWith("filter.show.port", CH_FILTER, "查看端口包过滤条目", null,
                List.of(portType, portNo), "show packet-filter port {portType} {portNo}"));

        // 10.4 QOS
        list.add(config("qos.cosq", CH_QOS, "配置优先级与队列映射", "每个 cos 对应一个队列（0-7）",
                List.of(OltActionParam.number("q0", "cos0 → 队列", 0, 7, null),
                        OltActionParam.number("q1", "cos1 → 队列", 0, 7, null),
                        OltActionParam.number("q2", "cos2 → 队列", 0, 7, null),
                        OltActionParam.number("q3", "cos3 → 队列", 0, 7, null),
                        OltActionParam.number("q4", "cos4 → 队列", 0, 7, null),
                        OltActionParam.number("q5", "cos5 → 队列", 0, 7, null),
                        OltActionParam.number("q6", "cos6 → 队列", 0, 7, null),
                        OltActionParam.number("q7", "cos7 → 队列", 0, 7, null)),
                "qos cosq-map cos0 {q0} cos1 {q1} cos2 {q2} cos3 {q3} cos4 {q4} cos5 {q5} cos6 {q6} cos7 {q7}"));
        list.add(config("qos.sp", CH_QOS, "队列调度方式：严格优先级", "缺省方式", List.of(),
                "qos queue-scheduler strict-priority"));
        list.add(config("qos.wrr", CH_QOS, "队列调度方式：WRR 加权轮询",
                "8 个权重相加必须等于 100 或 0",
                List.of(OltActionParam.number("w0", "队列 0 权重", 0, 100, null),
                        OltActionParam.number("w1", "队列 1 权重", 0, 100, null),
                        OltActionParam.number("w2", "队列 2 权重", 0, 100, null),
                        OltActionParam.number("w3", "队列 3 权重", 0, 100, null),
                        OltActionParam.number("w4", "队列 4 权重", 0, 100, null),
                        OltActionParam.number("w5", "队列 5 权重", 0, 100, null),
                        OltActionParam.number("w6", "队列 6 权重", 0, 100, null),
                        OltActionParam.number("w7", "队列 7 权重", 0, 100, null)),
                "qos queue-scheduler wrr {w0} {w1} {w2} {w3} {w4} {w5} {w6} {w7}"));
        list.add(query("qos.show", CH_QOS, "查看队列调度模式", null, "show queue-scheduler"));
        return list;
    }

    /** —— 第 11 章路由 / 第 12 章链路聚合（手册 p126-130） —— */
    private static List<OltAction> routeAgg() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam portType = OltActionParam.portType();
        OltActionParam ports = OltActionParam.ports("ports", "端口列表", "如 1-3");

        list.add(config("route.enable", CH_ROUTE2, "开启/关闭静态路由开关",
                "关闭会清掉所有静态路由和 ARP 表项", List.of(OltActionParam.choice("state", "状态",
                        List.of("enable", "disable"), "enable", null)), "ip route {state}"));
        list.add(config("route.static", CH_ROUTE2, "增加静态路由",
                "掩码可填 255.255.255.0 或前缀长度 24",
                List.of(OltActionParam.text("destIp", "目的地址", "如 192.168.200.12"),
                        OltActionParam.text("mask", "掩码/前缀", "如 255.255.255.0 或 24"),
                        OltActionParam.text("nextHop", "下一跳", "如 192.168.100.1")),
                "ip route-static dest-ip {destIp} {mask} next-hop {nextHop}"));
        list.add(config("route.static.del", CH_ROUTE2, "删除静态路由", null,
                List.of(OltActionParam.text("destIp", "目的地址", null),
                        OltActionParam.text("mask", "掩码/前缀", null),
                        OltActionParam.text("nextHop", "下一跳", null)),
                "no ip route-static dest-ip {destIp} {mask} next-hop {nextHop}"));
        list.add(query("route.show", CH_ROUTE2, "查看静态路由表", null, "show route-table"));
        list.add(query("arp.show", CH_ROUTE2, "查看 ARP 表", null, "show arp-table"));
        list.add(config("arp.flush", CH_ROUTE2, "删除 ARP 表", "清空后要重新学习", List.of(),
                "arp flush all").dangerous());

        OltActionParam groupId = OltActionParam.number("groupId", "聚合组 ID", 1, 8, null);
        list.add(config("lag.add", CH_LAG, "创建链路聚合组 / 增加成员",
                "成员端口属性必须一致；环回检测、镜像、静态 MAC 端口不能加入",
                List.of(groupId, portType, ports),
                "link-aggregation group {groupId} member add {portType} {ports}"));
        list.add(config("lag.del", CH_LAG, "删除链路聚合组成员", "成员全部删掉聚合组即删除",
                List.of(groupId, portType, ports),
                "link-aggregation group {groupId} member delete {portType} {ports}"));
        list.add(config("lag.balance", CH_LAG, "配置负载均衡算法", "缺省基于源 MAC",
                List.of(groupId, OltActionParam.choice("mode", "算法", List.of("dest-ip", "dest-mac",
                        "src-dest-mac", "src-ip", "src-mac"), "src-mac", null)),
                "link-aggregation group {groupId} load-balance {mode}"));
        list.add(query("lag.show", CH_LAG, "查看链路聚合组", null, "show link-aggregation group"));
        return list;
    }

    /** —— 第 2/3 章：系统管理与带内管理口（手册 p11-33） —— */
    private static List<OltAction> system() {
        List<OltAction> list = new ArrayList<>();
        OltActionParam vlanId = OltActionParam.number("vlanId", "VLAN ID", 1, 4094, "管理 VLAN，如 1");

        list.add(config("sys.hostname", CH_HOSTNAME, "配置设备名称", "不能包含空格",
                List.of(OltActionParam.text("name", "设备名称", "如 NEUTRAL-E08")), "hostname {name}"));
        list.add(config("sys.hostname.reset", CH_HOSTNAME, "恢复缺省设备名称", null, List.of(), "no hostname"));

        list.add(query("sys.version", CH_VERSION, "查看系统版本信息", "版本号 / 编译时间", "show version"));
        list.add(query("sys.cpu", CH_CPU, "查看 CPU 利用率", null, "show cpu-usage"));
        list.add(query("sys.memory", CH_MEMORY, "查看内存利用率", null, "show memory statistics"));
        list.add(query("sys.history", CH_HISTORY, "查看历史输入命令", null, "show history"));

        list.add(config("sys.route.default", CH_ROUTE, "配置 IPv4 缺省路由",
                "网关必须在相同网段的带内或带外 IP 地址",
                List.of(OltActionParam.text("gateway", "网关", "如 192.168.100.1")),
                "route default gw {gateway}"));
        list.add(config("sys.route.default6", CH_ROUTE, "配置 IPv6 缺省路由",
                "网关必须在相同网段的带内或带外 IP 地址",
                List.of(OltActionParam.text("gateway", "网关", "如 2021::1")),
                "ipv6 route default gw {gateway}"));
        list.add(config("sys.route.default.del", CH_ROUTE, "删除 IPv4 缺省路由", null, List.of(),
                "no route default"));
        list.add(config("sys.route.default6.del", CH_ROUTE, "删除 IPv6 缺省路由", null, List.of(),
                "no ipv6 route default"));
        list.add(query("sys.route.default.show", CH_ROUTE, "查询缺省路由", null, "show route default"));

        list.add(config("sys.dns", CH_DNS, "配置 IPv4 DNS", "主/备 DNS",
                List.of(OltActionParam.text("primary", "主 DNS", "如 114.114.114.114"),
                        OltActionParam.text("secondary", "备 DNS", "如 8.8.8.8")),
                "dns primary {primary} secondary {secondary}"));
        list.add(config("sys.dns.del", CH_DNS, "删除 IPv4 DNS", null, List.of(), "no dns"));
        list.add(config("sys.dns6", CH_DNS, "配置 IPv6 DNS", "主/备 DNS",
                List.of(OltActionParam.text("primary", "主 DNS", "如 2400:3200::1"),
                        OltActionParam.text("secondary", "备 DNS", "如 2400:3200:baba::1")),
                "ipv6 dns primary {primary} secondary {secondary}"));
        list.add(config("sys.dns6.del", CH_DNS, "删除 IPv6 DNS", null, List.of(), "no ipv6 dns"));
        list.add(query("sys.dns.show", CH_DNS, "查看 DNS", null, "show dns"));

        list.add(config("sys.access.add", CH_ACCESS, "创建系统访问控制",
                "只允许满足条件的终端访问指定服务",
                List.of(OltActionParam.text("ruleName", "规则名", "如 test"),
                        OltActionParam.text("mac", "终端 MAC", "如 38:3a:21:2c:2f:bc"),
                        OltActionParam.choice("protocol", "访问方式", List.of("all", "ssh", "telnet", "web"),
                                "telnet", null),
                        OltActionParam.choice("family", "地址族", List.of("ipv4", "ipv6"), "ipv4", null),
                        OltActionParam.text("host", "IP 地址/前缀", "如 192.168.103.100/24")),
                "access-control add {ruleName} mac {mac} access {protocol} {family} {host}"));
        list.add(query("sys.access.show", CH_ACCESS, "查看系统访问控制", null, "show access-control"));
        list.add(config("sys.access.del", CH_ACCESS, "删除系统访问控制", null,
                List.of(OltActionParam.text("ruleName", "规则名", null)), "no access-control {ruleName}"));

        list.add(queryIn(OltAction.View.VLANIF, "sys.mgmt.show", CH_MGMT, "查看带内管理 IP",
                "回显 Internet Address / netmask 等", List.of(vlanId), "ifconfig"));
        list.add(in(OltAction.View.VLANIF, "sys.mgmt.ip", CH_MGMT, "配置带内管理 IPv4 地址",
                "建议先在设备上建好该 VLAN 的三层接口",
                List.of(vlanId, OltActionParam.text("ipPrefix", "IP/前缀", "如 192.168.105.1/24")),
                "ifconfig {ipPrefix}"));
        list.add(in(OltAction.View.VLANIF, "sys.mgmt.ip6", CH_MGMT, "配置带内管理 IPv6 地址",
                "格式 X:X:X:X/X，如 fe80::1/32",
                List.of(vlanId, OltActionParam.text("ipv6", "IPv6 地址", "如 3000::1/64")),
                "ifconfig ipv6 {ipv6}"));
        list.add(in(OltAction.View.VLANIF, "sys.mgmt.ip6.del", CH_MGMT, "删除带内管理 IPv6 地址", null,
                List.of(vlanId, OltActionParam.text("ipv6", "IPv6 地址", null)), "no ipv6-address {ipv6}"));
        list.add(in(OltAction.View.VLANIF, "sys.mgmt.telnet", CH_MGMT, "开启/关闭带内 telnet 功能", null,
                List.of(vlanId, OltActionParam.choice("state", "状态", List.of("enable", "disable"),
                        "enable", null)), "telnet {state}"));
        list.add(config("sys.mgmt.vlanif.del", CH_MGMT, "删除带内接口", "VLAN 取值 1-4094",
                List.of(vlanId), "no interface vlanif {vlanId}"));

        // 3.6 配置管理
        list.add(config("sys.save", CH_SAVE, "保存系统配置", "把运行配置写入 flash",
                List.of(), "save"));
        list.add(query("sys.running", CH_RUNNING, "查看系统运行配置", "当前生效的配置", "show running-config"));
        list.add(query("sys.saved", CH_RUNNING, "查看系统保存配置", "flash 里的配置，重启不丢",
                "show save-config"));
        list.add(config("sys.erase", CH_FACTORY, "擦除配置文件", "恢复出厂第一步：擦除后还要重启",
                List.of(), "erase config-file").dangerous());
        list.add(config("sys.reboot", CH_FACTORY, "重启设备", "设备会在 1 分钟后重启，业务短暂中断",
                List.of(), "reboot").dangerous());

        // 3.6.4 配置文件导入导出（系统管理视图）
        list.add(in(OltAction.View.MANAGE, "sys.config.push", CH_CONFIG_FTP, "导出配置到 FTP 服务器",
                "填 FTP 服务器 IP/掩码，文件放根目录",
                List.of(OltActionParam.text("server", "FTP IP/掩码", "如 192.168.100.123/24")),
                "config push {server}"));
        list.add(in(OltAction.View.MANAGE, "sys.config.pull", CH_CONFIG_FTP, "从 FTP 服务器导入配置",
                "导入后建议重启设备", List.of(OltActionParam.text("server", "FTP IP/掩码", null)),
                "config pull {server}"));

        // 3.7 系统升级（系统管理视图，支持单镜像）
        list.add(in(OltAction.View.MANAGE, "sys.update.system.ftp", CH_UPGRADE, "FTP 升级系统文件",
                "文件名需带后缀，不指定则用默认文件名", List.of(
                        OltActionParam.text("ip", "FTP 服务器 IP", null),
                        OltActionParam.text("user", "用户名", null),
                        OltActionParam.text("password", "密码", null),
                        OltActionParam.text("file", "系统文件名", "如 system.bin")),
                "update system ftp {ip} {user} {password} {file}"));
        list.add(in(OltAction.View.MANAGE, "sys.update.firmware.ftp", CH_UPGRADE, "FTP 升级固件",
                null, List.of(OltActionParam.text("ip", "FTP 服务器 IP", null),
                        OltActionParam.text("user", "用户名", null),
                        OltActionParam.text("password", "密码", null),
                        OltActionParam.text("file", "固件名", "如 firmware.bin")),
                "update firmware ftp {ip} {user} {password} {file}"));
        list.add(in(OltAction.View.MANAGE, "sys.update.system.tftp", CH_UPGRADE, "TFTP 升级系统文件",
                null, List.of(OltActionParam.text("ip", "TFTP 服务器 IP", null),
                        OltActionParam.text("file", "系统文件名", null)),
                "update system tftp {ip} {file}"));
        list.add(in(OltAction.View.MANAGE, "sys.update.firmware.tftp", CH_UPGRADE, "TFTP 升级固件",
                null, List.of(OltActionParam.text("ip", "TFTP 服务器 IP", null),
                        OltActionParam.text("file", "固件名", null)),
                "update firmware tftp {ip} {file}"));

        // 3.8 系统诊断
        list.add(queryWith("sys.ping", CH_PING, "连通性测试",
                "对目标主机发 5 个包，回显时延与丢包率",
                List.of(OltActionParam.text("target", "目标地址", "IPv4 或 IPv6，如 192.168.103.123")),
                "ping {target}"));

        // 3.9 时间管理
        list.add(queryWith("sys.timezone.list", CH_TIMEZONE, "按 UTC 偏移查可选城市",
                "只列候选城市，不改配置；time 格式 HH:MM",
                List.of(OltActionParam.choice("direction", "方向", List.of("utc+", "utc-"), "utc+", null),
                        OltActionParam.text("offset", "偏移时间", "格式 HH:MM，例如 11:00")),
                "timezone {direction} {offset}"));
        list.add(config("sys.timezone.set", CH_TIMEZONE, "配置时区", "填城市名，如 Asia/Chongqing",
                List.of(OltActionParam.text("region", "城市时区", "如 Asia/Chongqing")),
                "timezone \"{region}\""));
        list.add(query("sys.timezone.show", CH_TIMEZONE, "查看时区", null, "show timezone"));
        list.add(config("sys.time", CH_TIME, "配置系统时间", "格式 YYYY/MM/DD-HH:MM:SS",
                List.of(OltActionParam.text("value", "系统时间", "如 2018/06/05-15:51:00")),
                "time {value}"));
        list.add(query("sys.time.show", CH_TIME, "查看系统时间", null, "time"));
        list.add(config("sys.ntp.enable", CH_NTP, "配置 NTP 服务器",
                "备份服务器可选",
                List.of(OltActionParam.text("serverIp", "首选服务器 IP", "如 120.25.115.20"),
                        OltActionParam.optionalText("backupIp", "备份服务器 IP", "可选")),
                "ntp enable ntp-server-ip {serverIp}[ backup-server-ip {backupIp}]"));
        list.add(config("sys.ntp.disable", CH_NTP, "关闭 NTP 功能", null, List.of(), "ntp disable"));
        list.add(config("sys.ntp.interval", CH_NTP, "配置 NTP 轮询间隔",
                "取值 180-2147483648 秒",
                List.of(OltActionParam.number("interval", "轮询间隔(秒)", 180, 2147483647, "如 240")),
                "ntp poll-interval-time {interval}"));
        list.add(query("sys.ntp.show", CH_NTP, "查看 NTP 配置", null, "show ntp-status"));

        // 3.10 用户管理
        list.add(query("sys.user.show", CH_USER, "查看用户信息", null, "show user"));
        list.add(query("sys.user.current", CH_USER, "查看当前登录用户", null, "show current-user"));
        list.add(config("sys.user.add", CH_USER, "新增用户",
                "权限三档：admin / common / operator；reenter 为同时登录用户数",
                List.of(OltActionParam.text("userName", "用户名", "如 test"),
                        OltActionParam.text("password", "密码", "如 test123"),
                        OltActionParam.choice("level", "用户权限", List.of("admin", "common", "operator"),
                                "common", null),
                        OltActionParam.optionalNumber("reenter", "同时登录数", 1, 16, "如 4"),
                        OltActionParam.optionalText("info", "用户描述", "可选")),
                "user add {userName} {password} {level}[ reenter {reenter}][ {info}]"));
        list.add(config("sys.user.delete", CH_USER, "删除用户", null,
                List.of(OltActionParam.text("userName", "用户名", null)), "user delete {userName}"));
        list.add(config("sys.user.offline", CH_USER, "强制用户下线", null,
                List.of(OltActionParam.text("userName", "用户名", null)), "user offline {userName}"));
        list.add(config("sys.user.save", CH_USER, "保存用户信息", null, List.of(), "user save"));
        list.add(config("sys.user.password", CH_USER, "修改用户密码", "旧密码是当前密码",
                List.of(OltActionParam.text("userName", "用户名", null),
                        OltActionParam.text("oldPassword", "旧密码", null),
                        OltActionParam.text("newPassword", "新密码", null)),
                "user password {userName} {oldPassword} {newPassword}"));
        list.add(config("sys.user.default", CH_USER, "用户信息恢复初始状态",
                "所有账号回到出厂设置，谨慎", List.of(), "user default").dangerous());

        // 3.11 系统服务
        list.add(query("sys.service.port", CH_SERVICE, "查看系统服务端口", null, "show service-user port"));
        list.add(query("sys.web.port.show", CH_SERVICE, "查看 WEB 端口", "含 HTTP 与 HTTPS", "web-port"));
        list.add(config("sys.web.port", CH_SERVICE, "配置 WEB 端口",
                "HTTP 80-50000，HTTPS 443-50000",
                List.of(OltActionParam.choice("protocol", "协议", List.of("http", "https"), "http", null),
                        OltActionParam.number("port", "端口号", 1, 50000, "HTTP 80-50000 / HTTPS 443-50000")),
                "web-port {protocol} {port}"));
        list.add(config("sys.telnet.port", CH_SERVICE, "修改 Telnet 端口号",
                "1000-60000，改完 CLI 会重启",
                List.of(OltActionParam.number("port", "端口号", 1000, 60000, "如 9000")),
                "telnet port-modify {port}"));
        list.add(config("sys.telnet.port.reset", CH_SERVICE, "恢复默认 Telnet 端口号", null, List.of(),
                "telnet port-modify default"));
        list.add(config("sys.ssh", CH_SERVICE, "开启/关闭 SSH 服务", null,
                List.of(OltActionParam.choice("state", "状态", List.of("start", "stop"), "start", null)),
                "ssh-server {state}"));
        list.add(query("sys.snmp.show", CH_SERVICE, "查看 SNMP 配置", "trap 与 community 两段",
                "show snmp-config"));
        list.add(config("sys.snmp.read", CH_SERVICE, "配置 SNMP 读共同体",
                "一般填 public", List.of(OltActionParam.text("community", "读共同体", "如 public")),
                "snmp community-cfg read {community}"));
        list.add(config("sys.snmp.write", CH_SERVICE, "配置 SNMP 写共同体",
                "一般填 private", List.of(OltActionParam.text("community", "写共同体", "如 private")),
                "snmp community-cfg write {community}"));
        list.add(query("sys.snmp.community", CH_SERVICE, "查询 SNMP 共同体配置", null,
                "show snmp community-cfg"));
        list.add(config("sys.snmp.trap", CH_SERVICE, "配置告警 IP",
                "告警信息发送到该主机",
                List.of(OltActionParam.text("ip", "告警主机 IP", "如 192.168.100.123"),
                        OltActionParam.text("community", "团体名", "如 public")),
                "snmp trap-cfg serverip {ip} {community}"));
        return list;
    }
}

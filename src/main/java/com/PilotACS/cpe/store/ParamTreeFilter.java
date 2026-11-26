/**
 * @author: tangsky001
 * @date:   2026年11月26日
 *
 */

package com.PilotACS.cpe.store;

import java.util.Locale;
import java.util.Set;

/**
 * 参数树落库白名单：SQLite cpe_device_param 只保留业务功能真正消费的参数，
 * 其余（统计、能力清单、无关配置等）不落库，控制库体积。
 *
 * 白名单依据（对应代码里的实际消费方）：
 * <ul>
 * <li>WAN 下发/清理/快照：WANDevice.{i}.WANConnectionDevice.{j}.WANIPConnection / WANPPPConnection
 *     实例子树（DHCP / PPP / 桥接 / 静态都在这两类对象里，含 VLAN 标记、寻址方式等厂商叶子；
 *     WANCommonInterfaceConfig / WANDSLLink 等链路对象不落）；</li>
 * <li>WiFi 下发/快照：LANDevice.{i}.(LAN)WLANConfiguration.{k}（TR-098）、WiFi.*（TR-181）；</li>
 * <li>LAN 口：LANDevice.{i}.LANEthernetInterface(Config)?.{k}、Ethernet.Interface.{k}；</li>
 * <li>SIP（数量与参数树）：Services.VoiceService.*；</li>
 * <li>反向唤醒凭据 / TR069 管理通道判定：ManagementServer.*；</li>
 * <li>超级管理员账号密码 / Telnet：TeleComAccount / WebUserInfo / User 实例子树，
 *     以及任意父路径下的 TelnetEnable / TelnetUserName / TelnetPassword / TelnetPort /
 *     AdminUserName / SuperUserName / AdminPassword / SuperPassword 等叶子；</li>
 * <li>运营商识别（IspVariant 全树扫私有前缀）：DeviceInfo 下的 X_* 私有节点、
 *     各业务子树内的 X_* 叶子（随子树保留）；</li>
 * <li>台账派生字段（loadBizParams / 快照顶层扫描）：MACAddress / ExternalIPAddress / UpTime /
 *     RxPower / SignalLevel / 含 PON 的 .Mode / LOID / LogicalID。</li>
 * </ul>
 * 对象行（路径以 . 结尾）全量保留：DeviceData 的实例枚举依赖它，且行数远少于叶子。
 */
public final class ParamTreeFilter {

    private ParamTreeFilter() {
    }

    /** 命中任一段即整棵子树保留（段名精确或后缀匹配） */
    private static final Set<String> SEGMENT_EXACT = Set.of(
            "WiFi", "VoiceService", "ManagementServer", "UserInterface",
            "Interface", "User");

    private static final String[] SEGMENT_SUFFIX = {
            "WANIPConnection", "WANPPPConnection",
            "WLANConfiguration", "LANWLANConfiguration",
            "LANEthernetInterface", "LANEthernetInterfaceConfig",
            "UserInfo", "WebUserInfo", "TeleComAccount"};

    /** 任意父路径下按叶子名保留（大小写不敏感） */
    private static final Set<String> LEAVES = Set.of(
            "telnetenable", "telnetusername", "telnetpassword", "telnetport",
            "telnetwanaccess", "wanaccess", "remoteaccess", "wanremoteaccess",
            "adminusername", "superusername", "adminpassword", "superpassword",
            "macaddress", "externalipaddress", "uptime", "rxpower", "signallevel",
            "loid", "logicalid", "loidpassword");

    /** 仅 DeviceInfo 下保留的基础信息叶子（大小写不敏感） */
    private static final Set<String> DEVICE_INFO_LEAVES = Set.of(
            "manufacturer", "manufactureroui", "productclass", "modelname",
            "serialnumber", "hardwareversion", "softwareversion", "devicesummary");

    /** 判断一条参数是否需要落库 */
    public static boolean keep(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        // 对象行：实例枚举（DeviceData.instances）依赖它，全量保留
        if (path.endsWith(".")) {
            return true;
        }
        String[] segments = path.split("\\.");
        for (String seg : segments) {
            if (SEGMENT_EXACT.contains(seg)) {
                return true;
            }
            for (String suffix : SEGMENT_SUFFIX) {
                if (seg.endsWith(suffix)) {
                    return true;
                }
            }
            // DeviceInfo 下的厂商私有节点是运营商识别（IspVariant）的依据
            if (seg.startsWith("X_") && path.contains(".DeviceInfo.")) {
                return true;
            }
        }
        String leaf = segments[segments.length - 1].toLowerCase(Locale.ROOT);
        if (LEAVES.contains(leaf)) {
            return true;
        }
        if ("mode".equals(leaf) && path.toUpperCase(Locale.ROOT).contains("PON")) {
            return true;
        }
        if (DEVICE_INFO_LEAVES.contains(leaf) && path.contains(".DeviceInfo.")) {
            return true;
        }
        return false;
    }
}

package com.tr069.acs.acs.config;

import com.tr069.acs.acs.model.Isp;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 按运营商的能力采集标准 */
@Data
@ConfigurationProperties(prefix = "acs.device.capability")
public class CapabilityProfiles {

    /** 总开关：关掉后一律走内置默认标准（等同移动那套） */
    private boolean profileEnabled = true;

    /** 各家运营商的采集标准 */
    private Map<String, Profile> profiles = new LinkedHashMap<>();

    /** 取某家运营商的采集标准：yml 里配了就用配的，没配用 {@link #builtin} 里写死的那份 */
    public Profile forIsp(Isp isp) {
        Isp target = isp == null ? Isp.STANDARD : isp;
        if (!profileEnabled) {
            return builtin(Isp.STANDARD);
        }
        Profile configured = find(target.getCode());
        return configured != null ? configured : builtin(target);
    }

    /** 写死的采集标准：四家运营商 + 通用档各一份 */
    public static Profile builtin(Isp isp) {
        Profile profile = new Profile();
        Isp target = isp == null ? Isp.STANDARD : isp;
        profile.setName(target.getLabel());
        // 策略现状四家一致：先递归 + 递归不成逐层 + 都不成按实例号探；某家有差异时在此单独赋值
        profile.setGpnStrategy(Profile.STRATEGY_RECURSIVE_FIRST);
        profile.setInstanceProbe(true);
        profile.setMaxInstances(4);
        // 已知路径兜底：GPN 问不出来的设备（父对象一律 9002）靠这几条先认出设备身份；相对根节点书写
        profile.setProbePaths(List.of(
                "DeviceInfo.SpecVersion",
                "DeviceInfo.Manufacturer",
                "DeviceInfo.ManufacturerOUI",
                "DeviceInfo.ProductClass",
                "DeviceInfo.HardwareVersion",
                "DeviceInfo.SoftwareVersion",
                // 连接请求凭据只有问设备才知道，没有它就唤不醒设备
                "ManagementServer.ConnectionRequestUsername",
                "ManagementServer.ConnectionRequestPassword",
                // 业务能力代表性叶子：GPN 全 9002 的固件靠 GPV 探测 WiFi/LAN 能力，读不到会被拆批剔掉
                "LANDevice.1.WLANConfiguration.1.Enable",
                "LANDevice.1.WLANConfiguration.1.SSID",
                "LANDevice.1.WLANConfiguration.1.PreSharedKey.1.KeyPassphrase",
                "LANDevice.1.LANHostConfigManagement.IPInterface.1.IPInterfaceIPAddress",
                "LANDevice.1.LANHostConfigManagement.IPInterface.1.IPInterfaceSubnetMask",
                "LANDevice.1.LANHostConfigManagement.DHCPServerEnable",
                "LANDevice.1.WLANConfiguration.1.X_CT-COM_SSID",
                // 条目数与频段：NumberOfEntries 是设备自报口径，比数实例更准（有的固件不建实例会数出 0）
                "LANDevice.1.LANEthernetInterfaceNumberOfEntries",
                "LANDevice.1.LANEthernetInterfaceConfig.1.MaxBitRate",
                // 网口开关类参数：适配页试探下发靠它证明能力可配，只采 MaxBitRate 会因值域不一被判 9003
                "LANDevice.1.LANEthernetInterfaceConfig.1.Enable",
                "LANDevice.1.LANEthernetInterfaceConfig.1.Status",
                "LANDevice.1.WLANConfigurationNumberOfEntries",
                "LANDevice.1.WLANConfiguration.1.OperatingFrequencyBand",
                "Services.VoiceService.1.VoiceProfileNumberOfEntries",
                "Services.VoiceService.1.VoiceProfile.1.LineNumberOfEntries",
                // 8 路语音口的机器常常只建一条 Line，容量挂在物理口上
                "Services.VoiceService.1.PhyInterfaceNumberOfEntries",
                // WAN 连接完整叶子：GPN 扫不出树时靠 GPV 兜底读回业务面板字段，IP 与 PPP 连接各一套
                "WANDevice.1.WANConnectionDeviceNumberOfEntries",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnectionNumberOfEntries",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnectionNumberOfEntries",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.Enable",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.Name",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.ConnectionType",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.ConnectionStatus",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.AddressingType",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.ExternalIPAddress",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.DefaultGateway",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.DNSServers",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.NATEnabled",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.MaxMTUSize",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_ServiceList",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_VLANMode",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_VLANID",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_IPMode",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_LanInterface",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_IPv6ConnStatus",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_IPv6Address",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.Enable",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.Name",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.ConnectionType",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.ConnectionStatus",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.AddressingType",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.Username",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.ExternalIPAddress",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.DefaultGateway",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.DNSServers",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.NATEnabled",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.MaxMTUSize",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_ServiceList",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_VLANMode",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_VLANID",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_IPMode",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_LanInterface",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_IPv6ConnStatus",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_IPv6Address",
                // LAN 网口完整叶子：补 DuplexMode 和插线状态
                "LANDevice.1.LANEthernetInterfaceConfig.1.MACAddress",
                "LANDevice.1.LANEthernetInterfaceConfig.1.DuplexMode",
                "LANDevice.1.LANEthernetInterfaceConfig.1.X_CT-COM-UpLineStatus",
                "LANDevice.1.LANEthernetInterfaceConfig.1.X_CT-COM-LineStatus",
                "Services.VoiceService.1.VoiceProfile.1.Line.1.DirectoryNumber",
                // SIP 未配置时 ProxyServer 等是空值或 9005，Line 与 SIP 的开关必定有值
                "Services.VoiceService.1.VoiceProfile.1.Line.1.Enable",
                "Services.VoiceService.1.VoiceProfile.1.SIP.Enable",
                "Services.VoiceService.1.VoiceProfile.1.SIP.AuthUserName",
                "Services.VoiceService.1.VoiceProfile.1.SIP.ProxyServer"));
        // 各家差异已移到各自的 IspAdaptation 实现类（如 CtAdaptation），这里不再按运营商写 if 分支
        return profile;
    }

    private Profile find(String code) {
        if (profiles == null || profiles.isEmpty() || code == null) {
            return null;
        }
        Profile exact = profiles.get(code);
        if (exact != null) {
            return exact;
        }
        String wanted = code.trim().toUpperCase(Locale.ROOT);
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            if (entry.getKey() != null && entry.getKey().trim().toUpperCase(Locale.ROOT).equals(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 一家运营商的采集标准 */
    @Data
    public static class Profile {

        /** 递归优先：先 {@code NextLevel=false} 一次拿全量，失败再逐层展开 */
        public static final String STRATEGY_RECURSIVE_FIRST = "recursive-first";

        /** 逐层优先：先 {@code NextLevel=true} 逐层下钻，设备不吃递归时用它 */
        public static final String STRATEGY_LEVEL_FIRST = "level-first";

        /** 只读关键字段：一条 GetParameterNames 都不发，直接按需 GetParameterValues */
        public static final String STRATEGY_VALUES_ONLY = "values-only";

        /** 自适应：先按参数树扫，扫不动就降级为只读关键字段，并记住这台设备 */
        public static final String STRATEGY_ADAPTIVE = "adaptive";

        /** 展示名，只进日志 */
        private String name = "";

        /** GPN 策略：{@code recursive-first}（默认）或 {@code level-first} */
        private String gpnStrategy = STRATEGY_RECURSIVE_FIRST;

        /** 父对象整棵拉不到时，是否按实例号 {@code 1..N} 逐个探测 */
        private boolean instanceProbe = true;

        /** 实例探测的最大实例号，探到第一个不存在的实例号就停 */
        private int maxInstances = 4;

        /** TR-098（{@code InternetGatewayDevice.}）下的业务子树，相对根节点书写 */
        private List<String> subtrees = List.of();

        /** TR-181（{@code Device.}）下的业务子树，留空用内置默认 */
        private List<String> subtreesTr181 = List.of();

        /** 兜底探测路径：GPN 各种姿势都失败时，改用 GetParameterValues 直接读这些路径 */
        private List<String> probePaths = List.of();

        /** 逐层展开时要绕开的对象名前缀：命中就记下这个对象，但不再往下展开 */
        private List<String> skipPrefixes = List.of();

        /** 是否逐层优先 */
        public boolean isLevelFirst() {
            return STRATEGY_LEVEL_FIRST.equalsIgnoreCase(
                    gpnStrategy == null ? "" : gpnStrategy.trim());
        }

        /** 是否只读关键字段、完全不扫参数树 */
        public boolean isValuesOnly() {
            return STRATEGY_VALUES_ONLY.equalsIgnoreCase(
                    gpnStrategy == null ? "" : gpnStrategy.trim());
        }

        /** 是否先试参数树、扫不动再降级为只读关键字段 */
        public boolean isAdaptive() {
            return STRATEGY_ADAPTIVE.equalsIgnoreCase(
                    gpnStrategy == null ? "" : gpnStrategy.trim());
        }
    }
}

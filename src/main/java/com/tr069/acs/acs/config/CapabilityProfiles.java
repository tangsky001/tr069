package com.tr069.acs.acs.config;

import com.tr069.acs.acs.model.Isp;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按运营商的能力采集标准。
 */
@Data
@ConfigurationProperties(prefix = "acs.device.capability")
public class CapabilityProfiles {

    /** 总开关：关掉后一律走内置默认标准（等同移动那套）。 */
    private boolean profileEnabled = true;

    /** 各家运营商的采集标准。 */
    private Map<String, Profile> profiles = new LinkedHashMap<>();

    /**
     * 取某家运营商的采集标准：yml 里配了就用配的，没配用 {@link #builtin} 里写死的那份。
     */
    public Profile forIsp(Isp isp) {
        Isp target = isp == null ? Isp.STANDARD : isp;
        if (!profileEnabled) {
            return builtin(Isp.STANDARD);
        }
        Profile configured = find(target.getCode());
        return configured != null ? configured : builtin(target);
    }

    /**
     * 写死的采集标准：四家运营商 + 通用档各一份。
     */
    public static Profile builtin(Isp isp) {
        Profile profile = new Profile();
        Isp target = isp == null ? Isp.STANDARD : isp;
        profile.setName(target.getLabel());
        // 策略现状：四家一致，都是「先递归 + 递归不成逐层 + 都不成按实例号探」。
        // 哪天某家确认了更合适的姿势，在这里单独给这家赋值即可
        profile.setGpnStrategy(Profile.STRATEGY_RECURSIVE_FIRST);
        profile.setInstanceProbe(true);
        profile.setMaxInstances(4);
        // 已知路径兜底：GetParameterNames 一句都问不出来的设备（电信定制机的典型表现，
        // 父对象一律 9002），靠这几条把设备身份读回来——先认出这是什么猫，再谈能力树。
        // 相对数据模型根节点书写，TR-098 与 TR-181 通用
        profile.setProbePaths(List.of(
                "DeviceInfo.SpecVersion",
                "DeviceInfo.Manufacturer",
                "DeviceInfo.ManufacturerOUI",
                "DeviceInfo.ProductClass",
                "DeviceInfo.HardwareVersion",
                "DeviceInfo.SoftwareVersion",
                // 连接请求（ACS 反连设备）的认证凭据是设备侧生成的，只有问它才知道。
                // 没有凭据就唤不醒设备，手动采集只能干等它自己上报，所以一并读回来
                "ManagementServer.ConnectionRequestUsername",
                "ManagementServer.ConnectionRequestPassword",
                // 业务能力：拉不动参数树的设备（GetParameterNames 一律回 9002 的固件，
                // 瑞斯康达 MSG2100E 就是这种）拿不到完整的树，型号模板里的
                // WiFi / LAN / SIP 数就全是 0，同型号的工单没法下发这几类业务。
                // 这几条是 TR-098 的代表性叶子，读到就等于承认有这块能力，
                // 读不到会被二分拆批剔掉，不影响其他路径
                "LANDevice.1.WLANConfiguration.1.Enable",
                "LANDevice.1.WLANConfiguration.1.SSID",
                "LANDevice.1.WLANConfiguration.1.PreSharedKey.1.KeyPassphrase",
                "LANDevice.1.LANHostConfigManagement.IPInterface.1.IPInterfaceIPAddress",
                "LANDevice.1.LANHostConfigManagement.IPInterface.1.IPInterfaceSubnetMask",
                "LANDevice.1.LANHostConfigManagement.DHCPServerEnable",
                "LANDevice.1.WLANConfiguration.1.X_CT-COM_SSID",
                // 条目数与频段：适配页要拿实测数校验申报值（4 口 / 8 路 SIP / 2.4G）。
                // 只数实例容易低估——不建 LANEthernetInterfaceConfig 的固件数出 0 口，
                // 只建一路 Line 的数出 1 路。NumberOfEntries 是设备自报的口径，更准
                "LANDevice.1.LANEthernetInterfaceNumberOfEntries",
                "LANDevice.1.LANEthernetInterfaceConfig.1.MaxBitRate",
                // 网口的开关类参数：适配页的试探下发要靠它证明「这个能力真能配」。
                // 只采 MaxBitRate 的话，值域各厂商不一（「1000M」写回去就被判 9003），
                // 表格里网口那一行只能显示「无可写参数」
                "LANDevice.1.LANEthernetInterfaceConfig.1.Enable",
                "LANDevice.1.LANEthernetInterfaceConfig.1.Status",
                "LANDevice.1.WLANConfigurationNumberOfEntries",
                "LANDevice.1.WLANConfiguration.1.OperatingFrequencyBand",
                "Services.VoiceService.1.VoiceProfileNumberOfEntries",
                "Services.VoiceService.1.VoiceProfile.1.LineNumberOfEntries",
                // 8 路语音口的机器常常只建一条 Line，容量挂在物理口上
                "Services.VoiceService.1.PhyInterfaceNumberOfEntries",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.ExternalIPAddress",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.Username",
                "Services.VoiceService.1.VoiceProfile.1.Line.1.DirectoryNumber",
                // SIP 未配置时 ProxyServer / AuthUserName 是空值或直接 9005，
                // 试探就一个候选都挑不出来。Line 与 SIP 的开关是必定有值的
                "Services.VoiceService.1.VoiceProfile.1.Line.1.Enable",
                "Services.VoiceService.1.VoiceProfile.1.SIP.Enable",
                "Services.VoiceService.1.VoiceProfile.1.SIP.AuthUserName",
                "Services.VoiceService.1.VoiceProfile.1.SIP.ProxyServer"));
        // 天翼那套私有分支，遍历进去必抛 9002：逐层展开到此为止
        profile.setSkipPrefixes(List.of("X_CTC_"));
        if (target == Isp.CT) {
            // 电信天翼网关：不是每台都扫不动（HS8145C5 能递归拉全树，B866 连「只列下一层」
            // 都 9002），所以先试一次参数树，撞 9002 再降级为只读关键字段并记住这台
            profile.setGpnStrategy(Profile.STRATEGY_ADAPTIVE);
            // 天翼网关自带一份清单，不共用「按移动验证过」的内置默认：
            // 标准三棵之外，天翼的业务标识挂在 X_CT-COM_UserInfo 下（随 Inform 上报过）
            profile.setSubtrees(List.of(
                    "WANDevice.",
                    "LANDevice.",
                    "Services.VoiceService.",
                    "X_CT-COM_UserInfo."));
            // 这台设备唯一能拿到的东西：设备身份 + 天翼业务标识 + 几个常用入口参数。
            // 清单刻意保持短——多一条无效路径就多一轮拆批，每轮要等设备一个上报周期；
            // 读不到的路径会被二分拆批剔除，不影响其余
            List<String> probePaths = new ArrayList<>(profile.getProbePaths());
            probePaths.addAll(List.of(
                    "DeviceInfo.ModelName",
                    "DeviceInfo.OUI",
                    "DeviceInfo.SerialNumber",
                    "DeviceInfo.ProvisioningCode",
                    "ManagementServer.ConnectionRequestURL",
                    "WANDeviceNumberOfEntries",
                    "WANDevice.1.WANCommonInterfaceConfig.WANAccessType",
                    "X_CT-COM_UserInfo.UserId",
                    "X_CT-COM_UserInfo.UserName"));
            profile.setProbePaths(probePaths);
        }
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

    /**
     * 一家运营商的采集标准。
     */
    @Data
    public static class Profile {

        /** 递归优先：先 {@code NextLevel=false} 一次拿全量，失败再逐层展开。 */
        public static final String STRATEGY_RECURSIVE_FIRST = "recursive-first";

        /** 逐层优先：先 {@code NextLevel=true} 逐层下钻，设备不吃递归时用它。 */
        public static final String STRATEGY_LEVEL_FIRST = "level-first";

        /**
         * 只读关键字段：一条 GetParameterNames 都不发，直接按需 GetParameterValues。
         */
        public static final String STRATEGY_VALUES_ONLY = "values-only";

        /**
         * 自适应：先按参数树扫，扫不动就降级为只读关键字段，并记住这台设备。
         */
        public static final String STRATEGY_ADAPTIVE = "adaptive";

        /** 展示名，只进日志。 */
        private String name = "";

        /**
         * GPN 策略：{@code recursive-first}（默认）或 {@code level-first}。
         */
        private String gpnStrategy = STRATEGY_RECURSIVE_FIRST;

        /**
         * 父对象整棵拉不到时，是否按实例号 {@code 1..N} 逐个探测。
         */
        private boolean instanceProbe = true;

        /** 实例探测的最大实例号，探到第一个不存在的实例号就停。 */
        private int maxInstances = 4;

        /**
         * TR-098（{@code InternetGatewayDevice.}）下的业务子树，相对根节点书写。
         * 留空用内置默认：WAN / LAN 网口（含 WiFi）/ 语音。
         */
        private List<String> subtrees = List.of();

        /** TR-181（{@code Device.}）下的业务子树，留空用内置默认。 */
        private List<String> subtreesTr181 = List.of();

        /**
         * 兜底探测路径：GPN 各种姿势都失败时，改用 GetParameterValues 直接读这些路径。
         *
         * <p>逐个路径单独发（一条一回），代价是每条等一个上报周期，只在已知关键路径时填。</p>
         */
        private List<String> probePaths = List.of();

        /**
         * 逐层展开时要绕开的对象名前缀：命中就记下这个对象，但不再往下展开。
         */
        private List<String> skipPrefixes = List.of();

        /**
         * 是否逐层优先。
         */
        public boolean isLevelFirst() {
            return STRATEGY_LEVEL_FIRST.equalsIgnoreCase(
                    gpnStrategy == null ? "" : gpnStrategy.trim());
        }

        /** 是否只读关键字段、完全不扫参数树。 */
        public boolean isValuesOnly() {
            return STRATEGY_VALUES_ONLY.equalsIgnoreCase(
                    gpnStrategy == null ? "" : gpnStrategy.trim());
        }

        /** 是否先试参数树、扫不动再降级为只读关键字段。 */
        public boolean isAdaptive() {
            return STRATEGY_ADAPTIVE.equalsIgnoreCase(
                    gpnStrategy == null ? "" : gpnStrategy.trim());
        }
    }
}

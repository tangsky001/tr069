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
 *
 * <p>四家运营商的定制机对同一句 CWMP 请求的反应并不一致：移动定制机吃
 * {@code GetParameterNames(InternetGatewayDevice.WANDevice., NextLevel=false)}，
 * 电信定制机对同样的请求直接回 9002 Internal error，得换成带实例号的
 * {@code InternetGatewayDevice.WANDevice.1.} 才认。所以采集标准按运营商分开：每家一份
 * 子树清单与兜底策略，标准本身<strong>写死在 {@link #builtin}</strong>，现场不用维护配置。</p>
 *
 * <p>yml 里的 {@code acs.device.capability.profiles.<CMCC|CT|CU|CBN|standard>} 只作为
 * 排障时的临时覆盖：配了就用配的，没配的运营商走 {@link #builtin}。
 * 未登记运营商的设备走 {@code standard}。</p>
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
     *
     * <p>四家定制机的硬件往往是同一套，差别全在固件：私有参数前缀不同，连「能不能递归拉
     * 参数树」都不一致（电信定制机对 {@code InternetGatewayDevice.WANDevice.} 直接回 9002）。
     * 所以每家一份标准，子树清单、GPN 策略、实例探测都在里面，新增一家加一支 {@code case}。</p>
     *
     * <p>子树留空表示「用采集服务内置的那份」（TR-098 的 WAN / LAN / 语音，TR-181 对应挂载点）。
     * 只有确认了自家结构的运营商才单独写清单——电信天翼网关已经写了，
     * 不与移动共用同一份默认。</p>
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
                "ManagementServer.ConnectionRequestPassword"));
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
         *
         * <p>电信天翼网关就是这类：它的 CWMP 模块把第三方 ACS 的参数树遍历当成非法调用，
         * 递归进去撞上 {@code X_CTC_} 私有分支直接抛 9002；实测连
         * {@code GetParameterNames(InternetGatewayDevice.WANDevice., NextLevel=true)} 这种
         * 「只列下一层」都回 9002——它对对象路径的 GPN 一律不答。官方 ITMS 也从不遍历，
         * 只按需读几个字段。所以这类设备按 ITMS 的做法走：不扫树，只读清单里的参数。</p>
         */
        public static final String STRATEGY_VALUES_ONLY = "values-only";

        /**
         * 自适应：先按参数树扫，扫不动就降级为只读关键字段，并记住这台设备。
         *
         * <p>同为电信天翼网关，固件差异极大：B866 那种对对象路径的 GPN 一律回 9002
         * （连「只列下一层」都不行），HS8145C5 却能正常递归拉全树。一刀切不扫树，
         * 会让能扫的设备白白丢掉能力树（也就导不出型号档案、生成不了业务模板）。
         * 所以先试一次：扫得动就正常采，撞 9002 就降级并记住，下一轮不再白等。</p>
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
         *
         * <p>电信定制机对 {@code WANDevice.} 这种「对象 + 下一个实例还没定」的路径回 9002，
         * 但 {@code WANDevice.1.} 能正常应答；代价是每探一个实例要等设备一次上报周期。</p>
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
         *
         * <p>电信定制机 {@code X_CTC_} 那一套（天翼插件、ITMS 探针、天翼管家、DPI 软探针、
         * 业务虚拟对象）固件实现得很脆：参数树一遍历进去就抛 9002 Internal error，整棵子树
         * 连带报废。ITMS 自己的做法也是只按需读几个字段，从不递归遍历。</p>
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

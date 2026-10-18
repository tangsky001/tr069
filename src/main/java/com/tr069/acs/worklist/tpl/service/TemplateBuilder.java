package com.tr069.acs.worklist.tpl.service;

import com.tr069.acs.worklist.tpl.dao.DeviceLanDao;
import com.tr069.acs.worklist.tpl.dao.DeviceWanDao;
import com.tr069.acs.worklist.tpl.dao.SipDao;
import com.tr069.acs.worklist.tpl.dao.VlanDao;
import com.tr069.acs.worklist.tpl.dao.WlanADao;
import com.tr069.acs.worklist.tpl.dao.WlanBDao;
import com.tr069.acs.worklist.tpl.entity.Device;
import com.tr069.acs.worklist.tpl.entity.DeviceLan;
import com.tr069.acs.worklist.tpl.entity.DeviceWan;
import com.tr069.acs.worklist.tpl.entity.Sip;
import com.tr069.acs.worklist.tpl.entity.Vlan;
import com.tr069.acs.worklist.tpl.entity.WlanA;
import com.tr069.acs.worklist.tpl.entity.WlanB;
import com.tr069.acs.worklist.tpl.TemplateVariables;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 按「厂商参数命名规则 + 适配申报能力」拼出下发模板，写进各业务表。
 *
 * <p>这里做的是<b>拼接</b>而不是猜测：实例号来自厂商约定、路径后缀来自该型号实际
 * 采集到的参数名，能力有无来自适配时的人工申报。三者都不需要靠解读上报值去推断。</p>
 *
 * <h3>WiFi 的实例号约定</h3>
 * <p>光猫的 SSID 实例按射频分组，各厂商把每组的起始实例号放在固定位置——
 * 中兴 P22g-CIac 实测是 <b>SSID1 起为 2.4G、SSID5 起为 5.8G</b>（共 8 个实例）。
 * 所以模板取每组的第一个实例：</p>
 * <ul>
 *   <li>申报 {@code 2.4G} → 只写 {@code wlan_a}，实例号 {@value #SSID_24G}</li>
 *   <li>申报 {@code 2.4G+5.8G} → 再写 {@code wlan_b}，实例号 {@value #SSID_58G}</li>
 * </ul>
 * <p>申报只到「2.4G」与「2.4G+5.8G」两档，5G 是叠加在 2.4G 之上的，
 * 不存在「只有 5G 没有 2.4G」的组合，因此这里不需要为那种情况写分支。</p>
 *
 * <h3>为什么值一律是变量</h3>
 * <p>模板库要能整个拷走，所以 Password/PreSharedKey 这类字段只能写 {@code %wifi_pwd%}，
 * SSID 写 {@code %room%}。库里出现明文口令视为 Bug。</p>
 */
@Slf4j
@Service
public class TemplateBuilder {

    /** TR-098 下 SSID 实例的挂载位置（目前这个型号只用到 TR-098）。 */
    private static final String TR098_WLAN = "LANDevice.1.WLANConfiguration.";

    /** 2.4G 频段的 SSID 起始实例号（厂商约定，非推测）。 */
    public static final int SSID_24G = 1;

    /** 5.8G 频段的 SSID 起始实例号（厂商约定，非推测）。 */
    public static final int SSID_58G = 5;

    /** 语音业务挂载位置，中兴 P22g-CIac 实测只有 1 个 VoiceService / VoiceProfile 实例。 */
    private static final String VOICE_BASE = "Services.VoiceService.1.VoiceProfile.1.";

    /** 一路 SIP 账号的起始 Line 实例号（厂商约定，非推测）。 */
    public static final int LINE_FIRST = 1;

    /**
     * WAN 业务连接挂载位置，取自真实下发报文（权威参考，非推测）：
     * AddObject 建在 {@code WANConnectionDevice.2} 下。
     */
    public static final String WAN_BASE = "WANDevice.1.WANConnectionDevice.2.";

    /** AddObject 的对象名末段，末尾不带实例号——实例号由设备分配，事后才知道。 */
    public static final String WAN_IP_CONN = "WANIPConnection.";

    /** AddObject 的对象名末段，末尾不带实例号——实例号由设备分配，事后才知道。 */
    public static final String WAN_PPP_CONN = "WANPPPConnection.";

    /** 参数路径里的实例号占位符，下发引擎 AddObject 后回填真实值。 */
    public static final String INSTANCE_PLACEHOLDER = "{i}";

    /** LAN 网口挂载位置。 */
    private static final String LAN_BASE = "LANDevice.1.LANEthernetInterfaceConfig.";

    /** 模板库里各业务 WAN 的排位号，用来区分 DHCP / PPPoE / Static 各自的模板行。 */
    public static final int WAN_SLOT_DHCP = 1;
    public static final int WAN_SLOT_PPPOE = 2;
    public static final int WAN_SLOT_STATIC = 3;

    /** 默认数据模型根节点：TR-098。 */
    private static final String DEFAULT_ROOT = "InternetGatewayDevice.";

    private final WlanADao wlanADao;
    private final WlanBDao wlanBDao;
    private final SipDao sipDao;
    private final DeviceWanDao deviceWanDao;
    private final VlanDao vlanDao;
    private final DeviceLanDao deviceLanDao;

    public TemplateBuilder(WlanADao wlanADao, WlanBDao wlanBDao, SipDao sipDao,
                           DeviceWanDao deviceWanDao, VlanDao vlanDao, DeviceLanDao deviceLanDao) {
        this.wlanADao = wlanADao;
        this.wlanBDao = wlanBDao;
        this.sipDao = sipDao;
        this.deviceWanDao = deviceWanDao;
        this.vlanDao = vlanDao;
        this.deviceLanDao = deviceLanDao;
    }

    /**
     * 按设备的 WiFi 能力申报生成 2.4G / 5.8G 下发模板。
     *
     * @param device 型号主表记录，需含 {@code capWifiSpec} 与 {@code rootNode}
     * @return 两张表各写了几条
     */
    public WifiResult buildWifi(Device device) {
        WifiResult r = new WifiResult();
        if (device == null || device.getId() == null) {
            return r;
        }
        String spec = device.getCapWifiSpec();
        if (spec == null || spec.isBlank() || "无".equals(spec.trim())) {
            log.debug("型号未申报 WiFi 能力，跳过: id={}", device.getId());
            return r;
        }

        boolean has24 = spec.contains("2.4G");
        boolean has58 = spec.contains("5.8G") || spec.contains("5G");

        if (has24) {
            wlanADao.upsert(fill24(device, base(device, SSID_24G), SSID_24G));
            r.wlanA = 1;
        }
        if (has58) {
            wlanBDao.upsert(fill58(device, base(device, SSID_58G), SSID_58G));
            r.wlanB = 1;
        }
        log.info("生成 WiFi 模板: deviceId={}, cap={}, wlan_a={}, wlan_b={}",
                device.getId(), spec, r.wlanA, r.wlanB);
        return r;
    }

    /**
     * 按设备的语音能力申报生成 SIP 下发模板，一路 Line 一条记录。
     *
     * <p>路径全部取自中兴 P22g-CIac 实际采集到的参数名。其中有两条是实测才发现、
     * 照搬 TR-104 标准一定会踩的坑：</p>
     * <ul>
     *   <li>{@code VoiceProfile.1.Enable} 在这个型号上<b>并不存在</b>，可写开关只挂在
     *       {@code Line.{i}.Enable}，写前者设备会回「无效参数」</li>
     *   <li>号码不走 {@code DirectoryNumber}（该型号同样没有），账号由
     *       {@code SIP.AuthUserName} 承载，注册地址由 {@code SIP.URI} 承载</li>
     * </ul>
     *
     * @param device 型号主表记录，需含 {@code capSipCount} 与 {@code rootNode}
     * @return 写了几条 SIP 记录
     */
    public SipResult buildSip(Device device) {
        SipResult r = new SipResult();
        if (device == null || device.getId() == null) {
            return r;
        }
        int count = declaredCount(device.getCapSipCount());
        if (count <= 0) {
            log.debug("型号未申报语音能力，跳过 SIP: id={}", device.getId());
            return r;
        }
        String profile = rootOf(device) + VOICE_BASE;
        for (int line = LINE_FIRST; line < LINE_FIRST + count; line++) {
            sipDao.upsert(fillSip(device, profile, line));
            r.sip++;
        }
        log.info("生成 SIP 模板: deviceId={}, cap={}, lines={}",
                device.getId(), device.getCapSipCount(), r.sip);
        return r;
    }

    /**
     * 申报的能力数量。申报值是人工填的字符串，可能是「1」「4」也可能是「无」。
     */
    private static int declaredCount(String declared) {
        if (declared == null || declared.isBlank()) {
            return 0;
        }
        String text = declared.trim();
        if ("无".equals(text) || "否".equals(text)) {
            return 0;
        }
        try {
            int n = Integer.parseInt(text.replaceAll("\\D", ""));
            // 光猫通常最多 8 路语音，超出按 8 封顶，防止申报填错把模板写崩
            return Math.min(n, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 根节点前缀，末尾保证带点，各业务拼路径共用。
     */
    private static String rootOf(Device device) {
        String root = device.getRootNode();
        if (root == null || root.isBlank()) {
            root = DEFAULT_ROOT;
        }
        return root.endsWith(".") ? root : root + ".";
    }

    /**
     * 拼某个 SSID 实例的路径前缀，如 {@code InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.}。
     */
    private static String base(Device device, int instanceNo) {
        return rootOf(device) + TR098_WLAN + instanceNo + ".";
    }

    /** 一路 SIP 账号的全部下发路径与变量值。 */
    private static Sip fillSip(Device device, String profile, int lineNo) {
        String line = profile + "Line." + lineNo + ".";
        Sip r = new Sip();
        r.setDeviceId(device.getId());
        r.setInstanceNo(lineNo);
        // 首路 Line 出厂自带可直接设值，更多的要 AddObject 建出来
        r.setAction(lineNo == LINE_FIRST ? "SET" : "ADD");
        r.setVerified("UNVERIFIED");
        r.setPathEnable(line + "Enable");
        r.setValEnable("1");
        r.setPathUser(line + "SIP.AuthUserName");
        r.setValUser(TemplateVariables.SIP_NUM);
        r.setPathPwd(line + "SIP.AuthPassword");
        r.setValPwd(TemplateVariables.SIP_PWD);
        r.setPathUri(line + "SIP.URI");
        // URI 只放号码，不再拼 @代理：设备上「号码/用户名」两栏直接读 URI，
        // 带上 @192.168.x.x 会被当成号码的一部分，运维看着像账号开错了。
        // 注册/代理用的服务器由 ProxyServer、RegistrarServer 单独下发，不需要塞进 URI。
        r.setValUri(TemplateVariables.SIP_NUM);
        // 代理服务器挂在 VoiceProfile 一级，不是每个 Line 各一份
        r.setPathProxy(profile + "SIP.ProxyServer");
        r.setValProxy(TemplateVariables.SIP_PROXY);
        r.setPathPort(profile + "SIP.ProxyServerPort");
        r.setValPort(TemplateVariables.SIP_PORT);
        r.setPathRegistrar(profile + "SIP.RegistrarServer");
        r.setValRegistrar(TemplateVariables.SIP_REGISTRAR);
        r.setPathRegistrarPort(profile + "SIP.RegistrarServerPort");
        r.setValRegistrarPort(TemplateVariables.SIP_REGISTRAR_PORT);
        return r;
    }

    /**
     * 生成 WAN DHCP（IPoE 上网）下发模板，对照真实下发报文逐项落库：
     *
     * <pre>
     * AddObject        ...WANConnectionDevice.2.WANIPConnection.
     * SetParameterValues  ...WANIPConnection.{i}.Enable            = 1
     *                     ...WANIPConnection.{i}.ConnectionType    = IP_Routed
     *                     ...WANIPConnection.{i}.IPAddressingType  = DHCP
     *                     ...WANIPConnection.{i}.NATEnabled        = 1
     *                     ...WANIPConnection.{i}.X_CMCC_VLANID     = %vlan_id%
     *                     ...WANIPConnection.{i}.X_CMCC_BindLANPort = ""
     * </pre>
     *
     * <p>两条实测结论直接决定这里的写法：其一，业务 WAN 建在
     * {@code WANConnectionDevice.2} 下，实例号由设备 AddObject 时分配、事先不知道，
     * 所以参数路径全部用 {@value #INSTANCE_PLACEHOLDER} 占位，下发引擎拿到返回实例号再回填；
     * 其二，VLAN 的真实参数名是 {@code X_CMCC_VLANID}（不是能力树里出现的
     * {@code X_CMCC_VLANIDMark}，那个是 TR069 通道上的另一个参数），VLAN 语义独立成
     * {@code vlan} 表存，{@code wanInstance} 关联刚建的 WAN。</p>
     *
     * <p>绑定端口（截图里的 LAN/SSID/PON 多选）报文里下发的是空串（不修改），
     * 模板先与报文保持一致存空，等绑定端口的取值格式（逗号分隔？下标？）确认后再接多选。</p>
     *
     * @param device 型号主表记录
     * @return 写了几条（wan / vlan 各一张表）
     */
    public WanResult buildWanDhcp(Device device) {
        WanResult r = new WanResult();
        if (device == null || device.getId() == null) {
            return r;
        }
        DeviceWan wan = newWan(device, WAN_SLOT_DHCP, "IPoE", WAN_IP_CONN);
        String p = paramPrefix(wan);
        wan.setPathAddrType(p + "IPAddressingType");
        wan.setValAddrType("DHCP");
        wan.setPathNat(p + "NATEnabled");
        wan.setValNat("1");
        wan.setPathBindPort(p + "X_CMCC_BindLANPort");
        wan.setValBindPort("");
        deviceWanDao.upsert(wan);
        r.setWan(1);

        upsertVlan(device, WAN_SLOT_DHCP, p + "X_CMCC_VLANID");
        r.setVlan(1);

        log.info("生成 WAN DHCP 模板: deviceId={}, objectPath={}", device.getId(), wan.getObjectPath());
        return r;
    }

    /**
     * 生成 WAN PPPoE 下发模板。
     *
     * <p><b>与 DHCP 那套的区别只有对象和鉴权参数</b>：实例建在 {@code WANPPPConnection.}
     * 下（该对象在能力树里确实存在，与 DHCP 的 {@code WANIPConnection.} 并列），
     * 账号密码落到 TR-098 标准的 {@code Username} / {@code Password}。</p>
     *
     * <p><b>这两处是推断而非实测</b>：这台设备当前没有 PPPoE 连接，能力树上只采得到
     * {@code WANPPPConnection} 这个对象名和 {@code WANPPPConnectionNumberOfEntries}，
     * 采不到实例参数，所以鉴权参数名按标准取、整行标 {@code UNVERIFIED}。
     * 拿到一台真跑 PPPoE 的设备（或一份 PPPoE 下发报文）即可验掉这个不确定性。</p>
     */
    public WanResult buildWanPppoe(Device device) {
        WanResult r = new WanResult();
        if (device == null || device.getId() == null) {
            return r;
        }
        DeviceWan wan = newWan(device, WAN_SLOT_PPPOE, "PPPoE", WAN_PPP_CONN);
        String p = paramPrefix(wan);
        wan.setPathUsername(p + "Username");
        wan.setValUsername(TemplateVariables.PPPOE_USER);
        wan.setPathPassword(p + "Password");
        wan.setValPassword(TemplateVariables.PPPOE_PWD);
        wan.setPathNat(p + "NATEnabled");
        wan.setValNat("1");
        wan.setPathBindPort(p + "X_CMCC_BindLANPort");
        wan.setValBindPort("");
        deviceWanDao.upsert(wan);
        r.setWan(1);

        upsertVlan(device, WAN_SLOT_PPPOE, p + "X_CMCC_VLANID");
        r.setVlan(1);

        log.info("生成 WAN PPPoE 模板: deviceId={}, objectPath={}", device.getId(), wan.getObjectPath());
        return r;
    }

    /**
     * 生成 WAN 静态地址下发模板。
     *
     * <p>四个地址参数名取自能力树实测（{@code ExternalIPAddress} / {@code SubnetMask} /
     * {@code DefaultGateway} / {@code DNSServers}），值一律留变量按房间分配。
     * 静态与 DHCP 同为 {@code WANIPConnection}，差别只在 IPAddressingType=Static。</p>
     */
    public WanResult buildWanStatic(Device device) {
        WanResult r = new WanResult();
        if (device == null || device.getId() == null) {
            return r;
        }
        DeviceWan wan = newWan(device, WAN_SLOT_STATIC, "Static", WAN_IP_CONN);
        String p = paramPrefix(wan);
        wan.setPathAddrType(p + "IPAddressingType");
        wan.setValAddrType("Static");
        wan.setPathNat(p + "NATEnabled");
        wan.setValNat("1");
        wan.setPathIp(p + "ExternalIPAddress");
        wan.setValIp(TemplateVariables.WAN_IP);
        wan.setPathMask(p + "SubnetMask");
        wan.setValMask(TemplateVariables.WAN_MASK);
        wan.setPathGw(p + "DefaultGateway");
        wan.setValGw(TemplateVariables.WAN_GW);
        wan.setPathDns(p + "DNSServers");
        wan.setValDns(TemplateVariables.WAN_DNS);
        deviceWanDao.upsert(wan);
        r.setWan(1);

        upsertVlan(device, WAN_SLOT_STATIC, p + "X_CMCC_VLANID");
        r.setVlan(1);

        log.info("生成 WAN 静态地址模板: deviceId={}, objectPath={}", device.getId(), wan.getObjectPath());
        return r;
    }

    /**
     * 生成 LAN 网口下发模板，几个网口写几行。
     *
     * <p>参数名都是能力树实测：{@code Enable} / {@code DuplexMode} / {@code MaxBitRate}。
     * 网口是出厂就有的，不需要 AddObject，所以 action 一律 SET。</p>
     *
     * <p>双工与速率统一写 {@code Auto}（自适应）——真机上如果要求强制千兆全双工，
     * 改这两个值即可，参数名不用动。</p>
     */
    public LanResult buildLan(Device device) {
        LanResult r = new LanResult();
        if (device == null || device.getId() == null) {
            return r;
        }
        int ports = declaredCount(device.getCapLanPorts());
        if (ports <= 0) {
            log.debug("型号未申报网口数，跳过 LAN: id={}", device.getId());
            return r;
        }
        String base = rootOf(device) + LAN_BASE;
        for (int i = 1; i <= ports; i++) {
            DeviceLan lan = new DeviceLan();
            lan.setDeviceId(device.getId());
            lan.setInstanceNo(i);
            lan.setAction("SET");
            lan.setVerified("UNVERIFIED");
            String p = base + i + ".";
            lan.setPathEnable(p + "Enable");
            lan.setValEnable("1");
            lan.setPathDuplex(p + "DuplexMode");
            lan.setValDuplex("Auto");
            lan.setPathSpeed(p + "MaxBitRate");
            lan.setValSpeed("Auto");
            deviceLanDao.upsert(lan);
            r.setLan(r.getLan() + 1);
        }
        log.info("生成 LAN 模板: deviceId={}, ports={}", device.getId(), ports);
        return r;
    }

    /** 一条 WAN 模板行的公共部分：挂载位置、开关、连接类型。 */
    private static DeviceWan newWan(Device device, int slot, String connType, String objectSuffix) {
        DeviceWan wan = new DeviceWan();
        wan.setDeviceId(device.getId());
        // 模板库内的排位号；真实实例号下发时以 AddObject 返回为准
        wan.setInstanceNo(slot);
        wan.setConnType(connType);
        wan.setAction("ADD");
        wan.setVerified("UNVERIFIED");
        wan.setObjectPath(rootOf(device) + WAN_BASE + objectSuffix);
        String p = paramPrefix(wan);
        wan.setPathEnable(p + "Enable");
        wan.setValEnable("1");
        wan.setPathConnType(p + "ConnectionType");
        wan.setValConnType("IP_Routed");
        return wan;
    }

    /** 参数路径前缀，实例号留占位——AddObject 之后才知道是几号。 */
    private static String paramPrefix(DeviceWan wan) {
        return wan.getObjectPath() + INSTANCE_PLACEHOLDER + ".";
    }

    /** VLAN 独立成表，{@code wanInstance} 关联到对应业务 WAN 的排位号。 */
    private void upsertVlan(Device device, int wanInstance, String path) {
        Vlan vlan = new Vlan();
        vlan.setDeviceId(device.getId());
        vlan.setWanInstance(wanInstance);
        vlan.setAction("ADD");
        vlan.setVerified("UNVERIFIED");
        vlan.setPathVlanId(path);
        vlan.setValVlanId(TemplateVariables.VLAN_ID);
        vlanDao.upsert(vlan);
    }

    private static WlanA fill24(Device device, String base, int instanceNo) {
        WlanA r = new WlanA();
        r.setDeviceId(device.getId());
        r.setInstanceNo(instanceNo);
        r.setAction(instanceExists(device, instanceNo) ? "SET" : "ADD");
        r.setVerified("UNVERIFIED");
        r.setPathEnable(base + "Enable");
        r.setValEnable("1");
        r.setPathSsid(base + "SSID");
        r.setValSsid("%room%");
        r.setPathChannel(base + "Channel");
        r.setValChannel("0");
        r.setPathBeacon(base + "SSIDAdvertisementEnabled");
        r.setValBeacon("1");
        r.setPathWpaMode(base + "IEEE11iEncryptionModes");
        r.setValWpaMode("AESEncryption");
        r.setPathKey(base + "PreSharedKey.1.KeyPassphrase");
        r.setValKey("%wifi_pwd%");
        r.setPathPsk(base + "PreSharedKey.1.PreSharedKey");
        r.setValPsk("%wifi_pwd%");
        return r;
    }

    private static WlanB fill58(Device device, String base, int instanceNo) {
        WlanB r = new WlanB();
        r.setDeviceId(device.getId());
        r.setInstanceNo(instanceNo);
        r.setAction(instanceExists(device, instanceNo) ? "SET" : "ADD");
        r.setVerified("UNVERIFIED");
        r.setPathEnable(base + "Enable");
        r.setValEnable("1");
        r.setPathSsid(base + "SSID");
        r.setValSsid("%room%_5G");
        r.setPathChannel(base + "Channel");
        r.setValChannel("0");
        r.setPathBeacon(base + "SSIDAdvertisementEnabled");
        r.setValBeacon("1");
        r.setPathWpaMode(base + "IEEE11iEncryptionModes");
        r.setValWpaMode("AESEncryption");
        r.setPathKey(base + "PreSharedKey.1.KeyPassphrase");
        r.setValKey("%wifi_pwd%");
        r.setPathPsk(base + "PreSharedKey.1.PreSharedKey");
        r.setValPsk("%wifi_pwd%");
        return r;
    }

    /**
     * 该范围内是否已有实例。首实例（1 / 5）通常出厂就有，可直接 SET；
     * 后续对接 SoC 信息齐全后，这里改为按实际采集结果判定。
     */
    private static boolean instanceExists(Device device, int instanceNo) {
        return instanceNo == SSID_24G || instanceNo == SSID_58G;
    }

    /** 本次生成结果，供调用方记录日志或回显。 */
    @Data
    public static class WifiResult {
        private int wlanA;
        private int wlanB;

        public int total() {
            return wlanA + wlanB;
        }
    }

    /** 本次 SIP 生成结果。 */
    @Data
    public static class SipResult {
        private int sip;
    }

    /** 本次 WAN 生成结果。 */
    @Data
    public static class WanResult {
        private int wan;
        private int vlan;
    }

    /** 本次 LAN 生成结果。 */
    @Data
    public static class LanResult {
        private int lan;
    }
}

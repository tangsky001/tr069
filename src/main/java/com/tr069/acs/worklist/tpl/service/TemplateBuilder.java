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

/** 按「厂商参数命名规则 + 适配申报能力」拼出下发模板，写进各业务表 */
@Slf4j
@Service
public class TemplateBuilder {

    /** TR-098 下 SSID 实例的挂载位置（目前这个型号只用到 TR-098） */
    private static final String TR098_WLAN = "LANDevice.1.WLANConfiguration.";

    /** 2.4G 频段的 SSID 起始实例号（厂商约定，非推测） */
    public static final int SSID_24G = 1;

    /** 5.8G 频段的 SSID 起始实例号（厂商约定，非推测） */
    public static final int SSID_58G = 5;

    /** 语音业务挂载位置，中兴 P22g-CIac 实测只有 1 个 VoiceService / VoiceProfile 实例 */
    private static final String VOICE_BASE = "Services.VoiceService.1.VoiceProfile.1.";

    /** 一路 SIP 账号的起始 Line 实例号（厂商约定，非推测） */
    public static final int LINE_FIRST = 1;

    /** WAN 业务连接挂载位置，取自真实下发报文（权威参考，非推测） */
    public static final String WAN_BASE = "WANDevice.1.WANConnectionDevice.2.";

    /** AddObject 的对象名末段，末尾不带实例号——实例号由设备分配，事后才知道 */
    public static final String WAN_IP_CONN = "WANIPConnection.";

    /** AddObject 的对象名末段，末尾不带实例号——实例号由设备分配，事后才知道 */
    public static final String WAN_PPP_CONN = "WANPPPConnection.";

    /** IP 协议版本节点名（厂商私有，挂载在连接对象下） */
    public static final String WAN_IP_MODE = "X_CMCC_IPMode";

    /** 参数路径里的实例号占位符，下发引擎 AddObject 后回填真实值 */
    public static final String INSTANCE_PLACEHOLDER = "{i}";

    /** LAN 网口挂载位置 */
    private static final String LAN_BASE = "LANDevice.1.LANEthernetInterfaceConfig.";

    /** 模板库里各业务 WAN 的排位号，用来区分 DHCP / PPPoE / Static / Bridge 各自的模板行 */
    public static final int WAN_SLOT_DHCP = 1;
    public static final int WAN_SLOT_PPPOE = 2;
    public static final int WAN_SLOT_STATIC = 3;
    public static final int WAN_SLOT_BRIDGE = 4;

    /** 桥接连接的 ConnectionType */
    public static final String WAN_BRIDGE_CONN_TYPE = "IP_Bridged";

    /** 默认数据模型根节点：TR-098 */
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

    /** 按设备的 WiFi 能力申报生成 2.4G / 5.8G 下发模板 */
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

    /** 按设备的语音能力申报生成 SIP 下发模板，一路 Line 一条记录 */
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

    /** 申报的能力数量。申报值是人工填的字符串，可能是「1」「4」也可能是「无」 */
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

    /** 根节点前缀，末尾保证带点，各业务拼路径共用 */
    private static String rootOf(Device device) {
        String root = device.getRootNode();
        if (root == null || root.isBlank()) {
            root = DEFAULT_ROOT;
        }
        return root.endsWith(".") ? root : root + ".";
    }

    /** 拼某个 SSID 实例的路径前缀，如 {@code InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.} */
    private static String base(Device device, int instanceNo) {
        return rootOf(device) + TR098_WLAN + instanceNo + ".";
    }

    /** 一路 SIP 账号的全部下发路径与变量值 */
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

    /** 生成 WAN DHCP（IPoE 上网）下发模板，对照真实下发报文逐项落库 */
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

        upsertVlan(device, WAN_SLOT_DHCP, p);
        r.setVlan(1);

        log.info("生成 WAN DHCP 模板: deviceId={}, objectPath={}", device.getId(), wan.getObjectPath());
        return r;
    }

    /** 生成 WAN PPPoE 下发模板 */
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

        upsertVlan(device, WAN_SLOT_PPPOE, p);
        r.setVlan(1);

        log.info("生成 WAN PPPoE 模板: deviceId={}, objectPath={}", device.getId(), wan.getObjectPath());
        return r;
    }

    /** 生成 WAN 静态地址下发模板 */
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
        // 静态与 DHCP 同为 WANIPConnection，绑定端口也一样要能绑：
        // 漏了这条 path，静态连接就永远绑不上 LAN，以前是 Take("没登记端口") 当成不支持
        wan.setPathBindPort(p + "X_CMCC_BindLANPort");
        wan.setValBindPort("");
        deviceWanDao.upsert(wan);
        r.setWan(1);

        upsertVlan(device, WAN_SLOT_STATIC, p);
        r.setVlan(1);

        log.info("生成 WAN 静态地址模板: deviceId={}, objectPath={}", device.getId(), wan.getObjectPath());
        return r;
    }

    /** 生成 WAN 桥接下发模板 */
    public WanResult buildWanBridge(Device device) {
        WanResult r = new WanResult();
        if (device == null || device.getId() == null) {
            return r;
        }
        DeviceWan wan = newWan(device, WAN_SLOT_BRIDGE, "Bridge", WAN_IP_CONN);
        String p = paramPrefix(wan);
        wan.setValConnType(WAN_BRIDGE_CONN_TYPE);
        wan.setPathBindPort(p + "X_CMCC_BindLANPort");
        wan.setValBindPort("");
        deviceWanDao.upsert(wan);
        r.setWan(1);

        upsertVlan(device, WAN_SLOT_BRIDGE, p);
        r.setVlan(1);

        log.info("生成 WAN 桥接模板: deviceId={}, objectPath={}", device.getId(), wan.getObjectPath());
        return r;
    }

    /** 生成 LAN 网口下发模板，几个网口写几行 */
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

    /** 一条 WAN 模板行的公共部分：挂载位置、开关、连接类型、MTU */
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
        wan.setPathMtu(p + "MaxMTUSize");
        wan.setValMtu("");
        // IP 协议版本三种业务共用同一个节点，与 MTU 一样放在公共部分；
        // 值留空＝不改设备的现有地址族，只有模板上明确选了才下发
        wan.setPathIpMode(p + WAN_IP_MODE);
        wan.setValIpMode("");
        return wan;
    }

    /** 参数路径前缀，实例号留占位——AddObject 之后才知道是几号 */
    private static String paramPrefix(DeviceWan wan) {
        return wan.getObjectPath() + INSTANCE_PLACEHOLDER + ".";
    }

    /** VLAN 独立成表，{@code wanInstance} 关联到对应业务 WAN 的排位号 */
    private void upsertVlan(Device device, int wanInstance, String prefix) {
        Vlan vlan = new Vlan();
        vlan.setDeviceId(device.getId());
        vlan.setWanInstance(wanInstance);
        vlan.setAction("ADD");
        vlan.setVerified("UNVERIFIED");
        vlan.setPathVlanId(prefix + "X_CMCC_VLANID");
        vlan.setValVlanId(TemplateVariables.VLAN_ID);
        vlan.setPathVlanMode(prefix + "X_CMCC_VLANMode");
        vlan.setValVlanMode("");
        vlan.setPath8021p(prefix + "X_CMCC_802-1pMark");
        vlan.setVal8021p("");
        vlan.setPathMulticast(prefix + "X_CMCC_MulticastVlan");
        vlan.setValMulticast("");
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

    /** 该范围内是否已有实例。首实例（1 / 5）通常出厂就有，可直接 SET */
    private static boolean instanceExists(Device device, int instanceNo) {
        return instanceNo == SSID_24G || instanceNo == SSID_58G;
    }

    /** 本次生成结果，供调用方记录日志或回显 */
    @Data
    public static class WifiResult {
        private int wlanA;
        private int wlanB;

        public int total() {
            return wlanA + wlanB;
        }
    }

    /** 本次 SIP 生成结果 */
    @Data
    public static class SipResult {
        private int sip;
    }

    /** 本次 WAN 生成结果 */
    @Data
    public static class WanResult {
        private int wan;
        private int vlan;
    }

    /** 本次 LAN 生成结果 */
    @Data
    public static class LanResult {
        private int lan;
    }
}

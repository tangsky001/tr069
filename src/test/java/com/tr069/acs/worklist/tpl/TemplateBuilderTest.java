package com.tr069.acs.worklist.tpl;

import com.tr069.acs.worklist.tpl.dao.DeviceDao;
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
import com.tr069.acs.worklist.tpl.service.TemplateBuilder;
import com.tr069.acs.worklist.tpl.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WiFi 模板生成：验证「申报决定有没有 5G」「实例号按厂商约定」「库里零明文」。
 */
class TemplateBuilderTest {

    static {
        SqliteNativeLoader.ensureLoaded();
    }

    @TempDir(cleanup = CleanupMode.NEVER)
    static Path tmp;

    static DeviceDao deviceDao;
    static WlanADao wlanADao;
    static WlanBDao wlanBDao;
    static SipDao sipDao;
    static DeviceWanDao deviceWanDao;
    static VlanDao vlanDao;
    static DeviceLanDao deviceLanDao;
    static TemplateBuilder builder;
    static SnowflakeIdGenerator snowflake;

    @BeforeAll
    static void setup() throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("builder.pack"));
        DataSource source = ds;
        ScriptUtils.executeSqlScript(source.getConnection(),
                new ClassPathResource("db/schema-tr069tpl.sql"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        snowflake = new SnowflakeIdGenerator(1L);
        deviceDao = new DeviceDao(jdbc, snowflake);
        wlanADao = new WlanADao(jdbc, snowflake);
        wlanBDao = new WlanBDao(jdbc, snowflake);
        sipDao = new SipDao(jdbc, snowflake);
        deviceWanDao = new DeviceWanDao(jdbc, snowflake);
        vlanDao = new VlanDao(jdbc, snowflake);
        deviceLanDao = new DeviceLanDao(jdbc, snowflake);
        builder = new TemplateBuilder(wlanADao, wlanBDao, sipDao, deviceWanDao, vlanDao, deviceLanDao);
    }

    @BeforeEach
    void clearDevices() {
        deviceDao.listAll().forEach(d -> deviceDao.deleteAll());
    }

    private static Device deviceWith(String spec) {
        Device d = new Device();
        d.setIsp("CM");
        d.setModelKey("P22g-CIac_V1.0.00.488");
        d.setSoftwareVersion("V1.0.00.488");
        d.setCapWifiSpec(spec);
        d.setRootNode("InternetGatewayDevice.");
        deviceDao.upsert(d);
        return d;
    }

    @Test
    void only24GWritesWlanAWithInstanceOne() {
        Device d = deviceWith("2.4G");
        TemplateBuilder.WifiResult r = builder.buildWifi(d);

        assertEquals(1, r.total(), "只申报 2.4G 时应只写 wlan_a");
        List<WlanA> rows = wlanADao.findByDeviceId(d.getId());
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).getInstanceNo(), "2.4G 固定用 SSID1");
        assertTrue(rows.get(0).getPathSsid().endsWith("WLANConfiguration.1.SSID"),
                "路径必须带上真实实例号，实际=" + rows.get(0).getPathSsid());
        assertTrue(wlanBDao.findByDeviceId(d.getId()).isEmpty(), "未申报 5G 时 wlan_b 应为空");
    }

    @Test
    void dualBandWritesBothTablesWithSsidOneAndFive() {
        Device d = deviceWith("2.4G+5.8G");
        TemplateBuilder.WifiResult r = builder.buildWifi(d);

        assertEquals(2, r.total(), "申报双频应两张表各一条");

        List<WlanA> a = wlanADao.findByDeviceId(d.getId());
        assertEquals(1, a.get(0).getInstanceNo(), "2.4G 用 SSID1");

        List<WlanB> b = wlanBDao.findByDeviceId(d.getId());
        assertEquals(5, b.get(0).getInstanceNo(), "5.8G 用 SSID5");
        assertTrue(b.get(0).getPathSsid().endsWith("WLANConfiguration.5.SSID"),
                "5G 路径的实例号必须是 5，实际=" + b.get(0).getPathSsid());
    }

    @Test
    void noWifiCapabilityWritesNothing() {
        Device d = deviceWith("无");
        assertEquals(0, builder.buildWifi(d).total(), "申报「无」时不写任何 WiFi 模板");
        assertTrue(wlanADao.findByDeviceId(d.getId()).isEmpty());
    }

    @Test
    void passwordFieldsMustBeVariablesNotPlaintext() {
        Device d = deviceWith("2.4G+5.8G");
        builder.buildWifi(d);

        WlanA a = wlanADao.findByDeviceId(d.getId()).get(0);
        WlanB b = wlanBDao.findByDeviceId(d.getId()).get(0);
        for (String v : List.of(a.getValKey(), a.getValPsk(), b.getValKey(), b.getValPsk())) {
            assertEquals("%wifi_pwd%", v, "密钥字段只能是变量，库里出现明文即 Bug");
        }
        assertFalse(a.getPathKey().isEmpty(), "密钥路径必须拼出来，否则下发时无从设值");
        assertTrue(a.getPathKey().endsWith("PreSharedKey.1.KeyPassphrase"),
                "密钥应落到该型号真实存在的节点上");
    }

    @Test
    void rebuildIsIdempotent() {
        Device d = deviceWith("2.4G+5.8G");
        builder.buildWifi(d);
        long firstId = wlanADao.findByDeviceId(d.getId()).get(0).getId();

        builder.buildWifi(d);

        assertEquals(1, wlanADao.findByDeviceId(d.getId()).size(), "重复生成不应新增行");
        assertEquals(firstId, wlanADao.findByDeviceId(d.getId()).get(0).getId(),
                "重复生成应走 UPDATE，主键不变");
    }

    @Test
    void customRootNodeIsRespected() {
        Device d = deviceWith("2.4G");
        d.setRootNode("Device.");
        deviceDao.upsert(d);

        builder.buildWifi(d);

        Optional<WlanA> row = wlanADao.findOne(d.getId(), TemplateBuilder.SSID_24G);
        assertTrue(row.isPresent());
        assertTrue(row.get().getPathSsid().startsWith("Device.LANDevice.1."),
                "根节点应取自 device.root_node，实际=" + row.get().getPathSsid());
    }

    private static Device deviceWithSip(String count) {
        Device d = new Device();
        d.setIsp("CM");
        d.setModelKey("P22g-CIac_V1.0.00.488");
        d.setSoftwareVersion("V1.0.00.488");
        d.setCapSipCount(count);
        d.setRootNode("InternetGatewayDevice.");
        deviceDao.upsert(d);
        return d;
    }

    @Test
    void sipDeclareOneWritesSingleLine() {
        Device d = deviceWithSip("1");
        TemplateBuilder.SipResult r = builder.buildSip(d);

        assertEquals(1, r.getSip(), "申报 1 路应只写一条");
        List<Sip> rows = sipDao.findByDeviceId(d.getId());
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).getInstanceNo(), "首路固定 Line1");
        assertTrue(rows.get(0).getPathUser().endsWith("VoiceProfile.1.Line.1.SIP.AuthUserName"),
                "账号挂在 Line 的 SIP 子对象下，实际=" + rows.get(0).getPathUser());
    }

    @Test
    void sipDeclareFourWritesFourConsecutiveLines() {
        Device d = deviceWithSip("4");
        builder.buildSip(d);

        List<Sip> rows = sipDao.findByDeviceId(d.getId());
        assertEquals(4, rows.size(), "申报 4 路应写 4 条");
        for (int i = 0; i < 4; i++) {
            assertEquals(i + 1, rows.get(i).getInstanceNo(), "Line 实例号应连续 1..4");
        }
        assertEquals("SET", rows.get(0).getAction(), "Line1 出厂自带，直接设值");
        assertEquals("ADD", rows.get(1).getAction(), "Line2 起需要 AddObject 建出来");
    }

    @Test
    void sipNumberAndPasswordMustBeVariables() {
        Device d = deviceWithSip("1");
        builder.buildSip(d);

        Sip row = sipDao.findByDeviceId(d.getId()).get(0);
        assertEquals("%sip_num%", row.getValUser(), "号码必须留变量");
        assertEquals("%sip_pwd%", row.getValPwd(), "密码必须留变量，库里不许有明文");
        assertEquals("%sip_num%", row.getValUri(),
                "URI 只放号码，不再拼 @%sip_proxy%，实际=" + row.getValUri());
        assertEquals("%sip_proxy%", row.getValProxy());
        assertEquals("%sip_port%", row.getValPort());
        assertEquals("%sip_registrar%", row.getValRegistrar());
        assertEquals("%sip_registrar_port%", row.getValRegistrarPort());
    }

    @Test
    void sipNeverWritesNonExistingVoiceProfileEnable() {
        Device d = deviceWithSip("1");
        builder.buildSip(d);

        Sip row = sipDao.findByDeviceId(d.getId()).get(0);
        // 中兴这个型号压根没有 VoiceProfile.1.Enable，照搬标准写会被设备回无效参数
        assertFalse(row.getPathEnable().contains("VoiceProfile.1.Enable"),
                "开关必须挂在 Line 上，实际=" + row.getPathEnable());
        assertTrue(row.getPathEnable().endsWith("VoiceProfile.1.Line.1.Enable"),
                "实际=" + row.getPathEnable());
    }

    @Test
    void sipProxyIsPerProfileNotPerLine() {
        Device d = deviceWithSip("2");
        builder.buildSip(d);

        for (Sip row : sipDao.findByDeviceId(d.getId())) {
            assertTrue(row.getPathProxy().endsWith("VoiceProfile.1.SIP.ProxyServer"),
                    "代理服务器挂在 VoiceProfile 一级，每条 Line 各写一份是错的，实际=" + row.getPathProxy());
            assertTrue(row.getPathRegistrar().endsWith("VoiceProfile.1.SIP.RegistrarServer"),
                    "注册服务器也挂在 VoiceProfile 一级，实际=" + row.getPathRegistrar());
        }
    }

    @Test
    void sipNoneOrBlankWritesNothing() {
        assertEquals(0, builder.buildSip(deviceWithSip("无")).getSip(), "申报「无」不写");
        assertEquals(0, builder.buildSip(deviceWithSip(null)).getSip(), "未申报不写");
    }

    @Test
    void sipCustomRootNodeIsRespected() {
        Device d = deviceWithSip("1");
        d.setRootNode("Device.");
        deviceDao.upsert(d);

        builder.buildSip(d);

        Sip row = sipDao.findByDeviceId(d.getId()).get(0);
        assertTrue(row.getPathUser().startsWith("Device.Services.VoiceService"),
                "根节点要跟着走，实际=" + row.getPathUser());
    }

    @Test
    void sipRebuildIsIdempotent() {
        Device d = deviceWithSip("3");
        builder.buildSip(d);
        long firstId = sipDao.findOne(d.getId(), 1).get().getId();

        builder.buildSip(d);

        assertEquals(3, sipDao.findByDeviceId(d.getId()).size(), "重复生成不应新增行");
        assertEquals(firstId, sipDao.findOne(d.getId(), 1).get().getId(),
                "重复生成应走 UPDATE，主键不变");
    }

    private static Device deviceWithWan() {
        return deviceWith("无");
    }

    @Test
    void wanDhcpWritesAddObjectTemplatePerRealRequest() {
        Device d = deviceWithWan();
        TemplateBuilder.WanResult r = builder.buildWanDhcp(d);

        assertEquals(1, r.getWan(), "device_wan 一条");
        assertEquals(1, r.getVlan(), "vlan 一条");

        DeviceWan wan = deviceWanDao.findByDeviceId(d.getId()).get(0);
        assertEquals("ADD", wan.getAction(), "业务 WAN 是新建的，走 AddObject");
        // 报文权威参考：AddObject 建在 WANConnectionDevice.2 下，末尾不带实例号
        assertEquals("InternetGatewayDevice.WANDevice.1.WANConnectionDevice.2.WANIPConnection.",
                wan.getObjectPath(), "AddObject 父路径必须与真实报文一致");
        assertTrue(wan.getPathEnable().endsWith("WANIPConnection.{i}.Enable"),
                "实例号设备分配、事先不知道，参数路径必须用 {i} 占位，实际=" + wan.getPathEnable());
        assertEquals("IP_Routed", wan.getValConnType());
        assertEquals("DHCP", wan.getValAddrType());
        assertEquals("1", wan.getValNat());
    }

    @Test
    void wanDhcpVlanUsesRealParamNameNotTheCapabilityTreeOne() {
        Device d = deviceWithWan();
        builder.buildWanDhcp(d);

        Vlan vlan = vlanDao.findByDeviceId(d.getId()).get(0);
        // 能力树里出现的是 X_CMCC_VLANIDMark（TR069 通道上），真实下发认的是 X_CMCC_VLANID
        assertTrue(vlan.getPathVlanId().endsWith("X_CMCC_VLANID"),
                "实际=" + vlan.getPathVlanId());
        assertFalse(vlan.getPathVlanId().contains("VLANIDMark"),
                "写 VLANIDMark 设备不认，实际=" + vlan.getPathVlanId());
        assertEquals("%vlan_id%", vlan.getValVlanId(), "VLAN ID 按局点分配，留变量");
        assertEquals(1, vlan.getWanInstance(), "VLAN 关联到新建的 WAN 实例");
    }

    @Test
    void wanDhcpBindPortStaysEmptyUntilFormatConfirmed() {
        Device d = deviceWithWan();
        builder.buildWanDhcp(d);

        DeviceWan wan = deviceWanDao.findByDeviceId(d.getId()).get(0);
        assertTrue(wan.getPathBindPort().endsWith("X_CMCC_BindLANPort"));
        assertEquals("", wan.getValBindPort(),
                "真实报文下发的是空串（不修改）；绑定端口的取值格式确认前保持一致");
    }

    @Test
    void wanDhcpRebuildIsIdempotent() {
        Device d = deviceWithWan();
        builder.buildWanDhcp(d);
        long firstId = deviceWanDao.findOne(d.getId(), 1).get().getId();

        builder.buildWanDhcp(d);

        assertEquals(1, deviceWanDao.findByDeviceId(d.getId()).size(), "重复生成不应新增行");
        assertEquals(firstId, deviceWanDao.findOne(d.getId(), 1).get().getId(),
                "重复生成应走 UPDATE，主键不变");
    }

    private static Device deviceWithLan(String ports) {
        Device d = new Device();
        d.setIsp("CM");
        d.setModelKey("P22g-CIac_V1.0.00.488");
        d.setSoftwareVersion("V1.0.00.488");
        d.setCapLanPorts(ports);
        d.setRootNode("InternetGatewayDevice.");
        deviceDao.upsert(d);
        return d;
    }

    @Test
    void lanWritesOneRowPerPortWithRealParamNames() {
        Device d = deviceWithLan("4");
        TemplateBuilder.LanResult r = builder.buildLan(d);

        assertEquals(4, r.getLan(), "申报 4 口应写 4 行");
        List<DeviceLan> rows = deviceLanDao.findByDeviceId(d.getId());
        assertEquals(4, rows.size());
        for (int i = 0; i < 4; i++) {
            DeviceLan lan = rows.get(i);
            int no = i + 1;
            assertEquals(no, lan.getInstanceNo());
            // 双工/速率的参数名取自能力树实测（DuplexMode / MaxBitRate），不是照标准猜的
            assertTrue(lan.getPathDuplex().endsWith("LANEthernetInterfaceConfig." + no + ".DuplexMode"),
                    "实际=" + lan.getPathDuplex());
            assertTrue(lan.getPathSpeed().endsWith("LANEthernetInterfaceConfig." + no + ".MaxBitRate"),
                    "实际=" + lan.getPathSpeed());
            assertEquals("SET", lan.getAction(), "网口出厂就有，不需要 AddObject");
        }
    }

    @Test
    void lanNoneOrBlankWritesNothing() {
        assertEquals(0, builder.buildLan(deviceWithLan(null)).getLan(), "未申报不写");
        assertEquals(0, builder.buildLan(deviceWithLan("无")).getLan(), "申报「无」不写");
    }

    @Test
    void wanPppoeUsesPppObjectAndStandardAuthParams() {
        Device d = deviceWithWan();
        builder.buildWanPppoe(d);

        DeviceWan wan = deviceWanDao.findOne(d.getId(), TemplateBuilder.WAN_SLOT_PPPOE).get();
        assertTrue(wan.getObjectPath().endsWith("WANConnectionDevice.2.WANPPPConnection."),
                "PPPoE 建在 WANPPPConnection 下，实际=" + wan.getObjectPath());
        assertTrue(wan.getPathUsername().endsWith("Username"));
        assertTrue(wan.getPathPassword().endsWith("Password"));
        assertEquals("%pppoe_user%", wan.getValUsername(), "宽带账号必须留变量");
        assertEquals("%pppoe_pwd%", wan.getValPassword(), "宽带密码必须留变量，库里不许明文");
        // 这台设备没有 PPPoE 连接实例，鉴权参数名是按 TR-098 标准取的，必须留 UNVERIFIED
        assertEquals("UNVERIFIED", wan.getVerified(),
                "推断出来的参数不能假装已验证");
    }

    @Test
    void wanStaticUsesRealParamNamesAndVariables() {
        Device d = deviceWithWan();
        builder.buildWanStatic(d);

        DeviceWan wan = deviceWanDao.findOne(d.getId(), TemplateBuilder.WAN_SLOT_STATIC).get();
        assertEquals("Static", wan.getValAddrType());
        assertTrue(wan.getPathIp().endsWith("ExternalIPAddress"), "实际=" + wan.getPathIp());
        assertTrue(wan.getPathMask().endsWith("SubnetMask"), "实际=" + wan.getPathMask());
        assertTrue(wan.getPathGw().endsWith("DefaultGateway"), "实际=" + wan.getPathGw());
        assertTrue(wan.getPathDns().endsWith("DNSServers"), "实际=" + wan.getPathDns());
        assertEquals("%wan_ip%", wan.getValIp());
        assertEquals("%wan_mask%", wan.getValMask());
        assertEquals("%wan_gw%", wan.getValGw());
        assertEquals("%wan_dns%", wan.getValDns());
    }

    @Test
    void threeWanTypesDoNotOverwriteEachOther() {
        Device d = deviceWithWan();
        builder.buildWanDhcp(d);
        builder.buildWanPppoe(d);
        builder.buildWanStatic(d);

        assertEquals(3, deviceWanDao.findByDeviceId(d.getId()).size(),
                "三种宽带业务各占一行，不能互相覆盖");
        assertEquals(3, vlanDao.findByDeviceId(d.getId()).size(), "VLAN 各关联自己的 WAN");
        assertEquals("IPoE", deviceWanDao.findOne(d.getId(), TemplateBuilder.WAN_SLOT_DHCP).get().getConnType());
        assertEquals("PPPoE", deviceWanDao.findOne(d.getId(), TemplateBuilder.WAN_SLOT_PPPOE).get().getConnType());
        assertEquals("Static", deviceWanDao.findOne(d.getId(), TemplateBuilder.WAN_SLOT_STATIC).get().getConnType());
    }
}

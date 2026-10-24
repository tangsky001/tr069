package com.tr069.acs.worklist.service;

import com.tr069.acs.acs.service.CwmpRpcService;
import com.tr069.acs.acs.service.DeviceCapabilityService;
import com.tr069.acs.common.log.AcsCoreLog;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import com.tr069.acs.worklist.service.ModelAdaptationService.AdaptRequest;
import com.tr069.acs.worklist.service.ModelAdaptationService.Detection;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 机型适配的能力解析与申报比对：从参数树解析实测能力是否准确，比对规则是否合理。
 */
class ModelAdaptationServiceTest {

    private final CpeParameterMapper parameterMapper = Mockito.mock(CpeParameterMapper.class);
    private final CpeDeviceMapper deviceMapper = Mockito.mock(CpeDeviceMapper.class);
    private final DeviceCapabilityService capabilityService =
            Mockito.mock(DeviceCapabilityService.class);
    private final CwmpRpcService cwmpRpcService = Mockito.mock(CwmpRpcService.class);
    private final ModelMappingService modelMappingService =
            Mockito.mock(ModelMappingService.class);
    private final ModelTreeStore modelTreeStore = Mockito.mock(ModelTreeStore.class);
    private final ModelAdaptationProfileStore profileStore =
            Mockito.mock(ModelAdaptationProfileStore.class);
    private final AcsCoreLog coreLog = new AcsCoreLog();

    private ModelAdaptationService service() {
        return new ModelAdaptationService(
                deviceMapper, parameterMapper, capabilityService, cwmpRpcService,
                modelMappingService, modelTreeStore, coreLog, profileStore);
    }

    private static CpeParameter param(String path, String value) {
        return param(path, value, false);
    }

    /** writable=true 表示该参数可写，试探下发只会挑这类参数。 */
    private static CpeParameter param(String path, String value, boolean writable) {
        CpeParameter row = new CpeParameter();
        row.setSn("TEST");
        row.setParamPath(path);
        row.setNodeType(CpeParameter.TYPE_PARAM);
        row.setParamValue(value);
        row.setWritable(writable ? 1 : 0);
        return row;
    }

    private static AdaptRequest request(String isp, String lanPorts, String lanSpeed,
                                        String sipCount, String wifiSpec) {
        AdaptRequest req = new AdaptRequest();
        req.setIsp(isp);
        req.setLanPorts(lanPorts);
        req.setLanSpeed(lanSpeed);
        req.setSipCount(sipCount);
        req.setWifiSpec(wifiSpec);
        return req;
    }

    /** TR-098 4 网口千兆 + 双频 WIFI + 2 路 SIP 的典型参数树（混入厂商私有节点）。 */
    private List<CpeParameter> tr098Tree() {
        String r = "InternetGatewayDevice.";
        return List.of(
                param(r + "DeviceInfo.ProductClass", "P22g-CLac"),
                param(r + "LANDevice.1.LANEthernetInterfaceConfig.1.MaxBitRate", "1000"),
                param(r + "LANDevice.1.LANEthernetInterfaceConfig.2.MaxBitRate", "1000"),
                param(r + "LANDevice.1.LANEthernetInterfaceConfig.3.MaxBitRate", "100"),
                param(r + "LANDevice.1.LANEthernetInterfaceConfig.4.MaxBitRate", "1000"),
                param(r + "LANDevice.1.WLANConfiguration.1.SSID", "CMCC-M6rq"),
                param(r + "LANDevice.1.WLANConfiguration.1.X_CMCC_FrequencyBand", "2.4GHz"),
                param(r + "LANDevice.1.WLANConfiguration.5.SSID", "CMCC-M6rq-5G"),
                param(r + "LANDevice.1.WLANConfiguration.5.X_CMCC_FrequencyBand", "5GHz"),
                param(r + "VoiceService.1.VoiceProfile.1.SIP.AuthUserName", "8601"),
                param(r + "VoiceService.1.VoiceProfile.2.SIP.AuthUserName", "8602"));
    }

    @Test
    void detectParsesTr098Tree() {
        Detection d = service().detect(tr098Tree());
        assertThat(d.getLanPortCount()).isEqualTo(4);
        // 实测最高千兆
        assertThat(d.getLanSpeedMbps()).isEqualTo(1000.0);
        assertThat(d.getSipCount()).isEqualTo(2);
        assertThat(d.getWifiBands()).containsExactlyInAnyOrder("2.4G", "5.8G");
    }

    @Test
    void detectParsesVendorSipAndTr181Tree() {
        String r = "Device.";
        Detection d = service().detect(List.of(
                param(r + "Ethernet.Interface.1.MaxBitRate", "1000000"), // kbps 单位兜底
                param(r + "Ethernet.Interface.2.MaxBitRate", "1000000"),
                param(r + "WiFi.Radio.1.Channel", "6"),
                param(r + "WiFi.SSID.1.SSID", "ChinaNet-24G"),
                param(r + "WiFi.SSID.2.SSID", "ChinaNet-5G"),
                param(r + "X_CMCC_SipObj.1.RegStatus", "ok"),
                param(r + "X_CMCC_SipObj.2.RegStatus", "ok")));
        assertThat(d.getLanPortCount()).isEqualTo(2);
        assertThat(d.getLanSpeedMbps()).isEqualTo(1000.0);
        assertThat(d.getSipCount()).isEqualTo(2);
        // 频段叶子缺失时双射频兜底为双频
        assertThat(d.getWifiBands()).containsExactlyInAnyOrder("2.4G", "5.8G");
    }

    /**
     * MSG2200C 这类移动定制固件：不实现 OperatingFrequencyBand，2.4G 的 SSID
     * 也不带频段字样（CMCC-cTDt），只有 5G 那个带 -5G 后缀。以前只认频段字段和 SSID，
     * 结果只识别出 5.8G，申报双频就报「未检测到 2.4G WIFI」。
     * PossibleChannels 是标准参数，2.4G 只到 13、5G 从 36 起，据此能判准。
     */
    @Test
    void 无频段参数时按信道列表识别双频() {
        String r = "InternetGatewayDevice.LANDevice.1.WLANConfiguration.";
        Detection d = service().detect(List.of(
                param(r + "1.PossibleChannels", "1-13"),
                param(r + "1.Standard", "bgn"),
                param(r + "1.SSID", "CMCC-cTDt"),
                param(r + "5.PossibleChannels", "36 40 44 48 52 56 60 64 149 153 157 161"),
                param(r + "5.Standard", "anac"),
                param(r + "5.SSID", "CMCC-cTDt-5G")));

        assertThat(d.getWifiBands()).containsExactlyInAnyOrder("2.4G", "5.8G");
        assertThat(service().compare(d, request("CMCC", "无", "自动", "无", "2.4G+5.8G")))
                .as("申报双频不该被判不满足").isEmpty();
    }

    /**
     * 用 MSG2200C 实际导出的档案跑一遍。这台设备不实现 OperatingFrequencyBand，
     * 2.4G 的 SSID 也没有「2.4」字样（CMCC-cTDt），只有 PossibleChannels=1-13
     * 与 Standard=bgn 能证明它是 2.4G 射频。
     */
    @Test
    void 从真实型号档案识别双频() throws Exception {
        Path xml = Path.of("configuration/models/standard/standard/msg2200c-up-4e4v_p370_431.xml");
        assumeTrue(Files.exists(xml), "型号档案不在，跳过");
        List<CpeParameter> params = new ArrayList<>();
        Matcher m = Pattern.compile("<Param [^>]*path=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"")
                .matcher(Files.readString(xml));
        while (m.find()) {
            CpeParameter row = new CpeParameter();
            row.setParamPath(m.group(1));
            row.setParamValue(m.group(2));
            params.add(row);
        }

        Detection d = service().detect(params);

        assertThat(d.getWifiBands()).containsExactlyInAnyOrder("2.4G", "5.8G");
    }

    /**
     * 只给 5G 那个 SSID 标频段、2.4G 那个什么都不标（CMCC-xxxx-5G 配 CMCC-xxxx），
     * 是绝大多数厂家的做法，不能因为「2.4G 没标注」就认定设备没有 2.4G。
     */
    @Test
    void 只标5G不标2点4G时按双频认定() {
        Detection d = service().detect(List.of(
                param("InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.SSID", "CMCC-ABCD"),
                param("InternetGatewayDevice.LANDevice.1.WLANConfiguration.2.SSID", "CMCC-ABCD-5G"),
                param("InternetGatewayDevice.LANDevice.1.WLANConfigurationNumberOfEntries", "2")));

        assertThat(d.getWifiBands()).containsExactlyInAnyOrder("2.4G", "5.8G");
        assertThat(service().compare(d, request("CMCC", "无", "自动", "无", "2.4G+5.8G")))
                .as("申报双频不该被判不满足").isEmpty();
    }

    /** 反过来不成立：只有一个射频且它标了 5G，就是纯 5G 设备，不该补 2.4G。 */
    @Test
    void 单射频标5G时不补2点4G() {
        Detection d = service().detect(List.of(
                param("InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.SSID", "CMCC-5G"),
                param("InternetGatewayDevice.LANDevice.1.WLANConfigurationNumberOfEntries", "1")));

        assertThat(d.getWifiBands()).containsExactly("5.8G");
    }

    @Test
    void detectReportsNothingOnEmptyTree() {
        Detection d = service().detect(List.of());
        assertThat(d.getLanPortCount()).isZero();
        assertThat(d.getSipCount()).isZero();
        assertThat(d.getLanSpeedMbps()).isNull();
        assertThat(d.getWifiBands()).isEmpty();
    }

    /**
     * MSG2100E 这类固件：GetParameterNames 不可用，只靠已知路径读回参数，
     * 既没有 OperatingFrequencyBand，SSID 也是 ChinaNet-xxxx 不带频段。
     * 单射频若不按 2.4G 认定，适配页申报 2.4G 永远报「未检测到」。
     */
    @Test
    void 单射频无频段参数时按2点4G认定() {
        Detection d = service().detect(List.of(
                param("InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.Enable", "1"),
                param("InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.SSID", "ChinaNet-ABCD"),
                param("InternetGatewayDevice.LANDevice.1.WLANConfigurationNumberOfEntries", "1")));
        assertThat(d.getWifiBands()).containsExactly("2.4G");
        assertThat(d.getRadioCount()).isEqualTo(1);
        assertThat(service().compare(d, request("CT", "无", "自动", "无", "2.4G"))).isEmpty();
    }

    /**
     * 不建 LANEthernetInterfaceConfig、只建一路 Line 的固件，靠数实例会把 4 口 8 路
     * 误判成 0 口 1 路，申报值怎么填都比不过。设备自报的 NumberOfEntries 才是准的。
     */
    @Test
    void 按设备自报条目数识别网口与语音() {
        String r = "InternetGatewayDevice.";
        Detection d = service().detect(List.of(
                param(r + "LANDevice.1.LANHostConfigManagement.IPInterface.1.IPInterfaceIPAddress", "192.168.1.1"),
                param(r + "LANDevice.1.LANEthernetInterfaceNumberOfEntries", "4"),
                param(r + "LANDevice.1.LANEthernetInterfaceConfig.1.MaxBitRate", "Auto"),
                param(r + "LANDevice.1.WLANConfiguration.1.Enable", "1"),
                param(r + "LANDevice.1.WLANConfiguration.1.SSID", "ChinaNet-ABCD"),
                param(r + "LANDevice.1.WLANConfiguration.1.OperatingFrequencyBand", "2.4GHz"),
                param(r + "LANDevice.1.WLANConfigurationNumberOfEntries", "1"),
                param(r + "Services.VoiceService.1.VoiceProfileNumberOfEntries", "1"),
                param(r + "Services.VoiceService.1.VoiceProfile.1.LineNumberOfEntries", "8"),
                param(r + "Services.VoiceService.1.VoiceProfile.1.Line.1.DirectoryNumber", "8601")));
        assertThat(d.getLanPortCount()).isEqualTo(4);
        assertThat(d.getSipCount()).isEqualTo(8);
        assertThat(d.getWifiBands()).containsExactly("2.4G");
        // 申报 4 口 / 自动速率 / 8 路 SIP / 2.4G 应当全部通过
        assertThat(service().compare(d, request("CT", "4", "自动", "8", "2.4G"))).isEmpty();
    }

    /** 8 路语音口的机器常常只建一条 Line，容量挂在物理口上，同样要认。 */
    @Test
    void 按语音物理口条目数识别路数() {
        Detection d = service().detect(List.of(
                param("InternetGatewayDevice.Services.VoiceService.1.PhyInterfaceNumberOfEntries", "8"),
                param("InternetGatewayDevice.Services.VoiceService.1.VoiceProfile.1.Line.1.DirectoryNumber", "8601")));
        assertThat(d.getSipCount()).isEqualTo(8);
        assertThat(service().compare(d, request("CT", "无", "自动", "8", "无"))).isEmpty();
    }

    /**
     * 只暴露一条 Line、容量参数（Line / PhyInterface / VoiceProfile 条目数）全不可读的固件，
     * 实测 1 路只是下限。拿它否定「实物 8 个语音口」的申报，这类型号永远适不上。
     */
    @Test
    void 语音容量不可读时不否定申报路数() {
        ModelAdaptationService svc = service();
        Detection d = svc.detect(List.of(
                param("InternetGatewayDevice.Services.VoiceService.1.VoiceProfile.1.Line.1.DirectoryNumber", "8601")));
        assertThat(d.getSipCount()).as("能看到的就一路").isEqualTo(1);
        assertThat(d.isSipCapacityKnown()).as("没有任何容量证据").isFalse();
        assertThat(svc.compare(d, request("CT", "无", "自动", "8", "无"))).isEmpty();
        assertThat(svc.notes(d, request("CT", "无", "自动", "8", "无")))
                .anyMatch(n -> n.contains("未上报语音容量"));
    }

    @Test
    void compareAcceptsMatchingRequest() {
        ModelAdaptationService svc = service();
        Detection d = svc.detect(tr098Tree());
        List<String> reasons = svc.compare(d,
                request("CMCC", "4-8", "1000M", "2", "2.4G+5.8G"));
        assertThat(reasons).isEmpty();
    }

    @Test
    void compareRejectsShortfalls() {
        ModelAdaptationService svc = service();
        Detection d = svc.detect(tr098Tree());
        // 申报 2500M、4 路 SIP——都不满足（4-8 口对实测 4 口算满足，取下限）
        List<String> reasons = svc.compare(d,
                request("CMCC", "4-8", "2500M", "4", "2.4G+5.8G"));
        assertThat(reasons).hasSize(2);

        // 申报单频 2.4G，设备双频也能满足（能力向下兼容）
        assertThat(svc.compare(d, request("CMCC", "2", "自动", "无", "2.4G"))).isEmpty();
        // 只报 5.8G 单频同样满足
        assertThat(svc.compare(d, request("CMCC", "无", "无", "无", "2.4G+5.8G"))).isEmpty();
    }

    @Test
    void compareFlagsMissingSpeedEvidence() {
        ModelAdaptationService svc = service();
        Detection d = svc.detect(List.of(
                param("InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.1.Enable", "1")));
        List<String> reasons = svc.compare(d, request("CMCC", "1", "1000M", "无", "无"));
        assertThat(reasons).anyMatch(r -> r.contains("未采集到网口速率参数"));
    }

    @Test
    void valueTypeFallsBackToValue() {
        assertThat(ModelAdaptationService.valueTypeOf("string", "abc")).isEqualTo("string");
        // 采集时带 xsd: 前缀的要剥掉，保持与下发渲染器一致的写法
        assertThat(ModelAdaptationService.valueTypeOf("xsd:unsignedInt", "41"))
                .isEqualTo("unsignedInt");
        assertThat(ModelAdaptationService.valueTypeOf(null, "true")).isEqualTo("boolean");
        assertThat(ModelAdaptationService.valueTypeOf(null, "41")).isEqualTo("unsignedInt");
        assertThat(ModelAdaptationService.valueTypeOf(null, "abc")).isEqualTo("string");
    }

    @Test
    void probeSkipsCapabilitiesNotDeclared() {
        // 全部申报为「无」，一个试探都不该发
        List<ModelAdaptationService.Probe> probes =
                service().probeCapabilities("TEST", tr098Tree(),
                        request("CMCC", "无", "无", "无", "无"));
        assertThat(probes).isEmpty();
        Mockito.verifyNoInteractions(cwmpRpcService);
    }

    @Test
    void probeReportsWhenNoWritableNode() {
        // 树里没有可写节点：每组给出「无可写参数」，且不应真的下发。
        // 三组：网口 / SIP / WIFI。网口速率那组已去掉——MaxBitRate 的值域各厂商
        // 各行其是（bps / Mbps / Auto /「1000M」），写回原值常被判 9003，不是可靠证据
        List<ModelAdaptationService.Probe> probes =
                service().probeCapabilities("TEST", tr098Tree(),
                        request("CMCC", "4", "1000M", "2", "2.4G+5.8G"));
        assertThat(probes).hasSize(3);
        assertThat(probes).allSatisfy(p -> {
            assertThat(p.getStatus()).isEqualTo("无可写参数");
            assertThat(p.isFailed()).isFalse();
        });
        Mockito.verifyNoInteractions(cwmpRpcService);
    }

    @Test
    void probeSendsCurrentValueBack() {
        List<CpeParameter> tree = List.of(
                param("InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.1.Enable",
                        "true", true),
                param("InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.SSID",
                        "CMCC-test", true));
        // 试探等待超时归零 -> 立即超时判失败，不依赖真实设备
        List<ModelAdaptationService.Probe> probes =
                service().probeCapabilities("TEST", tree,
                        request("CMCC", "1", "无", "无", "2.4G"));
        assertThat(probes).hasSize(2);
        assertThat(probes).allSatisfy(p -> assertThat(p.isFailed()).isTrue());
        // 下发的是参数当前值，配置不会被改
        Mockito.verify(cwmpRpcService, Mockito.times(2))
                .setParameterValues(Mockito.eq("TEST"), Mockito.any(), Mockito.any());
    }

    @Test
    void parseHelpersHandleOptions() {
        assertThat(ModelAdaptationService.parseCount("无")).isZero();
        assertThat(ModelAdaptationService.parseCount("3")).isEqualTo(3);
        assertThat(ModelAdaptationService.parseCount("4-8")).isEqualTo(4);
        assertThat(ModelAdaptationService.parseSpeed("自动")).isZero();
        assertThat(ModelAdaptationService.parseSpeed("1000M")).isEqualTo(1000.0);
    }

    @Test
    void testFailsWhenCollectFails() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        when(parameterMapper.selectList(any())).thenReturn(List.of());
        when(capabilityService.collect(any(), Mockito.anyBoolean()))
                .thenThrow(new IllegalStateException("离线"));
        ModelAdaptationService svc = service();
        java.util.Map<String, Object> result =
                svc.test("NOPE", request("CMCC", "1", "无", "无", "无"));
        assertThat(result.get("supported")).isEqualTo(false);
        assertThat(result.get("collectStatus")).isEqualTo("fail");
    }
}

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void detectReportsNothingOnEmptyTree() {
        Detection d = service().detect(List.of());
        assertThat(d.getLanPortCount()).isZero();
        assertThat(d.getSipCount()).isZero();
        assertThat(d.getLanSpeedMbps()).isNull();
        assertThat(d.getWifiBands()).isEmpty();
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
        // 树里没有可写节点：每组给出「无可写参数」，且不应真的下发
        List<ModelAdaptationService.Probe> probes =
                service().probeCapabilities("TEST", tr098Tree(),
                        request("CMCC", "4", "1000M", "2", "2.4G+5.8G"));
        assertThat(probes).hasSize(4);
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

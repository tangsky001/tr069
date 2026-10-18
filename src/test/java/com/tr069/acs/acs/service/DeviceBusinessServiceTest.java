package com.tr069.acs.acs.service;

import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 业务信息分区解析：路径形态到 WAN/WLAN/SIP 业务实例的归类是否正确。
 */
class DeviceBusinessServiceTest {

    private final CpeParameterMapper parameterMapper = Mockito.mock(CpeParameterMapper.class);
    private final CpeDeviceMapper deviceMapper = Mockito.mock(CpeDeviceMapper.class);
    private final DeviceCapabilityService capabilityService =
            Mockito.mock(DeviceCapabilityService.class);

    private DeviceBusinessService serviceWithRoot(String root) {
        CpeDevice device = new CpeDevice();
        device.setSn("TEST");
        device.setRootNode(root);
        when(deviceMapper.selectOne(any())).thenReturn(device);
        return new DeviceBusinessService(parameterMapper, deviceMapper, capabilityService);
    }

    private static CpeParameter param(String path, String value) {
        CpeParameter row = new CpeParameter();
        row.setSn("TEST");
        row.setParamPath(path);
        row.setNodeType(CpeParameter.TYPE_PARAM);
        // 库里有父路径；业务服务应优先用它，为空时才自行推导
        row.setParentPath(DeviceCapabilityService.parentOf(path));
        row.setParamValue(value);
        return row;
    }

    @Test
    void summaryGroupsInstancesBySection() {
        String root = "InternetGatewayDevice.";
        when(parameterMapper.selectList(any())).thenReturn(List.of(
                param(root + "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.Username",
                        "test@163.gd"),
                param(root + "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.ConnectionStatus",
                        "Connected"),
                param(root + "LANDevice.1.WLANConfiguration.1.SSID", "ChinaNet-abc"),
                param(root + "LANDevice.1.WLANConfiguration.1.Channel", "6"),
                param(root + "LANDevice.1.WLANConfiguration.2.SSID", "ChinaNet-5G"),
                // 分区之外的参数不应出现在任何分区里
                param(root + "DeviceInfo.SoftwareVersion", "V3.0")
        ));
        DeviceBusinessService service = serviceWithRoot(root);

        Map<String, Object> data = service.summary("TEST");

        assertThat(data.get("rootNode")).isEqualTo(root);
        assertThat((List<?>) data.get("wan")).hasSize(1);
        Map<?, ?> wan = (Map<?, ?>) ((List<?>) data.get("wan")).get(0);
        Map<?, ?> wanParams = (Map<?, ?>) wan.get("params");
        assertThat(wanParams.get("Username")).isEqualTo("test@163.gd");
        assertThat(wanParams.get("ConnectionStatus")).isEqualTo("Connected");

        assertThat((List<?>) data.get("wifi")).hasSize(2);
        Map<?, ?> wifi1 = (Map<?, ?>) ((List<?>) data.get("wifi")).get(0);
        assertThat(wifi1.get("path")).isEqualTo(root + "LANDevice.1.WLANConfiguration.1.");
        assertThat(((Map<?, ?>) wifi1.get("params")).get("SSID")).isEqualTo("ChinaNet-abc");

        assertThat((List<?>) data.get("sip")).isEmpty();
    }

    @Test
    void summarySupportsTr181RootAndSipSection() {
        String root = "Device.";
        when(parameterMapper.selectList(any())).thenReturn(List.of(
                param(root + "Services.VoiceService.1.VoiceProfile.1.SIP.1.UserName", "057188887788"),
                param(root + "Services.VoiceService.1.VoiceProfile.1.SIP.1.RegistrarServer",
                        "sip.ctcims.cn"),
                // 不带实例号的 SIP 对象是汇总对象，不是真正的线路，应被过滤掉
                param(root + "Services.VoiceService.2.VoiceProfile.1.SIP.UserName", "01012345678")
        ));
        DeviceBusinessService service = serviceWithRoot(root);

        Map<String, Object> data = service.summary("TEST");

        List<?> sip = (List<?>) data.get("sip");
        assertThat(sip).hasSize(1);
        Map<?, ?> first = (Map<?, ?>) sip.get(0);
        assertThat(((Map<?, ?>) first.get("params")).get("UserName")).isEqualTo("057188887788");
        // TR-181 的 WLAN 在 Device.WiFi.SSID.{i} 下，前缀过滤要覆盖到
        assertThat((List<?>) data.get("wifi")).isEmpty();
    }

    @Test
    void summaryStripsVendorLeafPrefix() {
        String root = "InternetGatewayDevice.";
        // 库按 param_path 升序返回，标准节点排在 X_CMCC_ 私有节点之前
        when(parameterMapper.selectList(any())).thenReturn(List.of(
                param(root + "WANDevice.1.WANConnectionDevice.1.WANIPConnection.2.ConnectionStatus",
                        "Connected"),
                param(root + "WANDevice.1.WANConnectionDevice.1.WANIPConnection.2.X_CMCC_VLANIDMark",
                        "41"),
                param(root + "WANDevice.1.WANConnectionDevice.1.WANIPConnection.2.X_CMCC_VLANMode",
                        "2")
        ));
        DeviceBusinessService service = serviceWithRoot(root);

        Map<String, Object> data = service.summary("TEST");

        Map<?, ?> wan = (Map<?, ?>) ((List<?>) data.get("wan")).get(0);
        Map<?, ?> wanParams = (Map<?, ?>) wan.get("params");
        // 前端按标准名取值，厂商前缀必须剥掉
        assertThat(wanParams.get("VLANIDMark")).isEqualTo("41");
        assertThat(wanParams.get("VLANMode")).isEqualTo("2");
        // 前缀剥离不应误伤普通叶子
        assertThat(wanParams.get("ConnectionStatus")).isEqualTo("Connected");
    }

    @Test
    void normalizeLeafPrefersStandardNameWhenBothReported() {
        assertThat(DeviceBusinessService.normalizeLeaf("X_CMCC_VLANIDMark")).isEqualTo("VLANIDMark");
        assertThat(DeviceBusinessService.normalizeLeaf("X_CT-COM_VLANIDMark")).isEqualTo("VLANIDMark");
        assertThat(DeviceBusinessService.normalizeLeaf("VLANIDMark")).isEqualTo("VLANIDMark");
        assertThat(DeviceBusinessService.normalizeLeaf("MACAddress")).isEqualTo("MACAddress");
    }

    @Test
    void summaryGroupsLanPortsAndMarksConnected() {
        String root = "InternetGatewayDevice.";
        when(parameterMapper.selectList(any())).thenReturn(List.of(
                param(root + "LANDevice.1.LANEthernetInterfaceConfig.1.MACAddress",
                        "AA:BB:CC:DD:EE:01"),
                param(root + "LANDevice.1.LANEthernetInterfaceConfig.1.X_CT-COM_UpLineStatus", "Up"),
                param(root + "LANDevice.1.LANEthernetInterfaceConfig.2.MACAddress",
                        "AA:BB:CC:DD:EE:02"),
                param(root + "LANDevice.1.LANEthernetInterfaceConfig.2.Status", "Disabled")
        ));
        DeviceBusinessService service = serviceWithRoot(root);

        Map<String, Object> data = service.summary("TEST");

        List<?> lan = (List<?>) data.get("lan");
        assertThat(lan).hasSize(2);
        Map<?, ?> lan1 = (Map<?, ?>) lan.get(0);
        assertThat(lan1.get("instance")).isEqualTo("1");
        assertThat(lan1.get("connected")).isEqualTo(true);
        assertThat(lan1.get("mac")).isEqualTo("AA:BB:CC:DD:EE:01");

        Map<?, ?> lan2 = (Map<?, ?>) lan.get(1);
        assertThat(lan2.get("instance")).isEqualTo("2");
        assertThat(lan2.get("connected")).isEqualTo(false);
        assertThat(lan2.get("mac")).isEqualTo("AA:BB:CC:DD:EE:02");
    }

    @Test
    void lanStatusPathsCoversEveryPortStatusLeaves() {
        String root = "InternetGatewayDevice.";
        when(parameterMapper.selectList(any())).thenReturn(List.of(
                param(root + "LANDevice.1.LANEthernetInterfaceConfig.1.MACAddress",
                        "AA:BB:CC:DD:EE:01"),
                param(root + "LANDevice.1.LANEthernetInterfaceConfig.2.MACAddress",
                        "AA:BB:CC:DD:EE:02"),
                // 网口之外的参数不应混进刷新清单
                param(root + "LANDevice.1.WLANConfiguration.1.SSID", "ChinaNet-abc")
        ));
        DeviceBusinessService service = serviceWithRoot(root);

        List<String> paths = service.lanStatusPaths("TEST");

        assertThat(paths).contains(
                root + "LANDevice.1.LANEthernetInterfaceConfig.1.Status",
                root + "LANDevice.1.LANEthernetInterfaceConfig.1.X_CT-COM-UpLineStatus",
                root + "LANDevice.1.LANEthernetInterfaceConfig.2.Status");
        // 2 个网口 × 9 个状态叶子
        assertThat(paths).hasSize(18);
        assertThat(paths).noneMatch(p -> p.contains("WLANConfiguration"));
    }

    @Test
    void lanStatusPathsEmptyWhenNoLanParams() {
        when(parameterMapper.selectList(any())).thenReturn(List.of(
                param("InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.SSID", "ChinaNet-abc")
        ));
        DeviceBusinessService service = serviceWithRoot("InternetGatewayDevice.");

        assertThat(service.lanStatusPaths("TEST")).isEmpty();
    }

    @Test
    void collectWithoutTreeReportsEmpty() {
        when(parameterMapper.selectList(any())).thenReturn(List.of());
        DeviceBusinessService service = serviceWithRoot("InternetGatewayDevice.");

        Map<String, Object> data = service.collect("TEST");

        assertThat(data.get("collectStatus")).isEqualTo("empty");
        assertThat(String.valueOf(data.get("message"))).isNotBlank();
    }
}

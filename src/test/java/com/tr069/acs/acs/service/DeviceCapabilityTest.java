package com.tr069.acs.acs.service;

import com.tr069.acs.cwmp.RpcMethod;
import com.tr069.acs.cwmp.SoapEnvelopeBuilder;
import com.tr069.acs.cwmp.SoapParser;
import com.tr069.acs.cwmp.model.SoapMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备能力采集的报文与解析校验。
 *
 * <p>采集链路本身要设备在线才能跑通，这里只验证不依赖外部资源的部分：
 * 下发的报文形态、设备响应的解析结果，以及能力树层级推导。</p>
 */
class DeviceCapabilityTest {

    private static final String NAMESPACES =
            "xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "xmlns:SOAP-ENC=\"http://schemas.xmlsoap.org/soap/encoding/\" "
                    + "xmlns:cwmp=\"urn:dslforum-org:cwmp-1-0\" "
                    + "xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" "
                    + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"";

    /**
     * 递归采集：NextLevel=false 才能一次性拿全量路径。
     */
    @Test
    void buildGetParameterNamesRecursive() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ParameterPath", "InternetGatewayDevice.");
        args.put("NextLevel", "false");

        String soap = SoapEnvelopeBuilder.build(RpcMethod.GET_PARAMETER_NAMES, args, "1", "cwmp-1-0");

        assertThat(soap).contains("<cwmp:GetParameterNames>");
        assertThat(soap).contains("<cwmp:ParameterPath>InternetGatewayDevice.</cwmp:ParameterPath>");
        assertThat(soap).contains("<cwmp:NextLevel>false</cwmp:NextLevel>");
    }

    /**
     * 逐层采集：命中对象后要能接着往下问。
     */
    @Test
    void buildGetParameterNamesNextLevel() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ParameterPath", "InternetGatewayDevice.DeviceInfo.");
        args.put("NextLevel", "true");

        String soap = SoapEnvelopeBuilder.build(RpcMethod.GET_PARAMETER_NAMES, args, "1", "cwmp-1-0");

        assertThat(soap).contains("<cwmp:NextLevel>true</cwmp:NextLevel>");
    }

    /**
     * 批量读值：一次请求携带多个参数路径，数组长度要与实际个数一致。
     */
    @Test
    void buildGetParameterValuesBatch() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ParameterNames", List.of("InternetGatewayDevice.DeviceInfo.SoftwareVersion",
                "InternetGatewayDevice.DeviceInfo.HardwareVersion"));

        String soap = SoapEnvelopeBuilder.build(RpcMethod.GET_PARAMETER_VALUES, args, "1", "cwmp-1-0");

        assertThat(soap).contains("<cwmp:GetParameterValues>");
        assertThat(soap).contains("SOAP-ENC:arrayType=\"xsd:string[2]\"");
        assertThat(soap).contains("InternetGatewayDevice.DeviceInfo.SoftwareVersion");
    }

    /**
     * GetParameterNamesResponse：路径以句点结尾的是对象，Writable 带回读写权限。
     */
    @Test
    void parseGetParameterNamesResponse() {
        String xml = "<SOAP-ENV:Envelope " + NAMESPACES + ">"
                + "<SOAP-ENV:Header><cwmp:ID SOAP-ENV:mustUnderstand=\"1\">1</cwmp:ID></SOAP-ENV:Header>"
                + "<SOAP-ENV:Body><cwmp:GetParameterNamesResponse>"
                + "<ParameterList SOAP-ENC:arrayType=\"cwmp:ParameterInfoStruct[3]\">"
                + "<ParameterInfoStruct><Name>InternetGatewayDevice.DeviceInfo.</Name>"
                + "<Writable>false</Writable></ParameterInfoStruct>"
                + "<ParameterInfoStruct><Name>InternetGatewayDevice.DeviceInfo.SoftwareVersion</Name>"
                + "<Writable>false</Writable></ParameterInfoStruct>"
                + "<ParameterInfoStruct><Name>InternetGatewayDevice.ManagementServer.URL</Name>"
                + "<Writable>true</Writable></ParameterInfoStruct>"
                + "</ParameterList></cwmp:GetParameterNamesResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>";

        SoapMessage message = SoapParser.parse(xml);

        assertThat(message.isFault()).isFalse();
        List<Map<String, Object>> list = message.listValue("ParameterList");
        assertThat(list).hasSize(3);
        assertThat(list.get(0).get("Name")).isEqualTo("InternetGatewayDevice.DeviceInfo.");
        assertThat(String.valueOf(list.get(0).get("Name")).endsWith(".")).isTrue();
        assertThat(list.get(2).get("Writable")).isEqualTo("true");
    }

    /**
     * GetParameterValuesResponse：Value 的 xsi:type 去掉 xsd: 前缀后是 Value_type。
     */
    @Test
    void parseGetParameterValuesResponse() {
        String xml = "<SOAP-ENV:Envelope " + NAMESPACES + ">"
                + "<SOAP-ENV:Header><cwmp:ID SOAP-ENV:mustUnderstand=\"1\">1</cwmp:ID></SOAP-ENV:Header>"
                + "<SOAP-ENV:Body><cwmp:GetParameterValuesResponse>"
                + "<ParameterList SOAP-ENC:arrayType=\"cwmp:ParameterValueStruct[2]\">"
                + "<ParameterValueStruct><Name>InternetGatewayDevice.DeviceInfo.SoftwareVersion</Name>"
                + "<Value xsi:type=\"xsd:string\">V1.0.2</Value></ParameterValueStruct>"
                + "<ParameterValueStruct><Name>InternetGatewayDevice.ManagementServer.PeriodicInformEnable</Name>"
                + "<Value xsi:type=\"xsd:boolean\">1</Value></ParameterValueStruct>"
                + "</ParameterList></cwmp:GetParameterValuesResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>";

        SoapMessage message = SoapParser.parse(xml);

        List<Map<String, Object>> list = message.listValue("ParameterList");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("Value")).isEqualTo("V1.0.2");
        assertThat(list.get(0).get("Value_type")).isEqualTo("string");
        assertThat(list.get(1).get("Value_type")).isEqualTo("boolean");
    }

    /**
     * 层级推导：父路径、末段名、深度都要对，否则前端树与子树查询会错。
     */
    @Test
    void deriveTreeHierarchy() {
        assertThat(DeviceCapabilityService.parentOf("InternetGatewayDevice.")).isEqualTo("");
        assertThat(DeviceCapabilityService.parentOf("InternetGatewayDevice.DeviceInfo."))
                .isEqualTo("InternetGatewayDevice.");
        assertThat(DeviceCapabilityService.parentOf(
                "InternetGatewayDevice.DeviceInfo.SoftwareVersion"))
                .isEqualTo("InternetGatewayDevice.DeviceInfo.");

        assertThat(DeviceCapabilityService.nameOf("InternetGatewayDevice."))
                .isEqualTo("InternetGatewayDevice");
        assertThat(DeviceCapabilityService.nameOf("InternetGatewayDevice.DeviceInfo."))
                .isEqualTo("DeviceInfo");

        assertThat(DeviceCapabilityService.depthOf("InternetGatewayDevice.")).isZero();
        assertThat(DeviceCapabilityService.depthOf("InternetGatewayDevice.DeviceInfo.")).isEqualTo(1);
        assertThat(DeviceCapabilityService.depthOf(
                "InternetGatewayDevice.DeviceInfo.SoftwareVersion")).isEqualTo(2);
    }

    /**
     * 子树清单按设备的数据模型根节点展开，两种模型都不能漏：
     * TR-098 走 IGD 的 WANDevice./LANDevice.，TR-181 走 Device. 的 WAN./Ethernet.Interface./WiFi.。
     */
    @Test
    void resolvesSubtreesUnderRootNode() {
        DeviceCapabilityService service = newDeviceCapabilityService();

        List<String> tr098 = ReflectionTestUtils.invokeMethod(
                service, "resolveSubtrees", "InternetGatewayDevice.");
        List<String> tr181 = ReflectionTestUtils.invokeMethod(service, "resolveSubtrees", "Device.");

        assertThat(tr098).isNotEmpty()
                .allSatisfy(path -> assertThat(path).startsWith("InternetGatewayDevice."));
        assertThat(tr181).isNotEmpty()
                .allSatisfy(path -> assertThat(path).startsWith("Device."));
        assertThat(tr098).contains("InternetGatewayDevice.WANDevice.", "InternetGatewayDevice.LANDevice.",
                "InternetGatewayDevice.Services.VoiceService.");
        assertThat(tr181).contains("Device.WAN.", "Device.Ethernet.Interface.",
                "Device.WiFi.", "Device.Services.VoiceService.");
        // 子树名与数据模型绑定：TR-098 设备不能收到 InternetGatewayDevice.LAN./WiFi. 这类
        // TR-181 写法，否则每次采集都回一串 9005 Invalid parameter name
        assertThat(tr098).doesNotContain("InternetGatewayDevice.LAN.", "InternetGatewayDevice.WAN.",
                "InternetGatewayDevice.WiFi.", "InternetGatewayDevice.Ethernet.Interface.");
        assertThat(tr181).doesNotContain("Device.WANDevice.", "Device.LANDevice.");
    }

    /**
     * 只拉子树时要补上根节点与中间层对象：型号 XML 与能力树浏览都按 parent_path 逐级渲染，
     * 少了 {@code Services.} 这一层，整棵 SIP 子树都渲染不出来。
     */
    @Test
    void completesRootAndIntermediateObjects() {
        DeviceCapabilityService service = newDeviceCapabilityService();
        List<DeviceCapabilityService.Node> nodes = List.of(
                new DeviceCapabilityService.Node(
                        "InternetGatewayDevice.Services.VoiceService.1.VoiceProfile.1.SIP.", true, false),
                new DeviceCapabilityService.Node("InternetGatewayDevice.WANDevice.1.", true, false));

        List<?> tree = ReflectionTestUtils.invokeMethod(service, "completeTree",
                nodes, "InternetGatewayDevice.");

        assertThat(paths(tree)).containsExactlyInAnyOrder(
                "InternetGatewayDevice.",
                "InternetGatewayDevice.WANDevice.",
                "InternetGatewayDevice.Services.",
                "InternetGatewayDevice.Services.VoiceService.",
                "InternetGatewayDevice.Services.VoiceService.1.",
                "InternetGatewayDevice.Services.VoiceService.1.VoiceProfile.",
                "InternetGatewayDevice.Services.VoiceService.1.VoiceProfile.1.",
                "InternetGatewayDevice.Services.VoiceService.1.VoiceProfile.1.SIP.",
                "InternetGatewayDevice.WANDevice.1.");
        assertThat(paths(tree).get(0)).isEqualTo("InternetGatewayDevice.");
    }

    private static DeviceCapabilityService newDeviceCapabilityService() {
        return new DeviceCapabilityService(null, null, null, null, null);
    }

    private static List<String> paths(List<?> nodes) {
        List<String> result = new ArrayList<>();
        for (Object node : nodes) {
            result.add(((DeviceCapabilityService.Node) node).getPath());
        }
        return result;
    }
}

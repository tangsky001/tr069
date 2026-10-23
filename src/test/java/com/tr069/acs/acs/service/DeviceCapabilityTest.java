package com.tr069.acs.acs.service;

import com.tr069.acs.cwmp.RpcMethod;
import com.tr069.acs.cwmp.SoapEnvelopeBuilder;
import com.tr069.acs.cwmp.SoapParser;
import com.tr069.acs.acs.config.CapabilityProfiles;
import com.tr069.acs.cwmp.model.InformRequest;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.acs.model.Isp;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.PendingRpc;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import com.tr069.acs.queue.config.QueueProperties;
import com.tr069.acs.worklist.service.ModelTreeStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * 整批被设备拒绝时二分拆批重试：只丢掉真正失效的那条路径，同批其余照常落库。
     *
     * <p>GetParameterValues 是整批事务：批里混一条设备上没有的路径，整批回 9005，
     * 这批的值一个也落不了库。采集清单里留着已删实例的僵尸路径时，整段业务快照
     * 就刷不回来，前端一直显示旧数据。</p>
     */
    @Test
    void 整批被拒时二分拆批只丢失效路径() {
        String sn = "CIOT185A63C0";
        String good1 = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.5.Enable";
        String good2 = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.5.Name";
        String bad = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.9.Enable";

        CpeSession session = Mockito.mock(CpeSession.class);
        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        Mockito.when(sessionManager.find(sn)).thenReturn(session);
        // 「僵尸路径」参与的那批回 9005，其余正常返回
        Mockito.when(session.enqueue(ArgumentMatchers.anyLong(), Mockito.eq("GetParameterValues"),
                        ArgumentMatchers.<Map<String, Object>>any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = inv.getArgument(2);
                    @SuppressWarnings("unchecked")
                    List<String> names = (List<String>) args.get("ParameterNames");
                    return pendingRpc(names.contains(bad)
                            ? faultXml("9005", "Invalid parameter name")
                            : valuesXml(names));
                });

        DeviceCapabilityService service = new DeviceCapabilityService(
                sessionManager, null, Mockito.mock(CpeParameterMapper.class),
                Mockito.mock(QueueProperties.class), null,
                Mockito.mock(CpeDeviceMapper.class), null);
        ReflectionTestUtils.setField(service, "workerThreads", 2);
        ReflectionTestUtils.setField(service, "valueBatchSize", 2);

        DeviceCapabilityService.CollectStatus status =
                new DeviceCapabilityService.CollectStatus(sn, "auto");
        CompletableFuture<?> done = ReflectionTestUtils.invokeMethod(
                service, "readValuesBatch", sn, List.of(good1, bad), status, 0, 1);
        done.join();

        // 坏的那条被剔掉，好的那条照常读到值
        assertThat(status.getValueCount()).isEqualTo(1);
        assertThat(status.getFailBatches()).isEqualTo(1);
        assertThat(String.join(";", status.getErrors()))
                .contains(bad).doesNotContain(good1);
    }

    /**
     * 首次上报的设备：采集跑在台账入库之前（要抢空 POST 窗口），这时查库还没有运营商。
     * Inform 自带 {@code X_CT-COM_UserInfo}，采摘要据此直接按电信档走，
     * 而不是等入库后第二轮才用上电信档。
     */
    @Test
    void 首次上报按Inform认运营商不走通用档() {
        String sn = "36833B4B055937557";
        List<String> gpvPaths = Collections.synchronizedList(new ArrayList<>());

        CpeSession session = Mockito.mock(CpeSession.class);
        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        Mockito.when(sessionManager.find(sn)).thenReturn(session);
        Mockito.when(session.enqueue(ArgumentMatchers.anyLong(), Mockito.eq("GetParameterNames"),
                        ArgumentMatchers.<Map<String, Object>>any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> pendingRpc(namesXml(List.of())));
        Mockito.when(session.enqueue(ArgumentMatchers.anyLong(), Mockito.eq("GetParameterValues"),
                        ArgumentMatchers.<Map<String, Object>>any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = inv.getArgument(2);
                    gpvPaths.addAll((List<String>) args.get("ParameterNames"));
                    return pendingRpc(valuesXml(Map.of(
                            "InternetGatewayDevice.X_CT-COM_UserInfo.UserId", "")));
                });

        // 台账上还没有运营商：这是采集开跑那一刻的真实状态
        CpeDevice device = new CpeDevice();
        device.setIsp(null);
        CpeDeviceMapper deviceMapper = Mockito.mock(CpeDeviceMapper.class);
        Mockito.when(deviceMapper.selectOne(Mockito.any())).thenReturn(device);

        DeviceCapabilityService service = new DeviceCapabilityService(
                sessionManager, null, Mockito.mock(CpeParameterMapper.class),
                Mockito.mock(QueueProperties.class), null, deviceMapper, new CapabilityProfiles());
        ReflectionTestUtils.setField(service, "workerThreads", 2);
        ReflectionTestUtils.setField(service, "valueBatchSize", 64);

        InformRequest inform = InformRequest.builder()
                .parameterList(List.of(ParameterValueStruct.builder()
                        .name("InternetGatewayDevice.X_CT-COM_UserInfo.UserId")
                        .value("")
                        .build()))
                .build();
        ReflectionTestUtils.invokeMethod(service, "rememberInformIsp", sn, inform);

        DeviceCapabilityService.CollectStatus status =
                new DeviceCapabilityService.CollectStatus(sn, "inform");
        ReflectionTestUtils.<CompletableFuture<?>>invokeMethod(service, "runCollect",
                sn, "InternetGatewayDevice.", status.getBatch(), status).join();

        // 电信档的兜底清单里有天翼业务参数，通用档没有：拿它坐实走的是电信档
        assertThat(gpvPaths).as("首次上报也要按电信档采")
                .contains("InternetGatewayDevice.X_CT-COM_UserInfo.UserId");
    }

    /**
     * 电信天翼网关扫不动参数树时（B866 撞 9002）要降级为只读关键字段，
     * 但必须是**连续两次**：9002 也可能只是设备当时状态不对（重启中、CWMP 模块忙），
     * 一次就把设备永久判成扫不动，会让它从此只剩十几条关键字段、型号档案停更。
     */
    @Test
    void 参数树撞9002后记住这台设备后续不再扫() {
        String sn = "001681S17513S0080";
        Map<String, String> values = new LinkedHashMap<>();
        values.put("InternetGatewayDevice.DeviceInfo.Manufacturer", "Huawei");
        values.put("InternetGatewayDevice.DeviceInfo.ProductClass", "B866-S1-4E1P3W1-Ethernet");

        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        CpeSession session = Mockito.mock(CpeSession.class);
        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        Mockito.when(sessionManager.find(sn)).thenReturn(session);
        Mockito.when(session.enqueue(ArgumentMatchers.anyLong(), Mockito.eq("GetParameterNames"),
                        ArgumentMatchers.<Map<String, Object>>any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    asked.add("GetParameterNames");
                    return pendingRpc(faultXml("9002", "Internal error"));
                });
        Mockito.when(session.enqueue(ArgumentMatchers.anyLong(), Mockito.eq("GetParameterValues"),
                        ArgumentMatchers.<Map<String, Object>>any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> pendingRpc(valuesXml(values)));

        CpeDevice device = new CpeDevice();
        device.setIsp("CT");
        CpeDeviceMapper deviceMapper = Mockito.mock(CpeDeviceMapper.class);
        Mockito.when(deviceMapper.selectOne(Mockito.any())).thenReturn(device);

        DeviceCapabilityService service = new DeviceCapabilityService(
                sessionManager, null, Mockito.mock(CpeParameterMapper.class),
                Mockito.mock(QueueProperties.class), null, deviceMapper, new CapabilityProfiles());
        ReflectionTestUtils.setField(service, "workerThreads", 2);
        ReflectionTestUtils.setField(service, "valueBatchSize", 64);

        // 第一轮：按电信档先试参数树，撞 9002 后降级读关键字段
        DeviceCapabilityService.CollectStatus first =
                new DeviceCapabilityService.CollectStatus(sn, "inform");
        ReflectionTestUtils.<CompletableFuture<?>>invokeMethod(service, "runCollect",
                sn, "InternetGatewayDevice.", first.getBatch(), first).join();
        assertThat(asked).as("第一轮要先试一次参数树").isNotEmpty();
        assertThat(first.getValueCount()).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo("partial");

        // 第二轮：只失败一次不算，仍要再试——万一上次只是设备状态不对
        asked.clear();
        DeviceCapabilityService.CollectStatus second =
                new DeviceCapabilityService.CollectStatus(sn, "inform");
        ReflectionTestUtils.<CompletableFuture<?>>invokeMethod(service, "runCollect",
                sn, "InternetGatewayDevice.", second.getBatch(), second).join();
        assertThat(asked).as("只失败一次不该就判死，要再试一轮").isNotEmpty();

        // 第三轮：连续两次失败，才降级为只读关键字段
        asked.clear();
        DeviceCapabilityService.CollectStatus third =
                new DeviceCapabilityService.CollectStatus(sn, "inform");
        ReflectionTestUtils.<CompletableFuture<?>>invokeMethod(service, "runCollect",
                sn, "InternetGatewayDevice.", third.getBatch(), third).join();
        assertThat(asked).as("连续两次扫不动才降级，别每轮都白等").isEmpty();
        assertThat(third.getValueCount()).isEqualTo(2);
    }

    /**
     * 电信天翼网关这类设备：一条 GetParameterNames 都不发，直接按需读关键字段。
     * 对象路径的 GPN 一律回 9002（连「只列下一层」都回），扫树纯属白等。
     */
    @Test
    void 只读关键字段的设备不发任何GetParameterNames() {
        String sn = "001681S17513S0080";
        Map<String, String> values = new LinkedHashMap<>();
        values.put("InternetGatewayDevice.DeviceInfo.Manufacturer", "Huawei");
        values.put("InternetGatewayDevice.DeviceInfo.ProductClass", "B866-S1-4E1P3W1-Ethernet");
        values.put("InternetGatewayDevice.X_CT-COM_UserInfo.UserId", "");

        CpeSession session = Mockito.mock(CpeSession.class);
        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        Mockito.when(sessionManager.find(sn)).thenReturn(session);
        Mockito.when(session.enqueue(ArgumentMatchers.anyLong(), Mockito.eq("GetParameterValues"),
                        ArgumentMatchers.<Map<String, Object>>any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> pendingRpc(valuesXml(values)));

        CpeParameterMapper parameterMapper = Mockito.mock(CpeParameterMapper.class);
        CpeDeviceMapper deviceMapper = Mockito.mock(CpeDeviceMapper.class);
        CpeDevice device = new CpeDevice();
        device.setIsp("CT");
        Mockito.when(deviceMapper.selectOne(Mockito.any())).thenReturn(device);

        // 电信档默认是自适应（先试一次参数树），这里要测的是「明确禁止扫树」那一档
        CapabilityProfiles.Profile noTree = CapabilityProfiles.builtin(Isp.CT);
        noTree.setGpnStrategy(CapabilityProfiles.Profile.STRATEGY_VALUES_ONLY);
        CapabilityProfiles profiles = Mockito.mock(CapabilityProfiles.class);
        Mockito.when(profiles.forIsp(Mockito.any())).thenReturn(noTree);

        DeviceCapabilityService service = new DeviceCapabilityService(
                sessionManager, null, parameterMapper, Mockito.mock(QueueProperties.class), null,
                deviceMapper, profiles);
        ReflectionTestUtils.setField(service, "workerThreads", 2);
        ReflectionTestUtils.setField(service, "valueBatchSize", 64);

        DeviceCapabilityService.CollectStatus status =
                new DeviceCapabilityService.CollectStatus(sn, "inform");
        CompletableFuture<?> done = ReflectionTestUtils.invokeMethod(
                service, "runCollect", sn, "InternetGatewayDevice.", status.getBatch(), status);
        done.join();

        // 一条 GPN 都不该下发，只有 GPV
        Mockito.verify(session, Mockito.never())
                .enqueue(ArgumentMatchers.anyLong(), Mockito.eq("GetParameterNames"),
                        ArgumentMatchers.<Map<String, Object>>any(), ArgumentMatchers.any());
        assertThat(status.getValueCount()).isEqualTo(3);
        assertThat(status.getStatus()).isEqualTo("partial");
        Mockito.verify(parameterMapper).updateValues(Mockito.eq(sn), ArgumentMatchers.anyList());
    }

    /**
     * 逐层展开遇到 {@code X_CTC_} 私有分支就停：节点记下来，但不再往下钻。
     * 电信固件一遍历进去就抛 9002，整个子树连带报废。
     */
    @Test
    void 逐层展开绕开天翼私有分支() {
        String sn = "001681S17513S0080";
        String wan = "InternetGatewayDevice.WANDevice.1.";
        String ctc = wan + "X_CTC_IPTV.";
        String conn = wan + "WANConnectionDevice.";

        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        CpeSession session = Mockito.mock(CpeSession.class);
        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        Mockito.when(sessionManager.find(sn)).thenReturn(session);
        Mockito.when(session.enqueue(ArgumentMatchers.anyLong(), Mockito.eq("GetParameterNames"),
                        ArgumentMatchers.<Map<String, Object>>any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = inv.getArgument(2);
                    String path = String.valueOf(args.get("ParameterPath"));
                    asked.add(path);
                    if (wan.equals(path)) {
                        // 这一层既有天翼私有分支，也有正常分支
                        return pendingRpc(namesXml(List.of(ctc, conn)));
                    }
                    if (conn.equals(path)) {
                        return pendingRpc(namesXml(List.of(conn + "1.")));
                    }
                    return pendingRpc(namesXml(List.of()));
                });

        DeviceCapabilityService service = new DeviceCapabilityService(
                sessionManager, null, Mockito.mock(CpeParameterMapper.class),
                Mockito.mock(QueueProperties.class), null,
                Mockito.mock(CpeDeviceMapper.class), null);
        ReflectionTestUtils.setField(service, "workerThreads", 2);
        ReflectionTestUtils.setField(service, "maxObjectRounds", 500);

        List<DeviceCapabilityService.Node> sink = Collections.synchronizedList(new ArrayList<>());
        DeviceCapabilityService.CollectStatus status =
                new DeviceCapabilityService.CollectStatus(sn, "inform");
        CompletableFuture<?> done = ReflectionTestUtils.invokeMethod(service, "fetchLevel",
                sn, wan, sink, new AtomicInteger(), status, CapabilityProfiles.builtin(Isp.CT));
        done.join();

        assertThat(asked).as("私有分支不该再往下问").doesNotContain(ctc);
        assertThat(asked).as("正常分支照常展开").contains(conn);
        assertThat(paths(sink)).contains(ctc, conn, conn + "1.");
    }

    /**
     * 设备对 GetParameterNames 一句都答不出来时（电信那台父对象一律 9002），退到采集标准
     * 登记的已知路径直接 GetParameterValues，至少把设备身份读回来。
     */
    @Test
    void GPN全废时按已知路径读回设备身份() {
        String sn = "001681S17513S0080";
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put("InternetGatewayDevice.DeviceInfo.SpecVersion", "1.0");
        identity.put("InternetGatewayDevice.DeviceInfo.Manufacturer", "CIOT");
        identity.put("InternetGatewayDevice.DeviceInfo.ProductClass", "HG6145F1");

        CpeSession session = Mockito.mock(CpeSession.class);
        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        Mockito.when(sessionManager.find(sn)).thenReturn(session);
        Mockito.when(session.enqueue(ArgumentMatchers.anyLong(), Mockito.eq("GetParameterValues"),
                        ArgumentMatchers.<Map<String, Object>>any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> pendingRpc(valuesXml(identity)));

        CpeParameterMapper parameterMapper = Mockito.mock(CpeParameterMapper.class);
        DeviceCapabilityService service = new DeviceCapabilityService(
                sessionManager, null, parameterMapper, Mockito.mock(QueueProperties.class), null,
                Mockito.mock(CpeDeviceMapper.class), null);
        ReflectionTestUtils.setField(service, "workerThreads", 2);

        DeviceCapabilityService.CollectStatus status =
                new DeviceCapabilityService.CollectStatus(sn, "inform");
        CompletableFuture<Boolean> done = ReflectionTestUtils.invokeMethod(service, "probeGlobalPaths",
                sn, "InternetGatewayDevice.", CapabilityProfiles.builtin(Isp.STANDARD), status);

        assertThat(done.join()).isTrue();
        assertThat(status.getValueCount()).isEqualTo(3);
        // 值要落到 cpe_parameter，设备详情与能力参数接口才查得到
        Mockito.verify(parameterMapper).updateValues(Mockito.eq(sn), ArgumentMatchers.anyList());
    }

    /**
     * 读 {@code InternetGatewayDevice.DeviceInfo.*} 的身份参数并打印。
     *
     * <p>GPN 拉不出树的猫（电信那台对 {@code WANDevice.} 回 9002）也能走这条路：
     * DeviceInfo 是已知路径，直接发 GetParameterValues 就行。打印出来看设备到底给了什么——
     * SpecVersion 只是规范版本，认型号要看 ProductClass / Manufacturer / ManufacturerOUI。</p>
     */
    @Test
    void 读取DeviceInfo身份参数并打印() {
        String sn = "001681S17513S0080";
        Map<String, String> deviceInfo = new LinkedHashMap<>();
        deviceInfo.put("InternetGatewayDevice.DeviceInfo.SpecVersion", "1.0");
        deviceInfo.put("InternetGatewayDevice.DeviceInfo.Manufacturer", "CIOT");
        deviceInfo.put("InternetGatewayDevice.DeviceInfo.ManufacturerOUI", "A0B1C2");
        deviceInfo.put("InternetGatewayDevice.DeviceInfo.ProductClass", "ZNM802");
        deviceInfo.put("InternetGatewayDevice.DeviceInfo.HardwareVersion", "T");
        deviceInfo.put("InternetGatewayDevice.DeviceInfo.SoftwareVersion", "V0200-0101190724");

        CpeSession session = Mockito.mock(CpeSession.class);
        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        Mockito.when(sessionManager.find(sn)).thenReturn(session);
        Mockito.when(session.enqueue(ArgumentMatchers.anyLong(), Mockito.eq("GetParameterValues"),
                        ArgumentMatchers.<Map<String, Object>>any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = inv.getArgument(2);
                    System.out.println("[DeviceInfo] 下发 GetParameterValues: " + args.get("ParameterNames"));
                    return pendingRpc(valuesXml(deviceInfo));
                });

        DeviceCapabilityService service = new DeviceCapabilityService(
                sessionManager, null, Mockito.mock(CpeParameterMapper.class),
                Mockito.mock(QueueProperties.class), null,
                Mockito.mock(CpeDeviceMapper.class), null);
        ReflectionTestUtils.setField(service, "workerThreads", 2);
        ReflectionTestUtils.setField(service, "valueBatchSize", 64);

        Map<String, String> got = service.readPathValues(sn, new ArrayList<>(deviceInfo.keySet())).join();

        for (String path : deviceInfo.keySet()) {
            System.out.println("[DeviceInfo] " + path + " = " + got.get(path));
        }

        assertThat(got).containsEntry("InternetGatewayDevice.DeviceInfo.SpecVersion", "1.0");
        assertThat(got).containsEntry("InternetGatewayDevice.DeviceInfo.ProductClass", "ZNM802");
    }

    /**
     * 采集标准按运营商配置子树：电信定制机拉不动 {@code WANDevice.} 时，
     * 直接在标准里写死带实例号的子树，不动代码。
     */
    @Test
    void 按运营商采集标准取子树() {
        DeviceCapabilityService service = newDeviceCapabilityService();

        CapabilityProfiles.Profile ct = new CapabilityProfiles.Profile();
        ct.setName("中国电信");
        ct.setSubtrees(List.of("WANDevice.1.WANConnectionDevice.", "LANDevice."));

        List<String> paths = ReflectionTestUtils.invokeMethod(
                service, "resolveSubtrees", "InternetGatewayDevice.", ct);

        assertThat(paths).containsExactly("InternetGatewayDevice.WANDevice.1.WANConnectionDevice.",
                "InternetGatewayDevice.LANDevice.");
    }

    /**
     * 电信定制机的典型表现：{@code GetParameterNames(InternetGatewayDevice.WANDevice.)} 回
     * 9002 Internal error，递归与逐层都不认，只能按实例号 {@code WANDevice.1.} 逐个问。
     */
    @Test
    void 父对象GPN被拒时按实例号探测() {
        String sn = "001681S17513S0080";
        String parent = "InternetGatewayDevice.WANDevice.";
        String instance = "InternetGatewayDevice.WANDevice.1.";

        CpeSession session = Mockito.mock(CpeSession.class);
        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        Mockito.when(sessionManager.find(sn)).thenReturn(session);
        Mockito.when(session.enqueue(ArgumentMatchers.anyLong(),
                        Mockito.eq("GetParameterNames"),
                        ArgumentMatchers.<Map<String, Object>>any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = inv.getArgument(2);
                    String path = String.valueOf(args.get("ParameterPath"));
                    // 父对象一律 9002，只有第一个实例号应答，第二个实例号不存在
                    return pendingRpc(instance.equals(path)
                            ? namesXml(List.of(instance + "WANConnectionDevice."))
                            : faultXml("9002", "Internal error"));
                });

        DeviceCapabilityService service = new DeviceCapabilityService(
                sessionManager, null, Mockito.mock(CpeParameterMapper.class),
                Mockito.mock(QueueProperties.class), null,
                Mockito.mock(CpeDeviceMapper.class), null);
        ReflectionTestUtils.setField(service, "workerThreads", 2);
        ReflectionTestUtils.setField(service, "fallbackNextLevel", true);
        // @Value 的默认值在单测里不生效，逐层展开的上限要自己给，否则第一轮就判超限
        ReflectionTestUtils.setField(service, "maxObjectRounds", 500);

        CapabilityProfiles.Profile profile = new CapabilityProfiles.Profile();
        List<DeviceCapabilityService.Node> sink = Collections.synchronizedList(new ArrayList<>());
        DeviceCapabilityService.CollectStatus status =
                new DeviceCapabilityService.CollectStatus(sn, "inform");

        CompletableFuture<?> done = ReflectionTestUtils.invokeMethod(service, "collectSubtree",
                sn, parent, profile, sink, new AtomicInteger(), status);
        done.join();

        assertThat(paths(sink)).contains(instance + "WANConnectionDevice.");
        // 实例号探到第一个不存在的就停，不会一路试到上限
        assertThat(String.join(";", status.getErrors()))
                .contains("InternetGatewayDevice.WANDevice.2.");
        assertThat(status.isAborted()).isFalse();
    }

    /**
     * 采集成功后的归档走统一入口：不管哪家运营商的猫，都先截到 standard 目录
     * （models/standard/standard/{型号}.xml），按运营商分目录是人工适配申报时才做的事。
     */
    @Test
    void 采集成功后按通用目录归档() {
        ModelTreeStore store = Mockito.mock(ModelTreeStore.class);
        DeviceCapabilityService service = new DeviceCapabilityService(
                null, null, null, null, store, null, null);
        ReflectionTestUtils.setField(service, "workerThreads", 2);

        ReflectionTestUtils.invokeMethod(service, "exportModelTreeAsync", "SN001");

        Mockito.verify(store, Mockito.timeout(3000)).exportCollected("SN001");
    }

    /**
     * 手动采集时设备多半不在下发窗口：指令压在队列里，等响应的 future 永远不完成，
     * 那一轮就一直挂着。不强制收尾的话 {@code inFlight} 永远占着，
     * 这台设备从此再也采不了（界面一直转圈，只能重启 ACS）。
     */
    @Test
    void 卡死的采集要能被下一轮强制收尾() {
        String sn = "37300148C4AA410D6";

        CpeSession session = Mockito.mock(CpeSession.class);
        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        Mockito.when(sessionManager.find(sn)).thenReturn(session);
        Mockito.when(session.enqueue(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(),
                        ArgumentMatchers.<Map<String, Object>>any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> pendingRpc(namesXml(List.of())));

        DeviceCapabilityService service = new DeviceCapabilityService(
                sessionManager, null, Mockito.mock(CpeParameterMapper.class),
                Mockito.mock(QueueProperties.class), null,
                Mockito.mock(CpeDeviceMapper.class), new CapabilityProfiles());
        ReflectionTestUtils.setField(service, "workerThreads", 2);
        ReflectionTestUtils.setField(service, "collectTimeoutMillis", 200L);

        // 上一轮卡死：开跑于 10 秒前，到现在还是 running
        DeviceCapabilityService.CollectStatus stuck =
                new DeviceCapabilityService.CollectStatus(sn, "manual");
        ReflectionTestUtils.setField(stuck, "status", "running");
        ReflectionTestUtils.setField(stuck, "startTime", System.currentTimeMillis() - 10_000L);
        @SuppressWarnings("unchecked")
        Map<String, DeviceCapabilityService.CollectStatus> statuses =
                (Map<String, DeviceCapabilityService.CollectStatus>)
                        ReflectionTestUtils.getField(service, "statuses");
        statuses.put(sn, stuck);

        DeviceCapabilityService.CollectStatus next = service.collect(sn, true);

        assertThat(stuck.getStatus()).as("超时的上一轮要被判失败，不能永远 running")
                .isEqualTo("fail");
        assertThat(next).as("要开新的一轮，不能把卡死那轮原样还回去").isNotSameAs(stuck);
    }

    private static PendingRpc pendingRpc(SoapMessage message) {
        PendingRpc rpc = Mockito.mock(PendingRpc.class);
        Mockito.when(rpc.getResponse()).thenReturn(CompletableFuture.completedFuture(message));
        return rpc;
    }

    /** 组装一个 GetParameterValuesResponse：按给定值回带每个路径。 */
    private static SoapMessage valuesXml(Map<String, String> values) {
        StringBuilder list = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            list.append("<ParameterValueStruct><Name>").append(entry.getKey())
                    .append("</Name><Value xsi:type=\"xsd:string\">").append(entry.getValue())
                    .append("</Value></ParameterValueStruct>");
        }
        return SoapParser.parse("<SOAP-ENV:Envelope " + NAMESPACES + ">"
                + "<SOAP-ENV:Header><cwmp:ID SOAP-ENV:mustUnderstand=\"1\">1</cwmp:ID></SOAP-ENV:Header>"
                + "<SOAP-ENV:Body><cwmp:GetParameterValuesResponse>"
                + "<ParameterList SOAP-ENC:arrayType=\"cwmp:ParameterValueStruct[" + values.size() + "]\">"
                + list
                + "</ParameterList></cwmp:GetParameterValuesResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>");
    }

    /** 组装一个 GetParameterValuesResponse：回带每个路径的值。 */
    private static SoapMessage valuesXml(List<String> names) {
        StringBuilder list = new StringBuilder();
        for (String name : names) {
            list.append("<ParameterValueStruct><Name>").append(name)
                    .append("</Name><Value xsi:type=\"xsd:string\">v</Value></ParameterValueStruct>");
        }
        return SoapParser.parse("<SOAP-ENV:Envelope " + NAMESPACES + ">"
                + "<SOAP-ENV:Header><cwmp:ID SOAP-ENV:mustUnderstand=\"1\">1</cwmp:ID></SOAP-ENV:Header>"
                + "<SOAP-ENV:Body><cwmp:GetParameterValuesResponse>"
                + "<ParameterList SOAP-ENC:arrayType=\"cwmp:ParameterValueStruct[" + names.size() + "]\">"
                + list
                + "</ParameterList></cwmp:GetParameterValuesResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>");
    }

    /** 组装一个 GetParameterNamesResponse：回带若干路径。 */
    private static SoapMessage namesXml(List<String> names) {
        StringBuilder list = new StringBuilder();
        for (String name : names) {
            list.append("<ParameterInfoStruct><Name>").append(name)
                    .append("</Name><Writable>true</Writable></ParameterInfoStruct>");
        }
        return SoapParser.parse("<SOAP-ENV:Envelope " + NAMESPACES + ">"
                + "<SOAP-ENV:Header><cwmp:ID SOAP-ENV:mustUnderstand=\"1\">1</cwmp:ID></SOAP-ENV:Header>"
                + "<SOAP-ENV:Body><cwmp:GetParameterNamesResponse>"
                + "<ParameterList SOAP-ENC:arrayType=\"cwmp:ParameterInfoStruct[" + names.size() + "]\">"
                + list
                + "</ParameterList></cwmp:GetParameterNamesResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>");
    }

    /** 组装一个 CWMP 故障响应（9005 无效参数名）。 */
    private static SoapMessage faultXml(String code, String text) {
        return SoapParser.parse("<SOAP-ENV:Envelope " + NAMESPACES + ">"
                + "<SOAP-ENV:Header><cwmp:ID SOAP-ENV:mustUnderstand=\"1\">1</cwmp:ID></SOAP-ENV:Header>"
                + "<SOAP-ENV:Body><SOAP-ENV:Fault>"
                + "<faultcode>SOAP-ENV:Client</faultcode>"
                + "<faultstring>CWMP fault</faultstring>"
                + "<detail><cwmp:Fault><FaultCode>" + code
                + "</FaultCode><FaultString>" + text
                + "</FaultString></cwmp:Fault></detail>"
                + "</SOAP-ENV:Fault></SOAP-ENV:Body></SOAP-ENV:Envelope>");
    }

    private static DeviceCapabilityService newDeviceCapabilityService() {
        return new DeviceCapabilityService(null, null, null, null, null, null, null);
    }

    private static List<String> paths(List<?> nodes) {
        List<String> result = new ArrayList<>();
        for (Object node : nodes) {
            result.add(((DeviceCapabilityService.Node) node).getPath());
        }
        return result;
    }
}

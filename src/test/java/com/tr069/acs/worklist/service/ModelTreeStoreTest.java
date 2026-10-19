package com.tr069.acs.worklist.service;

import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeParameter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 型号能力树文件的导出与查询。
 */
class ModelTreeStoreTest {

    private static final String WAN_IP =
            "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.";
    private static final String WAN_SIP = WAN_IP + "X_CT-COM_SIPEnable";
    private static final String WAN_VLAN = WAN_IP + "X_CT-COM_VLANID";
    private static final String WAN_NEW =
            "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.2.";

    @Test
    void exportsNestedTreeWithEscapedValues() {
        List<CpeParameter> nodes = List.of(
                object("InternetGatewayDevice.", ""),
                object("InternetGatewayDevice.WANDevice.1.", "InternetGatewayDevice."),
                object("InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.",
                        "InternetGatewayDevice.WANDevice.1."),
                object(WAN_IP, "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1."),
                param(WAN_SIP, WAN_IP, "1", true, "boolean"),
                param(WAN_VLAN, WAN_IP, "a<b\"c", false, "string"));

        String xml = ModelTreeStore.buildXml("SN001", "ct", "HG6145F1_V3.0", device(), nodes);

        assertTrue(xml.contains("<Object name=\"InternetGatewayDevice\" path=\"InternetGatewayDevice.\">"),
                "根节点应作为对象输出");
        assertTrue(xml.contains("<Object name=\"1\" path=\"InternetGatewayDevice.WANDevice.1.\">"),
                "实例对象的节点名就是路径末段的实例号");
        assertTrue(xml.indexOf("WANDevice.1.\"") < xml.indexOf("WANIPConnection.1.\""),
                "对象节点应按层级嵌套而不是平铺");
        assertTrue(xml.contains("<Param name=\"X_CT-COM_SIPEnable\" path=\"" + WAN_SIP
                        + "\" writable=\"true\" type=\"boolean\" value=\"1\"/>"),
                "参数节点应带路径、可写位、类型与值");
        assertTrue(xml.contains("value=\"a&lt;b&quot;c\""), "值里的尖括号与引号必须转义");
        assertTrue(xml.contains("modelKey=\"HG6145F1_V3.0\""), "文件名依据的型号标识要写在根节点上");
    }

    @Test
    void readsParamsBackFromFile() throws Exception {
        List<CpeParameter> nodes = List.of(
                object("InternetGatewayDevice.", ""),
                param(WAN_SIP, "InternetGatewayDevice.", "1", true, "boolean"));
        String xml = ModelTreeStore.buildXml("SN001", "ct", "HG6145F1_V3.0", device(), nodes);

        Path file = Files.createTempDirectory("model-tree").resolve("ct").resolve("hg6145f1.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, xml, StandardCharsets.UTF_8);

        List<ModelTreeStore.ParamNode> params =
                new ModelTreeStore(null, null, null, null, null, null, null).readParams(file);
        assertEquals(1, params.size());
        assertEquals(WAN_SIP, params.get(0).getPath());
        assertTrue(params.get(0).isWritable(), "下发前要能看出该节点可写");
        assertEquals("boolean", params.get(0).getType());
        assertEquals("1", params.get(0).getValue());
    }

    @Test
    void matchesBizItemByKeywords() {
        assertEquals(List.of("wan", "sip", "vlan"), ModelTreeStore.keywords("wan_sip_vlan"));
        assertEquals(List.of(), ModelTreeStore.keywords(null));

        List<String> words = ModelTreeStore.keywords("wan_sip_vlan");
        assertTrue(ModelTreeStore.score(WAN_SIP, words) > 0, "命中部分关键字也要作为候选返回");
        assertEquals(0, ModelTreeStore.score("InternetGatewayDevice.DeviceInfo.ModelName", words),
                "完全不相关的路径不应命中");
        assertTrue(ModelTreeStore.score(WAN_SIP, List.of("sip"))
                > ModelTreeStore.score("InternetGatewayDevice.DeviceInfo.SoftwareVersion", List.of("sip")));
    }

    @Test
    void rewritesTemplatePathToModelSpecificNode() {
        List<ModelTreeStore.ParamNode> params = List.of(
                node(WAN_NEW + "X_CMCC_VLANIDMark", true),
                node(WAN_NEW + "Name", true),
                node("InternetGatewayDevice.Services.VoiceService.1.SIP.VLANIDMark", false));

        // 模板写通用名，父对象是 AddObject 刚建出来的实例 4（档案里只有 2/3）
        String template = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1."
                + "WANIPConnection.4.VLANIDMark";
        assertEquals("InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1."
                        + "WANIPConnection.4.X_CMCC_VLANIDMark",
                ModelTreeStore.matchPath(params, template),
                "应改写成该型号真实节点，实例号沿用 AddObject 新建的那个");

        assertEquals(WAN_NEW + "Name",
                ModelTreeStore.matchPath(params, WAN_NEW + "Name"),
                "路径本身存在于型号档案时保持原样");
    }

    @Test
    void rewritesVendorSpecificNameThroughAlias() {
        List<ModelTreeStore.ParamNode> params = List.of(
                node(WAN_NEW + "X_CMCC_VLANIDMark", true),
                node(WAN_NEW + "AddressingType", true));

        assertEquals(WAN_NEW + "X_CMCC_VLANIDMark",
                ModelTreeStore.matchPath(params, WAN_NEW + "X_CMCC_VLANID"),
                "移动定制机的 VLAN 号写在 VLANIDMark 上，模板写的通用名要能改判过去");

        assertEquals(WAN_NEW + "AddressingType",
                ModelTreeStore.matchPath(params, WAN_NEW + "IPAddressingType"),
                "地址获取方式的厂商写法要落到这台设备上的标准名");

        assertEquals(null, ModelTreeStore.matchPath(List.of(node(WAN_NEW + "Name", true)),
                        WAN_NEW + "X_CMCC_VLANID"),
                "树上没有 VLAN 相关节点时不该照别名硬凑一个");
    }

    @Test
    void leavesPathAloneWhenModelHasNoSuchNode() {
        List<ModelTreeStore.ParamNode> params = List.of(node(WAN_NEW + "X_CMCC_VLANIDMark", true));
        assertEquals(null, ModelTreeStore.matchPath(params, WAN_NEW + "TotallyUnknown"),
                "型号里没有的节点不该被改写，交由设备侧报错更清楚");
        assertEquals(null, ModelTreeStore.matchPath(params, null));
    }

    @Test
    void comparesLeafNamesIgnoringVendorPrefix() {
        assertEquals("vlanidmark", ModelTreeStore.stripVendor("X_CMCC_VLANIDMark"));
        assertEquals("vlanidmark", ModelTreeStore.stripVendor("X_CT-COM_VLANIDMark"));
        assertEquals(90, ModelTreeStore.leafScore("X_CMCC_VLANIDMark", "VLANIDMark"),
                "剥掉厂商前缀后相同应视为同一参数");
        assertEquals(100, ModelTreeStore.leafScore("Enable", "Enable"));
        assertEquals(0, ModelTreeStore.leafScore("Enable", "VLANIDMark"));
    }

    @Test
    void refusesLooseMatchWhenNameLengthsDifferMuch() {
        String wlan = "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.";
        List<ModelTreeStore.ParamNode> params = List.of(node(wlan + "SSID", true));
        assertEquals(null, ModelTreeStore.matchPath(params, wlan + "X_CT-COM_HideSSID"),
                "SSID 是 HideSSID 的后缀但长度差太多，认了就会把隐藏值写进 SSID");

        List<ModelTreeStore.ParamNode> withAuto = List.of(node(wlan + "AutoChannelEnable", true));
        assertEquals(wlan + "AutoChannelEnable",
                ModelTreeStore.matchPath(withAuto, wlan + "AutoChannelEnabled"),
                "只差一个字母的命名差异仍要认，否则跨厂商模板没法用");
    }

    @Test
    void pointsAtRealParamNodeNotItsParentObject() {
        String wlan = "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.";
        List<ModelTreeStore.ParamNode> params = List.of(
                node(wlan + "PreSharedKey.1.PreSharedKey", true),
                node(wlan + "SSID", true));
        assertEquals(wlan + "PreSharedKey.1.PreSharedKey",
                ModelTreeStore.matchPath(params, wlan + "PreSharedKey"),
                "PreSharedKey 在设备上是对象，真实参数在它下面，不能拼出对象路径");
    }

    @Test
    void dropsParamsWhenSeveralCollapseToSameNode() {
        ModelTreeStore.PathResolution resolution = new ModelTreeStore.PathResolution();
        resolution.getRewritten().put("A.X_CT-COM_HideSSID", "A.SSID");
        resolution.getRewritten().put("A.X_CT-COM_SSID", "A.SSID");
        resolution.getRewritten().put("A.Name", "A.Name");
        ModelTreeStore.dropCollisions(resolution);

        assertEquals(List.of("A.X_CT-COM_HideSSID", "A.X_CT-COM_SSID"), resolution.getUnsupported(),
                "两条模板参数抢同一个节点，对应关系不可信，宁可不写");
        assertEquals(Set.of("A.Name"), resolution.getRewritten().keySet(),
                "没有歧义的那条照常下发");
    }

    @Test
    void normalizesInstanceNumbersForParentMatch() {
        assertEquals("InternetGatewayDevice.WANDevice.{}.WANConnectionDevice.{}.WANIPConnection.{}.",
                ModelTreeStore.normalizeInstances(
                        "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.2.WANIPConnection.4."),
                "新建实例号在档案里不存在，比较父对象时必须忽略实例号");
    }

    /**
     * 不管哪家运营商的猫，进来先归档到 standard：models/standard/standard/{型号}.xml，
     * 与移动那台当初一样，按运营商分目录留到人工适配申报时。
     */
    @Test
    void 未适配型号一律归档到通用目录() {
        ModelTreeStore store = new ModelTreeStore(null, null, null, null, null, null, null);

        assertEquals("standard", store.archiveIsp(device()), "电信猫没适配过也要先落 standard");
        assertEquals("standard", store.archiveIsp(null), "设备查不到时也不能落到别的目录");
    }

    /**
     * 已适配过的型号继续归档到已适配那家，否则同一型号会散出两份档案。
     */
    @Test
    void 已适配型号归档到已适配那家() {
        ModelAdaptationProfileStore profileStore = Mockito.mock(ModelAdaptationProfileStore.class);
        Mockito.when(profileStore.findAdaptedIsp("HG6145F1_V3.0")).thenReturn("CT");
        ModelTreeStore store = new ModelTreeStore(null, null, null, null, null, null, profileStore);

        assertEquals("CT", store.archiveIsp(device()));
    }

    private static ModelTreeStore.ParamNode node(String path, boolean writable) {
        return new ModelTreeStore.ParamNode(path, path.substring(path.lastIndexOf('.') + 1),
                writable, "string", "");
    }

    private static CpeDevice device() {
        CpeDevice device = new CpeDevice();
        device.setSn("SN001");
        device.setProductClass("HG6145F1");
        device.setSoftwareVersion("V3.0");
        device.setManufacturer("CIOT");
        return device;
    }

    private static CpeParameter object(String path, String parent) {
        CpeParameter node = new CpeParameter();
        node.setSn("SN001");
        node.setParamPath(path);
        node.setParentPath(parent);
        node.setNodeName(nameOf(path));
        node.setNodeType(CpeParameter.TYPE_OBJECT);
        node.setWritable(0);
        return node;
    }

    /** 与采集落库时的 nodeName 一致：路径末段。 */
    private static String nameOf(String path) {
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        return p.substring(p.lastIndexOf('.') + 1);
    }

    private static CpeParameter param(String path, String parent, String value,
                                      boolean writable, String type) {
        CpeParameter node = new CpeParameter();
        node.setSn("SN001");
        node.setParamPath(path);
        node.setParentPath(parent);
        node.setNodeName(nameOf(path));
        node.setNodeType(CpeParameter.TYPE_PARAM);
        node.setWritable(writable ? 1 : 0);
        node.setParamValue(value);
        node.setValueType(type);
        return node;
    }
}

package com.tr069.acs.exec;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeParameterMapper;
import com.tr069.acs.exec.model.IntentPlan;
import com.tr069.acs.exec.model.PlanItem;
import com.tr069.acs.worklist.service.ModelTreeStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * WAN 挂载位置判读。
 *
 * <p>模板里的 {@code WANConnectionDevice.2.} 是型号级常量、照着当初那台设备的报文写死的，
 * 换一台结构不同的设备照发就是 9002 Internal error。判读一旦失效，下发会静默地按模板
 * 原样发到不存在的父对象上——必须钉住。</p>
 */
class IntentDeliveryServiceTest {

    /** 构造一个只依赖参数快照的执行器：判读只需要 parameterMapper。 */
    private IntentDeliveryService service(List<CpeParameter> rows) {
        CpeParameterMapper mapper = Mockito.mock(CpeParameterMapper.class);
        Mockito.when(mapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<CpeParameter>>any()))
                .thenReturn(rows);
        return new IntentDeliveryService(null, null, null, null, null, null,
                null, null, null, null, null, mapper, null, null);
    }

    /** 构造一个只依赖型号能力树的执行器：补写 VLAN 模式只需要 modelTreeStore。 */
    private IntentDeliveryService serviceWithTree(ModelTreeStore tree) {
        CpeParameterMapper mapper = Mockito.mock(CpeParameterMapper.class);
        return new IntentDeliveryService(null, null, null, null, null, null,
                null, null, null, null, null, mapper, tree, null);
    }

    /** 能力树只认 X_CMCC_VLANMode，其余路径一律判为不存在。 */
    private ModelTreeStore treeWithVlanMode() {
        ModelTreeStore tree = Mockito.mock(ModelTreeStore.class);
        Mockito.when(tree.resolvePaths(Mockito.eq("CIOT185A63C0"),
                        ArgumentMatchers.<List<String>>any()))
                .thenAnswer(inv -> {
                    List<String> paths = inv.getArgument(1);
                    ModelTreeStore.PathResolution resolved = new ModelTreeStore.PathResolution();
                    for (String path : paths) {
                        if (path.endsWith("X_CMCC_VLANMode")) {
                            resolved.getTypes().put(path, "int");
                        } else {
                            resolved.getUnsupported().add(path);
                        }
                    }
                    return resolved;
                });
        return tree;
    }

    private static CpeParameter row(String path) {
        CpeParameter p = new CpeParameter();
        p.setParamPath(path);
        return p;
    }

    @Test
    void 取快照里最小的WANConnectionDevice编号() {
        IntentDeliveryService svc = service(List.of(
                row("InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.4.Enable"),
                row("InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.2.Name")));

        assertEquals(1, svc.detectWanDevice("CIOT185A63C0"));
    }

    @Test
    void 只有一级挂载点时就用那一级() {
        IntentDeliveryService svc = service(List.of(
                row("InternetGatewayDevice.WANDevice.1.WANConnectionDevice.2.WANIPConnection.1.Enable")));

        assertEquals(2, svc.detectWanDevice("CIOT185A63C0"));
    }

    @Test
    void 采不到就返回null让计划保持原样() {
        IntentDeliveryService svc = service(List.of());

        assertNull(svc.detectWanDevice("CIOT185A63C0"));
    }

    @Test
    void 模板路径按设备实际编号改写() {
        String template = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.2.WANIPConnection.{i}.Enable";

        assertEquals("InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.{i}.Enable",
                IntentDeliveryService.withWanDevice(template, 1));
    }

    @Test
    void 带VLAN的下发自动补上VLAN模式且排在ID前面() {
        IntentDeliveryService svc = serviceWithTree(treeWithVlanMode());
        String base = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.";

        IntentPlan plan = new IntentPlan("Internet_WAN", 1, List.of(), List.of(
                new PlanItem("Internet_WAN", "vlan_id", base + "X_CMCC_VLANIDMark", "100", "int"),
                new PlanItem("Internet_WAN", "conn_type", base + "ConnectionType", "IP_Routed", "string"),
                new PlanItem("Internet_WAN", "enable", base + "Enable", "true", "boolean")),
                List.of());

        IntentPlan fixed = svc.ensureVlanMode("CIOT185A63C0", plan);

        assertEquals(List.of("vlan_mode", "vlan_id", "conn_type", "enable"),
                fixed.parameters().stream().map(PlanItem::bizField).toList());
        assertEquals("2", fixed.parameters().get(0).value());
        assertEquals("int", fixed.parameters().get(0).valueType());
    }

    @Test
    void 已经有VLAN模式或没有VLAN时都不动计划() {
        IntentDeliveryService svc = serviceWithTree(treeWithVlanMode());
        String base = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.";

        // 模板自己带了模式：保持原样，不重复补
        IntentPlan withMode = new IntentPlan("Internet_WAN", 1, List.of(), List.of(
                new PlanItem("Internet_WAN", "vlan_mode", base + "X_CMCC_VLANMode", "2", "int"),
                new PlanItem("Internet_WAN", "vlan_id", base + "X_CMCC_VLANIDMark", "41", "int")),
                List.of());
        assertEquals(withMode.parameters(), svc.ensureVlanMode("CIOT185A63C0", withMode).parameters());

        // 没带 VLAN ID：透传连接，不该多写一个模式
        IntentPlan noVlan = new IntentPlan("Internet_WAN", 1, List.of(), List.of(
                new PlanItem("Internet_WAN", "conn_type", base + "ConnectionType", "IP_Routed", "string"),
                new PlanItem("Internet_WAN", "enable", base + "Enable", "true", "boolean")),
                List.of());
        assertEquals(noVlan.parameters(), svc.ensureVlanMode("CIOT185A63C0", noVlan).parameters());

        // VLAN ID 为 0 时同理
        IntentPlan zeroVlan = new IntentPlan("Internet_WAN", 1, List.of(), List.of(
                new PlanItem("Internet_WAN", "vlan_id", base + "X_CMCC_VLANIDMark", "0", "int")),
                List.of());
        assertEquals(zeroVlan.parameters(), svc.ensureVlanMode("CIOT185A63C0", zeroVlan).parameters());
    }

    @Test
    void 布尔值统一按标准布尔词下发() {
        // 1/0 是业务字段的写法，TRUE/FALSE 是这台设备自己上报的写法，
        // 两者都要落到 xsd:boolean 的标准词法上
        assertEquals("true", IntentDeliveryService.booleanValue("1"));
        assertEquals("false", IntentDeliveryService.booleanValue("0"));
        assertEquals("true", IntentDeliveryService.booleanValue("TRUE"));
        assertEquals("false", IntentDeliveryService.booleanValue("False"));
    }

    @Test
    void 认不出来的值原样返回() {
        assertEquals("100", IntentDeliveryService.booleanValue("100"));
        assertEquals("DHCP", IntentDeliveryService.booleanValue("DHCP"));
        assertNull(IntentDeliveryService.booleanValue(null));
    }

    @Test
    void 下发前把VLAN提到最前Enable放到最后() {
        String base = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.6.";
        List<PlanItem> items = List.of(
                new PlanItem("Internet_WAN", "enable", base + "Enable", "true", "boolean"),
                new PlanItem("Internet_WAN", "conn_type", base + "ConnectionType", "IP_Routed", "string"),
                new PlanItem("Internet_WAN", "addr_type", base + "AddressingType", "DHCP", "string"),
                new PlanItem("Internet_WAN", "nat", base + "NATEnabled", "true", "boolean"),
                new PlanItem("Internet_WAN", "vlan_id", base + "X_CMCC_VLANIDMark", "100", "int"));

        List<String> order = IntentDeliveryService.orderByDependency(items).stream()
                .map(PlanItem::bizField).toList();

        assertEquals(List.of("vlan_id", "conn_type", "addr_type", "nat", "enable"), order,
                "VLAN 先写才能解锁新建实例上的其余参数，Enable 必须等连接配齐才被接受");
    }

    @Test
    void 业务绑定紧跟VLAN其余按原序Enable仍在最后() {
        String base = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.6.";
        List<PlanItem> items = List.of(
                new PlanItem("Internet_WAN", "enable", base + "Enable", "true", "boolean"),
                new PlanItem("Internet_WAN", "conn_type", base + "ConnectionType", "IP_Routed", "string"),
                new PlanItem("Internet_WAN", "nat", base + "NATEnabled", "true", "boolean"),
                new PlanItem("Internet_WAN", "service_list", base + "X_CMCC_ServiceList",
                        "INTERNET", "string"),
                new PlanItem("Internet_WAN", "vlan_id", base + "X_CMCC_VLANIDMark", "100", "int"));

        List<String> order = IntentDeliveryService.orderByDependency(items).stream()
                .map(PlanItem::bizField).toList();

        assertEquals(List.of("vlan_id", "service_list", "conn_type", "nat", "enable"), order,
                "先 VLAN 解锁实例、再绑业务，最后才允许 Enable");
    }

    @Test
    void 重排只调顺序不增删参数() {
        String base = "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.";
        List<PlanItem> items = List.of(
                new PlanItem("WiFi_2G", "enable", base + "Enable", "true", "boolean"),
                new PlanItem("WiFi_2G", "ssid", base + "SSID", "room101", "string"));

        List<PlanItem> ordered = IntentDeliveryService.orderByDependency(items);

        assertEquals(2, ordered.size());
        assertEquals(Set.of("enable", "ssid"),
                ordered.stream().map(PlanItem::bizField).collect(java.util.stream.Collectors.toSet()));
        assertEquals("ssid", ordered.get(0).bizField());
        assertEquals("enable", ordered.get(1).bizField());
    }
}

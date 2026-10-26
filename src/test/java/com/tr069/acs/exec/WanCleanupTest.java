package com.tr069.acs.exec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAN 清理规则。这里是「会决定设备脱不脱管、业务剩几条」的逻辑，每条用例都必须成立。
 *
 * <p>当前策略：<strong>先删后建，一个批次只删一次</strong>。
 * 判定只看传入的存量快照，不发 RPC，所以用例全部是纯函数验证。</p>
 *
 * <p>参数名取自移动定制机型的真实写法 {@code X_CMCC_ServiceList}，顺带验证电信、联通、
 * 以及标准 {@code ServiceList} 也能认出来——业务类型判定不能只认某一家运营商的前缀。</p>
 */
class WanCleanupTest {

    private static final String P9 = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.9.";
    private static final String P10 = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.10.";
    private static final String P11 = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.11.";

    /** 移动前缀的写法 */
    private static WanCleanup.WanLink link(String path, String serviceList) {
        return new WanCleanup.WanLink(path, Map.of("X_CMCC_ServiceList", serviceList));
    }

    @Test
    void 只保留管理通道其余存量连接都要删() {
        WanCleanup.Plan plan = WanCleanup.plan(List.of(
                link(P9, "TR069"),
                link(P10, "INTERNET"),
                link(P11, "VOIP")));

        assertEquals(1, plan.keep().size(), "TR069 管理通道保留");
        assertTrue(plan.keep().get(0).path().contains(".9."));
        assertEquals(2, plan.delete().size(), "两条业务连接属于存量残留，清理掉再重建");
    }

    @Test
    void 本次已新建的连接不进删除清单() {
        // 第二条业务开工时，第一条建好的连接已经进了存量快照。
        // 它必须被认出来是本批次新建的，否则就是「下发两条只剩一条」那个 BUG
        WanCleanup.Plan plan = WanCleanup.plan(List.of(
                        link(P9, "TR069"),
                        link(P10, "INTERNET")),
                null, Set.of(P10));

        assertEquals(2, plan.keep().size());
        assertTrue(plan.delete().isEmpty(), "本次新建的连接一条都不许删");
    }

    @Test
    void 新建连接的参数路径同样受保护() {
        WanCleanup.Plan plan = WanCleanup.plan(List.of(
                        link(P9, "TR069"),
                        link(P10, "INTERNET")),
                null, Set.of(P10));

        assertTrue(plan.delete().isEmpty());
        // 前缀匹配：快照里可能是连接下的某个参数路径，不是连接对象本身
        WanCleanup.Plan byLeaf = WanCleanup.plan(List.of(
                        link(P9, "TR069"),
                        link(P10 + "Enable", "INTERNET")),
                null, Set.of(P10));
        assertTrue(byLeaf.delete().isEmpty(), "路径前缀命中保护名单即视为本次新建");
    }

    @Test
    void 认不出管理通道时一条都不删() {
        WanCleanup.Plan plan = WanCleanup.plan(List.of(link(P9, "INTERNET"), link(P10, "VOIP")));

        assertEquals(2, plan.keep().size());
        assertTrue(plan.delete().isEmpty(),
                "认不出管理通道就全留着：误删管理通道设备直接脱管，比旧连接抢路由严重得多");
    }

    @Test
    void managementServer指向的连接也算管理通道() {
        // 有的设备 ServiceList 为空，只在 ManagementServer 里写了挂哪条连接
        WanCleanup.Plan bySegment = WanCleanup.plan(List.of(
                new WanCleanup.WanLink(P9, Map.of()),
                link(P10, "INTERNET")), "WANIPConnection.9.", Set.of());
        assertEquals(1, bySegment.keep().size(), "按连接片段认出管理通道");
        assertEquals(P9, bySegment.keep().get(0).path());
        assertEquals(1, bySegment.delete().size());

        // 还有的只给实例号
        WanCleanup.Plan byNumber = WanCleanup.plan(List.of(
                new WanCleanup.WanLink(P9, Map.of()),
                link(P10, "INTERNET")), "9", Set.of());
        assertEquals(P9, byNumber.keep().get(0).path(), "按实例号认出管理通道");
    }

    @Test
    void 实例号只按号比不会把19认成9() {
        String p19 = "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.19.";
        assertFalse(WanCleanup.matchesManagement(p19, "9"), "纯数字线索必须整段比对实例号");
        assertTrue(WanCleanup.matchesManagement(p19, "19"));
        assertEquals(19, WanCleanup.instanceNumberOf(p19));
    }

    @Test
    void 一条连接都没有也不报错() {
        assertTrue(WanCleanup.plan(List.of()).keep().isEmpty(), "空列表");
        assertTrue(WanCleanup.plan(null).keep().isEmpty(), "null 也不抛异常");
        assertTrue(WanCleanup.plan(null).delete().isEmpty());
    }

    @Test
    void 各家运营商私有前缀都能认出管理通道() {
        assertTrue(WanCleanup.plan(List.of(
                new WanCleanup.WanLink(P9, Map.of("ServiceList", "TR069")),
                link(P10, "INTERNET"))).delete().stream()
                .noneMatch(l -> l.path().contains(".9.")), "标准 ServiceList");
        assertTrue(WanCleanup.plan(List.of(
                new WanCleanup.WanLink(P9, Map.of("X_CT-COM_ServiceList", "TR069")),
                link(P10, "INTERNET"))).delete().stream()
                .noneMatch(l -> l.path().contains(".9.")), "电信前缀");
        assertTrue(WanCleanup.plan(List.of(
                new WanCleanup.WanLink(P9, Map.of("X_CU_ServiceList", "TR069")),
                link(P10, "INTERNET"))).delete().stream()
                .noneMatch(l -> l.path().contains(".9.")), "联通前缀");
    }

    @Test
    void 有多条管理通道时全部保留() {
        WanCleanup.Plan plan = WanCleanup.plan(List.of(
                link(P9, "TR069"),
                link(P10, "TR069"),
                link(P11, "INTERNET")));

        assertEquals(2, plan.keep().size(), "两条管理通道都留着");
        assertEquals(1, plan.delete().size());
    }

    @Test
    void 业务类型认不出的连接按存量处理() {
        WanCleanup.Plan plan = WanCleanup.plan(List.of(
                link(P9, "TR069"),
                new WanCleanup.WanLink("WANIPConnection.10.", Map.of())));

        assertEquals(1, plan.keep().size());
        assertEquals(1, plan.delete().size(), "认不出业务类型不等于管理通道，按存量残留清掉");
        assertEquals("-", WanCleanup.serviceOf(plan.delete().get(0)), "认不出的仍标为 '-'");
    }
}

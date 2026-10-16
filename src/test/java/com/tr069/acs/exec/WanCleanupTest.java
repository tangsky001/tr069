package com.tr069.acs.exec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAN 清理规则。这里是「会决定设备脱不脱管」的逻辑，每条用例都必须成立。
 *
 * <p>参数名取自移动定制机型的真实写法 {@code X_CMCC_ServiceList}，顺带验证电信、联通、
 * 以及标准 {@code ServiceList} 也能认出来——判定不能只认某一家运营商的前缀。</p>
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
    void tr069那条原样保留其余全部删掉() {
        WanCleanup.Plan plan = WanCleanup.plan(List.of(
                link(P9, "TR069"),
                link(P10, "INTERNET"),
                link(P11, "VOIP")));

        assertEquals(1, plan.keep().size(), "只有 TR069 管理通道保留");
        assertTrue(plan.keep().get(0).path().contains(".9."), "保留的必须是 TR069 那条");
        assertEquals(2, plan.delete().size(), "INTERNET 与 VOIP 都要删");
        assertTrue(plan.delete().stream().noneMatch(l -> l.path().equals(P9)),
                "TR069 那条绝不能出现在删除列表里");
    }

    @Test
    void 认不出tr069就整体中止不删任何一条() {
        WanCleanup.Tr069NotFound err = assertThrows(WanCleanup.Tr069NotFound.class,
                () -> WanCleanup.plan(List.of(link(P9, "INTERNET"), link(P10, "VOIP"))),
                "没识别出管理通道时必须中止，不能继续删");

        assertEquals(2, err.total());
        assertTrue(err.getMessage().contains("TR069"), "错误信息要说清是没认出管理通道");
    }

    @Test
    void 一条连接都没有也中止() {
        assertThrows(WanCleanup.Tr069NotFound.class, () -> WanCleanup.plan(List.of()));
        assertThrows(WanCleanup.Tr069NotFound.class, () -> WanCleanup.plan(null));
    }

    @Test
    void 各家运营商私有前缀都能认出管理通道() {
        assertEquals(1, WanCleanup.plan(List.of(
                new WanCleanup.WanLink(P9, Map.of("ServiceList", "TR069")))).keep().size(),
                "标准 ServiceList");
        assertEquals(1, WanCleanup.plan(List.of(
                new WanCleanup.WanLink(P9, Map.of("X_CT-COM_ServiceList", "TR069")))).keep().size(),
                "电信前缀");
        assertEquals(1, WanCleanup.plan(List.of(
                new WanCleanup.WanLink(P9, Map.of("X_CU_ServiceList", "TR069")))).keep().size(),
                "联通前缀");
    }

    @Test
    void 有多条管理通道时全部保留() {
        WanCleanup.Plan plan = WanCleanup.plan(List.of(
                link(P9, "TR069"),
                link(P10, "TR069"),
                link(P11, "INTERNET")));

        assertEquals(2, plan.keep().size());
        assertEquals(1, plan.delete().size());
    }

    @Test
    void 业务类型认不出的连接也会被删掉() {
        // 用不带任何业务关键字的路径构造「真·认不出」的场景。
        // 注意真实 root 多为 InternetGatewayDevice，光靠路径会被推断成 INTERNET——
        // 那是既有行为且无害（管理通道靠 ServiceList 认，且无论推断成什么都会被删），
        // 这里单独构造干净路径验证 UNKNOWN 也归入删除。
        WanCleanup.Plan plan = WanCleanup.plan(List.of(
                link(P9, "TR069"),
                new WanCleanup.WanLink("WANIPConnection.10.", Map.of())));

        assertEquals(1, plan.delete().size(), "认不出业务类型的连接按非管理通道处理，一并清理");
        assertEquals("-", WanCleanup.serviceOf(plan.delete().get(0)));
    }
}

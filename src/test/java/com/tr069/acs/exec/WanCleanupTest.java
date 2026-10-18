package com.tr069.acs.exec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAN 清理规则。这里是「会决定设备脱不脱管」的逻辑，每条用例都必须成立。
 *
 * <p>当前策略：下发 WAN 配置时不删除任何已有连接——全部原样保留，只改参数。
 * 所以下面每条用例的共同结论都是「{@code delete} 为空」。</p>
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
    void 已有连接全部保留不删任何一条() {
        WanCleanup.Plan plan = WanCleanup.plan(List.of(
                link(P9, "TR069"),
                link(P10, "INTERNET"),
                link(P11, "VOIP")));

        assertEquals(3, plan.keep().size(), "现有连接一条都不删");
        assertTrue(plan.delete().isEmpty(), "清理列表必须为空");
        assertTrue(plan.keep().stream().anyMatch(l -> l.path().contains(".9.")),
                "TR069 那条仍在保留列表里");
    }

    @Test
    void 认不出tr069也不删任何一条() {
        WanCleanup.Plan plan = WanCleanup.plan(List.of(link(P9, "INTERNET"), link(P10, "VOIP")));

        assertEquals(2, plan.keep().size());
        assertTrue(plan.delete().isEmpty(),
                "不删连接就不需要判定管理通道，也不该因为没认出来就中止下发");
    }

    @Test
    void 一条连接都没有也不报错() {
        assertTrue(WanCleanup.plan(List.of()).keep().isEmpty(), "空列表");
        assertTrue(WanCleanup.plan(null).keep().isEmpty(), "null 也不抛异常");
        assertTrue(WanCleanup.plan(null).delete().isEmpty());
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

        assertEquals(3, plan.keep().size());
        assertTrue(plan.delete().isEmpty());
    }

    @Test
    void 业务类型认不出的连接同样保留() {
        // 用不带任何业务关键字的路径构造「真·认不出」的场景。
        // 注意真实 root 多为 InternetGatewayDevice，光靠路径会被推断成 INTERNET，
        // 所以这里单独构造干净路径验证 UNKNOWN 也归入保留。
        WanCleanup.Plan plan = WanCleanup.plan(List.of(
                link(P9, "TR069"),
                new WanCleanup.WanLink("WANIPConnection.10.", Map.of())));

        assertEquals(2, plan.keep().size());
        assertTrue(plan.delete().isEmpty(), "认不出业务类型照样保留，不再清理");
        assertEquals("-", WanCleanup.serviceOf(plan.keep().get(1)), "认不出的仍标为 '-'");
    }
}

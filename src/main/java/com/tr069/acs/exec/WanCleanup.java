package com.tr069.acs.exec;

import com.tr069.acs.cwmp.WanServiceClassifier;

import java.util.List;
import java.util.Map;

/**
 * 下发业务 WAN 前的连接清理。
 *
 * <h3>当前策略：一条都不删</h3>
 * <p>早期实现会保留 TR069 管理通道、删掉其余 WAN 连接，理由是设备出厂或上次配置残留的
 * 连接会和新建的业务连接抢默认路由、争同一个 VLAN。但现场要求下发时不得动已有连接
 * （工单下发要保证所有 WAN 都还在），所以现在 {@link #plan} 固定返回「全部保留、不删除」，
 * 只由 {@code SetParameterValues} 改参数；清理旧连接改由专门的清理动作或人工处理。</p>
 *
 * <p>既然不删，也就不需要再判定哪条是管理通道：{@link #plan} 不再抛
 * {@link Tr069NotFound}，下发不会因「没认出 TR069」而中止。</p>
 *
 * <p>下面保留的判定方法（{@link #isBusy}、{@link #serviceOf}、
 * {@link WanServiceClassifier}）仍用于日志里说明每条连接的业务与占用情况，
 * 将来若恢复清理策略，把 {@link #plan} 改回按业务类型分流即可。</p>
 */
public final class WanCleanup {

    private WanCleanup() {
    }

    /**
     * 设备上的一条 WAN 连接。
     *
     * @param path   对象路径，形如 {@code ...WANConnectionDevice.1.WANIPConnection.9.}
     * @param params 该连接的参数，用于判业务类型（含厂商私有的 ServiceList、Name）
     */
    public record WanLink(String path, Map<String, String> params) {
    }

    /**
     * 清理方案。
     *
     * @param keep   TR069 管理通道，务必原样保留
     * @param delete 其余连接，本次要逐个 DeleteObject 删掉
     */
    public record Plan(List<WanLink> keep, List<WanLink> delete) {
    }

    /**
     * 挑出要保留与要删除的连接。
     *
     * <p>当前业务策略：下发 WAN 配置时不自动删除任何已有连接。
     * 原有逻辑会删掉除 TR069 管理通道以外的所有 WAN 连接，但现场要求保留所有
     * 已有连接，只通过 {@code SetParameterValues} 修改参数；清理旧连接改由
     * 专门的清理接口或人工处理。因此本方法永远返回「全部保留、不删除」。</p>
     *
     * @param links 设备上现有的全部 WAN 连接
     */
    public static Plan plan(List<WanLink> links) {
        List<WanLink> keep = links == null ? List.of() : List.copyOf(links);
        return new Plan(keep, List.of());
    }

    /**
     * 这条连接是否正在承载业务（被占用）。
     *
     * <p>判据只看设备自己报的状态：已连通，或已启用且拿到了外网地址。
     * 出厂残留的空闲连接这两条都不满足，那才是该清掉的；
     * 正在用的删不动（设备回 9002），留着也不会抢路由——它本来就在跑。</p>
     */
    public static boolean isBusy(WanLink link) {
        if (link == null) {
            return false;
        }
        String status = param(link.params(), "ConnectionStatus");
        if (status != null && status.equalsIgnoreCase("Connected")) {
            return true;
        }
        String enable = param(link.params(), "Enable");
        String ip = param(link.params(), "ExternalIPAddress");
        return "1".equals(enable) && ip != null && !ip.isBlank() && !"0.0.0.0".equals(ip);
    }

    /**
     * 按参数名取参数值。快照里的 key 可能是全路径，也可能只是末段，两种都认。
     */
    private static String param(Map<String, String> params, String name) {
        if (params == null) {
            return null;
        }
        String hit = params.get(name);
        if (hit != null) {
            return hit;
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            String key = e.getKey();
            if (key != null && key.endsWith("." + name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 提取某条连接的业务类型，步骤记录里用它写清「为什么删」。 */
    public static String serviceOf(WanLink link) {
        return WanServiceClassifier.classify(link.params(), link.path());
    }

    /**
     * 没能识别出 TR069 管理通道。
     *
     * <p>当前「一条都不删」的策略下 {@link #plan} 不会抛它，保留是为了将来恢复清理策略时
     * 直接复用：那时认不出管理通道就必须整体中止，不得继续删连接。</p>
     */
    public static class Tr069NotFound extends RuntimeException {

        private final int total;

        public Tr069NotFound(int total) {
            super("未识别到 TR069 管理通道，中止下发以防设备脱管"
                    + "（已读取 " + total + " 条 WAN 连接，均未判定为管理通道）");
            this.total = total;
        }

        /** 当时读到的连接总数，便于排查是没采到参数还是型号不适用。 */
        public int total() {
            return total;
        }
    }
}

package com.tr069.acs.exec;

import com.tr069.acs.cwmp.WanServiceClassifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 下发业务 WAN 前的连接清理：保留 TR069 管理通道，删掉其余 WAN 连接。
 *
 * <h3>为什么必须先清一遍</h3>
 * <p>设备出厂或上次配置残留的 WAN 连接如果留着，新下发的业务连接会和它们抢默认路由、
 * 争同一个 VLAN，表现为「配置下发成功但上网不通」。所以下发前统一清干净再按模板重建。</p>
 *
 * <h3>为什么 TR069 那条绝对不能删</h3>
 * <p>TR069 管理通道是设备与 ACS 通信的命脉，删掉它设备就再也连不上来，
 * 只能上门处理。所以这里把它挑出来单独保留。</p>
 *
 * <h3>认不出来时的处置</h3>
 * <p>如果一台 TR069 通道都没识别出来，说明这套判定在该型号上不适用（或参数没采到），
 * 此时继续删除等同于赌博。约定是<b>整个下发中止</b>——宁可本次下发失败，也不冒脱管的险。
 * 失败会带着明确原因写进工单，运维一看就知道是识别问题，而不是配置问题。</p>
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
     * @param links 设备上现有的全部 WAN 连接
     * @throws Tr069NotFound 一条 TR069 都没认出来，调用方必须中止下发
     */
    public static Plan plan(List<WanLink> links) {
        List<WanLink> keep = new ArrayList<>();
        List<WanLink> delete = new ArrayList<>();
        if (links != null) {
            for (WanLink link : links) {
                // 管理通道不能碰；正在承载业务的也不能碰——设备上正在用的连接
                // 删不掉（实测回 9002 Internal error），强行删只会让下发失败
                if (WanServiceClassifier.isTr069(link.params(), link.path()) || isBusy(link)) {
                    keep.add(link);
                } else {
                    delete.add(link);
                }
            }
        }
        // 设备能通过 TR069 上报上来，管理通道必然存在；认不出就说明判定没生效
        // （或参数没采到），此时删任何一条都可能删掉命脉，直接中止。
        // 注意只看「保留里有没有管理通道」：占用中的业务连接也会被留下来，
        // 但它们不能替代管理通道，光有它们仍说明没认出命脉在哪。
        boolean hasTr069 = keep.stream()
                .anyMatch(link -> WanServiceClassifier.isTr069(link.params(), link.path()));
        if (!hasTr069) {
            throw new Tr069NotFound(links == null ? 0 : links.size());
        }
        return new Plan(List.copyOf(keep), List.copyOf(delete));
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
     * 没能识别出 TR069 管理通道。抛出后下发必须整体中止，不得继续删连接。
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

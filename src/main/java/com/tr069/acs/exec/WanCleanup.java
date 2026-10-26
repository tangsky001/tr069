package com.tr069.acs.exec;

import com.tr069.acs.cwmp.WanServiceClassifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 下发业务 WAN 前的连接清理。
 *
 * <h3>策略：先删后建，一个批次只删一次</h3>
 * <p>顺序固定为「清点存量 → 删掉该删的 → AddObject 新建 → 按响应返回的实例号写参数」。
 * 反过来（先建后删）必然出事：批量下发 Internet + VoIP 时，第二条业务开工的清理会把
 * 第一条刚建好的连接当成「出厂残留」删掉，最后设备上只剩 TR069 和最后下发的那一条。
 * 所以删除只能发生在任何 AddObject 之前，并且一个批次只做一次——批次判定由
 * {@code IntentDeliveryService} 的清理台账负责，本类只负责「给定存量快照，判谁该删」。</p>
 *
 * <h3>保留哪些</h3>
 * <ul>
 *   <li><strong>TR069 管理通道</strong>：按 {@code ServiceList} 的业务标识认，或按
 *       {@code ManagementServer} 指向的那条连接认。两条路都不看实例号——出厂实例号
 *       会漂移，按「第几个实例」认迟早把管理通道删掉，设备直接脱管。</li>
 *   <li><strong>本次批次已经新建出来的连接</strong>：由调用方通过 {@code protectedPaths}
 *       传进来。下发完成会补采快照，它们此刻可能已经出现在存量里，但绝不能删。</li>
 * </ul>
 *
 * <h3>认不出管理通道时：一条都不删</h3>
 * <p>判不出来就全留着：宁可旧连接和新业务抢路由（业务起不来，可重下），
 * 也不能把设备搞脱管（只能上门）。此时 {@link #plan} 返回的删除清单为空，
 * 调用方照常继续下发，不停单。</p>
 *
 * <p>本次要新建的业务连接此刻还不存在，自然不在存量快照里，
 * 也就不会进删除判断——不需要调用方额外传「本次要开哪些业务」。</p>
 */
public final class WanCleanup {

    /** 连接对象的末段，形如 {@code WANIPConnection.9.}，用它取实例号。 */
    private static final Pattern WAN_CONNECTION = Pattern.compile("WAN(?:IP|PPP)Connection\\.(\\d+)\\.");

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
     * @param keep   TR069 管理通道与本次已新建的连接，务必原样保留
     * @param delete 存量里其余的连接，本次要逐个 DeleteObject 删掉
     * @param reason 为什么这么分，写进日志与工单备注
     */
    public record Plan(List<WanLink> keep, List<WanLink> delete, String reason) {

        public Plan(List<WanLink> keep, List<WanLink> delete) {
            this(keep, delete, "");
        }
    }

    /** 只有存量快照、没有管理通道线索、也没有要保护的新建连接时的简版入口。 */
    public static Plan plan(List<WanLink> links) {
        return plan(links, null, Set.of());
    }

    /**
     * 挑出要保留与要删除的连接。只看传入的存量快照，不发 RPC、不猜实例号。
     *
     * @param links           设备上现有的全部 WAN 连接（存量快照）
     * @param managementHint  {@code ManagementServer} 指向的那条连接，可能是
     *                        {@code WANIPConnection.9.} 这样的片段，也可能只是实例号
     *                        {@code 9}；没有线索传 null
     * @param protectedPaths  本次批次已经新建出来的连接路径前缀，一条都不许删
     */
    public static Plan plan(List<WanLink> links, String managementHint, Set<String> protectedPaths) {
        List<WanLink> all = links == null ? List.of() : List.copyOf(links);
        if (all.isEmpty()) {
            return new Plan(all, List.of(), "没有读到存量 WAN 连接，无需清理");
        }
        Set<String> guard = protectedPaths == null ? Set.of() : protectedPaths;
        List<WanLink> keep = new ArrayList<>();
        List<WanLink> victim = new ArrayList<>();
        boolean managementSeen = false;
        boolean createdHere = false;
        for (WanLink link : all) {
            boolean management = isManagement(link, managementHint);
            boolean created = createdByThisBatch(link.path(), guard);
            managementSeen |= management;
            createdHere |= created;
            if (management || created) {
                keep.add(link);
            } else {
                victim.add(link);
            }
        }
        if (!managementSeen) {
            // 一条 TR069 都没认出来：删谁都可能删到管理通道，全留着继续下发
            return new Plan(all, List.of(),
                    "未认出 TR069 管理通道，本次一条都不删（防止设备脱管）");
        }
        String reason = "保留 TR069 管理通道"
                + (createdHere ? "与本次已新建的连接" : "")
                + "，删除其余 " + victim.size() + " 条存量连接";
        return new Plan(List.copyOf(keep), List.copyOf(victim), reason);
    }

    /**
     * 这条连接是不是管理通道：业务标识认一次，{@code ManagementServer} 的指向再认一次。
     *
     * <p>两路判据是或的关系：有的设备 ServiceList 为空、只在 ManagementServer 里写了
     * 挂在哪个连接上；有的设备反过来。只认一路就会漏，漏了就是误删管理通道。</p>
     */
    public static boolean isManagement(WanLink link, String managementHint) {
        if (link == null) {
            return false;
        }
        return WanServiceClassifier.isTr069(link.params(), link.path())
                || matchesManagement(link.path(), managementHint);
    }

    /**
     * 连接路径与管理通道线索是否对得上。
     *
     * <p>线索可能是完整片段（{@code WANIPConnection.9.}）也可能只是实例号（{@code 9}）。
     * 纯数字只按实例号比：直接 {@code contains("9")} 会把 {@code .19.} 也认成管理通道。</p>
     */
    public static boolean matchesManagement(String path, String hint) {
        if (path == null || hint == null || hint.isBlank()) {
            return false;
        }
        String text = hint.trim();
        if (text.matches("\\d+")) {
            Integer own = instanceNumberOf(path);
            return own != null && own == Integer.parseInt(text);
        }
        if (path.contains(text)) {
            return true;
        }
        Integer hintInstance = instanceNumberOf(text);
        Integer own = instanceNumberOf(path);
        return hintInstance != null && own != null && hintInstance.equals(own);
    }

    /** 路径里的连接实例号，形如 {@code WANIPConnection.9.} 取 9；认不出返回 null。 */
    public static Integer instanceNumberOf(String path) {
        if (path == null) {
            return null;
        }
        Matcher m = WAN_CONNECTION.matcher(path);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 文本里那段连接片段（{@code WANIPConnection.9.}），没有就返回 null。
     *
     * <p>{@code ManagementServer} 的参数值有的写完整路径、有的只写这一段、
     * 有的只给实例号，调用方靠这个把前两种统一取出来。</p>
     */
    public static String connectionSegmentOf(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = WAN_CONNECTION.matcher(text);
        return m.find() ? m.group(0) : null;
    }

    /** 这条连接是不是本次批次刚建出来的（在保护名单里）。 */
    private static boolean createdByThisBatch(String path, Set<String> protectedPaths) {
        if (path == null || protectedPaths == null || protectedPaths.isEmpty()) {
            return false;
        }
        if (protectedPaths.contains(path)) {
            return true;
        }
        for (String guard : protectedPaths) {
            if (guard != null && !guard.isBlank() && path.startsWith(guard)) {
                return true;
            }
        }
        return false;
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
}

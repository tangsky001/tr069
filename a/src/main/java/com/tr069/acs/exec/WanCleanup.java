package com.tr069.acs.exec;

import com.tr069.acs.cwmp.WanServiceClassifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 下发业务 WAN 前的连接清理 */
public final class WanCleanup {

    /** 连接对象的末段，形如 {@code WANIPConnection.9.}，用它取实例号 */
    private static final Pattern WAN_CONNECTION = Pattern.compile("WAN(?:IP|PPP)Connection\\.(\\d+)\\.");

    private WanCleanup() {
    }

    /** 设备上的一条 WAN 连接 */
    public record WanLink(String path, Map<String, String> params) {
    }

    /** 清理方案 */
    public record Plan(List<WanLink> keep, List<WanLink> delete, String reason) {

        public Plan(List<WanLink> keep, List<WanLink> delete) {
            this(keep, delete, "");
        }
    }

    /** 只有存量快照、没有管理通道线索、也没有要保护的新建连接时的简版入口 */
    public static Plan plan(List<WanLink> links) {
        return plan(links, null, Set.of());
    }

    /** 挑出要保留与要删除的连接。只看传入的存量快照，不发 RPC、不猜实例号 */
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

    /** 这条连接是不是管理通道：业务标识认一次，{@code ManagementServer} 的指向再认一次 */
    public static boolean isManagement(WanLink link, String managementHint) {
        if (link == null) {
            return false;
        }
        return WanServiceClassifier.isTr069(link.params(), link.path())
                || matchesManagement(link.path(), managementHint);
    }

    /** 连接路径与管理通道线索是否对得上 */
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

    /** 路径里的连接实例号，形如 {@code WANIPConnection.9.} 取 9；认不出返回 null */
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

    /** 文本里那段连接片段（{@code WANIPConnection.9.}），没有就返回 null */
    public static String connectionSegmentOf(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = WAN_CONNECTION.matcher(text);
        return m.find() ? m.group(0) : null;
    }

    /** 这条连接是不是本次批次刚建出来的（在保护名单里） */
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

    /** 这条连接是否正在承载业务（被占用） */
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

    /** 按参数名取参数值。快照里的 key 可能是全路径，也可能只是末段，两种都认 */
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

    /** 提取某条连接的业务类型，步骤记录里用它写清「为什么删」 */
    public static String serviceOf(WanLink link) {
        return WanServiceClassifier.classify(link.params(), link.path());
    }
}

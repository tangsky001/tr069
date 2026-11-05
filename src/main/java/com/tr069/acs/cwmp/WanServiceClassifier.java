package com.tr069.acs.cwmp;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** WAN 连接的业务类型判定：TR069 / INTERNET / VOIP / IPTV，认不出返回 {@code "-"} */
public final class WanServiceClassifier {

    /** TR069 管理通道，删掉它设备就再也连不上 ACS */
    public static final String TR069 = "TR069";

    /** 未识别出来 */
    public static final String UNKNOWN = "-";

    /** 业务类型关键字，按优先级判定：TR069 管理通道优先于上网/语音/IPTV */
    private static final List<String> SERVICE_KEYWORDS = List.of("TR069", "VOIP", "VOICE", "IPTV", "INTERNET");

    private WanServiceClassifier() {
    }

    /** 一条 WAN 连接的业务类型 */
    public static String classify(Map<String, String> params, String path) {
        if (params == null || params.isEmpty()) {
            return classifyText(stripRoot(path));
        }
        String declared = declaredService(params);
        if (!declared.isBlank() && !UNKNOWN.equals(declared)) {
            // 设备自己申报了业务类型就认它：TR069 / INTERNET / VOIP / IPTV / OTHER 都是合法值，
            // 认不出的也原样回显，绝不能再拿路径去猜
            return declared;
        }
        String text = params.getOrDefault("Name", "") + " " + stripRoot(path);
        return classifyText(text);
    }

    private static String declaredService(Map<String, String> params) {
        String declared = params.get("ServiceList");
        if (declared == null || declared.isBlank()) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String key = entry.getKey();
                if (key != null && key.toLowerCase(Locale.ROOT).contains("servicelist")
                        && entry.getValue() != null && !entry.getValue().isBlank()) {
                    declared = entry.getValue();
                    break;
                }
            }
        }
        if (declared == null || declared.isBlank()) {
            return "";
        }
        // 多值（如 INTERNET,TR069）按关键字优先级挑一个；单个值原样大写回显
        String mixed = declared.trim();
        String byKeyword = classifyText(mixed);
        if (!UNKNOWN.equals(byKeyword)) {
            return byKeyword;
        }
        String first = mixed.split("[,，、+/\\s]+")[0].trim().toUpperCase(Locale.ROOT);
        return first;
    }

    /** 去掉数据模型根节点，避免根名本身命中关键字 */
    private static String stripRoot(String path) {
        if (path == null) {
            return "";
        }
        String p = path;
        for (String root : ROOTS) {
            if (p.regionMatches(true, 0, root, 0, root.length())) {
                p = p.substring(root.length());
                break;
            }
        }
        return p;
    }

    /** TR-098 / TR-181 的根节点写法 */
    private static final List<String> ROOTS = List.of("InternetGatewayDevice.", "Device.");

    /** 是否为 TR069 管理通道。下发清理靠它决定「哪条绝对不能删」 */
    public static boolean isTr069(Map<String, String> params, String path) {
        return TR069.equalsIgnoreCase(classify(params, path));
    }

    private static String classifyText(String text) {
        if (text == null || text.isBlank()) {
            return UNKNOWN;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        for (String keyword : SERVICE_KEYWORDS) {
            if (upper.contains(keyword)) {
                return "VOICE".equals(keyword) ? "VOIP" : keyword;
            }
        }
        return UNKNOWN;
    }
}

package com.tr069.acs.cwmp;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WAN 连接的业务类型判定：TR069 / INTERNET / VOIP / IPTV，认不出返回 {@code "-"}。
 *
 * <p>判定只用一处，下发清理和设备画像都调这里：两套判断一旦各写一份，
 * 「哪条是 TR069 管理通道」就会漂移，而這是会导致设备脱管的判断，
 * 必须保证下发删连接时和设备详情页看到的结论完全一致。</p>
 *
 * <h3>为什么按「键名含 ServiceList」匹配</h3>
 * <p>光猫上通常有多条 WAN 连接（INTERNET / TR069 / VOIP / IPTV），设备真正与 ACS
 * 通信的是 TR069 通道。区分它们靠 {@code ServiceList} 参数，但厂商私有前缀各家不同
 * （电信 {@code X_CT-COM_}、移动 {@code X_CMCC_}、联通 {@code X_CU_}），
 * 写死某一家的 key 在别的运营商机型上就取不到。所以按「键名含 ServiceList」匹配，
 * 新厂商不用改代码；都没有时再按连接名与路径推断。</p>
 */
public final class WanServiceClassifier {

    /** TR069 管理通道，删掉它设备就再也连不上 ACS。 */
    public static final String TR069 = "TR069";

    /** 未识别出来 */
    public static final String UNKNOWN = "-";

    /** 业务类型关键字，按优先级判定：TR069 管理通道优先于上网/语音/IPTV。 */
    private static final List<String> SERVICE_KEYWORDS = List.of("TR069", "VOIP", "VOICE", "IPTV", "INTERNET");

    private WanServiceClassifier() {
    }

    /**
     * 一条 WAN 连接的业务类型。
     *
     * @param params 该连接的参数（含厂商私有的 ServiceList、Name 等）
     * @param path   连接的对象路径，兜底时作为推断依据
     */
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

    /**
     * 去掉数据模型根节点，避免根名本身命中关键字。
     *
     * <p>{@code InternetGatewayDevice} 含 INTERNET、{@code Device.} 无影响，但一致处理更省心。</p>
     */
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

    /** TR-098 / TR-181 的根节点写法。 */
    private static final List<String> ROOTS = List.of("InternetGatewayDevice.", "Device.");

    /**
     * 是否为 TR069 管理通道。下发清理靠它决定「哪条绝对不能删」。
     */
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

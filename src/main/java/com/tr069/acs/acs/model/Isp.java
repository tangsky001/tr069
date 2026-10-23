package com.tr069.acs.acs.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 运营商：移动 / 电信 / 联通 / 广电，以及未归类时的通用档。
 *
 * <p>四家运营商的定制机是同一套硬件、不同的固件：CWMP 节点名带各自的私有前缀
 * （移动 {@code X_CMCC_}、电信 {@code X_CT-COM_}、联通 {@code X_CU_}），
 * 连「能不能递归拉参数树」这种基础能力都不一样——电信定制机对
 * {@code GetParameterNames(InternetGatewayDevice.WANDevice.)} 直接回 9002 Internal error，
 * 只能带实例号逐个问。所以采集标准（子树与兜底策略）必须按运营商分开。</p>
 *
 * <p>设备台账的 {@code isp} 是人工适配时申报的，采集发生时往往还是空的；
 * 空值一律落到 {@link #STANDARD}，按通用档采集，采到私有前缀后再回写台账。</p>
 */
public enum Isp {

    /** 中国移动。 */
    CMCC("CMCC", "中国移动", "CM", "X_CMCC_", ""),

    /**
     * 中国电信。
     *
     * <p>两套私有前缀：{@code X_CT-COM_} 是标准扩展（SIP / VLAN 这些业务参数就用它），
     * {@code X_CTC_} 是天翼那一套（天翼插件、ITMS 探针、天翼管家、DPI 软探针、业务虚拟对象），
     * 固件实现得很脆——参数树一遍历进去就抛 9002。</p>
     */
    CT("CT", "中国电信", "CT", "X_CT-COM_", "X_CTC_"),

    /** 中国联通。 */
    CU("CU", "中国联通", "CU", "X_CU_", ""),

    /** 中国广电。 */
    CBN("CBN", "中国广电", "CBN", "X_CBN_", ""),

    /** 通用 / 未归类：台账还没登记运营商的设备都走这一档。 */
    STANDARD("standard", "通用", "standard", "", "");

    /** 台账与配置里写的运营商代码。 */
    private final String code;

    /** 展示用中文名。 */
    private final String label;

    /** 型号档案目录名：移动 CM / 电信 CT / 联通 CU / 广电 CBN。 */
    private final String dir;

    /** 该运营商定制机的 CWMP 私有参数前缀，用于从能力树反查运营商。 */
    private final String prefix;

    /** 同一家运营商的第二套私有前缀（电信 {@code X_CTC_}），没有则为空。 */
    private final String altPrefix;

    Isp(String code, String label, String dir, String prefix, String altPrefix) {
        this.code = code;
        this.label = label;
        this.dir = dir;
        this.prefix = prefix;
        this.altPrefix = altPrefix;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public String getDir() {
        return dir;
    }

    public String getPrefix() {
        return prefix;
    }

    /**
     * 归一化：台账里可能有 CM / CTCC / CHINA-UNICOM 之类写法，统一收敛到枚举。
     *
     * @param value 台账或配置里的运营商，空值与认不出的写法都归到 {@link #STANDARD}
     */
    public static Isp of(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        String v = value.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        return switch (v) {
            case "CMCC", "CM", "CHINAMOBILE" -> CMCC;
            case "CT", "CTCC", "TELECOM", "CHINATELECOM" -> CT;
            case "CU", "CUCC", "UNICOM", "CHINAUNICOM" -> CU;
            case "CBN", "CATV", "GB", "CHINABROADNET" -> CBN;
            default -> STANDARD;
        };
    }

    /**
     * 按存储目录反查（型号档案目录名与代码不是一回事：CMCC 的目录是 CM）。
     */
    public static Isp ofDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return STANDARD;
        }
        String v = dir.trim().toUpperCase(Locale.ROOT);
        for (Isp isp : values()) {
            if (isp.dir.equals(v)) {
                return isp;
            }
        }
        return STANDARD;
    }

    /**
     * 从能力树（或 Inform 参数）里按私有前缀判定运营商。
     *
     * <p>只认带前缀的节点名：同型号硬件卖给多家运营商，固件不同但型号名一样，
     * 唯一能区分的就是这些私有参数。一家都没命中返回 {@code null}，调用方保持原值。</p>
     */
    public static Isp detect(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        Map<Isp, Integer> hits = new EnumMap<>(Isp.class);
        for (String path : paths) {
            if (path == null) {
                continue;
            }
            String upper = path.toUpperCase(Locale.ROOT);
            for (Isp isp : values()) {
                if (isp == STANDARD) {
                    continue;
                }
                if ((!isp.prefix.isEmpty() && upper.contains(isp.prefix))
                        || (!isp.altPrefix.isEmpty() && upper.contains(isp.altPrefix))) {
                    hits.merge(isp, 1, Integer::sum);
                }
            }
        }
        // 按声明顺序取命中最多的一家，票数相同时移动优先（与旧行为一致）
        Isp best = null;
        int most = 0;
        for (Isp isp : values()) {
            if (isp == STANDARD) {
                continue;
            }
            int count = hits.getOrDefault(isp, 0);
            if (count > most) {
                best = isp;
                most = count;
            }
        }
        return best;
    }
}

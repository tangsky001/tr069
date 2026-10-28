package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.model.OltBoardProfile;
import com.tr069.acs.olt.model.OltBoardStatusRule;

import java.util.List;
import java.util.Locale;

/**
 * 板卡档案与状态翻译：设备侧知识，以前整张躺在页面里。
 *
 * <p>页面需要的不是「型号→档案」这张表，而是「这块板该怎么画、状态该怎么说」，
 * 所以由服务端按型号匹配好、连同板卡一起返回，页面拿到就能用。</p>
 */
public final class OltBoardCatalog {

    private OltBoardCatalog() {
    }

    /** 环境温度告警阈值(摄氏度)：超过就当告警 */
    public static final int ENV_TEMP_ALARM_C = 65;

    /**
     * 型号档案。
     *
     * <p><b>顺序不能随便调：</b>{@code XGBD} 必须排在 {@code GPBD} 前面——
     * {@code H801XGBD} 里也有 {@code GPBD} 这串字符，先匹配 GPBD 会认错板。</p>
     */
    private static final List<OltBoardProfile> PROFILES = List.of(
            new OltBoardProfile("H802SCUN", "SCUN 主控板（4 个管理/上行 GE 口）", "ge", "GE",
                    4, true, List.of("SCUN")),
            new OltBoardProfile("H801X2CS", "X2CS 上联板（2 路 10GE 光口）", "xge", "10GE",
                    2, false, List.of("X2CS")),
            new OltBoardProfile("H801XGBD", "XGBD 8 口 GPON 业务板（10GE 上联）", "pon", "GPON",
                    8, false, List.of("XGBD")),
            new OltBoardProfile("H807GPBD", "GPBD 8 口 GPON 业务板", "pon", "GPON",
                    8, false, List.of("GPBD")),
            new OltBoardProfile("H808EPSD", "EPSD 8 口 PON 业务板", "pon", "EPON",
                    8, false, List.of("EPSD")));

    /** 状态翻译规则，从上往下第一条命中的生效 */
    private static final List<OltBoardStatusRule> STATUS_RULES = List.of(
            new OltBoardStatusRule(List.of("auto find"), "自动发现", "warning"),
            new OltBoardStatusRule(List.of("not installed"), "未插板", "danger"),
            new OltBoardStatusRule(List.of("active"), "激活", "success"),
            new OltBoardStatusRule(List.of("standby"), "备用", "warning"),
            new OltBoardStatusRule(List.of("fail", "fault"), "故障", "danger"),
            new OltBoardStatusRule(List.of("abnormal"), "异常", "danger"),
            new OltBoardStatusRule(List.of("offline"), "离线", "danger"),
            new OltBoardStatusRule(List.of("config"), "配置中", "warning"),
            new OltBoardStatusRule(
                    List.of("inservice", "in service", "in position", "normal", "working", "online", "up"),
                    "正常", "success"));

    /** 页面要展示整张表时给出去（比如画个型号下拉）；判定一律走 {@link #match} */
    public static List<OltBoardProfile> profiles() {
        return PROFILES;
    }

    /** 按板卡型号匹配档案，匹配不到返回 null */
    public static OltBoardProfile match(String boardType) {
        if (boardType == null || boardType.isBlank()) {
            return null;
        }
        String type = boardType.toUpperCase(Locale.ROOT);
        for (OltBoardProfile profile : PROFILES) {
            for (String keyword : profile.keywords()) {
                if (type.contains(keyword.toUpperCase(Locale.ROOT))) {
                    return profile;
                }
            }
        }
        return null;
    }

    /**
     * 没配档案的板卡按端口类型推断面板样式，让主控板、上联板也有像样的面板；
     * 认不出来就退回 generic，按端口数画一排通用口。
     */
    public static String panelKindOf(String portMode) {
        String mode = portMode == null ? "" : portMode.toUpperCase(Locale.ROOT);
        if (mode.isBlank()) {
            return "generic";
        }
        if (mode.contains("GPON") || mode.contains("EPON") || mode.contains("PON")) {
            return "pon";
        }
        if (mode.contains("XGE") || mode.contains("10GE") || mode.contains("SFP")) {
            return "xge";
        }
        if (mode.contains("GE") || mode.contains("FE") || mode.contains("ETH")) {
            return "ge";
        }
        return "generic";
    }

    /** 设备返回的英文状态 → 翻译规则，认不出来返回 null（页面就显示原样） */
    public static OltBoardStatusRule statusOf(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        // 设备写成 Active_normal / in-service 这类，统一成空格分隔的小写再比
        String text = status.toLowerCase(Locale.ROOT).replaceAll("[_-]+", " ").trim();
        for (OltBoardStatusRule rule : STATUS_RULES) {
            for (String keyword : rule.keywords()) {
                if (text.contains(keyword)) {
                    return rule;
                }
            }
        }
        return null;
    }
}

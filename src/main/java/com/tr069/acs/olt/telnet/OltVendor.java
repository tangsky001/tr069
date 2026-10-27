package com.tr069.acs.olt.telnet;

import java.util.List;
import java.util.Locale;

/**
 * OLT 厂商。
 *
 * <p>同一件事（版本、板卡）各家命令不同，登录后先用 {@code display version} /
 * {@code show version} 定下命令风格与厂商，再决定后续用哪套命令：
 * 华为是 {@code display}，中兴/烽火等第三方是 {@code show}。</p>
 */
public enum OltVendor {

    /** 华为 MA5600T / MA5800 / EA5800 */
    HUAWEI("华为", CliStyle.DISPLAY,
            List.of("display version"),
            // 只跑带机框号的 display board 0：不带参数时设备会回 "Parameter: FrameID/SlotID"
            // 停下来等输入，命令就一直等到超时
            List.of("display board 0"),
            // MA5600 系列关分页是 scroll（screen-length 是 VRP 交换机的，OLT 不认）；
            // undo smart 在部分版本会弹交互确认，反而拖慢采集，不发
            List.of("scroll", "screen-length 0 temporary")),

    /** 中兴 ZXA10 C300 / C320 / C350 */
    ZTE("中兴", CliStyle.SHOW,
            List.of("show version"),
            List.of("show card", "show slot"),
            List.of("terminal length 0")),

    /** 烽火 AN5516 / AN6000 */
    FIBERHOME("烽火", CliStyle.SHOW,
            List.of("show version"),
            List.of("show card", "show slot"),
            List.of("terminal length 0")),

    /**
     * 鸿升光 HSGQ-E04 等 EPON 一体化 OLT。
     *
     * <p>没有板卡概念：{@code show pon-info} 一条带回全部 PON 口和每口 ONU 数，
     * {@code show onu-info all} 带回每条 ONU 的 MAC 与在线状态。提示符是
     * {@code OLT>} / {@code OLT(config)#}，进配置视图的命令是 {@code configure}。</p>
     */
    HSGQ("鸿升光", CliStyle.SHOW,
            List.of("show version"),
            List.of("show pon-info"),
            List.of()),

    /** 识别不出来的设备，只做通用探测 */
    GENERIC("通用", null,
            List.of("display version", "show version"),
            List.of("display board 0", "show card"),
            List.of("terminal length 0"));

    /**
     * 命令风格：华为一套 {@code display}，第三方（中兴/烽火等）一套 {@code show}。
     *
     * <p>登录后的第一步就是分清这两套——发错了设备只回一句 Unknown command，
     * 白等一个来回，还拿不到任何信息。</p>
     */
    public enum CliStyle {

        /** 华为：display version / display board 0 */
        DISPLAY("display"),

        /** 第三方（中兴、烽火）：show version / show card */
        SHOW("show");

        private final String keyword;

        CliStyle(String keyword) {
            this.keyword = keyword;
        }

        public String getKeyword() {
            return keyword;
        }

        /**
         * 从命令文本判断风格；{@code display ...} 是华为，{@code show ...} 是第三方。
         */
        public static CliStyle ofCommand(String command) {
            if (command == null) {
                return null;
            }
            String text = command.trim().toLowerCase(Locale.ROOT);
            if (text.startsWith("display")) {
                return DISPLAY;
            }
            if (text.startsWith("show")) {
                return SHOW;
            }
            return null;
        }
    }

    private final String label;

    private final CliStyle cliStyle;

    private final List<String> versionCommands;

    private final List<String> boardCommands;

    private final List<String> pagingCommands;

    OltVendor(String label, CliStyle cliStyle, List<String> versionCommands, List<String> boardCommands,
              List<String> pagingCommands) {
        this.label = label;
        this.cliStyle = cliStyle;
        this.versionCommands = versionCommands;
        this.boardCommands = boardCommands;
        this.pagingCommands = pagingCommands;
    }

    public String getLabel() {
        return label;
    }

    public CliStyle getCliStyle() {
        return cliStyle;
    }

    public List<String> getVersionCommands() {
        return versionCommands;
    }

    public List<String> getBoardCommands() {
        return boardCommands;
    }

    public List<String> getPagingCommands() {
        return pagingCommands;
    }

    /**
     * 按回显关键字识别厂商，识别不出返回 {@link #GENERIC}。
     */
    public static OltVendor detect(String output) {
        if (output == null || output.isBlank()) {
            return GENERIC;
        }
        String text = output.toLowerCase(Locale.ROOT);
        if (text.contains("huawei") || text.contains("quidway") || text.contains("versatile routing")
                || text.contains("smartax")
                || text.contains("ma5600") || text.contains("ma5603") || text.contains("ma5608")
                || text.contains("ma5680") || text.contains("ma5683")
                || text.contains("ma5800") || text.contains("ea5800") || text.contains("ma6800")) {
            return HUAWEI;
        }
        if (text.contains("zte") || text.contains("zxan") || text.contains("zxr10")
                || text.contains("c320") || text.contains("c300") || text.contains("c350")) {
            return ZTE;
        }
        if (text.contains("fiberhome") || text.contains("烽火")
                || text.contains("an5516") || text.contains("an6000")) {
            return FIBERHOME;
        }
        if (text.contains("hsgq") || text.contains("hongsheng") || text.contains("鸿升光")) {
            return HSGQ;
        }
        return GENERIC;
    }

    /**
     * 认不出厂商就是第三方，不拿命令风格猜华为。
     *
     * <p>早先 {@code display} 有回显就记作华为，结果第三方 OLT（也用 display 那一套）
     * 全被存成 HUAWEI，页面一律出华为界面，而华为专属命令在它们上面全是 Unknown command。
     * 厂商只认回显里的厂商关键字（{@link #detect(String)}）；命令风格是另一回事，
     * 由 {@code OltCliSession} 单独记在 cliStyle 上决定后续发 display 还是 show。</p>
     */
    public static OltVendor detect(CliStyle style, String output) {
        return detect(output);
    }

    /**
     * 按名称反查，用于页面回显。
     */
    public static OltVendor of(String code) {
        if (code == null || code.isBlank()) {
            return GENERIC;
        }
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GENERIC;
        }
    }
}

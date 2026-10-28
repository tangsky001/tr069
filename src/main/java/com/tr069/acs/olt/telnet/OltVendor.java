package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.vendor.OltVendorProfiles;

import java.util.Locale;

/**
 * OLT 厂商。
 *
 * <p>同一件事（版本、板卡）各家命令不同，登录后先用 {@code display version} /
 * {@code show version} 定下命令风格与厂商，再决定后续用哪套命令：
 * 华为是 {@code display}，中兴/烽火等第三方是 {@code show}。</p>
 *
 * <p>这里只留「厂商编号 + 页面标签 + 命令风格」这三样身份信息；
 * 命令清单、识别规则、型号怎么抠、有没有板卡概念这些差别都在
 * {@link com.tr069.acs.olt.vendor.OltVendorProfile} 里——新增品牌去那边写一个类，
 * 不用动枚举以外的任何地方。</p>
 */
public enum OltVendor {

    /** 华为 MA5600T / MA5800 / EA5800 */
    HUAWEI("华为", CliStyle.DISPLAY),

    /** 中兴 ZXA10 C300 / C320 / C350 */
    ZTE("中兴", CliStyle.SHOW),

    /** 烽火 AN5516 / AN6000 */
    FIBERHOME("烽火", CliStyle.SHOW),

    /** 鸿升光 HSGQ-E04 等 EPON 一体化 OLT：整机一张卡，没有板卡概念 */
    HSGQ("鸿升光 EPON", CliStyle.SHOW),

    /**
     * 鸿升光 GPON 一体化 OLT（4/8/16 口，型号 Neutral-G04/G08/G16）：
     * 与 EPON 同一套 CLI，但 PON 口是 gpon、业务靠 service-port 虚端口
     */
    HSGQ_GPON("鸿升光 GPON", CliStyle.SHOW),

    /** 识别不出来的设备，只做通用探测 */
    GENERIC("通用", null);

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

    OltVendor(String label, CliStyle cliStyle) {
        this.label = label;
        this.cliStyle = cliStyle;
    }

    public String getLabel() {
        return label;
    }

    /** 命令风格；认不出的厂商为 null，由会话按探测到的那条版本命令定 */
    public CliStyle getCliStyle() {
        return cliStyle;
    }

    /**
     * 按回显关键字识别厂商，识别不出返回 {@link #GENERIC}。
     *
     * <p>真正的识别规则在各家 profile 的 {@code matches} 里（见
     * {@link OltVendorProfiles}），这里只是转发，让老调用点不受影响。</p>
     */
    public static OltVendor detect(String output) {
        if (output == null || output.isBlank()) {
            return GENERIC;
        }
        return OltVendorProfiles.detect(output).vendor();
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

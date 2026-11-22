/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.723
 *
 */

package com.PilotACS.olt.telnet;

import com.PilotACS.olt.vendor.OltVendorProfiles;

import java.util.Locale;

/** OLT 厂商 */
public enum OltVendor {

    /** 华为 MA5600T / MA5800 / EA5800 */
    HUAWEI("华为", CliStyle.DISPLAY),

    /** 中兴 ZXA10 C300 / C320 / C350 */
    ZTE("中兴", CliStyle.SHOW),

    /** 烽火 AN5516 / AN6000 */
    FIBERHOME("烽火", CliStyle.SHOW),

    /** 鸿升光 HSGQ-E04 等 EPON 一体化 OLT：整机一张卡，没有板卡概念 */
    HSGQ("鸿升光 EPON", CliStyle.SHOW),

    /** 鸿升光 GPON 一体化 OLT（4/8/16 口，型号 Neutral-G04/G08/G16） */
    HSGQ_GPON("鸿升光 GPON", CliStyle.SHOW),

    /** 识别不出来的设备，只做通用探测 */
    GENERIC("通用", null);

    /** 命令风格 */
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

        /** 从命令文本判断风格 */
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

    public CliStyle getCliStyle() {
        return cliStyle;
    }

    /** 按回显关键字识别厂商 */
    public static OltVendor detect(String output) {
        if (output == null || output.isBlank()) {
            return GENERIC;
        }
        return OltVendorProfiles.detect(output).vendor();
    }

    public static OltVendor detect(CliStyle style, String output) {
        return detect(output);
    }

    /** 按名称反查 */
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

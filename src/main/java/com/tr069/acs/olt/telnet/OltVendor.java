package com.tr069.acs.olt.telnet;

import java.util.List;
import java.util.Locale;

/**
 * OLT 厂商。
 *
 * <p>同一件事（版本、板卡）各家命令不同，登录后先跑一遍版本命令，靠回显里的关键字
 * 判定厂商，再决定后续用哪套命令。</p>
 */
public enum OltVendor {

    /** 华为 MA5600T / MA5800 / EA5800 */
    HUAWEI("华为",
            List.of("display version"),
            // 只跑带机框号的 display board 0：不带参数时设备会回 "Parameter: FrameID/SlotID"
            // 停下来等输入，命令就一直等到超时
            List.of("display board 0"),
            // 只关分页；undo smart 在部分版本会弹交互确认，反而拖慢采集
            List.of("screen-length 0 temporary")),

    /** 中兴 ZXA10 C300 / C320 / C350 */
    ZTE("中兴",
            List.of("show version"),
            List.of("show card", "show slot"),
            List.of("terminal length 0")),

    /** 烽火 AN5516 / AN6000 */
    FIBERHOME("烽火",
            List.of("show version"),
            List.of("show card", "show slot"),
            List.of("terminal length 0")),

    /** 识别不出来的设备，只做通用探测 */
    GENERIC("通用",
            List.of("display version", "show version"),
            List.of("display board 0", "show card"),
            List.of("terminal length 0"));

    private final String label;
    private final List<String> versionCommands;
    private final List<String> boardCommands;
    private final List<String> pagingCommands;

    OltVendor(String label, List<String> versionCommands, List<String> boardCommands,
              List<String> pagingCommands) {
        this.label = label;
        this.versionCommands = versionCommands;
        this.boardCommands = boardCommands;
        this.pagingCommands = pagingCommands;
    }

    public String getLabel() {
        return label;
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
                || text.contains("ma5600") || text.contains("ma5800") || text.contains("ea5800")
                || text.contains("ma6800")) {
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
        return GENERIC;
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

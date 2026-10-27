package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.model.OltDbaProfile;
import com.tr069.acs.olt.model.OltGemMapping;
import com.tr069.acs.olt.model.OltGemport;
import com.tr069.acs.olt.model.OltLineProfile;
import com.tr069.acs.olt.model.OltServicePort;
import com.tr069.acs.olt.model.OltSrvProfile;
import com.tr069.acs.olt.model.OltTrafficTable;
import com.tr069.acs.olt.model.OltTr069Profile;
import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.model.OltOntInfo;
import com.tr069.acs.olt.model.OltTcont;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltIpInterface;
import com.tr069.acs.olt.model.OltPortVlan;
import com.tr069.acs.olt.model.OltStaticRoute;
import com.tr069.acs.olt.model.OltVlan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命令行回显解析。
 *
 * <p>以华为 MA5600T / MA5683T 的实际回显为主，中兴、烽火按它们的 show 命令格式兼容。
 * 解析原则：能解析就填，解析不出留空（页面显示「-」），不让一条正则失败导致整次同步失败——
 * 原始回显会落到 olt_sync_record，现场按原文补规则即可。</p>
 */
public final class OltInfoParser {

    // ------------------------------------------------------------ 型号 / 版本

    /** 华为 OLT 型号：MA5683T / MA5600T；注意别把版本号 MA5600V800R015C00 当成型号 */
    private static final Pattern MODEL_HUAWEI = Pattern.compile(
            "(?i)\\b(MA5800-[A-Z0-9]+|EA5800-[A-Z0-9]+|MA6800T|MA5\\d{3,4}T?)\\b");

    private static final Pattern MODEL_ZTE = Pattern.compile(
            "(?i)\\b(ZXA10\\s*[A-Z]\\d{3,4}[A-Z]?|ZXA10\\s*C\\d{3})\\b");

    private static final Pattern MODEL_FIBERHOME = Pattern.compile(
            "(?i)\\b(AN5\\d{3}-\\d{2}|AN6000-\\d+|AN5516-\\d{2})\\b");

    /** 鸿升光：HSGQ-E04（show version 的 Software Version 和 Product 行里都有） */
    private static final Pattern MODEL_HSGQ = Pattern.compile(
            "(?i)\\b(HSGQ-[A-Za-z0-9]{2,10})\\b");

    /** 型号常见写法：Model: xxx / Product name : xxx / 设备型号 */
    private static final Pattern MODEL_LINE = Pattern.compile(
            "(?im)^\\s*(?:model(?:\\s*name)?|product\\s*name|device\\s*type|equipment\\s*type|设备型号|产品名称)"
                    + "\\s*[:：]\\s*([A-Za-z0-9._/-]{3,40})\\s*$");

    /** 产品版本号：MA5600V800R015C00（华为型号+版本拼在一起，优先于纯 VRP 号） */
    private static final Pattern VERSION_PRODUCT = Pattern.compile(
            "\\b([A-Z]{2}\\d{3,4}V\\d{3}R\\d{3}C\\d{2,3})\\b");

    /** 华为 VRP 版本：V800R018C10 */
    private static final Pattern VERSION_VRP = Pattern.compile("\\bV\\d{3}R\\d{3}(?:C\\d{2,3})?[A-Z0-9]*\\b");

    private static final Pattern VERSION_SEMVER = Pattern.compile(
            "(?i)\\b(?:software\\s+|main\\s+|patch\\s+)?version\\s*[:：]\\s*([A-Za-z0-9][A-Za-z0-9._()\\-_]{1,40})");

    private static final Pattern VERSION_SEMVER_FALLBACK = Pattern.compile("\\bV\\d+\\.\\d+(?:\\.\\d+)+[A-Za-z0-9]*");

    /** 补丁：PATCH : SPC107 */
    private static final Pattern PATCH = Pattern.compile(
            "(?im)^\\s*patch(?:\\s*version)?\\s*[:：]\\s*([A-Za-z0-9._-]{2,32})");

    /**
     * 背板/主板型号：PCB Version : H802MABO VER.B。
     * 只认 PCB Version / Backplane，「Board Type」在部分版本里回的是主控板型号，不能当背板。
     */
    private static final Pattern BACKPLANE = Pattern.compile(
            "(?im)^\\s*(?:main\\s+board\\s*[:：]?\\s*)?(?:pcb\\s*version|backplane(?:\\s*type)?)"
                    + "\\s*[:：]\\s*([A-Z][A-Z0-9]{3,19})\\b");

    private static final Pattern MAC_ADDRESS = Pattern.compile(
            "(?im)^\\s*(?:main\\s+board\\s+|base\\s+)?mac(?:\\s*address)?\\s*[:：]\\s*"
                    + "([0-9A-Fa-f]{2}(?:[-:][0-9A-Fa-f]{2}){5}|[0-9A-Fa-f]{4}(?:[-:][0-9A-Fa-f]{4}){2})\\b");

    /** 运行时长：MA5683T uptime is 59 day(s), 18 hour(s), ... */
    private static final Pattern UPTIME = Pattern.compile("(?i)uptime\\s+is\\s+([^\\r\\n]+)");

    // ------------------------------------------------------------ 板卡

    /** 板卡行：槽位 板卡名 [端口数] 状态 */
    private static final Pattern BOARD_LINE = Pattern.compile(
            "^\\s*(\\d{1,2})\\s+([A-Za-z][A-Za-z0-9/_.-]{1,31})(?:\\s+(\\d{1,3}))?\\s+(" + statuses() + ")\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** 中兴 show card 的表头：Rack Shelf Slot Cpu BoardType PortNum Status */
    private static final Pattern RACK_SHELF_HEADER = Pattern.compile(
            "(?im)^\\s*rack\\s+shelf\\s+slot\\b");

    /** 带机架/机框列的行：机架 机框 槽位 CPU 板卡名 端口数 状态 */
    private static final Pattern BOARD_LINE_RACK_SHELF = Pattern.compile(
            "^\\s*\\d{1,2}\\s+\\d{1,2}\\s+(\\d{1,2})\\s+\\d{1,2}\\s+"
                    + "([A-Za-z][A-Za-z0-9/_.-]{1,31})\\s+(\\d{1,3})\\s+(" + statuses() + ")\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** 背板板卡（如 H802MABO）不是业务板，display board 0 里会占一行，单独识别后跳过 */
    private static final Pattern BACKPLANE_BOARD_NAME = Pattern.compile(
            "(?i)^H\\d{3}MAB[A-Z0-9]{0,4}$");

    private static final Pattern BACKPLANE_BOARD = Pattern.compile(
            "(?m)^\\s*\\d{1,2}\\s+(H\\d{3}MAB[A-Z0-9]{0,4})(?=\\s|$)", Pattern.CASE_INSENSITIVE);

    /** 槽位表里的空槽位行：整行只有一个槽位号，没有板卡名 */
    private static final Pattern EMPTY_SLOT_LINE = Pattern.compile("(?m)^\\s*(\\d{1,2})\\s*$");

    /**
     * 华为 display temperature all 的表头：
     * Slot Card Sensor Status Current(C) Lower(C) Upper(C)
     */
    private static final Pattern TEMPERATURE_SENSOR_HEADER = Pattern.compile(
            "(?i)(?:sensor\\s+status|current\\s*\\(c\\))");

    /** 华为温度行：9  -  1  Minor  70  0  64 */
    private static final Pattern TEMPERATURE_SENSOR_ROW = Pattern.compile(
            "^\\s*(\\d{1,2})\\s+(\\S+)\\s+(\\d{1,2})\\s+([A-Za-z]+)\\s+(-?\\d{1,3})\\b",
            Pattern.MULTILINE);

    /** 其它版本的简表：槽位 板卡名 温度 [上限 下限] */
    private static final Pattern TEMPERATURE_ROW = Pattern.compile(
            "^\\s*(\\d{1,2})\\s+[A-Za-z][A-Za-z0-9/_.-]{1,31}\\s+(-?\\d{1,3})(?:\\s|$)",
            Pattern.MULTILINE);

    private static final Pattern TEMPERATURE_WORD = Pattern.compile("(?i)temperature");

    private OltInfoParser() {
    }

    /**
     * 板卡状态枚举。各家写法差异大，这里统一列一遍：
     * Normal / Active_normal / Standby_normal / InService / Auto_find / Failed ...
     */
    private static String statuses() {
        return "normal|active[_ ]normal|standby[_ ]normal|active|standby|inservice|in[_ ]service|in[_ ]position|"
                + "working|online|up|fault|faulty|abnormal|offline|failed|config|configured|auto[_ ]?find|"
                + "not[_ ]installed|out[_ ]of[_ ]service|busy|unknown|unregistered|initializing";
    }

    // ------------------------------------------------------------ 版本信息

    /**
     * 从版本回显里提取型号。
     */
    public static String extractModel(OltVendor vendor, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Pattern[] candidates;
        switch (vendor == null ? OltVendor.GENERIC : vendor) {
            case HUAWEI:
                candidates = new Pattern[]{MODEL_HUAWEI, MODEL_LINE};
                break;
            case ZTE:
                candidates = new Pattern[]{MODEL_ZTE, MODEL_LINE};
                break;
            case FIBERHOME:
                candidates = new Pattern[]{MODEL_FIBERHOME, MODEL_LINE};
                break;
            case HSGQ:
                candidates = new Pattern[]{MODEL_HSGQ, MODEL_LINE};
                break;
            default:
                candidates = new Pattern[]{MODEL_HUAWEI, MODEL_ZTE, MODEL_FIBERHOME, MODEL_HSGQ, MODEL_LINE};
                break;
        }
        for (Pattern pattern : candidates) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return clean(matcher.group(1));
            }
        }
        return null;
    }

    /**
     * 软件版本：优先取 MA5600V800R015C00 这种产品版本号，其次 VRP 号，最后 version: 行。
     */
    public static String extractSoftwareVersion(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher product = VERSION_PRODUCT.matcher(text);
        if (product.find()) {
            return product.group(1);
        }
        Matcher vrp = VERSION_VRP.matcher(text);
        if (vrp.find()) {
            return vrp.group();
        }
        Matcher semver = VERSION_SEMVER.matcher(text);
        if (semver.find()) {
            return semver.group(1);
        }
        Matcher fallback = VERSION_SEMVER_FALLBACK.matcher(text);
        return fallback.find() ? fallback.group() : null;
    }

    /**
     * 补丁版本，如 SPC107。
     */
    public static String extractPatchVersion(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = PATCH.matcher(text);
        return matcher.find() ? clean(matcher.group(1)) : null;
    }

    /**
     * 背板/主板型号，如 MA5683T 的 H802MABO。
     */
    public static String extractBackplane(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = BACKPLANE.matcher(text);
        return matcher.find() ? clean(matcher.group(1)) : null;
    }

    /**
     * 从 display board 0 的回显里找背板型号，版本命令没带 PCB 时兜底。
     */
    public static String extractBackplaneBoard(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = BACKPLANE_BOARD.matcher(text);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /**
     * 主控 MAC。设备回显里没写就是 null，页面显示「-」。
     */
    public static String extractMacAddress(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = MAC_ADDRESS.matcher(text);
        return matcher.find() ? clean(matcher.group(1)).toUpperCase(Locale.ROOT) : null;
    }

    /**
     * 运行时长，统一转成「59天18小时21分11秒」。
     */
    public static String extractUptime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = UPTIME.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return humanizeUptime(matcher.group(1).trim());
    }

    private static String humanizeUptime(String raw) {
        Integer years = numberBefore(raw, "year");
        Integer weeks = numberBefore(raw, "week");
        Integer days = numberBefore(raw, "day");
        Integer hours = numberBefore(raw, "hour");
        Integer minutes = numberBefore(raw, "minute");
        Integer seconds = numberBefore(raw, "second");
        if (years == null && weeks == null && days == null && hours == null
                && minutes == null && seconds == null) {
            return raw.length() > 64 ? raw.substring(0, 64) : raw;
        }
        StringBuilder text = new StringBuilder();
        if (years != null) {
            text.append(years).append("年");
        }
        if (weeks != null) {
            text.append(weeks).append("周");
        }
        if (days != null) {
            text.append(days).append("天");
        }
        if (hours != null) {
            text.append(hours).append("小时");
        }
        if (minutes != null) {
            text.append(minutes).append("分");
        }
        if (seconds != null) {
            text.append(seconds).append("秒");
        }
        return text.toString();
    }

    private static Integer numberBefore(String text, String unit) {
        Matcher matcher = Pattern.compile("(\\d+)\\s*" + unit, Pattern.CASE_INSENSITIVE).matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    // ------------------------------------------------------------ 板卡

    /**
     * 解析板卡列表，已按槽位排序。
     */
    public static List<OltBoard> parseBoards(String text) {
        List<OltBoard> boards = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return boards;
        }
        // 中兴 C320 的 show card 比别的厂商多两列（机架/机框），按表头决定用哪套规则
        Pattern boardPattern = RACK_SHELF_HEADER.matcher(text).find() ? BOARD_LINE_RACK_SHELF : BOARD_LINE;
        Matcher matcher = boardPattern.matcher(text);
        while (matcher.find()) {
            int slot = Integer.parseInt(matcher.group(1));
            // 同一槽位重复出现时保留第一条（部分命令会把主控板列两次）
            boolean duplicated = boards.stream().anyMatch(b -> b.getSlotNo() == slot);
            if (duplicated) {
                continue;
            }
            String boardType = clean(matcher.group(2));
            if (boardType != null && BACKPLANE_BOARD_NAME.matcher(boardType).matches()) {
                // 背板不是板卡，跳过；背板型号由 extractBackplaneBoard 单独取
                continue;
            }
            String portCountRaw = matcher.group(3);
            String status = clean(matcher.group(4)).replaceAll("\\s+", " ");

            BoardKind kind = classifyBoard(boardType, slot);
            int knownPorts = knownPortCount(boardType);
            int ports = portCountRaw == null ? 0 : Integer.parseInt(portCountRaw);
            if (ports == 0) {
                ports = knownPorts;
            }
            String portMode = guessPortMode(boardType);

            OltBoard board = new OltBoard();
            board.setSlotNo(slot);
            board.setBoardType(boardType);
            board.setStatus(status);
            board.setPortCount(ports);
            board.setPortMode(portMode);
            board.setBoardKind(kind.name());
            board.setDescription(describeBoard(boardType, kind, ports, portMode));
            boards.add(board);
        }
        boards.sort((a, b) -> Integer.compare(a.getSlotNo(), b.getSlotNo()));
        return boards;
    }

    /**
     * 机框槽位数：{@code display board 0} 槽位表里最大的 SlotID + 1。
     *
     * <p>表里空槽位也占一行（只有槽位号，没有板卡名），所以不能只看解析出来的板卡行——
     * 现场这台 MA5683T 的槽位表是 0..12 共 13 个槽位，只有 1/6/8 插了板卡，
     * 机框要按 13 个槽位画，而不是只画到 8 号。</p>
     */
    public static Integer parseSlotCount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int max = -1;
        Matcher bare = EMPTY_SLOT_LINE.matcher(text);
        while (bare.find()) {
            max = Math.max(max, Integer.parseInt(bare.group(1)));
        }
        for (OltBoard board : parseBoards(text)) {
            if (board.getSlotNo() != null) {
                max = Math.max(max, board.getSlotNo());
            }
        }
        return max < 0 ? null : max + 1;
    }

    /**
     * 解析板卡温度，返回「槽位 -> 温度」。命令不支持时返回空表。
     */
    public static Map<Integer, Integer> parseTemperatures(String text) {
        Map<Integer, Integer> temperatures = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return temperatures;
        }
        // 别把板卡表、端口表当成温度表：必须有 temperature 字样或温度表表头
        if (!TEMPERATURE_WORD.matcher(text).find() && !TEMPERATURE_SENSOR_HEADER.matcher(text).find()) {
            return temperatures;
        }
        // 华为 MA5600T/MA5683T 走「Slot Card Sensor Status Current」这块表，
        // 一块板卡多个传感器时取最高温，避免把传感器号当成温度
        if (TEMPERATURE_SENSOR_HEADER.matcher(text).find()) {
            Matcher sensor = TEMPERATURE_SENSOR_ROW.matcher(text);
            while (sensor.find()) {
                int slot = Integer.parseInt(sensor.group(1));
                int temperature = Integer.parseInt(sensor.group(5));
                if (sane(temperature)) {
                    temperatures.merge(slot, temperature, Math::max);
                }
            }
            if (!temperatures.isEmpty()) {
                return temperatures;
            }
        }
        Matcher matcher = TEMPERATURE_ROW.matcher(text);
        while (matcher.find()) {
            int slot = Integer.parseInt(matcher.group(1));
            int temperature = Integer.parseInt(matcher.group(2));
            if (sane(temperature)) {
                temperatures.putIfAbsent(slot, temperature);
            }
        }
        return temperatures;
    }

    private static boolean sane(int temperature) {
        return temperature >= -20 && temperature <= 150;
    }

    /**
     * 板卡类别。先按板卡型号关键字判断，认不出再按 MA5600T/MA5683T 的槽位约定兜底。
     */
    public static BoardKind classifyBoard(String boardType, Integer slotNo) {
        String type = boardType == null ? "" : boardType.toUpperCase(Locale.ROOT);
        if (type.contains("SCU") || type.contains("MPL") || type.contains("CCU") || type.contains("SRVC")) {
            return BoardKind.CONTROL;
        }
        if (type.contains("X2C") || type.contains("X1C") || type.contains("GIC") || type.contains("CRT")
                || type.contains("UPLINK") || type.contains("10GE")) {
            return BoardKind.UPLINK;
        }
        if (type.contains("PIL") || type.contains("PWR") || type.contains("POWER")) {
            return BoardKind.POWER;
        }
        if (type.contains("GPB") || type.contains("GPF") || type.contains("GPH") || type.contains("GPU")
                || type.contains("EPB") || type.contains("EPF") || type.contains("EPH") || type.contains("EPU")
                || type.contains("GTG") || type.contains("ETG")
                || type.contains("PON") || type.contains("DSL")) {
            return BoardKind.SERVICE;
        }
        if (type.contains("ETH") || type.contains("GICF") || type.contains("GE")) {
            return BoardKind.INTERFACE;
        }
        // 型号认不出时，只在华为板卡（H8xx/H9xx）上按槽位约定猜，避免把别家板卡猜错
        boolean huawei = type.startsWith("H8") || type.startsWith("H9");
        if (huawei && slotNo != null) {
            switch (slotNo) {
                case 6:
                case 7:
                    return BoardKind.CONTROL;
                case 8:
                case 9:
                    return BoardKind.UPLINK;
                case 10:
                case 11:
                    return BoardKind.POWER;
                case 12:
                    return BoardKind.INTERFACE;
                default:
                    return slotNo >= 0 && slotNo <= 5 ? BoardKind.SERVICE : BoardKind.UNKNOWN;
            }
        }
        return BoardKind.UNKNOWN;
    }

    /**
     * 生成页面上的「描述」列，如 8口GPON（业务板）。
     */
    static String describeBoard(String boardType, BoardKind kind, int ports, String portMode) {
        switch (kind) {
            case CONTROL:
                return boardType != null && boardType.toUpperCase(Locale.ROOT).contains("SCU")
                        ? "超级控制单元板（主控板）" : "主控板";
            case UPLINK:
                return ports > 0 && portMode != null ? ports + "路" + portMode + "光口（上联板）" : "上联板";
            case SERVICE:
                return ports > 0 && portMode != null ? ports + "口" + portMode + "（业务板）" : "业务板";
            case POWER:
                return "电源板";
            case INTERFACE:
                return "通用接口板";
            default:
                return null;
        }
    }

    /**
     * 按板卡型号猜端口类型，猜不出返回 null（页面显示「-」）。
     */
    static String guessPortMode(String boardType) {
        if (boardType == null || boardType.isBlank()) {
            return null;
        }
        String type = boardType.toUpperCase(Locale.ROOT);
        if (type.contains("X2C") || type.contains("X1C") || type.contains("10GE")) {
            return "10GE";
        }
        if (type.contains("GPB") || type.contains("GPF") || type.contains("GPH") || type.contains("GPU")
                || type.contains("GTG") || type.contains("GPON")) {
            return "GPON";
        }
        if (type.contains("EPB") || type.contains("EPF") || type.contains("EPH") || type.contains("EPU")
                || type.contains("ETG") || type.contains("EPON")) {
            return "EPON";
        }
        if (type.contains("PON")) {
            return "PON";
        }
        return null;
    }

    /**
     * 常见华为板卡的端口数，设备回显不带端口数时兜底（display board 0 就不带）。
     */
    static int knownPortCount(String boardType) {
        if (boardType == null || boardType.isBlank()) {
            return 0;
        }
        String type = boardType.toUpperCase(Locale.ROOT);
        if (type.endsWith("GPBD") || type.endsWith("EPBD")) {
            return 8;
        }
        if (type.endsWith("GPFD") || type.endsWith("EPFD")) {
            return 16;
        }
        if (type.endsWith("X2CS") || type.endsWith("X2CA")) {
            return 2;
        }
        // 主控板的 4 个 GE 口就是现场看到的 0/6/0-3，上行 VLAN 透传就挂在这几个口上
        if (type.endsWith("SCUN") || type.endsWith("SCUH")) {
            return 4;
        }
        return 0;
    }

    // ------------------------------------------------------------ 端口光模块

    /** 带 F/S/P 的表格行：0/1/0   GPON   up   normal   -8.12 */
    private static final Pattern PORT_ROW_FSP = Pattern.compile(
            "^\\s*(\\d{1,2})/(\\d{1,2})/(\\d{1,2})\\s+(.*)$");

    /** 只有端口号的端口行：P0 ... / Port 0 ... */
    private static final Pattern PORT_ROW_SIMPLE = Pattern.compile(
            "(?i)^\\s*(?:P|port\\s*)?(\\d{1,2})\\s+(.*)$");

    /** 键值写法：Port ID : 0/1/0 */
    private static final Pattern PORT_KEY_FSP = Pattern.compile(
            "(?i)port\\s*(?:id)?\\s*[:：]\\s*(\\d{1,2})/(\\d{1,2})/(\\d{1,2})");

    /**
     * 光模块状态关键字，取值来自华为 Optic Status：
     * normal 在位、absence 不在位、abnormal 故障、mismatch 类型不匹配。
     */
    private static final Pattern OPTIC_STATUS_WORD = Pattern.compile(
            "(?i)\\b(normal|absence|absent|abnormal|mismatch|present|fault|not\\s+installed)\\b");

    private static final Pattern RX_POWER = Pattern.compile(
            "(-?\\d{1,2}(?:\\.\\d+)?)\\s*dBm", Pattern.CASE_INSENSITIVE);

    /** 表头写了 RXPower(dBm)、数值列只留数字的版本，按小数和量程兜底 */
    private static final Pattern RX_POWER_LOOSE = Pattern.compile("(?<![\\d.])(-?\\d{1,2}\\.\\d+)(?![\\d.])");

    /**
     * 解析端口光模块状态，供页面点亮「在位」指示灯。
     *
     * <p>两种回显都认：表格行（{@code 0/1/0 GPON normal -8.12}）和键值块
     * （{@code Port ID : 0/1/0} 换行 {@code Optic status : normal}）。都没有时返回空表，
     * 页面显示灰灯（未采集），不会误报「不在位」。</p>
     */
    public static List<OltPort> parsePorts(String text) {
        List<OltPort> ports = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return ports;
        }
        for (String line : text.split("\\r?\\n")) {
            OltPort port = parsePortRow(line);
            if (port != null) {
                ports.add(port);
            }
        }
        if (ports.isEmpty()) {
            ports.addAll(parsePortBlocks(text));
        }
        return mergePorts(ports);
    }

    private static OltPort parsePortRow(String line) {
        Matcher fsp = PORT_ROW_FSP.matcher(line);
        Integer frameNo = null;
        Integer slotNo = null;
        Integer portNo;
        String rest;
        if (fsp.matches()) {
            frameNo = Integer.parseInt(fsp.group(1));
            slotNo = Integer.parseInt(fsp.group(2));
            portNo = Integer.parseInt(fsp.group(3));
            rest = fsp.group(4);
        } else {
            Matcher simple = PORT_ROW_SIMPLE.matcher(line);
            if (!simple.matches()) {
                return null;
            }
            portNo = Integer.parseInt(simple.group(1));
            rest = simple.group(2);
            // 只有端口号的写法容易撞上 ONT 统计那些行，必须本行就带光模块信息才认
            if (!hasOpticInfo(line)) {
                return null;
            }
        }
        return buildPort(frameNo, slotNo, portNo, rest);
    }

    private static List<OltPort> parsePortBlocks(String text) {
        List<OltPort> ports = new ArrayList<>();
        Matcher matcher = PORT_KEY_FSP.matcher(text);
        int start = -1;
        Integer frameNo = null;
        Integer slotNo = null;
        Integer portNo = null;
        while (matcher.find()) {
            if (start >= 0) {
                OltPort port = buildPort(frameNo, slotNo, portNo, text.substring(start, matcher.start()));
                if (port != null) {
                    ports.add(port);
                }
            }
            frameNo = Integer.parseInt(matcher.group(1));
            slotNo = Integer.parseInt(matcher.group(2));
            portNo = Integer.parseInt(matcher.group(3));
            start = matcher.end();
        }
        if (start >= 0) {
            OltPort port = buildPort(frameNo, slotNo, portNo, text.substring(start));
            if (port != null) {
                ports.add(port);
            }
        }
        return ports;
    }

    private static OltPort buildPort(Integer frameNo, Integer slotNo, Integer portNo, String block) {
        if (block == null || block.isBlank()) {
            return null;
        }
        String rxPower = null;
        Matcher rx = RX_POWER.matcher(block);
        if (rx.find()) {
            rxPower = rx.group(1) + " dBm";
        } else {
            Matcher loose = RX_POWER_LOOSE.matcher(block);
            while (loose.find()) {
                double value = Double.parseDouble(loose.group(1));
                // 收光功率量程，避免把端口号/温度之类的小数当成光功率
                if (value >= -50 && value <= 10) {
                    rxPower = loose.group(1) + " dBm";
                    break;
                }
            }
        }

        String opticStatus = null;
        Boolean present = null;
        Matcher status = OPTIC_STATUS_WORD.matcher(block);
        if (status.find()) {
            String word = status.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            opticStatus = word;
            present = !(word.startsWith("absence") || word.startsWith("absent") || word.contains("not installed"));
        }
        if (opticStatus == null && rxPower == null) {
            return null;
        }
        OltPort port = new OltPort();
        port.setFrameNo(frameNo);
        port.setSlotNo(slotNo);
        port.setPortNo(portNo);
        port.setPortName("P" + portNo);
        port.setOpticStatus(opticStatus);
        port.setOpticalPresent(present != null ? present : (rxPower != null ? Boolean.TRUE : null));
        port.setRxPower(Boolean.FALSE.equals(port.getOpticalPresent()) ? null : rxPower);
        return port;
    }

    private static boolean hasOpticInfo(String line) {
        return OPTIC_STATUS_WORD.matcher(line).find() || RX_POWER.matcher(line).find();
    }

    /**
     * 同一端口多行出现时合并：保留先出现的信息，缺的字段用后面的补。
     */
    private static List<OltPort> mergePorts(List<OltPort> ports) {
        Map<String, OltPort> merged = new LinkedHashMap<>();
        for (OltPort port : ports) {
            String key = port.getFrameNo() + "/" + port.getSlotNo() + "/" + port.getPortNo();
            OltPort exists = merged.get(key);
            if (exists == null) {
                merged.put(key, port);
                continue;
            }
            if (exists.getOpticStatus() == null) {
                exists.setOpticStatus(port.getOpticStatus());
                exists.setOpticalPresent(port.getOpticalPresent());
            }
            if (exists.getRxPower() == null) {
                exists.setRxPower(port.getRxPower());
            }
        }
        return new ArrayList<>(merged.values());
    }

    // ------------------------------------------------------------ ONT 数量

    /**
     * 汇总行（GPON）：{@code In port 0/ 1/0 , the total of ONTs are: 23, online: 22}。
     * 注意设备会把端口写成带空格的 {@code 0/ 1/0}，逗号前也带空格。
     * EPON 版本只给总数不带在线数，在线数得自己从表格里数。
     */
    private static final Pattern ONT_SUMMARY = Pattern.compile(
            "(?i)In\\s+port\\s+(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*,\\s*the\\s+total\\s+of\\s+ONTs"
                    + "\\s+are\\s*[:：]?\\s*(\\d+)(?:\\s*,\\s*online\\s*[:：]?\\s*(\\d+))?");

    /**
     * ONT 明细行：F/S/P ONT-ID SN Control Run Config Match。
     * 设备会把 F/S/P 写成带空格的 {@code 0/ 1/0}，所以斜杠后面允许空格。
     */
    private static final Pattern ONT_ROW = Pattern.compile(
            "^\\s*(\\d{1,2})/\\s*(\\d{1,2})/\\s*(\\d{1,2})\\s+(\\d{1,3})\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)",
            Pattern.MULTILINE);

    /**
     * ONT 明细行（取明细用）。和 {@link #ONT_ROW} 的区别：<strong>不锚定行首</strong>。
     * 分页时设备会把提示直接打在行中间：
     * {@code ( Press 'Q' to break ) ----                   0/ 1/0   32  534B... active online ...}，
     * 锚定行首的写法会漏掉这些 ONT，数量对得上、明细却少几条。
     * SN 限定成十六进制（GPON）或带横杠的十六进制（EPON 的 MAC），避免把 Description 表
     * （{@code 0/ 1/0   0   SK-D748S}）也当成明细行。
     */
    private static final Pattern ONT_DETAIL = Pattern.compile(
            "(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s+(\\d{1,3})\\s+([0-9A-Fa-f][0-9A-Fa-f-]{7,20})"
                    + "\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)");

    /** Description 表：{@code 0/ 1/0   0   SK-D748S}，行尾那个就是 ONT 型号 */
    private static final Pattern ONT_DESCRIPTION = Pattern.compile(
            "(?m)(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s+(\\d{1,3})\\s+(\\S+)\\s*$");

    /**
     * 解析整块板的 ONT 数量。
     *
     * <p>命令是 config 视图的 {@code display ont info 0 <槽> all}——参数用空格分隔，不是 0/1，
     * 而且必须先进 config 视图，用户视图下设备不认这条。一条命令就把这块板所有端口的
     * ONT 明细都带回来了，按端口分组统计。优先用每个端口末尾的汇总行，
     * 没有汇总行时按明细行数，Run state 为 online（EPON 是 up）的算在线。</p>
     *
     * @return key 是端口号，value 是 {总数, 在线数}
     */
    public static Map<Integer, int[]> parseOntCounts(String text) {
        Map<Integer, int[]> counts = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return counts;
        }
        Matcher summary = ONT_SUMMARY.matcher(text);
        while (summary.find()) {
            int portNo = Integer.parseInt(summary.group(3));
            int total = Integer.parseInt(summary.group(4));
            int online = summary.group(5) == null ? countOnline(text, portNo) : Integer.parseInt(summary.group(5));
            counts.put(portNo, new int[]{total, online});
        }
        // 明细行兜底：整段没有汇总行（部分版本只有表格），或者只有部分端口带汇总行
        // （分页截断、只在有 ONT 的口打汇总）时，用表格行数补上——否则这些口会被当成 0 个 ONT，
        // 页面就把明明有 ONT 在线的口画成离线。
        Map<Integer, int[]> fromRows = new LinkedHashMap<>();
        Matcher row = ONT_ROW.matcher(text);
        while (row.find()) {
            int portNo = Integer.parseInt(row.group(3));
            int[] stat = fromRows.computeIfAbsent(portNo, key -> new int[]{0, 0});
            stat[0]++;
            if (isOnline(row.group(7))) {
                stat[1]++;
            }
        }
        fromRows.forEach(counts::putIfAbsent);
        return counts;
    }

    private static int countOnline(String text, int portNo) {
        int online = 0;
        Matcher row = ONT_ROW.matcher(text);
        while (row.find()) {
            if (Integer.parseInt(row.group(3)) == portNo && isOnline(row.group(7))) {
                online++;
            }
        }
        return online;
    }

    /**
     * 解析整块板的 ONT 明细：每个端口下有哪些 ONT，各自的 SN、在线/离线、型号描述。
     *
     * <p>页面要看的不是「23 个」这个数字，而是 23 条 ONT 各自的 ID、SN 和在线状态，
     * 所以这里把回显的主表逐行拆出来，再用 Description 表把型号补上。
     * 分页提示打在行中间的那几行也一起收进来（见 {@link #ONT_DETAIL}）。</p>
     *
     * @return key 是端口号，value 是该端口下的 ONT 明细（按 ONT ID 升序）
     */
    public static Map<Integer, List<OltOnt>> parseOntDetails(String text) {
        Map<Integer, List<OltOnt>> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Map<String, OltOnt> byKey = new LinkedHashMap<>();
        Matcher row = ONT_DETAIL.matcher(text);
        while (row.find()) {
            int portNo = Integer.parseInt(row.group(3));
            int ontId = Integer.parseInt(row.group(4));
            OltOnt ont = new OltOnt();
            ont.setOntId(ontId);
            ont.setSn(row.group(5));
            ont.setControlFlag(row.group(6));
            ont.setRunState(row.group(7));
            ont.setConfigState(row.group(8));
            ont.setMatchState(row.group(9));
            ont.setOnline(isOnline(row.group(7)));
            result.computeIfAbsent(portNo, key -> new ArrayList<>()).add(ont);
            byKey.put(portNo + "/" + ontId, ont);
        }
        Matcher desc = ONT_DESCRIPTION.matcher(text);
        while (desc.find()) {
            OltOnt ont = byKey.get(Integer.parseInt(desc.group(3)) + "/" + Integer.parseInt(desc.group(4)));
            if (ont != null && (ont.getDescription() == null || ont.getDescription().isBlank())) {
                ont.setDescription(desc.group(5));
            }
        }
        for (List<OltOnt> onts : result.values()) {
            onts.sort(Comparator.comparing(ont -> ont.getOntId() == null ? 0 : ont.getOntId()));
        }
        return result;
    }

    /**
     * 单板温度：{@code The temperature of the board:  41C( 105F)}。
     * 部分版本不支持 display temperature all，只能按槽位一条条问。
     */
    private static final Pattern BOARD_TEMPERATURE = Pattern.compile(
            "(?i)temperature\\s+of\\s+the\\s+board\\s*[:：]?\\s*(-?\\d{1,3})\\s*C");

    public static Integer parseBoardTemperature(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = BOARD_TEMPERATURE.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    /**
     * {@code display board 0/<槽位>} 的端口状态表：
     * {@code 0  GE  absence  1  auto  auto  auto  off  active  offline}
     * 端口号 / 端口类型 / 光模块状态 / ... / 链路状态（online / offline）。
     * 光模块不在位（absence）、链路离线（offline）的口也照记——同步就要把空口的状态记进去。
     */
    private static final Pattern BOARD_PORT_STATE = Pattern.compile(
            "(?im)^\\s*(\\d{1,2})\\s+(?:GE|10GE|XGE|ETH)\\s+"
                    + "(normal|absence|abnormal|mismatch)\\b.*?(online|offline)\\s*$");

    /**
     * 解析 display board 0/<槽位> 回显：端口号 → {光模块状态, 链路状态}。
     * 表头行（Port Port Optic ...）不含类型词，不会误命中。
     */
    public static Map<Integer, String[]> parseBoardPortStates(String text) {
        Map<Integer, String[]> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Matcher matcher = BOARD_PORT_STATE.matcher(text);
        while (matcher.find()) {
            int portNo = Integer.parseInt(matcher.group(1));
            result.put(portNo, new String[]{matcher.group(2), matcher.group(3)});
        }
        return result;
    }

    /** GPON 写 online/offline，EPON 写 up/down */
    private static boolean isOnline(String runState) {
        if (runState == null) {
            return false;
        }
        String state = runState.toLowerCase(Locale.ROOT);
        return state.contains("online") || "up".equals(state);
    }

    // ------------------------------------------------------------ 端口光模块数值

    /**
     * {@code display port state <口>}（接口视图）里的键值行：
     * {@code TX power(dBm)      : 4.23}、{@code Temperature(C)    : 35}。
     */
    private static final Pattern PORT_STATE_ROW = Pattern.compile("^([^:：]{2,48})[:：]\\s*(\\S.*)$");

    /**
     * 解析光模块数值：Optical Module status / Laser state / TX fault /
     * Temperature(C) / TX Bias current(mA) / Supply Voltage(V) / TX power(dBm) / RX power(dBm)。
     *
     * <p>回显里还有 Port Information 段（Port state、Last up time 等），这里只挑光模块的字段；
     * 一个都没认出来（命令不认、端口不存在）返回 null，调用方据此判断要不要继续问下一个口。</p>
     */
    public static OltPort parsePortStateOptics(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        OltPort optics = new OltPort();
        boolean any = false;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("$ ") || line.contains("#")
                    || (!line.contains(":") && !line.contains("："))) {
                continue;
            }
            Matcher row = PORT_STATE_ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            String label = row.group(1).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            String value = row.group(2).trim();
            switch (label) {
                case "optical module status":
                    optics.setModuleStatus(value);
                    any = true;
                    break;
                case "laser state":
                    optics.setLaserState(value);
                    any = true;
                    break;
                case "tx fault":
                    optics.setTxFault(value);
                    any = true;
                    break;
                case "temperature(c)":
                    optics.setOpticTemperature(value + " ℃");
                    any = true;
                    break;
                case "tx bias current(ma)":
                    optics.setBiasCurrent(value + " mA");
                    any = true;
                    break;
                case "supply voltage(v)":
                    optics.setSupplyVoltage(value + " V");
                    any = true;
                    break;
                case "tx power(dbm)":
                    optics.setTxPower(value + " dBm");
                    any = true;
                    break;
                case "rx power(dbm)":
                    optics.setRxPower(value + " dBm");
                    any = true;
                    break;
                default:
                    // Port state / Last up time 这些不是光模块数值
                    break;
            }
        }
        return any ? optics : null;
    }

    // ------------------------------------------------------------ 鸿升光 HSGQ

    /**
     * 鸿升光整机卡片：现场不需要采集板卡信息（{@code show pon-info} 在部分固件上
     * 只回一声命令回显，采不到数据），识别出厂商后直接按型号生成——
     * E04 固定 4 个 PON 口，端口只给名字，ONU 数量页面显示「-」，示意图按型号出。
     */
    public static OltBoard buildHsgqBoard(String model) {
        OltBoard board = new OltBoard();
        board.setSlotNo(0);
        board.setBoardType(model == null || model.isBlank() ? "HSGQ-E04" : model);
        board.setStatus("Up");
        board.setPortCount(4);
        board.setPortMode("EPON");
        board.setBoardKind(BoardKind.SERVICE.name());
        board.setDescription("整机（4 口 EPON）");
        List<OltPort> ports = new ArrayList<>();
        for (int no = 1; no <= 4; no++) {
            OltPort port = new OltPort();
            port.setFrameNo(0);
            port.setSlotNo(0);
            port.setPortNo(no);
            port.setPortName(String.format("PON%02d", no));
            ports.add(port);
        }
        board.setPorts(ports);
        return board;
    }

    /**
     * {@code show pon-info} 的端口行：
     * {@code PON01   Up        5/5}（端口名 / 状态 / 在线数/总数）。
     */
    private static final Pattern HSGQ_PON_PORT = Pattern.compile(
            "(?im)^\\s*PON(\\d{1,2})\\s+(Up|Down)\\s+(\\d{1,3})\\s*/\\s*(\\d{1,3})\\s*$");

    /**
     * {@code show onu-info all} 的数据行：
     * {@code 1/1   c8:21:da:f8:2f:70   Online   TRUE   TRUE   2026/10/25 00:46:10   ONU01/01   NO-DESCRIPTI}。
     * 前面的 1/1 是「PON 口号/ONU 号」，后面跟着 MAC 与在线状态；末行 {@code Total: 8  Online:7  Offline:1}。
     */
    private static final Pattern HSGQ_ONU_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,2})\\s*/\\s*(\\d{1,3})\\s+"
                    + "([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\\s+"
                    + "(Online|Offline)\\b(?:.*?\\b(ONU[0-9]{2}/[0-9]{2})\\b)?");

    /**
     * 鸿升光 {@code show pon-info}：PON 口号 → {在线数, 总数}。
     * 采不到就返回空表，页面上 ONU 列照旧显示「-」，不会把没采到的数据当成 0。
     */
    public static Map<Integer, int[]> parseHsgqPonInfo(String text) {
        Map<Integer, int[]> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Matcher matcher = HSGQ_PON_PORT.matcher(text);
        while (matcher.find()) {
            int ponNo = Integer.parseInt(matcher.group(1));
            int online = Integer.parseInt(matcher.group(3));
            int total = Integer.parseInt(matcher.group(4));
            result.put(ponNo, new int[]{online, total});
        }
        return result;
    }

    /**
     * 鸿升光 {@code show onu-info all}：PON 口号 → 这条 PON 口下的 ONU 列表。
     * EPON 没有 GPON 的 SN，唯一标识就是 MAC，所以 MAC 放在 sn 上（页面显示「SN/MAC」那列）。
     */
    public static Map<Integer, List<OltOnt>> parseHsgqOnuInfo(String text) {
        Map<Integer, List<OltOnt>> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Matcher matcher = HSGQ_ONU_ROW.matcher(text);
        while (matcher.find()) {
            int ponNo = Integer.parseInt(matcher.group(1));
            OltOnt ont = new OltOnt();
            ont.setOntId(Integer.parseInt(matcher.group(2)));
            ont.setSn(matcher.group(3).toUpperCase(Locale.ROOT));
            ont.setRunState(matcher.group(4));
            ont.setOnline("online".equalsIgnoreCase(matcher.group(4)));
            ont.setDescription(matcher.group(5));
            result.computeIfAbsent(ponNo, key -> new ArrayList<>()).add(ont);
        }
        return result;
    }

    // ------------------------------------------------------------ VLAN

    /**
     * {@code display vlan all} 的数据行：
     * {@code "   41   smart  common          6              57             -"}。
     * 表头（VLAN Type Attribute ...）和汇总行（Total: 4）都不是数字开头，靠行首数字天然排除。
     */
    private static final Pattern VLAN_ROW = Pattern.compile(
            "(?m)^\\s*(\\d{1,4})\\s+(\\S+)\\s+(\\S+)\\s+(\\d{1,6})\\s+(\\d{1,6})\\s+(\\S+)\\s*$");

    /** 汇总行 Total: 4（display vlan all 的末尾） */
    private static final Pattern VLAN_TOTAL = Pattern.compile("(?i)\\bTotal\\s*[:：]\\s*(\\d{1,4})");

    /** Native VLAN：只有上行口才有这一行 */
    private static final Pattern NATIVE_VLAN = Pattern.compile("(?i)Native\\s+VLAN\\s*[:：]\\s*(\\d{1,4})");

    /** 端口 VLAN 列表里允许的字符：数字、空白、逗号 */
    private static final Pattern PORT_VLAN_LINE = Pattern.compile("[\\d\\s,]+");

    /**
     * 解析 {@code display vlan all} 的 VLAN 列表。
     */
    public static List<OltVlan> parseVlans(String text) {
        List<OltVlan> vlans = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return vlans;
        }
        Matcher row = VLAN_ROW.matcher(text);
        while (row.find()) {
            OltVlan vlan = new OltVlan();
            vlan.setVlanId(Integer.parseInt(row.group(1)));
            vlan.setType(row.group(2));
            vlan.setAttribute(row.group(3));
            vlan.setStdPortNum(Integer.parseInt(row.group(4)));
            vlan.setServPortNum(Integer.parseInt(row.group(5)));
            vlan.setVlanConNum(optionalInt(row.group(6)));
            vlans.add(vlan);
        }
        return vlans;
    }

    /** display vlan all 末尾的 Total，解析不出返回 0 */
    public static int parseVlanTotal(String text) {
        if (text == null) {
            return 0;
        }
        Matcher matcher = VLAN_TOTAL.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /**
     * 解析 {@code display port vlan 0/<槽位>/<端口>}：这个口放通了哪些 VLAN、Native VLAN 是多少。
     *
     * <p>回显除了几行数字，还有命令本身、Total、Native VLAN 和分页/参数提示，
     * 这里只收「整行都是 VLAN 数字」的行，避免把 Total 或提示里的数字当成 VLAN。</p>
     *
     * @return 解析不出（命令不支持、端口不存在）时返回 null
     */
    public static OltPortVlan parsePortVlan(String text) {
        if (text == null || text.isBlank() || looksUnsupported(text)) {
            return null;
        }
        OltPortVlan portVlan = new OltPortVlan();
        Matcher nativeVlan = NATIVE_VLAN.matcher(text);
        if (nativeVlan.find()) {
            portVlan.setNativeVlan(Integer.parseInt(nativeVlan.group(1)));
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.indexOf('#') >= 0) {
                // 命令回显那一行（MA5683T(config)#display port vlan 0/6/0）
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.contains("total") || lower.contains("native") || lower.contains("display port vlan")
                    || trimmed.contains("<") || trimmed.contains(">") || trimmed.contains("|")) {
                continue;
            }
            if (!PORT_VLAN_LINE.matcher(trimmed).matches()) {
                // 分隔线（-----）和参数提示都不长这样，直接跳过
                continue;
            }
            for (String token : trimmed.split("[\\s,]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                int vlanId = Integer.parseInt(token);
                if (vlanId >= 1 && vlanId <= 4094 && !portVlan.getVlans().contains(vlanId)) {
                    portVlan.getVlans().add(vlanId);
                }
            }
        }
        if (portVlan.getVlans().isEmpty() && portVlan.getNativeVlan() == null) {
            return null;
        }
        portVlan.getVlans().sort(Comparator.naturalOrder());
        return portVlan;
    }

    /**
     * {@code display ip interface brief} 里的 vlanif 行：
     * {@code "vlanif46         10.172.10.254/24      up          up"}。
     * meth0 这种带外管理口不算三层 VLAN 接口，直接跳过。
     */
    private static final Pattern IP_IF_ROW = Pattern.compile(
            "(?mi)^\\s*vlanif(\\d{1,4})\\s+([\\d.]+)/(\\d{1,2})\\s+(\\S+)\\s+(\\S+)\\s*$");

    /**
     * {@code display ip routing-table} 的数据行：
     * {@code "        0.0.0.0/0   Static  60   0          RD   10.172.10.1     vlanif46"}。
     * 只留 Proto=Static 的，Direct/OSPF 这些不是静态路由。
     */
    private static final Pattern ROUTE_ROW = Pattern.compile(
            "(?m)^\\s*([\\d.]+/\\d{1,2})\\s+(\\S+)\\s+(\\d{1,5})\\s+(\\d{1,6})\\s+(\\S+)\\s+([\\d.]+)\\s+(\\S+)\\s*$");

    /** 解析三层 VLAN 接口（vlanif），按 VLAN ID 升序 */
    public static List<OltIpInterface> parseIpInterfaces(String text) {
        List<OltIpInterface> interfaces = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return interfaces;
        }
        Matcher row = IP_IF_ROW.matcher(text);
        while (row.find()) {
            OltIpInterface item = new OltIpInterface();
            item.setVlanId(Integer.parseInt(row.group(1)));
            item.setName("vlanif" + row.group(1));
            item.setIpAddress(row.group(2));
            item.setPrefixLength(Integer.parseInt(row.group(3)));
            item.setMask(prefixToMask(item.getPrefixLength()));
            item.setPhysical(row.group(4));
            item.setProtocol(row.group(5));
            interfaces.add(item);
        }
        interfaces.sort(Comparator.comparing(OltIpInterface::getVlanId));
        return interfaces;
    }

    /** 解析路由表里的静态路由 */
    public static List<OltStaticRoute> parseStaticRoutes(String text) {
        List<OltStaticRoute> routes = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return routes;
        }
        Matcher row = ROUTE_ROW.matcher(text);
        while (row.find()) {
            if (!"static".equalsIgnoreCase(row.group(2))) {
                continue;
            }
            OltStaticRoute route = new OltStaticRoute();
            route.setDestination(row.group(1));
            route.setPreference(Integer.parseInt(row.group(3)));
            route.setCost(Integer.parseInt(row.group(4)));
            route.setNextHop(row.group(6));
            route.setIface(row.group(7));
            routes.add(route);
        }
        return routes;
    }

    // ------------------------------------------------------------ DBA / 线路模板

    /**
     * {@code display dba-profile all} 的数据行，三种回显写法都认：
     * 现网 MA5683T 的 {@code 0  3  No  0  8192  20480  1}——列是
     * Profile-ID / type(纯数字) / Bandwidth compensation / Fix / Assure / Max / Bind times，
     * <strong>总表里没有模板名</strong>（名字要靠 {@code profile-id} 详情的块状回显补）；
     * 还有带名字的表格式 {@code 1  DBA-1  type3  1024  2048  102400}，
     * 和老版本的一行式 {@code 1  DBA-1  type3  fix 1024 assure 2048 max 102400}。
     */
    private static final Pattern DBA_ROW_MA5600 = Pattern.compile(
            "(?im)^\\s*(\\d{1,4})\\s+(\\d)\\s+(\\S+)\\s+(\\d{1,9})\\s+(\\d{1,9})\\s+(\\d{1,9})(?:\\s+\\d{1,4})?\\s*$");

    private static final Pattern DBA_ROW_TABULAR = Pattern.compile(
            "(?im)^\\s*(\\d{1,4})\\s+(\\S+)\\s+(type\\d)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*$");

    private static final Pattern DBA_ROW_INLINE = Pattern.compile(
            "(?im)^\\s*(\\d{1,4})\\s+(\\S+)\\s+(type\\d)\\s+fix\\s+(\\d+)\\s+assure\\s+(\\d+)\\s+max\\s+(\\d+)\\s*$");

    /**
     * {@code display ont-lineprofile gpon all} 的模板行：{@code 1  line-1  ONT  dFEC  OMCI}。
     * 前面三列是 ID / 名字 / 类型（ONT），后面 FEC、OMCI 这些列各版本多少不一，不参与解析。
     */
    private static final Pattern LINE_PROFILE_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,4})\\s+(\\S+)\\s+(\\S+)(?:\\s+.*)?$");

    /**
     * T-CONT 行，两种写法都认：display 回显 {@code T-CONT  1  DBA-profile  1}
     * 与配置脚本 {@code tcont 1 dba-profile-id 100}。
     */
    private static final Pattern LINE_TCONT_ROW = Pattern.compile(
            "(?i)t-?cont\\s+(\\d{1,4})\\s+DBA-profile(?:-id)?\\s+(\\d{1,4})");

    /**
     * {@code GEM port 1  T-CONT 1  Mapping mode VLAN  41}。
     * 各字段分头取：整行里有没有这几段都不一定（有的版本只给 GEM port 编号），
     * 用一个正则带一串可选组会因为惰性匹配全部取到 null，所以拆成四条单独找。
     */
    /** GEM port 行：回显 {@code GEM port 1 ...} 与配置脚本 {@code gem add 0 eth tcont 1} */
    private static final Pattern LINE_GEM_ROW = Pattern.compile(
            "(?i)^\\s*(?:GEM\\s+port\\s+|gem\\s+add\\s+)(\\d{1,4})\\b");

    private static final Pattern LINE_GEM_TCONT = Pattern.compile("(?i)t-?cont\\s+(\\d{1,4})");

    private static final Pattern LINE_GEM_MODE = Pattern.compile("(?i)mapping\\s+mode\\s+(\\S+)");

    /**
     * GEM 映射行（配置脚本）：{@code gem mapping 0 0 vlan 46}——
     * GEM port 号、GEM 内映射序号、映射的 VLAN，页面「GEM 映射ID」那一列就是第二个数字。
     */
    /** 配置脚本里的模板概要：{@code ont-lineprofile gpon profile-id 100 profile-name "FTTH-GPON-100"} */
    private static final Pattern LINE_PROFILE_CONFIG = Pattern.compile(
            "(?i)ont-lineprofile\\s+gpon\\s+profile-id\\s+(\\d{1,4})\\s+profile-name\\s+\"?([^\"\\s]+)\"?");

    /**
     * 明细回显的 T-CONT 块头：{@code <T-CONT  0>       DBA Profile-ID:1}（注意双空格与冒号）。
     */
    private static final Pattern LINE_TCONT_BLOCK = Pattern.compile(
            "(?i)^\\s*<T-CONT\\s+(\\d{1,4})>\\s+DBA\\s*Profile-ID:\\s*(\\d{1,4})");

    /**
     * 明细回显的 GEM 块头：{@code <Gem Index:0>}（冒号后空格、尖括号缺失都容忍），
     * 后面的映射表格行都归它。
     */
    private static final Pattern LINE_GEM_BLOCK = Pattern.compile(
            "(?i)^\\s*<?Gem\\s+Index\\s*:\\s*(\\d{1,4})\\s*>?");

    /**
     * GEM 块内的映射数据行：{@code 0    45   -    -    -    -    -    -}——
     * 映射序号 + VLAN + 后面的列（"-" 或具体值都行）。要求至少三列且当前处于
     * GEM 块内，避免吞掉概要行。
     */
    private static final Pattern LINE_GEM_MAP_DATA = Pattern.compile(
            "^\\s*(\\d{1,4})\\s+(\\d{1,4})(\\s+\\S+)+\\s*$");

    /** 明细回显的块状键值行：{@code Profile-ID        :100} */
    private static final Pattern LINE_KV = Pattern.compile(
            "^([^:：]{2,40})[:：]\\s*(.*)$");

    private static final Pattern LINE_GEM_MAPPING = Pattern.compile(
            "(?i)^\\s*gem\\s+mapping\\s+(\\d{1,4})\\s+(\\d{1,4})(?:\\s+(\\S+)\\s+(\\d{1,4}))?");

    private static final Pattern LINE_GEM_VLAN = Pattern.compile("(?i)vlan\\s+(\\d{1,4})");

    private static final Pattern LINE_TCONT_COUNT = Pattern.compile(
            "(?i)Number\\s+of\\s+T-CONT\\s*[:：]\\s*(\\d{1,4})");

    private static final Pattern LINE_GEM_COUNT = Pattern.compile(
            "(?i)Number\\s+of\\s+GEM\\s*port\\s*[:：]\\s*(\\d{1,4})");

    private static final Pattern[] DBA_ROWS = {DBA_ROW_MA5600, DBA_ROW_TABULAR, DBA_ROW_INLINE};

    /** 解析 DBA 模板列表：模板 ID、名字、type1~5、三个带宽值（kbps） */
    public static List<OltDbaProfile> parseDbaProfiles(String text) {
        List<OltDbaProfile> profiles = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return profiles;
        }
        for (Pattern pattern : DBA_ROWS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                OltDbaProfile profile = new OltDbaProfile();
                profile.setProfileId(Integer.parseInt(matcher.group(1)));
                // MA5600 总表没有名字列，type 在第 2 列；带名字的表格式第 2 列才是名字
                if (pattern == DBA_ROW_MA5600) {
                    profile.setType("type" + matcher.group(2));
                } else {
                    profile.setName(matcher.group(2));
                    profile.setType(matcher.group(3));
                }
                profile.setFixKbps(Integer.parseInt(matcher.group(4)));
                profile.setAssureKbps(Integer.parseInt(matcher.group(5)));
                profile.setMaxKbps(Integer.parseInt(matcher.group(6)));
                profiles.add(profile);
            }
            if (!profiles.isEmpty()) {
                break;
            }
        }
        return profiles;
    }

    /**
     * {@code display dba-profile all detail} 的块状详情：一块一条模板，
     * 含 Profile-name / Profile-ID / type / Fix / Assure / Max / bind-times。
     *
     * <p>总表（display dba-profile all）没有名字列，名字只能从这里按 ID 对回去；
     * detail 解析成功时也能单独当数据源（总表行式对不上时兜底）。</p>
     */
    public static List<OltDbaProfile> parseDbaDetails(String text) {
        List<OltDbaProfile> profiles = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return profiles;
        }
        for (LinkedHashMap<String, String> block : parseOntKeyValueBlocks(text)) {
            OltDbaProfile profile = new OltDbaProfile();
            boolean hasId = false;
            for (Map.Entry<String, String> entry : block.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key.contains("Profile-ID")) {
                    Integer id = toInt(value);
                    if (id != null) {
                        profile.setProfileId(id);
                        hasId = true;
                    }
                } else if (key.contains("Profile-name")) {
                    profile.setName(value);
                } else if (key.equalsIgnoreCase("type")) {
                    profile.setType("type" + value.replaceAll("\\D", ""));
                } else if (key.contains("Fix")) {
                    profile.setFixKbps(toInt(value));
                } else if (key.contains("Assure")) {
                    profile.setAssureKbps(toInt(value));
                } else if (key.contains("Max")) {
                    profile.setMaxKbps(toInt(value));
                }
            }
            if (hasId) {
                profiles.add(profile);
            }
        }
        return profiles;
    }

    /**
     * 解析线路模板列表：模板行之后跟着的 T-CONT / GEM port 行归到这条模板下，
     * 一行一行扫下去（回显就是先模板概要、再逐个 T-CONT 与 GEM port 这么排的）。
     */
    public static List<OltLineProfile> parseLineProfiles(String text) {
        List<OltLineProfile> profiles = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return profiles;
        }
        OltLineProfile current = null;
        OltGemport currentGem = null;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("-")) {
                continue;
            }
            // 概要表头（Profile-ID Profile-name Binding times）与统计行跳过；
            // 明细回显的 "Profile-ID :100" 带冒号，是块头，不能跳
            String lowerLine = line.toLowerCase(Locale.ROOT);
            if ((lowerLine.startsWith("profile") && !line.contains(":"))
                    || lowerLine.startsWith("total") || lowerLine.startsWith("notes:")) {
                continue;
            }
            // 明细回显的 T-CONT 块头：<T-CONT  0>  DBA Profile-ID:1
            Matcher tcontBlock = LINE_TCONT_BLOCK.matcher(line);
            if (tcontBlock.find()) {
                if (current == null) {
                    current = new OltLineProfile();
                    profiles.add(current);
                }
                OltTcont item = new OltTcont();
                item.setTcontId(Integer.parseInt(tcontBlock.group(1)));
                item.setDbaProfileId(Integer.parseInt(tcontBlock.group(2)));
                current.getTconts().add(item);
                continue;
            }
            // 明细回显的 GEM 块头：<Gem Index:0>，之后的映射行都归它
            Matcher gemBlock = LINE_GEM_BLOCK.matcher(line);
            if (gemBlock.find()) {
                if (current == null) {
                    current = new OltLineProfile();
                    profiles.add(current);
                }
                int gemportId = Integer.parseInt(gemBlock.group(1));
                final OltLineProfile owner = current;
                currentGem = owner.getGemports().stream()
                        .filter(g -> gemportId == g.getGemportId())
                        .findFirst()
                        .orElseGet(() -> {
                            OltGemport created = new OltGemport();
                            created.setGemportId(gemportId);
                            owner.getGemports().add(created);
                            return created;
                        });
                continue;
            }
            // 块状键值行（明细回显开头的 Profile-ID / Profile-name）
            Matcher kv = LINE_KV.matcher(line);
            if (kv.matches()) {
                String key = kv.group(1).trim();
                String value = kv.group(2).trim();
                if (key.equalsIgnoreCase("Profile-ID")) {
                    Integer id = toInt(value);
                    if (id != null) {
                        current = new OltLineProfile();
                        current.setProfileId(id);
                        profiles.add(current);
                    }
                } else if (key.equalsIgnoreCase("Profile-name")) {
                    if (current == null) {
                        current = new OltLineProfile();
                        profiles.add(current);
                    }
                    current.setName(value);
                }
                continue;
            }
            Matcher tcontCount = LINE_TCONT_COUNT.matcher(line);
            if (tcontCount.find()) {
                if (current != null) {
                    current.setTcontCount(Integer.parseInt(tcontCount.group(1)));
                }
                continue;
            }
            Matcher gemCount = LINE_GEM_COUNT.matcher(line);
            if (gemCount.find()) {
                if (current != null) {
                    current.setGemportCount(Integer.parseInt(gemCount.group(1)));
                }
                continue;
            }
            Matcher tcont = LINE_TCONT_ROW.matcher(line);
            if (tcont.find()) {
                if (current == null) {
                    current = new OltLineProfile();
                    profiles.add(current);
                }
                OltTcont item = new OltTcont();
                item.setTcontId(Integer.parseInt(tcont.group(1)));
                item.setDbaProfileId(Integer.parseInt(tcont.group(2)));
                current.getTconts().add(item);
                continue;
            }
            Matcher gemMapping = LINE_GEM_MAPPING.matcher(line);
            if (gemMapping.find()) {
                if (current == null) {
                    current = new OltLineProfile();
                    profiles.add(current);
                }
                int gemportId = Integer.parseInt(gemMapping.group(1));
                final OltLineProfile owner = current;
                OltGemport item = owner.getGemports().stream()
                        .filter(g -> gemportId == g.getGemportId())
                        .findFirst()
                        .orElseGet(() -> {
                            OltGemport created = new OltGemport();
                            created.setGemportId(gemportId);
                            owner.getGemports().add(created);
                            return created;
                        });
                item.setMappingId(Integer.parseInt(gemMapping.group(2)));
                if (gemMapping.group(3) != null) {
                    item.setMappingMode(gemMapping.group(3));
                }
                if (gemMapping.group(4) != null) {
                    item.setVlan(gemMapping.group(4));
                }
                OltGemMapping mapping = new OltGemMapping();
                mapping.setIndex(item.getMappingId());
                mapping.setVlan(item.getVlan());
                item.getMappings().add(mapping);
                currentGem = item;
                continue;
            }
            Matcher gem = LINE_GEM_ROW.matcher(line);
            if (gem.find()) {
                if (current == null) {
                    current = new OltLineProfile();
                    profiles.add(current);
                }
                OltGemport item = new OltGemport();
                item.setGemportId(Integer.parseInt(gem.group(1)));
                Matcher gemTcont = LINE_GEM_TCONT.matcher(line);
                if (gemTcont.find()) {
                    item.setTcontId(Integer.parseInt(gemTcont.group(1)));
                }
                Matcher gemMode = LINE_GEM_MODE.matcher(line);
                if (gemMode.find()) {
                    item.setMappingMode(gemMode.group(1));
                }
                Matcher gemVlan = LINE_GEM_VLAN.matcher(line);
                if (gemVlan.find()) {
                    item.setVlan(gemVlan.group(1));
                }
                current.getGemports().add(item);
                currentGem = item;
                continue;
            }
            // GEM 块内的映射数据行：0    45   -    -    -    -（映射序号 + VLAN + 一串 "-" 列）
            Matcher mapData = LINE_GEM_MAP_DATA.matcher(line);
            if (mapData.matches() && currentGem != null) {
                OltGemMapping mapping = new OltGemMapping();
                mapping.setIndex(Integer.parseInt(mapData.group(1)));
                mapping.setVlan(mapData.group(2));
                currentGem.getMappings().add(mapping);
                continue;
            }
            // 配置脚本里的模板概要行：ID + 名字（类型固定 gpon）
            Matcher headConfig = LINE_PROFILE_CONFIG.matcher(line);
            if (headConfig.find()) {
                OltLineProfile profile = new OltLineProfile();
                profile.setProfileId(Integer.parseInt(headConfig.group(1)));
                profile.setName(headConfig.group(2));
                profile.setType("gpon");
                profiles.add(profile);
                current = profile;
                continue;
            }
            // 模板概要行：ID + 名字 + 类型
            Matcher head = LINE_PROFILE_ROW.matcher(line);
            if (head.find()) {
                OltLineProfile profile = new OltLineProfile();
                profile.setProfileId(Integer.parseInt(head.group(1)));
                profile.setName(head.group(2));
                profile.setType(head.group(3));
                profiles.add(profile);
                current = profile;
            }
        }
        // 回显给了个数但没逐条列出时（简略版）也别留空：按个数给个占位编号
        for (OltLineProfile profile : profiles) {
            if (profile.getTcontCount() == null && !profile.getTconts().isEmpty()) {
                profile.setTcontCount(profile.getTconts().size());
            }
            if (profile.getGemportCount() == null && !profile.getGemports().isEmpty()) {
                profile.setGemportCount(profile.getGemports().size());
            }
            // 映射列表里的第一条回填到 mappingId/vlan，页面单值列直接能用
            for (OltGemport gemport : profile.getGemports()) {
                if (gemport.getMappingId() == null && !gemport.getMappings().isEmpty()) {
                    OltGemMapping first = gemport.getMappings().get(0);
                    gemport.setMappingId(first.getIndex());
                    gemport.setVlan(first.getVlan());
                }
            }
        }
        return profiles;
    }

    // ------------------------------------------------------------ 业务流 / ONT 能力模板

    /**
     * 业务流行：{@code INDEX VLAN VLAN PORT F/S/P ONT GEM ...}。
     *
     * <pre>{@code
     * INDEX  VLAN  VLAN  PORT  F/S/P    VPI  VCI  FLOW  FLOW  RX  TX  STATE
     *        ID    ATTR  TYPE
     *   0    45    45    gpon  0/ 1/0   1    0    -     -     -   -   -
     * }</pre>
     *
     * <p>不同版本列数不一样，这里只抓前面固定那几列（索引、VLAN、端口、F/S/P），
     * 后面的 ONT ID / GEM port 按出现顺序取，取不到就留空——页面宁可少一列，
     * 也不能把 VPI/VCI 当 ONT ID。</p>
     */
    /**
     * 配置脚本里的 service-port 行（{@code display current-configuration}）：
     * {@code service-port 0 vlan 41 gpon 0/1/1 ont 21 gemport 1 multi-service user-vlan 41 tag-transform translate}。
     *
     * <p>这一行比表格回显规整，字段含义不会随版本变；回显会被终端宽度折断
     * （tag-transform 常被挤到下一行），所以 user-vlan 之后的部分一律不解析。</p>
     */
    private static final Pattern SERVICE_PORT_CONFIG = Pattern.compile(
            "(?im)^\\s*service-port\\s+(\\d{1,4})\\s+vlan\\s+(\\d{1,4})\\s+gpon\\s+(\\d{1,2})/(\\d{1,2})/(\\d{1,3})"
                    + "\\s+ont\\s+(\\d{1,3})\\s+gemport\\s+(\\d{1,4})(?:\\s+.*?user-vlan\\s+(\\d{1,4}))?");

    /** 解析配置脚本里的 service-port 段 */
    public static List<OltServicePort> parseServicePortsFromConfig(String text) {
        List<OltServicePort> ports = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return ports;
        }
        Matcher matcher = SERVICE_PORT_CONFIG.matcher(text);
        while (matcher.find()) {
            OltServicePort port = new OltServicePort();
            port.setIndex(Integer.parseInt(matcher.group(1)));
            port.setVlanId(Integer.parseInt(matcher.group(2)));
            port.setFrameNo(Integer.parseInt(matcher.group(3)));
            port.setSlotNo(Integer.parseInt(matcher.group(4)));
            port.setPortNo(Integer.parseInt(matcher.group(5)));
            port.setOntId(Integer.parseInt(matcher.group(6)));
            port.setGemport(Integer.parseInt(matcher.group(7)));
            port.setUserVlan(toInt(matcher.group(8)));
            ports.add(port);
        }
        return ports;
    }

    /**
     * TR069 服务器模板（{@code display ont tr069-server-profile all}）。
     *
     * <p>两种回显都认：总表行式 {@code 10  TR069_ACS  http://10.172.10.1:9090/  acs123}（靠 URL 认行），
     * 以及 {@code profile-id} 详情的块状 {@code URL : http://...}。</p>
     */
    private static final Pattern TR069_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,4})\\s+(\\S+)\\s+(https?://\\S+)(?:\\s+(\\S+))?");

    /**
     * 配置脚本写法：{@code ont tr069-server-profile add profile-id 10 profile-name "TR069_ACS"
     * url "http://10.172.10.1:9090/" user "acs123" "<密文>"}。
     */
    private static final Pattern TR069_CONFIG = Pattern.compile(
            "(?i)ont\\s+tr069-server-profile\\s+add\\s+profile-id\\s+(\\d{1,4})\\s+profile-name\\s+\"?([^\"\\s]+)\"?"
                    + ".*?url\\s+\"?([^\\s\"]+)\"?(?:\\s+user\\s+\"?([^\\s\"]+)\"?)?");

    public static List<OltTr069Profile> parseTr069Profiles(String text) {
        List<OltTr069Profile> profiles = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return profiles;
        }
        Matcher config = TR069_CONFIG.matcher(text);
        while (config.find()) {
            OltTr069Profile profile = new OltTr069Profile();
            profile.setProfileId(Integer.parseInt(config.group(1)));
            profile.setName(config.group(2));
            profile.setUrl(config.group(3));
            profile.setUser(config.group(4));
            profiles.add(profile);
        }
        if (!profiles.isEmpty()) {
            return profiles;
        }
        Matcher matcher = TR069_ROW.matcher(text);
        while (matcher.find()) {
            OltTr069Profile profile = new OltTr069Profile();
            profile.setProfileId(Integer.parseInt(matcher.group(1)));
            profile.setName(matcher.group(2));
            profile.setUrl(matcher.group(3));
            profile.setUser(matcher.group(4));
            profiles.add(profile);
        }
        if (!profiles.isEmpty()) {
            return profiles;
        }
        // 回退：块状详情里按键名取值
        for (LinkedHashMap<String, String> block : parseOntKeyValueBlocks(text)) {
            OltTr069Profile profile = new OltTr069Profile();
            boolean hasId = false;
            for (Map.Entry<String, String> entry : block.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key.contains("Profile-ID")) {
                    Integer id = toInt(value);
                    if (id != null) {
                        profile.setProfileId(id);
                        hasId = true;
                    }
                } else if (key.contains("Profile-name")) {
                    profile.setName(value);
                } else if (key.toLowerCase(Locale.ROOT).contains("url")) {
                    profile.setUrl(value);
                } else if (key.toLowerCase(Locale.ROOT).contains("user")) {
                    profile.setUser(value);
                }
            }
            if (hasId) {
                profiles.add(profile);
            }
        }
        return profiles;
    }

    private static final Pattern SERVICE_PORT_ROW = Pattern.compile(
            "^\\s*(\\d{1,4})\\s+(\\d{1,4})\\s+(\\S+)\\s+(\\S+)\\s+(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*/\\s*(\\d{1,3})"
                    + "(?:\\s+(\\d{1,4}))?(?:\\s+(\\d{1,4}))?");

    /**
     * 业务流表格整行：INDEX VLAN-ID VLAN-ATTR PORT-TYPE F/S/P VPI VCI FLOW-TYPE FLOW-PARA RX TX STATE。
     * VPI 是 ONT ID、VCI 是 GEM index（回显 Note 里写明），FLOW TYPE=vlan 时 FLOW PARA
     * 就是用户侧 VLAN，行尾 STATE 是 up/down。
     */
    private static final Pattern SERVICE_PORT_FULL_ROW = Pattern.compile(
            "^\\s*(\\d{1,4})\\s+(\\d{1,4})\\s+(\\S+)\\s+(\\S+)\\s+(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*/\\s*(\\d{1,3})"
                    + "\\s+(\\d{1,3})\\s+(\\d{1,4})\\s+(\\S+)\\s+(\\S+)\\s+\\S+\\s+\\S+\\s+([Uu]p|[Dd]own)\\b.*$");

    public static List<OltServicePort> parseServicePorts(String text) {
        List<OltServicePort> ports = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return ports;
        }
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("-") || line.contains("INDEX") || line.contains("display")) {
                continue;
            }
            OltServicePort port = new OltServicePort();
            Matcher full = SERVICE_PORT_FULL_ROW.matcher(line);
            if (full.matches()) {
                port.setIndex(Integer.parseInt(full.group(1)));
                port.setVlanId(Integer.parseInt(full.group(2)));
                port.setVlanAttr(full.group(3));
                port.setPortType(full.group(4));
                port.setFrameNo(Integer.parseInt(full.group(5)));
                port.setSlotNo(Integer.parseInt(full.group(6)));
                port.setPortNo(Integer.parseInt(full.group(7)));
                port.setOntId(Integer.parseInt(full.group(8)));
                port.setGemport(Integer.parseInt(full.group(9)));
                // FLOW TYPE=vlan 时 FLOW PARA 就是用户侧 VLAN
                if ("vlan".equalsIgnoreCase(full.group(10))) {
                    port.setUserVlan(toInt(full.group(11)));
                }
                port.setState(full.group(12).toLowerCase());
            } else {
                Matcher row = SERVICE_PORT_ROW.matcher(line);
                if (!row.find()) {
                    continue;
                }
                port.setIndex(Integer.parseInt(row.group(1)));
                port.setVlanId(Integer.parseInt(row.group(2)));
                port.setVlanAttr(row.group(3));
                port.setPortType(row.group(4));
                port.setFrameNo(Integer.parseInt(row.group(5)));
                port.setSlotNo(Integer.parseInt(row.group(6)));
                port.setPortNo(Integer.parseInt(row.group(7)));
                port.setOntId(toInt(row.group(8)));
                port.setGemport(toInt(row.group(9)));
            }
            ports.add(port);
        }
        return ports;
    }

    /**
     * ONT 能力模板（{@code display ont-srvprofile gpon all}）：回显是块状 key-value，
     * 一块一条模板，按 Profile-ID / Profile-name 定字段，其余按原样收进 items；
     * TR069 相关的键另收一份，页面单独展示管理参数。
     */
    private static final Pattern TR069_KEY = Pattern.compile("(?i)tr069|acs");

    /** 配置脚本里的能力模板概要：{@code ont-srvprofile gpon profile-id 100 profile-name "100"} */
    private static final Pattern SRV_PROFILE_CONFIG = Pattern.compile(
            "(?i)ont-srvprofile\\s+gpon\\s+profile-id\\s+(\\d{1,4})\\s+profile-name\\s+\"?([^\"\\s]+)\"?");

    /** {@code ont-port pots adaptive 32 eth adaptive 8}：能力协商的端口上限 */
    private static final Pattern SRV_POTS_PORTS = Pattern.compile("(?i)pots\\s+adaptive\\s+(\\d{1,3})");

    private static final Pattern SRV_ETH_PORTS = Pattern.compile("(?i)eth\\s+adaptive\\s+(\\d{1,3})");

    /**
     * {@code display ont-srvprofile gpon all} 的概要表行：{@code 100  100  0}——
     * ID / 名字 / 绑定次数（绑定次数列有的版本没有，可有可无）。
     */
    private static final Pattern SRV_PROFILE_ROW = Pattern.compile(
            "^\\s*(\\d{1,4})\\s+([A-Za-z0-9_.\\-]+)\\s*(\\d{1,8})?\\s*$");

    public static List<OltSrvProfile> parseSrvProfiles(String text) {
        List<OltSrvProfile> profiles = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return profiles;
        }
        OltSrvProfile current = null;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("-") || line.contains("display")) {
                continue;
            }
            // 配置脚本里的模板开头，也可能是 display 回显里的一块新模板
            Matcher head = SRV_PROFILE_CONFIG.matcher(line);
            if (head.find()) {
                current = new OltSrvProfile();
                current.setProfileId(Integer.parseInt(head.group(1)));
                current.setName(head.group(2));
                profiles.add(current);
                continue;
            }
            Matcher pots = SRV_POTS_PORTS.matcher(line);
            Matcher eth = SRV_ETH_PORTS.matcher(line);
            // find() 会推进匹配位置，先各判一次再取值，别重复 find
            boolean hasPots = pots.find();
            boolean hasEth = eth.find();
            if (hasPots || hasEth) {
                if (current == null) {
                    current = new OltSrvProfile();
                    profiles.add(current);
                }
                if (hasPots) {
                    current.setPotsPorts(Integer.parseInt(pots.group(1)));
                }
                if (hasEth) {
                    current.setEthPorts(Integer.parseInt(eth.group(1)));
                }
                continue;
            }
            // 概要表行（display all 的表格）：ID / 名字 / 绑定次数
            Matcher summary = SRV_PROFILE_ROW.matcher(line);
            if (summary.matches()) {
                Integer id = Integer.parseInt(summary.group(1));
                OltSrvProfile existing = profiles.stream()
                        .filter(p -> id.equals(p.getProfileId()))
                        .findFirst()
                        .orElse(null);
                if (existing == null) {
                    current = new OltSrvProfile();
                    current.setProfileId(id);
                    current.setName(summary.group(2));
                    profiles.add(current);
                } else {
                    current = existing;
                    if (existing.getName() == null || existing.getName().isBlank()) {
                        existing.setName(summary.group(2));
                    }
                }
                continue;
            }
            Matcher row = ONT_KV_ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            String key = row.group(1).trim();
            String value = row.group(2).trim();
            // Profile-ID 出现即视为新的一块（display 回显按块给，配置脚本按行给）
            if (key.contains("Profile-ID")) {
                Integer id = toInt(value);
                if (id != null) {
                    current = new OltSrvProfile();
                    current.setProfileId(id);
                    profiles.add(current);
                }
                continue;
            }
            if (current == null) {
                current = new OltSrvProfile();
                profiles.add(current);
            }
            if (key.contains("Profile-name")) {
                current.setName(value);
            } else if (TR069_KEY.matcher(key).find()) {
                current.getTr069().put(key, value);
            } else {
                current.getItems().put(key, value);
            }
        }
        // 没有 Profile-ID 的（解析不出来的）整段丢掉，页面宁可显示「没有模板」也不拿空壳充数
        profiles.removeIf(profile -> profile.getProfileId() == null);
        return profiles;
    }

    private static Integer toInt(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty() || "-".equals(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 流量限速模板（{@code display traffic table ip all}）：
     * {@code 0  traffic_table_0  -  1024000 ...}（索引 / 名称 / CIR / PIR，后面还有
     * CBS/PBS/优先级列，只抓前四列；CIR 为 - 表示没有承诺速率）。
     */
    private static final Pattern TRAFFIC_ROW = Pattern.compile(
            "^\\s*(\\d{1,4})\\s+(\\S+)\\s+(\\d{1,9}|-)\\s+(\\d{1,9}|-)\\s");

    public static List<OltTrafficTable> parseTrafficTables(String text) {
        List<OltTrafficTable> tables = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tables;
        }
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("-") || line.contains("display")
                    || line.toLowerCase().startsWith("index") || line.toLowerCase().startsWith("total")) {
                continue;
            }
            Matcher row = TRAFFIC_ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            OltTrafficTable table = new OltTrafficTable();
            table.setIndex(Integer.parseInt(row.group(1)));
            table.setName(row.group(2));
            table.setCir(toInt(row.group(3)));
            table.setPir(toInt(row.group(4)));
            tables.add(table);
        }
        return tables;
    }

    // ------------------------------------------------------------ ONT 按需查询

    /**
     * ONT 按需查询的块状回显（{@code display ont optical-info / version / register-info}）：
     *
     * <pre>{@code
     * ------------------------------
     * 索引编号          : 2
     * 认证方式          : SN认证
     * ...
     * ------------------------------
     * 索引编号          : 1
     * ...
     * ------------------------------
     * }</pre>
     *
     * <p>分隔线之间是一块（一条 ONT 的一段信息，注册历史有多块），块内一行「键 : 值」。
     * 命令回显行与提示符行不进结果。</p>
     */
    private static final Pattern ONT_KV_ROW = Pattern.compile(
            // 标签最长给到 64：英文回显的标签会被设备填到 40 多列才放冒号（如 Supply voltage alarm threshold(V)）
            "^([^:：]{2,64})[:：]\\s*(.*)$");

    public static List<LinkedHashMap<String, String>> parseOntKeyValueBlocks(String text) {
        List<LinkedHashMap<String, String>> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return blocks;
        }
        LinkedHashMap<String, String> current = null;
        // 没冒号的行暂存：可能是折断的标签前半段（"ONT optical module power" +
        // 下一行 "control capability: ..."），也可能是值的续行（"the TX and RX power can be"
        // + 下一行 "controlled separately"），等下一行到了再判断归谁。
        StringBuilder carry = new StringBuilder();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("$ ") || line.contains("display ont ")
                    || line.contains("(config")) {
                continue;
            }
            // 分隔线结束当前块：register-info 用它分段，optical-info / version 的头尾各一条
            if (line.matches("-{8,}\\s*")) {
                flushCarry(current, carry);
                current = null;
                continue;
            }
            Matcher row = ONT_KV_ROW.matcher(line);
            if (!row.matches()) {
                if (carry.length() > 0) {
                    carry.append(' ');
                }
                carry.append(line);
                continue;
            }
            String key = row.group(1).trim();
            if (key.isEmpty()) {
                continue;
            }
            if (current == null) {
                current = new LinkedHashMap<>();
                blocks.add(current);
            }
            if (carry.length() > 0) {
                String fragment = carry.toString().trim();
                carry.setLength(0);
                String lastKey = lastKeyOf(current);
                if (lastKey == null) {
                    // 块的第一行就是标签前半段：拼给当前键
                    key = fragment + ' ' + key;
                } else if (current.get(lastKey).isEmpty()) {
                    // 上一条键的值是空的：暂存行是它的值
                    current.put(lastKey, fragment);
                } else if (current.get(lastKey).endsWith(fragment)) {
                    // 已经拼过，别重复
                } else {
                    // 上一条键的值有内容：暂存行要么是值的续行（贴回去），
                    // 要么是下一条键的前半段（拼回键名）。值续行的特征是当前键值非空且
                    // 暂存行以小写字母开头（英文回显的标签都以大写/数字开头）
                    if (Character.isLowerCase(fragment.charAt(0))) {
                        current.put(lastKey, current.get(lastKey) + ' ' + fragment);
                    } else {
                        key = fragment + ' ' + key;
                    }
                }
            }
            current.put(key, row.group(2).trim());
        }
        flushCarry(current, carry);
        // 空块（只有分隔线没内容）别返回去
        blocks.removeIf(Map::isEmpty);
        return blocks;
    }

    /** LinkedHashMap 最后插入的键（保持回显顺序用） */
    private static String lastKeyOf(LinkedHashMap<String, String> map) {
        String last = null;
        for (String key : map.keySet()) {
            last = key;
        }
        return last;
    }

    /** 块结束（分隔线/回显结束）时把暂存的续行归给块里最后一条空值键 */
    private static void flushCarry(LinkedHashMap<String, String> current, StringBuilder carry) {
        if (current == null || current.isEmpty() || carry.length() == 0) {
            carry.setLength(0);
            return;
        }
        String lastKey = lastKeyOf(current);
        if (current.get(lastKey).isEmpty()) {
            current.put(lastKey, carry.toString().trim());
        }
        carry.setLength(0);
    }

    /**
     * 单条 ONT 详情里的关键字段：{@code display ont info 0 <槽位> <端口> <ONT ID>}。
     *
     * <p>整板的 {@code all} 表只给 ID / SN / 在线状态，线路模板、业务模板、IP、
     * 最后一次掉线原因只有详单里有，这里按「标签 : 值」逐项挑出来给页面直接显示。
     * 同一台设备中英文回显都见过（language-mode 切换），标签两套都认，
     * 以现场真实回显为准：SN / Line profile ID / Service profile ID / Last down cause…。</p>
     */
    public static OltOntInfo parseOntInfoDetail(String text) {
        OltOntInfo info = new OltOntInfo();
        if (text == null || text.isBlank()) {
            return info;
        }
        info.setOntId(toInt(ontField(text, "ONT-ID", "ONT编号", "ONT ID")));
        info.setSn(ontField(text, "SN", "序列号"));
        info.setDesc(ontField(text, "Description", "描述"));
        info.setIp(ontField(text,
                "ONT IP 0 address/mask",
                "ONT IP 0 地址/掩码", "ONT IP 0地址/掩码"));
        info.setLastDownReason(ontField(text, "Last down cause", "最后一次下线原因"));
        info.setLastUpTime(ontField(text, "Last up time", "最后一次上线时间"));
        info.setLastDownTime(ontField(text, "Last down time", "最后一次下线时间"));
        info.setOnlineDuration(ontField(text, "ONT online duration", "ONT在线时长", "ONT 在线时长"));
        info.setRunState(ontField(text, "Run state", "运行标志"));
        info.setConfigState(ontField(text, "Config state", "配置状态"));
        info.setMatchState(ontField(text, "Match state", "匹配状态"));
        info.setLineProfileId(toInt(ontField(text, "Line profile ID", "线路模板编号")));
        info.setLineProfileName(ontField(text, "Line profile name", "线路模板名称"));
        info.setSrvProfileId(toInt(ontField(text, "Service profile ID", "业务模板编号")));
        info.setSrvProfileName(ontField(text, "Service profile name", "业务模板名称"));
        return info;
    }

    /** 按标签取值：回显里标签后带一串空格再跟冒号，取第一个命中的值 */
    private static String ontField(String text, String... labels) {
        for (String label : labels) {
            Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(label) + "\\s*[:：]\\s*(.*?)\\s*$")
                    .matcher(text);
            if (matcher.find() && !matcher.group(1).isEmpty()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    /**
     * 配置脚本里的 ONT 授权行（{@code display current-configuration ont 0/1/0 1}）：
     * {@code ont add 0 1 sn-auth "4648545474095C80" omci ont-lineprofile-id 100
     * ont-srvprofile-id 100 desc "HG5145F"}。
     *
     * <p>终端宽度会把行折断（ont-srvprofile-id 经常掉到下一行），所以各段之间用
     * 跨行匹配；EPON 的 mac-auth、password-auth 也顺带认。</p>
     */
    private static final Pattern ONT_ADD_CONFIG = Pattern.compile(
            "(?is)ont\\s+add\\s+\\d+\\s+\\d+\\s+(sn-auth|mac-auth|password-auth)\\s+\"?([^\"\\s]+)\"?"
                    + "[\\s\\S]*?ont-lineprofile-id\\s+(\\d{1,4})"
                    + "[\\s\\S]*?ont-srvprofile-id\\s+(\\d{1,4})(?:[\\s\\S]{0,200}?desc\\s+\"([^\"]*)\")?");

    /**
     * 从配置摘录里解析授权信息：SN / 线路模板 ID / 业务模板 ID / 描述。
     * 解不出时字段留空，页面还有原始回显可看。
     */
    public static OltOntInfo parseOntBindInfo(String text) {
        OltOntInfo info = new OltOntInfo();
        if (text == null || text.isBlank()) {
            return info;
        }
        Matcher matcher = ONT_ADD_CONFIG.matcher(text);
        if (matcher.find()) {
            info.setSn(matcher.group(2));
            info.setLineProfileId(toInt(matcher.group(3)));
            info.setSrvProfileId(toInt(matcher.group(4)));
            info.setDesc(matcher.group(5));
        }
        return info;
    }

    /**
     * 表格式回显（{@code display ont optical-info} 单台查询给的是一行表格，不是 key-value 块）：
     *
     * <pre>{@code
     *  ------------------------------------------------------------------
     *  ONT  Rx power(dBm)  Tx power(dBm)  OLT Rx ONT power(dBm)  Temperature(°C)  Voltage(V)
     *  ------------------------------------------------------------------
     *   0    -22.14          2.15           -25.31                 41.20            3.25
     *  ------------------------------------------------------------------
     * }</pre>
     *
     * <p>列名与数据按连续两个以上空格切开对上；表头认「含字母（含中文）」的行，
     * 数据行认「首列是纯数字」的行（单台查询首列就是 ONT ID）。key-value 块
     * 解析不出时（{@link #parseOntKeyValueBlocks} 返回空）用它兜底。</p>
     */
    public static List<LinkedHashMap<String, String>> parseOntTableBlocks(String text) {
        List<LinkedHashMap<String, String>> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return blocks;
        }
        List<String> headers = null;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("$ ") || line.startsWith("-")
                    || line.contains("display ont ") || line.contains("(config")) {
                continue;
            }
            String[] cells = line.split("\\s{2,}");
            if (cells.length < 2) {
                headers = null;
                continue;
            }
            if (cells[0].matches("\\d{1,4}")) {
                // 数据行：和最近的表头对上；表头被终端切行认不出时整行放弃
                if (headers == null) {
                    continue;
                }
                LinkedHashMap<String, String> block = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    block.put(headers.get(i), i < cells.length ? cells[i] : "");
                }
                blocks.add(block);
            } else {
                boolean hasLetters = false;
                for (String cell : cells) {
                    if (cell.matches(".*\\p{L}.*")) {
                        hasLetters = true;
                        break;
                    }
                }
                headers = cells.length >= 3 && hasLetters ? Arrays.asList(cells) : null;
            }
        }
        return blocks;
    }

    /** 24 → 255.255.255.0 */
    public static String prefixToMask(int length) {
        if (length <= 0) {
            return "0.0.0.0";
        }
        if (length >= 32) {
            return "255.255.255.255";
        }
        long bits = 0xFFFFFFFFL << (32 - length);
        bits &= 0xFFFFFFFFL;
        return ((bits >> 24) & 0xFF) + "." + ((bits >> 16) & 0xFF) + "."
                + ((bits >> 8) & 0xFF) + "." + (bits & 0xFF);
    }

    /** 把「-」之类的占位符当成空，其余转数字 */
    private static Integer optionalInt(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed) || "--".equals(trimmed)) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 设备不支持这条命令时的常见说法，采到就别再接着试了。
     */
    public static boolean looksUnsupported(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("command is not found") || lower.contains("unrecognized")
                || lower.contains("unknown command") || lower.contains("failure:");
    }

    private static String clean(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    /**
     * 板卡类别，中文名给页面直接用。
     */
    public enum BoardKind {
        SERVICE("业务板"),
        CONTROL("主控板"),
        UPLINK("上联板"),
        POWER("电源板"),
        INTERFACE("通用接口板"),
        UNKNOWN("未知板卡");

        private final String label;

        BoardKind(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }
}

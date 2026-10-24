package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltIpInterface;
import com.tr069.acs.olt.model.OltPortVlan;
import com.tr069.acs.olt.model.OltStaticRoute;
import com.tr069.acs.olt.model.OltVlan;

import java.util.ArrayList;
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
            "(?i)\\b(?:software\\s+|main\\s+|patch\\s+)?version\\s*[:：]\\s*([A-Za-z0-9][A-Za-z0-9._()\\-]{1,40})");

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
            "(?im)^\\s*(?:main\\s+board\\s+)?mac(?:\\s*address)?\\s*[:：]\\s*"
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
            default:
                candidates = new Pattern[]{MODEL_HUAWEI, MODEL_ZTE, MODEL_FIBERHOME, MODEL_LINE};
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
        if (!counts.isEmpty()) {
            return counts;
        }
        // 没有汇总行（部分版本只有表格）：行数当总数，Run state 数在线
        Matcher row = ONT_ROW.matcher(text);
        while (row.find()) {
            int portNo = Integer.parseInt(row.group(3));
            int[] stat = counts.computeIfAbsent(portNo, key -> new int[]{0, 0});
            stat[0]++;
            if (isOnline(row.group(7))) {
                stat[1]++;
            }
        }
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

    /** GPON 写 online/offline，EPON 写 up/down */
    private static boolean isOnline(String runState) {
        if (runState == null) {
            return false;
        }
        String state = runState.toLowerCase(Locale.ROOT);
        return state.contains("online") || "up".equals(state);
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

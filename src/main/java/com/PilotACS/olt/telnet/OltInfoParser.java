/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.716
 *
 */

package com.PilotACS.olt.telnet;

import com.PilotACS.olt.entity.OltBoard;
import com.PilotACS.olt.model.OltDbaProfile;
import com.PilotACS.olt.model.OltGemMapping;
import com.PilotACS.olt.model.OltGemport;
import com.PilotACS.olt.model.OltLineProfile;
import com.PilotACS.olt.model.OltLinkAggregation;
import com.PilotACS.olt.model.VlanIdExpression;
import com.PilotACS.olt.model.OltServicePort;
import com.PilotACS.olt.model.OltSrvProfile;
import com.PilotACS.olt.model.OltTrafficTable;
import com.PilotACS.olt.model.OltTr069Profile;
import com.PilotACS.olt.model.OltOnt;
import com.PilotACS.olt.model.OltOntInfo;
import com.PilotACS.olt.model.OltTcont;
import com.PilotACS.olt.model.OltPort;
import com.PilotACS.olt.model.OltIpInterface;
import com.PilotACS.olt.model.OltPortVlan;
import com.PilotACS.olt.model.OltStaticRoute;
import com.PilotACS.olt.model.OltOntAutofind;
import com.PilotACS.olt.model.OltVlan;
import com.PilotACS.olt.vendor.OltVendorProfiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 命令行回显解析 */
public final class OltInfoParser {

    /** 产品版本号（华为型号+版本） */
    private static final Pattern VERSION_PRODUCT = Pattern.compile(
            "\\b([A-Z]{2}\\d{3,4}V\\d{3}R\\d{3}C\\d{2,3})\\b");

    /** 华为 VRP 版本 */
    private static final Pattern VERSION_VRP = Pattern.compile("\\bV\\d{3}R\\d{3}(?:C\\d{2,3})?[A-Z0-9]*\\b");

    private static final Pattern VERSION_SEMVER = Pattern.compile(
            "(?i)\\b(?:software\\s+|main\\s+|patch\\s+)?version\\s*[:：]\\s*([A-Za-z0-9][A-Za-z0-9._()\\-_]{1,40})");

    private static final Pattern VERSION_SEMVER_FALLBACK = Pattern.compile("\\bV\\d+\\.\\d+(?:\\.\\d+)+[A-Za-z0-9]*");

    /** 补丁版本 */
    private static final Pattern PATCH = Pattern.compile(
            "(?im)^\\s*patch(?:\\s*version)?\\s*[:：]\\s*([A-Za-z0-9._-]{2,32})");

    /** 背板型号 */
    private static final Pattern BACKPLANE = Pattern.compile(
            "(?im)^\\s*(?:main\\s+board\\s*[:：]?\\s*)?(?:pcb\\s*version|backplane(?:\\s*type)?)"
                    + "\\s*[:：]\\s*([A-Z][A-Z0-9]{3,19})\\b");

    private static final Pattern MAC_ADDRESS = Pattern.compile(
            "(?im)^\\s*(?:main\\s+board\\s+|base\\s+)?mac(?:\\s*address)?\\s*[:：]\\s*"
                    + "([0-9A-Fa-f]{2}(?:[-:][0-9A-Fa-f]{2}){5}|[0-9A-Fa-f]{4}(?:[-:][0-9A-Fa-f]{4}){2})\\b");

    /** 运行时长 */
    private static final Pattern UPTIME = Pattern.compile("(?i)uptime\\s+is\\s+([^\\r\\n]+)");

    /** 板卡行 */
    private static final Pattern BOARD_LINE = Pattern.compile(
            "^\\s*(\\d{1,2})\\s+([A-Za-z][A-Za-z0-9/_.-]{1,31})(?:\\s+(\\d{1,3}))?\\s+(" + statuses() + ")\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** 中兴 show card 的表头 */
    private static final Pattern RACK_SHELF_HEADER = Pattern.compile(
            "(?im)^\\s*rack\\s+shelf\\s+slot\\b");

    /** 带机架/机框列的板卡行 */
    private static final Pattern BOARD_LINE_RACK_SHELF = Pattern.compile(
            "^\\s*\\d{1,2}\\s+\\d{1,2}\\s+(\\d{1,2})\\s+\\d{1,2}\\s+"
                    + "([A-Za-z][A-Za-z0-9/_.-]{1,31})\\s+(\\d{1,3})\\s+(" + statuses() + ")\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** 背板板卡名 */
    private static final Pattern BACKPLANE_BOARD_NAME = Pattern.compile(
            "(?i)^H\\d{3}MAB[A-Z0-9]{0,4}$");

    private static final Pattern BACKPLANE_BOARD = Pattern.compile(
            "(?m)^\\s*\\d{1,2}\\s+(H\\d{3}MAB[A-Z0-9]{0,4})(?=\\s|$)", Pattern.CASE_INSENSITIVE);

    /** 空槽位行 */
    private static final Pattern EMPTY_SLOT_LINE = Pattern.compile("(?m)^\\s*(\\d{1,2})\\s*$");

    /** 温度表表头 */
    private static final Pattern TEMPERATURE_SENSOR_HEADER = Pattern.compile(
            "(?i)(?:sensor\\s+status|current\\s*\\(c\\))");

    /** 温度传感器行 */
    private static final Pattern TEMPERATURE_SENSOR_ROW = Pattern.compile(
            "^\\s*(\\d{1,2})\\s+(\\S+)\\s+(\\d{1,2})\\s+([A-Za-z]+)\\s+(-?\\d{1,3})\\b",
            Pattern.MULTILINE);

    /** 简表温度行 */
    private static final Pattern TEMPERATURE_ROW = Pattern.compile(
            "^\\s*(\\d{1,2})\\s+[A-Za-z][A-Za-z0-9/_.-]{1,31}\\s+(-?\\d{1,3})(?:\\s|$)",
            Pattern.MULTILINE);

    private static final Pattern TEMPERATURE_WORD = Pattern.compile("(?i)temperature");

    private OltInfoParser() {
    }

    private static String statuses() {
        return "normal|active[_ ]normal|standby[_ ]normal|active|standby|inservice|in[_ ]service|in[_ ]position|"
                + "working|online|up|fault|faulty|abnormal|offline|failed|config|configured|auto[_ ]?find|"
                + "not[_ ]installed|out[_ ]of[_ ]service|busy|unknown|unregistered|initializing";
    }

    /** 从版本回显里提取型号 */
    public static String extractModel(OltVendor vendor, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return OltVendorProfiles.of(vendor).extractModel(text);
    }

    /** 提取软件版本 */
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

    /** 提取补丁版本 */
    public static String extractPatchVersion(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = PATCH.matcher(text);
        return matcher.find() ? clean(matcher.group(1)) : null;
    }

    /** 提取背板型号 */
    public static String extractBackplane(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = BACKPLANE.matcher(text);
        return matcher.find() ? clean(matcher.group(1)) : null;
    }

    /** 从板卡回显里找背板型号 */
    public static String extractBackplaneBoard(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = BACKPLANE_BOARD.matcher(text);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** 提取主控 MAC */
    public static String extractMacAddress(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = MAC_ADDRESS.matcher(text);
        return matcher.find() ? clean(matcher.group(1)).toUpperCase(Locale.ROOT) : null;
    }

    /** 提取运行时长并转中文格式 */
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

    /** 解析板卡列表，已按槽位排序 */
    public static List<OltBoard> parseBoards(String text) {
        List<OltBoard> boards = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return boards;
        }
        Pattern boardPattern = RACK_SHELF_HEADER.matcher(text).find() ? BOARD_LINE_RACK_SHELF : BOARD_LINE;
        Matcher matcher = boardPattern.matcher(text);
        while (matcher.find()) {
            int slot = Integer.parseInt(matcher.group(1));
            boolean duplicated = boards.stream().anyMatch(b -> b.getSlotNo() == slot);
            if (duplicated) {
                continue;
            }
            String boardType = clean(matcher.group(2));
            if (boardType != null && BACKPLANE_BOARD_NAME.matcher(boardType).matches()) {
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

    /** 解析机框槽位数 */
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

    /** 解析板卡温度，返回「槽位 -> 温度」 */
    public static Map<Integer, Integer> parseTemperatures(String text) {
        Map<Integer, Integer> temperatures = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return temperatures;
        }
        // 别把板卡表、端口表当成温度表：必须有 temperature 字样或温度表表头
        if (!TEMPERATURE_WORD.matcher(text).find() && !TEMPERATURE_SENSOR_HEADER.matcher(text).find()) {
            return temperatures;
        }
        if (TEMPERATURE_SENSOR_HEADER.matcher(text).find()) {
            Matcher sensor = TEMPERATURE_SENSOR_ROW.matcher(text);
            while (sensor.find()) {
                String slotRaw = sensor.group(1);
                String tempRaw = sensor.group(5);
                if (slotRaw == null || tempRaw == null) {
                    continue;
                }
                int slot;
                int temperature;
                try {
                    slot = Integer.parseInt(slotRaw);
                    temperature = Integer.parseInt(tempRaw);
                } catch (NumberFormatException ignored) {
                    continue;
                }
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

    /** 判断板卡类别 */
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

    /** 生成板卡描述 */
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

    /** 按板卡型号猜端口类型 */
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

    /** 常见板卡的已知端口数 */
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
        if (type.endsWith("SCUN") || type.endsWith("SCUH")) {
            return 4;
        }
        return 0;
    }

    /** 带 F/S/P 的表格行 */
    private static final Pattern PORT_ROW_FSP = Pattern.compile(
            "^\\s*(\\d{1,2})/(\\d{1,2})/(\\d{1,2})\\s+(.*)$");

    /** 只有端口号的端口行 */
    private static final Pattern PORT_ROW_SIMPLE = Pattern.compile(
            "(?i)^\\s*(?:P|port\\s*)?(\\d{1,2})\\s+(.*)$");

    /** 键值写法端口行 */
    private static final Pattern PORT_KEY_FSP = Pattern.compile(
            "(?i)port\\s*(?:id)?\\s*[:：]\\s*(\\d{1,2})/(\\d{1,2})/(\\d{1,2})");

    /** 光模块状态关键字 */
    private static final Pattern OPTIC_STATUS_WORD = Pattern.compile(
            "(?i)\\b(normal|absence|absent|abnormal|mismatch|present|fault|not\\s+installed)\\b");

    private static final Pattern RX_POWER = Pattern.compile(
            "(-?\\d{1,2}(?:\\.\\d+)?)\\s*dBm", Pattern.CASE_INSENSITIVE);

    /** 数值列只留数字时的收光功率兜底 */
    private static final Pattern RX_POWER_LOOSE = Pattern.compile("(?<![\\d.])(-?\\d{1,2}\\.\\d+)(?![\\d.])");

    /** 解析端口光模块状态 */
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

    /** 同一端口多行出现时合并 */
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

    /** ONT 汇总行 */
    private static final Pattern ONT_SUMMARY = Pattern.compile(
            "(?i)In\\s+port\\s+(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*,\\s*the\\s+total\\s+of\\s+ONTs"
                    + "\\s+are\\s*[:：]?\\s*(\\d+)(?:\\s*,\\s*online\\s*[:：]?\\s*(\\d+))?");

    /** Run state 取值 */
    private static final String RUN_STATES =
            "(online|offline|up|down|los|dyinggasp|working|alarm)";

    private static final Pattern ONT_ROW = Pattern.compile(
            "^\\s*(\\d{1,2})/\\s*(\\d{1,2})/\\s*(\\d{1,2})\\s+(\\d{1,3})\\s+(\\S+)\\s+(\\S+)\\s+"
                    + RUN_STATES + "\\s+(\\S+)\\s+(\\S+)",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** ONT 明细行 */
    private static final Pattern ONT_DETAIL = Pattern.compile(
            "(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s+(\\d{1,3})\\s+([0-9A-Fa-f][0-9A-Fa-f-]{7,20})"
                    + "\\s+(\\S+)\\s+" + RUN_STATES + "\\s+(\\S+)\\s+(\\S+)",
            Pattern.CASE_INSENSITIVE);

    /** ONT Description 表行 */
    private static final Pattern ONT_DESCRIPTION = Pattern.compile(
            "(?m)(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s+(\\d{1,3})\\s+(\\S+)\\s*$");

    /** 解析整块板的 ONT 数量 */
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

    /** 解析整块板的 ONT 明细 */
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

    /** 单板温度 */
    private static final Pattern BOARD_TEMPERATURE = Pattern.compile(
            "(?i)temperature\\s+of\\s+the\\s+board\\s*[:：]?\\s*(-?\\d{1,3})\\s*C");

    public static Integer parseBoardTemperature(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = BOARD_TEMPERATURE.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    /** 板卡端口状态表 */
    private static final Pattern BOARD_PORT_STATE = Pattern.compile(
            "(?im)^\\s*(\\d{1,2})\\s+(?:GE|10GE|XGE|ETH)\\s+"
                    + "(normal|absence|abnormal|mismatch)\\b.*?(online|offline)\\s*$");

    /** 解析板卡端口状态，端口号 → {光模块状态, 链路状态} */
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

    private static boolean isOnline(String runState) {
        if (runState == null) {
            return false;
        }
        String state = runState.toLowerCase(Locale.ROOT);
        return state.contains("online") || "up".equals(state);
    }

    /** display port state 键值行 */
    private static final Pattern PORT_STATE_ROW = Pattern.compile("^([^:：]{2,48})[:：]\\s*(\\S.*)$");

    /** 解析光模块数值 */
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
                    break;
            }
        }
        return any ? optics : null;
    }

    /** display port state all 口块开头 */
    private static final Pattern PORT_STATE_ALL_PORT = Pattern.compile(
            "(?i)^\\s*Port\\s*[:：]?\\s*(\\d+)\\b");

    /** 解析 display port state all 的多口回显 */
    public static Map<Integer, OltPort> parsePortStateOpticsAll(String text) {
        Map<Integer, OltPort> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String[] lines = text.split("\\R");
        StringBuilder block = new StringBuilder();
        Integer currentPort = null;
        for (String line : lines) {
            Matcher m = PORT_STATE_ALL_PORT.matcher(line);
            if (m.find()) {
                if (currentPort != null && block.length() > 0) {
                    OltPort optics = parsePortStateOptics(block.toString());
                    if (optics != null) {
                        result.put(currentPort, optics);
                    }
                }
                currentPort = Integer.parseInt(m.group(1));
                block.setLength(0);
                continue;
            }
            if (currentPort != null) {
                block.append(line).append('\n');
            }
        }
        if (currentPort != null && block.length() > 0) {
            OltPort optics = parsePortStateOptics(block.toString());
            if (optics != null) {
                result.put(currentPort, optics);
            }
        }
        return result;
    }

    /** display vlan all 数据行 */
    private static final Pattern VLAN_ROW = Pattern.compile(
            "(?m)^\\s*(\\d{1,4})\\s+(\\S+)\\s+(\\S+)\\s+(\\d{1,6})\\s+(\\d{1,6})\\s+(\\S+)\\s*$");

    /** VLAN 汇总行 */
    private static final Pattern VLAN_TOTAL = Pattern.compile("(?i)\\bTotal\\s*[:：]\\s*(\\d{1,4})");

    /** Native VLAN 行 */
    private static final Pattern NATIVE_VLAN = Pattern.compile("(?i)Native\\s+VLAN\\s*[:：]\\s*(\\d{1,4})");

    /** 端口 VLAN 列表行 */
    private static final Pattern PORT_VLAN_LINE = Pattern.compile("[\\d\\s,]+");

    /** 解析 VLAN 列表 */
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

    /** 解析 VLAN 总数 */
    public static int parseVlanTotal(String text) {
        if (text == null) {
            return 0;
        }
        Matcher matcher = VLAN_TOTAL.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** 解析端口 VLAN 放通情况 */
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
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.contains("total") || lower.contains("native") || lower.contains("display port vlan")
                    || trimmed.contains("<") || trimmed.contains(">") || trimmed.contains("|")) {
                continue;
            }
            if (!PORT_VLAN_LINE.matcher(trimmed).matches()) {
                continue;
            }
            for (String token : trimmed.split("[\\s,]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                int vlanId;
                try {
                    vlanId = Integer.parseInt(token);
                } catch (NumberFormatException ignored) {
                    continue;
                }
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

    // ---- 配置脚本解析（display current-configuration） ----

    /** 配置脚本 VLAN 定义行 */
    private static final Pattern VLAN_CONFIG_DEF = Pattern.compile(
            "(?im)^\\s*vlan\\s+(\\d{1,4})(?:\\s+to\\s+(\\d{1,4}))?\\s+(?:smart|common)\\s*$");

    /** 配置脚本端口放通行 */
    private static final Pattern PORT_VLAN_CONFIG = Pattern.compile(
            "(?im)^\\s*port\\s+vlan\\s+(\\d{1,4})(?:\\s+to\\s+(\\d{1,4}))?\\s+0/(\\d{1,2})/(\\d{1,2})\\s*$");

    /** 从配置脚本解析 VLAN 定义列表 */
    public static List<OltVlan> parseVlansFromConfig(String text) {
        List<OltVlan> vlans = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return vlans;
        }
        Set<Integer> seen = new TreeSet<>();
        Matcher m = VLAN_CONFIG_DEF.matcher(text);
        while (m.find()) {
            int start = Integer.parseInt(m.group(1));
            int end = m.group(2) != null ? Integer.parseInt(m.group(2)) : start;
            for (int vid = start; vid <= end && vid <= 4094; vid++) {
                if (vid >= 1 && seen.add(vid)) {
                    OltVlan vlan = new OltVlan();
                    vlan.setVlanId(vid);
                    vlan.setType("smart");
                    vlans.add(vlan);
                }
            }
        }
        return vlans;
    }

    // ------------------------------------------------------------ 链路聚合

    /** 解析链路聚合表格 */
    public static List<OltLinkAggregation> parseLinkAggregations(String text) {
        List<OltLinkAggregation> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Pattern row = Pattern.compile(
                "(\\d+\\s*/\\s*\\d+(?:\\s*/\\s*\\d+)?)\\s+(ingress|egress)\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = row.matcher(text);
        while (m.find()) {
            OltLinkAggregation group = new OltLinkAggregation();
            group.setMasterPort(m.group(1).replaceAll("\\s+", ""));
            group.setMode(m.group(2).toLowerCase(Locale.ROOT));
            group.setPortNum(Integer.parseInt(m.group(3)));
            group.setWorkmode(m.group(4));
            group.setMaxLinkNumber("-".equals(m.group(5)) ? null : m.group(5));
            group.setLeastLinkNumber("-".equals(m.group(6)) ? null : m.group(6));
            result.add(group);
        }
        return result;
    }

    /** 从配置脚本解析端口 VLAN 放通 */
    public static List<OltPortVlan> parsePortVlansFromConfig(String text) {
        List<OltPortVlan> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Map<String, OltPortVlan> byIface = new LinkedHashMap<>();
        Matcher m = PORT_VLAN_CONFIG.matcher(text);
        while (m.find()) {
            int vlanStart = Integer.parseInt(m.group(1));
            int vlanEnd = m.group(2) != null ? Integer.parseInt(m.group(2)) : vlanStart;
            int slot = Integer.parseInt(m.group(3));
            int port = Integer.parseInt(m.group(4));
            String key = slot + "/" + port;
            OltPortVlan pv = byIface.computeIfAbsent(key, k -> {
                OltPortVlan v = new OltPortVlan();
                v.setFrameNo(0);
                v.setSlotNo(slot);
                v.setPortNo(port);
                v.setIface("0/" + slot + "/" + port);
                return v;
            });
            for (int vid = vlanStart; vid <= vlanEnd && vid <= 4094; vid++) {
                if (vid >= 1 && !pv.getVlans().contains(vid)) {
                    pv.getVlans().add(vid);
                }
            }
        }
        for (OltPortVlan pv : byIface.values()) {
            pv.getVlans().sort(Comparator.naturalOrder());
            if (!pv.getVlans().isEmpty()) {
                result.add(pv);
            }
        }
        return result;
    }

    /** vlanif 行 */
    private static final Pattern IP_IF_ROW = Pattern.compile(
            "(?mi)^\\s*vlanif(\\d{1,4})\\s+([\\d.]+)/(\\d{1,2})\\s+(\\S+)\\s+(\\S+)\\s*$");

    /** 路由表数据行 */
    private static final Pattern ROUTE_ROW = Pattern.compile(
            "(?m)^\\s*([\\d.]+/\\d{1,2})\\s+(\\S+)\\s+(\\d{1,5})\\s+(\\d{1,6})\\s+(\\S+)\\s+([\\d.]+)\\s+(\\S+)\\s*$");

    /** 解析三层 VLAN 接口，按 VLAN ID 升序 */
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

    /** DBA 总表数据行 */
    private static final Pattern DBA_ROW_MA5600 = Pattern.compile(
            "(?im)^\\s*(\\d{1,4})\\s+(\\d)\\s+(\\S+)\\s+(\\d{1,9})\\s+(\\d{1,9})\\s+(\\d{1,9})(?:\\s+\\d{1,4})?\\s*$");

    private static final Pattern DBA_ROW_TABULAR = Pattern.compile(
            "(?im)^\\s*(\\d{1,4})\\s+(\\S+)\\s+(type\\d)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*$");

    private static final Pattern DBA_ROW_INLINE = Pattern.compile(
            "(?im)^\\s*(\\d{1,4})\\s+(\\S+)\\s+(type\\d)\\s+fix\\s+(\\d+)\\s+assure\\s+(\\d+)\\s+max\\s+(\\d+)\\s*$");

    /** 线路模板模板行 */
    private static final Pattern LINE_PROFILE_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,4})\\s+(\\S+)\\s+(\\S+)(?:\\s+.*)?$");

    /** T-CONT 行 */
    private static final Pattern LINE_TCONT_ROW = Pattern.compile(
            "(?i)t-?cont\\s+(\\d{1,4})\\s+DBA-profile(?:-id)?\\s+(\\d{1,4})");

    /** GEM port 行 */
    private static final Pattern LINE_GEM_ROW = Pattern.compile(
            "(?i)^\\s*(?:GEM\\s+port\\s+|gem\\s+add\\s+)(\\d{1,4})\\b");

    private static final Pattern LINE_GEM_TCONT = Pattern.compile("(?i)t-?cont\\s+(\\d{1,4})");

    private static final Pattern LINE_GEM_MODE = Pattern.compile("(?i)mapping\\s+mode\\s+(\\S+)");

    /** 配置脚本模板概要行（gpon/epon 都认） */
    private static final Pattern LINE_PROFILE_CONFIG = Pattern.compile(
            "(?i)ont-lineprofile\\s+(gpon|epon)\\s+profile-id\\s+(\\d{1,4})\\s+profile-name\\s+\"?([^\"\\s]+)\"?");

    /** 明细回显的 T-CONT 块头 */
    private static final Pattern LINE_TCONT_BLOCK = Pattern.compile(
            "(?i)^\\s*<T-CONT\\s+(\\d{1,4})>\\s+DBA\\s*Profile-ID:\\s*(\\d{1,4})");

    /** 明细回显的 GEM 块头 */
    private static final Pattern LINE_GEM_BLOCK = Pattern.compile(
            "(?i)^\\s*<?Gem\\s+Index\\s*:\\s*(\\d{1,4})\\s*>?");

    /** GEM 块内的映射数据行 */
    private static final Pattern LINE_GEM_MAP_DATA = Pattern.compile(
            "^\\s*(\\d{1,4})\\s+(\\d{1,4})(\\s+\\S+)+\\s*$");

    /** 明细回显的块状键值行 */
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

    /** 解析 DBA 模板列表 */
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

    /** 解析 DBA 块状详情 */
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

    /** 解析线路模板列表 */
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
            String lowerLine = line.toLowerCase(Locale.ROOT);
            if ((lowerLine.startsWith("profile") && !line.contains(":"))
                    || lowerLine.startsWith("total") || lowerLine.startsWith("notes:")) {
                continue;
            }
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
            Matcher mapData = LINE_GEM_MAP_DATA.matcher(line);
            if (mapData.matches() && currentGem != null) {
                OltGemMapping mapping = new OltGemMapping();
                mapping.setIndex(Integer.parseInt(mapData.group(1)));
                mapping.setVlan(mapData.group(2));
                currentGem.getMappings().add(mapping);
                continue;
            }
            Matcher headConfig = LINE_PROFILE_CONFIG.matcher(line);
            if (headConfig.find()) {
                OltLineProfile profile = new OltLineProfile();
                profile.setPonType(headConfig.group(1).toLowerCase(Locale.ROOT));
                profile.setProfileId(Integer.parseInt(headConfig.group(2)));
                profile.setName(headConfig.group(3));
                profiles.add(profile);
                current = profile;
                continue;
            }
            Matcher head = LINE_PROFILE_ROW.matcher(line);
            if (head.find()) {
                OltLineProfile profile = new OltLineProfile();
                profile.setProfileId(Integer.parseInt(head.group(1)));
                profile.setName(head.group(2));
                profiles.add(profile);
                current = profile;
            }
        }
        for (OltLineProfile profile : profiles) {
            if (profile.getTcontCount() == null && !profile.getTconts().isEmpty()) {
                profile.setTcontCount(profile.getTconts().size());
            }
            if (profile.getGemportCount() == null && !profile.getGemports().isEmpty()) {
                profile.setGemportCount(profile.getGemports().size());
            }
            for (OltGemport gemport : profile.getGemports()) {
                if (gemport.getMappingId() == null && !gemport.getMappings().isEmpty()) {
                    OltGemMapping first = gemport.getMappings().get(0);
                    gemport.setMappingId(first.getIndex());
                    gemport.setVlan(first.getVlan());
                }
            }
        }
        profiles.removeIf(profile -> profile.getProfileId() == null);
        return profiles;
    }

    /** 配置脚本 service-port 行 */
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

    /** TR069 模板概要行（带 ACS 地址列的表格） */
    private static final Pattern TR069_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,4})\\s+(\\S+)\\s+(https?://\\S+)(?:\\s+(\\S+))?");

    /** TR069 模板概要行（只有 ID/名称/绑定数三列，如华为 display ont tr069-server-profile all） */
    private static final Pattern TR069_SUMMARY_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,4})\\s+(\\S+)\\s+(\\d{1,6})\\s*$");

    /** 完整 ACS 地址：scheme 后面必须有内容，防终端折行截出来的 "http://" 冒充地址 */
    private static final Pattern TR069_FULL_URL =
            Pattern.compile("^(https?|tr069)://\\S+$", Pattern.CASE_INSENSITIVE);

    /** 模板详情的 key : value 行（key 以字母开头，排除 URL 折行出来的 "//10.x.x.x:9090/" 之类） */
    private static final Pattern TR069_DETAIL_KV = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9 /-]*)\\s*:\\s*(.*)$");

    /** 配置脚本 TR069 模板行 */
    private static final Pattern TR069_CONFIG = Pattern.compile(
            "(?i)ont\\s+tr069-server-profile\\s+add\\s+profile-id\\s+(\\d{1,4})\\s+profile-name\\s+\"?([^\"\\s]+)\"?"
                    + ".*?url\\s+\"?([^\\s\"]+)\"?(?:\\s+user\\s+\"?([^\\s\"]+)\"?)?");

    /** 解析 TR069 模板列表 */
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
        Matcher summary = TR069_SUMMARY_ROW.matcher(text);
        while (summary.find()) {
            OltTr069Profile profile = new OltTr069Profile();
            profile.setProfileId(Integer.parseInt(summary.group(1)));
            profile.setName(summary.group(2));
            profile.setBindTimes(Integer.parseInt(summary.group(3)));
            profiles.add(profile);
        }
        if (!profiles.isEmpty()) {
            return profiles;
        }
        for (LinkedHashMap<String, String> block : parseOntKeyValueBlocks(text)) {
            OltTr069Profile profile = new OltTr069Profile();
            boolean hasId = false;
            for (Map.Entry<String, String> entry : block.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key.toLowerCase(Locale.ROOT).contains("profile-id")) {
                    Integer id = toInt(value);
                    if (id != null) {
                        profile.setProfileId(id);
                        hasId = true;
                    }
                } else if (key.toLowerCase(Locale.ROOT).contains("profile-name")) {
                    profile.setName(value);
                } else if (key.toLowerCase(Locale.ROOT).contains("url")) {
                    if (value != null && TR069_FULL_URL.matcher(value).matches()) {
                        profile.setUrl(value);
                    }
                } else if (key.toLowerCase(Locale.ROOT).contains("user")) {
                    if (value != null && !value.isBlank() && !"-".equals(value)) {
                        profile.setUser(value);
                    }
                }
            }
            if (hasId) {
                profiles.add(profile);
            }
        }
        return profiles;
    }

    /**
     * 解析单条 TR069 模板详情（{@code display ont tr069-server-profile profile-id X}）
     * 并按模板 ID 合进列表，补 ACS 地址与登录账号。
     *
     * <p>终端按宽度折行会把 URL 截成 "http://" 加下一行，这里逐行接回来直到地址完整；
     * 接不全的地址宁可不填，也不能把半截 URL 当数据显示。</p>
     */
    public static void mergeTr069ProfileDetail(String text, List<OltTr069Profile> profiles) {
        if (text == null || text.isBlank() || profiles == null || profiles.isEmpty()) {
            return;
        }
        String[] lines = text.split("\\R");
        Integer id = null;
        String url = null;
        String user = null;
        Integer boundTimes = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher kv = TR069_DETAIL_KV.matcher(line);
            if (!kv.matches()) {
                continue;
            }
            String key = kv.group(1).trim();
            String value = kv.group(2).trim();
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("profile-id") && id == null) {
                id = toInt(value);
            } else if (lower.contains("url")) {
                StringBuilder builder = new StringBuilder(value);
                while (!TR069_FULL_URL.matcher(builder).matches() && i + 1 < lines.length) {
                    String next = lines[i + 1].trim();
                    if (next.isEmpty() || TR069_DETAIL_KV.matcher(next).matches()
                            || next.matches("-{8,}.*") || next.matches("(?i)(display|total).*")
                            || next.matches("(?i).*\\(config\\).*")) {
                        break;
                    }
                    builder.append(next);
                    i++;
                }
                if (TR069_FULL_URL.matcher(builder).matches()) {
                    url = builder.toString();
                }
            } else if (lower.contains("user") && !value.isBlank() && !"-".equals(value)) {
                user = value;
            } else if (lower.contains("binding")) {
                Integer bound = toInt(value);
                if (bound != null) {
                    boundTimes = bound;
                }
            }
        }
        if (id == null || (url == null && user == null && boundTimes == null)) {
            return;
        }
        Integer foundId = id;
        String foundUrl = url;
        String foundUser = user;
        Integer foundBound = boundTimes;
        profiles.stream()
                .filter(profile -> foundId.equals(profile.getProfileId()))
                .findFirst()
                .ifPresent(profile -> {
                    if (foundUrl != null) {
                        profile.setUrl(foundUrl);
                    }
                    if (foundUser != null) {
                        profile.setUser(foundUser);
                    }
                    if (foundBound != null && profile.getBindTimes() == null) {
                        profile.setBindTimes(foundBound);
                    }
                });
    }

    private static final Pattern GEM_INDEX = Pattern.compile("<Gem\\s+Index\\s+(\\d+)\\s*>");

    /** 映射表表头 */
    private static final Pattern MAPPING_HEADER =
            Pattern.compile("^\\s*Mapping\\s+VLAN\\s+.*", Pattern.CASE_INSENSITIVE);
    /** 映射数据行 */
    private static final Pattern MAPPING_ROW = Pattern.compile("^\\s*(\\d{1,3})\\s+(\\d{1,4})\\s+.*");

    /** 线路模板详情里的 GEM 索引 */
    public static Integer parseGemIndex(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = GEM_INDEX.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    /** 解析线路模板详情里 VLAN → GEM Port 对应关系 */
    public static Map<Integer, Integer> parseGemVlanPairs(String text) {
        Map<Integer, Integer> pairs = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return pairs;
        }
        boolean header = false;
        boolean rows = false;
        int currentGem = -1;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher gem = GEM_INDEX.matcher(line);
            if (gem.find()) {
                currentGem = Integer.parseInt(gem.group(1));
                header = false;
                rows = false;
                continue;
            }
            if (MAPPING_HEADER.matcher(rawLine).matches()) {
                header = true;
                rows = false;
                continue;
            }
            if (currentGem < 0 || !header) {
                continue;
            }
            if (!rows) {
                if (line.startsWith("-")) {
                    rows = true;
                }
                continue;
            }
            Matcher matcher = MAPPING_ROW.matcher(rawLine);
            if (!matcher.matches()) {
                header = false;
                rows = false;
                continue;
            }
            int vlan = Integer.parseInt(matcher.group(2));
            if (vlan >= 1 && vlan <= VlanIdExpression.MAX_VLAN_ID) {
                pairs.putIfAbsent(vlan, currentGem);
            }
        }
        return pairs;
    }

    private static final Pattern SERVICE_PORT_ROW = Pattern.compile(
            "^\\s*(\\d{1,4})\\s+(\\d{1,4})\\s+(\\S+)\\s+(\\S+)\\s+(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*/\\s*(\\d{1,3})"
                    + "(?:\\s+(\\d{1,4}))?(?:\\s+(\\d{1,4}))?");

    /** 业务流表格整行 */
    private static final Pattern SERVICE_PORT_FULL_ROW = Pattern.compile(
            "^\\s*(\\d{1,4})\\s+(\\d{1,4})\\s+(\\S+)\\s+(\\S+)\\s+(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*/\\s*(\\d{1,3})"
                    + "\\s+(\\d{1,3})\\s+(\\d{1,4})\\s+(\\S+)\\s+(\\S+)\\s+\\S+\\s+\\S+\\s+([Uu]p|[Dd]own)\\b.*$");

    /** 解析业务流表格 */
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

    /** TR069 相关键名 */
    private static final Pattern TR069_KEY = Pattern.compile("(?i)tr069|acs");

    /** 配置脚本能力模板概要行 */
    private static final Pattern SRV_PROFILE_CONFIG = Pattern.compile(
            "(?i)ont-srvprofile\\s+gpon\\s+profile-id\\s+(\\d{1,4})\\s+profile-name\\s+\"?([^\"\\s]+)\"?");

    /** 能力协商端口上限 */
    private static final Pattern SRV_POTS_PORTS = Pattern.compile("(?i)pots\\s+adaptive\\s+(\\d{1,3})");

    private static final Pattern SRV_ETH_PORTS = Pattern.compile("(?i)eth\\s+adaptive\\s+(\\d{1,3})");

    /** 能力模板概要表行 */
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

    /** 流量限速模板行 */
    private static final Pattern TRAFFIC_ROW = Pattern.compile(
            "^\\s*(\\d{1,4})\\s+(\\S+)\\s+(\\d{1,9}|-)\\s+(\\d{1,9}|-)\\s");

    /** 解析流量限速模板 */
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

    /** ONT 按需查询的块状键值行 */
    private static final Pattern ONT_KV_ROW = Pattern.compile(
            "^([^:：]{2,64})[:：]\\s*(.*)$");

    public static List<LinkedHashMap<String, String>> parseOntKeyValueBlocks(String text) {
        List<LinkedHashMap<String, String>> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return blocks;
        }
        LinkedHashMap<String, String> current = null;
        StringBuilder carry = new StringBuilder();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("$ ") || line.contains("display ont ")
                    || line.contains("(config")) {
                continue;
            }
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
                    key = fragment + ' ' + key;
                } else if (current.get(lastKey).isEmpty()) {
                    current.put(lastKey, fragment);
                } else if (current.get(lastKey).endsWith(fragment)) {
                } else {
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
        blocks.removeIf(Map::isEmpty);
        return blocks;
    }

    /** 取最后插入的键 */
    private static String lastKeyOf(LinkedHashMap<String, String> map) {
        String last = null;
        for (String key : map.keySet()) {
            last = key;
        }
        return last;
    }

    /** 把暂存续行归给块里最后一条空值键 */
    private static void flushCarry(LinkedHashMap<String, String> current, StringBuilder carry) {
        if (current == null || current.isEmpty() || carry.length() == 0) {
            carry.setLength(0);
            return;
        }
        String lastKey = lastKeyOf(current);
        String lastVal = current.get(lastKey);
        if (lastVal != null && lastVal.isEmpty()) {
            current.put(lastKey, carry.toString().trim());
        }
        carry.setLength(0);
    }

    /** 解析单条 ONT 详情 */
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
        String mac = ontField(text,
                "MAC address", "MAC 地址", "MAC地址", "ONT MAC address", "MAC");
        if (mac == null) {
            Matcher macFallback = ONT_IPCONFIG_MAC.matcher(text);
            if (macFallback.find()) {
                mac = macFallback.group(1);
            }
        }
        info.setMac(mac == null ? null : normalizeMac(mac));
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

    /** 按标签取值 */
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

    /** 解析 display ont ipconfig 回显 */
    public static OltOntInfo parseOntIpconfig(String text) {
        OltOntInfo info = new OltOntInfo();
        if (text == null || text.isBlank()) {
            return info;
        }
        Matcher mac = ONT_IPCONFIG_MAC.matcher(text);
        info.setMac(mac.find() ? normalizeMac(mac.group(1)) : null);
        Matcher ip = ONT_IPCONFIG_IP.matcher(text);
        if (ip.find()) {
            if (!"-".equals(ip.group(1))) {
                info.setIp(ip.group(1));
            } else {
                info.setIpEmpty(Boolean.TRUE);
            }
        }
        return info;
    }

    /** ONT MAC 行 */
    private static final Pattern ONT_IPCONFIG_MAC = Pattern.compile(
            "(?im)^\\s*ONT\\s+MAC[^:：\\r\\n]*[:：]\\s*(\\S+)");

    /** ONT IP 行 */
    private static final Pattern ONT_IPCONFIG_IP = Pattern.compile(
            "(?im)^\\s*ONT\\s+IP\\s+[^:：\\r\\n]{0,8}[:：]\\s*(\\S+)");

    /** MAC 归一成 AA:BB:CC:DD:EE:FF */
    public static String normalizeMac(String raw) {
        if (raw == null) {
            return null;
        }
        String hex = raw.trim().replaceAll("[-:.\\s]", "");
        if (!hex.matches("[0-9A-Fa-f]{12}")) {
            return null;
        }
        if (hex.matches("0{12}")) {
            return null;
        }
        return hex.toUpperCase().replaceAll("(..)(?!$)", "$1:");
    }

    /** 配置脚本 ONT 授权行 */
    private static final Pattern ONT_ADD_CONFIG = Pattern.compile(
            "(?is)ont\\s+add\\s+\\d+\\s+\\d+\\s+(sn-auth|mac-auth|password-auth)\\s+\"?([^\"\\s]+)\"?"
                    + "[\\s\\S]*?ont-lineprofile-id\\s+(\\d{1,4})"
                    + "[\\s\\S]*?ont-srvprofile-id\\s+(\\d{1,4})(?:[\\s\\S]{0,200}?desc\\s+\"([^\"]*)\")?");

    /** 从配置摘录解析授权信息 */
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

    /** 解析表格式回显 */
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

    /** 判断命令是否不被设备支持 */
    public static boolean looksUnsupported(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("command is not found") || lower.contains("unrecognized")
                || lower.contains("unknown command") || lower.contains("failure:")
                || lower.contains("too many parameters") || lower.contains("incomplete command")
                || lower.contains("error:");
    }

    // --------------------------------------------------- 待注册 ONT（display ont autofind）

    /** 判断待注册 ONT 是否为空 */
    public static boolean autofindIsEmpty(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("automatically found")
                || lower.contains("no ont") || lower.contains("0 ont");
    }

    /** 解析待注册 ONT */
    public static List<OltOntAutofind> parseOntAutofind(String text) {
        List<OltOntAutofind> items = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return items;
        }
        int[][] bounds = null;
        List<String> headers = null;
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.matches("[-= ]+")) {
                continue;
            }
            int[][] candidate = autofindColumnBounds(line);
            if (candidate.length < 2) {
                continue;
            }
            List<String> cells = autofindCells(line, candidate);
            if (autofindIsHeader(cells)) {
                bounds = candidate;
                headers = autofindHeaders(cells);
                continue;
            }
            if (headers == null) {
                continue;
            }
            OltOntAutofind item = autofindRow(autofindCells(line, bounds), headers);
            if (item != null) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            items = autofindFromBlocks(text);
        }
        return items;
    }

    /** 竖排键值格式兜底解析 */
    private static List<OltOntAutofind> autofindFromBlocks(String text) {
        List<OltOntAutofind> items = new ArrayList<>();
        for (LinkedHashMap<String, String> block : parseOntKeyValueBlocks(text)) {
            OltOntAutofind item = new OltOntAutofind();
            boolean recognized = false;
            for (Map.Entry<String, String> entry : block.entrySet()) {
                String key = autofindKey(entry.getKey());
                String value = entry.getValue();
                if (value == null || value.isBlank() || "-".equals(value.trim())) {
                    continue;
                }
                switch (key) {
                    case "number" -> {
                        Integer number = toInt(value);
                        if (number != null) {
                            item.setId(number);
                            recognized = true;
                        }
                    }
                    case "f/s/p" -> {
                        item.setFsp(value.trim());
                        recognized = true;
                    }
                    case "ontsn", "sn", "serialnumber" -> {
                        item.setSn(stripSnNote(value));
                        recognized = true;
                    }
                    case "password" -> item.setPassword(value.trim());
                    case "loid", "logicalid", "logicid" -> item.setLoid(value.trim());
                    case "checkcode" -> item.setCheckCode(value.trim());
                    case "vendorid" -> item.setVendorId(value.trim());
                    case "ontversion" -> item.setHardwareVersion(value.trim());
                    case "ontsoftwareversion", "softwareversion" -> item.setSoftwareVersion(value.trim());
                    case "ontequipmentid", "equipmentid" -> {
                        item.setEquipmentId(value.trim());
                        item.setModel(value.trim());
                    }
                    case "ontmodel", "model" -> item.setModel(value.trim());
                    case "ontautofindtime", "autofindtime" -> item.setLastTime(value.trim());
                    default -> { }
                }
            }
            if (recognized) {
                if (item.getFsp() != null) {
                    String[] parts = item.getFsp().split("/");
                    if (parts.length == 3) {
                        try {
                            item.setFrameNo(Integer.parseInt(parts[0].trim()));
                            item.setSlotNo(Integer.parseInt(parts[1].trim()));
                            item.setPortNo(Integer.parseInt(parts[2].trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                items.add(item);
            }
        }
        return items;
    }

    /** 去掉 SN 后的对照注解 */
    private static String stripSnNote(String value) {
        return value.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
    }

    /** 按列间宽空白切列，返回每列起止下标 */
    private static int[][] autofindColumnBounds(String rawLine) {
        String line = rawLine.replace("\t", "    ");
        List<int[]> spans = new ArrayList<>();
        int index = 0;
        int length = line.length();
        while (index < length) {
            while (index < length && line.charAt(index) == ' ') {
                index++;
            }
            if (index >= length) {
                break;
            }
            int start = index;
            while (index < length) {
                if (line.charAt(index) != ' ') {
                    index++;
                    continue;
                }
                int next = index;
                while (next < length && line.charAt(next) == ' ') {
                    next++;
                }
                if (next - index >= 2) {
                    break;
                }
                index = next;
            }
            spans.add(new int[]{start, index});
        }
        return spans.toArray(new int[0][]);
    }

    /** 按列边界切单元格 */
    private static List<String> autofindCells(String line, int[][] bounds) {
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < bounds.length; i++) {
            int start = Math.min(bounds[i][0], line.length());
            int end = i + 1 < bounds.length ? Math.min(bounds[i + 1][0], line.length()) : line.length();
            cells.add(start >= end ? "" : line.substring(start, end).trim());
        }
        return cells;
    }

    /** 判断是否表头行 */
    private static boolean autofindIsHeader(List<String> cells) {
        boolean number = false;
        boolean identity = false;
        for (String cell : cells) {
            String key = autofindKey(cell);
            if (key.isEmpty()) {
                continue;
            }
            if (key.startsWith("number")) {
                number = true;
            }
            if (key.contains("s/p") || (key.contains("port") && key.contains("slot"))
                    || key.contains("frameslot") || key.contains("sn") || key.contains("serial")) {
                identity = true;
            }
        }
        return number && identity;
    }

    private static List<String> autofindHeaders(List<String> cells) {
        List<String> headers = new ArrayList<>();
        for (String cell : cells) {
            headers.add(autofindKey(cell));
        }
        return headers;
    }

    /** 列名规范化 */
    private static String autofindKey(String cell) {
        return cell == null ? "" : cell.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/]", "");
    }

    /** 解析一行数据 */
    private static OltOntAutofind autofindRow(List<String> cells, List<String> headers) {
        OltOntAutofind item = new OltOntAutofind();
        for (int i = 0; i < headers.size() && i < cells.size(); i++) {
            String header = headers.get(i);
            String value = clean(cells.get(i));
            if (header.isEmpty() || value == null || value.isEmpty()) {
                continue;
            }
            if (header.startsWith("number")) {
                item.setId(autofindInt(value));
            } else if (header.contains("password")) {
                item.setPassword(value);
            } else if (header.contains("loid")) {
                item.setLoid(value);
            } else if (header.contains("checkcode")) {
                item.setCheckCode(value);
            } else if (header.contains("equipment")) {
                item.setEquipmentId(value);
            } else if (header.contains("software")) {
                item.setSoftwareVersion(value);
            } else if (header.contains("version")) {
                item.setHardwareVersion(value);
            } else if (header.contains("s/p") || header.contains("frameslot")
                    || (header.contains("slot") && header.contains("port"))) {
                autofindSetPort(item, value);
            } else if (header.contains("vendor") || header.contains("vender")) {
                if (header.contains("model")) {
                    autofindSetVendorModel(item, value);
                } else {
                    item.setVendorId(value);
                }
            } else if (header.contains("model")) {
                item.setModel(value);
            } else if (header.contains("time")) {
                item.setLastTime(value);
            } else if (header.contains("serial") || header.endsWith("sn")) {
                item.setSn(value);
            }
        }
        return item.getSn() != null || item.getFsp() != null ? item : null;
    }

    /** F/S/P 拆成框/槽/端口 */
    private static void autofindSetPort(OltOntAutofind item, String value) {
        item.setFsp(value);
        String[] parts = value.split("/");
        if (parts.length == 3) {
            item.setFrameNo(autofindInt(parts[0]));
            item.setSlotNo(autofindInt(parts[1]));
            item.setPortNo(autofindInt(parts[2]));
        }
    }

    /** 拆分厂商/型号 */
    private static void autofindSetVendorModel(OltOntAutofind item, String value) {
        int slash = value.indexOf('/');
        if (slash > 0) {
            item.setVendorId(value.substring(0, slash).trim());
            item.setModel(value.substring(slash + 1).trim());
            return;
        }
        if (item.getModel() == null) {
            item.setModel(value);
        }
    }

    private static Integer autofindInt(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clean(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    /** 板卡类别 */
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

package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltVlanPort;
import com.tr069.acs.olt.telnet.OltSnapshot;
import com.tr069.acs.olt.telnet.OltVendor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 鸿升光 GPON 一体化 OLT（4 / 8 / 16 口）。
 *
 * <p>与 EPON 那台（{@link HsgqOltProfile}）是同一家的两条产品线：整机一张卡、没有板卡插框，
 * 登录方式和提示符一样，但 PON 口叫 {@code gpon}、ONT 按 SN/LOID 注册、业务靠
 * {@code service-port} 虚端口绑定 GEM Port（见 {@link HsgqGponActionCatalog}）。</p>
 *
 * <p>型号回显是 {@code Product: Neutral-G16}（16 口）这类写法，也有刷成 {@code HSGQ-G08} 的；</p>
 */
public class HsgqGponOltProfile extends HsgqOltProfile {

    /** 没采到型号时的兜底 */
    private static final String DEFAULT_MODEL = "Neutral-G08";

    /** 型号 → PON 口数 */
    private static final Map<String, Integer> PON_PORT_COUNT = Map.of(
            "NEUTRAL-G04", 4,
            "NEUTRAL-G08", 8,
            "NEUTRAL-G16", 16,
            "HSGQ-G04", 4,
            "HSGQ-G08", 8,
            "HSGQ-G16", 16);

    /** 从型号里抠口数：Neutral-G16 → 16、HSGQ-G08 → 8 */
    private static final Pattern MODEL_PORT_COUNT = Pattern.compile("(?i)^(?:NEUTRAL|HSGQ)-G(\\d{1,2})");

    /** show version 的 Product 行 / 登录横幅里的型号 */
    private static final Pattern MODEL = Pattern.compile("(?i)\\b((?:NEUTRAL|HSGQ)-G\\d{1,2}[A-Z]?)\\b");

    /**
     * {@code show ont-info all} 的数据行（GPON 口视图里执行）。
     *
     * <p>手册只给了字段说明没给回显样例，各固件列序略有差别，所以按「ONT ID + SN + ... + 在线状态」
     * 这种最宽松的形状取：第一个数字是 ONT ID，紧跟的一串字母数字是 SN，行内出现 Online/Offline
     * 就算一条。解析不出来就返回空表，页面显示「-」，绝不拿错位的列凑数。</p>
     */
    private static final Pattern ONT_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,3})\\s+([0-9A-Za-z]{8,20})\\s+[^\\r\\n]*?\\b(online|offline)\\b");

    @Override
    public OltVendor vendor() {
        return OltVendor.HSGQ_GPON;
    }

    @Override
    public boolean matches(String output) {
        // 只认鸿升光自己的记号：横幅 "Neutral GPON OLT Operating System"、型号 Neutral-G16 / HSGQ-G08。
        // 不能只凭 "gpon"——别家 GPON 也会打这个词，认错了整页界面和命令都是错的
        return containsAny(output, "neutral", "hsgq-g");
    }

    @Override
    protected List<Pattern> modelPatterns() {
        return List.of(MODEL, MODEL_LABEL);
    }

    @Override
    public List<String> boardCommands() {
        // 整机设备没有板卡表，也没有 show pon-info，什么都别发
        return List.of();
    }

    @Override
    public List<OltAction> actions() {
        return HsgqGponActionCatalog.actions();
    }

    @Override
    public int ponPortCount(String model) {
        if (model != null && !model.isBlank()) {
            Integer known = PON_PORT_COUNT.get(model.trim().toUpperCase(Locale.ROOT));
            if (known != null) {
                return known;
            }
            Matcher matcher = MODEL_PORT_COUNT.matcher(model.trim());
            if (matcher.find()) {
                int count = Integer.parseInt(matcher.group(1));
                if (count > 0) {
                    return count;
                }
            }
        }
        return 8;
    }

    @Override
    public OltBoard buildWholeDevice(String model) {
        OltBoard board = super.buildWholeDevice(
                model == null || model.isBlank() ? DEFAULT_MODEL : model.trim());
        board.setPortMode("GPON");
        board.setDescription("整机（" + board.getPortCount() + " 口 GPON）");
        return board;
    }

    /**
     * GPON 的 PON 口不做二层成员：业务靠 service-port 绑 GEM Port，
     * 所以「创建 VLAN」弹窗里只给上联口，勾了 PON 口下发过去设备会直接报错。
     */
    @Override
    public Set<String> vlanMemberPortTypes() {
        return Set.of("ge", "xge");
    }

    /** PON 口的关键字是 {@code gpon}，生成后会被 {@link #vlanMemberPortTypes()} 筛掉 */
    @Override
    protected String ponType() {
        return "gpon";
    }

    /**
     * GPON 的 TR-069 模板命令还没核过（手册第 5 章那套是 {@code ont-tr069-profile}），
     * 先不让页面发 EPON 风格的 {@code onu-tr069-profile}——发过去只会报语法错。
     */
    @Override
    public boolean supportsTr069Profile() {
        return false;
    }

    /**
     * 配置视图下补采：每个 PON 口的 ONT 明细（{@code interface gpon n} → {@code show ont-info all}）
     * 与 VLAN 快照（{@code show running-config}）。
     */
    @Override
    public void collectDetails(OltCli cli, OltSnapshot snapshot, List<OltBoard> boards,
                               List<String> notes, int timeoutMs) {
        if (cli == null || boards == null || boards.isEmpty()) {
            return;
        }
        if (!enterConfigView(cli, timeoutMs)) {
            notes.add("进不了配置视图（enable → config/configure），ONT / VLAN 未采到");
            return;
        }
        try {
            fillPortOnts(cli, boards, notes, timeoutMs);
            collectVlans(cli, snapshot, notes, timeoutMs);
        } finally {
            cli.run("quit", timeoutMs);
        }
    }

    /**
     * 逐个 PON 口进 {@code interface gpon n} 采 ONT 列表。
     *
     * <p>采不到就留空——四/八/十六口的端口格子按型号生成，不会因为没采到就变成 0。</p>
     */
    private void fillPortOnts(OltCli cli, List<OltBoard> boards, List<String> notes, int timeoutMs) {
        int parsed = 0;
        for (OltBoard board : boards) {
            List<OltPort> ports = board.getPorts();
            if (ports == null) {
                continue;
            }
            for (OltPort port : ports) {
                Integer ponNo = port.getPortNo();
                if (ponNo == null) {
                    continue;
                }
                cli.run("interface gpon " + ponNo, timeoutMs);
                List<OltOnt> onts = parseOntInfo(cli.run("show ont-info all", timeoutMs));
                cli.run("exit", timeoutMs);
                if (!onts.isEmpty()) {
                    parsed += onts.size();
                    int online = (int) onts.stream()
                            .filter(ont -> Boolean.TRUE.equals(ont.getOnline())).count();
                    port.setOnts(onts);
                    port.setOnuTotal(onts.size());
                    port.setOnuOnline(online);
                }
            }
        }
        if (parsed == 0) {
            notes.add("ONT 明细未采到：show ont-info all 无有效回显（不影响端口与 VLAN）");
        }
    }

    /**
     * {@code show ont-info all} → ONT 列表。
     *
     * <p>GPON 用 SN 标识 ONT，MAC 那列留空；SN 放在 {@code sn} 上，页面「SN/MAC」列显示。</p>
     */
    public static List<OltOnt> parseOntInfo(String text) {
        List<OltOnt> onts = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return onts;
        }
        Map<Integer, OltOnt> byId = new LinkedHashMap<>();
        Matcher matcher = ONT_ROW.matcher(text);
        while (matcher.find()) {
            int ontId = Integer.parseInt(matcher.group(1));
            OltOnt ont = new OltOnt();
            ont.setOntId(ontId);
            ont.setSn(matcher.group(2).toUpperCase(Locale.ROOT));
            boolean online = "online".equalsIgnoreCase(matcher.group(3));
            ont.setOnline(online);
            ont.setRunState(online ? "Online" : "Offline");
            byId.putIfAbsent(ontId, ont);
        }
        onts.addAll(byId.values());
        return onts;
    }
}

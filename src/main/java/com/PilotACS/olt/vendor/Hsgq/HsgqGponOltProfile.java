/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.746
 *
 */

package com.PilotACS.olt.vendor.Hsgq;

import com.PilotACS.olt.entity.OltBoard;
import com.PilotACS.olt.model.OltAction;
import com.PilotACS.olt.model.OltOnt;
import com.PilotACS.olt.model.OltPort;
import com.PilotACS.olt.model.OltVlanPort;
import com.PilotACS.olt.telnet.OltSnapshot;
import com.PilotACS.olt.telnet.OltVendor;
import com.PilotACS.olt.vendor.OltCli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 鸿升光 GPON 一体化 OLT（4 / 8 / 16 口） */
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

    /** {@code show ont-info all} 的数据行（GPON 口视图里执行） */
    private static final Pattern ONT_ROW = Pattern.compile(
            "(?im)^\\s*(\\d{1,3})\\s+([0-9A-Za-z]{8,20})\\s+[^\\r\\n]*?\\b(online|offline)\\b");

    @Override
    public OltVendor vendor() {
        return OltVendor.HSGQ_GPON;
    }

    @Override
    public boolean matches(String output) {
        return containsAny(output, "neutral", "hsgq-g");
    }

    @Override
    protected List<Pattern> modelPatterns() {
        return List.of(MODEL, MODEL_LABEL);
    }

    @Override
    public List<String> boardCommands() {
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

    /** PON 类型名 */
    @Override
    protected String ponTypeName() {
        return "GPON";
    }

    /** 可做 VLAN 成员的端口类型 */
    @Override
    public Set<String> vlanMemberPortTypes() {
        return Set.of("ge", "xge");
    }

    /** PON 口端口类型关键字 */
    @Override
    protected String ponType() {
        return "gpon";
    }

    @Override
    public boolean supportsTr069Profile() {
        return false;
    }

    /** 配置视图下补采各 PON 口的 ONT 明细 */
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

    /** 逐个 PON 口进 {@code interface gpon n} 采 ONT 列表 */
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

    /** 解析 ONT 列表 */
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

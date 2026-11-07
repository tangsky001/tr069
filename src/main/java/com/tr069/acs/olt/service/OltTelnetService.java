package com.tr069.acs.olt.service;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.model.OltLineProfile;
import com.tr069.acs.olt.telnet.OltInfoParser;
import com.tr069.acs.olt.model.OltOntDeleteResult;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltVlanInfo;
import com.tr069.acs.olt.telnet.OltCharset;
import com.tr069.acs.olt.telnet.OltCliSession;
import com.tr069.acs.olt.telnet.OltSnapshot;
import com.tr069.acs.olt.telnet.OltVendor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** OLT 命令行采集入口 */
@Slf4j
@Service
public class OltTelnetService {

    /** 回显等待的并行线程池，线程按需创建、空闲回收 */
    private static final ExecutorService SECTION_POOL = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "olt-collect-split");
        thread.setDaemon(true);
        return thread;
    });

    private final OltSessionPool sessionPool;

    @Value("${acs.olt.max-raw-output-chars:60000}")
    private int maxRawOutputChars;

    public OltTelnetService(OltSessionPool sessionPool) {
        this.sessionPool = sessionPool;
    }

    /** 登录并采集（含端口光模块） */
    public OltSnapshot collect(OltDevice device, boolean withBoards) throws IOException {
        return collect(device, withBoards, true);
    }

    /**
     * 登录并采集。
     *
     * @param withOptics 是否采端口光模块数值（{@code display port state}，每口一条，8~20 秒）。
     *                   自动刷新这类高频轮次传 false，库里的旧值沿用；只有手动同步才采
     */
    public OltSnapshot collect(OltDevice device, boolean withBoards, boolean withOptics) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltSnapshot snapshot = doCollect(device, charset, withBoards, withOptics);

        // 「自动」编码：回显里出现替换符说明按 UTF-8 解错了，用 GBK 再连一次
        if (OltCharset.isAuto(device.getCharset()) && garbled(snapshot)) {
            log.debug("OLT {} 回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                OltSnapshot retried = doCollect(device, OltCharset.fallback(), withBoards, withOptics);
                if (!garbled(retried)) {
                    return retried;
                }
            } catch (IOException e) {
                log.debug("OLT {} GBK 重试失败，保留首次结果: {}", device.getHost(), e.getMessage());
            }
        }
        return snapshot;
    }

    /** 全量采集拆成多段并行截取回显，慢段之间互不阻塞 */
    private OltSnapshot doCollect(OltDevice device, Charset charset, boolean withBoards,
                                  boolean withOptics) throws IOException {
        boolean strict = charsetStrict(device, charset);
        if (!withBoards) {
            return sessionPool.execute(device, charset, strict, session -> session.collect(false));
        }
        // 第一段：版本 + 板卡，拿到槽位表才能拆后面的段
        long totalStart = System.currentTimeMillis();
        long baseStart = System.currentTimeMillis();
        OltSnapshot base = sessionPool.execute(device, charset, strict,
                session -> session.collect(true, EnumSet.noneOf(OltCliSession.Section.class)));
        log.info("OLT 分段耗时 [{}] 板卡/版本段: {} ms", device.getHost(), System.currentTimeMillis() - baseStart);
        List<OltBoard> boards = base.getBoards();
        if (boards == null || boards.isEmpty()) {
            return base;
        }
        if (base.getVendor() != OltVendor.HUAWEI) {
            // 分段采集只编排了华为那套命令，其它厂商仍走单会话全量
            return sessionPool.execute(device, charset, strict, session -> session.collect(true));
        }
        // 端口光模块数值（display port state，每口一条、单台设备 8~20 秒）只在手动同步时采；
        // 自动刷新这类高频轮次跳过，端口列表沿用库里的旧值
        Set<OltCliSession.Section> portPart = withOptics
                ? EnumSet.of(OltCliSession.Section.TEMPERATURES,
                        OltCliSession.Section.PORT_STATES, OltCliSession.Section.OPTICS)
                : EnumSet.of(OltCliSession.Section.TEMPERATURES, OltCliSession.Section.PORT_STATES);
        List<Part> parts = List.of(
                new Part(portPart, false),
                // 全量采集不逐台补 ONT 详情（每台一两条命令、设备侧秒级）：库里已有的详情由
                // OltService 按上一轮数据对回，还缺的走后台补采，别把每轮同步拖成几分钟
                new Part(EnumSet.of(OltCliSession.Section.ONTS), false),
                // 线路模板 / 业务模板的逐条详情不在全量里采：那是「点开才看」的数据，
                // 只在模板页签的按需同步（scope=template）里采
                new Part(EnumSet.of(OltCliSession.Section.VLANS), false));
        List<Future<OltSnapshot>> futures = new ArrayList<>(parts.size());
        List<Long> partStarts = new ArrayList<>(parts.size());
        for (Part part : parts) {
            List<OltBoard> skeleton = skeleton(boards);
            partStarts.add(System.currentTimeMillis());
            futures.add(SECTION_POOL.submit(() -> sessionPool.execute(device, charset, strict,
                    session -> session.collectSections(skeleton, part.sections(), part.enrichOnt()))));
        }
        List<String> notes = new ArrayList<>(base.getNotes() == null ? List.of() : base.getNotes());
        StringBuilder transcript = new StringBuilder(
                base.getTranscript() == null ? "" : base.getTranscript());
        for (int i = 0; i < parts.size(); i++) {
            OltSnapshot part;
            try {
                part = futures.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("OLT 采集被中断", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.warn("OLT 分段耗时 [{}] {} 失败于 {} ms: {}", device.getHost(),
                        sectionNames(parts.get(i).sections()), System.currentTimeMillis() - partStarts.get(i),
                        cause.getMessage());
                notes.add("分段采集失败：" + cause.getMessage());
                continue;
            }
            log.info("OLT 分段耗时 [{}] {}: {} ms", device.getHost(),
                    sectionNames(parts.get(i).sections()), System.currentTimeMillis() - partStarts.get(i));
            mergePart(base, part, parts.get(i).sections());
            if (part.getNotes() != null) {
                notes.addAll(part.getNotes());
            }
            if (part.getTranscript() != null) {
                transcript.append(part.getTranscript());
            }
        }
        base.setNotes(notes);
        base.setTranscript(transcript.toString());
        log.info("OLT 分段耗时 [{}] 全量合计: {} ms", device.getHost(), System.currentTimeMillis() - totalStart);
        return base;
    }

    /** 分段名，日志用 */
    private static String sectionNames(Set<OltCliSession.Section> sections) {
        StringBuilder names = new StringBuilder();
        for (OltCliSession.Section section : sections) {
            if (names.length() > 0) {
                names.append('+');
            }
            names.append(switch (section) {
                case TEMPERATURES -> "温度";
                case PORT_STATES -> "端口状态";
                case ONTS -> "ONT明细";
                case OPTICS -> "光模块";
                case VLANS -> "VLAN";
                case TEMPLATES -> "模板";
            });
        }
        return names.toString();
    }

    /** 分段采集任务定义 */
    private record Part(Set<OltCliSession.Section> sections, boolean enrichOnt) {
    }

    /** 板卡骨架拷贝：只带槽位/板型/端口数，各段任务在拷贝上各自建端口 */
    private static List<OltBoard> skeleton(List<OltBoard> boards) {
        List<OltBoard> copies = new ArrayList<>(boards.size());
        for (OltBoard board : boards) {
            OltBoard copy = new OltBoard();
            copy.setSlotNo(board.getSlotNo());
            copy.setBoardType(board.getBoardType());
            copy.setBoardKind(board.getBoardKind());
            copy.setStatus(board.getStatus());
            copy.setDescription(board.getDescription());
            copy.setPortMode(board.getPortMode());
            copy.setPortCount(board.getPortCount());
            copies.add(copy);
        }
        return copies;
    }

    /** 把分段采集结果合回主板卡表 */
    private static void mergePart(OltSnapshot merged, OltSnapshot part,
                                  Set<OltCliSession.Section> sections) {
        if (part == null) {
            return;
        }
        if (sections.contains(OltCliSession.Section.VLANS) && part.getVlanInfo() != null) {
            merged.setVlanInfo(part.getVlanInfo());
        }
        if (sections.contains(OltCliSession.Section.TEMPLATES) && part.getTemplateInfo() != null) {
            merged.setTemplateInfo(part.getTemplateInfo());
        }
        if (part.getBoards() == null || merged.getBoards() == null) {
            return;
        }
        Map<Integer, OltBoard> bySlot = new HashMap<>();
        for (OltBoard board : merged.getBoards()) {
            if (board.getSlotNo() != null) {
                bySlot.put(board.getSlotNo(), board);
            }
        }
        for (OltBoard partBoard : part.getBoards()) {
            OltBoard target = partBoard.getSlotNo() == null ? null : bySlot.get(partBoard.getSlotNo());
            if (target == null) {
                continue;
            }
            if (sections.contains(OltCliSession.Section.TEMPERATURES) && partBoard.getTemperature() != null) {
                target.setTemperature(partBoard.getTemperature());
            }
            if (partBoard.getPorts() == null) {
                continue;
            }
            List<OltPort> ports = target.getPorts() == null ? new ArrayList<>() : target.getPorts();
            target.setPorts(ports);
            for (OltPort partPort : partBoard.getPorts()) {
                OltPort port = findPort(ports, partPort.getPortNo());
                if (port == null) {
                    ports.add(partPort);
                    continue;
                }
                if (sections.contains(OltCliSession.Section.PORT_STATES)) {
                    port.setOpticStatus(partPort.getOpticStatus());
                    port.setOpticalPresent(partPort.getOpticalPresent());
                    port.setLinkStatus(partPort.getLinkStatus());
                }
                if (sections.contains(OltCliSession.Section.ONTS)) {
                    port.setOnts(partPort.getOnts());
                    port.setOnuTotal(partPort.getOnuTotal());
                    port.setOnuOnline(partPort.getOnuOnline());
                }
                if (sections.contains(OltCliSession.Section.OPTICS)) {
                    port.setTxPower(partPort.getTxPower());
                    port.setRxPower(partPort.getRxPower());
                    port.setOpticTemperature(partPort.getOpticTemperature());
                    port.setBiasCurrent(partPort.getBiasCurrent());
                    port.setSupplyVoltage(partPort.getSupplyVoltage());
                    port.setModuleStatus(partPort.getModuleStatus());
                    port.setLaserState(partPort.getLaserState());
                    port.setTxFault(partPort.getTxFault());
                }
            }
        }
    }

    private static OltPort findPort(List<OltPort> ports, Integer portNo) {
        if (portNo == null) {
            return null;
        }
        for (OltPort port : ports) {
            if (portNo.equals(port.getPortNo())) {
                return port;
            }
        }
        return null;
    }

    /** 快速采集：只要板卡（型号 / 板卡类型 / 槽位 / 机框槽位数），秒级返回 */
    public OltSnapshot collectBoards(OltDevice device) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltSnapshot snapshot = doCollectBoards(device, charset);
        if (OltCharset.isAuto(device.getCharset()) && garbled(snapshot)) {
            log.debug("OLT {} 快速采集回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                OltSnapshot retried = doCollectBoards(device, OltCharset.fallback());
                if (!garbled(retried)) {
                    return retried;
                }
            } catch (IOException e) {
                log.debug("OLT {} GBK 重试失败，保留首次结果: {}", device.getHost(), e.getMessage());
            }
        }
        return snapshot;
    }

    private OltSnapshot doCollectBoards(OltDevice device, Charset charset) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collectBoardsOnly());
    }

    /** 按页签同步：只采指定段（温度 / 端口+ONT+光模块 / VLAN / 模板） */
    public OltSnapshot collectSections(OltDevice device, List<OltBoard> boards,
                                       Set<OltCliSession.Section> sections) throws IOException {
        return collectSections(device, boards, sections, true);
    }

    /** （那部分一台一条命令，几十台要几分钟，页签同步等不起） */
    public OltSnapshot collectSections(OltDevice device, List<OltBoard> boards,
                                       Set<OltCliSession.Section> sections,
                                       boolean enrichOntDetails) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltSnapshot snapshot = doCollectSections(device, charset, boards, sections, enrichOntDetails);
        // 「自动」编码：与全量采集同一套 GBK 重试逻辑
        if (OltCharset.isAuto(device.getCharset()) && garbled(snapshot)) {
            log.debug("OLT {} 按页签采集回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                OltSnapshot retried = doCollectSections(device, OltCharset.fallback(), boards, sections,
                        enrichOntDetails);
                if (!garbled(retried)) {
                    return retried;
                }
            } catch (IOException e) {
                log.debug("OLT {} GBK 重试失败，保留首次结果: {}", device.getHost(), e.getMessage());
            }
        }
        return snapshot;
    }

    private OltSnapshot doCollectSections(OltDevice device, Charset charset, List<OltBoard> boards,
                                          Set<OltCliSession.Section> sections,
                                          boolean enrichOntDetails) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collectSections(boards, sections, enrichOntDetails));
    }

    /** 只补 ONT 单台详情（管理 IP / MAC / 模板号）：后台补采任务用 */
    public OltSnapshot collectOntDetails(OltDevice device, List<OltBoard> boards) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltSnapshot snapshot = doCollectOntDetails(device, charset, boards);
        if (OltCharset.isAuto(device.getCharset()) && garbled(snapshot)) {
            log.debug("OLT {} ONT 详情补采回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                OltSnapshot retried = doCollectOntDetails(device, OltCharset.fallback(), boards);
                if (!garbled(retried)) {
                    return retried;
                }
            } catch (IOException e) {
                log.debug("OLT {} GBK 重试失败，保留首次结果: {}", device.getHost(), e.getMessage());
            }
        }
        return snapshot;
    }

    private OltSnapshot doCollectOntDetails(OltDevice device, Charset charset, List<OltBoard> boards)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collectOntDetails(boards));
    }

    /** 只重读一条线路模板（加完 T-CONT / GEM Port / 映射后刷新用）：一条 */
    public OltLineProfile collectLineProfile(OltDevice device, int profileId, String ponType)
            throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collectLineProfile(profileId, ponType));
    }

    /** 清空业务板上的全部 ONT：登录一次，只发那几条命令（板卡位置 → 进板视图 → 逐口删除） */
    public List<OltOntDeleteResult> deleteBoardOnts(OltDevice device, List<Integer> slots) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        List<OltOntDeleteResult> results = doDeleteBoardOnts(device, charset, slots);
        if (OltCharset.isAuto(device.getCharset()) && garbledDelete(results)) {
            log.debug("OLT {} 删除 ONT 回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            results = doDeleteBoardOnts(device, OltCharset.fallback(), slots);
        }
        return results;
    }

    private List<OltOntDeleteResult> doDeleteBoardOnts(OltDevice device, Charset charset, List<Integer> slots)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.deleteBoardOnts(slots));
    }

    private static boolean garbledDelete(List<OltOntDeleteResult> results) {
        if (results == null) {
            return false;
        }
        return results.stream().flatMap(result -> result.getOutputs().stream())
                .anyMatch(out -> out != null && out.indexOf('\uFFFD') >= 0);
    }

    /** 只重采 VLAN（上行口 VLAN 下发后立刻刷新用）：一条 {@code display vlan all} */
    public OltVlanInfo collectVlans(OltDevice device, List<OltBoard> boards) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltVlanInfo info = doCollectVlans(device, charset, boards);
        if (OltCharset.isAuto(device.getCharset()) && garbledVlans(info)) {
            log.debug("OLT {} VLAN 回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            info = doCollectVlans(device, OltCharset.fallback(), boards);
        }
        return info;
    }

    private OltVlanInfo doCollectVlans(OltDevice device, Charset charset, List<OltBoard> boards)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collectVlansOnly(boards));
    }

    /** VLAN 快照里有没有解错的替换符（端口清单这类文本最可能先暴露出来） */
    private static boolean garbledVlans(OltVlanInfo info) {
        if (info == null || info.getVlans() == null) {
            return false;
        }
        return info.getVlans().stream().anyMatch(vlan ->
                (vlan.getType() != null && vlan.getType().indexOf('\uFFFD') >= 0)
                        || (vlan.getTaggedPorts() != null && vlan.getTaggedPorts().indexOf('\uFFFD') >= 0)
                        || (vlan.getUntaggedPorts() != null && vlan.getUntaggedPorts().indexOf('\uFFFD') >= 0));
    }

    /** 批量补 ONT 模板号：一条配置命令解析全部 ont add 行，返回补上台数 */
    public int fillOntProfiles(OltDevice device, List<OltBoard> boards) throws IOException {
        if (boards == null || boards.isEmpty()) {
            return 0;
        }
        List<String> notes = new ArrayList<>();
        Charset charset = OltCharset.resolve(device.getCharset());
        Integer filled = sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.fillOntProfilesFromConfig(boards, notes));
        if (!notes.isEmpty()) {
            log.info("OLT {} ONT 模板号补采提示：{}", device.getHost(), String.join("；", notes));
        }
        return filled == null ? 0 : filled;
    }

    /** 单端口光模块按需查询：点开某个 PON 口时才发这一条 {@code display port state} */
    public OltPort queryPortOptics(OltDevice device, int slot, int portNo) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String out = doQueryPortState(device, charset, slot, portNo);
        // 「自动」编码：回显里出现替换符说明按 UTF-8 解错了，用 GBK 再连一次
        if (OltCharset.isAuto(device.getCharset()) && out != null && out.indexOf('\uFFFD') >= 0) {
            out = doQueryPortState(device, OltCharset.fallback(), slot, portNo);
        }
        return OltInfoParser.parsePortStateOptics(out);
    }

    private String doQueryPortState(OltDevice device, Charset charset, int slot, int portNo) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.queryPortState(slot, portNo));
    }

    /** 单条 ONT 详情查询（光信息 / 版本 / 注册历史）：登录一次、进 GPON 端口视图发命令 */
    public String queryOnt(OltDevice device, int slot, int port, int ontId, String subcommand) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String out = doQueryOnt(device, charset, slot, port, ontId, subcommand);
        // 「自动」编码：回显里出现替换符说明按 UTF-8 解错了，用 GBK 再连一次
        if (OltCharset.isAuto(device.getCharset()) && out.indexOf('\uFFFD') >= 0) {
            return doQueryOnt(device, OltCharset.fallback(), slot, port, ontId, subcommand);
        }
        return out;
    }

    private String doQueryOnt(OltDevice device, Charset charset, int slot, int port,
                              int ontId, String subcommand) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.queryOntDetail(slot, port, ontId, subcommand));
    }

    /** 单条 ONT 的配置摘录 + 业务流表格 + 详情，返回 {配置摘录, 业务流回显, 详情回显}，点序列号时触发 */
    public String[] queryOntConfigAndServicePorts(OltDevice device, int slot, int port, int ontId) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String[] outs = doQueryOntConfig(device, charset, slot, port, ontId);
        if (OltCharset.isAuto(device.getCharset())
                && (outs[0].indexOf('\uFFFD') >= 0 || outs[1].indexOf('\uFFFD') >= 0
                        || outs[2].indexOf('\uFFFD') >= 0)) {
            return doQueryOntConfig(device, OltCharset.fallback(), slot, port, ontId);
        }
        return outs;
    }

    private String[] doQueryOntConfig(OltDevice device, Charset charset, int slot, int port, int ontId)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.queryOntConfigAndServicePorts(slot, port, ontId));
    }

    /** 单条 ONT 的业务流表格（display service-port port 0/x/x ont n），点业务流列「查看」时触发 */
    public String queryOntServicePorts(OltDevice device, int slot, int port, int ontId) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String out = doQueryOntServicePorts(device, charset, slot, port, ontId);
        if (OltCharset.isAuto(device.getCharset()) && out.indexOf('\uFFFD') >= 0) {
            return doQueryOntServicePorts(device, OltCharset.fallback(), slot, port, ontId);
        }
        return out;
    }

    private String doQueryOntServicePorts(OltDevice device, Charset charset, int slot, int port, int ontId)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.queryServicePorts(slot, port, ontId));
    }

    /** 只读查询（TR-069 模板列表这类不进库的实时数据）：登录 → config 视图逐条 show */
    public List<String> queryCommands(OltDevice device, List<String> commands) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        List<String> outputs = doQueryCommands(device, charset, commands);
        if (OltCharset.isAuto(device.getCharset())
                && outputs.stream().anyMatch(out -> out != null && out.indexOf('\uFFFD') >= 0)) {
            return doQueryCommands(device, OltCharset.fallback(), commands);
        }
        return outputs;
    }

    private List<String> doQueryCommands(OltDevice device, Charset charset, List<String> commands)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.queryCommands(commands));
    }

    /** 下发配置命令（DBA 模板增删这类写操作）：登录 → config 视图逐条执行 */
    public List<String> sendConfig(OltDevice device, List<String> commands) throws IOException {
        return sendConfig(device, commands, false);
    }

    /** 一键开局要打开：整套配置声明一遍时，已存在的 VLAN / 模板 */
    public List<String> sendConfig(OltDevice device, List<String> commands, boolean tolerateExisting)
            throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        List<String> outputs = doSendConfig(device, charset, commands, tolerateExisting);
        if (OltCharset.isAuto(device.getCharset())
                && outputs.stream().anyMatch(out -> out != null && out.indexOf('\uFFFD') >= 0)) {
            return doSendConfig(device, OltCharset.fallback(), commands, tolerateExisting);
        }
        return outputs;
    }

    private List<String> doSendConfig(OltDevice device, Charset charset, List<String> commands,
                                      boolean tolerateExisting) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.sendConfigCommands(commands, tolerateExisting));
    }

    /** 池中会话的字符集要不要严格匹配：台账指定了编码、或者这次是 GBK 重试 */
    private boolean charsetStrict(OltDevice device, Charset charset) {
        return !OltCharset.isAuto(device.getCharset())
                || !charset.equals(OltCharset.resolve(device.getCharset()));
    }

    private boolean garbled(OltSnapshot snapshot) {
        return containsReplacement(snapshot.getVersionText())
                || containsReplacement(snapshot.getBoardText())
                || containsReplacement(snapshot.getTranscript());
    }

    private boolean containsReplacement(String text) {
        return text != null && text.indexOf('\uFFFD') >= 0;
    }

    /** 回显落库前截断，避免一条异常输出撑爆记录表 */
    public String truncateRaw(String raw) {
        if (raw == null) {
            return null;
        }
        int limit = maxRawOutputChars > 0 ? maxRawOutputChars : 60000;
        return raw.length() <= limit ? raw : raw.substring(0, limit) + "\n...[已截断，原长 " + raw.length() + " 字符]";
    }
}

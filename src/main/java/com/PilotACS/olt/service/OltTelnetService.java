/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.147
 *
 */

package com.PilotACS.olt.service;
import com.PilotACS.common.log.Logs;

import com.PilotACS.olt.entity.OltBoard;
import com.PilotACS.olt.entity.OltDevice;
import com.PilotACS.olt.model.OltLineProfile;
import com.PilotACS.olt.telnet.OltInfoParser;
import com.PilotACS.olt.model.OltOntDeleteResult;
import com.PilotACS.olt.model.OltPort;
import com.PilotACS.olt.model.OltVlanInfo;
import com.PilotACS.olt.telnet.OltCharset;
import com.PilotACS.olt.telnet.OltCliSession;
import com.PilotACS.olt.telnet.OltSnapshot;
import com.PilotACS.olt.telnet.OltVendor;
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
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** OLT 命令行采集入口 */
@Service
public class OltTelnetService {

    /** 分段采集并行线程数上限 */
    private static final int SECTION_POOL_MAX = 60;

    private static final ExecutorService SECTION_POOL = createSectionPool();

    private static ExecutorService createSectionPool() {
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(SECTION_POOL_MAX, SECTION_POOL_MAX,
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(200), r -> {
            Thread thread = new Thread(r, "olt-collect-split-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        SECTION_POOL.shutdown();
    }

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

    /** 登录并采集。 */
    public OltSnapshot collect(OltDevice device, boolean withBoards, boolean withOptics) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltSnapshot snapshot = doCollect(device, charset, withBoards, withOptics);

        if (OltCharset.isAuto(device.getCharset()) && garbled(snapshot)) {
            Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} 回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                OltSnapshot retried = doCollect(device, OltCharset.fallback(), withBoards, withOptics);
                if (!garbled(retried)) {
                    return retried;
                }
            } catch (IOException e) {
                Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} GBK 重试失败，保留首次结果: {}", device.getHost(), e.getMessage());
            }
        }
        return snapshot;
    }

    /** 全量分段并行采集 */
    private OltSnapshot doCollect(OltDevice device, Charset charset, boolean withBoards,
                                  boolean withOptics) throws IOException {
        boolean strict = charsetStrict(device, charset);
        if (!withBoards) {
            return sessionPool.execute(device, charset, strict, session -> session.collect(false));
        }
        // 第一段：版本 + 板卡
        long totalStart = System.currentTimeMillis();
        long baseStart = System.currentTimeMillis();
        OltSnapshot base = sessionPool.execute(device, charset, strict,
                session -> session.collect(true, EnumSet.noneOf(OltCliSession.Section.class)));
        Logs.info(Logs.Module.OLT_COLLECT, "OLT 分段耗时 [{}] 板卡/版本段: {} ms", device.getHost(), System.currentTimeMillis() - baseStart);
        List<OltBoard> boards = base.getBoards();
        if (boards == null || boards.isEmpty()) {
            return base;
        }
        if (base.getVendor() != OltVendor.HUAWEI) {
            return sessionPool.execute(device, charset, strict, session -> session.collect(true));
        }
        Set<OltCliSession.Section> portPart = withOptics
                ? EnumSet.of(OltCliSession.Section.TEMPERATURES,
                        OltCliSession.Section.PORT_STATES, OltCliSession.Section.OPTICS)
                : EnumSet.of(OltCliSession.Section.TEMPERATURES, OltCliSession.Section.PORT_STATES);
        List<Part> parts = List.of(
                new Part(portPart, false),
                new Part(EnumSet.of(OltCliSession.Section.ONTS), false),
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
                Logs.warn(Logs.Module.OLT_COLLECT, "OLT 分段耗时 [{}] {} 失败于 {} ms: {}", device.getHost(),
                        sectionNames(parts.get(i).sections()), System.currentTimeMillis() - partStarts.get(i),
                        cause.getMessage());
                notes.add("分段采集失败：" + cause.getMessage());
                continue;
            }
            Logs.info(Logs.Module.OLT_COLLECT, "OLT 分段耗时 [{}] {}: {} ms", device.getHost(),
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
        Logs.info(Logs.Module.OLT_COLLECT, "OLT 分段耗时 [{}] 全量合计: {} ms", device.getHost(), System.currentTimeMillis() - totalStart);
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

    /** 生成板卡骨架拷贝 */
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

    /** 快速采集板卡信息 */
    public OltSnapshot collectBoards(OltDevice device) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltSnapshot snapshot = doCollectBoards(device, charset);
        if (OltCharset.isAuto(device.getCharset()) && garbled(snapshot)) {
            Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} 快速采集回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                OltSnapshot retried = doCollectBoards(device, OltCharset.fallback());
                if (!garbled(retried)) {
                    return retried;
                }
            } catch (IOException e) {
                Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} GBK 重试失败，保留首次结果: {}", device.getHost(), e.getMessage());
            }
        }
        return snapshot;
    }

    private OltSnapshot doCollectBoards(OltDevice device, Charset charset) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collectBoardsOnly());
    }

    /** 按页签同步指定段 */
    public OltSnapshot collectSections(OltDevice device, List<OltBoard> boards,
                                       Set<OltCliSession.Section> sections) throws IOException {
        return collectSections(device, boards, sections, true);
    }

    public OltSnapshot collectSections(OltDevice device, List<OltBoard> boards,
                                       Set<OltCliSession.Section> sections,
                                       boolean enrichOntDetails) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltSnapshot snapshot = doCollectSections(device, charset, boards, sections, enrichOntDetails);
        if (OltCharset.isAuto(device.getCharset()) && garbled(snapshot)) {
            Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} 按页签采集回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                OltSnapshot retried = doCollectSections(device, OltCharset.fallback(), boards, sections,
                        enrichOntDetails);
                if (!garbled(retried)) {
                    return retried;
                }
            } catch (IOException e) {
                Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} GBK 重试失败，保留首次结果: {}", device.getHost(), e.getMessage());
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

    /** 补采 ONT 单台详情 */
    public OltSnapshot collectOntDetails(OltDevice device, List<OltBoard> boards) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltSnapshot snapshot = doCollectOntDetails(device, charset, boards);
        if (OltCharset.isAuto(device.getCharset()) && garbled(snapshot)) {
            Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} ONT 详情补采回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                OltSnapshot retried = doCollectOntDetails(device, OltCharset.fallback(), boards);
                if (!garbled(retried)) {
                    return retried;
                }
            } catch (IOException e) {
                Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} GBK 重试失败，保留首次结果: {}", device.getHost(), e.getMessage());
            }
        }
        return snapshot;
    }

    private OltSnapshot doCollectOntDetails(OltDevice device, Charset charset, List<OltBoard> boards)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collectOntDetails(boards));
    }

    /** 重新读取一条线路模板 */
    public OltLineProfile collectLineProfile(OltDevice device, int profileId, String ponType)
            throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collectLineProfile(profileId, ponType));
    }

    /** 清空业务板上的全部 ONT */
    public List<OltOntDeleteResult> deleteBoardOnts(OltDevice device, List<Integer> slots) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        List<OltOntDeleteResult> results = doDeleteBoardOnts(device, charset, slots);
        if (OltCharset.isAuto(device.getCharset()) && garbledDelete(results)) {
            Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} 删除 ONT 回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                results = doDeleteBoardOnts(device, OltCharset.fallback(), slots);
            } catch (IOException e) {
                Logs.debug(Logs.Module.OLT_COLLECT, "GBK 重试删除 ONT 失败，保留首次结果: {}", e.getMessage());
            }
        }
        return results;
    }

    /** 删除单个 ONT */
    public OltOntDeleteResult deleteOnt(OltDevice device, int slot, int port, int ontId) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltOntDeleteResult result = doDeleteOnt(device, charset, slot, port, ontId);
        if (OltCharset.isAuto(device.getCharset()) && garbled(result.getOutputs())) {
            Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} 删除单个 ONT 回显疑似非 UTF-8，改用 GBK 重试",
                    device.getHost());
            try {
                result = doDeleteOnt(device, OltCharset.fallback(), slot, port, ontId);
            } catch (IOException e) {
                Logs.debug(Logs.Module.OLT_COLLECT, "GBK 重试删除单个 ONT 失败，保留首次结果: {}", e.getMessage());
            }
        }
        return result;
    }

    /** 整机重启 */
    public String reboot(OltDevice device) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.reboot());
    }

    /** 复位单个 ONU */
    public String resetOnt(OltDevice device, int slot, int port, int ontId) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.resetOnt(slot, port, ontId));
    }

    /** 复位整条 PON 口的全部 ONU */
    public String resetPortOnts(OltDevice device, int slot, int port) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.resetPortOnts(slot, port));
    }

    /** 复位整块板卡的全部 ONU */
    public List<String> resetBoardOnts(OltDevice device, int slot, List<Integer> portNos)
            throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.resetBoardOnts(slot, portNos));
    }

    private OltOntDeleteResult doDeleteOnt(OltDevice device, Charset charset, int slot, int port, int ontId)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.deleteOnt(slot, port, ontId));
    }

    private static boolean garbled(List<String> outputs) {
        return outputs != null && outputs.stream().anyMatch(out -> out != null && out.indexOf('\uFFFD') >= 0);
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

    /** 重新采集 VLAN 信息 */
    public OltVlanInfo collectVlans(OltDevice device, List<OltBoard> boards) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        OltVlanInfo info = doCollectVlans(device, charset, boards);
        if (OltCharset.isAuto(device.getCharset()) && garbledVlans(info)) {
            Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} VLAN 回显疑似非 UTF-8，改用 GBK 重试", device.getHost());
            try {
                info = doCollectVlans(device, OltCharset.fallback(), boards);
            } catch (IOException e) {
                Logs.debug(Logs.Module.OLT_COLLECT, "GBK 重试采 VLAN 失败，保留首次结果: {}", e.getMessage());
            }
        }
        return info;
    }

    private OltVlanInfo doCollectVlans(OltDevice device, Charset charset, List<OltBoard> boards)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.collectVlansOnly(boards));
    }

    /** 判断 VLAN 回显是否乱码 */
    private static boolean garbledVlans(OltVlanInfo info) {
        if (info == null || info.getVlans() == null) {
            return false;
        }
        return info.getVlans().stream().anyMatch(vlan ->
                (vlan.getType() != null && vlan.getType().indexOf('\uFFFD') >= 0)
                        || (vlan.getTaggedPorts() != null && vlan.getTaggedPorts().indexOf('\uFFFD') >= 0)
                        || (vlan.getUntaggedPorts() != null && vlan.getUntaggedPorts().indexOf('\uFFFD') >= 0));
    }

    /** 批量补采 ONT 模板号，返回补上台数 */
    public int fillOntProfiles(OltDevice device, List<OltBoard> boards) throws IOException {
        if (boards == null || boards.isEmpty()) {
            return 0;
        }
        List<String> notes = new ArrayList<>();
        Charset charset = OltCharset.resolve(device.getCharset());
        Integer filled = sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.fillOntProfilesFromConfig(boards, notes));
        if (!notes.isEmpty()) {
            Logs.info(Logs.Module.OLT_COLLECT, "OLT {} ONT 模板号补采提示：{}", device.getHost(), String.join("；", notes));
        }
        return filled == null ? 0 : filled;
    }

    /** 查询单端口光模块信息 */
    public OltPort queryPortOptics(OltDevice device, int slot, int portNo) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String out = doQueryPortState(device, charset, slot, portNo);
        if (OltCharset.isAuto(device.getCharset()) && out != null && out.indexOf('\uFFFD') >= 0) {
            out = doQueryPortState(device, OltCharset.fallback(), slot, portNo);
        }
        return OltInfoParser.parsePortStateOptics(out);
    }

    /** 查询链路聚合组 */
    public String queryLinkAggregations(OltDevice device) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String table = doQueryLinkAggregations(device, charset);
        if (OltCharset.isAuto(device.getCharset()) && table != null && table.indexOf('\uFFFD') >= 0) {
            Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} 链路聚合回显疑似非 UTF-8，改用 GBK 重试",
                    device.getHost());
            table = doQueryLinkAggregations(device, OltCharset.fallback());
        }
        return table;
    }

    private String doQueryLinkAggregations(OltDevice device, Charset charset) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                OltCliSession::queryLinkAggregations);
    }

    /** 删除链路聚合组 */
    public String deleteLinkAggregation(OltDevice device, String masterFs) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String out = doDeleteLinkAggregation(device, charset, masterFs);
        if (OltCharset.isAuto(device.getCharset()) && out != null && out.indexOf('\uFFFD') >= 0) {
            Logs.debug(Logs.Module.OLT_COLLECT, "OLT {} 删除聚合组回显疑似非 UTF-8，改用 GBK 重试",
                    device.getHost());
            out = doDeleteLinkAggregation(device, OltCharset.fallback(), masterFs);
        }
        return out;
    }

    private String doDeleteLinkAggregation(OltDevice device, Charset charset, String masterFs)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.deleteLinkAggregation(masterFs));
    }

    private String doQueryPortState(OltDevice device, Charset charset, int slot, int portNo) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.queryPortState(slot, portNo));
    }

    /** 查询单条 ONT 详情 */
    public String queryOnt(OltDevice device, int slot, int port, int ontId, String subcommand) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String out = doQueryOnt(device, charset, slot, port, ontId, subcommand);
        if (OltCharset.isAuto(device.getCharset()) && out != null && out.indexOf('\uFFFD') >= 0) {
            return doQueryOnt(device, OltCharset.fallback(), slot, port, ontId, subcommand);
        }
        return out;
    }

    private String doQueryOnt(OltDevice device, Charset charset, int slot, int port,
                              int ontId, String subcommand) throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.queryOntDetail(slot, port, ontId, subcommand));
    }

    /** 查询单条 ONT 的配置摘录、业务流表格与详情 */
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

    /** 查询单条 ONT 的业务流表格 */
    public String queryOntServicePorts(OltDevice device, int slot, int port, int ontId) throws IOException {
        Charset charset = OltCharset.resolve(device.getCharset());
        String out = doQueryOntServicePorts(device, charset, slot, port, ontId);
        if (OltCharset.isAuto(device.getCharset()) && out != null && out.indexOf('\uFFFD') >= 0) {
            return doQueryOntServicePorts(device, OltCharset.fallback(), slot, port, ontId);
        }
        return out;
    }

    private String doQueryOntServicePorts(OltDevice device, Charset charset, int slot, int port, int ontId)
            throws IOException {
        return sessionPool.execute(device, charset, charsetStrict(device, charset),
                session -> session.queryServicePorts(slot, port, ontId));
    }

    /** 只读执行查询命令并返回回显 */
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

    /** 下发配置命令 */
    public List<String> sendConfig(OltDevice device, List<String> commands) throws IOException {
        return sendConfig(device, commands, false);
    }

    /** 下发配置命令（容忍已存在的配置项） */
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

    /** 判断会话字符集是否需严格匹配 */
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

    /** 回显落库前截断 */
    public String truncateRaw(String raw) {
        if (raw == null) {
            return null;
        }
        int limit = maxRawOutputChars > 0 ? maxRawOutputChars : 60000;
        return raw.length() <= limit ? raw : raw.substring(0, limit) + "\n...[已截断，原长 " + raw.length() + " 字符]";
    }
}

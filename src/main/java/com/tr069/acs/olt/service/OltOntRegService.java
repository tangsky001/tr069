package com.tr069.acs.olt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.entity.OltOntRegConfig;
import com.tr069.acs.olt.entity.OltOntRegRecord;
import com.tr069.acs.olt.entity.OltOntRegTemplate;
import com.tr069.acs.olt.mapper.OltBoardMapper;
import com.tr069.acs.olt.mapper.OltDeviceMapper;
import com.tr069.acs.olt.mapper.OltOntRegConfigMapper;
import com.tr069.acs.olt.mapper.OltOntRegRecordMapper;
import com.tr069.acs.olt.mapper.OltOntRegTemplateMapper;
import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.model.OltOntAutofind;
import com.tr069.acs.olt.model.OltOntRegisterRequest;
import com.tr069.acs.olt.model.OltOntRegisterResult;
import com.tr069.acs.olt.model.OltServicePort;
import com.tr069.acs.olt.model.VlanIdExpression;
import com.tr069.acs.olt.telnet.OltInfoParser;
import com.tr069.acs.olt.telnet.OltVendor;
import com.tr069.acs.olt.vendor.OltVendorProfile;
import com.tr069.acs.olt.vendor.OltVendorProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** ONT 注册：模板维护 + 手动一键注册 + 后台自动注册 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OltOntRegService {

    /** 注册记录里自动注册的标记 */
    public static final String SOURCE_AUTO = "AUTO";

    public static final String SOURCE_MANUAL = "MANUAL";

    /** 后台自动注册写在操作人这一列 */
    public static final String OPERATOR_AUTO = "自动注册";

    private final OltDeviceMapper deviceMapper;

    private final OltBoardMapper boardMapper;

    private final OltOntRegTemplateMapper templateMapper;

    private final OltOntRegConfigMapper configMapper;

    private final OltOntRegRecordMapper recordMapper;

    private final OltTelnetService telnetService;

    private final OltService oltService;

    // ---------------------------------------------------------------- 注册模板

    /** 模板列表 */
    public List<OltOntRegTemplate> templates(Long oltId, boolean onlyCurrent) {
        LambdaQueryWrapper<OltOntRegTemplate> wrapper = new LambdaQueryWrapper<>();
        if (onlyCurrent && oltId != null) {
            wrapper.and(w -> w.isNull(OltOntRegTemplate::getOltId)
                    .or().eq(OltOntRegTemplate::getOltId, oltId));
        }
        wrapper.orderByAsc(OltOntRegTemplate::getPriority)
                .orderByAsc(OltOntRegTemplate::getId);
        return templateMapper.selectList(wrapper);
    }

    /** 新建模板的初始表单：默认值由后端给，页面不自己准备一份 */
    public OltOntRegTemplate blankTemplate(Long oltId, String oltIp) {
        OltOntRegTemplate template = new OltOntRegTemplate();
        template.setOltId(oltId);
        template.setOltIp(oltIp);
        template.setName("");
        template.setPriority(0);
        template.setEnabled(1);
        template.setAuthMode("SN");
        template.setPonType("GPON");
        template.setWorkMode("HGU");
        return template;
    }

    /** 新增或更新一条模板；字段缺的按默认值补，不让页面上留空导致后面匹配不上 */
    public OltOntRegTemplate saveTemplate(Long oltId, OltOntRegTemplate form) {
        if (form == null || form.getName() == null || form.getName().isBlank()) {
            throw new IllegalArgumentException("模板名称不能为空");
        }
        // 优先级是匹配顺序也是设备侧理解的值域，越界直接拒，不让一条脏数据进库
        if (form.getPriority() != null && (form.getPriority() < 0 || form.getPriority() > 999)) {
            throw new IllegalArgumentException("优先级要填 0-999 之间的数字");
        }
        if (form.getAuthMode() != null && !form.getAuthMode().isBlank()
                && !Set.of("SN", "MAC", "LOID", "PASSWORD").contains(form.getAuthMode().trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("注册方式只能是 SN / MAC / LOID / PASSWORD");
        }
        if (form.getPonType() != null && !form.getPonType().isBlank()
                && !Set.of("GPON", "EPON").contains(form.getPonType().trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("PON 类型只能是 GPON / EPON");
        }
        if (form.getWorkMode() != null && !form.getWorkMode().isBlank()
                && !Set.of("HGU", "SFU", "MIX", "MDU").contains(form.getWorkMode().trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("光猫工作模式只能是 HGU / SFU / MIX / MDU");
        }
        OltOntRegTemplate template = new OltOntRegTemplate();
        template.setId(form.getId());
        template.setOltId(form.getOltId() != null ? form.getOltId() : oltId);
        template.setOltIp(form.getOltIp());
        template.setName(form.getName().trim());
        template.setPriority(form.getPriority() == null ? 0 : form.getPriority());
        template.setEnabled(form.getEnabled() == null ? 1 : form.getEnabled());
        template.setAuthMode(normalizeAuthMode(form.getAuthMode()));
        template.setPonType(form.getPonType() == null || form.getPonType().isBlank()
                ? "GPON" : form.getPonType().trim().toUpperCase(Locale.ROOT));
        template.setLineProfileId(form.getLineProfileId());
        template.setSrvProfileId(form.getSrvProfileId());
        template.setBindPorts(blankToNull(form.getBindPorts()));
        template.setOntModels(blankToNull(form.getOntModels()));
        template.setWorkMode(form.getWorkMode());
        template.setServiceVlanMap(blankToNull(form.getServiceVlanMap()));
        template.setTr069Vlan(blankToNull(form.getTr069Vlan()));
        template.setIptvVlan(blankToNull(form.getIptvVlan()));
        template.setMulticastVlan(blankToNull(form.getMulticastVlan()));
        template.setRemark(form.getRemark());
        if (template.getId() == null) {
            templateMapper.insert(template);
        } else {
            OltOntRegTemplate exists = templateMapper.selectById(template.getId());
            if (exists == null) {
                throw new IllegalArgumentException("模板不存在或已删除");
            }
            // 不能用 updateById：它默认跳过 null 字段，页面上「清空」的字段
            // （业务 VLAN、绑定端口这类可空文本）传上来是 null，原样留在库里，
            // 清了等于没清。这里把这些列显式 set，空值才写得进去。
            templateMapper.update(template, new LambdaUpdateWrapper<OltOntRegTemplate>()
                    .eq(OltOntRegTemplate::getId, template.getId())
                    .set(OltOntRegTemplate::getBindPorts, template.getBindPorts())
                    .set(OltOntRegTemplate::getOntModels, template.getOntModels())
                    .set(OltOntRegTemplate::getServiceVlanMap, template.getServiceVlanMap())
                    .set(OltOntRegTemplate::getTr069Vlan, template.getTr069Vlan())
                    .set(OltOntRegTemplate::getIptvVlan, template.getIptvVlan())
                    .set(OltOntRegTemplate::getMulticastVlan, template.getMulticastVlan())
                    .set(OltOntRegTemplate::getRemark, template.getRemark()));
        }
        return templateMapper.selectById(template.getId());
    }

    public void deleteTemplates(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        templateMapper.deleteByIds(ids);
    }

    // ---------------------------------------------------------------- 自动注册参数

    /** 这台 OLT 的参数；没存过就给一份默认值，页面上第一次打开也有数 */
    public OltOntRegConfig config(Long oltId) {
        OltOntRegConfig config = selectConfig(oltId);
        if (config != null) {
            // 时间文本随参数一起给：当天只写时分秒、跨天带月日，页面原来自己判「是不是今天」
            decorateTimes(config);
            return config;
        }
        OltOntRegConfig fresh = new OltOntRegConfig();
        fresh.setOltId(oltId);
        fresh.setEnabled(0);
        fresh.setScanSeconds(30);
        fresh.setSaveSeconds(28800);
        fresh.setConcurrency(1);
        fresh.setAutoMove(0);
        fresh.setAutoDeleteOffline(0);
        decorateTimes(fresh);
        return fresh;
    }

    /** 当天的时间只写时分秒，看得清「刚扫过」 */
    private static final DateTimeFormatter TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 跨天的时间带上月-日，避免把昨天的记录看成今天的 */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    /** 三个「最近…」时间的显示文本：当天只写时分秒，跨天写月-日 时分秒，空着写「—」 */
    private static void decorateTimes(OltOntRegConfig config) {
        LocalDate today = LocalDate.now();
        config.setLastScanText(timeTextOf(config.getLastScanAt(), today));
        config.setLastRegisterText(timeTextOf(config.getLastRegisterAt(), today));
        config.setLastSaveText(timeTextOf(config.getLastSaveAt(), today));
    }

    private static String timeTextOf(LocalDateTime time, LocalDate today) {
        if (time == null) {
            return "—";
        }
        return time.toLocalDate().isEqual(today)
                ? time.format(TIME_ONLY)
                : time.format(DATE_TIME);
    }

    public OltOntRegConfig saveConfig(Long oltId, OltOntRegConfig form) {
        if (form == null) {
            throw new IllegalArgumentException("参数不能为空");
        }
        OltOntRegConfig exists = selectConfig(oltId);
        OltOntRegConfig config = exists == null ? new OltOntRegConfig() : exists;
        config.setOltId(oltId);
        config.setEnabled(form.getEnabled() == null ? 0 : form.getEnabled());
        config.setScanSeconds(form.getScanSeconds() == null || form.getScanSeconds() < 5
                ? 30 : form.getScanSeconds());
        config.setSaveSeconds(form.getSaveSeconds() == null || form.getSaveSeconds() < 0
                ? 28800 : form.getSaveSeconds());
        config.setConcurrency(form.getConcurrency() == null || form.getConcurrency() < 1
                ? 1 : form.getConcurrency());
        config.setAutoMove(form.getAutoMove() == null ? 0 : form.getAutoMove());
        config.setAutoDeleteOffline(form.getAutoDeleteOffline() == null ? 0 : form.getAutoDeleteOffline());
        if (exists == null) {
            configMapper.insert(config);
        } else {
            configMapper.updateById(config);
        }
        return config(oltId);
    }

    private OltOntRegConfig selectConfig(Long oltId) {
        if (oltId == null) {
            return null;
        }
        return configMapper.selectOne(new LambdaQueryWrapper<OltOntRegConfig>()
                .eq(OltOntRegConfig::getOltId, oltId)
                .last("LIMIT 1"));
    }

    // ---------------------------------------------------------------- 注册记录

    public List<OltOntRegRecord> records(Long oltId, int limit) {
        if (oltId == null) {
            return List.of();
        }
        int size = limit <= 0 ? 50 : Math.min(limit, 200);
        return recordMapper.selectList(new LambdaQueryWrapper<OltOntRegRecord>()
                .eq(OltOntRegRecord::getOltId, oltId)
                .orderByDesc(OltOntRegRecord::getId)
                .last("LIMIT " + size));
    }

    // ---------------------------------------------------------------- 注册

    /** 注册一批待注册 ONT */
    public List<OltOntRegisterResult> register(Long oltId, OltOntRegisterRequest request,
                                               String operator, String source) {
        OltDevice device = requireDevice(oltId);
        OltVendorProfile profile = profileOf(device);
        if (!profile.supportsOntConfirm()) {
            throw new IllegalArgumentException("这台设备还没接入 ONT 注册命令");
        }
        OltOntRegisterRequest req = request == null ? new OltOntRegisterRequest() : request;
        List<OltOntRegisterRequest.Row> rows = rowsOf(oltId, req);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<OltOntRegTemplate> templates = templates(oltId, true);
        OltOntRegTemplate fixed = null;
        if (req.getTemplateId() != null) {
            fixed = templates.stream().filter(t -> t.getId().equals(req.getTemplateId())).findFirst().orElse(null);
            if (fixed == null) {
                throw new IllegalArgumentException("指定的注册模板不存在或已删除");
            }
        }
        boolean auto = SOURCE_AUTO.equals(source);
        int limit = auto ? Math.max(1, concurrency(oltId)) : Integer.MAX_VALUE;
        List<OltOntRegisterResult> results = new ArrayList<>();
        int handled = 0;
        for (OltOntRegisterRequest.Row row : rows) {
            if (handled >= limit) {
                log.info("OLT {} 本轮注册达到并发上限 {}，剩下的下一轮处理", oltId, limit);
                break;
            }
            handled++;
            OltOntRegisterResult result = registerOne(device, profile, row, fixed, templates);
            results.add(result);
            saveRecord(oltId, row, result, operator, source, auto);
        }
        return results;
    }

    /** 只编命令不下发，给页面预览 */
    public List<String> previewCommands(Long oltId, OltOntRegisterRequest request) {
        OltDevice device = requireDevice(oltId);
        OltVendorProfile profile = profileOf(device);
        if (!profile.supportsOntConfirm()) {
            throw new IllegalArgumentException("这台设备还没接入 ONT 注册命令");
        }
        OltOntRegisterRequest req = request == null ? new OltOntRegisterRequest() : request;
        List<OltOntRegisterRequest.Row> rows = rowsOf(oltId, req);
        List<OltOntRegTemplate> templates = templates(oltId, true);
        OltOntRegTemplate fixed = req.getTemplateId() == null ? null
                : templates.stream().filter(t -> t.getId().equals(req.getTemplateId())).findFirst().orElse(null);
        List<String> commands = new ArrayList<>();
        for (OltOntRegisterRequest.Row row : rows) {
            OltOntRegTemplate template = fixed != null ? fixed : match(templates, row);
            if (template == null) {
                commands.add("# " + describe(row) + "：没有匹配到注册模板");
                continue;
            }
            int[] fsp = fsp(row.getFsp());
            if (fsp == null) {
                commands.add("# " + describe(row) + "：端口格式不对（" + row.getFsp() + "）");
                continue;
            }
            commands.addAll(commandsFor(profile, fsp, template, row));
            // 业务虚口不在预览里逐条列出：ONT ID 是 confirm 时设备现分配的，注册成功才查得到
            List<Integer> previewVlans = serviceVlansOf(device, profile, template);
            if (!previewVlans.isEmpty() && profile.supportsServicePorts()) {
                commands.add("# 注册成功后按模板「" + template.getName() + "」下发业务虚口（VLAN "
                        + previewVlans + "，ONT ID 由设备分配）");
            }
        }
        return commands;
    }

    /** 后台自动跑一轮：扫设备上的待注册 ONT → 按优先级匹配模板 → 注册 → 到点存盘 */
    public int autoRegister(Long oltId) {
        return runCycle(oltId, true);
    }

    /** 手动触发的一轮：不看开关（开关管的是后台定时任务跑不跑） */
    public int scanNow(Long oltId) {
        return runCycle(oltId, false);
    }

    private int runCycle(Long oltId, boolean requireEnabled) {
        OltOntRegConfig config = config(oltId);
        if (requireEnabled && !isEnabled(config.getEnabled())) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        config.setLastScanAt(now);
        List<OltOntAutofind> found;
        try {
            found = oltService.ontAutofind(oltId);
        } catch (Exception e) {
            log.info("自动注册扫描失败 id={}: {}", oltId, e.getMessage());
            updateScanTime(config);
            return 0;
        }
        updateScanTime(config);
        if (found.isEmpty()) {
            // 这条日志是给排障用的：文件里能看到「扫了但没扫到」，和「根本没扫」区分开
            log.info("ONT 自动注册：扫描完成，设备上没有待注册光猫 id={}", oltId);
            return 0;
        }
        OltOntRegisterRequest request = new OltOntRegisterRequest();
        request.setRows(found.stream().map(OltOntRegService::row).collect(Collectors.toList()));
        List<OltOntRegisterResult> results;
        try {
            results = register(oltId, request, OPERATOR_AUTO, SOURCE_AUTO);
        } catch (Exception e) {
            log.warn("自动注册失败 id={}: {}", oltId, e.getMessage());
            return 0;
        }
        int ok = (int) results.stream().filter(OltOntRegisterResult::isSuccess).count();
        // 已经在线的老 ONT 不在自动发现表里，注册流程碰不到它们，单独补一遍业务虚口
        try {
            int repaired = repairServicePorts(requireDevice(oltId), profileOf(requireDevice(oltId)),
                    templates(oltId, true));
            if (repaired > 0) {
                log.info("ONT 自动注册：补发业务虚口 {} 台 id={}", repaired, oltId);
            }
        } catch (Exception e) {
            log.info("补发业务虚口异常 id={}: {}", oltId, e.getMessage());
        }
        if (ok > 0) {
            config.setLastRegisterAt(LocalDateTime.now());
            updateRegisterTime(config);
            maybeSave(oltId, config);
        }
        return ok;
    }

    // ---------------------------------------------------------------- 单台注册

    private OltOntRegisterResult registerOne(OltDevice device, OltVendorProfile profile,
                                             OltOntRegisterRequest.Row row,
                                             OltOntRegTemplate fixed,
                                             List<OltOntRegTemplate> templates) {
        OltOntRegisterResult result = new OltOntRegisterResult();
        result.setFsp(row.getFsp());
        result.setSn(row.getSn());
        result.setModel(row.getModel());
        OltOntRegTemplate template = fixed != null ? fixed : match(templates, row);
        if (template == null) {
            result.setSuccess(false);
            result.setMessage("没有匹配到注册模板：PON 类型、端口或型号对不上，或者模板都停用了");
            return result;
        }
        result.setTemplateName(template.getName());
        result.setAuthMode(template.getAuthMode());
        int[] fsp = fsp(row.getFsp());
        if (fsp == null) {
            result.setSuccess(false);
            result.setMessage("端口格式不对：" + row.getFsp() + "（要形如 0/2/0）");
            return result;
        }
        // 自动发现编号不再是命令的一部分（设备按 SN 认光猫、ONT ID 自己分配），
        // 缺编号照样能注册，不再卡在这一步
        String authProblem = authProblem(template, row);
        if (authProblem != null) {
            result.setSuccess(false);
            result.setMessage(authProblem);
            return result;
        }
        List<String> commands = commandsFor(profile, fsp, template, row);
        result.setCommand(String.join("；", commands));
        try {
            telnetService.sendConfig(device, commands);
            result.setSuccess(true);
            // 注册上只是通了管理通道，用户流量还要业务虚口（service-port）才放得出去
            result.setMessage("已按模板「" + template.getName() + "」注册"
                    + applyOntModelRemark(device, profile, fsp, row)
                    + deliverServicePorts(device, profile, fsp, template, row));
            return result;
        } catch (Exception first) {
            String message = rootMessage(first);
            OltOntRegConfig config = config(device.getId());
            if (isEnabled(config.getAutoMove()) && looksDuplicate(message)) {
                // 换口上线：这台 SN 已经在别的口注册过，先删旧记录再按新口确认
                RegisteredOnt old = findRegistered(device, profile, authValue(template, row));
                if (old != null) {
                    result.setMessage("该 ONT 已在 " + old.fsp + " 注册（ONT ID " + old.ontId + "），按自动移机先删旧记录");
                    if (deleteAndRetry(device, profile, old, commands)) {
                        result.setSuccess(true);
                        result.setMessage("已自动移机到 " + row.getFsp() + " 并注册（原 " + old.fsp + " 已删除）"
                                + applyOntModelRemark(device, profile, fsp, row)
                                + deliverServicePorts(device, profile, fsp, template, row));
                        return result;
                    }
                }
            }
            if (isEnabled(config.getAutoDeleteOffline()) && looksFull(message)) {
                int cleared = deleteOffline(device, profile, fsp);
                if (cleared > 0 && deleteAndRetry(device, profile, null, commands)) {
                    result.setSuccess(true);
                    result.setMessage("注册表已满，清掉 " + cleared + " 台离线 ONT 后注册成功"
                            + applyOntModelRemark(device, profile, fsp, row)
                            + deliverServicePorts(device, profile, fsp, template, row));
                    return result;
                }
                result.setMessage(message + "（已清掉 " + cleared + " 台离线 ONT，仍然没注册上）");
                return result;
            }
            result.setSuccess(false);
            result.setMessage(message);
            return result;
        }
    }

    /** 命令块：进板视图 → ont confirm → 退出视图 */
    private List<String> commandsFor(OltVendorProfile profile, int[] fsp,
                                     OltOntRegTemplate template, OltOntRegisterRequest.Row row) {
        List<String> commands = new ArrayList<>();
        commands.add(profile.ponViewCommand(fsp[0], fsp[1]));
        String confirm = profile.ontConfirmCommand(fsp[2], template.getAuthMode(),
                authValue(template, row), template.getLineProfileId(), template.getSrvProfileId());
        if (confirm == null) {
            throw new IllegalStateException("编不出 ont confirm 命令（认证方式或认证串缺失）");
        }
        commands.add(confirm);
        commands.add("quit");
        return commands;
    }

    /** GEM Port 兜底号：线路模板读不出来时才用它（一键开局建的是 gem add 1） */
    private static final int DEFAULT_GEM_PORT = 1;

    /**
     * 注册成功后把光猫型号写进 ONT 描述（页面上看到的「备注 / 型号」就是设备侧的 desc），
     * 例如 P22g-Clac。写失败只记在结果说明里，不能把已经注册成功的 ONT 报成失败——
     * 描述只是备注，跟能不能上网无关。
     */
    private String applyOntModelRemark(OltDevice device, OltVendorProfile profile, int[] fsp,
                                       OltOntRegisterRequest.Row row) {
        String model = blankToNull(row.getModel());
        if (model == null || !profile.supportsOntDescription()) {
            return "";
        }
        Integer ontId = findOntIdOnPort(device, profile, fsp, row.getSn());
        if (ontId == null) {
            return "；没查到设备分配的 ONT ID，备注未写型号";
        }
        String command = profile.ontDescriptionCommand(fsp[2], ontId, model);
        if (command == null) {
            return "";
        }
        try {
            telnetService.sendConfig(device, List.of(
                    profile.ponViewCommand(fsp[0], fsp[1]), command, "quit"));
            return "；备注已写型号 " + model;
        } catch (Exception e) {
            log.info("ONT 备注写型号失败 id={}: {}", device.getId(), e.getMessage());
            return "；备注写型号失败：" + rootMessage(e);
        }
    }

    /** 一轮最多补几台老 ONT 的业务虚口：补太多会一直占着设备的 Telnet 会话 */
    private static final int MAX_REPAIR_PER_ROUND = 10;

    /** 注册成功后按模板建业务虚口（service-port），返回追加到结果里的说明 */
    private String deliverServicePorts(OltDevice device, OltVendorProfile profile, int[] fsp,
                                       OltOntRegTemplate template, OltOntRegisterRequest.Row row) {
        if (!profile.supportsServicePorts()) {
            return "";
        }
        List<Integer> vlans = serviceVlansOf(device, profile, template);
        if (vlans.isEmpty()) {
            return "；模板没配业务 VLAN，线路模板里也没读到 GEM 映射，不下发业务虚口";
        }
        Integer ontId = findOntIdOnPort(device, profile, fsp, row.getSn());
        if (ontId == null) {
            return "；没查到设备分配的 ONT ID，业务虚口未下发";
        }
        int startIndex = nextServicePortIndex(device);
        if (startIndex < 0) {
            return "；查不到设备上已有的业务虚口（没法避开已用索引），未下发";
        }
        List<String> commands = new ArrayList<>();
        int index = startIndex;
        Map<Integer, Integer> gemByVlan = gemVlanMapOf(device, profile, template);
        for (Integer vlan : vlans) {
            commands.add(profile.servicePortCommand(index++, vlan,
                    fsp[0], fsp[1], fsp[2], ontId, gemByVlan.getOrDefault(vlan, positionGem(vlans, vlan))));
        }
        try {
            telnetService.sendConfig(device, commands);
            return "；已下发 " + commands.size() + " 条业务虚口（VLAN " + vlans + "）";
        } catch (Exception e) {
            log.info("业务虚口下发失败 id={}: {}", device.getId(), e.getMessage());
            return "；业务虚口下发失败：" + rootMessage(e);
        }
    }

    /** 给「早就注册过、但一条业务虚口都没有」的 ONT 补下发 */
    private int repairServicePorts(OltDevice device, OltVendorProfile profile,
                                   List<OltOntRegTemplate> templates) {
        if (!profile.supportsServicePorts() || templates.isEmpty()) {
            return 0;
        }
        Set<String> hasFlow = existingServicePortKeys(device);
        if (hasFlow == null) {
            return 0;
        }
        int repaired = 0;
        for (Integer slot : gponSlots(device.getId())) {
            if (repaired >= MAX_REPAIR_PER_ROUND) {
                break;
            }
            for (Map.Entry<Integer, List<OltOnt>> entry : ontListOfBoard(device, profile, 0, slot).entrySet()) {
                Integer port = entry.getKey();
                for (OltOnt ont : entry.getValue()) {
                    if (repaired >= MAX_REPAIR_PER_ROUND) {
                        break;
                    }
                    if (ont.getOntId() == null || !Boolean.TRUE.equals(ont.getOnline())) {
                        continue;
                    }
                    if (hasFlow.contains(portKey(0, slot, port, ont.getOntId()))) {
                        continue;
                    }
                    OltOntRegTemplate template = match(templates, rowOf(ont, slot, port));
                    if (template == null) {
                        continue;
                    }
                    List<Integer> vlans = serviceVlansOf(device, profile, template);
                    if (vlans.isEmpty()) {
                        continue;
                    }
                    int startIndex = nextServicePortIndex(device);
                    if (startIndex < 0) {
                        return repaired;
                    }
                    List<String> commands = new ArrayList<>();
                    int index = startIndex;
                    Map<Integer, Integer> gemByVlan = gemVlanMapOf(device, profile, template);
                    for (Integer vlan : vlans) {
                        commands.add(profile.servicePortCommand(index++, vlan, 0, slot, port,
                                ont.getOntId(), gemByVlan.getOrDefault(vlan, positionGem(vlans, vlan))));
                    }
                    try {
                        telnetService.sendConfig(device, commands);
                        repaired++;
                        log.info("补发业务虚口 id={} 0/{}/{} ONT {} VLAN {}",
                                device.getId(), slot, port, ont.getOntId(), vlans);
                    } catch (Exception e) {
                        log.info("补发业务虚口失败 id={} 0/{}/{} ONT {}: {}", device.getId(),
                                slot, port, ont.getOntId(), e.getMessage());
                    }
                }
            }
        }
        return repaired;
    }

    /** 设备上现有业务虚口的「口#ONT」集合；查不到返回 null（避免误判成「都没有」而重复下发） */
    private Set<String> existingServicePortKeys(OltDevice device) {
        try {
            List<String> outputs = telnetService.queryCommands(device, List.of("display service-port all"));
            String text = outputs == null || outputs.isEmpty() ? null : outputs.get(0);
            if (text == null) {
                return null;
            }
            Set<String> keys = new HashSet<>();
            for (OltServicePort sp : OltInfoParser.parseServicePorts(text)) {
                if (sp.getOntId() != null) {
                    keys.add(sp.getFrameNo() + "/" + sp.getSlotNo() + "/" + sp.getPortNo() + "#" + sp.getOntId());
                }
            }
            return keys;
        } catch (Exception e) {
            log.info("查现有业务虚口失败 id={}: {}", device.getId(), e.getMessage());
            return null;
        }
    }

    private String portKey(int frame, int slot, int port, int ontId) {
        return frame + "/" + slot + "/" + port + "#" + ontId;
    }

    /** 把一台已注册的 ONT 包装成待注册行，好走同一套模板匹配（型号不报就当不限） */
    private OltOntRegisterRequest.Row rowOf(OltOnt ont, int slot, int port) {
        OltOntRegisterRequest.Row row = new OltOntRegisterRequest.Row();
        row.setSn(ont.getSn());
        row.setFsp("0/" + slot + "/" + port);
        // 只扫 GPON 业务板，所以按 GPON 参与匹配
        row.setPonType("GPON");
        return row;
    }

    /** 要放通的业务 VLAN：模板上填了的（TR069 VLAN + 业务 VLAN + IPTV VLAN）优先 */
    private List<Integer> serviceVlansOf(OltDevice device, OltVendorProfile profile,
                                         OltOntRegTemplate template) {
        LinkedHashSet<Integer> vlans = new LinkedHashSet<>();
        Integer tr069 = firstInt(template.getTr069Vlan());
        if (tr069 != null) {
            vlans.add(tr069);
        }
        String map = template.getServiceVlanMap();
        if (map != null && !map.isBlank()) {
            vlans.addAll(VlanIdExpression.expand(map.trim()));
        } else if (template.getLineProfileId() != null) {
            vlans.addAll(gemVlanMapOf(device, profile, template).keySet());
        }
        Integer iptv = firstInt(template.getIptvVlan());
        if (iptv != null) {
            vlans.add(iptv);
        }
        return new ArrayList<>(vlans);
    }

    /** 读线路模板详情，得到「VLAN → 承载它的 GEM Port」；读不到返回空表 */
    private Map<Integer, Integer> gemVlanMapOf(OltDevice device, OltVendorProfile profile,
                                               OltOntRegTemplate template) {
        if (template.getLineProfileId() == null) {
            return Map.of();
        }
        String command = profile.lineProfileDetailCommand(template.getPonType(), template.getLineProfileId());
        if (command == null) {
            return Map.of();
        }
        try {
            List<String> outputs = telnetService.queryCommands(device, List.of(command));
            String text = outputs == null || outputs.isEmpty() ? null : outputs.get(0);
            return OltInfoParser.parseGemVlanPairs(text);
        } catch (Exception e) {
            log.info("读线路模板 GEM 映射失败 id={} profileId={}: {}",
                    device.getId(), template.getLineProfileId(), e.getMessage());
            return Map.of();
        }
    }

    /** VLAN 在列表里的位置就是它的 GEM 号（一键开局按这个顺序建 GEM）；兜底 0 */
    private int positionGem(List<Integer> vlans, Integer vlan) {
        int position = vlans.indexOf(vlan);
        return position < 0 ? DEFAULT_GEM_PORT : position;
    }

    /** 「46 10」这种「VLAN 模板ID」写法只取第一个整数；不是数字返回 null */
    private Integer firstInt(String text) {
        String trimmed = text == null ? null : text.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return null;
        }
        for (String token : trimmed.split("[^0-9]+")) {
            if (!token.isEmpty()) {
                try {
                    return Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 在指定 PON 口上按 SN 找刚注册的 ONT 拿设备分配的 ONT ID */
    private Integer findOntIdOnPort(OltDevice device, OltVendorProfile profile, int[] fsp, String sn) {
        String wanted = normalizeSn(sn);
        if (wanted == null || wanted.isEmpty()) {
            return null;
        }
        Map<Integer, List<OltOnt>> byPort = ontListOfBoard(device, profile, fsp[0], fsp[1]);
        for (OltOnt ont : byPort.getOrDefault(fsp[2], List.of())) {
            if (ont.getSn() != null && normalizeSn(ont.getSn()).equals(wanted)) {
                return ont.getOntId();
            }
        }
        return null;
    }

    /** 挑下一个没被占用的业务虚口索引：全表现有里最大的往后排 */
    private int nextServicePortIndex(OltDevice device) {
        try {
            List<String> outputs = telnetService.queryCommands(device, List.of("display service-port all"));
            String text = outputs == null || outputs.isEmpty() ? null : outputs.get(0);
            int max = -1;
            if (text != null) {
                for (OltServicePort sp : OltInfoParser.parseServicePorts(text)) {
                    if (sp.getIndex() != null && sp.getIndex() > max) {
                        max = sp.getIndex();
                    }
                }
            }
            return max + 1;
        } catch (Exception e) {
            log.info("查业务虚口索引失败 id={}: {}", device.getId(), e.getMessage());
            return -1;
        }
    }

    /**
     * 删 ONT 前先查它名下的业务虚口，编出逐条 undo service-port（config 视图执行）。
     * 带着 service-port 引用的 ont delete 会被设备顶回来；就算删掉了也会留下指向
     * 空 ONT 的死流。返回的命令要排在 ont delete 之前下发。
     */
    private List<String> servicePortDeleteCommands(OltDevice device, OltVendorProfile profile,
                                                   int[] fsp, int ontId) {
        if (!profile.supportsServicePorts()) {
            return List.of();
        }
        String table;
        try {
            table = telnetService.queryOntServicePorts(device, fsp[1], fsp[2], ontId);
        } catch (Exception e) {
            log.info("查 ONT 业务虚口失败 id={} ont={}: {}", device.getId(), ontId, e.getMessage());
            return List.of();
        }
        List<String> commands = new ArrayList<>();
        for (OltServicePort sp : OltInfoParser.parseServicePorts(table)) {
            // 查询兜底会退回整口表格：只删这条 ONT 自己的流，别误伤同口其它光猫
            if (sp.getIndex() != null && Objects.equals(sp.getOntId(), ontId)) {
                commands.add(profile.servicePortDeleteCommand(sp.getIndex()));
            }
        }
        return commands;
    }

    /** 删掉别处的旧注册后重试一次 */
    private boolean deleteAndRetry(OltDevice device, OltVendorProfile profile,
                                   RegisteredOnt old, List<String> confirmCommands) {
        try {
            List<String> commands = new ArrayList<>();
            if (old != null) {
                int[] fsp = fsp(old.fsp);
                if (fsp == null) {
                    return false;
                }
                // 先拆业务虚口再删 ONT，顺序不能反
                commands.addAll(servicePortDeleteCommands(device, profile, fsp, old.ontId));
                commands.add(profile.ponViewCommand(fsp[0], fsp[1]));
                commands.add(profile.ontDeleteCommand(fsp[2], old.ontId));
                commands.add("quit");
            }
            commands.addAll(confirmCommands);
            telnetService.sendConfig(device, commands);
            return true;
        } catch (Exception e) {
            log.info("ONT 注册重试失败 id={}: {}", device.getId(), e.getMessage());
            return false;
        }
    }

    /** 清掉某个 PON 口下离线的 ONT，给新光猫腾位置 */
    private int deleteOffline(OltDevice device, OltVendorProfile profile, int[] fsp) {
        Map<Integer, List<OltOnt>> byPort = ontListOfBoard(device, profile, fsp[0], fsp[1]);
        List<OltOnt> onts = byPort.get(fsp[2]);
        if (onts == null || onts.isEmpty()) {
            return 0;
        }
        List<Integer> offline = onts.stream()
                .filter(ont -> !Boolean.TRUE.equals(ont.getOnline()))
                .map(OltOnt::getOntId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (offline.isEmpty()) {
            return 0;
        }
        List<String> commands = new ArrayList<>();
        // undo service-port 在 config 视图发，ont delete 要进 PON 板视图：先拆流再删记录
        for (Integer ontId : offline) {
            commands.addAll(servicePortDeleteCommands(device, profile, fsp, ontId));
        }
        commands.add(profile.ponViewCommand(fsp[0], fsp[1]));
        offline.forEach(ontId -> commands.add(profile.ontDeleteCommand(fsp[2], ontId)));
        commands.add("quit");
        try {
            telnetService.sendConfig(device, commands);
            return offline.size();
        } catch (Exception e) {
            log.info("删除离线 ONT 失败 id={}: {}", device.getId(), e.getMessage());
            return 0;
        }
    }

    /** 按 SN 找这台光猫是不是已经在别的口注册过 */
    private RegisteredOnt findRegistered(OltDevice device, OltVendorProfile profile, String sn) {
        if (sn == null || sn.isBlank()) {
            return null;
        }
        String wanted = normalizeSn(sn);
        for (Integer slot : gponSlots(device.getId())) {
            Map<Integer, List<OltOnt>> byPort = ontListOfBoard(device, profile, 0, slot);
            for (Map.Entry<Integer, List<OltOnt>> entry : byPort.entrySet()) {
                for (OltOnt ont : entry.getValue()) {
                    if (ont.getSn() != null && normalizeSn(ont.getSn()).equals(wanted)) {
                        return new RegisteredOnt("0/" + slot + "/" + entry.getKey(),
                                entry.getKey(), ont.getOntId() == null ? 0 : ont.getOntId(),
                                Boolean.TRUE.equals(ont.getOnline()));
                    }
                }
            }
        }
        return null;
    }

    /** 整块板所有 PON 口的 ONT；查不到返回空表 */
    private Map<Integer, List<OltOnt>> ontListOfBoard(OltDevice device, OltVendorProfile profile,
                                                      int frame, int slot) {
        String command = profile.ontListCommand(frame, slot);
        if (command == null) {
            return Map.of();
        }
        try {
            List<String> outputs = telnetService.queryCommands(device, List.of(command));
            return profile.parseOntList(outputs == null || outputs.isEmpty() ? null : outputs.get(0));
        } catch (Exception e) {
            log.info("查 ONT 明细失败 id={} slot={}: {}", device.getId(), slot, e.getMessage());
            return Map.of();
        }
    }

    /** 到点把配置存盘：华为只写运行配置，不 save 掉电就回到注册前 */
    private void maybeSave(Long oltId, OltOntRegConfig config) {
        int saveSeconds = config.getSaveSeconds() == null ? 28800 : config.getSaveSeconds();
        LocalDateTime base = config.getLastSaveAt() != null ? config.getLastSaveAt() : config.getLastRegisterAt();
        if (base == null || Duration.between(base, LocalDateTime.now()).getSeconds() < saveSeconds) {
            return;
        }
        OltDevice device = deviceMapper.selectById(oltId);
        if (device == null) {
            return;
        }
        try {
            telnetService.sendConfig(device, List.of(profileOf(device).saveCommand()));
            config.setLastSaveAt(LocalDateTime.now());
            configMapper.updateById(config);
            log.info("自动注册后已存盘 id={}", oltId);
        } catch (Exception e) {
            log.warn("自动注册后存盘失败 id={}: {}", oltId, e.getMessage());
        }
    }

    /** 只更新已经存过的参数：没存过说明自动注册没开，不该被后台顺手建出一条 */
    private void updateScanTime(OltOntRegConfig config) {
        if (config.getId() == null) {
            return;
        }
        configMapper.updateById(config);
    }

    private void updateRegisterTime(OltOntRegConfig config) {
        updateScanTime(config);
    }

    // ---------------------------------------------------------------- 模板匹配

    /** 按优先级挑第一条命中的模板：PON 类型 → 端口 → 型号，三项都对上才算命中 */
    private OltOntRegTemplate match(List<OltOntRegTemplate> templates, OltOntRegisterRequest.Row row) {
        for (OltOntRegTemplate template : templates) {
            if (!isEnabled(template.getEnabled())) {
                continue;
            }
            if (!ponMatches(template.getPonType(), row.getPonType())) {
                continue;
            }
            if (!portsMatch(template.getBindPorts(), row.getFsp())) {
                continue;
            }
            if (!modelMatches(template.getOntModels(), row.getModel())) {
                continue;
            }
            return template;
        }
        return null;
    }

    private boolean ponMatches(String ponType, String actual) {
        String expected = ponType == null || ponType.isBlank() ? "GPON" : ponType.trim();
        String real = actual == null || actual.isBlank() ? "GPON" : actual.trim();
        return expected.equalsIgnoreCase(real);
    }

    /** 模板没填端口表示不限；填了就按 fsp 精确匹配（也认只写端口号的写法） */
    private boolean portsMatch(String bindPorts, String fsp) {
        if (bindPorts == null || bindPorts.isBlank()) {
            return true;
        }
        if (fsp == null || fsp.isBlank()) {
            return false;
        }
        for (String port : bindPorts.split("[,，\\s]+")) {
            if (port.isBlank()) {
                continue;
            }
            if (port.trim().equals(fsp.trim()) || port.trim().equals(lastSegment(fsp))) {
                return true;
            }
        }
        return false;
    }

    /** 模板指定了型号而光猫型号未知时不算命中——型号对不上套错模板比不注册更麻烦 */
    private boolean modelMatches(String ontModels, String model) {
        if (ontModels == null || ontModels.isBlank()) {
            return true;
        }
        if (model == null || model.isBlank()) {
            return false;
        }
        String real = model.trim().toLowerCase(Locale.ROOT);
        for (String item : ontModels.split("[,，\\s]+")) {
            if (item.isBlank()) {
                continue;
            }
            if (real.contains(item.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 模板要的认证方式，这台光猫有没有报上来 */
    private String authProblem(OltOntRegTemplate template, OltOntRegisterRequest.Row row) {
        String mode = normalizeAuthMode(template.getAuthMode());
        boolean missing = switch (mode) {
            case "LOID" -> row.getLoid() == null || row.getLoid().isBlank();
            case "PASSWORD" -> row.getPassword() == null || row.getPassword().isBlank();
            default -> row.getSn() == null || row.getSn().isBlank();
        };
        return missing ? "模板用 " + mode + " 认证，但这台光猫没上报对应的值，跳过" : null;
    }

    private String authValue(OltOntRegTemplate template, OltOntRegisterRequest.Row row) {
        return switch (normalizeAuthMode(template.getAuthMode())) {
            case "LOID" -> row.getLoid();
            case "PASSWORD" -> row.getPassword();
            default -> row.getSn();
        };
    }

    // ---------------------------------------------------------------- 工具

    private List<OltOntRegisterRequest.Row> rowsOf(Long oltId, OltOntRegisterRequest request) {
        List<OltOntRegisterRequest.Row> rows = request.getRows();
        if (rows != null && !rows.isEmpty()) {
            return rows;
        }
        // 没指定就注册设备上当前所有待注册的：自动注册走的就是这条
        return oltService.ontAutofind(oltId).stream()
                .map(OltOntRegService::row)
                .collect(Collectors.toList());
    }

    private static OltOntRegisterRequest.Row row(OltOntAutofind ont) {
        OltOntRegisterRequest.Row row = new OltOntRegisterRequest.Row();
        row.setId(ont.getId());
        row.setFsp(ont.getFsp());
        row.setSn(ont.getSn());
        row.setLoid(ont.getLoid());
        row.setPassword(ont.getPassword());
        row.setModel(ont.getModel());
        row.setVendorId(ont.getVendorId());
        row.setPonType(ont.getPonType());
        return row;
    }

    private void saveRecord(Long oltId, OltOntRegisterRequest.Row row, OltOntRegisterResult result,
                            String operator, String source, boolean auto) {
        // 自动注册每轮都跑，同一台光猫因为同一个原因失败时不重复记：
        // 否则 30 秒一条，记录表很快就只剩同一句话
        if (auto && !result.isSuccess() && sameAsLast(oltId, row.getSn(), result.getMessage())) {
            return;
        }
        OltOntRegRecord record = new OltOntRegRecord();
        record.setOltId(oltId);
        record.setTemplateName(result.getTemplateName());
        record.setFsp(row.getFsp());
        record.setOntSn(row.getSn());
        record.setModel(row.getModel());
        record.setAuthMode(result.getAuthMode());
        record.setSuccess(result.isSuccess() ? 1 : 0);
        record.setSource(source);
        record.setCommand(result.getCommand());
        record.setMessage(cut(result.getMessage(), 900));
        record.setOperator(operator);
        record.setCreatedAt(LocalDateTime.now());
        recordMapper.insert(record);
    }

    private boolean sameAsLast(Long oltId, String sn, String message) {
        if (sn == null || sn.isBlank()) {
            return false;
        }
        OltOntRegRecord last = recordMapper.selectOne(new LambdaQueryWrapper<OltOntRegRecord>()
                .eq(OltOntRegRecord::getOltId, oltId)
                .eq(OltOntRegRecord::getOntSn, sn)
                .orderByDesc(OltOntRegRecord::getId)
                .last("LIMIT 1"));
        return last != null && !Integer.valueOf(1).equals(last.getSuccess())
                && message != null && message.equals(last.getMessage());
    }

    private int concurrency(Long oltId) {
        OltOntRegConfig config = selectConfig(oltId);
        return config == null || config.getConcurrency() == null ? 1 : config.getConcurrency();
    }

    private List<Integer> gponSlots(Long oltId) {
        return boardMapper.selectList(new LambdaQueryWrapper<OltBoard>()
                        .eq(OltBoard::getOltId, oltId)
                        .orderByAsc(OltBoard::getSlotNo))
                .stream()
                .filter(board -> "SERVICE".equalsIgnoreCase(board.getBoardKind()))
                .filter(board -> board.getBoardType() != null
                        && board.getBoardType().toUpperCase(Locale.ROOT).contains("GP"))
                .map(OltBoard::getSlotNo)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private OltDevice requireDevice(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("OLT id 不能为空");
        }
        OltDevice device = deviceMapper.selectById(id);
        if (device == null) {
            throw new IllegalArgumentException("OLT 不存在或已删除");
        }
        return device;
    }

    private OltVendorProfile profileOf(OltDevice device) {
        return OltVendorProfiles.of(OltVendor.of(device.getVendor()));
    }

    /** 0/2/0 → {0,2,0}；认不出返回 null */
    private static int[] fsp(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.trim().split("/");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String lastSegment(String fsp) {
        int slash = fsp.lastIndexOf('/');
        return slash < 0 ? fsp : fsp.substring(slash + 1).trim();
    }

    private static String normalizeSn(String sn) {
        return sn.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
    }

    private static String normalizeAuthMode(String authMode) {
        if (authMode == null || authMode.isBlank()) {
            return "SN";
        }
        String mode = authMode.trim().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "MAC", "LOID", "PASSWORD" -> mode;
            default -> "SN";
        };
    }

    private static boolean isEnabled(Integer value) {
        return value != null && value == 1;
    }

    /** SN 重复：设备说这台已经在别处注册过了 */
    private static boolean looksDuplicate(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return lower.contains("exist") || lower.contains("duplicate") || lower.contains("already")
                || lower.contains("已注册") || lower.contains("已存在");
    }

    /** 注册表满：这台设备上的 ONT 记录到上限了 */
    private static boolean looksFull(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return lower.contains("exceed") || lower.contains("full") || lower.contains("no more")
                || lower.contains("上限") || lower.contains("已满") || lower.contains("满");
    }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String describe(OltOntRegisterRequest.Row row) {
        String sn = row.getSn() == null || row.getSn().isBlank() ? row.getLoid() : row.getSn();
        return row.getFsp() + " " + (sn == null ? "" : sn);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 一台已经注册的 ONT 在哪儿 */
    private record RegisteredOnt(String fsp, int port, int ontId, boolean online) {
    }
}

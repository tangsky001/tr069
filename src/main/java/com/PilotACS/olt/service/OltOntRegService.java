/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.902
 *
 */

package com.PilotACS.olt.service;
import com.PilotACS.common.log.Logs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.PilotACS.olt.entity.OltBoard;
import com.PilotACS.olt.entity.OltDevice;
import com.PilotACS.olt.entity.OltOntRegConfig;
import com.PilotACS.olt.entity.OltOntRegRecord;
import com.PilotACS.olt.entity.OltOntRegTemplate;
import com.PilotACS.olt.mapper.OltBoardMapper;
import com.PilotACS.olt.mapper.OltDeviceMapper;
import com.PilotACS.olt.mapper.OltOntRegConfigMapper;
import com.PilotACS.olt.mapper.OltOntRegRecordMapper;
import com.PilotACS.olt.mapper.OltOntRegTemplateMapper;
import com.PilotACS.olt.model.OltOnt;
import com.PilotACS.olt.model.OltOntAutofind;
import com.PilotACS.olt.model.OltOntRegisterRequest;
import com.PilotACS.olt.model.OltOntRegisterResult;
import com.PilotACS.olt.model.OltServicePort;
import com.PilotACS.olt.telnet.OltInfoParser;
import com.PilotACS.olt.telnet.OltVendor;
import com.PilotACS.olt.vendor.OltVendorProfile;
import com.PilotACS.olt.vendor.OltVendorProfiles;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            // 校验归属：模板的 oltId 必须与路径参数一致（或为全局模板）
            if (exists.getOltId() != null && !exists.getOltId().equals(oltId)) {
                throw new IllegalArgumentException("模板不属于这台 OLT，无权修改");
            }
            // updateById 跳过 null 字段，页面上清空的列写不进去，须显式 set
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

    public void deleteTemplates(Long oltId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (Long id : ids) {
            OltOntRegTemplate template = templateMapper.selectById(id);
            if (template == null) {
                continue;
            }
            if (template.getOltId() != null && !template.getOltId().equals(oltId)) {
                throw new IllegalArgumentException("模板 " + id + " 不属于这台 OLT，无权删除");
            }
        }
        templateMapper.deleteByIds(ids);
    }

    /** 删除 OLT 时清理这台设备的全部 ONT 注册关联数据 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByOltId(Long oltId) {
        if (oltId == null) {
            return;
        }
        templateMapper.delete(new LambdaQueryWrapper<OltOntRegTemplate>().eq(OltOntRegTemplate::getOltId, oltId));
        configMapper.delete(new LambdaQueryWrapper<OltOntRegConfig>().eq(OltOntRegConfig::getOltId, oltId));
        recordMapper.delete(new LambdaQueryWrapper<OltOntRegRecord>().eq(OltOntRegRecord::getOltId, oltId));
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
        oltService.lockDeviceOrThrow(oltId, "ONT 注册");
        try {
            return doRegister(oltId, request, operator, source);
        } finally {
            oltService.unlockDevice(oltId);
        }
    }

    private List<OltOntRegisterResult> doRegister(Long oltId, OltOntRegisterRequest request,
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
                Logs.info(Logs.Module.OLT, "OLT {} 本轮注册达到并发上限 {}，剩下的下一轮处理", oltId, limit);
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
            // ONT ID 是 confirm 时设备现分配的，注册成功先查 ID，再整批下发注册后配置
            StringBuilder after = new StringBuilder("# 注册成功后（ONT ID 由设备分配）");
            Integer tr069ProfileId = tr069ProfileIdOf(template);
            if (tr069ProfileId != null) {
                after.append("；ont tr069-server-config ").append(fsp[2]).append(" <ontId> profile-id ")
                        .append(tr069ProfileId);
            }
            List<VlanPair> mappings = serviceVlanMappingsOf(template);
            if (!mappings.isEmpty()) {
                VlanPair first = mappings.get(0);
                if (first.translated()) {
                    after.append("；ont port native-vlan ").append(fsp[2]).append(" <ontId> eth 1-")
                            .append(ONT_ETH_PORT_COUNT).append(" vlan ").append(first.userVlan());
                }
                for (VlanPair pair : mappings) {
                    after.append("；service-port vlan ").append(pair.serviceVlan()).append(" gpon ")
                            .append(fsp[0]).append('/').append(fsp[1]).append('/').append(fsp[2])
                            .append(" ont <ontId> gemport ? multi-service user-vlan ")
                            .append(pair.userVlan()).append(" tag-transform translate");
                }
            }
            commands.add(after.toString());
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
        oltService.lockDeviceOrThrow(oltId, "ONT 自动注册");
        try {
            return doRunCycle(oltId, requireEnabled);
        } finally {
            oltService.unlockDevice(oltId);
        }
    }

    private int doRunCycle(Long oltId, boolean requireEnabled) {
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
            Logs.info(Logs.Module.OLT, "自动注册扫描失败 id={}: {}", oltId, e.getMessage());
            updateScanTime(config);
            return 0;
        }
        updateScanTime(config);
        if (found.isEmpty()) {
            // 这条日志是给排障用的：文件里能看到「扫了但没扫到」，和「根本没扫」区分开
            Logs.info(Logs.Module.OLT, "ONT 自动注册：扫描完成，设备上没有待注册光猫 id={}", oltId);
            return 0;
        }
        OltOntRegisterRequest request = new OltOntRegisterRequest();
        request.setRows(found.stream().map(OltOntRegService::row).collect(Collectors.toList()));
        List<OltOntRegisterResult> results;
        try {
            results = register(oltId, request, OPERATOR_AUTO, SOURCE_AUTO);
        } catch (Exception e) {
            Logs.warn(Logs.Module.OLT, "自动注册失败 id={}: {}", oltId, e.getMessage());
            return 0;
        }
        int ok = (int) results.stream().filter(OltOntRegisterResult::isSuccess).count();
        // 已经在线的老 ONT 不在自动发现表里，注册流程碰不到它们，单独补一遍业务虚口
        try {
            OltDevice device = requireDevice(oltId);
            int repaired = repairServicePorts(device, profileOf(device),
                    templates(oltId, true));
            if (repaired > 0) {
                Logs.info(Logs.Module.OLT, "ONT 自动注册：补发业务虚口 {} 台 id={}", repaired, oltId);
            }
        } catch (Exception e) {
            Logs.info(Logs.Module.OLT, "补发业务虚口异常 id={}: {}", oltId, e.getMessage());
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
        // 设备按 SN 认光猫、ONT ID 自己分配，命令里不再需要自动发现编号
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
            // 注册上只是通了管理通道，用户流量还要业务虚口（service-port）才放得出去；
            // TR069 服务器模板与业务 VLAN 映射也在这一批紧跟下发（ONT ID 先查再发）
            result.setMessage("已按模板「" + template.getName() + "」注册"
                    + deliverPostRegister(device, profile, fsp, template, row));
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
                                + deliverPostRegister(device, profile, fsp, template, row));
                        return result;
                    } else {
                        result.setSuccess(false);
                        result.setMessage("自动移机失败：原 " + old.fsp + " 的 ONT 已删除，但新口注册未成功，需手动处理");
                        return result;
                    }
                }
            }
            if (isEnabled(config.getAutoDeleteOffline()) && looksFull(message)) {
                int cleared = deleteOffline(device, profile, fsp);
                if (cleared > 0 && deleteAndRetry(device, profile, null, commands)) {
                    result.setSuccess(true);
                    result.setMessage("注册表已满，清掉 " + cleared + " 台离线 ONT 后注册成功"
                            + deliverPostRegister(device, profile, fsp, template, row));
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

    // ============================ 注册后配置（TR069 模板 + 业务 VLAN 映射） ============================

    /** ONT 以太口 native-vlan 覆盖范围：eth 1..4（HGU 常见 4 口） */
    private static final int ONT_ETH_PORT_COUNT = 4;

    /**
     * 注册成功后的整批下发（一次 Telnet 会话，命令顺序与视图切换严格对应）：
     * <pre>
     * interface gpon 0/2
     * ont modify 0 &lt;ontId&gt; desc "型号"                       （有型号时）
     * ont tr069-server-config 0 &lt;ontId&gt; profile-id 10        （「下发 TR069 VLAN 及 ACS 地址」非空才发）
     * ont port native-vlan 0 &lt;ontId&gt; eth 1..4 vlan 104 priority 0（首组映射才发，光猫侧 VLAN）
     * quit
     * service-port vlan 46 gpon 0/2/0 ont &lt;ontId&gt; gemport 1 multi-service user-vlan 104 tag-transform translate
     * service-port vlan 46 gpon 0/2/0 ont &lt;ontId&gt; gemport 1 multi-service user-vlan 4011 tag-transform translate
     * service-port vlan 46 gpon 0/2/0 ont &lt;ontId&gt; gemport 1 multi-service user-vlan 4015 tag-transform translate
     * </pre>
     * 多组映射（每行一对）每组一条 service-port；native-vlan 只按首组。
     * 第 2 个参数是设备分配的 ONT ID（注册前未知，先查再发）；0 是 PON 端口号。
     */
    private String deliverPostRegister(OltDevice device, OltVendorProfile profile, int[] fsp,
                                       OltOntRegTemplate template, OltOntRegisterRequest.Row row) {
        String model = blankToNull(row.getModel());
        Integer tr069ProfileId = tr069ProfileIdOf(template);
        List<VlanPair> mappings = List.of();
        String badMapNote = "";
        try {
            if (profile.supportsServicePorts()) {
                mappings = serviceVlanMappingsOf(template);
            }
        } catch (IllegalArgumentException badMap) {
            // 模板里某一行写错只影响业务虚口，注释/TR069 模板仍要下发，不能把注册结果带崩
            Logs.info(Logs.Module.OLT, "业务 VLAN 映射配置无效，跳过业务虚口 template={}: {}",
                    template.getName(), badMap.getMessage());
            badMapNote = "；业务 VLAN 映射配置无效，未下发业务虚口：" + badMap.getMessage();
        }
        boolean needOntId = (model != null && profile.supportsOntDescription())
                || (tr069ProfileId != null && profile.supportsTr069ServerConfig())
                || !mappings.isEmpty();
        if (!needOntId) {
            return badMapNote;
        }
        // 刚 confirm 完设备把 ONT 加进列表有延迟，查不到就重试几轮再放弃
        Integer ontId = null;
        for (int attempt = 0; attempt < 3 && ontId == null; attempt++) {
            ontId = findOntIdOnPort(device, profile, fsp, row.getSn());
            if (ontId == null && attempt < 2) {
                try {
                    Thread.sleep(600L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (ontId == null) {
            Logs.info(Logs.Module.OLT, "ONT 注册后配置未下发：查不到设备分配的 ONT ID sn={} fsp={}",
                    row.getSn(), row.getFsp());
            return "；没查到设备分配的 ONT ID，注册后配置未下发（下轮自动补发会重试）";
        }
        // 每条命令单独成组下发：以前把所有命令塞进一个 sendConfig，任一条被设备拒绝
        // （某个 eth 口不存在、TR069 模板没预建、流已存在…）就整批抛异常中断，
        // 首组之后的 service-port 全部丢失 —— 这正是「注册上来只映射了一组」的根因。
        // PON 板视图命令各自带 interface/quit 成对，config 视图的 service-port 单独一条。
        String ponView = profile.ponViewCommand(fsp[0], fsp[1]);
        List<List<String>> batches = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (model != null && profile.supportsOntDescription()) {
            String desc = profile.ontDescriptionCommand(fsp[2], ontId, model);
            if (desc != null) {
                batches.add(List.of(ponView, desc, "quit"));
                labels.add("备注写型号 " + model);
            }
        }
        if (tr069ProfileId != null && profile.supportsTr069ServerConfig()) {
            batches.add(List.of(ponView,
                    profile.tr069ServerConfigCommand(fsp[2], ontId, tr069ProfileId), "quit"));
            labels.add("绑定 TR069 服务器模板 profile-id " + tr069ProfileId);
        }
        // 多组映射时以太口 native-vlan 只按第一组：一个 eth 口只能有一个 native VLAN，
        // 第一组（如 104 46 的 104）是 untagged 主业务，其余组是光猫自己打标的
        // tag 业务（4011/4015），靠 service-port 的 user-vlan 区分，不能互相覆盖
        VlanPair first = mappings.isEmpty() ? null : mappings.get(0);
        if (first != null && first.translated() && profile.supportsOntPortNativeVlan()) {
            for (int eth = 1; eth <= ONT_ETH_PORT_COUNT; eth++) {
                batches.add(List.of(ponView,
                        profile.ontPortNativeVlanCommand(fsp[2], ontId, eth, first.userVlan()), "quit"));
                labels.add("eth" + eth + " native-vlan " + first.userVlan());
            }
        }
        if (!mappings.isEmpty()) {
            Map<Integer, Integer> gemByVlan = gemVlanMapOf(device, profile, template);
            for (VlanPair pair : mappings) {
                int oltVlan = pair.serviceVlan();
                // GEM 承载的是光猫上行的 VLAN tag：线路模板映射表里的 VLAN 是光猫侧的，
                // 要拿光猫侧 VLAN 查 GEM（如 4011→gem 2），拿 OLT 侧 VLAN（46）查永远查不到，
                // 之前全被兜成同一个默认 gemport，这就是「没映射 GEM VLAN」的根因
                int gem = gemByVlan.getOrDefault(pair.userVlan(), DEFAULT_GEM_PORT);
                batches.add(List.of(profile.servicePortTranslateCommand(oltVlan, fsp[0], fsp[1], fsp[2],
                        ontId, gem, pair.userVlan())));
                labels.add("业务虚口 " + (pair.translated()
                        ? "光猫侧 " + pair.userVlan() + " → OLT 侧 " + oltVlan
                        : "VLAN " + oltVlan + " 透传"));
            }
        }
        int ok = 0;
        int fail = 0;
        StringBuilder done = new StringBuilder();
        for (int i = 0; i < batches.size(); i++) {
            List<String> batch = batches.get(i);
            String cmdText = String.join("；", batch);
            try {
                // tolerateExisting=true：流/配置已存在不算失败，也不阻断其它组
                telnetService.sendConfig(device, batch, true);
                ok++;
                done.append("；").append(labels.get(i));
                Logs.info(Logs.Module.OLT, "ONT 注册后配置下发成功", "sn", row.getSn(),
                        "fsp", row.getFsp(), "ontId", ontId, "步骤", labels.get(i), "命令", cmdText);
            } catch (Exception e) {
                fail++;
                // 每组独立日志：哪条失败、失败原因一眼可见，不用猜
                Logs.info(Logs.Module.OLT, "ONT 注册后配置下发失败", "sn", row.getSn(),
                        "fsp", row.getFsp(), "ontId", ontId, "步骤", labels.get(i),
                        "命令", cmdText, "原因", rootMessage(e));
            }
        }
        StringBuilder note = new StringBuilder();
        note.append("；注册后配置 ").append(ok).append(" 组成功");
        if (fail > 0) {
            note.append("、").append(fail).append(" 组失败（见下发日志）");
        }
        note.append(done).append(badMapNote);
        return note.toString();
    }

    /** 一轮最多补几台老 ONT 的业务虚口：补太多会一直占着设备的 Telnet 会话 */
    private static final int MAX_REPAIR_PER_ROUND = 10;

    /**
     * 「OLT 侧业务 VLAN 映射」一组解析结果。
     * userVlan = 光猫侧（native-vlan / user-vlan）；oltVlan = OLT 侧（service-port vlan），
     * null 表示透传（只填一个数时 userVlan 与 OLT 侧相同）。
     */
    private record VlanPair(int userVlan, Integer oltVlan) {
        int serviceVlan() {
            return oltVlan == null ? userVlan : oltVlan;
        }

        boolean translated() {
            return oltVlan != null;
        }
    }

    /**
     * 解析「下发 TR069 VLAN 及 ACS 地址」（如 46 10）：返回最后的整数作为 OLT 上预建的
     * tr069-server profile-id（命令 ont tr069-server-config &lt;port&gt; &lt;ontId&gt; profile-id N）。
     * 字段为空 / 没有数字 → null（不下发 TR069 模板，光猫拿不到 ACS 地址就不会来 Inform）。
     */
    private Integer tr069ProfileIdOf(OltOntRegTemplate template) {
        List<Integer> ints = intsOf(template.getTr069Vlan());
        return ints.isEmpty() ? null : ints.get(ints.size() - 1);
    }

    /**
     * 解析「OLT 侧业务 VLAN 映射」，支持多组：一行一组，也兼容分号/逗号混排。
     * <pre>
     * 104 46        → 光猫侧 104 → OLT 侧 46
     * 4011 46       → 光猫侧 4011 → OLT 侧 46
     * 4015 46       → 光猫侧 4015 → OLT 侧 46
     * </pre>
     * <ul>
     *   <li>整段为空 → 空列表：不映射业务（不下发 native-vlan、不下发 service-port）</li>
     *   <li>每行 2 个数 → 一组映射（空格/逗号/点分隔都行）；1 个数 → 该 VLAN 透传</li>
     *   <li>每行超过 2 个数 → 明确报错，避免把「104 46 4011 46」误当成一组</li>
     * </ul>
     */
    private List<VlanPair> serviceVlanMappingsOf(OltOntRegTemplate template) {
        String text = template.getServiceVlanMap();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<VlanPair> pairs = new ArrayList<>();
        for (String line : text.split("[\\r\\n;；]+")) {
            List<Integer> ints = intsOf(line);
            if (ints.isEmpty()) {
                continue;
            }
            if (ints.size() == 1) {
                pairs.add(new VlanPair(ints.get(0), null));
                continue;
            }
            if (ints.size() != 2) {
                throw new IllegalArgumentException("「OLT 侧业务 VLAN 映射」每行只能填 1 个数（透传 VLAN）"
                        + "或 2 个数（光猫侧 OLT 侧，如 104 46），这一行填了 " + ints.size()
                        + " 个：" + line.trim());
            }
            pairs.add(new VlanPair(ints.get(0), ints.get(1)));
        }
        return pairs;
    }

    /** 提取文本里的全部整数（空格/逗号/点等任意分隔都能容忍）：46 10 → [46, 10] */
    private static List<Integer> intsOf(String text) {
        List<Integer> ints = new ArrayList<>();
        if (text == null) {
            return ints;
        }
        for (String token : text.trim().split("[^0-9]+")) {
            if (token.isEmpty()) {
                continue;
            }
            try {
                ints.add(Integer.parseInt(token));
            } catch (NumberFormatException ignored) {
                // 超长数字串不是 VLAN/模板 ID，跳过
            }
        }
        return ints;
    }

    /** 给「早就注册过、业务虚口不全」的 ONT 按组补齐（缺哪组补哪组） */
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
                    OltOntRegTemplate template = match(templates, rowOf(ont, slot, port));
                    if (template == null) {
                        continue;
                    }
                    List<VlanPair> mappings;
                    try {
                        mappings = serviceVlanMappingsOf(template);
                    } catch (IllegalArgumentException badMap) {
                        Logs.info(Logs.Module.OLT, "补发业务虚口跳过（映射配置无效）id={} template={}: {}",
                                device.getId(), template.getName(), badMap.getMessage());
                        continue;
                    }
                    if (mappings.isEmpty()) {
                        continue;
                    }
                    String ontKey = portKey(0, slot, port, ont.getOntId());
                    Map<Integer, Integer> gemByVlan = gemVlanMapOf(device, profile, template);
                    int added = 0;
                    for (VlanPair pair : mappings) {
                        // 按「口#ONT#OLT侧VLAN#光猫侧VLAN」逐组判重：以前只看这台 ONT
                        // 有没有任意一条流，首组建了其余组就永远补不上
                        String flowKey = flowKey(ontKey, pair.serviceVlan(), pair.userVlan());
                        if (hasFlow.contains(flowKey)) {
                            continue;
                        }
                        // GEM 按光猫侧 VLAN 查线路模板映射（映射表记的是光猫上行的 VLAN tag）
                        int gem = gemByVlan.getOrDefault(pair.userVlan(), DEFAULT_GEM_PORT);
                        String command = profile.servicePortTranslateCommand(pair.serviceVlan(), 0, slot, port,
                                ont.getOntId(), gem, pair.userVlan());
                        try {
                            // 逐条独立下发：一条被拒不影响其余组
                            telnetService.sendConfig(device, List.of(command), true);
                            added++;
                            Logs.info(Logs.Module.OLT, "补发业务虚口成功 id={} 0/{}/{} ONT {} 命令 {}",
                                    device.getId(), slot, port, ont.getOntId(), command);
                        } catch (Exception e) {
                            Logs.info(Logs.Module.OLT, "补发业务虚口失败 id={} 0/{}/{} ONT {} 命令 {} 原因 {}",
                                    device.getId(), slot, port, ont.getOntId(), command, e.getMessage());
                        }
                    }
                    if (added > 0) {
                        repaired++;
                        Logs.info(Logs.Module.OLT, "ONT 自动注册：补齐业务虚口 {} 条 0/{}/{} ONT {}",
                                added, slot, port, ont.getOntId());
                    }
                }
            }
        }
        return repaired;
    }

    /**
     * 设备上现有业务虚口的「口#ONT#OLT侧VLAN#光猫侧VLAN」集合（逐组判重）。
     * 「光猫侧 VLAN」解析不出来时退化成 OLT 侧 VLAN（透传流 user-vlan 可能不显示）。
     * 查不到返回 null（避免误判成「都没有」而重复下发）。
     */
    private Set<String> existingServicePortKeys(OltDevice device) {
        try {
            List<String> outputs = telnetService.queryCommands(device, List.of("display service-port all"));
            String text = outputs == null || outputs.isEmpty() ? null : outputs.get(0);
            if (text == null) {
                return null;
            }
            Set<String> keys = new HashSet<>();
            for (OltServicePort sp : OltInfoParser.parseServicePorts(text)) {
                if (sp.getOntId() == null || sp.getVlanId() == null) {
                    continue;
                }
                String ontKey = portKey(sp.getFrameNo(), sp.getSlotNo(), sp.getPortNo(), sp.getOntId());
                int userVlan = sp.getUserVlan() == null ? sp.getVlanId() : sp.getUserVlan();
                keys.add(flowKey(ontKey, sp.getVlanId(), userVlan));
            }
            return keys;
        } catch (Exception e) {
            Logs.info(Logs.Module.OLT, "查现有业务虚口失败 id={}: {}", device.getId(), e.getMessage());
            return null;
        }
    }

    private String portKey(int frame, int slot, int port, int ontId) {
        return frame + "/" + slot + "/" + port + "#" + ontId;
    }

    private String flowKey(String ontKey, int oltVlan, int userVlan) {
        return ontKey + "#" + oltVlan + "#" + userVlan;
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
            Logs.info(Logs.Module.OLT, "读线路模板 GEM 映射失败 id={} profileId={}: {}",
                    device.getId(), template.getLineProfileId(), e.getMessage());
            return Map.of();
        }
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

    /** 删 ONT 前编出逐条 undo service-port（要排在 ont delete 之前）：带引用的 ont delete 会被设备顶回 */
    private List<String> servicePortDeleteCommands(OltDevice device, OltVendorProfile profile,
                                                   int[] fsp, int ontId) {
        if (!profile.supportsServicePorts()) {
            return List.of();
        }
        String table;
        try {
            table = telnetService.queryOntServicePorts(device, fsp[1], fsp[2], ontId);
        } catch (Exception e) {
            Logs.info(Logs.Module.OLT, "查 ONT 业务虚口失败 id={} ont={}: {}", device.getId(), ontId, e.getMessage());
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
            Logs.info(Logs.Module.OLT, "ONT 注册重试失败 id={}: {}", device.getId(), e.getMessage());
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
            Logs.info(Logs.Module.OLT, "删除离线 ONT 失败 id={}: {}", device.getId(), e.getMessage());
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
            Logs.info(Logs.Module.OLT, "查 ONT 明细失败 id={} slot={}: {}", device.getId(), slot, e.getMessage());
            return Map.of();
        }
    }

    /** 存盘连续失败次数：超过阈值暂停自动注册 */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger> saveFailStreaks
            = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int MAX_SAVE_FAIL_STREAK = 3;

    /** 到点把配置存盘：华为只写运行配置，不 save 掉电就回到注册前 */
    private void maybeSave(Long oltId, OltOntRegConfig config) {
        int saveSeconds = config.getSaveSeconds() == null ? 28800 : config.getSaveSeconds();
        LocalDateTime base = config.getLastSaveAt() != null ? config.getLastSaveAt() : config.getLastRegisterAt();
        if (base == null || Duration.between(base, LocalDateTime.now()).getSeconds() < saveSeconds) {
            return;
        }
        java.util.concurrent.atomic.AtomicInteger streak = saveFailStreaks.computeIfAbsent(oltId, k -> new java.util.concurrent.atomic.AtomicInteger());
        if (streak.get() >= MAX_SAVE_FAIL_STREAK) {
            Logs.error(Logs.Module.OLT, "ONT 自动注册存盘连续失败 {} 次，已暂停存盘 id={}，需人工检查设备", streak.get(), oltId);
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
            streak.set(0);
            Logs.info(Logs.Module.OLT, "自动注册后已存盘 id={}", oltId);
        } catch (Exception e) {
            int count = streak.incrementAndGet();
            Logs.warn(Logs.Module.OLT, "自动注册后存盘失败 id={} 第 {} 次: {}", oltId, count, e.getMessage());
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
        // 自动注册同一台光猫因同一原因失败时不重复记，否则记录表很快被刷满
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

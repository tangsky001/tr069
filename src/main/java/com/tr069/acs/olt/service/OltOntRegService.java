package com.tr069.acs.olt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.tr069.acs.olt.telnet.OltVendor;
import com.tr069.acs.olt.vendor.OltVendorProfile;
import com.tr069.acs.olt.vendor.OltVendorProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * ONT 注册：模板维护 + 手动一键注册 + 后台自动注册。
 *
 * <p>注册动作本身只有一条 {@code ont confirm}：拿自动发现表里那个 Number 直接确认，
 * ONT ID 由设备分配。真正要做的事在这之前——这台光猫该套哪条模板、它是不是已经在
 * 别的口上注册过、设备的注册表是不是满了。</p>
 *
 * <p>两个刻意的取舍：</p>
 * <ul>
 *   <li><strong>每次都留记录</strong>：自动注册是后台跑的，页面上看不见过程，
 *       成了的能看出套了哪个模板，没成的把设备回的原因带上。</li>
 *   <li><strong>失败不中断</strong>：一台注册失败（SN 重复、模板没匹配上）不影响其它台，
 *       结果逐条返回，页面逐条显示。</li>
 * </ul>
 */
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

    /**
     * 模板列表。
     *
     * @param onlyCurrent true 时只取「不限设备」和绑在这台 OLT 上的，页面「只显示当前 OLT 模板」用
     */
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

    /** 新增或更新一条模板；字段缺的按默认值补，不让页面上留空导致后面匹配不上 */
    public OltOntRegTemplate saveTemplate(Long oltId, OltOntRegTemplate form) {
        if (form == null || form.getName() == null || form.getName().isBlank()) {
            throw new IllegalArgumentException("模板名称不能为空");
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
            templateMapper.updateById(template);
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
        return fresh;
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

    /**
     * 注册一批待注册 ONT。
     *
     * @param source AUTO / MANUAL：自动注册受并发上限约束，且重复的失败不重复记记录
     */
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
            commands.addAll(commandsFor(profile, fsp, row.getId() == null ? 0 : row.getId(), template));
        }
        return commands;
    }

    /**
     * 后台自动跑一轮：扫设备上的待注册 ONT → 按优先级匹配模板 → 注册 → 到点存盘。
     *
     * @return 本轮成功注册的数量
     */
    public int autoRegister(Long oltId) {
        OltOntRegConfig config = config(oltId);
        if (!isEnabled(config.getEnabled())) {
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
        if (row.getId() == null) {
            result.setSuccess(false);
            result.setMessage("缺自动发现编号，没法 ont confirm；先在页面上重新查一次");
            return result;
        }
        String authProblem = authProblem(template, row);
        if (authProblem != null) {
            result.setSuccess(false);
            result.setMessage(authProblem);
            return result;
        }
        List<String> commands = commandsFor(profile, fsp, row.getId(), template);
        result.setCommand(String.join("；", commands));
        try {
            telnetService.sendConfig(device, commands);
            result.setSuccess(true);
            result.setMessage("已按模板「" + template.getName() + "」注册");
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
                        result.setMessage("已自动移机到 " + row.getFsp() + " 并注册（原 " + old.fsp + " 已删除）");
                        return result;
                    }
                }
            }
            if (isEnabled(config.getAutoDeleteOffline()) && looksFull(message)) {
                int cleared = deleteOffline(device, profile, fsp);
                if (cleared > 0 && deleteAndRetry(device, profile, null, commands)) {
                    result.setSuccess(true);
                    result.setMessage("注册表已满，清掉 " + cleared + " 台离线 ONT 后注册成功");
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
    private List<String> commandsFor(OltVendorProfile profile, int[] fsp, int autofindNo,
                                     OltOntRegTemplate template) {
        List<String> commands = new ArrayList<>();
        commands.add(profile.ponViewCommand(fsp[0], fsp[1]));
        commands.add(profile.ontConfirmCommand(fsp[2], autofindNo, template.getAuthMode(),
                template.getLineProfileId(), template.getSrvProfileId()));
        commands.add("quit");
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

    /**
     * 清掉某个 PON 口下离线的 ONT，给新光猫腾位置。
     *
     * <p>只删离线的：在线的一删就是当场断用户的网，宁可注册失败也不能那么干。</p>
     */
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

    /**
     * 按优先级挑第一条命中的模板：PON 类型 → 端口 → 型号，三项都对上才算命中。
     */
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

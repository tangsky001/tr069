package com.tr069.acs.worklist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.acs.service.CwmpRpcService;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.Worklist;
import com.tr069.acs.domain.entity.WorklistStepRecord;
import com.tr069.acs.exec.IntentDeliveryService;
import com.tr069.acs.exec.TaskFinishedEvent;
import com.tr069.acs.exec.entity.DeviceTask;
import com.tr069.acs.exec.model.DeliveryRequest;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.WorklistMapper;
import com.tr069.acs.mapper.WorklistStepMapper;
import com.tr069.acs.template.service.ConfigTemplateService;
import com.tr069.acs.worklist.tpl.TemplateVariables;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/** 管理端下发工单：建单、转下发、记录步骤、推进状态 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorklistDeliveryService {

    /** 工单转下发任务时在 DeviceTask.source 上打的标记，回写结果时按它识别 */
    public static final String SOURCE_WORKLIST = "worklist";

    private final WorklistMapper worklistMapper;
    private final WorklistStepMapper stepMapper;
    private final CpeDeviceMapper deviceMapper;
    private final ObjectMapper objectMapper;
    private final IntentDeliveryService intentDeliveryService;
    private final ConfigTemplateService configTemplateService;
    private final CwmpRpcService cwmpRpcService;
    private final DeviceIdentifierResolver identifierResolver;
    private final com.tr069.acs.biz.service.BizTemplateExecutionService bizExecutionService;

    /** 流水号自增段：同一秒内连开多单时靠它区分 */
    private static final AtomicLong DESC_SEQ = new AtomicLong();

    private static final DateTimeFormatter DESC_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 生成工单流水号（幂等键），格式 {@code ADMIN-{业务}-{时间戳}-{自增}-{随机}} */
    private String newWorklistDesc(String category) {
        String biz = category == null || category.isBlank() ? "biz" : category;
        return String.format(Locale.ROOT, "ADMIN-%s-%s-%d-%03d",
                biz, LocalDateTime.now().format(DESC_STAMP),
                DESC_SEQ.incrementAndGet(), ThreadLocalRandom.current().nextInt(1000));
    }

    /** 创建下发工单（老调用入口，不带扩展字段） */
    public Worklist create(String sn, String category, String worklistDesc,
                           Map<String, String> values, String username, Long templateId) {
        return create(sn, category, worklistDesc, values, username, templateId, null);
    }

    /**
     * 创建下发工单。
     *
     * 建单不立即下发：先按 SN / OUI-SN / MAC / LOID 在库里找设备——
     * 找不到就一直留在「等待执行」（standby），设备登记后由调度自动跟上；
     * 找到就把房间号同步进设备台账，设备在线则进入「预约中」（reserve），
     * 满 10 秒由调度执行下发，离线则等上线再预约。
     */
    public Worklist create(String sn, String category, String worklistDesc,
                           Map<String, String> values, String username, Long templateId,
                           OrderExtras ext) {
        if (sn == null || sn.isBlank()) {
            throw new IllegalArgumentException("缺少设备序列号");
        }
        if (worklistDesc == null || worklistDesc.isBlank()) {
            // 页面上直接点提交时调用方不给流水号，这里按需生成一个——
            // 流水号是幂等键，交给前端生成的话它只能用 Math.random，
            // 撞号时两单合成一单，后一单被幂等掉，运维以为开成功了其实没发
            worklistDesc = newWorklistDesc(category);
        }

        Worklist exist = worklistMapper.selectOne(new LambdaQueryWrapper<Worklist>()
                .eq(Worklist::getWorklistDesc, worklistDesc)
                .last("LIMIT 1"));
        if (exist != null) {
            log.info("工单已存在，本次提交忽略: desc={}, id={}", worklistDesc, exist.getId());
            return exist;
        }

        // 设备标识解析：找不到不报错，工单留在「等待执行」，设备登记后调度自动跟上
        CpeDevice device = identifierResolver.resolve(sn);
        if (device != null) {
            // 房间号同步设备台账：建单时运维填的房间号就是这台设备现在的位置
            syncRoomNo(device, ext == null ? null : ext.roomNo());
        }

        // 变量兜底：房间号取设备登记的备注，口令没填用约定默认值。
        // 单台与批量下发都在这里补齐，调用方一个变量都不传也能渲染出完整计划
        Map<String, String> filled = withDefaults(category, device, values);
        String bizType = ext == null || ext.bizType() == null || ext.bizType().isBlank()
                ? "normal" : ext.bizType();
        if (ext != null && ext.bizTemplateId() != null) {
            // 业务模板整包：调度按这个 id 找到模板后走整包通道
            filled.put("bizTemplateId", String.valueOf(ext.bizTemplateId()));
        }

        LocalDateTime now = LocalDateTime.now();
        boolean online = device != null && CpeDevice.onlineOf(device.getStatus());
        Worklist w = new Worklist();
        w.setWorklistDesc(worklistDesc);
        w.setWorklistName(worklistNameOf(category, ext));
        w.setWorklistType("logic");
        w.setSn(device != null ? device.getSn() : sn.trim());
        w.setIsp(device == null ? null : device.getIsp());
        w.setDomain(device == null ? null : device.getDeviceType());
        w.setStatus(statusAtCreate(ext, device, online));
        w.setBizType(bizType);
        w.setGroupId(ext == null ? null : ext.groupId());
        w.setRoomNo(ext == null ? null : ext.roomNo());
        w.setExtra(ext == null ? null : ext.extra());
        w.setRemark(ext == null ? null : ext.remark());
        w.setActive(ext == null || ext.active() == null || ext.active() ? 1 : 0);
        // 口令值不落日志，只记录填了哪些变量
        w.setParameters(toJson(filled));
        w.setUsername(username);
        w.setRollbackFlag(0);
        w.setTimeInit(now);
        if ("reserve".equals(w.getStatus())) {
            // 在线设备从建单这一刻起算 10 秒
            w.setTimeReserve(now);
        }
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        worklistMapper.insert(w);
        log.info("创建下发工单: id={}, sn={}, category={}, bizType={}, status={}, 变量={}", w.getId(),
                w.getSn(), category, bizType, w.getStatus(), List.copyOf(filled.keySet()));
        return w;
    }

    /** 业务模板整包工单的展示名，其余工单用业务类型 */
    private String worklistNameOf(String category, OrderExtras ext) {
        if (ext != null && ext.bizTemplateId() != null) {
            return "整包下发 · " + (ext.bizTemplateName() == null ? "业务模板" : ext.bizTemplateName());
        }
        return category;
    }

    /** 建单时的状态：未激活 build / 找不到设备或离线 standby / 在线 reserve（10 秒后下发） */
    private String statusAtCreate(OrderExtras ext, CpeDevice device, boolean online) {
        if (ext != null && ext.active() != null && !ext.active()) {
            return "build";
        }
        if (device == null || !online) {
            return "standby";
        }
        return "reserve";
    }

    /** 房间号同步设备台账：台账 cpe_device.room_no 是权威，工单上只存录入值 */
    private void syncRoomNo(CpeDevice device, String roomNo) {
        if (roomNo == null || roomNo.isBlank() || roomNo.trim().equals(device.getRoomNo())) {
            return;
        }
        CpeDevice patch = new CpeDevice();
        patch.setId(device.getId());
        patch.setRoomNo(roomNo.trim());
        deviceMapper.updateById(patch);
        // 内存对象同步改掉，变量兜底（%room%）取的就是新房间号
        device.setRoomNo(roomNo.trim());
        log.info("工单同步设备房间号: sn={}, roomNo={}", device.getSn(), roomNo.trim());
    }

    // ==================== 待执行工单调度 ====================

    /**
     * 扫描等待执行 / 预约中的工单：
     * 设备已登记且在线 → 预约满 10 秒执行下发；设备未登记 → 一直等待。
     * 状态先抢占成 sending 再下发，防止下一轮扫描重复执行。
     */
    @Scheduled(fixedDelay = 10000, initialDelay = 5000)
    public void deliverPendingWorklists() {
        List<Worklist> pending;
        try {
            pending = worklistMapper.selectList(new LambdaQueryWrapper<Worklist>()
                    .in(Worklist::getStatus, "standby", "reserve")
                    .orderByAsc(Worklist::getId)
                    .last("limit 200"));
        } catch (Exception e) {
            log.warn("扫描待执行工单失败: {}", e.getMessage());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Worklist w : pending) {
            try {
                processPending(w, now);
            } catch (Exception e) {
                log.warn("处理待执行工单失败: id={}, err={}", w.getId(), e.getMessage());
            }
        }
    }

    private void processPending(Worklist w, LocalDateTime now) {
        CpeDevice device = identifierResolver.resolve(w.getSn());
        if (device == null) {
            // 设备还没登记进台账，继续等待
            return;
        }
        syncRoomNo(device, w.getRoomNo());
        boolean online = CpeDevice.onlineOf(device.getStatus());
        if ("standby".equals(w.getStatus())) {
            if (online) {
                // 设备在线，从现在起预约 10 秒后下发
                Worklist patch = new Worklist();
                patch.setId(w.getId());
                patch.setStatus("reserve");
                patch.setTimeReserve(now);
                patch.setUpdatedAt(now);
                worklistMapper.updateById(patch);
                log.info("工单预约下发: id={}, sn={}, 10 秒后执行", w.getId(), device.getSn());
            }
            return;
        }
        // reserve：预约中
        if (!online) {
            // 掉线了，退回等待，上线后重新预约
            Worklist patch = new Worklist();
            patch.setId(w.getId());
            patch.setStatus("standby");
            patch.setUpdatedAt(now);
            worklistMapper.updateById(patch);
            return;
        }
        LocalDateTime reserved = w.getTimeReserve() == null ? now.minusSeconds(60) : w.getTimeReserve();
        if (reserved.plusSeconds(10).isAfter(now)) {
            return;
        }
        // 抢占：先改成 sending，防止下一轮扫描重复执行
        Worklist claim = new Worklist();
        claim.setId(w.getId());
        claim.setStatus("sending");
        claim.setTimeExecStart(now);
        claim.setUpdatedAt(now);
        if (worklistMapper.updateById(claim) == 0) {
            return;
        }
        w.setStatus("sending");
        Long bizTemplateId = bizTemplateIdOf(w);
        if (bizTemplateId != null) {
            // 业务模板整包：复用已登记的工单记录，不再新开一张
            bizExecutionService.executeForWorklist(bizTemplateId, device.getSn(),
                    new HashMap<>(), w.getUsername(), w.getRoomNo(), w.getId());
            return;
        }
        Map<String, String> values = readValues(w);
        values.remove("bizTemplateId");
        // 房间号以台账为准重算 %room%：建单时设备可能还没登记进台账，
        // 兜底值 0000 已经写进工单参数，不重算的话 WiFi SSID / SIP 账号会按 0000 下发。
        // 只覆盖兜底值与空值，运维手填的房间名（非 0000）不动
        String storedRoom = values.get(TemplateVariables.ROOM);
        if (storedRoom == null || storedRoom.isBlank()) {
            storedRoom = values.get("room");
        }
        if (storedRoom == null || storedRoom.isBlank()
                || TemplateVariables.DEFAULT_ROOM.equals(storedRoom.trim())) {
            String room = device.getRoomNo();
            values.remove(TemplateVariables.ROOM);
            values.remove("room");
            values.put(TemplateVariables.ROOM,
                    room == null || room.isBlank() ? TemplateVariables.DEFAULT_ROOM : room.trim());
        }
        if ("reset".equals(w.getBizType()) || "reboot".equals(w.getBizType())) {
            triggerRpcDelivery(w, w.getBizType());
        } else {
            triggerDelivery(w, values, null);
        }
    }

    /** 工单参数里的业务模板 id，普通工单没有 */
    private Long bizTemplateIdOf(Worklist w) {
        String id = readValues(w).get("bizTemplateId");
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(id.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 复位 / 重启工单：不走模板，直接经 CWMP 通道发一条 RPC */
    private void triggerRpcDelivery(Worklist w, String bizType) {
        boolean reboot = "reboot".equals(bizType);
        String rpc = reboot ? "Reboot" : "FactoryReset";
        String comment = reboot ? "重启设备" : "恢复出厂";
        String commandKey = "WL-" + w.getId();
        WorklistStepRecord step = addStep(w.getId(), 0, comment, rpc,
                Map.of("CommandKey", commandKey));
        try {
            java.util.concurrent.CompletableFuture<SoapMessage> future = reboot
                    ? cwmpRpcService.reboot(w.getSn(), commandKey)
                    : cwmpRpcService.submit(w.getSn(), rpc, Map.of("CommandKey", commandKey));
            LocalDateTime now = LocalDateTime.now();
            w.setStatus("sending");
            w.setTimeExecStart(now);
            w.setUpdatedAt(now);
            worklistMapper.updateById(w);
            long startMs = System.currentTimeMillis();
            future.whenComplete((msg, err) -> finishRpcStep(w.getId(), step, msg, err,
                    System.currentTimeMillis() - startMs));
            log.info("复位/重启工单已转 RPC: id={}, sn={}, rpc={}", w.getId(), w.getSn(), rpc);
        } catch (Exception e) {
            fail(w, e.getMessage());
        }
    }

    /** RPC 回应后落步骤与工单终态 */
    private void finishRpcStep(Long worklistId, WorklistStepRecord step,
                               SoapMessage response, Throwable error, long durationMs) {
        try {
            step.setStatus(error == null ? "success" : "fail");
            step.setDurationMs(durationMs);
            step.setErrorMsg(error == null ? null : error.getMessage());
            stepMapper.updateById(step);
        } catch (Exception e) {
            log.warn("复位/重启步骤回写失败: worklistId={}, err={}", worklistId, e.getMessage());
        }
        Worklist w = worklistMapper.selectById(worklistId);
        if (w == null || "success".equals(w.getStatus()) || "failed".equals(w.getStatus())) {
            return;
        }
        if (error == null) {
            w.setStatus("success");
            w.setResult(null);
        } else {
            w.setStatus("failed");
            w.setResult(error.getMessage());
        }
        w.setTimeExecFinish(LocalDateTime.now());
        w.setUpdatedAt(LocalDateTime.now());
        worklistMapper.updateById(w);
        log.info("复位/重启工单结束: id={}, status={}", worklistId, w.getStatus());
    }

    /** 建单扩展字段：管理端「新增工单」弹窗的附加信息 */
    public record OrderExtras(String bizType, Long groupId, String roomNo,
                              String extra, String remark, Boolean active,
                              Long bizTemplateId, String bizTemplateName) {
    }

    /** 补上调用方没填的业务变量，规则由 {@link TemplateVariables} 统一定义 */
    private Map<String, String> withDefaults(String category, CpeDevice device,
                                             Map<String, String> values) {
        Map<String, String> filled = new HashMap<>();
        TemplateVariables.applyDefaults(category,
                        values == null ? null : new HashMap<>(values),
                        device == null ? null : device.getRoomNo())
                .forEach((k, v) -> filled.put(k, v == null ? null : String.valueOf(v)));
        return filled;
    }

    // ==================== 删改 ====================

    /** 工单修改请求：sn 只有未执行的单允许改，其余字段随时可改 */
    public record OrderUpdate(String sn, Long groupId, String roomNo, String worklistType,
                              String extra, String remark, Boolean active) {
    }

    /** 执行中的状态，改删都挡掉 */
    private boolean inFlight(String status) {
        return "sending".equals(status) || "running".equals(status) || "bind".equals(status);
    }

    /** 还没跑的状态：设备标识还能改 */
    private boolean pending(String status) {
        return "build".equals(status) || "standby".equals(status) || "reserve".equals(status);
    }

    /**
     * 修改工单。
     *
     * 房间号改动同步设备台账（与建单同口径）；设备标识改动只在 build/standby/reserve
     * 阶段允许，改完重新归一 SN 并刷新运营商/型号；未激活单改回激活后进入「等待执行」，
     * 由调度自动跟上。
     */
    public Worklist update(Long id, OrderUpdate cmd) {
        Worklist w = worklistMapper.selectById(id);
        if (w == null) {
            throw new IllegalArgumentException("工单不存在: " + id);
        }
        if (inFlight(w.getStatus())) {
            throw new IllegalArgumentException("工单正在执行，暂不能修改");
        }
        // 设备标识：改了就重新解析归一
        if (cmd.sn() != null && !cmd.sn().isBlank() && !cmd.sn().trim().equals(w.getSn())) {
            if (!pending(w.getStatus())) {
                throw new IllegalArgumentException("工单已执行，设备标识不能再改");
            }
            CpeDevice device = identifierResolver.resolve(cmd.sn);
            w.setSn(device != null ? device.getSn() : cmd.sn.trim());
            if (device != null) {
                w.setIsp(device.getIsp());
                w.setDomain(device.getDeviceType());
            }
        }
        // 房间号：工单与台账一起改
        if (cmd.roomNo() != null) {
            w.setRoomNo(cmd.roomNo().isBlank() ? null : cmd.roomNo().trim());
        }
        CpeDevice device = identifierResolver.resolve(w.getSn());
        if (device != null && w.getRoomNo() != null && !w.getRoomNo().isBlank()) {
            syncRoomNo(device, w.getRoomNo());
        }
        if (cmd.groupId() != null) {
            w.setGroupId(cmd.groupId() <= 0 ? null : cmd.groupId());
        }
        if (cmd.worklistType() != null && !cmd.worklistType().isBlank()
                && (pending(w.getStatus()) || w.getBizType() == null)) {
            // 工单类型只在未执行阶段允许切换，避免已下发的复位单半路变重启
            w.setBizType(cmd.worklistType().trim());
        }
        if (cmd.extra() != null) {
            w.setExtra(cmd.extra().isBlank() ? null : cmd.extra().trim());
        }
        if (cmd.remark() != null) {
            w.setRemark(cmd.remark().isBlank() ? null : cmd.remark().trim());
        }
        if (cmd.active() != null) {
            w.setActive(cmd.active() ? 1 : 0);
            // 未激活单改回激活：进入等待执行，调度自动跟上
            if (cmd.active() && "build".equals(w.getStatus())) {
                w.setStatus("standby");
            }
        }
        w.setUpdatedAt(LocalDateTime.now());
        worklistMapper.updateById(w);
        Worklist.decorate(w);
        log.info("修改工单: id={}, sn={}, status={}, roomNo={}", w.getId(), w.getSn(),
                w.getStatus(), w.getRoomNo());
        return w;
    }

    /** 删除工单：执行中的单拒删；逻辑删除，列表与调度都不再看见 */
    public void delete(Long id) {
        Worklist w = worklistMapper.selectById(id);
        if (w == null) {
            return;
        }
        if (inFlight(w.getStatus())) {
            throw new IllegalArgumentException("工单正在执行，暂不能删除");
        }
        worklistMapper.deleteById(id);
        log.info("删除工单: id={}, sn={}, desc={}", id, w.getSn(), w.getWorklistDesc());
    }

    // ==================== 工单 → 下发 ====================

    /** 工单落库后立刻转成下发任务 */
    private void triggerDelivery(Worklist w, Map<String, String> values, Long templateId) {
        Long tpl = templateId != null ? templateId : defaultTemplateId(w.getWorklistName());
        Map<String, Object> vars = values == null ? new HashMap<>() : new HashMap<>(values);
        try {
            DeviceTask task = intentDeliveryService.deliver(new DeliveryRequest(
                    "WL-" + w.getId(), w.getSn(), w.getWorklistName(), tpl, null,
                    vars, true, SOURCE_WORKLIST, w.getUsername()));
            if (DeviceTask.STATUS_REJECTED.equals(task.getStatus())) {
                fail(w, task.getErrorMsg());
            } else {
                w.setStatus("sending");
                w.setUpdatedAt(LocalDateTime.now());
                worklistMapper.updateById(w);
                log.info("工单已转下发任务: worklistId={}, taskId={}, status={}",
                        w.getId(), task.getId(), task.getStatus());
            }
        } catch (Exception e) {
            fail(w, e.getMessage());
        }
    }

    /** 未指定模板时按业务类型取第一条，让前端不开模板选择也能下发 */
    private Long defaultTemplateId(String category) {
        try {
            List<Map<String, Object>> templates = configTemplateService.list(category, null);
            if (templates.isEmpty()) {
                return null;
            }
            Object id = templates.get(0).get("id");
            return id == null ? null : Long.valueOf(String.valueOf(id));
        } catch (Exception e) {
            log.warn("按业务类型查找模板失败: category={}, err={}", category, e.getMessage());
            return null;
        }
    }

    /** 下发任务结束回写工单终态：只认 {@link #SOURCE_WORKLIST} 来源，OpenAPI 的单不归工单管 */
    @EventListener
    public void onTaskFinished(TaskFinishedEvent event) {
        DeviceTask task = event.getTask();
        if (task == null || !SOURCE_WORKLIST.equals(task.getSource())
                || task.getTaskNo() == null || !task.getTaskNo().startsWith("WL-")) {
            return;
        }
        try {
            Worklist w = worklistMapper.selectById(Long.valueOf(task.getTaskNo().substring(3)));
            if (w == null || "success".equals(w.getStatus()) || "failed".equals(w.getStatus())) {
                return;
            }
            if (DeviceTask.STATUS_SUCCESS.equals(task.getStatus())) {
                w.setStatus("success");
                w.setResult(task.getErrorMsg());
            } else {
                w.setStatus("failed");
                w.setResult(task.getErrorMsg());
            }
            w.setTimeExecFinish(LocalDateTime.now());
            w.setUpdatedAt(LocalDateTime.now());
            worklistMapper.updateById(w);
            log.info("工单下发结束: id={}, status={}, err={}", w.getId(), w.getStatus(), task.getErrorMsg());
        } catch (Exception e) {
            log.warn("工单状态回写失败: taskNo={}, err={}", task.getTaskNo(), e.getMessage());
        }
    }

    private void fail(Worklist w, String reason) {
        w.setStatus("failed");
        w.setResult(reason);
        w.setTimeExecFinish(LocalDateTime.now());
        worklistMapper.updateById(w);
        log.warn("工单转下发失败: id={}, err={}", w.getId(), reason);
    }

    /** 记录工单的一条执行步骤。已有同序号步骤则覆盖，避免重试灌入重复步骤 */
    public WorklistStepRecord addStep(Long worklistId, int stepIndex, String comment,
                                      String rpc, Object requestArgs) {
        stepMapper.delete(new LambdaQueryWrapper<WorklistStepRecord>()
                .eq(WorklistStepRecord::getWorklistId, worklistId)
                .eq(WorklistStepRecord::getStepIndex, stepIndex));

        WorklistStepRecord step = new WorklistStepRecord();
        step.setWorklistId(worklistId);
        step.setStepIndex(stepIndex);
        step.setComment(comment);
        step.setRpc(rpc);
        step.setRequestArgs(toJson(requestArgs));
        step.setStatus("init");
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);
        return step;
    }

    /** 推进工单状态 */
    public void moveTo(Long worklistId, String status) {
        Worklist patch = new Worklist();
        patch.setId(worklistId);
        patch.setStatus(status);
        patch.setUpdatedAt(LocalDateTime.now());
        worklistMapper.updateById(patch);
    }

    /** 读回工单里存的参数值 */
    public Map<String, String> readValues(Worklist worklist) {
        if (worklist == null || worklist.getParameters() == null || worklist.getParameters().isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(worklist.getParameters(),
                    new TypeReference<Map<String, String>>() {
                    });
        } catch (Exception e) {
            log.warn("工单参数解析失败，按空处理: id={}", worklist.getId(), e);
            return new HashMap<>();
        }
    }

    /** 供前端生成表单：某业务类型要填哪些变量 */
    public static String normalize(String category) {
        return category == null ? "" : category.toUpperCase(Locale.ROOT);
    }

    private String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("参数序列化失败，存空对象", e);
            return "{}";
        }
    }
}

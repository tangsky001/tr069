package com.PilotACS.worklist.service;

import com.PilotACS.biz.mapper.BizTemplateExecutionMapper;
import com.PilotACS.biz.service.BizTemplateExecutionService;
import com.PilotACS.biz.service.ConfigTemplateResolver;
import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.cpe.service.TaskService;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.group.GroupService;
import com.PilotACS.template.exec.IntentDeliveryService;
import com.PilotACS.worklist.entity.Worklist;
import com.PilotACS.worklist.entity.WorklistStepRecord;
import com.PilotACS.worklist.mapper.WorklistMapper;
import com.PilotACS.worklist.mapper.WorklistStepMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 管理端下发工单：建单、预约调度、转下发、记录步骤、推进状态。
 *
 * <p>状态流转：build（未激活）→ standby（设备未登记/离线）→ reserve（在线，预约 10 秒）
→ sending（执行中）→ success / fail。调度扫描由 {@code @Scheduled} 驱动，
实际下发在本服务的工作线程串行执行（意图下发是同步阻塞的）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorklistDeliveryService {

    private final WorklistMapper worklistMapper;
    private final WorklistStepMapper stepMapper;
    private final CpeStore store;
    private final ObjectMapper objectMapper;
    private final IntentDeliveryService intentDeliveryService;
    private final ConfigTemplateResolver templateResolver;
    private final BizTemplateExecutionService bizExecutionService;
    private final BizTemplateExecutionMapper bizExecutionMapper;
    private final TaskService taskService;
    private final GroupService groupService;

    /** 流水号自增段：同一秒内连开多单时靠它区分 */
    private static final AtomicLong DESC_SEQ = new AtomicLong();

    private static final DateTimeFormatter DESC_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 整包下发轮询执行记录的最长等待：半小时足够 7 个成员串行跑完 */
    private static final long BIZ_EXEC_TIMEOUT_MS = 30 * 60_000L;

    /** 生成工单流水号（幂等键），格式 {@code ADMIN-{业务}-{时间戳}-{自增}-{随机}} */
    private String newWorklistDesc(String category) {
        String biz = category == null || category.isBlank() ? "biz" : category;
        return String.format(Locale.ROOT, "ADMIN-%s-%s-%d-%03d",
                biz, LocalDateTime.now().format(DESC_STAMP),
                DESC_SEQ.incrementAndGet(), ThreadLocalRandom.current().nextInt(1000));
    }

    /**
     * 创建下发工单。
     *
     * <p>建单不立即下发：按 SN 找台账——找不到就留在「等待执行」（standby），设备登记后由调度跟上；
     * 找到则同步房间号进台账，设备在线进入「预约中」（reserve），满 10 秒由调度执行下发。</p>
     */
    public Worklist create(String sn, String category, String worklistDesc,
                           Map<String, String> values, String username, Long templateId,
                           OrderExtras ext) {
        if (sn == null || sn.isBlank()) {
            throw new BusinessException("缺少设备序列号");
        }
        if (worklistDesc == null || worklistDesc.isBlank()) {
            // 页面上直接点提交时调用方不给流水号；流水号是幂等键，
            // 交给前端生成的话撞号时两单合成一单，后一单被幂等掉
            worklistDesc = newWorklistDesc(category);
        }

        Worklist exist = worklistMapper.selectOne(new LambdaQueryWrapper<Worklist>()
                .eq(Worklist::getWorklistDesc, worklistDesc)
                .last("LIMIT 1"));
        if (exist != null) {
            log.info("工单已存在，本次提交忽略: desc={}, id={}", worklistDesc, exist.getId());
            return Worklist.decorate(exist);
        }

        Map<String, Object> device = findDevice(sn);
        if (device != null && ext != null) {
            // 第一步：房间号/备注先落设备台账——下发替换 %room% 与页面备注列读的都是台账值
            String room = ext.roomNo() == null ? "" : ext.roomNo().trim();
            String remark = ext.remark() == null ? "" : ext.remark().trim();
            if (!room.isBlank() || !remark.isBlank()) {
                syncDeviceProfile(sn, room, remark);
            }
            // 第二步：选了分组就把设备移进该分组（归属关系落分组表，工单只存 group_id 备查）
            if (ext.groupId() != null && ext.groupId() > 0) {
                String moveError = groupService.moveMember("G:" + ext.groupId(), "ONU", sn.trim(), username);
                if (moveError != null) {
                    throw new BusinessException("设备移动到分组失败: " + moveError);
                }
                log.info("工单建单移动分组: sn={}, groupId={}", sn, ext.groupId());
            }
        }
        // 第三步：下发由调度在设备在线后自动跟上（预约 10 秒执行）

        boolean bizOrder = ext != null && ext.bizTemplateId() != null;
        // 变量兜底：空串键移除（下发时用模板默认值）
        Map<String, String> filled = WorklistVariables.applyDefaults(category, values);
        if (bizOrder) {
            filled.put("bizTemplateId", String.valueOf(ext.bizTemplateId()));
        }

        LocalDateTime now = LocalDateTime.now();
        boolean online = device != null && "online".equals(device.get("status"));
        Worklist w = new Worklist();
        w.setWorklistDesc(worklistDesc);
        w.setWorklistName(bizOrder
                ? "整包下发 · " + (ext.bizTemplateName() == null ? "业务模板" : ext.bizTemplateName())
                : category);
        w.setWorklistType("logic");
        w.setSn(sn.trim());
        if (device != null) {
            w.setIsp((String) device.get("isp"));
            w.setDomain((String) device.get("device_type"));
        }
        w.setStatus(statusAtCreate(ext, online));
        w.setBizType(ext == null || ext.bizType() == null || ext.bizType().isBlank()
                ? "normal" : ext.bizType());
        if (ext != null) {
            w.setGroupId(ext.groupId());
            w.setRoomNo(ext.roomNo());
            w.setExtra(ext.extra());
            w.setRemark(ext.remark());
        }
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
                w.getSn(), category, w.getBizType(), w.getStatus(), filled.keySet());
        return Worklist.decorate(w);
    }

    /** 建单时的状态：未激活 build / 找不到设备或离线 standby / 在线 reserve（10 秒后下发） */
    private String statusAtCreate(OrderExtras ext, boolean online) {
        if (ext != null && ext.active() != null && !ext.active()) {
            return "build";
        }
        return online ? "reserve" : "standby";
    }

    /** 房间号/备注同步设备台账：下发替换 %room% 与页面备注列读的都是台账值 */
    private void syncDeviceProfile(String sn, String roomNo, String remark) {
        try {
            Map<String, Object> cols = new LinkedHashMap<>();
            if (roomNo != null && !roomNo.isBlank()) {
                cols.put("room_no", roomNo);
            }
            if (remark != null && !remark.isBlank()) {
                cols.put("remark", remark);
            }
            store.updateDeviceColumns(sn, cols);
        } catch (Exception e) {
            log.warn("工单同步设备房间号/备注失败: sn={}, err={}", sn, e.getMessage());
        }
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
                    .eq(Worklist::getActive, 1)
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
        Map<String, Object> device = findDevice(w.getSn());
        if (device == null) {
            // 设备还没登记进台账，继续等待
            return;
        }
        boolean online = "online".equals(device.get("status"));
        if ("standby".equals(w.getStatus())) {
            if (online) {
                // 设备在线，从现在起预约 10 秒后下发
                Worklist patch = new Worklist();
                patch.setId(w.getId());
                patch.setStatus("reserve");
                patch.setTimeReserve(now);
                patch.setUpdatedAt(now);
                worklistMapper.updateById(patch);
                log.info("工单预约下发: id={}, sn={}, 10 秒后执行", w.getId(), w.getSn());
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
        if (bizTemplateId != null || "reset".equals(w.getBizType()) || "reboot".equals(w.getBizType())) {
            // 复位/重启/整包都在调度线程直接跑（整包内部异步，复位走任务管线）
            executeClaimed(w, bizTemplateId, device);
        } else {
            // 意图下发同步阻塞，交给工作线程，别把 10 秒调度拖死
            worker.execute(() -> {
                try {
                    executeClaimed(w, null, device);
                } catch (Exception e) {
                    log.error("工单下发异常: id={}", w.getId(), e);
                    fail(w, e.getMessage());
                }
            });
        }
    }

    /** 单线程工作池：意图下发同步阻塞（逐条 RPC 等待），串行足够 */
    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "worklist-delivery");
                t.setDaemon(true);
                return t;
            });

    /** 抢占到 sending 后的实际执行入口 */
    private void executeClaimed(Worklist w, Long bizTemplateId, Map<String, Object> device) {
        if (bizTemplateId != null) {
            runBizDelivery(w, bizTemplateId);
        } else if ("reset".equals(w.getBizType()) || "reboot".equals(w.getBizType())) {
            runRpcDelivery(w, "reboot".equals(w.getBizType()));
        } else {
            runTemplateDelivery(w, device);
        }
    }

    // ==================== 三类下发 ====================

    /** 配置模板意图下发：变量覆盖字段 → 占位替换 → 逐条 RPC */
    private void runTemplateDelivery(Worklist w, Map<String, Object> device) {
        Map<String, String> values = readValues(w);
        values.remove("bizTemplateId");
        Long tpl = defaultTemplateId(w.getWorklistName());
        if (tpl == null) {
            fail(w, "没有可用的配置模板: " + w.getWorklistName());
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>(templateResolver.fieldsOf(tpl));
        // 工单变量按同键覆盖模板字段（口令没填就留模板默认值）
        values.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                fields.put(k, v);
            }
        });
        // 房间号以台账为准重算 %room%：建单时设备可能还没登记，兜底值已写进参数，
        // 不重算的话 WiFi SSID / SIP 账号会按 0000 下发
        String room = device == null ? null : (String) device.get("room_no");
        String desc = device == null ? null : (String) device.get("remark");
        fields.replaceAll((k, v) -> v instanceof String s ? WorklistVariables.resolve(s, room, desc) : v);
        boolean incremental = templateResolver.incrementalOf(tpl);

        WorklistStepRecord step = addStep(w.getId(), 0,
                "意图下发: " + w.getWorklistName(), "IntentDelivery", fields);
        long startMs = System.currentTimeMillis();
        Map<String, Object> result = intentDeliveryService.deliver(
                w.getSn(), w.getWorklistName(), fields, incremental, w.getUsername());
        long duration = System.currentTimeMillis() - startMs;
        boolean ok = Boolean.TRUE.equals(result.get("success"));
        finishStep(step, ok, duration, toJson(result));
        if (ok) {
            succeed(w, null);
        } else {
            fail(w, String.valueOf(result.get("error")));
        }
    }

    /** 业务模板整包下发：复用业务模板执行器，轮询执行记录到终态后回写工单 */
    private void runBizDelivery(Worklist w, Long bizTemplateId) {
        WorklistStepRecord step = addStep(w.getId(), 0,
                "整包下发 · 业务模板", "BizTemplate", Map.of("templateId", bizTemplateId));
        try {
            Long execId = bizExecutionService.execute(bizTemplateId, w.getSn(),
                    new HashMap<>(), w.getRoomNo(), w.getUsername());
            long deadline = System.currentTimeMillis() + BIZ_EXEC_TIMEOUT_MS;
            Map<String, Object> exec = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Map<String, Object> cur = bizExecutionService.execution(execId);
                    String status = String.valueOf(cur.get("status"));
                    if (!"running".equals(status)) {
                        exec = cur;
                        break;
                    }
                } catch (Exception e) {
                    // 执行记录被清了之类的异常，按失败收口
                    break;
                }
                Thread.sleep(5000);
            }
            if (exec == null) {
                fail(w, "整包下发超时未完成");
                step.setStatus("fail");
                step.setErrorMsg("整包下发超时未完成");
                stepMapper.updateById(step);
                return;
            }
            String status = String.valueOf(exec.get("status"));
            boolean ok = "success".equals(status);
            String summary = "成员 成功" + exec.get("successCount") + "/失败" + exec.get("failCount")
                    + "，执行记录 " + execId;
            finishStep(step, ok, null, toJson(exec.get("results")));
            if (ok) {
                succeed(w, summary);
            } else {
                fail(w, "整包下发未全部成功: " + summary);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(w, "整包下发被中断");
        } catch (Exception e) {
            fail(w, e.getMessage());
        }
    }

    /** 复位 / 重启工单：不走模板，直接走任务管线发一条 RPC */
    private void runRpcDelivery(Worklist w, boolean reboot) {
        String rpc = reboot ? "Reboot" : "FactoryReset";
        String comment = reboot ? "重启设备" : "恢复出厂";
        WorklistStepRecord step = addStep(w.getId(), 0, comment, rpc, Map.of());
        try {
            long taskId = taskService.createTask(w.getSn(), rpc, Map.of(), "worklist");
            Map<String, Object> task = taskService.awaitTask(taskId, 200);
            String status = task == null ? null : String.valueOf(task.get("status"));
            boolean ok = "success".equals(status) || "done".equals(status);
            finishStep(step, ok, null, task == null ? null : String.valueOf(task.get("result_content")));
            if (ok) {
                succeed(w, null);
            } else {
                fail(w, task == null ? "任务超时未完成" : String.valueOf(
                        task.getOrDefault("fault_string", task.get("status"))));
            }
        } catch (Exception e) {
            finishStep(step, false, null, null);
            fail(w, e.getMessage());
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

    /** 未指定模板时按业务类型取第一条，让前端不开模板选择也能下发 */
    private Long defaultTemplateId(String category) {
        try {
            List<Map<String, Object>> templates =
                    templateResolver.list(category, null);
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

    // ==================== 删改 ====================

    /** 建单扩展字段：管理端「新增工单」弹窗的附加信息 */
    public record OrderExtras(String bizType, Long groupId, String roomNo,
                              String extra, String remark, Boolean active,
                              Long bizTemplateId, String bizTemplateName) {
    }

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
     * 修改工单。房间号改动同步设备台账（与建单同口径）；设备标识改动只在
     * build/standby/reserve 阶段允许；未激活单改回激活后进入「等待执行」。
     */
    public Worklist update(Long id, OrderUpdate cmd) {
        Worklist w = worklistMapper.selectById(id);
        if (w == null) {
            throw new BusinessException("工单不存在: " + id);
        }
        if (inFlight(w.getStatus())) {
            throw new BusinessException("工单正在执行，暂不能修改");
        }
        // 设备标识：改了就重新归一（台账里有就取台账的 SN）
        if (cmd.sn() != null && !cmd.sn().isBlank() && !cmd.sn().trim().equals(w.getSn())) {
            if (!pending(w.getStatus())) {
                throw new BusinessException("工单已执行，设备标识不能再改");
            }
            Map<String, Object> device = findDevice(cmd.sn);
            w.setSn(device != null ? String.valueOf(device.get("sn")) : cmd.sn.trim());
            if (device != null) {
                w.setIsp((String) device.get("isp"));
                w.setDomain((String) device.get("device_type"));
            }
        }
        // 房间号：工单与台账一起改
        if (cmd.roomNo() != null) {
            w.setRoomNo(cmd.roomNo().isBlank() ? null : cmd.roomNo().trim());
        }
        if ((w.getRoomNo() != null && !w.getRoomNo().isBlank())
                || (w.getRemark() != null && !w.getRemark().isBlank())) {
            syncDeviceProfile(w.getSn(), w.getRoomNo(), w.getRemark());
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
        log.info("修改工单: id={}, sn={}, status={}, roomNo={}", w.getId(), w.getSn(),
                w.getStatus(), w.getRoomNo());
        return Worklist.decorate(w);
    }

    /** 删除工单：执行中的单拒删 */
    public void delete(Long id) {
        Worklist w = worklistMapper.selectById(id);
        if (w == null) {
            return;
        }
        if (inFlight(w.getStatus())) {
            throw new BusinessException("工单正在执行，暂不能删除");
        }
        worklistMapper.deleteById(id);
        stepMapper.delete(new LambdaQueryWrapper<WorklistStepRecord>()
                .eq(WorklistStepRecord::getWorklistId, id));
        log.info("删除工单: id={}, sn={}, desc={}", id, w.getSn(), w.getWorklistDesc());
    }

    // ==================== 步骤与状态工具 ====================

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

    private void finishStep(WorklistStepRecord step, boolean ok, Long durationMs, String response) {
        try {
            step.setStatus(ok ? "success" : "fail");
            step.setDurationMs(durationMs);
            step.setResponseContent(response);
            stepMapper.updateById(step);
        } catch (Exception e) {
            log.warn("工单步骤回写失败: worklistId={}, err={}", step.getWorklistId(), e.getMessage());
        }
    }

    private void succeed(Worklist w, String summary) {
        w.setStatus("success");
        w.setResult(summary);
        w.setTimeExecFinish(LocalDateTime.now());
        w.setUpdatedAt(LocalDateTime.now());
        worklistMapper.updateById(w);
        log.info("工单下发成功: id={}, sn={}", w.getId(), w.getSn());
    }

    private void fail(Worklist w, String reason) {
        w.setStatus("failed");
        w.setResult(reason);
        w.setTimeExecFinish(LocalDateTime.now());
        w.setUpdatedAt(LocalDateTime.now());
        worklistMapper.updateById(w);
        log.warn("工单下发失败: id={}, err={}", w.getId(), reason);
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

    /** 按台账找设备，找不到返回 null（工单留在等待执行） */
    private Map<String, Object> findDevice(String sn) {
        try {
            return store.findDevice(sn);
        } catch (Exception e) {
            return null;
        }
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

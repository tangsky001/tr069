package com.PilotACS.worklist.controller;

import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import com.PilotACS.common.security.UserContext;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.worklist.entity.Worklist;
import com.PilotACS.worklist.entity.WorklistStepRecord;
import com.PilotACS.worklist.mapper.WorklistMapper;
import com.PilotACS.worklist.mapper.WorklistStepMapper;
import com.PilotACS.worklist.service.WorklistDeliveryService;
import com.PilotACS.worklist.service.WorklistVariables;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 工单：建单、状态、下发记录与步骤明细 */
@Slf4j
@RestController
@RequestMapping("/worklist")
@RequiredArgsConstructor
@Tag(name = "工单管理", description = "下发工单：建单/预约/整包下发/执行记录/步骤明细")
public class WorklistController {

    private final WorklistMapper worklistMapper;
    private final WorklistStepMapper worklistStepMapper;
    private final CpeStore store;
    private final WorklistDeliveryService deliveryService;

    /** 某类业务需要填写哪些变量，前端据此渲染下发表单 */
    @Operation(summary = "业务变量清单：按配置模板类型给出要填的意图字段")
    @GetMapping("/variables")
    @RequiresPermission("worklist:view")
    public Result<List<Map<String, Object>>> variables(@RequestParam String category) {
        return Result.ok(WorklistVariables.forCategory(category));
    }

    /** 提交一张下发工单 */
    @Operation(summary = "提交下发工单：worklistDesc 为幂等键，不传由后端发号")
    @PostMapping("/instances")
    @RequiresPermission("worklist:update")
    public Result<Worklist> create(@RequestBody CreateRequest body) {
        String username = body.getUsername() == null || body.getUsername().isBlank()
                ? UserContext.get().getUsername() : body.getUsername();
        Worklist created = deliveryService.create(
                body.getSn(),
                body.getCategory(),
                body.getWorklistDesc(),
                body.getValues(),
                username,
                body.getTemplateId(),
                new WorklistDeliveryService.OrderExtras(
                        body.getWorklistType(),
                        body.getGroupId(),
                        body.getRoomNo(),
                        body.getExtra(),
                        body.getRemark(),
                        body.getActive(),
                        body.getBizTemplateId(),
                        body.getBizTemplateName()));
        return Result.ok(Worklist.decorate(created));
    }

    /** 修改工单：房间号/备注/扩展属性随时可改，设备标识与工单类型仅未执行的单可改 */
    @Operation(summary = "修改工单：执行中的单拒改")
    @PutMapping("/instances/{id}")
    @RequiresPermission("worklist:update")
    public Result<Worklist> update(@PathVariable Long id,
                                   @RequestBody WorklistDeliveryService.OrderUpdate body) {
        return Result.ok(Worklist.decorate(deliveryService.update(id, body)));
    }

    /** 删除工单：执行中的单拒删 */
    @Operation(summary = "删除工单：执行中的单拒删")
    @DeleteMapping("/instances/{id}")
    @RequiresPermission("worklist:update")
    public Result<Void> delete(@PathVariable Long id) {
        deliveryService.delete(id);
        return Result.ok();
    }

    /** 工单状态全集（带中文与标签色，前端筛选下拉直接渲染） */
    @Operation(summary = "工单状态全集：码+中文+标签色，页面筛选下拉取这里")
    @GetMapping("/statuses")
    @RequiresPermission("worklist:view")
    public Result<List<Map<String, Object>>> statuses() {
        return Result.ok(Worklist.statusOptions());
    }

    /** 建单入参 */
    @Data
    public static class CreateRequest {
        /** 设备序列号 */
        private String sn;
        /** 业务类型：WIFI_24G / WIFI_58G / SIP / WAN_DHCP / WAN_PPPOE / WAN_STATIC / WAN_BRIDGE / OTHER */
        private String category;
        /** 调用方流水号，幂等键；不传由后端发号 */
        private String worklistDesc;
        /** 变量值，键为配置模板意图字段键 */
        private Map<String, String> values;
        /** 操作人；不传取当前登录人 */
        private String username;
        /** 配置模板 id；不传则按业务类型自动取第一条模板 */
        private Long templateId;
        /** 工单类型：normal 普通 / reset 恢复出厂 / reboot 重启，缺省 normal */
        private String worklistType;
        /** 设备分组 id */
        private Long groupId;
        /** 房间号：落工单并同步设备台账 */
        private String roomNo;
        /** 扩展属性 */
        private String extra;
        /** 备注 */
        private String remark;
        /** 是否激活：false 只建单不下发，缺省 true */
        private Boolean active;
        /** 业务模板 id：传了走整包下发通道（由调度在设备在线后触发） */
        private Long bizTemplateId;
        /** 业务模板名称，仅用于工单列表展示 */
        private String bizTemplateName;
    }

    /** 工单执行记录，按设备或状态查询。 */
    @Operation(summary = "工单执行记录分页：keyword/worklistNo/room/时间区间/状态多值筛选")
    @GetMapping("/instances")
    @RequiresPermission("worklist:view")
    public Result<Map<String, Object>> instances(
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String worklistNo,
            @RequestParam(required = false) String room,
            @RequestParam(required = false) String timeStart,
            @RequestParam(required = false) String timeEnd,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        LambdaQueryWrapper<Worklist> query = new LambdaQueryWrapper<>();
        if (sn != null && !sn.isBlank()) {
            query.eq(Worklist::getSn, sn);
        }
        // 设备标识一个框查：SN 模糊 + MAC 参数值反查出的 SN
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            List<String> kwSns = snsByMac(kw);
            query.and(w -> {
                w.like(Worklist::getSn, kw);
                if (!kwSns.isEmpty()) {
                    w.or().in(Worklist::getSn, kwSns);
                }
            });
        }
        // 工单号：数字先按工单 id 精确匹配，再按流水号模糊兜底
        if (worklistNo != null && !worklistNo.isBlank()) {
            String no = worklistNo.trim();
            query.and(w -> {
                if (no.matches("\\d+")) {
                    w.eq(Worklist::getId, Long.parseLong(no)).or().like(Worklist::getWorklistDesc, no);
                } else {
                    w.like(Worklist::getWorklistDesc, no);
                }
            });
        }
        // 房间号经设备台账反查：工单上的 room_no 只在建单时录入过，台账才是权威
        if (room != null && !room.isBlank()) {
            List<String> roomSns = snsByRoom(room.trim());
            if (roomSns.isEmpty()) {
                return Result.ok(Map.of("items", List.of(), "total", 0));
            }
            query.in(Worklist::getSn, roomSns);
        }
        // 执行时间区间：按执行开始时间过滤，传日期自动补全天
        LocalDateTime start = parseTime(timeStart, false);
        LocalDateTime end = parseTime(timeEnd, true);
        if (start != null) {
            query.ge(Worklist::getTimeExecStart, start);
        }
        if (end != null) {
            query.le(Worklist::getTimeExecStart, end);
        }
        // 状态：逗号分隔多值，单值退化为 eq；fail/failed 互为同义词（历史数据两种写法都存在）
        if (status != null && !status.isBlank()) {
            java.util.LinkedHashSet<String> expanded = new java.util.LinkedHashSet<>();
            for (String part : status.split(",")) {
                String s = part.trim();
                if (s.isEmpty()) {
                    continue;
                }
                expanded.add(s);
                if ("fail".equals(s)) {
                    expanded.add("failed");
                } else if ("failed".equals(s)) {
                    expanded.add("fail");
                }
            }
            if (expanded.size() == 1) {
                query.eq(Worklist::getStatus, expanded.iterator().next());
            } else if (!expanded.isEmpty()) {
                query.in(Worklist::getStatus, expanded);
            }
        }
        query.orderByDesc(Worklist::getId);
        IPage<Worklist> result = worklistMapper.selectPage(
                Page.of(Math.max(1, page), Math.max(1, Math.min(size, 200))), query);
        // 状态中文/标签色/时间文本随记录下发，页面不再维护状态码表；MAC 从能力树带出
        result.getRecords().forEach(Worklist::decorate);
        decorateMac(result.getRecords());
        return Result.ok(Map.of("items", result.getRecords(), "total", result.getTotal()));
    }

    /** MAC 参数值反查设备 SN：标识不在台账也不在工单上，只在能力树参数里 */
    private List<String> snsByMac(String value) {
        try {
            return store.findSnsByParamValue(value);
        } catch (Exception e) {
            log.warn("按 MAC 反查设备失败: value={}, err={}", value, e.getMessage());
            return List.of();
        }
    }

    /** 房间号反查设备 SN：台账 cpe_device.room_no 模糊匹配 */
    private List<String> snsByRoom(String room) {
        try {
            return store.findSnsByRoom(room);
        } catch (Exception e) {
            log.warn("按房间号反查设备失败: room={}, err={}", room, e.getMessage());
            return List.of();
        }
    }

    /** 给一页工单补 MAC 文本：一台设备取能力树里第一条 MAC 参数值 */
    private void decorateMac(List<Worklist> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (Worklist w : records) {
            try {
                w.setMacText(store.loadBizParams(w.getSn()).entrySet().stream()
                        .filter(e -> e.getKey().endsWith("MACAddress"))
                        .map(Map.Entry::getValue).findFirst().orElse(null));
            } catch (Exception e) {
                // 参数树没有就空着
            }
        }
    }

    /** 时间入参：完整时间（yyyy-MM-dd[ T]HH:mm[:ss]）或纯日期（按开始/结束补全天） */
    private static LocalDateTime parseTime(String text, boolean endOfDay) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.trim().replace('T', ' ');
        try {
            if (s.length() == 10) {
                LocalDate date = LocalDate.parse(s);
                return endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
            }
            if (s.length() == 16) {
                s = s + ":00";
            }
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            log.warn("执行时间筛选参数解析失败: text={}", text);
            return null;
        }
    }

    /** 单张工单的执行进度 */
    @Operation(summary = "工单执行进度：轮询端也拿得到 statusText")
    @GetMapping("/instances/{id}")
    @RequiresPermission("worklist:view")
    public Result<Worklist> instance(@PathVariable Long id) {
        Worklist record = worklistMapper.selectById(id);
        if (record == null) {
            throw new BusinessException("工单不存在: " + id);
        }
        return Result.ok(Worklist.decorate(record));
    }

    /** 某个设备的工单历史 */
    @Operation(summary = "某设备的工单历史（最多 100 条）")
    @GetMapping("/instances/device/{sn}")
    @RequiresPermission("worklist:view")
    public Result<List<Worklist>> listByDevice(@PathVariable String sn) {
        List<Worklist> list = worklistMapper.selectList(new LambdaQueryWrapper<Worklist>()
                .eq(Worklist::getSn, sn)
                .orderByDesc(Worklist::getId)
                .last("limit 100"));
        list.forEach(Worklist::decorate);
        return Result.ok(list);
    }

    /** 单张工单的步骤明细 */
    @Operation(summary = "工单步骤明细：含下发参数与设备响应")
    @GetMapping("/instances/{id}/steps")
    @RequiresPermission("worklist:view")
    public Result<List<WorklistStepRecord>> steps(@PathVariable Long id) {
        List<WorklistStepRecord> steps = worklistStepMapper.selectList(
                new LambdaQueryWrapper<WorklistStepRecord>()
                        .eq(WorklistStepRecord::getWorklistId, id)
                        .orderByAsc(WorklistStepRecord::getStepIndex));
        steps.forEach(WorklistStepRecord::decorate);
        return Result.ok(steps);
    }
}

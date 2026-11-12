package com.tr069.acs.worklist.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tr069.acs.common.PageResult;
import com.tr069.acs.common.Result;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.domain.entity.Worklist;
import com.tr069.acs.domain.entity.WorklistStepRecord;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import com.tr069.acs.mapper.WorklistMapper;
import com.tr069.acs.mapper.WorklistStepMapper;
import com.tr069.acs.worklist.model.WifiEdit;
import com.tr069.acs.worklist.service.WorklistDeliveryService;
import com.tr069.acs.worklist.tpl.TemplateVariables;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工单下发与执行记录查询 */
@Slf4j
@RestController
@RequestMapping("/api/worklist")
@RequiredArgsConstructor
public class WorklistExecuteController {

    private final WorklistMapper worklistMapper;
    private final WorklistStepMapper worklistStepMapper;
    private final CpeDeviceMapper cpeDeviceMapper;
    private final CpeParameterMapper cpeParameterMapper;
    private final WorklistDeliveryService deliveryService;

    /** 某类业务需要填写哪些变量，前端据此渲染下发表单 */
    @GetMapping("/variables")
    public Result<List<TemplateVariables.Var>> variables(@RequestParam String category) {
        return Result.success(TemplateVariables.forCategory(category));
    }

    /** 提交一张下发工单 */
    @PostMapping("/instances")
    public Result<Worklist> create(@RequestBody CreateRequest body) {
        String category = body.getCategory();
        Map<String, String> values = body.getValues();
        WifiEdit wifi = body.getWifi();
        if (wifi != null) {
            // WiFi 编辑只提交业务字段：类型与意图键名都在后端翻译，页面不感知具体键名
            if (wifi.getSsid() == null || wifi.getSsid().isBlank()) {
                return Result.failure("SSID 名称不能为空");
            }
            category = wifi.toCategory();
            values = wifi.toValues();
        }
        Worklist created = deliveryService.create(
                body.getSn(),
                category,
                body.getWorklistDesc(),
                values,
                body.getUsername(),
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
        return Result.success(created);
    }

    /** 修改工单：房间号/备注/扩展属性随时可改，设备标识与工单类型仅未执行的单可改 */
    @PutMapping("/instances/{id}")
    public Result<Worklist> update(@PathVariable Long id,
                                   @RequestBody WorklistDeliveryService.OrderUpdate body) {
        return Result.success(deliveryService.update(id, body));
    }

    /** 删除工单：执行中的单拒删，逻辑删除 */
    @DeleteMapping("/instances/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        deliveryService.delete(id);
        return Result.success(Boolean.TRUE);
    }

    /** 工单状态全集 */
    @GetMapping("/statuses")
    public Result<List<String>> statuses() {
        return Result.success(Worklist.STATUSES);
    }

    /** 建单入参 */
    @Data
    public static class CreateRequest {
        /** 设备序列号 */
        private String sn;
        /** 业务类型：WIFI_24G / WIFI_58G / SIP / WAN_DHCP / WAN_PPPOE / WAN_STATIC / WAN_BRIDGE */
        private String category;
        /** 调用方流水号，幂等键；不传由调用方保证不重复时可省略 */
        private String worklistDesc;
        /** 变量值，键为 %room% 这类占位符 */
        private Map<String, String> values;
        /** 操作人 */
        private String username;
        /** 开通模板 id；不传则按业务类型自动取第一条模板 */
        private Long templateId;
        /** WiFi 编辑的业务字段。传了它就不必再传 category 与 values */
        private WifiEdit wifi;
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

    /** 工单执行记录查询：keyword（SN/MAC/LOID）、worklistNo、room、时间区间、status 多值；兼容老参数 sn/status */
    @GetMapping("/instances")
    public Result<PageResult<Worklist>> instances(
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
        // 设备标识一个框查：SN 模糊 + MAC/LOID 参数值反查出的 SN
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            List<String> kwSns = snsByIdParam(kw);
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
                return Result.success(PageResult.of(List.of(), 0, page, size));
            }
            query.in(Worklist::getSn, roomSns);
        }
        // 执行时间区间：按执行开始时间过滤，传日期（yyyy-MM-dd）自动补全天
        LocalDateTime start = parseTime(timeStart, false);
        LocalDateTime end = parseTime(timeEnd, true);
        if (start != null) {
            query.ge(Worklist::getTimeExecStart, start);
        }
        if (end != null) {
            query.le(Worklist::getTimeExecStart, end);
        }
        // 状态：逗号分隔多值，单值退化为 eq
        if (status != null && !status.isBlank()) {
            List<String> statuses = Arrays.stream(status.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (statuses.size() == 1) {
                query.eq(Worklist::getStatus, statuses.get(0));
            } else if (!statuses.isEmpty()) {
                query.in(Worklist::getStatus, statuses);
            }
        }
        query.orderByDesc(Worklist::getId);
        Page<Worklist> pager = Page.of(page, size);
        IPage<Worklist> result = worklistMapper.selectPage(pager, query);
        // 状态中文/标签色/时间文本随记录下发，页面不再维护状态码表
        result.getRecords().forEach(Worklist::decorate);
        // MAC 从能力树带出，工单页设备标识列的副行展示
        decorateMac(result.getRecords());
        return Result.success(PageResult.of(result.getRecords(), result.getTotal(), page, size));
    }

    /** MAC/LOID 参数值反查设备 SN：标识不在台账也不在工单上，只在能力树参数里 */
    private List<String> snsByIdParam(String value) {
        try {
            return cpeParameterMapper.selectList(new LambdaQueryWrapper<CpeParameter>()
                    .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                    .and(w -> w.like(CpeParameter::getParamPath, "MAC")
                            .or().like(CpeParameter::getParamPath, "LOID")
                            .or().like(CpeParameter::getParamPath, "LogicalID"))
                    .like(CpeParameter::getParamValue, value)
                    .last("limit 200"))
                    .stream().map(CpeParameter::getSn).distinct().toList();
        } catch (Exception e) {
            log.warn("按 MAC/LOID 反查设备失败: value={}, err={}", value, e.getMessage());
            return List.of();
        }
    }

    /** 房间号反查设备 SN：台账 cpe_device.room_no 模糊匹配 */
    private List<String> snsByRoom(String room) {
        try {
            return cpeDeviceMapper.selectList(new LambdaQueryWrapper<CpeDevice>()
                    .like(CpeDevice::getRoomNo, room)
                    .last("limit 500"))
                    .stream().map(CpeDevice::getSn).distinct().toList();
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
        List<String> sns = records.stream().map(Worklist::getSn).distinct().toList();
        Map<String, String> macBySn = new LinkedHashMap<>();
        try {
            for (CpeParameter row : cpeParameterMapper.selectList(new LambdaQueryWrapper<CpeParameter>()
                    .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                    .like(CpeParameter::getParamPath, "MAC")
                    .in(CpeParameter::getSn, sns))) {
                String value = row.getParamValue();
                if (value != null && !value.isBlank()) {
                    macBySn.putIfAbsent(row.getSn(), value.trim());
                }
            }
        } catch (Exception e) {
            log.warn("工单页补 MAC 失败: {}", e.getMessage());
        }
        for (Worklist w : records) {
            w.setMacText(macBySn.get(w.getSn()));
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
    @GetMapping("/instances/{id}")
    public Result<Worklist> instance(@PathVariable Long id) {
        Worklist record = worklistMapper.selectById(id);
        if (record == null) {
            return Result.failure("工单不存在: " + id);
        }
        // 轮询端也拿得到 statusText，页面不必再翻一遍码表
        return Result.success(Worklist.decorate(record));
    }

    /** 某个设备的工单历史 */
    @GetMapping("/instances/device/{sn}")
    public Result<List<Worklist>> listByDevice(@PathVariable String sn) {
        return Result.success(worklistMapper.selectList(
                new LambdaQueryWrapper<Worklist>()
                        .eq(Worklist::getSn, sn)
                        .orderByDesc(Worklist::getId)
                        .last("limit 100")));
    }

    /** 单张工单的步骤明细 */
    @GetMapping("/instances/{id}/steps")
    public Result<List<WorklistStepRecord>> steps(@PathVariable Long id) {
        return Result.success(worklistStepMapper.selectList(
                new LambdaQueryWrapper<WorklistStepRecord>()
                        .eq(WorklistStepRecord::getWorklistId, id)
                        .orderByAsc(WorklistStepRecord::getStepIndex)));
    }

}

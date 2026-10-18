package com.tr069.acs.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tr069.acs.acs.service.DeviceAccountService;
import com.tr069.acs.acs.service.DeviceBusinessService;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.common.PageResult;
import com.tr069.acs.common.Result;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.queue.RpcTaskQueue;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.mapper.CpeDeviceMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备管理接口。
 *
 * <p>对应 Python 版 web/itms 下的设备查询与 CWMP 方法手动下发。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final CpeDeviceMapper cpeDeviceMapper;
    private final SessionManager sessionManager;
    private final RpcDispatchService rpcDispatchService;
    private final RpcTaskQueue rpcTaskQueue;
    private final DeviceBusinessService businessService;
    private final DeviceAccountService accountService;

    /**
     * 设备分页查询，支持按序列号模糊匹配与运营商筛选。
     */
    @GetMapping("/page")
    public Result<PageResult<CpeDevice>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String isp,
            @RequestParam(required = false) String deviceType) {
        LambdaQueryWrapper<CpeDevice> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(isNotBlank(sn), CpeDevice::getSn, sn)
                .eq(isNotBlank(isp), CpeDevice::getIsp, isp)
                .eq(isNotBlank(deviceType), CpeDevice::getDeviceType, deviceType)
                .orderByDesc(CpeDevice::getLastContactTime);

        IPage<CpeDevice> result = cpeDeviceMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(PageResult.of(result.getRecords(), result.getTotal(), page, size));
    }

    /**
     * 批量业务信息：设备列表页整页一次拉取 WAN/WLAN/SIP 概要，避免逐行发请求。
     *
     * <p>sns 为逗号分隔的序列号，单台失败不影响其余。</p>
     */
    @GetMapping("/business/batch")
    public Result<Map<String, Map<String, Object>>> businessBatch(@RequestParam String sns) {
        List<String> snList = java.util.Arrays.stream(sns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(200)
                .toList();
        return Result.success(businessService.summaryBatch(snList));
    }

    /**
     * 设备详情，同时回带在线会话的实时属性。
     */
    @GetMapping("/{sn}")
    public Result<Map<String, Object>> detail(@PathVariable String sn) {
        CpeDevice device = cpeDeviceMapper.selectOne(
                new LambdaQueryWrapper<CpeDevice>().eq(CpeDevice::getSn, sn));
        if (device == null) {
            return Result.failure("设备不存在");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("device", device);

        CpeSession session = sessionManager.find(sn);
        if (session != null) {
            Map<String, Object> live = new HashMap<>();
            live.put("online", session.isOnline());
            live.put("state", session.getState());
            live.put("lastContactTime", session.getLastContactTime());
            live.put("pendingCount", session.getOutbox().size());
            data.put("session", live);
        }
        return Result.success(data);
    }

    /**
     * 设备在线状态。
     */
    @GetMapping("/{sn}/status")
    public Result<Map<String, Object>> status(@PathVariable String sn) {
        CpeSession session = sessionManager.find(sn);
        Map<String, Object> data = new HashMap<>();
        data.put("sn", sn);
        data.put("online", session != null && session.isOnline());
        data.put("state", session == null ? "offline" : session.getState().name());
        return Result.success(data);
    }

    /**
     * 在线会话列表。
     */
    @GetMapping("/online")
    public Result<List<Map<String, Object>>> online() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CpeSession session : sessionManager.allSessions()) {
            if (!session.isOnline()) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("sn", session.getSn());
            item.put("oui", session.getOui());
            item.put("state", session.getState().name());
            item.put("softwareVersion", session.getSoftwareVersion());
            item.put("hardwareVersion", session.getHardwareVersion());
            item.put("connectionRequestUrl", session.getConnectionRequestUrl());
            item.put("lastContactTime", session.getLastContactTime());
            // 待下发指令已放到 Redis 队列，不再统计内存 outbox
            item.put("pendingCount", rpcTaskQueue.pendingCount(session.getSn()));
            list.add(item);
        }
        return Result.success(list);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 保存设备的房间号与备注。
     *
     * <p>房间号是下发时替换模板变量 {@code %room%} 的取值来源，例如把备注设为 0001，
     * 下发 WiFi 名称时就会用 0001 替换掉模板里的 {@code %room%}。两者都是台账侧的
     * 运维字段，不参与 CWMP 交互，因此直接改设备表即可，不需要设备在线。</p>
     */
    @PostMapping("/{sn}/remark")
    public Result<Boolean> updateRemark(@PathVariable String sn,
                                        @RequestBody(required = false) RemarkRequest request) {
        CpeDevice device = cpeDeviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                .eq(CpeDevice::getSn, sn));
        if (device == null) {
            return Result.failure("设备不存在: " + sn);
        }
        if (request != null) {
            device.setRoomNo(request.getRoomNo());
            device.setRemark(request.getRemark());
        }
        int rows = cpeDeviceMapper.updateById(device);
        log.info("更新设备房间号/备注: sn={}, roomNo={}, remark={}",
                sn, device.getRoomNo(), device.getRemark());
        return Result.success(rows > 0);
    }

    /**
     * 房间号与备注的保存入参。两个字段都允许传空串表示清空。
     */
    @Data
    public static class RemarkRequest {

        private String roomNo;

        private String remark;
    }

    /**
     * 查询设备本地账号（超管 telecomadmin 等）。
     *
     * <p>每台设备的超管口令可能不一样，只能到设备上现读。这里对在线设备下发
     * {@code GetParameterValues} 读 {@code User.{i}.Username} / {@code Password}，
     * 账号与口令都是明文返回，前端直接展示给运维登后台用。</p>
     *
     * <p>请求会挂起等设备回值，最长 {@code acs.device.account.read-timeout-seconds}
     * （默认 30s）；设备离线立即返回 offline，不空等。</p>
     *
     * @return {@code accounts}：账号列表，每项 instance / path / username / password；
     *         {@code status}：success / empty / timeout / fail / offline
     */
    @GetMapping("/{sn}/admin-account")
    public Result<Map<String, Object>> adminAccount(@PathVariable String sn) {
        return Result.success(accountService.queryAdminAccount(sn));
    }

    /**
     * 手动下发一条 CWMP RPC，对应原版 web 端的 19 种 RPC 下发入口。
     */
    @PostMapping("/{sn}/rpc/{rpcName}")
    public Result<Map<String, Object>> rpc(@PathVariable String sn,
                                           @PathVariable String rpcName,
                                           @RequestBody(required = false) Map<String, Object> args) {
        // 提交即返回，不再挂起请求线程等设备回应：
        // 设备可能几十秒后才来空 POST 把指令取走
        RpcDispatchService.DispatchHandle handle = rpcDispatchService.submitAsync(
                sn, rpcName, args == null ? Map.of() : args, null);
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", handle.getTaskId());
        data.put("queued", handle.isQueued());
        return Result.success(data);
    }
}

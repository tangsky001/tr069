/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.controller;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import com.PilotACS.sip.SipProperties;
import com.PilotACS.sip.call.CallManager;
import com.PilotACS.sip.call.CallSession;
import com.PilotACS.sip.h248.GatewayRegistry;
import com.PilotACS.sip.queue.SipQueue;
import com.PilotACS.sip.queue.SipQueueService;
import com.PilotACS.sip.record.SipCallRecord;
import com.PilotACS.sip.record.SipCallRecordService;
import com.PilotACS.sip.reg.ExtensionRegistry;
import com.PilotACS.sip.reg.Registration;
import com.PilotACS.sip.reg.SipExtension;
import com.PilotACS.sip.reg.SipExtensionService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SIP 软交换接口：分机管理、注册查询、活跃呼叫、H.248 网关 */
@Tag(name = "电话交换")
@RestController
@RequestMapping("/sip")
@RequiresPermission("sip:view")
public class SipAdminController {

    private final SipProperties props;
    private final ExtensionRegistry registry;
    private final GatewayRegistry registry248;
    private final CallManager callManager;
    private final SipExtensionService extService;
    private final SipQueueService queueService;
    private final SipCallRecordService recordService;

    public SipAdminController(SipProperties props, ExtensionRegistry registry,
                              GatewayRegistry registry248, CallManager callManager,
                              SipExtensionService extService, SipQueueService queueService,
                              SipCallRecordService recordService) {
        this.props = props;
        this.registry = registry;
        this.registry248 = registry248;
        this.callManager = callManager;
        this.extService = extService;
        this.queueService = queueService;
        this.recordService = recordService;
    }

    @Operation(summary = "分机列表（含注册状态）")
    @GetMapping("/extensions")
    public Result<List<Map<String, Object>>> extensions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SipExtension e : extService.list()) {
            String ext = e.getExtension();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("extension", ext);
            row.put("password", e.getPassword());
            row.put("displayName", e.getDisplayName());
            row.put("remark", e.getRemark());
            Registration reg = registry.findAlive(ext);
            row.put("registered", reg != null);
            if (reg != null) {
                row.put("address", reg.getAddress().toString());
                row.put("userAgent", reg.getUserAgent());
                row.put("expireSeconds", Math.max(0, (reg.getExpiresAt() - System.currentTimeMillis()) / 1000));
            }
            row.put("inCall", callManager.isBusy(ext));
            out.add(row);
        }
        return Result.ok(out);
    }

    @Operation(summary = "添加分机")
    @RequiresPermission("sip:update")
    @PostMapping("/extensions")
    public Result<SipExtension> addExtension(@RequestBody Map<String, String> body) {
        return Result.ok(extService.add(body.get("extension"), body.get("password"),
                body.get("displayName"), body.get("remark")));
    }

    @Operation(summary = "修改分机")
    @RequiresPermission("sip:update")
    @PutMapping("/extensions/{id}")
    public Result<Void> updateExtension(@PathVariable Long id, @RequestBody Map<String, String> body) {
        extService.update(id, body.get("password"), body.get("displayName"), body.get("remark"));
        return Result.ok();
    }

    @Operation(summary = "删除分机")
    @RequiresPermission("sip:delete")
    @DeleteMapping("/extensions/{id}")
    public Result<Void> deleteExtension(@PathVariable Long id) {
        extService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "批量导入分机（txt/csv，每行：分机号 密码 [备注]）")
    @RequiresPermission("sip:update")
    @PostMapping("/extensions/import")
    public Result<Map<String, Object>> importExtensions(@RequestParam("file") MultipartFile file) throws Exception {
        return Result.ok(extService.importFile(file));
    }

    @Operation(summary = "批量生成号段（前缀+起止补齐4位，可排除第3/4位含4）")
    @RequiresPermission("sip:update")
    @PostMapping("/extensions/generate")
    public Result<Map<String, Object>> generateExtensions(@RequestBody Map<String, Object> body) {
        String prefix = String.valueOf(body.getOrDefault("prefix", "")).trim();
        int start = Integer.parseInt(String.valueOf(body.getOrDefault("start", "1")));
        int end = Integer.parseInt(String.valueOf(body.getOrDefault("end", "99")));
        boolean exclude4 = Boolean.parseBoolean(String.valueOf(body.getOrDefault("exclude4", "false")));
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        String displayName = body.get("displayName") == null ? "" : String.valueOf(body.get("displayName"));
        return Result.ok(extService.generate(prefix, start, end, exclude4, password, displayName));
    }

    @Operation(summary = "呼叫队列列表")
    @GetMapping("/queues")
    public Result<List<SipQueue>> queues() {
        return Result.ok(queueService.list());
    }

    @Operation(summary = "保存队列（id 为空新建）")
    @RequiresPermission("sip:update")
    @PostMapping("/queues")
    public Result<Void> saveQueue(@RequestBody SipQueue queue) {
        queueService.save(queue);
        return Result.ok();
    }

    @Operation(summary = "删除队列")
    @RequiresPermission("sip:delete")
    @DeleteMapping("/queues/{id}")
    public Result<Void> deleteQueue(@PathVariable Long id) {
        queueService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "活跃呼叫列表")
    @GetMapping("/calls")
    public Result<List<Map<String, Object>>> calls() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CallSession s : callManager.activeSessions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caller", s.getCallerExt());
            row.put("callee", s.getCalleeExt());
            row.put("callerName", extService.displayNameOf(s.getCallerExt()));
            row.put("calleeName", extService.displayNameOf(s.getCalleeExt()));
            row.put("state", s.getState().name());
            row.put("durationSeconds", s.getState() == CallSession.State.ACTIVE
                    ? (System.currentTimeMillis() - s.getCreatedAt()) / 1000 : 0);
            out.add(row);
        }
        return Result.ok(out);
    }

    @Operation(summary = "H.248 网关与终结点列表")
    @GetMapping("/gateways")
    public Result<List<Map<String, Object>>> gateways() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GatewayRegistry.Gateway gw : registry248.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("address", gw.address == null ? "-" : gw.address.toString());
            row.put("version", gw.version);
            row.put("registeredSeconds", gw.registeredAt > 0
                    ? (System.currentTimeMillis() - gw.registeredAt) / 1000 : 0);
            row.put("terminals", props.getH248Terminals());
            out.add(row);
        }
        return Result.ok(out);
    }

    @Operation(summary = "通话记录分页查询（ext 同时匹配主叫/被叫）")
    @GetMapping("/records")
    public Result<Map<String, Object>> records(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String ext) {
        IPage<SipCallRecord> result = recordService.page(ext, page, size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", result.getTotal());
        out.put("list", result.getRecords());
        return Result.ok(out);
    }

    @Operation(summary = "下载通话录音")
    @GetMapping("/records/{id}/download")
    public ResponseEntity<ByteArrayResource> downloadRecord(@PathVariable Long id) {
        Path file = recordService.resolveRecordFile(id);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] data = Files.readAllBytes(file);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''"
                            + URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8))
                    .contentType(MediaType.parseMediaType("audio/wav"))
                    .contentLength(data.length)
                    .body(new ByteArrayResource(data));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}

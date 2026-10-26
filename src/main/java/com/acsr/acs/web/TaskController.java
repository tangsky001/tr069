package com.acsr.acs.web;

import com.acsr.acs.common.Result;
import com.acsr.acs.model.entity.CpeDevice;
import com.acsr.acs.model.entity.DispatchTask;
import com.acsr.acs.service.DispatchService;
import com.acsr.acs.service.DeviceService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 下发任务：创建、查询、取消。 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

  private static final List<String> RPCS = List.of(
      "GetParameterValues", "SetParameterValues", "GetParameterNames",
      "AddObject", "DeleteObject", "Reboot", "FactoryReset", "Download", "Upload",
      "GetParameterAttributes", "SetParameterAttributes", "ScheduleInform");

  private final DispatchService dispatchService;
  private final DeviceService deviceService;
  private final WebSupport webSupport;

  @GetMapping("/rpcs")
  public Result<List<String>> rpcs() {
    return Result.ok(RPCS);
  }

  @GetMapping
  public Result<IPage<DispatchTask>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String unitId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String rpcName) {
    return Result.ok(dispatchService.page(current, size, unitId, status, rpcName));
  }

  @GetMapping("/{id}")
  public Result<DispatchTask> detail(@PathVariable Long id) {
    return Result.ok(dispatchService.get(id));
  }

  @Data
  public static class CreateTaskRequest {
    /** 单台设备 */
    private String unitId;
    /** 批量设备 */
    private List<String> unitIds;
    /** 按型号筛选批量（model + 可选固件版本） */
    private String model;
    private String firmwareVersion;
    private String rpcName;
    /** SetParameterValues: [{"name":"","value":"","type":"xsd:string"}] */
    private Object params;
    /** GetParameterValues: ["path1","path2"] 或 {"names":[...]} */
    private Object names;
    /** Download/Upload 的文件 id */
    private Long fileId;
    /** AddObject/DeleteObject 的对象路径 */
    private String objectName;
  }

  @PostMapping
  public Result<Map<String, Object>> create(@RequestBody CreateTaskRequest req,
                                            HttpServletRequest request) {
    List<String> unitIds = resolveTargets(req);
    if (unitIds.isEmpty()) {
      return Result.fail("没有匹配到目标设备");
    }
    String payload = buildPayload(req);
    Long operator = webSupport.currentUserId();
    List<Long> ids = new ArrayList<>();
    for (String unitId : unitIds) {
      CpeDevice device = deviceService.findByUnitId(unitId);
      DispatchTask task = dispatchService.create(unitId,
          device == null ? null : device.getSerialNumber(), req.getRpcName(), payload, operator);
      ids.add(task.getId());
    }
    webSupport.audit(request, "CREATE_TASK", req.getRpcName(),
        "设备数=" + ids.size() + ", payload=" + payload);
    return Result.ok(Map.of("taskIds", ids, "count", ids.size()));
  }

  @PostMapping("/cancel")
  public Result<Map<String, Object>> cancel(@RequestBody Map<String, List<Long>> body) {
    int n = dispatchService.cancel(body.get("ids"));
    return Result.ok(Map.of("canceled", n));
  }

  @GetMapping("/stats")
  public Result<Map<String, Object>> stats() {
    Map<String, Object> data = new java.util.LinkedHashMap<>();
    for (String s : List.of(DispatchService.PENDING, DispatchService.QUEUED, DispatchService.SENDING,
        DispatchService.WAIT_RESULT, DispatchService.SUCCESS, DispatchService.FAILED,
        DispatchService.TIMEOUT, DispatchService.CANCELED)) {
      data.put(s, dispatchService.countByStatus(s));
    }
    return Result.ok(data);
  }

  private List<String> resolveTargets(CreateTaskRequest req) {
    List<String> targets = new ArrayList<>();
    if (req.getUnitIds() != null) {
      targets.addAll(req.getUnitIds());
    }
    if (req.getUnitId() != null && !req.getUnitId().isBlank()) {
      targets.add(req.getUnitId());
    }
    if ((targets.isEmpty()) && req.getModel() != null && !req.getModel().isBlank()) {
      var page = deviceService.page(1, 1000, req.getModel(), null);
      for (CpeDevice d : page.getRecords()) {
        if (req.getFirmwareVersion() == null || req.getFirmwareVersion().isBlank()
            || req.getFirmwareVersion().equals(d.getFirmwareVersion())) {
          targets.add(d.getUnitId());
        }
      }
    }
    return targets;
  }

  private String buildPayload(CreateTaskRequest req) {
    try {
      if (req.getParams() != null) {
        if (req.getParams() instanceof String s) {
          return s;
        }
        return new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(Map.of("params", req.getParams()));
      }
      if (req.getNames() != null) {
        if (req.getNames() instanceof String s) {
          return "{\"names\":[\"" + s + "\"]}";
        }
        return new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(Map.of("names", req.getNames()));
      }
      if (req.getFileId() != null) {
        return "{\"fileId\":" + req.getFileId() + "}";
      }
      if (req.getObjectName() != null) {
        return "{\"objectName\":\"" + req.getObjectName() + "\"}";
      }
    } catch (Exception e) {
      return "{}";
    }
    return "{}";
  }
}

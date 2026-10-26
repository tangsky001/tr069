package com.acsr.acs.web;

import com.acsr.acs.common.Result;
import com.acsr.acs.model.entity.CpeDevice;
import com.acsr.acs.model.entity.UnitParam;
import com.acsr.acs.service.DeviceService;
import com.acsr.acs.service.KickService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 设备台账 / 参数 / 唤醒。 */
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

  private final DeviceService deviceService;
  private final KickService kickService;
  private final WebSupport webSupport;

  @GetMapping
  public Result<IPage<CpeDevice>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Integer online) {
    return Result.ok(deviceService.page(current, size, keyword, online));
  }

  @GetMapping("/online")
  public Result<Map<String, Object>> online() {
    Map<String, Object> data = new HashMap<>();
    data.put("online", deviceService.page(1, 1, null, 1).getTotal());
    data.put("total", deviceService.page(1, 1, null, null).getTotal());
    return Result.ok(data);
  }

  @GetMapping("/{unitId}")
  public Result<CpeDevice> detail(@PathVariable String unitId) {
    CpeDevice device = deviceService.findByUnitId(unitId);
    if (device == null) {
      return Result.fail(404, "设备不存在");
    }
    return Result.ok(device);
  }

  @PutMapping("/{id}")
  public Result<?> update(@PathVariable Long id, @RequestBody CpeDevice body, HttpServletRequest request) {
    body.setId(id);
    deviceService.updateDevice(body);
    webSupport.audit(request, "UPDATE_DEVICE", String.valueOf(id), null);
    return Result.ok();
  }

  @DeleteMapping("/{id}")
  public Result<?> delete(@PathVariable Long id, HttpServletRequest request) {
    deviceService.deleteDevice(id);
    webSupport.audit(request, "DELETE_DEVICE", String.valueOf(id), null);
    return Result.ok();
  }

  @GetMapping("/{unitId}/params")
  public Result<List<UnitParam>> params(@PathVariable String unitId) {
    return Result.ok(deviceService.params(unitId));
  }

  @Data
  public static class ParamRequest {
    private String name;
    private String value;
    private String flags;
  }

  @PostMapping("/{unitId}/params")
  public Result<?> setParam(@PathVariable String unitId, @RequestBody ParamRequest req,
                            HttpServletRequest request) {
    deviceService.setParam(unitId, req.getName(), req.getValue(),
        req.getFlags() == null ? "RW" : req.getFlags());
    webSupport.audit(request, "SET_PARAM", unitId, req.getName() + "=" + req.getValue());
    return Result.ok();
  }

  @DeleteMapping("/{unitId}/params")
  public Result<?> deleteParam(@PathVariable String unitId, @RequestParam String name) {
    deviceService.deleteParam(unitId, name);
    return Result.ok();
  }

  /** 唤醒设备（连接请求）。 */
  @PostMapping("/{unitId}/kick")
  public Result<Map<String, Object>> kick(@PathVariable String unitId, HttpServletRequest request) {
    CpeDevice device = deviceService.findByUnitId(unitId);
    if (device == null) {
      return Result.fail(404, "设备不存在");
    }
    boolean ok = kickService.kick(device);
    webSupport.audit(request, "KICK_DEVICE", unitId, String.valueOf(ok));
    return Result.ok(Map.of("success", ok, "url", String.valueOf(kickService.connectionRequestUrl(device))));
  }
}

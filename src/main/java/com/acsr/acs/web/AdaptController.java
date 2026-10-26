package com.acsr.acs.web;

import com.acsr.acs.adapt.AdaptParamMap;
import com.acsr.acs.adapt.AdaptService;
import com.acsr.acs.adapt.CapabilityTree;
import com.acsr.acs.adapt.DeviceAdapted;
import com.acsr.acs.adapt.DeviceTemplate;
import com.acsr.acs.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 光猫适配库（SQLite，管理端只读为主）。 */
@RestController
@RequestMapping("/api/adapt")
@RequiredArgsConstructor
public class AdaptController {

  private final AdaptService adaptService;

  @GetMapping("/templates")
  public Result<List<DeviceTemplate>> templates() {
    return Result.ok(adaptService.listTemplates());
  }

  @GetMapping("/templates/{id}")
  public Result<DeviceTemplate> template(@PathVariable Long id) {
    return Result.ok(adaptService.getTemplate(id));
  }

  @DeleteMapping("/templates/{id}")
  public Result<?> deleteTemplate(@PathVariable Long id) {
    adaptService.deleteTemplate(id);
    return Result.ok();
  }

  /** 能力树参数清单。 */
  @GetMapping("/templates/{id}/params")
  public Result<Map<String, Object>> params(@PathVariable Long id,
                                            @RequestParam(required = false) String keyword) {
    CapabilityTree tree = adaptService.capability(id);
    List<Map<String, Object>> list = new ArrayList<>();
    tree.getParams().forEach((name, meta) -> {
      if (keyword == null || keyword.isBlank() || name.toLowerCase().contains(keyword.toLowerCase())) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", meta.getName());
        m.put("writable", meta.isWritable());
        m.put("type", meta.getType());
        list.add(m);
      }
    });
    return Result.ok(Map.of("total", tree.size(), "list", list));
  }

  @GetMapping("/param-maps")
  public Result<List<AdaptParamMap>> paramMaps(@RequestParam Long templateId) {
    return Result.ok(adaptService.paramMaps(templateId));
  }

  @PostMapping("/param-maps")
  public Result<?> saveParamMap(@RequestBody AdaptParamMap map) {
    adaptService.saveParamMap(map);
    return Result.ok();
  }

  @DeleteMapping("/param-maps/{id}")
  public Result<?> deleteParamMap(@PathVariable Long id) {
    adaptService.deleteParamMap(id);
    return Result.ok();
  }

  /** 已适配设备命中记录。 */
  @GetMapping("/devices")
  public Result<List<DeviceAdapted>> devices() {
    return Result.ok(adaptService.listAdapted());
  }
}

package com.acsr.acs.web;

import com.acsr.acs.adapt.AdaptService;
import com.acsr.acs.common.Result;
import com.acsr.acs.cwmp.CwmpEngine;
import com.acsr.acs.service.DispatchService;
import com.acsr.acs.service.DeviceService;
import com.acsr.acs.model.entity.CpeDevice;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 首页概览。 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

  private final DeviceService deviceService;
  private final DispatchService dispatchService;
  private final AdaptService adaptService;
  private final CwmpEngine engine;

  @GetMapping("/summary")
  public Result<Map<String, Object>> summary() {
    Map<String, Object> data = new LinkedHashMap<>();
    long total = deviceService.page(1, 1, null, null).getTotal();
    long online = deviceService.page(1, 1, null, 1).getTotal();
    data.put("deviceTotal", total);
    data.put("deviceOnline", online);
    data.put("deviceOffline", total - online);
    Map<String, Object> tasks = new LinkedHashMap<>();
    for (String s : List.of(DispatchService.PENDING, DispatchService.SENDING,
        DispatchService.WAIT_RESULT, DispatchService.SUCCESS, DispatchService.FAILED,
        DispatchService.TIMEOUT, DispatchService.CANCELED)) {
      tasks.put(s, dispatchService.countByStatus(s));
    }
    data.put("tasks", tasks);
    data.put("templateCount", adaptService.listTemplates().size());
    data.put("activeSessions", engine.sessionSnapshot().size());
    return Result.ok(data);
  }

  /** 最近上线设备。 */
  @GetMapping("/recent")
  public Result<List<CpeDevice>> recent() {
    return Result.ok(deviceService.page(1, 10, null, null).getRecords());
  }
}

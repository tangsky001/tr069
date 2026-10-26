package com.acsr.acs.web;

import com.acsr.acs.common.Result;
import com.acsr.acs.mapper.CwmpLogMapper;
import com.acsr.acs.mapper.DeviceSyslogMapper;
import com.acsr.acs.mapper.OpAuditMapper;
import com.acsr.acs.model.entity.CwmpLog;
import com.acsr.acs.model.entity.DeviceSyslog;
import com.acsr.acs.model.entity.OpAudit;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 日志查询：协议交互日志、审计、设备 syslog。 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

  private final CwmpLogMapper cwmpLogMapper;
  private final OpAuditMapper opAuditMapper;
  private final DeviceSyslogMapper syslogMapper;

  @GetMapping("/cwmp")
  public Result<IPage<CwmpLog>> cwmp(@RequestParam(defaultValue = "1") long current,
                                     @RequestParam(defaultValue = "50") long size,
                                     @RequestParam(required = false) String unitId,
                                     @RequestParam(required = false) String direction,
                                     @RequestParam(required = false) String method) {
    LambdaQueryWrapper<CwmpLog> q = new LambdaQueryWrapper<>();
    if (StringUtils.hasText(unitId)) {
      q.like(CwmpLog::getUnitId, unitId);
    }
    if (StringUtils.hasText(direction)) {
      q.eq(CwmpLog::getDirection, direction);
    }
    if (StringUtils.hasText(method)) {
      q.eq(CwmpLog::getMethod, method);
    }
    q.orderByDesc(CwmpLog::getId);
    return Result.ok(cwmpLogMapper.selectPage(new Page<>(current, size), q));
  }

  @GetMapping("/audit")
  public Result<IPage<OpAudit>> audit(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "50") long size) {
    LambdaQueryWrapper<OpAudit> q = new LambdaQueryWrapper<>();
    q.orderByDesc(OpAudit::getId);
    return Result.ok(opAuditMapper.selectPage(new Page<>(current, size), q));
  }

  @GetMapping("/syslog")
  public Result<IPage<DeviceSyslog>> syslog(@RequestParam(defaultValue = "1") long current,
                                            @RequestParam(defaultValue = "50") long size,
                                            @RequestParam(required = false) String unitId) {
    LambdaQueryWrapper<DeviceSyslog> q = new LambdaQueryWrapper<>();
    if (StringUtils.hasText(unitId)) {
      q.like(DeviceSyslog::getUnitId, unitId);
    }
    q.orderByDesc(DeviceSyslog::getId);
    return Result.ok(syslogMapper.selectPage(new Page<>(current, size), q));
  }
}

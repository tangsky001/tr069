package com.acsr.acs.service;

import com.acsr.acs.mapper.DispatchTaskMapper;
import com.acsr.acs.model.entity.DispatchTask;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 下发任务队列（台账在 MySQL）。
 *
 * <p>状态机：PENDING -> QUEUED -> SENDING -> (WAIT_RESULT) -> SUCCESS / FAILED / TIMEOUT / CANCELED
 */
@Slf4j
@Service
public class DispatchService {

  public static final String PENDING = "PENDING";
  public static final String QUEUED = "QUEUED";
  public static final String SENDING = "SENDING";
  public static final String WAIT_RESULT = "WAIT_RESULT";
  public static final String SUCCESS = "SUCCESS";
  public static final String FAILED = "FAILED";
  public static final String TIMEOUT = "TIMEOUT";
  public static final String CANCELED = "CANCELED";

  private final DispatchTaskMapper taskMapper;
  private final JdbcTemplate jdbc;

  public DispatchService(DispatchTaskMapper taskMapper,
                         @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
    this.taskMapper = taskMapper;
    this.jdbc = jdbc;
  }

  /** 创建任务（PENDING）。 */
  public DispatchTask create(String unitId, String serialNumber, String rpcName,
                             String paramPayload, Long createdBy) {
    DispatchTask task = new DispatchTask();
    task.setTaskNo("T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
    task.setUnitId(unitId);
    task.setSerialNumber(serialNumber);
    task.setRpcName(rpcName);
    task.setParamPayload(paramPayload);
    task.setStatus(PENDING);
    task.setCreatedBy(createdBy);
    task.setCreateTime(LocalDateTime.now());
    taskMapper.insert(task);
    return task;
  }

  /** 取该设备最早一个待执行任务。 */
  public DispatchTask next(String unitId) {
    List<DispatchTask> list = taskMapper.selectList(new LambdaQueryWrapper<DispatchTask>()
        .eq(DispatchTask::getUnitId, unitId)
        .in(DispatchTask::getStatus, List.of(PENDING, QUEUED))
        .orderByAsc(DispatchTask::getId)
        .last("LIMIT 1"));
    return list.isEmpty() ? null : list.get(0);
  }

  public void markSending(DispatchTask task) {
    jdbc.update("UPDATE dispatch_task SET status=? WHERE id=? AND status IN (?,?)",
        SENDING, task.getId(), PENDING, QUEUED);
    task.setStatus(SENDING);
  }

  /** 取等待 TransferComplete 的任务。 */
  public DispatchTask findWaiting(String unitId) {
    List<DispatchTask> list = taskMapper.selectList(new LambdaQueryWrapper<DispatchTask>()
        .eq(DispatchTask::getUnitId, unitId)
        .eq(DispatchTask::getStatus, WAIT_RESULT)
        .orderByAsc(DispatchTask::getId)
        .last("LIMIT 1"));
    return list.isEmpty() ? null : list.get(0);
  }

  public void markWaitResult(DispatchTask task) {
    jdbc.update("UPDATE dispatch_task SET status=? WHERE id=?", WAIT_RESULT, task.getId());
    task.setStatus(WAIT_RESULT);
  }

  public void finish(DispatchTask task, String status, String result, String failReason) {
    Long cost = task.getCreateTime() == null ? null
        : java.time.Duration.between(task.getCreateTime(), LocalDateTime.now()).toMillis();
    jdbc.update(
        "UPDATE dispatch_task SET status=?, result_payload=?, fail_reason=?, cost_ms=?, finish_time=NOW() WHERE id=?",
        status, result, failReason, cost, task.getId());
    task.setStatus(status);
    task.setResultPayload(result);
    task.setFailReason(failReason);
    task.setFinishTime(LocalDateTime.now());
  }

  /** 批量取消。 */
  public int cancel(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    int n = 0;
    for (Long id : ids) {
      n += jdbc.update("UPDATE dispatch_task SET status=?, finish_time=NOW() WHERE id=? AND status IN (?,?,?)",
          CANCELED, id, PENDING, QUEUED, WAIT_RESULT);
    }
    return n;
  }

  /** 超时任务置为 TIMEOUT。 */
  public int timeout(int seconds) {
    return jdbc.update(
        "UPDATE dispatch_task SET status=?, fail_reason='等待设备响应超时', finish_time=NOW() "
            + "WHERE status IN (?,?,?) AND create_time < DATE_SUB(NOW(), INTERVAL ? SECOND)",
        TIMEOUT, PENDING, SENDING, WAIT_RESULT, seconds);
  }

  public IPage<DispatchTask> page(long current, long size, String unitId, String status, String rpcName) {
    LambdaQueryWrapper<DispatchTask> q = new LambdaQueryWrapper<>();
    if (StringUtils.hasText(unitId)) {
      q.like(DispatchTask::getUnitId, unitId);
    }
    if (StringUtils.hasText(status)) {
      q.eq(DispatchTask::getStatus, status);
    }
    if (StringUtils.hasText(rpcName)) {
      q.eq(DispatchTask::getRpcName, rpcName);
    }
    q.orderByDesc(DispatchTask::getId);
    return taskMapper.selectPage(new Page<>(current, size), q);
  }

  public DispatchTask get(Long id) {
    return taskMapper.selectById(id);
  }

  /** 统计概览。 */
  public long countByStatus(String status) {
    Long n = taskMapper.selectCount(new LambdaQueryWrapper<DispatchTask>()
        .eq(DispatchTask::getStatus, status));
    return n == null ? 0 : n;
  }
}

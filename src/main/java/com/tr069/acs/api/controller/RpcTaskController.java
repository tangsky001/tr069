package com.tr069.acs.api.controller;

import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.common.Result;
import com.tr069.acs.queue.RpcTaskQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下发指令的结果查询。
 *
 * <p>指令提交后不再同步等待设备回应，调用方凭任务号查询结果，
 * 状态包括 queued / running / success / fail。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/rpc")
@RequiredArgsConstructor
public class RpcTaskController {

    private final RpcDispatchService rpcDispatchService;
    private final RpcTaskQueue rpcTaskQueue;

    /**
     * 按任务号查询执行结果。
     */
    @GetMapping("/{taskId}")
    public Result<RpcTaskQueue.RpcTaskResult> result(@PathVariable long taskId) {
        RpcTaskQueue.RpcTaskResult result = rpcDispatchService.queryResult(taskId);
        if (result == null) {
            return Result.failure("任务不存在或结果已过期: " + taskId);
        }
        return Result.success(result);
    }

    /**
     * 某台设备当前排队待下发的指令数量。
     */
    @GetMapping("/device/{sn}/pending")
    public Result<Long> pending(@PathVariable String sn) {
        return Result.success(rpcTaskQueue.pendingCount(sn));
    }
}

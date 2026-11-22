/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.model;

import lombok.Data;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** 会话内一个任务的执行上下文：任务被拆成一串 RPC 步骤，逐步下发 */
@Data
public class TaskRun {

    private long taskId;
    private String taskNo;
    private String type;
    private Map<String, Object> params = new LinkedHashMap<>();
    /** 待执行的 RPC 步骤 */
    private final Deque<RpcStep> steps = new ArrayDeque<>();
    /** 步骤执行中收集的数据 */
    private Map<String, Object> collected = new LinkedHashMap<>();
    private int sentSteps;
    private volatile boolean completed;

    public void markCompleted() {
        this.completed = true;
    }

    public boolean isCompleted() {
        return completed;
    }

    public RpcStep peek() {
        return steps.peek();
    }

    public RpcStep poll() {
        sentSteps++;
        return steps.poll();
    }

    public boolean finished() {
        return steps.isEmpty();
    }

    public record RpcStep(RpcRequest request, String purpose) {
    }
}

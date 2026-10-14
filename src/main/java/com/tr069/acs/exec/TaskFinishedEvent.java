package com.tr069.acs.exec;

import com.tr069.acs.exec.entity.DeviceTask;
import org.springframework.context.ApplicationEvent;

/**
 * 下发任务结束事件（成功、失败、被拒绝都会发出）。
 *
 * <p>北向 Webhook 订阅它，把结果推给上层业务系统；管理端的应用日志也可以挂上去。
 * 用 Spring 事件是为了让北向模块与下发执行器不必互相依赖。</p>
 */
public class TaskFinishedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final transient DeviceTask task;

    public TaskFinishedEvent(Object source, DeviceTask task) {
        super(source);
        this.task = task;
    }

    /**
     * 结束时的任务快照，含最终状态与失败原因。
     */
    public DeviceTask getTask() {
        return task;
    }
}

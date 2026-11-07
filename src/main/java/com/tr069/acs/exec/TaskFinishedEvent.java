package com.tr069.acs.exec;

import com.tr069.acs.exec.entity.DeviceTask;
import org.springframework.context.ApplicationEvent;

/** 下发任务结束事件（成功、失败、被拒绝都会发出） */
public class TaskFinishedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final transient DeviceTask task;

    public TaskFinishedEvent(Object source, DeviceTask task) {
        super(source);
        this.task = task;
    }

    /** 结束时的任务快照，含最终状态与失败原因 */
    public DeviceTask getTask() {
        return task;
    }
}

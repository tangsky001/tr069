/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.queue;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 呼叫队列：队列号 → 有序目标分机（总台），成员分机限定使用者（空=所有分机） */
@Data
@TableName("sys_sip_queue")
public class SipQueue {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 拨打的队列号（如 0/1/9） */
    private String queueNo;

    /** 队列名称（如 总台8888） */
    private String queueName;

    /** 有序目标分机，逗号分隔（如 8888,9999,6666），忙音自动顺延下一目标 */
    private String targets;

    /** 允许呼叫本队列的分机，逗号分隔，空=不限制 */
    private String members;
}

/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.record;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** SIP 通话记录 */
@Data
@TableName("sys_sip_call_record")
public class SipCallRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 主叫分机 */
    private String caller;

    /** 被叫分机（队列呼叫为实际接通的分机） */
    private String callee;

    /** 呼叫发起时间 */
    private LocalDateTime startedAt;

    /** 是否接通 */
    private boolean answered;

    /** 通话时长（秒），未接通为 0 */
    private int durationSeconds;

    /** 结束原因（如 正常挂断/振铃超时/486 忙） */
    private String endReason;

    /** 录音文件相对路径（未接通或关闭录音为空） */
    private String recordPath;
}

/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.queue;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 呼叫队列 Mapper */
@Mapper
public interface SipQueueMapper extends BaseMapper<SipQueue> {
}

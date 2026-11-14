/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.912
 *
 */

package com.PilotACS.mapper;

import com.PilotACS.entity.SysOperLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

// 操作日志 Mapper
@Mapper
public interface SysOperLogMapper extends BaseMapper<SysOperLog> {
}

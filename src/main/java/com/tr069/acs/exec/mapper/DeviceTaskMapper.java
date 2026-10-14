package com.tr069.acs.exec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.exec.entity.DeviceTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 下发任务数据访问。
 */
@Mapper
public interface DeviceTaskMapper extends BaseMapper<DeviceTask> {
}

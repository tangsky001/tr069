package com.tr069.acs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.domain.entity.WorklistStepRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单步骤明细数据访问。
 */
@Mapper
public interface WorklistStepMapper extends BaseMapper<WorklistStepRecord> {
}

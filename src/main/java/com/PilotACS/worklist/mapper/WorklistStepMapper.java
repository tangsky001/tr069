package com.PilotACS.worklist.mapper;

import com.PilotACS.worklist.entity.WorklistStepRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 工单执行步骤 Mapper */
@Mapper
public interface WorklistStepMapper extends BaseMapper<WorklistStepRecord> {
}

package com.PilotACS.worklist.mapper;

import com.PilotACS.worklist.entity.Worklist;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 工单实例 Mapper */
@Mapper
public interface WorklistMapper extends BaseMapper<Worklist> {
}

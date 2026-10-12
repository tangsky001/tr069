package com.tr069.acs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.domain.entity.Worklist;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单实例数据访问。
 */
@Mapper
public interface WorklistMapper extends BaseMapper<Worklist> {
}

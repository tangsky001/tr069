package com.tr069.acs.olt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.olt.entity.OltBoard;
import org.apache.ibatis.annotations.Mapper;

/**
 * OLT 板卡数据访问。
 */
@Mapper
public interface OltBoardMapper extends BaseMapper<OltBoard> {
}

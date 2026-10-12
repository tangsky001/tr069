package com.tr069.acs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.domain.entity.RpcRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * RPC 记录数据访问。
 */
@Mapper
public interface RpcRecordMapper extends BaseMapper<RpcRecord> {
}

package com.tr069.acs.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.api.entity.OpsRemoteConfig;
import org.apache.ibatis.annotations.Mapper;

/** 远程运维配置数据访问 */
@Mapper
public interface OpsRemoteConfigMapper extends BaseMapper<OpsRemoteConfig> {
}

package com.tr069.acs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.domain.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理端用户数据访问。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}

package com.tr069.acs.olt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.olt.entity.OltDevice;
import org.apache.ibatis.annotations.Mapper;

/**
 * OLT 设备台账数据访问。
 */
@Mapper
public interface OltDeviceMapper extends BaseMapper<OltDevice> {
}

package com.tr069.acs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.domain.entity.CpeDevice;
import org.apache.ibatis.annotations.Mapper;

/**
 * CPE 设备数据访问。
 */
@Mapper
public interface CpeDeviceMapper extends BaseMapper<CpeDevice> {
}

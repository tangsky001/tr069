/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.770
 *
 */

package com.PilotACS.olt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.PilotACS.olt.entity.OltDevice;
import org.apache.ibatis.annotations.Mapper;

/** OLT 设备台账数据访问 */
@Mapper
public interface OltDeviceMapper extends BaseMapper<OltDevice> {
}

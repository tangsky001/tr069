/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.787
 *
 */

package com.PilotACS.olt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.PilotACS.olt.entity.OltOntRegRecord;
import org.apache.ibatis.annotations.Mapper;

/** ONT 注册记录数据访问 */
@Mapper
public interface OltOntRegRecordMapper extends BaseMapper<OltOntRegRecord> {
}

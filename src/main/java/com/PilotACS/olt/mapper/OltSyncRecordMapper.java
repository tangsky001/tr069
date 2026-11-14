package com.PilotACS.olt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.PilotACS.olt.entity.OltSyncRecord;
import org.apache.ibatis.annotations.Mapper;

/** OLT 采集记录数据访问 */
@Mapper
public interface OltSyncRecordMapper extends BaseMapper<OltSyncRecord> {
}

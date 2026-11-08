package com.tr069.acs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.domain.entity.CpeParameter;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 设备参数能力树数据访问 */
@Mapper
public interface CpeParameterMapper extends BaseMapper<CpeParameter> {

    /** 该设备最近一次采集时间，用于判断是否需要重新采集；从未采集过返回 null */
    @Select("SELECT MAX(collect_time) FROM cpe_parameter WHERE sn = #{sn}")
    LocalDateTime lastCollectTime(@Param("sn") String sn);

    /** 批量写入能力树节点。调用方需保证同一批内 sn 相同且不重复 */
    @Insert("<script>INSERT INTO cpe_parameter "
            + "(sn, param_path, parent_path, node_name, node_type, writable, depth, "
            + "collect_batch, collect_time, created_at, updated_at) VALUES "
            + "<foreach collection='list' item='p' separator=','>"
            + "(#{p.sn}, #{p.paramPath}, #{p.parentPath}, #{p.nodeName}, #{p.nodeType}, "
            + "#{p.writable}, #{p.depth}, #{p.collectBatch}, #{p.collectTime}, NOW(), NOW())"
            + "</foreach></script>")
    int insertBatch(@Param("list") List<CpeParameter> list);

    /** 按路径回填参数值。写成单条 CASE WHEN 而不是逐行 update */
    @Update("<script>UPDATE cpe_parameter SET "
            + "param_value = <foreach collection='list' item='p' open='CASE param_path' close='END'>"
            + " WHEN #{p.paramPath} THEN #{p.paramValue}</foreach>, "
            + "value_type = <foreach collection='list' item='p' open='CASE param_path' close='END'>"
            + " WHEN #{p.paramPath} THEN #{p.valueType}</foreach>, "
            + "updated_at = NOW() "
            + "WHERE sn = #{sn} AND param_path IN "
            + "<foreach collection='list' item='p' open='(' separator=',' close=')'>"
            + "#{p.paramPath}</foreach></script>")
    int updateValues(@Param("sn") String sn, @Param("list") List<CpeParameter> list);

    /**
     * Inform 上报参数 upsert：路径已存在就更新值，不存在就插入。
     * 利用 (sn, param_path) 唯一索引做 ON DUPLICATE KEY UPDATE。
     * 仅写参数值，不触碰能力树结构（node_type / writable / depth 等保持原值或合理默认）。
     */
    @Insert("<script>INSERT INTO cpe_parameter "
            + "(sn, param_path, parent_path, node_name, node_type, writable, depth, "
            + "param_value, value_type, collect_batch, collect_time, created_at, updated_at) VALUES "
            + "<foreach collection='list' item='p' separator=','>"
            + "(#{p.sn}, #{p.paramPath}, #{p.parentPath}, #{p.nodeName}, "
            + "#{p.nodeType}, #{p.writable}, #{p.depth}, "
            + "#{p.paramValue}, #{p.valueType}, #{p.collectBatch}, #{p.collectTime}, NOW(), NOW())"
            + "</foreach>"
            + " ON DUPLICATE KEY UPDATE "
            + "param_value = VALUES(param_value), "
            + "value_type = VALUES(value_type), "
            + "updated_at = NOW()</script>")
    int upsertInformValues(@Param("list") List<CpeParameter> list);
}

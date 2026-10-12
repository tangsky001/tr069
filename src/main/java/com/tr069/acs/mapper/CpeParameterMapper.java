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

/**
 * 设备参数能力树数据访问。
 *
 * <p>一次采集会写入几千行、再分批回填值，逐行 insert/update 会把连接池和日志都压垮，
 * 因此这里提供两条批量语句：</p>
 * <ul>
 *   <li>{@link #insertBatch}：多行 VALUES 单条 INSERT，用于整棵树的一次性写入</li>
 *   <li>{@link #updateValues}：CASE WHEN 单条 UPDATE，用于一批 GetParameterValues 结果的回填</li>
 * </ul>
 */
@Mapper
public interface CpeParameterMapper extends BaseMapper<CpeParameter> {

    /**
     * 该设备最近一次采集时间，用于判断是否需要重新采集；从未采集过返回 null。
     */
    @Select("SELECT MAX(collect_time) FROM cpe_parameter WHERE sn = #{sn}")
    LocalDateTime lastCollectTime(@Param("sn") String sn);

    /**
     * 批量写入能力树节点。调用方需保证同一批内 sn 相同且不重复。
     */
    @Insert("<script>INSERT INTO cpe_parameter "
            + "(sn, param_path, parent_path, node_name, node_type, writable, depth, "
            + "collect_batch, collect_time, created_at, updated_at) VALUES "
            + "<foreach collection='list' item='p' separator=','>"
            + "(#{p.sn}, #{p.paramPath}, #{p.parentPath}, #{p.nodeName}, #{p.nodeType}, "
            + "#{p.writable}, #{p.depth}, #{p.collectBatch}, #{p.collectTime}, NOW(), NOW())"
            + "</foreach></script>")
    int insertBatch(@Param("list") List<CpeParameter> list);

    /**
     * 按路径回填参数值。写成单条 CASE WHEN 而不是逐行 update，
     * 一次 GetParameterValues 只跑一次 SQL。
     */
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
}

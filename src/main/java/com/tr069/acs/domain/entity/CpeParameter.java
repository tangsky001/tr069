package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备参数能力树节点。
 *
 * <p>一次采集的结果：先用 {@code GetParameterNames} 拿到设备支持的全部路径与读写权限，
 * 再用 {@code GetParameterValues} 分批读值回填。对象节点（路径以句点结尾）没有值。</p>
 *
 * <p>按 sn 组织，每台设备一份快照；重新采集时整棵替换，保证树与设备当前能力一致。</p>
 */
@Data
@TableName("cpe_parameter")
public class CpeParameter {

    /**
     * 节点类型：对象
     */
    public static final String TYPE_OBJECT = "object";

    /**
     * 节点类型：普通参数
     */
    public static final String TYPE_PARAM = "param";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sn;

    /**
     * 参数完整路径，对象以句点结尾
     */
    private String paramPath;

    /**
     * 父对象路径，根节点为空串
     */
    private String parentPath;

    /**
     * 路径末段名，便于前端展示与关键字检索
     */
    private String nodeName;

    /**
     * 节点类型：{@link #TYPE_OBJECT} / {@link #TYPE_PARAM}
     */
    private String nodeType;

    /**
     * 是否可写：1 可写，0 只读
     */
    private Integer writable;

    /**
     * 参数当前值
     */
    private String paramValue;

    /**
     * 值类型，如 string / unsignedInt / boolean
     */
    private String valueType;

    /**
     * 层级深度，根节点为 0
     */
    private Integer depth;

    /**
     * 采集批次号
     */
    private String collectBatch;

    private LocalDateTime collectTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

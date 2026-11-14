package com.PilotACS.group.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分组成员绑定关系：把 OLT 挂到某个分组节点上。
 *
 * <p>分组节点有两类，用 group_ref 统一引用：
 * {@code C:110000} = 区划节点（civilCode.csv 自动存在），{@code G:5} = 自定义分组（res_group.id）。
 * member_type 预留类型字段，TR069 设备成体系后加 'DEVICE' 即可复用。</p>
 */
@Data
@TableName("res_group_member")
@Schema(description = "分组成员绑定关系")
public class GroupMember {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "分组节点引用：C:区划编号 或 G:自定义分组 id")
    private String groupRef;

    @Schema(description = "成员类型：OLT（预留 DEVICE）")
    private String memberType;

    @Schema(description = "成员主键：OLT 为 olt_device.id")
    private Long memberId;

    @Schema(description = "绑定时的成员名称快照，设备改名不影响历史记录")
    private String memberLabel;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "绑定时间")
    private LocalDateTime createdAt;

    @Schema(description = "操作人")
    private String createdBy;
}

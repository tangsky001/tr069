/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.842
 *
 */

package com.PilotACS.group.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分组成员绑定关系：把设备挂到某个分组节点上。
 *
 * <p>分组节点用 group_ref 统一引用：{@code ROOT} = 固定根（资源根组），
 * {@code G:5} = 自定义分组（res_group.id）。
 * member_type 区分设备体系：{@code OLT} = OLT 台账（member_id = olt_device.id），
 * {@code ONU} = 光猫（member_ref = 光猫业务标识，如 SN；member_id 恒 0），
 * 光猫设备模块接入前归属关系先行落库。</p>
 */
@Data
@TableName("res_group_member")
@Schema(description = "分组成员绑定关系")
public class GroupMember {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "分组节点引用：ROOT 或 G:自定义分组 id")
    private String groupRef;

    @Schema(description = "成员类型：OLT / ONU（光猫）")
    private String memberType;

    @Schema(description = "成员主键：OLT 为 olt_device.id；ONU 恒 0")
    private Long memberId;

    @Schema(description = "成员业务标识：OLT 同 member_id 字符串；ONU 为光猫标识（如 SN）")
    private String memberRef;

    @Schema(description = "绑定时的成员名称快照，设备改名不影响历史记录")
    private String memberLabel;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "绑定时间")
    private LocalDateTime createdAt;

    @Schema(description = "操作人")
    private String createdBy;
}

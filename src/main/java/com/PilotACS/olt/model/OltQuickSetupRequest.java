/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.284
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

@Data
public class OltQuickSetupRequest {

    @Schema(description = "页面上「统一端口 VLAN 模式」这一步勾没勾")
    private Boolean portModeEnabled = Boolean.TRUE;

    @Schema(description = "要改模式的端口，空就是不改端口")
    private List<PortRef> ports;

    @Schema(description = "端口 VLAN 模式：access / hybrid / trunk")
    private String mode;

    @Schema(description = "trunk、hybrid 放通的 VLAN 列表，写法 100-200,300")
    private String vlanList;

    @Schema(description = "要创建的 VLAN ID，写法 100,200 或 100-105；空就是不建")
    private String createVlanIds;

    @Schema(description = "新建的 VLAN 要不要把 {@link #ports} 加成 tagged 成员")
    private Boolean addCreatedToPorts;

    @Schema(description = "TR-069 模板参数，null 就是不建")
    private OltTr069ProfileRequest tr069;

    @Schema(description = "华为开局参数；不为 null 时整态都按华为那套模板流程走")
    private HuaweiQuickSetupRequest huawei;

    @Data
    public static class PortRef {

        @Schema(description = "端口类型：epon / gpon / ge / xge")
        private String type;

        @Schema(description = "端口号，从 1 开始")
        private Integer no;
    }
}

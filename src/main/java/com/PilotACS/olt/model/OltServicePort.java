/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.940
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/** ONT 业务流 */
@Data
public class OltServicePort {

    @Schema(description = "业务流索引（INDEX），删除/修改时按它定位")
    private Integer index;

    @Schema(description = "用户侧 VLAN ID")
    private Integer vlanId;

    @Schema(description = "用户侧 VLAN（GPON 表格回显 FLOW TYPE=vlan 时的 FLOW PARA，配置脚本里的 user-vlan）")
    private Integer userVlan;

    @Schema(description = "流状态，表格回显最后一列：up / down")
    private String state;

    @Schema(description = "VLAN 属性，回显里的第二列（常见 common / smart / qinq）")
    private String vlanAttr;

    @Schema(description = "端口类型：gpon / epon")
    private String portType;

    private Integer frameNo;

    private Integer slotNo;

    private Integer portNo;

    @Schema(description = "ONT ID（GPON 才有）")
    private Integer ontId;

    @Schema(description = "承载它的 GEM port")
    private Integer gemport;

    public String iface() {
        if (slotNo == null || portNo == null) {
            return null;
        }
        return (frameNo == null ? 0 : frameNo) + "/" + slotNo + "/" + portNo;
    }
}

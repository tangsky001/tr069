/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.657
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltOntInfo {

    private Integer ontId;

    @Schema(description = "认证串（GPON 是 SN，EPON 是 MAC）")
    private String sn;

    @Schema(description = "ONT 型号/描述，如 HG5145F")
    private String desc;

    @Schema(description = "ONT IP 0 地址/掩码，如 10.172.10.17/24")
    private String ip;

    @Schema(description = "回显里明确写了 ONT IP : -（设备没上报管理地址）；没出现 IP 行时为 null，二者要区分开")
    private Boolean ipEmpty;

    @Schema(description = "MAC 地址：部分版本的单台详情回显里有，没有时采集侧按认证码还原")
    private String mac;

    @Schema(description = "线路模板：编号与名称，页面显示成 100 / FTTH-GPON-100")
    private Integer lineProfileId;

    private String lineProfileName;

    @Schema(description = "业务（能力）模板：编号与名称")
    private Integer srvProfileId;

    private String srvProfileName;

    @Schema(description = "最后一次下线原因，如 LOSi/LOBi【光质量差/光纤断】")
    private String lastDownReason;

    private String lastUpTime;

    private String lastDownTime;

    private String onlineDuration;

    private String runState;

    private String configState;

    private String matchState;
}

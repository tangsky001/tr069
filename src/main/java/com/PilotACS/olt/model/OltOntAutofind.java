/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.506
 *
 */
package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltOntAutofind {

    @Schema(description = "回显里的行号（Number），不是注册后的 ONT ID")
    private Integer id;

    @Schema(description = "框/槽/端口，如 0/1/0")
    private String fsp;

    private Integer frameNo;

    private Integer slotNo;

    private Integer portNo;

    @Schema(description = "认证串：GPON 一般就是 SN，EPON 是 MAC")
    private String sn;

    @Schema(description = "Password 认证方式下的密码")
    private String password;

    @Schema(description = "LOID 逻辑 ID")
    private String loid;

    @Schema(description = "认证码（Checkcode），LOID/Password 方式才有")
    private String checkCode;

    @Schema(description = "厂商 ID，如 HWTC；回显里和型号可能挤在一列（HWTC/HG8010H）")
    private String vendorId;

    @Schema(description = "ONT 型号，如 HG8010H")
    private String model;

    @Schema(description = "ONT 硬件版本（回显里的 Ont Version）")
    private String hardwareVersion;

    @Schema(description = "ONT 软件版本（Ont SoftwareVersion）")
    private String softwareVersion;

    @Schema(description = "设备 ID（Ont EquipmentID），部分版本才有")
    private String equipmentId;

    @Schema(description = "最后一次被自动发现的时间，按回显原样保留")
    private String lastTime;

    @Schema(description = "PON 类型：gpon / epon，由下发的查询命令决定")
    private String ponType;
}

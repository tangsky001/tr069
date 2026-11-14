package com.tr069.acs.olt.model;

import lombok.Data;

/** {@code display ont autofind all} 采到的一条待注册 ONT */
@Data
public class OltOntAutofind {

    /** 回显里的行号（Number），不是注册后的 ONT ID */
    private Integer id;

    /** 框/槽/端口，如 0/1/0 */
    private String fsp;

    private Integer frameNo;

    private Integer slotNo;

    private Integer portNo;

    /** 认证串：GPON 一般就是 SN，EPON 是 MAC */
    private String sn;

    /** Password 认证方式下的密码 */
    private String password;

    /** LOID 逻辑 ID */
    private String loid;

    /** 认证码（Checkcode），LOID/Password 方式才有 */
    private String checkCode;

    /** 厂商 ID，如 HWTC；回显里和型号可能挤在一列（HWTC/HG8010H） */
    private String vendorId;

    /** ONT 型号，如 HG8010H */
    private String model;

    /** ONT 硬件版本（回显里的 Ont Version） */
    private String hardwareVersion;

    /** ONT 软件版本（Ont SoftwareVersion） */
    private String softwareVersion;

    /** 设备 ID（Ont EquipmentID），部分版本才有 */
    private String equipmentId;

    /** 最后一次被自动发现的时间，按回显原样保留 */
    private String lastTime;

    /** PON 类型：gpon / epon，由下发的查询命令决定 */
    private String ponType;
}

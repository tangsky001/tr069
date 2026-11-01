package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * {@code display ont autofind all} 采到的一条待注册 ONT。
 *
 * <p>这些 ONT 已经被 OLT 发现（光纤到了、SN 报上来了），但还没被授权到某个端口上，
 * 所以还没有 ONT ID、也没有业务。要在页面上完成注册，就是给它们挑一条模板、
 * 下发 {@code ont confirm} 变成正式 ONT。</p>
 *
 * <p>字段跟设备回显那张表一一对应，同时也就是页面上「ONT 自动发现」那张表要显示的列：
 * ID / 端口 / SN / 逻辑ID密码 / 认证码 / 厂商型号 / 软硬件版本 / 发现时间。</p>
 *
 * <p>这台 ONT 最终会落到哪个 ONT ID 由 {@code ont confirm} 时的 {@code ontid} 决定，
 * 这里拿不到，所以 {@link #id} 只是回显里的行号。</p>
 */
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

/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.909
 *
 */

package com.PilotACS.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** OLT 设备台账 */
@Data
@TableName("olt_device")
public class OltDevice {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** OLT 名称 */
    private String name;

    /** OLT 地址：IP 或域名 */
    private String host;

    /** 登录类型：TELNET / SSH */
    private String protocol;

    /** 登录账号 */
    private String username;

    /** 登录密码 */
    private String password;

    /** 登录端口 */
    private Integer port;

    /** 字符集编码：AUTO / UTF-8 / GBK / GB18030 */
    private String charset;

    /** 绑定本地出口 IP */
    private String bindLocalIp;

    /** 允许访问的 Web 登录账号 */
    private String webAccounts;

    /** 厂商 */
    private String vendor;

    /** 型号 */
    private String model;

    /** 软件版本 */
    private String softwareVersion;

    /** 补丁版本，如 SPC107 */
    private String patchVersion;

    /** 背板/主板型号 */
    private String backplane;

    /** 机框槽位数 */
    private Integer slotCount;

    /** 主控 MAC */
    private String macAddress;

    /** 运行时长 */
    private String uptime;

    /** 状态：UNKNOWN / ONLINE / OFFLINE */
    private String status;

    private LocalDateTime lastConnectAt;

    private LocalDateTime lastSyncAt;

    /** 最近一次失败原因 */
    private String lastError;

    /** 最近一次同步摘要 */
    private String syncSummary;

    /** VLAN 快照（JSON） */
    private String vlanJson;

    /** 模板快照（JSON） */
    private String templateJson;

    private String remark;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

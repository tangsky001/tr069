package com.tr069.acs.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * OLT 设备台账。
 *
 * <p>OLT 不跑 TR-069，纳管方式是在设备上登录命令行（Telnet/SSH）执行查询，
 * 所以这里保存的是「怎么连」以及「连上后采集到什么」两类信息。</p>
 */
@Data
@TableName("olt_device")
public class OltDevice {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** OLT 名称，页面下拉框展示用；留空时按地址生成。 */
    private String name;

    /** OLT 地址：IP 或域名。 */
    private String host;

    /** 登录类型：TELNET / SSH。 */
    private String protocol;

    /** 登录账号。 */
    private String username;

    /** 登录密码，管理端接口不回显。 */
    private String password;

    /** 登录端口。 */
    private Integer port;

    /** 字符集编码：AUTO / UTF-8 / GBK / GB18030。 */
    private String charset;

    /** 绑定本地访问出口 IP，多网卡环境下指定源地址。 */
    private String bindLocalIp;

    /** 允许访问的 Web 登录账号，逗号分隔；为空表示不限制。 */
    private String webAccounts;

    /** 厂商：HUAWEI / ZTE / FIBERHOME / GENERIC。 */
    private String vendor;

    /** 型号，登录后采集。 */
    private String model;

    /** 软件版本，登录后采集。 */
    private String softwareVersion;

    /** 补丁版本，如 SPC107。 */
    private String patchVersion;

    /** 背板/主板型号，如 MA5683T 的 H802MABO。 */
    private String backplane;

    /** 主控 MAC，设备回显里有才采得到。 */
    private String macAddress;

    /** 运行时长（中文），如 59天18小时21分11秒。 */
    private String uptime;

    /** 状态：UNKNOWN / ONLINE / OFFLINE。 */
    private String status;

    private LocalDateTime lastConnectAt;

    private LocalDateTime lastSyncAt;

    /** 最近一次失败原因。 */
    private String lastError;

    /** 最近一次同步摘要。 */
    private String syncSummary;

    /** VLAN 快照（JSON）：VLAN 列表 + 上行口放通情况，随同步整体覆盖 */
    private String vlanJson;

    private String remark;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

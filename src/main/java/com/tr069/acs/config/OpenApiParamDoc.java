package com.tr069.acs.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 在线文档的「参数说明」文字表：请求参数（path / query）一句、响应字段一句。*/
final class OpenApiParamDoc {

    private OpenApiParamDoc() {
    }

    /** 请求参数说明：参数名 → 一句话。 */
    private static final Map<String, String> PARAM = new HashMap<>();

    /**
     * 路径里的 {@code {id}} 指的是什么：把 {@code /{id}} 去掉之后的路径 → 主语。
     *
     * <p>同一个 {@code id} 在不同接口里是 OLT、模板还是工单，只有路径能区分。</p>
     */
    private static final Map<String, String> ID_OWNER = new HashMap<>();

    /** 响应字段说明：字段名 → 一句话。同名字段按最主要的那层含义写。 */
    private static final Map<String, String> FIELD = new HashMap<>();

    /** 通用响应体 {@code Result} 的字段说明。 */
    private static final Map<String, String> RESULT = new HashMap<>();

    static {
        RESULT.put("code", "业务码：`200` 成功、`500` 通用业务失败、`401` 令牌失效、`403` 没权限");
        RESULT.put("message", "提示信息，成功时是 `success`，失败时是可直接展示给用户的中文原因");
        RESULT.put("data", "业务数据，失败时通常为 `null`；下面几行就是这个对象的字段");
        RESULT.put("success", "是否成功，等价于 `code == 200`");
    }

    static {
        // ==================== 请求参数 ====================
        p("token", "登录令牌，等价于请求头 `X-Token`，只在调试时走 query");
        p("id", "主键 id");
        p("sn", "设备序列号（光猫上报的，不含 OUI 前缀）");
        p("sns", "设备序列号，多个用逗号分隔");
        p("taskId", "下发任务号，提交指令 / 下发文件时返回");
        p("taskNo", "调用方的业务单号，用于幂等与按单号查询");
        p("rpcName", "CWMP 方法名，如 GetParameterValues / SetParameterValues / Reboot / Download");
        p("code", "角色编码，对应 `sys_role.code`");
        p("keyword", "关键字，按名称 / 序列号 / 路径模糊匹配，不传就是全部");
        p("page", "页码，从 1 开始");
        p("size", "每页条数");
        p("limit", "最多返回多少条");
        p("isp", "运营商代码：CMCC 移动 / CT 电信 / CU 联通 / CBN 广电 / standard 通用");
        p("deviceType", "设备型号，如 GPON_4+1");
        p("roomNo", "房间号");
        p("status", "状态过滤，如 PENDING / RUNNING / SUCCESS / FAIL");
        p("category", "业务 / 模板分类码，如 WAN_DHCP");
        p("templateId", "业务模板 id");
        p("prefix", "参数路径前缀，如 InternetGatewayDevice.WANDevice.");
        p("parent", "父对象路径，不传返回根节点这一层");
        p("path", "参数完整路径，如 InternetGatewayDevice.DeviceInfo.SerialNumber");
        p("item", "业务项标识，型号能力树里的那一项");
        p("type", "节点类型过滤：object 对象 / parameter 参数");
        p("writable", "是否可写：1 可写、0 只读，不传不过滤");
        p("force", "true 表示忽略最小采集间隔，立即重采");
        p("userId", "只看这个用户的日志，不传看全部");
        p("file", "文件本身（multipart/form-data）");
        p("fileType", "文件类型：FIRMWARE 固件 / CONFIG 配置 / VENDOR 厂商文件，不填按固件");
        p("version", "版本号，固件建议必填");

        ID_OWNER.put("/api/olt", "OLT 台账");
        ID_OWNER.put("/api/files", "文件");
        ID_OWNER.put("/api/config-templates", "配置模板");
        ID_OWNER.put("/api/biz-template", "业务模板");
        ID_OWNER.put("/api/biz-template/executions", "下发记录");
        ID_OWNER.put("/api/worklist/instances", "工单");
        ID_OWNER.put("/openapi/v1/deliveries", "下发任务");
        ID_OWNER.put("/openapi/v1/events/subscriptions", "事件订阅");

        // ==================== 通用响应体 ====================
        f("code", "动作编号，如 `vlan.create`；通用响应体里的 `code` 是业务码，见「鉴权」一节");
        f("message", "提示信息，成功时是 `success`，失败时是可直接展示的中文原因");
        f("data", "业务数据，失败时通常为 `null`");
        f("items", "当前页数据");
        f("total", "总条数");
        f("page", "当前页码，从 1 开始");
        f("size", "每页条数；文件对象里是字节数");

        // ==================== 设备台账 ====================
        f("id", "主键 id");
        f("sn", "设备序列号（不含 OUI 前缀）");
        f("serialNumber", "设备唯一标识，格式 OUI-SN");
        f("oui", "厂商标识（OUI）");
        f("manufacturer", "厂商，取自 DeviceInfo.Manufacturer");
        f("productClass", "设备型号，取自 DeviceInfo.ProductClass");
        f("model", "型号");
        f("modelKey", "型号标识，建议写成 型号_固件，如 HS8145V5_V5R19");
        f("vendor", "厂商代码");
        f("vendorLabel", "厂商中文名");
        f("softwareVersion", "软件版本");
        f("hardwareVersion", "硬件版本");
        f("patchVersion", "补丁版本");
        f("version", "版本号");
        f("domain", "设备类型，如 ADSL_4+1");
        f("deviceType", "设备型号，如 GPON_4+1");
        f("isp", "运营商代码：CMCC / CT / CU / CBN / standard");
        f("ispVersion", "运营商定制版本");
        f("macAddress", "MAC 地址");
        f("uptime", "设备已运行时长");
        f("online", "是否在线");
        f("onlineDuration", "在线时长");
        f("lastContactTime", "最后通信时间");
        f("lastConnectAt", "最近一次连接时间");
        f("lastSyncAt", "最近一次同步时间");
        f("lastOfflineTime", "最后掉线时间，设备不上报时为空");
        f("offlineReason", "掉线原因，设备不上报时为空");
        f("connectionRequestUrl", "反向连接地址，取自 ManagementServer.ConnectionRequestURL");
        f("cwmpVersion", "CWMP 版本，如 cwmp-1-0");
        f("soapInformTimeout", "SOAP Inform 超时（秒）");
        f("informInterval", "定期通知间隔（秒），1-86400");
        f("informEnable", "定期通知开关：true 使能、false 去使能");
        f("rootNode", "根数据模型节点：`InternetGatewayDevice.` 或 `Device.`");
        f("eventCode", "事件码，如 `0 BOOTSTRAP`、`2 PERIODIC`、`6 CONNECTION REQUEST`");
        f("eventList", "本次 Inform 上报的事件列表");
        f("retryCount", "已重试次数");
        f("deleted", "是否已删除");
        f("roomNo", "房间号，下发时替换模板里的 `%room%` 变量");
        f("remark", "备注");
        f("createdAt", "创建时间");
        f("updatedAt", "更新时间");
        f("createdBy", "创建人");
        f("operator", "操作人");
        f("name", "名称：模板名、接口名、对象名，看所在对象");
        f("description", "描述");
        f("desc", "型号 / 描述");
        f("summary", "规格说明，如 `8 × GPON + 4 × GE + 4 × XGE`");
        f("status", "状态");
        f("state", "状态，如 up / down");
        f("type", "类型，看所在对象（DBA type1~type5、节点类型、文件类型等）");
        f("label", "页面显示名，如 PON01 / GE03 / XGE01");
        f("value", "值");
        f("index", "序号");
        f("comment", "备注");

        // ==================== 用户与登录 ====================
        f("nickname", "昵称");
        f("role", "角色编码，对应 `sys_role.code`");
        f("roleCode", "角色编码");
        f("username", "用户名");
        f("password", "密码，只写不读，不回显");
        f("hasPassword", "是否设过密码（密码本身不回显）");
        f("permission", "权限点，如 `device:view`");
        f("lastLoginAt", "最近登录时间");
        f("lastLoginIp", "最近登录 IP");
        f("lastLoginSource", "最近登录来源：WEB / CLIENT / MOBILE");
        f("source", "来源：WEB / CLIENT / MOBILE / OPENAPI");

        // ==================== 能力树与业务快照 ====================
        f("nodeName", "路径末段名，便于展示与检索");
        f("nodeType", "节点类型：object / parameter");
        f("paramPath", "参数完整路径，对象以句点结尾");
        f("paramValue", "参数当前值");
        f("parentPath", "父对象路径，根节点为空串");
        f("depth", "层级深度，根节点为 0");
        f("writable", "是否可写：1 可写、0 只读");
        f("valueType", "值类型，如 string / unsignedInt / boolean");
        f("fields", "意图字段，如 ip_protocol / vlan_used / port_bind");
        f("field", "字段名，或 `param:参数路径`");
        f("collectBatch", "采集批次号");
        f("collectTime", "采集时间");
        f("collectedAt", "采集时间，页面显示「快照时间」");
        f("oltId", "所属 OLT 的 id");
        f("deviceId", "设备 id");
        f("device", "设备台账信息");
        f("ssid", "SSID 名称");
        f("band", "频段：2.4G / 5.8G");
        f("instance", "实例号");
        f("port", "端口");

        // ==================== 指令 / 任务 / 工单 ====================
        f("taskNo", "外部业务单号，与来源组成唯一键，重复提交据此幂等");
        f("rpc", "CWMP 方法名");
        f("rpcId", "RPC 记录 id");
        f("parameters", "下发参数，JSON");
        f("params", "参数键值对");
        f("parameterList", "Inform 上报的参数列表");
        f("parameterName", "参数名");
        f("setParameterValuesFaultList", "SetParameterValues 里失败参数的明细");
        f("faultCode", "CWMP 故障码，如 8000 / 9001");
        f("faultString", "故障描述");
        f("soapFaultCode", "SOAP faultcode，如 SOAP-ENV:Client");
        f("msgType", "报文类型：Inform / InformResponse / GetParameterValues …");
        f("contentBody", "报文正文");
        f("currentTime", "当前时间");
        f("success", "是否成功");
        f("errorMsg", "错误信息");
        f("errorMessage", "失败原因");
        f("costMs", "耗时（毫秒）");
        f("rawOutput", "本次交互回显（截断），页面可折叠查看");
        f("rawPreview", "命令回显片段");
        f("raw", "命令原始回显");
        f("outputs", "每条命令的回显");
        f("finishedAt", "结束时间");
        f("worklistId", "所属工单 id");
        f("worklistName", "工单名称");
        f("worklistType", "工单类型：physic / logical");
        f("worklistDesc", "工单唯一描述，由调用方流水号构成，用于幂等");
        f("timeInit", "建单时间");
        f("timeExecStart", "开始执行时间");
        f("timeExecFinish", "执行完成时间");
        f("timeReserve", "预约时间");
        f("timeBind", "绑定时间");
        f("stepIndex", "步骤序号，从 0 开始");
        f("durationMs", "这一步耗时（毫秒）");
        f("statusLevel", "告警级别");
        f("statusText", "状态文字");
        f("prompt", "提示信息");

        // ==================== 业务模板与配置模板 ====================
        f("templateId", "模板 id");
        f("templateName", "模板名");
        f("templateKey", "配置模板 key");
        f("template", "配置模板");
        f("category", "分类码，如 WAN_DHCP");
        f("categoryLabel", "分类中文名");
        f("doc", "模板说明摘要");
        f("itemOrder", "成员执行顺序，从 0 开始");
        f("continueOnError", "某个成员失败后是否继续后面的成员");
        f("available", "配置模板是否还存在且能正常解析");
        f("executable", "是否已迁移成可下发的执行步骤");
        f("issue", "不可用 / 不可执行的原因");
        f("intentFields", "意图字段快照 JSON");
        f("plan", "渲染出的下发计划 JSON");
        f("result", "执行结果");
        f("resultContent", "执行结果内容");
        f("currentKey", "正在执行的成员配置模板 key");
        f("totalCount", "成员总数");
        f("finishedCount", "已完成条数");
        f("successCount", "成功条数");
        f("failCount", "失败条数");
        f("groupName", "业务分组");
        f("query", "是不是查询类动作");
        f("commands", "真正下发的命令（含进视图与收尾的 exit）");
        f("command", "实际下发的命令");
        f("commandKey", "命令标识");
        f("action", "子动作：optical 光信息 / version 版本 / register 注册历史 / config 授权与业务流 / info 管理 IP");
        f("detail", "执行后重采的最新详情；查询类动作为 null");

        // ==================== 文件 ====================
        f("fileType", "FIRMWARE 固件 / CONFIG 配置 / VENDOR 厂商文件");
        f("sha256", "文件内容的 SHA-256");
        f("storePath", "相对存储目录的文件路径，不对外暴露");

        // ==================== 事件回调 ====================
        f("webhookId", "订阅 id");
        f("eventType", "事件类型");
        f("eventTypes", "订阅的事件类型，多个用逗号分隔，`*` 表示全部");
        f("eventId", "事件 id");
        f("payload", "推送内容");
        f("url", "回调地址");
        f("secret", "回调签名密钥，空表示不签名");
        f("enabled", "是否启用");
        f("responseCode", "对方返回的 HTTP 状态码");
        f("responseBody", "对方返回的响应体");
        f("attempts", "已投递次数");
        f("nextRetryAt", "下次重试时间");

        // ==================== OLT 台账与板卡 ====================
        f("host", "OLT 地址：IP 或域名");
        f("protocol", "连接协议：TELNET / SSH");
        f("charset", "字符集：AUTO / UTF-8 / GBK / GB18030");
        f("cliStyle", "命令风格：DISPLAY（华为）/ SHOW（第三方）");
        f("bindLocalIp", "绑定本地访问出口 IP");
        f("ponType", "PON 口类型：GPON / EPON");
        f("ponCount", "PON 口数");
        f("geCount", "上联 GE 口数");
        f("xgeCount", "上联 10GE 口数");
        f("slotCount", "机框槽位数，机框视图按它画槽位");
        f("controlBoard", "主控板槽位");
        f("backplane", "背板带宽");
        f("hasConsole", "有没有 CONSOLE 口");
        f("hasNms", "有没有独立 NMS 管理口");
        f("hasUsb", "有没有 USB 口");
        f("webAccounts", "允许访问的 Web 登录账号，空表示不限制");
        f("syncSummary", "本次同步摘要");
        f("deepSyncing", "后台仍在补采慢数据（端口状态 / ONT / VLAN / 模板），客户端应轮询到它变 false");
        f("boards", "板卡列表");
        f("boardCount", "板卡数量");
        f("boardType", "板卡类型，如 H901GPHF / GTGO");
        f("boardKind", "板卡类别：SERVICE / CONTROL / UPLINK / POWER / INTERFACE");
        f("frontPanel", "前面板信息，只有一体化整机（鸿升光）才有");
        f("panelKind", "面板类型");
        f("slotNo", "槽位号");
        f("cpuUsage", "CPU 使用率");
        f("memUsage", "内存使用率");
        f("temperature", "温度（℃）");
        f("syncedAt", "状态刷新时间");

        // ==================== OLT 端口与 ONT ====================
        f("ports", "端口列表");
        f("portCount", "端口数量");
        f("portNo", "PON 端口号");
        f("portType", "端口类型：gpon / epon");
        f("portMode", "端口类型：GPON / EPON / PON");
        f("portName", "端口名，如 P0");
        f("no", "端口号，从 1 开始");
        f("linkStatus", "链路状态：online / offline，没采到为 null");
        f("opticalPresent", "光模块是否在位");
        f("opticStatus", "设备原始光模块状态：normal / absence / abnormal / mismatch");
        f("opticTemperature", "光模块温度");
        f("moduleStatus", "光模块状态：Online / Offline");
        f("laserState", "激光器状态：Normal / Abnormal");
        f("biasCurrent", "偏置电流，如 92 mA");
        f("supplyVoltage", "供电电压，如 3.22 V");
        f("txPower", "发光功率，如 4.23 dBm");
        f("rxPower", "接收光功率，如 -21.4267 dBm");
        f("txFault", "发送故障：Normal / Fault");
        f("portsJson", "端口光模块状态快照（JSON 数组）");
        f("onts", "该端口下的 ONT 列表（在线不在线都在）");
        f("onuTotal", "该端口下的 ONT 总数");
        f("onuOnline", "其中在线的 ONT 数");
        f("ontId", "ONT ID，同一端口内唯一");
        f("frameNo", "机框号，一般是 0");
        f("runState", "Run state：online / offline（EPON 写 up / down）");
        f("configState", "Config state：normal / initial / failed");
        f("matchState", "Match state：match / mismatch");
        f("controlFlag", "Control flag：active / deactive");
        f("authType", "认证类型：digest / basic / None");
        f("authorized", "是否已通过认证（回显 Auth 列 TRUE/FALSE）");
        f("encryption", "加密方式，如 `AESEncryption`");
        f("registerTime", "注册（上线）时间，回显原样保留");
        f("lastUpTime", "最后上线时间");
        f("lastDownTime", "最后下线时间");
        f("lastDownReason", "最后一次下线原因，如 LOSi / LOBi");
        f("distance", "逻辑距离（米）");
        f("onuType", "ONU 类型，如 `1ge3fe`");
        f("ethPorts", "网口数量上限，没解析到时页面显示「自适应」");
        f("potsPorts", "电话口（POTS）数量上限");
        f("ontInfo", "ONT 关键信息（IP / 线路模板 / 业务模板 / 掉线原因），只有查 info 时带");
        f("servicePorts", "业务流（service-port）列表，只有查 config 时带");
        f("userVlan", "业务流里的内层 VLAN（user-vlan）");
        f("servPortNum", "业务虚端口个数");
        f("stdPortNum", "标准口个数");
        f("portInstance", "端口实例号");
        f("records", "记录列表");
        f("physical", "物理状态 up / down");

        // ==================== OLT 模板与带宽 ====================
        f("lineProfileId", "线路模板 ID");
        f("lineProfileName", "线路模板名");
        f("lineProfiles", "线路模板列表");
        f("srvProfileId", "业务（能力）模板 ID");
        f("srvProfileName", "业务（能力）模板名");
        f("srvProfiles", "ONT 能力模板列表");
        f("dbaProfileId", "引用的 DBA 模板 ID");
        f("dbaProfiles", "DBA 模板列表");
        f("profileId", "模板 ID");
        f("profileName", "模板名");
        f("bindTimes", "已绑定这个模板的 ONU 数，厂商不回显时为 null");
        f("assureKbps", "保证带宽（kbps）");
        f("fixKbps", "固定带宽（kbps）");
        f("maxKbps", "最大带宽（kbps）");
        f("maxEnvelopes", "最大信元数");
        f("cir", "承诺速率 CIR（kbps）");
        f("pir", "峰值速率 PIR（kbps）");
        f("trafficTables", "流量限速模板列表");
        f("tcontId", "承载它的 T-CONT");
        f("tcontCount", "T-CONT 个数");
        f("tconts", "T-CONT 列表");
        f("gemport", "承载它的 GEM port");
        f("gemportId", "GEM port 编号");
        f("gemportCount", "GEM port 个数");
        f("gemports", "GEM port 列表");
        f("mappingId", "GEM 内的映射序号");
        f("mappingMode", "映射方式：VLAN / 802.1p / Port");
        f("mappings", "GEM 内映射列表");

        // ==================== OLT VLAN ====================
        f("vlan", "VLAN 快照，还没采过为 null");
        f("vlans", "该口放通的 VLAN，升序，连续段页面压成 45-46 显示");
        f("vlanId", "VLAN ID");
        f("vlanIds", "VLAN ID 表达式，支持 `,` 与 `-`，如 `100-200,300`");
        f("vlanList", "trunk / hybrid 放通的 VLAN 列表，如 `100-200,300`");
        f("endVlanId", "结束 VLAN ID（兼容旧表单）");
        f("vlanAttr", "VLAN 属性，如 common / smart / qinq");
        f("vlanConNum", "VLAN-Con 个数，回显为「-」时为 null");
        f("nativeVlan", "Native VLAN（PVID），回显里没有这一行时为 null");
        f("tagged", "打进 Tagged 的端口，写法 `epon1` / `ge3`");
        f("untagged", "打进 Untagged 的端口");
        f("taggedPorts", "带标签的成员端口");
        f("untaggedPorts", "不带标签的成员端口");
        f("memberPorts", "可做 VLAN 成员的物理端口");
        f("portVlans", "主控口 / 上行口的 VLAN（回显里的「透传 VLAN」）");
        f("defaultVlanPorts", "缺省 VLAN 成员端口");
        f("createVlanIds", "要创建的 VLAN ID，写法 `100,200` 或 `100-105`，空就是不建");
        f("addCreatedToPorts", "新建的 VLAN 是否顺带把 ports 加成 tagged 成员");
        f("portModeEnabled", "是否要改端口 VLAN 模式，false 时 ports 里的内容会被忽略");
        f("mode", "端口 VLAN 模式：access / hybrid / trunk");
        f("tr069", "TR-069 模板参数，null 表示不建");
        f("tr069Profiles", "TR-069（ACS）模板列表");
        f("acsPassword", "ACS 服务器密码");
        f("acsUsername", "ACS 服务器用户名（光猫注册到 ACS 用的）");
        f("acsToCpeName", "ACS 反向连接光猫用的账号");
        f("acsToCpePassword", "上面那个账号的密码");
        f("cpeToAcsName", "光猫访问 ACS 用的用户名");
        f("cpeToAcsPassword", "上面那个账号的密码");

        // ==================== OLT 三层接口与路由 ====================
        f("ipInterfaces", "三层 VLAN 接口（vlanif）列表");
        f("ipAddress", "接口 IP");
        f("ip", "IP 地址 / 掩码");
        f("mask", "子网掩码");
        f("prefixLength", "掩码位数，如 24");
        f("iface", "接口标识，如 0/6/0，和命令行一致");
        f("staticRoutes", "静态路由列表");
        f("destination", "目的网络 / 掩码");
        f("nextHop", "下一跳网关");
        f("cost", "路由开销");
        f("preference", "优先级");

        // ==================== 型号适配 ====================
        f("capLanPorts", "适配申报网口数量");
        f("capLanSpeed", "适配申报网口速率");
        f("capSipCount", "适配申报 SIP 数量");
        f("capWifiSpec", "适配申报 WiFi 规格");
        f("sampleSn", "适配时参考的样机序列号");
    }

    private static void p(String name, String text) {
        PARAM.put(name, text);
    }

    private static void f(String name, String text) {
        FIELD.put(name, text);
    }

    /**
     * 取请求参数的说明。
     *
     * @param key  `方法 路径`，如 {@code GET /api/olt/{id}}
     * @param name 参数名
     * @return 说明，没有就返回 null（界面上那一格留空）
     */
    static String param(String key, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        if ("id".equals(name) && key != null) {
            int cut = key.lastIndexOf("/{");
            if (cut > 0) {
                String owner = ID_OWNER.get(key.substring(key.indexOf(' ') + 1, cut));
                if (owner != null) {
                    return owner + " id";
                }
            }
        }
        return PARAM.get(name);
    }

    /** 取响应字段的说明，没有就返回 null。 */
    static String field(String name) {
        return name == null ? null : FIELD.get(name);
    }

    /** 取通用响应体 {@code Result} 字段的说明，没有就返回 null。 */
    static String resultField(String name) {
        return name == null ? null : RESULT.get(name);
    }

    /** 响应状态码的说明，灌进「响应状态」那张表。 */
    static String status(String httpCode) {
        if ("200".equals(httpCode)) {
            return "接口正常返回；业务是否成功看响应体里的 code，200 才算成功";
        }
        if ("401".equals(httpCode)) {
            return "没带令牌或令牌已失效，响应体里业务码也是 401";
        }
        if ("403".equals(httpCode)) {
            return "登录了但缺权限点，响应体里业务码也是 403，message 里带着缺哪个权限";
        }
        return null;
    }
}

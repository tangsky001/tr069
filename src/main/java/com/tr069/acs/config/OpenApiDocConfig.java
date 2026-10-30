package com.tr069.acs.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线接口文档（springdoc / Swagger UI）：启动后打开 {@code /doc/index.html} 就能看到全部接口。 */
@Configuration
public class OpenApiDocConfig {

    /** 令牌请求头：管理端页面用的就是这个，和 AdminAuthInterceptor 一致 */
    private static final String TOKEN_HEADER = "X-Token";

    /**
     * 接口说明表：`方法 路径` → 说明。
     *
     * <p>第一段是列表里显示的一句话，后面（换行起）是展开后的参数与注意事项。</p>
     */
    private static final Map<String, String> DOC = new HashMap<>();

    /** 分组名：`Controller 简单类名` → 中文分组，不登记的就是默认的类名 */
    private static final Map<String, String> CLASS_TAG = new HashMap<>();

    static {
        CLASS_TAG.put("AuthController", "鉴权");
        CLASS_TAG.put("OltController", "OLT 管理");
        CLASS_TAG.put("FileManageController", "文件台账");
        CLASS_TAG.put("SystemController", "系统管理");
        CLASS_TAG.put("DeviceController", "设备台账");
        CLASS_TAG.put("DeviceCapabilityController", "设备能力树");
        CLASS_TAG.put("DeviceBusinessController", "设备业务快照");
        CLASS_TAG.put("RpcTaskController", "RPC 下发与任务");
        CLASS_TAG.put("DashboardController", "首页概览");
        CLASS_TAG.put("ConfigTemplateController", "配置意图模板");
        CLASS_TAG.put("BizTemplateController", "业务模板");
        CLASS_TAG.put("ModelAdaptationController", "型号适配");
        CLASS_TAG.put("WorklistExecuteController", "工单与开通");
        CLASS_TAG.put("OpenApiDeviceController", "北向-设备查询");
        CLASS_TAG.put("OpenApiDeliveryController", "北向-配置下发");
        CLASS_TAG.put("OpenApiEventController", "北向-事件订阅");
        CLASS_TAG.put("CwmpController", "南向 CWMP（不进文档）");
    }

    static {
        // 鉴权
        doc("POST /api/user/login", "登录换 token",
                "唯一免鉴权接口。成功后取 `data.token`，之后每个请求带请求头 `X-Token`。\n"
                        + "登录失败也是 HTTP 200：`code=500`，`message` 是「用户名或密码错误」这类原因。\n"
                        + "令牌默认 12 小时有效，没有刷新接口，过期重新登录。");
        doc("GET /api/user/info", "当前登录用户信息", "需要带令牌。`data` 里是 name / avatar / roles / introduction。");
        doc("POST /api/user/logout", "登出", "服务端没有令牌黑名单，登出只是给客户端一个明确的退出动作。");

        // OLT 台账
        doc("GET /api/olt/list", "OLT 台账列表",
                "`keyword` 匹配名称 / 地址 / 型号，不传就是全部。\n"
                        + "密码不回显，只给 `hasPassword` 表示有没有设过。");
        doc("GET /api/olt/options", "码表与默认值（建议缓存）",
                "一次返回所有下拉选项与阈值：厂商、字符集、默认端口与账号、板卡型号档案、\n"
                        + "视图中文名、VLAN 类型中文、板卡类别中文、机框槽位兜底、温度告警阈值。\n"
                        + "客户端应缓存，不要每次请求都拉。");
        doc("GET /api/olt/web-accounts", "「允许访问的 Web 登录账号」候选", "来自 `sys_user` 里的启用账号，用于填台账的 `webAccounts`。");
        doc("GET /api/olt/{id}", "OLT 详情",
                "含台账、板卡与端口（含每个端口下的 ONT 明细）、VLAN 快照、模板快照、最近 10 条采集记录。\n"
                        + "`deepSyncing=true` 表示后台还在补采慢数据，客户端应轮询本接口直到它变 false。\n"
                        + "`boards[].frontPanel` 只有一体化整机（鸿升光）才有，插框设备为 null。");
        doc("POST /api/olt/save", "新增或修改 OLT",
                "`id` 为空是新增，非空是修改。\n"
                        + "`password` 为空：新增时表示用设备默认口令 admin，修改时表示**沿用原密码**。\n"
                        + "`username` 为空按 root；`port` 为空按协议默认值（Telnet 23 / SSH 22）。\n"
                        + "`charset` 取 AUTO / UTF-8 / GBK / GB18030。`webAccounts` 为空表示不限制。\n"
                        + "改了地址 / 协议 / 端口会清空已采集的型号、厂商、板卡摘要。");
        doc("POST /api/olt/delete", "删除 OLT 台账", "连带物理清理这台设备的板卡记录。");
        doc("POST /api/olt/test", "诊断测试（未保存的表单也能测）",
                "不写库、不覆盖已采集的板卡，用来先确认能不能连上。\n"
                        + "失败同样是 HTTP 200 + `success=false`，原因在 `message`，设备回显在 `rawOutput`。");
        doc("POST /api/olt/{id}/test", "对已保存设备做诊断测试", "同上，用台账里存的连接信息。");
        doc("GET /api/olt/{id}/records", "采集记录",
                "`limit` 默认 10、上限 100。排查解析问题时看 `rawPreview`（设备回显片段）。");

        // OLT 同步
        doc("POST /api/olt/{id}/sync", "全量同步",
                "登录采集版本、板卡、端口、ONT、VLAN、模板，覆盖写库后返回最新详情。\n"
                        + "耗时十几秒到几十秒，客户端应给出等待提示。");
        doc("POST /api/olt/{id}/quick-sync", "快速同步（板卡先出来，慢数据后台补）",
                "只采板卡就立即返回，端口状态 / ONT / VLAN / 模板交给后台线程补采。\n"
                        + "返回 `deepSyncing=true` 时轮询 `GET /api/olt/{id}` 直到变 false。");
        doc("POST /api/olt/{id}/refresh", "实时刷新", "不改台账，直连采最新数据写库后返回。");
        doc("POST /api/olt/{id}/ont-query", "单条 ONT 按需查询",
                "`action` 取 optical（光信息）/ version（版本）/ register（注册历史）/ config（授权信息 + 业务流）/ info（管理 IP）。\n"
                        + "配合 `frameNo` / `slotNo` / `portNo` / `ontId` 定位到具体那台 ONT。");

        // OLT VLAN
        doc("GET /api/olt/{id}/vlan/form", "新建 VLAN 的约束",
                "给出 VLAN ID 上下限、不带成员端口与带成员端口时各自的批量上限、\n"
                        + "能做成员的端口类型、同一个口能不能既 tagged 又 untagged。\n"
                        + "页面校验与后端校验用的是同一份约束，按它填就不会被拒。");
        doc("POST /api/olt/{id}/vlan/preview", "VLAN 命令预览（不下发）",
                "把这次会执行的命令算出来给页面看，和下发走同一个方法，看到的必然是将要下发的那条序列。\n"
                        + "`warnings` 是提醒不是错误；参数写错时返回 `code=500` + 中文原因。");
        doc("POST /api/olt/{id}/vlan", "新建 VLAN 并配成员端口",
                "`vlanIds` 支持 `100-102,300` 这种写法；`tagged` / `untagged` 写 `epon1` / `ge3`，同一个口不能两边都占。\n"
                        + "不带端口时一次最多 64 个，带端口一次最多 16 个（每个 VLAN 都要单独进视图配端口）。\n"
                        + "成功后自动重采并返回最新详情。");
        doc("DELETE /api/olt/{id}/vlan", "删除 VLAN",
                "请求体是 VLAN ID 数组，如 `[100, 200]`；连续的会合并成一条 `no vlan <start> <end>`。\n"
                        + "成功后自动重采并返回最新详情。");

        // OLT DBA
        doc("GET /api/olt/{id}/dba-profile/form", "DBA 模板表单约束",
                "给出 type1~type5 各自要填哪几个带宽字段、模板 ID 范围、模板名长度与正则、带宽上下限。");
        doc("POST /api/olt/{id}/dba-profile/preview", "DBA 命令预览（不下发）", "同上：把 `dba-profile add` 这条命令算出来。");
        doc("POST /api/olt/{id}/dba-profile", "新增 DBA 模板",
                "`profileId` 10-512 且设备上不能重复；`name` 只允许数字、字母、下划线。\n"
                        + "`type` 决定要填哪几个带宽：type1 固定、type2 保证、type3 保证+最大、type4 最大、type5 固定+保证+最大。");
        doc("DELETE /api/olt/{id}/dba-profile", "删除 DBA 模板", "请求体是模板 ID 数组，如 `[10, 11]`。");

        // OLT TR-069
        doc("GET /api/olt/{id}/tr069/form", "TR-069 模板表单约束",
                "给出模板 ID 范围、连接端口范围、模板名 / ACS 地址 / 账号密码的最大长度、ACS 地址正则、以及默认填什么。");
        doc("GET /api/olt/{id}/tr069-profiles", "TR-069（ACS）模板列表", "设备上已有的 ACS 服务器模板。");
        doc("POST /api/olt/{id}/tr069-profiles", "新增 TR-069（ACS）模板",
                "`profileId` 1-32；`url` 是 ACS 地址；`informEnable` + `informInterval` 控制定期通知；\n"
                        + "`username` / `password` / `port` 是连接请求凭据。成功后返回最新模板列表。");

        // OLT 一键开局
        doc("GET /api/olt/{id}/quick-setup/form", "一键开局表单约束",
                "给出可选的端口 VLAN 模式（access / hybrid / trunk）与默认模式、可选端口类型与默认勾选哪些、\n"
                        + "一次最多能改几个端口、VLAN ID 上下限、TR-069 段的默认值。");
        doc("POST /api/olt/{id}/quick-setup/preview", "一键开局命令预览（不下发）",
                "把这次开局要下发的所有命令按顺序算出来，和下发走同一个方法。\n"
                        + "`warnings` 是提醒（如「没填 VLAN，只会改端口模式」）；参数不合法时 `code=500` + 中文原因。");
        doc("POST /api/olt/{id}/quick-setup", "一键开局",
                "一次把三件事做完：改端口 VLAN 模式 + 建业务 VLAN + 建 TR-069 模板，哪一步没勾就跳过哪一步。\n"
                        + "`mode`：trunk / hybrid 走 `vlanList`（可写 `41,45-46`），access 只认一个 VLAN。\n"
                        + "`createVlanIds` + `addCreatedToPorts=true` 表示新建的 VLAN 顺带把 `ports` 加成 tagged 成员。\n"
                        + "`portModeEnabled=false` 表示完全不动端口，`ports` 里的内容也会被忽略。\n"
                        + "成功后自动重采并返回最新详情。");

        // OLT 命令动作
        doc("GET /api/olt/{id}/actions", "这台设备能执行的命令动作目录",
                "按章节分组返回每个动作的功能名、参数定义（占位符 / 类型 / 范围 / 可选值 / 是否必填）、\n"
                        + "命令模板、是不是查询类、是不是破坏性操作。客户端据此渲染表单，不用自己判断厂商差异。");
        doc("POST /api/olt/{id}/exec", "执行一条命令动作",
                "`code` 取动作目录里的编号，`params` 是 `{占位符: 值}`，如 `{\"vlanId\":\"100\"}`。\n"
                        + "查询类返回设备回显 `outputs`；配置类下发后重采，`detail` 是最新详情。");
        doc("POST /api/olt/{id}/actions/preview", "动作命令预览（不下发）", "把这次动作会执行的完整命令（含进视图与 exit）算出来。");

        // 文件台账
        doc("GET /api/files", "文件清单", "固件与配置，新的在前。");
        doc("GET /api/files/{id}", "文件详情", "含字节数与内容摘要。");
        doc("GET /api/files/{id}/url", "设备取这个文件用的地址",
                "下发给设备 Download 时填的就是它。由 `acs.file.base-url` 决定；\n"
                        + "留空则按 `acs.public-host` + 上报端口拼。\n"
                        + "**ACS 的地址与设备能通的网段常常不是一回事**，现场对不上就把 base-url 配成设备真正能访问的地址。");
        doc("POST /api/files", "上传文件（multipart/form-data）",
                "`file` 是文件本身；`fileType` 取 FIRMWARE 固件 / CONFIG 配置 / VENDOR 厂商文件，不填按固件；\n"
                        + "`version` 是版本号。单个文件上限 512MB。\n"
                        + "上传走 `/api` 要令牌；设备取文件走 `/files/{id}`，不要令牌。");
        doc("POST /api/files/{id}/deliver", "把文件下发给一批设备（Download）",
                "`sns` 是设备序列号，数组或逗号分隔都行；`delaySeconds` 让设备延迟多少秒再取。\n"
                        + "每台设备各排一条 Download，队列是按设备分流的，多设备之间不会互相堵。\n"
                        + "返回每台设备的 `taskId`，结果用 `GET /api/rpc/{taskId}` 查。");
        doc("DELETE /api/files/{id}", "删除文件", "连带清掉磁盘上的内容。");

        // 系统管理
        doc("GET /api/system/permissions", "权限点清单",
                "按模块分组返回全部权限点，页面按它勾选。**权限点不落库，也不允许用户增删**。");
        doc("GET /api/system/roles", "角色列表",
                "带每个角色的权限点与人数。\n"
                        + "系统管理员的权限是代码里短路出来的全权限，库里没有它的行。");
        doc("POST /api/system/roles", "新建角色",
                "`{\"code\":\"VIEWER\",\"name\":\"只读\"}`，code 统一成大写。\n"
                        + "新角色一个权限都没有，建完去勾。");
        doc("PUT /api/system/roles/{code}/permissions", "设置角色权限",
                "`{\"permissions\":[\"device:view\",\"device:manage\"]}`，**整份覆盖**，\n"
                        + "传进去的就是这个角色的全部权限。改系统管理员的会被拒。");
        doc("DELETE /api/system/roles/{code}", "删角色",
                "内置角色删不掉，还有人在用的也删不掉。");
        doc("GET /api/system/users", "用户列表", "带最近登录时间与来源（WEB / CLIENT / MOBILE）。");
        doc("POST /api/system/users", "新建用户",
                "`{\"username\":\"wang\",\"password\":\"123456\",\"nickname\":\"老王\","
                        + "\"role\":\"VIEWER\"}`。密码至少 6 位。");
        doc("PUT /api/system/users/{id}", "改用户",
                "昵称 / 角色 / 状态 / 密码都可选，传 null 的项不动。\n"
                        + "**改密码要二次验证**：带 password 时必须同时带 currentPassword（操作者自己的当前密码），\n"
                        + "少了或不对直接被拒。拿到会话不等于拿到密码，会话被借走的人改不了密码。\n"
                        + "最后一个系统管理员既删不掉也停不掉、也降不成别的角色。");
        doc("GET /api/system/login-logs", "登录日志",
                "记时间、登录名、IP、来源、结果。**失败的也记**，连着一片失败是有人在试密码。\n"
                        + "传 userId 只看某一个人，默认看全部最近 100 条。\n"
                        + "IP 取自 X-Forwarded-For 的第一段，其次 X-Real-IP，都没有才用连接的地址。");
        doc("DELETE /api/system/users/{id}", "删用户",
                "不能删自己。**初始系统账号 admin 删不掉**，返回「系统用户禁止删除」；\\n"
                        + "最后一个系统管理员也删不掉。这两条在数据库里都有触发器兜底，\\n"
                        + "直接在 MySQL 里 delete 同样会被拦。");

        // 设备台账
        doc("GET /api/device/stat", "设备统计", "总数、在线数、离线数。");
        doc("GET /api/device/page", "设备分页查询", "`keyword` 按序列号模糊，`isp` / 型号筛选，返回 `PageResult`。");
        doc("GET /api/device/online", "在线会话列表", "当前在线的设备会话及版本、连接请求地址。");
        doc("GET /api/device/{sn}", "设备详情", "台账 + 在线会话里的实时属性。");
        doc("GET /api/device/{sn}/status", "设备在线状态", "这台设备现在在线与否、会话状态。");
        doc("GET /api/device/business/batch", "批量取业务概要", "`sns` 逗号分隔，一次拿多台设备的 WAN / WLAN / SIP 概要。");
        doc("POST /api/device/{sn}/remark", "保存房间号与备注", "房间号会用来替换模板变量。");
        doc("GET /api/device/{sn}/admin-account", "现读超管账号", "到在线设备上读取超管账号与明文口令，带超时，可能读不到。");
        doc("POST /api/device/{sn}/rpc/{rpcName}", "手动下发一条 CWMP 指令", "提交即返回任务号，结果用 `/api/rpc/{taskId}` 查。");

        // 设备能力树
        doc("GET /api/device/{sn}/capability", "能力汇总", "路径数、对象数、参数数、最近采集时间与进度。");
        doc("POST /api/device/{sn}/capability/collect", "触发能力采集", "`force=true` 时忽略最小间隔立即重采。");
        doc("GET /api/device/{sn}/capability/tree", "能力树下一层", "`parent` 为空返回根节点。");
        doc("GET /api/device/{sn}/capability/params", "能力参数分页查询", "支持父路径、类型、是否可写、关键字筛选。");
        doc("GET /api/device/{sn}/capability/param", "查单个参数", "按 `path` 看设备支不支持这个参数，回带读写权限与当前值。");
        doc("POST /api/device/{sn}/capability/export-tree", "导出型号 XML", "把这台设备的参数树导出成型号能力树文件，`force=true` 可覆盖同名文件。");
        doc("GET /api/device/{sn}/capability/model-file", "查型号能力树文件位置", "下发前确认该文件在不在。");
        doc("GET /api/device/{sn}/capability/biz-params", "查业务项对应参数", "从型号 XML 查某个业务项用到哪些参数节点，可限定父路径。");

        // 设备业务快照
        doc("GET /api/device/{sn}/business", "业务快照", "读库里的快照，按 wan / wifi / sip 分区返回。");
        doc("POST /api/device/{sn}/business/collect", "刷新业务快照", "向在线设备下发取值指令后回读。");

        // RPC 任务
        doc("GET /api/rpc/{taskId}", "查指令执行结果", "状态含排队、执行中、成功、失败。");
        doc("GET /api/rpc/device/{sn}/pending", "查排队中的指令数", "这台设备还压着多少条没下发。");

        // 首页
        doc("GET /api/dashboard/overview", "首页概览", "设备总量与在线离线、品牌分布、工单与模板统计。");

        // 配置意图模板 / 业务模板
        doc("GET /api/config-templates/meta", "模板字段定义", "模板类型与各分类要填的开通意图字段。");
        doc("GET /api/config-templates", "配置模板列表", "可按分类筛选、关键字搜索。");
        doc("GET /api/config-templates/{id}", "配置模板详情", "编辑时回填用。");
        doc("POST /api/config-templates", "保存配置模板", "新增或更新。");
        doc("POST /api/config-templates/{id}/delete", "删除配置模板", "按 id 删除。");
        doc("GET /api/biz-template/list", "业务模板列表", "支持关键字搜索。");
        doc("GET /api/biz-template/{id}", "业务模板详情", "含成员编排。");
        doc("POST /api/biz-template/save", "保存业务模板", "新增或更新模板及其成员编排。");
        doc("POST /api/biz-template/delete", "删除业务模板", "请求体里带 `id`。");
        doc("GET /api/biz-template/{id}/validate", "模板自检", "下发前确认启用状态、成员完整性与可下发校验。");
        doc("POST /api/biz-template/execute", "整包下发业务模板", "对设备按成员串行执行，返回下发记录 id。");
        doc("GET /api/biz-template/executions", "下发记录列表", "可按设备、模板过滤，`limit` 限制条数。");
        doc("GET /api/biz-template/executions/{id}", "查单条下发记录", "含各成员的执行结果。");

        // 型号适配与工单
        doc("GET /api/device/{sn}/adaptation/status", "查单台设备适配状态", "库里有没有这个型号的能力树。");
        doc("GET /api/models/adapt-status", "批量查适配状态", "`sns` 逗号分隔，一次查整页设备。");
        doc("POST /api/device/{sn}/adaptation/test", "实测能力比对", "按申报的运营商、网口、速率、SIP、WIFI 逐项比对。");
        doc("POST /api/device/{sn}/adaptation/save", "导出能力树并落库", "测试通过后导出 XML，同型号之后免适配。");
        doc("GET /api/worklist/variables", "取模板变量清单", "按业务类型返回下发表单要填哪些变量。");
        doc("POST /api/worklist/instances", "提交开通工单", "用 `worklistDesc` 作幂等键防重复开通。");
        doc("GET /api/worklist/instances", "工单分页查询", "可按设备序列号与状态过滤。");
        doc("GET /api/worklist/instances/{id}", "查工单执行进度", "提交后轮询这个接口看结果。");
        doc("GET /api/worklist/instances/device/{sn}", "查某台设备的历史工单", "最多返回一百条。");
        doc("GET /api/worklist/instances/{id}/steps", "查工单各步骤明细", "每一步的执行情况。");

        // 北向（8899 端口）
        doc("GET /openapi/v1/devices", "北向设备列表", "关键字与运营商过滤，分页。");
        doc("GET /openapi/v1/devices/{sn}", "北向设备详情", "台账 + 能力汇总 + 数据模型。");
        doc("GET /openapi/v1/devices/{sn}/online", "北向在线状态", "这台设备现在在线与否。");
        doc("GET /openapi/v1/devices/{sn}/capability/tree", "北向能力树", "`parent` 为空返回根节点层。");
        doc("GET /openapi/v1/devices/{sn}/capability/params", "北向参数清单", "可按路径前缀 `prefix` 过滤。");
        doc("GET /openapi/v1/devices/{sn}/business", "北向业务快照", "WAN / WiFi / SIP，只读库，不打扰设备。");
        doc("GET /openapi/v1/devices/{sn}/deliveries", "北向下发任务历史", "`limit` 限制条数。");
        doc("GET /openapi/v1/devices/{sn}/work-orders", "北向工单结果", "`limit` 限制条数。");
        doc("GET /openapi/v1/deliveries/meta", "北向业务类型与模板", "核对 `category` 可取哪些值。");
        doc("POST /openapi/v1/deliveries", "北向提交下发任务", "按调用方 `taskNo` 幂等防重复；异步执行，返回 `PENDING` 属正常。");
        doc("GET /openapi/v1/deliveries", "北向查下发任务", "带 `taskNo` 时按外部单号查，否则按 `sn` / `status` 查最近任务。");
        doc("GET /openapi/v1/deliveries/{id}", "北向按 id 查任务", "查异步执行结果。");
        doc("GET /openapi/v1/events/types", "可订阅事件类型", "如设备上下线、下发结束。");
        doc("GET /openapi/v1/events/subscriptions", "事件订阅列表", "当前所有回调订阅。");
        doc("POST /openapi/v1/events/subscriptions", "新增或修改事件订阅", "回调地址、`eventTypes`（`*` 表示全部）、`secret`（不填不签名）。");
        doc("DELETE /openapi/v1/events/subscriptions/{id}", "删除事件订阅", "按 id 删除。");
        doc("GET /openapi/v1/events/subscriptions/{id}/deliveries", "查某订阅的推送记录", "含对方响应状态码。");
        doc("GET /openapi/v1/events/deliveries", "查全部回调投递记录", "全局排障用。");
        doc("POST /openapi/v1/events/test", "发一条测试事件", "验证回调地址与签名是否正常。");
    }

    private static void doc(String key, String summary, String description) {
        DOC.put(key, summary + "\n" + description);
    }

    @Bean
    public OpenAPI acsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("TR069 ACS 接口文档")
                        .version("1.0.0")
                        .contact(new Contact().name("PilotACS"))
                        .description("""
                                管理端 `/api/**` 与北向 `/openapi/v1/**` 两套接口。三条约定：

                                1. **业务成功看 `code == 200`，HTTP 200 不代表成功**：
                                   参数写错、设备拒绝、命令算不出来都是 HTTP 200 + `code: 500`，
                                   原因在 `message` 里（可直接展示给用户）。缺令牌是 HTTP 401（`code: 401`），
                                   缺权限是 HTTP 403（`code: 403`）。
                                2. **先 `POST /api/user/login` 换 token**，之后每个请求带请求头 `X-Token`。
                                   令牌默认 12 小时有效，没有刷新接口，过期重新登录。
                                3. **北向接口在 8899 端口**（前缀 `/openapi/v1`），鉴权方式相同。

                                右上角 Authorize 填一次 token，之后调试每个接口都会自动带上。
                                """))
                .components(new Components()
                        .addSecuritySchemes(TOKEN_HEADER, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(TOKEN_HEADER)
                                .description("登录接口返回的 token，放在 X-Token 请求头里"))
                        .addSecuritySchemes("BearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("等价写法：Authorization: Bearer <token>")))
                .addSecurityItem(new SecurityRequirement().addList(TOKEN_HEADER));
    }

    /**
     * 把 {@link #DOC} 里的中文说明挂到对应接口上：匹配 `方法 路径`，命中就写 summary 与 description。
     */
    @Bean
    public OperationCustomizer docCustomizer() {
        return (Operation operation, HandlerMethod handlerMethod) -> {
            // 分组名默认是类名（olt-controller 这种），换成中文
            String tag = CLASS_TAG.get(handlerMethod.getMethod().getDeclaringClass().getSimpleName());
            if (tag != null) {
                operation.setTags(List.of(tag));
            }
            String key = pathOf(handlerMethod);
            if (key == null) {
                return operation;
            }
            // 登录接口本身不要令牌，否则页面上调试它会白带一个 X-Token
            if ("POST /api/user/login".equals(key)) {
                operation.setSecurity(List.of());
            }
            // 参数说明与响应状态说明先补：这两个跟接口有没有写 DOC 无关
            fillParameters(operation, key);
            fillResponses(operation);
            String text = DOC.get(key);
            if (text == null) {
                return operation;
            }
            int br = text.indexOf('\n');
            operation.setSummary(text.substring(0, br).trim());
            operation.setDescription(text.substring(br + 1).trim());
            return operation;
        };
    }

    /**
     * 给「请求参数」那张表补说明：路径参数与查询参数逐个按名字查 {@link OpenApiParamDoc}。
     *
     * <p>{@code id} 这类名字在不同接口里指向不同对象，查表时带上 {@code 方法 路径} 作上下文。</p>
     */
    private static void fillParameters(Operation operation, String key) {
        if (operation.getParameters() == null) {
            return;
        }
        for (Parameter parameter : operation.getParameters()) {
            if (parameter == null || (parameter.getDescription() != null && !parameter.getDescription().isBlank())) {
                continue;
            }
            String text = OpenApiParamDoc.param(key, parameter.getName());
            if (text != null) {
                parameter.setDescription(text);
            }
        }
    }

    /** 给「响应状态」那张表补说明：200 与 401 这两行有话说，其余保持框架默认。 */
    private static void fillResponses(Operation operation) {
        if (operation.getResponses() == null) {
            return;
        }
        for (Map.Entry<String, ApiResponse> entry : operation.getResponses().entrySet()) {
            String text = OpenApiParamDoc.status(entry.getKey());
            if (text != null && entry.getValue() != null) {
                entry.getValue().setDescription(text);
            }
        }
    }

    /**
     * 给「响应参数」那张表补说明：遍历 {@code components.schemas}，按字段名查表写 description。
     *
     * <p>Swagger Models 页面用的是同一份 schema，所以那边也会一起有说明。</p>
     */
    @Bean
    public OpenApiCustomizer fieldDocCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().forEach((name, schema) -> fillSchema(name, schema, 0));
        };
    }

    /** 递归补一个 schema 的字段说明，{@code depth} 用来兜住自引用。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void fillSchema(String name, Schema<?> schema, int depth) {
        if (schema == null || depth > 3) {
            return;
        }
        fillSchema(name, schema.getItems(), depth + 1);
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        // 通用响应体的 code / message / data 与业务对象里的同名字段含义不同，只在顶层按 Result 走一套说法
        boolean resultBody = depth == 0 && name.startsWith("Result");
        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            Schema<?> property = entry.getValue();
            if (property == null) {
                continue;
            }
            if (property.getDescription() == null || property.getDescription().isBlank()) {
                String text = resultBody ? OpenApiParamDoc.resultField(entry.getKey()) : null;
                if (text == null) {
                    text = OpenApiParamDoc.field(entry.getKey());
                }
                if (text != null) {
                    property.setDescription(text);
                }
            }
            fillSchema(name, property, depth + 1);
        }
    }

    /** 从 Controller 与方法上的 @*Mapping 拼出 `方法 路径`，如 `GET /api/olt/{id}` */
    private static String pathOf(HandlerMethod handlerMethod) {
        String base = "";
        // 用方法声明的类，避免拿到 AOP 代理类
        RequestMapping typeMapping = handlerMethod.getMethod().getDeclaringClass().getAnnotation(RequestMapping.class);
        if (typeMapping != null && typeMapping.value().length > 0) {
            // 类路径里可能写 ${acs.openapi.path-prefix:/openapi/v1}，展开成默认值
            base = typeMapping.value()[0].replaceAll("\\$\\{[^:]*:([^}]*)\\}", "$1");
        }
        String sub = null;
        String verb = null;
        for (Annotation annotation : handlerMethod.getMethod().getAnnotations()) {
            String name = annotation.annotationType().getSimpleName();
            if (!name.endsWith("Mapping")) {
                continue;
            }
            if (verb == null && name.length() > "Mapping".length()) {
                verb = name.substring(0, name.length() - "Mapping".length()).toUpperCase();
            }
            try {
                Method value = annotation.annotationType().getMethod("value");
                String[] paths = (String[]) value.invoke(annotation);
                // 不写路径的方法（类上的路径就是完整路径）也要认，只是拼出来不带后缀
                if (sub == null) {
                    sub = paths.length > 0 ? paths[0] : "";
                }
            } catch (Exception ignored) {
                // 注解没有 value 就跳过
            }
        }
        if (verb == null || sub == null) {
            return null;
        }
        return verb + " " + base + sub;
    }
}

package com.tr069.acs.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.common.exception.BizException;
import com.tr069.acs.template.entity.ConfigTemplate;
import com.tr069.acs.template.mapper.ConfigTemplateMapper;
import com.tr069.acs.template.model.ConfigTemplateSaveRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 配置意图模板：只描述「要开通成什么样」，不下发任何数据。
 *
 * <p>模板里的意图字段（IP 协议、是否带 VLAN、是否绑定端口……）与型号语义映射里的
 * {@code biz_field} 对齐；下发时由执行器按设备型号取出真实 CWMP 参数路径，
 * 再组合成 SetParameterValues 报文。新增意图字段只改 {@link #FIELD_DEFS}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigTemplateService {

    /** 模板类型：代码 -> 展示名。 */
    private static final List<Map<String, String>> CATEGORIES = List.of(
            cat("WAN_DHCP", "WAN DHCP"),
            cat("WAN_PPPOE", "WAN PPPoE"),
            cat("WAN_STATIC", "WAN 静态IP"),
            cat("WAN_BRIDGE", "WAN 桥接"),
            cat("WIFI_24G", "2.4G WiFi"),
            cat("WIFI_58G", "5.8G WiFi"),
            cat("SIP", "SIP 语音"),
            cat("OTHER", "其他"));

    /**
     * 各类型的意图字段定义：只描述意图，不写参数路径（路径由型号映射决定）。
     *
     * <p>字段 key 与 {@code SemanticSchema} 的业务字段 code 对齐（service_list / vlan_id /
     * mtu / bind_ports…），下发时渲染器直接拿它去命中型号映射表；对不上 key 的旧字段
     * 只是描述，不产生报文。</p>
     */
    private static final List<Map<String, Object>> FIELD_DEFS = buildFieldDefs();

    private static List<Map<String, Object>> buildFieldDefs() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (String wan : List.of("WAN_DHCP", "WAN_PPPOE", "WAN_STATIC", "WAN_BRIDGE")) {
            defs.addAll(wanFieldDefs(wan));
        }
        defs.add(def("WAN_PPPOE", "username", "拨号账号", "string", "", null, null));
        defs.add(def("WAN_PPPOE", "password", "拨号口令", "string", "", null,
                Map.of("inputType", "password")));
        defs.add(def("WAN_STATIC", "external_ip", "静态 IP", "string", "", null, null));
        defs.add(def("WAN_STATIC", "subnet_mask", "子网掩码", "string", "", null, null));
        defs.add(def("WAN_STATIC", "default_gateway", "默认网关", "string", "", null, null));

        defs.addAll(wifiFieldDefs("WIFI_24G", "2.4G WiFi 信道",
                List.of(opt("auto", "自动"),
                        opt("1", "1"), opt("2", "2"), opt("3", "3"), opt("4", "4"), opt("5", "5"),
                        opt("6", "6"), opt("7", "7"), opt("8", "8"), opt("9", "9"), opt("10", "10"),
                        opt("11", "11"), opt("12", "12"), opt("13", "13")),
                List.of(opt("auto", "自动"), opt("20MHz", "20MHz"), opt("40MHz", "40MHz"))));
        defs.addAll(wifiFieldDefs("WIFI_58G", "5.8G WiFi 信道",
                List.of(opt("auto", "自动"),
                        opt("36", "36"), opt("40", "40"), opt("44", "44"), opt("48", "48"),
                        opt("149", "149"), opt("153", "153"), opt("157", "157"), opt("161", "161")),
                List.of(opt("auto", "自动"), opt("20MHz", "20MHz"),
                        opt("40MHz", "40MHz"), opt("80MHz", "80MHz"))));
        defs.addAll(sipFieldDefs());
        defs.addAll(otherFieldDefs());
        return List.copyOf(defs);
    }

    /**
     * WiFi 模板字段，对照开通界面：SSID / 信道带宽 / 信道 / 隐藏 / WPS / 发射功率 /
     * 加密方式 / WPA 算法 / 预共享密钥。字段 key 与 {@code SemanticSchema} WiFi 字段对齐。
     */
    private static List<Map<String, Object>> wifiFieldDefs(String category, String channelLabel,
                                                           List<Map<String, String>> channels,
                                                           List<Map<String, String>> bandwidths) {
        List<Map<String, Object>> defs = new ArrayList<>();
        defs.add(def(category, "radio_enable", "启用无线 SSID", "enum", "true",
                List.of(opt("true", "是"), opt("false", "否")), null));
        defs.add(def(category, "ssid", "SSID 名称", "string", "", null, null));
        defs.add(def(category, "bandwidth", "信道带宽", "enum", "auto", bandwidths, null));
        defs.add(def(category, "channel", channelLabel, "enum", "auto", channels, null));
        defs.add(def(category, "hidden", "隐藏 SSID", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def(category, "wps_disable", "禁用 WPS 按键功能", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def(category, "tx_power", "发射功率", "enum", "auto",
                List.of(opt("auto", "自动"), opt("100", "100%"), opt("75", "75%"),
                        opt("50", "50%"), opt("25", "25%")), null));
        defs.add(def(category, "radio_hardware_enable", "无线模块硬件开关", "enum", "true",
                List.of(opt("true", "是"), opt("false", "否")), null));
        defs.add(def(category, "encryption", "加密方式", "enum", "11i",
                List.of(opt("Basic", "开放（无密码）"), opt("WPA", "WPA-PSK"),
                        opt("11i", "WPA2-PSK"), opt("WPAand11i", "WPA/WPA2 混合")), null));
        defs.add(def(category, "wpa_algorithm", "WPA 加密算法", "enum", "AES+TKIP",
                List.of(opt("AES+TKIP", "AES+TKIP (默认)"), opt("AES", "AES"),
                        opt("TKIP", "TKIP")), null));
        defs.add(def(category, "wpa_key", "WPA 预共享密钥", "string", "", null,
                Map.of("inputType", "password", "tip", "8-63 位，加密方式为开放时无效")));
        return defs;
    }

    /** SIP 语音模板字段，对照开通界面：服务器三件套 + 数图 + 拨号约束。 */
    private static List<Map<String, Object>> sipFieldDefs() {
        List<Map<String, Object>> defs = new ArrayList<>();
        defs.add(def("SIP", "protocol", "语音协议", "enum", "SIP",
                List.of(opt("SIP", "SIP"), opt("H248", "H.248")), null));
        defs.add(def("SIP", "registrar", "SIP 注册服务器地址", "string", "", null,
                Map.of("tip", "IP 或域名")));
        defs.add(def("SIP", "registrar_port", "SIP 注册服务器端口", "number", 5060, null,
                Map.of("min", 1, "max", 65535)));
        defs.add(def("SIP", "proxy", "SIP 代理服务器地址", "string", "", null,
                Map.of("tip", "可选，默认和 SIP 服务器相同")));
        defs.add(def("SIP", "proxy_port", "SIP 代理服务器端口", "number", null, null,
                Map.of("min", 1, "max", 65535)));
        defs.add(def("SIP", "outbound_proxy", "Outbound 服务器地址", "string", "", null,
                Map.of("tip", "IP 或域名，可选")));
        defs.add(def("SIP", "outbound_proxy_port", "Outbound 服务器端口", "number", null, null,
                Map.of("min", 1, "max", 65535)));
        defs.add(def("SIP", "backup_registrar", "备用 SIP 注册服务器地址", "string", "", null,
                Map.of("tip", "IP 或域名，可选")));
        defs.add(def("SIP", "backup_registrar_port", "备用 SIP 注册服务器端口", "number", null, null,
                Map.of("min", 1, "max", 65535)));
        defs.add(def("SIP", "min_digits", "最小拨号长度", "number", null, null,
                Map.of("tip", "可选，1-3", "min", 1, "max", 3)));
        defs.add(def("SIP", "user_agent_domain", "用户域名 (UserAgentDomain)", "string", "", null,
                Map.of("tip", "可选，一般为空")));
        defs.add(def("SIP", "digitmap_enable", "数图 (DigitMap) 开关", "enum", "ignore",
                List.of(opt("ignore", "= 忽略 ="), opt("enable", "启用"), opt("disable", "禁用")), null));
        defs.add(def("SIP", "digitmap_rule", "数图 (DigitMap) 规则", "string", "", null,
                Map.of("tip", "可选，如 [1-9]xxxxxxx")));
        defs.add(def("SIP", "disable_special_dial", "禁用特殊号码拨号 (1xx)", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("SIP", "sip_user_info", "SIP 用户信息", "textarea", "", null,
                Map.of("tip", "每条记录占一行，每行格式：SIP账号名 密码；变量：%room% 表示房间号，"
                        + "%desc% 表示光猫备注，将备注设为房间号时可用；"
                        + "例1: 8601 123456；例2: %room% %room% 表示账号和密码均为房间号；"
                        + "例3: %room% 123456 账号为房间号，密码为123456")));
        defs.add(def("SIP", "prefer_existing_account", "优先使用已存在的SIP账号信息", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        return defs;
    }

    /** 「其他」类型：设备管理类意图，多为厂商私有节点，映射后才会产生报文。 */
    private static List<Map<String, Object>> otherFieldDefs() {
        List<Map<String, Object>> defs = new ArrayList<>();
        defs.add(def("OTHER", "unlock_limit", "解除上网限制", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "telnet_enable", "开启 Telnet", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "telnet_username", "修改 Telnet 用户名", "string", "", null, null));
        defs.add(def("OTHER", "telnet_password", "修改 Telnet 密码", "string", "", null,
                Map.of("inputType", "password", "tip", "为空不修改")));
        defs.add(def("OTHER", "reboot_after", "下发业务完成后重启光猫", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "wan_access", "开启 WAN 侧访问", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "igmp_support", "IGMP 组播支持", "enum", "ignore",
                List.of(opt("ignore", "= 忽略 ="), opt("enable", "开启"), opt("disable", "关闭")), null));
        defs.add(def("OTHER", "multicast_mode", "组播工作模式", "enum", "auto",
                List.of(opt("auto", "自动 (默认)"), opt("proxy", "代理"), opt("snooping", "侦听")), null));
        defs.add(def("OTHER", "clear_loid", "清除逻辑ID (LOID)", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "super_account", "超管账号", "string", "", null,
                Map.of("tip", "为空不修改")));
        defs.add(def("OTHER", "super_password", "超管密码", "string", "", null,
                Map.of("inputType", "password", "tip", "如 Admin@123，为空表示不修改")));
        defs.add(def("OTHER", "firewall_disable", "关闭防火墙及攻击防御", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "log_disable", "禁用光猫日志功能", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "wifi_isolation_disable", "关闭WiFi共享隔离 (端口&STA)", "enum", "false",
                List.of(opt("false", "否"), opt("true", "是")), null));
        defs.add(def("OTHER", "led_control", "光猫面板 LED 灯控制", "enum", "ignore",
                List.of(opt("ignore", "= 忽略 ="), opt("on", "开启"), opt("off", "关闭")), null));
        defs.add(def("OTHER", "wifi_roaming", "WiFi 漫游支持", "enum", "ignore",
                List.of(opt("ignore", "= 忽略 ="), opt("enable", "开启"), opt("disable", "关闭")), null));
        defs.add(def("OTHER", "roaming_threshold_24g", "2.4G 漫游阈值", "number", null, null,
                Map.of("tip", "-60~-75，默认为 -70", "min", -90, "max", -40)));
        defs.add(def("OTHER", "roaming_threshold_58g", "5.8G 漫游阈值", "number", null, null,
                Map.of("tip", "-60~-75，默认为 -70", "min", -90, "max", -40)));
        return defs;
    }

    /**
     * WAN 类共用的意图字段，对照开通界面：业务模式 / VLAN / 绑定端口 / MTU / 组播。
     *
     * <p>绑定端口值形如 {@code [LAN1, SSID1, PON]}，下发时以逗号拼接写入该型号的绑定节点。</p>
     */
    private static List<Map<String, Object>> wanFieldDefs(String category) {
        List<Map<String, Object>> defs = new ArrayList<>();
        boolean bridge = "WAN_BRIDGE".equalsIgnoreCase(category);
        defs.add(def(category, "ip_protocol", "IP 协议", "enum", "IPv4",
                List.of(opt("IPv4", "IPv4 (默认)"), opt("IPv6", "IPv6"),
                        opt("IPv4+IPv6", "IPv4 + IPv6")), null));
        defs.add(def(category, "vlan_id", "WAN VLAN ID", "number", null, null,
                Map.of("tip", "如 201，为空表示无", "min", 0, "max", 4094)));
        defs.add(def(category, "vlan_priority", "802.1p QoS 优先级", "number", null, null,
                Map.of("tip", "0-7，为空表示不下发", "min", 0, "max", 7)));
        // 选项值用设备口径（大写）：设备上 X_CMCC_ServiceList 实测只有
        // INTERNET / TR069 / VOIP / IPTV 这几种写法，发 "Internet" 过去会被判 9007
        if (bridge) {
            // 桥接的业务模式是「这条桥给谁用」，与路由业务不是一套取值：
            // Other 是纯二层透传，不发业务绑定
            defs.add(def(category, "service_list", "桥接业务模式", "enum", "OTHER",
                    List.of(opt("OTHER", "其他 / Other（默认）"),
                            opt("INTERNET", "上网 / Internet"),
                            opt("IPTV", "电视 / IPTV"),
                            opt("OTT", "OTT")), null));
            // 桥接类型决定建哪个对象：IP→WANIPConnection(IP_Bridged)，
            // PPPoE→WANPPPConnection(PPPoE_Bridged)
            defs.add(def(category, "bridge_type", "桥接类型", "enum", "IP",
                    List.of(opt("IP", "IP（默认）"), opt("PPPoE", "PPPoE")), null));
        } else {
            defs.add(def(category, "service_list", "业务模式", "enum", "INTERNET",
                    List.of(opt("INTERNET", "Internet 上网"), opt("TR069", "TR069 管理"),
                            opt("VOIP", "VOIP 语音"), opt("IPTV", "IPTV 组播"),
                            opt("INTERNET,TR069", "上网 + 管理"),
                            opt("INTERNET,TR069,VOIP", "上网 + 管理 + 语音"),
                            opt("INTERNET,TR069,VOIP,IPTV", "全业务")), null));
        }
        defs.add(def(category, "bind_ports", "绑定端口", "ports", List.of(), null,
                Map.of("tip", "勾选该 WAN 绑定的端口 / SSID，留空表示不修改")));
        defs.add(def(category, "mtu", "MTU", "number", null, null,
                Map.of("tip", "1280-1500，PPPoE 最大 1492，空表示不修改", "min", 1280, "max", 1500)));
        defs.add(def(category, "multicast_vlan", "组播 VLAN ID", "number", null, null,
                Map.of("tip", "0 或空表示不启用组播上行通道", "min", 0, "max", 4094)));
        return defs;
    }

    private final ConfigTemplateMapper mapper;
    private final ObjectMapper objectMapper;

    // ==================== 元数据 ====================

    /**
     * 绑定端口候选，供页面渲染勾选组。
     *
     * <p>取值形如 {@code LAN1} / {@code SSID1} / {@code PON}，与 {@link #wanFieldDefs} 里
     * bind_port 的约定一致：选中的值以逗号拼接后直接写进型号的绑定节点。
     * 页面若自行造一个 {@code LAN9}，型号不认这个端口，整条 SPV 会按 9003 拒回来。</p>
     *
     * <p>SSID5~8 归在 5.8G 组，与频段判定的约定一致（前 4 个 SSID 是 2.4G）。</p>
     */
    private static final List<Map<String, Object>> BIND_PORT_GROUPS = List.of(
            portGroup("lan", "LAN", "LAN1", "LAN2", "LAN3", "LAN4", "LAN5", "LAN6", "LAN7", "LAN8"),
            portGroup("wifi24", "2.4G", "SSID1", "SSID2", "SSID3", "SSID4"),
            portGroup("wifi58", "5.8G", "SSID5", "SSID6", "SSID7", "SSID8"),
            portGroup("pon", "PON", "PON"));

    private static Map<String, Object> portGroup(String code, String label, String... ports) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("code", code);
        group.put("label", label);
        List<Map<String, String>> list = new ArrayList<>();
        for (String p : ports) {
            Map<String, String> port = new LinkedHashMap<>();
            port.put("v", p);
            // PON 口是从光猫下行来的，页面上要标清楚，不然会被当成普通网口乱勾
            port.put("t", "PON".equals(p) ? "PON（FFTR从光猫下行PON口）" : p);
            list.add(port);
        }
        group.put("ports", list);
        return group;
    }

    /**
     * 模板类型与各类别的意图字段定义，供页面按类型渲染表单。
     */
    public Map<String, Object> meta() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("categories", CATEGORIES);
        // 绑定端口候选：与 bind_port 意图字段的取值同源，页面照它渲染勾选组
        data.put("bindPortGroups", BIND_PORT_GROUPS);
        Map<String, List<Map<String, Object>>> fieldsByType = new LinkedHashMap<>();
        for (Map<String, Object> def : FIELD_DEFS) {
            fieldsByType.computeIfAbsent(String.valueOf(def.get("category")), k -> new ArrayList<>())
                    .add(def);
        }
        data.put("fields", fieldsByType);
        return data;
    }

    // ==================== 查询 ====================

    public List<Map<String, Object>> list(String category, String keyword) {
        LambdaQueryWrapper<ConfigTemplate> query = new LambdaQueryWrapper<>();
        if (category != null && !category.isBlank()) {
            query.eq(ConfigTemplate::getCategory, category.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(ConfigTemplate::getName, kw)
                    .or().like(ConfigTemplate::getRemark, kw)
                    .or().like(ConfigTemplate::getGroupName, kw));
        }
        query.orderByAsc(ConfigTemplate::getCategory).orderByAsc(ConfigTemplate::getId);
        List<Map<String, Object>> views = new ArrayList<>();
        for (ConfigTemplate template : mapper.selectList(query)) {
            views.add(toView(template));
        }
        return views;
    }

    public Map<String, Object> get(Long id) {
        ConfigTemplate template = mapper.selectById(id);
        if (template == null) {
            throw new BizException("模板不存在: " + id);
        }
        return toView(template);
    }

    /**
     * 按 id 取模板视图，不存在返回 null（业务模板成员引用时用）。
     */
    public Map<String, Object> findView(Long id) {
        if (id == null) {
            return null;
        }
        ConfigTemplate template = mapper.selectById(id);
        return template == null ? null : toView(template);
    }

    // ==================== 保存 / 删除 ====================

    public Map<String, Object> save(ConfigTemplateSaveRequest request) {
        if (request == null) {
            throw new BizException("提交内容不能为空");
        }
        String name = request.getName() == null ? "" : request.getName().trim();
        String category = request.getCategory() == null ? "" : request.getCategory().trim();
        if (name.isEmpty()) {
            throw new BizException("模板名称不能为空");
        }
        if (category.isEmpty()) {
            throw new BizException("模板类型不能为空");
        }

        ConfigTemplate template = request.getId() == null ? new ConfigTemplate() : mapper.selectById(request.getId());
        if (template == null) {
            throw new BizException("模板不存在: " + request.getId());
        }

        boolean dup = mapper.selectCount(new LambdaQueryWrapper<ConfigTemplate>()
                .eq(ConfigTemplate::getCategory, category)
                .eq(ConfigTemplate::getName, name)
                .ne(request.getId() != null, ConfigTemplate::getId, request.getId())) > 0;
        if (dup) {
            throw new BizException("同类型下已存在同名模板: " + name);
        }

        template.setName(name);
        template.setCategory(category);
        template.setGroupName(request.getGroupName() == null ? "" : request.getGroupName().trim());
        template.setEnabled(request.getEnabled() == null || request.getEnabled());
        template.setRemark(request.getRemark() == null ? "" : request.getRemark().trim());
        template.setFields(writeFields(request.getFields()));
        template.setIncremental(request.getIncremental() != null && request.getIncremental());

        LocalDateTime now = LocalDateTime.now();
        if (request.getId() == null) {
            template.setCreatedAt(now);
            template.setUpdatedAt(now);
            mapper.insert(template);
        } else {
            template.setUpdatedAt(now);
            mapper.updateById(template);
        }
        return toView(template);
    }

    public void delete(Long id) {
        if (mapper.selectById(id) == null) {
            throw new BizException("模板不存在: " + id);
        }
        mapper.deleteById(id);
    }

    public int count() {
        return mapper.selectCount(null).intValue();
    }

    /**
     * 取模板的意图字段，供下发时按型号映射翻译成真实参数。
     */
    public Map<String, Object> fieldsOf(Long id) {
        ConfigTemplate template = mapper.selectById(id);
        if (template == null) {
            return new LinkedHashMap<>();
        }
        return readFields(template.getFields());
    }

    /**
     * 模板是否勾了「增量业务」。
     *
     * <p>下发侧在 {@code IntentDeliveryService} 里按任务上的模板 id 现取：模板改了开关，
     * 排队中的任务跟着走新语义，不需要给 device_task 再加一列。</p>
     */
    public boolean incrementalOf(Long id) {
        if (id == null) {
            return false;
        }
        ConfigTemplate template = mapper.selectById(id);
        return template != null && template.getIncremental() != null && template.getIncremental();
    }

    // ==================== 内部实现 ====================

    private Map<String, Object> toView(ConfigTemplate template) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", template.getId());
        view.put("name", template.getName());
        view.put("category", template.getCategory());
        view.put("categoryLabel", labelOf(template.getCategory()));
        view.put("groupName", template.getGroupName());
        view.put("enabled", template.getEnabled() != null && template.getEnabled());
        view.put("remark", template.getRemark());
        view.put("fields", readFields(template.getFields()));
        view.put("incremental", template.getIncremental() != null && template.getIncremental());
        view.put("createdAt", template.getCreatedAt());
        view.put("updatedAt", template.getUpdatedAt());
        return view;
    }

    private Map<String, Object> readFields(String json) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return fields;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            if (parsed != null) {
                parsed.forEach((k, v) -> {
                    if (k != null && v != null) {
                        fields.put(k, v);
                    }
                });
            }
        } catch (Exception e) {
            log.warn("模板意图字段解析失败，已忽略: {}", e.getMessage());
        }
        return fields;
    }

    private String writeFields(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            throw new BizException("意图字段序列化失败: " + e.getMessage());
        }
    }

    private static String labelOf(String category) {
        if (category == null) {
            return "";
        }
        for (Map<String, String> c : CATEGORIES) {
            if (Objects.equals(c.get("code"), category)) {
                return c.get("label");
            }
        }
        return category;
    }

    private static Map<String, String> cat(String code, String label) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("label", label);
        return item;
    }

    private static Map<String, Object> def(String category, String key, String label,
                                           String type, Object defaultValue,
                                           List<Map<String, String>> options,
                                           Map<String, Object> extra) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("category", category);
        item.put("key", key);
        item.put("label", label);
        item.put("type", type);
        item.put("defaultValue", defaultValue);
        item.put("options", options);
        if (extra != null) {
            item.putAll(extra);
        }
        return item;
    }

    private static Map<String, String> opt(String value, String label) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("label", label);
        return item;
    }
}

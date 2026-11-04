package com.tr069.acs.exec;

import com.tr069.acs.exec.model.IntentPlan;
import com.tr069.acs.exec.model.PlanItem;
import com.tr069.acs.worklist.tpl.TemplateVariables;
import com.tr069.acs.worklist.tpl.dao.DeviceLanDao;
import com.tr069.acs.worklist.tpl.dao.DeviceWanDao;
import com.tr069.acs.worklist.tpl.dao.SipDao;
import com.tr069.acs.worklist.tpl.dao.VlanDao;
import com.tr069.acs.worklist.tpl.dao.WlanADao;
import com.tr069.acs.worklist.tpl.dao.WlanBDao;
import com.tr069.acs.worklist.tpl.entity.Device;
import com.tr069.acs.worklist.tpl.entity.DeviceLan;
import com.tr069.acs.worklist.tpl.entity.DeviceWan;
import com.tr069.acs.worklist.tpl.entity.Sip;
import com.tr069.acs.worklist.tpl.entity.Vlan;
import com.tr069.acs.worklist.tpl.entity.WlanA;
import com.tr069.acs.worklist.tpl.entity.WlanB;
import com.tr069.acs.worklist.tpl.service.TemplateBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 业务意图 → CWMP 下发计划（读 SQLite 适配库） */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlitePlanRenderer {

    /** 变量占位符，如 %room% */
    private static final Pattern VAR = Pattern.compile("%([A-Za-z0-9_]+)%");

    private final WlanADao wlanADao;
    private final WlanBDao wlanBDao;
    private final SipDao sipDao;
    private final DeviceWanDao deviceWanDao;
    private final VlanDao vlanDao;
    private final DeviceLanDao deviceLanDao;

    /** 渲染下发计划 */
    public IntentPlan render(String bizObject, String category, Device device,
                             Map<String, Object> values, int instance,
                             Map<String, String> overrides) {
        List<PlanItem> items = new ArrayList<>();
        List<String> objectsToAdd = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        switch (bizObject == null ? "" : bizObject) {
            case "WiFi_2G" -> wifi(device, values, instance, items, errors, true, overrides);
            case "WiFi_5G" -> wifi(device, values, instance, items, errors, false, overrides);
            case "Voice_Service" -> voice(device, values, instance, items, errors, overrides);
            case "Internet_WAN" -> wan(category, device, values, items, objectsToAdd, errors, overrides, instance);
            case "Bridge_WAN" -> wan(category, device, values, items, objectsToAdd, errors, overrides, instance);
            case "Device_Mgmt" -> deviceMgmt(values, overrides, errors);
            default -> errors.add("该业务还没有适配模板，请先对该型号做适配: " + bizObject);
        }

        // Device_Mgmt 的重启计划本来就是空的，动作在执行收尾时发，不能当「没适配」报错
        if (items.isEmpty() && errors.isEmpty() && !"Device_Mgmt".equals(bizObject)) {
            errors.add("该型号没有可下发的适配结果: bizObject=" + bizObject
                    + "，请重新发起适配");
        }
        log.debug("SQLite 渲染: model={}, bizObject={}, instance={}, 参数={}, 新建对象={}",
                device == null ? null : device.getModelKey(), bizObject, instance,
                items.size(), objectsToAdd.size());
        return new IntentPlan(bizObject, instance, objectsToAdd, items, errors);
    }

    /** 设备管理类意图（OTHER / Device_Mgmt） */
    private void deviceMgmt(Map<String, Object> values, Map<String, String> overrides,
                            List<String> errors) {
        if (on(values == null ? null : values.get("reboot_after"))
                || on(overrides == null ? null : overrides.get("reboot_after"))) {
            return;
        }
        List<String> active = new ArrayList<>();
        if (values != null) {
            values.forEach((k, v) -> {
                if (!"reboot_after".equals(k) && !"instance".equals(k) && on(v)) {
                    active.add(k);
                }
            });
        }
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (!"reboot_after".equals(k) && !"instance".equals(k) && on(v) && !active.contains(k)) {
                    active.add(k);
                }
            });
        }
        if (active.isEmpty()) {
            errors.add("该模板没有启用任何动作：请把「下发业务完成后重启光猫」设为是");
        } else {
            errors.add("设备管理字段要按型号适配映射后才能下发: " + String.join(", ", active)
                    + "。只想重启设备的话，单独建一张只勾「下发业务完成后重启光猫」的模板即可");
        }
    }

    /** 开关类取值：非空且不是「否/关/忽略」就算启用 */
    private static boolean on(Object value) {
        if (value == null) {
            return false;
        }
        String s = String.valueOf(value).trim();
        return !s.isEmpty() && !"false".equalsIgnoreCase(s) && !"0".equals(s)
                && !"否".equals(s) && !"ignore".equalsIgnoreCase(s);
    }

    // ==================== 各业务 ====================

    private void wifi(Device device, Map<String, Object> values, int instance,
                      List<PlanItem> items, List<String> errors, boolean band24,
                      Map<String, String> overrides) {
        if (band24) {
            WlanA row = pick(wlanADao.findByDeviceId(device.getId()), WlanA::getInstanceNo,
                    instance, "2.4G", device.getModelKey(), errors);
            if (row == null) {
                return;
            }
            put(items, errors, "WiFi_2G", "enable", row.getPathEnable(), row.getValEnable(), values, instance, overrides);
            put(items, errors, "WiFi_2G", "ssid", row.getPathSsid(), row.getValSsid(), values, instance, overrides);
            put(items, errors, "WiFi_2G", "wpa_key", row.getPathKey(), row.getValKey(), values, instance, overrides);
            put(items, errors, "WiFi_2G", "wpa_psk", row.getPathPsk(), row.getValPsk(), values, instance, overrides);
            put(items, errors, "WiFi_2G", "channel", row.getPathChannel(), row.getValChannel(), values, instance, overrides);
            put(items, errors, "WiFi_2G", "beacon", row.getPathBeacon(), row.getValBeacon(), values, instance, overrides);
            put(items, errors, "WiFi_2G", "wpa_mode", row.getPathWpaMode(), row.getValWpaMode(), values, instance, overrides);
        } else {
            WlanB row = pick(wlanBDao.findByDeviceId(device.getId()), WlanB::getInstanceNo,
                    instance, "5G", device.getModelKey(), errors);
            if (row == null) {
                return;
            }
            put(items, errors, "WiFi_5G", "enable", row.getPathEnable(), row.getValEnable(), values, instance, overrides);
            put(items, errors, "WiFi_5G", "ssid", row.getPathSsid(), row.getValSsid(), values, instance, overrides);
            put(items, errors, "WiFi_5G", "wpa_key", row.getPathKey(), row.getValKey(), values, instance, overrides);
            put(items, errors, "WiFi_5G", "wpa_psk", row.getPathPsk(), row.getValPsk(), values, instance, overrides);
            put(items, errors, "WiFi_5G", "channel", row.getPathChannel(), row.getValChannel(), values, instance, overrides);
            put(items, errors, "WiFi_5G", "beacon", row.getPathBeacon(), row.getValBeacon(), values, instance, overrides);
            put(items, errors, "WiFi_5G", "wpa_mode", row.getPathWpaMode(), row.getValWpaMode(), values, instance, overrides);
        }
    }

    /** 按实例号取模板行，取不到就回退到该频段的第一行 */
    private static <T> T pick(List<T> rows, Function<T, Integer> instanceNo, int wanted,
                              String label, String modelKey, List<String> errors) {
        if (rows == null || rows.isEmpty()) {
            errors.add(label + " 没有可用模板：该型号适配时未申报这一档 WiFi 能力，"
                    + "请重新适配并把「WIFI 规格」选成包含 " + label + " 的一档");
            return null;
        }
        for (T row : rows) {
            Integer no = instanceNo.apply(row);
            if (no != null && no == wanted) {
                return row;
            }
        }
        T fallback = rows.get(0);
        log.warn("{} 模板里没有第 {} 个实例，回退到第 {} 个下发: model={}",
                label, wanted, instanceNo.apply(fallback), modelKey);
        return fallback;
    }

    private void voice(Device device, Map<String, Object> values, int instance,
                       List<PlanItem> items, List<String> errors,
                       Map<String, String> overrides) {
        Sip row = sipDao.findByDeviceId(device.getId()).stream()
                .filter(r -> r.getInstanceNo() != null && r.getInstanceNo() == instance)
                .findFirst().orElse(null);
        if (row == null) {
            errors.add("语音模板里没有第 " + instance + " 路，请重新适配");
            return;
        }
        put(items, errors, "Voice_Service", "enable", row.getPathEnable(), row.getValEnable(), values, instance, overrides);
        put(items, errors, "Voice_Service", "sip_num", row.getPathUser(), row.getValUser(), values, instance, overrides);
        put(items, errors, "Voice_Service", "sip_pwd", row.getPathPwd(), row.getValPwd(), values, instance, overrides);
        put(items, errors, "Voice_Service", "sip_uri", row.getPathUri(), row.getValUri(), values, instance, overrides);
        put(items, errors, "Voice_Service", "sip_proxy", row.getPathProxy(), row.getValProxy(), values, instance, overrides);
        put(items, errors, "Voice_Service", "sip_port", row.getPathPort(), row.getValPort(), values, instance, overrides);
        put(items, errors, "Voice_Service", "sip_registrar", row.getPathRegistrar(), row.getValRegistrar(), values, instance, overrides);
        put(items, errors, "Voice_Service", "sip_registrar_port", row.getPathRegistrarPort(), row.getValRegistrarPort(), values, instance, overrides);
    }

    /** WAN 连接：先按业务类型挑模板行（DHCP / PPPoE / Static 各一行） */
    private void wan(String category, Device device, Map<String, Object> values,
                       List<PlanItem> items, List<String> objectsToAdd, List<String> errors,
                       Map<String, String> overrides, int instance) {
        int slot = wanSlot(category, errors);
        if (slot <= 0) {
            return;
        }
        boolean bridge = "WAN_BRIDGE".equalsIgnoreCase(category == null ? "" : category);
        // 记下本业务新增条目的起点：桥接切 PPPoE 时要把对象名与参数前缀整体换掉
        int itemStart = items.size();
        int addStart = objectsToAdd.size();
        String biz = bridge ? "Bridge_WAN" : "Internet_WAN";
        DeviceWan row = deviceWanDao.findOne(device.getId(), slot).orElse(null);
        if (row == null) {
            errors.add("WAN 模板里没有 " + category + " 对应的连接，请重新适配");
            return;
        }
        // 业务 WAN 是新建的：先把对象建出来，实例号设备分配后再回填参数路径
        if ("ADD".equalsIgnoreCase(row.getAction()) && row.getObjectPath() != null
                && !row.getObjectPath().isBlank()) {
            objectsToAdd.add(row.getObjectPath());
        }
        // 实例号必须用渲染时定的那个（plan.instance()），不能写死 1：
        // AddObject 返回的实例号是设备分配的，下发前还要按它回填参数路径，
        // 两处对不上就会把参数写到别的连接上去
        put(items, errors, biz, "enable", row.getPathEnable(), row.getValEnable(), values, instance, overrides);
        if (bridge) {
            // 桥接只有连接类型可写：IP→IP_Bridged、PPPoE→PPPoE_Bridged。
            // 地址获取方式、NAT、拨号账号在桥接下不存在，带上会让设备整批回 9007
            put(items, errors, biz, "bridge_type",
                    row.getPathConnType(), row.getValConnType(), values, instance, overrides);
        } else {
            put(items, errors, biz, "conn_type", row.getPathConnType(), row.getValConnType(), values, instance, overrides);
            put(items, errors, biz, "addr_type", row.getPathAddrType(), row.getValAddrType(), values, instance, overrides);
            put(items, errors, biz, "nat", row.getPathNat(), row.getValNat(), values, instance, overrides);
            put(items, errors, biz, "pppoe_user", row.getPathUsername(), row.getValUsername(), values, instance, overrides);
            put(items, errors, biz, "pppoe_pwd", row.getPathPassword(), row.getValPassword(), values, instance, overrides);
            put(items, errors, biz, "wan_ip", row.getPathIp(), row.getValIp(), values, instance, overrides);
            put(items, errors, biz, "wan_mask", row.getPathMask(), row.getValMask(), values, instance, overrides);
            put(items, errors, biz, "wan_gw", row.getPathGw(), row.getValGw(), values, instance, overrides);
            put(items, errors, biz, "wan_dns", row.getPathDns(), row.getValDns(), values, instance, overrides);
        }
        put(items, errors, biz, "ip_protocol", row.getPathIpMode(), row.getValIpMode(), values, instance, overrides);
        put(items, errors, biz, "bind_port", row.getPathBindPort(), row.getValBindPort(), values, instance, overrides);
        putMtu(items, errors, device, row, values, instance, overrides);

        Vlan vlan = vlanDao.findOne(device.getId(), slot).orElse(null);
        if (vlan != null) {
            put(items, errors, biz, "vlan_id", vlan.getPathVlanId(), vlan.getValVlanId(), values, instance, overrides);
            put(items, errors, biz, "vlan_8021p", vlan.getPath8021p(), vlan.getVal8021p(), values, instance, overrides);
            put(items, errors, biz, "vlan_mode", vlan.getPathVlanMode(), vlan.getValVlanMode(), values, instance, overrides);
        }

        // 桥接类型选了 PPPoE：对象与整条前缀一起换成 WANPPPConnection。
        // 只改 ConnectionType 会写到一台没有该对象的设备上，回 9005
        if (bridge && isPppoeBridge(items, itemStart)) {
            swapConnObject(items, objectsToAdd, itemStart, addStart);
        }
    }

    /** 本段计划里的桥接类型是不是 PPPoE（值已被归一成 {@code PPPoE_Bridged}） */
    private static boolean isPppoeBridge(List<PlanItem> items, int from) {
        for (int i = from; i < items.size(); i++) {
            PlanItem item = items.get(i);
            if ("bridge_type".equalsIgnoreCase(item.bizField())) {
                return item.value() != null
                        && item.value().toLowerCase(Locale.ROOT).startsWith("pppoe");
            }
        }
        return false;
    }

    /** 把本段计划的 AddObject 对象名与参数前缀从 {@code WANIPConnection} 换成 */
    private static void swapConnObject(List<PlanItem> items, List<String> objectsToAdd,
                                       int itemStart, int addStart) {
        for (int i = addStart; i < objectsToAdd.size(); i++) {
            objectsToAdd.set(i, swapConnObject(objectsToAdd.get(i)));
        }
        for (int i = itemStart; i < items.size(); i++) {
            PlanItem item = items.get(i);
            String path = swapConnObject(item.path());
            if (!path.equals(item.path())) {
                items.set(i, new PlanItem(item.bizObject(), item.bizField(),
                        path, item.value(), item.valueType()));
            }
        }
    }

    private static String swapConnObject(String path) {
        if (path == null) {
            return null;
        }
        return path.contains("WANIPConnection.")
                ? path.replace("WANIPConnection.", "WANPPPConnection.")
                : path;
    }

    /** MTU 单独走一段，不直接 put——这是个填错了会把网搞断的字段 */
    private void putMtu(List<PlanItem> items, List<String> errors, Device device, DeviceWan row,
                        Map<String, Object> values, int instance, Map<String, String> overrides) {
        // 先判值再判路径：没填 MTU 就完全不碰路径，存量型号的行为与改动前一致
        String mtu = effectiveValue(row.getValMtu(), overrides, "mtu");
        if (mtu == null || mtu.isBlank()) {
            log.debug("MTU 未填写，保持设备原值: model={}", device.getModelKey());
            return;
        }
        if ("0".equals(mtu.trim())) {
            log.warn("MTU 填了 0，按不下发处理（下发 0 会触发 9003 并可能断网）: model={}",
                    device.getModelKey());
            return;
        }
        String path = row.getPathMtu();
        if (path == null || path.isBlank()) {
            // 适配库里登记过的最准（实测参数名，可能是厂商私有写法）；
            // 没登记的按标准路径兜底：MaxMTUSize 在 TR-098 与 TR-181 下同名，
            // 而 objectPath 已经带了根节点，两个数据模型都直接拼得出来
            if (row.getObjectPath() == null || row.getObjectPath().isBlank()) {
                log.warn("该型号没有 MTU 登记也没有 WAN 对象路径，跳过 MTU 下发: model={}",
                        device.getModelKey());
                return;
            }
            path = row.getObjectPath() + TemplateBuilder.INSTANCE_PLACEHOLDER + ".MaxMTUSize";
            log.warn("型号未登记 MTU 路径，按标准路径兜底下发，建议重新适配核实: model={}, path={}",
                    device.getModelKey(), path);
        }
        put(items, errors, "Internet_WAN", "mtu", path, mtu, values, instance, overrides);
    }

    /** 各宽带业务在模板库里的排位号，与 {@link TemplateBuilder} 生成时一致 */
    private static int wanSlot(String category, List<String> errors) {
        if (category == null) {
            errors.add("下发未指定宽带业务类型");
            return -1;
        }
        switch (category.toUpperCase()) {
            case "WAN_DHCP":
                return TemplateBuilder.WAN_SLOT_DHCP;
            case "WAN_PPPOE":
                return TemplateBuilder.WAN_SLOT_PPPOE;
            case "WAN_STATIC":
                return TemplateBuilder.WAN_SLOT_STATIC;
            case "WAN_BRIDGE":
                return TemplateBuilder.WAN_SLOT_BRIDGE;
            default:
                errors.add("该宽带业务还没有适配模板，请先对该型号做适配: " + category);
                return -1;
        }
    }

    // ==================== 取值 ====================

    /** 落一条待下发参数。路径为空表示该型号没有这个参数（不是错误，跳过） */
    private void put(List<PlanItem> items, List<String> errors, String bizObject, String field,
                     String path, String val, Map<String, Object> values, int instance,
                     Map<String, String> overrides) {
        if (path == null || path.isBlank()) {
            // 模板上明明填了值、型号却没登记这条路径（如 802.1p / 组播 VLAN 还没做映射）：
            // 静默跳过会让「填了不生效」查不到原因，这里留一条能落地的告警
            String wanted = effectiveValue(null, overrides, field);
            if (wanted != null && !wanted.isBlank()) {
                log.warn("模板上填了 {}={}，但该型号没有登记对应的参数路径，本次不下发", field, wanted);
            }
            return;
        }
        String value = normalize(field,
                resolve(field, effectiveValue(val, overrides, field), values, errors));
        if (value == null) {
            return;
        }
        String realPath = path.replace(TemplateBuilder.INSTANCE_PLACEHOLDER, String.valueOf(instance));
        if (realPath.contains(TemplateBuilder.INSTANCE_PLACEHOLDER)) {
            errors.add("参数路径仍有未替换的实例号: " + realPath);
            return;
        }
        String type = valueTypeOf(realPath, value);
        if ("boolean".equalsIgnoreCase(type)) {
            value = IntentDeliveryService.booleanValue(value);
        }
        items.add(new PlanItem(bizObject, field, realPath, value, type));
    }

    /** 配置模板字段覆盖 SQLite 默认值。模板没填（空串）时回退到默认值 */
    private static String effectiveValue(String val, Map<String, String> overrides, String field) {
        if (overrides == null) {
            return val;
        }
        for (String key : keysToTry(field)) {
            String over = overrides.get(key);
            if (over != null && !over.isBlank()) {
                return over;
            }
        }
        return val;
    }

    /** 本字段及其在配置模板里的等价名字，按优先级排列 */
    private static final Map<String, List<String>> ALIASES = Map.of(
            "enable", List.of("radio_enable"),
            "wpa_mode", List.of("encryption"),
            "sip_proxy", List.of("proxy"),
            "sip_port", List.of("proxy_port"),
            "sip_registrar", List.of("registrar"),
            "sip_registrar_port", List.of("registrar_port"),
            // 渲染器内部叫 bind_port / vlan_8021p，配置模板与语义映射里叫 bind_ports /
            // vlan_priority。少了这两个别名，页面上勾的端口与填的 QoS 优先级一个都传不进去：
            // 取不到覆盖值就回退到 SQLite 里的空默认，绑定端口整条被跳过
            "bind_port", List.of("bind_ports"),
            "vlan_8021p", List.of("vlan_priority"));

    private static List<String> keysToTry(String field) {
        List<String> aliases = ALIASES.get(field);
        if (aliases == null || aliases.isEmpty()) {
            return List.of(field);
        }
        List<String> keys = new ArrayList<>();
        keys.add(field);
        keys.addAll(aliases);
        return keys;
    }

    /** 把业务侧的写法归一成设备认的写法 */
    private static String normalize(String field, String value) {
        if (value == null) {
            return null;
        }
        return switch (field) {
            case "channel" -> normalizeChannel(value);
            case "wpa_mode" -> normalizeEncryptionModes(value);
            case "ip_protocol" -> normalizeIpProtocol(value);
            case "bridge_type" -> normalizeBridgeType(value);
            default -> value;
        };
    }

    /** 信道：模板里的 auto/自动 落到设备上是数字，0 表示自动选道 */
    private static String normalizeChannel(String value) {
        String v = value.trim();
        if (v.isEmpty() || v.matches("\\d+")) {
            return v;
        }
        log.debug("信道值不是数字，按自动选道下发: {} -> 0", value);
        return "0";
    }

    /** 加密方式：{@code IEEE11iEncryptionModes} 只认 {@code *Encryption} 写法 */
    private static final Map<String, String> ENCRYPTION_MODES = Map.ofEntries(
            Map.entry("none", "None"),
            Map.entry("basic", "WEPEncryption"),
            Map.entry("wep", "WEPEncryption"),
            Map.entry("wpa", "TKIPEncryption"),
            Map.entry("11i", "AESEncryption"),
            Map.entry("wpa2", "AESEncryption"),
            Map.entry("wpapsk", "AESEncryption"),
            Map.entry("wpaand11i", "TKIPandAESEncryption"),
            Map.entry("tkip", "TKIPEncryption"),
            Map.entry("aes", "AESEncryption"),
            Map.entry("tkipandaes", "TKIPandAESEncryption"));

    private static String normalizeEncryptionModes(String value) {
        String v = value.trim();
        if (v.isEmpty() || v.endsWith("Encryption") || "None".equalsIgnoreCase(v)) {
            return v;
        }
        String mapped = ENCRYPTION_MODES.get(v.toLowerCase(Locale.ROOT));
        return mapped != null ? mapped : "AESEncryption";
    }

    /** IP 协议版本：配置模板上给的是 {@code IPv4 / IPv6 / IPv4+IPv6} */
    private static final Map<String, String> IP_PROTOCOL_MODES = Map.of(
            "ipv4", "1",
            "ipv6", "2",
            "ipv4+ipv6", "3",
            "ipv4v6", "3",
            "ipv4/v6", "3");

    private static String normalizeIpProtocol(String value) {
        String v = value.trim();
        if (v.isEmpty() || v.matches("\\d+")) {
            return v;
        }
        String mapped = IP_PROTOCOL_MODES.get(v.toLowerCase(Locale.ROOT).replace(" ", ""));
        if (mapped == null) {
            log.warn("IP 协议值无法识别，按 IPv4 下发: {}", value);
            return "1";
        }
        return mapped;
    }

    /** 桥接类型：配置模板上给的是 {@code IP / PPPoE}，设备的 {@code ConnectionType} */
    private static String normalizeBridgeType(String value) {
        String v = value.trim();
        if (v.isEmpty()) {
            return v;
        }
        String low = v.toLowerCase(Locale.ROOT).replace(" ", "");
        if (low.startsWith("ppp")) {
            return "PPPoE_Bridged";
        }
        if (low.startsWith("ip")) {
            return "IP_Bridged";
        }
        log.warn("桥接类型无法识别，按 IP 桥接下发: {}", value);
        return "IP_Bridged";
    }

    /** 把变量换成真实值。键既接受 {@code %room%} 也接受 {@code room} */
    private String resolve(String field, String val, Map<String, Object> values, List<String> errors) {
        if (val == null || val.isBlank()) {
            return null;
        }
        Matcher m = VAR.matcher(val);
        if (!m.find()) {
            return val.trim();
        }
        String out = val;
        // 最多解两层：变量的值本身可能还是变量。SIP 账号常按房间号开，模板上写 %room%，
        // 第一层解出 sip_num=%room%，不解第二层就把字面量 %room% 发给了设备（实测 9007）
        for (int depth = 0; depth < 2; depth++) {
            Matcher it = VAR.matcher(out);
            if (!it.find()) {
                break;
            }
            it.reset();
            while (it.find()) {
                String token = it.group();
                Object raw = values == null ? null : values.get(token);
                if (raw == null && values != null) {
                    raw = values.get(it.group(1));
                }
                if (raw == null) {
                    errors.add("缺业务字段值: " + token + "（" + field + "）");
                    return null;
                }
                out = out.replace(token, String.valueOf(raw).trim());
            }
        }
        return out.trim();
    }

    /** 设备参数名里这些在设备上是整数或布尔，纯数字的值才该按数字下发 */
    private static final Pattern NUMERIC_LEAF = Pattern.compile(
            "(?i)(channel|port|count|numberofentries|index|interval|seconds|"
                    + "timeout|priority|gain|tail|vlanid|status|mode|level|length|rate|limit|"
                    // MaxMTUSize 不在这个清单里就会被当成 string 下发 1500，
                    // 设备不认字符串形式的 MTU，直接回 9007
                    + "mtusize)$");

    /** 设备参数名里这些是布尔：{@code Enable} / {@code SSIDAdvertisementEnabled} 等 */
    private static final Pattern BOOLEAN_LEAF = Pattern.compile(
            "(?i)(enable|enabled|disable|disabled)$");

    /** 没有登记类型时按参数名与值一起判断：布尔语义的一律 boolean */
    private static String valueTypeOf(String path, String value) {
        if (isBooleanLeaf(path)) {
            return "boolean";
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return "boolean";
        }
        if (value.matches("-?\\d+") && isNumericLeaf(path)) {
            return "unsignedInt";
        }
        return "string";
    }

    private static boolean isBooleanLeaf(String path) {
        String leaf = leafOf(path);
        return leaf != null && BOOLEAN_LEAF.matcher(leaf).find();
    }

    private static boolean isNumericLeaf(String path) {
        String leaf = leafOf(path);
        if (leaf == null) {
            return false;
        }
        return leaf.startsWith("NumberOf") || NUMERIC_LEAF.matcher(leaf).find();
    }

    /** 参数路径的最后一段，如 {@code ...Line.1.Enable} 取 {@code Enable} */
    private static String leafOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String leaf = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int dot = leaf.lastIndexOf('.');
        return dot >= 0 ? leaf.substring(dot + 1) : leaf;
    }

    /** 供调用方判断某类业务是否有适配结果，避免下发时才报「查不到」 */
    public boolean hasTemplate(Device device, String bizObject) {
        long id = device.getId();
        switch (bizObject == null ? "" : bizObject) {
            case "WiFi_2G":
                return !wlanADao.findByDeviceId(id).isEmpty();
            case "WiFi_5G":
                return !wlanBDao.findByDeviceId(id).isEmpty();
            case "Voice_Service":
                return !sipDao.findByDeviceId(id).isEmpty();
            case "Internet_WAN":
                return !deviceWanDao.findByDeviceId(id).isEmpty();
            // 桥接模板行就在同一张表里（slot 4）。这里认不出 Bridge_WAN 的话，
            // 每次下发桥接都会先跑一遍全量补生成，再在渲染时报「没有适配模板」
            case "Bridge_WAN":
                return deviceWanDao.findOne(id, TemplateBuilder.WAN_SLOT_BRIDGE).isPresent();
            case "LAN":
                return !deviceLanDao.findByDeviceId(id).isEmpty();
            default:
                return false;
        }
    }

    /** LAN 网口：按实例号取对应网口 */
    public List<PlanItem> lan(Device device, Map<String, Object> values, int instance,
                              List<String> errors, Map<String, String> overrides) {
        List<PlanItem> items = new ArrayList<>();
        DeviceLan row = deviceLanDao.findByDeviceId(device.getId()).stream()
                .filter(r -> r.getInstanceNo() != null && r.getInstanceNo() == instance)
                .findFirst().orElse(null);
        if (row == null) {
            errors.add("网口模板里没有第 " + instance + " 个网口，请重新适配");
            return items;
        }
        put(items, errors, "LAN", "enable", row.getPathEnable(), row.getValEnable(), values, instance, overrides);
        put(items, errors, "LAN", "duplex", row.getPathDuplex(), row.getValDuplex(), values, instance, overrides);
        put(items, errors, "LAN", "speed", row.getPathSpeed(), row.getValSpeed(), values, instance, overrides);
        return items;
    }

    /** 变量清单入口，前端下发表单据此渲染输入框 */
    public static String sample(String varName) {
        return TemplateVariables.ROOM.equals(varName) ? "房间号" : varName;
    }
}

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

/**
 * 业务意图 → CWMP 下发计划（读 SQLite 适配库）。
 *
 * <p>与旧的 {@link IntentPlanRenderer} 的区别：旧版读 MySQL 的
 * {@code model_param_mapping}（人工逐条登记「业务字段 → 参数路径」），
 * 这一版读 SQLite 里由 {@link TemplateBuilder} 生成的适配结果——
 * 型号采集过、适配过了，库里就有现成的路径和值，下发直接取用。</p>
 *
 * <p>库里的 {@code val_xx} 存的是变量（{@code %room%} / {@code %sip_pwd%}）
 * 或常量（{@code 1} / {@code IP_Routed}），真实值由本次下发的
 * {@code values} 填进来；填不出来的变量直接记为错误，不带占位符下发给设备。</p>
 *
 * <p>没有适配结果的型号不是渲染器能补的——那要回到适配流程重新采集，
 * 由 {@link com.tr069.acs.worklist.tpl.service.ModelTemplateService} 在采集后生成。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlitePlanRenderer {

    /** 变量占位符，如 %room%。 */
    private static final Pattern VAR = Pattern.compile("%([A-Za-z0-9_]+)%");

    private final WlanADao wlanADao;
    private final WlanBDao wlanBDao;
    private final SipDao sipDao;
    private final DeviceWanDao deviceWanDao;
    private final VlanDao vlanDao;
    private final DeviceLanDao deviceLanDao;

    /**
     * 渲染下发计划。
     *
     * @param bizObject 业务对象：WiFi_2G / WiFi_5G / Voice_Service / Internet_WAN
     * @param category  下发业务类型：WIFI_24G / SIP / WAN_DHCP ...，WAN 用它挑对应的连接模板
     * @param device     SQLite 里的已适配型号
     * @param values     本次下发的业务字段值，键可以是 {@code %room%} 或 {@code room}
     * @param instance   实例号，写第几个（第几个 SSID、第几路语音、第几个网口）
     * @param overrides  配置模板里保存的字段值，按字段名覆盖 SQLite 表里的默认值；
     *                   允许模板里的值再带 {@code %room%} 等变量，会被 {@code values} 二次替换
     */
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
            case "Internet_WAN" -> wan(category, device, values, items, objectsToAdd, errors, overrides);
            default -> errors.add("该业务还没有适配模板，请先对该型号做适配: " + bizObject);
        }

        if (items.isEmpty() && errors.isEmpty()) {
            errors.add("该型号没有可下发的适配结果: bizObject=" + bizObject
                    + "，请重新发起适配");
        }
        return new IntentPlan(bizObject, instance, objectsToAdd, items, errors);
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

    /**
     * 按实例号取模板行，取不到就回退到该频段的第一行。
     *
     * <p>设备上报的 WLANConfiguration 索引和模板里登记的实例号常常不是一回事：
     * 中兴 P22g-CIac 实测 2.4G 从 SSID1 起、5.8G 从 SSID5 起，中间 2~4 是别的 2.4G 网络，
     * 而模板每组只登记了起始那一个。遇到没登记的实例号，宁可用该频段的起始实例继续下发，
     * 也好过整单失败——回退会打 WARN，现场据此能看出写的是哪个实例。</p>
     *
     * <p>整张表为空说明这个型号压根没申报该频段的能力，回退也没意义，只能提示重新适配。</p>
     */
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

    /**
     * WAN 连接：先按业务类型挑模板行（DHCP / PPPoE / Static 各一行），
     * 再把 VLAN 一并带上——VLAN 参数是挂在 WAN 下的，分开表存只是管理上的拆分。
     */
    private void wan(String category, Device device, Map<String, Object> values,
                       List<PlanItem> items, List<String> objectsToAdd, List<String> errors,
                       Map<String, String> overrides) {
        int slot = wanSlot(category, errors);
        if (slot <= 0) {
            return;
        }
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
        put(items, errors, "Internet_WAN", "enable", row.getPathEnable(), row.getValEnable(), values, 1, overrides);
        put(items, errors, "Internet_WAN", "conn_type", row.getPathConnType(), row.getValConnType(), values, 1, overrides);
        put(items, errors, "Internet_WAN", "addr_type", row.getPathAddrType(), row.getValAddrType(), values, 1, overrides);
        put(items, errors, "Internet_WAN", "nat", row.getPathNat(), row.getValNat(), values, 1, overrides);
        put(items, errors, "Internet_WAN", "bind_port", row.getPathBindPort(), row.getValBindPort(), values, 1, overrides);
        put(items, errors, "Internet_WAN", "pppoe_user", row.getPathUsername(), row.getValUsername(), values, 1, overrides);
        put(items, errors, "Internet_WAN", "pppoe_pwd", row.getPathPassword(), row.getValPassword(), values, 1, overrides);
        put(items, errors, "Internet_WAN", "wan_ip", row.getPathIp(), row.getValIp(), values, 1, overrides);
        put(items, errors, "Internet_WAN", "wan_mask", row.getPathMask(), row.getValMask(), values, 1, overrides);
        put(items, errors, "Internet_WAN", "wan_gw", row.getPathGw(), row.getValGw(), values, 1, overrides);
        put(items, errors, "Internet_WAN", "wan_dns", row.getPathDns(), row.getValDns(), values, 1, overrides);

        Vlan vlan = vlanDao.findOne(device.getId(), slot).orElse(null);
        if (vlan != null) {
            put(items, errors, "Internet_WAN", "vlan_id", vlan.getPathVlanId(), vlan.getValVlanId(), values, 1, overrides);
            put(items, errors, "Internet_WAN", "vlan_8021p", vlan.getPath8021p(), vlan.getVal8021p(), values, 1, overrides);
            put(items, errors, "Internet_WAN", "vlan_mode", vlan.getPathVlanMode(), vlan.getValVlanMode(), values, 1, overrides);
        }
    }

    /** 各宽带业务在模板库里的排位号，与 {@link TemplateBuilder} 生成时一致。 */
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
            default:
                errors.add("该宽带业务还没有适配模板，请先对该型号做适配: " + category);
                return -1;
        }
    }

    // ==================== 取值 ====================

    /**
     * 落一条待下发参数。路径为空表示该型号没有这个参数（不是错误，跳过）；
     * 值为空同理（如绑定端口留空＝不修改）。
     *
     * <p>取值优先级：配置模板字段 {@code overrides} > SQLite 适配库默认值 {@code val} >
     * 变量替换。这样模板里填的 SSID / 密码等才会真正下发，而不是被适配时的默认值顶掉。</p>
     */
    private void put(List<PlanItem> items, List<String> errors, String bizObject, String field,
                     String path, String val, Map<String, Object> values, int instance,
                     Map<String, String> overrides) {
        if (path == null || path.isBlank()) {
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

    /**
     * 配置模板字段覆盖 SQLite 默认值。模板没填（空串）时回退到默认值，
     * 这样用户只改 SSID 时不会把其它字段清成空。
     *
     * <p>键按 {@link #ALIASES} 逐个试：配置模板里叫 {@code encryption} / {@code radio_enable}，
     * 渲染器内部字段叫 {@code wpa_mode} / {@code enable}，两边命名不一样，
     * 不认别名的话模板上配的加密方式与开关就等于白填。</p>
     */
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

    /** 本字段及其在配置模板里的等价名字，按优先级排列。 */
    private static final Map<String, List<String>> ALIASES = Map.of(
            "enable", List.of("radio_enable"),
            "wpa_mode", List.of("encryption"),
            "sip_proxy", List.of("proxy"),
            "sip_port", List.of("proxy_port"),
            "sip_registrar", List.of("registrar"),
            "sip_registrar_port", List.of("registrar_port"));

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

    /**
     * 把业务侧的写法归一成设备认的写法。
     *
     * <p>配置模板面向人，取值是「auto」「WPA2-PSK」这类说法；设备参数只认自己那套枚举或数字，
     * 照原样发一律 9003 Invalid arguments。SPV 又是按批生效的，一条不合法整批都废，
     * 所以必须在渲染阶段兜住，而不是指望每个填模板的人都记得设备要什么。</p>
     */
    private static String normalize(String field, String value) {
        if (value == null) {
            return null;
        }
        return switch (field) {
            case "channel" -> normalizeChannel(value);
            case "wpa_mode" -> normalizeEncryptionModes(value);
            default -> value;
        };
    }

    /** 信道：模板里的 auto/自动 落到设备上是数字，0 表示自动选道。 */
    private static String normalizeChannel(String value) {
        String v = value.trim();
        if (v.isEmpty() || v.matches("\\d+")) {
            return v;
        }
        return "0";
    }

    /**
     * 加密方式：{@code IEEE11iEncryptionModes} 只认 {@code *Encryption} 写法，
     * 而配置模板给的是 {@code BeaconType} 那套（Basic / WPA / 11i / WPAand11i），
     * 两套枚举混用是实测 9003 的直接原因。
     */
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

    /**
     * 把变量换成真实值。键既接受 {@code %room%} 也接受 {@code room}，
     * 两种前端表单写法都能对上。
     */
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

    /**
     * 设备参数名里这些在设备上是整数或布尔，纯数字的值才该按数字下发。
     *
     * <p>反过来，SIP 账号、密码、电话号码常是纯数字（如 2026），而设备声明的是 string；
     * 只看值就会把它们写成 unsignedInt，设备回 9007 Invalid parameter value。</p>
     */
    private static final Pattern NUMERIC_LEAF = Pattern.compile(
            "(?i)(channel|port|count|numberofentries|index|interval|seconds|"
                    + "timeout|priority|gain|tail|vlanid|status|mode|level|length|rate|limit)$");

    /**
     * 设备参数名里这些是布尔：{@code Enable} / {@code SSIDAdvertisementEnabled} 等。
     *
     * <p>值常写成 1/0，按数字发出去设备回 9007——它要的是 xsd:boolean。</p>
     */
    private static final Pattern BOOLEAN_LEAF = Pattern.compile(
            "(?i)(enable|enabled|disable|disabled)$");

    /**
     * 没有登记类型时按参数名与值一起判断：布尔语义的一律 boolean，
     * 数字语义且值是纯数字才给 unsignedInt，其余一律 string。
     */
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

    /** 参数路径的最后一段，如 {@code ...Line.1.Enable} 取 {@code Enable}。 */
    private static String leafOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String leaf = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int dot = leaf.lastIndexOf('.');
        return dot >= 0 ? leaf.substring(dot + 1) : leaf;
    }

    /** 供调用方判断某类业务是否有适配结果，避免下发时才报「查不到」。 */
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
            case "LAN":
                return !deviceLanDao.findByDeviceId(id).isEmpty();
            default:
                return false;
        }
    }

    /** LAN 网口：按实例号取对应网口。 */
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

    /** 变量清单入口，前端下发表单据此渲染输入框。 */
    public static String sample(String varName) {
        return TemplateVariables.ROOM.equals(varName) ? "房间号" : varName;
    }
}

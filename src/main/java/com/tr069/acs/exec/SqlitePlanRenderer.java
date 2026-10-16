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
import java.util.Map;
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
     * @param device    SQLite 里的已适配型号
     * @param values    本次下发的业务字段值，键可以是 {@code %room%} 或 {@code room}
     * @param instance  实例号，写第几个（第几个 SSID、第几路语音、第几个网口）
     */
    public IntentPlan render(String bizObject, String category, Device device,
                             Map<String, Object> values, int instance) {
        List<PlanItem> items = new ArrayList<>();
        List<String> objectsToAdd = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        switch (bizObject == null ? "" : bizObject) {
            case "WiFi_2G" -> wifi(device, values, instance, items, errors, true);
            case "WiFi_5G" -> wifi(device, values, instance, items, errors, false);
            case "Voice_Service" -> voice(device, values, instance, items, errors);
            case "Internet_WAN" -> wan(category, device, values, items, objectsToAdd, errors);
            default -> errors.add("该业务还没有适配模板，请先对该型号做适配: " + bizObject);
        }

        if (items.isEmpty() && errors.isEmpty()) {
            errors.add("该型号没有可下发的适配结果: bizObject=" + bizObject
                    + "，请重新发起适配");
        }
        log.debug("SQLite 渲染: model={}, bizObject={}, instance={}, 参数={}, 新建对象={}",
                device.getModelKey(), bizObject, instance, items.size(), objectsToAdd.size());
        return new IntentPlan(bizObject, instance, objectsToAdd, items, errors);
    }

    // ==================== 各业务 ====================

    private void wifi(Device device, Map<String, Object> values, int instance,
                      List<PlanItem> items, List<String> errors, boolean band24) {
        if (band24) {
            WlanA row = wlanADao.findByDeviceId(device.getId()).stream()
                    .filter(r -> r.getInstanceNo() != null && r.getInstanceNo() == instance)
                    .findFirst().orElse(null);
            if (row == null) {
                errors.add("2.4G 模板里没有第 " + instance + " 个实例，请重新适配");
                return;
            }
            put(items, errors, "WiFi_2G", "enable", row.getPathEnable(), row.getValEnable(), values, instance);
            put(items, errors, "WiFi_2G", "ssid", row.getPathSsid(), row.getValSsid(), values, instance);
            put(items, errors, "WiFi_2G", "wpa_key", row.getPathKey(), row.getValKey(), values, instance);
            put(items, errors, "WiFi_2G", "wpa_psk", row.getPathPsk(), row.getValPsk(), values, instance);
            put(items, errors, "WiFi_2G", "channel", row.getPathChannel(), row.getValChannel(), values, instance);
            put(items, errors, "WiFi_2G", "beacon", row.getPathBeacon(), row.getValBeacon(), values, instance);
            put(items, errors, "WiFi_2G", "wpa_mode", row.getPathWpaMode(), row.getValWpaMode(), values, instance);
        } else {
            WlanB row = wlanBDao.findByDeviceId(device.getId()).stream()
                    .filter(r -> r.getInstanceNo() != null && r.getInstanceNo() == instance)
                    .findFirst().orElse(null);
            if (row == null) {
                errors.add("5G 模板里没有第 " + instance + " 个实例，请重新适配");
                return;
            }
            put(items, errors, "WiFi_5G", "enable", row.getPathEnable(), row.getValEnable(), values, instance);
            put(items, errors, "WiFi_5G", "ssid", row.getPathSsid(), row.getValSsid(), values, instance);
            put(items, errors, "WiFi_5G", "wpa_key", row.getPathKey(), row.getValKey(), values, instance);
            put(items, errors, "WiFi_5G", "wpa_psk", row.getPathPsk(), row.getValPsk(), values, instance);
            put(items, errors, "WiFi_5G", "channel", row.getPathChannel(), row.getValChannel(), values, instance);
            put(items, errors, "WiFi_5G", "beacon", row.getPathBeacon(), row.getValBeacon(), values, instance);
            put(items, errors, "WiFi_5G", "wpa_mode", row.getPathWpaMode(), row.getValWpaMode(), values, instance);
        }
    }

    private void voice(Device device, Map<String, Object> values, int instance,
                       List<PlanItem> items, List<String> errors) {
        Sip row = sipDao.findByDeviceId(device.getId()).stream()
                .filter(r -> r.getInstanceNo() != null && r.getInstanceNo() == instance)
                .findFirst().orElse(null);
        if (row == null) {
            errors.add("语音模板里没有第 " + instance + " 路，请重新适配");
            return;
        }
        put(items, errors, "Voice_Service", "enable", row.getPathEnable(), row.getValEnable(), values, instance);
        put(items, errors, "Voice_Service", "sip_num", row.getPathUser(), row.getValUser(), values, instance);
        put(items, errors, "Voice_Service", "sip_pwd", row.getPathPwd(), row.getValPwd(), values, instance);
        put(items, errors, "Voice_Service", "sip_uri", row.getPathUri(), row.getValUri(), values, instance);
        put(items, errors, "Voice_Service", "sip_proxy", row.getPathProxy(), row.getValProxy(), values, instance);
        put(items, errors, "Voice_Service", "sip_port", row.getPathPort(), row.getValPort(), values, instance);
    }

    /**
     * WAN 连接：先按业务类型挑模板行（DHCP / PPPoE / Static 各一行），
     * 再把 VLAN 一并带上——VLAN 参数是挂在 WAN 下的，分开表存只是管理上的拆分。
     */
    private void wan(String category, Device device, Map<String, Object> values,
                     List<PlanItem> items, List<String> objectsToAdd, List<String> errors) {
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
        put(items, errors, "Internet_WAN", "enable", row.getPathEnable(), row.getValEnable(), values, 1);
        put(items, errors, "Internet_WAN", "conn_type", row.getPathConnType(), row.getValConnType(), values, 1);
        put(items, errors, "Internet_WAN", "addr_type", row.getPathAddrType(), row.getValAddrType(), values, 1);
        put(items, errors, "Internet_WAN", "nat", row.getPathNat(), row.getValNat(), values, 1);
        put(items, errors, "Internet_WAN", "bind_port", row.getPathBindPort(), row.getValBindPort(), values, 1);
        put(items, errors, "Internet_WAN", "pppoe_user", row.getPathUsername(), row.getValUsername(), values, 1);
        put(items, errors, "Internet_WAN", "pppoe_pwd", row.getPathPassword(), row.getValPassword(), values, 1);
        put(items, errors, "Internet_WAN", "wan_ip", row.getPathIp(), row.getValIp(), values, 1);
        put(items, errors, "Internet_WAN", "wan_mask", row.getPathMask(), row.getValMask(), values, 1);
        put(items, errors, "Internet_WAN", "wan_gw", row.getPathGw(), row.getValGw(), values, 1);
        put(items, errors, "Internet_WAN", "wan_dns", row.getPathDns(), row.getValDns(), values, 1);

        Vlan vlan = vlanDao.findOne(device.getId(), slot).orElse(null);
        if (vlan != null) {
            put(items, errors, "Internet_WAN", "vlan_id", vlan.getPathVlanId(), vlan.getValVlanId(), values, 1);
            put(items, errors, "Internet_WAN", "vlan_8021p", vlan.getPath8021p(), vlan.getVal8021p(), values, 1);
            put(items, errors, "Internet_WAN", "vlan_mode", vlan.getPathVlanMode(), vlan.getValVlanMode(), values, 1);
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
     */
    private void put(List<PlanItem> items, List<String> errors, String bizObject, String field,
                     String path, String val, Map<String, Object> values, int instance) {
        if (path == null || path.isBlank()) {
            return;
        }
        String value = resolve(field, val, values, errors);
        if (value == null) {
            return;
        }
        String realPath = path.replace(TemplateBuilder.INSTANCE_PLACEHOLDER, String.valueOf(instance));
        if (realPath.contains(TemplateBuilder.INSTANCE_PLACEHOLDER)) {
            errors.add("参数路径仍有未替换的实例号: " + realPath);
            return;
        }
        items.add(new PlanItem(bizObject, field, realPath, value, valueTypeOf(value)));
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
        m.reset();
        while (m.find()) {
            String token = m.group();
            Object raw = values == null ? null : values.get(token);
            if (raw == null && values != null) {
                raw = values.get(m.group(1));
            }
            if (raw == null) {
                errors.add("缺业务字段值: " + token + "（" + field + "）");
                return null;
            }
            out = out.replace(token, String.valueOf(raw).trim());
        }
        return out.trim();
    }

    /**
     * 没有登记类型时按值本身猜：数字给 unsignedInt，true/false 给 boolean。
     */
    private static String valueTypeOf(String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return "boolean";
        }
        if (value.matches("-?\\d+")) {
            return "unsignedInt";
        }
        return "string";
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
                              List<String> errors) {
        List<PlanItem> items = new ArrayList<>();
        DeviceLan row = deviceLanDao.findByDeviceId(device.getId()).stream()
                .filter(r -> r.getInstanceNo() != null && r.getInstanceNo() == instance)
                .findFirst().orElse(null);
        if (row == null) {
            errors.add("网口模板里没有第 " + instance + " 个网口，请重新适配");
            return items;
        }
        put(items, errors, "LAN", "enable", row.getPathEnable(), row.getValEnable(), values, instance);
        put(items, errors, "LAN", "duplex", row.getPathDuplex(), row.getValDuplex(), values, instance);
        put(items, errors, "LAN", "speed", row.getPathSpeed(), row.getValSpeed(), values, instance);
        return items;
    }

    /** 变量清单入口，前端下发表单据此渲染输入框。 */
    public static String sample(String varName) {
        return TemplateVariables.ROOM.equals(varName) ? "房间号" : varName;
    }
}

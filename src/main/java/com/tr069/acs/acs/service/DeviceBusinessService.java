package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.acs.adaptation.AdaptationManager;
import com.tr069.acs.acs.adaptation.IspAdaptation;
import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.cwmp.WanServiceClassifier;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 设备业务信息解析与采集 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceBusinessService {

    /** 业务分区定义：key 即返回 JSON 里的字段名，instance 是"实例对象路径"的匹配规则 */
    private record Section(String key, String name, Pattern instance) {
    }

    /** 兜底根节点：设备台账里没有记录时按 TR-098 处理 */
    private static final String DEFAULT_ROOT = DataModel.TR098.getRoot();

    private static final List<Section> SECTIONS = List.of(
            new Section("device", "设备信息",
                    Pattern.compile("\\.DeviceInfo\\.$")),
            new Section("wan", "WAN 连接",
                    Pattern.compile("\\.(WANPPPConnection|WANIPConnection)\\.\\d+\\.$")),
            new Section("wifi", "WLAN",
                    Pattern.compile("\\.(WLANConfiguration\\.\\d+|WiFi\\.SSID\\.\\d+)\\.$")),
            new Section("lan", "LAN 网口",
                    Pattern.compile("\\.(LANDevice\\.\\d+\\.LANEthernetInterfaceConfig\\.\\d+|Ethernet\\.Interface\\.\\d+)\\.$")),
            new Section("sip", "SIP 语音",
                    Pattern.compile("VoiceService\\.\\d+\\.VoiceProfile\\.\\d+\\.(SIP(\\.\\d+)?|Line\\.\\d+\\.SIP)\\.$")));

    private final CpeParameterMapper parameterMapper;
    private final CpeDeviceMapper deviceMapper;
    private final DeviceCapabilityService capabilityService;
    /** 按设备运营商取定制参数名，替代硬编码 X_CMCC_/X_CT-COM_ 常量 */
    private final AdaptationManager adaptationManager;

    /** 业务采集等待设备的超时（秒），超过就返回已刷到库里的部分结果 */
    @Value("${acs.device.business.collect-timeout-seconds:45}")
    private long collectTimeoutSeconds;

    // ==================== 查询 ====================

    /** 按设备运营商取适配实现 */
    private IspAdaptation adaptationOf(String sn) {
        CpeDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                .eq(CpeDevice::getSn, sn)
                .last("LIMIT 1"));
        String isp = device == null || device.getIsp() == null || device.getIsp().isBlank()
                ? "standard" : device.getIsp();
        return adaptationManager.of(isp);
    }

    /** 从库里的能力快照解析业务信息，不与设备交互 */
    public Map<String, Object> summary(String sn) {
        String root = resolveRoot(sn);
        IspAdaptation adaptation = adaptationOf(sn);
        List<CpeParameter> params = selectBusinessParams(sn);
        Map<String, Object> data = baseInfo(sn, root);
        for (Section section : SECTIONS) {
            data.put(section.key(), groupInstances(params, section));
        }
        data.put("pon", ponParams(sn));
        // 电信机型的 VLAN / 封装模式挂在 WANConnectionDevice.{i}.X_CT-COM_WANGponLinkConfig 上，按实例聚合会漏掉，先并回 WAN 行
        mergeWanDeviceParams(params, data);
        enrichDevice(data);
        enrichWan(data, adaptation);
        enrichWifi(data);
        enrichLan(data);
        enrichSip(data);
        enrichPon(data);
        // 设备能力串（2.4G WiFi、5.8G WiFi、电话）：页面原来自己翻 wifi/sip 两份列表拼
        data.put("capabilityText", capabilityText(data));
        return data;
    }

    /** 设备能力串：WiFi 频段 + 有没有电话口，如「2.4G WiFi、5.8G WiFi、1电话」 */
    @SuppressWarnings("unchecked")
    private static String capabilityText(Map<String, Object> data) {
        List<String> parts = new ArrayList<>();
        Object wifiObj = data.get(WIFI_KEY);
        if (wifiObj instanceof List<?> wifi) {
            boolean band24 = false;
            boolean band58 = false;
            for (Object row : wifi) {
                if (!(row instanceof Map<?, ?> map)) {
                    continue;
                }
                String band = str(((Map<String, Object>) map).get("band"));
                if (BAND_24.equals(band)) {
                    band24 = true;
                } else if (BAND_58.equals(band)) {
                    band58 = true;
                }
            }
            if (band24) {
                parts.add("2.4G WiFi");
            }
            if (band58) {
                parts.add("5.8G WiFi");
            }
        }
        Object sipObj = data.get(SIP_KEY);
        if (sipObj instanceof List<?> sip && !sip.isEmpty()) {
            parts.add("1电话");
        }
        return parts.isEmpty() ? null : String.join("、", parts);
    }

    /** 批量解析：设备列表页一次拉整页设备的业务概要，避免每行一个请求 */
    public Map<String, Map<String, Object>> summaryBatch(List<String> sns) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (sns == null) {
            return result;
        }
        for (String sn : sns) {
            if (sn == null || sn.isBlank()) {
                continue;
            }
            try {
                result.put(sn.trim(), summary(sn.trim()));
            } catch (Exception e) {
                log.warn("批量业务信息解析失败: sn={}, err={}", sn, e.getMessage());
            }
        }
        return result;
    }

    /** 对在线设备重新读取业务参数，再回库解析 */
    public Map<String, Object> collect(String sn) {
        String root = resolveRoot(sn);
        List<CpeParameter> params = selectBusinessParams(sn);
        List<String> paths = collectiblePaths(params);
        if (paths.isEmpty()) {
            Map<String, Object> data = baseInfo(sn, root);
            for (Section section : SECTIONS) {
                data.put(section.key(), List.of());
            }
            data.put("collectStatus", "empty");
            data.put("message", "设备能力树为空或未覆盖业务路径，请先执行能力树采集");
            return data;
        }
        log.info("采集设备业务参数: sn={}, pathCount={}", sn, paths.size());
        // PON/光模块参数不在业务分区实例下，单独补进采集清单
        paths.addAll(ponParams(sn).keySet());
        // 点刷新要的是「现在的值」：先叫上来建立会话，不然指令在队列里等下一轮周期上报，等满超时拿到的还是库里的旧值
        capabilityService.wakeUpAndAwait(sn);
        // WAN 多实例发现：库里可能只有实例 1（电信机对 NumberOfEntries 拒答），按实例 1 叶子模板盲探 2..N，探到就补整组叶子，否则「重新采集」永远只读到旧的一条
        paths.addAll(discoverWanInstances(sn, paths));
        CompletableFuture<Map<String, String>> future =
                capabilityService.readPathValues(sn, paths);
        String status;
        try {
            future.get(collectTimeoutSeconds, TimeUnit.SECONDS);
            status = "success";
        } catch (TimeoutException e) {
            status = "timeout";
            // 半成品不落库：超时先返回库内旧快照；后台链把全部路径测完（读到或明确拒答）才统一落库
            log.warn("业务参数采集超时，先返回库内快照，后台测完后统一落库: sn={}, paths={}",
                    sn, paths.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "fail";
        } catch (Exception e) {
            status = "fail";
            log.error("业务参数采集失败: sn={}", sn, e);
        }
        Map<String, Object> data = summary(sn);
        data.put("collectStatus", status);
        return data;
    }

    /** WAN 多实例发现：按实例 1 叶子模板生成 2..N 探测路径（每实例读一条叶子），探到值再把整组叶子补回采集清单；读不到的由 readPathValues 二分容错自然跳过 */
    private List<String> discoverWanInstances(String sn, List<String> paths) {
        List<String> ipTpls = new ArrayList<>();
        List<String> pppTpls = new ArrayList<>();
        List<String> linkTpls = new ArrayList<>();
        for (String path : paths) {
            if (path.contains("WANConnectionDevice.1.WANIPConnection.1.")) {
                ipTpls.add(path);
            } else if (path.contains("WANConnectionDevice.1.WANPPPConnection.1.")) {
                pppTpls.add(path);
            } else if (path.contains("WANConnectionDevice.1.X_CT-COM_WANGponLinkConfig.")) {
                linkTpls.add(path);
            }
        }
        if (ipTpls.isEmpty() && pppTpls.isEmpty() && linkTpls.isEmpty()) {
            return List.of();
        }
        // 探测路径 → {cd, ci, 类型(0=IP/1=PPP/2=GPON链路层)}：命中后按类型展开整组叶子
        Map<String, int[]> probes = new LinkedHashMap<>();
        for (int cd = 1; cd <= 8; cd++) {
            for (int ci = 1; ci <= (cd == 1 ? 2 : 1); ci++) {
                if (cd == 1 && ci == 1) {
                    continue; // 实例 1 已在采集清单里
                }
                if (!ipTpls.isEmpty()) {
                    probes.put(expandWanTpl(ipTpls.get(0), cd, ci), new int[]{cd, ci, 0});
                }
                if (!pppTpls.isEmpty()) {
                    probes.put(expandWanTpl(pppTpls.get(0), cd, ci), new int[]{cd, ci, 1});
                }
            }
            if (!linkTpls.isEmpty()) {
                probes.put(expandWanTpl(linkTpls.get(0), cd, 1), new int[]{cd, 1, 2});
            }
        }
        if (probes.isEmpty()) {
            return List.of();
        }
        log.info("盲探 WAN 多实例: sn={}, probeCount={}", sn, probes.size());
        Map<String, String> found;
        try {
            found = capabilityService.readPathValues(sn, new ArrayList<>(probes.keySet()))
                    .get(collectTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("盲探 WAN 多实例失败: sn={}, err={}", sn, e.getMessage());
            return List.of();
        }
        List<String> extra = new ArrayList<>();
        int hit = 0;
        for (Map.Entry<String, int[]> entry : probes.entrySet()) {
            String value = found.get(entry.getKey());
            if (value == null || value.isBlank()) {
                continue;
            }
            hit++;
            int cd = entry.getValue()[0];
            int ci = entry.getValue()[1];
            List<String> tplsByType = entry.getValue()[2] == 0 ? ipTpls
                    : entry.getValue()[2] == 1 ? pppTpls : linkTpls;
            for (String tpl : tplsByType) {
                extra.add(expandWanTpl(tpl, cd, ci));
            }
        }
        if (hit > 0) {
            log.info("盲探命中 {} 个 WAN 实例，追加 {} 条采集路径: sn={}", hit, extra.size(), sn);
        }
        return extra;
    }

    /** 实例 1 的路径模板 → 指定 (ConnectionDevice.cd, 连接.ci) 的路径 */
    private static String expandWanTpl(String tpl, int cd, int ci) {
        return tpl.replace("WANConnectionDevice.1.", "WANConnectionDevice." + cd + ".")
                .replace("WANIPConnection.1.", "WANIPConnection." + ci + ".")
                .replace("WANPPPConnection.1.", "WANPPPConnection." + ci + ".");
    }

    /** 挑出需要向设备重新读取的路径：命中分区实例对象的叶子参数 */
    private List<String> collectiblePaths(List<CpeParameter> params) {
        Set<String> paths = new LinkedHashSet<>();
        Set<String> sipParents = new LinkedHashSet<>();
        Set<String> lanParents = new LinkedHashSet<>();
        for (Section section : SECTIONS) {
            for (CpeParameter row : params) {
                String parent = parentOf(row);
                if (!parent.isEmpty() && section.instance().matcher(parent).find()) {
                    paths.add(row.getParamPath());
                    switch (section.key()) {
                        case "sip" -> sipParents.add(parent);
                        case "lan" -> lanParents.add(parent);
                        default -> { /* no-op */ }
                    }
                }
            }
        }
        for (String parent : sipParents) {
            paths.add(parent + "AuthUserName");
            paths.add(parent + "AuthPassword");
            paths.add(parent + "URI");
            paths.add(parent + "UserName");
            paths.add(parent + "DirectoryNumber");
            paths.add(parent + "PhoneNumber");
            paths.add(parent + "Enable");
            paths.add(parent + "Status");
            paths.add(parent + "SIPRegisterStatus");
        }
        for (String parent : lanParents) {
            paths.add(parent + "Enable");
            paths.add(parent + "Status");
            paths.add(parent + "X_CT-COM-UpLineStatus");
            paths.add(parent + "X_CT-COM_UpLineStatus");
            paths.add(parent + "X_CT-COM-LineStatus");
            paths.add(parent + "X_CT-COM_LineStatus");
            paths.add(parent + "MACAddress");
            paths.add(parent + "MaxBitRate");
            paths.add(parent + "DuplexMode");
        }
        return new ArrayList<>(paths);
    }

    // ==================== LAN 网口状态 ====================

    /** 列出该设备 LAN 网口需要重读的状态参数路径，供定时自动刷新复用 */
    public List<String> lanStatusPaths(String sn) {
        IspAdaptation adaptation = adaptationOf(sn);
        Section lan = SECTIONS.stream()
                .filter(s -> LAN_KEY.equals(s.key()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少 LAN 分区定义"));
        List<String> prefixes = DataModel.businessPrefixes(LAN_KEY);
        List<CpeParameter> params = parameterMapper.selectList(new LambdaQueryWrapper<CpeParameter>()
                .eq(CpeParameter::getSn, sn)
                .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                .and(w -> {
                    for (int i = 0; i < prefixes.size(); i++) {
                        if (i == 0) {
                            w.likeRight(CpeParameter::getParamPath, prefixes.get(i));
                        } else {
                            w.or().likeRight(CpeParameter::getParamPath, prefixes.get(i));
                        }
                    }
                }));
        Set<String> parents = new LinkedHashSet<>();
        for (CpeParameter row : params) {
            String parent = parentOf(row);
            if (!parent.isEmpty() && lan.instance().matcher(parent).find()) {
                parents.add(parent);
            }
        }
        List<String> paths = new ArrayList<>();
        for (String parent : parents) {
            for (String leaf : adaptation.lanStatusLeaves()) {
                paths.add(parent + leaf);
            }
        }
        return paths;
    }

    // ==================== 分区解析 ====================

    /** 按实例对象分组。返回结构 */
    private List<Map<String, Object>> groupInstances(List<CpeParameter> params, Section section) {
        // TreeMap 保证实例按序号自然排列
        Map<String, Map<String, String>> grouped = new TreeMap<>();
        for (CpeParameter row : params) {
            String parent = parentOf(row);
            if (parent.isEmpty() || !section.instance().matcher(parent).find()) {
                continue;
            }
            Map<String, String> fields =
                    grouped.computeIfAbsent(parent, k -> new LinkedHashMap<>());
            String name = normalizeLeaf(DeviceCapabilityService.nameOf(row.getParamPath()));
            fields.putIfAbsent(name, row.getParamValue());
        }
        List<Map<String, Object>> result = new ArrayList<>(grouped.size());
        for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.getKey());
            item.put("params", entry.getValue());
            result.add(item);
        }
        return result;
    }

    /** WAN 分区 key，与 SECTIONS 里的定义对应 */
    private static final String WAN_KEY = "wan";

    /** 把 WANConnectionDevice 直属子对象（电信 X_CT-COM_WANGponLinkConfig 等）的参数并回同前缀 WAN 连接行；WAN(IP|PPP)Connection 自身也是直属子对象但排除——并给兄弟实例就是串数据 */
    private void mergeWanDeviceParams(List<CpeParameter> params, Map<String, Object> data) {
        if (!(data.get(WAN_KEY) instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        Pattern linkObject = Pattern.compile("\\.WANConnectionDevice\\.\\d+\\.[^.]+\\.$");
        Map<String, Map<String, String>> byDevice = new TreeMap<>();
        for (CpeParameter row : params) {
            String parent = parentOf(row);
            if (parent.isEmpty() || WAN_CONN_INSTANCE.matcher(parent).find()
                    || !linkObject.matcher(parent).find()) {
                continue;
            }
            String device = wanDevicePath(parent);
            byDevice.computeIfAbsent(device, k -> new LinkedHashMap<>())
                    .putIfAbsent(normalizeLeaf(DeviceCapabilityService.nameOf(row.getParamPath())),
                            row.getParamValue());
        }
        if (byDevice.isEmpty()) {
            return;
        }
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) raw;
            Map<String, String> rowParams = paramsOf(item);
            if (rowParams.isEmpty()) {
                continue;
            }
            Map<String, String> extra = byDevice.get(wanDevicePath(
                    String.valueOf(item.getOrDefault("path", ""))));
            if (extra != null) {
                extra.forEach(rowParams::putIfAbsent);
            }
        }
    }

    /** 从对象路径剥掉最后一段，得到父对象路径（保留结尾句点） */
    private static String wanDevicePath(String objectPath) {
        if (objectPath == null || objectPath.isEmpty()) {
            return "";
        }
        String s = objectPath.endsWith(".") ? objectPath.substring(0, objectPath.length() - 1) : objectPath;
        int idx = s.lastIndexOf('.');
        return idx < 0 ? "" : s.substring(0, idx + 1);
    }


    /** 给 WAN 连接补业务语义，并算出设备的管理地址 */
    @SuppressWarnings("unchecked")
    private void enrichWan(Map<String, Object> data, IspAdaptation adaptation) {
        Object wanObj = data.get(WAN_KEY);
        if (!(wanObj instanceof List<?> list)) {
            return;
        }
        String ip = null;
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            Map<String, String> params = paramsOf(item);
            // 电信私有名 X_CT-COM_VLANID 归一化后叫 VLANID，页面读标准名 VLANIDMark：补别名，VLAN ID 才不会恒显示 "-"
            String vlan = firstNonBlank(params, "VLANIDMark", "VLANID");
            if (vlan != null) {
                params.put("VLANIDMark", vlan);
            }
            String path = String.valueOf(item.getOrDefault("path", ""));
            String service = serviceOf(params, path);
            item.put("service", service);
            boolean tr069 = "TR069".equalsIgnoreCase(service);
            item.put("tr069", tr069);
            item.put("up", isUp(params));
            item.put("statusText", wanStatusText(params));
            item.put("title", wanTitle(params, path));
            item.put("connMode", connModeOf(params));
            item.put("bindPorts", bindPortsOf(params, adaptation));
            item.put("natEnabled", boolOf(params.get("NATEnabled"), false));
            item.put("ipProtocol", ipProtocolOf(params, adaptation));
            item.put("mtu", mtuOf(params));
            item.put("uptimeText", uptimeText(firstNonBlank(params, "Uptime", "UpTime")));
            item.put("shortName", wanShortName(path));
            if (tr069 && ip == null) {
                String value = params.get("ExternalIPAddress");
                if (value != null && !value.isBlank() && !"0.0.0.0".equals(value.trim())) {
                    ip = value.trim();
                }
            }
        }
        // 管理地址 = TR069 通道地址；取不到就是没采到，不退回其它通道
        data.put("ip", ip);
        data.put("ipSource", ip == null ? null : "WAN_TR069");
    }

    /** 一条 WAN 连接的业务类型 */
    private static String serviceOf(Map<String, String> params, String path) {
        return WanServiceClassifier.classify(params, path);
    }

    /** WAN 连接实例：WANIPConnection.1. / WANPPPConnection.1 */
    private static final Pattern WAN_CONN_INSTANCE = Pattern.compile("WAN(PPP|IP)Connection\\.(\\d+)\\.$");

    /** WAN 连接是否联通：ConnectionStatus / Status 报 Connected / Up */
    private static boolean isUp(Map<String, String> params) {
        String s = firstNonBlank(params, "ConnectionStatus", "Status").toLowerCase(Locale.ROOT);
        return "connected".equals(s) || "up".equals(s);
    }

    /** 地址协议类型：{@code IPv4} / {@code IPv6} / {@code IPv4+IPv6}，空串表示判不出来 */
    private static String ipProtocolOf(Map<String, String> params, IspAdaptation adaptation) {
        // 桥接也有地址族：IPMode 是多少就显示多少，不替设备决定
        String mode = firstNonBlank(params, adaptation.ipModeNames().toArray(String[]::new));
        switch (mode) {
            case "1":
                return "IPv4";
            case "2":
                return "IPv6";
            case "3":
                return "IPv4+IPv6";
            default:
                break;
        }
        boolean v4 = hasIpv4(params);
        boolean v6 = hasIpv6(params, adaptation);
        if (v4 && v6) {
            return "IPv4+IPv6";
        }
        if (v4) {
            return "IPv4";
        }
        return v6 ? "IPv6" : "";
    }

    /** 是否拿到了可用的 IPv4 地址 */
    private static boolean hasIpv4(Map<String, String> params) {
        String ip = firstNonBlank(params, "ExternalIPAddress", "IPAddress");
        return !ip.isEmpty() && !"0.0.0.0".equals(ip);
    }

    /** IPv6 侧是否已起来：连接状态为 Connected，或已分配到 IPv6 地址 */
    private static boolean hasIpv6(Map<String, String> params, IspAdaptation adaptation) {
        String status = firstNonBlank(params, adaptation.ipv6StatusNames().toArray(String[]::new));
        if ("connected".equalsIgnoreCase(status) || "up".equalsIgnoreCase(status)) {
            return true;
        }
        String addr = firstNonBlank(params, adaptation.ipv6AddressNames().toArray(String[]::new));
        return !addr.isEmpty() && !"::".equals(addr);
    }

    /** 该连接的 MTU：优先标准参数 {@code MaxMTUSize}，部分厂商只报 {@code ExternalMTU} */
    private static String mtuOf(Map<String, String> params) {
        String mtu = firstNonBlank(params, "MaxMTUSize", "ExternalMTU");
        return "0".equals(mtu) ? "" : mtu;
    }

    /** 连接模式 + 地址类型的组合文案，如「路由 / DHCP」「桥接」 */
    private static String connModeOf(Map<String, String> params) {
        String type = firstNonBlank(params, "ConnectionType");
        String typeLower = type.toLowerCase(Locale.ROOT);
        if (typeLower.contains("bridged")) {
            return "桥接";
        }
        String addr = firstNonBlank(params, "IPAddressingType", "AddressingType");
        if (addr.isBlank()) {
            // 电信定制机把获取地址方式放在 X_CT-COM_IPMode（GPON 链路对象上，随 WAN 行合并进来的）
            addr = firstNonBlank(params, "IPMode");
        }
        if (addr.isBlank()) {
            addr = typeLower.contains("ppp") ? "PPPoE" : "-";
        }
        return "路由 / " + addr;
    }

    /** 绑定端口：设备存的是 LAN 口参数路径（移动为 X_CMCC_LanInterface） */
    private static String bindPortsOf(Map<String, String> params, IspAdaptation adaptation) {
        String raw = firstNonBlank(params, adaptation.bindPortNames().toArray(String[]::new));
        if (raw.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split("[,;\\s]+")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            Matcher m = LAN_PORT_INDEX.matcher(p);
            String port = m.find() ? "LAN" + m.group(1) : p;
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(port);
        }
        return sb.toString();
    }

    /** LAN 口路径末段：{@code LANEthernetInterfaceConfig.1} → 1 */
    private static final Pattern LAN_PORT_INDEX =
            Pattern.compile("LANEthernetInterfaceConfig\\.(\\d+)$", Pattern.CASE_INSENSITIVE);

    /** 连接标题：设备报了 Name（如 1_TR069_R_VID_46）就用它，否则用实例序号 */
    private static String wanTitle(Map<String, String> params, String path) {
        String name = firstNonBlank(params, "Name");
        if (!name.isBlank()) {
            return name;
        }
        Matcher m = WAN_CONN_INSTANCE.matcher(path);
        return m.find() ? m.group(2) : path;
    }

    /** 设备没上报 Name 时的兜底叫法：WANPPPConnection.1. →「WAN PPP 1」 */
    private static String wanShortName(String path) {
        Matcher m = WAN_CONN_INSTANCE.matcher(path);
        return m.find() ? "WAN " + m.group(1) + " " + m.group(2) : "WAN";
    }

    /** 运行时长格式化：秒 →「1天2小时3分4秒」。页面三处各写一份且有两份漏了秒，统一到这一个 */
    private static String uptimeText(String seconds) {
        if (seconds == null || seconds.isBlank()) {
            return "";
        }
        long n;
        try {
            n = Long.parseLong(seconds.trim());
        } catch (NumberFormatException e) {
            return "";
        }
        if (n <= 0) {
            return "";
        }
        long d = n / 86400;
        long h = (n % 86400) / 3600;
        long mi = (n % 3600) / 60;
        long s = n % 60;
        return (d > 0 ? d + "天" : "") + h + "小时" + mi + "分" + s + "秒";
    }

    /** WiFi 分区 key，与 SECTIONS 里的定义对应 */
    private static final String WIFI_KEY = "wifi";

    private static final String BAND_24 = "2.4G";
    private static final String BAND_58 = "5.8G";

    /** 从 WLANConfiguration.1. / WiFi.SSID.1. 这类路径里取实例号 */
    private static final Pattern WIFI_INSTANCE = Pattern.compile(
            "(?:WLANConfiguration|WLAN|SSID)\\.(\\d+)\\.$");

    /** SSID 名里的 5G 后缀，运营商基本都这么命名 */
    private static final Pattern SSID_5G = Pattern.compile("(?i)-?5g(\\b|$)");

    /** 给 WLAN 实例补业务语义：频段、开关、是否加密、SSID、实例号 */
    @SuppressWarnings("unchecked")
    private void enrichWifi(Map<String, Object> data) {
        Object wifiObj = data.get(WIFI_KEY);
        if (!(wifiObj instanceof List<?> list)) {
            return;
        }
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            Map<String, String> params = paramsOf(item);
            String path = String.valueOf(item.getOrDefault("path", ""));
            item.put("band", wifiBandOf(params, path));
            item.put("enabled", isWifiEnabled(params));
            item.put("encrypted", isWifiEncrypted(params));
            item.put("ssid", firstNonBlank(params, "SSID"));
            // WLAN 的开关是 Enable、状态是 Status，两个字段不是一回事：页面上行状态灯看 up，芯片开关看 enabled
            item.put("up", isUp(params));
            item.put("statusText", wifiStatusText(params));
            // 编辑弹窗要的是「真正可写的那个参数」：拿 BeaconType 的值填进 IEEE11iEncryptionModes 设备会回 9003，这里按优先级把参数名和当前值一起下发，前端不猜协议参数
            String[] encryption = pickParam(params,
                    "IEEE11iEncryptionModes", "BasicEncryptionModes", "WPAEncryptionModes");
            item.put("encryptionParam", encryption[0]);
            item.put("encryptionValue", encryption[1]);
            String[] key = pickParam(params, "KeyPassphrase", "PreSharedKey", "WPA_PSK");
            item.put("keyParam", key[0]);
            item.put("keyValue", key[1]);
            Matcher m = WIFI_INSTANCE.matcher(path);
            item.put("instance", m.find() ? m.group(1) : "");
        }
    }

    /** 按优先级取第一个非空的参数，返回 {@code [参数名, 值]}；都没有时给两个空串 */
    private static String[] pickParam(Map<String, String> params, String... names) {
        for (String name : names) {
            String value = params.get(name);
            if (value != null && !value.isBlank()) {
                return new String[]{name, value};
            }
        }
        return new String[]{"", ""};
    }

    /** 判定一条 WLAN 实例工作在哪个频段 */
    private static String wifiBandOf(Map<String, String> params, String path) {
        // 1) RFBand：电信规范 0 = 2.4G、1 = 5G，华为 / 中兴 / 烽火都报，是最准的一级
        String rfBand = params.get("RFBand");
        if (rfBand != null && !rfBand.isBlank()) {
            String v = rfBand.trim();
            if ("0".equals(v)) {
                return BAND_24;
            }
            if ("1".equals(v)) {
                return BAND_58;
            }
        }
        // 2) 设备直接报的频段
        String band = firstNonBlank(params, "OperatingFrequencyBand", "FrequencyBand")
                .toLowerCase(Locale.ROOT);
        if (band.contains("5")) {
            return BAND_58;
        }
        if (band.contains("2.4") || band.contains("24g") || band.contains("2g")) {
            return BAND_24;
        }
        // 3) 支持制式：b/g/n 是 2.4G 射频，a/ac 是 5G 射频；只在报成列表时才可信
        String std = firstNonBlank(params, "SupportedStandards", "Standard").toLowerCase(Locale.ROOT);
        if (std.contains(",")) {
            boolean has24 = false;
            boolean has5 = false;
            for (String p : std.split("[,|\\s]+")) {
                if (p.isBlank()) {
                    continue;
                }
                if ("b".equals(p) || "g".equals(p) || "11b".equals(p) || "11g".equals(p)) {
                    has24 = true;
                }
                if ("a".equals(p) || "ac".equals(p) || "ax".equals(p)
                        || "11a".equals(p) || "11ac".equals(p)) {
                    has5 = true;
                }
            }
            if (has24 && !has5) {
                return BAND_24;
            }
            if (has5 && !has24) {
                return BAND_58;
            }
        }
        // 4) SSID 名带 5G 后缀
        if (SSID_5G.matcher(firstNonBlank(params, "SSID")).find()) {
            return BAND_58;
        }
        // 5) 兜底按实例号：电信/移动光猫约定前 4 个 SSID 是 2.4G，第 5 个起是 5G
        Matcher m = WIFI_INSTANCE.matcher(path);
        if (m.find()) {
            try {
                if (Integer.parseInt(m.group(1)) >= 5) {
                    return BAND_58;
                }
            } catch (NumberFormatException e) {
                // 实例号不是数字就走默认
            }
        }
        return BAND_24;
    }

    /** WLAN 开关。状态字段优先：{@code Status} 明确是 disabled/down 才算关 */
    private static boolean isWifiEnabled(Map<String, String> params) {
        String status = firstNonBlank(params, "Status").toLowerCase(Locale.ROOT);
        if ("disabled".equals(status) || "down".equals(status)) {
            return false;
        }
        return boolOf(params.get("Enable"), true);
    }

    /** 是否加密。{@code BasicEncryptionModes} / {@code BeaconType} 里含 None/Open 即判为不加密 */
    private static boolean isWifiEncrypted(Map<String, String> params) {
        String enc = firstNonBlank(params, "BasicEncryptionModes", "BeaconType").toLowerCase(Locale.ROOT);
        return !enc.contains("none") && !enc.contains("open");
    }

    /** WLAN 状态文案：Up/Enabled 为正常，Disabled 为已禁用，其余按设备原值显示 */
    private static String wifiStatusText(Map<String, String> params) {
        String raw = firstNonBlank(params, "Status");
        if (raw.isBlank()) {
            return "未知";
        }
        String s = raw.toLowerCase(Locale.ROOT);
        if ("up".equals(s) || "enabled".equals(s)) {
            return "正常";
        }
        if ("disabled".equals(s)) {
            return "已禁用";
        }
        return raw;
    }

    /** WAN 连接状态文案：Connected/Up 为已连接，Disconnected/Down 为未连接 */
    private static String wanStatusText(Map<String, String> params) {
        String raw = firstNonBlank(params, "ConnectionStatus", "Status");
        String s = raw.toLowerCase(Locale.ROOT);
        if ("connected".equals(s) || "up".equals(s)) {
            return "已连接";
        }
        if ("disconnected".equals(s) || "down".equals(s)) {
            return "未连接";
        }
        // 中间态照设备原词翻成中文：直接回显 "Connecting" 会让页面上中英混排
        switch (s) {
            case "connecting":
                return "连接中";
            case "authenticating":
                return "认证中";
            case "pending":
                return "等待中";
            case "error":
            case "unconfigured":
                return "异常";
            default:
                return raw.isBlank() ? "-" : raw;
        }
    }

    /** CWMP 的布尔写法不统一：{@code 1/0}、{@code true/false}、{@code Up/Down} */
    private static boolean boolOf(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String s = value.trim().toLowerCase(Locale.ROOT);
        if (List.of("0", "false", "no", "off", "disabled", "down").contains(s)) {
            return false;
        }
        if (List.of("1", "true", "yes", "on", "enabled", "up").contains(s)) {
            return true;
        }
        return fallback;
    }

    /** 取第一个有值的参数，都没有返回空串 */
    private static String firstNonBlank(Map<String, String> params, String... names) {
        for (String name : names) {
            String value = params.get(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> paramsOf(Map<String, Object> item) {
        Object obj = item.get("params");
        return obj instanceof Map<?, ?> ? (Map<String, String>) obj : Map.of();
    }

    /** LAN 分区 key，与 SECTIONS 里的定义对应 */
    private static final String LAN_KEY = "lan";

    /** 从 LANEthernetInterfaceConfig.1. 或 Ethernet.Interface.1. 路径里取实例号 */
    private static final Pattern LAN_INSTANCE = Pattern.compile(
            "(?:LANEthernetInterfaceConfig|Ethernet\\.Interface)\\.(\\d+)\\.$");

    /** 给 LAN 网口补业务状态：是否插线、MAC 地址、实例序号 */
    @SuppressWarnings("unchecked")
    private void enrichLan(Map<String, Object> data) {
        Object lanObj = data.get(LAN_KEY);
        if (!(lanObj instanceof List<?> list)) {
            return;
        }
        String faceplateMac = String.valueOf(data.getOrDefault("mac", ""));
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            Object paramObj = item.get("params");
            Map<String, String> params = paramObj instanceof Map<?, ?>
                    ? (Map<String, String>) paramObj : Map.of();
            boolean connected = isLanConnected(params);
            item.put("connected", connected);
            String mac = params.get("MACAddress");
            mac = mac == null ? "" : mac.trim();
            item.put("mac", mac);
            // 铭牌 MAC = LAN 侧第一个网口的地址（设备标签印的就是它）；WAN 的 MACAddress 随通道变不能认设备，所以先在本分区取、WAN 段不覆盖
            if (faceplateMac.isEmpty()) {
                faceplateMac = mac;
            }
            String path = String.valueOf(item.getOrDefault("path", ""));
            Matcher m = LAN_INSTANCE.matcher(path);
            item.put("instance", m.find() ? m.group(1) : "");
        }
        data.put("mac", faceplateMac);
    }

    /** 判定 LAN 网口是否插线。优先看厂商扩展状态，再看标准 Status */
    private static boolean isLanConnected(Map<String, String> params) {
        for (String key : List.of("UpLineStatus", "LineStatus", "Status")) {
            String value = params.get(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            String s = value.trim().toLowerCase(Locale.ROOT);
            if (s.equals("up") || s.equals("connected") || s.equals("true")
                    || s.equals("enabled") || s.equals("1")) {
                return true;
            }
            if (s.equals("down") || s.equals("disconnected") || s.equals("false")
                    || s.equals("disabled") || s.equals("0")) {
                return false;
            }
        }
        return false;
    }

    /** SIP 分区 key，与 SECTIONS 里的定义对应 */
    private static final String SIP_KEY = "sip";

    /** 真正的 SIP 线路实例：VoiceProfile.{n}.SIP.{i}. 或 VoiceProfile.{n}.Line.{i}.SIP */
    private static final Pattern SIP_LINE_INSTANCE =
            Pattern.compile("VoiceService\\.\\d+\\.VoiceProfile\\.\\d+\\.(SIP\\.\\d+|Line\\.\\d+\\.SIP)\\.$");

    /** URI 里 @ 前面的号码部分 */
    private static final Pattern SIP_URI_NUMBER = Pattern.compile("^([^@]+)@");

    /** VoiceProfile 级的 SIP 对象：整档语音的服务器地址挂在它身上，不是某个话机口 */
    private static final Pattern SIP_PROFILE_INSTANCE =
            Pattern.compile("VoiceService\\.\\d+\\.VoiceProfile\\.\\d+\\.SIP\\.$");

    /** 从 VoiceProfile 级补到每一路 Line 上的字段：只有服务器相关的才补 */
    private static final List<String> SIP_SERVER_KEYS = List.of(
            "RegistrarServer", "RegistrarServerPort", "RegistrarServerTransport",
            "ProxyServer", "ProxyServerPort", "ProxyServerTransport",
            "OutboundProxy", "OutboundProxyPort",
            "UserAgentDomain", "UserAgentPort", "UserAgentTransport",
            "RegisterExpires");

    /** 过滤 SIP 分组里的非线路实例 */
    @SuppressWarnings("unchecked")
    private void enrichSip(Map<String, Object> data) {
        Object sipObj = data.get(SIP_KEY);
        if (!(sipObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>(rawList.size());
        for (Object row : rawList) {
            if (row instanceof Map<?, ?>) {
                list.add((Map<String, Object>) row);
            }
        }
        boolean hasLineInstance = false;
        for (Map<String, Object> row : list) {
            String path = String.valueOf(row.getOrDefault("path", ""));
            if (SIP_LINE_INSTANCE.matcher(path).find()) {
                hasLineInstance = true;
                break;
            }
        }
        if (!hasLineInstance) {
            // 只有汇总对象时保留它，避免直接丢数据；语义照常补，页面不至于显示成空白行
            applySipSemantics(list);
            return;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : list) {
            String path = String.valueOf(row.getOrDefault("path", ""));
            if (SIP_LINE_INSTANCE.matcher(path).find()) {
                filtered.add(row);
            }
        }
        // 服务器地址要先补再算语义：statusText/number/server 都读 params，顺序反了 server 会先被判成「-」再也没机会改回来
        Map<String, String> profileParams = profileSipParams(list);
        for (Map<String, Object> row : filtered) {
            mergeServerParams(row, profileParams);
        }
        applySipSemantics(filtered);
        data.put(SIP_KEY, filtered);
    }

    /** VoiceProfile 级 SIP 对象里的服务器类参数 */
    private static Map<String, String> profileSipParams(List<Map<String, Object>> rows) {
        Map<String, String> profile = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String path = String.valueOf(row.getOrDefault("path", ""));
            if (!SIP_PROFILE_INSTANCE.matcher(path).find()) {
                continue;
            }
            Map<String, String> params = paramsOf(row);
            for (String key : SIP_SERVER_KEYS) {
                String value = params.get(key);
                if (value != null && !value.isBlank()) {
                    profile.putIfAbsent(key, value);
                }
            }
        }
        return profile;
    }

    /** 把整档服务器参数并进一条线路，线路自己没报的才补 */
    private static void mergeServerParams(Map<String, Object> row, Map<String, String> profileParams) {
        if (profileParams.isEmpty()) {
            return;
        }
        Object raw = row.get("params");
        if (!(raw instanceof Map<?, ?>)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> params = (Map<String, String>) raw;
        for (Map.Entry<String, String> entry : profileParams.entrySet()) {
            params.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    /** 给 SIP 线路补业务语义：是否注册、状态文案、号码、服务器 */
    private static void applySipSemantics(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Map<String, String> params = paramsOf(row);
            row.put("registered", isSipRegistered(params));
            row.put("statusText", sipStatusText(params));
            row.put("number", sipNumber(params));
            row.put("server", sipServer(params));
            row.put("enabled", boolOf(params.get("Enable"), true));
            // 第几路：页面开编辑弹窗、按路下工单都要它，别让页面再拿路径做正则
            row.put("line", lineNoOf(String.valueOf(row.getOrDefault("path", ""))));
            // 编辑表单要的字段一次给全（服务器参数已在上面按 VoiceProfile 补过）；端口没配按 5060 显示，页面不再自己兜默认值
            row.put("authUser", firstNonBlank(params, "AuthUserName", "UserName"));
            row.put("password", firstNonBlank(params, "AuthPassword"));
            row.put("registrar", firstNonBlank(params, "RegistrarServer"));
            row.put("registrarPort", portOrDefault(firstNonBlank(params, "RegistrarServerPort")));
            row.put("proxy", firstNonBlank(params, "ProxyServer"));
            row.put("proxyPort", portOrDefault(firstNonBlank(params, "ProxyServerPort")));
        }
    }

    /** SIP 端口：设备没配就按 5060 显示（与设备侧默认一致） */
    private static String portOrDefault(String value) {
        return value == null || value.isBlank() ? DEFAULT_SIP_PORT : value.trim();
    }

    /** 行业默认 SIP 端口 */
    public static final String DEFAULT_SIP_PORT = "5060";

    /** 从线路对象路径里取路号：{@code …VoiceProfile.1.Line.2.SIP.} → 2，取不到为 0 */
    private static int lineNoOf(String path) {
        Matcher m = SIP_LINE_INDEX.matcher(path == null ? "" : path);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 线路实例末段里的序号：{@code SIP.3.} 或 {@code Line.3.SIP.} */
    private static final Pattern SIP_LINE_INDEX = Pattern.compile("(?:SIP|Line)\\.(\\d+)(?:\\.SIP)?\\.$");

    /** 注册状态：SIPRegisterStatus / Status 报 Registered / OK / Up / 成功 都算注册上 */
    private static boolean isSipRegistered(Map<String, String> params) {
        String s = firstNonBlank(params, "SIPRegisterStatus", "Status").toLowerCase(Locale.ROOT);
        return "registered".equals(s) || "ok".equals(s) || "up".equals(s) || "成功".equals(s);
    }

    /** 注册状态文案。有号码但没注册状态时是「未注册」，连号码都没有才是「未配置」—— */
    private static String sipStatusText(Map<String, String> params) {
        String raw = firstNonBlank(params, "SIPRegisterStatus", "Status");
        if (raw.isBlank()) {
            return "-".equals(sipNumber(params)) ? "未配置" : "未注册";
        }
        return isSipRegistered(params) ? "已注册" : "未注册";
    }

    /** 线路号码：优先认证名 / 用户名 / 号码，都没有就从 URI 里截 @ 前面的部分 */
    private static String sipNumber(Map<String, String> params) {
        String name = firstNonBlank(params, "AuthUserName", "UserName", "DirectoryNumber", "PhoneNumber");
        if (!name.isBlank()) {
            return name;
        }
        String uri = firstNonBlank(params, "URI");
        if (uri.isBlank()) {
            return "-";
        }
        Matcher m = SIP_URI_NUMBER.matcher(uri);
        return m.find() ? m.group(1) : uri;
    }

    /** 注册/代理服务器：域名 + 端口，如「sip.example.com:5060」 */
    private static String sipServer(Map<String, String> params) {
        String host = firstNonBlank(params, "RegistrarServer", "ProxyServer");
        if (host.isBlank()) {
            return "-";
        }
        String port = firstNonBlank(params, "RegistrarServerPort", "ProxyServerPort");
        return port.isBlank() ? host : host + ":" + port;
    }

    /** 设备区 key，与 SECTIONS 里的定义对应 */
    private static final String DEVICE_KEY = "device";

    /** 设备级语义：运行时长格式化 */
    @SuppressWarnings("unchecked")
    private void enrichDevice(Map<String, Object> data) {
        Object devObj = data.get(DEVICE_KEY);
        if (!(devObj instanceof List<?> list)) {
            return;
        }
        String uptime = "";
        String oui = "";
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, String> params = paramsOf((Map<String, Object>) raw);
            if (uptime.isEmpty()) {
                uptime = firstNonBlank(params, "UpTime", "Uptime");
            }
            if (oui.isEmpty()) {
                oui = firstNonBlank(params, "ManufacturerOUI", "Manufacturer");
            }
        }
        data.put("uptimeText", uptimeText(uptime));
        // 排序要的是原始秒数，格式化后的文案没法比大小
        data.put("uptimeSeconds", uptime);
        data.put("title", deviceTitle(String.valueOf(data.getOrDefault("sn", "")),
                oui.isEmpty() ? ledgerOui(String.valueOf(data.getOrDefault("sn", ""))) : oui));
    }

    /** 台账里的 OUI：设备 Inform 上报时就存下了 */
    private String ledgerOui(String sn) {
        if (sn.isBlank()) {
            return "";
        }
        CpeDevice device = deviceMapper.selectOne(
                new LambdaQueryWrapper<CpeDevice>().eq(CpeDevice::getSn, sn).last("LIMIT 1"));
        return device == null || device.getOui() == null ? "" : device.getOui().trim();
    }

    /** 设备标题：厂商 OUI 前 6 位 + 序列号，如 {@code A0B1C2-SN123} */
    private static String deviceTitle(String sn, String oui) {
        if (oui.isBlank()) {
            return sn;
        }
        return oui.substring(0, Math.min(6, oui.length())) + "-" + sn;
    }

    /** 光功率参数名里含 power；收/发方向分别按 rx/receive 与 tx/transmit 认 */
    private static final Pattern POWER_KEY = Pattern.compile("(?i)power");
    private static final Pattern RX_KEY = Pattern.compile("(?i)rx|receive");
    private static final Pattern TX_KEY = Pattern.compile("(?i)tx|transmit");
    private static final Pattern EPON_KEY = Pattern.compile("(?i)epon");
    private static final Pattern GPON_KEY = Pattern.compile("(?i)gpon");
    private static final Pattern DBM_SUFFIX = Pattern.compile("(?i)dbm");

    /** PON / 光模块：把光功率统一换算成 dBm，并判出上联类型 */
    @SuppressWarnings("unchecked")
    private void enrichPon(Map<String, Object> data) {
        Object ponObj = data.get("pon");
        if (!(ponObj instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, String> pon = (Map<String, String>) ponObj;
        String rx = null;
        String tx = null;
        boolean hasPower = false;
        for (Map.Entry<String, String> e : pon.entrySet()) {
            if (!POWER_KEY.matcher(e.getKey()).find()) {
                continue;
            }
            String dbm = powerDbm(e.getValue());
            if (dbm == null) {
                continue;
            }
            hasPower = true;
            if (rx == null && RX_KEY.matcher(e.getKey()).find()) {
                rx = dbm;
            } else if (tx == null && TX_KEY.matcher(e.getKey()).find()) {
                tx = dbm;
            }
        }
        if (rx == null && tx != null) {
            rx = tx;
        }
        if (tx == null && rx != null) {
            tx = rx;
        }
        data.put("opticState", hasPower ? "ok" : "none");
        data.put("rxPower", rx);
        data.put("txPower", tx);
        data.put("opticText", rx == null || tx == null ? "" : rx + " / " + tx + " dBm");
        data.put("ponType", ponTypeOf(deviceModelOf(data), String.join(" ", pon.keySet())));
    }

    /** 上联类型：先看型号里写没写（GPON_4+1 / EPON...），再看 PON 参数名 */
    private static String ponTypeOf(String model, String ponKeys) {
        String text = model + " " + ponKeys;
        if (EPON_KEY.matcher(text).find()) {
            return "EPON";
        }
        if (GPON_KEY.matcher(text).find()) {
            return "GPON";
        }
        if (!ponKeys.isBlank()) {
            return "PON";
        }
        return "GE";
    }

    /** PON 判定要用的型号文本 */
    @SuppressWarnings("unchecked")
    private static String deviceModelOf(Map<String, Object> data) {
        String ledger = (str(data.get("deviceType")) + " " + str(data.get("productClass"))).trim();
        if (!ledger.isBlank()) {
            return ledger;
        }
        Object devObj = data.get(DEVICE_KEY);
        if (!(devObj instanceof List<?> list)) {
            return "";
        }
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, String> params = paramsOf((Map<String, Object>) raw);
            String model = firstNonBlank(params, "ProductClass", "ModelName");
            if (!model.isBlank()) {
                return model;
            }
        }
        return "";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 光功率换算成 dBm，保留一位小数 */
    private static String powerDbm(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String s = value.trim();
        try {
            if (DBM_SUFFIX.matcher(s).find()) {
                double d = Double.parseDouble(DBM_SUFFIX.matcher(s).replaceAll("").trim());
                return String.format(Locale.ROOT, "%.1f", d);
            }
            double n = Double.parseDouble(s);
            double mw = Math.abs(n) > 100 ? n * 0.0001 : n;
            if (mw <= 0) {
                return null;
            }
            return String.format(Locale.ROOT, "%.1f", 10 * Math.log10(mw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 厂商私有叶子前缀，形如 X_CMCC_、X_CT-COM_（中间可带连字符） */
    private static final Pattern VENDOR_LEAF_PREFIX = Pattern.compile("(?i)^x_[a-z0-9-]+_");

    /** 业务卡片用的参数名：剥掉厂商私有前缀，归一到标准名 */
    static String normalizeLeaf(String leaf) {
        if (leaf == null) {
            return "";
        }
        java.util.regex.Matcher m = VENDOR_LEAF_PREFIX.matcher(leaf);
        return m.find() ? leaf.substring(m.end()) : leaf;
    }

    private Map<String, Object> baseInfo(String sn, String root) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sn", sn);
        data.put("rootNode", root);
        // 对外说明按哪种数据模型解读的这套数据，排查「某些型号业务查不到」时先看这里
        data.put("dataModel", DataModel.ofRoot(root).getLabel());
        try {
            data.put("lastCollectTime", parameterMapper.lastCollectTime(sn));
        } catch (Exception e) {
            log.warn("查询业务参数采集时间失败: sn={}, err={}", sn, e.getMessage());
            data.put("lastCollectTime", null);
        }
        // 台账里的注册时间、最近联系时间，和从能力树读出的运行时长一起组成前端信息卡
        try {
            CpeDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                    .eq(CpeDevice::getSn, sn)
                    .last("LIMIT 1"));
            if (device != null) {
                data.put("registerTime", device.getCreatedAt());
                data.put("lastContactTime", device.getLastContactTime());
                // 台账登记的型号一起带上：PON 类型判定要读它，能力树没采到 ProductClass 时台账是唯一线索
                data.put("deviceType", device.getDeviceType());
                data.put("productClass", device.getProductClass());
            }
        } catch (Exception e) {
            log.warn("查询设备台账时间失败: sn={}, err={}", sn, e.getMessage());
        }
        return data;
    }

    /** PON / 光模块参数：能力快照里路径含 PON 的参数 */
    private Map<String, String> ponParams(String sn) {
        try {
            List<CpeParameter> rows = parameterMapper.selectList(new LambdaQueryWrapper<CpeParameter>()
                    .eq(CpeParameter::getSn, sn)
                    .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                    .like(CpeParameter::getParamPath, "PON")
                    .orderByAsc(CpeParameter::getParamPath));
            Map<String, String> result = new LinkedHashMap<>();
            for (CpeParameter row : rows) {
                result.put(row.getParamPath(), row.getParamValue());
            }
            return result;
        } catch (Exception e) {
            log.warn("查询PON参数失败: sn={}, err={}", sn, e.getMessage());
            return Map.of();
        }
    }

    /** 只取业务相关前缀下的参数行，避免把整棵能力树（上万行）拉进内存 */
    private List<CpeParameter> selectBusinessParams(String sn) {
        List<String> prefixes = new ArrayList<>();
        for (Section section : SECTIONS) {
            prefixes.addAll(DataModel.businessPrefixes(section.key()));
        }
        try {
            return parameterMapper.selectList(new LambdaQueryWrapper<CpeParameter>()
                    .eq(CpeParameter::getSn, sn)
                    .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                    .and(w -> {
                        for (int i = 0; i < prefixes.size(); i++) {
                            if (i == 0) {
                                w.likeRight(CpeParameter::getParamPath, prefixes.get(i));
                            } else {
                                w.or().likeRight(CpeParameter::getParamPath, prefixes.get(i));
                            }
                        }
                    })
                    .orderByAsc(CpeParameter::getParamPath));
        } catch (Exception e) {
            log.warn("查询业务参数失败: sn={}, err={}", sn, e.getMessage());
            return List.of();
        }
    }

    private String resolveRoot(String sn) {
        try {
            CpeDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                    .eq(CpeDevice::getSn, sn)
                    .last("LIMIT 1"));
            if (device != null && device.getRootNode() != null && !device.getRootNode().isBlank()) {
                return device.getRootNode();
            }
        } catch (Exception e) {
            log.warn("查询设备根节点失败，回退默认根: sn={}, err={}", sn, e.getMessage());
        }
        return DEFAULT_ROOT;
    }

    private static String parentOf(CpeParameter row) {
        if (row.getParentPath() != null) {
            return row.getParentPath();
        }
        return DeviceCapabilityService.parentOf(row.getParamPath());
    }
}

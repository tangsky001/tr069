package com.tr069.acs.worklist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.acs.service.CwmpRpcService;
import com.tr069.acs.acs.service.DeviceCapabilityService;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeModel;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import lombok.Data;
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
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.tr069.acs.common.log.AcsCoreLog;

/**
 * 机型适配：以「运营商 + 型号」为维度，把一台样机的能力固化成库里的型号档案。
 *
 * <p>流程对齐《TR069-ACS-Inform 后下发数据拼接指南》的探测思想：</p>
 * <ol>
 *   <li><b>查库</b>：设备列表按 sn 批量查 {@code cpe_model.config_path}——
 *       已有该型号的能力树 XML 文件（models/{运营商}/{型号}.xml）即视为已适配，直接调库；</li>
 *   <li><b>适配</b>：未适配的型号，人工申报 运营商 / 网口数量 / 网口速率 / SIP 数量 / WIFI 规格，
 *       点「测试」时对样机强制做一次 GetParameterNames+Values 采集（RPC 经捎带通道真实下发到光猫），
 *       再从采集到的参数树里解析出设备<b>实际</b>支持的能力，与申报值逐项比对；</li>
 *   <li><b>入库</b>：全部支持才允许保存——导出型号能力树 XML、回写 {@code cpe_model.config_path}
 *       并落申报的能力字段。下次同运营商同型号（同固件）设备上线即命中档案，无需再适配。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelAdaptationService {

    private final CpeDeviceMapper deviceMapper;
    private final CpeParameterMapper parameterMapper;
    private final DeviceCapabilityService capabilityService;
    private final CwmpRpcService cwmpRpcService;
    private final ModelMappingService modelMappingService;
    private final ModelTreeStore modelTreeStore;
    private final AcsCoreLog coreLog;
    private final ModelAdaptationProfileStore profileStore;

    /** 测试时等待光猫返回参数树的最长时间，秒。 */
    @Value("${acs.adapt.test-timeout-seconds:120}")
    private int testTimeoutSeconds;

    /** 单次试探下发等待设备响应的时间，秒。 */
    @Value("${acs.adapt.probe-timeout-seconds:30}")
    private int probeTimeoutSeconds;

    // ==================== 适配状态 ====================

    /**
     * 单台设备的适配状态：库里有没有该型号的能力树 XML 文件。
     *
     * <p>判定标准就一条——{@code cpe_model.config_path} 非空且文件真实存在，
     * 即「先在库里查该光猫 xml 配置文件路径，有 = 已适配」。</p>
     */
    public Map<String, Object> status(String sn) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sn", sn);
        CpeDevice device = deviceBySn(sn);
        if (device == null) {
            data.put("adapted", false);
            data.put("hint", "设备不存在");
            return data;
        }
        // 没登记过的型号顺手登记成待适配，与 Inform 自动登记保持一致
        CpeModel model = modelMappingService.resolveOrRegister(device.getIsp(),
                device.getManufacturer(), device.getProductClass(),
                device.getSoftwareVersion(), device.getHardwareVersion(), sn);
        data.put("isp", model.getIsp());
        data.put("productClass", model.getProductClass());
        data.put("softwareVersion", model.getSoftwareVersion());
        data.put("modelId", model.getId());
        data.put("modelStatus", model.getStatus());
        data.put("capLanPorts", model.getCapLanPorts());
        data.put("capLanSpeed", model.getCapLanSpeed());
        data.put("capSipCount", model.getCapSipCount());
        data.put("capWifiSpec", model.getCapWifiSpec());
        // 已适配看同名 YML 里的 adapted，XML 只是能力档案，存在不算适配过
        boolean adapted = profileStore.isAdapted(model);
        String adaptedIsp = null;
        if (!adapted) {
            // 设备上报的 isp 可能与适配申报的 isp 不同（上报为空/standard 而适配给了 CMCC），
            // 同型号只要在任意运营商下有 adapted: true 的档案就视为已适配——
            // 不然适配完列表永远还挂着红色的「适配」入口
            adaptedIsp = profileStore.findAdaptedIsp(model.getModelKey());
            if (adaptedIsp != null) {
                adapted = true;
                data.put("adaptedIsp", adaptedIsp);
                data.put("profile", profileStore.readByIsp(model.getModelKey(), adaptedIsp));
            }
        }
        if (!adapted) {
            // 兜底：适配文件没生成或没找到时，以数据库里的状态为准。
            // 只要库里有 config_path 且状态 READY，就视为已适配——配置已经落库，
            // 用户明确说"适配后配置已经在数据库中保存了，可以直接调库"
            if (model.getConfigPath() != null && !model.getConfigPath().isBlank()
                    && CpeModel.STATUS_READY.equals(model.getStatus())) {
                adapted = true;
                data.put("adaptedFromDb", true);
                data.put("adaptedIsp", adaptedIsp != null ? adaptedIsp : model.getIsp());
                data.putIfAbsent("profile", profileStore.read(model));
            }
        }
        data.put("adapted", adapted);
        data.put("configPath", model.getConfigPath());
        data.put("adaptPath", model.getAdaptPath());
        data.putIfAbsent("profile", profileStore.read(model));
        return data;
    }

    /**
     * 整页设备批量适配状态，返回 sn -> 状态，供设备列表一次请求渲染「已适配/适配」标识。
     */
    public Map<String, Map<String, Object>> statusBatch(List<String> sns) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (sns != null) {
            for (String sn : sns) {
                if (sn != null && !sn.isBlank()) {
                    result.put(sn, status(sn));
                }
            }
        }
        return result;
    }

    // ==================== 能力测试 ====================

    /**
     * 向光猫下发采集 RPC 并比对申报能力。
     *
     * <p>「测试」会真实触发一次强制能力采集：GetParameterNames + GetParameterValues
     * 经捎带通道下发给光猫（设备需在线，离线设备的指令留在队列里等下次上线，
     * 测试会以超时结束并提示）。采完后从参数树解析实际能力，与申报逐项比对。</p>
     */
    public Map<String, Object> test(String sn, AdaptRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requested", request.asMap());
        CollectResult collect = collectTree(sn);
        data.put("collectStatus", collect.status);
        if (collect.error != null) {
            data.put("collectError", collect.error);
        }
        List<CpeParameter> params = parameterMapper.selectList(
                new LambdaQueryWrapper<CpeParameter>()
                        .eq(CpeParameter::getSn, sn)
                        .orderByAsc(CpeParameter::getParamPath));
        Detection detected = detect(params);
        data.put("detected", detected.asMap());
        List<String> reasons = new ArrayList<>(compare(detected, request));
        // 采集没成功的话，解析结果不可信，也不再做试探下发
        boolean collected = "success".equals(collect.status);
        if (!collected && reasons.isEmpty()) {
            reasons.add("能力采集未成功(" + collect.status + ")"
                    + (collect.error == null ? "" : "：" + collect.error));
        }
        // 真下发一次：把参数当前值原样写回，验证光猫确实能执行这些能力
        List<Probe> probes = collected ? probeCapabilities(sn, params, request) : List.of();
        data.put("probes", probes.stream().map(Probe::asMap).toList());
        // 试探失败只作提示：Writable 未知时候选面本来就宽，写回同值被拒常常只是值域
        // 问题（MaxBitRate 上报「1000M」，标准是 bps 数字或 Auto），不代表能力缺失，
        // 拿它拦人会让这类固件永远适不上
        List<String> notes = new ArrayList<>(notes(detected, request));
        for (Probe p : probes) {
            if (p.failed) {
                notes.add("参数下发未通过：" + p.capability + " " + p.path + " -> " + p.status);
            }
        }
        // 没挑到候选的组要说清原因。表格里那个「-」容易被读成「这台没有这块能力」，
        // 其实多数时候是这条路径压根没采到，或参数当前值是空的
        for (ProbeGroup group : PROBE_GROUPS) {
            if (!group.required(request)) {
                continue;
            }
            String label = group.label;
            if (probes.stream().noneMatch(p -> label.equals(p.getCapability()))) {
                notes.add(label + "：这次没做试探——设备上没采到可调参数"
                        + "（路径不存在，或值为空），能力判定不受影响");
            }
        }
        data.put("supported", reasons.isEmpty());
        data.put("reasons", reasons);
        data.put("notes", notes);
        return data;
    }

    /**
     * 测试通过后保存：导出型号能力树 XML 并落库。
     *
     * @throws IllegalStateException 申报能力与设备实测不符，拒绝入库
     */
    public Map<String, Object> save(String sn, AdaptRequest request) {
        CpeDevice device = deviceBySn(sn);
        if (device == null) {
            throw new IllegalStateException("设备不存在: " + sn);
        }
        Detection detected = detect(parameterMapper.selectList(
                new LambdaQueryWrapper<CpeParameter>()
                        .eq(CpeParameter::getSn, sn)
                        .orderByAsc(CpeParameter::getParamPath)));
        List<String> reasons = compare(detected, request);
        if (!reasons.isEmpty()) {
            String reason = String.join("；", reasons);
            coreLog.adaptation(request.getIsp() + "/" + device.getProductClass(),
                    "适配入库", false, "能力不符: " + reason);
            throw new IllegalStateException("设备能力与申报不符，已拒绝入库：" + reason);
        }
        List<String> notes = notes(detected, request);
        if (!notes.isEmpty()) {
            log.info("机型适配提示（不阻断保存）: sn={}, notes={}", sn, String.join("；", notes));
        }
        // 申报的运营商与设备上报不一致时，给目标运营商另立一份档案
        String isp = request.getIsp() != null && !request.getIsp().isBlank()
                ? request.getIsp().trim() : device.getIsp();
        CpeModel model = modelMappingService.resolveOrRegister(isp,
                device.getManufacturer(), device.getProductClass(),
                device.getSoftwareVersion(), device.getHardwareVersion(), sn);
        // 型号文件按运营商分目录：models/CM|CT|CU|standard/standard/{型号}.xml
        CpeModel pathPatch = new CpeModel();
        pathPatch.setConfigPath(ModelTreeStore.MODEL_DIR + "/"
                + ModelMappingService.defaultConfigPath(isp, model.getModelKey()));
        pathPatch.setAdaptPath(ModelTreeStore.MODEL_DIR + "/"
                + ModelMappingService.defaultAdaptPath(isp, model.getModelKey()));
        modelMappingService.update(model.getId(), pathPatch);
        // 导出能力树 XML 并回写 config_path（能力档案，不代表已适配）
        ModelTreeStore.ExportResult export = modelTreeStore.export(sn, true, isp);
        CpeModel patch = new CpeModel();
        patch.setCapLanPorts(request.getLanPorts());
        patch.setCapLanSpeed(request.getLanSpeed());
        patch.setCapSipCount(request.getSipCount());
        // 实测有 5.8G 但申报只勾了 2.4G 时按实测补齐：申报漏掉 5G 会让模板库里根本
        // 没有 wlan_b 那张表，后面想改一次 5G 的 SSID 一律报「5G 模板里没有实例」
        String wifiSpec = request.getWifiSpec();
        if (detected.wifiBands.contains("5.8G") && wifiSpec != null
                && wifiSpec.contains("2.4") && !wifiSpec.contains("5")) {
            wifiSpec = "2.4G+5.8G";
            log.info("申报 WIFI 规格只到 2.4G，但实测含 5.8G，按实测补齐: sn={}", sn);
        }
        patch.setCapWifiSpec(wifiSpec);
        // 适配通过后状态置为 READY：后续状态判断可以直接以库为准，不必依赖文件系统
        patch.setStatus(CpeModel.STATUS_READY);
        if (model.getSampleSn() == null || model.getSampleSn().isBlank()) {
            patch.setSampleSn(sn);
        }
        CpeModel updated = modelMappingService.update(model.getId(), patch);
        // 申报值必须同步进 SQLite 模板库：上面的 export 发生在申报之前，
        // 那一刻库里还没有能力字段，生成不出模板；这里回写一次并按申报重新生成
        modelTreeStore.applyCapability(isp, updated.getModelKey(), updated);
        // 测试通过才写适配档：adapted: true，同型号设备上线读它判定已适配
        String adaptPath = profileStore.writeAdapted(updated, isp, sn, request);
        updated = modelMappingService.update(model.getId(),
                solePath(adaptPath));
        // 适配时选定的运营商就是这台设备的运营商：光猫上报通常不带运营商（或只带
        // 通用值），人工申报的归属才是生效值，写回台账后列表、详情、能力树归档
        // 都按它走，不必再从适配档反推
        if (!isp.equals(device.getIsp())) {
            CpeDevice devicePatch = new CpeDevice();
            devicePatch.setId(device.getId());
            devicePatch.setIsp(isp);
            deviceMapper.updateById(devicePatch);
            log.info("按适配申报登记设备运营商: sn={}, {} -> {}", sn, device.getIsp(), isp);
        }
        log.info("机型适配入库: sn={}, isp={}, modelKey={}, configPath={}, adaptPath={}",
                sn, isp, model.getModelKey(), export.getConfigPath(), adaptPath);
        coreLog.adaptation(isp + "/" + model.getModelKey(), "适配入库", true,
                "sn=" + sn + ", adaptPath=" + adaptPath);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", updated);
        data.put("configPath", export.getConfigPath());
        data.put("adaptPath", adaptPath);
        data.put("detected", detected.asMap());
        return data;
    }

    private static CpeModel solePath(String adaptPath) {
        CpeModel patch = new CpeModel();
        patch.setAdaptPath(adaptPath);
        return patch;
    }

    // ==================== 采集等待 ====================

    /** 强制采集并等待结束，返回采集状态与首条错误。 */
    private CollectResult collectTree(String sn) {
        CollectResult result = new CollectResult();
        DeviceCapabilityService.CollectStatus status;
        try {
            status = capabilityService.collect(sn, true);
        } catch (Exception e) {
            result.status = "fail";
            result.error = e.getMessage();
            return result;
        }
        long deadline = System.currentTimeMillis() + testTimeoutSeconds * 1000L;
        while (status != null && "running".equals(status.getStatus())
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            status = capabilityService.status(sn);
        }
        if (status == null) {
            result.status = "unknown";
            return result;
        }
        result.status = status.getStatus();
        List<String> errors = status.getErrors();
        if (errors != null && !errors.isEmpty()) {
            result.error = errors.get(errors.size() - 1);
        }
        return result;
    }

    private static class CollectResult {
        String status = "unknown";
        String error;
    }

    // ==================== 能力解析 ====================

    /** 网口实例：优先 LANDevice 下的配置对象，退化到 TR-181 的 Ethernet.Interface。 */
    private static final Pattern LAN_IF = Pattern.compile(
            "(?:LANDevice\\.\\d+\\.)?(?:LANEthernetInterfaceConfig|EthernetInterface)\\.(\\d+)\\.");
    private static final Pattern ETH_IF_TR181 = Pattern.compile("(?:^|\\.)Ethernet\\.Interface\\.(\\d+)\\.");
    private static final Pattern WLAN_IF = Pattern.compile(
            "(?:LANDevice\\.\\d+\\.)?WLANConfiguration\\.(\\d+)\\.");
    private static final Pattern WIFI_RADIO = Pattern.compile("(?:^|\\.)WiFi\\.Radio\\.(\\d+)\\.");
    private static final Pattern VOICE_PROFILE = Pattern.compile(
            "VoiceService\\.\\d+\\.VoiceProfile\\.(\\d+)\\.");
    /**
     * 语音物理口，如 VoiceService.1.PhyInterface.2。
     *
     * <p>不少光猫只建一个 VoiceService、一个 VoiceProfile，4 路电话全靠
     * PhyInterface.1..4 承载；数「SIP 数量」时只数 VoiceProfile 会把这种
     * 4 口设备误判成 1 路，适配比对永远过不了。</p>
     */
    private static final Pattern VOICE_PHY = Pattern.compile(
            "VoiceService\\.\\d+\\.PhyInterface\\.(\\d+)\\.");
    /** 厂商私有 SIP 对象，如 X_CMCC_SipObj.1、X_ZTE-COM_SIP.2。 */
    private static final Pattern VENDOR_SIP = Pattern.compile(
            "(?i)X_[a-z0-9-]+_SIP[A-Za-z]*\\.(\\d+)\\.");
    private static final Pattern SPEED_LEAF = Pattern.compile(
            "(?i)(maxbitrate|maxspeed|linkrate|portspeed|linkspeed)$");
    private static final Pattern BAND_LEAF = Pattern.compile(
            "(?i)(frequencyband|operatingfrequencyband|frequency)");
    /**
     * 信道列表叶子。2.4G 只用 1~14，5G 从 36 起，据此反推频段——
     * 不少定制固件不实现 {@code OperatingFrequencyBand}，但这两个参数一定有值。
     */
    private static final Pattern CHANNEL_LEAF = Pattern.compile(
            "(?i)(possiblechannels|supportedchannels|channelsinuse|channel)$");
    private static final Pattern CHANNEL_NUMBER = Pattern.compile("\\d+");
    /** 2.4G 的最大信道号，超过它的就是 5G。 */
    private static final int MAX_2G_CHANNEL = 14;

    /**
     * 从参数树解析设备实际能力：网口数量、网口速率(Mbps)、SIP 数量、WIFI 频段。
     *
     * <p>解析规则同时兼容 TR-098（InternetGatewayDevice.LANDevice.*）与
     * TR-181（Device.Ethernet.Interface / Device.WiFi.Radio）两种数据模型，
     * 并兼顾 X_CMCC_ 等厂商私有节点。</p>
     */
    Detection detect(List<CpeParameter> params) {
        Set<String> lanIfs = new TreeSet<>();
        Set<String> ethIfs = new TreeSet<>();
        Set<String> wlans = new TreeSet<>();
        Set<String> radios = new TreeSet<>();
        Set<String> voiceProfiles = new TreeSet<>();
        Set<String> voicePhys = new TreeSet<>();
        Set<String> vendorSips = new TreeSet<>();
        double maxSpeedMbps = -1;
        Set<String> bands = new LinkedHashSet<>();
        // 自己报出过频段证据的射频：用来判断「还有几个射频没标频段」
        Set<String> bandedWlan = new LinkedHashSet<>();
        // 设备自报的条目数。有些固件不建 LANEthernetInterfaceConfig（只给个
        // LANHostConfigManagement），语音只建一路 Line，靠数实例会把 4 口 8 路
        // 误判成 0 口 1 路，申报怎么填都比不过
        int lanEntries = 0;
        int wlanEntries = 0;
        int profileEntries = 0;
        int lineEntries = 0;
        int phyEntries = 0;

        for (CpeParameter row : params) {
            String path = row.getParamPath() == null ? "" : row.getParamPath();
            Matcher m = LAN_IF.matcher(path);
            if (m.find()) {
                lanIfs.add(m.group(1));
            }
            m = ETH_IF_TR181.matcher(path);
            if (m.find()) {
                ethIfs.add(m.group(1));
            }
            m = WLAN_IF.matcher(path);
            if (m.find()) {
                wlans.add(m.group(1));
            }
            m = WIFI_RADIO.matcher(path);
            if (m.find()) {
                radios.add(m.group(1));
            }
            m = VOICE_PROFILE.matcher(path);
            if (m.find()) {
                voiceProfiles.add(m.group(1));
            }
            m = VOICE_PHY.matcher(path);
            if (m.find()) {
                voicePhys.add(m.group(1));
            }
            m = VENDOR_SIP.matcher(path);
            if (m.find()) {
                vendorSips.add(m.group(1));
            }
            String leaf = leafOf(path);
            if ("LANEthernetInterfaceNumberOfEntries".equals(leaf)) {
                lanEntries = Math.max(lanEntries, intValue(row));
            } else if ("WLANConfigurationNumberOfEntries".equals(leaf)) {
                wlanEntries = Math.max(wlanEntries, intValue(row));
            } else if ("VoiceProfileNumberOfEntries".equals(leaf)) {
                profileEntries = Math.max(profileEntries, intValue(row));
            } else if ("LineNumberOfEntries".equals(leaf)) {
                lineEntries = Math.max(lineEntries, intValue(row));
            } else if ("PhyInterfaceNumberOfEntries".equals(leaf)) {
                phyEntries = Math.max(phyEntries, intValue(row));
            }
            double speed = speedMbps(row, leaf);
            if (speed > maxSpeedMbps) {
                maxSpeedMbps = speed;
            }
            if (collectBand(row, leaf, bands)) {
                Matcher mi = WLAN_IF.matcher(path);
                if (mi.find()) {
                    bandedWlan.add("wlan:" + mi.group(1));
                } else if ((mi = WIFI_RADIO.matcher(path)).find()) {
                    bandedWlan.add("radio:" + mi.group(1));
                }
            }
        }
        int radioCount = Math.max(Math.max(wlans.size(), radios.size()), wlanEntries);
        // 只标 5G、不标 2.4G 是行业惯例：CMCC-xxxx-5G 配一个什么都不标的 CMCC-xxxx。
        // 所以认出 5.8G 却还剩射频没标出频段时，没标的那个按 2.4G 认定——
        // 反过来不成立，只有一个射频且它标了 5G 就是纯 5G 设备
        if (bands.contains("5.8G") && !bands.contains("2.4G") && radioCount > bandedWlan.size()) {
            bands.add("2.4G");
        }
        // 频段一个都识别不出时按射频数推断：单射频基本都是 2.4G，双射频才是双频。
        // 老固件不报 OperatingFrequencyBand，SSID 也不带频段（ChinaNet-xxxx 这种），
        // 不兜底的话「实测 WIFI 无」，申报 2.4G 永远过不了
        if (bands.isEmpty() && radioCount >= 1) {
            bands.add("2.4G");
            if (radioCount >= 2) {
                bands.add("5.8G");
            }
        }
        Detection d = new Detection();
        // 语音容量有没有证据：条目数可读，或者本身就能数出多路实例。
        // 一个 VoiceProfile、一条 Line、条目数全 9005 的设备，攒出的「1 路」不算证据
        d.sipCapacityKnown = profileEntries > 0 || lineEntries > 0 || phyEntries > 0
                || voiceProfiles.size() > 1 || voicePhys.size() > 1 || vendorSips.size() > 1;
        d.lanPortCount = Math.max(Math.max(lanIfs.size(), ethIfs.size()), lanEntries);
        d.lanSpeedMbps = maxSpeedMbps > 0 ? maxSpeedMbps : null;
        // SIP 数量按「能打几路电话」口径：VoiceProfile / PhyInterface / 厂商私有对象 /
        // Line 条目数取最大——只有一路 Line 实例但 LineNumberOfEntries=8 的机型很常见
        d.sipCount = Math.max(
                Math.max(Math.max(voiceProfiles.size(), voicePhys.size()), vendorSips.size()),
                Math.max(Math.max(profileEntries, lineEntries), phyEntries));
        d.radioCount = radioCount;
        d.wifiBands = new ArrayList<>(bands);
        return d;
    }

    /** 从叶子参数提取网口速率，统一换算成 Mbps；非速率叶子返回 -1。 */
    private double speedMbps(CpeParameter row, String leaf) {
        if (leaf.isEmpty() || !SPEED_LEAF.matcher(leaf).find()) {
            return -1;
        }
        double v;
        try {
            v = Double.parseDouble(row.getParamValue() == null ? "" : row.getParamValue().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        // MaxBitRate 常见单位为 Mbps，个别厂商报 kbps（>=10000 视为 kbps）
        return v >= 10000 ? v / 1000.0 : v;
    }

    /** 从频段字段、信道列表或 802.11 制式推断 WIFI 频段，返回这条参数是否给出了频段证据。 */
    private boolean collectBand(CpeParameter row, String leaf, Set<String> bands) {
        String value = row.getParamValue() == null ? "" : row.getParamValue().trim();
        if (value.isEmpty() || leaf.isEmpty()) {
            return false;
        }
        int before = bands.size();
        String lower = value.toLowerCase(Locale.ROOT);
        if (BAND_LEAF.matcher(leaf).find()) {
            // SupportedFrequencyBands 是逗号分隔的多值（2.4GHz,5GHz），两个频段都要认
            collectBandsFromText(lower, bands);
        } else if (CHANNEL_LEAF.matcher(leaf).find()) {
            // 信道列表是最可靠的判据：1-13 只可能是 2.4G，36 起只可能是 5G
            int maxChannel = maxChannelOf(value);
            if (maxChannel > MAX_2G_CHANNEL) {
                bands.add("5.8G");
            } else if (maxChannel >= 1) {
                bands.add("2.4G");
            }
        } else if ("standard".equalsIgnoreCase(leaf)) {
            // b/g 只跑 2.4G，ac/ax 只跑 5G。值为 "n" 时不表态：n 两个频段都能跑
            if (lower.contains("ac") || lower.contains("ax") || lower.contains("be")) {
                bands.add("5.8G");
            } else if (lower.contains("b") || lower.contains("g")) {
                bands.add("2.4G");
            }
        } else if ("SSID".equalsIgnoreCase(leaf)) {
            // SSID 只有在带频段字样时才作数：CMCC-5G 认得出，CMCC-0002 认不出
            collectBandsFromText(lower, bands);
        }
        return bands.size() > before;
    }

    /** 从文本里找频段字样。两个频段分开判断，多值字段才能都认出来。 */
    private static void collectBandsFromText(String lower, Set<String> bands) {
        if (lower.contains("2.4") || lower.contains("2g") || lower.contains("24g")) {
            bands.add("2.4G");
        }
        if (lower.contains("5.8") || lower.contains("5g") || lower.contains("5ghz")) {
            bands.add("5.8G");
        }
    }

    /** 取信道列表里的最大信道号：{@code 1-13}、{@code 36 40 44}、{@code 36-64,149-165} 都能解析。 */
    private static int maxChannelOf(String value) {
        int max = 0;
        Matcher m = CHANNEL_NUMBER.matcher(value);
        while (m.find()) {
            try {
                max = Math.max(max, Integer.parseInt(m.group()));
            } catch (NumberFormatException ignored) {
                // 超长数字与信道无关，跳过
            }
        }
        return max;
    }

    /** NumberOfEntries 这类计数字段，读不出数字按 0 处理。 */
    private static int intValue(CpeParameter row) {
        try {
            return Integer.parseInt(row.getParamValue() == null ? "" : row.getParamValue().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String leafOf(String path) {
        int idx = path.lastIndexOf('.');
        if (idx < 0) {
            return path;
        }
        // 末尾是点的为对象路径，取点前一段做叶子名兜底
        return idx == path.length() - 1 ? "" : path.substring(idx + 1);
    }

    // ==================== 申报比对 ====================

    /** 逐项比对申报能力与实测能力，返回不满足的原因列表，空列表即全部支持。 */
    List<String> compare(Detection d, AdaptRequest req) {
        List<String> reasons = new ArrayList<>();
        int lanRequired = parseCount(req.getLanPorts());
        if (lanRequired > 0 && d.lanPortCount < lanRequired) {
            reasons.add("网口数量不足：申报 " + req.getLanPorts() + "，实测 " + d.lanPortCount);
        }
        double speedRequired = parseSpeed(req.getLanSpeed());
        if (speedRequired > 0) {
            if (d.lanSpeedMbps == null) {
                reasons.add("未采集到网口速率参数，无法确认申报的 " + req.getLanSpeed());
            } else if (d.lanSpeedMbps < speedRequired) {
                reasons.add(String.format("网口速率不足：申报 %s，实测最高 %.0fMbps",
                        req.getLanSpeed(), d.lanSpeedMbps));
            }
        }
        int sipRequired = parseCount(req.getSipCount());
        // 只有设备确实报了语音容量（条目数或可数的多路实例）才谈得上「不足」。
        // 有的固件一个容量参数都不给、只暴露 Line.1，攒出来的「1 路」并不是证据，
        // 按实物（8 个语音口）申报 8 路反倒会被它挡住
        if (sipRequired > 0 && d.sipCount < sipRequired && d.sipCapacityKnown) {
            reasons.add("SIP 数量不足：申报 " + req.getSipCount() + "，实测 " + d.sipCount);
        }
        String wifi = req.getWifiSpec();
        if (wifi != null && wifi.contains("2.4") && !d.wifiBands.contains("2.4G")) {
            reasons.add("未检测到 2.4G WIFI");
        }
        if (wifi != null && wifi.contains("5.8") && !d.wifiBands.contains("5.8G")) {
            reasons.add("未检测到 5.8G WIFI");
        }
        return reasons;
    }

    /**
     * 需要提醒、但不阻断保存的项。
     *
     * <p>「实测没数出来」和「设备没有」是两回事：有的固件压根不给语音容量参数，
     * 实测只剩 1 路；拿这个去否定申报（实物有 8 个语音口）就把设备挡在门外了。
     * 这种交给人工判断的走提示，确实矛盾的（申报数超过设备自报条目数）才走 {@link #compare}。</p>
     */
    List<String> notes(Detection d, AdaptRequest req) {
        List<String> notes = new ArrayList<>();
        int sipRequired = parseCount(req.getSipCount());
        if (sipRequired > 0 && !d.sipCapacityKnown) {
            notes.add("设备未上报语音容量（Line / PhyInterface 条目数均不可读），"
                    + "SIP " + req.getSipCount() + " 路按申报为准，请核对实物语音口数量");
        }
        return notes;
    }

    /** 申报数量：无=0，4-8=4（下限），其余取数字。 */
    static int parseCount(String option) {
        if (option == null || option.isBlank() || "无".equals(option.trim())) {
            return 0;
        }
        String v = option.trim();
        if (v.contains("-")) {
            v = v.substring(0, v.indexOf('-'));
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 申报速率：无/自动=0（不校验），100M=100，1000M=1000。 */
    static double parseSpeed(String option) {
        if (option == null) {
            return 0;
        }
        String v = option.trim().toLowerCase();
        if (v.isEmpty() || "无".equals(v) || "自动".equals(v)) {
            return 0;
        }
        try {
            return Double.parseDouble(v.replace("m", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== 试探下发 ====================

    /** 每组最多挑几个可写叶子做试探，减少下发轮次也降低打扰。 */
    private static final int PROBE_LIMIT = 2;

    /**
     * 试探下发：把采集到的当前值原样写成 SetParameterValues 下发给光猫。
     *
     * <p>写回的是<b>同一个值</b>，所以配置不会被改变，却能验证三件事：
     * 路径真实存在（否则 9005）、类型匹配（否则 9003/9006）、参数可写（只读会 9001）。
     * 这比只读采集更能证明「这台设备真的能按这些能力下发业务」。</p>
     *
     * <p>按能力<b>分组单独下发</b>：SetParameterValues 在多数 CPE 上是事务性的，
     * 混在一批里任何一个只读参数都会把整批拖成回滚，分组才能定位到具体能力。</p>
     */
    List<Probe> probeCapabilities(String sn, List<CpeParameter> params, AdaptRequest request) {
        List<Probe> probes = new ArrayList<>();
        for (ProbeGroup group : PROBE_GROUPS) {
            if (!group.required(request)) {
                continue;
            }
            List<CpeParameter> candidates = new ArrayList<>();
            for (CpeParameter row : params) {
                if (row.getParamPath() == null) {
                    continue;
                }
                // 0 = 设备明确说只读；null / -1（只能走 GetParameterValues 兜底读参数的
                // 固件，Writable 属性无从而知）都放行——真能不能写，由这次下发自己证明
                Integer writable = row.getWritable();
                if (writable != null && writable == 0) {
                    continue;
                }
                if (!group.prefix.matcher(row.getParamPath()).find()) {
                    continue;
                }
                String leaf = leafOf(row.getParamPath());
                if (leaf.isEmpty() || !group.leaf.matcher(leaf).find()) {
                    continue;
                }
                if (row.getParamValue() == null || row.getParamValue().isBlank()) {
                    continue;
                }
                candidates.add(row);
                if (candidates.size() >= PROBE_LIMIT) {
                    break;
                }
            }
            if (candidates.isEmpty()) {
                Probe skipped = new Probe();
                skipped.capability = group.label;
                skipped.path = "-";
                skipped.status = "无可写参数";
                probes.add(skipped);
                continue;
            }
            for (CpeParameter row : candidates) {
                probes.add(sendProbe(sn, group.label, row));
            }
        }
        return probes;
    }

    /** 单条试探下发：一次只写一个参数，拿到 CPE 的 Status / Fault 再判定。 */
    private Probe sendProbe(String sn, String capability, CpeParameter row) {
        Probe probe = new Probe();
        probe.capability = capability;
        probe.path = row.getParamPath();
        probe.value = row.getParamValue();
        ParameterValueStruct struct = ParameterValueStruct.of(row.getParamPath(), row.getParamValue());
        struct.setValueType(valueTypeOf(row.getValueType(), row.getParamValue()));
        try {
            CompletableFuture<SoapMessage> future = cwmpRpcService.setParameterValues(
                    sn, List.of(struct), "adapt-probe");
            SoapMessage resp = future.get(probeTimeoutSeconds, TimeUnit.SECONDS);
            if (resp == null) {
                probe.status = "无响应";
                probe.failed = true;
            } else if (resp.getFault() != null) {
                probe.status = CwmpRpcService.faultText(resp);
                probe.failed = true;
            } else {
                String status = CwmpRpcService.statusOf(resp);
                probe.status = status == null ? "无 Status" : status;
                probe.failed = !"0".equals(String.valueOf(status).trim());
            }
        } catch (TimeoutException e) {
            probe.status = "下发超时（设备可能离线，指令留在队列等下次上线）";
            probe.failed = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            probe.status = "下发中断";
            probe.failed = true;
        } catch (Exception e) {
            probe.status = "下发异常: " + e.getMessage();
            probe.failed = true;
        }
        if (probe.failed) {
            log.warn("机型适配试探下发未通过: sn={}, capability={}, path={}, status={}",
                    sn, capability, probe.path, probe.status);
        }
        return probe;
    }

    /** 试探下发的参数类型：优先用采集到的类型，没有就按值猜，与下发渲染器同一套规则。 */
    static String valueTypeOf(String declared, String value) {
        String v = declared == null ? "" : declared.trim();
        if (!v.isEmpty()) {
            return v.startsWith("xsd:") ? v.substring(4) : v;
        }
        if (value == null) {
            return "string";
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return "boolean";
        }
        if (value.matches("-?\\d+")) {
            return "unsignedInt";
        }
        return "string";
    }

    /** 试探分组：能力名 -> 命中该能力的子树正则 + 倾向试探的叶子名 + 申报了才测。 */
    private static final List<ProbeGroup> PROBE_GROUPS = List.of(
            // 网口只拿开关类参数做试探。速率（MaxBitRate）刻意排除：厂商对它的值域
            // 各行其是（bps 数字 / Mbps 数字 / 枚举 Auto /「1000M」这种自由文本），
            // 把上报值原样写回也常被判 9003，拿它当「能不能写」的证据只会误伤
            new ProbeGroup("网口", Pattern.compile(
                    "(?:LANDevice\\.\\d+\\.)?(?:LANEthernetInterfaceConfig|EthernetInterface)\\.\\d+\\."
                            + "|(?:^|\\.)Ethernet\\.Interface\\.\\d+\\."),
                    Pattern.compile("(?i)(enable|status|portbind|portenable)"),
                    req -> parseCount(req.getLanPorts()) > 0),
            new ProbeGroup("SIP", Pattern.compile(
                    "VoiceService\\.\\d+\\.VoiceProfile\\.\\d+\\."
                            + "|(?i)X_[a-z0-9-]+_SIP[A-Za-z]*\\.\\d+\\."),
                    Pattern.compile("(?i)(enable|username|authusername|registrar|proxy|port)"),
                    req -> parseCount(req.getSipCount()) > 0),
            new ProbeGroup("WIFI", Pattern.compile(
                    "(?:LANDevice\\.\\d+\\.)?WLANConfiguration\\.\\d+\\."
                            + "|(?:^|\\.)WiFi\\.(?:Radio|SSID)\\.\\d+\\."),
                    Pattern.compile("(?i)(enable|ssid|channel|bandwidth)"),
                    req -> req.getWifiSpec() != null && !req.getWifiSpec().isBlank()
                            && !"无".equals(req.getWifiSpec().trim())));

    /** 一条试探下发的执行结果。 */
    @Data
    static class Probe {
        private String capability;
        private String path;
        private String value;
        private String status;
        /** 未通过：下发故障、超时或返回非 0 状态。 */
        private boolean failed;

        Map<String, Object> asMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("capability", capability);
            data.put("path", path);
            data.put("value", value);
            data.put("status", status);
            data.put("failed", failed);
            return data;
        }
    }

    private static class ProbeGroup {
        final String label;
        final Pattern prefix;
        final Pattern leaf;
        final java.util.function.Predicate<AdaptRequest> required;

        ProbeGroup(String label, Pattern prefix, Pattern leaf,
                   java.util.function.Predicate<AdaptRequest> required) {
            this.label = label;
            this.prefix = prefix;
            this.leaf = leaf;
            this.required = required;
        }

        boolean required(AdaptRequest req) {
            return req != null && required.test(req);
        }
    }

    private static boolean isTrue(Integer flag) {
        return flag != null && flag == 1;
    }

    // ==================== 数据结构 ====================

    /** 实测能力快照，直接给前端展示。 */
    @Data
    static class Detection {
        private int lanPortCount;
        private Double lanSpeedMbps;
        private int sipCount;
        private int radioCount;
        private List<String> wifiBands = new ArrayList<>();
        /** 设备是否上报了语音容量；false 时「实测几路」只是下限，不能用来否定申报。 */
        private boolean sipCapacityKnown;

        Map<String, Object> asMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("lanPortCount", lanPortCount);
            data.put("lanSpeedMbps", lanSpeedMbps);
            data.put("sipCount", sipCount);
            data.put("sipCapacityKnown", sipCapacityKnown);
            data.put("radioCount", radioCount);
            data.put("wifiBands", wifiBands);
            return data;
        }
    }

    /** 适配申报单。 */
    @Data
    public static class AdaptRequest {
        /** 运营商：CT / CU / CMCC / standard */
        private String isp;
        /** 网口数量：无/1/2/3/4-8 */
        private String lanPorts;
        /** 网口速率：无/自动/100M/1000M/2500M */
        private String lanSpeed;
        /** SIP 数量：无/1/2/3/4-8 */
        private String sipCount;
        /** WIFI 规格：无/2.4G/2.4G+5.8G */
        private String wifiSpec;

        Map<String, Object> asMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("isp", isp);
            data.put("lanPorts", lanPorts);
            data.put("lanSpeed", lanSpeed);
            data.put("sipCount", sipCount);
            data.put("wifiSpec", wifiSpec);
            return data;
        }
    }

    private CpeDevice deviceBySn(String sn) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                .eq(CpeDevice::getSn, sn)
                .last("LIMIT 1"));
    }
}

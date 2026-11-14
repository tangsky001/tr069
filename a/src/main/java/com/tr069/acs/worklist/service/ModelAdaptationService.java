package com.tr069.acs.worklist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.acs.adaptation.AdaptationManager;
import com.tr069.acs.acs.adaptation.IspAdaptation;
import com.tr069.acs.acs.service.CwmpRpcService;
import com.tr069.acs.acs.service.DeviceCapabilityService;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeModel;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import com.tr069.acs.worklist.tpl.dao.DeviceDao;
import com.tr069.acs.worklist.tpl.entity.Device;
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

/** 机型适配：以「运营商 + 型号」为维度，把一台样机的能力固化成库里的型号档案 */
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
    /** SQLite 模板库：适配结果的唯一权威，下发查的就是它，列表判定必须跟它一致 */
    private final DeviceDao deviceDao;
    private final AdaptationManager adaptationManager;

    /** 测试时等待光猫返回参数树的最长时间，秒。上千参数按批串行读，120s 经常不够 */
    @Value("${acs.adapt.test-timeout-seconds:300}")
    private int testTimeoutSeconds;

    /** 单次试探下发等待设备响应的时间，秒 */
    @Value("${acs.adapt.probe-timeout-seconds:30}")
    private int probeTimeoutSeconds;

    /** 算「采到了数据」的采集状态：partial（GPN 回 9002 只能 GPV 兜底读值，电信定制固件均如此且读回真实参数）也算成功 */
    private static final Set<String> COLLECTED_OK = Set.of("success", "partial");

    // ==================== 适配状态 ====================

    /** 单台设备的适配状态：先查 SQLite 模板库，再退到文件与 MySQL 档案 */
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
        if (model == null) {
            // 型号表登记不上时不能抛异常：status 是批量接口，一台抛了整页拿不到结果；适配结果在 SQLite 模板库里照旧能判
            Device only = adaptedInTemplateLib(device.getIsp(), ModelMappingService.buildModelKey(
                    device.getProductClass(), device.getSoftwareVersion()));
            data.put("isp", device.getIsp());
            data.put("productClass", device.getProductClass());
            data.put("softwareVersion", device.getSoftwareVersion());
            data.put("ispText", ispTextOf(data));
            data.put("capabilityText", null);
            data.put("adapted", only != null);
            if (only != null) {
                data.put("adaptedFrom", "sqlite");
                data.put("adaptedIsp", only.getIsp());
                data.put("templateLibId", only.getId());
            } else {
                data.put("adaptedFrom", "none");
                data.put("hint", "型号未登记，请先采集设备能力树");
            }
            return data;
        }
        data.put("isp", model.getIsp());
        data.put("productClass", model.getProductClass());
        data.put("softwareVersion", model.getSoftwareVersion());
        data.put("modelId", model.getId());
        data.put("modelStatus", model.getStatus());
        data.put("capLanPorts", model.getCapLanPorts());
        data.put("capLanSpeed", model.getCapLanSpeed());
        data.put("capSipCount", model.getCapSipCount());
        data.put("capWifiSpec", model.getCapWifiSpec());
        // 一、SQLite 模板库：适配结果在这里，下发也查这里，判定以它为准
        Device tpl = adaptedInTemplateLib(model.getIsp(), model.getModelKey());
        boolean adapted = tpl != null;
        String adaptedIsp = null;
        if (adapted) {
            adaptedIsp = tpl.getIsp();
            data.put("adaptedFrom", "sqlite");
            data.put("adaptedIsp", adaptedIsp);
            data.put("templateLibId", tpl.getId());
        }
        // 二、模板库里没有（老库迁移前）才退到 YML 适配档：已适配看 adapted 标记，XML 只是能力档案，存在不算适配过
        if (!adapted) {
            adapted = profileStore.isAdapted(model);
            if (adapted) {
                data.put("adaptedFrom", "yml");
            }
        }
        if (!adapted) {
            // 设备上报的 isp 可能与适配申报的不同，同型号在任意运营商下有 adapted:true 档案即视为已适配——不然适配完列表永远挂着红色「适配」入口
            adaptedIsp = profileStore.findAdaptedIsp(model.getModelKey());
            if (adaptedIsp != null) {
                adapted = true;
                data.put("adaptedFrom", "yml");
                data.put("adaptedIsp", adaptedIsp);
                data.put("profile", profileStore.readByIsp(model.getModelKey(), adaptedIsp));
            }
        }
        if (!adapted) {
            // 兜底：适配文件没生成或没找到时以库为准——有 config_path 且状态 READY 就视为已适配，配置已落库
            if (model.getConfigPath() != null && !model.getConfigPath().isBlank()
                    && CpeModel.STATUS_READY.equals(model.getStatus())) {
                adapted = true;
                data.put("adaptedFromDb", true);
                data.put("adaptedFrom", "mysql");
                data.put("adaptedIsp", adaptedIsp != null ? adaptedIsp : model.getIsp());
                data.putIfAbsent("profile", profileStore.read(model));
            }
        }
        data.put("adapted", adapted);
        data.put("configPath", model.getConfigPath());
        data.put("adaptPath", model.getAdaptPath());
        data.putIfAbsent("profile", profileStore.read(model));
        // 运营商中文名与能力串：设备详情/列表页/北向都在拼同一段文字，口径只在这里
        data.put("ispText", ispTextOf(data));
        data.put("capabilityText", adapted ? capabilityTextOf(data) : null);
        return data;
    }

    /** 运营商显示名：适配档里申报的 isp 优先，其次台账/模型上登记的值，码表在 AdaptationManager */
    @SuppressWarnings("unchecked")
    private String ispTextOf(Map<String, Object> data) {
        Object profile = data.get("profile");
        String isp = "";
        if (profile instanceof Map<?, ?> map) {
            isp = str(((Map<String, Object>) map).get("isp"));
        }
        if (isp.isBlank()) {
            isp = str(data.get("adaptedIsp"));
        }
        if (isp.isBlank()) {
            isp = str(data.get("isp"));
        }
        return isp.isBlank() ? "" : adaptationManager.labels().getOrDefault(isp, isp);
    }

    /** 已适配设备的能力串：网口数量/速率、SIP 路数、WiFi 规格、样机 SN */
    @SuppressWarnings("unchecked")
    private static String capabilityTextOf(Map<String, Object> data) {
        Object profileObj = data.get("profile");
        Map<String, Object> profile = profileObj instanceof Map<?, ?>
                ? (Map<String, Object>) profileObj : java.util.Map.of();
        String lanPorts = firstText(profile.get("lan_ports"), data.get("capLanPorts"));
        String lanSpeed = firstText(profile.get("lan_speed"), data.get("capLanSpeed"));
        String sipCount = firstText(profile.get("sip_count"), data.get("capSipCount"));
        String wifiSpec = firstText(profile.get("wifi_spec"), data.get("capWifiSpec"));
        String sampleSn = firstText(profile.get("sample_sn"), data.get("sampleSn"));
        List<String> parts = new java.util.ArrayList<>();
        if (!lanPorts.isBlank() && !"无".equals(lanPorts)) {
            boolean speedUseful = !lanSpeed.isBlank() && !"无".equals(lanSpeed) && !"自动".equals(lanSpeed);
            parts.add("网口 " + lanPorts + (speedUseful ? "×" + lanSpeed : ""));
        } else if ("无".equals(lanPorts)) {
            parts.add("无网口");
        }
        if (!sipCount.isBlank() && !"无".equals(sipCount)) {
            parts.add("SIP " + sipCount + "路");
        } else if ("无".equals(sipCount)) {
            parts.add("无SIP");
        }
        if (!wifiSpec.isBlank() && !"无".equals(wifiSpec)) {
            parts.add("WiFi " + wifiSpec);
        } else if ("无".equals(wifiSpec)) {
            parts.add("无WiFi");
        }
        String text = String.join("、", parts);
        if (!sampleSn.isBlank()) {
            text = text + "（ " + sampleSn + "）";
        }
        return text.isBlank() ? null : text;
    }

    private static String firstText(Object primary, Object fallback) {
        String value = str(primary);
        return value.isBlank() ? str(fallback) : value;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** SQLite 模板库里这个型号的适配结果，查不到返回 null */
    private Device adaptedInTemplateLib(String isp, String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return null;
        }
        String owner = (isp == null || isp.isBlank()) ? "standard" : isp.trim();
        Device row = deviceDao.findByIspModelKey(owner, modelKey).orElse(null);
        if (row == null || !declared(row)) {
            Device withCap = deviceDao.findByModelKey(modelKey).stream()
                    .filter(ModelAdaptationService::declared)
                    .findFirst().orElse(null);
            if (withCap != null) {
                return withCap;
            }
        }
        if (row == null && !"standard".equals(owner)) {
            row = deviceDao.findByIspModelKey("standard", modelKey).orElse(null);
        }
        return row != null && declared(row) ? row : null;
    }

    /** 这份归档有没有真正做过能力申报：没申报的生成不出模板，等于没适配 */
    private static boolean declared(Device row) {
        if (row == null) {
            return false;
        }
        if ("ADAPTED".equalsIgnoreCase(row.getStatus())) {
            return true;
        }
        return notBlank(row.getCapWifiSpec()) || notBlank(row.getCapSipCount())
                || notBlank(row.getCapLanPorts());
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /** 整页设备批量适配状态，返回 sn -> 状态，供设备列表一次请求渲染「已适配/适配」标识 */
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

    /** 向光猫下发采集 RPC 并比对申报能力 */
    public Map<String, Object> test(String sn, AdaptRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requested", request.asMap());
        CollectResult collect = collectTree(sn, request.getIsp());
        data.put("collectStatus", collect.status);
        data.put("collectInterrupted", collect.interrupted);
        if (collect.error != null) {
            data.put("collectError", collect.error);
        }
        List<CpeParameter> params = parameterMapper.selectList(
                new LambdaQueryWrapper<CpeParameter>()
                        .eq(CpeParameter::getSn, sn)
                        .orderByAsc(CpeParameter::getParamPath));
        Detection detected = detect(params);
        data.put("detected", detected.asMap());
        List<Check> checks = checks(detected, request);
        data.put("checks", checks.stream().map(Check::asMap).toList());
        List<String> reasons = new ArrayList<>(compare(detected, request));
        List<String> notes = new ArrayList<>(notes(detected, request));
        // 采集状态判定：partial 也算数——电信定制固件 GPN 对父对象一律 9002 只能 GPV 兜底读值，照样读回真实参数，判失败等于把电信设备挡在门外。
        // running 是「还没采完」不是失败：等待超时但能力比对全过时按通过处理，结论基于已采到的参数
        boolean collected = COLLECTED_OK.contains(collect.status);
        if ("running".equals(collect.status)) {
            if (reasons.isEmpty()) {
                notes.add("采集仍在进行（等待超时未结束），本次结论基于已采到的参数，能力比对全部通过");
            }
            collected = false;
        } else if (!COLLECTED_OK.contains(collect.status) && reasons.isEmpty()) {
            if (collect.interrupted) {
                reasons.add("设备会话中断，采集未完成（不是能力不满足），请重新测试");
            } else {
                reasons.add("能力采集未成功(" + collect.status + ")"
                        + (collect.error == null ? "" : "：" + collect.error));
            }
        }
        // 真下发一次：把参数当前值原样写回，验证光猫确实能执行这些能力
        List<Probe> probes = collected ? probeCapabilities(sn, params, request) : List.of();
        data.put("probes", probes.stream().map(Probe::asMap).toList());
        // 试探失败只作提示：Writable 未知时候选面本来就宽，写回同值被拒常只是值域问题（MaxBitRate 报「1000M」），不代表能力缺失
        for (Probe p : probes) {
            if (p.failed) {
                notes.add("参数下发未通过：" + p.capability + " " + p.path + " -> " + p.status);
            }
        }
        // 没挑到候选的组要说清原因：表格里的「-」多数时候是路径没采到或值为空，不是没有这块能力
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

    /** 测试通过后保存：导出型号能力树 XML 并落库 */
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
        // 落库存申报值而非实测值：申报是目标、实测只是验证，实测受固件参数树完整度影响可能偏低，存实测会拖累同型号设备
        CpeModel patch = new CpeModel();
        patch.setCapLanPorts(request.getLanPorts());
        patch.setCapLanSpeed(request.getLanSpeed());
        patch.setCapSipCount(request.getSipCount());
        // 实测有 5.8G 但申报只勾 2.4G 时按实测补齐：漏报 5G 会让模板库里没有 wlan_b 表，改 5G SSID 一律报「模板里没有实例」
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
        // 申报值必须同步进 SQLite 模板库：上面 export 发生在申报之前，库里还没有能力字段生成不出模板；这里回写并按申报重新生成
        modelTreeStore.applyCapability(isp, updated.getModelKey(), updated);
        // 测试通过才写适配档：adapted: true，同型号设备上线读它判定已适配
        String adaptPath = profileStore.writeAdapted(updated, isp, sn, request);
        updated = modelMappingService.update(model.getId(),
                solePath(adaptPath));
        // 适配时选定的运营商写回台账：光猫上报通常不带运营商，人工申报的归属才是生效值，列表/详情/能力树归档都按它走
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

    /** 强制采集并等待结束，返回采集状态与首条错误 */
    private CollectResult collectTree(String sn) {
        return collectTree(sn, null);
    }

    /** 强制采集并等待结束，可指定采集标准所用运营商：适配页选了运营商就按这档采，台账还是 standard 时走通用档采不到私有参数、测出的能力偏低 */
    private CollectResult collectTree(String sn, String isp) {
        CollectResult result = new CollectResult();
        DeviceCapabilityService.CollectStatus status;
        try {
            status = capabilityService.collectWithIsp(sn, isp);
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
        // 会话中断（设备中途不再回应）不是能力问题：单独立标记，测试结果按「请重测」呈现而不是「不满足」
        result.interrupted = status.isAborted()
                || (status.getMessage() != null && status.getMessage().contains("采集中断"));
        List<String> errors = status.getErrors();
        if (errors != null && !errors.isEmpty()) {
            // 倒序找第一条真实故障：单条路径被拒「已跳过」是探测的正常保护动作，不该当成采集失败的原因展示
            for (int i = errors.size() - 1; i >= 0; i--) {
                if (!errors.get(i).contains("已跳过")) {
                    result.error = errors.get(i);
                    break;
                }
            }
        }
        return result;
    }

    private static class CollectResult {
        String status = "unknown";
        String error;
        boolean interrupted;
    }

    // ==================== 能力解析 ====================

    /** 网口实例：优先 LANDevice 下的配置对象，退化到 TR-181 的 Ethernet.Interface */
    private static final Pattern LAN_IF = Pattern.compile(
            "(?:LANDevice\\.\\d+\\.)?(?:LANEthernetInterfaceConfig|EthernetInterface)\\.(\\d+)\\.");
    private static final Pattern ETH_IF_TR181 = Pattern.compile("(?:^|\\.)Ethernet\\.Interface\\.(\\d+)\\.");
    private static final Pattern WLAN_IF = Pattern.compile(
            "(?:LANDevice\\.\\d+\\.)?WLANConfiguration\\.(\\d+)\\.");
    private static final Pattern WIFI_RADIO = Pattern.compile("(?:^|\\.)WiFi\\.Radio\\.(\\d+)\\.");
    private static final Pattern VOICE_PROFILE = Pattern.compile(
            "VoiceService\\.\\d+\\.VoiceProfile\\.(\\d+)\\.");
    /** 语音物理口，如 VoiceService.1.PhyInterface.2 */
    private static final Pattern VOICE_PHY = Pattern.compile(
            "VoiceService\\.\\d+\\.PhyInterface\\.(\\d+)\\.");
    /** 厂商私有 SIP 对象，如 X_CMCC_SipObj.1、X_ZTE-COM_SIP.2 */
    private static final Pattern VENDOR_SIP = Pattern.compile(
            "(?i)X_[a-z0-9-]+_SIP[A-Za-z]*\\.(\\d+)\\.");
    private static final Pattern SPEED_LEAF = Pattern.compile(
            "(?i)(maxbitrate|maxspeed|linkrate|portspeed|linkspeed)$");
    private static final Pattern BAND_LEAF = Pattern.compile(
            "(?i)(frequencyband|operatingfrequencyband|frequency)");
    /** 信道列表叶子。2.4G 只用 1~14，5G 从 36 起，据此反推频段—— */
    private static final Pattern CHANNEL_LEAF = Pattern.compile(
            "(?i)(possiblechannels|supportedchannels|channelsinuse|channel)$");
    private static final Pattern CHANNEL_NUMBER = Pattern.compile("\\d+");
    /** 2.4G 的最大信道号，超过它的就是 5G */
    private static final int MAX_2G_CHANNEL = 14;

    /** 从参数树解析设备实际能力：网口数量、网口速率(Mbps)、SIP 数量、WIFI 频段 */
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
        // 设备自报的条目数：有些固件不建 LANEthernetInterfaceConfig、语音只建一路 Line，光数实例会把 4 口 8 路误判成 0 口 1 路
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
        // 只标 5G 不标 2.4G 是行业惯例：认出 5.8G 还有射频没标频段时，没标的按 2.4G 认定；反过来不成立（单射频标 5G 就是纯 5G）
        if (bands.contains("5.8G") && !bands.contains("2.4G") && radioCount > bandedWlan.size()) {
            bands.add("2.4G");
        }
        // 频段一个都识别不出时按射频数推断（单射频 2.4G、双射频双频）：老固件不报 OperatingFrequencyBand、SSID 也不带频段，不兜底申报永远过不了
        if (bands.isEmpty() && radioCount >= 1) {
            bands.add("2.4G");
            if (radioCount >= 2) {
                bands.add("5.8G");
            }
        }
        Detection d = new Detection();
        // 语音容量有没有证据：条目数可读或能数出多路实例才算；一个 VoiceProfile、一条 Line、条目数全 9005 攒出的「1 路」不算
        d.sipCapacityKnown = profileEntries > 0 || lineEntries > 0 || phyEntries > 0
                || voiceProfiles.size() > 1 || voicePhys.size() > 1 || vendorSips.size() > 1;
        d.lanPortCount = Math.max(Math.max(lanIfs.size(), ethIfs.size()), lanEntries);
        d.lanSpeedMbps = maxSpeedMbps > 0 ? maxSpeedMbps : null;
        // SIP 数量按「能打几路电话」口径：各来源取最大——只有一路 Line 实例但 LineNumberOfEntries=8 的机型很常见
        d.sipCount = Math.max(
                Math.max(Math.max(voiceProfiles.size(), voicePhys.size()), vendorSips.size()),
                Math.max(Math.max(profileEntries, lineEntries), phyEntries));
        d.radioCount = radioCount;
        d.wifiBands = new ArrayList<>(bands);
        return d;
    }

    /** 从叶子参数提取网口速率，统一换算成 Mbps；非速率叶子返回 -1 */
    private double speedMbps(CpeParameter row, String leaf) {
        if (leaf.isEmpty() || !SPEED_LEAF.matcher(leaf).find()) {
            return -1;
        }
        Double parsed = parseSpeedValue(row.getParamValue());
        if (parsed == null) {
            return -1;
        }
        double v = parsed;
        // MaxBitRate 常见单位为 Mbps，个别厂商报 kbps（>=10000 视为 kbps）
        return v >= 10000 ? v / 1000.0 : v;
    }

    /** 速率值转数字：认 1000 / "1000M" / "1G" / "Auto"；早先只用 parseDouble，电信固件把 MaxBitRate 报成 "1000M" 一律解析失败、速率恒 null */
    private static Double parseSpeedValue(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            // 不是纯数字，走带单位的解析
        }
        // 剥掉单位后缀再试：1000M / 1000Mb / 1000Mbps / 1G / 1Gb
        String digits = text.replaceAll("[^0-9.]", "");
        if (digits.isEmpty()) {
            return null;
        }
        double v;
        try {
            v = Double.parseDouble(digits);
        } catch (NumberFormatException e) {
            return null;
        }
        // 带 G 后缀的换算成 Mbps；带 M 的就是 Mbps 原样
        if (text.contains("g") && !text.contains("m")) {
            return v * 1000.0;
        }
        return v;
    }

    /** 从频段字段、信道列表或 802.11 制式推断 WIFI 频段，返回这条参数是否给出了频段证据 */
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
        } else if (isSsidLeaf(leaf)) {
            // SSID 只有带频段字样才作数（CMCC-5G 认得出，CMCC-0002 认不出）；叶子按后缀匹配，厂商私有变体同样能认出
            collectBandsFromText(lower, bands);
        }
        return bands.size() > before;
    }

    /** SSID 类叶子：标准 SSID 及厂商私有 X_CT-COM_SSID / X_CMCC_SSID 变体；早先只全等比较 SSID，电信定制机的频段字样全被漏掉 */
    private static boolean isSsidLeaf(String leaf) {
        if (leaf == null || leaf.isEmpty()) {
            return false;
        }
        String lower = leaf.toLowerCase(Locale.ROOT);
        return lower.equals("ssid") || lower.endsWith("_ssid");
    }

    /** 从文本里找频段字样。两个频段分开判断，多值字段才能都认出来 */
    private static void collectBandsFromText(String lower, Set<String> bands) {
        if (lower.contains("2.4") || lower.contains("2g") || lower.contains("24g")) {
            bands.add("2.4G");
        }
        if (lower.contains("5.8") || lower.contains("5g") || lower.contains("5ghz")) {
            bands.add("5.8G");
        }
    }

    /** 取信道列表里的最大信道号：{@code 1-13}、{@code 36 40 44}、{@code 36-64,149-165} 都能解析 */
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

    /** NumberOfEntries 这类计数字段，读不出数字按 0 处理 */
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

    /** 运营商候选来自各运营商适配类：v 是存库值，t 是展示名 */
    private List<Map<String, String>> ispOptions() {
        List<Map<String, String>> options = new ArrayList<>();
        for (IspAdaptation a : adaptationManager.all()) {
            options.add(Map.of("v", a.code(), "t", a.shortLabel()));
        }
        return options;
    }

    /** 网口数量与 SIP 数量共用：两者口径一致，都是「能接几条」 */
    private static final List<String> PORT_OPTIONS = List.of("无", "1", "2", "3", "4", "8");

    private static final List<String> SPEED_OPTIONS = List.of("无", "自动", "100M", "1000M", "2500M");

    private static final List<String> WIFI_SPEC_OPTIONS = List.of("无", "2.4G", "2.4G+5.8G");

    /** 能力申报的候选值 */
    public Map<String, Object> capabilityOptions() {
        return Map.of(
                "isp", ispOptions(),
                "ports", PORT_OPTIONS,
                "speeds", SPEED_OPTIONS,
                "wifiSpecs", WIFI_SPEC_OPTIONS);
    }

    /** 逐项比对申报能力与实测能力，返回不满足的原因列表，空列表即全部支持 */
    /** 单项能力比对结论，前端按 ✓/✗ 逐项渲染 */
    @Data
    static class Check {

        /** 能力项名称：网口数量 / 网口速率 / SIP 数量 / WIFI 规格 */
        private String label;

        /** 是否参与比对：申报「无 / 自动」时不比对 */
        private boolean checked;

        /** 比对结论：true 满足 */
        private boolean pass;

        /** 申报与实测的对照说明 */
        private String detail;

        /** 不满足时的原因；满足时为 null */
        private String reason;

        Map<String, Object> asMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("label", label);
            data.put("checked", checked);
            data.put("pass", pass);
            data.put("detail", detail);
            data.put("reason", reason);
            return data;
        }
    }

    /** 逐项比对申报与实测，每项独立给结论 */
    List<Check> checks(Detection d, AdaptRequest req) {
        List<Check> result = new ArrayList<>();

        int lanRequired = parseCount(req.getLanPorts());
        Check lan = new Check();
        lan.setLabel("网口数量");
        lan.setChecked(lanRequired > 0);
        lan.setDetail(lanRequired > 0
                ? "申报 " + req.getLanPorts() + "，实测 " + d.lanPortCount
                : "未申报，不比对");
        if (lanRequired > 0) {
            lan.setPass(d.lanPortCount >= lanRequired);
            if (!lan.isPass()) {
                lan.setReason("网口数量不足：申报 " + req.getLanPorts() + "，实测 " + d.lanPortCount);
            }
        }
        result.add(lan);

        double speedRequired = parseSpeed(req.getLanSpeed());
        Check speed = new Check();
        speed.setLabel("网口速率");
        speed.setChecked(speedRequired > 0);
        if (speedRequired > 0) {
            if (d.lanSpeedMbps == null) {
                speed.setPass(false);
                speed.setDetail("申报 " + req.getLanSpeed() + "，实测未采集到速率参数");
                speed.setReason("未采集到网口速率参数，无法确认申报的 " + req.getLanSpeed());
            } else {
                speed.setPass(d.lanSpeedMbps >= speedRequired);
                speed.setDetail(String.format("申报 %s，实测最高 %.0fMbps",
                        req.getLanSpeed(), d.lanSpeedMbps));
                if (!speed.isPass()) {
                    speed.setReason(String.format("网口速率不足：申报 %s，实测最高 %.0fMbps",
                            req.getLanSpeed(), d.lanSpeedMbps));
                }
            }
        } else {
            speed.setDetail("未申报，不比对");
        }
        result.add(speed);

        int sipRequired = parseCount(req.getSipCount());
        Check sip = new Check();
        sip.setLabel("SIP 数量");
        sip.setChecked(sipRequired > 0);
        sip.setDetail(sipRequired > 0
                ? "申报 " + req.getSipCount() + "，实测 " + d.sipCount + " 路"
                : "未申报，不比对");
        if (sipRequired > 0) {
            // 只有设备确实报了语音容量才谈「不足」：有的固件只暴露 Line.1，攒出的「1 路」不是证据，按实物申报 8 路反倒被挡
            boolean pass = !(d.sipCount < sipRequired && d.sipCapacityKnown);
            sip.setPass(pass);
            if (!pass) {
                sip.setReason("SIP 数量不足：申报 " + req.getSipCount() + "，实测 " + d.sipCount);
            }
        }
        result.add(sip);

        String wifi = req.getWifiSpec();
        boolean wifiChecked = wifi != null && (wifi.contains("2.4") || wifi.contains("5.8"));
        Check wifiCheck = new Check();
        wifiCheck.setLabel("WIFI 规格");
        wifiCheck.setChecked(wifiChecked);
        wifiCheck.setDetail(wifiChecked
                ? "申报 " + wifi + "，实测 " + (d.wifiBands.isEmpty() ? "无" : String.join("+", d.wifiBands))
                : "未申报，不比对");
        if (wifiChecked) {
            boolean pass = true;
            StringBuilder reason = new StringBuilder();
            if (wifi.contains("2.4") && !d.wifiBands.contains("2.4G")) {
                pass = false;
                reason.append("未检测到 2.4G WIFI");
            }
            if (wifi.contains("5.8") && !d.wifiBands.contains("5.8G")) {
                if (!reason.isEmpty()) {
                    reason.append("；");
                }
                reason.append("未检测到 5.8G WIFI");
            }
            wifiCheck.setPass(pass);
            wifiCheck.setReason(pass ? null : reason.toString());
        }
        result.add(wifiCheck);

        return result;
    }

    List<String> compare(Detection d, AdaptRequest req) {
        List<String> reasons = new ArrayList<>();
        for (Check c : checks(d, req)) {
            if (c.isChecked() && !c.isPass() && c.getReason() != null) {
                reasons.add(c.getReason());
            }
        }
        return reasons;
    }

    /** 需要提醒、但不阻断保存的项 */
    List<String> notes(Detection d, AdaptRequest req) {
        List<String> notes = new ArrayList<>();
        int sipRequired = parseCount(req.getSipCount());
        if (sipRequired > 0 && !d.sipCapacityKnown) {
            notes.add("设备未上报语音容量（Line / PhyInterface 条目数均不可读），"
                    + "SIP " + req.getSipCount() + " 路按申报为准，请核对实物语音口数量");
        }
        return notes;
    }

    /** 申报数量：无=0，4-8=4（下限），其余取数字 */
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

    /** 申报速率：无/自动=0（不校验），100M=100，1000M=1000 */
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

    /** 每组最多挑几个可写叶子做试探，减少下发轮次也降低打扰 */
    private static final int PROBE_LIMIT = 2;

    /** 试探下发：把采集到的当前值原样写成 SetParameterValues 下发给光猫 */
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
                // 0=设备明确说只读；null/-1（Writable 无从而知）都放行——真能不能写由这次下发自己证明
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

    /** 单条试探下发：一次只写一个参数，拿到 CPE 的 Status / Fault 再判定 */
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

    /** 试探下发的参数类型：优先用采集到的类型，没有就按值猜，与下发渲染器同一套规则 */
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

    /** 试探分组：能力名 -> 命中该能力的子树正则 + 倾向试探的叶子名 + 申报了才测 */
    private static final List<ProbeGroup> PROBE_GROUPS = List.of(
            // 网口只拿开关类参数做试探，速率 MaxBitRate 刻意排除：厂商值域各行其是（bps/Mbps/Auto/"1000M"），原样写回也常 9003，当「能不能写」的证据只会误伤
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

    /** 一条试探下发的执行结果 */
    @Data
    static class Probe {
        private String capability;
        private String path;
        private String value;
        private String status;
        /** 未通过：下发故障、超时或返回非 0 状态 */
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

    /** 实测能力快照，直接给前端展示 */
    @Data
    static class Detection {
        private int lanPortCount;
        private Double lanSpeedMbps;
        private int sipCount;
        private int radioCount;
        private List<String> wifiBands = new ArrayList<>();
        /** 设备是否上报了语音容量；false 时「实测几路」只是下限，不能用来否定申报 */
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

    /** 适配申报单 */
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

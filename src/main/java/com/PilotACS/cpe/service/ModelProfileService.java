/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.IspVariant;
import com.PilotACS.cpe.rpc.TaskPlanner;
import com.PilotACS.cpe.session.CwmpSession;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.log.Logs;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 机型模板库：品牌+型号+版本号+运营商 唯一绑定一份设备参数树。 */
@Service
@RequiredArgsConstructor
public class ModelProfileService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 参数树低于该值视为没采全，值得自动探测一次 */
    private static final int FULL_TREE_MIN_PARAMS = 100;

    /** 本次启动内已自动排过探测的 sn（防止周期 Inform 反复排任务） */
    private final Set<String> ispProbeDone = ConcurrentHashMap.newKeySet();

    private final CpeStore store;
    private final CapabilityDetector capabilityDetector;
    private final CpeProperties props;
    private final TaskPlanner planner;

    /** 台账没写运营商时，从落库的参数树私有节点识别一次并回填（已写的不再动） */
    public String detectIsp(String sn) {
        try {
            Map<String, Object> device = store.findDevice(sn);
            if (device == null) {
                Logs.warn(Logs.Module.CPE, "运营商识别跳过：设备台账不存在", "sn", sn);
                return null;
            }
            String isp = str(device.get("isp"));
            if (isp != null && !isp.isBlank()) {
                return isp;
            }
            Map<String, CpeParam> params = store.loadParams(sn);
            String hit = detectIspFromPaths(sn, params.keySet());
            if (hit == null) {
                // 没认出来：把参数树里 X_ 开头的二段节点取样打出来，方便补前缀表
                Set<String> samples = new LinkedHashSet<>();
                for (String path : params.keySet()) {
                    int i = path.indexOf(".X_");
                    if (i < 0) {
                        continue;
                    }
                    int end = path.indexOf('.', i + 1);
                    samples.add(end < 0 ? path.substring(i + 1) : path.substring(i + 1, end));
                    if (samples.size() >= 5) {
                        break;
                    }
                }
                Logs.info(Logs.Module.CPE, "运营商识别未命中", "sn", sn,
                        "参数数", params.size(), "私有节点样本", samples);
            }
            return hit;
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE, "运营商识别异常", "sn", sn, "原因", e.toString());
            return null;
        }
    }

    /** 从一批参数路径识别运营商并回填台账。会话内可直接传入内存参数树， */
    public String detectIspFromPaths(String sn, java.util.Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> device = store.findDevice(sn);
            String known = device == null ? null : str(device.get("isp"));
            if (known != null && !known.isBlank()) {
                // 已申报/已识别过的不再覆盖
                return known;
            }
            IspVariant hit = null;
            for (String path : paths) {
                hit = IspVariant.ofPath(path);
                if (hit != null) {
                    break;
                }
            }
            if (hit == null) {
                return null;
            }
            store.updateDeviceColumns(sn, Map.of("isp", hit.code()));
            Logs.info(Logs.Module.CPE, "按私有节点识别运营商", "sn", sn,
                    "运营商", hit.code(), "参数数", paths.size());
            return hit.code();
        } catch (Exception e) {
            // 识别/回填失败不能拖垮会话收尾
            Logs.warn(Logs.Module.CPE, "运营商识别回填失败", "sn", sn, "原因", e.toString());
            return null;
        }
    }

    /** 会话结束调用：识别运营商；认不出且树不完整就排一次探测任务（下次 Inform 执行） */
    public String detectIspAfterSession(String sn) {
        String isp = detectIsp(sn);
        if (isp == null) {
            maybeProbeTree(sn, store.loadParams(sn).size());
        }
        return isp;
    }

    /** Inform 时调用：运营商未知且树不完整 → 直接把探测任务插进**当前会话队列**， */
    private void probeInSession(CwmpSession s) {
        String sn = s.getSn();
        if (ispProbeDone.contains(sn)) {
            return;
        }
        ispProbeDone.add(sn);
        int count = s.getData().all().size();
        if (count >= FULL_TREE_MIN_PARAMS) {
            Logs.info(Logs.Module.CPE, "参数树已采全且无私有节点，不再探测", "sn", sn, "参数数", count);
            return;
        }
        if (store.countActiveTasks(sn, "AdaptProbe") > 0) {
            // 库里已有探测任务：会话装载时会跑，不重复排
            return;
        }
        long id = store.insertTask(sn, "AdaptProbe", Map.of(), "system-isp-detect");
        Map<String, Object> row = store.getTask(id);
        String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
        s.getQueue().add(planner.build(row, s.getData(), root, s.getModelParams()));
        Logs.info(Logs.Module.CPE, "运营商未知且参数树不完整，本会话立即全树探测",
                "sn", sn, "参数数", count, "任务", id);
    }

    /** 系统业务刷新任务的创建人标识（防重复排队用） */
    public static final String BIZ_REFRESH_BY = "system-biz-refresh";

    /** Inform 时调用：库里有业务参数树 → 本会话排一条轻量 GPV 刷 WAN/WIFI/SIP 最新值。 */
    public void refreshBizInSession(CwmpSession s) {
        try {
            java.util.List<String> leaves = bizRefreshNames(s.getSn());
            if (leaves.isEmpty()) {
                // 新设备还没有树：全树探测已由 attachModel/probeInSession 安排
                return;
            }
            if (store.countActiveTasks(s.getSn(), TaskPlanner.TYPE_GPV, BIZ_REFRESH_BY) > 0) {
                return;
            }
            long id = store.insertTask(s.getSn(), TaskPlanner.TYPE_GPV,
                    Map.of("names", leaves, "bizRefresh", true), BIZ_REFRESH_BY);
            Map<String, Object> row = store.getTask(id);
            String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
            s.getQueue().add(planner.build(row, s.getData(), root, s.getModelParams()));
            Logs.debug(Logs.Module.CPE, "本会话排业务参数轻量刷新",
                    "sn", s.getSn(), "参数数", leaves.size(), "任务", id);
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE, "业务参数刷新排程失败", "sn", s.getSn(), "原因", e.toString());
        }
    }

    /** 库里业务域（WAN/WIFI/SIP）叶子路径；没有树返回空集合。供会话内刷新与控制台采集共用 */
    public java.util.List<String> bizRefreshNames(String sn) {
        Map<String, CpeParam> params = store.loadParams(sn);
        if (params.isEmpty()) {
            return java.util.List.of();
        }
        com.PilotACS.cpe.model.DeviceData data = new com.PilotACS.cpe.model.DeviceData();
        data.loadAll(params);
        String root = BusinessPaths.detectRoot(data, props.getDefaultRootNode());
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        java.util.Set<String> bad = badParamNames.getOrDefault(sn, java.util.Set.of());
        for (String br : BusinessPaths.businessRoots(root)) {
            for (Map.Entry<String, CpeParam> e : params.entrySet()) {
                String p = e.getKey();
                if (!p.startsWith(br) || p.endsWith(".")) {
                    continue;
                }
                // 空壳路径（GPN 登记过但从未采到值）与已学习的 9005 坏名：不排进 GPV，省得每轮撞 Fault
                CpeParam v = e.getValue();
                if (v == null || v.value() == null || v.value().isBlank() || bad.contains(p)) {
                    continue;
                }
                out.add(p);
            }
        }
        return new java.util.ArrayList<>(out);
    }

    // ==================== 9005 坏参数名学习（GPV 报 9005 的名字直接过滤，避免重复二分） ====================

    /** 设备不认的参数名：sn -> 名字集合（重启后重建，代价只是一轮重新学习） */
    private final Map<String, java.util.Set<String>> badParamNames = new java.util.concurrent.ConcurrentHashMap<>();

    /** 记住设备不认的参数名，后续采集直接跳过。 */
    public void rememberBadParam(String sn, String name) {
        if (sn != null && name != null) {
            if (badParamNames.size() >= 50_000) {
                badParamNames.clear();
            }
            badParamNames.computeIfAbsent(sn, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(name);
        }
    }

    /** 过滤设备不认的参数名 */
    public java.util.Set<String> badParamsOf(String sn) {
        return badParamNames.getOrDefault(sn, java.util.Set.of());
    }

    /** 参数树缺私有节点时自动排一次全树探测（AdaptProbe：GPN 全树 → 会话引擎补 GPV） */
    private void maybeProbeTree(String sn, int paramCount) {
        if (ispProbeDone.contains(sn)) {
            return;
        }
        ispProbeDone.add(sn);
        if (paramCount >= FULL_TREE_MIN_PARAMS) {
            // 树已经采全还是没有私有节点：通用固件，认不出属正常
            Logs.info(Logs.Module.CPE, "参数树已采全且无私有节点，不再探测", "sn", sn, "参数数", paramCount);
            return;
        }
        if (store.countActiveTasks(sn, "AdaptProbe") > 0) {
            return;
        }
        store.insertTask(sn, "AdaptProbe", Map.of(), "system-isp-detect");
        Logs.info(Logs.Module.CPE, "参数树不完整，自动排全树探测", "sn", sn,
                "参数数", paramCount, "任务类型", "AdaptProbe");
    }

    /** Inform 后调用：按设备身份匹配模板并挂到会话 */
    public void attachModel(CwmpSession s) {
        try {
            Map<String, Object> device = store.findDevice(s.getSn());
            String isp = str(device.get("isp"));
            if (isp == null || isp.isBlank()) {
                // 台账还没写运营商：先用**会话内存里的参数树**识别（不依赖落库时序），再退到落库树
                String detected = detectIspFromPaths(s.getSn(), s.getData().all().keySet());
                if (detected == null) {
                    detected = detectIsp(s.getSn());
                }
                if (detected == null) {
                    probeInSession(s);
                    return;
                }
                isp = detected;
            }
            String brand = normalizeBrand(s.getManufacturer());
            String model = str(s.getProductClass());
            String version = str(s.getSoftwareVersion());
            Map<String, Object> row = null;
            for (String ispCode : IspVariant.candidateCodes(isp)) {
                row = store.findModelExact(brand, model, version, ispCode);
                if (row != null) {
                    break;
                }
            }
            if (row == null) {
                for (String ispCode : IspVariant.candidateCodes(isp)) {
                    row = store.findModelLoose(brand, model, ispCode);
                    if (row != null) {
                        break;
                    }
                }
            }
            if (row == null) {
                return;
            }
            s.setModelRow(row);
            s.setModelParams(store.loadModelParams(((Number) row.get("id")).longValue()));
        } catch (Exception e) {
            // 设备台账尚未落库等场景不阻断会话
            Logs.debug(Logs.Module.CPE, "机型模板匹配跳过: {}", e.getMessage());
        }
    }

    /** 适配入库：测试达标后把设备参数树整树存为（品牌+型号+版本+运营商）的模板 */
    public long saveTemplate(String sn, Map<String, Object> declared, Map<String, Object> detected) {
        Map<String, Object> device = store.findDevice(sn);
        String isp = IspVariant.canonical(str(declared.get("isp")));
        String brand = normalizeBrand(str(device.get("manufacturer")));
        String model = str(device.get("product_class"));
        String version = str(device.get("software_version"));

        Map<String, CpeParam> params = store.loadParams(sn);
        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("declared", declared);
        capability.put("detected", detected);
        capability.put("savedAt", System.currentTimeMillis());
        String capabilityJson;
        try {
            capabilityJson = JSON.writeValueAsString(capability);
        } catch (Exception e) {
            capabilityJson = "{}";
        }

        long id = store.saveModel(brand, model, version, isp, null,
                str(device.get("root_node")), capabilityJson, sn, params);
        store.updateDeviceColumns(sn, Map.of(
                "model_id", id, "adapted", 1, "adapted_from", sn, "isp", isp));
        Logs.info(Logs.Module.CPE, "机型适配入库", "sn", sn,
                "品牌", brand, "型号", model, "版本", version, "运营商", isp,
                "模板id", id, "参数数", params.size());
        return id;
    }

    /** /models/adapt-status：sn → 机型适配状态 */
    public Map<String, Object> adaptStatus(List<String> sns) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (sns == null) {
            return out;
        }
        for (String sn : sns) {
            try {
                Map<String, Object> device = store.findDevice(sn);
                out.put(sn, statusOf(device, store));
            } catch (Exception e) {
                // 设备不存在：未适配
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("adapted", false);
                out.put(sn, empty);
            }
        }
        return out;
    }

    private Map<String, Object> statusOf(Map<String, Object> device, CpeStore store) {
        Map<String, Object> st = new LinkedHashMap<>();
        int adapted = device.get("adapted") instanceof Number n ? n.intValue() : 0;
        st.put("productClass", device.get("product_class"));
        st.put("isp", device.get("isp"));
        st.put("ispText", IspText.of(str(device.get("isp"))));
        st.put("adapted", adapted == 1);
        Map<String, Object> model = null;
        Long modelId = device.get("model_id") instanceof Number n ? n.longValue() : null;
        if (modelId != null) {
            try {
                model = store.findModel(modelId);
            } catch (Exception ignore) {
                model = null;
            }
        }
        if (model == null && adapted == 1) {
            String isp = str(device.get("isp"));
            String brand = normalizeBrand(str(device.get("manufacturer")));
            for (String ispCode : IspVariant.candidateCodes(isp)) {
                model = store.findModelExact(brand,
                        str(device.get("product_class")),
                        str(device.get("software_version")), ispCode);
                if (model != null) {
                    break;
                }
            }
        }
        if (model != null) {
            st.put("adaptedFrom", model.get("source_sn"));
            st.put("templateLibId", model.get("id"));
            st.put("configPath", model.get("root_node"));
            st.put("capabilityText", capabilityText(model.get("capability_json")));
        } else {
            st.put("adaptedFrom", device.get("adapted_from"));
            st.put("templateLibId", null);
            st.put("configPath", device.get("root_node"));
            st.put("capabilityText", null);
        }
        return st;
    }

    /** capability_json 里的申报能力 → 展示文案（后端算好给前端） */
    public String capabilityText(Object capabilityJson) {
        if (!(capabilityJson instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> cap = JSON.readValue(s, Map.class);
            Map<String, Object> declared = (Map<String, Object>) cap.get("declared");
            if (declared == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            Object lanPorts = declared.get("lanPorts");
            if (lanPorts != null) {
                sb.append(lanPorts).append("网口");
            }
            Object speed = declared.get("lanSpeed");
            if (speed != null && !String.valueOf(speed).isBlank()) {
                sb.append('/').append(speed);
            }
            Object wifi = declared.get("wifiSpec");
            if (wifi != null && !String.valueOf(wifi).isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append('|');
                }
                sb.append(wifi);
            }
            Object sip = declared.get("sipCount");
            if (sip != null) {
                if (!sb.isEmpty()) {
                    sb.append('|');
                }
                sb.append(sip).append("路SIP");
            }
            return sb.isEmpty() ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 厂商名归一化（小写去空白，作模板品牌键） */
    public String normalizeBrand(String manufacturer) {
        return manufacturer == null ? "" : manufacturer.trim().toLowerCase();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 运营商编码 → 文案（委托 {@link IspVariant}） */
    public static final class IspText {
        private IspText() {
        }

        public static String of(String isp) {
            return IspVariant.text(isp);
        }
    }
}

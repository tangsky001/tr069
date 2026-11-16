/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.IspVariant;
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

/**
 * 机型模板库：品牌+型号+版本号+运营商 唯一绑定一份设备参数树。
 * <ul>
 *   <li>设备 Inform 时按 身份+运营商 匹配模板挂到会话，后续下发自动按模板组装</li>
 *   <li>能力测试达标后把实测参数树整树入库（adaptation/save）</li>
 * </ul>
 */
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

    /** 台账没写运营商时，从参数树私有节点识别一次并回填（已写的不再动） */
    public String detectIsp(String sn) {
        try {
            Map<String, Object> device = store.findDevice(sn);
            if (device == null) {
                return null;
            }
            String isp = str(device.get("isp"));
            if (isp != null && !isp.isBlank()) {
                return isp;
            }
            IspVariant hit = null;
            for (String path : store.loadParams(sn).keySet()) {
                hit = IspVariant.ofPath(path);
                if (hit != null) {
                    break;
                }
            }
            if (hit == null) {
                // 没认出来：把参数树里 X_ 开头的二段节点取样打出来，方便补前缀表
                Set<String> samples = new LinkedHashSet<>();
                Map<String, CpeParam> params = store.loadParams(sn);
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
                maybeProbeTree(sn, params.size());
                return null;
            }
            store.updateDeviceColumns(sn, Map.of("isp", hit.code()));
            Logs.info(Logs.Module.CPE, "按私有节点识别运营商", "sn", sn, "运营商", hit.code());
            return hit.code();
        } catch (Exception e) {
            Logs.debug(Logs.Module.CPE, "运营商识别跳过: {}", e.getMessage());
            return null;
        }
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
                // 台账还没写运营商：Inform 时也按存量参数树识别一次
                detectIsp(s.getSn());
                return;
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

    /**
     * 适配入库：测试达标后把设备参数树整树存为（品牌+型号+版本+运营商）的模板
     *
     * @param declared 前端申报 { isp, lanPorts, lanSpeed, sipCount, wifiSpec }
     * @return 模板 id
     */
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
            if (isp == null || isp.isBlank()) {
                return null;
            }
            IspVariant v = IspVariant.of(isp);
            return v == null ? isp : v.label();
        }
    }
}

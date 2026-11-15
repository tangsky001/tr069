/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.session.CwmpSession;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.log.Logs;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final CpeStore store;
    private final CapabilityDetector capabilityDetector;

    /** Inform 后调用：按设备身份匹配模板并挂到会话 */
    public void attachModel(CwmpSession s) {
        try {
            Map<String, Object> device = store.findDevice(s.getSn());
            String isp = str(device.get("isp"));
            if (isp == null || isp.isBlank()) {
                return;
            }
            String brand = normalizeBrand(s.getManufacturer());
            String model = str(s.getProductClass());
            String version = str(s.getSoftwareVersion());
            Map<String, Object> row = store.findModelExact(brand, model, version, isp);
            if (row == null) {
                row = store.findModelLoose(brand, model, isp);
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
        String isp = str(declared.get("isp"));
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
            model = isp == null ? null : store.findModelExact(
                    normalizeBrand(str(device.get("manufacturer"))),
                    str(device.get("product_class")),
                    str(device.get("software_version")), isp);
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

    /** 运营商编码 → 文案 */
    public static final class IspText {
        private IspText() {
        }

        public static String of(String isp) {
            if (isp == null) {
                return null;
            }
            return switch (isp) {
                case "CT" -> "电信";
                case "CU" -> "联通";
                case "CMCC" -> "移动";
                case "CBN" -> "广电";
                case "standard" -> "标准/通用";
                default -> isp;
            };
        }
    }
}

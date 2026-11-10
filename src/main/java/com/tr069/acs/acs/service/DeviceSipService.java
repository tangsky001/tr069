package com.tr069.acs.acs.service;

import com.tr069.acs.worklist.model.SipEdit;
import com.tr069.acs.worklist.service.ModelAdaptationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SIP 语音：可编辑的线路清单 + 按路下发 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceSipService {

    private final DeviceBusinessService businessService;
    private final ModelAdaptationService adaptationService;
    private final DirectDispatchService directDispatchService;

    /** 申报能力里的数字：{@code 1} / {@code 4-8} / {@code 无} → 8 / 1 / 0（取最大值） */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** 这台设备可编辑的语音线路清单 */
    public List<Map<String, Object>> lines(String sn) {
        Map<Integer, Map<String, Object>> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : sipRows(sn)) {
            Object lineNo = row.get("line");
            int line = lineNo instanceof Number number ? number.intValue() : 0;
            if (line <= 0 || actual.containsKey(line)) {
                continue;
            }
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("line", line);
            option.put("number", text(row.get("number")));
            option.put("statusText", text(row.get("statusText")));
            option.put("registered", Boolean.TRUE.equals(row.get("registered")));
            option.put("authUser", text(row.get("authUser")));
            option.put("password", text(row.get("password")));
            option.put("registrar", text(row.get("registrar")));
            option.put("registrarPort", text(row.get("registrarPort"), DeviceBusinessService.DEFAULT_SIP_PORT));
            option.put("proxy", text(row.get("proxy")));
            option.put("proxyPort", text(row.get("proxyPort"), DeviceBusinessService.DEFAULT_SIP_PORT));
            actual.put(line, option);
        }
        int max = actual.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        int total = Math.max(Math.max(declaredSipCount(sn), max), 1);
        List<Map<String, Object>> result = new ArrayList<>(total);
        for (int line = 1; line <= total; line++) {
            Map<String, Object> option = actual.get(line);
            if (option != null) {
                result.add(option);
                continue;
            }
            // 申报了但设备还没上报的一路：给一份空表单，端口按默认值填好
            Map<String, Object> blank = new LinkedHashMap<>();
            blank.put("line", line);
            blank.put("number", "");
            blank.put("statusText", "未配置");
            blank.put("registered", false);
            blank.put("authUser", "");
            blank.put("password", "");
            blank.put("registrar", "");
            blank.put("registrarPort", DeviceBusinessService.DEFAULT_SIP_PORT);
            blank.put("proxy", "");
            blank.put("proxyPort", DeviceBusinessService.DEFAULT_SIP_PORT);
            result.add(blank);
        }
        return result;
    }

    /** 按路下发 SIP 配置：一路一张工单 */
    public Map<String, Object> submit(String sn, SipEdit edit, String operator) {
        if (edit == null) {
            throw new IllegalArgumentException("缺少下发参数");
        }
        edit.setSn(sn);
        String invalid = edit.validate();
        if (invalid != null) {
            throw new IllegalArgumentException(invalid);
        }
        String user = operator != null && !operator.isBlank() ? operator : edit.getUsername();
        List<Map<String, Object>> submitted = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Integer line : edit.normalizedLines()) {
            // 直接下发：受理即发，不建工单；单路失败记录原因后继续其余线路
            Map<String, Object> row = directDispatchService.dispatchOne(
                    sn, SipEdit.CATEGORY, null, edit.toValues(line), user);
            if (row.get("errorMsg") != null) {
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("line", line);
                fail.put("message", row.get("errorMsg"));
                failures.add(fail);
                log.warn("SIP 下发失败 sn={} line={}: {}", sn, line, row.get("errorMsg"));
                continue;
            }
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("line", line);
            ok.put("taskId", row.get("taskId"));
            ok.put("status", row.get("status"));
            submitted.add(ok);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lines", edit.normalizedLines());
        result.put("submitted", submitted.size());
        result.put("failed", failures.size());
        result.put("tasks", submitted);
        result.put("failures", failures);
        return result;
    }

    /** 业务快照里的 SIP 线路行；接口异常时按空处理，页面照样能列出申报的路数 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sipRows(String sn) {
        try {
            Object sip = businessService.summary(sn).get("sip");
            return sip instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        } catch (Exception e) {
            log.warn("读取 SIP 业务快照失败 sn={}: {}", sn, e.getMessage());
            return List.of();
        }
    }

    /** 适配申报的语音路数：{@code 4-8} 取 8，{@code 无} 取 0，取不到也按 0 */
    private int declaredSipCount(String sn) {
        try {
            Object raw = adaptationService.status(sn).get("capSipCount");
            if (raw == null) {
                return 0;
            }
            Matcher m = DIGITS.matcher(String.valueOf(raw));
            int max = 0;
            while (m.find()) {
                max = Math.max(max, Integer.parseInt(m.group()));
            }
            return max;
        } catch (Exception e) {
            log.warn("读取申报语音路数失败 sn={}: {}", sn, e.getMessage());
            return 0;
        }
    }

    private static String text(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String value = String.valueOf(candidate).trim();
            if (!value.isEmpty() && !"-".equals(value)) {
                return value;
            }
        }
        return "";
    }
}

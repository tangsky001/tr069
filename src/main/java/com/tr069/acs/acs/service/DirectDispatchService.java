package com.tr069.acs.acs.service;

import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.exec.IntentDeliveryService;
import com.tr069.acs.exec.entity.DeviceTask;
import com.tr069.acs.exec.model.DeliveryRequest;
import com.tr069.acs.template.service.ConfigTemplateService;
import com.tr069.acs.worklist.service.DeviceIdentifierResolver;
import com.tr069.acs.worklist.tpl.TemplateVariables;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** 设备页直接下发通道：受理即发，不建工单，进度走下发任务本身 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectDispatchService {

    /** 直接下发在下发任务 source 上的标记，区别于工单（worklist）与整包（biz） */
    public static final String SOURCE = "dispatch";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final IntentDeliveryService intentDeliveryService;
    private final ConfigTemplateService configTemplateService;
    private final DeviceIdentifierResolver identifierResolver;

    /** 任务号自增段：同一秒内多台 / 多路也不会撞号 */
    private final AtomicInteger seq = new AtomicInteger(0);

    /**
     * 单台直接下发。
     *
     * 支持 SN / OUI-SN / MAC / LOID 标识；变量按 {@link TemplateVariables} 兜底
     * （房间号取台账），模板不传则按业务类型取第一条可用配置模板。
     * 返回行：sn / taskId / status / errorMsg，失败不抛异常，由调用方汇总。
     */
    public Map<String, Object> dispatchOne(String id, String category, Long templateId,
                                           Map<String, String> values, String username) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sn", id);
        try {
            CpeDevice device = identifierResolver.resolve(id);
            if (device == null) {
                row.put("status", DeviceTask.STATUS_REJECTED);
                row.put("errorMsg", "设备不存在，请确认已注册");
                return row;
            }
            row.put("sn", device.getSn());
            Map<String, Object> raw = new HashMap<>(values == null ? Map.of() : values);
            Map<String, Object> vars = new HashMap<>();
            TemplateVariables.applyDefaults(category, raw, device.getRoomNo())
                    .forEach((k, v) -> vars.put(k, v == null ? null : String.valueOf(v)));
            String taskNo = String.format(Locale.ROOT, "DSP-%s-%d-%03d",
                    LocalDateTime.now().format(STAMP), seq.incrementAndGet(),
                    ThreadLocalRandom.current().nextInt(1000));
            DeviceTask task = intentDeliveryService.deliver(new DeliveryRequest(
                    taskNo, device.getSn(), category, defaultTemplateId(templateId, category),
                    null, vars, true, SOURCE, username));
            row.put("taskId", task.getId());
            row.put("status", task.getStatus());
            if (DeviceTask.STATUS_REJECTED.equals(task.getStatus())) {
                row.put("errorMsg", task.getErrorMsg());
            }
        } catch (Exception e) {
            row.put("status", "fail");
            row.put("errorMsg", e.getMessage());
        }
        return row;
    }

    /** 按业务类型取第一条可用配置模板，找不到就交给下发侧自行推导 */
    private Long defaultTemplateId(Long templateId, String category) {
        if (templateId != null) {
            return templateId;
        }
        try {
            List<Map<String, Object>> templates = configTemplateService.list(category, null);
            if (templates.isEmpty()) {
                return null;
            }
            Object id = templates.get(0).get("id");
            return id == null ? null : Long.valueOf(String.valueOf(id));
        } catch (Exception e) {
            log.warn("按业务类型查找模板失败: category={}, err={}", category, e.getMessage());
            return null;
        }
    }
}

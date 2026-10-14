package com.tr069.acs.exec.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.exec.entity.DeviceTask;
import com.tr069.acs.exec.mapper.DeviceTaskMapper;
import com.tr069.acs.exec.model.DeliveryRequest;
import com.tr069.acs.template.service.ConfigTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 北向下发请求的受理封装：把外部 JSON 收敛成 {@link DeliveryRequest}，
 * 并记录调用者，保证任务表里的 {@code createdBy} 能追到具体账号。
 *
 * <p>鉴权已在拦截器里做过，这里从 {@code USERNAME_ATTR} 取账号。
 * 缺少必填字段时抛出的消息会原样回到调用方，所以文案写清楚。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiDeliveryService {

    private final DeviceTaskMapper taskMapper;

    private final ConfigTemplateService templateService;

    /**
     * 解析下发请求。
     */
    public DeliveryRequest parse(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String sn = text(body.get("sn"));
        String taskNo = text(body.get("taskNo"));
        String category = text(body.get("category"));
        if (sn == null) {
            throw new IllegalArgumentException("缺少设备序列号 sn");
        }
        if (taskNo == null) {
            throw new IllegalArgumentException("缺少外部业务单号 taskNo");
        }
        if (category == null) {
            throw new IllegalArgumentException("缺少业务类型 category");
        }
        Long templateId = body.get("templateId") == null ? null
                : Long.parseLong(String.valueOf(body.get("templateId")).trim());
        Map<String, Object> values = new LinkedHashMap<>();
        Object rawValues = body.get("values");
        if (rawValues instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    values.put(String.valueOf(k), v);
                }
            });
        }
        boolean createInstance = Boolean.parseBoolean(String.valueOf(body.get("createInstance")));
        return new DeliveryRequest(taskNo, sn, category, templateId, text(body.get("bizObject")),
                values, createInstance, DeviceTask.SOURCE_OPENAPI, currentUser());
    }

    /**
     * 可选的业务清单，供调用方挑选模板。
     */
    public List<Map<String, Object>> templates() {
        return templateService.list(null, null);
    }

    /**
     * 任务列表：按设备或状态过滤，最新的在前。
     */
    public List<Map<String, Object>> list(String sn, String status, int limit) {
        LambdaQueryWrapper<DeviceTask> query = new LambdaQueryWrapper<DeviceTask>()
                .orderByDesc(DeviceTask::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        if (sn != null && !sn.isBlank()) {
            query.eq(DeviceTask::getSn, sn.trim());
        }
        if (status != null && !status.isBlank()) {
            query.eq(DeviceTask::getStatus, status.trim().toUpperCase());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (DeviceTask task : taskMapper.selectList(query)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", task.getId());
            view.put("taskNo", task.getTaskNo());
            view.put("sn", task.getSn());
            view.put("category", task.getCategory());
            view.put("status", task.getStatus());
            view.put("errorMsg", task.getErrorMsg());
            view.put("createdAt", task.getCreatedAt());
            view.put("finishedAt", task.getFinishedAt());
            items.add(view);
        }
        return items;
    }

    private String currentUser() {
        org.springframework.web.context.request.ServletRequestAttributes attrs =
                (org.springframework.web.context.request.ServletRequestAttributes)
                        org.springframework.web.context.request.RequestContextHolder
                                .getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        Object username = attrs.getRequest()
                .getAttribute(com.tr069.acs.config.AdminAuthInterceptor.USERNAME_ATTR);
        return username == null ? null : String.valueOf(username);
    }

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
}

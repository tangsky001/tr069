package com.tr069.acs.openapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.openapi.entity.WebhookDelivery;
import com.tr069.acs.openapi.entity.WebhookSubscription;
import com.tr069.acs.openapi.mapper.WebhookDeliveryMapper;
import com.tr069.acs.openapi.mapper.WebhookSubscriptionMapper;
import com.tr069.acs.openapi.model.NorthboundEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 北向事件推送。
 *
 * <p>事件 NB 推送两步走，避免任何一个环节拖住业务主流程：</p>
 * <ol>
 *   <li>事件到达时先给关心的每个订阅插一条投递记录（落库即算受理）；</li>
 *   <li>后台线程按计划重发，成功或重试次数用尽结束。</li>
 * </ol>
 *
 * <p>推送带 {@code X-ACS-Signature}：订阅侧用同一份 secret 对 body 做 HMAC-SHA256 比对，
 * 确认请求确实来自 ACS 而不是别的来源。密钥由订阅配置时的 {@code secret} 字段给出。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    /** 最大推送尝试次数，超过标记为 FAILED。 */
    private static final int MAX_ATTEMPTS = 5;

    /** 重试退避基数：第 n 次重试等待 base * 2^(n-1) 秒。 */
    private static final long BASE_BACKOFF_SECONDS = 30;

    private final WebhookSubscriptionMapper subscriptionMapper;

    private final WebhookDeliveryMapper deliveryMapper;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** 单次推送的超时（秒）。 */
    @Value("${acs.openapi.webhook.timeout-seconds:10}")
    private long webhookTimeoutSeconds;

    /** 单次推送最多重试多少次，由配置覆盖 {@link #MAX_ATTEMPTS} 的缺省值。 */
    @Value("${acs.openapi.webhook.max-attempts:5}")
    private int maxAttempts;

    // ==================== 订阅管理 ====================

    public List<WebhookSubscription> subscriptions() {
        return subscriptionMapper.selectList(
                new LambdaQueryWrapper<WebhookSubscription>()
                        .orderByDesc(WebhookSubscription::getId));
    }

    public WebhookSubscription save(WebhookSubscription subscription) {
        if (subscription == null) {
            throw new IllegalArgumentException("订阅内容不能为空");
        }
        if (subscription.getUrl() == null || subscription.getUrl().isBlank()) {
            throw new IllegalArgumentException("回调地址不能为空");
        }
        if (!subscription.getUrl().startsWith("http://")
                && !subscription.getUrl().startsWith("https://")) {
            throw new IllegalArgumentException("回调地址必须是 http/https 开头: " + subscription.getUrl());
        }
        if (subscription.getEventTypes() == null || subscription.getEventTypes().isBlank()) {
            subscription.setEventTypes(WebhookSubscription.ALL_EVENTS);
        }
        if (subscription.getEnabled() == null) {
            subscription.setEnabled(1);
        }
        LocalDateTime now = LocalDateTime.now();
        if (subscription.getId() == null) {
            subscription.setCreatedAt(now);
            subscription.setUpdatedAt(now);
            subscriptionMapper.insert(subscription);
        } else {
            subscription.setUpdatedAt(now);
            subscriptionMapper.updateById(subscription);
        }
        return subscription;
    }

    public void delete(Long id) {
        subscriptionMapper.deleteById(id);
    }

    /**
     * 某个订阅最近的投递记录，便于对接方自查「为什么没收到」。
     */
    public List<WebhookDelivery> deliveries(Long webhookId, int limit) {
        LambdaQueryWrapper<WebhookDelivery> query = new LambdaQueryWrapper<WebhookDelivery>()
                .orderByDesc(WebhookDelivery::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        if (webhookId != null) {
            query.eq(WebhookDelivery::getWebhookId, webhookId);
        }
        return deliveryMapper.selectList(query);
    }

    // ==================== 事件受理 ====================

    /**
     * 事件入口：给每个关心的订阅插一条投递记录，然后立刻尝试推送一次。
     *
     * <p>所有事件（含 {@code TaskFinishedEvent} 这类 Spring 事件）都先转成
     * {@link NorthboundEvent}，再走这里。</p>
     */
    public void publish(NorthboundEvent event) {
        List<WebhookSubscription> targets = subscriptions();
        if (targets.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadOf(event));
        } catch (Exception e) {
            log.error("事件序列化失败，跳过推送: {}", e.getMessage());
            return;
        }
        for (WebhookSubscription subscription : targets) {
            if (!matches(subscription, event.eventType())) {
                continue;
            }
            WebhookDelivery existing = deliveryMapper.selectOne(
                    new LambdaQueryWrapper<WebhookDelivery>()
                            .eq(WebhookDelivery::getWebhookId, subscription.getId())
                            .eq(WebhookDelivery::getEventId, event.eventId())
                            .last("LIMIT 1"));
            if (existing != null) {
                continue;
            }
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setWebhookId(subscription.getId());
            delivery.setEventId(event.eventId());
            delivery.setEventType(event.eventType());
            delivery.setPayload(payload);
            delivery.setStatus(WebhookDelivery.STATUS_PENDING);
            delivery.setAttempts(0);
            delivery.setCreatedAt(LocalDateTime.now());
            delivery.setUpdatedAt(LocalDateTime.now());
            deliveryMapper.insert(delivery);
            send(subscription, delivery);
        }
    }

    /**
     * 下发任务结束：转成北向事件推给订阅方。
     */
    @EventListener
    public void onTaskFinished(com.tr069.acs.exec.TaskFinishedEvent appEvent) {
        if (appEvent.getTask() == null) {
            return;
        }
        publish(NorthboundEvent.of(NorthboundEvent.DELIVERY_FINISHED, taskData(appEvent.getTask())));
    }

    /**
     * 设备上下线事件：由 {@link com.tr069.acs.openapi.watch.DeviceStatusWatcher} 侦测后转入这里。
     */
    public void publishDeviceState(String sn, boolean online) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sn", sn);
        data.put("online", online);
        data.put("occurredAt", LocalDateTime.now().toString());
        publish(NorthboundEvent.of(online ? NorthboundEvent.DEVICE_ONLINE
                : NorthboundEvent.DEVICE_OFFLINE, data));
    }

    private Map<String, Object> taskData(com.tr069.acs.exec.entity.DeviceTask task) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", task.getId());
        data.put("taskNo", task.getTaskNo());
        data.put("sn", task.getSn());
        data.put("category", task.getCategory());
        data.put("status", task.getStatus());
        data.put("errorMsg", task.getErrorMsg());
        data.put("source", task.getSource());
        data.put("finishedAt", task.getFinishedAt() == null ? null : task.getFinishedAt().toString());
        return data;
    }

    private Map<String, Object> payloadOf(NorthboundEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.eventId());
        body.put("eventType", event.eventType());
        body.put("occurredAt", event.occurredAt() == null ? null : event.occurredAt().toString());
        body.put("data", event.data());
        return body;
    }

    private boolean matches(WebhookSubscription subscription, String eventType) {
        if (subscription.getEnabled() == null || subscription.getEnabled() != 1) {
            return false;
        }
        String wanted = subscription.getEventTypes();
        if (wanted == null || wanted.isBlank() || WebhookSubscription.ALL_EVENTS.equals(wanted.trim())) {
            return true;
        }
        for (String part : wanted.split("[,;\\s]+")) {
            if (part.trim().equalsIgnoreCase(eventType)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 推送与重试 ====================

    /**
     * 重试未成功的投递：按 next_retry_at 到点重发。
     *
     * <p>查表这一段单独兜底：自动建表被关掉（acs.openapi.auto-create-table=false）或表被误删时，
     * 没有兜底就是每 30 秒一条完整的堆栈，日志当天就能刷到几百兆，真正的推送错误反而被淹掉。</p>
     */
    @Scheduled(fixedDelayString = "${acs.openapi.webhook.retry-interval-millis:30000}")
    public void retryPending() {
        LocalDateTime now = LocalDateTime.now();
        List<WebhookDelivery> pending;
        try {
            pending = deliveryMapper.selectList(
                    new LambdaQueryWrapper<WebhookDelivery>()
                            .eq(WebhookDelivery::getStatus, WebhookDelivery.STATUS_PENDING)
                            .le(WebhookDelivery::getNextRetryAt, now)
                            .last("LIMIT 100"));
        } catch (DataAccessException e) {
            log.warn("读取待重试的推送记录失败，跳过本轮: {}；若关闭了自动建表请手工执行 "
                    + "classpath:db/schema.sql", e.getMessage());
            return;
        }
        for (WebhookDelivery delivery : pending) {
            WebhookSubscription subscription = subscriptionMapper.selectById(delivery.getWebhookId());
            if (subscription == null) {
                delivery.setStatus(WebhookDelivery.STATUS_FAILED);
                delivery.setResponseBody("订阅已删除");
                delivery.setUpdatedAt(LocalDateTime.now());
                deliveryMapper.updateById(delivery);
                continue;
            }
            send(subscription, delivery);
        }
    }

    private void send(WebhookSubscription subscription, WebhookDelivery delivery) {
        int attempts = delivery.getAttempts() == null ? 0 : delivery.getAttempts();
        String body = delivery.getPayload() == null ? "{}" : delivery.getPayload();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(subscription.getUrl()))
                    .timeout(Duration.ofSeconds(Math.max(webhookTimeoutSeconds, 1)))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("X-ACS-Event-Id", delivery.getEventId())
                    .header("X-ACS-Event-Type", delivery.getEventType())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (subscription.getSecret() != null && !subscription.getSecret().isBlank()) {
                builder.header("X-ACS-Signature", sign(body, subscription.getSecret()));
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            delivery.setAttempts(attempts + 1);
            delivery.setResponseCode(code);
            delivery.setResponseBody(truncate(response.body()));
            if (code >= 200 && code < 300) {
                delivery.setStatus(WebhookDelivery.STATUS_SUCCESS);
                delivery.setNextRetryAt(null);
            } else {
                scheduleRetry(delivery, delivery.getAttempts());
            }
            delivery.setUpdatedAt(LocalDateTime.now());
            deliveryMapper.updateById(delivery);
        } catch (Exception e) {
            delivery.setAttempts(attempts + 1);
            delivery.setResponseBody(truncate(e.getMessage()));
            scheduleRetry(delivery, delivery.getAttempts());
            delivery.setUpdatedAt(LocalDateTime.now());
            deliveryMapper.updateById(delivery);
            log.warn("北向事件推送失败: event={}, url={}, err={}", delivery.getEventType(),
                    subscription.getUrl(), e.getMessage());
        }
    }

    private void scheduleRetry(WebhookDelivery delivery, int attempts) {
        int limit = maxAttempts > 0 ? maxAttempts : MAX_ATTEMPTS;
        if (attempts >= limit) {
            delivery.setStatus(WebhookDelivery.STATUS_FAILED);
            delivery.setNextRetryAt(null);
            return;
        }
        delivery.setStatus(WebhookDelivery.STATUS_PENDING);
        delivery.setNextRetryAt(LocalDateTime.now()
                .plusSeconds(BASE_BACKOFF_SECONDS * (1L << Math.min(attempts, 4))));
    }

    /**
     * HMAC-SHA256 签名，订阅方可对 body 重算比对。
     */
    public static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}

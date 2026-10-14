package com.tr069.acs.openapi.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回调签名。
 *
 * <p>接收方看 ACS 推来的请求是否可信，靠的就是这个签名：
 * 同一份 body 与 secret 必须算出同一个值，不同 body 不能撞。</p>
 */
class WebhookSignatureTest {

    private static final String SECRET = "acs-webhook-secret";

    @Test
    void signsSameBodyToSameValue() {
        String body = "{\"eventId\":\"device.online:1\",\"data\":{\"sn\":\"ZXHN0001\"}}";

        assertThat(WebhookService.sign(body, SECRET))
                .isEqualTo(WebhookService.sign(body, SECRET))
                .isNotBlank();
    }

    @Test
    void differsForDifferentBodyOrSecret() {
        String body = "{\"eventId\":\"delivery.finished:1\"}";

        assertThat(WebhookService.sign(body, SECRET))
                .isNotEqualTo(WebhookService.sign(body + " ", SECRET))
                .isNotEqualTo(WebhookService.sign(body, "another-secret"));
    }
}

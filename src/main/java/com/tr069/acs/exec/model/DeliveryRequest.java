package com.tr069.acs.exec.model;

import java.util.Map;

/** 一次下发请求 */
public record DeliveryRequest(String taskNo, String sn, String category, Long templateId,
                              String bizObject, Map<String, Object> values,
                              boolean createInstance, String source, String createdBy) {
}

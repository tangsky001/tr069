package com.tr069.acs.exec.model;

import java.util.Map;

/**
 * 一次下发请求。
 *
 * <p>北向与管理端都用它，差别只在 {@code source} 与 {@code createdBy}。</p>
 *
 * @param taskNo        外部业务单号，同 source 唯一，重复提交返回既有任务，不会重复下发
 * @param sn            设备序列号
 * @param category      业务类型：WAN_DHCP / WAN_PPPOE / WAN_STATIC / WAN_BRIDGE /
 *                      WIFI_24G / WIFI_58G / SIP
 * @param templateId    意图模板 id，取模板里的默认意图字段
 * @param bizObject     业务对象，不传则按 category 推导
 * @param values        业务字段值，键为 model_param_mapping.biz_field
 * @param createInstance 是否新建实例（AddObject），默认复用已有实例号
 * @param source        来源
 * @param createdBy     发起方账号
 */
public record DeliveryRequest(String taskNo, String sn, String category, Long templateId,
                              String bizObject, Map<String, Object> values,
                              boolean createInstance, String source, String createdBy) {
}

package com.tr069.acs.acs.service;

import com.tr069.acs.domain.entity.SoapMessage;
import com.tr069.acs.mapper.SoapMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 设备故障报文留证：把带回 fault 的原始报文落到 soap_message 表 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SoapTraceService {

    private final SoapMessageMapper soapMessageMapper;

    /** 故障报文留证开关 */
    @Value("${acs.soap-message.fault-trace-enabled:true}")
    private boolean faultTraceEnabled;

    /** 单条留证的报文长度上限（字符），超出截断，防大报文撑爆 MEDIUMTEXT */
    @Value("${acs.soap-message.max-chars:60000}")
    private int maxChars;

    /** 记一条故障报文；留证失败不影响主链路 */
    public void recordFault(String sn, long rpcId, String msgType, String rawXml) {
        if (!faultTraceEnabled || rawXml == null || rawXml.isBlank()) {
            return;
        }
        try {
            SoapMessage record = new SoapMessage();
            record.setRpcId(rpcId > 0 ? rpcId : null);
            record.setSn(sn == null ? "unknown" : sn);
            record.setMsgType(msgType);
            record.setDirection("in");
            record.setContentBody(rawXml.length() > maxChars ? rawXml.substring(0, maxChars) : rawXml);
            soapMessageMapper.insert(record);
        } catch (Exception e) {
            log.warn("故障报文留证失败: sn={}, err={}", sn, e.getMessage());
        }
    }
}

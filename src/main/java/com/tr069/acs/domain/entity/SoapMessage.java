package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SOAP 报文日志。
 *
 * <p>对应 Python 版 SOAP 与 SOAP_EX 的合并。</p>
 */
@Data
@TableName("soap_message")
public class SoapMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long rpcId;

    private String sn;

    /**
     * 报文类型：Inform / InformResponse / GetParameterValues ...
     */
    private String msgType;

    /**
     * 方向：in / out
     */
    private String direction;

    private String eventCode;

    private String contentBody;

    private LocalDateTime createdAt;
}

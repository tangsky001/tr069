package com.tr069.acs.common.exception;

/**
 * CWMP 报文构造或解析异常。
 */
public class SoapException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SoapException(String message) {
        super(message);
    }

    public SoapException(String message, Throwable cause) {
        super(message, cause);
    }
}

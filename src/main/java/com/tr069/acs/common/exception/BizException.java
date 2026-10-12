package com.tr069.acs.common.exception;

import lombok.Getter;

/**
 * 管理端业务异常。
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = 50000;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
        this.code = 50000;
    }
}

/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.852
 *
 */

package com.PilotACS.common.exception;

import lombok.Getter;

// 业务异常
@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(String msg) {
        this(500, msg);
    }

    public BusinessException(int code, String msg) {
        super(msg);
        this.code = code;
    }
}

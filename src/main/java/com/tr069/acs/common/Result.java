package com.tr069.acs.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理端统一响应体。*/
@Data
public class Result<T> implements Serializable {

    /** 业务成功码 */
    public static final int SUCCESS_CODE = 200;

    /** 通用业务失败码：参数不合法、设备拒绝、命令算不出来等 */
    public static final int ERROR_CODE = 500;

    /** 令牌失效或未带令牌，与 HTTP 401 同步返回 */
    public static final int UNAUTHORIZED_CODE = 401;

    /** 登录了但没有这个权限点，与 HTTP 403 同步返回 */
    public static final int FORBIDDEN_CODE = 403;

    private static final long serialVersionUID = 1L;

    private int code;

    private String message;

    private T data;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功响应的默认提示语：返回给前端直接展示，用中文。 */
    public static final String SUCCESS_MESSAGE = "成功";

    public static <T> Result<T> success() {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(SUCCESS_CODE, message, data);
    }

    public static <T> Result<T> failure(String message) {
        return new Result<>(ERROR_CODE, message, null);
    }

    public static <T> Result<T> failure(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** 令牌失效 / 未带令牌，业务码 {@link #UNAUTHORIZED_CODE}，通常配 HTTP 401 一起返回。 */
    public static <T> Result<T> unauthorized(String message) {
        return new Result<>(UNAUTHORIZED_CODE, message, null);
    }

    /** 没有权限，业务码 {@link #FORBIDDEN_CODE}，通常配 HTTP 403 一起返回。 */
    public static <T> Result<T> forbidden(String message) {
        return new Result<>(FORBIDDEN_CODE, message, null);
    }

    public boolean isSuccess() {
        return code == SUCCESS_CODE;
    }
}

package com.tr069.acs.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理端统一响应体。
 *
 * <p>字段与状态码严格对齐 vue-admin-template 的约定：
 * 前端响应式拦截器判定 {@code code === 200} 为成功，错误消息取自 {@code message}。</p>
 *
 * @see <a href="javaacs/web/src/utils/request.js">request.js 响应拦截器</a>
 */
@Data
public class Result<T> implements Serializable {

    /**
     * 业务成功码，vue-admin-template 约定值
     */
    public static final int SUCCESS_CODE = 20000;

    /**
     * 通用失败码
     */
    public static final int ERROR_CODE = 50000;

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

    public static <T> Result<T> success() {
        return new Result<>(SUCCESS_CODE, "success", null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, "success", data);
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

    public boolean isSuccess() {
        return code == SUCCESS_CODE;
    }
}

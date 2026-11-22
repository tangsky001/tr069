/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.979
 *
 */

package com.PilotACS.common.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 统一响应体 */
@Data
@Schema(description = "统一响应体")
public class Result<T> {
    public static final int SUCCESS = 200;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int ERROR = 500;

    @Schema(description = "状态码：200成功，401未登录，403无权限，500失败", example = "200")
    private int code;
    @Schema(description = "提示信息", example = "成功")
    private String message;
    @Schema(description = "业务数据，无数据时为 null")
    private T data;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = SUCCESS;
        r.message = "成功";
        r.data = data;
        return r;
    }

    public static <T> Result<T> fail(String msg) {
        return fail(ERROR, msg);
    }

    public static <T> Result<T> fail(int code, String msg) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = msg;
        return r;
    }
}

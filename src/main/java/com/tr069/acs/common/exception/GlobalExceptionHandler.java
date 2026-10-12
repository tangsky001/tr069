package com.tr069.acs.common.exception;

import com.tr069.acs.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * <p>统一转换为 vue-admin-template 约定的响应结构。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.failure(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(SoapException.class)
    public Result<Void> handleSoap(SoapException e) {
        log.warn("CWMP 报文异常: {}", e.getMessage());
        return Result.failure("CWMP 报文处理失败: " + e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败"
                : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return Result.failure(message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        return Result.failure(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("服务异常", e);
        return Result.failure("服务异常: " + e.getMessage());
    }
}

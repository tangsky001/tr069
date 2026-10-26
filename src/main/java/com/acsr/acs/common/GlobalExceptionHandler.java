package com.acsr.acs.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常处理：REST 接口统一返回 Result。 */
@Slf4j
@RestControllerAdvice(basePackages = "com.acsr.acs.web")
public class GlobalExceptionHandler {

  @ExceptionHandler(BizException.class)
  public Result<?> handleBiz(BizException e) {
    return Result.fail(e.getCode(), e.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public Result<?> handleValid(MethodArgumentNotValidException e) {
    String msg = e.getBindingResult().getFieldErrors().stream()
        .findFirst()
        .map(f -> f.getField() + " " + f.getDefaultMessage())
        .orElse("参数校验失败");
    return Result.fail(400, msg);
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.OK)
  public Result<?> handleOther(Exception e) {
    log.error("REST 处理异常", e);
    return Result.fail(e.getMessage() == null ? "服务器内部错误" : e.getMessage());
  }
}

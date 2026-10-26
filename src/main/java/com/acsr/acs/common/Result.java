package com.acsr.acs.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 统一 REST 返回结构。 */
@Data
@NoArgsConstructor
public class Result<T> implements Serializable {

  private int code;
  private String message;
  private T data;

  private Result(int code, String message, T data) {
    this.code = code;
    this.message = message;
    this.data = data;
  }

  public static <T> Result<T> ok() {
    return new Result<>(0, "success", null);
  }

  public static <T> Result<T> ok(T data) {
    return new Result<>(0, "success", data);
  }

  public static <T> Result<T> fail(String message) {
    return new Result<>(500, message, null);
  }

  public static <T> Result<T> fail(int code, String message) {
    return new Result<>(code, message, null);
  }

  public boolean isSuccess() {
    return code == 0;
  }
}

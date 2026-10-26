package com.acsr.acs.adapt;

import lombok.Data;

/** SQLite：已适配参数映射（取值对照）。 */
@Data
public class AdaptParamMap {

  private Long id;
  private Long templateId;
  /** Inform 参数列表里对应的观测路径 */
  private String informPath;
  /** GetParameterValues 取值路径 */
  private String getPath;
  /** SetParameterValues 写入路径 */
  private String setPath;
  /** string/int/boolean/base64 */
  private String valueType;
  /** 取值/写值转换表达式 */
  private String transform;
}

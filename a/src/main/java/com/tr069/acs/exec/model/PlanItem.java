package com.tr069.acs.exec.model;

/** 一条待写入的参数 */
public record PlanItem(String bizObject, String bizField, String path, String value,
                       String valueType) {
}

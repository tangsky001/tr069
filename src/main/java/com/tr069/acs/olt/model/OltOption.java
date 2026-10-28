package com.tr069.acs.olt.model;

/**
 * 下拉框的一个选项。
 *
 * @param code  提交给后端的值（如 {@code HSGQ}）
 * @param label 页面上显示的文字（如 {@code 鸿升光 EPON}）
 */
public record OltOption(String code, String label) {
}

package com.tr069.acs.olt.model;

/**
 * TR-069（ACS）模板表单的默认值和取值约束，建模板的两个入口（一键开局、「TR069模板」
 * 页签的「增加」）共用同一份，不会出现两处默认值不一致。
 *
 * @param supported    这台设备有没有接入 TR-069 模板命令
 * @param defaults     表单默认值
 * @param profileIdMin 模板 ID 下限
 * @param profileIdMax 模板 ID 上限
 * @param portMin      连接端口下限
 * @param portMax      连接端口上限
 * @param nameMax      模板名最大长度
 * @param urlMax       ACS 地址最大长度
 * @param fieldMax     账号/密码这类字段的最大长度
 * @param urlPattern   ACS 地址的正则（页面直接拿来校验，规则只有一份）
 */
public record OltTr069Form(boolean supported, OltTr069ProfileRequest defaults,
                           int profileIdMin, int profileIdMax, int portMin, int portMax,
                           int nameMax, int urlMax, int fieldMax, String urlPattern) {
}

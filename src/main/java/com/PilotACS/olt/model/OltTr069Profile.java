/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.862
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/** TR069 服务器模板 */
@Data
public class OltTr069Profile {

    private Integer profileId;

    private String name;

    @Schema(description = "ACS 地址")
    private String url;

    @Schema(description = "ACS 登录账号（密码是密文，不展示也不采集）")
    private String user;

    @Schema(description = "已绑定这个模板的 ONU 数（鸿升光回显的 BindTimes，别的厂商不给就是 null）")
    private Integer bindTimes;
}

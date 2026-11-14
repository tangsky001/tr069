/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.700
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltVlanPort {

    @Schema(description = "端口类型，与命令里的关键字一致：epon / gpon / ge / xge")
    private String type;

    @Schema(description = "端口号，从 1 开始")
    private Integer no;

    @Schema(description = "页面上显示的名字：PON01 / GE03 / XGE01")
    private String label;

    public OltVlanPort() {
    }

    public OltVlanPort(String type, Integer no) {
        this.type = type;
        this.no = no;
        this.label = label(type, no);
    }

    public static String label(String type, Integer no) {
        String prefix = switch (type == null ? "" : type.toLowerCase()) {
            case "epon", "gpon" -> "PON";
            case "xge" -> "XGE";
            default -> "GE";
        };
        return prefix + String.format("%02d", no == null ? 0 : no);
    }
}

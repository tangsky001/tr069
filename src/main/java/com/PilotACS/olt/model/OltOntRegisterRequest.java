/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.871
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** ONT 注册请求 */
@Data
public class OltOntRegisterRequest {

    @Schema(description = "手动注册时指定的模板 ID；留空表示按优先级自动匹配")
    private Long templateId;

    @Schema(description = "要注册的 ONT；留空表示注册设备上当前所有待注册的")
    private List<Row> rows = new ArrayList<>();

    @Schema(description = "true 时只编命令不下发，给页面预览用")
    private boolean preview;

    /** 待注册的 ONT 行 */
    @Data
    public static class Row {

        @Schema(description = "display ont autofind 回显里的 Number，ont confirm 用的就是它")
        private Integer id;

        @Schema(description = "框/槽/端口，如 0/2/0")
        private String fsp;

        @Schema(description = "SN / MAC / LOID，按模板的注册方式取对应那个值")
        private String sn;

        private String loid;

        private String password;

        private String model;

        private String vendorId;

        private String ponType;
    }
}

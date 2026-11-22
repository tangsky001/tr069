/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.148
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** ONT 业务（能力）模板 */
@Data
public class OltSrvProfile {

    @Schema(description = "Profile-ID")
    private Integer profileId;

    @Schema(description = "Profile-name")
    private String name;

    @Schema(description = "PON 类型：gpon / epon（命令里的类型关键字跟着 PON 板走，采集时探测填入）")
    private String ponType;

    @Schema(description = "网口数量：ont-port eth adaptive 8 里的上限，”自适应,最大 8” 就是这么来的")
    private Integer ethPorts;

    @Schema(description = "电话口（POTS）数量上限：ont-port pots adaptive 32")
    private Integer potsPorts;

    @Schema(description = "能力项，按回显顺序保留")
    private Map<String, String> items = new LinkedHashMap<>();

    @Schema(description = "TR069 管理参数（键名含 TR069 / ACS 的那几项）")
    private Map<String, String> tr069 = new LinkedHashMap<>();
}

/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.446
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** ONT 查询结果 */
@Data
public class OltOntQueryResult {

    @Schema(description = "实际下发的命令，如 display ont optical-info 0 1")
    private String command;

    @Schema(description = "命令原始回显")
    private String raw;

    @Schema(description = "解析出来的块，每块是一段「键 -> 值」（注册历史一块就是一次上/下线）")
    private List<LinkedHashMap<String, String>> blocks = new ArrayList<>();

    @Schema(description = "display ont info 的关键字段（IP / 线路模板 / 业务模板 / 掉线原因），只有查 info 时带")
    private OltOntInfo ontInfo;

    @Schema(description = "这条 ONT 的业务流（service-port 行），只有查 config（display current-configuration ont）时带")
    private List<OltServicePort> servicePorts = new ArrayList<>();
}

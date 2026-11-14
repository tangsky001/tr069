/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.677
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OltExecResult {

    private String code;

    private String label;

    @Schema(description = "真正下发的命令（含进视图的 vlan standard / interface 与收尾的 exit），页面显示出来便于核对")
    private List<String> commands = new ArrayList<>();

    @Schema(description = "每条命令的回显")
    private List<String> outputs = new ArrayList<>();

    @Schema(description = "是不是查询类动作")
    private boolean query;

    @Schema(description = "配置类动作执行后重采的最新详情；查询类为 null")
    private OltDetail detail;
}

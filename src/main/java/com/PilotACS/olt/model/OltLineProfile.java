/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.643
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** OLT 线路模板 */
@Data
public class OltLineProfile {

    @Schema(description = "模板 ID，ONT 注册时按它下发")
    private Integer profileId;

    @Schema(description = "模板名")
    private String name;

    @Schema(description = "模板类型（ONT 等）")
    private String type;

    @Schema(description = "回显里的 T-CONT 个数（Number of T-CONT）")
    private Integer tcontCount;

    @Schema(description = "回显里的 GEM port 个数（Number of GEM port）")
    private Integer gemportCount;

    @Schema(description = "每个 T-CONT 引用的 DBA 模板")
    private List<OltTcont> tconts = new ArrayList<>();

    @Schema(description = "每个 GEM port 挂在哪个 T-CONT 上、怎么映射")
    private List<OltGemport> gemports = new ArrayList<>();

    @Schema(description = "是不是 TR-069 用的线路模板：设备侧没有专门标记位，按模板名判断")
    private Boolean tr069Bound;

    @Schema(description = "映射关系表：一行一个 GEM port，T-CONT 的 DBA 模板顺带对上")
    private List<OltLineMappingRow> mappingRows = new ArrayList<>();
}

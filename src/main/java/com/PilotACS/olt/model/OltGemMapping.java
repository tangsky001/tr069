/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.223
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** GEM 映射行。 */
@Data
public class OltGemMapping {

    @Schema(description = "GEM 内映射序号（页面「GEM 映射ID」）")
    private Integer index;

    @Schema(description = "映射的 VLAN")
    private String vlan;
}

/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.936
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

@Data
public class OltOntDeleteAllRequest {

    @Schema(description = "只清这几块业务板（槽位号）；留空表示所有业务板")
    private List<Integer> slots;
}

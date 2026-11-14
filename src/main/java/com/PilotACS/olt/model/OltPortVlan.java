/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.683
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OltPortVlan {

    @Schema(description = "机框号，华为固定 0")
    private Integer frameNo;

    private Integer slotNo;

    private Integer portNo;

    @Schema(description = "接口标识，如 0/6/0，和命令行一致")
    private String iface;

    @Schema(description = "该口放通的 VLAN，升序")
    private List<Integer> vlans = new ArrayList<>();

    @Schema(description = "压好的 VLAN 串：「1、41、45-46」，与网管写法一致")
    private String vlanText;

    @Schema(description = "Native VLAN（PVID），回显里没有这一行时为 null")
    private Integer nativeVlan;
}

/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.183
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一体化 OLT 前面板信息。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OltFrontPanel {

    @Schema(description = "型号，如 HSGQ-XE08R")
    private String model;

    @Schema(description = "PON 口类型：GPON / EPON")
    private String ponType;

    @Schema(description = "PON 口数")
    private int ponCount;

    @Schema(description = "上联 GE 口数（RJ45）")
    private int geCount;

    @Schema(description = "上联 10GE 口数（SFP+）")
    private int xgeCount;

    @Schema(description = "有没有独立 NMS 管理口")
    private boolean hasNms;

    @Schema(description = "有没有 CONSOLE 口")
    private boolean hasConsole;

    @Schema(description = "有没有 USB 口")
    private boolean hasUsb;

    @Schema(description = "规格说明：8 × GPON + 4 × GE + 4 × XGE")
    private String summary;
}

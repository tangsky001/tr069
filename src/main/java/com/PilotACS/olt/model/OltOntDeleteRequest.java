/**
 * @author: tangsky001
 * @date:   2026年11月21日
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** 删除单个 ONT 的请求：槽位 + PON 端口 + ONT ID，三项都必填 */
public class OltOntDeleteRequest {

    @Schema(description = "槽位号，如 2（0/2/0）", example = "2")
    private Integer slot;

    @Schema(description = "PON 端口号，如 0", example = "0")
    private Integer port;

    @Schema(description = "设备分配的 ONT ID，如 0", example = "0")
    private Integer ontId;

    public Integer getSlot() {
        return slot;
    }

    public void setSlot(Integer slot) {
        this.slot = slot;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public Integer getOntId() {
        return ontId;
    }

    public void setOntId(Integer ontId) {
        this.ontId = ontId;
    }
}

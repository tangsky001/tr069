/**
 * @author: tangsky001
 * @date:   2026年11月21日
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * ONU 复位（重启）请求，按字段缺省分级：
 * <ul>
 *   <li>{@code slot + port + ontId} 都给：复位单个 ONU（{@code ont reset <port> <ontId>}）；</li>
 *   <li>只给 {@code slot + port}：复位整条 PON 口（{@code ont reset <port> all}）；</li>
 *   <li>只给 {@code slot}：复位整块板卡——口数按库里板卡快照算，逐口 {@code ont reset <port> all}。</li>
 * </ul>
 * 复位只让 ONU 中断业务重新注册，注册与业务配置不动。
 */
@Data
public class OltOntResetRequest {

    @Schema(description = "槽位号（0/槽，例 2）")
    private Integer slot;

    @Schema(description = "PON 端口号；缺省时复位整块板卡")
    private Integer port;

    @Schema(description = "ONT ID；缺省时复位整条 PON 口")
    private Integer ontId;
}

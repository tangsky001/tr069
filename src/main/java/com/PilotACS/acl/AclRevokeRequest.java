/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.511
 *
 */

package com.PilotACS.acl;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** ACL 撤销请求 */
@Data
public class AclRevokeRequest {

    @Schema(description = "ACL 记录主键")
    private Long id;
}

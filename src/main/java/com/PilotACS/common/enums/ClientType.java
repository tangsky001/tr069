/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.618
 *
 */

package com.PilotACS.common.enums;

import lombok.Getter;

// 登录端标识
@Getter
public enum ClientType {
    WEB("网页端"),
    CLIENT("客户端"),
    MOBILE("移动端");

    private final String label;

    ClientType(String label) {
        this.label = label;
    }

    public static ClientType of(String name) {
        if (name == null || name.isBlank()) {
            return WEB;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return WEB;
        }
    }
}

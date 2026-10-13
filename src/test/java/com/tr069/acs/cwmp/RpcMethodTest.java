package com.tr069.acs.cwmp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CWMP 方法名解析。
 */
class RpcMethodTest {

    @Test
    void resolvesStandardName() {
        assertEquals(RpcMethod.GET_PARAMETER_NAMES, RpcMethod.of("GetParameterNames"));
        assertEquals(RpcMethod.SET_PARAMETER_VALUES, RpcMethod.of("SetParameterValues"));
    }

    /**
     * 历史代码与现场模板里出现过枚举常量名写法，必须也能识别，
     * 否则指令入队后到空 POST 才失败，设备侧表现为 500。
     */
    @Test
    void toleratesEnumConstantStyle() {
        assertEquals(RpcMethod.GET_PARAMETER_NAMES, RpcMethod.of("GET_PARAMETER_NAMES"));
        assertEquals(RpcMethod.DELETE_OBJECT, RpcMethod.of("DELETE_OBJECT"));
        assertEquals(RpcMethod.GET_PARAMETER_VALUES, RpcMethod.of("get-parameter-values"));
        assertEquals(RpcMethod.REBOOT, RpcMethod.of("reboot"));
    }

    @Test
    void rejectsUnknownOrEmpty() {
        assertNull(RpcMethod.of(null));
        assertNull(RpcMethod.of(""));
        assertNull(RpcMethod.of("NotARpc"));
    }

    @Test
    void keepsSoapMethodNameOnValue() {
        assertEquals("GetParameterNames", RpcMethod.GET_PARAMETER_NAMES.getValue());
    }
}

package com.tr069.acs.olt.telnet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 视图识别回归：长连接不退出登录，靠提示符判断停在哪一层，
 * 判错就会在根视图上多发一个 quit（直接退出登录，白丢一个 OLT 登录名额）。
 */
class OltCliViewTest {

    @Test
    void 根视图提示符应识别为ROOT() {
        assertEquals(OltCliSession.CliView.ROOT, OltCliSession.viewOf("MA5680T>", OltCliSession.CliView.UNKNOWN));
        assertEquals(OltCliSession.CliView.ROOT, OltCliSession.viewOf("MA5680T#", OltCliSession.CliView.UNKNOWN));
        assertEquals(OltCliSession.CliView.ROOT, OltCliSession.viewOf("Huawei Integrated Access Software\r\nMA5680T#",
                OltCliSession.CliView.UNKNOWN));
    }

    @Test
    void 配置视图应识别为CONFIG() {
        assertEquals(OltCliSession.CliView.CONFIG, OltCliSession.viewOf("(config)#", OltCliSession.CliView.UNKNOWN));
        assertEquals(OltCliSession.CliView.CONFIG, OltCliSession.viewOf("ZXAN(config)#", OltCliSession.CliView.UNKNOWN));
    }

    @Test
    void 接口与模板子视图应识别为SUB() {
        assertEquals(OltCliSession.CliView.SUB,
                OltCliSession.viewOf("(config-if-gpon-0/1)#", OltCliSession.CliView.UNKNOWN));
        assertEquals(OltCliSession.CliView.SUB,
                OltCliSession.viewOf("(config-if-gpon-0/1-ont-0)#", OltCliSession.CliView.UNKNOWN));
        assertEquals(OltCliSession.CliView.SUB,
                OltCliSession.viewOf("ZXAN(config-if-gei_1/1)#", OltCliSession.CliView.UNKNOWN));
        // aaa 这类也是子视图：当成 config 会卡死在这一层进不去
        assertEquals(OltCliSession.CliView.SUB,
                OltCliSession.viewOf("(config-aaa)#", OltCliSession.CliView.UNKNOWN));
    }

    @Test
    void 回显末尾不是提示符时应保持原视图() {
        assertEquals(OltCliSession.CliView.CONFIG,
                OltCliSession.viewOf("  F/S/P   ONT   Run state\r\n  0/1/0   0     online",
                        OltCliSession.CliView.CONFIG));
        assertEquals(OltCliSession.CliView.ROOT, OltCliSession.viewOf("", OltCliSession.CliView.ROOT));
    }
}

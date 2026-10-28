package com.tr069.acs.olt.telnet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 厂商识别。
 *
 * <p>厂商直接决定页面走哪一套界面（华为界面 / 第三方界面），所以这里只认回显里的
 * 厂商关键字：第三方 OLT 也有用 {@code display} 那一套命令的，早先「display 有回显
 * 就是华为」让它们全被记成 HUAWEI，页面一律出华为界面，华为专属命令还全是报错。</p>
 */
class OltVendorTest {

    @Test
    void shouldDetectHuaweiByKeyword() {
        assertEquals(OltVendor.HUAWEI,
                OltVendor.detect("Huawei Versatile Routing Platform Software\r\n<HUAWEI>"));
        assertEquals(OltVendor.HUAWEI, OltVendor.detect("  PRODUCT : MA5683T"));
        assertEquals(OltVendor.HUAWEI, OltVendor.detect("VERSION : MA5600V800R015C00"));
        assertEquals(OltVendor.HUAWEI, OltVendor.detect("SmartAX MA5608T"));
    }

    @Test
    void shouldDetectThirdPartyByKeyword() {
        assertEquals(OltVendor.ZTE, OltVendor.detect("ZXAN# show version"));
        assertEquals(OltVendor.FIBERHOME, OltVendor.detect("AN5516-01#"));
        assertEquals(OltVendor.HSGQ, OltVendor.detect(" Product : HSGQ-E04"));
    }

    /**
     * 第三方 OLT 用 display 那一套命令时不能被认成华为：认不出就是第三方（GENERIC），
     * 命令风格另外记在 cliStyle 上，不影响后续发 display 还是 show。
     */
    @Test
    void shouldNotInferHuaweiFromDisplayStyle() {
        assertEquals(OltVendor.GENERIC, OltVendor.detect("Gpon OLT Version: 2.1.3 build 20240618"));
        assertEquals(OltVendor.GENERIC,
                OltVendor.detect(OltVendor.CliStyle.DISPLAY, "Gpon OLT Version: 2.1.3 build 20240618"));
        assertEquals(OltVendor.GENERIC, OltVendor.detect(OltVendor.CliStyle.SHOW, "no vendor keyword here"));
    }
}

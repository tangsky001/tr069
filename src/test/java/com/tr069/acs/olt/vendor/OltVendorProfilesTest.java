package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.telnet.OltVendor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 厂商 profile 注册表：取哪份、怎么认出来 */
class OltVendorProfilesTest {

    @Test
    void shouldResolveProfileByVendor() {
        assertTrue(OltVendorProfiles.of(OltVendor.HUAWEI) instanceof HuaweiOltProfile);
        assertTrue(OltVendorProfiles.of(OltVendor.ZTE) instanceof ZteOltProfile);
        assertTrue(OltVendorProfiles.of(OltVendor.FIBERHOME) instanceof FiberhomeOltProfile);
        assertTrue(OltVendorProfiles.of(OltVendor.HSGQ) instanceof HsgqOltProfile);
        assertTrue(OltVendorProfiles.of(OltVendor.GENERIC) instanceof GenericOltProfile);
        // 没传厂商就给兜底那份，别到处判空
        assertSame(OltVendorProfiles.of(OltVendor.GENERIC), OltVendorProfiles.of(null));
    }

    @Test
    void shouldDetectVendorFromOutput() {
        assertEquals(OltVendor.HUAWEI, OltVendorProfiles.detect(" Product : MA5683T").vendor());
        assertEquals(OltVendor.ZTE, OltVendorProfiles.detect("ZXAN# show version").vendor());
        assertEquals(OltVendor.FIBERHOME, OltVendorProfiles.detect("AN5516-01").vendor());
        assertEquals(OltVendor.HSGQ, OltVendorProfiles.detect(" Product : HSGQ-E04").vendor());
        // 认不出：给兜底那份，而不是判给华为
        assertEquals(OltVendor.GENERIC, OltVendorProfiles.detect("welcome to my box").vendor());
        assertEquals(OltVendor.GENERIC, OltVendorProfiles.detect(null, "").vendor());
    }

    @Test
    void shouldKeepHuaweiCommands() {
        OltVendorProfile huawei = OltVendorProfiles.of(OltVendor.HUAWEI);
        assertEquals(List.of("display version"), huawei.versionCommands());
        // 只跑带机框号的 display board 0：不带参数时设备会停在 Parameter 提示上
        assertEquals(List.of("display board 0"), huawei.boardCommands());
        assertEquals(List.of("scroll", "screen-length 0 temporary"), huawei.pagingCommands());
        assertEquals("MA5683T", huawei.extractModel(" Product name : MA5683T"));
        assertFalse(huawei.wholeDevice());
    }

    @Test
    void shouldTryBothStylesWhenVendorUnknown() {
        OltVendorProfile generic = OltVendorProfiles.of(OltVendor.GENERIC);
        assertEquals(List.of("display version", "show version"), generic.versionCommands());
        // 认不出厂商时不认领任何命令风格，由会话按探测到的那条版本命令定
        assertEquals(null, generic.cliStyle());
        // 型号挨家问一遍：新品牌注册进来这里自动认得
        assertEquals("MA5683T", generic.extractModel(" Product : MA5683T"));
        assertEquals("ZXA10 C320", generic.extractModel("ZXAN# ZXA10 C320"));
        assertEquals("HSGQ-E04", generic.extractModel(" Product : HSGQ-E04"));
        assertEquals("AN5516-01", generic.extractModel("AN5516-01#"));
    }
}

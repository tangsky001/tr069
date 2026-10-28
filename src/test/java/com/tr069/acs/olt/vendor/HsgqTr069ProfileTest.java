package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.model.OltTr069Profile;
import com.tr069.acs.olt.model.OltTr069ProfileRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 鸿升光 TR-069（ACS）模板：命令怎么拼、回显怎么解。
 *
 * <p>命令写法来自现场回显：
 * {@code onu-tr069-profile add profile-id 20 profile-name acs url http://123.123.123.123
 * acs-password acs acs-username acs port 9090 password acs username acs inform-interval 300}。</p>
 */
class HsgqTr069ProfileTest {

    private final HsgqOltProfile epon = new HsgqOltProfile();

    private final HsgqGponOltProfile gpon = new HsgqGponOltProfile();

    @Test
    void shouldParseProfileTable() {
        String text = "Profile ID  Profile Name    BindTimes\r\n"
                + "----------------------------------------\r\n"
                + "10          acs_tr069       0\r\n"
                + "20          acs2            3\r\n"
                + "OLT(config)#";

        List<OltTr069Profile> profiles = epon.parseTr069Profiles(text);

        assertEquals(2, profiles.size());
        assertEquals(Integer.valueOf(10), profiles.get(0).getProfileId());
        assertEquals("acs_tr069", profiles.get(0).getName());
        assertEquals(Integer.valueOf(0), profiles.get(0).getBindTimes());
        assertEquals(Integer.valueOf(20), profiles.get(1).getProfileId());
        assertEquals(Integer.valueOf(3), profiles.get(1).getBindTimes());
    }

    @Test
    void shouldReturnEmptyWhenNothingCollected() {
        assertTrue(epon.parseTr069Profiles(null).isEmpty());
        assertTrue(epon.parseTr069Profiles("").isEmpty());
        // 只有表头没有数据行：不能把表头当成一条模板
        assertTrue(epon.parseTr069Profiles("Profile ID  Profile Name    BindTimes\r\n").isEmpty());
    }

    @Test
    void shouldBuildAddCommandLikeTheDevice() {
        List<String> commands = epon.createTr069ProfileCommands(fullRequest());

        assertEquals(List.of("onu-tr069-profile add profile-id 20 profile-name acs"
                + " url http://123.123.123.123"
                + " acs-password acs acs-username acs"
                + " port 9090 password acs username acs"
                + " inform-interval 300"), commands);
    }

    @Test
    void shouldSkipInformIntervalWhenDisabled() {
        OltTr069ProfileRequest request = fullRequest();
        request.setInformEnable(false);
        request.setInformInterval(null);

        String command = epon.createTr069ProfileCommands(request).get(0);

        // 设备没有单独的「定期通知」开关，去使能时就是不下发间隔
        assertFalse(command.contains("inform-interval"), command);
        assertTrue(command.endsWith("username acs"), command);
    }

    @Test
    void shouldSkipEmptyOptionalArgs() {
        OltTr069ProfileRequest request = new OltTr069ProfileRequest();
        request.setProfileId(7);
        request.setProfileName("t1");
        request.setUrl("http://1.1.1.1");
        request.setPort(9090);
        request.setAcsUsername("  ");
        request.setAcsPassword(null);

        // 空参数直接不写：设备按关键字解析，写成 acs-password acs-username 反而会报参数错误
        assertEquals(List.of("onu-tr069-profile add profile-id 7 profile-name t1"
                + " url http://1.1.1.1 port 9090"), epon.createTr069ProfileCommands(request));
    }

    @Test
    void shouldOnlyEnableTr069ForEpon() {
        assertTrue(epon.supportsTr069Profile());
        assertEquals(List.of("show onu-tr069-profile all"), epon.tr069ProfileQueryCommands());
        // GPON 的命令还没核过，先别让页面把 epon 那套发过去
        assertFalse(gpon.supportsTr069Profile());
    }

    private OltTr069ProfileRequest fullRequest() {
        OltTr069ProfileRequest request = new OltTr069ProfileRequest();
        request.setProfileId(20);
        request.setProfileName("acs");
        request.setUrl("http://123.123.123.123");
        request.setAcsUsername("acs");
        request.setAcsPassword("acs");
        request.setPort(9090);
        request.setUsername("acs");
        request.setPassword("acs");
        request.setInformEnable(true);
        request.setInformInterval(300);
        return request;
    }
}

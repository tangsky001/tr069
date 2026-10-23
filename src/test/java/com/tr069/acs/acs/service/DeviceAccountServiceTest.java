package com.tr069.acs.acs.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备本地账号的组装规则：哪些读回来的值算「超管账号」。
 *
 * <p>只测组装这一层纯逻辑，向设备读参数那部分依赖会话，不在这里覆盖。</p>
 */
class DeviceAccountServiceTest {

    private static final String ROOT = "InternetGatewayDevice.User.";

    @Test
    void pairsUsernameAndPasswordByInstance() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ROOT + "2.Username", "user");
        values.put(ROOT + "2.Password", "pwd2");
        values.put(ROOT + "1.Username", "telecomadmin");
        values.put(ROOT + "1.Password", "pwd1");

        List<Map<String, Object>> accounts = DeviceAccountService.assemble(values);

        assertEquals(2, accounts.size(), "两路 User 实例应合成两个账号");
        assertEquals("1", accounts.get(0).get("instance"), "实例按序号排列，超管在第 1 路");
        assertEquals("telecomadmin", accounts.get(0).get("username"));
        assertEquals("pwd1", accounts.get(0).get("password"));
        assertEquals("2", accounts.get(1).get("instance"));
        assertEquals("user", accounts.get(1).get("username"));
        assertEquals("pwd2", accounts.get(1).get("password"));
    }

    @Test
    void ignoresWanDialUsernameAndBlankValues() {
        Map<String, String> values = new LinkedHashMap<>();
        // WAN 拨号账号的叶子名也叫 Username，不当本地账号排掉就会冒充超管
        values.put("InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1."
                + "WANPPPConnection.1.Username", "pppoeuser");
        values.put(ROOT + "1.Username", "");
        values.put(ROOT + "1.Password", "");

        assertTrue(DeviceAccountService.assemble(values).isEmpty(),
                "WAN 拨号账号与空口令都不应产出本地账号");
    }

    @Test
    void recognizesVendorPrefixedLeaves() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ROOT + "1.X_CT-COM_Username", "cuadmin");
        values.put(ROOT + "1.X_CT-COM_Password", "cupwd");

        List<Map<String, Object>> accounts = DeviceAccountService.assemble(values);

        assertEquals(1, accounts.size(), "厂商私有叶子剥掉前缀后应与标准名同样识别");
        assertEquals("cuadmin", accounts.get(0).get("username"));
        assertEquals("cupwd", accounts.get(0).get("password"));
    }

    @Test
    void recognizesHuaweiWebUserInfo() {
        // 华为通用固件：超管挂在 UserInterface.X_HW_WebUserInfo.2，叶子写作 UserName
        Map<String, String> values = new LinkedHashMap<>();
        values.put("InternetGatewayDevice.UserInterface.X_HW_WebUserInfo.2.UserName",
                "CMCCAdmin");
        values.put("InternetGatewayDevice.UserInterface.X_HW_WebUserInfo.2.Password",
                "aDm8H%MdA");
        values.put("InternetGatewayDevice.UserInterface.X_HW_WebUserInfo.1.UserName",
                "user");
        values.put("InternetGatewayDevice.UserInterface.X_HW_WebUserInfo.1.Password",
                "userpwd");

        List<Map<String, Object>> accounts = DeviceAccountService.assemble(values);

        assertEquals(2, accounts.size(), "WebUserInfo 的两路实例都应产出账号");
        assertEquals("1", accounts.get(0).get("instance"));
        assertEquals("user", accounts.get(0).get("username"));
        assertEquals("2", accounts.get(1).get("instance"), "移动版超管在实例 2");
        assertEquals("CMCCAdmin", accounts.get(1).get("username"));
        assertEquals("aDm8H%MdA", accounts.get(1).get("password"));
    }

    @Test
    void recognizesTelecomAccountWithoutInstance() {
        // 运营商超管节点没有实例号：X_CMCC_TeleComAccount.Username
        Map<String, String> values = new LinkedHashMap<>();
        values.put("InternetGatewayDevice.DeviceInfo.X_CMCC_TeleComAccount.Username",
                "CMCCAdmin");
        values.put("InternetGatewayDevice.DeviceInfo.X_CMCC_TeleComAccount.Password",
                "pwd");

        List<Map<String, Object>> accounts = DeviceAccountService.assemble(values);

        assertEquals(1, accounts.size(), "无实例号的超管节点也要能识别");
        assertEquals("", accounts.get(0).get("instance"), "没有实例序号时留空");
        assertEquals("CMCCAdmin", accounts.get(0).get("username"));
    }
}

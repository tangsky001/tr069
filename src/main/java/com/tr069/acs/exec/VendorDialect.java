package com.tr069.acs.exec;

import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.worklist.tpl.entity.Device;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;

/**
 * 厂商方言：华为 / 中兴私有参数候选。
 * 与运营商适配层互补，候选统一经能力树验证后才下发。
 */
@Slf4j
public enum VendorDialect {

    HUAWEI("华为") {
        @Override
        public List<String> serviceListLeaves() {
            return List.of("X_HW_ExServiceList", "X_HW_ServiceList");
        }

        @Override
        public List<String> vlanIdLeaves() {
            return List.of("X_HW_VLAN");
        }

        @Override
        public String vlanEnableLeaf() {
            return "X_HW_VlanEnable";
        }

        @Override
        public List<String> wifiModuleLeaves() {
            return List.of("X_HW_APService.Enable");
        }

        @Override
        public List<String> telnetEnablePaths() {
            return List.of("X_HW_TelnetEnable");
        }

        @Override
        public List<String> adminAccountNodes() {
            return List.of("UserInterface.X_HW_WebUserInfo.2.");
        }

        @Override
        public List<String> limitModePaths() {
            return List.of();
        }
    },

    ZTE("中兴") {
        @Override
        public List<String> serviceListLeaves() {
            return List.of("X_CT-COM_ServiceList", "X_ZTE-COM_ServiceList");
        }

        @Override
        public List<String> vlanIdLeaves() {
            return List.of("X_CT-COM_VLANID", "X_ZTE-COM_VLANID");
        }

        @Override
        public String vlanEnableLeaf() {
            return null;
        }

        @Override
        public List<String> wifiModuleLeaves() {
            return List.of("X_CT-COM_APModuleEnable");
        }

        @Override
        public List<String> telnetEnablePaths() {
            return List.of("X_ZTE-COM_TelnetEnable", "X_CT-COM_TelnetEnable", "X_CU_Function.Telnet");
        }

        @Override
        public List<String> adminAccountNodes() {
            return List.of("DeviceInfo.X_CT-COM_TeleComAccount.",
                    "DeviceInfo.X_ZTE-COM_TeleComAccount.",
                    "DeviceInfo.X_CMCC_TeleComAccount.");
        }

        @Override
        public List<String> limitModePaths() {
            return List.of("Services.X_CT-COM_MWBAND.Mode");
        }
    },

    NONE("通用") {
        @Override
        public List<String> serviceListLeaves() {
            return List.of();
        }

        @Override
        public List<String> vlanIdLeaves() {
            return List.of();
        }

        @Override
        public String vlanEnableLeaf() {
            return null;
        }

        @Override
        public List<String> wifiModuleLeaves() {
            return List.of();
        }

        @Override
        public List<String> telnetEnablePaths() {
            return List.of();
        }

        @Override
        public List<String> adminAccountNodes() {
            return List.of();
        }

        @Override
        public List<String> limitModePaths() {
            return List.of();
        }
    };

    private final String label;

    VendorDialect(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** WAN 业务绑定叶子候选 */
    public abstract List<String> serviceListLeaves();

    /** WAN VLAN ID 叶子候选 */
    public abstract List<String> vlanIdLeaves();

    /** VLAN 生效开关叶子，无此开关的厂商返回 null */
    public abstract String vlanEnableLeaf();

    /** 无线模块硬件开关候选 */
    public abstract List<String> wifiModuleLeaves();

    /** Telnet 使能参数（相对根节点），不支持返回空 */
    public abstract List<String> telnetEnablePaths();

    /** 超管账号节点（相对根节点，带尾点），UserName/Password 拼在后面 */
    public abstract List<String> adminAccountNodes();

    /** 终端数限制参数（相对根节点，写 0 解除限制），不支持返回空 */
    public abstract List<String> limitModePaths();

    /** 从 SQLite 型号档案识别厂商 */
    public static VendorDialect of(Device model) {
        return model == null
                ? NONE
                : detect(model.getManufacturer(), model.getProductClass(), model.getSampleSn());
    }

    /** 从台账设备识别厂商 */
    public static VendorDialect detect(CpeDevice device) {
        return device == null
                ? NONE
                : detect(device.getManufacturer(), device.getProductClass(), device.getSn());
    }

    /** 厂商识别：厂商名 → 型号前缀 → SN 前缀（HWTC 华为 / ZTEG 中兴） */
    public static VendorDialect detect(String manufacturer, String productClass, String sn) {
        String mfg = manufacturer == null ? "" : manufacturer.trim();
        if (mfg.matches("(?i).*(huawei|华为).*")) {
            return HUAWEI;
        }
        if (mfg.matches("(?i).*(zte|中兴).*")) {
            return ZTE;
        }
        String pc = productClass == null ? "" : productClass.trim();
        if (pc.matches("(?i)^(HG|EG|HS|HN|MA|AR|F50).*$")) {
            return HUAWEI;
        }
        if (pc.matches("(?i)^(ZXHN|F6|F7|F4).*$")) {
            return ZTE;
        }
        if (sn != null && sn.length() >= 4) {
            String head = sn.substring(0, 4).toUpperCase(Locale.ROOT);
            if ("HWTC".equals(head)) {
                return HUAWEI;
            }
            if ("ZTEG".equals(head)) {
                return ZTE;
            }
        }
        return NONE;
    }
}

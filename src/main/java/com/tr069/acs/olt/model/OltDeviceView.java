package com.tr069.acs.olt.model;

import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.telnet.OltVendor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * OLT 台账视图。
 *
 * <p>密码一律不回显，只给一个 {@code hasPassword} 标记；编辑时密码留空表示沿用原值。</p>
 */
@Data
public class OltDeviceView {

    private Long id;

    private String name;

    private String host;

    private String protocol;

    private String username;

    private Integer port;

    private String charset;

    private String bindLocalIp;

    private List<String> webAccounts = new ArrayList<>();

    private String vendor;

    private String vendorLabel;

    private String model;

    private String softwareVersion;

    private String patchVersion;

    private String backplane;

    /** 机框槽位数（来自 display board 0 的槽位表），机框视图按它画槽位 */
    private Integer slotCount;

    private String macAddress;

    private String uptime;

    private String status;

    private LocalDateTime lastConnectAt;

    private LocalDateTime lastSyncAt;

    private String lastError;

    private String syncSummary;

    private String remark;

    private boolean hasPassword;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static OltDeviceView from(OltDevice device) {
        if (device == null) {
            return null;
        }
        OltDeviceView view = new OltDeviceView();
        view.setId(device.getId());
        view.setName(device.getName());
        view.setHost(device.getHost());
        view.setProtocol(device.getProtocol());
        view.setUsername(device.getUsername());
        view.setPort(device.getPort());
        view.setCharset(device.getCharset());
        view.setBindLocalIp(device.getBindLocalIp());
        view.setWebAccounts(split(device.getWebAccounts()));
        view.setVendor(device.getVendor());
        view.setVendorLabel(device.getVendor() == null || device.getVendor().isBlank()
                ? null : OltVendor.of(device.getVendor()).getLabel());
        view.setModel(device.getModel());
        view.setSoftwareVersion(device.getSoftwareVersion());
        view.setPatchVersion(device.getPatchVersion());
        view.setBackplane(device.getBackplane());
        view.setSlotCount(device.getSlotCount());
        view.setMacAddress(device.getMacAddress());
        view.setUptime(device.getUptime());
        view.setStatus(device.getStatus());
        view.setLastConnectAt(device.getLastConnectAt());
        view.setLastSyncAt(device.getLastSyncAt());
        view.setLastError(device.getLastError());
        view.setSyncSummary(device.getSyncSummary());
        view.setRemark(device.getRemark());
        view.setHasPassword(device.getPassword() != null && !device.getPassword().isEmpty());
        view.setCreatedAt(device.getCreatedAt());
        view.setUpdatedAt(device.getUpdatedAt());
        return view;
    }

    /**
     * 逗号分隔的账号串转列表。
     */
    public static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }
}

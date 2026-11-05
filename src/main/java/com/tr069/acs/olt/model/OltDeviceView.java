package com.tr069.acs.olt.model;

import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.telnet.OltVendor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** OLT 台账视图 */
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

    /** 状态中文：在线 / 离线 / 未探测。页面不再维护 {@code {ONLINE:'在线',...}} 码表 */
    private String statusText;

    /** 状态标签色（Element UI 的 type）：success / danger / info */
    private String statusLevel;

    /** 固件版本：「软件版本 / 补丁」拼好；两个都没有是「-」 */
    private String firmwareText;

    /** Web 登录账号串：「a、b」；没有账号限制时是「不限制」 */
    private String webAccountText;

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
        // 状态文案与配色、固件版本串、账号串都在这里拼：账页、详情页头、设备信息卡用的是同一份
        view.setStatusText(statusTextOf(device.getStatus()));
        view.setStatusLevel(statusLevelOf(device.getStatus()));
        view.setFirmwareText(firmwareTextOf(device.getSoftwareVersion(), device.getPatchVersion()));
        view.setWebAccountText(webAccountTextOf(view.getWebAccounts()));
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

    /** 状态中文：库里的状态码只有三种口径，其余一律按「未探测」说 */
    public static String statusTextOf(String status) {
        if ("ONLINE".equals(status)) {
            return "在线";
        }
        if ("OFFLINE".equals(status)) {
            return "离线";
        }
        return "未探测";
    }

    /** 状态标签色：在线绿、离线红、其余灰 */
    public static String statusLevelOf(String status) {
        if ("ONLINE".equals(status)) {
            return "success";
        }
        if ("OFFLINE".equals(status)) {
            return "danger";
        }
        return "info";
    }

    /** 固件版本按「版本 / 补丁」拼，和现场 OLT 的写法一致；一个都没有就是「-」 */
    public static String firmwareTextOf(String softwareVersion, String patchVersion) {
        List<String> parts = new ArrayList<>();
        if (softwareVersion != null && !softwareVersion.isBlank()) {
            parts.add(softwareVersion.trim());
        }
        if (patchVersion != null && !patchVersion.isBlank()) {
            parts.add(patchVersion.trim());
        }
        return parts.isEmpty() ? "-" : String.join(" / ", parts);
    }

    /** Web 账号串：空列表表示不限制登录账号，不是「没配」 */
    public static String webAccountTextOf(List<String> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return "不限制";
        }
        return String.join("、", accounts);
    }

    /** 逗号分隔的账号串转列表 */
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

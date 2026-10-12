package com.tr069.acs.worklist.service;

import com.tr069.acs.worklist.model.DeviceProfile;
import com.tr069.acs.worklist.registry.ProfileRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 依据设备上报的标识信息，匹配出适用于该设备的光猫适配档案。
 *
 * <p>匹配顺序按 {@code priority} 升序，第一个全部字段都命中（或留空不参与匹配）的档案胜出。
 * 新接入一款光猫时，只要新增对应 profile 并设置更精确的匹配条件即可。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceProfileMatcher {

    private final ProfileRegistry profileRegistry;

    /**
     * 按设备标识匹配适配档案。
     *
     * @param manufacturer    厂商
     * @param oui             OUI
     * @param productClass    产品类别
     * @param softwareVersion 软件版本
     * @param serialNumber    序列号
     * @return 命中的档案，没有命中返回 empty
     */
    public Optional<DeviceProfile> match(String manufacturer, String oui, String productClass,
                                         String softwareVersion, String serialNumber) {
        for (DeviceProfile profile : profileRegistry.orderedProfiles()) {
            if (matchRule(profile, manufacturer, oui, productClass, softwareVersion, serialNumber)) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    /**
     * 按序列号匹配，供管理端查询某台设备当前使用的档案。
     */
    public Optional<DeviceProfile> matchBySn(String serialNumber) {
        return match(null, null, null, null, serialNumber);
    }

    private boolean matchRule(DeviceProfile profile, String manufacturer, String oui,
                              String productClass, String softwareVersion, String serialNumber) {
        DeviceProfile.MatchRule rule = profile.getMatch();
        if (rule == null) {
            return false;
        }
        // 至少需要一个匹配条件，避免误会通用档案为全匹配
        if (isBlank(rule.getManufacturer()) && isBlank(rule.getOui())
                && isBlank(rule.getProductClass()) && isBlank(rule.getSoftwareVersion())
                && isBlank(rule.getSerialNumber())) {
            return false;
        }
        return matches(rule.getManufacturer(), manufacturer)
                && matches(rule.getOui(), oui)
                && matches(rule.getProductClass(), productClass)
                && matches(rule.getSoftwareVersion(), softwareVersion)
                && matches(rule.getSerialNumber(), serialNumber);
    }

    /**
     * 规则为空表示不参与匹配；设备侧字段为空则按不匹配处理。
     */
    private boolean matches(String regex, String actual) {
        if (regex == null || regex.isBlank()) {
            return true;
        }
        if (actual == null || actual.isBlank()) {
            return false;
        }
        return actual.matches(regex);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

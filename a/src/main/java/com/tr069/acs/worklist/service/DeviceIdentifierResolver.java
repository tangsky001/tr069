package com.tr069.acs.worklist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 设备标识解析：SN / OUI-SN / MAC / LOID 统一归到台账里的设备 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceIdentifierResolver {

    private final CpeDeviceMapper deviceMapper;
    private final CpeParameterMapper parameterMapper;

    /**
     * 按 SN、OUI-SN（如 00259E-485754439F17789B）、MAC、LOID 找设备。
     * 找不到返回 null，调用方决定是报错还是把工单留在「等待执行」。
     */
    public CpeDevice resolve(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim();
        // 1) 原样按 SN 查
        CpeDevice device = bySn(key);
        if (device != null) {
            return device;
        }
        // 2) OUI-SN：横杠后段是 SN，前段是 OUI，两者都试
        if (key.contains("-")) {
            String[] parts = key.split("-");
            device = bySn(parts[parts.length - 1]);
            if (device != null) {
                return device;
            }
            if (parts.length == 2) {
                device = deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                        .eq(CpeDevice::getSn, parts[1])
                        .eq(CpeDevice::getOui, parts[0])
                        .last("LIMIT 1"));
                if (device != null) {
                    return device;
                }
            }
        }
        // 3) MAC / LOID 是光猫侧参数值，从能力树参数里反查
        for (String pathKeyword : new String[]{"MAC", "LOID", "LogicalID"}) {
            try {
                var hits = parameterMapper.selectList(new LambdaQueryWrapper<CpeParameter>()
                        .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                        .like(CpeParameter::getParamPath, pathKeyword)
                        .eq(CpeParameter::getParamValue, key)
                        .last("LIMIT 1"));
                if (!hits.isEmpty()) {
                    device = bySn(hits.get(0).getSn());
                    if (device != null) {
                        log.info("设备标识按 {} 参数归一到 SN: {} -> {}", pathKeyword, key, device.getSn());
                        return device;
                    }
                }
            } catch (Exception e) {
                log.warn("按 {} 参数反查设备失败: value={}, err={}", pathKeyword, key, e.getMessage());
            }
        }
        return null;
    }

    private CpeDevice bySn(String sn) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                .eq(CpeDevice::getSn, sn)
                .last("LIMIT 1"));
    }
}

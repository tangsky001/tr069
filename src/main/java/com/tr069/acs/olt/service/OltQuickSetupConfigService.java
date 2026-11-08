package com.tr069.acs.olt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.olt.entity.OltQuickSetupConfig;
import com.tr069.acs.olt.mapper.OltQuickSetupConfigMapper;
import com.tr069.acs.olt.model.HuaweiQuickSetupRequest;
import com.tr069.acs.olt.model.OltHuaweiSetupForm;
import com.tr069.acs.olt.vendor.hw.HuaweiQuickSetupPlanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 一键开局参数的存储与默认值 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OltQuickSetupConfigService {

    // 现场默认值：网段、VLAN、上联口都是这套部署下的实测值，改现场就改这里
    private static final int DEFAULT_MANAGE_VLAN = 46;

    private static final String DEFAULT_MANAGE_VLAN_IP = "10.172.10.254/255.255.255.0";

    private static final String DEFAULT_ROUTE = "10.172.10.1";

    private static final int DEFAULT_TR069_VLAN = 46;

    private static final String DEFAULT_SERVICE_VLAN = "41,45";

    private static final String DEFAULT_PON_TYPE = "GPON";

    private static final int DEFAULT_DBA_BANDWIDTH_MBPS = 1000;

    private static final String DEFAULT_UPLINK_PORTS = "0/17/0,0/17/1,0/7/0,0/7/1,0/7/2,0/7/3";

    @Value("${acs.olt.quick-setup.acs-url:http://10.172.10.1:9090/}")
    private String defaultAcsUrl;

    @Value("${acs.olt.quick-setup.acs-username:acs}")
    private String defaultAcsUsername;

    @Value("${acs.olt.quick-setup.acs-password:acs}")
    private String defaultAcsPassword;

    private final OltQuickSetupConfigMapper configMapper;

    private final ObjectMapper objectMapper;

    /** 开局表单：当前参数 + 带宽上限 */
    public OltHuaweiSetupForm form(Long oltId, boolean forceDefaults) {
        HuaweiQuickSetupRequest request = forceDefaults ? defaults() : config(oltId);
        return OltHuaweiSetupForm.of(request);
    }

    /** 这台 OLT 存过的开局参数；没存过给默认值 */
    public HuaweiQuickSetupRequest config(Long oltId) {
        OltQuickSetupConfig row = select(oltId);
        if (row == null || row.getConfigJson() == null || row.getConfigJson().isBlank()) {
            return defaults();
        }
        try {
            HuaweiQuickSetupRequest request =
                    objectMapper.readValue(row.getConfigJson(), HuaweiQuickSetupRequest.class);
            return request == null ? defaults() : request;
        } catch (Exception e) {
            // 存过的东西读不出来（字段改过名之类）就退回默认值，别让页面打不开
            log.warn("开局参数解析失败，退回默认值 id={}: {}", oltId, e.getMessage());
            return defaults();
        }
    }

    /** 存一份开局参数，一台 OLT 一条（事务保证 upsert 原子性） */
    @Transactional(rollbackFor = Exception.class)
    public HuaweiQuickSetupRequest save(Long oltId, HuaweiQuickSetupRequest form) {
        if (form == null) {
            throw new IllegalArgumentException("开局参数不能为空");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(form);
        } catch (Exception e) {
            throw new IllegalArgumentException("开局参数存不下来：" + e.getMessage());
        }
        OltQuickSetupConfig row = select(oltId);
        if (row == null) {
            row = new OltQuickSetupConfig();
            row.setOltId(oltId);
            row.setConfigJson(json);
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            configMapper.insert(row);
        } else {
            row.setConfigJson(json);
            row.setUpdatedAt(LocalDateTime.now());
            configMapper.updateById(row);
        }
        return config(oltId);
    }

    /** 现场默认值：页面上「默认设置」就是回到这一套 */
    public HuaweiQuickSetupRequest defaults() {
        HuaweiQuickSetupRequest request = new HuaweiQuickSetupRequest();
        request.setManageVlan(DEFAULT_MANAGE_VLAN);
        request.setManageVlanIp(DEFAULT_MANAGE_VLAN_IP);
        request.setDefaultRoute(DEFAULT_ROUTE);
        request.setTr069Vlan(DEFAULT_TR069_VLAN);
        request.setServiceVlan(DEFAULT_SERVICE_VLAN);
        request.setPonType(DEFAULT_PON_TYPE);
        request.setAcsUrl(defaultAcsUrl);
        request.setAcsUsername(defaultAcsUsername);
        request.setAcsPassword(defaultAcsPassword);
        request.setDbaBandwidthMbps(DEFAULT_DBA_BANDWIDTH_MBPS);
        // 模板 ID 默认统一用一个号：现场对着设备看配置时一眼能认出是同一套。
        // 区间由设备定（10~512 / 1~32），这里不重复写死，取 planner 的默认与下限
        request.setDbaProfileId(HuaweiQuickSetupPlanner.DEFAULT_PROFILE_ID);
        request.setLineProfileId(HuaweiQuickSetupPlanner.DEFAULT_PROFILE_ID);
        request.setSrvProfileId(HuaweiQuickSetupPlanner.DEFAULT_PROFILE_ID);
        request.setTr069ProfileId(HuaweiQuickSetupPlanner.DEFAULT_TR069_PROFILE_ID);
        request.setUplinkPorts(DEFAULT_UPLINK_PORTS);
        request.setAllowUnknownMulticast(Boolean.FALSE);
        request.setMulticastService(Boolean.FALSE);
        return request;
    }

    private OltQuickSetupConfig select(Long oltId) {
        if (oltId == null) {
            return null;
        }
        return configMapper.selectOne(new LambdaQueryWrapper<OltQuickSetupConfig>()
                .eq(OltQuickSetupConfig::getOltId, oltId)
                .last("LIMIT 1"));
    }
}

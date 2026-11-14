package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.acs.adaptation.AdaptationManager;
import com.tr069.acs.acs.adaptation.IspAdaptation;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import com.tr069.acs.worklist.service.ModelMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.tr069.acs.common.log.AcsCoreLog;

/** 设备台账落库 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceRegistryService implements ApplicationRunner {

    private static final String STATUS_ONLINE = "online";

    private static final String STATUS_OFFLINE = "offline";

    private final CpeDeviceMapper cpeDeviceMapper;

    private final CpeParameterMapper cpeParameterMapper;

    /** 在线判定窗口（秒）：最近一次上报落在窗口内就算在线 */
    @Value("${acs.session.online-window-seconds:900}")
    private long onlineWindowSeconds;

    /** 看门狗是否启用（与 DeviceOnlineWatchdog 同口径）：启用时清扫窗口放宽，先给看门狗几轮唤醒机会 */
    @Value("${acs.device.watchdog.enabled:true}")
    private boolean watchdogEnabled;

    @Value("${acs.device.watchdog.max-retries:3}")
    private int watchdogMaxRetries;

    @Value("${acs.device.watchdog.interval-millis:600000}")
    private long watchdogIntervalMillis;
    private final ModelMappingService modelMappingService;

    private final AcsCoreLog coreLog;

    private final AdaptationManager adaptationManager;

    /** 启动后重算在线标记 */
    @Override
    public void run(ApplicationArguments args) {
        sweepExpiredOnline();
    }

    /** 设备上线或信息变更时落库：已存在则更新，否则新建 */
    public void upsertOnline(CpeSession session) {
        upsertOnline(session, List.of());
    }

    /** 设备上线或信息变更时落库：已存在则更新，否则新建 */
    public void upsertOnline(CpeSession session, List<String> informPaths) {
        try {
            LocalDateTime now = LocalDateTime.now();
            CpeDevice exist = cpeDeviceMapper.selectOne(
                    new LambdaQueryWrapper<CpeDevice>().eq(CpeDevice::getSn, session.getSn()));
            if (exist == null) {
                CpeDevice device = new CpeDevice();
                fill(device, session);
                device.setIsp(detectIsp(session.getSn(), informPaths, null));
                device.setStatus(STATUS_ONLINE);
                device.setLastContactTime(now);
                cpeDeviceMapper.insert(device);
                log.info("设备入库: sn={}, oui={}", session.getSn(), session.getOui());
                coreLog.online(session.getSn(), "首次上报入库");
                registerModel(session, device);
                return;
            }
            if (!STATUS_ONLINE.equals(exist.getStatus())) {
                // 从离线恢复：单独记一条，便于和设备掉线时间对上
                coreLog.online(session.getSn(), "离线后重新上报");
            }
            CpeDevice update = new CpeDevice();
            update.setId(exist.getId());
            fill(update, session);
            // 填上才更新：updateById 不动 null 字段，认不出来时保持库里原样
            update.setIsp(detectIsp(session.getSn(), informPaths, exist.getIsp()));
            update.setStatus(STATUS_ONLINE);
            update.setLastContactTime(now);
            cpeDeviceMapper.updateById(update);
        } catch (Exception e) {
            log.error("设备落库失败: sn={}", session.getSn(), e);
        }
    }

    /** 设备上线时按 Inform 私有前缀认运营商，再交给对应运营商的适配类；只补台账上还没有的 */
    private String detectIsp(String sn, List<String> informPaths, String current) {
        if (current != null && !current.isBlank()) {
            return null;
        }
        IspAdaptation adaptation = adaptationManager.detect(informPaths);
        if (adaptation == null) {
            return null;
        }
        log.info("按 Inform 私有前缀识别运营商: sn={}, isp={}, 适配类={}",
                sn, adaptation.code(), adaptation.getClass().getSimpleName());
        return adaptation.code();
    }

    /** 按上报窗口清扫：超过窗口还没再上报的设备才置离线；窗口按设备实际 PeriodicInformInterval 动态放宽 */
    @Scheduled(fixedDelayString = "${acs.session.offline-sweep-interval-millis:60000}")
    public void sweepExpiredOnline() {
        try {
            LocalDateTime deadline = LocalDateTime.now().minusSeconds(onlineWindowSeconds);
            List<CpeDevice> expired = cpeDeviceMapper.selectList(
                    new LambdaQueryWrapper<CpeDevice>()
                            .eq(CpeDevice::getStatus, STATUS_ONLINE)
                            .lt(CpeDevice::getLastContactTime, deadline));
            if (expired.isEmpty()) {
                return;
            }
            // 上报间隔被纳管改大过的设备，用固定窗口会误判离线：判定线取 max(窗口, 间隔×3)
            Map<String, Long> intervals = loadInformIntervals(
                    expired.stream().map(CpeDevice::getSn).toList());
            LocalDateTime now = LocalDateTime.now();
            for (CpeDevice device : expired) {
                Long interval = intervals.get(device.getSn());
                long effWindow = interval == null || interval <= 0
                        ? onlineWindowSeconds
                        : Math.max(onlineWindowSeconds, interval * 3);
                // 看门狗开着：多留几轮唤醒时间（轮数 × 巡检间隔），唤醒救不活再离线
                if (watchdogEnabled) {
                    effWindow += watchdogMaxRetries * watchdogIntervalMillis / 1000;
                }
                if (device.getLastContactTime() == null
                        || device.getLastContactTime().isBefore(now.minusSeconds(effWindow))) {
                    markOffline(device.getSn());
                }
            }
        } catch (Exception e) {
            log.warn("按上报窗口清扫离线失败，忽略: {}", e.getMessage());
        }
    }

    /** 批量取设备实际生效的 PeriodicInformInterval（秒），取不到的设备不在返回里 */
    public Map<String, Long> loadInformIntervals(List<String> sns) {
        Map<String, Long> result = new HashMap<>();
        try {
            List<CpeParameter> rows = cpeParameterMapper.selectList(
                    new LambdaQueryWrapper<CpeParameter>()
                            .in(CpeParameter::getSn, sns)
                            .like(CpeParameter::getParamPath, "ManagementServer.PeriodicInformInterval"));
            for (CpeParameter row : rows) {
                try {
                    long interval = Long.parseLong(row.getParamValue().trim());
                    // 更长的间隔优先：同设备多条记录时保留最大的
                    result.merge(row.getSn(), interval, Math::max);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("读取设备上报间隔失败，按固定窗口判定: {}", e.getMessage());
        }
        return result;
    }

    /** 标记离线；本就离线或尚未入库的设备直接跳过，避免无谓写库 */
    public void markOffline(String sn) {
        try {
            CpeDevice exist = cpeDeviceMapper.selectOne(
                    new LambdaQueryWrapper<CpeDevice>().eq(CpeDevice::getSn, sn));
            if (exist == null || STATUS_OFFLINE.equals(exist.getStatus())) {
                return;
            }
            CpeDevice update = new CpeDevice();
            update.setId(exist.getId());
            update.setStatus(STATUS_OFFLINE);
            cpeDeviceMapper.updateById(update);
            log.info("设备离线: sn={}", sn);
            coreLog.offline(sn, "会话结束或超时未上报");
        } catch (Exception e) {
            log.error("标记设备离线失败: sn={}", sn, e);
        }
    }

    /** 新设备首次上线：按「运营商 + 型号 + 固件」登记型号档案 */
    private void registerModel(CpeSession session, CpeDevice device) {
        try {
            String isp = (device.getIsp() == null || device.getIsp().isBlank())
                    ? "standard"
                    : device.getIsp().trim();
            modelMappingService.resolveOrRegister(isp, session.getManufacturer(), session.getProductClass(),
                    session.getSoftwareVersion(), session.getHardwareVersion(), session.getSn());
        } catch (Exception e) {
            log.warn("型号登记失败，不影响设备上线: sn={}, err={}", session.getSn(), e.getMessage());
        }
    }

    private void fill(CpeDevice device, CpeSession session) {
        device.setSn(session.getSn());
        device.setOui(session.getOui());
        device.setManufacturer(session.getManufacturer());
        device.setProductClass(session.getProductClass());
        device.setSoftwareVersion(session.getSoftwareVersion());
        device.setHardwareVersion(session.getHardwareVersion());
        device.setRootNode(session.getRootNode());
        device.setConnectionRequestUrl(session.getConnectionRequestUrl());
        device.setCwmpVersion(session.getCwmpVersion());
    }
}

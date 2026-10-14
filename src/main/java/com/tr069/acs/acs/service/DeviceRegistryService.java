package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.common.log.AcsCoreLog;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.worklist.model.DeviceProfile;
import com.tr069.acs.worklist.service.DeviceProfileMatcher;
import com.tr069.acs.worklist.service.ModelMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 设备台账落库。
 *
 * <p>会话只活在内存里，而管理端的设备列表读的是 {@code cpe_device} 表：设备注册或
 * 上报时必须同步落库，会话关闭或超时后要标记离线，否则列表永远空、重启后设备就丢了。</p>
 *
 * <p>落库失败不影响 CWMP 交互——台账是管理端视图，不该把设备挡在门外。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceRegistryService implements ApplicationRunner {

    private static final String STATUS_ONLINE = "online";

    private static final String STATUS_OFFLINE = "offline";

    private final CpeDeviceMapper cpeDeviceMapper;
    private final ModelMappingService modelMappingService;
    private final DeviceProfileMatcher profileMatcher;

    private final AcsCoreLog coreLog;

    /**
     * 启动后把残留的在线标记清掉：会话是内存态，重启后没有设备是在线的。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            CpeDevice update = new CpeDevice();
            update.setStatus(STATUS_OFFLINE);
            int rows = cpeDeviceMapper.update(update,
                    new LambdaUpdateWrapper<CpeDevice>().eq(CpeDevice::getStatus, STATUS_ONLINE));
            if (rows > 0) {
                log.info("启动时清理残留在线设备: {} 台", rows);
            }
        } catch (Exception e) {
            log.warn("清理残留在线设备失败，忽略: {}", e.getMessage());
        }
    }

    /**
     * 设备上线或信息变更时落库：已存在则更新，否则新建。
     */
    public void upsertOnline(CpeSession session) {
        try {
            LocalDateTime now = LocalDateTime.now();
            CpeDevice exist = cpeDeviceMapper.selectOne(
                    new LambdaQueryWrapper<CpeDevice>().eq(CpeDevice::getSn, session.getSn()));
            if (exist == null) {
                CpeDevice device = new CpeDevice();
                fill(device, session);
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
            update.setStatus(STATUS_ONLINE);
            update.setLastContactTime(now);
            cpeDeviceMapper.updateById(update);
        } catch (Exception e) {
            log.error("设备落库失败: sn={}", session.getSn(), e);
        }
    }

    /**
     * 标记离线；本就离线或尚未入库的设备直接跳过，避免无谓写库。
     */
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

    /**
     * 新设备首次上线：按「运营商 + 型号 + 固件」登记型号档案。
     *
     * <p>同型号同固件只在库里存在一条记录：已登记的直接复用其映射（免人工适配），
     * 没登记的建一条待适配记录，等人工在型号映射页补齐一次映射。</p>
     */
    private void registerModel(CpeSession session, CpeDevice device) {
        try {
            String isp = device.getIsp();
            if (isp == null || isp.isBlank()) {
                // 设备自己没带运营商时，用已配置的适配档案反推（CT / CU / CMCC）
                isp = profileMatcher.match(session.getManufacturer(), session.getOui(),
                                session.getProductClass(), session.getSoftwareVersion(), session.getSn())
                        .map(DeviceProfile::getIsp)
                        .orElse("standard");
            }
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

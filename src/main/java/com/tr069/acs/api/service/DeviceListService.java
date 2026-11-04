package com.tr069.acs.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tr069.acs.acs.service.DeviceBusinessService;
import com.tr069.acs.common.PageResult;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.mapper.CpeDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 设备列表查询：筛选、排序、分页都在后端做 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceListService {

    /** 派生排序要先把候选设备捞出来算键，给个上限防止全表拉爆（与导出同口径） */
    private static final int MAX_SCAN = 5000;

    /** 业务派生排序键：这些键不在 cpe_device 表里，得靠业务快照 */
    private static final Set<String> DERIVED_SORT_KEYS = Set.of("pon", "upTime", "ip");

    /** 中文比较器：型号、厂家这类中文名按拼音排，与页面原来的 localeCompare('zh') 一致 */
    private static final Collator COLLATOR = Collator.getInstance(Locale.CHINA);

    private final CpeDeviceMapper cpeDeviceMapper;
    private final DeviceBusinessService businessService;

    /** 设备分页 */
    public PageResult<CpeDevice> page(long page, long size, String sn, String isp, String deviceType,
                                      String roomNo, String optic, String sortKey, String sortOrder) {
        String key = sortKey == null ? "" : sortKey.trim();
        String order = "desc".equalsIgnoreCase(sortOrder) ? "desc" : "asc";
        boolean derivedSort = DERIVED_SORT_KEYS.contains(key);
        boolean derivedFilter = optic != null && !optic.isBlank();
        LambdaQueryWrapper<CpeDevice> wrapper = filter(sn, isp, deviceType, roomNo);

        if (!derivedSort && !derivedFilter) {
            sortLedger(wrapper, key, order);
            IPage<CpeDevice> result = cpeDeviceMapper.selectPage(new Page<>(page, size), wrapper);
            markOnline(result.getRecords());
            return PageResult.of(result.getRecords(), result.getTotal(), page, size);
        }

        // 派生排序 / 光模块筛选：候选集先取出来（不在这里分页），算完键再切片
        wrapper.orderByDesc(CpeDevice::getLastContactTime).last("LIMIT " + MAX_SCAN);
        List<CpeDevice> candidates = new ArrayList<>(cpeDeviceMapper.selectList(wrapper));
        markOnline(candidates);
        List<String> sns = candidates.stream().map(CpeDevice::getSn).toList();
        Map<String, Map<String, Object>> business;
        try {
            business = businessService.summaryBatch(sns);
        } catch (Exception e) {
            log.warn("读取业务快照失败，派生筛选/排序跳过: {}", e.getMessage());
            business = Map.of();
        }

        if (derivedFilter) {
            String want = optic.trim();
            List<CpeDevice> kept = new ArrayList<>(candidates.size());
            for (CpeDevice device : candidates) {
                Object state = business.getOrDefault(device.getSn(), Map.of()).get("opticState");
                if (state != null && want.equals(String.valueOf(state))) {
                    kept.add(device);
                }
            }
            candidates = kept;
        }
        if (derivedSort) {
            // 比较器里要用快照，先落成 final 引用（business 在 catch 分支里被赋过值）
            final Map<String, Map<String, Object>> index = business;
            final String compareKey = key;
            final int dir = "desc".equals(order) ? -1 : 1;
            candidates.sort((left, right) -> dir * compareDerived(left, right, compareKey, index));
        }

        int total = candidates.size();
        long from = Math.max(0, (page - 1) * size);
        int start = (int) Math.min(from, total);
        int end = (int) Math.min(start + size, total);
        return PageResult.of(new ArrayList<>(candidates.subList(start, end)), total, page, size);
    }

    /** 列表筛选条件：序列号与房间号模糊，运营商与设备类型精确 */
    private static LambdaQueryWrapper<CpeDevice> filter(String sn, String isp, String deviceType, String roomNo) {
        LambdaQueryWrapper<CpeDevice> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(isNotBlank(sn), CpeDevice::getSn, sn)
                .like(isNotBlank(roomNo), CpeDevice::getRoomNo, roomNo)
                .eq(isNotBlank(isp), CpeDevice::getIsp, isp)
                .eq(isNotBlank(deviceType), CpeDevice::getDeviceType, deviceType);
        return wrapper;
    }

    /** 台账列排序：能落到列上的都进 SQL，这样翻页也不会乱 */
    private static void sortLedger(LambdaQueryWrapper<CpeDevice> wrapper, String key, String order) {
        boolean asc = "asc".equals(order);
        switch (key) {
            case "sn" -> wrapper.orderBy(true, asc, CpeDevice::getSn);
            case "oui" -> wrapper.orderBy(true, asc, CpeDevice::getOui);
            case "manufacturer" -> wrapper.orderBy(true, asc, CpeDevice::getManufacturer);
            case "productClass" -> wrapper.orderBy(true, asc, CpeDevice::getProductClass);
            case "hardwareVersion" -> wrapper.orderBy(true, asc, CpeDevice::getHardwareVersion);
            case "softwareVersion" -> wrapper.orderBy(true, asc, CpeDevice::getSoftwareVersion);
            case "roomNo" -> wrapper.orderBy(true, asc, CpeDevice::getRoomNo);
            case "isp" -> wrapper.orderBy(true, asc, CpeDevice::getIsp);
            case "createdAt" -> wrapper.orderBy(true, asc, CpeDevice::getCreatedAt);
            // 「在线状态」按 status 排：ONLINE / OFFLINE 的字典序正好是在线在前
            case "online", "status" -> wrapper.orderBy(true, asc, CpeDevice::getStatus);
            case "lastContactTime" -> wrapper.orderBy(true, asc, CpeDevice::getLastContactTime);
            default -> {
            }
        }
        // 次要排序固定「最近上报倒序」：主键并列时列表不会来回跳
        wrapper.orderByDesc(CpeDevice::getLastContactTime);
    }

    /** 派生列比较：PON 类型 / 管理 IP 按中文串比，运行时长按秒数比 */
    private static int compareDerived(CpeDevice left, CpeDevice right, String key,
                                      Map<String, Map<String, Object>> business) {
        Map<String, Object> leftBiz = business.getOrDefault(left.getSn(), Map.of());
        Map<String, Object> rightBiz = business.getOrDefault(right.getSn(), Map.of());
        if ("upTime".equals(key)) {
            return Long.compare(seconds(leftBiz.get("uptimeSeconds")), seconds(rightBiz.get("uptimeSeconds")));
        }
        if ("ip".equals(key)) {
            return COLLATOR.compare(text(leftBiz.get("ip")), text(rightBiz.get("ip")));
        }
        // pon：没采到时与页面同口径，按 GE 处理
        String leftPon = text(leftBiz.get("ponType"));
        String rightPon = text(rightBiz.get("ponType"));
        return COLLATOR.compare(leftPon.isEmpty() ? "GE" : leftPon, rightPon.isEmpty() ? "GE" : rightPon);
    }

    private static long seconds(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 在线标记后端算：页面不再拿 status 字符串自己 includes */
    private static void markOnline(List<CpeDevice> devices) {
        devices.forEach(device -> device.setOnline(CpeDevice.onlineOf(device.getStatus())));
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}

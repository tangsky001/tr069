package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备本地账号（超管 telecomadmin 等）查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAccountService {

    /** 叶子名：TR-069 标准写 Username，华为私有节点里写 UserName。 */
    private static final String LEAF_USERNAME = "Username";
    private static final String LEAF_USERNAME_HW = "UserName";
    private static final String LEAF_PASSWORD = "Password";

    /** 展示字段名：两种叶子写法归一到这里，后面就不用区分了。 */
    private static final String FIELD_USER = "username";
    private static final String FIELD_PASSWORD = "password";

    /** User 实例探测上限：超管一般就在前两路，多试几路成本可控。 */
    private static final int MAX_USER_INSTANCE = 4;

    /** 整批被拒后逐条重试的单条等待时间（秒）。 */
    private static final long RETRY_TIMEOUT_SECONDS = 8;

    /** 读正式参数前的探活等待时间（秒）：设备不回应时快速给结论。 */
    private static final long PROBE_TIMEOUT_SECONDS = 6;

    /**
     * 本地账号所在的父路径形态。
     */
    private static final Pattern LOCAL_ACCOUNT_PARENT =
            Pattern.compile("[._](User|WebUserInfo|TeleComAccount)\\.(\\d+\\.)?$");

    private final CpeParameterMapper parameterMapper;
    private final CpeDeviceMapper deviceMapper;
    private final DeviceCapabilityService capabilityService;
    private final SessionManager sessionManager;

    /** 等设备回参数的最长时间（秒）。 */
    @Value("${acs.device.account.read-timeout-seconds:30}")
    private long readTimeoutSeconds;

    /**
     * 查询某台设备的本地账号（含超管）。
     */
    public Map<String, Object> queryAdminAccount(String sn) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sn", sn);
        String root = resolveRoot(sn);
        data.put("rootNode", root);

        CpeSession session = sessionManager.find(sn);
        boolean online = session != null && session.isOnline();
        data.put("online", online);
        if (!online) {
            data.put("status", "offline");
            data.put("accounts", List.of());
            data.put("message", "设备离线，无法实时读取口令，请等设备上线后重试");
            return data;
        }

        // 会话在线不等于设备真会回应。先拿一条必定存在的参数探一下，探不动就
        // 直接给结论——否则要干等到读取超时，页面上一分钟没反应
        String probe = root + "DeviceInfo.SerialNumber";
        try {
            capabilityService.readPathValues(sn, List.of(probe))
                    .get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("设备未回应探活读取: sn={}, path={}, err={}",
                    sn, probe, e.getMessage());
            data.put("status", "timeout");
            data.put("accounts", List.of());
            data.put("message", "设备未回应参数读取请求，会话可能已失效，请稍候重试");
            return data;
        }

        // 只按设备登记的根节点先读一轮：三种根节点一起拼会把候选翻到二十来条，
        // 其中两种在这台设备上必然不存在，二分拆批的往返又把耗时吃掉了
        List<String> primary = candidatePaths(sn, List.of(root));
        if (primary.isEmpty()) {
            data.put("status", "empty");
            data.put("accounts", List.of());
            data.put("message", "设备上没有可用的本地账号参数路径");
            return data;
        }

        String status = "success";
        Map<String, String> values = new LinkedHashMap<>();
        try {
            CompletableFuture<Map<String, String>> future =
                    capabilityService.readPathValues(sn, primary);
            values.putAll(future.get(readTimeoutSeconds, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            status = "timeout";
            log.warn("读取设备账号超时: sn={}, paths={}", sn, primary.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "fail";
        } catch (Exception e) {
            status = "fail";
            log.warn("读取设备账号失败: sn={}, err={}", sn, e.getMessage());
        }

        // 登记的根节点读不到才换另外两种根节点。兜底里的兜底，放第二轮不拖慢主线
        List<String> candidates = new ArrayList<>(primary);
        if (values.isEmpty()) {
            List<String> others = candidatePaths(sn, otherRoots(root));
            for (String path : others) {
                candidates.add(path);
            }
            if (!others.isEmpty()) {
                try {
                    values.putAll(capabilityService.readPathValues(sn, others)
                            .get(readTimeoutSeconds, TimeUnit.SECONDS));
                } catch (Exception e) {
                    log.debug("备用根节点读取账号失败: sn={}, err={}", sn, e.getMessage());
                }
            }
        }

        // 整批被拒时逐条重试：有些设备只要批里有一个不认识的节点就整批回 fault，
        // 拆开单发却每一条都认（下发侧同样的兼容性问题已实测过）。
        // 重试共用读取超时这个总预算，避免设备不支持时把请求拖到几分钟。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(readTimeoutSeconds);
        for (String path : candidates) {
            if (values.containsKey(path)) {
                continue;
            }
            long remain = TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime());
            if (remain <= 0) {
                break;
            }
            try {
                values.putAll(capabilityService.readPathValues(sn, List.of(path))
                        .get(Math.min(RETRY_TIMEOUT_SECONDS, remain), TimeUnit.SECONDS));
            } catch (Exception e) {
                // 单条读不到就是设备没这个节点，跳过
            }
        }

        List<Map<String, Object>> accounts = assemble(values);
        if (accounts.isEmpty() && "success".equals(status)) {
            status = "empty";
        }
        data.put("status", status);
        data.put("accounts", accounts);
        if (accounts.isEmpty()) {
            data.put("message", "设备未返回账号信息，可能型号不支持读取本地账号");
        } else {
            data.put("message", null);
        }
        log.info("查询设备本地账号: sn={}, 账号数={}, status={}", sn, accounts.size(), status);
        return data;
    }

    /**
     * 运营商定制固件的超管节点：叶子名与标准 User 实例一致。
     */
    private static final Map<String, String> ADMIN_NODE_BY_ISP = Map.of(
            "CT", "DeviceInfo.X_CT-COM_TeleComAccount.",    // 电信定制
            "CMCC", "DeviceInfo.X_CMCC_TeleComAccount.",    // 移动定制
            "CU", "DeviceInfo.X_CU_TeleComAccount.");       // 联通定制

    /**
     * 华为公开版（未做运营商定制）固件的 Web 管理账号节点。
     */
    private static final String HUAWEI_WEB_USER_NODE = "UserInterface.X_HW_WebUserInfo.";

    /**
     * 待读取的参数路径：按设备登记的运营商挑对应的超管节点，能力树快照里采到的
     * 账号路径补在后面，快照里一个都没有时再按 TR-098 / TR-181 两种根拼标准路径兜底。
     */
    private List<String> candidatePaths(String sn, List<String> roots) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        String isp = resolveIsp(sn).toUpperCase(Locale.ROOT);
        String adminNode = ADMIN_NODE_BY_ISP.get(isp);
        for (String r : roots) {
            if (adminNode != null) {
                paths.add(r + adminNode + LEAF_USERNAME);
                paths.add(r + adminNode + LEAF_PASSWORD);
                continue;
            }
            // 通用型号：华为公开版固件。两种叶子写法都试，各固件不一致
            for (int i = 1; i <= MAX_USER_INSTANCE; i++) {
                String base = r + HUAWEI_WEB_USER_NODE + i + ".";
                paths.add(base + LEAF_USERNAME_HW);
                paths.add(base + LEAF_USERNAME);
                paths.add(base + LEAF_PASSWORD);
            }
        }
        int fromSnapshot = 0;
        for (CpeParameter row : snapshotUserParams(sn)) {
            String leaf = DeviceBusinessService.normalizeLeaf(
                    DeviceCapabilityService.nameOf(row.getParamPath()));
            if (!LEAF_USERNAME.equals(leaf) && !LEAF_PASSWORD.equals(leaf)) {
                continue;
            }
            // 快照常只采到 Username 没有 Password（设备不把口令报给 GetParameterNames），
            // 这里按实例把两个叶子都补齐，只查账号查不出密码没意义
            String parent = DeviceCapabilityService.parentOf(row.getParamPath());
            paths.add(parent + LEAF_USERNAME);
            paths.add(parent + LEAF_PASSWORD);
            fromSnapshot++;
        }
        // 快照里采到真实账号路径就够了，不必再拼一堆标准 User 实例；
        // 一条都没有说明这台不对外部暴露账号节点，才往下兜底
        if (fromSnapshot > 0) {
            return new ArrayList<>(paths);
        }
        for (String r : roots) {
            for (int i = 1; i <= MAX_USER_INSTANCE; i++) {
                paths.add(r + "User." + i + "." + LEAF_USERNAME);
                paths.add(r + "User." + i + "." + LEAF_PASSWORD);
            }
        }
        return new ArrayList<>(paths);
    }

    /**
     * 能力树快照里形如 {@code ...User.{i}.Username} 的参数行。
     */
    private List<CpeParameter> snapshotUserParams(String sn) {
        try {
            return parameterMapper.selectList(new LambdaQueryWrapper<CpeParameter>()
                    .eq(CpeParameter::getSn, sn)
                    .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                    .like(CpeParameter::getParamPath, ".User.")
                    .orderByAsc(CpeParameter::getParamPath));
        } catch (Exception e) {
            log.warn("查询设备账号参数失败: sn={}, err={}", sn, e.getMessage());
            return List.of();
        }
    }

    /**
     * 把读到的路径按 User 实例配对成账号。

     */
    static List<Map<String, Object>> assemble(Map<String, String> values) {
        Map<String, Map<String, String>> grouped = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String path = entry.getKey();
            String leaf = DeviceBusinessService.normalizeLeaf(
                    DeviceCapabilityService.nameOf(path));
            String field = fieldOf(leaf);
            if (field == null) {
                continue;
            }
            // 只看本地账号那几种父路径：WAN 连接的 Username 是拨号账号，
            // 叶子名一模一样，不过滤就会被当成超管显示出来
            String parent = DeviceCapabilityService.parentOf(path);
            if (!LOCAL_ACCOUNT_PARENT.matcher(parent).find()) {
                continue;
            }
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(parent, k -> new LinkedHashMap<>())
                    .putIfAbsent(field, value);
        }
        List<Map<String, Object>> accounts = new ArrayList<>(grouped.size());
        for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
            Map<String, String> fields = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.getKey());
            item.put("instance", instanceOf(entry.getKey()));
            item.put("username", fields.getOrDefault(FIELD_USER, ""));
            item.put("password", fields.getOrDefault(FIELD_PASSWORD, ""));
            accounts.add(item);
        }
        return accounts;
    }

    /** 除指定根节点以外的标准根节点，用于第二轮兜底。 */
    private static List<String> otherRoots(String root) {
        List<String> others = new ArrayList<>();
        for (String r : List.of(DataModel.TR098.getRoot(), DataModel.TR181.getRoot())) {
            if (!r.equals(root)) {
                others.add(r);
            }
        }
        return others;
    }

    /**
     * 设备台账登记的运营商。
     */
    private String resolveIsp(String sn) {
        try {
            CpeDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                    .eq(CpeDevice::getSn, sn)
                    .last("LIMIT 1"));
            if (device != null && device.getIsp() != null) {
                String isp = device.getIsp().trim();
                return "standard".equalsIgnoreCase(isp) ? "" : isp;
            }
        } catch (Exception e) {
            log.warn("查询设备运营商失败: sn={}, err={}", sn, e.getMessage());
        }
        return "";
    }

    /** 从 {@code ...User.2.} 里取实例号，取不到返回空串。 */
    private static String instanceOf(String parent) {
        Matcher m = LOCAL_ACCOUNT_PARENT.matcher(parent);
        // TeleComAccount 这类节点没有实例号，返回空即可
        return m.find() && m.group(2) != null ? m.group(2).replace(".", "") : "";
    }

    /** 账号叶子名 -> 展示字段；认不出来（比如 SSID 之类的同名叶子）返回 null。 */
    private static String fieldOf(String leaf) {
        if (LEAF_USERNAME.equals(leaf) || LEAF_USERNAME_HW.equals(leaf)) {
            return FIELD_USER;
        }
        if (LEAF_PASSWORD.equals(leaf)) {
            return FIELD_PASSWORD;
        }
        return null;
    }

    /** 设备台账里登记的根节点，没登记按 TR-098 处理。 */
    private String resolveRoot(String sn) {
        try {
            CpeDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                    .eq(CpeDevice::getSn, sn)
                    .last("LIMIT 1"));
            if (device != null && device.getRootNode() != null && !device.getRootNode().isBlank()) {
                return device.getRootNode();
            }
        } catch (Exception e) {
            log.warn("查询设备根节点失败，回退默认根: sn={}, err={}", sn, e.getMessage());
        }
        return DataModel.TR098.getRoot();
    }
}

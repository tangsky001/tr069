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
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备本地账号（超管 telecomadmin 等）查询。
 *
 * <p>运维日常要登光猫后台改配置，超管口令每台都不一样又常被装维改过，
 * 只能到设备上现读。这里对在线设备下发 {@code GetParameterValues} 读
 * {@code InternetGatewayDevice.User.{i}.Username} / {@code Password}
 * （TR-181 设备是 {@code Device.User.{i}.}），把读到的账号原样返回。</p>
 *
 * <p>与 {@link DeviceBusinessService} 不同，这里不与能力树快照绑定：快照里
 * 采不到也要能查（很多设备压根不把 User 子树报给 GetParameterNames），
 * 所以快照有路径就按快照读，没有就按标准路径逐个试，试不到就是设备不支持。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAccountService {

    /** 叶子名：TR-069 标准里本地账号就是这两个节点。 */
    private static final String LEAF_USERNAME = "Username";
    private static final String LEAF_PASSWORD = "Password";

    /** User 实例探测上限：超管一般就在前两路，多试几路成本可控。 */
    private static final int MAX_USER_INSTANCE = 4;

    /** 整批被拒后逐条重试的单条等待时间（秒）。 */
    private static final long RETRY_TIMEOUT_SECONDS = 8;

    private static final Pattern USER_INSTANCE = Pattern.compile("\\.User\\.(\\d+)\\.$");

    private final CpeParameterMapper parameterMapper;
    private final CpeDeviceMapper deviceMapper;
    private final DeviceCapabilityService capabilityService;
    private final SessionManager sessionManager;

    /** 等设备回参数的最长时间（秒）。 */
    @Value("${acs.device.account.read-timeout-seconds:30}")
    private long readTimeoutSeconds;

    /**
     * 查询某台设备的本地账号（含超管）。
     *
     * @return {@code accounts} 为账号列表，每项含 instance / path / username / password；
     *         {@code status}：success / empty / timeout / fail / offline
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

        List<String> candidates = candidatePaths(sn, root);
        if (candidates.isEmpty()) {
            data.put("status", "empty");
            data.put("accounts", List.of());
            data.put("message", "设备上没有可用的本地账号参数路径");
            return data;
        }

        String status = "success";
        Map<String, String> values = new LinkedHashMap<>();
        try {
            CompletableFuture<Map<String, String>> future =
                    capabilityService.readPathValues(sn, candidates);
            values.putAll(future.get(readTimeoutSeconds, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            status = "timeout";
            log.warn("读取设备账号超时: sn={}, paths={}", sn, candidates.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "fail";
        } catch (Exception e) {
            status = "fail";
            log.warn("读取设备账号失败: sn={}, err={}", sn, e.getMessage());
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
        return data;
    }

    /**
     * 待读取的参数路径：优先用能力树快照里采到的真实路径，
     * 快照里没有才按 TR-098 / TR-181 两种根各拼若干路标准路径兜底。
     */
    private List<String> candidatePaths(String sn, String root) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
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
        }
        if (!paths.isEmpty()) {
            return new ArrayList<>(paths);
        }
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        roots.add(root);
        roots.add(DataModel.TR098.getRoot());
        roots.add(DataModel.TR181.getRoot());
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
     *
     * <p>{@code like} 会生成 {@code LIKE '%.User.%'}，WAN 连接的
     * {@code WANPPPConnection.1.Username} 不含 {@code .User.} 段，不会被误收进来。</p>
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
     *
     * <p>TreeMap 保证实例按序号排列；同一实例里 Username / Password 各取一次，
     * 设备同时报标准节点与厂商私有节点时保留先到的那个。</p>
     */
    static List<Map<String, Object>> assemble(Map<String, String> values) {
        Map<String, Map<String, String>> grouped = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String path = entry.getKey();
            String leaf = DeviceBusinessService.normalizeLeaf(
                    DeviceCapabilityService.nameOf(path));
            if (!LEAF_USERNAME.equals(leaf) && !LEAF_PASSWORD.equals(leaf)) {
                continue;
            }
            // 只看 User.{i} 下的账号：WAN 连接的 Username 是拨号账号，
            // 叶子名一模一样，不当本地账号过滤掉就会被当成超管显示出来
            String parent = DeviceCapabilityService.parentOf(path);
            if (!USER_INSTANCE.matcher(parent).find()) {
                continue;
            }
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(parent, k -> new LinkedHashMap<>())
                    .putIfAbsent(leaf, value);
        }
        List<Map<String, Object>> accounts = new ArrayList<>(grouped.size());
        for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
            Map<String, String> fields = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.getKey());
            item.put("instance", instanceOf(entry.getKey()));
            item.put("username", fields.getOrDefault(LEAF_USERNAME, ""));
            item.put("password", fields.getOrDefault(LEAF_PASSWORD, ""));
            accounts.add(item);
        }
        return accounts;
    }

    /** 从 {@code ...User.2.} 里取实例号，取不到返回空串。 */
    private static String instanceOf(String parent) {
        Matcher m = USER_INSTANCE.matcher(parent);
        return m.find() ? m.group(1) : "";
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

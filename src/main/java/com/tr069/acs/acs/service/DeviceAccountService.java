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

    /** 会话还在但可能已断链时，反向唤醒后最多等设备重新建链的时间（秒）。 */
    private static final long WAKEUP_WAIT_SECONDS = 15;

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
     * 整次查询的总预算（秒）。唤醒、两轮读取、逐条重试共用它，
     * 免得每一段各自按满额算，加起来超过前端的 90s 请求超时，请求被 axios 掐断
     * 时页面只剩一个「查询中」。
     */
    @Value("${acs.device.account.total-timeout-seconds:70}")
    private long totalTimeoutSeconds;

    /**
     * 设备离线时，发连接请求后最多等它上来的时间（秒）。
     *
     * <p>离线通常意味着链路断了或设备在休眠，叫醒比会话还在的场景慢，给的时间要长一些；
     * 这一段用完，剩下的总预算还够读一轮参数。</p>
     */
    @Value("${acs.device.account.offline-wakeup-wait-seconds:30}")
    private long offlineWakeupWaitSeconds;

    /**
     * 查询某台设备的本地账号（含超管）。
     */
    public Map<String, Object> queryAdminAccount(String sn) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sn", sn);
        String root = resolveRoot(sn);
        data.put("rootNode", root);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(totalTimeoutSeconds);

        CpeSession session = sessionManager.find(sn);
        boolean online = session != null && session.isOnline();
        data.put("online", online);
        if (!online) {
            // 「没有会话」不等于叫不醒：设备可被 UDP ConnectionRequest 叫上来，
            // 它自己建链之后指令才有地方捎带。先叫一次，叫得醒就接着读，
            // 直接回 offline 等于把唯一能救回来的手段丢掉了
            if (!capabilityService.canWakeUp(sn)) {
                data.put("status", "offline");
                data.put("accounts", List.of());
                data.put("message", "设备离线且无法反向唤醒：台账里没有这台设备的连接请求地址，"
                        + "检查纳管时设备是否上报了 ConnectionRequestURL");
                return data;
            }
            online = capabilityService.wakeUpAndAwait(sn,
                    Math.min(offlineWakeupWaitSeconds, remaining(deadline)));
            data.put("online", online);
            if (!online) {
                data.put("status", "offline");
                data.put("accounts", List.of());
                data.put("message", "设备离线，已发连接请求但 " + offlineWakeupWaitSeconds
                        + " 秒内没上来：确认设备能否访问 acs.public-host，或等它下一次周期上报");
                return data;
            }
            log.info("设备原本离线，已被连接请求唤醒: sn={}", sn);
        } else {
            // 会话在线不等于设备现在还连着：设备报完一轮就把 TCP 断了，指令只能排在队列里等
            // 下一次周期上报（十几分钟）——现场就是「17:52:20 下发成功，17:52:36 读取超时」。
            // 所以不拿探活读取当在线判据（它必然超时并误判成设备不回应），改成先反向唤醒再读
            capabilityService.wakeUpAndAwait(sn, Math.min(WAKEUP_WAIT_SECONDS, remaining(deadline)));
        }

        // 第一轮只发最可能命中的几条：运营商定制节点 + 能力树快照里真实采到过的账号路径。
        // 两种根节点一起拼会把候选翻到二十来条，二分拆批的往返把耗时吃掉了
        String isp = resolveIsp(sn);
        List<String> primary = primaryPaths(sn, root, isp);

        String status = "success";
        Map<String, String> values = new LinkedHashMap<>();
        try {
            CompletableFuture<Map<String, String>> future =
                    capabilityService.readPathValues(sn, primary);
            values.putAll(future.get(Math.min(readTimeoutSeconds, remaining(deadline)),
                    TimeUnit.SECONDS));
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

        // 定制节点读不到才扩大范围：先在同根下换节点形态（华为 Web 账号 / 标准 User 实例），
        // 再换另外两种根节点。只换根不换形态时，运营商登记错一台设备就永远读不到账号
        List<String> candidates = new ArrayList<>(primary);
        if (values.isEmpty() && !expired(deadline)) {
            List<String> fallback = fallbackPaths(root, isp);
            candidates.addAll(fallback);
            try {
                values.putAll(capabilityService.readPathValues(sn, fallback)
                        .get(Math.min(readTimeoutSeconds, remaining(deadline)), TimeUnit.SECONDS));
            } catch (Exception e) {
                log.debug("备用账号节点读取失败: sn={}, err={}", sn, e.getMessage());
            }
        }

        // 整批被拒时逐条重试：有些设备只要批里有一个不认识的节点就整批回 fault，
        // 拆开单发却每一条都认（下发侧同样的兼容性问题已实测过）。
        // 重试与前面几段共用总预算，避免设备不支持时把请求拖到几分钟。
        for (String path : candidates) {
            if (values.containsKey(path)) {
                continue;
            }
            if (expired(deadline)) {
                break;
            }
            try {
                values.putAll(capabilityService.readPathValues(sn, List.of(path))
                        .get(Math.min(RETRY_TIMEOUT_SECONDS, remaining(deadline)),
                                TimeUnit.SECONDS));
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
            // 超时和不支持是两回事：页面上「说明」列要能看出该重试还是该换型号
            if ("timeout".equals(status)) {
                data.put("message", "设备 " + readTimeoutSeconds + " 秒内没有回账号参数，稍后重试");
            } else if ("fail".equals(status)) {
                data.put("message", "读取账号参数失败，重试无效请查后端日志");
            } else {
                data.put("message",
                        "设备没有可用的本地账号节点，定制节点与通用 User 实例都读不到");
            }
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
     * 第一轮候选：运营商定制节点 + 能力树快照里真实采到过的账号路径。
     *
     * <p>这两类都短：定制节点就两条叶子，快照命中的路径按实例补齐 Username / Password。
     * 一条都拼不出来（运营商登记为空且快照没采到账号）才直接上通用形态，免得白跑一轮。</p>
     */
    private List<String> primaryPaths(String sn, String root, String isp) {
        LinkedHashSet<String> paths = new LinkedHashSet<>(ispPaths(root, isp));
        paths.addAll(snapshotPaths(sn));
        if (paths.isEmpty()) {
            paths.addAll(genericPaths(root));
        }
        return new ArrayList<>(paths);
    }

    /**
     * 第二轮候选：定制节点读不到时才展开。
     *
     * <p>先在同一根下换节点形态——运营商标错、或定制固件其实用华为 Web 账号 / 标准
     * {@code User.{i}} 的设备，只换根节点是永远读不到的；再换另外两种根节点。</p>
     */
    private List<String> fallbackPaths(String root, String isp) {
        LinkedHashSet<String> paths = new LinkedHashSet<>(genericPaths(root));
        // 台账上的运营商可能是别的：这台实际节点是 X_CMCC_TeleComAccount，
        // 登记成别家的就只有把三家定制节点都试一遍才读得到
        paths.addAll(otherIspPaths(root, isp));
        for (String r : otherRoots(root)) {
            paths.addAll(ispPaths(r, isp));
            paths.addAll(otherIspPaths(r, isp));
            paths.addAll(genericPaths(r));
        }
        return new ArrayList<>(paths);
    }

    /** 运营商定制固件的超管节点；未登记运营商返回空。 */
    private static List<String> ispPaths(String root, String isp) {
        String adminNode = ADMIN_NODE_BY_ISP.get(isp == null ? "" : isp.toUpperCase(Locale.ROOT));
        if (adminNode == null) {
            return List.of();
        }
        return List.of(root + adminNode + LEAF_USERNAME, root + adminNode + LEAF_PASSWORD);
    }

    /** 除已登记运营商以外的定制超管节点。 */
    private static List<String> otherIspPaths(String root, String isp) {
        String registered = isp == null ? "" : isp.toUpperCase(Locale.ROOT);
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : ADMIN_NODE_BY_ISP.entrySet()) {
            if (e.getKey().equals(registered)) {
                continue;
            }
            paths.add(root + e.getValue() + LEAF_USERNAME);
            paths.add(root + e.getValue() + LEAF_PASSWORD);
        }
        return new ArrayList<>(paths);
    }

    /** 总预算还剩下的秒数，至少 1 秒——0 会让 future.get 立刻超时，白跑一趟。 */
    private static long remaining(long deadline) {
        return Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()));
    }

    /** 总预算是否用完。 */
    private static boolean expired(long deadline) {
        return System.nanoTime() >= deadline;
    }

    /** 通用形态：华为公开版 Web 账号节点 + TR-098 / TR-181 标准 User 实例。 */
    private static List<String> genericPaths(String root) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        // 华为公开版固件，两种叶子写法都试，各固件不一致
        for (int i = 1; i <= MAX_USER_INSTANCE; i++) {
            String base = root + HUAWEI_WEB_USER_NODE + i + ".";
            paths.add(base + LEAF_USERNAME_HW);
            paths.add(base + LEAF_USERNAME);
            paths.add(base + LEAF_PASSWORD);
        }
        for (int i = 1; i <= MAX_USER_INSTANCE; i++) {
            paths.add(root + "User." + i + "." + LEAF_USERNAME);
            paths.add(root + "User." + i + "." + LEAF_PASSWORD);
        }
        return new ArrayList<>(paths);
    }

    /**
     * 能力树快照里真实存在、且父节点是本地账号对象的那几条路径。
     *
     * <p>快照常只采到 Username 没有 Password（设备不把口令报给 GetParameterNames），
     * 这里按实例把两个叶子都补齐，只查账号查不出密码没意义。</p>
     */
    private List<String> snapshotPaths(String sn) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (CpeParameter row : snapshotUserParams(sn)) {
            String leaf = DeviceBusinessService.normalizeLeaf(
                    DeviceCapabilityService.nameOf(row.getParamPath()));
            if (!LEAF_USERNAME.equals(leaf) && !LEAF_PASSWORD.equals(leaf)) {
                continue;
            }
            String parent = DeviceCapabilityService.parentOf(row.getParamPath());
            // WAN 拨号账号的叶子名也叫 Username，父路径不是本地账号对象的一律不要
            if (!LOCAL_ACCOUNT_PARENT.matcher(parent).find()) {
                continue;
            }
            paths.add(parent + LEAF_USERNAME);
            paths.add(parent + LEAF_PASSWORD);
        }
        return new ArrayList<>(paths);
    }

    /**
     * 能力树快照里账号对象的参数行：{@code User.{i}} / {@code X_HW_WebUserInfo.{i}} /
     * 运营商定制 {@code X_CT-COM_TeleComAccount} 都算。
     *
     * <p>之前只按 {@code .User.} 模糊匹配，定制节点的账号行一条都查不出来，
     * 快照兜底等于没生效。</p>
     */
    private List<CpeParameter> snapshotUserParams(String sn) {
        try {
            return parameterMapper.selectList(new LambdaQueryWrapper<CpeParameter>()
                    .eq(CpeParameter::getSn, sn)
                    .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                    .and(w -> w.like(CpeParameter::getParamPath, ".User.")
                            .or().like(CpeParameter::getParamPath, "WebUserInfo.")
                            .or().like(CpeParameter::getParamPath, "TeleComAccount."))
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

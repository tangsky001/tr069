package com.PilotACS.template.exec;

import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.DeviceData;
import com.PilotACS.cpe.service.BusinessPaths;
import com.PilotACS.cpe.service.TaskService;
import com.PilotACS.cpe.session.CwmpSession;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 意图下发服务：把配置模板的意图字段翻译成设备真实参数并执行。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentDeliveryService {

    /** SPV 等待上限：普通参数包几十秒内应答 */
    private static final long SPV_TIMEOUT_MS = 120_000L;
    /** AddObject / DeleteObject 等待上限：光猫重建 WAN 对象会慢一些 */
    private static final long OBJECT_TIMEOUT_MS = 180_000L;
    /** Reboot 等待上限 */
    private static final long REBOOT_TIMEOUT_MS = 180_000L;

    private final CpeStore store;
    private final TaskService taskService;
    private final com.PilotACS.cpe.server.CwmpService cwmpService;

    /** 业务绑定叶子候选集：各厂商私有写法全枚举（枚举不全 = VLAN/业务识别失败） */
    private static final List<String> SERVICE_LIST_LEAVES = List.of(
            "ServiceList", "X_CMCC_ServiceList", "X_CT-COM_ServiceList", "X_CTC_ServiceList",
            "X_CU_ServiceList", "X_ZTE-COM_SERVICELIST", "X_HW_SERVICELIST");
    /** VLAN 标记叶子候选集 */
    private static final List<String> VLAN_ID_LEAVES = List.of(
            "X_CMCC_VLANIDMark", "VLANIDMark", "X_CT-COM_VLANIDMark", "X_CTC_VLANIDMark",
            "X_CU_VLANID", "X_ZTE-COM_VLANID", "X_HW_VLAN");
    /** 802.1p 优先级叶子候选集 */
    private static final List<String> VLAN_PRIO_LEAVES = List.of(
            "X_CMCC_802-1pMark", "X_CT-COM_8021pMark", "X_CU_8021p", "X_ZTE-COM_VLANPri", "8021pMark");

    /** 下发单个意图 */
    public Map<String, Object> deliver(String sn, String category, Map<String, Object> fields,
                                       boolean incremental, String username) {
        // 同一设备互斥：业务模板/工单下发线程池并行后，同 SN 必须串行，
        // 否则两批任务交错入队、WAN 清理与新实例定位互相踩踏
        Object deliverLock = deliverLocks.computeIfAbsent(sn, k -> new Object());
        synchronized (deliverLock) {
            return doDeliver(sn, category, fields, incremental, username);
        }
    }

    private Map<String, Object> doDeliver(String sn, String category, Map<String, Object> fields,
                                          boolean incremental, String username) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, String>> detail = new ArrayList<>();
        out.put("detail", detail);
        try {
            // %room%/%desc%/字段变量统一在入口解析，WiFi/SIP/WAN/LAN/OTHER 渲染层无感知
            Map<String, String> vars = buildVars(sn, fields);
            fields = resolveFields(fields, vars, detail);
            detail.add(row("变量解析", "%room%=" + vars.get("%room%"), "ok", ""));
            DeviceData data = loadMergedDeviceData(sn);
            if (data.all() == null || data.all().isEmpty()) {
                throw new BusinessException("设备 " + sn + " 无存量参数树（未采集过），先做一次全量同步");
            }
            String root = BusinessPaths.detectRoot(data, null);
            if (root == null) {
                throw new BusinessException("无法识别设备数据模型（TR-098 / TR-181）");
            }
            String upperCat = category.toUpperCase(Locale.ROOT);
            if (upperCat.startsWith("WAN_")) {
                // WAN 预检：存量树缺 ServiceList 值（漏采）会让 TR069 通道识别、增量复用比对全失效，
                // 先轻量 GPV 回读补齐（失败静默跳过，不阻断下发）
                preflightRefreshWanSummary(sn, root, data);
            }

            List<Map.Entry<String, String>> plan = new ArrayList<>();
            switch (upperCat) {
                case "WAN_DHCP", "WAN_PPPOE", "WAN_STATIC", "WAN_BRIDGE" ->
                        renderWan(sn, root, data, category, fields, incremental, plan, detail);
                case "WIFI_24G", "WIFI_58G" ->
                        renderWifi(root, data, category, fields, plan, detail);
                case "SIP" ->
                        renderSip(root, data, fields, plan, detail);
                case "LAN" ->
                        renderLan(root, data, fields, plan, detail);
                case "OTHER" ->
                        renderOther(root, data, fields, plan, detail);
                default -> throw new BusinessException("未知模板类型: " + category);
            }
            log.info("[下发] 计划生成 sn={} 类别={} 增量={} 步骤={} 条: {}", sn, category, incremental,
                    plan.size(), plan.stream().map(Map.Entry::getKey).toList());
            // master 口径：计划渲染完（耗时不占挂起窗口），先反向唤醒并等设备建链，
            // 再提交第一条指令——避免"指令先入队、会话还没建立"只能等周期上报
            taskService.awaitSession(sn);

            boolean allOk = true;
            String newCdInstance = null;
            String newInstance = null;
            boolean usedFallbackCd = false;
            boolean usedFallbackInstance = false;
            String connObj = null;
            Set<Integer> knownInstances = null;
            Map<String, String> expected = new LinkedHashMap<>();
            for (Map.Entry<String, String> step : plan) {
                String desc = step.getKey();
                Map<String, Object> taskParams = parseParams(step.getValue());
                String type = String.valueOf(taskParams.remove("__type"));
                String path = String.valueOf(taskParams.remove("__path"));
                String fallback = String.valueOf(taskParams.remove("__fallback"));
                Object optionalMeta = taskParams.remove("__optional");
                Object connObjMeta = taskParams.remove("__conn_obj");
                Object knownMeta = taskParams.remove("__known");
                String role = String.valueOf(taskParams.remove("__role"));
                String curCd = newCdInstance;
                String curInst = newInstance;
                String realPath = resolvePlaceholders(path, curCd, curInst, fallback);
                Object values = taskParams.get("values");
                if (values instanceof List<?> list) {
                    List<Map<String, String>> fixed = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> mv) {
                            Map<String, String> nv = new LinkedHashMap<>();
                            mv.forEach((k, v) -> nv.put(String.valueOf(k),
                                    resolvePlaceholders(String.valueOf(v), curCd, curInst, fallback)));
                            fixed.add(nv);
                        }
                    }
                    taskParams.put("values", fixed);
                }
                List<Map<String, String>> effective = null;
                if ("SetParameterValues".equals(type)
                        && taskParams.get("values") instanceof List<?> vlist) {
                    effective = new ArrayList<>();
                    for (Object item : vlist) {
                        if (item instanceof Map<?, ?> mv) {
                            Map<String, String> one = new LinkedHashMap<>();
                            mv.forEach((k, v) -> one.put(String.valueOf(k), String.valueOf(v)));
                            effective.add(one);
                        }
                    }
                }
                Map<String, Object> stepResult = runTask(sn, type, realPath, taskParams, username);
                boolean ok = stepResult != null && Boolean.TRUE.equals(stepResult.get("ok"));
                String fault = stepResult == null ? "无结果"
                        : String.valueOf(stepResult.getOrDefault("fault", ""));
                log.info("[下发] 步骤 sn={} 任务={} 类型={} 路径={} 结果={}", sn, desc, type, realPath,
                        ok ? "成功" : "失败(" + fault + ")");
                if (!ok && "DeleteObject".equals(type)) {
                    // 设备拒绝删除（9002 占用/内置不可删）或对象已不存在（9005）都按「没清掉」处理，
                    // 跳过该连接继续下发——总比整单判死、新业务一条都发不下去强（master 口径）
                    detail.add(row("跳过: " + desc, realPath, "skip", fault));
                    continue;
                }
                if (!ok && taskParams.get("deleteVerify") != null) {
                    // 删后确认失败（条目未归零/设备不支持该叶子）：提示但不中断，
                    // 与「删除失败跳过继续」口径一致，残留实例由下次清理兜底
                    detail.add(row("跳过: " + desc, realPath, "skip", fault));
                    log.warn("[下发] 删后确认未通过 sn={} 详情={}", sn, fault);
                    continue;
                }
                if ("AddObject".equals(type) && ok) {
                    String result = String.valueOf(stepResult.getOrDefault("result_content", ""));
                    String parsed = parseInstanceNumber(result);
                    if ("conn_device".equals(role)) {
                        // 多 CD 拓扑：先建 WANConnectionDevice 实例
                        if (parsed != null) {
                            newCdInstance = parsed;
                            usedFallbackCd = false;
                        } else {
                            newCdInstance = fallback;
                            usedFallbackCd = true;
                        }
                        log.info("[下发] AddObject 新 WANConnectionDevice 实例号={}（设备返回={}，兜底={}）",
                                newCdInstance, result, fallback);
                    } else {
                        if (parsed != null) {
                            newInstance = parsed;
                            usedFallbackInstance = false;
                        } else {
                            newInstance = fallback;
                            usedFallbackInstance = true;
                            log.warn("[下发] AddObject 结果未解析到实例号，只能用兜底={}（结果={}），SPV 可能写向不存在的实例",
                                    fallback, result);
                        }
                    }
                    if (connObjMeta instanceof String co && !co.isBlank()) {
                        connObj = co;
                        knownInstances = parseKnownInstances(String.valueOf(knownMeta));
                        // 登记新建路径（多 CD 下 CD 与连接都要登记）：
                        // 窗口内后续清理绝不删它们（master rememberCreated 口径）
                        String resolvedObj = resolvePlaceholders(co, newCdInstance, newInstance, fallback);
                        if ("conn_device".equals(role)) {
                            wanCleanupLedger.computeIfAbsent(sn, k -> new WanCleanupState())
                                    .remember(resolvedObj + newCdInstance + ".");
                        } else if (newInstance != null) {
                            wanCleanupLedger.computeIfAbsent(sn, k -> new WanCleanupState())
                                    .remember(resolvedObj + newInstance + ".");
                        }
                    }
                    if (!"conn_device".equals(role)) {
                        log.info("[下发] AddObject 新实例号={}（设备返回={}，兜底={}）", newInstance, result, fallback);
                    }
                }
                // 整批 SPV 参数级被拒时逐条重发定位
                if (!ok && effective != null && SPV_REJECT.matcher(fault).find()) {
                    // 实例号兜底猜错是整批 9003 的常见原因：AddObject 已建实例但 SPV 写向不存在的路径。
                    // 先按落库参数树实测新实例号，校正路径整包重发一次。
                    // 多 CD 拓扑 CD 层同理：CD AddObject 兜底猜错时先实测校正 CD 实例号
                    if (usedFallbackCd && connObj != null && newCdInstance != null) {
                        String realCd = rediscoverNewInstance(sn, connObj, knownInstances);
                        if (realCd != null && !realCd.equals(newCdInstance)) {
                            List<Map<String, String>> fixed = fixInstancePaths(effective, connObj, newCdInstance, realCd);
                            Map<String, Object> r2 = runTask(sn, "SetParameterValues", "",
                                    Map.of("values", List.copyOf(fixed)), username);
                            if (r2 != null && Boolean.TRUE.equals(r2.get("ok"))) {
                                log.info("[下发] SPV 整批被拒：CD 实例号校正 {}→{} 后整包重发成功 sn={}",
                                        newCdInstance, realCd, sn);
                                ok = true;
                                fault = "";
                                effective = fixed;
                                newCdInstance = realCd;
                                usedFallbackCd = false;
                            }
                        }
                    }
                    if (usedFallbackInstance && connObj != null && newInstance != null) {
                        String real = rediscoverNewInstance(sn, connObj, knownInstances);
                        if (real != null && !real.equals(newInstance)) {
                            List<Map<String, String>> fixed = fixInstancePaths(effective, connObj, newInstance, real);
                            Map<String, Object> r2 = runTask(sn, "SetParameterValues", "",
                                    Map.of("values", List.copyOf(fixed)), username);
                            if (r2 != null && Boolean.TRUE.equals(r2.get("ok"))) {
                                log.info("[下发] SPV 整批被拒：实例号校正 {}→{} 后整包重发成功 sn={}",
                                        newInstance, real, sn);
                                ok = true;
                                fault = "";
                                effective = fixed;
                                newInstance = real;
                                usedFallbackInstance = false;
                            } else {
                                log.warn("[下发] SPV 实例号校正 {}→{} 整包重发仍失败 sn={}",
                                        newInstance, real, sn);
                            }
                        } else if (real == null) {
                            log.warn("[下发] SPV 整批被拒且参数树未发现新实例（AddObject 可能返回 Status=1 待重启生效）sn={}",
                                    sn);
                        }
                    }
                    if (!ok) {
                        LocateResult located = new LocateResult();
                        locateRejected(sn, effective, detail, located);
                        if (!located.rejected.isEmpty()) {
                            detail.add(row("执行: " + desc, realPath, "fail",
                                    "整批被拒，逐条重发（含换写法）后仍有 " + located.rejected.size()
                                            + " 个参数被拒: " + String.join("; ", located.rejected)));
                            allOk = false;
                            break; // 核心参数写不进去，继续发没意义
                        }
                        ok = true;
                        fault = "";
                        effective = located.accepted;
                        detail.add(row("执行: " + desc, realPath, "ok",
                                "整批被拒，逐条定位后全部落定（含换写法重试）"));
                    }
                }
                detail.add(row("执行: " + desc, realPath, ok ? "ok" : "fail", ok ? "" : fault));
                if (ok && effective != null) {
                    for (Map<String, String> mv : effective) {
                        String name = String.valueOf(mv.get("name"));
                        String val = String.valueOf(mv.get("value"));
                        if (name.contains("Password") || name.contains("PreSharedKey")
                                || name.contains("KeyPassphrase")) {
                            continue;
                        }
                        expected.put(name, val);
                    }
                }
                if (!ok) {
                    allOk = false;
                    break;
                }
            }
            out.put("success", allOk);
            if (allOk) {
                verify(sn, expected, detail, out);
            }
            out.put("error", allOk ? "" : "存在失败步骤，详见 detail");
            log.info("[下发] 结束 sn={} 类别={} 成功={} verified={} 步骤={} 条",
                    sn, category, allOk, out.get("verified"), detail.size());
            return out;
        } catch (BusinessException e) {
            log.warn("[下发] 计划失败 sn={} 类别={} 原因={}", sn, category, e.getMessage());
            detail.add(row("计划", "", "fail", e.getMessage()));
            out.put("success", false);
            out.put("error", e.getMessage());
            return out;
        } catch (Exception e) {
            log.warn("意图下发异常 sn={} category={}", sn, category, e);
            detail.add(row("计划", "", "fail", e.getMessage()));
            out.put("success", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    /** 同步执行一条 RPC 任务，超时视为失败 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> runTask(String sn, String type, String path,
                                        Map<String, Object> taskParams, String username) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (path != null && !path.isBlank()) {
            params.put("path", path);
        }
        if (taskParams != null) {
            params.putAll(taskParams);
        }
        long timeoutMs = switch (type) {
            case "SPV" -> SPV_TIMEOUT_MS;
            case "Reboot" -> REBOOT_TIMEOUT_MS;
            default -> OBJECT_TIMEOUT_MS;
        };
        long taskId = taskService.createTask(sn, type, params, username);
        Map<String, Object> task = taskService.awaitTask(taskId, (int) (timeoutMs / 1000));
        if (task == null) {
            return Map.of("ok", false, "fault", "任务超时");
        }
        String status = String.valueOf(task.get("status"));
        boolean ok = "success".equals(status);
        String fault = String.valueOf(task.get("fault_code"));
        if (fault == null || "null".equals(fault) || fault.isBlank()) {
            fault = String.valueOf(task.get("result_content"));
        }
        // result_content 必须带回：AddObject 靠它解析设备返回的真实实例号
        return Map.of("ok", ok, "fault", ok ? "" : fault,
                "result_content", String.valueOf(task.get("result_content")));
    }

    private Map<String, Object> parseParams(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new BusinessException("任务参数解析失败: " + e.getMessage());
        }
    }

    /** 下发成功后回读比对确认生效 */
    private void verify(String sn, Map<String, String> expected,
                        List<Map<String, String>> detail, Map<String, Object> out) {
        if (expected.isEmpty()) {
            out.put("verified", "skip");
            return;
        }
        try {
            long taskId = taskService.createTask(sn, "GetParameterValues",
                    Map.of("names", List.copyOf(expected.keySet())), "verify");
            taskService.awaitTask(taskId, 60);
            // 用合并树比对：会话里刚采集/刚写入的参数立刻可见，不用等落库
            DeviceData data = loadMergedDeviceData(sn);
            int diff = 0;
            for (Map.Entry<String, String> e : expected.entrySet()) {
                String actual = data.has(e.getKey()) ? data.value(e.getKey()) : null;
                if (actual == null) {
                    detail.add(row("回读确认", e.getKey(), "fail", "设备未上报该参数"));
                    diff++;
                } else if (!same(actual, e.getValue())) {
                    detail.add(row("回读确认", e.getKey(), "fail",
                            "期望 " + e.getValue() + "，设备实际 " + actual));
                    diff++;
                }
            }
            out.put("verified", diff == 0 ? "yes" : "partial");
            out.put("verifyTotal", expected.size());
            out.put("verifyDiff", diff);
            log.info("[下发] 回读确认 sn={} 比对={} 个 差异={} 个", sn, expected.size(), diff);
            if (diff == 0) {
                detail.add(row("回读确认", expected.size() + " 个参数全部一致", "ok", ""));
            }
        } catch (Exception e) {
            log.warn("回读确认失败 sn={}", sn, e);
            out.put("verified", "unknown");
            detail.add(row("回读确认", "", "skip", "回读失败: " + e.getMessage()));
        }
    }

    /** 回读比对：宽松口径——1↔true、0↔false、WPAand11i↔WPA/WPA2 都算一致（原先只做大小写不敏感，写对了也误报 partial） */
    private static boolean same(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        String x = a.trim();
        String y = b.trim();
        if (x.equalsIgnoreCase(y)) {
            return true;
        }
        Boolean bx = parseBoolLoose(x);
        Boolean by = parseBoolLoose(y);
        if (bx != null && by != null) {
            return bx.equals(by);
        }
        return ("WPAand11i".equalsIgnoreCase(x) && "WPA/WPA2".equalsIgnoreCase(y))
                || ("WPA/WPA2".equalsIgnoreCase(x) && "WPAand11i".equalsIgnoreCase(y));
    }

    private static Boolean parseBoolLoose(String s) {
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 合并数据源：存量参数树 + 活会话热数据（机型适配过的设备未采集时也不会直接抛错） */
    private DeviceData loadMergedDeviceData(String sn) {
        DeviceData data = new DeviceData();
        data.loadAll(store.loadParams(sn));
        try {
            CwmpSession session = cwmpService.findSessionBySn(sn);
            if (session != null && !session.isEnded() && session.getData() != null) {
                data.loadAll(session.getData().all());
            }
        } catch (Exception e) {
            log.debug("[下发] 合并活会话数据失败 sn={}: {}", sn, e.getMessage());
        }
        return data;
    }

    /** 占位符展开：__NEW_CD__ → 新 CD 实例号，__NEW__ → 新连接实例号（null 时用兜底号） */
    private static String resolvePlaceholders(String text, String newCd, String newConn, String fallback) {
        if (text == null) {
            return "";
        }
        String out = text;
        if (out.contains("__NEW_CD__")) {
            out = out.replace("__NEW_CD__", newCd != null ? newCd : fallback);
        }
        if (out.contains("__NEW__")) {
            out = out.replace("__NEW__", newConn != null ? newConn : fallback);
        }
        return out;
    }

    /** WAN 预检回读：扫描全部 WAN 实例，发现 ServiceList 有叶子无值（漏采）时批量 GPV 补齐并合并 */
    private void preflightRefreshWanSummary(String sn, String root, DeviceData data) {
        List<Object> toRead = new ArrayList<>();
        boolean missingServiceValue = false;
        String wanDevicePrefix = root + "WANDevice.";
        for (Integer wd : data.instances(wanDevicePrefix)) {
            String connPrefix = wanDevicePrefix + wd + ".WANConnectionDevice.";
            for (Integer cd : data.instances(connPrefix)) {
                for (String connType : List.of("WANIPConnection", "WANPPPConnection")) {
                    String typePrefix = connPrefix + cd + "." + connType + ".";
                    for (Integer i : data.instances(typePrefix)) {
                        String instBase = typePrefix + i;
                        String svcLeaf = existingLeafDot(data, instBase, SERVICE_LIST_LEAVES.toArray(String[]::new));
                        if (svcLeaf != null) {
                            if (data.value(svcLeaf) == null) {
                                missingServiceValue = true;
                            }
                            toRead.add(svcLeaf);
                        }
                        for (String extra : List.of("Name", "ExternalIPAddress", "ConnectionType")) {
                            if (data.has(instBase + "." + extra)) {
                                toRead.add(instBase + "." + extra);
                            }
                        }
                        String vlanLeaf = existingLeafDot(data, instBase, VLAN_ID_LEAVES.toArray(String[]::new));
                        if (vlanLeaf != null) {
                            toRead.add(vlanLeaf);
                        }
                    }
                }
            }
        }
        if (!missingServiceValue || toRead.isEmpty()) {
            return;
        }
        try {
            long taskId = taskService.createTask(sn, "GetParameterValues",
                    Map.of("names", new ArrayList<>(new LinkedHashSet<>(toRead))), "wan-preflight");
            taskService.awaitTask(taskId, 20);
            data.loadAll(loadMergedDeviceData(sn).all());
        } catch (Exception e) {
            log.debug("[下发] WAN 预检轻量读取跳过 sn={}: {}", sn, e.getMessage());
        }
    }

    /** 存量实例基路径（无尾点）上的候选叶子完整路径，存在即返回 */
    private static String existingLeafDot(DeviceData data, String instanceBaseNoDot, String... candidates) {
        for (String c : candidates) {
            String path = instanceBaseNoDot + "." + c;
            if (data.has(path)) {
                return path;
            }
        }
        return null;
    }

    private static Map<String, String> row(String step, String path, String status, String message) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("step", step);
        row.put("path", path == null ? "" : path);
        row.put("status", status);
        row.put("message", message == null ? "" : message);
        return row;
    }

    /** 渲染 WAN 类意图：清理存量、AddObject、SPV（多 CD 拓扑 + 全厂商叶子候选集） */
    private void renderWan(String sn, String root, DeviceData data, String category, Map<String, Object> fields,
                           boolean incremental,
                           List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        String wanDevicePrefix = root + "WANDevice.";
        List<Integer> wanDevices = data.instances(wanDevicePrefix);
        if (wanDevices.isEmpty()) {
            throw new BusinessException("设备参数树无 WANDevice 实例，无法下发 WAN 业务");
        }
        int targetWd = wanDevices.get(0);
        String cdPrefix = wanDevicePrefix + targetWd + ".WANConnectionDevice.";
        List<Integer> cdInstances = data.instances(cdPrefix);
        if (cdInstances.isEmpty()) {
            throw new BusinessException("设备参数树无 WANConnectionDevice 实例，无法下发 WAN 业务");
        }
        // 全量枚举存量连接（所有 CD × IP/PPP × 全部实例），多 CD 拓扑的清理/复用/参照都靠它
        String crHost = extractConnectionRequestHost(sn, root, data);
        List<ExistingWan> allExisting = new ArrayList<>();
        for (Integer cd : cdInstances) {
            for (String ct : List.of("WANIPConnection", "WANPPPConnection")) {
                String pfx = cdPrefix + cd + "." + ct + ".";
                for (Integer i : data.instances(pfx)) {
                    String instNoDot = pfx + i;
                    allExisting.add(new ExistingWan(targetWd, cd, ct, i, instNoDot + ".",
                            isTr069Connection(data, instNoDot, crHost),
                            findLeafValue(data, instNoDot, SERVICE_LIST_LEAVES.toArray(String[]::new)),
                            findLeafValue(data, instNoDot, VLAN_ID_LEAVES.toArray(String[]::new))));
                }
            }
        }

        boolean pppoe = "WAN_PPPOE".equals(category)
                || ("WAN_BRIDGE".equals(category) && "PPPoE".equalsIgnoreCase(text(fields, "bridge_type")));
        boolean bridge = "WAN_BRIDGE".equals(category);
        String wantConnType = pppoe ? "WANPPPConnection" : "WANIPConnection";
        String serviceList = normalizeServiceList(text(fields, "service_list"), bridge);
        String vlanId = text(fields, "vlan_id");
        boolean multiCd = isMultiCdTopology(data, cdPrefix, cdInstances);

        if (!incremental) {
            cleanupLegacyWanMulti(sn, cdPrefix, allExisting, data, multiCd, plan, detail);
        }

        // 参照实例基路径：新实例叶子存在性/类型探测用（优先同类型非管理连接）
        String refWanBase = findReferenceWanBase(allExisting, wantConnType);

        // 增量模式：同连接类型 + 同 VLAN + 同 ServiceList 的非管理连接直接复用，
        // 防止同设备第二种业务增量下发时写坏已有连接或重复建连接
        String base;
        ExistingWan reusable = null;
        if (incremental) {
            for (ExistingWan w : allExisting) {
                if (w.tr069() || !w.connType().equals(wantConnType)) {
                    continue;
                }
                boolean sameVlan = vlanId == null || vlanId.isBlank()
                        ? w.vlanId() == null || w.vlanId().isBlank()
                        : vlanId.trim().equals(w.vlanId() == null ? "" : w.vlanId().trim());
                boolean sameSvc = w.serviceList() != null && !w.serviceList().isBlank()
                        && normalizeServiceList(w.serviceList(), bridge).equalsIgnoreCase(serviceList);
                if (sameVlan && sameSvc) {
                    reusable = w;
                    break;
                }
            }
        }
        if (reusable != null) {
            base = reusable.base();
            detail.add(row("复用同特征增量连接", base, "ok", ""));
        } else {
            List<Integer> existing = data.instances(cdPrefix + cdInstances.get(0) + "." + wantConnType + ".");
            if (multiCd) {
                // 多 CD：先动态分配 WANConnectionDevice，再在其下建连接（业务与 TR069 通道隔离）
                int fallbackCd = cdInstances.stream().mapToInt(Integer::intValue).max().orElse(1) + 1;
                StringBuilder cdKnown = new StringBuilder();
                for (Integer cdi : cdInstances) {
                    if (cdKnown.length() > 0) {
                        cdKnown.append(',');
                    }
                    cdKnown.append(cdi);
                }
                String cdObj = cdPrefix;
                plan.add(step("新增 WANConnectionDevice 实例", taskJson("AddObject", cdObj,
                        Map.of("__fallback", fallbackCd, "__role", "conn_device",
                                "__conn_obj", cdObj, "__known", cdKnown.toString()))));
                String connObj = cdObj + "__NEW_CD__." + wantConnType + ".";
                plan.add(step("新增 " + wantConnType + " 实例", taskJson("AddObject", connObj,
                        Map.of("__fallback", 1, "__role", "conn"))));
                base = connObj + "__NEW__.";
                detail.add(row("新建独立 WAN 通道", base, "ok",
                        "多连接设备拓扑，动态分配 WANConnectionDevice 与连接实例"));
            } else {
                int targetCd = cdInstances.get(0);
                String connObj = cdPrefix + targetCd + "." + wantConnType + ".";
                int maxInst = existing.stream().mapToInt(Integer::intValue).max().orElse(0);
                StringBuilder knownStr = new StringBuilder();
                for (Integer i : existing) {
                    if (knownStr.length() > 0) {
                        knownStr.append(',');
                    }
                    knownStr.append(i);
                }
                plan.add(step("新增实例 " + connObj,
                        taskJson("AddObject", connObj, Map.of(
                                "__fallback", maxInst + 1,
                                "__conn_obj", connObj,
                                "__known", knownStr.toString()))));
                base = connObj + "__NEW__.";
                detail.add(row("新实例号", connObj, "ok",
                        "以 AddObject 返回为准，兜底为 " + (maxInst + 1)));
            }
        }

        // 渲染参数。注意：Enable 不在这里写——SPV 报文按顺序应用，
        // Enable 先置位会提前触发连接，VLAN/账号还没生效 → 拨号失败退避重试
        // （"参数全对但要等好几分钟才通"）。Enable 统一在报文末尾下发（见 emitSpv 前）
        Map<String, TypedVal> values = new LinkedHashMap<>();
        boolean routed = !bridge;
        putValue(values, data, base, refWanBase, detail, "ConnectionType",
                routed ? "IP_Routed" : (pppoe ? "PPPoE_Bridged" : "IP_Bridged"),
                "ConnectionType");
        String name = "WAN_" + (vlanId == null ? "" : vlanId)
                + (bridge ? "_BRIDGE" : "") + "_" + category.replace("WAN_", "");
        putValue(values, data, base, refWanBase, detail, "Name", name, "Name");
        if (routed) {
            putOptionalValue(values, data, base, refWanBase, "NAT 开关", "1", "NATEnabled",
                    "X_CT-COM_NATEnabled", "X_CMCC_NATEnabled");
        } else {
            // 桥接必须显式关 NAT：不写部分固件保持路由 NAT 残留
            putOptionalValue(values, data, base, refWanBase, "NAT 开关", "0", "NATEnabled",
                    "X_CT-COM_NATEnabled", "X_CMCC_NATEnabled");
        }
        String svcLeaf = leafRef(data, base, refWanBase, SERVICE_LIST_LEAVES.toArray(String[]::new));
        if (svcLeaf != null) {
            // 华为 X_HW_SERVICELIST 的分隔符是下划线（逗号会被固件截断）
            String formattedSvc = svcLeaf.endsWith("X_HW_SERVICELIST") ? serviceList.replace(',', '_') : serviceList;
            putDirect(values, data, refWanBase, svcLeaf, formattedSvc);
        } else {
            detail.add(row("跳过参数", "业务绑定(ServiceList)", "skip", "未匹配到 ServiceList 节点"));
        }
        if (vlanId != null && !vlanId.isBlank()) {
            boolean tagged = !"0".equals(vlanId.trim());
            String prio = text(fields, "vlan_priority");
            String pbit = prio == null || prio.isBlank() ? "0" : prio.trim();
            // X_CMCC_VLANIDMark 各厂商口径不一：字符串型是 "VID 802.1p" 组合串；
            // 数值型（unsignedInt/int）只能写 VID，802.1p 改走独立的 X_CMCC_802-1pMark。
            // 组合串写进数值型叶子会被客户端预检拒绝（参数类型不匹配），整包直接报废——瑞斯康达这台就是 unsignedInt
            if (isNumericType(declaredType(data, base, "X_CMCC_VLANIDMark"))) {
                putValue(values, data, base, refWanBase, detail, "VLAN 标记(X_CMCC_VLANIDMark)", vlanId.trim(),
                        "X_CMCC_VLANIDMark");
                putValue(values, data, base, refWanBase, detail, "802.1p 优先级(X_CMCC_802-1pMark)", pbit,
                        "X_CMCC_802-1pMark");
            } else {
                putValue(values, data, base, refWanBase, detail, "VLAN 标记(X_CMCC_VLANIDMark)",
                        vlanId.trim() + " " + pbit, "X_CMCC_VLANIDMark");
            }
            // 标准与各厂商 VLAN 叶子全候选（中兴 X_ZTE-COM_VLANID / 联通 X_CU_VLANID / 华为 X_HW_VLAN 原先写不进去）
            putValue(values, data, base, refWanBase, detail, "VLAN ID", vlanId.trim(),
                    VLAN_ID_LEAVES.toArray(String[]::new));
            // VLAN 模式（Tagged=2/Transparent=1）与使能开关：次要叶子，机型没有就静默跳过
            putOptionalValue(values, data, base, refWanBase, "VLAN 模式", tagged ? "2" : "1",
                    "X_CMCC_VLANMode", "X_CT-COM_VLANMode", "X_CTC_VLANMode", "X_CU_VLANMode", "VLANMode");
            putOptionalValue(values, data, base, refWanBase, "VLAN 使能", tagged ? "1" : "0",
                    "X_HW_VLANEnable", "X_CU_VLANEnabled", "X_ZTE-COM_VLANEnable", "VLANEnabled");
            if (prio != null && !prio.isBlank()) {
                putOptionalValue(values, data, base, refWanBase, "802.1p 优先级策略", "Specified", "X_HW_PriPolicy");
                putOptionalValue(values, data, base, refWanBase, "802.1p 优先级", prio.trim(),
                        VLAN_PRIO_LEAVES.toArray(String[]::new));
            }
        }
        // IP 协议版本：华为走 X_HW_IPv4Enable/IPv6Enable 双开关；其余走 IPMode 族
        // （数值型写 1/2/3，字符串型写 IPv4/IPv6/Both，按参照实例声明类型分流）
        renderWanIpProtocol(values, data, base, refWanBase, text(fields, "ip_protocol"));
        String mtu = text(fields, "mtu");
        if (mtu != null && !mtu.isBlank()) {
            // PPPoE 承载在 PPP 层，MTU 应写 MaxMRUSize（写 MaxMTUSize 部分机型 9003）
            putValue(values, data, base, null, detail, pppoe ? "MaxMRUSize" : "MaxMTUSize", mtu.trim(),
                    pppoe ? "MaxMRUSize" : "MaxMTUSize");
        }
        String multicastVlan = text(fields, "multicast_vlan");
        if (multicastVlan != null && !multicastVlan.isBlank() && !"0".equals(multicastVlan.trim())) {
            putValue(values, data, base, refWanBase, detail, "组播 VLAN", multicastVlan.trim(),
                    "X_HW_MultiCastVLAN", "X_CMCC_MulticastVlan", "X_CT-COM_MulticastVlan",
                    "X_CTC_MulticastVlan", "X_CU_MulticastVlan", "X_ZTE-COM_MulticastVlan", "MulticastVlan");
        }
        if ("WAN_DHCP".equals(category)) {
            putOptionalValue(values, data, base, refWanBase, "寻址方式", "DHCP", "AddressingType");
        } else if ("WAN_PPPOE".equals(category)) {
            putValue(values, data, base, refWanBase, detail, "PPPoE 账号", text(fields, "username"), "Username");
            putValue(values, data, base, refWanBase, detail, "PPPoE 密码", text(fields, "password"), "Password");
            // 部分机型按需拨号会断线，显式常在（次要叶子，没有就跳过）
            putOptionalValue(values, data, base, refWanBase, "连接触发", "AlwaysOn", "ConnectionTrigger");
        }
        // 寻址方式（master TemplateBuilder 口径）：WAN_DHCP 必须显式写 IPAddressingType=DHCP。
        // AddObject 新建的 WANIPConnection 固件默认就是 Static，漏写就会「选 DHCP 却变静态」；
        // WAN_STATIC 同理显式写 Static。增量复用旧连接时也要写，把上次残留的 Static 改回 DHCP。
        if (routed && !pppoe) {
            String addrType = text(fields, "addr_type");
            if (addrType == null || addrType.isBlank()) {
                addrType = "WAN_STATIC".equalsIgnoreCase(category) ? "Static" : "DHCP";
            } else if ("dhcp".equalsIgnoreCase(addrType) || "ipoe".equalsIgnoreCase(addrType)) {
                addrType = "DHCP";
            } else if ("static".equalsIgnoreCase(addrType)) {
                addrType = "Static";
            }
            putAddressingType(values, data, base, refWanBase, detail, addrType);
        }
        if ("WAN_STATIC".equalsIgnoreCase(category)) {
            putValue(values, data, base, refWanBase, detail, "静态 IP", text(fields, "external_ip"), "ExternalIPAddress");
            putValue(values, data, base, refWanBase, detail, "子网掩码", text(fields, "subnet_mask"), "SubnetMask");
            // 默认路由唯一性自查（master 口径）：只允许上网 WAN 当默认路由，
            // 其他连接也挂着 DefaultGateway 会出口漂移（业务时通时不通）
            warnDefaultRouteClash(data, base, detail);
            putValue(values, data, base, refWanBase, detail, "默认网关", text(fields, "default_gateway"), "DefaultGateway");
            String dns = text(fields, "dns_servers");
            if (dns != null && !dns.isBlank()) {
                putOptionalValue(values, data, base, refWanBase, "静态 DNS", dns, "DNSServers");
            }
        }
        String bindPorts = textList(fields, "bind_ports", "bind_port");
        if (bindPorts != null && !bindPorts.isBlank()) {
            renderWanPortBinding(root, data, base, refWanBase, bridge,
                    parsePortTokens(bindPorts), values, detail);
        }
        // Enable 必须走 putValue 拼完整路径（裸 key "Enable" 设备必拒 9005，
        // 曾导致新建 WAN 连接从未启用——Disconnected 拿不到 IP 的根因）；
        // 且必须排在报文末尾：先配 VLAN/承载/账号，最后置位触发连接
        putValue(values, data, base, refWanBase, detail, "启用连接(Enable)", "1", "Enable");
        emitSpv(plan, "WAN 参数包 [" + category + "]", values, detail);
    }

    /** 多 CD 拓扑判定：CD 实例数>1 且 CD 对象可写；或出现华为双 CD 特征叶子（X_HW_SERVICELIST/X_HW_VLAN） */
    private static boolean isMultiCdTopology(DeviceData data, String cdPrefix, List<Integer> cdInstances) {
        CpeParam cdObj = data.get(cdPrefix);
        if (cdInstances.size() > 1 && (cdObj == null || cdObj.writable())) {
            return true;
        }
        for (String p : data.all().keySet()) {
            if (p.contains(".X_HW_SERVICELIST") || p.contains(".X_HW_VLAN")) {
                return cdObj == null || cdObj.writable();
            }
        }
        return false;
    }

    /** 新实例叶子的参照实例基路径：优先同类型非管理连接 → 同类型任意连接 → 任一连接 */
    private static String findReferenceWanBase(List<ExistingWan> allExisting, String wantConnType) {
        for (ExistingWan w : allExisting) {
            if (w.connType().equals(wantConnType) && !w.tr069()) {
                return w.base();
            }
        }
        for (ExistingWan w : allExisting) {
            if (w.connType().equals(wantConnType)) {
                return w.base();
            }
        }
        return allExisting.isEmpty() ? null : allExisting.get(0).base();
    }

    /** 设备 CR URL 的 host（参数树没有时回退台账），用于 ExternalIPAddress==CR host 的管理通道判定 */
    private String extractConnectionRequestHost(String sn, String root, DeviceData data) {
        String url = data.value(root + "ManagementServer.ConnectionRequestURL");
        if (url == null || url.isBlank()) {
            try {
                Map<String, Object> dev = store.findDevice(sn);
                if (dev != null && dev.get("connection_request_url") != null) {
                    url = String.valueOf(dev.get("connection_request_url"));
                }
            } catch (Exception ignore) {
            }
        }
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * TR069 管理通道判定：ServiceList 族含 TR069 / Name 含 TR069 / ExternalIPAddress == CR host。
     * 第三条补 src isManagementLink 的盲区：ServiceList 未上报且 ManagementServer 无指向线索时，
     * 原先认不出管理通道 → 防脱管保护反而拒绝清理。
     */
    private static boolean isTr069Connection(DeviceData data, String instNoDot, String crHost) {
        for (String leafName : SERVICE_LIST_LEAVES) {
            String v = data.value(instNoDot + "." + leafName);
            if (v != null && v.toUpperCase(Locale.ROOT).contains("TR069")) {
                return true;
            }
        }
        String name = data.value(instNoDot + ".Name");
        if (name != null && name.toUpperCase(Locale.ROOT).contains("TR069")) {
            return true;
        }
        return crHost != null && !crHost.isBlank()
                && data.value(instNoDot + ".ExternalIPAddress") != null
                && data.value(instNoDot + ".ExternalIPAddress").trim().equals(crHost.trim());
    }

    /** IP 协议版本渲染：华为双开关优先，否则 IPMode 族按参照声明类型分流数值/字符串口径 */
    private static void renderWanIpProtocol(Map<String, TypedVal> values, DeviceData data,
                                            String base, String refBase, String rawProto) {
        String proto = rawProto == null || rawProto.isBlank() ? "IPv4" : rawProto.trim();
        boolean wantV4 = !proto.equalsIgnoreCase("IPv6");
        boolean wantV6 = proto.toUpperCase(Locale.ROOT).contains("IPV6");
        String hwV4 = leafRef(data, base, refBase, "X_HW_IPv4Enable");
        String hwV6 = leafRef(data, base, refBase, "X_HW_IPv6Enable");
        if (hwV4 != null || hwV6 != null) {
            if (hwV4 != null) {
                putDirect(values, data, refBase, hwV4, wantV4 ? "1" : "0");
            }
            if (hwV6 != null) {
                putDirect(values, data, refBase, hwV6, wantV6 ? "1" : "0");
            }
            return;
        }
        String ipModeLeaf = leaf(data, base, refBase,
                "X_CMCC_IPMode", "X_CT-COM_IPMode", "X_CTC_IPMode", "X_CU_IPMode",
                "X_ZTE-COM_IPMode", "X_CBN_IPMode", "IPMode");
        if (ipModeLeaf != null) {
            CpeParam refParam = findTemplateParam(data, refBase, ipModeLeaf);
            String refType = refParam == null ? "int" : baseType(refParam.type());
            String refVal = refParam == null ? null : refParam.value();
            boolean numericEnum = !"string".equalsIgnoreCase(refType)
                    || (refVal != null && refVal.trim().matches("[123]"));
            String modeVal = numericEnum
                    ? (wantV4 && wantV6 ? "3" : (wantV6 ? "2" : "1"))
                    : (wantV4 && wantV6 ? "Both" : (wantV6 ? "IPv6" : "IPv4"));
            putDirect(values, data, refBase, ipModeLeaf, modeVal);
        }
    }

    /** 端口绑定 token 解析：兼容 List / "[LAN1, LAN2]" / "LAN1;LAN2" 等输入 */
    private static List<String> parsePortTokens(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof Collection<?> col) {
            for (Object item : col) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
            return out;
        }
        String s = String.valueOf(raw).trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        for (String part : s.split("[,;\\s\"']+")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /**
     * WAN 端口绑定三层降级：
     * ① 华为 X_HW_LANBIND.Lan{n}Enable/SSID{n}Enable 独立布尔；
     * ② 通用 X_*_LanInterface 全路径逗号串（桥接时附加 DHCPEnable=0 防地址残留）；
     * ③ 短叶 BindPhyPortInfo/BindLANPort 直写 token。
     */
    private static void renderWanPortBinding(String root, DeviceData data, String base, String refBase,
                                             boolean bridge, List<String> bindTokens,
                                             Map<String, TypedVal> values, List<Map<String, String>> detail) {
        boolean boundAny = false;
        String hwProbe = leafRef(data, base, refBase, "X_HW_LANBIND.Lan1Enable");
        if (hwProbe != null) {
            for (String token : bindTokens) {
                String upper = token.toUpperCase(Locale.ROOT);
                String hwLeaf = null;
                if (upper.matches("LAN\\d+")) {
                    hwLeaf = leafRef(data, base, refBase, "X_HW_LANBIND.Lan" + upper.substring(3) + "Enable");
                } else if (upper.matches("SSID\\d+")) {
                    hwLeaf = leafRef(data, base, refBase, "X_HW_LANBIND.SSID" + upper.substring(4) + "Enable");
                }
                if (hwLeaf != null) {
                    putDirect(values, data, refBase, hwLeaf, "1");
                    boundAny = true;
                }
            }
        }
        String fullPathLeaf = leaf(data, base, refBase,
                "X_CMCC_LanInterface", "X_CT-COM_LanInterface", "X_CTC_LanInterface",
                "X_CU_LanInterface", "X_ZTE-COM_LanInterface", "LanInterface");
        if (fullPathLeaf != null) {
            List<String> fullPaths = new ArrayList<>();
            for (String token : bindTokens) {
                String mapped = mapPortFullPath(root, data, token);
                if (mapped != null) {
                    fullPaths.add(mapped);
                }
            }
            if (!fullPaths.isEmpty()) {
                putDirect(values, data, refBase, fullPathLeaf, String.join(",", fullPaths));
                boundAny = true;
                if (bridge) {
                    // 桥接口残留 DHCP 会私接地址，显式关闭（次要叶子，没有就跳过）
                    putOptionalValue(values, data, base, refBase, "桥接端口关本地DHCP", "0",
                            "X_CMCC_LanInterface-DHCPEnable", "X_CT-COM_LanInterface-DHCPEnable",
                            "X_CU_LanInterface-DHCPEnable", "LanInterface-DHCPEnable");
                }
            }
        }
        if (!boundAny) {
            String shortLeaf = leaf(data, base, refBase,
                    "X_HW_BindPhyPortInfo", "X_CMCC_BindLANPort", "X_CT-COM_LANInterfaceBind", "BindLANPort");
            if (shortLeaf != null) {
                putDirect(values, data, refBase, shortLeaf, String.join(",", bindTokens));
                boundAny = true;
            }
        }
        if (!boundAny) {
            detail.add(row("跳过参数", "绑定端口(" + String.join(",", bindTokens) + ")", "skip",
                    "设备参数树未匹配到端口绑定节点或所选物理口不存在"));
        }
    }

    /** LAN{n}/SSID{n} → 设备参数树上真实存在的端口全路径（TR-098 两种写法 + TR-181 Ethernet.Interface/WiFi.SSID） */
    private static String mapPortFullPath(String root, DeviceData data, String port) {
        String p = port.trim();
        if (p.startsWith(root)) {
            return p.endsWith(".") ? p.substring(0, p.length() - 1) : p;
        }
        String upper = p.toUpperCase(Locale.ROOT);
        if (upper.matches("LAN\\d+")) {
            int no = Integer.parseInt(upper.substring(3));
            for (Integer n : data.instances(root + "LANDevice.")) {
                String prefix = root + "LANDevice." + n + ".LANEthernetInterfaceConfig." + no;
                if (hasObjectOrLeaves(data, prefix)) {
                    return prefix;
                }
            }
            int idx = 0;
            for (Integer i : data.instances(root + "Ethernet.Interface.")) {
                String base = root + "Ethernet.Interface." + i;
                String upstream = findLeafValue(data, base, "Upstream");
                if ("true".equalsIgnoreCase(upstream) || "1".equals(upstream)) {
                    continue;
                }
                if (++idx == no) {
                    return base;
                }
            }
        } else if (upper.matches("SSID\\d+")) {
            int no = Integer.parseInt(upper.substring(4));
            for (Integer n : data.instances(root + "LANDevice.")) {
                for (String seg : List.of("WLANConfiguration", "LANWLANConfiguration")) {
                    String prefix = root + "LANDevice." + n + "." + seg + "." + no;
                    if (hasObjectOrLeaves(data, prefix)) {
                        return prefix;
                    }
                }
            }
            String tr181Ssid = root + "WiFi.SSID." + no;
            if (hasObjectOrLeaves(data, tr181Ssid)) {
                return tr181Ssid;
            }
        }
        return null;
    }

    private static boolean hasObjectOrLeaves(DeviceData data, String pathNoDot) {
        return data.has(pathNoDot) || data.has(pathNoDot + ".") || !data.leaves(pathNoDot + ".").isEmpty();
    }

    /**
     * WAN 下发前清理旧业务连接（master WanCleanup 口径，增强多 CD）：
     * TR069 管理通道保留（三重判定 + CR host 比对）；本批次已新建的连接保留；
     * 一条管理通道都认不出时一条都不删（防止设备脱管）；
     * 台账窗口内不重复清理；多 CD 下清空的 CD 追加可选删除（__optional，9005 容错）。
     */
    private void cleanupLegacyWanMulti(String sn, String cdPrefix, List<ExistingWan> allExisting,
                                       DeviceData data, boolean multiCd,
                                       List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        long now = System.currentTimeMillis();
        // 过期台账惰性回收
        wanCleanupLedger.entrySet().removeIf(e -> now - e.getValue().lastCleanupAt > WAN_CLEANUP_WINDOW_MS * 2);
        synchronized (wanCleanupLocks.computeIfAbsent(sn, k -> new Object())) {
            WanCleanupState state = wanCleanupLedger.computeIfAbsent(sn, k -> new WanCleanupState());
            if (state.fresh()) {
                detail.add(row("跳过存量清理", cdPrefix, "skip",
                        "本批次已清理过 WAN，避免删掉刚建的业务连接"));
                return;
            }
            state.createdPaths.clear();
            String managementHint = findManagementHint(data);
            List<String> keep = new ArrayList<>();
            List<String> victims = new ArrayList<>();
            boolean managementSeen = false;
            Set<Integer> keptCds = new LinkedHashSet<>();
            for (ExistingWan w : allExisting) {
                boolean created = false;
                for (String guard : state.createdPaths) {
                    if (w.base().startsWith(guard)) {
                        created = true;
                        break;
                    }
                }
                boolean management = w.tr069() || isManagementLink(w.base(), data, managementHint);
                managementSeen |= management;
                if (management || created) {
                    keep.add(w.base() + (management ? "（管理通道）" : "（本批次新建）"));
                    keptCds.add(w.cd());
                } else {
                    victims.add(w.base());
                }
            }
            if (!managementSeen) {
                detail.add(row("跳过存量清理", cdPrefix, "skip",
                        "未认出 TR069 管理通道，一条都不删（防止设备脱管）"));
                return;
            }
            if (victims.isEmpty()) {
                detail.add(row("清理存量连接", cdPrefix, "ok", "存量全是管理通道，无需删除"));
                state.lastCleanupAt = now;
                return;
            }
            detail.add(row("清理存量连接", cdPrefix, "ok",
                    "保留 " + keep + "，删除 " + victims));
            for (String victim : victims) {
                plan.add(step("删除存量 " + victim, taskJson("DeleteObject", victim, Map.of())));
            }
            if (multiCd) {
                // 清完后不再被任何保留连接引用的空 CD 追加可选删除（9005/已删光都容错跳过）
                for (Integer cd : data.instances(cdPrefix)) {
                    if (keptCds.contains(cd)) {
                        continue;
                    }
                    String cdObj = cdPrefix + cd + ".";
                    plan.add(step("清理空 WANConnectionDevice." + cd,
                            taskJson("DeleteObject", cdObj, Map.of("__optional", true))));
                }
            }
            // 删后确认（master WanCleanup 增强）：回读条目数，归零才算清干净，
            // 别假设删除一定成功——部分固件删上层后子对象悬空会留脏实例。
            // 设备不申报 NumberOfEntries 时走 9005 容错跳过，不阻断下发
            plan.add(step("删后确认 " + cdPrefix, taskJson("GetParameterValues", "", Map.of(
                    "names", List.of(
                            cdPrefix + "1.WANIPConnectionNumberOfEntries",
                            cdPrefix + "1.WANPPPConnectionNumberOfEntries"),
                    "deleteVerify", true))));
            // 清理只此一次：同批后面的成员不再触发任何删除；新建连接由 rememberCreated 保护
            state.lastCleanupAt = now;
        }
    }

    /**
     * 默认路由竞争提示（master 口径）：同一 WANConnectionDevice 下其他连接已挂默认网关时提醒。
     * 不阻断——是否让位由模板决定，但必须在下发记录里留下痕迹，避免"业务时通时不通、出口漂移"
     * 这类疑难杂症无从查起。
     */
    private void warnDefaultRouteClash(DeviceData data, String base, List<Map<String, String>> detail) {
        if (base.contains("__NEW__")) {
            return;
        }
        int cut = base.lastIndexOf('.', base.lastIndexOf('.') - 1);
        String connDevice = base.substring(0, cut + 1);
        for (String connType : List.of("WANIPConnection", "WANPPPConnection")) {
            for (Integer i : data.instances(connDevice + connType + ".")) {
                String other = connDevice + connType + "." + i;
                if ((base + ".").startsWith(other + ".")) {
                    continue;
                }
                String gw = data.value(other + ".DefaultGateway");
                if (gw == null || gw.isBlank() || "0".equals(gw.trim())) {
                    continue;
                }
                detail.add(row("默认路由竞争", other, "warn",
                        "该连接 DefaultGateway=" + gw.trim() + " 与本连接冲突，出口可能漂移；"
                                + "只允许上网 WAN 挂默认路由，其余业务走静态路由"));
            }
        }
    }

    /** 频段实例约定（master TemplateBuilder 口径）：2.4G 起始实例 1，5.8G 起始实例 5 */
    private static final int SSID_24G = 1;
    private static final int SSID_58G = 5;

    /** 渲染 WiFi 类意图（TR-098 / TR-181 双路径自适应） */
    private void renderWifi(String root, DeviceData data, String category, Map<String, Object> fields,
                            List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        boolean band5 = "WIFI_58G".equalsIgnoreCase(category);
        String cfgBase = wifiCfgBase(root, data);
        List<Integer> instances = data.instances(cfgBase);
        if (instances.isEmpty()) {
            throw new BusinessException("设备参数树无 WLAN 配置实例，无法下发 WiFi");
        }
        String base;
        // TR-181 机型走独立 SSID/WiFiAccessPoint 渲染器
        if (cfgBase.contains("WiFi.SSID.")) {
            base = renderWifiTr181(root, data, cfgBase, instances, band5, fields, detail);
            emitSpv(plan, "WiFi 参数包 [" + (band5 ? "5.8G" : "2.4G") + "]",
                    buildTr181WifiPackage(data, base, band5, fields, detail), detail);
            return;
        }

        // —— TR-098 / LANDevice.WLANConfiguration 路径 ——
        String refBase = cfgBase + instances.get(0) + ".";
        base = selectTr098WifiInstance(root, data, cfgBase, instances, band5, fields, detail);
        Map<String, TypedVal> values = new LinkedHashMap<>();
        // 射频开关与无线使能是两个语义：部分机型 Enable 只管射频；
        // 有独立 RadioEnabled/X_HW_RadioEnable 的机型才分开写，防止把无线总闸关了
        boolean hwRadioLeafExists = leafRef(data, base, refBase, "RadioEnabled",
                "X_HW_RadioEnable", "X_CMCC_RadioEnable") != null;
        String hw = text(fields, "radio_hardware_enable");
        if (hw != null && !hw.isBlank()) {
            if (hwRadioLeafExists) {
                putValue(values, data, base, refBase, detail, "射频总开关(radio_hardware_enable)",
                        "true".equalsIgnoreCase(hw) ? "1" : "0",
                        "RadioEnabled", "X_HW_RadioEnable", "X_CMCC_RadioEnable");
            }
            if (!hwRadioLeafExists || "true".equalsIgnoreCase(hw)) {
                // 没有独立射频叶子的机型：radio_hardware_enable=false 只能关 Enable 兜底
                putValue(values, data, base, refBase, detail, "无线开关(radio_hardware_enable 兜底)",
                        "true".equalsIgnoreCase(hw) ? "1" : "0", "Enable");
            }
        }
        if (text(fields, "radio_enable") != null) {
            putValue(values, data, base, refBase, detail, "无线开关(radio_enable)",
                    "true".equalsIgnoreCase(text(fields, "radio_enable")) ? "1" : "0", "Enable");
        }
        putValue(values, data, base, refBase, detail, "SSID", text(fields, "ssid"), "SSID");
        String channel = text(fields, "channel");
        if (channel != null && !channel.isBlank()) {
            // auto 显式写回 AutoChannelEnable=1（此前 auto 会被静默跳过，手动的 auto 改不回来自动）
            putValue(values, data, base, refBase, detail, "信道自动选择",
                    "auto".equalsIgnoreCase(channel) ? "1" : "0", "AutoChannelEnable");
            if (!"auto".equalsIgnoreCase(channel)) {
                putValue(values, data, base, refBase, detail, "信道(Channel)", channel, "Channel");
            }
        }
        String hidden = text(fields, "hidden");
        if (hidden != null) {
            putValue(values, data, base, refBase, detail, "隐藏 SSID",
                    "true".equalsIgnoreCase(hidden) ? "1" : "0",
                    "HideSSID", "X_CMCC_HideSSID", "X_CT-COM_HideSSID",
                    "X_CU_HideSSID", "X_ZTE-COM_HideSSID");
        }
        String wps = text(fields, "wps_disable");
        if ("true".equalsIgnoreCase(wps)) {
            putValue(values, data, base, refBase, detail, "WPS 关闭", "1",
                    "X_CMCC_WpsDisable", "X_CT-COM_WPSDisable", "WPSConfigurationEnable",
                    "X_CU_WpsDisable", "X_ZTE-COM_WpsDisable");
        }
        String tx = text(fields, "tx_power");
        if (tx != null && !tx.isBlank() && !"auto".equalsIgnoreCase(tx)) {
            putValue(values, data, base, refBase, detail, "发射功率", tx, "TransmitPower");
        }
        renderTr098WifiSecurity(values, data, base, refBase, fields, detail);
        renderTr098WifiBandwidth(values, data, base, refBase, fields, detail);
        emitSpv(plan, "WiFi 参数包 [" + (band5 ? "5.8G" : "2.4G") + "]", values, detail);
    }

    /**
     * TR-098 WiFi 实例挑选（master 口径增强）：模板显式 wifi_instance 优先，
     * 其次频段标记匹配（RFBand/Standard/Channel/SSID 名），最后按约定 2.4G=1、5.8G=5/最大实例。
     * 设备已申报频段信息时优先信设备（detectInstanceIs5G），机型适配才做最后兜底。
     */
    private String selectTr098WifiInstance(String root, DeviceData data, String cfgBase,
                                           List<Integer> instances, boolean band5,
                                           Map<String, Object> fields, List<Map<String, String>> detail) {
        String explicit = text(fields, "wifi_instance");
        if (explicit != null) {
            try {
                int n = Integer.parseInt(explicit.trim());
                if (instances.contains(n)) {
                    String base = cfgBase + n + ".";
                    detail.add(row("频段实例匹配", base, "ok", "模板指定 SSID 实例 " + n));
                    return base;
                }
                detail.add(row("频段实例匹配", "SSID." + n, "skip", "设备参数树无此实例，改按频段识别"));
            } catch (NumberFormatException ignore) {
            }
        }
        for (Integer i : instances) {
            String band = wifiBandOf(data, cfgBase + i);
            if (band != null && "5.8G".equals(band) == band5) {
                String base = cfgBase + i + ".";
                detail.add(row("频段实例匹配", base, "ok", "依据设备申报标记 " + band));
                return base;
            }
        }
        Integer pick = null;
        if (band5) {
            for (Integer i : instances) {
                if (detectInstanceIs5G(data, cfgBase + i + ".") && (pick == null || i > pick)) {
                    pick = i;
                }
            }
            if (pick == null) {
                for (Integer i : instances) {
                    if (i >= SSID_58G) {
                        pick = i;
                        break;
                    }
                }
            }
            if (pick == null && instances.size() > 1) {
                pick = instances.get(instances.size() - 1);
            }
        } else if (instances.contains(SSID_24G)) {
            pick = SSID_24G;
        } else {
            for (Integer i : instances) {
                if (pick == null || i < pick) {
                    pick = i;
                }
            }
        }
        if (pick == null) {
            pick = instances.get(0);
        }
        String base = cfgBase + pick + ".";
        detail.add(row("频段实例匹配", base, "ok", "未识别到频段标记，按实例约定取 " + pick
                + (instances.size() < 2 && band5 ? "（设备仅一个 SSID 实例，请核对机型适配）" : "")));
        return base;
    }

    /** 实例是否申报为 5G 频段（OperatingFrequencyBand/RFBand/Standard/SSID 名启发式） */
    private static boolean detectInstanceIs5G(DeviceData data, String base) {
        for (String leafName : List.of("OperatingFrequencyBand", "RFBand", "X_CMCC_Band",
                "X_CT-COM_Band", "X_CU_Band")) {
            String v = data.value(base + leafName);
            if (v != null && (v.contains("5") || v.toLowerCase(Locale.ROOT).contains("5g"))) {
                return true;
            }
        }
        String std = data.value(base + "Standard");
        if (std != null && std.toUpperCase(Locale.ROOT).contains("AC")) {
            return true;
        }
        String ssid = data.value(base + "SSID");
        return ssid != null && ssid.toLowerCase(Locale.ROOT).contains("5g");
    }

    /** TR-098 安全参数渲染：BeaconType 按参照实例声明类型分流（string 型写原文，其余写标准值） */
    private static void renderTr098WifiSecurity(Map<String, TypedVal> values, DeviceData data,
                                                String base, String refBase,
                                                Map<String, Object> fields,
                                                List<Map<String, String>> detail) {
        String encryption = text(fields, "encryption");
        if (encryption == null || encryption.isBlank()) {
            return;
        }
        String beaconLeaf = leafRef(data, base, refBase, "BeaconType");
        if (beaconLeaf == null) {
            detail.add(row("跳过参数", "BeaconType", "skip", "设备无 BeaconType 节点"));
            return;
        }
        putDirect(values, data, refBase, beaconLeaf, encryption);
        String algo = text(fields, "wpa_algorithm");
        if (algo != null && !algo.isBlank()) {
            putOptionalValue(values, data, base, refBase, "加密算法", algo,
                    "WPAEncryptionModes", "IEEE11iEncryptionModes",
                    "BasicEncryptionModes", "X_HW_WPAEncryptionModes");
        }
        String key = text(fields, "wpa_key");
        if (key == null || key.isBlank()) {
            // 加密方式为开放时密码无效，其余情况按行业惯例默认值下发（master 同口径）
            key = DEFAULT_WIFI_PWD;
            detail.add(row("WiFi 密码", "（未填写）", "ok", "按惯例默认值下发 " + DEFAULT_WIFI_PWD));
        }
        putValue(values, data, base, refBase, detail, "WiFi 密码", key, "KeyPassphrase",
                "PreSharedKey.1.KeyPassphrase", "PreSharedKey.1.PreSharedKey", "PreSharedKey");
    }

    /** TR-098 频宽渲染：Auto 按参照值口径写（1=20MHz/2=40MHz/3=自动），X_CMCC/X_CT-COM 分流 */
    private static void renderTr098WifiBandwidth(Map<String, TypedVal> values, DeviceData data,
                                                 String base, String refBase,
                                                 Map<String, Object> fields,
                                                 List<Map<String, String>> detail) {
        String bandwidth = text(fields, "bandwidth");
        if (bandwidth == null || bandwidth.isBlank() || "auto".equalsIgnoreCase(bandwidth)) {
            return;
        }
        String cmccLeaf = leafRef(data, base, refBase, "X_CMCC_ChannelWidth", "X_CT-COM_ChannelWidth");
        if (cmccLeaf != null) {
            putDirect(values, data, refBase, cmccLeaf, bandwidth.trim());
            return;
        }
        CpeParam refAuto = data.get(refBase + "X_HW_HTBandWidth");
        String refAutoVal = refAuto == null ? null : refAuto.value();
        boolean numeric = "Auto".equalsIgnoreCase(refAutoVal == null ? "" : refAutoVal.trim())
                || refAutoVal == null;
        putDirect(values, data, refBase, "X_HW_HTBandWidth", numeric ? "3" : "Auto");
        putOptionalValue(values, data, base, refBase, "频宽(HT)",
                numeric ? "3" : "Auto", "X_HW_HTBandWidth", "X_CU_HTBandWidth", "HTBandWidth");
    }

    /** TR-181 SSID 路径渲染（WiFi.SSID + WiFi.AccessPoint 双对象） */
    private String renderWifiTr181(String root, DeviceData data, String cfgBase,
                                   List<Integer> instances, boolean band5,
                                   Map<String, Object> fields, List<Map<String, String>> detail) {
        String refBase = cfgBase + instances.get(0) + ".";
        String base = null;
        String explicit = text(fields, "wifi_instance");
        if (explicit != null) {
            try {
                int n = Integer.parseInt(explicit.trim());
                if (instances.contains(n)) {
                    base = cfgBase + n + ".";
                }
            } catch (NumberFormatException ignore) {
            }
        }
        if (base == null) {
            for (Integer i : instances) {
                String ssid = data.value(cfgBase + i + ".SSID");
                boolean is5g = ssid != null && ssid.toLowerCase(Locale.ROOT).contains("5g");
                String lowerLink = data.value(cfgBase + i + ".LowerLayers");
                if (lowerLink != null) {
                    // LowerLayers 指向 Radio.*.{i}，Radio 的 OperatingFrequencyBand 是最可靠的频段证据
                    Matcher m = Pattern.compile("(Radio\\.\\d+)").matcher(lowerLink);
                    if (m.find()) {
                        String radioBand = data.value(root + "WiFi." + m.group(1) + ".OperatingFrequencyBand");
                        is5g = radioBand != null && radioBand.contains("5");
                    }
                }
                if (is5g == band5) {
                    base = cfgBase + i + ".";
                    break;
                }
            }
            if (base == null) {
                base = cfgBase + (band5 ? instances.get(instances.size() - 1) : instances.get(0)) + ".";
            }
        }
        detail.add(row("频段实例匹配", base, "ok",
                band5 ? "TR-181 5G SSID" : "TR-181 2.4G SSID"));
        return base;
    }

    /** TR-181 WiFi 参数包：SSID 对象 + AccessPoint 对象（安全/密钥在 AccessPoint 下） */
    private Map<String, TypedVal> buildTr181WifiPackage(DeviceData data, String ssidBase,
                                                        boolean band5, Map<String, Object> fields,
                                                        List<Map<String, String>> detail) {
        String refBase = ssidBase;
        Map<String, TypedVal> values = new LinkedHashMap<>();
        String apBase = null;
        Matcher m = Pattern.compile("(WiFi\\.SSID\\.\\d+)").matcher(ssidBase);
        if (m.find()) {
            String apProbe = ssidBase.replace(m.group(1), "WiFi.AccessPoint.");
            List<Integer> aps = data.instances(apProbe);
            if (!aps.isEmpty()) {
                // AccessPoint.{i}.SSIDReference 指回 SSID.{i} 的取关联实例；没有引用字段时取同号
                for (Integer i : aps) {
                    String ref = data.value(apProbe + i + ".SSIDReference");
                    if (ref != null && ssidBase.startsWith(ref.trim())) {
                        apBase = apProbe + i + ".";
                        break;
                    }
                }
                if (apBase == null) {
                    apBase = apProbe + aps.get(0) + ".";
                }
            }
        }
        if (apBase == null) {
            apBase = ssidBase;
        }
        String enable = text(fields, "radio_enable");
        if (enable != null) {
            putValue(values, data, ssidBase, refBase, detail, "无线开关(radio_enable)",
                    "true".equalsIgnoreCase(enable) ? "1" : "0", "Enable");
        }
        putValue(values, data, ssidBase, refBase, detail, "SSID", text(fields, "ssid"), "SSID");
        String channel = text(fields, "channel");
        if (channel != null && !channel.isBlank()) {
            putValue(values, data, ssidBase, refBase, detail, "信道自动选择",
                    "auto".equalsIgnoreCase(channel) ? "1" : "0", "AutoChannelEnable");
            if (!"auto".equalsIgnoreCase(channel)) {
                putValue(values, data, ssidBase, refBase, detail, "信道(Channel)", channel, "Channel");
            }
        }
        String hidden = text(fields, "hidden");
        if (hidden != null) {
            putValue(values, data, ssidBase, refBase, detail, "隐藏 SSID",
                    "true".equalsIgnoreCase(hidden) ? "1" : "0", "HideSSID");
        }
        if (text(fields, "wpa_key") != null || text(fields, "encryption") != null) {
            String key = text(fields, "wpa_key");
            if (key == null || key.isBlank()) {
                key = DEFAULT_WIFI_PWD;
                detail.add(row("WiFi 密码", "（未填写）", "ok", "按惯例默认值下发 " + DEFAULT_WIFI_PWD));
            }
            putValue(values, data, apBase, refBase, detail, "WiFi 密码", key, "KeyPassphrase",
                    "PreSharedKey.1.KeyPassphrase", "PreSharedKey.1.PreSharedKey", "PreSharedKey");
            String encryption = text(fields, "encryption");
            if (encryption != null && !encryption.isBlank()) {
                putOptionalValue(values, data, apBase, refBase, "安全模式", encryption,
                        "SecurityModeEnabled");
            }
        }
        return values;
    }

    /** 找设备上真正有实例的 WLAN 配置对象（TR-098 两种写法 + TR-181 WiFi.SSID） */
    private static String wifiCfgBase(String root, DeviceData data) {
        List<Integer> lds = data.instances(root + "LANDevice.");
        if (lds.isEmpty()) {
            lds = List.of(1);
        }
        for (Integer ld : lds) {
            for (String obj : List.of("LANWLANConfiguration.", "WLANConfiguration.")) {
                String b = root + "LANDevice." + ld + "." + obj;
                if (!data.instances(b).isEmpty()) {
                    return b;
                }
            }
        }
        String ssid = root + "WiFi.SSID.";
        if (!data.instances(ssid).isEmpty()) {
            return ssid;
        }
        return root + "LANDevice.1.WLANConfiguration.";
    }

    /** 判断某个 WLAN 实例属于哪个频段：设备申报标记优先，其次 Standard/Channel/SSID 名 */
    private static String wifiBandOf(DeviceData data, String instanceBase) {
        String prefix = instanceBase.endsWith(".") ? instanceBase : instanceBase + ".";
        String band = bandFromText(data.value(prefix + "X_CMCC_RFBand"));
        if (band == null) {
            band = bandFromText(data.value(prefix + "OperatingFrequencyBand"));
        }
        if (band != null) {
            return band;
        }
        String std = data.value(prefix + "Standard");
        if (std != null && !std.isBlank()) {
            String low = std.toLowerCase(Locale.ROOT);
            if (low.contains("802.11a") || low.contains("802.11ac") || low.contains("802.11ax")) {
                return "5.8G";
            }
            if (low.contains("802.11b") || low.contains("802.11g") || low.contains("802.11n")) {
                return "2.4G";
            }
        }
        String ch = data.value(prefix + "Channel");
        if (ch != null && !ch.isBlank()) {
            try {
                double c = Double.parseDouble(ch.trim().replaceAll("[^0-9.]", ""));
                if (c > 0) {
                    return c > 14 ? "5.8G" : "2.4G";
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return bandFromText(data.value(prefix + "SSID"));
    }

    /** 从 "2.4G"/"5G"/"5GHz"/"5.8GHz" 等文本里认频段 */
    private static String bandFromText(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String low = v.toLowerCase(Locale.ROOT);
        if (low.contains("2.4")) {
            return "2.4G";
        }
        if (low.contains("5.8") || low.contains("5g") || low.contains("5 ghz")) {
            return "5.8G";
        }
        return null;
    }

    /** 渲染 SIP 类意图 */
    private void renderSip(String root, DeviceData data, Map<String, Object> fields,
                           List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        String protocol = text(fields, "protocol");
        if (protocol != null && "H248".equalsIgnoreCase(protocol)) {
            throw new BusinessException("H.248 协议下发未适配，请使用 SIP 模板");
        }
        List<Integer> services = data.instances(root + "Services.VoiceService.");
        if (services.isEmpty()) {
            throw new BusinessException("设备参数树无 VoiceService 实例（不支持语音业务）");
        }
        int svc = services.get(0);
        String svcBase = root + "Services.VoiceService." + svc + ".";
        List<Integer> profiles = data.instances(svcBase + "VoiceProfile.");
        if (profiles.isEmpty()) {
            throw new BusinessException("设备参数树无 VoiceProfile 实例");
        }
        String base = svcBase + "VoiceProfile." + profiles.get(0) + ".";

        Map<String, TypedVal> values = new LinkedHashMap<>();
        // 参照实例：同 VoiceProfile 下第一个 Line（新 profile 无 Line 时用 null 兜底探测）
        List<Integer> refLines = data.instances(base + "Line.");
        String refBase = refLines.isEmpty() ? null : base + "Line." + refLines.get(0) + ".";
        String registrar = text(fields, "registrar");
        if (registrar != null && !registrar.isBlank()) {
            putValue(values, data, base, refBase, detail, "注册服务器", registrar,
                    "RegistrarServer", "SIP.RegistrarServer");
        }
        putNumber(values, data, base, refBase, detail, "注册服务器端口", fields, "registrar_port",
                "RegistrarServerPort", "SIP.RegistrarServerPort");
        String proxy = text(fields, "proxy");
        if (proxy == null || proxy.isBlank()) {
            proxy = registrar;
        }
        if (proxy != null && !proxy.isBlank()) {
            putValue(values, data, base, refBase, detail, "代理服务器", proxy,
                    "ProxyServer", "SIP.ProxyServer");
        }
        putNumber(values, data, base, refBase, detail, "代理服务器端口", fields, "proxy_port",
                "ProxyServerPort", "SIP.ProxyServerPort");
        putNumber(values, data, base, refBase, detail, "出代理端口", fields, "outbound_proxy_port",
                "OutboundProxyPort", "SIP.OutboundProxyPort");
        String outbound = text(fields, "outbound_proxy");
        if (outbound != null && !outbound.isBlank()) {
            putValue(values, data, base, refBase, detail, "出代理", outbound,
                    "OutboundProxy", "SIP.OutboundProxy");
        }
        String serverType = text(fields, "server_type");
        if (serverType != null && !serverType.isBlank()) {
            putOptionalValue(values, data, base, refBase, "服务器类型", serverType,
                    "X_CMCC_ServerType", "X_CT-COM_ServerType", "X_CU_ServerType", "ServerType");
        }
        String backup = text(fields, "backup_registrar");
        if (backup != null && !backup.isBlank()) {
            putValue(values, data, base, refBase, detail, "备用注册服务器", backup,
                    "X_CMCC_RegistrarServer2", "X_CT-COM_RegistrarServer2",
                    "X_CU_RegistrarServer2", "X_ZTE-COM_RegistrarServer2", "RegistrarServer2");
        }
        putNumber(values, data, base, refBase, detail, "备用注册端口", fields, "backup_registrar_port",
                "X_CMCC_RegistrarServerPort2", "RegistrarServerPort2");
        putNumber(values, data, base, refBase, detail, "最短位数", fields, "min_digits",
                "X_CMCC_MinDigits", "X_CU_MinDigits");
        String uaDomain = text(fields, "user_agent_domain");
        if (uaDomain != null && !uaDomain.isBlank()) {
            putValue(values, data, base, refBase, detail, "UserAgent 域", uaDomain,
                    "UserAgentDomain", "X_CMCC_UserAgentDomain", "SIP.UserAgentDomain");
        }
        String dmEnable = text(fields, "digitmap_enable");
        String dmRule = text(fields, "digitmap_rule");
        if (dmEnable != null && !dmEnable.isBlank()) {
            putOptionalValue(values, data, base, refBase, "拨号计划开关",
                    "enable".equalsIgnoreCase(dmEnable) ? "1" : "0",
                    "X_CMCC_DigitMapEnable", "X_CU_DigitMapEnable", "DigitMapEnable");
        }
        if (dmRule != null && !dmRule.isBlank() && !"disable".equalsIgnoreCase(dmEnable)) {
            putValue(values, data, base, refBase, detail, "拨号计划(DigitMap)", dmRule,
                    "DigitMap", "X_CMCC_DigitMap", "X_CU_DigitMap");
        }
        if ("true".equalsIgnoreCase(text(fields, "disable_special_dial"))) {
            putValue(values, data, base, refBase, detail, "禁用特殊拨号", "1", "X_CMCC_DisableSpecialDial");
        }

        // SIP 账号逐行写到 Line.{i}
        String userLines = text(fields, "sip_user_info");
        if (userLines != null && !userLines.isBlank()) {
            boolean preferExisting = "true".equalsIgnoreCase(text(fields, "prefer_existing_account"));
            String[] lines = userLines.split("\\r?\\n");
            List<Integer> lineInstances = data.instances(base + "Line.");
            int written = 0;
            for (String line : lines) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2 || parts[0].isBlank()) {
                    continue;
                }
                // prefer_existing_account=true：优先绑定已有同名账号的 Line 实例（覆盖重下发不变实例号）
                Integer existingLine = null;
                if (preferExisting) {
                    for (Integer i : lineInstances) {
                        String lb = base + "Line." + i + ".";
                        String u = data.value(lb + "AuthUserName");
                        if (parts[0].equals(u)) {
                            existingLine = i;
                            break;
                        }
                    }
                }
                String lineBase;
                if (existingLine != null) {
                    lineBase = base + "Line." + existingLine + ".";
                } else {
                    if (written >= lineInstances.size()) {
                        detail.add(row("SIP 账号", parts[0], "skip", "Line 实例不够（设备只暴露 "
                                + lineInstances.size() + " 个）"));
                        break;
                    }
                    lineBase = base + "Line." + lineInstances.get(written++) + ".";
                }
                putValue(values, data, lineBase, refBase, detail, "SIP 账号启用(" + parts[0] + ")", "1", "Enable");
                putValue(values, data, lineBase, refBase, detail, "SIP URI(" + parts[0] + ")",
                        "sip:" + parts[0] + "@" + (registrar == null ? "" : registrar),
                        "URI", "SIP_URI", "URI;");
                putValue(values, data, lineBase, refBase, detail, "SIP 账号名(" + parts[0] + ")",
                        parts[0], "AuthUserName");
                putValue(values, data, lineBase, refBase, detail, "SIP 密码(" + parts[0] + ")",
                        parts[1], "AuthPassword");
            }
        }
        emitSpv(plan, "SIP 参数包", values, detail);
    }

    /** 渲染 LAN 网口意图（参考 master lan()：Enable / AutoNegotiate / DuplexMode / MaxSpeed） */
    private void renderLan(String root, DeviceData data, Map<String, Object> fields,
                           List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        String ports = textList(fields, "lan_ports");
        if (ports == null || ports.isBlank()) {
            throw new BusinessException("LAN 模板未勾选网口");
        }
        String enable = text(fields, "lan_enable");
        String duplex = text(fields, "lan_duplex");
        String speed = text(fields, "lan_speed");
        boolean autoMode = "Auto".equalsIgnoreCase(duplex) || "Auto".equalsIgnoreCase(speed);
        Map<String, TypedVal> values = new LinkedHashMap<>();
        List<String> okPorts = new ArrayList<>();
        for (String p : ports.split("[,;\\s]+")) {
            if (p.isBlank()) {
                continue;
            }
            String base = lanPortPath(root, data, p);
            if (base == null) {
                detail.add(row("跳过网口", p, "skip", "设备参数树上找不到该网口"));
                continue;
            }
            if (enable != null && !enable.isBlank()) {
                putValue(values, data, base + ".", detail, "网口启停(" + p + ")",
                        "true".equalsIgnoreCase(enable) || "1".equals(enable) ? "1" : "0", "Enable");
            }
            // 双工或速率任一为自协商时写 AutoNegotiate=1，非自协商才写死 DuplexMode/MaxSpeed
            if (autoMode) {
                putValue(values, data, base + ".", detail, "自协商(" + p + ")", "1", "AutoNegotiate");
            }
            if (duplex != null && !duplex.isBlank() && !"Auto".equalsIgnoreCase(duplex)) {
                String d = duplex.substring(0, 1).toUpperCase(Locale.ROOT)
                        + duplex.substring(1).toLowerCase(Locale.ROOT);
                putValue(values, data, base + ".", detail, "双工模式(" + p + ")", d, "DuplexMode");
            }
            if (speed != null && !speed.isBlank() && !"Auto".equalsIgnoreCase(speed)) {
                putValue(values, data, base + ".", detail, "端口速率(" + p + ")", speed.trim(), "MaxSpeed");
            }
            okPorts.add(p);
        }
        if (okPorts.isEmpty()) {
            throw new BusinessException("勾选的网口在设备参数树上都不存在: " + ports);
        }
        emitSpv(plan, "LAN 网口参数包 [" + String.join(",", okPorts) + "]", values, detail);
    }

    /**
     * ManagementServer.* 参数值里指向的连接片段（如 WANIPConnection.9.）。
     * 纯数字兜底只认键名含 WAN/Instance 的参数——PeriodicInformInterval 这类数值与连接无关，不能拿来猜。
     */
    private String findManagementHint(DeviceData data) {
        String numeric = null;
        for (Map.Entry<String, CpeParam> e : data.all().entrySet()) {
            String key = e.getKey();
            if (key == null || !key.contains("ManagementServer.")) {
                continue;
            }
            String val = e.getValue() == null ? null : e.getValue().value();
            if (val == null || val.isBlank()) {
                continue;
            }
            Matcher m = WAN_CONNECTION.matcher(val);
            if (m.find()) {
                return m.group(0);
            }
            String t = val.trim();
            if (numeric == null && t.matches("\\d+")
                    && (key.toLowerCase(Locale.ROOT).contains("wan")
                    || key.toLowerCase(Locale.ROOT).contains("instance"))) {
                numeric = t;
            }
        }
        return numeric;
    }

    /** TR069 管理通道判定（master WanServiceClassifier 口径）：ServiceList → Name/路径 → ManagementServer 指向 */
    private static boolean isManagementLink(String path, DeviceData data, String hint) {
        String svc = declaredService(data, path);
        if (svc == null || svc.isBlank()) {
            String name = findLeafValue(data, path, "Name");
            svc = (name == null ? "" : name) + " " + stripRoot(path);
        }
        if (svc != null && svc.toUpperCase(Locale.ROOT).contains("TR069")) {
            return true;
        }
        return matchesManagement(path, hint);
    }

    /**
     * 该连接申报的业务类型：不挑厂商前缀，凡是连接下名字含 ServiceList 的叶子都认。
     * 标准树是 ServiceList，移动/电信/华为/中兴/联通各有私有变体（X_CMCC_/X_CT-COM_/X_HUAWEI_/X_ZTE-...），
     * 逐个枚举永远枚举不全，扫描覆盖任意写法。
     */
    private static String declaredService(DeviceData data, String connPath) {
        String direct = findLeafValue(data, connPath, "ServiceList");
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String prefix = connPath.endsWith(".") ? connPath : connPath + ".";
        for (Map.Entry<String, CpeParam> e : data.all().entrySet()) {
            String key = e.getKey();
            if (key == null || !key.startsWith(prefix)
                    || !key.toLowerCase(Locale.ROOT).contains("servicelist")) {
                continue;
            }
            String val = e.getValue() == null ? null : e.getValue().value();
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return "";
    }

    /** 去掉数据模型根节点，避免根名本身命中关键字 */
    private static String stripRoot(String path) {
        for (String root : List.of("InternetGatewayDevice.", "Device.")) {
            if (path.regionMatches(true, 0, root, 0, root.length())) {
                return path.substring(root.length());
            }
        }
        return path;
    }

    /** 连接路径与管理通道线索是否对得上（master WanCleanup.matchesManagement） */
    private static boolean matchesManagement(String path, String hint) {
        if (path == null || hint == null || hint.isBlank()) {
            return false;
        }
        String text = hint.trim();
        if (text.matches("\\d+")) {
            Integer own = instanceNumberOf(path);
            return own != null && own == Integer.parseInt(text);
        }
        if (path.contains(text)) {
            return true;
        }
        Integer hintInstance = instanceNumberOf(text);
        Integer own = instanceNumberOf(path);
        return hintInstance != null && own != null && hintInstance.equals(own);
    }

    /** 路径里的连接实例号，形如 WANIPConnection.9. 取 9；认不出返回 null */
    private static Integer instanceNumberOf(String path) {
        if (path == null) {
            return null;
        }
        Matcher m = WAN_CONNECTION.matcher(path);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 渲染设备管理类意图 */
    private void renderOther(String root, DeviceData data, Map<String, Object> fields,
                             List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        Map<String, TypedVal> values = new LinkedHashMap<>();
        boolean reboot = "true".equalsIgnoreCase(text(fields, "reboot_after"));

        String telnet = text(fields, "telnet_enable");
        if ("true".equalsIgnoreCase(telnet)) {
            putValue(values, data, root, detail, "Telnet 开关", "1",
                    "X_CMCC_TelnetEnable", "TelnetEnable", "X_CT-COM_TelnetEnable",
                    "X_HW_TelnetEnable", "X_CU_TelnetEnable");
        }
        putValue(values, data, root, detail, "Telnet 账号", text(fields, "telnet_username"),
                "X_CMCC_TelnetUserName", "X_HW_TelnetUserName", "TelnetUserName");
        putValue(values, data, root, detail, "Telnet 密码", text(fields, "telnet_password"),
                "X_CMCC_TelnetPassword", "X_HW_TelnetPassword", "TelnetPassword");
        putValue(values, data, root, detail, "Telnet 端口", text(fields, "telnet_port"),
                "X_HW_TelnetPort", "TelnetPort");
        putValue(values, data, root, detail, "超管账号", text(fields, "super_account"),
                "X_CMCC_AdminUserName", "X_HW_SuperUserName", "SuperUserName", "AdminUserName");
        putValue(values, data, root, detail, "超管密码", text(fields, "super_password"),
                "X_CMCC_AdminPassword", "X_HW_SuperPassword", "SuperPassword", "AdminPassword");
        if ("true".equalsIgnoreCase(text(fields, "firewall_disable"))) {
            putValue(values, data, root, detail, "防火墙关闭", "0",
                    "X_CMCC_FirewallEnable", "FirewallEnabled", "X_HW_FirewallEnabled");
        }
        String igmp = text(fields, "igmp_support");
        if ("enable".equalsIgnoreCase(igmp) || "disable".equalsIgnoreCase(igmp)) {
            putValue(values, data, root, detail, "IGMP 开关",
                    "enable".equalsIgnoreCase(igmp) ? "1" : "0",
                    "X_CMCC_IGMPEnable", "IGMPEnabled", "X_CT-COM_IGMPEnable",
                    "X_HW_IGMPEnable", "X_CU_IGMPEnable");
        }
        String mode = text(fields, "multicast_mode");
        if (mode != null && !"auto".equalsIgnoreCase(mode) && !mode.isBlank()) {
            putValue(values, data, root, detail, "组播模式", mode.toLowerCase(Locale.ROOT),
                    "X_CMCC_IGMPMode", "IGMPMode", "X_CT-COM_IGMPMode",
                    "X_HW_IGMPMode", "X_CU_IGMPMode");
        }
        String led = text(fields, "led_control");
        if ("on".equalsIgnoreCase(led) || "off".equalsIgnoreCase(led)) {
            putValue(values, data, root, detail, "指示灯",
                    "on".equalsIgnoreCase(led) ? "1" : "0",
                    "X_CMCC_LedEnable", "LEDControl", "X_CT-COM_LedControl",
                    "X_HW_LedEnable", "X_CU_LedEnable");
        }
        String roaming = text(fields, "wifi_roaming");
        if ("enable".equalsIgnoreCase(roaming) || "disable".equalsIgnoreCase(roaming)) {
            putValue(values, data, root, detail, "WiFi 漫游",
                    "enable".equalsIgnoreCase(roaming) ? "1" : "0",
                    "X_CMCC_WiFiRoamingEnable", "X_CT-COM_WiFiRoamingEnable",
                    "X_HW_WiFiRoamingEnable", "X_CU_WiFiRoamingEnable");
        }
        putNumber(values, data, root, detail, "漫游阈值 2.4G", fields, "roaming_threshold_24g",
                "X_CMCC_RoamingThreshold24G", "X_HW_RoamingThreshold24G");
        putNumber(values, data, root, detail, "漫游阈值 5.8G", fields, "roaming_threshold_58g",
                "X_CMCC_RoamingThreshold58G", "X_HW_RoamingThreshold58G");

        emitSpv(plan, "设备管理参数包", values, detail);
        if (reboot) {
            plan.add(step("重启光猫", taskJson("Reboot", "", Map.of())));
        }
    }

    /** 拼 SPV 步骤，全空不发（TypedVal 携带真实 xsd 类型，不再恒写 xsd:string） */
    private static void emitSpv(List<Map.Entry<String, String>> plan, String desc,
                                Map<String, TypedVal> values, List<Map<String, String>> detail) {
        List<Map<String, String>> valid = new ArrayList<>();
        for (Map.Entry<String, TypedVal> e : values.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                detail.add(row("渲染 " + desc, "", "skip", "叶子未匹配到设备参数树，已跳过"));
                continue;
            }
            if (e.getValue() == null || e.getValue().value() == null || e.getValue().value().isBlank()) {
                continue;
            }
            Map<String, String> one = new LinkedHashMap<>();
            one.put("name", e.getKey());
            one.put("value", e.getValue().value());
            one.put("type", e.getValue().type());
            valid.add(one);
        }
        if (valid.isEmpty()) {
            detail.add(row("渲染 " + desc, "", "skip", "没有可下发的参数（叶子未匹配到，机型未适配？）"));
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("values", valid);
        plan.add(step(desc, taskJson("SetParameterValues", "", params)));
    }

    /**
     * 写入一个参数（多级降级链）：
     * ① leafStrict 精确探测（base/refBase 下候选叶子）→ ② putDeepAny 全树深搜同名叶子
     * → ③ putDirect 直写兜底（新增实例路径）→ ④ 显式记 skip。
     * 类型按参照实例声明推断（xsd:string 恒类型导致的 9003 根因）。
     */
    private static void putValue(Map<String, TypedVal> values, DeviceData data, String base,
                                 List<Map<String, String>> detail, String fieldDesc,
                                 String value, String... candidates) {
        putValue(values, data, base, null, detail, fieldDesc, value, candidates);
    }

    private static void putValue(Map<String, TypedVal> values, DeviceData data, String base, String refBase,
                                 List<Map<String, String>> detail, String fieldDesc,
                                 String value, String... candidates) {
        if (value == null || value.isBlank()) {
            return;
        }
        String path = leafRef(data, base, refBase, candidates);
        if (path != null) {
            CpeParam refParam = findTemplateParam(data, refBase, extractRelativeLeaf(path));
            String xsd = inferXsdType(refParam, extractRelativeLeaf(path));
            values.put(path, new TypedVal(path, normalizeValueForType(value, xsd), xsd));
            return;
        }
        if (putDeepAny(values, data, base, refBase, detail, value, candidates)) {
            return;
        }
        if (putDirect(values, data, refBase, base + candidates[0], value)) {
            return;
        }
        detail.add(row("跳过参数", fieldDesc, "skip",
                "设备参数树上没有叶子 " + String.join(" / ", candidates) + "（机型未适配？）"));
    }

    /** 次要调优参数写入：叶子不存在时静默跳过（只记一行 skip，不算失败） */
    private static void putOptionalValue(Map<String, TypedVal> values, DeviceData data, String base,
                                         String refBase, String fieldDesc, String value, String... candidates) {
        if (value == null || value.isBlank()) {
            return;
        }
        String path = leafRef(data, base, refBase, candidates);
        if (path == null) {
            return;
        }
        CpeParam refParam = findTemplateParam(data, refBase, extractRelativeLeaf(path));
        String xsd = inferXsdType(refParam, extractRelativeLeaf(path));
        values.put(path, new TypedVal(path, normalizeValueForType(value, xsd), xsd));
    }

    /**
     * 直写已知完整路径（调用方自行做过叶子探测）：存在才写，或允许 __NEW__ 新实例路径；
     * 返回是否真的写入。类型仍按参照实例/叶子名推断。
     */
    private static boolean putDirect(Map<String, TypedVal> values, DeviceData data, String refBase,
                                     String path, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (!data.has(path) && !path.contains("__NEW__")) {
            return false;
        }
        String leafName = extractRelativeLeaf(path);
        CpeParam refParam = findTemplateParam(data, refBase, leafName);
        String xsd = inferXsdType(refParam, leafName);
        values.put(path, new TypedVal(path, normalizeValueForType(value, xsd), xsd));
        return true;
    }

    /** 直写但允许空串值（部分开关需要显式写空清掉） */
    private static boolean putDirectAllowEmpty(Map<String, TypedVal> values, DeviceData data, String refBase,
                                               String path, String value) {
        if (value == null) {
            return false;
        }
        if (!data.has(path) && !path.contains("__NEW__")) {
            return false;
        }
        String leafName = extractRelativeLeaf(path);
        CpeParam refParam = findTemplateParam(data, refBase, leafName);
        String xsd = inferXsdType(refParam, leafName);
        values.put(path, new TypedVal(path, value.trim(), xsd));
        return true;
    }

    /**
     * 全树深搜同名叶子写入（候选名在 base 的父对象下任意子对象里出现都算命中）：
     * 处理设备把业务参数放在非标准子对象下的机型（叶子名对、路径深一层）。
     */
    private static boolean putDeepAny(Map<String, TypedVal> values, DeviceData data, String base, String refBase,
                                      List<Map<String, String>> detail, String value, String... candidates) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String parentNoDot = base.endsWith(".") ? base.substring(0, base.length() - 1) : base;
        for (String c : candidates) {
            for (Map.Entry<String, CpeParam> e : data.all().entrySet()) {
                String key = e.getKey();
                if (key == null || !key.endsWith("." + c)) {
                    continue;
                }
                String instPath = key.substring(0, key.length() - c.length() - 1);
                String parentPath = instPath.contains(".")
                        ? instPath.substring(0, instPath.lastIndexOf('.') + 1) : "";
                if (!parentNoDot.startsWith(parentPath)) {
                    continue;
                }
                String xsd = inferXsdType(e.getValue(), c);
                values.put(key, new TypedVal(key, normalizeValueForType(value, xsd), xsd));
                return true;
            }
            if (refBase != null) {
                String refNoDot = refBase.endsWith(".") ? refBase.substring(0, refBase.length() - 1) : refBase;
                for (Map.Entry<String, CpeParam> e : data.all().entrySet()) {
                    String key = e.getKey();
                    if (key == null || !key.startsWith(refNoDot) || !key.endsWith("." + c)) {
                        continue;
                    }
                    String xsd = inferXsdType(e.getValue(), c);
                    values.put(key, new TypedVal(key, normalizeValueForType(value, xsd), xsd));
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 写 WAN 寻址方式：master 适配库里 WAN_DHCP 的 path_addr_type 是 IPAddressingType、val=DHCP，
     * WAN_STATIC 是同叶子 val=Static。这里同时兼容厂商私有写法；
     * 参数树没有申报该叶子时不能静默跳过（否则设备保持默认 Static），
     * 按 master 口径强制写 IPAddressingType，由设备/回读校验兜底。
     */
    private static void putAddressingType(Map<String, TypedVal> values, DeviceData data, String base,
                                          String refBase, List<Map<String, String>> detail, String value) {
        String[] candidates = {"IPAddressingType", "AddressingType", "X_CMCC_IPAddressingType",
                "X_CT-COM_IPAddressingType", "X_CMCC_AddressingType"};
        String path = leafRef(data, base, refBase, candidates);
        if (path == null) {
            path = base + candidates[0];
            detail.add(row("寻址方式路径", path, "ok",
                    "参数树未申报寻址叶子，按 master 适配口径写 IPAddressingType"));
        }
        values.put(path, new TypedVal(path, value, "xsd:string"));
    }

    /** 叶子在参数树里的声明类型；__NEW__ 基础下参照同类型已有实例，查不到返回 null */
    private static String declaredType(DeviceData data, String base, String leafName) {
        String refPath = null;
        if (base.contains("__NEW__")) {
            String parent = base.substring(0, base.indexOf("__NEW__"));
            List<Integer> refs = data.instances(parent);
            if (!refs.isEmpty()) {
                refPath = parent + refs.get(0) + "." + leafName;
            }
        }
        CpeParam p = data.get(base + leafName);
        if (p == null && refPath != null) {
            p = data.get(refPath);
        }
        return p == null ? null : p.type();
    }

    private static boolean isNumericType(String type) {
        if (type == null) {
            return false;
        }
        String t = type.toLowerCase(Locale.ROOT);
        return "unsignedint".equals(t) || "int".equals(t);
    }

    /**
     * 探测候选叶子路径，都不存在返回 null；__NEW__ 基础下用 refBase（参照实例）做存在性参照，
     * 未给 refBase 时自动按 __NEW__ 父对象第一个实例兜底。
     */
    private static String leaf(DeviceData data, String base, String... candidates) {
        return leafRef(data, base, null, candidates);
    }

    private static String leafRef(DeviceData data, String base, String refBase, String... candidates) {
        if (refBase == null && base.contains("__NEW__")) {
            String parent = base.substring(0, base.indexOf("__NEW__"));
            List<Integer> refs = data.instances(parent);
            if (!refs.isEmpty()) {
                refBase = parent + refs.get(0) + ".";
            }
        }
        for (String c : candidates) {
            String path = base + c;
            if (data.has(path)) {
                return path;
            }
            if (refBase != null && data.has(refBase + c)) {
                return path;
            }
        }
        if (refBase == null && base.contains("__NEW__") && candidates.length > 0) {
            return base + candidates[0];
        }
        return null;
    }

    /** 参照实例基路径下找同名叶子声明（找不到返回 null；refBase 为空时无从参照） */
    private static CpeParam findTemplateParam(DeviceData data, String refBase, String leafName) {
        if (refBase == null || leafName == null || leafName.isBlank()) {
            return null;
        }
        String direct = refBase + leafName;
        if (data.has(direct)) {
            return data.get(direct);
        }
        for (Map.Entry<String, CpeParam> e : data.all().entrySet()) {
            String key = e.getKey();
            if (key != null && key.startsWith(refBase) && key.endsWith("." + leafName)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 路径里的叶子名（最后一段） */
    private static String extractRelativeLeaf(String path) {
        if (path == null) {
            return "";
        }
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('.');
        return idx < 0 ? p : p.substring(idx + 1);
    }

    /** 参照声明类型 → xsd 类型（推断不出时按叶子名启发式，恒 xsd:string 是 9003 的历史根因） */
    private static String inferXsdType(CpeParam ref, String leafName) {
        String t = ref == null ? null : ref.type();
        if (t != null && !t.isBlank()) {
            String b = baseType(t).toLowerCase(Locale.ROOT);
            if (b.endsWith("unsignedint")) {
                return "xsd:unsignedInt";
            }
            if (b.endsWith("unsignedlong")) {
                return "xsd:unsignedLong";
            }
            if (b.endsWith("int")) {
                return "xsd:int";
            }
            if (b.endsWith("boolean")) {
                return "xsd:boolean";
            }
            if (b.endsWith("datetime")) {
                return "xsd:dateTime";
            }
            if (b.endsWith("string")) {
                return "xsd:string";
            }
            return "xsd:string";
        }
        String l = leafName == null ? "" : leafName.toLowerCase(Locale.ROOT);
        if (l.endsWith("enable") || l.endsWith("enabled") || l.endsWith("disable")
                || l.contains("boolean")) {
            return "xsd:boolean";
        }
        return "xsd:string";
    }

    /** 值按 xsd 类型归一：boolean 1/0（部分固件不认 true/false），数值去空白 */
    private static String normalizeValueForType(String value, String xsdType) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        if ("xsd:boolean".equals(xsdType)) {
            Boolean b = parseBoolLoose(v);
            return b != null ? (b ? "1" : "0") : v;
        }
        return v;
    }

    private static String findLeafValue(DeviceData data, String instanceBase, String... candidates) {
        for (String c : candidates) {
            String path = instanceBase + "." + c;
            if (data.has(path)) {
                return data.value(path);
            }
        }
        return null;
    }

    /** 数值类字段写入 */
    private static void putNumber(Map<String, TypedVal> values, DeviceData data, String base,
                                  List<Map<String, String>> detail, String fieldDesc,
                                  Map<String, Object> fields, String key, String... leafNames) {
        putNumber(values, data, base, null, detail, fieldDesc, fields, key, leafNames);
    }

    private static void putNumber(Map<String, TypedVal> values, DeviceData data, String base, String refBase,
                                  List<Map<String, String>> detail, String fieldDesc,
                                  Map<String, Object> fields, String key, String... leafNames) {
        String v = text(fields, key);
        if (v == null || v.isBlank()) {
            return;
        }
        putValue(values, data, base, refBase, detail, fieldDesc, v, leafNames);
    }

    /** 把勾选端口翻成设备认的 LAN 口路径串 */
    private static String lanInterfaceValue(String root, DeviceData data, String value) {
        StringBuilder sb = new StringBuilder();
        for (String part : value.split("[,;\\s]+")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String port = lanPortPath(root, data, p);
            if (port == null) {
                // 个别端口（如 PON）映射不出来时跳过它，不再让整条绑定作废
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(port);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** LAN{n} → 设备参数树上真实存在的 LAN 口路径，映射不出来返回 null */
    private static String lanPortPath(String root, DeviceData data, String port) {
        String p = port.trim();
        if (p.startsWith(root)) {
            return p;
        }
        String upper = p.toUpperCase(Locale.ROOT);
        if (upper.matches("SSID\\d+")) {
            // 无线 SSID 也可以作为桥接绑定端口
            int ssid = Integer.parseInt(p.substring(4));
            for (String obj : List.of(root + "LANDevice.1.LANWLANConfiguration.",
                    root + "LANDevice.1.WLANConfiguration.", root + "WiFi.SSID.")) {
                if (data.instances(obj).contains(ssid)) {
                    return obj + ssid;
                }
            }
            return null;
        }
        if (!upper.matches("LAN\\d+")) {
            return null;
        }
        int no = Integer.parseInt(p.substring(3));
        for (Integer ld : data.instances(root + "LANDevice.")) {
            String path = root + "LANDevice." + ld + ".LANEthernetInterfaceConfig." + no;
            if (data.has(path)) {
                return path;
            }
        }
        int idx = 0;
        for (Integer i : data.instances(root + "Ethernet.Interface.")) {
            String base = root + "Ethernet.Interface." + i;
            String upstream = findLeafValue(data, base, "Upstream");
            if ("true".equalsIgnoreCase(upstream) || "1".equals(upstream)) {
                continue;
            }
            if (++idx == no) {
                return base;
            }
        }
        return null;
    }

    /** SPV 参数级被拒的错误码 */
    private static final Pattern SPV_REJECT = Pattern.compile("\\b900[3-8]\\b");

    /** WAN 连接路径末段，形如 WANIPConnection.9. */
    private static final Pattern WAN_CONNECTION = Pattern.compile("WAN(?:IP|PPP)Connection\\.(\\d+)\\.");

    /** 清理台账窗口：同批多个 WAN 成员（整包多模板）只清理一次 */
    private static final long WAN_CLEANUP_WINDOW_MS = 5 * 60_000L;

    /** 每台设备一把清理锁：同一批次的多个业务任务不能同时清点、删除连接（master 口径） */
    private final Map<String, Object> wanCleanupLocks = new ConcurrentHashMap<>();
    /** 每台设备的下发互斥锁：并发下发线程池下同 SN 串行，任务不交错 */
    private final Map<String, Object> deliverLocks = new ConcurrentHashMap<>();
    /** 每台设备的清理台账：窗口内不重复清理，本批次新建的连接永不进删除清单 */
    private final Map<String, WanCleanupState> wanCleanupLedger = new ConcurrentHashMap<>();

    private static final class WanCleanupState {
        volatile long lastCleanupAt;
        final Set<String> createdPaths = ConcurrentHashMap.newKeySet();

        boolean fresh() {
            return lastCleanupAt > 0 && System.currentTimeMillis() - lastCleanupAt < WAN_CLEANUP_WINDOW_MS;
        }

        void remember(String prefix) {
            createdPaths.add(prefix);
        }
    }

    /** SPV 参数（带推断的 xsd 类型，替代恒 xsd:string——类型错是 9003 整包报废的历史根因） */
    private record TypedVal(String path, String value, String type) {
    }

    /** 存量 WAN 连接快照（多 CD 全量枚举用） */
    private record ExistingWan(int wanDevice, int cd, String connType, int instance,
                               String base, boolean tr069, String serviceList, String vlanId) {
    }

    /** 变量占位符，如 %room%（口径与 master TemplateVariables 一致） */
    private static final Pattern VAR = Pattern.compile("%([A-Za-z0-9_]+)%");

    /** 房间号没登记时的兜底值 */
    private static final String DEFAULT_ROOM = "0000";
    /** WiFi 密码没填写时的兜底值 */
    private static final String DEFAULT_WIFI_PWD = "88888888";

    /** IP 协议版本：X_CMCC_IPMode 设备口径是数值（master 实测口径） */
    private static final Map<String, String> IP_PROTOCOL_MODES = Map.of(
            "ipv4", "1",
            "ipv6", "2",
            "ipv4+ipv6", "3",
            "ipv4v6", "3",
            "ipv4/v6", "3",
            "dual", "3");

    /** IP 协议写法归一：IPv4/IPv6/IPv4+IPv6 → 1/2/3，无法识别按 IPv4 */
    private static String normalizeIpProtocol(String value) {
        String v = value.trim();
        if (v.isEmpty() || v.matches("\\d+")) {
            return v;
        }
        String mapped = IP_PROTOCOL_MODES.get(v.toLowerCase(Locale.ROOT).replace(" ", ""));
        if (mapped == null) {
            log.warn("[下发] IP 协议值无法识别，按 IPv4 下发: {}", value);
            return "1";
        }
        return mapped;
    }

    /**
     * 变量取值表：%room% 优先字段 room，其次设备台账 room_no，最后兜底 0000；
     * %desc% 取台账 remark；%wifi_pwd% 兜底 88888888；其余字段键值以 %键% 直通。
     */
    private Map<String, String> buildVars(String sn, Map<String, Object> fields) {
        Map<String, String> vars = new LinkedHashMap<>();
        Map<String, Object> device = null;
        try {
            device = store.findDevice(sn);
        } catch (Exception ignore) {
        }
        String room = text(fields, "room");
        if (room == null || room.isBlank()) {
            Object dr = device == null ? null : device.get("room_no");
            room = dr == null ? "" : String.valueOf(dr).trim();
        }
        vars.put("%room%", room == null || room.isBlank() ? DEFAULT_ROOM : room);
        Object remark = device == null ? null : device.get("remark");
        vars.put("%desc%", remark == null ? "" : String.valueOf(remark).trim());
        String wifiPwd = text(fields, "wpa_key");
        vars.put("%wifi_pwd%", wifiPwd == null || wifiPwd.isBlank() ? DEFAULT_WIFI_PWD : wifiPwd.trim());
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (e.getValue() != null) {
                vars.putIfAbsent("%" + e.getKey() + "%", String.valueOf(e.getValue()).trim());
            }
        }
        return vars;
    }

    /** 把字段值里的 %var% 换成真实值；变量值本身可能还是变量，最多解两层 */
    private Map<String, Object> resolveFields(Map<String, Object> fields, Map<String, String> vars,
                                              List<Map<String, String>> detail) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s && s.indexOf('%') >= 0) {
                out.put(e.getKey(), resolveVars(s, vars, e.getKey(), detail));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    /** 变量替换；识别不了的 %token% 直接报错，避免把字面量下发给设备（master 同口径） */
    private String resolveVars(String val, Map<String, String> vars, String field,
                               List<Map<String, String>> detail) {
        String out = val;
        for (int depth = 0; depth < 2; depth++) {
            Matcher it = VAR.matcher(out);
            if (!it.find()) {
                break;
            }
            StringBuilder sb = new StringBuilder();
            it.reset();
            List<String> missing = new ArrayList<>();
            while (it.find()) {
                String token = it.group();
                String rep = vars.get(token);
                if (rep == null) {
                    missing.add(token);
                    rep = token;
                }
                it.appendReplacement(sb, Matcher.quoteReplacement(rep == null ? "" : rep));
            }
            it.appendTail(sb);
            out = sb.toString();
            if (!missing.isEmpty()) {
                detail.add(row("变量缺失", String.join(", ", missing), "fail",
                        "字段 " + field + " 里的变量没有值，下发终止"));
                throw new BusinessException("字段 " + field + " 的变量没有值: " + String.join(", ", missing));
            }
        }
        return out.trim();
    }

    /** AddObject 响应实例号：result_content 形如 {"instanceNumber":2,"status":0} */
    private static final Pattern INSTANCE_NUMBER = Pattern.compile("(?i)\"instanceNumber\"\\s*:\\s*(\\d+)");
    /** 兜底宽松解析 */
    private static final Pattern INSTANCE_NUMBER_LOOSE = Pattern.compile("(?i)instance[^0-9]{0,20}(\\d+)");

    private static class LocateResult {
        final List<Map<String, String>> accepted = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();
    }

    /** 从 AddObject 任务结果解析设备返回的实例号 */
    private static String parseInstanceNumber(String result) {
        if (result == null || result.isBlank() || "null".equals(result)) {
            return null;
        }
        Matcher m = INSTANCE_NUMBER.matcher(result);
        if (m.find()) {
            return m.group(1);
        }
        m = INSTANCE_NUMBER_LOOSE.matcher(result);
        return m.find() ? m.group(1) : null;
    }

    /** 解析 renderWan 快照里的存量实例号清单（逗号分隔） */
    private static Set<Integer> parseKnownInstances(String known) {
        Set<Integer> out = new LinkedHashSet<>();
        if (known != null && !known.isBlank() && !"null".equals(known)) {
            for (String p : known.split(",")) {
                try {
                    out.add(Integer.parseInt(p.trim()));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return out;
    }

    /**
     * AddObject 后从落库参数树实测新实例号（等会话收尾落库，最多等 ~20s）。
     * 新实例 = 参数树里出现、且不在 AddObject 前存量快照中的那一个。
     */
    private String rediscoverNewInstance(String sn, String connObj, Set<Integer> known) {
        for (int i = 0; i < 20; i++) {
            try {
                DeviceData d = new DeviceData();
                d.loadAll(store.loadParams(sn));
                for (Integer inst : d.instances(connObj)) {
                    if (known == null || !known.contains(inst)) {
                        return String.valueOf(inst);
                    }
                }
            } catch (Exception e) {
                log.warn("[下发] 实例号实测读参数树失败 sn={}: {}", sn, e.getMessage());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** SPV 参数路径里的猜错实例号统一替换为实测号 */
    private static List<Map<String, String>> fixInstancePaths(List<Map<String, String>> values,
                                                              String connObj, String guessed, String real) {
        String wrongPrefix = connObj + guessed + ".";
        String rightPrefix = connObj + real + ".";
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> v : values) {
            Map<String, String> nv = new LinkedHashMap<>(v);
            String name = nv.get("name");
            if (name != null && name.startsWith(wrongPrefix)) {
                nv.put("name", rightPrefix + name.substring(wrongPrefix.length()));
            }
            out.add(nv);
        }
        return out;
    }

    /** 整批 SPV 被拒时逐条重发定位，设备不认的开关类记 skip */
    private void locateRejected(String sn, List<Map<String, String>> values,
                                List<Map<String, String>> detail, LocateResult out) {
        for (Map<String, String> v : values) {
            String name = String.valueOf(v.get("name"));
            String raw = v.get("value") == null ? "" : v.get("value");
            if (values.size() > 1 && tryWriteOne(sn, v)) {
                out.accepted.add(v);
                continue;
            }
            Map<String, String> accepted = null;
            for (Map<String, String> alt : alternates(v)) {
                if (tryWriteOne(sn, alt)) {
                    accepted = alt;
                    break;
                }
            }
            if (accepted != null) {
                log.info("[下发] 参数换写法后被设备接受: {}={}（type {} -> {}）",
                        name, accepted.get("value"), v.get("type"), accepted.get("type"));
                out.accepted.add(accepted);
                continue;
            }
            // 次要调优参数（优先级策略/频宽/信道策略等）设备不认时降级为 skip，
            // 不再整批失败——但连接启用/寻址/业务绑定等核心开关维持失败口径
            if (!isSwitch(name) && isOptionalSecondaryLeaf(name)) {
                log.info("[下发] 次要调优参数设备不认，跳过不阻断: {}={}", name, raw);
                detail.add(row("参数降级跳过", name, "skip",
                        "次要调优参数，逐条重发（含换写法）仍被设备拒绝，不阻断整批下发"));
                continue;
            }
            if (isSwitch(name)) {
                log.warn("[下发] 启用开关类参数设备不接受: {}（按失败处理，不再静默跳过）", name);
                detail.add(row("参数被拒", name, "fail",
                        "开关类参数（Enable/NATEnabled 等）逐条重发（含换写法）仍被设备拒绝，"
                                + "新连接可能无法启用，按失败处理"));
                out.rejected.add(name + "=" + raw + "（type=" + v.get("type") + "）");
                continue;
            }
            out.rejected.add(name + "=" + raw + "（type=" + v.get("type") + "）");
            detail.add(row("参数被拒", name, "fail", "逐条重发（含换写法）仍被设备拒绝，值=" + raw));
        }
    }

    /** 次要调优参数判定：被拒时降级 skip 不阻断整批（核心开关除外） */
    private static boolean isOptionalSecondaryLeaf(String path) {
        int idx = path.lastIndexOf('.');
        String leafName = idx < 0 ? path : path.substring(idx + 1);
        String u = leafName.toUpperCase(Locale.ROOT);
        return u.contains("PRIPOLICY") || u.contains("PRI POLICY") || u.endsWith("PRIMARK")
                || u.endsWith("8021PMARK") || u.endsWith("802-1PMARK") || u.endsWith("1PMARK")
                || u.contains("HTBANDWIDTH") || u.contains("CHANNELWIDTH")
                || u.contains("CHANNELMODE") || u.contains("CHANNELBAND")
                || u.endsWith("PRIVALUE") || u.contains("SCHEDULING")
                || u.contains("PREAMBLE") || u.contains("FRAGTHRESHOLD")
                || u.contains("RTSTHRESHOLD") || u.contains("DTIMINTERVAL")
                || u.endsWith("BEACONINTERVAL") || u.contains("BASICRATE")
                || u.contains("OPERATIONALRATE") || u.contains("TRANSMITRATE")
                || u.endsWith("GUARDINTERVAL") || u.endsWith("STBC")
                || u.endsWith("VLANENABLED") || u.endsWith("VLANMODE")
                || u.endsWith("VLANPRI") || u.endsWith("DHCPENABLE");
    }

    /** 单参数 SPV 试写 */
    private boolean tryWriteOne(String sn, Map<String, String> item) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("values", List.of(new LinkedHashMap<>(item)));
        Map<String, Object> r = runTask(sn, "SetParameterValues", "", params, "locate");
        return r != null && Boolean.TRUE.equals(r.get("ok"));
    }

    /** 生成同一参数的多种写法备选 */
    private static List<Map<String, String>> alternates(Map<String, String> v) {
        List<Map<String, String>> alts = new ArrayList<>();
        String name = String.valueOf(v.get("name"));
        String raw = v.get("value") == null ? "" : v.get("value");
        String origType = baseType(v.get("type"));
        if (!"string".equals(origType)) {
            alts.add(item(name, raw, "string"));
        }
        String bool = booleanValue(raw);
        if (!bool.equals(raw) && !"boolean".equals(origType)) {
            alts.add(item(name, bool, "boolean"));
        }
        if (raw.matches("\\d+") && !"unsignedInt".equals(origType)) {
            alts.add(item(name, raw, "unsignedInt"));
        }
        if (raw.matches("\\d+") && !"int".equals(origType)) {
            alts.add(item(name, raw, "int"));
        }
        // 加密写法互换（WPAand11i↔WPA/WPA2、TKIPandAES↔TKIP/AES）：机型口径不一
        if ("WPAand11i".equalsIgnoreCase(raw)) {
            alts.add(item(name, "WPA/WPA2", "string"));
        } else if ("WPA/WPA2".equalsIgnoreCase(raw)) {
            alts.add(item(name, "WPAand11i", "string"));
        }
        if ("TKIPandAES".equalsIgnoreCase(raw)) {
            alts.add(item(name, "TKIP/AES", "string"));
        } else if ("TKIP/AES".equalsIgnoreCase(raw)) {
            alts.add(item(name, "TKIPandAES", "string"));
        }
        return alts;
    }

    /** xsi:type 去前缀 */
    private static String baseType(String t) {
        if (t == null) {
            return "string";
        }
        String s = t.trim();
        int i = s.indexOf(':');
        return i < 0 ? s : s.substring(i + 1);
    }

    private static Map<String, String> item(String name, String value, String type) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        m.put("type", type);
        return m;
    }

    /** 布尔取值归一 */
    private static String booleanValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if ("1".equals(value) || "true".equalsIgnoreCase(value)) {
            return "true";
        }
        if ("0".equals(value) || "false".equalsIgnoreCase(value)) {
            return "false";
        }
        return raw;
    }

    /** 判断是否启用开关类参数 */
    private static boolean isSwitch(String path) {
        int idx = path.lastIndexOf('.');
        String leafName = idx < 0 ? path : path.substring(idx + 1);
        return leafName.matches("(?i).*(enable|enabled|disable|disabled)$");
    }

    private static final String SERVICE_INTERNET = "INTERNET";

    private static final List<String> SERVICE_TOKENS =
            List.of("INTERNET", "TR069", "VOIP", "IPTV", "OTHER", "OTT");

    /** 业务模式归一成设备口径（桥接连接没有 INTERNET 业务，归 OTHER） */
    private static String normalizeServiceList(String raw, boolean bridge) {
        String out = normalizeServiceList(raw);
        if (bridge && "INTERNET".equals(out)) {
            return "OTHER";
        }
        return out;
    }

    /** 业务模式归一成设备口径 */
    private static String normalizeServiceList(String raw) {
        if (raw == null || raw.isBlank()) {
            return SERVICE_INTERNET;
        }
        List<String> out = new ArrayList<>();
        for (String token : raw.split("[,，、+/\\s]+")) {
            String v = token.trim().toUpperCase(Locale.ROOT);
            if (v.isEmpty()) {
                continue;
            }
            if (!SERVICE_TOKENS.contains(v)) {
                log.warn("业务模式取值不在设备口径内，按 INTERNET 处理: {}", token);
                v = SERVICE_INTERNET;
            }
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        return out.isEmpty() ? SERVICE_INTERNET : String.join(",", out);
    }

    /** 意图字段的字符串取值 */
    private static String text(Map<String, Object> fields, String key) {
        if (fields == null) {
            return null;
        }
        Object v = fields.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String textOrEmpty(Map<String, Object> fields, String key) {
        String v = text(fields, key);
        return v == null ? "" : v;
    }

    /**
     * 端口类字段取值：模板页 ports 勾选组提交的是 JSON 数组（List），
     * 工单覆盖参数也可能是 "[LAN1, LAN2]" 字符串。统一转成 "LAN1,LAN2"，
     * 否则 String.valueOf(List) 会变成 "[LAN1, LAN2]"，端口映射全部失败。
     * 依次尝试多个别名（master 里 bind_port / bind_ports 同义）。
     */
    private static String textList(Map<String, Object> fields, String... keys) {
        if (fields == null) {
            return null;
        }
        for (String key : keys) {
            Object v = fields.get(key);
            if (v == null) {
                continue;
            }
            String joined;
            if (v instanceof java.util.Collection<?> list) {
                StringBuilder sb = new StringBuilder();
                for (Object o : list) {
                    if (o == null) {
                        continue;
                    }
                    String s = String.valueOf(o).trim().replace("[", "").replace("]", "");
                    if (!s.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append(',');
                        }
                        sb.append(s);
                    }
                }
                joined = sb.toString();
            } else {
                joined = String.valueOf(v).trim();
                if (joined.startsWith("[")) {
                    joined = joined.substring(1);
                }
                if (joined.endsWith("]")) {
                    joined = joined.substring(0, joined.length() - 1);
                }
            }
            if (!joined.isBlank() && !"null".equalsIgnoreCase(joined)) {
                return joined.trim();
            }
        }
        return null;
    }

    /** 拼一个计划步骤 */
    private static Map.Entry<String, String> step(String desc, String json) {
        return Map.entry(desc, json);
    }

    private static String taskJson(String type, String path, Map<String, Object> params) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("__type", type);
        all.put("__path", path == null ? "" : path);
        if (params != null) {
            all.putAll(params);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(all);
        } catch (Exception e) {
            throw new BusinessException("任务参数序列化失败: " + e.getMessage());
        }
    }
}

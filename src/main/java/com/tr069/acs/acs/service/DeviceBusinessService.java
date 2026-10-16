package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.cwmp.WanServiceClassifier;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 设备业务信息解析与采集。
 *
 * <p>WiFi、SIP、WAN 这些业务参数在能力树里就是普通路径，业务含义靠<strong>路径形态</strong>区分。
 * 这里把 {@code cpe_parameter} 里的参数按 WAN 连接 / WLAN / SIP 语音三个分区归类，
 * 每个分区再按对象实例（{@code WLANConfiguration.1}、{@code SIP.1} 这种带序号的路径）分组，
 * 前端拿到的就是"一条 WAN 连接有哪些字段值"这样的业务视角，而不是一棵裸参数树。</p>
 *
 * <p>数据来源分两条路：</p>
 * <ul>
 *   <li>{@link #summary}：只读库，取最近一次能力采集回填的值，毫秒级返回</li>
 *   <li>{@link #collect}：先对在线设备下发 {@code GetParameterValues} 把这些路径的值刷一遍，
 *       再回库读，适合"设备刚改了配置、页面要最新值"的场景</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceBusinessService {

    /**
     * 业务分区定义：key 即返回 JSON 里的字段名，instance 是"实例对象路径"的匹配规则。
     *
     * <p>实例对象指路径末段形如 {@code XXX.1} 的对象，其下一层叶子参数就是这条业务实例的属性。
     * 规则刻意写成后缀匹配，InternetGatewayDevice（TR-098）与 Device（TR-181）两种根都能对上。</p>
     */
    private record Section(String key, String name, Pattern instance) {
    }

    /**
     * 兜底根节点：设备台账里没有记录时按 TR-098 处理。
     *
     * <p>业务参数的前缀不由它决定——那部分走 {@link DataModel#businessPrefixes(String)}，
     * 同时覆盖两种模型，台账里的根节点错了也不会查空。</p>
     */
    private static final String DEFAULT_ROOT = DataModel.TR098.getRoot();

    private static final List<Section> SECTIONS = List.of(
            new Section("device", "设备信息",
                    Pattern.compile("\\.DeviceInfo\\.$")),
            new Section("wan", "WAN 连接",
                    Pattern.compile("\\.(WANPPPConnection|WANIPConnection)\\.\\d+\\.$")),
            new Section("wifi", "WLAN",
                    Pattern.compile("\\.(WLANConfiguration\\.\\d+|WiFi\\.SSID\\.\\d+)\\.$")),
            new Section("sip", "SIP 语音",
                    Pattern.compile("VoiceService\\.\\d+\\.VoiceProfile\\.\\d+\\.SIP(\\.\\d+)?\\.$")));

    private final CpeParameterMapper parameterMapper;
    private final CpeDeviceMapper deviceMapper;
    private final DeviceCapabilityService capabilityService;

    /** 业务采集等待设备的超时（秒），超过就返回已刷到库里的部分结果。 */
    @Value("${acs.device.business.collect-timeout-seconds:45}")
    private long collectTimeoutSeconds;

    // ==================== 查询 ====================

    /**
     * 从库里的能力快照解析业务信息，不与设备交互。
     */
    public Map<String, Object> summary(String sn) {
        String root = resolveRoot(sn);
        List<CpeParameter> params = selectBusinessParams(sn);
        Map<String, Object> data = baseInfo(sn, root);
        for (Section section : SECTIONS) {
            data.put(section.key(), groupInstances(params, section));
        }
        data.put("pon", ponParams(sn));
        enrichWan(data);
        return data;
    }

    /**
     * 批量解析：设备列表页一次拉整页设备的业务概要，避免每行一个请求。
     *
     * <p>单台失败不影响其余设备，失败的 sn 不出现在结果里，前端按空数据兜底展示。</p>
     */
    public Map<String, Map<String, Object>> summaryBatch(List<String> sns) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (sns == null) {
            return result;
        }
        for (String sn : sns) {
            if (sn == null || sn.isBlank()) {
                continue;
            }
            try {
                result.put(sn.trim(), summary(sn.trim()));
            } catch (Exception e) {
                log.warn("批量业务信息解析失败: sn={}, err={}", sn, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 对在线设备重新读取业务参数，再回库解析。
     *
     * <p>要读哪些路径是从已采集的能力树里挑的——树都没采过就无从谈起，
     * 此时返回 {@code collectStatus=empty}，前端应引导先做能力树采集。</p>
     *
     * @return 与 summary 同构，外加 collectStatus：success / timeout / fail / empty
     */
    public Map<String, Object> collect(String sn) {
        String root = resolveRoot(sn);
        List<CpeParameter> params = selectBusinessParams(sn);
        List<String> paths = collectiblePaths(params);
        if (paths.isEmpty()) {
            Map<String, Object> data = baseInfo(sn, root);
            for (Section section : SECTIONS) {
                data.put(section.key(), List.of());
            }
            data.put("collectStatus", "empty");
            data.put("message", "设备能力树为空或未覆盖业务路径，请先执行能力树采集");
            return data;
        }
        log.info("采集设备业务参数: sn={}, pathCount={}", sn, paths.size());
        // PON/光模块参数不在业务分区实例下，单独补进采集清单
        paths.addAll(ponParams(sn).keySet());
        CompletableFuture<Map<String, String>> future =
                capabilityService.readPathValues(sn, paths);
        String status;
        try {
            future.get(collectTimeoutSeconds, TimeUnit.SECONDS);
            status = "success";
        } catch (TimeoutException e) {
            status = "timeout";
            log.warn("业务参数采集超时，返回已落库的部分结果: sn={}, paths={}", sn, paths.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "fail";
        } catch (Exception e) {
            status = "fail";
            log.error("业务参数采集失败: sn={}", sn, e);
        }
        Map<String, Object> data = summary(sn);
        data.put("collectStatus", status);
        return data;
    }

    /**
     * 挑出需要向设备重新读取的路径：命中分区实例对象的叶子参数。
     */
    private List<String> collectiblePaths(List<CpeParameter> params) {
        Set<String> paths = new LinkedHashSet<>();
        for (Section section : SECTIONS) {
            for (CpeParameter row : params) {
                String parent = parentOf(row);
                if (!parent.isEmpty() && section.instance().matcher(parent).find()) {
                    paths.add(row.getParamPath());
                }
            }
        }
        return new ArrayList<>(paths);
    }

    // ==================== 分区解析 ====================

    /**
     * 按实例对象分组。返回结构：
     * <pre>[{"path": "...WLANConfiguration.1.", "params": {"SSID": "ChinaNet-xxx", ...}}, ...]</pre>
     */
    private List<Map<String, Object>> groupInstances(List<CpeParameter> params, Section section) {
        // TreeMap 保证实例按序号自然排列
        Map<String, Map<String, String>> grouped = new TreeMap<>();
        for (CpeParameter row : params) {
            String parent = parentOf(row);
            if (parent.isEmpty() || !section.instance().matcher(parent).find()) {
                continue;
            }
            Map<String, String> fields =
                    grouped.computeIfAbsent(parent, k -> new LinkedHashMap<>());
            String name = normalizeLeaf(DeviceCapabilityService.nameOf(row.getParamPath()));
            fields.putIfAbsent(name, row.getParamValue());
        }
        List<Map<String, Object>> result = new ArrayList<>(grouped.size());
        for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.getKey());
            item.put("params", entry.getValue());
            result.add(item);
        }
        return result;
    }

    /** WAN 分区 key，与 SECTIONS 里的定义对应。 */
    private static final String WAN_KEY = "wan";


    /**
     * 给 WAN 连接补业务语义，并算出设备的管理地址。
     *
     * <p>光猫上通常有多条 WAN 连接（INTERNET / TR069 / VOIP / IPTV），设备真正与 ACS
     * 通信的是 TR069 通道，"当前位置 IP"要的是它的地址，不能拿 INTERNET 通道的上网公网地址顶替。
     * 判定在后端一次做完，前端只读 {@code ip} 与每条连接的 {@code service}，
     * 避免同一套厂商适配规则在前端再写一遍。</p>
     *
     * <p>设备既然能上报上来，TR069 通道必然存在且带地址，因此不做任何兜底：
     * 没识别出来就是这次快照没采到，{@code ip} 为 null，由前端显示 "-"，
     * 而不是退回去取别的通道。</p>
     */
    @SuppressWarnings("unchecked")
    private void enrichWan(Map<String, Object> data) {
        Object wanObj = data.get(WAN_KEY);
        if (!(wanObj instanceof List<?> list)) {
            return;
        }
        String ip = null;
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            Object paramObj = item.get("params");
            Map<String, String> params = paramObj instanceof Map<?, ?>
                    ? (Map<String, String>) paramObj : Map.of();
            String service = serviceOf(params, String.valueOf(item.getOrDefault("path", "")));
            item.put("service", service);
            boolean tr069 = "TR069".equalsIgnoreCase(service);
            item.put("tr069", tr069);
            if (tr069 && ip == null) {
                String value = params.get("ExternalIPAddress");
                if (value != null && !value.isBlank() && !"0.0.0.0".equals(value.trim())) {
                    ip = value.trim();
                }
            }
        }
        // 管理地址 = TR069 通道地址；取不到就是没采到，不退回其它通道
        data.put("ip", ip);
        data.put("ipSource", ip == null ? null : "WAN_TR069");
    }

    /**
     * 一条 WAN 连接的业务类型。
     *
     * <p>判定统一委托 {@link WanServiceClassifier}：下发清理挑 TR069 保护对象时
     * 调的是同一个类。两套判定一旦分家，「详情页看到的业务类型」和
     * 「下发决定删哪条」就可能不一致，而那是会直接把设备搞脱管的判断。</p>
     */
    private static String serviceOf(Map<String, String> params, String path) {
        return WanServiceClassifier.classify(params, path);
    }

    /** 厂商私有叶子前缀，形如 X_CMCC_、X_CT-COM_（中间可带连字符）。 */
    private static final Pattern VENDOR_LEAF_PREFIX = Pattern.compile("(?i)^x_[a-z0-9-]+_");

    /**
     * 业务卡片用的参数名：剥掉厂商私有前缀，归一到标准名。
     *
     * <p>移动定制机的 VLAN ID 叫 {@code X_CMCC_VLANIDMark}，电信叫 {@code X_CT-COM_VLANIDMark}，
     * 而前端按标准名 {@code VLANIDMark} 取值——不剥前缀的话页面上永远是 "-"。
     * 同一实例里若设备同时报了标准节点与私有节点（按路径升序标准节点先到），
     * {@code putIfAbsent} 保证标准值优先，不被私有值覆盖。</p>
     */
    static String normalizeLeaf(String leaf) {
        if (leaf == null) {
            return "";
        }
        java.util.regex.Matcher m = VENDOR_LEAF_PREFIX.matcher(leaf);
        return m.find() ? leaf.substring(m.end()) : leaf;
    }

    private Map<String, Object> baseInfo(String sn, String root) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sn", sn);
        data.put("rootNode", root);
        // 对外说明按哪种数据模型解读的这套数据，排查「某些型号业务查不到」时先看这里
        data.put("dataModel", DataModel.ofRoot(root).getLabel());
        try {
            data.put("lastCollectTime", parameterMapper.lastCollectTime(sn));
        } catch (Exception e) {
            log.warn("查询业务参数采集时间失败: sn={}, err={}", sn, e.getMessage());
            data.put("lastCollectTime", null);
        }
        return data;
    }

    /**
     * PON / 光模块参数：能力快照里路径含 PON 的参数（MySQL 排序规则大小写不敏感，
     * GPON / EPON / Gpon / Pon 都能命中）。
     *
     * <p>返回完整参数路径到值的映射；设备没上报（GE 上联或快照未采集）返回空 map，
     * 前端据此按 GE 设备兜底展示。光功率参数（如 X_CT-COM_TransceiverPower）通常以
     * 0.1µW 为单位，换算 dBm 由前端负责。</p>
     */
    private Map<String, String> ponParams(String sn) {
        try {
            List<CpeParameter> rows = parameterMapper.selectList(new LambdaQueryWrapper<CpeParameter>()
                    .eq(CpeParameter::getSn, sn)
                    .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                    .like(CpeParameter::getParamPath, "PON")
                    .orderByAsc(CpeParameter::getParamPath));
            Map<String, String> result = new LinkedHashMap<>();
            for (CpeParameter row : rows) {
                result.put(row.getParamPath(), row.getParamValue());
            }
            return result;
        } catch (Exception e) {
            log.warn("查询PON参数失败: sn={}, err={}", sn, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 只取业务相关前缀下的参数行，避免把整棵能力树（上万行）拉进内存。
     *
     * <p>前缀由 {@link DataModel#businessPrefixes(String)} 给出，天然包含两种根数据模型的写法：
     * 这里若只按 {@code root} 拼一种，TR-181 设备（路径是 {@code Device.WAN.}）的 WAN 分区
     * 就一条都查不到，页面只会显示「暂无数据」而没有任何报错。</p>
     */
    private List<CpeParameter> selectBusinessParams(String sn) {
        List<String> prefixes = new ArrayList<>();
        for (Section section : SECTIONS) {
            prefixes.addAll(DataModel.businessPrefixes(section.key()));
        }
        try {
            return parameterMapper.selectList(new LambdaQueryWrapper<CpeParameter>()
                    .eq(CpeParameter::getSn, sn)
                    .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                    .and(w -> {
                        for (int i = 0; i < prefixes.size(); i++) {
                            if (i == 0) {
                                w.likeRight(CpeParameter::getParamPath, prefixes.get(i));
                            } else {
                                w.or().likeRight(CpeParameter::getParamPath, prefixes.get(i));
                            }
                        }
                    })
                    .orderByAsc(CpeParameter::getParamPath));
        } catch (Exception e) {
            log.warn("查询业务参数失败: sn={}, err={}", sn, e.getMessage());
            return List.of();
        }
    }

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
        return DEFAULT_ROOT;
    }

    private static String parentOf(CpeParameter row) {
        if (row.getParentPath() != null) {
            return row.getParentPath();
        }
        return DeviceCapabilityService.parentOf(row.getParamPath());
    }
}

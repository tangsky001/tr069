package com.tr069.acs.worklist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeModel;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import com.tr069.acs.worklist.config.WorklistProperties;
import com.tr069.acs.worklist.tpl.dao.DeviceDao;
import com.tr069.acs.worklist.tpl.entity.Device;
import com.tr069.acs.worklist.tpl.service.ModelTemplateService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 型号能力树文件：把设备上报的参数树导出成 XML 文件，按光猫型号命名落盘。
 *
 * <p>库里的 {@code cpe_parameter} 只是设备自己的快照，型号维度要能被复用必须落成文件：
 * 同型号设备上线时，按「运营商 + 型号 + 固件」查 {@code cpe_model.config_path} 拿到文件位置，
 * 读这份 XML 就能知道该型号真实支持哪些节点、路径怎么写、类型与读写权限是什么，
 * 下发（如 wan_sip_vlan）时据此拼 {@code SetParameterValues}，不必再猜路径。</p>
 *
 * <p>落盘位置：{@code {配置根目录}/models/{运营商}/{型号标识}.xml}，
 * 与 {@link ModelMappingService#defaultConfigPath(String, String)} 的命名规则一致，
 * 相对路径回写 {@code cpe_model.config_path}，因此库里存的就是文件的查找入口。</p>
 *
 * <p>文件结构为嵌套形式，对象节点与参数节点按真实层级展开，参数节点带上
 * {@code writable / type / value}，既是能力档案也是下发取值格式参考：</p>
 * <pre>{@code
 * <ModelTree modelKey="HG6145F1_V3.0" ...>
 *   <Object name="InternetGatewayDevice" path="InternetGatewayDevice.">
 *     <Object name="WANDevice" path="InternetGatewayDevice.WANDevice.1.">
 *       <Param name="Enable" path="InternetGatewayDevice.WANDevice.1.Enable"
 *              writable="true" type="boolean" value="True"/>
 *     </Object>
 *   </Object>
 * </ModelTree>
 * }</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelTreeStore {

    /**
     * 型号能力树在配置根目录下的子目录名。
     */
    public static final String MODEL_DIR = "models";

    /**
     * 单次业务查询最多返回的参数条数。
     */
    private static final int LOOKUP_LIMIT = 200;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CpeParameterMapper parameterMapper;
    private final CpeDeviceMapper deviceMapper;
    private final WorklistProperties properties;
    private final WorklistRootResolver rootResolver;
    /** 型号主表读写：走 SQLite 模板库，不再是 MySQL cpe_model */
    private final DeviceDao deviceDao;
    /** 型号档案更新后重生成下发模板，避免库里留着旧路径 */
    private final ModelTemplateService templateService;

    // ==================== 导出 ====================

    /**
     * 把某台设备的参数树导出为该型号的 XML 文件，并把相对路径写回 {@code cpe_model.config_path}。
     *
     * <p>同一型号已由其它样机导出过时，默认不覆盖，避免拿一台异常设备把好的档案冲掉；
     * 确认要用当前设备刷新时传 {@code force = true}。</p>
     *
     * @param sn    设备序列号，能力树须已采集
     * @param force 是否覆盖已存在的型号文件
     * @return 导出结果，含文件相对路径与绝对路径
     */
    public ExportResult export(String sn, boolean force) {
        return export(sn, force, null);
    }

    /**
     * @param ispOverride 归档到指定运营商目录：适配时以人工申报的运营商为准，
     *                    可能与设备上报的 isp 不同（同型号卖给多家运营商）。
     */
    public ExportResult export(String sn, boolean force, String ispOverride) {
        List<CpeParameter> nodes = parameterMapper.selectList(new LambdaQueryWrapper<CpeParameter>()
                .eq(CpeParameter::getSn, sn)
                .orderByAsc(CpeParameter::getParamPath));
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("设备能力树为空，请先采集: " + sn);
        }
        CpeDevice device = findDevice(sn);
        String isp = notBlank(ispOverride) ? ispOverride.trim()
                : (device != null && notBlank(device.getIsp()) ? device.getIsp().trim() : "standard");
        String modelKey = ModelMappingService.buildModelKey(
                device == null ? null : device.getProductClass(),
                device == null ? null : device.getSoftwareVersion());

        Device modelRow = resolveModelRow(isp, modelKey, device, sn);
        if (modelRow == null) {
            throw new IllegalStateException("型号登记失败: " + modelKey);
        }
        String relative = relativePath(isp, modelKey);
        Path file = resolveFile(relative);

        if (Files.isRegularFile(file) && !force && notBlank(modelRow.getSampleSn())
                && !sn.equals(modelRow.getSampleSn())) {
            return ExportResult.skipped(sn, isp, modelKey, relative, file, count(nodes, true),
                    count(nodes, false), modelRow.getSampleSn());
        }

        String xml = buildXml(sn, isp, modelKey, device, nodes);
        writeAtomic(file, xml);

        if (force || !notBlank(modelRow.getSampleSn())) {
            modelRow.setSampleSn(sn);
        }
        deviceDao.upsert(modelRow);

        // 能力申报可能刚变过，模板要跟着重推一遍；模板没生成出来不影响导出本身
        if (templateService != null) {
            try {
                templateService.generateAll(modelRow);
            } catch (Exception e) {
                log.warn("生成型号模板失败: model={}, err={}", modelKey, e.getMessage());
            }
        }

        return ExportResult.written(sn, isp, modelKey, relative, file,
                count(nodes, true), count(nodes, false));
    }

    // ==================== 能力申报同步 ====================

    /**
     * 把适配申报落到 SQLite 模板库，并按申报重新生成模板。
     *
     * <p>申报是模板生成的唯一输入——几个网口、几路语音、什么 WiFi 规格。
     * 它必须写进模板库（{@code device.cap_*}），不能只停在 MySQL 的型号档案上：
     * 采集导出发生在申报<b>之前</b>，那一刻库里还没有能力字段，一条模板都生成不出来；
     * 申报之后若不再回写一次，模板库就永远是空的，下发只能报「该型号未适配」。</p>
     *
     * <p>型号行不存在时顺带创建（申报也可能先于采集发生）。</p>
     *
     * @param isp      归档运营商
     * @param modelKey 型号 key
     * @param model    已写入申报值的型号档案
     */
    public void applyCapability(String isp, String modelKey, CpeModel model) {
        Device row = deviceDao.findByIspModelKey(isp, modelKey).orElseGet(() -> {
            Device d = new Device();
            d.setIsp(isp);
            d.setModelKey(modelKey);
            return d;
        });
        row.setManufacturer(model.getManufacturer());
        row.setProductClass(model.getProductClass());
        row.setSoftwareVersion(model.getSoftwareVersion());
        row.setHardwareVersion(model.getHardwareVersion());
        row.setRootNode(model.getRootNode());
        row.setCapLanPorts(model.getCapLanPorts());
        row.setCapLanSpeed(model.getCapLanSpeed());
        row.setCapSipCount(model.getCapSipCount());
        row.setCapWifiSpec(model.getCapWifiSpec());
        if (model.getSampleSn() != null && !model.getSampleSn().isBlank()) {
            row.setSampleSn(model.getSampleSn());
        }
        row.setStatus("ADAPTED");
        deviceDao.upsert(row);


        // 回查一次拿到主键：新建行的 id 由 upsert 落库时才产生
        Device saved = deviceDao.findByIspModelKey(isp, modelKey).orElse(row);
        try {
            ModelTemplateService.GenerateResult r = templateService.generateAll(saved);
        } catch (Exception e) {
            log.warn("按申报生成模板失败: model={}, err={}", modelKey, e.getMessage());
        }
    }

    // ==================== 定位 ====================

    /**
     * 按设备定位它的型号能力树文件，即「下发前去库里查路径」这一步。
     *
     * @return 型号、相对路径、绝对路径与文件是否已落盘；未登记型号时为 null
     */
    public Map<String, Object> locate(String sn) {
        CpeDevice device = findDevice(sn);
        String isp = device != null && notBlank(device.getIsp()) ? device.getIsp().trim() : "standard";
        String modelKey = ModelMappingService.buildModelKey(
                device == null ? null : device.getProductClass(),
                device == null ? null : device.getSoftwareVersion());
        // 型号档案改查 SQLite 模板库：下发前取路径这一步必须只看「适配数据」那一侧，
        // MySQL cpe_model 不再是数据源
        Device model = deviceDao.findByIspModelKey(isp, modelKey).orElse(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sn", sn);
        data.put("isp", isp);
        data.put("modelKey", modelKey);
        data.put("productClass", device == null ? null : device.getProductClass());
        data.put("softwareVersion", device == null ? null : device.getSoftwareVersion());
        if (model == null) {
            data.put("registered", false);
            data.put("configPath", null);
            data.put("exists", false);
            data.put("hint", "该型号尚未登记，请先采集设备能力树");
            return data;
        }
        String relative = relativePath(isp, modelKey);
        Path file = resolveFile(relative);
        data.put("registered", true);
        data.put("modelId", model.getId());
        data.put("configPath", relative);
        data.put("absolutePath", file.toString());
        data.put("exists", Files.isRegularFile(file));
        data.put("sampleSn", model.getSampleSn());
        if (!Files.isRegularFile(file)) {
            data.put("hint", "型号文件尚未生成，请对该设备执行一次能力树导出");
        }
        return data;
    }

    // ==================== 按业务项查参数 ====================

    /**
     * 从型号 XML 里找出某个业务项对应的参数节点，供下发取路径与格式。
     *
     * <p>业务项按「_ / 空格 / 点 / 横杠」拆成关键字，如 {@code wan_sip_vlan} 拆为
     * {@code [wan, sip, vlan]}；路径命中全部关键字的排在前面，只读节点也会返回，
     * 由调用方按 {@code writable} 决定是否可写。</p>
     *
     * @param sn     设备序列号，用于定位型号
     * @param item   业务项编码或关键字，如 wan_sip_vlan
     * @param parent 可选，限定在该对象路径下查找
     * @param limit  返回条数上限
     */
    public List<Map<String, Object>> lookup(String sn, String item, String parent, int limit) {
        Map<String, Object> loc = locate(sn);
        Object relative = loc.get("configPath");
        if (relative == null) {
            throw new IllegalStateException("设备型号未登记，无法定位能力树文件: " + sn);
        }
        Path file = resolveFile(String.valueOf(relative));
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("型号能力树文件不存在，请先导出: " + file);
        }
        List<ParamNode> params = readParams(file);
        List<String> words = keywords(item);
        int max = limit <= 0 ? LOOKUP_LIMIT : Math.min(limit, 1000);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ParamNode node : params) {
            if (notBlank(parent) && !node.path.startsWith(parent)) {
                continue;
            }
            int score = score(node.path, words);
            if (score <= 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", node.path);
            row.put("name", node.name);
            row.put("writable", node.writable);
            row.put("type", node.type);
            row.put("value", node.value);
            row.put("score", score);
            result.add(row);
        }
        result.sort((a, b) -> {
            int c = Integer.compare((int) b.get("score"), (int) a.get("score"));
            if (c != 0) {
                return c;
            }
            c = Boolean.compare(Boolean.TRUE.equals(b.get("writable")),
                    Boolean.TRUE.equals(a.get("writable")));
            if (c != 0) {
                return c;
            }
            return String.valueOf(a.get("path")).compareTo(String.valueOf(b.get("path")));
        });
        return result.size() <= max ? result : new ArrayList<>(result.subList(0, max));
    }

    // ==================== 下发路径校正 ====================

    /**
     * 型号能力树按文件缓存：下发前每次解析上千节点的 XML 太贵，按文件修改时间失效。
     */
    private final ConcurrentHashMap<String, CachedTree> treeCache = new ConcurrentHashMap<>();

    /**
     * 设备自身参数树缓存，短时有效：一次工单连续几步下发，不必每步都扫一遍参数表。
     */
    private static final long DEVICE_CACHE_TTL_MILLIS = 60_000L;

    private final ConcurrentHashMap<String, DeviceCache> deviceCache = new ConcurrentHashMap<>();

    /**
     * 读出该设备型号的能力树参数，供下发前校正路径。
     *
     * <p>型号尚未登记或文件缺失时返回空列表，表示「无法校正」，调用方保持模板原路径。</p>
     */
    public List<ParamNode> modelParams(String sn) {
        Map<String, Object> loc = locate(sn);
        Object relative = loc.get("configPath");
        if (relative == null) {
            return List.of();
        }
        Path file = resolveFile(String.valueOf(relative));
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        return cached(file).params;
    }

    /**
     * 读出这台设备自身已采集的参数树。
     *
     * <p>校正的第一手依据：这里是这台设备真实存在的节点，实例号、类型、当前值都是准的，
     * 比型号档案更贴近实际——型号档案是同型号样机的快照，而同一型号不同设备开了几个
     * WLANConfiguration、哪个是 5G，只有它自己知道。</p>
     *
     * <p>带短时缓存：一次工单的多个步骤会连续查，没必要每步都扫一遍表。</p>
     */
    public List<ParamNode> deviceParams(String sn) {
        if (sn == null || sn.isEmpty()) {
            return List.of();
        }
        DeviceCache hit = deviceCache.get(sn);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.loadedAt < DEVICE_CACHE_TTL_MILLIS) {
            return hit.params;
        }
        List<ParamNode> params = loadDeviceParams(sn);
        deviceCache.put(sn, new DeviceCache(now, params));
        return params;
    }

    private List<ParamNode> loadDeviceParams(String sn) {
        List<CpeParameter> rows;
        try {
            rows = parameterMapper.selectList(new LambdaQueryWrapper<CpeParameter>()
                    .eq(CpeParameter::getSn, sn)
                    .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM));
        } catch (Exception e) {
            log.warn("读取设备参数树失败，改用型号档案: sn={}, err={}", sn, e.getMessage());
            return List.of();
        }
        List<ParamNode> params = new ArrayList<>(rows.size());
        for (CpeParameter row : rows) {
            String path = str(row.getParamPath());
            if (path.isEmpty()) {
                continue;
            }
            params.add(new ParamNode(path, str(row.getNodeName()),
                    row.getWritable() != null && row.getWritable() == 1,
                    str(row.getValueType()), str(row.getParamValue())));
        }
        return params;
    }

    /**
     * 校正依据：这台设备的参数树优先，型号档案补齐它没有的部分。
     *
     * <p>设备自身参数优先，因为它就是这台设备的实况；型号档案兜底，因为 AddObject 刚建出来的
     * 实例还没采集回来，只有同型号的骨架能对上。</p>
     */
    private List<ParamNode> resolveCandidates(String sn) {
        List<ParamNode> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ParamNode p : deviceParams(sn)) {
            if (p.getPath() != null && seen.add(p.getPath())) {
                merged.add(p);
            }
        }
        for (ParamNode p : modelParams(sn)) {
            if (p.getPath() != null && seen.add(p.getPath())) {
                merged.add(p);
            }
        }
        return merged;
    }

    /**
     * 把模板里写的参数路径校正为该型号真实存在的路径。
     *
     * <p>模板是跨厂商通用的（写 {@code VLANIDMark}），移动定制机上真实节点是
     * {@code X_CMCC_VLANIDMark}，电信是 {@code X_CT-COM_VLANIDMark}。直接按模板下发会被设备
     * 判为参数不存在；这里拿型号 XML 把末段名对上，父对象沿用模板里的实例号
     * （AddObject 新建的实例在档案里还没有），于是新实例也能写到正确的节点上。</p>
     *
     * @param sn            设备序列号，用于定位型号
     * @param templatePaths 模板解析后的参数路径
     * @return 改写映射与型号上找不到的参数；型号档案缺失时两者都为空，表示「无从判断」
     */
    public PathResolution resolvePaths(String sn, List<String> templatePaths) {
        PathResolution result = new PathResolution();
        if (templatePaths == null || templatePaths.isEmpty()) {
            return result;
        }
        List<ParamNode> params = resolveCandidates(sn);
        if (params.isEmpty()) {
            // 这台设备的参数和型号档案都没有，不能因为查不到就说参数不存在，否则会误删整单参数
            return result;
        }
        for (String path : templatePaths) {
            ParamNode node = matchNode(params, path);
            if (node == null) {
                result.getUnsupported().add(path);
                log.warn("该型号能力树上没有这个参数: {}", path);
                continue;
            }
            String real = realPath(node, path);
            // 设备声明只读的参数写了必失败，而且是 9007 这类笼统错误，会把整批都拖下水。
            // 例如 VoiceService 的 Line.Enable，试过 1 和 true 都被拒——它不是值不合法，是设备不让写
            if (!node.isWritable()) {
                result.getReadOnly().add(real);
                log.warn("该参数在设备上是只读，跳过不下发: {}", real);
                continue;
            }
            // 下发格式以型号档案声明的类型为准，不能靠字面值猜：SSID 取值 1111、密码 00000000
            // 全是数字，猜成 int 会被设备拒绝
            if (notBlank(node.getType())) {
                result.getTypes().put(real, node.getType().trim());
            }
            if (notBlank(node.getValue())) {
                result.getCurrentValues().put(real, node.getValue().trim());
            }
            if (!real.equals(path)) {
                result.getRewritten().put(path, real);
            }
        }
        dropCollisions(result);
        return result;
    }

    /**
     * 两条不同的模板参数落到同一个节点时，说明匹配不可信，一并剔除。
     *
     * <p>写错值比不写更糟：名字对不上就先不下发，日志里留痕由人补映射。</p>
     */
    static void dropCollisions(PathResolution result) {
        Map<String, List<String>> byReal = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : result.getRewritten().entrySet()) {
            byReal.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        for (Map.Entry<String, List<String>> e : byReal.entrySet()) {
            if (e.getValue().size() <= 1) {
                continue;
            }
            log.warn("多条模板参数落到同一节点，对应关系不可信，已一并剔除: {} -> {}",
                    e.getValue(), e.getKey());
            for (String source : e.getValue()) {
                result.getRewritten().remove(source);
                result.getUnsupported().add(source);
            }
        }
    }

    private CachedTree cached(Path file) {
        long modified;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            modified = 0L;
        }
        CachedTree hit = treeCache.get(file.toString());
        if (hit != null && hit.modified == modified) {
            return hit;
        }
        CachedTree fresh = new CachedTree(modified, readParams(file));
        treeCache.put(file.toString(), fresh);
        return fresh;
    }

    /**
     * 在型号档案里找出与模板路径对应的真实节点。
     *
     * <p>先精确命中；再在「忽略实例号之后同父对象」的范围内比对末段名——厂商私有前缀
     * （{@code X_CMCC_} / {@code X_CT-COM_}）剥掉后再比，这是跨厂商模板能落到具体型号的关键。</p>
     *
     * @return 真实路径，找不到返回 null
     */
    static String matchPath(List<ParamNode> params, String templatePath) {
        ParamNode node = matchNode(params, templatePath);
        return node == null ? null : realPath(node, templatePath);
    }

    /**
     * 命中的节点本身，调用方还要用它身上的类型定下发格式。
     *
     * <p>通用写法在这台设备上叫别的名字时（见 {@link #LEAF_ALIASES}），按别名再找一轮。</p>
     */
    static ParamNode matchNode(List<ParamNode> params, String templatePath) {
        ParamNode hit = matchKnown(params, templatePath);
        if (hit != null || templatePath == null || templatePath.isEmpty()) {
            return hit;
        }
        for (String alias : LEAF_ALIASES.getOrDefault(leafOf(templatePath), List.of())) {
            ParamNode aliasHit = matchKnown(params, parentOf(templatePath) + alias);
            if (aliasHit != null) {
                return aliasHit;
            }
        }
        return null;
    }

    /**
     * 同一语义的参数，各厂商写法差到「剥前缀 + 比后缀」都对不上的那几个。
     *
     * <p>只登记能确定就是同一个语义的写法：地址获取方式，有的厂商写成
     * {@code IPAddressingType}，TR-098 标准名是 {@code AddressingType}；VLAN 号模板写
     * {@code X_CMCC_VLANID}，移动定制机真实存在于 {@code WANIPConnection} 下的却是
     * {@code X_CMCC_VLANIDMark}（同父对象里没有第二个 VLANID 节点，不存在选错的风险）。</p>
     *
     * <p>这里只是<b>可以试的顺序</b>，采不采用要看树：逐一拿去比对，这台设备的能力树上真有
     * 才认。一个都认不出来就返回 null——由设备明确报「参数不存在」比凭猜测写错节点好。</p>
     */
    private static final Map<String, List<String>> LEAF_ALIASES = aliases();

    private static Map<String, List<String>> aliases() {
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.put("X_CMCC_VLANID", List.of("X_CMCC_VLANIDMark", "X_CT-COM_VLANIDMark", "VLANIDMark"));
        map.put("VLANID", List.of("X_CMCC_VLANIDMark", "X_CT-COM_VLANIDMark", "VLANIDMark"));
        map.put("IPAddressingType", List.of("AddressingType"));
        return Map.copyOf(map);
    }

    /**
     * 按 {@link #leafScore(String, String)} 逐级放宽的匹配本体。
     */
    static ParamNode matchKnown(List<ParamNode> params, String templatePath) {
        if (templatePath == null || templatePath.isEmpty()) {
            return null;
        }
        for (ParamNode p : params) {
            if (templatePath.equals(p.getPath())) {
                return p;
            }
        }
        String parent = parentOf(templatePath);
        String wanted = leafOf(templatePath);
        String skeleton = skeleton(parent);

        // 第一轮只在同父对象范围内找：模板要写的是这个对象下的参数，别处的同名节点不算数，
        // 否则 VoiceService 下的 VLANIDMark 会压过 WAN 侧的 X_CMCC_VLANIDMark。
        // 父对象按「去掉实例号后的骨架」比较，因此模板把实例号写错位置也能对上。
        ParamNode best = null;
        int bestScore = 0;
        for (ParamNode p : params) {
            String modelParent = parentOf(p.getPath());
            if (!skeleton(modelParent).equals(skeleton)) {
                continue;
            }
            int score = leafScore(leafOf(p.getPath()), wanted);
            if (score >= 40 && score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (best != null) {
            return best;
        }
        // 第二轮才考虑别的对象：必须剥掉厂商前缀后完全同名，弱匹配（后缀/包含）一律不采信，
        // 宁可让设备明确报「参数不存在」，也不能把值写到不相干的节点上
        for (ParamNode p : params) {
            int score = leafScore(leafOf(p.getPath()), wanted);
            if (score >= 90 && score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /**
     * 把命中的节点按模板里的实例号还原成完整路径。
     *
     * <p>必须走这一步而不是「模板父对象 + 末段名」：模板写的 {@code PreSharedKey} 在不少设备上是
     * 对象，真实参数在 {@code PreSharedKey.1.PreSharedKey}，直接拼父对象会写到对象上去。</p>
     */
    static String realPath(ParamNode node, String templatePath) {
        return alignInstances(parentOf(templatePath), parentOf(node.getPath())) + leafOf(node.getPath());
    }

    /**
     * 末段名相似度：精确 100，剥掉厂商前缀后相同 90，一方是另一方的后缀 80，包含 40。
     */
    static int leafScore(String candidate, String wanted) {
        if (candidate == null || wanted == null || candidate.isEmpty() || wanted.isEmpty()) {
            return 0;
        }
        if (candidate.equalsIgnoreCase(wanted)) {
            return 100;
        }
        String c = stripVendor(candidate);
        String w = stripVendor(wanted);
        if (c.equals(w)) {
            return 90;
        }
        // 只有长度接近才认「后缀/包含」：丢掉这条约束，HideSSID 会因为 SSID 是它的后缀而
        // 匹配到 SSID 上，等于把「隐藏」的取值写到了 SSID 名称上
        if (Math.abs(c.length() - w.length()) > 2) {
            return 0;
        }
        if (c.endsWith(w) || w.endsWith(c)) {
            return 80;
        }
        if (c.contains(w) || w.contains(c)) {
            return 40;
        }
        return 0;
    }

    /**
     * 去掉厂商私有前缀与分隔符：X_CMCC_VLANIDMark -> vlanidmark。
     */
    static String stripVendor(String leaf) {
        // 厂商前缀形如 X_CMCC_ / X_CT-COM_，中间可能带连字符
        String s = leaf.replaceAll("(?i)^x_[a-z0-9-]+_", "");
        return s.replace("_", "").replace("-", "").toLowerCase();
    }

    /**
     * 把路径里的纯数字段换成占位符，使新建实例号能与档案里的已有实例号对齐比较。
     */
    static String normalizeInstances(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        for (String seg : path.split("\\.")) {
            if (seg.isEmpty()) {
                continue;
            }
            sb.append(isDigits(seg) ? "{}" : seg).append('.');
        }
        return sb.toString();
    }

    /**
     * 路径骨架：去掉全部实例号后的样子，用于判断两个父对象是不是同一类对象。
     *
     * <p>{@code ...WANConnectionDevice.1.4.WANIPConnection.} 与
     * {@code ...WANConnectionDevice.1.WANIPConnection.2.} 骨架相同，都是
     * {@code ...WANConnectionDevice.WANIPConnection.}——模板把实例号写在中间也能认出来。</p>
     */
    static String skeleton(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        for (String seg : path.split("\\.")) {
            if (seg.isEmpty() || isDigits(seg)) {
                continue;
            }
            sb.append(seg).append('.');
        }
        return sb.toString();
    }

    /**
     * 按型号档案的真实结构重写实例号。
     *
     * <p>模板常把 AddObject 得到的实例号直接接在连接设备后面（{@code WANConnectionDevice.1.4.
     * WANIPConnection.}），而设备上实例号在末尾（{@code WANConnectionDevice.1.WANIPConnection.4.}）。
     * 这里取模板里的实例号，从左到右填进档案骨架的实例号槽位，写出来的路径才真的存在。</p>
     */
    static String alignInstances(String templateParent, String modelParent) {
        List<String> numbers = new ArrayList<>();
        for (String seg : templateParent.split("\\.")) {
            if (!seg.isEmpty() && isDigits(seg)) {
                numbers.add(seg);
            }
        }
        StringBuilder sb = new StringBuilder(modelParent.length());
        int used = 0;
        for (String seg : modelParent.split("\\.")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (isDigits(seg) && used < numbers.size()) {
                sb.append(numbers.get(used++));
            } else {
                sb.append(seg);
            }
            sb.append('.');
        }
        return sb.toString();
    }

    private static boolean isDigits(String seg) {
        if (seg.isEmpty()) {
            return false;
        }
        for (int i = 0; i < seg.length(); i++) {
            if (!Character.isDigit(seg.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String parentOf(String path) {
        int idx = path.lastIndexOf('.');
        return idx < 0 ? "" : path.substring(0, idx + 1);
    }

    private static String leafOf(String path) {
        int idx = path.lastIndexOf('.');
        return idx < 0 ? path : path.substring(idx + 1);
    }

    // ==================== XML 构建 ====================

    /**
     * 按 parentPath 归组后递归输出嵌套 XML。包级可见，便于单元测试直接校验层级与转义。
     */
    static String buildXml(String sn, String isp, String modelKey, CpeDevice device,
                           List<CpeParameter> nodes) {
        Map<String, List<CpeParameter>> children = new LinkedHashMap<>();
        for (CpeParameter node : nodes) {
            String parent = node.getParentPath() == null ? "" : node.getParentPath();
            children.computeIfAbsent(parent, k -> new ArrayList<>()).add(node);
        }
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<ModelTree sn=\"").append(escape(sn))
                .append("\" isp=\"").append(escape(isp))
                .append("\" modelKey=\"").append(escape(modelKey))
                .append("\" productClass=\"")
                .append(escape(device == null ? "" : str(device.getProductClass())))
                .append("\" softwareVersion=\"")
                .append(escape(device == null ? "" : str(device.getSoftwareVersion())))
                .append("\" manufacturer=\"")
                .append(escape(device == null ? "" : str(device.getManufacturer())))
                .append("\" objectCount=\"").append(count(nodes, true))
                .append("\" paramCount=\"").append(count(nodes, false))
                .append("\" exportTime=\"").append(escape(LocalDateTime.now().format(TS)))
                .append("\">\n");
        for (CpeParameter root : children.getOrDefault("", Collections.emptyList())) {
            render(sb, root, children, 1);
        }
        sb.append("</ModelTree>\n");
        return sb.toString();
    }

    private static void render(StringBuilder sb, CpeParameter node,
                               Map<String, List<CpeParameter>> children, int depth) {
        indent(sb, depth);
        String name = node.getNodeName() == null ? "" : node.getNodeName();
        if (CpeParameter.TYPE_OBJECT.equals(node.getNodeType())) {
            sb.append("<Object name=\"").append(escape(name))
                    .append("\" path=\"").append(escape(str(node.getParamPath())))
                    .append("\">\n");
            for (CpeParameter child : children.getOrDefault(str(node.getParamPath()),
                    Collections.emptyList())) {
                render(sb, child, children, depth + 1);
            }
            indent(sb, depth);
            sb.append("</Object>\n");
            return;
        }
        sb.append("<Param name=\"").append(escape(name))
                .append("\" path=\"").append(escape(str(node.getParamPath())))
                .append("\" writable=\"").append(node.getWritable() != null && node.getWritable() == 1)
                .append("\" type=\"").append(escape(str(node.getValueType())))
                .append("\" value=\"").append(escape(str(node.getParamValue())))
                .append("\"/>\n");
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
    }

    // ==================== XML 读取 ====================

    /**
     * 读出文件里全部 Param 节点的路径与格式。
     */
    public List<ParamNode> readParams(Path file) {
        List<ParamNode> result = new ArrayList<>();
        try {
            Document doc = parse(file);
            NodeList list = doc.getElementsByTagName("Param");
            for (int i = 0; i < list.getLength(); i++) {
                Element el = (Element) list.item(i);
                result.add(new ParamNode(
                        el.getAttribute("path"),
                        el.getAttribute("name"),
                        "true".equalsIgnoreCase(el.getAttribute("writable")),
                        el.getAttribute("type"),
                        el.getAttribute("value")));
            }
        } catch (Exception e) {
            throw new IllegalStateException("解析型号能力树失败: " + file + ", " + e.getMessage(), e);
        }
        return result;
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file.toFile());
    }

    // ==================== 文件与型号 ====================

    /**
     * 型号文件相对配置根目录的路径：已有登记就沿用，否则按默认规则生成。
     */
    /**
     * 型号能力树文件的相对路径（相对配置根目录）。
     *
     * <p>SQLite 化后不再把路径存进字段：路径完全由「运营商 + 型号标识」按约定推导，
     * 库里少一份要与实际文件保持一致的状态。</p>
     */
    private static String relativePath(String isp, String modelKey) {
        return MODEL_DIR + "/" + ModelMappingService.defaultConfigPath(isp, modelKey);
    }

    private Path resolveFile(String relative) {
        // 统一经根目录解析组件定位：从任何工作目录启动，型号文件都落在同一处
        return rootResolver.root().resolve(relative).normalize();
    }

    /**
     * 先写临时文件再替换，避免导出中途失败留下半个文件。
     */
    private void writeAtomic(Path file, String content) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // 跨文件系统时 ATOMIC_MOVE 会失败，退回普通替换
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入型号能力树失败: " + file + ", " + e.getMessage(), e);
        }
    }

    /**
     * 按「运营商 + 型号标识」定位型号档案，没有就登记一条待适配。
     */
    /**
     * 按「运营商 + 型号标识」定位型号档案，没有就登记一条待适配。
     *
     * <p>读写走 SQLite 模板库的 {@code device} 表，不再是 MySQL {@code cpe_model}：
     * 型号适配数据属于「光猫适配数据」，按边界约定只进 SQLite。MySQL 那张表
     * 留到阶段 5 连同实体与 Mapper 一起删。</p>
     */
    private Device resolveModelRow(String isp, String modelKey, CpeDevice device, String sn) {
        Optional<Device> exist = deviceDao.findByIspModelKey(isp, modelKey);
        if (exist.isPresent()) {
            return exist.get();
        }
        Device row = new Device();
        row.setIsp(isp);
        row.setModelKey(modelKey);
        row.setManufacturer(device == null ? null : device.getManufacturer());
        row.setProductClass(device == null ? null : device.getProductClass());
        row.setSoftwareVersion(device == null ? null : device.getSoftwareVersion());
        row.setHardwareVersion(device == null ? null : device.getHardwareVersion());
        // 根节点决定模板路径前缀：TR-181 设备是 Device.，漏了会整批写错前缀
        row.setRootNode(device == null ? null : device.getRootNode());
        row.setStatus("PENDING");
        row.setSampleSn(sn);
        deviceDao.upsert(row);
        return row;
    }

    private CpeDevice findDevice(String sn) {
        try {
            return deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                    .eq(CpeDevice::getSn, sn)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            log.warn("查询设备档案失败: sn={}, err={}", sn, e.getMessage());
            return null;
        }
    }

    // ==================== 工具 ====================

    /**
     * 业务项拆关键字：忽略长度小于 2 的片段，避免实例序号之类噪音。
     */
    static List<String> keywords(String item) {
        Set<String> words = new LinkedHashSet<>();
        if (item == null) {
            return List.of();
        }
        for (String part : item.split("[^a-zA-Z0-9]+")) {
            String word = part.toLowerCase();
            if (word.length() >= 2) {
                words.add(word);
            }
        }
        return new ArrayList<>(words);
    }

    /**
     * 路径命中程度：按命中的关键字个数计分，全命中额外加成，全不命中 0 分。
     *
     * <p>不能把「部分命中」统一压成同一个分值：像 wan_sip_vlan 这种业务项往往没有路径能同时命中
     * 三个词，若都算 1 分就退化成按路径字典序排，真正落在 WANConnection 下的节点会被
     * LANDevice、DeviceInfo 里的同名片段挤到后面。按命中个数排，命中越多越靠前。</p>
     */
    static int score(String path, List<String> words) {
        if (path == null || words.isEmpty()) {
            return 0;
        }
        String lower = path.toLowerCase();
        int hit = 0;
        for (String word : words) {
            if (lower.contains(word)) {
                hit++;
            }
        }
        if (hit == 0) {
            return 0;
        }
        return hit == words.size() ? hit + 2 : hit;
    }

    private static int count(List<CpeParameter> nodes, boolean object) {
        int n = 0;
        for (CpeParameter node : nodes) {
            if (object == CpeParameter.TYPE_OBJECT.equals(node.getNodeType())) {
                n++;
            }
        }
        return n;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                case '\n' -> sb.append("&#10;");
                case '\r' -> sb.append("&#13;");
                case '\t' -> sb.append("&#9;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    // ==================== 内部模型 ====================

    /**
     * XML 里的一个参数节点：下发需要的全部信息都在这几个字段上。
     */
    public static class ParamNode {

        private final String path;

        private final String name;

        private final boolean writable;

        private final String type;

        private final String value;

        ParamNode(String path, String name, boolean writable, String type, String value) {
            this.path = path;
            this.name = name;
            this.writable = writable;
            this.type = type;
            this.value = value;
        }

        public String getPath() {
            return path;
        }

        public String getName() {
            return name;
        }

        public boolean isWritable() {
            return writable;
        }

        public String getType() {
            return type;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * 路径校正结果：要改写的，以及型号上压根没有的。
     */
    @Getter
    public static class PathResolution {

        /**
         * 需要改写的「原路径 -> 真实路径」。
         */
        private final Map<String, String> rewritten = new LinkedHashMap<>();

        /**
         * 型号能力树上找不到的参数，留在下发列表里会让整条 SetParameterValues 被设备判 9003。
         */
        private final List<String> unsupported = new ArrayList<>();

        /**
         * 设备声明为只读的参数。写了设备必然拒绝，且回的是 9007 这种笼统错误，
         * 会把同批其它参数一起拖下水——SPV 按批生效，一条被拒整批都废。
         */
        private final List<String> readOnly = new ArrayList<>();

        /**
         * 真实路径 -> 型号档案声明的值类型，下发时用它定 xsi:type。
         */
        private final Map<String, String> types = new LinkedHashMap<>();

        /**
         * 真实路径 -> 采集时这台设备上的当前值。不是取值范围，只作对照用：
         * 设备拒了某个值，先看它原本用的是什么，比翻 XML 快。
         */
        private final Map<String, String> currentValues = new LinkedHashMap<>();
    }

    /**
     * 缓存的一份设备参数树，带加载时刻，短时内复用。
     */
    private record DeviceCache(long loadedAt, List<ParamNode> params) {
    }

    /**
     * 缓存的一份型号档案，按文件修改时间判失效。
     */
    private static class CachedTree {

        private final long modified;

        private final List<ParamNode> params;

        CachedTree(long modified, List<ParamNode> params) {
            this.modified = modified;
            this.params = params;
        }
    }

    /**
     * 一次导出的结果，回带给调用方文件位置与规模。
     */
    public static class ExportResult {

        private final String sn;

        private final String isp;

        private final String modelKey;

        private final String configPath;

        private final String absolutePath;

        private final int objectCount;

        private final int paramCount;

        private final boolean written;

        private final String skipReason;

        private ExportResult(String sn, String isp, String modelKey, String configPath,
                             String absolutePath, int objectCount, int paramCount,
                             boolean written, String skipReason) {
            this.sn = sn;
            this.isp = isp;
            this.modelKey = modelKey;
            this.configPath = configPath;
            this.absolutePath = absolutePath;
            this.objectCount = objectCount;
            this.paramCount = paramCount;
            this.written = written;
            this.skipReason = skipReason;
        }

        static ExportResult written(String sn, String isp, String modelKey, String relative,
                                    Path file, int objects, int params) {
            return new ExportResult(sn, isp, modelKey, relative, file.toString(),
                    objects, params, true, null);
        }

        static ExportResult skipped(String sn, String isp, String modelKey, String relative,
                                    Path file, int objects, int params, String sampleSn) {
            return new ExportResult(sn, isp, modelKey, relative, file.toString(),
                    objects, params, false, "型号文件已由样机 " + sampleSn + " 导出，未覆盖");
        }

        public String getSn() {
            return sn;
        }

        public String getIsp() {
            return isp;
        }

        public String getModelKey() {
            return modelKey;
        }

        public String getConfigPath() {
            return configPath;
        }

        public String getAbsolutePath() {
            return absolutePath;
        }

        public int getObjectCount() {
            return objectCount;
        }

        public int getParamCount() {
            return paramCount;
        }

        public boolean isWritten() {
            return written;
        }

        public String getSkipReason() {
            return skipReason;
        }
    }
}

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

/** 型号能力树文件：把设备上报的参数树导出成 XML 文件，按光猫型号命名落盘 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelTreeStore {

    /** 型号能力树在配置根目录下的子目录名 */
    public static final String MODEL_DIR = "models";

    /** 单次业务查询最多返回的参数条数 */
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
    /** 适配档（YML）读写：判断同型号是否已在某家运营商下适配过 */
    private final ModelAdaptationProfileStore profileStore;

    // ==================== 导出 ====================

    /** 把某台设备的参数树导出为该型号的 XML 文件，并把相对路径写回 {@code cpe_model.config_path} */
    public ExportResult export(String sn, boolean force) {
        return export(sn, force, null);
    }

    /** 采集完成后的归档：不管哪家运营商的猫，先把能力树截到 standard 目录 */
    public ExportResult exportCollected(String sn) {
        return export(sn, false, archiveIsp(findDevice(sn)));
    }

    /**
     * 采集完成后的归档：只写能力树 XML，不往 SQLite 模板库登记型号。
     * <p>
     * 采集只是「把这台设备的能力读回来」，还没做适配——没适配的型号往模板库里塞一条
     * PENDING 占位没意义：它不参与下发，却让库里堆满未适配型号的垃圾行，
     * 列表上的适配判定也跟着变乱。
     * <p>
     * 真正登记型号是「测试通过 → 保存到库」那一步的事：
     * 那时落库的是实测能力，status=ADAPTED，同型号设备上线直接复用。
     */
    public ExportResult exportCollectedOnly(String sn) {
        return export(sn, false, archiveIsp(findDevice(sn)), false);
    }

    /** 归档运营商：未适配的型号一律 {@code standard}，已适配的维持已适配那一家 */
    String archiveIsp(CpeDevice device) {
        return archiveIspOf(ModelMappingService.buildModelKey(
                device == null ? null : device.getProductClass(),
                device == null ? null : device.getSoftwareVersion()));
    }

    String archiveIspOf(String modelKey) {
        String adapted = profileStore == null ? null : profileStore.findAdaptedIsp(modelKey);
        return notBlank(adapted) ? adapted.trim() : "standard";
    }

    /** 可能与设备上报的 isp 不同（同型号卖给多家运营商） */
    public ExportResult export(String sn, boolean force, String ispOverride) {
        return export(sn, force, ispOverride, true);
    }

    /**
     * 导出型号能力树。
     *
     * @param registerInLib 是否往 SQLite 模板库登记型号。
     *                      false 表示只写 XML 不落库——采集自动归档走这条，
     *                      没适配的型号不必在模板库里占位。
     */
    public ExportResult export(String sn, boolean force, String ispOverride, boolean registerInLib) {
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

        // 不落库时也要拿到这一行做「能力树已存在就别覆盖」的判断，
        // 但没有就算了——写文件本身不依赖模板库
        Device modelRow = registerInLib ? resolveModelRow(isp, modelKey, device, sn) : null;
        if (registerInLib && modelRow == null) {
            throw new IllegalStateException("型号登记失败: " + modelKey);
        }
        String relative = relativePath(isp, modelKey);
        Path file = resolveFile(relative);

        if (Files.isRegularFile(file) && !force && modelRow != null
                && notBlank(modelRow.getSampleSn())
                && !sn.equals(modelRow.getSampleSn())) {
            log.info("型号能力树已存在且来自其它样机，本次不覆盖: model={}, file={}, sample={}",
                    modelKey, relative, modelRow.getSampleSn());
            return ExportResult.skipped(sn, isp, modelKey, relative, file, count(nodes, true),
                    count(nodes, false), modelRow.getSampleSn());
        }

        String xml = buildXml(sn, isp, modelKey, device, nodes);
        writeAtomic(file, xml);

        if (modelRow != null) {
            if (force || !notBlank(modelRow.getSampleSn())) {
                modelRow.setSampleSn(sn);
            }
            deviceDao.upsert(modelRow);
        }
        log.info("导出型号能力树: model={}, 对象={}, 参数={}, file={}, 落库={}",
                modelKey, count(nodes, true), count(nodes, false), file, registerInLib);

        // 能力申报可能刚变过，模板要跟着重推一遍；模板没生成出来不影响导出本身
        if (templateService != null && modelRow != null) {
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

    /** 把适配申报落到 SQLite 模板库，并按申报重新生成模板 */
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

        log.info("能力申报已同步到模板库: model={}, wifi={}, sip={}, lan={}, root={}",
                modelKey, row.getCapWifiSpec(), row.getCapSipCount(),
                row.getCapLanPorts(), row.getRootNode());

        // 回查一次拿到主键：新建行的 id 由 upsert 落库时才产生
        Device saved = deviceDao.findByIspModelKey(isp, modelKey).orElse(row);
        try {
            ModelTemplateService.GenerateResult r = templateService.generateAll(saved);
            log.info("按申报重新生成模板: model={}, 共 {} 条", modelKey, r.total());
        } catch (Exception e) {
            log.warn("按申报生成模板失败: model={}, err={}", modelKey, e.getMessage());
        }
    }

    // ==================== 定位 ====================

    /** 按设备定位它的型号能力树文件，即「下发前去库里查路径」这一步 */
    public Map<String, Object> locate(String sn) {
        CpeDevice device = findDevice(sn);
        String modelKey = ModelMappingService.buildModelKey(
                device == null ? null : device.getProductClass(),
                device == null ? null : device.getSoftwareVersion());
        // 与归档同一套规则：没适配过的型号档案只可能在 standard 下，
        // 拿台账运营商去查会查到「未登记」，与磁盘上真实存在的文件对不上
        String isp = archiveIspOf(modelKey);
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
            // 能力树 XML 只是归档产物： SQLite 模板库里那份适配结果还在，下发照旧能发，
            // 缺的只是「同型号新设备的骨架」。说成「必须重新适配」会让现场白跑一轮采集
            data.put("hint", "型号能力树文件缺失（适配结果仍在模板库中，下发不受影响）；"
                    + "需要时可对该设备再执行一次能力树导出重建");
            log.warn("型号能力树文件缺失，下发路径校正只用设备自身参数树: model={}, file={}",
                    modelKey, file);
        }
        return data;
    }

    // ==================== 按业务项查参数 ====================

    /** 从型号 XML 里找出某个业务项对应的参数节点，供下发取路径与格式 */
    public List<Map<String, Object>> lookup(String sn, String item, String parent, int limit) {
        Map<String, Object> loc = locate(sn);
        Object relative = loc.get("configPath");
        if (relative == null) {
            throw new IllegalStateException("设备型号未登记，无法定位能力树文件: " + sn);
        }
        Path file = resolveFile(String.valueOf(relative));
        if (!Files.isRegularFile(file)) {
            // 这份接口查的是归档的能力档案，文件不在就拿不出节点清单；
            // 与下发无关：适配结果在 SQLite 模板库里，缺的只是这份档案本身
            throw new IllegalStateException("型号能力树文件不存在（适配结果仍在模板库，下发不受影响）；"
                    + "需要这份档案时对该设备再执行一次能力树导出即可重建: " + file);
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

    /** 型号能力树按文件缓存：下发前每次解析上千节点的 XML 太贵，按文件修改时间失效 */
    private final ConcurrentHashMap<String, CachedTree> treeCache = new ConcurrentHashMap<>();

    /** 设备自身参数树缓存，短时有效：一次工单连续几步下发，不必每步都扫一遍参数表 */
    private static final long DEVICE_CACHE_TTL_MILLIS = 60_000L;

    private final ConcurrentHashMap<String, DeviceCache> deviceCache = new ConcurrentHashMap<>();

    /** 读出该设备型号的能力树参数，供下发前校正路径 */
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

    /** 读出这台设备自身已采集的参数树 */
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

    /** 校正依据：这台设备的参数树优先，型号档案补齐它没有的部分 */
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

    /** 把模板里写的参数路径校正为该型号真实存在的路径 */
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
                log.info("按型号能力树校正参数路径: {} -> {}", path, real);
            }
        }
        dropCollisions(result);
        return result;
    }

    /** 两条不同的模板参数落到同一个节点时，说明匹配不可信，一并剔除 */
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

    /** 在型号档案里找出与模板路径对应的真实节点 */
    static String matchPath(List<ParamNode> params, String templatePath) {
        ParamNode node = matchNode(params, templatePath);
        return node == null ? null : realPath(node, templatePath);
    }

    /** 命中的节点本身，调用方还要用它身上的类型定下发格式 */
    static ParamNode matchNode(List<ParamNode> params, String templatePath) {
        ParamNode hit = matchKnown(params, templatePath);
        if (hit != null || templatePath == null || templatePath.isEmpty()) {
            return hit;
        }
        for (String alias : LEAF_ALIASES.getOrDefault(leafOf(templatePath), List.of())) {
            ParamNode aliasHit = matchKnown(params, parentOf(templatePath) + alias);
            if (aliasHit != null) {
                log.info("参数名按别名改判: {} -> {}", templatePath, realPath(aliasHit, templatePath));
                return aliasHit;
            }
        }
        return null;
    }

    /** 同一语义的参数，各厂商写法差到「剥前缀 + 比后缀」都对不上的那几个 */
    private static final Map<String, List<String>> LEAF_ALIASES = aliases();

    private static Map<String, List<String>> aliases() {
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.put("X_CMCC_VLANID", List.of("X_CMCC_VLANIDMark", "X_CT-COM_VLANIDMark", "VLANIDMark"));
        map.put("VLANID", List.of("X_CMCC_VLANIDMark", "X_CT-COM_VLANIDMark", "VLANIDMark"));
        map.put("IPAddressingType", List.of("AddressingType"));
        // 绑定端口：模板库统一登记的是 X_CMCC_BindLANPort，而移动定制机（如中兴 P22g-CIac）
        // 上真实存在的节点叫 X_CMCC_LanInterface——两个名字毫无字面相似，不加别名就永远
        // 判成「该型号不支持」，勾的端口一个都发不出去
        map.put("X_CMCC_BindLANPort", List.of("X_CMCC_LanInterface", "X_CT-COM_LanInterface",
                "X_CMCC_LANInterface"));
        map.put("BindLANPort", List.of("X_CMCC_LanInterface", "X_CT-COM_LanInterface"));
        return Map.copyOf(map);
    }

    /** 按 {@link #leafScore(String, String)} 逐级放宽的匹配本体 */
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

    /** 把命中的节点按模板里的实例号还原成完整路径 */
    static String realPath(ParamNode node, String templatePath) {
        return alignInstances(parentOf(templatePath), parentOf(node.getPath())) + leafOf(node.getPath());
    }

    /** 末段名相似度：精确 100，剥掉厂商前缀后相同 90，一方是另一方的后缀 80，包含 40 */
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

    /** 去掉厂商私有前缀与分隔符：X_CMCC_VLANIDMark -> vlanidmark */
    static String stripVendor(String leaf) {
        // 厂商前缀形如 X_CMCC_ / X_CT-COM_，中间可能带连字符
        String s = leaf.replaceAll("(?i)^x_[a-z0-9-]+_", "");
        return s.replace("_", "").replace("-", "").toLowerCase();
    }

    /** 把路径里的纯数字段换成占位符，使新建实例号能与档案里的已有实例号对齐比较 */
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

    /** 路径骨架：去掉全部实例号后的样子，用于判断两个父对象是不是同一类对象 */
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

    /** 按型号档案的真实结构重写实例号 */
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

    /** 按 parentPath 归组后递归输出嵌套 XML。包级可见，便于单元测试直接校验层级与转义 */
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

    /** 读出文件里全部 Param 节点的路径与格式 */
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

    /** 型号文件相对配置根目录的路径：已有登记就沿用，否则按默认规则生成 */
    /** 型号能力树文件的相对路径（相对配置根目录） */
    private static String relativePath(String isp, String modelKey) {
        return MODEL_DIR + "/" + ModelMappingService.defaultConfigPath(isp, modelKey);
    }

    private Path resolveFile(String relative) {
        // 统一经根目录解析组件定位：从任何工作目录启动，型号文件都落在同一处
        return rootResolver.root().resolve(relative).normalize();
    }

    /** 先写临时文件再替换，避免导出中途失败留下半个文件 */
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

    /** 按「运营商 + 型号标识」定位型号档案，没有就登记一条待适配 */
    /** 按「运营商 + 型号标识」定位型号档案，没有就登记一条待适配 */
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

    /** 业务项拆关键字：忽略长度小于 2 的片段，避免实例序号之类噪音 */
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

    /** 路径命中程度：按命中的关键字个数计分，全命中额外加成，全不命中 0 分 */
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

    /** XML 里的一个参数节点：下发需要的全部信息都在这几个字段上 */
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

    /** 路径校正结果：要改写的，以及型号上压根没有的 */
    @Getter
    public static class PathResolution {

        /** 需要改写的「原路径 -> 真实路径」 */
        private final Map<String, String> rewritten = new LinkedHashMap<>();

        /** 型号能力树上找不到的参数，留在下发列表里会让整条 SetParameterValues 被设备判 9003 */
        private final List<String> unsupported = new ArrayList<>();

        /** 设备声明为只读的参数。写了设备必然拒绝，且回的是 9007 这种笼统错误 */
        private final List<String> readOnly = new ArrayList<>();

        /** 真实路径 -> 型号档案声明的值类型，下发时用它定 xsi:type */
        private final Map<String, String> types = new LinkedHashMap<>();

        /** 真实路径 -> 采集时这台设备上的当前值。不是取值范围，只作对照用 */
        private final Map<String, String> currentValues = new LinkedHashMap<>();
    }

    /** 缓存的一份设备参数树，带加载时刻，短时内复用 */
    private record DeviceCache(long loadedAt, List<ParamNode> params) {
    }

    /** 缓存的一份型号档案，按文件修改时间判失效 */
    private static class CachedTree {

        private final long modified;

        private final List<ParamNode> params;

        CachedTree(long modified, List<ParamNode> params) {
            this.modified = modified;
            this.params = params;
        }
    }

    /** 一次导出的结果，回带给调用方文件位置与规模 */
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

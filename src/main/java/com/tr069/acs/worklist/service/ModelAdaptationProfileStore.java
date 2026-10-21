package com.tr069.acs.worklist.service;

import com.tr069.acs.domain.entity.CpeModel;
import com.tr069.acs.worklist.config.WorklistProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 型号适配档（YML）的读写：一个型号一份，与能力树 XML 同名同目录。
 *
 * <p>XML（{@code p22g-ciac_v1.0.00.488.xml}）只是型号的能力档案——记录了这台设备支持哪些
 * 节点、路径怎么写、可不可写；它存在<b>不代表</b>这个型号适配过了（采集一次就会导出）。</p>
 *
 * <p>「是否已适配」由同名 YML 里的 {@code adapted} 说话：只有能力测试通过、真正入库时才写
 * {@code adapted: true}。设备上线时按 {@code cpe_model.adapt_path} 找到这份 YML 读它，
 * {@code adapted} 不为 true 的一律当未适配，列表上继续显示「适配」入口。</p>
 *
 * <pre>{@code
 * # models/CM/standard/p22g-ciac_v1.0.00.488.yml
 * model_key: P22G-CLAC_V1.0.00.488
 * isp: CM
 * adapted: true
 * config_file: models/CM/standard/p22g-ciac_v1.0.00.488.xml
 * lan_ports: 4
 * lan_speed: 1000M
 * sip_count: 2
 * wifi_spec: 2.4G+5.8G
 * sample_sn: CIOT29AC1489
 * adapted_at: 2026-10-14 13:48:00
 * }</pre>
 *
 * <p>只写扁平的 {@code key: value}，自己按行解析，避免为一个状态文件引入 YAML 依赖。</p>
 */
@Slf4j
@Service
public class ModelAdaptationProfileStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorklistProperties properties;

    private final WorklistRootResolver rootResolver;

    public ModelAdaptationProfileStore(WorklistProperties properties,
                                       WorklistRootResolver rootResolver) {
        this.properties = properties;
        this.rootResolver = rootResolver;
    }

    /** 适配档是否已标记为已适配。yml 缺失或 adapted 不为 true 都算未适配。 */
    public boolean isAdapted(CpeModel model) {
        Map<String, String> profile = read(model);
        return "true".equalsIgnoreCase(profile.getOrDefault("adapted", "").trim());
    }

    /** 读适配档内容，文件不存在返回空。 */
    public Map<String, String> read(CpeModel model) {
        Path file = fileOf(model);
        return read(file);
    }

    /** 同型号在任意运营商下已适配的 isp；找不到返回 null。 */
    public String findAdaptedIsp(String modelKey) {
        Path modelsDir = rootResolver.root().resolve(ModelTreeStore.MODEL_DIR);
        String fileName = ModelMappingService.modelFileName(modelKey) + ".yml";
        if (!Files.isDirectory(modelsDir)) {
            return null;
        }
        try (var isps = Files.list(modelsDir)) {
            for (Path ispDir : isps.toList()) {
                if (!Files.isDirectory(ispDir)) {
                    continue;
                }
                Path yml = ispDir.resolve("standard").resolve(fileName);
                if (!Files.isRegularFile(yml)) {
                    continue;
                }
                if ("true".equalsIgnoreCase(read(yml).getOrDefault("adapted", "").trim())) {
                    return ispDir.getFileName().toString();
                }
            }
        } catch (IOException e) {
            log.warn("扫描型号适配档失败: modelKey={}, err={}", modelKey, e.getMessage());
        }
        return null;
    }

    /** 按运营商维度读适配档：不看库里登记的 adapt_path，直接按默认规则定位。 */
    public Map<String, String> readByIsp(String modelKey, String isp) {
        Path file = rootResolver.root().resolve(ModelTreeStore.MODEL_DIR)
                .resolve(ModelMappingService.defaultAdaptPath(isp, modelKey))
                .normalize();
        return read(file);
    }

    private Map<String, String> read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, String> data = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                int idx = text.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                data.put(text.substring(0, idx).trim(), text.substring(idx + 1).trim());
            }
        } catch (IOException e) {
            log.warn("读取型号适配档失败: file={}, err={}", file, e.getMessage());
            return Map.of();
        }
        return data;
    }

    /**
     * 写入适配档并置为已适配，只有测试通过才调它。
     *
     * @return 写入的 YML 相对路径
     */
    public String writeAdapted(CpeModel model, String isp, String sn,
                               ModelAdaptationService.AdaptRequest request) {
        Path file = fileOf(model);
        if (file == null) {
            throw new IllegalStateException("型号未登记，无法写适配档: " + model.getModelKey());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 型号适配档：只有能力测试通过才会写 adapted: true\n");
        append(sb, "model_key", model.getModelKey());
        append(sb, "isp", isp);
        append(sb, "adapted", "true");
        append(sb, "config_file", ModelTreeStore.MODEL_DIR + "/" + rel(model.getConfigPath()));
        append(sb, "lan_ports", request.getLanPorts());
        append(sb, "lan_speed", request.getLanSpeed());
        append(sb, "sip_count", request.getSipCount());
        append(sb, "wifi_spec", request.getWifiSpec());
        append(sb, "sample_sn", sn);
        append(sb, "adapted_at", LocalDateTime.now().format(TS));
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入型号适配档失败: " + file + ", " + e.getMessage(), e);
        }
        log.info("写入型号适配档: file={}, adapted=true", file);
        // 入库值统一为相对配置根的 models/... 形式，与 cpe_model.config_path 同一套约定
        String relative = rootResolver.root().relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
        return relative.startsWith("/") ? relative.substring(1) : relative;
    }

    /** YML 绝对路径：优先用库里登记的 adapt_path，没有就按运营商+型号默认规则生成。 */
    private Path fileOf(CpeModel model) {
        if (model == null) {
            return null;
        }
        String relative = model.getAdaptPath();
        if (relative == null || relative.isBlank()) {
            relative = ModelTreeStore.MODEL_DIR + "/"
                    + ModelMappingService.defaultAdaptPath(model.getIsp(), model.getModelKey());
        } else {
            relative = ModelTreeStore.MODEL_DIR + "/" + rel(relative);
        }
        // 统一经根目录解析组件定位：库里登记的路径一律相对配置根，与从哪个目录启动无关
        return rootResolver.root().resolve(relative).normalize();
    }

    private static void append(StringBuilder sb, String key, String value) {
        sb.append(key).append(": ").append(value == null ? "" : value).append('\n');
    }

    /** 去掉可能重复拼接的 models/ 前缀与盘符根，只保留相对部分。 */
    private static String rel(String path) {
        String v = path == null ? "" : path.replace('\\', '/');
        while (v.startsWith("/")) {
            v = v.substring(1);
        }
        String prefix = ModelTreeStore.MODEL_DIR + "/";
        while (v.startsWith(prefix)) {
            v = v.substring(prefix.length());
        }
        return v;
    }
}

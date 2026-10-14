package com.tr069.acs.worklist.service;

import com.tr069.acs.worklist.config.WorklistProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 光猫配置根目录的统一解析。
 *
 * <p>所有落盘/读取型号文件的地方（能力树 XML、适配档 YML、光猫档案 profiles）
 * 必须经这里拿根目录，不能各自拿 {@code rootPath} 相对进程工作目录去拼——
 * 同一份配置从不同目录起 jar 会落到不同位置：从 target 目录起就写进
 * {@code target/configuration}，换个工作目录启动又去 {@code configuration} 找，
 * 结果就是「库里登记了 adapt_path，文件却不在那个位置」，适配状态永远判定失败。</p>
 *
 * <p>解析规则：配置值优先；若它指向的目录不存在或为空，依次回退到 jar 同级的
 * {@code configuration/} 与 {@code target/configuration/}（与 WorklistConfigService
 * 原有的回退逻辑一致）。结果缓存，进程内只解析一次。</p>
 */
@Slf4j
@Component
public class WorklistRootResolver {

    private final WorklistProperties properties;

    private final AtomicReference<Path> cached = new AtomicReference<>();

    public WorklistRootResolver(WorklistProperties properties) {
        this.properties = properties;
    }

    /** 配置根目录（绝对路径、已规范化），进程内只解析一次。 */
    public Path root() {
        Path result = cached.get();
        if (result == null) {
            result = resolve();
            cached.set(result);
            log.info("光猫配置根目录: {}", result);
        }
        return result;
    }

    private Path resolve() {
        Path configured = Path.of(properties.getRootPath()).toAbsolutePath().normalize();
        // 目录存在（哪怕为空）就直接用：型号文件/适配档写入时会自行 createDirectories，
        // 空目录不代表配置没准备好。只有目录压根不存在时才尝试回退，
        // 兜住「从 target 目录起 jar 把 rootPath 拼到 target/target/configuration」这类场景
        if (Files.isDirectory(configured)) {
            return configured;
        }
        Path base = jarDirectory();
        if (base != null) {
            List<Path> candidates = List.of(
                    base.resolve("configuration"),
                    base.resolve("target").resolve("configuration"));
            for (Path candidate : candidates) {
                if (Files.isDirectory(candidate)) {
                    log.warn("配置目录 {} 不存在，回退使用 {}", configured, candidate.normalize());
                    return candidate.normalize();
                }
            }
        }
        return configured;
    }

    private static Path jarDirectory() {
        try {
            java.security.CodeSource source = WorklistRootResolver.class
                    .getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return Path.of(source.getLocation().toURI()).getParent();
        } catch (Exception e) {
            log.debug("无法确定 jar 所在目录: {}", e.getMessage());
            return null;
        }
    }
}

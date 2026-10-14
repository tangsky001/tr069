package com.tr069.acs.worklist.service;

import com.tr069.acs.worklist.config.WorklistProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /** 从 fat jar 的嵌套 URL 里取出外层 jar 文件路径：{@code jar:nested:/x/app.jar/!BOOT-INF/classes/!/} */
    private static final java.util.regex.Pattern NESTED_JAR =
            java.util.regex.Pattern.compile("^jar:(?:nested|file):(.+?\\.jar)[/!]");

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
        String value = properties.getRootPath();
        Path configured = Path.of(value);
        // 绝对路径是显式指定（现场部署 / 单元测试注入临时目录），原样尊重
        if (configured.isAbsolute()) {
            return configured.toAbsolutePath().normalize();
        }
        // 相对路径不能按进程工作目录拼：同一个 jar 从 e:/tr069 起和从 e:/tr069/target 起
        // 会落到两个不同的 configuration 目录，适配档案就此分叉（一边写入，另一边读不到）。
        // 统一以 jar 位置为基准推导，保证无论从哪个目录启动都指向同一处。
        Path base = jarDirectory();
        if (base == null) {
            return configured.toAbsolutePath().normalize();
        }
        Path anchor = isBuildOutputDir(base) && base.getParent() != null ? base.getParent() : base;
        Path canonical = anchor.resolve(value).normalize();
        if (Files.isDirectory(canonical)) {
            return canonical;
        }
        // 推导位置还没准备好（例如 IDE 直接从源码目录启动），退回工作目录那一份
        Path byWorkDir = configured.toAbsolutePath().normalize();
        if (Files.isDirectory(byWorkDir)) {
            log.warn("配置目录 {} 不存在，回退使用 {}", canonical, byWorkDir);
            return byWorkDir;
        }
        return canonical;
    }

    /** jar 落在构建产物目录里时，配置根目录应取它的上一级（项目根）。 */
    private static boolean isBuildOutputDir(Path dir) {
        Path name = dir.getFileName();
        if (name == null) {
            return false;
        }
        String n = name.toString();
        return "target".equals(n) || "build".equals(n) || "out".equals(n);
    }

    private static Path jarDirectory() {
        try {
            java.security.CodeSource source = WorklistRootResolver.class
                    .getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            String location = source.getLocation().toString();
            // Spring Boot fat jar：Boot 2 是 jar:file:/.../app.jar!/BOOT-INF/classes!/，
            // Boot 3 改成 jar:nested:/.../app.jar/!BOOT-INF/classes/!/，
            // 两种都要先剥出外层 jar 文件路径，否则拿到的是 jar 内部的目录
            java.util.regex.Matcher nested = NESTED_JAR.matcher(location);
            if (nested.find()) {
                String jarPath = nested.group(1);
                // Windows 下形如 /E:/tr069/... 带一个前导斜杠，Linux 下是正常绝对路径
                if (jarPath.length() > 2 && jarPath.charAt(2) == ':') {
                    jarPath = jarPath.substring(1);
                }
                return asDirectory(Path.of(jarPath));
            }
            java.net.URI uri = source.getLocation().toURI();
            try {
                return asDirectory(uri);
            } catch (IllegalArgumentException notAbsoluteFileUri) {
                // 相对路径启动（cd 到 jar 目录后 java -jar xxx.jar）时是相对 URI
                return asDirectory(Path.of(uri.toString()));
            }
        } catch (Exception e) {
            log.debug("无法确定 jar 所在目录: {}", e.getMessage());
            return null;
        }
    }

    /** 定位到 jar 所在目录：入参可能是 jar 文件，也可能是 classes 目录。 */
    private static Path asDirectory(java.net.URI uri) {
        return asDirectory(Path.of(uri));
    }

    private static Path asDirectory(Path path) {
        Path absolute = path.isAbsolute() ? path : path.toAbsolutePath();
        return Files.isRegularFile(absolute.normalize())
                ? absolute.normalize().getParent()
                : absolute.normalize();
    }
}

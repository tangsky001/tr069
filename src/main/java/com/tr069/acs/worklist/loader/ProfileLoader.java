package com.tr069.acs.worklist.loader;

import com.tr069.acs.common.exception.ConfigLoadException;
import com.tr069.acs.worklist.model.DeviceProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 光猫适配档案（DeviceProfile）解析器。
 */
@Slf4j
@Component
public class ProfileLoader {

    private final Yaml yaml = new Yaml();

    /**
     * 解析单个 profile 文件。
     *
     * @param file          yaml 文件
     * @param relativePath  相对配置根目录的路径
     * @return 加载结果
     */
    public LoadedItem<DeviceProfile> load(Path file, String relativePath) {
        long lastModified = safeLastModified(file);
        try {
            DeviceProfile profile = parse(file);
            normalize(profile, file, lastModified);
            validate(profile);
            return LoadedItem.ok(file, relativePath, profile, lastModified);
        } catch (ConfigLoadException e) {
            log.warn("光猫适配档案解析失败: {} -> {}", relativePath, e.getMessage());
            return LoadedItem.fail(file, relativePath, e.getMessage(), lastModified);
        } catch (Exception e) {
            log.warn("光猫适配档案读取异常: {}", relativePath, e);
            return LoadedItem.fail(file, relativePath, String.valueOf(e.getMessage()), lastModified);
        }
    }

    /**
     * 读取并反序列化 YAML。
     */
    private DeviceProfile parse(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            DeviceProfile profile = yaml.loadAs(in, DeviceProfile.class);
            if (profile == null) {
                throw new ConfigLoadException(String.valueOf(file), "文件内容为空");
            }
            return profile;
        } catch (YAMLException e) {
            throw new ConfigLoadException(file.toString(), "YAML 格式错误: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ConfigLoadException(file.toString(), "文件读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 补齐由文件名推导的默认字段。
     */
    private void normalize(DeviceProfile profile, Path file, long lastModified) {
        profile.setSourceFile(file.toString());
        profile.setLastModified(lastModified);
        profile.setValid(true);
        profile.setError(null);

        String fileName = file.getFileName().toString();
        String baseName = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;
        if (profile.getKey() == null || profile.getKey().isBlank()) {
            profile.setKey(baseName);
        }
        if (profile.getName() == null || profile.getName().isBlank()) {
            profile.setName(profile.getKey());
        }
    }

    /**
     * 校验档案合法性，尤其是正则类字段，避免运行期才发现写错。
     */
    private void validate(DeviceProfile profile) {
        DeviceProfile.MatchRule match = profile.getMatch();
        if (match != null) {
            checkRegex("match.manufacturer", match.getManufacturer());
            checkRegex("match.oui", match.getOui());
            checkRegex("match.productClass", match.getProductClass());
            checkRegex("match.softwareVersion", match.getSoftwareVersion());
            checkRegex("match.serialNumber", match.getSerialNumber());
        }
        if (profile.getCapability() != null
                && profile.getCapability().getSupportedRpc() != null) {
            for (String rpc : profile.getCapability().getSupportedRpc()) {
                if (com.tr069.acs.cwmp.RpcMethod.of(rpc) == null) {
                    throw new ConfigLoadException(profile.getSourceFile(),
                            "capability.supportedRpc 含未知方法: " + rpc);
                }
            }
        }
    }

    /**
     * 确认字符串是合法正则。
     */
    private void checkRegex(String field, String regex) {
        if (regex == null || regex.isBlank()) {
            return;
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ConfigLoadException("", field + " 非法正则: " + regex);
        }
    }

    private long safeLastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}

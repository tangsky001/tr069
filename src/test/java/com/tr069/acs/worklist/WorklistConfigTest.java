package com.tr069.acs.worklist;

import com.tr069.acs.worklist.loader.LoadedItem;
import com.tr069.acs.worklist.loader.ProfileLoader;
import com.tr069.acs.worklist.model.DeviceProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 光猫适配档案解析链路验证。
 *
 * <p>覆盖：YAML -> 对象，这一段是热加载能否真正生效的关键。</p>
 */
class WorklistConfigTest {

    private static final Path CLASSES = Path.of("target", "test-classes");

    private final ProfileLoader profileLoader = new ProfileLoader();

    @Test
    void shouldLoadSampleProfile() {
        Path file = CLASSES.resolve("config-sample/profiles/_example-device-profile.yaml");
        assertTrue(Files.exists(file), "示例档案应随构建输出到 classes 目录");

        LoadedItem<DeviceProfile> item = profileLoader.load(file, file.toString());
        assertTrue(item.isValid(), "示例档案应解析成功: " + item.getError());

        DeviceProfile profile = item.getValue();
        assertEquals("example-huawei-hg8245", profile.getKey());
        assertEquals("A0B1C2", profile.getMatch().getOui());
        assertEquals(64, profile.getCapability().getMaxParametersPerRequest());
        assertTrue(profile.getParameterAliases().containsKey("WANConnectionDevice"));
    }

    /**
     * 坏文件必须降级跳过，不能让一份写错的档案拖垮整批热加载。
     *
     * <p>注意：这个用例会打一条 {@code 光猫适配档案解析失败 ... profile-broken*.yaml}
     * 的 WARN，是预期内的，不是故障。</p>
     */
    @Test
    void shouldReportErrorOnBadYaml(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("profile-broken.yaml");
        Files.write(bad, List.of("key: [unclosed", "  bad: : value"));

        LoadedItem<DeviceProfile> item = profileLoader.load(bad, bad.getFileName().toString());
        assertFalse(item.isValid(),
                "畸形 YAML 应被标记为解析失败而不是抛异常终止整个加载过程");
        assertTrue(item.getError() != null && !item.getError().isBlank(),
                "失败原因要带出来，运维据此知道该改哪个文件");
    }
}

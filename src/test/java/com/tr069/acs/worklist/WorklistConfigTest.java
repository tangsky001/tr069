package com.tr069.acs.worklist;

import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.worklist.engine.ExecutionContext;
import com.tr069.acs.worklist.engine.StepArgsFactory;
import com.tr069.acs.worklist.loader.LoadedItem;
import com.tr069.acs.worklist.loader.ProfileLoader;
import com.tr069.acs.worklist.loader.WorklistLoader;
import com.tr069.acs.worklist.model.DeviceProfile;
import com.tr069.acs.worklist.model.WorklistTemplate;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 光猫适配档案与工单模板的解析链路验证。
 *
 * <p>覆盖：YAML -> 对象 -> 占位符替换 -> CWMP 参数构造，
 * 这一段是热加载能否真正生效的关键。</p>
 */
class WorklistConfigTest {

    private static final Path CLASSES = Path.of("target", "classes");

    private final ProfileLoader profileLoader = new ProfileLoader();
    private final WorklistLoader worklistLoader = new WorklistLoader();

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

    @Test
    void shouldLoadSampleWorklistAndDeriveKey() {
        Path file = CLASSES.resolve(
                "config-sample/operators/CT/v3.0/business/ADSL_4+1/WAN_ADD.yaml");
        assertTrue(Files.exists(file), "示例工单应随构建输出到 classes 目录");

        LoadedItem<WorklistTemplate> item = worklistLoader.load(file, CLASSES);
        assertTrue(item.isValid(), "示例工单应解析成功: " + item.getError());

        WorklistTemplate template = item.getValue();
        assertEquals("CT/v3.0/business/ADSL_4+1/WAN_ADD", template.getKey());
        assertEquals("CT", template.getIsp());
        assertEquals("v3.0", template.getVersion());
        assertEquals("business", template.getDomain());
        assertEquals("ADSL_4+1", template.getDeviceType());
        assertEquals(2, template.getSteps().size());
        assertEquals("AddObject", template.getSteps().get(0).getRpc());
        assertEquals(5, template.getArgs().size());
    }

    @Test
    void shouldResolvePlaceholderFromArgsAndAliases() {
        Map<String, String> params = Map.of("ConnectionType", "PPPoE_Routed");
        Map<String, String> aliases = Map.of("WANConnectionDevice",
                "InternetGatewayDevice.WANDevice.1.WANConnectionDevice");
        Map<String, String> device = Map.of("sn", "A0B1C2123456");
        ExecutionContext ctx = new ExecutionContext(params, aliases, device);
        ctx.put("wanInstance", "3");

        assertEquals("PPPoE_Routed", ctx.resolve("${ConnectionType}"));
        assertEquals("A0B1C2123456", ctx.resolve("${sn}"));
        assertEquals("3", ctx.resolve("${ctx.wanInstance}"));
        assertEquals("InternetGatewayDevice.WANDevice.1.WANConnectionDevice",
                ctx.resolve("${WANConnectionDevice}"));
        assertEquals("InternetGatewayDevice.WANDevice.1.WANConnectionDevice.3.WANIPConnection.Enable",
                ctx.resolve("${WANConnectionDevice}.${ctx.wanInstance}.WANIPConnection.Enable"));
        // 未提供的占位符保持原样，便于在结果中发现漏填
        assertEquals("${Missing}", ctx.resolve("${Missing}"));
    }

    @Test
    void shouldBuildAddObjectArgsWithResolvedPath() {
        Path file = CLASSES.resolve(
                "config-sample/operators/CT/v3.0/business/ADSL_4+1/WAN_ADD.yaml");
        LoadedItem<WorklistTemplate> item = worklistLoader.load(file, CLASSES);
        WorklistTemplate template = item.getValue();

        Path profileFile = CLASSES.resolve("config-sample/profiles/_example-device-profile.yaml");
        DeviceProfile profile = profileLoader.load(profileFile, profileFile.toString()).getValue();

        ExecutionContext ctx = new ExecutionContext(
                Map.of("ConnectionType", "IP_Routed", "Enable", "true"),
                profile.getParameterAliases(),
                Map.of("sn", "A0B1C2123456"));

        Map<String, Object> addArgs = StepArgsFactory.build(template.getSteps().get(0), ctx);
        assertEquals("InternetGatewayDevice.WANDevice.1.WANConnectionDevice",
                addArgs.get("ObjectName"));

        Map<String, Object> setArgs = StepArgsFactory.build(template.getSteps().get(1), ctx);
        @SuppressWarnings("unchecked")
        List<ParameterValueStruct> list =
                (List<ParameterValueStruct>) setArgs.get("ParameterList");
        assertNotNull(list);
        assertEquals(5, list.size());
        assertEquals("IP_Routed", list.get(0).getValue());
        // 布尔字面值应推断为 boolean 而非 string
        assertEquals("boolean", list.get(4).getValueType());
    }

    @Test
    void shouldReportErrorOnBadYaml() throws Exception {
        Path bad = Files.createTempFile("profile-broken", ".yaml");
        Files.write(bad, List.of("key: [unclosed", "  bad: : value"));

        LoadedItem<DeviceProfile> item = profileLoader.load(bad, bad.getFileName().toString());
        assertFalse(item.isValid(),
                "畸形 YAML 应被标记为解析失败而不是抛异常终止整个加载过程");
        Files.deleteIfExists(bad);
    }
}

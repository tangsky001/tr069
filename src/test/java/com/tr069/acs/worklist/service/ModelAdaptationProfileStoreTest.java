package com.tr069.acs.worklist.service;

import com.tr069.acs.domain.entity.CpeModel;
import com.tr069.acs.worklist.config.WorklistProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 型号适配档：只有测试通过写下的 yml（adapted: true）才算已适配，
 * 光有 xml 能力树不算；文件按运营商分目录。
 */
class ModelAdaptationProfileStoreTest {

    @TempDir
    Path root;

    private ModelAdaptationProfileStore store() {
        WorklistProperties properties = Mockito.mock(WorklistProperties.class);
        Mockito.when(properties.getRootPath()).thenReturn(root.toString());
        return new ModelAdaptationProfileStore(properties, new WorklistRootResolver(properties));
    }

    private static CpeModel model(String isp, String modelKey) {
        CpeModel model = new CpeModel();
        model.setIsp(isp);
        model.setModelKey(modelKey);
        model.setConfigPath(ModelTreeStore.MODEL_DIR + "/"
                + ModelMappingService.defaultConfigPath(isp, modelKey));
        model.setAdaptPath(ModelTreeStore.MODEL_DIR + "/"
                + ModelMappingService.defaultAdaptPath(isp, modelKey));
        return model;
    }

    @Test
    void notAdaptedWithoutProfile() throws Exception {
        // 能力树 xml 存在，但没有 yml —— 依然算未适配
        CpeModel model = model("CMCC", "P22G-CLAC_V1.0.00.488");
        Path xml = root.resolve(model.getConfigPath());
        Files.createDirectories(xml.getParent());
        Files.writeString(xml, "<ModelTree/>");
        assertThat(Files.isRegularFile(xml)).isTrue();
        assertThat(store().isAdapted(model)).isFalse();
        assertThat(store().read(model)).isEmpty();
    }

    @Test
    void adaptedOnlyWhenProfileSaysTrue() throws Exception {
        CpeModel model = model("CMCC", "P22G-CLAC_V1.0.00.488");
        ModelAdaptationProfileStore store = store();
        ModelAdaptationService.AdaptRequest req = new ModelAdaptationService.AdaptRequest();
        req.setIsp("CMCC");
        req.setLanPorts("4");
        req.setLanSpeed("1000M");
        req.setSipCount("2");
        req.setWifiSpec("2.4G+5.8G");

        String path = store.writeAdapted(model, "CMCC", "CIOT29AC1489", req);
        // 入库值统一为相对配置根的 models/... 形式，与 cpe_model.config_path 同一套约定
        assertThat(path).startsWith("models/").contains("CM").endsWith(".yml");
        // 与能力树 xml 同名同目录
        Path file = root.resolve(path);
        assertThat(Files.isRegularFile(file)).isTrue();
        assertThat(Files.isRegularFile(root.resolve(model.getConfigPath()))).isFalse();

        Map<String, String> profile = store.read(model);
        assertThat(profile.get("adapted")).isEqualTo("true");
        assertThat(profile.get("model_key")).isEqualTo("P22G-CLAC_V1.0.00.488");
        assertThat(profile.get("isp")).isEqualTo("CMCC");
        assertThat(profile.get("lan_ports")).isEqualTo("4");
        assertThat(profile.get("wifi_spec")).isEqualTo("2.4G+5.8G");
        assertThat(profile.get("sample_sn")).isEqualTo("CIOT29AC1489");
        assertThat(store.isAdapted(model)).isTrue();
    }

    @Test
    void pathsSplitByIsp() {
        assertThat(ModelMappingService.defaultConfigPath("CMCC", "P22G-CLAC_V1.0.00.488"))
                .isEqualTo("CM/standard/p22g-clac_v1.0.00.488.xml");
        assertThat(ModelMappingService.defaultAdaptPath("CMCC", "P22G-CLAC_V1.0.00.488"))
                .isEqualTo("CM/standard/p22g-clac_v1.0.00.488.yml");
        assertThat(ModelMappingService.defaultConfigPath("CT", "HS8145V5_V5R19"))
                .isEqualTo("CT/standard/hs8145v5_v5r19.xml");
        assertThat(ModelMappingService.defaultConfigPath("CU", "PON"))
                .isEqualTo("CU/standard/pon.xml");
        assertThat(ModelMappingService.defaultConfigPath(null, "PON"))
                .isEqualTo("standard/standard/pon.xml");
    }
}

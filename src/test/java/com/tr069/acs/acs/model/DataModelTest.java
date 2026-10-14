package com.tr069.acs.acs.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双数据模型的识别与前缀展开。
 *
 * <p>这里测的都是「静默失败」类型的问题：判定错了不会抛异常，只会让某个业务分区查不到数据。</p>
 */
class DataModelTest {

    /**
     * 只看第一条参数会被私有扩展节点带偏，必须按全部参数取多数。
     */
    @Test
    void detectsByMajorityOfReportedPaths() {
        assertThat(DataModel.detect(List.of(
                "InternetGatewayDevice.DeviceInfo.SerialNumber",
                "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.ExternalIPAddress")))
                .isEqualTo(DataModel.TR098);

        assertThat(DataModel.detect(List.of(
                "Device.DeviceInfo.SerialNumber",
                "Device.WAN.1.IPConnectionNumberOfEntries",
                "Device.WiFi.SSID.1.SSID")))
                .isEqualTo(DataModel.TR181);
    }

    /**
     * 首条参数是厂商私有节点时仍要以多数为准，这是换成遍历判定之前的真实坑。
     */
    @Test
    void ignoresUnknownFirstParameter() {
        assertThat(DataModel.detect(List.of(
                "X_CT-COM_SomeVendorNode.",
                "Device.DeviceInfo.SerialNumber",
                "Device.DeviceInfo.SoftwareVersion")))
                .isEqualTo(DataModel.TR181);
    }

    /**
     * 一条都不认得时退回 TR-098，保持与旧版一致的行为。
     */
    @Test
    void fallsBackToTr098WhenNothingMatches() {
        assertThat(DataModel.detect(List.of("X_Unknown.1.", "VendorOnly")))
                .isEqualTo(DataModel.TR098);
        assertThat(DataModel.detect(List.of())).isEqualTo(DataModel.TR098);
    }

    /**
     * 业务前缀必须同时覆盖两种模型，否则 TR-181 设备的 WAN 分区会查空。
     */
    @Test
    void businessPrefixesCoverBothModels() {
        assertThat(DataModel.businessPrefixes("wan"))
                .contains("InternetGatewayDevice.WANDevice.", "Device.WAN.");
        assertThat(DataModel.businessPrefixes("wifi"))
                .contains("InternetGatewayDevice.LANDevice.", "Device.WiFi.");
        assertThat(DataModel.businessPrefixes("sip"))
                .contains("InternetGatewayDevice.Services.VoiceService.", "Device.VoiceService.");
        assertThat(DataModel.businessPrefixes("unknown")).isEmpty();
    }

    /**
     * 根节点字符串反查模型。
     */
    @Test
    void resolvesRootNodeString() {
        assertThat(DataModel.ofRoot("Device.")).isEqualTo(DataModel.TR181);
        assertThat(DataModel.ofRoot("InternetGatewayDevice.")).isEqualTo(DataModel.TR098);
        assertThat(DataModel.ofRoot(null)).isEqualTo(DataModel.TR098);
    }
}

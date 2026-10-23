package com.tr069.acs.acs.config;

import com.tr069.acs.acs.model.Isp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集标准的配置绑定：yml 里按运营商写的一段要能落到位。
 *
 * <p>这类配置错了不会报错，只是默默按默认值走——电信定制机的采集标准配了没生效，
 * 排查时很难往「配置没绑上」上想，所以这里把绑定固定住。</p>
 */
class CapabilityProfilesTest {

    @Test
    void 按运营商绑定采集标准() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("acs.device.capability.profiles.CMCC.name", "中国移动");
        source.put("acs.device.capability.profiles.CT.name", "中国电信");
        source.put("acs.device.capability.profiles.CT.gpn-strategy", "level-first");
        source.put("acs.device.capability.profiles.CT.subtrees[0]", "WANDevice.1.");
        source.put("acs.device.capability.profiles.CT.subtrees[1]", "LANDevice.");
        source.put("acs.device.capability.profiles.CT.instance-probe", "false");
        source.put("acs.device.capability.profiles.CT.max-instances", "6");

        CapabilityProfiles props = new Binder(new MapConfigurationPropertySource(source))
                .bind("acs.device.capability", Bindable.of(CapabilityProfiles.class))
                .get();

        assertThat(props.forIsp(Isp.CT).getName()).isEqualTo("中国电信");
        assertThat(props.forIsp(Isp.CT).getSubtrees()).containsExactly("WANDevice.1.", "LANDevice.");
        assertThat(props.forIsp(Isp.CT).isLevelFirst()).isTrue();
        assertThat(props.forIsp(Isp.CT).isInstanceProbe()).isFalse();
        assertThat(props.forIsp(Isp.CT).getMaxInstances()).isEqualTo(6);

        // 大小写无关
        assertThat(props.forIsp(Isp.of("ctcc")).getName()).isEqualTo("中国电信");
        // 没配的运营商走写死的那份
        assertThat(props.forIsp(Isp.CBN).getName()).isEqualTo("中国广电");
        assertThat(props.forIsp(Isp.CBN).isInstanceProbe()).isTrue();

        // 总开关关掉后不按运营商区分，一律用通用档
        props.setProfileEnabled(false);
        assertThat(props.forIsp(Isp.CT).getName()).isEqualTo("通用");
    }

    /**
     * 配置里没有这个模块时（默认部署就是如此），四家各自有一份写死的标准。
     */
    @Test
    void 未配置时四家各有内置采集标准() {
        CapabilityProfiles props = new CapabilityProfiles();

        assertThat(props.forIsp(Isp.CMCC).getName()).isEqualTo("中国移动");
        assertThat(props.forIsp(Isp.CT).getName()).isEqualTo("中国电信");
        assertThat(props.forIsp(Isp.CU).getName()).isEqualTo("中国联通");
        assertThat(props.forIsp(Isp.CBN).getName()).isEqualTo("中国广电");
        assertThat(props.forIsp(Isp.STANDARD).getName()).isEqualTo("通用");
        assertThat(props.forIsp(null).getName()).isEqualTo("通用");

        // 电信档自适应：先试参数树，扫不动再降级（B866 扫不动，HS8145C5 能扫）
        assertThat(props.forIsp(Isp.CT).isAdaptive()).isTrue();
        assertThat(props.forIsp(Isp.CMCC).isAdaptive()).isFalse();
        assertThat(props.forIsp(Isp.CT).isInstanceProbe()).isTrue();
        assertThat(props.forIsp(Isp.CT).getMaxInstances()).isEqualTo(4);
        // 已知路径兜底清单：GPN 一句都问不出来时靠它认出这是什么猫
        assertThat(props.forIsp(Isp.CT).getProbePaths())
                .contains("DeviceInfo.SpecVersion", "DeviceInfo.ProductClass");
        // 天翼私有分支（X_CTC_）遍历进去必抛 9002，逐层展开要绕开
        assertThat(props.forIsp(Isp.CT).getSkipPrefixes()).contains("X_CTC_");
    }

    /**
     * 天翼网关不与移动共用同一份默认：自带子树与兜底路径，递归让位给逐层。
     */
    @Test
    void 电信档自带子树与兜底路径() {
        CapabilityProfiles props = new CapabilityProfiles();

        // 电信写了自己的清单，不再留空吃「按移动验证过」的内置默认
        assertThat(props.forIsp(Isp.CT).getSubtrees())
                .contains("WANDevice.", "LANDevice.", "Services.VoiceService.", "X_CT-COM_UserInfo.");
        // 兜底除设备身份外还带天翼业务参数
        assertThat(props.forIsp(Isp.CT).getProbePaths())
                .contains("X_CT-COM_UserInfo.UserId", "X_CT-COM_UserInfo.UserName");
        // 移动仍用内置默认
        assertThat(props.forIsp(Isp.CMCC).getSubtrees()).isEmpty();
        assertThat(props.forIsp(Isp.CMCC).getProbePaths()).doesNotContain("X_CT-COM_UserInfo.UserId");
    }

    /**
     * 同为电信天翼网关，固件差异极大：B866 连「只列下一层」的 GPN 都回 9002，
     * HS8145C5 却能递归拉全树。所以电信档是自适应：先试参数树，撞 9002 再降级。
     * 移动仍按递归扫整棵，不需要降级这套。
     */
    @Test
    void 电信档自适应其余照常扫树() {
        CapabilityProfiles props = new CapabilityProfiles();

        assertThat(props.forIsp(Isp.CT).isAdaptive()).isTrue();
        assertThat(props.forIsp(Isp.CMCC).isAdaptive()).isFalse();
        assertThat(props.forIsp(Isp.STANDARD).isAdaptive()).isFalse();
        // 自适应不等于不扫树：能扫的设备要走完整采集，别白丢能力树
        assertThat(props.forIsp(Isp.CT).isValuesOnly()).isFalse();
        // 只读关键字段靠这份清单撑着：设备身份 + 天翼业务标识 + 常用入口
        assertThat(props.forIsp(Isp.CT).getProbePaths())
                .contains("DeviceInfo.Manufacturer", "DeviceInfo.ProductClass",
                        "DeviceInfo.ProvisioningCode", "ManagementServer.ConnectionRequestURL",
                        "WANDevice.1.WANCommonInterfaceConfig.WANAccessType",
                        "X_CT-COM_UserInfo.UserId");
    }
}

package com.tr069.acs.acs.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运营商归一化与识别。
 */
class IspTest {

    @Test
    void 归一化各种写法() {
        assertThat(Isp.of("CMCC")).isEqualTo(Isp.CMCC);
        assertThat(Isp.of("CM")).isEqualTo(Isp.CMCC);
        assertThat(Isp.of("ct")).isEqualTo(Isp.CT);
        assertThat(Isp.of("CTCC")).isEqualTo(Isp.CT);
        assertThat(Isp.of("CUCC")).isEqualTo(Isp.CU);
        assertThat(Isp.of("CBN")).isEqualTo(Isp.CBN);
        assertThat(Isp.of("standard")).isEqualTo(Isp.STANDARD);
        // 空值与认不出的写法都归到通用档，采集按通用档走，不至于因脏数据中断
        assertThat(Isp.of(null)).isEqualTo(Isp.STANDARD);
        assertThat(Isp.of(" ")).isEqualTo(Isp.STANDARD);
        assertThat(Isp.of("某省广电")).isEqualTo(Isp.STANDARD);
    }

    @Test
    void 目录名与代码分开() {
        assertThat(Isp.CMCC.getDir()).isEqualTo("CM");
        assertThat(Isp.CT.getDir()).isEqualTo("CT");
        assertThat(Isp.CU.getDir()).isEqualTo("CU");
        assertThat(Isp.CBN.getDir()).isEqualTo("CBN");
        assertThat(Isp.STANDARD.getDir()).isEqualTo("standard");
        assertThat(Isp.ofDir("CM")).isEqualTo(Isp.CMCC);
    }

    /**
     * 设备台账的运营商往往是空的：认私有前缀，采完一轮就能把运营商补上。
     */
    @Test
    void 按私有前缀识别运营商() {
        assertThat(Isp.detect(List.of(
                "InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1."
                        + "WANIPConnection.1.X_CT-COM_VLANIDMark"))).isEqualTo(Isp.CT);
        assertThat(Isp.detect(List.of(
                "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.X_CMCC_SSID")))
                .isEqualTo(Isp.CMCC);
        assertThat(Isp.detect(List.of("InternetGatewayDevice.DeviceInfo.SoftwareVersion"))).isNull();
        assertThat(Isp.detect(List.of())).isNull();
        assertThat(Isp.detect(null)).isNull();
    }

    /**
     * 电信两套私有前缀：{@code X_CT-COM_} 是标准扩展，{@code X_CTC_} 是天翼那一套
     * （插件 / ITMS 探针 / 天翼管家 / DPI 软探针）。只出现 X_CTC_ 也要认出是电信。
     */
    @Test
    void 认电信天翼私有前缀() {
        assertThat(Isp.detect(List.of("InternetGatewayDevice.X_CTC_IPTV."))).isEqualTo(Isp.CT);
        assertThat(Isp.detect(List.of("InternetGatewayDevice.X_CTC_TelecomItms.Enable")))
                .isEqualTo(Isp.CT);
        // 移动那套不受影响
        assertThat(Isp.detect(List.of("InternetGatewayDevice.X_CMCC_VLANMode")))
                .isEqualTo(Isp.CMCC);
    }
}

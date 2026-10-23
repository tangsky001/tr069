package com.tr069.acs.acs.service;

import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.common.log.AcsCoreLog;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.worklist.service.ModelMappingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备台账落库：运营商的认定时机与覆盖规则。
 */
class DeviceRegistryServiceTest {

    /**
     * 运营商私有前缀是随 Inform 一起上来的（电信 {@code X_CT-COM_UserInfo} 就是），
     * 入库时就认出来，采集第一轮就能按电信档（逐层下钻）走；
     * 等采完能力树再认的话，第一轮只能按通用档先试递归，电信定制机对递归必回 9002。
     */
    @Test
    void 首次入库按Inform私有前缀登记运营商() {
        CpeDeviceMapper mapper = Mockito.mock(CpeDeviceMapper.class);
        Mockito.when(mapper.selectOne(Mockito.any())).thenReturn(null);
        DeviceRegistryService service = newService(mapper);

        service.upsertOnline(session(), List.of(
                "InternetGatewayDevice.DeviceInfo.SpecVersion",
                "InternetGatewayDevice.X_CT-COM_UserInfo.UserId"));

        ArgumentCaptor<CpeDevice> captor = ArgumentCaptor.forClass(CpeDevice.class);
        Mockito.verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getIsp()).isEqualTo("CT");
    }

    /** 报告里只有标准参数：认不出运营商就留空，等采完能力树再看。 */
    @Test
    void 没有私有前缀时不猜运营商() {
        CpeDeviceMapper mapper = Mockito.mock(CpeDeviceMapper.class);
        Mockito.when(mapper.selectOne(Mockito.any())).thenReturn(null);
        DeviceRegistryService service = newService(mapper);

        service.upsertOnline(session(), List.of(
                "InternetGatewayDevice.DeviceInfo.SpecVersion",
                "InternetGatewayDevice.DeviceInfo.SoftwareVersion"));

        ArgumentCaptor<CpeDevice> captor = ArgumentCaptor.forClass(CpeDevice.class);
        Mockito.verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getIsp()).isNull();
    }

    /** 库里已有运营商（人工适配申报或早先认出）的以库里为准，不被自动识别覆盖。 */
    @Test
    void 已登记运营商的不覆盖() {
        CpeDeviceMapper mapper = Mockito.mock(CpeDeviceMapper.class);
        CpeDevice exist = new CpeDevice();
        exist.setId(1L);
        exist.setIsp("CMCC");
        Mockito.when(mapper.selectOne(Mockito.any())).thenReturn(exist);
        DeviceRegistryService service = newService(mapper);

        service.upsertOnline(session(), List.of("InternetGatewayDevice.X_CT-COM_UserInfo.UserId"));

        ArgumentCaptor<CpeDevice> captor = ArgumentCaptor.forClass(CpeDevice.class);
        Mockito.verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getIsp()).isNull();
    }

    private static DeviceRegistryService newService(CpeDeviceMapper mapper) {
        return new DeviceRegistryService(mapper, Mockito.mock(ModelMappingService.class),
                Mockito.mock(AcsCoreLog.class));
    }

    private static CpeSession session() {
        CpeSession session = Mockito.mock(CpeSession.class);
        Mockito.when(session.getSn()).thenReturn("37300148C4AA410D6");
        return session;
    }
}

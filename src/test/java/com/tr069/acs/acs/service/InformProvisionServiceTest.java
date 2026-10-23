package com.tr069.acs.acs.service;

import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.cwmp.model.EventStruct;
import com.tr069.acs.cwmp.model.InformRequest;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.mapper.CpeDeviceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * 设备纳管：Inform 后是否按配置把 ACS 地址与上报间隔写回设备。
 */
class InformProvisionServiceTest {

    private final CwmpRpcService rpc = Mockito.mock(CwmpRpcService.class);

    private final CpeDeviceMapper deviceMapper = Mockito.mock(CpeDeviceMapper.class);

    private InformProvisionService service() {
        Mockito.when(rpc.setParameterValues(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        InformProvisionService service = new InformProvisionService(rpc, deviceMapper);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "informIntervalSeconds", 300L);
        ReflectionTestUtils.setField(service, "acsUrl", "");
        ReflectionTestUtils.setField(service, "publicHost", "10.0.0.1");
        ReflectionTestUtils.setField(service, "listenPorts", "9090,8082");
        ReflectionTestUtils.setField(service, "cpePath", "/ACS-server/ACS");
        ReflectionTestUtils.setField(service, "reprovisionIntervalHours", 24L);
        ReflectionTestUtils.setField(service, "forceOnBootstrap", true);
        ReflectionTestUtils.setField(service, "crUsername", "");
        ReflectionTestUtils.setField(service, "crPassword", "");
        return service;
    }

    private static CpeSession session() {
        CpeSession session = Mockito.mock(CpeSession.class);
        Mockito.when(session.getSn()).thenReturn("SN001");
        Mockito.when(session.getRootNode()).thenReturn("InternetGatewayDevice.");
        return session;
    }

    private static InformRequest inform(String... nameValues) {
        List<ParameterValueStruct> params = new ArrayList<>();
        for (int i = 0; i + 1 < nameValues.length; i += 2) {
            params.add(ParameterValueStruct.of(nameValues[i], nameValues[i + 1]));
        }
        return InformRequest.builder().parameterList(params).eventList(List.of()).build();
    }

    private static InformRequest bootstrap(String... nameValues) {
        InformRequest request = inform(nameValues);
        request.setEventList(List.of(EventStruct.builder().eventCode("0 BOOTSTRAP").build()));
        return request;
    }

    @Test
    void 首次上报下发地址间隔与连接请求凭据() {
        service().onInform(session(),
                inform("InternetGatewayDevice.DeviceInfo.SoftwareVersion", "V1"));

        List<ParameterValueStruct> values = captured();
        assertThat(values).hasSize(4);
        assertThat(values.get(0).getName()).isEqualTo("InternetGatewayDevice.ManagementServer.URL");
        assertThat(values.get(0).getValue()).isEqualTo("http://10.0.0.1:9090/ACS-server/ACS");
        assertThat(values.get(1).getName())
                .isEqualTo("InternetGatewayDevice.ManagementServer.PeriodicInformInterval");
        assertThat(values.get(1).getValue()).isEqualTo("300");
        assertThat(values.get(2).getName())
                .isEqualTo("InternetGatewayDevice.ManagementServer.ConnectionRequestUsername");
        assertThat(values.get(2).getValue()).isEqualTo("itms");
        assertThat(values.get(3).getName())
                .isEqualTo("InternetGatewayDevice.ManagementServer.ConnectionRequestPassword");
        assertThat(values.get(3).getValue()).isEqualTo("itms");
    }

    /**
     * 各批次光猫出厂的 CR 密码互不相同，纳管时统一改写，ACS 才知道拿什么去唤醒它。
     * 配置里留空或写 auto 都按 itms 处理。
     */
    @Test
    void 连接请求凭据按配置下发且默认itms() {
        InformProvisionService service = service();
        ReflectionTestUtils.setField(service, "crUsername", "cmccadmin");
        ReflectionTestUtils.setField(service, "crPassword", "abc123");

        service.onInform(session(), inform());

        List<ParameterValueStruct> values = captured();
        assertThat(values.get(2).getValue()).isEqualTo("cmccadmin");
        assertThat(values.get(3).getValue()).isEqualTo("abc123");
    }

    /** 凭据改写成功才登记台账：唤醒设备要拿台账上的这对账号密码去认证。 */
    @Test
    void 凭据写成功后登记进台账() {
        service().onInform(session(), inform());

        org.mockito.ArgumentCaptor<CpeDevice> captor =
                org.mockito.ArgumentCaptor.forClass(CpeDevice.class);
        Mockito.verify(deviceMapper).update(captor.capture(), any());
        assertThat(captor.getValue().getAcsToCpeName()).isEqualTo("itms");
        assertThat(captor.getValue().getAcsToCpePassword()).isEqualTo("itms");
    }

    @Test
    void 上报值已一致时不再下发() {
        service().onInform(session(), inform(
                "InternetGatewayDevice.ManagementServer.URL", "http://10.0.0.1:9090/ACS-server/ACS",
                "InternetGatewayDevice.ManagementServer.PeriodicInformInterval", "300",
                "InternetGatewayDevice.ManagementServer.ConnectionRequestUsername", "itms",
                "InternetGatewayDevice.ManagementServer.ConnectionRequestPassword", "itms"));

        Mockito.verify(rpc, Mockito.never()).setParameterValues(any(), any(), any());
    }

    @Test
    void 刚下发过的设备不再重复下发() {
        InformProvisionService service = service();
        // 设备上报的地址是原厂平台，第一次把 ACS 地址写下去
        service.onInform(session(), inform(
                "InternetGatewayDevice.ManagementServer.URL", "http://old-platform"));
        Mockito.verify(rpc, Mockito.times(1)).setParameterValues(any(), any(), any());

        // 设备紧接着又上报一次（写入还没反映到 Inform 里），24 小时内不重发
        service.onInform(session(), inform(
                "InternetGatewayDevice.ManagementServer.URL", "http://old-platform"));
        Mockito.verify(rpc, Mockito.times(1)).setParameterValues(any(), any(), any());

        // BOOTSTRAP 是恢复出厂，地址会被改回原厂，强制重发
        service.onInform(session(), bootstrap(
                "InternetGatewayDevice.ManagementServer.URL", "http://old-platform"));
        Mockito.verify(rpc, Mockito.times(2)).setParameterValues(any(), any(), any());
    }

    @Test
    void 按数据模型根节点拼路径() {
        CpeSession session = Mockito.mock(CpeSession.class);
        Mockito.when(session.getSn()).thenReturn("SN002");
        Mockito.when(session.getRootNode()).thenReturn("Device.");

        service().onInform(session, inform());

        assertThat(captured().get(0).getName()).isEqualTo("Device.ManagementServer.URL");
    }

    @SuppressWarnings("unchecked")
    private List<ParameterValueStruct> captured() {
        ArgumentCaptor<List<ParameterValueStruct>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(rpc).setParameterValues(any(), captor.capture(), any());
        return captor.getValue();
    }
}

package com.tr069.acs.acs.service;

import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LAN 网口状态定时刷新：只对在线、且采过网口参数的设备下发读取指令。
 */
class LanStatusRefreshServiceTest {

    private final SessionManager sessionManager = Mockito.mock(SessionManager.class);
    private final DeviceBusinessService businessService = Mockito.mock(DeviceBusinessService.class);
    private final DeviceCapabilityService capabilityService =
            Mockito.mock(DeviceCapabilityService.class);

    private LanStatusRefreshService service(boolean enabled) {
        LanStatusRefreshService svc =
                new LanStatusRefreshService(sessionManager, businessService, capabilityService);
        ReflectionTestUtils.setField(svc, "enabled", enabled);
        ReflectionTestUtils.setField(svc, "waitSeconds", 5L);
        ReflectionTestUtils.setField(svc, "maxDevicesPerRound", 10);
        return svc;
    }

    private static CpeSession session(String sn, boolean online) {
        CpeSession session = Mockito.mock(CpeSession.class);
        when(session.getSn()).thenReturn(sn);
        when(session.isOnline()).thenReturn(online);
        return session;
    }

    @Test
    void readsLanStatusForOnlineDevice() {
        CpeSession online = session("SN1", true);
        when(sessionManager.allSessions()).thenReturn(List.of(online));
        when(businessService.lanStatusPaths("SN1"))
                .thenReturn(List.of("InternetGatewayDevice.LANDevice.1."
                        + "LANEthernetInterfaceConfig.1.Status"));
        when(capabilityService.readPathValues(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(Map.of()));

        service(true).refreshOnlineDevices();

        verify(capabilityService).readPathValues(eq("SN1"), any());
    }

    @Test
    void skipsOfflineDevice() {
        CpeSession offline = session("SN1", false);
        when(sessionManager.allSessions()).thenReturn(List.of(offline));

        service(true).refreshOnlineDevices();

        verify(capabilityService, never()).readPathValues(any(), any());
    }

    @Test
    void skipsDeviceWithoutLanParams() {
        CpeSession online = session("SN1", true);
        when(sessionManager.allSessions()).thenReturn(List.of(online));
        when(businessService.lanStatusPaths("SN1")).thenReturn(List.of());

        service(true).refreshOnlineDevices();

        verify(capabilityService, never()).readPathValues(any(), any());
    }

    @Test
    void doesNothingWhenDisabled() {
        CpeSession online = session("SN1", true);
        when(sessionManager.allSessions()).thenReturn(List.of(online));
        when(businessService.lanStatusPaths("SN1"))
                .thenReturn(List.of("InternetGatewayDevice.LANDevice.1."
                        + "LANEthernetInterfaceConfig.1.Status"));

        service(false).refreshOnlineDevices();

        verify(capabilityService, never()).readPathValues(any(), any());
    }
}

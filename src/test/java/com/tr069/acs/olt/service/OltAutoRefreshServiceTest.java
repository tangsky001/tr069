package com.tr069.acs.olt.service;

import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.mapper.OltDeviceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OLT 自动采集的时间行为：每台都采一遍；失败的往后退避，不算每轮都白等一个登录超时。
 */
@ExtendWith(MockitoExtension.class)
class OltAutoRefreshServiceTest {

    @Mock
    private OltDeviceMapper deviceMapper;

    @Mock
    private OltService oltService;

    private OltAutoRefreshService service;

    @BeforeEach
    void setUp() {
        service = new OltAutoRefreshService(deviceMapper, oltService);
    }

    @Test
    void shouldSyncEveryDeviceOnce() {
        when(deviceMapper.selectList(any())).thenReturn(devices(1L, 2L));

        service.refreshAll();

        verify(oltService).sync(1L, "自动刷新");
        verify(oltService).sync(2L, "自动刷新");
    }

    @Test
    void shouldBackOffFailedDeviceInNextRound() {
        when(deviceMapper.selectList(any())).thenReturn(devices(1L, 2L));
        when(oltService.sync(eq(1L), any())).thenThrow(new IllegalStateException("连接失败：超时"));

        service.refreshAll();
        service.refreshAll();

        verify(oltService, times(1)).sync(eq(1L), any());
        verify(oltService, times(2)).sync(eq(2L), any());
    }

    @Test
    void shouldResumeAfterBackoffElapsed() {
        when(deviceMapper.selectList(any())).thenReturn(devices(1L));
        when(oltService.sync(eq(1L), any()))
                .thenThrow(new IllegalStateException("连接失败：超时"))
                .thenReturn(null);

        service.refreshAll();
        service.refreshAll();
        verify(oltService, times(1)).sync(eq(1L), any());

        // 退避到点：这里直接清掉记录模拟时间过去，采通之后退避清零，下轮照常参与
        ReflectionTestUtils.setField(service, "retryAt", new ConcurrentHashMap<Long, Long>());
        service.refreshAll();
        verify(oltService, times(2)).sync(eq(1L), any());
        service.refreshAll();
        verify(oltService, times(3)).sync(eq(1L), any());
    }

    @Test
    void shouldKeepOtherDevicesRunningWhenOneFails() {
        when(deviceMapper.selectList(any())).thenReturn(devices(1L, 2L));
        when(oltService.sync(eq(1L), any())).thenThrow(new IllegalStateException("登录失败（账号 root）"));

        assertDoesNotThrow(() -> service.refreshAll());

        verify(oltService).sync(eq(2L), any());
    }

    @Test
    void shouldStopWhenRoundExceedsLimit() {
        when(deviceMapper.selectList(any())).thenReturn(devices(1L, 2L));
        // 负数表示截止时间已过：本轮应立刻停止，一台都不采
        ReflectionTestUtils.setField(service, "maxRoundMillis", -1L);

        service.refreshAll();

        verify(oltService, never()).sync(any(Long.class), any());
    }

    private static List<OltDevice> devices(Long... ids) {
        List<OltDevice> list = new ArrayList<>();
        for (Long id : ids) {
            OltDevice device = new OltDevice();
            device.setId(id);
            list.add(device);
        }
        return list;
    }
}

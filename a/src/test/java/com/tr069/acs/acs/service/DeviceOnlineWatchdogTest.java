package com.tr069.acs.acs.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeviceOnlineWatchdogTest {

    @Test
    void keepsWindowWhenIntervalUnknown() {
        assertEquals(900L, DeviceOnlineWatchdog.effectiveThreshold(900, null));
        assertEquals(900L, DeviceOnlineWatchdog.effectiveThreshold(900, 0L));
        assertEquals(900L, DeviceOnlineWatchdog.effectiveThreshold(900, -1L));
    }

    @Test
    void widensForSlowReportingDevice() {
        assertEquals(10800L, DeviceOnlineWatchdog.effectiveThreshold(900, 3600L));
    }

    @Test
    void neverTightensBelowWindow() {
        assertEquals(900L, DeviceOnlineWatchdog.effectiveThreshold(900, 300L));
    }
}

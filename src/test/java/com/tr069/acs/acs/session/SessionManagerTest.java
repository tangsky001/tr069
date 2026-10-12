package com.tr069.acs.acs.session;

import com.tr069.acs.acs.service.DeviceRegistryService;
import com.tr069.acs.cwmp.model.SoapMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话生命周期：周期重连不能被误判成重复会话，旧连接要被清理干净。
 */
class SessionManagerTest {

    private static final String SN = "CIOT29AC19D1";

    private static final String OUI = "1C25E1";

    private SessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new SessionManager(Mockito.mock(DeviceRegistryService.class));
        ReflectionTestUtils.setField(manager, "duplicateGraceSeconds", 30L);
    }

    private static void backdate(CpeSession session, long seconds) {
        ReflectionTestUtils.setField(session, "lastContactTime",
                System.currentTimeMillis() - seconds * 1000L);
    }

    /**
     * 现场最常见的场景：设备每 60 秒周期上报，每次都是新的 TCP 连接（源端口不同）。
     * 这不构成重复会话，否则设备每分钟被判一次离线。
     */
    @Test
    void periodicReconnectIsNotDuplicateSession() {
        CpeSession session = manager.getOrCreate(OUI, SN, "10.0.0.1:40000");
        backdate(session, 60);

        assertThat(manager.detectDuplicateSession(SN, "10.0.0.1:41234")).isFalse();
    }

    /**
     * 旧会话几秒前还在通信，说明设备上确实同时存在两条连接，这才需要关掉旧的。
     */
    @Test
    void concurrentConnectionIsDuplicateSession() {
        CpeSession session = manager.getOrCreate(OUI, SN, "10.0.0.1:40000");
        backdate(session, 2);

        assertThat(manager.detectDuplicateSession(SN, "10.0.0.1:41234")).isTrue();
    }

    @Test
    void sameConnectionIsNeverDuplicate() {
        manager.getOrCreate(OUI, SN, "10.0.0.1:40000");

        assertThat(manager.detectDuplicateSession(SN, "10.0.0.1:40000")).isFalse();
    }

    /**
     * 设备换连接后旧连接映射必须清掉：不清的话设备每上报一次就漏一条，
     * 长跑会把 connectionToSn 撑爆。
     */
    @Test
    void reconnectReleasesStaleConnectionMapping() {
        manager.getOrCreate(OUI, SN, "10.0.0.1:40000");
        manager.getOrCreate(OUI, SN, "10.0.0.1:41234");

        assertThat(manager.findSnByConnection("10.0.0.1:40000")).isNull();
        assertThat(manager.findSnByConnection("10.0.0.1:41234")).isEqualTo(SN);
    }

    /**
     * 旧连接上还有没等到回应的指令，设备已经从新连接来了，那条指令不会再有回应，
     * 必须立即作废，否则调用方要一直等到 510 秒的兜底超时。
     */
    @Test
    void reconnectFailsAwaitingRpcOnOldConnection() {
        CpeSession session = manager.getOrCreate(OUI, SN, "10.0.0.1:40000");
        PendingRpc rpc = session.enqueue(9527L, "GetParameterValues", Map.of(), null);
        session.markAwaiting(rpc);

        manager.getOrCreate(OUI, SN, "10.0.0.1:41234");

        SoapMessage response = rpc.getResponse().getNow(null);
        assertThat(response).isNotNull();
        assertThat(response.isFault()).isTrue();
        assertThat(session.getAwaiting()).isNull();
    }

    @Test
    void connectionLostClearsConnectionId() {
        CpeSession session = manager.getOrCreate(OUI, SN, "10.0.0.1:40000");

        manager.onConnectionLost("10.0.0.1:40000");

        // 连接标识清空后，设备从新连接上报不会被误判成重复会话
        assertThat(session.getConnectionId()).isNull();
        assertThat(manager.detectDuplicateSession(SN, "10.0.0.1:41234")).isFalse();
    }
}

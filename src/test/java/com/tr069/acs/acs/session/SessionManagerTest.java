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
     * 旧会话几秒前还在通信，说明设备可能同时存在两条连接——记为疑似双会话。
     * 注意这只是告警信号，不再据此关闭会话（见 reconnectTakesOverSessionWithoutRebuilding）。
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
     * 旧假设是"设备换了新连接，旧连接上那条指令不会再有回应，必须立即作废"——
     * 被现网日志证伪：17:21:27 在连接 A 下发 GetParameterValues，17:21:31 设备就
     * 从新端口回连了。CPE 关掉旧连接、另开一条把回应发回来是常见实现，
     * 立刻作废会让适配探测当场失败；这里保留等待，兜底由 RPC 响应定时器（60 秒）负责。
     */
    @Test
    void reconnectKeepsAwaitingRpcForNewConnection() {
        CpeSession session = manager.getOrCreate(OUI, SN, "10.0.0.1:40000");
        PendingRpc rpc = session.enqueue(9527L, "GetParameterValues", Map.of(), null);
        session.markAwaiting(rpc);

        manager.getOrCreate(OUI, SN, "10.0.0.1:41234");

        assertThat(rpc.getResponse().isDone()).isFalse();
        assertThat(session.getAwaiting()).isSameAs(rpc);
    }

    /**
     * 会话上还有指令在等回应时，设备换连接属于"回应回来了"，不是双会话：
     * 判成重复会关掉会话、把设备打离线，等待中的探测指令也被作废。
     */
    @Test
    void reconnectWithAwaitingRpcIsNotDuplicateSession() {
        CpeSession session = manager.getOrCreate(OUI, SN, "10.0.0.1:40000");
        PendingRpc rpc = session.enqueue(9527L, "GetParameterValues", Map.of(), null);
        session.markAwaiting(rpc);
        backdate(session, 2);

        assertThat(manager.detectDuplicateSession(SN, "10.0.0.1:41234")).isFalse();
    }

    @Test
    void connectionLostClearsConnectionId() {
        CpeSession session = manager.getOrCreate(OUI, SN, "10.0.0.1:40000");

        manager.onConnectionLost("10.0.0.1:40000");

        // 连接标识清空后，设备从新连接上报不会被误判成重复会话
        assertThat(session.getConnectionId()).isNull();
        assertThat(manager.detectDuplicateSession(SN, "10.0.0.1:41234")).isFalse();
    }

    /**
     * 周期上报换连接是"会话接管"，不是重建会话：会话对象、在线状态、待下发队列
     * 都要原样保留。之前判成重复会 closeSession——打离线、清空待下发队列、
     * 作废等待中的指令，适配测试做到一半设备"掉线"就是这么来的。
     */
    @Test
    void reconnectTakesOverSessionWithoutRebuilding() {
        CpeSession session = manager.getOrCreate(OUI, SN, "10.0.0.1:40000");
        session.enqueue(9528L, "GetParameterNames", Map.of(), null);
        backdate(session, 2);

        // 疑似双会话只是告警信号，不应带任何破坏动作
        assertThat(manager.detectDuplicateSession(SN, "10.0.0.1:41234")).isTrue();

        CpeSession taken = manager.getOrCreate(OUI, SN, "10.0.0.1:41234");

        assertThat(taken).isSameAs(session);
        assertThat(manager.find(SN)).isSameAs(session);
        assertThat(session.isOnline()).isTrue();
        assertThat(session.getOutbox()).hasSize(1);
        assertThat(session.getConnectionId()).isEqualTo("10.0.0.1:41234");
    }

    /**
     * 连接断开只清连接标识：周期上报之间这条 TCP 连接本来就会断，设备常常另开一条
     * 把指令回应发回来。等待中的指令必须留着，否则"下发 → 断开 → 新连接回响应"
     * 这条正常链路上的指令永远拿不到回应。
     */
    @Test
    void connectionLostKeepsAwaitingRpc() {
        CpeSession session = manager.getOrCreate(OUI, SN, "10.0.0.1:40000");
        PendingRpc rpc = session.enqueue(9527L, "GetParameterValues", Map.of(), null);
        session.markAwaiting(rpc);

        manager.onConnectionLost("10.0.0.1:40000");

        assertThat(session.getConnectionId()).isNull();
        assertThat(rpc.getResponse().isDone()).isFalse();
        assertThat(session.getAwaiting()).isSameAs(rpc);
    }

    /**
     * 只标离线不回收的话 snToSession 只增不减，长跑会越攒越多：
     * 超过在线判定周期先标离线，再静默足够久才回收会话对象。
     */
    @Test
    void sweepReclaimsSessionAfterLongSilence() {
        ReflectionTestUtils.setField(manager, "onlineCheckInterval", 120L);
        CpeSession session = manager.getOrCreate(OUI, SN, "10.0.0.1:40000");

        backdate(session, 130);
        manager.sweepExpiredSessions();

        assertThat(session.isOnline()).isFalse();
        assertThat(manager.find(SN)).isNotNull();

        backdate(session, 1300);
        manager.sweepExpiredSessions();

        assertThat(manager.find(SN)).isNull();
    }
}

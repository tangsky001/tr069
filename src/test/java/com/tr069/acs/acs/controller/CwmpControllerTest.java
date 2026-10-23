package com.tr069.acs.acs.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.acs.service.DeviceCapabilityService;
import com.tr069.acs.acs.service.InformProvisionService;
import com.tr069.acs.acs.service.InformService;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.CwmpSessionMutex;
import com.tr069.acs.acs.session.PendingRpc;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.queue.ReportQueueService;
import com.tr069.acs.queue.RpcDeliveryWaiter;
import com.tr069.acs.queue.RpcTaskQueue;
import com.tr069.acs.queue.config.QueueProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下发链路的回归保护：指令靠 HTTP 响应捎带给设备，空 POST 和「设备回响应」两个入口都要能捎带。
 */
class CwmpControllerTest {

    /**
     * 设备回完一条 RPC 响应时，同一个 HTTP 响应里必须直接带上下一条指令。
     *
     * <p>华为电信这类固件收到 204 就认为会话结束，不会再发空 POST。不捎带的话一条指令
     * 就要等一个上报周期，采一棵树要几十分钟，设备还常常中途掉线。</p>
     */
    @Test
    void 设备回响应时同一个响应里捎带下一条指令() throws Exception {
        String sn = "37300148C4AA410D6";
        String connId = "10.172.10.4:44885";

        PendingRpc done = Mockito.mock(PendingRpc.class);
        Mockito.when(done.getRpcName()).thenReturn("GetParameterNames");
        Mockito.when(done.getRpcId()).thenReturn(308L);

        CpeSession session = Mockito.mock(CpeSession.class);
        Mockito.when(session.completeAwaiting(ArgumentMatchers.any())).thenReturn(done);
        // 下发要在会话锁内串行，mock 会话得给一把真锁，否则 tryLock 会 NPE
        Mockito.when(session.getDeliveryLock()).thenReturn(new ReentrantLock());

        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        Mockito.when(sessionManager.findSnByConnection(connId)).thenReturn(sn);
        Mockito.when(sessionManager.find(sn)).thenReturn(session);

        RpcTaskQueue.RpcTask next = new RpcTaskQueue.RpcTask();
        next.setTaskId(309L);
        next.setRpcName("GetParameterValues");
        next.setArgsJson("{\"ParameterNames\":[\"InternetGatewayDevice.DeviceInfo.ProductClass\"]}");

        RpcTaskQueue rpcTaskQueue = Mockito.mock(RpcTaskQueue.class);
        Mockito.when(rpcTaskQueue.poll(sn)).thenReturn(next);
        Mockito.when(rpcTaskQueue.parseArgs(ArgumentMatchers.anyString()))
                .thenReturn(Map.of("ParameterNames",
                        java.util.List.of("InternetGatewayDevice.DeviceInfo.ProductClass")));

        QueueProperties queueProperties = Mockito.mock(QueueProperties.class);
        Mockito.when(queueProperties.isEnabled()).thenReturn(true);

        CwmpController controller = new CwmpController(
                sessionManager, null, Mockito.mock(InformService.class),
                Mockito.mock(ReportQueueService.class), rpcTaskQueue,
                Mockito.mock(RpcDispatchService.class),
                Mockito.mock(DeviceCapabilityService.class),
                Mockito.mock(InformProvisionService.class), queueProperties,
                new ObjectMapper(), Mockito.mock(RpcDeliveryWaiter.class),
                sharedMutex());

        MockHttpServletResponse response = new MockHttpServletResponse();
        ReflectionTestUtils.invokeMethod(controller, "handleSoap",
                connId, gpnResponseXml(), null, response);

        assertThat(response.getStatus()).as("不是 204，说明指令确实捎带在响应里了").isEqualTo(200);
        assertThat(response.getContentAsString()).contains("<cwmp:GetParameterValues>");
        Mockito.verify(session).markAwaiting(ArgumentMatchers.any());
    }

    /**
     * 队列里没指令时仍然是 204：会话正常收尾，设备照常结束这一轮。
     */
    @Test
    void 没有待下发指令时回复204() throws Exception {
        String sn = "37300148C4AA410D6";
        String connId = "10.172.10.4:44885";

        PendingRpc done = Mockito.mock(PendingRpc.class);
        Mockito.when(done.getRpcName()).thenReturn("GetParameterNames");

        CpeSession session = Mockito.mock(CpeSession.class);
        Mockito.when(session.completeAwaiting(ArgumentMatchers.any())).thenReturn(done);
        Mockito.when(session.getDeliveryLock()).thenReturn(new ReentrantLock());

        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        Mockito.when(sessionManager.findSnByConnection(connId)).thenReturn(sn);
        Mockito.when(sessionManager.find(sn)).thenReturn(session);

        RpcTaskQueue rpcTaskQueue = Mockito.mock(RpcTaskQueue.class);
        Mockito.when(rpcTaskQueue.poll(sn)).thenReturn(null);

        QueueProperties queueProperties = Mockito.mock(QueueProperties.class);
        Mockito.when(queueProperties.isEnabled()).thenReturn(true);

        CwmpController controller = new CwmpController(
                sessionManager, null, Mockito.mock(InformService.class),
                Mockito.mock(ReportQueueService.class), rpcTaskQueue,
                Mockito.mock(RpcDispatchService.class),
                Mockito.mock(DeviceCapabilityService.class),
                Mockito.mock(InformProvisionService.class), queueProperties,
                new ObjectMapper(), Mockito.mock(RpcDeliveryWaiter.class),
                sharedMutex());

        MockHttpServletResponse response = new MockHttpServletResponse();
        ReflectionTestUtils.invokeMethod(controller, "handleSoap",
                connId, gpnResponseXml(), null, response);

        assertThat(response.getStatus()).isEqualTo(204);
    }

    /**
     * 设备换了 TCP 连接还能不能再认出来。
     *
     * <p>会话中途换源端口是常态：NAT 超时重开连接、或者同一轮里空 POST 和 RPC 响应
     * 各走一条。以前只按 ip:端口 认设备，换端口就 400，本轮下发当场断掉——
     * 表现就是「下发时灵时不灵、采集中途卡住」。现在靠会话号 Cookie 续接。</p>
     */
    @Test
    void 设备换连接后仍凭会话Cookie认出() throws Exception {
        String sn = "37300148C4AA410D6";
        String informConn = "10.172.10.4:44885";
        String emptyPostConn = "10.172.10.4:51902";

        SessionManager sessionManager = new SessionManager();
        CpeSession session = sessionManager.getOrCreate("1C25E1", sn, informConn);
        assertThat(session.getSessionId()).as("Inform 时要发会话号给设备").isNotBlank();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Cookie", "session=" + session.getSessionId());

        MockHttpServletResponse response = new MockHttpServletResponse();
        ReflectionTestUtils.invokeMethod(controllerFor(sessionManager, false),
                "handleEmptyPost", emptyPostConn, request, response);

        assertThat(response.getStatus()).as("换端口后仍要认得出设备，不能是 400").isEqualTo(204);
        assertThat(sessionManager.findSnByConnection(emptyPostConn))
                .as("新连接要把会话接过去，后续请求才有依托").isEqualTo(sn);
    }

    /** 没有 Cookie 也没有已知连接时，定位不到就是 400。 */
    @Test
    void 既无Cookie也无已知连接时返回400() throws Exception {
        SessionManager sessionManager = new SessionManager();

        MockHttpServletResponse response = new MockHttpServletResponse();
        ReflectionTestUtils.invokeMethod(controllerFor(sessionManager, false),
                "handleEmptyPost", "10.172.10.4:60001", new MockHttpServletRequest(), response);

        assertThat(response.getStatus()).isEqualTo(400);
    }

    private CwmpController controllerFor(SessionManager sessionManager, boolean queueEnabled) {
        RpcTaskQueue rpcTaskQueue = Mockito.mock(RpcTaskQueue.class);
        Mockito.when(rpcTaskQueue.poll(ArgumentMatchers.anyString())).thenReturn(null);
        return controllerFor(sessionManager, rpcTaskQueue, queueEnabled, sharedMutex());
    }

    private CwmpController controllerFor(SessionManager sessionManager, RpcTaskQueue rpcTaskQueue,
                                         boolean queueEnabled, CwmpSessionMutex mutex) {
        QueueProperties queueProperties = Mockito.mock(QueueProperties.class);
        Mockito.when(queueProperties.isEnabled()).thenReturn(queueEnabled);

        CwmpController controller = new CwmpController(
                sessionManager, null, Mockito.mock(InformService.class),
                Mockito.mock(ReportQueueService.class), rpcTaskQueue,
                Mockito.mock(RpcDispatchService.class),
                Mockito.mock(DeviceCapabilityService.class),
                Mockito.mock(InformProvisionService.class), queueProperties,
                new ObjectMapper(), Mockito.mock(RpcDeliveryWaiter.class), mutex);
        // 关掉挂起等待：队列空时直接回 204，测试不必真的等一轮 piggyback
        ReflectionTestUtils.setField(controller, "piggybackWaitMs", 0L);
        return controller;
    }

    /**
     * 会话锁被别的实例握着时要让位。
     *
     * <p>两个 ACS 同时对一台设备编排，双方都在等回应而设备只有一条通路——
     * 结果是成倍的超时和互相插队。宁可这次不接，让设备下个周期再来。</p>
     */
    @Test
    void 会话锁被别的实例握着时本次Inform落空() {
        String sn = "37300148C4AA410D6";
        SessionManager sessionManager = new SessionManager();
        CpeSession session = sessionManager.getOrCreate("1C25E1", sn, "10.0.0.1:40001");

        CwmpSessionMutex mutex = Mockito.mock(CwmpSessionMutex.class);
        Mockito.when(mutex.acquire(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(false);
        CwmpController controller = controllerFor(
                sessionManager, Mockito.mock(RpcTaskQueue.class), true, mutex);

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean granted = Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(controller,
                "acquireSession", sn, session.getSessionId(), null, response));

        assertThat(granted).as("握不到会话就该让位，设备下个周期会再来一步").isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    /**
     * 还在退避期的指令本跳不发，放回队首等技术发：第一次等 60 秒、第二次 120 秒。
     */
    @Test
    void 指令还在退避期时本跳不下发() throws Exception {
        String sn = "37300148C4AA410D6";
        SessionManager sessionManager = new SessionManager();
        CpeSession session = sessionManager.getOrCreate("1C25E1", sn, "10.0.0.1:40002");

        RpcTaskQueue.RpcTask task = new RpcTaskQueue.RpcTask();
        task.setTaskId(88L);
        task.setSn(sn);
        task.setRpcName("GetParameterNames");
        task.setArgsJson("{}");
        task.setNotBefore(System.currentTimeMillis() + 60_000);

        RpcTaskQueue queue = Mockito.mock(RpcTaskQueue.class);
        Mockito.when(queue.poll(sn)).thenReturn(task);
        CwmpController controller = controllerFor(sessionManager, queue, true, sharedMutex());

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean delivered = Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                controller, "deliverNextRpc", sn, session, response));

        assertThat(delivered).as("退避期没到，本跳不能把指令塞给设备").isFalse();
        Mockito.verify(queue).pushBack(task);
        assertThat(response.getContentAsString()).as("本跳不能写指令给设备").isEmpty();
    }

    /** 会话锁一律放行：这组用例测的是下发链路，互斥另有用例覆盖。 */
    private static CwmpSessionMutex sharedMutex() {
        CwmpSessionMutex mutex = Mockito.mock(CwmpSessionMutex.class);
        Mockito.when(mutex.acquire(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(true);
        return mutex;
    }

    /** 一条最简单的 GetParameterNamesResponse，够走到「处理响应」这一步即可。 */
    private static String gpnResponseXml() {
        return "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "xmlns:SOAP-ENC=\"http://schemas.xmlsoap.org/soap/encoding/\" "
                + "xmlns:cwmp=\"urn:dslforum-org:cwmp-1-0\">"
                + "<SOAP-ENV:Header><cwmp:ID SOAP-ENV:mustUnderstand=\"1\">1</cwmp:ID></SOAP-ENV:Header>"
                + "<SOAP-ENV:Body><cwmp:GetParameterNamesResponse>"
                + "<ParameterList SOAP-ENC:arrayType=\"cwmp:ParameterInfoStruct[1]\">"
                + "<ParameterInfoStruct><Name>InternetGatewayDevice.WANDevice.1.</Name>"
                + "<Writable>1</Writable></ParameterInfoStruct>"
                + "</ParameterList></cwmp:GetParameterNamesResponse>"
                + "</SOAP-ENV:Body></SOAP-ENV:Envelope>";
    }
}

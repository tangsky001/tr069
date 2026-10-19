package com.tr069.acs.acs.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.acs.service.DeviceCapabilityService;
import com.tr069.acs.acs.service.InformService;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.PendingRpc;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.queue.ReportQueueService;
import com.tr069.acs.queue.RpcTaskQueue;
import com.tr069.acs.queue.config.QueueProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

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
                Mockito.mock(DeviceCapabilityService.class), queueProperties,
                new ObjectMapper());

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
                Mockito.mock(DeviceCapabilityService.class), queueProperties,
                new ObjectMapper());

        MockHttpServletResponse response = new MockHttpServletResponse();
        ReflectionTestUtils.invokeMethod(controller, "handleSoap",
                connId, gpnResponseXml(), null, response);

        assertThat(response.getStatus()).isEqualTo(204);
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

/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.h248;

import com.PilotACS.common.log.Logs;
import com.PilotACS.sip.SipProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** H.248 UDP 监听（文本编码，端口 2944）：收包分发 + 对外发送 */
@Component
public class H248Server {

    /** 惰性注入，避免与 H248Controller 构造环（Spring Boot 3 默认禁循环依赖） */
    @Autowired
    private ObjectProvider<H248Controller> controllerProvider;

    private final SipProperties props;
    private volatile DatagramSocket socket;
    private ExecutorService workers;
    private volatile boolean running;

    public H248Server(SipProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void start() {
        if (!props.isEnabled() || !props.isH248Enabled()) {
            return;
        }
        try {
            socket = new DatagramSocket(new InetSocketAddress(props.getH248Port()));
        } catch (SocketException e) {
            Logs.error(Logs.Module.SIP, "H.248 端口监听失败", "端口", props.getH248Port(), "错误", e.toString());
            return;
        }
        workers = Executors.newFixedThreadPool(4);
        running = true;
        Thread recv = new Thread(this::receiveLoop, "h248-udp-recv");
        recv.setDaemon(true);
        recv.start();
        Logs.info(Logs.Module.SIP, "H.248 MGC 已启动", "端口", props.getH248Port());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[65535];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.receive(pkt);
                String raw = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                InetSocketAddress remote = new InetSocketAddress(pkt.getAddress(), pkt.getPort());
                String rawCopy = raw;
                workers.execute(() -> {
                    H248Controller controller = controllerProvider.getIfAvailable();
                    if (controller == null) {
                        return;
                    }
                    try {
                        controller.handle(rawCopy, remote);
                    } catch (Exception e) {
                        Logs.error(Logs.Module.SIP, "H.248 报文处理异常", "远端", remote, "错误", e.toString());
                    }
                });
            } catch (SocketException e) {
                break;
            } catch (Exception e) {
                Logs.error(Logs.Module.SIP, "H.248 收包异常", "错误", e.toString());
            }
        }
    }

    public void send(String raw, InetSocketAddress target) {
        if (socket == null || socket.isClosed() || target == null) {
            return;
        }
        try {
            byte[] data = raw.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, target.getAddress(), target.getPort()));
        } catch (Exception e) {
            Logs.debug(Logs.Module.SIP, "H.248 发送失败", "目标", target, "错误", e.toString());
        }
    }

    public int port() {
        return props.getH248Port();
    }
}

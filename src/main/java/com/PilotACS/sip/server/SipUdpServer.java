/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.server;

import com.PilotACS.common.log.Logs;
import com.PilotACS.sip.SipProperties;
import com.PilotACS.sip.core.SipMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SIP UDP 监听：5060 收包分发，并提供对外发送与公网地址解析 */
@Component
public class SipUdpServer {

    /** 惰性注入，避免与 SipController 构造环（Spring Boot 3 默认禁循环依赖） */
    @Autowired
    private ObjectProvider<SipController> controllerProvider;

    private final SipProperties props;
    private volatile DatagramSocket socket;
    private ExecutorService workers;
    private volatile boolean running;
    private volatile String publicIp = "127.0.0.1";

    public SipUdpServer(SipProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void start() {
        if (!props.isEnabled()) {
            Logs.info(Logs.Module.SIP, "SIP 软交换未启用（acs.sip.enabled=false）");
            return;
        }
        publicIp = props.getPublicIp() == null || props.getPublicIp().isBlank()
                ? detectLocalIp() : props.getPublicIp();
        try {
            socket = new DatagramSocket(new InetSocketAddress(props.getUdpPort()));
        } catch (SocketException e) {
            Logs.error(Logs.Module.SIP, "SIP 端口监听失败", "端口", props.getUdpPort(), "错误", e.toString());
            return;
        }
        workers = Executors.newFixedThreadPool(8);
        running = true;
        Thread recv = new Thread(this::receiveLoop, "sip-udp-recv");
        recv.setDaemon(true);
        recv.start();
        Logs.info(Logs.Module.SIP, "SIP 软交换已启动", "端口", props.getUdpPort(), "地址", publicIp);
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
                    SipMessage msg = SipMessage.parse(rawCopy);
                    SipController controller = controllerProvider.getIfAvailable();
                    if (msg != null && controller != null) {
                        try {
                            controller.handle(msg, remote);
                        } catch (Exception e) {
                            Logs.error(Logs.Module.SIP, "SIP 报文处理异常", "远端", remote, "错误", e.toString());
                        }
                    }
                });
            } catch (SocketException e) {
                break;
            } catch (Exception e) {
                Logs.error(Logs.Module.SIP, "SIP 收包异常", "错误", e.toString());
            }
        }
    }

    /** 发送原始 SIP 文本 */
    public void send(String raw, InetSocketAddress target) {
        if (socket == null || socket.isClosed() || target == null) {
            return;
        }
        try {
            byte[] data = raw.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, target.getAddress(), target.getPort()));
        } catch (Exception e) {
            Logs.debug(Logs.Module.SIP, "SIP 发送失败", "目标", target, "错误", e.toString());
        }
    }

    public void send(SipMessage msg, InetSocketAddress target) {
        send(msg.serialize(), target);
    }

    /** 对外公布的 IP（RTP/SDP/Contact 用） */
    public String publicIp() {
        return publicIp;
    }

    public int port() {
        return props.getUdpPort();
    }

    /** 取本机非回环 IPv4，取不到回落 127.0.0.1 */
    private String detectLocalIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            // 回落默认
        }
        return "127.0.0.1";
    }
}

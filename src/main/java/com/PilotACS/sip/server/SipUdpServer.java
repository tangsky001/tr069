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
    /** public-ip 是否为留空自动探测；显式配置时一切以配置为准，不做任何按对端改写 */
    private volatile boolean autoIp;

    public SipUdpServer(SipProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void start() {
        if (!props.isEnabled()) {
            Logs.info(Logs.Module.SIP, "SIP 软交换未启用（acs.sip.enabled=false）");
            return;
        }
        autoIp = props.getPublicIp() == null || props.getPublicIp().isBlank();
        publicIp = autoIp ? detectLocalIp() : props.getPublicIp().trim();
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
        Logs.info(Logs.Module.SIP, "SIP 软交换已启动", "端口", props.getUdpPort(), "地址", publicIp,
                "来源", autoIp ? "自动探测" : "配置 public-ip");
        if (autoIp) {
            Logs.info(Logs.Module.SIP, "信令地址自适应", "说明",
                    "public-ip 留空：发出报文的 Contact/Via/From/To/Request-URI/SDP 按对端网段选择本机 IP",
                    "本机网卡", listLocalIps());
        }
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
        if (msg != null && target != null && autoIp) {
            // 仅留空自动探测时才按对端网段重写：Contact/Via/From/To/Request-URI/SDP 一次覆盖。
            // 显式配置 public-ip 的以配置为准，不做改写。
            String localIp = mediaIpFor(target.getAddress());
            if (!localIp.equals(publicIp)) {
                msg.replaceText(publicIp, localIp);
            }
        }
        send(msg.serialize(), target);
    }

    /** 对外公布的 IP（RTP/SDP/Contact 用） */
    public String publicIp() {
        return publicIp;
    }

    /**
     * 按对端信令源地址自适应选择 SDP/RTP 应申报的本机媒体地址。
     * 多网卡服务器的经典故障：detectLocalIp 抽中 192.168.10.100，而终端在 10.101.125.x
     * 网段——SDP 里申报的媒体地址终端根本路由不到，RTP 全丢，30 秒 RTP 空闲拆线
     * （症状：能注册、能呼叫、能接通，接通后约半分钟无声音自动挂断）。
     * 规则：优先与对端同 /24 的本机地址，其次同 /16，都无则退回 publicIp。
     */
    public String mediaIpFor(InetAddress peer) {
        // 显式配置 public-ip：一切以配置为准，不做按对端改写
        if (!autoIp || peer == null || peer.getAddress().length != 4) {
            return publicIp;
        }
        String best = null;
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
                    if (addr.isLoopbackAddress() || addr.getAddress().length != 4) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (samePrefix(ip, peer.getHostAddress(), 3)) {
                        return ip;                      // 同 /24：直接用
                    }
                    if (best == null && samePrefix(ip, peer.getHostAddress(), 2)) {
                        best = ip;                      // 同 /16：备选
                    }
                }
            }
        } catch (SocketException ignored) {
            // 回落默认
        }
        return best != null ? best : publicIp;
    }

    /** 列出全部非回环 IPv4，仅用于启动日志核对多网卡 */
    private String listLocalIps() {
        StringBuilder sb = new StringBuilder();
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
                        if (sb.length() > 0) {
                            sb.append(',');
                        }
                        sb.append(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            // 日志字段，失败不影响
        }
        return sb.length() == 0 ? publicIp : sb.toString();
    }

    /** IPv4 前 octets 个字节是否相同（3=/24，2=/16） */
    private static boolean samePrefix(String a, String b, int octets) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        if (pa.length != 4 || pb.length != 4) {
            return false;
        }
        for (int i = 0; i < octets; i++) {
            if (!pa[i].equals(pb[i])) {
                return false;
            }
        }
        return true;
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

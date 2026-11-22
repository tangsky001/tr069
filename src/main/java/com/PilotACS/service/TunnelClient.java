/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.690
 *
 */

package com.PilotACS.service;

import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 公网隧道客户端，实现外网云登录 */
@Service
@RequiredArgsConstructor
public class TunnelClient {

    private final OpsRemoteService opsRemoteService;


    /** 隧道服务器清单 */
    private static final List<Server> SERVERS = List.of(
            new Server("total.i-ddns.com", 7000, false, "主隧道服务器"),
            new Server("total.hiwyz.com", 7000, false, "腾讯云备用隧道服务器"),
            new Server("total.hiwyz.cn", 7000, false, "阿里云备用隧道服务器"),
            new Server("total.i-ddns.cn", 7000, false, "公共云备用隧道服务器")
    );

    /** 建连超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 5000;
    /** 心跳间隔（秒） */
    private static final int HEARTBEAT_INTERVAL = 30;
    /** 重连间隔（秒） */
    private static final int RECONNECT_INTERVAL = 10;

    /** 一台隧道服务器 */
    private record Server(String host, int port, boolean tls, String name) {
    }

    /** 本地服务端口 */
    @Value("${server.port:8888}")
    private int localPort;

    private static final byte TYPE_HANDSHAKE = 1;
    private static final byte TYPE_HANDSHAKE_RESP = 2;
    private static final byte TYPE_HEARTBEAT = 3;
    private static final byte TYPE_HTTP_REQUEST = 4;
    private static final byte TYPE_HTTP_RESPONSE = 5;
    private static final byte TYPE_DISCONNECT = 6;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Socket socket;
    private volatile DataInputStream in;
    private volatile DataOutputStream out;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** 复用的 HttpClient */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 当前已连接的隧道服务器 */
    private volatile Server currentServer;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running.set(true);
        executor.submit(this::connectLoop);
    }

    /** 停止隧道 */
    @PreDestroy
    public void stop() {
        running.set(false);
        closeQuietly();
        executor.shutdownNow();
        Logs.info(Logs.Module.TUNNEL, "客户端已停止");
    }

    private void connectLoop() {
        while (running.get()) {
            try {
                List<Probe> probes = probeServers();
                if (probes.isEmpty()) {
                    Logs.warn(Logs.Module.TUNNEL, "全部隧道服务器探测失败", "服务器数", SERVERS.size());
                }
                boolean connected = false;
                for (Probe p : probes) {
                    if (!running.get()) {
                        break;
                    }
                    try {
                        connect(p.server(), p.latencyMs());
                        connected = true;
                        break;
                    } catch (Exception e) {
                        Logs.warn(Logs.Module.TUNNEL, p.server().name() + "连接失败",
                                "地址", p.server().host() + ":" + p.server().port(), "原因", e.getMessage());
                    }
                }
                if (running.get()) {
                    Logs.info(Logs.Module.TUNNEL, connected ? "连接断开" : "本轮未能建立隧道",
                            "重连间隔(秒)", RECONNECT_INTERVAL);
                    sleep(RECONNECT_INTERVAL * 1000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** 一次探测结果 */
    private record Probe(Server server, long latencyMs) {
    }

    /** 并发探测各服务器延迟，返回按延迟升序的可达清单 */
    private List<Probe> probeServers() throws InterruptedException {
        List<Probe> probes = new java.util.ArrayList<>();
        @SuppressWarnings("unchecked")
        Probe[] results = new Probe[SERVERS.size()];
        Thread[] threads = new Thread[SERVERS.size()];
        for (int i = 0; i < SERVERS.size(); i++) {
            final int idx = i;
            Server s = SERVERS.get(i);
            threads[i] = new Thread(() -> results[idx] = probeOne(s));
            threads[i].setName("tunnel-probe-" + i);
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        List<String> failedNames = new java.util.ArrayList<>();
        for (int i = 0; i < results.length; i++) {
            if (results[i] != null) {
                probes.add(results[i]);
            } else {
                failedNames.add(SERVERS.get(i).name());
            }
        }
        probes.sort(java.util.Comparator.comparingLong(Probe::latencyMs));
        if (!probes.isEmpty()) {
            Probe best = probes.get(0);
            Logs.info(Logs.Module.TUNNEL, "隧道探测完成，选择延迟最低的 " + best.server().name());
            for (Probe p : probes) {
                Logs.info(Logs.Module.TUNNEL, "隧道探测明细", "服务器", p.server().name(),
                        "延迟", p.latencyMs() + "ms");
            }
            for (String name : failedNames) {
                Logs.info(Logs.Module.TUNNEL, "隧道探测明细", "服务器", name, "状态", "探测失败");
            }
        }
        return probes;
    }

    /** 单台探测，失败返回 null */
    private Probe probeOne(Server s) {
        long start = System.currentTimeMillis();
        try (Socket sock = new Socket()) {
            sock.connect(new java.net.InetSocketAddress(s.host(), s.port()), CONNECT_TIMEOUT_MS);
            return new Probe(s, System.currentTimeMillis() - start);
        } catch (Exception e) {
            Logs.warn(Logs.Module.TUNNEL, s.name() + "探测失败");
            return null;
        }
    }

    private void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    /** 当前隧道对应的外网域名 */
    private static String publicDomain(Server s, String subdomain) {
        return subdomain + "." + domainSuffix(s);
    }

    /** 域名后缀 */
    private static String domainSuffix(Server s) {
        int dot = s.host().indexOf('.');
        return dot > 0 ? s.host().substring(dot + 1) : s.host();
    }

    /** 当前隧道域名后缀 */
    public String currentDomainSuffix() {
        Server s = currentServer != null ? currentServer : SERVERS.get(0);
        return domainSuffix(s);
    }

    private void connect(Server s, long latencyMs) throws IOException {
        Logs.info(Logs.Module.TUNNEL, "正在连接{}，当前延迟[{}ms]", s.name(), latencyMs);

        Socket raw;
        if (s.tls()) {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            ssl.connect(new java.net.InetSocketAddress(s.host(), s.port()), CONNECT_TIMEOUT_MS);
            ssl.startHandshake();
            raw = ssl;
        } else {
            raw = new Socket();
            raw.connect(new java.net.InetSocketAddress(s.host(), s.port()), CONNECT_TIMEOUT_MS);
        }
        raw.setKeepAlive(true);
        raw.setSoTimeout(0);
        socket = raw;
        in = new DataInputStream(raw.getInputStream());
        out = new DataOutputStream(raw.getOutputStream());

        String instanceId = opsRemoteService.ensureInstanceId();
        String subdomain = opsRemoteService.ensureSubdomain();
        sendHandshake(instanceId, subdomain);

        byte respType = in.readByte();
        if (respType != TYPE_HANDSHAKE_RESP) {
            throw new IOException("握手响应帧类型错误: " + respType);
        }
        int code = in.readByte();
        int msgLen = in.readInt();
        byte[] msgBytes = new byte[msgLen];
        in.readFully(msgBytes);
        String msg = new String(msgBytes, java.nio.charset.StandardCharsets.UTF_8);

        if (code != 1) {
            Logs.warn(Logs.Module.TUNNEL, "握手失败", "原因", msg);
            if (msg.contains("conflict") || msg.contains("冲突")) {
                Logs.info(Logs.Module.TUNNEL, "子域名冲突，重新生成");
                opsRemoteService.regenerateSubdomain();
            }
            closeQuietly();
            throw new IOException("握手被拒绝: " + msg);
        }

        currentServer = s;
        Logs.info(Logs.Module.TUNNEL, "连接成功 {} 域名={}", s.name(), publicDomain(s, subdomain));
        executor.submit(() -> heartbeatLoop(raw));
        readLoop();
    }

    private void readLoop() throws IOException {
        while (running.get() && socket != null && !socket.isClosed()) {
            byte type = in.readByte();
            switch (type) {
                case TYPE_HTTP_REQUEST:
                    handleHttpRequest();
                    break;
                case TYPE_HEARTBEAT:
                    break;
                case TYPE_DISCONNECT:
                    Logs.info(Logs.Module.TUNNEL, "服务器通知断开");
                    closeQuietly();
                    return;
                default:
                    Logs.warn(Logs.Module.TUNNEL, "未知帧类型，忽略", "类型", type);
                    break;
            }
        }
    }

    private void handleHttpRequest() {
        try {
            String requestId = readString();
            int bodyLen = in.readInt();
            byte[] httpBytes = new byte[bodyLen];
            in.readFully(httpBytes);
            executor.submit(() -> forwardToLocal(requestId, httpBytes));
        } catch (IOException e) {
            Logs.warn(Logs.Module.TUNNEL, "读取请求帧失败", "原因", e.getMessage());
            closeQuietly();
        }
    }

    /** HTTP 请求转发到本地服务 */
    private void forwardToLocal(String requestId, byte[] httpBytes) {
        try {
            int headerEnd = indexOf(httpBytes, "\r\n\r\n");
            int headLen = headerEnd < 0 ? Math.min(httpBytes.length, 65536) : headerEnd;
            String head = new String(httpBytes, 0, headLen, java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = head.split("\r\n");
            String[] requestLine = lines[0].split(" ", 3);
            String method = requestLine[0];
            String path = requestLine.length > 1 ? requestLine[1] : "/";

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + localPort + path))
                    .timeout(Duration.ofSeconds(30));

            int bodyStart = headerEnd + 4;
            byte[] body = bodyStart > 0 && bodyStart < httpBytes.length
                    ? java.util.Arrays.copyOfRange(httpBytes, bodyStart, httpBytes.length)
                    : new byte[0];

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.isEmpty() || isHopByHopHeader(name)) {
                    continue;
                }
                try {
                    reqBuilder.header(name, value);
                } catch (IllegalArgumentException ignored) {
                }
            }

            if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
                reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                reqBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            }

            HttpResponse<byte[]> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());

            StringBuilder respHead = new StringBuilder();
            int statusCode = resp.statusCode();
            respHead.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText(statusCode)).append("\r\n");
            for (Map.Entry<String, java.util.List<String>> entry : resp.headers().map().entrySet()) {
                String headerName = entry.getKey();
                if (headerName == null) continue;
                if (headerName.equalsIgnoreCase("Content-Length")
                        || headerName.equalsIgnoreCase("Transfer-Encoding")) {
                    continue;
                }
                for (String v : entry.getValue()) {
                    respHead.append(headerName).append(": ").append(v).append("\r\n");
                }
            }
            respHead.append("Content-Length: ").append(resp.body().length).append("\r\n").append("\r\n");

            byte[] respHeadBytes = respHead.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] respBytes = new byte[respHeadBytes.length + resp.body().length];
            System.arraycopy(respHeadBytes, 0, respBytes, 0, respHeadBytes.length);
            System.arraycopy(resp.body(), 0, respBytes, respHeadBytes.length, resp.body().length);

            sendHttpResponse(requestId, respBytes);

        } catch (Exception e) {
            Logs.warn(Logs.Module.TUNNEL, "转发本地服务失败", "原因", e.getMessage());
            byte[] err = ("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            sendHttpResponse(requestId, err);
        }
    }

    /** 状态码对应文案 */
    private static String statusText(int code) {
        return switch (code / 100) {
            case 1 -> code == 100 ? "Continue" : code == 101 ? "Switching Protocols" : "OK";
            case 2 -> code == 200 ? "OK" : code == 201 ? "Created" : code == 202 ? "Accepted"
                    : code == 204 ? "No Content" : code == 206 ? "Partial Content" : "OK";
            case 3 -> code == 301 ? "Moved Permanently" : code == 302 ? "Found" : code == 304 ? "Not Modified"
                    : code == 307 ? "Temporary Redirect" : code == 308 ? "Permanent Redirect" : "Redirect";
            case 4 -> code == 401 ? "Unauthorized" : code == 403 ? "Forbidden" : code == 404 ? "Not Found"
                    : code == 405 ? "Method Not Allowed" : code == 408 ? "Request Timeout"
                    : code == 409 ? "Conflict" : code == 413 ? "Payload Too Large"
                    : code == 415 ? "Unsupported Media Type" : "Bad Request";
            case 5 -> code == 501 ? "Not Implemented" : code == 502 ? "Bad Gateway"
                    : code == 503 ? "Service Unavailable" : code == 504 ? "Gateway Timeout"
                    : "Internal Server Error";
            default -> "Unknown";
        };
    }

    private void heartbeatLoop(Socket own) {
        while (running.get() && own != null && !own.isClosed() && own == socket) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL * 1000L);
                synchronized (out) {
                    out.writeByte(TYPE_HEARTBEAT);
                    out.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                Logs.warn(Logs.Module.TUNNEL, "心跳发送失败", "原因", e.getMessage());
                closeQuietly();
                return;
            }
        }
    }

    private void sendHandshake(String instanceId, String subdomain) throws IOException {
        synchronized (out) {
            out.writeByte(TYPE_HANDSHAKE);
            writeString(instanceId);
            writeString(subdomain);
            out.flush();
        }
    }

    private void sendHttpResponse(String requestId, byte[] respBytes) {
        try {
            synchronized (out) {
                out.writeByte(TYPE_HTTP_RESPONSE);
                writeString(requestId);
                out.writeInt(respBytes.length);
                out.write(respBytes);
                out.flush();
            }
        } catch (IOException e) {
            Logs.warn(Logs.Module.TUNNEL, "响应发送失败", "原因", e.getMessage());
            closeQuietly();
        }
    }

    private void writeString(String s) throws IOException {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private String readString() throws IOException {
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 判断是否逐跳头 */
    private static boolean isHopByHopHeader(String name) {
        return name.equalsIgnoreCase("Host")
                || name.equalsIgnoreCase("Content-Length")
                || name.equalsIgnoreCase("Transfer-Encoding")
                || name.equalsIgnoreCase("Connection")
                || name.equalsIgnoreCase("Keep-Alive")
                || name.equalsIgnoreCase("Proxy-Connection")
                || name.equalsIgnoreCase("Upgrade")
                || name.equalsIgnoreCase("TE")
                || name.equalsIgnoreCase("Trailer")
                || name.equalsIgnoreCase("Expect");
    }

    private static int indexOf(byte[] data, String sep) {
        byte[] sepBytes = sep.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= data.length - sepBytes.length; i++) {
            for (int j = 0; j < sepBytes.length; j++) {
                if (data[i + j] != sepBytes[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private void closeQuietly() {
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
        }
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }
}

package com.tr069.acs.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 纯 Java 隧道客户端：ACS 启动后连公网服务器建立 Socket 长连接，自定义简单帧协议（type 1~6 见常量），参数全部写死 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TunnelClient {

    private final OpsRemoteService opsRemoteService;

    // ==================== 写死的参数 ====================

    /** 公网服务器地址（与统计上报同一台服务器） */
    private static final String SERVER_HOST = "total.i-ddns.com";

    /** 公网服务器隧道监听端口 */
    private static final int SERVER_PORT = 7000;

    /** 是否用 TLS（公网服务器有证书时用 true） */
    private static final boolean USE_TLS = false;

    /** 心跳间隔（秒） */
    private static final int HEARTBEAT_INTERVAL = 30;

    /** 重连间隔（秒） */
    private static final int RECONNECT_INTERVAL = 10;

    /** 本地 Tomcat 端口（跟随 server.port） */
    @Value("${server.port:8080}")
    private int localPort;

    // ==================== 帧类型 ====================

    private static final byte TYPE_HANDSHAKE = 1;
    private static final byte TYPE_HANDSHAKE_RESP = 2;
    private static final byte TYPE_HEARTBEAT = 3;
    private static final byte TYPE_HTTP_REQUEST = 4;
    private static final byte TYPE_HTTP_RESPONSE = 5;
    private static final byte TYPE_DISCONNECT = 6;

    // ==================== 运行状态 ====================

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Socket socket;
    private volatile DataInputStream in;
    private volatile DataOutputStream out;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** 复用单例 HttpClient，避免每个请求都 new */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (SERVER_HOST == null || SERVER_HOST.isBlank()) {
            log.info("隧道客户端：SERVER_HOST 为空，不启动内网穿透");
            return;
        }
        running.set(true);
        executor.submit(this::connectLoop);
    }

    /** 停止隧道 */
    @PreDestroy
    public void stop() {
        running.set(false);
        closeQuietly();
        executor.shutdownNow();
        log.info("隧道客户端已停止");
    }

    // ==================== 连接循环（含重连） ====================

    private void connectLoop() {
        while (running.get()) {
            try {
                connect();
                if (running.get()) {
                    log.info("隧道断开，{}秒后重连", RECONNECT_INTERVAL);
                    Thread.sleep(RECONNECT_INTERVAL * 1000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("隧道连接异常: {}", e.getMessage());
                if (running.get()) {
                    try {
                        Thread.sleep(RECONNECT_INTERVAL * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    // ==================== 建连 + 握手 ====================

    private void connect() throws IOException {
        log.info("正在连接隧道服务器...");

        Socket raw;
        if (USE_TLS) {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket(SERVER_HOST, SERVER_PORT);
            ssl.startHandshake();
            raw = ssl;
        } else {
            raw = new Socket(SERVER_HOST, SERVER_PORT);
        }
        raw.setKeepAlive(true);
        raw.setSoTimeout(0); // 不超时，长连接等着读

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
            log.warn("握手失败: {}", msg);
            if (msg.contains("conflict") || msg.contains("冲突")) {
                log.info("子域名冲突，重新生成...");
                opsRemoteService.regenerateSubdomain();
            }
            closeQuietly();
            return;
        }

        log.info("隧道连接成功: {} → {}.i-ddns.com", instanceId, subdomain);

        executor.submit(this::heartbeatLoop);

        readLoop();
    }

    // ==================== 读循环：处理服务器发来的帧 ====================

    private void readLoop() throws IOException {
        while (running.get() && socket != null && !socket.isClosed()) {
            byte type = in.readByte();
            switch (type) {
                case TYPE_HTTP_REQUEST:
                    handleHttpRequest();
                    break;
                case TYPE_HEARTBEAT:
                    // 服务器回的心跳，忽略
                    break;
                case TYPE_DISCONNECT:
                    log.info("服务器通知断开");
                    closeQuietly();
                    return;
                default:
                    log.warn("未知帧类型: {}，忽略", type);
                    break;
            }
        }
    }

    // ==================== HTTP 请求转发到本地 Tomcat ====================

    private void handleHttpRequest() {
        try {
            String requestId = readString();
            int bodyLen = in.readInt();
            byte[] httpBytes = new byte[bodyLen];
            in.readFully(httpBytes);

            // 异步处理，不阻塞读循环
            executor.submit(() -> forwardToLocal(requestId, httpBytes));
        } catch (IOException e) {
            log.warn("读取 HTTP 请求帧失败: {}", e.getMessage());
            closeQuietly();
        }
    }

    /** 把 HTTP 请求字节转发给本地 Tomcat，拿到响应再回传给服务器 */
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

            // 转发请求头：跳过 Host/Content-Length（HttpClient 自己管）和逐跳头（JDK HttpClient 视为受限头会抛异常）
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

            // 构造 HTTP 响应字节流——跳过 Content-Length（已有），补状态文案
            StringBuilder respHead = new StringBuilder();
            int statusCode = resp.statusCode();
            String statusText = statusText(statusCode);
            respHead.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");
            boolean hasContentLength = false;
            for (Map.Entry<String, java.util.List<String>> entry : resp.headers().map().entrySet()) {
                String headerName = entry.getKey();
                if (headerName == null) continue;
                // Content-Length 由我们自己算，跳过 HttpClient 给的（可能含 transfer-encoding 冲突）
                if (headerName.equalsIgnoreCase("Content-Length")
                        || headerName.equalsIgnoreCase("Transfer-Encoding")) {
                    continue;
                }
                for (String v : entry.getValue()) {
                    respHead.append(headerName).append(": ").append(v).append("\r\n");
                }
            }
            // 统一用 Content-Length，不用 chunked
            respHead.append("Content-Length: ").append(resp.body().length).append("\r\n");
            respHead.append("\r\n");

            byte[] respHeadBytes = respHead.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] respBytes = new byte[respHeadBytes.length + resp.body().length];
            System.arraycopy(respHeadBytes, 0, respBytes, 0, respHeadBytes.length);
            System.arraycopy(resp.body(), 0, respBytes, respHeadBytes.length, resp.body().length);

            sendHttpResponse(requestId, respBytes);

        } catch (Exception e) {
            log.warn("转发 HTTP 请求到本地失败: {}", e.getMessage());
            byte[] err = ("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            sendHttpResponse(requestId, err);
        }
    }

    /** HTTP 状态码 → 状态文案 */
    private static String statusText(int code) {
        return switch (code / 100) {
            case 1 -> switch (code) {
                case 100 -> "Continue";
                case 101 -> "Switching Protocols";
                case 102 -> "Processing";
                default -> "OK";
            };
            case 2 -> switch (code) {
                case 200 -> "OK";
                case 201 -> "Created";
                case 202 -> "Accepted";
                case 204 -> "No Content";
                case 206 -> "Partial Content";
                default -> "OK";
            };
            case 3 -> switch (code) {
                case 301 -> "Moved Permanently";
                case 302 -> "Found";
                case 304 -> "Not Modified";
                case 307 -> "Temporary Redirect";
                case 308 -> "Permanent Redirect";
                default -> "Redirect";
            };
            case 4 -> switch (code) {
                case 400 -> "Bad Request";
                case 401 -> "Unauthorized";
                case 403 -> "Forbidden";
                case 404 -> "Not Found";
                case 405 -> "Method Not Allowed";
                case 408 -> "Request Timeout";
                case 409 -> "Conflict";
                case 413 -> "Payload Too Large";
                case 415 -> "Unsupported Media Type";
                default -> "Bad Request";
            };
            case 5 -> switch (code) {
                case 500 -> "Internal Server Error";
                case 501 -> "Not Implemented";
                case 502 -> "Bad Gateway";
                case 503 -> "Service Unavailable";
                case 504 -> "Gateway Timeout";
                default -> "Internal Server Error";
            };
            default -> "Unknown";
        };
    }

    // ==================== 心跳循环 ====================

    private void heartbeatLoop() {
        while (running.get() && socket != null && !socket.isClosed()) {
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
                log.warn("心跳发送失败: {}", e.getMessage());
                closeQuietly();
                return;
            }
        }
    }

    // ==================== 帧发送 ====================

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
            log.warn("发送 HTTP 响应帧失败: {}", e.getMessage());
            closeQuietly();
        }
    }

    // ==================== 工具方法 ====================

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

    /** 逐跳头 + HttpClient 受限头：转发给本地 Tomcat 前必须丢掉，否则 build 时抛 IllegalArgumentException */
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
        } catch (IOException ignored) { }
        try {
            if (out != null) out.close();
        } catch (IOException ignored) { }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) { }
        socket = null;
        in = null;
        out = null;
    }
}

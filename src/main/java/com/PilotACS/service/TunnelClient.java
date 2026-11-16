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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

// 纯 Java 隧道客户端：启动后连公网服务器建立长连接（自定义帧协议），实现外网云登录
@Service
@RequiredArgsConstructor
public class TunnelClient {

    private final OpsRemoteService opsRemoteService;

    // ==================== 写死的参数 ====================

    // 公网服务器地址
    private static final String SERVER_HOST = "total.i-ddns.com";
    // 公网服务器隧道监听端口
    private static final int SERVER_PORT = 7000;
    // 是否用 TLS
    private static final boolean USE_TLS = false;
    // 心跳间隔（秒）
    private static final int HEARTBEAT_INTERVAL = 30;
    // 重连间隔（秒）
    private static final int RECONNECT_INTERVAL = 10;

    // 本地服务端口（跟随 server.port）
    @Value("${server.port:8888}")
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

    // 复用单例 HttpClient
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running.set(true);
        executor.submit(this::connectLoop);
    }

    // 停止隧道
    @PreDestroy
    public void stop() {
        running.set(false);
        closeQuietly();
        executor.shutdownNow();
        Logs.info(Logs.Module.TUNNEL, "客户端已停止");
    }

    // ==================== 连接循环（含重连） ====================

    private void connectLoop() {
        while (running.get()) {
            try {
                connect();
                if (running.get()) {
                    Logs.info(Logs.Module.TUNNEL, "连接断开", "重连间隔(秒)", RECONNECT_INTERVAL);
                    Thread.sleep(RECONNECT_INTERVAL * 1000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Logs.warn(Logs.Module.TUNNEL, "连接失败", "原因", e.getMessage());
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
        Logs.info(Logs.Module.TUNNEL, "正在连接服务器");

        Socket raw;
        if (USE_TLS) {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket(SERVER_HOST, SERVER_PORT);
            ssl.startHandshake();
            raw = ssl;
        } else {
            raw = new Socket(SERVER_HOST, SERVER_PORT);
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
            return;
        }

        Logs.info(Logs.Module.TUNNEL, "连接成功", "域名", subdomain + ".i-ddns.com");
        executor.submit(this::heartbeatLoop);
        readLoop();
    }

    // ==================== 读循环 ====================

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

    // ==================== HTTP 请求转发到本地服务 ====================

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

    // 把 HTTP 请求转发给本地服务，响应回传给隧道服务器
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

            // 跳过 Host/Content-Length 与逐跳头
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

    // HTTP 状态码 → 状态文案
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
                Logs.warn(Logs.Module.TUNNEL, "心跳发送失败", "原因", e.getMessage());
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
            Logs.warn(Logs.Module.TUNNEL, "响应发送失败", "原因", e.getMessage());
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

    // 逐跳头：转发前必须丢掉
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

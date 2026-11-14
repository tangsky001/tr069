package com.tr069.acs.olt.telnet;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收包回归测试：本地起一个假设备，把登录横幅分几包推过来，
 * 验证 ByteBuffer 缓冲路径（ByteBuf 直读）不会丢字节。
 *
 * <p>曾经的坑：直接用 {@code buf.readBytes(byteBuffer)} 会按 dst 整段剩余空间读，
 * 小包直接抛异常且被管道尾吞掉，业务侧只看到「等待设备回显超时，已收到：」。</p>
 */
class TelnetClientReceiveTest {

    private static final Pattern PROMPT = Pattern.compile("[>#]\\s*$");

    @Test
    void 分片推送的登录横幅应完整进缓冲() throws Exception {
        byte[] banner = "User name:".getBytes(StandardCharsets.US_ASCII);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread device = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    OutputStream out = socket.getOutputStream();
                    // 故意拆三包：模拟登录横幅被 TCP 分片，验证 cross-packet 衔接
                    out.write(banner, 0, 5);
                    out.flush();
                    Thread.sleep(30);
                    out.write(banner, 5, banner.length - 5);
                    out.write("\r\nroot#".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(200);
                } catch (Exception ignored) {
                    // 客户端提前关闭属于正常收尾
                }
            }, "fake-olt");
            device.setDaemon(true);
            device.start();

            try (TelnetClient client = TelnetClient.connect(server.getInetAddress().getHostAddress(),
                    server.getLocalPort(), null, Charset.forName("UTF-8"), 3000, 3000)) {
                String text = client.readUntilAny(List.of(PROMPT), 3000);
                assertTrue(text.contains("User name:"), "登录横幅应完整收到，实际：" + text);
                assertTrue(text.contains("root#"), "提示符应收到，实际：" + text);
            }
        }
    }
}

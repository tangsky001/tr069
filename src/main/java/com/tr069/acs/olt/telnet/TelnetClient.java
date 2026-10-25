package com.tr069.acs.olt.telnet;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 Telnet 客户端。
 *
 * <p>只做 OLT 命令行采集需要的那部分协议：</p>
 * <ul>
 *   <li>IAC 选项协商：对 WILL/DO 回 DONT/WONT，避免设备等待协商结果卡住；</li>
 *   <li>子协商（SB ... SE）整段丢弃；</li>
 *   <li>按配置字符集增量解码，处理退格、CR、ANSI 转义；</li>
 *   <li>自动应答分页提示（---- More ---- 之类）——不做这一步，长输出会停在第一页。</li>
 * </ul>
 *
 * <p>没有引入 commons-net：现场部署环境常常连不上外网仓库，多一个依赖就多一次装不上。</p>
 */
public class TelnetClient implements Closeable {

    private static final int IAC = 255;
    private static final int DONT = 254;
    private static final int DO = 253;
    private static final int WONT = 252;
    private static final int WILL = 251;
    private static final int SB = 250;
    private static final int SE = 240;

    private static final int OPT_BINARY = 0;
    private static final int OPT_ECHO = 1;
    private static final int OPT_SUPPRESS_GO_AHEAD = 3;

    /** 分页提示：Huawei / ZTE / FiberHome 的写法都在这里覆盖 */
    private static final Pattern MORE_PROMPT = Pattern.compile(
            "(?:-{2,}\\s*More\\s*(?:\\(\\s*Press\\s*'?[Qq]'?\\s*to\\s*break\\s*\\))?\\s*-{0,}"
                    + "|<---\\s*More\\s*--->"
                    + "|--More--"
                    + "|----\\s*More\\s*\\(\\s*[^)]*\\)\\s*----"
                    // 华为 OLT 的写法：提示打在行首或行中，破折号在后面，如
                    // "( Press 'Q' to break ) ----  0/ 1/0  32  534B..."（后面紧跟下一页内容）
                    + "|\\(\\s*Press\\s*'[Qq]'\\s*to\\s*break\\s*\\)\\s*-{0,})",
            Pattern.CASE_INSENSITIVE);

    /** 启动分页的默认字节上限，超出后只保留末尾，防止异常设备把内存打满 */
    private static final int DEFAULT_MAX_CHARS = 400_000;

    /**
     * 匹配到「提示符」后还要静默这么久才算真的结束。
     *
     * <p>设备的回显是一包一包来的，一行可能分几次到。只看 {@code >} 结尾会误判：
     * 例如 MA5683T 的参数提示 {@code { <cr>|backplane<K>|... }}，收到 {@code |backplane<K>}
     * 那一刻缓冲区正好以 {@code >} 结尾，就会被当成提示符，命令只拿到半行。
     * 因此匹配后要确认后续没有新字节，才认为提示符到齐了。</p>
     */
    private static final int PROMPT_SETTLE_MS = 120;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Charset charset;
    private final int maxChars;

    private final CharsetDecoder decoder;
    private final ByteBuffer byteBuffer = ByteBuffer.allocate(8192);
    private final CharBuffer charBuffer = CharBuffer.allocate(8192);
    private final StringBuilder text = new StringBuilder();

    /** ANSI 转义状态：0 普通，1 收到 ESC，2 CSI，3 OSC */
    private int ansiState;

    private boolean closed;

    private TelnetClient(Socket socket, Charset charset, int maxChars) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
        this.charset = charset;
        this.maxChars = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        this.decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    /**
     * 建立 TCP 连接。
     *
     * @param host             设备地址
     * @param port             设备端口
     * @param localIp          绑定的本地出口 IP，空则交给系统选路
     * @param charset          回显字符集
     * @param connectTimeoutMs 建连超时
     * @param readTimeoutMs    单次读超时，读取循环会按需覆盖
     */
    public static TelnetClient connect(String host, int port, String localIp, Charset charset,
                                       int connectTimeoutMs, int readTimeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            if (localIp != null && !localIp.isBlank()) {
                InetAddress local = InetAddress.getByName(localIp.trim());
                socket.bind(new InetSocketAddress(local, 0));
            }
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(Math.max(500, readTimeoutMs));
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            return new TelnetClient(socket, charset, DEFAULT_MAX_CHARS);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理，原始异常更重要
            }
            throw new OltTelnetException("连接 " + host + ":" + port + " 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 等待任意一个模式命中，返回此前的全部回显。
     *
     * @param stops     终止模式，通常带 {@code $} 表示「提示符出现在末尾」
     * @param timeoutMs 整体超时
     */
    public String readUntilAny(List<Pattern> stops, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        int matchedLength = -1;
        long matchedAt = 0;
        while (true) {
            boolean matched = matchesAny(stops);
            if (matched) {
                if (text.length() != matchedLength) {
                    matchedLength = text.length();
                    matchedAt = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() - matchedAt >= PROMPT_SETTLE_MS) {
                    return text.toString();
                }
            } else {
                matchedLength = -1;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new OltTelnetException("等待设备回显超时(" + timeoutMs + "ms)，已收到："
                        + tail(180));
            }
            socket.setSoTimeout((int) Math.max(50, Math.min(remaining, 500)));
            try {
                pumpOnce();
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                // 设备说完就断开（例如登录失败、只回一句就踢人）：已经匹配到内容就按正常收尾
                if (matchedLength >= 0) {
                    return text.toString();
                }
                throw e;
            }
            handlePagination();
        }
    }

    /**
     * 读取一段回显直到静默（不再有新字节），主要用于登录横幅这类没有明确提示符的场景。
     */
    public String readIdle(long idleMs, long maxMs) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(1, maxMs);
        long lastDataAt = System.currentTimeMillis();
        while (true) {
            long now = System.currentTimeMillis();
            if (now > deadline) {
                return text.toString();
            }
            socket.setSoTimeout((int) Math.max(50, Math.min(idleMs, deadline - now)));
            try {
                pumpOnce();
                lastDataAt = System.currentTimeMillis();
            } catch (SocketTimeoutException e) {
                if (System.currentTimeMillis() - lastDataAt >= idleMs) {
                    return text.toString();
                }
            }
            handlePagination();
        }
    }

    public String getText() {
        return text.toString();
    }

    public Charset getCharset() {
        return charset;
    }

    /**
     * 清空回显缓冲，进入下一条命令的读取。
     */
    public void resetBuffer() {
        text.setLength(0);
    }

    /**
     * 发送一行（自动补 CRLF）。
     */
    public void sendLine(String line) throws IOException {
        sendRaw(line + "\r\n");
    }

    /**
     * 发送原始字符串。
     */
    public void sendRaw(String data) throws IOException {
        if (closed) {
            throw new OltTelnetException("连接已关闭");
        }
        out.write(data.getBytes(charset));
        out.flush();
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭异常不影响调用方
        }
    }

    // ---------------------------------------------------------------- 读取与解码

    private void pumpOnce() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new OltTelnetException("设备已关闭连接");
        }
        if (b == IAC) {
            handleIac();
            return;
        }
        decode((byte) b);
    }

    private void decode(byte b) {
        byteBuffer.put(b);
        byteBuffer.flip();
        decoder.decode(byteBuffer, charBuffer, false);
        byteBuffer.compact();
        charBuffer.flip();
        while (charBuffer.hasRemaining()) {
            accept(charBuffer.get());
        }
        charBuffer.clear();
    }

    private void accept(char c) {
        switch (ansiState) {
            case 1:
                if (c == '[') {
                    ansiState = 2;
                } else if (c == ']') {
                    ansiState = 3;
                } else {
                    ansiState = 0;
                }
                return;
            case 2:
                if (c >= '@' && c <= '~') {
                    ansiState = 0;
                }
                return;
            case 3:
                if (c == '\u0007') {
                    ansiState = 0;
                }
                return;
            default:
                break;
        }
        if (c == 27) {
            ansiState = 1;
            return;
        }
        if (c == '\b') {
            if (text.length() > 0) {
                text.deleteCharAt(text.length() - 1);
            }
            return;
        }
        if (c == '\r') {
            return;
        }
        if (c == 0 || c == '\u0007' || (c < 0x20 && c != '\n' && c != '\t')) {
            return;
        }
        text.append(c);
        if (text.length() > maxChars) {
            text.delete(0, text.length() - maxChars / 2);
        }
    }

    // ---------------------------------------------------------------- 分页

    private void handlePagination() throws IOException {
        for (int guard = 0; guard < 200; guard++) {
            int from = Math.max(0, text.length() - 200);
            String window = text.substring(from);
            Matcher matcher = MORE_PROMPT.matcher(window);
            int start = -1;
            int end = -1;
            while (matcher.find()) {
                start = matcher.start();
                end = matcher.end();
            }
            if (start < 0) {
                return;
            }
            boolean atTail = window.substring(end).trim().isEmpty();
            // 分页提示不是正文，先删掉；华为 OLT 的提示常和下一页内容挤在同一行，
            // 那种情况设备已经在继续输出，只清提示不送空格
            text.delete(from + start, from + end);
            if (!atTail) {
                continue;
            }
            sendRaw(" ");
        }
    }

    // ---------------------------------------------------------------- Telnet 协商

    private void handleIac() throws IOException {
        int command = in.read();
        if (command < 0) {
            throw new OltTelnetException("设备已关闭连接");
        }
        switch (command) {
            case WILL: {
                int option = readOption();
                reply(acceptWill(option) ? DO : DONT, option);
                break;
            }
            case DO: {
                int option = readOption();
                reply(acceptDo(option) ? WILL : WONT, option);
                break;
            }
            case WONT:
            case DONT:
                readOption();
                break;
            case SB:
                skipSubnegotiation();
                break;
            case IAC:
                // IAC IAC 表示字面量 0xFF
                decode((byte) 0xFF);
                break;
            default:
                break;
        }
    }

    private boolean acceptWill(int option) {
        return option == OPT_ECHO || option == OPT_SUPPRESS_GO_AHEAD || option == OPT_BINARY;
    }

    private boolean acceptDo(int option) {
        return option == OPT_SUPPRESS_GO_AHEAD || option == OPT_BINARY;
    }

    private int readOption() throws IOException {
        int option = in.read();
        if (option < 0) {
            throw new OltTelnetException("设备已关闭连接");
        }
        return option;
    }

    private void reply(int command, int option) throws IOException {
        out.write(new byte[]{(byte) IAC, (byte) command, (byte) option});
        out.flush();
    }

    private void skipSubnegotiation() throws IOException {
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new OltTelnetException("设备已关闭连接");
            }
            if (b == IAC) {
                int next = in.read();
                if (next == SE || next < 0) {
                    return;
                }
            }
        }
    }

    /**
     * 只在末尾 512 字符里匹配：提示符一定在尾部，全量扫会让长回显变成 O(n²)。
     */
    private boolean matchesAny(List<Pattern> patterns) {
        String window = text.substring(Math.max(0, text.length() - 512));
        for (Pattern pattern : patterns) {
            if (pattern.matcher(window).find()) {
                return true;
            }
        }
        return false;
    }

    private String tail(int length) {
        String all = text.toString();
        return all.length() <= length ? all : all.substring(all.length() - length);
    }
}

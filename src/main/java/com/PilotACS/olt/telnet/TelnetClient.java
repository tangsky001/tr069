/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.783
 *
 */

package com.PilotACS.olt.telnet;
import com.PilotACS.common.log.Logs;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.concurrent.TimeUnit;

/** 极简 Telnet 客户端（Netty NIO 版） */
public class TelnetClient implements Closeable {

    /** 全部连接共享的事件循环组 */
    private static final EventLoopGroup GROUP =
            new NioEventLoopGroup(0, new DefaultThreadFactory("olt-telnet-netty", true));

    /** 关闭共享的事件循环组 */
    public static void shutdownGroup() {
        GROUP.shutdownGracefully(0, 5, TimeUnit.SECONDS);
    }

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

    /** 接受的 WILL 选项 */
    private static final Set<Integer> ACCEPT_WILL = Set.of(OPT_ECHO, OPT_SUPPRESS_GO_AHEAD, OPT_BINARY);
    /** 接受的 DO 选项 */
    private static final Set<Integer> ACCEPT_DO = Set.of(OPT_SUPPRESS_GO_AHEAD, OPT_BINARY);

    private static final int ST_DATA = 0;
    private static final int ST_IAC = 1;
    private static final int ST_CMD = 2;
    private static final int ST_SB = 3;
    private static final int ST_SB_IAC = 4;

    /** 分页提示 */
    private static final Pattern MORE_PROMPT = Pattern.compile(
            "(?:-{2,}\\s*More\\s*(?:\\(\\s*Press\\s*'?\"?[Qq]'?\\s*to\\s*break\\s*\\))?\\s*-{0,}"
                    + "|<---\\s*More\\s*--->"
                    + "|--More--"
                    + "|----\\s*More\\s*\\(\\s*[^)]*\\)\\s*----"
                    + "|\\(\\s*Press\\s*'[Qq]'\\s*to\\s*break\\s*\\)\\s*-{0,}"
                    + "|<\\s*More\\s*>"
                    + "|Press\\s+any\\s+key\\s+to\\s+continue)",
            Pattern.CASE_INSENSITIVE);

    /** 分页提示残段 */
    private static final Pattern MORE_TAIL = Pattern.compile(
            "(?is)(?:-{2,}\\s*M(?:o(?:r(?:e)?)?)?"
                    + "|\\(\\s*P(?:r(?:e(?:s(?:s)?)?)?)?)\\s*$");

    /** 启动分页的默认字节上限 */
    private static final int DEFAULT_MAX_CHARS = 400_000;

    /** 分页卡顿判定阈值 */
    private static final long PAGE_STALL_MS = 500;

    /** 两次按键最小间隔 */
    private static final long PAGE_PRESS_GAP_MS = 150;

    /** 单条命令按键次数上限 */
    private static final int PAGE_PRESS_LIMIT = 60;

    /** 提示符静默确认时间 */
    private static final int PROMPT_SETTLE_MS = 50;

    /** 轮询等待间隔 */
    private static final int POLL_WAIT_MS = 50;

    /** 分页是否已关闭 */
    private volatile boolean pagingDisabled;

    private volatile Channel channel;
    private final Charset charset;
    private final int maxChars;

    private final CharsetDecoder decoder;
    private final CharBuffer decodeOut = CharBuffer.allocate(8192);
    /** 待解码数据缓冲 */
    private ByteBuffer pending = ByteBuffer.allocate(64 * 1024);
    private final StringBuilder text = new StringBuilder();

    /** 回显尾窗视图 */
    private final TailView matchWindow = new TailView();
    private final TailView pageWindow = new TailView();

    /** 等待/唤醒锁 */
    private final Object monitor = new Object();

    /** 已应答过的协商项 */
    private final Set<Integer> answered = ConcurrentHashMap.newKeySet();

    /** ANSI 转义状态 */
    private int ansiState;

    private volatile boolean closed;
    /** 设备侧断开标记 */
    private volatile boolean eof;

    /** 最近收到字节的时间 */
    private volatile long lastByteAt = System.currentTimeMillis();
    /** 本次命令首个回显字节时间 */
    private long firstByteAt;
    /** 最近一次翻页按键时间 */
    private long lastPagePressAt;
    /** 本次命令按键次数 */
    private int pagePresses;
    /** 连续无效按键次数 */
    private int fruitlessPresses;

    private TelnetClient(Charset charset, int maxChars) {
        this.charset = charset;
        this.maxChars = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        this.decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    /** 建立 TCP 连接并完成固定的首轮协商 */
    public static TelnetClient connect(String host, int port, String localIp, Charset charset,
                                       int connectTimeoutMs, long readTimeoutMs) throws IOException {
        TelnetClient client = new TelnetClient(charset, DEFAULT_MAX_CHARS);
        Bootstrap bootstrap = new Bootstrap()
                .group(GROUP)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(1, connectTimeoutMs))
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .remoteAddress(host, port)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new IacDecoder(client),
                                new SimpleChannelInboundHandler<ByteBuf>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                        client.onData(msg);
                                    }

                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                        Logs.warn(Logs.Module.OLT_COLLECT, "Telnet 收数据异常，关闭连接：{}", cause.toString());
                                        client.onInactive();
                                        ctx.close();
                                    }
                                });
                    }
                });
        if (localIp != null && !localIp.isBlank()) {
            bootstrap.localAddress(new InetSocketAddress(InetAddress.getByName(localIp.trim()), 0));
        }
        ChannelFuture future = bootstrap.connect();
        if (!future.awaitUninterruptibly(Math.max(1, connectTimeoutMs)) || !future.isSuccess()) {
            Throwable cause = future.cause();
            future.channel().close();
            throw new OltTelnetException(cause == null
                    ? "连接 " + host + ":" + port + " 连接超时"
                    : "连接 " + host + ":" + port + " 失败：" + cause.getMessage(), cause);
        }
        client.channel = future.channel();
        return client;
    }

    /** 等待任意一个模式命中，返回此前的全部回显 */
    public String readUntilAny(List<Pattern> stops, long timeoutMs) throws IOException {
        return readUntilAny(stops, timeoutMs, false);
    }

    /** 等待模式命中（带分页推进） */
    public String readUntilAny(List<Pattern> stops, long timeoutMs, boolean pageAssist) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        int matchedLength = -1;
        long matchedAt = 0;
        while (true) {
            synchronized (monitor) {
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
                if (eof) {
                    if (matchedLength >= 0) {
                        return text.toString();
                    }
                    throw new OltTelnetException("设备已关闭连接，已收到：" + tail(180));
                }
                handlePagination();
                if (pageAssist && !matched && !pagingDisabled) {
                    pressPageIfStalled();
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new OltTelnetException("等待设备回显超时(" + timeoutMs + "ms)，已收到：" + tail(180));
                }
                long waitMs = Math.min(remaining, POLL_WAIT_MS);
                if (matched) {
                    long settleLeft = PROMPT_SETTLE_MS - (System.currentTimeMillis() - matchedAt);
                    waitMs = Math.min(waitMs, Math.max(1, settleLeft));
                }
                try {
                    monitor.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new OltTelnetException("等待设备回显被中断", e);
                }
            }
        }
    }

    /** 读回显直到静默 */
    public String readIdle(long idleMs, long maxMs) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(1, maxMs);
        long lastDataAt = System.currentTimeMillis();
        int lastLength = 0;
        while (true) {
            synchronized (monitor) {
                if (text.length() != lastLength) {
                    lastDataAt = System.currentTimeMillis();
                    lastLength = text.length();
                }
                long now = System.currentTimeMillis();
                if (now - lastDataAt >= idleMs || now > deadline) {
                    return text.toString();
                }
                if (eof) {
                    return text.toString();
                }
                handlePagination();
                try {
                    monitor.wait(Math.max(20, Math.min(idleMs, deadline - now)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new OltTelnetException("等待设备回显被中断", e);
                }
            }
        }
    }

    public String getText() {
        synchronized (monitor) {
            return text.toString();
        }
    }

    public Charset getCharset() {
        return charset;
    }

    /** 连接是否还活着 */
    public boolean isAlive() {
        Channel ch = channel;
        return !closed && !eof && ch != null && ch.isActive();
    }

    /** 清空回显缓冲，进入下一条命令的读取 */
    public void resetBuffer() {
        synchronized (monitor) {
            text.setLength(0);
            pagePresses = 0;
            fruitlessPresses = 0;
            lastPagePressAt = 0;
            lastByteAt = System.currentTimeMillis();
            firstByteAt = 0;
        }
    }

    /** 标记分页已关闭 */
    public void setPagingDisabled(boolean disabled) {
        pagingDisabled = disabled;
    }

    /** 本次命令首个回显字节时间戳 */
    public long firstByteAt() {
        synchronized (monitor) {
            return firstByteAt;
        }
    }

    /** 发送一行（自动补 CRLF） */
    public void sendLine(String line) throws IOException {
        sendRaw(line + "\r\n");
    }

    /** 发送原始字符串 */
    public void sendRaw(String data) throws IOException {
        Channel ch = channel;
        if (closed || ch == null || !ch.isActive()) {
            throw new OltTelnetException("连接已关闭");
        }
        lastByteAt = System.currentTimeMillis();
        ch.writeAndFlush(Unpooled.wrappedBuffer(data.getBytes(charset)))
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        onInactive();
                    }
                });
    }

    /** 连接建立并完成首轮协商 */
    void onActive(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        replyIac(ctx, WILL, OPT_SUPPRESS_GO_AHEAD);
        replyIac(ctx, DO, OPT_SUPPRESS_GO_AHEAD);
        answered.add((WILL << 8) | OPT_SUPPRESS_GO_AHEAD);
        answered.add((DO << 8) | OPT_SUPPRESS_GO_AHEAD);
    }

    /** 设备侧断开回调 */
    void onInactive() {
        eof = true;
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    /** 收到正文数据 */
    void onData(ByteBuf buf) {
        int n = buf.readableBytes();
        synchronized (monitor) {
            if (pending.remaining() < n) {
                ByteBuffer bigger = ByteBuffer.allocate(
                        Math.max(pending.capacity() * 2, pending.position() + n));
                pending.flip();
                bigger.put(pending);
                pending = bigger;
            }
            int writePos = pending.position();
            pending.limit(writePos + n);
            buf.readBytes(pending);
            pending.limit(pending.capacity());
            decodePending();
            long now = System.currentTimeMillis();
            if (firstByteAt == 0) {
                firstByteAt = now;
            }
            lastByteAt = now;
            fruitlessPresses = 0;
            monitor.notifyAll();
        }
    }

    /** 固定协商 */
    private void negotiate(ChannelHandlerContext ctx, int command, int option) {
        Byte answer;
        switch (command) {
            case WILL:
                answer = (byte) (ACCEPT_WILL.contains(option) ? DO : DONT);
                break;
            case DO:
                answer = (byte) (ACCEPT_DO.contains(option) ? WILL : WONT);
                break;
            default:
                return;
        }
        if (!answered.add((command << 8) | option)) {
            return;
        }
        replyIac(ctx, answer, option);
    }

    private static void replyIac(ChannelHandlerContext ctx, int command, int option) {
        ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{(byte) IAC, (byte) command, (byte) option}));
    }

    private void decodePending() {
        pending.flip();
        decoder.decode(pending, decodeOut, false);
        decodeOut.flip();
        while (decodeOut.hasRemaining()) {
            accept(decodeOut.get());
        }
        decodeOut.clear();
        pending.compact();
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

    private void handlePagination() throws IOException {
        if (pagingDisabled) {
            return;
        }
        for (int guard = 0; guard < 200; guard++) {
            int from = Math.max(0, text.length() - 400);
            CharSequence window = pageWindow.of(400);
            Matcher matcher = MORE_PROMPT.matcher(window);
            int start = -1;
            int end = -1;
            while (matcher.find()) {
                start = matcher.start();
                end = matcher.end();
            }
            if (start < 0) {
                if (MORE_TAIL.matcher(window).find()) {
                    pressPageOnce();
                }
                return;
            }
            boolean atTail = isBlankAfter(window, end);
            text.delete(from + start, from + end);
            if (!atTail) {
                continue;
            }
            pressPageOnce();
        }
    }

    /** 判断是否全为空白 */
    private static boolean isBlankAfter(CharSequence view, int end) {
        for (int i = end; i < view.length(); i++) {
            if (!Character.isWhitespace(view.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 分页看门狗 */
    private void pressPageIfStalled() throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastByteAt < PAGE_STALL_MS) {
            return;
        }
        pressPageOnce();
    }

    /** 翻页按键 */
    private void pressPageOnce() throws IOException {
        long now = System.currentTimeMillis();
        if (lastPagePressAt > 0 && now - lastPagePressAt < PAGE_PRESS_GAP_MS) {
            return;
        }
        if (pagePresses >= PAGE_PRESS_LIMIT) {
            return;
        }
        if (fruitlessPresses >= 4) {
            return;
        }
        pagePresses++;
        fruitlessPresses++;
        lastPagePressAt = now;
        sendRaw(" ");
    }

    /** 在末尾窗口内匹配 */
    private boolean matchesAny(List<Pattern> patterns) {
        CharSequence window = matchWindow.of(512);
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

    @Override
    public void close() {
        closed = true;
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    /** 回显尾窗零拷贝视图 */
    private final class TailView implements CharSequence {
        private int from;

        TailView of(int max) {
            this.from = Math.max(0, text.length() - max);
            return this;
        }

        @Override
        public int length() {
            return text.length() - from;
        }

        @Override
        public char charAt(int index) {
            return text.charAt(from + index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(from + start, from + end);
        }

        @Override
        public String toString() {
            return text.substring(from);
        }
    }

    /** IAC 解码器 */
    private static final class IacDecoder extends ByteToMessageDecoder {

        private final TelnetClient client;
        private int state = ST_DATA;
        private int command;

        private IacDecoder(TelnetClient client) {
            this.client = client;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            ByteBuf data = null;
            try {
                while (in.isReadable()) {
                    int b = in.readUnsignedByte();
                    switch (state) {
                        case ST_DATA:
                            if (b == IAC) {
                                state = ST_IAC;
                            } else {
                                data = dataBuffer(ctx, data);
                                data.writeByte(b);
                            }
                            break;
                        case ST_IAC:
                            if (b == IAC) {
                                data = dataBuffer(ctx, data);
                                data.writeByte(0xFF);
                                state = ST_DATA;
                            } else {
                                command = b;
                                state = b == SB ? ST_SB : ST_CMD;
                            }
                            break;
                        case ST_CMD:
                            client.negotiate(ctx, command, b);
                            state = ST_DATA;
                            break;
                        case ST_SB:
                            if (b == IAC) {
                                state = ST_SB_IAC;
                            }
                            break;
                        case ST_SB_IAC:
                            state = b == SE ? ST_DATA : ST_SB;
                            break;
                        default:
                            state = ST_DATA;
                            break;
                    }
                }
                if (data != null && data.isReadable()) {
                    out.add(data);
                    data = null;
                }
            } finally {
                if (data != null) {
                    data.release();
                }
            }
        }

        private static ByteBuf dataBuffer(ChannelHandlerContext ctx, ByteBuf existing) {
            return existing != null ? existing : ctx.alloc().ioBuffer(256);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            client.onActive(ctx);
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            client.onInactive();
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            client.onInactive();
            ctx.close();
        }
    }
}

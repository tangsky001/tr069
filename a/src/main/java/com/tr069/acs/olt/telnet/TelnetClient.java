package com.tr069.acs.olt.telnet;

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

import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class TelnetClient implements Closeable {

    /** 全部 Telnet 连接共享的事件循环组：连接数上百也只占这几条线程 */
    private static final EventLoopGroup GROUP =
            new NioEventLoopGroup(0, new DefaultThreadFactory("olt-telnet-netty", true));

    /** 关闭共享的事件循环组（应用关闭时调用） */
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

    // ---------------- 固定协商策略（写死，不做动态协商） ----------------

    /** 设备提议 WILL x 时接受哪些：其余一律回 DONT */
    private static final Set<Integer> ACCEPT_WILL = Set.of(OPT_ECHO, OPT_SUPPRESS_GO_AHEAD, OPT_BINARY);
    /** 设备请求 DO x 时接受哪些：其余一律回 WONT */
    private static final Set<Integer> ACCEPT_DO = Set.of(OPT_SUPPRESS_GO_AHEAD, OPT_BINARY);

    // ---------------- IAC 解析状态机 ----------------

    /** 普通数据流 */
    private static final int ST_DATA = 0;
    /** 收到 IAC */
    private static final int ST_IAC = 1;
    /** 收到 IAC + 命令字，等选项字节 */
    private static final int ST_CMD = 2;
    /** 子协商 SB ... SE 内容，整段丢弃 */
    private static final int ST_SB = 3;
    /** SB 里出现 IAC：IAC IAC 是字面量，IAC SE 结束子协商 */
    private static final int ST_SB_IAC = 4;

    /** 分页提示：Huawei / ZTE / FiberHome 的写法都在这里覆盖 */
    private static final Pattern MORE_PROMPT = Pattern.compile(
            "(?:-{2,}\\s*More\\s*(?:\\(\\s*Press\\s*'?\"?[Qq]'?\\s*to\\s*break\\s*\\))?\\s*-{0,}"
                    + "|<---\\s*More\\s*--->"
                    + "|--More--"
                    + "|----\\s*More\\s*\\(\\s*[^)]*\\)\\s*----"
                    // 华为 OLT 的写法：提示打在行首或行中，破折号在后面（提示后紧跟下一页内容）
                    + "|\\(\\s*Press\\s*'[Qq]'\\s*to\\s*break\\s*\\)\\s*-{0,}"
                    // 其它常见分页写法
                    + "|<\\s*More\\s*>"
                    + "|Press\\s+any\\s+key\\s+to\\s+continue)",
            Pattern.CASE_INSENSITIVE);

    /** 分页提示的「残段」：提示被 TCP 拆包（{@code ---- Mo} 先到、{@code re ----} 后到） */
    private static final Pattern MORE_TAIL = Pattern.compile(
            "(?is)(?:-{2,}\\s*M(?:o(?:r(?:e)?)?)?"
                    + "|\\(\\s*P(?:r(?:e(?:s(?:s)?)?)?)?)\\s*$");

    /** 启动分页的默认字节上限，超出后只保留末尾，防止异常设备把内存打满 */
    private static final int DEFAULT_MAX_CHARS = 400_000;

    // ---------------- 分页看门狗（不依赖 More 提示被识别） ----------------

    /** 这么久没有新字节、又还没等到提示符，就当卡在分页上，主动按一次空格推进一页 */
    private static final long PAGE_STALL_MS = 500;

    /** 两次主动按键的最小间隔，防止刷屏 */
    private static final long PAGE_PRESS_GAP_MS = 150;

    /** 单条命令最多主动按几次：设备真沉默（不是分页）时不能无限灌按键 */
    private static final int PAGE_PRESS_LIMIT = 60;

    /** 提示符匹配到后的静默确认：50ms 覆盖局域网 TCP 包间间隔（OLT 回显流式到达，提示符是末尾包） */
    private static final int PROMPT_SETTLE_MS = 50;

    /** 未匹配提示符时的轮询间隙上限：onData 的 notifyAll 即时唤醒，50ms 只是提示符分包到达的兜底 */
    private static final int POLL_WAIT_MS = 50;

    /** 分页已被关闭（screen-length 0 / undo smart）：为 true 时跳过翻页检测和卡顿按键推进 */
    private volatile boolean pagingDisabled;

    private volatile Channel channel;
    private final Charset charset;
    private final int maxChars;

    private final CharsetDecoder decoder;
    private final CharBuffer decodeOut = CharBuffer.allocate(8192);
    /** IAC 过滤后的待解码数据（compact 模式）：尾部留不完整的多字节字符等下一包续解 */
    private ByteBuffer pending = ByteBuffer.allocate(64 * 1024);
    private final StringBuilder text = new StringBuilder();

    /** 回显尾窗的零拷贝视图（复用实例）：正则只看末尾几百字符，不再每轮 substring 复制 */
    private final TailView matchWindow = new TailView();
    private final TailView pageWindow = new TailView();

    /** 本会话的等待/唤醒锁；事件循环线程填充缓冲，业务线程在这里等匹配 */
    private final Object monitor = new Object();

    /** 已应答过的 (命令 << 8 | 选项)：重复提议不重复应答，杜绝协商拉锯 */
    private final Set<Integer> answered = ConcurrentHashMap.newKeySet();

    /** ANSI 转义状态：0 普通，1 收到 ESC，2 CSI，3 OSC */
    private int ansiState;

    private volatile boolean closed;
    /** 设备侧关了连接（channelInactive / 异常）：与本地 close 区分，业务侧据此收尾 */
    private volatile boolean eof;

    /** 最近一次收到字节的时间：分页看门狗靠它判断「回显卡住了」 */
    private volatile long lastByteAt = System.currentTimeMillis();
    /** 本次命令收到第一个回显字节的时间（resetBuffer 清零）：区分「设备算得慢」和「回显收得慢」 */
    private long firstByteAt;
    /** 最近一次主动翻页按键的时间 */
    private long lastPagePressAt;
    /** 本次命令已经主动按了几次（resetBuffer 时清零） */
    private int pagePresses;
    /** 连续按了几次都没换来新回显：分页器一按就有数据，一直没数据说明设备是在算 */
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
                        // IacDecoder 输出过滤后的正文 ByteBuf，由下一个 handler 交给会话缓冲
                        ch.pipeline().addLast(new IacDecoder(client),
                                new SimpleChannelInboundHandler<ByteBuf>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                        client.onData(msg);
                                    }

                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                        // 收数据途中出错不能再往管道尾丢：那样数据静静丢掉，业务侧只看到「回显超时」没有线索
                                        log.warn("Telnet 收数据异常，关闭连接：{}", cause.toString());
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
        // awaitUninterruptibly：连接阶段不吃线程中断，超时由 CONNECT_TIMEOUT_MILLIS 与这里双保险
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

    // ---------------------------------------------------------------- 等待回显

    /** 等待任意一个模式命中，返回此前的全部回显 */
    public String readUntilAny(List<Pattern> stops, long timeoutMs) throws IOException {
        return readUntilAny(stops, timeoutMs, false);
    }

    /** 靠「卡顿看门狗」主动按键推进，不再干等提示符超时 */
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
                        // 设备说完就断开（例如登录失败、只回一句就踢人）：已经匹配到内容就按正常收尾
                        return text.toString();
                    }
                    throw new OltTelnetException("设备已关闭连接，已收到：" + tail(180));
                }
                handlePagination();
                // 还没等到提示符、回显又停住了：十有八九卡在分页上，主动按键推进（分页已被 screen-length 0 关闭时跳过）
                if (pageAssist && !matched && !pagingDisabled) {
                    pressPageIfStalled();
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new OltTelnetException("等待设备回显超时(" + timeoutMs + "ms)，已收到：" + tail(180));
                }
                long waitMs = Math.min(remaining, POLL_WAIT_MS);
                if (matched) {
                    // 提示符已经到齐，只差静默确认：按剩余确认时间睡，别整轮 200ms 白等
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

    /** 读取一段回显直到静默（不再有新字节），主要用于登录横幅这类没有明确提示符的场景 */
    public String readIdle(long idleMs, long maxMs) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(1, maxMs);
        long lastDataAt = System.currentTimeMillis();
        int lastLength = 0;
        while (true) {
            synchronized (monitor) {
                // 只有缓冲真的变长才算「设备还在说话」
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

    /** 连接是否还活着：不发任何探测命令，只看通道与 EOF 标记（省下 OLT 的一次往返） */
    public boolean isAlive() {
        Channel ch = channel;
        return !closed && !eof && ch != null && ch.isActive();
    }

    /** 清空回显缓冲，进入下一条命令的读取 */
    public void resetBuffer() {
        synchronized (monitor) {
            text.setLength(0);
            // 新的一条命令：主动翻页计数从头算，卡顿计时从这一刻起（别拿上一条命令的旧时间立刻按键）
            pagePresses = 0;
            fruitlessPresses = 0;
            lastPagePressAt = 0;
            lastByteAt = System.currentTimeMillis();
            firstByteAt = 0;
        }
    }

    /** 标记分页已被关闭（screen-length 0 等）：后续 readUntilAny 跳过翻页检测和卡顿按键推进 */
    public void setPagingDisabled(boolean disabled) {
        pagingDisabled = disabled;
    }

    /** 本次命令收到第一个回显字节的时间戳，0 表示还没收到任何字节 */
    public long firstByteAt() {
        synchronized (monitor) {
            return firstByteAt;
        }
    }

    // ---------------------------------------------------------------- 发送

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
        // 刚发出去就算「有活动」：看门狗至少等满 PAGE_STALL_MS 才按键，免得设备还在算就先塞一个空格
        lastByteAt = System.currentTimeMillis();
        // Netty 写入线程安全，且回压由其内部出站缓冲消化，业务线程不会被 TCP 零窗口卡住
        ch.writeAndFlush(Unpooled.wrappedBuffer(data.getBytes(charset)))
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        onInactive();
                    }
                });
    }

    // ---------------------------------------------------------------- 事件循环回调（仅 Netty 线程调用）

    /** 连接建立：挂上 channel 引用，完成固定首轮协商 */
    void onActive(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        // 申报内容记入 answered：设备后续无论怎么反复提议，都按固定表只答一次
        replyIac(ctx, WILL, OPT_SUPPRESS_GO_AHEAD);
        replyIac(ctx, DO, OPT_SUPPRESS_GO_AHEAD);
        answered.add((WILL << 8) | OPT_SUPPRESS_GO_AHEAD);
        answered.add((DO << 8) | OPT_SUPPRESS_GO_AHEAD);
    }

    /** 设备侧断开 / 通道异常：唤醒等在缓冲上的业务线程去收尾 */
    void onInactive() {
        eof = true;
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    /** 解码出的正文数据：直接把网络字节读进 ByteBuffer 缓冲，不落中间 byte[]，批量进字符集解码器 */
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
            // ByteBuf 直读进 ByteBuffer 写入区：Netty 的 readBytes(ByteBuffer) 按 dst.remaining() 整段读不截断，
            // 必须先把 limit 收到「刚好本包 n 字节」，否则小包读不够会抛异常丢数据
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
            // 有回显了：上一轮「按了没反应」的计数清零（分页器一按就会吐下一页）
            fruitlessPresses = 0;
            monitor.notifyAll();
        }
    }

    /** 固定协商：设备提议什么答什么是写死的，同一提议只答一次 */
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
                // WONT / DONT 是终止性通知，按协议不需要回应
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

    // ---------------------------------------------------------------- 解码

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

    // ---------------------------------------------------------------- 分页

    private void handlePagination() throws IOException {
        if (pagingDisabled) {
            return;
        }
        for (int guard = 0; guard < 200; guard++) {
            // 尾窗用零拷贝 CharSequence 视图匹配：不再每轮 substring 复制几百字符
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
                // 完整提示没认出来：尾部是「---- Mo」「( Press」这类残段也要翻页，否则等到超时（MA5683T 有提示被拆包的情况）
                if (MORE_TAIL.matcher(window).find()) {
                    pressPageOnce();
                }
                return;
            }
            boolean atTail = isBlankAfter(window, end);
            // 分页提示不是正文先删掉；华为 OLT 提示常和下一页内容挤同一行，此时设备已在继续输出，只清提示不送空格
            text.delete(from + start, from + end);
            if (!atTail) {
                continue;
            }
            pressPageOnce();
        }
    }

    /** 视图 end 之后是否全是空白（零分配：逐字符看，不 substring） */
    private static boolean isBlankAfter(CharSequence view, int end) {
        for (int i = end; i < view.length(); i++) {
            if (!Character.isWhitespace(view.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 分页看门狗：不依赖「---- More ----」被完整识别 */
    private void pressPageIfStalled() throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastByteAt < PAGE_STALL_MS) {
            return;
        }
        pressPageOnce();
    }

    /** 翻页按键（空格=下一屏），带间隔与次数上限，避免把按键刷进设备输入缓冲 */
    private void pressPageOnce() throws IOException {
        long now = System.currentTimeMillis();
        if (lastPagePressAt > 0 && now - lastPagePressAt < PAGE_PRESS_GAP_MS) {
            return;
        }
        if (pagePresses >= PAGE_PRESS_LIMIT) {
            return;
        }
        // 连按几次都没回显：设备不是在等翻页，停手等它自己往下说
        if (fruitlessPresses >= 4) {
            return;
        }
        pagePresses++;
        fruitlessPresses++;
        lastPagePressAt = now;
        sendRaw(" ");
    }

    // ---------------------------------------------------------------- 匹配

    /** 只在末尾 512 字符里匹配：提示符一定在尾部，全量扫会让长回显变成 O(n²)；尾窗走零拷贝视图不再复制字符串 */
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

    // ---------------------------------------------------------------- 收尾

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

    /** 回显尾窗的零拷贝 CharSequence 视图：指向 text 的末尾 max 个字符，读时零复制 */
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

    // ---------------------------------------------------------------- Netty pipeline

    /** IAC 解码器：Netty 的 {@link ByteToMessageDecoder} 自带跨包累积缓冲 */
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
                                // IAC IAC 表示字面量 0xFF
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
                            // IAC SE 结束子协商；SB 里的 IAC IAC 是字面量，整段内容本来就丢弃
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
                // 异常路径下把没送出去的缓冲还掉，防止内存泄漏
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

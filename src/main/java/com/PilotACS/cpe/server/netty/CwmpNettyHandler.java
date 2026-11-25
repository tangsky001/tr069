/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.cpe.server.netty;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.server.CwmpService;
import com.PilotACS.common.log.Logs;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** CWMP 报文处理器 */
@Component
@ChannelHandler.Sharable
public class CwmpNettyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final CwmpService cwmpService;
    private final CpeProperties props;

    /**
     * 业务线程池：会话处理含 SOAP 解析/DB 读写/per-SN 锁等待，
     * 必须移出 Netty event loop，否则一台慢设备会卡住同 event loop 上所有设备的响应，
     * 导致设备超时重发 Inform → 会话被顶替重建。
     */
    private final ExecutorService businessPool = Executors.newFixedThreadPool(
            Math.max(8, Runtime.getRuntime().availableProcessors() * 4), r -> {
                Thread t = new Thread(r, "cwmp-biz");
                t.setDaemon(true);
                return t;
            });

    public CwmpNettyHandler(CwmpService cwmpService, CpeProperties props) {
        this.cwmpService = cwmpService;
        this.props = props;
    }

    @PreDestroy
    public void shutdown() {
        businessPool.shutdown();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        String remoteAddr = remoteAddr(ctx);
        if (!"POST".equalsIgnoreCase(request.method().name())) {
            respond(ctx, keepAlive, build(HttpResponseStatus.METHOD_NOT_ALLOWED, null, null));
            return;
        }
        if (!basicAuthOk(request, remoteAddr)) {
            respond(ctx, keepAlive, build(HttpResponseStatus.UNAUTHORIZED, null,
                    r -> r.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Basic realm=\"acs\"")));
            return;
        }
        // 先在 event loop 上把报文取出（channelRead0 返回后 FullHttpRequest 会被释放）
        String body = request.content().toString(StandardCharsets.UTF_8);
        String sessionId = cookieSession(request);
        try {
            businessPool.execute(() -> {
                CwmpService.Reply settled;
                try {
                    settled = cwmpService.processRequest(body, sessionId, remoteAddr);
                } catch (Exception e) {
                    Logs.error(Logs.Module.CPE, "CWMP 报文处理异常", e,
                            "地址", remoteAddr);
                    settled = CwmpService.Reply.empty(500);
                }
                respond(ctx, keepAlive, toResponse(settled));
            });
        } catch (RejectedExecutionException e) {
            Logs.warn(Logs.Module.CPE, "业务线程池已满，回 503 让设备重试", "地址", remoteAddr);
            respond(ctx, keepAlive, build(HttpResponseStatus.SERVICE_UNAVAILABLE, null, null));
        }
    }

    private FullHttpResponse toResponse(CwmpService.Reply settled) {
        byte[] payload = settled.body() == null || settled.body().isEmpty()
                ? null : settled.body().getBytes(StandardCharsets.UTF_8);
        return build(HttpResponseStatus.valueOf(settled.httpStatus()), payload, r -> {
            if (payload != null) {
                r.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
            }
            if (settled.newSessionId() != null) {
                // Cookie Max-Age 跟随会话实际空闲超时（设备声明优先），而非固定 60s，
                // 避免长会话中途 Cookie 失效导致会话绑定断裂
                int maxAge = settled.cookieMaxAgeSeconds() > 0
                        ? settled.cookieMaxAgeSeconds()
                        : props.getSessionTimeoutSeconds();
                r.headers().set(HttpHeaderNames.SET_COOKIE, "session=" + settled.newSessionId()
                        + "; Path=/; HttpOnly; Max-Age=" + maxAge + "; Discard");
            }
        });
    }

    private void respond(ChannelHandlerContext ctx, boolean keepAlive, FullHttpResponse response) {
        HttpUtil.setContentLength(response, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION,
                keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
        if (keepAlive) {
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Logs.debug(Logs.Module.CPE, "CWMP 连接异常", "地址", remoteAddr(ctx), "原因", cause.toString());
        ctx.close();
    }

    private String remoteAddr(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress addr
                && addr.getAddress() != null) {
            return addr.getAddress().getHostAddress();
        }
        return "";
    }

    private FullHttpResponse build(HttpResponseStatus status, byte[] payload,
                                   java.util.function.Consumer<FullHttpResponse> extra) {
        FullHttpResponse r = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                payload == null ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(payload));
        if (extra != null) {
            extra.accept(r);
        }
        return r;
    }

    private boolean basicAuthOk(FullHttpRequest request, String remoteAddr) {
        if (props.getAuthUsername() == null || props.getAuthUsername().isBlank()) {
            return true;
        }
        String auth = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Basic ")) {
            return false;
        }
        String expected = Base64.getEncoder().encodeToString(
                (props.getAuthUsername() + ":" + props.getAuthPassword()).getBytes(StandardCharsets.UTF_8));
        boolean ok = expected.equals(auth.substring(6).trim());
        if (!ok) {
            Logs.warn(Logs.Module.CPE, "CPE Basic 认证失败", "地址", remoteAddr);
        }
        return ok;
    }

    private String cookieSession(FullHttpRequest request) {
        String cookie = request.headers().get(HttpHeaderNames.COOKIE);
        if (cookie == null) {
            return null;
        }
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("session=")) {
                return p.substring(8);
            }
        }
        return null;
    }
}

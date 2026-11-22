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
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** CWMP 报文处理器 */
@Component
@ChannelHandler.Sharable
public class CwmpNettyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final CwmpService cwmpService;
    private final CpeProperties props;

    public CwmpNettyHandler(CwmpService cwmpService, CpeProperties props) {
        this.cwmpService = cwmpService;
        this.props = props;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        String remoteAddr = remoteAddr(ctx);
        FullHttpResponse response;
        if (!"POST".equalsIgnoreCase(request.method().name())) {
            response = build(HttpResponseStatus.METHOD_NOT_ALLOWED, null, null);
        } else if (!basicAuthOk(request, remoteAddr)) {
            response = build(HttpResponseStatus.UNAUTHORIZED, null,
                    r -> r.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Basic realm=\"acs\""));
        } else {
            String body = request.content().toString(StandardCharsets.UTF_8);
            String sessionId = cookieSession(request);
            CwmpService.Reply reply;
            try {
                reply = cwmpService.processRequest(body, sessionId, remoteAddr);
            } catch (Exception e) {
                Logs.error(Logs.Module.CPE, "CWMP 报文处理异常", e,
                        "地址", remoteAddr, "报文", request.uri());
                reply = CwmpService.Reply.empty(500);
            }
            final CwmpService.Reply settled = reply;
            byte[] payload = settled.body() == null || settled.body().isEmpty()
                    ? null : settled.body().getBytes(StandardCharsets.UTF_8);
            response = build(HttpResponseStatus.valueOf(settled.httpStatus()), payload, r -> {
                if (payload != null) {
                    r.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
                }
                if (settled.newSessionId() != null) {
                    r.headers().set(HttpHeaderNames.SET_COOKIE, "session=" + settled.newSessionId()
                            + "; Path=/; HttpOnly; Max-Age=" + props.getSessionTimeoutSeconds() + "; Discard");
                }
            });
        }
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

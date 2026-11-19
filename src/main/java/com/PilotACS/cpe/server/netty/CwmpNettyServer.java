/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.cpe.server.netty;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.common.log.Logs;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** CPE 上报接入层（Netty）：在 acs.cpe.ports 配置的每个端口上起 Netty HTTP 服务， */
@Component
public class CwmpNettyServer implements SmartLifecycle {

    private final CpeProperties props;
    private final CwmpNettyHandler handler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final List<Channel> servers = new ArrayList<>();
    private volatile boolean running;

    public CwmpNettyServer(CpeProperties props, CwmpNettyHandler handler) {
        this.props = props;
        this.handler = handler;
    }

    @Override
    public void start() {
        bossGroup = new NioEventLoopGroup(1, r -> {
            Thread t = new Thread(r, "cwmp-netty-boss");
            t.setDaemon(true);
            return t;
        });
        workerGroup = new NioEventLoopGroup(0, r -> {
            Thread t = new Thread(r, "cwmp-netty-worker");
            t.setDaemon(true);
            return t;
        });
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("codec", new HttpServerCodec())
                                // 报文体积硬上限：超出聚合器直接回 413，读不全的大报文不进业务层
                                .addLast("aggregator", new HttpObjectAggregator(props.getMaxBodyBytes() + 8192))
                                .addLast("cwmp", handler);
                    }
                });
        try {
            for (int port : props.getPorts()) {
                servers.add(bootstrap.bind(port).sync().channel());
            }
            running = true;
            Logs.info(Logs.Module.CPE, "Netty CWMP 接入层就绪", "端口", props.getPorts(),
                    "报文上限(字节)", props.getMaxBodyBytes());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Netty CWMP 端口绑定被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("Netty CWMP 端口绑定失败 " + props.getPorts(), e);
        }
    }

    @Override
    public void stop() {
        running = false;
        for (Channel c : servers) {
            c.close();
        }
        servers.clear();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        Logs.info(Logs.Module.CPE, "Netty CWMP 接入层已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

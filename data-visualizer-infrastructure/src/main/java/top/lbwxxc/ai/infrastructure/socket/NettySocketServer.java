package top.lbwxxc.ai.infrastructure.socket;

import com.alibaba.fastjson.JSON;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.model.entity.GatewayCommandEntity;
import top.lbwxxc.ai.domain.agent.model.valobj.GatewayResponseVO;
import top.lbwxxc.ai.types.enums.ResponseCode;
import top.lbwxxc.ai.types.exception.AppException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Netty Socket 服务端
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/2/13
 */
@Slf4j
@Service
public class NettySocketServer {

    @Value("${gateway.socket.port}")
    private int port;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    // 存储连接的 Channel，这里简单起见只存储最近的一个或者用 Map 存储
    private Channel clientChannel;
    private final Map<String, Channel> clientMap = new ConcurrentHashMap<>();

    // 存储待响应的 Future: Map<requestId, Future>
    private final Map<String, CompletableFuture<GatewayResponseVO>> pendingResponses = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() {
        new Thread(() -> {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
            try {
                ServerBootstrap b = new ServerBootstrap();
                b.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            public void initChannel(SocketChannel ch) {
                                ChannelPipeline p = ch.pipeline();
                                // 基于换行符的解码器，解决粘包拆包问题
                                p.addLast(new LineBasedFrameDecoder(1024 * 1024 * 5)); // 5MB max frame (for screenshots)
                                p.addLast(new StringDecoder(StandardCharsets.UTF_8));
                                p.addLast(new StringEncoder(StandardCharsets.UTF_8));
                                p.addLast(new NettyServerHandler());
                            }
                        })
                        .option(ChannelOption.SO_BACKLOG, 128)
                        .childOption(ChannelOption.SO_KEEPALIVE, true);

                ChannelFuture f = b.bind(port).sync();
                log.info("Netty Socket Server started on port: {}", port);
                serverChannel = f.channel();
                serverChannel.closeFuture().sync();
            } catch (Exception e) {
                log.error("Netty Server start error", e);
            } finally {
                workerGroup.shutdownGracefully();
                bossGroup.shutdownGracefully();
            }
        }).start();
    }

    @PreDestroy
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    /**
     * 发送指令并等待响应
     *
     * @param command 指令对象
     * @return 响应结果
     */
    public GatewayResponseVO sendCommand(GatewayCommandEntity command) {
        Channel channel = clientMap.get(command.getHostString());

        if (channel == null || !channel.isActive()) {
            throw new AppException(ResponseCode.E0003);
        }

        CompletableFuture<GatewayResponseVO> future = new CompletableFuture<>();
        pendingResponses.put(command.getId(), future);

        try {
            String json = JSON.toJSONString(command);
            // 发送数据，注意要带上换行符，因为客户端（或者解码器）可能依赖它
            channel.writeAndFlush(json + "\n");
            log.info("Sent command request: id={}, host={}", command.getId(), command.getHostString());

            // 等待响应，设置超时时间，例如 30 秒
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingResponses.remove(command.getId());
            throw new RuntimeException("Command execution failed or timed out", e);
        }
    }

    public GatewayResponseVO queryClients() {
        return GatewayResponseVO.builder()
                .id("")
                .message(JSON.toJSONString(clientMap))
                .status("success")
                .build();
    }

    /**
     * Netty Handler
     */
    private class NettyServerHandler extends SimpleChannelInboundHandler<String> {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            InetSocketAddress socketAddress = (InetSocketAddress) channel.remoteAddress();
            String hostString = socketAddress.getHostString();

            clientMap.put(hostString, channel);

            log.info("Client connected: {}", hostString);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            InetSocketAddress socketAddress = (InetSocketAddress) channel.remoteAddress();
            String hostString = socketAddress.getHostString();

            if (clientMap.containsKey(hostString)) {
                clientMap.get(hostString).close();
                clientMap.remove(hostString);
            }

            log.info("Client disconnected: {}", hostString);

        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            if (log.isDebugEnabled()) {
                log.debug("Received message: {}", msg);
            }
            try {
                GatewayResponseVO response = JSON.parseObject(msg, GatewayResponseVO.class);
                if (response != null && response.getId() != null) {
                    CompletableFuture<GatewayResponseVO> future = pendingResponses.remove(response.getId());
                    if (future != null) {
                        future.complete(response);
                    } else {
                        log.warn("Received response for unknown or expired request ID: {}", response.getId());
                    }
                }
            } catch (Exception e) {
                log.error("Error parsing response", e);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Connection error", cause);
            ctx.close();
        }
    }
}

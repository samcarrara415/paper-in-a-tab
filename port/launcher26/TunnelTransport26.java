import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;

/**
 * In-JVM tunnel listener for the 26.2 port — the modern sibling of the 1.8.8
 * TunnelTransport. Relay clients arrive as (id, bytes); each becomes a
 * LocalChannel pair whose server side gets vanilla's own serialization
 * pipeline via Connection.configureSerialization, so the server sees a
 * completely ordinary remote player.
 */
public class TunnelTransport26 {

    static final LocalAddress ADDRESS = new LocalAddress("paper-in-a-tab-26");

    static boolean started;
    static EventLoopGroup group;
    static Channel serverChannel;
    static final Map<Long, Channel> conns = new HashMap<Long, Channel>();
    static final Map<Long, List<byte[]>> preActive = new HashMap<Long, List<byte[]>>();

    static synchronized void ensureStarted(final MinecraftServer server) throws Exception {
        if (started) {
            return;
        }
        final ServerConnectionListener listener = server.getConnection();
        final List<Connection> pending = connectionsList(listener);
        group = new DefaultEventLoopGroup(2);

        ServerBootstrap boot = new ServerBootstrap()
                .group(group)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    protected void initChannel(Channel ch) {
                        final Connection conn = new Connection(PacketFlow.SERVERBOUND);
                        // CraftBukkit's login flow casts the connection address
                        // to InetSocketAddress; LocalChannels report a netty
                        // LocalAddress, so swap in loopback after Connection
                        // captures it in channelActive.
                        ch.pipeline().addLast("addr_fix", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                ctx.fireChannelActive();
                                for (Field f : Connection.class.getDeclaredFields()) {
                                    if (f.getType().equals(java.net.SocketAddress.class)) {
                                        f.setAccessible(true);
                                        f.set(conn, new java.net.InetSocketAddress(
                                                java.net.InetAddress.getLoopbackAddress(), 0));
                                    }
                                }
                            }
                        });
                        Connection.configureSerialization(ch.pipeline(), PacketFlow.SERVERBOUND, false, null);
                        ch.pipeline().addLast("packet_handler", conn);
                        conn.setListenerForServerboundHandshake(
                                new ServerHandshakePacketListenerImpl(server, conn));
                        synchronized (pending) {
                            pending.add(conn);
                        }
                    }
                });
        serverChannel = boot.bind(ADDRESS).syncUninterruptibly().channel();
        started = true;
        BrowserLauncher26.send("[tunnel] in-tab listener up; relaying real clients into this server");
    }

    static List<Connection> connectionsList(ServerConnectionListener listener) throws Exception {
        for (Field f : ServerConnectionListener.class.getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType())
                    && f.getGenericType().toString().contains("Connection")) {
                f.setAccessible(true);
                return (List<Connection>) f.get(listener);
            }
        }
        throw new IllegalStateException("connections list not found");
    }

    /** {"open":[..],"data":[{"id","b64"}..],"close":[..]} from the page. */
    static void handleBatch(MinecraftServer server, String json) {
        try {
            ensureStarted(server);
            // reuse the launcher's tiny JSON reader style: values are simple
            long[] none = new long[0];
            for (long id : idArray(json, "open")) open(id);
            for (String[] d : dataArray(json)) write(Long.parseLong(d[0]),
                    java.util.Base64.getDecoder().decode(d[1]));
            for (long id : idArray(json, "close")) close(id);
        } catch (Throwable t) {
            BrowserLauncher26.send("[tunnel] batch error: " + t);
        }
    }

    // -- minimal parsing for the fixed batch shape (no gson in the modern jar's
    //    public namespace); the page emits compact JSON with no nesting tricks.

    static long[] idArray(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return new long[0];
        int a = json.indexOf('[', k), b = json.indexOf(']', a);
        String body = json.substring(a + 1, b).trim();
        if (body.isEmpty()) return new long[0];
        String[] parts = body.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Long.parseLong(parts[i].trim());
        return out;
    }

    static List<String[]> dataArray(String json) {
        List<String[]> out = new ArrayList<String[]>();
        int k = json.indexOf("\"data\"");
        if (k < 0) return out;
        int i = json.indexOf('[', k);
        while (true) {
            int obj = json.indexOf('{', i);
            int end = json.indexOf('}', obj);
            if (obj < 0 || end < 0) break;
            String o = json.substring(obj, end + 1);
            int idK = o.indexOf("\"id\"");
            int idColon = o.indexOf(':', idK);
            int idEnd = idColon + 1;
            while (idEnd < o.length() && (Character.isDigit(o.charAt(idEnd)) || Character.isWhitespace(o.charAt(idEnd)))) idEnd++;
            String id = o.substring(idColon + 1, idEnd).trim();
            int bK = o.indexOf("\"b64\"");
            int q1 = o.indexOf('"', o.indexOf(':', bK) + 1);
            int q2 = o.indexOf('"', q1 + 1);
            out.add(new String[]{id, o.substring(q1 + 1, q2)});
            i = end + 1;
            if (i >= json.length() || json.charAt(i) == ']') break;
        }
        return out;
    }

    static synchronized void open(final long id) {
        if (conns.containsKey(id)) {
            return;
        }
        preActive.put(id, new ArrayList<byte[]>());
        Bootstrap boot = new Bootstrap()
                .group(group)
                .channel(LocalChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                flushPreActive(id, ctx.channel());
                            }
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) msg;
                                byte[] out = new byte[buf.readableBytes()];
                                buf.readBytes(out);
                                buf.release();
                                BrowserLauncher26.tunnelOut("{\"id\":" + id + ",\"b64\":\""
                                        + java.util.Base64.getEncoder().encodeToString(out) + "\"}");
                            }
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                dropped(id);
                            }
                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                ctx.close();
                            }
                        });
                    }
                });
        boot.connect(ADDRESS).addListener(new io.netty.channel.ChannelFutureListener() {
            public void operationComplete(io.netty.channel.ChannelFuture f) {
                if (f.isSuccess()) {
                    synchronized (TunnelTransport26.class) {
                        conns.put(id, f.channel());
                    }
                } else {
                    dropped(id);
                }
            }
        });
    }

    static synchronized void flushPreActive(long id, Channel ch) {
        conns.put(id, ch);
        List<byte[]> buffered = preActive.remove(id);
        if (buffered != null) {
            for (byte[] b : buffered) {
                ch.writeAndFlush(Unpooled.wrappedBuffer(b));
            }
        }
    }

    static synchronized void write(long id, byte[] data) {
        Channel ch = conns.get(id);
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.wrappedBuffer(data));
            return;
        }
        List<byte[]> buffered = preActive.get(id);
        if (buffered != null) {
            buffered.add(data);
        }
    }

    static synchronized void close(long id) {
        preActive.remove(id);
        Channel ch = conns.remove(id);
        if (ch != null) {
            ch.close();
        }
    }

    static void dropped(long id) {
        boolean known;
        synchronized (TunnelTransport26.class) {
            known = conns.remove(id) != null || preActive.remove(id) != null;
        }
        if (known) {
            BrowserLauncher26.tunnelOut("{\"close\":" + id + "}");
        }
    }
}

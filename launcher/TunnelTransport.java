import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.v1_8_R3.EnumProtocolDirection;
import net.minecraft.server.v1_8_R3.HandshakeListener;
import net.minecraft.server.v1_8_R3.LegacyPingHandler;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.NetworkManager;
import net.minecraft.server.v1_8_R3.PacketDecoder;
import net.minecraft.server.v1_8_R3.PacketEncoder;
import net.minecraft.server.v1_8_R3.PacketPrepender;
import net.minecraft.server.v1_8_R3.PacketSplitter;
import net.minecraft.server.v1_8_R3.ServerConnection;

/**
 * The in-JVM half of the tunnel. The page relays real Minecraft TCP clients
 * as (id, bytes) events; each id becomes a netty LocalChannel pair whose
 * server side wears the exact vanilla network pipeline, so the server sees an
 * ordinary player connection. The TCP bind stays patched out — this listener
 * lives entirely inside the JVM.
 */
public class TunnelTransport {

    static final Gson GSON = new Gson();
    static final LocalAddress ADDRESS = new LocalAddress("paper-in-a-tab");

    static boolean started;
    static Channel serverChannel;
    static final Map<Long, Channel> conns = new HashMap<Long, Channel>();
    static final Map<Long, List<byte[]>> preActive = new HashMap<Long, List<byte[]>>();

    /** Binds the in-JVM listener with the vanilla pipeline. Idempotent. */
    static synchronized void ensureStarted(final MinecraftServer server) throws Exception {
        if (started) {
            return;
        }
        final ServerConnection serverConnection = server.getServerConnection();
        final List<NetworkManager> pending = pendingList(serverConnection);

        ServerBootstrap boot = new ServerBootstrap()
                .group(NetworkManager.f.c())
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    protected void initChannel(Channel ch) {
                        final NetworkManager nm = new NetworkManager(EnumProtocolDirection.SERVERBOUND);
                        ch.pipeline()
                          // CraftBukkit casts the connection address to
                          // InetSocketAddress in the login flow; a LocalChannel
                          // reports a LocalAddress, so swap in a loopback one
                          // right after NetworkManager captures it.
                          .addLast("addr_fix", new ChannelInboundHandlerAdapter() {
                              @Override
                              public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                  ctx.fireChannelActive();
                                  for (Field f : NetworkManager.class.getDeclaredFields()) {
                                      if (f.getType().equals(java.net.SocketAddress.class)) {
                                          f.setAccessible(true);
                                          f.set(nm, new java.net.InetSocketAddress(
                                                  java.net.InetAddress.getLoopbackAddress(), 0));
                                      }
                                  }
                              }
                          })
                          .addLast("legacy_query", new LegacyPingHandler(serverConnection))
                          .addLast("splitter", new PacketSplitter())
                          .addLast("decoder", new PacketDecoder(EnumProtocolDirection.SERVERBOUND))
                          .addLast("prepender", new PacketPrepender())
                          .addLast("encoder", new PacketEncoder(EnumProtocolDirection.CLIENTBOUND))
                          .addLast("packet_handler", nm);
                        nm.a(new HandshakeListener(server, nm));
                        synchronized (pending) {
                            pending.add(nm);
                        }
                    }
                });
        serverChannel = boot.bind(ADDRESS).syncUninterruptibly().channel();
        started = true;
        BrowserLauncher.send("[tunnel] in-tab listener up; relaying real clients into this server");
    }

    /** The ServerConnection's list of connections awaiting their first tick. */
    static List<NetworkManager> pendingList(ServerConnection sc) throws Exception {
        for (Field f : ServerConnection.class.getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object v = f.get(sc);
                // two Lists exist: ChannelFuture list (g) and NetworkManager list (h);
                // an empty list is ambiguous, so pick by generic name order: 'h' is
                // the NetworkManager one in 1.8.8.
                if (f.getName().equals("h")) {
                    return (List<NetworkManager>) v;
                }
            }
        }
        throw new IllegalStateException("pending-connections list not found");
    }

    /** Handles one poll batch from the page: {"open":[..],"data":[{"id","b64"}..],"close":[..]} */
    static void handleBatch(MinecraftServer server, String json) {
        try {
            ensureStarted(server);
            JsonObject batch = new JsonParser().parse(json).getAsJsonObject();
            if (batch.has("open")) {
                for (JsonElement e : batch.getAsJsonArray("open")) {
                    open(e.getAsLong());
                }
            }
            if (batch.has("data")) {
                for (JsonElement e : batch.getAsJsonArray("data")) {
                    JsonObject d = e.getAsJsonObject();
                    write(d.get("id").getAsLong(),
                          java.util.Base64.getDecoder().decode(d.get("b64").getAsString()));
                }
            }
            if (batch.has("close")) {
                for (JsonElement e : batch.getAsJsonArray("close")) {
                    close(e.getAsLong());
                }
            }
        } catch (Throwable t) {
            BrowserLauncher.send("[tunnel] batch error: " + t);
        }
    }

    static synchronized void open(final long id) {
        if (conns.containsKey(id)) {
            return;
        }
        preActive.put(id, new ArrayList<byte[]>());
        Bootstrap boot = new Bootstrap()
                .group(NetworkManager.f.c())
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
                                JsonObject o = new JsonObject();
                                o.addProperty("id", id);
                                o.addProperty("b64", java.util.Base64.getEncoder().encodeToString(out));
                                BrowserLauncher.tunnelOut(GSON.toJson(o));
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
                    synchronized (TunnelTransport.class) {
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

    /** Local side went down: tell the page so the relay can drop the client. */
    static void dropped(long id) {
        boolean known;
        synchronized (TunnelTransport.class) {
            known = conns.remove(id) != null || preActive.remove(id) != null;
        }
        if (known) {
            JsonObject o = new JsonObject();
            o.addProperty("close", id);
            BrowserLauncher.tunnelOut(GSON.toJson(o));
        }
    }
}

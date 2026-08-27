import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;

import net.minecraft.server.v1_8_R3.DedicatedServer;
import net.minecraft.server.v1_8_R3.MinecraftServer;

/**
 * Entry point CheerpJ runs instead of the stock Main. It boots Paper on a
 * secondary thread and pumps two JS-fed queues: console commands and file
 * manager operations. All four native methods are implemented in JavaScript
 * on the page (see app.js). Run with -Dbrowser.natives=false to test the
 * whole thing on a real JDK 8 using stdin/stdout instead of the JS bridge.
 */
public class BrowserLauncher {

    public static native void emitLine(String line);
    public static native String pollCommand();
    public static native String pollOp();
    public static native void opResult(String json);
    // tunnel bridge: poll returns a batch of relay events (null = no tunnel
    // session, "{}" = connected but idle); tunnelOut pushes bytes/closes back.
    public static native String tunnelPoll();
    public static native void tunnelOut(String json);

    static final boolean NATIVES = !"false".equals(System.getProperty("browser.natives", "true"));
    static PrintStream realOut;
    static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        realOut = System.out;
        writeDefaultConfigs();
        hookLog4j();
        // Under CheerpJ, log4j resolves a second logger context for the
        // server's classes and those lines never reach the appender above, so
        // tee stdout/stderr as well; the page dedupes the overlap.
        if (NATIVES) {
            System.setOut(new PrintStream(new LineTee(realOut), true, "UTF-8"));
            System.setErr(new PrintStream(new LineTee(System.err), true, "UTF-8"));
        }

        Thread boot = new Thread(new Runnable() {
            public void run() {
                try {
                    org.bukkit.craftbukkit.Main.main(new String[]{"--nojline", "nogui"});
                } catch (Throwable t) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    send("[launcher] server thread died: " + sw);
                }
            }
        }, "ServerBootstrap");
        boot.setDaemon(false);
        boot.start();

        BufferedReader stdin = NATIVES ? null : new BufferedReader(new InputStreamReader(System.in));
        boolean tunnelArmed = true;
        while (true) {
            String cmd = NATIVES ? pollCommand() : (stdin.ready() ? stdin.readLine() : null);
            if (cmd != null && !cmd.trim().isEmpty()) {
                MinecraftServer srv = MinecraftServer.getServer();
                if (srv instanceof DedicatedServer && srv.isRunning()) {
                    ((DedicatedServer) srv).issueCommand(cmd.trim(), srv);
                } else {
                    send("[launcher] server is not ready for commands yet");
                }
            }
            if (NATIVES) {
                String op = pollOp();
                if (op != null) handleOp(op);
            }
            boolean tunnelActive = false;
            if (NATIVES && tunnelArmed) {
                try {
                    String batch = tunnelPoll();
                    if (batch != null) {
                        tunnelActive = true;
                        MinecraftServer srv2 = MinecraftServer.getServer();
                        if (srv2 != null && srv2.isRunning() && !"{}".equals(batch)) {
                            TunnelTransport.handleBatch(srv2, batch);
                        }
                    }
                } catch (UnsatisfiedLinkError e) {
                    // page code predates the tunnel natives (stale cache) —
                    // run without the tunnel instead of dying.
                    tunnelArmed = false;
                    send("[launcher] tunnel bridge unavailable on this page version; refresh for tunnel support");
                }
            }
            // a live tunnel needs low-latency pumping; idle pages don't
            Thread.sleep(tunnelActive ? 50 : 250);
        }
    }

    static void send(String line) {
        if (NATIVES) emitLine(line);
        else realOut.println("[CAP] " + line);
    }

    static void sendResult(String json) {
        if (NATIVES) opResult(json);
        else realOut.println("[OP] " + json);
    }

    // ---- console capture -------------------------------------------------

    static class LineTee extends OutputStream {
        final OutputStream through;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        LineTee(OutputStream through) { this.through = through; }
        public synchronized void write(int b) throws IOException {
            through.write(b);
            if (b == '\n') {
                String line = buf.toString("UTF-8");
                buf.reset();
                if (!line.trim().isEmpty()) emitLine(line);
            } else if (b != '\r') {
                buf.write(b);
            }
        }
    }

    static void hookLog4j() {
        try {
            final org.apache.logging.log4j.core.Logger root =
                    (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger();
            org.apache.logging.log4j.core.appender.AbstractAppender app =
                    new org.apache.logging.log4j.core.appender.AbstractAppender("browser", null, null) {
                        final java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss");
                        public void append(org.apache.logging.log4j.core.LogEvent e) {
                            try {
                                StringBuilder sb = new StringBuilder();
                                sb.append('[').append(fmt.format(new java.util.Date(e.getTimeMillis())))
                                  .append(' ').append(e.getLevel()).append("]: ")
                                  .append(e.getMessage().getFormattedMessage());
                                Throwable t = e.getThrown();
                                if (t != null) {
                                    StringWriter sw = new StringWriter();
                                    t.printStackTrace(new PrintWriter(sw));
                                    sb.append('\n').append(sw);
                                }
                                send(sb.toString());
                            } catch (Throwable ignored) {}
                        }
                    };
            app.start();
            root.addAppender(app);
        } catch (Throwable t) {
            send("[launcher] log hook failed, console will be quiet: " + t);
        }
    }

    // ---- first-boot configuration ---------------------------------------

    // Bump this whenever the tuned defaults below change: existing browsers
    // carry their configs in IndexedDB, and a bump overwrites them with the
    // new profile on next boot (a marker file tracks the applied version).
    static final int CONFIG_VERSION = 2;

    static void writeDefaultConfigs() throws IOException {
        boolean upgrade = readConfigVersion() < CONFIG_VERSION;
        writeConfig("eula.txt", "eula=true\n", upgrade);

        // max-tick-time=-1 disables the vanilla single-tick watchdog: a phone
        // GC pause or backgrounded tab can stall one tick past 60s, and the
        // default kills the whole server for it.
        writeConfig("server.properties",
                "level-type=FLAT\n" +
                "online-mode=false\n" +
                "allow-nether=false\n" +
                "view-distance=3\n" +
                "max-tick-time=-1\n" +
                "snooper-enabled=false\n" +
                "max-players=5\n" +
                "spawn-protection=0\n" +
                "motd=Paper in a tab (AI remake)\n", upgrade);

        // Fewer entities to tick, spawn chunks allowed to unload when idle,
        // more frequent chunk GC to keep the tab's memory down, and autosave
        // spread out so world saves don't stutter the tick loop as often.
        writeConfig("bukkit.yml",
                "settings:\n" +
                "  allow-end: false\n" +
                "  shutdown-message: Server closed\n" +
                "spawn-limits:\n" +
                "  monsters: 20\n" +
                "  animals: 5\n" +
                "  water-animals: 2\n" +
                "  ambient: 1\n" +
                "ticks-per:\n" +
                "  animal-spawns: 400\n" +
                "  monster-spawns: 4\n" +
                "  autosave: 3000\n" +
                "chunk-gc:\n" +
                "  period-in-ticks: 300\n" +
                "  load-threshold: 120\n", upgrade);

        // timeout-time keeps Spigot's watchdog patient with slow browser
        // ticks; the activation/tracking ranges and per-tick time budgets cap
        // how much entity and tile work a single tick is allowed to do.
        writeConfig("spigot.yml",
                "settings:\n" +
                "  timeout-time: 600\n" +
                "  restart-on-crash: false\n" +
                "  save-user-cache-on-stop-only: true\n" +
                "world-settings:\n" +
                "  default:\n" +
                "    mob-spawn-range: 2\n" +
                "    entity-activation-range:\n" +
                "      animals: 8\n" +
                "      monsters: 12\n" +
                "      misc: 2\n" +
                "    entity-tracking-range:\n" +
                "      players: 32\n" +
                "      animals: 24\n" +
                "      monsters: 24\n" +
                "      misc: 16\n" +
                "      other: 32\n" +
                "    max-tick-time:\n" +
                "      tile: 30\n" +
                "      entity: 30\n" +
                "    ticks-per:\n" +
                "      hopper-transfer: 8\n" +
                "      hopper-check: 8\n", upgrade);

        // Paper's own levers: cheaper explosions, no weather tick work, and —
        // the big one — spawn chunks may unload, so an idle server ticks
        // almost nothing and the tab stays responsive.
        writeConfig("paper.yml",
                "world-settings:\n" +
                "  default:\n" +
                "    keep-spawn-loaded: false\n" +
                "    optimize-explosions: true\n" +
                "    disable-thunder: true\n" +
                "    disable-ice-and-snow: true\n" +
                "    mob-spawner-tick-rate: 2\n" +
                "    max-entity-collisions: 2\n", upgrade);

        // No usage pings to mcstats.org from inside a demo tab.
        new File("plugins/PluginMetrics").mkdirs();
        writeConfig("plugins/PluginMetrics/config.yml",
                "opt-out: true\nguid: 00000000-0000-0000-0000-000000000000\ndebug: false\n", upgrade);

        if (upgrade) {
            Writer w = new OutputStreamWriter(new FileOutputStream(".pit-config-version"), StandardCharsets.UTF_8);
            w.write(String.valueOf(CONFIG_VERSION));
            w.close();
            send("[launcher] applied browser performance profile v" + CONFIG_VERSION);
        }
    }

    static int readConfigVersion() {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(".pit-config-version"), StandardCharsets.UTF_8));
            String s = r.readLine();
            r.close();
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    static void writeConfig(String name, String content, boolean force) throws IOException {
        File f = new File(name);
        if (force || !f.exists()) {
            Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
            w.write(content);
            w.close();
        }
    }

    // ---- file manager ops ------------------------------------------------

    static void handleOp(String json) {
        JsonObject req = new JsonParser().parse(json).getAsJsonObject();
        long id = req.get("id").getAsLong();
        String op = req.get("op").getAsString();
        JsonObject res = new JsonObject();
        res.addProperty("id", id);
        try {
            File root = new File(".").getCanonicalFile();
            if ("list".equals(op)) {
                File dir = resolve(root, req.get("path").getAsString());
                JsonArray arr = new JsonArray();
                File[] kids = dir.listFiles();
                if (kids != null) for (File k : kids) {
                    JsonObject e = new JsonObject();
                    e.addProperty("name", k.getName());
                    e.addProperty("dir", k.isDirectory());
                    e.addProperty("size", k.isDirectory() ? 0 : k.length());
                    arr.add(e);
                }
                res.add("entries", arr);
            } else if ("read".equals(op)) {
                File f = resolve(root, req.get("path").getAsString());
                long cap = 20L * 1024 * 1024;
                if (f.length() > cap) throw new IOException("file too large");
                res.addProperty("b64", java.util.Base64.getEncoder().encodeToString(readAll(f)));
            } else if ("write".equals(op)) {
                File f = resolve(root, req.get("path").getAsString());
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                byte[] data = java.util.Base64.getDecoder().decode(req.get("b64").getAsString());
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(data);
                fo.close();
            } else if ("mkdir".equals(op)) {
                resolve(root, req.get("path").getAsString()).mkdirs();
            } else if ("delete".equals(op)) {
                deleteRec(resolve(root, req.get("path").getAsString()));
            } else if ("export".equals(op)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(bos);
                zipDir(root, root, zos);
                zos.close();
                res.addProperty("b64", java.util.Base64.getEncoder().encodeToString(bos.toByteArray()));
            } else if ("stop".equals(op)) {
                MinecraftServer srv = MinecraftServer.getServer();
                if (srv != null) srv.safeShutdown();
            } else {
                throw new IOException("unknown op " + op);
            }
            res.addProperty("ok", true);
        } catch (Throwable t) {
            res.addProperty("ok", false);
            res.addProperty("error", String.valueOf(t.getMessage() == null ? t : t.getMessage()));
        }
        sendResult(GSON.toJson(res));
    }

    static File resolve(File root, String rel) throws IOException {
        File f = new File(root, rel).getCanonicalFile();
        if (!f.getPath().startsWith(root.getPath())) throw new IOException("path escapes root");
        return f;
    }

    static byte[] readAll(File f) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FileInputStream is = new FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }

    static void zipDir(File root, File dir, ZipOutputStream zos) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String rel = root.toURI().relativize(k.toURI()).getPath();
            if (k.isDirectory()) {
                zipDir(root, k, zos);
            } else {
                zos.putNextEntry(new ZipEntry(rel));
                zos.write(readAll(k));
                zos.closeEntry();
            }
        }
    }
}

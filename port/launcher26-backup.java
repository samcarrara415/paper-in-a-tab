import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;

/**
 * Browser entry point for the Paper 26.2 port — same four-native bridge as
 * the 1.8.8 launcher, but against Mojang-mapped modern internals. JSON for
 * the file-manager ops is hand-rolled here because the modern jar relocates
 * gson. Run with -Dbrowser.natives=false to drive it from stdin on a JDK 8.
 */
public class BrowserLauncher26 {

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

    public static void main(String[] args) throws Exception {
        realOut = System.out;
        // The 26.2 port lives in its own directory so the 1.8.8 world and
        // configs in /files are untouched.
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(System.getProperty("user.dir", ".")));
        } catch (Throwable t) {
            send("[launcher] could not create working dir: " + t);
        }
        // A boot that crashed during world creation leaves a world dir with
        // no level.dat; the next boot would die on the half-written files.
        // NOTE: CheerpJ's java.io.File cannot see NIO-created entries, and the
        // modern server is NIO throughout — every file operation in this
        // launcher must therefore use java.nio.
        java.nio.file.Path world = java.nio.file.Paths.get("world").toAbsolutePath();
        if (java.nio.file.Files.isDirectory(world) && !worldLooksUsable(world)) {
            send("[launcher] removing broken world left by a crashed boot");
            deleteRecNio(world);
        }
        try {
            writeDefaultConfigs();
        } catch (Throwable t) {
            send("[launcher] config setup failed: " + t);
        }
        send("[launcher] cwd io=" + new File(".").getAbsolutePath()
                + " nio=" + java.nio.file.Paths.get("").toAbsolutePath());
        String rel = "./world/dimensions/minecraft/overworld/data/minecraft/world_gen_settings.dat";
        if (new File(rel).exists()) {
            try {
                InputStream in = java.nio.file.Files.newInputStream(java.nio.file.Paths.get(rel));
                int n = 0;
                while (in.read() >= 0) n++;
                in.close();
                send("[launcher] rel-path nio read ok, bytes=" + n);
            } catch (Throwable t) {
                send("[launcher] rel-path nio read FAILED: " + t);
            }
        }
        hookLog4j();
        if (NATIVES) {
            System.setOut(new PrintStream(new LineTee(realOut), true, "UTF-8"));
            System.setErr(new PrintStream(new LineTee(System.err), true, "UTF-8"));
        }

        Thread boot = new Thread(new Runnable() {
            public void run() {
                try {
                    org.bukkit.craftbukkit.Main.main(new String[]{"nogui", "--noconsole",
                            "--universe", System.getProperty("user.dir", ".")});
                } catch (Throwable t) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    send("[launcher] server thread died: " + sw);
                }
            }
        }, "ServerBootstrap");
        boot.start();

        BufferedReader stdin = NATIVES ? null : new BufferedReader(new InputStreamReader(System.in));
        boolean hooked = false;
        // Safari's scheduler starves background threads while the server
        // thread is busy, so once the server exists the bridge is pumped from
        // inside the tick loop itself (addTickable). This thread only
        // bootstraps the hook and then acts as a slow fallback.
        while (true) {
            MinecraftServer srv = MinecraftServer.getServer();
            if (!hooked && srv instanceof DedicatedServer && srv.isRunning()) {
                final DedicatedServer ds = (DedicatedServer) srv;
                ds.addTickable(new Runnable() {
                    public void run() {
                        pump(ds);
                    }
                });
                ds.handleConsoleInput("gamerule spawnChunkRadius 0", ds.createCommandSourceStack());
                send("[launcher] bridge pump attached to the tick loop");
                hooked = true;
            }
            if (!NATIVES) {
                String cmd = stdin.ready() ? stdin.readLine() : null;
                if (cmd != null && !cmd.trim().isEmpty() && srv instanceof DedicatedServer && srv.isRunning()) {
                    ((DedicatedServer) srv).handleConsoleInput(cmd.trim(), srv.createCommandSourceStack());
                }
            } else if (!hooked) {
                // pre-ready: answer file ops so the page does not time out
                String op = pollOp();
                if (op != null) handleOp(op);
                String cmd = pollCommand();
                if (cmd != null && !cmd.trim().isEmpty()) {
                    send("[launcher] server is not ready for commands yet");
                }
            }
            Thread.sleep(hooked ? 5000 : 500);
        }
    }

    /** Runs on the server thread, every tick. */
    static void pump(DedicatedServer ds) {
        try {
            String cmd;
            while ((cmd = pollCommand()) != null) {
                if (!cmd.trim().isEmpty()) {
                    ds.handleConsoleInput(cmd.trim(), ds.createCommandSourceStack());
                }
            }
            String op = pollOp();
            if (op != null) {
                handleOp(op);
            }
            String batch = tunnelPoll();
            if (batch != null && !"{}".equals(batch)) {
                TunnelTransport26.handleBatch(ds, batch);
            }
        } catch (Throwable t) {
            send("[launcher] pump error: " + t);
        }
    }

    static void send(String line) {
        if (NATIVES) emitLine(line);
        else realOut.println("[CAP] " + line);
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
                    new org.apache.logging.log4j.core.appender.AbstractAppender(
                            "browser", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
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

    static final int CONFIG_VERSION = 4;

    static void writeDefaultConfigs() throws IOException {
        boolean upgrade = readConfigVersion() < CONFIG_VERSION;
        // Always rewritten: a failed load makes the server clobber it with
        // eula=false, and that must never survive into the next boot.
        writeConfig("eula.txt", "eula=true\n", true);
        writeConfig("server.properties",
                "level-type=minecraft\\:flat\n" +
                "online-mode=false\n" +
                "allow-nether=false\n" +
                "view-distance=2\n" +
                "simulation-distance=2\n" +
                "max-tick-time=-1\n" +
                // per-chunk fsync over IndexedDB is brutal; batched writes only
                "sync-chunk-writes=false\n" +
                "max-players=5\n" +
                "spawn-protection=0\n" +
                "motd=Paper 26.2 in a tab (AI port)\n", upgrade);
        writeConfig("bukkit.yml",
                "settings:\n  allow-end: false\n  shutdown-message: Server closed\n" +
                "spawn-limits:\n  monsters: 15\n  animals: 4\n  water-animals: 2\n  ambient: 1\n" +
                "ticks-per:\n  animal-spawns: 400\n  monster-spawns: 4\n  autosave: 3000\n" +
                "chunk-gc:\n  period-in-ticks: 300\n", upgrade);
        writeConfig("spigot.yml",
                "settings:\n  timeout-time: 600\n  restart-on-crash: false\n" +
                "world-settings:\n  default:\n" +
                "    mob-spawn-range: 2\n" +
                "    entity-activation-range:\n      animals: 8\n      monsters: 12\n      raiders: 16\n      misc: 2\n" +
                "    entity-tracking-range:\n      players: 32\n      animals: 24\n      monsters: 24\n      misc: 16\n      other: 32\n", upgrade);
        writeConfig("config/paper-world-defaults.yml",
                "chunks:\n  max-auto-save-chunks-per-tick: 8\n" +
                "collisions:\n  max-entity-collisions: 2\n" +
                "tick-rates:\n  mob-spawner: 2\n" +
                "misc:\n  redstone-implementation: ALTERNATE_CURRENT\n", upgrade);
        // spark's sampler NPE-loops without a real ThreadMXBean.
        java.nio.file.Files.createDirectories(java.nio.file.Paths.get("config").toAbsolutePath());
        writeConfig("config/paper-global.yml",
                "spark:\n  enabled: false\n  enable-immediately: false\n", upgrade);
        if (upgrade) {
            Writer w = new OutputStreamWriter(new FileOutputStream(".pit-config-version"), StandardCharsets.UTF_8);
            w.write(String.valueOf(CONFIG_VERSION));
            w.close();
            send("[launcher] applied 26.2 browser profile v" + CONFIG_VERSION);
        }
    }

    static int readConfigVersion() {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    java.nio.file.Files.newInputStream(java.nio.file.Paths.get(".pit-config-version").toAbsolutePath()), StandardCharsets.UTF_8));
            String s = r.readLine();
            r.close();
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    static void writeConfig(String name, String content, boolean force) throws IOException {
        File f = new File(name);
        if (!force && f.exists()) {
            return;
        }
        // CheerpJ quirk: FileOutputStream truncation of an existing IDB-backed
        // file can silently keep the old bytes. Delete + NIO write + verify.
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        java.nio.file.Path path = f.getAbsoluteFile().toPath();
        if (path.getParent() != null) {
            java.nio.file.Files.createDirectories(path.getParent());
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try { java.nio.file.Files.deleteIfExists(path); } catch (IOException ignored) {}
            java.nio.file.Files.write(path, bytes);
            try {
                if (java.util.Arrays.equals(java.nio.file.Files.readAllBytes(path), bytes)) return;
            } catch (IOException ignored) {}
        }
        send("[launcher] warning: " + name + " did not verify after write");
    }

    // ---- file manager ops (hand-rolled JSON) -----------------------------

    static String jstr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    static String jget(String json, String key) {
        // values are strings or numbers, objects are flat — good enough here
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int colon = json.indexOf(':', k);
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (json.charAt(i) == '"') {
            StringBuilder sb = new StringBuilder();
            i++;
            while (json.charAt(i) != '"') {
                if (json.charAt(i) == '\\') i++;
                sb.append(json.charAt(i++));
            }
            return sb.toString();
        }
        int e = i;
        while (e < json.length() && json.charAt(e) != ',' && json.charAt(e) != '}') e++;
        return json.substring(i, e).trim();
    }

    static void handleOp(String json) {
        long id = 0;
        StringBuilder res = new StringBuilder();
        try {
            id = Long.parseLong(jget(json, "id"));
            String op = jget(json, "op");
            java.nio.file.Path root = java.nio.file.Paths.get("").toAbsolutePath();
            res.append("{\"id\":").append(id);
            if ("list".equals(op)) {
                java.nio.file.Path dir = resolveNio(root, jget(json, "path"));
                res.append(",\"entries\":[");
                boolean first = true;
                if (java.nio.file.Files.isDirectory(dir)) {
                    java.nio.file.DirectoryStream<java.nio.file.Path> kids = java.nio.file.Files.newDirectoryStream(dir);
                    for (java.nio.file.Path k : kids) {
                        if (!first) res.append(',');
                        first = false;
                        boolean isDir = java.nio.file.Files.isDirectory(k);
                        long size = 0;
                        try { if (!isDir) size = java.nio.file.Files.size(k); } catch (IOException ignored) {}
                        res.append("{\"name\":").append(jstr(k.getFileName().toString()))
                           .append(",\"dir\":").append(isDir)
                           .append(",\"size\":").append(size).append('}');
                    }
                    kids.close();
                }
                res.append(']');
            } else if ("read".equals(op)) {
                java.nio.file.Path f = resolveNio(root, jget(json, "path"));
                if (java.nio.file.Files.size(f) > 20L * 1024 * 1024) throw new IOException("file too large");
                res.append(",\"b64\":").append(jstr(java.util.Base64.getEncoder()
                        .encodeToString(java.nio.file.Files.readAllBytes(f))));
            } else if ("write".equals(op)) {
                java.nio.file.Path f = resolveNio(root, jget(json, "path"));
                if (f.getParent() != null) java.nio.file.Files.createDirectories(f.getParent());
                java.nio.file.Files.deleteIfExists(f);
                java.nio.file.Files.write(f, java.util.Base64.getDecoder().decode(jget(json, "b64")));
            } else if ("mkdir".equals(op)) {
                java.nio.file.Files.createDirectories(resolveNio(root, jget(json, "path")));
            } else if ("delete".equals(op)) {
                deleteRecNio(resolveNio(root, jget(json, "path")));
            } else if ("export".equals(op)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(bos);
                zipDirNio(root, root, zos);
                zos.close();
                res.append(",\"b64\":").append(jstr(java.util.Base64.getEncoder().encodeToString(bos.toByteArray())));
            } else if ("stop".equals(op)) {
                MinecraftServer srv = MinecraftServer.getServer();
                if (srv != null) srv.halt(false);
            } else {
                throw new IOException("unknown op " + op);
            }
            res.append(",\"ok\":true}");
        } catch (Throwable t) {
            res.setLength(0);
            res.append("{\"id\":").append(id).append(",\"ok\":false,\"error\":")
               .append(jstr(String.valueOf(t.getMessage() == null ? t : t.getMessage()))).append('}');
        }
        if (NATIVES) opResult(res.toString());
        else realOut.println("[OP] " + res);
    }

    static java.nio.file.Path resolveNio(java.nio.file.Path root, String rel) throws IOException {
        while (rel.startsWith("/")) rel = rel.substring(1);
        java.nio.file.Path f = root.resolve(rel).normalize();
        if (!f.startsWith(root)) throw new IOException("path escapes root");
        return f;
    }

    static void zipDirNio(java.nio.file.Path root, java.nio.file.Path dir, ZipOutputStream zos) throws IOException {
        if (!java.nio.file.Files.isDirectory(dir)) return;
        java.nio.file.DirectoryStream<java.nio.file.Path> kids = java.nio.file.Files.newDirectoryStream(dir);
        for (java.nio.file.Path k : kids) {
            if (java.nio.file.Files.isDirectory(k)) {
                zipDirNio(root, k, zos);
            } else {
                zos.putNextEntry(new ZipEntry(root.relativize(k).toString()));
                zos.write(java.nio.file.Files.readAllBytes(k));
                zos.closeEntry();
            }
        }
        kids.close();
    }

    static boolean worldLooksUsable(java.nio.file.Path world) {
        if (!java.nio.file.Files.exists(world.resolve("level.dat"))) {
            return false;
        }
        // An unreadable, empty, or missing world_gen_settings.dat makes the
        // load die on "Overworld settings missing" — happens when a boot
        // crashed before the browser filesystem flushed.
        java.nio.file.Path wgs = world.resolve("dimensions/minecraft/overworld/data/minecraft/world_gen_settings.dat");
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(wgs);
            if (raw.length == 0) {
                return false;
            }
            InputStream in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(raw));
            byte[] buf = new byte[8192];
            while (in.read(buf) > 0) { /* drain */ }
            in.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static void deleteRecNio(java.nio.file.Path p) {
        try {
            if (java.nio.file.Files.isDirectory(p)) {
                java.nio.file.DirectoryStream<java.nio.file.Path> kids = java.nio.file.Files.newDirectoryStream(p);
                for (java.nio.file.Path k : kids) deleteRecNio(k);
                kids.close();
            }
            java.nio.file.Files.deleteIfExists(p);
        } catch (Throwable ignored) {}
    }

}

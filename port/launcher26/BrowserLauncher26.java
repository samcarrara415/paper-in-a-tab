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

    static final boolean NATIVES = !"false".equals(System.getProperty("browser.natives", "true"));
    static PrintStream realOut;

    public static void main(String[] args) throws Exception {
        realOut = System.out;
        // The 26.2 port lives in its own directory so the 1.8.8 world and
        // configs in /files are untouched.
        new File(System.getProperty("user.dir", ".")).mkdirs();
        writeDefaultConfigs();
        hookLog4j();
        if (NATIVES) {
            System.setOut(new PrintStream(new LineTee(realOut), true, "UTF-8"));
            System.setErr(new PrintStream(new LineTee(System.err), true, "UTF-8"));
        }

        Thread boot = new Thread(new Runnable() {
            public void run() {
                try {
                    org.bukkit.craftbukkit.Main.main(new String[]{"nogui", "--noconsole"});
                } catch (Throwable t) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    send("[launcher] server thread died: " + sw);
                }
            }
        }, "ServerBootstrap");
        boot.start();

        BufferedReader stdin = NATIVES ? null : new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            String cmd = NATIVES ? pollCommand() : (stdin.ready() ? stdin.readLine() : null);
            if (cmd != null && !cmd.trim().isEmpty()) {
                MinecraftServer srv = MinecraftServer.getServer();
                if (srv instanceof DedicatedServer && srv.isRunning()) {
                    ((DedicatedServer) srv).handleConsoleInput(cmd.trim(), srv.createCommandSourceStack());
                } else {
                    send("[launcher] server is not ready for commands yet");
                }
            }
            if (NATIVES) {
                String op = pollOp();
                if (op != null) handleOp(op);
            }
            Thread.sleep(250);
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

    static final int CONFIG_VERSION = 1;

    static void writeDefaultConfigs() throws IOException {
        boolean upgrade = readConfigVersion() < CONFIG_VERSION;
        writeConfig("eula.txt", "eula=true\n", upgrade);
        writeConfig("server.properties",
                "level-type=minecraft\\:flat\n" +
                "online-mode=false\n" +
                "allow-nether=false\n" +
                "view-distance=2\n" +
                "simulation-distance=2\n" +
                "max-tick-time=-1\n" +
                "max-players=5\n" +
                "spawn-protection=0\n" +
                "motd=Paper 26.2 in a tab (AI port)\n", upgrade);
        writeConfig("bukkit.yml",
                "settings:\n  allow-end: false\n  shutdown-message: Server closed\n" +
                "ticks-per:\n  autosave: 3000\n", upgrade);
        writeConfig("spigot.yml",
                "settings:\n  timeout-time: 600\n  restart-on-crash: false\n", upgrade);
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
            File root = new File(".").getCanonicalFile();
            res.append("{\"id\":").append(id);
            if ("list".equals(op)) {
                File dir = resolve(root, jget(json, "path"));
                res.append(",\"entries\":[");
                File[] kids = dir.listFiles();
                boolean first = true;
                if (kids != null) for (File k : kids) {
                    if (!first) res.append(',');
                    first = false;
                    res.append("{\"name\":").append(jstr(k.getName()))
                       .append(",\"dir\":").append(k.isDirectory())
                       .append(",\"size\":").append(k.isDirectory() ? 0 : k.length()).append('}');
                }
                res.append(']');
            } else if ("read".equals(op)) {
                File f = resolve(root, jget(json, "path"));
                if (f.length() > 20L * 1024 * 1024) throw new IOException("file too large");
                res.append(",\"b64\":").append(jstr(java.util.Base64.getEncoder().encodeToString(readAll(f))));
            } else if ("write".equals(op)) {
                File f = resolve(root, jget(json, "path"));
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(java.util.Base64.getDecoder().decode(jget(json, "b64")));
                fo.close();
            } else if ("mkdir".equals(op)) {
                resolve(root, jget(json, "path")).mkdirs();
            } else if ("delete".equals(op)) {
                deleteRec(resolve(root, jget(json, "path")));
            } else if ("export".equals(op)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(bos);
                zipDir(root, root, zos);
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
            if (k.isDirectory()) zipDir(root, k, zos);
            else {
                zos.putNextEntry(new ZipEntry(rel));
                zos.write(readAll(k));
                zos.closeEntry();
            }
        }
    }
}

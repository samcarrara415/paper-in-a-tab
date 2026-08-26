import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Browser-compat patches applied to the stock Paper 1.8.8 jar:
 *
 * 1. ServerConnection.a(InetAddress, int) -> no-op.
 *    A browser tab cannot listen on a TCP port; without this the failed bind
 *    makes DedicatedServer.init() return false and the server never ticks.
 *
 * 2. LogManager.callerClass(Class) -> falls back to LogManager.class.
 *    CheerpJ supports neither sun.reflect.Reflection.getCallerClass nor
 *    SecurityManager stack inspection, so log4j's no-arg getLogger() throws
 *    UnsupportedOperationException in MinecraftServer's static initializer.
 *
 * Usage: java -cp asm.jar:. Patcher <in.jar> <out.jar>
 */
public class Patcher {

    interface BodyWriter { void write(MethodVisitor mv); }

    static class Target {
        final String method, desc; final BodyWriter body;
        Target(String m, String d, BodyWriter b) { method = m; desc = d; body = b; }
    }

    static final Map<String, Target> TARGETS = new HashMap<String, Target>();
    // Classes whose getSystemLoadAverage() call sites are replaced by the
    // constant -1.0 (the documented "load average unavailable" value):
    // CheerpJ has no Java_sun_misc_Unsafe_getLoadAverage, and Paper's timings
    // MinuteReport crashed the whole server on it after exactly one minute.
    static final Set<String> LOADAVG_CLASSES = new HashSet<String>(Arrays.asList(
            "co/aikar/timings/TimingHistory$MinuteReport.class",
            "co/aikar/timings/TimingHistory.class"));
    static {
        TARGETS.put("net/minecraft/server/v1_8_R3/ServerConnection.class",
            new Target("a", "(Ljava/net/InetAddress;I)V", new BodyWriter() {
                public void write(MethodVisitor mv) {
                    mv.visitCode();
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(0, 3);
                    mv.visitEnd();
                }
            }));
        TARGETS.put("org/apache/logging/log4j/LogManager.class",
            new Target("callerClass", "(Ljava/lang/Class;)Ljava/lang/Class;", new BodyWriter() {
                public void write(MethodVisitor mv) {
                    mv.visitCode();
                    Label fallback = new Label();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitJumpInsn(Opcodes.IFNULL, fallback);
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitLabel(fallback);
                    mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    mv.visitLdcInsn(Type.getObjectType("org/apache/logging/log4j/LogManager"));
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(1, 1);
                    mv.visitEnd();
                }
            }));
    }

    public static void main(String[] args) throws Exception {
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        Path dir = Files.createTempDirectory("patch");

        List<String> allEntries = new ArrayList<String>(TARGETS.keySet());
        allEntries.addAll(LOADAVG_CLASSES);

        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(in.toFile())) {
            for (String entryName : LOADAVG_CLASSES) {
                java.util.zip.ZipEntry ze = zf.getEntry(entryName);
                if (ze == null) throw new RuntimeException("not found: " + entryName);
                ClassReader cr = new ClassReader(readAll(zf.getInputStream(ze)));
                ClassWriter cw = new ClassWriter(cr, 0);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] exc) {
                        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(acc, name, desc, sig, exc)) {
                            @Override
                            public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                                if (mname.equals("getSystemLoadAverage") && mdesc.equals("()D")) {
                                    super.visitInsn(Opcodes.POP);
                                    super.visitLdcInsn(Double.valueOf(-1.0));
                                    return;
                                }
                                super.visitMethodInsn(op, owner, mname, mdesc, itf);
                            }
                        };
                    }
                }, 0);
                Path clsFile = dir.resolve(entryName);
                Files.createDirectories(clsFile.getParent());
                Files.write(clsFile, cw.toByteArray());
                System.out.println("patched (loadavg) " + entryName);
            }

            for (Map.Entry<String, Target> entry : TARGETS.entrySet()) {
                java.util.zip.ZipEntry ze = zf.getEntry(entry.getKey());
                if (ze == null) throw new RuntimeException("not found: " + entry.getKey());
                byte[] cls = readAll(zf.getInputStream(ze));

                final Target t = entry.getValue();
                ClassReader cr = new ClassReader(cls);
                ClassWriter cw = new ClassWriter(cr, 0);
                final boolean[] hit = {false};
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] exc) {
                        MethodVisitor mv = super.visitMethod(acc, name, desc, sig, exc);
                        if (name.equals(t.method) && desc.equals(t.desc)) {
                            hit[0] = true;
                            t.body.write(mv);
                            return null;
                        }
                        return mv;
                    }
                }, 0);
                if (!hit[0]) throw new RuntimeException("method missing in " + entry.getKey());

                Path clsFile = dir.resolve(entry.getKey());
                Files.createDirectories(clsFile.getParent());
                Files.write(clsFile, cw.toByteArray());
                System.out.println("patched " + entry.getKey());
            }
        }

        List<String> cmd = new ArrayList<String>(Arrays.asList("jar", "uf", out.toAbsolutePath().toString()));
        for (String k : allEntries) { cmd.add("-C"); cmd.add(dir.toAbsolutePath().toString()); cmd.add(k); }
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        if (p.waitFor() != 0) throw new RuntimeException("jar update failed");
        System.out.println("wrote " + out);
    }

    static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }
}

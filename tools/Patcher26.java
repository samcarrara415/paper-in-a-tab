import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Browser patches for the downgraded Paper 26.2 jars, applied in place:
 *
 *   java -cp asm.jar:. Patcher26 bind <paper-26.2.jar>
 *      no-ops ServerConnectionListener.startTcpServerListener(InetAddress, int)
 *      — a browser tab cannot listen on a TCP port.
 *
 *   java -cp asm.jar:. Patcher26 log4j <log4j-api.jar>
 *      LogManager.callerClass(Class) falls back to LogManager.class — CheerpJ
 *      cannot resolve caller classes, which makes the no-arg getLogger() throw.
 */
public class Patcher26 {

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path jar = Paths.get(args[1]);
        String entry;
        byte[] patched;

        if (mode.equals("bind")) {
            entry = "net/minecraft/server/network/ServerConnectionListener.class";
            byte[] cls = read(jar, entry);
            cls = transform(cls, new BodySwap("startTcpServerListener", "(Ljava/net/InetAddress;I)V") {
                void body(MethodVisitor mv) {
                    mv.visitCode();
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(0, 3);
                    mv.visitEnd();
                }
            });
            patched = transform(cls, new BodySwap("startTcpServerListener", "(Ljava/net/SocketAddress;)V") {
                void body(MethodVisitor mv) {
                    mv.visitCode();
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(0, 2);
                    mv.visitEnd();
                }
            });
        } else if (mode.equals("log4j")) {
            entry = "org/apache/logging/log4j/LogManager.class";
            patched = transform(read(jar, entry), new BodySwap("callerClass", "(Ljava/lang/Class;)Ljava/lang/Class;") {
                void body(MethodVisitor mv) {
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
            });
        } else if (mode.equals("netty")) {
            // estimateMaxDirectMemory reaches sun.misc.VM through a
            // MethodHandle that crashes CheerpJ's invoker; the heap bound is
            // an acceptable estimate.
            entry = "io/netty/util/internal/PlatformDependent.class";
            patched = transform(read(jar, entry), new BodySwap("estimateMaxDirectMemory", "()J") {
                void body(MethodVisitor mv) {
                    mv.visitCode();
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "maxMemory", "()J", false);
                    mv.visitInsn(Opcodes.LRETURN);
                    mv.visitMaxs(2, 0);
                    mv.visitEnd();
                }
            });
        } else if (mode.equals("cleaner")) {
            // CleanerJava6's probing MethodHandles crash CheerpJ's invoker.
            // Leave CLEAN_METHOD null: isSupported() reads false and netty
            // falls back to its no-op cleaner.
            entry = "io/netty/util/internal/CleanerJava6.class";
            patched = transform(read(jar, entry), new BodySwap("<clinit>", "()V") {
                void body(MethodVisitor mv) {
                    mv.visitCode();
                    mv.visitLdcInsn(Type.getObjectType("io/netty/util/internal/CleanerJava6"));
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "io/netty/util/internal/logging/InternalLoggerFactory",
                            "getInstance", "(Ljava/lang/Class;)Lio/netty/util/internal/logging/InternalLogger;", false);
                    mv.visitFieldInsn(Opcodes.PUTSTATIC, "io/netty/util/internal/CleanerJava6",
                            "logger", "Lio/netty/util/internal/logging/InternalLogger;");
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitFieldInsn(Opcodes.PUTSTATIC, "io/netty/util/internal/CleanerJava6",
                            "CLEAN_METHOD", "Ljava/lang/invoke/MethodHandle;");
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(1, 0);
                    mv.visitEnd();
                }
            });
        } else if (mode.equals("serverenv")) {
            // The root-user check instantiates com.sun.security.auth.module
            // .UnixSystem, which needs the jaas_unix native. A tab is not root.
            entry = "io/papermc/paper/util/ServerEnvironment.class";
            patched = transform(read(jar, entry), new BodySwap("<clinit>", "()V") {
                void body(MethodVisitor mv) {
                    mv.visitCode();
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitFieldInsn(Opcodes.PUTSTATIC, "io/papermc/paper/util/ServerEnvironment",
                            "RUNNING_AS_ROOT_OR_ADMIN", "Z");
                    mv.visitLdcInsn("S-1-16-12288");
                    mv.visitFieldInsn(Opcodes.PUTSTATIC, "io/papermc/paper/util/ServerEnvironment",
                            "WINDOWS_HIGH_INTEGRITY_LEVEL", "Ljava/lang/String;");
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(1, 0);
                    mv.visitEnd();
                }
            });
        } else if (mode.equals("authlib")) {
            // The services-key HTTPS fetch freezes CheerpJ's cooperative
            // scheduler on some browsers (desktop Safari hung the whole boot).
            // Offline mode never needs the key: report "no keys" immediately.
            entry = "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo.class";
            patched = transform(read(jar, entry), new BodySwap("fetch",
                    "(Ljava/net/URL;Lcom/mojang/authlib/minecraft/client/MinecraftClient;)Ljava/util/Optional;") {
                void body(MethodVisitor mv) {
                    mv.visitCode();
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false);
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(1, 2);
                    mv.visitEnd();
                }
            });
        } else if (mode.equals("verfetch")) {
            // Same freeze risk for Paper's update check — cut every fetch path.
            entry = "com/destroystokyo/paper/PaperVersionFetcher.class";
            byte[] c = read(jar, entry);
            c = transform(c, new BodySwap("getUpdateStatusStartupMessage", "()V") {
                void body(MethodVisitor mv) {
                    mv.visitCode(); mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 0); mv.visitEnd();
                }
            });
            c = transform(c, new BodySwap("fetchDistanceFromSiteApi", "(I)I") {
                void body(MethodVisitor mv) {
                    mv.visitCode(); mv.visitInsn(Opcodes.ICONST_M1); mv.visitInsn(Opcodes.IRETURN); mv.visitMaxs(1, 1); mv.visitEnd();
                }
            });
            c = transform(c, new BodySwap("fetchDistanceFromGitHub", "(Ljava/lang/String;Ljava/lang/String;)I") {
                void body(MethodVisitor mv) {
                    mv.visitCode(); mv.visitInsn(Opcodes.ICONST_M1); mv.visitInsn(Opcodes.IRETURN); mv.visitMaxs(1, 2); mv.visitEnd();
                }
            });
            patched = transform(c, new BodySwap("fetchMinecraftVersionList", "()Ljava/util/Optional;") {
                void body(MethodVisitor mv) {
                    mv.visitCode();
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false);
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(1, 0);
                    mv.visitEnd();
                }
            });
        } else {
            throw new IllegalArgumentException(mode);
        }

        Path dir = Files.createTempDirectory("patch26");
        Path clsFile = dir.resolve(entry);
        Files.createDirectories(clsFile.getParent());
        Files.write(clsFile, patched);
        Process p = new ProcessBuilder("jar", "uf", jar.toAbsolutePath().toString(),
                "-C", dir.toAbsolutePath().toString(), entry).inheritIO().start();
        if (p.waitFor() != 0) throw new RuntimeException("jar update failed");
        System.out.println("patched " + mode + " in " + jar.getFileName());
    }

    abstract static class BodySwap {
        final String name, desc;
        BodySwap(String name, String desc) { this.name = name; this.desc = desc; }
        abstract void body(MethodVisitor mv);
    }

    static byte[] transform(byte[] cls, final BodySwap swap) {
        ClassReader cr = new ClassReader(cls);
        ClassWriter cw = new ClassWriter(cr, 0);
        final boolean[] hit = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, exc);
                if (name.equals(swap.name) && desc.equals(swap.desc)) {
                    hit[0] = true;
                    swap.body(mv);
                    return null;
                }
                return mv;
            }
        }, 0);
        if (!hit[0]) throw new RuntimeException("method not found: " + swap.name + swap.desc);
        return cw.toByteArray();
    }

    static byte[] read(Path jar, String entry) throws IOException {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry ze = zf.getEntry(entry);
            if (ze == null) throw new IOException("entry not found: " + entry);
            InputStream is = zf.getInputStream(ze);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}

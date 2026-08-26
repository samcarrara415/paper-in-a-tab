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
            patched = transform(read(jar, entry), new BodySwap("startTcpServerListener", "(Ljava/net/InetAddress;I)V") {
                void body(MethodVisitor mv) {
                    mv.visitCode();
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(0, 3);
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

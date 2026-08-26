# Paper 26.2 → Java 8 port worklog (AI, from scratch)

Goal: run modern Paper 26.2 under CheerpJ in the browser, ported by AI without
using Lodeway's patches. The 1.8.8 build stays as the phone-friendly version.

## Pipeline

```
paperclip 26.2-119 (JDK 25)     -> run25/  (105 jars: versions/ + libraries/)
JvmDowngrader 2.0.1 (patched)   -> dg/     (every jar rewritten to class v52)
+ bytecode patches              -> docs/jars26/  (108 jars, ~111 MB, + manifest.json)
```

Scripts: `rebuild.sh` (tool + api + downgrade all + configurate splice),
`prepare-browser.sh` (assemble docs/jars26 + browser-only ASM patches).
Local JDK 8 verify: run `BrowserLauncher26` with `-Dbrowser.natives=false`,
feed commands via stdin file (see run8L/). Boots "Done" in ~12-19s on Temurin 8.

## JvmDowngrader fixes (patches/jvmdg-fixes.diff, applied to tag 2.0.1)

1. Build unpinned from Azul JDK 6/7 toolchains (don't exist on mac arm64);
   compile floors at Java 8.
2. Java11Downgrader nest-accessor rewriter: handle INVOKEINTERFACE (private
   interface methods), preserve itf flags.
3. j19 Thread.join(Duration) stub: was void, must return boolean — the void
   version made the stubber emit `checkcast Z` on an empty stack (VerifyError
   in leafpile's BalancedPrioritisedThreadPool).
4. j21 SequencedMap stubs: firstEntry/lastEntry/pollFirst/pollLast now
   delegate to NavigableMap natives and return null on empty (was
   iterator().next() → NoSuchElementException in chunk scheduler queues).
5. j9 MethodHandles.byteArrayViewVarHandle + byte-view mode in the VarHandle
   emulation (plain get/set, both orders) — starlight's SWMRNibbleArray needs
   the long view.
6. j9 ForkJoinPool: @Modify stub maps the Java 9 10-arg constructor onto the
   Java 8 4-arg one (drops tuning args) — Util.makeExecutor uses it.

## Other patches

- configurate-core ObjectFieldDiscoverer (patches/ObjectFieldDiscoverer-patched.java):
  jvmdg-desugared records (superclass J_L_Record) instantiate via
  Unsafe.allocateInstance; Paper's config types are records.
- Browser-only (Patcher26, applied in docs/jars26 only):
  - ServerConnectionListener.startTcpServerListener(InetAddress,int) → no-op
  - log4j-api LogManager.callerClass → LogManager.class fallback (CheerpJ has
    no caller-class reflection)
  - netty PlatformDependent.estimateMaxDirectMemory → Runtime.maxMemory()
    (its MethodHandle path crashes CheerpJ's invoker)

## Runtime classpath (browser and JDK 8 verify)

launcher26.jar : api-52-all.jar : jvmdg-patched.jar (jvmdg runtime util +
shaded asm) : shade-asm.jar : paper-26.2.jar : all libraries. System props:
Paper.IgnoreJavaVersion=true; 26.2 works in /files/v26 (1.8.8 owns /files).

## Known-benign noise

- "No key layers in MapLike[{}]" — flat preset with empty generator-settings;
  present on the JDK 25 baseline too, falls back to default flat layers.
- oshi/JNA/udev warnings in browser — no native platform; non-fatal.
- "Failed to get system info for Memory" NPEs — ManagementFactory heap beans
  unimplemented in CheerpJ; warn-level only.

## State

- JDK 8 local: boots, commands work, clean shutdown.
- CheerpJ: bootstrap + config + registries load; netty estimateMaxDirectMemory
  patched; currently verifying full boot in browser.

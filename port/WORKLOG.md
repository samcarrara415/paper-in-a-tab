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

## CheerpJ-specific findings (browser only)

- MethodHandle.linkToInterface throws ArrayIndexOutOfBounds in CheerpJ's
  invoker — hit by netty's probing (estimateMaxDirectMemory, CleanerJava6).
  Both patched out (Patcher26 netty/cleaner modes) + io.netty.noUnsafe=true.
- FileOutputStream truncate-overwrite of an existing /files (IDB) file keeps
  the old bytes. launcher26 writeConfig now deletes + NIO-writes + verifies.
  This also explains the one-time "Failed to load eula.txt" → server clobbered
  eula to false → persisted. eula.txt is force-rewritten every boot now.
- com.sun.security.auth.module.UnixSystem needs the jaas_unix native —
  Paper's ServerEnvironment root check. Patched clinit (serverenv mode).

## State

- JDK 8 local: boots, commands work, clean shutdown.
- CheerpJ: FULLY WORKING — boots to Done in ~40s (M-series desktop), commands, clean stop, world persistence across reloads all verified locally.

## Mobile 26.2 round (Aug 26)

- Measured: 26.2 running uses ~662 MB JS heap on desktop Chromium — inside
  modern-phone budgets. CPU is the constraint, not memory.
- iOS WebKit verified via simulator against localhost (sim shares host
  localhost; ?report=1 beacons every page/console line to a local collector
  on :8898 — see docs/app.js report mode + /tmp/logserver.py pattern).
- Fresh-boot bug (all platforms): paper-world-defaults.yml written before
  config/ existed → clean exit 0 before any Java log line. writeConfig now
  creates parents; main() reports config failures instead of dying.
- Safari starves background Java threads while the server thread is busy:
  the 250ms polling loop never ran → commands/file ops timed out. Fix:
  MinecraftServer.addTickable pumps the bridge from inside the tick loop
  (launcher26 v5). MAX_PRIORITY on the poll thread was tried first and
  starved the *boot* instead — do not use priorities under CheerpJ.
- Results (iPhone 17 sim, real WebKit): 26.2 boots Done in 80–145s, commands
  work via tick pump, world persists across Safari relaunch.

## Tunnel round (Aug 26, tunnel branch)

- PC session's Go relay + playit + tailscale-serve verified their side; this side adds:
  launcher/TunnelTransport.java — in-JVM netty LocalServerChannel wearing the exact
  vanilla 1.8.8 pipeline; each relay conn id becomes a LocalChannel pair. Two new
  natives (tunnelPoll/tunnelOut) batch (id, b64) events; poll drops to 50ms while linked.
- Fixes found by a real client join (mineflayer 1.8.8):
  1) CraftBukkit login casts the connection address to InetSocketAddress — addr_fix
     handler overwrites NetworkManager.l with a loopback address post-channelActive.
  2) NetworkManager.getRawAddress() reads channel.remoteAddress() live (LocalAddress)
     — patched in the jar (445p4) to return field l.
- VERIFIED: mineflayer joined through relay→WS→tab: login 329ms, spawn 603ms, chat
  echoed in tab console, clean disconnect. U3 bay UI: ?tunnel= and ?addr= params.
- Quirk: first joiner can fall through unloaded spawn chunks (keep-spawn-loaded off
  for phone perf); ground loads a moment later, respawn recovers.
- 26.2 tunnel: not wired yet (transport is version-specific; 1.8.8 only for now).

## 26.2 tunnel (Aug 26)

- launcher26/TunnelTransport26.java: modern sibling of the 1.8.8 transport —
  LocalServerChannel + vanilla Connection.configureSerialization(SERVERBOUND),
  Connection + ServerHandshakePacketListenerImpl per conn, connections list via
  reflection, own DefaultEventLoopGroup. Same addr_fix InetSocketAddress swap.
  Pumped from the existing addTickable tick hook (tunnelPoll/tunnelOut natives,
  registered for the BrowserLauncher26 prefix in app.js). launcher26.v6.jar.
- Hand-rolled JSON batch parsing (modern jar relocates gson).
- VERIFIED locally: 26.2 boots, links, and answers a hand-written server-list
  ping through relay→WS→tab: version "Paper 26.2" proto 776, MOTD, player
  counts. Login/play phases need a real 26.2 client to verify (mineflayer
  cannot speak 26.2) — same addr_fix + pipeline that fully worked on 1.8.8.

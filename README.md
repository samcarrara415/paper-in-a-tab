# paper-in-a-tab

A real Paper Minecraft server running inside a browser tab, hosted entirely on GitHub Pages.
An AI remake of the [Lodeway Labs "Paper in the browser" experiment](https://paper.labs.lodeway.app)
([their repo](https://github.com/Lodeway/paper-in-browser)), rebuilt from scratch in one session to
compare against the team-built original — and tuned so it boots on a mobile browser.

**Live: https://samcarrara415.github.io/paper-in-a-tab/**

## How this remake differs from the original

The original ports **Paper 26.2** (built for Java 25) down to Java 8 bytecode with ~30 patches, a
custom netty transport, and a JVMDowngrader pipeline, then runs it on [CheerpJ](https://cheerpj.com).
A Go backend gives every visitor a joinable TCP address through a WebSocket tunnel.

This remake makes two trades to fit "quick" and "static hosting only":

1. **Paper 1.8.8 instead of a ported Paper 26.2.** Paper 1.8.8 already runs on Java 8, so the
   whole porting pipeline disappears. Two small ASM patches (see `tools/Patcher.java`) are enough:
   - `ServerConnection.a(InetAddress, int)` → no-op (a tab can't listen on a TCP port)
   - `LogManager.callerClass(Class)` → fixed fallback (CheerpJ has no caller-class reflection,
     which made log4j's no-arg `getLogger()` throw during `MinecraftServer.<clinit>`)
2. **No tunnel.** GitHub Pages serves files and runs nothing, so there is no joinable address.
   The page says so instead of pretending (the empty "U3 / tunnel" bay).

It's also tuned to boot on phones: flat world, Nether and End disabled, view distance 3,
and a ~21 MB download instead of ~110 MB.

## The 26.2 port (yes, really)

The site now has a second bay: **Paper 26.2 — modern Paper, built for Java 25 — AI-ported to
Java 8 bytecode from scratch** (no Lodeway patches used) and running under CheerpJ. The pipeline
lives in `port/`: official Paper 26.2 jars are downgraded with a bug-fixed
[JVMDowngrader](https://github.com/unimined/JvmDowngrader) (six fixes, in `port/patches/`),
plus a dozen targeted bytecode patches for things a browser can't do (TCP listeners, JFR,
JAAS natives, netty's Unsafe probing, CheerpJ filesystem quirks). `port/WORKLOG.md` documents
every failure and fix in order. Boots to "Done" in ~40–90 s on a desktop; the 1.8.8 bay remains
the phone-sized option.

## Layout

```
docs/            the GitHub Pages site (index.html, style.css, app.js, jars/)
launcher/        BrowserLauncher.java — what CheerpJ actually runs: boots CraftBukkit on a
                 thread, hooks log4j for console output, and pumps command + file-op queues
                 whose four native methods are implemented in docs/app.js
tools/           Patcher.java — the two ASM patches applied to the stock Paper jar
```

## The bridge

The launcher declares four `native` methods; CheerpJ maps them to JavaScript functions in
`docs/app.js`:

| Java (BrowserLauncher)     | Direction | Purpose                          |
| -------------------------- | --------- | -------------------------------- |
| `emitLine(String)`         | Java → JS | console lines (via a log4j appender) |
| `pollCommand()`            | JS → Java | console commands, polled every 250 ms |
| `pollOp()`                 | JS → Java | file-manager ops as JSON         |
| `opResult(String)`         | Java → JS | file-manager results (base64 for file bytes) |

The world lives in CheerpJ's IndexedDB-backed `/files` mount and survives reloads.

## Build it yourself

Needs a JDK 8 (`javac`/`jar` on PATH), `curl`, and nothing else:

```bash
# 1. fetch Paperclip 1.8.8-445 and let it produce the real server jar
curl -LO https://fill-data.papermc.io/v1/objects/7ff6d2cec671ef0d95b3723b5c92890118fb882d73b7f8fa0a2cd31d97c55f86/paper-1.8.8-445.jar
mkdir -p work && cd work && cp ../paper-1.8.8-445.jar . && java -jar paper-1.8.8-445.jar; cd ..

# 2. apply the browser patches
curl -L -o tools/asm.jar https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar
(cd tools && javac -cp asm.jar Patcher.java && java -cp asm.jar:. Patcher ../work/cache/patched_1.8.8.jar ../docs/jars/paper-server.445p3.jar)

# 3. build the launcher
(cd launcher && javac -source 8 -target 8 -cp ../docs/jars/paper-server.445p3.jar BrowserLauncher.java && jar cf ../docs/jars/launcher.3.jar *.class)

# 4. serve (CheerpJ needs HTTP Range support, so python -m http.server won't work)
npx http-server docs -p 8899 -c-1
```

To test the launcher on a real JDK 8 without a browser:
`java -Dbrowser.natives=false -cp docs/jars/paper-server.445p3.jar:docs/jars/launcher.3.jar BrowserLauncher`

## Licenses & credit

Code original to this repo (`launcher/`, `tools/`, `docs/*.html|css|js`) is MIT. The two jars in
`docs/jars/` are built from [Paper](https://github.com/PaperMC/Paper) and Mojang code and remain
under their licenses (Paper is GPL-3.0; Mojang code under Mojang's terms). Booting the server means
agreeing to the [Minecraft EULA](https://aka.ms/MinecraftEULA); the page asks before first boot.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
Concept credit: the Lodeway Labs team. Runtime: [CheerpJ](https://cheerpj.com) by Leaning
Technologies (free for non-commercial use).

## Built with AI

This entire remake — patches, launcher, bridge, page, tests — was produced by Claude in a single
session as a comparison exercise against the human-team original.

/* paper-in-a-tab — page-side half of the bridge.
   The Java launcher polls pollCommand()/pollOp() every 250ms; we feed it queues
   and it feeds us console lines and op results through emitLine()/opResult(). */

(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };

  var consoleEl = $("srv-console");
  var cmdInput = $("cmd-input");
  var cmdSend = $("cmd-send");
  var btnStart = $("btn-start");
  var btnStop = $("btn-stop");
  var ledConsole = $("led-console");
  var ledFiles = $("led-files");
  var statusConsole = $("status-console");
  var statusFiles = $("status-files");

  // ---- state ----
  var state = "idle"; // idle | loading | booting | running | stopping | stopped | failed
  var cmdQueue = [];
  var opQueue = [];
  var pendingOps = {}; // id -> {resolve, reject}
  var opSeq = 1;
  var history = [];
  var histPos = -1;

  // GitHub Pages serves this site from a subpath; CheerpJ's /app mount maps to
  // the origin root, so the jar paths must include wherever we're deployed.
  var basePath = new URL(".", location.href).pathname; // e.g. "/paper-in-a-tab/"
  var classpath = "/app" + basePath + "jars/launcher.3.jar:/app" + basePath + "jars/paper-server.445p3.jar";

  // Which server this boot runs: "188" (proven, phone-sized) or "262"
  // (the AI-ported modern Paper — heavy, desktop-recommended).
  var version = "188";

  // ---- console rendering (batched, ring-buffered) ----
  var pendingLines = [];
  var flushScheduled = false;
  var MAX_LINES = 700;

  function classify(line) {
    if (/ ERROR\]|^\[launcher\]|Exception|\tat /.test(line)) return "console__line--error";
    if (/ WARN\]/.test(line)) return "console__line--warn";
    if (/^\[page\]/.test(line)) return "console__line--sys";
    if (/^> /.test(line)) return "console__line--cmd";
    return "";
  }

  function log(line) {
    pendingLines.push(line);
    if (!flushScheduled) {
      flushScheduled = true;
      requestAnimationFrame(flushLines);
    }
  }

  function flushLines() {
    flushScheduled = false;
    var stick = consoleEl.scrollHeight - consoleEl.scrollTop - consoleEl.clientHeight < 60;
    var frag = document.createDocumentFragment();
    for (var i = 0; i < pendingLines.length; i++) {
      var div = document.createElement("div");
      div.className = ("console__line " + classify(pendingLines[i])).trim();
      div.textContent = pendingLines[i];
      frag.appendChild(div);
    }
    pendingLines.length = 0;
    consoleEl.appendChild(frag);
    while (consoleEl.childElementCount > MAX_LINES) consoleEl.removeChild(consoleEl.firstElementChild);
    if (stick) consoleEl.scrollTop = consoleEl.scrollHeight;
  }

  // ---- status LEDs ----
  function setState(next, label) {
    state = next;
    var led = { idle: "", loading: "led--amber led--blink", booting: "led--amber led--blink",
                running: "led--green", stopping: "led--amber led--blink", stopped: "", failed: "led--red" }[next] || "";
    ledConsole.className = ("led " + led).trim();
    statusConsole.textContent = label || next;
    var up = next === "running";
    cmdInput.disabled = !up;
    cmdSend.disabled = !up;
    btnStop.disabled = !(next === "running" || next === "booting");
    btnStart.disabled = next !== "idle";
    ["btn-refresh", "btn-upload", "btn-mkdir", "btn-export"].forEach(function (id) { $(id).disabled = !up; });
    ledFiles.className = "led" + (up ? " led--green" : "");
    statusFiles.textContent = up ? "mounted" : "offline";
    if (up) { cmdInput.placeholder = "try: say hi · time set day · gamerule doDaylightCycle false"; }
  }

  // ---- natives: the JavaScript half of BrowserLauncher ----

  // Console lines arrive twice for some loggers (log4j appender + stdout tee),
  // so drop a line whose timestamp-stripped payload was just seen.
  var recentLines = new Map();
  function isDuplicate(line) {
    var key = line.replace(/^\[\d\d:\d\d:\d\d[^\]]*\]:?\s*/, "");
    var now = Date.now();
    if (recentLines.size > 200) recentLines.clear();
    var seen = recentLines.get(key);
    recentLines.set(key, now);
    return seen !== undefined && now - seen < 4000;
  }

  async function Java_BrowserLauncher_emitLine(lib, str) {
    var line = String(str);
    if (isDuplicate(line)) return;
    log(line);
    if (state === "booting" && line.indexOf("Done (") !== -1) {
      setState("running");
      log("[page] server is up. commands are live below.");
      refreshFiles();
      // ?autocmd=<command> runs one command after boot — automated testing.
      var auto = new URLSearchParams(location.search).get("autocmd");
      if (auto) setTimeout(function () { log("> " + auto); cmdQueue.push(auto); }, 4000);
    }
    if (line.indexOf("Closing Server") !== -1 || line.indexOf("Stopping server") !== -1) {
      setState("stopping");
    }
  }
  async function Java_BrowserLauncher_pollCommand(lib) {
    return cmdQueue.length ? cmdQueue.shift() : null;
  }
  async function Java_BrowserLauncher_pollOp(lib) {
    return opQueue.length ? opQueue.shift() : null;
  }
  async function Java_BrowserLauncher_opResult(lib, json) {
    try {
      var res = JSON.parse(String(json));
      var p = pendingOps[res.id];
      if (p) { delete pendingOps[res.id]; res.ok ? p.resolve(res) : p.reject(new Error(res.error || "op failed")); }
    } catch (e) { console.error("bad op result", e); }
  }

  function sendOp(op) {
    return new Promise(function (resolve, reject) {
      op.id = opSeq++;
      pendingOps[op.id] = { resolve: resolve, reject: reject };
      opQueue.push(JSON.stringify(op));
      setTimeout(function () {
        if (pendingOps[op.id]) { delete pendingOps[op.id]; reject(new Error("timed out")); }
      }, op.op === "export" ? 120000 : 30000);
    });
  }

  // ---- boot ----

  // Fetching the jars up front warms the CDN edge and the browser cache
  // (CheerpJ's ranged reads occasionally hit a cold edge that answers
  // without Range support), and it gives a visible progress number.
  async function warmJars(urls, totalHint) {
    var total = totalHint || 0, loaded = 0;
    try {
      if (!total) {
        var heads = await Promise.all(urls.map(function (u) { return fetch(u, { method: "HEAD" }); }));
        heads.forEach(function (h) { total += Number(h.headers.get("content-length")) || 0; });
      }
      var queue = urls.slice();
      async function worker() {
        while (queue.length) {
          var u = queue.shift();
          try {
            var res = await fetch(u);
            if (!res.ok || !res.body) continue;
            var reader = res.body.getReader();
            while (true) {
              var chunk = await reader.read();
              if (chunk.done) break;
              loaded += chunk.value.length;
              if (total) setState("booting", "downloading " + Math.min(99, Math.round(loaded / total * 100)) + "%");
            }
          } catch (e) { /* per-jar failure is fine; CheerpJ refetches */ }
        }
      }
      await Promise.all([worker(), worker(), worker(), worker(), worker(), worker()]);
    } catch (e) { /* CheerpJ fetches the jars itself either way */ }
  }

  async function bootPlan() {
    if (version === "188") {
      return {
        main: "BrowserLauncher",
        classpath: classpath,
        userDir: "/files",
        warm: ["./jars/launcher.3.jar", "./jars/paper-server.445p3.jar"],
        warmTotal: 0,
        label: "paper 1.8.8"
      };
    }
    var mf = await (await fetch("./jars26/manifest.json")).json();
    var cp = mf.jars.map(function (j) { return "/app" + basePath + "jars26/" + j; }).join(":");
    var total = 0;
    mf.jars.forEach(function (j) { total += mf.sizes[j] || 0; });
    return {
      main: "BrowserLauncher26",
      classpath: cp,
      userDir: "/files/v26",
      warm: mf.jars.map(function (j) { return "./jars26/" + j; }),
      warmTotal: total,
      label: "paper 26.2 (AI port)"
    };
  }

  function injectCheerpJ() {
    return new Promise(function (resolve, reject) {
      var s = document.createElement("script");
      s.src = "https://cjrtnc.leaningtech.com/4.3/loader.js";
      s.onload = resolve;
      s.onerror = function () { reject(new Error("could not load the CheerpJ runtime")); };
      document.head.appendChild(s);
    });
  }

  async function boot() {
    setState("loading", "fetching runtime");
    log("[page] fetching the CheerpJ runtime…");
    try {
      var plan = await bootPlan();
      $("ver-188").disabled = true;
      $("ver-262").disabled = true;
      await injectCheerpJ();
      await cheerpjInit({
        version: 8,
        status: "none",
        natives: {
          Java_BrowserLauncher_emitLine: Java_BrowserLauncher_emitLine,
          Java_BrowserLauncher_pollCommand: Java_BrowserLauncher_pollCommand,
          Java_BrowserLauncher_pollOp: Java_BrowserLauncher_pollOp,
          Java_BrowserLauncher_opResult: Java_BrowserLauncher_opResult,
          Java_BrowserLauncher26_emitLine: Java_BrowserLauncher_emitLine,
          Java_BrowserLauncher26_pollCommand: Java_BrowserLauncher_pollCommand,
          Java_BrowserLauncher26_pollOp: Java_BrowserLauncher_pollOp,
          Java_BrowserLauncher26_opResult: Java_BrowserLauncher_opResult,
          // CheerpJ ships no implementation of this JDK native; -1 is the
          // documented "load average unavailable" answer.
          Java_sun_misc_Unsafe_getLoadAverage: async function (lib, self, loadavg, nelems) { return -1; },
          // Missing type-annotation natives: null means "none", which the
          // JDK's annotation parser accepts. Paper's config mapper trips these.
          Java_java_lang_reflect_Field_getTypeAnnotationBytes0: async function (lib, self) { return null; },
          Java_java_lang_reflect_Method_getTypeAnnotationBytes0: async function (lib, self) { return null; },
          Java_java_lang_reflect_Constructor_getTypeAnnotationBytes0: async function (lib, self) { return null; },
          // CheerpJ implements the plain Unsafe accessors but not the
          // volatile ones (reflection on final fields uses them). Its
          // threading is cooperative, so plain semantics are equivalent.
          Java_sun_misc_Unsafe_putObjectVolatile: async function (lib, self, o, off, x) { return await self.putObject(o, off, x); },
          Java_sun_misc_Unsafe_putBooleanVolatile: async function (lib, self, o, off, x) { return await self.putBoolean(o, off, x); },
          Java_sun_misc_Unsafe_putByteVolatile: async function (lib, self, o, off, x) { return await self.putByte(o, off, x); },
          Java_sun_misc_Unsafe_putShortVolatile: async function (lib, self, o, off, x) { return await self.putShort(o, off, x); },
          Java_sun_misc_Unsafe_putCharVolatile: async function (lib, self, o, off, x) { return await self.putChar(o, off, x); },
          Java_sun_misc_Unsafe_putIntVolatile: async function (lib, self, o, off, x) { return await self.putInt(o, off, x); },
          Java_sun_misc_Unsafe_putLongVolatile: async function (lib, self, o, off, x) { return await self.putLong(o, off, x); },
          Java_sun_misc_Unsafe_putFloatVolatile: async function (lib, self, o, off, x) { return await self.putFloat(o, off, x); },
          Java_sun_misc_Unsafe_putDoubleVolatile: async function (lib, self, o, off, x) { return await self.putDouble(o, off, x); },
          Java_sun_misc_Unsafe_getObjectVolatile: async function (lib, self, o, off) { return await self.getObject(o, off); },
          Java_sun_misc_Unsafe_getBooleanVolatile: async function (lib, self, o, off) { return await self.getBoolean(o, off); },
          Java_sun_misc_Unsafe_getByteVolatile: async function (lib, self, o, off) { return await self.getByte(o, off); },
          Java_sun_misc_Unsafe_getShortVolatile: async function (lib, self, o, off) { return await self.getShort(o, off); },
          Java_sun_misc_Unsafe_getCharVolatile: async function (lib, self, o, off) { return await self.getChar(o, off); },
          Java_sun_misc_Unsafe_getIntVolatile: async function (lib, self, o, off) { return await self.getInt(o, off); },
          Java_sun_misc_Unsafe_getLongVolatile: async function (lib, self, o, off) { return await self.getLong(o, off); },
          Java_sun_misc_Unsafe_getFloatVolatile: async function (lib, self, o, off) { return await self.getFloat(o, off); },
          Java_sun_misc_Unsafe_getDoubleVolatile: async function (lib, self, o, off) { return await self.getDouble(o, off); }
        },
        javaProperties: ["user.dir=" + plan.userDir, "java.awt.headless=true",
                         "log4j2.formatMsgNoLookups=true", "Paper.IgnoreJavaVersion=true",
                         // netty's Unsafe/cleaner probing goes through MethodHandle
                         // paths that crash CheerpJ's invoker; heap buffers are the
                         // right choice inside a browser tab anyway.
                         "io.netty.noUnsafe=true", "io.netty.noPreferDirect=true",
                         "io.netty.transport.noNative=true"]
      });
      setState("booting", "downloading jars");
      log("[page] runtime up. downloading " + Math.max(21, Math.round(plan.warmTotal / 1048576)) + " MB of jars (cached after the first visit)…");
      await warmJars(plan.warm, plan.warmTotal);
      log("[page] booting " + plan.label + " — this takes a while" + (version === "262" ? ", and 26.2 really wants a desktop." : ", longer on a phone."));
      setState("booting");
      var code = await cheerpjRunMain(plan.main, plan.classpath);
      // cheerpjRunMain resolves when the JVM exits (i.e. after /stop).
      setState("stopped", "powered off");
      log("[page] the jvm has exited (code " + code + "). reload the page to power on again.");
      offerReload();
    } catch (e) {
      console.error(e);
      setState("failed");
      log("[page] boot failed: " + (e && e.message ? e.message : e));
      log("[page] reload the page to try again. on phones, closing other tabs frees memory.");
      offerReload();
    }
  }

  function offerReload() {
    btnStart.textContent = "reload page";
    btnStart.disabled = false;
    btnStart.onclick = function () { location.reload(); };
  }

  // ---- controls ----
  btnStart.addEventListener("click", function () {
    if (state !== "idle") return;
    if (!localStorage.getItem("pit.eula")) { $("eula").hidden = false; return; }
    boot();
  });
  $("btn-eula-yes").addEventListener("click", function () {
    try { localStorage.setItem("pit.eula", "yes"); } catch (e) {}
    $("eula").hidden = true;
    boot();
  });
  $("btn-eula-no").addEventListener("click", function () { $("eula").hidden = true; });

  btnStop.addEventListener("click", function () {
    if (state === "running" || state === "booting") {
      log("[page] asking the server to stop and save…");
      cmdQueue.push("stop");
      setState("stopping");
    }
  });

  $("cmd-form").addEventListener("submit", function (e) {
    e.preventDefault();
    var cmd = cmdInput.value.trim();
    if (!cmd || state !== "running") return;
    log("> " + cmd);
    cmdQueue.push(cmd);
    history.push(cmd);
    histPos = history.length;
    cmdInput.value = "";
    if (cmd === "stop") setState("stopping");
  });

  cmdInput.addEventListener("keydown", function (e) {
    if (e.key === "ArrowUp" && histPos > 0) { histPos--; cmdInput.value = history[histPos]; e.preventDefault(); }
    else if (e.key === "ArrowDown" && histPos < history.length - 1) { histPos++; cmdInput.value = history[histPos]; e.preventDefault(); }
  });

  // ---- wake lock (phones dim + suspend, which freezes the server) ----
  var wakeLock = null;
  if ("wakeLock" in navigator) {
    $("wake-label").hidden = false;
    $("wake-toggle").addEventListener("change", async function () {
      try {
        if (this.checked) { wakeLock = await navigator.wakeLock.request("screen"); }
        else if (wakeLock) { await wakeLock.release(); wakeLock = null; }
      } catch (e) { this.checked = false; }
    });
    document.addEventListener("visibilitychange", async function () {
      if (document.visibilityState === "visible" && $("wake-toggle").checked && !wakeLock) {
        try { wakeLock = await navigator.wakeLock.request("screen"); } catch (e) {}
      }
    });
  }

  // ---- file manager ----
  var cwd = "/";

  function fmtSize(n) {
    if (n < 1024) return n + " B";
    if (n < 1048576) return (n / 1024).toFixed(1) + " KB";
    return (n / 1048576).toFixed(1) + " MB";
  }

  function renderCrumbs() {
    var el = $("crumbs");
    el.textContent = "";
    var parts = cwd.split("/").filter(Boolean);
    var a = document.createElement("a");
    a.href = "#"; a.textContent = "/";
    a.onclick = function (e) { e.preventDefault(); cwd = "/"; refreshFiles(); };
    el.appendChild(a);
    var acc = "";
    parts.forEach(function (p) {
      acc += "/" + p;
      var link = document.createElement("a");
      var target = acc;
      link.href = "#"; link.textContent = p + "/";
      link.onclick = function (e) { e.preventDefault(); cwd = target; refreshFiles(); };
      el.appendChild(link);
    });
  }

  async function refreshFiles() {
    if (state !== "running") return;
    try {
      var res = await sendOp({ op: "list", path: cwd });
      renderCrumbs();
      var list = $("file-list");
      list.textContent = "";
      var entries = res.entries || [];
      entries.sort(function (a, b) { return (b.dir - a.dir) || a.name.localeCompare(b.name); });
      if (cwd !== "/") {
        var up = document.createElement("li");
        var upBtn = document.createElement("button");
        upBtn.className = "file__name file__name--dir";
        upBtn.textContent = "../";
        upBtn.onclick = function () { cwd = cwd.replace(/\/[^/]+$/, "") || "/"; refreshFiles(); };
        up.appendChild(upBtn);
        list.appendChild(up);
      }
      if (!entries.length && cwd === "/") {
        var em = document.createElement("li");
        em.className = "files__empty";
        em.textContent = "nothing here yet";
        list.appendChild(em);
      }
      entries.forEach(function (en) {
        var li = document.createElement("li");
        var name = document.createElement("button");
        name.className = "file__name" + (en.dir ? " file__name--dir" : "");
        name.textContent = en.name + (en.dir ? "/" : "");
        name.onclick = function () {
          if (en.dir) { cwd = (cwd === "/" ? "" : cwd) + "/" + en.name; refreshFiles(); }
          else { downloadFile(en.name); }
        };
        li.appendChild(name);
        if (!en.dir) {
          var size = document.createElement("span");
          size.className = "file__size";
          size.textContent = fmtSize(en.size);
          li.appendChild(size);
        }
        var del = document.createElement("button");
        del.className = "file__act";
        del.textContent = "delete";
        del.onclick = function () {
          if (confirm("Delete " + en.name + "?")) {
            sendOp({ op: "delete", path: join(cwd, en.name) }).then(refreshFiles)
              .catch(function (e) { log("[page] delete failed: " + e.message); });
          }
        };
        li.appendChild(del);
        list.appendChild(li);
      });
    } catch (e) {
      log("[page] file listing failed: " + e.message);
    }
  }

  function join(dir, name) { return (dir === "/" ? "" : dir) + "/" + name; }

  function saveBlob(b64, filename) {
    var bytes = Uint8Array.from(atob(b64), function (c) { return c.charCodeAt(0); });
    var url = URL.createObjectURL(new Blob([bytes]));
    var a = document.createElement("a");
    a.href = url; a.download = filename;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 5000);
  }

  function downloadFile(name) {
    sendOp({ op: "read", path: join(cwd, name) })
      .then(function (res) { saveBlob(res.b64, name); })
      .catch(function (e) { log("[page] download failed: " + e.message); });
  }

  $("btn-refresh").addEventListener("click", refreshFiles);

  $("btn-mkdir").addEventListener("click", function () {
    var name = prompt("Folder name:");
    if (!name) return;
    sendOp({ op: "mkdir", path: join(cwd, name) }).then(refreshFiles)
      .catch(function (e) { log("[page] new folder failed: " + e.message); });
  });

  $("btn-upload").addEventListener("click", function () { $("upload-input").click(); });
  $("upload-input").addEventListener("change", function () {
    var f = this.files[0];
    this.value = "";
    if (!f) return;
    if (f.size > 20 * 1048576) { log("[page] upload capped at 20 MB"); return; }
    var reader = new FileReader();
    reader.onload = function () {
      var b64 = String(reader.result).split(",")[1];
      sendOp({ op: "write", path: join(cwd, f.name), b64: b64 }).then(function () {
        log("[page] uploaded " + f.name);
        refreshFiles();
      }).catch(function (e) { log("[page] upload failed: " + e.message); });
    };
    reader.readAsDataURL(f);
  });

  $("btn-export").addEventListener("click", function () {
    log("[page] zipping the filesystem…");
    sendOp({ op: "export" })
      .then(function (res) { saveBlob(res.b64, "paper-in-a-tab.zip"); log("[page] export ready."); })
      .catch(function (e) { log("[page] export failed: " + e.message); });
  });

  $("btn-reset").addEventListener("click", async function () {
    if (state !== "idle" && state !== "stopped" && state !== "failed") {
      log("[page] stop the server before a factory reset."); return;
    }
    if (!confirm("Erase the saved world and all server files from this browser?")) return;
    try {
      var dbs = (indexedDB.databases ? await indexedDB.databases() : []);
      var victims = dbs.filter(function (d) { return d.name && /files/i.test(d.name); });
      if (!victims.length) victims = [{ name: "cjFS_/files/" }];
      await Promise.all(victims.map(function (d) {
        return new Promise(function (res) {
          var rq = indexedDB.deleteDatabase(d.name);
          rq.onsuccess = rq.onerror = rq.onblocked = res;
        });
      }));
      log("[page] world erased. reload the page for a fresh start.");
    } catch (e) {
      log("[page] reset failed: " + e.message);
    }
  });

  setState("idle");

  function pickVersion(v) {
    if (state !== "idle") return;
    version = v;
    $("ver-188").classList.toggle("verpick__opt--on", v === "188");
    $("ver-262").classList.toggle("verpick__opt--on", v === "262");
    $("ver-188").setAttribute("aria-checked", String(v === "188"));
    $("ver-262").setAttribute("aria-checked", String(v === "262"));
    if (v === "262") log("[page] paper 26.2 selected — the AI-ported modern server. big download; a phone will struggle.");
  }
  $("ver-188").addEventListener("click", function () { pickVersion("188"); });
  $("ver-262").addEventListener("click", function () { pickVersion("262"); });
  if (new URLSearchParams(location.search).get("v") === "262") pickVersion("262");

  // ?autoboot=1 powers on immediately (and counts as accepting the EULA) —
  // used for automated testing and boot-me deep links.
  if (new URLSearchParams(location.search).has("autoboot")) {
    try { localStorage.setItem("pit.eula", "yes"); } catch (e) {}
    boot();
  }
})();

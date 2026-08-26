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
  var classpath = "/app" + basePath + "jars/launcher.2.jar:/app" + basePath + "jars/paper-server.445p3.jar";

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
      await injectCheerpJ();
      await cheerpjInit({
        version: 8,
        status: "none",
        natives: {
          Java_BrowserLauncher_emitLine: Java_BrowserLauncher_emitLine,
          Java_BrowserLauncher_pollCommand: Java_BrowserLauncher_pollCommand,
          Java_BrowserLauncher_pollOp: Java_BrowserLauncher_pollOp,
          Java_BrowserLauncher_opResult: Java_BrowserLauncher_opResult,
          // CheerpJ ships no implementation of this JDK native; -1 is the
          // documented "load average unavailable" answer.
          Java_sun_misc_Unsafe_getLoadAverage: async function (lib, self, loadavg, nelems) { return -1; }
        },
        javaProperties: ["user.dir=/files", "java.awt.headless=true", "log4j2.formatMsgNoLookups=true"]
      });
      setState("booting", "downloading jars");
      log("[page] runtime up. downloading ~21 MB of jars (cached after the first visit)…");
      log("[page] booting paper 1.8.8 — this takes a minute or two, longer on a phone.");
      setState("booting");
      var code = await cheerpjRunMain("BrowserLauncher", classpath);
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

  // ?autoboot=1 powers on immediately (and counts as accepting the EULA) —
  // used for automated testing and boot-me deep links.
  if (new URLSearchParams(location.search).has("autoboot")) {
    try { localStorage.setItem("pit.eula", "yes"); } catch (e) {}
    boot();
  }
})();

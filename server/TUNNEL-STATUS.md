# Tunnel status

State of the relay/tunnel side as of 2026-08-26. Host machine: **`sams-pc`** (Windows 11).

## TL;DR for the page side

| Thing | Value | State |
|---|---|---|
| **Public Minecraft address** | **`olds-hunger.tun.ply.gg:39539`** | ✅ **working, verified through the internet** |
| Host WebSocket URL for the tab | `wss://sams-pc.taila9d64b.ts.net:8443/host` | ⛔ **blocked** — see [Tailscale](#tailscale--blocked) |
| Health URL | `https://sams-pc.taila9d64b.ts.net:8443/health` | ⛔ blocked, same cause |
| Local host WebSocket (works now) | `ws://localhost:8971/host` | ✅ working |
| Local health (works now) | `http://localhost:8971/health` | ✅ working |

The relay and the public TCP path are **done and proven end to end**. The only thing left is the
`wss://` endpoint for the phone, which is blocked on one toggle in the Tailscale admin console —
not a code problem, and it does not change the protocol.

Build the page half against `ws://localhost:8971/host` now; when the toggle lands the only change
is the scheme and host in the URL.

## What is verified working

Built from `server/`, run as `-tcp 127.0.0.1:25565 -ws 127.0.0.1:8971`:

| Test | Result |
|---|---|
| `echotest` → relay → `fakehost` → back (local) | ✅ `OK echo matched` |
| **`echotest -mc` → playit → relay → `fakehost` → back (public internet)** | ✅ **`OK echo matched`, 31 bytes** |
| Second connection to `/host` | ✅ `closed by relay, code 1013 "host already connected"`, first host unaffected |
| Kill `fakehost` while a client is held open | ✅ `OK peer closed the connection (EOF)`, health → `{"conns":0,"host":false}` |
| TCP client with no host connected | ✅ accepted then immediately aborted |
| `/health` connection counting | ✅ `{"conns":1,"host":true}` with one client |

The public run shows up in the relay log as `conn 5: open from 127.238.109.183` — the playit
agent — with `fakehost: echo 31 byte(s) on conn 5`.

## playit.gg — working

| | |
|---|---|
| Public address | `olds-hunger.tun.ply.gg:39539` |
| Static IPv4 | `147.185.221.230:39539` (also `2602:fbaf:800::e6`) |
| Tunnel name / type | `Minecraft Java`, tcp, region global, shared IP |
| Forwards to | `127.0.0.1:25565` |
| Agent | `from-key-8b2a`, id `65eb2824-404e-4295-9953-1c179a038669` |
| Agent version | 1.0.10, installed via `winget install --id DevelopedMethods.playit -e` |
| Secret | `C:\ProgramData\playit_gg\playit.toml` (system-wide, service-owned) |

Runs as a Windows service, so it comes back by itself after a reboot.

### Testing through the public address needs `-mc`

A playit **Minecraft Java** tunnel is protocol-aware. Its edge reads the client's handshake packet
to route the connection and **silently drops anything whose first bytes are not Minecraft**. A
plain `hello\n` gets accepted by the edge and killed before it reaches the agent — the relay logs
nothing whatsoever, which looks exactly like a broken relay and is not one. This cost some time to
pin down; it is why `echotest` grew a `-mc` flag that sends a real handshake + status request.

```bash
./echotest.exe -mc -timeout 25s olds-hunger.tun.ply.gg:39539     # public: needs -mc
./echotest.exe 127.0.0.1:25565                                   # local: -mc optional
```

Real Minecraft clients are unaffected — they send a real handshake by definition.

## Tailscale — blocked

`tailscale serve --bg --https 8443 http://localhost:8971` hangs with no output and writes no serve
config. Cause, confirmed directly and re-tested three times:

```
$ tailscale cert sams-pc.taila9d64b.ts.net
500 Internal Server Error: your Tailscale account does not support getting TLS certs
```

HTTPS certificates are not enabled for the tailnet, so `serve` can never provision a cert.

**What to click:** <https://login.tailscale.com/admin/dns> → **HTTPS Certificates** section →
**Enable HTTPS**, and confirm the dialog (it warns that machine names become public in certificate
transparency logs — expected and normal). MagicDNS is already on, nothing else is needed.

Then the serve command works as written; first cert takes 30–60 s.

- Tailnet `taila9d64b.ts.net` · this machine `sams-pc.taila9d64b.ts.net` (100.73.179.27)
- Tailscale 1.102.2, already installed and logged in
- On the tailnet: `sams-pc`, `sams-macbook-pro`, `iphone182` — the phone can already reach this
  desktop, it just needs TLS

## Running everything after a reboot

```bash
# 1. relay — from the repo, on the tunnel branch
cd server
go build -o relay.exe .
./relay.exe -tcp 127.0.0.1:25565 -ws 127.0.0.1:8971

# 2. playit agent — Windows service, should already be up
"/c/Program Files/playit_gg/bin/playit.exe" status     # expect Phase: running
"/c/Program Files/playit_gg/bin/playit.exe" start      # only if it is not

# 3. tailscale serve — persists once set; run again only if serve status is empty
"/c/Program Files/Tailscale/tailscale.exe" serve --bg --https 8443 http://localhost:8971
"/c/Program Files/Tailscale/tailscale.exe" serve status
```

Sanity check the whole chain without the browser:

```bash
./fakehost.exe -relay ws://localhost:8971/host &
./echotest.exe 127.0.0.1:25565                                  # local
./echotest.exe -mc -timeout 25s olds-hunger.tun.ply.gg:39539    # through the internet
```

## Notes

**Ports.** Relay TCP `25565`, relay HTTP/WS `8971`, Tailscale HTTPS `8443`. The relay binds to
`127.0.0.1` deliberately: the playit agent and `tailscale serve` both run on this same desktop and
reach it over loopback, so it never needs LAN exposure — and Windows Firewall never prompts.
Defaults in the binary remain `:25565` / `:8971` per spec; the localhost bind is a run-time flag.

**Windows Firewall.** No firewall dialog was ever shown or accepted, for the reason above.

## Deviations from the spec

None in the protocol. Three things worth knowing:

1. **`"host"` in `/health` means *handshake complete*, not merely *socket connected*.** It flips
   true after the tab's `hello` is answered with `ready`, which is the point the relay will
   actually accept clients for it. The window between TCP connect and `hello` is sub-second.
2. **A rejected second host is drained before its socket closes.** Writing the 1013 close frame
   and immediately closing left the peer's unread `hello` in the receive buffer, and Windows turns
   that into an RST — which discards the close frame, so the second tab saw a dropped connection
   instead of `1013`. The relay now reads until the peer's own close (2 s cap) before closing.
   Without this the page cannot distinguish "already hosted" from "relay is down".
3. **`echotest` gained a `-mc` flag** that was not in the brief, for the playit protocol-inspection
   reason above. The brief's `echotest` behaviour is unchanged by default.

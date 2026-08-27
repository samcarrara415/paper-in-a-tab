# Tunnel status

State of the relay/tunnel side as of 2026-08-26. Host machine: **`sams-pc`** (Windows 11).
Everything in the brief is built, wired up, and verified end to end. Nothing is blocked.

## TL;DR for the page side

| Thing | Value |
|---|---|
| **Host WebSocket URL for the tab** | **`wss://sams-pc.taila9d64b.ts.net:8443/host`** |
| **Public Minecraft address** | **`olds-hunger.tun.ply.gg:39539`** |
| Health URL (tailnet, valid TLS) | `https://sams-pc.taila9d64b.ts.net:8443/health` |
| Local host WebSocket | `ws://localhost:8971/host` |
| Local health | `http://localhost:8971/health` |

The `wss://` URL is reachable from any device on the tailnet, including the phone. TLS is a real
Let's Encrypt cert provisioned by Tailscale, so an `https://` page can open it without mixed-content
or cert complaints.

## Verified

Relay run as `-tcp 127.0.0.1:25565 -ws 127.0.0.1:8971`.

| Test | Result |
|---|---|
| `echotest` → relay → `fakehost` → back (local) | ✅ `OK echo matched` |
| `echotest -mc` → **playit** → relay → `fakehost` → back (public internet) | ✅ 31 bytes, `OK echo matched` |
| **Full production topology**: public playit → relay → **`wss://` over Tailscale** → host | ✅ 31 bytes, `OK echo matched` |
| `/health` over TLS via Tailscale | ✅ `{"conns":0,"host":true}`, no cert warnings |
| Second `/host` **over `wss://`** | ✅ `code 1013 "host already connected"`, first host unaffected |
| Second `/host` over plain `ws://` | ✅ same |
| Kill the host while a client is held open | ✅ `OK peer closed the connection (EOF)`, health → `{"conns":0,"host":false}` |
| TCP client with no host connected | ✅ accepted then immediately aborted |
| `/health` connection counting | ✅ `{"conns":1,"host":true}` with one client |

The close code **1013 survives the Tailscale TLS proxy intact**, which matters — the page relies on
distinguishing "already hosted" from "relay is down".

## Tailscale

```bash
tailscale serve --bg --https 8443 http://localhost:8971
```

That exact command, run once. It persists across reboots; `tailscale serve status` shows:

```
https://sams-pc.taila9d64b.ts.net:8443 (tailnet only)
|-- / proxy http://localhost:8971
```

To undo: `tailscale serve --https=8443 off`.

- Tailnet `taila9d64b.ts.net` · this machine `sams-pc.taila9d64b.ts.net` (100.73.179.27)
- Tailscale 1.102.2
- On the tailnet: `sams-pc`, `sams-macbook-pro`, `iphone182`

**This needed HTTPS certificates enabled for the tailnet** (admin console → DNS → HTTPS
Certificates). Until that was on, `tailscale cert` returned `500: your Tailscale account does not
support getting TLS certs` and `tailscale serve --https` would hang with no output and write no
config. If the `wss://` URL ever stops working with that symptom, check that toggle first.

## playit.gg

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
nothing whatsoever, which looks exactly like a broken relay and is not one. This cost real time to
pin down; it is why `echotest` has a `-mc` flag that sends a real handshake + status request.

```bash
./echotest.exe -mc -timeout 25s olds-hunger.tun.ply.gg:39539     # public: needs -mc
./echotest.exe 127.0.0.1:25565                                   # local: -mc optional
```

Real Minecraft clients are unaffected — they send a real handshake by definition.

## Running everything after a reboot

playit and Tailscale both restore themselves. Only the relay needs starting:

```bash
cd server
go build -o relay.exe .
./relay.exe -tcp 127.0.0.1:25565 -ws 127.0.0.1:8971
```

Confirm the other two are up:

```bash
"/c/Program Files/playit_gg/bin/playit.exe" status              # expect Phase: running
"/c/Program Files/Tailscale/tailscale.exe" serve status         # expect the 8443 proxy line
```

Sanity check the whole chain without the browser:

```bash
./fakehost.exe -relay wss://sams-pc.taila9d64b.ts.net:8443/host &
./echotest.exe -mc -timeout 25s olds-hunger.tun.ply.gg:39539
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

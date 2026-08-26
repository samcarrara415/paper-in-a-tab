# Tunnel status

State of the relay/tunnel side as of 2026-08-26. Host machine: **`sams-pc`** (Windows 11).

## TL;DR for the page side

| Thing | Value | State |
|---|---|---|
| Host WebSocket URL for the tab | `wss://sams-pc.taila9d64b.ts.net:8443/host` | ⛔ **blocked** — see [Tailscale](#tailscale--blocked) |
| Health URL | `https://sams-pc.taila9d64b.ts.net:8443/health` | ⛔ blocked, same cause |
| Public Minecraft address | not yet assigned | ⛔ **blocked** — see [playit](#playitgg--blocked) |
| Local host WebSocket (works now) | `ws://localhost:8971/host` | ✅ working |
| Local health (works now) | `http://localhost:8971/health` | ✅ working |

**The relay itself is finished and fully tested.** Both blocked items are account-level toggles
in someone else's web console — neither is a code problem, and neither changes the protocol.
Build the page half against `ws://localhost:8971/host` now; the only thing that changes when the
blockers clear is the scheme and host in the URL.

## What is verified working

The relay, built from `server/`, passes every test in the brief. Run against
`-tcp 127.0.0.1:25565 -ws 127.0.0.1:8971`:

| Test | Result |
|---|---|
| `echotest` → relay → `fakehost` → back | ✅ `got 6 byte(s): "hello\n"` / `OK echo matched` |
| Second connection to `/host` | ✅ `closed by relay, code 1013 "host already connected"` — first host unaffected |
| Kill `fakehost` while a client is held open | ✅ `OK peer closed the connection (EOF)`, health returns to `{"conns":0,"host":false}` |
| TCP client with no host connected | ✅ accepted then immediately aborted |
| `/health` connection counting | ✅ `{"conns":1,"host":true}` with one client, `{"conns":0,"host":false}` after |

## Blockers

### Tailscale — blocked

`tailscale serve --bg --https 8443 http://localhost:8971` hangs with no output and writes no
serve config. Cause, confirmed directly:

```
$ tailscale cert sams-pc.taila9d64b.ts.net
500 Internal Server Error: your Tailscale account does not support getting TLS certs
```

HTTPS certificates are not enabled for the tailnet, so `serve` can never provision a cert.

**What you need to click:** open <https://login.tailscale.com/admin/dns>, scroll to the
**HTTPS Certificates** section, and press **Enable HTTPS**. Confirm the dialog (it warns that
your machine names become public in certificate transparency logs — that is expected and normal).
MagicDNS is already on, so nothing else is needed.

Once that is done, the serve command should succeed as written. Cert provisioning takes 30–60 s
on first run.

- Tailnet: `taila9d64b.ts.net` · this machine's MagicDNS name: `sams-pc.taila9d64b.ts.net`
- Devices seen on the tailnet: `sams-pc`, `sams-macbook-pro`, `iphone182` — so the phone can
  already reach this desktop once TLS is available.

### playit.gg — blocked

An agent was already claimed on this machine (secret dated 2026-05-13, stored at
`C:\ProgramData\playit_gg\playit.toml`), so there is **no claim URL to hand over** — the account
exists. The agent was upgraded to 1.0.10 and the service starts, but refuses to run:

```
Phase: disabled over limit
The playit service cannot start because this account is over the agent limit.
```

**What you need to click:** open <https://playit.gg/account/agents> and delete an agent you no
longer use (or upgrade at <https://playit.gg/account/upgrade>). The service retries on its own
once there is room.

After that, create a **Minecraft Java (TCP)** tunnel pointing at `localhost:25565` and the public
address gets recorded here.

## Running everything after a reboot

Nothing here is installed as an auto-start service except playit, so after a reboot:

```bash
# 1. relay — from the repo, on the tunnel branch
cd server
go build -o relay.exe .
./relay.exe -tcp 127.0.0.1:25565 -ws 127.0.0.1:8971

# 2. playit agent (Windows service, survives reboot once it is under the agent limit)
"/c/Program Files/playit_gg/bin/playit.exe" start
"/c/Program Files/playit_gg/bin/playit.exe" status     # expect Phase: online

# 3. tailscale serve (persists across reboots once set; --bg makes it a stored config)
"/c/Program Files/Tailscale/tailscale.exe" serve --bg --https 8443 http://localhost:8971
"/c/Program Files/Tailscale/tailscale.exe" serve status
```

Sanity check the whole chain without the browser:

```bash
./fakehost.exe -relay ws://localhost:8971/host &
./echotest.exe 127.0.0.1:25565                 # local
./echotest.exe -timeout 20s <public-playit-address>   # through the internet
```

## Notes

**Ports.** Relay TCP `25565`, relay HTTP/WS `8971`, Tailscale HTTPS `8443`. The relay is bound to
`127.0.0.1` deliberately: the playit agent and `tailscale serve` both run on this same desktop and
reach it over loopback, so it never needs LAN exposure — and Windows Firewall never prompts.
Defaults in the binary remain `:25565` / `:8971` per spec; the localhost bind is a run-time flag.

**Windows Firewall.** No firewall dialog has been accepted. None appeared, because of the
loopback bind above and because the playit agent only makes outbound connections.

## Deviations from the spec

None in the protocol. Two things worth knowing:

1. **`"host"` in `/health` means *handshake complete*, not merely *socket connected*.** It flips
   true after the tab's `hello` is answered with `ready`, which is the point the relay will
   actually accept clients for it. The window between TCP connect and `hello` is sub-second.
2. **A rejected second host is drained before its socket closes.** Writing the 1013 close frame
   and immediately closing left unread bytes in the receive buffer, which makes Windows send an
   RST — and the RST threw away the close frame, so the second tab saw a dropped connection
   instead of `1013`. The relay now reads until the peer's own close (2 s cap) before closing.
   Without this the page cannot distinguish "already hosted" from "relay is down".

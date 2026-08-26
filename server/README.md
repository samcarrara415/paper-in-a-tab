# relay — TCP ⇄ WebSocket bridge for paper-in-a-tab

The Paper server runs inside a browser tab, and a tab cannot listen on a TCP port. This relay
gives it one. Real Minecraft Java clients connect over TCP; the tab dials **out** to the relay
over a WebSocket and receives their bytes.

```
MC client --TCP--> playit.gg --TCP--> relay (this desktop) --WebSocket--> browser tab (phone)
```

The tab is the *client* of the WebSocket, not the server — that is what makes this work from a
phone with no inbound connectivity.

## Build

Needs Go (1.21+; developed on 1.26.7). One dependency, `github.com/gorilla/websocket`.

```bash
cd server
go build -o relay.exe .
go build -o fakehost.exe ./cmd/fakehost
go build -o echotest.exe ./cmd/echotest
```

On Linux/macOS drop the `.exe`. `relay` is a single static binary with no runtime deps.

## Run

```bash
./relay.exe                                            # :25565 TCP, :8971 HTTP/WS
./relay.exe -tcp 127.0.0.1:25565 -ws 127.0.0.1:8971    # localhost-only (recommended)
```

| Flag  | Env         | Default   | Purpose                                  |
|-------|-------------|-----------|------------------------------------------|
| `-tcp`| `RELAY_TCP` | `:25565`  | TCP listener for Minecraft clients        |
| `-ws` | `RELAY_WS`  | `:8971`   | HTTP + WebSocket listener for the tab     |

**Bind to `127.0.0.1` when you can.** Everything that talks to the relay runs on the same
machine — the playit agent connects to `localhost:25565`, `tailscale serve` proxies to
`localhost:8971` — so the relay never needs to be reachable on the LAN. It also means Windows
Firewall never prompts.

No TLS and no auth, on purpose. Tailscale terminates TLS in front of the WebSocket port
(see `TUNNEL-STATUS.md`); public `wss://` for arbitrary visitors is a later VPS job.

## Protocol

The relay is a dumb pipe. It never inspects or rewrites Minecraft protocol bytes.

### HTTP

```
GET /health -> 200 {"host": <bool: tab connected>, "conns": <int: open connections>}
WS  /host   -> the browser tab connects here
```

`/health` sends `Access-Control-Allow-Origin: *` so the page can poll it cross-origin.

Exactly **one** host at a time. A second connection to `/host` is closed with
**close code 1013** (Try Again Later) and reason `"host already connected"`; the first host is
left untouched. While no host is connected, incoming TCP clients are accepted and immediately
closed.

### Control messages — WebSocket TEXT frames, JSON

| Direction    | Message                          | Meaning                        |
|--------------|----------------------------------|--------------------------------|
| tab → relay  | `{"t":"hello","proto":1}`        | first frame after connecting   |
| relay → tab  | `{"t":"ready"}`                  | handshake accepted             |
| relay → tab  | `{"t":"open","id":<uint32>}`     | a Minecraft client connected   |
| either way   | `{"t":"close","id":<uint32>}`    | close that one connection      |

The tab must send `hello` within 10 seconds or the relay drops it. A `proto` mismatch is logged
as a warning but not fatal. Connection ids start at 1 and increase; they are never reused within
a relay process.

### Data — WebSocket BINARY frames, both directions

```
[ 4 bytes: connection id, big-endian uint32 ][ raw TCP payload bytes ]
```

Payload bytes are passed through verbatim. Frames carry up to 32 KiB of payload. Binary frames
shorter than 4 bytes, or naming an unknown id, are dropped silently — that id's socket is
already gone.

### Lifecycle

- A Minecraft client connects → relay allocates an id, sends `{"t":"open","id":N}`.
- The client disconnects → relay sends `{"t":"close","id":N}`.
- The tab sends `{"t":"close","id":N}` → relay closes that TCP socket and does **not** echo a
  `close` back for it.
- The host WebSocket drops → relay closes **every** client socket and reports `"host": false`.

## Tests

Two helper commands stand in for the browser side, so the relay can be proven end to end with
no page involved.

- `cmd/fakehost` — connects to `/host` as a fake tab, does the handshake, echoes every binary
  payload back on the same connection id.
- `cmd/echotest` — TCP client: connects to a `host:port`, sends `hello\n`, checks the reply.
  `-hold <dur>` keeps the socket open afterwards and asserts the far end closes it.
  `-mc` sends a real Minecraft handshake instead of `hello\n` — **required when testing through
  the public playit address**, see below.

```bash
./relay.exe -tcp 127.0.0.1:25565 -ws 127.0.0.1:8971 &
./fakehost.exe -relay ws://localhost:8971/host &

# 1. echo round-trips through relay + fakehost
./echotest.exe 127.0.0.1:25565                  # -> OK echo matched

# 2. a second host is refused
./fakehost.exe -relay ws://localhost:8971/host  # -> closed by relay, code 1013

# 3. killing the host drops live clients
./echotest.exe -hold 20s 127.0.0.1:25565 &      # -> OK peer closed the connection
taskkill //F //IM fakehost.exe

# 4. with no host, clients are refused immediately
./echotest.exe 127.0.0.1:25565                  # -> FAIL read: connection aborted
```

`echotest` exits non-zero on failure, so these script cleanly.

To test through the public address, pass the playit hostname **and `-mc`**:

```bash
./echotest.exe -mc -timeout 25s olds-hunger.tun.ply.gg:39539
```

`-mc` is not optional there. A playit **Minecraft Java** tunnel is protocol-aware: its edge reads
the client's handshake packet to route the connection, and silently drops connections whose first
bytes are not Minecraft. Without `-mc` the connection is accepted by the edge and then killed
before it ever reaches the agent — the relay logs nothing at all, which looks like a relay fault
and is not one. With `-mc` the same 31 bytes round-trip cleanly.

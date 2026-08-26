// Command relay bridges real Minecraft Java clients to a Paper server running
// inside a browser tab.
//
//	MC client --TCP--> relay --WebSocket--> browser tab (the tab dials OUT to us)
//
// The tab connects to /host and becomes "the host". Every TCP client that
// arrives is assigned a uint32 id; bytes are forwarded verbatim in both
// directions as WebSocket binary frames prefixed with that id. Control events
// (open/close) are JSON text frames. See README.md for the wire protocol.
package main

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Protocol constants.
const (
	protoVersion = 1
	// idLen is the big-endian uint32 connection id that prefixes every
	// binary frame's payload.
	idLen = 4
	// readBuf caps how much we lift off a TCP socket per WebSocket frame.
	readBuf = 32 * 1024
	// helloWait is how long a freshly connected host has to send {"t":"hello"}.
	helloWait = 10 * time.Second
	// rejectDrain is how long a rejected surplus host is drained for, so its
	// close frame lands before the socket goes away. See rejectHost.
	rejectDrain = 2 * time.Second
)

// ctrl is every JSON control message, in both directions. ID is a pointer so
// open/close can carry id 0 while hello/ready omit the field entirely.
type ctrl struct {
	T     string  `json:"t"`
	ID    *uint32 `json:"id,omitempty"`
	Proto int     `json:"proto,omitempty"`
}

// host is the single connected browser tab. gorilla/websocket allows only one
// concurrent writer, so every send goes through wmu.
type host struct {
	ws    *websocket.Conn
	wmu   sync.Mutex
	ready bool // set once the hello/ready handshake completes
}

func (h *host) sendCtrl(m ctrl) error {
	b, err := json.Marshal(m)
	if err != nil {
		return err
	}
	h.wmu.Lock()
	defer h.wmu.Unlock()
	return h.ws.WriteMessage(websocket.TextMessage, b)
}

// sendData writes one binary frame: 4-byte big-endian id, then payload verbatim.
func (h *host) sendData(id uint32, payload []byte) error {
	frame := make([]byte, idLen+len(payload))
	binary.BigEndian.PutUint32(frame[:idLen], id)
	copy(frame[idLen:], payload)
	h.wmu.Lock()
	defer h.wmu.Unlock()
	return h.ws.WriteMessage(websocket.BinaryMessage, frame)
}

// client is one live Minecraft TCP connection.
type client struct {
	id  uint32
	tcp net.Conn
	// byTab marks a socket the tab asked us to close, so the TCP reader that
	// is about to fail does not bounce a redundant close back at the tab.
	byTab bool
}

type relay struct {
	mu     sync.Mutex
	h      *host
	conns  map[uint32]*client
	nextID uint32
}

func newRelay() *relay {
	return &relay{conns: make(map[uint32]*client), nextID: 1}
}

// currentHost returns the host only if it has finished the handshake.
func (r *relay) currentHost() *host {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.h != nil && r.h.ready {
		return r.h
	}
	return nil
}

func (r *relay) handleHealth(w http.ResponseWriter, req *http.Request) {
	r.mu.Lock()
	connected := r.h != nil && r.h.ready
	n := len(r.conns)
	r.mu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	json.NewEncoder(w).Encode(map[string]any{"host": connected, "conns": n})
}

// upgrader accepts any Origin: the page is served from GitHub Pages (or a
// phone) while the relay lives on the tailnet, so same-origin never holds.
// There is no auth in v1 and nothing here is reachable off the tailnet.
var upgrader = websocket.Upgrader{
	ReadBufferSize:  readBuf,
	WriteBufferSize: readBuf,
	CheckOrigin:     func(*http.Request) bool { return true },
}

func (r *relay) handleHost(w http.ResponseWriter, req *http.Request) {
	// Check the host slot before upgrading so a second tab is turned away
	// with 1013 (Try Again Later).
	r.mu.Lock()
	taken := r.h != nil
	r.mu.Unlock()

	ws, err := upgrader.Upgrade(w, req, nil)
	if err != nil {
		log.Printf("host: upgrade failed: %v", err)
		return
	}
	if taken {
		log.Printf("host: rejecting second host from %s (1013)", req.RemoteAddr)
		rejectHost(ws)
		return
	}

	h := &host{ws: ws}
	r.mu.Lock()
	if r.h != nil { // lost the race between the check above and here
		r.mu.Unlock()
		log.Printf("host: rejecting second host from %s (1013)", req.RemoteAddr)
		rejectHost(ws)
		return
	}
	r.h = h
	r.mu.Unlock()

	log.Printf("host: connected from %s", req.RemoteAddr)
	defer r.dropHost(h)

	if err := r.handshake(h); err != nil {
		log.Printf("host: handshake failed: %v", err)
		return
	}
	log.Printf("host: ready")
	r.hostLoop(h)
}

// rejectHost turns away a surplus host with close code 1013.
func rejectHost(ws *websocket.Conn) {
	ws.WriteControl(websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.CloseTryAgainLater, "host already connected"),
		time.Now().Add(time.Second))
	// Drain before closing. The rejected tab has almost certainly already sent
	// its hello; closing a socket with unread inbound bytes makes Windows send
	// an RST, and the RST discards the close frame we just queued -- so the tab
	// would see a dropped connection instead of the 1013 it needs to act on.
	// Reading until the peer's own close (or the deadline) avoids that.
	ws.SetReadDeadline(time.Now().Add(rejectDrain))
	for {
		if _, _, err := ws.ReadMessage(); err != nil {
			break
		}
	}
	ws.Close()
}

// handshake waits for the tab's hello and answers ready.
func (r *relay) handshake(h *host) error {
	h.ws.SetReadDeadline(time.Now().Add(helloWait))
	typ, data, err := h.ws.ReadMessage()
	if err != nil {
		return err
	}
	h.ws.SetReadDeadline(time.Time{})
	if typ != websocket.TextMessage {
		return errors.New("first message was not text")
	}
	var m ctrl
	if err := json.Unmarshal(data, &m); err != nil {
		return err
	}
	if m.T != "hello" {
		return errors.New("first message was not hello: " + m.T)
	}
	if m.Proto != protoVersion {
		log.Printf("host: warning: tab speaks proto %d, relay speaks %d", m.Proto, protoVersion)
	}
	if err := h.sendCtrl(ctrl{T: "ready"}); err != nil {
		return err
	}
	r.mu.Lock()
	h.ready = true
	r.mu.Unlock()
	return nil
}

// hostLoop pumps frames from the tab until the socket dies.
func (r *relay) hostLoop(h *host) {
	for {
		typ, data, err := h.ws.ReadMessage()
		if err != nil {
			log.Printf("host: disconnected: %v", err)
			return
		}
		switch typ {
		case websocket.TextMessage:
			var m ctrl
			if err := json.Unmarshal(data, &m); err != nil {
				log.Printf("host: bad control frame: %v", err)
				continue
			}
			if m.T == "close" && m.ID != nil {
				r.closeFromTab(*m.ID)
			}
		case websocket.BinaryMessage:
			if len(data) < idLen {
				log.Printf("host: short binary frame (%d bytes)", len(data))
				continue
			}
			id := binary.BigEndian.Uint32(data[:idLen])
			r.mu.Lock()
			c := r.conns[id]
			r.mu.Unlock()
			if c == nil {
				continue // client already gone; drop the bytes
			}
			if _, err := c.tcp.Write(data[idLen:]); err != nil {
				r.closeFromTab(id) // socket is dead; tear it down quietly
			}
		}
	}
}

// closeFromTab closes one TCP socket at the tab's request, without echoing a
// close back for it.
func (r *relay) closeFromTab(id uint32) {
	r.mu.Lock()
	c := r.conns[id]
	if c != nil {
		c.byTab = true
		delete(r.conns, id)
	}
	r.mu.Unlock()
	if c != nil {
		log.Printf("conn %d: closed by tab", id)
		c.tcp.Close()
	}
}

// dropHost clears the host slot and closes every client socket with it.
func (r *relay) dropHost(h *host) {
	r.mu.Lock()
	if r.h != h {
		r.mu.Unlock()
		return
	}
	r.h = nil
	victims := make([]*client, 0, len(r.conns))
	for _, c := range r.conns {
		c.byTab = true // host is gone; nobody left to notify
		victims = append(victims, c)
	}
	r.conns = make(map[uint32]*client)
	r.mu.Unlock()

	h.ws.Close()
	for _, c := range victims {
		c.tcp.Close()
	}
	if len(victims) > 0 {
		log.Printf("host: gone, dropped %d client(s)", len(victims))
	}
}

// handleTCP owns one Minecraft client for its whole life.
func (r *relay) handleTCP(tcp net.Conn) {
	h := r.currentHost()
	if h == nil {
		log.Printf("tcp: refusing %s, no host connected", tcp.RemoteAddr())
		tcp.Close()
		return
	}

	r.mu.Lock()
	id := r.nextID
	r.nextID++
	c := &client{id: id, tcp: tcp}
	r.conns[id] = c
	r.mu.Unlock()

	log.Printf("conn %d: open from %s", id, tcp.RemoteAddr())
	if err := h.sendCtrl(ctrl{T: "open", ID: &id}); err != nil {
		log.Printf("conn %d: could not announce: %v", id, err)
		r.reapClient(c, false)
		return
	}

	// TCP -> tab.
	buf := make([]byte, readBuf)
	for {
		n, err := tcp.Read(buf)
		if n > 0 {
			if werr := h.sendData(id, buf[:n]); werr != nil {
				log.Printf("conn %d: host write failed: %v", id, werr)
				break
			}
		}
		if err != nil {
			if err != io.EOF {
				log.Printf("conn %d: read: %v", id, err)
			}
			break
		}
	}
	r.reapClient(c, true)
}

// reapClient removes a client and, unless the tab initiated the close, tells
// the tab the socket is gone.
func (r *relay) reapClient(c *client, notify bool) {
	r.mu.Lock()
	byTab := c.byTab
	if _, live := r.conns[c.id]; live {
		delete(r.conns, c.id)
	} else {
		notify = false // already reaped by closeFromTab or dropHost
	}
	h := r.h
	ready := h != nil && h.ready
	r.mu.Unlock()

	c.tcp.Close()
	if notify && !byTab && ready {
		log.Printf("conn %d: closed", c.id)
		h.sendCtrl(ctrl{T: "close", ID: &c.id})
	}
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func main() {
	tcpAddr := flag.String("tcp", env("RELAY_TCP", ":25565"), "TCP listen address for Minecraft clients")
	wsAddr := flag.String("ws", env("RELAY_WS", ":8971"), "HTTP/WebSocket listen address for the browser tab")
	flag.Parse()

	r := newRelay()

	mux := http.NewServeMux()
	mux.HandleFunc("/health", r.handleHealth)
	mux.HandleFunc("/host", r.handleHost)

	ln, err := net.Listen("tcp", *tcpAddr)
	if err != nil {
		log.Fatalf("tcp listen %s: %v", *tcpAddr, err)
	}
	log.Printf("relay: minecraft TCP on %s", *tcpAddr)
	log.Printf("relay: host websocket on %s (path /host, health /health)", *wsAddr)

	go func() {
		for {
			tcp, err := ln.Accept()
			if err != nil {
				log.Fatalf("tcp accept: %v", err)
			}
			go r.handleTCP(tcp)
		}
	}()

	if err := http.ListenAndServe(*wsAddr, mux); err != nil {
		log.Fatalf("http listen %s: %v", *wsAddr, err)
	}
}

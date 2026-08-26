// Command fakehost stands in for the browser tab so the relay can be tested
// without the page side. It connects to the relay's /host endpoint, completes
// the hello/ready handshake, and echoes every binary payload straight back on
// the same connection id.
package main

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/url"
	"os"

	"github.com/gorilla/websocket"
)

type ctrl struct {
	T     string  `json:"t"`
	ID    *uint32 `json:"id,omitempty"`
	Proto int     `json:"proto,omitempty"`
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

// describe spells out a WebSocket close code when there is one, so "rejected
// with 1013" is visible in the log instead of a bare socket error.
func describe(err error) string {
	var ce *websocket.CloseError
	if errors.As(err, &ce) {
		return fmt.Sprintf("closed by relay, code %d %q", ce.Code, ce.Text)
	}
	return err.Error()
}

func main() {
	addr := flag.String("relay", env("RELAY_HOST_URL", "ws://localhost:8971/host"), "relay /host WebSocket URL")
	flag.Parse()

	u, err := url.Parse(*addr)
	if err != nil {
		log.Fatalf("bad url %q: %v", *addr, err)
	}

	ws, resp, err := websocket.DefaultDialer.Dial(u.String(), nil)
	if err != nil {
		if resp != nil {
			log.Fatalf("dial %s: %v (http %s)", u, err, resp.Status)
		}
		log.Fatalf("dial %s: %v", u, err)
	}
	defer ws.Close()
	log.Printf("fakehost: connected to %s", u)

	hello, _ := json.Marshal(ctrl{T: "hello", Proto: 1})
	if err := ws.WriteMessage(websocket.TextMessage, hello); err != nil {
		log.Fatalf("send hello: %v", err)
	}

	typ, data, err := ws.ReadMessage()
	if err != nil {
		// A second fakehost is expected to land here: the relay allows exactly
		// one host and turns the rest away with 1013.
		log.Fatalf("await ready: %s", describe(err))
	}
	if typ != websocket.TextMessage {
		log.Fatalf("await ready: got binary frame")
	}
	var m ctrl
	if err := json.Unmarshal(data, &m); err != nil || m.T != "ready" {
		log.Fatalf("await ready: got %q", data)
	}
	log.Printf("fakehost: ready, echoing")

	for {
		typ, data, err := ws.ReadMessage()
		if err != nil {
			log.Printf("fakehost: disconnected: %s", describe(err))
			return
		}
		switch typ {
		case websocket.TextMessage:
			var m ctrl
			if err := json.Unmarshal(data, &m); err != nil {
				log.Printf("fakehost: bad control frame: %s", data)
				continue
			}
			id := uint32(0)
			if m.ID != nil {
				id = *m.ID
			}
			log.Printf("fakehost: ctrl %s id=%d", m.T, id)
		case websocket.BinaryMessage:
			if len(data) < 4 {
				log.Printf("fakehost: short binary frame (%d bytes)", len(data))
				continue
			}
			id := binary.BigEndian.Uint32(data[:4])
			log.Printf("fakehost: echo %d byte(s) on conn %d", len(data)-4, id)
			// The frame already carries the right id prefix, so echoing it
			// verbatim sends the payload back on the same connection.
			if err := ws.WriteMessage(websocket.BinaryMessage, data); err != nil {
				log.Printf("fakehost: echo failed: %v", err)
				return
			}
		}
	}
}

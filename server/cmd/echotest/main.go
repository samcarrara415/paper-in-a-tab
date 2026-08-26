// Command echotest is a tiny TCP client for exercising the relay end to end.
// It connects to a host:port, sends a message, and prints what comes back.
//
//	echotest localhost:25565
//	echotest -hold 30s bluewave.gl.joinmc.link:12345
//
// With -hold it stays connected after the echo and reports when the far end
// drops the socket, which is how the "killing fakehost drops the client" case
// is checked. Exit status is non-zero on any failure so it scripts cleanly.
package main

import (
	"bytes"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"time"
)

// appendVarInt appends a Minecraft protocol VarInt.
func appendVarInt(b []byte, v int32) []byte {
	u := uint32(v)
	for {
		if u&^0x7f == 0 {
			return append(b, byte(u))
		}
		b = append(b, byte(u&0x7f|0x80))
		u >>= 7
	}
}

// mcPing builds the opening bytes of a Minecraft Java "server list ping": a
// handshake packet with next-state=status, followed by a status request.
//
// This matters through playit. A Minecraft Java tunnel is protocol-aware --
// its edge reads the client's handshake to decide where to route -- and it
// drops connections whose first packet is not Minecraft. A plain "hello\n"
// gets killed at the edge and never reaches the relay, so -mc is what to use
// when testing against the public address.
func mcPing(host string, port uint16) []byte {
	var pkt []byte
	pkt = append(pkt, 0x00) // packet id: handshake
	pkt = appendVarInt(pkt, 47)
	pkt = appendVarInt(pkt, int32(len(host)))
	pkt = append(pkt, host...)
	pkt = append(pkt, byte(port>>8), byte(port))
	pkt = appendVarInt(pkt, 1) // next state: status

	out := appendVarInt(nil, int32(len(pkt)))
	out = append(out, pkt...)
	out = append(out, 0x01, 0x00) // status request: length 1, packet id 0
	return out
}

func main() {
	msg := flag.String("msg", "hello\n", "message to send")
	mc := flag.Bool("mc", false, "send a real Minecraft handshake instead of -msg (required through a playit Minecraft tunnel)")
	timeout := flag.Duration("timeout", 10*time.Second, "dial and read timeout")
	hold := flag.Duration("hold", 0, "after the echo, wait this long for the peer to close")
	flag.Parse()

	if flag.NArg() != 1 {
		fmt.Fprintln(os.Stderr, "usage: echotest [flags] host:port")
		flag.PrintDefaults()
		os.Exit(2)
	}
	addr := flag.Arg(0)

	sent := []byte(*msg)
	if *mc {
		host, portStr, err := net.SplitHostPort(addr)
		if err != nil {
			fmt.Fprintf(os.Stderr, "FAIL bad address %s: %v\n", addr, err)
			os.Exit(1)
		}
		port, err := strconv.ParseUint(portStr, 10, 16)
		if err != nil {
			fmt.Fprintf(os.Stderr, "FAIL bad port %s: %v\n", portStr, err)
			os.Exit(1)
		}
		sent = mcPing(host, uint16(port))
	}

	conn, err := net.DialTimeout("tcp", addr, *timeout)
	if err != nil {
		fmt.Fprintf(os.Stderr, "FAIL dial %s: %v\n", addr, err)
		os.Exit(1)
	}
	defer conn.Close()
	fmt.Printf("connected to %s (%s)\n", addr, conn.RemoteAddr())

	if _, err := conn.Write(sent); err != nil {
		fmt.Fprintf(os.Stderr, "FAIL write: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("sent %d byte(s)\n", len(sent))

	// Read exactly as many bytes as were sent: the echo can arrive split
	// across several TCP segments, especially through the public tunnel.
	conn.SetReadDeadline(time.Now().Add(*timeout))
	buf := make([]byte, len(sent))
	if _, err := io.ReadFull(conn, buf); err != nil {
		fmt.Fprintf(os.Stderr, "FAIL read: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("got %d byte(s): %q\n", len(buf), buf)
	if !bytes.Equal(buf, sent) {
		fmt.Fprintf(os.Stderr, "FAIL echo mismatch: sent %q got %q\n", sent, buf)
		os.Exit(1)
	}
	fmt.Println("OK echo matched")

	if *hold > 0 {
		fmt.Printf("holding up to %s for the peer to close...\n", *hold)
		conn.SetReadDeadline(time.Now().Add(*hold))
		_, err := conn.Read(buf)
		switch {
		case errors.Is(err, os.ErrDeadlineExceeded):
			fmt.Fprintln(os.Stderr, "FAIL still connected after hold, peer never closed")
			os.Exit(1)
		case err == io.EOF || err != nil:
			fmt.Printf("OK peer closed the connection (%v)\n", err)
		default:
			fmt.Fprintln(os.Stderr, "FAIL unexpected extra data during hold")
			os.Exit(1)
		}
	}
}

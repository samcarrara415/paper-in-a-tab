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
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"time"
)

func main() {
	msg := flag.String("msg", "hello\n", "message to send")
	timeout := flag.Duration("timeout", 10*time.Second, "dial and read timeout")
	hold := flag.Duration("hold", 0, "after the echo, wait this long for the peer to close")
	flag.Parse()

	if flag.NArg() != 1 {
		fmt.Fprintln(os.Stderr, "usage: echotest [flags] host:port")
		flag.PrintDefaults()
		os.Exit(2)
	}
	addr := flag.Arg(0)

	conn, err := net.DialTimeout("tcp", addr, *timeout)
	if err != nil {
		fmt.Fprintf(os.Stderr, "FAIL dial %s: %v\n", addr, err)
		os.Exit(1)
	}
	defer conn.Close()
	fmt.Printf("connected to %s (%s)\n", addr, conn.RemoteAddr())

	if _, err := conn.Write([]byte(*msg)); err != nil {
		fmt.Fprintf(os.Stderr, "FAIL write: %v\n", err)
		os.Exit(1)
	}

	conn.SetReadDeadline(time.Now().Add(*timeout))
	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		fmt.Fprintf(os.Stderr, "FAIL read: %v\n", err)
		os.Exit(1)
	}
	got := string(buf[:n])
	fmt.Printf("got %d byte(s): %q\n", n, got)
	if got != *msg {
		fmt.Fprintf(os.Stderr, "FAIL echo mismatch: sent %q got %q\n", *msg, got)
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

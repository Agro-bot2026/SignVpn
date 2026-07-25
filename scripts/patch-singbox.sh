#!/bin/bash
# Patch script for sing-box: Go 1.25 compat + custom protocol registration + HTTP Custom tunnel
set -e

SINGBOX=/tmp/sing-box

# ===== 1. Go 1.25 compatibility patches =====
rm -f $SINGBOX/experimental/libbox/pidfd_android.go
rm -rf $SINGBOX/experimental/libbox/internal/oomprofile
mkdir -p $SINGBOX/experimental/libbox/internal/oomprofile

cat > $SINGBOX/experimental/libbox/internal/oomprofile/linkname.go << 'GOEOF'
//go:build darwin || linux || windows
package oomprofile
import ("runtime"; "unsafe")

type memProfileRecord struct {
        AllocBytes, FreeBytes     int64
        AllocObjects, FreeObjects int64
        Stack0                    [32]uintptr
}
type blockProfileRecord struct {
        Count  int64
        Cycles int64
        Stack0 [32]uintptr
}
type stackRecord struct {
        Stack0 [32]uintptr
}

func WriteFile(destPath string, name string) (string, error) { return "", nil }
func runtimeMemProfileInternal(p []memProfileRecord, inuseZero bool) (n int, ok bool) { return 0, false }
func runtimeBlockProfileInternal(p []blockProfileRecord) (n int, ok bool) { return 0, false }
func runtimeMutexProfileInternal(p []blockProfileRecord) (n int, ok bool) { return 0, false }
func runtimeThreadCreateInternal(p []stackRecord) (n int, ok bool) { return 0, false }
func runtimeGoroutineProfileWithLabels(p []stackRecord, labels []unsafe.Pointer) (n int, ok bool) { return 0, false }
func runtimeCyclesPerSecond() int64 { return 0 }
func runtimeMakeProfStack() []uintptr { return nil }
func runtimeFrameStartLine(f *runtime.Frame) int { return 0 }
func runtimeFrameSymbolName(f *runtime.Frame) string { return "" }
func runtimeExpandFinalInlineFrame(stk []uintptr) []uintptr { return stk }
func stdParseProcSelfMaps(data []byte, addMapping func(lo uint64, hi uint64, offset uint64, file string, buildID string)) {}
func stdELFBuildID(file string) (string, error) { return "", nil }
GOEOF

# ===== 2. Register custom protocol types in constant/proxy.go =====
if ! grep -q "TypePayloadInject" $SINGBOX/constant/proxy.go 2>/dev/null; then
  sed -i '/^const (/,/^)/ {
    /TypeACME/a\
\tTypePayloadInject = "payloadinject"\n\tTypeBadVPN        = "badvpn"
  }' $SINGBOX/constant/proxy.go
  echo "[+] Added TypePayloadInject and TypeBadVPN constants"
fi

# ===== 3. Add custom option structs in the option package =====
cat > $SINGBOX/option/payloadinject.go << 'GOEOF'
package option

type PayloadInjectOutboundOptions struct {
	DialerOptions
	ServerOptions
	User       string `json:"user,omitempty"`
	Password   string `json:"password,omitempty"`
	CustomPayload string `json:"custom_payload,omitempty"`
	SkipBytes  int    `json:"skip_bytes,omitempty"`
}
GOEOF

cat > $SINGBOX/option/badvpn.go << 'GOEOF'
package option

type BadVPOutboundOptions struct {
	DialerOptions
	ServerOptions
	UDPGWPort int `json:"udpgw_port,omitempty"`
}
GOEOF
echo "[+] Added custom option structs"

# ===== 4. Import and register custom protocols in include/registry.go =====
REGISTRY=$SINGBOX/include/registry.go
if ! grep -q "payloadinject" "$REGISTRY" 2>/dev/null; then
  sed -i '/"github.com\/sagernet\/sing-box\/protocol\/ssh"/a\
\t"github.com/sagernet/sing-box/protocol/payloadinject"\n\t"github.com/sagernet/sing-box/protocol/badvpn"' "$REGISTRY"
  sed -i '/ssh.RegisterOutbound(registry)/a\
\tpayloadinject.RegisterOutbound(registry)\n\tbadvpn.RegisterOutbound(registry)' "$REGISTRY"
  echo "[+] Registered custom protocols in include/registry.go"
fi

# ===== 5. Add HTTP Custom tunnel in libbox =====
cat > $SINGBOX/experimental/libbox/httpcustom.go << 'GOEOF'
package libbox

import (
    "bufio"
    "bytes"
    "fmt"
    "math/rand"
    "net"
    "strings"
    "sync"
    "time"

    "crypto/tls"

    "golang.org/x/crypto/ssh"
)

var (
	activeSSHClient   *ssh.Client
	activeListener    net.Listener
	muTunnel          sync.Mutex
	stopTunnelCh      chan struct{}
)

//export StartHTTPCustomTunnel
func StartHTTPCustomTunnel(server string, port int, user string, password string, payload string, socksPort int, mode int) error {
    cb := &tunnelLogger{}
    return startTunnel(server, port, user, password, payload, socksPort, mode, cb)
}

//export StopHTTPCustomTunnel  
func StopHTTPCustomTunnel() {
    muTunnel.Lock()
    defer muTunnel.Unlock()
    if stopTunnelCh != nil {
        close(stopTunnelCh)
        stopTunnelCh = nil
    }
    if activeListener != nil {
        activeListener.Close()
        activeListener = nil
    }
    if activeSSHClient != nil {
        activeSSHClient.Close()
        activeSSHClient = nil
    }
}

type tunnelLogger struct{}
func (t *tunnelLogger) log(msg string) { fmt.Println("[HC]", msg) }
func (t *tunnelLogger) Log(msg string) { t.log(msg) }

func startTunnel(server string, port int, user, password, payload string, socksPort int, mode int, log interface{ Log(string) }) error {
    addr := fmt.Sprintf("%s:%d", server, port)
    
    // Modes:
    // 0 = SSH Direct: enviar payload, SSH directo (no leer HTTP)
    // 1 = SSH+Proxy: enviar payload, leer HTTP 200, luego SSH
    // 2 = SSH WebSocket: enviar payload WS, leer 101, luego SSH  
    // 3 = SSL+Proxy: TLS al proxy, enviar payload, leer 200, SSH
    // 4 = SSL Direct: TLS directo al server, SSH

    var conn net.Conn
    var err error

    if mode == 3 || mode == 4 {
        // TLS connection
        tlsCfg := &tls.Config{
            ServerName:         server,
            InsecureSkipVerify: true,
        }
        conn, err = tls.DialWithDialer(&net.Dialer{Timeout: 15 * time.Second}, "tcp", addr, tlsCfg)
    } else {
        conn, err = net.DialTimeout("tcp", addr, 15*time.Second)
    }
    if err != nil {
        return fmt.Errorf("conexion: %w", err)
    }

    rendered := renderPayload(payload, server, fmt.Sprintf("%d", port))
    conn.Write([]byte(rendered))

    if mode == 1 || mode == 3 {
        // SSH+Proxy o SSL+Proxy: leer HTTP 200
        if err := consumeHTTP(conn, 10*time.Second); err != nil {
            conn.Close()
            return fmt.Errorf("http: %w", err)
        }
    } else if mode == 2 {
        // SSH WebSocket: leer hasta obtener el banner SSH
        conn.SetReadDeadline(time.Now().Add(15 * time.Second))
        var buf bytes.Buffer
        var tmp [4096]byte
        for {
            n, err := conn.Read(tmp[:])
            if err != nil { break }
            buf.Write(tmp[:n])
            if bytes.Contains(buf.Bytes(), []byte("SSH-")) { break }
            if buf.Len() > 65536 { break }
        }
        conn.SetReadDeadline(time.Time{})
        resp := buf.String()
        if !strings.Contains(resp, "101") && !strings.Contains(resp, "200") {
            conn.Close()
            return fmt.Errorf("ws: se esperaba 101/200, got: %s", resp[:min(len(resp), 80)])
        }
        // Envolver conexion para reinyectar datos ya leidos
        conn = &prependConn{conn: conn, prepend: buf.Bytes()}
    }
    // mode 0 y 4: no leer respuesta

    sshCfg := &ssh.ClientConfig{
        User:            user,
        Auth:            []ssh.AuthMethod{ssh.Password(password)},
        HostKeyCallback: ssh.InsecureIgnoreHostKey(),
        Timeout:         15 * time.Second,
    }
    sshCon, chans, reqs, err := ssh.NewClientConn(conn, addr, sshCfg)
    if err != nil {
        conn.Close()
        return fmt.Errorf("ssh: %w", err)
    }
    client := ssh.NewClient(sshCon, chans, reqs)

    ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort))
    if err != nil {
        client.Close()
        return fmt.Errorf("socks: %w", err)
    }

    muTunnel.Lock()
    activeSSHClient = client
    activeListener = ln
    stopTunnelCh = make(chan struct{})
    muTunnel.Unlock()

    go func() {
        for {
            select {
            case <-stopTunnelCh:
                return
            default:
            }
            c, err := ln.Accept()
            if err != nil {
                return
            }
            go handleSocks5(c, client)
        }
    }()
    return nil
}

// prependConn wraps a connection and prepends already-read data
type prependConn struct {
    conn    net.Conn
    prepend []byte
    offset  int
}

func (c *prependConn) Read(b []byte) (int, error) {
    if c.offset < len(c.prepend) {
        n := copy(b, c.prepend[c.offset:])
        c.offset += n
        return n, nil
    }
    return c.conn.Read(b)
}
func (c *prependConn) Write(b []byte) (int, error)  { return c.conn.Write(b) }
func (c *prependConn) Close() error                  { return c.conn.Close() }
func (c *prependConn) LocalAddr() net.Addr           { return c.conn.LocalAddr() }
func (c *prependConn) RemoteAddr() net.Addr          { return c.conn.RemoteAddr() }
func (c *prependConn) SetDeadline(t time.Time) error  { return c.conn.SetDeadline(t) }
func (c *prependConn) SetReadDeadline(t time.Time) error  { return c.conn.SetReadDeadline(t) }
func (c *prependConn) SetWriteDeadline(t time.Time) error { return c.conn.SetWriteDeadline(t) }

func min(a, b int) int { if a < b { return a }; return b }

func renderPayload(template, host, port string) string {
    p := template
    rng := rand.New(rand.NewSource(time.Now().UnixNano()))
    for _, marker := range []string{"[rotate=", "[random="} {
        for {
            start := strings.Index(p, marker)
            if start == -1 { break }
            end := strings.Index(p[start:], "]")
            if end == -1 { break }
            end += start
            inner := p[start+len(marker):end]
            choices := strings.Split(inner, ";")
            p = p[:start] + choices[rng.Intn(len(choices))] + p[end+1:]
        }
    }
    reps := map[string]string{
        "[host]": host, "[port]": port, "[host_port]": host+":"+port,
        "[method]": "CONNECT", "[protocol]": "HTTP/1.0", "[ssh]": "22",
        "[crlf]": "\r\n", "[lf]": "\n", "[cr]": "\r", "[lfcr]": "\n\r",
        "[ua]": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
    }
    for k, v := range reps { p = strings.ReplaceAll(p, k, v) }
    return p
}

func consumeHTTP(conn net.Conn, timeout time.Duration) error {
    conn.SetReadDeadline(time.Now().Add(timeout))
    defer conn.SetReadDeadline(time.Time{})
    var buf [1]byte; var data strings.Builder; crlfCount := 0
    for {
        _, err := conn.Read(buf[:])
        if err != nil { return err }
        data.Write(buf[:])
        if buf[0]=='\r'||buf[0]=='\n' { crlfCount++ } else { crlfCount=0 }
        if crlfCount >= 4 { return nil }
        if data.Len() > 65536 { return nil }
    }
}

func handleSocks5(local net.Conn, client *ssh.Client) {
    defer local.Close()
    buf := make([]byte, 2)
    if _, err := local.Read(buf); err != nil { return }
    nMethods := int(buf[1])
    if nMethods > 0 { methods := make([]byte, nMethods); local.Read(methods) }
    local.Write([]byte{0x05, 0x00})
    header := make([]byte, 4)
    if _, err := local.Read(header); err != nil { return }
    atyp := header[3]
    var host string; var port int
    if atyp == 0x01 { ip := make([]byte,4); local.Read(ip); host = net.IP(ip).String() }
    if atyp == 0x03 { buf=make([]byte,1); local.Read(buf); l:=int(buf[0]); d:=make([]byte,l); local.Read(d); host=string(d) }
    buf=make([]byte,2); local.Read(buf); port=int(buf[0])<<8|int(buf[1])
    remote, err := client.Dial("tcp", fmt.Sprintf("%s:%d", host, port))
    if err != nil { local.Write([]byte{0x05,0x04,0x00,0x01,0,0,0,0,0,0}); return }
    defer remote.Close()
    local.Write([]byte{0x05,0x00,0x00,0x01,0,0,0,0,0,0})
    go func() { buf:=make([]byte,32768); for{n,_:=local.Read(buf);if n==0{return};remote.Write(buf[:n])} }()
    buf2:=make([]byte,32768); for{n,_:=remote.Read(buf2);if n==0{return};local.Write(buf2[:n])}
}
GOEOF
echo "[+] Added HTTP Custom tunnel in libbox"

echo "[+] Patch complete"

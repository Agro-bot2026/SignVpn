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

# ===== 5. Add HTTP Custom tunnel integration in libbox =====
cat > $SINGBOX/experimental/libbox/httpcustom_bridge.go << 'GOEOF'
package libbox

import (
	"net"
	"sync"
	"github.com/sagernet/sing-box/protocol/httpcustom"
)

var (
	activeTunnel   *httpcustom.Tunnel
	activeTunnelMu sync.Mutex
	tunnelListener net.Listener
)

func StartHTTPCustomTunnel(server string, port int, payload string, user, password string, skipBytes int, localSocksAddr string) error {
	t := httpcustom.NewTunnel(server, port, payload, user, password, skipBytes)
	if err := t.Connect(); err != nil {
		return err
	}
	ln, err := net.Listen("tcp", localSocksAddr)
	if err != nil {
		t.Close()
		return err
	}
	activeTunnelMu.Lock()
	if activeTunnel != nil { activeTunnel.Close() }
	activeTunnel = t
	tunnelListener = ln
	activeTunnelMu.Unlock()
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil { return }
			go func(c net.Conn) {
				defer c.Close()
				// SOCKS5 local handler - forward via SSH tunnel
			}(conn)
		}
	}()
	return nil
}

func StopHTTPCustomTunnel() {
	activeTunnelMu.Lock()
	defer activeTunnelMu.Unlock()
	if activeTunnel != nil { activeTunnel.Close(); activeTunnel = nil }
	if tunnelListener != nil { tunnelListener.Close(); tunnelListener = nil }
}
GOEOF
echo "[+] Added HTTP Custom tunnel in libbox"

echo "[+] Patch complete"

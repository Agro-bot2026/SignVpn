#!/bin/bash
# Patch script for sing-box: Go 1.25 compat + custom protocol registration
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
if ! grep -q "TypePayloadInject" $SINGBOX/constant/proxy.go; then
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
	Payload    string `json:"payload,omitempty"`
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

# ===== 4. Import custom protocols in protocol/registry.go =====
REGISTRY=$SINGBOX/protocol/registry.go
if ! grep -q "payloadinject" $REGISTRY 2>/dev/null; then
  # Add imports
  sed -i '/^import (/,/^)/ {
    /"github.com\/sagernet\/sing-box\/protocol\/ssh"/a\
\t_ "github.com/sagernet/sing-box/protocol/payloadinject"\n\t_ "github.com/sagernet/sing-box/protocol/badvpn"
  }' $REGISTRY 2>/dev/null || true
  echo "[+] Registered custom protocols in registry"
fi

# ===== 5. Make sure option package allows custom dir =====
# (the custom/ subdir will be compiled as part of the option package)
echo "[+] Patch complete"

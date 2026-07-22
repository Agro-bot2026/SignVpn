#!/bin/bash
# Patch script for sing-box Go 1.25 compatibility
rm -f /tmp/sing-box/experimental/libbox/pidfd_android.go
rm -rf /tmp/sing-box/experimental/libbox/internal/oomprofile
mkdir -p /tmp/sing-box/experimental/libbox/internal/oomprofile

cat > /tmp/sing-box/experimental/libbox/internal/oomprofile/linkname.go << 'GOEOF'
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

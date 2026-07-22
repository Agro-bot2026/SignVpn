# SingVPN

Cliente VPN Android con soporte de protocolos ADMRufu/HTTP Custom basado en [sing-box](https://github.com/SagerNet/sing-box).

## Protocolos soportados

### Nativos de sing-box
- SSH, SSL/Stunnel, Shadowsocks, VMess, VLESS, Trojan, WireGuard, Hysteria/Hysteria2, TUIC, NaiveProxy, HTTP, SOCKS5, OpenVPN, Tailscale

### Custom (agregados)
- **PayloadInject**: HTTP 101 Switching Protocols + 200 Connection Established + byte skip + tunnel SSH (equivalente a HTTP Custom / PDirect.py de ADMRufu)
- **BadVPN UDPGW**: UDP over TCP gateway (equivalente a badvpn-udpgw)

## Build local

```bash
# Requiere: Go 1.24+, Android SDK 34+, NDK r27+
export ANDROID_SDK_ROOT=/opt/android-sdk
export NDK_HOME=$ANDROID_SDK_ROOT/ndk/27.0.12077973

# Build AAR
gomobile bind -v -target=android/arm64 \
  -androidapi 21 \
  -tags="with_quic,with_utls,with_clash_api,with_gvisor,with_dhcp" \
  -o singbox.aar \
  github.com/sagernet/sing-box/experimental/libbox
```

## Build con GitHub Actions
Hacer fork, pushear, y el Action compila solo.

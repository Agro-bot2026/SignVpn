package payloadinject

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[option.PayloadInjectOutboundOptions](registry, C.TypePayloadInject, NewOutbound)
}

type Outbound struct {
	outbound.Adapter
	ctx           context.Context
	logger        logger.ContextLogger
	dialer        N.Dialer
	serverAddr    M.Socksaddr
	user          string
	password      string
	customPayload string
	skipBytes     int
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.PayloadInjectOutboundOptions) (adapter.Outbound, error) {
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	cp := options.CustomPayload
	if cp == "" {
		cp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\nHTTP/1.1 200 Connection Established\r\n\r\n"
	}
	return &Outbound{
		Adapter:       outbound.NewAdapter(C.TypePayloadInject, tag, []string{N.NetworkTCP}, options.DialerOptions),
		ctx:           ctx,
		logger:        logger,
		dialer:        outboundDialer,
		serverAddr:    options.ServerOptions.Build(),
		user:          options.User,
		password:      options.Password,
		customPayload: cp,
		skipBytes:     options.SkipBytes,
	}, nil
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return o.dialer.DialContext(ctx, N.NetworkTCP, o.serverAddr)
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}

func (o *Outbound) InterfaceUpdated() { common.Close(o) }
func (o *Outbound) Close() error      { return nil }

// PayloadInjectConn maneja el handshake HTTP personalizado
type PayloadInjectConn struct {
	net.Conn
	customPayload string
	skipBytes     int
	handshake     bool
	host          string
	port          string
	method        string
}

func NewPayloadInjectConn(conn net.Conn, customPayload string, skipBytes int, host string, port string) *PayloadInjectConn {
	method := "GET"
	if strings.HasPrefix(customPayload, "CONNECT") {
		method = "CONNECT"
	} else if strings.HasPrefix(customPayload, "POST") {
		method = "POST"
	}
	return &PayloadInjectConn{
		Conn:          conn,
		customPayload: customPayload,
		skipBytes:     skipBytes,
		host:          host,
		port:          port,
		method:        method,
	}
}

// RenderPayload reemplaza variables en el payload y envía
func (c *PayloadInjectConn) RenderPayload() (string, error) {
	p := c.customPayload

	// Reemplazar variables
	p = strings.ReplaceAll(p, "[host]", c.host)
	p = strings.ReplaceAll(p, "[port]", c.port)
	p = strings.ReplaceAll(p, "[method]", c.method)
	p = strings.ReplaceAll(p, "[crlf]", "\r\n")
	p = strings.ReplaceAll(p, "[lf]", "\n")

	// Soporte para [split]
	if strings.Contains(p, "[split]") {
		parts := strings.Split(p, "[split]")
		p = parts[0]
	}

	return p, nil
}

// Handshake realiza el handshake HTTP personalizado y skipea bytes si es necesario
func (c *PayloadInjectConn) Handshake() error {
	if c.handshake {
		return nil
	}
	c.Conn.SetDeadline(time.Now().Add(15 * time.Second))

	// 1. Renderizar y enviar payload
	payload, err := c.RenderPayload()
	if err != nil {
		return err
	}
	if _, err := c.Conn.Write([]byte(payload)); err != nil {
		return err
	}

	// 2. Skiping de bytes si hace falta
	if c.skipBytes > 0 {
		tmp := make([]byte, c.skipBytes)
		c.Conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		io.ReadFull(c.Conn, tmp)
		c.Conn.SetReadDeadline(time.Time{})
	}

	c.Conn.SetDeadline(time.Time{})
	c.handshake = true
	return nil
}

// DialPayloadInject es función helper para hacer el handshake completo desde un dial
func DialPayloadInject(ctx context.Context, dialer N.Dialer, serverAddr M.Socksaddr, customPayload string, skipBytes int) (net.Conn, error) {
	conn, err := dialer.DialContext(ctx, N.NetworkTCP, serverAddr)
	if err != nil {
		return nil, err
	}

	host := serverAddr.AddrString()
	port := "80"
	if serverAddr.Port > 0 {
		port = M.PortToString(serverAddr)
	}

	pic := NewPayloadInjectConn(conn, customPayload, skipBytes, host, port)
	if err := pic.Handshake(); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

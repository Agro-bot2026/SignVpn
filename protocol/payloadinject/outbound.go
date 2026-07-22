package payloadinject

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"os"
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
	ctx        context.Context
	logger     logger.ContextLogger
	dialer     N.Dialer
	serverAddr M.Socksaddr
	user       string
	password   string
	payload    string
	skipBytes  int
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.PayloadInjectOutboundOptions) (adapter.Outbound, error) {
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	payload := options.Payload
	if payload == "" {
		payload = "101"
	}
	return &Outbound{
		Adapter:    outbound.NewAdapter(C.TypePayloadInject, tag, []string{N.NetworkTCP}, options.DialerOptions),
		ctx:        ctx,
		logger:     logger,
		dialer:     outboundDialer,
		serverAddr: options.ServerOptions.Build(),
		user:       options.User,
		password:   options.Password,
		payload:    payload,
		skipBytes:  options.SkipBytes,
	}, nil
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	conn, err := o.dialer.DialContext(ctx, N.NetworkTCP, o.serverAddr)
	if err != nil {
		return nil, err
	}
	// Hacemos el handshake HTTP con el cliente vía la conexión entrante
	// NOTA: En sing-box, este outbound recibe tráfico ya ruteado.
	// El payload injection se maneja como un transporte,
	// no como un reemplazo del handshake.
	return conn, nil
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}

func (o *Outbound) InterfaceUpdated() {
	// No-op
}

func (o *Outbound) Close() error {
	return nil
}

// PayloadInjectConn envuelve una conexión con el handshake HTTP 101/200
type PayloadInjectConn struct {
	net.Conn
	payload   string
	skipBytes int
	handshake bool
}

func NewPayloadInjectConn(conn net.Conn, payload string, skipBytes int) *PayloadInjectConn {
	return &PayloadInjectConn{
		Conn:      conn,
		payload:   payload,
		skipBytes: skipBytes,
	}
}

func (c *PayloadInjectConn) Handshake() error {
	if c.handshake {
		return nil
	}
	c.Conn.SetDeadline(time.Now().Add(15 * time.Second))

	// 1. Leer request HTTP del cliente
	reader := bufio.NewReader(c.Conn)
	req, err := http.ReadRequest(reader)
	if err != nil {
		return err
	}
	_ = req

	// 2. Enviar payload 101
	var respPayload string
	if c.payload == "101" {
		respPayload = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
	} else {
		respPayload = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
	}
	// Siempre enviamos 200 adicional después
	respPayload += "HTTP/1.1 200 Connection Established\r\n\r\n"

	if _, err := c.Conn.Write([]byte(respPayload)); err != nil {
		return err
	}

	// 3. Skiping de bytes si hace falta
	if c.skipBytes > 0 {
		tmp := make([]byte, c.skipBytes)
		c.Conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		io.ReadFull(c.Conn, tmp)
		c.Conn.SetReadDeadline(time.Time{})
	}

	// 4. Drenar buffer del reader
	if reader.Buffered() > 0 {
		buf := make([]byte, reader.Buffered())
		reader.Read(buf)
		// Escribir al destino real (se hace fuera)
	}

	c.Conn.SetDeadline(time.Time{})
	c.handshake = true
	return nil
}

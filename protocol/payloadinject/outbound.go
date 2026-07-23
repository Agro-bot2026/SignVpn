package payloadinject

import (
	"context"
	"fmt"
	"io"
	"math/rand"
	"net"
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
		cp = "GET http://[host]/ HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf][crlf]HTTP/1.1 200 Connection Established[crlf][crlf]"
	}
	return &Outbound{
		Adapter:       outbound.NewAdapterWithDialerOptions(C.TypePayloadInject, tag, []string{N.NetworkTCP}, options.DialerOptions),
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
	conn, err := o.dialer.DialContext(ctx, N.NetworkTCP, o.serverAddr)
	if err != nil {
		return nil, err
	}

	// Hacer el handshake HTTP con payload personalizado
	host := o.serverAddr.AddrString()
	port := "80"
	if o.serverAddr.Port > 0 {
		port = M.PortToString(o.serverAddr)
	}

	pic := NewPayloadInjectConn(conn, o.customPayload, o.skipBytes, host, port)
	if err := pic.Handshake(); err != nil {
		conn.Close()
		return nil, fmt.Errorf("payload handshake: %w", err)
	}

	return conn, nil
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}

func (o *Outbound) InterfaceUpdated() { common.Close(o) }
func (o *Outbound) Close() error      { return nil }

// PayloadInjectConn maneja el handshake HTTP personalizado tipo HTTP Custom / HTTP Injector
type PayloadInjectConn struct {
	net.Conn
	rawPayload string
	skipBytes  int
	handshake  bool
	host       string
	port       string
}

func NewPayloadInjectConn(conn net.Conn, rawPayload string, skipBytes int, host, port string) *PayloadInjectConn {
	return &PayloadInjectConn{
		Conn:       conn,
		rawPayload: rawPayload,
		skipBytes:  skipBytes,
		host:       host,
		port:       port,
	}
}

// render reemplaza variables: [host] [port] [crlf] [lf] [rotate=...]
func (c *PayloadInjectConn) render(template string) string {
	p := template
	rng := rand.New(rand.NewSource(time.Now().UnixNano()))

	// Reemplazar [random=val1;val2;...] (igual que rotate)
	for {
		start := strings.Index(p, "[random=")
		if start == -1 {
			break
		}
		end := strings.Index(p[start:], "]")
		if end == -1 {
			break
		}
		end += start
		inner := p[start+8 : end]
		choices := strings.Split(inner, ";")
		chosen := choices[rng.Intn(len(choices))]
		p = p[:start] + chosen + p[end+1:]
	}

	// Reemplazar [rotate=val1;val2;...]
	for {
		start := strings.Index(p, "[rotate=")
		if start == -1 {
			break
		}
		end := strings.Index(p[start:], "]")
		if end == -1 {
			break
		}
		end += start
		inner := p[start+8 : end]
		choices := strings.Split(inner, ";")
		chosen := choices[rng.Intn(len(choices))]
		p = p[:start] + chosen + p[end+1:]
	}

	// Reemplazar variables simples
	p = strings.ReplaceAll(p, "[host]", c.host)
	p = strings.ReplaceAll(p, "[port]", c.port)
	p = strings.ReplaceAll(p, "[host_port]", fmt.Sprintf("%s:%s", c.host, c.port))
	p = strings.ReplaceAll(p, "[method]", "CONNECT")
	p = strings.ReplaceAll(p, "[protocol]", "HTTP/1.0")
	p = strings.ReplaceAll(p, "[ssh]", "22")
	p = strings.ReplaceAll(p, "[crlf]", "\r\n")
	p = strings.ReplaceAll(p, "[lf]", "\n")
	p = strings.ReplaceAll(p, "[cr]", "\r")
	p = strings.ReplaceAll(p, "[lfcr]", "\n\r")
	p = strings.ReplaceAll(p, "[ua]", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36")
	p = strings.ReplaceAll(p, "\\n", "\n")
	p = strings.ReplaceAll(p, "\\r", "\r")

	return p
}

// sendRaw envía texto crudo al socket con deadlin
func (c *PayloadInjectConn) sendRaw(data string) error {
	_, err := fmt.Fprint(c.Conn, data)
	return err
}

// readUntil busca una subcadena en el flujo de respuesta SIN buffer extra
// Lee raw bytes del socket y los descarta (la respuesta HTTP no nos interesa),
// pero NO absorbe bytes del SSH que vienen después.
func (c *PayloadInjectConn) readUntil(target string, timeout time.Duration) error {
	c.Conn.SetReadDeadline(time.Now().Add(timeout))
	defer c.Conn.SetReadDeadline(time.Time{})
	// Leer byte a byte para no absorber de más
	var buf [1]byte
	var match strings.Builder
	for {
		_, err := c.Conn.Read(buf[:])
		if err != nil {
			return err
		}
		match.Write(buf[:])
		if strings.Contains(match.String(), target) {
			return nil
		}
		// Mantener solo los últimos 20 bytes para el matching
		if match.Len() > 20 {
			discard := match.Len() - 20
			_ = discard // simplemente limitamos el escaneo
		}
		// Si es demasiado largo, salimos
		if match.Len() > 65536 {
			return nil
		}
	}
}

// Handshake ejecuta el payload completo con soporte [split]
// [split] = envía parte 1, lee respuesta del proxy, envía parte 2
// LEE SOLO la respuesta HTTP (200 OK), NO absorbe bytes SSH
func (c *PayloadInjectConn) Handshake() error {
	if c.handshake {
		return nil
	}
	c.Conn.SetDeadline(time.Now().Add(20 * time.Second))
	defer c.Conn.SetDeadline(time.Time{})

	rendered := c.render(c.rawPayload)

	// Si tiene [split], es doble payload
	if strings.Contains(rendered, "[split]") {
		parts := strings.SplitN(rendered, "[split]", 2)
		part1 := parts[0]
		part2 := parts[1]

		// Enviar parte 1 (ej: CONNECT)
		if err := c.sendRaw(part1); err != nil {
			return fmt.Errorf("send part1: %w", err)
		}

		// Leer respuesta del proxy (200 Connection Established)
		// Lee SOLO bytes HTTP, NO toca datos SSH que vengan después
		if err := c.readUntil("200", 5*time.Second); err != nil {
			return fmt.Errorf("read 200 part1: %w", err)
		}

		// Enviar parte 2 (ej: WebSocket upgrade)
		if err := c.sendRaw(part2); err != nil {
			return fmt.Errorf("send part2: %w", err)
		}

		// Leer respuesta del upgrade (200 OK)
		if err := c.readUntil("200", 5*time.Second); err != nil {
			// Si falla, puede que ya haya recibido el banner SSH - intentamos igual
			return nil
		}
	} else {
		// Payload único
		if err := c.sendRaw(rendered); err != nil {
			return fmt.Errorf("send payload: %w", err)
		}

		// Leer respuesta HTTP
		if err := c.readUntil("200", 5*time.Second); err != nil {
			return fmt.Errorf("read 200: %w", err)
		}
	}

	// Skip bytes opcional
	if c.skipBytes > 0 {
		tmp := make([]byte, c.skipBytes)
		c.Conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		io.ReadFull(c.Conn, tmp)
		c.Conn.SetReadDeadline(time.Time{})
	}

	c.handshake = true
	return nil
}

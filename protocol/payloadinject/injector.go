package payloadinject

import (
	"bufio"
	"io"
	"net"
	"net/http"
	"sync"
	"time"
)

// PayloadInjector maneja el handshake HTTP 101/200 y el túnel bidireccional
// Es el equivalente a PDirect.py de ADMRufu pero en Go
type PayloadInjector struct {
	listenAddr string
	targetAddr string
	payload101 string
	payload200 string
	skipBytes  int
	timeout    time.Duration
	listener   net.Listener
	mu         sync.Mutex
}

type Option func(*PayloadInjector)

func WithSkipBytes(n int) Option {
	return func(p *PayloadInjector) {
		p.skipBytes = n
	}
}

func WithTimeout(d time.Duration) Option {
	return func(p *PayloadInjector) {
		p.timeout = d
	}
}

func WithCustomPayload101(payload string) Option {
	return func(p *PayloadInjector) {
		p.payload101 = payload
	}
}

const (
	defaultPayload101 = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
	defaultPayload200 = "HTTP/1.1 200 Connection Established\r\n\r\n"
	defaultTimeout    = 60 * time.Second
)

func NewInjector(listenAddr, targetAddr string, opts ...Option) *PayloadInjector {
	p := &PayloadInjector{
		listenAddr: listenAddr,
		targetAddr: targetAddr,
		payload101: defaultPayload101,
		payload200: defaultPayload200,
		skipBytes:  0,
		timeout:    defaultTimeout,
	}
	for _, opt := range opts {
		opt(p)
	}
	return p
}

func (p *PayloadInjector) Start() error {
	var err error
	p.listener, err = net.Listen("tcp", p.listenAddr)
	if err != nil {
		return err
	}
	for {
		conn, err := p.listener.Accept()
		if err != nil {
			return err
		}
		go p.handleConnection(conn)
	}
}

func (p *PayloadInjector) Stop() error {
	if p.listener != nil {
		return p.listener.Close()
	}
	return nil
}

func (p *PayloadInjector) handleConnection(client net.Conn) {
	defer client.Close()

	client.SetDeadline(time.Now().Add(15 * time.Second))

	// 1. Leer el request HTTP inicial
	reader := bufio.NewReader(client)
	req, err := http.ReadRequest(reader)
	if err != nil {
		return
	}
	_ = req // No nos importa el contenido, solo consumirlo

	// 2. Enviar payload 101 (Switching Protocols)
	client.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := client.Write([]byte(p.payload101)); err != nil {
		return
	}

	// 3. Enviar payload 200 (Connection Established)
	if _, err := client.Write([]byte(p.payload200)); err != nil {
		return
	}

	// 4. Resetear deadline para el túnel largo
	client.SetDeadline(time.Time{})

	// 5. Conectar al destino real (SSH)
	target, err := net.DialTimeout("tcp", p.targetAddr, 10*time.Second)
	if err != nil {
		return
	}
	defer target.Close()

	// 6. Si hay bytes para skipear, los leemos y descartamos
	if p.skipBytes > 0 {
		if _, err := io.CopyN(io.Discard, reader, int64(p.skipBytes)); err != nil {
			// Si falla, intentamos con raw read del conn
			tmp := make([]byte, p.skipBytes)
			client.SetReadDeadline(time.Now().Add(3 * time.Second))
			io.ReadFull(client, tmp)
			client.SetReadDeadline(time.Time{})
		}
	}
	// El reader puede tener datos en buffer que也要 forwardear
	// Creamos un pipe que primero drena el buffer del reader
	done := make(chan struct{}, 2)

	// Forward: client -> target (drenando buffer primero)
	go func() {
		// Primero lo que quedó en el buffer del reader
		if reader.Buffered() > 0 {
			buf := make([]byte, reader.Buffered())
			reader.Read(buf)
			target.Write(buf)
		}
		// Luego el resto del socket
		io.Copy(target, client)
		done <- struct{}{}
	}()

	// Forward: target -> client
	go func() {
		io.Copy(client, target)
		done <- struct{}{}
	}()

	// Esperar a que una dirección se cierre
	<-done
	// Cerrar ambas direcciones
	client.Close()
	target.Close()
	<-done
}

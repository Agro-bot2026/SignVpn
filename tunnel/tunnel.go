// Package tunnel — Motor HTTP Custom completo para Android
// Gomobile-ready: exporta StartTunnel / StopTunnel como Java

package tunnel

import (
	"fmt"
	"math/rand"
	"net"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// TunnelCallback interface que Android implementa para recibir logs
type TunnelCallback interface {
	OnLog(msg string)
	OnStatus(status string)
	OnError(err string)
	OnConnected()
	OnDisconnected()
}

var (
	activeClient   *ssh.Client
	activeConn     net.Conn
	activeListener net.Listener
	mu             sync.Mutex
	stopCh         chan struct{}
)

// renderPayload reemplaza variables HTTP Custom
func renderPayload(template, host, port string) string {
	p := template
	rng := rand.New(rand.NewSource(time.Now().UnixNano()))
	for _, marker := range []string{"[rotate=", "[random="} {
		for {
			start := strings.Index(p, marker)
			if start == -1 {
				break
			}
			end := strings.Index(p[start:], "]")
			if end == -1 {
				break
			}
			end += start
			inner := p[start+len(marker):end]
			choices := strings.Split(inner, ";")
			p = p[:start] + choices[rng.Intn(len(choices))] + p[end+1:]
		}
	}
	reps := map[string]string{
		"[host]": host, "[port]": port,
		"[host_port]": host + ":" + port,
		"[method]": "CONNECT", "[protocol]": "HTTP/1.0", "[ssh]": "22",
		"[crlf]": "\r\n", "[lf]": "\n", "[cr]": "\r", "[lfcr]": "\n\r",
		"[ua]": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
	}
	for k, v := range reps {
		p = strings.ReplaceAll(p, k, v)
	}
	return p
}

// consumeHTTP lee respuesta HTTP hasta doble CRLF
func consumeHTTP(conn net.Conn, timeout time.Duration) error {
	conn.SetReadDeadline(time.Now().Add(timeout))
	defer conn.SetReadDeadline(time.Time{})
	var buf [1]byte
	var data strings.Builder
	crlfCount := 0
	for {
		_, err := conn.Read(buf[:])
		if err != nil {
			return err
		}
		data.Write(buf[:])
		if buf[0] == '\r' || buf[0] == '\n' {
			crlfCount++
		} else {
			crlfCount = 0
		}
		if crlfCount >= 4 {
			return nil
		}
		if data.Len() > 65536 {
			return nil
		}
	}
}

// StartTunnel inicia el túnel HTTP Custom completo
// server: IP del proxy, port: puerto del proxy
// user/password: credenciales SSH
// payload: payload HTTP Custom con variables
// socksPort: puerto local para SOCKS5
// cb: callback para logs/estado desde Go → Android
func StartTunnel(server string, port int, user, password, payload string, socksPort int, cb TunnelCallback) {
	mu.Lock()
	if activeClient != nil {
		mu.Unlock()
		cb.OnError("Ya hay un túnel activo")
		return
	}
	stopCh = make(chan struct{})
	mu.Unlock()

	cb.OnStatus("Conectando...")
	cb.OnLog(fmt.Sprintf("Conectando a %s:%d", server, port))

	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", server, port), 15*time.Second)
	if err != nil {
		cb.OnError(fmt.Sprintf("Error de conexión: %v", err))
		cb.OnDisconnected()
		return
	}

	rendered := renderPayload(payload, server, fmt.Sprintf("%d", port))
	cb.OnLog(fmt.Sprintf("Enviando payload (%d bytes)...", len(rendered)))
	conn.Write([]byte(rendered))

	cb.OnLog("Esperando respuesta HTTP...")
	if err := consumeHTTP(conn, 10*time.Second); err != nil {
		conn.Close()
		cb.OnError(fmt.Sprintf("Error respuesta HTTP: %v", err))
		cb.OnDisconnected()
		return
	}
	// Segunda respuesta (200)
	consumeHTTP(conn, 3*time.Second)
	cb.OnLog("✅ HTTP OK, banner SSH en socket")

	// SSH handshake
	cb.OnStatus("Autenticando SSH...")
	sshConfig := &ssh.ClientConfig{
		User:            user,
		Auth:            []ssh.AuthMethod{ssh.Password(password)},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         15 * time.Second,
	}
	sshConn, chans, reqs, err := ssh.NewClientConn(conn, fmt.Sprintf("%s:%d", server, 22), sshConfig)
	if err != nil {
		conn.Close()
		cb.OnError(fmt.Sprintf("Error SSH: %v", err))
		cb.OnDisconnected()
		return
	}
	client := ssh.NewClient(sshConn, chans, reqs)

	mu.Lock()
	activeClient = client
	activeConn = conn
	mu.Unlock()

	cb.OnLog("🎉 SSH Conectado!")
	cb.OnStatus("Conectado")

	// Iniciar SOCKS5 local
	addr := fmt.Sprintf("127.0.0.1:%d", socksPort)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		client.Close()
		conn.Close()
		cb.OnError(fmt.Sprintf("Error SOCKS: %v", err))
		cb.OnDisconnected()
		return
	}
	mu.Lock()
	activeListener = listener
	mu.Unlock()

	cb.OnConnected()
	cb.OnLog(fmt.Sprintf("SOCKS5 en %s", addr))

	// Aceptar conexiones SOCKS hasta que paren
	go func() {
		for {
			select {
			case <-stopCh:
				return
			default:
			}
			local, err := listener.Accept()
			if err != nil {
				return
			}
			go handleSOCKS(local, client, cb)
		}
	}()

	// Esperar a que paren
	<-stopCh
}

// StopTunnel detiene el túnel activo
func StopTunnel() {
	mu.Lock()
	defer mu.Unlock()
	if stopCh != nil {
		close(stopCh)
	}
	if activeListener != nil {
		activeListener.Close()
		activeListener = nil
	}
	if activeClient != nil {
		activeClient.Close()
		activeClient = nil
	}
	if activeConn != nil {
		activeConn.Close()
		activeConn = nil
	}
}

// handleSOCKS maneja una conexión SOCKS5 entrante y la redirige por SSH
func handleSOCKS(local net.Conn, client *ssh.Client, cb TunnelCallback) {
	defer local.Close()

	// Leer método SOCKS5
	buf := make([]byte, 2)
	if _, err := local.Read(buf); err != nil {
		return
	}
	nMethods := int(buf[1])
	if nMethods > 0 {
		methods := make([]byte, nMethods)
		if _, err := local.Read(methods); err != nil {
			return
		}
	}
	// Responder: no auth
	local.Write([]byte{0x05, 0x00})

	// Leer request
	header := make([]byte, 4)
	if _, err := local.Read(header); err != nil {
		return
	}
	cmd := header[1] // 0x01 = CONNECT
	atyp := header[3]

	var host string
	var port int

	if atyp == 0x01 { // IPv4
		ip := make([]byte, 4)
		local.Read(ip)
		host = net.IP(ip).String()
	} else if atyp == 0x03 { // Domain
		buf = make([]byte, 1)
		local.Read(buf)
		len := int(buf[0])
		domain := make([]byte, len)
		local.Read(domain)
		host = string(domain)
	}
	buf = make([]byte, 2)
	local.Read(buf)
	port = int(buf[0])<<8 | int(buf[1])

	if cmd == 0x01 {
		remote, err := client.Dial("tcp", fmt.Sprintf("%s:%d", host, port))
		if err != nil {
			local.Write([]byte{0x05, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00})
			return
		}
		defer remote.Close()
		local.Write([]byte{0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00})

		go forward(local, remote)
		forward(remote, local)
	}
}

func forward(src, dst net.Conn) {
	buf := make([]byte, 32768)
	for {
		n, err := src.Read(buf)
		if err != nil {
			return
		}
		if _, err := dst.Write(buf[:n]); err != nil {
			return
		}
	}
}

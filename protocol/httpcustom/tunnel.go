package httpcustom

import (
	"fmt"
	"math/rand"
	"net"
	"strings"
	"time"

	"golang.org/x/crypto/ssh"
)

// Tunnel establece un túnel completo: payload → proxy → 200 → SSH
// y devuelve un cliente SSH listo para usar.
type Tunnel struct {
	Conn      net.Conn
	SSHClient *ssh.Client
	server    string
	port      int
	user      string
	password  string
	payload   string
	skipBytes int
}

// NewTunnel crea una nueva configuración de túnel
func NewTunnel(server string, port int, payload string, user, password string, skipBytes int) *Tunnel {
	return &Tunnel{
		server:    server,
		port:      port,
		user:      user,
		password:  password,
		payload:   payload,
		skipBytes: skipBytes,
	}
}

// Connect establece el túnel completo
func (t *Tunnel) Connect() error {
	addr := fmt.Sprintf("%s:%d", t.server, t.port)
	conn, err := net.DialTimeout("tcp", addr, 15*time.Second)
	if err != nil {
		return fmt.Errorf("conexión: %w", err)
	}

	rendered := renderPayload(t.payload, t.server, fmt.Sprintf("%d", t.port))

	// Enviar TODO el payload de una vez
	if _, err := conn.Write([]byte(rendered)); err != nil {
		conn.Close()
		return fmt.Errorf("envío payload: %w", err)
	}

	// Consumir respuestas HTTP hasta doble CRLF después de "200"
	if err := consumeHTTP(conn, 10*time.Second); err != nil {
		conn.Close()
		return fmt.Errorf("respuesta HTTP: %w", err)
	}

	// Skip bytes opcional
	if t.skipBytes > 0 {
		tmp := make([]byte, t.skipBytes)
		conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		defer conn.SetReadDeadline(time.Time{})
		_, err := conn.Read(tmp)
		if err != nil {
			conn.Close()
			return fmt.Errorf("skip bytes: %w", err)
		}
	}

	// Establecer SSH
	sshConfig := &ssh.ClientConfig{
		User:            t.user,
		Auth:            []ssh.AuthMethod{ssh.Password(t.password)},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         15 * time.Second,
	}

	sshConn, chans, reqs, err := ssh.NewClientConn(conn, addr, sshConfig)
	if err != nil {
		conn.Close()
		return fmt.Errorf("SSH handshake: %w", err)
	}

	t.Conn = conn
	t.SSHClient = ssh.NewClient(sshConn, chans, reqs)
	return nil
}

// Close cierra el túnel
func (t *Tunnel) Close() {
	if t.SSHClient != nil {
		t.SSHClient.Close()
	}
	if t.Conn != nil {
		t.Conn.Close()
	}
}

// Forward establece un forward local → remoto a través del túnel SSH
func (t *Tunnel) Forward(localAddr, remoteAddr string) error {
	listener, err := net.Listen("tcp", localAddr)
	if err != nil {
		return err
	}
	for {
		local, err := listener.Accept()
		if err != nil {
			return err
		}
		go func() {
			remote, err := t.SSHClient.Dial("tcp", remoteAddr)
			if err != nil {
				local.Close()
				return
			}
			go forward(local, remote)
			go forward(remote, local)
		}()
	}
}

func forward(src, dst net.Conn) {
	defer src.Close()
	defer dst.Close()
	buf := make([]byte, 32768)
	for {
		n, err := src.Read(buf)
		if err != nil {
			return
		}
		_, err = dst.Write(buf[:n])
		if err != nil {
			return
		}
	}
}

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

// consumeHTTP lee respuestas HTTP hasta doble CRLF después de "200"
func consumeHTTP(conn net.Conn, timeout time.Duration) error {
	conn.SetReadDeadline(time.Now().Add(timeout))
	defer conn.SetReadDeadline(time.Time{})
	var buf [1]byte
	var data strings.Builder
	seen200 := false
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
		if strings.Contains(data.String(), "200") {
			seen200 = true
		}
		if seen200 && crlfCount >= 4 {
			return nil
		}
		if data.Len() > 65536 {
			return nil
		}
	}
}

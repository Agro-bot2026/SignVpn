package badvpn

import (
	"encoding/binary"
	"io"
	"net"
	"sync"
	"time"
)

// BadVPN UDPGW protocol implementation
// Puentea UDP sobre TCP, igual que badvpn-udpgw
// Formato del frame:
//   [1 byte flags] [1 byte frag] [2 bytes peer_id] [2 bytes port] [4 bytes addr] [N bytes data]

const (
	// Flags del protocolo UDPGW
	flagKeepAlive = 1 << iota
	flagRebind
	flagIPv6
	flagFrag
	flagNATData
	flagNATPeers
	flagNATAddr
)

const (
	defaultMTU   = 1500
	maxFrameSize = 65535
)

type UDPGWServer struct {
	listenAddr string
	targetAddr string
	mtu        int
	listener   net.Listener
	conns      sync.Map
	udpConn    *net.UDPConn
}

func NewUDPGWServer(listenAddr, targetAddr string) *UDPGWServer {
	return &UDPGWServer{
		listenAddr: listenAddr,
		targetAddr: targetAddr,
		mtu:        defaultMTU,
	}
}

func (s *UDPGWServer) Start() error {
	// Escucha TCP para conexiones UDPGW
	var err error
	s.listener, err = net.Listen("tcp", s.listenAddr)
	if err != nil {
		return err
	}

	// También escucha UDP directo
	udpAddr, err := net.ResolveUDPAddr("udp", s.listenAddr)
	if err == nil {
		s.udpConn, err = net.ListenUDP("udp", udpAddr)
		if err == nil {
			go s.handleUDPDirect()
		}
	}

	go s.acceptLoop()
	return nil
}

func (s *UDPGWServer) acceptLoop() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		go s.handleUDPGW(conn)
	}
}

func (s *UDPGWServer) handleUDPGW(tcpConn net.Conn) {
	defer tcpConn.Close()

	tcpConn.SetDeadline(time.Now().Add(30 * time.Second))

	for {
		// Leer header del frame UDPGW: 4 bytes fijos + addr variable
		var header [4]byte
		if _, err := io.ReadFull(tcpConn, header[:]); err != nil {
			return
		}

		flags := header[0]
		_ = header[1] // fragmento (no usado)
		peerID := binary.BigEndian.Uint16(header[2:4])

		// Resetear deadline en cada frame válido
		tcpConn.SetDeadline(time.Now().Add(30 * time.Second))

		if flags&flagKeepAlive != 0 {
			// Keep-alive, solo continuar
			continue
		}

		// Dirección destino
		var addrLen int
		if flags&flagIPv6 != 0 {
			addrLen = 16
		} else {
			addrLen = 4
		}

		addrPort := make([]byte, addrLen+2)
		if _, err := io.ReadFull(tcpConn, addrPort); err != nil {
			return
		}

		port := binary.BigEndian.Uint16(addrPort[addrLen:])

		// Leer payload
		payload := make([]byte, maxFrameSize)
		n, err := tcpConn.Read(payload)
		if err != nil {
			return
		}
		payload = payload[:n]

		// Resolver dirección
		var dstAddr net.IP
		if flags&flagIPv6 != 0 {
			dstAddr = net.IP(addrPort[:16])
		} else {
			dstAddr = net.IP(addrPort[:4])
		}

		// Enviar UDP
		remoteAddr := &net.UDPAddr{IP: dstAddr, Port: int(port)}
		udpConn, dialErr := net.DialUDP("udp", nil, remoteAddr)
		if dialErr != nil {
			continue
		}

		_, writeErr := udpConn.Write(payload)
		if writeErr != nil {
			udpConn.Close()
			continue
		}

		// Leer respuesta y reenviar por TCP
		resp := make([]byte, maxFrameSize)
		udpConn.SetReadDeadline(time.Now().Add(10 * time.Second))
		respN, _, readErr := udpConn.ReadFromUDP(resp)
		udpConn.Close()
		if readErr != nil {
			continue
		}

		// Armar frame de respuesta UDPGW
		respFrame := make([]byte, 4+addrLen+2+respN)
		respFrame[0] = flags
		respFrame[1] = 0
		binary.BigEndian.PutUint16(respFrame[2:4], peerID)
		copy(respFrame[4:], addrPort)
		copy(respFrame[4+addrLen+2:], resp[:respN])

		tcpConn.Write(respFrame)
	}
}

func (s *UDPGWServer) handleUDPDirect() {
	buf := make([]byte, maxFrameSize)
	for {
		n, addr, err := s.udpConn.ReadFromUDP(buf)
		if err != nil {
			return
		}

		// Reenviar al target (badvpn local)
		target, err := net.DialTimeout("tcp", s.targetAddr, 5*time.Second)
		if err != nil {
			continue
		}

		// Armar frame UDPGW
		ip := addr.IP.To4()
		isIPv6 := ip == nil
		var addrLen int
		if isIPv6 {
			addrLen = 16
		} else {
			addrLen = 4
		}

		frame := make([]byte, 4+addrLen+2+n)
		var flags byte
		if isIPv6 {
			flags = flagIPv6
		}

		// peerID = 0 (conexión entrante)
		frame[0] = flags
		frame[1] = 0
		binary.BigEndian.PutUint16(frame[2:4], 0)

		if isIPv6 {
			copy(frame[4:], addr.IP.To16())
		} else {
			copy(frame[4:], ip)
		}
		binary.BigEndian.PutUint16(frame[4+addrLen:], uint16(addr.Port))
		copy(frame[4+addrLen+2:], buf[:n])

		target.Write(frame)
		target.Close()
	}
}

func (s *UDPGWServer) Stop() error {
	if s.listener != nil {
		s.listener.Close()
	}
	if s.udpConn != nil {
		s.udpConn.Close()
	}
	return nil
}

// BadVPN client - conecta a un servidor UDPGW y encapsula/desencapsula UDP
type UDPGWClient struct {
	serverAddr string
	conn       net.Conn
	mu         sync.Mutex
}

func NewUDPGWClient(serverAddr string) *UDPGWClient {
	return &UDPGWClient{serverAddr: serverAddr}
}

func (c *UDPGWClient) Dial() (net.Conn, error) {
	conn, err := net.DialTimeout("tcp", c.serverAddr, 10*time.Second)
	if err != nil {
		return nil, err
	}
	c.conn = conn
	return conn, nil
}

func (c *UDPGWClient) SendUDP(data []byte, addr *net.UDPAddr) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	ip := addr.IP.To4()
	isIPv6 := ip == nil
	var addrLen int
	if isIPv6 {
		addrLen = 16
	} else {
		addrLen = 4
	}

	frame := make([]byte, 4+addrLen+2+len(data))
	var flags byte
	if isIPv6 {
		flags = flagIPv6
	}
	frame[0] = flags
	frame[1] = 0
	binary.BigEndian.PutUint16(frame[2:4], 1) // peerID fijo

	if isIPv6 {
		copy(frame[4:], addr.IP.To16())
	} else {
		copy(frame[4:], ip)
	}
	binary.BigEndian.PutUint16(frame[4+addrLen:], uint16(addr.Port))
	copy(frame[4+addrLen+2:], data)

	_, err := c.conn.Write(frame)
	return err
}

func (c *UDPGWClient) ReadUDP() ([]byte, *net.UDPAddr, error) {
	var header [4]byte
	if _, err := io.ReadFull(c.conn, header[:]); err != nil {
		return nil, nil, err
	}

	flags := header[0]
	_ = header[1] // frag

	var addrLen int
	if flags&flagIPv6 != 0 {
		addrLen = 16
	} else {
		addrLen = 4
	}

	addrPort := make([]byte, addrLen+2)
	if _, err := io.ReadFull(c.conn, addrPort); err != nil {
		return nil, nil, err
	}

	port := binary.BigEndian.Uint16(addrPort[addrLen:])

	var ip net.IP
	if flags&flagIPv6 != 0 {
		ip = net.IP(addrPort[:16])
	} else {
		ip = net.IP(addrPort[:4])
	}

	payload := make([]byte, maxFrameSize)
	n, err := c.conn.Read(payload)
	if err != nil {
		return nil, nil, err
	}

	return payload[:n], &net.UDPAddr{IP: ip, Port: int(port)}, nil
}

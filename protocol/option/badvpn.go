package option

type BadVPOutboundOptions struct {
	DialerOptions
	ServerOptions
	UDPGWPort int `json:"udpgw_port,omitempty"`
}

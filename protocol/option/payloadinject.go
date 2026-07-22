package option

type PayloadInjectOutboundOptions struct {
	DialerOptions
	ServerOptions
	User       string `json:"user,omitempty"`
	Password   string `json:"password,omitempty"`
	Payload    string `json:"payload,omitempty"`
	SkipBytes  int    `json:"skip_bytes,omitempty"`
}

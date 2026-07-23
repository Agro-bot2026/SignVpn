//go:build ignore

package main

import (
	"fmt"
	"os"
	"os/exec"
)

func main() {
	// Build the AAR from the tunnel package
	cmd := exec.Command("gomobile", "bind",
		"-target", "android/arm64",
		"-androidapi", "21",
		"-o", "app/libs/tunnel.aar",
		"Agro-bot2026/SignVpn/tunnel",
	)
	cmd.Dir = "/root/SignVpn"
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Env = append(os.Environ(),
		"PATH="+os.Getenv("PATH")+":"+os.Getenv("GOPATH")+"/bin",
	)
	if err := cmd.Run(); err != nil {
		fmt.Printf("Error: %v\n", err)
		os.Exit(1)
	}
	fmt.Println("✅ AAR generado")
}

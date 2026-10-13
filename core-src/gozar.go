package gozar

import (
    "context"
    "fmt"
    "os"
    "strings"
    "sync"

    "github.com/xtls/xray-core/core"
    _ "github.com/xtls/xray-core/main/distro/all"
    "github.com/xtls/xray-core/infra/conf/serial"

    "golang.org/x/sys/unix"
)

// V2RayPoint — سازگار با پل بازتابی جاوا
type V2RayPoint struct {
    mu      sync.Mutex
    server  *core.Instance
    running bool
    tunFile *os.File // TUN file descriptor — we keep it to close on stop
}

func NewV2RayPoint() *V2RayPoint { return &V2RayPoint{} }

// RunV2RayWithTun — اجرای هسته با fd تونل مستقیم (مسیر v2rayNG)
// این همان روشی است که v2rayNG استفاده می‌کند: fd را به Go بده، خودش TUN را مدیریت کند
func (p *V2RayPoint) RunV2RayWithTun(config string, tunFd int) error {
    p.mu.Lock()
    defer p.mu.Unlock()
    if p.running {
        return nil
    }

    // فایل Go از fd اندروید بساز — این TUN است
    tunFile := os.NewFile(uintptr(tunFd), "tun")
    if tunFile == nil {
        return fmt.Errorf("cannot wrap tun fd %d", tunFd)
    }
    p.tunFile = tunFile

    // متیو: در این نسخه Xray را بدون TUN-inbound اجرا می‌کنیم و
    // به‌جایش ترافیک را از fd تونل به SOCKS محلی هدایت می‌کنیم.
    // چون sing-box/gvisor در دسترس نیست و پیاده‌سازی کامل tun2socks
    // در Go بسیار سنگین است، مسیر عملی‌تر این است:
    // هسته با SOCKS inbound معمولی بالا می‌آید (10808) و hev (قبلاً ساخته‌شده)
    // روی fd تونل کار می‌کند. اگر hev کار نکرد، اینجا در آینده جایگزین می‌شود.

    cf, err := serial.DecodeJSONConfig(strings.NewReader(config))
    if err != nil {
        return err
    }
    pb, err := cf.Build()
    if err != nil {
        return err
    }
    inst, err := core.New(pb)
    if err != nil {
        return err
    }
    if err = inst.Start(); err != nil {
        return err
    }
    p.server = inst
    p.running = true
    return nil
}

// RunV2Ray — اجرای هسته بدون TUN (بازگشت به حالت قبلی)
func (p *V2RayPoint) RunV2Ray(config string) error {
    p.mu.Lock()
    defer p.mu.Unlock()
    if p.running {
        return nil
    }
    cf, err := serial.DecodeJSONConfig(strings.NewReader(config))
    if err != nil {
        return err
    }
    pb, err := cf.Build()
    if err != nil {
        return err
    }
    inst, err := core.New(pb)
    if err != nil {
        return err
    }
    if err = inst.Start(); err != nil {
        return err
    }
    p.server = inst
    p.running = true
    return nil
}

func (p *V2RayPoint) StopV2Ray() {
    p.mu.Lock()
    defer p.mu.Unlock()
    if p.server != nil {
        p.server.Close()
        p.server = nil
    }
    if p.tunFile != nil {
        p.tunFile.Close()
        p.tunFile = nil
    }
    p.running = false
}

func (p *V2RayPoint) IsRunning() bool {
    p.mu.Lock()
    defer p.mu.Unlock()
    return p.running
}

func InitV2Env(assetPath string) {
    os.Setenv("XRAY_LOCATION_ASSET", assetPath)
    os.Setenv("V2RAY_LOCATION_ASSET", assetPath)
}

// PingHost — پینگ TCP واقعی از داخل گو (مستقل از تونل)
func PingHost(host string, port int) bool {
    conn, err := unix.Socket(unix.AF_INET, unix.SOCK_STREAM, 0)
    if err != nil {
        return false
    }
    defer unix.Close(conn)
    unix.SetNonblock(conn, true)
    err = unix.Connect(conn, &unix.SockaddrInet4{Port: port})
    if err == nil {
        return true
    }
    if err != unix.EINPROGRESS {
        return false
    }
    // صبر تا ۵ ثانیه
    fds := []unix.PollFd{{Fd: int32(conn), Events: unix.POLLOUT}}
    _, err = unix.Ppoll(fds, nil, &unix.Timespec{Sec: 5, Nsec: 0})
    if err != nil {
        return false
    }
    val, err := unix.GetsockoptInt(conn, unix.SOL_SOCKET, unix.SO_ERROR)
    if err != nil || val != 0 {
        return false
    }
    return true
}

// Unused imports kept for future TUN-in-Go work
var _ = context.Background

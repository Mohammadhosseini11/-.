package gozar

import (
    "fmt"
    "net"
    "os"
    "strings"
    "sync"
    "time"

    "github.com/xtls/xray-core/core"
    _ "github.com/xtls/xray-core/main/distro/all"
    "github.com/xtls/xray-core/infra/conf/serial"
)

// V2RayPoint — سازگار با پل بازتابی جاوا
type V2RayPoint struct {
    mu      sync.Mutex
    server  *core.Instance
    running bool
}

func NewV2RayPoint() *V2RayPoint { return &V2RayPoint{} }

// InitV2Env — مسیر assets و لاگ‌ها را برای هسته تنظیم می‌کند.
// assetPath = filesDir اپ؛ فایل‌های geoip/geosite.dat و لاگ‌ها همین‌جاست.
func InitV2Env(assetPath string) {
    os.Setenv("XRAY_LOCATION_ASSET", assetPath)
    os.Setenv("V2RAY_LOCATION_ASSET", assetPath)
    os.Setenv("XRAY_LOG_LOCATION", assetPath)
}

// RunV2Ray — اجرای هسته با کانفیگ JSON
func (p *V2RayPoint) RunV2Ray(config string) error {
    p.mu.Lock()
    defer p.mu.Unlock()
    if p.running {
        return nil
    }
    cf, err := serial.DecodeJSONConfig(strings.NewReader(config))
    if err != nil {
        return fmt.Errorf("config decode: %w", err)
    }
    pb, err := cf.Build()
    if err != nil {
        return fmt.Errorf("config build: %w", err)
    }
    inst, err := core.New(pb)
    if err != nil {
        return fmt.Errorf("core new: %w", err)
    }
    if err = inst.Start(); err != nil {
        return fmt.Errorf("core start: %w", err)
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
    p.running = false
}

func (p *V2RayPoint) IsRunning() bool {
    p.mu.Lock()
    defer p.mu.Unlock()
    return p.running
}

// PingHost — پینگ TCP واقعی از داخل گو (مستقل از تونل)
func PingHost(host string, port int) bool {
    conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", host, port), 5*time.Second)
    if err != nil {
        return false
    }
    _ = conn.Close()
    return true
}

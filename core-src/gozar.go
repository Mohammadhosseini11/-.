package gozar

import (
    "os"
    "strings"

    "github.com/xtls/xray-core/core"
    _ "github.com/xtls/xray-core/main/distro/all"
    "github.com/xtls/xray-core/infra/conf/serial"
)

// V2RayPoint — سازگار با پل بازتابی جاوا
type V2RayPoint struct {
    server  *core.Instance
    running bool
}

func NewV2RayPoint() *V2RayPoint { return &V2RayPoint{} }

func (p *V2RayPoint) RunV2Ray(config string) error {
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
    if p.server != nil {
        p.server.Close()
        p.server = nil
    }
    p.running = false
}

func (p *V2RayPoint) IsRunning() bool { return p.running }

// InitV2Env مسیر فایل‌های geo را برای هسته تنظیم می‌کند
func InitV2Env(assetPath string) {
    os.Setenv("XRAY_LOCATION_ASSET", assetPath)
    os.Setenv("V2RAY_LOCATION_ASSET", assetPath)
}

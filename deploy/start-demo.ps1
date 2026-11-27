# 门禁演示环境一键启动
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File deploy\start-demo.ps1
#   powershell -ExecutionPolicy Bypass -File deploy\start-demo.ps1 -OffsetMinutes -745
#
# 做三件事：
#   1) 打印当前 WLAN IP，并和固件里的 APP_BROKER_HOST 比对（不一致会红字告警）
#   2) 1883 没监听就拉起 Mosquitto
#   3) 用正确的 JDK 和 profile 启动后端（会清掉 SERVER__PORT 这个坑）
param(
    [int]$OffsetMinutes = 0,
    [switch]$SkipBackend
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Write-Host "=== 门禁演示环境 ===" -ForegroundColor Cyan

# ---------- 1. 网络自检 ----------
$ip = $null
$wlan = Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias 'WLAN' -ErrorAction SilentlyContinue
if ($wlan) {
    $ip = ($wlan | Select-Object -First 1).IPAddress
    Write-Host ("WLAN IP        : " + $ip)
} else {
    Write-Host "WLAN IP        : 未找到 WLAN 网卡（热点没开？）" -ForegroundColor Yellow
}

$cfg = 'D:\code\WebHardware\Overall\App\app_config.h'
if (Test-Path $cfg) {
    $m = Select-String -Path $cfg -Pattern 'APP_BROKER_HOST\s+"([^"]+)"'
    if ($m) {
        $brokerHost = $m.Matches[0].Groups[1].Value
        Write-Host ("固件指向       : " + $brokerHost)
        if ($ip -and $brokerHost -ne $ip) {
            Write-Host "!! IP 与固件不一致：改 app_config.h 后执行 python Tools\fw.py cycle 30 重新烧录" -ForegroundColor Red
        }
    }
}

# ---------- 2. Broker ----------
$listening = Get-NetTCPConnection -LocalPort 1883 -State Listen -ErrorAction SilentlyContinue
if ($listening) {
    Write-Host "Mosquitto      : 已在运行"
} else {
    $exe = "C:\Program Files\Mosquitto\mosquitto.exe"
    if (-not (Test-Path $exe)) {
        Write-Host "Mosquitto      : 未安装（找不到 $exe）" -ForegroundColor Red
        exit 1
    }
    Start-Process -FilePath $exe `
        -ArgumentList @('-c', 'deploy\mosquitto\mosquitto-windows.conf', '-v') `
        -WorkingDirectory $root
    Write-Host "Mosquitto      : 已启动"
    Start-Sleep -Seconds 2
}

if ($SkipBackend) {
    Write-Host "（-SkipBackend：不启动后端）"
    exit 0
}

# ---------- 3. 后端 ----------
$jdk = 'C:\Users\魏\.jdks\ms-21.0.12.1'
if (Test-Path (Join-Path $jdk 'bin\java.exe')) {
    $env:JAVA_HOME = $jdk
    $env:PATH = (Join-Path $jdk 'bin') + ';' + $env:PATH
    Write-Host ("JDK            : " + $jdk)
} else {
    Write-Host "JDK            : 找不到 $jdk，可能用的是系统 java（JRE 1.8 会失败）" -ForegroundColor Yellow
}

# WorkBuddy 会注入这个变量，顶掉 server.port 且端口被占用
Remove-Item Env:SERVER__PORT -ErrorAction SilentlyContinue

$mvnArgs = @('spring-boot:run', '-Dspring-boot.run.profiles=local,mqtt-broker')
if ($OffsetMinutes -ne 0) {
    $mvnArgs += ('-Dspring-boot.run.arguments=--app.clock.offset-minutes=' + $OffsetMinutes)
    Write-Host ("时间偏移       : " + $OffsetMinutes + " 分钟" ) -ForegroundColor Yellow
    Write-Host "  → 服务端时间往回跳时，板子必须断电重启才能重新授时" -ForegroundColor Yellow
} else {
    Write-Host "时间偏移       : 无（使用真实时间）"
}

Write-Host "启动后端 ..." -ForegroundColor Cyan
Push-Location $root
try {
    & (Join-Path $root 'mvnw.cmd') @mvnArgs
} finally {
    Pop-Location
}

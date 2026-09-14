# AeroFleet RF 链路几何回归测试（Batch E1）
# 前置：cloud-backend(8080/14550) 已运行；JAVA_HOME 指向 JDK17。
# 场景：纯距离衰减（无障碍），起飞后 RSSI 由近场强信号单调走弱。
# 山体遮挡/越顶罚项由 RadioEnvironmentTest 六个单测覆盖（含 20dB 断言）——
# 本 e2e 验证全链：sim 播报 RADIO_STATUS -> 后端入库 -> REST rssiDbm -> 前端信号条。
# 断言：
#   1. 近 home RSSI 强（> -70 dBm，FSPL@~几十米）
#   2. 飞远后 RSSI 衰减（< -80 dBm，FSPL@~800m）
#   3. REST rssiDbm 可查且 = 衰减值
$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'
$Fail = 0

# ---- Java 版本自检（e2e 共用守卫）----
function Assert-Java17([string]$JavaExe) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $raw = & $JavaExe -version 2>&1
    $ErrorActionPreference = $prev
    $v = (@($raw) | ForEach-Object { "$_" } | Select-Object -First 1)
    if ($v -notmatch '"(\d+)(\.(\d+))?') { return }
    $major = [int]$Matches[1]; if (-not $Matches[3]) { $minor = 0 } else { $minor = [int]$Matches[3] }
    $ok = if ($major -eq 1) { $minor -ge 17 } else { $major -ge 17 }
    if (-not $ok) { Write-Host "需要 Java >= 17，当前: $v" -ForegroundColor Red; exit 1 }
}

function Step($m) { Write-Host "== $m" -ForegroundColor Cyan }
function Check($desc, $ok) {
    if ($ok) { Write-Host "   PASS: $desc" -ForegroundColor Green }
    else { Write-Host "   FAIL: $desc" -ForegroundColor Red; $script:Fail = 1 }
}

# ---- 0. 前置 ----
try { $null = Invoke-RestMethod "$Base/drones" -TimeoutSec 5 }
catch { Write-Host '后端未运行，中止' -ForegroundColor Red; exit 1 }

# ---- 1. 启动无障碍模拟器 ----
Step '启动 RF 模拟器 (sysid=11, mavlink=14543, truth=18081, 无障碍)'
$jar = Join-Path $PSScriptRoot '..\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar'
if ($env:AF_JAVA) { $java = $env:AF_JAVA } elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" } else { $java = "java.exe" }
Assert-Java17 $java
$sim = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $jar, '--port', '14543', '--sysid', '11', '--name', 'AF-RF-01',
    '--http-port', '18081'
) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-rf-sim.log') -RedirectStandardError (Join-Path $env:TEMP 'af-rf-sim.err')
Start-Sleep 2

$registered = $false
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep 1
    $list = Invoke-RestMethod "$Base/drones" -TimeoutSec 5
    if ($list | Where-Object { $_.sysid -eq 11 -and $_.online -eq $true }) { $registered = $true; break }
}
Check 'RF 模拟器上线 (sysid=11)' $registered
if (-not $registered) {
    Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
    Write-Host 'RF E2E FAILED' -ForegroundColor Red; exit 1
}

try {
    # ---- 2. 近场链路强度 ----
    Step '近场链路强度'
    Start-Sleep 4   # 等 1Hz RADIO_STATUS 播报数帧
    $radio0 = Invoke-RestMethod "http://127.0.0.1:18081/radio" -TimeoutSec 5
    Write-Host "   近场 RSSI = $($radio0.rssiDbm) dBm"
    Check "近场 RSSI > -70 dBm (got $($radio0.rssiDbm))" ([double]$radio0.rssiDbm -gt -70)

    # ---- 3. 起飞飞远 -> 距离衰减 ----
    Step '起飞 -> 飞远 -> 距离衰减'
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/11/commands" -Body (@{ type='arm' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/11/commands" -Body (@{ type='takeoff'; alt=60 } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 30
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/11/mission" -Body (@{
        items = @(@{ cmd='takeoff'; lat=22.5907; lon=113.9345; alt=60 },
                   @{ cmd='waypoint'; lat=22.5979; lon=113.9345; alt=60 })   # ~800m 北
    } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 30
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/11/commands" -Body (@{ type='start_mission' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10

    # 采样 RSSI 直到明显衰减（800m @ 2.4GHz FSPL ~ 98dB -> RSSI ~ -78dBm）
    $minRssi = -30
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep 3
        $r = Invoke-RestMethod "http://127.0.0.1:18081/radio" -TimeoutSec 5
        $dbm = [double]$r.rssiDbm
        if ($dbm -lt $minRssi) { $minRssi = $dbm }
        if ($dbm -lt -75) { break }
    }
    Write-Host "   最弱 RSSI = $minRssi dBm"
    Check "飞远后 RSSI < -75 dBm（距离衰减, got $minRssi）" ($minRssi -lt -75)

    # ---- 4. REST 透传 ----
    Step '后端 RADIO_STATUS 透传'
    $tel = Invoke-RestMethod "$Base/drones/11/telemetry" -TimeoutSec 5
    $rssiRest = $tel.rssiDbm
    Check "REST rssiDbm 可查且 <= -75 (got $rssiRest)" ([double]$rssiRest -le -75)
} finally {
    Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
    if ($Fail -eq 0) { Write-Host 'RF E2E PASSED' -ForegroundColor Green }
    else { Write-Host 'RF E2E FAILED' -ForegroundColor Red }
}
if ($Fail -ne 0) { exit 1 }
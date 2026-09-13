# AeroFleet 故障注入回归测试（PowerShell 版）
# 前置：cloud-backend(8080/14550) 已运行。
# 本脚本自行启动一台带故障脚本的模拟器（端口 14541，避开正常模拟器），
# 验证三个故障的反应链：
#   1. gps-loss  -> 后端告警 + telemetry.gpsHealthy=false
#   2. link-loss -> 心跳超时后 devices 列表 online=false
#   3. battery-fault -> telemetry.battery 跳到故障值 + 电压骤降
# 注意后端 discovery 默认指向 127.0.0.1:14540（正常模拟器），
# 故障模拟器必须自己主动向后端 14550 发包注册（脚本用 python 探针触发）。
$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'
$Fail = 0

# ---- Java 版本自检（e2e 共用守卫）：JDK8 会静默杀掉 sim（class 61 vs 52）----
function Assert-Java17([string]$JavaExe) {
    # EAP=Stop + 原生命令 stderr 在 PS5.1 会被当作终止错误：探测前临时降级
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $raw = & $JavaExe -version 2>&1
    $ErrorActionPreference = $prev
    $v = (@($raw) | ForEach-Object { "$_" } | Select-Object -First 1)
    if ($v -notmatch '"(\d+)(\.(\d+))?') { return }   # 解析不出就交给运行时报错
    $major = [int]$Matches[1]; if (-not $Matches[3]) { $minor = 0 } else { $minor = [int]$Matches[3] }
    $ok = if ($major -eq 1) { $minor -ge 17 } else { $major -ge 17 }   # 1.8 -> 8
    if (-not $ok) {
        Write-Host "需要 Java >= 17，当前: $v" -ForegroundColor Red
        Write-Host '设置 $env:JAVA_HOME 指向 JDK17（或 $env:AF_JAVA 指向 java.exe）后重跑' -ForegroundColor Red
        exit 1
    }
}

function Step($m) { Write-Host "== $m" -ForegroundColor Cyan }
function Check($desc, $ok) {
    if ($ok) { Write-Host "   PASS: $desc" -ForegroundColor Green }
    else { Write-Host "   FAIL: $desc" -ForegroundColor Red; $script:Fail = 1 }
}

# ---- 0. 前置：后端在线 ----
try { $null = Invoke-RestMethod "$Base/drones" -TimeoutSec 5 }
catch { Write-Host '后端未运行（先 start-all.cmd），中止' -ForegroundColor Red; exit 1 }

# ---- 1. 启动故障模拟器：gps-loss@8s:10s, battery-fault@40s, link-loss@60s:12s ----
Step '启动故障模拟器 (sysid=7, port=14541)'
$jar = Join-Path $PSScriptRoot '..\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar'
if ($env:AF_JAVA) { $java = $env:AF_JAVA } elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" } else { $java = "java.exe" }
Assert-Java17 $java
$sim = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $jar, '--port', '14541', '--sysid', '7', '--name', 'AF-FAULT-01',
    '--scenario', 'gps-loss:8:10,battery-fault:40,link-loss:60:20'
) -PassThru -WindowStyle Hidden
Start-Sleep 2

# 后端 discovery 已配置 14541（aerofleet.drone-extra-ports），等它注册
$registered = $false
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep 1
    $list = Invoke-RestMethod "$Base/drones" -TimeoutSec 5
    if ($list | Where-Object { $_.sysid -eq 7 }) { $registered = $true; break }
}
Check '故障模拟器上线 (sysid=7)' $registered
if (-not $registered) {
    Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
    Write-Host 'SMOKE TESTS FAILED' -ForegroundColor Red; exit 1
}

# ---- 2. GPS 丢失（8s 起，持续 10s）----
Step '等待 GPS 丢失窗口 (t=8..18s)'
$gpsDegraded = $false
$gpsRestored = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep 1
    $t = Invoke-RestMethod "$Base/drones/7/telemetry" -TimeoutSec 5
    if ($t.gpsHealthy -eq $false -and -not $gpsDegraded) {
        $gpsDegraded = $true
        Write-Host ("   gpsHealthy=false fixType={0} sats={1} (t~{2}s)" -f $t.fixType, $t.satellites, $i)
    }
    if ($gpsDegraded -and $t.gpsHealthy -eq $true) { $gpsRestored = $true; break }
}
Check 'GPS 丢失被检测 (gpsHealthy=false)' $gpsDegraded
Check 'GPS 恢复被检测 (gpsHealthy=true)' $gpsRestored

# ---- 3. 电池故障（40s 起，跳到 12%）----
Step '等待电池故障 (t=40s)'
$batteryFired = $false
for ($i = 0; $i -lt 25; $i++) {
    Start-Sleep 1
    $t = Invoke-RestMethod "$Base/drones/7/telemetry" -TimeoutSec 5
    if ($t.battery -ne $null -and $t.battery -le 15 -and $t.battery -ge 5) {
        $batteryFired = $true
        Write-Host ("   battery={0}% voltage={1}mV (t~{2}s)" -f $t.battery, $t.voltage, (40 + $i))
        break
    }
}
Check '电池故障被检测 (battery 跳变)' $batteryFired

# ---- 4. 链路中断（60s 起，20s 黑洞）----
Step '等待链路中断 (t=60..80s)'
$linkLost = $false
# 从 t=60 起最多观察到 t=85：中断 60..80s，心跳超时 10s，
# offline 窗口约为 70..90s，留足余量
for ($i = 0; $i -lt 35; $i++) {
    Start-Sleep 1
    $d = (Invoke-RestMethod "$Base/drones" -TimeoutSec 5) | Where-Object { $_.sysid -eq 7 }
    if ($d -and $d.online -eq $false) { $linkLost = $true; Write-Host "   online=false (心跳超时触发, t~$(60+$i)s)"; break }
}
Check '链路中断被检测 (online=false)' $linkLost

# ---- 清理 ----
Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
Write-Host "`n（sysid=7 将在 10s 心跳超时后自动离线消失）"

if ($Fail -eq 0) { Write-Host 'ALL FAULT-SCENARIO TESTS PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host 'FAULT-SCENARIO TESTS FAILED' -ForegroundColor Red; exit 1 }

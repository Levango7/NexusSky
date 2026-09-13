# AeroFleet 网络损伤回归测试（PowerShell 版）
# 前置：cloud-backend(8080/14550) 已运行（aerofleet.drone-port=14600 指向链路代理）。
# 本脚本：
#   1. 启动 link-sim（默认 lte）+ 绑定 127.0.0.2 的模拟器 —— LTE 损伤链路
#   2. 在损伤链路上跑完整任务流（上传/ARM/任务/RTL）
#   3. 换 starlink 画像验证周期黑洞下的设备存活
# 用法：powershell -File scripts\e2e-network.ps1 [-Profile lte]
param([string]$Profile = 'lte')

$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'
$Root = Join-Path $PSScriptRoot '..'
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
function Send-Cmd($sysid, $type) {
    $body = @{ type = $type } | ConvertTo-Json
    Invoke-RestMethod -Uri "$Base/drones/$sysid/commands" -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 25
}

# ---- 清理旧实验进程 ----
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.CommandLine -match 'link-sim' -or ($_.CommandLine -match 'drone-sim' -and $_.CommandLine -match 'bind-ip')) {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
}
Start-Sleep 2

# Java 探测 + 版本自检（JDK8 会静默杀掉 sim：class 61 vs 52）
if ($env:AF_JAVA) { $java = $env:AF_JAVA } elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" } else { $java = "java.exe" }
Assert-Java17 $java

# ---- 1. 损伤链路 + 段内飞机 ----
Step "启动 link-sim ($Profile) + 段内模拟器 (127.0.0.2)"
$ls = Start-Process -FilePath $java -ArgumentList @(
    '-jar', "$Root\link-sim\target\aerofleet-link-sim-0.1.0-SNAPSHOT.jar",
    '--profile', $Profile, '--port', '14600', '--drone-ip', '127.0.0.2', '--drone-port', '14540'
) -PassThru -WindowStyle Hidden
$sim = Start-Process -FilePath $java -ArgumentList @(
    '-jar', "$Root\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar",
    '--port', '14540', '--bind-ip', '127.0.0.2', '--sysid', '1', '--name', 'AF-NET-01'
) -PassThru -WindowStyle Hidden

$up = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep 1
    try {
        $d = (Invoke-RestMethod "$Base/drones" -TimeoutSec 5) | Where-Object { $_.sysid -eq 1 }
        if ($d -and $d.online) { $up = $true; break }
    } catch { }
}
Check "损伤链路上设备发现" $up
if (-not $up) { Write-Host 'NETWORK TESTS FAILED' -ForegroundColor Red; exit 1 }

# ---- 2. 损伤链路上的完整任务流 ----
Step "在 $Profile 损伤链路上执行完整任务流"
$mission = @{
    items = @(
        @{ cmd = 'takeoff';   lat = 22.5907; lon = 113.9345; alt = 30; holdTime = 0 }
        @{ cmd = 'waypoint'; lat = 22.5917; lon = 113.9345; alt = 50; holdTime = 2 }
        @{ cmd = 'waypoint'; lat = 22.5917; lon = 113.9355; alt = 50; holdTime = 2 }
        @{ cmd = 'rtl';      lat = 22.5907; lon = 113.9345; alt = 0;  holdTime = 0 }
    )
} | ConvertTo-Json -Depth 4
$upload = Invoke-RestMethod -Uri "$Base/drones/1/mission" -Method Post -Body $mission -ContentType 'application/json; charset=utf-8' -TimeoutSec 40
Write-Host "   上传: status=$($upload.status)"
Check "损伤链路任务上传" ($upload.status -eq 'ok')

foreach ($c in 'arm', 'start_mission') {
    $r = Send-Cmd 1 $c
    Write-Host "   ${c}: $($r.status)"
    Check "损伤链路命令 $c" ($r.status -eq 'ok')
}

Start-Sleep 10
$t = Invoke-RestMethod "$Base/drones/1/telemetry" -TimeoutSec 5
Write-Host ("   t=10s: alt={0}m seq={1}/{2}" -f $t.relativeAlt, $t.missionSeq, $t.missionTotal)
Check "损伤链路任务推进（起飞）" ($t.relativeAlt -gt 5)

$r = Send-Cmd 1 'rtl'
Check "损伤链路 RTL" ($r.status -eq 'ok')

Start-Sleep 3
$track = Invoke-RestMethod "$Base/drones/1/track" -TimeoutSec 5
Check "损伤链路轨迹留存 ($($track.Count) 点)" ($track.Count -gt 20)

# ---- 3. Starlink 周期黑洞存活（固定画像，与 --profile 无关都跑这一段） ----
Step "Starlink 周期黑洞存活验证（等待 65s 跨 2 个黑洞）"
Stop-Process -Id $ls.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
Start-Sleep 2
$ls2 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', "$Root\link-sim\target\aerofleet-link-sim-0.1.0-SNAPSHOT.jar",
    '--profile', 'starlink', '--port', '14600', '--drone-ip', '127.0.0.2', '--drone-port', '14540'
) -PassThru -WindowStyle Hidden
$sim2 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', "$Root\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar",
    '--port', '14540', '--bind-ip', '127.0.0.2', '--sysid', '1', '--name', 'AF-SL-01'
) -PassThru -WindowStyle Hidden

$slUp = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep 1
    try {
        $d = (Invoke-RestMethod "$Base/drones" -TimeoutSec 5) | Where-Object { $_.sysid -eq 1 }
        if ($d -and $d.online) { $slUp = $true; break }
    } catch { }
}
Check "Starlink 链路设备发现" $slUp

Start-Sleep 65   # 25s up + 2.5s down 周期：跨过至少 2 个黑洞
$d = (Invoke-RestMethod "$Base/drones" -TimeoutSec 5) | Where-Object { $_.sysid -eq 1 }
$alive = $d -and $d.online
Write-Host "   65s 后 online=$alive"
Check "周期黑洞下设备持续在线（10s 心跳容忍 2.5s 黑洞）" $alive

# ---- 清理 ----
Stop-Process -Id $ls2.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $sim2.Id -Force -ErrorAction SilentlyContinue

if ($Fail -eq 0) { Write-Host 'ALL NETWORK-IMPACT TESTS PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host 'NETWORK TESTS FAILED' -ForegroundColor Red; exit 1 }

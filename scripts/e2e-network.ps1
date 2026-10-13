# AeroFleet 网络损伤回归测试（PowerShell 版）
# 前置：cloud-backend 已运行（aerofleet.drone-port=14600 指向链路代理）。
#       8080 被占用时用 AF_BACKEND_REST_PORT 指定 REST 口（必须与后端一致）。
# 本脚本：
#   1. 启动 link-sim（默认 lte）+ 绑定 127.0.0.2 的模拟器 —— LTE 损伤链路
#   2. 在损伤链路上跑完整任务流（上传/ARM/任务/RTL）
#   3. 换 starlink 画像验证周期黑洞下的设备存活
# 用法：powershell -File scripts\e2e-network.ps1 [-Profile lte]
param([string]$Profile = 'lte')

$ErrorActionPreference = 'Stop'
$script:CleanupActions = @()
$BackendRestPort = if ($env:AF_BACKEND_REST_PORT) { [int]$env:AF_BACKEND_REST_PORT } else { 8080 }
$Base = "http://localhost:$BackendRestPort/api/v1"
$Root = Join-Path $PSScriptRoot '..'
$Fail = 0

# ---- 清理注册 ----
# 别把进程对象闭包进动作里：PowerShell scriptblock 是**调用时**动态查变量，不是定义时
# 捕获。本脚本 $ls/$sim 中途会重绑（换 starlink 那段），照 formation 的老写法清理时
# 只会拿到最后一次绑定的值，前面起的进程全漏杀。统一进清单按序遍历。
$script:E2eProcesses = New-Object System.Collections.ArrayList
function Register-E2eProcess($proc) {
    if ($null -ne $proc) { $null = $script:E2eProcesses.Add($proc) }
}
function Invoke-Cleanup {
    foreach ($p in $script:E2eProcesses) {
        try {
            if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
        } catch { }
    }
    $script:E2eProcesses.Clear()
}
trap { Invoke-Cleanup; break }

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
# 只按本脚本专属端口认领（link-sim 14600 / drone-sim 14540+bind-ip），不要匹配
# 裸 'link-sim'：那会把别的 e2e（如 e2e-mesh 的中继）或他人正在跑的进程一起杀掉。
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    $cl = $_.CommandLine
    if (($cl -match 'link-sim' -and $cl -match '14600') -or
        ($cl -match 'drone-sim' -and $cl -match '14540' -and $cl -match 'bind-ip')) {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
}
Start-Sleep 2

# ---- 前置：后端必须已在跑（本脚本不启动后端，drone-port=14600 指向链路代理）----
$backendOk = $false
try {
    $null = Invoke-RestMethod "$Base/drones" -TimeoutSec 5
    $backendOk = $true
} catch { }
if (-not $backendOk) {
    Write-Host ("后端未运行（$Base 不可达），中止。先起后端：mvn -pl cloud-backend -am package 后以 --spring.profiles.active=dev --aerofleet.drone-port=14600 启动") -ForegroundColor Red
    exit 1
}

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
    '-jar', "$Root\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar",
    '--port', '14540', '--bind-ip', '127.0.0.2', '--sysid', '1', '--name', 'AF-NET-01'
) -PassThru -WindowStyle Hidden
Register-E2eProcess $ls
Register-E2eProcess $sim

$up = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep 1
    try {
        $d = (Invoke-RestMethod "$Base/drones" -TimeoutSec 5) | Where-Object { $_.sysid -eq 1 }
        if ($d -and $d.online) { $up = $true; break }
    } catch { }
}
Check "损伤链路上设备发现" $up
if (-not $up) {
    Invoke-Cleanup
    Write-Host 'NETWORK TESTS FAILED' -ForegroundColor Red
    exit 1
}

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
# 换画像前先停掉 LTE 那对进程（不清理清单，末尾统一收尾）
foreach ($p in @($ls, $sim)) {
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
}
Start-Sleep 2
$ls2 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', "$Root\link-sim\target\aerofleet-link-sim-0.1.0-SNAPSHOT.jar",
    '--profile', 'starlink', '--port', '14600', '--drone-ip', '127.0.0.2', '--drone-port', '14540'
) -PassThru -WindowStyle Hidden
$sim2 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', "$Root\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar",
    '--port', '14540', '--bind-ip', '127.0.0.2', '--sysid', '1', '--name', 'AF-SL-01'
) -PassThru -WindowStyle Hidden
Register-E2eProcess $ls2
Register-E2eProcess $sim2

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
Invoke-Cleanup
Write-Host '已停止 link-sim / drone-sim（仅脚本启动的进程）'

if ($Fail -eq 0) { Write-Host 'ALL NETWORK-IMPACT TESTS PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host 'NETWORK TESTS FAILED' -ForegroundColor Red; exit 1 }

# AeroFleet Failsafe 回归测试（PowerShell 版）
# 前置：cloud-backend(8080/14550) 已运行（discovery 已含 14541）。
# 本脚本自行启动一台带 link-loss 故障脚本的模拟器（sysid=9, 端口 14542），
# 验证 P0 failsafe 反应链（PX4 兼容行为）：
#   1. 正常任务中段断链 -> 飞机 15s 失联阈值后自动 RTL（NAV_DLLC_ACT 行为）
#   2. 链路恢复后飞机仍在自主返航（云端看到 mode=RTL）
#   3. 落地后自主 disarm，云端最终观察到 STANDBY
# 注意：全程一旦起飞即不再发任何命令，观察飞机的自主动作。
$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'
$Fail = 0

function Step($m) { Write-Host "== $m" -ForegroundColor Cyan }
function Check($desc, $ok) {
    if ($ok) { Write-Host "   PASS: $desc" -ForegroundColor Green }
    else { Write-Host "   FAIL: $desc" -ForegroundColor Red; $script:Fail = 1 }
}

# ---- 0. 前置：后端在线 ----
try { $null = Invoke-RestMethod "$Base/drones" -TimeoutSec 5 }
catch { Write-Host '后端未运行（先 start-all.cmd），中止' -ForegroundColor Red; exit 1 }

# ---- 1. 启动故障模拟器：断链 t=60s 起持续 45s（起飞+长航点必跨窗口）----
# 注意 sysid=9 / 端口 14542 需要后端 discovery 知道 14542；本脚本直接向后端
# UDP 14550 发一帧 GCS 心跳注册模拟器（模拟 PX4 主动上报），后端会从入帧
# 学习源地址并加入路由表。
Step '启动故障模拟器 (sysid=9, port=14542, link-loss@60s:45s)'
$jar = Join-Path $PSScriptRoot '..\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar'
if ($env:AF_JAVA) { $java = $env:AF_JAVA } elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" } else { $java = "java.exe" }
$sim = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $jar, '--port', '14542', '--sysid', '9', '--name', 'AF-FAILSAFE-01',
    '--scenario', 'link-loss:60:45'
) -PassThru -WindowStyle Hidden

# 后端 discovery 不会主动找 14542：用 python/自写 UDP 探针让 9 号机先说话
# 简单方式：等故障机自己发心跳——drone-sim 收到 GCS discovery 之前不主动发。
# 所以用 PowerShell UDP 客户端从后端端口角度无法构造；替代方案：把模拟器
# 端口登记进后端 -- 需要 application.properties 配置。骨架阶段：改用 14541
# （后端默认 discovery 含 14541）——直接在 14541 上起（此前故障回归也这么用）。
Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
$sim = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $jar, '--port', '14541', '--sysid', '9', '--name', 'AF-FAILSAFE-01',
    '--scenario', 'link-loss:60:45'
) -PassThru -WindowStyle Hidden

$registered = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep 1
    try {
        $list = Invoke-RestMethod "$Base/drones" -TimeoutSec 5
        if ($list | Where-Object { $_.sysid -eq 9 }) { $registered = $true; break }
    } catch { }
}
Check '故障模拟器上线 (sysid=9)' $registered
if (-not $registered) {
    Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
    Write-Host 'FAILSAFE TESTS FAILED' -ForegroundColor Red; exit 1
}

# ---- 2. 起飞进任务（窗口 t=60s，起飞 t≈15s，单航点 ~24s 飞行必跨 60s）----
Step '上传长航点任务并起飞（断链窗口将落在飞行中段）'
$mission = @{
    items = @(
        @{ cmd = 'takeoff';   lat = 22.5907; lon = 113.9345; alt = 30; holdTime = 0 }
        @{ cmd = 'waypoint'; lat = 22.5924; lon = 113.9345; alt = 50; holdTime = 1 }
        @{ cmd = 'waypoint'; lat = 22.5924; lon = 113.9370; alt = 50; holdTime = 1 }
        @{ cmd = 'waypoint'; lat = 22.5907; lon = 113.9370; alt = 50; holdTime = 1 }
        @{ cmd = 'rtl';      lat = 22.5907; lon = 113.9345; alt = 0;  holdTime = 0 }
    )
} | ConvertTo-Json -Depth 4
$upload = Invoke-RestMethod -Uri "$Base/drones/9/mission" -Method Post -Body $mission -ContentType 'application/json; charset=utf-8' -TimeoutSec 40
Check '任务上传' ($upload.status -eq 'ok')

$arm = Invoke-RestMethod -Uri "$Base/drones/9/commands" -Method Post -Body '{"type":"arm"}' -ContentType 'application/json' -TimeoutSec 25
Check 'ARM' ($arm.status -eq 'ok')
$start = Invoke-RestMethod -Uri "$Base/drones/9/commands" -Method Post -Body '{"type":"start_mission"}' -ContentType 'application/json' -TimeoutSec 25
Check '开始任务' ($start.status -eq 'ok')

# ---- 3. 静默观察：断链(60s..105s) -> 云端 offline -> failsafe RTL -> 恢复 ----
# RTL 下降到落地全程 ~200s（45s 断链 + 10s 超时 + 返航/下降），观察窗 220s。
Step '静默观察 failsafe 反应链（起飞后 220s 内不再发命令）'
$sawOffline   = $false
$sawRtl       = $false
$sawLanded    = $false
$modeTrace    = @()
for ($i = 0; $i -lt 44; $i++) {
    Start-Sleep 5
    try {
        $d = Invoke-RestMethod "$Base/drones" -TimeoutSec 5
        $d9 = $d | Where-Object { $_.sysid -eq 9 }
        if ($null -eq $d9) { continue }
        $modeTrace += "[$($i*5+5)s] online=$($d9.online) mode=$($d9.mode) alt=$($d9.relativeAlt)"
        if (-not $d9.online) { $sawOffline = $true }
        if ($d9.online -and $d9.mode -eq 'RTL') { $sawRtl = $true }
        if ($d9.online -and $d9.mode -eq 'STANDBY' -and $d9.armed -eq $false -and $i -gt 30) { $sawLanded = $true }
    } catch { }
}
$modeTrace | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray }

Check '断链期间云端标记 offline（心跳超时 10s）' $sawOffline
Check '链路恢复后飞机在自主 RTL（failsafe 生效）' $sawRtl
Check '最终自主落地 STANDBY/非武装' $sawLanded

# ---- 清理 ----
Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue

if ($Fail -eq 0) { Write-Host 'ALL FAILSAFE TESTS PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host 'FAILSAFE TESTS FAILED' -ForegroundColor Red; exit 1 }

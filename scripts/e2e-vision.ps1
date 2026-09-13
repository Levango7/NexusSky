# AeroFleet 视觉链路回归测试（Batch A5）
# 前置：cloud-backend(8080/14550) 已运行（start-all.cmd）；JAVA_HOME 指向 JDK17
#       （无 JAVA_HOME 时 fallback 到 PATH 上的 java，需自行保证 >= 17）。
# 本脚本自行启动一台带真值 HTTP(18080) 的模拟器（MAVLink 14542），
# 三段断言：
#   1. capture    -> MAV_CMD 2000 拍照 -> geolocation truthError < 2m
#   2. camera     -> REQUEST_CAMERA_INFORMATION/SETTINGS/CAPTURE_STATUS 往返
#   3. orbit     -> 环绕任务 8 站点拍照 -> 目标每站命中 -> 航迹 ACTIVE
# 目标布置：环绕中心 = home 北 100m，静态目标 A 在中心（0m），B 在中心东 30m。
# 半径 50m、高度 60m、FOV 90° -> 每站点距目标 <= 58m，均入画。
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

# ---- 1. 启动视觉模拟器 ----
Step '启动视觉模拟器 (sysid=9, mavlink=14542, truth=18080)'
$jar = Join-Path $PSScriptRoot '..\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar'
if ($env:AF_JAVA) { $java = $env:AF_JAVA } elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" } else { $java = "java.exe" }
# 环绕中心: home 北 100m = lat 22.5916；目标 A 在中心，B 东 30m (lon+30/102790)
# spec 语法: 目标间 ';'，字段间 ':'，坐标对内 ','（speed/heading/turn 可选）
# FOV 90°@60m: 水平 ±60m / 竖直 ±33.75m -> 环绕半径 30m 时目标均在画内
$simLog = Join-Path $env:TEMP 'af-vision-sim.log'
$sim = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $jar, '--port', '14542', '--sysid', '9', '--name', 'AF-VISION-01',
    '--http-port', '18080',
    '--targets', 'static:22.5916,113.9345;static:22.5916,113.93479'
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $simLog -RedirectStandardError (Join-Path $env:TEMP 'af-vision-sim.err')
Start-Sleep 2

$registered = $false
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep 1
    $list = Invoke-RestMethod "$Base/drones" -TimeoutSec 5
    # 必须在线：offline 残影（上一轮的 9 号）不算注册成功
    if ($list | Where-Object { $_.sysid -eq 9 -and $_.online -eq $true }) { $registered = $true; break }
}
Check '视觉模拟器上线 (sysid=9)' $registered
if (-not $registered) {
    Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
    Write-Host 'VISION E2E FAILED' -ForegroundColor Red; exit 1
}

try {
    # ---- 2. 相机会话：三条 REQUEST_CAMERA_* 命令往返 ----
    Step '相机会话 (CAMERA_INFORMATION / SETTINGS / CAPTURE_STATUS)'
    # 注册 PASS 只证明后端学到了 sim 地址；sim 侧 lastPeer 要等后端的
    # GCS HEARTBEAT 探测（1s 周期）才学到。等 2s 消除竞态。
    Start-Sleep 2
    foreach ($cmdId in 518, 520, 521) {
        $body = @{ type = 'raw'; cmd = $cmdId; p1 = 0; p2 = 0; p3 = 0; p4 = 0; p5 = 0; p6 = 0; p7 = 0 } | ConvertTo-Json
        $ack = Invoke-RestMethod -Method Post -Uri "$Base/drones/9/commands" -Body $body -ContentType 'application/json' -TimeoutSec 10
        Check "MAV_CMD $cmdId ACK accepted (result=$($ack.result))" ($ack.result -eq 'MAV_RESULT_0')
    }

    # ---- 3. 单拍定位 ----
    Step '起飞 -> 飞临目标上空 -> 单拍 capture -> truth score'
    # 拍照仅接受 armed 状态。目标在 home 北 100m：飞到目标正上方 (北 100m) 悬停拍照，
    # nadir 视角下目标位于像中心 (960,540)，检出即定位。
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/9/commands" -Body (@{ type='arm' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/9/commands" -Body (@{ type='takeoff'; alt=60 } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    Start-Sleep 8   # 爬升到 60m
    # 北 100m = lat 22.5916; 东 0m。用 mission 飞一站再拍。
    $wpBody = @{
        items = @(
            @{ cmd='waypoint'; lat=22.5916; lon=113.9345; alt=60; holdTime=2 }
        )
    } | ConvertTo-Json
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/9/mission" -Body $wpBody -ContentType 'application/json' -TimeoutSec 30
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/9/commands" -Body (@{ type='start_mission' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    # 等 ~100m / 8m/s 巡航 + hold 2s + 余量
    Start-Sleep 18
    $cap = Invoke-RestMethod -Method Post -Uri "$Base/vision/drones/9/capture" -TimeoutSec 30
    $n = $cap.detections.Count
    Check "拍照完成且检出目标 ($n 个)" ($n -ge 1)
    $maxErr = ($cap.detections | Measure-Object -Property truthErrorM -Maximum).Maximum
    Check "定位误差 < 2m (max=$maxErr m)" ($maxErr -ne $null -and $maxErr -lt 2)

    # ---- 4. 环绕闭环 ----
    Step '环绕 orbit -> 逐站拍照 -> 航迹'
    # 环绕中心即目标 A 所在 (22.5916, 113.9345)；半径 25m，高度 60m。
    # 竖直半幅 33.75m：25m + wobble 偏移 1.8m << 33.75m，站站目标入画。
    # 环点航点带 2.5s hold（OrbitService 内置），等姿态从减速前倾恢复到水平。
    $orb = Invoke-RestMethod -Method Post -Uri "$Base/vision/drones/9/orbit" -Body (
        @{ lat = 22.5916; lon = 113.9345; radiusM = 25; altM = 60; photos = 4 } | ConvertTo-Json
    ) -ContentType 'application/json' -TimeoutSec 300
    Check "环绕完成 (photosTaken=$($orb.photosTaken)/4)" ($orb.photosTaken -eq 4)
    foreach ($s in $orb.shots) {
        Write-Host ("   station frame={0} lat={1:F7} lon={2:F7} alt={3:F1} r/p/y={4:F1}/{5:F1}/{6:F1} dets={7}" -f `
            $s.frameSeq, $s.shotLat, $s.shotLon, $s.altM, $s.droneRollDeg, $s.dronePitchDeg, $s.droneYawDeg, $s.detections.Count)
    }
    $hitShots = @($orb.shots | Where-Object { $_.detections.Count -ge 1 })
    Check "每站都有检出 (>=1 的站数=$($hitShots.Count))" ($hitShots.Count -eq 4)
    $orbMaxErr = 0.0
    foreach ($s in $orb.shots) {
        foreach ($d in $s.detections) {
            if ($d.truthErrorM -gt $orbMaxErr) { $orbMaxErr = $d.truthErrorM }
        }
    }
    Check "全环定位误差 < 3m (max=$orbMaxErr m)" ($orbMaxErr -lt 3)

    Step '航迹查询 tracks'
    $tr = Invoke-RestMethod "$Base/vision/drones/9/tracks" -TimeoutSec 10
    foreach ($t in $tr.tracks) {
        Write-Host ("   track id={0} state={1} hits={2} kind={3} last=({4:F7},{5:F7})" -f `
            $t.trackId, $t.state, $t.hits, $t.kind, $t.lastSeen.lat, $t.lastSeen.lon)
    }
    $active = @($tr.tracks | Where-Object { $_.state -eq 'ACTIVE' })
    Check "存在 ACTIVE 航迹 ($($tr.tracks.Count) 条, ACTIVE=$($active.Count))" ($active.Count -ge 1)
    $t101 = @($tr.tracks | Where-Object { $_.hits -ge 4 })
    Check "至少一条航迹 >= 4 hits（环绕 4 站连续命中）" ($t101.Count -ge 1)
} finally {
    Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
    if (Test-Path $simLog) {
        Write-Host '---- sim log tail ----' -ForegroundColor DarkGray
        Get-Content $simLog -Tail 25 | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray }
    }
}

if ($Fail -eq 0) { Write-Host 'VISION E2E PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host 'VISION E2E FAILED' -ForegroundColor Red; exit 1 }

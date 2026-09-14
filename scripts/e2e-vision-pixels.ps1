# AeroFleet 像素域视觉链路测试（Batch E2）
# 前置：cloud-backend(8080/14550) 已运行；JAVA_HOME 指向 JDK17。
# 场景：模拟器带静态目标 -> 起飞到目标上空 -> 单拍。后端以 pixels 模式
#       （拉真实 JPEG -> BlobDetector 检出斑块 -> 解算地理坐标）走全链，
#       真值只用于打分。证明「渲染->检测->解算」像素链路自洽，不靠元数据神谕。
# 断言：
#   1. pixels 模式检出 >= 1 个目标（纯像素，无 id）
#   2. 定位误差 < 3m（最近真值目标）
#   3. 单拍返回 sizeBytes > 0（图像字节真实存在）
$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'
$Fail = 0

# ---- Java 版本自检 ----
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

# source 切换通过 REST？看一眼后端是否接受 query 覆盖 —— 由 CaptureService 读配置，
# pixels e2e 需要后端以 pixels 启动。这里约定：跑此脚本前用
# AEROFLEET_VISION_SOURCE=pixels 或等价方式启动后端（见 README）。
# 用一个探针确认后端当前 source：先按 truth 拍一张，若返回 sizeBytes 则说明后端是 E2 版。

# ---- 1. 启动模拟器 ----
Step '启动模拟器 (sysid=12, mavlink=14544, truth=18082, 静态目标)'
$jar = Join-Path $PSScriptRoot '..\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar'
if ($env:AF_JAVA) { $java = $env:AF_JAVA } elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" } else { $java = "java.exe" }
Assert-Java17 $java
$sim = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $jar, '--port', '14544', '--sysid', '12', '--name', 'AF-PIXELS-01',
    '--http-port', '18082',
    '--targets', 'static:22.5916,113.9345;static:22.5916,113.93479'
) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-pixels-sim.log') -RedirectStandardError (Join-Path $env:TEMP 'af-pixels-sim.err')
Start-Sleep 2

$registered = $false
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep 1
    $list = Invoke-RestMethod "$Base/drones" -TimeoutSec 5
    if ($list | Where-Object { $_.sysid -eq 12 -and $_.online -eq $true }) { $registered = $true; break }
}
Check '模拟器上线 (sysid=12)' $registered
if (-not $registered) {
    Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
    Write-Host 'PIXELS E2E FAILED' -ForegroundColor Red; exit 1
}

try {
    # ---- 2. 起飞到目标上空 ----
    Step '起飞 -> 目标上空 -> 单拍'
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/12/commands" -Body (@{ type='arm' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/12/commands" -Body (@{ type='takeoff'; alt=60 } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 30
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/12/mission" -Body (@{
        items = @(@{ cmd='takeoff'; lat=22.5907; lon=113.9345; alt=60 },
                   @{ cmd='waypoint'; lat=22.5916; lon=113.9345; alt=60 })   # 目标 A 上空
    } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 30
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/12/commands" -Body (@{ type='start_mission' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    Start-Sleep 20   # ~100m / 8m/s + hold

    # ---- 3. pixels 模式单拍 ----
    # 后端需以 aerofleet.vision.source=pixels 启动（README 说明）。此处拍一张，
    # 断言 detections 不带 id（id=-1 或缺失）即证明走了 pixels 管道。
    Step 'pixels 模式单拍 -> 检测 -> 解算'
    $cap = Invoke-RestMethod -Method Post -Uri "$Base/vision/drones/12/capture" -TimeoutSec 30
    $n = @($cap.detections).Count
    Check "pixels 检出 >= 1 个目标 ($n 个)" ($n -ge 1)
    # pixels 管道: kind=blob 且 id=-1
    $allBlob = $true
    foreach ($d in $cap.detections) {
        if ($d.id -ne -1) { $allBlob = $false }
    }
    Check 'pixels 检出为 blob（无目标 id）' $allBlob
    $maxErr = 0.0
    foreach ($d in $cap.detections) {
        if ($d.truthErrorM -gt $maxErr) { $maxErr = $d.truthErrorM }
    }
    Check "pixels 定位误差 < 3m (max=$maxErr m)" ($maxErr -lt 3)

    # ---- 4. JPEG 字节真实存在 ----
    Step '图像字节存在'
    $shots = Invoke-RestMethod "http://127.0.0.1:18082/camera/shots" -TimeoutSec 5
    $last = $shots[-1]
    $size = [long]$last.sizeBytes
    Check "单拍 sizeBytes > 0 (got $size)" ($size -gt 0)
    # 且 jpg 端点可下载且是 JPEG 魔数
    $jpeg = Invoke-WebRequest "http://127.0.0.1:18082/camera/shots/$($last.frameSeq).jpg" -TimeoutSec 5
    $magic = $jpeg.RawContentStream.ReadByte()
    Check "JPEG 端点返回 JPEG 魔数 (0xFF=$magic)" ($magic -eq 0xFF)
} finally {
    Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
    if ($Fail -eq 0) { Write-Host 'PIXELS E2E PASSED' -ForegroundColor Green }
    else { Write-Host 'PIXELS E2E FAILED' -ForegroundColor Red }
}
if ($Fail -ne 0) { exit 1 }
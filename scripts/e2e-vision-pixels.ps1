# AeroFleet 像素域视觉链路测试（Batch E2）
# 前置：cloud-backend(8080/14550) 已运行，且按下面这样启动：
#         --spring.profiles.active=dev
#         --aerofleet.vision.source=pixels
#         --aerofleet.sim-truth-base=http://127.0.0.1:18082
#         --aerofleet.drone-extra-ports=14544
#       JAVA_HOME 指向 JDK17。
#       三个后端参数缺一不可：source=pixels 才会走"真 JPEG → 斑块检测"管道；
#       sim-truth-base 默认指向 18080，而本脚本的模拟器挂在 18082，指错了后端
#       拉不到 JPEG（ConnectException），整条像素链无从验证；drone-extra-ports
#       缺不得是因为 drone-sim 被动监听 14544 等 GCS 来探，而后端默认只探
#       14550 + 14541-14543 —— 不把 14544 加进来，模拟器永远注册不上
#       （首断言"模拟器上线"必 FAIL，与后端/模拟器本身无关）。
#       8080 被占用时用 AF_BACKEND_REST_PORT 指定 REST 口（后端 --server.port 要一致）。
# 场景：模拟器带静态目标 -> 起飞到目标上空 -> 单拍。后端以 pixels 模式
#       （拉真实 JPEG -> BlobDetector 检出斑块 -> 解算地理坐标）走全链，
#       真值只用于打分。证明「渲染->检测->解算」像素链路自洽，不靠元数据神谕。
# 断言：
#   1. pixels 模式检出 >= 1 个目标（纯像素，无 id）
#   2. 定位误差 < 3m（最近真值目标）
#   3. 单拍返回 sizeBytes > 0（图像字节真实存在）
$ErrorActionPreference = 'Stop'
# 跨平台公共库（B2 批次接 CI 时引入）：java 探测 / jar 路径 / 临时目录。
. (Join-Path $PSScriptRoot 'e2e-common.ps1')
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$TempDir = Get-E2eTempDir   # Linux runner 上 $env:TEMP 常未设置，不能直接用
# 本机 8080 可能被常驻服务（Docker 容器等）占用：AF_BACKEND_REST_PORT 覆盖。
# 覆盖时必须连带改后端的 MAVLink 口（默认 14550），否则 sim 发现不了本脚本要找的 sysid。
$BackendRestPort = if ($env:AF_BACKEND_REST_PORT) { [int]$env:AF_BACKEND_REST_PORT } else { 8080 }
$Base = "http://localhost:$BackendRestPort/api/v1"
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
# jar 路径走 Get-E2eJar（反斜杠字面量在 Linux 上不是分隔符，Test-Path 恒 false）；
# Java 探测走 Get-E2eJava（Linux 上叫 java 不是 java.exe）。
$jar = Get-E2eJar -Root $Root -Module 'drone-sim' -Jar 'aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar'
$java = Get-E2eJava
Assert-Java17 $java
# marker (-Dpixels-e2e=true) 放 -jar 前：JVM 消费为系统属性，并作为 CI 兜底清理凭据。
$sim = Start-Process -FilePath $java -ArgumentList @(
    '-Dpixels-e2e=true',
    '-jar', $jar, '--port', '14544', '--sysid', '12', '--name', 'AF-PIXELS-01',
    '--http-port', '18082',
    '--targets', 'static:22.5916,113.9345;static:22.5916,113.93479'
) -PassThru -RedirectStandardOutput (Join-Path $TempDir 'af-pixels-sim.log') -RedirectStandardError (Join-Path $TempDir 'af-pixels-sim.err')
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
    # 指向性诊断：drone-sim 被动监听 14544 等探测，后端默认只探 14550/14541-14543。
    # 缺 --aerofleet.drone-extra-ports=14544 时模拟器永远学不到对端地址，注册必超时。
    Write-Host '   最可能原因：后端启动参数缺 --aerofleet.drone-extra-ports=14544' -ForegroundColor Red
    Write-Host '   （drone-sim 被动监听 14544 等 GCS 来探，后端默认只探 14550/14541-14543）' -ForegroundColor Red
    Write-Host '   完整启动参数见文件头注释' -ForegroundColor Red
    Write-Host 'PIXELS E2E FAILED' -ForegroundColor Red; exit 1
}

try {
    # ---- 2. 起飞到目标上空 ----
    Step '起飞 -> 目标上空 -> 单拍'
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/12/commands" -Body (@{ type='arm' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/12/commands" -Body (@{ type='takeoff'; alt=60 } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 30
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/12/mission" -Body (@{
        items = @(@{ cmd='takeoff'; lat=22.5907; lon=113.9345; alt=60 },
                   # holdTime 必须给：到点后 mission 立即完成会触发 RTL 回家，飞机离开
                   # 目标上空，后面怎么拍都拍不到斑块。
                   @{ cmd='waypoint'; lat=22.5916; lon=113.9345; alt=60; holdTime=120 })
    } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 30
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/12/commands" -Body (@{ type='start_mission' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    # 60m 爬升约 30s（2m/s）+ home→目标约 100m 巡航约 13s（8m/s）：等 50s 才到位。
    # 原来只等 20s，飞机还在爬升段，目标根本不在画面里 —— 检出走 0 个斑块。
    Start-Sleep 50
    $t = Invoke-RestMethod "$Base/drones/12/telemetry" -TimeoutSec 5
    Write-Host ("   到位检查: lat={0} lon={1} alt={2}m seq={3}/{4}" -f $t.lat, $t.lon, $t.relativeAlt, $t.missionSeq, $t.missionTotal)

    # ---- 3. pixels 模式单拍 ----
    # 后端需以 --aerofleet.vision.source=pixels + --aerofleet.sim-truth-base=http://127.0.0.1:18082
    # 启动（见文件头）。此处拍一张，断言 detections 不带 id（id=-1 或缺失）即证明走了 pixels 管道。
    Step 'pixels 模式单拍 -> 检测 -> 解算'
    $cap = Invoke-RestMethod -Method Post -Uri "$Base/vision/drones/12/capture" -TimeoutSec 30
    # 后端把异常转成 {status:error,result:...}（HTTP 200），这里先拦一层：
    # 否则下面 $cap.detections 取不到只会当成"0 个检测"，看不出是后端拉不到 JPEG。
    if ($cap.status -eq 'error') {
        Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
        Write-Host ("   capture 失败: " + $cap.result) -ForegroundColor Red
        Write-Host '   检查后端是否按文件头启动（--aerofleet.vision.source=pixels --aerofleet.sim-truth-base=http://127.0.0.1:18082）' -ForegroundColor Red
        Write-Host 'PIXELS E2E FAILED' -ForegroundColor Red
        exit 1
    }
    # 注意：PowerShell 里 @($null).Count 等于 **1**（不是 0），所以缺失字段必须显式判空，
    # 否则"没有 detections"会被数成 1 个、后面两个断言还会跟着假绿。
    $detections = @()
    if ($null -ne $cap.detections) { $detections = @($cap.detections) }
    $n = $detections.Count
    Check "pixels 检出 >= 1 个目标 ($n 个)" ($n -ge 1)
    # pixels 管道: kind=blob 且 id=-1
    $allBlob = ($n -ge 1)
    foreach ($d in $detections) {
        if ($d.id -ne -1) { $allBlob = $false }
    }
    Check 'pixels 检出为 blob（无目标 id）' $allBlob
    $maxErr = 0.0
    foreach ($d in $detections) {
        if ($null -ne $d.truthErrorM -and $d.truthErrorM -gt $maxErr) { $maxErr = $d.truthErrorM }
    }
    Check "pixels 定位误差 < 3m (max=$maxErr m)" ($maxErr -lt 3)

    # ---- 4. JPEG 字节真实存在 ----
    Step '图像字节存在'
    $shots = Invoke-RestMethod "http://127.0.0.1:18082/camera/shots" -TimeoutSec 5
    $last = $shots[-1]
    $size = [long]$last.sizeBytes
    Check "单拍 sizeBytes > 0 (got $size)" ($size -gt 0)
    # 且 jpg 端点可下载且是 JPEG 魔数
    # 不用 Invoke-WebRequest + RawContentStream：PS 5.1 下该属性常为 null
    # （NullReferenceException，还会把整个 try 块炸掉、连累 exit code）。直接取字节。
    $jpgUrl = "http://127.0.0.1:18082/camera/shots/$($last.frameSeq).jpg"
    $bytes = (New-Object System.Net.WebClient).DownloadData($jpgUrl)
    $magic = $bytes[0]
    Check "JPEG 端点返回 JPEG 魔数 (0xFF=$magic)" ($magic -eq 0xFF)
} finally {
    Stop-Process -Id $sim.Id -Force -ErrorAction SilentlyContinue
    if ($Fail -eq 0) { Write-Host 'PIXELS E2E PASSED' -ForegroundColor Green }
    else { Write-Host 'PIXELS E2E FAILED' -ForegroundColor Red }
}
if ($Fail -ne 0) { exit 1 }
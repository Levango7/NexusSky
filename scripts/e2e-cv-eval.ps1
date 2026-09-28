# CV 评测指标层端到端验证脚本（F1）
#
# 依据：design.md §4.2 + spec.md O2
# 场景：cloud-backend + drone-sim（真值 HTTP）全链路三指标评测；
#       ARM + 起飞 60m + 飞临目标上空驻留拍摄（拍照仅接受 armed 状态，
#       drone-sim VirtualDrone.handleImageCapture 非 armed 即 MAV_RESULT_DENIED）
#
# 断言目标：
#   E1  每帧拍摄记录评测帧（capture 响应含 detectionSource/latencyMs）
#   E3  GET /cv-eval/metrics 聚合三指标（frames/recall/latencyP95Ms）
#   O1  source 过滤生效（pixels 维度帧数正确）
#   E4  POST /cv-eval/reset 清空窗口
#   S1  truth 模式识别率 ≈ 1.0（投影上界校准）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-cv-eval.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-cv-eval.ps1 -SkipBuild
#
# 前置：JDK 17 + Maven（自动构建缺失 jar）

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

# ---- 端口配置 ----
# 本机 8080 可能被常驻服务（Docker 容器等）占用：AF_BACKEND_PORT 覆盖
$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 8080 }
$DronePort = 14542
$DroneSysid = 9
$TruthPort = 18080

$Base = "http://localhost:$BackendPort/api/v1"

function Assert-True($cond, $msg) {
    if ($cond) {
        Write-Host "  [PASS] $msg" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] $msg" -ForegroundColor Red
        $script:Fail++
    }
}

function Stop-Proc($p) {
    if ($p -and -not $p.HasExited) {
        # scriptblock 非闭包：PID 内联进脚本块文本，避免 finally 执行时变量已失效
        $procId = $p.Id
        $script:CleanupActions += [scriptblock]::Create(
            "try { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue } catch {}")
    }
}

try {
    # ---- 0. 构建缺失 jar ----
    $backendJar = Join-Path $Root 'cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'
    $simJar = Join-Path $Root 'drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar'
    if (-not $SkipBuild -or -not (Test-Path $backendJar) -or -not (Test-Path $simJar)) {
        Write-Host '[1/6] 构建后端 + 模拟器...'
        $env:JAVA_HOME = 'E:\dev-tools\jdk17.0.20_8'
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        Push-Location $Root
        mvn -q package -DskipTests
        Pop-Location
    } else {
        Write-Host '[1/6] 使用既有 jar（-SkipBuild）'
    }

    if ($env:AF_JAVA) { $java = $env:AF_JAVA }
    elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    else { $java = 'java.exe' }

    # ---- 1. 起服务 ----
    Write-Host '[2/6] 启动 cloud-backend + drone-sim（真值 HTTP 18080，2 静态目标）...'
    $simLog = Join-Path $env:TEMP 'af-cveval-sim.log'
    $sim = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $simJar, '--port', "$DronePort", '--sysid', "$DroneSysid", '--name', 'AF-CVEVAL-01',
        '--http-port', "$TruthPort",
        '--targets', 'static:22.5916,113.9345;static:22.5916,113.93479'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $simLog `
        -RedirectStandardError (Join-Path $env:TEMP 'af-cveval-sim.err')
    Stop-Proc $sim

    $backendLog = Join-Path $env:TEMP 'af-cveval-backend.log'
    # C4 后主配置默认 profile=prod（强制 PostgreSQL+JWT 环境变量，fail-fast）；
    # e2e 用 dev profile（H2 + dev-mode 认证白名单）
    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $backendJar, "--server.port=$BackendPort",
        '--spring.profiles.active=dev',
        '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
        "--aerofleet.drone-port=$DronePort"
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog
    Stop-Proc $backend

    # ---- 2. 等待设备上线 ----
    Write-Host '[3/6] 等待设备上线（dev profile 全量启动约需 30-50s）...'
    $online = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $drones = (Invoke-RestMethod -Uri "$Base/drones" -TimeoutSec 2)
            $match = @($drones) | Where-Object { $_.sysid -eq $DroneSysid -and $_.online }
            if ($match) { $online = $true; break }
        } catch {}
    }
    Assert-True $online "设备 sysid=$DroneSysid 上线"
    if (-not $online) { throw 'drone not online' }

    # ---- 3. ARM + 起飞 + 飞临目标上空（驻留 30s）----
    # 拍照仅接受 armed（drone-sim VirtualDrone.handleImageCapture）；目标 A 在
    # home 北 100m（22.5916,113.9345），holdTime 驻留期内完成全部拍摄，避免
    # 任务结束自动 RTL（VirtualDrone.finishMission -> startRtl）后目标出视场。
    Write-Host '[4/6] ARM + 起飞 60m + 飞临目标上空（驻留 30s）...'
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/commands" -Body (@{ type='arm' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/commands" -Body (@{ type='takeoff'; alt=60 } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    Start-Sleep 8   # 爬升到 60m
    $wpBody = @{
        items = @(
            @{ cmd='waypoint'; lat=22.5916; lon=113.9345; alt=60; holdTime=30 }
        )
    } | ConvertTo-Json
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/mission" -Body $wpBody -ContentType 'application/json' -TimeoutSec 30
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/commands" -Body (@{ type='start_mission' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    Start-Sleep 18   # ~100m / 8m/s 巡航 ≈ 13s + 余量；到达后驻留 30s 供拍摄

    # ---- 4. truth 模式拍 3 帧（上界校准）+ pixels 模式拍 2 帧 ----
    Write-Host '[5/6] 连续拍摄评测（truth×3 + pixels×2）...'
    $okTruth = 0
    $okPixels = 0
    for ($i = 0; $i -lt 3; $i++) {
        try {
            $r = Invoke-RestMethod -Method Post -Uri "$Base/vision/drones/$DroneSysid/capture" -TimeoutSec 15
            if ($r.detectionSource -eq 'truth' -and $null -ne $r.latencyMs) { $okTruth++ }
        } catch { Start-Sleep -Milliseconds 800 }
    }
    for ($i = 0; $i -lt 2; $i++) {
        try {
            $r = Invoke-RestMethod -Method Post -Uri "$Base/vision/drones/$DroneSysid/capture?source=pixels" -TimeoutSec 15
            if ($r.detectionSource -eq 'pixels' -and $null -ne $r.latencyMs) { $okPixels++ }
        } catch { Start-Sleep -Milliseconds 800 }
    }
    Assert-True ($okTruth -eq 3) "truth 模式 3 帧拍摄成功且带 detectionSource/latencyMs（E1，实际 $okTruth）"
    Assert-True ($okPixels -eq 2) "pixels 模式 2 帧拍摄成功（E1，实际 $okPixels）"

    # ---- 5. 评测指标断言 ----
    Write-Host '[6/6] 校验三指标聚合...'
    $m = Invoke-RestMethod -Uri "$Base/cv-eval/metrics" -TimeoutSec 5
    Assert-True ($m.frames -eq 5) "评测窗口 5 帧（E3，实际 $($m.frames)）"
    Assert-True ($null -ne $m.recall) "识别率非 null"
    Assert-True ($m.recall -gt 0) "识别率 > 0（实际 $($m.recall)）"
    Assert-True ($m.latencyP95Ms -gt 0) "p95 处理时间 > 0（实际 $($m.latencyP95Ms) ms）"
    Assert-True ($m.recall -ge 0.6) "truth+pixels 混合识别率 ≥0.6（上界校准，实际 $($m.recall)）"
    Assert-True ($null -ne $m.reference -and $m.reference.recall -eq 0.85) "参考线 85%/15% 随响应返回"

    $mp = Invoke-RestMethod -Uri "$Base/cv-eval/metrics?source=pixels" -TimeoutSec 5
    Assert-True ($mp.frames -eq 2) "source=pixels 过滤 2 帧（O1，实际 $($mp.frames)）"

    $mt = Invoke-RestMethod -Uri "$Base/cv-eval/metrics?source=truth" -TimeoutSec 5
    Assert-True ($mt.recall -eq 1.0) "truth 模式识别率 = 1.0（S1 上界校准，实际 $($mt.recall)）"

    # ---- 5. reset ----
    Invoke-RestMethod -Method Post -Uri "$Base/cv-eval/reset" -TimeoutSec 5 | Out-Null
    $mr = Invoke-RestMethod -Uri "$Base/cv-eval/metrics" -TimeoutSec 5
    Assert-True ($mr.frames -eq 0) "reset 后窗口清空（E4）"

} catch {
    Write-Host "  [FAIL] 异常：$($_.Exception.Message)" -ForegroundColor Red
    $Fail++
} finally {
    foreach ($a in $script:CleanupActions) { & $a }
}

Write-Host ''
if ($Fail -eq 0) {
    Write-Host '=== e2e-cv-eval: ALL PASS ===' -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== e2e-cv-eval: $Fail FAILED ===" -ForegroundColor Red
    exit 1
}

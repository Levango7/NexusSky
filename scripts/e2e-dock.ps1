# 机巢管控端到端验证脚本（F2）
#
# 依据：.codeartsdoer/specs/f2_dock/spec.md R1-R5
# 场景：cloud-backend（dev profile）+ drone-sim dock 子命令（机巢模拟器）
#
# 断言目标：
#   R1  注册机巢 → 201；SN 重复 → 409；OSD 心跳后上线
#   R2  状态机：合法开门（IDLE→OPENING→OPEN）、非法态拒绝（409 + 状态名）
#   R3  命令经 sim 网关真下发（DockSim 收到并时序推进）
#   R4  定时任务创建（cron 非法→400）、门控 SKIPPED（无托管机）
#   R5  度量聚合形状（summary/perDay，分母零返回 null）
#   R6  GCS 面板可加载（可选，仅当 gcs-web 已构建）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-dock.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-dock.ps1 -SkipBuild
#
# 前置：JDK 17 + Maven（自动构建缺失 jar）

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

# ---- 端口配置（本机 8080 常被常驻容器占用，用环境变量覆盖）----
$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 18099 }
$DronePort = 14544
$DockHttpPort = 18081
$DroneSysid = 9

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
        # scriptblock 非闭包：PID 内联进脚本块文本（避免 finally 时变量失效）
        $procId = $p.Id
        $script:CleanupActions += [scriptblock]::Create(
            "try { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue } catch {}")
    }
}

function Try-Json($method, $uri, $body) {
    # 返回 @{ code; body }：4xx 也要能读到响应体做断言（Invoke-RestMethod 对非 2xx 抛异常）
    try {
        $req = @{ Method = $method; Uri = $uri; TimeoutSec = 15; ContentType = 'application/json' }
        if ($body) { $req['Body'] = $body }
        $r = Invoke-WebRequest @req -UseBasicParsing
        $parsed = $null
        try { $parsed = $r.Content | ConvertFrom-Json } catch {}
        return @{ code = [int]$r.StatusCode; body = $parsed }
    } catch {
        $status = $null
        try { $status = [int]$_.Exception.Response.StatusCode } catch {}
        $text = $null
        try {
            $stream = $_.Exception.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $text = $reader.ReadToEnd() | ConvertFrom-Json
        } catch {}
        return @{ code = if ($status) { $status } else { 0 }; body = $text }
    }
}

try {
    # ---- 0. 构建 ----
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

    # ---- 1. 起后端 ----
    Write-Host '[2/6] 启动 cloud-backend（dev profile）...'
    $backendLog = Join-Path $env:TEMP 'af-dock-backend.log'
    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $backendJar, "--server.port=$BackendPort",
        '--spring.profiles.active=dev',
        '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
        "--aerofleet.drone-port=$DronePort",
        "--aerofleet.dock.sim-base-url=http://127.0.0.1:$DockHttpPort",
        '--aerofleet.dock.schedule-poll-ms=5000'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog
    Stop-Proc $backend

    # 等后端健康
    $up = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $null = Invoke-RestMethod -Uri "$Base/docks" -TimeoutSec 2
            $up = $true; break
        } catch {}
    }
    Assert-True $up '后端就绪（GET /api/v1/docks 可达）'
    if (-not $up) { throw 'backend not ready' }

    # ---- 2. 注册与幂等 ----
    Write-Host '[3/6] 注册机巢（R1）...'
    $body = @{ name = 'A区无人值守机巢'; sn = 'DOCK-E2E-001'; model = 'NS-D1'; lat = 22.5916; lon = 113.9345 } | ConvertTo-Json
    $r1 = Try-Json 'POST' "$Base/docks" $body
    Assert-True ($r1.code -eq 200 -or $r1.code -eq 201) "注册成功（code=$($r1.code)）"
    $dockId = $r1.body.id
    Assert-True ($null -ne $dockId) "返回 dockId=$dockId"

    $r1b = Try-Json 'POST' "$Base/docks" $body
    Assert-True ($r1b.code -eq 409) "重复 SN → 409（实际 $($r1b.code)）"

    # ---- 3. 起机巢模拟器并推 OSD ----
    Write-Host '[4/6] 启动机巢模拟器（DockSim，HTTP 18081）并观测 OSD 上线...'
    $simLog = Join-Path $env:TEMP 'af-dock-sim.log'
    $sim = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $simJar, 'dock',
        '--sn', 'DOCK-E2E-001', '--name', 'A区无人值守机巢',
        '--http-port', "$DockHttpPort", '--drone-sysid', "$DroneSysid",
        '--osd-url', "http://127.0.0.1:$BackendPort/api/v1/docks/osd",
        '--osd-period-ms', '2000'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $simLog `
        -RedirectStandardError (Join-Path $env:TEMP 'af-dock-sim.err')
    Stop-Proc $sim
    Start-Sleep 2

    $online = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 700
        $d = Try-Json 'GET' "$Base/docks/$dockId" $null
        if ($d.body -and $d.body.state -ne 'OFFLINE') { $online = $true; break }
    }
    Assert-True $online "OSD 心跳后上线（state 离开 OFFLINE，实际 $($d.body.state)）"

    # ---- 4. 命令与状态机 ----
    Write-Host '[5/6] 命令与状态机（R2/R3）...'
    $open = Try-Json 'POST' "$Base/docks/$dockId/commands" (@{ method = 'door_open' } | ConvertTo-Json)
    Assert-True ($open.code -eq 200) "开门命令受理（code=$($open.code)）"
    Assert-True ($open.body.state -eq 'OPENING') "进入 OPENING（实际 $($open.body.state)）"

    # 机巢侧 3s 后推进到 OPEN；云端 OSD 跟随
    $reachedOpen = $false
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 700
        $d = Try-Json 'GET' "$Base/docks/$dockId" $null
        if ($d.body.state -eq 'OPEN') { $reachedOpen = $true; break }
    }
    Assert-True $reachedOpen "门开到位 OPEN（模拟器时序 + OSD 跟随，实际 $($d.body.state)）"

    # 非法态：门已开时再开门 → 409
    $bad = Try-Json 'POST' "$Base/docks/$dockId/commands" (@{ method = 'door_open' } | ConvertTo-Json)
    Assert-True ($bad.code -eq 409) "OPEN 态重复开门 → 409（实际 $($bad.code)）"
    Assert-True ($bad.body.result -match 'OPEN') "409 响应点名当前状态（$($bad.body.result)）"

    # 未知命令 → 400
    $unknown = Try-Json 'POST' "$Base/docks/$dockId/commands" (@{ method = 'nope' } | ConvertTo-Json)
    Assert-True ($unknown.code -eq 400) "未知 method → 400（实际 $($unknown.code)）"

    # 关门回 IDLE/CHARGING
    $close = Try-Json 'POST' "$Base/docks/$dockId/commands" (@{ method = 'door_close' } | ConvertTo-Json)
    Assert-True ($close.code -eq 200) "关门命令受理（code=$($close.code)）"
    $back = $false
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 700
        $d = Try-Json 'GET' "$Base/docks/$dockId" $null
        if ($d.body.state -eq 'IDLE' -or $d.body.state -eq 'CHARGING') { $back = $true; break }
    }
    Assert-True $back "关门回稳（IDLE/CHARGING，实际 $($d.body.state)）"

    # ---- 5. 定时任务与门控 ----
    Write-Host '[6/6] 定时任务与度量（R4/R5）...'
    $schedBad = Try-Json 'POST' "$Base/docks/$dockId/schedules" `
        (@{ name = '坏cron'; cron = 'not-a-cron' } | ConvertTo-Json)
    Assert-True ($schedBad.code -eq 400) "非法 cron → 400（实际 $($schedBad.code)）"

    $schedOk = Try-Json 'POST' "$Base/docks/$dockId/schedules" `
        (@{ name = '每30秒巡'; cron = '*/30 * * * * *'; waypoints = @(@(22.5916, 113.9345, 60)) } | ConvertTo-Json -Depth 5)
    Assert-True ($schedOk.code -eq 201) "创建定时任务 → 201（实际 $($schedOk.code)）"

    $schedList = Try-Json 'GET' "$Base/docks/$dockId/schedules" $null
    Assert-True (@($schedList.body).Count -ge 1) "任务列表含新任务"

    # 门控：注册机巢时未指定托管机（droneSysid=null）→ 必然 SKIPPED:no-docked-drone。
    # 等待窗口必须覆盖 cron 的最坏触发延迟：*/30 秒任务最坏 30s 才 due，
    # 加上 poll 间隔 5s 与首见登记（due() 第一次只登记 nextDue，不触发）→ 上限约 40s。
    $skipped = $false
    for ($i = 0; $i -lt 50; $i++) {
        Start-Sleep -Milliseconds 1000
        $sl = Try-Json 'GET' "$Base/docks/$dockId/schedules" $null
        $one = @($sl.body) | Where-Object { $_.lastResult -like 'SKIPPED*' }
        if ($one) { $skipped = $true; break }
    }
    Assert-True $skipped '门控不满足 → SKIPPED 记录（R4）'

    $metrics = Try-Json 'GET' "$Base/docks/$dockId/metrics?days=7" $null
    Assert-True ($metrics.code -eq 200) "度量可查（code=$($metrics.code)）"
    Assert-True ($null -ne $metrics.body.summary) 'summary 段存在'
    Assert-True ($null -ne $metrics.body.perDay) 'perDay 段存在'
    # doorCycles = 开关门次数：本脚本只开了一次门（OPENING 计数一次），故 ==1。
    # 口径与仓库定义一致（每次开门记一次，机械寿命考核用），不是"开+关各计一次"。
    Assert-True ($metrics.body.summary.doorCycles -eq 1) "开关门计数 =1（实际 $($metrics.body.summary.doorCycles)）"
    Assert-True ($metrics.body.summary.avgChargeTimeMin -eq $null -or $metrics.body.summary.avgChargeTimeMin -ge 0) `
        '平均充电时长：null（无分母）或非负值，不造假数'

    # 状态迁移时间线非空
    Assert-True (@($d.body.recentTransitions).Count -ge 3) "状态迁移日志 ≥3 条（实际 $((@($d.body.recentTransitions)).Count)）"

} catch {
    Write-Host "  [FAIL] 异常：$($_.Exception.Message)" -ForegroundColor Red
    $Fail++
} finally {
    foreach ($a in $script:CleanupActions) { & $a }
}

Write-Host ''
if ($Fail -eq 0) {
    Write-Host '=== e2e-dock: ALL PASS ===' -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== e2e-dock: $Fail FAILED ===" -ForegroundColor Red
    exit 1
}
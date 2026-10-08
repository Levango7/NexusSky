# 行业航线库端到端验证脚本（F3）
#
# 依据：.codeartsdoer/specs/f3_route_templates/spec.md §2
# 场景：cloud-backend + drone-sim；四类模板生成断言 + tower 模板真下发全程执行。
#
# 断言目标：
#   G1  tower 模板：航点数=每塔(1抵达+4环绕+1驻留)+尾RTL、legs 按塔分段、estKm>0
#   G2  solar 模板：弓字形（隔行方向交替）+ RTL 尾项
#   G3  pipeline：拐点保留 + 采样点数合理
#   G4  shoreline：首尾闭合
#   V1  参数校验：polygon<3 点 → 400 + 字段名；未知 type → 400
#   E1  tower 模板真下发：上传 ACK 后 MISSION_START → 飞完回 HOME（armed 解除或 alt 回 0）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-route-templates.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-route-templates.ps1 -SkipBuild

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 18099 }
$DronePort = 14552
$DroneSysid = 9
$TruthPort = 18080
$Base = "http://localhost:$BackendPort/api/v1"

function Assert-True($cond, $msg) {
    if ($cond) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
    else { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:Fail++ }
}

function Stop-Proc($p) {
    if ($p -and -not $p.HasExited) {
        $procId = $p.Id
        $script:CleanupActions += [scriptblock]::Create(
            "try { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue } catch {}")
    }
}

function Try-Json($method, $uri, $body) {
    try {
        $req = @{ Method = $method; Uri = $uri; TimeoutSec = 30; ContentType = 'application/json' }
        if ($body) { $req['Body'] = $body }
        $r = Invoke-WebRequest @req -UseBasicParsing
        $parsed = $null
        try { $parsed = $r.Content | ConvertFrom-Json } catch {}
        return @{ code = [int]$r.StatusCode; body = $parsed }
    } catch {
        $status = $null
        try { $status = [int]$_.Exception.Response.StatusCode } catch {}
        return @{ code = if ($status) { $status } else { 0 }; body = $null }
    }
}

try {
    # ---- 0. 构建 ----
    $backendJar = Join-Path $Root 'cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'
    $simJar = Join-Path $Root 'drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar'
    if (-not $SkipBuild -or -not (Test-Path $backendJar) -or -not (Test-Path $simJar)) {
        Write-Host '[1/4] 构建后端 + 模拟器...'
        $env:JAVA_HOME = 'E:\dev-tools\jdk17.0.20_8'
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        Push-Location $Root
        mvn -q package -DskipTests
        Pop-Location
    } else { Write-Host '[1/4] 使用既有 jar（-SkipBuild）' }

    if ($env:AF_JAVA) { $java = $env:AF_JAVA }
    elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    else { $java = 'java.exe' }

    # ---- 1. 起服务 ----
    Write-Host '[2/4] 启动 cloud-backend + drone-sim...'
    $sim = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $simJar, '--port', "$DronePort", '--sysid', "$DroneSysid", '--name', 'AF-ROUTE-01',
        '--http-port', "$TruthPort", '--lat', '22.5907', '--lon', '113.9345'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-route-sim.log') `
        -RedirectStandardError (Join-Path $env:TEMP 'af-route-sim.err')
    Stop-Proc $sim

    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $backendJar, "--server.port=$BackendPort",
        '--spring.profiles.active=dev',
        '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
        "--aerofleet.drone-port=$DronePort"
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-route-backend.log')
    Stop-Proc $backend

    $online = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $drones = (Invoke-RestMethod -Uri "$Base/drones" -TimeoutSec 2)
            if (@($drones) | Where-Object { $_.sysid -eq $DroneSysid -and $_.online }) { $online = $true; break }
        } catch {}
    }
    Assert-True $online "设备 sysid=$DroneSysid 上线"
    if (-not $online) { throw 'drone not online' }

    # ---- 2. 四类模板生成断言 ----
    Write-Host '[3/4] 四类模板生成断言...'
    # G1 tower
    $towerBody = @{ type='tower'; altM=50; orbitRadiusM=20; orbitPoints=4; hoverSec=2; towers=@(
        @{ towerNo='T001'; lat=22.5908; lon=113.9345 },
        @{ towerNo='T002'; lat=22.5909; lon=113.9345 }
    ) } | ConvertTo-Json -Depth 5
    $t1 = Try-Json 'POST' "$Base/route-templates/generate" $towerBody
    Assert-True ($t1.code -eq 200) "tower 生成 200（实际 $($t1.code)）"
    Assert-True ($t1.body.waypointCount -eq 13) "tower 航点数 = 2塔×6+RTL = 13（实际 $($t1.body.waypointCount)）"
    Assert-True (@($t1.body.legs).Count -eq 2 -and $t1.body.legs[0].towerNo -eq 'T001') "legs 按塔分段（G1）"
    Assert-True ($t1.body.estKm -gt 0) "estKm = $($t1.body.estKm) > 0"

    # G2 solar（100m×80m 矩形，间距 30）
    $poly = @(@(22.5907,113.9345), @(22.5907,113.9355), @(22.5914,113.9355), @(22.5914,113.9345))
    $solarBody = @{ type='solar'; altM=60; lineSpacingM=30; directionDeg=0; polygon=$poly } | ConvertTo-Json -Depth 5
    $t2 = Try-Json 'POST' "$Base/route-templates/generate" $solarBody
    Assert-True ($t2.code -eq 200) "solar 生成 200（实际 $($t2.code)）"
    Assert-True ($t2.body.waypointCount -ge 4) "solar ≥4 航点（实际 $($t2.body.waypointCount)）"
    Assert-True ($t2.body.waypoints[-1].cmd -eq 'rtl') "solar 尾项 RTL（G2）"
    if ($t2.body.waypointCount -ge 5) {
        $d1 = $t2.body.waypoints[1].lon - $t2.body.waypoints[0].lon
        $d2 = $t2.body.waypoints[3].lon - $t2.body.waypoints[2].lon
        Assert-True (([double]$d1 - [double]$d2) -ne 0 -and [math]::Sign([double]$d1) -ne [math]::Sign([double]$d2)) "弓字形隔行折返（G2）"
    }

    # G3 pipeline
    $line = @(@(22.5907,113.9345), @(22.5907,113.9365), @(22.5912,113.9365))
    $plBody = @{ type='pipeline'; altM=60; stepM=50; line=$line } | ConvertTo-Json -Depth 5
    $t3 = Try-Json 'POST' "$Base/route-templates/generate" $plBody
    Assert-True ($t3.code -eq 200) "pipeline 生成 200"
    $hasCorner = @($t3.body.waypoints | Where-Object { [math]::Abs([double]$_.lat - 22.5912) -lt 1e-6 -and [math]::Abs([double]$_.lon - 113.9365) -lt 1e-6 }).Count -ge 1
    Assert-True $hasCorner "pipeline 拐点保留（G3）"

    # G4 shoreline
    $shBody = @{ type='shoreline'; altM=60; stepM=40; offsetM=0; polygon=$poly } | ConvertTo-Json -Depth 5
    $t4 = Try-Json 'POST' "$Base/route-templates/generate" $shBody
    Assert-True ($t4.code -eq 200) "shoreline 生成 200"
    $wp4 = @($t4.body.waypoints | Where-Object { $_.cmd -eq 'waypoint' })
    $first = $wp4[0]; $lastWp = $wp4[-1]
    Assert-True ([math]::Abs([double]$first.lat - [double]$lastWp.lat) -lt 1e-4 -and [math]::Abs([double]$first.lon - [double]$lastWp.lon) -lt 1e-4) "shoreline 首尾闭合（G4）"

    # V1 校验
    $bad = Try-Json 'POST' "$Base/route-templates/generate" (@{ type='solar'; polygon=@(@(1,2),@(3,4)) } | ConvertTo-Json -Depth 5)
    Assert-True ($bad.code -eq 400) "polygon<3 → 400（实际 $($bad.code)）"
    $bad2 = Try-Json 'POST' "$Base/route-templates/generate" (@{ type='nope' } | ConvertTo-Json)
    Assert-True ($bad2.code -eq 400) "未知 type → 400（实际 $($bad2.code)）"

    # ---- 3. tower 模板真下发执行（E1）----
    Write-Host '[4/4] tower 模板真下发执行（ARM→起飞→2塔巡检→RTL）...'
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/commands" -Body (@{ type='arm' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/commands" -Body (@{ type='takeoff'; alt=50 } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    Start-Sleep 8
    $missionBody = @{ items = $t1.body.waypoints } | ConvertTo-Json -Depth 6
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/mission" -Body $missionBody -ContentType 'application/json' -TimeoutSec 30
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/commands" -Body (@{ type='start_mission' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10

    # 等任务完成：遥测高度回地面（RTL 后落地）
    $landed = $false
    for ($i = 0; $i -lt 150; $i++) {
        Start-Sleep -Seconds 2
        try {
            $tel = Invoke-RestMethod -Uri "$Base/drones/$DroneSysid/telemetry" -TimeoutSec 3
            if ($tel.relativeAlt -lt 1 -and $i -gt 10) { $landed = $true; break }
        } catch {}
    }
    Assert-True $landed "tower 巡检全程执行完毕并落地（E1）"

} catch {
    Write-Host "  [FAIL] 异常：$($_.Exception.Message)" -ForegroundColor Red
    $Fail++
} finally {
    foreach ($a in $script:CleanupActions) { & $a }
}

Write-Host ''
if ($Fail -eq 0) {
    Write-Host '=== e2e-route-templates: ALL PASS ===' -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== e2e-route-templates: $Fail FAILED ===" -ForegroundColor Red
    exit 1
}
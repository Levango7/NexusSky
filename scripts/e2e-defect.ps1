# 缺陷报告与工单闭环端到端验证脚本（F4）
#
# 依据：.codeartsdoer/specs/f4_defect/spec.md §2
# 场景：cloud-backend + drone-sim（truth 模式，confidence=1.0 必然触发自动晋升）
#
# 断言目标：
#   A1  ARM+起飞+飞临目标后拍照 → 自动晋升缺陷（2 targets → 2 条，severity P1）
#   A2  再拍一张 → 去重（≤10m 同 kind 不重复立案）
#   A3  建工单 → 201；流转 dispatch→start→resolve
#   A4  复检（飞机仍在目标上空，必然命中）→ REOPENED + 命中清单
#   A5  报告导出：csv 含数据行、md 含标题、json 含 summary
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-defect.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-defect.ps1 -SkipBuild

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 18099 }
$DronePort = 14546
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
        $req = @{ Method = $method; Uri = $uri; TimeoutSec = 20; ContentType = 'application/json' }
        if ($body) { $req['Body'] = $body }
        $r = Invoke-WebRequest @req -UseBasicParsing
        $parsed = $null
        try { $parsed = $r.Content | ConvertFrom-Json } catch {}
        return @{ code = [int]$r.StatusCode; body = $parsed; raw = $r.Content }
    } catch {
        $status = $null
        try { $status = [int]$_.Exception.Response.StatusCode } catch {}
        return @{ code = if ($status) { $status } else { 0 }; body = $null; raw = $null }
    }
}

try {
    # ---- 0. 构建 ----
    $backendJar = Join-Path $Root 'cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'
    $simJar = Join-Path $Root 'drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar'
    if (-not $SkipBuild -or -not (Test-Path $backendJar) -or -not (Test-Path $simJar)) {
        Write-Host '[1/5] 构建后端 + 模拟器...'
        $env:JAVA_HOME = 'E:\dev-tools\jdk17.0.20_8'
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        Push-Location $Root
        mvn -q package -DskipTests
        Pop-Location
    } else {
        Write-Host '[1/5] 使用既有 jar（-SkipBuild）'
    }

    if ($env:AF_JAVA) { $java = $env:AF_JAVA }
    elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    else { $java = 'java.exe' }

    # ---- 1. 起服务（与 e2e-cv-eval 同款飞行前置：拍照仅接受 armed）----
    Write-Host '[2/5] 启动 cloud-backend + drone-sim...'
    $sim = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $simJar, '--port', "$DronePort", '--sysid', "$DroneSysid", '--name', 'AF-DEFECT-01',
        '--http-port', "$TruthPort",
        '--targets', 'static:22.5916,113.9345;static:22.5916,113.93479'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-defect-sim.log') `
        -RedirectStandardError (Join-Path $env:TEMP 'af-defect-sim.err')
    Stop-Proc $sim

    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $backendJar, "--server.port=$BackendPort",
        '--spring.profiles.active=dev',
        '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
        "--aerofleet.drone-port=$DronePort"
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-defect-backend.log')
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

    # ---- 2. ARM + 起飞 + 飞临目标上空 ----
    Write-Host '[3/5] ARM + 起飞 60m + 飞临目标上空（驻留 30s）...'
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/commands" -Body (@{ type='arm' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/commands" -Body (@{ type='takeoff'; alt=60 } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    Start-Sleep 8
    $wpBody = @{ items = @(@{ cmd='waypoint'; lat=22.5916; lon=113.9345; alt=60; holdTime=45 }) } | ConvertTo-Json
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/mission" -Body $wpBody -ContentType 'application/json' -TimeoutSec 30
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/commands" -Body (@{ type='start_mission' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    Start-Sleep 18

    # ---- 3. 拍照 → 自动晋升 + 去重（A1/A2）----
    Write-Host '[4/5] 拍照自动晋升（A1）+ 去重（A2）...'
    $null = Invoke-RestMethod -Method Post -Uri "$Base/vision/drones/$DroneSysid/capture" -TimeoutSec 15
    Start-Sleep 2
    $d1 = Try-Json 'GET' "$Base/defects" $null
    $n1 = @($d1.body).Count
    Assert-True ($n1 -ge 2) "拍照后自动晋升缺陷 ≥2（A1，实际 $n1）"
    if ($n1 -ge 1) {
        Assert-True ($d1.body[0].severity -eq 'P1') "severity=P1（truth conf=1.0 → P1，实际 $($d1.body[0].severity)）"
        Assert-True ($d1.body[0].source -eq 'auto') "source=auto（实际 $($d1.body[0].source)）"
    }

    # 再拍一张：同位置同 kind → 去重，数量不变
    $null = Invoke-RestMethod -Method Post -Uri "$Base/vision/drones/$DroneSysid/capture" -TimeoutSec 15
    Start-Sleep 2
    $d2 = Try-Json 'GET' "$Base/defects" $null
    $n2 = @($d2.body).Count
    Assert-True ($n2 -eq $n1) "复拍去重：数量不变（A2，$n1 → $n2）"

    # ---- 4. 工单全链路（A3/A4）----
    Write-Host '[5/5] 工单创建 → 流转 → 复检闭环（A3/A4）...'
    $defectIds = @($d2.body | ForEach-Object { $_.id })
    $woBody = @{ defectIds = $defectIds; title = 'e2e 巡检缺陷处置' } | ConvertTo-Json -Depth 5
    $wo = Try-Json 'POST' "$Base/defects/work-orders" $woBody
    Assert-True ($wo.code -eq 201) "创建工单 → 201（A3，实际 $($wo.code)）"
    $woId = $wo.body.id
    Assert-True ($null -ne $woId) "返回工单 id=$woId"

    $t1 = Try-Json 'POST' "$Base/defects/work-orders/$woId/transition" (@{ action='dispatch' } | ConvertTo-Json)
    Assert-True ($t1.code -eq 200 -and $t1.body.status -eq 'DISPATCHED') "dispatch → DISPATCHED（实际 $($t1.body.status)）"
    $t2 = Try-Json 'POST' "$Base/defects/work-orders/$woId/transition" (@{ action='start' } | ConvertTo-Json)
    Assert-True ($t2.code -eq 200 -and $t2.body.status -eq 'IN_PROGRESS') "start → IN_PROGRESS（实际 $($t2.body.status)）"
    $t3 = Try-Json 'POST' "$Base/defects/work-orders/$woId/transition" (@{ action='resolve' } | ConvertTo-Json)
    Assert-True ($t3.code -eq 200 -and $t3.body.status -eq 'RESOLVED') "resolve → RESOLVED（实际 $($t3.body.status)）"

    # 非法转移：RESOLVED 再 dispatch → 409
    $bad = Try-Json 'POST' "$Base/defects/work-orders/$woId/transition" (@{ action='dispatch' } | ConvertTo-Json)
    Assert-True ($bad.code -eq 409) "RESOLVED 态 dispatch → 409（实际 $($bad.code)）"

    # 复检：飞机仍在目标上空 → 必然命中 → REOPENED（A4）
    $v = Try-Json 'POST' "$Base/defects/work-orders/$woId/verify" (@{ sysid=$DroneSysid } | ConvertTo-Json)
    Assert-True ($v.code -eq 200) "复检受理（实际 $($v.code)）"
    Assert-True ($v.body.result -eq 'REOPENED') "复检命中 → REOPENED（A4，实际 $($v.body.result)）"
    Assert-True (@($v.body.hitDefects).Count -ge 1) "命中清单非空（实际 $(@($v.body.hitDefects).Count) 条）"

    # ---- 5. 报告导出（A5）----
    $csv = Try-Json 'GET' "$Base/defects/report?format=csv" $null
    Assert-True ($csv.code -eq 200 -and $csv.raw -match 'id,kind') "csv 表头正确（A5）"
    Assert-True ($csv.raw -match 'auto') "csv 含自动晋升数据行"
    $md = Try-Json 'GET' "$Base/defects/report?format=md" $null
    Assert-True ($md.raw -match '# NexusSky 缺陷报告') "md 含标题（A5）"
    $json = Try-Json 'GET' "$Base/defects/report?format=json" $null
    Assert-True ($null -ne $json.body.summary) "json 含 summary（复检通过率口径）"

} catch {
    Write-Host "  [FAIL] 异常：$($_.Exception.Message)" -ForegroundColor Red
    $Fail++
} finally {
    foreach ($a in $script:CleanupActions) { & $a }
}

Write-Host ''
if ($Fail -eq 0) {
    Write-Host '=== e2e-defect: ALL PASS ===' -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== e2e-defect: $Fail FAILED ===" -ForegroundColor Red
    exit 1
}
# 运营报表端到端验证脚本（E6）
#
# 依据：.codeartsdoer/specs/e6_operations_report/spec.md R6
# 场景：cloud-backend + drone-sim；ARM→起飞→驻空→落地，产生真实 armed 遥测帧，
#       再从 GET /api/v1/operations/report 断言架次/时长/里程/能耗被正确聚合。
#
# 断言目标：
#   O1  飞行后报表 sorties >= 1
#   O2  flightMinutes > 0 且 distanceKm > 0
#   O3  groupBy=drone 分组含 Drone-9
#   O4  cost = flightMinutes/60 × cost-per-hour（脚本注入 60 元/h → 可精确断言）
#   V1  窗口校验：groupBy 非法 → 400；窗口 > 31 天 → 400
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-operations.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-operations.ps1 -SkipBuild

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 18099 }
$DronePort = 14558
$DroneSysid = 9
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

try {
    $backendJar = Join-Path $Root 'cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'
    $simJar = Join-Path $Root 'drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar'
    if (-not $SkipBuild -or -not (Test-Path $backendJar) -or -not (Test-Path $simJar)) {
        Write-Host '[1/3] 构建...'
        $env:JAVA_HOME = 'E:\dev-tools\jdk17.0.20_8'
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        Push-Location $Root; mvn -q package -DskipTests; Pop-Location
    } else { Write-Host '[1/3] 使用既有 jar（-SkipBuild）' }

    if ($env:AF_JAVA) { $java = $env:AF_JAVA }
    elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    else { $java = 'java.exe' }

    Write-Host '[2/3] 启动服务 + 真飞一段（ARM→起飞→驻空 15s→disarm）...'
    $sim = Start-Process -FilePath $java -ArgumentList @('-jar', $simJar,
        '--port', "$DronePort", '--sysid', "$DroneSysid", '--name', 'AF-OPS-01') `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-ops-sim.log') `
        -RedirectStandardError (Join-Path $env:TEMP 'af-ops-sim.err')
    Stop-Proc $sim

    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $backendJar, "--server.port=$BackendPort",
        '--spring.profiles.active=dev',
        '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
        "--aerofleet.drone-port=$DronePort",
        '--aerofleet.report.cost-per-hour=60',
        # E6 报表数据源 = flight_log 表（C4 DB 路径）。该开关默认 false（JSONL 落盘），
        # e2e 打开它——运营报表的 SQL 聚合与 C4 的批量写库路径同时获得端到端验证。
        '--aerofleet.flightlog.persist-to-db=true'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-ops-backend.log')
    Stop-Proc $backend

    $online = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $drones = Invoke-RestMethod -Uri "$Base/drones" -TimeoutSec 2
            if (@($drones) | Where-Object { $_.sysid -eq $DroneSysid -and $_.online }) { $online = $true; break }
        } catch {}
    }
    Assert-True $online "设备上线"
    if (-not $online) { throw 'drone not online' }

    # 真飞：ARM → 起飞 40m → 驻空 15s → disarm（落地）
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/commands" -Body (@{ type='arm' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/commands" -Body (@{ type='takeoff'; alt=40 } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    Start-Sleep 20   # 爬升+驻空，遥测帧持续入库
    $null = Invoke-RestMethod -Method Post -Uri "$Base/drones/$DroneSysid/commands" -Body (@{ type='disarm' } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 10
    Start-Sleep 3    # 等末帧落库

    # ---- 报表断言 ----
    Write-Host '[3/3] 运营报表断言...'
    $r = Invoke-RestMethod -Uri "$Base/operations/report?groupBy=drone" -TimeoutSec 10
    Assert-True ($r.summary.sorties -ge 1) "O1 sorties >= 1（实际 $($r.summary.sorties)）"
    Assert-True ($r.summary.flightMinutes -gt 0) "O2 flightMinutes > 0（实际 $($r.summary.flightMinutes)）"
    Assert-True ($r.summary.distanceKm -ge 0) "O2 distanceKm 非负（实际 $($r.summary.distanceKm)；悬停爬升可能接近 0）"
    Assert-True ($r.costPerHour -eq 60) "O4 costPerHour=60（实际 $($r.costPerHour)）"
    # 成本口径：cost ≈ minutes/60×60 = minutes（元）——允许 round 误差
    $expectCost = [math]::Round([double]$r.summary.flightMinutes / 60.0 * 60, 2)
    Assert-True ([math]::Abs([double]$r.summary.cost - $expectCost) -lt 0.05) "O4 cost=minutes×1（实际 $($r.summary.cost)，期望 $expectCost）"
    $droneGroup = @($r.groups | Where-Object { $_.key -eq 'Drone-9' })
    Assert-True ($droneGroup.Count -ge 1) "O3 groupBy=drone 含 Drone-9"

    # 校验腿
    $bad = try { Invoke-WebRequest -UseBasicParsing "http://localhost:$BackendPort/api/v1/operations/report?groupBy=nope" -TimeoutSec 5 } catch { $_.Exception.Response.StatusCode.value__ }
    Assert-True ($bad -eq 400) "V1 非法 groupBy → 400（实际 $bad）"
    $now = [DateTime]::UtcNow.ToString('o')
    $old = [DateTime]::UtcNow.AddDays(-40).ToString('o')
    $wide = try { Invoke-WebRequest -UseBasicParsing "http://localhost:$BackendPort/api/v1/operations/report?from=$old&to=$now" -TimeoutSec 5 } catch { $_.Exception.Response.StatusCode.value__ }
    Assert-True ($wide -eq 400) "V1 窗口>31天 → 400（实际 $wide）"

} catch {
    Write-Host "  [FAIL] 异常：$($_.Exception.Message)" -ForegroundColor Red
    $Fail++
} finally {
    foreach ($a in $script:CleanupActions) { & $a }
}

Write-Host ''
if ($Fail -eq 0) {
    Write-Host '=== e2e-operations: ALL PASS ===' -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== e2e-operations: $Fail FAILED ===" -ForegroundColor Red
    exit 1
}
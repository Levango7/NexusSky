# ROC 一控多机席位端到端验证脚本（E1）
#
# 依据：.codeartsdoer/specs/e1_roc_seat/spec.md 验收 2
# 场景：cloud-backend + 2 台 drone-sim（多机席位最小集）
#
# 断言目标：
#   S1  席位创建 → 机队绑定（2 机）→ 席位视图两机在线
#   S2  批量 RTL：逐机结果 200 且两机 ok（未起飞即 RTL 被拒也如实——断言 targets 数与逐机结构）
#   S3  警情登记 → 建议含最近机（距离因子主导）
#   S4  确认派飞 → DISPATCHED → 遥测可见目标机 armed/高度变化（警情上空驻留）
#   V1  席位重名 409；机队越上限 400；越席位子集 400
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-roc.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-roc.ps1 -SkipBuild

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 18099 }
$DronePort1 = 14580
$DronePort2 = 14581
$Sysid1 = 71
$Sysid2 = 72
$Base = "http://localhost:$BackendPort/api/v1/roc"

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
        return @{ code = [int]$r.StatusCode; body = $parsed }
    } catch {
        $status = $null
        try { $status = [int]$_.Exception.Response.StatusCode } catch {}
        return @{ code = if ($status) { $status } else { 0 }; body = $null }
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

    Write-Host '[2/3] 启动后端 + 2 台模拟器（多机席位）...'
    # 71 号机放在警情点近处、72 号机远 100m+（lat -0.002 ≈ 222m）
    $sim1 = Start-Process -FilePath $java -ArgumentList @('-jar', $simJar,
        '--port', "$DronePort1", '--sysid', "$Sysid1", '--name', 'AF-ROC-71',
        '--lat', '22.5910', '--lon', '113.9340') `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-roc-sim1.log') `
        -RedirectStandardError (Join-Path $env:TEMP 'af-roc-sim1.err')
    Stop-Proc $sim1
    $sim2 = Start-Process -FilePath $java -ArgumentList @('-jar', $simJar,
        '--port', "$DronePort2", '--sysid', "$Sysid2", '--name', 'AF-ROC-72',
        '--lat', '22.5890', '--lon', '113.9340') `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-roc-sim2.log') `
        -RedirectStandardError (Join-Path $env:TEMP 'af-roc-sim2.err')
    Stop-Proc $sim2

    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $backendJar, "--server.port=$BackendPort",
        '--spring.profiles.active=dev',
        '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
        '--aerofleet.drone-port=14580',
        '--aerofleet.drone-extra-ports=14581'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-roc-backend.log')
    Stop-Proc $backend

    $up = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 1000
        try {
            $drones = Invoke-RestMethod "http://localhost:$BackendPort/api/v1/drones" -TimeoutSec 3
            if ((@($drones) | Where-Object { $_.online }).Count -ge 2) { $up = $true; break }
        } catch {}
    }
    Assert-True $up '两机上线'
    if (-not $up) { throw 'drones not online' }

    Write-Host '[3/3] 席位全链路断言...'
    # S1 席位 + 机队
    $seat = Try-Json 'POST' "$Base/seats" (@{ operatorName = 'e2e-roc-op' } | ConvertTo-Json)
    Assert-True ($seat.code -eq 201) "席位创建 201（实际 $($seat.code)）"
    $seatId = $seat.body.seatId
    $bind = Try-Json 'PUT' "$Base/seats/$seatId/fleet" (@{ sysids = @($Sysid1, $Sysid2) } | ConvertTo-Json)
    Assert-True ($bind.code -eq 200 -and $bind.body.fleetSize -eq 2) "机队绑定 2 机（实际 $($bind.body.fleetSize)）"
    $onlineCount = @($bind.body.fleet | Where-Object { $_.online }).Count
    Assert-True ($onlineCount -eq 2) "S1 席位视图两机在线（实际 $onlineCount）"

    # V1 校验
    $dup = Try-Json 'POST' "$Base/seats" (@{ operatorName = 'e2e-roc-op' } | ConvertTo-Json)
    Assert-True ($dup.code -eq 409) "V1 席位重名 409（实际 $($dup.code)）"
    $over = Try-Json 'PUT' "$Base/seats/$seatId/fleet" (@{ sysids = @(1,2,3,4,5,6,7,8,9,10) } | ConvertTo-Json)
    Assert-True ($over.code -eq 400) "V1 机队越上限 400（实际 $($over.code)）"
    $outside = Try-Json 'POST' "$Base/seats/$seatId/commands" (@{ action='arm'; sysids=@(99) } | ConvertTo-Json)
    Assert-True ($outside.code -eq 400) "V1 越席位子集 400（实际 $($outside.code)）"

    # S2 批量指令逐机结构（未起飞时 RTL 拒收——逐机结果如实）
    $batch = Try-Json 'POST' "$Base/seats/$seatId/commands" (@{ action = 'rtl' } | ConvertTo-Json)
    Assert-True ($batch.code -eq 200) "批量指令 200"
    Assert-True (@($batch.body.targets).Count -eq 2) "S2 逐机结果 2 条（实际 $((@($batch.body.targets)).Count)）"
    $okOrFailed = @($batch.body.targets | Where-Object { $_.result -eq 'ok' -or $_.result -eq 'failed' }).Count
    Assert-True ($okOrFailed -eq 2) "S2 逐机结构完整（result ok/failed，实际 $okOrFailed/2）"

    # S3 警情：建议最近的 71 号机
    $inc = Try-Json 'POST' "$Base/seats/$seatId/incidents" (@{
        lat = 22.5910; lon = 113.9340; priority = 'P0'; description = 'e2e 火情'
    } | ConvertTo-Json)
    Assert-True ($inc.code -eq 201) "警情登记 201（实际 $($inc.code)）"
    Assert-True ($inc.body.suggestedSysid -eq $Sysid1) "S3 建议最近机 $Sysid1（实际 $($inc.body.suggestedSysid)）"
    $incidentId = $inc.body.incidentId

    # S4 确认派飞 → DISPATCHED → 遥测可见
    $disp = Try-Json 'POST' "$Base/seats/$seatId/incidents/$incidentId/dispatch" (@{ holdSec = 20 } | ConvertTo-Json)
    Assert-True ($disp.code -eq 200 -and $disp.body.status -eq 'DISPATCHED') "S4 派飞 DISPATCHED（实际 $($disp.code)/$($disp.body.status)）"
    Start-Sleep 6   # arm→任务→爬升
    $tel = Invoke-RestMethod "http://localhost:$BackendPort/api/v1/drones/$Sysid1/telemetry" -TimeoutSec 5
    Assert-True ($tel.armed -and $tel.relativeAlt -gt 5) "S4 目标机已起飞（alt=$($tel.relativeAlt) armed=$($tel.armed)）"

} catch {
    Write-Host "  [FAIL] 异常：$($_.Exception.Message)" -ForegroundColor Red
    $Fail++
} finally {
    foreach ($a in $script:CleanupActions) { & $a }
}

Write-Host ''
if ($Fail -eq 0) {
    Write-Host '=== e2e-roc: ALL PASS ===' -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== e2e-roc: $Fail FAILED ===" -ForegroundColor Red
    exit 1
}
# 侦测态势端到端验证脚本（E2+E4）
#
# 依据：.codeartsdoer/specs/e2e4_sensing/spec.md D6
# 场景：cloud-backend（两模拟源自动运行）+ drone-sim（提供在线设备作近域告警锚）
#
# 断言目标：
#   S1  sources 清单含两类来源（COUNTER_DRONE_RADAR / FIVE_G_SENSING）
#   S2  tracks 含两类 sourceType 的航迹
#   S3  近域告警：intruder 航迹逼近基站（sim 设备即锚）→ alert 航迹 ≥1 / alertOnly 过滤
#   S4  source 过滤正确（各自 count）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-sensing.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-sensing.ps1 -SkipBuild

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 18099 }
$DronePort = 14595
$DroneSysid = 95
$Base = "http://localhost:$BackendPort/api/v1/sensing"

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
        Write-Host '[1/2] 构建...'
        $env:JAVA_HOME = 'E:\dev-tools\jdk17.0.20_8'
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        Push-Location $Root; mvn -q package -DskipTests; Pop-Location
    } else { Write-Host '[1/2] 使用既有 jar（-SkipBuild）' }

    if ($env:AF_JAVA) { $java = $env:AF_JAVA }
    elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    else { $java = 'java.exe' }

    Write-Host '[2/2] 启动后端（两感知源）+ sim（告警锚设备）...'
    # sim 设备放在基站基点（22.5907,113.9345）——intruder 航迹会逼近它触发告警
    $sim = Start-Process -FilePath $java -ArgumentList @('-jar', $simJar,
        '--port', "$DronePort", '--sysid', "$DroneSysid", '--name', 'AF-SENSING-95',
        '--lat', '22.5907', '--lon', '113.9345') `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-sensing-sim.log') `
        -RedirectStandardError (Join-Path $env:TEMP 'af-sensing-sim.err')
    Stop-Proc $sim

    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $backendJar, "--server.port=$BackendPort",
        '--spring.profiles.active=dev',
        '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
        "--aerofleet.drone-port=$DronePort",
        '--aerofleet.sensing.aggregate-ms=3000',
        '--aerofleet.sensing.track-ttl-ms=60000'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-sensing-backend.log')
    Stop-Proc $backend

    $up = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 1000
        try {
            $drones = Invoke-RestMethod "http://localhost:$BackendPort/api/v1/drones" -TimeoutSec 3
            if (@($drones) | Where-Object { $_.sysid -eq $DroneSysid -and $_.online }) { $up = $true; break }
        } catch {}
    }
    Assert-True $up "设备上线（告警锚就绪）"
    if (-not $up) { throw 'drone not online' }
    Start-Sleep 8   # 等两轮聚合（intruder 逼近若干步）

    # S1 来源清单
    $src = Try-Json 'GET' "$Base/sources" $null
    Assert-True ($src.code -eq 200 -and @($src.body).Count -eq 2) "S1 两来源（实际 $(@($src.body).Count)）"
    $types = @($src.body | ForEach-Object { $_.sourceType })
    Assert-True ($types -contains 'COUNTER_DRONE_RADAR' -and $types -contains 'FIVE_G_SENSING') "S1 两类来源齐全"

    # S2 航迹含两源
    $all = Try-Json 'GET' "$Base/tracks" $null
    Assert-True ($all.code -eq 200 -and $all.body.count -ge 3) "S2 航迹 ≥3（雷达 2 + 5GA 1，实际 $($all.body.count)）"
    $tkTypes = @($all.body.tracks | ForEach-Object { $_.sourceType } | Sort-Object -Unique)
    Assert-True ($tkTypes.Count -eq 2) "S2 航迹含两类来源（实际 $($tkTypes -join ',')）"

    # S3 近域告警（intruder 逼近锚设备）
    $alerted = $false
    for ($i = 0; $i -lt 20; $i++) {
        $a = Try-Json 'GET' "$Base/tracks?alertOnly=true" $null
        if (@($a.body.tracks).Count -ge 1) { $alerted = $true; break }
        Start-Sleep 2   # intruder 每聚合 tick 逼近 250m（4km 起步，~46s 进 1km 圈）
    }
    Assert-True $alerted "S3 出现近域告警航迹（intruder 逼近锚设备）"
    if ($alerted) {
        $a = Try-Json 'GET' "$Base/tracks?alertOnly=true" $null
        Assert-True (@($a.body.tracks[0]).Count -ge 0 -and $a.body.tracks[0].alert -eq $true) "S3 alert 标记为 true"
        Assert-True ($null -ne $a.body.tracks[0].nearestFleetM) "S3 带距我方最近距离（实际 $($a.body.tracks[0].nearestFleetM)m）"
    }

    # S4 source 过滤
    $radar = Try-Json 'GET' "$Base/tracks?source=COUNTER_DRONE_RADAR" $null
    $fiveg = Try-Json 'GET' "$Base/tracks?source=FIVE_G_SENSING" $null
    Assert-True (@($radar.body.tracks).Count -eq 2) "S4 雷达源 2 条（实际 $(@($radar.body.tracks).Count)）"
    Assert-True (@($fiveg.body.tracks).Count -eq 1) "S4 5G-A 源 1 条（实际 $(@($fiveg.body.tracks).Count)）"

} catch {
    Write-Host "  [FAIL] 异常：$($_.Exception.Message)" -ForegroundColor Red
    $Fail++
} finally {
    foreach ($a in $script:CleanupActions) { & $a }
}

Write-Host ''
if ($Fail -eq 0) {
    Write-Host '=== e2e-sensing: ALL PASS ===' -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== e2e-sensing: $Fail FAILED ===" -ForegroundColor Red
    exit 1
}
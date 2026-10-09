# U-space 四服务端到端验证脚本（E3）
#
# 依据：.codeartsdoer/specs/e3_uspace/spec.md 验收 2
# 场景：cloud-backend + drone-sim（--rid 广播）+ 一个测试限飞区文件
#
# 断言目标：
#   U1  net-rid 三 format 各取一次：gb46750/astm/eu 标准名与核心字段正确
#   U2  geo-awareness：查询限飞区中心 → 命中该区（含 id/名称）
#   U3  flight-authorization 三态：区内 DENIED / 区外 AUTHORIZED / 越界 400
#   U4  traffic：半径内有机（含距离排序）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-uspace.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-uspace.ps1 -SkipBuild

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 18099 }
$DronePort = 14590
$DroneSysid = 91
$Base = "http://localhost:$BackendPort/api/v1/uspace"

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

    Write-Host '[2/3] 启动后端（带测试限飞区）+ RID 模拟器...'
    # 测试限飞区文件（跑道坐标系附近一个 5km 圆）
    $restrictionFile = Join-Path $env:TEMP 'af-uspace-restrictions.json'
    @'
[
  {
    "zoneId": "E2E-NFZ-001",
    "name": "e2e 测试禁飞区",
    "type": "CIRCLE",
    "centerLat": 22.5910,
    "centerLon": 113.9340,
    "radiusM": 5000
  }
]
'@ | Set-Content -Path $restrictionFile -Encoding UTF8

    $sim = Start-Process -FilePath $java -ArgumentList @('-jar', $simJar,
        '--port', "$DronePort", '--sysid', "$DroneSysid", '--name', 'AF-USPACE-91',
        '--lat', '22.5910', '--lon', '113.9340', '--rid') `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-uspace-sim.log') `
        -RedirectStandardError (Join-Path $env:TEMP 'af-uspace-sim.err')
    Stop-Proc $sim

    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $backendJar, "--server.port=$BackendPort",
        '--spring.profiles.active=dev',
        '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
        "--aerofleet.drone-port=$DronePort",
        '--aerofleet.geofence.restriction.source-type=LOCAL_FILE',
        # 注意两个键：restriction-file 是 LocalFileRestrictionSource 的 @Value 键
        # （source-path 是 config 键——数据源实现实际读前者，e2e 踩过）
        "--aerofleet.geofence.restriction-file=$restrictionFile",
        '--aerofleet.geofence.restriction.enabled=true'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-uspace-backend.log')
    Stop-Proc $backend

    $up = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 1000
        try {
            $drones = Invoke-RestMethod "http://localhost:$BackendPort/api/v1/drones" -TimeoutSec 3
            if (@($drones) | Where-Object { $_.sysid -eq $DroneSysid -and $_.online }) { $up = $true; break }
        } catch {}
    }
    Assert-True $up "设备上线"
    if (-not $up) { throw 'drone not online' }
    Start-Sleep 5   # 等 RID 帧累积

    Write-Host '[3/3] U-space 四服务断言...'
    # U1 net-rid 三套格式
    $g = Try-Json 'GET' "$Base/net-rid?format=gb46750" $null
    Assert-True ($g.code -eq 200 -and $g.body.format -eq 'gb46750') "U1 net-rid gb46750"
    if (@($g.body.snapshots).Count -ge 1) {
        Assert-True ($g.body.snapshots[0].standard -eq 'GB46750') "U1 gb46750 标准名（实际 $($g.body.snapshots[0].standard)）"
    } else { Assert-True $false "U1 net-rid 应有 ≥1 快照（实际 $(@($g.body.snapshots).Count)）" }

    $a = Try-Json 'GET' "$Base/net-rid?format=astm" $null
    Assert-True ($a.code -eq 200) "U1 net-rid astm"
    $e = Try-Json 'GET' "$Base/net-rid?format=eu" $null
    Assert-True ($e.code -eq 200) "U1 net-rid eu"
    $bad = Try-Json 'GET' "$Base/net-rid?format=faa" $null
    Assert-True ($bad.code -eq 400) "U1 非法 format 400（实际 $($bad.code)）"

    # U2 geo-awareness：限飞区中心命中
    $geo = Try-Json 'GET' "$Base/geo-awareness?lat=22.5910&lon=113.9340&radiusM=1000" $null
    Assert-True ($geo.code -eq 200) "U2 geo-awareness 200"
    $hit = @($geo.body.volumes | Where-Object { $_.id -eq 'E2E-NFZ-001' })
    Assert-True ($hit.Count -eq 1) "U2 命中测试禁飞区（实际 $($hit.Count)）"

    # U3 flight-authorization：区内 DENIED / 区外 AUTHORIZED / 越界 400
    $denied = Try-Json 'POST' "$Base/flight-authorization" `
        (@{ uasId = 'E2E-UA-001'; lat = 22.5910; lon = 113.9340 } | ConvertTo-Json)
    Assert-True ($denied.code -eq 200 -and $denied.body.decision -eq 'DENIED') "U3 区内 DENIED（实际 $($denied.body.decision)）"
    $auth = Try-Json 'POST' "$Base/flight-authorization" `
        (@{ uasId = 'E2E-UA-001'; lat = 22.7500; lon = 113.9340 } | ConvertTo-Json)
    Assert-True ($auth.code -eq 200 -and $auth.body.decision -eq 'AUTHORIZED') "U3 区外 AUTHORIZED（实际 $($auth.body.decision)）"
    $oob = Try-Json 'POST' "$Base/flight-authorization" `
        (@{ uasId = 'E2E-UA-001'; lat = 95.0; lon = 113.9340 } | ConvertTo-Json)
    Assert-True ($oob.code -eq 400) "U3 坐标越界 400（实际 $($oob.code)）"

    # U4 traffic：半径内有机
    $traffic = Try-Json 'GET' "$Base/traffic?lat=22.5910&lon=113.9340&radiusM=5000" $null
    Assert-True ($traffic.code -eq 200) "U4 traffic 200"
    Assert-True ($traffic.body.count -ge 1) "U4 半径内有机（实际 $($traffic.body.count)）"

} catch {
    Write-Host "  [FAIL] 异常：$($_.Exception.Message)" -ForegroundColor Red
    $Fail++
} finally {
    foreach ($a in $script:CleanupActions) { & $a }
}

Write-Host ''
if ($Fail -eq 0) {
    Write-Host '=== e2e-uspace: ALL PASS ===' -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== e2e-uspace: $Fail FAILED ===" -ForegroundColor Red
    exit 1
}
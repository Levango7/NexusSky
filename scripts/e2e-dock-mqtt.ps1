# 机巢 MQTT 传输端到端验证（DJI 上云 API 对接面）
#
# 依据：DJI Cloud API 官方文档 dji-sdk/Cloud-API-Doc v1.18
#       60.api-reference/20.dock-to-cloud/00.mqtt/00.topic-definition.md
# 场景：cloud-backend（transport=mqtt）+ 本地 MQTT broker（docker mosquitto）
#       + drone-sim 的 MQTT 设备侧对端（--mqtt-url）
#
# 断言目标：
#   M1  broker 起得来（docker 可用）
#   M2  机巢注册 → 201
#   M3  **OSD 经 MQTT 上报后机巢上线**（state 离开 OFFLINE）——证明设备侧上报面通
#   M4  开门命令经 services→services_reply 受理（200 + OPENING）
#   M5  门开到位 OPEN（设备侧 3s 时序 + OSD 经 MQTT 跟随）
#   M6  非法态：OPEN 态重复开门 → 409（响应点名当前状态）
#   M7  关门回稳（IDLE/CHARGING）
#   M8  对端**不提供** HTTP /services 回环端点（走错传输要立刻失败，不能悄悄成功）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-dock-mqtt.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-dock-mqtt.ps1 -SkipBuild
#
# 前置：JDK 17 + Maven + Docker（镜像 eclipse-mosquitto:2）

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
. (Join-Path $PSScriptRoot 'e2e-common.ps1')
$TempDir = Get-E2eTempDir
$Fail = 0
$script:CleanupActions = @()

# ---- 端口配置（避开 e2e-dock.ps1 用的 18099/18081/1883）----
$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 18199 }
$BrokerPort = if ($env:AF_BROKER_PORT) { [int]$env:AF_BROKER_PORT } else { 18884 }
$PeerHttpPort = if ($env:AF_PEER_HTTP_PORT) { [int]$env:AF_PEER_HTTP_PORT } else { 18091 }
$Base = "http://localhost:$BackendPort/api/v1"
$DockerName = 'af-dock-mqtt-broker'
$DockSn = 'DOCK-E2E-MQTT-001'

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
    Invoke-E2eJson -Method $method -Uri $uri -Body $body
}

try {
    # ---- 0. 构建 ----
    $backendJar = Get-E2eJar -Root $Root -Module 'cloud-backend' -Jar 'aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'
    $simJar = Get-E2eJar -Root $Root -Module 'drone-sim' -Jar 'aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar'
    if (-not $SkipBuild -or -not (Test-Path $backendJar) -or -not (Test-Path $simJar)) {
        Write-Host '[1/6] 构建后端 + 模拟器...'
        if (Test-Path 'E:\dev-tools\jdk17.0.20_8') {
            $env:JAVA_HOME = 'E:\dev-tools\jdk17.0.20_8'
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        }
        Push-Location $Root
        mvn -q package -DskipTests
        Pop-Location
    } else {
        Write-Host '[1/6] 使用既有 jar（-SkipBuild）'
    }
    $java = Get-E2eJava

    # ---- 1. 起 broker（M1）----
    Write-Host '[2/6] 起 MQTT broker（docker mosquitto）...'
    $dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $dockerCmd) {
        throw 'docker 不可用：本脚本的 broker 依赖 docker（无替代方案，不做假 broker）'
    }
    # mosquitto 2 默认只允许本机匿名连接；显式给一份配置，避免镜像默认配置差异导致连不上
    $confPath = Join-Path $TempDir 'mosquitto.conf'
    Set-Content -Path $confPath -Value @(
        'listener 1883',
        'allow_anonymous true',
        'persistence false'
    ) -Encoding ASCII
    $existing = docker ps -a --filter "name=^/$DockerName$" --format '{{.Names}}' 2>$null
    if ($existing) {
        docker rm -f $DockerName 2>&1 | Out-Null
    }
    # broker 镜像：可用 AF_MOSQUITTO_IMAGE 覆盖（CI 上优先走 mirror.gcr.io——
    # 本仓 ci.yml 的 docker-build 注释记录过「Docker Hub 拉镜像在限流下连续致红」的坑）。
    # 首选源拉不到就自动退到下一个源，而不是直接红——**镜像源问题不该冒充链路问题**。
    $brokerImages = @()
    if ($env:AF_MOSQUITTO_IMAGE) { $brokerImages += $env:AF_MOSQUITTO_IMAGE }
    $brokerImages += 'eclipse-mosquitto:2'
    $brokerImage = $null
    foreach ($img in $brokerImages) {
        Write-Host "  拉 broker 镜像 $img ..."
        docker pull $img 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { $brokerImage = $img; break }
        Write-Host "  镜像 $img 拉取失败，尝试下一个源" -ForegroundColor Yellow
    }
    if (-not $brokerImage) {
        throw "broker 镜像拉不到（试过：$($brokerImages -join ', ')）"
    }
    $cid = docker run -d --rm --name $DockerName -p "${BrokerPort}:1883" `
        -v "${confPath}:/mosquitto/config/mosquitto.conf:ro" $brokerImage 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or -not $cid.Trim()) {
        throw "broker 起不来：$cid"
    }
    $script:CleanupActions += [scriptblock]::Create(
        "try { docker rm -f $DockerName 2>&1 | Out-Null } catch {}")
    Start-Sleep -Seconds 2
    $brokerAlive = docker ps --filter "name=^/$DockerName$" --format '{{.Names}}' 2>$null
    Assert-True ([bool]$brokerAlive) "broker 容器在跑（$DockerName，宿主端口 $BrokerPort）"

    # ---- 2. 起后端（transport=mqtt）----
    Write-Host '[3/6] 启动 cloud-backend（transport=mqtt）...'
    $backendLog = Join-Path $TempDir 'af-dock-mqtt-backend.log'
    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $backendJar, "--server.port=$BackendPort",
        '--spring.profiles.active=dev',
        '--spring.datasource.url=jdbc:h2:mem:dock-mqtt-e2e;DB_CLOSE_DELAY=-1',
        '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
        '--aerofleet.dock.transport=mqtt',
        "--aerofleet.dock.mqtt-broker-url=tcp://127.0.0.1:$BrokerPort",
        '--aerofleet.dock.command-timeout-ms=8000',
        '--aerofleet.dock.schedule-poll-ms=5000'
    ) -PassThru -RedirectStandardOutput $backendLog `
        -RedirectStandardError (Join-Path $TempDir 'af-dock-mqtt-backend.err')
    Stop-Proc $backend

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

    # ---- 3. 注册机巢 ----
    Write-Host '[4/6] 注册机巢并起 MQTT 设备侧对端...'
    $body = @{ name = 'MQTT机巢'; sn = $DockSn; model = 'NS-D1'; lat = 22.5916; lon = 113.9345 } | ConvertTo-Json
    $r1 = Try-Json 'POST' "$Base/docks" $body
    Assert-True ($r1.code -eq 200 -or $r1.code -eq 201) "注册成功（code=$($r1.code)）"
    $dockId = $r1.body.id
    Assert-True ($null -ne $dockId) "返回 dockId=$dockId"

    # 设备侧对端：MQTT 模式（**不暴露** HTTP /services）
    $peerLog = Join-Path $TempDir 'af-dock-mqtt-peer.log'
    $peer = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $simJar, 'dock',
        '--sn', $DockSn, '--name', 'MQTT机巢',
        '--http-port', "$PeerHttpPort",
        '--mqtt-url', "tcp://127.0.0.1:$BrokerPort",
        '--osd-period-ms', '2000'
    ) -PassThru -RedirectStandardOutput $peerLog `
        -RedirectStandardError (Join-Path $TempDir 'af-dock-mqtt-peer.err')
    Stop-Proc $peer
    Start-Sleep -Seconds 2

    # ---- 4. OSD 经 MQTT 上报 → 上线（M3）----
    $online = $false
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 700
        $d = Try-Json 'GET' "$Base/docks/$dockId" $null
        if ($d.body -and $d.body.state -ne 'OFFLINE') { $online = $true; break }
    }
    Assert-True $online "OSD 经 MQTT 上报后上线（state 离开 OFFLINE，实际 $($d.body.state)）"

    # ---- 5. 命令经 services→services_reply（M4/M5/M6/M7）----
    Write-Host '[5/6] 命令与状态机（services → services_reply）...'
    $open = Try-Json 'POST' "$Base/docks/$dockId/commands" (@{ method = 'door_open' } | ConvertTo-Json)
    Assert-True ($open.code -eq 200) "开门命令受理（code=$($open.code)）"
    Assert-True ($open.body.state -eq 'OPENING') "进入 OPENING（实际 $($open.body.state)）"

    $reachedOpen = $false
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 700
        $d = Try-Json 'GET' "$Base/docks/$dockId" $null
        if ($d.body.state -eq 'OPEN') { $reachedOpen = $true; break }
    }
    Assert-True $reachedOpen "门开到位 OPEN（对端 3s 时序 + MQTT OSD 跟随，实际 $($d.body.state)）"

    $bad = Try-Json 'POST' "$Base/docks/$dockId/commands" (@{ method = 'door_open' } | ConvertTo-Json)
    Assert-True ($bad.code -eq 409) "OPEN 态重复开门 → 409（实际 $($bad.code)）"
    Assert-True ($bad.body.result -match 'OPEN') "409 响应点名当前状态（$($bad.body.result)）"

    $close = Try-Json 'POST' "$Base/docks/$dockId/commands" (@{ method = 'door_close' } | ConvertTo-Json)
    Assert-True ($close.code -eq 200) "关门命令受理（code=$($close.code)）"
    $back = $false
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 700
        $d = Try-Json 'GET' "$Base/docks/$dockId" $null
        if ($d.body.state -eq 'IDLE' -or $d.body.state -eq 'CHARGING') { $back = $true; break }
    }
    Assert-True $back "关门回稳（IDLE/CHARGING，实际 $($d.body.state)）"

    # ---- 6. 对端不提供 HTTP /services（M8：证明命令只能走 MQTT）----
    Write-Host '[6/6] 校验对端未开 HTTP /services 回环...'
    $httpProbe = Try-Json 'POST' "http://localhost:$PeerHttpPort/services" `
        (@{ method = 'door_open' } | ConvertTo-Json)
    Assert-True ($httpProbe.code -eq 404) "对端 HTTP /services 不可用（实际 $($httpProbe.code)）"

} catch {
    Write-Host "  [FAIL] 异常：$($_.Exception.Message)" -ForegroundColor Red
    $Fail++
} finally {
    foreach ($a in $script:CleanupActions) { & $a }
}

Write-Host ''
if ($Fail -eq 0) {
    Write-Host '=== e2e-dock-mqtt: ALL PASS ===' -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== e2e-dock-mqtt: $Fail FAILED ===" -ForegroundColor Red
    exit 1
}

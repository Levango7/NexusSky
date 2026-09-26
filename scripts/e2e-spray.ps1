# 喷洒物流端到端演示（PowerShell 自启版）
#
# 参照 e2e-spray.sh 的 7 步流程，使用 PowerShell 原生语法。
# 自启动 cloud-backend + drone-sim（参照 e2e-formation.ps1 的自启动模式）。
#
# 7 步流程：
#   1. 确认无人机在线
#   2. 创建喷洒任务
#   3. 查询喷洒状态
#   4. 打开夹爪
#   5. 关闭夹爪
#   6. 查询物流配送序列
#   7. 取消喷洒任务
#
# 所有 API 路径使用 /api/v1/ 前缀。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-spray.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-spray.ps1 -SkipBuild
#
# 前置：JDK 17 + Maven（自动构建缺失 jar）

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$Script:CleanupActions = @()
$Script:StartedProcesses = @()

# ---- 端口配置（参照 e2e-formation.ps1）----
$BackendRestPort  = 8080
$BackendDronePort = 14550
$DroneSimPort     = 14551               # 单台 drone-sim 端口（喷洒场景只需 1 架）
$DroneSysid       = 1

# ---- 超时配置 ----
$BackendStartTimeoutSec = 60
$DroneRegisterTimeoutSec = 40
$PollIntervalSec = 2

# ---- REST 基址 ----
$Base = "http://localhost:$BackendRestPort/api/v1"

# =====================================================================
# 输出辅助
# =====================================================================
function Step($m) {
    Write-Host ""
    Write-Host "================================================================" -ForegroundColor Cyan
    Write-Host "  $m" -ForegroundColor Cyan
    Write-Host "================================================================" -ForegroundColor Cyan
}
function Check($desc, $ok) {
    if ($ok) { Write-Host "   PASS: $desc" -ForegroundColor Green }
    else { Write-Host "   FAIL: $desc" -ForegroundColor Red; $script:Fail = 1 }
}
function Info($m) { Write-Host "   $m" -ForegroundColor DarkGray }

# =====================================================================
# Java 版本自检（参照 e2e-formation.ps1）
# =====================================================================
function Assert-Java17 {
    $javaExe = 'java'
    if ($env:AF_JAVA -and (Test-Path $env:AF_JAVA)) { return $env:AF_JAVA }
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $javaExe = "$env:JAVA_HOME\bin\java.exe"
    }
    try {
        $raw = & $javaExe -version 2>&1
        $v = "$(@($raw)[0])"
        if ($v -match '"(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 17) { return $javaExe }
        }
    } catch {}
    Write-Host "需要 Java >= 17，请设置 JAVA_HOME 或 AF_JAVA 环境变量" -ForegroundColor Red
    exit 1
}

# =====================================================================
# 端口可用性检查（参照 e2e-formation.ps1）
# =====================================================================
function Test-PortFree($port) {
    $listener = $null
    try {
        $listener = [System.Net.Sockets.UdpClient]::new($port)
        return $true
    } catch {
        return $false
    } finally {
        if ($listener) { $listener.Close() }
    }
}

# =====================================================================
# 清理（参照 e2e-formation.ps1）
# =====================================================================
function Register-Cleanup($action) { $Script:CleanupActions += $action }
function Invoke-Cleanup() {
    foreach ($a in $Script:CleanupActions) {
        try { & $a } catch { }
    }
    $Script:CleanupActions = @()
}
trap { Invoke-Cleanup; break }

# =====================================================================
# 清理旧实验进程（参照 e2e-formation.ps1）
# =====================================================================
function Cleanup-OldProcesses {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.CommandLine -match 'drone-sim.*spray-e2e' -or
            $_.CommandLine -match 'cloud-backend.*spray-e2e') {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep 1
}

# =====================================================================
# REST 调用辅助（参照 e2e-formation.ps1）
# =====================================================================
function Invoke-PostJson($url, $bodyObj) {
    $json = $bodyObj | ConvertTo-Json -Depth 6
    return Invoke-RestMethod -Uri $url -Method Post -Body $json -ContentType 'application/json' -TimeoutSec 10
}

function Invoke-GetJson($url) {
    return Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 10
}

# =====================================================================
# 等待后端就绪（参照 e2e-formation.ps1）
# =====================================================================
function Wait-BackendReady($timeoutSec) {
    $healthUrl = "http://localhost:$BackendRestPort/actuator/health"
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep 1
        try {
            $r = Invoke-WebRequest -Uri $healthUrl -TimeoutSec 2 -UseBasicParsing
            if ($r.StatusCode -eq 200) { return $true }
        } catch { }
    }
    return $false
}

# =====================================================================
# 等待无人机在线（参照 e2e-formation.ps1）
# =====================================================================
function Wait-DroneOnline($expectedSysid, $timeoutSec) {
    $url = "$Base/drones"
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $drones = Invoke-GetJson $url
            $d = $drones | Where-Object { $_.sysid -eq $expectedSysid }
            if ($d -and $d.online) { return $true }
        } catch { }
        Start-Sleep $PollIntervalSec
    }
    return $false
}

# ============================================================
# 前置检查（参照 e2e-formation.ps1：jar 存在、端口可用、Java>=17）
# ============================================================
Step '前置检查（jar 存在、端口可用、Java>=17）'

$backendJar  = Join-Path $Root 'cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'
$droneSimJar = Join-Path $Root 'drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar'

$backendOk  = Test-Path $backendJar
$droneSimOk = Test-Path $droneSimJar

if (-not ($backendOk -and $droneSimOk)) {
    if ($SkipBuild) {
        Check 'cloud-backend jar 存在' $backendOk
        Check 'drone-sim jar 存在' $droneSimOk
        Info "cloud-backend jar: $backendJar"
        Info "drone-sim jar: $droneSimJar"
        Info '前置条件缺失：请先用 JDK17 构建 (mvn -pl cloud-backend,drone-sim -am package -DskipTests)'
        Write-Host "`nSPRAY E2E FAILED（前置条件缺失，非喷洒缺陷）" -ForegroundColor Red
        exit 1
    }
    Info 'jar 不存在，自动构建...'
    & mvn -q -DskipTests -f "$Root\pom.xml" -pl cloud-backend,drone-sim -am package 2>&1 | ForEach-Object { Info "$_" }
    $backendOk = Test-Path $backendJar
    $droneSimOk = Test-Path $droneSimJar
}

Check 'cloud-backend jar 存在' $backendOk
Check 'drone-sim jar 存在' $droneSimOk
if (-not ($backendOk -and $droneSimOk)) {
    Info '前置条件缺失：构建失败'
    Write-Host "`nSPRAY E2E FAILED（前置条件缺失：jar 不存在，非喷洒缺陷）" -ForegroundColor Red
    exit 1
}

# Java 探测 + 版本自检
$java = Assert-Java17
Info "Java: $java"

# 端口可用性检查
$restPortFree = $true
$backendAlreadyRunning = $false
try {
    $r = Invoke-WebRequest -Uri "http://localhost:$BackendRestPort/actuator/health" -TimeoutSec 2 -UseBasicParsing
    if ($r.StatusCode -eq 200) {
        $backendAlreadyRunning = $true
        Info "cloud-backend 已在运行（端口 $BackendRestPort 可达），将复用既有后端"
    }
} catch {
    $restPortFree = $false
    try {
        $tcp = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $BackendRestPort)
        $tcp.Start(); $tcp.Stop()
        $restPortFree = $true
    } catch { }
}
if (-not $backendAlreadyRunning -and -not $restPortFree) {
    Check "REST 端口 $BackendRestPort 可用" $false
    Info '前置条件缺失：REST 端口被占用且非可达后端，请清理旧进程后重跑'
    Write-Host "`nSPRAY E2E FAILED（前置条件缺失：端口占用，非喷洒缺陷）" -ForegroundColor Red
    exit 1
}

if (-not $backendAlreadyRunning) {
    $dronePortFree = Test-PortFree $DroneSimPort
    Check "drone-sim 端口 $DroneSimPort 可用" $dronePortFree
    if (-not $dronePortFree) {
        Info "前置条件缺失：端口 $DroneSimPort 被占用，请清理旧进程后重跑"
        Write-Host "`nSPRAY E2E FAILED（前置条件缺失：端口 $DroneSimPort 占用，非喷洒缺陷）" -ForegroundColor Red
        exit 1
    }
}

Cleanup-OldProcesses

# ============================================================
# 1. 启动阶段：cloud-backend + drone-sim（参照 e2e-formation.ps1）
# ============================================================
Step '1. 启动阶段：cloud-backend + drone-sim'

$backendStartedByScript = $false
if (-not $backendAlreadyRunning) {
    Info '启动 cloud-backend...'
    $backendLog = Join-Path $env:TEMP "spray-backend.log"
    if (Test-Path $backendLog) { Remove-Item $backendLog -Force }

    $backendArgs = @(
        '-jar', $backendJar,
        "--aerofleet.drone-port=$BackendDronePort",
        "--aerofleet.drone-extra-ports=$DroneSimPort",
        '-Dspray-e2e=true'
    )

    $backend = Start-Process -FilePath $java -ArgumentList $backendArgs `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog
    Register-Cleanup { Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue }
    Info "backend PID=$($backend.Id), log=$backendLog"
    $backendStartedByScript = $true

    $up = Wait-BackendReady $BackendStartTimeoutSec
    Check 'cloud-backend 启动成功' $up
    if (-not $up) {
        Info '后端启动失败，查看日志:'
        if (Test-Path $backendLog) { Get-Content $backendLog -Tail 30 | ForEach-Object { Info $_ } }
        Write-Host "`nSPRAY E2E FAILED（后端启动失败）" -ForegroundColor Red
        Invoke-Cleanup
        exit 1
    }
} else {
    Info 'cloud-backend 已在运行，复用既有后端'
}

# 启动 drone-sim（单台，sysid=1）
$droneStartedByScript = $false
$droneNeedsStart = $true
if ($backendAlreadyRunning) {
    # 检查是否已有 sysid=1 在线
    try {
        $drones = Invoke-GetJson "$Base/drones"
        $d1 = $drones | Where-Object { $_.sysid -eq $DroneSysid }
        if ($d1 -and $d1.online) {
            Info "无人机 sysid=$DroneSysid 已在线，无需启动 drone-sim"
            $droneNeedsStart = $false
        }
    } catch { }
}

if ($droneNeedsStart) {
    Info "启动 drone-sim（sysid=$DroneSysid, port=$DroneSimPort）..."
    $droneLog = Join-Path $env:TEMP "spray-drone-$DroneSysid.log"
    if (Test-Path $droneLog) { Remove-Item $droneLog -Force }

    $droneArgs = @(
        '-jar', $droneSimJar,
        '--port', $DroneSimPort,
        '--sysid', $DroneSysid,
        '--name', 'AF-SPRAY-01',
        '-Dspray-e2e=true'
    )

    $drone = Start-Process -FilePath $java -ArgumentList $droneArgs `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog
    Register-Cleanup { Stop-Process -Id $drone.Id -Force -ErrorAction SilentlyContinue }
    Info "drone-sim PID=$($drone.Id), sysid=$DroneSysid, port=$DroneSimPort"
    $droneStartedByScript = $true

    Start-Sleep 3
    if ($drone.HasExited) {
        Check 'drone-sim 进程启动成功' $false
        Info 'drone-sim 启动失败，查看日志:'
        if (Test-Path $droneLog) { Get-Content $droneLog -Tail 20 | ForEach-Object { Info $_ } }
        Write-Host "`nSPRAY E2E FAILED（drone-sim 启动失败）" -ForegroundColor Red
        Invoke-Cleanup
        exit 1
    }
    Check 'drone-sim 进程启动成功' $true
}

# ============================================================
# 2. 注册等待：无人机在线
# ============================================================
Step "2. 注册等待：无人机 sysid=$DroneSysid 在线"

Info "等待无人机 sysid=$DroneSysid 在线注册（超时 ${DroneRegisterTimeoutSec}s）..."
$registered = Wait-DroneOnline $DroneSysid $DroneRegisterTimeoutSec
Check "无人机 sysid=$DroneSysid 已上线" $registered
if (-not $registered) {
    Info '注册超时，查看当前 /drones 状态:'
    try {
        $drones = Invoke-GetJson "$Base/drones"
        foreach ($d in $drones) { Info "  sysid=$($d.sysid), online=$($d.online)" }
    } catch { Info "  /drones 不可达: $_" }
    Write-Host "`nSPRAY E2E FAILED（无人机注册失败）" -ForegroundColor Red
    Invoke-Cleanup
    exit 1
}

# 打印注册详情
try {
    $drones = Invoke-GetJson "$Base/drones"
    foreach ($d in $drones) {
        if ($d.sysid -eq $DroneSysid) {
            Info "  sysid=$($d.sysid), online=$($d.online), lat=$($d.lat), lon=$($d.lon), alt=$($d.relativeAlt), battery=$($d.battery)"
        }
    }
} catch { }

# ============================================================
# 3. 确认无人机在线（7步流程第1步）
# ============================================================
Step '3/7 确认无人机在线（GET /api/v1/drones）'
try {
    $drones = Invoke-GetJson "$Base/drones"
    $d1 = $drones | Where-Object { $_.sysid -eq $DroneSysid }
    if ($d1) {
        Info "无人机 sysid=$DroneSysid: online=$($d1.online), battery=$($d1.battery)"
        Check "无人机 sysid=$DroneSysid 在线" ($d1.online -eq $true)
    } else {
        Check "无人机 sysid=$DroneSysid 在线" $false
    }
} catch {
    Info "请求失败: $_"
    Check '查询无人机列表' $false
}

# ============================================================
# 4. 创建喷洒任务（7步流程第2步）
# ============================================================
Step '4/7 创建喷洒任务（POST /api/v1/spray/task）— rateLpm=2.5, totalLiters=50, 2段'

$sprayBody = @{
    sysid = $DroneSysid
    rateLpm = 2.5
    totalLiters = 50
    segments = @(
        @{ id = 1; startLat = 22.5907; startLon = 113.9345; endLat = 22.5917; endLon = 113.9345 },
        @{ id = 2; startLat = 22.5917; startLon = 113.9345; endLat = 22.5917; endLon = 113.9355 }
    )
}
$taskId = $null
try {
    $sprayResp = Invoke-PostJson "$Base/spray/task" $sprayBody
    Info "响应: $($sprayResp | ConvertTo-Json -Compress -Depth 4)"
    $taskId = $sprayResp.taskId
    if (-not $taskId) { $taskId = $sprayResp.task_id }
    if (-not $taskId) { $taskId = $sprayResp.id }
    Check '返回 taskId 非空' ($taskId -ne $null -and $taskId -ne '')
} catch {
    Info "请求失败: $_"
    Check '创建喷洒任务' $false
}
Info "任务 ID: $taskId"

# ============================================================
# 5. 查询喷洒状态（7步流程第3步）
# ============================================================
Step '5/7 查询喷洒状态（GET /api/v1/spray/status/1）'
Start-Sleep 1
try {
    $stResp = Invoke-GetJson "$Base/spray/status/$DroneSysid"
    Info "响应: $($stResp | ConvertTo-Json -Compress -Depth 4)"
    $pumpState = $null
    if ($stResp.pump -is [string]) {
        $pumpState = $stResp.pump
    } elseif ($stResp.pump -and $stResp.pump.state) {
        $pumpState = $stResp.pump.state
    } elseif ($stResp.pump) {
        $pumpState = "$($stResp.pump)"
    }
    Check '状态包含 pump 字段' ($pumpState -ne $null -and $pumpState -ne '')
    Info "泵状态: $pumpState"
} catch {
    Info "请求失败: $_"
    Check '查询喷洒状态' $false
}

# ============================================================
# 6. 打开夹爪（7步流程第4步）
# ============================================================
Step '6/7 打开夹爪（POST /api/v1/spray/gripper {open:true}）'
$gripperOpenBody = @{ sysid = $DroneSysid; open = $true }
try {
    $grResp = Invoke-PostJson "$Base/spray/gripper" $gripperOpenBody
    Info "响应: $($grResp | ConvertTo-Json -Compress -Depth 4)"
    $openOk = ($grResp.status -eq 'ok') -or ($grResp.ok -eq $true) -or ($grResp.success -eq $true)
    Check '夹爪打开返回成功' $openOk
} catch {
    Info "请求失败: $_"
    Check '夹爪打开' $false
}

# ============================================================
# 7. 关闭夹爪（7步流程第5步）
# ============================================================
Step '7/7 关闭夹爪（POST /api/v1/spray/gripper {open:false}）'
$gripperCloseBody = @{ sysid = $DroneSysid; open = $false }
try {
    $gr2Resp = Invoke-PostJson "$Base/spray/gripper" $gripperCloseBody
    Info "响应: $($gr2Resp | ConvertTo-Json -Compress -Depth 4)"
    $closeOk = ($gr2Resp.status -eq 'ok') -or ($gr2Resp.ok -eq $true) -or ($gr2Resp.success -eq $true)
    Check '夹爪关闭返回成功' $closeOk
} catch {
    Info "请求失败: $_"
    Check '夹爪关闭' $false
}

# ============================================================
# 8. 查询物流配送序列（7步流程第6步）
# ============================================================
Step '8/7 查询物流配送序列（GET /api/v1/delivery/sequence/1）'
try {
    $seqResp = Invoke-GetJson "$Base/delivery/sequence/$DroneSysid"
    Info "响应: $($seqResp | ConvertTo-Json -Compress -Depth 4)"
    $sites = 0
    if ($seqResp.sites) { $sites = $seqResp.sites.Count }
    elseif ($seqResp.stations) { $sites = $seqResp.stations.Count }
    elseif ($seqResp.segments) { $sites = $seqResp.segments.Count }
    Check '配送序列包含站点列表' ($sites -ge 1)
    Info "配送序列站点数: $sites"
} catch {
    Info "请求失败: $_"
    Check '查询物流配送序列' $false
}

# ============================================================
# 9. 取消喷洒任务（7步流程第7步）
# ============================================================
Step '9/7 取消喷洒任务（POST /api/v1/spray/cancel）'
if ($taskId) {
    $cancelBody = @{ sysid = $DroneSysid; taskId = $taskId }
} else {
    $cancelBody = @{ sysid = $DroneSysid }
}
try {
    $cancelResp = Invoke-PostJson "$Base/spray/cancel" $cancelBody
    Info "响应: $($cancelResp | ConvertTo-Json -Compress -Depth 4)"
    $cancelOk = ($cancelResp.status -eq 'ok') -or ($cancelResp.ok -eq $true) -or ($cancelResp.success -eq $true) -or
                ($cancelResp.status -match 'cancel')
    Check '取消任务返回成功' $cancelOk
} catch {
    Info "请求失败: $_"
    Check '取消喷洒任务' $false
}

# ============================================================
# 清理阶段：停止脚本启动的进程
# ============================================================
Step '清理阶段：停止脚本启动的进程'

Invoke-Cleanup
Info '已停止 cloud-backend / drone-sim（仅脚本启动的进程）'

# ============================================================
# 结果汇总
# ============================================================
Write-Host ""
if ($Fail -eq 0) {
    Write-Host 'SPRAY E2E PASSED（喷洒物流端到端演示验证通过）' -ForegroundColor Green
    Write-Host '  覆盖：确认在线 → 创建喷洒任务 → 查询状态 → 夹爪控制 → 配送序列 → 取消任务' -ForegroundColor DarkGray
    exit 0
} else {
    Write-Host 'SPRAY E2E FAILED（部分断言未通过，见上方 FAIL 行）' -ForegroundColor Red
    exit 1
}
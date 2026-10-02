# RID 端到端验证脚本（C2-T24）
#
# 依据：design.md §RID + spec.md FR-RID
# 场景：cloud-backend + drone-sim --rid 全链路 RID 验证
#
# 断言目标：
#   1. drone-sim 启动后被 cloud-backend 发现（≤10s）
#   2. RID 状态为 BROADCASTING（GET /api/v1/rid/status/1）
#   3. RID 状态列表包含 sysid=1（GET /api/v1/rid/status）
#   4. BasicId 数据包含 serialNo=TEST-001（basicId.uasId）
#   5. Location 数据包含 latitude/longitude
#   6. System 数据包含 operatorLocationType
#   7. 停止 drone-sim → 等待超时 → 状态变为 BROADCASTING_ERROR
#   8. 清理：停止所有进程
#
# 前置检查：cloud-backend / drone-sim jar 存在、端口可用
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-rid.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-rid.ps1 -SkipBuild
#
# 前置：JDK 17 + Maven（自动构建缺失 jar）

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

# ---- 端口配置 ----
$BackendPort = 8080
$DronePort = 14540
$DroneSysid = 1
$DroneSerialNo = 'TEST-001'

# ---- RID 超时配置 ----
# cloud-backend RidConfig: timeoutPeriods=3, broadcastInterval=1.0s → 超时 3s
# 为加速超时验证，通过命令行参数覆盖为更短周期
$RidTimeoutPeriods = 3
$RidBroadcastInterval = 1.0

# ---- REST 基址 ----
$Base = "http://localhost:$BackendPort/api/v1/rid"

# ============================================================
# 输出辅助
# ============================================================
function Step($m) {
    Write-Host "`n== $m" -ForegroundColor Cyan
}
function Check($desc, $ok) {
    if ($ok) { Write-Host "   PASS: $desc" -ForegroundColor Green }
    else { Write-Host "   FAIL: $desc" -ForegroundColor Red; $script:Fail = 1 }
}
function Info($m) { Write-Host "   $m" -ForegroundColor DarkGray }

# ============================================================
# Java 版本自检（JDK8 不支持 record 语法）
# ============================================================
function Assert-Java17([string]$JavaExe) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $raw = & $JavaExe -version 2>&1
    $ErrorActionPreference = $prev
    $v = (@($raw) | ForEach-Object { "$_" } | Select-Object -First 1)
    if ($v -notmatch '"(\d+)(\.(\d+))?') { return }
    $major = [int]$Matches[1]; if (-not $Matches[3]) { $minor = 0 } else { $minor = [int]$Matches[3] }
    $ok = if ($major -eq 1) { $minor -ge 17 } else { $major -ge 17 }
    if (-not $ok) {
        Write-Host "需要 Java >= 17，当前: $v" -ForegroundColor Red
        Write-Host '设置 $env:JAVA_HOME 指向 JDK17（或 $env:AF_JAVA 指向 java.exe）后重跑' -ForegroundColor Red
        exit 1
    }
}

# ============================================================
# 端口可用性检查
# ============================================================
function Test-PortFree($port) {
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
        $listener.Start()
        $listener.Stop()
        return $true
    } catch {
        return $false
    }
}

# ============================================================
# HTTP 请求辅助
# ============================================================
function Send-GetJson($url) {
    $resp = Invoke-WebRequest -Uri $url -Method Get -TimeoutSec 30 -UseBasicParsing
    return $resp.Content | ConvertFrom-Json
}

# ============================================================
# 清理注册
# ============================================================
function Register-Cleanup($action) { $script:CleanupActions += $action }
function Invoke-Cleanup() {
    foreach ($a in $script:CleanupActions) {
        try { & $a } catch { }
    }
    $script:CleanupActions = @()
}
trap { Invoke-Cleanup; break }

# ============================================================
# 清理旧实验进程（避免端口占用）
# ============================================================
function Cleanup-OldProcesses {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.CommandLine -match 'cloud-backend.*rid-e2e' `
            -or $_.CommandLine -match 'drone-sim.*rid-e2e') {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep 1
}

# ============================================================
# 构建 jar（如果缺失且未指定 -SkipBuild）
# ============================================================
function Build-Jars {
    $mavenCmd = 'mvn'
    if ($env:MAVEN_HOME -and (Test-Path (Join-Path $env:MAVEN_HOME "bin\mvn.cmd"))) {
        $mavenCmd = Join-Path $env:MAVEN_HOME "bin\mvn.cmd"
    }

    # 设置 JDK17：尊重已设置且可用的 JAVA_HOME；未设/无效时回退 AF_JDK17_HOME → 内置默认路径
    if (-not ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe")))) {
        $jdk17Home = $env:AF_JDK17_HOME
        if (-not $jdk17Home) { $jdk17Home = 'E:\dev-tools\jdk17.0.20_8' }
        if (Test-Path $jdk17Home) {
            $env:JAVA_HOME = $jdk17Home
            Info "JAVA_HOME 设置为 $jdk17Home"
        }
    }

    Step '构建缺失的 jar（JDK17）'
    foreach ($module in @('cloud-backend', 'drone-sim')) {
        $jarPath = Join-Path $Root "$module\target\*$module*.jar"
        if (-not (Get-ChildItem $jarPath -ErrorAction SilentlyContinue)) {
            Info "构建 $module ..."
            & $mavenCmd -pl $module -am package -DskipTests -q
            if ($LASTEXITCODE -ne 0) {
                Write-Host "构建 $module 失败" -ForegroundColor Red
                exit 1
            }
            Info "$module 构建完成"
        } else {
            Info "$module jar 已存在，跳过构建"
        }
    }
}

# ============================================================
# 主流程
# ============================================================

# ---- Java 探测 + 版本自检 ----
if ($env:AF_JAVA) { $java = $env:AF_JAVA }
elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" }
elseif ($env:AF_JDK17_HOME -and (Test-Path (Join-Path $env:AF_JDK17_HOME "bin\java.exe"))) { $java = Join-Path $env:AF_JDK17_HOME "bin\java.exe" }
else {
    # 默认使用 JDK17 路径
    $jdk17Default = 'E:\dev-tools\jdk17.0.20_8\bin\java.exe'
    if (Test-Path $jdk17Default) { $java = $jdk17Default }
    else { $java = "java.exe" }
}
Assert-Java17 $java
Info "Java: $java"

# ---- 前置检查：jar 存在 ----
Step '前置检查（jar 存在、端口可用）'

$backendJar = Get-ChildItem (Join-Path $Root 'cloud-backend\target\*cloud-backend*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
$droneSimJar = Get-ChildItem (Join-Path $Root 'drone-sim\target\*drone-sim*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1

$backendOk = $null -ne $backendJar
$droneSimOk = $null -ne $droneSimJar

Check 'cloud-backend jar 存在' $backendOk
Check 'drone-sim jar 存在' $droneSimOk

if (-not ($backendOk -and $droneSimOk)) {
    if ($SkipBuild) {
        Info '前置条件缺失：jar 不存在且 -SkipBuild 已指定，无法继续'
        Write-Host '`nRID E2E FAILED（前置条件缺失）' -ForegroundColor Red
        exit 1
    }
    Info '部分 jar 缺失，自动构建...'
    Build-Jars
    # 重新检测
    $backendJar = Get-ChildItem (Join-Path $Root 'cloud-backend\target\*cloud-backend*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
    $droneSimJar = Get-ChildItem (Join-Path $Root 'drone-sim\target\*drone-sim*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
    $backendOk = $null -ne $backendJar
    $droneSimOk = $null -ne $droneSimJar
    Check 'cloud-backend jar 构建后存在' $backendOk
    Check 'drone-sim jar 构建后存在' $droneSimOk
    if (-not ($backendOk -and $droneSimOk)) {
        Write-Host '`nRID E2E FAILED（jar 构建失败）' -ForegroundColor Red
        exit 1
    }
}

Info "cloud-backend jar: $($backendJar.FullName)"
Info "drone-sim jar: $($droneSimJar.FullName)"

# ---- 端口可用性 ----
$portBackendFree = Test-PortFree $BackendPort
$portDroneFree = Test-PortFree $DronePort
Check "cloud-backend 端口 $BackendPort 可用" $portBackendFree
Check "drone-sim 端口 $DronePort 可用" $portDroneFree

if (-not ($portBackendFree -and $portDroneFree)) {
    Info '端口被占用，尝试清理旧进程...'
    Cleanup-OldProcesses
    Start-Sleep 2
    $portBackendFree = Test-PortFree $BackendPort
    $portDroneFree = Test-PortFree $DronePort
    if (-not ($portBackendFree -and $portDroneFree)) {
        Info '端口仍被占用，请手动清理后重跑'
        Write-Host '`nRID E2E FAILED（端口占用）' -ForegroundColor Red
        exit 1
    }
}

Cleanup-OldProcesses

# ============================================================
# 1. 启动 cloud-backend（默认配置，RID 通过 UdpGateway 自动接收）
# ============================================================
Step '启动 cloud-backend（默认配置，RID 自动接收）'

$backendLog = Join-Path $env:TEMP "rid-e2e-backend.log"
if (Test-Path $backendLog) { Remove-Item $backendLog -Force }

$backendProc = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $backendJar.FullName,
    '--spring.profiles.active=dev',
    "--aerofleet.rid.enabled=true",
    "--aerofleet.rid.broadcast-interval=$RidBroadcastInterval",
    "--aerofleet.rid.timeout-periods=$RidTimeoutPeriods",
    "--aerofleet.security.rbac-enabled=false",
    '--aerofleet.security.dev-mode=true'
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog
Register-Cleanup { Stop-Process -Id $backendProc.Id -Force -ErrorAction SilentlyContinue }
Info "cloud-backend PID=$($backendProc.Id), log=$backendLog"

# 等待 cloud-backend 启动
$backendUp = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep 1
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$BackendPort/actuator/health" -TimeoutSec 2 -UseBasicParsing
        if ($r.StatusCode -eq 200) { $backendUp = $true; break }
    } catch { }
}
Check 'cloud-backend 启动成功' $backendUp
if (-not $backendUp) {
    Info 'cloud-backend 启动失败，查看日志:'
    if (Test-Path $backendLog) { Get-Content $backendLog -Tail 30 | ForEach-Object { Info $_ } }
    Write-Host '`nRID E2E FAILED（cloud-backend 启动失败）' -ForegroundColor Red
    Invoke-Cleanup
    exit 1
}

# ============================================================
# 2. 启动 drone-sim（--sysid 1 --rid=on --serial-no TEST-001）
# ============================================================
Step "启动 drone-sim（sysid=$DroneSysid, rid=on, serial-no=$DroneSerialNo）"

$droneLog = Join-Path $env:TEMP "rid-e2e-drone.log"
if (Test-Path $droneLog) { Remove-Item $droneLog -Force }

$droneProc = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $droneSimJar.FullName,
    '--port', $DronePort,
    '--sysid', $DroneSysid,
    '--rid=on',
    '--serial-no', $DroneSerialNo
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog
Register-Cleanup { Stop-Process -Id $droneProc.Id -Force -ErrorAction SilentlyContinue }
Info "drone-sim PID=$($droneProc.Id), log=$droneLog"
Start-Sleep 2

$droneAlive = -not $droneProc.HasExited
Check 'drone-sim 启动成功' $droneAlive
if (-not $droneAlive) {
    Info 'drone-sim 启动失败，查看日志:'
    if (Test-Path $droneLog) { Get-Content $droneLog -Tail 20 | ForEach-Object { Info $_ } }
    Write-Host '`nRID E2E FAILED（drone-sim 启动失败）' -ForegroundColor Red
    Invoke-Cleanup
    exit 1
}

# ============================================================
# 3. 等待 drone-sim 被发现（≤10s）
# ============================================================
Step '等待 drone-sim 被 cloud-backend 发现（≤10s）'

$discovered = $false
for ($i = 0; $i -lt 10; $i++) {
    Start-Sleep 1
    try {
        $dronesResp = Invoke-RestMethod -Uri "http://localhost:$BackendPort/api/v1/drones" -TimeoutSec 5
        $drone = $dronesResp | Where-Object { $_.sysid -eq $DroneSysid }
        if ($drone -and $drone.online) {
            $discovered = $true
            Info "drone-sim 在第 $($i+1)s 被发现"
            break
        }
    } catch { }
}
Check 'drone-sim 被 cloud-backend 发现（≤10s）' $discovered
if (-not $discovered) {
    Info 'drone-sim 未被发现，请检查网络和进程状态'
    Write-Host '`nRID E2E FAILED（drone-sim 未被发现）' -ForegroundColor Red
    Invoke-Cleanup
    exit 1
}

# 等待 RID 消息到达（drone-sim RID 广播间隔 1s，需等待至少 1-2 个周期）
Info '等待 RID 消息到达 cloud-backend...'
Start-Sleep 3

# ============================================================
# 4. 断言 RID 状态：GET /api/v1/rid/status/1 → BROADCASTING
# ============================================================
Step '断言 RID 状态为 BROADCASTING（GET /api/v1/rid/status/1）'

$status1Resp = $null
try {
    $status1Resp = Send-GetJson "$Base/status/$DroneSysid"
    Info "status/1 响应: $($status1Resp | ConvertTo-Json -Compress -Depth 5)"
    $ridStatusOk = $status1Resp.ridStatus -eq 'BROADCASTING'
    Check 'RID 状态为 BROADCASTING' $ridStatusOk
} catch {
    Info "status/1 请求异常: $_"
    Check 'RID 状态为 BROADCASTING' $false
}

# ============================================================
# 5. 断言 RID 状态列表：GET /api/v1/rid/status → 包含 sysid=1
# ============================================================
Step '断言 RID 状态列表包含 sysid=1（GET /api/v1/rid/status）'

try {
    $statusListResp = Send-GetJson "$Base/status"
    Info "status 列表响应: $($statusListResp | ConvertTo-Json -Compress -Depth 5)"
    $containsSysid = $false
    foreach ($item in $statusListResp) {
        if ($item.sysid -eq $DroneSysid) {
            $containsSysid = $true
            break
        }
    }
    Check 'RID 状态列表包含 sysid=1' $containsSysid
} catch {
    Info "status 列表请求异常: $_"
    Check 'RID 状态列表包含 sysid=1' $false
}

# ============================================================
# 6. 断言 BasicId 数据：status/1 响应中包含 serialNo=TEST-001
# ============================================================
Step '断言 BasicId 数据包含 serialNo=TEST-001'

if ($null -ne $status1Resp -and $null -ne $status1Resp.basicId) {
    $uasId = $status1Resp.basicId.uasId
    Info "basicId.uasId = '$uasId'"
    $serialNoOk = $uasId -eq $DroneSerialNo
    Check "BasicId.uasId = '$DroneSerialNo'" $serialNoOk
} else {
    Check 'BasicId 数据存在' $false
    Info 'status/1 响应中 basicId 为 null 或不存在'
}

# ============================================================
# 7. 断言 Location 数据：status/1 响应中包含 latitude/longitude
# ============================================================
Step '断言 Location 数据包含 latitude/longitude'

if ($null -ne $status1Resp -and $null -ne $status1Resp.location) {
    $hasLat = $null -ne $status1Resp.location.latitude
    $hasLon = $null -ne $status1Resp.location.longitude
    Info "location.latitude = $($status1Resp.location.latitude)"
    Info "location.longitude = $($status1Resp.location.longitude)"
    Check 'Location 数据包含 latitude' $hasLat
    Check 'Location 数据包含 longitude' $hasLon
} else {
    Check 'Location 数据存在' $false
    Info 'status/1 响应中 location 为 null 或不存在'
}

# ============================================================
# 8. 断言 System 数据：status/1 响应中包含 operatorLocationType
# ============================================================
Step '断言 System 数据包含 operatorLocationType'

if ($null -ne $status1Resp -and $null -ne $status1Resp.system) {
    $hasOperatorLocationType = $null -ne $status1Resp.system.operatorLocationType
    Info "system.operatorLocationType = $($status1Resp.system.operatorLocationType)"
    Check 'System 数据包含 operatorLocationType' $hasOperatorLocationType
} else {
    Check 'System 数据存在' $false
    Info 'status/1 响应中 system 为 null 或不存在'
}

# ============================================================
# 9. 停止 drone-sim → 等待超时 → 断言状态变为 BROADCASTING_ERROR
# ============================================================
Step '停止 drone-sim → 等待超时 → 断言状态变为 BROADCASTING_ERROR'

Info "停止 drone-sim (PID=$($droneProc.Id))..."
Stop-Process -Id $droneProc.Id -Force -ErrorAction SilentlyContinue
# 从清理列表中移除已停止的进程，避免重复 Stop-Process 报错
$script:CleanupActions = @($script:CleanupActions | Where-Object { $_ -notmatch "droneProc" })

# RID 超时 = timeoutPeriods × broadcastInterval = 3 × 1.0 = 3s
# 等待 6s 确保超时检测周期已执行（每秒检查一次）
$timeoutWaitSec = [int]($RidTimeoutPeriods * $RidBroadcastInterval + 3)
Info "等待 ${timeoutWaitSec}s 让 RID 状态超时（timeout=${RidTimeoutPeriods}×${RidBroadcastInterval}s）..."
Start-Sleep $timeoutWaitSec

$timeoutResp = $null
try {
    $timeoutResp = Send-GetJson "$Base/status/$DroneSysid"
    Info "超时后 status/1 响应: $($timeoutResp | ConvertTo-Json -Compress -Depth 5)"
    $errorStatusOk = $timeoutResp.ridStatus -eq 'BROADCASTING_ERROR'
    Check 'RID 状态变为 BROADCASTING_ERROR（超时后）' $errorStatusOk
} catch {
    Info "超时后 status/1 请求异常: $_"
    Check 'RID 状态变为 BROADCASTING_ERROR（超时后）' $false
}

# ============================================================
# 10. 清理：停止所有进程
# ============================================================
Step '清理：停止所有进程'
Invoke-Cleanup

# ---- 最终结果 ----
if ($Fail -eq 0) {
    Write-Host "`nALL RID E2E TESTS PASSED" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`nRID E2E TESTS FAILED" -ForegroundColor Red
    exit 1
}
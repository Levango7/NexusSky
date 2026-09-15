# 编队表演端到端演示闭环脚本（M1 T16 / FR-18 编队演示闭环）
#
# 依据：design.md §6.6 场景编排 + spec.md FR-18 + tasks.md T16
# 场景：3 机编队创建 → 起飞到位 → 队形变换（LINE→CIRCLE）→ 灯光同步 → 解散
#   - 启动 cloud-backend（UdpGateway discovery 指向 3 台 drone-sim 端口）
#   - 启动 3 台 drone-sim（sysid=1/2/3，不同端口，同一参考点附近）
#   - 可选启动 link-sim --relay（M0a 中继复用，FR-22，-IncludeRelay 开关）
#
# 断言目标（FR-18 编队演示闭环）：
#   FR-14 编队创建 → 200 + formationId + state=FORMING
#   FR-15 起飞命令下发 → 3 机起飞
#   FR-16 状态机转移 → 起飞到位 state=STABLE
#   FR-04 队形平滑变换 LINE→CIRCLE → state=TRANSITIONING → 到位 state=STABLE
#   FR-11 灯光同步 RAINBOW → 3 机灯光状态更新
#   FR-13 GET /lights 反映灯光状态（全程可观测）
#   FR-16 解散 → state=DISSOLVED
#
# 前置检查（异常 5.5.3-1）：drone-sim / cloud-backend jar 存在、端口可用、Java>=17；
#   缺失则 FAIL 并标注前置条件，不误报编队缺陷。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-formation.ps1 [-IncludeRelay]
#   -IncludeRelay：启用 M0a 中继复用（启动 link-sim --relay，FR-22），默认关闭（直接发现场景，design.md §6.6）
#
# 注意：本脚本启动 cloud-backend + 3 台 drone-sim，结束后自动清理。
#       若 cloud-backend 已在运行（8080 端口可达），则复用既有后端，不重复启动。

param([switch]$IncludeRelay)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

# ---- 端口配置（design.md §6.6 + 避免与 e2e-mesh.ps1 冲突）----
$BackendRestPort  = 8080                          # cloud-backend REST 端口
$BackendDronePort = 14550                         # 后端主 drone 端口（MAVLink GCS 标准）
$DroneSimPorts    = @(14551, 14552, 14553)        # 3 台 drone-sim 绑定端口（sysid=1/2/3）
$GcsPort          = 14541                         # 中继面向 GCS/后端侧（-IncludeRelay 时用）
$RelayPort        = 14543                         # 中继面向远端飞机侧（-IncludeRelay 时用）
$UplinkProfile    = 'lte-edge'                    # 中继上行链路画像
$DownlinkProfile  = 'wifi5'                       # 中继下行链路画像

# ---- 编队演示参数（任务描述 + design.md §6.6）----
$MemberSysids   = @(1, 2, 3)
$InitShape      = 'LINE'
$TargetShape    = 'CIRCLE'
$SpacingM       = 5
$HeadingDeg     = 0
$TakeoffAlt     = 10                             # 起飞高度（米）
$TransitionSteps = 4                             # 队形变换插值航点数
$RefLat         = 22.5431                         # 参考点纬度（深圳）
$RefLon         = 113.9578                         # 参考点经度
$RefAlt         = 0                               # 参考点高度

# ---- 超时配置 ----
$BackendStartTimeoutSec = 60                      # 后端启动超时
$DroneRegisterTimeoutSec = 40                     # 3 机注册等待超时
$StateStableTimeoutSec   = 60                     # 状态等待 STABLE 超时
$StateDissolvedTimeoutSec = 40                    # 状态等待 DISSOLVED 超时
$LightApplyTimeoutSec    = 20                     # 灯光生效等待超时
$PollIntervalSec         = 2                      # 轮询间隔

# ---- 输出辅助 ----
function Step($m) { Write-Host "`n== $m" -ForegroundColor Cyan }
function Check($desc, $ok) {
    if ($ok) { Write-Host "   PASS: $desc" -ForegroundColor Green }
    else { Write-Host "   FAIL: $desc" -ForegroundColor Red; $script:Fail = 1 }
}
function Info($m) { Write-Host "   $m" -ForegroundColor DarkGray }

# ---- Java 版本自检（JDK8 不支持 record 语法，class 61 vs 52）----
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

# ---- 端口可用性检查 ----
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

# ---- 清理注册 ----
function Register-Cleanup($action) { $script:CleanupActions += $action }
function Invoke-Cleanup() {
    foreach ($a in $script:CleanupActions) {
        try { & $a } catch { }
    }
    $script:CleanupActions = @()
}
trap { Invoke-Cleanup; break }

# ---- 清理旧实验进程（避免端口占用）----
function Cleanup-OldProcesses {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.CommandLine -match 'drone-sim.*formation-e2e' -or
            $_.CommandLine -match 'cloud-backend.*formation-e2e' -or
            $_.CommandLine -match 'link-sim.*--relay.*formation-e2e') {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep 1
}

# ---- REST 调用辅助 ----
function Invoke-PostJson($url, $bodyObj) {
    $json = $bodyObj | ConvertTo-Json -Depth 6
    return Invoke-RestMethod -Uri $url -Method Post -Body $json -ContentType 'application/json' -TimeoutSec 10
}

function Invoke-GetJson($url) {
    return Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 10
}

# ---- 等待后端就绪 ----
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

# ---- 等待 N 架飞机在线注册 ----
function Wait-DronesOnline($expectedSysids, $timeoutSec) {
    $url = "$Base/drones"
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $drones = Invoke-GetJson $url
            $allOnline = $true
            foreach ($sid in $expectedSysids) {
                $d = $drones | Where-Object { $_.sysid -eq $sid }
                if (-not $d -or -not $d.online) { $allOnline = $false; break }
            }
            if ($allOnline) { return $true }
        } catch { }
        Start-Sleep $PollIntervalSec
    }
    return $false
}

# ---- 等待编队状态达到期望值 ----
function Wait-FormationState($formationId, $expectedState, $timeoutSec, $intervalSec) {
    $url = "$FormationBase/$formationId"
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $lastState = $null
    while ((Get-Date) -lt $deadline) {
        try {
            $f = Invoke-GetJson $url
            $lastState = $f.state
            if ($f.state -eq $expectedState) { return $f }
        } catch { }
        Start-Sleep $intervalSec
    }
    Info "等待状态 $expectedState 超时，最后状态: $lastState"
    return $null
}

# ============================================================
# 前置检查（异常 5.5.3-1：jar 存在、端口可用、Java>=17）
# ============================================================
Step '前置检查（异常 5.5.3-1：jar 存在、端口可用、Java>=17）'

$backendJar   = Join-Path $Root 'cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'
$droneSimJar  = Join-Path $Root 'drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar'
$linkSimJar   = Join-Path $Root 'link-sim\target\aerofleet-link-sim-0.1.0-SNAPSHOT.jar'

$backendOk  = Test-Path $backendJar
$droneSimOk = Test-Path $droneSimJar
Check 'cloud-backend jar 存在' $backendOk
Check 'drone-sim jar 存在' $droneSimOk
if (-not ($backendOk -and $droneSimOk)) {
    Info "cloud-backend jar: $backendJar"
    Info "drone-sim jar: $droneSimJar"
    Info '前置条件缺失：请先用 JDK17 构建 (mvn -pl cloud-backend,drone-sim -am package -DskipTests)'
    Write-Host '`nFORMATION E2E FAILED（前置条件缺失，非编队缺陷）' -ForegroundColor Red
    exit 1
}

# Java 探测 + 版本自检
if ($env:AF_JAVA) { $java = $env:AF_JAVA }
elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" }
else { $java = "java.exe" }
Assert-Java17 $java
Info "Java: $java"

# 端口可用性检查（REST 端口 + drone-sim 端口）
$restPortFree = $true
try {
    $r = Invoke-WebRequest -Uri "http://localhost:$BackendRestPort/actuator/health" -TimeoutSec 2 -UseBasicParsing
    if ($r.StatusCode -eq 200) {
        Info "cloud-backend 已在运行（端口 $BackendRestPort 可达），将复用既有后端"
    }
} catch {
    # 后端未运行，检查端口是否可绑定（通过 TCP listener 试探）
    $restPortFree = $false
    try {
        $tcp = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $BackendRestPort)
        $tcp.Start(); $tcp.Stop()
        $restPortFree = $true
    } catch { }
}
if (-not $restPortFree) {
    Check "REST 端口 $BackendRestPort 可用" $false
    Info '前置条件缺失：REST 端口被占用且非可达后端，请清理旧进程后重跑'
    Write-Host '`nFORMATION E2E FAILED（前置条件缺失：端口占用，非编队缺陷）' -ForegroundColor Red
    exit 1
}

foreach ($p in $DroneSimPorts) {
    $free = Test-PortFree $p
    Check "drone-sim 端口 $p 可用" $free
    if (-not $free) {
        Info "前置条件缺失：端口 $p 被占用，请清理旧进程后重跑"
        Write-Host "`nFORMATION E2E FAILED（前置条件缺失：端口 $p 占用，非编队缺陷）" -ForegroundColor Red
        exit 1
    }
}

if ($IncludeRelay) {
    $lsJarOk = Test-Path $linkSimJar
    Check 'link-sim jar 存在（-IncludeRelay 中继复用）' $lsJarOk
    if (-not $lsJarOk) {
        Info "link-sim jar: $linkSimJar"
        Info '前置条件缺失：-IncludeRelay 需要 link-sim jar，请构建 (mvn -pl link-sim -am package -DskipTests)'
        Write-Host '`nFORMATION E2E FAILED（前置条件缺失：link-sim jar 不存在）' -ForegroundColor Red
        exit 1
    }
    $gcsFree = Test-PortFree $GcsPort
    $relayFree = Test-PortFree $RelayPort
    Check "gcsPort $GcsPort 可用" $gcsFree
    Check "relayPort $RelayPort 可用" $relayFree
    if (-not ($gcsFree -and $relayFree)) {
        Info '前置条件缺失：中继端口被占用，请清理旧进程后重跑'
        Write-Host '`nFORMATION E2E FAILED（前置条件缺失：中继端口占用，非编队缺陷）' -ForegroundColor Red
        exit 1
    }
}

Cleanup-OldProcesses

# ---- REST 基址 ----
$Base = "http://localhost:$BackendRestPort/api/v1"
$FormationBase = "$Base/formation"

# ============================================================
# 1. 启动阶段：cloud-backend + 3 台 drone-sim（+ 可选 link-sim --relay）
# ============================================================
Step '1. 启动阶段：cloud-backend + 3 台 drone-sim'

# 检查后端是否已在运行
$backendRunning = $false
try {
    $r = Invoke-WebRequest -Uri "http://localhost:$BackendRestPort/actuator/health" -TimeoutSec 2 -UseBasicParsing
    $backendRunning = $r.StatusCode -eq 200
} catch { }

$backendStartedByScript = $false
if (-not $backendRunning) {
    Info '启动 cloud-backend（discovery 指向 3 台 drone-sim 端口）...'
    $backendLog = Join-Path $env:TEMP "formation-backend.log"
    if (Test-Path $backendLog) { Remove-Item $backendLog -Force }

    # 后端配置：drone-extra-ports 含 3 台 drone-sim 端口（直接发现场景，design.md §6.6）
    # -IncludeRelay 时：drone-port 指向中继 gcsPort，drone-extra-ports 含中继 relayPort
    if ($IncludeRelay) {
        $backendArgs = @(
            '-jar', $backendJar,
            "--aerofleet.drone-port=$GcsPort",
            "--aerofleet.drone-extra-ports=$RelayPort"
        )
    } else {
        $extraPorts = $DroneSimPorts -join ','
        $backendArgs = @(
            '-jar', $backendJar,
            "--aerofleet.drone-port=$BackendDronePort",
            "--aerofleet.drone-extra-ports=$extraPorts"
        )
    }
    # 标记命令行用于旧进程清理识别
    $backendArgs += @('-Dformation-e2e=true')

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
        Write-Host '`nFORMATION E2E FAILED（后端启动失败）' -ForegroundColor Red
        Invoke-Cleanup
        exit 1
    }
} else {
    Info 'cloud-backend 已在运行，复用既有后端'
}

# 可选：启动 link-sim --relay（M0a 中继复用，FR-22）
if ($IncludeRelay) {
    Info '启动 link-sim --relay（M0a 中继复用，FR-22）...'
    $relayLog = Join-Path $env:TEMP "formation-relay.log"
    if (Test-Path $relayLog) { Remove-Item $relayLog -Force }
    $relay = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $linkSimJar,
        '--relay',
        '--gcs-port', $GcsPort,
        '--relay-port', $RelayPort,
        '--uplink-profile', $UplinkProfile,
        '--downlink-profile', $DownlinkProfile,
        '-Dformation-e2e=true'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $relayLog
    Register-Cleanup { Stop-Process -Id $relay.Id -Force -ErrorAction SilentlyContinue }
    Info "relay PID=$($relay.Id), gcsPort=$GcsPort, relayPort=$RelayPort"
    Start-Sleep 2
    $relayAlive = -not $relay.HasExited
    Check '中继进程启动成功' $relayAlive
    if (-not $relayAlive) {
        Info '中继启动失败，查看日志:'
        if (Test-Path $relayLog) { Get-Content $relayLog -Tail 20 | ForEach-Object { Info $_ } }
    }
}

# 启动 3 台 drone-sim（sysid=1/2/3，不同端口，同一参考点附近）
$droneProcesses = @()
for ($i = 0; $i -lt $MemberSysids.Count; $i++) {
    $sid = $MemberSysids[$i]
    $port = $DroneSimPorts[$i]
    $name = "AF-FORM-0$sid"
    $droneLog = Join-Path $env:TEMP "formation-drone-$sid.log"
    if (Test-Path $droneLog) { Remove-Item $droneLog -Force }

    $droneArgs = @(
        '-jar', $droneSimJar,
        '--port', $port,
        '--sysid', $sid,
        '--name', $name,
        '-Dformation-e2e=true'
    )
    # -IncludeRelay 时，drone-sim 需向中继发包（当前 drone-sim 为被动模式，记录为环境限制）
    # 直接发现场景下 drone-sim 绑定各自端口，后端通过 drone-extra-ports 发现

    $d = Start-Process -FilePath $java -ArgumentList $droneArgs `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog
    Register-Cleanup { Stop-Process -Id $d.Id -Force -ErrorAction SilentlyContinue }
    $droneProcesses += $d
    Info "drone-sim[$i] PID=$($d.Id), sysid=$sid, port=$port, name=$name"
}

# 等待 drone-sim 进程稳定
Start-Sleep 3
$allDroneAlive = $true
foreach ($d in $droneProcesses) {
    if ($d.HasExited) { $allDroneAlive = $false; Info "drone-sim PID=$($d.Id) 已退出" }
}
Check '3 台 drone-sim 进程启动成功' $allDroneAlive
if (-not $allDroneAlive) {
    Write-Host '`nFORMATION E2E FAILED（drone-sim 启动失败）' -ForegroundColor Red
    Invoke-Cleanup
    exit 1
}

# ============================================================
# 2. 注册等待：3 架飞机在线注册到 DeviceRegistry
# ============================================================
Step '2. 注册等待：3 架飞机心跳到达 → DeviceRegistry 在线'

Info "等待 $($MemberSysids.Count) 架飞机在线注册（超时 ${DroneRegisterTimeoutSec}s）..."
$registered = Wait-DronesOnline $MemberSysids $DroneRegisterTimeoutSec
Check 'FR-14: 3 架飞机在线注册到 DeviceRegistry' $registered
if (-not $registered) {
    Info '注册超时，查看当前 /drones 状态:'
    try {
        $drones = Invoke-GetJson "$Base/drones"
        foreach ($d in $drones) { Info "  sysid=$($d.sysid), online=$($d.online)" }
    } catch { Info "  /drones 不可达: $_" }
    if ($IncludeRelay) {
        Info '环境限制：drone-sim 为被动模式，-IncludeRelay 时可能无法经中继被发现（需 drone-sim 支持 --gcs-host 主动发包）'
    }
    Write-Host '`nFORMATION E2E FAILED（飞机注册失败）' -ForegroundColor Red
    Invoke-Cleanup
    exit 1
}

# 打印注册详情
try {
    $drones = Invoke-GetJson "$Base/drones"
    foreach ($d in $drones) {
        if ($MemberSysids -contains $d.sysid) {
            Info "  sysid=$($d.sysid), online=$($d.online), lat=$($d.lat), lon=$($d.lon), alt=$($d.relativeAlt), battery=$($d.battery)"
        }
    }
} catch { }

# ============================================================
# 3. 创建编队：POST /api/v1/formation/create
# ============================================================
Step '3. 创建编队：POST /api/v1/formation/create {members:[1,2,3], shape:LINE, spacing:5, heading:0}'

# 创建请求 body（字段名 members 与后端 FormationCreateRequest DTO 一致，design.md §6.6 / tasks.md T4）
$createBody = @{
    members  = $MemberSysids
    shape    = $InitShape
    spacing  = $SpacingM
    heading  = $HeadingDeg
    refLat   = $RefLat
    refLon   = $RefLon
    refAlt   = $RefAlt
}

$formation = $null
try {
    $formation = Invoke-PostJson "$FormationBase/create" $createBody
} catch {
    Info "编队创建请求失败: $_"
}
$createOk = $null -ne $formation -and $null -ne $formation.formationId
Check 'FR-14: 编队创建返回 formationId' $createOk

if (-not $createOk) {
    Write-Host '`nFORMATION E2E FAILED（编队创建失败）' -ForegroundColor Red
    Invoke-Cleanup
    exit 1
}

$formationId = $formation.formationId
Info "formationId = $formationId"
Info "state = $($formation.state)"
if ($formation.assignments) {
    Info "assignments 数量 = $($formation.assignments.Count)"
    foreach ($a in $formation.assignments) {
        Info "  sysid=$($a.sysid) → slotIndex=$($a.slotIndex), target=($($a.lat), $($a.lon), $($a.alt))"
    }
}

# 断言：3 个分配 + state=FORMING
$assignCountOk = $formation.assignments -and $formation.assignments.Count -eq $MemberSysids.Count
Check "FR-14: 分配数量 = $($MemberSysids.Count)" $assignCountOk
$stateFormingOk = $formation.state -eq 'FORMING'
Check 'FR-14: 初始 state = FORMING' $stateFormingOk

# ============================================================
# 4. 起飞命令：POST /api/v1/formation/{id}/command {type:TAKEOFF, alt:10}
# ============================================================
Step "4. 起飞命令：POST /api/v1/formation/$formationId/command {type:TAKEOFF, alt:$TakeoffAlt}"

$takeoffBody = @{
    type = 'TAKEOFF'
    alt  = $TakeoffAlt
}
$takeoffOk = $false
try {
    $resp = Invoke-PostJson "$FormationBase/$formationId/command" $takeoffBody
    $takeoffOk = $true
    Info "起飞命令已下发，响应: $($resp | ConvertTo-Json -Compress -Depth 4)"
} catch {
    Info "起飞命令失败: $_"
}
Check 'FR-15: 起飞命令下发成功' $takeoffOk

# ============================================================
# 5. 等待稳定：轮询 GET /api/v1/formation/{id} 直到 state=STABLE
# ============================================================
Step "5. 等待稳定：轮询 GET /api/v1/formation/$formationId 直到 state=STABLE"

Info "等待起飞到位（Keeper 检测到位 → state=STABLE，超时 ${StateStableTimeoutSec}s）..."
$stableFormation = Wait-FormationState $formationId 'STABLE' $StateStableTimeoutSec $PollIntervalSec
$takeoffStableOk = $null -ne $stableFormation
Check 'FR-16: 起飞到位后 state = STABLE' $takeoffStableOk
if ($takeoffStableOk) {
    Info "起飞到位，state=$($stableFormation.state), version=$($stableFormation.version)"
}

# ============================================================
# 6. 队形变换：POST /api/v1/formation/{id}/transition {newShape:CIRCLE, steps:4}
# ============================================================
Step "6. 队形变换：POST /api/v1/formation/$formationId/transition {newShape:$TargetShape, steps:$TransitionSteps}"

$transitionBody = @{
    newShape = $TargetShape
    steps    = $TransitionSteps
}
$transitionOk = $false
try {
    $resp = Invoke-PostJson "$FormationBase/$formationId/transition" $transitionBody
    $transitionOk = $true
    Info "队形变换命令已下发，响应: $($resp | ConvertTo-Json -Compress -Depth 4)"
} catch {
    Info "队形变换失败: $_"
}
Check 'FR-04: 队形变换命令下发成功' $transitionOk

# 变换后应进入 TRANSITIONING
Start-Sleep $PollIntervalSec
try {
    $f = Invoke-GetJson "$FormationBase/$formationId"
    Info "变换后即时状态: state=$($f.state)"
    $transitioningOk = $f.state -eq 'TRANSITIONING' -or $f.state -eq 'STABLE'
    Check 'FR-16: 变换后 state = TRANSITIONING（或已快速到位 STABLE）' $transitioningOk
} catch {
    Info "查询变换后状态失败: $_"
    Check 'FR-16: 变换后 state = TRANSITIONING' $false
}

# ============================================================
# 7. 等待变换完成：轮询直到 state=STABLE
# ============================================================
Step "7. 等待变换完成：轮询 GET /api/v1/formation/$formationId 直到 state=STABLE"

Info "等待队形变换到位（3 机沿插值路径移动 → state=STABLE，超时 ${StateStableTimeoutSec}s）..."
$transitionStable = Wait-FormationState $formationId 'STABLE' $StateStableTimeoutSec $PollIntervalSec
$transitionStableOk = $null -ne $transitionStable
Check 'FR-04: 队形变换到位后 state = STABLE' $transitionStableOk
if ($transitionStableOk) {
    Info "变换到位，state=$($transitionStable.state), version=$($transitionStable.version)"
    if ($transitionStable.targetPositions) {
        foreach ($tp in $transitionStable.targetPositions) {
            Info "  sysid=$($tp.sysid) → ($($tp.lat), $($tp.lon), $($tp.alt))"
        }
    }
}

# ============================================================
# 8. 灯光控制：POST /api/v1/formation/{id}/lights {pattern:RAINBOW, sync:true}
# ============================================================
Step "8. 灯光控制：POST /api/v1/formation/$formationId/lights {pattern:RAINBOW, sync:true}"

# 灯光命令 body（字段与后端 LedControlCommand DTO 一致，tasks.md T4）
# RAINBOW 模式下 color 会被色相旋转覆盖，提供合理默认值
$lightsBody = @{
    pattern    = 'RAINBOW'
    on         = $true
    colorR     = 255
    colorG     = 0
    colorB     = 0
    brightness = 80
    freq       = 2
    sync       = $true
}
$lightsOk = $false
try {
    $resp = Invoke-PostJson "$FormationBase/$formationId/lights" $lightsBody
    $lightsOk = $true
    Info "灯光命令已下发，响应: $($resp | ConvertTo-Json -Compress -Depth 4)"
} catch {
    Info "灯光命令失败: $_"
}
Check 'FR-11: 灯光同步命令下发成功' $lightsOk

# ============================================================
# 9. 等待灯光：GET /api/v1/formation/{id}/lights 验证所有成员 active=true
# ============================================================
Step "9. 等待灯光：GET /api/v1/formation/$formationId/lights 验证所有成员 active=true"

Info "等待灯光状态生效（超时 ${LightApplyTimeoutSec}s）..."
$lightsVerified = $false
$deadline = (Get-Date).AddSeconds($LightApplyTimeoutSec)
$lastLightsResp = $null
while ((Get-Date) -lt $deadline) {
    try {
        $lastLightsResp = Invoke-GetJson "$FormationBase/$formationId/lights"
        # 检查所有成员灯光 active=true
        $allActive = $true
        if ($lastLightsResp.members) {
            foreach ($m in $lastLightsResp.members) {
                if (-not $m.active) { $allActive = $false; break }
            }
        } elseif ($lastLightsResp.active) {
            # 单一灯光状态对象
            $allActive = $lastLightsResp.active
        } else {
            $allActive = $false
        }
        if ($allActive) { $lightsVerified = $true; break }
    } catch { }
    Start-Sleep $PollIntervalSec
}

Check 'FR-11: 灯光同步生效（所有成员 active=true）' $lightsVerified
Check 'FR-13: GET /lights 反映灯光状态（全程可观测）' $lightsVerified
if ($lightsVerified -and $lastLightsResp) {
    Info "灯光状态: $($lastLightsResp | ConvertTo-Json -Compress -Depth 4)"
} elseif ($lastLightsResp) {
    Info "最后灯光状态: $($lastLightsResp | ConvertTo-Json -Compress -Depth 4)"
}

# ============================================================
# 10. 解散命令：POST /api/v1/formation/{id}/dissolve
# ============================================================
Step "10. 解散命令：POST /api/v1/formation/$formationId/dissolve"

# design.md §6.6：解散前在飞成员 RTL（dissolve 内部触发 RTL，tasks.md T6）
$dissolveOk = $false
try {
    $resp = Invoke-PostJson "$FormationBase/$formationId/dissolve" @{}
    $dissolveOk = $true
    Info "解散命令已下发，响应: $($resp | ConvertTo-Json -Compress -Depth 4)"
} catch {
    Info "解散命令失败: $_"
}
Check 'FR-16: 解散命令下发成功' $dissolveOk

# ============================================================
# 11. 验证解散：轮询直到 state=DISSOLVED
# ============================================================
Step "11. 验证解散：轮询 GET /api/v1/formation/$formationId 直到 state=DISSOLVED"

Info "等待编队解散完成（在飞成员 RTL → state=DISSOLVED，超时 ${StateDissolvedTimeoutSec}s）..."
$dissolvedFormation = Wait-FormationState $formationId 'DISSOLVED' $StateDissolvedTimeoutSec $PollIntervalSec
$dissolvedOk = $null -ne $dissolvedFormation
Check 'FR-16: 解散后 state = DISSOLVED' $dissolvedOk
if ($dissolvedOk) {
    Info "解散完成，state=$($dissolvedFormation.state), version=$($dissolvedFormation.version)"
}

# ============================================================
# 12. 清理阶段：停止所有进程
# ============================================================
Step '12. 清理阶段：停止脚本启动的进程'

Invoke-Cleanup
Info '已停止 cloud-backend / drone-sim / link-sim（仅脚本启动的进程）'

# ============================================================
# 结果汇总
# ============================================================
Write-Host ''
if ($Fail -eq 0) {
    Write-Host 'FORMATION E2E PASSED（FR-18 编队演示闭环验证通过）' -ForegroundColor Green
    Write-Host '  覆盖：FR-14 编队创建 / FR-15 命令下发 / FR-16 状态机 / FR-04 队形变换 / FR-11 灯光同步 / FR-13 可观测 / FR-18 演示闭环' -ForegroundColor DarkGray
    exit 0
} else {
    Write-Host 'FORMATION E2E FAILED（部分断言未通过，见上方 FAIL 行）' -ForegroundColor Red
    exit 1
}
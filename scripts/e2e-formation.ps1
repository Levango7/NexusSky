# 编队表演端到端演示闭环脚本（M1 T16 / FR-18 编队演示闭环）
#
# 依据：design.md §6.6 场景编排 + spec.md FR-18 + tasks.md T16
# 场景：3 机编队创建 → 起飞到位 → 队形变换（LINE→CIRCLE）→ 灯光同步 → 解散
#   - 启动 cloud-backend（UdpGateway discovery 指向 3 台 drone-sim 端口）
#   - 启动 3 台 drone-sim（sysid=1/2/3，不同端口，home 显式设为编队参考点）
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
# 本机 8080 可能被常驻服务（Docker 容器等）占用：AF_BACKEND_REST_PORT 覆盖
$BackendRestPort  = if ($env:AF_BACKEND_REST_PORT) { [int]$env:AF_BACKEND_REST_PORT } else { 8080 }   # cloud-backend REST 端口
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
# 参考点高度必须等于起飞高度：slot 目标 alt 就取这个值，Keeper 到位判定用水平
# 距离，但飞往 slot 的 mission 航点 alt 若低于巡航高度，飞机会先去俯冲，队形
# 全程不在巡航面上。drone-sim 的 home 也必须显式设成参考点——它的默认 home
# （22.5907, 113.9345）离参考点 5.3km，飞机一起飞就"不在位"，STABLE 永远等不到。
$RefAlt         = $TakeoffAlt                     # 参考点高度 = 巡航高度
$SlotHoldSec    = 90                             # slot 悬停秒数（> 后续步骤耗时）

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
# 教训（2026-10-13，本脚本曾把 3 台 drone-sim 全漏杀）：清理动作里**不能引用脚本级
# 变量**。PowerShell 的 scriptblock 是调用时动态查变量，不是定义时捕获：本脚本 $d 先绑
# drone-sim 进程，step 2 又绑 /drones 返回的视图对象，清理时 `Stop-Process -Id $d.Id`
# 拿到的是 $null——三条动作一个都杀不掉，后端死了、3 台模拟器长驻，还会污染后续
# e2e（占着 14551-14553，别的脚本收到的"PASS"其实是它们的广播）。
# 所以进程统一进清单，清理时按清单遍历，动作 scriptblock 不引用任何外部变量。
$script:E2eProcesses = New-Object System.Collections.ArrayList
function Register-E2eProcess($proc) {
    if ($null -ne $proc) { $null = $script:E2eProcesses.Add($proc) }
}
function Register-Cleanup($action) { $script:CleanupActions += $action }
function Invoke-Cleanup {
    foreach ($p in $script:E2eProcesses) {
        try {
            if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
        } catch { }
    }
    $script:E2eProcesses.Clear()
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
    # 业务判据而非 actuator/health：dev profile 连本地 Redis，6379 不通/带口令时
    # health 恒 503（RedisHealthContributor），但服务本身早已可用——据 health 判失败
    # 会把 28 秒就起来的后端误杀。/api/v1/drones 只读且恒 200，同样证明 web 层就绪。
    $readyUrl = "http://localhost:$BackendRestPort/api/v1/drones"
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep 1
        try {
            $r = Invoke-WebRequest -Uri $readyUrl -TimeoutSec 2 -UseBasicParsing
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
$droneSimJar  = Join-Path $Root 'drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar'
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
    # 业务判据而非 actuator/health：本机 Redis 不通会把 health 打成 503，
    # 而服务其实可用。用只读的 /api/v1/drones 探活。
    $r = Invoke-WebRequest -Uri "http://localhost:$BackendRestPort/api/v1/drones" -TimeoutSec 2 -UseBasicParsing
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
# 业务就绪判据而非 actuator/health：dev profile 连本地 Redis，本机 6379 若不通/带口令，
# RedisHealthContributor 会把 health 打成 503——与"服务是否可用"无关，据此判失败是误杀
# （e2e-regulator 同款教训：backend 28s 就起来了，脚本却因 503 把它杀掉）。
$backendRunning = $false
try {
    $r = Invoke-WebRequest -Uri "http://localhost:$BackendRestPort/api/v1/drones" -TimeoutSec 2 -UseBasicParsing
    if ($r.StatusCode -eq 200) { $backendRunning = $true }
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
            '--spring.profiles.active=dev',
            "--server.port=$BackendRestPort",
            "--aerofleet.drone-port=$GcsPort",
            "--aerofleet.drone-extra-ports=$RelayPort"
        )
    } else {
        $extraPorts = $DroneSimPorts -join ','
        $backendArgs = @(
            '-jar', $backendJar,
            '--spring.profiles.active=dev',
            "--server.port=$BackendRestPort",
            "--aerofleet.drone-port=$BackendDronePort",
            "--aerofleet.drone-extra-ports=$extraPorts"
        )
    }
    # 标记命令行用于旧进程清理识别
    $backendArgs += @('-Dformation-e2e=true')
    # 每次跑都自带内存库，别用 dev profile 的 ./data/aerofleet 文件库：上一轮跑完的编队
    # 行会被 restoreFormations 复活，而它的成员和本轮完全相同（sysid=1/2/3）——旧编队的
    # Keeper 会把飞机往**旧队形**的 slot 上拉，新编队的 FORMING→STABLE 永远等不到
    # （2026-10-13 实测：FR-16 超时失败，而变换指令一发出来立刻就 STABLE，因为飞机
    # 一直被往旧 CIRCLE 目标位拽）。同一份 JVM 生命周期内没有重启，mem 库足够。
    $backendArgs += @('--spring.datasource.url=jdbc:h2:mem:formation-e2e;DB_CLOSE_DELAY=-1')

    $backend = Start-Process -FilePath $java -ArgumentList $backendArgs `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog
    Register-E2eProcess $backend
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
    Register-E2eProcess $relay
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
        '--lat', "$RefLat",
        '--lon', "$RefLon",
        '-Dformation-e2e=true'
    )
    # -IncludeRelay 时，drone-sim 需向中继发包（当前 drone-sim 为被动模式，记录为环境限制）
    # 直接发现场景下 drone-sim 绑定各自端口，后端通过 drone-extra-ports 发现

    $d = Start-Process -FilePath $java -ArgumentList $droneArgs `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog
    Register-E2eProcess $d
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
# 3. 创建编队：POST /api/v1/formation
# ============================================================
Step '3. 创建编队：POST /api/v1/formation {members:[1,2,3], shape:LINE, spacing:5, heading:0}'

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
    # 创建编队走 POST /api/v1/formation（控制器 @PostMapping 无 /create 子路径）
    $formation = Invoke-PostJson "$FormationBase" $createBody
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
# assignments 是 Map<sysid, assignment>（对象，键即成员 sysid），不是数组。
# 按键统计：ConvertFrom-Json 对单键对象不暴露 .Count，逐键取才稳。
$assignmentKeys = @()
if ($formation.assignments) {
    $assignmentKeys = @($formation.assignments.PSObject.Properties.Name)
    Info "assignments 数量 = $($assignmentKeys.Count)"
    foreach ($k in $assignmentKeys) {
        $a = $formation.assignments.$k
        Info "  sysid=$k → slotIndex=$($a.slotIndex), target=($($a.lat), $($a.lon), $($a.alt))"
    }
}

# 断言：3 个分配 + state=FORMING
$assignCountOk = $assignmentKeys.Count -eq $MemberSysids.Count
Check "FR-14: 分配数量 = $($MemberSysids.Count)" $assignCountOk
$stateFormingOk = $formation.state -eq 'FORMING'
Check 'FR-14: 初始 state = FORMING' $stateFormingOk

# ============================================================
# 3b. 逐机上传"飞往本机 slot"的 mission（Keeper 到位判定的前提）
# ============================================================
# 为什么必须有：Keeper 的 FORMING/TRANSITIONING → STABLE 判定要求每个在线成员
# 已在自己 slot 目标位的 1m 容差内（FormationKeeper.KEEP_TOLERANCE_M）。
# 创建编队只生成 assignment，飞机不会自己挪过去——TAKEOFF 只是原地升降。
# 不把 slot 坐标作为航线下发，state 会永远卡在 FORMING，FR-16 必然是 FAIL。
Step '3b. 逐机上传飞往 slot 的 mission（到位判定的前提）'
$slotMissionOk = 0
foreach ($k in $assignmentKeys) {
    $a = $formation.assignments.$k
    try {
        # 传对象、不要传 ConvertTo-Json 后的字符串： Invoke-PostJson 内部会再做一次
        # 序列化，二次转义后 items 变成 JSON 字符串，服务端报
        # "body must contain a non-empty 'items' array"（400）。
        # waypoint 的 holdTime 必须够长：hold 结束后 mission 会推进到 rtl 回家，
        # 飞机离开 slot 就让 Keeper 判"不在位"。hold 期间发文变换命令
        # （DO_REPOSITION）会接管本机，不受 hold 限制。
        $body = @{
            items = @(
                @{ cmd = 'takeoff'; lat = $RefLat; lon = $RefLon; alt = $TakeoffAlt; holdTime = 0 }
                @{ cmd = 'waypoint'; lat = $a.lat; lon = $a.lon; alt = $a.alt; holdTime = $SlotHoldSec }
                @{ cmd = 'rtl';      lat = $RefLat; lon = $RefLon; alt = 0;      holdTime = 0 }
            )
        }
        $null = Invoke-PostJson "$Base/drones/$k/mission" $body
        $slotMissionOk++
    } catch {
        Info "slot mission 上传失败 sysid=$k : $_"
    }
}
Info "slot mission 上传成功 $slotMissionOk/$($assignmentKeys.Count) 架"
Check 'FR-14 前置: 逐机 slot mission 上传' ($slotMissionOk -eq $assignmentKeys.Count)

# ============================================================
# 4. 逐机 ARM → 编队起飞 → 逐机 start_mission
# ============================================================
# 顺序不能颠倒：drone-sim 未 armed 会拒 TAKEOFF / start_mission（MAV_RESULT=2），
# 而 start_mission 若在 TAKEOFF 之前，任务的第一个 item（NAV_TAKEOFF）会把
# physics 目标重置成"原地爬升"，之后才飞去 slot——先把编队 TAKEOFF 发出去，
# start_mission 的 takeoff item 才是"已到位、秒过"，飞机直接奔 slot。
Step "4. 逐机 ARM + 编队起飞 + start_mission {type:TAKEOFF, alt:$TakeoffAlt}"

$armOk = 0
foreach ($s in $MemberSysids) {
    try {
        $null = Invoke-PostJson "$Base/drones/$s/commands" @{ type = 'arm' }
        $armOk++
    } catch {
        Info "ARM 失败 sysid=$s : $_"
    }
}
Info "ARM 成功 $armOk/$($MemberSysids.Count) 架"
Check 'FR-15 前置: 逐机 ARM' ($armOk -eq $MemberSysids.Count)

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

# start_mission 才会真正执行：只上传不启动，飞机仍停在原位。
# 必须晚于 ARM（否则 rejected: not armed）和 TAKEOFF（否则 mission 首 item
# 把爬升目标重置成原地，飞机永远到不了 slot）。
$missionStartOk = 0
foreach ($k in $assignmentKeys) {
    try {
        $null = Invoke-PostJson "$Base/drones/$k/commands" @{ type = 'start_mission' }
        $missionStartOk++
    } catch {
        Info "start_mission 失败 sysid=$k : $_"
    }
}
Info "start_mission 成功 $missionStartOk/$($assignmentKeys.Count) 架"
Check 'FR-14 前置: 逐机 start_mission' ($missionStartOk -eq $assignmentKeys.Count)

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
# pattern 是 int（mavlink-core LightPattern 的 ordinal），不是枚举名：传 "RAINBOW"
# 会被 Jackson 判 400（Cannot deserialize value of type `int` from String）。
# 序位查 mavlink-core/enums/LightPattern.java：STEADY=0 BLINK=1 BREATHE=2 CHASE=3
# RAINBOW=4。改枚举顺序时这里必须跟着改，否则灯效会静默变成另一种。
$LightPatternRainbow = 4
$lightsBody = @{
    on         = $true
    colorR     = 255
    colorG     = 0
    colorB     = 0
    pattern    = $LightPatternRainbow
    brightness = 80
    freq       = 2
    sync       = $true
}
$lightsOk = $false
$lightsAllAckOk = $false
try {
    $resp = Invoke-PostJson "$FormationBase/$formationId/lights" $lightsBody
    $lightsOk = $true
    Info "灯光命令已下发，响应: $($resp | ConvertTo-Json -Compress -Depth 4)"
    # FR-11 逐机生效判据：results 里每个成员 result=0（offline 成员在该步会 fail）
    $acked = 0
    if ($resp.results) {
        foreach ($m in $resp.results.PSObject.Properties) {
            if ($m.Value.result -eq 0) { $acked++ }
            else { Info "灯光未生效 sysid=$($m.Name): $($m.Value.error)" }
        }
    }
    $lightsAllAckOk = ($acked -eq $MemberSysids.Count)
} catch {
    Info "灯光命令失败: $_"
}
Check 'FR-11: 灯光同步命令下发成功' $lightsOk
Check 'FR-11: 灯光逐机生效（所有成员 result=0）' $lightsAllAckOk

# ============================================================
# 9. 灯光状态回读：GET /api/v1/formation/{id}/lights 验证命令被反映（FR-13）
# ============================================================
# api-reference.md 的契约：GET /lights 返回 {formationId, on, pattern, brightness,
# freq, sync, colorR/G/B}（编队级最后一次灯光命令视图），没有 per-member active
# 字段。逐机"是否收下命令"由 POST 的 results（每机 AckResult）表达，见第 8 步。
Step "9. 灯光状态回读：GET /api/v1/formation/$formationId/lights（FR-13 全程可观测）"

Info "等待灯光状态可观测（超时 ${LightApplyTimeoutSec}s）..."
$lightsVerified = $false
$lastLightsResp = $null
$deadline = (Get-Date).AddSeconds($LightApplyTimeoutSec)
while ((Get-Date) -lt $deadline) {
    try {
        $lastLightsResp = Invoke-GetJson "$FormationBase/$formationId/lights"
        # GET 视图反映的是最后一次命令：开灯 + RAINBOW 序位 + 同步标志
        # （PowerShell 续行必须把运算符放行尾，放行首会解析失败）
        if ($lastLightsResp.on -eq $true -and
            $lastLightsResp.pattern -eq $LightPatternRainbow -and
            $lastLightsResp.sync -eq $true) {
            $lightsVerified = $true
            break
        }
    } catch { }
    Start-Sleep $PollIntervalSec
}

Check 'FR-13: GET /lights 反映灯光状态（on/pattern/sync）' $lightsVerified
if ($lastLightsResp) {
    Info "灯光状态: $($lastLightsResp | ConvertTo-Json -Compress -Depth 4)"
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
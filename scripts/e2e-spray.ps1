# 喷洒物流端到端演示（PowerShell 自启版）
#
# 断言链参照 e2e-spray.sh，API 端点口径对齐
# io.aerofleet.cloud.mission.spray.SprayController / mission.delivery.DeliveryController。
# 自启动 cloud-backend + drone-sim（参照 e2e-formation.ps1 的自启动模式）。
#
# 流程（对齐 e2e-spray.sh 的断言链）：
#   1. 确认无人机在线
#   2. 创建喷洒任务（POST /api/v1/spray，waypoints >= 2）
#   3. 查询喷洒任务（GET /api/v1/spray/{id}）
#   4. 控制喷洒任务 START / STOP（POST /api/v1/spray/{id}/control）
#   5. 创建配送任务（POST /api/v1/delivery，2 站点各带 1 件负载）
#   6. 查询配送序列（GET /api/v1/delivery/{id}，sites 为对象数组）
#   7. 查询负载清单（GET /api/v1/delivery/{id}/payload）
#   8. 按需触发机载负载上报（POST /api/v1/delivery/{id}/payload/query，MAV_CMD 30085）
#   9. 夹爪复位 + 配送启动（POST /api/v1/delivery/{id}/control，夹爪走 MAV_CMD 30084）
#
# 注意：夹爪在本仓没有独立 REST 端点（早期版本的 /spray/gripper 是虚构路径，
#   后端从未实现过）；夹爪的机载能力由配送控制（MAV_CMD 30084 GRIPPER_CONTROL）覆盖。
#   drone-sim 必须带 --actuators 启动：SprayPump/Gripper 默认不注入，
#   否则 30083/30084/30085 的机载回执恒 result=-1（本脚本要求拿到肯定回执）。
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
# 跨平台公共库（B2 批次接 CI 时引入）：java 探测 / jar 路径 / 临时目录 / 按标记清残留
. (Join-Path $PSScriptRoot 'e2e-common.ps1')
$TempDir = Get-E2eTempDir   # Linux runner 上 $env:TEMP 常未设置，不能直接用
$Fail = 0
$Script:CleanupActions = @()
$Script:StartedProcesses = @()

# ---- 端口配置（参照 e2e-formation.ps1；D2 参数化：REST/drone/sim 三端口均可经环境变量覆盖）----
$BackendRestPort  = if ($env:AF_BACKEND_PORT)     { [int]$env:AF_BACKEND_PORT }     else { 18099 }
$BackendDronePort = if ($env:AF_SPRAY_DRONE_PORT) { [int]$env:AF_SPRAY_DRONE_PORT } else { 14550 }
$DroneSimPort     = if ($env:AF_SPRAY_SIM_PORT)   { [int]$env:AF_SPRAY_SIM_PORT }   else { 14551 }   # 单台 drone-sim 端口（喷洒场景只需 1 架）
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
    # java 探测走公共库 Get-E2eJava（跨平台：Linux 叫 java 不是 java.exe；
    # AF_JAVA → JAVA_HOME → AF_JDK17_HOME → PATH），替换原先只认 java.exe 的内联探测
    $javaExe = Get-E2eJava
    # 两个坑（formation/pixels 的同名函数各自防住了，本脚本原先都没防）：
    # 1) `java -version` 的输出走 stderr。脚本级 $ErrorActionPreference='Stop' 下，
    #    `2>&1` 会把 stderr 行提升成 terminating error（本机实测：JAVA_TOOL_OPTIONS
    #    的 "Picked up..." 行直接抛 RemoteException），被 catch 吞掉后一律误报
    #    "需要 Java >= 17"。必须像 formation/pixels 那样临时降级 EAP 再捕获。
    # 2) 设了 JAVA_TOOL_OPTIONS 时首行是 "Picked up JAVA_TOOL_OPTIONS..."，版本行
    #    在后面——不能盲取第一行，要在全部输出行里找版本行。
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = @(& $javaExe -version 2>&1 | ForEach-Object { "$_" })
    } catch {
        $raw = @()
    } finally {
        $ErrorActionPreference = $prev
    }
    $v = ($raw | Where-Object { $_ -match '"(\d+)(\.(\d+))?' } | Select-Object -First 1)
    if (-not $v) {
        Write-Host "无法解析 Java 版本（输出: $($raw -join ' | ')），请设置 JAVA_HOME 或 AF_JAVA 环境变量" -ForegroundColor Red
        exit 1
    }
    $null = $v -match '"(\d+)(\.(\d+))?'
    $major = [int]$Matches[1]
    $minor = if ($Matches[3]) { [int]$Matches[3] } else { 0 }
    $ok = if ($major -eq 1) { $minor -ge 17 } else { $major -ge 17 }
    if (-not $ok) {
        Write-Host "需要 Java >= 17，当前: $v" -ForegroundColor Red
        exit 1
    }
    return $javaExe
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
    # B2-CI 批次改造：原实现用 Get-CimInstance Win32_Process 枚举（Windows-only，
    # Linux 上该 cmdlet 不存在且被 SilentlyContinue 吞掉——清理静默失效，端口被占，
    # 表现为莫名超时，与 e2e-signing 踩过的坑同源）。改为 e2e-common 的
    # Stop-E2eStaleProcesses（Windows CIM / Linux /proc），按命令行留痕标记
    # -Dspray-e2e=true 精确杀（下方脚本启动的 backend / drone-sim 均带该标记）。
    $null = Stop-E2eStaleProcesses -Marker 'spray-e2e'
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
    # 业务判据而非 /actuator/health：dev profile 连本地 Redis，本机 6379 不通时
    # RedisHealthContributor 会把 health 打成 503，与后端是否可用无关——据 health
    # 判失败会把早就起来的后端误杀。/api/v1/drones 只读且恒 200（照抄 e2e-rid）。
    $probeUrl = "http://localhost:$BackendRestPort/api/v1/drones"
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep 1
        try {
            $r = Invoke-WebRequest -Uri $probeUrl -TimeoutSec 2 -UseBasicParsing
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

# jar 路径走 Get-E2eJar：反斜杠字面量在 Linux 上不是分隔符，Test-Path 恒 false
$backendJar  = Get-E2eJar -Root $Root -Module 'cloud-backend' -Jar 'aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'
$droneSimJar = Get-E2eJar -Root $Root -Module 'drone-sim' -Jar 'aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar'

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
    # 探测端点与 Wait-BackendReady 保持一致（业务端点，非 /actuator/health：
    # Redis 不通时 health 恒 503，会把已在运行的后端误判成"未运行"再起一个，
    # 撞端口后整个前置检查失败）
    $r = Invoke-WebRequest -Uri "http://localhost:$BackendRestPort/api/v1/drones" -TimeoutSec 2 -UseBasicParsing
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
    $backendLog = Join-Path $TempDir "spray-backend.log"
    if (Test-Path $backendLog) { Remove-Item $backendLog -Force }

    $backendArgs = @(
        '-jar', $backendJar,
        '--spring.profiles.active=dev',
        "--server.port=$BackendRestPort",
        "--aerofleet.drone-port=$BackendDronePort",
        "--aerofleet.drone-extra-ports=$DroneSimPort",
        # 默认 profile 的数据源是 PostgreSQL(localhost:5432)，本机/CI 常没有——
        # Flyway 连不上，后端启动即崩（本机实测 Connection refused → Spring 失败）。
        # 参照 e2e-rid/e2e-formation：自起后端统一传内存 H2，同时避免 dev 的
        # ./data/aerofleet 文件库残留别的 e2e 数据（编队/航线）干扰本脚本断言。
        "--spring.datasource.url=jdbc:h2:mem:spray-e2e;DB_CLOSE_DELAY=-1",
        # 默认 profile rbac-enabled=true 会拦业务 API；dev-mode 双保险旁路（照抄 rid）
        "--aerofleet.security.rbac-enabled=false",
        '--aerofleet.security.dev-mode=true',
        '-Dspray-e2e=true'
    )

    $backend = Start-Process -FilePath $java -ArgumentList $backendArgs `
        -PassThru -RedirectStandardOutput $backendLog
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
    $droneLog = Join-Path $TempDir "spray-drone-$DroneSysid.log"
    if (Test-Path $droneLog) { Remove-Item $droneLog -Force }

    $droneArgs = @(
        '-jar', $droneSimJar,
        '--port', $DroneSimPort,
        '--sysid', $DroneSysid,
        '--name', 'AF-SPRAY-01',
        # 必须带 --actuators（e2e-spray.sh 的既定要求）：不带时 SprayPump/Gripper
        # 不注入（VirtualDrone 构造器 actuatorsEnabled=false），30083/30084/30085
        # 的机载回执恒 result=-1，后续控制断言必挂
        '--actuators',
        '-Dspray-e2e=true'
    )

    $drone = Start-Process -FilePath $java -ArgumentList $droneArgs `
        -PassThru -RedirectStandardOutput $droneLog
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
# 3. 确认无人机在线（流程第1步）
# ============================================================
Step '3. 确认无人机在线（GET /api/v1/drones）'
try {
    $drones = Invoke-GetJson "$Base/drones"
    $d1 = $drones | Where-Object { $_.sysid -eq $DroneSysid }
    if ($d1) {
        # ${DroneSysid} 必须加大括号：裸写 $DroneSysid: 会被 PowerShell 解析成
        # 作用域/驱动器限定变量引用（InvalidVariableReferenceWithDrive），整个脚本解析失败。
        Info "无人机 sysid=${DroneSysid}: online=$($d1.online), battery=$($d1.battery)"
        Check "无人机 sysid=$DroneSysid 在线" ($d1.online -eq $true)
    } else {
        Check "无人机 sysid=$DroneSysid 在线" $false
    }
} catch {
    Info "请求失败: $_"
    Check '查询无人机列表' $false
}

# ============================================================
# 4. 创建喷洒任务（流程第2步）— POST /api/v1/spray
# ============================================================
Step '4. 创建喷洒任务（POST /api/v1/spray）— targetRate=2500mL/s, capacityMl=50000, sprayWidth=4m, 3航点'

# 端点口径对齐 SprayController.create：waypoints 为 [[lat,lon],...]（至少 2 点），
# targetRate/capacityMl/sprayWidth 必须为正
$sprayBody = @{
    targetSysid = $DroneSysid
    waypoints = @(
        @(22.5907, 113.9345),
        @(22.5917, 113.9345),
        @(22.5917, 113.9355)
    )
    targetRate = 2500
    capacityMl = 50000
    sprayWidth = 4
}
$taskId = $null
try {
    $sprayResp = Invoke-PostJson "$Base/spray" $sprayBody
    Info "响应: $($sprayResp | ConvertTo-Json -Compress -Depth 6)"
    $taskId = $sprayResp.taskId
    Check '返回 taskId 非空' ($taskId -ne $null -and $taskId -ne '')
    Check "分段数=航点数-1 (segments=$($sprayResp.segments))" ("$($sprayResp.segments)" -eq '2')
    # SprayTask.State = PENDING / RUNNING / PAUSED / COMPLETED / FAILED，无 CREATED
    Check "初始状态为 PENDING (state=$($sprayResp.state))" ("$($sprayResp.state)" -eq 'PENDING')
    Check "totalArea 为正数 ($($sprayResp.totalArea))" ([double]$sprayResp.totalArea -gt 0)
} catch {
    Info "请求失败: $_"
    Check '创建喷洒任务' $false
}
Info "任务 ID: $taskId"

# ============================================================
# 5. 查询喷洒任务（流程第3步）— GET /api/v1/spray/{id}
# ============================================================
Step "5. 查询喷洒任务（GET /api/v1/spray/$taskId）"
Start-Sleep 1
if ($taskId) {
    try {
        $stResp = Invoke-GetJson "$Base/spray/$taskId"
        Info "响应: $($stResp | ConvertTo-Json -Compress -Depth 4)"
        Check "查询返回 state (state=$($stResp.state))" ($null -ne $stResp.state -and "$($stResp.state)" -ne '')
        Check "remainingChemical 为正数（初始=药箱容量, got $($stResp.remainingChemical)）" ([double]$stResp.remainingChemical -gt 0)
    } catch {
        Info "请求失败: $_"
        Check '查询喷洒任务' $false
    }
} else {
    Check '查询喷洒任务（无 taskId 可查）' $false
}

# ============================================================
# 6. 控制喷洒任务 START（流程第4步）— POST /api/v1/spray/{id}/control
# ============================================================
Step "6. 控制喷洒任务 START（POST /api/v1/spray/$taskId/control）"
# 机载回执必须为肯定：drone-sim 带 --actuators 时 SprayPump 已注入，handleSprayControl
# 返回 MAV_RESULT_ACCEPTED；result=-1 说明执行机构没装配，是必须暴露的配置问题而非噪声
$controlStartAcked = $false
$controlStartHasResults = $false
if ($taskId) {
    try {
        $startResp = Invoke-PostJson "$Base/spray/$taskId/control" @{ action = 'START' }
        Info "响应: $($startResp | ConvertTo-Json -Compress -Depth 6)"
        $controlStartHasResults = ($null -ne $startResp.results)
        $controlStartAcked = $controlStartHasResults
        if ($startResp.results) {
            foreach ($p in $startResp.results.PSObject.Properties) {
                if ([int]$p.Value.result -eq -1) { $controlStartAcked = $false }
            }
        }
    } catch { Info "请求失败: $_" }
}
Check 'START 返回 results 结构' $controlStartHasResults
Check 'START 拿到机载肯定回执（需 drone-sim 带 --actuators）' $controlStartAcked

# ============================================================
# 7. 控制喷洒任务 STOP（流程第4步）— POST /api/v1/spray/{id}/control
# ============================================================
Step "7. 控制喷洒任务 STOP（POST /api/v1/spray/$taskId/control）"
$controlStopAcked = $false
$controlStopHasResults = $false
if ($taskId) {
    try {
        $stopResp = Invoke-PostJson "$Base/spray/$taskId/control" @{ action = 'STOP' }
        Info "响应: $($stopResp | ConvertTo-Json -Compress -Depth 6)"
        $controlStopHasResults = ($null -ne $stopResp.results)
        $controlStopAcked = $controlStopHasResults
        if ($stopResp.results) {
            foreach ($p in $stopResp.results.PSObject.Properties) {
                if ([int]$p.Value.result -eq -1) { $controlStopAcked = $false }
            }
        }
    } catch { Info "请求失败: $_" }
}
Check 'STOP 返回 results 结构' $controlStopHasResults
Check 'STOP 拿到机载肯定回执（需 drone-sim 带 --actuators）' $controlStopAcked

# ============================================================
# 8. 创建配送任务（流程第5步）— POST /api/v1/delivery
# ============================================================
Step '8. 创建配送任务（POST /api/v1/delivery）— 2 站点，各带 1 件负载'

# 端点口径对齐 DeliveryController.create：sites 每站含 lat/lon/alt/payloadId/
# payloadWeightKg/payloadVolumeL/dropAccuracyM
$deliveryBody = @{
    targetSysid = $DroneSysid
    sites = @(
        @{ lat = 22.5907; lon = 113.9345; alt = 80; payloadId = 1; payloadWeightKg = 2.5; payloadVolumeL = 8; dropAccuracyM = 1.5 },
        @{ lat = 22.5917; lon = 113.9355; alt = 80; payloadId = 2; payloadWeightKg = 1.5; payloadVolumeL = 5; dropAccuracyM = 2.0 }
    )
}
$deliveryId = $null
try {
    $dResp = Invoke-PostJson "$Base/delivery" $deliveryBody
    Info "响应: $($dResp | ConvertTo-Json -Compress -Depth 6)"
    $deliveryId = $dResp.deliveryId
    Check '返回 deliveryId 非空' ($deliveryId -ne $null -and $deliveryId -ne '')
    Check "创建响应 sites=站点数 (got $($dResp.sites))" ("$($dResp.sites)" -eq '2')
} catch {
    Info "请求失败: $_"
    Check '创建配送任务' $false
}
Info "配送任务 ID: $deliveryId"

# ============================================================
# 9. 查询配送序列（流程第6步）— GET /api/v1/delivery/{id}
# ============================================================
Step "9. 查询配送序列（GET /api/v1/delivery/$deliveryId）"
if ($deliveryId) {
    try {
        $seqResp = Invoke-GetJson "$Base/delivery/$deliveryId"
        Info "响应: $($seqResp | ConvertTo-Json -Compress -Depth 6)"
        # 注意 @($null).Count 在 PowerShell 里等于 1，缺失字段必须显式判空
        $sitesArr = @()
        if ($null -ne $seqResp.sites) { $sitesArr = @($seqResp.sites) }
        Check "配送序列包含 2 个站点 (got $($sitesArr.Count))" ($sitesArr.Count -eq 2)
        $firstPid = if ($sitesArr.Count -ge 1) { $sitesArr[0].payloadId } else { $null }
        Check "首站 payloadId=1 (got $firstPid)" ("$firstPid" -eq '1')
        Check "progress 字段存在 (got $($seqResp.progress))" ($null -ne $seqResp.progress)
    } catch {
        Info "请求失败: $_"
        Check '查询配送序列' $false
    }
} else {
    Check '查询配送序列（无 deliveryId 可查）' $false
}

# ============================================================
# 10. 查询负载清单（流程第7步）— GET /api/v1/delivery/{id}/payload
# ============================================================
Step "10. 查询负载清单（GET /api/v1/delivery/$deliveryId/payload）"
if ($deliveryId) {
    try {
        $payResp = Invoke-GetJson "$Base/delivery/$deliveryId/payload"
        Info "响应: $($payResp | ConvertTo-Json -Compress -Depth 6)"
        $payArr = @()
        if ($null -ne $payResp.payloads) { $payArr = @($payResp.payloads) }
        Check "负载清单含 payloads 数组 (got $($payArr.Count) 件)" ($null -ne $payResp.payloads)
        Check "totalWeight 为数值 (got $($payResp.totalWeight))" ($null -ne $payResp.totalWeight)
    } catch {
        Info "请求失败: $_"
        Check '查询负载清单' $false
    }
} else {
    Check '查询负载清单（无 deliveryId）' $false
}

# ============================================================
# 11. 按需触发机载负载上报（流程第8步）— POST /api/v1/delivery/{id}/payload/query
# ============================================================
Step "11. 按需触发机载负载上报（POST /api/v1/delivery/$deliveryId/payload/query）"
# payload query 的处理器也要求 gripper 已注入，否则恒回 error；
# status=ok 才算机载真的回了 PAYLOAD_STATUS(30006)
if ($deliveryId) {
    try {
        $pqResp = Invoke-PostJson "$Base/delivery/$deliveryId/payload/query" @{ }
        Info "响应: $($pqResp | ConvertTo-Json -Compress -Depth 6)"
        Check "负载上报目标 sysid=$DroneSysid (got $($pqResp.sysid))" ("$($pqResp.sysid)" -eq "$DroneSysid")
        Check "机载接受负载上报并回执（需 drone-sim 带 --actuators, status=$($pqResp.status))" ("$($pqResp.status)" -eq 'ok')
    } catch {
        Info "请求失败: $_"
        Check '按需触发机载负载上报' $false
    }
} else {
    Check '按需触发机载负载上报（无 deliveryId）' $false
}

# ============================================================
# 12. 夹爪复位 + 配送启动（流程第9步）— POST /api/v1/delivery/{id}/control
# ============================================================
Step "12. 夹爪复位 + 配送启动（POST /api/v1/delivery/$deliveryId/control）"
# 夹爪的机载能力走 MAV_CMD 30084 GRIPPER_CONTROL（本仓无独立 REST 端点）。
# 先 RESET：抓取的前置条件是夹爪 state==IDLE，上轮跑完可能停在 HOLDING，
# 直接 START 会被机载正确拒绝（MAV_RESULT_DENIED）；复位也让断言可重复执行。
if ($deliveryId) {
    $resetAcked = $false; $resetHasResults = $false
    $dStartAcked = $false; $dStartHasResults = $false
    try {
        $grResp = Invoke-PostJson "$Base/delivery/$deliveryId/control" @{ action = 'RESET' }
        Info "响应: $($grResp | ConvertTo-Json -Compress -Depth 6)"
        $resetHasResults = ($null -ne $grResp.results)
        $resetAcked = $resetHasResults
        if ($grResp.results) {
            foreach ($p in $grResp.results.PSObject.Properties) {
                if ([int]$p.Value.result -eq -1) { $resetAcked = $false }
            }
        }
    } catch { Info "请求失败: $_" }
    Check '夹爪复位返回 results 结构' $resetHasResults
    Check '夹爪复位拿到机载肯定回执（需 drone-sim 带 --actuators）' $resetAcked
    try {
        $dsResp = Invoke-PostJson "$Base/delivery/$deliveryId/control" @{ action = 'START' }
        Info "响应: $($dsResp | ConvertTo-Json -Compress -Depth 6)"
        $dStartHasResults = ($null -ne $dsResp.results)
        $dStartAcked = $dStartHasResults
        if ($dsResp.results) {
            foreach ($p in $dsResp.results.PSObject.Properties) {
                if ([int]$p.Value.result -eq -1) { $dStartAcked = $false }
            }
        }
    } catch { Info "请求失败: $_" }
    Check '配送 START 返回 results 结构' $dStartHasResults
    Check '配送 START 拿到机载肯定回执（需 drone-sim 带 --actuators）' $dStartAcked
} else {
    Check '夹爪复位返回 results 结构' $false
    Check '夹爪复位拿到机载肯定回执（需 drone-sim 带 --actuators）' $false
    Check '配送 START 返回 results 结构' $false
    Check '配送 START 拿到机载肯定回执（需 drone-sim 带 --actuators）' $false
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
    Write-Host '  覆盖：确认在线 → 创建喷洒 → 查询喷洒 → 喷洒控制(START/STOP) → 创建配送 → 查询序列 → 负载清单 → 负载上报 → 夹爪复位/配送启动' -ForegroundColor DarkGray
    exit 0
} else {
    Write-Host 'SPRAY E2E FAILED（部分断言未通过，见上方 FAIL 行）' -ForegroundColor Red
    exit 1
}
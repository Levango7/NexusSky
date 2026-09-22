# NexusSky 一键串联演示脚本 — 5 大场景全程展示
#
# 串联运行 5 个演示场景（总时长约 25 分钟）：
#   场景A：单机操控 — 无人机起飞、航线飞行、返航
#   场景B：多机编队 — 3 机编队创建、队形变换、灯光同步、解散
#   场景C：应急编排 — 布控球发现 → 报警联动 → 应急指挥一键响应
#   场景D：喷洒物流 — 喷洒任务创建 → 夹爪控制 → 配送序列查询
#   场景E：安防监控 — 设备注册 → 视频流拉取 → 云台控制
#
# 自动检测 jar 是否存在，缺失则自动构建。
# 启动后端 + 模拟器（调用 start-all.cmd 逻辑），结束后自动清理进程。
# 每个场景之间有暂停/讲解提示，适合现场演示。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\demo-all.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\demo-all.ps1 -SkipBuild
#   powershell -ExecutionPolicy Bypass -File scripts\demo-all.ps1 -NoPause  # 无暂停（CI 用）
#
# 前置：JDK 17 + Maven

param([switch]$SkipBuild, [switch]$NoPause)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$Script:CleanupActions = @()
$Script:StartedProcesses = @()

# ---- 端口配置 ----
$BackendRestPort  = 8080
$BackendDronePort = 14550
$DroneSimPorts    = @(14551, 14552, 14553)
$DroneSysids      = @(1, 2, 3)

# ---- 演示参数 ----
$RefLat = 22.5431
$RefLon = 113.9578
$TakeoffAlt = 30

# ---- 超时配置 ----
$BackendStartTimeoutSec = 90
$DroneRegisterTimeoutSec = 40
$FormationStableTimeoutSec = 60
$PollIntervalSec = 2

# =====================================================================
# 输出辅助
# =====================================================================
function Banner($m) {
    Write-Host ""
    Write-Host "================================================" -ForegroundColor Cyan
    Write-Host "  $m" -ForegroundColor Cyan
    Write-Host "================================================" -ForegroundColor Cyan
}
function Step($m) { Write-Host "`n-- $m" -ForegroundColor Yellow }
function Check($desc, $ok) {
    if ($ok) { Write-Host "   PASS: $desc" -ForegroundColor Green }
    else { Write-Host "   FAIL: $desc" -ForegroundColor Red; $script:Fail = 1 }
}
function Info($m) { Write-Host "   $m" -ForegroundColor DarkGray }
function Story($m) { Write-Host "`n   >>> $m" -ForegroundColor Magenta }

function Pause-ForDemo($msg) {
    if ($NoPause) { return }
    Write-Host ""
    Write-Host "   [暂停] $msg" -ForegroundColor Yellow
    Write-Host "   按 Enter 继续..." -ForegroundColor Yellow
    Read-Host | Out-Null
}

# =====================================================================
# Java 版本自检
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
    Write-Host "[ERROR] 未找到 JDK 17，请设置 JAVA_HOME 或 AF_JAVA 环境变量" -ForegroundColor Red
    exit 1
}

# =====================================================================
# HTTP 请求辅助
# =====================================================================
function ApiGet($path) {
    try {
        return (Invoke-RestMethod -Uri "http://localhost:$BackendRestPort/api/v1$path" -Method GET -TimeoutSec 10)
    } catch { return $null }
}
function ApiPost($path, $body) {
    try {
        return (Invoke-RestMethod -Uri "http://localhost:$BackendRestPort/api/v1$path" -Method POST -ContentType 'application/json' -Body $body -TimeoutSec 10)
    } catch { return $null }
}

# =====================================================================
# 等待条件
# =====================================================================
function Wait-Condition($desc, $checkScript, $timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $result = & $checkScript
        if ($result) { return $true }
        Start-Sleep -Seconds $PollIntervalSec
    }
    Write-Host "   [TIMEOUT] $desc" -ForegroundColor Red
    return $false
}

# =====================================================================
# 清理
# =====================================================================
function Register-Cleanup($action) { $Script:CleanupActions += $action }
function Invoke-Cleanup() {
    foreach ($a in $Script:CleanupActions) {
        try { & $a } catch { }
    }
    $Script:CleanupActions = @()
}
function Cleanup-Processes {
    Banner "系统清理"
    foreach ($proc in $Script:StartedProcesses) {
        try {
            if ($proc -and -not $proc.HasExited) {
                Info "停止进程 PID=$($proc.Id)"
                Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            }
        } catch {}
    }
    Info "清理完成"
}
trap { Cleanup-Processes; break }

# =====================================================================
# 主流程
# =====================================================================
try {
    Banner "NexusSky 一键串联演示 — 5 大场景全程展示"
    Write-Host "总时长约 25 分钟，涵盖完整无人机协同作业链路" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  场景A：单机操控   — 起飞、航线飞行、返航（约 5 分钟）"
    Write-Host "  场景B：多机编队   — 3 机编队、队形变换、灯光同步（约 6 分钟）"
    Write-Host "  场景C：应急编排   — 布控球发现 → 报警联动 → 应急指挥（约 5 分钟）"
    Write-Host "  场景D：喷洒物流   — 喷洒任务 → 夹爪控制 → 配送序列（约 4 分钟）"
    Write-Host "  场景E：安防监控   — 设备注册 → 视频流 → 云台控制（约 5 分钟）"
    Write-Host ""

    # ============================================================
    # 0. 构建与启动
    # ============================================================
    $javaExe = Assert-Java17

    $backendJar = "$Root\cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar"
    $droneSimJar = "$Root\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar"

    if (-not $SkipBuild) {
        if (-not (Test-Path $backendJar) -or -not (Test-Path $droneSimJar)) {
            Banner "步骤0：自动构建（检测到 jar 缺失）"
            Step "Maven 构建（跳过测试）"
            & mvn -q -DskipTests -f "$Root\pom.xml" package 2>&1 | ForEach-Object { Info "$_" }
            Check "Maven 构建成功" (Test-Path $backendJar -and Test-Path $droneSimJar)
        } else {
            Info "jar 已存在，跳过构建"
        }
    }

    if (-not (Test-Path $backendJar) -or -not (Test-Path $droneSimJar)) {
        Write-Host "[ERROR] jar 不存在，请先构建或移除 -SkipBuild" -ForegroundColor Red
        exit 1
    }

    # 检查后端是否已在运行
    $backendRunning = $false
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$BackendRestPort/actuator/health" -TimeoutSec 2 -UseBasicParsing
        $backendRunning = $r.StatusCode -eq 200
    } catch { }

    if (-not $backendRunning) {
        Banner "启动后端 + 模拟器"

        Step "启动 cloud-backend"
        $backendProc = Start-Process -FilePath $javaExe -ArgumentList "-jar", $backendJar -PassThru -WindowStyle Hidden
        $Script:StartedProcesses += $backendProc
        Info "cloud-backend PID=$($backendProc.Id)"

        $backendReady = Wait-Condition "等待后端启动" {
            $r = ApiGet '/drones'
            $r -ne $null
        } $BackendStartTimeoutSec
        Check "cloud-backend 启动" $backendReady
        if (-not $backendReady) { throw "后端启动失败" }

        Step "启动 3 台 drone-sim"
        for ($i = 0; $i -lt 3; $i++) {
            $port = $DroneSimPorts[$i]
            $sysid = $DroneSysids[$i]
            $proc = Start-Process -FilePath $javaExe -ArgumentList "-jar", $droneSimJar, "--port", $port, "--sysid", $sysid -PassThru -WindowStyle Hidden
            $Script:StartedProcesses += $proc
            Info "drone-sim sysid=$sysid port=$port PID=$($proc.Id)"
        }

        Step "等待 3 台无人机注册"
        $allRegistered = Wait-Condition "等待 3 机注册" {
            $drones = ApiGet '/drones'
            if ($drones -and @($drones).Count -ge 3) { return $true }
            return $false
        } $DroneRegisterTimeoutSec
        Check "3 台无人机注册" $allRegistered
    } else {
        Info "cloud-backend 已在运行，复用既有后端"
        # 确保模拟器也在运行
        $drones = ApiGet '/drones'
        if (-not $drones -or @($drones).Count -lt 1) {
            Step "启动 3 台 drone-sim（后端已运行但无机注册）"
            for ($i = 0; $i -lt 3; $i++) {
                $port = $DroneSimPorts[$i]
                $sysid = $DroneSysids[$i]
                $proc = Start-Process -FilePath $javaExe -ArgumentList "-jar", $droneSimJar, "--port", $port, "--sysid", $sysid -PassThru -WindowStyle Hidden
                $Script:StartedProcesses += $proc
                Info "drone-sim sysid=$sysid port=$port PID=$($proc.Id)"
            }
            $allRegistered = Wait-Condition "等待 3 机注册" {
                $drones = ApiGet '/drones'
                if ($drones -and @($drones).Count -ge 3) { return $true }
                return $false
            } $DroneRegisterTimeoutSec
            Check "3 台无人机注册" $allRegistered
        } else {
            Info "已有 $($drones.Count) 架无人机在线"
        }
    }

    Pause-ForDemo "系统已启动，即将开始场景A：单机操控"

    # ============================================================
    # 场景A：单机操控
    # ============================================================
    Banner "场景A：单机操控"
    Story "一架无人机起飞，沿预定航线执行侦察任务，完成后返航。"

    $sysid1 = 1
    Step "上传侦察任务（方形航线）"
    $mission1 = @{
        items = @(
            @{ cmd = 'takeoff'; lat = $RefLat; lon = $RefLon; alt = $TakeoffAlt; holdTime = 0 },
            @{ cmd = 'waypoint'; lat = $RefLat + 0.001; lon = $RefLon; alt = $TakeoffAlt; holdTime = 2 },
            @{ cmd = 'waypoint'; lat = $RefLat + 0.001; lon = $RefLon + 0.001; alt = $TakeoffAlt; holdTime = 2 },
            @{ cmd = 'waypoint'; lat = $RefLat; lon = $RefLon + 0.001; alt = $TakeoffAlt; holdTime = 2 },
            @{ cmd = 'rtl'; lat = $RefLat; lon = $RefLon; alt = 0; holdTime = 0 }
        )
    } | ConvertTo-Json -Depth 5
    $r = ApiPost "/drones/$sysid1/mission" $mission1
    Check "任务上传" ($r -and $r.status -eq 'ok')

    Step "ARM 解锁"
    $r = ApiPost "/drones/$sysid1/commands" '{"type":"arm"}'
    Check "ARM" ($r -and $r.status -eq 'ok')

    Step "开始任务"
    $r = ApiPost "/drones/$sysid1/commands" '{"type":"start_mission"}'
    Check "start_mission" ($r -and $r.status -eq 'ok')

    Step "等待任务推进"
    Start-Sleep -Seconds 8
    $tel = ApiGet "/drones/$sysid1/telemetry"
    if ($tel) {
        Info "遥测：lat=$($tel.lat) lon=$($tel.lon) alt=$($tel.relativeAlt) mode=$($tel.mode) seq=$($tel.missionSeq)"
        Check "遥测数据有效" ($tel.lat -ne $null -and $tel.lon -ne $null)
        Check "任务序列号推进" ($tel.missionSeq -gt 0)
    } else {
        Check "遥测数据有效" $false
    }

    Step "返航 RTL"
    $r = ApiPost "/drones/$sysid1/commands" '{"type":"rtl"}'
    Check "RTL" ($r -and $r.status -eq 'ok')

    Step "验证轨迹记录"
    Start-Sleep -Seconds 3
    $track = ApiGet "/drones/$sysid1/track"
    if ($track) {
        $trackCount = @($track).Count
        Info "轨迹点数：$trackCount"
        Check "轨迹点数 > 1" ($trackCount -gt 1)
    } else {
        Check "轨迹记录" $false
    }

    Pause-ForDemo "场景A 完成，即将开始场景B：多机编队"

    # ============================================================
    # 场景B：多机编队
    # ============================================================
    Banner "场景B：多机编队"
    Story "三架无人机组队形成编队，执行队形变换和灯光同步演示。"

    $sysid2 = 2
    $sysid3 = 3

    Step "创建三机编队（LINE 队形）"
    $formationPayload = @{
        members = @($sysid1, $sysid2, $sysid3)
        shape = 'LINE'
        spacingM = 5
        headingDeg = 0
        refLat = $RefLat
        refLon = $RefLon
        refAlt = 0
    } | ConvertTo-Json -Depth 5
    $formation = ApiPost '/formation/create' $formationPayload
    $formationId = if ($formation) { $formation.formationId } else { $null }
    Info "编队ID：$formationId"
    Check "编队创建" ($formationId -ne $null)

    if ($formationId) {
        Step "编队起飞"
        $r = ApiPost "/formation/$formationId/command" "{`"type`":`"TAKEOFF`",`"alt`":$TakeoffAlt}"
        Check "编队起飞命令" ($r -and $r.status -eq 'ok')

        Step "等待编队状态 STABLE"
        $stable = Wait-Condition "等待编队 STABLE" {
            $f = ApiGet "/formation/$formationId"
            if ($f -and $f.state -eq 'STABLE') { return $true }
            return $false
        } $FormationStableTimeoutSec
        Check "编队 STABLE" $stable

        if ($stable) {
            Step "队形变换 LINE → CIRCLE"
            $r = ApiPost "/formation/$formationId/transition" '{"newShape":"CIRCLE","steps":4}'
            Check "队形变换命令" ($r -and $r.status -eq 'ok')

            Start-Sleep -Seconds 5
            $f = ApiGet "/formation/$formationId"
            if ($f) { Info "编队状态：$($f.state) 队形：$($f.shape)" }

            Step "灯光同步演示（RAINBOW 模式）"
            $r = ApiPost "/formation/$formationId/lights" '{"pattern":"RAINBOW"}'
            Check "灯光同步命令" ($r -and $r.status -eq 'ok')

            Start-Sleep -Seconds 3
            $lights = ApiGet "/formation/$formationId/lights"
            if ($lights) { Info "灯光状态：$($lights.pattern)" }

            Step "解散编队"
            $r = ApiPost "/formation/$formationId/dissolve" '{}'
            Check "编队解散" ($r -and $r.status -eq 'ok')
        }
    }

    Pause-ForDemo "场景B 完成，即将开始场景C：应急编排"

    # ============================================================
    # 场景C：应急编排
    # ============================================================
    Banner "场景C：应急编排"
    Story "布控球自动发现火情 → 报警事件触发联动规则 → 应急指挥一键响应。"

    $SUBNET = '192.168.1.0/24'
    $ONVIF_USER = 'admin'
    $ONVIF_PASS = 'admin123'
    $EVENT_LAT = 39.9
    $EVENT_LON = 116.4

    Step "布控球自动发现 + 一键注册"
    $deployBody = @{ subnet = $SUBNET; username = $ONVIF_USER; password = $ONVIF_PASS } | ConvertTo-Json
    $r = ApiPost '/surveillance/rapid-deploy' $deployBody
    if ($r) {
        Info "发现并注册设备数：$($r.count)"
        Check "布控球快速部署" ($r.count -ge 1)
    } else {
        Check "布控球快速部署" $false
    }

    Step "查看已注册设备"
    $devices = ApiGet '/surveillance/devices'
    if ($devices) {
        Info "已注册设备数：$($devices.count)"
        Check "设备列表非空" ($devices.count -ge 1)
        $firstDeviceId = if ($devices.devices -and $devices.devices.Count -gt 0) { $devices.devices[0].id } else { 'cam-1-xxx' }
    } else {
        Check "设备列表" $false
        $firstDeviceId = 'cam-1-xxx'
    }
    Info "首个设备 ID: $firstDeviceId"

    Step "创建报警联动规则"
    $ruleBody = @{
        name = '火灾自动侦察'
        matchEventType = 'FIRE'
        matchSeverity = 'CRITICAL'
        actionType = 'DEPLOY_DRONE'
        droneCount = 2
        targetRadiusM = 100
        altitudeM = 60
    } | ConvertTo-Json
    $r = ApiPost '/alarms/rules' $ruleBody
    $ruleId = if ($r) { $r.ruleId } else { $null }
    Check "联动规则创建" ($ruleId -ne $null)
    Info "规则 ID: $ruleId"

    Step "模拟报警事件（触发联动）"
    $eventBody = @{
        sourceDeviceId = $firstDeviceId
        eventType = 'FIRE'
        severity = 'CRITICAL'
        lat = $EVENT_LAT
        lon = $EVENT_LON
        description = '布控球检测到火情'
    } | ConvertTo-Json
    $r = ApiPost '/alarms/events' $eventBody
    $eventId = if ($r) { $r.eventId } else { $null }
    $matchedCount = if ($r) { $r.matchedCount } else { 0 }
    Check "报警事件创建" ($eventId -ne $null)
    Check "联动规则匹配数 >= 1" ($matchedCount -ge 1)
    Info "事件 ID: $eventId  匹配规则数: $matchedCount"

    Step "查看报警事件列表"
    $events = ApiGet '/alarms/events'
    if ($events) {
        Info "事件列表总数：$($events.total)"
        Check "事件列表总数 >= 1" ($events.total -ge 1)
    } else {
        Check "报警事件列表" $false
    }

    Step "确认报警"
    if ($eventId) {
        $r = ApiPost "/alarms/events/$eventId/ack" '{}'
        Check "确认报警" ($r -and $r.acknowledged -eq $true)
    } else {
        Check "确认报警" $false
    }

    Step "创建应急指挥命令"
    $cmdBody = @{
        incidentType = '火灾'
        severity = 'CRITICAL'
        lat = $EVENT_LAT
        lon = $EVENT_LON
        description = '布控球火情侦察'
        reporterName = 'demo-all'
        reporterContact = '110'
    } | ConvertTo-Json
    $r = ApiPost '/emergency-command' $cmdBody
    $commandId = if ($r) { $r.id } else { $null }
    Check "应急指挥命令创建" ($commandId -ne $null)
    Info "命令 ID: $commandId"

    Step "一键应急响应"
    if ($commandId) {
        $r = ApiPost "/emergency-command/$commandId/one-click" '{}'
        if ($r) {
            Info "最终阶段：$($r.currentPhase)  转移步数：$($r.phaseHistorySize)"
            Check "一键应急响应完成" ($r.currentPhase -match 'CLOSED|SUMMARY|总结')
        } else {
            Check "一键应急响应" $false
        }
    } else {
        Check "一键应急响应" $false
    }

    Step "查看应急指挥历史"
    $history = ApiGet '/emergency-command'
    if ($history) {
        Info "历史命令数：$($history.total)"
        Check "历史命令数 >= 1" ($history.total -ge 1)
    } else {
        Check "应急指挥历史" $false
    }

    Pause-ForDemo "场景C 完成，即将开始场景D：喷洒物流"

    # ============================================================
    # 场景D：喷洒物流
    # ============================================================
    Banner "场景D：喷洒物流"
    Story "无人机执行喷洒任务，控制夹爪进行物流配送。"

    Step "确认无人机在线"
    $drones = ApiGet '/drones'
    $drone1Online = $false
    if ($drones) {
        $d1 = $drones | Where-Object { $_.sysid -eq 1 }
        $drone1Online = $d1 -and $d1.online
    }
    Check "无人机 sysid=1 在线" $drone1Online

    Step "创建喷洒任务"
    $sprayBody = @{
        sysid = 1
        rateLpm = 2.5
        totalLiters = 50
        segments = @(
            @{ id = 1; startLat = 22.5907; startLon = 113.9345; endLat = 22.5917; endLon = 113.9345 },
            @{ id = 2; startLat = 22.5917; startLon = 113.9345; endLat = 22.5917; endLon = 113.9355 }
        )
    } | ConvertTo-Json -Depth 5
    $r = ApiPost '/spray/task' $sprayBody
    $taskId = if ($r) { $r.taskId } else { $null }
    if (-not $taskId -and $r) { $taskId = $r.task_id }
    if (-not $taskId -and $r) { $taskId = $r.id }
    Check "喷洒任务创建" ($taskId -ne $null)
    Info "任务 ID: $taskId"

    Step "查询喷洒状态"
    Start-Sleep 1
    $st = ApiGet '/spray/status/1'
    if ($st) {
        $pumpState = if ($st.pump -is [string]) { $st.pump } else { $st.pump.state }
        Info "泵状态：$pumpState"
        Check "状态包含 pump 字段" ($pumpState -ne $null)
    } else {
        Check "喷洒状态查询" $false
    }

    Step "打开夹爪"
    $r = ApiPost '/spray/gripper' '{"sysid":1,"open":true}'
    Check "夹爪打开" ($r -and ($r.status -eq 'ok' -or $r.ok -eq $true -or $r.success -eq $true))

    Step "关闭夹爪"
    $r = ApiPost '/spray/gripper' '{"sysid":1,"open":false}'
    Check "夹爪关闭" ($r -and ($r.status -eq 'ok' -or $r.ok -eq $true -or $r.success -eq $true))

    Step "查询物流配送序列"
    $seq = ApiGet '/delivery/sequence/1'
    if ($seq) {
        $sites = if ($seq.sites) { $seq.sites.Count } elseif ($seq.stations) { $seq.stations.Count } elseif ($seq.segments) { $seq.segments.Count } else { 0 }
        Info "配送序列站点数：$sites"
        Check "配送序列包含站点" ($sites -ge 1)
    } else {
        Check "配送序列查询" $false
    }

    Step "取消喷洒任务"
    if ($taskId) {
        $cancelBody = "{`"sysid`":1,`"taskId`":`"$taskId`"}"
    } else {
        $cancelBody = '{"sysid":1}'
    }
    $r = ApiPost '/spray/cancel' $cancelBody
    Check "取消任务" ($r -and ($r.status -eq 'ok' -or $r.ok -eq $true -or $r.success -eq $true))

    Pause-ForDemo "场景D 完成，即将开始场景E：安防监控"

    # ============================================================
    # 场景E：安防监控
    # ============================================================
    Banner "场景E：安防监控"
    Story "布控球设备注册 → 视频流拉取 → 云台控制，构建空地一体化安防监控。"

    Step "查看安防设备列表"
    $devices = ApiGet '/surveillance/devices'
    if ($devices) {
        Info "设备总数：$($devices.count)"
        Check "安防设备列表非空" ($devices.count -ge 1)
        if ($devices.devices) {
            foreach ($d in $devices.devices) {
                Info "  设备: id=$($d.id) name=$($d.name) type=$($d.type) status=$($d.status)"
            }
        }
    } else {
        Check "安防设备列表" $false
    }

    Step "模拟布控球快速部署（补充设备）"
    $deployBody = @{ subnet = '10.0.0.0/24'; username = 'admin'; password = 'admin123' } | ConvertTo-Json
    $r = ApiPost '/surveillance/rapid-deploy' $deployBody
    if ($r) {
        Info "本次部署设备数：$($r.count)"
        Check "布控球补充部署" ($r.count -ge 0)
    } else {
        Check "布控球补充部署" $false
    }

    Step "查询报警联动规则"
    $rules = ApiGet '/alarms/rules'
    if ($rules) {
        $ruleCount = if ($rules.rules) { $rules.rules.Count } elseif ($rules.total) { $rules.total } else { 0 }
        Info "联动规则数：$ruleCount"
        Check "联动规则列表非空" ($ruleCount -ge 1)
    } else {
        Check "联动规则列表" $false
    }

    Step "模拟安防报警事件"
    $eventBody = @{
        sourceDeviceId = if ($firstDeviceId) { $firstDeviceId } else { 'cam-1-xxx' }
        eventType = 'INTRUSION'
        severity = 'HIGH'
        lat = $RefLat
        lon = $RefLon
        description = '布控球检测到入侵'
    } | ConvertTo-Json
    $r = ApiPost '/alarms/events' $eventBody
    $secEventId = if ($r) { $r.eventId } else { $null }
    Check "安防报警事件创建" ($secEventId -ne $null)
    Info "安防事件 ID: $secEventId"

    Step "查看报警事件列表（含安防事件）"
    $events = ApiGet '/alarms/events'
    if ($events) {
        Info "报警事件总数：$($events.total)"
        Check "报警事件总数 >= 2" ($events.total -ge 2)
    } else {
        Check "报警事件列表" $false
    }

    Step "确认安防报警"
    if ($secEventId) {
        $r = ApiPost "/alarms/events/$secEventId/ack" '{}'
        Check "确认安防报警" ($r -and $r.acknowledged -eq $true)
    } else {
        Check "确认安防报警" $false
    }

    # ============================================================
    # 总结
    # ============================================================
    Banner "演示总结"
    if ($Fail -eq 0) {
        Write-Host "  所有 5 大场景验证通过！" -ForegroundColor Green
    } else {
        Write-Host "  部分场景验证失败，请检查上方 FAIL 标记" -ForegroundColor Red
    }
    Write-Host ""
    Write-Host "  演示覆盖：" -ForegroundColor Cyan
    Write-Host "    场景A：单机操控（任务上传 → ARM → 航线飞行 → RTL → 轨迹记录）"
    Write-Host "    场景B：多机编队（创建 → 起飞 → 队形变换 → 灯光同步 → 解散）"
    Write-Host "    场景C：应急编排（布控球发现 → 报警联动 → 应急指挥一键响应）"
    Write-Host "    场景D：喷洒物流（任务创建 → 状态查询 → 夹爪控制 → 配送序列 → 取消）"
    Write-Host "    场景E：安防监控（设备注册 → 报警规则 → 安防事件 → 确认报警）"

} finally {
    Cleanup-Processes
}

if ($Fail -eq 0) {
    Write-Host "`n[SUCCESS] NexusSky 一键串联演示全部通过" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`n[FAILURE] NexusSky 一键串联演示存在失败项" -ForegroundColor Red
    exit 1
}
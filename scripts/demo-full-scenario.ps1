# NexusSky 综合演示场景脚本
#
# 讲述一个完整的无人机协同作业故事：
#   场景1：单机侦察 — 一架无人机起飞执行侦察任务并返航
#   场景2：多机协同 — 检测到异常，增派两架无人机协同作业
#   场景3：编队包围 — 三机组成编队，队形变换，灯光同步
#   场景4：应急响应 — 一架无人机失联，自动返航，剩余机继续任务
#   场景5：追踪恢复 — 定位失联无人机，引导搜索
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\demo-full-scenario.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\demo-full-scenario.ps1 -SkipBuild
#
# 前置：JDK 17 + Maven + Python3（用于 JSON 解析）

param([switch]$SkipBuild)

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
$MissionProgressTimeoutSec = 30
$FormationStableTimeoutSec = 60
$PollIntervalSec = 2

# ---- 输出辅助 ----
function Banner($m) {
    Write-Host ""
    Write-Host "================================================" -ForegroundColor Cyan
    Write-Host "  $m" -ForegroundColor Cyan
    Write-Host "================================================" -ForegroundColor Cyan
}
function Step($m) { Write-Host "`n-- $m" -ForegroundColor Yellow }
function Check($desc, $ok) {
    if ($ok) { Write-Host "   [PASS] $desc" -ForegroundColor Green }
    else { Write-Host "   [FAIL] $desc" -ForegroundColor Red; $script:Fail = 1 }
}
function Info($m) { Write-Host "   $m" -ForegroundColor DarkGray }
function Story($m) { Write-Host "`n   >>> $m" -ForegroundColor Magenta }

# ---- JSON 解析 ----
function JsonGet($json, $key) {
    try { return ($json | ConvertFrom-Json).$key } catch { return $null }
}
function JsonGetList($json) {
    try { return ($json | ConvertFrom-Json) } catch { return @() }
}

# ---- HTTP 请求 ----
function ApiGet($path) {
    try { return (Invoke-RestMethod -Uri "http://localhost:$BackendRestPort/api/v1$path" -Method GET -TimeoutSec 10) } catch { return $null }
}
function ApiPost($path, $body) {
    try {
        return (Invoke-RestMethod -Uri "http://localhost:$BackendRestPort/api/v1$path" -Method POST -ContentType 'application/json' -Body $body -TimeoutSec 10)
    } catch { return $null }
}

# ---- Java 版本自检 ----
function Assert-Java17 {
    $javaExe = 'java'
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
    # 尝试 JDK 17 路径
    $jdk17 = 'E:\dev-tools\jdk17.0.20_8\bin\java.exe'
    if (Test-Path $jdk17) { return $jdk17 }
    Write-Host "[ERROR] 未找到 JDK 17，当前 Java 版本不满足要求" -ForegroundColor Red
    exit 1
}

# ---- 等待条件 ----
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

# ---- 清理 ----
function Cleanup {
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

# ---- 主流程 ----
try {
    Banner "NexusSky 综合演示场景"
    Write-Host "讲述一个完整的无人机协同作业故事" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "场景1：单机侦察 — 一架无人机起飞执行侦察任务并返航"
    Write-Host "场景2：多机协同 — 检测到异常，增派两架无人机协同作业"
    Write-Host "场景3：编队包围 — 三机组成编队，队形变换，灯光同步"
    Write-Host "场景4：应急响应 — 一架无人机失联，自动返航"
    Write-Host "场景5：追踪恢复 — 定位失联无人机，引导搜索"

    # ============================================================
    # 0. 构建与启动
    # ============================================================
    $javaExe = Assert-Java17

    if (-not $SkipBuild) {
        Banner "步骤0：构建系统"
        Step "Maven 构建（跳过测试）"
        $env:JAVA_HOME = 'E:\dev-tools\jdk17.0.20_8'
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        & mvn -q -DskipTests -f "$Root\pom.xml" package 2>&1 | ForEach-Object { Info "$_" }
        Check "Maven 构建成功" (Test-Path "$Root\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar")
    }

    Step "启动 cloud-backend"
    $backendJar = "$Root\cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar"
    if (-not (Test-Path $backendJar)) {
        Write-Host "[ERROR] cloud-backend jar 不存在，请先构建" -ForegroundColor Red
        exit 1
    }
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
    $droneSimJar = "$Root\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar"
    if (-not (Test-Path $droneSimJar)) {
        Write-Host "[ERROR] drone-sim jar 不存在，请先构建" -ForegroundColor Red
        exit 1
    }
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
    if (-not $allRegistered) {
        $drones = ApiGet '/drones'
        Info "当前注册数量：$(if ($drones) { @($drones).Count } else { 0 })"
    }

    # ============================================================
    # 场景1：单机侦察
    # ============================================================
    Banner "场景1：单机侦察"
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

    # ============================================================
    # 场景2：多机协同部署
    # ============================================================
    Banner "场景2：多机协同部署"
    Story "侦察发现异常情况，指挥中心增派两架无人机前往协同作业。"

    $sysid2 = 2
    $sysid3 = 3

    Step "2号机起飞执行巡逻任务"
    $mission2 = @{
        items = @(
            @{ cmd = 'takeoff'; lat = $RefLat - 0.001; lon = $RefLon; alt = $TakeoffAlt; holdTime = 0 },
            @{ cmd = 'waypoint'; lat = $RefLat - 0.002; lon = $RefLon + 0.002; alt = $TakeoffAlt; holdTime = 2 },
            @{ cmd = 'waypoint'; lat = $RefLat - 0.001; lon = $RefLon + 0.003; alt = $TakeoffAlt; holdTime = 2 },
            @{ cmd = 'rtl'; lat = $RefLat - 0.001; lon = $RefLon; alt = 0; holdTime = 0 }
        )
    } | ConvertTo-Json -Depth 5
    $r = ApiPost "/drones/$sysid2/mission" $mission2
    Check "2号机任务上传" ($r -and $r.status -eq 'ok')
    $r = ApiPost "/drones/$sysid2/commands" '{"type":"arm"}'
    Check "2号机 ARM" ($r -and $r.status -eq 'ok')
    $r = ApiPost "/drones/$sysid2/commands" '{"type":"start_mission"}'
    Check "2号机开始任务" ($r -and $r.status -eq 'ok')

    Step "3号机起飞执行监控任务"
    $mission3 = @{
        items = @(
            @{ cmd = 'takeoff'; lat = $RefLat; lon = $RefLon - 0.001; alt = $TakeoffAlt; holdTime = 0 },
            @{ cmd = 'waypoint'; lat = $RefLat + 0.001; lon = $RefLon - 0.002; alt = $TakeoffAlt; holdTime = 2 },
            @{ cmd = 'waypoint'; lat = $RefLat + 0.002; lon = $RefLon - 0.001; alt = $TakeoffAlt; holdTime = 2 },
            @{ cmd = 'rtl'; lat = $RefLat; lon = $RefLon - 0.001; alt = 0; holdTime = 0 }
        )
    } | ConvertTo-Json -Depth 5
    $r = ApiPost "/drones/$sysid3/mission" $mission3
    Check "3号机任务上传" ($r -and $r.status -eq 'ok')
    $r = ApiPost "/drones/$sysid3/commands" '{"type":"arm"}'
    Check "3号机 ARM" ($r -and $r.status -eq 'ok')
    $r = ApiPost "/drones/$sysid3/commands" '{"type":"start_mission"}'
    Check "3号机开始任务" ($r -and $r.status -eq 'ok')

    Step "验证多机同时在线"
    Start-Sleep -Seconds 5
    $drones = ApiGet '/drones'
    if ($drones) {
        $onlineCount = @($drones | Where-Object { $_.online }).Count
        Info "在线数量：$onlineCount / $(@($drones).Count)"
        Check "至少 2 机在线" ($onlineCount -ge 2)
    } else {
        Check "多机在线" $false
    }

    Step "验证多机遥测"
    $tel2 = ApiGet "/drones/$sysid2/telemetry"
    $tel3 = ApiGet "/drones/$sysid3/telemetry"
    if ($tel2 -and $tel3) {
        Info "2号机：alt=$($tel2.relativeAlt) mode=$($tel2.mode)"
        Info "3号机：alt=$($tel3.relativeAlt) mode=$($tel3.mode)"
        Check "2号机遥测有效" ($tel2.lat -ne $null)
        Check "3号机遥测有效" ($tel3.lat -ne $null)
    } else {
        Check "多机遥测" $false
    }

    # ============================================================
    # 场景3：编队包围
    # ============================================================
    Banner "场景3：编队包围"
    Story "三架无人机组队形成编队，执行队形变换和灯光同步演示。"

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
            $r = ApiPost "/formation/$formationId/command" '{"type":"DISSOLVE"}'
            Check "编队解散" ($r -and $r.status -eq 'ok')
        }
    }

    # ============================================================
    # 场景4：应急响应
    # ============================================================
    Banner "场景4：应急响应"
    Story "一架无人机突然失联，系统检测到链路中断，触发自动返航保护。"

    Step "1号机执行 RTL（模拟应急返航）"
    $r = ApiPost "/drones/$sysid1/commands" '{"type":"rtl"}'
    Check "1号机 RTL" ($r -and $r.status -eq 'ok')

    Step "验证告警生成"
    Start-Sleep -Seconds 3
    $flightLog = ApiGet "/flightlog?sysid=$sysid1&limit=50"
    if ($flightLog) {
        $logCount = @($flightLog).Count
        Info "飞行日志条数：$logCount"
        Check "飞行日志记录" ($logCount -gt 0)
    } else {
        Check "飞行日志记录" $false
    }

    Step "2号机和3号机继续执行任务"
    $tel2 = ApiGet "/drones/$sysid2/telemetry"
    $tel3 = ApiGet "/drones/$sysid3/telemetry"
    if ($tel2) {
        Info "2号机状态：mode=$($tel2.mode) alt=$($tel2.relativeAlt)"
        Check "2号机仍在线" ($tel2.lat -ne $null)
    }
    if ($tel3) {
        Info "3号机状态：mode=$($tel3.mode) alt=$($tel3.relativeAlt)"
        Check "3号机仍在线" ($tel3.lat -ne $null)
    }

    Step "2号机也执行 RTL"
    $r = ApiPost "/drones/$sysid2/commands" '{"type":"rtl"}'
    Check "2号机 RTL" ($r -and $r.status -eq 'ok')

    Step "3号机执行 RTL"
    $r = ApiPost "/drones/$sysid3/commands" '{"type":"rtl"}'
    Check "3号机 RTL" ($r -and $r.status -eq 'ok')

    # ============================================================
    # 场景5：追踪恢复
    # ============================================================
    Banner "场景5：追踪恢复"
    Story "系统查询失联无人机的历史轨迹，为搜索提供引导数据。"

    Step "查询1号机历史轨迹"
    $track1 = ApiGet "/drones/$sysid1/track"
    if ($track1) {
        $trackCount = @($track1).Count
        Info "1号机轨迹点数：$trackCount"
        Check "1号机轨迹存在" ($trackCount -gt 1)
        if ($trackCount -gt 0) {
            $lastPoint = $track1[-1]
            Info "最后位置：lat=$($lastPoint.lat) lon=$($lastPoint.lon)"
        }
    } else {
        Check "1号机轨迹" $false
    }

    Step "查询2号机历史轨迹"
    $track2 = ApiGet "/drones/$sysid2/track"
    if ($track2) {
        $trackCount = @($track2).Count
        Info "2号机轨迹点数：$trackCount"
        Check "2号机轨迹存在" ($trackCount -gt 1)
    } else {
        Check "2号机轨迹" $false
    }

    Step "查询3号机历史轨迹"
    $track3 = ApiGet "/drones/$sysid3/track"
    if ($track3) {
        $trackCount = @($track3).Count
        Info "3号机轨迹点数：$trackCount"
        Check "3号机轨迹存在" ($trackCount -gt 1)
    } else {
        Check "3号机轨迹" $false
    }

    Step "查询飞行日志（全机队）"
    $flightLog = ApiGet "/flightlog?limit=100"
    if ($flightLog) {
        $logCount = @($flightLog).Count
        Info "飞行日志总条数：$logCount"
        Check "飞行日志记录丰富" ($logCount -ge 3)
    } else {
        Check "飞行日志" $false
    }

    # ============================================================
    # 总结
    # ============================================================
    Banner "演示总结"
    if ($Fail -eq 0) {
        Write-Host "  所有场景验证通过！" -ForegroundColor Green
        Write-Host "  NexusSky 系统完整功能链路演示成功。" -ForegroundColor Green
    } else {
        Write-Host "  部分场景验证失败，请检查日志。" -ForegroundColor Red
    }
    Write-Host ""
    Write-Host "  演示覆盖：" -ForegroundColor Cyan
    Write-Host "    - 单机侦察飞行（任务上传→ARM→航线飞行→RTL→轨迹记录）"
    Write-Host "    - 多机协同部署（3机同时在线→各自独立任务→遥测并行）"
    Write-Host "    - 编队包围（创建→起飞→队形变换→灯光同步→解散）"
    Write-Host "    - 应急响应（RTL保护→飞行日志→剩余机继续任务）"
    Write-Host "    - 追踪恢复（历史轨迹查询→飞行日志全量审计）"

} finally {
    Cleanup
}

if ($Fail -eq 0) {
    Write-Host "`n[SUCCESS] NexusSky 综合演示全部通过" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`n[FAILURE] NexusSky 综合演示存在失败项" -ForegroundColor Red
    exit 1
}
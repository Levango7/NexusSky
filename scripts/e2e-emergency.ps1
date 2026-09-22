# 空地一体化应急指挥端到端演示（PowerShell 版）
#
# 参照 e2e-emergency.sh 的 9 步流程，使用 PowerShell 原生语法。
# 自启动 cloud-backend（检测端口 8080 是否已占用，未占用则启动）。
#
# 9 步流程：
#   1. 布控球自动发现 + 一键注册
#   2. 查看已注册设备
#   3. 创建报警联动规则
#   4. 模拟报警事件（触发联动）
#   5. 查看报警事件列表
#   6. 确认报警
#   7. 创建应急指挥命令（接报）
#   8. 一键应急响应
#   9. 查看应急指挥历史
#
# 所有 API 路径使用 /api/v1/ 前缀。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-emergency.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-emergency.ps1 -SkipBuild
#
# 前置：JDK 17 + Maven（自动构建缺失 jar）

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$Script:CleanupActions = @()
$Script:StartedProcesses = @()

# ---- 端口配置 ----
$BackendRestPort = 8080

# ---- 演示场景常量 ----
$SUBNET = '192.168.1.0/24'
$ONVIF_USER = 'admin'
$ONVIF_PASS = 'admin123'
$EVENT_LAT = 39.9
$EVENT_LON = 116.4

# ---- 超时配置 ----
$BackendStartTimeoutSec = 60
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
    Write-Host "需要 Java >= 17，请设置 JAVA_HOME 或 AF_JAVA 环境变量" -ForegroundColor Red
    exit 1
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
trap { Invoke-Cleanup; break }

# =====================================================================
# 等待后端就绪
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
# REST 调用辅助
# =====================================================================
function Invoke-PostJson($url, $bodyObj) {
    $json = $bodyObj | ConvertTo-Json -Depth 6
    return Invoke-RestMethod -Uri $url -Method Post -Body $json -ContentType 'application/json' -TimeoutSec 10
}

function Invoke-GetJson($url) {
    return Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 10
}

# =====================================================================
# 前置检查
# =====================================================================
Step '前置检查：jar 存在 + Java >= 17'

$backendJar = Join-Path $Root 'cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'

if (-not (Test-Path $backendJar)) {
    if ($SkipBuild) {
        Info "cloud-backend jar 不存在: $backendJar"
        Info '前置条件缺失：请先用 JDK17 构建 (mvn -pl cloud-backend -am package -DskipTests)'
        Write-Host "`nEMERGENCY E2E FAILED（前置条件缺失：jar 不存在）" -ForegroundColor Red
        exit 1
    }
    Info 'cloud-backend jar 不存在，自动构建...'
    & mvn -q -DskipTests -f "$Root\pom.xml" -pl cloud-backend -am package 2>&1 | ForEach-Object { Info "$_" }
    if (-not (Test-Path $backendJar)) {
        Check 'cloud-backend jar 构建成功' $false
        Write-Host "`nEMERGENCY E2E FAILED（构建失败）" -ForegroundColor Red
        exit 1
    }
}
Check 'cloud-backend jar 存在' (Test-Path $backendJar)

$java = Assert-Java17
Info "Java: $java"

# =====================================================================
# 自启动 cloud-backend（检测端口 8080 是否已占用）
# =====================================================================
Step '自启动检测：cloud-backend 端口 8080'

$backendRunning = $false
try {
    $r = Invoke-WebRequest -Uri "http://localhost:$BackendRestPort/actuator/health" -TimeoutSec 2 -UseBasicParsing
    $backendRunning = $r.StatusCode -eq 200
} catch { }

$backendStartedByScript = $false
if (-not $backendRunning) {
    Info 'cloud-backend 未运行，自动启动...'
    $backendLog = Join-Path $env:TEMP "emergency-backend.log"
    if (Test-Path $backendLog) { Remove-Item $backendLog -Force }

    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $backendJar,
        '-Demergency-e2e=true'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog
    Register-Cleanup { Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue }
    Info "backend PID=$($backend.Id), log=$backendLog"
    $backendStartedByScript = $true

    $up = Wait-BackendReady $BackendStartTimeoutSec
    Check 'cloud-backend 启动成功' $up
    if (-not $up) {
        Info '后端启动失败，查看日志:'
        if (Test-Path $backendLog) { Get-Content $backendLog -Tail 30 | ForEach-Object { Info $_ } }
        Write-Host "`nEMERGENCY E2E FAILED（后端启动失败）" -ForegroundColor Red
        Invoke-Cleanup
        exit 1
    }
} else {
    Info 'cloud-backend 已在运行（端口 8080 可达），复用既有后端'
}

# =====================================================================
# 0. 前置：后端可达
# =====================================================================
Step "前置：后端可达 http://localhost:$BackendRestPort"
try {
    $r = Invoke-GetJson "$Base/surveillance/devices"
    Info '后端可达'
    Check '后端可达' $true
} catch {
    Check '后端可达' $false
    Write-Host "`nEMERGENCY E2E FAILED（后端不可达）" -ForegroundColor Red
    Invoke-Cleanup
    exit 1
}

# =====================================================================
# 1. 布控球自动发现 + 一键注册（rapid-deploy）
# =====================================================================
Step '1/9 布控球自动发现 + 一键注册（POST /api/v1/surveillance/rapid-deploy）'
Info "子网: $SUBNET  用户: $ONVIF_USER"

$deployBody = @{ subnet = $SUBNET; username = $ONVIF_USER; password = $ONVIF_PASS }
try {
    $deployResp = Invoke-PostJson "$Base/surveillance/rapid-deploy" $deployBody
    Info "响应: $($deployResp | ConvertTo-Json -Compress -Depth 4)"
    $deployCount = $deployResp.count
    Check '发现并注册设备数 >= 1' ($deployCount -ge 1)
} catch {
    Info "请求失败: $_"
    Check '布控球快速部署' $false
    $deployCount = 0
}

# =====================================================================
# 2. 查看已注册设备
# =====================================================================
Step '2/9 查看已注册设备（GET /api/v1/surveillance/devices）'
try {
    $devResp = Invoke-GetJson "$Base/surveillance/devices"
    Info "响应: $($devResp | ConvertTo-Json -Compress -Depth 4)"
    $devCount = $devResp.count
    Check '已注册设备数 >= 1' ($devCount -ge 1)
    # 取第一个设备 ID，用于后续报警事件来源
    if ($devResp.devices -and $devResp.devices.Count -gt 0) {
        $firstDeviceId = $devResp.devices[0].id
    } else {
        $firstDeviceId = 'cam-1-xxx'
    }
    Info "首个设备 ID: $firstDeviceId"
} catch {
    Info "请求失败: $_"
    Check '查询设备列表' $false
    $firstDeviceId = 'cam-1-xxx'
}

# =====================================================================
# 3. 创建报警联动规则
# =====================================================================
Step '3/9 创建报警联动规则（POST /api/v1/alarms/rules）'
Info '规则: 火灾自动侦察 — FIRE+CRITICAL → DEPLOY_DRONE(2架, 半径100m, 高度60m)'

$ruleBody = @{
    name = '火灾自动侦察'
    matchEventType = 'FIRE'
    matchSeverity = 'CRITICAL'
    actionType = 'DEPLOY_DRONE'
    droneCount = 2
    targetRadiusM = 100
    altitudeM = 60
}
$ruleId = $null
try {
    $ruleResp = Invoke-PostJson "$Base/alarms/rules" $ruleBody
    Info "响应: $($ruleResp | ConvertTo-Json -Compress -Depth 4)"
    $ruleId = $ruleResp.ruleId
    Check '返回 ruleId 非空' ($ruleId -ne $null -and $ruleId -ne '')
} catch {
    Info "请求失败: $_"
    Check '创建联动规则' $false
}
Info "规则 ID: $ruleId"

# =====================================================================
# 4. 模拟报警事件（触发联动）
# =====================================================================
Step '4/9 模拟报警事件（POST /api/v1/alarms/events）— 触发联动规则'
Info "来源设备: $firstDeviceId  位置: ($EVENT_LAT, $EVENT_LON)"

$eventBody = @{
    sourceDeviceId = $firstDeviceId
    eventType = 'FIRE'
    severity = 'CRITICAL'
    lat = $EVENT_LAT
    lon = $EVENT_LON
    description = '布控球检测到火情'
}
$eventId = $null
$matchedCount = 0
try {
    $eventResp = Invoke-PostJson "$Base/alarms/events" $eventBody
    Info "响应（含联动触发结果）: $($eventResp | ConvertTo-Json -Compress -Depth 4)"
    $eventId = $eventResp.eventId
    $matchedCount = $eventResp.matchedCount
    Check '返回 eventId 非空' ($eventId -ne $null -and $eventId -ne '')
    Check '联动规则匹配数 >= 1' ($matchedCount -ge 1)
} catch {
    Info "请求失败: $_"
    Check '接收报警事件' $false
}
Info "事件 ID: $eventId  匹配规则数: $matchedCount"

# =====================================================================
# 5. 查看报警事件列表
# =====================================================================
Step '5/9 查看报警事件列表（GET /api/v1/alarms/events）'
try {
    $eventsResp = Invoke-GetJson "$Base/alarms/events"
    Info "响应: $($eventsResp | ConvertTo-Json -Compress -Depth 4)"
    $eventTotal = $eventsResp.total
    Check '事件列表总数 >= 1' ($eventTotal -ge 1)
} catch {
    Info "请求失败: $_"
    Check '查询报警事件列表' $false
}

# =====================================================================
# 6. 确认报警
# =====================================================================
Step "6/9 确认报警（POST /api/v1/alarms/events/$eventId/ack）"
Info "事件 ID: $eventId"
if ($eventId) {
    try {
        $ackResp = Invoke-PostJson "$Base/alarms/events/$eventId/ack" @{}
        Info "响应: $($ackResp | ConvertTo-Json -Compress -Depth 4)"
        $acked = $ackResp.acknowledged
        Check 'acknowledged == true' ($acked -eq $true)
    } catch {
        Info "请求失败: $_"
        Check '确认报警' $false
    }
} else {
    Check '确认报警（eventId 为空）' $false
}

# =====================================================================
# 7. 创建应急指挥命令（接报）
# =====================================================================
Step '7/9 创建应急指挥命令（POST /api/v1/emergency-command）— 接报'
Info "事件类型: 火灾  严重程度: CRITICAL  位置: ($EVENT_LAT, $EVENT_LON)"

$cmdBody = @{
    incidentType = '火灾'
    severity = 'CRITICAL'
    lat = $EVENT_LAT
    lon = $EVENT_LON
    description = '布控球火情侦察'
    reporterName = 'e2e-demo'
    reporterContact = '110'
}
$commandId = $null
$cmdPhase = $null
try {
    $cmdResp = Invoke-PostJson "$Base/emergency-command" $cmdBody
    Info "响应: $($cmdResp | ConvertTo-Json -Compress -Depth 4)"
    $commandId = $cmdResp.id
    $cmdPhase = $cmdResp.currentPhase
    Check '返回 commandId 非空' ($commandId -ne $null -and $commandId -ne '')
} catch {
    Info "请求失败: $_"
    Check '创建应急指挥命令' $false
}
Info "命令 ID: $commandId  当前阶段: $cmdPhase"

# =====================================================================
# 8. 一键应急响应
# =====================================================================
Step "8/9 一键应急响应（POST /api/v1/emergency-command/$commandId/one-click）"
Info "命令 ID: $commandId"
Info '预期阶段流转: 接报 → 研判 → 部署 → 执行 → 评估 → 总结'
if ($commandId) {
    try {
        $oneClickResp = Invoke-PostJson "$Base/emergency-command/$commandId/one-click" @{}
        Info "响应: $($oneClickResp | ConvertTo-Json -Compress -Depth 4)"
        $finalPhase = $oneClickResp.currentPhase
        $phaseHistSize = $oneClickResp.phaseHistorySize
        Check '最终阶段为 CLOSED/SUMMARY/总结' ($finalPhase -match 'CLOSED|SUMMARY|总结')
        Check '阶段转移历史 >= 4 步' ($phaseHistSize -ge 4)
        Info "最终阶段: $finalPhase  转移步数: $phaseHistSize"
    } catch {
        Info "请求失败: $_"
        Check '一键应急响应' $false
    }
} else {
    Check '一键应急响应（commandId 为空）' $false
}

# =====================================================================
# 9. 查看应急指挥历史
# =====================================================================
Step '9/9 查看应急指挥历史（GET /api/v1/emergency-command）'
try {
    $historyResp = Invoke-GetJson "$Base/emergency-command"
    Info "响应: $($historyResp | ConvertTo-Json -Compress -Depth 4)"
    $cmdTotal = $historyResp.total
    Check '历史命令数 >= 1' ($cmdTotal -ge 1)
} catch {
    Info "请求失败: $_"
    Check '查询应急指挥历史' $false
}

# =====================================================================
# 演示总结
# =====================================================================
Step '演示总结'
Info "后端地址:        http://localhost:$BackendRestPort"
Info "布控球子网:      $SUBNET"
Info "注册设备数:      $deployCount"
Info "联动规则 ID:     $(if ($ruleId) { $ruleId } else { 'N/A' })"
Info "报警事件 ID:     $(if ($eventId) { $eventId } else { 'N/A' })  (匹配规则 $matchedCount 条)"
Info "应急命令 ID:     $(if ($commandId) { $commandId } else { 'N/A' })"
Info "命令最终阶段:    $(if ($finalPhase) { $finalPhase } else { 'N/A' })  (转移 $(if ($phaseHistSize) { $phaseHistSize } else { 0 }) 步)"
Info "历史命令数:      $(if ($cmdTotal) { $cmdTotal } else { 0 })"

# 清理脚本启动的进程
if ($backendStartedByScript) {
    Invoke-Cleanup
    Info '已停止脚本启动的 cloud-backend'
}

Write-Host ""
if ($Fail -eq 0) {
    Write-Host 'PASS: 空地一体化应急指挥端到端演示全部通过' -ForegroundColor Green
    Write-Host '  完整流程: 布控球自动发现 → 报警事件触发 → 联动规则匹配 → 应急指挥工作流(一键响应)' -ForegroundColor DarkGray
    exit 0
} else {
    Write-Host 'FAIL: 空地一体化应急指挥端到端演示存在失败步骤（见上方 FAIL 标记）' -ForegroundColor Red
    exit 1
}
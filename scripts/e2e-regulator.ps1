# 监管合规端到端验证脚本（C1-T16）
#
# 依据：design.md §监管合规 + spec.md FR-31~FR-35
# 场景：regulator-sim + cloud-backend + drone-sim 全链路监管合规验证
#
# 断言目标（FR-31~35）：
#   FR-31 实名验证全链路（POST /verify → VERIFIED → GET /status → VERIFIED）
#   FR-32 激活上报全链路（POST /activate → activationId → GET /status → ACTIVATED）
#   FR-33 遥测上报全链路（等待 ≤2 周期 → GET /api/records?type=telemetry → 包含 sysid）
#   FR-34 注销全链路（POST /cancel → cancellationId → GET /status → CANCELLED → 等待 2 周期 → 无新遥测记录）
#   FR-35 regulator-sim 不可达时降级（停止 regulator-sim → POST /verify → ERROR）
#
# 前置检查：regulator-sim / cloud-backend / drone-sim jar 存在、端口可用
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-regulator.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-regulator.ps1 -SkipBuild
#
# 前置：JDK 17 + Maven（自动构建缺失 jar）

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

# ---- 端口配置 ----
$RegulatorPort = 18080
$BackendPort = 8080
$DronePort = 14555
$DroneSysid = 2

# ---- 测试数据（regulator-sim 预置数据）----
$TestSerialNo = 'TEST-001'    # 预置状态 VERIFIED（张三）
$TestCertNo = 'CERT-001'
$TestSerialNoUnverified = 'TEST-002'  # 预置状态 UNVERIFIED

# ---- 遥测上报周期（秒）----
# cloud-backend RegulatorConfig.telemetryReportInterval 默认 60s
# 为加速 e2e 测试，通过命令行参数覆盖为 10s（最小允许值）
$TelemetryIntervalSec = 10

# ---- REST 基址 ----
$BackendBase = "http://localhost:$BackendPort/api/v1/regulator"
$RegulatorBase = "http://localhost:$RegulatorPort"

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
function Send-PostJson($url, $body) {
    $json = $body | ConvertTo-Json -Depth 10 -Compress
    $resp = Invoke-WebRequest -Uri $url -Method Post -Body $json `
        -ContentType 'application/json; charset=UTF-8' -TimeoutSec 30 -UseBasicParsing
    return $resp.Content | ConvertFrom-Json
}

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
        if ($_.CommandLine -match 'regulator-sim' `
            -or $_.CommandLine -match 'cloud-backend.*regulator-e2e' `
            -or $_.CommandLine -match 'drone-sim.*regulator-e2e') {
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

    # 设置 JDK17
    $jdk17Home = 'E:\dev-tools\jdk17.0.20_8'
    if (Test-Path $jdk17Home) {
        $env:JAVA_HOME = $jdk17Home
        Info "JAVA_HOME 设置为 $jdk17Home"
    }

    Step '构建缺失的 jar（JDK17）'
    foreach ($module in @('regulator-sim', 'cloud-backend', 'drone-sim')) {
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
else { $java = "java.exe" }
Assert-Java17 $java
Info "Java: $java"

# ---- 前置检查：jar 存在 ----
Step '前置检查（jar 存在、端口可用）'

$regulatorJar = Get-ChildItem (Join-Path $Root 'regulator-sim\target\*regulator-sim*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
$backendJar = Get-ChildItem (Join-Path $Root 'cloud-backend\target\*cloud-backend*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
$droneSimJar = Get-ChildItem (Join-Path $Root 'drone-sim\target\*drone-sim*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1

$regulatorOk = $null -ne $regulatorJar
$backendOk = $null -ne $backendJar
$droneSimOk = $null -ne $droneSimJar

Check 'regulator-sim jar 存在' $regulatorOk
Check 'cloud-backend jar 存在' $backendOk
Check 'drone-sim jar 存在' $droneSimOk

if (-not ($regulatorOk -and $backendOk -and $droneSimOk)) {
    if ($SkipBuild) {
        Info '前置条件缺失：jar 不存在且 -SkipBuild 已指定，无法继续'
        Write-Host '`nREGULATOR E2E FAILED（前置条件缺失）' -ForegroundColor Red
        exit 1
    }
    Info '部分 jar 缺失，自动构建...'
    Build-Jars
    # 重新检测
    $regulatorJar = Get-ChildItem (Join-Path $Root 'regulator-sim\target\*regulator-sim*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
    $backendJar = Get-ChildItem (Join-Path $Root 'cloud-backend\target\*cloud-backend*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
    $droneSimJar = Get-ChildItem (Join-Path $Root 'drone-sim\target\*drone-sim*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
    $regulatorOk = $null -ne $regulatorJar
    $backendOk = $null -ne $backendJar
    $droneSimOk = $null -ne $droneSimJar
    Check 'regulator-sim jar 构建后存在' $regulatorOk
    Check 'cloud-backend jar 构建后存在' $backendOk
    Check 'drone-sim jar 构建后存在' $droneSimOk
    if (-not ($regulatorOk -and $backendOk -and $droneSimOk)) {
        Write-Host '`nREGULATOR E2E FAILED（jar 构建失败）' -ForegroundColor Red
        exit 1
    }
}

Info "regulator-sim jar: $($regulatorJar.FullName)"
Info "cloud-backend jar: $($backendJar.FullName)"
Info "drone-sim jar: $($droneSimJar.FullName)"

# ---- 端口可用性 ----
$portRegulatorFree = Test-PortFree $RegulatorPort
$portBackendFree = Test-PortFree $BackendPort
$portDroneFree = Test-PortFree $DronePort
Check "regulator-sim 端口 $RegulatorPort 可用" $portRegulatorFree
Check "cloud-backend 端口 $BackendPort 可用" $portBackendFree
Check "drone-sim 端口 $DronePort 可用" $portDroneFree

if (-not ($portRegulatorFree -and $portBackendFree -and $portDroneFree)) {
    Info '端口被占用，尝试清理旧进程...'
    Cleanup-OldProcesses
    Start-Sleep 2
    $portRegulatorFree = Test-PortFree $RegulatorPort
    $portBackendFree = Test-PortFree $BackendPort
    $portDroneFree = Test-PortFree $DronePort
    if (-not ($portRegulatorFree -and $portBackendFree -and $portDroneFree)) {
        Info '端口仍被占用，请手动清理后重跑'
        Write-Host '`nREGULATOR E2E FAILED（端口占用）' -ForegroundColor Red
        exit 1
    }
}

Cleanup-OldProcesses

# ============================================================
# 1. 启动 regulator-sim（--port 18080）
# ============================================================
Step '启动 regulator-sim（--port 18080）'

$regulatorLog = Join-Path $env:TEMP "regulator-e2e-sim.log"
if (Test-Path $regulatorLog) { Remove-Item $regulatorLog -Force }

$regulatorProc = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $regulatorJar.FullName,
    '--port', $RegulatorPort
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $regulatorLog
Register-Cleanup { Stop-Process -Id $regulatorProc.Id -Force -ErrorAction SilentlyContinue }
Info "regulator-sim PID=$($regulatorProc.Id), log=$regulatorLog"

# 等待 regulator-sim 启动
$regulatorUp = $false
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep 1
    try {
        $r = Invoke-WebRequest -Uri "$RegulatorBase/api/records?type=telemetry" -TimeoutSec 2 -UseBasicParsing
        if ($r.StatusCode -eq 200) { $regulatorUp = $true; break }
    } catch { }
}
Check 'regulator-sim 启动成功' $regulatorUp
if (-not $regulatorUp) {
    Info 'regulator-sim 启动失败，查看日志:'
    if (Test-Path $regulatorLog) { Get-Content $regulatorLog -Tail 20 | ForEach-Object { Info $_ } }
    Write-Host '`nREGULATOR E2E FAILED（regulator-sim 启动失败）' -ForegroundColor Red
    Invoke-Cleanup
    exit 1
}

# ============================================================
# 2. 启动 cloud-backend（sink-type=sim）
# ============================================================
Step '启动 cloud-backend（sink-type=sim, dev profile）'

$backendLog = Join-Path $env:TEMP "regulator-e2e-backend.log"
if (Test-Path $backendLog) { Remove-Item $backendLog -Force }

$backendProc = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $backendJar.FullName,
    '--spring.profiles.active=dev',
    "--aerofleet.regulator.sink-type=sim",
    "--aerofleet.regulator.sim-base-url=http://localhost:$RegulatorPort",
    "--aerofleet.regulator.telemetry-report-interval=$TelemetryIntervalSec",
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
    Write-Host '`nREGULATOR E2E FAILED（cloud-backend 启动失败）' -ForegroundColor Red
    Invoke-Cleanup
    exit 1
}

# ============================================================
# 3. 启动 drone-sim（发送 MAVLink 遥测帧）
# ============================================================
Step "启动 drone-sim（sysid=$DroneSysid, port=$DronePort）"

$droneLog = Join-Path $env:TEMP "regulator-e2e-drone.log"
if (Test-Path $droneLog) { Remove-Item $droneLog -Force }

$droneProc = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $droneSimJar.FullName,
    '--port', $DronePort,
    '--sysid', $DroneSysid,
    '--name', 'AF-REG-02'
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog
Register-Cleanup { Stop-Process -Id $droneProc.Id -Force -ErrorAction SilentlyContinue }
Info "drone-sim PID=$($droneProc.Id), log=$droneLog"
Start-Sleep 2

$droneAlive = -not $droneProc.HasExited
Check 'drone-sim 启动成功' $droneAlive
if (-not $droneAlive) {
    Info 'drone-sim 启动失败，查看日志:'
    if (Test-Path $droneLog) { Get-Content $droneLog -Tail 20 | ForEach-Object { Info $_ } }
    Write-Host '`nREGULATOR E2E FAILED（drone-sim 启动失败）' -ForegroundColor Red
    Invoke-Cleanup
    exit 1
}

# 等待 drone-sim 被后端发现（后端通过 UDP 心跳发现 drone-sim）
Info '等待 drone-sim 被 cloud-backend 发现...'
Start-Sleep 5

# ============================================================
# FR-31：实名验证全链路
# POST /api/v1/regulator/verify → verifyStatus=VERIFIED
# GET /api/v1/regulator/status/{sysid} → status=VERIFIED
# ============================================================
Step 'FR-31：实名验证全链路（POST /verify → VERIFIED → GET /status → VERIFIED）'

$verifyBody = @{
    sysid = $DroneSysid
    productSerialNo = $TestSerialNo
    realNameCertNo = $TestCertNo
}
Info "POST /verify body: $($verifyBody | ConvertTo-Json -Compress)"

try {
    $verifyResp = Send-PostJson "$BackendBase/verify" $verifyBody
    Info "verify 响应: $($verifyResp | ConvertTo-Json -Compress)"
    $verifyStatusOk = $verifyResp.verifyStatus -eq 'VERIFIED'
    Check 'FR-31: POST /verify 返回 verifyStatus=VERIFIED' $verifyStatusOk
} catch {
    Info "verify 请求异常: $_"
    Check 'FR-31: POST /verify 返回 verifyStatus=VERIFIED' $false
    $verifyResp = $null
}

# 查询合规状态
try {
    $statusResp = Send-GetJson "$BackendBase/status/$DroneSysid"
    Info "status 响应: $($statusResp | ConvertTo-Json -Compress)"
    $statusVerified = $statusResp.status -eq 'VERIFIED'
    Check 'FR-31: GET /status/{sysid} 返回 status=VERIFIED' $statusVerified
} catch {
    Info "status 查询异常: $_"
    Check 'FR-31: GET /status/{sysid} 返回 status=VERIFIED' $false
}

# ============================================================
# FR-32：激活上报全链路
# POST /api/v1/regulator/activate → success=true, activationId 非空
# GET /api/v1/regulator/status/{sysid} → status=ACTIVATED
# ============================================================
Step 'FR-32：激活上报全链路（POST /activate → activationId → GET /status → ACTIVATED）'

$activateBody = @{
    sysid = $DroneSysid
    productSerialNo = $TestSerialNo
    activationTime = 0
    latitude = 22.5907
    longitude = 113.9345
}
Info "POST /activate body: $($activateBody | ConvertTo-Json -Compress)"

try {
    $activateResp = Send-PostJson "$BackendBase/activate" $activateBody
    Info "activate 响应: $($activateResp | ConvertTo-Json -Compress)"
    $activateSuccess = $activateResp.success -eq $true
    $activationIdPresent = $null -ne $activateResp.activationId -and $activateResp.activationId -ne ''
    Check 'FR-32: POST /activate 返回 success=true' $activateSuccess
    Check 'FR-32: POST /activate 返回 activationId 非空' $activationIdPresent
} catch {
    Info "activate 请求异常: $_"
    Check 'FR-32: POST /activate 返回 success=true' $false
    Check 'FR-32: POST /activate 返回 activationId 非空' $false
    $activateResp = $null
}

# 查询合规状态
try {
    $statusResp = Send-GetJson "$BackendBase/status/$DroneSysid"
    Info "status 响应: $($statusResp | ConvertTo-Json -Compress)"
    $statusActivated = $statusResp.status -eq 'ACTIVATED'
    Check 'FR-32: GET /status/{sysid} 返回 status=ACTIVATED' $statusActivated
} catch {
    Info "status 查询异常: $_"
    Check 'FR-32: GET /status/{sysid} 返回 status=ACTIVATED' $false
}

# ============================================================
# FR-33：遥测上报全链路
# 等待 ≤2 个遥测上报周期 → GET /api/records?type=telemetry → 包含 sysid
# ============================================================
Step "FR-33：遥测上报全链路（等待 ≤2 周期[${TelemetryIntervalSec}s × 2] → records 包含 sysid）"

# 遥测上报由 cloud-backend TelemetryReportService 定时执行
# drone-sim 发送 MAVLink 遥测帧到后端 UDP 端口，后端接收后存入快照
# 定时任务按周期从快照提取数据并 POST /api/telemetry 到 regulator-sim
# 等待 2 个周期确保至少一次上报发生
$waitSec = $TelemetryIntervalSec * 2 + 5  # 2 周期 + 5s 余量
Info "等待 ${waitSec}s 让遥测上报至少执行一次..."
Start-Sleep $waitSec

# 查询 regulator-sim 的遥测记录
$telemetryRecordsFound = $false
$telemetryContainsSysid = $false
try {
    $recordsResp = Send-GetJson "$RegulatorBase/api/records?type=telemetry"
    $recordCount = @($recordsResp).Count
    Info "regulator-sim telemetry records 数量: $recordCount"
    if ($recordCount -gt 0) {
        $telemetryRecordsFound = $true
        # 检查记录中是否包含我们的 sysid
        foreach ($record in $recordsResp) {
            $recordData = $record.data
            if ($recordData.sysid -eq $DroneSysid) {
                $telemetryContainsSysid = $true
                Info "找到 sysid=$DroneSysid 的遥测记录: $($record | ConvertTo-Json -Compress)"
                break
            }
        }
    }
} catch {
    Info "records 查询异常: $_"
}

Check 'FR-33: regulator-sim 有遥测记录' $telemetryRecordsFound
Check "FR-33: 遥测记录包含 sysid=$DroneSysid" $telemetryContainsSysid

# ============================================================
# FR-34：注销全链路
# POST /api/v1/regulator/cancel → success=true, cancellationId 非空
# GET /api/v1/regulator/status/{sysid} → status=CANCELLED
# 等待 2 周期 → 无新遥测记录
# ============================================================
Step 'FR-34：注销全链路（POST /cancel → cancellationId → CANCELLED → 无新遥测）'

# 记录注销前的遥测记录数
$telemetryCountBeforeCancel = 0
try {
    $recordsBefore = Send-GetJson "$RegulatorBase/api/records?type=telemetry"
    $telemetryCountBeforeCancel = @($recordsBefore).Count
    Info "注销前遥测记录数: $telemetryCountBeforeCancel"
} catch { }

$cancelBody = @{
    sysid = $DroneSysid
    productSerialNo = $TestSerialNo
    cancellationReason = 'e2e-test-cancellation'
}
Info "POST /cancel body: $($cancelBody | ConvertTo-Json -Compress)"

try {
    $cancelResp = Send-PostJson "$BackendBase/cancel" $cancelBody
    Info "cancel 响应: $($cancelResp | ConvertTo-Json -Compress)"
    $cancelSuccess = $cancelResp.success -eq $true
    $cancellationIdPresent = $null -ne $cancelResp.cancellationId -and $cancelResp.cancellationId -ne ''
    Check 'FR-34: POST /cancel 返回 success=true' $cancelSuccess
    Check 'FR-34: POST /cancel 返回 cancellationId 非空' $cancellationIdPresent
} catch {
    Info "cancel 请求异常: $_"
    Check 'FR-34: POST /cancel 返回 success=true' $false
    Check 'FR-34: POST /cancel 返回 cancellationId 非空' $false
}

# 查询合规状态
try {
    $statusResp = Send-GetJson "$BackendBase/status/$DroneSysid"
    Info "status 响应: $($statusResp | ConvertTo-Json -Compress)"
    $statusCancelled = $statusResp.status -eq 'CANCELLED'
    Check 'FR-34: GET /status/{sysid} 返回 status=CANCELLED' $statusCancelled
} catch {
    Info "status 查询异常: $_"
    Check 'FR-34: GET /status/{sysid} 返回 status=CANCELLED' $false
}

# 等待 2 个遥测上报周期，确认无新遥测记录
$cancelWaitSec = $TelemetryIntervalSec * 2 + 5
Info "注销后等待 ${cancelWaitSec}s 确认无新遥测记录..."
Start-Sleep $cancelWaitSec

$telemetryCountAfterCancel = 0
try {
    $recordsAfter = Send-GetJson "$RegulatorBase/api/records?type=telemetry"
    $telemetryCountAfterCancel = @($recordsAfter).Count
    Info "注销后遥测记录数: $telemetryCountAfterCancel（注销前: $telemetryCountBeforeCancel）"
} catch { }

$noNewTelemetry = $telemetryCountAfterCancel -eq $telemetryCountBeforeCancel
Check 'FR-34: 注销后无新遥测记录（上报已停止）' $noNewTelemetry

# ============================================================
# FR-35：regulator-sim 不可达时降级
# 停止 regulator-sim → POST /verify → verifyStatus=ERROR
# ============================================================
Step 'FR-35：regulator-sim 不可达时降级（停止 regulator-sim → POST /verify → ERROR）'

# 停止 regulator-sim
Info "停止 regulator-sim（PID=$($regulatorProc.Id)）..."
Stop-Process -Id $regulatorProc.Id -Force -ErrorAction SilentlyContinue
# 从清理列表中移除（已手动停止）
$script:CleanupActions = $script:CleanupActions | Where-Object { $_ -notmatch "regulatorProc" }
Start-Sleep 3

# 确认 regulator-sim 已停止
$regulatorStillUp = $false
try {
    $r = Invoke-WebRequest -Uri "$RegulatorBase/api/records?type=telemetry" -TimeoutSec 2 -UseBasicParsing
    if ($r.StatusCode -eq 200) { $regulatorStillUp = $true }
} catch { }
Check 'regulator-sim 已停止' (-not $regulatorStillUp)

# 使用新的 sysid 避免被 CANCELLED 状态拦截（已注销的无人机拒绝所有监管操作）
$degradeSysid = 99
$degradeBody = @{
    sysid = $degradeSysid
    productSerialNo = $TestSerialNo
    realNameCertNo = $TestCertNo
}
Info "POST /verify body（降级测试）: $($degradeBody | ConvertTo-Json -Compress)"

try {
    $degradeResp = Send-PostJson "$BackendBase/verify" $degradeBody
    Info "verify 响应（降级测试）: $($degradeResp | ConvertTo-Json -Compress)"
    $degradeError = $degradeResp.verifyStatus -eq 'ERROR'
    Check 'FR-35: regulator-sim 不可达时 POST /verify 返回 verifyStatus=ERROR' $degradeError
} catch {
    Info "verify 请求异常（降级测试）: $_"
    # HTTP 异常也可能表示降级行为（取决于后端实现）
    Check 'FR-35: regulator-sim 不可达时 POST /verify 返回 verifyStatus=ERROR' $false
}

# ============================================================
# 清理：停止所有进程
# ============================================================
Step '清理：停止所有进程'
Invoke-Cleanup

# 确保所有 java 进程已清理
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.CommandLine -match 'regulator-sim' `
        -or $_.CommandLine -match 'cloud-backend.*regulator-e2e' `
        -or $_.CommandLine -match 'drone-sim.*regulator-e2e') {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

# ============================================================
# 结果
# ============================================================
Write-Host ''
if ($Fail -eq 0) {
    Write-Host 'REGULATOR E2E PASSED（FR-31~35 端到端验证通过）' -ForegroundColor Green
    exit 0
} else {
    Write-Host 'REGULATOR E2E FAILED（部分断言未通过，见上方 FAIL 行）' -ForegroundColor Red
    exit 1
}
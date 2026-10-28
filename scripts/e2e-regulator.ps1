# 监管合规端到端验证脚本（C1-T16）
#
# 依据：design.md §监管合规 + spec.md FR-31~FR-39
# 场景：regulator-sim + cloud-backend + drone-sim 全链路监管合规验证
#
# 断言目标（FR-31~39）：
#   FR-31 实名验证全链路（POST /verify → VERIFIED → GET /status → VERIFIED）
#   FR-32 激活上报全链路（POST /activate → activationId → GET /status → ACTIVATED）
#   FR-33 遥测上报全链路（等待 ≤2 周期 → GET /api/records?type=telemetry → 包含 sysid）
#   FR-34 注销全链路（POST /cancel → cancellationId → GET /status → CANCELLED → 等待 2 周期 → 无新遥测记录）
#   FR-35 regulator-sim 不可达时降级（停止 regulator-sim → POST /verify → ERROR）
#   FR-36 激活幂等：同 serialNo 重复 activate → 返回同一 activationId（打 sim 本体）
#   FR-37 未激活上报拒绝：未 activate 的 serialNo telemetry → 403 NOT_ACTIVATED（打 sim 本体）
#   FR-38 注销后激活拒绝：已注销 serialNo 再 activate → 403 CANCELLED（打 sim 本体）
#   FR-39 reset 生效：POST /api/reset 后状态归零、可重新激活（打 sim 本体）
#   FR-40 4xx 语义终态：--error-status 403 只请求一次、500 仍退避（打 sim 本体）
#
# 前置检查：regulator-sim / cloud-backend / drone-sim jar 存在、端口可用
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-regulator.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-regulator.ps1 -SkipBuild
#   $env:AF_BACKEND_PORT=18099; ...   # 8080 被常驻服务占用时避开（另可选 AF_REGULATOR_PORT / AF_DRONE_PORT）
#
# 前置：JDK 17 + Maven（自动构建缺失 jar）

param(
    [switch]$SkipBuild,
    # 上报通道：sim（SimReportSink，默认）/ uom（UomReportSink——**生产用的真实现**）。
    # 2026-10-28 加：此前全链只跑 sim 通道，真实现只有单测/跨模块契约测试；
    # uom 腿把"后端真的按 sink-type=uom 装起来"这条配置/装配链路也钉进 CI。
    [ValidateSet('sim', 'uom')][string]$SinkType = 'sim'
)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
# 跨平台公共库（B1 批次接 CI 时引入）：java 探测 / jar 路径 / 临时目录 / 按标记清残留
. (Join-Path $PSScriptRoot 'e2e-common.ps1')
$TempDir = Get-E2eTempDir   # Linux runner 上 $env:TEMP 常未设置，不能直接用
$Fail = 0
$script:CleanupActions = @()

# ---- 端口配置 ----
# 本机 8080 可能被常驻服务（Docker 容器等）占用：AF_BACKEND_PORT 覆盖
$RegulatorPort = if ($env:AF_REGULATOR_PORT) { [int]$env:AF_REGULATOR_PORT } else { 18080 }
$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 8080 }
$DronePort = if ($env:AF_DRONE_PORT) { [int]$env:AF_DRONE_PORT } else { 14555 }
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

function Send-PostJsonAllowFailure($url, $body) {
    # 语义拒绝（403 等）与成功都要拿到 body，才能断言 verdict/reason。这里不走
    # Invoke-WebRequest：PS 5.1 会在抛错前把响应流吃掉（GetResponseStream() 读出
    # 来是空串），裸 HttpWebRequest 的流不被预消费，非 2xx 的 body 照样读得到。
    # 返回 @{ Status = <int，0 表示网络层失败>; Json = <解析后对象或 $null> }。
    $json = $body | ConvertTo-Json -Depth 10 -Compress
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Method = 'POST'
    $req.ContentType = 'application/json; charset=UTF-8'
    $req.Timeout = 30000
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $req.ContentLength = $bytes.Length
    $stream = $req.GetRequestStream()
    try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Close() }

    $statusCode = 0
    $content = ''
    try {
        $resp = $req.GetResponse()
        $statusCode = [int]$resp.StatusCode
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { $content = $reader.ReadToEnd() } finally { $reader.Close(); $resp.Close() }
    } catch [System.Net.WebException] {
        $errResp = $_.Exception.Response
        if ($null -ne $errResp) {
            $statusCode = [int]$errResp.StatusCode
            $reader = New-Object System.IO.StreamReader($errResp.GetResponseStream(), [System.Text.Encoding]::UTF8)
            try { $content = $reader.ReadToEnd() } finally { $reader.Close() }
        }
    }

    $parsed = $null
    if (-not [string]::IsNullOrWhiteSpace($content)) {
        try { $parsed = $content | ConvertFrom-Json } catch { $parsed = $null }
    }
    return @{ Status = $statusCode; Json = $parsed }
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
# B1-CI 批次改造：原实现用 Get-CimInstance Win32_Process 枚举（Windows-only，
# Linux 上该 cmdlet 不存在且被 SilentlyContinue 吞掉——清理静默失效，端口被占，
# 表现为莫名的超时，与 e2e-signing 踩过的坑同源）。改为 e2e-common 的
# Stop-E2eStaleProcesses（Windows CIM / Linux /proc），按命令行留痕标记
# -Dregulator-e2e=true 精确杀（见下方各 Start-Process：该参数在 -jar 之前，
# 由 JVM 消费为系统属性，不会进 main 的 args，对 sim 的手写参数解析零影响）。
# 注：改版之前启动的旧残留（命令行无此标记）不再被清——新一轮进程全部带标记。
function Cleanup-OldProcesses {
    $null = Stop-E2eStaleProcesses -Marker 'regulator-e2e'
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
        $jarPath = Join-Path $Root "$module/target/*$module*.jar"   # 正斜杠：Linux 上反斜杠不是分隔符
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
$java = Get-E2eJava   # 跨平台探测（Linux 叫 java 不是 java.exe）
Assert-Java17 $java
Info "Java: $java"

# ---- 前置检查：jar 存在 ----
Step '前置检查（jar 存在、端口可用）'

$regulatorJar = Get-ChildItem (Join-Path $Root 'regulator-sim/target/*regulator-sim*-shaded.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
$backendJar = Get-ChildItem (Join-Path $Root 'cloud-backend/target/*cloud-backend*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
$droneSimJar = Get-ChildItem (Join-Path $Root 'drone-sim/target/*drone-sim*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1

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
    $regulatorJar = Get-ChildItem (Join-Path $Root 'regulator-sim/target/*regulator-sim*-shaded.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
    $backendJar = Get-ChildItem (Join-Path $Root 'cloud-backend/target/*cloud-backend*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
    $droneSimJar = Get-ChildItem (Join-Path $Root 'drone-sim/target/*drone-sim*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
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
# 1. 启动 regulator-sim（--port $RegulatorPort）
# ============================================================
Step "启动 regulator-sim（--port $RegulatorPort）"

$regulatorLog = Join-Path $TempDir "regulator-e2e-sim.log"
if (Test-Path $regulatorLog) { Remove-Item $regulatorLog -Force }

$regulatorProc = Start-Process -FilePath $java -ArgumentList @(
    '-Dregulator-e2e=true',   # 命令行留痕：Stop-E2eStaleProcesses 按它精确清残留
    '-jar', $regulatorJar.FullName,
    '--port', $RegulatorPort
) -PassThru -RedirectStandardOutput $regulatorLog
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
Step "启动 cloud-backend（sink-type=$SinkType, dev profile）"

$backendLog = Join-Path $TempDir "regulator-e2e-backend.log"
if (Test-Path $backendLog) { Remove-Item $backendLog -Force }

# 上报通道参数按 SinkType 分派（显式数组拼接——PS 5.1 的 -ArgumentList 不接受嵌套子表达式数组，
# 2026-10-28 实测：`@(..., $(if ...), ...)` 会抛 CannotConvertArgument）。
$sinkArgs = if ($SinkType -eq 'uom') {
    # 真实现（UomReportSink）指向同一个 MockUomServer——字段名与状态机语义同源，
    # 本腿多验的是**装配链**：条件 Bean 选型 / uom-base-url / API Key 与 HMAC 签名头
    # （sim 侧不校验签名；但 uom-base-url 写错、Bean 没装上，这里立刻全链失败）。
    @(
        '--aerofleet.regulator.sink-type=uom',
        "--aerofleet.regulator.uom-base-url=http://localhost:$RegulatorPort",
        '--aerofleet.regulator.api-key=e2e-uom-key',
        '--aerofleet.regulator.signing-key=e2e-uom-signing-key'
    )
} else {
    @(
        '--aerofleet.regulator.sink-type=sim',
        "--aerofleet.regulator.sim-base-url=http://localhost:$RegulatorPort"
    )
}

# 每轮独立内存库：dev profile 默认是**文件型** H2（jdbc:h2:file:./data/aerofleet），
# 而 #182/#185 之后合规状态（V32）会落库、跨重启保留——于是同一台机器上**连跑两轮 e2e**
# 时，上一轮 FR-34 注销过的 sysid=2 会以 CANCELLED 状态把下一轮 FR-31 顶死
# （2026-10-28 本机实测：uom 腿过了、紧接着 sim 腿 FR-31~34 全红，status 响应里带着
# 上一轮的 lastVerifyTime）。CI 上两条腿在同一个 runner 连跑，同样会撞。
# 用带随机后缀的 in-mem 库隔离每轮（Flyway 启动时全新迁移；SITL 的 backend 同款做法）。
$dbName = 'regulator-e2e-' + [guid]::NewGuid().ToString('N').Substring(0, 8)

$backendArgs = @(
    '-Dregulator-e2e=true',   # 命令行留痕：Stop-E2eStaleProcesses 按它精确清残留
    '-jar', $backendJar.FullName,
    "--server.port=$BackendPort",
    '--spring.profiles.active=dev',
    "--spring.datasource.url=jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"
) + $sinkArgs + @(
    "--aerofleet.regulator.telemetry-report-interval=$TelemetryIntervalSec",
    "--aerofleet.drone-port=$DronePort",
    "--aerofleet.security.rbac-enabled=false",
    '--aerofleet.security.dev-mode=true'
)

$backendProc = Start-Process -FilePath $java -ArgumentList $backendArgs -PassThru -RedirectStandardOutput $backendLog
Register-Cleanup { Stop-Process -Id $backendProc.Id -Force -ErrorAction SilentlyContinue }
Info "cloud-backend PID=$($backendProc.Id), log=$backendLog"

# 等待 cloud-backend 启动
# 业务就绪判据而非 actuator/health：dev profile 连本地 Redis，本机 6379 若不通/带口令，
# RedisHealthContributor 会把 health 打成 503——与"服务是否可用"无关，据此判失败是误杀。
# /drones 只读且恒 200（cv-eval 同款），足以证明 web 层与持久层已就绪。
$backendUp = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep 1
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$BackendPort/api/v1/drones" -TimeoutSec 2 -UseBasicParsing
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

$droneLog = Join-Path $TempDir "regulator-e2e-drone.log"
if (Test-Path $droneLog) { Remove-Item $droneLog -Force }

$droneProc = Start-Process -FilePath $java -ArgumentList @(
    '-Dregulator-e2e=true',   # 命令行留痕：Stop-E2eStaleProcesses 按它精确清残留
    '-jar', $droneSimJar.FullName,
    '--port', $DronePort,
    '--sysid', $DroneSysid,
    '--name', 'AF-REG-02'
) -PassThru -RedirectStandardOutput $droneLog
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
Step 'FR-36~39：UOM 激活状态机（幂等/未激活拒绝/注销后拒绝/reset —— sim HTTP 面）'

# C1 高保真模拟（2026-10-12）：regulator-sim 的 activate/cancel/telemetry
# 不再无条件 success，而是按真实 UOM 语义做状态机。以下断言直连 sim（HTTP 面），
# 不经 cloud-backend——后端自己的合规状态机按 sysid 且只进不退，重复激活在入口
# 就被 409 挡掉，sim 的 serialNo 维度语义根本触达不到；要验 sim 就得打 sim。
# 拒绝用 403 表达（非 2xx），故统一走 Send-PostJsonAllowFailure 取 body 断言。
$SimApi = "$RegulatorBase/api"

# FR-36：激活幂等——同 serialNo 重复 activate 返回同一 activationId
$firstActivate = $null
$secondActivate = $null
try {
    $firstActivate = Send-PostJsonAllowFailure "$SimApi/activate" @{ serialNo = $TestSerialNo }
    $secondActivate = Send-PostJsonAllowFailure "$SimApi/activate" @{ serialNo = $TestSerialNo }
    Info "activate 第 1 次: status=$($firstActivate.Status) $($firstActivate.Json | ConvertTo-Json -Compress)"
    Info "activate 第 2 次: status=$($secondActivate.Status) $($secondActivate.Json | ConvertTo-Json -Compress)"
    $id1 = $firstActivate.Json.activationId
    $id2 = $secondActivate.Json.activationId
    Check 'FR-36: 重复 activate 返回同一 activationId' ($id1 -and $id1 -eq $id2)
    Check 'FR-36: 重复 activate 第二次带幂等标记 idempotent=true' ($secondActivate.Json.idempotent -eq $true)
} catch {
    Info "activate 幂等测试异常: $_"
    Check 'FR-36: 重复 activate 返回同一 activationId' $false
    Check 'FR-36: 重复 activate 第二次带幂等标记 idempotent=true' $false
}

# FR-37：未实名/未激活拒绝——reset 后 UNVERIFIED 的 serialNo 既激活不了也上报不了
try {
    # 直连 sim reset（用例间隔离；预置数据与记录不动）
    Invoke-RestMethod -Uri "$SimApi/reset" -Method Post -TimeoutSec 5 | Out-Null
    # TEST-002 预置状态 UNVERIFIED：activate 应被拒（NOT_VERIFIED），自然处于未激活态
    $badActivate = Send-PostJsonAllowFailure "$SimApi/activate" @{ serialNo = $TestSerialNoUnverified }
    Info "TEST-002 activate 响应: status=$($badActivate.Status) $($badActivate.Json | ConvertTo-Json -Compress)"
    Check 'FR-37: 未实名（UNVERIFIED）serialNo 激活被拒' ($badActivate.Status -eq 403 -and $badActivate.Json.success -eq $false)
    Check 'FR-37: 拒绝原因为 NOT_VERIFIED 语义' ($badActivate.Json.verdict -eq 'NOT_VERIFIED')
    # 未激活的 serialNo 直接上报遥测：应被拒（NOT_ACTIVATED），不进记录
    $unactivatedTelemetry = Send-PostJsonAllowFailure "$SimApi/telemetry" @{ sysid = $DroneSysid; serialNo = $TestSerialNoUnverified }
    Info "TEST-002 遥测上报响应: status=$($unactivatedTelemetry.Status) $($unactivatedTelemetry.Json | ConvertTo-Json -Compress)"
    Check 'FR-37: 未激活 serialNo 的遥测上报被拒（403 + success≠true）' ($unactivatedTelemetry.Status -eq 403 -and $unactivatedTelemetry.Json.success -ne $true)
    Check 'FR-37: 遥测拒绝原因为 NOT_ACTIVATED' ($unactivatedTelemetry.Json.verdict -eq 'NOT_ACTIVATED')
} catch {
    Info "未激活拒绝测试异常: $_"
    Check 'FR-37: 未实名（UNVERIFIED）serialNo 激活被拒' $false
    Check 'FR-37: 未激活 serialNo 的遥测上报被拒（403 + success≠true）' $false
}

# FR-38：注销后激活拒绝——先注销再激活应被拒（sim 状态 CANCELLED）
try {
    # 先恢复 FR-36 激活过的 TEST-001，再注销，否则断言的是"从未激活"而非"已注销"
    Send-PostJsonAllowFailure "$SimApi/activate" @{ serialNo = $TestSerialNo } | Out-Null
    $midCancel = Send-PostJsonAllowFailure "$SimApi/cancel" @{ serialNo = $TestSerialNo; reason = 'e2e-fr38' }
    Info "FR-38 cancel 响应: status=$($midCancel.Status) $($midCancel.Json | ConvertTo-Json -Compress)"
    $afterCancelActivate = Send-PostJsonAllowFailure "$SimApi/activate" @{ serialNo = $TestSerialNo }
    Info "注销后再 activate 响应: status=$($afterCancelActivate.Status) $($afterCancelActivate.Json | ConvertTo-Json -Compress)"
    Check 'FR-38: 已注销 serialNo 再激活被拒（403 + success=false）' ($afterCancelActivate.Status -eq 403 -and $afterCancelActivate.Json.success -eq $false)
    Check 'FR-38: 拒绝原因为 CANCELLED' ($afterCancelActivate.Json.verdict -eq 'CANCELLED')
} catch {
    Info "注销后激活测试异常: $_"
    Check 'FR-38: 已注销 serialNo 再激活被拒（403 + success=false）' $false
}

# FR-39：reset 生效——状态归零后同一 serialNo 可重新完整激活
try {
    Invoke-RestMethod -Uri "$SimApi/reset" -Method Post -TimeoutSec 5 | Out-Null
    $freshActivate = Send-PostJsonAllowFailure "$SimApi/activate" @{ serialNo = $TestSerialNo }
    Info "reset 后 activate 响应: status=$($freshActivate.Status) $($freshActivate.Json | ConvertTo-Json -Compress)"
    Check 'FR-39: reset 后同一 serialNo 可重新激活（success=true 且有新 activationId）' `
        ($freshActivate.Status -eq 200 -and $freshActivate.Json.success -eq $true -and $freshActivate.Json.activationId -and $freshActivate.Json.idempotent -ne $true)
} catch {
    Info "reset 测试异常: $_"
    Check 'FR-39: reset 后同一 serialNo 可重新激活（success=true 且有新 activationId）' $false
}

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
# FR-40：4xx 是语义终态，不得退避重试（#55 修完后补的验证）
# 起一个 --error-rate 1.0 --error-status 403 的 sim，经后端发一次 /verify，
# 断言：① 只到 sim 一次（不是 1+3 次）；② 5xx 才走退避（+2 次对比）。
# 此前 e2e-regulator 从未用过 --error-rate，这条路径一直没人验过。
# ============================================================
Step 'FR-40：4xx 语义终态不重试（精确请求计数 + 5xx 对照）'

function Sim-RequestCount($method, $path) {
    $stats = Send-GetJson "$RegulatorBase/api/stats"
    $key = "$method $path"
    if ($stats.requests -and $stats.requests.$key) { return [int]$stats.requests.$key }
    return 0
}

try {
    # 1) 403 注入
    Stop-Process -Id $regulatorProc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep 2
    $regulatorProc = Start-Process -FilePath $java -ArgumentList @(
        '-Dregulator-e2e=true',   # 命令行留痕：Stop-E2eStaleProcesses 按它精确清残留
        '-jar', $regulatorJar.FullName,
        '--port', $RegulatorPort,
        '--error-rate', '1.0',
        '--error-status', '403'
    ) -PassThru -RedirectStandardOutput $regulatorLog
    Register-Cleanup { Stop-Process -Id $regulatorProc.Id -Force -ErrorAction SilentlyContinue }
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep 1
        try { $null = Invoke-WebRequest -Uri "$RegulatorBase/api/stats" -TimeoutSec 2 -UseBasicParsing; break } catch { }
    }
    Info "sim 已按 --error-rate 1.0 --error-status 403 重启"

    # 2) 经后端发一次 verify（新版状态机下 sysid=2 已注销，用一个新 sysid）
    $retrySysid = 77
    $retryBody = @{ sysid = $retrySysid; productSerialNo = $TestSerialNo; realNameCertNo = $TestCertNo }
    Send-PostJson "$RegulatorBase/api/reset" @{} | Out-Null
    Send-GetJson "$RegulatorBase/api/stats" | Out-Null   # 触发计数
    $verifyBefore = Sim-RequestCount 'GET' '/api/verify'

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $retryResp = Send-PostJson "$BackendBase/verify" $retryBody
    $sw.Stop()
    Info "verify 响应: $($retryResp | ConvertTo-Json -Compress)（耗时 $($sw.ElapsedMilliseconds) ms）"

    $verifyAfter = Sim-RequestCount 'GET' '/api/verify'
    $requests = $verifyAfter - $verifyBefore
    Info "sim 收到的 verify 次数: $requests（终态应=1，可重试旧行为会=4）"
    Check 'FR-40: 4xx 只请求一次（不退避重试）' ($requests -eq 1)
    Check 'FR-40: verify 返回 ERROR（失败如实上报）' ($retryResp.verifyStatus -eq 'ERROR')
    # 无重试 ⇒ 不应有 1s/2s/4s 的退避等待
    Check 'FR-40: 无退避等待（耗时 < 2s）' ($sw.ElapsedMilliseconds -lt 2000)

    # 3) 对照：500 必须仍走退避（1+3 次），否则"不重试"会被误做成"永不重试"
    Stop-Process -Id $regulatorProc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep 2
    $regulatorProc = Start-Process -FilePath $java -ArgumentList @(
        '-Dregulator-e2e=true',   # 命令行留痕：Stop-E2eStaleProcesses 按它精确清残留
        '-jar', $regulatorJar.FullName,
        '--port', $RegulatorPort,
        '--error-rate', '1.0',
        '--error-status', '500'
    ) -PassThru -RedirectStandardOutput $regulatorLog
    Register-Cleanup { Stop-Process -Id $regulatorProc.Id -Force -ErrorAction SilentlyContinue }
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep 1
        try { $null = Invoke-WebRequest -Uri "$RegulatorBase/api/stats" -TimeoutSec 2 -UseBasicParsing; break } catch { }
    }
    $verifyBefore = Sim-RequestCount 'GET' '/api/verify'

    $sw2 = [System.Diagnostics.Stopwatch]::StartNew()
    $retryResp2 = Send-PostJson "$BackendBase/verify" $retryBody
    $sw2.Stop()
    $verifyAfter2 = Sim-RequestCount 'GET' '/api/verify'
    $requests2 = $verifyAfter2 - $verifyBefore
    Info "500 对照：sim 收到 $requests2 次，耗时 $($sw2.ElapsedMilliseconds) ms"
    Check 'FR-40: 500 仍会退避重试（次数 > 1）' ($requests2 -gt 1)
    Check 'FR-40: 500 的退避有实际等待（耗时 > 2s）' ($sw2.ElapsedMilliseconds -gt 2000)
    Check 'FR-40: 500 同样如实返回 ERROR' ($retryResp2.verifyStatus -eq 'ERROR')
} catch {
    Info "FR-40 异常: $_"
    Check 'FR-40: 4xx 语义终态不重试' $false
}

# ============================================================
# 清理：停止所有进程
# ============================================================
Step '清理：停止所有进程'
Invoke-Cleanup

# 确保所有 java 进程已清理（跨平台按标记杀，见 Cleanup-OldProcesses 注释）
$null = Stop-E2eStaleProcesses -Marker 'regulator-e2e'

# ============================================================
# 结果
# ============================================================
Write-Host ''
if ($Fail -eq 0) {
    Write-Host 'REGULATOR E2E PASSED（FR-31~40 端到端验证通过）' -ForegroundColor Green
    exit 0
} else {
    Write-Host 'REGULATOR E2E FAILED（部分断言未通过，见上方 FAIL 行）' -ForegroundColor Red
    exit 1
}
# MAVLink v2 签名端到端验证脚本（C5-T18）
#
# 依据：design.md §签名安全 + spec.md FR-36~FR-41
# 场景：cloud-backend + drone-sim + link-sim 全链路签名验证
#
# 断言目标（6 个场景）：
#   场景1 正常签名通信：签名启用 + 同一密钥 → 命令/遥测正常通信 → 签名验证通过
#   场景2 篡改检测：link-sim --profile tamper → 篡改帧被接收方拒绝 → WARN 日志可见签名验证失败
#   场景3 未签名拒绝：link-sim --profile unsigned → 未签名帧被接收方拒绝 → WARN 日志可见拒绝未签名帧
#   场景4 密钥不匹配：backend 与 drone-sim 使用不同密钥 → 所有帧验证失败 → 通信中断
#   场景5 多机密钥分发：使用密钥文件配置不同 sysid 的密钥 → 各机通信正常
#   场景6 签名未启用兼容：不启用签名 → 通信正常，行为与现有 e2e 一致
#
# 前置检查：cloud-backend / drone-sim / link-sim jar 存在、端口可用
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-signing.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-signing.ps1 -SkipBuild
#
# 前置：JDK 17 + Maven（自动构建缺失 jar）

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

# ---- 端口配置 ----
$BackendRestPort = 8080
$BackendUdpPort = 14550
$DronePort = 14540
$DronePort2 = 14541
$LinkSimPort = 14600

# ---- 签名测试密钥 ----
$SigningKey = 'test-signing-secret-key-2026'
$SigningKeyAlt = 'different-key-mismatch-test'
$Sys1Key = 'sys1-secret-key'
$Sys2Key = 'sys2-secret-key'

# ---- REST 基址 ----
$Base = "http://localhost:$BackendRestPort/api/v1"

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

function Send-Cmd($sysid, $type) {
    $body = @{ type = $type } | ConvertTo-Json
    return Invoke-RestMethod -Uri "$Base/drones/$sysid/commands" -Method Post -Body $body `
        -ContentType 'application/json' -TimeoutSec 25
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
        if ($_.CommandLine -match 'cloud-backend.*signing-e2e' `
            -or $_.CommandLine -match 'drone-sim.*signing-e2e' `
            -or $_.CommandLine -match 'link-sim.*signing-e2e') {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep 1
}

# ============================================================
# 等待 backend 启动
# ============================================================
function Wait-BackendUp($logFile) {
    $up = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep 1
        try {
            $r = Invoke-WebRequest -Uri "http://localhost:$BackendRestPort/actuator/health" -TimeoutSec 2 -UseBasicParsing
            if ($r.StatusCode -eq 200) { $up = $true; break }
        } catch { }
    }
    if (-not $up -and $logFile -and (Test-Path $logFile)) {
        Info 'backend 启动失败，查看日志尾部:'
        Get-Content $logFile -Tail 30 | ForEach-Object { Info $_ }
    }
    return $up
}

# ============================================================
# 等待 drone-sim 被发现
# ============================================================
function Wait-DroneDiscovered($sysid, $timeoutSec = 30) {
    for ($i = 0; $i -lt $timeoutSec; $i++) {
        Start-Sleep 1
        try {
            $d = (Invoke-RestMethod "$Base/drones" -TimeoutSec 5) | Where-Object { $_.sysid -eq $sysid }
            if ($d -and $d.online) { return $true }
        } catch { }
    }
    return $false
}

# ============================================================
# 搜索日志文件中的关键词
# ============================================================
function Search-Log($logFile, $pattern) {
    if (-not (Test-Path $logFile)) { return $false }
    $content = Get-Content $logFile -ErrorAction SilentlyContinue
    foreach ($line in $content) {
        if ($line -match $pattern) {
            Info "日志匹配: $line"
            return $true
        }
    }
    return $false
}

# ============================================================
# 构建缺失 jar
# ============================================================
function Build-Jars {
    $mavenCmd = 'mvn'
    if ($env:MAVEN_HOME -and (Test-Path (Join-Path $env:MAVEN_HOME "bin\mvn.cmd"))) {
        $mavenCmd = Join-Path $env:MAVEN_HOME "bin\mvn.cmd"
    }

    $jdk17Home = 'E:\dev-tools\jdk17.0.20_8'
    if (Test-Path $jdk17Home) {
        $env:JAVA_HOME = $jdk17Home
        Info "JAVA_HOME 设置为 $jdk17Home"
    }

    Step '构建缺失的 jar（JDK17）'
    foreach ($module in @('cloud-backend', 'drone-sim', 'link-sim')) {
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
# 停止并清理当前场景进程
# ============================================================
function Stop-SceneProcesses {
    Invoke-Cleanup
    # 额外清理：确保所有 signing-e2e 相关 java 进程被终止
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.CommandLine -match 'signing-e2e') {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep 2
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

$backendJar = Get-ChildItem (Join-Path $Root 'cloud-backend\target\*cloud-backend*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
$droneSimJar = Get-ChildItem (Join-Path $Root 'drone-sim\target\*drone-sim*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
$linkSimJar = Get-ChildItem (Join-Path $Root 'link-sim\target\*link-sim*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1

$backendOk = $null -ne $backendJar
$droneSimOk = $null -ne $droneSimJar
$linkSimOk = $null -ne $linkSimJar

Check 'cloud-backend jar 存在' $backendOk
Check 'drone-sim jar 存在' $droneSimOk
Check 'link-sim jar 存在' $linkSimOk

if (-not ($backendOk -and $droneSimOk -and $linkSimOk)) {
    if ($SkipBuild) {
        Info '前置条件缺失：jar 不存在且 -SkipBuild 已指定，无法继续'
        Write-Host '`nSIGNING E2E FAILED（前置条件缺失）' -ForegroundColor Red
        exit 1
    }
    Info '部分 jar 缺失，自动构建...'
    Build-Jars
    # 重新检测
    $backendJar = Get-ChildItem (Join-Path $Root 'cloud-backend\target\*cloud-backend*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
    $droneSimJar = Get-ChildItem (Join-Path $Root 'drone-sim\target\*drone-sim*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
    $linkSimJar = Get-ChildItem (Join-Path $Root 'link-sim\target\*link-sim*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
    $backendOk = $null -ne $backendJar
    $droneSimOk = $null -ne $droneSimJar
    $linkSimOk = $null -ne $linkSimJar
    Check 'cloud-backend jar 构建后存在' $backendOk
    Check 'drone-sim jar 构建后存在' $droneSimOk
    Check 'link-sim jar 构建后存在' $linkSimOk
    if (-not ($backendOk -and $droneSimOk -and $linkSimOk)) {
        Write-Host '`nSIGNING E2E FAILED（jar 构建失败）' -ForegroundColor Red
        exit 1
    }
}

Info "cloud-backend jar: $($backendJar.FullName)"
Info "drone-sim jar: $($droneSimJar.FullName)"
Info "link-sim jar: $($linkSimJar.FullName)"

# ---- 端口可用性 ----
$portsToCheck = @($BackendRestPort, $BackendUdpPort, $DronePort, $LinkSimPort)
$allPortsFree = $true
foreach ($p in $portsToCheck) {
    $free = Test-PortFree $p
    Check "端口 $p 可用" $free
    if (-not $free) { $allPortsFree = $false }
}

if (-not $allPortsFree) {
    Info '端口被占用，尝试清理旧进程...'
    Cleanup-OldProcesses
    Start-Sleep 2
    $allPortsFree = $true
    foreach ($p in $portsToCheck) {
        if (-not (Test-PortFree $p)) { $allPortsFree = $false }
    }
    if (-not $allPortsFree) {
        Info '端口仍被占用，请手动清理后重跑'
        Write-Host '`nSIGNING E2E FAILED（端口占用）' -ForegroundColor Red
        exit 1
    }
}

Cleanup-OldProcesses

# ============================================================
# 场景1：正常签名通信
# backend(signing on, key=$SigningKey) + drone-sim(signing on, key=$SigningKey) 直连
# ============================================================
Step '场景1：正常签名通信（签名启用 + 同一密钥）'

$backendLog1 = Join-Path $env:TEMP "signing-e2e-backend-s1.log"
if (Test-Path $backendLog1) { Remove-Item $backendLog1 -Force }

$backend1 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $backendJar.FullName,
    "--aerofleet.drone-port=$DronePort",
    '--aerofleet.security.rbac-enabled=false',
    '--aerofleet.security.dev-mode=true',
    '--mavlink.signing.enabled=true',
    "--mavlink.signing.secret-key=$SigningKey"
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog1
Register-Cleanup { Stop-Process -Id $backend1.Id -Force -ErrorAction SilentlyContinue }
Info "backend PID=$($backend1.Id)"

$backendUp1 = Wait-BackendUp $backendLog1
Check '场景1: cloud-backend 启动成功（签名启用）' $backendUp1
if (-not $backendUp1) {
    Stop-SceneProcesses
    Write-Host '`nSIGNING E2E FAILED（场景1 backend 启动失败）' -ForegroundColor Red
    exit 1
}

$droneLog1 = Join-Path $env:TEMP "signing-e2e-drone-s1.log"
if (Test-Path $droneLog1) { Remove-Item $droneLog1 -Force }

$drone1 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $droneSimJar.FullName,
    '--port', $DronePort,
    '--sysid', '1',
    '--name', 'AF-SIGN-01',
    '--signing-key', $SigningKey
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog1
Register-Cleanup { Stop-Process -Id $drone1.Id -Force -ErrorAction SilentlyContinue }
Info "drone-sim PID=$($drone1.Id)"
Start-Sleep 2

$drone1Alive = -not $drone1.HasExited
Check '场景1: drone-sim 启动成功（签名启用）' $drone1Alive

# 等待设备发现
$discovered1 = Wait-DroneDiscovered 1
Check '场景1: 签名启用时设备发现正常' $discovered1

if ($discovered1) {
    # 验证命令通信
    try {
        $cmdResp = Send-Cmd 1 'arm'
        $cmdOk = $cmdResp.status -eq 'ok'
        Check '场景1: 签名启用时命令通信正常（ARM）' $cmdOk
    } catch {
        Info "命令发送异常: $_"
        Check '场景1: 签名启用时命令通信正常（ARM）' $false
    }

    # 验证遥测
    try {
        $tel = Invoke-RestMethod "$Base/drones/1/telemetry" -TimeoutSec 5
        $telOk = $null -ne $tel
        Check '场景1: 签名启用时遥测数据可读' $telOk
    } catch {
        Info "遥测查询异常: $_"
        Check '场景1: 签名启用时遥测数据可读' $false
    }

    # 检查 backend 日志中是否有签名验证通过的记录
    Start-Sleep 3
    $signingVerified = Search-Log $backendLog1 'signing.*verified|verified='
    Check '场景1: backend 日志可见签名验证统计' $signingVerified
}

Stop-SceneProcesses

# ============================================================
# 场景2：篡改检测
# backend(signing on) + link-sim(tamper) + drone-sim(signing on)
# ============================================================
Step '场景2：篡改检测（link-sim --profile tamper）'

$backendLog2 = Join-Path $env:TEMP "signing-e2e-backend-s2.log"
if (Test-Path $backendLog2) { Remove-Item $backendLog2 -Force }

$backend2 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $backendJar.FullName,
    "--aerofleet.drone-port=$LinkSimPort",
    '--aerofleet.security.rbac-enabled=false',
    '--aerofleet.security.dev-mode=true',
    '--mavlink.signing.enabled=true',
    "--mavlink.signing.secret-key=$SigningKey"
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog2
Register-Cleanup { Stop-Process -Id $backend2.Id -Force -ErrorAction SilentlyContinue }
Info "backend PID=$($backend2.Id)"

$backendUp2 = Wait-BackendUp $backendLog2
Check '场景2: cloud-backend 启动成功' $backendUp2
if (-not $backendUp2) {
    Stop-SceneProcesses
    Write-Host '`nSIGNING E2E FAILED（场景2 backend 启动失败）' -ForegroundColor Red
    exit 1
}

# 启动 link-sim（篡改画像）
$linkSimLog2 = Join-Path $env:TEMP "signing-e2e-linksim-s2.log"
if (Test-Path $linkSimLog2) { Remove-Item $linkSimLog2 -Force }

$linkSim2 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $linkSimJar.FullName,
    '--profile', 'tamper',
    '--port', $LinkSimPort,
    '--drone-ip', '127.0.0.1',
    '--drone-port', $DronePort,
    '--tamper-rate', '1.0'
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $linkSimLog2
Register-Cleanup { Stop-Process -Id $linkSim2.Id -Force -ErrorAction SilentlyContinue }
Info "link-sim PID=$($linkSim2.Id) (profile=tamper)"
Start-Sleep 2

$linkSim2Alive = -not $linkSim2.HasExited
Check '场景2: link-sim 启动成功（tamper 画像）' $linkSim2Alive

# 启动 drone-sim（签名启用）
$droneLog2 = Join-Path $env:TEMP "signing-e2e-drone-s2.log"
if (Test-Path $droneLog2) { Remove-Item $droneLog2 -Force }

$drone2 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $droneSimJar.FullName,
    '--port', $DronePort,
    '--sysid', '1',
    '--name', 'AF-SIGN-02',
    '--signing-key', $SigningKey
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog2
Register-Cleanup { Stop-Process -Id $drone2.Id -Force -ErrorAction SilentlyContinue }
Info "drone-sim PID=$($drone2.Id)"
Start-Sleep 2

$drone2Alive = -not $drone2.HasExited
Check '场景2: drone-sim 启动成功' $drone2Alive

# 等待通信建立和篡改帧被拒绝
Info '等待 10s 让篡改帧产生并被 backend 拒绝...'
Start-Sleep 10

# 检查 backend 日志中是否有签名验证失败的记录
$tamperDetected = Search-Log $backendLog2 '签名验证失败|signing.*rejected'
Check '场景2: backend 日志可见签名验证失败（篡改检测）' $tamperDetected

# 检查 link-sim 日志中是否有篡改统计
$tamperStats = Search-Log $linkSimLog2 'tampered='
Check '场景2: link-sim 日志可见篡改统计' $tamperStats

# 篡改场景下设备应该无法正常通信（因为帧被篡改后签名验证失败）
$drone2Discovered = Wait-DroneDiscovered 1 10
if ($drone2Discovered) {
    # 即使发现了设备（通过未篡改的帧），命令通信应该失败
    Info '设备被发现（部分帧可能未被篡改），验证命令通信...'
    try {
        $cmdResp2 = Send-Cmd 1 'arm'
        # 篡改场景下命令可能到达也可能不到，关键断言是篡改检测日志
        Info "命令响应: $($cmdResp2.status)"
    } catch {
        Info "命令发送失败（预期行为，篡改链路下通信不可靠）"
    }
}
# 核心断言：篡改帧被检测到并拒绝
Check '场景2: 篡改帧被接收方拒绝（签名验证失败日志可见）' $tamperDetected

Stop-SceneProcesses

# ============================================================
# 场景3：未签名拒绝
# backend(signing on, reject-unsigned=true) + link-sim(unsigned) + drone-sim(signing on)
# ============================================================
Step '场景3：未签名拒绝（link-sim --profile unsigned）'

$backendLog3 = Join-Path $env:TEMP "signing-e2e-backend-s3.log"
if (Test-Path $backendLog3) { Remove-Item $backendLog3 -Force }

$backend3 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $backendJar.FullName,
    "--aerofleet.drone-port=$LinkSimPort",
    '--aerofleet.security.rbac-enabled=false',
    '--aerofleet.security.dev-mode=true',
    '--mavlink.signing.enabled=true',
    "--mavlink.signing.secret-key=$SigningKey",
    '--mavlink.signing.reject-unsigned=true'
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog3
Register-Cleanup { Stop-Process -Id $backend3.Id -Force -ErrorAction SilentlyContinue }
Info "backend PID=$($backend3.Id)"

$backendUp3 = Wait-BackendUp $backendLog3
Check '场景3: cloud-backend 启动成功（reject-unsigned=true）' $backendUp3
if (-not $backendUp3) {
    Stop-SceneProcesses
    Write-Host '`nSIGNING E2E FAILED（场景3 backend 启动失败）' -ForegroundColor Red
    exit 1
}

# 启动 link-sim（未签名画像）
$linkSimLog3 = Join-Path $env:TEMP "signing-e2e-linksim-s3.log"
if (Test-Path $linkSimLog3) { Remove-Item $linkSimLog3 -Force }

$linkSim3 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $linkSimJar.FullName,
    '--profile', 'unsigned',
    '--port', $LinkSimPort,
    '--drone-ip', '127.0.0.1',
    '--drone-port', $DronePort
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $linkSimLog3
Register-Cleanup { Stop-Process -Id $linkSim3.Id -Force -ErrorAction SilentlyContinue }
Info "link-sim PID=$($linkSim3.Id) (profile=unsigned)"
Start-Sleep 2

$linkSim3Alive = -not $linkSim3.HasExited
Check '场景3: link-sim 启动成功（unsigned 画像）' $linkSim3Alive

# 启动 drone-sim（签名启用）
$droneLog3 = Join-Path $env:TEMP "signing-e2e-drone-s3.log"
if (Test-Path $droneLog3) { Remove-Item $droneLog3 -Force }

$drone3 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $droneSimJar.FullName,
    '--port', $DronePort,
    '--sysid', '1',
    '--name', 'AF-SIGN-03',
    '--signing-key', $SigningKey
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog3
Register-Cleanup { Stop-Process -Id $drone3.Id -Force -ErrorAction SilentlyContinue }
Info "drone-sim PID=$($drone3.Id)"
Start-Sleep 2

$drone3Alive = -not $drone3.HasExited
Check '场景3: drone-sim 启动成功' $drone3Alive

# 等待通信建立和未签名帧被拒绝
Info '等待 10s 让未签名帧产生并被 backend 拒绝...'
Start-Sleep 10

# 检查 backend 日志中是否有拒绝未签名帧的记录
$unsignedRejected = Search-Log $backendLog3 '拒绝未签名帧|rejectUnsigned'
Check '场景3: backend 日志可见拒绝未签名帧' $unsignedRejected

# 检查 link-sim 日志中是否有剥离签名统计
$strippedStats = Search-Log $linkSimLog3 'stripped='
Check '场景3: link-sim 日志可见签名剥离统计' $strippedStats

# 未签名场景下设备应该无法被发现（签名被剥离后帧被拒绝）
$drone3Discovered = Wait-DroneDiscovered 1 10
Check '场景3: 未签名帧被拒绝（设备不应被发现）' (-not $drone3Discovered)

Stop-SceneProcesses

# ============================================================
# 场景4：密钥不匹配
# backend(signing on, key=A) + drone-sim(signing on, key=B) 直连
# ============================================================
Step '场景4：密钥不匹配（backend key=A, drone-sim key=B）'

$backendLog4 = Join-Path $env:TEMP "signing-e2e-backend-s4.log"
if (Test-Path $backendLog4) { Remove-Item $backendLog4 -Force }

$backend4 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $backendJar.FullName,
    "--aerofleet.drone-port=$DronePort",
    '--aerofleet.security.rbac-enabled=false',
    '--aerofleet.security.dev-mode=true',
    '--mavlink.signing.enabled=true',
    "--mavlink.signing.secret-key=$SigningKey"
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog4
Register-Cleanup { Stop-Process -Id $backend4.Id -Force -ErrorAction SilentlyContinue }
Info "backend PID=$($backend4.Id) (key=$SigningKey)"

$backendUp4 = Wait-BackendUp $backendLog4
Check '场景4: cloud-backend 启动成功（密钥 A）' $backendUp4
if (-not $backendUp4) {
    Stop-SceneProcesses
    Write-Host '`nSIGNING E2E FAILED（场景4 backend 启动失败）' -ForegroundColor Red
    exit 1
}

# drone-sim 使用不同的密钥
$droneLog4 = Join-Path $env:TEMP "signing-e2e-drone-s4.log"
if (Test-Path $droneLog4) { Remove-Item $droneLog4 -Force }

$drone4 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $droneSimJar.FullName,
    '--port', $DronePort,
    '--sysid', '1',
    '--name', 'AF-SIGN-04',
    '--signing-key', $SigningKeyAlt
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog4
Register-Cleanup { Stop-Process -Id $drone4.Id -Force -ErrorAction SilentlyContinue }
Info "drone-sim PID=$($drone4.Id) (key=$SigningKeyAlt)"
Start-Sleep 2

$drone4Alive = -not $drone4.HasExited
Check '场景4: drone-sim 启动成功（密钥 B）' $drone4Alive

# 等待一段时间让通信尝试发生
Info '等待 15s 让密钥不匹配的通信尝试发生...'
Start-Sleep 15

# 密钥不匹配时，签名验证应该失败
$keyMismatchRejected = Search-Log $backendLog4 '签名验证失败|signing.*rejected'
Check '场景4: backend 日志可见签名验证失败（密钥不匹配）' $keyMismatchRejected

# 密钥不匹配时设备不应被发现
$drone4Discovered = Wait-DroneDiscovered 1 10
Check '场景4: 密钥不匹配时设备无法正常通信' (-not $drone4Discovered)

Stop-SceneProcesses

# ============================================================
# 场景5：多机密钥分发
# backend(signing on, key-store=JSON文件) + drone-sim1(sysid=1, key=sys1) + drone-sim2(sysid=2, key=sys2)
# ============================================================
Step '场景5：多机密钥分发（密钥文件配置不同 sysid 的密钥）'

# 创建临时 JSON 密钥文件
$keyStoreFile = Join-Path $env:TEMP "signing-e2e-keystore.json"
$keyStoreJson = @{
    version = 1
    defaultKey = $SigningKey
    sysids = @{
        "1" = @{ key = $Sys1Key; linkId = 1 }
        "2" = @{ key = $Sys2Key; linkId = 2 }
    }
} | ConvertTo-Json -Depth 5
Set-Content -Path $keyStoreFile -Value $keyStoreJson -Encoding UTF8
Info "密钥文件: $keyStoreFile"
Info "密钥文件内容: $keyStoreJson"

$backendLog5 = Join-Path $env:TEMP "signing-e2e-backend-s5.log"
if (Test-Path $backendLog5) { Remove-Item $backendLog5 -Force }

$backend5 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $backendJar.FullName,
    "--aerofleet.drone-port=$DronePort",
    "--aerofleet.drone-extra-ports=$DronePort2",
    '--aerofleet.security.rbac-enabled=false',
    '--aerofleet.security.dev-mode=true',
    '--mavlink.signing.enabled=true',
    "--mavlink.signing.key-store-path=$keyStoreFile"
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog5
Register-Cleanup { Stop-Process -Id $backend5.Id -Force -ErrorAction SilentlyContinue }
Info "backend PID=$($backend5.Id) (multi-key mode)"

$backendUp5 = Wait-BackendUp $backendLog5
Check '场景5: cloud-backend 启动成功（多机密钥模式）' $backendUp5
if (-not $backendUp5) {
    Stop-SceneProcesses
    Write-Host '`nSIGNING E2E FAILED（场景5 backend 启动失败）' -ForegroundColor Red
    exit 1
}

# 启动 drone-sim 1（sysid=1, key=sys1-secret）
$droneLog5a = Join-Path $env:TEMP "signing-e2e-drone-s5a.log"
if (Test-Path $droneLog5a) { Remove-Item $droneLog5a -Force }

$drone5a = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $droneSimJar.FullName,
    '--port', $DronePort,
    '--sysid', '1',
    '--name', 'AF-SIGN-05A',
    '--signing-key', $Sys1Key
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog5a
Register-Cleanup { Stop-Process -Id $drone5a.Id -Force -ErrorAction SilentlyContinue }
Info "drone-sim 1 PID=$($drone5a.Id) (sysid=1, key=$Sys1Key)"
Start-Sleep 2

$drone5aAlive = -not $drone5a.HasExited
Check '场景5: drone-sim 1 启动成功（sysid=1）' $drone5aAlive

# 启动 drone-sim 2（sysid=2, key=sys2-secret）
$droneLog5b = Join-Path $env:TEMP "signing-e2e-drone-s5b.log"
if (Test-Path $droneLog5b) { Remove-Item $droneLog5b -Force }

$drone5b = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $droneSimJar.FullName,
    '--port', $DronePort2,
    '--sysid', '2',
    '--name', 'AF-SIGN-05B',
    '--signing-key', $Sys2Key
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog5b
Register-Cleanup { Stop-Process -Id $drone5b.Id -Force -ErrorAction SilentlyContinue }
Info "drone-sim 2 PID=$($drone5b.Id) (sysid=2, key=$Sys2Key)"
Start-Sleep 2

$drone5bAlive = -not $drone5b.HasExited
Check '场景5: drone-sim 2 启动成功（sysid=2）' $drone5bAlive

# 等待两架飞机被发现
$drone5aDiscovered = Wait-DroneDiscovered 1 30
Check '场景5: sysid=1 飞机被发现（多机密钥分发）' $drone5aDiscovered

$drone5bDiscovered = Wait-DroneDiscovered 2 30
Check '场景5: sysid=2 飞机被发现（多机密钥分发）' $drone5bDiscovered

# 验证两架飞机的命令通信
if ($drone5aDiscovered) {
    try {
        $cmdResp5a = Send-Cmd 1 'arm'
        $cmd5aOk = $cmdResp5a.status -eq 'ok'
        Check '场景5: sysid=1 命令通信正常' $cmd5aOk
    } catch {
        Info "sysid=1 命令发送异常: $_"
        Check '场景5: sysid=1 命令通信正常' $false
    }
}

if ($drone5bDiscovered) {
    try {
        $cmdResp5b = Send-Cmd 2 'arm'
        $cmd5bOk = $cmdResp5b.status -eq 'ok'
        Check '场景5: sysid=2 命令通信正常' $cmd5bOk
    } catch {
        Info "sysid=2 命令发送异常: $_"
        Check '场景5: sysid=2 命令通信正常' $false
    }
}

# 检查 backend 日志中是否有密钥加载记录
$keyStoreLoaded = Search-Log $backendLog5 'KeyStore loaded|Loaded key for sysid'
Check '场景5: backend 日志可见密钥文件加载' $keyStoreLoaded

Stop-SceneProcesses

# 清理临时密钥文件
if (Test-Path $keyStoreFile) { Remove-Item $keyStoreFile -Force }

# ============================================================
# 场景6：签名未启用兼容
# backend(signing off) + drone-sim(signing off) 直连
# ============================================================
Step '场景6：签名未启用兼容（不启用签名 → 通信正常）'

$backendLog6 = Join-Path $env:TEMP "signing-e2e-backend-s6.log"
if (Test-Path $backendLog6) { Remove-Item $backendLog6 -Force }

$backend6 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $backendJar.FullName,
    "--aerofleet.drone-port=$DronePort",
    '--aerofleet.security.rbac-enabled=false',
    '--aerofleet.security.dev-mode=true'
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog6
Register-Cleanup { Stop-Process -Id $backend6.Id -Force -ErrorAction SilentlyContinue }
Info "backend PID=$($backend6.Id) (signing disabled)"

$backendUp6 = Wait-BackendUp $backendLog6
Check '场景6: cloud-backend 启动成功（签名未启用）' $backendUp6
if (-not $backendUp6) {
    Stop-SceneProcesses
    Write-Host '`nSIGNING E2E FAILED（场景6 backend 启动失败）' -ForegroundColor Red
    exit 1
}

# drone-sim 不启用签名（不传 --signing-key 参数）
$droneLog6 = Join-Path $env:TEMP "signing-e2e-drone-s6.log"
if (Test-Path $droneLog6) { Remove-Item $droneLog6 -Force }

$drone6 = Start-Process -FilePath $java -ArgumentList @(
    '-jar', $droneSimJar.FullName,
    '--port', $DronePort,
    '--sysid', '1',
    '--name', 'AF-SIGN-06'
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog6
Register-Cleanup { Stop-Process -Id $drone6.Id -Force -ErrorAction SilentlyContinue }
Info "drone-sim PID=$($drone6.Id) (signing disabled)"
Start-Sleep 2

$drone6Alive = -not $drone6.HasExited
Check '场景6: drone-sim 启动成功（签名未启用）' $drone6Alive

# 等待设备发现
$drone6Discovered = Wait-DroneDiscovered 1
Check '场景6: 签名未启用时设备发现正常' $drone6Discovered

if ($drone6Discovered) {
    # 验证命令通信
    try {
        $cmdResp6 = Send-Cmd 1 'arm'
        $cmd6Ok = $cmdResp6.status -eq 'ok'
        Check '场景6: 签名未启用时命令通信正常（ARM）' $cmd6Ok
    } catch {
        Info "命令发送异常: $_"
        Check '场景6: 签名未启用时命令通信正常（ARM）' $false
    }

    # 验证遥测
    try {
        $tel6 = Invoke-RestMethod "$Base/drones/1/telemetry" -TimeoutSec 5
        $tel6Ok = $null -ne $tel6
        Check '场景6: 签名未启用时遥测数据可读' $tel6Ok
    } catch {
        Info "遥测查询异常: $_"
        Check '场景6: 签名未启用时遥测数据可读' $false
    }
}

# 检查 backend 日志中不应有签名验证相关记录
Start-Sleep 3
$noSigningLog = -not (Search-Log $backendLog6 'signing.*verified|signing.*rejected|签名验证')
Check '场景6: 签名未启用时无签名验证日志（兼容行为）' $noSigningLog

Stop-SceneProcesses

# ============================================================
# 总结
# ============================================================
Step '签名端到端验证总结'

if ($Fail -eq 0) {
    Write-Host 'ALL SIGNING E2E TESTS PASSED' -ForegroundColor Green
    exit 0
} else {
    Write-Host 'SIGNING E2E TESTS FAILED' -ForegroundColor Red
    exit 1
}
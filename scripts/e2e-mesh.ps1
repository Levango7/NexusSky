# Mesh / 一跳静态中继端到端验证脚本（M0a Phase G，运行时场景编排）
#
# 依据：design.md §6.2 + spec.md FR-22~25
# 场景：GCS/后端 ↔ 中继(link-sim --relay) ↔ 远端飞机
#   - 中继 gcsPort=14541 面向 GCS/后端侧
#   - 中继 relayPort=14543 面向远端飞机侧
#   - uplink=lte-edge（GCS→飞机），downlink=wifi5（飞机→GCS）异构链路
#
# 断言目标（FR-22~25）：
#   FR-22 命令经中继到达远端飞机（GCS 发命令 → 中继转发 → 飞机收到）
#   FR-23 遥测反向到达 GCS（飞机发遥测 → 中继转发 → GCS 收到）
#   FR-24 中继透明性（转发前后帧字节完全一致）
#   FR-25 两段异构链路共存（中继统计日志反映 lte-edge / wifi5 各自画像）
#
# 前置检查（FR 5.6.3-1）：link-sim jar 存在、端口可用；缺失则 FAIL 并标注前置条件
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-mesh.ps1 [-Mode auto|simplified|full]
#   -Mode auto：优先完整版（后端+中继+drone-sim），失败回退简化版
#   -Mode simplified：仅中继 + PowerShell UDP 模拟 GCS/drone（默认，无外部依赖）
#   -Mode full：完整版（需要 cloud-backend jar + drone-sim jar）

param([string]$Mode = 'auto')

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Fail = 0
$script:CleanupActions = @()

# ---- 端口配置（design.md §6.2）----
$GcsPort = 14541      # 中继面向 GCS/后端侧
$RelayPort = 14543    # 中继面向远端飞机侧
$UplinkProfile = 'lte-edge'
$DownlinkProfile = 'wifi5'

# ---- 辅出辅助 ----
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

# ---- 字节比较（FR-24 透明性）----
function Compare-Bytes([byte[]]$a, [byte[]]$b) {
    if ($null -eq $a -or $null -eq $b) { return $false }
    if ($a.Length -ne $b.Length) { return $false }
    for ($i = 0; $i -lt $a.Length; $i++) {
        if ($a[$i] -ne $b[$i]) { return $false }
    }
    return $true
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
        if ($_.CommandLine -match 'link-sim.*--relay' -or $_.CommandLine -match 'drone-sim.*mesh-e2e') {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep 1
}

# ============================================================
# 前置检查（FR 5.6.3-1）
# ============================================================
Step '前置检查（FR 5.6.3-1：link-sim jar 存在、端口可用）'

$lsJar = Join-Path $Root 'link-sim\target\aerofleet-link-sim-0.1.0-SNAPSHOT.jar'
$jarOk = Test-Path $lsJar
Check 'link-sim jar 存在（relay 扩展基础）' $jarOk
if (-not $jarOk) {
    Info "jar 路径: $lsJar"
    Info '前置条件缺失：请先用 JDK17 构建 link-sim (mvn -pl link-sim -am package -DskipTests)'
    Write-Host '`nMESH E2E FAILED（前置条件缺失，非协议缺陷）' -ForegroundColor Red
    exit 1
}

$portGcsFree = Test-PortFree $GcsPort
$portRelayFree = Test-PortFree $RelayPort
Check "gcsPort $GcsPort 可用" $portGcsFree
Check "relayPort $RelayPort 可用" $portRelayFree
if (-not ($portGcsFree -and $portRelayFree)) {
    Info '前置条件缺失：端口被占用，请清理旧进程后重跑'
    Write-Host '`nMESH E2E FAILED（前置条件缺失：端口占用，非协议缺陷）' -ForegroundColor Red
    exit 1
}

# Java 探测 + 版本自检
if ($env:AF_JAVA) { $java = $env:AF_JAVA }
elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" }
else { $java = "java.exe" }
Assert-Java17 $java
Info "Java: $java"

Cleanup-OldProcesses

# ============================================================
# 简化版验证：PowerShell UDP 模拟 GCS + drone，验证中继转发行为
# 覆盖 FR-22/23/24/25 核心转发语义（不依赖后端/drone-sim）
# ============================================================
function Run-Simplified {
    Step '简化版：启动 link-sim --relay（uplink=lte-edge, downlink=wifi5）'

    $relayLog = Join-Path $env:TEMP "mesh-relay-simplified.log"
    if (Test-Path $relayLog) { Remove-Item $relayLog -Force }

    $relay = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $lsJar,
        '--relay',
        '--gcs-port', $GcsPort,
        '--relay-port', $RelayPort,
        '--uplink-profile', $UplinkProfile,
        '--downlink-profile', $DownlinkProfile
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $relayLog
    Register-Cleanup { Stop-Process -Id $relay.Id -Force -ErrorAction SilentlyContinue }
    Info "relay PID=$($relay.Id), log=$relayLog"
    Start-Sleep 2  # 等待中继绑定双 socket

    # 检查中继进程存活
    $relayAlive = -not $relay.HasExited
    Check '中继进程启动成功' $relayAlive
    if (-not $relayAlive) {
        Info '中继启动失败，查看日志:'
        if (Test-Path $relayLog) { Get-Content $relayLog | ForEach-Object { Info $_ } }
        return
    }

    Step 'FR-22/24：命令经中继到达远端飞机（上行 GCS→drone，字节一致）'

    # 模拟远端飞机：绑定 14555，先向中继 relayPort 发遥测引导地址学习
    $dronePort = 14555
    $droneSock = [System.Net.Sockets.UdpClient]::new($dronePort)
    $droneSock.Client.ReceiveTimeout = 5000
    Register-Cleanup { $droneSock.Close() }
    $relayEP = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Loopback, $RelayPort)

    # 模拟 GCS：绑定 14550，向中继 gcsPort 发命令
    $gcsPort = 14550
    $gcsSock = [System.Net.Sockets.UdpClient]::new($gcsPort)
    $gcsSock.Client.ReceiveTimeout = 5000
    Register-Cleanup { $gcsSock.Close() }
    $gcsEP = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Loopback, $GcsPort)

    # 构造测试帧：模拟 MAVLink 命令帧（含 sysid=2 的标识字节）
    # 字节模式：[0xFD=MAVLink v2 magic, sysid=2, compid=1, msgId=76=COMMAND_LONG, ...payload...]
    $cmdBytes = [byte[]](0xFD, 0x02, 0x01, 0x4C, 0x01, 0x00, 0x00, 0x00, 0x00, 0x16, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    $telemetryBytes = [byte[]](0xFD, 0x02, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x06, 0x08, 0x22, 0x00, 0x00, 0x2D, 0x4E, 0x56, 0x00, 0x00, 0x00, 0x00)

    # 步骤1：drone 先向中继 relayPort 发遥测 → 中继学习 droneAddr
    # 注意：此时 gcsAddr 未学习，遥测会被静默丢弃（FR 5.2.3-1），但 droneAddr 已学习
    Info '步骤1: drone 向中继 relayPort 发遥测（引导 droneAddr 学习）'
    $droneSock.Send($telemetryBytes, $telemetryBytes.Length, $relayEP) | Out-Null
    Start-Sleep 1

    # 步骤2：GCS 向中继 gcsPort 发命令 → 中继学习 gcsAddr + 转发到 drone
    Info '步骤2: GCS 向中继 gcsPort 发命令（中继学习 gcsAddr 并转发到 drone）'
    $gcsSock.Send($cmdBytes, $cmdBytes.Length, $gcsEP) | Out-Null
    Start-Sleep 1

    # 步骤3：drone 收到经中继转发的命令（FR-22 命令经中继到达）
    $droneRecvEP = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
    $cmdReceived = $null
    try {
        $cmdReceived = $droneSock.Receive([ref]$droneRecvEP)
    } catch {
        Info "drone 接收超时: $_"
    }

    $cmdArrived = $null -ne $cmdReceived -and $cmdReceived.Length -gt 0
    Check 'FR-22: 命令经中继到达远端飞机' $cmdArrived

    # FR-24：字节一致（透明性）
    $cmdBytesMatch = $cmdArrived -and (Compare-Bytes $cmdReceived $cmdBytes)
    Check 'FR-24: 命令帧字节完全一致（中继透明转发）' $cmdBytesMatch
    if ($cmdArrived) {
        Info "命令帧: 发送 $($cmdBytes.Length) 字节, 收到 $($cmdReceived.Length) 字节"
        Info "来源: $($droneRecvEP.Address):$($droneRecvEP.Port)（应为中继 relayPort $RelayPort）"
    }

    Step 'FR-23/24：遥测反向到达 GCS（下行 drone→GCS，字节一致）'

    # 步骤4：drone 向中继 relayPort 发遥测 → 中继转发到 GCS
    Info '步骤4: drone 向中继 relayPort 发遥测（中继转发到 GCS）'
    $droneSock.Send($telemetryBytes, $telemetryBytes.Length, $relayEP) | Out-Null
    Start-Sleep 1

    # 步骤5：GCS 收到经中继转发的遥测（FR-23 遥测反向到达）
    $gcsRecvEP = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
    $telemetryReceived = $null
    try {
        $telemetryReceived = $gcsSock.Receive([ref]$gcsRecvEP)
    } catch {
        Info "GCS 接收超时: $_"
    }

    $telemetryArrived = $null -ne $telemetryReceived -and $telemetryReceived.Length -gt 0
    Check 'FR-23: 遥测经中继反向到达 GCS' $telemetryArrived

    # FR-24：字节一致
    $telemetryBytesMatch = $telemetryArrived -and (Compare-Bytes $telemetryReceived $telemetryBytes)
    Check 'FR-24: 遥测帧字节完全一致（sysid 不被篡改）' $telemetryBytesMatch
    if ($telemetryArrived) {
        Info "遥测帧: 发送 $($telemetryBytes.Length) 字节, 收到 $($telemetryReceived.Length) 字节"
        Info "来源: $($gcsRecvEP.Address):$($gcsRecvEP.Port)（应为中继 gcsPort $GcsPort）"
        # 验证 sysid 字节保持（第2字节 = 0x02 = sysid 2）
        if ($telemetryReceived.Length -ge 2) {
            $sysidPreserved = $telemetryReceived[1] -eq 0x02
            Check 'FR-23: 远端飞机 sysid=2 保持（非中继身份替换）' $sysidPreserved
        }
    }

    Step 'FR-25：两段异构链路共存（中继统计日志反映各自画像）'

    # 多发几轮包让统计累计
    for ($i = 0; $i -lt 5; $i++) {
        $gcsSock.Send($cmdBytes, $cmdBytes.Length, $gcsEP) | Out-Null
        Start-Sleep -Milliseconds 200
        $droneSock.Send($telemetryBytes, $telemetryBytes.Length, $relayEP) | Out-Null
        Start-Sleep -Milliseconds 200
    }

    # 等待 10s 统计周期
    Info '等待 11s 让中继输出 10s 周期统计日志...'
    Start-Sleep 11

    # 读取中继日志检查统计输出
    $statsOk = $false
    $hasUplinkStats = $false
    $hasDownlinkStats = $false
    if (Test-Path $relayLog) {
        $logContent = Get-Content $relayLog -ErrorAction SilentlyContinue
        foreach ($line in $logContent) {
            if ($line -match 'up\(gcs->drone\)') { $hasUplinkStats = $true; Info "上行统计: $line" }
            if ($line -match 'down\(drone->gcs\)') { $hasDownlinkStats = $true; Info "下行统计: $line" }
        }
    }
    $statsOk = $hasUplinkStats -and $hasDownlinkStats
    Check 'FR-25: 中继统计日志输出 up(gcs->drone) 行（lte-edge 画像）' $hasUplinkStats
    Check 'FR-25: 中继统计日志输出 down(drone->gcs) 行（wifi5 画像）' $hasDownlinkStats
    if ($statsOk) {
        Info "两段异构链路共存验证通过：uplink=$UplinkProfile, downlink=$DownlinkProfile"
    }

    # FR-12 对端唯一性（learn-once）：第三方源不改变已学习对端
    Step 'FR-12：对端唯一性（learn-once，第三方源不改变转发目标）'
    $thirdPartySock = [System.Net.Sockets.UdpClient]::new(14566)
    $thirdPartySock.Client.ReceiveTimeout = 2000
    Register-Cleanup { $thirdPartySock.Close() }
    # 第三方向 gcsPort 发包（伪装 GCS），中继应保持原 gcsAddr 不变
    $thirdPartyBytes = [byte[]](0xFD, 0xFF, 0x01, 0x00)
    $thirdPartySock.Send($thirdPartyBytes, $thirdPartyBytes.Length, $gcsEP) | Out-Null
    Start-Sleep 1
    # GCS 再发命令，drone 应仍收到（gcsAddr 未被第三方覆盖）
    $gcsSock.Send($cmdBytes, $cmdBytes.Length, $gcsEP) | Out-Null
    Start-Sleep 1
    $fr12Ok = $false
    try {
        $fr12Recv = $droneSock.Receive([ref]$droneRecvEP)
        $fr12Ok = (Compare-Bytes $fr12Recv $cmdBytes)
    } catch { }
    Check 'FR-12: 第三方源不改变已学习 gcsAddr（命令仍经中继到达 drone）' $fr12Ok
}

# ============================================================
# 完整版验证：cloud-backend + link-sim --relay + drone-sim
# 覆盖 FR-23 sysid 保持（后端 DeviceRegistry 以真实 sysid 注册）
# ============================================================
function Run-Full {
    Step '完整版：启动 cloud-backend + link-sim --relay + drone-sim'

    $backendJar = Join-Path $Root 'cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'
    $droneSimJar = Join-Path $Root 'drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar'

    $backendOk = Test-Path $backendJar
    $droneSimOk = Test-Path $droneSimJar
    Check 'cloud-backend jar 存在' $backendOk
    Check 'drone-sim jar 存在' $droneSimOk

    if (-not ($backendOk -and $droneSimOk)) {
        Info '完整版前置缺失：缺少 cloud-backend 或 drone-sim jar'
        Info '回退到简化版验证'
        return $false
    }

    $Base = 'http://localhost:8080/api/v1'

    # 检查后端是否已在运行
    $backendRunning = $false
    try {
        $resp = Invoke-WebRequest -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 2 -UseBasicParsing
        $backendRunning = $resp.StatusCode -eq 200
    } catch { }

    if (-not $backendRunning) {
        Info '启动 cloud-backend（discovery 指向中继 gcsPort=14541）...'
        $backendLog = Join-Path $env:TEMP "mesh-backend.log"
        if (Test-Path $backendLog) { Remove-Item $backendLog -Force }
        # 后端配置：drone-port 指向中继 gcsPort，drone-extra-ports 含中继 relayPort
        $backend = Start-Process -FilePath $java -ArgumentList @(
            '-jar', $backendJar,
            "--aerofleet.drone-port=$GcsPort",
            "--aerofleet.drone-extra-ports=$RelayPort"
        ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendLog
        Register-Cleanup { Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue }
        Info "backend PID=$($backend.Id)"

        # 等待后端启动
        $up = $false
        for ($i = 0; $i -lt 30; $i++) {
            Start-Sleep 1
            try {
                $r = Invoke-WebRequest -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 2 -UseBasicParsing
                if ($r.StatusCode -eq 200) { $up = $true; break }
            } catch { }
        }
        Check 'cloud-backend 启动成功' $up
        if (-not $up) { Info '后端启动失败，回退简化版'; return $false }
    } else {
        Info 'cloud-backend 已在运行'
    }

    # 启动中继
    $relayLog = Join-Path $env:TEMP "mesh-relay-full.log"
    if (Test-Path $relayLog) { Remove-Item $relayLog -Force }
    $relay = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $lsJar,
        '--relay',
        '--gcs-port', $GcsPort,
        '--relay-port', $RelayPort,
        '--uplink-profile', $UplinkProfile,
        '--downlink-profile', $DownlinkProfile
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $relayLog
    Register-Cleanup { Stop-Process -Id $relay.Id -Force -ErrorAction SilentlyContinue }
    Info "relay PID=$($relay.Id)"
    Start-Sleep 2

    # 启动远端 drone-sim（sysid=2，绑定 14555）
    # 注意：drone-sim 是被动的，需要后端经中继发心跳引导
    # drone-sim 绑定 14555，后端 drone-extra-ports 含 14555 会直接发现（绕过中继）
    # 为强制走中继，drone-sim 绑定中继 relayPort 不可行（端口冲突）
    # 因此完整版验证：drone-sim 绑定 14555，后端通过 drone-extra-ports=14555 直接发现
    # 中继场景需 drone-sim 主动向中继发包（当前 drone-sim 不支持 --gcs-host 参数）
    # → 完整版受限于 drone-sim 被动性，记录为环境限制
    $droneSysid = 2
    $droneBindPort = 14555
    $droneLog = Join-Path $env:TEMP "mesh-drone.log"
    if (Test-Path $droneLog) { Remove-Item $droneLog -Force }
    $drone = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $droneSimJar,
        '--port', $droneBindPort,
        '--sysid', $droneSysid,
        '--name', 'AF-MESH-02'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $droneLog
    Register-Cleanup { Stop-Process -Id $drone.Id -Force -ErrorAction SilentlyContinue }
    Info "drone-sim PID=$($drone.Id), sysid=$droneSysid, port=$droneBindPort"
    Start-Sleep 2

    # 由于 drone-sim 被动等待 GCS 包，且后端 drone-extra-ports 需含 14555 才能发现
    # 当前后端配置 drone-extra-ports=14543（中继 relayPort），不含 14555
    # → drone-sim 无法被发现，这是 drone-sim 被动性的已知限制
    # 需要后端 drone-extra-ports 含 drone-sim 端口，或 drone-sim 支持 --gcs-host 主动发
    Info '完整版限制：drone-sim 为被动模式，需后端 drone-extra-ports 含 drone-sim 端口才能发现'
    Info '当前后端 drone-extra-ports 配置为中继端口，drone-sim 无法经中继被发现'
    Info '这需要 drone-sim 增加 --gcs-host 参数支持主动向中继发包（超出 e2e 脚本范围）'

    # 尝试：临时让后端直接发现 drone-sim（验证后端 sysid 注册能力）
    # 用 PowerShell 引导：发一个 UDP 包到 drone-sim，源地址伪装成中继 relayPort
    # 但普通 UDP socket 不能伪装源端口，因此无法引导 drone-sim 向中继发

    # 验证后端可达性
    $backendReachable = $false
    try {
        $drones = Invoke-RestMethod "$Base/drones" -TimeoutSec 5
        $backendReachable = $true
        Info "后端 /drones 返回 $($drones.Count) 个设备"
    } catch {
        Info "后端 /drones 不可达: $_"
    }
    Check '后端 REST API 可达' $backendReachable

    if (-not $backendReachable) { return $false }

    # 等待发现（drone-sim 可能无法经中继被发现，记录为环境限制）
    Start-Sleep 5
    $d = $null
    try {
        $allDrones = Invoke-RestMethod "$Base/drones" -TimeoutSec 5
        $d = $allDrones | Where-Object { $_.sysid -eq $droneSysid }
    } catch { }

    if ($d -and $d.online) {
        Check "FR-23: 远端飞机 sysid=$droneSysid 经中继到达后端并注册在线" $true
        Info "DeviceRegistry 注册: sysid=$($d.sysid), online=$($d.online)"
        return $true
    } else {
        Info "drone-sim sysid=$droneSysid 未在后端注册（环境限制：drone-sim 被动模式无法经中继被发现）"
        Check 'FR-23: 远端飞机经中继注册（完整版，受 drone-sim 被动性限制）' $false
        return $false
    }
}

# ============================================================
# 主流程
# ============================================================
$modeRun = $Mode.ToLower()
Info "运行模式: $modeRun（gcsPort=$GcsPort, relayPort=$RelayPort, uplink=$UplinkProfile, downlink=$DownlinkProfile）"

$fullResult = $false
if ($modeRun -eq 'full') {
    $fullResult = Run-Full
    if (-not $fullResult) {
        Info '完整版未通过，继续运行简化版'
        Run-Simplified
    }
} elseif ($modeRun -eq 'simplified') {
    Run-Simplified
} else {
    # auto：优先完整版，失败回退简化版
    $fullResult = Run-Full
    if (-not $fullResult) {
        Info '完整版未通过或前置缺失，回退简化版验证'
        Invoke-Cleanup
        Run-Simplified
    }
}

# ---- 清理 ----
Invoke-Cleanup

# ---- 结果 ----
Write-Host ''
if ($Fail -eq 0) {
    Write-Host 'MESH E2E PASSED（FR-22~25 端到端验证通过）' -ForegroundColor Green
    exit 0
} else {
    Write-Host 'MESH E2E FAILED（部分断言未通过，见上方 FAIL 行）' -ForegroundColor Red
    exit 1
}

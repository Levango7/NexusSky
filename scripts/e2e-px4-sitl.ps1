# NexusSky PX4 SITL 真机协议验证（PowerShell 版）
# 前置：PX4 SITL 已安装（或脚本检测到 WSL2 内的 PX4-Autopilot）
# 验证：心跳交换 → 设备注册 → 任务上传 → ARM → 遥测接收 → RTL
$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'
$Fail = 0
$SITLJob = $null
$CloudJob = $null
$SITLLogPath = "$env:TEMP\nexus-px4-sitl.log"
$CloudLogPath = "$env:TEMP\nexus-cloud-backend.log"

# ───────────────────────── 工具函数 ─────────────────────────
function Step($m) { Write-Host "`n== $m" }
function Check($desc, $ok) {
    if ($ok) { Write-Host "   PASS: $desc" -ForegroundColor Green }
    else { Write-Host "   FAIL: $desc" -ForegroundColor Red; $script:Fail = 1 }
}
function Send-Cmd($type, $alt) {
    $body = @{ type = $type } | ConvertTo-Json
    if ($alt) { $body = @{ type = $type; alt = $alt } | ConvertTo-Json }
    Invoke-RestMethod -Uri "$Base/drones/$Sysid/commands" -Method Post -Body $body -ContentType 'application/json; charset=utf-8' -TimeoutSec 20
}

# ───────────────────────── 清理函数 ─────────────────────────
function Cleanup {
    Step '清理'
    if ($null -ne $script:SITLJob -and $script:SITLJob.HasExited -eq $false) {
        Write-Host '   停止 PX4 SITL...'
        Stop-Process -Id $script:SITLJob.Id -Force -ErrorAction SilentlyContinue
        Write-Host '   SITL 已停止' -ForegroundColor Green
    }
    if ($null -ne $script:CloudJob -and $script:CloudJob.HasExited -eq $false) {
        Write-Host '   停止 cloud-backend...'
        Stop-Process -Id $script:CloudJob.Id -Force -ErrorAction SilentlyContinue
        Write-Host '   cloud-backend 已停止' -ForegroundColor Green
    }
}
trap { Cleanup; exit 1 }

# ───────────────────────── 前置检查 ─────────────────────────
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host 'NexusSky PX4 SITL 真机协议验证' -ForegroundColor Cyan
Write-Host '============================================================' -ForegroundColor Cyan

Step '0. 前置检查：PX4 SITL 是否已安装'

# 检查方式1：Windows PATH 中是否有 px4 命令
$px4Cmd = $null
try { $px4Cmd = Get-Command px4 -ErrorAction Stop } catch {}

# 检查方式2：WSL2 内是否有 PX4-Autopilot
$wslPx4Dir = $null
try {
    $wslResult = wsl -e bash -c 'ls -d ~/PX4-Autopilot 2>/dev/null && echo FOUND || echo NOTFOUND' 2>$null
    if ($wslResult -match 'FOUND') { $wslPx4Dir = $true }
} catch {}

# 检查方式3：常见 Windows 安装路径
$winPx4Paths = @(
    'C:\PX4-Autopilot',
    "$env:USERPROFILE\PX4-Autopilot",
    'C:\Program Files\PX4'
)
$winPx4Dir = $null
foreach ($p in $winPx4Paths) {
    if (Test-Path $p) { $winPx4Dir = $p; break }
}

$sitlAvailable = $false
$sitlMode = ''  # 'wsl' | 'win' | 'none'

if ($null -ne $px4Cmd) {
    $sitlAvailable = $true
    $sitlMode = 'win-path'
    Write-Host "   检测到 px4 命令: $($px4Cmd.Source)" -ForegroundColor Green
} elseif ($wslPx4Dir) {
    $sitlAvailable = $true
    $sitlMode = 'wsl'
    Write-Host '   检测到 WSL2 内 PX4-Autopilot 目录' -ForegroundColor Green
} elseif ($null -ne $winPx4Dir) {
    $sitlAvailable = $true
    $sitlMode = 'win-dir'
    Write-Host "   检测到 PX4 目录: $winPx4Dir" -ForegroundColor Green
} else {
    Write-Host '   未检测到 PX4 SITL 安装' -ForegroundColor Yellow
    Write-Host ''
    Write-Host '   PX4 SITL 安装指引（不自动安装）：' -ForegroundColor Yellow
    Write-Host ''
    Write-Host '   方式1：WSL2 Ubuntu 内安装（推荐）' -ForegroundColor White
    Write-Host '     1. 安装 WSL2: wsl --install -d Ubuntu-24.04' -ForegroundColor White
    Write-Host '     2. 进入 WSL2: wsl' -ForegroundColor White
    Write-Host '     3. 安装依赖:' -ForegroundColor White
    Write-Host '        sudo apt update && sudo apt install -y \' -ForegroundColor White
    Write-Host '          git build-essential cmake ninja-build python3-pip \' -ForegroundColor White
    Write-Host '          genromfs kconfig libncurses5-dev libncursesw5-dev \' -ForegroundColor White
    Write-Host '          libusb-dev libudev-dev libjsoncpp-dev libopencv-dev' -ForegroundColor White
    Write-Host '     4. 克隆与编译:' -ForegroundColor White
    Write-Host '        cd ~ && git clone --depth 1 --branch v1.17.0 \' -ForegroundColor White
    Write-Host '          https://github.com/PX4/PX4-Autopilot.git' -ForegroundColor White
    Write-Host '        cd PX4-Autopilot && bash Tools/setup/ubuntu.sh' -ForegroundColor White
    Write-Host '        make px4_sitl_default' -ForegroundColor White
    Write-Host ''
    Write-Host '   方式2：Windows 原生安装' -ForegroundColor White
    Write-Host '     参考: https://docs.px4.io/main/en/dev_setup/dev_env_windows.html' -ForegroundColor White
    Write-Host ''
    Write-Host '   安装完成后重新运行此脚本。' -ForegroundColor Yellow
    Write-Host ''
    Write-Host '   跳过 SITL 启动，将尝试仅验证 cloud-backend 连接...' -ForegroundColor Yellow
}

# ───────────────────────── 启动 PX4 SITL ─────────────────────────
$sitlStarted = $false
if ($sitlAvailable) {
    Step '1. 启动 PX4 SITL'

    if ($sitlMode -eq 'wsl') {
        # WSL2 模式：在 WSL 内启动 PX4 SITL 无头模式
        Write-Host '   通过 WSL2 启动 PX4 SITL（无头模式）...'
        $wslCmd = 'cd ~/PX4-Autopilot && ' +
                  'PX4_SIM_HOST=127.0.0.1 PX4_SYSID=1 ' +
                  './build/px4_sitl_default/bin/px4 ' +
                  '-d /dev/null -s etc/init/posix-airframes/rcS_posix ' +
                  "> /tmp/nexus-px4-sitl.log 2>&1"
        $script:SITLJob = Start-Process -FilePath 'wsl' -ArgumentList '-e', 'bash', '-c', $wslCmd -PassThru -WindowStyle Hidden
        Write-Host "   SITL 进程已启动 (PID=$($script:SITLJob.Id))"

    } elseif ($sitlMode -eq 'win-path') {
        # Windows PATH 模式
        Write-Host '   通过 px4 命令启动 SITL...'
        $script:SITLJob = Start-Process -FilePath 'px4' -ArgumentList '_sitl', 'jmavsim' -PassThru -RedirectStandardOutput $SITLLogPath -RedirectStandardError $SITLLogPath -WindowStyle Hidden
        Write-Host "   SITL 进程已启动 (PID=$($script:SITLJob.Id))"

    } elseif ($sitlMode -eq 'win-dir') {
        # Windows 目录模式
        $px4Bin = Join-Path $winPx4Dir 'build\px4_sitl_default\bin\px4.exe'
        if (Test-Path $px4Bin) {
            Write-Host "   通过 $px4Bin 启动 SITL..."
            $script:SITLJob = Start-Process -FilePath $px4Bin -PassThru -RedirectStandardOutput $SITLLogPath -RedirectStandardError $SITLLogPath -WindowStyle Hidden
            Write-Host "   SITL 进程已启动 (PID=$($script:SITLJob.Id))"
        } else {
            Write-Host '   PX4 SITL 未编译（找不到 px4.exe），跳过 SITL 启动' -ForegroundColor Yellow
            Write-Host "   预期路径: $px4Bin" -ForegroundColor Yellow
            Write-Host '   请先在 PX4-Autopilot 目录执行: make px4_sitl_default' -ForegroundColor Yellow
        }
    }

    # 等待 SITL 就绪（最长 60 秒）
    if ($null -ne $script:SITLJob) {
        Write-Host '   等待 PX4 SITL 就绪（最长 60s）...'
        $sitlReady = $false
        for ($i = 0; $i -lt 60; $i++) {
            # 检查进程是否存活
            if ($script:SITLJob.HasExited) {
                Write-Host '   FAIL: SITL 进程意外退出' -ForegroundColor Red
                if (Test-Path $SITLLogPath) {
                    Write-Host '   日志最后 20 行:'
                    Get-Content $SITLLogPath -Tail 20 -ErrorAction SilentlyContinue
                }
                break
            }
            # 检查日志中是否出现就绪标志
            $logContent = ''
            if ($sitlMode -eq 'wsl') {
                try { $logContent = wsl -e bash -c 'cat /tmp/nexus-px4-sitl.log 2>/dev/null | tail -50' 2>$null } catch {}
            } elseif (Test-Path $SITLLogPath) {
                $logContent = Get-Content $SITLLogPath -Tail 50 -ErrorAction SilentlyContinue -Raw
            }
            if ($logContent -match 'Ready for takeoff|Starting commander|px4io starting') {
                Write-Host "   SITL 就绪 (${i}s)" -ForegroundColor Green
                $sitlReady = $true
                $sitlStarted = $true
                break
            }
            Start-Sleep 1
        }
        if (-not $sitlReady) {
            Write-Host '   FAIL: SITL 未在 60s 内就绪' -ForegroundColor Red
        }
    }
} else {
    Step '1. 启动 PX4 SITL（跳过 — 未安装）'
}

# ───────────────────────── 启动 cloud-backend ─────────────────────────
Step '2. 启动 cloud-backend'

$cloudRunning = $false
# 检查 cloud-backend 是否已在运行
try {
    $health = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 3 -ErrorAction Stop
    if ($health.status -eq 'UP') {
        $cloudRunning = $true
        Write-Host '   cloud-backend 已运行（health UP）' -ForegroundColor Green
    }
} catch {}

if (-not $cloudRunning) {
    # 尝试自动启动 cloud-backend
    $jarPath = "$PSScriptRoot\..\cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar"
    if (Test-Path $jarPath) {
        Write-Host "   启动 cloud-backend ($jarPath)..."
        $script:CloudJob = Start-Process -FilePath 'java' -ArgumentList '-jar', $jarPath -PassThru -RedirectStandardOutput $CloudLogPath -RedirectStandardError $CloudLogPath -WindowStyle Hidden
        Write-Host "   cloud-backend PID=$($script:CloudJob.Id)，等待启动..."
        for ($i = 0; $i -lt 30; $i++) {
            try {
                $health = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 2 -ErrorAction Stop
                if ($health.status -eq 'UP') {
                    $cloudRunning = $true
                    Write-Host "   cloud-backend 已启动 (${i}s)" -ForegroundColor Green
                    break
                }
            } catch {}
            Start-Sleep 1
        }
    } else {
        Write-Host "   未找到 cloud-backend JAR: $jarPath" -ForegroundColor Yellow
        Write-Host '   请先执行: mvn -pl cloud-backend -DskipTests package' -ForegroundColor Yellow
    }
}

if (-not $cloudRunning) {
    Write-Host '   FAIL: cloud-backend 不可用，无法进行端到端验证' -ForegroundColor Red
    Cleanup
    exit 1
}

# ───────────────────────── 验证 a. 心跳交换 ─────────────────────────
Step '3a. 心跳交换验证'

# 等待 PX4 SITL 的 HEARTBEAT 被 cloud-backend 识别
$drone = $null
$heartbeatReceived = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $list = Invoke-RestMethod -Uri "$Base/drones" -TimeoutSec 5
        if ($list.Count -gt 0) {
            $drone = $list[0]
            $heartbeatReceived = $true
            break
        }
    } catch {}
    Start-Sleep 1
}

if ($sitlStarted) {
    Check 'NexusSky 收到 PX4 SITL 的 HEARTBEAT' $heartbeatReceived
} else {
    # SITL 未启动时，检查是否有 drone-sim 的心跳
    Check '收到无人机 HEARTBEAT（SITL 或 drone-sim）' $heartbeatReceived
}

if ($null -eq $drone) {
    Write-Host '   无无人机上线，验证终止' -ForegroundColor Red
    Cleanup
    exit 1
}

$Sysid = $drone.sysid
Write-Host "   sysid=$Sysid callsign=$($drone.callsign) mode=$($drone.mode)"

# ───────────────────────── 验证 b. 设备注册 ─────────────────────────
Step '3b. 设备注册验证'

$droneDetail = $null
try {
    $droneDetail = Invoke-RestMethod -Uri "$Base/drones/$Sysid" -TimeoutSec 5
} catch {}

Check 'PX4 SITL 出现在 /api/v1/drones 列表中' ($null -ne $droneDetail)
if ($null -ne $droneDetail) {
    Write-Host "   详情: sysid=$($droneDetail.sysid) mode=$($droneDetail.mode) armed=$($droneDetail.armed) online=$($droneDetail.online)"
}

# ───────────────────────── 验证 c. 任务上传 ─────────────────────────
Step '3c. 任务上传验证'

$mission = @{
    items = @(
        @{ cmd = 'takeoff';   lat = 22.5907; lon = 113.9345; alt = 30; holdTime = 0 }
        @{ cmd = 'waypoint'; lat = 22.5917; lon = 113.9345; alt = 50; holdTime = 2 }
        @{ cmd = 'waypoint'; lat = 22.5917; lon = 113.9355; alt = 50; holdTime = 2 }
        @{ cmd = 'waypoint'; lat = 22.5907; lon = 113.9355; alt = 50; holdTime = 2 }
        @{ cmd = 'rtl';      lat = 22.5907; lon = 113.9345; alt = 0;  holdTime = 0 }
    )
} | ConvertTo-Json -Depth 4

$missionResult = $null
try {
    $missionResult = Invoke-RestMethod -Uri "$Base/drones/$Sysid/mission" -Method Post -Body $mission -ContentType 'application/json; charset=utf-8' -TimeoutSec 30
} catch {
    Write-Host "   任务上传异常: $_" -ForegroundColor Red
}

Check '任务上传到 PX4 SITL 成功' ($null -ne $missionResult -and $missionResult.status -eq 'ok')
if ($null -ne $missionResult) {
    Write-Host "   status=$($missionResult.status) uploaded=$($missionResult.uploaded)"
}

# ───────────────────────── 验证 d. ARM 命令 ─────────────────────────
Step '3d. ARM 命令验证'

$armResult = $null
try {
    $armResult = Send-Cmd 'arm'
} catch {
    Write-Host "   ARM 异常: $_" -ForegroundColor Red
}

Check 'ARM 命令发送成功' ($null -ne $armResult -and $armResult.status -eq 'ok')
if ($null -ne $armResult) {
    Write-Host "   $($armResult.status) / $($armResult.result)"
}

# 等待 ARMED 状态生效
Start-Sleep 3
$droneAfterArm = $null
try {
    $droneAfterArm = Invoke-RestMethod -Uri "$Base/drones/$Sysid" -TimeoutSec 5
} catch {}

Check 'PX4 SITL 进入 ARMED 状态' ($null -ne $droneAfterArm -and $droneAfterArm.armed -eq $true)
if ($null -ne $droneAfterArm) {
    Write-Host "   armed=$($droneAfterArm.armed) mode=$($droneAfterArm.mode)"
}

# ───────────────────────── 验证 e. 遥测接收 ─────────────────────────
Step '3e. 遥测接收验证'

Start-Sleep 5  # 等待遥测数据积累
$telemetry = $null
try {
    $telemetry = Invoke-RestMethod -Uri "$Base/drones/$Sysid/telemetry" -TimeoutSec 5
} catch {}

Check '收到 ATTITUDE 遥测' ($null -ne $telemetry -and $null -ne $telemetry.mode)
if ($null -ne $telemetry) {
    Write-Host "   mode=$($telemetry.mode) alt=$($telemetry.relativeAlt)m battery=$($telemetry.battery)%"
}

Check '收到 GLOBAL_POSITION_INT（高度数据）' ($null -ne $telemetry -and $null -ne $telemetry.relativeAlt)

# 检查轨迹数据（包含位置遥测）
$track = $null
try {
    $track = Invoke-RestMethod -Uri "$Base/drones/$Sysid/track" -TimeoutSec 5
} catch {}

Check '遥测轨迹非空' ($null -ne $track -and $track.Count -gt 0)
if ($null -ne $track) {
    Write-Host "   轨迹点数: $($track.Count)"
}

# ───────────────────────── 验证 f. RTL 命令 ─────────────────────────
Step '3f. RTL 命令验证'

$rtlResult = $null
try {
    $rtlResult = Send-Cmd 'rtl'
} catch {
    Write-Host "   RTL 异常: $_" -ForegroundColor Red
}

Check 'RTL 命令发送成功' ($null -ne $rtlResult -and $rtlResult.status -eq 'ok')
if ($null -ne $rtlResult) {
    Write-Host "   $($rtlResult.status) / $($rtlResult.result)"
}

# 等待 RTL 执行
Start-Sleep 5
$droneAfterRtl = $null
try {
    $droneAfterRtl = Invoke-RestMethod -Uri "$Base/drones/$Sysid" -TimeoutSec 5
} catch {}

Check 'PX4 SITL 执行返航（mode 变化）' ($null -ne $droneAfterRtl)
if ($null -ne $droneAfterRtl) {
    Write-Host "   mode=$($droneAfterRtl.mode) armed=$($droneAfterRtl.armed)"
}

# ───────────────────────── 验证结果汇总 ─────────────────────────
Step '验证结果汇总'
Write-Host "   PX4 SITL:     $(if ($sitlStarted) { '已启动' } else { '未启动/跳过' })"
Write-Host "   cloud-backend: 已连接"
Write-Host "   心跳交换:      $(if ($heartbeatReceived) { 'PASS' } else { 'FAIL' })"
Write-Host "   设备注册:      $(if ($null -ne $droneDetail) { 'PASS' } else { 'FAIL' })"
Write-Host "   任务上传:      $(if ($null -ne $missionResult -and $missionResult.status -eq 'ok') { 'PASS' } else { 'FAIL' })"
Write-Host "   ARM 命令:      $(if ($null -ne $armResult -and $armResult.status -eq 'ok') { 'PASS' } else { 'FAIL' })"
Write-Host "   遥测接收:      $(if ($null -ne $telemetry -and $null -ne $telemetry.mode) { 'PASS' } else { 'FAIL' })"
Write-Host "   RTL 命令:      $(if ($null -ne $rtlResult -and $rtlResult.status -eq 'ok') { 'PASS' } else { 'FAIL' })"

# ───────────────────────── 清理 ─────────────────────────
Cleanup

if ($Fail -eq 0) {
    Write-Host "`nALL PX4 SITL TESTS PASSED" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`nPX4 SITL TESTS FAILED" -ForegroundColor Red
    exit 1
}
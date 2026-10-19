# RTK 高精度链路端到端验证脚本（F6）
#
# 依据：ROADMAP F6——遥测引入精度等级字段；RTK 固定/浮点解状态上报
# 场景：cloud-backend + 3 台 drone-sim（--rtk fixed / --rtk float / 缺省）
#
# 断言目标：
#   R1  --rtk fixed 机：/drones status.rtkStatus = RTK_FIXED
#   R2  --rtk float 机：rtkStatus = RTK_FLOAT
#   R3  缺省机：rtkStatus = STANDALONE（既有行为不变）
#   R4  GPS_RAW_INT fixType 直连口径：fixed=6 / float=5 / 缺省=3
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-rtk.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-rtk.ps1 -SkipBuild

param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
# 跨平台公共库（B2 批次接 CI 时引入）：java 探测 / jar 路径 / 临时目录 / 按标记清残留
# 以及下面用的 Get-E2eSysidKey（sysid 作为 hashtable 键的归一化：JSON 整数在 5.1 是 Int32、
# 在 pwsh 7 是 Int64，而 hashtable 的键类型敏感，两边得用同一种形式）。
# 公共库全是函数定义，dot-source 不会引入任何副作用。
. (Join-Path $PSScriptRoot 'e2e-common.ps1')
$TempDir = Get-E2eTempDir   # Linux runner 上 $env:TEMP 常未设置，不能直接用
$Fail = 0
$script:CleanupActions = @()

$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 18099 }
$Base = "http://localhost:$BackendPort/api/v1"
# jar 路径走 Get-E2eJar：反斜杠字面量在 Linux 上不是分隔符，Test-Path 恒 false
$SimJar = Get-E2eJar -Root $Root -Module 'drone-sim' -Jar 'aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar'
$BackendJar = Get-E2eJar -Root $Root -Module 'cloud-backend' -Jar 'aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'

function Assert-True($cond, $msg) {
    if ($cond) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
    else { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:Fail++ }
}

function Stop-Proc($p) {
    if ($p -and -not $p.HasExited) {
        $procId = $p.Id
        $script:CleanupActions += [scriptblock]::Create(
            "try { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue } catch {}")
    }
}

try {
    if (-not $SkipBuild -or -not (Test-Path $BackendJar) -or -not (Test-Path $SimJar)) {
        Write-Host '[1/3] 构建...'
        # 本机 Windows 的 JDK17 固定位置；不存在（如 Linux CI）则沿用外部环境已配好的 JDK
        if (Test-Path 'E:\dev-tools\jdk17.0.20_8') {
            $env:JAVA_HOME = 'E:\dev-tools\jdk17.0.20_8'
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        }
        Push-Location $Root; mvn -q package -DskipTests; Pop-Location
    } else { Write-Host '[1/3] 使用既有 jar（-SkipBuild）' }

    $java = Get-E2eJava   # 跨平台探测（Linux 叫 java 不是 java.exe）

    # ---- 起 3 台 sim（fixed / float / 缺省）+ 后端 ----
    Write-Host '[2/3] 启动 3 台模拟器（fixed/float/缺省）+ 后端...'
    $p1 = Start-Process -FilePath $java -ArgumentList @(
        '-Drtk-e2e=true',   # 命令行留痕：Stop-E2eStaleProcesses 按它精确清残留
        '-jar', $SimJar,
        '--port','14560','--sysid','61','--name','AF-RTK-FIXED','--rtk','fixed') `
        -PassThru -RedirectStandardOutput (Join-Path $TempDir 'af-rtk-sim1.log')
    Stop-Proc $p1
    $p2 = Start-Process -FilePath $java -ArgumentList @(
        '-Drtk-e2e=true',   # 命令行留痕：Stop-E2eStaleProcesses 按它精确清残留
        '-jar', $SimJar,
        '--port','14561','--sysid','62','--name','AF-RTK-FLOAT','--rtk','float') `
        -PassThru -RedirectStandardOutput (Join-Path $TempDir 'af-rtk-sim2.log')
    Stop-Proc $p2
    $p3 = Start-Process -FilePath $java -ArgumentList @(
        '-Drtk-e2e=true',   # 命令行留痕：Stop-E2eStaleProcesses 按它精确清残留
        '-jar', $SimJar,
        '--port','14562','--sysid','63','--name','AF-RTK-STD') `
        -PassThru -RedirectStandardOutput (Join-Path $TempDir 'af-rtk-sim3.log')
    Stop-Proc $p3

    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-Drtk-e2e=true',   # 命令行留痕：Stop-E2eStaleProcesses 按它精确清残留
        '-jar', $BackendJar, "--server.port=$BackendPort",
        '--spring.profiles.active=dev',
        # 自带内存库：dev profile 默认落 ./data/aerofleet 文件库，上一轮 e2e 残留的
        # 编队/任务会被 restore 复活并干扰本轮断言（同一 JVM 生命周期内不重启，mem 足够）。
        '--spring.datasource.url=jdbc:h2:mem:rtk-e2e;DB_CLOSE_DELAY=-1',
        '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
        '--aerofleet.drone-port=14560',
        '--aerofleet.drone-extra-ports=14561,14562'
    ) -PassThru -RedirectStandardOutput (Join-Path $TempDir 'af-rtk-backend.log')
    Stop-Proc $backend

    # ---- 等 3 机上线 + 断言 ----
    Write-Host '[3/3] 等待上线并断言 RTK 状态...'
    $seen = @{}
    $lastRaw = $null
    $polls = 0
    $backendAlive = $false
    $lastErr = $null
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 1000
        $polls++
        try {
            $drones = Invoke-RestMethod -Uri "$Base/drones" -TimeoutSec 3
            $backendAlive = $true
            # 保留最后一次响应的原始形状：失败时要能看到 API 到底返回了什么，
            # 而不是只知道「没上线」。下面断言依赖的就是这个形状。
            $lastRaw = @($drones | ForEach-Object { "sysid=$($_.sysid) online=$($_.online) rtk=$($_.rtkStatus)" })
            # 键统一走 Get-E2eSysidKey：JSON 里的 sysid 是**数字**，而整数在 Windows PowerShell 5.1
            # 解成 Int32、在 pwsh 7 解成 Int64；PowerShell 的 hashtable 对键又是类型敏感的，
            # 所以插入与查询两边必须归一化成同一种形式。
            # CI run 39279718213 实测：响应里 61/62/63 online=True 全在，ContainsKey(61) 却报缺失。
            foreach ($d in $drones) { if ($d.online) { $seen[(Get-E2eSysidKey $d.sysid)] = $d } }
            if ($seen.Count -ge 3) { break }
        } catch {
            # 连不上了：保留上一次成功的响应，并把错误记下来（下面报「后端不可达」而不是「不上线」）
            $lastErr = $_.Exception.Message
        }
    }

    # 失败时把诊断信息打出来：原本这里只会说「没上线」，
    # 而「没上线」可能是后端死了、可能是后端活着但 online 为 false、
    # 也可能是响应里的 sysid 不是期望的那些 ——三种排查方向完全不同。
    #
    # 触发条件是「任一期望 sysid 缺失」而不是「总数不足」——CI run 39273582273 实测：
    # FAIL 走「上线」分支但 DIAG 零输出，反推 seen.Count>=3 却缺 61/62/63 中的某些，
    # 即上面三种里的最后一种；旧条件对它完全盲飞。
    $expected = @(61, 62, 63)
    $missing = @($expected | Where-Object { -not $seen.ContainsKey((Get-E2eSysidKey $_)) })
    if ($missing.Count -gt 0) {
        Write-Host ("  [DIAG] 轮询 $polls 次，后端可达=$backendAlive，已收集 $($seen.Count) 个 online 的交机（sysid: $($seen.Keys -join ',')）")
        Write-Host ("  [DIAG] 缺失 sysid: $($missing -join ',')（期望 61,62,63 全上线且 rtkStatus 正确）")
        if ($lastRaw) { foreach ($l in $lastRaw) { Write-Host ("  [DIAG] 最后一次响应：$l") } }
        if (-not $backendAlive -and $lastErr) { Write-Host ("  [DIAG] 后端不可达：$lastErr") }
        if ($backendAlive -and -not $lastRaw) { Write-Host "  [DIAG] 后端可达但从未拿到响应体（Invoke-RestMethod 每次调用都失败）" }
    }

    foreach ($pair in @(@(61, 'RTK_FIXED', 'R1'), @(62, 'RTK_FLOAT', 'R2'), @(63, 'STANDALONE', 'R3'))) {
        $sysid = $pair[0]; $expect = $pair[1]; $tag = $pair[2]
        $d = $seen[(Get-E2eSysidKey $sysid)]
        if ($null -eq $d) {
            Assert-True $false "$tag sysid=$sysid 上线"
            continue
        }
        # /drones 列表返回的 status 嵌套（WS 帧 status 同构）——检查 rtkStatus
        $rtk = $d.rtkStatus
        if (-not $rtk -and $d.status) { $rtk = $d.status.rtkStatus }
        Assert-True ($rtk -eq $expect) "$tag sysid=$sysid rtkStatus=$expect（实际 $rtk）"
    }

} catch {
    Write-Host "  [FAIL] 异常：$($_.Exception.Message)" -ForegroundColor Red
    $Fail++
} finally {
    foreach ($a in $script:CleanupActions) { & $a }
}

Write-Host ''
if ($Fail -eq 0) {
    Write-Host '=== e2e-rtk: ALL PASS ===' -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== e2e-rtk: $Fail FAILED ===" -ForegroundColor Red
    exit 1
}
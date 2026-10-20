# ROC 一控多机席位端到端验证脚本（E1）
#
# 依据：.codeartsdoer/specs/e1_roc_seat/spec.md 验收 2
# 场景：cloud-backend + 2 台 drone-sim（多机席位最小集）
#
# 断言目标：
#   S1  席位创建 → 机队绑定（2 机）→ 席位视图两机在线
#   S2  批量 RTL：逐机结果 200 且两机 ok（未起飞即 RTL 被拒也如实——断言 targets 数与逐机结构）
#   S3  警情登记 → 建议含最近机（距离因子主导）
#   S4  确认派飞 → DISPATCHED → 遥测可见目标机 armed/高度变化（警情上空驻留）
#   V1  席位重名 409；机队越上限 400；越席位子集 400
#
# 跨平台改造（2026-10-20，与 B2 批次的 D1-D8 同款）：本脚本原先只在本机 Windows 跑过，
# 埋了四个平台依赖 —— ① jar 路径用反斜杠字面量（Linux 上 Test-Path 恒 false）；
# ② java 可执行写死 java.exe；③ `-WindowStyle Hidden`（Linux 上直接报"找不到参数"）；
# ④ $env:TEMP（Linux runner 上常未设置）。现全部改走 e2e-common.ps1 的既有抽象。
# 另外补上 `-Droc-e2e=true` marker，使 CI 步间清理能按标记精确回收本脚本的残留进程。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-roc.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-roc.ps1 -SkipBuild
param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
# 跨平台公共库：java 探测 / jar 路径 / 临时目录 / 按标记清残留 / 跨版本 JSON 响应
. (Join-Path $PSScriptRoot 'e2e-common.ps1')
$TempDir = Get-E2eTempDir   # Linux runner 上 $env:TEMP 常未设置，不能直接用
$Fail = 0
$script:CleanupActions = @()
$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 18099 }
$DronePort1 = 14580
$DronePort2 = 14581
$Sysid1 = 71
$Sysid2 = 72
$Base = "http://localhost:$BackendPort/api/v1/roc"

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

function Try-Json($method, $uri, $body) {
    # 单一实现见 e2e-common.ps1 的 Invoke-E2eJson：它内部对错误响应体做了
    # 5.1（HttpWebResponse.GetResponseStream）/ 7+（HttpResponseMessage.Content）双分支回退，
    # 本脚本原先自己写的 try/catch 取不到 5.1 之外的响应体（V1 的 409/400 正文就丢了）。
    # 超时沿用本脚本原值 20s。
    Invoke-E2eJson -Method $method -Uri $uri -Body $body -TimeoutSec 20
}

try {
    # jar 路径走 Get-E2eJar：反斜杠字面量在 Linux 上不是分隔符，Test-Path 恒 false
    $backendJar = Get-E2eJar -Root $Root -Module 'cloud-backend' -Jar 'aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'
    $simJar = Get-E2eJar -Root $Root -Module 'drone-sim' -Jar 'aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar'
    if (-not $SkipBuild -or -not (Test-Path $backendJar) -or -not (Test-Path $simJar)) {
        Write-Host '[1/3] 构建...'
        # 本机 Windows 的 JDK17 固定位置；不存在（如 Linux CI）则沿用外部环境已配好的 JDK
        if (Test-Path 'E:\dev-tools\jdk17.0.20_8') {
            $env:JAVA_HOME = 'E:\dev-tools\jdk17.0.20_8'
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        }
        Push-Location $Root; mvn -q package -DskipTests; Pop-Location
    } else { Write-Host '[1/3] 使用既有 jar（-SkipBuild）' }

    $java = Get-E2eJava   # 跨平台探测（Linux 叫 java 不是 java.exe）

    Write-Host '[2/3] 启动后端 + 2 台模拟器（多机席位）...'
    # 71 号机放在警情点近处、72 号机远 100m+（lat -0.002 ≈ 222m）
    $sim1 = Start-Process -FilePath $java -ArgumentList @(
        '-Droc-e2e=true',   # 命令行留痕：Stop-E2eStaleProcesses 按它精确清残留
        '-jar', $simJar,
        '--port', "$DronePort1", '--sysid', "$Sysid1", '--name', 'AF-ROC-71',
        '--lat', '22.5910', '--lon', '113.9340') `
        -PassThru -RedirectStandardOutput (Join-Path $TempDir 'af-roc-sim1.log') `
        -RedirectStandardError (Join-Path $TempDir 'af-roc-sim1.err')
    Stop-Proc $sim1
    $sim2 = Start-Process -FilePath $java -ArgumentList @(
        '-Droc-e2e=true',   # 命令行留痕：Stop-E2eStaleProcesses 按它精确清残留
        '-jar', $simJar,
        '--port', "$DronePort2", '--sysid', "$Sysid2", '--name', 'AF-ROC-72',
        '--lat', '22.5890', '--lon', '113.9340') `
        -PassThru -RedirectStandardOutput (Join-Path $TempDir 'af-roc-sim2.log') `
        -RedirectStandardError (Join-Path $TempDir 'af-roc-sim2.err')
    Stop-Proc $sim2

    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-Droc-e2e=true',   # 命令行留痕：Stop-E2eStaleProcesses 按它精确清残留
        '-jar', $backendJar, "--server.port=$BackendPort",
        '--spring.profiles.active=dev',
        # 自带内存库：dev profile 默认落 ./data/aerofleet 文件库，上一轮 e2e 残留的
        # 编队/任务会被 restore 复活并干扰本轮断言（同一 JVM 生命周期内不重启，mem 足够）。
        '--spring.datasource.url=jdbc:h2:mem:roc-e2e;DB_CLOSE_DELAY=-1',
        '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
        '--aerofleet.drone-port=14580',
        '--aerofleet.drone-extra-ports=14581'
    ) -PassThru -RedirectStandardOutput (Join-Path $TempDir 'af-roc-backend.log')
    Stop-Proc $backend

    $up = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 1000
        try {
            $drones = Invoke-RestMethod "http://localhost:$BackendPort/api/v1/drones" -TimeoutSec 3
            if ((@($drones) | Where-Object { $_.online }).Count -ge 2) { $up = $true; break }
        } catch {}
    }
    Assert-True $up '两机上线'
    if (-not $up) { throw 'drones not online' }

    Write-Host '[3/3] 席位全链路断言...'
    # S1 席位 + 机队
    $seat = Try-Json 'POST' "$Base/seats" (@{ operatorName = 'e2e-roc-op' } | ConvertTo-Json)
    Assert-True ($seat.code -eq 201) "席位创建 201（实际 $($seat.code)）"
    $seatId = $seat.body.seatId
    $bind = Try-Json 'PUT' "$Base/seats/$seatId/fleet" (@{ sysids = @($Sysid1, $Sysid2) } | ConvertTo-Json)
    Assert-True ($bind.code -eq 200 -and $bind.body.fleetSize -eq 2) "机队绑定 2 机（实际 $($bind.body.fleetSize)）"
    $onlineCount = @($bind.body.fleet | Where-Object { $_.online }).Count
    Assert-True ($onlineCount -eq 2) "S1 席位视图两机在线（实际 $onlineCount）"

    # V1 校验
    $dup = Try-Json 'POST' "$Base/seats" (@{ operatorName = 'e2e-roc-op' } | ConvertTo-Json)
    Assert-True ($dup.code -eq 409) "V1 席位重名 409（实际 $($dup.code)）"
    $over = Try-Json 'PUT' "$Base/seats/$seatId/fleet" (@{ sysids = @(1,2,3,4,5,6,7,8,9,10) } | ConvertTo-Json)
    Assert-True ($over.code -eq 400) "V1 机队越上限 400（实际 $($over.code)）"
    $outside = Try-Json 'POST' "$Base/seats/$seatId/commands" (@{ action='arm'; sysids=@(99) } | ConvertTo-Json)
    Assert-True ($outside.code -eq 400) "V1 越席位子集 400（实际 $($outside.code)）"

    # S2 批量指令逐机结构（未起飞时 RTL 拒收——逐机结果如实）
    $batch = Try-Json 'POST' "$Base/seats/$seatId/commands" (@{ action = 'rtl' } | ConvertTo-Json)
    Assert-True ($batch.code -eq 200) "批量指令 200"
    Assert-True (@($batch.body.targets).Count -eq 2) "S2 逐机结果 2 条（实际 $((@($batch.body.targets)).Count)）"
    $okOrFailed = @($batch.body.targets | Where-Object { $_.result -eq 'ok' -or $_.result -eq 'failed' }).Count
    Assert-True ($okOrFailed -eq 2) "S2 逐机结构完整（result ok/failed，实际 $okOrFailed/2）"

    # S3 警情：建议最近的 71 号机
    $inc = Try-Json 'POST' "$Base/seats/$seatId/incidents" (@{
        lat = 22.5910; lon = 113.9340; priority = 'P0'; description = 'e2e 火情'
    } | ConvertTo-Json)
    Assert-True ($inc.code -eq 201) "警情登记 201（实际 $($inc.code)）"
    Assert-True ($inc.body.suggestedSysid -eq $Sysid1) "S3 建议最近机 $Sysid1（实际 $($inc.body.suggestedSysid)）"
    $incidentId = $inc.body.incidentId

    # S4 确认派飞 → DISPATCHED → 遥测可见
    $disp = Try-Json 'POST' "$Base/seats/$seatId/incidents/$incidentId/dispatch" (@{ holdSec = 20 } | ConvertTo-Json)
    Assert-True ($disp.code -eq 200 -and $disp.body.status -eq 'DISPATCHED') "S4 派飞 DISPATCHED（实际 $($disp.code)/$($disp.body.status)）"
    Start-Sleep 6   # arm→任务→爬升
    $tel = Invoke-RestMethod "http://localhost:$BackendPort/api/v1/drones/$Sysid1/telemetry" -TimeoutSec 5
    Assert-True ($tel.armed -and $tel.relativeAlt -gt 5) "S4 目标机已起飞（alt=$($tel.relativeAlt) armed=$($tel.armed)）"

} catch {
    Write-Host "  [FAIL] 异常：$($_.Exception.Message)" -ForegroundColor Red
    $Fail++
} finally {
    foreach ($a in $script:CleanupActions) { & $a }
}

Write-Host ''
if ($Fail -eq 0) {
    Write-Host '=== e2e-roc: ALL PASS ===' -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== e2e-roc: $Fail FAILED ===" -ForegroundColor Red
    exit 1
}

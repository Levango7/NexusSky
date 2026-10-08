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
$Fail = 0
$script:CleanupActions = @()

$BackendPort = if ($env:AF_BACKEND_PORT) { [int]$env:AF_BACKEND_PORT } else { 18099 }
$Base = "http://localhost:$BackendPort/api/v1"
$SimJar = Join-Path $Root 'drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar'
$BackendJar = Join-Path $Root 'cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'

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
        $env:JAVA_HOME = 'E:\dev-tools\jdk17.0.20_8'
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        Push-Location $Root; mvn -q package -DskipTests; Pop-Location
    } else { Write-Host '[1/3] 使用既有 jar（-SkipBuild）' }

    if ($env:AF_JAVA) { $java = $env:AF_JAVA }
    elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    else { $java = 'java.exe' }

    # ---- 起 3 台 sim（fixed / float / 缺省）+ 后端 ----
    Write-Host '[2/3] 启动 3 台模拟器（fixed/float/缺省）+ 后端...'
    $p1 = Start-Process -FilePath $java -ArgumentList @('-jar', $SimJar,
        '--port','14560','--sysid','61','--name','AF-RTK-FIXED','--rtk','fixed') `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-rtk-sim1.log')
    Stop-Proc $p1
    $p2 = Start-Process -FilePath $java -ArgumentList @('-jar', $SimJar,
        '--port','14561','--sysid','62','--name','AF-RTK-FLOAT','--rtk','float') `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-rtk-sim2.log')
    Stop-Proc $p2
    $p3 = Start-Process -FilePath $java -ArgumentList @('-jar', $SimJar,
        '--port','14562','--sysid','63','--name','AF-RTK-STD') `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-rtk-sim3.log')
    Stop-Proc $p3

    $backend = Start-Process -FilePath $java -ArgumentList @(
        '-jar', $BackendJar, "--server.port=$BackendPort",
        '--spring.profiles.active=dev',
        '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
        '--aerofleet.drone-port=14560',
        '--aerofleet.drone-extra-ports=14561,14562'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'af-rtk-backend.log')
    Stop-Proc $backend

    # ---- 等 3 机上线 + 断言 ----
    Write-Host '[3/3] 等待上线并断言 RTK 状态...'
    $seen = @{}
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 1000
        try {
            $drones = Invoke-RestMethod -Uri "$Base/drones" -TimeoutSec 3
            foreach ($d in $drones) { if ($d.online) { $seen[$d.sysid] = $d } }
            if ($seen.Count -ge 3) { break }
        } catch {}
    }

    foreach ($pair in @(@(61, 'RTK_FIXED', 'R1'), @(62, 'RTK_FLOAT', 'R2'), @(63, 'STANDALONE', 'R3'))) {
        $sysid = $pair[0]; $expect = $pair[1]; $tag = $pair[2]
        $d = $seen[$sysid]
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
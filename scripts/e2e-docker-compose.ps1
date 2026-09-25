# NexusSky Docker Compose 端到端验证（PowerShell 版）
# 使用 docker-compose 启动全部服务并验证完整链路
# 前置：已执行 mvn -DskipTests package，jar 包已生成
$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'
$Fail = 0
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $ProjectRoot 'docker-compose.yml'

function Step($m) { Write-Host "== $m" }
function Check($desc, $ok) {
    if ($ok) { Write-Host "   PASS: $desc" -ForegroundColor Green }
    else { Write-Host "   FAIL: $desc" -ForegroundColor Red; $script:Fail = 1 }
}

# ── 清理函数：确保容器不会泄漏 ──
function Cleanup-Containers {
    Write-Host '== 清理容器...'
    try {
        docker-compose -f $ComposeFile down --remove-orphans 2>$null
    } catch {
        Write-Host '   (清理时出现异常，忽略)'
    }
}

# 注册退出时清理
trap { Cleanup-Containers; exit 1 }

# ═══════════════════════════════════════════════════
# 场景 1：启动全部服务
# ═══════════════════════════════════════════════════
Step '启动 Docker Compose 全部服务'
# 先清理可能残留的容器
Cleanup-Containers
Start-Sleep 2

# 启动 redis（docker-compose.yml 未包含，需要单独启动）
docker run -d --name nexussky-redis --network host redis:7-alpine 2>$null
Start-Sleep 2

# 启动 docker-compose 服务
docker-compose -f $ComposeFile up -d
Start-Sleep 5
Check 'docker-compose 服务已启动' ($LASTEXITCODE -eq 0)

# ═══════════════════════════════════════════════════
# 场景 2：等待服务就绪（健康检查轮询）
# ═══════════════════════════════════════════════════
Step '等待 cloud-backend 就绪（/actuator/health 轮询）'
$backendReady = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $health = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 5
        if ($health.status -eq 'UP') {
            $backendReady = $true
            Write-Host "   backend 就绪（第 $($i+1) 次探测）"
            break
        }
    } catch { Start-Sleep 2 }
}
Check 'cloud-backend 健康检查返回 UP' $backendReady
if (-not $backendReady) {
    Write-Host '   backend 未就绪，尝试获取诊断信息...'
    try {
        $diag = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 5
        Write-Host "   健康响应: $($diag | ConvertTo-Json -Depth 3)"
    } catch {
        Write-Host "   无响应: $($_.Exception.Message)"
    }
    Cleanup-Containers
    exit 1
}

# ═══════════════════════════════════════════════════
# 场景 3：验证 cloud-backend /actuator/health 返回 UP
# ═══════════════════════════════════════════════════
Step '验证 /actuator/health 返回 UP'
$health = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 5
Write-Host "   status=$($health.status)"
Check '/actuator/health status=UP' ($health.status -eq 'UP')

# ═══════════════════════════════════════════════════
# 场景 4：验证 gcs-web 返回 HTML 页面
# ═══════════════════════════════════════════════════
Step '验证 gcs-web 返回 HTML 页面'
$webOk = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $resp = Invoke-WebRequest -Uri 'http://localhost:5173' -TimeoutSec 5 -UseBasicParsing
        if ($resp.StatusCode -eq 200 -and $resp.Content -match '<html') {
            $webOk = $true
            break
        }
    } catch { Start-Sleep 2 }
}
Check 'gcs-web 返回 HTML 页面（200 + <html>）' $webOk

# ═══════════════════════════════════════════════════
# 场景 5：验证 drone-sim 被 cloud-backend 发现
# ═══════════════════════════════════════════════════
Step '验证 drone-sim 被 cloud-backend 发现（/api/v1/drones 非空）'
$drone = $null
for ($i = 0; $i -lt 30; $i++) {
    try {
        $list = Invoke-RestMethod -Uri "$Base/drones" -TimeoutSec 5
        if ($list.Count -gt 0) { $drone = $list[0]; break }
    } catch { Start-Sleep 2 }
}
Check '机队列表非空（drone-sim 已被发现）' ($null -ne $drone)
if ($null -eq $drone) {
    Write-Host '   drone-sim 未被发现，终止测试'
    Cleanup-Containers
    exit 1
}
$Sysid = $drone.sysid
Write-Host "   sysid=$Sysid callsign=$($drone.callsign)"

# ═══════════════════════════════════════════════════
# 场景 6：执行基本飞行操作（ARM → takeoff → RTL）
# ═══════════════════════════════════════════════════
Step '执行基本飞行操作'

# ARM
$body = @{ type = 'arm' } | ConvertTo-Json
$r = Invoke-RestMethod -Uri "$Base/drones/$Sysid/commands" -Method Post -Body $body -ContentType 'application/json; charset=utf-8' -TimeoutSec 20
Write-Host "   ARM: status=$($r.status)"
Check 'ARM ok' ($r.status -eq 'ok')

# Takeoff
$body = @{ type = 'takeoff'; alt = 30 } | ConvertTo-Json
$r = Invoke-RestMethod -Uri "$Base/drones/$Sysid/commands" -Method Post -Body $body -ContentType 'application/json; charset=utf-8' -TimeoutSec 20
Write-Host "   takeoff: status=$($r.status)"
Check 'takeoff ok' ($r.status -eq 'ok')

Start-Sleep 5

# RTL
$body = @{ type = 'rtl' } | ConvertTo-Json
$r = Invoke-RestMethod -Uri "$Base/drones/$Sysid/commands" -Method Post -Body $body -ContentType 'application/json; charset=utf-8' -TimeoutSec 20
Write-Host "   RTL: status=$($r.status)"
Check 'RTL ok' ($r.status -eq 'ok')

# ═══════════════════════════════════════════════════
# 场景 7：验证飞行日志记录（/api/v1/flightlog）
# ═══════════════════════════════════════════════════
Step '验证飞行日志记录（/api/v1/flightlog）'
Start-Sleep 3
$flog = Invoke-RestMethod -Uri "$Base/flightlog" -TimeoutSec 5
Write-Host "   日志条目数: $($flog.Count)"
Check '飞行日志非空' ($flog.Count -ge 1)

# ═══════════════════════════════════════════════════
# 清理容器
# ═══════════════════════════════════════════════════
Step '清理容器'
Cleanup-Containers
# 清理单独启动的 redis
docker rm -f nexussky-redis 2>$null
Check '容器已清理' $true

# ═══════════════════════════════════════════════════
# 结果汇总
# ═══════════════════════════════════════════════════
if ($Fail -eq 0) {
    Write-Host 'ALL DOCKER COMPOSE E2E TESTS PASSED' -ForegroundColor Green
    exit 0
} else {
    Write-Host 'DOCKER COMPOSE E2E TESTS FAILED' -ForegroundColor Red
    exit 1
}
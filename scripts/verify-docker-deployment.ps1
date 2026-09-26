# NexusSky Docker 部署验证脚本
# 验证 Docker 镜像构建、容器启动、服务连通性
# 用法: .\scripts\verify-docker-deployment.ps1

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Fail = 0

function Step($m) { Write-Host "`n== $m" }
function Check($desc, $ok) {
    if ($ok) { Write-Host "   PASS: $desc" -ForegroundColor Green }
    else { Write-Host "   FAIL: $desc" -ForegroundColor Red; $script:Fail = 1 }
}

Step '1. 检查 Docker 环境'
$dockerOk = $false
try {
    $dockerVersion = docker version --format '{{.Server.Version}}' 2>$null
    if ($dockerVersion) {
        Write-Host "   Docker Server 版本: $dockerVersion"
        $dockerOk = $true
    }
} catch {}
Check 'Docker 可用' $dockerOk
if (-not $dockerOk) { Write-Host '验证终止：Docker 不可用'; exit 1 }

Step '2. 构建镜像（如果不存在）'
$images = @(
    @{ Name = 'nexussky/cloud-backend:1.0.0'; Dockerfile = 'deploy/docker/Dockerfile.cloud' },
    @{ Name = 'nexussky/drone-sim:1.0.0'; Dockerfile = 'deploy/docker/Dockerfile.sim' },
    @{ Name = 'nexussky/gcs-web:1.0.0'; Dockerfile = 'deploy/docker/Dockerfile.web' }
)

foreach ($img in $images) {
    $exists = docker image inspect $img.Name 2>$null
    if ($exists) {
        Write-Host "   镜像已存在: $($img.Name)"
    } else {
        Write-Host "   构建镜像: $($img.Name)"
        $dfPath = Join-Path $ProjectRoot $img.Dockerfile
        docker build -f $dfPath -t $img.Name $ProjectRoot 2>&1 | ForEach-Object { Write-Host "   $_" }
        $buildOk = $LASTEXITCODE -eq 0
        Check "构建 $($img.Name)" $buildOk
        if (-not $buildOk) { Write-Host "   构建失败，跳过后续验证"; exit 1 }
    }
}

Step '3. 清理旧容器'
$oldContainers = @('nexussky-verify-cloud', 'nexussky-verify-sim', 'nexussky-verify-web', 'nexussky-verify-redis')
foreach ($c in $oldContainers) {
    docker rm -f $c 2>$null | Out-Null
}
Write-Host '   旧容器已清理'

Step '4. 启动 Redis 容器'
docker run -d --name nexussky-verify-redis -p 6380:6379 redis:7-alpine 2>&1 | Out-Null
$redisOk = $LASTEXITCODE -eq 0
Check 'Redis 容器启动' $redisOk

Step '5. 启动 cloud-backend 容器'
docker run -d --name nexussky-verify-cloud -p 8081:8080 `
    -e SPRING_PROFILES_ACTIVE=dev `
    -e SPRING_DATA_REDIS_HOST=host.docker.internal `
    -e SPRING_DATA_REDIS_PORT=6380 `
    nexussky/cloud-backend:1.0.0 2>&1 | Out-Null
$cloudOk = $LASTEXITCODE -eq 0
Check 'cloud-backend 容器启动' $cloudOk

Step '6. 等待 cloud-backend 就绪'
$cloudReady = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $health = Invoke-RestMethod -Uri 'http://localhost:8081/actuator/health' -TimeoutSec 3
        if ($health.status -eq 'UP') {
            $cloudReady = $true
            Write-Host "   cloud-backend 就绪 (${i}s)"
            break
        }
    } catch {}
    Start-Sleep 2
}
Check 'cloud-backend 健康检查 UP' $cloudReady

Step '7. 启动 drone-sim 容器'
docker run -d --name nexussky-verify-sim `
    -e JAVA_OPTS='-XX:+UseG1GC -XX:MaxRAMPercentage=75' `
    nexussky/drone-sim:1.0.0 --sysid=1 --port=14540 2>&1 | Out-Null
$simOk = $LASTEXITCODE -eq 0
Check 'drone-sim 容器启动' $simOk

Step '8. 启动 gcs-web 容器'
docker run -d --name nexussky-verify-web -p 3001:80 nexussky/gcs-web:1.0.0 2>&1 | Out-Null
$webOk = $LASTEXITCODE -eq 0
Check 'gcs-web 容器启动' $webOk

Step '9. 验证服务连通性'
# cloud-backend API
try {
    $drones = Invoke-RestMethod -Uri 'http://localhost:8081/api/v1/drones' -TimeoutSec 5
    Check 'cloud-backend API 可访问' $true
} catch {
    Check 'cloud-backend API 可访问' $false
}

# gcs-web HTML
try {
    $webResp = Invoke-WebRequest -Uri 'http://localhost:3001' -TimeoutSec 5
    $webHtml = $webResp.StatusCode -eq 200
    Check 'gcs-web 返回 HTML' $webHtml
} catch {
    Check 'gcs-web 返回 HTML' $false
}

# drone-sim 日志
$simLogs = docker logs nexussky-verify-sim 2>&1 | Select-Object -First 5
$simRunning = $simLogs -match 'heartbeat|HEARTBEAT|UDP|listening'
Check 'drone-sim 日志正常' $simRunning

Step '10. 清理验证容器'
foreach ($c in $oldContainers) {
    docker rm -f $c 2>$null | Out-Null
}
Write-Host '   验证容器已清理'

if ($Fail -eq 0) {
    Write-Host "`n=== ALL DOCKER DEPLOYMENT VERIFICATIONS PASSED ===" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`n=== DOCKER DEPLOYMENT VERIFICATION FAILED ===" -ForegroundColor Red
    exit 1
}
# 安防取流链路**真机**端到端验证驱动（本机 UVC 摄像头版，非 CI）。
#
# 为什么需要这个脚本
# ------------------
# `scripts/e2e-mjpeg-ci.sh` 是 CI 配方（合成帧源）；本脚本把帧源换成**真摄像头**
# （`scripts/camera-snapshot-source.py`，每请求抓真帧），并在生产鉴权姿势下跑
# `scripts/e2e-mjpeg-preview.py` 的 S1-S7 断言链（23 条断言：真 JPEG / 匿名 401 /
# 短令牌 200 / 帧号递增 / 注销 404）。CI runner 没有摄像头，所以这是本机手动脚本。
#
# 与 e2e-mjpeg-ci.sh 的差异
# -------------------------
#   1. 帧源：synthetic-frame-source.py -> camera-snapshot-source.py（真 UVC 摄像头）；
#   2. 建号方式：bootstrap-admin **一次起齐**（免去 dev 起停 + H2 落盘舞步）；
#   3. 平台：Windows PowerShell（CI 配方是 bash）。
#
# 本机踩过的三个坑（下面代码里已规避，勿删）：
#   - PATH 上的 java 可能是 1.8（UnsupportedClassVersionError）-> 显式用 $env:JAVA_HOME\bin\java.exe；
#   - `localhost` 先解析 IPv6（::1），Tomcat 绑 IPv4 -> 健康检查一律用 127.0.0.1；
#   - Windows PowerShell 5.1 没有 -SkipHttpErrorCheck -> 非 2xx 会抛异常，健康轮询
#     需要自己 try/catch 记状态码。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\run-webcam-mjpeg-e2e.ps1 -SkipBuild
#   powershell -ExecutionPolicy Bypass -File scripts\run-webcam-mjpeg-e2e.ps1 -BackendPort 18099 -FramePort 18077
#
# 前置：本机有可用摄像头（cv2.VideoCapture(0) 能打开）；JDK 17+（jar 是 17 字节码）。

$ErrorActionPreference = 'Continue'
$ROOT = Split-Path -Parent $PSScriptRoot
Set-Location $ROOT

$BACKEND_PORT = 18099
$FRAME_PORT   = 18077
$JAR          = 'cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'
$PY           = 'E:\dev-tools\python-3.14.3\python.exe'
$JWT_SECRET   = 'webcam-e2e-local-only-jwt-secret-0123456789'
$ADMIN_PW     = 'webcam-E2E-Passw0rd!'
$LOGDIR       = 'F:\Agent\opencode\workspace\webcam-e2e-logs'
New-Item -ItemType Directory -Force -Path $LOGDIR | Out-Null

if (-not (Test-Path $PY)) { $PY = "python" }
if (-not (Test-Path $JAR)) { Write-Output "找不到 $JAR（先跑 mvn -pl cloud-backend -am package -DskipTests）"; exit 2 }

# 0) 干净 H2
Remove-Item -ErrorAction SilentlyContinue .\data\aerofleet.mv.db, .\data\aerofleet.trace.db

# 1) 起真摄像头帧源
$cam = Start-Process -FilePath $PY -ArgumentList "scripts/camera-snapshot-source.py","--port","$FRAME_PORT" `
        -RedirectStandardOutput "$LOGDIR\cam.log" -RedirectStandardError "$LOGDIR\cam.err" -PassThru -WindowStyle Hidden
Write-Output "camera source pid=$($cam.Id) port=$FRAME_PORT"

# 2) 等帧源出真 JPEG
$ok = $false
for ($i = 0; $i -lt 20; $i++) {
  try {
    $tmp = "$LOGDIR\probe.jpg"
    Invoke-WebRequest -Uri "http://127.0.0.1:$FRAME_PORT/snapshot" -OutFile $tmp -UseBasicParsing -TimeoutSec 5
    $bytes = [System.IO.File]::ReadAllBytes($tmp)
    if ($bytes.Length -gt 100 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xD8) { $ok = $true; break }
  } catch { Start-Sleep 1 }
}
if (-not $ok) { Write-Output "帧源没出 JPEG（摄像头不存在/被占用）"; Get-Content "$LOGDIR\cam.err" -ErrorAction SilentlyContinue | Select-Object -First 10; Stop-Process -Id $cam.Id -Force; exit 2 }
Write-Output "帧源 OK：snapshot 返回真 JPEG（$($bytes.Length) bytes）"

# 3) 起后端（生产鉴权姿势 + bootstrap admin 一次起齐）；PATH 上的 java 是 1.8，显式用 JAVA_HOME 的 17
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $javaExe)) { $javaExe = 'java' }
$backend = Start-Process -FilePath $javaExe -ArgumentList @(
    '-jar', $JAR,
    '--spring.profiles.active=dev',
    "--server.port=$BACKEND_PORT",
    '--aerofleet.security.dev-mode=false',
    "--aerofleet.security.jwt-secret=$JWT_SECRET",
    "--aerofleet.security.bootstrap-admin-password=$ADMIN_PW",
    '--aerofleet.udp-port=14551',
    '--aerofleet.drone-port=14544',
    '--aerofleet.drone-extra-ports=14545,14546,14547'
  ) -RedirectStandardOutput "$LOGDIR\backend.log" -RedirectStandardError "$LOGDIR\backend.err" -PassThru -WindowStyle Hidden
Write-Output "backend pid=$($backend.Id) port=$BACKEND_PORT (dev-mode=false)"

# 4) 等健康（全新 H2 + Flyway 冷启动可能 >2 分钟，放宽到 240s；打印最后状态码便于诊断）
$healthy = $false
$lastCode = 0
for ($i = 0; $i -lt 120; $i++) {
  try {
    $code = (Invoke-WebRequest -Uri "http://localhost:$BACKEND_PORT/actuator/health" -UseBasicParsing -TimeoutSec 5 -SkipHttpErrorCheck).StatusCode
    $lastCode = $code
  } catch { $lastCode = 0 }
  if ($code -eq 200) { $healthy = $true; break }
  if ($backend.HasExited) { Write-Output "后端进程已死："; Get-Content "$LOGDIR\backend.log" -Tail 25; break }
  Start-Sleep 2
}
if (-not $healthy) { Write-Output "后端没就绪（最后状态码 $lastCode）"; Get-Content "$LOGDIR\backend.log" -Tail 15; Stop-Process -Id $cam.Id -Force -ErrorAction SilentlyContinue; if (-not $backend.HasExited) { Stop-Process -Id $backend.Id -Force }; exit 2 }
Write-Output "后端就绪（health 200）"

# 5) 跑 S1-S7 断言链（真摄像头帧源）
& $PY scripts/e2e-mjpeg-preview.py `
    --backend "http://localhost:$BACKEND_PORT" `
    --snapshot-url "http://127.0.0.1:$FRAME_PORT/snapshot" `
    --username admin --password $ADMIN_PW --frames 4
$rc = $LASTEXITCODE

# 6) 清理
Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $cam.Id -Force -ErrorAction SilentlyContinue
Write-Output "=== E2E exit=$rc ==="
exit $rc

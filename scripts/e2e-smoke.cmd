@echo off
REM AeroFleet 端到端冒烟测试（Windows CMD 原生版）
REM 前置：cloud-backend(8080/14550) 与 drone-sim(14540) 已启动
setlocal enabledelayedexpansion
set BASE=http://localhost:8080/api/v1
set FAIL=0

echo == 等待设备上线
set COUNT=0
for /L %%i in (1,1,25) do (
  if !COUNT!==0 (
    for /f %%c in ('curl -s %BASE%/drones ^| python -c "import sys,json;print(len(json.load(sys.stdin)))" 2^>nul') do set COUNT=%%c
    if !COUNT!==0 timeout /t 1 >nul
  )
)
if %COUNT%==0 (echo    FAIL: 机队列表为空 & set FAIL=1) else (echo    PASS: %COUNT% 台设备在线)

for /f %%s in ('curl -s %BASE%/drones ^| python -c "import sys,json;print(json.load(sys.stdin)[0]['sysid'])"') do set SYSID=%%s
echo    sysid=%SYSID%

echo == 上传方形任务
curl -s -X POST -H "Content-Type: application/json" -d "{\"items\":[{\"cmd\":\"takeoff\",\"lat\":22.5907,\"lon\":113.9345,\"alt\":30,\"holdTime\":0},{\"cmd\":\"waypoint\",\"lat\":22.5917,\"lon\":113.9345,\"alt\":50,\"holdTime\":2},{\"cmd\":\"waypoint\",\"lat\":22.5917,\"lon\":113.9355,\"alt\":50,\"holdTime\":2},{\"cmd\":\"waypoint\",\"lat\":22.5907,\"lon\":113.9355,\"alt\":50,\"holdTime\":2},{\"cmd\":\"rtl\",\"lat\":22.5907,\"lon\":113.9345,\"alt\":0,\"holdTime\":0}]}" %BASE%/drones/%SYSID%/mission > "%TEMP%\af_up.json"
type "%TEMP%\af_up.json"
echo.
echo %TEMP%\af_up.json | findstr ok >nul 2>&1
findstr ok "%TEMP%\af_up.json" >nul 2>&1 || (echo    FAIL: 任务上传 & set FAIL=1)
if not errorlevel 1 echo    PASS: 任务上传 ok

echo == ARM
curl -s -X POST -H "Content-Type: application/json" -d "{\"type\":\"arm\"}" %BASE%/drones/%SYSID%/commands
echo.

echo == 开始任务
curl -s -X POST -H "Content-Type: application/json" -d "{\"type\":\"start_mission\"}" %BASE%/drones/%SYSID%/commands
echo.

echo == 等待任务推进
timeout /t 8 >nul
curl -s %BASE%/drones/%SYSID%/telemetry
echo.

echo == RTL
curl -s -X POST -H "Content-Type: application/json" -d "{\"type\":\"rtl\"}" %BASE%/drones/%SYSID%/commands
echo.

echo == 轨迹检查
timeout /t 3 >nul
curl -s %BASE%/drones/%SYSID%/track > "%TEMP%\af_track.json"
for /f %%n in ('python -c "import json;print(len(json.load(open(r'%TEMP%\af_track.json'))))" 2^>nul') do set TRKN=%%n
echo    轨迹点数: !TRKN!
if !TRKN! GTR 1 (echo    PASS) else (echo    FAIL & set FAIL=1)

if %FAIL%==0 (echo ALL SMOKE TESTS PASSED) else (echo SMOKE TESTS FAILED)
exit /b %FAIL%

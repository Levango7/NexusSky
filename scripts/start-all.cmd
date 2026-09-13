@echo off
REM AeroFleet 一键启动（Windows CMD）：模拟器 + 云后端
REM 前置：JAVA_HOME 指向 JDK17（或 AF_JAVA 指向 java.exe）；mvn 在 PATH
setlocal

set ROOT=%~dp0..

REM ---- Java 探测：AF_JAVA > JAVA_HOME > PATH ----
set JAVA_EXE=java.exe
if defined AF_JAVA set "JAVA_EXE=%AF_JAVA%"
if not defined AF_JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

echo [1/2] Building Java modules...
pushd "%ROOT%"
call mvn -q -DskipTests package || (echo BUILD FAILED & popd & exit /b 1)
popd

echo [2/2] Starting drone simulator (UDP 14540) + cloud backend (HTTP 8080, UDP 14550)...
start "aerofleet-sim" cmd /c "%JAVA_EXE% -jar %ROOT%\drone-sim\target\aerofleet-drone-sim-0.1.0-SNAPSHOT.jar --port 14540"
start "aerofleet-cloud" cmd /c "%JAVA_EXE% -jar %ROOT%\cloud-backend\target\aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar"

timeout /t 5 >nul
echo.
echo Backend : http://localhost:8080  (API: /api/v1/drones)
echo GCS dev : cd gcs-web ^&^& npm run dev  -^>  http://localhost:5173
echo.
echo To stop: close the two console windows.
endlocal

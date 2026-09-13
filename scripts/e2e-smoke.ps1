# AeroFleet 端到端冒烟测试（PowerShell 版）
# 前置：cloud-backend(8080/14550) 与 drone-sim(14540) 已启动
$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'
$Fail = 0

function Step($m) { Write-Host "== $m" }
function Check($desc, $ok) {
    if ($ok) { Write-Host "   PASS: $desc" -ForegroundColor Green }
    else { Write-Host "   FAIL: $desc" -ForegroundColor Red; $script:Fail = 1 }
}

Step '等待设备上线'
$drone = $null
for ($i = 0; $i -lt 20; $i++) {
    try {
        $list = Invoke-RestMethod -Uri "$Base/drones" -TimeoutSec 5
        if ($list.Count -gt 0) { $drone = $list[0]; break }
    } catch { Start-Sleep 1 }
}
Check '机队列表非空' ($null -ne $drone)
if ($null -eq $drone) { Write-Host 'SMOKE TESTS FAILED'; exit 1 }
$Sysid = $drone.sysid
Write-Host "   sysid=$Sysid callsign=$($drone.callsign)"

Step '上传方形任务'
$mission = @{
    items = @(
        @{ cmd = 'takeoff';   lat = 22.5907; lon = 113.9345; alt = 30; holdTime = 0 }
        @{ cmd = 'waypoint'; lat = 22.5917; lon = 113.9345; alt = 50; holdTime = 2 }
        @{ cmd = 'waypoint'; lat = 22.5917; lon = 113.9355; alt = 50; holdTime = 2 }
        @{ cmd = 'waypoint'; lat = 22.5907; lon = 113.9355; alt = 50; holdTime = 2 }
        @{ cmd = 'rtl';      lat = 22.5907; lon = 113.9345; alt = 0;  holdTime = 0 }
    )
} | ConvertTo-Json -Depth 4
$up = Invoke-RestMethod -Uri "$Base/drones/$Sysid/mission" -Method Post -Body $mission -ContentType 'application/json; charset=utf-8' -TimeoutSec 30
Write-Host "   status=$($up.status) uploaded=$($up.uploaded)"
Check '任务上传 ok' ($up.status -eq 'ok')

function Send-Cmd($type, $alt) {
    $body = @{ type = $type } | ConvertTo-Json
    if ($alt) { $body = @{ type = $type; alt = $alt } | ConvertTo-Json }
    Invoke-RestMethod -Uri "$Base/drones/$Sysid/commands" -Method Post -Body $body -ContentType 'application/json; charset=utf-8' -TimeoutSec 20
}

Step 'ARM'
$r = Send-Cmd 'arm'
Write-Host "   $($r.status) / $($r.result)"
Check 'ARM ok' ($r.status -eq 'ok')

Step '开始任务'
$r = Send-Cmd 'start_mission'
Write-Host "   $($r.status) / $($r.result)"
Check 'start_mission ok' ($r.status -eq 'ok')

Step '等待任务推进（12s）'
Start-Sleep 12
$t = Invoke-RestMethod -Uri "$Base/drones/$Sysid/telemetry" -TimeoutSec 5
Write-Host ("   mode={0} missionSeq={1}/{2} alt={3}m battery={4}%" -f $t.mode, $t.missionSeq, $t.missionTotal, $t.relativeAlt, $t.battery)
Check '遥测有 missionSeq' ($null -ne $t.missionSeq)
Check '高度 > 5m（已起飞）' ($t.relativeAlt -gt 5)

Step 'RTL'
$r = Send-Cmd 'rtl'
Write-Host "   $($r.status) / $($r.result)"
Check 'RTL ok' ($r.status -eq 'ok')

Step '轨迹检查'
Start-Sleep 3
$track = Invoke-RestMethod -Uri "$Base/drones/$Sysid/track" -TimeoutSec 5
Write-Host "   轨迹点数: $($track.Count)"
Check '轨迹点数 > 1' ($track.Count -gt 1)

if ($Fail -eq 0) { Write-Host 'ALL SMOKE TESTS PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host 'SMOKE TESTS FAILED' -ForegroundColor Red; exit 1 }

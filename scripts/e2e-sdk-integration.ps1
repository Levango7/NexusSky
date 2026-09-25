# NexusSky SDK 集成端到端测试（PowerShell 版）
# 前置：cloud-backend(8080) 与 drone-sim(14540) 已启动
# 验证：通过 HTTP 调用模拟 Java/Python SDK 的 API 行为，覆盖所有核心端点
$ErrorActionPreference = 'Stop'
$Base = 'http://localhost:8080/api/v1'
$Fail = 0

function Step($m) { Write-Host "== $m" }
function Check($desc, $ok) {
    if ($ok) { Write-Host "   PASS: $desc" -ForegroundColor Green }
    else { Write-Host "   FAIL: $desc" -ForegroundColor Red; $script:Fail = 1 }
}

# ── 辅助函数：发送 HTTP 请求并返回解析后的 JSON ──
function Invoke-Api($method, $path, $body) {
    $uri = "$Base$path"
    $params = @{ Method = $method; Uri = $uri; TimeoutSec = 15 }
    if ($body) {
        $params.Body = $body
        $params.ContentType = 'application/json; charset=utf-8'
    }
    return Invoke-RestMethod @params
}

# ── 辅助函数：发送命令 ──
function Send-Cmd($sysid, $type, $alt) {
    if ($alt) {
        $body = @{ type = $type; alt = $alt } | ConvertTo-Json
    } else {
        $body = @{ type = $type } | ConvertTo-Json
    }
    return Invoke-Api 'Post' "/drones/$sysid/commands" $body
}

# ═══════════════════════════════════════════════════
# 场景 1：等待设备上线（DroneApi.list → GET /drones）
# ═══════════════════════════════════════════════════
Step '等待设备上线（DroneApi.list）'
$drone = $null
for ($i = 0; $i -lt 30; $i++) {
    try {
        $list = Invoke-Api 'Get' '/drones'
        if ($list.Count -gt 0) { $drone = $list[0]; break }
    } catch { Start-Sleep 1 }
}
Check '机队列表非空' ($null -ne $drone)
if ($null -eq $drone) { Write-Host 'SDK INTEGRATION TESTS FAILED'; exit 1 }
$Sysid = $drone.sysid
Write-Host "   sysid=$Sysid callsign=$($drone.callsign)"

# ═══════════════════════════════════════════════════
# 场景 2：DroneApi.get(sysid) → GET /drones/{sysid}
# ═══════════════════════════════════════════════════
Step 'DroneApi.get(sysid) → GET /drones/{sysid}'
$single = Invoke-Api 'Get' "/drones/$Sysid"
Check '返回的 sysid 与请求一致' ($single.sysid -eq $Sysid)
Check '包含 callsign 字段' ($null -ne $single.callsign)
Check '包含 mode 字段' ($null -ne $single.mode)

# ═══════════════════════════════════════════════════
# 场景 3：DroneApi.telemetry(sysid) → GET /drones/{sysid}/telemetry
# ═══════════════════════════════════════════════════
Step 'DroneApi.telemetry(sysid) → GET /drones/{sysid}/telemetry'
$tel = Invoke-Api 'Get' "/drones/$Sysid/telemetry"
Check '遥测包含 mode 字段' ($null -ne $tel.mode)
Check '遥测包含 battery 字段' ($null -ne $tel.battery)
Check '遥测包含 relativeAlt 字段' ($null -ne $tel.relativeAlt)

# ═══════════════════════════════════════════════════
# 场景 4：DroneApi.arm(sysid) → POST /drones/{sysid}/commands {"type":"arm"}
# ═══════════════════════════════════════════════════
Step 'DroneApi.arm(sysid) → POST commands {"type":"arm"}'
$r = Send-Cmd $Sysid 'arm'
Write-Host "   status=$($r.status)"
Check 'ARM 返回 status=ok' ($r.status -eq 'ok')

# ═══════════════════════════════════════════════════
# 场景 5：DroneApi.takeoff(sysid, alt) → POST commands {"type":"takeoff","alt":30}
# ═══════════════════════════════════════════════════
Step 'DroneApi.takeoff(sysid, 30) → POST commands {"type":"takeoff","alt":30}'
$r = Send-Cmd $Sysid 'takeoff' 30
Write-Host "   status=$($r.status)"
Check 'takeoff 返回 status=ok' ($r.status -eq 'ok')

# 等待起飞完成
Start-Sleep 5

# ═══════════════════════════════════════════════════
# 场景 6：DroneApi.rtl(sysid) → POST commands {"type":"rtl"}
# ═══════════════════════════════════════════════════
Step 'DroneApi.rtl(sysid) → POST commands {"type":"rtl"}'
$r = Send-Cmd $Sysid 'rtl'
Write-Host "   status=$($r.status)"
Check 'RTL 返回 status=ok' ($r.status -eq 'ok')

# ═══════════════════════════════════════════════════
# 场景 7：MissionApi.upload(sysid, waypoints) → POST /drones/{sysid}/mission
# ═══════════════════════════════════════════════════
Step 'MissionApi.upload(sysid, waypoints) → POST /drones/{sysid}/mission'
$mission = @{
    items = @(
        @{ cmd = 'takeoff';   lat = 22.5907; lon = 113.9345; alt = 30; holdTime = 0 }
        @{ cmd = 'waypoint'; lat = 22.5917; lon = 113.9345; alt = 50; holdTime = 2 }
        @{ cmd = 'waypoint'; lat = 22.5917; lon = 113.9355; alt = 50; holdTime = 2 }
        @{ cmd = 'rtl';      lat = 22.5907; lon = 113.9345; alt = 0;  holdTime = 0 }
    )
} | ConvertTo-Json -Depth 4
$up = Invoke-Api 'Post' "/drones/$Sysid/mission" $mission
Write-Host "   status=$($up.status)"
Check '任务上传返回 status=ok' ($up.status -eq 'ok')

# ═══════════════════════════════════════════════════
# 场景 8：MissionApi.download(sysid) → GET /drones/{sysid}/mission
# ═══════════════════════════════════════════════════
Step 'MissionApi.download(sysid) → GET /drones/{sysid}/mission'
$dl = Invoke-Api 'Get' "/drones/$Sysid/mission"
Check '任务下载包含 items 字段' ($null -ne $dl.items)
Check '任务下载 items 数量 >= 3' ($dl.items.Count -ge 3)

# ═══════════════════════════════════════════════════
# 场景 9：FlightLogApi.list() → GET /flightlog
# ═══════════════════════════════════════════════════
Step 'FlightLogApi.list() → GET /flightlog'
Start-Sleep 2
$flog = Invoke-Api 'Get' '/flightlog'
Check '飞行日志列表非空' ($flog.Count -ge 1)
Write-Host "   日志条目数: $($flog.Count)"

# ═══════════════════════════════════════════════════
# 场景 10：验证 ApiResponse 格式
# ═══════════════════════════════════════════════════
Step '验证 ApiResponse 格式（status + data）'
$listRaw = Invoke-Api 'Get' '/drones'
Check '列表响应是数组格式' ($listRaw -is [array])
$cmdResp = Send-Cmd $Sysid 'arm'
Check '命令响应包含 status 字段' ($null -ne $cmdResp.status)
Check '命令响应 status 值为 ok 或 error' ($cmdResp.status -eq 'ok' -or $cmdResp.status -eq 'error')

# ═══════════════════════════════════════════════════
# 场景 11：错误场景 — 404 不存在的无人机
# ═══════════════════════════════════════════════════
Step '错误场景：GET /drones/99999（不存在的 sysid）'
$notFound = $false
try {
    Invoke-Api 'Get' '/drones/99999'
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    $notFound = ($statusCode -eq 404)
    Write-Host "   HTTP $statusCode"
}
Check '不存在的无人机返回 404' $notFound

# ═══════════════════════════════════════════════════
# 场景 12：错误场景 — 无效命令
# ═══════════════════════════════════════════════════
Step '错误场景：POST 无效命令 {"type":"invalid_cmd"}'
$invalidBody = @{ type = 'invalid_cmd' } | ConvertTo-Json
$errResp = $null
$errCaught = $false
try {
    $errResp = Invoke-Api 'Post' "/drones/$Sysid/commands" $invalidBody
} catch {
    $errCaught = $true
    Write-Host "   异常: $($_.Exception.Message)"
}
if ($errResp) {
    Check '无效命令返回 status=error' ($errResp.status -eq 'error')
} else {
    Check '无效命令返回 status=error' $errCaught
}

# ═══════════════════════════════════════════════════
# 场景 13：Waypoint 范围验证
# ═══════════════════════════════════════════════════
Step 'Waypoint 范围验证：lat ∈ [-90,90], lon ∈ [-180,180], alt ≥ 0'

# 合法航点
$validMission = @{
    items = @(
        @{ cmd = 'waypoint'; lat = 22.5907; lon = 113.9345; alt = 30; holdTime = 0 }
    )
} | ConvertTo-Json -Depth 4
$validResp = Invoke-Api 'Post' "/drones/$Sysid/mission" $validMission
Check '合法航点上传成功' ($validResp.status -eq 'ok')

# 非法纬度（> 90）
$badLatMission = @{
    items = @(
        @{ cmd = 'waypoint'; lat = 95.0; lon = 113.9345; alt = 30; holdTime = 0 }
    )
} | ConvertTo-Json -Depth 4
$badLatResp = $null
$badLatErr = $false
try {
    $badLatResp = Invoke-Api 'Post' "/drones/$Sysid/mission" $badLatMission
} catch {
    $badLatErr = $true
}
if ($badLatResp) {
    Check '非法纬度(>90)被拒绝' ($badLatResp.status -eq 'error')
} else {
    Check '非法纬度(>90)被拒绝' $badLatErr
}

# 非法经度（< -180）
$badLonMission = @{
    items = @(
        @{ cmd = 'waypoint'; lat = 22.5907; lon = -200.0; alt = 30; holdTime = 0 }
    )
} | ConvertTo-Json -Depth 4
$badLonResp = $null
$badLonErr = $false
try {
    $badLonResp = Invoke-Api 'Post' "/drones/$Sysid/mission" $badLonMission
} catch {
    $badLonErr = $true
}
if ($badLonResp) {
    Check '非法经度(<-180)被拒绝' ($badLonResp.status -eq 'error')
} else {
    Check '非法经度(<-180)被拒绝' $badLonErr
}

# 非法高度（< 0）
$badAltMission = @{
    items = @(
        @{ cmd = 'waypoint'; lat = 22.5907; lon = 113.9345; alt = -10; holdTime = 0 }
    )
} | ConvertTo-Json -Depth 4
$badAltResp = $null
$badAltErr = $false
try {
    $badAltResp = Invoke-Api 'Post' "/drones/$Sysid/mission" $badAltMission
} catch {
    $badAltErr = $true
}
if ($badAltResp) {
    Check '非法高度(<0)被拒绝' ($badAltResp.status -eq 'error')
} else {
    Check '非法高度(<0)被拒绝' $badAltErr
}

# ═══════════════════════════════════════════════════
# 结果汇总
# ═══════════════════════════════════════════════════
if ($Fail -eq 0) {
    Write-Host 'ALL SDK INTEGRATION TESTS PASSED' -ForegroundColor Green
    exit 0
} else {
    Write-Host 'SDK INTEGRATION TESTS FAILED' -ForegroundColor Red
    exit 1
}
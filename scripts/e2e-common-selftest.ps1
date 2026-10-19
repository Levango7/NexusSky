# e2e 公共库自检（pwsh 5.1 / 7+ 都可跑）
#
# 为什么要它：`Read-E2eErrorBody` 的两条分支分别对应 PowerShell 5.1 的
# `HttpWebResponse.GetResponseStream()` 与 7+ 的 `HttpResponseMessage.Content`。
# 本机只有 5.1 时，7+ 那条分支**测不到**——而 CI 的 Linux runner 跑的正是 pwsh。
# 于是"7+ 分支写错了"这种错误在本机永远是绿的。
#
# 办法：构造两个**形状**不同但都合理的假 Response，直接喂给读取器，验证分派逻辑。
# 这测的是"按方法名分派"，不需要真的装 pwsh。
#
# 用法：powershell -ExecutionPolicy Bypass -File scripts/e2e-common-selftest.ps1

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
. (Join-Path $PSScriptRoot 'e2e-common.ps1')

$script:Fail = 0

function Assert-True($cond, $msg) {
    if ($cond) {
        Write-Host "[PASS] $msg"
    } else {
        Write-Host "[FAIL] $msg"
        $script:Fail++
    }
}

$Payload = '{"status":"error","result":"command door_open rejected: dock state=OPEN"}'

# ---- 形状 1：PowerShell 5.1 的 HttpWebResponse（有 GetResponseStream，无 Content）----
$ms51 = New-Object System.IO.MemoryStream
$bytes = [System.Text.Encoding]::UTF8.GetBytes($Payload)
$ms51.Write($bytes, 0, $bytes.Length)
$ms51.Position = 0

$resp51 = New-Object psobject
$resp51 | Add-Member -MemberType ScriptMethod -Name GetResponseStream -Value { $script:ms51 }
# 明确不给 Content（5.1 的 WebResponse 没有这个属性）
$resp51 | Add-Member -MemberType NoteProperty -Name StatusCode -Value 409

$exc51 = New-Object psobject
$exc51 | Add-Member -MemberType NoteProperty -Name Response -Value $resp51
$rec51 = New-Object psobject
$rec51 | Add-Member -MemberType NoteProperty -Name Exception -Value $exc51

# ---- 形状 2：PowerShell 7+ 的 HttpResponseMessage（无 GetResponseStream，有 Content）----
$content7 = New-Object psobject
$content7 | Add-Member -MemberType ScriptMethod -Name ReadAsStringAsync -Value {
    $tcs = New-Object 'System.Threading.Tasks.TaskCompletionSource[string]'
    $tcs.SetResult($script:Payload)
    return $tcs.Task
}
$resp7 = New-Object psobject
$resp7 | Add-Member -MemberType NoteProperty -Name Content -Value $content7
$resp7 | Add-Member -MemberType NoteProperty -Name StatusCode -Value 409

$exc7 = New-Object psobject
$exc7 | Add-Member -MemberType NoteProperty -Name Response -Value $resp7
$rec7 = New-Object psobject
$rec7 | Add-Member -MemberType NoteProperty -Name Exception -Value $exc7

Write-Host '=== 5.1 形状（GetResponseStream）==='
$got51 = Read-E2eErrorBody $rec51
Assert-True ($null -ne $got51) '5.1 形状能读出响应体（不为 null）'
Assert-True ($got51 -eq $Payload) "5.1 形状内容逐字一致（实际长度 $($got51.Length)）"

Write-Host ''
Write-Host '=== 7+ 形状（HttpResponseMessage.Content）==='
$got7 = Read-E2eErrorBody $rec7
Assert-True ($null -ne $got7) '7+ 形状能读出响应体（不为 null）'
Assert-True ($got7 -eq $Payload) "7+ 形状内容逐字一致（实际长度 $($got7.Length)）"

Write-Host ''
Write-Host '=== 兜底与边界 ==='
Assert-True ($null -eq (Read-E2eErrorBody $null)) '传入 null 返回 null，不抛'
$recEmpty = New-Object psobject
$recEmpty | Add-Member -MemberType NoteProperty -Name Exception -Value (New-Object psobject)
Assert-True ($null -eq (Read-E2eErrorBody $recEmpty)) '异常里没有 Response 时返回 null，不抛'

# ---- Linux /proc 分支：实参位置的 [int]$x.Name 不成立 ----------------------------
#
# 背景：Stop-E2eStaleProcesses 的 Linux 分支原来写的是
#     Stop-Process -Id [int]$procDir.Name -Force
# PowerShell 在实参位置不把这当类型转换，会拼成字符串 "[int]/proc/2977.Name"，
# -Id 绑不上 [int] 而直接抛绑定错误；该错误发生在 -ErrorAction 生效之前，压不住，
# 于是整个 e2e step 崩掉。CI 上首次暴露于
# "E2E B2 B-type scripts (network / rf / vision-pixels) — observe"：
#     Stop-E2eStaleProcesses: scripts/e2e-network.ps1:74
#     Cannot bind parameter 'Id'. Cannot convert value "[int]/proc/2977.Name" ...
#
# 那个分支只在 Linux 上跑，Windows 本机进不来，所以单靠"跑一遍"测不到。
# 但这个坑是 **PowerShell 解析层**的，与平台无关：实参位置加不加括号，
# 在任何平台上行为一致。因此这里用假对象直接喂那一步，把绑定错误钉住。
#
# 用一个几乎不可能存在的 pid（int 上界）：进程不存在时 Stop-Process 抛的是
# ProcessNotFoundException，属非终止错误，被 -ErrorAction SilentlyContinue 吞掉；
# 而**绑定错误**是终止错误，catch 得到 —— 两者可区分。
Write-Host ''
Write-Host '=== Linux /proc 分支：pid 转换 ==='
$fakeProcDir = New-Object psobject
$fakeProcDir | Add-Member -MemberType NoteProperty -Name Name -Value ([string][int]::MaxValue)

$bindingError = $null
try {
    Stop-E2eProcByDir -ProcDir $fakeProcDir
} catch {
    $bindingError = $_.Exception.Message
}
Assert-True ($null -eq $bindingError) `
    "Stop-E2eProcByDir 把 ProcDir.Name 当真 int 用（去掉括号会抛：$bindingError）"

# 同一处若写成字符串拼接，返回值就不是 int
$pidCast = [int]($fakeProcDir.Name)
Assert-True ($pidCast -is [int]) '假目录的 Name 能被真正转成 int'

# ---- 调用点也要守住：只测 helper 的话，把调用点改回内联写法不会被发现 ----------
#
# 行为用例只覆盖了 Stop-E2eProcByDir 本身。若有人把 Stop-E2eStaleProcesses 的
# 调用点改回 `Stop-Process -Id [int]$procDir.Name`（helper 留着不用），
# 行为用例仍然是绿的，而 Linux 上会重新崩。
#
# 这条 Linux 分支在本机（Windows）根本进不来，没有别的方式能观测到它；
# 所以这里退一步做**源码级**断言：库里不允许再出现"实参位置紧跟 -Id 的裸转换"。
# 宁可漏（出现新写法导致断言误报），不可漏（旧的坏写法复活）。
$commonPath = Join-Path $PSScriptRoot 'e2e-common.ps1'
$commonSrc = [System.IO.File]::ReadAllText($commonPath)
# 先剥掉注释：函数上方的说明里**故意**写着那个坏写法的示例，
# 若不剥，断言会把自己人判红。块注释 <#...#> 与行注释 #... 都要去。
$codeOnly = [regex]::Replace($commonSrc, '(?s)<#.*?#>', '')
$codeOnly = [regex]::Replace($codeOnly, '(?m)#.*$', '')
$bareCast = [regex]::Matches($codeOnly, '-Id\s+\[int\]\$')
Assert-True ($bareCast.Count -eq 0) `
    "e2e-common.ps1 里没有裸的 `-Id [int]` 实参写法（发现 $($bareCast.Count) 处；这种写法在实参位置不成立）"
Assert-True ($commonSrc -match 'Stop-E2eProcByDir') 'e2e-common.ps1 的 Linux 分支确实走 Stop-E2eProcByDir'
# 只看"出现过"不够：函数定义本身就满足。要的是**调用点**还在
# （定义 1 处 + 调用 1 处 = 2 处；把调用注释掉就只剩 1 处）。
$helperUses = [regex]::Matches($codeOnly, 'Stop-E2eProcByDir').Count
Assert-True ($helperUses -ge 2) `
    "剥注释后 Stop-E2eProcByDir 至少出现 2 次（定义 + 调用），实际 $helperUses 次 —— 调用点被删/注释掉会让 Linux 分支不再清理残留"

Write-Host ''
if ($script:Fail -eq 0) {
    Write-Host '=== e2e-common-selftest: ALL PASS ==='
    exit 0
} else {
    Write-Host "=== e2e-common-selftest: $script:Fail FAILED ==="
    exit 1
}
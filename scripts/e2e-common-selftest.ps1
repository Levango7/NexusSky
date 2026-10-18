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

Write-Host ''
if ($script:Fail -eq 0) {
    Write-Host '=== e2e-common-selftest: ALL PASS ==='
    exit 0
} else {
    Write-Host "=== e2e-common-selftest: $script:Fail FAILED ==="
    exit 1
}
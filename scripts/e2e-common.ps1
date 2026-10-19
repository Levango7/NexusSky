<#
.SYNOPSIS
    NexusSky e2e 脚本的跨平台公共库（Windows / Linux 通用，pwsh 5.1 与 7+ 均可用）。

.DESCRIPTION
    为什么需要：这批 e2e 脚本原本只在 Windows 上跑，埋了四个平台依赖——
      1) jar 路径用反斜杠字面量（Join-Path 'cloud-backend\target\x.jar'）：
         Linux 上反斜杠不是分隔符，Test-Path 恒 false，报"jar 不存在"；
      2) java 可执行找 java.exe：Linux 上叫 java；
      3) 清残留进程用 Get-CimInstance Win32_Process：Linux 没有，
         而**静默失败比报错更糟**——清理退化成什么都不做，端口被上次残留占住，
         表现为莫名其妙的超时（e2e-signing.ps1 踩过并写在注释里）；
      4) $env:TEMP：Linux runner 上通常未设置。

    本库把这四件事各收一个函数，脚本 dot-source 后直接用。
    进程枚举的平台分支照 e2e-signing.ps1 的既有写法（Windows CIM / 非 Windows ps），
    不发明第二套。

.NOTES
    用法（脚本开头）：
        . (Join-Path $PSScriptRoot 'e2e-common.ps1')
        $java = Get-E2eJava
        $backendJar = Get-E2eJar -Root $Root -Module 'cloud-backend' -Jar 'aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar'
        $tmp = Get-E2eTempDir
        Stop-E2eStaleProcesses -Marker 'formation-e2e'      # 只杀自己这轮留痕的
#>

# 是否 Windows（pwsh 5.1 没有 $IsWindows 自动变量，用 PSVersionTable 兜底）。
function Test-E2eWindows {
    if (Get-Variable -Name IsWindows -Scope Script -ErrorAction SilentlyContinue) {
        return [bool]$IsWindows
    }
    return $true   # PowerShell 5.1 只在 Windows 上存在
}

<#
    找 java 可执行文件。顺序：AF_JAVA 然后 JAVA_HOME 然后 AF_JDK17_HOME 最后 PATH 里的 java。
    返回绝对路径或 'java' / 'java.exe'（后者交给 PATH 解析）。
#>
function Get-E2eJava {
    $isWin = Test-E2eWindows
    $exe = if ($isWin) { 'java.exe' } else { 'java' }
    if ($env:AF_JAVA) { return $env:AF_JAVA }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/$exe"))) {
        return Join-Path $env:JAVA_HOME "bin/$exe"
    }
    if ($env:AF_JDK17_HOME -and (Test-Path (Join-Path $env:AF_JDK17_HOME "bin/$exe"))) {
        return Join-Path $env:AF_JDK17_HOME "bin/$exe"
    }
    return $exe
}

<#
    平台无关地拼 jar 路径：Get-E2eJar -Root $Root -Module 'cloud-backend' -Jar 'x.jar'
    等价于 Root/Module/target/Jar，Windows 上自动用反斜杠。
    注意不能写成 Join-Path $Root 'cloud-backend\target\x.jar'——Linux 上反斜杠
    是文件名的普通字符，不是分隔符，那样会拼出一个不存在的路径。
#>
function Get-E2eJar {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Module,
        [Parameter(Mandatory = $true)][string]$Jar
    )
    return (Join-Path (Join-Path (Join-Path $Root $Module) 'target') $Jar)
}

# 临时目录：Windows 用 $env:TEMP，Linux 上它常未设置，回退 /tmp。
function Get-E2eTempDir {
    if ($env:TEMP) { return $env:TEMP }
    if ($env:TMP) { return $env:TMP }
    return '/tmp'
}

<#
    按命令行标记杀掉上一轮残留的 e2e java 进程（只杀自己留痕的，不按 java 一刀切）。
    跨平台：Windows 走 CIM；Linux 走 /proc。

    **不要用 `& ps -eo pid,args`**：PowerShell 里 `ps` 是 Get-Process 的别名
    （全平台通用，别名优先于 PATH 查找），`-eo` 会直接 parameter binding 失败。
    e2e-signing.ps1 早年那么写，Linux 分支从没真正跑起来过——清理静默失效，
    只是被 CI 的 continue-on-error 掩盖了。读 /proc/*/cmdline 没这个坑，
    也不需要外部依赖。

    参数 Marker：命令行里出现的唯一标记（各脚本启动 java 时带 -D<marker>=true）。
#>
<#
.SYNOPSIS
    按 /proc/<pid> 目录杀掉一个进程。

.DESCRIPTION
    这个函数**单独存在**，是因为原来的写法在 Linux 上必然抛异常：

        Stop-Process -Id [int]$procDir.Name -Force

    PowerShell 在**实参位置**不把 `[int]$x.Name` 当作类型转换 —— 它被解析成
    字符串拼接（"[int]" + 路径 + ".Name"），于是 `-Id` 收到的是
    "[int]/proc/2977.Name"，绑定到 [int] 参数直接失败：

        Cannot bind parameter 'Id'. Cannot convert value
        "[int]/proc/2977.Name" to type "System.Int32".

    参数绑定失败发生在 `-ErrorAction SilentlyContinue` 生效之前，
    **压不住**（这是它当时把整个 e2e step 带崩的原因）。

    修法是加括号：`([int]$procDir.Name)`。但加括号这件事本身没有测试能守住
    —— 原来的分支只在 Linux 上跑，Windows 本机根本进不来。
    所以把"按目录杀进程"这一步单独抽出来，自检脚本用假对象直接喂它，
    绑定错误一旦回来就会被钉住（见 e2e-common-selftest.ps1）。
#>
function Stop-E2eProcByDir {
    param([Parameter(Mandatory = $true)]$ProcDir)

    # 括号不能省：去掉就是上面注释里那个必然抛异常的写法。
    Stop-Process -Id ([int]$ProcDir.Name) -Force -ErrorAction SilentlyContinue
}

function Stop-E2eStaleProcesses {
    param([Parameter(Mandatory = $true)][string]$Marker)

    $hit = 0
    if (Test-E2eWindows) {
        Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
            ForEach-Object {
                if ($_.CommandLine -and $_.CommandLine -match $Marker) {
                    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
                    $hit++
                }
            }
    } elseif (Test-Path '/proc') {
        # /proc/<pid>/cmdline 以 NUL 分隔参数；还原成空格串后做标记匹配
        foreach ($procDir in (Get-ChildItem -Path '/proc' -Directory -ErrorAction SilentlyContinue)) {
            if ($procDir.Name -notmatch '^\d+$') { continue }
            $raw = $null
            try { $raw = Get-Content -LiteralPath (Join-Path $procDir.FullName 'cmdline') -Raw -ErrorAction Stop } catch { continue }
            if (-not $raw) { continue }
            $cmd = $raw -replace "`0", ' '
            if ($cmd -match 'java' -and $cmd -match $Marker) {
                Stop-E2eProcByDir -ProcDir $procDir
                $hit++
            }
        }
    }
    return $hit
}

<#
    JDK 版本自检：JDK8 跑不了 record 语法（class 61 vs 52），会静默杀掉 sim。
    失败直接 throw（调用方用 try/catch 转成 FAIL 前置）。
#>
function Assert-E2eJava17 {
    param([Parameter(Mandatory = $true)][string]$JavaExe)

    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $raw = & $JavaExe -version 2>&1
    $ErrorActionPreference = $prev
    $v = (@($raw) | ForEach-Object { "$_" } | Select-Object -First 1)
    if ($v -notmatch '"(\d+)(\.(\d+))?') { return }
    $major = [int]$Matches[1]
    if (-not $Matches[3]) { $minor = 0 } else { $minor = [int]$Matches[3] }
    $ok = if ($major -eq 1) { $minor -ge 17 } else { $major -ge 17 }
    if (-not $ok) {
        throw "需要 Java >= 17，当前: $v（设 AF_JAVA 或 JAVA_HOME 指向 JDK17）"
    }
}

<#
.SYNOPSIS
    从 HTTP 调用的异常里取出错误响应体（跨 PowerShell 版本）。

.DESCRIPTION
    为什么需要：e2e 要断言 4xx 的**响应体**（例如 409 必须在 result 里点名当前
    状态），但 `Invoke-WebRequest` 遇非 2xx 会抛异常，响应体只能从异常里捞。

    而"怎么捞"在两个 PowerShell 版本上**类型不同**，这是实测踩到的：
      - **PowerShell 5.1**：`$_.Exception.Response` 是 `System.Net.WebException`
        包的 `HttpWebResponse`，有 `GetResponseStream()`，读得到。
      - **PowerShell 7+**（pwsh，Linux runner 用的就是它）：`$_.Exception.Response`
        是 `System.Net.Http.HttpResponseMessage`，**根本没有 GetResponseStream 方法**
        ——调用会抛 MethodNotFound。而 pwsh 里 `$_.ErrorDetails.Message` 才是可读的。

    也就是说照着 5.1 写的读法在 pwsh 上必然失败，且被常见的 `catch {}` 静默吞掉
    → 响应体变成 `$null` → 断言"响应体说明了 X"时括号里是空的，表现为断言失败
    而不是可诊断的报错。e2e-dock 的 CI 失败正是这一条（实测：后端 409 体里确实有
    `dock state=OFFLINE`，是 harness 读不到）。

.PARAMETER ErrorRecord
    `try { Invoke-WebRequest ... } catch { $_ }` 捕获到的错误记录。

.OUTPUTS
    响应体字符串；任何一步失败都返回 `$null`（不 throw，让调用方自己决定怎么断言）。
#>
function Read-E2eErrorBody {
    param([Parameter(Mandatory = $false)]$ErrorRecord)

    if ($null -eq $ErrorRecord) { return $null }

    # 1) PowerShell 5.1：WebResponse.GetResponseStream()
    try {
        $resp = $ErrorRecord.Exception.Response
        if ($null -ne $resp) {
            if ($null -ne $resp.PSObject.Methods['GetResponseStream']) {
                $stream = $resp.GetResponseStream()
                if ($null -ne $stream) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    $text = $reader.ReadToEnd()
                    if ($text) { return $text }
                }
            }
            # 2) PowerShell 7+：HttpResponseMessage.Content.ReadAsStringAsync()
            #    在 **Content 上**找方法，而不是在 Response 上找 'get_Content'：
            #    后者依赖对象是真实 .NET 类型（属性 getter 才叫 get_Content），
            #    Response 一旦被包装/替换就漏判并静默返回 null。
            #    （自检 scripts/e2e-common-selftest.ps1 正是靠这条抓到的）
            $content = $resp.Content
            if ($null -ne $content -and $null -ne $content.PSObject.Methods['ReadAsStringAsync']) {
                $task = $content.ReadAsStringAsync()
                $text = $task.GetAwaiter().GetResult()
                if ($text) { return $text }
            }
        }
    } catch {
        # 落到下面的 ErrorDetails 兜底
    }

    # 3) 兜底：ErrorDetails.Message（pwsh 上通常可读）
    try {
        $details = $ErrorRecord.ErrorDetails.Message
        if ($details) { return $details }
    } catch {
        # 确实读不到，返回 $null
    }

    return $null
}

<#
.SYNOPSIS
    跨平台 JSON 请求助手：返回 @{ code; body }，4xx 也能读到响应体。

.DESCRIPTION
    此前每个 e2e 脚本各自复制一份 `Try-Json`，全仓共 9 份 / 4 个变体：
      - 1 份用 5.1 专有的 GetResponseStream（pwsh 上必然读不到，见上）
      - **6 份根本没有读错误体的分支** —— 那些脚本里所有 4xx 响应体断言都在对
        `$null` 做，属于"看起来测了其实没测"。
    把能力收进公共库一次，各脚本只调用，才能保证只有一份需要维护。

.PARAMETER Method  HTTP 方法（GET/POST/...）。
.PARAMETER Uri     完整 URL。
.PARAMETER Body    请求体字符串（可选）。
.PARAMETER Token   Bearer 令牌（可选）。
#>
function Invoke-E2eJson {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $false)][AllowNull()]$Body,
        [Parameter(Mandatory = $false)][AllowNull()][string]$Token,
        [int]$TimeoutSec = 15
    )

    $req = @{ Method = $Method; Uri = $Uri; TimeoutSec = $TimeoutSec; ContentType = 'application/json' }
    if ($PSBoundParameters.ContainsKey('Body') -and $null -ne $Body) { $req['Body'] = $Body }
    if ($Token) { $req['Headers'] = @{ Authorization = "Bearer $Token" } }

    try {
        $r = Invoke-WebRequest @req -UseBasicParsing
        $parsed = $null
        try { $parsed = $r.Content | ConvertFrom-Json } catch {}
        return @{ code = [int]$r.StatusCode; body = $parsed }
    } catch {
        $status = $null
        try { $status = [int]$_.Exception.Response.StatusCode } catch {}
        $text = Read-E2eErrorBody $_
        $parsed = $null
        if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $text } }
        return @{ code = if ($status) { $status } else { 0 }; body = $parsed }
    }
}

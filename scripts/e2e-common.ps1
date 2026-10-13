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
                Stop-Process -Id [int]$procDir.Name -Force -ErrorAction SilentlyContinue
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

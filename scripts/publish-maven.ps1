<#
.SYNOPSIS
    NexusSky SDK Java - Maven Central 发布脚本（PowerShell 版）

.DESCRIPTION
    自动化发布流程：环境检查 → 构建 JAR/Sources/Javadoc → GPG 签名 → 上传 Sonatype OSSRH
    支持 dry-run 模式（只构建不上传）

.PARAMETER SonatypeUsername
    Sonatype OSSRH 用户名

.PARAMETER SonatypePassword
    Sonatype OSSRH 密码

.PARAMETER GpgKeyId
    GPG 签名密钥 ID（如 ABCDEF1234567890）

.PARAMETER DryRun
    只构建不上传，用于验证构建流程

.EXAMPLE
    .\publish-maven.ps1 -DryRun
    .\publish-maven.ps1 -SonatypeUsername "your-username" -SonatypePassword "your-password" -GpgKeyId "ABCDEF1234567890"
#>

param(
    [string]$SonatypeUsername = "",
    [string]$SonatypePassword = "",
    [string]$GpgKeyId = "",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path "$PSScriptRoot/.."
$SdkJavaDir = Join-Path $ProjectRoot "sdk-java"

function Write-Step([string]$msg) {
    Write-Host "`n[STEP] $msg" -ForegroundColor Cyan
}

function Write-Ok([string]$msg) {
    Write-Host "[OK] $msg" -ForegroundColor Green
}

function Write-Warn([string]$msg) {
    Write-Host "[WARN] $msg" -ForegroundColor Yellow
}

function Write-Err([string]$msg) {
    Write-Host "[ERROR] $msg" -ForegroundColor Red
}

function Check-Command([string]$cmd, [string]$minVersion = "") {
    try {
        $result = & $cmd --version 2>&1
        if ($LASTEXITCODE -ne 0 -and $result -eq $null) {
            throw "$cmd 未找到"
        }
        if ($minVersion -ne "") {
            $versionStr = ($result -split "`n")[0]
            if ($versionStr -notmatch $minVersion) {
                Write-Warn "$cmd 版本可能不满足要求: $versionStr (建议 >= $minVersion)"
            }
        }
        Write-Ok "$cmd 可用: $($result -split "`n" | Select-Object -First 1)"
        return $true
    } catch {
        Write-Err "$cmd 不可用: $_"
        return $false
    }
}

# ====== 1. 环境检查 ======
Write-Step "环境检查"

$javaOk = Check-Command "java" "17"
if (-not $javaOk) {
    Write-Err "需要 JDK 17 或更高版本。请安装并设置 JAVA_HOME。"
    exit 1
}

$mavenOk = Check-Command "mvn" "3.6"
if (-not $mavenOk) {
    Write-Err "需要 Maven 3.6 或更高版本。"
    exit 1
}

if (-not $DryRun) {
    $gpgOk = Check-Command "gpg" "2.2"
    if (-not $gpgOk) {
        Write-Err "非 dry-run 模式需要 GPG 用于签名。请安装 GPG 并导入密钥。"
        exit 1
    }

    if ([string]::IsNullOrWhiteSpace($SonatypeUsername)) {
        Write-Err "非 dry-run 模式需要 -SonatypeUsername 参数"
        exit 1
    }
    if ([string]::IsNullOrWhiteSpace($SonatypePassword)) {
        Write-Err "非 dry-run 模式需要 -SonatypePassword 参数"
        exit 1
    }
    if ([string]::IsNullOrWhiteSpace($GpgKeyId)) {
        Write-Err "非 dry-run 模式需要 -GpgKeyId 参数"
        exit 1
    }
}

Write-Ok "环境检查通过"

# ====== 2. 构建 JAR + Sources JAR + Javadoc JAR ======
Write-Step "构建 JAR + Sources JAR + Javadoc JAR"

Push-Location $SdkJavaDir
try {
    $buildArgs = @("clean", "package", "-DskipTests")
    Write-Host "执行: mvn $($buildArgs -join ' ')"
    & mvn @buildArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Maven 构建失败"
        exit 1
    }

    # 验证产物
    $targetDir = Join-Path $SdkJavaDir "target"
    $jarFile = Get-ChildItem -Path $targetDir -Filter "nexussky-sdk-java-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "-sources|-javadoc" } |
        Select-Object -First 1
    $sourcesJar = Get-ChildItem -Path $targetDir -Filter "*-sources.jar" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    $javadocJar = Get-ChildItem -Path $targetDir -Filter "*-javadoc.jar" -ErrorAction SilentlyContinue |
        Select-Object -First 1

    if (-not $jarFile) {
        Write-Err "主 JAR 文件未找到"
        exit 1
    }
    Write-Ok "主 JAR: $($jarFile.Name)"

    if (-not $sourcesJar) {
        Write-Warn "Sources JAR 未找到，尝试单独构建..."
        & mvn source:jar -DskipTests
        $sourcesJar = Get-ChildItem -Path $targetDir -Filter "*-sources.jar" -ErrorAction SilentlyContinue |
            Select-Object -First 1
    }
    if ($sourcesJar) { Write-Ok "Sources JAR: $($sourcesJar.Name)" }
    else { Write-Warn "Sources JAR 仍未找到" }

    if (-not $javadocJar) {
        Write-Warn "Javadoc JAR 未找到，尝试单独构建..."
        & mvn javadoc:jar -DskipTests
        $javadocJar = Get-ChildItem -Path $targetDir -Filter "*-javadoc.jar" -ErrorAction SilentlyContinue |
            Select-Object -First 1
    }
    if ($javadocJar) { Write-Ok "Javadoc JAR: $($javadocJar.Name)" }
    else { Write-Warn "Javadoc JAR 仍未找到" }

} finally {
    Pop-Location
}

# ====== 3. GPG 签名 ======
if (-not $DryRun) {
    Write-Step "GPG 签名"

    Push-Location $SdkJavaDir
    try {
        $signArgs = @("gpg:sign", "-Dgpg.keyId=$GpgKeyId", "-DskipTests")
        Write-Host "执行: mvn $($signArgs -join ' ')"
        & mvn @signArgs
        if ($LASTEXITCODE -ne 0) {
            Write-Err "GPG 签名失败。请确认密钥 ID 正确且 GPG agent 可用。"
            Write-Host "提示: 如果 GPG 提示输入密码，请确保设置了 GPG_TTY 环境变量。"
            Write-Host "  PowerShell: `$env:GPG_TTY = 'console'"
            exit 1
        }
        Write-Ok "GPG 签名完成"
    } finally {
        Pop-Location
    }
} else {
    Write-Step "GPG 签名（跳过 - dry-run 模式）"
}

# ====== 4. 上传到 Sonatype OSSRH ======
if (-not $DryRun) {
    Write-Step "上传到 Sonatype OSSRH"

    Push-Location $SdkJavaDir
    try {
        $deployArgs = @(
            "deploy",
            "-DskipTests",
            "-Dsonatype.username=$SonatypeUsername",
            "-Dsonatype.password=$SonatypePassword",
            "-P release"
        )
        Write-Host "执行: mvn $($deployArgs -join ' ')"
        & mvn @deployArgs
        if ($LASTEXITCODE -ne 0) {
            Write-Err "上传到 Sonatype OSSRH 失败"
            Write-Host "常见原因:"
            Write-Host "  1. 凭证错误 - 检查 SonatypeUsername/Password"
            Write-Host "  2. GPG 签名未通过 - 检查密钥是否已上传到 keyserver"
            Write-Host "  3. POM 配置问题 - 确认 distributionManagement 已启用"
            exit 1
        }
        Write-Ok "上传到 Sonatype OSSRH 完成"
        Write-Host ""
        Write-Host "下一步: 登录 https://s01.oss.sonatype.org/ 检查 staging 仓库"
        Write-Host "确认无误后点击 Release 完成发布（或配置 autoReleaseAfterClose 自动发布）"
    } finally {
        Pop-Location
    }
} else {
    Write-Step "上传到 Sonatype OSSRH（跳过 - dry-run 模式）"
}

# ====== 完成 ======
Write-Step "发布流程完成"
if ($DryRun) {
    Write-Host "Dry-run 模式: 已完成构建验证，未执行签名和上传。" -ForegroundColor Yellow
    Write-Host "要执行实际发布，请去掉 -DryRun 参数并提供凭证。"
} else {
    Write-Ok "NexusSky SDK Java 已发布到 Maven Central（staging）"
}
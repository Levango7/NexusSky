<#
.SYNOPSIS
    NexusSky SDK Python - PyPI 发布脚本（PowerShell 版）

.DESCRIPTION
    自动化发布流程：环境检查 → 构建 wheel + sdist → 上传 PyPI
    支持 dry-run 模式（只构建不上传）

.PARAMETER PypiToken
    PyPI API Token（以 pypi- 开头）

.PARAMETER DryRun
    只构建不上传，用于验证构建流程

.EXAMPLE
    .\publish-pypi.ps1 -DryRun
    .\publish-pypi.ps1 -PypiToken "pypi-xxxxxxxxxxxxxxxxxxxx"
#>

param(
    [string]$PypiToken = "",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path "$PSScriptRoot/.."
$SdkPythonDir = Join-Path $ProjectRoot "sdk-python"

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

function Check-Command([string]$cmd) {
    try {
        $result = & $cmd --version 2>&1
        if ($LASTEXITCODE -ne 0 -and $result -eq $null) {
            throw "$cmd 未找到"
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

$pythonOk = Check-Command "python"
if (-not $pythonOk) {
    Write-Err "需要 Python 3.8 或更高版本。"
    exit 1
}

$pipOk = Check-Command "pip"
if (-not $pipOk) {
    Write-Err "需要 pip"
    exit 1
}

# 检查构建工具
$buildTools = @("build", "twine")
foreach ($tool in $buildTools) {
    $checkResult = & pip show $tool 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warn "$tool 未安装，正在安装..."
        & pip install $tool
        if ($LASTEXITCODE -ne 0) {
            Write-Err "$tool 安装失败"
            exit 1
        }
    }
    Write-Ok "$tool 已安装"
}

if (-not $DryRun) {
    if ([string]::IsNullOrWhiteSpace($PypiToken)) {
        Write-Err "非 dry-run 模式需要 -PypiToken 参数"
        Write-Host "获取 Token: 登录 https://pypi.org/manage/account/token/ 创建 API Token"
        exit 1
    }
}

Write-Ok "环境检查通过"

# ====== 2. 构建 wheel + sdist ======
Write-Step "构建 wheel + sdist"

Push-Location $SdkPythonDir
try {
    # 清理旧构建产物
    $distDir = Join-Path $SdkPythonDir "dist"
    $buildDir = Join-Path $SdkPythonDir "build"
    if (Test-Path $distDir) { Remove-Item -Recurse -Force $distDir }
    if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }

    Write-Host "执行: python -m build"
    & python -m build
    if ($LASTEXITCODE -ne 0) {
        Write-Err "构建失败"
        exit 1
    }

    # 验证产物
    $wheelFile = Get-ChildItem -Path $distDir -Filter "*.whl" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    $sdistFile = Get-ChildItem -Path $distDir -Filter "*.tar.gz" -ErrorAction SilentlyContinue |
        Select-Object -First 1

    if (-not $wheelFile) {
        Write-Err "wheel 文件未找到"
        exit 1
    }
    Write-Ok "Wheel: $($wheelFile.Name)"

    if (-not $sdistFile) {
        Write-Err "sdist 文件未找到"
        exit 1
    }
    Write-Ok "SDist: $($sdistFile.Name)"

} finally {
    Pop-Location
}

# ====== 3. 上传到 PyPI ======
if (-not $DryRun) {
    Write-Step "上传到 PyPI"

    Push-Location $SdkPythonDir
    try {
        $distPath = Join-Path $SdkPythonDir "dist/*"
        Write-Host "执行: twine upload dist/*"
        & twine upload $distPath -u "__token__" -p $PypiToken
        if ($LASTEXITCODE -ne 0) {
            Write-Err "上传到 PyPI 失败"
            Write-Host "常见原因:"
            Write-Host "  1. Token 错误 - 检查 PypiToken 参数"
            Write-Host "  2. 版本号已存在 - PyPI 不允许重复上传同一版本"
            Write-Host "  3. 网络问题 - 检查网络连接"
            exit 1
        }
        Write-Ok "上传到 PyPI 完成"
        Write-Host ""
        Write-Host "查看发布结果: https://pypi.org/project/aerofleet-sdk/"
    } finally {
        Pop-Location
    }
} else {
    Write-Step "上传到 PyPI（跳过 - dry-run 模式）"
}

# ====== 完成 ======
Write-Step "发布流程完成"
if ($DryRun) {
    Write-Host "Dry-run 模式: 已完成构建验证，未执行上传。" -ForegroundColor Yellow
    Write-Host "要执行实际发布，请去掉 -DryRun 参数并提供 PyPI Token。"
} else {
    Write-Ok "NexusSky SDK Python 已发布到 PyPI"
}
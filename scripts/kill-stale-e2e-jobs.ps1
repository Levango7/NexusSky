# 清理残留的 E2E Java 进程（后端/模拟器）。
# 单独成文件而不是内联 powershell -Command：CMD 会把 $_ 吃掉（转义坑）。
$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*aerofleet-cloud-backend*' -or $_.CommandLine -like '*aerofleet-drone-sim*' }
if (-not $procs) { Write-Host 'no stale java procs'; exit 0 }
foreach ($p in $procs) {
    Write-Host ("kill {0}" -f $p.ProcessId)
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
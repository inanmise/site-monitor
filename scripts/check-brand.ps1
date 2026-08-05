# Marka bekçisi — rename (CertMonitor -> Site Monitor) sonrasi kalinti tarar (check-brand.sh esdegeri).
# Cikti YALNIZ bilincli birakilan satirlari icermeli ("# geriye-uyum" alias'lari; CHANGELOG/docs haric).
$root = Split-Path -Parent $PSScriptRoot
$excludeDirs = @('\.git\', '\node_modules\', '\dist\', '\target\', '\logs\', '\data\', '\docs\')
$excludeFiles = @('*.log', '*.gz', '*.jar', '*.pdf', 'package-lock.json', 'CHANGELOG.md')

Get-ChildItem -Path $root -Recurse -File -Exclude $excludeFiles |
  Where-Object { $p = $_.FullName; -not ($excludeDirs | Where-Object { $p -like "*$_*" }) } |
  Select-String -Pattern 'certmonitor|cert-monitor|cert_monitor' -CaseSensitive:$false |
  Where-Object { $_.Line -notmatch 'geriye-uyum' } |
  ForEach-Object { "$($_.Path):$($_.LineNumber): $($_.Line.Trim())" }

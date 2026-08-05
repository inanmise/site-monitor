$ErrorActionPreference = "Stop"
$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvFile    = Join-Path $ScriptDir ".env"
$JarPattern = Join-Path $ScriptDir "backend\target\*.jar"
$Java       = "C:\Program Files\Zulu\zulu-25\bin\java.exe"

if (-not (Test-Path $EnvFile)) {
    Write-Error ".env not found at $EnvFile"
    exit 1
}

$cfg = @{}
Get-Content $EnvFile | Where-Object { $_ -match '^\s*[^#]\S+=.*' } | ForEach-Object {
    $parts = $_ -split '=', 2
    $cfg[$parts[0].Trim()] = $parts[1].Trim()
}

$jar = Get-ChildItem $JarPattern -Exclude "*-original*" -ErrorAction SilentlyContinue |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) {
    Write-Error "No JAR found - run: mvn -f backend/pom.xml package -DskipTests"
    exit 1
}

# geriye-uyum: .env'de eski CERT_MONITOR_* adlari varsa yeni SITE_MONITOR_* karsiliklarina kopyalanir
foreach ($k in @($cfg.Keys)) {
    if ($k -like 'CERT_MONITOR_*') {   # geriye-uyum
        $newKey = $k -replace '^CERT_MONITOR_', 'SITE_MONITOR_'   # geriye-uyum
        if (-not $cfg.ContainsKey($newKey)) { $cfg[$newKey] = $cfg[$k] }
    }
}

$propMap = @{
    SITE_MONITOR_EMAIL_ENABLED = "site.monitor.email.enabled"
    SPRING_MAIL_HOST           = "spring.mail.host"
    SPRING_MAIL_PORT           = "spring.mail.port"
    SPRING_MAIL_USERNAME       = "spring.mail.username"
    SPRING_MAIL_PASSWORD       = "spring.mail.password"
    SITE_MONITOR_EMAIL_FROM    = "site.monitor.email.from"
    SITE_MONITOR_USERNAME      = "site.monitor.username"
    SITE_MONITOR_PASSWORD      = "site.monitor.password"
    CORS_ALLOWED_ORIGINS       = "site.monitor.cors.allowed-origins"
    OPENSSL_BIN                = "site.monitor.diagnostics.openssl-bin"
    SPRING_PROFILES_ACTIVE     = "spring.profiles.active"
    DB_HOST                    = "DB_HOST"
    DB_PORT                    = "DB_PORT"
    DB_NAME                    = "DB_NAME"
    DB_USER                    = "DB_USER"
    DB_PASSWORD                = "DB_PASSWORD"
}

$dProps = @()
foreach ($envKey in $propMap.Keys) {
    if ($cfg.ContainsKey($envKey) -and $cfg[$envKey] -ne "") {
        $propName = $propMap[$envKey]
        if ($propName -eq "DB_HOST" -or $propName -eq "DB_PORT" -or
            $propName -eq "DB_NAME" -or $propName -eq "DB_USER" -or
            $propName -eq "DB_PASSWORD") {
            $dProps += "-D$($envKey)=$($cfg[$envKey])"
        } else {
            $dProps += "-D$($propName)=$($cfg[$envKey])"
        }
    }
}

$existing = Get-Process java -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Stopping existing Java process (PID $($existing.Id))..."
    Stop-Process -Id $existing.Id -Force
    Start-Sleep -Seconds 2
}

$allArgs = $dProps + @("-jar", $jar.FullName)
Write-Host "Starting $($jar.Name) with Zulu 25..."
Write-Host "  Profile      : $($cfg['SPRING_PROFILES_ACTIVE'])"
Write-Host "  DB host      : $($cfg['DB_HOST']):$($cfg['DB_PORT'])/$($cfg['DB_NAME'])"
Write-Host "  Mail enabled : $($cfg['SITE_MONITOR_EMAIL_ENABLED'])"
Write-Host ""

Start-Process -FilePath $Java `
    -ArgumentList $allArgs `
    -WorkingDirectory (Join-Path $ScriptDir "backend") `
    -RedirectStandardOutput (Join-Path $ScriptDir "backend\app.log") `
    -RedirectStandardError  (Join-Path $ScriptDir "backend\app-err.log") `
    -NoNewWindow

Write-Host "Backend started. Logs: backend\app.log"
Write-Host "Waiting for startup..."
$deadline = (Get-Date).AddSeconds(40)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    try {
        $h = Invoke-RestMethod -Uri "http://localhost:8080/health" -TimeoutSec 2
        if ($h.status -eq "UP") {
            Write-Host "Backend is UP - http://localhost:8080"
            break
        }
    } catch { }
}

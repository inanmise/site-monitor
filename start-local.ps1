# ─────────────────────────────────────────────────────────────────────────────
# start-local.ps1 — Starts the CertMonitor backend for local development.
#
# Usage:
#   .\start-local.ps1
#
# Reads credentials from .env and passes them as JVM -D properties so they
# are guaranteed to reach the Spring application regardless of how PowerShell
# handles environment variable inheritance in child processes.
# ─────────────────────────────────────────────────────────────────────────────

$ErrorActionPreference = "Stop"
$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvFile    = Join-Path $ScriptDir ".env"
$JarPattern = Join-Path $ScriptDir "backend\target\*.jar"
$Java       = "C:\Program Files\Zulu\zulu-21\bin\java.exe"

# ── Read .env ──────────────────────────────────────────────────────────────
if (-not (Test-Path $EnvFile)) {
    Write-Error ".env not found at $EnvFile — copy .env.example and fill in values."
    exit 1
}

$cfg = @{}
Get-Content $EnvFile | Where-Object { $_ -match '^\s*[^#]\S+=.*' } | ForEach-Object {
    $parts      = $_ -split '=', 2
    $cfg[$parts[0].Trim()] = $parts[1].Trim()
}

# ── Resolve JAR ───────────────────────────────────────────────────────────
$jar = Get-ChildItem $JarPattern -Exclude "*-original*" -ErrorAction SilentlyContinue |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) {
    Write-Error "No JAR found at $JarPattern — run: mvn -f backend/pom.xml package -DskipTests"
    exit 1
}

# ── Build -D args from .env keys ─────────────────────────────────────────
# Map env var name → Spring property name
$propMap = @{
    CERT_MONITOR_EMAIL_ENABLED = "cert.monitor.email.enabled"
    SPRING_MAIL_HOST           = "spring.mail.host"
    SPRING_MAIL_PORT           = "spring.mail.port"
    SPRING_MAIL_USERNAME       = "spring.mail.username"
    SPRING_MAIL_PASSWORD       = "spring.mail.password"
    CERT_MONITOR_EMAIL_TO      = "cert.monitor.email.to"
    CERT_MONITOR_EMAIL_FROM    = "cert.monitor.email.from"
    CERT_MONITOR_USERNAME      = "cert.monitor.username"
    CERT_MONITOR_PASSWORD      = "cert.monitor.password"
    CORS_ALLOWED_ORIGINS       = "cert.monitor.cors.allowed-origins"
}

$dProps = @()
foreach ($envKey in $propMap.Keys) {
    if ($cfg.ContainsKey($envKey) -and $cfg[$envKey] -ne "") {
        $dProps += "-D$($propMap[$envKey])=$($cfg[$envKey])"
    }
}

# ── Stop any existing backend ─────────────────────────────────────────────
$existing = Get-Process java -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Stopping existing Java process (PID $($existing.Id))..."
    Stop-Process -Id $existing.Id -Force
    Start-Sleep -Seconds 2
}

# ── Launch ────────────────────────────────────────────────────────────────
$allArgs = $dProps + @("-jar", $jar.FullName)
Write-Host "Starting $($jar.Name) with Zulu 21..."
Write-Host "  Mail enabled : $($cfg['CERT_MONITOR_EMAIL_ENABLED'])"
Write-Host "  SMTP user    : $($cfg['SPRING_MAIL_USERNAME'])"
Write-Host ""

Start-Process -FilePath $Java `
    -ArgumentList $allArgs `
    -WorkingDirectory (Join-Path $ScriptDir "backend") `
    -RedirectStandardOutput (Join-Path $ScriptDir "backend\app.log") `
    -RedirectStandardError  (Join-Path $ScriptDir "backend\app-err.log") `
    -NoNewWindow

Write-Host "Backend started. Logs:"
Write-Host "  Stdout: backend\app.log"
Write-Host "  Stderr: backend\app-err.log"
Write-Host ""
Write-Host "Waiting for startup..."
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    try {
        $h = Invoke-RestMethod -Uri "http://localhost:8080/health" -TimeoutSec 2
        if ($h.status -eq "UP") {
            Write-Host "Backend is UP — http://localhost:8080"
            break
        }
    } catch { }
}

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
    # Outbound proxy + alan adı (RDAP/WHOIS) — .env'de doluysa yerel JVM'e de geçsin
    # (2026-08: burada olmadıkları için yerelde proxy'li senaryolar test edilemiyordu).
    HTTP_PROXY_HOST            = "site.monitor.proxy.host"
    HTTP_PROXY_PORT            = "site.monitor.proxy.port"
    HTTP_PROXY_USER            = "site.monitor.proxy.user"
    HTTP_PROXY_PASS            = "site.monitor.proxy.pass"
    NO_PROXY                   = "site.monitor.proxy.no-proxy"
    DOMAIN_WHOIS_ENABLED       = "site.monitor.domain.whois-enabled"
    DOMAIN_TR_WEB_WHOIS_ENABLED = "site.monitor.domain.tr-web-whois-enabled"
    SPRING_PROFILES_ACTIVE     = "spring.profiles.active"
    SPRING_SESSION_STORE_TYPE  = "spring.session.store-type"   # jdbc: oturum yeniden baslatmada dusmez (2026-09-11)
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

# YALNIZ :8080'i dinleyen sureci durdur — tum java'yi oldurmek VS Code jdt.ls gibi
# ilgisiz sureclere de carpiyordu (2026-08).
$conn8080 = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($conn8080) {
    Write-Host "Stopping process on :8080 (PID $($conn8080.OwningProcess))..."
    Stop-Process -Id $conn8080.OwningProcess -Force
    Start-Sleep -Seconds 2
}

# JVM bayraklari (-Xmx, -Xlog:gc*, NMT, ...) $env:JAVA_OPTS ile gecirilir ve -jar'dan ONCE
# gelmek ZORUNDADIR; -jar'dan sonrasi uygulamanin argumani sayilir ve JVM onlari yok sayar.
# 2026-08-20'ye kadar bu script JAVA_OPTS'u HIC okumuyordu: bellek olcumu icin
# "$env:JAVA_OPTS = '-Xmx512m ...'; .\start-local.ps1" diyen her deneme sessizce
# BAYRAKSIZ bir JVM baslatiyor ve olculen sayilar varsayilan yapilandirmaya ait oluyordu.
# Asagidaki "JVM opts" satiri bu sessiz-hatanin tekrarini engeller: bayraklar gorunmuyorsa
# gecmemislerdir. Dogrulama: jcmd <pid> VM.command_line
$jvmOpts = @()
if ($env:JAVA_OPTS) { $jvmOpts = ($env:JAVA_OPTS -split '\s+') | Where-Object { $_ } }

$allArgs = $jvmOpts + $dProps + @("-jar", $jar.FullName)
Write-Host "Starting $($jar.Name) with Zulu 25..."
Write-Host "  Profile      : $($cfg['SPRING_PROFILES_ACTIVE'])"
Write-Host "  DB host      : $($cfg['DB_HOST']):$($cfg['DB_PORT'])/$($cfg['DB_NAME'])"
Write-Host "  Mail enabled : $($cfg['SITE_MONITOR_EMAIL_ENABLED'])"
Write-Host "  JVM opts     : $(if ($jvmOpts) { $jvmOpts -join ' ' } else { '(yok - varsayilan)' })"
Write-Host ""

# Log dizini MUTLAK verilir. Aksi halde logback'in "logs" varsayilani CALISMA DIZININE gore
# cozulur: bu script backend\logs\ uretirken, jar'i baska bir dizinden baslatan bir komut
# <o dizin>\logs\ uretir. 2026-08-16'da tam bu yuzden bir inceleme yanlis dosyayi okudu
# (canli ornek D:\site-monitor\logs\, test suiti backend\logs\ yaziyordu).
$env:LOGGING_FILE_PATH = Join-Path $ScriptDir "logs"

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

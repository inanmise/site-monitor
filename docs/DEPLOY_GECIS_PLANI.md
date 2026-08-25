# Dağıtım Geçiş Planı — CertMonitor → Site Monitor (rename)

> Hedef kitle: altyapı/ops ekibi. Prod **canlı** olduğu için ad değişikliği yerinde
> `helm upgrade` ile YAPILAMAZ — Helm release adı, chart adı (`sitemonitor-chart`),
> imaj adı (`site-monitor`) ve kaynak adları birlikte değişiyor. Bu plan kesintisiz
> (veya bakım pencereli) geçişin adımlarını tanımlar.
>
> Değişmeyenler: **DB şeması ve DB kimliği** (`dbName/dbUser: certmonitor`), tüm
> `/api/...` yolları, prod host (`certmonitor-prod.example.com` — DNS/URL kapsam dışı,
> ayrıca ele alınacak), uygulama verisi.

## 0. Ön koşullar
- Rename sürümü main'e girmiş ve release edilmiş olmalı (imaj: `ghcr.io/<owner>/site-monitor:v<X.Y.Z>`,
  chart: `oci://ghcr.io/<owner>/sitemonitor-chart`).
- `app_settings` VERİ göçü uygulamanın İÇİNDE otomatiktir: yeni sürüm ilk açılışta
  `cert.monitor.%` anahtarlarını `site.monitor.%`'e, `logging.level.com.certmonitor%`'i
  `com.sitemonitor%`'e idempotent taşır (çakışma guard'lı). Ops tarafında DB adımı YOK.

## 1. İmaj
Yeni imaj yeni adla build+push edilir (release pipeline'ı yapar). Eski
`ghcr.io/<owner>/certmonitor` paketleri SİLİNMEZ — eski sürümlere geri dönüş için gerekli.

## 2. Secret / ConfigMap
Mevcut namespace'te secret'ları **yeni env adlarıyla** (`SITE_MONITOR_USERNAME`,
`SITE_MONITOR_PASSWORD`, `SITE_MONITOR_SECRET_KEY`, `SITE_MONITOR_EMAIL_ENABLED`,
`SITE_MONITOR_EMAIL_FROM`) oluştur/kopyala.
- Acele yok: properties'te `${SITE_MONITOR_X:${CERT_MONITOR_X:...}}` geriye-uyum zinciri
  var — eski `CERT_MONITOR_*` adları çalışmaya devam eder; uygulama açılışta eski adları
  görürse TEK bir WARN basar.
- `SECRET_KEY` DEĞERİ AYNI KALMALI (yalnız anahtar ADI değişiyor) — değer değişirse
  şifreli saklanan SMTP/LDAP/k6 secret'ları çözülemez.

### 2.1 ⚠ ESKİ RELEASE'İN --set / ELLE VERİLMİŞ DEĞERLERİ TAŞINMAZ — kontrol listesi
> 2026-08 prod kesintisi bu adım eksik olduğu için yaşandı: proxy değerleri yalnız eski
> release'in `--set`'lerinde yaşıyordu; taze install'da ConfigMap boş proxy bastı →
> RDAP/registrar sorguları firewall'da timeout aldı, Alan Adı İzleme günlerce veri çekemedi.

Taze install ÖNCESİ eski release'ten dök ve karşılaştır:
`helm get values <eski-release> -n <ns> -a > eski-values.yaml`

| Değer | Values anahtarı | Env | Not |
|---|---|---|---|
| Outbound proxy host/port | `config.httpProxyHost/httpProxyPort` | `HTTP_PROXY_HOST/PORT` | prod'da `environments/master.yaml`'da KALICI — elle Deployment patch'i yapmayın |
| Proxy bypass | `config.noProxy` | `NO_PROXY` | iç sonekler (example.com,intranet.local,…) |
| Proxy kimlik (varsa) | `secret.httpProxyUser/Pass` | `HTTP_PROXY_USER/PASS` | |
| WHOIS kararı | `config.whoisEnabled` | `DOMAIN_WHOIS_ENABLED` | port-43 proxy'den geçemez → prod'da `false` doğru; `.tr` verisi web-whois'ten gelir (`tr-web-whois-enabled` varsayılan açık) |
| Kurumsal CA paketi | `config.caBundlePem` veya DB `site.monitor.trust.ca-bundle-pem` | `TRUST_CA_BUNDLE_PEM` | SSL-inspection proxy'de RDAP PKIX için |
| Uygulama taban URL'i | (Genel Ayarlar `site.monitor.app.base-url` — DB) | `APP_BASE_URL` | e-posta linkleri; eski host kalmasın |
| Log seviyesi | `config.logLevel` | `LOG_LEVEL` | prod'da INFO |

Doğrulama: açılış "ETKİN KONFİGÜRASYON" dökümünde `site.monitor.proxy.host` dolu ve
`RDAP istemcisi proxy üzerinden: <host>:<port>` satırı VAR (yoksa uygulama artık açık
WARN basar: "RDAP istemcisi DOĞRUDAN çıkışta — proxy tanımsız").

## 3. Kurulum stratejisi (infra ile seçilecek)
**(a) Yan yana (önerilen):** aynı namespace'e `helm install site-monitor ...` → doğrulama
sonrası eski `cert-monitor` release'i uninstall.
**(b) Bakım penceresi:** eski release uninstall → yeni install.

Notlar:
- Spring Session JDBC + `scheduler_lock` DB'de olduğundan pod adları değişse de
  oturumlar ve zamanlayıcı kilitleri korunur.
- İki release'in aynı anda scheduler çalıştırması `scheduler_lock` sayesinde güvenlidir;
  yine de geçişte eski release `replicas=0`'a çekilerek ilerlenmesi önerilir.

## 4. Ingress / Route
Kaynak adları yenilenir (`site-monitor`, TLS secret `site-monitor-tls`); **host AYNI kalır**
(URL/DNS bu planın kapsamı dışında).

## 5. Doğrulama
- `/health` UP; login; bir izleme detayı; Ayarlar → SMTP "test e-postası".
- Log dosyaları yeni adla yazılıyor: `/var/log/site-monitor/site-monitor*.log`.
- Prometheus scrape hedefi yeni pod'ları görüyor.
- Açılış banner'ında `ETKİN KONFİGÜRASYON — Site Monitör <sürüm>` ve app_settings
  override'larının etkin olduğu (örn. değiştirilmiş bir eşik değerinin korunması).

## 6. Geri alma
Eski release'i tekrar ölçekle/kur — imaj ve DB değişmedi, geri dönüş güvenli.
(Not: yeni sürüm bir kez açıldıysa `app_settings` anahtarları `site.monitor.*`'e göçmüş
olur; ESKİ sürüm bu anahtarları okumaz → override'lar geri dönüşte varsayılana döner.
Kalıcı geri dönüş gerekirse anahtarları tersine güncelleyen tek SQL yeterlidir:
`UPDATE app_settings SET setting_key='cert.monitor.'||substring(setting_key,14) WHERE setting_key LIKE 'site.monitor.%';`)

## 7. Takip
- **WAF/proxy istisnaları:** giden kontrol User-Agent'ları değişti —
  `SiteMonitor-HttpMonitor/1.0`, `SiteMonitor-KeywordMonitor/1.0`, `SiteMonitor-PortCheck`,
  `SiteMonitor-DomainMonitor/1.0`, `SiteMonitor-PageCheck/1.0`, `... SiteMonitor/1.0` (TR whois).
  Eski `CertMonitor-*` UA'sına tanımlı WAF/rate-limit istisnaları yeni desene güncellenmeli.
- **Log toplayıcı:** filebeat/fluentd benzeri toplayıcılarda `cert-monitor*.log` dosya/dizin
  desenleri `site-monitor*.log` ve `/var/log/site-monitor` olarak güncellenmeli.
- 2 sürüm sonra `CERT_MONITOR_*` env alias'ları ve eski remember-me cookie tanıma
  (`cert-monitor-remember`) kaldırılacak — dağıtımlar o tarihe dek yeni adlara geçmiş olmalı.

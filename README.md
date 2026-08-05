# SSL/TLS Sertifika İzleme Sistemi

Kurumunuzdaki SSL/TLS sertifikalarını, TCP/TLS port erişilebilirliğini, HTTP/S çalışma süresini, DNS kayıtlarını, ICMP ping erişilebilirliğini ve HTTP içerik (keyword) doğrulamasını merkezi olarak izleyen; çok-seviyeli alarm, olay yönetimi ve raporlama sunan tam kapsamlı sistem.

## Özellikler

### Sertifika İzleme
- **Otomatik Sertifika Kontrolü** — Yapılandırılabilir cron ile tüm envanteri tarar (varsayılan: her saat)
- **Çok Seviyeli Uyarı** — WARNING (30g) / HIGH (15g) / CRITICAL (7g) eşikleri, Admin Panel'den ayarlanabilir
- **Zincir Doğrulama** — Eksik/geçersiz sertifika zinciri tespiti (BouncyCastle)
- **İptal Kontrolü** — OCSP öncelikli, CRL yedekli iptal doğrulaması
- **Parmak İzi & Konu Sabitleme** — Sertifika değişimini otomatik tespit eder
- **Zayıf Algoritma Raporu** — MD5/SHA1 imzalı veya kısa anahtarlı sertifikaların tespiti
- **Yenileme Tavsiyeleri** — Yakında dolacak sertifikalar için öneri listesi

### İzleme Tipleri (Monitoring)
- **Port İzleme** — TCP / TLS / HTTP / BANNER / UDP kontrol tipleri; gecikme ölçümü, geçmiş & süre grafiği
- **HTTP/S Uptime** — HTTP durum kodu, yanıt süresi ve SSL bilgisi takibi
- **DNS İzleme** — A/AAAA/CNAME/MX/TXT/NS; değişiklik/rotasyon tespiti, beklenen-değer kilidi, çoklu-resolver tutarlılık (propagation), yavaş-çözümleme (slow) alarmı
- **Ping (ICMP)** — Host erişilebilirliği, RTT ve paket kaybı (konteynerde non-root ICMP)
- **Keyword (İçerik)** — HTTP içerik / anahtar-kelime doğrulaması (occurrence + operatör koşulu)
- **HTTP / Website** — URL uptime (durum kodu pattern / yönlendirme takibi / gecikme) + opsiyonel per-monitör SSL & domain-expiry hatırlatmaları
- **Domain (Alan Adı) Süre Bitişi** — Registrar kayıt bitişi: **RDAP** birincil (IANA bootstrap ile TLD→sunucu, proxy-aware, rdap.org fallback) + **WHOIS/43** fallback (env-gated, `.tr`/nic.tr parser dahil); registrar, EPP status kodları, nameserver; **4-seviyeli OK/WARNING/CRITICAL/UNKNOWN**; değişiklik tespiti (registrar/NS/status → hijack sinyali) + DNS çapraz doğrulama; Public Suffix List ile eTLD+1, IDN → punycode
- **Sayfa Bütünlüğü** — İzlenen sayfanın kod seviyesinde sağlıklı yüklendiğini doğrular (kırık link/kaynak, mixed content, zaman aşımı, içerik anomalisi); Tek Sayfa + Site Tarama modları
- **Senaryo İzleme (k6)** — Kullanıcı tanımlı **k6** scriptlerini periyodik tek-iterasyon çalıştırarak çok adımlı akışları (OIDC/Keycloak login, API zincirleri) uçtan uca izler; k6 imaja gömülü binary olarak, her kontrolde kısa ömürlü **sandboxlu alt süreç** koşar (`--blacklist-ip` ile iç ağ/metadata engellenir, SIGTERM→SIGKILL, temp temizliği); env değişkenleri (secret'lar şifreli), hazır şablonlar, "Test Çalıştır"; sonuç PASS/FAIL/ERROR/TIMEOUT
- **Ortak İzleme Özellikleri** — Monitör-başına alarm hassasiyeti (Nx teyit / Nx kurtarma), mantıksal gruplar, takım bazlı sahiplik & filtreleme, tıkla-filtreli özet dashboard'ları, 4-sekmeli detay (Kontrol / Alarm Geçmişi / Süre Grafiği / Rehber & Notlar), tıkla-izole legend'lı süre grafikleri, izleme-başına Rehber & Notlar; alarm e-postasından detaya deep-link

> **Domain lifecycle & UNKNOWN — neden UNKNOWN de alarmdır:** Bir alan adı yaşam döngüsü
> *registered → active → (yenilenmezse) autoRenewPeriod → redemptionPeriod → pendingDelete → released*
> evrelerinden geçer. `redemptionPeriod`/`pendingDelete`/`serverHold`/`clientHold` EPP kodları anında **CRITICAL** üretir.
> RDAP/WHOIS yanıt vermezse veya bitiş tarihi ayrıştırılamazsa durum **UNKNOWN** olur ve **ayrı bir alarm** çıkar —
> *"veri yok ≠ sorun yok"* (körlük gizli risktir; erişim/proxy/TLD desteği doğrulanmalıdır). `.tr` gibi RDAP'siz TLD'ler
> kurumsal DMZ proxy'sinden WHOIS/43 geçemediğinde UNKNOWN kalır (port-43 egress gerekir); RDAP'lı TLD'ler proxy üzerinden çözülür.

### Alarm & Bildirim
- **Alarm Yaşam Döngüsü** — Teyit (Nx tekrar), otomatik kurtarma, onayla, yeniden bildir, çöz; tam bildirim geçmişi
- **Eskalasyon Kişileri** — Org rol bazlı (PO, TECH, MANAGER, CLEVEL); e-posta + Teams/Slack webhook
- **Outlook-uyumlu E-posta** — VML/MSO-güvenli HTML alarm & çözüm e-postaları, monitör detayına deep-link CTA
- **Olay (Incident) Yönetimi** — Olay kaydı, geçmişi ve görselleri

### Yönetim & Raporlama
- **Kullanıcı & Takım Yönetimi** — Rol tabanlı (USER / AUDIT / ADMIN) + ince-taneli izinler; takım bazlı envanter & izleme sahipliği
- **Haftalık Erişilebilirlik Raporları** — Otomatik haftalık uptime/erişilebilirlik özetleri (e-posta + görsel)
- **Denetim Günlüğü** — Tüm yönetici işlemleri zaman damgalı
- **Rehber & Notlar / Bağlantılar** — İzleme-başına rehber + yapılandırılmış not günlüğü; kurumsal yardım bağlantıları
- **LDAP Entegrasyonu** — Opsiyonel dizin kimlik doğrulama

### Sistem & Operasyon
- **Sistem Sağlığı** — Heartbeat, SMTP istatistikleri, DB latency, tarama istatistikleri, JVM/CPU metrikleri
- **HTTP İstek Gezgini (kalıcı seri)** — Per-endpoint istek/hata/gecikme (p95/p99) zaman serileri; takvim + tıkla-izole legend
- **SQL Playground** — Salt-okunur SQL keşif arayüzü (admin)
- **Giriş Isı Haritası & GeoIP Anomali** — Saat/gün bazlı giriş yoğunluğu; ofis-dışı / coğrafi-hız anomali tespiti
- **Ağ Tanılama** — curl / dig / traceroute / openssl derin tanılama araçları (pod içi)
- **Prometheus Metrikleri** — `/api/admin/system/metrics`
- **REST API** — Harici sistemlerle entegrasyon
- **Web Dashboard** — React SPA, koyu/açık tema, Türkçe/İngilizce
- **Docker & Helm** — Üretime hazır konteyner (non-root, salt-okunur FS) + Kubernetes/Helm

## Teknoloji Yığını

| Katman | Teknoloji |
|--------|-----------|
| Backend | Java 25, Spring Boot 4.1.0, Spring Data JPA |
| Veritabanı | PostgreSQL |
| Sertifika | BouncyCastle (OCSP, CRL, zincir doğrulama) |
| DNS | dnsjava (çok-resolver sorgu, SOA/NS) |
| Frontend | React 18.3, Vite 5, recharts, react-markdown, lucide-react |
| Test | JUnit 5 + Mockito (backend), Vitest + Testing Library (frontend) |
| Konteyner | Docker çok-aşamalı (Node 20 → Maven 3.9/Temurin 25 → **Temurin 25 JRE** Alpine, non-root UID 1000) |
| K8s | Helm chart (sürüm `VERSION` dosyasından) |
| CI/CD | GitHub Actions — release: sürüm bump → Docker/GHCR + Trivy tarama → Helm push → git tag → GitHub Release → develop back-merge |

## Proje Yapısı

```
site-monitor/
├── backend/
│   └── src/main/java/com/sitemonitor/
│       ├── controller/          # REST API (Certificate, Auth, Admin, Monitoring, MonitorNotes,
│       │                        #   Incident, WeeklyReport, System, SqlPlayground, Permission, Ldap…)
│       ├── service/             # İş mantığı (CertificateChecker, Scheduler, MonitoringOutage,
│       │                        #   Escalation, EmailNotification, Ping/Keyword/Dns/PortChecker,
│       │                        #   WeeklyReport, HttpMetricsQuery, Permission…)
│       ├── model/               # JPA entity'leri
│       ├── repository/          # Spring Data repository'leri
│       └── config/              # WebConfig, GlobalExceptionHandler, AuthInterceptor
├── frontend/
│   └── src/
│       ├── components/          # İzleme sayfaları (Ping/Keyword/Port/DnsMonitorPage, DnsDetailModal),
│       │                        #   paylaşımlı (ResponseTimeChart, MonitorStatsBar, MonitorNotes),
│       │                        #   sertifika (CertificatesTable, CertificateModal, StatsPanel…)
│       ├── components/admin/    # Admin (Inventory, Thresholds, Contacts, AlertHistory, UserManager,
│       │                        #   TeamManager, PermissionMatrix, AuditLogViewer, SystemHealth,
│       │                        #   HttpMetricsExplorer, ChartModal, LoginHeatmap, SqlPlayground,
│       │                        #   WeakAlgorithmReport)
│       ├── components/ui/       # Yeniden kullanılabilir (SearchableSelect, Dialog, TimeRangePicker,
│       │                        #   DateTimeRangePicker, MarkdownEditor, SiteMonitorLogo)
│       ├── pages/               # ExpiryForecastPage, WeeklyReportsPage, IncidentHistoryPage, HelpPage
│       ├── api/client.js        # API istemcisi
│       └── i18n/                # Çok dil (TR/EN) & tema yönetimi
├── helm/site-monitor/           # Helm chart + ortam değerleri
│   └── environments/            # master.yaml, develop.yaml, release.yaml
├── scripts/                     # build-image.sh / build-image.ps1
├── .github/workflows/           # ci.yml, docker-build.yml, release.yml
├── Dockerfile                   # Çok aşamalı build (Node 20 → Maven 3.9/Temurin 25 → Temurin 25 JRE)
├── docker-compose.yml
└── sertifikaListesi.txt         # Dosya tabanlı domain listesi (isteğe bağlı)
```

## Kurulum

### Gereksinimler

- Java 25 (Azul Zulu / Eclipse Temurin)
- Maven 3.9+
- Node.js 20+

### Yerel Geliştirme

**1. Backend**

```bash
cd backend
mvn spring-boot:run
```

Backend `http://localhost:8080` adresinde çalışır.

**2. Frontend**

```bash
cd frontend
npm install
npm run dev
```

Frontend `http://localhost:5173` adresinde çalışır.

**3. Giriş**

| Ayar | Varsayılan |
|------|-----------|
| Kullanıcı adı | `user` |
| Parola | `password` |

> Ortam değişkenlerini özelleştirmek için `.env.example` dosyasını `.env` olarak kopyalayın.

### Docker Compose ile Başlatma

```bash
cp .env.example .env
# .env içindeki parolaları düzenleyin
docker-compose up -d
```

Uygulama `http://localhost:8080` adresinde çalışır.

### Docker İmajı Oluşturma

```bash
# Linux/Mac
./scripts/build-image.sh

# Windows
.\scripts\build-image.ps1

# Registry'ye push ile
./scripts/build-image.sh --registry ghcr.io/your-org --push
```

İmaj etiketleri branch'e göre otomatik belirlenir:

| Branch | Etiket |
|--------|--------|
| `master` | `{VERSION}`, `latest` |
| `release/*` | `{VERSION}-rc`, `staging` |
| `develop` | `develop-{sha}`, `develop` |
| diğer | `{branch-adı}-{sha}` |

## Yapılandırma

Tüm ayarlar ortam değişkeni ile yönetilir. Varsayılanlar `application.properties` içindedir.

| Değişken | Varsayılan | Açıklama |
|----------|-----------|----------|
| `SITE_MONITOR_USERNAME` | `user` | Dashboard kullanıcı adı |
| `SITE_MONITOR_PASSWORD` | `password` | Dashboard parolası |
| `DB_URL` | `jdbc:postgresql://localhost:5432/sitemonitor` | Veritabanı bağlantı URL'i |
| `DB_POOL_MAX` | `10` | Maksimum bağlantı havuzu boyutu |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,...` | İzin verilen CORS adresleri |
| `SITE_MONITOR_EMAIL_ENABLED` | `false` | E-posta bildirimlerini etkinleştir |
| `SPRING_MAIL_HOST` | `smtp.gmail.com` | SMTP sunucusu |
| `SPRING_MAIL_PORT` | `587` | SMTP portu |
| `SPRING_MAIL_USERNAME` | — | SMTP kullanıcı adı |
| `SPRING_MAIL_PASSWORD` | — | SMTP parolası |
| `SCHEDULER_CRON` | `0 0 * * * *` | Sertifika kontrol zamanı (her saat) |
| `PARALLEL_WORKERS` | `20` | Paralel kontrol iş parçacığı sayısı |
| `LOGIN_MAX_ATTEMPTS` | `10` | Ardışık başarısız giriş limiti |
| `COOKIE_SECURE` | `false` | HTTPS ortamında `true` yapın |

Üretim ortamı için ek ayarlar `application-prod.properties` dosyasında (`spring.profiles.active=prod`).

### Başlangıç Konfigürasyon Logu

Uygulama her açılışta, çalıştığı **etkin (nihai) konfigürasyonun tamamını** tek bir okunabilir `INFO` bloğu halinde
loglar — operatör pod logundan uygulamanın o anki davranışını tek bakışta görebilsin diye. Blok, tüm ayar kaynaklarını
(varsayılan / `application.properties` / ortam değişkeni-JVM override / DB'deki `app_settings`·SMTP·LDAP) birleştirip
**nihai değeri** basar; kategorilere ayrılır (Uygulama, Sunucu, Veritabanı, Zamanlayıcı, Güvenlik, SMTP, LDAP, Katalog
grupları, Ortam …).

- **Kaynak etiketleri** — her satırın yanında değerin nereden geldiği yazar:
  - `[default]` — ayarlanmamış, gömülü varsayılanla çalışıyor
  - `[config]` — bir `application*.properties` dosyasında literal set edilmiş
  - `[env]` — ortam değişkeni / JVM `-D` parametresi ile override edilmiş
  - `[db]` — admin ekranından (DB) override edilmiş
  - `[runtime]` / `[file]` — çalışma anında hesaplanan (JVM/OS) ya da `VERSION` dosyası
- **Secret maskeleme** — parola/secret/token/anahtar türü değerler `*****` olarak basılır; JDBC URL'e gömülü kimlik
  bilgileri ayıklanır; şifreli saklanan secret'lar (SMTP/LDAP parolası) **asla çözülmez**, yalnız `password_set=true/false`
  bilgisi verilir. `site.monitor.secret-key` için yalnız "configured / not-set" durumu gösterilir.
- **JSON modu** — log-toplama sistemleri için: `STARTUP_CONFIG_LOG_JSON=true` → aynı içerik tek satır JSON (varsayılan
  kapalı, insan-okunur çerçeveli metin).
- **Kapatma** — `STARTUP_CONFIG_LOG=false`.
- DB'ye erişilemeyen bir açılışta blok yine basılır; DB-bağımlı bölümler `okunamadı` der (açılış engellenmez).

### Senaryo İzleme (Scripted Check / k6)

Kullanıcı tanımlı k6 scriptleri periyodik olarak **tek iterasyon** çalıştırılır ve sonucuna göre sağlık kararı üretilir
(PASS/FAIL/ERROR/TIMEOUT). k6 kütüphane olarak gömülmez; imaja eklenmiş sürümlü binary her kontrolde **kısa ömürlü,
sıkı sandboxlu bir alt süreç** olarak koşar (Grafana Synthetic Monitoring deseni).

- **k6 binary'si:** Docker imajına `K6_VERSION` build-arg'ıyla `grafana/k6` imajından kopyalanır. Yerel geliştirmede
  k6 kurulu değilse tür ekranda **"devre dışı — k6 bulunamadı"** görünür; uygulama normal açılır.
- **Güvenlik:** her URL/hedef `--blacklist-ip` ile iç ağ (RFC1918), loopback, link-local ve cloud-metadata
  (169.254.169.254) adreslerine engellenir (kural seti `SsrfGuard` ile tek kaynak); timeout'ta SIGTERM→SIGKILL
  (zombie süreç yok); geçici script/özet dosyaları her durumda silinir; secret env değerleri çıktıda maskelenir.
- **Yetki:** senaryo oluşturma/düzenleme/silme yalnız **ADMIN + TEAM_ADMIN (PO)** (`monitoring.scripted` izni) —
  k6 keyfi kod çalıştırmaktır. Görüntüleme/sonuç okuma sahiplik kurallarına tabidir.
- **Gizli değerler:** parola/token gibi değerleri script gövdesine YAZMAYIN; **ortam değişkeni (secret)** olarak
  tanımlayın (`SecretCipher` ile şifreli saklanır, ekrana/API'ye asla düz metin dönmez) ve script'te `__ENV` üzerinden
  kullanın. Kaydederken script gövdesi desen taramasından geçer (`SCRIPTED_HARDCODED_SECRET_POLICY=WARN|BLOCK`).
- **⚠ İzleme için ayrı bir SERVİS HESABI kullanın** (OIDC/login senaryolarında) — gerçek bir kullanıcı hesabıyla
  izleme yapmayın (hesap kilitlenmesi, MFA, denetim gürültüsü riski).

**OIDC/Keycloak örneği (hazır şablon):** auth endpoint → login formu submit → redirect/code → token exchange →
claim doğrulama; credential'lar `__ENV.USERNAME` / `__ENV.PASSWORD` (secret) üzerinden. Form'daki "Şablon" seçicisinden
yüklenir. Ayrıca basit API zinciri (POST→GET) ve form-login şablonları mevcuttur.

Config anahtarları (admin UI'dan canlı): `site.monitor.scripted.{enabled,pool-size,default-timeout-seconds,
max-timeout-seconds,output-tail-bytes,manual-cooldown-seconds,k6-bin,hardcoded-secret-policy}`,
`site.monitor.metrics.scripted.retention-days`. Micrometer: `scripted.k6.active`, `scripted.k6.queued`.

### Zamanlayıcı Ayarı

`SCHEDULER_CRON` ortam değişkeni ile ya da `application.properties` içinde:

```properties
site.monitor.scheduler.cron=0 0 2 * * *
```

Yukarıdaki örnek her gece 02:00'de çalıştırır. Varsayılan `0 0 * * * *` (her saat başı).

### Uyarı Eşikleri

Admin Paneli → Eşikler sekmesinden arayüz üzerinden ya da `ALERT_DEFAULT_*` değişkenleriyle değiştirilebilir.

Varsayılanlar:

| Seviye | Gün |
|--------|-----|
| WARNING | 30 |
| HIGH | 15 |
| CRITICAL | 7 |

### Domain Listesi

`sertifikaListesi.txt` dosyasına her satıra bir domain yazılır:

```
# Yorum satırı
google.com
github.com:443
api.example.com:8443
internal.app.com:8000
```

Admin Paneli → Envanter üzerinden de domain eklenebilir.

## Kullanım

### Web Dashboard

Giriş yaptıktan sonra:

1. **Dashboard** — Özet istatistikler ve sertifika kartları
2. **Tüm Sertifikalar** — Sayfalanmış, filtrelenebilir tablo
3. **HTTP / Domain / Port / Ping / Keyword / DNS / Uptime İzleme** — Her tip için liste + tıkla-filtreli özet dashboard + detay modali (Kontrol / Alarm Geçmişi / Süre Grafiği / Rehber & Notlar)
4. **Yenileme Tavsiyeleri** — Yakında dolacak sertifikalar için öneri listesi
5. **Olaylar & Haftalık Raporlar** — Olay (incident) geçmişi ve haftalık erişilebilirlik raporları
6. **Aktivite** — Son kontrol ve işlem günlüğü
7. **Admin** — Envanter, kullanıcılar, takımlar, izinler, eşikler, eskalasyon kişileri, uyarılar, denetim, sistem sağlığı, SQL playground

Herhangi bir sertifikaya tıklayarak detaylar, uyarı geçmişi ve notlar görüntülenebilir.

### Manuel Kontrol

Dashboard'da "Şimdi Kontrol Et" düğmesine basın ya da:

```bash
curl -u user:password -X POST http://localhost:8080/api/scheduler/run
```

Belirli bir domain'i hemen kontrol etmek için:

```bash
curl -u user:password http://localhost:8080/api/check/example.com
```

## API Endpoints

Tüm istekler HTTP Basic Auth veya oturum çerezi gerektirir.

### Sertifikalar

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/certificates` | Tüm son kontrol sonuçları |
| GET | `/api/certificates/list` | Sayfalanmış + filtrelenmiş liste |
| GET | `/api/warnings` | Uyarı gerektiren sertifikalar |
| GET | `/api/history/{domain}` | Domain kontrol geçmişi (son 30) |
| GET | `/api/history/{domain}/alerts` | Domain uyarı olayları |
| GET | `/api/check/{domain}` | Domain'i hemen kontrol et |
| GET | `/api/activity` | Aktivite logu (varsayılan son 24 saat) |
| GET | `/api/stats` | İstatistikler (toplam, uyarı, kritik, vb.) |
| GET | `/api/stats/teams` | Takım bazlı istatistikler |
| GET | `/api/renewal-advice` | Yenileme önerileri |
| GET | `/api/alerts/silent-domains` | Sessiz domain listesi |
| POST | `/api/scheduler/run` | Zamanlayıcıyı hemen çalıştır |
| GET | `/api/scheduler/status` | Zamanlayıcı durumu |

### Admin — Envanter

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/admin/inventory` | Envanter listesi (`?showDeleted=true` ile silinenleri dahil et) |
| POST | `/api/admin/inventory` | Domain ekle |
| PUT | `/api/admin/inventory/{id}` | Domain güncelle |
| DELETE | `/api/admin/inventory/{id}` | Domain'i soft-delete ile işaretle |
| POST | `/api/admin/inventory/{id}/restore` | Silinen domain'i geri getir |
| POST | `/api/admin/inventory/{id}/transfer` | SY ekibi transferi |
| POST | `/api/admin/inventory/{id}/transfer-ug` | UG ekibi transferi |

### Admin — Eşikler & Uyarılar

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/admin/thresholds` | Uyarı eşikleri |
| POST | `/api/admin/thresholds` | Yeni eşik oluştur |
| PUT | `/api/admin/thresholds/{id}` | Eşik güncelle |
| GET | `/api/admin/alerts` | Uyarı olayları |
| POST | `/api/admin/alerts/{id}/acknowledge` | Uyarıyı onayla |
| POST | `/api/admin/alerts/{id}/resolve` | Uyarıyı çöz |
| POST | `/api/admin/alerts/{id}/re-notify` | Yeniden bildir |
| GET | `/api/admin/alerts/{id}/notifications` | Bildirim geçmişi |

### Admin — Kullanıcılar & Takımlar

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/admin/users` | Kullanıcı listesi |
| POST | `/api/admin/users` | Kullanıcı ekle |
| PUT | `/api/admin/users/{id}` | Kullanıcı güncelle |
| POST | `/api/admin/users/{id}/reset-password` | Parola sıfırla |
| POST | `/api/admin/users/{id}/unlock` | Hesap kilidini aç |
| DELETE | `/api/admin/users/{id}` | Kullanıcı sil |
| GET | `/api/admin/teams` | Takım listesi |
| POST | `/api/admin/teams` | Takım ekle |
| PUT | `/api/admin/teams/{id}` | Takım güncelle |
| DELETE | `/api/admin/teams/{id}` | Takım sil |
| GET | `/api/admin/teams/{id}/users` | Takım üyeleri |

### Admin — Kişiler & Notlar

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/admin/contacts` | Aktif eskalasyon kişileri |
| GET | `/api/admin/contacts/all` | Tüm eskalasyon kişileri |
| POST | `/api/admin/contacts` | Kişi ekle |
| PUT | `/api/admin/contacts/{id}` | Kişi güncelle |
| DELETE | `/api/admin/contacts/{id}` | Kişi sil |
| GET | `/api/admin/notes/{domain}` | Domain notları |
| POST | `/api/admin/notes/{domain}` | Not ekle |
| DELETE | `/api/admin/notes/{domain}/{noteId}` | Not sil |

### İzleme (Monitoring)

Dört serbest izleme tipi (`port`, `ping`, `keyword`, `dns`) ortak bir REST desenini paylaşır — aşağıda `{type}` ∈ `port | ping | keyword | dns`:

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/monitoring/{type}` | Monitör listesi (grup & takım alanlarıyla) |
| POST | `/api/monitoring/{type}` | Monitör ekle |
| PUT | `/api/monitoring/{type}/{id}` | Monitör güncelle (monitör-başına alarm hassasiyeti dahil) |
| DELETE | `/api/monitoring/{type}/{id}` | Monitör sil |
| POST | `/api/monitoring/{type}/{id}/check` | Hemen kontrol et |
| GET | `/api/monitoring/{type}/{id}/history` | Kontrol geçmişi |
| GET | `/api/monitoring/{type}/{id}/response-series` | Süre grafiği serisi (avg / p95 / min-max; ping'de paket kaybı) |
| POST | `/api/monitoring/{type}/test` | Kaydetmeden anlık test |
| GET | `/api/monitoring/dns/{id}/details` | DNS çözümleme detayı (resolver başına) |
| GET | `/api/monitoring/defaults` | Yeni monitör varsayılanları (kontrol aralığı, alarm eşikleri) |

**HTTP/S Uptime** (sertifika envanteri üzerinden):

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/monitoring/uptime/overview` | Tüm domain'lerin uptime özeti |
| GET | `/api/monitoring/uptime/{domain}/history` | Uptime geçmişi |
| GET | `/api/monitoring/uptime/{domain}/http-history` | HTTP kontrol geçmişi |
| GET | `/api/monitoring/uptime/{domain}/ssl-history` | SSL kontrol geçmişi |

**Domain (Alan Adı Süre Bitişi)** (`/api/monitoring/domain` — RDAP/WHOIS, latency yok → response-series yok):

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/monitoring/domain` | Domain monitör listesi (durum / kalan gün / registrar / EPP) |
| POST | `/api/monitoring/domain` | Domain monitör ekle (URL/subdomain → PSL ile registrable'a indirger) |
| PUT / DELETE | `/api/monitoring/domain/{id}` | Güncelle / sil |
| POST | `/api/monitoring/domain/{id}/check` | Hemen kontrol et (RDAP → WHOIS fallback) |
| GET | `/api/monitoring/domain/{id}/history` | Kontrol geçmişi (trend + değişiklik tespiti) |
| POST | `/api/monitoring/domain/test` | Kaydetmeden anlık sorgu |

**Rehber & Notlar** (`/api/monitoring/notes`):

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/monitoring/notes?type={type}&target={target}` | İzleme rehberi + not günlüğü |
| PUT | `/api/monitoring/notes/guide` | Rehber içeriğini kaydet |
| POST | `/api/monitoring/notes` | Not ekle |
| PUT | `/api/monitoring/notes/{id}` | Not güncelle |
| DELETE | `/api/monitoring/notes/{id}` | Not sil |

### Denetim & Sistem

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/audit` | Denetim günlüğü |
| GET | `/api/audit/stats` | Denetim istatistikleri |
| GET | `/api/audit/weak-algorithms` | Zayıf algoritma raporu |
| GET | `/api/admin/system` | Sistem sağlığı özeti |
| GET | `/api/admin/system/smtp-logs` | SMTP gönderim geçmişi |
| GET | `/api/admin/system/db-stats` | PostgreSQL tablo istatistikleri |
| GET | `/api/admin/system/metrics` | Prometheus metrikleri |
| GET | `/api/admin/system/http-metrics` | HTTP istek metrikleri (anlık) |
| GET | `/api/admin/system/http-metrics/endpoints` | Kalıcı seri için endpoint listesi |
| GET | `/api/admin/system/http-metrics/series` | Kalıcı per-endpoint istek/hata/gecikme (p95/p99) serisi |
| GET | `/api/admin/system/heartbeat-timeline` | Heartbeat zaman çizelgesi |
| POST | `/api/admin/system/heartbeat` | Heartbeat kaydı |
| DELETE | `/api/admin/system/scheduler-lock` | Zamanlayıcı kilidini serbest bırak |

### Diğer Modüller

| Taban Yol | Modül |
|-----------|-------|
| `/api/incidents` | Olay (incident) kaydı, geçmişi ve görselleri |
| `/api/weekly-reports` | Haftalık erişilebilirlik raporları & alıcıları |
| `/api/guide-links` | Kurumsal yardım / rehber bağlantıları |
| `/api/users` | Kullanıcı dizini (arama, self-servis) |
| `/api/admin/permissions` | İnce-taneli izin matrisi |
| `/api/admin/general` | Genel uygulama ayarları (base-URL vb.) |
| `/api/admin/smtp` | SMTP yapılandırması & test |
| `/api/admin/ldap` | LDAP dizin entegrasyonu |
| `/api/admin/sql` | Salt-okunur SQL playground |
| `/api/admin/database` | Veritabanı tablo bilgisi |
| `/api/admin/secret-tools` | Ağ tanılama araçları (curl / dig / traceroute / openssl) |

### Auth

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| POST | `/api/login` | Giriş yap |
| POST | `/api/logout` | Çıkış yap |
| GET | `/api/me` | Mevcut oturum bilgisi |

### Örnek API Yanıtı

```bash
curl -u user:password http://localhost:8080/api/certificates
```

```json
{
  "success": true,
  "data": [
    {
      "domain": "google.com",
      "subject": "google.com",
      "issuer_cn": "WR2",
      "not_before": "2026-03-17T08:22:36",
      "not_after": "2026-06-09T08:22:35",
      "days_remaining": 16,
      "alert_level": "high",
      "warning": true,
      "status": "warning",
      "checked_at": "2026-05-24T02:00:00"
    }
  ],
  "timestamp": "2026-05-24T10:00:00"
}
```

## Veritabanı

PostgreSQL kullanılır. Bağlantı `DB_URL` ortam değişkeniyle yapılandırılır (varsayılan: `jdbc:postgresql://localhost:5432/sitemonitor`).

**Tablolar:**

| Tablo | Açıklama |
|-------|----------|
| `certificate_checks` | Tüm kontrol geçmişi |
| `latest_checks` | Domain başına en son sonuç |
| `certificate_inventory` | Yönetilen domain envanteri (soft-delete destekli) |
| `alert_thresholds` | Uyarı eşik tanımları |
| `alert_events` | Uyarı olayları ve durumları |
| `escalation_contacts` | Bildirim alıcıları |
| `notification_logs` | E-posta / webhook gönderim geçmişi |
| `app_users` | Kullanıcı hesapları ve roller |
| `teams` | Takım tanımları |
| `certificate_notes` | Domain başına dahili notlar |
| `port_monitors` | İzlenen TCP port'ları |
| `port_checks` | Port kontrol geçmişi |
| `uptime_checks` | HTTP/S uptime kontrol geçmişi |
| `dns_monitors` | İzlenen DNS kayıtları |
| `dns_records` | DNS değişim geçmişi |
| `audit_log` | Yönetici işlem günlüğü |
| `system_heartbeat` | Sistem canlılık kayıtları |
| `remember_me_tokens` | "Beni hatırla" oturum token'ları |
| `password_history` | Parola geçmişi (yeniden kullanım engeli) |
| `permission_grants` | İnce-taneli izin atamaları |
| `ping_monitors` | İzlenen ICMP ping hedefleri |
| `ping_checks` | Ping kontrol geçmişi (RTT, paket kaybı) |
| `keyword_monitors` | İzlenen HTTP içerik / keyword kuralları |
| `keyword_results` | Keyword kontrol geçmişi |
| `http_monitors` | İzlenen HTTP/Website monitörleri |
| `http_checks` | HTTP uptime kontrol geçmişi |
| `domain_monitors` | İzlenen alan adları (registrable domain + eşikler) |
| `domain_checks` | Alan adı kontrol geçmişi (RDAP/WHOIS: expiry, registrar, EPP, NS) |
| `monitor_guide` | İzleme-başına rehber içeriği |
| `monitor_notes` | İzleme-başına not günlüğü |
| `guide_links` | Kurumsal yardım / rehber bağlantıları |
| `http_metric_minute` | Dakikalık kalıcı HTTP istek/gecikme metrikleri |
| `incident_images` | Olay (incident) görselleri |
| `weekly_reports` | Haftalık erişilebilirlik raporları |
| `weekly_report_mails` | Haftalık rapor e-posta alıcıları |
| `weekly_report_images` | Haftalık rapor görselleri |
| `weekly_availability_log` | Haftalık erişilebilirlik ham günlüğü |
| `diagnostic_runs` | Ağ tanılama çalıştırma geçmişi |
| `ldap_settings` | LDAP dizin yapılandırması |
| `smtp_settings` | SMTP yapılandırması |

## Kubernetes Dağıtımı

```bash
VERSION=$(cat VERSION)
helm upgrade --install site-monitor ./helm/site-monitor \
  -f helm/site-monitor/values.yaml \
  -f helm/site-monitor/environments/master.yaml \
  --set image.tag=${VERSION} \
  --set secret.adminPassword=$ADMIN_PASSWORD \
  --set secret.dbPassword=$DB_PASSWORD \
  -n site-monitor --create-namespace
```

Ortam bazlı değer dosyaları:

| Dosya | Ortam | Özellikler |
|-------|-------|-----------|
| `environments/master.yaml` | Üretim | 3 replica, HPA min:3/max:10, PDB |
| `environments/release.yaml` | Staging | 2 replica, HPA min:2/max:5 |
| `environments/develop.yaml` | Geliştirme | 1 replica, HPA kapalı, e-posta kapalı |

## Güvenlik

- Tüm kimlik bilgileri ortam değişkenlerinden okunur — kod içinde hardcoded değer yok
- HTTP güvenlik başlıkları: `X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`, `Referrer-Policy`, `Content-Security-Policy`, `Permissions-Policy`
- CORS yalnızca yapılandırılmış kaynaklara izin verir
- Brute-force koruma: ardışık başarısız girişlerde artan süre kilitleme
- GeoIP tabanlı giriş anomali tespiti (ofis saatleri dışı, coğrafi hız saldırısı)
- Konteyner root olmayan kullanıcı (UID 1000) ile çalışır
- Konteyner kök dosya sistemi salt okunur
- K8s pod güvenlik bağlamında tüm yetenekler düşürülür
- TLS Ingress üzerinden zorunlu (`ssl-redirect: true`)

## Sorun Giderme

### "Port 8080 zaten kullanımda"

`application.properties` içinde veya `SERVER_PORT` ortam değişkeniyle:

```properties
server.port=8081
```

### "Sertifika kontrol edilemiyor"

1. Ağ bağlantısını ve güvenlik duvarı kurallarını kontrol edin
2. `curl -v https://domain.com` ile doğrudan test edin
3. Backend günlüklerini inceleyin: `docker logs site-monitor`

### "Dashboard boş görünüyor"

1. Tarayıcı konsolunda hata olup olmadığını kontrol edin (F12)
2. `sertifikaListesi.txt` dosyasında veya envanterinde domain olduğunu doğrulayın
3. "Şimdi Kontrol Et" düğmesine basarak ilk kontrolü tetikleyin

### "E-posta gönderilmiyor"

1. `SITE_MONITOR_EMAIL_ENABLED=true` olduğunu doğrulayın
2. SMTP kimlik bilgilerini kontrol edin
3. Admin Paneli → Sistem Sağlığı → SMTP istatistiklerinde hata mesajını inceleyin

### "Zamanlayıcı çalışmıyor"

1. Admin Paneli → Sistem Sağlığı'ndan son tarama zamanını kontrol edin
2. Birden fazla pod varsa dağıtık kilit nedeniyle yalnızca bir pod çalıştırır (bu beklenen davranıştır)
3. Kilidi zorla serbest bırakmak için: `DELETE /api/admin/system/scheduler-lock`

## Performans

- Varsayılan olarak 20 paralel iş parçacığı ile kontrol yapılır (`PARALLEL_WORKERS`)
- Maksimum iş parçacığı havuzu: 50 (`EXECUTOR_MAX_SIZE`)
- Tipik süreler: 50 domain ≈ 5-10s, 200 domain ≈ 15-30s

## Lisans

Dahili kullanım için tasarlanmıştır.

---

**Sürüm:** 19.44.1 — Son Güncelleme: 8 Temmuz 2026

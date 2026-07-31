# Changelog

All notable changes to CertMonitor are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/).

---

## [Unreleased]

### Added
- **Senaryo İzleme (Scripted Check / k6) — 10. izleme türü.** Kullanıcı tanımlı k6 scriptleri periyodik olarak tek
  iterasyon çalıştırılır (`--vus 1 --iterations 1`) ve sonucuna göre sağlık kararı üretilir (PASS/FAIL/ERROR/TIMEOUT) —
  çok adımlı akışların (OIDC/Keycloak login, API zincirleri) uçtan uca izlenmesi. k6 GÖMÜLMEZ; imaja `K6_VERSION`
  build-arg'ıyla eklenen binary her kontrolde **kısa ömürlü, sandboxlu alt süreç** olarak koşar (`ProcessProbe` —
  SIGTERM→SIGKILL, çıktı-sınırlı; temp dosyalar her durumda silinir).
  - **Güvenlik:** `SsrfGuard.blacklistCidrs()` → k6 `--blacklist-ip` (iç ağ/loopback/link-local/cloud-metadata engeli,
    tek kaynak); env secret'ları `SecretCipher` ile şifreli saklanır (API/ekranda asla düz metin — yalnız `value_set`);
    çıktı `SecretMask.maskValues` ile maskelenir; script gövdesi sabit-kodlu-secret taraması (`hardcoded-secret-policy`).
  - **Yetki:** yeni `monitoring.scripted` izni (EDIT+EXECUTE) — varsayılan **ADMIN + TEAM_ADMIN (PO)**; USER/AUDIT'e açılmaz.
  - **Alarm:** tek tip `SCRIPTED_FAIL` (N ardışık başarısızlık → CRITICAL); mevcut confirmation/recovery + Storm +
    MaintenanceWindow + EscalationService/Email + ActivityLog zincirinden geçer (PAGE deseniyle aynı). E-postada senaryo
    adı + başarısız check listesi + maskeli çıktı kuyruğu. Micrometer: `scripted.k6.active`/`queued`.
  - **Frontend:** yeni "Senaryo İzleme" sekmesi; JS syntax-highlight editör (react-simple-code-editor + prismjs),
    env-secret (write-only) yönetimi, hazır şablonlar (OIDC/Keycloak, API zinciri, form-login), "Test Çalıştır", detay
    ekranında check-bazlı geçti/kaldı + maskeli stdout/stderr. Tüm metinler TR + EN.
  - **Config/infra:** `cert.monitor.scripted.*` (pool-size, timeout tavanları, output-tail, retention, k6-bin,
    hardcoded-secret-policy) + startup config-log; Dockerfile'a `COPY --from=grafana/k6`; helm/compose bellek limiti
    k6 süreç yüküne göre yükseltildi; `.env.example` + `CERT_MONITOR_SECRET_KEY`. Yeni tablolar `scripted_monitors` /
    `scripted_checks` (LATERAL latest, gün-bazlı retention + gece purge + günlük rollup).
- **Başlangıç "Etkin Konfigürasyon" logu (StartupLogger genişletme).** Uygulama açılışta çalıştığı TÜM etkin ayarları
  (varsayılan / `application.properties` / env-JVM override / DB `app_settings`·SMTP·LDAP birleşik NİHAİ değer) tek
  okunabilir `INFO` bloğu halinde loglar — kategorilere ayrılmış, her satırda kaynak etiketi (`[default]`/`[config]`/
  `[env]`/`[db]`/`[runtime]`). `ApplicationReadyEvent` + `@Order(HIGHEST_PRECEDENCE)` (zamanlanmış işlerden önce).
  - **Merkezî maskeleme** `SecretMask` (yeni, tek kara-liste; `AuditDiff` de buradan besleniyor): parola/secret/token/
    anahtar (`*****`), JDBC URL'e gömülü kimlik ayıklama, segment-farkında eşleşme (`keyword`/`keepalive` yanlış-pozitif
    değil). Şifreli secret'lar asla çözülmez — yalnız `password_set` / `secret-key configured` durumu.
  - **JSON modu** (`STARTUP_CONFIG_LOG_JSON=true`, varsayılan kapalı) log-toplama için; `STARTUP_CONFIG_LOG=false` ile
    kapatılabilir. DB erişilemez açılışta blok yine basılır (DB-bağımlı bölümler "okunamadı"). Gerçek uygulama sürümü
    kök `VERSION` dosyasından (`AppVersion`).
  - **Config:** `cert.monitor.version` (`APP_VERSION`), `cert.monitor.startup.config-log.enabled/json`.
- **Sayfa Bütünlüğü İzleme (Page Integrity Monitor) — 9. izleme türü.** Bir web sayfasının KOD SEVİYESİNDE
  sağlıklı yüklendiğini doğrular: jsoup ile HTML kaynak envanteri (img/CSS/JS/link/iframe/font/favicon) çıkarılır,
  her kaynak sınırlı eşzamanlılıkla doğrulanır (önce HEAD, desteklenmiyorsa GET) ve kırık kaynak / mixed content /
  yavaş kaynak tespit edilir. İki mod: **SINGLE_PAGE** (sık, ana sayfa) ve **SITE_CRAWL** (günlük, same-origin
  derinlik taraması; robots.txt + sitemap.xml uyumlu, tek site anda).
  - **Durum makinesi:** OK → DEGRADED (sayfa döndü ama sorunlu kaynak var) → DOWN (ana sayfa alınamadı). İki ayrı
    alarm tipi (`PAGE_DOWN` CRITICAL, `PAGE_INTEGRITY` HIGH) mevcut confirmation/recovery + Storm + MaintenanceWindow
    + EscalationService/Email zincirinden geçer (KEYWORD'ün çok-alarm-tipli deseniyle aynı).
  - **DEGRADED alarm politikası:** monitör başına `alertThirdParty` (varsayılan **kapalı**) — yalnız birinci-taraf
    (same-origin) kırıklar e-posta üretir; üçüncü-taraf kırıklar yalnız UI'da görünür. Mixed content her zaman alarm.
  - **SSRF:** ana sayfa + parse'tan çıkan HER kaynak + HER redirect adımı `SsrfGuard`'tan geçer (redirect'ler manuel
    izlenir → DNS-rebind/redirect-SSRF kapalı).
  - **Yeni tablolar** `page_monitors` / `page_checks` / `page_resource_issues` (yalnız sorunlu kaynaklar saklanır) —
    owner+timestamp index'leri, gün-bazlı yapılandırılabilir retention + gece batch-purge + günlük rollup baştan dahil.
  - **Frontend:** yeni "Sayfa Bütünlüğü" sekmesi (liste/form/detay); detayda durum kartları, kırık-kaynak zaman
    grafiği, filtrelenebilir sorun tablosu (tür/kaynak/bulunduğu sayfa/sorun/HTTP/süre) + CSV dışa aktarma. Aktivite
    Logu ve haftalık rapor ekosistemine otomatik dahil. Tüm metinler TR + EN.
  - **Config:** `cert.monitor.page.*` (alert-enabled, interval/crawl-interval, resource-concurrency, retention,
    user-agent, per-tip form varsayılanları) — admin UI'dan canlı. `org.jsoup:jsoup` bağımlılığı eklendi.

### Changed
- **Sayfa Bütünlüğü alarm maili yeniden düzenlendi.** "Sorunlu Kaynaklar" artık hizalı, ≤10 satır, karışmayan
  URL'ler içeren Outlook-güvenli tablo (tür etiketi + kısaltılmış URL + HTTP, `… ve N kaynak daha` özeti). Maile
  **Mod** (Tek Sayfa/Site Tarama), **Alarm Kapsamı** (üçüncü-taraf/mixed/zaman-aşımı politikası) ve **Doğrulama**
  (N ardışık kontrolde üretildi) satırları eklendi. "HATA=null" gösterimi düzeltildi (`strCtx` literal "null"/"undefined"
  değerlerini yok sayar).
- **Alarm/recovery e-postaları — StatusCake esintili iyileştirmeler.** Alarm mailine **"Neden bu e-postayı aldınız?"**
  alıcı-şeffaflık bloğu (bildirimin hangi takıma tanımlı olduğu; anti-phishing/güven). Recovery maillerinde kesinti
  süresi StatusCake tarzı kompakt saatle (`HHH:MM:SS`, ör. `000:05:25`) Türkçe metnin yanında; tutarlı **"Toplam
  Kesinti Süresi"** etiketi; net **Kesinti Başlangıcı → Yeniden Ulaşılabilir** aralığı.
- **Recovery e-postaları — şeffaflık bloğu + erişilebilirlik özeti.** "Neden bu e-postayı aldınız?" bloğu artık
  RECOVERY (çözüldü) maillerine de eklendi (`teamNames` tüm çözüm-builder'larına geçirildi). ACCESSIBILITY
  recovery'sinde **son 24s/7g uptime% + kesinti sayısı** özet kartı (`WeeklyAvailabilityReportService.availabilityLastHours`
  yeniden kullanımı; döngüsel bağımlılık olmadan EscalationService katmanında). Uptime verisi olmayan tiplerde kart atlanır.

---

## [12.1.0] — 2026-05-24

### Added
- **Dinamik Alert Seviyeleri (Backend)** — `alert_level` alanı `CertificateDto`'ya eklendi; `critical` / `high` / `warning` / `valid` / `expired` / `error` değerleri `AlertThreshold` tablosundaki eşiklere göre dinamik hesaplanıyor (sabit kodlu 7/15 gün yerine)
- **`high` Alert Seviyesi (Backend)** — İstatistik API'si artık `critical_count`, `high_count` ve `expiring_in_7_days` döndürüyor; `/api/certificates` filtreleme `high` ve `expiring7` değerlerini destekliyor
- **Modal İkon Badge'leri** — Add User (mavi, `UserPlus`/`UserCog`), Add Team (yeşil, `UsersRound`/`PenLine`), Port Monitor Edit (turuncu, `Plug`) modallerine renkli ikon badge'leri eklendi
- **AlertHistory Domain Filtresi** — `AlertHistory` bileşeni opsiyonel `domain` prop alıyor; `CertificateModal`'ın Alerts sekmesinde domain'e özel geçmiş gösteriliyor
- **Dashboard Sertifika Modalı Yeniden Tasarımı** — Koyu navy gradient başlık, durum pill'i, `Globe`/`X` ikonları, sekmeli layout (Detaylar · Uyarılar · Notlar), modal içi scroll

### Changed
- **Dashboard İstatistik Kartları** — Sıra `valid ��� warning → high → critical` olarak düzenlendi; ikonlar alarm seviyesini görsel olarak ifade edecek şekilde güncellendi (`TriangleAlert` → warning, `OctagonAlert` → high, `Siren` → critical)
- **Port Monitor Edit Modal** — `upt-modal` stili yerine uygulama genelindeki `modal-overlay` / `modal-box` / `modal-icon-hdr` standart yapısı kullanılıyor
- **Add User / Add Team Form Hizalaması** — Zorunlu alan `*` işareti ayrı flex item oluşturduğundan label metniyle hizalanamıyordu; `<span>` wrapper ile düzeltildi (6 alan)
- **Nav Menü Grupları** — Logout yapılırken `nav-groups-open` localStorage anahtarı temizleniyor; sonraki login'de MONITORING / LOGS / MANAGEMENT grupları kapalı başlıyor

### Fixed
- **`expiring7` Filtresi** — `STAT_FILTER_FN`'de `expiring7` anahtarı eksikti; tıklandığında filtre uygulanmayıp tüm sertifikalar listeleniyordu; doğru `days_remaining` koşulu eklendi
- **Modal Durum Pill Seviyesi** — Dashboard kartında "High" gösteren sertifika modal'da "Warning" gösteriyordu; `alert_level` alanı `CertificateDto`'dan modal'a prop olarak iletildi
- **Modal Scroll** — Büyük sertifika detay modal'ında scroll sayfa yerine modal içinde çalışıyor

---

## [11.0.0] — 2026-05-22

### Added
- **Soft Delete** — Domain Inventory artık fiziksel silme yapmıyor; `deletedAt` timestamp alanı ile soft delete uygulanıyor
- **Silindi Rozeti** — Soft-delete edilen satırlar tabloda soluk görünüm + "Silindi" rozeti ile gösteriliyor
- **Silinenleri Göster Filtresi** — Admin kullanıcılar için "Silinenleri Göster" toggle'ı ile sadece silinmiş domainler listeleniyor
- **Domain Geri Getirme** — Silinen bir domain "Geri Getir" butonuyla yeniden aktif hale getirilebiliyor (admin only)
- **UG Ekibi Transferi** — `POST /api/admin/inventory/{id}/transfer-ug` endpoint'i ile `ugTeamId` ayrı güncelleniyor
- **SY Ekibi Transferi** — Mevcut transfer endpoint'i `DOMAIN_TRANSFER_SY` audit log kaydı ile güçlendirildi
- **Audit Log** — `DOMAIN_SOFT_DELETE`, `DOMAIN_RESTORE`, `DOMAIN_TRANSFER_SY`, `DOMAIN_TRANSFER_UG` event'leri eklendi
- **Test coverage** — AdminControllerTest soft-delete + listInventory güncellendi; UserServiceTest yeni guard method'u; client.test.js showDeleted param testi

### Changed
- `GET /api/admin/inventory` — `showDeleted` query param eklendi (varsayılan `false`); admin silinenleri, non-admin sadece aktif kayıtları görür
- `DELETE /api/admin/inventory/{id}` — Hard delete → soft delete; `latestCheck` geçmişi korunuyor
- `InventoryManager.jsx` — Transfer butonları "SY Ekibi" ve "UG Ekibi" olarak ikiye ayrıldı; modal `transferType` ile yönetiliyor
- `UserService.deleteTeam` — `existsByTeamIdAndActiveTrueAndDeletedAtIsNull` ile soft-delete edilen kayıtlar team silme guard'ından hariç tutuluyor

---

## [10.5.0] — 2026-05-21

### Added
- **Org Role** — `AppUser` carries an organizational role (`PO`, `TECH`, `MANAGER`, `CLEVEL`); colour-coded badge in Users table and Team member chips
- **User-linked Escalation Contacts** — EscalationContact gains `user_id` FK; Add/Edit Contact form replaces manual name/email entry with a user picker; backend auto-populates name/email from linked user
- **Team Member List** — TeamManager rows expandable (▶/▼ toggle); shows all team members with org role badges
- **SearchableSelect label fix** — trigger button uses `onMouseDown`; fixes dropdown reopen bug when wrapped inside HTML `<label>`
- **AdminPanel default tab** — non-admin users land on Escalation Contacts tab by default
- **Test coverage** — `updateUser` (6 scenarios), `orgRole` edge cases, User CRUD endpoint tests, Contact+userId endpoint tests

### Changed
- `EscalationContacts.jsx` — form uses user dropdown instead of standalone name/email fields
- `AdminController.addContact/updateContact` — `applyContactFields` resolves `userId` → name/email via `AppUserRepository`
- `api.admin.acknowledgeAlert` — accepts `(id, acknowledgedBy)` and sends `{ acknowledged_by }` in POST body

---

## [6.8.0] — 2026-05-19

### Added
- **System Health — Heartbeat** — backend records a DB heartbeat every 10 minutes; alarm triggers if signal is missing for >15 minutes
- **System Health — SMTP Statistics** — 30-day delivery rate (sent/attempted); alarm below 99%; separate `countAttemptedSince` query excludes intentional SKIPPED_DISABLED entries from the denominator
- **System Health — SMTP Failure Modal** — clicking the SMTP card opens a modal listing all non-SENT notifications (FAILED + SKIPPED) with kind badge, error detail, recipient, and trigger
- **System Health — Database Stats** — DB response time (SELECT 1 latency in ms); sortable PostgreSQL table statistics (rows, table size, total size) with client-side column sort
- **System Health — DB Refresh Button** — dedicated refresh button reloads DB stats independently without reloading the full page
- **System Health — Certificate Scan Statistics** — last scan time, duration, total/warning/error counts tracked via atomic fields in SchedulerService
- **System Health — Scan Staleness Alarm** — red alarm banner when no scan has run for ≥2 hours
- **System Health — Alarm Banner** — consolidated red banner at page top when any alarm is active (scan, SMTP, heartbeat)
- **Audit Log — DOMAIN_EDIT event** — every domain edit records a structured JSON diff (`{"field":{"from":old,"to":new}}`) in the `detail` column; covers all 24+ CertificateInventory fields
- **Audit Log — Diff Viewer** — expandable row in Audit Log table shows field-level diff (old value in red, new value in green) for DOMAIN_EDIT entries
- **Audit Log — DOMAIN_EDIT badge** — amber/orange event badge distinct from create (blue) and delete (red)
- **`SystemHeartbeat` entity & repository** — new `system_heartbeat` table, auto-created by Hibernate `ddl-auto=update`
- **`ExtendedHealthService`** — new service encapsulating heartbeat recording, SMTP stats, DB latency, and table stats
- **`/api/admin/system/db-stats` endpoint** — returns PostgreSQL table statistics (row counts + sizes)
- **`/api/admin/system/smtp-logs` endpoint** — returns non-SENT notification records for the last 30 days

### Changed
- **Smart scan polling** — Check Now button polls every 2 s and detects completion via `running: true→false` transition OR `last_run` timestamp change (fixes race condition for fast scans)
- **DB table layout** — switched to `table-layout: fixed` with `<colgroup>` for stable column widths; added raw byte columns (`table_size_bytes`, `total_size_bytes`) for correct numeric sort
- **SMTP rate calculation** — rate now computed as `sent / (sent + failed)`, intentional SKIPPED_DISABLED records excluded from denominator
- **Audit Log table** — expanded from 9 to 10 columns; 10th column shows diff toggle button for DOMAIN_EDIT entries

### Fixed
- SMTP success rate showing 66% when SKIPPED_DISABLED notifications were incorrectly counted as failures
- Certificate Scan card showing "Not yet run" after Check Now due to polling race condition
- DB table column misalignment ("kaymış") resolved with fixed-layout table

---

## [1.0.0] — 2026-05-16

### Added
- **SSL/TLS Certificate Monitoring** — automated checking of certificates from an inventory file and a managed domain list
- **Certificate inventory management** — add/edit/delete monitored domains via admin UI
- **Multi-level alerting** — WARNING / HIGH / CRITICAL thresholds with configurable day counts
- **Escalation contacts** — per-role contacts (PO, TECH, MANAGER, CLEVEL) with email and Teams/Slack webhook delivery
- **Alert lifecycle** — acknowledge, re-notify, and resolve alerts with full audit trail
- **OCSP/CRL revocation checking** via BouncyCastle
- **Chain validation** — detects incomplete or broken certificate chains
- **Fingerprint & Subject pinning** — detects certificate replacements
- **Activity log** — per-run timeline with per-certificate status
- **Notification history** — full log of every email/webhook delivery attempt
- **Dashboard** with clickable stat cards and filter support
- **Dark / Light mode** with system-preference detection and localStorage persistence
- **Internationalisation** — Turkish (default) and English UI, switchable at runtime
- **Lucide React icons** replacing all emoji throughout the UI
- **Kubernetes** deployment manifests (namespace, deployment, service, configmap, secret, ingress, HPA, PDB, ServiceAccount)
- **Helm chart** `helm/cert-monitor` v0.1.0 — fully parameterised via `values.yaml`
- **Multi-stage Docker build** with separate frontend and backend stages
- **Prometheus metrics** endpoint at `/metrics`
- **Remember-me** cookie authentication (7-day token, HttpOnly, SameSite=Strict in prod)
- **PostgreSQL** as the sole database backend

### Security
- All credentials sourced from environment variables — no hardcoded secrets in code
- HTTP security headers: `X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`, `Referrer-Policy`, `Content-Security-Policy`, `Permissions-Policy`
- CORS restricted to explicitly configured allowed origins
- Container runs as non-root user (UID 1000)
- Read-only root filesystem in container
- All capabilities dropped in K8s pod security context
- TLS enforced via Ingress (`ssl-redirect: true`)
- `secret.yaml` excluded from version control (`.gitignore`); `secret.example.yaml` provided as template
- Docker Compose uses `.env` file — `.env.example` provided as template

---

[1.0.0]: https://github.com/your-org/cert-monitor/releases/tag/v1.0.0

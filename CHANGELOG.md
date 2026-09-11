# Changelog

All notable changes to Site Monitor (formerly CertMonitor) are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/).

---

## [Unreleased]

## [20.54.3] — 2026-09-11

### Changed
- **Yerel geliştirme oturumları kalıcı.** `start-local.ps1` artık `SPRING_SESSION_STORE_TYPE`'ı geçirir; `.env.example` `jdbc` önerir — jar yeniden başlatmalarında oturum düşmez (prod ile aynı depo).
- **Heartbeat zaman çizelgesi son kovayı düşürmüyor.** Kova sayısı yukarı yuvarlanır; son (kısmi) kovanın beklenen değeri kalan dakika kadardır — en yeni heartbeat'ler artık çizelgede görünür.
- **Kimlik sızıntısı kapısı `.gstack/` altını taramaz** (gitignore'lu QA raporları yerel `verify`'ı düşürmesin).

## [20.54.2] — 2026-09-11

### Fixed
- **İzleme modallarında React uyarısı.** Dokuz izleme sayfasında "Devamı için kaydırın" ipucuna hook nesnesi yayılıyor, `ref` fonksiyon bileşenine sızıyordu (`Function components cannot be given refs`); açık prop geçişine çevrildi, kaynak kapısı yayılımı reddeder.
- **Sertifika Sağlığı cipher çipi.** Kopyalama düğmesi satır başlığı `<button>`'unun içinde ikinci bir `<button>` idi (geçersiz DOM); `CopyButton as="span"` (klavye + stopPropagation).

## [20.54.1] — 2026-09-11

### Added
- **"Planlı yenilemeydi" onayı.** Sertifika Sağlığı'nda "sertifika değişti" uyarısının altında onay düğmesi: onay (kim / ne zaman / hangi parmak izi) kaydedilir, satır yeşile döner; parmak izi yeniden değişirse uyarı tekrar çıkar. Sunulan ≠ sabitlenen (araya girme imzası) onaylanamaz (409). Denetim olayı `CERT_RENEWAL_CONFIRMED`.

### Fixed
- **Yayın dizini `--check` sahte "bayat".** `--append` BUILD_TIME yazıyor, `--full` etiket tarihini okuyordu; saniyelik fark bir sonraki sürümü düşürüyordu. Toleranslı karşılaştırma + etiket tarihi BUILD_TIME'a sabitlendi.

## [20.54.0] — 2026-09-11

### Added
- **Sürüm & Dağıtım Geçmişi.** Nav sürüm çipi popover'ı (yayın / devreye alma / gecikme / commit / uptime / helm rev), Yardım → Yenilikler (yayın dizini, "son ziyaretinizden beri"), Sistem Sağlığı → Sürüm & Dağıtım (KPI, zaman çizelgesi, tablo, CSV, denetimden geri doldurma, elle kayıt; `release_history.read/edit`). Uygulama açılış/kapanışını `deployment_history`'ye kendisi yazar; build meta imajdan (`APP_GIT_COMMIT`, `APP_BUILD_TIME`), Helm meta Downward API'den. Metrikler `sitemonitor_build_info`, `sitemonitor_deployment_started_seconds`. Haftalık rapora dağıtım bandı; isteğe bağlı dağıtım e-postası (`site.monitor.deploy.notify.enabled`).
- **Kişi webhook "Kim alır?" paneli.** Takım + seviye seçince her üye için push kararı ve gerekçesi (grup eşleşmedi / seviye altı / opt-out / pasif / üyelik kaydı yok). Teslimat günlüğü test gönderiminden sonra kendini tazeler ve takım rozeti gösterir; 24 s / 7 g KPI kartları günlüğü süzer.
- **Takım Müdürü tek kişi** + Edit Team'de elle müdür; LDAP özyinelemeli müdür kaydı sicil alır.
- **Tanılama yetkisi** TEAM_ADMIN ve USER'a açıldı — yalnız kendi takımının envanter kayıtları için.
- **SQL Playground tablo zamanları:** oluşturma ≈ ilk görülme / son veri değişimi + detayda Zaman & Aktivite.
- **Dokuz izleme modalı:** sabit başlık + kaydırılan gövde + sabit alt bar + "Devamı için kaydırın".

### Fixed
- **Page Integrity:** pod'un SsrfGuard'ının reddettiği / kurumsal DNS'in çözemediği üçüncü-taraf link "Broken" değil "Belirsiz"; zaman aşımında retry yok (kaynak başına 16 s → 4 s).
- **CSV formül enjeksiyonu (CWE-1236):** üç dışa aktarım ortak `Csv` kuralına bağlandı; kaynak-tarayan kapı.
- My Activity "Webhook push istemiyorum" 404; kullanıcı yönetimi tablosu 11 → 7 sütun; üye kartından açılan düzenleme modalı üstte.

## [12.1.0 → 20.53.2] — 2026-05-24 … 2026-09-10 (toplu)

> Bu aralıktaki ~480 sürüm CHANGELOG'a sürüm sürüm işlenmedi; aşağıdaki maddeler o dönemde
> yayınlanan değişikliklerin kürasyonlu özetidir. Sürüm bazında tarih, commit listesi ve bump
> türü için **Yardım → Yenilikler** ekranına ya da `docs/releases/index.json`'a bakın
> (`scripts/gen-release-index.mjs`). 2026-09-10'dan itibaren `[Unreleased]` doluysa her
> release CI tarafından tarihlenir (`scripts/promote-changelog.mjs`).

### Added
- **Sayfa düzeyi "Şimdi Kontrol Et" — dokuz izleme sayfasının hepsinde.** Panodaki toplu
  kontrolün karşılığı: araç çubuğundaki düğme önce takım seçtirir, sonra seçilen izlemeleri
  sınırlı eşzamanlılıkla koşturur ve sonuçları panoyla AYNI akan tabloda gösterir (ilerleme
  çubuğu, başarılı/hatalı sayacı, duvar saati, "Durdur"). Kolonlar türe uyarlanır: HTTP durum
  kodu, yanıt süresi, DNS değeri, RTT/kayıp, kalan gün, kırık kaynak, TTFB/boyut, k6 kontrol
  sayıları. Yeni bir sunucu ucu YOK — koşum, var olan tekil tetikleme uçları üzerinden istemci
  tarafında dağıtılır; kartların kendi "kontrol ediliyor" göstergeleri de yanar. Eşzamanlılık
  tavanı tür başına: sentetikte 2 (k6 süreç havuzuyla aynı), sayfa bütünlüğü/hızında 3, diğerlerinde 6.
  Aday kümesi kullanıcının tek tek çalıştırabildiği satırlarla sınırlı; DNS'te ucın yönetici
  şartı nedeniyle düğme yalnız yöneticiye çizilir. Koşum sırasında sayfanın 60 sn'lik otomatik
  tazelemesi durur (liste altından kaymasın), biter bitmez bir kez tazelenir.
- **Marka logosu (mor turp) uygulama genelinde.** Navbar, sekme ikonu (favicon), login, yardım,
  yükleme ekranı, PDF dışa aktarımları ve dokümanlar — uygulama içinde logo daima nötr yeşildir
  (marka durumla renk değiştirmez; filo sağlığı rozet/sayaçlarda). Alarm e-postaları severity'ye
  uygun (amber/kırmızı yaprak), çözülme e-postaları "yeşile döndü" logosuyla gelir (CID inline).
  Beyaz-etiket `logo-data` override'ı varsayılanın önüne geçebilir. Kurallar: `branding/BRAND.md`.

### Changed
- **Marka yazımı: "Site Monitör" → "SiteMonitor".** Ürün adı tüm görünen yüzeylerde (login, navbar,
  sekme başlığı, e-posta konu/gövdeleri, yardım/whitepaper) tek biçim "SiteMonitor" olarak birleştirildi.
  Beyaz-etiket `app-name` override mekanizması değişmedi.
- **Ürün adı: CertMonitor → Site Monitör.** Uygulama genelinde görünen marka (giriş ekranı wordmark'ı, sekme
  başlığı, e-posta konu/altbilgileri, haftalık raporlar, webhook kartları, yardım/whitepaper dokümanları ve
  açılış konfigürasyon banner'ı) **Site Monitör** (EN: *Site Monitor*) oldu. Alan dili değişmedi: sertifika
  domain terimleri (`CertificateInventory`, `/api/certificates`, DB şeması) ve API yolları aynen korunuyor.
  `CertMonitorLogo` bileşeni `SiteMonitorLogo` olarak yeniden adlandırıldı; whitepaper PDF'leri bir sonraki
  whitepaper güncellemesinde yeni adla yeniden üretilecek. Teknik kimliklerin (paket adı, property önekleri,
  imaj/chart adları) geçişi ayrı adımlarda sürüyor.

### Added
- **Paylaşılabilir URL / derin bağlantı.** İzleme sayfalarında ekranda görünen durumun tamamı artık adres
  çubuğunda yaşıyor: takım/grup filtresi, arama metni, stat kartı filtresi, sıralama, sayfa/sayfa boyutu ve
  açık detay modalı (`/?tab=keyword&group=X&q=example&page=2&monitor=42`). URL'i kopyalayıp paylaşan herkes
  birebir aynı görünümü açar; varsayılan değerler param üretmez (temiz URL), yazım debounce'lu ve tarayıcı
  geçmişini şişirmez. Her izleme sayfasına ve detay modalına tek tıkla **"Bağlantıyı Kopyala"** butonu
  eklendi (pano + bildirim). Kapsam: 8 izleme türü + Uptime + Dashboard sertifika kartları + Olay Geçmişi +
  Alarm Geçmişi + Denetim Kaydı + Envanter. Sekme değişince önceki sayfanın paramları otomatik temizlenir;
  e-postalardaki eski `?monitor=` linkleri çalışmaya devam eder (artık modal açıkken URL'de kalıcı).
- **Tüm liste görünümlerinde standart sayfalama.** 8 izleme türü sayfası (HTTP/Ping/Port/DNS/Alan Adı/Kelime/Sayfa
  Bütünlüğü/Sentetik), Uptime, Dashboard sertifika kartları, Envanter ve Bakım Pencereleri artık sayfalanıyor —
  hiçbir görünüm 200'den fazla kaydı aynı anda render etmiyor. Tek ortak bileşen (`PaginationBar` + `usePagination`):
  sayfa boyutları **25/50/100/200** (varsayılan 50, görünüm bazında kalıcı), «İlk/Önceki/numaralı/Sonraki/Son»
  gezinme, "Sayfa X/Y · A–B / N kayıt" bilgisi, 10+ sayfada "Sayfaya git" girişi. Modal içi kontrol geçmişleri
  compact varyantla sayfalı (Sayfa/Alan Adı/Sentetik'e yeni eklendi); sunucu-taraflı listeler (Tüm Sertifikalar,
  Alarm Geçmişi, Olay Geçmişi, Denetim Kaydı, İstatistik tablosu, Kendi Kayıtlarım, Giriş Sorunları) aynı bileşene
  taşındı ve Denetim Kaydı'nda sayfa boyutu artık seçilebilir. Filtre/arama değişiminde sayfa 1'e döner; veri
  küçülünce sayfa otomatik düzeltilir; 60 sn'lik oto-yenileme sayfa konumunu bozmaz; `?monitor=` derin bağlantıları
  hedef kayıt hangi sayfada olursa olsun çalışır.
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

[Unreleased]: https://github.com/inanmise/site-monitor/compare/v20.54.3...HEAD
[20.54.3]: https://github.com/inanmise/site-monitor/releases/tag/v20.54.3
[20.54.2]: https://github.com/inanmise/site-monitor/releases/tag/v20.54.2
[20.54.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.54.1
[20.54.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.54.0
[12.1.0]: https://github.com/inanmise/site-monitor/releases/tag/v12.1.0
[11.0.0]: https://github.com/inanmise/site-monitor/releases/tag/v11.0.0
[10.5.0]: https://github.com/inanmise/site-monitor/releases/tag/v10.5.0
[6.8.0]: https://github.com/inanmise/site-monitor/releases/tag/v6.8.0
[1.0.0]: https://github.com/inanmise/site-monitor/releases/tag/v1.0.0

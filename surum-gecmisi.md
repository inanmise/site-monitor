---
description: SiteMonitor "Sürüm & Dağıtım Geçmişi" — hangi sürüm ne zaman çıktı (release zaman çizelgesi, içerik, commit), hangi ortamda ne zaman DEVREYE alındı (dağıtım kaydı, pod başına, geri alma/atlanan sürüm görünür), şu an koşan sürümün devreye alınma anı bir tıkla. Build meta (commit/build zamanı/helm revizyonu) imajdan uygulamaya taşınır, açılışta kalıcı dağıtım kaydı yazılır, CI release indeksi üretir, CHANGELOG tarihlenir, 628 tag'lik geçmiş dürüstçe geri doldurulur. K1–K11 sorulmadan uygulanmaz.
argument-hint: [tasarim|build|backend|frontend|retro|hizli] (opsiyonel — boş bırakılırsa TAM kapsam koşulur)
---

# /surum-gecmisi — Sürüm ve Dağıtım Geçmişi

Görevin: SiteMonitor'ün sürüm hikâyesini iki ayrı ama birbirine bağlı zaman çizelgesi olarak
uçtan uca görünür kılmak:

1. **Yayın (release) çizelgesi — "hangi sürüm ne zaman çıktı, ne içeriyordu":** git tag'i /
   GitHub Release anı, bump türü (major/minor/patch), commit özeti, imaj etiketi, kürasyonlu
   CHANGELOG notu (varsa).
2. **Dağıtım (deployment) çizelgesi — "hangi ortamda ne zaman DEVREYE alındı":** pod başına
   başlangıç/bitiş, sürüm, commit, helm revizyonu, imaj referansı, düğüm; sürüm GEÇİŞİ (önceki
   sürümden farklı ilk açılış) ile sıradan pod restart'ı ayrı gösterilir; geri alma (semver
   gerileme) ve "yayınlandı ama hiç devreye alınmadı" sürümler işaretlenir.

Kullanıcının bir cümlelik ihtiyacı: **"En son geçerli sürüm hangisi ve ne zaman devreye alındı?"**
— bu soru Nav'daki sürüm çipine tıklayınca TEK popover'da cevaplanır; ayrıntı Sistem Sağlığı'nda.

**DÜRÜSTLÜK İLKESİ (pazarlık dışı):** bugün DB'de hiçbir yerde "şu sürüm şu tarihte açıldı" kaydı
yok. Geçmişe dönük dağıtım verisi ancak (a) audit'teki başlangıç izlerinden (sürümsüz) ve (b) elle
girilen kayıtlardan kurulabilir. Tahminî eşleştirme yapılırsa BAYRAKLI gösterilir; "kesin" gibi
sunulmaz. Yayın çizelgesi ise git tag'lerinden EKSİKSİZ kurulabilir (628 tag).

Yüzeysel iş KABUL EDİLMEZ: her faz kanıtla raporlanır; ürün kararları (K1–K11) tahmin edilmez,
seçenek + öneriyle kullanıcıya sorulur.

Kapsam argümanı: `$ARGUMENTS`
- Boş → tüm fazlar (0–7). `tasarim` → Faz 0 (karar dosyası `docs/SURUM_GECMISI_KARARLAR.md`, kod
  yok). `build` → 0,1. `backend` → 0,1,2,3,6,7. `frontend` → 0,4,6,7 (API hazır varsayılır;
  değilse raporla). `retro` → 0,5,7. `hizli` → 0–4 (testler asgari, retro yok).

## Değişmez kurallar (her fazda geçerli)

1. **Sürüm sahibi CI'dır:** `VERSION` ve `helm/site-monitor/Chart.yaml` elle düzenlenmez
   (`release.yml` her `main` push'unda bump'lar — CLAUDE.md "Things that bite"). Bu geliştirme
   sürüm ÜRETİMİNİ değiştirmez; yalnız sürüm META'sını taşır, kaydeder ve gösterir. K7 (major
   politikası) hariç — o da yalnız GELECEK numaralandırmayı etkiler, geçmiş tag'ler yeniden
   yazılmaz.
2. **Çalışma anında dış ağa çıkılmaz:** uygulama GitHub API / ghcr'ye ASLA istek atmaz (kurum
   ağında egress yok). Yayın verisi build zamanında imaja gömülür, dağıtım verisi uygulamanın
   kendi açılışında yazılır.
3. **`/api/**` yanıtları `no-store`** (NetScaler eski sürüm numarasını dakikalarca önbellekledi —
   CLAUDE.md 2026-09-10). Yeni uçlar bu kuralı bozmaz; `WebConfigTest.publicEndpoints_areNoStore`
   yeşil kalır.
4. **Şema kuralı:** yeni tablo/kolon `ddl-auto=update` + `applySchemaPatches()` idempotent
   `patch()`; türetilmiş `deleteBy…`/`@Modifying` repo metodları `@Transactional` + `int`
   (`RepositoryWriteTransactionGuardTest`). Zaman damgaları UTC; ekranda Europe/Istanbul
   (`formatDateSec`), aritmetikte `setUTC*`.
5. **Sırlara dokunulmaz:** dağıtım kaydına ortam değişkeni DÖKÜMÜ yazılmaz — yalnız sürüm/commit/
   build zamanı/imaj ref/helm rev/env adı/düğüm/pod/instance. `SecretMask` zaten var; yeni bir
   sızıntı yüzeyi açılmaz. Commit SHA anonim yüzeye (`/info`, `/api/branding`) K9 kararı olmadan
   çıkmaz.
6. **Bağımlılık eklenmez:** semver ayrıştırma için `VersionLabels.SEMVER` deseni yeniden kullanılır
   (yeni kütüphane yok); CI tarafında git-cliff/release-please gibi araçlar ancak K6 onayıyla ve
   yalnız workflow içinde.
7. **i18n:** tüm anahtarlar TR+EN aynı değişiklikte (`i18n-parity.test.jsx`); toast'a düşen sunucu
   mesajları `Msg.t(tr,en)`; `.properties` değerlerinde ham Türkçe karakter yok (`\uXXXX`); ikon
   yalnız `lucide-react` (`Rocket`/`Tag`/`GitCommit`/`History`/`Undo2`).
8. **Tasarım dili:** mevcut `ui/` primitifleri (SegmentedControl, PaginationBar, StatusBlock,
   Dialog, Toast, CopyButton) + `scripted/VersionTimeline.jsx`'in zaman-çizelgesi deseni
   (`sc-vt` sınıf ailesi) türetilir — sıfırdan tablo yazılmaz; dark theme elle doğrulanır.
9. Ortam Windows (`JAVA_HOME=C:\Program Files\Zulu\zulu-25`, Maven
   `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`, Node 24); smoke öncesi
   `mvn package -DskipTests` + `npm run build` + `start-local.ps1`. Hiçbir şey commit'lenmez;
   coverage floor yalnız yukarı; `TESTING.md` etkilenirse güncellenir.

## Keşifte doğrulanmış altyapı gerçekleri (2026-09-10 — yeniden keşfetme, DOĞRULA ve kullan)

**Sürümün bugünkü yolculuğu (build → imaj → uygulama → ekran):**
- Tek doğruluk kaynağı kök `VERSION` = `20.53.1`; `Chart.yaml` version/appVersion aynı. `pom.xml`
  `<version>7.2.0</version>` (satır 16) BAYAT — CI bump'lamıyor; `AppVersion` manifest yedeği bu
  yüzden yanlış sürüm verebilir (AppVersion.java 40'taki yorum bunu itiraf ediyor).
  `docker-compose.yml` `VERSION: "1.0.0"` / `image: site-monitor:1.0.0`, `helm/…/environments/*.yaml`
  `tag: "1.0.0"`/`"1.0.0-rc"`/`develop` yer tutucu, `k8s/deployment.yaml` `version: "1.0.0"` — hepsi
  bayat/yer tutucu (PROD_KAPISI Y16 "k8s/ ↔ helm ayrışması" açık madde).
- `release.yml`: gate (CI yeşil şartı, 34–132) → detect (bump türü commit BAŞLIKLARINDAN; major =
  gövdede `BREAKING[- ]CHANGE` **case-insensitive** VEYA `!:` başlık — satır 192; minor = `^feat`
  194) → release: `VERSION`+`Chart.yaml` bump + `chore(release)` commit (238–252) → changelog =
  `git log PREV..HEAD --pretty="- %s"` (255–271) → Docker build-arg `VERSION`/`BUILD_DATE`/
  `GIT_COMMIT` (292–295; **`BUILD_DATE=${{ github.event.repository.updated_at }}` — bu repo
  meta zamanıdır, build zamanı DEĞİL**) → Trivy (report-only) → Helm OCI push → annotated tag
  `vX.Y.Z` "Release vX.Y.Z" (326–335) → main→develop back-merge → GitHub Release gövdesi = commit
  listesi (347–369). Prod'a DEPLOY ADIMI YOK — prod dağıtımı ops tarafından elle `helm upgrade`
  (docs/DEPLOY_GECIS_PLANI.md; PROD_KAPISI: `--set secret.secretKey=…` zorunlu).
- `Dockerfile`: `ARG VERSION/BUILD_DATE/GIT_COMMIT` (34–36) YALNIZ OCI `LABEL`'lara giriyor (41–45:
  `image.version/created/revision`) — çalışan uygulamaya commit/build zamanı GEÇMİYOR. `VERSION`
  dosyası `/app/VERSION` olarak kopyalanıyor (58; frontend stage için 11). Sürüm meta boşluğu
  burada kapanır (Faz 1).
- `AppVersion.resolveWithSource(env)`: `site.monitor.version` property (=`APP_VERSION` env,
  application.properties 310) → `VERSION`/`../VERSION`/`/app/VERSION` → jar manifest → `unknown`;
  kaynak etiketi env|file|manifest|none. `StartupLogger.appSection` (105–106) sürüm+kaynak satırı,
  banner `ETKİN KONFİGÜRASYON — SiteMonitor <sürüm>` (280–283), JSON `_meta.version` (310).
- Frontend: `vite.config.js` 7/11 `../VERSION` → `__APP_VERSION__` (derleme yedeği);
  `utils/appVersion.js` `BUILD_VERSION` (19), `setRuntimeVersion` (24), `currentVersion` (29) —
  gerçek kaynak ÇALIŞMA ANINDA `/api/branding` `app_version` (BrandingController 132, public,
  no-store). Gösterim: `Nav.jsx` 18 `useAppVersion()`, `sb-brand-version` çipi (198, 209 — tıklanmaz,
  düz `<span>`); `HelpPage.jsx` 32–36 kılavuzdaki `{{VERSION}}` yer tutucusunu çözer, 100
  `v{appVersion}`.
- Actuator: `management.endpoints.web.exposure.include=health,prometheus,info` (674), base-path `/`
  (679) → `/info` HERKESE açık ama `management.info.env.enabled=false` (683) ve build-info/git
  properties YOK → boş JSON. `spring-boot-maven-plugin` (pom 179) `build-info` goal'ü TANIMSIZ;
  `git-commit-id` plugin'i yok. `/info` `/api/**` dışında olduğu için AuthInterceptor korumaz.

**Dağıtım anına dair bugün ne var, ne yok:**
- `SchedulerService`: `HOSTNAME`/`INSTANCE_ID` (259–261, hostname+8 hex); `runOnStartup`
  `@EventListener(ApplicationReadyEvent)` (291–335): REFUSING_TRAFFIC → `applySchemaPatches()` →
  **`auditService.recordSystemEvent("SCHEMA_PATCH","SYSTEM","db",…)` (300) → HER POD AÇILIŞINDA bir
  audit satırı** (sürümsüz) → bootstrap → ACCEPTING_TRAFFIC (335). `applySchemaPatches` 491,
  `patch()` 1317. `getSystemHealth` (2040) `scheduler.instance_id` (2052) döner; uptime/başlangıç
  zamanı/sürüm YOK. Helm `deployment.yaml` Downward API ile `NODE_NAME`/`POD_NAME`/`POD_IP` env
  veriyor (Tanıla ekranı için) — dağıtım kaydına hazır girdi.
- `AuditEventCatalog`: `SYSTEM_STARTUP` (189) ve `SYSTEM_SHUTDOWN` (188) katalogda VAR, `main`
  kodda HİÇ YAYILMIYOR (grep: yalnız katalog) — ölü giriş; kategori `SYSTEM` (274). Audit
  retention 365 gün (properties 412). `AuditService.recordSystemEvent` (455–466) actor=SYSTEM.
- `ShutdownLogger`: `ContextClosedEvent` (41–47; crash nedeni `CRASH_REASON`), `ApplicationFailedEvent`
  (50), JVM shutdown hook (hard exit). Kapanış NEDENİ loglanıyor ama DB'ye yazılmıyor.
- `SystemHeartbeat` = yalnız `recorded_at`; `ExtendedHealthService.recordHeartbeat` `@Scheduled
  fixedDelay=60s` (81–82) — instance/sürüm yok; her replica yazar. Dağıtım kaydının `last_seen_at`
  tazelemesi için doğal kanca.
- `RetentionRun` (model) çalışma-özeti tablosu deseni: ISO-UTC string `started_at`/`finished_at`,
  `instance_id`, `triggered_by` — yeni `deployment_history` aynı desenle kurulur.
  `ExtendedHealthService.getCleanupStatus` "N saattir çalışmadı" sinyali emsali.
- Ortam kimliği YOK: `springProfile: prod` dev/staging/master ÜÇÜNDE de (values 41, environments
  22/22/26) → profil ortamı ayırt etmez; Helm revizyonu/chart sürümü/imaj ref pod'a geçmiyor
  (`app.kubernetes.io/version` yalnız label).

**Yayın geçmişinin ham malzemesi:**
- Git: `origin=github.com/inanmise/site-monitor`; **628 tag** `v1.0.0` (2026-05-16) → `v20.53.1`
  (packed-refs), hepsi bot imzalı annotated "Release vX" → tagger tarihi = yayın anı, EKSİKSİZ.
  Dört ayda major **1→20 (19 major bump)**: hipotez = `BREAKING[- ]CHANGE` gövde taramasının yanlış pozitifleri
  (Türkçe/İngilizce düzyazıda geçen "breaking change") — Faz 0'da `git log` ile doğrulanır.
- `CHANGELOG.md`: Keep-a-Changelog biçimi; `[Unreleased]` (9) altında Mayıs'tan beri birikmiş
  ~26 madde / ~118 satır; son tarihli başlık `## [12.1.0] — 2026-05-24` (127); 12.1.0→20.53.1 arası ~600 sürüm
  başlıksız. CI CHANGELOG'a DOKUNMUYOR. Alt bağlantı `your-org/cert-monitor` (bayat).
- GitHub Releases: her tag için gövde = commit başlıkları + compare linki (release.yml 352–369) —
  kurum ağından erişilemez, yalnız retro script'in kaynağı olabilir.

**Yüzey emsalleri:** `SystemHealth.jsx` katlanır bölümler `openSection` ('db','users' — 109–112),
`sys-card-header`/`metrics-title` düzeni; `admin/retention/RetentionRunsPanel.jsx` sunucu-sayfalı
çalışma geçmişi (süzgeç+CSV+URL `r_` param); `scripted/VersionTimeline.jsx` olay zaman çizelgesi
(`EVENT_STYLE` ikon/ton haritası 26–35, `sc-ver-chip`, `sc-ver-current`); `App.jsx` `VALID_TABS`
(122–127); `Nav.jsx` `system` sekmesi `isGlobalAdmin || isAudit` (83), `help` herkese (108);
`PermissionCatalog` `system_health.read` (66), `audit_log.read` (89); `api/client.js`
`getBranding` (214), `getSystemHealth` (827), `getHeartbeatTimeline` (844), `getAuditLogs` (780).
Whitepaper tazelik kapısı (`whitepaper-pdf-freshness.test.jsx`, manifest+sha256) — üretilmiş
dosya bayatlamasına karşı test deseni; release indeksi için aynı desen.

## Faz 0 — Keşif doğrulaması + karar noktaları

Gerçekleri doğrula, ÖZELLİKLE: (1) `git for-each-ref refs/tags --sort=taggerdate
--format='%(refname:short)|%(taggerdate:iso8601)|%(objectname:short)'` ile 628 tag'in tarih/sıra
sağlığı (tarihsiz/lightweight tag var mı); (2) `git log --all -i --grep='breaking[- ]change'`
çıktısını major geçişleriyle eşleştirip K7 hipotezini test et (kaç major gerçek?); (3)
`SYSTEM_STARTUP/SHUTDOWN`'ın gerçekten hiç yayılmadığı (test kodunda da); (4) `.dockerignore`
`.git`'i dışlıyor mu (K3b'yi öldürür); (5) `management.info` yüzeyinin dışarıdan gerçekten boş
döndüğü. Sonra kararları seçenek + öneriyle sun (`tasarim` modunda karar dosyası):

- **K1 — Yüzey/yerleşim:** (a) **ÜÇLÜ (ÖNERİLEN):** Nav sürüm çipi TIKLANIR → popover ("v20.53.1 ·
  yayın 10 Eyl 14:22 · prod'a devreye alma 10 Eyl 16:05 · commit 3e7300a · çalışma süresi 2s 14dk ·
  Yenilikler →") herkese; **Sistem Sağlığı → yeni katlanır bölüm "Sürüm & Dağıtım"** (zaman
  çizelgesi, stat şeridi, elle kayıt) admin+AUDIT; **Yardım → "Yenilikler / Sürüm Notları"** herkese
  (release indeksi + CHANGELOG). (b) ayrı `releases` sekmesi (VALID_TABS + Nav — kalabalık).
  (c) yalnız Sistem Sağlığı.
- **K2 — Dağıtım kaydının kaynağı:** (a) **uygulamanın KENDİNİ kaydetmesi (ÖNERİLEN):**
  `ApplicationReadyEvent`'te `deployment_history` satırı — prod'da CI deploy adımı olmadığı için
  tek güvenilir kaynak; elle helm upgrade'i de, rollback'i de, crash-restart'ı da görür.
  (b) CI/CD tarafından kayıt — prod deploy manuel → UYGULANAMAZ. (c) (a) + Helm hook (post-upgrade
  Job) — ek karmaşıklık, sonraki faz.
- **K3 — Build meta'nın uygulamaya taşınması:** (a) **Docker ARG→ENV (ÖNERİLEN):** Dockerfile'da
  `ENV APP_GIT_COMMIT=${GIT_COMMIT} APP_BUILD_TIME=${BUILD_DATE} APP_IMAGE_VERSION=${VERSION}` +
  properties `site.monitor.build.commit/time` — `.git` gerekmez, `build-image.sh` zaten SHA
  geçiriyor. (b) `spring-boot-maven-plugin` `build-info` + `git-commit-id-maven-plugin` →
  `BuildProperties`/`GitProperties` bean'leri — pom sürümü bayat olduğu için ÖNCE pom `${revision}`
  (CI-friendly) düzeltmesi şart, Docker context'e `.git` gerekir. **Alt karar K3.1 — pom senkronu:**
  `<version>${revision}</version>` + `<revision>0.0.0-local</revision>` varsayılanı, Dockerfile
  `mvn package -Drevision=$(cat /app/VERSION)` → manifest ve (varsa) build-info doğru olur
  (öneri: EVET — bayat 7.2.0 manifest yedeği bir gün yanlış sürüm loglayacak).
- **K4 — Helm/K8s kimliği:** Deployment env'ine `HELM_RELEASE_NAME={{ .Release.Name }}`,
  `HELM_RELEASE_REVISION={{ .Release.Revision }}`, `HELM_CHART_VERSION={{ .Chart.Version }}`,
  `APP_IMAGE_REF=<repo:tag>`, `APP_ENVIRONMENT={{ .Values.environmentName }}` (values: dev/
  staging/prod — environments/*.yaml'a eklenir) ve Downward API `CONFIG_CHECKSUM`
  (`metadata.annotations['checksum/config']`) → kayıt "helm rev 47, yalnız config değişti" ile
  "sürüm geçişi"ni ayırt eder. Hepsi boş-toleranslı (compose/yerel koşumda yok). Öneri: EVET —
  chart değişikliği prod'da zaten her sürümde yapılan `helm upgrade` ile gelir; `k8s/` düz
  manifestlerine de aynı env'ler eklenir (Y16 ayrışması büyütülmez).
- **K5 — Yayın indeksi (hangi sürüm ne zaman çıktı):** (a) **CI üretir, imaja gömülür, backend
  servis eder (ÖNERİLEN):** `release.yml` "Bump version files" adımında `scripts/gen-release-index.mjs
  --append` → `docs/releases/index.json` (version, tag, released_at UTC, commit, bump, subjects[]
  {type,scope,subject}, compare_url); Dockerfile `COPY docs/releases/index.json /app/releases.json`;
  backend `AppVersion` aday-yol deseniyle okur; `GET /api/system/releases`. (b) yalnız frontend'e
  `?raw` gömme (Yardım için yeter, API/merge için yetmez). (c) çalışma anında GitHub API — kural 2
  gereği HAYIR. **Gösterim yoğunluğu (K5.1):** 600+ sürüm → varsayılan görünüm "dağıtılmış
  sürümler + minor/major" (patch'ler katlanır, "tümünü göster" anahtarı), gün bazlı gruplama.
- **K6 — CHANGELOG politikası:** (a) **Keep-a-Changelog release adımı otomasyonu (ÖNERİLEN):** CI
  release'te `[Unreleased]` DOLUYSA içeriğini `## [X.Y.Z] — YYYY-MM-DD` altına taşır, BOŞSA başlık
  açmaz (600 boş başlık üretilmez); kürasyon insan işi kalır, tarihleme makine işi olur; alt
  bağlantılar `inanmise/site-monitor` compare linkine düzelir. 12.1.0→20.53.1 boşluğu için TEK
  SEFERLİK toplu başlık: `## [12.1.0 → 20.53.1] — 2026-05-24 … 2026-09-10 (toplu; sürüm bazında
  tarih için release indeksi)`. (b) git-cliff ile tamamen üretilmiş changelog (628 sürüm → gürültü;
  kürasyon kaybolur). (c) dokunma (boşluk büyümeye devam eder).
- **K7 — Major bump politikası:** bugünkü kural gövdede geçen her "breaking change" ifadesini major
  sayıyor (release.yml 192). (a) **yalnız Conventional Commits'e uygun sinyal (ÖNERİLEN):** başlıkta
  `!:` VEYA gövdede satır başı `BREAKING CHANGE:`/`BREAKING-CHANGE:` footer'ı (case-sensitive,
  `^BREAKING[- ]CHANGE:`). (b) major yalnız `workflow_dispatch` ile elle. (c) dokunma. Karar geçmiş
  numaraları DEĞİŞTİRMEZ; raporda "19 major bump'ın N'i gerçek" kanıtıyla sunulur.
- **K8 — Geçmişe dönük DAĞITIM verisi (retro):** (a) **dürüst geri doldurma (ÖNERİLEN):** her
  ortamın DB'sindeki audit `SCHEMA_PATCH` (actor SYSTEM) satırları → `deployment_history`'ye
  `source=BACKFILL`, `version=NULL` ("sürüm bilinmiyor — kayıt öncesi"), pod/instance yok; admin
  "Elle dağıtım kaydı" ile bilinen geçişleri girer (`source=MANUAL`, not zorunlu, audit'li) —
  ekranda kaynak rozeti (Otomatik/Elle/Geri doldurma). (b) (a) + tahmin: restart anından önceki
  son tag "olası sürüm" bayrağıyla (prod deploy'lar yayından günlerce sonra yapıldığı için yanıltıcı
  — yalnız dev/staging'de anlamlı). (c) retro yok. Not: 365 günlük audit ufku = geri doldurma ufku.
- **K9 — Erişim/izin ve anonim yüzey:** `GET /api/system/version` kimlik doğrulamalı HERKES (Nav
  popover); dağıtım geçmişi + elle kayıt için yeni `release_history.read` (VIEW; varsayılan ADMIN+
  AUDIT) ve `release_history.edit` (EDIT; ADMIN) — `PermissionCatalog`'a aynı değişiklikte (CLAUDE.md
  kuralı), `system_health.read`'e yaslanmak yerine (öneri: yeni anahtar — matris görünürlüğü).
  Anonim `/info`'ya build/commit AÇILMAZ; `/api/branding` `app_version` olduğu gibi kalır (commit
  eklenmez). Öneri: bu şekilde.
- **K10 — Saklama:** `deployment_history` küçük (pod açılışı başına 1 satır) → (a) **silinmez
  (retention 0/devre dışı) — RetentionCatalog'a "muaf" politika satırı + `docs/RETENTION_POLITIKASI.md`
  güncellemesi (ÖNERİLEN);** (b) 1095 gün. `SYSTEM_STARTUP/SHUTDOWN` audit satırları normal 365 gün.
- **K11 — Metrik/anotasyon:** `sitemonitor_build_info{version,commit,environment} 1` gauge +
  `sitemonitor_deployment_started_seconds` (epoch) — Prometheus'ta `changes(...)` ile "sürüm değişti"
  anotasyonu/alarmı, DORA dağıtım sıklığı Grafana'dan okunur. Öneri: EVET (ucuz; `MetricsService`
  emsali). Ek alt karar: haftalık rapora "bu hafta N dağıtım" satırı — E listesine.

## Faz 1 — Build/CI zinciri: sürüm META'sının doğru üretilmesi ve taşınması

- **Dockerfile:** runtime stage'e `ENV APP_GIT_COMMIT=${GIT_COMMIT} APP_BUILD_TIME=${BUILD_DATE}
  APP_IMAGE_VERSION=${VERSION}`; `COPY docs/releases/index.json /app/releases.json` (K5a; dosya
  yoksa build kırılmasın — repo'da her zaman var olacak, retro script ilk hâlini üretir).
  K3.1 seçildiyse backend stage `mvn package -DskipTests -Drevision=$(cat /app/VERSION)`
  (VERSION o stage'e de kopyalanır).
- **release.yml:** `BUILD_DATE` → `date -u +%Y-%m-%dT%H:%M:%SZ` adım çıktısı (294'teki
  `repository.updated_at` KALKAR); "Bump version files" adımına release indeksi ekleme +
  (K6a) CHANGELOG release adımı; commit'e `docs/releases/index.json` + `CHANGELOG.md` eklenir
  (`git add` satırı 251 genişler; `[skip ci]` belirteci literal yazılmaz — 78–81'deki tuzak);
  (K7) detect regex'i; GitHub Release gövdesine indeksteki gruplu özet (feat/fix/diğer).
  Job summary'ye `Released vX · commit · build time` satırı.
- **build-image.sh/.ps1, docker-compose.yml:** `VERSION`'ı dosyadan oku (compose `args.VERSION:
  "${APP_VERSION:-…}"` + `.env.example`'a not), sabit `1.0.0` KALKAR; `environments/*.yaml` yer
  tutucuları yorumla "CI --set image.tag ile ezer" netleştirilir (dokunmadan da olur — K4'te
  `environmentName` eklenirken düzeltilir). `k8s/deployment.yaml` `version: "1.0.0"` label'ları
  için not (Y16 kapsamı; burada yalnız env eklenir).
- **Helm (K4):** `deployment.yaml` env bloğu + `values.yaml` `environmentName: ""` +
  `environments/{develop,release,master}.yaml` → `dev`/`staging`/`prod`; `helm lint` üç values ile
  (ci.yml zaten koşuyor). `NOTES.txt`'e "Sürüm & Dağıtım ekranında bu release'in revizyonu görünür".
- **application.properties:** `site.monitor.build.commit=${APP_GIT_COMMIT:}`,
  `site.monitor.build.time=${APP_BUILD_TIME:}`, `site.monitor.build.image-ref=${APP_IMAGE_REF:}`,
  `site.monitor.deploy.environment=${APP_ENVIRONMENT:}`, `site.monitor.deploy.helm-release/
  helm-revision/chart-version/config-checksum`, `site.monitor.releases.index-path=${RELEASES_INDEX_PATH:}`
  (aday yollar: `releases.json`, `../docs/releases/index.json`, `/app/releases.json`).
  `AppSettingsCatalog`'a GİRMEZ (salt-okunur build gerçekleri; DB'den override anlamsız).
- **StartupLogger.appSection:** `build.commit`, `build.time`, `deploy.environment`,
  `deploy.helm-revision`, `deploy.image-ref` satırları (kaynak etiketi `[env]`/`[none]`); banner
  değişmez. JSON `_meta`'ya `commit` eklenir (log-toplama korelasyonu).

## Faz 2 — Runtime: kalıcı dağıtım kaydı + audit + metrik

- **`DeploymentRecord` (`deployment_history`)**: `id`, `version`, `git_commit`, `build_time`,
  `image_ref`, `environment`, `helm_release`, `helm_revision`, `chart_version`, `config_checksum`,
  `instance_id`, `hostname`, `pod_name`, `node_name`, `java_version`, `started_at` (ISO UTC),
  `ready_at` (ACCEPTING_TRAFFIC anı), `last_seen_at`, `ended_at`, `end_reason` (graceful/crash/
  failed-start/unknown), `source` (AUTO|MANUAL|BACKFILL), `note`, `created_by` (MANUAL'da
  kullanıcı). İndeks: `started_at`, `(environment, started_at)`, `version`. `patch()` satırları
  (indeksler + eski DB'de tablo). Repository yazan metodlar `@Transactional`+`int`.
- **`ReleaseHistoryService`** (yeni, `service/`):
  - `recordStartup()` — `SchedulerService.runOnStartup` içinde `applySchemaPatches()`'ten HEMEN
    SONRA açık çağrı (historyBackfill deseni; listener sırasına güvenilmez); `ready_at` finally
    bloğunda ACCEPTING_TRAFFIC'in yanında güncellenir. Hata YUTULUR (açılışı düşürmez, WARN).
  - `touch()` — `ExtendedHealthService.recordHeartbeat` (60 sn) içinden `last_seen_at` günceller
    (aynı tick, ek zamanlayıcı yok).
  - `recordShutdown(reason)` — `ShutdownLogger.onContextClosed`'dan (Spring canlıyken) best-effort
    `JdbcTemplate` UPDATE; `ApplicationFailedEvent`'te `end_reason=failed-start`. Hard exit'te
    `ended_at` NULL kalır → ekran "son görülme" ile kapatır ve "kayıtsız kapanış" rozeti gösterir.
  - **Geçiş türetimi (sunucuda, tek yerde):** ortam bazında `started_at` sıralı kayıtlarda önceki
    farklı sürümden sonra gelen ilk satır = **sürüm geçişi (`deployedAt`)**; aynı sürümün sonraki
    satırları = restart/ölçekleme; semver küçülmesi = **geri alma**; rolling update örtüşmesi
    (eski `ended_at` > yeni `started_at`) hesaplanıp gösterilir. `VersionLabels.SEMVER` ile
    karşılaştırma; ayrıştırılamayan sürüm en sona (kilitlemez).
  - **Yayın ↔ dağıtım birleşimi:** indeksteki her sürüm için `firstDeployedAt(env)`, yayın→devreye
    alma gecikmesi, "atlandı" (hiç açılmadı), "şu an koşuyor". Çok replica'da kaynak DB ortak →
    tek gerçek.
- **Audit:** `SYSTEM_STARTUP` (detail JSON: version, commit, environment, instance, helmRevision) ve
  `SYSTEM_SHUTDOWN` (reason) artık GERÇEKTEN yayılır (`recordSystemEvent`); `SCHEMA_PATCH` aynen
  kalır (retro kaynağı bozulmaz). Elle kayıt: `DEPLOYMENT_RECORD_MANUAL` (katalog + `categoryOf`
  SYSTEM dalına `DEPLOYMENT_` öneki), `DEPLOYMENT_RECORD_DELETE` (yalnız MANUAL satır silinebilir).
- **Metrik (K11):** `MetricsService`'e `sitemonitor_build_info` gauge (etiketler sabit, kardinalite
  1) + `sitemonitor_deployment_started_seconds`.
- **Config bayrağı:** `site.monitor.deploy.history.enabled=${DEPLOY_HISTORY_ENABLED:true}` (kapatma
  yalnız test/teşhis için; StartupLogger'a satır).

## Faz 3 — Backend: yayın indeksi + API

- **`ReleaseIndexLoader`:** aday yollardan `releases.json` okur (Jackson), bozuk/eksik dosyada boş
  liste + WARN (uygulama çökmez); sha256 + yükleme zamanı meta (Sistem Sağlığı "indeks tazeliği").
  Girdi modeli: `{version, tag, released_at, commit, bump, subjects:[{type,scope,subject}],
  compare_url, notes?}` — `notes` CHANGELOG'daki `## [X.Y.Z]` bölümünden CI'da çıkarılır (K6a).
- **Uçlar (`SystemController` altında ya da yeni `ReleaseHistoryController`):**
  - `GET /api/system/version` (auth, herkes): `{version, commit, buildTime, environment, startedAt,
    readyAt, uptimeSeconds, deployedAt (bu sürümün bu ortamda ilk açılışı), releasedAt (indeksten),
    helmRevision, imageRef, instance, releaseLag}`. NO-STORE.
  - `GET /api/admin/system/releases?env=&page=&size=&scope=deployed|minor|all` (`release_history.read`):
    birleşik zaman çizelgesi satırları (yayın + dağıtım olayları tek akışta, `kind`=RELEASE|DEPLOY|
    RESTART|ROLLBACK|SHUTDOWN|MANUAL|BACKFILL) + özet `{current, previous, deploymentsLast30d,
    avgReleaseLagHours, rollbacks, skippedReleases, restartsLast7d}`.
  - `GET /api/admin/system/deployments?env=&from=&to=&page=` ham dağıtım kayıtları (CSV `?format=csv`
    — RetentionRunsPanel emsali).
  - `POST /api/admin/system/deployments` (`release_history.edit`): elle kayıt `{version, environment,
    deployedAt, note}` — semver doğrulanır, gelecek tarih reddedilir, audit'lenir;
    `DELETE …/{id}` yalnız `source=MANUAL`.
  - `GET /api/system/releases/notes?since=<version>` (auth, herkes): Yardım "Yenilikler" +
    "son ziyaretinden beri" için sürüm aralığı notları.
- `Msg.t` ile TR/EN hata metinleri; scoped admin (`SessionScope`) davranışı: okuma serbest, elle
  kayıt yalnız global admin (`requireNotScopedAdmin`) — `SettingsScopedAdminGateTest` genişletilir.

## Faz 4 — Frontend

- **Nav sürüm popover'ı:** `sb-brand-version` `<button>` olur (a11y: `aria-haspopup`, Esc kapatır);
  içerik `GET /api/system/version`: sürüm çipi, "Yayın: …", "Devreye alma (prod): …" (env adı
  gerçek değerden), "Çalışma süresi", commit kısa + `CopyButton`, "Yenilikler →" (Yardım'a atlar),
  admin ise "Dağıtım geçmişi →" (Sistem Sağlığı bölümüne derin bağlantı `?tab=system&sec=releases`).
  Sunucu okunamazsa yalnız sürüm (bugünkü davranış) — asla boş popover.
- **Sistem Sağlığı → "Sürüm & Dağıtım" katlanır bölümü** (`openSection='releases'`):
  - Stat şeridi: Koşan sürüm · Devreye alma · Önceki sürüm · Son 30 gün dağıtım · Ortalama
    yayın→devreye alma · Geri alma sayısı · Atlanan sürüm.
  - **Zaman çizelgesi** (`VersionTimeline` deseni türevi `ReleaseTimeline.jsx`): olay ikon/ton
    haritası RELEASE(`Tag`)/DEPLOY(`Rocket`, vurgulu)/RESTART(`RotateCcw`, soluk)/ROLLBACK(`Undo2`,
    danger)/SHUTDOWN/MANUAL(`PencilLine`)/BACKFILL(`History`, kesikli); satırda sürüm çipi,
    "ŞU AN" rozeti (`sc-ver-current`), kaynak rozeti, ortam çipi, meta (zaman · pod · düğüm · helm
    rev); genişletmede commit listesi (feat/fix gruplu), imaj ref, config checksum farkı ("yalnız
    yapılandırma değişti"), kapanış nedeni.
  - Süzgeçler: ortam (SegmentedControl), kapsam (Dağıtılanlar / Minor+ / Tümü — K5.1), tarih
    aralığı, "restart'ları gizle"; URL `rel_` öneki (`useUrlQuerySync`); `PaginationBar`; CSV.
  - "Elle dağıtım kaydı ekle" (`release_history.edit`): Dialog form (sürüm SearchableSelect
    indeksten, ortam, tarih-saat `DateTimeField`, not) → Toast; MANUAL satırda sil.
  - Boş durumlar: indeks yoksa "Yayın indeksi imajda yok (build eski) — dağıtım kayıtları yine
    gösterilir"; kayıt yoksa "Bu sürümden itibaren otomatik kaydedilecek; geçmiş için geri doldurma
    çalıştırın".
- **Yardım → "Yenilikler":** HelpPage üstüne sekme/bölüm: sürüm listesi (K5.1 yoğunluk kuralı),
  her sürümde tarih + bump rozeti + gruplu commit özeti + CHANGELOG notu (varsa); "Son ziyaretinden
  beri" şeridi (localStorage `sm.release.lastSeenVersion`, try/catch; Nav çipinde küçük nokta —
  E1 ile birlikte karar). `{{VERSION}}` çözümü aynen.
- i18n `rel.*` anahtar ailesi TR+EN; savunmacı render (eksik alanlı satır düşer, bölüm çökmez —
  ResponseTimeChart kuralı); dark theme el doğrulaması; `Nav.test.jsx` mevcut beklentileri korunur.

## Faz 5 — Geçmişe dönük geri doldurma (retro)

- **Yayın indeksi (tam):** `scripts/gen-release-index.mjs --full` yerel klonda 628 tag'i tarar:
  tagger tarihi (UTC), commit SHA, `git log prev..tag --no-merges --pretty='%s'` başlıkları
  (conventional ayrıştırma type/scope), bump türü sürüm farkından; opsiyonel `--github` ile
  GitHub Releases `published_at` karşılaştırması (yalnız geliştirici makinesinde). Çıktı
  `docs/releases/index.json` (sürüm sırasıyla, deterministik; diff'lenebilir). Tazelik kapısı:
  `release-index.test.js` — `VERSION` dosyasındaki sürüm indekste var mı (CI'da ilk tam koşum
  sonrası her release CI ekler; yerelde eksikse `npm run gen:release-index` ipucu, whitepaper
  manifest deseni).
- **CHANGELOG (K6a):** toplu başlık + `[Unreleased]` boş; alt bağlantılar düzeltilir; `README.md`/
  `TESTING.md`'ye "Sürüm geçmişi nasıl okunur" kısa bölüm.
- **Dağıtım geri doldurma (K8a):** `POST /api/admin/system/deployments/backfill` (global admin,
  idempotent, audit'li) → audit `SCHEMA_PATCH` satırlarını `source=BACKFILL, version=NULL`
  kayıtlarına çevirir (aynı dakikadaki çoklu satır = çoklu replica → tek olay, `instance` yok);
  Sistem Sağlığı'nda "Geri doldur" düğmesi + önizleme sayısı. Bilinen geçişler için elle kayıt
  örneği raporda gösterilir; ASLA uydurma tarih girilmez — kullanıcı girer.
- Tek pod'lu ortamlar (dev/yerel) için de aynı akış; `environment` boşsa "belirsiz" etiketi.

## Faz 6 — Testler

- **Backend:** `ReleaseHistoryServiceTest` — açılış kaydı (alanlar, hata yutma), heartbeat touch,
  graceful/failed-start kapanış, geçiş türetimi (aynı sürüm restart ≠ geçiş; semver gerileme =
  rollback; rolling örtüşme; ayrıştırılamayan sürüm), yayın↔dağıtım birleşimi (lag, skipped,
  current); `ReleaseIndexLoaderTest` (bozuk JSON → boş + WARN, aday yol sırası); controller
  testleri — izin kapısı (`release_history.read/edit`), scoped admin 403, MANUAL dışı silme 4xx,
  gelecek tarih reddi, no-store başlığı, CSV; `AuditEventCatalog` yeni tipler + `categoryOf`;
  `PermissionCatalog` sözleşme testi; `MetricsService` gauge; `StartupLogger` yeni satırlar +
  maskeleme (commit maskelenmez, sır yok); `RepositoryWriteTransactionGuardTest` yeşil;
  `PropertiesEncodingTest` yeşil. K3.1 seçildiyse `AppVersion` manifest yedeğinin doğru sürümü
  verdiği (paketlenmiş jar testi/`mvn package` sonrası doğrulama adımı).
- **Frontend:** `ReleaseTimeline.test.jsx` (olay türleri, ŞU AN rozeti, kaynak rozetleri, katlanan
  patch'ler, bozuk satır düşürme), `SystemHealth` yeni bölüm yükleme/hata (mock api),
  `Nav.test.jsx` popover (aç/kapat, sunucu hatasında yalnız sürüm), Help "Yenilikler" + "son
  ziyaretten beri" (localStorage engelli senaryo — `storage-disabled.test.jsx` deseni),
  `release-index.test.js` tazelik, `i18n-parity` yeşil, `permission-labels-sync` yeşil.
- **CI/Helm:** `helm lint` üç values; release.yml değişikliği için `act`/kuru koşum yapılamıyorsa
  adım adım gözden geçirme + `bash -n`; `gen-release-index.mjs` birim testi (conventional
  ayrıştırma, bump türetme).

## Faz 7 — Doğrulama, smoke ve rapor

1. `mvn -B clean verify` → `npm run test` → `npm run build` → `mvn package -DskipTests` →
   `start-local.ps1` → `/health` UP → açılış banner'ında yeni satırlar.
2. Smoke: login → Nav çipi popover (sürüm, devreye alma "az önce", commit boş/yerel) →
   Sistem Sağlığı "Sürüm & Dağıtım": AUTO satır + ŞU AN; backend'i durdur-başlat → RESTART satırı
   (geçiş DEĞİL); `VERSION`'ı geçici olarak farklı sürümle çalıştır (`APP_VERSION` env) → DEPLOY
   geçişi; eskiye dön → ROLLBACK rozeti; elle kayıt ekle/sil → audit satırları; geri doldurma
   önizleme; Yardım "Yenilikler"; `/metrics`'te `sitemonitor_build_info`; `/info` hâlâ boş;
   `/api/system/version` `Cache-Control: no-store`; TR/EN; dark theme.
3. Docker yolu (mümkünse): `./scripts/build-image.ps1` → konteynerde `env | grep APP_` ve
   `/app/releases.json` varlığı; compose ile açılış → kayıt satırında commit dolu.
4. Rapor: dosya listesi, test çıktıları, K kararları, K7 kanıtı (gerçek major sayısı),
   bilinen sınırlar (retro dağıtım verisi audit ufkuyla sınırlı ve sürümsüz; hard-kill'de
   `ended_at` yok; helm revizyonu yalnız Helm ile kurulan ortamlarda), prod dağıtım notu
   (ilk gerçek kayıt bu sürümün prod'a çıkışıyla oluşur; `helm upgrade` komutuna
   `environmentName` values'tan gelir, ek `--set` gerekmez).

## Zenginleştirme önerileri (kullanıcıya sun — şimdi mi sonra mı)

- **E1 — "Yeni sürüm" rozeti:** kullanıcı en son gördüğü sürümden farklı bir sürüm görünce Nav
  çipinde nokta + popover'da "Bu sürümde yeni: …" (localStorage, try/catch).
- **E2 — Haftalık rapora dağıtım satırı:** `WeeklyReportService`/haftalık kullanılabilirlik
  maili'ne "Bu hafta N dağıtım (vX→vY), M restart, 0 geri alma".
- **E3 — Dağıtım e-postası (opt-in):** sürüm geçişi tespit edilince admin e-postası ("prod'da
  v20.54.0 devreye alındı · commit · yenilikler") — `SecurityMailDispatcher`/SMTP altyapısı hazır.
- **E4 — Grafana anotasyon köprüsü:** K11 metriklerinden anotasyon kuralı örneği `docs/`'a;
  Prometheus alert `changes(sitemonitor_build_info[10m]) > 0` şablonu.
- **E5 — Ortamlar arası görünüm:** her ortamın DB'si ayrı olduğu için tek ekranda görünmez; CI'ın
  ortam DB'lerini sorgulamadan, her ortamın `GET /api/system/version` çıktısını toplayan küçük bir
  "ortam matrisi" (dev/staging/prod hangi sürümde) — ayrı karar (ağ erişimi).
- **E6 — İmaj digest'i:** Y18 (digest pinleme) kapanınca `APP_IMAGE_REF`'e `@sha256:` eklenir →
  kayıt imaj bütünlüğünü de taşır; Trivy sonucu özetini indekse ekleme.
- **E7 — Sürüm karşılaştırma:** iki sürüm seçilir → aradaki commit/feat/fix listesi + CHANGELOG
  notları (compare linki yerine kurum içi görünüm).
- **E8 — Denetim dışa aktarımı:** SOX/denetim için "değişiklik yönetimi kanıtı" PDF/CSV: sürüm,
  yayın anı, devreye alma anı, onaylayan (elle kayıt notu), Prod Kapısı hükmü bağlantısı
  (`PROD_KAPISI_<tarih>.md` dosya adı deseni).
- **E9 — `k8s/` düz manifest senkronu:** Y16 kapsamında helm ile aynı env/label setine çekilmesi
  (bu komutun dışında, ayrı tur).

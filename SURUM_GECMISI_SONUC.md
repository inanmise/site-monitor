# Sürüm & Dağıtım Geçmişi — Sonuç Raporu (2026-09-11)

Komut: `/surum-gecmisi` (tam kapsam, K1–K11 + E1–E4). Plan: `C:\Users\7636\.claude\plans\description-sitemonitor-s-r-m-drifting-dawn.md`.
Kural 9 gereği **hiçbir şey commit'lenmedi**; tüm iş çalışma ağacında. Aynı ağaçta bu oturumda çözülen 9 ayrı kullanıcı bulgusu da duruyor (bkz. §7) — "release" dendiğinde ayrı commit'ler olarak öne alınacak.

## 1. Tek cümlelik cevap

Nav'daki sürüm çipine tıklayınca popover: **"v20.53.2 · Yayın 10/09/2026 21:19 · Devreye alma 11/09/2026 00:34 (local, İlk görülme) · yayından 3s 15d sonra · commit · çalışma süresi · helm rev"** + "Yenilikler →" ve (izinliyse) "Dağıtım geçmişi →". Yerel smoke ile doğrulandı.

## 2. Kararlar (K1–K11, E1–E4) — hepsi önerilen seçenekle uygulandı

| Karar | Uygulama |
|---|---|
| K1 üç yüzey | Nav çipi popover (herkes) · Sistem Sağlığı "Sürüm & Dağıtım" (release_history.read: ADMIN+AUDIT) · Yardım "Yenilikler" (herkes) |
| K2 uygulama kendini kaydeder | `SchedulerService.runOnStartup` → `applySchemaPatches` SONRASI `DeploymentHistoryService.recordStartup()`, finally `markReady()`; `ShutdownLogger.recordShutdown(graceful/crash/failed-start)`; heartbeat `touch()` |
| K3 + K3.1 | Dockerfile ARG→ENV (`APP_GIT_COMMIT/APP_BUILD_TIME/APP_IMAGE_VERSION`), `COPY docs/releases/index.json /app/releases.json`; pom `${revision}` (yerel jar `site-monitor-0.0.0-local.jar`) |
| K4 6 env | Helm deployment.yaml: APP_ENVIRONMENT, HELM_RELEASE_NAME/REVISION, HELM_CHART_VERSION, APP_IMAGE_REF, CONFIG_CHECKSUM (Downward API); `environmentName` dev/staging/prod; `helm lint` 3 values yeşil |
| K5 indeks CI'da | `scripts/gen-release-index.mjs` (`--full/--append/--check`), 629 sürüm, 452 KB, deterministik; `ReleaseIndexService` imajdan okur; varsayılan görünüm katlanmış |
| K6 CHANGELOG | `scripts/promote-changelog.mjs`; toplu `[12.1.0 → 20.53.2]` başlığı; bağlantılar `inanmise/site-monitor` |
| K7 major kapısı | release.yml: elle `major` ya da anlamlı commit yokken bump → `confirm_major=yes`; breaking yalnız `!:` ya da satır başı `BREAKING[- ]CHANGE:` |
| K8 dürüst geri doldurma | `POST /api/admin/deployments/backfill`: audit SCHEMA_PATCH → BACKFILL, sürüm NULL, `audit_ref` tekil (kısmi unique index); elle kayıt `POST /api/admin/deployments` (env regex, semver, ISO ≤ şimdi, not ≤500) |
| K9 izinler | `/api/system/version|releases|releases/notes` kimlikli herkes; `release_history.read` (VIEW) / `release_history.edit` (EDIT, kapsamlı müdür 403); anonim yüzey değişmedi; `management.info` kapalı kaldı |
| K10 saklama | `deployment_history` RetentionCatalog BOUNDED (asla silinmez), yalnız MANUAL satır silinir (aksi 409) |
| K11 metrikler | `sitemonitor_build_info{version,commit,environment}=1`, `sitemonitor_deployment_started_seconds` — yerelde `/metrics`'te doğrulandı |
| E1 yeni-sürüm noktası | `sm.release.lastSeenVersion` (try/catch); çipte nokta, popover'da "son ziyaretinizden beri N sürüm", Yenilikler'de şerit |
| E2 haftalık rapor satırı | `WeeklyAvailabilityReportService` @Lazy ctor (elle yazıldı) → "Bu hafta N dağıtım (vX→vY), M yeniden başlatma, R geri alma" (Outlook-safe td/hex; mail HTML-only) |
| E3 dağıtım e-postası | `DeploymentNotifyService` (TransitionEvent UPGRADE/ROLLBACK; ayar `site.monitor.deploy.notify.enabled` vars. KAPALI; alıcı `system-admin.email`; ≤5 öne çıkan) — **push paritesi bilinçli atlandı** (UserPushService alarm-olayına bağlı; sistem olayı taşıyamaz; gerekçe sınıf Javadoc'unda) |
| E4 Grafana | `docs/GRAFANA_SURUM_ANOTASYON.md` (anotasyon sorgusu, alarm kuralı, DORA paneli) |

## 3. Dosyalar

**Yeni backend:** `util/Semver`, `model/DeploymentHistory`, `repository/DeploymentHistoryRepository`, `service/BuildInfo`, `service/BuildInfoMetrics`, `service/ReleaseIndexService`, `service/DeploymentHistoryService`, `service/DeploymentNotifyService`, `controller/SystemInfoController`, `controller/DeploymentHistoryController`.
**Değişen backend:** SchedulerService (kancalar, unique index patch, health `build`), ShutdownLogger, ExtendedHealthService, AuditLifecycleListener (JSON detail), StartupLogger (build satırları), RetentionCatalog, AuditEventCatalog (SYSTEM_DEPLOYMENT_*), PermissionCatalog, AppSettingsCatalog, EmailNotificationService (E2/E3), WeeklyAvailabilityReportService, application.properties, pom.xml.
**Yeni frontend:** `utils/releaseUi.js`, `components/VersionChip.jsx`, `components/VersionPopover.jsx`, `components/ReleaseNotesPanel.jsx`, `components/admin/DeploymentHistoryPanel.jsx`.
**Değişen frontend:** api/client.js (`api.system.*`, `api.admin.getDeployments…`), Nav.jsx (çip), App.jsx (`handleTabChange(id, extraParams)`), HelpPage.jsx (Kılavuz | Yenilikler), SystemHealth.jsx (son bölüm + `?sec=releases`), scripted/VersionTimeline.jsx (geriye-uyumlu `eventStyles/renderMeta/versionPrefix/caret/renderBelow`), useUrlQuerySync (`d_` öneki), i18n TR/EN (`version.*`, `releases.*`, `deploy.*`, `help.view.*`, `perm.res.release_history.*`, `general.lbl…deploy.notify`), App.css.
**Build/CI/Helm/docs:** Dockerfile, docker-compose.yml, scripts/build-image.ps1, `.github/workflows/release.yml`, helm (deployment/values/environments/NOTES), `scripts/gen-release-index.mjs`, `scripts/promote-changelog.mjs`, `scripts/__tests__/gen-release-index.test.mjs`, `docs/releases/index.json`, CHANGELOG.md, `docs/RETENTION_POLITIKASI.md` (üretildi), `docs/GRAFANA_SURUM_ANOTASYON.md`.

## 4. Testler

- **Backend tam süit:** 257 sınıf, 3301 test, 0 hata (tam koşumda `ProcessProbeTest.largeOutput_doesNotDeadlock` 60 sn zaman aşımına takıldı — frontend kapsam süiti + uygulama yeniden başlatma ile eşzamanlı yük; dosyaya dokunulmadı, tek başına 2,7 sn'de yeşil). Yeni/uzayan sınıflar: SemverTest 5, DeploymentTransitionTest 8, ReleaseIndexServiceTest 6, BuildInfoTest 7, DeploymentHistoryServiceTest 18, SystemInfoControllerTest 6, DeploymentHistoryControllerTest 15, DeploymentNotifyServiceTest 8, AuditLifecycleListenerTest +2, WeeklyAvailabilityReportServiceTest +2, EmailBrandCidTest +1; kapılar (AuditEventCatalog, PermissionCatalog, RetentionCoverage/Doc, RepositoryWriteTransactionGuard, RepositoryNullableParamCast, WebConfig no-store 3 yeni URI, IdentityLeakGuard, PropertiesEncoding) yeşil.
- **Frontend tam kapı:** 207 dosya, 1856 test yeşil (1 todo); lint 0 hata; kapsam tabanı tamam. Yeni: releaseUi.test.js 5, VersionChip.test.jsx 5, ReleaseNotesPanel.test.jsx 4, DeploymentHistoryPanel.test.jsx 5, VersionTimeline.test.jsx 3, release-index.test.js 4; uzayan: Nav (çip), HelpPage (`?view=releases`), SystemHealth (6 bölüm, `releases` SONDA + izin mock'u).
- **Belge:** `TESTING.md`'ye yeni kapılar bölümü eklendi (backend/frontend/script; izin anahtarı tuzağı).
- **Script/CI/Helm:** `node --test scripts/__tests__` yeşil; `gen-release-index --check` "güncel"; release.yml 12 `run:` bloğu `bash -n` + 14 vakalı tespit kuru-koşumu; `helm lint` 3 ortam.

## 5. Yerel smoke (jar `0.0.0-local`, gerçek tarayıcı)

- Açılış: `Release index loaded: 629 release(s)`, `Permission backfill: 8 grant`, `deployment_history` ilk satır (FIRST_SEEN, local), `/api/system/version` → 401 + `Cache-Control: no-store`.
- Çip popover: sürüm/bump/yayın/devreye alma/gecikme/uptime; hata durumunda yalnız sürüm; Escape/dış tıklama kapatır; "Yenilikler" → `?tab=help&view=releases` (25 satır, katlanmış yamalar çipli); "Dağıtım geçmişi" → `?tab=health&sec=releases` (6. bölüm açık: koşan sürüm kartı, 5 KPI, zaman çizelgesi, tablo, CSV, "Denetimden geri doldur (343)", "Elle kayıt ekle").
- `/metrics`: `sitemonitor_build_info{commit="unknown",environment="local",version="20.53.2"} 1`, `sitemonitor_deployment_started_seconds`.
- Geçişler (ikinci tur): `APP_VERSION=99.0.0` ile açılış → çipte E1 noktası + popover "Yükseltme"; geri dönüş → "Geri alma · yayından 3s 54d sonra"; sert kill → "kayıtsız kapanış" rozeti + RESTART satırları (restarts=2).
- Yazma akışları tarayıcıda: elle kayıt (v20.40.0, 01/09) eklendi → zaman çizelgesi türleri yeniden türedi ("Yükseltme ← v20.40.0"); "Denetimden geri doldur (347)" koşuldu → ikinci tıklama "Geri doldurulacak kayıt yok" (idempotent); MANUAL süzgeci ile elle kayıt silindi, tablo boş-duruma düştü.
- TR + koyu tema: bölüm/çip/kart etiketleri Türkçe, `data-theme=dark` altında alt bar ve popover token'lı; 375 px'te yatay taşma yok (`scrollWidth=375`).
- E2/E3 e-posta gövdeleri: `MailPreviewDumpTest` (`-Dmail.preview.dir=…`, varsayılan atlanır) ile dökülüp tarayıcıda açıldı — haftalık raporda "🚀 Sürüm & Dağıtım · Bu hafta 2 dağıtım (v20.52.1 → v20.53.2), 1 yeniden başlatma, 0 geri alma" bandı; dağıtım bildirimi (yükseltme/geri alma) başlık, ortam, sürüm geçişi, tür, commit, öne çıkanlar ve "kırıcı değişiklik" satırıyla.

## 6. Bilinen sınırlar / prod notları

- Geri doldurma yalnız audit'in tuttuğu ufukta (365 gün) ve sürümsüz; hard-kill'de `ended_at` boş → "kayıtsız kapanış" rozeti; helm rev/imaj ref yalnız Helm ortamında; `HELM_RELEASE_REVISION` env'i her `helm upgrade`'de pod'u döndürür (checksum/config zaten döndürüyordu).
- İlk gerçek prod kaydı bu sürümün prod'a çıkışıyla oluşur; `environmentName` values'tan gelir (ek `--set` yok); backfill her ortamda bir kez koşulur (idempotent).
- Yerel/dev klonda indeks yoksa Yenilikler "dizin yok" uyarısı gösterir (hata değil). CI'da ilk `--append` bu sürümün Release adımında koşar; sonrasında `/app/releases.json` newest == VERSION.
- Bulundu ve düzeltildi (bu turda): izin kontrolü `canView('release_history')` sessizce false dönüyordu — anahtar TAM ad (`release_history.read`); `handleTabChange` extraParams temizlemeden SONRA yazılıyor.
- Aynı sekmedeyken çipten "Yenilikler"/"Dağıtım geçmişi": App `sm:tab-params` olayı yayar, HelpPage/SystemHealth dinler (ikinci turda kapatıldı; testli).
- **Docker imaj doğrulaması YAPILAMADI**: bu makinede Docker Desktop "unable to start" (WSL/Hyper-V). Dockerfile/compose/`build-image.ps1` düzenlendi, `helm template` doğrulandı; imaj içi `env | grep APP_` ve `/app/releases.json` kontrolü CI'daki ilk Release koşumunda ya da Docker'ı olan bir makinede yapılmalı.

## 7. Aynı oturumda çözülen bağımsız bulgular (çalışma ağacında)

1. Page Integrity: SsrfGuard politika reddi / 3P çözülemeyen host → BLOCKED (Belirsiz), Broken değil (tiktok vakası).
2. Page Integrity hız: zaman aşımında retry yok + 3P linkte HEAD timeout sonrası GET yok (16 s → 4 s / timeout); öneri: izleme "Kaynak eşzamanlılığı" 5 → 15-20.
3. Dokuz izleme modalı: sabit başlık + kaydırılan gövde + sabit alt bar + "Devamı için kaydırın" (`useModalScrollHint`, `modal-sticky-actions`; kapı modalScroll.test.jsx).
4. Takım Müdürü tek kişi (`utils/teamManager.js`) + Edit Team'de elle müdür (`teams.manager_id`, "(elle)").
5. LDAP müdür sicili: özyinelemeli müdür kaydı da sicil alır; ea4 DN değilse manager DN'ine düşülür; elle sicil `manager_id` bağı kurar.
6. Üye kartından açılan Kullanıcı düzenleme modalı üstte (portal + `modal-overlay--top`).
7. My Activity "Webhook push istemiyorum": istemci yolu `/api/auth/me/…` → `/api/me/push-opt-out` (404 idi).
8. Kullanıcı yönetimi tablosu 11 → 7 sütun (kimlik tek hücrede), Eylemler daralmaz; 1366 px'te taşma yok.
9. My Activity webhook opt-out satırı ayar kartına dönüştü (ikon · başlık/açıklama · switch).
10. Kişi webhook ayarları: "Son 24 saat / Son 7 gün" KPI kartları tıklanabilir → teslimat günlüğü pencere (+durum) süzgeci, çip ile kaldırma.
11. Tanılama yetkisi: TEAM_ADMIN ve USER artık `diagnostics.run` alıyor (politika yükseltmesi mevcut kurulumlarda da açar); uç `requireAdminOrMonitoredDomain` hedefi KENDİ takımının envanter kaydına daraltır, `proxy-ca-chain` admin'de kalır.
12. Kişi webhook "Kim alır?" paneli: takım + seviye seçilince her üye için karar ve gerekçesi (alır / grup eşleşmedi / seviye altı / kişi kapattı / pasif / kullanıcı adı yok / üyelik kaydı yok). Kullanıcı listesinde opt-out yapanlar çan-kapalı rozetiyle işaretli.
13. Teslimat günlüğü: test gönderiminden sonra kendiliğinden tazelenir (0/1,5/4 sn sessiz poll — PENDING → SENT/FAILED akışı görünür) ve her satırda kişinin takım rozeti var.
14. SQL Playground: tablo "oluşturma ≈ ilk görülme / son veri değişimi" (`schema_table_registry`, heartbeat'te pg_stat sayaç farkı) + detay modalında Zaman & Aktivite (MAX(ts) 5 sn zaman aşımlı, boyut, canlı satır, sayaçlar).

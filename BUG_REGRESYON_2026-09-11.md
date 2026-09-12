# BUG REGRESYON — 2026-09-11 (sürüm öncesi, diff-odaklı: 142 dosya, 3.633 ekleme)

Kapsam: çalışma ağacındaki tüm değişiklik — Sürüm & Dağıtım Geçmişi (K1–K11, E1–E4) + 14 bağımsız
bulgu (Page Integrity, modal kaydırma, Takım Müdürü, LDAP müdür, push opt-out, "Kim alır?", SQL
Playground zaman/aktivite, tanılama yetkisi…). Yöntem: `/bug-regresyon` imzaları (S1–S17) ARA → ELE →
DOĞRULA; her aday kaynakta okundu.

## (A) Baseline re-check

Bu turda baseline dosyalarının BİRKAÇI değişti (önceki beş turda hiç değişmemişti), her biri ayrıca
doğrulandı:

| Alan | Dosya | Durum |
|---|---|---|
| S13 (Y6) izin kataloğu | `PermissionCatalog` | `release_history.read` VIEW ve `release_history.edit` EDIT **ayrı satır**; `diagnostics.run` PolicyUpgrade TEAM_ADMIN/USER `execute` — auditDefaults'a EDIT sızmıyor → **KAPALI ✓** |
| S16 (N1/N5) SQL salt-okunur | `SqlPlaygroundService` | Değişiklik yalnız `listTables`/`tableActivity` ekleri; kara-liste/LIMIT/`setMaxRows` yolu dokunulmadı; `MAX(col) FROM table` sorgusunda `validateIdentifier` hem kolona hem tabloya (satır 179/312) uygulanıyor, 5 sn sorgu zaman aşımı → **KAPALI ✓** |
| S2/S4 (Y8/O13) SSRF | `SsrfGuard`, `PageCheckerService` | Yalnız `UNRESOLVABLE_PREFIX` sabiti + timeout'ta retry'sız yol; `PageFetchCore` değişmedi, `SafeRedirect` hop döngüsü yerinde → **KAPALI ✓** |
| S1 (Y1–Y3/O9) IDOR | `AdminController#requireAdminOrMonitoredDomain` | Yetki genişletildi (TEAM_ADMIN/USER) ama uç **daraltıldı**: hedef `viewTeamIds(session)` kapsamındaki envanter kaydı olmalı; sahipsiz kayıt (teamId null) izinli — bilinçli, yorumda gerekçeli. `proxy-ca-chain` admin'de → **KAPALI ✓** |
| S9 tx | `DeploymentHistoryRepository` | Dört `@Modifying` metod da `@Transactional` + `int`; `AppUserRepository.findTeamMembershipsByUsernames` salt okuma → **KAPALI ✓** |
| S17 mail | `EmailNotificationService` (E2/E3) | Tüm değerler `newDeviceRow`/`escHtml` ile kaçırılıyor; Outlook-safe `td bgcolor` + solid hex → **KAPALI ✓** |

**REGRESYON YOK.**

## (B) Benzer-tarama — 1 bulgu (düzeltildi, aynı sürüme girdi)

### B1 — ORTA · CSV formül enjeksiyonu (CWE-1236) korumasız üç dışa aktarım — sınıf: S17'nin CSV kardeşi (2026-08-23 denetimi `Csv.cell` kararı)

- `backend/src/main/java/com/sitemonitor/controller/DeploymentHistoryController.java:219` — **yeni** `/api/admin/deployments/export`, yerel `csvRow` kopyası: tırnaklama var, `=`/`+`/`-`/`@` nötrlemesi YOK.
- `backend/src/main/java/com/sitemonitor/controller/RetentionAdminController.java:223` — kopyanın kaynağı (`/runs/export`, 2026-09-10'da girmiş), aynı eksik.
- `backend/src/main/java/com/sitemonitor/service/report/InventoryExportService.java:96` — `csvEscape`, aylık envanter raporu **mail eki**; `domain`, `purchasedBy`, `changeDescription` kullanıcı metni.

**Neden bug:** 2026-08-23 denetimi formül nötrlemesini `com.sitemonitor.util.Csv` ortak sınıfına taşımış ve
Javadoc'una "kural tek yerde durmazsa bir sonraki dışa aktarım yine korumasız yazılır" diye not düşmüştü;
tam bu oldu — Retention kendi kopyasını yazdı, yeni Dağıtım geçmişi onu kopyaladı, envanter eki hiç
geçirilmemişti. Senaryo: `release_history.edit` sahibi bir yönetici elle kayda `=HYPERLINK(...)` /
`=cmd|'/c calc'!A1` notu yazar; CSV'yi Excel'de açan diğer yönetici formülü çalıştırır. Envanter ekinde
aynı yol `changeDescription` üzerinden (envanter düzenleme yetkisi daha geniş). Önem ORTA: yazan taraf
yetkili kullanıcı, ama etki dosyayı açan makinede.

**Çözüm (uygulandı):** `Csv.row(Object...)` eklendi; üç yüzey de `Csv.row`/`Csv.cell`'e bağlandı, yerel
kopyalar silindi. Kapı: **`CsvExportGuardTest`** (kaynak tarayıcı) — (1) `text/csv` üreten her main dosya
`Csv.` kullanır (devreden `CertificateInventoryReportService` gerekçeli muaf, muafiyet bayatlığı da test
ediliyor), (2) `replace("\"", "\"\"")` elle tırnak ikilemesi yalnız `util/Csv.java`'da olabilir.
Kapı düzeltmeden ÖNCE koşuldu: 2/4 kırmızı (iki kural da üç dosyayı yakaladı); düzeltme sonrası 4/4 yeşil.
Sözleşme testleri: `DeploymentHistoryControllerTest.export_neutralisesFormulaPrefix` (`=1+1` → `'=1+1`),
`InventoryExportServiceTest.csvEscaping` (+2 iddia). Etkilenen 6 sınıf tek koşumda yeşil.

Yan etki: `Csv.cell` `;` içeren hücreyi de tırnaklar (Retention `items` hücresi birden çok kalemde artık
tırnaklı — Excel açılışı için doğru davranış; tek kalemli mevcut test değişmedi).

## Temiz sınıflar (imza tarandı, başka örnek yok)

- **S1** — `DeploymentHistoryController` (`requireRead`/`requireEdit` + `requireNotScopedAdmin`), `SystemInfoController` (kimlikli herkes, WebConfig no-store), `UserPushController#explain` (`requireAdmin`), `TeamDirectoryController` (beyaz liste + `manager_id`).
- **S3** — yeni giden HTTP yüzeyi yok; `PageFetchCore` değişmedi.
- **S5** — `DeploymentHistoryService` gecikme/uptime hesapları `long` saniye; bölme yok.
- **S6** — `DeploymentHistoryPanel` 1-tabanlı (`useState(1)`, `page-1` API'de, `rangeStart=(page-1)*size+1`); `UserPushSettings` `page+1`/`p-1` deseni korunmuş; `ReleaseNotesPanel` 1-tabanlı.
- **S7** — `VersionTimeline` `renderMeta`/`eventStyles` geriye uyumlu; `ts` dış veriden değil, indeks tarihi (`date`) string.
- **S8** — `deployment_history` yeni tablo (ddl-auto create) + `uq_deploy_audit_ref` kısmi unique index **açık patch**; `teams.manager_id` nullable (ddl-auto ekler, entity yorumunda gerekçe); `schema_table_registry` kendi `CREATE TABLE IF NOT EXISTS` DDL'i; RetentionCatalog'da ikisi de BOUNDED (RetentionCoverage kapısı yeşil).
- **S10** — yeni entity'lerde Boolean alan yok; `Boolean.TRUE.equals` deseni Retention/Team'de korunmuş.
- **S11** — `DeploymentHistoryPanel`/`VersionPopover`/`ReleaseNotesPanel` `alive`/`seq` korumalı; `UserPushSettings` poll zinciri `pollRef` ile unmount'ta temizleniyor, "sessiz" tazeleme listeyi boşaltmıyor; `UserManager` diff'inde yeni fetch yok.
- **S12** — `DeploymentHistoryPanel` URL'den okunan state `useUrlQuerySync` ile tek yönlü; prop'tan türeyen state eklenmedi.
- **S14** — `UserPushRecipientResolver.explain()` ile `resolve()` aynı testte pinli (`explain_listsEveryMemberWithDecision`, satır 205-206).
- **S15** — `DeploymentNotifyService` mail-only (`EmailNotificationService` üzerinden, outbox yok); push paritesi bilinçli atlandı (Javadoc).
- **S16** — `tableActivity` yalnız `tableDetails` içinden, tablo adı iki kez `validateIdentifier`.

## Sonuç

Regresyon yok. Bir benzer-bug sınıfı (CSV formül enjeksiyonu, 3 yüzey) bulundu, düzeltildi ve kaynak
düzeyinde kapıya bağlandı. **Sürüme engel yok.**

## Ek — ikinci tur (aynı gün, `v20.54.0..HEAD`: ISSUE-001 planlı yenileme onayı)

Diff 12 dosya (2 commit). İmza süpürmesi: **S8** — `LatestCheck` 3 yeni alan ↔ `applySchemaPatches` 3 açık
`ADD COLUMN` (nullable; adlar birebir); **S1** — yeni `POST …/health/confirm-renewal` mevcut
`requireViewableForHealth` kapısını kullanır (görüş alanı dışı 404 + güvenlik olayı, testli), sunulan ≠
sabitlenen 409; **S11** — `confirmRenewal()` `refresh()` ile aynı `seq` yarış koruması; **S13/S9/S16/S17** —
dokunulmadı. `cleanupInterceptedCertPins` onay kolonlarına dokunmuyor — onay parmak izine bağlı olduğu için
bayat onay yeni pini kapsamaz (`staleConfirmationDoesNotCoverNewChange`). Baseline dosyaları değişmedi →
**REGRESYON YOK**, benzer bulgu yok. Sürüme engel yok.

## Ek — üçüncü tur (aynı gün, `v20.54.1..HEAD`: tarayıcı QA'sı ISSUE-002/003)

Diff 13 dosya, yalnız frontend (2 commit). Sınıf: **S11/S12** dokunulmadı (yeni fetch/state yok); **TeamBadge
sınıfı** (buton içinde etkileşimli kontrol) — `CopyButton as="span"` ile aynı desen, başka `<button>` içinde
`<CopyButton>` kullanımı taranıp bulunmadı; `<ModalScrollHint {...}>` yayılımı 9/9 kapatıldı, kapı
`modalScroll.test.jsx`. Baseline dosyaları değişmedi → **REGRESYON YOK**, benzer bulgu yok. Sürüme engel yok.

## Ek — dördüncü tur (aynı gün, `v20.54.2..HEAD`: teknik borçlar)

Diff 6 dosya (3 commit): `ExtendedHealthService#getHeartbeatTimeline` (S5 sayısal — `ceil` + son kova
`expected` = kalan dakika, ≥1; sıfıra bölme yok, `bucketMinutes` sabit 10/60), `IdentityLeakGuardTest`
SKIP_DIRS (+`.gstack`, kapı zayıflamaz — gitignore'lu dizin), `start-local.ps1`/`.env.example` (yerel
geliştirme; prod'a etkisi yok), CHANGELOG. Baseline dosyaları değişmedi → **REGRESYON YOK**. Sürüme engel yok.

## Ek — beşinci tur (aynı gün, `v20.54.3..HEAD`: push alıcı kuralı + eskalasyon push seviyesi)

Diff 7 dosya (3 commit). **S14 — GERÇEK BULGU (prod bildirim geçmişinden, düzeltildi):** `EscalationService`
eskalasyon dalı `setAlertLevel(HIGH)` sonrası gönderimi tetikleyip `save`'i gönderimden SONRAYA bırakıyordu;
`UserPushService.enqueueAlert` olayı DB'den yeniden yüklediği (processResults transactional değil) için
push ESKİ seviyeyle çözülüyor → "nobody qualifies at this level" (prod 07/09: e-posta HIGH gitti, push
atlandı, 13 sa sonra elle gönderim 5 kişiye ulaştı). Terfi artık INITIAL dalıyla aynı sırada: save → send.
Diğer send dalları (re-alert, manuel, resolve) seviye değiştirmiyor → imza yalnız burada. Kapı:
`processResults_levelEscalation_persistsLevelBeforePush` (InOrder save→enqueueAlert + seviye HIGH).
**Alıcı kuralı:** `UserPushRecipientResolver` unvanı okumuyor (yalnız `explain()` çıktısında bilgi amaçlı);
eski `source:"title"` kaydı `LEGACY_ROLE_SETS` ile çevriliyor (testli); `explain()` ile `resolve()` aynı
kurallarda pinli. S1/S9/S16/S17 dokunulmadı. Baseline dosyaları: `EscalationService` (Y4/Y5 alanı) değişti —
Y4/Y5 imzaları (`teamOnlyRecipients` tek kaynak, storm↔bireysel) dokunulmadı → **REGRESYON YOK**.

## Ek — altıncı tur (aynı gün, `v20.55.0..HEAD`: beş sabit kademe kartı)

Diff 5 dosya (1 commit): `UserPushRecipientResolver` (TIER_ROLES sabitleme + eksik kademe kapalı ekleme —
alıcı kümesini yalnız DARALTIR/sabitler, genişletmez; `explain()`/`resolve()` aynı `groupRules()`), UI kart
(çip → rozet; kaydedilen JSON tek rol), i18n, testler. S1/S9/S13/S16/S17 dokunulmadı; baseline dosyaları
değişmedi → **REGRESYON YOK**. Tarayıcı QA'sı (ikinci tur) bulgusuz. Sürüme engel yok.

## Ek — yedinci tur (aynı gün, `d18199be..HEAD`: KPI modalı + açılır bölümler — yalnız frontend)

Diff 4 dosya (2 commit): `UserPushSettings.jsx` (WindowModal: `alive` bayrağıyla yarış koruması, ModalShell
üzerinden Escape/odak; SectionHead `<button aria-expanded>` — HelpTip düğmesi başlığın DIŞINDA, iç içe button
yok; localStorage `try/catch`), CSS (kapı `cssClasses` yeşil), i18n TR/EN parite yeşil, testler. S11 (yarış)
ve TeamBadge sınıfı (buton içinde buton) tarandı → temiz. Backend değişmedi → **REGRESYON YOK**.

## Ek — sekizinci tur (aynı gün, `v20.56.0..HEAD`: bölümler varsayılan kapalı)

Diff 2 dosya (1 commit, yalnız frontend): `isOpen` varsayılanı `=== true`; kapalı bölüm içeriği yalnız CSS ile
gizli (DOM'da kalır → form değerleri/kaydet etkilenmez). S11/S12 dokunulmadı → **REGRESYON YOK**.

## Ek — dokuzuncu tur (2026-09-12, `v20.57.0..HEAD`: teslimat satırı yerleşimi)

Diff 2 dosya (1 commit, yalnız CSS + modal boyutu). Kapılar `cssClasses`/`cssTokens` yeşil (yeni sınıf `.modal-shell--xl`
tanımlı, `@container` sorgusu). Davranış/veri değişikliği yok → **REGRESYON YOK**.

## Ek — onuncu tur (2026-09-12, `v20.57.1..HEAD`: alan başına kontrol sıklığı + kayıt şeridi + sağlık notu)

**Alan başına kontrol sıklığı** (`check_interval_hours` 1/6/12/24/168, NULL = genel saatlik): entity + idempotent
`ALTER TABLE ... ADD COLUMN` (nullable — dolu tabloda NOT NULL sessizce düşerdi), `AdminController.normalizeInterval`
beyaz liste (başka değer → NULL), `SchedulerService.dueForScheduledSweep` (5 dk tolerans, bozuk/boş tarih → girer).
**Bulgu (denetimde yakalandı, düzeltildi):** stale süpürmesi (5 dk, 65 dk'dır kontrolsüz alanlar) aynı süzgeci
uygulamıyordu → saatlik süpürmenin atladığı GÜNLÜK alanı 5 dk sonra yakalar, ayar hiç çalışmazdı. Stale süpürmesi
artık `dueForScheduledSweep` üzerinden; satırlar `loadDomainsFromInventory`'den gelir (alan başına `timeout_seconds`
de artık stale süpürmesinde taşınıyor — önceden düşüyordu). Elle "Şimdi kontrol et" / `runCheck()` süzgeçsiz.
Sağlık ucu `next_check_at` alan başına (`nextCertificateSweepAt(domain, hours)`), panelde sıklık etiketi.
Kapı `change-field-labels-sync` `chg.field.checkIntervalHours` eksikliğini yakaladı → TR/EN eklendi.
Testler: SchedulerServiceTest +6 (89), AdminControllerTest +3 (136), CertificateControllerTest stub güncel (47),
InventoryFormActions +2, CertHealthPanel 22 yeşil.

**Kayıt şeridi** (kullanıcı: "kaydet butonunu göremiyorum"): yalnız-değişince-beliren şerit keşfedilemiyordu → HER
ZAMAN görünür; temiz durumda "Tüm değişiklikler kaydedildi" + pasif Kaydet, değişince vurgulu çerçeve + Geri al +
etkin Kaydet. `cssClasses`/`cssTokens`/i18n parite yeşil.

**Sistem Sağlığı notu**: "↻ 30 sn'de bir yenilenir" Sürüm & Dağıtım bölümünün üstünde sahipsiz kalıyordu → en alta;
DOM sırası testle pinli. Frontend kapı üçlüsü yeşil (0 lint hatası, 1890 test, taban %40) → **REGRESYON YOK**.

## Ek — on birinci tur (2026-09-12, Zayıf Algoritma Raporu zenginleştirme — 10 madde)

Yeni `WeakAlgorithmReportService` (eski `data/total/critical/high` sözleşmesi AYNEN korunur; `AuditControllerTest`
delege testi pinler). Eklenen bölümler: scan / rules / distribution / outlook(2030, RSA≥3072) / tls / chain / teams /
trend(30 gün, yalnız zayıf satırlar sorgulanır — tüm zaman serisi taranmaz) / exceptions. Hükümler
`CertificateHealthRules`'a devredilir; kural kataloğu şiddetleri `classifyWeakness` ile birebir (SHA-1 HIGH, RSA ≤1024
CRITICAL / 1025–2047 HIGH, EC <192 CRITICAL / 192–255 HIGH) — denetimde ilk taslak SHA-1'i CRITICAL yazmıştı, düzeltildi.
Yeni tablo `weak_algo_exception` (yeni tablo → NOT NULL tuzağı yok; UNIQUE domain). Yeni izin `weak_algo.manage`
(ayrı satır — auditDefaults deseni; TEAM_ADMIN'e açık, USER'a kapalı), yeni denetim olayları `WEAK_ALGO_*` (kategori
CERTIFICATE, `_EXPORT` soneki kuralından ÖNCE). "Takıma bildir" = e-posta (`sendAlert`, Outlook-güvenli şablon) +
push (`enqueueTeamNotice`, olaysız, takım+CRITICAL alıcı çözümü, gün bazlı dedupe) — kanal paritesi; günlük
tetik süzgecine `WEAK_ALGO` eklendi. CSV `Csv.row` (CsvExportGuard yeşil). Haftalık e-posta bandı: 8. parametre
`WeakAlgoWeekly` (7-arg aşırı yük korunur; `WeeklyAvailabilityReportService` depo ile hesaplar — servis enjekte
edilmez, dairesel referans). Kapılar yakaladı: `permission-labels-sync` (perm.res.weak_algo.manage), `progress-guard`
(elle yüzde çubuğu → `ProgressBar`), `cssTokens` (`--card-bg` hayalet yedek). **EOL tuzağı:** Git Bash `sed -i` App.css
ve i18n'i LF'ye çevirdi → CRLF geri yazıldı, commit öncesi EOL karşılaştırma script'i koşuldu (hafızaya işlendi).
Testler: WeakAlgorithmReportServiceTest 10, AuditControllerTest 16, UserPushServiceTest 44, WeeklyAvailability 44,
EmailNotification 62, PermissionCatalog/AuditEventCatalog/IdentityLeak/CsvExport/RepositoryWriteTx yeşil; frontend
0 lint hatası, 1897 test, taban %40 → **REGRESYON YOK**.

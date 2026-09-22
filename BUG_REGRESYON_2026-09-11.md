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

## Ek — on ikinci tur (2026-09-12, haftalık rapor sayfalama + son giriş ayarı + 2030 görünümü)

**Sayfalama:** yalnız render'ı böler (`usePagination`, InventoryManager deseni; liste yıl+takım ile sınırlı, takvim
işaretleri tüm listeyi ister) — "tümünü seç" filtrelenmiş liste üzerinde kalır; 53 satır → 50+3 testle pinli.
**Son giriş:** iki canlı ayar (`deadline-day` ENUM MON…SUN, `deadline-time` HH:mm), `WeeklyReportDeadline.parse` bozuk
değerde sessizce varsayılana düşer (`valid=false`). Hatırlatma cron'u varsayılanı `FRI` → HER GÜN 09:00; gün kararı
serviste (`sendFridayReminders(true)` yalnız son giriş gününde) — **prod `WR_REMINDER_CRON` override'ı varsa** son giriş
günü değişince onun da güncellenmesi gerekir (values drift notu). E-posta metni ayardan ("bugün saat 17:30" / "Perşembe
saat 17:30"); 3-arg aşırı yük korunur. Sayfa `GET /weekly-reports/deadline` ile okur; yükleme async+try/catch
(ilk taslakta `.then` zinciri eski test süitini düşürdü — düzeltildi). Kapı `bilingual-fields-lang` `day_name_en`
alanını iki-dilli token sandı → `day_tr/day_en` olarak adlandırıldı (yalancı pozitif ama sözleşme değişikliği ucuz).
**2030 görünümü:** her alan için beklenen eylem (`reissue` = sertifika 2030 sonunu aşıyor → erken yeniden düzenleme,
`renew` = yenilemede büyüt, `unknown` = önce kontrol), en geç tarih, hedef anahtar; özet (filo payı, eylem/takım
kırılımı, sunset'e kalan gün); UI'da risk bandı + "alan sahibinden beklenen" 5 adım + tablo.
Testler: WeeklyReportDeadlineTest 2, WeeklyReportReminderServiceTest 6, WeeklyReportControllerTest 26,
WeakAlgorithmReportServiceTest 11; frontend 1900 test, 0 lint hatası, taban %40 → **REGRESYON YOK**.

## Ek — on üçüncü tur (2026-09-12, 25 maddelik zenginleştirme — `v20.57.1..HEAD`, 31 commit)

Kapsam: kart mini trend + son-5 nokta (#4/#14), kart bilgisi (#6), Incidents PaginationBar (#17), yapılandırma
sağlığı kartı (#25), komut paleti (#1), "Sizin için — bugün" (#3), SLA satırı + `sla.target-pct` ayarı (#11),
yönetici özeti (#20), takım tamamlama panosu (#21), bildirim kutusu (#2), aylık takvim + ICS + bakım rozeti
(#8/#19), kesinti zaman çizelgesi (#12), gürültü analizi (#18), "ne değişti" satırı (#7), yenileme yükü (#9),
sütun seçici (#10), toplu işlem (#13), sayfa hızı bütçe çizgisi (#15), "neden hâlâ açık" (#16), aktivite
gruplama (#22), grafik eşikleri (#23), bağlama duyarlı yardım (#24), gerçek boş durumlar (#5).

**Denetim odakları ve sonuç:**
- Yeni uçların tamamı takım kapsamlı: `SessionScope.canView` predicate'i servise verilir (sparklines/sla/search/
  today/inbox/executive/changes/noise); config-health `settings.general` + `requireNotScopedAdmin`. Controller
  testleri takım 9'un monitörünü dışarıda tutuyor (IDOR).
- Natif SQL yalnız `MonitorSparklineService` ve `GlobalSearchService`'te; ikisi de H2 (Postgres kipi) üstünde gerçek
  motorla test edildi; LIKE parametreli, `%`/`_` kaçırılır. Kova sorguları ham satır taşımaz.
- Toplu işlem (#13) sunucuya yeni uç eklemedi — satır başına mevcut PUT/DELETE; yetki/denetim/alarm kapatma aynı.
- Yeni bağımlılık zinciri kırılganlıkları: `ExecutiveStatsService` ve `CertificateController`'a yeni alan → WebMvc
  testlerine `@MockitoBean` eklendi; `WeeklyAvailabilityReportService` yine servis enjekte ETMEZ (dairesel referans).
- Kapıların yakaladıkları: `cssClasses` (3 hayalet sınıf), `cssTokens` (1), `bilingual-fields-lang` (day_name_en),
  `progress-guard`, `permission-labels-sync`; hepsi kapatıldı. Heredoc kaçış çöküşü (ics.js) Write aracıyla yeniden
  yazıldı. Karışık EOL iki kez yakalandı (StatsView, DnsMonitorPage.test) → CRLF'e normalize edildi.
- Testler: frontend 1939 (224 dosya) yeşil, 0 lint hatası, taban %40; backend yeni sınıflar için 14 test sınıfı
  (Sparkline/Search/ConfigHealth/Today/Inbox/Executive/Noise/WeeklyCompletion) + etkilenen suit'ler yeşil; tam
  `clean verify` sürüm öncesi koşuldu.
Bilinen sınırlar (bilinçli): SLA hedefi filo geneli (takım başına ertelendi); grafik eşikleri sabit; palet arama
LIKE (tam metin değil); ICS yalnız istemcide üretilir. → **REGRESYON YOK**.

## Ek — on dördüncü tur (2026-09-12, tarayıcı QA turu `/qa` — `v20.58.0..HEAD`, yalnız frontend)

Kapsam: uçtan uca headless tarayıcı QA'sı (34 sayfa, İngilizce arayüz + Türkçe örneklem, karanlık tema, 375 px);
13 bulgu (4 orta, 9 düşük; kritik/yüksek yok, konsol hatası 0, kırık bağlantı 0), rapor
`.gstack/qa-reports/qa-report-localhost-2026-09-12.md`. Ayrıca "Sizin için — bugün" varsayılan kapalı (e0f678ba).

**Bulgular ve düzeltme deseni:**
- ISSUE-004 (orta, işlevsel): takvim/ICS `not_after.slice(0,10)` ile **UTC gününü** alıyordu; liste yerel günü gösterir
  (24/10 02:59 İstanbul = `2026-10-23T23:59:59Z`) → aynı sertifika listede 24/10, takvimde 23/10, .ics'te bir gün
  erken. Tek yardımcı `localDayKey` (`api/client.js`, `toUtc` ile aynı sözleşme); `MonthCalendar` kovalama, `ics.js`
  DTSTART, `RenewalAdvice` uid, `MaintenanceWindowsPage` olayı bu yardımcıyı kullanır. Test beklentisi saat dilimine
  bağlı DEĞİL (CI UTC / yerel İstanbul tuzağı): `localDayKey(x)` == `formatDateOnly(x)` günü.
- ISSUE-008 (orta, işlevsel): Zayıf Algoritma KPI'ı yalnız **zayıf bulguya bağlı** istisnaları sayıyordu (`excepted`),
  bölüm ise tüm kayıtları → sayfa kendiyle çelişiyordu. KPI = kayıtlı istisna (bölümle aynı), alt satır = bulguya
  bağlı adet. Backend sözleşmesi değişmedi (`excepted` haftalık e-postada kullanılmaya devam eder).
- ISSUE-002 (orta, içerik): yenileme tavsiyesi mesaj/eylemi sunucuda yalnız TR üretilir; arayüz `code` + gün sayısıyla
  çevirir, bilinmeyen kodda sunucu metni kalır (geriye uyumlu). Backend/e-posta metni değişmedi.
- ISSUE-007 (orta, görsel): gürültü paneli `auto-fit minmax(320px)` üç sütuna bölünüp tablo komşuya taşıyordu →
  `3fr/2fr` + `overflow-x:auto`, ≤1100px tek sütun.
- Sınıf düzeltmesi (kardeş yüzey süpürmesi): `%${v}` Türkçe sırası 12 yüzeyde → `formatPercent` (dateLocale.js,
  `dateLocale()` aynasıyla dile bağlı). Türkçe birim/jeton sızıntıları (`sn/dk/sa`, `kırık/istek/yüklenemedi`,
  Türkçe ay adı, `Az/Çok`, `≥1 kez`, `1 problems`) sözlüğe taşındı; aktivite özeti jeton haritasıyla kenarda
  çevrilir (sunucu metni değişmedi → CSV/e-posta aynı).
- ISSUE-012 (düşük, UX): yapılandırma sağlığı "Aç" aynı bölümde hiçbir şey yapmıyor gibiydi → `onOpenSection(sec, key)`
  sözleşmesi (ikinci arg yalnız `general` satırlarında), `GeneralSettings` `data-setting-key` ile kaydır/odakla/vurgula.

**Denetim odakları ve sonuç:**
- Sunucu tarafı ve API sözleşmesi DEĞİŞMEDİ (git diff yalnız `frontend/`); yetki/kapsam yüzeyi yok.
- Kapılar: 0 lint hatası (70 uyarı ana dalla birebir aynı), i18n paritesi (her yeni anahtar TR+EN), cssClasses/cssTokens
  (yeni sınıf `.threshold-grid.is-focus-target` App.css'te), EOL kontrolü (CRLF korundu).
- Testlerde iki mock eksikliği yakalandı (`formatDate`/`formatDateOnly` `../api/client` mock'larında) ve
  `%25`→`25%` beklentileri dile duyarlı yapıldı; `KeywordMonitorPage` TR ifade beklentisi iki dile açıldı.
- Yeni testler: `localDayKey.test.jsx` (5), RenewalAdvice (+1), WeakAlgorithmReport (KPI assert), ConfigHealthCard
  (tekil çip + 2-arg sözleşme).
Bilinen sınırlar (bilinçli): keyword `expect`/`trig` İngilizce "1 times" biçimi (tekil/çoğul ayrımı yok); ISSUE-013
düzeltmesi yalnız tarayıcıda doğrulandı (jsdom yerleşim yapmaz). → **REGRESYON YOK**.

**CI kırmızısı (sürüm sırasında):** `UserPushServiceTest.enqueueTeamNotice_writesRowsWithDedupe` runner'da düştü
(yerelde iki kez yeşil). Kök neden flake değil yarış: ilk çağrı satır kuyruklayınca outbox worker iş parçacığı hemen
koşup `appSettings` mock'unu çağırıyor; test iş parçacığı aynı anda `when(...)` ile yeniden stub'lıyor — Mockito
stubbing iş parçacığı güvenli değil, yeni stub kayboluyor (`reason=null`). Kapalı dal ayrı teste alındı
(`enqueueTeamNotice_disabled`, worker hiç başlamıyor). Desen notu: worker'lı serviste satır kuyrukladıktan SONRA
mock'u yeniden stub'lama.

## Ek — on beşinci tur (2026-09-12, Domain Envanteri zenginleştirme — 15 madde, `v20.58.1..HEAD`)

Kapsam: arama+süzgeç (#1), hijyen bandı (#2), canlı sertifika sütunları (#3), sütun seçici+sıralama (#4),
bayrak/sorumlu ikonları (#5), CSV içe aktarma (#6), takıma göre görünüm (#7), çekmece + satır-içi düzenleme (#8),
alan adı bitişi (#9), çöp kutusu künyesi + otomatik purge ayarı (#10), şimdi kontrol et (#11), çakışma sezgisi (#12),
kayıtlı görünüm + bağlantı (#13), etiket çipleri (#14), yoğunluk/mobil (#15). Ayrıca "Bildirim grubu" çift etiket
düzeltmesi (85240399).

**Denetim odakları ve sonuç:**
- Yeni uçlar: `GET /admin/inventory/hygiene` (liste ile aynı kapsam; `inventory.crud` edit → USER'a kapalı),
  `POST /admin/inventory/import` (`dry_run` VARSAYILAN true; satır başına `SessionScope.canManage`; silinmiş
  kayıt/dosya içi tekrar/kapsam dışı atlanır, batch durmaz; `DOMAIN_IMPORT` denetimi + satır başına geçmiş).
  AdminController'a bağımlılık eklenmedi (ayrı `InventoryInsightController`, 5 WebMvc testi).
- Kuru koşu işlem DIŞINDA koşar (`plan()` @Transactional değil): open-in-view kapalı → `findByDomain` kopuk
  varlık döner, deneme mutasyonları flush edilmez. Aynı gövde @Transactional olsaydı "kuru" koşu sessizce
  yazardı — servis testinde `save` hiç çağrılmıyor diye pinli.
- `listInventory` `latest_checks` ile zenginleşti (tek ek sorgu, `@Transient cert_*` alanları); satır-içi
  düzenleme tam gövde + yama gönderir ve `cert_*`/`team_name` alanlarını gövdeden eler (Jackson bilinmeyen
  alanı yok sayar ama sözleşme temiz kalsın).
- Otomatik purge: retention kataloğuna ham DELETE politikası OLARAK EKLENMEDİ (checks/notes öksüz kalırdı);
  ayrı servis elle purge ile aynı zinciri koşar, `DOMAIN_AUTO_PURGE` sistem denetimi, HA kilidi
  (`tryAcquireSchedulerLock` public yapıldı). `RetentionCoverageTest` EXEMPT girdisi (soft delete) korunur.
  Yumuşak silme artık aktörü damgalar (`stampUpdated`) → çöp kutusunda "kim sildi".
- Hijyen bulgularına makine kodu eklendi (`Finding.codes`); e-posta metni değişmedi (`InventoryHygieneServiceTest`
  +2). `analyze(rows, cap)` — sayfa tavansız ister.
- Frontend saf model (`inventoryModel.js`, 7 test): süzgeç/sıralama/çakışma/CSV ayrıştırma (RFC-4180'e yakın,
  `;` otomatik, BOM, yerelleştirilmiş başlık eşlemesi). Bileşen testleri `InventoryEnrichment.test.jsx` (10).
- Tarayıcı doğrulaması: hijyen çipi → `i_hy` URL'de, süzgeç paneli, çekmece Kontroller sekmesi (ilk denemede
  `renderRow` eksikti → ErrorBoundary; CertificateModal'ın satır çizicisi taşındı, LIR-2026-000033 bu
  denemeden), takım görünümü, içe aktarma kuru koşu (1 yeni / 1 değişiklik yok / 1 geçersiz — yazılmadı).
- Kapıların yakaladıkları: `cssClasses` (`.input-sm`, `.inv-table`), `settings-help-coverage` (EN başlık
  "Recommended:"), i18n çift anahtar (`inv.colUgTeam`), eski test seçicileri (satır artık iki checkbox
  taşıyor; alan adı düğme oldu).
- Kalıcı düzeltme: `teams: teamsProp = []` her render'da yeni dizi → prop-senkron efekti sonsuz döngü
  (teams verilmeden USER rolü); modül sabiti `NO_TEAMS`.
Bilinen sınırlar (bilinçli): süzgeçler istemci tarafı (liste zaten tam yükleniyor, ≤1000 satır); içe aktarma
tek istekte 5000 satır; çakışma sezgisi yalnız öneri; kayıtlı görünümler tarayıcıya özel (paylaşım = bağlantı).
→ **REGRESYON YOK**.

## Ek — on altıncı tur (2026-09-12, Vade Takvimi zenginleştirme — 14 madde)

Kapsam: dolmuş/erişilemeyen görünür (#1), takım/UG/tier/grup süzgeci (#2), renew-by + tier lead ayarı (#3),
tazelik/ortam/hata bandı (#4), alarm eşikleri (#5), eylemler + planlanan yenileme (#6), takıma göre dolacaklar (#7),
toplu iş/kapsama/veren (#8), ICS/CSV/bağlantı/yazdır (#9), zamanında yenileme oranı (#10), ay görünümü +
tatil/hafta sonu (#11), boş durumlar (#12), sözlük (#13), tek uç `/api/forecast` (#14).

**Denetim odakları ve sonuç:**
- Yeni uçlar `ForecastController` (AdminController/CertificateController'a dokunulmadı): `GET /api/forecast`
  (`inventory.list` view; `SessionScope.viewTeamIds` + satır bazlı `canView`), `POST/DELETE /api/forecast/{domain}/plan`
  (`inventory.crud` edit + `canManage`; yabancı takım 403, bilinmeyen domain 400 — `ForecastControllerTest` 3).
- Eski model **dolmuş ve hatalı** sertifikaları hiç göstermiyordu (`d < today` / `days<0` süzgeci) — bu sayfada
  "≤7 gün 0" yazarken dolmuş sertifika olabiliyordu. Yeni model `classify` ile overdue/unreachable ayrı kova.
- Eşikler sabit 7/14/30'dan `AlertThreshold`'a bağlandı (alarmla aynı; kayıt yoksa 30/15/7). Lead ayarları
  `AppSettingsCatalog` "monitoring" grubunda → `settings-labels-sync` + `settings-help-coverage` için TR/EN etiket ve
  3-satır yardım eklendi (EN başlık "Recommended:" kuralı bir kez ısırdı).
- Yenileme geçmişi DB'de toplanır (`GROUP BY domain, fingerprint` — ham satır taşınmaz); geçiş = yenileme, "zamanında"
  = önceki bitiş − tier lead'inden önce. Yerel veride 3 olay (1 zamanında, 2 geç) — sayfa gerçekten sinyal veriyor.
- Planlanan yenileme: `certificate_inventory` 4 NULLABLE kolon (ddl-auto sessiz NOT NULL tuzağı yok), geçmiş +
  `CERT_RENEWAL_PLANNED/_PLAN_CLEARED` denetimi; "done" durumu sunucuda türetilir (not_before ≥ plan tarihi).
- Saf model `forecastModel.js`: ilk sürümde `addDays` `toISOString().slice(0,10)` ile UTC gününe kayıyordu (İstanbul'da
  bir gün geri) — test yakaladı, yerel getter'larla düzeltildi. Tüm testler `today` sabitiyle TZ'den bağımsız (CI UTC).
- Tarayıcı doğrulaması: LOCAL etiketi, veri damgası, 6 KPI, 90 günde 6 satır + renew-by + hafta sonu düzeltmesi,
  plan modalı, takım tablosu (`unreachable` alanı eksik → NaN; düzeltildi ve `>30` sütunu eklendi).
- `PAGE_STATE_PREFIXES`'e `i_` (envanter) ve `f_` (takvim) eklendi — sekme değişince parametreler temizlenir.
- Kapılar: `cssClasses` (`.fc-intel-block`), `lazy-tabs-smoke` mock'una `localDayKey`; i18n paritesi tam.
Bilinen sınırlar (bilinçli): tatil tablosu 2026–2028 (dini bayramlar yıl bazlı), yenileme penceresi 90 gün sabit,
renew-by sunucuda UTC günü (istemci yerel güne çevirir), plan "done" yalnız not_before ile (parmak izi onayı ayrı).
→ **REGRESYON YOK**.

## Ek — on yedinci tur (2026-09-13, `/qa` 2. tur + sürüm öncesi regresyon süpürmesi — `v20.59.0..HEAD`)

Kapsam: Domain Envanteri + Vade Takvimi tarayıcı QA'sı (14 bulgu, 13 düzeltme, rapor `.gstack/qa-reports/qa-report-localhost-2026-09-12-r2.md`)
ve o düzeltmelerin diff'i üzerinde imza-güdümlü süpürme.

**On altıncı turun "bilinçli sınır" dediği madde YANLIŞTI:** *"renew-by sunucuda UTC günü (istemci yerel güne çevirir)"* —
istemci çıplak `YYYY-MM-DD` alanı `localDayKey`'den olduğu gibi geçirir, çevirmez. `23:59:59Z` biten her sertifikada
(DigiCert varsayılanı) "bitiş 24.10 · en geç 23.09" (30 gün lead için 31 gün) çıktı. Sunucu artık `ZoneId.systemDefault()`
(prod Europe/Istanbul) gününü üretir; `renewed_at` günü, plan-done günü ve ay kovası aynı dilimde. Surefire zaten
`-Duser.timezone=UTC` koştuğundan test beklentileri dilim-açık (`ZoneOffset.UTC` → 23.09, `Europe/Istanbul` → 24.09).

**Kardeş süpürme (aynı sınıf: UTC damgası → `.slice/substring(0,10)` ile çıplak gün):**
- `CheckHistoryTab.jsx:111` — gün ayraçları UTC günüyle; satırlar yerel saat basıyor → 00:00–03:00 kontrolleri önceki günün
  başlığında (her izleme geçmişi sekmesi + envanter çekmecesi). → `localDayKey`. Sabit fikstürler gece yarısı UTC'den
  öğlene taşındı (dilimden bağımsız), yeni `CheckHistoryTab.regression-1.test.jsx`. **DÜZELTİLDİ (147f016e).**
- `MaintenanceWindowsPage.jsx:236` — saat pencere diliminde, tarih UTC. → `dayInTz`. **DÜZELTİLDİ (aynı commit).**
- `WeekDatePicker` `toISOString().slice(0,10)` — GÜVENLİ (monthGrid UTC-öğlen tarihleri üretir).
- Backend `substring(0,10)` eşleşmeleri dosya adı / retention `DATE10` / IncidentService fallback — kullanıcıya gün olarak
  gösterilen yüzey değil; bırakıldı.
- `toUtc`/`localDayKey` `utils/localDay.js`'e taşındı, `api/client` yeniden dışa aktarır: istemci modülünü tümüyle
  mock'layan 16 sayfa testi bileşenleri bu yardımcılardan yoksun bırakıyordu (`No "localDayKey" export`).

**Diğer QA düzeltmeleri (kısa):** heat-map hatching `background` kısayolu ×2 (inline + `.fc-hm-zero !important`) ile
siliniyordu; plan tarihi takvimde çizilmiyordu (modal metni vaat ediyordu); `MonthCalendar` ay başlığı tarayıcı dilinden
(3 sayfa); 375 px'te KPI/05-06 satırı ve hijyen bandı; `i_sort` URL'de; takım aç/kapat `aria-label`; içe aktarma önizlemesi
yeni satırda `team`/`port`; zamanında-yenileme KPI süzgeci izler; boş durum metni; ay görünümü 12 ay.
Bilinen sınırlar: heat-map 375 px'te kartı ~35 px aşar (7 sütun min-content); `GET /api/issue-reports` 405 yerine 500
(API-only, ertelendi); süzgeçli zamanında oranı sunucunun 50'lik olay listesinden sayılır.
→ **REGRESYON YOK** (16. turun sınır varsayımı düzeltildi; kardeş 2 bulgu kapatıldı).

## Ek — on sekizinci tur (2026-09-13, Sistem Sağlığı → Kullanıcı / Oturum zenginleştirme — 14 madde)

Kapsam: `UserActivityService` + yeni `PageUsageService`, `SystemController` 2 yeni uç + sonlandırma gerekçesi;
`SystemHealth.jsx` içindeki 400 satırlık blok `components/admin/useractivity/` (panel, modallar, saf model) olarak ayrıldı.

**Denetim odakları ve sonuç:**
- Sayfa kullanımı (#1): oturum ping'i yalnız `tab` anahtarını taşır (beyaz liste `^[a-z0-9-]{1,40}$`; URL parametresi,
  alan adı, arama metni ASLA); `page_usage_daily` raw-DDL tablosu → `RetentionCoverageTest` RAW_DDL listesi + katalog AGE
  kuralı (90 g, min 7, PERSONAL) + ayar/yardım TR-EN + `docs/RETENTION_POLITIKASI.md` üretildi. Yazma update-then-insert
  (PG/H2 aynı); DB hatasında tampon korunur (`PageUsageServiceTest`).
- Anomali onayı (#3): `audit_log` append-only kalır; `login_anomaly_ack` ayrı tablo + `anomaly-ack-orphan` kuralı;
  `LOGIN_ANOMALY_ACK` denetim tipi (`AuditEventCatalogTest`). Uç `system_health.read` ile (AUDIT de onaylayabilir — salt
  okuma denetçisinin 'gördüm' damgası bilinçli).
- Sonlandırma (#10): kendi oturumunu kapatma 400; gerekçe denetim satırında. `SystemControllerTest` +3.
- **Tarayıcıda yakalanan gerçek kusur:** aktif oturum `login_at` oturumun EN SON denetim satırından geliyordu → onay
  yazınca 'Login zamanı' ileri kaydı, süre 0 dk oldu. Artık oturumun LOGIN satırı → herhangi satırı → son LOGIN sırası
  (`findTopByActorAndSessionIdAndEventTypeOrderByEventTimeDesc`), regresyon testi süreyi 49–51 dk arasında pinler.
- Gizlilik (#14): sicil yalnız global admin; User-Agent 'göster'e kadar gizli; e-posta zaten görünürdü (değişmedi).
- Erişilebilirlik (#13): satır tıklaması yerine hücre içi `Detay` düğmesi; sıralanabilir başlık `<button>` + `aria-sort`;
  takım satır başlığında `TeamBadge as="span"` (button-içinde-button — 2026-09-10 ISSUE-001 sınıfı, testte yakalandı).
- URL `u_*` öneki `PAGE_STATE_PREFIXES`'e eklendi (sekme değişince temizlenir). CSV `utils/csv.js` (`csvRows`).
- Isı haritası: mesai dışı `office_hours` payload'dan (AuditService ile aynı anahtarlar), SVG `<pattern>` taraması; başarısız
  hücre noktası. Bakım penceresi overlay'i YAPILMADI (yineleme kuralı takvim gününe ucuz çevrilemiyor — bilinçli sınır).
- Kapılar: cssClasses (`.uact-table--sessions` eksikti → eklendi), i18n paritesi 105 anahtar, settings-help (yeni
  retention anahtarı), coverage floor 63 dosya. Bir kez `lazy-tabs-smoke` 15 sn'de düştü, ikinci tam koşuda yeşil — süit
  yükünde bütçesinin sınırında; ayrıca izlenmeli.
Bilinen sınırlar: süzgeçli 'zamanında/takım' sayıları 7 günlük pencereye bağlı (sunucu tek payload); sayfa kullanımı
dakikası ping × 15 sn tahmini; bakım overlay'i yok; e-posta detay modalında görünür (mevcut davranış).
→ **REGRESYON YOK** (1 yeni kusur tarayıcıda bulundu ve aynı turda kapatıldı).

## Ek — on dokuzuncu tur (2026-09-13, sürüm öncesi — `/qa` Kullanıcı / Oturum paneli + regresyon süpürmesi, `v20.59.1..HEAD`)

Kapsam: 8 commit — panel (18. tur) + QA 2 bulgu. İmza süpürmesi (skip-ci belirteci, gerçek kimlik, UTC gün dilimlemesi,
button-içinde-button, elle kurulmuş modal): temiz — yeni modallar tümü `ModalShell`, rozetler `as="span"` ya da inert `UserBadge`.

**QA bulguları (rapor `.gstack/qa-reports/qa-report-localhost-2026-09-13.md`):**
- **ISSUE-002 (YÜKSEK, mevcut kusur):** `SchedulerService` açılışta `clearAllActiveSessions()` — 'in-memory oturum restart'ı
  yaşamaz' varsayımı; oysa prod `application-prod.properties` ve yerel launcher `spring.session.store-type=jdbc`. Her
  deploy sonrası içerideki kullanıcılar 'Aktif Oturum'dan düşüyor, tek-oturum süpersede koruması devre dışı kalıyordu.
  Ping artık işaret NULL ise oturumu yeniden sahiplenir (`adoptSessionIfNone`, `WHERE activeSessionId IS NULL` —
  canlı/TERMINATED işaret asla ezilmez). `RepositoryWriteTransactionGuardTest` pini + `UserServiceSessionAdoptTest` 3.
  Tarayıcıda restart sonrası yeniden giriş yapmadan '1 Aktif Oturum' doğrulandı.
- **ISSUE-001 (orta):** `u_*` süzgeçli paylaşılan bağlantı bölümü kapalı açıyordu → `?sec=users` ya da `u_*` bölümü açar,
  kopyalanan bağlantı `sec=users` taşır.
Bilinen sınırlar (bilinçli): restart anında tarayıcının ping'i 401 alırsa beni-hatırla ile YENİ oturum açılır (süre 0'dan
başlar — doğru); `clearAllActiveSessions` açılışta kalır (in-memory profil için hâlâ gerekli).
→ **REGRESYON YOK**.

## Ek — yirminci tur (2026-09-13, sürüm öncesi — iki `/qa` turu: tam süpürme + etkileşim akışları, `v20.60.0..HEAD`)

Kapsam: 5 QA commit'i (17 dosya). İmza süpürmesi: skip-ci belirteci yok, gerçek kimlik yok, `useEscapeKey` çağrısı 11 dosyada
da bileşen gövdesinde koşullu return'den ÖNCE (hook + erken-return tuzağı kontrol edildi: 0 return arada).

**QA bulguları (`qa-report-localhost-2026-09-13-full.md`, `-flows.md`):**
- Sertifika tabloları (Stats + Tüm Sertifikalar) 375 px'te kaydırma kabı yoktu → `.table-scroll`; akordiyon çubuğu etiketi
  küçülmüyordu → ≤640 px sarma. (görsel, orta/düşük)
- **11 detay modalı Escape ile kapanmıyordu** (9 izleme türü + Uptime + Sertifika; ModalShell'e taşınmamış eski modallar).
  Ortak `hooks/useEscapeKey`: yalnız açıkken dinler, üstte `.modal-overlay` (ModalShell YA DA elle kurulu düzenleme formu —
  form bilinçli olarak Escape/dış tıklamayla kapanmaz) varsa dokunmaz, `defaultPrevented` olayları yok sayar. İlk sürüm yalnız
  `.modal-shell-overlay`'e bakıyordu → düzenleme formu açıkken Escape ALTTAKİ detayı kapattı; tarayıcıda yakalandı, commit
  öncesi düzeltildi; `useEscapeKey.regression-1.test.jsx` katmanlamayı pinler.
- 'Yeni Monitor' (aksansız, port/keyword/ping) → 'Yeni Monitör'.
- Ertelenen: denetleyici hata metinleri EN arayüzde Türkçe (`SsrfGuard.UNRESOLVABLE_PREFIX` vb. — DB'de saklanıp
  `MonitoringOutageService` tarafından desenle eşleniyor; hata-kodu sözleşmesi ayrı iş).
Bilinen sınırlar: düzenleme formları Escape ile kapanmaz (bilinçli); headless QA'da 60 dk hareketsizlik çıkışı sentetik
tıklamayı saymaz (araç notu, uygulama davranışı doğru).
→ **REGRESYON YOK**.

## Ek — yirmi birinci tur (2026-09-13, sürüm öncesi — düğme boyu süpürmesi, `v20.60.1..HEAD`)

Kapsam: 2 commit (App.css 2 kural + CertRenewalGuide 1 satır). Kullanıcı bulgusu: Tüm Sertifikalar'da Sıfırla (45 px) ≠
Sütunlar (36 px). Örnek kapatılmadı, SINIF kapatıldı: 35 sekmede çalışma zamanı ölçümü (aynı flex satırındaki aynı boy
`.btn` kardeşleri, >2 px eşik) iki aile daha buldu — `.controls` satırı (36 vs input'a uzayan 39) ve dokuz izleme sayfasının
küçük düğme çubuğu (24/25/27: yalnız-ikon / ikon+metin / metin).

**Kök neden:** `.btn`/`.btn-sm` satır yüksekliği ve hizalama tanımlamıyordu; kutu içerik türüne göre değişiyordu. Altı
sayfa-yerel `.x .btn { inline-flex; gap }` kuralı aynı deseni tek tek yamalıyordu (kanıt: kök kuralın doğru olduğu).
**Düzeltme:** kök kural `inline-flex + center + gap + line-height 1.3 + min-height (em tabanlı)`; `.colpick` sarmalayıcı `flex`.

**Regresyon taraması (global `display` değişikliği riskli):**
- `.btn`/`.btn-sm` seçicili başka `display` / `width: 100%` / `text-align` / `flex-direction` kuralı yok (grep).
- `.btn` gövdesinde blok çocuk (`div/br/p/small/ul`) ya da `textAlign` inline stili olan düğme YOK (flex satırında yan yana
  dizilirdi) — 0 eşleşme, 105 jsx dosyası tarandı.
- İkon `marginRight` + `gap` çift boşluk: yalnız CertRenewalGuide (kaldırıldı).
- Kapılar: cssClasses/cssTokens + CertificatesTable/HttpMonitorPage/CertRenewalGuide yeşil; 33 sekmede tarama 'ok';
  görsel: HTTP başlık çubuğu, dashboard kontrolleri, düzenleme formu alt çubuğu (Test Et / İptal / Kaydet), Haftalık Raporlar.
Bilinen sınır: yalnız-ikon düğmeler artık metinli kardeşleriyle aynı boyda (24 → 27 px) — istenen davranış.
→ **REGRESYON YOK**.

## Ek — yirmi ikinci tur (2026-09-13, sürüm öncesi — Tüm Sertifikalar zenginleştirmesi, 16 madde, `v20.60.2..HEAD`)

Kapsam: 2 commit, 22 dosya (+2277/−342). İmza süpürmesi: skip-ci belirteci yok, gerçek kimlik yok (testler example.com /
"Takım A"), UTC gün dilimlemesi yok (göreli zaman `toMs` ile `Z` eki — api/client toUtc sözleşmesi), hook + erken-return:
CertificatesTable/CertTableToolbar/CertBulkBar'da tüm hook'lar koşullu return'lerin ÜSTÜNDE (CertBulkBar `if (domains.length
=== 0) return null` useEffect'ten sonra).

**Kapatılan kusurlar:**
- Tablo bayat kalıyordu: App'in 5 dk yenilemesi ve "Şimdi Kontrol Et" tabloya ulaşmıyordu → `refreshKey=lastUpdate` sessiz
  tazeleme + görünürken `useVisibleInterval`. Test: rerender'da ikinci istek, `.pg-block` çizilmez, satır güncellenir.
- Durum süzgeci ↔ satır hükmü ayrışması: sunucu süzgeci kendi merdivenini kuruyordu ("Kritik" süzgeci "Süresi doldu"
  satırlarını getiriyor, "valid" süzgeci "Yüksek" satırları sayıyordu) → `levelOf()` ile tek hüküm; "expired" seçeneği.

**Denetim odakları:**
- Takım kapsamı: `/certificates/list`, `/export.csv` `SessionScope.viewTeamIds` ile aynı listeden; `set-team` yalnız GLOBAL
  admin (`requireAdmin` + `inventory.transfer`), TEAM_ADMIN 403 pinli; `domains` çözümü bilinmeyen alanı atlar.
- Cache güvenliği: paylaşılan parmak izi sayımı cache'li DTO'ya YAZILMAZ (iki kapsamın isteği aynı nesneyi ezerdi) →
  ayrı `shared` haritası; sıralama/CSV oradan okur. Test: DTO'da alan yok, haritada yalnız >1.
- Sıralama beyaz listesi: bilinmeyen anahtar (`__proto__`) alan adına düşer — istemci dizesiyle yansıma yok.
- CSV: sunucu tarafı formül/virgül/tırnak kaçışı (`csvCell`), CERT_LIST_EXPORT satır sayısıyla; istemci seçim CSV'si
  `utils/csv.js` (csv-escape-guard). Katalog testi (`AuditEventCatalogTest`) yeni üç türü doğruladı.
- URL ad alanı: `c_` öneki PAGE_STATE_PREFIXES'e eklendi — sekme değişince temizlenir; `?domain=` (e-posta bağlantısı)
  alan süzgecine düşer, dashboard'daki `search` davranışı korunur.
- Kapılar: progress-guard elle yazılmış yüzde çubuğunu yakaladı (ct-life `width:%` → `ProgressBar` native `<progress>`,
  `.pg-bar--ok/warn/crit` tonları); cssClasses/cssTokens/i18n-parity/used-keys temiz (82 TR + 82 EN anahtar).
- Kademe atama doğrulaması: 0/5 → 400, verilmezse kademe kaldırılır (pinli).
- Testler: backend CertificateListQueryTest 8 + Controller +2 + Admin +3; frontend certTableModel 12, enrich 11, mevcut 7
  uyarlandı (veri sütunu sayımı seçim/işlem sütunlarını dışlar); tam süit 2014 yeşil, taban %40, build.
Bilinen sınırlar (bilinçli): ön ayarlar tarayıcıda; yapışkan başlık yok (kaydırmalı kap + sayfa kaydırması); toplu
"sustur" yok (alan bazlı susturma API'si yok); `expiring7` sunucuda takma ad olarak kaldı (eski bağlantılar).
→ **REGRESYON YOK**.

## Ek — yirmi üçüncü tur (2026-09-13, sürüm öncesi — `/qa` v20.61.0 üzerinde, `v20.61.0..HEAD`)

Kapsam: 3 QA commit'i. İmza süpürmesi temiz (skip-ci yok, gerçek kimlik yok, test kapısı yeni dosyada).

**QA bulguları (`.gstack/qa-reports/qa-report-localhost-2026-09-13-r2.md`):**
- **ISSUE-001 (YÜKSEK, mevcut kusur 2026-08-25'ten beri):** `/admin/inventory/bulk` ucunun `@CacheEvict`'i, `applyBulkContacts`
  javadoc'u araya girince ÖZEL yardımcıya kaymıştı; proxy özel çağrıyı sarmaz → toplu pasifleştirme/silme/kademe/takım
  sonrası cert-latest/warnings/stats/renewal-advice 5 dk bayat. Ek uç metoduna alındı; `CacheEvictPlacementRegressionTest`
  (cache eki yalnız public metotta + bulk uç dört cache'i boşaltır). Tarayıcıda liste ve envanter kademesi anında eşit.
- **ISSUE-002 (orta, görsel):** 10+ sütunda `%100` genişlik hücreleri kelime kelime kırıyordu → `.ct-table` doğal genişlik +
  `nowrap`, `.table-scroll` kaydırır; varsayılan 7 sütunda yerleşim değişmedi (mobil kart kuralı sonra geldiği için kazanır).
Doğrulama: 5 sekme, TR+EN, açık+koyu, 1280+375 px, 21 sütun; konsol hata farkı 0; 500'ler yalnız yeniden başlatma
sırasındaki Vite proxy yanıtları (5 bayt), sonrasında 200. → **REGRESYON YOK**.

## Ek — yirmi dördüncü tur (2026-09-13, sürüm öncesi — ürün turu, 12 madde, `v20.61.1..HEAD`)

Kapsam: 3 commit. İmza süpürmesi temiz (skip-ci yok, gerçek kimlik yok; test fixture'ları "testuser"/"newbie").
Hook + erken-return: `TourProvider` App'in auth kapısından SONRA (oturumlu ağaç, PermissionsProvider içinde) mount edilir,
gövdesinde koşullu return yok; `TourPageChip`/`OnboardingChecklist` hook'ları return'lerin üstünde.

**Denetim odakları:**
- Kalıcılık: `tour_state` yalnız kendi kaydına yazılır (push-opt-out deseni, IDOR yüzeyi yok); `dismissed` YAPIŞKAN —
  started/snoozed onu ezmez (TourStateServiceTest); istemci tamamlamış/kapatmış kullanıcıyı yeniden başlatınca
  'started'a düşürmez (tarayıcıda yakalandı, TourProvider testi pinledi). Yönetici sıfırlama takım kapsamlı
  (`requireTeamScopedAdmin`; TEAM_ADMIN başka takım → 403 pinli).
- Yan etki disiplini: persist çağrıları setState güncelleyicisinin DIŞINDA (StrictMode çift çağrı → çift POST olurdu).
- Hayalet hedef kapısı (`tour-targets.test.js`): her adımın `data-tour` hedefi bir JSX'te, TR/EN metni var, sekme
  kimlikleri geçerli — hedef silinirse test kırmızı (tur sessizce erimez).
- Çizim: karartma dört parça, delik boş (hedefe tıklanabilir); z-index 3000 (modal 1000–2100 üstü); balon yalnız
  `data-tour` ölçer, 200 ms + scroll/resize'da tazelenir; bulunamayan hedef 3 sn'de atlanır.
- Kapılar: progress-guard elle yüzde çubuğunu yakaladı → `ProgressBar`; cssClasses `.tour-welcome`'ı yakaladı →
  tanımlandı; i18n parity 103/103; DDL patch idempotent (JPA update önce oluşturdu, patch noop).
- Testler: backend TourStateServiceTest 7 + Auth +3 + Admin +1; frontend tourEngine 9, TourProvider 10, tour-targets 4;
  tam süitler yeşil (backend clean verify aşağıda, frontend 2037).
Bilinen sınırlar (bilinçli): whitepaper'da tur bölümü yok (kılavuz + PDF ayrı iş); "Yenilikler" turu TOUR_VERSION
elle artırılınca devreye girer; mobilde tur kısaltılmış (palet adımı yok); ön ayarlar gibi tur da tek sürüm
(çoklu dil metni i18n'den).
→ **REGRESYON YOK**.

## Ek — yirmi beşinci tur (2026-09-13, sürüm öncesi — kılavuz §14.30 Ürün Turu, `v20.62.0..HEAD`)

Kapsam: 1 commit (kılavuz TR/EN + kök kopyalar + PDF'ler + manifest + tur adımlarında help bağlantısı). Kod değişikliği yok.
Kapılar: `whitepaper-sync` (kök = kaynak), `whitepaper-pdf-freshness` (manifest sha'ları güncel), `tour-targets` yeşil;
yardım çekmecesinde §14.30 açılıyor (3024 karakter, yönetici paragrafı dâhil). → **REGRESYON YOK**.

## Ek — yirmi altıncı tur (2026-09-13, sürüm öncesi — Haftalık Raporlar zenginleştirmesi, `v20.62.1..HEAD`)

Kapsam: 2 commit. İmza süpürmesi temiz (yeni testler example/SY-A takma adlarıyla; mevcut DEFAULT_TEMPLATE_JSON'daki kanal
adlarına dokunulmadı). Hook + erken-return: WeeklyReportsPage'e eklenen hook'lar (useUrlQuerySync, previous/suggestions
effect'i, useMemo'lar) sayfanın tek return'ünün üstünde; yeni bileşenlerde koşullu return hook'lardan sonra.

**Denetim odakları:**
- Takım kapsamı: this-week yalnız kapsamdaki takımlar (admin/AUDIT: hatırlatması açık takımlar; kullanıcı: kendisi);
  suggestions/previous `service.get(id, actor)` üstünden (aynı yetki), açık olay sayımı `scoped=true` + takım listesi
  (IncidentRecordRepository.countByStatus). Listeden onay/iade mevcut uçlarla (sunucu izin + durum makinesi).
- Skor anlık görüntüsü: submit içinde try/catch — KPI hatası gönderimi durdurmaz (test pinli); DDL patch idempotent.
- URL ad alanı: `w_` PAGE_STATE_PREFIXES'e eklendi; bildirim kutusu `team` paramı da okunur (mevcut sözleşme bozulmadı).
- Yarış/yan etki: iade modalı `id` taşır (listeden iade doğru rapora — test yakaladı, düzeltildi); toplu onay işlem bayrağı
  try/finally (bayrak kapısı yakaladı); `previous`/`suggestions` effect'i `alive` bayrağıyla bayat yanıtı yazmaz.
- Kapılar: cssClasses/cssTokens/i18n-parity/tour-targets/progress-guard/csv-escape-guard yeşil; tam süit 2046; backend
  WeeklyReportService 63 / Controller 31 / Inbox 2 yeşil.
Bilinen sınırlar (bilinçli): yorum dizisi, hatırlatma görünürlüğü, yönetici PDF/CSV özeti, takım şablonu ikinci tur;
e-posta CTA'ları değişmedi (Outlook-güvenli şablon ayrı iş) — derin bağlantı yalnız uygulama içi.
→ **REGRESYON YOK**.

## Ek — yirmi yedinci tur (2026-09-13, sürüm öncesi — Haftalık Raporlar ikinci tur, `v20.63.0..HEAD`)

Kapsam: 1 commit (yorum dizisi, hatırlatma görünürlüğü, yıl özeti, takım kanal şablonu; pano + şerit varsayılan kapalı).
İmza süpürmesi: yeni kod/test/commit'te gerçek kişi/kurum adı yok (DEFAULT_TEMPLATE_JSON'daki eski kanal adı "Web Kanalı"
olarak nötrlendi; test iddiası da güncellendi). Hook + erken-return: yeni hook'lar (teamChannels/reminderNonce state,
missingTemplateChannels useMemo) sayfanın tek return'ünün üstünde; WeeklyComments/WeeklyReminderStatus'ta koşullu return
hook'lardan sonra.

**Denetim odakları:**
- Yetki: comments/addComment `service.get(id, actor)` kapısından geçer (takım kapsamı aynı); AUDIT yazamaz (SecurityException
  → 403), reminders/status yalnız admin/AUDIT (USER 403 pinli). weekly_channels yalnız takım üyesi/admin ucundan (mevcut
  weekly-notifications kapısı; ad/e-posta/aktiflik yine dokunulmaz — `ignoresAdminOnlyFields` yeşil).
- Veri: sistem yorumu try/catch — durum makinesini durdurmaz; rapor silme yorumları da siler (`deleteByReportId` tx
  sahibi `WeeklyReportService.delete` @Transactional, allow-list'e gerekçeyle eklendi); öksüz retention politikası
  (`weekly-report-comments-orphan`, PERSONAL) + RetentionCoverage/RetentionDoc yeşil, belge yeniden üretildi.
- Zaman: reminderStatus sonraki koşu IST 09:00 → UTC ISO; test `today()` ile göreli (sabit tarih yok), Cuma/09:00/gelecek
  iddiaları IST'e çevrilerek doğrulanır (CI UTC tuzağı).
- Kaçış: yıl özeti HTML'inde takım adı/durum `escHtml`; CSV `csvRows` (formül nötrleme test pinli). Yazdırma gizli
  iframe'de, uygulama CSS'inden bağımsız; tarayıcıda `print()` stub'lanarak 3 satır × 39 sütun + 5 lejant doğrulandı.
- Yarış/yan etki: yorum gönderimi try/finally (işlem bayrağı kapısı); `alive` bayrağı bayat yanıtı yazmaz; TeamManager
  şablon kaydı yalnız DEĞİŞTİYSE ikinci (dar) uca gider; "Şablondan tamamla" mevcut kanal adlarını (TR harf-duyarsız)
  korur, yalnız eksikleri ekler.
- Kapılar: eslint temiz (tek yeni uyarı useMemo bağımlılığı → düzeltildi); i18n TR/EN parity; cssClasses/cssTokens
  yeni sınıf/token'lar tanımlı; DDL patch idempotent (JPA update önce oluşturdu, patch noop). Tarayıcı: hatırlatma satırı
  (18.09 09:00, 2 gidecek / 1 girmiş / 3 açık), yorum gönderme + rozet, takım formunda 3 chip → `weekly_channels`
  JSON, editörde "Şablondan tamamla (3)" → 3 sekme.
Bilinen sınırlar (bilinçli): yorum dizisi e-posta/push bildirimi üretmez (PO'ya "yeni yorum" bildirimi ayrı iş);
yıl özeti PDF'i tarayıcının "PDF olarak kaydet"iyle (sunucu tarafı PDF yok); kılavuz §14.18'e ikinci tur bölümü sonraki
belge turunda.
→ **REGRESYON YOK**.

## Ek — yirmi sekizinci tur (2026-09-13, sürüm öncesi — Haftalık rapor onayı → takıma/müdüre push, `v20.64.0..HEAD`)

Kapsam: 1 commit. İmza süpürmesi: test sicilleri M00050/M00060 biçiminde kurgu, e-postalar *@test; gerçek ad yok.
Push kanal paritesi (bellek kuralı): e-posta ne gönderiyorsa push da — onay maili + takım/müdür push aynı anda.

**Denetim odakları:**
- Kanonik zincir (9 halka): AppSettingsCatalog (+2 BOOL) → UserPushController.PLAIN_KEYS → UserPushService okuma
  (`weeklyTeamEnabled/weeklyManagerEnabled`, vars. true) → WeeklyReportService.approve/resend çağrısı → UI bölümü →
  TR/EN etiket + `help.set.*` (settings-help-coverage kapısı) → teslimat günlüğü tetik kataloğu (`TRIGGERS` +
  `userpush.trigger.WEEKLY_REPORT`) → testler → belge (bu tur). AppSettingsCatalogCoverageTest yeşil (kodda okunan her
  anahtar katalogda).
- Alıcı doğruluğu: müdür = e-postanın alıcısıyla aynı kişi (Team.managerId → MANAGER kontağı e-postası → AD zinciri);
  e-posta eşlemesi LOWER() sorgusuyla (findAll taraması YOK — LDAP ile binlerce kullanıcı olabilir). Uygulama kullanıcısı
  değilse sessizce atlanır (SKIPPED_NO_RECIPIENTS). Opt-out satırı yazılır ama gönderilmez (teşhis izi).
- Seviye: takım bildirimi WARNING → uzman/PO grupları; yönetici (HIGH+) grubu bilerek almaz — o kişi zaten müdür
  push'unun alıcısı (çift bildirim yok). Doğrudan kanalda sessiz saat uygulanmaz (bilgilendirme, alarm değil).
- Yan etki: push hatası try/catch — onay/yeniden gönderim durmaz (test pinli); dedupe id+version(+:MGR) — yeniden
  açılıp tekrar onaylanınca yeni bildirim, aynı sürümde tekrar yok; e-posta FAILED ise metin bunu söyler.
- Kapılar: PushMessageContractTest (şablon yer tutucuları değişmedi), UserPushControllerTest, SettingsScopedAdminGate,
  settings-help-coverage, eslint temiz.
Bilinen sınırlar (bilinçli): tarayıcı doğrulaması bu turda YAPILMADI (oturum açılmadı; birim/kontrol testleri kapsıyor,
sürüm sonrası ilk onayda teslimat günlüğüne bakılmalı); push metni sabit (şablon ayarı yok); yorum dizisi bildirimi yok.
→ **REGRESYON YOK**.

### 28. tur eki — /gstack-qa (2026-09-13, `v20.64.0..HEAD`, tarayıcı doğrulaması YAPILDI)

Yukarıdaki "tarayıcı doğrulaması yapılmadı" sınırı kapandı: onay → e-posta + takım/müdür push zinciri, Webhook ayar bölümü,
yorum dizisi sistem satırları ve hatırlatma satırı headless tarayıcıda uçtan uca doğrulandı. QA 2 bulgu (ikisi de
Orta) buldu ve düzeltti, her biri ayrı commit + ayrı regresyon test dosyası:
- ISSUE-001 (Fonksiyonel): PO = müdür e-postası olan kişiye aynı onay için İKİ push (takım + müdür satırı). Düzeltme:
  müdür push'u önce; `enqueueDirect` dönen usernames takım bildiriminden düşülür (`enqueueTeamNotice` 8-arg, harf
  duyarsız). Yeniden gönderimde teslimat günlüğü 3 satırdan 2'ye indi (kanıt QA raporunda).
- ISSUE-002 (İçerik): hatırlatma durumu sayaçları bugünün haftasına bakıyordu; son giriş günü geçince (Cmt/Paz,
  Cuma 09:00 sonrası) "2 takıma gidecek" yanlış sayı. Düzeltme: sonraki koşu gününün ISO haftası; yanıt `run_week`
  taşır ve satırda gösterilir; saat enjekte edilebilir (Cmt/Çrş/Cuma-sonrası üç senaryo pinli — CI UTC tuzağı yok).
Sağlık puanı 97 → 100 (bağlantı/perf/erişilebilirlik kapsam dışı). → **REGRESYON YOK**.

## Ek — yirmi dokuzuncu tur (2026-09-13, sürüm öncesi — Yapılandırma sağlığı kartı varsayılan kapalı)

Kapsam: 1 commit, yalnız frontend (ConfigHealthCard + testi). Kart artık sorun olsa da kendiliğinden açılmaz;
başlık çipleri ("1 sorun · 2 uyarı · N tamam") özeti taşımaya devam eder; tercih `localStorage cfg-health-open`
(tamamlama panosu / bu-hafta şeridiyle aynı desen, try/catch sarmalı). Hook sırası değişmedi (useState başlangıç
fonksiyonu). Test: "sorunlu → yine kapalı, başlıkta sayaç, açınca liste" pinli; beforeEach localStorage temizler.
Kapılar: eslint temiz; backend kaynakları önceki turdan beri DEĞİŞMEDİ (3769 test o turda yeşil). → **REGRESYON YOK**.

## Ek — otuzuncu tur (2026-09-16, sürüm öncesi — modül görünürlüğü + bildirim bağlantısı + kenar çubuğu, `v20.66.0..HEAD`)

Kapsam: 3 commit. İmza süpürmesi temiz (yeni test/ekran metinlerinde example.com / "Takım A").
Hook + erken-return: App.jsx'e eklenen `weeklyReportsVisible` state'i auth kapısının ÜSTÜNDE;
AlertHistory'deki derin bağlantı effect'i mevcut hook sırasının sonunda, koşullu return yok.

**Denetim odakları:**
- Kapı sunucuda: modül kapalı takımın raporu `requireCanRead` içinden 403 — global admin ve AUDIT dâhil
  (tarayıcıda doğrulandı: list 0, GET 403 WEEKLY_REPORTS_DISABLED, pano 0, /api/me visible=false; tekrar
  açınca list 4, GET 200, visible=true). Arayüzdeki gizleme tek başına güvenlik sayılmadı.
- Yan kanallar: hatırlatma cron'u (skippedDisabled sayacı), reminderStatus, completion, thisWeek, create,
  transfer hedefi ve Genel Bakış "bugün" kartı aynı bayrağı okur — kanonik zincirin dokuz halkası da bağlı.
- Veri: kapatma SİLMEZ (test: setFeatureEnabled sonrası deleteById çağrılmaz); DDL patch idempotent
  (kolon vardı → noop, mevcut 3 satır FALSE'a çekildi), NULL = kapalı.
- Yetki: aç/kapat yalnız GLOBAL admin (`SessionScope.isGlobalAdmin`); kapsamlı müdür ayar bölümünü
  göremez (GLOBAL_ONLY_SECTIONS). Denetim kaydı WEEKLY_REPORT_ACCESS (katalogda).
- Bildirim bağlantısı: açık alarm artık Alarm Geçmişi'nde tip+alan adı süzgeciyle açılıyor, kart
  vurgulanıp kaydırılıyor, param tüketiliyor; `alert/type/level/ack/from/to` sekme değişiminde temizlenen
  paramlara eklendi (asılı filtre tuzağı).
- Kırılan mevcut testler bilinçli düzeltildi: modül bayrağı varsayılan kapalı olduğu için haftalık süit
  fixture'ları açık doğuyor; kapalı davranış AYRI süitte (WeeklyReportAccessTest). AdminSettings sekme
  testleri indeks yerine etiketle seçiyor.
- Kapılar: backend `mvn clean verify` (IdentityLeakGuard hariç — yalnız kullanıcının commit'lenmemiş iki
  belgesini işaretliyor) 3777 test yeşil; frontend lint/2060 test/kapsam tabanı/build yeşil.
Bilinen sınırlar (bilinçli): açılan takımın üyeleri sekmeyi en geç bir sonraki /me yüklemesinde görür
(anlık push yok); kapalı takımın eski raporları veritabanında durur (ürün kararı).
→ **REGRESYON YOK**.

## Ek — otuz birinci tur (2026-09-16, sürüm öncesi — Alarm Geçmişi zenginleştirmesi, `v20.67.0..HEAD`)

Kapsam: 2 commit (bildirim ikinci tıklama + görünürlük anahtarı rengi; Alarm Geçmişi takım kırılımı /
imza geçmişi / gürültü analizi). İmza süpürmesi temiz (yeni testlerde example.com + "Takım A").

**Denetim odakları:**
- N+1 yok: kart imza geçmişi İKİ toplu sorgu (özet + 500 satır tavanlı zaman çizelgesi örneklemi);
  takım kırılımı iki listeyle (açıklar + 30 gün penceresi) çalışıp kesişimi id ile tekilleştirir.
- Kapsam (IDOR): takım kırılımı listeyle AYNI kapsamı uygular (global değilse yalnız görülen takımlar,
  takımı çözülemeyen alarm hiç sayılmaz) — test pinli; uç `alerts.read` ister.
- Çift kaynak tuzağı (hafıza: DNS/Port çift kaynak): alarmın takımı hem satırın `team_id`'sinden hem
  domain→envanter SY takımından çözülür; yalnız biri sayılsaydı sertifika alarmları tabloda yoktu.
- Sıralama: "takımı çözülemeyen" satırı adı boş olduğu için listenin BAŞINA oturuyordu → daima sona
  alındı (test yakaladı).
- Zaman: "ne zamandır sessiz" ve "arada X gün" istemci tarafında `Date.now()` ile hesaplanır; damgalar
  zone'suz UTC olduğu için 'Z' eklenerek ayrıştırılır (yerel saat kayması yok), bozuk damga null döner.
- Gürültü analizi: yeni alanlar (seri, tip kırılımı, MTTR, kapanma oranı, mesai dışı) AYNI tek geçişten
  çıkar — ek sorgu yok; mesai dışı tanımı hafta sonu ∪ 18:00–09:00 (İstanbul).
- Kapılar: backend `clean verify` 3775 test yeşil (IdentityLeakGuard hariç — yalnız kullanıcının
  commit'lenmemiş iki belgesi), frontend lint / 2069 test / kapsam tabanı / build yeşil. Tarayıcıda
  doğrulandı: takım kırılımı (4 açık / 37 kapalı / 3 / 39), imza şeridi ("geçmişte 6 alarm · Önceki
  12.09 20:05 · 4 gündür sessiz"), "Geçmişini gör" → 6 kayıt, gürültü KPI'ları.
Bilinen sınırlar (bilinçli): imza geçmişi penceresi zaman çizelgesi örneklemiyle sınırlı (çok eski
"önceki oluşum" 500 satırın dışındaysa gösterilmez); takım kırılımı "kapandı" sütunu son 30 günü kapsar.
→ **REGRESYON YOK**.

## Ek — otuz ikinci tur (2026-09-16/17, sürüm öncesi — /gstack-qa bulguları, `v20.68.0..HEAD`)

Kapsam: 3 commit (yalnız frontend: bir JSX satırı + CSS + regresyon testi). QA turu Alarm Geçmişi
zenginleştirmesini (takım kırılımı, imza şeridi, gürültü KPI'ları) headless tarayıcıda uçtan uca
gezdi; 3 bulgu çıktı, üçü de düzeltildi ve doğrulandı. Sağlık skoru 88 → 100.

**Bulgular ve denetim notları:**
- ISSUE-001 (Orta, konsol): yeni takım kırılımı panelinde satır düğmesinin İÇİNDE TeamBadge →
  React `validateDOMNesting: <button> içinde <button>`, her satırda bir uyarı. `as="span"` ile
  düzeltildi; regresyon testi satırın BUTTON kalmasını, içinde iç içe buton OLMAMASINI ve
  `role="button"` erişilebilirliğinin korunmasını pinliyor. Bu tuzak 2026-09-10 öğrenmesinde 11
  yüzeyde pinliydi — yeni yüzey yine düştü; öğrenme "yeni panel" vurgusuyla güncellendi.
- ISSUE-002 (Orta, görsel): 375px'te tablo panel kutusunun 306px dışına taşıp KIRPILIYORDU
  ("Son 7/30 gün" ne görünüyor ne kaydırılıyordu). Sayfa geneli taşmadığı için (`pageOverflow 0`)
  klasik ölçüm bunu göremez — ölçüm panel kabında (`scrollWidth-clientWidth`) yapıldı. Kendi
  yatay kaydırma kabı + `min-width` (tamamlama panosundaki `.wrc-scroll` deseniyle aynı).
- ISSUE-003 (Düşük, görsel): başlıktaki özet çipi dar ekranda başlığı dört satıra bölüyordu →
  760px altında gizlendi (sayılar panelin içinde zaten var); gürültü panelinin çipi de aynı kuralda.
- Kapılar: backend `clean verify` 3775 test yeşil (IdentityLeakGuard hariç — yalnız kullanıcının
  commit'lenmemiş iki belgesi), frontend lint / 2070 test / kapsam tabanı / build yeşil. Koyu tema
  ve 375px yerleşimi tarayıcıda görüldü; düzeltme sonrası konsol uyarı sayacı SABİT kaldı (delta 0).
Bilinen sınırlar (bilinçli): 375px'te kenar çubuğu içerik alanını ~204px'e sıkıştırıyor (mevcut
uygulama düzeni, bu turun kapsamı dışında); bağlantı/performans/erişilebilirlik kategorileri test
edilmedi.
→ **REGRESYON YOK**.

## Ek — otuz üçüncü tur (2026-09-17, sürüm öncesi — panel varsayılanı, e-posta çipi, bakım hedef seçici, `v20.68.1..HEAD`)

Kapsam: 3 commit, yalnız frontend. İmza süpürmesi temiz (testlerde example.com).

**Denetim odakları:**
- Panel tercihi oturumluk: sessionStorage; eski localStorage "true" kaydı okunmuyor (tarayıcıda
  doğrulandı: kayıt true dursa bile ikisi de kapalı açıldı). Test sızıntısı (bir önceki testin açık
  bıraktığı panel) beforeEach'te sessionStorage temizlenerek kapatıldı.
- E-posta çipi: kaynak `notified_contacts` (kademe) → `email_sent_count/failed` (notification_logs).
  "kimseye ulaşmadı" uyarısı da gerçek gönderime bakar; mevcut test yeni sözleşmeye uyarlandı.
- Bakım hedef seçici: değer sözleşmesi string[] kaldı; seçenek kimliği tür+hedef oldu — aynı URL'yi iki
  tür izleyince ikincisi dedup'ta eleniyordu (Sayfa Hızı hiç seçilemiyordu). `targetObjs` kimliği ilk
  ':' ile ayırır (tür adları ':' içermez); eski kayıtlar (`type` yoksa) hedefiyle yüklenir. Uçtan uca
  doğrulandı: "Şimdi başlat" ile pagespeed hedefi kaydedildi (`{type:"pagespeed",target:…}`),
  düzenlemede çip geri geldi, test kaydı silindi.
- `mw.type.pagespeed` anahtarı iki dilde de yoktu — parity kapısı bunu yakalayamaz (ikisinde de eksik);
  eklendi. Modal içindeki `.form-grid label` kuralı seçicinin arama kutusunu iki satıra bölüyordu → label
  yerine div (aria-label korunur).
- Kapılar: frontend lint / 2075 test / kapsam tabanı / build yeşil; backend kaynağı DEĞİŞMEDİ (son
  `clean verify` 3775 test yeşil, aynı kaynak).
Bilinen sınırlar (bilinçli): "türün tamamını seç" o anki monitörleri ekler (gelecekte eklenenler için
"Tüm monitörler" kutusu; ipucu metni söylüyor); tür bazlı joker hedef sunucuda yok.
→ **REGRESYON YOK**.

## Ek — otuz dördüncü tur (2026-09-18, sürüm öncesi — çok takımlı takım seçimi, grup+etiket zorunluluğu, takım kilidi, etiket filtresi, `v20.69.0..HEAD`)

Kapsam: backend (MonitoringController 9 tür + AdminController envanter + LdapProvisioning/UserService)
ve frontend (9 izleme sayfası, envanter formu, UserManager, monitorFilters). İmza süpürmesi temiz
(testlerde example.com / "Grup A" / "Takım A").

**Denetim odakları:**
- Çok takımlı kullanıcı: eskiden `canOperateTeam` USER için yalnız BİRİNCİL takımı kabul ediyor,
  ikincil takım istendiğinde `resolveWriteTeam` SESSİZCE birincile düşüyordu (kullanıcı "X'e ekledim"
  sanıp Y'de buluyordu). Artık üyesi olduğu her takım kabul; üye olmadığı takım 403 (sessiz düşüş yok);
  eski oturum (memberTeamIds yok) birincile geri düşer. Frontend: `useMonitorTeamPick` 9 sayfada ortak;
  2+ takımlı üye için kutu açılır, "takımsız" seçeneği yalnız admin'de. Hook, `teams` state'inden ÖNCE
  çağrılınca TDZ çöküşü — testte yakalandı, sıralama düzeltildi.
- Grup + etiket zorunlu: sunucu kapısı `requireGroupAndTags` (oluşturmada eksik/boş → 400; güncellemede
  yalnız GÖNDERİLİP boş bırakılmışsa 400 — kısmi PUT'lar, ör. `excludePatterns`, dokunulmaz). Domain/DNS/
  Ping'e `tags` kolonu (nullable, ddl-auto) + form bloğu eklendi. Envanter formunda etiket alanı YOKTU ve
  `updateInventory` `existing.setTags(item.getTags())` ile düzenlemede etiketleri SİLİYORDU → alan eklendi,
  boş gönderim reddedilir (test: mevcut "prod" korunur). Backend testlerinde 38+19 create/update gövdesine
  grup+etiket enjekte edildi; 9 tür için parametreli kapı testi.
- Takım kilidi: `team_locked` (role_locked/org_role_locked ile aynı sözleşme) — admin üyeliği DEĞİŞTİRİNCE
  kilit; aynı küme yeniden gönderilirse kilit KONMAZ (form her kayıtta team_ids yollar); LDAP `resolveTeams`
  kilitliyse hiç dokunmaz; `team-unlock` ucu + `USER_TEAM_UNLOCK` denetim olayı (katalog alfabetik).
  Tarayıcıda uçtan uca: ikinci takım eklenince rozet + "Takımları AD'ye geri ver" menüsü, toast, rozet
  kalktı; test kullanıcısı geri alındı.
- Etiket filtresi (9 sayfa): `matchesTag`/`tagNamesOf`/`matchesGroupOrTagText` tek yerde; URL `tag`
  paramı (PAGE_STATE_PARAMS); "Etiketsiz" seçeneği eski kayıtları bulur (kutu etiketsiz kayıt varken de
  görünür). `.ss-wrap` global width:100% üç kutuyu alt alta diziyordu → `.upt-toolbar > .ss-wrap` daraltması
  (Alarm Geçmişi ile aynı). Tarayıcıda: qa-beta → yalnız B, qa-ortak → A+B, URL `?tag=…`.
- Kapılar: backend `clean verify` 3810 test yeşil; frontend lint / test / kapsam tabanı / build yeşil.
Bilinen sınırlar (bilinçli): envanter CSV içe aktarma (`/inventory/import`, `/bulk`) grup/etiket kapısından
GEÇMEZ (mevcut içe aktarma sözleşmesi korunur); envanter-türevi DNS/Port satırları grup/etiket taşımaz
(kaynak envanter kaydıdır).
→ **REGRESYON YOK**.

## Ek — otuz beşinci tur (2026-09-18, sürüm öncesi — her rolde filtre, Domain Ekle her seviyede, Genel Bakış grup/etiket, çok takımlı kullanıcı kutusu, `v20.69.0..HEAD`)

Kapsam: backend (PermissionCatalog USER `inventory.crud`, AdminController `requireInventoryWriter`,
CertificateDto/Service group_name+tags) ve frontend (9 izleme sayfası filtre kaynağı, App Genel Bakış
çubuğu, InventoryManager, Nav). İmza süpürmesi temiz.

**Denetim odakları:**
- Filtre seçenekleri rol fark etmeksizin GÖRÜNEN listeden türer; istemcinin ikinci daraltması müdür/izleyici
  gibi çok takım gören rollerde başka takımın grubunu seçilemez kılıyordu. Sunucu kapsamı değişmedi.
- "Domain Ekle" USER'a açıldı: yetki `inventory.crud/edit` (mevcut kurulum için politika yükseltmesi, insan
  eli değmiş satıra dokunmaz); uç `requireInventoryWriter` = admin / yönetim kapsamı / ÜYELİK. Test: USER kendi
  takımına 200, başka takıma 403. Düzenleme/silme/aktarma/içe aktarma yönetici kapılarında KALDI (bilinçli).
- Genel Bakış: `/api/certificates` group_name+tags döner (DTO testi: envanterde boşsa null); grup/etiket
  kutuları, "Etiketsiz/Grupsuz" seçenekleri, metin araması grup/etikette de eşleşir, "Filtreleri temizle"
  (herhangi bir daraltmada görünür, istatistik kartı seçimini de sıfırlar). Tarayıcıda: tek kayıtta bulunan bir etiket seçildi → 1 kayıt,
  temizle → düğme kayboldu, liste geri geldi. Yerleşim: arama en başa alındı (sağa yaslı arama satır sonuna
  düşüyordu), etiket+kutu çifti `.sort-bar-field` ile birlikte sarıyor.
- Kenar çubuğu kutusu: çok takımlı kullanıcıda tek (birincil) takım yerine "N takım" + popover'da "Dahil
  olduğum takımlar" → modal (TeamBadge → üye modali; birincil işaretli). Tek takımlı görünüm değişmedi (Nav testi).
- Kapılar: backend `clean verify` 3813 test; frontend lint / 2098 test / kapsam tabanı / build yeşil.
→ **REGRESYON YOK**.

## Ek — otuz altıncı tur (2026-09-18, sürüm öncesi — USER kendi takımının envanter kaydını düzenler, `v20.69.0..HEAD`)

Kapsam: `updateInventory` kapısı `requireInventoryWriter` (admin / yönetim kapsamı / üyelik); takım aktarımı
yalnız kaydın takımını yönetenlere (TEAM_ADMIN ve üyelik yoluyla gelen USER için team_id sabitlenir — test:
team_id 9 gönderilse de 2'de kaldı). Başka takımın kaydı 403; SİLME kendi takımında bile 403 (yönetici işi).
Frontend: satır kapısı `canEditRow` (Düzenle/Kopyala/satır içi tier) — USER yalnız üyesi olduğu takımın
satırında; toplu seçim/sil/içe aktarma/hijyen bandı canManage'de kaldı; Genel Bakış kartında da aynı kapı.
Kapılar: backend `clean verify` yeşil; frontend lint / 2099 test / kapsam tabanı / build yeşil.
→ **REGRESYON YOK**.

## Ek — otuz yedinci tur (2026-09-18, sürüm sonrası — Durum İzleme grup/etiket, Vade Takvimi hücreler + yoğunluk özeti + sayfalı modal, Olaylar takım sütunu, Alarm Geçmişi hücre pop-up'ı, `v20.70.0..HEAD`)

Kapsam: backend (uptimeOverview group_name/tags/tier; IncidentsController team_id/team_name — damgalı takım
yoksa domain→envanter SY/UG tek toplu sorgu, TeamRepository bağımlılığı) ve frontend (UptimePage, ExpiryForecastPage +
forecastModel.teamBucketCerts, IncidentsPage, AlertTeamStatsPanel + AlertTeamCellModal). İmza süpürmesi temiz.

**Denetim odakları:**
- Durum İzleme: `.upt-toolbar-left > .ss-wrap` daraltması eklendi (üç kutu alt alta diziliyordu — tarayıcıda
  görüldü, düzeltildi); kart çipleri `.inv-tag` diliyle, tıklama filtreler; URL `group`/`tag`.
- Vade Takvimi: hücre listesi `teamBucketCerts` byTeam ile AYNI aralık kuralı (test: her kovanın uzunluğu
  tablodaki sayıya eşit); grafik `onClick` → gün modali; ChartInsight (toplam/yoğun gün/haftalık/tepe 7 gün/tepe gün).
  Modal `DayListModal` 10'luk sayfalı + 5+ kayıtta arama (kullanıcı bildirimi: 9 kayıt ekranı aşıyordu).
- Olaylar: takım sütunu; rozet hücresi `stopPropagation` (satır "link" davranışı korunur — test).
- Alarm Geçmişi: hücre → sunucu sayfalamalı pop-up; kova→sorgu eşlemesi AlertTeamStatsService ile aynı
  (açık tarihten bağımsız; kapandı pencere içi; 7/30 gün açılanlar). Takımsız satır tıklanmaz (teamId ile
  sorgulanamaz). Tarayıcıda: 43 → "1–25 / 43 kayıt", tablo `table-layout: fixed` ile modal genişliğine sığar.
- cssClasses kapısı iki hayalet sınıf yakaladı (`.alh-cell-row`, `.fc-ci-item`) → tanımlandı.
- Kapılar: backend `clean verify` yeşil; frontend lint / 2105 test / kapsam tabanı / build yeşil.
→ **REGRESYON YOK**.

## Ek — otuz sekizinci tur (2026-09-18, sürüm sonrası — push {ne}, "Tümünü gör" pop-up'ı, Yenileme Önerileri yeniden tasarımı, `v20.70.0..HEAD`)

Kapsam: backend (UserPushService.expiringWhat; TodayPanelService build(limit) + /me/today?full; CertificateService
buildAdvice bağlam alanları) ve frontend (TodayPanel + TodayListModal; RenewalAdvice tümüyle yeniden). İmza süpürmesi temiz.

**Denetim odakları:**
- Push: {ne} sabit "süre" idi → tipten türer (DOMAIN içeren tipler "alan adı kaydı", diğer EXPIRY "SSL sertifikası");
  test dört tipi pinler. Diğer şablonlar zaten neyi söylüyor; DEĞİŞMEDİ (admin'in özelleştirdiği şablon varsa
  {ne} yer tutucusu aynı adla dolmaya devam eder).
- Today: `build(canView, own, limit)` — count her iki halde tam sayı, items yalnız tavan (test). Pop-up 10'luk
  sayfalı + arama; "Sayfaya git" eski geçiş. Test: inline `Card` bileşeni her render'da yeniden kuruluyor →
  eski düğme referansı kopuk (testte yeniden sorgu; üretimde etkisi yok — kullanıcı her tıklamada güncel DOM'a basar).
- Yenileme Önerileri: sunucu sırası (öncelik) korunur, istemci sıralama/süzme ek; URL `r_pri`/`r_code` (r_ öneki
  zaten PAGE_STATE_PREFIXES'te) + ortak `team/group/tag/q/sort/page`. CSV `csvRows` (kaçış kapısı) + BOM.
  cssClasses kapısı `.rn`/`.rn-row` hayaletlerini, cssTokens `var(--card-bg, …)` yedeklisini yakaladı → düzeltildi.
  Tarayıcıda: özet şeridi/süzgeçler/kart/tablo görüldü; Genel Bakış pop-up'ı "Açık alarm (4)" + sayfa boyutu + Sayfaya git.
- Kapılar: backend `clean verify` yeşil; frontend lint / 2110 test / kapsam tabanı / build yeşil.
→ **REGRESYON YOK**.

## Ek — otuz dokuzuncu tur (2026-09-18, sürüm sonrası — Günlük Yoğunluk çubukları tıklanmıyordu, `v20.71.0..HEAD`)

Kapsam: yalnız frontend (ExpiryForecastPage). Bulgu tarayıcıda kanıtlandı: `elementFromPoint` çubuğun ortasında
`.recharts-line-curve` döndü — kümülatif çizgi çubukların üstünde çizilip tıklamayı yutuyordu; grafik seviyesi
`onClick` Recharts 3'te her tıklamada `activePayload` vermiyor. Düzeltme: çizgi `pointer-events: none`; üç `Bar`ın
kendi `onClick`'i birincil (payload → gün modali), grafik onClick `activeTooltipIndex/activeIndex` yedeği.
Doğrulama: aynı noktada `elementFromPoint` → `.recharts-rectangle`, pop-up "24.10.2026 — bu gün dolan sertifikalar (2)".
Kapılar: frontend lint / 2110 test / kapsam tabanı / build yeşil; backend DEĞİŞMEDİ.
→ **REGRESYON YOK**.

## Ek — kırkıncı tur (2026-09-19, sürüm sonrası — izleme alarm seviyesi: varsayılan Uyarı, izlemeden Yüksek/Kritik, `v20.71.1..HEAD`)

Kapsam: backend (MonitorAlertPrefs arayüzü + 9 modelde `alert_level`; SchedulerService.chanCtx(ctx, izleme);
MonitoringOutageService.levelFor → WARNING yedeği; EscalationService.includeManagerContacts seviye kapısı;
MonitoringController alertLevel uygula/döndür) ve frontend (NotifyChannels seviye seçici, 9 form). İmza süpürmesi temiz.

**Analiz (eski):** `levelFor(tip)` sabit HIGH/CRITICAL üretiyordu; erişilemiyor/sentetik/anahtar kelime/DNS hatası her
seferinde KRİTİK açılıyor, seviye eşikli push aboneleri ve (envanter-türevi tiplerde) eskalasyon kontakları bilgileniyordu.
Sertifika/alan adı süre-bitişi gün kademesiyle doğru çalışıyordu.

**Denetim odakları:**
- Seviye kaynağı tek: sweep bağlamı `alert_level` (izlemenin seçimi, null → WARNING); HESAPLANAN kademe (alan adı
  süre-bitişi CRITICAL/WARNING, EPP durumu) `putIfAbsent` ile korunur — test: chanCtx ezmez, domainItem EXPIRY
  hesaplananı, CHANGED/LOCK/BLACKLIST/UNKNOWN izleme seviyesini taşır. İlk sürümde chanCtx aşırı yüklemesi kendi kendini
  çağırıyordu (StackOverflow, 21 test) — toplu değiştirme yeni gövdeye de dokunmuştu; düzeltildi.
- Alıcı politikası tek kural: WARNING takım-özel; HIGH → Uyarı/Yüksek eşikli kontaklar; CRITICAL → hepsi (müdür dâhil).
  Eski "yalnız alan adı KRİTİK'te müdür" istisnası bu kuralın içine düştü; storm karar tablosu aynı fonksiyonu kullandığı
  için storm testleri güncellendi (WARNING üye → kontak sorgusu yok).
- Açık alarm seviyesini korur; izleme düzenlenip yükseltilirse mevcut terfi yolu ESCALATION gönderir; düşürme demote etmez.
- Form: "Bildirimler ve alarmlar" bloğunda Uyarı/Yüksek/Kritik segment + seviye notu; Kopyala alanı taşır (9 test);
  değişiklik geçmişi `chg.field.alertLevel` etiketi (parity kapısı yakaladı). Tarayıcıda: Ping formu, Kritik → API
  `alert_level: CRITICAL`, test kaydı silindi.
- Kapılar: backend `clean verify` 3823 test; frontend lint / 2112 test / kapsam tabanı / build yeşil.
Bilinen sınırlar (bilinçli): envanter-türevi (izlemesiz) port/DNS/erişilebilirlik kontrolleri WARNING yedeğinde —
envanter kaydına seviye alanı eklenmedi; alan adı EPP durumu (pendingDelete vb.) hesaplanan seviyesini korur.
→ **REGRESYON YOK**.

## Ek — kırk birinci tur (2026-09-19, sürüm sonrası — envanter formunda Kritiklik Seviyesi / Bağlantı zaman aşımı hizası, `v20.71.1..HEAD`)

Kapsam: yalnız frontend (InventoryFormModal + App.css). `.form-grid` ızgarası ALT hizalı (`align-items: end`); altında
ipucu olan "Bağlantı zaman aşımı" komşusu "Kritiklik Seviyesi"ni aşağı kaydırıyordu (tarayıcıda y 436 vs 389). Envanter
formu `form-grid--top` (üst hizalı) varyantını kullanır; diğer formlar dokunulmadı. Doğrulama: iki etiket y=436.7.
Kapılar: frontend lint / 2112 test / kapsam / build yeşil; backend DEĞİŞMEDİ.
→ **REGRESYON YOK**.


## Ek — kırk ikinci tur (2026-09-19, sürüm sonrası — başlık eylem düğmeleri tek stil: Yenile / Şimdi Kontrol Et / bağlantı kopyala / Nasıl doldurulur? / Yeni Monitör / Domain Ekle, `v20.71.1..HEAD`)

Kapsam: yalnız frontend (App.css + DnsMonitorPage, CopyLinkButton, InventoryManager, App.jsx). Dokuz izleme sayfası,
Envanter ve Genel Bakış başlıklarındaki eylem düğmeleri üç ayrı görünümdeydi (`.btn` gri, `.btn-primary` düz mavi,
`.btn-success` yeşil, `mguide-btn` ikonlu turuncu; DNS sayfası satır içi stil ile hizalanıyordu). Tek kural, yalnız
`.upt-header-right` / `.inv-header-actions` / `.sort-bar .sort-bar-add-domain` kapsamında: 34px / 9px köşe / 13px 600
ağırlık; ikincil = yüzey arka planı + kenarlık + marka hover; birincil (`btn-primary`, `btn-success`, `sort-bar-add-domain`)
= marka gradyanı + beyaz metin. Küresel `.btn` DOKUNULMADI (modal/tablo düğmeleri eski görünümde).
Denetim odakları:
- Kopyala düğmesi yalnız-ikon olduğunda 34×34 kare: `:has()` seçicisi jsdom/tarayıcıda güvenilir eşleşmedi →
  `data-icon-only` özniteliği (CopyLinkButton) + öznitelik seçicisi.
- DNS sayfası satır içi `style` sarmalayıcısı `upt-header-right` sınıfına çekildi (12 sayfa aynı sarmalayıcı).
- Envanter "Domain Ekle" (`btn-success`) ve Genel Bakış "Domain Ekle" (`sort-bar-add-domain`) birincil stile + `Plus` ikon;
  Genel Bakış düğmesi `margin-left:auto` ile süzgeç satırının sağına.
- Koyu tema: gölge geçersiz kılmaları; hayalet sınıf/token yok (cssClasses / cssTokens kapıları yeşil).
Tarayıcı doğrulaması: HTTP, DNS, Sentetik, Envanter (Dışa Aktar / İçe Aktar ikincil + mavi Domain Ekle), Genel Bakış.
Kapılar: frontend lint 0 hata / 2112 test (ilk koşumda lazy-tabs-smoke UptimePage 15 sn zaman aşımı, seri tam tekrar
246/246 yeşil) / kapsam tabanı / build yeşil; backend DEĞİŞMEDİ.
→ **REGRESYON YOK**.

## Ek — kırk üçüncü tur (2026-09-19, sürüm sonrası — Durum İzleme kartı: HTTP Kontrol Geçmişi de SAATLİK, `v20.71.1..HEAD`)

Kapsam: yalnız backend yapılandırması (SchedulerService `runUptimeChecks` @Scheduled yedeği + application.properties
`site.monitor.uptime.interval-ms`). SSL Kontrol Geçmişi saat başı (`scheduler.cron 0 0 * * * *`) dolarken HTTP Kontrol
Geçmişi 5 dk'da bir doluyordu (300000 ms); iki geçmiş aynı sıklıkta olsun diye varsayılan 3600000 ms'e çekildi.
Denetim odakları:
- Tek anahtar, iki yazım yeri (properties + @Scheduled yedeği) → `UptimeIntervalDefaultTest` ikisini de 1 saate ve
  SSL cron'unu saat başına pinler (PasswordPolicyDefaultTest deseni). Env `UPTIME_INTERVAL_MS` ile ezilebilir; yerel
  `.env` ezmiyor; helm/k8s bu anahtarı taşımıyor (prod da yeni varsayılanı alır).
- DOWN teyidi (confirm-* 30 sn × 3) ve kurtarma kontrolleri (`uptime.recovery-*`) süpürme aralığından bağımsız —
  alarm gecikmesi değişmez, yalnız TESPİT en geç 1 saat sonra. Bilinçli ürün kararı (kullanıcı isteği).
- Bayatlık eşiği (`scheduler.stale-minutes` 65) sertifika kontrolüne bakar, uptime tablosuna değil → etkilenmez.
  24 saatlik erişilebilirlik yüzdesi artık 24 örnekten hesaplanır (tek arıza ≈ %95,8).
- Frontend/i18n/whitepaper'da "5 dakikada bir" erişilebilirlik ifadesi yok (tarandı) → metin değişikliği gerekmedi.
Kapılar: backend `clean verify` 3830 test — 1 kırmızı `IdentityLeakGuardTest.noNewIdentityLeaks`, YALNIZ kullanıcının
takipsiz `docs/CORE_WEB_VITALS_FIZIBILITE.md` / `sre-slo-sekmesi.komut.md` dosyalarından (depoda yok, CI'ı
etkilemez; dokunulmadı); yeni test 2/2 yeşil. Jar yeniden kuruldu, :8080 `/health` UP, :5173 200. Frontend DEĞİŞMEDİ.
→ **REGRESYON YOK**.

## Ek — kırk dördüncü tur (2026-09-19, sürüm sonrası — "Sizin için — bugün": istisna kartı kalktı, dört izleme kartı geldi, `v20.71.1..HEAD`)

Kapsam: backend (MonitorSchedule arayüzü × 9 model; ActivityLogRepository 4 toplu sorgu; yeni TodayMonitorInsightsService;
TodayPanelService istisna bloğu kaldırıldı, görünürlük süzgeci) + frontend (TodayPanel, TodayListModal, todayMonitorRows,
i18n TR/EN, `.today-type`). Kullanıcı seçimi (4/4): kararsız (flapping) · yavaşlayan · sessiz/bayat · alan adı kaydı dolan.
Denetim odakları:
- Tek kaynak activity_log (monitor_id dolu → 9 tür); CERT/UPTIME envanter satırları kendi kartlarında. Sorgular zaman
  aralığıyla (idx_act_time) kesilir; kararsızlık dizisi YALNIZ aralıkta arıza yazan monitörler için çekilir (tür başına IN).
- Hesap tüm takımlar için 60 sn önbellekte (`today-monitors`); takım görünürlüğü TodayPanelService'te satır bazında —
  alarm kartıyla AYNI kural (takımı görünür ya da hedefi görünür envanterde). Önbellek satırı kopyalanır (team_name).
- Eşikler: kararsız ≥3 UP↔DOWN geçişi/24 sa (WARNING "iyi"); yavaş 24 sa ort. ≥1,5× taban VE ≥100 ms, iki tarafta ≥3
  örnek, DOMAIN hariç (whois gecikmesi); bayat son kontrol > 2×aralık (taban 5 dk), 7 gün pencere — pencerede yaratılmış
  ve hiç kontrol yoksa "hiç kontrol edilmedi", daha eskisi "7+ gündür kontrol yok"; alan adı ≤30 gün (dolmuş sayılır).
- Tarayıcı bulgusu: yerelde 13 "sessiz" satır çıktı — hepsi envanter-türevi DNS/Port öksüzleri (alanı envanterden
  silinmiş; süpürme `standalone != true && !activeDomains` ile BİLEREK atlıyor, liste uçları göstermiyor). Aynı kural
  `MonitorSchedule.scheduleStandalone()` ile karta taşındı → 0 satır; test `stale_skipsOrphanedInventoryDerived`.
- Satır tıklaması `?tab=<tür>&monitor=<id>` derin bağlantısı (useMonitorDeepLink, 8 sayfa); Sentetik'te yalnız sekme.
  Pop-up ve kart aynı satır bileşenini çizer (todayMonitorRows.jsx). Tarayıcıda: fetch yaması ile 4 kart dolu görüntü,
  Sessiz pop-up 2 satır, satır → `?tab=port&monitor=2`.
- Kaldırılan: `exceptions` bloğu/kartı, `today.exceptions*`/`expiredOn`/`untilIn` anahtarları (kullanılmayan anahtar
  kapısı yeşil), WeakAlgorithmExceptionRepository bağımlılığı.
Kapılar: backend `clean verify` 3837 test — tek kırmızı `IdentityLeakGuardTest` (kullanıcının takipsiz 2 dosyası, depoda
yok, dokunulmadı); yeni testler 7 + 4 (TodayPanelServiceTest güncellendi). Frontend lint 0 hata / 2113 test / kapsam / build
yeşil. Jar yeniden kuruldu, :8080 `/health` UP.
→ **REGRESYON YOK**.

## Ek — kırk beşinci tur (2026-09-19, sürüm sonrası — "Sizin için — bugün": teslim edilemeyen bildirim + sertifika sağlık bulgusu kartları, `v20.71.1..HEAD`)

Kapsam: backend (TodayMonitorInsightsService iki yeni blok + 6 bağımlılık; CertificateHealthService `thresholdDays()` /
5-arg `evaluate` — eşik BİR kez okunur; UserPushDeliveryRepository türetilmiş sorgu; TodayPanelService iki blok) +
frontend (TodayPanel 2 kart, todayMonitorRows NotificationRowBody/HealthRowBody, TodayListModal, i18n TR/EN, CSS rozetleri).
Kullanıcı seçimi (4 adaydan 2): teslim edilemeyen bildirim (24 sa) · sertifika sağlık bulguları.
Denetim odakları:
- Bildirim: notification_logs (e-posta/webhook `FAILED…`, alarm üstünden takım+alan) + user_push_deliveries (`FAILED`,
  takım). Hata metni "FAILED:" önekinden arındırılır; aynı olayın e-posta ve webhook'u ayrı satır (anahtar
  kanal:olay:hedef:zaman). Satır → Alarm Geçmişi `incident=<olay>`; olaysız push yalnız sekme. Kanal kırılımı alt yazıda.
- Sağlık: aktif envanterin her alanı için `evaluate` FAIL satırları — "expiry" hariç (30 gün altı kartı). Süresi dolmamış
  zayıf-algoritma istisnası signature/keySize'ı SUSTURUR (kaldırılan istisna kartının işlevi buraya taşındı; "istisnalı"
  etiketi), dolmuş istisna susturmaz; trust/chain/sanMatch/revocation KRİTİK (kırmızı rozet, kart tonu). Rozet başlığı
  `hlth.val.*` değer metni (ör. "Eski (TLSv1)"). Satır → sertifika detayı (onOpenDomain); "Sayfaya git" → Zayıf Algoritma Raporu.
- Görünürlük: her iki blok TodayPanelService'te aynı `visibleRows` kuralıyla süzülür; önbellek 60 sn (tüm takımlar).
- Yerel veri: 11 sertifika 14/14 OK, 24 saatte teslim hatası yok → iki kart 0 (uç ile doğrulandı); dolu görünüm fetch
  yamasıyla tarayıcıda: kanal rozetleri, hata metni, kritik/istisnalı rozetler, takım rozeti.
Kapılar: backend `clean verify` 3839 test — tek kırmızı `IdentityLeakGuardTest` (kullanıcının takipsiz 2 dosyası); yeni
testler 2 (+CertificateHealthServiceTest 36 yeşil). Frontend lint 0 hata / 2114 test / kapsam / build yeşil. Jar yeniden
kuruldu, :8080 `/health` UP.
→ **REGRESYON YOK**.

## Ek — kırk altıncı tur (2026-09-19, sürüm sonrası — Genel Bakış: "Domain Ekle" ve "Domain ara" üst kontrol satırına, `v20.71.1..HEAD`)

Kapsam: yalnız frontend (App.jsx + App.css). Kullanıcı isteği (iki adım): "Domain Ekle" "Şimdi Kontrol Et"in yanına,
ardından "Domain ara" kutusu "Domain Ekle"nin yanına. İkisi de süzgeç satırından (`.sort-bar`) `.controls`'a taşındı.
Denetim odakları:
- Domain Ekle artık "Şimdi Kontrol Et" ile aynı küresel `.btn-primary` (yan yana iki farklı mavi olmasın) →
  `.sort-bar-add-domain` gradyan/`margin-left:auto` kuralları ve 42. turdaki birleşik seçici üyeliği kaldırıldı;
  `.controls-add-domain` yalnız sarma/ikon boşluğu. Yalnız `tab === 'dashboard' && canAddInventory`.
- Arama: `.controls-search` sarmalayıcı `align-self: stretch` — kutu düğme yüksekliğine uzar (ilk denemede 36 vs 39 px
  hizasızdı, ölçülüp düzeltildi: hepsi y=20/h=39). Temizle düğmesi bitişik; arama/temizleme davranışı aynı state.
- Süzgeç satırında yalnız Sırala/Durum/Süre/Takım/Grup/Etiket kaldı; 2026-09-18 "arama en başta" notu güncellendi.
- Tarayıcıda: "axess" yazınca 1 kart, temizle düğmesi görünür; ekran görüntüsü.
Kapılar: frontend lint 0 hata / 2114 test / kapsam / build yeşil; backend DEĞİŞMEDİ.
→ **REGRESYON YOK**.

## Ek — kırk yedinci tur (2026-09-19, sürüm sonrası — SMTP Gönderim Logu v2: tam sayfa, sunucu taraflı arama/özet, `v20.71.1..HEAD`)

Kapsam: backend (SmtpLogController + SmtpLogQueryService yeni; NotificationLogRepository gövdesiz projeksiyon ×2;
EmailNotificationService.resendStoredHtml; AuditEventCatalog SMTP_RESEND) + frontend (SmtpLogView yeni; SystemHealth modal
kaldırıldı → `view=smtp` alt görünüm; api.admin.smtpLog; `m_` URL öneki; TodayPanel bildirim kartı buraya derin bağlanır;
i18n TR/EN 60 anahtar; CSS `.sml-*`). Kullanıcı seçimi (4+4+tam sayfa): KPI + zaman çizelgesi · takım kırılımı · hata
sınıfları · alıcı/alan özeti · zengin detay · CSV · yeniden gönder · otomatik yenileme + derin bağlantı.
Denetim odakları:
- Eski uç 30 günlük pencereyi TAM VARLIK (gövde dâhil, onlarca KB/satır) yüklüyordu; yeni pencere gövdesiz projeksiyon +
  alarm→takım/alan zenginleştirme, 60 sn önbellek (bean içi çağrı proxy'yi atladığı için @Cacheable DEĞİL, CacheManager
  elle). Özet + arama aynı paramla → tek tarama. Gövde yalnız detayda (findById).
- Takım kapsamı: CertificateController kuralı (görüş takımları + o takımların envanter alanları); global görücü hepsi.
  Kontrolör testi kapsam türetimini pinler.
- Hata sınıfı sözlüğü (TIMEOUT>AUTH>RATE>RECIPIENT>CONNECT>OTHER; SMTP kodları + JavaMail kalıpları) ve kind
  (SENT/FAILED/SKIPPED/QUEUED/UNKNOWN) birim testli. Zaman çizelgesi ≤72 sa saatlik, üstü günlük; boş kovalar yazılır.
- Yeniden gönder: yalnız FAILED, çözülmüş alarm 409, kapsam dışı 404; aynı gövde `sendFramedHtml` hunisinden (CID logo,
  EmailBrandCidTest huni sayısı korunur); yeni log satırı trigger MANUAL; denetim SMTP_RESEND; yalnız global admin +
  `system_health.actions`.
- **Tarayıcıda yakalanan hata:** `search` yanıtı `Map.of` ile kurulunca açık uçlu pencerede `to=null` → NPE → "Sunucu
  hatası" (birim testler mock'landığı için görmedi). LinkedHashMap'e çekildi; `SmtpLogControllerTest` (WebMvc) regresyon.
- **Kardeş bulgu:** `.modal-box { max-width:640px }` (App.css 2573) `.modal-shell--lg/xl` ile aynı özgüllükte ve SONRA
  geldiğinden lg/xl/full boyutları HİÇ uygulanmıyordu (UserPushSettings xl, AlertTeamCell lg, Uact lg de 640px'e
  sıkışıyordu). `.modal-box.modal-shell--*` bileşik seçiciyle düzeltildi — o modallar artık tasarlanan genişlikte.
- Tablo geniş ekranda sığmıyordu (1221 > 1078 px): konu sütunu esnek/ellipsis, iç boşluk dar → 1063 = 1063.
- progress-guard kapısı elle çizilen hata-sınıfı çubuğunu yakaladı → ProgressBar (`--pg-fill` token).
- Tarayıcıda: 30 kayıt / KPI / 7 günlük çubuk / takım-alıcı-alan tabloları; satır → detay (zincir 3, iframe, "Alarmı aç");
  geri → Sistem Sağlığı; SMTP kartı CTA → görünüm; dashboard derin bağlantı `view=smtp&m_status=FAILED&m_range=24h`
  → "Son 24 saat" + Başarısız KPI aktif.
Kapılar: backend `clean verify` (bkz. çıktı) — tek beklenen kırmızı `IdentityLeakGuardTest` (takipsiz kullanıcı dosyaları);
yeni testler 9 (servis) + 4 (kontrolör). Frontend lint 0 hata / 2123 test / kapsam (SmtpLogView %99,5) / build yeşil.
→ **REGRESYON YOK**.

## Ek — kırk sekizinci tur (2026-09-19, sürüm sonrası — Sistem Sağlığı'ndaki her bölüm her kademeye açık, `v20.71.1..HEAD`)

Kapsam: backend (PermissionCatalog USER/TEAM_ADMIN `release_history.read`; PermissionService PolicyUpgrade ×2 — mevcut
kurulumlarda `updated_by=system` satırlarını çevirir, yönetici kararı ezilmez; SystemController 5 salt-okuma uçtan
`requireSystemRead` kalktı: user-activity, user-activity/series, user-activity/user/{u}, db-analytics, http-metrics
endpoints/series) + frontend (SystemHealth `canViewUserActivity=true`, `canAck` → UserActivityPanel).
Denetim odakları:
- YAZMA uçları değişmedi: anomali onayı, oturum sonlandırma, heartbeat/lock, dağıtım kaydı yazma admin/AUDIT'te; panel
  onay düğmeleri `canAck` ile gizlenir (403'e koşmasın).
- **Sızıntı kapısı:** `/user-activity` payload'ı `employee_id` (sicil) taşıyordu; arayüz kapsamlı kullanıcıda "maskeli"
  gösteriyordu ama JSON'da duruyordu. Uç her kademeye açılınca sunucuda silinir (`maskEmployeeIds`, önbellekli özetin
  kopyası) — TeamBadge/üye listesi beyaz-liste ilkesiyle aynı. Test: USER payload'ında anahtar YOK, ADMIN'de var.
- SystemControllerTest: iki "USER 403" testi 200'e çevrildi + ack 403 kalır + sicil maskesi.
- Tarayıcıda: admin oturumunda 6 bölüm (Sistem · HTTP · JVM/CPU · Veritabanı · Kullanıcı/Oturum · Sürüm & Dağıtım).
  Not: yerelde USER oturumu açılmadı (tek hesap); kapı davranışı WebMvc testleriyle pinli.
Kapılar: bkz. 49. tur (ortak koşum).
→ **REGRESYON YOK**.

## Ek — kırk dokuzuncu tur (2026-09-19, sürüm sonrası — Sistem Sağlığı: Webhook Push kartı + Webhook Push Gönderim Logu sayfası, `v20.71.1..HEAD`)

Kapsam: backend (PushLogQueryService + PushLogController yeni; UserPushDeliveryRepository gövdesiz pencere projeksiyonu +
batch sorgusu; UserPushService.requeue; AuditEventCatalog USER_PUSH_REQUEUE) + frontend (PushLogView yeni; SystemHealth
Webhook Push kartı → `view=push`; `p_` URL öneki; api.admin.pushLog; i18n TR/EN; `.pl-trigger-*`, `.sml-pre`).
Denetim odakları:
- Kart SMTP kartıyla aynı iskelet: 24 sa / 7 gün / 30 gün pilleri, gönderilen/başarısız/kuyrukta/oran; oran <%90 alarm
  rengi. Veri `summary` ucundan (60 sn önbellek); görünürken 60 sn'de bir tazelenir.
- Sayfa: SMTP sayfasının sözlüğü (`.sml-*`) — KPI 9 (kuyrukta ve engellendi ayrı), zaman çizelgesi (sent/failed/
  pending/skipped), takım · hata sınıfı · alıcı · izleme · seviye kırılımları, sunucu taraflı arama/sıralama/sayfalama,
  detay (mesaj, ham yanıt, aynı batch'in alıcıları, "Alarmı aç"), CSV, yeniden kuyruğa alma.
- Durum sözlüğü: SENT/FAILED/PENDING/BLOCKED(CIRCUIT_OPEN, RATE_LIMITED)/SKIPPED(SKIPPED_*); hata sınıfı HTTP koduna
  göre (AUTH 401/403 · NOT_FOUND 404 · RATE 429 · SERVER 5xx · CLIENT 4xx), kodsuz CONFIG (URL ayarlanmamış / gövde
  kurulamadı — tarayıcıda "Diğer" çıkınca eklendi) · TIMEOUT · CONNECT · OTHER.
- Tetikleyici sözlüğü SMTP'den FARKLI (OPEN/RE_ALERT/RESOLVE/…): ilk sürüm SMTP listesini kullanıyordu → satırlarda ham
  "RE_ALERT" çıktı; mevcut `userpush.trigger.*` etiketleri + özetten gelen bilinmeyenler süzgeçte.
- Kapsam: push satırı alan adı taşımaz → görüş takımları + kullanıcının KENDİ satırları (username); kontrolör testi pinler.
- Yeniden kuyruk: FAILED/BLOCKED → PENDING, deneme sayacı 0, tek başına batch (eski batch arkadaşları yeniden gitmesin),
  worker hemen; kanal kapalıysa CHANNEL_DISABLED (409); yalnız global admin + system_health.actions; denetim
  USER_PUSH_REQUEUE (auditFormat `_REQUEUE` fiili).
- Tarayıcıda: kart 0/23 %0 (yerelde webhook URL'si yok → hepsi FAILED, gerçek veri); sayfa 34 kayıt, 9 KPI, günlük
  çubuklar, kırılımlar, satır detayı; deep-link `view=push`.
Kapılar: backend `clean verify` (bkz. çıktı) — tek beklenen kırmızı `IdentityLeakGuardTest` (takipsiz kullanıcı
dosyaları); yeni testler 5 (servis) + 3 (kontrolör). Frontend lint 0 hata / 2127 test / kapsam (68 dosya) / build yeşil.
→ **REGRESYON YOK**.

## Ek — ellinci tur (2026-09-19, sürüm sonrası — Genel Bakış sertifika kartı zengin görünümü (8 blok) + Kompakt/Zengin anahtarı, `v20.71.1..HEAD`)

Kapsam: backend (CertificateCardExtrasService + CertificateCardExtrasController `/api/certificates/card-extras`;
UptimeCheckRepository saatlik kova sorgusu; MaintenanceService windowInfoByTarget/activeEndAt) + frontend
(CertificateCardExtras yeni; CertificateCard `extra` prop; RenewalPlanModal Vade Takvimi'nden çıkarılıp ortaklaştı;
App.jsx veri + anahtar + eylemler; i18n TR/EN `ccx.*`; CSS `.ccx-*`). Kullanıcı seçimi 8/8 + "Zengin + kompakt anahtarı".
Denetim odakları:
- Ana liste (`/api/certificates`, `cert-latest` önbelleği, 50 testli kontrolör) DOKUNULMADI: zenginleştirme ayrı uçtan
  gelir, kart alan adıyla birleştirir; uç düşerse kart bugünkü hâlinde kalır (extra=undefined).
- Tek geçiş/60 sn önbellek (tüm envanter): latest_checks 1 tarama, açık alarmlar 1, uptime saatlik kova 1 GROUP BY +
  son kontrol 1 (LATERAL), bakım pencereleri 1, sağlık kuralları CPU (eşik BİR kez, `thresholdDays`). Kapsam
  `/certificates` kuralı (görüş takımlarının envanteri; global hepsi).
- Sağlık: FAIL satırlar (expiry hariç) + "ok/evaluated"; kritik anahtarlar (revocation/trust/sanMatch/chain) kırmızı;
  tıklama → sertifika modalı Sağlık sekmesi (`_tab: 'health'`). Açık alarm: en yüksek seviye + sayı + hepsi onaylı mı;
  tıklama → Alarm Geçmişi'nde ilk olay. Erişilebilirlik: 24 sa %, son ms, 24 saatlik kova sparkline (boş saat null).
  Değişim: pin uyuşmazlığı kırmızı (Onayla YOK — önce incelenir), 7 gün içinde onaysız değişim turuncu + Onayla
  (mevcut confirm-renewal ucu). Plan: gecikmiş (plan tarihi geçti, sertifika plandan sonra verilmedi) / tamam /
  bekliyor; 30 gün altı plansız → "Yenileme planla" (ortak modal, forecast plan ucu). Paylaşılan: aynı parmak izi +
  SAN sayısı (hover listesi). Bakım: hedefe özel pencere, yoksa "tüm izlemeler" penceresi; aktif → bitişe kadar, 24 sa
  içinde başlayacak → "Bakım hh:mm". Kontaklar: 4 alan; hepsi boşsa "Sorumlu kişi yok" hijyen uyarısı.
- Tüm zengin blok tıklamaları `stopPropagation` — aksi halde kart detayı da açılırdı (test pinler).
- Anahtar: süzgeç satırında Kompakt/Zengin (localStorage `dash-card-mode`, varsayılan Zengin); Kompakt = bugünkü kart.
- Tarayıcıda: 11 kart zengin (Sağlık 14/14 temiz, %100 erişilebilirlik · ms, SAN, "Sorumlu kişi yok"); anahtar
  Kompakt ↔ Zengin ve localStorage doğrulandı. Anahtar düğmesi ilk ekranda koyu dolguyla çıktı → yüzey + kenarlık.
Kapılar: backend `clean verify` (bkz. çıktı) — tek beklenen kırmızı `IdentityLeakGuardTest`; yeni test 5 (servis).
Frontend lint 0 hata / 2130 test / kapsam / build yeşil.
→ **REGRESYON YOK**.

## Ek — elli birinci tur (2026-09-19, sürüm sonrası — Genel Bakış kartı "şu an" şeridi: son erişim + son alarm, `v20.71.1..HEAD`)

Kapsam: backend (AlertEventRepository.findLatestPerDomain — alan başına EN SON alarm, açık/kapalı; card-extras `last_alert`
bloğu) + frontend (CertificateLiveStrip yeni; CertificateCard `live` prop — footer'da eylem düğmelerinin karşısında; App
her iki görünümde geçirir; i18n `live.*`; CSS `.cc-live*`).
Denetim odakları:
- Şerit kompakt görünümde de çizilir (tek satır); zengin bloktan bağımsız. Yalnız sorunda renkli (erişilemiyor / açık
  alarm → kırmızı), aksi hâlde gri — 100 kartta gürültü yapmasın.
- Erişilebilirlik saatlik olduğu için (43. tur) "son kontrol X dk önce" tooltip'te; şerit metni ilk denemede eylem
  düğmelerini alt satıra itti (footer flex-wrap) → `:has(.cc-live)` ile çip kabı esner + nowrap, metin kısaltıldı
  ("Ayakta 16ms · alarm 17g ✓"), font .68em; ölçüm: 155/155 px, eylemlerle aynı satır (y=692/693).
- Tıklama → Durum İzleme `?q=<alan>` (UptimePage arama paramı); kart onClick'i yutulur.
- Test: ayakta+çözülmüş gri / erişilemiyor+açık kırmızı, tooltip, navigasyon, kompaktta da var.
Kapılar: backend `clean verify` (bkz. çıktı) — tek beklenen kırmızı `IdentityLeakGuardTest`; frontend lint 0 hata / 2131
test / kapsam / build yeşil.
→ **REGRESYON YOK**.

## Ek — elli ikinci tur (2026-09-19, sürüm sonrası — Kompakt/Zengin anahtarı "Genel Bakış" başlığının yanına, `v20.71.1..HEAD`)

Kapsam: yalnız frontend (App.jsx + App.css). Süzgeç satırındaki tek düğme kaldırıldı; başlığın yanında iki durumlu segment
(Kompakt | Zengin, aktif olan vurgulu, `aria-pressed`). Aynı state/localStorage (`dash-card-mode`).
Denetim odakları: başlık `.dashboard-header > h2` kuralının dışına çıkınca alt boşluk geri geldi (dikey merkez 349 vs
359 px) → `.dashboard-title-row h2 { margin: 0 }`; ölçüm 349/349. Segment tıklamaları kart sayısını (11 ↔ 0 zengin blok)
ve tercihi değiştiriyor; süzgeç satırında eski düğme yok.
Kapılar: frontend lint 0 hata / 2131 test / kapsam / build yeşil; backend DEĞİŞMEDİ.
→ **REGRESYON YOK**.

## Ek — elli üçüncü tur (2026-09-19, sürüm sonrası — envanter formu Kaydet'te kayma yok + ilk kontrol süresi, `v20.72.0..HEAD`)

Kaynak: üretim ekran görüntüsü — "Domain Düzenle" formunda Kaydet'e basınca alt bar kayıyor; ardından "İlk kontrol
koşuyor…" bir dakikaya yakın sürüyor.
Kapsam: frontend (InventoryFormModal, CheckRunningStrip `label`, App.css) + backend (PageCheckerService.scanMixedContent,
CertificateAppLayerProbe).
Bulgular ve düzeltmeler:
- Alt bar: "Kaydet" → "Kaydediliyor…" → "İlk kontrol koşuyor…" (84→134→179 px) + araya giren "Kontrol ediliyor… N sn"
  şeridi satırı 723 px'e taşırıyor (iç genişlik 684); ölçüm: Test et x=343→302 (modal dışına), her düğme kaydı.
  → Düğme metinleri SABİT (Kaydet/Test et/Çalıştır; kilit + `aria-busy`), evre BAŞLIKTAKİ şeritte
  (CertificateModal deseni; `CheckRunningStrip label`). Ölçüm: iki evrede de x/w/y birebir aynı (343/669/784/867/943),
  kaydırma konumu korunur (scrollTop 300→300).
- Doğrulama hatası: `.alert-msg` gövde ile alt bar arasına giriyor (grid küçülüyor) + başa kaydırma → iki kayma; ayrıca
  hata YEŞİL kutudaydı. → AlertBanner(danger) alt barın üstünde YÜZER (`.modal-wide-float`, × ile kapanır, alan
  değişince gider), scrollTo yok; yüzerken kaydırma ipucu gizli (üst üste binmesin).
- Süre: `health/refresh` karışık içerik satırı için `pageChecker.test(...)` çağırıyordu = ana sayfanın TÜM alt
  kaynaklarını (5'li, 8 sn/kaynak, 60 sn deadline) doğruluyordu; pod'dan erişilemeyen CDN'lerde deadline'a dayanıyor.
  Karar URL şemasından verildiği için → `scanMixedContent` (tek GET, alt kaynak isteği yok). Sayfa izlemesinin
  `test`/`check` yolu DEĞİŞMEDİ.
Testler: InventoryFormModal.test (+3: iki evrede metin/şerit/aria-busy sabit; yüzen hata × ve alan değişimi; Test et
sabit), PageCheckerServiceTest.scanMixedContent_doesNotFetchSubresources (asılı img'li sayfa <1.5 sn),
CertificateAppLayerProbeTest test→scanMixedContent.
Kapılar: frontend lint 0 hata / 2134 test / kapsam / build yeşil; backend `clean verify` (bkz. çıktı) — tek beklenen
kırmızı `IdentityLeakGuardTest` (takipsiz yerel dosyalar).
→ **REGRESYON YOK**.

## Ek — elli dördüncü tur (2026-09-19, sürüm sonrası — "Kaydet" mekanizması dokuz izleme formunda denetlendi, `v20.72.0..HEAD`)

Soru: 53. turdaki iki bulgu (alt bar kayması + uzun ilk kontrol) başka izleme tipinde de var mı?
Denetim (kod + tarayıcı ölçümü):
- SÜRE: dokuz izleme formunun Kaydet'i (`POST/PUT /api/monitoring/{http,ping,dns,port,keyword,page,domain,pagespeed,
  scripted}`) yalnız DB yazar; kaydetme sonrası senkron ağ kontrolü YOK (envantere özgüydü). Tek istisna Sentetik: kayıt
  sonrası k6 doğrulama koşumu (`runSmokeCheck`) — bilinçli tasarım, kaydı bloklamaz, "Kapat" açık, banner bunu söylüyor.
  → Süre sorunu yalnız sertifika envanterindeydi (53. turda kapatıldı).
- KAYMA: dokuz formda da aynı sınıf, daha küçük ölçekte: "Kaydet" → "..." (84→48 px) Sil/İptal'i 36 px sağa kaydırıyor
  (HTTP formunda ölçüldü: Sil x=781→817); "Test et" → "Test ediliyor…" genişliyor (solda, `margin-right:auto` — başkasını
  itmiyor). → Düğme metinleri SABİT (+`aria-busy`), evre başlıktaki `CheckRunningStrip` şeridinde
  (`.modal-icon-hdr-running`, "Kaydediliyor… / Test ediliyor… N sn"); yeni i18n `mon.saving`. HTTP ölçümü: üç durumda da
  düğme x/w birebir (361/781/865/940). Sentetik'te test düğmesi spinner↔ikon takası kaldı (aynı 14 px, genişlik değişmez).
- Kapı: `monitorFormBusyLabels.test.js` — dizini listeleyip her `*MonitorPage.jsx`'te ternary'li Kaydet/Test etiketi YOK,
  `aria-busy` VAR, şerit başlıkta VAR / alt barda YOK; mutasyonla ısırdığı doğrulandı (`'...'` geri konunca kırmızı).
- Uygulama notu: toplu düzenleme script'inde heredoc `\1` kaçışı düştü → `onClick={save}` yerine 0x01 baytı yazıldı,
  dokuz sayfa çöktü (ErrorBoundary). Bayt taraması + `onClick={save}` sayımıyla geri kondu; `git checkout` ile bir dosyanın
  tüm sweep'i yanlışlıkla silindi, yeniden uygulandı ve diff'i diğer sekizle karşılaştırıldı.
Kapılar: frontend lint 0 hata / 2144 test / kapsam / build yeşil; backend DEĞİŞMEDİ.
→ **REGRESYON YOK**.

## Ek — elli beşinci tur (2026-09-20, sürüm sonrası — Yönetim Paneli uçtan uca denetim + 12 zenginleştirme, `v20.72.1..HEAD`)

Kapsam: 6 commit (fb2719ed, d6536196, 987bdef8, 6ac8c983, 2401ea0a, +6/6). Denetim bulguları ve seçilen 12 özellik:
- KUSURLAR: süzgeç çubuğunda `SearchableSelect` %100 genişlik → her süzgeç ayrı satır (Eskalasyon/Kullanıcılar/Denetim)
  → `.audit-filters > .ss-wrap` 240 px; alt sekme + süzgeçler URL'de (`g_*`, sekme geçişinde temizlenir, yetkisiz g_tab
  varsayılana iner); eşik Kaydet metni sabit.
- TIER EŞİKLERİ: `alert_thresholds.tier` (null = varsayılan); `ThresholdResolution` tek okuma; alarm seviyesi, kart
  seviyesi/istatistik, sağlık, kart ekleri, bugün, yenileme tahmini alan tier'ıyla. `findFirstByActiveTrue` artık
  yalnız varsayılan satır (tier satırı global sanılmaz — kapı ThresholdResolutionTest). Önizleme: kapsam + mevcut/önerilen
  sayım + örnekler. BULGU: eşik güncellemesi cache'e dokunmuyordu → kartlar TTL'e kadar eski seviyede;
  `afterThresholdChange` türetilmiş cache'leri boşaltır. Tarayıcı: Tier 1 satırı (60/30/14) → 34/46 gün kalanlar
  `warning`, silinince `valid`.
- DEĞİŞİKLİK GEÇMİŞİ: `/admin/history?resource=` (TEAM/ESCALATION_CONTACT kapsamlı; USER/ALERT_THRESHOLD global admin);
  JSON detail'den ad çekimi (WEEKLY_REPORT_ACCESS gövdesi ad olarak görünüyordu → düzeltildi).
- KİM BİLGİLENDİRİLİR: `simulateRecipients` gerçek gönderimle aynı kararlar; webhook testi → BULGU: notification_logs
  `alert_event_id NOT NULL` → test satırı düşüyordu (log'da görüldü) → sentinel 0 (rapor/anomali mailleriyle aynı desen),
  kapı testte pinli. Son teslimat sütunu `findLatestWebhookPerRecipient`.
- TAKIMLAR: tek geçiş sayaçlar (envanter-türevi teamId=null izleme sayılmaz), etki önizleme, `moveAll` (üyelik yeniden
  yazımı, varsayılan grup bayrağı düşer), üye ekle/çıkar (son takım çıkarılamaz). Tarayıcı doğrulaması sırasında
  script'in "Sil" düğmesini modalda yakalaması yerel boş `SmokeTeam-NoLeader` takımını sildi (yerel test verisi, bağlı
  varlık yoktu; üretim etkisi yok) — akışın kendisi doğru çalıştı (boş takım → doğrudan sil).
- KULLANICILAR: son giriş (`last_login_at` zaten JSON'daydı), uyuyan süzgeci (`findFilteredDormant`, ISO-UTC leksikografik),
  toplu işlem (PUT gövdesi `applyUserUpdate`'e ayrıldı; her kullanıcı aynı güvenlik zinciri — kendini pasifleştirme /
  son ADMIN testte pinli), CSV, detay kartı, özet şeridi (uyarı çipi → süzgeçli sekme; remount anahtarıyla aynı sekmede de).
Uygulama tuzakları (bu turda tekrar ısırdı): heredoc `\1`/`\d` kaçış çöküşü (Java regex ve JSX'te 0x01/illegal escape),
`git checkout` ile bir dosyanın sweep'ini silme (yeniden uygulandı), CRLF dosyada `\n` sonlu replace eşleşmedi (regex
`\r?\n`), tel biçimi SNAKE_CASE (eşik testleri camelCase gönderiyordu — alanlar sessizce yok sayılıyor; düzeltildi).
Kapılar: frontend lint 0 hata / test / kapsam / build (bkz. çıktı); backend `clean verify` (bkz. çıktı) — tek beklenen kırmızı
`IdentityLeakGuardTest` (takipsiz yerel dosyalar).
→ **REGRESYON YOK**.

### 55. tur eki — Değişiklik Geçmişi v2 (kullanıcı bildirimi)

Bildirim: "Kullanıcılar geçmişi sadece loginleri gösteriyor, sayfalama yok, görünüm hoş değil." Kök neden: giriş/çıkış
(LOGIN_SUCCESS/FAILED/LOGOUT), tur ve push olayları da `resource_type=USER` yazıyor; ham okuma giriş yağmuruydu.
Düzeltme: kaynak başına yönetimsel olay beyaz listesi (kapı: AdminHistoryServiceTest — LOGIN_* sorguya girmez), sunucu
sayfalama (global: DB sayfası + gerçek toplam; kapsamlı: pencere + bellek), olay türü süzgeci; arayüz Denetim Kaydı
dağarcığıyla tablo (özet fark "Takımlar: 5, 9 → 5", satır açılınca tam fark), çipler, PaginationBar. Tarayıcı: 43 kayıt /
2 sayfa, giriş satırı yok. Kapılar: frontend lint 0 hata / 2175 test / kapsam / build; backend AdminHistoryServiceTest +
AdminControllerTest yeşil (tam `clean verify` bu turun ana koşumunda). → REGRESYON YOK.

## Ek — elli altıncı tur (2026-09-20, sürüm sonrası — kullanıcı bildirimleri toplu: Kullanıcı Etkinliği dizini, yönetim araç çubukları, şema detayı, push/aktivite logu, takım toplu işlem, `v20.73.0..HEAD`)

Kapsam (13 kullanıcı bildirimi, hepsi tarayıcıda doğrulandı):
- KULLANICI ETKİNLİĞİ: "1 Aktif oturum" yalnız oturumdakileri açıyordu → `UserDirectoryModal` (login_status ⊕ active_users;
  çevrimiçi başta, boşta süresine göre; e-posta / rol+org rolü / takım+ek takımlar / LDAP-Yerel / son görülme / son giriş /
  oluşturulma / tur / hesap; görünüm-tur-takım-rol-kaynak-hesap süzgeçleri + metin arama; PaginationBar; KebabMenu:
  Detay, Kullanıcı yönetiminde aç (g_tab=users&g_q), e-posta, ad kopyala, Oturumu sonlandır, Turu sıfırla, Kilidi aç; CSV).
  "Turu tamamlayan" kartı aynı dizini tour=completed ile açar. Backend: `login_status` satırları e-posta/org_role/team_id/
  team_ids/employee_id/created_at/last_seen_at/permanent_lock/title/department/tour_status/tour_at taşır; `eventRows` ve
  anomali `recent` aktörden ad/rol/takım/kaynak çözer (büyük-küçük harf duyarsız); `maskEmployeeIds` login_status'u da
  maskeler (SystemControllerTest pinli). KPI modalları (giriş/başarısız/anomali/tekil kullanıcı) takım + tarayıcı sütunu;
  bölüm 06/09 tablolarında takım; oturum detayında Hesap bölümü (oluşturulma, durum, son görülme, tur), ek takımlar,
  "Tam kullanıcı kartı" (UserDetailPanel yığılı modal), "Kullanıcı yönetiminde aç", "Turu sıfırla". Sayfa kullanımı Pay
  hücresi: mutlak etiket kaldırıldı (çubuk + yüzde tek satır; Son görülme ile çakışmaz).
- KULLANICI YÖNETİMİ: `.audit-filters` yerine proje standardı `.invtb` araç çubuğu (arama + "Süzgeçler" paneli + sayaç)
  ve PaginationBar (1 tabanlı ↔ sunucu 0 tabanlı; boyut 25/50/100/200). Panel etkin süzgeçle açık başlar (derin bağlantı).
- BİLDİRİM GRUPLARI: takım combobox'ı başlıkta "Grup Ekle"nin solunda aynı hizada (varsayılan tüm takımlar); "N adres" çipi
  üzerine gelince/odaklanınca adres listesi (portal — tablo overflow'u kırpıyordu, düzeltildi); Oluşturan / güncelleyen
  sütunu (DTO'ya created_by/created_by_name/updated_by eklendi; yalnız oluşturulmuşta ikinci satır çizilmez).
- SQL PLAYGROUND ŞEMA DETAYI: elle modal → ModalShell (xl) + KPI şeridi (satır/boyut/kolon/indeks/kısıt/trigger/son
  değişim) + sekmeler: Genel bakış (Kimlik / Kullanım — seq/idx tarama çubuğu ProgressBar / Zaman & Aktivite), Kolonlar
  (PK/FK/UQ/IDX/ID rozetleri, pg_stats NULL % · ayrık · genişlik, açıklama), Bütünlük & indeksler (yapısal kısıt: kolonlar,
  hedef, ON DELETE; indeks kolonları + tarama + boyut, "kullanılmıyor" rozeti), İlişkiler (giden FK, bu tabloya bakanlar,
  *_id çıkarımı — tablo adları başka detayı açar). Backend `enrichTableDetails` (her sorgu safeQuery; katalog izni yoksa
  alan boş kalır, ekran düşmez — testte pg_stats hatası yutulur). Footer: Sorguya koy / Adı kopyala.
- WEBHOOK PUSH LOGU: mini kırılım tabloları taşıyordu → sabit yerleşim, ad sütunu kırılır, sayısal sütunlar dar/sağa,
  başlık "Ad"; "Hata sınıfları" paneli kaldırıldı (bilgi taşımıyordu; süzgeç açılır listesi kalır); takım/alıcı rozetine
  tıklama satır detayını AÇMIYOR (`.sml-stop` sarmalayıcı; SmtpLogView kardeş satırı da). Kapı: PushLogView testi.
- AKTİVİTE LOGU: satır adı ve "İzlemeye git" düğmesi ilgili izlemeye gider (`activityTarget`: CERT→dashboard ?q, UPTIME→
  uptime ?q, diğerleri ?monitor=id; scripted param taşımaz); özet kalemleri (toplam/başarılı/uyarı/hata) tıklanınca duruma
  süzer (astatus URL'de); satırda ve detayda takım rozeti (team_id zaten DTO'daydı; TeamDirectory'den ad).
- TAKIM YÖNETİMİ: `POST /admin/teams/bulk` (activate/deactivate/set_manager/weekly_*; her takım `applyTeamUpdate` — PUT
  ile aynı kapsam/izin zinciri + TEAM_UPDATE farkı; özet TEAM_BULK_UPDATE, katalog + geçmiş beyaz listesi; kapı:
  AdminControllerTest.bulkTeams — kapsam dışı satır düşer, diğerleri sürer). Arayüz: seçim kutuları (yalnız düzenlenebilir
  satır), toplu çubuk (aktif/pasif, haftalık e-posta, müdür ata/temizle), `.invtb` araç çubuğu — arama ad/e-posta/lider/
  müdür adıyla; süzgeçler durum / müdür / açık alarm (stats.open_alerts) / lider yok / haftalık; URL g_*; PaginationBar.
Uygulama notları: vitest coverage koşusu iki kez OOM (worker "Fatal process out of memory") — `NODE_OPTIONS=--max-old-
space-size=6144` + `--maxWorkers=2` ile yeşil (2204 test); progress-guard kapısı elle yazılmış tarama çubuğunu yakaladı →
ProgressBar. Kapılar: frontend lint 0 hata / test:coverage / coverage:floor / build; backend `clean verify` 3912 test — tek
kırmızı `IdentityLeakGuardTest` (dosya listesi okundu: yalnız bilinen iki takipsiz dosya). → **REGRESYON YOK**.

## Ek — elli yedinci tur (2026-09-20, sürüm sonrası — bildirim grubu geçmişi sayfalama + giriş KPI listesi v2, `v20.73.0..HEAD`)

- BİLDİRİM GRUPLARI GEÇMİŞİ (kullanıcı bildirimi "paging yapısında yapalım"): `NotificationGroupHistoryService.page(scope,
  groupId, page, size)` — 1000'lik pencere içinde kapsam süzgeci, sonra sayfa dilimi; `total` süzgeç sonrası sayı,
  `truncated` pencere dolunca. Uç `GET /notification-groups/history?page&size` (eski `limit` yalnız size yoksa boyut);
  yanıt `total/page/size/total_pages`. Arayüz PaginationBar (1 tabanlı ↔ sunucu 0 tabanlı), grup süzgeci / sayfa
  boyutu değişince başa döner. Kapılar: servis (kapsam dışı satır sayılmaz, gizlenen ayrı, boş sayfa) + denetleyici
  (page/size servise geçer) + NotificationGroups testi (page=1 yeniden okuma, boyut 50 → başa). Tarayıcı: 10 kayıt / 1 sayfa.
- GİRİŞ KPI LİSTESİ (kullanıcı bildirimi "login kartı çok basic"): `EventListModal` (giriş / başarısız / anomali kartları)
  — özet çipleri (tümü, tekil kullanıcı, tekil IP, takım, LDAP, başarısız, mesai dışı; başarısız ve mesai dışı
  tıklanınca süzer), süzgeçler (metin: kullanıcı/IP/kuruluş/sebep; takım; sonuç; bayrak), sütunlar zaman / kullanıcı +
  rol + olay türü / takım / kaynak / IP + konum + kuruluş / tarayıcı + OS / sonuç + sebep / bayraklar; sayfalama; CSV;
  kullanıcı → oturum detayı. Dizinde olmayan aktör login_status'tan tamamlanır. Backend eventRows `id / event_type /
  org / user_agent` taşır. Tarayıcı: 28 giriş, mesai dışı çipi 28, sayfalı.
Kapılar: frontend lint 0 hata / 259 test dosyası (coverage `--maxWorkers=2` + 6 GB heap) / kapsam tabanı / build; backend
`clean verify` 3913 test — tek kırmızı `IdentityLeakGuardTest` (yalnız bilinen iki takipsiz dosya). → **REGRESYON YOK**.

## Ek — elli sekizinci tur (2026-09-20, sürüm sonrası — /qa Standard, diff-aware `v20.73.0..v20.74.0`, `v20.74.0..HEAD`)

QA koşusu (gstack `$B`, yerel, giriş yapılmış oturum; 9 sayfa, 12 akış; konsol 0 yeni hata; rapor
`.gstack/qa-reports/qa-report-localhost-2026-09-20.md`). Sağlık 95 → 99.
- ISSUE-001 (high, düzeltildi 53d6a960): Aktivite Logu sertifika satırı ve bildirim kutusu `MonitorRefResolver` Genel Bakış'a
  `q` ile gidiyordu; App `q`'yu yalnız ilk yüklemede okur, canlı `sm:navigate` geçişi `domain` uygular → süzgeç uygulanmıyordu.
  Kapı: ActivityLog testi + InboxServiceTest.certRefUsesDomainParam. Tarayıcı: 11 kart → 1 kart.
- ISSUE-002 (high, düzeltildi 1acb1981 + regresyon testi): oturum detayı `active_users || login_status` seçiyordu; çevrimiçi
  kullanıcıda oluşturulma/tur/kilit/ek takım/user_id kayboluyor, Tur "hiç görmedi" (dizin: tamamladı), Tam kullanıcı kartı /
  Turu sıfırla çizilmiyordu → `detailRecord`: dizin taban, oturum alanları üstüne. Kapı: sessionDetailMerge.regression-1.
- ISSUE-003 (medium, düzeltildi b389a6de + regresyon testi): `atype/astatus/arange/aq` PAGE_STATE_PARAMS'a — bayat süzgeç
  paramı sekme değişiminde başka sekmeye taşınmıyor (`?tab=port&monitor=15`).
- Ertelenen (low): ISSUE-004 şema detayı ilişki bağlantısında sekme "İlişkiler"de kalıyor (`key={table}`), ISSUE-005 Kullanıcılar
  sayacı süzgeçli toplamı "tümü" gibi gösteriyor.
Kapılar: frontend lint 0 hata / test:coverage / kapsam tabanı / build; backend `clean verify` — tek kırmızı `IdentityLeakGuardTest`
(dosya listesi okundu: yalnız bilinen iki takipsiz dosya). → **REGRESYON YOK**.

## Ek — elli dokuzuncu tur (2026-09-20, sürüm sonrası — Kullanıcı Dizini satır düzeni v2, `v20.74.1..HEAD`)

Kullanıcı bildirimi (üretim ekran görüntüsü, 36 kullanıcı): uzun ad "(… Bölümü)" dar Kullanıcı sütununda 5 satıra sarıp
e-postayla birlikte komşu hücrelere giriyordu; sonra "çok karışık" → satır düzeni v2; sonra "Hesap süzgeci panelden kalksın,
kolon üzerinden seçilsin".
- d0b7aa8e: ad parantezsiz (`nameOnly`), departman ve e-posta tek satır + üç nokta. Ölçüm: `td min-width` otomatik yerleşimde
  sayılmıyor (118 px kaldı) → sütun genişliği başlıktan.
- 66c91558: 10 → 8 sütun (Son etkinlik + Son giriş = Etkinlik; Tur + Hesap = Durum), colgroup ile sabit yerleşim, tablo modal
  genişliğine sığar (yatay kaydırma yok; 1 px kenarlık payı). Kullanıcı hücresi: nokta + avatar + ad / kullanıcı adı ·
  departman / e-posta.
- f10b99fc: "İşlemler tıklanmıyor" → KebabMenu portal'ı z-index 900, ModalShell perdesi 2000: menü açılıyor ama perdenin
  ARKASINDA kalıyordu (sayfa tablolarında sorun yoktu). Token `--z-menu` 9600 (modal/Dialog üstü, toast altı); tarayıcıda
  menü ve "Detay" doğrulandı.
- e6cf5457: Hesap seçicisi panelden kaldırıldı; Durum sütun başlığında "Durum ▾" seçicisi; satırdaki hesap rozeti tıklanınca
  o duruma süzer (toggle). Tarayıcıda uzun ad / e-posta / çoklu takım enjekte edilerek doğrulandı.
Kapılar: frontend lint 0 hata / test:coverage / kapsam tabanı / build; backend değişikliği yok (sürüm için `clean verify`
yine koşuldu — tek kırmızı `IdentityLeakGuardTest`, yalnız bilinen iki takipsiz dosya). → **REGRESYON YOK**.

## Ek — altmışıncı tur (2026-09-21, sürüm sonrası — push logu kırılım paneli v2 + Kullanıcı Etkinliği / simülatör düzenlemeleri, `v20.75.0..HEAD`)

Kullanıcı bildirimleri (üretim 1366 px ekran görüntüleri):
- 18244fd9: push/SMTP logu kırılım kartları yan yana 280 px'e düşünce sabit yerleşimli mini tabloda ad sütunu ~10 px kalıyor,
  takım/alıcı/izleme/seviye adları HARF HARF sarıyordu → kartlar alt alta (tam genişlik).
- ae56e978: kırılım paneli v2 — her satırda gönderildi / başarısız / diğer oran çubuğu ve üç tıklanır rakam (Toplam → yalnız
  boyut; Gönderildi → +SENT; Başarısız → +FAILED); tıklama süzgeci uygular ve ana tabloya kaydırır, ikinci tıklama kaldırır;
  başlık daraltılır. İzleme boyutu q (metin arama) ile süzülür. Tarayıcı: n64954 · Başarısız → 13 kayıt, URL p_status=FAILED&p_user.
- 592e441f: Kullanıcı Etkinliği — sayfa kullanımı tablosu altında üç blok (yan yana sıkışma yoktu artık), listelerde yalnız ad
  soyad (bölüm eki / rol pili / kullanıcı adı kaldırıldı; `nameOnly`), hiç giriş yapmayanlar etiket altında, En Çok Login Gelen
  Kaynaklar IP hücresi sade + kullanıcılar ad soyadla; simülatör push alıcıları Kişi / Push grubu / Karar tablosu; eskalasyon
  kişileri listesi standart PaginationBar.
Kapı bulgusu: progress-guard kapısı kırılım çubuğundaki inline `width` desenini yakaladı → 69463eda: genişlikler CSS özel değişkeni (`--w`)
ile (çok parçalı çubuk ProgressBar'a sığmaz). Kapılar HEAD'de yeniden koşuldu.
Kapılar: frontend lint 0 hata / test:coverage / kapsam tabanı / build; backend değişikliği yok (sürüm için `clean verify` yine
koşuldu — tek kırmızı `IdentityLeakGuardTest`, yalnız bilinen iki takipsiz dosya). → **REGRESYON YOK**.

## Ek — altmış birinci tur (2026-09-22, sürüm öncesi — izleme başına kurumsal vekil kipi (HTTP/Keyword/Sayfa + Durum + Page Speed), /qa ×2 düzeltmeleri, `v20.76.0..HEAD`)

Kapsam: 17 commit. Özellik: `ProxyPolicyService` (AUTO=envanter / ON / OFF) → HTTP/Keyword/Sayfa formu + kart rozeti; Durum (uptime) yoklaması
envanter `use_proxy` bayrağını `ProxySettings.openConnectTunnel` ile onurlandırır; Page Speed'e vekil kipi (varsayılan OFF, vekil yolunda faz
probu atlanır). /qa turları: vekil formu 3 düzeltme (eski etkin ipucu, combobox genişliği, simge boşluğu); tam tarama 6 düzeltme (süpürme
kaynaklı ağ kesintisi yeniden başlatma sonrası kapanmıyordu — `outageReconciled`; Warnings 50 kart katlama; EN'de dk/sn ve %100 biçimi;
mobil nav rayı; Genel Bakış ilk yüklemede "Sertifika bulunamadı" yerine yükleniyor).
İmza taraması (diff'e uyan sınıflar):
- **S8 (alan ekleme zinciri) — BULGU, düzeltildi:** `PAGESPEED_FIELDS` snapshot listesinde `useProxy` yoktu → yalnız vekil kipi değişen bir Page
  Speed düzenlemesi Değişiklik Geçmişi'nde boş diff verirdi (listenin kendi yorumundaki tuzak, teyit/kurtarma alanlarıyla aynı sınıf).
  Ayrıca proje geleneği (scripted `use_proxy` örneği) gereği dört tabloya açık idempotent `ADD COLUMN use_proxy VARCHAR(10)` patch'i eklendi;
  tablo adı `pagespeed_monitors` (alt çizgisiz — S8'in "yazım hatası dahil" uyarısı ilk taslakta `page_speed_monitors` yazımını yakaladı).
- **S3 (sınırsız okuma) — sertleştirme:** `openConnectTunnel` başlık tüketme döngüsüne 100 satır tavanı (vekil yönetici yapılandırmasıdır; yine de).
- S4: yeni soket yolu vekile bağlanır, hedef host tünel öncesi `ssrfGuard.validate` ile doğrulanmaya devam eder ✓. S10: tüm `getUseProxy()`
  kullanımları `Boolean.TRUE.equals` / normalize üzerinden ✓. S1: yeni uç yok ✓. S11: yeni fetch/async yok (fold state saf) ✓.
- MonitoringOutageService düzeltmesi (bellek-içi bayrak vs DB): `jdbc-session-vs-startup-clear` öğrenimiyle aynı sınıf — `outageReconciled`
  regresyon testiyle pinlendi (`MonitoringOutageSweepReconcileRegressionTest`).
Baseline (Y/O/N serileri): bu turda değişen dosyalar ilgili sınıfların düzeltilmiş imzalarına dokunmuyor (SsrfGuard yolları, Redirect.NEVER,
sayfalama, outbox); diff-kapsamlı re-check. Kapılar sürüm adımında koşuluyor. → **REGRESYON YOK** (1 S8 bulgusu sürüm öncesi kapatıldı).

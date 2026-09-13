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

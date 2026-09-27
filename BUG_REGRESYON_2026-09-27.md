# BUG REGRESYON + BENZER-BUG TARAMASI — 2026-09-27 (yayın öncesi)

Kapsam: `main` @ `282c6691` (v20.86.0) ÜZERİNDE duran BÜYÜK commit'lenmemiş diff (~2 günlük iş:
tam shadcn/ui yeniden tasarımı + arka uç değişiklikleri). `git diff --stat` = 528 dosya,
+51.440/−45.641. Bu tur o commit'lenmemiş çalışma ağacını tarar (HEAD değil).

Yöntem: `/bug-regresyon` TAM kapsam — (A) bilinen bulguların düzeltilmiş imzası çalışma ağacında
hâlâ yerinde mi, (B) aynı anti-desen yeni/değişen kodda tekrar ediyor mu. Yeni arka uç yüzeyleri
(IssueReport/LoginIssue yorumları, LdapMembership, InventoryVisibility org-geneli görünürlük,
OriginCheckFilter, WeeklyReportService status_counts, SqlPlayground, HttpBodies, SecretMask) ve
ön uç güvenlik/URL yüzeyleri kaynak OKUNARAK doğrulandı. Grep eşleşmesi tek başına bulgu
sayılmadı. Kod DEĞİŞTİRİLMEDİ; test/Maven çalıştırılmadı (kapılar koordinatörde seri koşacak).

**Taban dosyaları.** Komutun adıyla aradığı `bug_denetimi_2026_08.md` ne bellek klasöründe ne
repoda bulundu; taban olarak repodaki önceki turlar kullanıldı:
`BUG_RAPORU_2026-09-23.md` (51 bulgu, 51/51 kapalı), `BUG_REGRESYON_2026-09-23.md` (B1–B7,
F1–F11), `BUG_REGRESYON_2026-09-25.md` (R1–R18). İmza sınıfları `.claude/commands/bug-regresyon.md`
(S1–S17).

> **Kapsam dürüstlüğü.** Bu geçişte paralel imza-tarama ajanları süre içinde dönmedi; rapor ANA
> akışın kaynak-okuyarak DOĞRUDAN doğruladığı yüzeylere dayanır. Şu alanlar bu turda **tam
> taranamadı** ve ayrı bir geçiş gerektirir: (a) ön uç a11y sınıflarının (F1–F11/R5/R14–R17) yeni
> bölünmüş bileşenlerdeki kardeşleri; (b) e-posta yeniden tasarımının (`service/mail/` yeni paket,
> `EmailNotificationService` −4341/+... satır) HTML/düz-metin paritesi + `esc()` (S17) tam sözleşmesi;
> (c) tüm 411 controller'ın `@PathVariable` S1 kapsam taraması. Aşağıdaki "doğrulanamadı" bölümüne
> bakınız.

---

## (A) Baseline re-check — DIFF KAYNAKLI REGRESYON YOK; iki 09-23 düzeltmesi FİİLEN ÇALIŞMIYOR

Commit'lenmemiş diff bilinen hiçbir düzeltmeyi geri almamış. Ancak arka uç baseline ajanı,
09-23'te "kapalı" işaretlenen **iki maddenin düzeltmesinin fiilen etkisiz/zararlı** olduğunu
buldu (diff kaynaklı değil, o günden beri böyle): **O1** (@Transactional self-invocation → hiç
devreye girmiyor) ve **O12** (düzeltmenin kendisi bir canlılık kusuru üretiyor olabilir). İkisi de
aşağıda (B) bölümünde bulgu olarak listelendi. Ayrıca **O15 kısmen açık** (N+1 sürüyor). Geri kalan
tüm baseline maddeleri (K1, Y1–Y12, O2–O11, O14, O21–O26, D1–D12, B1–B7, R1–R9, ISSUE-001)
kaynakta okunarak KAPALI doğrulandı — bazıları farklı yoldan:
- **O24 (esc tek-tırnak):** `escHtml` silindi; tüm kaçış `mail/MailKit.esc`'e taşındı (`'`→`&#39;`),
  href'ler çift tırnakta, link'e yalnız http/https çevriliyor.
- **O25:** `setText(text, html)` — metin yoksa `MailKit.plainTextFor` → tüm sendHtml mailleri
  multipart (fırtına dâhil).
- **Gövde tavanları gevşetilmedi:** `HttpBodies` yalnız EKLEME (süre bekçisi); `readCapped` tavan
  aşımında hâlâ istisna; `PageFetchCore` 2MB/10× sıkıştırma-bombası koruması değişmedi.
- **Kapı testleri:** `RepositoryWriteTransactionGuardTest`, `UserPushOutboxDrainGateTest`,
  `NoBareLocalNowInTestsTest`, `RetentionColumnExistsTest`, `ResolvedMailContextKeysTest`,
  `PushMessageContractTest` değişmedi; `OrgCalendarDayGateTest` ve `IdentityLeakGuardTest`
  SIKILAŞTI; silinen tek test `EmailTemplateStandardTest` (garantisi büyük ölçüde
  `EmailResponsiveContractTest`/`EmailSamples`'e devroldu; yalnız "şablon başına lockup bütünlüğü"
  garantisi dolaylıya indi — düşük risk).

Doğrudan okunarak doğrulanan örneklem (arka uç ajanının tablosuyla örtüşür):

| Madde | Durum | Kanıt (çalışma ağacı) |
|---|---|---|
| R6 (uptime %100 koruması) | KAPALI ✓ (farklı yoldan) | `MonitoringController.java:1070` artık `AvailabilityMath.pct(total, up, 2)` tek kuralına devretti; 99,99 koruması util'e taşındı |
| Y3/R2 (giriş IP/kimlik maskeleme) | KAPALI ✓ | `SecretMask.maskEmail/maskEmails` eklendi (log maskesi, P4-1); `redactForForeignReader` org-geneli okuyucuya `created_ip` düşürüyor (`AdminController.java:176`) |
| Y1/Y2 (redirect'te kimlik başlığı) | değişmedi | `PageFetchCore`/`KeywordCheckerService`/`HttpCheckerService` diff'te ama S2/S3 imzaları tam okunamadı → "doğrulanamadı" |
| N3/Y3 (test/izleme timeout tavanı) | KAPALI ✓ + GENİŞLETİLDİ | `MonitoringController.clampTimeoutMs` [1s,120s] artık 5 `/…/test` ucunda + tüm create/update yollarında; HTTP/Keyword test uçlarına oturum başına tek-uçuş kilidi (`TEST_IN_FLIGHT`, :3719) eklendi |
| S9 (tx'siz türetilmiş silme) | KAPALI ✓ | Yeni `IssueReportCommentRepository.deleteByReportId` `@Transactional`+`int` (:60); `LoginIssueService.purge` üç tabloyu tek `@Transactional`'da siliyor (yorum tablosu dâhil, öksüz bırakmıyor, :119-128) |
| S1 domain kapıları | KAPALI ✓ | `MonitoringController.readDomain` (:1277) ve `CertificateController.requireReadableDomain/requireViewableForHealth` org-geneli okumayı YALNIZ salt-okuma uçlarında açıyor; alarm katmanı `access.own()` ile kendi kapsamına kilitli (:1198, :1238) |
| IssueReport sahiplik | KAPALI ✓ | `getMine` = `findById().filter(ownedBy)` (`LoginIssueService.java:167`); başkasının raporu → boş → controller 404 (`IssueReportController.java:248,268`); `ownedBy` büyük/küçük harf duyarsız username eşleşmesi |
| SqlPlayground salt-okuma | KAPALI ✓ | `validateReadOnly`/`sanitize` diff'te DEĞİŞMEDİ; tek değişiklik hata mesajının en özgül nedene inmesi (admin aracı, kasıtlı) |
| S3 gövde tavanı | KAPALI ✓ + GÜÇLENDİ | `HttpBodies` yeni `withDeadline`/`DeadlineInputStream` + `readPreview`/`drain`: okuma tavanına ek olarak SÜRE bekçisi (yavaş-damlatan gövde artık iş parçacığını süresiz tutamaz; `HttpBodyDeadlineGateTest`) |
| Envanter yazma kapıları (R4 sınıfı) | KAPALI ✓ | Org-geneli görünürlük YALNIZ okumayı genişletiyor; `InventoryVisibility` javadoc + kod: yazma kapıları (`requireInventoryWriter`→`SessionScope.canWriteInventory`, `requireTeamScopedAdmin`, `requireNoteDomainWritable`) bu sınıfı HİÇ çağırmıyor |

**Kapı testlerinde gevşetme:** `EmailTemplateStandardTest.java` diff'te SİLİNMİŞ. Yerine
`EmailResponsiveContractTest`, `EmailTypeContentTest`, `EmailGalleryTest`, `EmailSamples` eklenmiş
(e-posta yeniden tasarımı). Silinen kapının garantisinin yenilere tam devrolup devrolmadığı bu
turda doğrulanamadı → "doğrulanamadı" bölümü.

---

## (A2) Ön uç baseline re-check — REGRESYON YOK (paralel ajan, doğrulandı)

Ön uç imza ajanı tüm baseline maddelerini KAPALI buldu (bazıları farklı yoldan). Doğrulanan
örneklem: O13, O16–O20, D3, D6, D8 (izleme/history yüzeyleri); F1–F11 ve R4(ön yüz), R5, R10–R17;
ISSUE-002 (URL ad alanı) ve ISSUE-003 (TodayPanel tekil/çoğul). Öne çıkanlar:
- **F1/R18 (farklı yol):** kart artık `role="button"` değil — "stretched button" deseni
  (`monitoring/MonitorCard.jsx:115-133`, ad `mon.openDetailFor`); iç içe etkileşimli kontrol sorunu
  ortadan kalktı.
- **useEscapeKey silindi → Radix Dialog:** 11 tüketici ModalShell (Radix, non-modal) içine taşındı;
  iç içe Escape yalnız üst katmanı kapatıyor; form-açıkken-alt-kapanmaz garantisi
  `dismissOnEscape={false}` ile korunuyor (`MonitorForm.jsx:52`, `CertificateModal :684`). Tek istisna
  aşağıda **BF3**.
- **Kapı testleri:** `paginationBase.test.js` yeniden yazılmış ve GÜÇLENMİŞ (her `<PaginationBar>`
  etiketi taranıyor). `shadcnOnly.test.js` yeni, taban `total:0`. Silinen a11y iddialarının hepsi
  yeni DOM'a eşdeğer iddiayla taşınmış (gevşeme yok). Küçük kod-kalitesi notu: `rowAccessibleNames`
  kural (2) artık VAKUM (kartlar MonitorCard'a taşındığı için `upt-card` regex'i hiçbir şeyle
  eşleşmiyor) — genel kural (3) onClick+tabIndex kartını yine yakalar, pratik risk düşük; kural
  yeniden yazılmalı.

---

## (B) Yeni bulgular — 1 KRİTİK · 1 YÜKSEK · 8 ORTA · ~20 DÜŞÜK (kardeş/hijyen)

Diff kaynaklı REGRESYON yok. KRİTİK bulgu (BK1) bu diff'in ürünü DEĞİL (kök neden `AuthInterceptor`
ata kod) ama canlı örnekte doğrulandı ve **yeni `OriginCheckFilter` aynı anti-deseni tekrarlıyor**.
YÜKSEK (BO0/O12) ve iki ORTA (BO4/O1, BO5/O15) 09-23'te "kapalı" sayılan düzeltmelerin fiilen
çalışmadığı/eksik kaldığı maddeler — en ciddi sinyaller çünkü kapalı sanılıyorlardı. ORTA'lar ayrıca
BO8 (OCSP/CRL süre tavanı, tek-pod DoS), BO9 (`ug_team_id` mass-assignment, admin-kapısı atlama),
BO6 (`ldap.*` GLOBAL_ONLY değil) ve BO7 (IdentityLeakGuard kör noktası). Bulgular dört paralel
imza ajanı (2 arka uç + 2 ön uç) tarafından kaynak OKUNARAK doğrulandı; DÜŞÜK'ler kardeş/hijyen
kalemleridir.

### KRİTİK

**BK1 · `AuthInterceptor.java:69-70` (+ `OriginCheckFilter.java:72-75`) — matris-parametreli yol
kimlik doğrulama katmanını ve yeni CSRF-Origin katmanını atlıyor: kimliksiz kullanıcı korumalı
`/api/**` okuma uçlarına erişebiliyor** (S1 sınıfı; ata kod, yeni filtre aynı hatayı tekrarlıyor)

- **Bug:** Kapı `String path = req.getRequestURI(); if (!path.startsWith("/api/") ...) return true;`.
  `getRequestURI()` HAM yolu döndürür (matris içeriği kırpılmaz). `/api;x/certificates` gibi bir yol
  `"/api/"` ile BAŞLAMAZ (`"/api;"` ile başlar) → koruyucu erken `true` döner ve auth ATLANIR. Oysa
  DispatcherServlet matris içeriğini eşleşmede kırptığı için isteği yine `/api/certificates`
  handler'ına yönlendirir. Projede Spring Security filtre zinciri ya da `StrictHttpFirewall` yok;
  `/api/**`'in TEK auth kapısı bu interceptor.
- **Neden bug:** `/api/certificates` normalde 401 döner (PUBLIC listesinde değil, oturum ister).
  `/api;x/certificates` ise 200 + tam veri döndürüyor (canlı yerel örnekte doğrulandı). Kimliği
  olmayan bir çağıran şu uçların hepsini kimliksiz okuyabiliyor (yalnız `permissionService.require`
  içeren admin uçları 403'e düşer, yalnız-oturum korumalı uçlar SIZAR): tüm sertifika/envanter
  listesi + CSV dışa aktarma, `/stats`, `/warnings`, `/renewal-advice`, `/me/today`, `/me/inbox`,
  `/teams/directory`, `/users/directory`, ağ durumu. Bu kümedeki veri kurumsal kimlik içeriyor.
  Ayrıca yeni `OriginCheckFilter.shouldNotFilter` aynı `getRequestURI().startsWith("/api/")`
  desenini kullandığından `/api;x/...` bir POST/PUT/DELETE **CSRF Origin doğrulamasını da** atlar.
- **Kapsam sınırı (dürüstlük):** Yazma uçlarının çoğu ayrıca oturum/rol kontrolü yapıp 401/403'e
  düştüğü için birincil etki KİMLİKSİZ VERİ İFŞASI'dır (durum değiştirme değil). Ayrıca prod'da
  NetScaler matris-segmenti normalize EDEBİLİR; ancak uygulama buna güvenmemeli (savunma
  derinliği). Bu nedenle KRİTİK/en az YÜKSEK.
- **Çözüm:**
  - `AuthInterceptor` (ve `OriginCheckFilter`) yol kontrolünü HAM `getRequestURI()` yerine
    normalize edilmiş yolla yapsın: `new org.springframework.web.util.UrlPathHelper()`
    `.getLookupPathForRequest(req)` ya da en azından `req.getServletPath()` (login-help gövde
    filtresi zaten `getServletPath()` kullanıyor — `WebConfig.java:151` — kardeş yüzey doğru).
  - Ek savunma: bir `StrictHttpFirewall`/filtre ile `;` içeren `/api` yollarını kökten reddet.
  - Kapı: "matris-param yolu auth'u atlayamaz" negatif testi (`/api;x/certificates` → 401).

### YÜKSEK

**BO0 (O12) · `MonitoringOutageService.java:205, 443-454, 738` — kurtarma-kuşağı düzeltmesi bir
canlılık kusuru üretiyor: alarm açıkken tek bir DOWN turu yaşandıysa izleme düzelince alarm OTOMATİK
KAPANMIYOR** (S14; 09-23 commit'i, diff kaynaklı DEĞİL — DOĞRULANMALI)

- **Bug (zincir):** `recoveryActive(key) = recoveryGeneration.containsKey(key)` (:205). "Kesinti
  sürüyor" dalı (:454) her DOWN turunda `bumpRecoveryGeneration`'ı `merge` ile çağırıp anahtarı
  haritaya sokuyor ve orada bırakıyor. Anahtarı yalnız `endRecovery` siliyor (yalnız "açık alarm
  yok" dalında ya da geçerli kuşakta). Sonuç: alarm açıkken bir DOWN turu geçtiyse, düzelme anında
  `startRecovery` (:738) "zaten sürüyor" deyip her turda ATLIYOR → aktif kurtarma hiç başlamıyor,
  alarm otomatik kapanmıyor.
- **Neden bug:** Envanter ACCESSIBILITY varsayılanları (`uptime.recovery-checks=3`,
  `recovery-interval-ms=30000`) ile bu VARSAYILAN kurulumda etkili; `recoveryIntervalSeconds`
  ayarlı keyword/ping/port izlemeleri de. Nöbetçi, çözülmüş bir hedef için açık alarmı/olayı
  süresiz görmeye devam eder ("çözüldü" hiç gitmez). Pod yeniden başlayınca harita sıfırlandığından
  sorun maskelenip geri gelir. Eski (bozulmadan önceki) davranış anahtarı `remove` ediyordu.
- **Doğrulanmalı:** Mevcut `MonitoringOutageServiceTest:1006-1050` yalnız `runRecoveryAttempt`'i
  doğrudan çağırıyor; "alarm açıkken DOWN → UP → recovery başlamalı" sırası test EDİLMİYOR.
- **Çözüm:** "Kesinti sürüyor" dalında kuşağı `merge` ile bırakmak yerine, kurtarma penceresi
  dışındayken anahtarı temizle (eski `remove` semantiği) ya da `startRecovery` içinde "gen bump ≠
  aktif kurtarma" ayrımını yap. Sıra testini ekle.

### ORTA

**BO4 (O1) · `SchedulerService.java:3967 → :4029` (`writeResourceBreakdown`) — @Transactional
self-invocation yüzünden TX HİÇ devreye girmiyor; 09-23'te "kapalı" işaretlendi ama fiilen AÇIK**
(S9; diff kaynaklı değil)

- **Bug:** `writeResourceBreakdown` (`:4029`, paket-özel, `@Transactional`) `recheckPageSpeed`
  içinden `this.` ile çağrılıyor (`:3967`) → Spring proxy devreye girmiyor (AspectJ/LTW yok).
  `deleteByMonitorIdAndKeepReason` kendi tx'inde commit ediyor, `saveAll` ayrı tx'te.
- **Neden bug:** `saveAll` düşerse LATEST Kaynak Kırılımı silinmiş kalır, çağıran (`:3860` civarı)
  istisnayı `log.warn`'la yutar → "Kaynak Kırılımı" ekranı bir sonraki başarılı kontrole kadar boş,
  kullanıcıya hata yok. O2/O3 controller metodu olduğu için proxy üzerinden çalışıyor (etkin); yalnız
  bu kardeş self-invocation'la kırık. `RepositoryWriteTransactionGuardTest` yalnız repository
  anotasyonuna baktığı için bunu göremez.
- **Çözüm:** Çağrıyı proxy üzerinden yap (ayrı bir `@Component`/self-inject `@Lazy` referans) ya da
  silme+kaydetmeyi tek transactional dış metoda taşı.

**BO5 (O15) · `WeeklyAvailabilityReportService.java:346-348 + :429` — haftalık rapor N+1 kısmen
duruyor** (S-perf; diff kaynaklı değil)

- **Bug:** `certDaysByDomain` toplu okumaya çevrildi (:331-340) ama `buildTeamReport` döngüsü domain
  başına bir uptime sorgusu atmaya devam ediyor (:346-348) ve `collectWeakAlgo` içinde domain başına
  `latestCheckRepo.findById` N+1'i sürüyor (:429).
- **Neden bug:** 200 domainli takımın haftalık raporu ~400 sorgu; `WeeklyReportKpiService` bunu
  EKRAN isteğinde çağırıyor. Tek pod / 100 eşzamanlı kullanıcı hedefinde CPU/gecikme yükü.
- **Çözüm:** Uptime ve latest okumalarını da toplu (tek sorguda) çek; `collectWeakAlgo`'yu
  `certDaysByDomain` gibi toplu haritadan besle.

**BO6 · `AppSettingsCatalog.java:52-56` — yeni `ldap.manager-attributes` / `ldap.prune-unsupported-teams`
/ `ldap.manager-refresh-hours` ayarları GLOBAL_ONLY değil** (S13/O4 sınıfı; yeni kod — ÜRÜN
KARARIYLA TEYİT EDİLMELİ)

- **Bug:** Müdür bağı bir YETKİLENDİRME girdisi (O4: `managerId` → görüş/yönetim kapsamı). Müdür
  sicilinin okunduğu AD niteliğini (`manager-attributes`) belirleyen ayar `GLOBAL_ONLY` listesinde
  değil; `settings.general/edit` verilmiş global-olmayan bir rol bunu değiştirerek kapsamı dolaylı
  etkileyebilir. Kardeşi `inventory.visible-to-all` doğru şekilde GLOBAL_ONLY'ye eklenmiş.
- **Çözüm:** Üç `ldap.*` anahtarını `AppSettingsCatalog` GLOBAL_ONLY kümesine ekle (ya da ürün
  sahibi kapsam kararını teyit etsin).

**BO7 · `IdentityLeakGuardTest` — büyük/küçük harf uyumsuzluğu 6 yasak terimi ÖLÜ bırakıyor; gerçek
bir kimlik izini kaçırıyor** (kimlik-sızıntısı kuralı; diff kaynaklı değil — bellek kuralı gereği
önemli)

- **Bug:** Test metni `toLowerCase` ile küçültüp `contains` yapıyor; FORBIDDEN listesindeki 6 terim
  BÜYÜK harf içerdiği için hiç eşleşemiyor (ölü kural). Bu kör nokta bugün gerçek bir izi kaçırıyor:
  `backend/src/test/java/com/sitemonitor/service/LdapProvisioningServiceTest.java:244, 259`'da
  gerçek bir takım adı terimi e-posta yerel-adı olarak 2 kez geçiyor (HEAD'de de vardı). *(Terim
  bu raporda yazılmadı; bellek kuralı: gerçek kurum/kişi adı kodda/testte/raporda geçmez.)*
- **Çözüm:** Karşılaştırmayı `toLowerCase`'li terimlerle yap (ya da terimleri normalize et); sonra
  `LdapProvisioningServiceTest`'teki izi yer tutucuya (`example.com` + "Takım A") çevir.

**BO8 · `ChainValidationService.java:247-256` (OCSP) + `:377-385` (CRL) — dış OCSP/CRL okumasında
TOPLAM süre tavanı yok: yavaş-damlatan hedef `certCheckExecutor` iş parçacığını tüketip taramayı
durduruyor** (S3, N1 kardeşi; önceden vardı)

- **Bug:** `HttpURLConnection` yalnız okuma-başına `setReadTimeout` (5/10 sn) + `HttpBodies.readCapped`
  bayt tavanı taşıyor; toplam süre (deadline) yok.
- **Neden bug:** OCSP/CRL URL'leri izlenen sunucunun sunduğu sertifikadan (AIA/CRL-DP) okunuyor;
  `checkRevocation` her sertifika taramasında çalışıyor (`CertificateCheckerService.java:456`).
  Kullanıcının eklediği host, bu URL'leri saniyede 1 bayt akıtan bir sunucuya yönlendirerek iş
  parçacığını OCSP'de ~256KB×5sn, CRL'de ~5MB×10sn tutar; `startNetworkCheck`'in `orTimeout(180s)`'si
  yalnız BEKLEYİŞİ bırakır, iş parçacığı bloke kalır. Her taramada bir iş parçacığı daha sızar →
  `certCheckExecutor` (20/50) doyar → caller-runs ile tarama durur. Prod tek pod: kesinti.
- **Kapı açığı:** `HttpBodyDeadlineGateTest` yalnız `java.net.http`'yi tarıyor; javadoc'u
  ChainValidationService'i "kapatıyor" diye YANLIŞ söylüyor.
- **Çözüm:** Süre dolunca `conn.disconnect()` çağıran bekçi (HttpBodies WATCHDOG deseni) ya da
  `java.net.http` + `HttpBodies.readCapped(HttpResponse…)` yoluna geç; kapıyı `HttpURLConnection`
  `getInputStream` okumalarını da kapsayacak şekilde genişlet.

**BO9 · `AdminController.java:430` (PUT `/inventory/{id}`) + `:285` (POST `/inventory`) — `ug_team_id`
gövdeden kontrolsüz yazılıyor: takım üyesi USER/TEAM_ADMIN, admine özel UG-aktarım kapısını atlayıp
kaydı başka takıma açabiliyor ve o takıma alarm e-postası yönlendirebiliyor** (S1/mass-assignment;
önceden vardı — ön uç güvenlik ajanı doğruladı)

- **Bug:** PUT `existing.setUgTeamId(item.getUgTeamId())` gövde değerini olduğu gibi yazıyor; POST'ta
  da UG alanı mass-assignment temizlik listesinde YOK (`deletedAt`/`domainExpiry`/`renewalPlanned*`/
  `updatedBy…` sıfırlanıyor ama `ugTeamId` değil). Ayrılmış uç `/inventory/{id}/transfer-ug` (:1227)
  ise `requireAdmin` + `inventory.transfer/execute` istiyor.
- **Neden bug:** `requireInventoryWriter` yalnız kaydın KENDİ SY takımına bakar; o takımın bir USER
  üyesi doğrudan API ile `ug_team_id`'ye BAŞKA takımın kimliğini yazabilir. Sonuç: o takım kaydı
  görüntüler (`InventoryVisibility.canRead` UG dalı, `CertificateController.java:185`) ve o takıma
  alarm e-postası gider (`collectTeamEmails(domainTeamId, ugTeamId, …)`). Arayüz UG alanını hep
  `null` gönderdiği için (`InventoryFormModal.jsx:392`) bu yalnız doğrudan API ile yapılır; admine
  özel aktarım kapısı fiilen atlanmış olur.
- **Çözüm:** PUT ve POST'ta `ugTeamId` yalnız `null` (temizle) ya da mevcut değere eşit olabilsin;
  farklı değer için `transfer-ug` ile aynı kapı (`requireAdmin` + `inventory.transfer`). Controller
  testi ekle.

**BF1 · `admin/UserManager.jsx:189-212` (`load`/searchUsers) — seq korumasız fetch yarışı** (S11,
önceden vardı; ön uç ajanı doğruladı)

- **Neden bug:** Tetikleyiciler 300 ms debounce'lu süzgeç (`appliedKey`), sayfa, boyut ve `refresh`.
  "a" için geniş/yavaş istek gider, ardından rol=ADMIN için dar/hızlı istek gider; geç dönen "a"
  yanıtı ADMIN çipinin altında yanlış listeyi ve toplamı yazar. "Sayfayı seç" + toplu devre dışı
  bırakma bu bayat liste üzerinde çalışır (yanlış kullanıcı toplu işleme girebilir). İlk dönen
  `finally` loading'i erken kapatır.
- **Çözüm:** `loadSeq` deseni (kardeş `MonitorChangesConsole`); yanıt ve `finally` seq-koşullu.

**BF2 · `admin/SystemHealth.jsx:186-195` (`loadDbAnalytics`) + `admin/DbAnalyticsPanel.jsx:334` —
seq yok, pencere seçici yüklenirken açık** (S11, önceden vardı; ön uç ajanı doğruladı)

- **Neden bug:** 7 → 30 → 1 gün hızlı tıklanınca 30 günlük ağır yanıt en son gelir; seçici "1 gün"
  gösterirken grafik 30 günlük kovaları saatlik etiketle çizer. Yoklama yok → sonraki tıklamaya
  kadar kalır.
- **Çözüm:** `dbSeq` ya da `{days,data}` sakla; eşleşmiyorsa çizme.

### DÜŞÜK

**BF3 · `MonitorNotes.jsx:103-106` (+ `:185`) — "Escape vazgeçer" hiç çalışmıyor; Escape tüm detay
penceresini kapatıyor** (useEscapeKey→Radix geçişiyle YENİ; ön uç ajanı doğruladı)

- **Neden bug:** Not formu MonitorDetailModal (ModalShell, `dismissOnEscape` varsayılanı true)
  içinde. Radix belge **capture** dinleyicisi React `onKeyDown`'dan önce çalıştığı için form
  içindeki `stopPropagation` işe yaramıyor: 10 izleme detay penceresinin Notlar sekmesinde not
  yazarken Escape formu değil pencereyi kapatıyor, taslak gidiyor. HEAD'de de pencere kapanıyordu;
  yeni eklenen "vazgeç" davranışı ölü.
- **Çözüm:** `SchemaDiagramModal` deseni — form açıkken üst pencereye `dismissOnEscape={false}`
  (context/callback), ya da işleyiciyi belge capture dinleyicisine taşı; diyalog-içi Escape testi.

**Birikmiş S11 kardeşleri (DÜŞÜK, çoğu önceden vardı; ön uç ajanı okuyarak doğruladı):** BF2 ile
aynı sınıf seq/temizlik eksikleri — `AlertHistory.jsx:136-158 loadSummary` (**diff'le yeni**;
`summarySeq`), `useractivity/UserActivityPanel.jsx:94-108 loadTrend`, `SystemHealth.jsx:248-256
loadPushKpi`, `SystemHealth.jsx:142-171` 30 sn poll mutasyon yenilemesini eziyor,
`SystemHealth.jsx:287-301 startScanPoll` (eski yanıt "Şimdi tara"yı kilitli bırakabilir),
`AlertNoisePanel.jsx:65-68`, `health/WeeklyAvailLogsModal.jsx:39-42` (koruma/catch/spinner yok),
`SmtpLogView.jsx:177-185`/`PushLogView.jsx:159-167` (`detailId` değişince `detail` temizlenmiyor →
A'nın "Yeniden gönder"i B için görünebilir), `TeamManager.jsx:317-330 toggleWeekly` (iyimser
güncelleme, catch yok). Tek commit'te seq/temizlik ekiyle kapatılır.

**BD1 · `renewal/guideSteps.js:172` (+ `renewal/GuideParts.jsx:238`) — yenileme kılavuzu bağlantısı
şema beyaz-listesi olmadan href olarak çiziliyor: `javascript:`/`data:` şeması geçer** (S17/URL
sınıfı; kardeş güvenli sürüm `weekly/weeklyLinks.js`)

- **Bug:** `normalizeUrl` `if (/^[a-z][a-z0-9+.-]*:/i.test(s)) return s` — ŞEMALI her değeri
  aynen döndürür (`mailto`, `file`, ama `javascript:`, `data:` de). `GuideLinkCard` bunu
  `<a href={normalizeUrl(link.url)}>` içine koyar; `linkKind` yalnız ikon seçer, href'i kısıtlamaz.
- **Neden DÜŞÜK:** `link.url` yalnız GLOBAL admin yazabiliyor (`GuideLinkController.requireAdmin` =
  `isGlobalAdmin`, sunucu şema doğrulamıyor). En yüksek yetkili aktörün kendi uygulamasına
  `javascript:` sokması = düşük etkili self-XSS; yine de saklanan-XSS savunma derinliği açığı.
  Kardeşi haftalık takip bağlantısı (`weeklyLinks.normaliseLink`) `javascript:`'i açıkça REDDEDİP
  yalnız geçerli http(s)'de tıklanabilir çiziyor — doğru desen orada duruyor.
- **Çözüm:** `GuideLinkCard`'ta yalnız `linkKind==='web'/'mail'` (http/https/mailto) için gerçek
  href çiz; `javascript:`/`data:`/`vbscript:` → tıklanamaz. İdeali: sunucuda da (`GuideLinkController`)
  şema beyaz-listesi.

**BD2 · `LdapMembershipService.java:242-244` — takım denetimi üye başına canlı AD sorgusu yapıyor:
tek admin isteğinde ≤200 senkron LDAP round-trip** (S15/perf sınıfı)

- **Bug:** `checkTeam` döngüsü her LDAP üyesi için `check(u)` çağırıyor; `check` her seferinde
  `directory.findUser(...)` ile AD'ye gidiyor. Tavan `TEAM_CHECK_MAX=200`.
- **Neden DÜŞÜK:** Yalnız global admin tetikler ve 200'de kesilir; yine de tek pod'da yavaş bir
  AD'de bir istek iş parçacığını uzun süre tutabilir. `resyncTeam` de üye başına yazan `resync`
  koşuyor (aynı, admin-tetikli).
- **Çözüm:** Toplu AD sorgusu (mümkünse tek filtre) ya da daha düşük tavan + zaman aşımı; en
  azından belge/uyarı.

**Birikmiş arka uç DÜŞÜK kardeşleri (arka uç imza ajanı, okuyarak doğruladı; hepsi önceden vardı):**
- **`EmailNotificationService.java:2351-2354` (`linkLine`; kull. :2238, :2252-2255) — S17/BD1'in
  E-POSTA kardeşi:** haftalık rapor `tracking_url`/`incidents_url`/`problems_url`/`postmortems_url`
  değerleri şema kontrolü olmadan `MailKit.link` ile href'e basılıyor; `WeeklyReportService`
  bu URL alanlarını doğrulamıyor → API'ye doğrudan `javascript:`/`data:` kaydedilip müdür onay
  mailinde tıklanabilir olur (etki düşük: istemciler `javascript:`'i nötrler). Çözüm: `MailKit.link`/
  `button` içinde merkezi http/https/mailto beyaz-listesi + sunucuda URL doğrulaması. (BD1 ile aynı
  commit'te kapatılır.)
- **Alıcı e-postası düz-log (P4-1 kör noktası):** `ClientErrorController.java:104-105`,
  `LoginHelpController.java:169-170`, `LdapProvisioningService.java:536-537`,
  `report/CertificateInventoryReportService.java:287-288`, `SchedulerService.java:5470, 5503-5504`,
  `NotificationGroupService.java:131-132` adresleri düz yazıyor; `LogRecipientMaskGateTest` yalnız
  `TO=/cc=/alıcı=` token'larını yakaladığı için kaçıyorlar. Çözüm: `SecretMask.maskEmails`; kapıyı
  argüman-adı tabanlı yap.
- **`CertificateCardExtrasService.java:227` (S5, O-5 kardeşi):** saatlik `points`
  `Math.round(v[1]*100.0/v[0])` — iki satır üstteki `pct24` `AvailabilityMath` kullanırken bu
  "hata varken 100 değil" korumasını almıyor. Çözüm: `AvailabilityMath.pct(v[0], v[1], 0)`.
- **`WeeklyReportService.java:1378-1384` (S5 sınır asimetrisi):** yeni `status_counts` 0..100000
  kırpılıyor ama `urgent/high/medium/low` `asInt(0)` ile SINIRSIZ toplanıyor → negatif/taşan API
  değeri müdür mailinde ve KPI'da `total`'ı negatif gösterir. Çözüm: önem sayılarına da
  `Math.max(0, Math.min(MAX, …))`.
- **`RdapDomainClient.java:169` + `RdapDomainExpiryService.java:188` (S3):** yönlendirme hop gövdesi
  `readNBytes(4096)` ile `withDeadline` olmadan okunuyor; varsayılan `rdap.org` her istekte 302
  döndüğünden bu NORMAL yol — 3xx başlığı gönderip gövdeyi bitirmeyen sunucu domain izleme iş
  parçacığını süresiz tutar. Çözüm: `HttpBodies.withDeadline`.
- **O11 kalıntısı (S15 backoff):** retry satırları `RETRY_AT`/`next_attempt_at` ile ayrılmadığı için
  B1'in 60 sn sweep'i ya da yeni enqueue, PENDING retry'yi backoff'u (30/120 sn) beklemeden gönderir
  → 120 sn backoff fiilen ≤60 sn. Etki sınırlı; `RETRY_AT` alanıyla kapatılır.
- **`TeamMembershipSourceService` javadoc yanlış (tx, DÜŞÜK):** "iz yazımı asıl işlemi ASLA
  düşürmez" iddiası `@Transactional` çağıranlarda (updateUser/deleteUser/moveAll/upsert/giriş)
  tutmuyor — repo proxy'sinden geçen istisna dış tx'i rollback-only yapar, catch yutar, commit
  `UnexpectedRollbackException` atar (pratikte seyrek; `merge` INSERT'i commit'e ertelediği için
  çoğu yol catch'e hiç düşmez). Çözüm: iz yazımını `REQUIRES_NEW` ile ayır ya da javadoc'u düzelt.

**Birikmiş ön uç DÜŞÜK kardeşleri (ön uç güvenlik+a11y ajanı, okuyarak doğruladı):**
- **localStorage'ta PII / paylaşılan makine (S3 sınıf 4, diff'te YENİ):** `palette/paletteModel.js:143-165`
  (+ `CommandPalette.jsx:231,244-247`) komut paleti "son kullanılanlar"ını `sm.palette.recent`'e
  yazıyor — admin'in kullanıcı arama sonuçları (ad + kullanıcı adı/e-posta) ve başka takımların alan
  adları dâhil; anahtar kullanıcıya özel DEĞİL ve çıkışta silinmiyor → aynı tarayıcıdaki sonraki
  kullanıcı görüyor. `diagnostics/diagModel.js:453-462` (`sm.dexp.recent`) ve `WeeklyReportsPage.jsx:444`
  (`wr.draft.<id>`) aynı sınıf (kullanıcıya özel değil, çıkışta silinmiyor). Çözüm: anahtara
  `:${username}` ekle, `kind:'user'` öğelerini kalıcılaştırma (ya da `sub`'ı at), çıkışta temizle.
  Kardeş güvenli desen `inbox-seen:${u}` (kullanıcıya özel).
- **TSV formül enjeksiyonu (S4/CWE-1236, diff'te YENİ):** `admin/sql/sqlUtils.js:114-117` (`rowsToTsv`,
  SQL sonuçlarını Ctrl+C ile panoya kopyalama) `csvCell` formül nötrlemesini atlıyor → `=/+/-/@` ile
  başlayan hücre Excel'e yapıştırılınca formül olur. Çözüm: `clean()`'e `csvCell` ön-ek kuralını uygula.
- **`localStorage.getItem` render'da try/catch'siz (S7, önceden vardı):** `pages/Login.jsx:27` — site
  verisi engelliyse `SecurityError` → login ekranı ErrorBoundary'ye düşer, giriş yapılamaz. Çözüm:
  useState başlatıcısında try/catch (diğer 44 okumanın hepsi korumalı).
- **İstemci-yalnız yetki gizlemesi (sunucu DOĞRU reddediyor — S5/S6, kozmetik):** `TeamManager.jsx:488`
  / `UserManager.jsx:130`→`UserDetailPanel.jsx:51` "AD ile karşılaştır/yeniden eşitle"yi kapsamlı
  müdüre (AD ADMIN) gösteriyor (`LdapMembershipController` global-admin ister → açılışta 403);
  `issues/IssueDetailSheet.jsx:275` yanıt kutusu+iç not anahtarını `canEdit`'siz çiziyor
  (`LoginIssueController` `edit` ister → toast hatası). Sunucu paritesi SAĞLAM; yalnız arayüz 403/toast
  üretiyor. Çözüm: `globalAdmin` bayrağını (App.jsx:329) ve `canEdit`'i bu yüzeylere geçir.
- **a11y satır-kimliği/ad kardeşleri (A1–A5, çoğu R15/R16):** `ui/CopyLinkButton.jsx:46` sabit
  `share.copyLink` adı 10 kart yüzeyinde tekrar ediyor (rowLabel yok); `WeeklyCompletionBoard.jsx:70-71`
  HintPopover adı yalnız "⏸"; `CertHealthPanel.jsx:277` `CopyButton` adsız; `InventoryTable.jsx:404`
  satır-içi kademe düğmesi yalnız "T2"/"—"; `pages/Login.jsx:297` `role="alertdialog"` `aria-labelledby`/
  `describedby` yok. Çözüm: `a11y.rowAction`/`useId` ile satır-adlı/etiketli hâle getir.

---

## Temiz sınıflar (tarandı, başka örnek yok)

**Arka uç.**
- **S1 (yeni yüzeyler):** `IssueReportController` sahiplik = oturum username, başkasınınki 404
  (doğrulandı). `LoginIssueController` yorum uçları `requireAccess("view"/"edit")` + iç notlar
  bildiren yanıtına HİÇ girmiyor (`publicComments` süzüyor, `commentItem` `internal` alanını bile
  döndürmüyor). `LdapMembershipController`: AD'ye giden 4 uç `requireGlobalAdmin` (kapsamlı müdür
  ADMIN rolüyle gelse de geçemez), `membership` ucu `users.list/view` + `requireCanView` (kapsam
  dışı → 404). `InboxService.build` yeniden-açma haberini YALNIZ `SessionScope.isGlobalAdmin`'e,
  yanıt haberini yalnız bildirenin kendi (LOWER-eşleşen) username'ine veriyor.
- **Envanter org-geneli görünürlük (kritik yeni yüzey):** YALNIZ okuma genişliyor; `InventoryVisibility`
  yazma kapılarında hiç çağrılmıyor; `orgWideReader` ayrıca `inventory.list/view` iznini şart
  koşuyor; başka takımın kaydında `can_manage=false` + `redactForForeignReader` ile `created_ip`
  düşüyor; not/revizyon/sağlık/geçmiş uçlarının hepsinde salt-okuma dalı ile yazma dalı ayrık.
  `addInventory` artık `existsByDomainIgnoreCase` ile 409 (harf-farkı devralma kapatıldı) + sunucu
  yönetimli alanlar (`deletedAt`, `domainExpiry`, `renewalPlanned*`, `updatedBy…`) gövdeden
  sıfırlanıyor (mass-assignment kapatıldı).
- **S2 (redirect SSRF):** `Redirect.NORMAL`/`setFollowRedirects` isabetlerinin hepsi yorum; 4
  `HttpURLConnection` `setInstanceFollowRedirects(false)`; HttpClient'lar varsayılan NEVER; 6
  `SafeRedirect` döngüsü her hop'ta `ssrfGuard`. Y1/Y2 imzaları yerinde (`PageFetchCore.java:240`
  origin-host eşleşmesi, `KeywordCheckerService.java:206` `isDowngrade`).
- **S4:** Yeni/değişen kod yeni soket/HTTP istemcisi açmıyor; mevcut 9 bağlantı noktası değişmedi.
- **S16:** `SqlPlaygroundService` `ReadOnlyDataSource` (`setReadOnly(true)`, 30 sn timeout, dış LIMIT),
  kara-liste `into/set/call/do/copy/lock/execute/prepare` + `pg_sleep/pg_read_file/lo_*/dblink`,
  yorum atma + tek-statement; tek değişiklik hata mesajının en özgül nedene inmesi (admin aracı).
- **S3 (tavanlar):** `HttpBodies` yalnız EKLEME (süre bekçisi); `PageFetchCore` 2MB/10× bomba
  koruması + `HttpCheckerService.sendDrained` bellek-almadan tüketim; Push/Webhook `readPreview`
  bayt tavanlı. (Açık kalan iki dış-okuma: BO8/ChainValidation ORTA + RDAP hop DÜŞÜK.)
- **S5:** `AvailabilityMath` 9 çağıranı `total≤0→null` korumalı; ortalamalarda 99,99 tavanı;
  `timeoutMs` yazan 11 nokta `clampTimeoutMs`. (İki asimetri kardeşi DÜŞÜK: CertCardExtras:227,
  WeeklyReportService:1378.)
- **S8:** LoginIssueReport'a eklenen 3 nullable kolonun 3 `ADD COLUMN` patch'i (tablo adı birebir);
  `issue_report_comments` + `app_user_team_sources` `CREATE TABLE IF NOT EXISTS` + index; dolu tabloya
  NOT NULL/UNIQUE eklenmiyor; CertificateInventory/DTO alanları `@Transient`/DTO kopyası.
- **S9:** Yeni `deleteBy…` metotları `@Transactional`; `LoginIssueService.purge` (@Tx) yorumları da
  siliyor; retention'da `issue-report-comments-orphan` var, `app_user_team_sources` muafiyette.
- **S10:** Diff'e eklenen satırlar + 7 yeni dosya 77 nullable Boolean getter'ına karşı tarandı; hepsi
  `Boolean.TRUE.equals` ya da primitive (`internal`/`byReporter` NOT NULL DEFAULT FALSE).
- **S13:** PermissionCatalog diff'i yalnız yorum; yeni uçlar mevcut `users.list/view`, `users.crud/edit`
  anahtarlarını kullanıyor; 5 yeni audit olay tipi `AuditEventCatalog`'da kayıtlı.
- **S14:** Tek karar kaynağı (teamOnly/contactsFor/isStandaloneEvent/hasTeamStamp) açılış/çözüm/tekrar
  bildir/fırtına/simülatörde ortak; seviye terfisi gönderimden önce kaydediliyor; çözümde mail erken
  dönse de push + (yeni O-2) webhook gidiyor.
- **S15:** Devre açıkken satır PENDING kalıyor; açılış drain (`ApplicationReadyEvent`) + 60 sn sweep;
  paylaşılan HttpClient `@PreDestroy` ile kapanıyor. (Backoff kalıntısı DÜŞÜK — yukarıda O11.)
- **S17 (parite/esc):** `MailKit.esc` 5 karakter kaçırıyor (`'`→`&#39;`); EmailNotificationService'te
  104, EmailTemplateBuilder'da 16 HTML yapının hepsinde düz-metin karşılığı var, kullanıcı değerleri
  kaçırılıyor; `endpointHtml`/markdown yolu şema-kontrollü. (Kalan link-URL kardeşi DÜŞÜK yukarıda;
  `MailDoc` düz-metin ham-değer/null→"null" gizli riski örneksiz.)
- **CSRF:** Yeni `OriginCheckFilter` mantığı doğru (Sec-Fetch-Site + Origin/Referer/Host/XFH/
  base-url/CORS eşleşmesi, `null` Origin reddi) — TEK kusuru BK1'deki yol-atlama.
- **Kilitlenme DoS:** `application.properties` — progressive lockout artık kalıcı kilide çıkmıyor
  (son süre tekrarlar); "kimliksiz saldırgan bilinen kullanıcıyı kalıcı kilitler" açığı kapatıldı.

**Ön uç (ön uç güvenlik+a11y ajanı tam süpürdü).**
- **XSS/HTML sink:** `dangerouslySetInnerHTML` yalnız 2 (App.jsx:1101 sayısal geri sayım + statik
  i18n; shadcn chart statik config); `innerHTML`/`outerHTML`/`insertAdjacentHTML`/`document.write`
  HİÇ yok. 9 `iframe srcDoc`'un hepsinde `sandbox` var; WeeklyReports srcDoc `allow-same-origin` ama
  `allow-scripts` YOK (script çalışmaz). 5 ReactMarkdown `rehype-raw`'suz (ham HTML render edilmez) +
  `defaultUrlTransform` `javascript:`'i siliyor; dış görsel CSP `img-src 'self' data:` ile engelli.
- **Tabnabbing:** 5 `target="_blank"`'in hepsinde noopener/noreferrer; 2 `window.open` (aynı-origin
  dışa aktarma + `mailto _self`).
- **URL şeması:** Dinamik href 21 yerde — haftalık çipler `isHttpLink`/`normaliseLink` (yalnız
  http/https), PageMonitor `source_page` regex, alert/incident/issue göreli `?tab=`. Açık tek şema
  boşluğu BD1 sınıfı (yenileme kılavuzu + `AnnouncementBanner` banner_link, admin-only).
- **Storage:** 45 `setItem` tarandı — token/parola/oturum kimliği saklanmıyor (`sm.session.active`
  yalnız bayrak). Açık PII kalemleri yukarıda (palette/dexp/wr.draft, DÜŞÜK). Kayıtlı görünümler
  kullanıcının bilinçli "kaydet" eylemi.
- **Yönlendirme/konsol:** 16 gezinme sink'i sabit/göreli (açık yönlendirme yok); 3 `console` çağrısı
  hassas veri basmıyor.
- **Sunucu paritesi:** LDAP 4 ucu global-admin; LoginIssue view/edit/purge ayrı kapılar +
  `issue-reports/mine` sahiplik 404; envanter DELETE/restore/permanent `requireTeamScopedAdmin`,
  purge-deleted/bulk satır-başına `canManageTeamResource`, set-team/transfer/transfer-ug global-admin,
  import `canManage`. Açık tek sunucu istisnası BO9 (`ug_team_id`). CSV dışa aktarımlar `csvCell`
  (tek istisna TSV kopyası, DÜŞÜK).
- **a11y:** 15 tıklanabilir satır klavye-erişimli; 29 Sheet/Dialog/AlertDialog başlıklı; KebabMenu
  çağrılarında `rowLabel`, seçim kutularında `bulk.selectOneFor`. Açık kalanlar A1–A5 (DÜŞÜK).

---

## Doğrulanamadı / gözlem (ayrı geçiş ya da tarayıcı/ölçüm gerekir)

Arka uç baseline + S2–S17 sweep ve ön uç baseline+S6/7/11/12 ajanları döndü; S2/S3/S17 arka uç
sınıfları ve ön uç a11y (F/R) artık DOĞRULANDI. Kalan boşluklar:
- **Ön uç S6 / S7:** `<PaginationBar>` çağrı yerlerinin taban sınıflandırması + `sizeOptions` kabulü
  (hook düzeyi ve kapı testi temiz; çağrı-yeri düzeyi doğrulanmadı); yeni grafiklerde ts koruması
  (NetworkOutageHistory, `admin/health/*`, DbAnalyticsPanel, `pages/forecast/*`, pagespeed/ping/dns).
- **Ön uç S11/S12 (admin dışı):** `issues/`, `inbox/`, `incidents/`, `MyIssueReports`, `renewal/`,
  `weekly/*`, `diagnostics/`, `palette/`, `pages/warnings`, 9 izleme sayfası — fetch-seq/alive ve
  prop-sync effect'i + hook-sırası/memo'suz-bağımlılık tek tek doğrulanmadı. (Admin S11 tamamlandı:
  60 yükleyicinin 49'u temiz; bulgular BF1/BF2/BF3 + birikmiş kardeşler.)
- **Tarayıcı/ölçüm işi:** BF2 ve birikmiş S11 kardeşleri zamanlamaya bağlı (uç süreleri ölçülmedi);
  Radix non-modal Sheet'lerin (AlertDetail, UserDetailPanel, IncidentDetailSheet, IssueDetailSheet)
  dışarı-tık kapanma davranışı tarayıcıda denenmeli; BF3 kod okumasıyla kesin ama koşulmadı.
- **Performans gözlemleri (bulgu değil, izlenmeli):** `InboxService.findAdminActivityForReporter`/
  `findReopensSince` her yoklamada 30 günü (TEXT alanlarıyla) belleğe alıp 20'de kesiyor,
  `LOWER(r.username)` için fonksiyonel indeks yok; `LdapDirectoryService` grup araması (~:586)
  countLimit/timeLimit taşımıyor (AD admin yapılandırır, read timeout 10 sn).

---

## Önerilen sıra

1. **Yayın öncesi (KRİTİK):** BK1 — `AuthInterceptor` + `OriginCheckFilter` yol kontrolünü
   normalize et (`getServletPath`/`UrlPathHelper`) + `StrictHttpFirewall`; "matris-param yolu auth'u
   atlayamaz" negatif testi.
2. **Yayın öncesi (YÜKSEK, doğrulanmalı):** BO0/O12 — kurtarma-kuşağı canlılık kusuru; önce
   "alarm açıkken DOWN→UP→recovery" sıra testiyle üret, sonra düzelt (varsayılan ACCESSIBILITY
   kurulumunu etkiliyor).
3. **Yayın öncesi (ORTA):** BO9 (`ug_team_id` mass-assignment — admin-kapısı atlama + çapraz-takım
   veri/alarm sızıntısı), BO4/O1 (self-invocation tx — kapalı sanılan veri kaybı), BO8/ChainValidation
   (OCSP/CRL süre tavanı — tek pod DoS), BO7 (IdentityLeakGuard büyük-küçük harf + gerçek iz temizliği).
4. **Ürün kararı:** BO6 (`ldap.*` ayarları GLOBAL_ONLY mi).
5. **Tek commit — a11y + seq/temizlik:** BF1, BF2, BF3 + birikmiş ön uç S11 kardeşleri.
6. **Küçük/kardeş süpürme:** BO5/O15 (haftalık N+1), BD1 + e-posta link-URL kardeşi (şema
   beyaz-listesi), P4-1 log maskeleme kardeşleri, iki S5 asimetri, RDAP hop deadline, O11 backoff,
   BD2 (LDAP denetimi toplu sorgu).
7. **Ayrı geçiş:** yukarıdaki "doğrulanamadı" ön uç S6/S7/S11-admin-dışı/S12 + güvenlik derin
   süpürme; tarayıcı/ölçüm işleri.

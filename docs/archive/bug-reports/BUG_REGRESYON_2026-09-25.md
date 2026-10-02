# BUG REGRESYON TARAMASI — 2026-09-25 (yayın öncesi)

Kapsam: `main` @ `d2a6ef5b` (push edilmemiş). Kaynak yerinde tarandı (`D:\site-monitor`); test, derleme ve
git yazma komutu çalıştırılmadı (kapılar koordinatörde ayrıca koşuyor). Bu sürümün yeni kodu
`git diff v20.85.0..HEAD`: shadcn/ui geçişi (306 frontend dosyası), `TeamActorScope` ile ekip kapsamlı Denetim
Logu ve İzleme Değişiklikleri, herkese açık salt-okunur Yetki matrisi, Haftalık Raporlarda ADMIN görünürlüğü,
pano platform süzgeci ve USER envanter ekleme formu düzeltmesi. 09-23 taramasından sonra gelen ama o turda
taranmamış kod da kapsama alındı: 09-24 Port İzleme vekil tüneli ve "Sizin için — bugün" paneli.

Yöntem: `/bug-regresyon` TAM kapsam. İmza-güdümlü süpürme yapıldı: (A) bilinen bulguların düzeltilmiş imzası
yerinde mi, (B) aynı anti-desen başka yerde var mı. Frontend taraması üç paralel ajana dağıtıldı. Her aday ana
akışta kaynak okunarak doğrulandı; grep eşleşmesi tek başına bulgu sayılmadı. Doğrulanamayan adaylar rapora
alınmadı.

Tabanlar:
- `BUG_RAPORU_2026-09-23.md` (51 bulgu, 51/51 kapalı)
- `BUG_REGRESYON_2026-09-23.md` (B1–B7, F1–F11)
- 09-24 QA düzeltmeleri (ISSUE-001…003)

---

## (A) Baseline re-check — REGRESYON YOK

**Backend.** v20.85.0'dan bu yana değişen üretim dosyası dokuz tane:

- `AuditController`, `AuthController`, `MonitoringController`, `PermissionController`
- `TeamActorScope` (yeni)
- `AppUserRepository`, `AuditLogRepository`, `MonitorChangeLogRepository`
- `WeeklyReportService`

09-23 düzeltme commit'lerinin hepsi v20.85.0'ın atası. Bu dokuz dosyaya düşen maddeler tek tek okundu. Geri
kalanların dosyası değişmediği için geri alınmış olamaz.

**Frontend.** 30 madde tek tek kontrol edildi. Buna 35caf8ca'nın satır klavye erişimi eklemelerinin 57 noktası
da dâhil.

| Madde | Durum | Kanıt / not |
|---|---|---|
| K1, Y1, Y2, Y5–Y12 | KAPALI ✓ | Dosyaları v20.85.0'dan beri değişmedi |
| Y3 (başkasının giriş IP/şehir/UA'sı) | KAPALI ✓ | `SystemController.maskIdentity` (:182, :257) ve `AuditController.userDeviceLogins` (:354 `requireAuditAccess`) yerinde. **Not:** 09-25 ürün kararı aynı veriyi ekip İÇİNDE Denetim Logu'nda bilinçli açtı; kardeş uçlar eski politikada kaldı → bkz. **R2** |
| Y4 (TERMINATED sentinel) | KAPALI ✓ | `AppUserRepository.java:81-83` `clearAllActiveSessions`, `NOT LIKE 'TERMINATED:%'` korunuyor |
| O1–O26, D1–D12 | KAPALI ✓ | Değişen dosyalardakiler yeniden okundu: O2 (`deletePageSpeed` `@Transactional`), O5 (`restoreChange` → `liveTeamOf`, :537), O8 (`ORG_ZONE`). Frontend'dekiler: O13, O16, O17, O19, O20, D3, D6, D8 |
| O18 (modal z-index) | KAPALI ✓ (farklı yoldan) | `ModalShell` artık `.modal-shell-overlay`/`--modal-z` kullanmıyor; `zIndex = 2000 + derinlik*10` satır içi (`ModalShell.jsx:70, :140, :152`), özgüllük yarışı ortadan kalktı |
| B1–B7 | KAPALI ✓ | B2 `denyIfDomainNotManageable` (:410-422), zayıf algoritma okuma kapsamı `build(viewTeamIds)` (:231, :238), B7 null-güvenli `open` (:1544) |
| F1–F11 | KAPALI ✓ | Kartlarda `role="button"` + `tabIndex` + `mon.openDetailFor` (9 sayfa). `bulk.selectOneFor` (9 ızgara + CertificatesTable). `KebabMenu` Radix'e taşındı; `rowLabel` tetikleyicide (`KebabMenu.jsx:58-59`), 8 çağıranın hepsi geçiyor. `SearchableSelect` silme kontrolü artık gerçek `Button` + Enter/Space. Sabit `aria-label="…"` kalmadı |
| ISSUE-001…003 (09-24 QA) | KAPALI ✓ | `AlertEventRepository.java:49` `CASE UPPER(...)` sırası; `TodayPanel.jsx:101-103` tekil/çoğul |

**Kapılar.**

- Backend bekçi testlerine (`RepositoryWriteTransactionGuardTest`, `OrgCalendarDayGateTest`,
  `IdentityLeakGuardTest`, `UserPushOutboxDrainGateTest` …) dokunulmamış.
- Değişen testlerde silinen üç 403 iddiası bilinçli politika değişikliklerine karşılık geliyor (ekip denetimi,
  matris GET, haftalık ADMIN). Yerlerine daha dar yeni iddialar yazılmış.
- `rowAccessibleNames.test.js` iki gerekçeli istisna aldı (`shadcn/input-group` eki, `data-slot="dialog-overlay"`
  örtüsü). Hiçbir iddia silinmedi.
- `cssClasses.test.js` Tailwind'in ürettiği sınıfları kabul ediyor. Uydurma sınıf adını reddettiğini pinleyen
  yeni bir negatif test de eklendi.
- `cssTokens` değişmedi.

---

## (B) Yeni bulgular — 18 (YÜKSEK 0 · ORTA 5 · DÜŞÜK 13)

Bu sürümün kendi kodundan gelenler: R1, R2, R4 (düzeltme bir yolda kaldı), R8, R9, R10, R17. R3, 09-24 vekil
tünelinden geliyor: v20.85.0'a girdi ama 09-23 taramasından sonra yazıldığı için hiç taranmamıştı. Geri
kalanlar, bilinen sınıfların **süpürülmemiş kardeşleri**; önceden de vardı.

### ORTA

**R1 · `MonitorChangeLogRepository.java:59-60, 64-65, 93-98, 132-137` (+ `MonitoringController.java:640-642`) — İzleme Değişiklikleri'nin aktör yolu, takımı DOLU satırları da açıyor: başka takımın izleme değişiklikleri sızıyor** (S1, yeni kod)

- **Bug:** Kapsam `c.teamId IN :teamIds OR c.actorId IN :actorIds OR LOWER(c.actor) IN :actorNames`. Aktör yolu
  satırın takımına bakmıyor.
- **Neden bug:** Özelliğin gerekçesi takımı **boş** satırlar:
  - `TeamActorScope` javadoc: "envanter türevi / sentetik — 136 değişikliğin 45'i".
  - Yeni test `monitorChangeLogActorMembership` yalnız `teamId=null` satırlarla kurulmuş.
  - Kural bundan geniş. Kardeş uçlar aynı satırı `canView` ile 404'lüyor: `/changes/{kind}/{id}` (:412-419, üstelik
    `CHANGE_LOG_DENIED` güvenlik olayı yazıyor) ve `/changes/{kind}/{id}/{seq}` (:440-443). Aynı satır için iki
    uç çelişik karar veriyor.
- **Senaryo:** Takım A üyesi bir müdür (görüş kapsamı A+B) ya da A+B çok-takımlı bir kullanıcı, B'nin HTTP
  izlemesinin URL'ini değiştiriyor. Yalnız A'da olan USER konsolda şunları görüyor ve olay/tür sayaçları da
  bunları sayıyor:
  - "Takım B · <izleme adı> · url: x → y"
  - aktörün IP'si ve User-Agent'ı
  - Aynı USER o izlemeyi hiçbir başka ekranda göremez.
  - Takımı olan global admin için etki daha geniş: kurum genelinde yaptığı her izleme değişikliği kendi
    takımına akar.
- **Çözüm:**
  - Aktör yolunu `c.teamId IS NULL AND (c.actorId IN … OR LOWER(c.actor) IN …)` ile sınırla. Hem kapsam hem takım
    süzgeci dalında, üç sorguda da.
  - `HistoryQueryGrammarTest`'e "üye aktör + yabancı takım satırı → görünmez" vakasını ekle.

**R2 · `AuditController.java:382-388` + `AuditLogRepository.java:137-138` + `TeamActorScope.java:36-47` — Denetim Logu ekip kapsamı aktör üyeliğine bakıyor; kaynağın takımına ve aktörün rolüne bakmıyor** (S1, yeni kod; ürün kararıyla sınırı teyit edilmeli)

- **Bug:** `ofTeams` görüş takımlarının **tüm** üyelerini döndürüyor. Buna takımı olan global admin, AUDIT ve
  müdür de dâhil. Eşleşen aktörün bütün satırları tam ayrıntıyla dönüyor.
- **Neden bug (a) — kapsam taşması:** Aktör bir takımın üyesiyse, başka takımlara dair eylemleri de o takımın
  her üyesine açılıyor:
  - başka takımların kaynak adları;
  - `USER_UPDATE` diff'lerinde başka kullanıcıların `employeeId`, `systemRole`, `email`, `teamIds` ve
    `managerId` değerleri (`AdminController.java:2670` anlık görüntü alanları; `AuditDiff` yalnız
    parola/sır maskeler);
  - `SQL_EXECUTE` alıntısı;
  - ayar değişiklikleri.
  - Ayrıca `actorId` eşleşmesi yüzünden, takıma yeni katılan kullanıcının **önceki takımındaki** bütün geçmişi
    de yeni takımına görünür oluyor.
- **Neden bug (b) — iki kayıtlı karar çelişiyor:** 2026-09-10 kararı `TeamDirectoryController.java:20-22`'de
  "sicil no, sistem rolü … DÖNMEZ" diyor. `SystemController.maskIdentity` (:182) ekip arkadaşının IP, şehir ve
  UA'sını hâlâ maskeliyor. Aynı veriler Denetim Logu'nda maskesiz geliyor.
- **Senaryo:** Takım A'nın TEAM_ADMIN'i bir ekip arkadaşının sicilini düzeltiyor. Takım A'daki her USER,
  Denetim Logu'nda sicilin eski ve yeni değerini görüyor. Oysa takım dizini bu alanı kasıtlı olarak hiç
  döndürmüyor.
- **Çözüm:** Ürün sahibine iki sınır sorulmalı:
  1. Aktör başka takımın kaynağına dokunmuşsa satır ekip kapsamından çıkarılsın mı? En azından global admin ve
     AUDIT aktörleri ekip kapsamı dışında tutulmalı.
  2. `employeeId`/`systemRole`/`managerId` diff'leri ekip kapsamında maskelensin mi?
  - Karar sonrası `maskIdentity` ile tek politikaya bağlanmalı (R8 ile birlikte).

**R3 · `PortCheckerService.java:217` — vekil tünelindeki HTTP türü port kontrolü, hedefin durum satırını TAVANSIZ okuyor** (S3, 09-24 kodu)

- **Bug:**
  `new BufferedReader(new InputStreamReader(s.getInputStream(), …)).readLine()`.
- **Neden bug:**
  - Hedef, kullanıcının girdiği host. Yeni satır göndermeden bayt akıtırsa satır tamponu sınırsız büyür.
  - `setSoTimeout` okuma başına işlediği için yavaş damlatan bir bağlantı `certCheckExecutor` iş parçacığını
    süresiz tutar.
  - `monitoring.crud` USER'da varsayılan açık. Vekilin CONNECT için izin verdiği portlar (443/8443) dış bir
    sunucuya tünel açmaya yetiyor.
  - Prod tek pod: bellek tavanı = kesinti.
- **Kardeş karşılaştırması:**
  - Aynı özellikteki `ProxySettings.readTunnelLine` 8 KB tavanlı ve yorumu açıkça "S3: tavanlı okuma" diyor.
  - Doğrudan yol (`doHttp`) `HttpURLConnection`'ın başlık tavanına yaslanıyor.
- **Çözüm:**
  - `readTunnelLine` benzeri, 8 KB tavanlı bir satır okuyucu kullan.
  - Kontrole toplam süre tavanı (deadline) ekle.

**R4 · `InventoryFormModal.jsx:781` (+ `:127`, `:152`) — pano yolunda takım seçici düzenleme kipinde açık; sunucu takımı sabitliyor, "Kaydedildi" diyor ve bildirim grubunu sessizce düşürüyor** (S12 / kardeşe süpürülmemiş düzeltme)

- **Bug:** `InventoryFormModalForDomain`, `canManage` geçmiyor. Varsayılan `true` olduğu için `teamPickable`
  herkes için `true`.
- **Neden bug:** USER kendi takımının pano kartından düzenleme formunu açıyor (`App.jsx:270`, `:728-736`) ve
  takımı değiştiriyor. Sunucu tarafında:
  - `AdminController.java:295-297` takımı eski değere sabitliyor.
  - `:348` `validInventoryGroup` yeni takımın bildirim grubunu `null`'a çekiyor; alarmlar sessizce takım
    varsayılanına düşüyor.
  - `:378` seçilen grup adını **eski** takımın altında yaratıyor ve başıboş bir grup bırakıyor.
  - Kullanıcı yine de başarı bildirimi görüyor.
- **Kapsam:** Bu sürümün düzeltmesi (`teamPickable`) yalnız InventoryManager + USER yolunu kilitledi. Aynı
  durum InventoryManager yolunda TEAM_ADMIN için de geçerli: sunucu `isTeamAdmin` için de takımı sabitliyor.
- **Çözüm:**
  - Sarmalayıcıya `canManage`/`canWrite` geçir.
  - `teamPickable`'ı düzenleme kipinde sunucu kuralına eşitle: yalnız global admin ya da yönetim kapsamı;
    TEAM_ADMIN ve USER hariç.

**R5 · Toplu seçim kutuları satır kimliği taşımıyor — F2 sınıfının süpürülmemiş kardeşleri** (önceden vardı)

- **Bug:**
  - `AlertHistory.jsx:1382`: `aria-label={t('alh.bulk.selectOne')}` her kartta aynı ("Bu alarmı seç").
  - `IncidentHistoryPage.jsx:728`: kutunun **hiç** adı yok.
  - `WeeklyReportsPage.jsx:1352`: kutunun **hiç** adı yok.
- **Neden bug:** Bu kutular yıkıcı ya da dışa etkili toplu eylemleri besliyor, ve onay diyalogları yalnız
  **ADET** söylüyor:
  - toplu onayla / çöz / yeniden bildir (`alh.bulk.*Msg`);
  - toplu takım aktarımı (`inc.transferConfirm`);
  - toplu "onayla ve gönder" (`wr.bulkApproveMsg`).
  - Ekran okuyucu kullanıcısı yanlış alarmı çözebilir ya da yanlış haftanın raporunu onaylayıp gönderebilir.
- **Çözüm:**
  - `t('bulk.selectOneFor', a.domain | r.title | formatWeekRange(...))`.
  - `rowAccessibleNames` kapısına kural: "checkbox + `selected.has(` → satır argümanlı ad".

### DÜŞÜK

**R6 · `MonitorSparklineService.java:247` — `up_pct`'de "hata varken asla 100" koruması yok** (S5)

- **Bug:** `Math.round(10000.0 * (n - fail) / n) / 100.0`.
- **Neden bug:**
  - Kardeş `MonitoringController.uptimePct` (:1036-1041) tam bu kusuru düzeltmiş.
  - 1 dakika aralıkla 15 günde 21.600, 30 günde 43.200 kontrol yapılır; tek hata %100,00'e yuvarlanır.
  - `MonitorSpark` ipucu aynı anda "1 hata · %100,00" diyor.
- **Çözüm:** `if (pct >= 100.0 && fail > 0) pct = 99.99`.

**R7 · `AuditController.java:89-91` — kaynak geçmişi önce ilk N satırı çekip sonra süzüyor**

- **Neden bug:** Ekip kullanıcısında bir kaynağın son 100 olayı yabancı aktörlerinse, zaman çizelgesi
  (`AuditLogViewer.jsx:323`) boş ya da eksik geliyor. Kullanıcı "kayıt yok" sanıyor.
- **Çözüm:** Kapsamı sorguya taşı. `findAdvanced` `resourceType`/`resourceId` süzgecini zaten destekliyor.

**R8 · Yetki matrisi ile denetim kapsamı tutarsız**

- **Bug:** `auditReadScope` ekip kapsamında `audit_log.read` sormuyor. Bu sürümde herkese açılan matris ise
  USER/TEAM_ADMIN için `audit_log.read`'i **kapalı** gösteriyor. `PermissionCatalog.java:217-218` ve `:288`
  yorumları hâlâ "yalnız admin/AUDIT" diyor.
- **Neden bug:**
  - Matrise "kimin neye yetkisi var" diye bakan kullanıcı, ekip arkadaşlarının denetim kaydını (IP dâhil)
    göremediğini sanıyor.
  - Yönetici ekip içi denetim görünürlüğünü matristen kapatamıyor.
- **Çözüm:** Ekip kapsamı için ayrı bir katalog satırı açılmalı (ör. `audit_log.team`), ya da matrise açıklama
  eklenmeli. İki yolda da yorumlar güncellenmeli.

**R9 · Performans (doğrulanmalı) — ekip kapsamının istek başı maliyeti**

- **Bug:**
  - `TeamActorScope.java:41`, her istekte takım üyelerinin **tam** `AppUser` varlıklarını çekiyor:
    `photo_base64` TEXT kolonu + EAGER `teamIds` → N+1.
  - Denetim sorgusu (`AuditLogRepository.java:137-138`) indekssiz `actor_team_id` ve `LOWER(actor)` üzerinde OR
    kuruyor. Sayfa sayımı 365 günlük tabloyu tarayabilir.
- **Neden önemli:** Denetim Logu artık herkesin menüsünde. Prod tek pod ve 100 eşzamanlı kullanıcı hedefi var.
- **Çözüm:**
  - Üyeler için `SELECT u.id, LOWER(u.username)` projeksiyonu.
  - `actor_team_id` indeksi.
  - Önce `EXPLAIN ANALYZE` ile ölç.

**R10 · `PermissionMatrix.jsx:50-62` — yükleme hatasında hata durumu yok** (yeni kod)

- **Neden bug:**
  - `success:false` olunca `canEdit` false kalıyor; global admin "salt okunur" bandını ve boş tabloyu görüyor.
  - İstek throw ederse `catch` yok; işlenmeyen ret oluşuyor.
  - "Bilinmiyor" ile "yetkin yok" aynı ekrana düşüyor.
- **Çözüm:** Hata durumu ekle; bandı yalnız başarılı yüklemeden sonra göster.

**R11 · `WeeklyReportsPage.jsx:503-531` — `loadReport` yarışı kilidi sızdırıyor** (S11)

- **Neden bug:**
  1. Rapor açılırken kullanıcı listeye dönüyor. O anda `lockHeld` false olduğu için `backToList` kilidi
     bırakmıyor (:1065).
  2. Geç gelen yanıt `setReport` yapıyor ve kilidi alıyor.
  3. 45 sn'lik kalp atışı (:702-715) kullanıcı liste ekranındayken kilidi tazelemeye devam ediyor. Diğer
     editörler "X düzenliyor" görüyor.
- **Çözüm:** Yanıttan sonra `selectedId` ref'ine ya da bir seq değerine bak; yanıt bayatsa kilidi bırak.

**R12 · Seq korumasız yüklemeler** (S11, önceden vardı)

- `AuditLogViewer.jsx:344-352` `loadLogs`:
  - **Neden bug:** 15 sn otomatik yenileme ile preset, sayfa ve kart tetikleri üst üste binebiliyor. Eski
    süzgecin yanıtı, yeni preset çipinin altında görünebilir.
- `CommandPalette.jsx:47`:
  - **Neden bug:** Kısa sorgu dalında `seq` artmıyor. Uçuştaki "ab" yanıtı, kullanıcı "a"ya döndükten sonra
    listelenebiliyor.
- **Çözüm:** İkisine de seq ref ekle.

**R13 · `ExpiryForecastPage.jsx:154`, `TodayListModal.jsx:88` — "10" sayfa boyutu seçeneği ölü** (S6'ya komşu)

- **Bug:** İki sayfa `sizeOptions={[10,25,50]}` geçiyor. `usePagination.setPageSize` (`hooks/usePagination.js:83-89`)
  ve `readPageSize` ise yalnız `[25,50,100,200]` kabul ediyor.
- **Neden bug:** 25'e geçen kullanıcı "10"a dönemiyor; tık sessizce yutuluyor.
- **Çözüm:** Boyutu çağıranın `sizeOptions` listesine göre doğrula.

**R14 · Klavye erişimi — F8 sınıfının kardeşleri**

- **Bug:**
  - `WeekDatePicker.jsx:78-80`: tetikleyici yalnız `onMouseDown` dinliyor; Enter/Space seçiciyi açmıyor.
  - `WeekDatePicker.jsx:93/:97`: ay gezinme düğmelerinin adı yok.
  - `DateTimeField.jsx:27-28`: temizleme "X"i odaklanamayan bir SVG ve tetikleyici düğmenin içinde duruyor.
- **Neden bug:**
  - WeekDatePicker, Haftalık Raporlar'daki haftaya atlama seçicisi; önceki/sonraki hafta düğmeleri geçici çözüm.
  - Klavye kullanıcısı isteğe bağlı bir tarihi temizleyemiyor.
- **Çözüm:**
  - Tetikleyiciye `onClick` bağla.
  - Temizleme X'ini tetikleyicinin dışında gerçek bir `<button>` yap.

**R15 · Satır ve kart eylem düğmelerinin adları sabit — F3/F4 kardeşleri**

- **Bug:** Aşağıdaki düğmeler her satırda ya da kartta aynı adı taşıyor:
  - `MonitorCardActions.jsx:52/54/57` (9 izleme sayfası);
  - `CertificateCard.jsx:253-271`;
  - `MaintenanceWindowsPage.jsx:258-265`;
  - `IncidentsPage.jsx:289`;
  - `DeploymentHistoryPanel.jsx:340`;
  - `PushLogView.jsx:343-344` (yeniden kuyruğa alma, yan etkili);
  - `InboxBell.jsx:164-165` ("İzlemeye git": F4'ün birebir kardeşi; `act.goMonitorFor` deseni hazır).
- **Neden DÜŞÜK:** Silme onayları hedefi adıyla söylüyor (`mon.deleteMsg`). Yine de düğme listesinde
  50 kez "Sil" duyuluyor.
- **Çözüm:** `KebabMenu`'deki `rowLabel` desenini uygula.

**R16 · Adı olmayan ya da i18n'siz kontroller**

- **Bug:**
  - `MonitorGuideButton.jsx:33`: kapatma X'inin adı yok (9 sayfada).
  - `Sparkline.jsx:22`: `aria-label={label || 'trend'}` sabiti her pano kartında okunuyor.
  - `PermissionMatrix.jsx:252-259`: ADMIN hücreleri yalnız `title` ve `aria-hidden` ikon taşıyor; ekran okuyucu
    boş hücre okuyor.
  - `Dialog.jsx:210, :220, :236-254`: varsayılan düğme metinleri Türkçe sabit. `cancelText` geçmeyen **20** çağrı
    yeri (ör. `DeviceHistoryPanel.jsx:125`, `MaintenanceWindowsPage.jsx:192`) EN arayüzde "İptal" gösteriyor;
    `RetentionSettings.jsx:180` istemi "Tamam" gösteriyor.
- **Çözüm:**
  - Diyalog varsayılanlarını `t('app.cancel')`/`t('app.ok')` ile değiştir.
  - Adsız düğmelere i18n'li ad ver.

**R17 · shadcn geçişinin getirdiği yeni a11y kayıpları** (yeni kod; bilinen bir düzeltmeyi bozmuyor)

- **Bug:**
  - `MultiTeamSelect.jsx:67-86`: seçenekler seçili durumu duyurmuyor. Checkbox `aria-hidden` ve `aria-checked` yok.
    - Eski sürümde yerel checkbox vardı.
    - Kardeş `FacetedFilter.jsx:73` doğru yazılmış.
  - `PickerPopover.jsx:73`: `role="combobox"` adını içerikten almıyor, ve `ariaLabel` geçmeyen seçicilerin adı
    kalmadı. Örnekler: `CertFilterRow.jsx:16`, envanter süzgeç satırı, `App.jsx:1181-1236`'daki `htmlFor`'suz
    etiketler.
  - `Nav.jsx:283-287` + `shadcn/sidebar.jsx:411`: kenar çubuğu daraltılınca grup tetikleyicileri `opacity-0`
    oluyor ama odaklanabilir kalıyor. Klavyede 7 görünmez durak var; hiçbiri bir şey yapmıyor.
- **Çözüm:**
  - `MultiTeamSelect` seçeneklerine `aria-checked` ekle.
  - Seçicilerde `ariaLabel`'i zorunlu kıl ya da etiketi `htmlFor` ile bağla.
  - Grup tetikleyicilerine `group-data-[collapsible=icon]:invisible` ekle.

**R18 · Bilgi / tarayıcıda doğrulanmalı — `role="button"` kartların içinde etkileşimli kontroller var**

- **Bug:** F1 tasarımında kartın kendisi `role="button"`. İçinde checkbox, bağlantı kopyalama ve eylem düğmeleri
  duruyor.
- **Neden önemli:**
  - ARIA'da button'ın alt öğeleri sunumsaldır ("children presentational"). axe bunu `nested-interactive`
    olarak raporlar.
  - VoiceOver içteki kontrolleri düzleştirebilir. Bu, F2 ve R15 düzeltmelerini o tarayıcıda boşa çıkarabilir.
- **Çözüm:** Önce gerçek tarayıcıda doğrula. Gerekirse kartı etkileşimsiz bırakıp başlığa gerçek bir düğme koy
  ("stretched link" deseni). Bu, kapı testinin (2) numaralı kuralını da değiştirir.

---

## Temiz sınıflar (tarandı, başka örnek yok)

### Backend

- **S1 (R1/R2 dışında):**
  - `PermissionController` yazma uçları yalnız `isGlobalAdmin`. GET salt okuyana yalnız
    (rol, kaynak, eylem, izin) döndürüyor.
  - `WeeklyReportService` ADMIN bypass'ı yalnız modül bayrağını aşıyor. Veri kapsamı `isAdmin()` (global)
    ile korunuyor; müdür başka takım okuyamıyor (test pinli).
  - `/audit/{id}` kapsam dışını 404'lüyor. `correlation`, `actor` ve `export` aynı kapsamda.
  - Sistem geneli yüzeyler (`integrity`, `stats`, cihaz girişleri) hâlâ `requireAuditAccess`.
    `event-types` sayaçları yalnız admin/AUDIT'e dönüyor.
  - `/changes/recent` yabancı takım süzgecine 403 veriyor.
- **S2:** Üretimde `Redirect.NORMAL` / `setInstanceFollowRedirects(true)` sıfır.
- **S3 (R3 dışında):** `CertificateCheckerService` CONNECT `readLine` kaynağı kurumsal vekil, güvenilir. TLS'de
  önce istemci konuştuğu için tampon yutma riski yok.
- **S4:** Vekilli port kontrolü de tünelden önce `ssrfGuard.validate` çağırıyor.
- **S5 (R6 dışında):** `TodayPanelService` IST gün hesabı doğru.
- **S8:**
  - `PortMonitor.use_proxy` nullable; ddl-auto güvenli; oluşturma ve güncellemede setter var (:1704).
  - `TodayPanelSnapshot` yeni tablo ile doğuyor.
- **S9:** Tek yeni `@Modifying` olan `TodayPanelSnapshotRepository.deleteOlderThan`, `@Transactional`.
- **S10:** Yeni kodda `Boolean.TRUE.equals` kullanılıyor; `PortCheck.open` NOT NULL.
- **S13:** `PermissionCatalog` değişmedi.
- **S14–S17:** `EscalationService`, `StormService`, `UserPushService`, `SqlPlayground` ve `EmailTemplateBuilder`
  09-23'ten beri değişmedi.

### Frontend

- **S6:**
  - `PaginationBar` sözleşmesi 1 tabanlı ve değişmedi. 45 çağrı yerinin hepsi tutarlı: 23 `usePagination`,
    14 tane 0 tabanlı + dönüşümlü, 8 uçtan uca 1 tabanlı.
  - Kapı notu: `paginationBase.test.js` her dosyada yalnız ilk `<PaginationBar>`'ın `page` prop'una bakıyor.
    `UserPushSettings`'teki ikinci çağrı elle doğrulandı.
- **S7:** Grafik değişiklikleri kozmetik; `shadcn/chart.jsx` üretim kodunda kullanılmıyor.
- **S11 (yeni kodda):** `MonitorChangesConsole` `loadSeq`; `InventoryFormModal` fetch'leri `alive` korumalı;
  platform kataloğu `alive` korumalı.
- **S12:**
  - `InventoryFormModal` `teams` senkronu ve ön seçim etkisi doğru: yalnız boş `team_id`'yi dolduruyor,
    düzenleme kipini atlıyor, geç gelen takımları karşılıyor.
  - `PermissionMatrix` `canEdit` sunucudan türüyor; `toggle` erken dönüyor.
- **Pano platform süzgeci:**
  - URL anahtarı `PAGE_STATE_PARAMS`'ta kayıtlı ve çakışmıyor.
  - Faset sayıları `preFiltered`'dan, liste ve sayfalama `filtered`'dan geliyor; ikisi tutarlı.
  - Her değişiklik sayfayı 1'e alıyor.
- **AuditLogViewer ekip kipi:** Admin'e özel uç çağrılmıyor. `count` yokluğu "undefined"/NaN üretmiyor.
- **Adlandırma ve i18n:**
  - `PermissionMatrix` Switch'lerinin adları tekil (rol · kaynak · eylem).
  - `FacetedFilter` `aria-checked` taşıyor.
  - `KebabMenu` `rowLabel` 8 çağıranda da geçiyor.
  - Yeni özelliklerin TR/EN anahtarları eksiksiz.

---

## Önerilen sıra

1. **Yayın öncesi:**
   - **R1:** Tek sorgu koşulu + test.
   - **R3:** Tavanlı okuyucu.
   - **R4:** Sarmalayıcıya iki prop + `teamPickable` kuralı.
   - Üçü de dar ve yerel. Doğru sürüm iki tanesi için aynı kod tabanında zaten duruyor
     (`readTunnelLine`, InventoryManager yolu).
2. **Ürün sahibine sorulacak:**
   - **R2** (+ R8): ekip denetim kapsamının sınırı ve PII maskelemesi.
   - Karar gelene kadar en güvenli ara adım: global admin/AUDIT aktörlerini ekip kapsamından çıkarmak.
3. **Tek a11y süpürme commit'i:** R5, R14, R15, R16, R17. Aynı commit'te kapıya iki kural eklenmeli:
   - "checkbox + `selected.has(` → satır argümanlı ad";
   - "`role="combobox"` → `ariaLabel` ya da ilişkili etiket".
4. **Küçük ve bağımsız:** R6, R7, R10, R11, R12, R13.
5. **Ölç, sonra düzelt:** R9 (`EXPLAIN ANALYZE`, projeksiyon, indeks).
6. **Tarayıcıda doğrula:** R18.

---

## Düzeltme durumu (aynı gün, yayından ÖNCE)

Ürün kararları (kullanıcı, 2026-09-25):
- **R1:** ekip arkadaşının değişikliği YALNIZ takımı boş izlemelerde görünür; başka takımın izlemesindeki
  değişiklik yalnız o takıma.
- **R2:** ADMIN / AUDIT rolündeki aktörlerin eylemleri ekip görünümüne GİRMEZ (yalnız admin/denetçi görür).
  Ekip arkadaşlarının birbirinin eylemlerini tam ayrıntıyla görmesi bilinçli olarak sürer (PII maskelemesi yok).
- **R8:** ekip denetim görünürlüğü her zaman açık; matris metni "sistem geneli denetim" olarak düzeltildi.

| # | Durum | Nerede |
|---|---|---|
| R1 | DÜZELTİLDİ | `MonitorChangeLogRepository` üç sorgu: aktör yolu `c.teamId IS NULL` ile sınırlı; `HistoryQueryGrammarTest` yabancı takım satırı (mutasyonla ısırtıldı) |
| R2 | DÜZELTİLDİ | `AuditLogRepository.findAdvanced` + `AuditController.teamVisible`: `actorRole ∉ {ADMIN, AUDIT}`; `AuditLogRepositoryTest` + `AuditControllerTest` (ısırtıldı) |
| R3 | DÜZELTİLDİ | `PortCheckerService.readStatusLine`: 8 KB tavan + toplam süre tavanı; 3 birim testi |
| R4 | DÜZELTİLDİ | Ön yüz: düzenlemede takım yalnız `canMoveTeam` (rol ADMIN) ile; pano yolu prop'ları. **Ek arka uç açığı kapatıldı:** `AdminController.updateInventory` hedef takımı da yönetim kapsamında doğruluyor (kapsamlı müdür kaydı kapsam dışı takıma taşıyamaz; `AdminControllerTest`, ısırtıldı) |
| R5 | DÜZELTİLDİ | AlertHistory / IncidentHistoryPage / WeeklyReportsPage (+ UserManager, InventoryTable) satır adlı onay kutuları; `rowAccessibleNames` yeni kural |
| R6 | DÜZELTİLDİ | `MonitorSparklineService.pct`: hata varken 99,99 |
| R7 | DÜZELTİLDİ | Ekip kapsamında kaynak/aktör geçmişi `findAdvanced` ile sorguda kapsamlanır |
| R8 | DÜZELTİLDİ | `PermissionCatalog` yorumları + `perm.res.audit_log.read` TR/EN metni |
| R9 | DÜZELTİLDİ | Üye projeksiyonu `findMemberIdentities` (id + küçük harf ad) + `idx_audit_actor_team_time` indeksi (patch + `@Index`) |
| R10 | DÜZELTİLDİ | PermissionMatrix yükleme hatası: danger AlertBanner + Yeniden dene |
| R11 | DÜZELTİLDİ | WeeklyReportsPage açılış sırası (`loadSeq`) + geç gelen kilit bırakılır |
| R12 | DÜZELTİLDİ | AuditLogViewer `logsSeq`, CommandPalette `seq` |
| R13 | DÜZELTİLDİ | `usePagination` `sizeOptions` — sunulan seçenek her zaman kabul edilir |
| R14 | DÜZELTİLDİ | WeekDatePicker klavye + adlı ay düğmeleri + Escape; DateTimeField temizle düğmesi (shadcn Button) |
| R15 | DÜZELTİLDİ | `a11y.rowAction` ile satır adlı kart/satır eylemleri (9 izleme sayfası, CertificateCard, MW, Incidents, DeploymentHistory, PushLog, InboxBell) |
| R16 | DÜZELTİLDİ | MonitorGuideButton kapat adı, Sparkline dekoratif/adlı, matris ADMIN hücresi, Dialog varsayılan metinleri dil-duyarlı (`dlg.*`) |
| R17 | DÜZELTİLDİ | MultiTeamSelect `aria-checked`, seçici adları (`ariaLabelledBy`/`id`, 69 çağrı yeri), daraltılmış kenar çubuğu grup başlıkları odaklanamaz |
| R18 | AÇIK (tarayıcıda doğrulanacak) | Öneri: "stretched button" deseni — ayrı iş |

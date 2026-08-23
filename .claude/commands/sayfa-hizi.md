---
description: SiteMonitor'e 10. izleme türü — Sayfa Hızı (Page Speed) izlemesi. StatusCake ekranından uyarlanan form (Test Detayları / Alarm Eşikleri: min-max boyut + azami yük süresi / Gelişmiş: UA, özel başlıklar, basic auth, tracker hariç tutma, DNT), kaynak-toplama ölçüm motoru (TTFB, toplam bayt, istek sayısı, tür kırılımı), sweep + teyitli alarm + takım yönlendirme, kontrol geçmişi + grafikler, 10.-tür entegrasyon kontrol listesi, eksiksiz testler.
argument-hint: [tasarim|backend|frontend|hizli] (opsiyonel — boş bırakılırsa TAM kapsam koşulur)
---

# /sayfa-hizi — Sayfa Hızı İzlemesi (10. Tür)

Görevin: SiteMonitor'e **Sayfa Hızı (Page Speed)** izleme türünü, projenin diğer 9 türüyle AYNI
omurgaya oturacak şekilde eklemek. Referans StatusCake ekranlarından uyarlanan çekirdek:

- **Liste görünümü:** Durum · URL · Ağ yolu · Son yük süresi · Sayfa boyutu · İstek sayısı +
  eylemler (duraklat/düzenle/sil) — SiteMonitor'ün mevcut monitör sayfası liste/kart standardıyla.
- **Form (akordeon bölümlü):** *Test Detayları* (ad, URL, kontrol aralığı, takım, grup) ·
  *Alarm Eşikleri* (Min. Boyut kb / Maks. Boyut kb / Maks. Yük Süresi ms — birim ekli girişler) ·
  *Gelişmiş* (User-Agent, özel başlıklar, basic auth, tracker hariç tutma, DNT) — hangileri v1'e
  girer K3/K4 kararlarıyla.
- **Ölçüm:** her kontrolde sayfanın HTML'i + alt kaynakları çekilir; yük süresi, toplam bayt,
  istek sayısı, tür kırılımı (js/css/img/font) ve en büyük N kaynak kaydedilir.
- **Alarm:** eşik aşımında SiteMonitor'ün teyit mekanizması (N ardışık doğrulama) + takım-scoped
  eskalasyon; günlük yeniden-alarm dedupe'u; çözülünce çözüm bildirimi.
- **Min. Boyut alarmının değeri** (StatusCake'ten aynen korunur): sayfa beklenenden KÜÇÜKSE de
  alarm — bozuk deploy / boş hata sayfası "200 OK" dönerken yakalanır.

Yüzeysel iş KABUL EDİLMEZ: her faz kanıtla raporlanır; ürün kararları (K1–K10) tahmin edilmez,
seçenek + öneriyle kullanıcıya sorulur. "Kopyala-yapıştır tür ekleme" tuzağına karşı: en yakın iki
tür (page=9, scripted=7) ile diff satır satır karşılaştırılır ve §Entegrasyon Kontrol Listesi'nin
HER maddesi işaretlenmeden iş bitmiş sayılmaz.

Kapsam argümanı: `$ARGUMENTS`
- Boş → tüm fazlar (0–7). `tasarim` → Faz 0–1 (karar dosyası, kod yok). `backend` → 0,1,2,3,6,7.
- `frontend` → 0,4,5,6,7 (API hazır varsayılır; değilse raporla). `hizli` → 0–5 (testler asgari).

## Değişmez kurallar (her fazda geçerli)

1. **Aynı omurga, sıfır özel yol:** CRUD `MonitoringController` üslubuyla (`ok()`/`badRequest()`,
   `resolveWriteTeam`/`canOperateTeam`/`resolveTeamChange`, `permissionService.require`); geçmiş
   `CheckHistoryService.Source` üzerinden; seri `buildResponseSeries` hunisinden (İSTİSNASIZ —
   2026-08-06 scripted çökmesi dersi); aktivite `activityLog.recordLifecycle/recordCheck`; denetim
   `auditService.recordAction` + `AuditDiff` (yeni alanlar `MON_FIELDS`'e girer). `/izleme-gecmisi`
   geliştirmesi uygulanmışsa (App.jsx'te `monitorchanges` sekmesi var — Faz 0'da doğrula) yeni tür
   onun yazım noktalarına ve kind eşlemesine DE kaydolur.
2. **SSRF ve gizlilik pazarlık dışı:** her fetch `SsrfGuard`'dan geçer; basic auth parolası
   `SecretCipher` (AES-GCM) ile saklanır, API'den ASLA düz dönmez (write-only), diff/audit'te
   `SecretMask` maskesi; özel başlık DEĞERLERİ de maske kapsamında. Sabit-kodlu secret taraması
   (scripted'daki `scanHardcodedSecrets` yaklaşımı) özel başlıklara uygulanır.
3. **Performans bütçesi:** kaynak sayısı ve okunan bayt SINIRLI (PageChecker'ın
   `MAX_RESOURCES_PER_CHECK` / `MAX_TOTAL_RESOURCES` deseni + toplam bayt tavanı); boyutlar önce
   HEAD `Content-Length`, yoksa akışla sayılıp kesilir; eşzamanlılık paylaşılan `certCheckExecutor`
   + monitör başına `resourceConcurrency`; sweep kilidi `<tür>-sweep` + TTL; liste endpoint'i
   N+1 sorgu YAPMAZ (mevcut enrich + harita deseni).
4. **Şema kuralı:** yeni tablolar `ddl-auto=update` ile doğar AMA `applySchemaPatches()`'e
   idempotent patch'ler eklenir; retention `RetentionCatalog`'a kaydolur (append-only check
   tablosu retention'sız BÜYÜR); türetilmiş `deleteBy…` repo metodları `@Transactional` + `int`.
5. **i18n:** tüm anahtarlar TR+EN aynı değişiklikte (`i18n-parity.test.jsx`); `.properties`
   değerlerinde ham Türkçe karakter yok (`\uXXXX`).
6. **Tasarım dili:** form akordeon bölümleri, `SegmentedControl` (kontrol aralığı), `Field`
   (birim ekli kb/ms girişleri), `TagInput` (hariç tutma desenleri), `SearchableSelect`,
   `MonitorHowBox` + `monitorGuides.js` girişi, kart/detay-modal düzeni diğer monitör
   sayfalarının birebir standardı. İkon yalnız `lucide-react`; dark theme elle doğrulanır.
7. **Dürüst etiketleme:** K1a motoru JS ÇALIŞTIRMAZ — UI hiçbir yerde "tarayıcı deneyimi/Core Web
   Vitals" iddia etmez; "statik kaynak analizi" olduğu yardım metninde ve MonitorHowBox'ta açıkça
   yazar. Ölçülemeyen (JS ile sonradan yüklenen kaynak) sınırı belgelenir.
8. **Alarm tutarlılığı:** üç bildirim yolu (ilk alarm / çözüm / manuel yeniden gönder) AYNI takım
   çözümünü kullanır (`event.getTeamId()` — DOMAINMON "müdüre gitti" bug'ının dersi) ve yeniden
   gönderim içeriği son `PageSpeedCheck`'ten YENİDEN kurulur (boş mail dersi). Müdür/eskalasyon
   kişileri EKLENMEZ (takım-only — mevcut standalone politikası). Günlük dedupe korunur;
   `EscalationServiceTest` sözleşmeleri bozulmaz.
9. Ortam Windows (`JAVA_HOME=C:\Program Files\Zulu\zulu-25`, Maven
   `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`, Node 24); smoke öncesi
   `mvn package -DskipTests` + `npm run build`. Hiçbir şey commit'lenmez; coverage floor yalnız
   yukarı; `TESTING.md` etkilenirse güncellenir.

## Keşifte doğrulanmış altyapı gerçekleri (2026-08-23 — yeniden keşfetme, DOĞRULA ve kullan)

- **Mevcut 9 tür:** cert envanteri (uptime/ssl) + MonitoringController'da PORT/DNS/KEYWORD/HTTP/
  PAGE/SCRIPTED/DOMAIN/PING. Page "9. tür" olarak alan enjeksiyonuyla eklendi (`pageMonitorRepo`
  bloğu ~108) — 10. tür için aynı desen.
- **Fetch çekirdeği hazır:** `PageCheckerService` — Jsoup ile kaynak envanteri (`inventory(bytes,
  baseUrl, …)` ~246), kaynak tavanları (`MAX_RESOURCES_PER_CHECK` ~272, `MAX_TOTAL_RESOURCES`
  ~216), HEAD-önce/GET-fallback doğrulama, `resourceConcurrency`, ana sayfa `bytes` + sha256 zaten
  ölçülüyor (`summarize(..., hash, bytes)` ~537). Sayfa Hızı motoru bu çekirdeğin ÜZERİNE kurulur —
  ikinci bir Jsoup hattı yazılmaz (K2).
- **Tür-üstü form/davranış alanları:** `intervalSeconds/timeoutMs/confirmAttempts/
  confirmIntervalSeconds/recoveryChecks/recoveryIntervalSeconds/active/teamId/groupName` — create/
  update kalıpları PORT bloğunda (799–897) net; clamp yardımcıları (`clampAttempts/clampInterval/
  clampRecovery`) mevcut. `standalone=true`, kopyada `active` devri, çift-kayıt reddi desenleri de.
- **Defaults endpoint'i tür başına:** `GET /monitoring/defaults` (150) — `site.monitor.<tür>.default-*`
  anahtarları `appSettings`'ten; yeni tür buraya + `AppSettingsCatalog`'a (Genel Ayarlar → Kontrol
  Sıklığı canlı besliyor) girer.
- **Sweep düzeni:** `SchedulerService` — tür başına `<tür>-sweep` scheduler_lock (crash sonrası
  kendi kilidini silme deseni ~357–360), in-memory `checkDue` vade takibi (yorum ~141: "testler
  reflection ile bağlı"), sweep-lock TTL 2 dk (~202). Paylaşılan `certCheckExecutor`.
- **Teyit makinesi:** `MonitoringOutageService` — tip-izolasyonlu N-ardışık-teyit; PAGE türü
  `TYPE_PAGE_DOWN`/`TYPE_PAGE_INTEGRITY` ile bağlı (EscalationService 143–146). Yeni tipler aynı
  yere: sabitler + `isStandaloneMon` seti (159–164) + tip-izolasyon.
- **Kontrol Geçmişi v2:** endpoint başına `CheckHistoryService.Source<T>` (page/total/fail/
  histogram/bounds lambda'ları) + `resolve(Query, historyRetentionDays("<kind>"))` + `execute(src,
  r, domain, Set.of(TYPE_…))` — ssl-history örneği 905–928. Frontend ortak `CheckHistoryTab
  kind=… listKey=…`.
- **Seri sözleşmesi:** her `*/response-series` `buildResponseSeries` hunisinden;
  `MonitoringControllerTest.responseSeries_contract_allEndpoints` eşlemeleri REFLEKTİF sayar —
  kayıt unutulursa build düşer (bilinçli). Chart'lar savunmacı (`typeof s?.ts === 'string'`).
- **Frontend tür yüzeyi:** `App.jsx` `VALID_TABS` (102–105; `monitorchanges` zaten listede —
  /izleme-gecmisi durumunu Faz 0'da doğrula) + tab render blokları (~1270); her tür kendi
  `*MonitorPage.jsx`; detay modalı `modal-tab` (control/alerts/chart/notes) + `CheckHistoryTab` +
  lazy `MonitorNotes`; `monitorGuides.js` başındaki yorum: "Yeni izleme türü eklenince buraya bir
  giriş ekle (proje standardı gereği MonitorHowBox da)". `MonitorCardActions/Meta`, `DensityStrip`,
  `ResponseTimeChart` ortak.
- **Ham girdi güvenlik emsali:** PORT `sendData` (ham payload) yalnız admin (SSRF yorumu 807/862) —
  özel başlık/basic-auth kararlarında emsal (K4).
- **Proxy:** envanterde per-domain `useProxy` deseni + `shouldBypassProxy` mevcut (K8 "Ağ yolu").

## Faz 0 — Keşif doğrulaması + karar noktaları

Gerçekleri doğrula; scripted (7.) ve page (9.) türlerinin eklendiği commit'lerin diff kapsamını
çıkar (dokunulan dosya listesi = kontrol listesinin denetimi). `monitorchanges` sekmesinin arkasında
/izleme-gecmisi altyapısı var mı bak (varsa kural 1'in son cümlesi zorunlu kapsama girer).
Sonra kararları seçenek + öneriyle sun (`tasarim` modunda `docs/` altına karar notu):

- **K1 — Ölçüm motoru:** (a) **statik kaynak toplama (ÖNERİLEN):** PageChecker fetch çekirdeğiyle
  HTML + alt kaynaklar; metrikler TTFB, HTML indirme süresi, toplam süre, toplam bayt (transfer),
  istek sayısı, tür kırılımı, en büyük N kaynak. Ek bağımlılık yok, mevcut proxy/SSRF/timeout
  disiplini aynen. Sınır: JS çalışmaz (kural 7). (b) k6 browser modülü: gerçek tarayıcı metrikleri
  ama pod imajına Chromium + k6 browser bağımlılığı, kaynak tüketimi, kurumsal ortam riski —
  ancak faz-2 hedefi olarak tasarımda yer bırakılır (motor arayüzü `PageSpeedEngine` soyutlanır).
  (c) Google PSI API: dış bağımlılık, iç domainlerde çalışmaz — önerilmez.
- **K2 — PageMonitor (9. tür) ile ilişki:** (a) **ayrı 10. tür (ÖNERİLEN):** amaç farklı (bütünlük
  vs performans), eşikleri/alarmları/grafikleri farklı; ortak fetch yardımcıları PageChecker'dan
  ÇIKARILIP paylaşılır (kopyalanmaz). (b) PageMonitor'e "performans modu" eklemek — form ve alarm
  matrisini şişirir, önerilmez. Karara göre iki tür arasında çapraz öneri UI'ı (E7).
- **K3 — Gelişmiş alanların v1 kapsamı (StatusCake uyarlaması):** her biri için AL/ALMA sor:
  **User-Agent** (öneri: AL — tek metin, varsayılan `SiteMonitor-PageSpeed/x.y`), **DNT başlığı**
  (öneri: AL — masrafsız toggle), **tracker hariç tutma** (öneri: AL — `excludePatterns` TagInput'u
  + koddan gelen hazır tracker alan listesi toggle'ı; PageMonitor'ün excludePatterns'iyle aynı
  semantik), **Throttling** (öneri: ölçüm YAPMA — bant genişliği simülasyonu statik motorda dürüst
  değil; bunun yerine karttaki "tahmini süre @ 3G/4G" TÜRETİLMİŞ göstergesi E4'te), **Viewport**
  (öneri: v1'de ALMA — yalnız gerçek tarayıcı motorunda anlamlı; şema alanı rezerve edilebilir).
- **K4 — Kimlikli istekler:** **Basic auth** (öneri: AL — SecretCipher şifreli, write-only,
  maskeli) ve **özel başlıklar** (öneri: AL ama `sendData` emsali gibi YALNIZ admin; ya da
  başlık-adı beyaz listesi ile herkese — seçtir). İkisi de audit diff'inde maskeli (kural 2).
- **K5 — Eşik modeli:** ekrandaki üçlü aynen: `minSizeKb` / `maxSizeKb` / `maxLoadtimeMs`
  (hepsi opsiyonel; boş = o eşik kapalı) + istek-sayısı eşiği `maxRequests` (öneri: EKLE —
  ekranda listede var, eşiği ucuz). Teyit/kurtarma alanları tür-üstü kalıptan aynen.
- **K6 — Alarm tipleri:** öneri: `TYPE_PAGESPEED_SLOW` (yük süresi) + `TYPE_PAGESPEED_SIZE`
  (min/maks boyut + istek sayısı — detay metinde hangisi) + erişilemezlik için mevcut sayfa
  deseni gibi ayrı `TYPE_PAGESPEED_DOWN` mu yoksa hata durumunda SLOW üzerinden mi — seçtir.
  Hepsi `isStandaloneMon`'a girer (takım-only, kural 8).
- **K7 — Grafikler:** response-series = yük süresi (standart huni + sözleşme testine kayıt).
  Boyut ve istek sayısı serileri: (a) **aynı endpoint'e `?metric=` parametresi (ÖNERİLEN —
  tek kayıt, DTO şekli aynı)** (b) ayrı endpointler. Detay modal grafiğinde `SegmentedControl`
  ile metrik değiştirici (Süre / Boyut / İstek); chart savunmacı filtreyi korur. Boyut ekseni
  insancıl (kb/MB) — mevcut chart bileşeninin eksen biçimlendiricisi yetmiyorsa küçük prop.
- **K8 — "Monitoring Region" uyarlaması → Ağ yolu:** tek bölgemiz var; StatusCake'in region'ı
  bizde anlamlısı (a) **per-monitör `useProxy` (ÖNERİLEN** — envanter emsali; WAF'lı sayfalar
  proxy'den) (b) hiç alma. UI'da "Ağ yolu: Doğrudan / Proxy" seçimi + listede rozet.
- **K9 — Kontrol aralığı sınırı:** sayfa hızı kontrolü ağır (onlarca istek) — öneri: tür tabanı
  **5 dk** (SegmentedControl seçenekleri 5dk/15dk/30dk/1sa/6sa/1gün; varsayılan 30 dk;
  `site.monitor.pagespeed.default-interval-seconds`). StatusCake'teki 1 dk'yı bilinçli VERMİYORUZ —
  onay iste.
- **K10 — Raporlama kapsamı:** haftalık rapor/KPI şeridine sayfa-hızı özeti (en yavaş 5 sayfa,
  haftalık medyan değişimi) bu fazda mı sonra mı (öneri: sonra — E5).

## Faz 1 — Veri modeli ve şema

- **`PageSpeedMonitor`** (`page_speed_monitors`): tür-üstü alanlar (name/url/active/standalone/
  teamId/groupName/interval/timeout/confirm*/recovery* — PORT kalıbı) + `minSizeKb`, `maxSizeKb`,
  `maxLoadtimeMs`, `maxRequests` (K5), `userAgent`, `sendDnt`, `excludePatterns` (CSV),
  `blockTrackers` (bool), `customHeadersJson` (K4; maskeli), `authUser`, `authPassEnc` (K4;
  SecretCipher), `useProxy` (K8), `resourceConcurrency`, `createdAt/updatedAt` (+ /izleme-gecmisi
  varsa createdBy ailesi). İndeksler: `active`, `teamId`.
- **`PageSpeedCheck`** (`page_speed_checks`, append-only): `monitorId`, `checkedAt`, `ok`,
  `status` (OK/SLOW/TOO_BIG/TOO_SMALL/TOO_MANY_REQ/ERROR), `httpStatus`, `totalMs`, `ttfbMs`,
  `htmlMs`, `totalBytes`, `requestCount`, `breakdownJson` (tür başına adet+bayt),
  `topResourcesJson` (en büyük N: url/tür/bayt/ms), `capped` (tavana takıldı bilgisi — kural 3
  şeffaflığı), `error`, `errorClass` (mevcut hata-sınıflandırma sözlüğüyle), `runId`.
  İndeks: (`monitorId`,`checkedAt`).
- `applySchemaPatches()` idempotent CREATE TABLE + indeksler; `RetentionCatalog` girişi
  (`site.monitor.pagespeed.retention-days`, komşu türlerin varsayılanıyla hizalı) +
  `historyRetentionDays("pagespeed")` eşlemesi.

## Faz 2 — Ölçüm motoru (`PageSpeedCheckerService`)

- `PageSpeedEngine` arayüzü + `StaticResourceEngine` (K1a): (1) ana HTML GET — TTFB (ilk bayt) ve
  HTML süresi ayrı ölçülür, gövde bayt sayılır (`Accept-Encoding: gzip` ile TRANSFER boyutu —
  tarayıcı gerçeğine yakın; not düşülür); (2) Jsoup envanteri (PageChecker'dan ÇIKARILAN ortak
  yardımcı — kural 1/K2); (3) hariç tutma: excludePatterns + `blockTrackers` ise koddaki
  `TRACKER_DOMAINS` listesi (test edilebilir sabit); (4) kaynaklar `resourceConcurrency` ile
  HEAD-önce (Content-Length) / gerekirse GET-akış-sayımı, tavanlar + toplam bayt bütçesi (kural 3);
  (5) toplam süre = duvar saati (paralel gerçeği) + ayrıca "seri toplam" değil — belgele;
  (6) sonuç `PageSpeedCheck` + durum türetimi (K5 eşikleri; birden çok eşik aşımı → en ağırı,
  detayda hepsi); (7) UA/DNT/özel başlık/basic-auth uygulanır; her URL `SsrfGuard`'dan geçer;
  redirect zinciri ana sayfa için izlenir (limitli).
- Hata sınıflandırma mevcut `errorClass` sözlüğüyle (NETWORK/DNS/SSL/HTTP…); NETWORK-sınıfı tek
  retry (cert checker politikasıyla tutarlı — onaylat).

## Faz 3 — Sweep, teyit, alarm

- `SchedulerService`: `pagespeed-sweep` kilidi + `checkDue` vadesi + startup kilit temizliği +
  aktif monitör taraması — page/scripted sweep'lerinin birebir simetriği; reflection'la bağlı
  scheduler testlerine yeni tür kaydı.
- `MonitoringOutageService`: yeni tipler tip-izolasyonuyla; N-teyit → alarm, kurtarma → çözüm.
- `EscalationService`: `TYPE_PAGESPEED_*` sabitleri + `isStandaloneMon` + üç yol tutarlılığı +
  yeniden-gönderim içeriği son `PageSpeedCheck`'ten (kural 8); `EmailTemplateBuilder`'a detay
  tablosu (süre/boyut/istek + eşikler + en büyük 3 kaynak); `WebhookService` payload'ı;
  `notification_logs` olağan akışı.
- Manuel tetik: `POST /monitoring/pagespeed/{id}/check` + cooldown (page/scripted'daki
  `ConcurrentHashMap` deseni) + `monitoring.trigger` izni.

## Faz 4 — API

- CRUD: `GET/POST /monitoring/pagespeed`, `PUT/DELETE /monitoring/pagespeed/{id}` — PORT kalıbı
  (soft-delete, kopya-active devri, çift URL reddi, `resolveWriteTeam`, audit MONITOR_CREATE/
  UPDATE/DELETE + AuditDiff; yeni alanlar `MON_FIELDS`'e, hassaslar SecretMask'e).
- Geçmiş: `GET /monitoring/pagespeed/{id}/history` — `CheckHistoryService.Source` + alarm eşleme
  (`Set.of(TYPE_PAGESPEED_…)`) + CSV.
- Seri: `GET /monitoring/pagespeed/{id}/response-series` (+K7 `?metric=duration|bytes|requests`) —
  `buildResponseSeries` hunisi + sözleşme testine kayıt.
- Test uçları: `POST /monitoring/pagespeed/test` (kaydetmeden dene — form önizlemesi; diğer
  türlerin `/test` deseni) — SSRF + izin aynen.
- `defaults` endpoint'ine `pagespeed` girişi + `AppSettingsCatalog` anahtarları (Genel Ayarlar →
  Kontrol Sıklığı yüzeyinde göründüğünü doğrula).

## Faz 5 — Frontend

- **`PageSpeedMonitorPage.jsx`** (en yakın akraba sayfadan türetilir — PageMonitorPage/
  HttpMonitorPage; diff satır satır): üst istatistik şeridi (`MonitorStatsBar`), grup/takım/durum
  filtreleri, kart listesi (kart metasında: son yük süresi, boyut, istek sayısı, Ağ yolu rozeti,
  `Sparkline` mini eğilim), `MonitorCardActions` (duraklat/düzenle/kopyala/sil), boş durum.
- **Form modalı** ekran görüntüsündeki üç akordeon bölümün SiteMonitor karşılığı: *Test
  Detayları* (ad, URL, `SegmentedControl` kontrol aralığı — K9 seçenekleri, takım
  `CheckTeamPicker`/`MultiTeamSelect`, grup, Ağ yolu K8) · *Alarm Eşikleri* (kb/ms/adet birim ekli
  `Field`'lar + teyit/kurtarma gelişmişleri) · *Gelişmiş* (K3/K4 seçilenleri: UA, DNT toggle,
  tracker toggle + `TagInput` hariç tutma, özel başlıklar, basic auth — parola alanı write-only
  placeholder "••• kayıtlı"). `MonitorHowBox` + `monitorGuides.js` TR/EN girişi.
- **Detay modalı:** `modal-tab` — Kontrol Geçmişi (`CheckHistoryTab kind="pagespeed"` + kolonlar:
  süre/boyut/istek/durum), Alarmlar (`AlertHistory`), Grafik (K7 metrik değiştirici +
  `ResponseTimeChart` savunmacı), **Kaynak Dökümü** (son kontrolün tür kırılımı + en büyük N kaynak
  tablosu — bayt insancıl, `CopyButton` URL; `capped` uyarı notu), Notlar (`MonitorNotes
  type="PAGESPEED"`).
- `App.jsx`: `VALID_TABS`'a `pagespeed`, tab render bloğu, `Nav` girişi (lucide ikon — ör.
  `Gauge`), `mtab` URL senkron whitelist'i; `api/client.js`'e `api.monitoring.pageSpeed*` ailesi;
  i18n TR+EN; dark theme el doğrulaması.

## Faz 6 — Testler (eksiksiz)

- **Motor:** `PageSpeedCheckerServiceTest` — boyut toplama (Content-Length'li/`chunked`/tavan
  aşımı `capped`), TTFB/süre ayrımı, hariç tutma + tracker listesi, UA/DNT/özel başlık/basic-auth
  uygulanması (WireMock ya da mevcut test HTTP sunucu deseni — projede hangisi varsa o), SSRF
  reddi, timeout, hata sınıflandırma, durum türetimi (K5 kombinasyonları: yalnız min, yalnız max,
  ikisi, istek eşiği, hepsi kapalı).
- **Controller:** CRUD + IDOR (yabancı takım 403/404) + çift URL reddi + kopya semantiği +
  audit diff (hassas alan maskeli) + `/test` ucu + cooldown; **sözleşme testleri:**
  `responseSeries_contract_allEndpoints`'e yeni kayıt (reflektif sayım güncellenir), CheckHistory
  kind, defaults anahtarları.
- **Alarm:** teyit makinesi akışı (N-1'de alarm YOK, N'de VAR; kurtarma), eskalasyon yönlendirme
  (takım-only; üç yolun tutarlılığı; yeniden-gönderim içeriği dolu — boş-mail regresyonu),
  günlük dedupe (`EscalationServiceTest` yeşil kalır).
- **Şema/retention:** patch kaynak-tarama, `RepositoryWriteTransactionGuardTest`, retention
  kaydının kırpma çalıştığı.
- **Frontend:** sayfa render + filtreler, form modal (birim alanları, toggle'lar, parola
  write-only), detay sekmeleri, kaynak dökümü tablosu, bozuk seri kaydı düşürme, `i18n-parity`,
  guide girişi. Mock API — gerçek fetch kaçmaz.

## Faz 7 — Doğrulama, smoke ve rapor

1. `mvn -B clean verify` → `npm run test` → `npm run build` → `mvn package -DskipTests` →
   `start-local.ps1` → `/health` UP.
2. Smoke: monitör oluştur (bilinen bir iç sayfa) → manuel kontrol → kart metriklerinin dolduğu;
   eşik ihlali senaryosu (maxLoadtimeMs'i 1 ms yap → teyit sonrası alarm + mail içeriği dolu →
   eşiği düzelt → çözüm bildirimi); grafik üç metrikte de çizer; geçmiş CSV; yabancı takımla 403;
   dark theme; TR/EN.
3. Rapor: dosya listesi, test çıktıları, K kararları, §Entegrasyon Kontrol Listesi'nin işaretli
   hâli, bilinen sınırlar (JS-sonrası kaynaklar ölçülmez; transfer-boyut yaklaşımı).

## Entegrasyon Kontrol Listesi (10. tür — HER madde işaretlenmeden bitmedi)

Backend: entity×2 + repo'lar · applySchemaPatches · RetentionCatalog + historyRetentionDays ·
defaults + AppSettingsCatalog · SchedulerService (sweep + kilit + checkDue + startup temizlik +
reflection'lı scheduler testleri) · MonitoringOutageService tip kaydı · EscalationService
(TYPE_* + isStandaloneMon + üç yol + mail içerik + webhook) · ActivityLogService tür sabiti +
recordLifecycle/recordCheck · AuditDiff MON_FIELDS + SecretMask · response-series hunisi +
sözleşme testi · CheckHistoryService Source · manuel tetik + cooldown · (varsa) /izleme-gecmisi
yazım noktaları + kind. — Frontend: VALID_TABS + tab render + Nav + `mtab` whitelist · sayfa +
form + detay modal sekmeleri · api client ailesi · monitorGuides + MonitorHowBox · i18n TR+EN ·
kart meta/aksiyonlar · dark theme. — Kalite: tüm sözleşme/parite/guard testleri yeşil.

## Zenginleştirme önerileri (kullanıcıya sun — şimdi mi sonra mı)

- **E1 — Bütçe temel çizgisi (baseline):** "son 7 gün medyanına göre ±%X sapma" alarmı — sabit
  eşiğin yanına oransal bozulma tespiti (deploy sonrası şişme yakalar).
- **E2 — Kontrol karşılaştırma:** iki kontrolü yan yana kıyasla — hangi kaynak büyüdü/eklendi
  (topResourcesJson diff'i); "bu deploy sayfayı 800 kb şişirdi" cevabı.
- **E3 — En büyük kaynaklar önerisi:** kaynak dökümünde basit ipuçları (sıkıştırılmamış görsel,
  duplicate JS, font sayısı) — kural tabanlı, iddiasız.
- **E4 — Türetilmiş "tahmini süre @ 3G/4G":** toplam bayttan bant genişliği modeliyle gösterge
  (ölçüm değil, etiketli tahmin — K3 throttling'in dürüst karşılığı).
- **E5 — Haftalık rapor bölümü:** takımın en yavaş/en şişkin 5 sayfası + haftalık eğilim (K10).
- **E6 — PageMonitor çapraz önerisi:** aynı URL'de bütünlük izlemesi yoksa "Sayfa Bütünlüğü de
  ekle" (ve tersi) ipucu satırı.
- **E7 — k6-browser motoru (faz-2):** `PageSpeedEngine` soyutlaması üzerinden gerçek tarayıcı
  metrikleri (LCP/FCP) — imaj bağımlılığı kararıyla ayrı bir geliştirme.
- **E8 — Public status kırpımı:** takım dışına salt-okunur hız kartı (PublicStatsController deseni).

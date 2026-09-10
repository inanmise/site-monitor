---
description: Site Monitor için uçtan uca bellek sızıntısı (memory leak) seferberliği — hs_err kanıt analizi, backend/frontend statik denetim, leak-pinleyen testlerin yazılıp koşulması, soak (dayanıklılık) ölçümü, düşük-bellek profili ve performans regresyon kapısı ile final rapor.
argument-hint: [backend|frontend|soak|hizli|profil] (opsiyonel — boş bırakılırsa TAM kapsam koşulur)
---

# /bellek-denetim — Bellek Sızıntısı Avı ve Düşük-Bellek Seferberliği

Görevin: Site Monitor'de **bugün sızdıran veya ileride sızdırabilecek her bellek konusunu** bulmak,
kanıtlamak, düzeltmek ve düzeltmeyi testle kilitlemek; sonra uygulamayı **ölçülmüş, mümkün olan en
düşük bellek ayak iziyle** ve **performanstan ödün vermeden** çalışır hale getirmek.
Yüzeysel "leak yok" raporu KABUL EDİLMEZ: her hüküm sayıyla (heap/NMT/thread/handle ölçümü,
test çıktısı, dosya:satır) kanıtlanır. "Leak buldum" da tek başına yetmez — sızıntı **yeniden
üretilir**, düzeltilir, düzeltme **önce kırmızı sonra yeşil** bir testle pinlenir.

Kapsam argümanı: `$ARGUMENTS`
- Boş → tüm fazlar (0–8) sırayla.
- `backend` → Faz 0, 1, 3, 4, 5, 6, 7, 8.
- `frontend` → Faz 0, 2, 3 (frontend kısmı), 5, 7, 8.
- `soak` → Faz 0, 4, 8 (yalnız çalışan uygulama ölçümü + rapor; kod değişikliği yok).
- `hizli` → Faz 0, 1, 2 ve rapor (yalnız statik denetim; test yazımı ve soak atlanır).
- `profil` → Faz 0, 6, 7, 8 (yalnız düşük-bellek ayarı + perf kapısı).

## Değişmez kurallar (her fazda geçerli)

1. **Önce ölç, sonra dokun, sonra tekrar ölç.** Hiçbir "düzelttim" iddiası önce/sonra ölçümü
   olmadan rapora giremez. Ölçü aletleri: `jcmd` (Zulu 25: `"C:\Program Files\Zulu\zulu-25\bin\jcmd.exe"`),
   `-Xlog:gc*`, `-XX:NativeMemoryTracking=summary`, JFR, `GC.class_histogram` diff'i.
2. **Performans SLA'sı pazarlık dışı:** k6 smoke (`perf/k6-smoke.js`, 50 VU × 30 s) hata oranı < %1,
   p95 < 500 ms. Bellek kazancı p95'i belirgin (>%10) kötüleştiriyorsa o ayar geri alınır ve rapora
   "denendi/geri alındı" olarak yazılır. Düşük bellek ↔ performans dengesinin hakemi ölçümdür.
3. **Sınırlı-tasarım ilkesi:** her in-memory birikim ya (a) sabit tavan + TTL ile sınırlanır,
   ya (b) yaşam döngüsü kanıtlanabilir şekilde kapanır (her put'un garantili remove'u), ya da
   (c) DB'ye taşınır. "Pratikte büyümez" bir gerekçe DEĞİLDİR — tavanı koda yaz, testle pinle.
4. **Bilinçli tasarım kararlarını "düzeltme":** Caffeine 300 sn LONG_TTL cache listesi
   (`CacheConfig` — 60 sn'e düşürmek dashboard thrash'ini geri getirir); günlük re-alert dedupe;
   toplu ağ kesintisi bastırması; `buildResponseSeries` hunisi; k6'nın her koşumda kısa ömürlü alt
   süreç olması. Bellek uğruna bu davranışlar değişmez; değişecekse önce kullanıcı onayı.
5. **Testler gerçek dış host'a bağlanmaz** (yerel `HttpsServer` + BouncyCastle deseni; frontend'te
   `vi.mock('../api/client', ...)`). Coverage floor'ları asla düşürülmez, yalnız yukarı ratchet'lenir.
6. Ortam Windows: `JAVA_HOME=C:\Program Files\Zulu\zulu-25`, Maven
   `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`, Node 24, PostgreSQL `localhost:5432`.
   `start-local.ps1` **paketlenmiş jar'ı** çalıştırır → koddan sonra `mvn package -DskipTests` şart.
7. Hiçbir şey commit'lenmez; tüm değişiklikler working tree'de kalır, final raporla onaya sunulur.
8. Soak/ölçüm koşuları biterken başlattığın her `java.exe` / `k6.exe` sürecini kapat
   (`Get-Process java,k6 -ErrorAction SilentlyContinue | Stop-Process -Force`) — kullanıcıyı uyararak.

## Faz 0 — Kanıt toplama ve taban çizgisi (baseline)

**0a. Çökme kanıtları — depoda hazır duran hs_err dosyaları.** Kökte ve `backend/` altında çok
sayıda JVM ölümcül hata logu var (`hs_err_pid*.log`, `replay_pid*.log`). Ön inceleme üç ayrı
imza gösterdi — üçünü de sınıflandırıp raporla:
- `hs_err_pid33600.log` (kök): Temurin **21.0.11** — `malloc failed to allocate 1048576 bytes,
  AllocateHeap` → native heap tükenmesi.
- `backend/hs_err_pid23248.log`: Zulu **25.0.3** — `Chunk::new` 32 KB ayrılamadı → **JIT derleyici
  arena'sı**; tipik olarak `mvn verify` sırasında (Surefire + JaCoCo) makine RAM'i doluyken görülür.
- `backend/hs_err_pid13412.log`: `G1 virtual space` için 256 MB `mmap` başarısız → JVM **açılışta**
  heap'i rezerve bile edememiş.

Her dosyadan çıkar: JRE, zaman damgası, hata türü, `Process memory` / `memory_total` bölümü, canlı
thread sayısı, o an koşan komut satırı. Sonra hükmü ver: bunlar **uygulama içi leak** mi, yoksa
**makine RAM aşırı taahhüdü** mü (aynı anda backend jar + `mvn` test JVM'i + Vite + tarayıcı)?
İkisi ayrı reçetedir: leak → Faz 1/4'te avlanır; overcommit → Faz 6'da bütçe/flag reçetesi yazılır
(ör. Surefire'a `argLine` ile `-Xmx` tavanı — `backend/pom.xml`'de şu an **hiç bellek sınırı yok**,
test JVM'i varsayılan %25-RAM heap'iyle açılıyor). İncelemeden sonra bu logların `.gitignore`
kapsamını doğrula ve temizlik önerisini rapora yaz (silme; `_to_delete/` önerisi yeterli).

**0b. Backend taban çizgisi.** Uygulamayı ölçüm bayraklarıyla başlat (PowerShell):
```powershell
$env:JAVA_OPTS = "-Xmx512m -Xlog:gc*:file=backend\gc-baseline.log " +
  "-XX:NativeMemoryTracking=summary -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=backend"
.\start-local.ps1     # /health = UP bekler
```
(`start-local.ps1` JAVA_OPTS'u geçirmiyorsa jar'ı aynı bayraklarla elle başlat ve bunu rapora not et.)
İlk 2 dakika ısınma sonrası kaydet: `jcmd <pid> GC.heap_info`, `jcmd <pid> VM.native_memory summary`
(baseline olarak `VM.native_memory baseline` da al), `jcmd <pid> Thread.print | Measure-Object -Line`,
`jcmd <pid> VM.classloader_stats`, `jcmd <pid> GC.class_histogram` (ilk 40 satır). RSS'i
`Get-Process java | Select WorkingSet64` ile al. Bu sayılar Faz 4 ve 6'nın referansıdır.

**0c. Frontend taban çizgisi.** `npm run build` sonrası `dist/assets` boyut dökümü (en büyük 10 chunk).
Bilinen ağırlıklar: `i18n/index.jsx` ~505 KB tek modül, `App.css` ~474 KB, `SystemHealth.jsx` ~122 KB,
`ScriptedMonitorPage.jsx` ~89 KB. React.lazy + Vite manualChunks zaten kurulu (`vite.config.js`,
`lazy-tabs-smoke.test.jsx`) — hangi sekmelerin hâlâ ana chunk'ta olduğunu ölç.

## Faz 1 — Backend statik bellek denetimi (dosya dosya)

Aşağıdaki envanter ön taramadan çıkan GERÇEK şüpheli/doğrulanacak noktalardır. Her maddeye üç
hükümden birini ver ve kanıtla: **SIZDIRIR** (yeniden üretilebilir büyüme) / **RİSK** (bugün değil
ama büyüyebilir — tavan ekle) / **SINIRLI-OK** (kanıtla ve Faz 3'te testle pinle).

**1a. Süresiz yaşayan in-memory durum:**
- `MonitoringOutageService` — 4 harita: `inFlight`, `recoveryUpCount`, `recoveryInFlight`,
  `suppressionActive` (`alertType:domain` anahtarlı). remove çağrıları var ama şunları kanıtla:
  (i) confirm/recovery zinciri **istisna** fırlatırsa ilgili key kalıyor mu (finally var mı)?
  (ii) bir monitör **silinince** o domain'in anahtarları temizleniyor mu, yoksa sonsuza dek mi kalıyor?
  (iii) `confirmExecutor`(4) + `recoveryExecutor`(2) daemon havuzları `@PreDestroy`/shutdown'a bağlı mı;
  içlerinde zincirlenmiş `schedule` görevleri kapanışta iptal ediliyor mu?
- `SchedulerService` (~254 KB — sınıfın TÜM alanlarını tara): sweep durumu, lock takibi, jitter,
  `manualRunPool` (virtual thread; cooldown'la sınırlı — cooldown atlanırsa ne olur?), açılıştaki
  ad-hoc `new Thread` (daemon mi? bean referanslarını sleep boyunca tutuyor — kabul edilebilir mi?).
- `EscalationService` / `StormService` / `AuditService` / `FailedLoginAnomalyService` — alan
  düzeyinde tüm Map/List/Set'leri listele; dedupe pencereleri bellekte mi DB'de mi, pencere dışı
  kayıtlar ne zaman düşüyor?
- `HttpMetricsService` — `history` deque 1440 ile sınırlı (OK) ama **bucket içi `endpoints`
  haritasının anahtar kardinalitesi** açık kapı: `HttpMetricsInterceptor` normalde
  `"METHOD route-şablonu"` kullanıyor (sınırlı — iyi), fakat **eşleşmeyen istekte ham URI'ye
  düşüyor**. Bir 404-tarama botu (`/wp-admin/...`, rastgele path'ler) dakikada yüzlerce benzersiz
  anahtar üretir → hem in-memory bucket hem `http_metric_minute` tablosu şişer. Eşleşmeyen
  istekleri tek `"(unmatched)"` anahtarına katla + bucket başına anahtar tavanı ekle.
- `HttpMetricsInterceptor` + `MetricsService` + Prometheus (`micrometer-registry-prometheus`) —
  **etiket kardinalite patlaması** klasiğidir: meter tag'lerinde ham URI/domain/kullanıcı var mı?
  `/actuator/prometheus` çıktısındaki seri sayısını say; monitör ekledikçe artıyorsa RİSK.
- `GeoIpService` — 10.000 tavan + tavana gelince `clear()` (kaba ama sınırlı). SINIRLI-OK olarak
  testle pinle; TTL süresi geçen girdilerin `get` edilmediği sürece silinmediğini not et (tavan var,
  kabul edilebilir).
- `AppSettingsService`, `TrustEvaluator` (canlı CA-bundle reload) — reload eski nesneleri bırakıyor
  mu (eski truststore/`SSLContext` referansı statikte kalıyor mu)?

**1b. Executor / thread hijyeni** (her biri için: kim kapatıyor + kuyruk sınırı ne?):
- `WebConfig` bean'leri: `certCheckExecutor` (20/50/kuyruk 5000, sayaçlı CallerRuns) ve
  `loginIssueMailExecutor` (2/4/kuyruk 100) — Spring bean olarak kapanır (OK), ama kuyruk 5000 ×
  görev başına tutulan nesne (closure'ın yakaladığı envanter/DTO) boyutunu hesapla; CallerRunsPolicy
  taşmada üreticiyi yavaşlatır (bilinçli — koru).
- `EmailNotificationService` — tek-thread'li `mail-retry` scheduler'ı: **421-retry kuyruğu sınırsız**
  ve her ertelenen mail closure'ı `InlineImage` byte[]'larını (logo + ekler) dakikalarca canlı tutar.
  SMTP uzun süre 421 dönerse kuyruk × mail gövdesi kadar bellek birikir → tavan (örn. bekleyen
  retry sayısı) + büyük eklerin diske/DB'ye düşürülmesi değerlendirilir. `@PreDestroy` var mı?
- `ProcessProbe.READER_POOL` — **statik `newCachedThreadPool`**: k6 + tanı araçları eşzamanlı
  patlarsa thread sayısı sınırsız; okuyucu thread stream'de bloke kalırsa (`destroyForcibly`
  sonrası EOF gelmezse) thread sızar. Tavanlı havuz veya virtual-thread'e geçişi değerlendir;
  her iki `getInputStream` okumasının TÜM yollarda kapandığını kanıtla (Windows'ta handle sızıntısı).
- `PageCheckerService.resourceExecutor`, `ScriptedCheckerService.execPool` (virtual) — shutdown bağlı mı?
- Her `@Async`/`CompletableFuture.supplyAsync` çağrısının hangi havuza gittiğini doğrula — havuzsuz
  `supplyAsync` commonPool'a kaçar.

**1c. Kaynak yaşam döngüsü (native/heap dışı):**
- `ScriptedCheckerService` temp dosyaları: `k6-script-*.js`, `k6-archive-*.tar`, `k6-ca-*.pem`,
  `k6-summary-*.json` — `deleteIfExists` finally'lerinin **istisna dahil tüm dallarda** çalıştığını
  kanıtla (bilinen `checksFailed` unboxing-NPE yolu dahil — o NPE `err()`'e düşerken temp'ler
  siliniyor mu?). Soak sonrası `%TEMP%` içinde `k6-*` kalıntısı sıfır olmalı; ayrıca zombi `k6.exe`
  süreci kalmadığını `Get-Process k6` ile kanıtla.
- PDF üretimi (`InventoryPdfWriter`, `WeeklyOutagePdfWriter`, `PdfCanvas` — pdfbox): her `PDDocument`
  try-with-resources ile mi; font/stream cache'leri statik mi?
- `HttpClient`/soket kullanan servisler (`GeoIpService`, `RdapDomainClient`, `TrWebWhoisClient`,
  checker'lar): client'lar yeniden mi kullanılıyor (istek başına `HttpClient.newBuilder` çağrısı
  hem yavaş hem native soket/selector biriktirir)?
- `CorrelationIdFilter` → MDC finally'de temizleniyor mu (thread-pool'da MDC kalıntısı = sızıntı)?

**1d. JPA / Hibernate / SQL katmanı:**
- **Query plan cache** klasiği: dinamik boyutlu `IN (...)` listeleri (`AlertEventRepository` ~22 KB
  sorgu, series/rollup sorguları) her farklı liste boyu için plan üretir.
  `hibernate.query.in_clause_parameter_padding=true` ve `hibernate.query.plan_cache_max_size`
  ayarlı mı? Değilse ekle (bu tek başına bilinen bir OOM kaynağıdır).
- Sınırsız `findAll`/tam-tablo çekişleri: büyük seri tabloları (`uptime_checks`, `http_checks`,
  `port_checks`, `dns_checks`, `domain_checks`, `keyword_results`, `audit_logs`,
  `notification_logs`) üzerinde LIMIT'siz sorgu var mı — özellikle `MonitoringController` (~254 KB)
  ve `WeeklyReport*`/`DbAnalyticsService` yollarında? Tüm series endpoint'leri zaman filtreli mi?
- `RetentionService` purge döngüsü (`purge-batch-size` 10000): batch'ler arasında persistence
  context temizleniyor mu (`em.clear()` / native delete), yoksa milyonlarca entity managed mı kalıyor?
- Hikari zaten `leak-detection-threshold=60000` — soak sırasında logda "connection leak" uyarısı
  taraması yap; çıkarsa dosya:satır'a kadar indir.
- `open-in-view=false` (OK) — ama `@Transactional` dışı lazy erişim istisnaları maskelemek için
  koleksiyonların önden greedy yüklendiği yer var mı (kartezyen fetch join şişmesi)?

**1e. Loglama ve string birikimi:**
- Logback `AsyncAppender` kuyrukları 512/256, `discardingThreshold=0` + bloklamalı — OK ama
  `neverBlock` durumunu ve pattern'lerde `%caller`/`%ex{full}` maliyetini kontrol et.
- `RequestLoggingFilter` TRACE kapılı + 64 KB body limiti (SINIRLI-OK — pinle).
- `EmailNotificationService`/`EmailTemplateBuilder` (~254+70 KB) HTML kurulumunda dev
  StringBuilder'lar tekrar kullanılabilir mi (tek seferlik allocation kabul; statik tampon İSTEME).

## Faz 2 — Frontend statik bellek denetimi

- **Interval/listener hijyeni (kural):** `useEffect` içindeki her `setInterval`/`setTimeout`/
  `addEventListener`/`ResizeObserver`/`IntersectionObserver` cleanup dönmek ZORUNDA. Tüm
  `src/` ağacını grep'le; `useVisibleInterval` hook'u standarttır (sekme gizliyken durur) — ham
  `setInterval` ile polling yapan sayfaları hook'a taşı. `App.jsx`'teki inaktivite zamanlayıcıları
  ve `popstate`/`visibilitychange` listener'ları temiz görünüyor — testle pinle.
- **Poll eden 12+ sayfa** (Uptime/Port/Dns/Http/Ping/Keyword/Page/Domain/Scripted monitör sayfaları,
  SystemHealth, AlertHistory, Dashboard): unmount sonrası poll'un gerçekten durduğunu ve **geç dönen
  fetch'in unmount edilmiş bileşende setState çağırmadığını** (cancelled bayrağı / AbortController)
  denetle. `api/client.js` timeout'u AbortController'lı (OK); indirme yolunda `revokeObjectURL`
  var (OK) — bunları test altına al.
- **Grafik verisi büyümesi:** series state'leri sabit pencere mi (son N nokta), yoksa her poll'da
  `[...prev, ...new]` ile mi büyüyor? `ResponseTimeChart` savunmacı filtresine dokunma.
- **Büyük tekil modüller:** i18n 505 KB tek chunk'ta mı — dil dosyalarını ayırmak/dynamic import
  etmek build ve tarayyıcı belleğini düşürür mü, ölç. `App.css` 474 KB — kullanılmayan seçici
  taraması (css-hygiene testi mevcut, kapsamını genişlet).
- **Modal/portal temizliği:** `ModalShell`, `Dialog`, `Toast` — body scroll-lock/focus-trap
  kalıntısı, kapatılan modal'ın interval'i.
- **`sessionStorage`/`localStorage`** büyüyen anahtar var mı (`migrateStorageKeys` mevcut).
- Vitest'te **happy-dom/jsdom leak sinyali**: `npm run test` çıktısında "worker terminated" /
  heap uyarıları var mı; test setup'ında global fetch mock sızıntısı olmadığını doğrula.

## Faz 3 — Leak-pinleyen testlerin yazılması ve koşulması

Var olan suite'e (backend ~1426, frontend ~282 test) şu sınıfta testler ekle — her biri önce
kırmızı olduğu (veya assert tersine çevrilince fail ettiği) gösterilerek:

**Backend (JUnit + AssertJ, MockitoExtension; paket aynası `src/test/java`):**
- `MonitoringOutageService`: (senaryo) alarm aç → çöz → **tüm 4 haritanın key'i silinmiş**;
  (senaryo) confirm zinciri istisna fırlatır → key yine silinmiş; (senaryo) monitör silme →
  kalıntı yok. Haritaları paket-görünür yapmak yerine boyutları yansıtan bir test-hook tercih et.
- `HttpMetricsService`: 3000 sahte dakika bas → `history.size() ≤ 1440`; 10.000 farklı URI bas →
  endpoint anahtar sayısı tavanı (Faz 1 kararına göre) aşılmıyor.
- `GeoIpService`: 10.001 IP → cache boyutu tavanı; TTL dolunca taze fetch.
- `ScriptedCheckerService`: başarı + timeout + istisna (NPE yolu dahil) koşularının HER birinde
  temp dosyaların silindiği (`%TEMP%` diff'i) ve süreç kalmadığı; `outputTail` üst sınırları.
- `ProcessProbe`: 50 ardışık koşu sonrası `Thread.activeCount()` artışı ≈ 0; timeout'ta
  `destroyForcibly` sonrası stream'lerin kapandığı.
- `EmailNotificationService`: 421-retry kuyruğu tavan testi (tavan Faz 5'te eklendikten sonra);
  retry tükenince closure'ların bırakıldığı (WeakReference ile gözlemlenebilir).
- Hibernate plan-cache: aynı sorguyu 1..N farklı IN-boyutuyla çalıştırıp (H2) plan cache
  boyutunun sınırlı kaldığını gösteren entegrasyon testi (padding açıldıktan sonra).
- Retention purge: 50k satırlık H2 tablosunda purge sonrası persistence context boyutu
  (`EntityManager` istatistiği) sabit.
- Sınıf bazlı büyüme dedektörü (genel kalıp): işlemi M kez çalıştır → `System.gc()` +
  `GC.class_histogram`/`MemoryMXBean` ile ilgili sınıfın canlı örnek sayısının M ile ölçeklenMEdiğini
  assert et. (Kesin GC garantisi yoktur — eşikleri toleranslı koy, flaky yapma.)

**Frontend (Vitest):**
- Her poll'lu sayfa için: render → unmount → `vi.getTimerCount()` 0 (fake timers) ve mock API
  çağrı sayısının unmount sonrası artmadığı.
- `useVisibleInterval`: hidden→durur, visible→tek tetik + devam, unmount→temiz (mevcut testi genişlet).
- Listener dengesi: `addEventListener`/`removeEventListener` spy'ları ile mount/unmount simetrisi
  (App inaktivite zamanlayıcıları dahil).
- Geç dönen fetch: unmount sonrası resolve olan promise "setState on unmounted" konsol hatası
  üretmiyor (konsola sıfır tolerans zaten kural).

Koşum: `mvn.cmd -f backend/pom.xml -B clean verify` + `cd frontend && npm run test:coverage`.
Sayıları raporla; floor'ları yukarı ratchet'le.

## Faz 4 — Soak / dayanıklılık ölçümü (çalışan uygulamada kanıt)

PostgreSQL ayakta olmalı. Faz 0b'deki bayraklarla + `-Xmx256m` gibi KASITLI DAR heap'le başlat
(sızıntıyı hızlı görünür kılar):
1. `jcmd <pid> VM.native_memory baseline` al.
2. Yük üret (en az 45–60 dk): `k6 run -e BASE_URL=http://localhost:8080 ./perf/k6-smoke.js`'i
   döngüde koştur; ayrıca login → dashboard → seri endpoint'leri → SystemHealth → birkaç manuel
   kontrol (scripted dahil, k6 kuruluysa) gezen bir senaryo ekle ki gerçek kod yolları ısınsın.
3. Her 5 dk örnekle ve CSV'ye yaz: `GC.heap_info` (old-gen doluluk **full-GC sonrası**), 
   `VM.native_memory summary.diff`, thread sayısı, `VM.classloader_stats`, RSS (WorkingSet64),
   `GC.class_histogram` ilk 40 (dosyaya).
4. Bitişte: `jcmd <pid> GC.heap_dump backend\soak-end.hprof` + histogram diff'i (başlangıç vs son).
5. **Hüküm kriterleri:** full-GC-sonrası old-gen eğilimi DÜZ mü; NMT kategorileri
   (Internal/Thread/Class/Compiler/Other) düz mü; thread & classloader sayısı düz mü; `%TEMP%`'te
   `k6-*` birikimi ve zombi süreç yok mu; Hikari leak uyarısı yok mu; `backend\app-err.log` temiz mi.
   Büyüyen ne varsa histogram diff'inden sınıf adına, oradan dosya:satır'a in.
6. Frontend soak: uygulamayı tarayıcıda 30 dk açık bırakan senaryoyu Chrome DevTools
   Performance.memory / heap snapshot diff'i ile (mümkünse) veya en azından sekme Görev Yöneticisi
   ölçümüyle değerlendir; poll eden bir sekmede bırak, JS heap eğilimini raporla.

## Faz 5 — Düzeltmeler

- Her SIZDIRIR/RİSK bulgusu için: kök neden → en dar kapsamlı düzeltme → pinleyen test → önce/sonra
  ölçüm. Tavanlar `private static final` sabit + gerekçe yorumu olarak yazılır
  (örn. `GeoIpService.MAX_CACHE_ENTRIES` deseni); sihirli sayı bırakma, gerekiyorsa
  `application.properties` + `AppSettingsCatalog` üzerinden ayarlanabilir yap (env şablonlarını
  — `.env.example`, `k8s/secret.example.yaml`, helm values — aynı değişiklikte güncelle).
- Davranış değiştiren düzeltmeleri (ör. retry kuyruğu tavanı doldu → mail düşürülür mü, DB'ye mi
  yazılır?) kendi başına kararlaştırma — seçenekleri final raporda kullanıcı onayına sun; güvenli
  olanı (kaydet + logla + `notification_logs`'a FAILED yaz) varsayılan öner.
- `applySchemaPatches()` gerektiren şema eklemesi olursa patch satırını unutma (CLAUDE.md kuralı).

## Faz 6 — Düşük-bellek profili (hedef: ölçülmüş en küçük ayak izi)

1. Faz 4 ölçümlerinden gerçek çalışma kümesini çıkar: canlı heap (full-GC sonrası) + metaspace +
   thread stack'leri (sayı × `-Xss`) + code cache + NMT "diğer".
2. Kademeli daralt ve HER kademede k6 SLA'sını koş: `-Xmx` (örn. 512→384→256m),
   `-XX:MaxMetaspaceSize`, `-XX:ReservedCodeCacheSize`, `-Xss512k`, `-XX:MaxDirectMemorySize`.
   G1'de `-XX:G1PeriodicGCInterval` + `-XX:SoftMaxHeapSize` ile boşta RSS iadesi dene; çok dar
   heap'te (≤256m) SerialGC'yi de ölçüp karşılaştır. Sonuç: **iki adlandırılmış profil** öner —
   `JAVA_OPTS_DUSUK` (minimum ayak izi, SLA'yı geçen en dar ayar) ve `JAVA_OPTS_DENGELI`.
3. Havuzları da bütçeye dahil et (her biri ölçümle, k6 kapısıyla): Tomcat `TOMCAT_MAX_THREADS`
   (100 → ölçülen eşzamanlılığa göre), Hikari `DB_POOL_MAX/MIN` (25/10 → düşür), 
   `SCHEDULING_POOL_SIZE` (8), `EXECUTOR_CORE/MAX/QUEUE` (20/50/5000 — kuyruk 5000 nesne tutar).
   Envanter büyüklüğüne bağlı öneriyi formülle yaz ("N monitör başına ...").
4. Önerileri uygulama noktaları: `start-local.ps1`/`.env.example` (yerel), Dockerfile
   `JAVA_OPTS` yorumu, `helm/site-monitor/environments/*.yaml` + `k8s/` resources
   requests/limits (HPA mem %80 eşiğiyle tutarlı olacak şekilde). Prod values değişikliği ÖNERİ
   olarak rapora; yerel dosyalara uygulanabilir.
5. Test JVM'lerine de bütçe koy: Surefire `argLine`'a `-Xmx` (+ JaCoCo agent'la birleşim) —
   `mvn verify` sırasındaki `Chunk::new` tarzı makine-RAM çökmelerine karşı.
6. Kalıcı gözlemlenebilirlik: prod'a `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...` ve
   `-Xlog:gc*` önerisi; `/actuator/prometheus`'taki `jvm_memory_*` metriklerinin SystemHealth
   sekmesinde bir "Bellek" kartına bağlanması için somut öneri (uygulama, kullanıcı isterse).

## Faz 7 — Performans regresyon kapısı

Tüm düzeltme ve profil ayarlarından SONRA, tek oturumda: `mvn -B clean verify` (tam suite yeşil),
`npm run test:coverage` + `npm run build`, `start-local.ps1` ile DÜŞÜK profil ayarlarıyla açılış,
`pwsh ./scripts/smoke.ps1`, k6 smoke SLA. p95/hata oranını Faz 0 taban çizgisiyle karşılaştır;
başlangıç süresi (health UP'a kadar) ve açılış RSS'ini de tabloya koy.

## Faz 8 — Final rapor

Türkçe, tek yapılandırılmış rapor (sohbete; istenirse `docs/BELLEK_DENETIM_RAPORU.md`):
1. **Yönetici özeti:** leak var mıydı? Kaç bulgu: SIZDIRIR/RİSK/SINIRLI-OK dağılımı.
2. **hs_err hükmü:** üç çökme imzasının açıklaması — leak mi overcommit mi, kanıtıyla.
3. **Bulgu tablosu:** dosya:satır × hüküm × kanıt (ölçüm) × düzeltme × pinleyen test.
4. **Soak grafiği/tabloları:** old-gen, NMT, thread, RSS eğilimleri (önce/sonra).
5. **Bellek profilleri:** JAVA_OPTS_DUSUK / JAVA_OPTS_DENGELI + havuz değerleri + hangi dosyalara
   yazıldığı/önerildiği; ulaşılan minimum RSS ve geçen k6 SLA sayıları.
6. **Test envanteri:** eklenen leak-testleri (dosya dosya, tek cümle davranış), yeni coverage,
   ratchet'lenen floor'lar.
7. **Kullanıcı kararı bekleyenler** ve **gelecek koruması:** CLAUDE.md'ye eklenecek kurallar
   (örn. "her yeni in-memory Map tavan+TTL+test ile doğar", "yeni @Scheduled havuzu @PreDestroy'suz
   merge edilmez") — onaylanırsa ekle.

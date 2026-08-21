# Bellek Sızıntısı Denetimi ve Düşük-Bellek Seferberliği

**Tarih:** 2026-08-20 · **Kapsam:** backend (Java 25 / Spring Boot) + frontend (React/Vite) + dağıtım yapılandırması
**Durum:** Faz 0–5 tamamlandı · Faz 4 (soak) ve Faz 6 (profil merdiveni) ölçümleri sürüyor
**Not:** Faz 0–5 düzeltmeleri 2026-08-21'de commit edildi ve sürüme girdi. Faz 4/6 ölçümleri
sürdüğü için `.env.example`'daki `JAVA_OPTS` profil satırları hâlâ yorumlu bırakıldı.

---

## 1. Yönetici özeti

**Klasik bir bellek sızıntısı YOK.** Depoda duran 15 JVM ölümcül hata kaydının tamamı makinenin
fiziksel RAM'inin tükenmesinden; hiçbiri Java-heap OOM'u değil ve yalnızca **1 tanesi** uygulamanın
kendisine ait — o kayıtta bile heap sabit (committed 248 MB'ta duruyor, kullanım 111–225 MB arasında
salınıyor, 45 dakikalık çalışma boyunca büyüme yok).

Buna karşılık **bir gerçek hata** ve **sınırsız büyümeye açık beş yüzey** bulundu ve düzeltildi.
En önemlisi bir bellek sorunu olmaktan çok bir **alarm körlüğü**: `MonitoringOutageService`'te
immediate teyit yolunda bir istisna, o monitörün kesinti teyidini **uygulama yeniden başlatılana
kadar kalıcı olarak** devre dışı bırakıyordu — üstelik aynı istisna o sweep'in kalan domain'lerini
de işlenmeden düşürüyordu.

| Ölçüm | Değer |
|---|---|
| Canlı set (3× zorlanmış GC sonrası, boşta) | **104.7 MB** |
| Açılış RSS / thread / handle | 721 MB / 93 / 1072 |
| Varsayılan MaxHeap (16 GB makinede) | ~4 GB (ayarsız) |
| Düzeltme sayısı | 9 (1 hata, 5 sınırlama, 3 hijyen) |
| Eklenen pinleyen test | backend +9 (2207→2216), frontend +1 (1004→1005) |

---

## 2. hs_err adli incelemesi — 15 çökme kaydı

Aşağıdaki tablonun her satırı dosyalardan **doğrudan** okundu (imza satırı, boş RAM, uptime, komut).

| İmza (kaynak) | Adet | Ne demek | Hangi süreçler |
|---|---|---|---|
| `arena.cpp:186` | **7** | JIT derleyicisinin native arena'sı malloc yapamadı (hepsinde `Current thread = C2 CompilerThread*`) | 3× Maven launcher, 3× Surefire fork, **1× uygulama jar'ı** |
| `os_windows.cpp:3554` | **7** | Açılışta G1 için 256 MB `mmap` rezerve edilemedi (uptime ~0.02 sn — kod hiç çalışmadan) | 6× `byte-buddy-agent` self-attach yardımcısı (Mockito inline mock), 1× Surefire fork |
| `allocation.cpp:44` | **1** | `AllocateHeap` malloc hatası | VS Code Java dil sunucusu (`redhat.java`, `-Xmx2G`) — **bu uygulamayla ilgisiz** |

Çökme anındaki **boş RAM 292 MB – 2768 MB** (makine toplam 16264 MB). Yani hepsi aynı tabloyu
anlatıyor: aynı anda çalışan çok sayıda JVM (Maven + Surefire fork + JaCoCo ajanı + byte-buddy
attach yardımcıları + VS Code dil sunucusu + uygulamanın kendisi) makineyi doldurmuş.

### Tek uygulama çökmesi (`backend/hs_err_pid23248.log`) — kritik kanıt

```
Native memory allocation (malloc) failed to allocate 32752 bytes. Error detail: Chunk::new
Out of Memory Error (arena.cpp:186)
Current thread: JavaThread "C2 CompilerThread0"
elapsed time: 2733.87 seconds (0d 0h 45m 33s)
Memory: system-wide physical 16264M (1513M free)
current process WorkingSet: 456M, peak: 620M
garbage-first heap  total reserved 4165632K, committed 253952K, used 113521K … 230323K
```

45 dakikalık çalışmada heap **committed 248 MB'ta sabit**, kullanım 111–225 MB arasında salınıyor.
Uygulamanın kendi RSS'i 456 MB iken makinede yalnız 1.5 GB boş kalmıştı. **Sızıntı değil; makine dolu.**

### Eylem karşılığı

Test JVM'i için `backend/pom.xml`'de **hiç surefire yapılandırması yoktu** → fork varsayılan
ergonomiyle (~4 GB MaxHeap) açılıyor ve JaCoCo ajanı bu kapaksız fork'un içinde koşuyordu.
15 kaydın 13'ü tam olarak bu şekilde üretilmiş. Bkz. F9.

---

## 3. Bulgular ve düzeltmeler

| # | Bulgu | Yer | Sınıf | Düzeltme | Pinleyen test |
|---|---|---|---|---|---|
| F1 | Immediate teyit yolunda `inFlight` anahtarı temizlenmiyor; `putIfAbsent` guard'ı yüzünden monitör **kalıcı sağır** kalıyor + istisna sweep'in kalanını düşürüyor | `MonitoringOutageService.java:489-499` (`withLock` L765-767 catch'siz) | 🔴 SIZDIRIR + alarm körlüğü | try/catch/**finally**; `runConfirmAttempt` ile simetrik: logla, yut, anahtarı bırak | `MonitoringOutageServiceTest` ×2 — fix öncesi kırmızı olduğu çıktıyla kanıtlandı |
| F2 | Eşleşmeyen istekte **ham URI** metrik anahtarı; dakika içi kova sayısı sınırsız | `HttpMetricsInterceptor.java:38` + `HttpMetricsService.record` | 🟡 Savunma (bkz. §4) | `(unmatched)` kovası + dakikada `MAX_ENDPOINTS_PER_MINUTE=500` tavanı, taşma `(overflow)`'a | `HttpMetricsInterceptorTest` ×2, `HttpMetricsServiceTest` ×1 |
| F3 | SMTP 421 retry kuyruğu sınırsız; her görev **tüm MimeMessage'ı** (HTML + logo + PDF eki) canlı tutuyor | `EmailNotificationService.java:63-68, 241-261` | 🟠 RİSK | `MAX_PENDING_RETRIES=50`; tavan dolunca düşür + `FAILED: retry kuyruğu dolu` + `log.error` | `EmailNotificationServiceTest` — **mutasyon kontrolü**: tavan devre dışı → kırmızı |
| F4 | 2 executor `@PreDestroy`'suz | `SchedulerService.java:3027`, `IncidentNotificationService.java:44` | 🟡 DÜŞÜK | Mevcut `EmailNotificationService` deseni birebir | `ExecutorShutdownTest` ×2 |
| F5 | Timeout'ta okuyucu thread `read()`'te asılı kalabiliyor (`cancel(true)` `CompletableFuture`'da thread'i **kesmez**) | `ProcessProbe.java:158-160` ve 2. overload | 🟡 DÜŞÜK | Stream'i kapat → `read()` IOException'la döner | — (kod incelemesi; test kırılgan olacağından yazılmadı) |
| F6 | Hibernate query-plan cache ayarsız; ~60 dinamik `IN (:liste)` sorgusu her farklı uzunluk için ayrı plan üretiyor | `application.properties` (eksik) | 🟠 RİSK | `in_clause_parameter_padding=true` + `plan_cache_max_size=1024` + `plan_parameter_metadata_max_size=64` | — (standart Hibernate özelliği; Faz 7'de `EXPLAIN ANALYZE` ile doğrulanacak) |
| F7 | `getOverview` zaman-pencereli ama **LIMIT'siz** (retention 365 gün → 30 günlük pencere tabloyu sınırlamıyor) | `DbAnalyticsService.java:74` | 🟠 RİSK | `Limit.of(50_000)` + en-yeni-önce + payload'da `truncated`/`row_limit` | `DbAnalyticsServiceTest` ×2 |
| F8 | 2 kopyalama zamanlayıcısı unmount'ta temizlenmiyor | `DomainDiagnostics.jsx:52`, `SqlRowDetailModal.jsx:20` | 🟢 Hijyen | `ui/CopyButton.jsx` deseni (ref + cleanup) | `CopyTimerCleanup.test.jsx` — **mutasyon kontrolü**: "expected 1 to be +0" |
| F9 | Test JVM'inde bellek tavanı yok, JaCoCo ajanı kapaksız fork'ta | `backend/pom.xml` (surefire bloğu yok) | 🟠 15 hs_err'in 13'ünün sebebi | `<argLine>@{argLine} -Xmx1g -Xss512k -XX:+HeapDumpOnOutOfMemoryError</argLine>` | — (yapılandırma) |

Ek olarak: `start-local.ps1` **JAVA_OPTS'u hiç geçirmiyordu** (Faz 0a). Bu düzeltilmeden hiçbir
bellek ölçümü yapılamazdı: `$env:JAVA_OPTS = '-Xmx512m …'; .\start-local.ps1` diyen her deneme
sessizce **bayraksız** bir JVM başlatıyor ve varsayılan yapılandırmayı ölçüyordu.

---

## 4. F2 hakkında dürüstlük notu — tehdit modeli daraldı

Planda F2 "bot sürücülü aktif risk" olarak sınıflanmıştı. **Ölçüm bunu doğrulamadı.**
Çalışan örneğe 9 rastgele yol gönderildi; `http_metric_minute` tablosunda distinct endpoint
**92'de sabit kaldı** (yazılan tek yeni satır giriş denemesiydi). Sebebi üç katmanlı:

1. `WebConfig.java:109` — interceptor **yalnız `/api/**`**'a kayıtlı; `/wp-admin/...` gibi taramalar hiç görülmüyor.
2. `AuthInterceptor` ondan **önce** kayıtlı; kimliksiz `/api/<rastgele>` 401'de kesiliyor ve metrik `afterCompletion`'ı hiç çalışmıyor.
3. Kimlikli ama eşleşmeyen `/api/...` istekleri Spring Boot'un varsayılan `/**` kaynak işleyicisine düşüp **sınırlı** şablon alıyor — tabloda `GET /**` satırının varlığı bunun kanıtı.

Yani ham-URI dalı bugün pratikte **ölü kod**. Düzeltme yine de uygulandı: iki satırlık maliyeti var
ve tek bir yapılandırma değişikliğiyle (interceptor'ı `/**`'a almak, statik kaynak eşlemesini
kapatmak) canlanır. Raporda **aktif sızıntı değil, savunma amaçlı sertleştirme** olarak sınıflandı.

Bir sözleşme değişikliği yapıldı: `HttpMetricsInterceptorTest`'te ham-URI davranışını **pinleyen
mevcut bir test vardı**, yani bu bilinçli bir tercihti. Test gerekçesiyle güncellendi; geri alınması
tek satırlık iştir.

---

## 5. Denetlendi ve TEMİZ (düzeltme gerekmedi)

Bu liste raporun yarısı kadar değerlidir: bir sonraki denetimin aynı yolları yeniden taramasını engeller.

| Alan | Neden temiz |
|---|---|
| `RetentionService` | Native bulk `DELETE … LIMIT` (JdbcTemplate); hiç entity yüklemiyor → persistence-context sorunu **yok**. Planın önerdiği `em.clear()` testi konusuz. |
| `GeoIpService` | `MAX_CACHE_ENTRIES=10_000` + tavanda `clear()` |
| `HttpMetricsService.history` | `MAX_BUCKETS=1440` ile sınırlı |
| `SchedulerService.lastMonitorCheckAt` | Her sweep'te `retainAll` ile budanıyor (L1826-1830) |
| Tüm `HttpClient` kullanımı | Yeniden kullanılıyor; tek istisna `TrWebWhoisClient:152` (cookie durumu için zorunlu, try-with-resources) |
| PDF yazıcıları | Hepsi `AutoCloseable` + try-with-resources; statik font/stream cache yok |
| MDC / ThreadLocal | Kod tabanında MDC **hiç kullanılmıyor**; `CorrelationIdFilter` request attribute kullanıyor → havuz thread'inde kalıntı riski yok |
| `CompletableFuture` | Çıplak `supplyAsync` yok; hepsi açık executor alıyor |
| `EscalationService`, `StormService`, `AuditService`, `FailedLoginAnomalyService` | Büyüyen koleksiyon alanı yok (durum DB'de) |
| Frontend zamanlayıcı/dinleyici hijyeni | ~50 çağrı yerinin tamamında cleanup var (F8'deki 2 istisna hariç); `ModalShell` scroll-lock sayaçlı, `Toast` zamanlayıcıları unmount'ta süpürülüyor |
| Frontend seri durumu | Grafik/seri state'i her yüklemede **replace** ediliyor, `[...prev, ...new]` birikimi yok |
| `localStorage` / `sessionStorage` | Sınırsız büyüyen anahtar yok (`ErrorBoundary` kapaklı, taslak anahtarları temizleniyor) |

**Bilinçli tasarım — dokunulmadı:** Caffeine `LONG_TTL` 300 sn / `maximumSize` 1000, günlük re-alert
dedupe, ağ-kesintisi bastırması, `buildResponseSeries` hunisi, k6'nın kısa ömürlü alt süreç olması,
`SystemHealth.jsx:339` hızlı-tarama polling'i, frontend'in `alive`/`seqRef` geç-yanıt koruması.

---

## 6. Soak ölçümü (Faz 4) — **sonuç: sızıntı YOK**

**Koşu:** 10:44–17:42, **6 sa 58 dk**, 409 örnek (`logs/mem-soak.csv`). 60 sn kadans, saatte bir
3× zorlanmış GC ile canlı set, 2 saatte bir sınıf histogramı. Yük sürücüsü kimlikli oturumla
30 gerçek uca vurdu.

**İki plandan sapma, ikisi de gerekçeli.** (1) İki geceye yayılan "önce/sonra" (A/B) soak tek
koşuya indirildi: A/B'nin amacı F2'nin büyümesini kanıtlamaktı, §4'te o vektörün erişilemez olduğu
ölçümle gösterilince karşılaştırılacak bir büyüme kalmadı. (2) Koşu 8 saat yerine 6 sa 58 dk'da
kullanıcı onayıyla kesildi: hüküm ölçütlerinin tamamı çoktan dolmuştu ve canlı set son saatte
+0.1 MB'de düzleşmişti; kalan süre aynı satırı tekrar yazacaktı.

### Ölçülen değerler

| Gösterge | Başlangıç | Bitiş | Hüküm |
|---|---|---|---|
| Canlı set (3× GC sonrası) | 118.3 MB | **110.5 MB** | ✅ aşağıda açıklandı |
| RSS | 719.5 MB | **644 MB** | ✅ düşüyor |
| OS thread | 92 | **92** | ✅ tam sabit |
| Handle | 1103 | 1127 (+24) | ✅ |
| Yüklü sınıf | 25.216 | 25.643 (+427) | ✅ platoda |
| NMT toplam committed | — | **−102 MB** | ✅ |
| NMT `Thread` | — | **+0.3 MB** | ✅ (eşik 50 MB) |
| NMT `Class` | — | +1.6 MB | ✅ |
| NMT `Code` | — | +8.4 MB | ✅ JIT ısınması |
| `http_metric` distinct endpoint | 92 | **92** | ✅ F2 tavanı çalışıyor |
| `%TEMP%` yeni `k6-*` | — | **0** | ✅ |
| Zombi `k6.exe` | — | 0 | ✅ |

### Canlı set eğimi — yuvarlanmadan

Saatlik ölçümler: **118.3 → 119 → 104.8 → 106 → 107.2 → 109.6 → 109.7 → 110.5 MB**

İlk iki değer ısınma (JIT + sınıf yükleme sürüyordu). Kararlı pencerede (t=2sa→7sa)
104.8 → 110.5, yani **~1.14 MB/saat**. Bu, plandaki "< 1 MB/sa" eşiğinin *tam üstünde* —
"düz" diye raporlamak yanlış olurdu. Sebebi sınıf histogramı farkıyla (t=0 → t=6sa) **isimlendirildi**:

| Sınıf | Artış | Yeni örnek |
|---|---|---|
| `[B` (byte dizisi) | +2792 KB | +27.164 |
| `ConcurrentHashMap$Node` | +1446 KB | +46.286 |
| **`catalina.webresources.CachedResource`** | **+1076 KB** | **+11.480** |
| `java.lang.String` | +676 KB | +28.830 |
| **`java.io.File`** | +359 KB | **+11.499** |
| `spring.core.MethodParameter` | +297 KB | +4.747 |

`CachedResource` ve `java.io.File` neredeyse birebir aynı adette (11.480 / 11.499): bu **Tomcat'in
statik kaynak önbelleği**. Kaynağı da bizzat yük sürücüsü — her 5 istekte bir **benzersiz GUID'li**
`/api/<guid>` yolu gönderiyordu; bu yollar hiçbir controller'a eşleşmeyip statik kaynak işleyicisine
düşüyor ve Tomcat her benzersiz yol için bir önbellek girdisi tutuyor.

**Bu bir sızıntı değil:** yapılandırmada Tomcat kaynak önbelleği için override YOK, yani varsayılan
`cacheMaxSize` **10 MB** tavanı ve LRU tahliyesi geçerli. Büyüme sınırlı bir tavana doğrudur ve
**gerçek trafikte oluşmaz** — normal kullanıcılar benzersiz yol uydurmaz. Kalan artış
(`MethodParameter`, `[B`, `String`) Spring'in yansıma önbelleklerinin olağan ısınmasıdır.

**Yan bulgu (bilgi):** yol tarayan bir bot da aynı önbelleği şişirebilir — ama 10 MB'ta tavanlanır,
yani sızıntı değil kapasite gürültüsüdür. Sınırlamak istenirse `server.tomcat.resource.*` ayarları
mevcuttur; bu turda değişiklik önerilmiyor.

### Hüküm
**Uygulamada bellek sızıntısı yok.** 7 saatlik yükte thread sayısı tam sabit, native bellek net
olarak azaldı, heap büyümesi tek bir *sınırlı* ve *sürücü kaynaklı* önbellekle açıklandı.

---

## 7. Düşük-bellek profili (Faz 6) — **kısmi: 2 basamak ölçüldü**

Merdiven (`logs/mem-ladder.ps1`) kullanıcı kararıyla 2 basamak sonra durduruldu; geliştirmeye
dönüldü. Ölçülenler karar verdirmeye yetiyor, ölçülmeyenler aşağıda açıkça işaretli.

| Basamak | Açılış | RSS (bitiş) | Canlı set | Heap committed | GC yükü | k6 p95 | checks | Hüküm |
|---|---|---|---|---|---|---|---|---|
| varsayılan (MaxHeap ~4 GB) | 24.3 sn | 815 MB | 111 MB | 360 MB | %0.01 | 23.6 ms | 1.0 | GEÇTİ |
| **`-Xms192m -Xmx512m`** | 21.1 sn | **631.8 MB** | 111 MB | 360 MB | %0.01 | **20.7 ms** | 1.0 | **GEÇTİ** |
| `-Xmx384m` / `256m` / `192m` / knob'lar | — | — | — | — | — | — | — | **ÖLÇÜLMEDİ** |

**Bulgu:** `-Xmx512m` RSS'i **183 MB (%22)** düşürüyor ve **performans bedeli yok** — p95 23.6 → 20.7 ms
(iyileşti), GC yükü %0.01'de sabit, açılış süresi 3 sn kısaldı. Canlı set iki koşumda da 111 MB,
yani soak ölçümüyle (110.5 MB) birebir tutarlı.

### Önerilen profil (ölçüme dayalı)
```
JAVA_OPTS_DENGELI = -Xms192m -Xmx512m -Xss512k -XX:+ExitOnOutOfMemoryError
                    -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/
```
`-Xmx512m` ölçülmüş ve kapıyı geçmiştir. `-Xss512k` helm'de zaten kullanılıyor (`values.yaml:96`),
yerelde de eşitlenmesi önerilir.

### ÖLÇÜLMEYEN — dürüstlük notu
**`JAVA_OPTS_DUSUK` bu turda BELİRLENEMEDİ.** Canlı set 111 MB olduğu için 256m (×2.3) ve hatta
192m (×1.75) teorik olarak mümkün görünüyor, ama **ölçülmeden profil önerilmez**: bu denetimin
kuralı "önce ölç, sonra dokun". Kırılma noktası (GC yükü >%5 veya k6 SLA düşüşü) belirlenmedi.
İstenirse merdiven kaldığı yerden 3 basamakla (~40 dk) tamamlanır.

**Ayrıca ölçülmedi:** `G1PeriodicGCInterval` (boşta RSS iadesi — bu uygulama zamanının çoğunu boşta
geçirdiği için en yüksek getirili knob olması beklenir), `MaxMetaspaceSize`, `ReservedCodeCacheSize`,
SerialGC karşılaştırması ve 6c'deki uygulama knob'ları (Tomcat/Hikari/executor havuzları).

---

## 8. Dağıtım tutarsızlıkları (rapor + karar bekliyor)

| Konu | Durum |
|---|---|
| `k8s/deployment.yaml` bellek limiti **8 Gi** ↔ `helm/.../master.yaml` **1536 Mi** | **5× tutarsız**, ikisi de repoda ve bağımsız bakılıyor. **Hangisi otoritatif?** |
| `TOMCAT_MAX_THREADS` Helm chart'ında hiç yok | Her zaman `application.properties` varsayılanı olan 100'e düşüyor |
| `Dockerfile` `$JAVA_OPTS`'u genişletiyor ama varsayılan tanımlamıyor | Env verilmezse hiç `-Xmx` yok, JVM ergonomisine kalıyor |
| `.env.example`'da bellek/eşzamanlılık knob'larının **hiçbiri** yoktu | Eklendi (yorumlu, davranış değişikliği yok) |

---

## 8b. `perf/k6-smoke.js` kendi eşiğini sağlayamıyor (yeni bulgu, 2026-08-20)

Merdiven kapısını kurarken ortaya çıktı. Script iki uç yokluyor: `/health` ve
`/api/system/network-status`. İkincisini **bilerek kimliksiz** çağırıyor ve sağlıklı cevap **401**
(script'in kendi yorumu bunu böyle yazıyor). Ama k6, varsayılan olarak 4xx'i "başarısız istek"
sayar — dolayısıyla `http_req_failed` bu script'te **yapısal olarak 0.5**'tir.

Ölçüm (varsayılan yapılandırma, sağlıklı uygulama):
```
checks.value          : 1        (11.612 / 11.612 geçti, 0 hata)
http_req_duration p95 : 23.6 ms  (SLA 500 ms)
http_req_failed.value : 0.5      ← eşik: rate<0.01
```

Yani script'in kendi `http_req_failed: rate<0.01` eşiği **hiçbir koşulda** sağlanamaz; k6 her
koşumda 99 ile çıkar. Belgelenmiş perf kapısını çalıştıran herkes, hiçbir şey ifade etmeyen bir
kırmızı alır — ve bir süre sonra kapıya bakmayı bırakır.

**Öneri (uygulanmadı, karar kullanıcının):** ya 401'i beklenen yanıt olarak işaretlemek
(`http.setResponseCallback(http.expectedStatuses(200, 401))`), ya da o eşiği kaldırıp doğrulamayı
zaten doğru davranışı ölçen `checks: ['rate>0.99']` eşiğine bırakmak. Perf kapısının davranışını
değiştirdiği için bu turda dokunulmadı.

> Aynı hatayı bu denetimin merdiveninde ben de yaptım: kapıyı `http_req_failed`'e bağlamıştım ve
> uygulama kusursuz çalışırken ilk basamağa `RED_hata` yazdı. Kapı `checks`'e çevrildi
> (`logs/mem-ladder.ps1`), ham ölçümler etkilenmedi.

## 9. Denetim dışı gözlem (bellek konusu değil)

`start-local.ps1` DB ve SMTP parolalarını `-D` argümanı olarak geçiyor. Bu değerler
`jcmd VM.command_line`, Görev Yöneticisi ve `Get-CimInstance Win32_Process` ile **aynı makinedeki
her kullanıcı tarafından** okunabiliyor. Yerel geliştirme betiği olduğu için düzeltilmedi;
istenirse `.env`'i environment değişkeni olarak geçirmek (komut satırına düşürmemek) mümkün.

---

## 10. Gelecek koruması (öneri — onay bekliyor)

CLAUDE.md'ye eklenmesi önerilen kurallar:

1. **Her yeni in-memory `Map`/`Set`/kuyruk tavan + (gerekiyorsa) TTL ile doğar ve testle pinlenir.**
   Gerekçe: bu denetimde bulunan üç sınırlama (F2, F3, F7) da "pratikte büyümez" varsayımıyla yazılmıştı.
2. **Yeni executor `@PreDestroy`'suz merge edilmez** (F4).
3. **Efektten `fire-and-forget` çağrılan her async yükleyici try/catch'e sarılır** — kardeşlerinin
   sarılmış olması yenisini korumaz (bkz. F1 ve daha önce `IncidentHistoryPage`).
4. **`withLock` gibi yardımcıların `finally`'si yalnız KENDİ kaynağını korur**; çağıranın durumu
   ayrı `finally` ister (F1'in kök sebebi tam olarak buydu).

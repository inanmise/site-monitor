# Core Web Vitals (LCP / INP / CLS) — Site Monitor Fizibilite Raporu

**Tarih:** 2026-09-13 · **Sürüm tabanı:** 20.62.1 · **Kapsam:** LCP, INP, CLS metriklerinin Site Monitor üzerinden izlenebilirliği

---

## 0. Hüküm

**KOŞULLU YAPILABİLİR.** Üç metriğin üçü aynı kefeye konamaz; Site Monitor'ün bugünkü mimarisi ikisini kısmen, birini hiç karşılayamaz.

| Metrik | Sentetik (Site Monitor pod'undan ölçüm) | Alan verisi (RUM — izlenen sayfaya JS) |
|---|---|---|
| **LCP** | ✅ Ölçülebilir — gerçek tarayıcı şartıyla | ✅ Tam |
| **CLS** | ⚠️ Kısmen — yalnız yükleme anındaki kayma | ✅ Tam |
| **INP** | ❌ **Anlamlı ölçülemez** | ✅ Tam |

Ve raporun en kritik cümlesi, sizin verdiğiniz eşiklerin içinde gizli:

> "Good threshold: Under 2.5 seconds **(at the 75th percentile of user sessions)**"

Bu parantez teknik bir şart. `p75 of user sessions` — yani **gerçek kullanıcı oturumlarının** dağılımının 75. yüzdeliği. Site Monitor'ün bir sayfayı 30 dakikada bir kendi pod'undan ölçmesi "kullanıcı oturumu" üretmez; kendi koşumlarının dağılımını üretir. Bu iki sayı aynı ada sahip olsa da **aynı şey değildir** ve Google'ın Search Console raporuyla, arama sıralamasıyla veya CrUX ile karşılaştırılamaz.

Dolayısıyla soruyu ikiye ayırmak gerekiyor:

- **"Sayfalarımızın performansını regresyona karşı izleyebilir miyiz?"** → Evet, sentetik yolla, LCP + CLS üzerinden. Site Monitor buna çok yakın.
- **"Google'ın Core Web Vitals değerlendirmesini Site Monitor'de görebilir miyiz?"** → Hayır. O rapor alan verisidir; ancak RUM ile veya doğrudan Google'ın API'sinden okunarak elde edilir.

---

## 1. Core Web Vitals neden mevcut 10 türden farklı

Site Monitor'ün bugünkü on izleme türü (`MonitorTypeCatalog.ORDER`) tek bir ortak varsayım üzerine kurulu: **ölçen taraf sunucudur, ölçülen taraf ağ ve sunucudur.** Sertifika, DNS, port, HTTP yanıt süresi, sayfa ağırlığı — hepsi pod'dan bakılınca görülen gerçeklerdir.

Core Web Vitals bu varsayımı kırar. Üçü de **tarayıcı içinde, kullanıcının cihazında, sayfa oluşturulurken** tanımlanmıştır:

**LCP**, en büyük içerik öğesinin boyanma anıdır. "Boyama" bir render olayıdır — HTML'i indirmek onu üretmez. Sayfanın CSS'i, font'u, JS'i çalışıp layout kurulmadan LCP diye bir an yoktur.

**CLS**, düzen kaymalarının birikimli skorudur. Kayma, iki ardışık render kare arasındaki farktır. Tek kare yoksa fark da yoktur.

**INP**, sayfanın **ömrü boyunca** kullanıcı etkileşimlerine verdiği yanıt gecikmesidir. Etkileşim yoksa INP yoktur. Google Chrome'dan Barry Pollard'ın ifadesiyle: *"Lighthouse, like many web perf tools, typically just loads the page and does not interact with it. No interactions = No INP to measure!"* Google'ın kendi laboratuvar aracı Lighthouse bile INP'yi raporlamaz; yerine **TBT (Total Blocking Time)** adlı bir vekil metrik verir ve Pollard bunu *"it's a clue, but not a substitute for actually measuring INP"* diye niteler.

Bu üç madde, aşağıdaki bütün fizibilite kararlarının kaynağıdır.

---

## 2. Site Monitor'de bugün ne var, ne yok

### 2.1 İyi haber: "Sayfa Hızı" türü zaten mevcut ve olgun

Proje bu soruya yarı yolda cevap vermiş durumda. **10. izleme türü olan Sayfa Hızı (`pagespeed`) tam kurulmuş ve üretimde.**

| Katman | Dosya | Durum |
|---|---|---|
| Monitör entity | `model/PageSpeedMonitor.java` | Eşikler, UA, DNT, tracker hariç tutma, basic-auth, özel başlık (şifreli), teyit/recovery |
| Ölçüm satırı | `model/PageSpeedCheck.java` | `ttfb_ms`, `html_ms`, `response_ms`, `total_bytes`, `request_count`, `dns/connect/tls/server_ms`, `breached_metrics`, `breach_detail` |
| Kaynak kırılımı | `model/PageSpeedResource.java` | Son ölçüm + ihlal anları (bilinçli kısıtlı) |
| Motor | `service/PageSpeedCheckerService.java` | Jsoup envanteri + eşzamanlı alt kaynak çekimi, tavanlar, faz probu |
| Kurallar | `service/page/PageSpeedRules.java` | Clamp, eşik değerlendirme, ihlal birleştirme |
| Süpürme | `SchedulerService.runPageSpeedChecks()` (3613) | `pagespeed-sweep` kilidi, `checkDue`, 60 sn tick |
| Alarm | `EscalationService.TYPE_PAGESPEED_DOWN/SLOW` (178-179) | `isStandaloneMon` içinde (2349) → takım yönlendirmesi doğru |
| Teyit | `MonitoringOutageService.handleSweepResults` | N-teyit, tip izolasyonlu |
| Seri/grafik | `MonitoringController:3374` `/pagespeed/{id}/response-series?metric=` | `load / ttfb / size / requests` projeksiyonu, `buildResponseSeries` hunisi |
| Arayüz | `frontend/src/components/PageSpeedMonitorPage.jsx` | Kart, modal, grafik, ihlal delili, bütçe çizgisi |
| Ayar | `AppSettingsCatalog:121-133, 231-232` | Eşzamanlılık, UA, tavanlar, retention, varsayılan aralık |
| Rollup | `SchedulerService:1601` | `PAGESPEED` günlük uptime rollup'ı |

Yani **CWV için gereken iskeletin tamamı hazır.** Eksik olan tek şey ölçüm motorunun kendisi.

### 2.2 Kötü haber: motor bilinçli olarak tarayıcısız

Proje bu sınırı yalnız kabul etmemiş, **kod içinde belgelemiş**:

`model/PageSpeedMonitor.java` sınıf Javadoc'u:

> **Ölçümün dürüst sınırı:** ölçüm gerçek bir tarayıcıda değil, HTML + alt kaynakların sunucudan çekilmesiyle yapılır. JavaScript ÇALIŞMAZ; dolayısıyla **LCP/CLS gibi Core Web Vitals metrikleri iddia edilmez.** Ölçülen şey "sunucu ne kadar sürede veriyor ve sayfa ne kadar ağır".

`service/PageSpeedCheckerService.java` aynı cümleyi tekrarlar ve ekler:

> JS ile sonradan enjekte edilen kaynaklar sayıma girmez — arayüz bunu açıkça söyler.

Bu, bu işin en sağlam zeminidir: **proje zaten CWV'nin neden ölçülemediğini biliyor ve kullanıcıya söylüyor.** İş, bu beyanı değiştirmek için motoru değiştirmektir — beyanı gevşetmek değil.

### 2.3 Yan altyapı: RUM için hazır emsal

`controller/ClientErrorController.java` tam olarak bir RUM ucunun ihtiyaç duyacağı deseni taşıyor: kimliksiz POST ucu, `AuthInterceptor` beyaz listesi, IP başına sliding-window hız sınırı, alan uzunluk tavanları, `SecretMask.maskUrlQuery` ile gizlilik maskeleme, ayarla kapatılabilirlik (`site.monitor.client-errors.enabled`). Bir `/api/rum/vitals` ucu yazılacaksa şablonu burada.

### 2.4 Sentetik için hazır altyapı: k6

`ScriptedCheckerService` (10. tür "Sentetik İzleme") k6 binary'sini kısa ömürlü, sandboxlu alt süreç olarak çalıştırıyor: `--iterations 1`, `SsrfGuard.blacklistCidrs()` → `--blacklist-ip`, izole ortam değişkenleri, kurumsal CA paketi enjeksiyonu, efemer k6 REST API portu, proxy desteği, süreç bütçesi. **Gerçek tarayıcı çalıştıracak bir motor için gereken süreç yönetimi ve güvenlik disiplini zaten yazılmış.**

Ama k6 sürümü `Dockerfile`'da `ARG K6_VERSION=0.49.0` ile sabitlenmiş — bu yaklaşık iki yıllık bir sürüm ve aşağıda göreceğiniz gibi tarayıcı yolu için sorun çıkarıyor.

---

## 3. Dört toplama yolu

### YOL A — RUM (Real User Monitoring): izlenen sayfaya `web-vitals` JS'i koymak

**Nasıl çalışır.** İzlenen her sayfaya Google'ın `web-vitals` kütüphanesi (~2 KB) gömülür. Kütüphane tarayıcının `PerformanceObserver` API'lerinden LCP, INP, CLS değerlerini okur ve sayfa arka plana alınırken `navigator.sendBeacon` ile Site Monitor'e POST eder. Site Monitor bu değerleri biriktirir, 28 günlük kayan pencerede p75 hesaplar, eşiği aşan sayfa gruplarına alarm açar.

**Avantajları**

- **Üç metriği de, tanımlandığı gibi ölçen tek yol budur.** INP başka hiçbir şekilde elde edilemez.
- p75 istatistiği gerçek kullanıcı oturumları üzerinden hesaplanır — Google'ın eşikleriyle **anlamlı biçimde** karşılaştırılabilir.
- Gerçek cihaz, gerçek şebeke, gerçek coğrafya karışımı. Bir mobil kullanıcının 3G'de yaşadığı LCP, pod'un veri merkezi ağından gördüğü LCP'ye benzemez.
- Segmentasyon açılır: cihaz tipi, tarayıcı, bağlantı türü, sayfa grubu, şube/kanal.
- Site Monitor tarafında **tarayıcı çalıştırmak gerekmez** — pod hafif kalır, imaj büyümez, güvenlik yüzeyi açılmaz. Sadece bir ingest ucu + toplama işi.
- ClientErrorController emsali sayesinde ucun güvenlik deseni hazır.

**Dezavantajları**

- **Bu bir izleme aracı kararı değil, bir uygulama değişikliği kararıdır.** Kurumun internet şubesi, mobil web, kurumsal siteleri — her birine kod girmesi, ilgili uygulama geliştirme (UG) ekiplerinin sprint'ine girmesi, değişiklik yönetimi ve güvenlik onayından geçmesi gerekir. Site Monitor ekibinin tek başına yapabileceği bir iş değil.
- **KVKK / gizlilik yüzeyi.** Beacon'lar kullanıcı tarayıcısından gelir; URL'ler yol ve query parametresi taşır, IP kayıt altına girer. Bankacılık oturumunda bir URL'in query'si hesap numarası taşıyabilir. `SecretMask` deseni bunu maskeleyebilir ama **veri sınıflandırması ve hukuk onayı gerekir.** Bu maddeyi hafife almayın; işin en uzun süren kalemi bu olabilir.
- İç uygulamalar / VPN arkası sistemler için beacon'ın Site Monitor'e ulaşabilmesi gerekir — ağ yolu ve CORS açılması.
- Ölçüm **trafik olduğunda** gelir. Gece 03:00'te kimse girmemişse veri yok; "sayfa yavaşladı mı" sorusu trafiksiz saatlerde cevapsız kalır. Regresyon tespiti için sentetik kadar hızlı değildir — bir deploy'un LCP'yi bozduğunu ancak yeterli kullanıcı girdikten sonra öğrenirsiniz.
- Veri hacmi: sayfa görüntüleme başına bir satır. Yüksek trafikli bir internet şubesi günde milyonlarca beacon üretir. **Ham satır saklanamaz**; dakikalık/saatlik histogram kovalarına indirgemek şart (`HttpMetricMinute` deseni zaten projede var). `RetentionCatalog`'a yeni bir politika girmesi gerekir.
- Tek pod (`k8s/deployment.yaml` `replicas: 1`) yüksek hacimli beacon ingest'i için uygun değil — yatay ölçekleme veya ingest'i ayırma gerekebilir.

---

### YOL B — Sentetik gerçek tarayıcı: Site Monitor pod'undan headless Chromium

**Nasıl çalışır.** Sayfa Hızı motoruna ikinci bir "gerçek tarayıcı" modu eklenir. Her kontrolde headless Chromium açılır, sayfa yüklenir, `web-vitals` kütüphanesi sayfaya enjekte edilir (veya k6 browser'ın kendi ölçümü okunur), LCP ve CLS toplanır, tarayıcı kapatılır. İki teknik seçenek var:

- **B1 — k6 browser modülü.** Mevcut `ScriptedCheckerService` altyapısı kullanılır.
- **B2 — Ayrı bir Node + Playwright "CWV runner" servisi.** Spring uygulaması ona HTTP ile sorar.

**Avantajları**

- **İzlenen uygulamalara hiç dokunulmaz.** Ne kod değişikliği, ne UG ekibi koordinasyonu, ne KVKK tartışması. Bu, banka ortamında paha biçilmez bir avantaj — sremonitor.io'nun "no code installation required, just paste your URL" vaadi de tam olarak budur.
- **Sabit koşullar → temiz regresyon sinyali.** Aynı ağ, aynı cihaz profili, aynı saat aralığı. LCP 1.8 sn'den 3.4 sn'ye çıktıysa sebep kodunuzdur, kullanıcının telefonu değil. Bir deploy'un sayfayı bozduğunu **trafik beklemeden** öğrenirsiniz.
- **Kimlik arkası sayfalar ölçülebilir.** `PageSpeedMonitor` zaten basic-auth ve şifreli özel başlık taşıyor — CrUX'un asla göremeyeceği iç uygulamalar ve giriş arkası ekranlar bu yolla ölçülür. Banka için bu, alan verisinin yapısal olarak çözemediği bir boşluktur.
- Site Monitor'ün mevcut alarm/teyit/escalation/rapor zincirine **birebir** oturur; yeni bir tür bile açmaya gerek yok, `pagespeed` türü genişletilir.
- Mevcut `?metric=` projeksiyonu (`MonitoringController:3374-3403`) `lcp` / `cls` anahtarlarıyla doğrudan genişler — grafik altyapısı bedava gelir.

**Dezavantajları**

- **INP yine gelmez.** Bu yol LCP ve CLS'i çözer, INP'yi çözmez. Senaryolu etkileşim yazılırsa (tıkla, bekle) bir INP sayısı üretilebilir ama bu tek bir senaryonun sayısıdır, sayfanın gerçek INP'si değildir — ve raporlandığı anda yanıltıcı olur.
- **CLS eksik ölçülür.** Sentetik koşum sayfayı yükler ve kapatır. Kullanıcının aşağı kaydırırken tetiklediği lazy-load kaymaları, geç gelen reklam/banner kaymaları ölçüme girmez. Gerçek CLS bu sayıdan **yüksek** çıkar.
- **p75 iddiası kurulamaz.** Koşumların dağılımı kullanıcı oturumlarının dağılımı değildir.
- **Ortam kısıtları ciddi** — aşağıda §5'te ayrı ele alındı. Özetle: imaj +250-400 MB, `readOnlyRootFilesystem: true`, `capabilities: drop ALL`, `seccompProfile: RuntimeDefault`, tek pod, `/dev/shm` 64 MB.
- Kaynak maliyeti yüksek: her koşum ~300-700 MB RSS ve ciddi CPU. Mevcut pod limiti 8 Gi / 5 core ve bu limit **zaten** sertifika/DNS/port/uptime süpürmelerini ve 20/50/5000'lik `certCheckExecutor` havuzunu besliyor. 50 sayfayı 30 dakikada bir tarayıcıyla ölçmek ayrı bir kapasite planı ister.

**B1 (k6 browser) hakkında özel uyarı.** Cazip görünüyor çünkü k6 zaten imajda. Ama:

1. k6 browser **ayrı bir Chromium kurulumu ister** — Grafana dokümantasyonu açık: *"install a Chromium-based browser on your machine."* `grafana/k6:0.49.0` imajında tarayıcı yok; `Dockerfile`'ın `COPY --from=k6-bin /usr/bin/k6` satırı yalnız binary'yi taşıyor.
2. **Sürüm pin'i eski.** 0.49'da modül `k6/experimental/browser`; v0.52'de `k6/browser` olarak taşındı, k6 bugün v2.x'te. Yükseltmek gerekir.
3. **Yükseltme mevcut Sentetik İzleme'yi kırar.** Proje hâlâ `--summary-export` kullanıyor (`ScriptedCheckerService:864`, ayrıştırıcı `:1634`) ve bu bayrak sonraki k6 sürümlerinde kaldırıldı. k6'yı CWV için yükseltmek, çalışan 10. türün özet ayrıştırmasını **yeniden yazmak** demektir. (Not: geçmişteki `checksFailed` unboxing NPE'si düzeltilmiş — `decideStatus` artık `Integer` alıyor, `:1241`. Yani risk NPE değil, özet sözleşmesinin tamamen değişmesi.)
4. k6 browser'ın CWV'si **koşum özeti** olarak gelir (`browser_web_vital_lcp/cls/inp`), tekil sayfa ölçümü olarak değil — Site Monitor'ün satır-başına-bir-kontrol modeline uyarlanması gerekir.

Bu dört madde birlikte **B2'yi (ayrı runner servisi) B1'e tercih ettiriyor**: ayrı bir kap, ayrı bellek limiti, çöktüğünde ana uygulamayı etkilemez, k6'ya dokunmaz, Chromium'u ana imajın dışında tutar. Frontend'de `@playwright/test` zaten devDependency olarak mevcut (`frontend/package.json`), yani ekip Playwright'a yabancı değil.

---

### YOL C — Google PSI / CrUX API / Search Console

**Nasıl çalışır.** Site Monitor, Google'ın PageSpeed Insights API'sini veya CrUX API'sini çağırır; Google'ın halihazırda topladığı alan verisini okur ve gösterir. Sizin paylaştığınız Search Console Core Web Vitals raporu da tam olarak bu veriye dayanıyor.

**Avantajları**

- **Ölçüm yükü sıfır.** Ne tarayıcı, ne beacon, ne JS enjeksiyonu.
- Google'ın arama sıralamasında kullandığı **aynı sayı** — "SEO açısından nerdeyiz" sorusunun tek doğru cevabı budur.
- Üç metrik de gelir, p75 hesaplanmış hâlde, 28 günlük kayan pencerede. Search Console dokümanının verdiği eşik tablosu (LCP ≤2.5s / ≤4s, INP ≤200ms / ≤500ms, CLS ≤0.1 / ≤0.25) doğrudan uygulanabilir.

**Dezavantajları**

- **Kapsam, banka için büyük ölçüde boş.** Search Console raporu için iki şart var: URL **indekslenmiş** olacak ve **yeterli CrUX verisi** bulunacak. Bu iki şart birleşince:
  - İnternet şubesi (giriş arkası) → CrUX'ta **yok**.
  - İç uygulamalar, UAT/pre-prod, dev/sandbox (`tier` 2-3-4) → **yok**.
  - Yalnız düşük trafikli kurumsal sayfalar → "yeterli veri yok", origin grubuna düşer.
  Envanterinizin büyük kısmı bu yolla **hiç** ölçülemez.
- **Veri gecikmeli.** 28 günlük kayan pencere. Bugün öğlen yapılan bir deploy'un LCP'yi bozduğunu haftalar sonra öğrenirsiniz. Site Monitor'ün varlık sebebi olan "erken uyarı" burada yok.
- **Dış bağımlılık ve kurumsal çıkış.** Google API'sine ulaşmak `HTTP_PROXY_HOST/PORT` üzerinden olur, API anahtarı gerekir, kota vardır. Bir bankanın izleme sisteminin Google'a bağımlı olması ayrıca güvenlik/uyum konusudur. Search Console yolu ayrıca alan adı doğrulaması ve OAuth ister.
- Alarm kurulamaz — 28 günlük pencerede eşik aşımı "alarm" değil, "rapor"dur.

**Bu yolun doğru yeri:** Site Monitor'ün birincil CWV kaynağı olamaz; ama **halka açık kurumsal sayfalar için** ayda bir okunan bir "Google bizi nasıl görüyor" paneli olarak değerlidir ve sentetik ölçümle karşılaştırıldığında "lab ile field ne kadar ayrışıyor" sorusunu cevaplar.

---

### YOL D — Vekil metrikler: hiçbir şey yapmadan bugünkü veriden çıkarım

**Nasıl çalışır.** `pagespeed_checks` tablosunda zaten olan `ttfb_ms`, `server_ms`, `total_bytes`, `request_count` değerlerinden LCP riskine dair bir gösterge üretilir. Lighthouse'un INP yerine TBT kullanması da aynı mantıktır.

**Avantajları**

- **Sıfır altyapı maliyeti.** Veri bugün toplanıyor, yalnız yorumlanacak.
- TTFB, LCP'nin alt sınırıdır — TTFB 2 sn ise LCP'nin 2.5 sn altında olması matematiksel olarak imkânsızdır. Bu **kesin** bir ifadedir ve değerlidir.
- Toplam bayt + istek sayısı, LCP bozulmasının en sık iki sebebidir; `max_page_kb` / `max_requests` eşikleri zaten var.

**Dezavantajları**

- **Bu bir CWV ölçümü değildir ve öyle etiketlenirse yalan olur.** TTFB iyi olup LCP korkunç olabilir (render-blocking JS, geç yüklenen hero görseli, font swap).
- CLS ve INP hakkında hiçbir şey söylemez.
- Projenin `PageSpeedMonitor` Javadoc'undaki "CWV iddia edilmez" kuralını çiğnemeden yapılabilecek tek sunum şekli: *"LCP riski"* etiketi, `LCP` etiketi değil.

---

## 4. Metrik × yol fizibilite matrisi

| | A: RUM | B: Sentetik tarayıcı | C: CrUX/PSI | D: Vekil |
|---|---|---|---|---|
| **LCP** | ✅ tam, p75 dahil | ✅ ölçülür (lab değeri) | ✅ ama yalnız halka açık sayfalar | ⚠️ yalnız alt sınır |
| **INP** | ✅ tek gerçek yol | ❌ | ✅ ama yalnız halka açık | ❌ |
| **CLS** | ✅ tam | ⚠️ yalnız yükleme anı | ✅ ama yalnız halka açık | ❌ |
| **Giriş arkası / iç sistemler** | ✅ | ✅ | ❌ | ✅ |
| **Erken uyarı hızı** | Orta (trafiğe bağlı) | ✅ Yüksek | ❌ 28 gün | ✅ Yüksek |
| **İzlenen uygulamaya dokunma** | ❌ Gerekir | ✅ Gerekmez | ✅ Gerekmez | ✅ Gerekmez |
| **KVKK yüzeyi** | Yüksek | Yok | Düşük | Yok |
| **Site Monitor altyapı maliyeti** | Orta (ingest + toplama) | Yüksek (Chromium) | Düşük | Sıfır |
| **Google ile karşılaştırılabilir** | ✅ | ❌ | ✅ | ❌ |

---

## 5. Ortam kısıtları — Yol B'nin gerçek maliyeti

Bu bölüm sentetik tarayıcı yolunu seçerseniz karşılaşacağınız somut engelleri sıralar. Hiçbiri aşılmaz değil ama hiçbiri de "bir kütüphane ekle geç" değil.

**Tek pod.** `k8s/deployment.yaml`: `replicas: 1`, yorumda *"Uretim TEK POD calisir (2026-08-19 teyidi); olcekleme DIKEY yapilir."* Tarayıcı koşumları bu pod'un CPU'sunu sertifika süpürmesiyle paylaşır. 8 Gi / 5 core limiti içinde eşzamanlı 2-3 Chromium'dan fazlası riskli.

**`readOnlyRootFilesystem: true`.** Chromium yazılabilir profil dizini ister. Tek yazılabilir yol `/tmp` (emptyDir). `--user-data-dir=/tmp/...` ile çözülür ama her koşumdan sonra temizlik şart — aksi halde emptyDir dolar ve pod'un tamamı düşer.

**`/dev/shm` 64 MB.** Kubernetes varsayılanı. Chromium bunu aşar ve sekme çökmesiyle sonuçlanır. Ya `--disable-dev-shm-usage` (performansı düşürür, ölçümü etkiler — yani ölçtüğünüz LCP'ye kendi kısıtınızı katarsınız) ya da `emptyDir: {medium: Memory}` ile `/dev/shm` mount'u gerekir.

**`capabilities: drop ALL` + `seccompProfile: RuntimeDefault` + `runAsNonRoot`.** Chromium'un kendi sandbox'ı user namespace ister; bu kısıtlar altında çalışmaz. Pratikte `--no-sandbox` gerekir. **Bu, güvenlik açısından en ağır maddedir:** Site Monitor izlenen sayfalardan gelen **güvenilmeyen JavaScript'i** sandbox'sız çalıştırır hâle gelir. Bir banka ortamında bu, güvenlik ekibinin onayı olmadan geçilemez. Ayrı bir runner kabında (B2), kendi namespace'inde, ağ politikası kısıtlı çalıştırmak bu riski yönetilebilir kılar — ana uygulama pod'unda çalıştırmak kılmaz.

**İmaj boyutu.** Alpine üzerine Chromium ~250-400 MB. Mevcut runtime `eclipse-temurin:25-jre-alpine`. Deploy süresi, registry maliyeti ve Trivy tarama yüzeyi (`docker-build.yml`) büyür.

**k6 yükseltme zinciri (yalnız B1 için).** 0.49 → 2.x: `k6/experimental/browser` → `k6/browser` import yolu, `--summary-export` kaldırılması, `ScriptedCheckerService`'in özet ayrıştırmasının yeniden yazımı, `ScriptedTemplate` şablonlarının doğrulanması. Bu, CWV işinden bağımsız bir migrasyon projesidir.

---

## 6. Önerilen yol

**Üç fazlı, her fazı tek başına değer üreten bir plan.** Her fazın sonunda durabilirsiniz.

### Faz 0 — Dürüst vekil (1-2 gün, sıfır risk)

`pagespeed_checks`'te **zaten duran** veriden bir "LCP Riski" göstergesi türetin: TTFB + toplam bayt + istek sayısı + render-blocking kaynak sayısı. Sayfa Hızı modalinde `LCP` değil **"LCP riski: YÜKSEK — TTFB 2.1 sn, LCP'nin 2.5 sn altında kalması mümkün değil"** biçiminde gösterin.

Bu faz hiçbir şey ölçmez ama **bugün var olan veriden bugün alınabilecek değeri alır** ve projenin "iddia etmeme" kuralını çiğnemez. Ayrıca Faz 1 geldiğinde vekilin ne kadar isabetli olduğunu geriye dönük ölçebilirsiniz.

### Faz 1 — Sentetik LCP + CLS (asıl iş)

Ayrı bir **CWV runner servisi** (Node + Playwright + `web-vitals` kütüphanesi, kendi Deployment'ı, kendi kaynak limiti, kısıtlı NetworkPolicy). Spring tarafı ona HTTP ile `{url, ua, headers, timeout}` gönderir, `{lcp, cls, fcp, ttfb}` alır.

Site Monitor tarafında dokunulacak yerler — `sayfa_hizi` belleğindeki "yeni tür entegrasyon yüzeyi" haritasının aynısı, ama yeni tür açmadan:

- `PageSpeedMonitor`: `browserMode` (bool), `maxLcpMs`, `maxCls` eşikleri + `applySchemaPatches()` satırları
- `PageSpeedCheck`: `lcp_ms`, `cls_score`, `fcp_ms` kolonları + patch satırları
- `PageSpeedRules`: yeni eşik anahtarları `LCP`, `CLS` → `breached_metrics` / `breach_detail`
- `MonitoringController:3386` `?metric=` switch'ine `lcp` / `cls`
- `PageSpeedMonitorPage.jsx:95` `METRICS` dizisine iki kayıt + i18n TR/EN parite
- `EscalationService` e-posta detay tablosuna iki satır (üç yol tutarlılığı: ilk alarm / çözüm / yeniden gönderme)
- `RetentionCatalog` — yeni kolonlar mevcut `pagespeed` politikasına dahil
- **`PageSpeedMonitor` ve `PageSpeedCheckerService` Javadoc'larındaki "CWV iddia edilmez" beyanının güncellenmesi** — artık "LCP ve CLS tarayıcı modunda ölçülür; INP ölçülmez ve p75 alan verisi değildir" olarak

Arayüzde **ölçüm modunu görünür kılın**: "Statik (JS çalışmaz)" / "Tarayıcı (JS çalışır)" rozeti. Kullanıcı hangi sayının nereden geldiğini rozete bakarak anlamalı.

### Faz 2 — RUM (yalnız INP ve gerçek p75 gerçekten gerekiyorsa)

Bu faz teknik değil **organizasyonel** bir projedir: UG ekipleri, değişiklik yönetimi, KVKK/hukuk onayı, veri sınıflandırması. Teknik iş (`/api/rum/vitals` ucu + histogram toplama + p75) muhtemelen en kısa kalemi olacaktır.

**Faz 2'ye ancak şu soru "evet" ise girin:** *"INP'yi ölçmemek ya da Google'ın gördüğü p75'i görmemek bize somut olarak neye mal oluyor?"* Halka açık kurumsal sayfalarda SEO cevabı olabilir. Giriş arkası internet şubesinde muhtemelen cevap "hiçbir şeye" — orada Faz 1 yeter.

---

## 7. Efor tahmini

| Faz | Backend | Frontend | Altyapı/DevOps | Onay süreçleri | Toplam |
|---|---|---|---|---|---|
| Faz 0 — vekil | 1 gün | 0.5 gün | — | — | **~2 gün** |
| Faz 1 — sentetik LCP+CLS | 5-7 gün | 3-4 gün | 4-6 gün (imaj, K8s, NetworkPolicy, kapasite) | Güvenlik onayı (`--no-sandbox`) | **~4-6 hafta** |
| Faz 2 — RUM | 5-8 gün | 3 gün | 3 gün | **KVKK + UG ekipleri + değişiklik yönetimi** | **Takvimi organizasyon belirler** |

Faz 1'in kritik yolu kod değil, **güvenlik onayı ve kapasite planı**. Faz 2'nin kritik yolu kod değil, **hukuk ve ekip koordinasyonu**.

---

## 8. Karar noktaları

Uygulamaya geçmeden önce cevaplanması gerekenler:

- **K1 — Kapsam.** CWV hangi envanter için? Yalnız `tier=1` (Customer-Facing Prod) mu, tümü mü? Tarayıcı ölçümü pahalı — envanterin tamamına açılamaz, seçilmiş bir alt küme gerekir.
- **K2 — INP şart mı?** Cevap "evet" ise Faz 2 kaçınılmazdır ve proje bir uygulama-değişikliği projesine dönüşür. "Hayır" ise Faz 1 yeter ve iş Site Monitor ekibinin elinde kalır.
- **K3 — Yeni tür mü, mevcut türün genişletilmesi mi?** Öneri: **genişletme.** `pagespeed` türü zaten "sayfa ne kadar hızlı" sorusunu soruyor; 11. tür açmak `MonitorTypeCatalog`, `WeeklyMonitoringStrip.ORDER`, `responseSeries_contract_allEndpoints`, `VALID_TABS`, izin kataloğu ve haftalık rapor zincirinin tamamını ikinci kez dolaşmak demek.
- **K4 — `--no-sandbox` kabul edilebilir mi?** Güvenlik ekibinin cevabı hayırsa Yol B tamamen düşer ve geriye A + C + D kalır. **Bu soru diğer her şeyden önce sorulmalı.**
- **K5 — Runner nerede koşacak?** Ana pod (ucuz, riskli) mi, ayrı Deployment (temiz, +1 bileşen) mi? Öneri: ayrı.
- **K6 — Eşik modeli.** `PageSpeedMonitor`'ün mevcut felsefesi "null eşik = alarm yok". LCP/CLS için varsayılan eşik Google'ın değerleri mi (2500 ms / 0.1) yoksa null mı? Öneri: **null** — Google eşiği alan verisi için tanımlı, lab ölçümünde yanlış yere oturur; kullanıcı kendi tabanını gördükten sonra koysun.
- **K7 — Alarm tipi.** Mevcut `PAGESPEED_SLOW` yeniden kullanılsın (`breached_metrics`'e `LCP`/`CLS` eklenir) mi, ayrı `PAGESPEED_CWV` mi? Öneri: **yeniden kullanım** — escalation'ın üç yolu (ilk/çözüm/yeniden-gönder) ve `MonitorTypeCatalog` kapısı zaten tutarlı.
- **K8 — Ölçüm sıklığı.** `MIN_INTERVAL_SECONDS = 300` statik mod için konmuş. Tarayıcı modu için taban daha yüksek olmalı (öneri: 15 dk) — aksi halde tek pod boğulur.
- **K9 — Google eşiği nasıl sunulacak?** Sentetik LCP'yi 2.5 sn ile karşılaştırıp "Good" yazmak, olmayan bir p75 iddiasıdır. Öneri: arayüzde "referans eşik (Google, alan verisi)" diye ayrı etiketlenmeli; rozet **"eşiğin altında"** demeli, **"Good"** dememeli.
- **K10 — Yol C paneli istenir mi?** Halka açık kurumsal alan adları için PSI/CrUX okuyan ayrı bir rapor sekmesi; kurumsal proxy + API anahtarı + kota işi.

---

## 9. Yapılmaması gerekenler

**Mevcut statik ölçüme CWV etiketi koymayın.** `PageSpeedMonitor` Javadoc'undaki "iddia edilmez" cümlesi bir kısıt değil, projenin en değerli kurallarından biri. TTFB'yi LCP diye göstermek, kullanıcının yanlış sayıya bakarak doğru karar verdiğini sanmasına yol açar.

**Sentetik ölçümü "p75" diye etiketlemeyin.** Kendi koşumlarınızın p75'ini hesaplayabilirsiniz ve bu faydalıdır — ama adı "ölçüm p75'i" olmalı, "kullanıcı p75'i" değil. Aynı ada sahip iki farklı istatistik, ikisi de raporda göründüğünde kimse hangisinin hangisi olduğunu hatırlamaz.

**Senaryolu tıklamadan üretilen sayıyı INP diye raporlamayın.** Tek bir butona tıklayıp ölçülen gecikme, sayfanın ömrü boyunca tüm etkileşimlerinin p75'i değildir. Google'ın kendi lab aracı bunu yapmıyor; sebebi var.

**k6'yı yalnız CWV için yükseltmeyi bu işin içine sokmayın.** 0.49 → 2.x migrasyonu, çalışan Sentetik İzleme türünün özet sözleşmesini (`--summary-export`) kırar ve kendi başına bir migrasyon projesidir. Ayrı runner servisi bu bağımlılığı tamamen ortadan kaldırır — CWV işi k6'ya hiç dokunmadan biter.

**Chromium'u ana uygulama pod'una koymayın.** Güvenilmeyen JS'i sandbox'sız çalıştıran bir süreçle sertifika özel anahtarlarına, LDAP bind parolasına ve `SecretCipher` anahtarına erişen bir JVM'i aynı kapta tutmak, kazanılan kolaylığa değmez.

---

## Kaynaklar

- [Core Web Vitals report — Google Search Console Help](https://support.google.com/webmasters/answer/9205520?hl=en)
- [Browser metrics — Grafana k6 documentation](https://grafana.com/docs/k6/latest/using-k6-browser/metrics/)
- [Using k6 browser — Grafana k6 documentation](https://grafana.com/docs/k6/latest/using-k6-browser/)
- [Migrating browser scripts to k6 v0.52 — Grafana k6](https://grafana.com/docs/k6/latest/using-k6-browser/migrating-to-k6-v0-52/)
- [Why Google Lighthouse Doesn't Include INP, A Core Web Vital — Search Engine Journal](https://www.searchenginejournal.com/why-google-lighthouse-doesnt-include-inp-a-core-web-vital/528734/)
- [Total Blocking Time (TBT) — web.dev](https://web.dev/articles/tbt)
- [SREmonitor](https://sremonitor.io/) — referans ürün; "no code installation required" vaadi sentetik ölçüm yaklaşımını doğruluyor
- Proje kaynakları: `model/PageSpeedMonitor.java`, `model/PageSpeedCheck.java`, `service/PageSpeedCheckerService.java`, `service/SchedulerService.java:3613`, `service/EscalationService.java:178`, `controller/MonitoringController.java:3374`, `controller/ClientErrorController.java`, `k8s/deployment.yaml`, `Dockerfile`

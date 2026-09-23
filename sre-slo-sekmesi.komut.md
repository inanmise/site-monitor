---
description: HTTP/Website izleme modaline SRE & SLO sekmesi ekler — SLO durum kartları, hata bütçesi, yanma hızı, 30 günlük SLO ızgarası. Yalnız MEVCUT veriden hesaplar; ölçemediğini göstermez. K1–K12 cevaplanmadan kod yazılmaz.
---

# /sre-slo-sekmesi — HTTP/Website modalinde SRE & SLO sekmesi

## Amaç

HTTP/Website izleme kartına tıklanınca açılan modale yeni bir sekme ekle. Sekme, o monitörün
URL'i dışında **hiçbir yeni girdi istemeden**, halihazırda toplanan kontrol verisinden bir SRE
panosu üretsin: SLO durumu, hata bütçesi, yanma hızı ve 30 günlük SLO geçmişi.

Referans görsel: SREmonitor.io monitör panosu (SLO STATUS / SLO & ERROR BUDGET TRACKING blokları).
Referans **düzen ve metrik seçimi** içindir; **tasarım kopyalanmaz** — aşağıdaki R3 kuralına bak.

---

## Bu komut ne YAPMAZ — dürüstlük kapıları

Bu dört kural pazarlığa kapalıdır. Referans ekranda görünen her kutu bu projede üretilebilir
değildir ve üretilemeyeni üretilmiş gibi göstermek, projenin en eski kuralını çiğner
(`PageSpeedMonitor` sınıf Javadoc'u: *"ölçümün dürüst sınırı … iddia edilmez"*).

- **D1 — Core Web Vitals (LCP/INP/CLS) bu sekmede GÖSTERİLMEZ.** Gerçek tarayıcı motoru yok;
  `docs/CORE_WEB_VITALS_FIZIBILITE.md` bunu gerekçesiyle belgeliyor. Sekmede CWV bölümü
  açılacaksa yalnız "bu sürümde ölçülmüyor" durumunda olur ve sahte/boş kart konmaz.
- **D2 — Perf Score (0-100) GÖSTERİLMEZ.** Lighthouse skorudur, aynı Chromium bağımlılığına
  tabidir. CWV ile aynı fazda gelir ya da hiç gelmez.
- **D3 — Compliance skoru ve Google Search Console bloğu KAPSAM DIŞI.** Biri ayrı bir ürün
  yeteneği, diğeri dış bağımlılık; ikisi de bu sekmenin işi değil.
- **D4 — Ölçülemeyen hücre "N/A" yazar, sıfır yazmaz.** `PageSpeedCheck` faz kolonlarındaki
  kural aynen geçerli: *"Ölçülemeyen faz null kalır — sıfır yazmak, olmayan bir hızı iddia
  etmek olurdu."*

---

## Doğrulanmış entegrasyon yüzeyi

Aşağıdaki her satır 2026-09-13 keşfinde **kaynaktan** doğrulandı. Uygularken bu haritayı
kontrol listesi gibi kullan; yeniden keşfetme.

### Veri kaynakları — HEPSİ ZATEN TOPLANIYOR

| Ne gerekiyor | Nereden | Not |
|---|---|---|
| Ham kontrol satırı | `http_checks` → `ok`, `response_ms` (Long), `http_status`, `checked_at` | `HttpCheck.java` |
| Günlük kova | `monitor_check_daily` → `(monitor_type, monitor_key, day, total_checks, up_checks, avg_response_ms, max_response_ms)` | `SchedulerService.upsertSql` (~1681); HTTP satırı `rollupUpsert("HTTP","http_checks","ok","response_ms",…)` (`:1598`); `monitor_key = CAST(monitor_id AS varchar)`, `day = substr(checked_at,1,10)` |
| Saatlik kova | aynı şema, `rollupInto(target, bucketCol, len, …)` (`:1672`) | günlük ve saatlik yol BİRE BİR aynı SQL üreticisinden çıkar |
| Kova bazlı p95 | `MonitoringController.buildResponseSeries` (`:2431`), `pt.put("p95", …)` (`:2465`) | avg/min/max/p95/count/down üretiyor — **p50 YOK** |
| Uptime yüzdeleri | `MonitorSparklineService.availability(type, days, ids)` → `up_pct`, `bad_hours` | `ExecutiveStatsService:117` bunu kullanıyor |
| Global SLA hedefi | `site.monitor.sla.target-pct` (varsayılan **99.9**) | `AppSettingsCatalog:200`, okuyan `ExecutiveStatsService:109` |
| Seri ucu | `GET /monitoring/http/{id}/response-series` | `MonitoringController:2778`, `buildResponseSeries` hunisinden |
| Takım kapsaması (IDOR) | `SessionScope.canView` — sparkline servisi monitör→takım haritası döner, **süzme çağıranda** | `MonitorSparklineService` sınıf Javadoc'u |

### Eksik olanlar — bu komutun asıl işi

| Eksik | Durum |
|---|---|
| Monitör başına SLO hedefi (erişilebilirlik + gecikme) | `HttpMonitor`'da gecikme eşiği **hiç yok** — `maxResponseMs` diye bir alan yok (alan listesi doğrulandı) |
| p50 | Hiçbir yerde hesaplanmıyor |
| Hata bütçesi (% kalan) | Kodda hiç geçmiyor |
| Yanma hızı (burn rate) | Kodda hiç geçmiyor |
| MET / RİSKTE / AŞILDI rozeti (monitör düzeyinde) | Yalnız executive'de "hedef altı monitör **sayısı**" var |
| 30 günlük SLO ızgarası | **Veri var** (`monitor_check_daily`), UI yok |

### Frontend dokunuş noktaları

`frontend/src/components/HttpMonitorPage.jsx`:

- `const [detailTab, setDetailTab] = useState('control')` — **:128**
- `openDetail()` sekmeyi `'control'`a sıfırlar — **:198**
- URL senkronu: `mtab: selected && detailTab !== 'control' ? detailTab : null` — **:439**
- Sekme düğmeleri bloğu `<div className="modal-tabs">` — **:636-644**
  (mevcut: `control` / `alerts` / `chart` / `notes` / `changes`)
- Sekme gövdeleri — **:646-676**

Ayrıca: `App.jsx` `mtab` beyaz listesi (yeni anahtar oraya eklenmezse derin link sessizce düşer),
`i18n/index.jsx` TR **ve** EN (parite testi `i18n-parity.test.jsx` boş değer ve placeholder
sayısı dahil kapılıyor), ikonlar yalnız `lucide-react` (emoji YASAK).

---

## Kapsam — iki faz, ikisi de tek başına sevk edilebilir

**Faz A (bu komutun ana işi) — SLO katmanı.** Yeni veri toplama YOK. Erişilebilirlik SLO'su,
gecikme SLO'su, p50/p95, hata bütçesi, yanma hızı, 30 günlük ızgara. Tamamı yukarıdaki mevcut
tablolardan hesaplanır.

**Faz B (ayrı iş) — tarayıcı gerektirenler.** CWV + Perf Score. `docs/CORE_WEB_VITALS_FIZIBILITE.md`
Faz 1'ine bağlıdır ve **güvenlik onayı olmadan başlamaz** (Chromium `--no-sandbox`). Bu komut
Faz B'yi uygulamaz; yalnız sekmede yerini boş bırakmadan, "bu sürümde ölçülmüyor" diye dürüstçe
işaretler.

### Faz B notları — referans üründen gelen saha kanıtı (2026-09-13)

Referans ürün aynı URL için CWV bloğunu doldurdu ve sonuç fizibilite raporunun iki ana iddiasını
**birebir doğruladı**:

| Kart | Değer | Ne söylüyor |
|---|---|---|
| LCP | 3635 ms (hedef ≤2500) | Sentetik ölçülebiliyor |
| FCP | 4350 ms (≤1800) | Sentetik ölçülebiliyor |
| TTFB | 2350 ms (≤800) | Sentetik ölçülebiliyor |
| CLS | 0.010 (≤0.1) | Sentetik ölçülebiliyor |
| **INP** | **N/A** (≤200) | **Ticari ürün de ölçemiyor** — etkileşim yok, INP yok |
| Perf Score | 21 (≥70) | Lighthouse skoru → arkada headless Chrome var |

İki çıkarım, Faz B'ye girildiğinde uygulanacak:

1. **INP'nin doğru gösterimi "N/A"dır.** Parası ödenen bir ürün bile bu hücreyi boş bırakıyor.
   Faz B geldiğinde INP kartı konacaksa değeri **N/A** olur ve altında sebebi yazar; senaryolu
   tıklamadan üretilmiş bir sayı buraya **yazılmaz** (D1'in devamı).

2. **Kısıtlama (throttling) kararı, sayıların birbirini yalanlamasını önler.** Aynı sayfa için
   referans ürün TTFB'yi **2350 ms** gösterirken Site Monitor'ün HTTP gecikmesi **212 ms**.
   Arada ~11 kat var ve ikisi de doğru: biri ham sunucu yanıtı, diğeri Lighthouse'un varsayılan
   simüle mobil/4G kısıtlaması altındaki tarayıcı ölçümü. Faz B'ye girildiğinde **önce** şu
   karar verilir: ölçüm kısıtlamalı mı (Google'ın eşikleriyle kıyaslanabilir ama mevcut gecikme
   grafiğiyle çelişir) yoksa kısıtlamasız mı (grafikle tutarlı ama 2.5 sn eşiği anlamını
   yitirir)? Hangisi seçilirse seçilsin **kartın üstünde yazar** — aksi halde aynı ekranda
   212 ms ve 2350 ms yan yana durur ve kullanıcı hangisine inanacağını bilemez.

   Not: `PageSpeedCheck` zaten kendi `ttfb_ms`/`server_ms` kolonlarını taşıyor. Faz B'de üçüncü
   bir TTFB tanımı daha doğar; üçünün hangisi olduğu etiketlenmezse üç sayı birbirini yalanlar.

---

## Hesaplama sözleşmesi — uydurma, birebir bunu uygula

Yanlış hesaplanmış bir SLO, hiç SLO olmamasından kötüdür. Formüller:

**Erişilebilirlik (30 gün)**
```
availability = SUM(up_checks) / SUM(total_checks)
  FROM monitor_check_daily
 WHERE monitor_type='HTTP' AND monitor_key=CAST(:id AS varchar) AND day >= :from
```

**Gecikme p50 / p95 (30 gün)** — `monitor_check_daily` yalnız avg/max taşır, **yüzdelik
taşımaz**. Yüzdelik için ham `http_checks` gerekir (5 dk aralıkta 30 gün ≈ 8.6k satır/monitör —
kabul edilebilir, ama `SERIES_RAW_CAP` benzeri bir tavan koy). `ok=false` satırların `response_ms`i
istatistiğe **girmez** (`buildResponseSeries` kuralı: *"Null süreler istatistiğe katılmaz"*).

**Hata bütçesi** — hangi SLO'ya bakıldığı açıkça yazılmalı:
```
erişilebilirlik bütçesi: izin verilen hata = (1 - target) × total_checks
                         harcanan         = total_checks - up_checks
gecikme bütçesi:         izin verilen ihlal = (1 - latencyObjective) × total_checks
                         harcanan           = COUNT(response_ms > latencyTargetMs)
kalan %                = 1 - (harcanan / izin verilen);  izin verilen = 0 ise N/A
```
Referans ekrandaki kutu **gecikme** bütçesiydi ("Target: 99% requests ≤ 400ms"), yani iki ayrı
hedef var: eşik (400 ms) ve o eşiği tutturma oranı (%99). İkisini tek alana sıkıştırma.

**Yanma hızı**
```
burnRate = gözlenen ihlal oranı / izin verilen ihlal oranı
1.0x  = bütçe tam pencere sonunda biter
<1.0x = güvenli · >1.0x = pencere dolmadan biter
izin verilen oran 0 ise N/A (bölme yok)
```

**30 günlük ızgara** — gün başına `monitor_check_daily` satırından:
```
up_pct = up_checks / total_checks
kare: up_pct >= target        → Hedef tutturuldu
      up_pct >= target - band → Sınırda
      up_pct <  target - band → Aşıldı
      satır yok               → Veri yok   (gri; "0%" DEĞİL — D4)
```
`band` bir ayar olmalı (K7).

**Zaman dilimi.** `checked_at` UTC saklanır; `day` kovası UTC'ye göre kesilir. Arayüz
Europe/Istanbul gösteriyor. Izgaradaki "gün" ile kullanıcının gördüğü gün 3 saat kayabilir —
bu bir hata değil ama **bilinçli karar** gerektirir (K8). `setUTC*` tuzağı için CLAUDE.md'ye bak.

---

## Karar noktaları — K1'den K12'ye cevaplanmadan tek satır kod yazılmaz

**K1 — Sekmenin adı.** Talep "SREmonitor" idi. Şunu bir kez söyleyip kararı sana bırakıyorum:
başka bir şirketin ürün adını kurum içi bir aracın sekmesine yazmak hem marka açısından tuhaf,
hem de var olmayan bir entegrasyonu ima eder (kullanıcı "SREmonitor'e mi bağlanıyoruz?" diye
sorar). Öneri: **"SRE & SLO"** (TR) / **"SRE & SLO"** (EN). Alternatif: "Servis Seviyesi".
Yine de "SREmonitor" istenirse uygulanır — ama bilinçli seçim olsun.

**K2 — SLO hedefi nerede tanımlanır?** (a) Bugünkü tek global ayar (`site.monitor.sla.target-pct`)
yeter mi, (b) monitör başına kolon mu (`slo_target_pct`, `latency_target_ms`, `latency_objective_pct`
+ `applySchemaPatches()` satırları), (c) ikisi: monitörde null ise globale düş? **Öneri: (c)** —
projedeki "null eşik = zinciri işlet" deseniyle aynı.

**K3 — Gecikme hedefi varsayılanı.** `HttpMonitor`'da gecikme eşiği hiç yok. Varsayılan
null (SLO kartı "hedef tanımlı değil" der) mi, yoksa bir sayı mı? **Öneri: null** — uydurma bir
400 ms, kullanıcının kendi tabanını görmeden yanlış yere oturur (`PageSpeedMonitor` eşik felsefesi).

**K4 — Pencere.** Sabit 30 gün mü, seçilebilir (7/30/90) mi? 90 gün ham `http_checks` taraması
p50 için ağırlaşır — tek pod. **Öneri: 30 sabit, 7 opsiyonel.**

**K5 — p50/p95 nereden?** (a) Ham `http_checks` + tavan, (b) `monitor_check_daily`'ye
`p50_response_ms`/`p95_response_ms` kolonları ekleyip rollup'ta hesapla (şema + `upsertSql`
değişikliği, saatlik yol da etkilenir), (c) yalnız p95, mevcut `buildResponseSeries`'ten.
**Öneri: (a)** — rollup SQL'ine dokunmak, günlük/saatlik yolun birebir aynı olduğunu kanıtlayan
testi de etkiler; bu işin kapsamını gereksiz büyütür.

**K6 — Hangi bütçe gösterilecek?** Yalnız gecikme (referans ekrandaki gibi), yalnız
erişilebilirlik, yoksa ikisi yan yana mı? **Öneri: ikisi** — SRE'de ayrı SLI'lardır ve biri
yeşilken diğeri yanıyor olabilir.

**K7 — "Sınırda" bandı.** Izgarada sarı kare hangi aralık? (ör. hedefin 0.05 puan altı).
Sabit mi, ayar mı?

**K8 — Izgara günü UTC mi, Europe/Istanbul mu?** Rollup UTC kovalıyor. Kullanıcının gördüğü
günle hizalamak için yeni bir yerel-gün rollup'ı mı yazılacak, yoksa ızgara "UTC günü" diye
etiketlenip olduğu gibi mi gösterilecek? **Öneri: ikincisi** — ucuz ve dürüst; etiket açıkça
yazsın.

**K9 — Bakım pencereleri.** `uptimeUpsertSql` bakımı hariç tutuyor
(`maintenance = false OR maintenance IS NULL`) ama HTTP rollup'ı **tutmuyor** — bakım sırasındaki
düşüşler HTTP erişilebilirliğine yazılıyor. SLO hesabı bakımı hariç tutacak mı? Tutacaksa bu
`monitor_check_daily`'nin HTTP satırlarını etkileyen ayrı bir iştir. **Bu soru atlanırsa SLO
sayısı planlı bakımlarda haksız yere kırmızıya döner.**

**K10 — Alarm üretilecek mi?** Bütçe %X'in altına inince ya da burn rate > N olunca alarm mı?
Eğer evet ise `MonitorTypeCatalog` + `EscalationService` TYPE_* + `isStandaloneMon` + üç yol
tutarlılığı (ilk/çözüm/yeniden-gönder) + frontend `alertTypeMeta` kapısı devreye girer —
kapsam ciddi büyür. **Öneri: bu fazda HAYIR, yalnız görselleştirme.**

**K11 — İzin anahtarı.** Yeni bir `PermissionCatalog` kaydı mı (`http.slo.view`), yoksa mevcut
HTTP görüntüleme izni mi yeter? Yeni anahtar eklenirse **aynı değişiklikte** `PermissionCatalog`'a
girmeli (CLAUDE.md kuralı) — yoksa matris UI'da görünmez ve bootstrap grant'i oluşmaz.

**K12 — Diğer türlere yayılacak mı?** Aynı pano ping/port/keyword/page/pagespeed için de
anlamlı. Bu fazda yalnız HTTP mi, yoksa `kind` parametreli ortak bileşen mi? **Öneri: ortak
bileşen yaz, yalnız HTTP'ye bağla** — ikinci tür bedava gelir, ama bu fazda sevk edilmez.

---

## Uygulama sırası

1. K1–K12 cevaplanır. Cevaplar bu dosyanın altına "Kararlar" başlığıyla yazılır.
2. **Backend — hesap servisi.** Yeni `SloService` (ya da `MonitorSloService`): pencere, hedef,
   availability, p50/p95, iki bütçe, burn rate, günlük ızgara. Saf hesap — test edilebilir olsun
   (`WeeklyAvailabilityReportService` deseni: *"saf hesap + oluşturma ayrı"*).
3. **Backend — uç.** `GET /monitoring/http/{id}/slo?days=30`. `SessionScope.canView` ile takım
   süzmesi (IDOR — sparkline servisinin kuralı). Yanıt `no-store` (tüm `/api` öyle).
4. **Şema.** K2 (b/c) seçildiyse `HttpMonitor` kolonları + `applySchemaPatches()` `patch()`
   satırları. `ddl-auto`'ya güvenme (CLAUDE.md).
5. **Frontend.** `HttpMonitorPage.jsx`: sekme düğmesi (**:636-644** bloğuna), gövde (**:646-676**),
   `App.jsx` `mtab` beyaz listesine anahtar. Yeni bileşen `components/slo/SloPanel.jsx` —
   `kind` + `monitorId` alsın (K12).
6. **i18n.** TR + EN aynı değişiklikte; parite testi kapıda.
7. **Testler.** Backend: hesap birim testleri (bütçe sıfır → N/A, veri yok → N/A, burn rate
   bölme yok). Frontend: bozuk/eksik kayıtla çökmeme (`ResponseTimeChart` savunmacı filtre deseni).
8. `mvn verify` → yeşilse `npm run build` → backend restart → smoke. `mvn test` repackage
   ETMEZ; `mvn package -DskipTests` şart (CLAUDE.md).

---

## Regresyon yasakları

- **R1 — `monitor_check_daily` rollup SQL'ine dokunma** (K5'te (b) seçilmediyse). `upsertSql`
  günlük ve saatlik yolun birebir aynı olduğunu kanıtlamak için ayrılmış; değiştirirsen iki
  yolu birden değiştirmiş olursun.
- **R2 — Mevcut beş sekme davranışı bozulmaz.** `detailTab` varsayılanı `'control'` kalır,
  `openDetail()` sıfırlaması korunur, `mtab` senkronu mevcut anahtarları kaybetmez.
- **R3 — Tasarım kopyalanmaz.** Referans ekranın metrik seçimi ve blok düzeni alınır; renkler,
  tipografi ve bileşenler **Site Monitor'ün kendi sisteminden** gelir (`components/ui/`
  primitifleri, `upt-*` sınıf ailesi, `[data-theme="dark"]` token'ları). Başka bir ürünün
  görsel kimliğini taşıma; hem gereksiz hem de temanın açık/koyu davranışını kırar.
- **R4 — `useT` null-context tuzağı.** Yeni bileşen `test-utils.jsx#render()` ile sarılmadan
  test edilmez.
- **R5 — D1-D4 dürüstlük kapıları** her incelemede yeniden kontrol edilir.

---

## Kabul kriterleri

- Hiç veri olmayan yeni bir monitörde sekme **çökmez**, her kutu "N/A" veya "Veri yok" der.
- Hedef tanımlı değilken bütçe ve rozet "hedef tanımlı değil" der; uydurma hedefle hesap yapmaz.
- `total_checks = 0` olan günler ızgarada gri; `up_pct = 0` olan günler kırmızı — ikisi
  karışmaz.
- Başka takımın monitörünün `id`'si elle çağrıldığında uç **403/404** döner (IDOR).
- TR ve EN'de her etiket dolu; parite testi yeşil.
- `mvn verify` ve `npm run test` yeşil.

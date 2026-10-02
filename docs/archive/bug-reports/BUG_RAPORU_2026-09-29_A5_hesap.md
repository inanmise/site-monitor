# BUG RAPORU — 2026-09-29 · Eksen A5 (E4): Sayısal / istatistik / zaman-penceresi hesapları

**Kapsam:** SiteMonitor `main` @ v20.91.0 + commit edilmemiş "ortam adı ayarı" değişikliği (o değişiklik bu eksene
dokunmuyor; `BuildInfo`/`DeploymentHistoryService` diff'i sayısal hesap içermiyor). **Yöntem:** salt okuma — kod
değiştirilmedi, test/derleme/sunucu koşturulmadı. Görevdeki 22 backend + 17 frontend dosyanın tamamı açılıp okundu
(liste sonda, "okundu ✓"); her aday satır kaynakta doğrulandı, grep eşleşmesi tek başına bulgu sayılmadı. Baseline
(`BUG_RAPORU_2026-09-23` Y6/Y7, `BUG_RAPORU_5` #5, `BUG_REGRESYON_2026-09-29c` D-c11, port-alarm-rca `resolved_at`
notu) yeniden kontrol edildi. Gerçek kişi/kurum/alan adı yazılmadı (`example.com`, "Takım A").

**Genel değerlendirme:** sayısal çekirdek sağlam — yüzde kuralı `AvailabilityMath`'te tekleşmiş, percentile'lar
nearest-rank/kova-içi interpolasyon, sıfıra bölme korumaları yerinde, hafta/gün pencereleri Europe/Istanbul'a bağlı,
teyit/kurtarma sayaçları doğru. Sistemik açık yok; bulgular **tekil sapmalar** ve **kardeş yüzeye taşınmamış
düzeltmeler** (aynı sınıf bir yerde kapatılmış, ikizinde açık). Bir bulgu (bakım penceresi > 24 sa) yanlış alarm
üretebildiği için YÜKSEK.

**Önem dağılımı:** KRİTİK 0 · **YÜKSEK 1** · **ORTA 5** · **DÜŞÜK 8** (2'si baseline AÇIK) · bilgi amaçlı 2.

---

## AÇIK bulgular

### Y-1 · YÜKSEK — `MaintenanceService.java:140-144` (`isActiveAt`) ve `:106-111` (`activeEndAt`) — 24 saati aşan TEKRARLAYAN bakım penceresi ikinci günden sonra "aktif değil" sayılıyor

**Bug.** Tekrarlayan (DAILY/WEEKLY/MONTHLY) pencerede yalnız `today.minusDays(1)` ve `today` günleri oluşum adayı
olarak deneniyor ("bugün + dün — gece yarısını aşan pencereler için"). Süre sınırsız: `MaintenanceController.java:287`
`Math.max(1, n)`, `MaintenanceEditor.jsx:144` özel dakika girişi `min="1"` (üst sınır yok), `DurationPicker` ön
ayarları dışında serbest sayı. Yani 62 saatlik "hafta sonu bakımı" tanımlanabiliyor ama motor onu yalnız ilk ~30
saat tanıyor.

**Neden bug — somut örnek.** WEEKLY, gün = Cuma, başlangıç Cuma 18:00 (Europe/Istanbul), `durationMinutes = 3720`
(Pazartesi 08:00'e kadar).
- Cumartesi 10:00 → adaylar {Cuma, Cumartesi}; Cuma eşleşir, `start=Cu 18:00`, `now < start+62sa` → **aktif ✓**.
- Pazar 10:00 → adaylar {Cumartesi, Pazar}; hiçbiri Cuma değil → **`isActiveAt=false` ✗** (doğrusu: aktif, bitiş Pzt 08:00).
- Pazartesi 07:00 → adaylar {Pazar, Pazartesi} → yine **false ✗**.

Pazar 00:00 – Pazartesi 08:00 arası (62 saatlik pencerenin 32 saati) bakım yokmuş gibi işlenir:
`isUnderMaintenance(target)=false` → sweep DOWN görünce teyit zinciri + `processConfirmedOutage` + e-posta/push
(**yanlış alarm**); `UptimeCheck.maintenance=false` yazılır → haftalık erişilebilirlik `computeRow`'da bakım hariç
tutulamaz (**yanlış %**); `WeeklyOutageReportService.overlapsMaintenance` "bakıma denk gelmiyor" der; Genel Bakış
bakım rozeti (`activeEndAt`) ve "Susturulmuş ve bakımda" kartı (`TodayMonitorInsightsService.maintenance:455`)
pencereyi görmez, `nextOccurrence` "gelecek Cuma" yazar.

**Çözüm.** Aday gün aralığını süreye göre genişlet: `int back = (int) (dur / 1440) + 1;` ve
`for (int i = back; i >= 0; i--) { LocalDate d = today.minusDays(i); … }` — `isActiveAt` ve `activeEndAt` ikisinde
de (ortak yardımcıya al). Kapı testi: WEEKLY Cuma 18:00 + 3720 dk → Pazar 10:00 aktif, Pazartesi 08:01 pasif.
İsteğe bağlı: arayüzde 1440 dk üstü için "çok günlük pencere" ipucu (kısıt DEĞİL; kullanıcı senaryosu meşru).

### O-1 · ORTA — `MonitoringController.java:1344-1345` ve `:1387-1390` (`uptimePct`) — hiç kontrolü olmayan alan "%100 erişilebilir"

**Bug.** `item.put("uptime_7d", s7 == null ? 100.0 : …)` ve `uptimePct(total==0) → 100.0`. Oysa aynı dosyanın
çağırdığı `AvailabilityMath.pct` sözleşmesi (`util/AvailabilityMath.java:24`) *"total <= 0 ise null (veri yok ≠ %100)"*;
`MonitorSparklineService`, `WeeklyAvailabilityReportService.computeRow`, `ExecutiveStatsService.health_pct`,
`DbAnalyticsService.pct` hepsi 0 paydada `null` döner. Bu iki satır O-5 (2026-09-25) süpürmesinden kaçmış.

**Neden bug — örnek.** Bugün eklenen `www.example.com` (henüz uptime kontrolü yok) → `agg7.get(domain)=null` →
`uptime_7d=100.0`, `uptime_30d=100.0`. `UptimePage.jsx:367` "%100" basar (yalnız `null`'da gizler), `:152`
`uptime-asc` sıralamasında kontrolsüz alan "en sağlıklı" olarak listenin sonuna düşer; 7 gündür hiç ölçülemeyen
(kuyruktan düşmüş) bir alan da yeşil "%100" görünür — "bilinmiyor" ile "sorun yok" aynı ekrana düşüyor.
Doğrusu: `null` → arayüz "—".

**Çözüm.** `uptimePct` `Double` döndürsün ve `total == 0 → null`; `:1344-1345` `s7 == null ? null : …`.
`UptimePage.jsx:152` sıralamada `?? 100` yerine `null`'ları sona at. `AvailabilityMathTest`'e "0 kontrol → null"
kardeş vakası (bu metot için) eklensin.

### O-2 · ORTA — `CheckHistoryTab.jsx:306-307, 321` + `i18n/dateLocale.js:48-52` (`formatRatePercent`) + `certmodal/certHistoryModel.js:165-172` / `CertHistoryInsights.jsx:50` + `history/OutageTimeline.jsx:66-68, 144` — hata varken "%100" (R6/O-5 kuralının frontend ikizi)

**Bug.** Backend'de kapatılan kural (*"başarısız kontrol varken sonuç ASLA %100 gösterilmez"*, `AvailabilityMath`)
frontend'de üç yerde tekrar açık:
1. `CheckHistoryTab` erişilebilirlik kutucuğu ham `((total-fail)*100)/total` hesaplayıp `formatRatePercent`'e veriyor;
   o da `v >= 99.995 → '100'` yazıyor (`dateLocale.js:51`).
2. `certHistoryModel.successRate` + `formatRate` aynı yol (Sertifika Kontrol Geçmişi).
3. `OutageTimeline` `availability = 100 − downMin/rangeMin·100` → `toLocaleString(…, 2 ondalık)`; kırpma yok.

Aynı sayfanın Süre Grafiği sekmesi bunu DOĞRU yapıyor: `responseChartModel.formatAvailability(pct, failed)`
(`:300-306`) `failed>0 && v>=100 → floor`. Yani yardımcı var, kardeş yüzey kullanmıyor.

**Neden bug — örnek.** 60 sn aralıklı HTTP izleme, 30 günlük aralık: `total=43 200`, `fail=1` →
`99,99769` → `formatRatePercent` → **"%100"**; yanındaki kutucuk **"Hata: 1"**. Çizelgede 1 dakikalık tek kesinti:
`99,99769` → `toLocaleString` → **"%100,00"**; başlık "1 kesinti · 1 dk". Doğrusu iki yerde de "%99,99"
(`MonitorSparklineService.pct` yorumundaki birebir vaka: *"30 günde 43.200 kontrolde tek hata %100,00"*).

**Çözüm.** `formatRatePercent(r, dash, failed = 0)`: `failed > 0 && v >= 99.995 → v = 99.99` (ondalık sayısına göre
`100 − 10^-n`); `CheckHistoryTab:321` `formatRatePercent(availability, '—', fail)`, `CertHistoryInsights` `fail`
geçsin, `OutageTimeline:144` `segs.length` geçsin. Ya da hepsi `responseChartModel.formatAvailability`'ye bağlansın.
Kapı: `dateLocale.test` "43 200/1 → %99,99".

### O-3 · ORTA — `MonitoringWeeklyStatsService.java:305-307` (`TopTarget.alarms`) ↔ `:344-351` (`perDomainOpened`) — alarm sayısı ad ile, alarm kaydı hedef ile anahtarlanıyor → adlı izlemelerde hep 0

**Bug.** `perDomainOpened` haritayı `AlertEvent.domain` ile kuruyor; `buildStats` ise
`c.perTarget().getOrDefault(name, 0)` — `name = nz(m.getName(), m.getUrl()/getHost()/getDomain())`. Alarm olayının
`domain`'i ise izlemenin HEDEFİ: `SchedulerService.java:3565` HTTP → `m.getUrl()`, `:3522` Ping → `m.getHost()`,
`:3366` Port → `m.getHost()`, `:3154` DNS → `m.getDomain()`, `:3269` Keyword → `m.getUrl()`, `:3733` Page →
`m.getUrl()`, `:4105` PageSpeed → `m.getUrl()`. Yalnız Sentetik (`:4414 m.getName()`) ve sertifika (domain) eşleşir.
Adı dolu her izlemede arama boşa düşer.

**Neden bug — örnek.** HTTP izlemesi ad "Ödeme API", url `https://api.example.com/health`; haftada 3 `HTTP_DOWN`
açılmış (`AlertEvent.domain = https://api.example.com/health`). `nameOf → "Ödeme API"`,
`perTarget.get("Ödeme API") → 0` → haftalık göstergelerde "en sorunlu 3 hedef" satırında **alarm: 0** (doğrusu 3).
`:326` süzgeci `successRate < 100 || alarms > 0` ve `:328` `-alarms` ikincil sıralaması adlı izlemelerde ölü —
kontrolleri %100 geçen ama alarmı olan izleme listeye hiç girmez. Aynı veri PDF eki ve e-posta tür tablosuna gider.

**Çözüm.** `MonRef`'e `target` alanı ekle (`m.getUrl()`/`getHost()`/`getDomain()`/`getName()` — sweep'in `SweepItem.domain`'i
ile AYNI kaynak); `buildStats`'e `Function<Object,String> targetOf` geç ve `perTarget.getOrDefault(targetOf.apply(r[0]), 0)`
kullan; gösterim adı olduğu gibi kalsın. Kapı: adlı HTTP izlemesi + 1 alarm → `TopTarget.alarms == 1`.

### O-4 · ORTA — `history/OutageTimeline.jsx:60-68` (`summarizeOutages`) — çakışan kesinti segmentleri toplanıyor, birleşim alınmıyor → erişilebilirlik olduğundan düşük

**Bug.** `downMinutes = segs.reduce(… + s.minutes)`; aynı aralıkta üst üste binen iki alarm iki kez sayılır.
Backend ikizi bunu bilerek birleşimle yapıyor (`WeeklyOutageReportService.unionMinutes:535-552`, gerekçe: *"yedi
günlük çizelgede 21 gün yazardı"*). Veri kaynağı `CheckHistoryService.execute:162` →
`AlertEventRepository.findOverlappingForHistory:68-71` = anahtardaki (domain + tür) TÜM alarmlar, sahip süzgeci yok.
2026-09-29 Y-1 kararıyla anahtar takımlar arasında paylaşılıyor → aynı URL için iki AÇIK `HTTP_DOWN` (iki sahip)
artık normal bir durum; aynı host'ta aynı takımın 443 + 8443 port izlemeleri de `PORT_DOWN` domain=host ile çakışır.

**Neden bug — örnek.** 24 saatlik aralık; iki takımın aynı `https://www.example.com` izlemesi 10:00–11:00 arasında
ikisi de DOWN → segs = 60 + 60 dk → `downMinutes = 120`, `availability = 100 − 120/1440·100 = %91,67`; doğrusu
birleşim 60 dk → **%95,83**. Kutucukta "2 kesinti · 2 sa" (gerçek 1 sa).

**Çözüm.** `segs`'i çizim için koru; `downMinutes`'ı `[s,e]` aralıklarını başlangıca göre sıralayıp birleştirerek
hesapla (backend `unionMinutes`'ın JS karşılığı, `Math.min(to, …)` kırpması korunur); `otl.summary` kesinti sayısını
birleşik aralık sayısından ya da "N alarm" diye açık yazsın.

### O-5 · ORTA — `DomainCheckerService.java:333-346` (`daysUntil`) — yalnız-tarih WHOIS bitişi UTC gece yarısı damgasına çevrilip `floorDiv` ile bölünüyor → alan adı "kalan gün" günün büyük kısmında 1 eksik (bugün frontend'de kapatılan sınıfın backend ikizi)

**Bug.** RDAP saatli damga döner (`OffsetDateTime` dalı doğru); WHOIS/.tr web-whois çoğunlukla `yyyy-MM-dd` döner →
`LocalDate.parse(...).atStartOfDay(UTC)` → `floorDiv(when − now, 86 400 000)`. Bu bir ZAMAN DAMGASI farkı, takvim
günü farkı değil. Frontend'de bugün aynı sınıf düzeltildi (`utils/localDay.js:37-55`: *"yalnız tarih = YEREL TAKVİM
GÜNÜ farkı: bugüne planlanan 0, yarın 1 … eskiden UTC gece yarısına çevrilip floor alınıyordu"*); backend kardeş
dokunulmamış. Sertifika tarafı etkilenmez (`notAfter` gerçek damga).

**Neden bug — örnek.** Bitiş `2026-10-01` (WHOIS). 30 Eylül:
- 02:00 İstanbul (29 Eyl 23:00Z) → `when−now = 25 sa` → **1 gün** ✓
- 10:00 İstanbul (07:00Z) → `17 sa` → **0 gün** ✗ (doğrusu 1)
- 1 Ekim 10:00 İstanbul → `−7 sa` → **−1 ("1 gün önce doldu")** ✗ (bitiş günü daha bitmedi)

Sonuçları: `status` (`:149` `days <= criticalDays`) bir gün ERKEN kritik olur; `DomainExpiryWeekly`,
`TodayMonitorInsightsService.domains` (`<= 30`), `RenewalForecastService.domainExpiries`, KPI `criticalDomainCount`
hepsi bu `days_remaining`'i okur; alan adı bitiş hatırlatıcısı takvimden bir gün erken kayar.

**Çözüm.** Yalnız-tarih dalında `ChronoUnit.DAYS.between(LocalDate.now(IST), LocalDate.parse(iso.substring(0,10)))`
(kurum dilimi, `OrgCalendarDayGateTest` sınıfı); saatli damgada mevcut `floorDiv` kalsın. Kapı: 30 Eyl 10:00 IST +
"2026-10-01" → 1.

### D-1 · DÜŞÜK — `DbAnalyticsService.java:220` — 0 sorguda `success_rate = 100.0` ("veri yok = %100")

`total > 0 ? … : 100.0`. Aynı dosyanın `pct()` (`:630-632`) 0 paydada `null` döner, yorumu *"oran tanımsız =
bilinmiyor, '%0' değil"*. Örnek: 7 günde sorgu yok → API `success_rate: 100.0`; `DbKpiGrid.jsx:68` `?? 100` ile
yankılıyor (rozeti `noQ`'da gizlediği için ekranda görünmez, CSV/API tüketicisine gider). Çözüm: `null` +
`successTone(null) → 'muted'`.

### D-2 · DÜŞÜK — `DbAnalyticsService.java:88` ↔ `:591-600` ve `UserActivityService.java:83, 154` ↔ `:434-449` — kayan `since` ile takvim kovaları hizasız: özet toplamı ≠ seri toplamı

`AlertNoiseService.java:44-49` bu sınıfı kapattı (*"olaylar UTC kayan pencereden çekilirken kovalar LocalDate.now(IST)
ile kuruluyordu … total'e giriyor ama series'ten düşüyordu"*); iki kardeş süpürülmemiş.
Örnek (DbAnalytics, `days=7`, şimdi Salı 14:37 IST): `since = geçen Salı 14:37`; kovalar Çar…Salı (7 takvim günü);
geçen Salı 15:00–23:59 arasındaki 5 sorgu `summary.queries`'e girer, `buildSeries:447` `buckets.get(...)==null → continue`
ile seriden düşer → "7 gün: 5 sorgu" ama grafik toplamı 0. UserActivity: `logins_7d` (`:163`) ↔ `series.day`
(`:438-441`), `logins_24h` (`:167`) ↔ `series.hour` (`:442-445`) aynı asimetri. Çözüm: `since`'i ilk kovanın
başlangıcından türet (`bucketStarts(g, win).get(0)`).

### D-3 · DÜŞÜK — `ExecutiveStatsService.java:181-185` (`recentChanges.alerts_resolved`) — yalnız pencerede AÇILAN alarmların çözümü sayılıyor

Sorgu `findByCreatedAtGreaterThanEqual(since)`; `resolved++` yalnız bu kümede. Örnek `days=7`: 10 gün önce açılıp dün
çözülen alarm → "çözülen: 0". Çözüm: `findByResolvedAtGreaterThanEqual(since)` (var, `TodayMonitorInsightsService:515`
kullanıyor) ile ayrı sayım, `visible` süzgeci aynı.

### D-4 · DÜŞÜK — `StormService.java:326-328` — fırtına toplu tekrarı sabit 24 saat, bireysel tekrar ayardan

`EscalationService.reAlertDue(last, now(), 24)` sabit; bireysel yol `reAlertIntervalHours()` (`EscalationService:1411,
3192-3196`, `AlertThreshold.reAlertIntervalHours`). Yönetici 12 saat ayarlarsa tekil alarmlar 12 saatte, fırtına 24
saatte tekrarlar (yorum "bireysel re-alert'in aynası" der). Çözüm: aynı `thresholdRepo` okumasını StormService'e ver.

### D-5 · DÜŞÜK (latent) — `SchedulerService.java:2294-2297` (`nextCertificateSweepAt(domain, hours)`) — vade ileride ise cron UTC diliminde, değilse sunucu diliminde hesaplanıyor

`from = dueFrom.isAfter(now) ? dueFrom.atZone(ZoneOffset.UTC) : ZonedDateTime.now()`; `CronExpression.next(from)`
alanları `from`'un DİLİMİNDE değerlendirir; `@Scheduled(cron=…)` (`:1601`, zone yok) sunucu varsayılanında koşar.
Varsayılan cron saatlik (`0 0 * * * *`) olduğu için bugün görünmez. Örnek: `sweepCron="0 30 8 * * *"`, JVM
Europe/Istanbul, alan sıklığı 24 sa, son kontrol bugün 07:00 IST → `dueFrom = yarın 06:55 IST = 03:55Z` → UTC'de
sonraki tik `08:30Z = 11:30 IST`; gerçek süpürme 08:30 IST → kart 3 saat geç gösterir. Çözüm:
`dueFrom.atZone(ZoneId.systemDefault())`.

### D-6 · DÜŞÜK (kozmetik) — `UptimePage.jsx:367-368, 411-412` `${uptime_7d}%`, `PushLogView.jsx:299`, `SmtpLogView.jsx:315, 354` `%${success_rate}` — yüzde sırası/ondalık ayırıcı sabit

`dateLocale.formatPercent/formatRatePercent` (ISSUE-001/007/010 düzeltmesi) varken üç yüzey hâlâ elle yazıyor: TR
arayüzde "99.99%" (nokta + sağda), EN arayüzde "%97". Çözüm: `formatRatePercent(item.uptime_7d)` /
`formatPercent(kpi.success_rate)`.

### D-7 · DÜŞÜK — **AÇIK (baseline D-c11 + port-alarm-rca `resolved_at` notu)** — `WeeklyAvailabilityReportService.java:476-482`, `AlertNoiseService.java:90-96, 159`, `MonitoringWeeklyStatsService.java:105` — MTTR/çözülen sayımı sessiz kapanışı ve hayalet kapanışı gerçek çözüm sayıyor

`resolvedSilently` hiçbir rapor yolunda okunmuyor (yalnız `StormService.sendStormRecovery`); silinen/duraklatılan/
tür-bildirimi-kapatılan izlemenin alarmı "çözüldü" ve süresi MTTR'a girer. Ek olarak takılı kurtarma (K2) sonrası
uzlaştırmayla kapanan hayalet alarmın `resolved_at`'i KAPANIŞ ANI'dır (gerçek iyileşme anı değil) → geçmiş süre
geriye dönük düzelmez. Örnek: Pzt 09:00 açılan alarm, hedef 09:30'da düzeldi, zincir takıldı, Çar 09:00'da uzlaştırma
kapattı → `durationMin = 2880`; haftanın diğer 3 alarmı 30'ar dk → MTTR `(2880+90)/4 = 742 dk` (gerçek 30).
`IncidentService.mttr_minutes` (`:159-169`) elle girilen olay kayıtlarından hesaplanır — bu bulgudan bağımsız, doğru.
Çözüm (D-c11 ile aynı): `resolved_silently = true` satırları MTTR/çözülen paydasından çıkar, "kapatıldı" kovasına al;
hayalet süreleri için geçmiş düzeltilemez, rapora dipnot ("otomatik uzlaştırmayla kapananlar dahil").

### D-8 · DÜŞÜK (latent) — `WeeklyReportKpiService.java:195-211` (`expiringInWindow`/`renewedInWindow`) — geçmiş haftalar için BUGÜNKÜ `latest_checks` kullanılıyor

`trend8w` ve `previous` KPI'sı `checks` (şu anki son kontrol) üzerinden hesaplanıyor: yenilenen sertifikanın eski
`notAfter`'ı kaybolduğu için "o hafta süresi dolan" geçmişe gidildikçe sıfıra yaklaşır (yenileme yapıldıysa hafta
dolmuş görünmez). Yorum bilinçli ("checks bellekte filtrelenir"); rapor okuyucusu için yanıltıcı olabilir.
Çözüm (isteğe bağlı): `certificate_checks` domain×parmak izi grupları (`RenewalForecastService.renewals` sorgusu)
ile o haftanın gerçek `not_after`'ını kullan.

---

## ÇÖZÜLMÜŞ ✓ (baseline RE-CHECK)

| Baseline | Durum | Kanıt |
|---|---|---|
| 2026-09-23 **Y6** `FailedLoginAnomalyService` tamsayı bölmesi (`143/144 = 0`) | ÇÖZÜLMÜŞ ✓ | `:186` `(double) baselineTotal / buckets`, `:150` kapı `baselineAvg > 0`, rapor 1 ondalık `fmt1` |
| 2026-09-23 **Y7** `MonitoringWeeklyStatsService` ağırlıklı ortalama paydası ölçümsüz satırları sayıyor | ÇÖZÜLMÜŞ ✓ | `:289-302` beşinci kolon `measured`, `:318` `weightedMs / measured` |
| BUG_RAPORU_5 **#5** push teslimat damgaları İstanbul yerel (+3 sa) | ÇÖZÜLMÜŞ ✓ | `UserPushService`'te `atZone(ZONE).toLocalDateTime` kalmadı (0 eşleşme) |
| 2026-09-25 O-5 / R6 yüzde tavanı (backend) | ÇÖZÜLMÜŞ ✓ (backend) — frontend ikizi **O-2** | `AvailabilityMath` + 6 çağıran |
| 2026-09-28 Ek 3/1 yalnız-tarih gün hesabı (`inventoryModel.daysUntil`, `inventoryDetailModel.daysFromNow`) | ÇÖZÜLMÜŞ ✓ — kardeşler: `forecastModel.dayDiff` yerel gün ✓, `RenewalForecastService.renewBy/localDay` IST ✓, `collectDomainExpiry` IST ✓, `WeeklyReportKpiService.criticalDomainCount` IST ✓; **backend ikizi `DomainCheckerService.daysUntil` AÇIK → O-5** | `utils/localDay.js:45-58` |
| 2026-09-29c **D-c11** raporlar `resolvedSilently` okumuyor | **AÇIK** → D-7 | `WeeklyAvailabilityReportService:478`, `AlertNoiseService:91` |
| port-alarm-rca "hayalet alarm `resolved_at` = kapanış anı" | **AÇIK (tasarım gereği, geçmiş düzeltilemez)** → D-7 dipnot | `WeeklyOutageReportService.toRow:270-275`, `OutageTimeline:62` |

---

## Doğru bulunan (tarandı, temiz — yanlış-pozitif üretilmedi)

**Yüzde / bölme / percentile.** `AvailabilityMath.pct` (0 payda → null, hata varken `100−10^-n` tavanı, ondalık
kırpma 0..4); `WeeklyAvailabilityReportService.computeRow` (bakım satırı kesinti süresini dondurur ama kesinti
kimliğini korur — M2/M2-COUNT; `percentile` nearest-rank boş listede null; `summarize` ortalama 99,99 tavanı;
`previousWeekAvg` aynı çekirdek); `WeeklyOutageReportService` (`durationMin` negatifte 0, `weekDurationMin` pencereye
kırpılı, `unionMinutes` bitişik/çakışan birleştirme, `avgAvailability` tavan, delta iki tarafta da "o hafta açılan",
dağılımlar yalnız `openedRows`); `HttpMetricsQueryService.percentile` (kova-içi interpolasyon, `total==0 → 0`,
`frac` 0..1 kırpma) — yanlış-pozitif listesi ✓; `HttpMetricsAggregate.Agg` (`minMs` yalnız `count>0`, `errorRatePct`
1 ondalık); `DbAnalyticsService.pct`/`percentile` (0 → null); `MonitorSparklineService.pct` (2 ondalık O-5);
`ExecutiveStatsService.health_pct` (0 → null; `last30/prev30` ayrımı `>= since30`); `AlertNoiseService`
(`share_pct`, `resolved_pct`, `per_day_avg` — `d ≥ 1`); `StormService.computeThreshold` (PERCENT `ceil` + taban 3,
COUNT taban 2, histerezis `(t+1)/2`, pencere 1–15 dk kırpma, `distinctTargets` host normalizasyonu);
`CertificateService.computeStats` (O1a/O1b alt sınırları `d >= 0`); `MonitoringController.buildResponseSeries`
(p95 `ceil(0.95n)−1`, n=1 güvenli, `down_total`, `capped`); frontend `responseChartModel` (`nearestRank`, ağırlıklı
ortalama, `p95Peak` etiketi, `formatAvailability` kırpması, boşluk noktası 75. yüzdelik adım), `statsModel`
(`bucketOf` sınırları, KPI kümülatif, `distribution` yalnız bilinenler, `kpiCounts.avgDays`), `forecastModel`
(`classify` `<=`, `dayDiff` yerel gün, `dailySeries` `diff < days`), `forecastUi.horizonBuckets` (haftalık/aylık
kovalar kapalı aralık), `dbModel` (`deadSummary`, `connModel.pct` 0 payda null, `buildChart.errPct`),
`httpMetricsModel` (`downsample` dakika ortalaması + `errPeak`, `breaches` seyreltme ÖNCESİ, `unclassifiedOf`),
`certDetailsModel.lifetimeOf` (`usedPct` 0..100 kırpma, `notYetValid`), `certHistoryModel` (`detectRenewals` koşu
birleştirme, `daysDomain` pay, `latestDays` kesin/kova ayrımı), `DensityStrip` (`bucketBounds` 16/13/10, `failRuns`
1 sn bitişiklik), `directoryModel.timeoutUsedPct` (0 payda null), `MonitoringWeeklyStatsService.buildStats` oran/delta
(`round1`).

**Teyit / kurtarma / tekrar.** `MonitoringOutageService`: teyit `n < effAttempts` → 3 deneme (3→3) ✓, kurtarma
`done = n+1 >= required` (tetikleyici + 2 re-check) ✓, elle kontrol aralığı O-1 (`gap`), takılı zincir bekçisi
`required×interval + 15 dk`, `skipped` kanıt sayılmaz, kuşak numarası global; `EscalationService.reAlertDue` (rolling
saat, ayrıştırılamazsa izin), `initialNotificationMissing` (5 dk pay), `determineAlertLevel` (`<= crit/high/warn`,
null eşikte varsayılan), sertifika yolu `unverified` koruması, `sendLevel` olay seviyesinin altına inmez;
`CertificateHealthRules.expiryStatus` (`<0` FAIL, `<= crit` FAIL, `<= warn` WARN) ✓; `DomainCheckerService.status`
(`days<0 || days<=crit`) eşik yönü ✓ (yalnız gün hesabı O-5).

**Zaman pencereleri / dilim.** `WeeklyAvailabilityReportService.windowForOffset/windowForMonday/mondayOfIsoWeek`
(ISO hafta, `LocalDate.of(y,1,4)` yıl sınırı, Pzt 00:00–Paz 23:59:59 IST → UTC ISO, `offset=0` → şimdiye kadar,
`shiftWeek` saniyeli biçim); `collectDeployments` `stripZ` karşılaştırması; `collectDomainExpiry` bugün IST;
`MonitoringWeeklyStatsService.compute` 8 hafta tavanı, `activeAsOf`; `WeeklyReportKpiService.weekRange`,
`scoreInputsFor` as-of-hafta-sonu, `criticalDomainCount` `0..7`; `UserActivityService` ısı haritaları Pazartesi
hizalı 4 hafta (`[from,to)`), `idle_sec`/`expires_in_sec`, `inactivitySeconds` 1..1440 dk kırpma;
`TodayMonitorInsightsService` (`slow` `b[0] <= 0` koruması + ≥1,5× VE ≥100 ms, `stale` `max(2×aralık, 300 sn)` +
`never`, `paused_days`, `exceptions` `[today, today+7]`, `renewedSince` `[since, now]`); `AlertNoiseService` `since`
kovalara hizalı (`:44-49`); `RetentionService.effectiveDays` (`max(minDays, v)`, `zeroMeansNever`), `cutoffFor`
DATE10/DATE13 kesimleri leksikografik doğru; `MaintenanceService` NONE penceresi yarı-açık `[start, end)`, ≤24 sa
gece yarısı geçişi, MONTHLY `min(dom, ayUzunluğu)`, DST `ZonedDateTime.of` — yanlış-pozitif listesi ✓ (yalnız >24 sa
tekrar Y-1); `SchedulerService.checkDue` GRID (`nextDueAfter`, ilk görüşte hemen, vade kayması yok), `jitteredInterval`
(`≤ base/4, ≤ 6 sa`), `nextCertificateSweepAt()` cron ile aynı dilim (yalnız alan-başına dalı D-5); haftalık
erişilebilirlik `@Scheduled(zone="Europe/Istanbul")` ↔ `schedulerHealth.next_run` IST ✓; `HttpMetricsQueryService`
`Range.of` (31 gün kırpma + `clamped`, `from > to → 400`), `bucketKeysInRange` IST + `capped` bayrağı, `MAX_MINUTE_HOURS`
48 sa; `DbAnalyticsService.bucketStart` IST; frontend `localDay.daysFromToday` (yalnız tarih = `Date.UTC` bileşen farkı,
DST-güvenli; damga = `floor`), `localDayKey`, `alertHistoryModel.dayStart/dayEnd` (yerel gün → UTC), `groupByDay`,
`quickRange`, `iso24hAgo`, `incidentMeta.parseUtc/autoDurationMinutes`, `directoryModel.loginBucket` ↔
`uactModel.loginStatus` tutarlı (`<1/<7/<30`), `whoNotifiedModel` (sayısal hesap yok).

**Göreli metin / tekil-çoğul.** `dayPhrases.js` (`n === 1` ayrımı), `statsUi.relativeDays`/`forecastUi.relativeDays`
(negatif → "önce doldu", 0 → "bugün", 1 → tekil), `OutageTimeline.formatDuration` (en az "1 dk"; 0 dakikada da),
`incidentMeta.formatDuration` (negatif → "—"), `certDetailsModel.relativeUnit` (gün/ay/yıl eşikleri).

**`Number.isFinite` / null korumaları.** `httpMetricsModel.num/cnt`, `dbModel.isNum`, `responseChartModel.num`,
`certHistoryModel.num`, `directoryModel.ts` (bozuk → 0), `alertHistoryModel.alertExpiryIso` (bozuk → null) ✓.

---

## Bilgi amaçlı (bulgu sayılmadı)

- `HttpMetricsQueryService.resolveGranularity:454` `minutes / 60 > 24` tamsayı bölmesi: 24 sa 59 dk hâlâ "minute"
  (1499 kova) — tavanın (48 sa) çok altında, etkisiz.
- `WeeklyOutageReportService.assemble:386` `totalDowntimeMin` çakışan alarmları ayrı sayar — yorum ve e-posta bunu
  açıkça yazıyor ("çakışanlar ayrı sayılır"); zaman çizelgesi satırı birleşimle. Bilinçli.
- `EscalationService:449, 452, 1422` `lastAlertTime.substring(0,10)` — damga her zaman 19 karakter; bozuk damgada
  `reAlertDue` zaten `true` döner ama `substring` log satırında düşer. Latent, yalnız bozuk veriyle.

---

## Önerilen düzeltme sırası

1. **Y-1** bakım penceresi >24 sa (`MaintenanceService` iki metot + kapı testi) — yanlış alarm + yanlış erişilebilirlik; küçük, izole.
2. **O-5** `DomainCheckerService.daysUntil` yalnız-tarih → IST takvim günü — bugünkü frontend düzeltmesinin backend ikizi; 3 satır.
3. **O-2** frontend "%100 + hata" (`formatRatePercent(r, dash, failed)` + 3 çağıran) — R6 sınıfının kalan ikizi.
4. **O-1** `uptime_7d/30d` veri yokken `null` — O-5 (2026-09-25) süpürmesinin atlanan yeri.
5. **O-3** haftalık gösterge `TopTarget.alarms` anahtar uyumu — rapor/PDF/e-posta üçünü birden düzeltir.
6. **O-4** `summarizeOutages` birleşim — Y-1 paylaşımlı anahtar kararının doğal sonucu.
7. **D-2, D-3, D-1** özet ↔ seri hizası ve 0-payda `null` (aynı sınıf, tek PR).
8. **D-7 (D-c11)** MTTR'da `resolved_silently` kovası; D-4, D-5, D-6 fırsat buldukça.

| # | Önem | Dosya:satır | Sınıf |
|---|---|---|---|
| Y-1 | YÜKSEK | `MaintenanceService.java:106-111, 140-144` | tekrarlayan pencere > 24 sa tanınmıyor → yanlış alarm |
| O-1 | ORTA | `MonitoringController.java:1344-1345, 1387-1390` | veri yok = %100 |
| O-2 | ORTA | `CheckHistoryTab.jsx:306-321` · `dateLocale.js:51` · `certHistoryModel.js:165-172` · `OutageTimeline.jsx:66-68,144` | hata varken "%100" (R6 frontend ikizi) |
| O-3 | ORTA | `MonitoringWeeklyStatsService.java:305-307 ↔ 344-351` | ad ↔ hedef anahtar uyumsuzluğu → alarm 0 |
| O-4 | ORTA | `OutageTimeline.jsx:60-68` | çakışan segment çift sayım |
| O-5 | ORTA | `DomainCheckerService.java:333-346` | yalnız-tarih gün hesabı 1 eksik (backend ikizi) |
| D-1 | DÜŞÜK | `DbAnalyticsService.java:220` | 0 sorguda %100 |
| D-2 | DÜŞÜK | `DbAnalyticsService.java:88 ↔ 591-600` · `UserActivityService.java:83,154 ↔ 434-449` | özet ≠ seri (kova hizası) |
| D-3 | DÜŞÜK | `ExecutiveStatsService.java:181-185` | çözülen sayımı eksik |
| D-4 | DÜŞÜK | `StormService.java:326-328` | tekrar aralığı paritesi |
| D-5 | DÜŞÜK | `SchedulerService.java:2294-2297` | cron dilimi tutarsız (latent) |
| D-6 | DÜŞÜK | `UptimePage.jsx:367-368,411-412` · `PushLogView.jsx:299` · `SmtpLogView.jsx:315,354` | yüzde biçimi sabit |
| D-7 | DÜŞÜK (AÇIK) | `WeeklyAvailabilityReportService.java:476-482` · `AlertNoiseService.java:90-96,159` · `MonitoringWeeklyStatsService.java:105` | MTTR sessiz/hayalet kapanış |
| D-8 | DÜŞÜK (latent) | `WeeklyReportKpiService.java:195-211` | geçmiş hafta için güncel `notAfter` |

---

## Okundu ✓

**Backend:** `util/AvailabilityMath` · `WeeklyScoreCalculator` · `WeeklyAvailabilityReportService` (tam) ·
`report/WeeklyOutageReportService` (tam) · `MonitoringWeeklyStatsService` (tam) · `HttpMetricsQueryService` (tam) ·
`HttpMetricsAggregate` (tam) · `DbAnalyticsService` (tam) · `MonitoringOutageService` (tam) · `EscalationService`
(`:296-458` sertifika yolu, `:786-846` catch-up, `:1370-1428` izleme tekrar yolu, `:2100-2140` `determineAlertLevel`,
`:3140-3212` `reAlertDue`/`initialNotificationMissing`/`reAlertIntervalHours`) · `DomainCheckerService` (tam) ·
`CertificateHealthRules` (tam) · `MaintenanceService` (tam) · `SchedulerService` (`:1601-1637` süpürmeler,
`:2258-2342` next-run, `:2586-2720` `checkDue`/grid, `:5296-5315` jitter, tüm `SweepItem` üreticileri,
`@Scheduled` cron/zone'ları) · `retention/RetentionService` (`:60-104`) · `UserActivityService` (`:75-185`,
`:320-349`, `:420-495`, `:720-819`, `:940-997`) · `StormService` (`:120-180`, `:200-330`, `:420-550`) ·
`ForecastController` (tam) · `RenewalForecastService` (tam) · `TodayMonitorInsightsService` (tam) ·
`IncidentsController` (MTTR yok — `IncidentService:150-184` + `IncidentRecordRepository:163-176`) ·
`AdminOverviewService` (tam) · `AlertNoiseService` (tam) · `MonitorSparklineService` (tam) · `ExecutiveStatsService`
(tam) · `AlertTeamStatsService` (tam) · `WeeklyReportKpiService` (tam) · `CertificateService.computeStats`
(`:896-965`) · `MonitoringController` (`:1318-1402`, `:3036-3100`) · `UptimeCheckRepository` (`:60-98`) ·
`AlertEventRepository.findOverlappingForHistory` · `CheckHistoryService.execute` (`:136-202`) ·
`FailedLoginAnomalyService` (`:150-186`, RE-CHECK) · `MaintenanceController` (`:287`).

**Frontend:** `utils/localDay.js` · `utils/dayPhrases.js` · `i18n/dateLocale.js` · `utils/alertKinds.js` ·
`utils/incidentMeta.js` · `admin/alerts/alertHistoryModel.js` · `history/OutageTimeline.jsx` ·
`history/DensityStrip.jsx` · `history/CheckHistoryTab.jsx` (`:286-330`) · `history/useCheckHistory.js` (alerts) ·
`certmodal/certHistoryModel.js` · `certmodal/certDetailsModel.js` · `inventory/inventoryDetailModel.js` ·
`inventory/inventoryModel.js` · `admin/httpmetrics/httpMetricsModel.js` · `admin/dbanalytics/dbModel.js` ·
`admin/useractivity/directoryModel.js` · `admin/useractivity/uactModel.js` (`:1-80`) · `stats/statsUi.jsx` ·
`stats/statsModel.js` · `pages/forecast/forecastUi.jsx` · `pages/forecastModel.js` · `ResponseTimeChart.jsx` ·
`responsechart/responseChartModel.js` · `admin/whonotified/whoNotifiedModel.js` · `maintenance/MaintenanceEditor.jsx`
(`:25-151`) · `UptimePage.jsx` (uptime alanları).

**Baseline / bağlam:** `.claude/commands/bug-denetle.md` · `BUG_RAPORU_2026-09-23.md` (Y6–Y11, "Doğru bulunan
Sayısal", kapanış) · `BUG_RAPORU_7.md` · `BUG_RAPORU_5.md` (#5, sayısal maddeler) · `BUG_REGRESYON_2026-09-29c.md`
(D-c11, soru 1–3) · `port-alarm-rca/PROD_DIAGNOSIS.md` (K2) · bellek notları: cert-monitor-test-timezone-trap,
test-fixed-date-time-bomb, cert-monitor-raw-detail-over-rollup, dogrulamadan-varsayma, prod-log-as-defect-source.

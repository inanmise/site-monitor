# SiteMonitor — Kod Seviyesi Bug Raporu (Üçüncü Tur)

**Tarih:** 2026-08-29 · **Kapsam:** sayısal/istatistik hesap doğruluğu, zaman-penceresi/sayaç mantığı, React state/efekt/render (yarış-dışı)
**İlişki:** `BUG_RAPORU.md` (tur 1: IDOR/alarm) ve `BUG_RAPORU_2.md` (tur 2: SSRF/yetki/sızıntı)'ya **EK**tir. Önceki iki turun eksenleri tekrar edilmedi; bu tur **hesap doğruluğu ve React state/render** eksenlerine odaklandı.

> **Genel:** İki turdan sonra bu tur, önceki bulguların dışında **yalnız birkaç** yeni kusur çıkardı — kod tabanı istatistik/zaman-penceresi tarafında da olağanüstü doğru (percentile interpolasyonu, teyit/kurtarma sayacı, bakım penceresi gece-yarısı geçişi, reAlertDue, cron next-run, availability ≤100 clamp'i hepsi doğru bulundu). Bu turun bulguları **kullanıcı-görünür doğruluk** sınıfında; güvenlik/veri-kaybı yok. Tümü kaynak üzerinde doğrulandı.

---

## YÜKSEK

### Y9 — "Değişiklik Geçmişi" sekmesinde sayfalama bir-kaydırmalı (navigasyon bozuk)
`frontend/src/components/history/ChangeHistoryTab.jsx:190,196` (state :29)

`page` state 0-tabanlı (`useState(0)`; API'ye `{page}` 0-tabanlı gidiyor, `rangeStart={page*size+1}` bunu doğruluyor) — ama **1-tabanlı** `PaginationBar`'a dönüşümsüz veriliyor: `page={page}` + `onPageChange={setPage}`. `PaginationBar` 1-tabanlıdır (`disabled={page <= 1}` ilk/önceki, `disabled={page >= totalPages}` sonraki/son, `clamp = min(max(1,p), totalPages)`). Sonuç:

- İlk açılışta (state=0) hiçbir sayfa numarası aktif görünmez; "1" düğmesi API'nin **2. sayfasına** gider (`go(1)→setPage(1)→page=1`).
- "Sonraki" son sayfanın **bir ötesindeki boş sayfaya** ulaşır; "Son"da boş liste.
- Sayfa numaraları bir kaydık; kullanıcı beklediği kayda gidemez.

Bu widget **9 izleme türünün** "Değişiklik Geçmişi" sekmesinde (DnsDetailModal dahil) ortak kullanıldığından hepsini etkiler. **Kanıt:** aynı 0-tabanlı sunucu-sayfalamasını kullanan tüm kardeşler dönüşümü yapıyor — `MyAuditLog` (`page={page+1}`, `onPageChange={p=>loadLogs(p-1)}`), `DeviceHistoryPanel`, `IncidentHistoryPage`, `AuditLogViewer`, `UserPushSettings`, `LoginIssueReports`; `CheckHistoryTab` ise 1-tabanlı `h.page` veriyor. Yalnız `ChangeHistoryTab` sapmış.

**Çözüm:** `rangeStart`/`rangeEnd` zaten 0-tabanlı doğru — sadece iki satırı düzelt:
```jsx
page={page + 1}
onPageChange={(p) => setPage(p - 1)}
```
Regresyon testi: 3 sayfalık veride "2" tıkla → API `page=1` çağrılsın; ilk sayfada "önceki" kapalı, ikinci sayfada açık.

---

## ORTA

### O15 — Sistem Sağlığı'nda SMTP teslim oranı tamsayı bölmesiyle yanlış (ondalık kaybı, alarmla çelişki)
`backend/.../service/ExtendedHealthService.java:329`

```java
long rateRounded = Math.round(rate * 10) / 10L;   // 96.7 → 967 / 10L = 96  (0,7 kayıp)
```
`Math.round(...)` `long` döner, `/ 10L` **tamsayı bölmesidir** → tek-ondalık niyeti (`*10 … /10`) atılır. Panoda 96,7 yerine **96** görünür. Dahası alarm ham `rate < 95.0` üzerinden hesaplandığından, `rate=94.96` iken pano `Math.round(949.6)=950/10=95` gösterir (sağlıklı gibi) ama **alarm AÇIK** olur → ekran/alarm çelişkisi. Kodun her yerinde doğru biçim `/10.0` (`HttpMetricsService.getSummary` error_rate_pct, `DbAnalyticsService.buildSummary` success_rate, `MonitoringWeeklyStatsService.round1`) — bu tek istisna.

**Çözüm:** `double rateRounded = Math.round(rate * 10) / 10.0;` (alan tipi `double`/`Object`). Alarm zaten ham `rate` kullandığından değişmez.

### O16 — DNS ve Port izleme sayfalarında istatistik kartları takım/grup/arama filtresini yok sayıyor
`frontend/src/components/DnsMonitorPage.jsx:276-283`, `PortMonitorPage.jsx:281-288`

`dnsCounts`/`portCounts` ham `monitors` (tam liste) üzerinden hesaplanıyor ve `total: monitors.length` veriliyor; ama alttaki liste takım+grup+arama ile süzülmüş. Kullanıcı bir takım seçince/arama yazınca liste daralır fakat üstteki kartlar (ve "Toplam") **küresel** sayıyı göstermeye devam eder. Kartlar aynı zamanda filtre görevi gördüğünden ("alarm 5" tıkla → yalnız 2 sonuç gelir) tutarsız. Diğer **6** sayfa (HTTP/Ping/Domain/Page/PageSpeed/Scripted) sayaçları `scoped` (filtreli) listeden hesaplıyor — bu iki sayfa sapmış.

**Çözüm:** HTTP sayfasındaki deseni uygula — `const scoped = useMemo(() => monitors.filter(takım+grup+arama), [...])`; sayaçları ve `total`'i `scoped` üzerinden hesapla, `MonitorStatsSection total={scoped.length}`.

---

## DÜŞÜK

- **D19 — `MetricsService.java:73` ilk örneklemede sahte GC-duraklama sıçraması.** `gc_delta_ms = gcNow - lastGcMs`, `lastGcMs` başlangıçta 0 → ilk örnek "dakikalık delta" yerine **JVM ömrü boyunca birikmiş** toplam GC süresini gösterir; grafiğin ilk noktası yanıltıcı sıçrar. **Çözüm:** ctor'da/ilk turda `lastGcMs = gcNow` ile başlat, ilk noktayı 0/atla. *(doğrulanmalı)*
- **D20 — `history/useCheckHistory.js:122` CSV href'i `days=custom` üretip backend 500'e yol açabiliyor.** `load()` "custom + uygulanmamış" durumunu koruyor (yorumda "days='custom' → 500" notu var) ama `csvParams` ham `days: preset` veriyor. Kullanıcı "Özel Aralık"ı seçip tarih uygulamadan (önceki preset'ten `total>0` kaldıysa) CSV'ye basarsa `?days=custom` → 500. **Çözüm:** csvParams'ta da `days`'i yalnız `Number.isFinite(Number(preset))` ise koy; aksi halde atla.
- **D21 — `ScriptedMonitorPage.jsx:1620` env değişkeni satırları `key={i}` (index) ile render ediliyor.** Ortadaki satır silinince React index'e göre uzlaştırır; girişler kontrollü olduğundan değer bozulmaz ama **odak yanlış satıra sıçrayabilir**. Proje genelinde reorderable listeler kararlı `m.id` kullanırken bu sapma. **Çözüm:** ekleme anında üretilen kararlı `_id` + `key={e._id}`. *(doğrulanmalı)*
- **D22 — `admin/InventoryManager.jsx:67` türetilmiş-state senkronsuzluğu.** `useState(teamsProp)` prop'u state'e kopyalıyor; sync effect'i yalnız `canManage` iken ve bir kez çalışıyor. AUDIT/USER'da `teams` ilk prop değerinde donar; parent prop'u sonradan güncellerse senkron olmaz. Oturum içinde takımlar seyrek değiştiğinden etki sınırlı. **Çözüm:** `useEffect(() => setTeams(teamsProp), [teamsProp])`. *(doğrulanmalı)*

---

## Doğru bulunan (yanlış pozitif üretmemek için)

Bu turda özellikle tarandı ve **kusursuz** bulundu: `HttpMetricsQueryService.percentile` (kümülatif + kova-içi lineer interpolasyon, overflow kovası, `total==0` koruması); `CheckHistoryService` histogram/bounds prefix sınırları; `WeeklyAvailabilityReportService` (nearest-rank percentile, bakım-farkında kesinti sayımı, `pct>=100 && up<total → 99.99`); `MonitoringOutageService` teyit (effAttempts=3 → tam 3 re-check) ve kurtarma (required=3 → tetikleyici+2) sayaçları; `EscalationService.reAlertDue` (rolling-saat) ve eşik kıyasları; `MaintenanceService` occurrence motoru (yarı-açık aralık, gece-yarısı geçişi, MONTHLY `min(dom, lengthOfMonth)`); cron next-run; `PublicStatsController` availability ≤100; React tarafında `useVisibleInterval`/`useRunningChecks`/`usePagination`/`Toast`/`CopyButton` timer temizlikleri ve `ResponseTimeChart`/`CheckHistoryTab` seq guard'ları.

---

## Üç turun birleşik önem tablosu (özet)

| # | Önem | Bulgu | Dosya | Tur |
|---|---|---|---|---|
| Y1 | YÜKSEK | `/dns/{id}/details` IDOR (auth yok) | MonitoringController:1695 | 1 |
| Y2 | YÜKSEK | `/domain/{id}/registration` IDOR + live yazma | :4386 | 1 |
| Y3 | YÜKSEK | port/dns response-series IDOR | :2077,2088 | 1 |
| Y4 | YÜKSEK | storm alıcı aşırı-erişimi (müdürlere) | StormService:576 | 1 |
| Y5 | YÜKSEK | eskalasyon seviyesi kalıcılaşmıyor → çözüm eksik | EscalationService:854 | 1 |
| Y6 | YÜKSEK | AUDIT rolü notification.groups EDIT kazanıyor | PermissionCatalog:36 | 2 |
| Y7 | YÜKSEK | gzip decompression bomb → OOM | PageFetchCore:270 | 2 |
| Y8 | YÜKSEK | keyword/http/hsts redirect SSRF → metadata | KeywordCheckerService:49 | 2 |
| Y9 | YÜKSEK | Değişiklik Geçmişi sayfalaması bir-kaydık | ChangeHistoryTab:190 | 3 |
| O1 | ORTA | cert sayaç/liste sınır tutarsızlığı | CertificateService:637 | 1 |
| O9 | ORTA | incident transfer kapsam kontrolü yok | IncidentController:178 | 2 |
| O12/O13 | ORTA | webhook/uptime SsrfGuard boşluğu | WebhookService/Uptime | 2 |
| O14 | ORTA | UserPushService HttpClient sızıntısı | UserPushService:380 | 2 |
| O15 | ORTA | SMTP oranı tamsayı bölmesi | ExtendedHealthService:329 | 3 |
| O16 | ORTA | DNS/Port stat kartları filtresiz | DnsMonitorPage:277 | 3 |

*(O2–O8, D-serisi maddeler ilgili tur raporlarında.)*

## Önerilen sıra (güncel)

Güvenlik önce: **Y6** (tek satır bölme), **Y8/O10/O11** (ortak güvenli-redirect yardımcısı), **Y7** (decode tavanı), **O12/O13** (SsrfGuard). Sonra doğruluk: **Y1–Y3** (IDOR üçlüsü — mekanik), **Y4/Y5** (alarm), **Y9** (iki satır) + **O15/O16** (ucuz UI düzeltmeleri). Her düzeltme mevcut testleri değiştirmeden yeni testle yapılmalı.

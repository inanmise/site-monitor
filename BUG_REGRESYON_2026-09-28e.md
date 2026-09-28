# BUG REGRESYON — 2026-09-28e (20.90.0 öncesi, ön uç "Doğrulanamadı" kapsamı)

Kapsam: `BUG_REGRESYON_2026-09-28c.md` → "Doğrulanamadı" bölümündeki ön uç modüller + görevde sayılan paylaşılan bileşenler,
`main` @ `bb0d42c8` (v20.89.0) üzerindeki commit'lenmemiş çalışma ağacı (`git status` / `git diff`). SALT OKUNUR: kod
değiştirilmedi; derleme / vitest / Playwright / sunucu koşturulmadı; alt ajan başlatılmadı. Grep eşleşmesi tek başına bulgu
sayılmadı — aşağıdaki her madde kaynak (gerekirse arka uç karşılığı da) okunarak doğrulandı.

**Tekrar yok kuralı.** Tur sırasında koordinatör iki ek rapor bildirdi: `BUG_REGRESYON_2026-09-28c-ek.md` (1–10: httpmetrics /
dbanalytics / useractivity; Ek 2: zaman–tel biçimi–i18n–CSS; Ek 3: sertifika penceresi ve bağlı yüzeyler). Oradaki bulgular
burada TEKRARLANMADI. Aşağıdakilerin hepsi **Ek 1–3'te OLMAYAN** yeni bulgulardır. (Bağımsız olarak aynı sonuca vardığım bir
madde: Ek 3 #4 "Envanterde aç → `App.jsx:395` `setSearch(domain)`" — kaynakta teyit ettim, yeniden listelenmedi.)

---

## Yeni bulgular (Ek 1–3'te olmayanlar) — 0 KRİTİK · 0 YÜKSEK · 2 ORTA · 3 DÜŞÜK (+ 2 not)

### ORTA

**E1 · `components/history/CheckHistoryTab.jsx:227-238` (+ `UptimePage.jsx:444-466`, `certmodal/CertCheckHistory.jsx:13`) — kontrollü aralıkta (Uptime detayı) SAHTE "saklama süresi nedeniyle kırpıldı" bandı**
(yanlış bilgi / "bilinmiyor ≠ sorun yok" sınıfı; kod önceden var — kontrollü aralık özelliğinden beri — bu diff SSL sütununu `CertCheckHistory` (ön ayar 7) ile değiştirerek bandı kalıcı hâle getirdi)

- **Bug:** `clampedFrom`, sunucunun döndürdüğü `range.from`'u İÇ ÖN AYARDAN türetilen `requested = şimdi − preset gün` ile
  karşılaştırır; `fixedMode` (kontrollü aralık) için hiçbir koruma yok. Kontrollü aralıkta istek ön ayarla değil sabit `from/to`
  ile gider (`useCheckHistory` `fixed`), sunucu `range.from`'u o `from` olarak döndürür (`CheckHistoryService.resolve:88-97` —
  saklama kırpması yoksa aynen) ve `h.preset` kullanılmadığı hâlde varsayılan değerinde kalır.
- **Neden bug:** Uptime detayı her açılışta aralığı "bugün 00:00 → şimdi" yapar (`UptimePage.jsx:112-116`). SSL sütunu
  (`CertCheckHistory`, ön ayar 7): `range.from` (bugün 00:00) > `şimdi − 7 gün` + 2 sa → her açılışta sarı uyarı bandı
  **"Saklama süresi nedeniyle kayıtlar <bugün 00:00> tarihinden itibaren gösteriliyor"**; ~7 günden kısa her aralık seçiminde
  de aynı. HTTP sütunu (ön ayar 1): yerel saat 22:00'den önce aynı sahte band. Hiçbir şey kırpılmadı — kullanıcı eski
  kayıtların saklama yüzünden silindiğini sanır. `UptimePage.sslHistory.test.jsx:53` zarfı `range` taşıdığı için band test
  DOM'unda da çiziliyor ama yokluğu iddia edilmiyor (yeşil süit, yanlış ekran).
- **Çözüm:** `clampedFrom`'da kontrollü aralıkta istenen ucu `range.from` prop'undan al (`fixed.from` → `toUtcIso`), ön ayar
  dalına hiç girme: `if (fixedMode) requested = toUtcIso(range.from)`. Kapı testi: `range={{from: şimdi−6 sa, to: şimdi}}` +
  zarf `range.from` = aynı uç → `hist.clampedNotice` YOK; `range.from` saklama sınırına kırpılmışsa VAR.

**E2 · `components/history/OutageTimeline.jsx:85, 162, 173` + `components/history/CheckHistoryTab.jsx:91` — "Alarm geçmişinde aç" derin bağlantısı `{ incident: id }` gönderiyor; Alarm Geçmişi yalnız `alert` okuyor → hedef alarm HİÇ açılmıyor**
(URL ad alanı / derin bağlantı sınıfı; önceden var — 2026-09-12 #12 kesinti çizelgesinden beri — bu diff dokunmatik listede
(`TimelineTouchList`) aynı hatayı yeniden üretti ve metinle vaat etti: `otl.touchHint` "Tap an alert to open it in the alert history")

- **Bug:** Dört eylem de `navigateTo('alerthistory', { incident: <id> })` çağırır: kesinti segmenti, uyarı çentiği, yeni
  dokunmatik liste satırı, Kontrol Geçmişi'ndeki alarm olay kartının "Alarm geçmişinde aç" düğmesi. `AlertHistory` derin
  bağlantıyı yalnız `alert` anahtarından okur (`AlertHistory.jsx:98` `readUrlParam('alert')`, `:223-236` `apply({ alert, view,
  type, q })`); `incident` anahtarını yalnız Olaylar (`IncidentsPage.jsx:157`) ve Olay & Hata Geçmişi (`LEGACY_DETAIL_KEY`)
  okur. Ayrıca alarm kapalıysa `view=closed` da gerekir — derin bağlantı yalnız o anki görünüm/sayfada arar
  (`AlertHistory.jsx:262-289`: bulunamazsa param "yalnız tüketilir").
- **Neden bug:** 9 izleme türünün + sertifika penceresinin Kontrol Geçmişi'nde segmente / çentiğe / listeye / "Alarm geçmişinde
  aç"a basan kullanıcı Alarm Geçmişi'nin varsayılan "Açık" görünümüne düşer; hedef alarm açılmaz, vurgulanmaz; çoğu kesinti
  segmenti ÇÖZÜLMÜŞ alarm olduğundan listede bile yoktur. `OutageTimeline.test.jsx:21, 115` `{ incident: 1 }` / `{ incident: 5 }`
  bekleyerek hatayı SÖZLEŞME olarak pinliyor (ISSUE-002 ile aynı desen).
- **Çözüm:** Bildirim kutusuyla aynı biçim (`alertHistoryModel.alertLink`): `navigateTo('alerthistory', { alert: String(a.id),
  type: a.alert_type, q: a.domain, view: a.resolved ? 'closed' : undefined })` — tek yardımcıya alın, dört çağrı yeri onu
  kullansın. Testleri `{ alert: '1', view: 'closed', … }` sözleşmesine çevirin + AlertHistory tarafında `?alert=<kapalı id>&view=closed`
  ile detayın açıldığını sınayan bir uçtan uca test.

### DÜŞÜK

**E3 · `certmodal/certHistoryModel.js:200-204` (`renewalWindow`) + `certmodal/CertHistoryInsights.jsx:63-66` — "Yenilemeye in" penceresi kontrol sıklığından dar; inilen sayfada "Yenilendi +N" rozeti çıkmıyor**
- **Bug:** Pencere = yenileme kovası ± `min(kova, 6 sa)` (saatlik kovada `[t−1 sa, t+2 sa]`). Satırın "Yenilendi" rozeti ve
  ayrıntı notu `compareToOlder(item, findOlder(items, index))` ile AYNI SAYFADAKİ bir önceki değerli kontrolden türer
  (`CertCheckHistory.jsx:65`).
- **Neden bug:** Varsayılan saatlik süpürmede önceki kontrol pencerede kalır, sorun yok. Ama kaydın `check_interval_hours`'ı
  6 / 12 / 24 / 168 ise önceki kontrol `t−6 sa`… öncesindedir → pencere dışında → `findOlder` null → inilen tek satırda
  "Yenilendi +365" rozeti de "önceki seri no." notu da yok; modelin yorumu ("yenileme satırı Yenilendi rozetiyle ilk sayfada")
  bu kayıtlarda tutmuyor. Kullanıcı yenileme anına indiğini anlamaz.
- **Çözüm:** `detectRenewals` olayına önceki değerli noktanın zamanını ekleyin (`prevT`) ve pencereyi `[prevT, t + kova]`
  yapın (üstten 7 günle sınırlayın); ya da `rowCtx`'e sayfa dışı komşu için tek satırlık `status=all&size=1&to=<checked_at>` sorgusu.

**E4 · `history/HistTile.jsx:33-35` + `certmodal/CertHistoryInsights.jsx:76-87` — salt gösterim kutucuklarının açıklaması yalnız fareyle (hover-only); yeni sertifika kutucuklarında son hatanın METNİ de orada**
- **Bug:** `onClick` olmayan kutucuk odaklanamayan bir `div` + `SimpleTooltip` (yalnız hover/odak — div odak almaz). Yeni
  "Başarı oranı", "Kalan gün", "Yenileme", "Son hata" kutucukları bu yoldan; "Son hata" ipucu `— <hata metni>` taşıyor.
- **Neden bug:** Dokunmatikte (Uptime, çekmece, telefon pencere) ve klavyede açıklama ve hata metni kutucuktan erişilemez;
  kullanıcı kuralı (2026-09-26, `ui/HintPopover` belgesi) "yalnız-hover bilgi YOK", `SimpleTooltip` belgesi "temel bilgi TAŞIMAZ".
- **Çözüm:** `HistTile`'da `hint` varsa ve `onClick` yoksa `HintPopover` (dokun-gör) kullanın; hata metnini ipucundan çıkarıp
  kutucuğun `sub` satırına (kırpılmış + `title`) taşıyın.

**E5 · `certmodal/certHistoryModel.js:170` (`formatRate`) + kardeşi `history/CheckHistoryTab.jsx:312` — yüzde yerelleştirilmemiş**
- **Bug:** `` `${r.toFixed(2)}%` `` — TR arayüzde "97.50%" (TR biçimi "%97,50"); HTTP ekranları `fmtPct` (`formatPercent` +
  `dateLocale`) kullanıyor. Ek #7'nin (DB ekranı) kardeşi; sertifika geçmişi yeniden tasarımı kalıbı kopyaladı.
- **Çözüm:** `formatPercent(nf(r, 2, dateLocale()))` (httpMetricsModel `fmtPct` deseni); iki çağrı yeri tek yardımcıdan.

### Notlar (bulgu değil / Ek 3'e ek)

- **N1 (Ek 3 #1'e ek):** Aynı `relDays`'in GELECEK dalı `t('inv.expiresIn', d)` EN'de d = 1 için "in 1 days"
  (`InventoryDetails.jsx:77-82`) — Ek 3'ün "1 days ago" tekil/çoğul notuyla aynı düzeltmede (`…One` anahtarı) kapatılmalı.
- **N2 (tasarım kokusu, bugün ulaşılamaz):** `range` URL anahtarı iki anlamlı — CheckHistoryTab ön ayarı (`range=7|30|custom`,
  söküm temizliği anahtarı SİLER) ve Alarm Geçmişi `range=active` (`alertHistoryModel.js:25, 35`). İkisi aynı ekranda
  bağlanmıyor (sertifika penceresi Alarm Geçmişi sekmesinde açılmıyor; gömülü AlertHistory `urlSync=false`; sekme geçişi
  `range`'i temizliyor), `filtersFromUrl` yalnız `'active'`'i kabul ediyor. Ek 3 #2 düzeltilirken (pencere açıkken sekme
  değişimi) bu yol da kapanmış olur; kalıcı çözüm CheckHistoryTab anahtarlarını öneklemek (`h_range`, eski `range` yalnız okunur).

---

## Temiz sınıflar (tarandı, Ek 1–3 dışında örnek yok — doğrulanan kapsamda)

- **Async / durum:** `useCertDaysSeries` / `useLastFailure` (seq + alive + `finally`, anahtar null'da koşulsuz sıfırlama, iptal
  bayrağı), `CertificateModal.refreshCert` (bayrak guard'dan önce, `setNoc(null)` `!domain` dönüşünden önce), `InventoryTab`
  (hedef değişince koşulsuz `loading`, `alive`), `useHttpOverview` / `HttpEndpointDetail` (seq, `useVisibleInterval`),
  `WarningsPage.load` (Promise.allSettled, seq). Memo'suz context bağımlılığı yok; yeni/değişen kodda çıplak `setInterval` yok (App.jsx'teki
  dört `setInterval` diff dışında, önceden var ve temizleniyor). `CheckHistoryTab` `ctx` her çizimde yeni nesne ama tüketiciler
  (`rangeParams` → `paramsKey`) dize anahtarla bağımlı — döngü yok.
- **Tel biçimi:** `CertificateDto` alanlarının tamamı (`issuer_cn`, `tls_version`, `cipher_suite`, `tls_assessment{protocol,
  cipher}`, `via` = `proxy|direct`, `tls_mode_used`, `is_ca`, `intermediate_*`), `/ssl/response-series` (`series[{ts,count,down,
  days}]`, `bucket`, `from`, `to`, `down_total` — `ts` UTC `Z`siz → `toUtc` doğru), `/http-metrics/overview` + uç satırları
  (`last_seen` = UTC dakika kovası → `shortDateTime`/`formatDate`/CSV `toUtc` doğru), `/history` zarfı `noc_notify`/`noc_group_ids`,
  `/api/warnings` satırı. Durum kodu kümesi (trust/chain/revocation/deployment) arka uçla eşleşiyor; tanınmayan kod ham yazılıyor.
- **Güvenlik:** `dangerouslySetInnerHTML` yok. Dış bağlantılar yalnız `safeHttpUrl` (http/https, `URL` ayrıştırıcısıyla) +
  `target=_blank rel="noopener noreferrer"` (OCSP/CRL, serbest metin, markdown `a`, site bağlantısı); markdown ham HTML
  çizmiyor; `mailto` yalnız e-posta regex'inden geçen adres.
- **URL ad alanı:** yeni anahtar yok (Alarm Geçmişi `range` zaten `PAGE_STATE_PARAMS`'ta; `alert`/`view` kurallı). Bkz. N2.
- **Paylaşılan bileşen geriye uyumu:** `CheckHistoryTab` 10 çağıranın hiçbiri `renderRow`'un ikinci argümanında `index`
  dışında alan okumuyor; `renderAbove` tek çağıranı (`DomainMonitorPage`) yalnız `preset` okuyor; yeni yuvalar verilmezse eski
  yol. `PaginationBar` / `calendar` / `DatePickerParts` (`TOUCH_HIT`) / `DateTimeField` × / `TimeRangePicker` / `WeekDatePicker`
  yalnız `pointer-coarse:` sınıfı ekliyor; × düğmesi DOM'da tetikten sonra ve konumlu → üstte. `sheet` `overlayClassName`
  isteğe bağlı, `SheetOverlay` `cn` ile birleştiriyor. `CardDensityToggle` `hideLabelsOnPhone` varsayılan kapalı. `HistTile`
  çıkarımı bit düzeyinde aynı. App.jsx'ten silinen içe aktarımlar (`Input`, `X`, `Search`, `Layers`, `SearchableSelect`,
  `FacetedFilter`, `dashFiltersActive`) başka yerde kullanılmıyor; CertificateModal'da kalan içe aktarımların hepsi kullanılıyor.
- **Sayfalama:** `useServerPagination` 1-tabanlı; `latestDays` `page === 1` doğru; CertSanList "Tümünü göster" gerekçeli
  `paginationBase` istisnası.
- **i18n:** betikle sayıldı — kapsamdaki 748 (sertifika/envanter/Genel Bakış/geçmiş/tarih/alarm/HTTP) + 392 (DB/dizin) statik
  anahtar ve tüm dinamik aileler (`cdp.{ku,eku,trust,chain,rev,dep,sum,host,flag}.*`, `dash.flt.*`, `hist.range*d`,
  `hreq.{class,sort,tone,chart.bucket}.*`, `inv.tier1-4`, `otl.unit*`, `range.<QUICK_RANGES>`, `attn.group.{now,soon,config}`,
  `alh.{level,quick}.*`, `uact.*`, `udir.*`, `dba.err.*`, `usr.orgRoleVal.*`) TR ve EN'de var; `{n}` yer tutucu kümeleri eşit.
  Yeni EN metinleri doğal British English (Ek 3 #1 ve N1'deki tekil/çoğul dışında).
- **UI kuralları:** sol renk şeridi yok; yeni legacy sınıf yok (`show-markdown`, `stats-filter-bar` önceden vardı);
  `--z-modal`/`--z-menu` tanımlı; `var(--color-*)` kullanımları `ChartContainer` `config` anahtarları; `--pg-fill` ProgressBar sözleşmesi.
- **Testler:** kapsamdaki 29 test dosyasının 33 `vi.mock` yolu gerçek modüllere çözülüyor; sabit tarihler yalnız gösterim /
  parametreli saf fonksiyonlarda (kayan pencereye karşı ölçüm yok); gün ayırıcı iddiası (`≥ 2`, 1 sa ve 27 sa önceki kayıt
  = her saatte en az iki yerel gün) ve göreli süre iddiaları (`now` prop'u) deterministik. İstisnalar Ek 2 #4/#5'te ve E2'de
  (yanlış sözleşmeyi pinleyen `OutageTimeline.test`).

---

## Okunan dosyalar

| Dosya | Durum |
|---|---|
| `components/admin/httpmetrics/*` (11: HttpEndpointDetail, HttpEndpointTable, HttpExplorerModal, HttpExplorerToolbar, HttpParts, HttpSparkCharts, HttpStatusCodes, HttpTopLists, HttpTrafficChart, httpMetricsModel, useHttpOverview) | okundu ✓ (satır satır; + arka uç `HttpMetricsQueryService` yanıt biçimi) |
| `components/admin/HttpMetricsExplorer.jsx`, `components/admin/health/HttpSection.jsx` | okundu ✓ |
| `components/admin/dbanalytics/*` (10), `components/admin/DbAnalyticsPanel.jsx` | 28c-ek kapsamında — koordinatör talimatıyla satır satır OKUNMADI; `DbAnalyticsPanel` başı + `useMinWidth` okundu, i18n (statik + dinamik) ve riskli desen / async süpürmesi yapıldı: bariz kaçak yok |
| `components/admin/useractivity/Directory*`, `UserDirectoryDetail.jsx`, `UserDirectoryModal.jsx`, `directoryModel.js` | 28c-ek kapsamında — satır satır OKUNMADI (koordinatör talimatı); i18n + riskli desen + async süpürmesi: bariz kaçak yok |
| `components/admin/useractivity/useViewportWidth.js` | okundu ✓ |
| `components/certmodal/CertDetailsPanel.jsx`, `CertDetailsParts.jsx`, `CertDetailsSummary.jsx`, `CertSanList.jsx`, `certDetailsModel.js` | okundu ✓ |
| `components/certmodal/CertCheckHistory.jsx`, `CertHistoryInsights.jsx`, `CertHistoryRow.jsx`, `CertDaysTrend.jsx`, `certHistoryModel.js`, `useCertHistoryData.js` | okundu ✓ (+ `history/useCheckHistory.js`, arka uç `ssl-history` / `ssl/response-series` / `CheckHistoryService.resolve`) |
| `components/inventory/InventoryDetails.jsx`, `InventoryDetailParts.jsx`, `inventoryDetailModel.js` | okundu ✓ |
| `components/inventory/InventoryDrawer.jsx` | okundu ✓ (tam) |
| `components/dashboard/DashboardFilters.jsx` | okundu ✓ |
| `pages/warnings/AttentionList.jsx` | okundu ✓ (tam) |
| `pages/WarningsPage.jsx` | diff + veri/süzgeç gövdesi okundu ✓ |
| `components/CertificateModal.jsx` | diff + yükleyiciler/durum bölümü okundu ✓ |
| `components/history/CheckHistoryTab.jsx` | okundu ✓ (tam) |
| `components/history/HistTile.jsx`, `DensityStrip.jsx`, `OutageTimeline.jsx` | okundu ✓ (+ `ui/HintPopover.jsx`, `ui/SimpleTooltip.jsx`) |
| `components/ui/PaginationBar.jsx`, `DateTimeField.jsx`, `DatePickerParts.jsx`, `DateTimeRangePicker.jsx`, `TimeRangePicker.jsx`, `WeekDatePicker.jsx`, `CardDensityToggle.jsx` | diff okundu ✓; `DateTimeField` + `DateTimePopover` tam |
| `components/shadcn/calendar.jsx`, `components/shadcn/sheet.jsx` | diff okundu ✓ |
| `components/UptimePage.jsx` | diff + aralık/yoklama bölümü okundu ✓ |
| `components/admin/alerts/AlertToolbar.jsx`, `alertHistoryModel.js` (range=active) | okundu ✓ (tam); + `AlertHistory.jsx` yükleme/derin bağlantı bölümü, arka uç haftalık e-posta `alarmsUrl` |
| `components/admin/alerts/` diğer 11 dosya | bu diff'te DEĞİŞMEDİ, `range` kullanmıyor (grep) — okunmadı |
| `App.jsx` | diff okundu ✓ (+ `handleTabChange`, `sm:navigate`/`popstate`, `cardActions`, sertifika penceresi açıcıları) |
| `components/noc/NocStatus.jsx`, `nocStatusModel.js`, `pages/warnings/warningsModel.js` | diff okundu ✓ |
| Yeni testler: CertCheckHistory, CertDetailsPanel, CheckHistoryTab.slots, DashboardFilters(.app), InventoryDetails.redesign, InventoryDrawer.certHistory, UptimePage.sslHistory, warningsNocStatus, certDetailsModel, certHistoryModel, inventoryDetailModel | `vi.mock` yolları + zaman/fixture/iddia taraması ✓; CertCheckHistory (1-260), CertDetailsPanel (1-75), CheckHistoryTab.slots (130-213), DashboardFilters (1-60), InventoryDetails.redesign (50-150) okundu |
| Yeni testler: HttpSection, SystemHealthHttp, httpMetricsModel, dbAnalyticsModel, userDirectoryModel | `vi.mock` yolu + tarih taraması ✓ (28c-ek/Ek 2 kapsamında) |
| Değişen testler: PaginationBar, a11yControls, datePickers, monitorCardStandard, paginationBase (kapı gevşemesi kontrolü) | diff okundu ✓ — kapı gevşetilmedi (paginationBase'e tek gerekçeli istisna: CertSanList) |
| Değişen testler: AlertHistory, CertDeepLink.app, CertificateModal, DbAnalyticsPanel, DensityStrip, HttpMetricsExplorer, OutageTimeline, UserDirectoryModal | `vi.mock` yolları ✓; OutageTimeline:21,115 (E2) okundu; diğerleri satır satır okunmadı |

Koşturulmadı: derleme, lint, vitest, Playwright, mweb ölçümü.

---

## Özet

| # | Önem | Dosya | Sınıf | Kaynak |
|---|---|---|---|---|
| E1 | ORTA | `history/CheckHistoryTab.jsx:227-238` (Uptime) | yanlış bilgi (sahte saklama kırpması bandı) | önceden var; bu diff SSL sütununda kalıcılaştırdı |
| E2 | ORTA | `history/OutageTimeline.jsx:85,162,173`, `CheckHistoryTab.jsx:91` | derin bağlantı anahtarı (`incident` ≠ `alert`, `view` yok) | önceden var; bu diff dokunmatik listede yeniden üretti; test yanlışı pinliyor |
| E3 | DÜŞÜK | `certmodal/certHistoryModel.js:200-204` | yenileme anına inme penceresi dar | yeni |
| E4 | DÜŞÜK | `history/HistTile.jsx:33-35` | a11y / yalnız-hover bilgi | yeni kutucuklarla genişledi |
| E5 | DÜŞÜK | `certmodal/certHistoryModel.js:170` | i18n yüzde biçimi | yeni (kardeşi önceden var) |
| N1 | not | `InventoryDetails.jsx:77-82` | i18n tekil/çoğul | Ek 3 #1'e ek |
| N2 | not | `range` URL anahtarı | ad alanı kokusu | bugün ulaşılamaz |

Önerilen sıra: E2 (tek yardımcı + test sözleşmesi) ve E1 (tek satırlık `fixedMode` dalı) yayın öncesi — ikisi de küçük, ikisi de
kullanıcıya yanlış sonuç gösteren yollar; E3–E5 ve N1 sonraki bakım turunda.

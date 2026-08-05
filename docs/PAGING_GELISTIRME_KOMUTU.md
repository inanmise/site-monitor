# GÖREV: CertMonitor — Tüm İzleme Sayfalarında Standart, Zenginleştirilmiş Sayfalama (Paging) Altyapısına Geçiş

> Bu komutu repo kökünde (D:\cert-monitor) Claude Code'a olduğu gibi verebilirsin.

---

## 1. Amaç

Projedeki **tüm liste/izleme görünümlerinde** tek ve standart bir sayfalama (paging) yapısına geçilecek. Hedefler:

- Hiçbir sayfada binlerce kayıt tek seferde render edilmesin (sayfa başına en fazla 200 kayıt).
- Sayfa başına kayıt seçenekleri: **25 / 50 / 100 / 200**, varsayılan **50**.
- **« İlk / ‹ Önceki / numaralı sayfalar / Sonraki › / Son »** butonlarıyla hızlı geçiş.
- Anlık **"Sayfa X / Y"** göstergesi + **"A–B / Toplam N kayıt"** aralık bilgisi.
- Çok sayfa varken **"Sayfaya git"** girişiyle doğrudan atlama.
- Kullanıcının seçtiği sayfa boyutu **kalıcı** olsun (localStorage, görünüm bazında).
- Tüm izleme sayfalarında **görsel ve davranışsal olarak birebir aynı** bileşen kullanılsın.

Bu fazda **backend değişikliği yok** — mevcut endpoint'ler olduğu gibi kalıyor (gerekçe için §6).

---

## 2. Mevcut Durum (tespit edilmiş, yeniden keşfetmeye gerek yok)

Bugün projede **en az 5 farklı sayfalama görünümü ve 4 farklı sayfa-boyutu seti** var; birçok liste ise hiç sayfalanmıyor:

### 2.1 Hiç sayfalaması OLMAYAN listeler (tüm kayıtlar tek seferde render ediliyor)
| Görünüm | Dosya | Not |
|---|---|---|
| HTTP izleme listesi | `frontend/src/components/HttpMonitorPage.jsx` | `displayMonitors.map(...)` — kart grid (`upt-grid`), sayfalama yok |
| Ping izleme listesi | `frontend/src/components/PingMonitorPage.jsx` | aynı desen |
| Port izleme listesi | `frontend/src/components/PortMonitorPage.jsx` | aynı desen |
| DNS izleme listesi | `frontend/src/components/DnsMonitorPage.jsx` | `filtered.map(...)` |
| Domain (alan adı) izleme listesi | `frontend/src/components/DomainMonitorPage.jsx` | aynı desen |
| Keyword izleme listesi | `frontend/src/components/KeywordMonitorPage.jsx` | aynı desen |
| Page (sayfa) izleme listesi | `frontend/src/components/PageMonitorPage.jsx` | liste **ve** geçmiş modalı sayfasız |
| Scripted izleme listesi | `frontend/src/components/ScriptedMonitorPage.jsx` | liste **ve** DetailModal geçmişi (`history.map`) sayfasız |
| Envanter yönetimi | `frontend/src/components/admin/InventoryManager.jsx` | `visibleItems.map(...)` — binlerce satır olabilir |
| Bakım pencereleri | `frontend/src/components/MaintenanceWindowsPage.jsx` | tablo sayfasız |
| Geçmiş listeleri | `PageMonitorPage`, `ScriptedMonitorPage`, `DomainMonitorPage`, `DnsDetailModal.jsx` | modal içi geçmiş tabloları sayfasız (DnsDetailModal'ı kontrol et; sayfalama varsa standarda çevir) |

### 2.2 Sayfalaması OLAN ama birbirinden farklı görünümler
| Görünüm | Dosya | Mevcut durum |
|---|---|---|
| Dashboard sertifika kartları | `frontend/src/App.jsx` (~satır 601–866) | Client-side; varsayılan **12** (seçeneklerde bile yok!), seçenekler [10, 25, 50, Tümü]; `dash-*` sınıfları; «‹ numara ›» tam bar |
| Uptime listesi | `frontend/src/components/UptimePage.jsx` | **Sabit PAGE_SIZE=12**, boyut seçici yok; `upt-*` sınıfları; `pageNumbers()` pencereli numara üretici burada |
| Tüm Sertifikalar tablosu | `frontend/src/components/CertificatesTable.jsx` | **Server-side** (`/certificates/list`, `page`/`per_page`); varsayılan 20, seçenekler [10, 20, 50, 100] (SearchableSelect); `pagination-*` sınıfları |
| Alarm Geçmişi | `frontend/src/components/admin/AlertHistory.jsx` | Server-side; 0-tabanlı `page`, boyutlar [10, 20, 50]; `alh-*` sınıfları |
| Incident Geçmişi | `frontend/src/components/IncidentHistoryPage.jsx` | Server-side; boyutlar [10, 20, 50]; `alh-*` sınıflarını ödünç alıyor; sadece önceki/sonraki |
| Audit Log | `frontend/src/components/admin/AuditLogViewer.jsx` | Server-side; **sabit 50**, boyut seçici yok; `audit-*` sınıfları |
| HTTP/Keyword/Ping/Port geçmiş modalları | ilgili sayfalar içinde | Client-side; boyutlar [50, 100, 200]; `dns-history-pagination` + `dash-*` sınıfları; ilk-sayfa butonu yok |

### 2.3 Veri akışı gerçekleri (tasarımı etkileyen)
- İzleme listeleri `api.monitoring.getXxxMonitors()` ile **tam liste** olarak çekiliyor ve `useVisibleInterval` ile ~60 sn'de bir yenileniyor (`REFRESH_INTERVAL`).
- İstatistik barı (`MonitorStatsBar` / stat kartları), takım/grup filtre seçenekleri ve deep-link (`?id=` → `monitors.find(...)`) **tam listeye** ihtiyaç duyuyor.
- Sayfalar zaten client-side filtre (takım, grup, arama, stat filtresi) uyguluyor; sayfalama bu filtrelenmiş dizinin **üzerine** gelmeli.

---

## 3. Yapılacak: Ortak Sayfalama Altyapısı

### 3.1 `frontend/src/hooks/usePagination.js` (yeni)
Client-side sayfalama hook'u:

```js
const {
  page,          // 1-tabanlı geçerli sayfa (clamp'lenmiş)
  setPage,
  pageSize,      // seçili boyut
  setPageSize,   // boyut değişince sayfa 1'e döner
  totalPages,    // Math.max(1, ceil(total / pageSize))
  totalItems,
  pageItems,     // items.slice((page-1)*pageSize, page*pageSize) — useMemo'lu
  rangeStart,    // (page-1)*pageSize + 1  (total 0 ise 0)
  rangeEnd,      // Math.min(page*pageSize, totalItems)
} = usePagination(items, {
  listKey: 'http-monitors',   // localStorage anahtarı: cm.pageSize.<listKey>
  defaultSize: 50,
  resetDeps: [search, teamFilter, groupFilter, statFilter], // değişince sayfa 1'e döner
})
```

Davranış kuralları:
- **Clamp**: veri küçülünce (ör. polling sonrası kayıt silindi, filtre daraldı) `page > totalPages` olursa otomatik son geçerli sayfaya çekilir — mevcut `safePage` desenlerinin genelleştirilmişi.
- **Reset**: `resetDeps` içindeki herhangi bir değer değişince `page = 1`.
- **Polling kararlılığı**: `items` referansı her yenilemede değişse de sayfa ve boyut korunur (state item'lara değil, page/pageSize'a bağlı).
- **Kalıcılık**: `pageSize` değişiminde `localStorage['cm.pageSize.' + listKey]` güncellenir; ilk yüklemede oradan okunur, geçersiz değerde `defaultSize`'a döner. (`try/catch` ile sarmala — private mode.)
- 1-tabanlı sayfa numarası **tek standart**; 0-tabanlı state kullanan mevcut sayfalar bileşene 1-tabanlı değer geçirir.

### 3.2 `frontend/src/components/ui/PaginationBar.jsx` (yeni)
Hem client-side hem server-side listelerin kullanacağı **tek görsel bileşen**:

```jsx
<PaginationBar
  page={page} totalPages={totalPages} totalItems={totalItems}
  rangeStart={rangeStart} rangeEnd={rangeEnd}
  pageSize={pageSize} sizeOptions={[25, 50, 100, 200]}
  onPageChange={setPage} onPageSizeChange={setPageSize}
  compact={false}          // modal içi küçük varyant
/>
```

Yerleşim (tek satır, dar ekranda sarar):

```
[ Sayfa başına: 25 | 50 | 100 | 200 ]   [ « ‹ 1 … 4 [5] 6 … 42 › » ]  [Git: __ ]   [ Sayfa 5/42 · 201–250 / 2.084 kayıt ]
```

Bileşen kuralları:
- **Numaralı sayfalar pencereli**: ilk, son, geçerli ±1; aradaki boşluklar `…` — `UptimePage.jsx` içindeki `pageNumbers()` yardımcısını **bileşenin içine taşı** ve oradan sil (tek kopya kalsın).
- **« İlk** ve **Son »** butonları her zaman var; sınırda `disabled`.
- **"Sayfaya git"** girişi yalnız `totalPages > 10` iken görünür; Enter ile gider, değer clamp'lenir, geçersiz girişte hiçbir şey yapmaz.
- `totalItems === 0` → bar hiç render edilmez. `totalPages === 1` → gezinme butonları gizlenir ama boyut seçici + kayıt sayısı görünür kalır.
- Sayfa değişince liste kapsayıcısının başına yumuşak scroll (`scrollIntoView({ behavior:'smooth', block:'start' })`; `prefers-reduced-motion` durumunda `auto`). Bunun için opsiyonel `scrollTargetRef` prop'u ekle; verilmezse scroll yapılmaz (modal içi kullanım).
- **Erişilebilirlik**: `<nav aria-label>` sarmalayıcı; her butonda `aria-label` (i18n), geçerli sayfa butonunda `aria-current="page"`; tüm kontroller klavyeyle kullanılabilir (native `<button>`/`<input>`).
- Sayı biçimlendirme: kayıt sayıları `toLocaleString` ile aktif dile göre (TR: 2.084, EN: 2,084).
- İkon gerekiyorsa **lucide-react** (`ChevronLeft`, `ChevronRight`, `ChevronsLeft`, `ChevronsRight`) — asla emoji değil (proje kuralı).
- `compact` varyantı: boyut seçici + ‹ x/y › + Git girişi; modallardaki geçmiş listeleri için.

### 3.3 CSS — `App.css`
- Yeni tek sınıf ailesi: `.pg-bar`, `.pg-sizer`, `.pg-size-btn`, `.pg-nav`, `.pg-btn`, `.pg-btn--active`, `.pg-ellipsis`, `.pg-goto`, `.pg-info`, `.pg-bar--compact`.
- Mevcut tema değişkenlerini kullan (`var(--primary)`, `var(--text-muted)` vb.) — **açık ve koyu temada** doğru görünmeli (`[data-theme="dark"]`).
- Responsive: `flex-wrap`; ~640px altında numaralı butonlar gizlenir (`display:none`), «‹›» + bilgi kalır.
- Görsel referans: mevcut `dash-pagination` / `alh-pagination` stillerinin sadeleşmiş birleşimi. Eski sınıflar (`dash-*`, `alh-*`, `upt-page*`, `pagination-*`, `audit-pagination`, `dns-history-pagination`) kullanım kalmayınca App.css'ten temizlenir (önce `grep` ile kullanım kalmadığını doğrula).

### 3.4 i18n — `frontend/src/i18n/index.jsx`
Yeni ortak anahtarlar (**hem TR hem EN**, parity testi zorunlu kılıyor):

| Anahtar | TR | EN |
|---|---|---|
| `pg.perPage` | Sayfa başına | Per page |
| `pg.pageOf` | Sayfa {0} / {1} | Page {0} of {1} |
| `pg.range` | {0}–{1} / {2} kayıt | {0}–{1} of {2} records |
| `pg.first` | İlk sayfa | First page |
| `pg.prev` | Önceki | Previous |
| `pg.next` | Sonraki | Next |
| `pg.last` | Son sayfa | Last page |
| `pg.goto` | Sayfaya git | Go to page |
| `pg.gotoLabel` | Git | Go |
| `pg.pageBtn` | Sayfa {0} | Page {0} |

Eski anahtarlar (`app.perPage`, `app.pageInfo`, `tbl.prev`, `alh.pageOf`, `inc.perPage`, `audit.pageInfo`, `uptime.pageInfo` vb.) başka yerde kullanılmıyorsa kaldırılabilir; önce `grep` ile doğrula, emin değilsen bırak (parity bozulmaz).

---

## 4. Kapsam — Dönüştürülecek Görünümler

### A) Client-side listeler → `usePagination` + `<PaginationBar>`
Sayfalama daima **filtrelenmiş dizinin** üzerine uygulanır (`displayMonitors` / `filtered` → `pageItems` render edilir). İstatistik barı, sayaçlar ve filtre seçenekleri **tam listeden** hesaplanmaya devam eder — bunlara dokunma.

1. `HttpMonitorPage.jsx` — `listKey:'http-monitors'`, resetDeps: search, teamFilter, groupFilter, statFilter
2. `PingMonitorPage.jsx` — `'ping-monitors'`
3. `PortMonitorPage.jsx` — `'port-monitors'`
4. `DnsMonitorPage.jsx` — `'dns-monitors'`
5. `DomainMonitorPage.jsx` — `'domain-monitors'`
6. `KeywordMonitorPage.jsx` — `'keyword-monitors'`
7. `PageMonitorPage.jsx` — `'page-monitors'`
8. `ScriptedMonitorPage.jsx` — `'scripted-monitors'`
9. `UptimePage.jsx` — `'uptime'`; sabit `PAGE_SIZE=12` kalkar, standart boyutlar gelir; `pageNumbers()` bileşene taşınır
10. `App.jsx` dashboard sertifika kartları — `'dashboard-certs'`; **"Tümü" seçeneği kaldırılır** (amaç binlerce kartın render edilmemesi; max 200/sayfa), 12'lik tutarsız varsayılan yerine standart 50
11. `admin/InventoryManager.jsx` — `'inventory'`; `visibleItems` → sayfalanır; **dikkat**: "tümünü seç" davranışı varsa `selectableItems` sayfadakileri değil filtrelenmiş tümünü mü seçiyor netleştir — mevcut davranışı koru, sadece render'ı sayfala
12. `MaintenanceWindowsPage.jsx` — `'maintenance-windows'`

### B) Modal içi geçmiş listeleri → `compact` varyant
Boyut seçenekleri burada da [25, 50, 100, 200] olabilir; mevcut [50, 100, 200] alışkanlığını korumak istersen `sizeOptions={[50,100,200]}` geç — ama **bileşen aynı olmalı**:

13. `HttpMonitorPage.jsx` geçmiş modalı — mevcut el yazması barı `<PaginationBar compact>` ile değiştir
14. `KeywordMonitorPage.jsx` geçmişi — aynı
15. `PingMonitorPage.jsx` geçmişi — aynı
16. `PortMonitorPage.jsx` geçmişi — aynı
17. `PageMonitorPage.jsx` geçmişi — **yeni ekle** (şu an sayfasız)
18. `ScriptedMonitorPage.jsx` DetailModal geçmişi — **yeni ekle** (props ile page state'i DetailModal'a taşırken dikkat; state sahibi üst bileşen kalabilir)
19. `DomainMonitorPage.jsx` geçmişi — **yeni ekle**
20. `DnsDetailModal.jsx` — geçmiş listesi sayfasızsa ekle, kendi barı varsa standarda çevir

### C) Server-side listeler → yalnız `<PaginationBar>` (hook'suz)
Sayfa/boyut state'i sayfada kalır; bileşene 1-tabanlı değerler geçirilir. Backend çağrıları aynen kalır.

21. `CertificatesTable.jsx` — SearchableSelect'li boyut seçici ve `pagination-controls` bloğu `<PaginationBar>` ile değiştirilir; boyutlar [25, 50, 100, 200]'e standardize edilir (backend `per_page`'i zaten parametrik); varsayılan 20 → 50
22. `admin/AlertHistory.jsx` — 0-tabanlı `page` içeride kalabilir, bileşene `page+1` geçir; boyutlar [10,20,50] → [25,50,100,200]; boyut tercihi `listKey` mantığıyla localStorage'a yazılsın (bileşen `onPageSizeChange` çağırır, sayfa kendisi persist eder ya da hook'un persist yardımcı fonksiyonunu export edip kullan)
23. `IncidentHistoryPage.jsx` — aynı dönüşüm
24. `admin/AuditLogViewer.jsx` — sabit 50 kalkar; `size` parametresi zaten backend'e gidiyorsa seçilebilir yap, gitmiyorsa `loadLogs`'a ekle (endpoint `page`+`size` alıyor; kontrol et — `AuditController` server-side sayfalı)

### Dokunulmayacaklar
- Backend endpoint'leri ve DTO'lar (bu fazda).
- `MonitorStatsBar` / stat kartları ve sayaçlar — tam listeden hesaplanmaya devam.
- Deep-link (`?id=...`) davranışı — monitör `monitors.find` ile bulunur, **sayfalamadan bağımsız çalışmaya devam etmeli** (kayıt 7. sayfada olsa bile modal açılmalı). Bunu bozma ve testle sabitle.
- `useVisibleInterval` polling mekanizması ve `REFRESH_INTERVAL`.
- Takım/grup filtre seçeneklerinin tam listeden türetilmesi.

---

## 5. Testler (zorunlu — hepsi yazılacak)

Vitest + `test-utils.jsx#render()`; API `vi.mock('../api/client', ...)` ile mock'lanır; gerçek `fetch` asla kaçmaz (proje kuralı).

### 5.1 `src/test/usePagination.test.js` (yeni)
- 120 elemanla varsayılan 50 → `pageItems.length === 50`, `totalPages === 3`, `rangeStart===1`, `rangeEnd===50`.
- `setPage(3)` → 20 eleman, `rangeStart===101`, `rangeEnd===120`.
- `setPageSize(100)` → sayfa 1'e döner, 100 eleman.
- **Clamp**: sayfa 3'teyken items 40'a düşer → sayfa otomatik 1 olur (`totalPages=1`).
- **Reset**: `resetDeps` değişince sayfa 1'e döner.
- Boş dizi → `totalPages===1`, `pageItems=[]`, `rangeStart===0`.
- **Persist**: `setPageSize(100)` sonrası `localStorage['cm.pageSize.<key>'] === '100'`; yeni mount'ta 100 okunur; localStorage'da saçma değer (`'999'`, `'abc'`) → `defaultSize`.
- Polling simülasyonu: aynı içerikli yeni dizi referansı → `page` değişmez.

### 5.2 `src/test/PaginationBar.test.jsx` (yeni)
- 42 sayfa, geçerli 5 → görünen numaralar: 1, …, 4, [5], 6, …, 42; `aria-current="page"` doğru butonda.
- Sayfa 1'de « ve ‹ `disabled`; son sayfada › ve » `disabled`.
- « → `onPageChange(1)`, » → `onPageChange(totalPages)`, numara tıklaması doğru değeri çağırır.
- Boyut butonuna tıklama → `onPageSizeChange(100)`.
- "Git" girişi: `totalPages=5` iken görünmez; `totalPages=11`'de görünür; "7"+Enter → `onPageChange(7)`; "99" → clamp `onPageChange(11)`; "abc"/boş → çağrı yok.
- `totalItems=0` → bar render edilmez; `totalPages=1` → nav yok, boyut seçici + kayıt bilgisi var.
- `compact` varyantı render olur ve temel kontrolleri içerir.
- TR ve EN dillerinde etiketler doğru (i18n parity testi zaten kalanı zorlar).

### 5.3 Sayfa entegrasyon testleri (mevcut test dosyalarına ekle)
Her izleme sayfası testine (`HttpMonitorPage.test.jsx`, `PingMonitorPage.test.jsx`, `PortMonitorPage.test.jsx`, `DnsMonitorPage.test.jsx`, `DomainMonitorPage.test.jsx`, `KeywordMonitorPage.test.jsx`, `PageMonitorPage.test.jsx`, `ScriptedMonitorPage.test.jsx`, `UptimePage` varsa) şu senaryolar:
- Mock ile **120 monitör** döndür → DOM'da 50 kart/satır; "Sayfa 1/3"; "1–50 / 120".
- "Sonraki" → 51–100 aralığı; farklı bir monitör adı görünür, ilk sayfadaki görünmez.
- Boyut 100 → sayfa 1'e döner, 100 kart.
- Arama/filtre değişimi → sayfa 1'e döner.
- 30 monitörle (tek sayfa) nav butonları yok ama "30 kayıt" bilgisi var.
- **Deep-link regresyonu** (Http için mevcut deep-link testi varsa genişlet): `?id=` son sayfadaki bir monitörü işaret ederken modal yine açılır.
- `InventoryManager`, `MaintenanceWindowsPage`, `CertificatesTable`, `AlertHistory`, `IncidentHistoryPage`, `AuditLogViewer` testlerinde: bar render oluyor, sayfa değişimi doğru API çağrısını (server-side) ya da doğru dilimi (client-side) üretiyor. Server-side'da `page` parametresinin 0/1-taban dönüşümünün doğruluğunu assert et (AlertHistory: bileşen 2 gösterirken API'ye `page=1` gitmeli).
- Geçmiş modalı olan sayfalarda: 120 kayıtlık mock geçmişte 50 satır + compact bar; sayfa 2'ye geçiş çalışır.

### 5.4 Genel
- `i18n-parity.test.jsx` yeşil (yeni anahtarlar TR+EN eksiksiz, placeholder sayıları eşit).
- Mevcut TÜM testler yeşil: `npm run test`.
- `npm run build` hatasız.

---

## 6. Neden bu fazda client-side (bilgi, uygulama değil)

Monitör list endpoint'leri (`/api/monitoring/http` vb.) tam listeyi zenginleştirip döner; stat barı, takım/grup filtreleri ve `?id=` deep-link tam listeye bağımlı. Bu fazda sayfalama **render katmanında** çözülür: tek seferde en fazla 200 DOM kartı üretilir, veri akışı değişmez, regresyon riski minimal. Fleet ~5.000+ monitöre ulaşırsa ikinci faz olarak server-side sayfalama (endpoint'lere `page`/`size` + ayrı `counts` endpoint'i) ayrıca planlanır — bu görevin kapsamı DIŞINDA, şimdi yapma.

---

## 7. Çalışma Sırası ve Proje Kuralları

1. Önce altyapı: `usePagination.js` + `PaginationBar.jsx` + CSS + i18n anahtarları + iki yeni test dosyası → `npm run test` yeşil.
2. Sonra görünümler: §4 sırasıyla, **her sayfadan sonra o sayfanın testlerini çalıştır** (`npx vitest run src/test/HttpMonitorPage.test.jsx` gibi).
3. En sonda süpürme: `grep -rn "slice(" src/components` ve `\.map(` taramasıyla 50+ satır render edebilecek gözden kaçmış liste kalmadığını doğrula; eski pagination CSS sınıflarını ve kullanılmayan i18n anahtarlarını temizle.
4. `CHANGELOG.md`'ye kullanıcıya dönük özet madde ekle (TR).
5. `VERSION` / `Chart.yaml` dosyalarına DOKUNMA — sürümü CI yönetiyor; commit mesajı conventional prefix ile: `feat(ui): tüm izleme listelerinde standart sayfalama altyapısı`.
6. Kod stili: mevcut dosyaların desenine uy (fonksiyonel bileşenler, `useMemo`, `useT(key)`, lucide-react ikonları, emoji yasak, i18n string'lerinde çıplak `*` yasak).
7. Kapanışta: `npm run test` → `npm run build` → kısa manuel duman testi listesi raporla (aşağıdaki kabul kriterleri üzerinden).

---

## 8. Kabul Kriterleri (hepsi sağlanmalı)

- [ ] 8 izleme türü sayfası (HTTP, Ping, Port, DNS, Domain, Keyword, Page, Scripted) + Uptime + Dashboard + Envanter + Bakım Pencereleri sayfalanıyor; hiçbirinde 200'den fazla kayıt aynı anda render edilmiyor.
- [ ] Tüm bar'lar aynı bileşen: boyutlar 25/50/100/200 (varsayılan 50), « ‹ numaralar › », "Sayfa X/Y", "A–B / N kayıt", 10+ sayfada "Git" girişi.
- [ ] Boyut tercihi görünüm bazında kalıcı (sayfa yenilense de hatırlanıyor).
- [ ] Filtre/arama değişiminde sayfa 1'e dönüyor; veri küçülünce sayfa clamp'leniyor; polling sayfa konumunu bozmuyor.
- [ ] Modal geçmiş listeleri compact bar ile sayfalı (eksik olan 4 sayfaya eklendi).
- [ ] Server-side sayfalı 4 görünüm aynı bileşeni kullanıyor; AuditLog'da boyut seçilebilir.
- [ ] Deep-link (`?id=`) her sayfada, hedef kayıt hangi sayfada olursa olsun çalışıyor.
- [ ] Açık/koyu tema + TR/EN + mobil (dar ekran) düzgün.
- [ ] `npm run test` ve `npm run build` yeşil; i18n parity yeşil; CHANGELOG güncel.

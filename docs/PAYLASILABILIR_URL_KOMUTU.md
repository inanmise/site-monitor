# GÖREV: CertMonitor — Paylaşılabilir URL / Derin Bağlantı (Deep-Link) Altyapısı: Sayfa İçi Konumun URL'e Senkronizasyonu + "Bağlantıyı Kopyala"

> Bu komutu repo kökünde (D:\cert-monitor) Claude Code'a olduğu gibi verebilirsin.

---

## 1. Amaç ve Kullanıcı Senaryosu

Bugün bir kullanıcı Keyword İzleme'de `callcenterfacewebmon` grubunu filtreleyip o anki görünümü bir arkadaşına göndermek istediğinde, tarayıcıdaki URL yalnız `/?tab=keyword` gösteriyor — grup filtresi, takım filtresi, arama metni, sayfa numarası ve açık detay modalı URL'de YOK. Link alan kişi boş/varsayılan sayfaya düşüyor.

Hedef: **o an ekranda görünen durumun tamamı URL'de yaşasın** ve URL her an paylaşılabilir olsun:

```
/?tab=keyword&group=callcenterfacewebmon
/?tab=http&team=SY-Takım A%20Bankacilik&stat=alarm&page=3&ps=50
/?tab=ping&q=example&monitor=42
```

- Filtre/arama/sayfa/detay-modalı değiştikçe URL **anlık** güncellenir (adres çubuğundan kopyala-yapıştır yeter).
- Her izleme sayfasına ve detay modalına **"Bağlantıyı Kopyala"** butonu eklenir (tek tık + toast).
- Link açan kişi **birebir aynı görünümü** görür: aynı filtreler, aynı sayfa, gerekiyorsa açık modal.
- Bu davranış **tüm izleme türlerinde standart** olur (Keyword dahil 8 monitör sayfası + Uptime), ikinci halkada diğer liste görünümlerine genişletilir.

---

## 2. Mevcut Durum (tespit edilmiş — yeniden keşfetmeye gerek yok)

`frontend/src/App.jsx`:
- `?tab=` senkronu VAR: `VALID_TABS` whitelist'i + `initialTabFromUrl()`; `handleTabChange` `pushState` ile yazar; `popstate` dinleyicisi Geri/İleri'de sekmeyi geri yükler.
- `handleTabChange` sekme değişince bayat derin-link paramlarını temizler: şu an sadece `monitor`, `domain`, `incident` siliniyor.
- Dashboard için `?domain=<d>` → arama kutusuna yazılıyor (e-posta olay linki). `?session=expired` ayrı akış — DOKUNMA.

İzleme sayfaları (`HttpMonitorPage.jsx`, `KeywordMonitorPage.jsx`, `PingMonitorPage.jsx`, `PortMonitorPage.jsx`, `DnsMonitorPage.jsx`, `DomainMonitorPage.jsx`, `PageMonitorPage.jsx`, `ScriptedMonitorPage.jsx`):
- E-posta CTA derin-linki VAR ama **tek yönlü ve silinir**: `?monitor=<id>` ilk yüklemede okunur (`deepLinkDone` ref), modal açılır, sonra `replaceState` ile **URL'den silinir**. Yani URL hiçbir zaman sayfa içi konumu taşımaz.
- Filtre state'leri URL'e yazılmıyor: `teamFilter` ('all' | '__none__' | takım adı), `groupFilter` ('all' | '__none__' | grup adı), `search` (metin), `statFilter` (null | 'total' | 'up' | 'down' | 'error' | 'alarm' | 'unacked' — sayfaya göre değişir).
- Sayfalama standardı YENİ uygulandı: `hooks/usePagination.js` (1-tabanlı `page`, `pageSize`, clamp, `resetDeps`, localStorage `cm.pageSize.<listKey>`) + `components/ui/PaginationBar.jsx`. `page`/`pageSize` da URL'de değil.
- Polling: `useVisibleInterval` ~60 sn'de `load()` çağırır — URL senkronu polling'den etkilenmemeli.

Sonuç: altyapının yarısı hazır (`tab` senkronu, `monitor` okuma, popstate iskeleti). Eksik olan: **filtre+sayfa+modal durumunun URL'e çift yönlü bağlanması** ve **kopyalama UX'i**.

---

## 3. Yapılacak: Ortak URL Durum Altyapısı

### 3.1 `frontend/src/hooks/useUrlQuerySync.js` (yeni)
Sayfa state'ini URL query paramlarına **çift yönlü** bağlayan hook + yardımcılar:

```js
// Okuma (mount'ta, useState initializer'larında kullanılır — flicker yok):
export function readUrlParam(key, fallback = null)      // string | fallback
export function readUrlInt(key, fallback = null)        // pozitif int | fallback

// Yazma (state değiştikçe):
useUrlQuerySync({
  group:  groupFilter  === 'all' ? null : groupFilter,
  team:   teamFilter   === 'all' ? null : teamFilter,
  q:      search.trim() || null,
  stat:   (!statFilter || statFilter === 'total') ? null : statFilter,
  page:   page > 1 ? page : null,
  ps:     page > 1 || pageSize !== DEFAULT_PAGE_SIZE ? pageSize : null,
  monitor: selected ? selected.id : null,
}, { debounceMs: 300 })
```

Kurallar:
- **Yalnız varsayılan-dışı değerler URL'e yazılır** (null/undefined → param silinir). URL temiz kalır: `/?tab=keyword&group=callcenterfacewebmon`.
- Yazma daima **`replaceState`** ile yapılır — filtre değişimleri tarayıcı geçmişini şişirmez; sekme geçişleri mevcut `pushState` davranışında kalır (Geri/İleri sekmeler arası gezinmeye devam eder).
- **Debounce (300 ms)**: özellikle arama kutusu her tuşta yazmasın — tarayıcılar `history.replaceState`'i hız-sınırlar (Safari ~100 çağrı/30 sn; aşımı exception fırlatır). Tek zamanlayıcı, son değer kazanır. `try/catch` ile sarılır.
- Diğer paramlara DOKUNULMAZ: `tab`, `session` ve sayfanın yönetmediği her param olduğu gibi korunur (mevcut `url.searchParams.set/delete` deseniyle).
- Hook unmount olurken kendi yazdığı paramları **silmez** (temizlik sekme geçişinde merkezî yapılır, aşağıda).
- Modül, izleme sayfalarının kullandığı param adlarını export eder:
  ```js
  export const PAGE_STATE_PARAMS = ['group', 'team', 'q', 'stat', 'page', 'ps', 'monitor', 'range', 'mtab', 'domain', 'incident']
  ```

### 3.2 `App.jsx` — merkezî temizlik + popstate
- `handleTabChange` içindeki elle silme (`monitor`/`domain`/`incident`) **`PAGE_STATE_PARAMS` döngüsüyle** değiştirilir: sekme değişince önceki sayfanın TÜM durum paramları temizlenir (bayat filtre başka sekmeye taşınmaz).
- `popstate` dinleyicisi aynen kalır (sekme geri yükleme). Filtre değişimleri `replaceState` kullandığı için sekme-içi geçmiş kaydı oluşmaz — popstate'e sayfa-içi mantık EKLEME.
- `?domain=` dashboard davranışı (arama kutusuna yazma) geriye dönük uyum için aynen korunur.

### 3.3 İzleme sayfalarında bağlama (8 sayfanın hepsi + Uptime)
Her sayfada aynı desen:

1. **Başlangıç state'leri URL'den** (`useState` initializer):
   ```js
   const [teamFilter, setTeamFilter]   = useState(() => readUrlParam('team', 'all'))
   const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
   const [search, setSearch]           = useState(() => readUrlParam('q', ''))
   const [statFilter, setStatFilter]   = useState(() => readUrlParam('stat', null))
   ```
   - Geçersiz `stat` değeri (sayfanın stat anahtarları dışında) → null'a düşür (sayfa başında küçük whitelist).
   - `team`/`group` değeri listede artık yoksa filtre uygulanır ama sonuç 0 kayıt gösterir — bu kabul edilebilir; kullanıcı "Tümü"ne dönebilir. Değeri sessizce silme (kullanıcı linkin neyi filtrelediğini görmeli).
2. **`usePagination` başlangıç sayfası**: hook'a `initialPage` opsiyonu ekle (`usePagination.js` içinde `useState(initialPage)`, clamp zaten var). Sayfadan `initialPage: readUrlInt('page', 1)` geçir. `ps` URL'de varsa ve `PAGE_SIZE_OPTIONS` içindeyse **o oturumda localStorage'daki tercihe baskın gelir** (`readPageSize` sonucu yerine kullanılır) — aksi halde link alan kişide farklı sayfa boyutu 3. sayfayı başka dilime kaydırır. `resetDeps`'in ilk mount'ta sıfırlamaması (`firstRun` guard) sayesinde URL'den gelen sayfa korunur — bunu bozma.
3. **`useUrlQuerySync` çağrısı** §3.1'deki eşlemeyle.
4. **`monitor` paramı artık kalıcı**: mevcut derin-link effect'indeki `u.searchParams.delete('monitor')` + `replaceState` temizliği KALDIRILIR. Bunun yerine `selected` state'i sync eşlemesine girer: modal açıkken `monitor=<id>` yazılır, kapanınca silinir. `deepLinkDone` ref mantığı kalır (polling yeniden açmasın); e-posta CTA linkleri aynen çalışmaya devam eder.
5. **Detay modalı içi konum (SHOULD)**: modalda sekme (`detailTab`) ve geçmiş aralığı (`rangeDays`) varsa `mtab` ve `range` paramlarıyla sync et — varsayılan değerlerde param yazılmaz. Link alan kişi modalın aynı sekmesinde açar. (Modal geçmiş sayfası `hp` gibi ekstra parama GEREK YOK — aşırı detay.)

### 3.4 "Bağlantıyı Kopyala" butonu
- Yeni ortak bileşen: `frontend/src/components/ui/CopyLinkButton.jsx`
  - lucide-react `Link2` (veya `Share2`) ikonu; tık → `navigator.clipboard.writeText(window.location.href)` → mevcut Toast sistemiyle `t('share.copied')` bildirimi.
  - Clipboard API başarısız olursa (http, izin yok) fallback: geçici `<textarea>` + `document.execCommand('copy')`; o da olmazsa URL'i gösteren küçük prompt.
  - `title`/`aria-label`: `t('share.copyLink')`.
- Yerleşim: her izleme sayfasının araç çubuğuna (arama kutusunun yanına, "Yenile" butonuyla aynı hizada) + her detay modalının başlığına. Buton URL'i değiştirmez; URL zaten her an günceldir — buton yalnız kopyalar.
- i18n anahtarları (TR + EN, parity zorunlu):
  | Anahtar | TR | EN |
  |---|---|---|
  | `share.copyLink` | Bağlantıyı kopyala | Copy link |
  | `share.copied` | Bağlantı kopyalandı — bu görünüm linkle aynen açılır | Link copied — this view opens exactly as is |

### 3.5 Param sözlüğü (standart — tüm sayfalarda aynı adlar)
| Param | Anlam | Örnek |
|---|---|---|
| `tab` | Sekme (mevcut) | `keyword` |
| `group` | Grup filtresi (grup adı veya `__none__`) | `callcenterfacewebmon` |
| `team` | Takım filtresi (takım adı veya `__none__`) | `SY-Takım A%20Bankacilik` |
| `q` | Arama metni | `example` |
| `stat` | Stat kartı filtresi | `alarm` |
| `page` | Sayfa (yalnız >1 iken) | `3` |
| `ps` | Sayfa boyutu (yalnız `page` varken veya ≠50) | `100` |
| `monitor` | Açık detay modalının id'si (mevcut param, artık kalıcı) | `42` |
| `mtab` / `range` | Modal içi sekme / geçmiş gün aralığı (varsayılan-dışıysa) | `history` / `7` |

Değerler `URLSearchParams` ile otomatik encode edilir; asla elle string birleştirme yapma. Paramlardan gelen hiçbir değer HTML'e ham basılmaz (mevcut React render'ı zaten güvenli; `dangerouslySetInnerHTML` YOK).

---

## 4. Kapsam

### Faz A (MUST — bu görevin çekirdeği)
1. `useUrlQuerySync.js` + `usePagination`'a `initialPage`/`ps` desteği + `CopyLinkButton.jsx` + i18n + `App.jsx` merkezî temizlik.
2. 8 izleme sayfası: `KeywordMonitorPage` (ekran görüntüsündeki asıl senaryo — İLK bunu yap), `HttpMonitorPage`, `PingMonitorPage`, `PortMonitorPage`, `DnsMonitorPage`, `DomainMonitorPage`, `PageMonitorPage`, `ScriptedMonitorPage`.
3. `UptimePage` — kendi filtreleri neyse (arama, durum, sayfa) aynı sözlükle.

### Faz B (SHOULD — aynı PR'da, süre yeterse)
4. Dashboard sertifika kartları (`App.jsx`): `q` (mevcut `domain` paramıyla uyumlu: okuma her ikisinden, yazma `q`), `page`, `ps`.
5. `IncidentHistoryPage`, `admin/AlertHistory`, `admin/AuditLogViewer`, `admin/InventoryManager`: filtre + sayfa paramları (server-side sayfalarda `page` doğrudan API sayfasıdır; 0/1-taban dönüşümüne dikkat — URL'de HEP 1-tabanlı).

### Dokunulmayacaklar
- `?session=expired` akışı ve login yönlendirmeleri.
- `VALID_TABS` whitelist mantığı ve sekme `pushState`/`popstate` davranışı (yalnız temizlik listesi genişler).
- E-posta CTA linklerinin formatı (`?tab=X&monitor=id`) — geriye dönük çalışmalı.
- Polling (`useVisibleInterval`) ve `usePagination`'ın clamp/reset davranışı.
- Backend — bu geliştirme %100 frontend.

---

## 5. Testler (zorunlu)

Vitest + `test-utils.jsx#render()`; API `vi.mock('../api/client', ...)`; URL kurulumu için `window.history.replaceState({}, '', '/?tab=keyword&group=X')` deseni; her testin sonunda URL'i temizle (test izolasyonu). `navigator.clipboard` `vi.stubGlobal`/`Object.defineProperty` ile mock'lanır.

### 5.1 `src/test/useUrlQuerySync.test.jsx` (yeni)
- Eşlemedeki dolu değerler URL'e yazılır; null değerler paramı siler; diğer paramlar (`tab`) korunur.
- Varsayılan değer → param YOK (temiz URL).
- Debounce: 3 hızlı değişimde tek `replaceState`, son değer kazanır (fake timers).
- `readUrlParam`/`readUrlInt`: mevcut param, eksik param, bozuk int (`abc`, `-5`) → fallback.
- `replaceState` fırlatırsa (rate-limit simülasyonu) hook patlamaz.

### 5.2 `src/test/CopyLinkButton.test.jsx` (yeni)
- Tık → `clipboard.writeText` tam `window.location.href` ile çağrılır; toast görünür.
- Clipboard reddederse fallback yolu çalışır (execCommand mock).
- `aria-label` TR/EN doğru.

### 5.3 Sayfa entegrasyon testleri (mevcut dosyalara ekle — en az Keyword + Http tam set, diğerlerinde çekirdek senaryo)
- **URL → ekran**: `/?tab=keyword&group=callcenterfacewebmon` ile mount + mock'ta 2 grup → yalnız o grubun monitörleri render olur; grup seçicisi o değeri gösterir.
- **Ekran → URL**: grup filtresini değiştir → URL'de `group=` güncellenir; "Tümü"ne dön → param silinir.
- Arama yaz → debounce sonrası `q=` yazılır.
- `/?...&page=2&ps=50` ile mount (120 mock kayıt) → 2. sayfa dilimi render olur (51–100); `ps` localStorage tercihinI ezer.
- Filtre değişince sayfa 1'e döner VE URL'den `page` silinir (resetDeps + sync birlikte).
- **Modal kalıcılığı**: `/?tab=keyword&monitor=42` → modal açılır ve param URL'de KALIR; modal kapat → `monitor` silinir. Karttan modal aç → `monitor=<id>` yazılır.
- **Sekme temizliği** (`App` testi): keyword'de `group` doluyken sekme değiştir → URL'de `group`/`q`/`page`/`monitor` kalmaz, `tab` yenisi olur.
- **Geriye dönük**: eski format `/?tab=http&monitor=5` modal açar (e-posta CTA regresyonu).
- Polling turu (mock `load` ikinci kez döner) → URL ve sayfa konumu değişmez.

### 5.4 Genel
- `i18n-parity.test.jsx` yeşil (yeni `share.*` anahtarları TR+EN).
- Tüm mevcut testler yeşil: `npm run test`; `npm run build` hatasız.

---

## 6. Kabul Kriterleri

- [ ] Keyword İzleme'de `callcenterfacewebmon` grubu seçiliyken adres çubuğundaki URL kopyalanıp başka tarayıcıda açıldığında **aynı filtrelenmiş görünüm** geliyor (asıl senaryo).
- [ ] 8 izleme sayfası + Uptime'da filtre/arama/stat/sayfa/detay-modalı URL'e anlık yansıyor; varsayılan değerler URL'i kirletmiyor.
- [ ] Her izleme sayfasında ve detay modalında "Bağlantıyı Kopyala" butonu var; tık → pano + toast.
- [ ] `?monitor=` artık modal açıkken URL'de kalıyor; eski e-posta linkleri çalışmaya devam ediyor.
- [ ] Sekme değişince önceki sayfanın paramları temizleniyor; Geri/İleri sekme gezintisi bozulmadı.
- [ ] Arama yazarken URL yazımı debounce'lu; konsolda history rate-limit hatası yok.
- [ ] Faz B görünümleri de (yapıldıysa) aynı param sözlüğünü kullanıyor.
- [ ] `npm run test` + `npm run build` + i18n parity yeşil; CHANGELOG.md'de kullanıcıya dönük TR madde.

---

## 7. Çalışma Kuralları

1. Sıra: altyapı (hook + buton + App.jsx temizlik + testleri) → KeywordMonitorPage (senaryoyu uçtan uca doğrula) → kalan sayfalar tek tek, her birinden sonra o sayfanın testi (`npx vitest run src/test/KeywordMonitorPage.test.jsx`).
2. `VERSION` / `Chart.yaml` DOKUNMA (CI yönetiyor). Commit: `feat(ui): izleme sayfalarında paylaşılabilir URL (derin bağlantı) ve bağlantı kopyalama`.
3. Kod stili: mevcut desenlere uy — fonksiyonel bileşenler, `useT(key)`, lucide-react (emoji yasak), i18n string'lerinde çıplak `*` yasak, gerçek `fetch` testlerden kaçmaz.
4. Kapanışta kısa rapor: hangi sayfalara hangi paramlar bağlandı + örnek 3 paylaşım URL'i + test/build çıktısı özeti.

# GELİŞTİRME KOMUTU — Tüm izleme türlerine "Duplicate / Kopyala" özelliği

> Bu dosyayı olduğu gibi Claude Code'a (veya geliştiriciye) ver. Repo: `D:\cert-monitor`.

---

## Görev özeti

CertMonitor'da bir izleme kartının üzerinden **tek tıkla kopya (duplicate) oluşturma** özelliği ekle. Kullanıcı senaryosu: elinde `callcenterfacewebmon1.example.com/...` gibi 30+ benzer URL var; her sunucu için formu sıfırdan doldurmak yerine mevcut karttaki **Kopyala** butonuna basacak, form mevcut izlemenin birebir kopyasıyla (isim, URL, takım, grup, interval, bildirim ayarları, eşikler, keyword/koşul, etiketler vb.) dolu açılacak, kullanıcı sadece URL'deki `1`'i `2` yapıp kaydedecek. Hiçbir alanı yeniden girmek zorunda kalmayacak. Kullanıcı hiçbir değişiklik yapmadan kaydederse backend "zaten izleniyor" hatası dönecek ve mükerrer kayıt OLUŞMAYACAK.

Özellik şu 8 izleme sayfasının **hepsine** aynı davranışla eklenecek:

| Sayfa (frontend/src/components/) | Create endpoint | Mevcut mükerrer koruması (create) |
|---|---|---|
| `HttpMonitorPage.jsx` | `POST /api/monitoring/http` | ✅ `httpMonitorRepo.existsDuplicate(url, teamId, null)` |
| `PageMonitorPage.jsx` | `POST /api/monitoring/page` | ✅ url + team |
| `KeywordMonitorPage.jsx` | `POST /api/monitoring/keyword` | ❌ **YOK — bu görevde eklenecek** |
| `PingMonitorPage.jsx` | `POST /api/monitoring/ping` | ✅ host + team |
| `PortMonitorPage.jsx` | `POST /api/monitoring/port` | ✅ host:port ("Bu host:port zaten izleniyor") |
| `DnsMonitorPage.jsx` | `POST /api/monitoring/dns` | ✅ domain + recordType |
| `DomainMonitorPage.jsx` | `POST /api/monitoring/domain` | ✅ registrable domain + team |
| `ScriptedMonitorPage.jsx` | `POST /api/monitoring/scripted` | ✅ name + team |

**Kapsam dışı (kullanıcıya raporla, kod yazma):** `UptimePage.jsx` ve sertifika genel görünümü türetilmiş/salt-okunur görünümlerdir, kendi create formları yoktur — Duplicate butonu eklenemez ve gerekmez (sertifika izlemesi Port(443) monitörü üzerinden yürüdüğü için Port sayfasındaki Duplicate bu ihtiyacı karşılar). `IncidentHistoryPage`, `MaintenanceWindowsPage` vb. izleme tanımı olmadığı için kapsam dışıdır.

---

## Mevcut kod deseni (buna uy, yeniden icat etme)

Her monitör sayfası aynı iskelete sahip — örnek `HttpMonitorPage.jsx`:

- `const [modal, setModal] = useState(null)` — `null` | `'new'` | monitör objesi (edit).
- `openNew()` → `setForm({...emptyForm, ...})` + `setModal('new')`
- `openEdit(m)` → entity'nin snake_case alanlarını form state'ine map eder (`m.expected_status → form.expectedStatus` vb.) + `setModal(m)`
- `save()` → `modal === 'new' ? api.monitoring.createXxx(payload) : api.monitoring.updateXxx(modal.id, payload)`; hata durumunda `toast.error(res?.error)` zaten var — backend'in "zaten izleniyor" mesajı otomatik olarak kullanıcıya görünür.
- Kart aksiyonları: `<button className="btn btn-sm mon-btn-check" ...><Play/></button>` ve `<button className="btn btn-sm mon-btn-edit" onClick={() => openEdit(m)}><Pencil/></button>` yan yana durur. İkonlar `lucide-react`'ten gelir.

---

## FRONTEND — yapılacaklar

### 1. Ortak yardımcı: `frontend/src/utils/duplicateName.js` (yeni dosya)

```js
// "Web Sunucu 1"          -> "Web Sunucu 1 (Kopya)"
// "Web Sunucu 1 (Kopya)"  -> "Web Sunucu 1 (Kopya 2)"
// "Web Sunucu 1 (Kopya 2)"-> "Web Sunucu 1 (Kopya 3)"
export function duplicateName(name) { ... }
```

Regex ile ` \(Kopya( \d+)?\)$` yakala, sayacı artır. Boş/null isimde `'(Kopya)'` döndür. Buna küçük bir vitest testi yaz (`src/test/duplicateName.test.js`).

### 2. Her sayfaya `openDuplicate(m)` fonksiyonu

`openEdit(m)`'in **kendi sayfasındaki** alan eşlemesini birebir yeniden kullan (her sayfanın form şekli farklı — DNS'te recordType, Keyword'de keyword+koşul, Scripted'da script alanları vb.; eşlemeyi kopyala, ortaklaştırmaya çalışıp alan kaçırma). Farklar:

- `setModal('new')` — kayıt **create** olarak gidecek, update değil.
- `name`: `duplicateName(m.name || m.url || m.host || ...)`.
- Kopyalanacaklar: URL/host/domain, method, expected status, keyword/koşul, custom headers, takım, grup, etiketler, bildirim ayarları (notifyEmail, SSL/domain reminder'ları), interval, timeout, confirm/recovery ayarları, `active` — yani formda görünen **her şey**.
- Kopyalanmayacaklar: `id`, durum/istatistik alanları, `created_at/updated_at`, geçmiş/incident verisi (bunlar zaten form state'inde yok; forma sızdırma).
- Yeni bir state ekle: `const [dupSource, setDupSource] = useState(null)` — `openDuplicate` içinde `setDupSource(m)`, `openNew`/`openEdit`/`closeEdit` içinde `setDupSource(null)`.

### 3. Kart üzerinde Kopyala butonu

Her sayfada Check/Edit butonlarının yanına, **Edit butonuyla aynı görünürlük/permission koşuluyla**:

```jsx
<button className="btn btn-sm mon-btn-edit" onClick={() => openDuplicate(m)}
        title={t('mon.duplicate')}><Copy size={12} /></button>
```

`Copy` ikonunu `lucide-react`'ten import et. Edit butonu bir permission/rol koşuluyla gizleniyorsa Kopyala da aynı koşulla gizlensin (backend zaten `monitoring.crud/edit` istiyor).

### 4. Modal'da "Kopya" belirteci

Modal başlığında, duplicate modundayken (`dupSource != null`) başlık `t('xxx.modalNew')` yanına küçük bir rozet: `<span className="badge">{t('mon.duplicateBadge')}</span>` ve altına tek satır ipucu: `t('mon.duplicateHint')` → TR: "Kaynak izlemenin birebir kopyası. Genelde sadece URL/host alanını değiştirip kaydetmeniz yeterli." Mevcut badge/hint class'larından uygun olanı kullan, yeni CSS yazma; yoksa `App.css`'e minimal bir class ekle. URL/host input'una duplicate modunda otomatik focus ver (`autoFocus` veya ref ile) — kullanıcının değiştireceği alan orası.

### 5. i18n

`frontend/src/i18n/index.jsx` içine **hem TR hem EN** ekle (parity testi `i18n-parity.test.jsx` kırılmasın):

- `mon.duplicate`: TR "Kopyala" / EN "Duplicate"
- `mon.duplicateBadge`: TR "Kopya" / EN "Copy"
- `mon.duplicateHint`: TR yukarıdaki metin / EN karşılığı

Sayfa-özel anahtar gerekiyorsa aynı disiplinle ekle.

---

## BACKEND — yapılacaklar

### 6. Keyword monitöre mükerrer koruması (eksik olan tek tür)

`MonitoringController.createKeyword` (`POST /api/monitoring/keyword`) şu an hiç duplicate kontrolü yapmıyor. Diğer türlerle aynı desende ekle:

- `KeywordMonitorRepository`'ye `existsDuplicate(String url, String keyword, Long teamId, Long excludeId)` sorgusu ekle (HttpMonitorRepository'deki `existsDuplicate` deseninin birebir benzeri; url karşılaştırmasını mevcut desen neyse — case/trim normalizasyonu dahil — ona uydur).
- `createKeyword` içinde: `if (keywordMonitorRepo.existsDuplicate(url, keyword, teamId, null)) return badRequest("Bu URL ve anahtar kelime bu takımda zaten izleniyor; mükerrer keyword monitörü oluşturulamaz.");`
- `updateKeyword` içinde de (ping'in PUT'undaki gibi, `excludeId = id` ile) aynı kontrolü uygula — edit ile mükerrere dönüşmeyi de engelle.
- Aynılık anahtarı **url + keyword + team**: aynı URL'i farklı keyword ile izlemek meşru bir senaryodur, engellenmemeli.

### 7. Diğer türlerde davranışı doğrula (kod değişikliği beklenmiyor)

http/page/ping/port/dns/domain/scripted create endpoint'lerindeki mevcut `existsDuplicate` / "zaten izleniyor" kontrollerinin duplicate akışında doğru hata mesajı döndürdüğünü testle doğrula. Backend otoritatiftir; frontend'e ayrıca ön-kontrol ekleme (kullanıcı URL'i değiştirmeden kaydederse toast'ta backend mesajı görünür, bu yeterli ve istenen davranış).

### 8. Değişmeyecekler

- Yeni endpoint ekleme — duplicate tamamen mevcut create endpoint'leri üzerinden çalışır; audit tarafında normal `MONITOR_CREATE` kaydı düşer, `activityLog.recordLifecycle(..., "CREATED", ...)` aynen çalışır. Ayrı bir "DUPLICATED" audit tipi ekleme.
- Permission modeli: `permissionService.require(session, "monitoring.crud", "edit")` zaten create'i koruyor; `PermissionCatalog`'a yeni resource key ekleme (gerekmiyor).

---

## TESTLER

1. `src/test/duplicateName.test.js` — isim sonek mantığı (boş isim, ilk kopya, ardışık kopyalar).
2. Her sayfanın mevcut test dosyasına (`HttpMonitorPage.test.jsx`, `PingMonitorPage.test.jsx`, `PortMonitorPage.test.jsx`, `KeywordMonitorPage.test.jsx`, `PageMonitorPage.test.jsx`, `DnsMonitorPage.test.jsx`, `DomainMonitorPage.test.jsx`, `ScriptedMonitorPage.test.jsx`) en az bir duplicate testi: Kopyala'ya tıkla → modal açılır, alanlar kaynaktan dolu, isim "(Kopya)" sonekli, "Kopya" rozeti görünür → kaydet → **create** API'si çağrılır (update değil). Bir sayfada da "backend 'zaten izleniyor' dönerse toast.error gösterilir" senaryosunu test et.
3. `i18n-parity.test.jsx` yeşil kalmalı.
4. Backend: keyword duplicate reddi için controller/repository testi (mevcut backend test düzenine uygun): aynı url+keyword+team ikinci kez POST → 400 + mesaj; farklı keyword aynı url → 200; PUT ile mükerrere dönüştürme → 400.
5. `cd frontend && npm run test` ve `cd backend && mvn -B clean verify` yeşil.

## KABUL KRİTERLERİ

1. 8 sayfanın hepsinde kartta Kopyala butonu var; tıklayınca form kaynağın birebir kopyasıyla "yeni kayıt" modunda açılıyor; başlıkta "Kopya" rozeti; URL/host alanı odaklı.
2. Sadece URL/host değiştirilip kaydedilince tüm diğer ayarlar (takım, grup, interval, bildirim, eşikler, etiketler, koşullar) kaynakla aynı olan yeni bir izleme oluşuyor.
3. Hiçbir alan değiştirilmeden kaydedilirse kayıt oluşmuyor, backend'in "zaten izleniyor" mesajı toast olarak görünüyor; modal açık kalıyor (kullanıcı URL'i düzeltip tekrar kaydedebilmeli).
4. Keyword türünde de artık mükerrer create/update engelleniyor.
5. TR/EN çeviriler eksiksiz; tüm frontend ve backend testleri geçiyor.
6. Uptime/sertifika görünümlerine buton eklenmediği ve nedeninin ne olduğu PR/commit açıklamasında belirtiliyor.

## UYGULAMA SIRASI ÖNERİSİ

Önce `HttpMonitorPage`'de uçtan uca bitir (helper + buton + rozet + i18n + test), davranışı doğrula; sonra aynı deseni diğer 7 sayfaya sırayla uygula; en son backend keyword kontrolü + testleri. Her sayfada `openEdit`'in alan listesiyle `openDuplicate`'inkini satır satır karşılaştırıp alan kaçmadığını kontrol et.

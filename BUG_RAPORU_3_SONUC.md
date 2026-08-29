# Bug Raporu Turu-3 — Uygulama Sonucu

**Tarih:** 2026-08-29 · **Kapsam:** Y9, O15, O16, D19/D20/D22 (D21 kapsam dışı — kullanıcı kararı)
**Sonuç:** 6 bulgunun 6'sı da gerçekti ve düzeltildi. Ek olarak **raporda olmayan 7. bir vaka**
tarama kapısı tarafından bulundu.

---

## 1. Raporda olmayan bulgu: Y9'un İKİNCİ vakası

Y9 için kurduğum kaynak-tarama kapısı (`paginationBase.test.js`) ilk çalıştırmasında **ikinci bir
ihlal** buldu:

```
admin/MonitorChangesConsole.jsx → page={page}
```

Aynı kusur, aynı biçimde: `page` state'i `useState(0)`, API'ye `:83`'te 0-tabanlı gidiyor,
`rangeStart={page * size + 1}` bunu doğruluyor — ama widget'a dönüşümsüz veriliyordu. Yani
**İzleme Değişiklikleri konsolunun** sayfalaması da bir kaydıktı. Rapor bu dosyayı kaçırmıştı;
kapı olmasaydı ben de kaçıracaktım. İkisi de düzeltildi.

## 2. Raporun iki çözüm önerisi uygulanmadı (uygulansaydı yeni hata üretecekti)

### O16 — `MonitorStatsSection total={scoped.length}`

`MonitorStatsSection.jsx:23` prop'u açıkça "ham monitör sayısı (**filtre ÖNCESİ**)" diye
belgeliyor ve tek kullanımı `hasMonitors = !loading && total > 0` — yani şeridin çizilip
çizilmeyeceğine karar veriyor. Kapsam sayısı verilseydi **filtre hiçbir şey eşleştirmediğinde
istatistik şeridi tamamen kaybolur** ve kullanıcı filtreyi şeritten temizleyemezdi.
Yalnız sayaçlar `scoped`'a taşındı; prop `monitors.length` kaldı. Bu senaryo ayrıca teste
bağlandı (aşağıdaki mutasyon `o16_wrongfix`).

### D22 — koşulsuz `useEffect(() => setTeams(teamsProp), [teamsProp])`

Bu öneri **sonsuz render döngüsü** üretiyor. `teamsProp = []` varsayılanı (ve satır-içi dizi
prop'ları) her render'da yeni kimlik → efekt tetiklenir → `setTeams` → render → yeni kimlik…
Mutasyon turunda bunu ölçtüm: test süiti kırmızıya düşmedi, **hiç bitmedi** (iki kez 6+ dakikada
zaman aşımı). Ayrıca `canManage` kullanıcılarda mount'ta `api.admin.getTeams()` ile çekilen
takım-kapsamlı listeyi de ezerdi.

Uygulanan: senkron **yalnız sahibi olmayan durumda** —
`useEffect(() => { if (!canManage) setTeams(teamsProp) }, [canManage, teamsProp])`.

## 3. O15'te raporun teşhisi eksikti — ikinci kusur ondalıkla çözülmüyor

Rapor iki sonuç sayıyordu: (a) ondalık kaybı, (b) ekran/alarm çelişkisi (`rate=94.96` →
pano 95 "sağlıklı", alarm AÇIK). **`/10.0`'a geçmek (b)'yi çözmüyor**: `Math.round(949.6)/10.0`
yine `95.0`. Tamsayı bölmesi zaten aşağı kırpıyordu, yani çelişki penceresi `[94.95, 95.0)` her
iki durumda da aynı.

Gerçek çözüm yuvarlamayı bırakmak: `Math.floor(rate * 10) / 10.0`. Gösterilen oran artık gerçek
oranı asla olduğundan iyi göstermez, dolayısıyla eşiği yukarı doğru geçemez. Projede aynı
gerekçenin emsali var — `WeeklyAvailabilityReportService`, `pct >= 100 && up < total` iken 99.99
yazıyor. **Bu, raporun literal önerisinin ötesine geçen bilinçli bir karardır.**

---

## 4. Bulgu → düzeltme → test eşlemesi

| # | Düzeltme | Kapı |
|---|---|---|
| **Y9** | `ChangeHistoryTab.jsx` + **`MonitorChangesConsole.jsx`**: `page={page + 1}` / `onPageChange={p => setPage(p - 1)}`. `rangeStart`/`rangeEnd` 0-tabanlı kaldı | `ChangeHistoryTab.test.jsx` +3 (1. sayfa aktif & "önceki" kapalı · "2" → API `page=1` · "Son" → `page=2`, "sonraki" kapalı) + **yeni `paginationBase.test.js`** (24 çağrı yerini tarar; kapı boşa düşerse kendini de kırar) |
| **O15** | `ExtendedHealthService:329` `Math.floor(rate * 10) / 10.0`, alan `double`. Alarm ham `rate` ile hesaplanmaya devam eder | `ExtendedHealthServiceTest` +3 (967/1000 → 96.7 · 94.96 → 94.9 **ve** alarm AÇIK · deneme yoksa 100.0) |
| **O16** | `DnsMonitorPage`/`PortMonitorPage`: tek memo ikiye ayrıldı — `scoped` (takım+grup+arama) ve `filtered` (`scoped` + statFilter). Sayaçlar `scoped`'dan; `statFilter` bilerek dışarıda (aksi halde seçili olmayan her kart 0 okur) | Her iki sayfa testine +2 (arama sayaçları daraltır · **boş sonuçta şerit yine çizilir**) |
| **D19** | `MetricsService`: `lastGcMs = -1` sentineli; ilk örnek taban kurar, delta 0 | `MetricsServiceTest` +2 |
| **D20** | `useCheckHistory:122` `csvParams.days` ifadesi `load():66` ile **birebir aynı** | `CheckHistoryTab.test.jsx` +1 (Özel Aralık uygulanmadan CSV'de `days` YOK) |
| **D22** | `!canManage` koşullu prop senkronu (yukarıdaki gerekçe) | `InventoryManager.test.jsx` +2 (AUDIT'te prop senkronlanır · ADMIN'de çekilen liste EZİLMEZ) |

**Yan temizlik:** "sayaç/istatistikler tam listeden hesaplanmaya devam eder" yorumu **8 izleme
sayfasına** kopyalanmıştı ve artık yanlıştı — O16'nın kök sebebi büyük olasılıkla buydu (talimat
gibi okunuyor). Sekizi de doğru kuralla değiştirildi.

## 5. Mevcut testlerde zorunlu değişiklik

O15 yanıt tipini `long` → `double` yaptığı için **5 mevcut assertion** `(Long) result.get("rate")`
cast'i yüzünden kırıldı. Cast `((Number) …).doubleValue()` yapıldı; **beklenen değerler aynen
korundu** (100 → 100.0, 96 → 96.0, 94 → 94.0). İddia değişmedi, yalnız tip.

## 6. Doğrulama

* **Backend:** `mvn -B verify` → **3111 test yeşil**, 0 hata (öncesi 3106).
* **Frontend:** 163 dosya / **1425 test yeşil** (öncesi 1412), `lint` 0 hata, kapsam tabanı tamam,
  `build` başarılı.
* **Mutasyon turu — 10/10 KIRMIZI:** `y9_change` · `y9_console` · `o15_intdiv` · `o15_round`
  (yukarı yuvarlama) · `o16_dns` · `o16_port` · `o16_wrongfix` (raporun yanlış önerisi) · `d19` ·
  `d20` · `d22` (bu kırmızıya değil **sonsuz döngüye** düştü). Silme mutasyonlarında boş-dize
  replace kullanılmadı; tur sonunda `grep MUTASYON` + `git diff` ile kalıntı taraması yapıldı —
  temiz.
* **Kural 0:** diff taraması temiz.
* **Yerel:** jar yeniden paketlendi; backend `/health` UP, Vite 5173 ayakta.

## 7. Tarayıcıda doğrulanması gerekenler

1. Bir izlemenin **"Değişiklik Geçmişi"** sekmesinde 2. sayfaya git → beklenen kayıtlar gelsin.
2. **Kayıtlar → İzleme Değişiklikleri** konsolunda aynı kontrol (ikinci vaka).
3. **DNS ve Port** sayfalarında takım/arama filtresi seç → kartlar daralsın; boş sonuçta şerit
   kaybolmasın.
4. **Sistem Sağlığı** → SMTP başarı oranı ondalıklı görünsün.

## 8. Kapsam dışı

* **D21** (`ScriptedMonitorPage.jsx:1620` `key={i}`) — gerçek, ama düzeltmesi 7 noktada `_id`
  taşımayı gerektiriyor (5 hidrasyon noktası + `addEnvRow` + `envPayload`'da temizleme); biri
  atlanırsa `key={undefined}`. Kazanım yalnız ortadaki satır silinince odak sıçraması. Kullanıcı
  kararıyla ayrı bir işe bırakıldı.
* Önceki turlardan devreden **D10** (UserDirectory `photoBase64` projeksiyonu) ve **D11** (dolu
  prod tablolarında unique index) hâlâ açık.

> **Not:** `_review_src.tar.gz` çalışma ağacında değişmiş görünüyor; bu turda ona dokunmadım.
> Release'de `git add -A` ile commit'e girmemesi için önceden bakılmalı.

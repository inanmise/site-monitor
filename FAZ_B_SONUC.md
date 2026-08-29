# Faz B — Standartlaştırma

**Tarih:** 2026-08-29 · **Kapsam:** B1 (konu 1), B2 (konu 1★), B3 (konu 4)
**Durum:** **B1 ve B2 tamamlandı.** **B3 kısmen** — ayar/altyapı tarafı bitti, manuel çalıştırmanın
değerlendirme hattına alınması YAPILMADI (gerekçesi ve tasarımı aşağıda).

---

## B1 · Ortak bildirim bloğu + aralık çubuğu — 9/9 form ✅

**Konu 1'in gerçek kök nedeni bulundu** (ilk raporumda "prod eski build koşuyor olabilir" demiştim;
bu YANLIŞTI — hata mevcut kaynakta):

`KeywordMonitorPage` ve `PortMonitorPage`'de webhook döşemesi `kw-channel--disabled` /
`port-channel--disabled` sınıfı taşıyordu. CSS: `opacity: .55; cursor: not-allowed`. Kutu
teknik olarak ÇALIŞIYORDU ama soluk çiziliyor ve imleç "yasak" gösteriyordu — kullanıcı haklı
olarak "tikli ama değiştiremiyorum" dedi. **Görsel bir yalandı.**

**Yeni paylaşılan bileşenler:**
* `ui/NotifyChannels.jsx` — dört döşeme + **bildirim grubu** aynı blokta. Hedef satırı doğruyu
  söyler: grup seçiliyse grubun adı, değilse takım adı. Yalnız SMS ve Sesli arama pasiftir.
* `ui/IntervalSlider.jsx` — aralık listesi **prop** (türlerin tabanı bilinçli farklı: Domain saat
  ölçeğinde, DNS/Ping 30 sn'ye inebilir, Scripted 1 dk). Listede olmayan değer çubuğu başa
  düşürmez, en yakın seçenek gösterilir.

**Dokuz formun tamamı** bu ikisini kullanıyor. DNS/Ping/Domain'de "E-posta" kutusu ilk kez var
(alan bu türlerde hiç yoktu: entity + `ALTER TABLE` + controller okuma/yazma eklendi).

**Yeni kapı — `notifyBlockStandard.test.js`:** dokuz form da ortak bileşenleri kullanır · hiçbir
form kendi kanal ızgarasını/aralık çubuğunu yeniden yazmaz · **webhook döşemesi pasif olamaz**
(görsel yalan kapısı, mutasyonla doğrulandı).

## B2 · `notify_email` artık gerçekten çalışıyor ✅

Bayrak backend'de hiçbir yerde okunmuyordu. `push_disabled` deseninin eşleniği kuruldu:
`SchedulerService.mailCtx` → `mail_disabled` damgası, `EscalationService` okuyup maili atlıyor.

**Yol boyunca ikinci kusur:** push tetiği metodun sonundaydı ve "alıcı yok" dalı erken `return`
ediyordu — mail gitmediği her durumda **webhook de sessizce düşüyordu**. `notify_email` uygulanınca
"mail yok" yaygın hâle geleceği için bu hatayla yaşanamazdı. Tetik ayrıldı; **e-postayı kapatmak
webhook'u kapatmıyor.**

**Sessiz kesinti yok:** `Boolean.FALSE.equals(...)` — kolonu sonradan eklenen eski satırlar `null`
taşır ve mail almaya devam eder. Prod'da saymak isterseniz SQL bu dosyanın sonunda.

## B3 · Doğrulama/kurtarma — ayar tarafı ✅, manuel çalıştırma ⚠️ YAPILMADI

### Yapılan

* `DnsMonitor` ve `DomainMonitor`'a dört alan eklendi (`confirmAttempts`,
  `confirmIntervalSeconds`, `recoveryChecks`, `recoveryIntervalSeconds`) — entity + idempotent
  `ALTER TABLE` + controller okuma/yazma + form alanları + ortak `verify.*` i18n ailesi.
* `SchedulerService.confirmCtx(...)` bu ayarları DNS/Domain sweep'lerinin ctx'ine basıyor;
  `MonitoringOutageService` zaten okuyor.
* **`createDns` kanal bayraklarını HİÇ okumuyordu** — "Kopyala" akışında e-posta/webhook KAPALI
  bir izlemenin kopyası AÇIK doğuyordu. Düzeltildi ve mutasyonla pinlendi.

### Bir teşhis düzeltmesi (kendi ilk yorumumu düzeltiyorum)

Kod yorumlarına önce "bu iki türde tek anlık hata doğrudan alarm açıyordu" yazmıştım. **Yanlıştı:**
global varsayılan (`site.monitor.uptime.confirm-attempts`) zaten **3**'tü ve DNS/Domain ona
düşüyordu. Eksik olan **davranış değil, izleme-bazlı ayarlanabilirlikti**. Yorumlar düzeltildi.

**Tek gerçek davranış değişikliği kurtarma tarafında:** global varsayılan `recovery-checks` **1**
(ilk başarılı kontrolde kapat), izleme varsayılanı ise diğer yedi türle hizalı **3**. Yani yeni
DNS/Domain izlemeleri biraz daha geç "kurtuldu" sayılacak. Mevcut satırlar `null` taşıdığı için
**etkilenmez**; bu bilinçli bir hizalama, isterseniz varsayılanı 1'e çekebiliriz.

### Yapılmayan: manuel çalıştırmanın alarm hattına alınması

Dokuz `/{tip}/{id}/check` ucu hâlâ tek kontrol yapıp sonucu kaydediyor; alarm açmıyor.
**Bilerek bu teslime almadım** — riskli ve kendi başına bir çalışma birimi:

* Doğrulama 3×30 sn sürüyor, yani uç **asenkron** olmalı ve kart "doğrulanıyor" durumunu
  göstermeli (mevcut `useRunningChecks`/`CheckRunningStrip` kullanılabilir).
* Değerlendirme girişi `MonitoringOutageService.handleSweepResults(alertType, items)`. **İyi haber:**
  domain bazında grupluyor ve yalnız listedeki domain'lere dokunuyor — tek öğeyle çağırmak diğer
  izlemelerin alarmlarını kapatmaz (bunu kaynakta doğruladım).
* **Ama bir tuzak var:** bulk-bastırma `networkDown / byDomain.size()` oranına bakıyor. Tek öğede
  oran 1.0; varsayılan `min-errors = 3` bugün koruyor, ama admin bunu 1 yaparsa her manuel
  çalıştırma bastırma olayı yazar. Manuel yol bu heuristiği **atlamalı**
  (`handleSweepResults(..., boolean manual)` aşırı yüklemesi + tek satırlık guard).
* Asıl maliyet: her türün sweep-ctx kurulumu `SchedulerService` içinde büyük döngülere gömülü;
  manuel yolun aynı ctx'i üretmesi için o kod tek-monitör metotlarına çıkarılmalı. Kopyalamak
  **sessiz sapma** üretir (ctx'te bir anahtar eksik kalırsa alarm yanlış takıma gider).

Kısacası: doğru iş, ama uzun bir oturumun sonunda aceleye getirilecek iş değil. Sıradaki adım.

---

## Doğrulama

* **Backend:** `mvn -B verify` → **3136 test yeşil**, 0 hata.
* **Frontend:** **1444 test yeşil**, `lint` 0 hata, kapsam tabanı tamam, `build` başarılı.
* **Mutasyon — 8/8 KIRMIZI:** `b2_stamp` · `b2_read` · `b2_indep` · `b1_toggle` · `b1_target` ·
  `b1_slider` · `b1_visual_lie` · `b3_createdns`. Kalıntı taraması temiz.
  > `b2_stamp` ilk turda **yeşil kaldı** ve gerçek bir boşluk gösterdi: alarm testi ctx'i elle
  > kuruyordu, yani "damga basılıyor mu" sorusunu hiçbir test sormuyordu. Kapatıldı.
* **Kural 0:** temiz. **Yerel:** jar yeniden paketlendi, backend + Vite yeniden başlatıldı.

## Zorunlu fixture güncellemeleri (iddialar değişmedi)

| Dosya | Sebep |
|---|---|
| DNS/Ping/Domain `Kopyala` testleri | Kopyalanan alan kümesi büyüdü (`notifyEmail` + 4 doğrulama alanı) |
| `monitorFormWiring.test.js` | Grup seçici artık ortak bloğun içinde — kural aynı, iki meşru bağlama biçimi |
| `ScriptedMonitorPage.form.test.jsx` | İki kutu ortak bloğa taşındı; ızgara sayaç tabanı 3 → 2 |
| `AdminControllerTest` · `EscalationServiceTest` | Faz A'daki imza/bağımlılık değişiklikleri |

## Sırada

1. **B3'ün kalanı** — manuel çalıştırma (yukarıdaki tasarım notlarıyla).
2. **Faz C (konu 8)** — DNS/Port kart görünümü.
3. **A3** — hâlâ sizden bir bakış bekliyor: açık alarmı olan bir sertifikayı silince Olaylar
   ekranında kayıt **"Çözüldü" mü, hâlâ "Açık" mı**?

## Ek: `notify_email = false` sayımı (prod'da çalıştırılabilir)

```sql
select 'http' t, count(*) from http_monitors where notify_email = false
union all select 'keyword',   count(*) from keyword_monitors   where notify_email = false
union all select 'page',      count(*) from page_monitors      where notify_email = false
union all select 'pagespeed', count(*) from pagespeed_monitors where notify_email = false
union all select 'port',      count(*) from port_monitors      where notify_email = false
union all select 'scripted',  count(*) from scripted_monitors  where notify_email = false;
```

**Commit yok** — açık "release" bekleniyor.

# Faz A — Bozuk Yeteneklerin Düzeltilmesi

**Tarih:** 2026-08-29 · **Kapsam:** A1 (konu 3), A2 (konu 2), A3 (konu 5), A4 (konu 6), A5 (konu 7)
**Durum:** A1 · A2 · A4-sonCuma · A5 **tamamlandı**. A3 ve A4-PDF **kısmen** — aşağıda gerekçesiyle.

---

## A1 · Takım PO'su ADMIN olunca hiçbir şey göremiyordu ✅

**Kök neden** (`UserService.computeViewTeamIds:364`): AD (LDAP) kullanıcısı ADMIN yapılınca görüş
alanı **yalnız astların takımları** oluyordu — kendi takımı dahil değil. Astı olmayan bir PO için
liste **boş** kalıyordu. `computeManageTeamIds:405` ise AD ADMIN'e **hiç** yönetim vermiyordu.
Yani rol yükseltmesi görünürlüğü ve yetkiyi **azaltıyordu**.

**Düzeltme:** her iki kapsam da `ownPlusSubordinateTeamIds(u)` = kendi üye olduğu takımlar ∪
astların takımları. Yerel/bootstrap ADMIN hâlâ `null` (global).

**Güvenlik sınırı korundu:** `SessionScope.isGlobalAdmin/isGlobalViewer` `viewTeamIds == null`
ile karar veriyor; AD ADMIN'in listesi **dolu** kaldığı için global admin olmaz — kimse fazladan
takım görmez. Bu ayrıca teste bağlandı.

> **Dağıtım notu:** oturum nitelikleri girişte hesaplanıyor. Mevcut oturumlar **yeniden giriş
> yapana kadar** eski kapsamı taşır.

**Testler:** `UserServiceTest` +4 (astı olmayan AD ADMIN görür/yönetir · çok takımlı tekilleştirme ·
global olmadığı). İki mevcut test **kasıtlı olarak** güncellendi — eski (hatalı) davranışı
sabitliyorlardı, A1'in amacı tam olarak onu değiştirmek.

## A2 · "Tekrar Bildir"de webhook + alıcı onay ekranı ✅

**Yapılanlar:**
* Onay pop-up'ı artık **kanal kanal**: e-posta ve webhook alıcıları ayrı listeleniyor, ayrı ayrı
  çıkarılabiliyor ("bu kişiye mail gitmesin ama push gitsin" meşru bir istek).
* **Gönderilemeyecek** webhook alıcıları da görünür — pasif satır + sebep (`RATE_LIMITED`,
  `SKIPPED_TEAM_OFF` …). Sessiz bir "gitmedi" yerine operatörün nedenini gördüğü liste.
* `POST /admin/alerts/{id}/re-notify` artık `excludeUsernames` alıyor; zincir
  `reNotify → reNotifyAsync → sendCombinedAlert → enqueueAlert` boyunca taşınıyor ve çıkarılan
  sicile **satır yazılmıyor**.
* Kanal kararı `channelBlockReason()` olarak ayrıldı: **gerçek gönderim ile önizleme aynı kodu
  paylaşıyor** — mail tarafındaki `resolveReNotifyTargets` disiplininin eşleniği. Önizleme,
  gönderilecek olandan sapamaz. Fallback takım da aynı kaynaktan (`reNotifyFallbackTeamId`).

**"Neden gitmiyordu" sorusu:** tetik zaten vardı ve RESEND dedupe'ı engellemiyordu; ret bir katman
kararından geliyor ve **teslimat günlüğüne yazılıyor**. Artık o sebep gönderim öncesi ekranda
görünüyor — prod'daki asıl sebep bir sonraki denemede doğrudan okunabilecek.

**Testler:** `UserPushServiceTest` +7, `AlertHistory.test.jsx` +2.

## A4 · Aylık envanter raporu

### Son cuma garantisi ✅ (kök neden bulundu)

`sendMonthlyReport:262` idempotensliği **AY** bazlıydı: `findByReportYearAndMonthNo(...).isPresent()`.
Ay içinde yapılan **manuel** bir gönderim, ayın **son cumasındaki planlı** gönderimi sessizce iptal
ediyordu. Oysa guard'ın amacı (sınıfın kendi yorumu) çok-pod'da kilit kaçarsa ikinci mailin
gitmemesi — bu **aynı gün** meselesidir, "bu ay bir kez" değil.

**Düzeltme:** `sentToday(year, month)` — mevcut tek-satır (yıl, ay) şeması korunuyor, ayrım
`sentAt` damgasından geliyor. Damga UTC yazıldığı, cron ise IST koştuğu için karşılaştırma
**IST gününde** yapılıyor. Ayrıştırılamayan damga gönderimi **engellemez** (guard kolaylık, kapı değil).

### PDF Türkçe ⚠️ mevcut derlemede ÜRETİLEMEDİ

Ölçümler:
* Roboto TTF'leri `src/main/resources/report-fonts/` altında **var**;
* paketlenmiş fat jar'ın içinde de **var** (`BOOT-INF/classes/report-fonts/…`);
* `InventoryPdfWriter` yerelde fontu **gömebiliyor**;
* `encodable()` Türkçe metni **bozmadan** geçiriyor (`şğıİÖÜÇçöüĞŞ` → aynen).

Yani **bugünkü kod doğru PDF üretiyor**. Prod'daki bozulma büyük olasılıkla daha eski bir imajdan
geliyor (konu 1'deki ekran görüntüsü de prod'un eski bir derleme koştuğunu düşündürüyor).

Bunu bir daha teşhis edilemez bırakmamak için: font yükleme başarısızlığı artık **sessiz değil** —
sebebiyle WARN'a yazılıyor ("PDF ASCII'ye indirgenecek, Türkçe kaybolur") ve üç kapı testi
eklendi. Prod'da bir sonraki rapor koşusunda log kesin cevabı verecek.

## A5 · Dashboard sertifika kartında silme ✅

`CertificateModal` başlığına silme eylemi eklendi; görünürlük `inventory.crud/edit` yetkisine bağlı
(backend kapısının aynısı) — yetkisi olmayana düğme **hiç çizilmiyor**. **Yeni uç açılmadı**:
mevcut `DELETE /admin/inventory/{id}` çağrılıyor, böylece denetim kaydı, soft-delete ve açık
alarmların kapatılması kendiliğinden miras kalıyor. Onay diyaloğu mevcut `showConfirm` deseninde.

## A3 · Envanter silince olay açık kalıyor ⚠️ bir dal elendi, kalanı tarayıcı doğrulaması istiyor

Kodda ölçülenler:
* Silme `deletedAt` **ve** `active=false` yazıyor, ardından `closeAlertsOnInventoryDelete` çağrılıyor
  → açık alarmlar `resolved=true` oluyor;
* süpürme yalnız `findByActiveTrue…` okuyor → **"sonraki süpürme yeniden açıyor" dalı ELENDİ**.

Geriye iki dal kalıyor ve ikisi de ekran davranışı: (a) eşleşme — `closeOpenAlerts` **domain**
eşitliğiyle çalışıyor, oysa HTTP/Keyword/Page alarmları **URL** anahtarlı; o alarmlar zaten
kapanmamalı (izleme hâlâ duruyor ve kendisi yeniden açar). (b) Olaylar ekranının süzgeç
varsayılanı / tazelenmemesi.

Hangisi olduğunu **kod okuyarak** ayırt edemiyorum; yanlış dalı düzeltmek gerçek bir alarmı
sessizce kapatma riski taşır. **Sizden bir ekran doğrulaması istiyorum:** açık alarmı olan bir
sertifikayı silin ve Olaylar ekranında o kaydın **"Çözüldü" mü yoksa hâlâ "Açık" mı** göründüğünü
söyleyin. Cevap tek dalı bırakır ve düzeltme dar olur.

---

## Doğrulama

* **Backend:** `mvn -B verify` → **3127 test yeşil**, 0 hata.
* **Frontend:** 163 dosya / **1429 test yeşil**, `lint` 0 hata, kapsam tabanı tamam, `build` başarılı.
* **Mutasyon turu — 7/7 KIRMIZI:** `a1_own` · `a1_manage` · `a2_exclude` · `a2_preview` ·
  `a4_month` · `a4_font` · `a5_delete`. Silme mutasyonlarında boş-dize replace kullanılmadı;
  tur sonunda `grep MUTASYON` kalıntı taraması **temiz**.
  > `a2_preview` ilk turda **yeşil kaldı** ve gerçek bir test boşluğunu ortaya çıkardı: önizlemede
  > yalnız erken (kanal kapalı) ve geç (alıcı yok) dallar pinliydi, **aradaki katman matrisi kararı**
  > değildi. Boşluk kapatıldı, mutasyon artık kırmızı.
* **Kural 0:** temiz. **Yerel:** jar yeniden paketlendi, backend + Vite yeniden başlatıldı.

## Zorunlu fixture güncellemeleri (iddialar değişmedi)

| Dosya | Sebep |
|---|---|
| `UserServiceTest` (2 test) | A1 kasıtlı davranış değişikliği — eski testler hatalı davranışı sabitliyordu |
| `AdminControllerTest` | Controller yeni bağımlılık aldı → `@MockitoBean UserPushService` |
| `EscalationServiceTest` (1 verify) | `enqueueAlert` imzası 5 parametreye çıktı; iddia aynı |
| `CertificateModal.test.jsx` (fixture) | `getInventoryByDomain` mock'unda `id` yoktu — üretim döndürüyor |

## Sırada

* **A3** — yukarıdaki ekran doğrulaması bekleniyor.
* **Faz B** — konu 1 (ortak bildirim bloğu + aralık çubuğu, `notify_email` bayrağının gerçekten
  uygulanması) ve konu 4 (doğrulama/kurtarma her türde + manuel çalıştırmada).
  > **B2 öncesi ölçüm sözü:** `notify_email` alanı yalnız 6 entity'de var (HTTP/Keyword/Page/
  > PageSpeed/Port/Scripted); DNS/Ping/Domain'de yok. Bayrağı uygulamadan önce prod'da kaç izlemede
  > `false` olduğunu size bildireceğim — sessiz mail kesintisi olmasın.
* **Faz C** — konu 8 (DNS/Port kart görünümü).

**Commit yok** — açık "release" bekleniyor.

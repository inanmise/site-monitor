# BUG REGRESYON TARAMASI — 2026-09-23 (yayın öncesi)

Kapsam: `backend/src/main/java` (411 dosya) + `frontend/src`. Yöntem: imza-güdümlü süpürme
(`/bug-regresyon`) — (A) bilinen bulguların düzeltilmiş imzası hâlâ yerinde mi, (B) aynı
anti-desen başka yüzeylerde tekrar ediyor mu. Her aday kaynakta **okunarak** doğrulandı; grep
eşleşmesi tek başına bulgu sayılmadı.

Taban: `BUG_RAPORU_2026-09-23.md` (aynı gün, 51 bulgu, 51/51 kapalı).

---

## (A) Baseline re-check — REGRESYON YOK

K1 ve Y1–Y12'nin düzeltilmiş imzalarının tamamı kaynakta duruyor; ORTA/DÜŞÜK örneklemi
(O1–O13, O21–O26, D4, D5, D10) de yerinde. Bir düzeltmenin geri alındığına dair tek bir işaret
yok. Tx'siz türetilmiş silme ve `ddl-auto` sapması kapıları da yeşil.

---

## (B) Yeni bulgular — 20 (hepsi bu sürümde düzeltildi)

### Backend

| # | Yer | Bulgu | Önem |
|---|---|---|---|
| B1 | `UserPushService` | Push outbox'ın açılış/periyodik drain'i YOK: restart ya da backoff penceresinde `PENDING` kalan satır bir sonraki alarma kadar (sessiz bir gecede saatlerce) askıda kalıyor. Satır `FAILED` bile olmuyor. Prod tek pod ve her sürüm bir restart. | YÜKSEK |
| B2 | `AuditController` zayıf-algoritma uçları | `{domain}` hiçbir takım kapısından geçmiyordu: kapsamlı müdür BAŞKA takımın zayıf kripto bulgusuna istisna yazabiliyor, başkasının istisnasını silebiliyor, ilgisiz takıma CRITICAL mail+push tetikleyebiliyordu. | YÜKSEK |
| B3 | `WeakAlgorithmReportService` + `WeeklyAvailabilityReportService` | Takvim günü UTC ile hesaplanıyordu: kurum saatine göre her gece 00:00–03:00 arasında bir gün geri. Süresi dolmuş istisna 3 saat daha "kabul edildi" görünüyor, biten alan adı 90 günlük pencereye girmiyordu. | ORTA |
| B4 | `SqlPlaygroundService` | Kullanıcı SQL'i salt-okunur OLMAYAN bağlantıda koşuyordu; savunma tamamen metinseldi (kara-liste). | ORTA |
| B5 | `EmailNotificationService.buildStormRecoveryText` | Düz metin partı "hâlâ erişilemeyen" listesinin kırpıldığını söylemiyordu (sabit 0): HTML okuyan "12 + 28 daha", düz metin okuyan yalnız 12 ad görüp listeyi TAM sanıyordu. | DÜŞÜK |
| B6 | `StormService` | Takıma özel listenin altındaki "+N monitör daha" ve "hâlâ erişilemeyen: N" sayaçları HESAP GENELİNDEN türüyordu: tek monitörü düşmüş takımın maili "ve 39 monitör daha" diyor, okuyan kendi 39 monitörünü sanıyordu. (Webhook partı zaten takım kapsamlıydı — aynı olay iki kanalda farklı rakam.) | DÜŞÜK |
| B7 | `MonitoringController:1533` | `(Boolean) r.getOrDefault("open", false)` — kardeşlerinin üçü de null-güvenli yazılmış; burada değildi. | DÜŞÜK |

### Frontend — hepsi tek bir sınıf: **satır kontrollerinin erişilebilir adı ve klavye erişimi**

Bu sınıf, sürümün başındaki ISSUE-002'nin ta kendisi: düzeltme bir yüzeyde uygulanmış, kardeş
yüzeylere süpürülmemişti.

| # | Yer | Bulgu | Önem |
|---|---|---|---|
| F1 | 9 izleme sayfası (`Http/Ping/Port/Dns/Keyword/PageSpeed/Page/Domain/Uptime`) | İzleme kartı tıklanabilir ama ne odaklanabilir ne adlandırılmış: **klavye kullanıcısı hiçbir kartın detayını açamıyordu**. Doğru sürüm `ScriptedMonitorPage`/`CertificateCard`'da duruyordu. | YÜKSEK |
| F2 | 9 kart ızgarası + `CertificatesTable` | Toplu seçim kutularının adı her satırda "Seç": 50 kartlık listede hangi monitörün seçildiği duyulmuyor, yanlış kayıt toplu silmeye girebiliyordu. | ORTA |
| F3 | 8 kebab menü çağrısı (`KebabMenu`) | Satır menüsünün adı her satırda aynı; 200 satırlık tabloda hangi kaydın silme menüsünde olunduğu duyulmuyordu. `rowLabel` ile satır kimliği eklendi (tooltip kısa kaldı). | YÜKSEK |
| F4 | `ActivityLog` | "İzlemeye git" düğmesi her satırda aynı adla. | ORTA |
| F5 | `DeviceHistoryPanel` | Oturum satırı genişletme düğmesi ayırt edilemiyordu (şüpheli oturumu gözle eşlemek gerekiyordu). | ORTA |
| F6 | `PageMonitorPage` | Kaynak dışlama düğmeleri satır adını taşımıyordu; yanlış kaynağı dışlamak sessiz izleme kaybı üretir. | ORTA |
| F7 | `InventoryToolbar` | Kayıtlı görünüm silme düğmesi her satırda aynı; yanlış görünümü silmek geri alınamaz. | ORTA |
| F8 | `ui/SearchableSelect` | Seçenek silme kontrolü `tabIndex` taşımıyor ve yalnız `onMouseDown` dinliyordu: **klavye/dokunmatik kullanıcısı seçeneği hiç silemiyordu**; adı da tekrarlıydı. | ORTA |
| F9 | `ui/TagInput`, `IncidentHistoryPage` (2), `ui/DateTimeField`, `pages/Login`, `admin/UserPushSettings` (2), `admin/BrandingSettings` | Sabit (i18n'siz) `aria-label`: TR arayüzde İngilizce okunuyor ve tanımı gereği her satırda aynı. Dekoratif renk kutusu `aria-hidden` yapıldı. | ORTA |
| F10 | `ScriptedMonitorPage` geçmiş satırı | Satırı açan kontrol `cursor:pointer` taşıyan bir `span`'di — k6 koşum detayı klavyeyle açılamıyordu. ISSUE-002'nin birebir düzeltilmemiş kardeşi. | ORTA |
| F11 | `KeywordMonitorPage` detay modali | ISSUE-001'in AYNI DOSYADAKİ kaçağı: `.filter(Boolean)` yok → `['', 'X-Api-Key']` başı virgülle başlayan kırık liste; `['','']` truthy olduğu için yedek metin de devreye girmiyordu. | DÜŞÜK |

**F9'un iki kalemini kod taraması değil, yeni kapı testi buldu** (`UserPushSettings`): süpürme
İngilizce sabitleri arıyordu, oradakiler Türkçe sabitti.

---

## Eklenen kapılar (sınıfı kapatanlar)

| Kapı | Ne pinliyor |
|---|---|
| `OrgCalendarDayGateTest` (backend) | Üretim kodunda `LocalDate.now()` zone'suz ya da UTC ile çağrılamaz — takvim günü kurum saatiyle. `LocalDateTime.now(UTC)` (zaman damgası) serbest. |
| `UserPushOutboxDrainGateTest` (backend) | Push outbox'ın açılış (`ApplicationReadyEvent`) + periyodik (`@Scheduled`) drain tetikleri HER ZAMAN var. |
| `AuditControllerTest` (3 yeni test) | Zayıf-algoritma yazma uçları takım kapsamlı; global admin/AUDIT davranışı daralmıyor. |
| `rowAccessibleNames.test.js` (frontend) | (1) `aria-label` sabit dizeye bağlanamaz; (2) tıklanabilir izleme kartı `role="button"` + `tabIndex` taşır. |
| `StormServiceTest` / `EmailNotificationServiceTest` (4 yeni test) | Fırtına sayaçları takım kapsamlı; çözüm mailinin düz metin partı kırpmayı söyler. |

---

## Temiz sınıflar (tarandı, başka örnek yok)

**Backend:** S2 Redirect SSRF (üretimde `Redirect.NORMAL` sıfır), S3 sınırsız gövde okuma
(tüm dış okumalar tavanlı), S4 SsrfGuard boşluğu (kullanıcı/envanter kaynaklı her hedefte guard),
S8 `@Column` ↔ `ADD COLUMN` (31 tablo karşılaştırıldı, öksüz kolon yok), S9 tx'siz repository
yazımı (19 türetilmiş silme + allow-list bayat değil), S10 Boolean unbox (B7 dışında),
S14 alarm/eskalasyon tutarlılığı, S16 SQL kara-liste/tavan, S1'in geri kalanı (49 controller'ın
tüm `@PathVariable` uçları tarandı).

**Frontend:** S6 sayfalama taban uyumsuzluğu (11 `PaginationBar` çağrısı), S7 chart ts-guard,
S11 fetch yarışı (ortak `useCheckHistory` hook'u seq korumalı), S12 türetilmiş state
senkronsuzluğu, S18 boş liste → i18n yer tutucusu (F11 dışında 46 `.replace` çağrısı korumalı),
TR/EN anahtar paritesi (tek yönlü fark yok).

---

## Kapanış eki (aynı gün, ikinci tur)

Raporda "ürün kararı bekliyor" diye bırakılan iki madde kullanıcı onayıyla kapatıldı:

**1. Zayıf Algoritma Raporunun OKUMA kapsamı — KAPANDI.** `build()` artık çağıranın görüş
kapsamını alıyor (`build(viewTeamIds)`); global admin/AUDIT için `null` = tüm takımlar, kapsamlı
müdür için yalnız kendi takımlarının (ya da alt-grup takımının) aktif alanları. Süzgeç tek noktada
(aktif envanter listesi) uygulanıyor: satırlar, TLS/zincir bulguları, dağılımlar, 2030 görünümü,
takım kırılımı ve tarama sayaçları kendiliğinden daralıyor. Sahipsiz alanlar (takımı olmayan)
kapsamlı kullanıcıya görünmüyor. **Trend ayrı süzgeç aldı** — o envanterden değil gözlem
tablosundan geliyor ve süzülmeseydi rapor gövdesi daralmışken `detected`/`resolved` satırlarında
başka takımların alan adlarını sızdırırdı. CSV dışa aktarma da aynı kapsamla çalışıyor.
Kapılar: `WeakAlgorithmReportServiceTest` (2 yeni test) + `AuditControllerTest` (kapsam servise
geçiyor mu).

**2. Test fixture'larındaki gerçek kimlik — TEMİZLENDİ.** Beş dosyada (dört frontend testi + bir
Java testi) gerçek kullanıcı adı, gösterim adı ve posta yerel-adı duruyordu; proje geleneğindeki
yer tutuculara çevrildi (`ali` / `Ali V` / `ali@example.com`, `ali.veli`). Toplam 22 geçiş.

   Tarama ayrıca **üretimde** bir kalıntı buldu: `SchedulerService`'teki
   `site.monitor.system-admin.email` `@Value` varsayılanı gerçek bir KİŞİSEL posta kutusuydu —
   ayar (Ayarlar → Genel) boş bırakıldığında kurumsal izleme aracının ağ kesintisi bildirimleri
   oraya gidiyordu. Aynı ayarın diğer sekiz tüketicisi zaten boş varsayılanla çalışıp gönderimi
   atlıyordu; bu tek istisna kardeşleriyle hizalandı (varsayılan boş + adres yoksa gönderme).

   `IdentityLeakGuardTest.FORBIDDEN` iki yeni terimle genişletildi (kullanıcı adı ve noktalı
   ad.soyad). **Bilinçli olarak yasaklanmayanlar:** soyadın tek başı — GitHub handle'ı
   (`ghcr.io/<handle>/site-monitor`, chart `home`/`sources`) onu içeriyor ve bu işlevsel bir
   değer; adın tek başı — dört harfli hece yüzlerce Türkçe sözcüğün içinde geçtiği için kapı
   sürekli yanlış yere ısırırdı. Tek yeni muafiyet `helm/site-monitor/Chart.yaml` (chart
   maintainer iletişimi = depo sahibinin kendi genel kimliği, paket metaverisi olarak gerçek
   olmak zorunda).

---
description: Kişi-bazlı Webhook Bildirim Kanalı — kurumsal bildirim API'sine {title, message, pipeline, userIds[sicil]} gövdesiyle POST atan, mail hattından TAMAMEN bağımsız ikinci kanal. Katmanlı aç/kapa (global vars. KAPALI → org-unvan → izleme tipi → takım → izleme), ayarlarda özel yönetim yüzeyi (URL/auth/timeout/filtreler/şablon editörü/test gönderimi/teslimat günlüğü), kısa-öz şablonlar (onaya sunulur), anti-loop + dedupe + fırtına bastırması, çok uzun saklama, alarm modalında kanal-ayrımlı teslimat görünümü, eksiksiz testler.
argument-hint: [tasarim|backend|frontend|hizli] (opsiyonel — boş bırakılırsa TAM kapsam koşulur)
---

# /webhook-bildirim — Kişi-Bazlı Webhook Bildirim Kanalı

Görevin: SiteMonitor'e, kurumsal bildirim API'sine sicil listesiyle toplu POST atan **ikinci bir
bildirim kanalı** eklemek. Referans istek gövdesi (Postman ekranı):
`{"title":"Site Monitor","message":"...","pipeline":"","userIds":["N.....","N....."]}` —
tek istekte ÇOKLU sicile toplu iletim. Ürün kararı netleşmiş çekirdek (kullanıcının sözleriyle):

- **Bağımsızlık pazarlık dışı:** webhook gönderilemedi diye MAİL ASLA aksamaz; mail gönderilemedi
  diye webhook aksamaz. İki kanal ayrı yollarda, ayrı hata yutma zarflarında, ayrı dayanıklılıkta.
- **Katmanlı aç/kapa:** global anahtar (varsayılan **KAPALI**) → org-unvan bazlı (Yönetici/Uzman/
  PO — hepsi varsayılan KAPALI) → izleme TİPİ bazlı → TAKIM bazlı → İZLEME bazlı. Herhangi bir
  katman kapalıysa gönderim yok (en kısıtlayıcı kazanır).
- **Ayarlarda özel yönetim yüzeyi:** URL + kimlik başlıkları + timeout'lar + tüm katman
  anahtarları + şablon editörü + **test gönderim ekranı** + zengin filtreli **teslimat günlüğü**
  (kime, ne zaman, hangi içerikle, hangi alarm için, sonuç ne) tek yerden.
- **Kısa-öz mesajlar:** mail değil push — "nerede, ne zaman, ne çalışmıyor / ne düzeldi" tek
  cümlede. Şablonlar ayarlardan düzenlenir ve **anında** etkilidir; önerilen metinler kullanıcı
  ONAYINA sunulur.
- **Seviyeler korunur:** KRİTİK/YÜKSEK/UYARI webhook mesajında da taşınır. Eskalasyon kuralları
  AYNEN: müdür yalnız YÜKSEK/KRİTİK'te — ve ancak unvanı webhook'a AÇILMIŞSA.
- **Anti-loop garanti:** aynı kullanıcıya aynı olay için tekrar tekrar mesaj ASLA; fırtına/bakım
  bastırmaları webhook'a da uygulanır; kullanıcı başına emniyet tavanı.
- **Çok uzun saklama:** teslimat günlüğü yıllar ölçeğinde tutulur; alarm modalındaki geçmişte
  mail ve webhook teslimatları AYRI AYRI, tüm detaylarıyla görünür.
- **Gizlilik kuralı:** commit mesajlarında, kod içi örneklerde, test fixture'larında ve SUNUCU
  loglarında GERÇEK sicil ya da kurum adı ASLA geçmez (örnek: `N00001`, `ornek-kurum`); sunucu
  logu yalnız ADET yazar ("3 alıcıya gönderildi"), sicil listesi yalnız DB/UI'da yaşar.

Yüzeysel iş KABUL EDİLMEZ: her faz kanıtla raporlanır; kararlar (K1–K11) tahmin edilmez,
seçenek + öneriyle kullanıcıya sorulur. Zaman bol — titizlik esas.

Kapsam argümanı: `$ARGUMENTS`
- Boş → tüm fazlar (0–7). `tasarim` → Faz 0 (karar notu + şablon onayı, kod yok).
- `backend` → 0,1,2,3,6,7. `frontend` → 0,4,5,6,7. `hizli` → 0–5 (bu özellikte ÖNERİLMEZ).

## Değişmez kurallar (her fazda geçerli)

1. **Mevcut davranış REGRESYON YASAĞI:** global anahtar kapalıyken (varsayılan) sistemin gözlenen
   davranışı bugünün birebir aynısıdır; mevcut alarm/eskalasyon/mail testleri TEK SATIR değişmeden
   yeşil kalır. Mail hattının kodu yalnız "webhook tetikleme noktası ekleme" kadar dokunulur ve o
   tetik try/catch + ayrı executor zarfındadır — mail yolunda istisna yayılamaz (testle pinlenir).
2. **İkinci kanal, İKİNCİ servis:** mevcut `WebhookService` (Teams/Slack kartları) DEĞİŞMEZ;
   yeni servis ayrı sınıftır (öneri: `UserPushService`) — adlandırma karışıklığı raporda açıklanır.
3. **Anti-loop mimarisi zorunlu:** (a) olay+kanal+kullanıcı idempotency — `alert_event_id +
   trigger + username` üzerine UNIQUE kısıt: aynı olayın aynı fazı (ilk/çözüm) aynı kişiye iki kez
   GÖNDERİLEMEZ (DB seviyesinde garanti, kod hatasında bile); (b) fırtına (`AlertStorm`) ve
   toplu-kesinti/bakım bastırmaları mail neyi bastırıyorsa webhook'u da bastırır; (c) kullanıcı
   başına saatlik emniyet tavanı (vars. 30/saat — aşılırsa gönderme + günlükte RATE_LIMITED +
   SystemHealth uyarısı); (d) retry tavanlı ve backoff'ludur, sonsuz kuyruk YOK (bellek denetimi
   dersi: sınırsız in-memory retry OOM riskidir — kuyruk DB-outbox'tadır, bellekte değil).
4. **Şema kuralı:** yeni tablo/kolonlar `ddl-auto=update` + `applySchemaPatches()` idempotent
   patch; `deleteBy…` `@Transactional` + `int`; RetentionCatalog kaydı (K10 süresiyle).
   Yeni izleme alanı (`notifyWebhook`) `MON_FIELDS`'e girer (audit + izleme-geçmişi otomatik).
5. **Gizli değerler:** bildirim API'sinin kimlik başlığı değerleri `SecretCipher` ile saklanır,
   API'den asla düz dönmez (write-only), audit diff'te `SecretMask`. URL + başlıklar yalnız admin.
6. **i18n TR+EN aynı değişiklikte; tasarım dili:** yönetim yüzeyi mevcut admin ayar sayfası
   desenleriyle (SmtpSettings/StormSettings kart düzeni), teslimat günlüğü `audit-viewer`/`nl-*`
   aile stilleri + `UserBadge` (sicil → ad-soyad + foto), `SegmentedControl`/`TagInput`/
   `DateTimeRangePicker`/`PaginationBar`/`Dialog`/`Toast`; ikon yalnız `lucide-react`
   (`BellRing`/`Send`/`OctagonPause`); dark theme elle doğrulanır.
7. **Eskalasyon sözleşmesi:** alıcı seçimi mevcut şiddet kurallarını AYNEN izler (müdür =
   YÜKSEK/KRİTİK kontağı — EscalationService ~1392 yorumu); webhook bunun ÜSTÜNE yalnız
   "unvanı açık mı" süzgeci ekler. Üç yol (ilk/çözüm/yeniden-gönder) kanal başına tutarlı.
8. Ortam Windows (Zulu 25 + Maven `D:\portablePrograms\...`); smoke öncesi `mvn package
   -DskipTests` + `npm run build`; hiçbir şey commit'lenmez; coverage floor yalnız yukarı;
   `TESTING.md` güncellenir.

## Keşifte doğrulanmış altyapı gerçekleri (2026-08-27 — yeniden keşfetme, DOĞRULA ve kullan)

- **Alıcı modeli hazır:** `AppUser` — `username` (unique; LDAP hesabı, sicil biçimi K1'de
  doğrulanır), `employeeId` (ayrı sicil alanı VAR — K1'in ikinci adayı), `title` (AD unvanı),
  `companyLevel`, `managerSicil`/`managerId`, `mudurlukId/Name`, `department`, `systemRole`
  (ADMIN/TEAM_ADMIN/PO/USER/AUDIT), `teamIds` çoklu üyelik. Unvan-eşleme kaynağı K2'nin konusu.
- **Teslimat günlüğü emsali:** `NotificationLog` — alertEventId + alıcı satırı + `emailStatus` VE
  `webhookStatus` kolonları zaten var (Teams/Slack kanalı için); `AlertHistory.jsx` bu satırları
  `nl-*` sınıfları ve `EmailStatusBadge` ile kanal rozetli gösteriyor (177: webhook_status
  SKIPPED değilse satır). Kişi-webhook teslimatları İÇİN AYRI tablo kurulacak (Faz 1) ama UI
  dili bu aileden devralınır.
- **Mevcut WebhookService:** Teams/Slack kart gönderimi, tek `site.monitor.webhook.timeout-seconds`
  (vars. 10 sn), `EscalationContact.webhookUrl/Type` üzerinden — DOKUNULMAYACAK (kural 2).
- **Şiddet/müdür kuralı:** WARNING=1 HIGH=2 CRITICAL=3 sıralaması (EscalationService:83); müdür
  minAlertLevel gereği yalnız YÜKSEK/KRİTİK alır (~1392–1393). Takım e-posta hunisi
  `collectTeamEmails` (bildirim-grupları oradan) — kişi-webhook alıcıları E-POSTADAN BAĞIMSIZ,
  AppUser üyeliğinden çözülür (Faz 2).
- **Bastırma altyapıları:** `AlertStorm` + toplu-kesinti bastırması + `MaintenanceWindow` —
  mail yolunda neyi susturuyorlarsa tespit edilip webhook tetiğine de bağlanır (kural 3b;
  Faz 0 çıkarımı). Günlük re-alert dedupe mail tarafında pinli — webhook'un günlük re-alert'e
  katılıp katılmayacağı K8.
- **Ayar altyapısı:** `AppSettings` canlı okunur (şablon değişikliği anında etkir — kullanıcının
  "anlık yansımalı" isteği bedavaya gelir); `AppSettingsCatalog` + admin ayar sayfaları deseni;
  `SecretCipher` mevcut. Retention: RetentionCatalog + `RetentionSettings` ekranı.
- **İzleme bayrağı emsali:** `notifyEmail` (Http/Port/… vars. true) — `notifyWebhook` aynı desene.
- Uygulanmış önceki geliştirmeler: bildirim-grupları (e-posta hedefi), izleme-geçmişi
  (MON_FIELDS diff'i), sorumlu-ekipler — bu kanal onlarla ÇAKIŞMAZ, yanlarına eklenir.

## Faz 0 — Keşif doğrulaması + karar noktaları + ŞABLON ONAYI

Gerçekleri doğrula (özellikle: username biçimi gerçekten sicil mi; bastırma noktalarının tam
listesi; PO'nun systemRole mı unvan mı olduğu). Sonra kararları sun:

- **K1 — Sicil kaynağı:** `userIds` alanına ne yazılır? (a) `username` (LDAP hesabı buysa —
  ÖNERİLEN, ayrı eşleme tablosu gerekmez) (b) `employeeId` (username sicil DEĞİLSE tek doğru
  kaynak; boş olanlar gönderimden düşer + günlükte SKIPPED_NO_ID). Faz 0 kanıtı karar verdirir.
- **K2 — Org-unvan eşlemesi:** "Yönetici / Uzman / PO" grupları neye bakar? (a) **`title` alanı
  desen-eşleme (ÖNERİLEN):** ayarlarda unvan-grubu → AD-title desen listesi (örn. Yönetici =
  "*Yönetici*,*Müdür*"; düzenlenebilir CSV) — kurum unvanları değişse de ayar yetişir;
  (b) `systemRole` (PO oradaysa PO'yu çözer ama "Uzman/Yönetici"yi çözmez) (c) `companyLevel`.
  Muhtemel doğru: PO=systemRole, Yönetici/Uzman=title — karma model; Faz 0 verisiyle netleştir.
- **K3 — Kimlik başlıkları:** bildirim API'si hangi auth'u istiyor (Postman'de Headers(10) —
  Authorization dahil)? Ayarlarda ad-değer başlık listesi (değerler şifreli, write-only) +
  Content-Type sabit `application/json`. Proxy'den mi doğrudan mı (SsrfGuard + iç ağ hedefi)?
- **K4 — `pipeline` alanı:** sabit boş mu, ayarlardan tek sabit değer mi (öneri: ayarlardan,
  vars. boş — API sözleşmesi ne istiyorsa).
- **K5 — Katman öncelik matrisi (onaya sunulacak tablo):** gönder ⇔ global AÇIK ∧ unvan-grubu
  AÇIK ∧ tip AÇIK ∧ takım AÇIK ∧ izleme `notifyWebhook` AÇIK ∧ olay bastırılmamış ∧ dedupe temiz
  ∧ tavan aşılmamış. Takım/tip anahtarlarının saklanışı: AppSettings JSON (az takım) vs küçük
  tablo (öneri: `user_push_scopes` tablosu — takım sayısı büyüyebilir, sorgulanabilir olsun).
  İzleme bayrağı vars. AÇIK (üst katmanlar zaten kapalı — çifte emniyet).
- **K6 — ŞABLON SETİ (metinler ONAY için sunulur, ≤200 karakter, tek satır):**
  - `DOWN`: `{seviye} ▸ {ad}: {hedef} yanıt vermiyor — {neden}. {saat}`
  - `SLOW/THRESHOLD`: `{seviye} ▸ {ad}: {metrik} eşiği aşıldı ({deger}, eşik {esik}). {saat}`
  - `EXPIRY` (sertifika/domain): `{seviye} ▸ {ad}: {ne} {gun} gün içinde doluyor ({tarih}).`
  - `CHANGED` (kayıt/hijack sinyali): `{seviye} ▸ {ad}: {degisen} değişti — kontrol edin. {saat}`
  - `RESOLVED`: `DÜZELDİ ▸ {ad}: {sure} sonra normale döndü. {saat}`
  - `TEST`: `Deneme ▸ SiteMonitor webhook testi — {saat}`
  Sorular: aile-bazlı bu 6 şablon yeterli mi, tip-özel varyant ister misin; `title` alanı sabit
  "SiteMonitor" mı yoksa `SiteMonitor · {seviye}` mi (öneri: ikincisi — bildirim listesinde seviye
  ilk bakışta); saat biçimi `15:04` mi tarihli mi. Yer tutucular şablon editöründe lejantla
  gösterilir; bilinmeyen yer tutucu kaydetmede reddedilir (sessiz bozulma olmasın).
- **K7 — Dayanıklılık mimarisi:** **DB-outbox (ÖNERİLEN):** teslimat satırı önce PENDING yazılır
  (tek toplu istek → olay başına bir "batch" satırı + kullanıcı başına alt satırlar; K8 dedupe
  kısıtı alt satırda), ayrı tek-worker executor gönderir; başarıda SENT + HTTP kodu, hatada
  FAILED + neden; retry: en çok 2 (30 sn / 2 dk backoff), sonra kalıcı FAILED; devre kesici:
  art arda 5 hata → 5 dk sus (günlükte CIRCUIT_OPEN + SystemHealth kartında uyarı). Timeout
  varsayılanları (hepsi ayarlardan): connect 3 sn, istek toplam 5 sn. Alternatif (in-memory
  fire-and-forget) reddi gerekçesiyle sunulur: pod restartında kayıp + OOM riski.
- **K8 — Dedupe kapsamı:** olay başına kanal fazları: ilk alarm 1×, çözüm 1× (UNIQUE kısıt);
  **günlük re-alert webhook'a GİTMESİN (ÖNERİLEN — push'ta tekrar gürültüdür; mail zaten
  hatırlatıyor)** — ayarla açılabilir yap. Manuel "yeniden gönder" webhook'u da tetikler mi
  (öneri: ayrı buton — "maili yeniden gönder" ≠ "push'u yeniden gönder").
- **K9 — Tek istek / kişi başı istek:** ekrandaki API toplu `userIds` alıyor → **tek toplu istek
  (ÖNERİLEN)**; kısmi başarı dönerse (API kişi bazlı sonuç veriyorsa) alt satırlara işlenir,
  vermiyorsa batch sonucu tüm alt satırlara yansır (dürüstçe "batch sonucu" etiketiyle).
- **K10 — Saklama:** `site.monitor.userpush.retention-days` — öneri vars. **1095 gün (3 yıl)**;
  RetentionCatalog + RetentionSettings'te görünür. Onayla.
- **K11 — Teslimat günlüğü filtreleri:** tarih aralığı, sicil/kişi, takım, izleme tipi, izleme
  adı, seviye, sonuç (SENT/FAILED/SKIPPED_*/RATE_LIMITED/CIRCUIT_OPEN), tetik (ilk/çözüm/test);
  CSV dışa aktarım dahil mi (öneri: evet — audit export deseni).

## Faz 1 — Veri modeli ve şema

- **`user_push_settings`** yerine AppSettings anahtarları (URL, başlıklar-şifreli, pipeline,
  timeout'lar, retry/backoff, devre-kesici eşikleri, saat tavanı, global anahtar, unvan-grubu
  anahtarları + desenleri, tip anahtarları, şablonlar, günlük re-alert bayrağı) —
  `AppSettingsCatalog`'a tanımlı, GeneralSettings değil ÖZEL sayfada yönetilir.
- **`user_push_scopes`** (K5): `scopeType` (TEAM/TYPE), `scopeKey`, `enabled` — takım/tip matrisi.
- **`user_push_deliveries`** (outbox+günlük): `alertEventId`, `trigger` (OPEN/RESOLVE/TEST),
  `monitorType`, `monitorId`, `monitorName`, `teamId`, `alertLevel`, `username`, `displayName`
  (gönderim anındaki ad — kişi silinse de günlük okunur), `title`, `message` (gönderilen metin
  AYNEN), `status` (PENDING/SENT/FAILED/SKIPPED_*/RATE_LIMITED/CIRCUIT_OPEN), `httpStatus`,
  `error`, `attempts`, `createdAt`, `sentAt`, `batchId`. UNIQUE(alertEventId, trigger, username)
  (kural 3a; TEST hariç — testte alertEventId null, batchId ile). İndeksler: createdAt, username,
  teamId, (monitorType, monitorId).
- Monitör entity'lerine `notifyWebhook` (Boolean vars. true) — 10 tür + MON_FIELDS + patch'ler.

## Faz 2 — Alıcı çözümü (`UserPushRecipientResolver`)

- Girdi: alarm olayı (teamId, alertLevel). Çıktı: sicil listesi + neden-etiketi.
  Adımlar: takım ÜYELERİ (`AppUser.teamIds` ∋ event.teamId, aktif) → unvan-grubu süzgeci (K2;
  yalnız AÇIK gruplardaki kullanıcılar) → şiddet kuralı (kural 7: müdür-grubu yalnız
  HIGH/CRITICAL — mevcut eskalasyon sözleşmesinin kanal içi eşleniği) → K1 kimliği boş olanlar
  SKIPPED_NO_ID satırı. Sicil listesi tekilleştirilir. E-posta/bildirim-grupları bu çözüme
  KARIŞMAZ (kanal bağımsızlığı).

## Faz 3 — Gönderim servisi (`UserPushService`) + tetikler

- K7 mimarisi: PENDING yaz → tek-worker gönder → sonuç işle; devre kesici + tavanlar + timeout'lar
  ayarlardan CANLI okunur. Gövde: `{title, message, pipeline, userIds}` — alan adları API
  sözleşmesine sadık; mesaj K6 şablonundan yer-tutucu doldurularak üretilir (200 karakter kırpma
  + "…" — kırpıldıysa günlükte işaret).
- Tetik noktaları: EscalationService'in ilk-alarm ve çözüm noktalarında, MAİL SONUCUNDAN BAĞIMSIZ
  `try { userPushService.enqueue(event, trigger) } catch (Exception e) { log.warn(...) }` (kural 1).
  Bastırma denetimleri enqueue İÇİNDE (fırtına/bakım/toplu-kesinti/katman matrisi/dedupe/tavan) —
  karar her satırda `status`/`skip nedeni` olarak görünür (görünmez sessizlik YOK).
- Test gönderimi: `POST /api/admin/user-push/test` — hedef sicil(ler) + şablon seç; gerçek
  gönderim + günlükte TEST satırı; audit `USER_PUSH_TEST`; dakikada 3 tavan.

## Faz 4 — Ayarlar yüzeyi (özel sayfa)

- Admin panelinde yeni bölüm **"Webhook Bildirimleri"** (SmtpSettings kart düzeni):
  (1) Bağlantı kartı — URL, başlıklar (değer maskeli), pipeline, timeout/retry/devre-kesici
  alanları, "Bağlantıyı sına"; (2) Katmanlar kartı — global anahtar (kapalıyken tüm sayfa soluk +
  bilgi notu), unvan-grubu anahtarları + K2 desen düzenleyici, izleme tipi matrisi (10 tip),
  takım matrisi (arama + toplu aç/kapa); (3) Şablonlar kartı — K6 şablonları tek satır editör +
  yer-tutucu lejantı + canlı önizleme ("örnek olayla nasıl görünür"); (4) Test kartı — sicil
  TagInput + şablon seçici + sonuç; (5) **Teslimat Günlüğü** — K11 filtreleri, satırda `UserBadge`
  (sicil→ad-soyad+foto), seviye/sonuç rozetleri, genişletmede gönderilen mesaj AYNEN + HTTP kodu +
  deneme sayısı + batchId; PaginationBar + CSV.
- Yetki: sayfa ve uçlar yalnız admin (`requireAdmin` + uygun resource_key — PermissionCatalog
  kuralıyla yeni key: `notifications.userpush` yönetim; teslimat günlüğü admin; takım kullanıcısı
  KENDİ izlemesinin modalında zaten görür).

## Faz 5 — İzleme yüzeyleri

- 10 türün form modalına `notifyWebhook` anahtarı (`notifyEmail`'in yanına; üst katman kapalıysa
  soluk + "genelde kapalı" ipucu). MON_FIELDS + izleme-geçmişi otomatik.
- **Alarm modalı (AlertHistory):** teslimatlar kanal ayrımlı — mevcut e-posta satırlarının yanına
  "Webhook" bölümü: kime (UserBadge listesi), ne zaman, seviye, mesaj içeriği, sonuç rozetleri;
  `nl-*` görsel ailesi genişletilir. Olay çözüm satırında iki kanalın çözüm teslimatları da ayrı.
- i18n TR+EN; dark theme; kartlarda değişiklik YOK (gürültü ekleme).

## Faz 6 — Testler (kapsamlı — kullanıcının açık isteği)

- **Karar matrisi:** katman kombinasyonları (global kapalı → hiçbir şey; yalnız unvan kapalı →
  o kullanıcılar SKIPPED; tip/takım/izleme kapalı; hepsi açık → SENT) tablo-güdümlü.
- **Anti-loop:** UNIQUE kısıt ihlalinde ikinci gönderim yok (yarış testi); günlük re-alert
  webhook üretmez (K8); fırtına/bakım bastırması; saat tavanı RATE_LIMITED; devre kesici aç/kapa.
- **Bağımsızlık (kural 1 kanıtı):** userPush enqueue'su istisna atarken mail yolunun etkilenmediği
  + tersi; mevcut Escalation/mail testleri DEĞİŞMEDEN yeşil.
- **Gönderim:** gövde şekli (alan adları/sıra), toplu userIds, timeout/retry/backoff, kısmi
  başarı (K9), 200-kırpma, şablon yer-tutucu doldurma + bilinmeyen yer-tutucu reddi, başlık
  şifreleme (write-only + maskeli diff).
- **Alıcı çözümü:** unvan desen eşleme, müdür yalnız HIGH/CRITICAL, sicil boş → SKIPPED_NO_ID,
  çoklu takım tekilleştirme. Fixture'larda YALNIZ sahte sicil/kurum (kural: gerçek ad tarayan
  basit bir kaynak-tarama testi eklenebilir — öneri).
- **UI:** ayar kartları (maskeli değerler, matrisler, şablon önizleme), test ekranı, teslimat
  günlüğü filtreleri + rozetler, AlertHistory kanal ayrımı; i18n-parity; RepositoryWriteTransaction-
  GuardTest + patch tarama + retention kırpma testi.

## Faz 7 — Doğrulama, smoke ve rapor

1. `mvn -B clean verify` → `npm run test` → `npm run build` → paket → `start-local.ps1`.
2. Smoke (test API'si ya da mock uçla): global kapalıyken hiçbir gönderim; aç + unvan aç + test
   ekranından deneme → günlükte SENT + API'ye tek toplu istek; bir izlemede alarm → ilk + çözüm
   birer push, mail her durumda bağımsız gitti; aynı olaya ikinci push yok; URL'i bozuk yap →
   FAILED + retry + devre kesici + mail ETKİLENMEDİ; alarm modalında iki kanal ayrı; şablonu
   değiştir → sonraki mesaj ANINDA yeni metinle; dark theme + TR/EN.
3. Rapor: dosya listesi, test çıktıları, K kararları + onaylanan şablon metinleri, katman
   matrisi son hâli, bilinen sınırlar (API kişi-bazlı sonuç vermiyorsa batch-sonucu etiketi).

## Zenginleştirme önerileri (kullanıcıya sun — avantaj/dezavantajıyla)

- **E1 — Kullanıcı bazlı opt-out:** kişi kendi profilinden "bana push gelmesin" diyebilsin.
  Avantaj: gürültü kontrolü kişiye iner; dezavantaj: "neden bana gelmedi" sorusu — günlükte
  SKIPPED_USER_OPT_OUT olarak görünür kılınırsa yönetilebilir. Öneri: EKLE (v1'de ya da hemen sonra).
- **E2 — Sessiz saatler:** gece penceresinde yalnız KRİTİK push (ayarlardan pencere + seviye).
  Avantaj: uyandırma disiplini; dezavantaj: gecikmiş haber — pencere biterken özet atılabilir.
- **E3 — Teslimat istatistik şeridi:** ayar sayfası üstünde son 24s/7g SENT/FAILED sayaçları +
  mini eğilim (Sparkline) — sorun anında tek bakışta.
- **E4 — SystemHealth entegrasyonu:** devre kesici açıkken ve FAILED oranı eşiği aşınca sağlık
  kartı uyarısı (E3'ün alarmlaşmışı).
- **E5 — Deep-link:** mesaja izlemenin paylaşılabilir URL'i eklensin mi? Push API'si link
  destekliyorsa avantaj büyük; desteklemiyorsa metin uzar (200 sınırıyla çatışır) — API
  yeteneğine göre karar.
- **E6 — Haftalık özet push'u:** cuma "bu hafta N alarm, M çözüldü" tek mesaj (opt-in).

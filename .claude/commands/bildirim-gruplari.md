---
description: Takım-kapsamlı Bildirim Grupları (StatusCake Notification Groups uyarlaması) — takımlar alarm e-postaları için adlandırılmış alıcı grupları kurar; takım başına bir "varsayılan" grup takım mailinin yerine geçer; monitör/envanter tanımında grup seçilebilir; grup yoksa mevcut takım-maili davranışı BİREBİR korunur. Çözümleme tek hunide (collectTeamEmails), eskalasyon kişileri bozulmaz, yönetim ekranı + seçiciler + eksiksiz yönlendirme test matrisi.
argument-hint: [tasarim|backend|frontend|hizli] (opsiyonel — boş bırakılırsa TAM kapsam koşulur)
---

# /bildirim-gruplari — Takım Bildirim Grupları

Görevin: SiteMonitor'e StatusCake'in "Notification Groups / Who to Alert" yapısının takım-tabanlı
uyarlamasını eklemek. Ürün kararı netleşmiş çekirdek (kullanıcının kendi sözleriyle):

- Bugün alarmlar TAKIM e-posta adresine gider (`Team.email` — tek adres); takımlara başka adres
  girme imkânı yok. Takım içinde farklı alarm adresleri İHTİYACI var.
- Takımlar kendilerine **Bildirim Grupları** oluşturabilir (ad + e-posta listesi). Takım bir grubu
  **varsayılan** işaretlerse, o takımın alarmları artık takım maili yerine o grubun adreslerine gider.
- **Monitör/alarm tanımında** kullanıcı, takımına ait kayıtlı gruplardan birini seçebilir
  (o izlemeye özel yönlendirme).
- **Hiç grup tanımlanmamışsa / seçilmemişse mevcut davranış AYNEN devam eder** (takım maili).
  "Mevcudu sakın bozma" bu geliştirmenin birinci yasasıdır.

Çözümleme zinciri (öncelik sırası): monitörde seçili grup → takımın varsayılan grubu →
`Team.email` (bugünkü davranış). Eskalasyon kişileri (`escalation_contacts` — müdür/severity
katmanı) bu zincirin ÜSTÜNE aynen eklenmeye devam eder (K4).

Referans ekranlar (StatusCake): grup listesi (Ad · entegrasyon rozetleri: e-posta/webhook sayısı ·
Sorunlar · düzenle-geçmiş-sil), grup formu (Ad, Repeat Alert toggle'ı, Communication Channels:
e-posta çoklu ekleme, SMS, Integrations, Webhook URL + Test + GET/POST), monitör formunda
"Who to Alert (Contact Groups)" açılır listesi. Bunlar SiteMonitor gerçeğine UYARLANIR (birebir
kopyalanmaz — SMS yok, Repeat Alert K6'da tartışılır, webhook K1'de).

Yüzeysel iş KABUL EDİLMEZ: her faz kanıtla raporlanır; ürün kararları (K1–K9) tahmin edilmez,
seçenek + öneriyle kullanıcıya sorulur. Acele YOK — yönlendirme değişikliği alarm altyapısına
dokunur; her adım test matrisiyle kanıtlanır.

Kapsam argümanı: `$ARGUMENTS`
- Boş → tüm fazlar (0–7). `tasarim` → Faz 0–1 (karar dosyası, kod yok). `backend` → 0,1,2,3,6,7.
- `frontend` → 0,4,5,6,7 (API hazır varsayılır; değilse raporla). `hizli` → 0–5 (testler asgari —
  bu özellikte ÖNERİLMEZ, alarm yönlendirmesi test istemeden değişmez).

## Değişmez kurallar (her fazda geçerli)

1. **MEVCUT DAVRANIŞ REGRESYON YASAĞI:** hiç grup tanımlı olmayan bir kurulumda davranış bayt bayt
   bugünkünün aynısıdır. Kanıt: mevcut `EscalationServiceTest` (ve tüm alarm testleri) TEK SATIR
   DEĞİŞMEDEN yeşil kalır — bir mevcut testi "yeni davranışa göre" güncellemek bu komutta yasaktır
   (yalnız YENİ testler eklenir). Günlük re-alert dedupe, CRITICAL-müdür kapısı, bulk-outage
   bastırma, üç-yol takım çözümü (event.getTeamId) politikaları AYNEN korunur.
2. **Tek çözümleme noktası:** grup çözümü YALNIZ takım-maili hunisine eklenir —
   `collectTeamEmails(syTeamId, ugTeamId)` (EscalationService ~1656) ve onun türevleri
   (`collectTeamRecipients` önizleme, `collectTeamNames`, `teamAlertEmails` dış sarmalayıcı).
   Üç bildirim yolunda (ilk alarm / çözüm / yeniden gönder) ayrı ayrı grup kodu YAZILMAZ; hepsi
   aynı huniden geçtiği için otomatik tutarlı kalır. Huninin dışında e-posta listesi kuran bir yol
   keşfedilirse (Faz 0) rapor edilir ve huniye bağlanır — kopyalanmaz.
3. **PermissionCatalog kuralı:** yeni `resource_key` AYNI değişiklikte `PermissionCatalog.ALL`'a
   girer (K2 — `contacts.list/crud`'un takım-scope emsali hazır: satır 183–188).
4. **Şema kuralı:** yeni tablo/kolonlar `ddl-auto=update` + `applySchemaPatches()` idempotent
   patch; türetilmiş `deleteBy…` `@Transactional` + `int`.
5. **i18n:** TR+EN aynı değişiklikte (`i18n-parity.test.jsx`); `.properties` değerlerinde ham
   Türkçe karakter yok (`\uXXXX`).
6. **Tasarım dili:** yönetim ekranı ve seçiciler mevcut primitiflerle (`TagInput` e-posta çoklu
   girişi, `SearchableSelect` grup seçici, `Dialog`, `Toast`, `KebabMenu`, `PaginationBar`,
   rozet/pill sınıfları); ikon yalnız `lucide-react` (`BellRing`/`Users`/`Mail`); dark theme elle
   doğrulanır; desen adları namethatui (badge-chip-pill, form-field, empty-state, token-field).
7. **Denetim + geçmiş:** grup CRUD'u `auditService.recordAction` + `AuditDiff` ile diff'li yazılır;
   monitörlere eklenen `notificationGroupId` alanı `MON_FIELDS`'e girer; `/izleme-gecmisi` altyapısı
   uygulanmışsa (VALID_TABS'ta `monitorchanges` görüldü — Faz 0'da doğrula) grup değişiklikleri o
   zaman çizelgesinde de görünür. Envanter dahilse (K3) CLAUDE.md'nin envanter-alan-ekleme kontrol
   listesi işletilir (InventoryFormModal EMPTY+payload, buildInventoryDiff, updateInventory setter).
8. Ortam Windows (`JAVA_HOME=C:\Program Files\Zulu\zulu-25`, Maven
   `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`, Node 24); smoke öncesi
   `mvn package -DskipTests` + `npm run build`. Hiçbir şey commit'lenmez; coverage floor yalnız
   yukarı; `TESTING.md` güncellenir.

## Keşifte doğrulanmış altyapı gerçekleri (2026-08-25 — yeniden keşfetme, DOĞRULA ve kullan)

- **Takım maili tek kolon:** `Team.email` (@Column length 200, satır 29–30) — bugünkü "varsayılan
  takım adresi" budur; başka adres alanı yok (kullanıcının şikâyeti doğrulandı).
- **ALTIN HUNİ:** `EscalationService.collectTeamEmails(syTeamId, ugTeamId)` (~1656) — takım
  mailinin alarm yollarına girdiği TEK yer: ilk-alarm/çözüm yolu (~1191, 1418), yeniden-gönderim
  önizleme + sayaç (~403), `collectTeamRecipients` ([email, takımAdı] önizleme çifti, ~1674),
  `collectTeamNames` (~1627), `teamAlertEmails(teamId)` public sarmalayıcı (~1652 — ScriptedAnomalyGuard
  gibi alarm-dışı bildirimciler için; Javadoc'u "alıcı çözümü tek yerde kalsın, kopyalanmasın" der —
  bu geliştirmenin ilkesiyle aynı). İki takım desteği: SY + UG (cert alarmlarında ikisi de).
- **Eskalasyon kişileri ayrı katman:** `EscalationContact` (userId/name/email/role/minAlertLevel/
  webhookUrl/webhookType/teamId/active) — `getContactsForLevel(level, teamId)` (~1361) severity +
  takım süzer; alıcı kümesi = takım e-postaları + kişiler (dedupe + exclusion, ~398–412, 1426–1429).
  Kişi katmanına DOKUNULMAYACAK (K4).
- **Yol semantiği (bellek/CLAUDE.md):** standalone alarmlar takımı `AlertEvent.teamId`'den çözer
  (alarm AÇILIRKEN damgalanır — grup damgalama için emsal desen, K5); müdür/eskalasyon yalnız
  CRITICAL domain expiry'de eklenir; günlük re-alert dedupe `EscalationServiceTest` ile pinli.
- **Monitörlerde bildirim bayrağı var:** `notifyEmail` (HttpMonitor:88, PortMonitor:82 —
  varsayılan true; diğer türlerde de desenini doğrula) — grup seçici bu bayrağın YANINA gelir
  (bayrak kapalıysa grup da sessizdir).
- **İzin emsali:** `contacts.list`/`contacts.crud` ("communication" grubu, PermissionCatalog 30–31)
  TAKIM SCOPE'ta çalışabilen kaynaklar listesinde (183–188) — bildirim grupları aynı deseni izler.
- **Webhook altyapısı:** `WebhookService` (Teams/Slack, 10 sn timeout, `notification_logs`'a
  yazar); `EscalationContact.webhookUrl/webhookType` kişi-başına webhook emsali (K1b'nin zemini).
- **UI emsalleri:** `admin/EscalationContacts.jsx` (kişi yönetimi), `TagInput` (token-field),
  `SearchableSelect` (gruplu seçenek desteği — şablon seçicisinden kanıtlı), monitör formları
  `*MonitorPage.jsx` içinde; envanter formu `inventory/InventoryFormModal.jsx` (dashboard kartıyla
  paylaşımlı). `NotificationLog` her denemeyi SENT/FAILED kaydeder.
- **10 tür + envanter:** MonitoringController'da 10 standalone tür (pagespeed dahil — VALID_TABS
  doğrulandı); cert alarmları envanter takımından yürür (AdminController/CertificateInventory).

## Faz 0 — Keşif doğrulaması + karar noktaları

Önce huninin GERÇEKTEN tek olduğunu kanıtla: `getEmail()` çağrılarını ve e-posta listesi kuran tüm
yerleri tara (EmailNotificationService, WeeklyReport*, CertInventoryReportLog, ScriptedAnomalyGuard,
IncidentNotificationService…) — alarm-dışı posta akışlarından hangileri grup kapsamına GİRMELİ
(K3'ün ikinci yarısı), hangileri takım mailinde kalmalı, listeyle kullanıcıya sun. Sonra kararlar:

- **K1 — Kanal kapsamı v1:** (a) **yalnız e-posta listesi (ÖNERİLEN):** çekirdek isteğin kendisi;
  grup = ad + adres listesi. (b) e-posta + grup webhook'u (Teams/Slack URL + Test düğmesi —
  WebhookService hazır; StatusCake ekranındaki Integrations/Webhook karşılığı). (c) SMS — altyapı
  yok, ALINMAZ (ekranda "yakında" bile gösterilmez; monitör formundaki mevcut "yakında" diliyle
  tutarlılık istenirse sor).
- **K2 — Yetki + ekran yeri:** yeni resource_key `notification.groups` (view/edit, takım-scope;
  `contacts.*` emsali) — PermissionCatalog + matris + bootstrap grant (USER'a açık mı? öneri:
  açık — takım kendi grubunu yönetsin; admin hepsini görür). Ekran: (a) **bağımsız "Bildirim
  Grupları" yüzeyi — Nav'a küçük bir giriş ya da monitör sayfalarının paylaştığı bir yönetim
  modalı (ÖNERİLEN: hangi yerleşim, mevcut Nav yoğunluğuna göre kullanıcıya sorulur)** (b)
  AdminPanel/EscalationContacts yanına (admin-odaklı olur, takım kullanıcısı erişemez — isteğe aykırı).
- **K3 — Uygulama kapsamı:** (a) **tüm alarm aileleri (ÖNERİLEN):** 10 standalone tür + cert
  envanteri + domain; envanter formuna da grup seçici (kural 7'deki kontrol listesiyle). UG takımı
  nüansı: grup çözümü TAKIM BAŞINA yapılır — SY takımının grubu SY adresinin, UG takımının kendi
  varsayılan grubu UG adresinin yerine geçer; monitör-seviyesi seçim yalnız izlemenin sahibi takım
  için geçerlidir. (b) yalnız standalone monitörler (envanter sonraya). Alarm-dışı akışlar
  (haftalık rapor, envanter raporu, anomali bildirimi) → Faz 0 listesinden karar.
- **K4 — Eskalasyon kişileriyle ilişki (GÜVENLİK AĞI):** grup yalnız TAKIM MAİLİ bileşenini
  değiştirir; `escalation_contacts` katmanı (müdür, severity) AYNEN üstüne eklenir (ÖNERİLEN —
  müdürün haberi kesilmez). Alternatif "grup her şeyi ezsin" ÖNERİLMEZ; yine de sor.
- **K5 — Damgalama:** monitörün grup seçimi alarm AÇILIRKEN `AlertEvent.notificationGroupId`'ye
  damgalanır (teamId emsali — çözüm/yeniden-gönderim aynı gruba gider, alarm ortasında grup
  değişse bile tutarlı kalır) (ÖNERİLEN) vs gönderim anında canlı çözüm. Damgalanan grup
  silinmişse → zincirin kalanına düş (varsayılan grup → takım maili).
- **K6 — "Enable Repeat Alert" toggle'ı:** StatusCake'teki bu anahtar bizim GÜNLÜK re-alert dedupe
  politikamızla çatışır (testle pinli ürün kararı). (a) **ALINMAZ (ÖNERİLEN)** — komut bunu grup
  formunda hiç göstermez, dedupe aynen; (b) grup-başına "günlük hatırlatma" esnetmesi — dedupe
  politikasını deler, ancak kullanıcı açıkça isterse ayrı geliştirme olarak ele alınır.
- **K7 — Grup yaşam döngüsü:** soft-delete (`active=false`; envanter restore deseni) + takım
  başına EN FAZLA BİR varsayılan (kısıt + UI radyo; ikinci "varsayılan yap" öncekini indirir —
  audit'e ikisi de yazılır). Silinen/pasif grup: monitör formunda "silinmiş grup" rozeti + kayıtlı
  seçim korunur ama çözümleme fallback'e düşer. Onaylat.
- **K8 — Adres doğrulama/kısıt:** format doğrulaması + grup başına adres tavanı (öneri 15) +
  opsiyonel kurumsal domain kısıtı (`site.monitor.notification.allowed-email-domains` AppSettings
  anahtarı — boş=serbest; kurumsal ortam için `*@example.com` gibi). Karar: kısıt v1'de var mı?
- **K9 — Görünürlük:** yeniden-gönderim ÖNİZLEMESİ (recipients_queued, ~402–433) ve alarm
  detayında alıcıların HANGİ kaynaktan geldiği görünür ("Grup: Ödeme-Nöbetçi" / "Takım maili" /
  "Eskalasyon: müdür") — `collectTeamRecipients` çifti kaynak etiketiyle zenginleşir;
  `notification_logs` satırına grup adı yazılır. (ÖNERİLEN — "mail kime gitti" sorusunu bitirir.)

## Faz 1 — Veri modeli ve şema

- **`NotificationGroup`** (`notification_groups`): `id`, `teamId` (zorunlu), `name` (takım içinde
  unique), `emails` (TEXT — normalize CSV; boş grup KAYDEDİLEMEZ), `isDefault` (takım başına en
  fazla bir — uygulama katmanında + kısmi indeksle koru), (K1b) `webhookUrl`/`webhookType`,
  `active`, `createdAt/updatedAt` (+ /izleme-gecmisi varsa createdBy ailesi). İndeks: `teamId`,
  (`teamId`,`isDefault`).
- **Monitör kolonları:** 10 tür entity'sine + (K3a) `CertificateInventory`'ye
  `notification_group_id` (nullable — null = zincirin kalanı). `AlertEvent`'e
  `notification_group_id` (K5 damgası).
- `applySchemaPatches()` patch'leri; `MON_FIELDS`'e `notificationGroupId`; retention GEREKMEZ
  (küçük yapılandırma tablosu).

## Faz 2 — Çözümleme motoru (kural 2'nin kalbi)

- **`NotificationGroupService`**: `resolveEmailsForTeam(teamId, Long stampedGroupId)` →
  sıra: damgalı grup (aktifse, o takıma aitse) → takımın aktif varsayılan grubu → `Team.email`.
  Dönen değer adres listesi + kaynak etiketi (K9). Grup adresleri de mevcut dedupe/exclusion
  akışından geçer.
- `collectTeamEmails/collectTeamRecipients/collectTeamNames` bu servise delege olur — imzalara
  `Long notificationGroupId` (event damgası) eklenir; ÇAĞIRAN üç yol yalnız `event`ten damgayı
  geçirir, başka mantık taşımaz. `teamAlertEmails(teamId)` sarmalayıcısı damgasız çalışır
  (varsayılan grup → takım maili) — ScriptedAnomalyGuard otomatik kazanır.
- Alarm açılış noktalarında (MonitoringOutageService onayı, SchedulerService domain/cert alarm
  üretimi) monitörün/envanterin `notificationGroupId`'si `AlertEvent`'e damgalanır (K5).
- Performans: sweep başına takım gruplarını tek sorguda haritala (N+1 yok — teamNameMap deseni).

## Faz 3 — CRUD API + doğrulama

- `GET/POST /api/notification-groups`, `PUT/DELETE /{id}`, `POST /{id}/make-default`
  (+K1b `POST /{id}/test-webhook`, rate-limitli) — `permissionService.require(session,
  "notification.groups", ...)` + `SessionScope.canView/canManage(teamId)`; IDOR → 403/404 +
  `recordSecurityEvent`; her mutasyon audit + AuditDiff (adres listesi diff'te görünür).
- Doğrulama: K8 kuralları; boş grup reddi; başka takımın grubunu monitöre seçme reddi (create/
  update yollarında `resolveWriteTeam` sonrası grup-takım eşleşme kontrolü); takım değişiminde
  (`resolveTeamChange`) eski takımın grubu otomatik NULL'lanır + yanıtta uyarı.
- Monitör CRUD'larına `notificationGroupId` alanı (10 tür + K3a envanter — envanterde kural 7
  kontrol listesi: EMPTY default, save payload, buildInventoryDiff, updateInventory setter).

## Faz 4 — Frontend

- **Yönetim ekranı (K2 yerleşimi):** StatusCake listesinin SiteMonitor uyarlaması — tablo/kart:
  Grup adı (+ "Varsayılan" rozeti), adres sayısı rozeti (`Mail` ikonlu; K1b webhook rozeti),
  "Sorun" sütunu (boş grup / geçersiz adres / silinmiş-grup-kullanan monitör sayısı uyarısı),
  eylemler (düzenle / varsayılan yap / sil — `KebabMenu` + `Dialog` onayı). Form: ad, adresler
  (`TagInput` + anlık format doğrulama + tavan sayacı), "takım varsayılanı yap" anahtarı,
  (K1b) webhook URL + tür + Test düğmesi (`Toast` sonucu). Boş durum: "Henüz grup yok — alarmlar
  takım adresine (x@y) gidiyor" (mevcut davranışı GÖSTEREN dürüst empty-state). Admin tüm
  takımları takım süzgeciyle görür.
- **Monitör formlarına seçici:** 10 türün form modalına "Bildirim Grubu" `SearchableSelect` —
  seçenekler: "Takım varsayılanı (grup yoksa takım maili)" (null) + takımın aktif grupları; takım
  değişince liste yeniden yüklenir; silinmiş grup seçiliyse kırmızı rozetli seçenek. `notifyEmail`
  bayrağının yanına yerleşir; MonitorHowBox/monitorGuides metinleri güncellenir. (K3a)
  InventoryFormModal'a aynı seçici.
- **K9 görünürlüğü:** alarm detay/yeniden-gönderim önizlemesinde alıcı listesi kaynak etiketli
  ("Grup: X" / "Takım maili" / "Kişi: müdür"); AlertHistory satırında grup adı rozeti.
- i18n `ng.*` ailesi TR+EN; dark theme el doğrulaması.

## Faz 5 — Entegrasyon ve tutarlılık taraması

- Faz 0'daki "huni dışı e-posta kuran yerler" listesindeki kapsam kararları uygulanır (örn.
  ScriptedAnomalyGuard otomatik; haftalık rapor/envanter raporu karara göre).
- `NotificationLog` satırlarına grup adı; `EmailTemplateBuilder` alt bilgisine "Bu bildirimi
  '<grup>' bildirim grubu aldı" satırı (kaynak şeffaflığı — karara bağla).
- HelpPage'e kısa bölüm; audit viewer'da yeni event türleri görünür.

## Faz 6 — Testler (yönlendirme matrisi — bu özelliğin kalbi)

- **Regresyon (kural 1 kanıtı):** MEVCUT alarm/eskalasyon testleri değişmeden yeşil; ek olarak
  "grupsuz kurulum" uçtan uca testi: grup tablosu boşken üç yolun alıcıları bugünkü çıktıyla birebir.
- **`NotificationGroupServiceTest` çözümleme matrisi:** monitör grubu var → grup; yalnız takım
  varsayılanı → varsayılan; hiçbiri → team.email; damgalı grup pasif/silik → zincir düşer; grup
  başka takıma ait → yok sayılır + log; SY+UG çifti → her takım kendi zincirinden bağımsız çözülür;
  boş `Team.email` + grupsuz → mevcut "No recipients — skipping" davranışı; dedupe (grup adresi ==
  kişi adresi → tek mail); eskalasyon kişilerinin ÜSTE eklendiği (K4).
- **Üç yol tutarlılığı:** ilk alarm / çözüm / yeniden-gönderim aynı damgayla aynı alıcıları üretir
  (yeniden-gönderim önizleme sayacı dahil). Dedupe politikası bozulmadı testi.
- **CRUD:** IDOR, takım-içi unique ad, tek-varsayılan kısıtı (ikinci make-default öncekini
  indirir), boş grup reddi, K8 doğrulamaları, soft-delete sonrası monitör fallback'i, takım
  değişiminde grup NULL'lama, audit diff (adres listesi).
- **Frontend:** yönetim ekranı (liste/form/doğrulama/boş durum), monitör formu seçicisi (takım
  değişimi + silinmiş grup rozeti), i18n parity; mock API.
- `RepositoryWriteTransactionGuardTest`, patch kaynak-tarama, envanter alan-ekleme sync testleri
  (inventory-flags-sync ETKİLENMEZ — bayrak değil select; yine de koştur).

## Faz 7 — Doğrulama, smoke ve rapor

1. `mvn -B clean verify` → `npm run test` → `npm run build` → `mvn package -DskipTests` →
   `start-local.ps1` → `/health` UP.
2. Smoke senaryosu (SMTP loglarıyla kanıtla): (1) grupsuz takımda alarm → takım mailine (mevcut);
   (2) takıma varsayılan grup tanımla → yeni alarm grup adreslerine; (3) bir monitöre farklı grup
   seç → o monitörün alarmı seçili gruba, diğerleri varsayılana; (4) grubu sil → sonraki alarm
   takım mailine düşer + formda rozet; (5) yeniden-gönderim önizlemesi kaynak etiketli doğru
   listeyi gösterir; (6) CRITICAL domain expiry'de müdürün HÂLÂ eklendiği; (7) yabancı takım
   grubuna erişim 403/404. Dark theme + TR/EN.
3. Rapor: dosya listesi, test çıktıları, K kararları, huni-dışı akışlar listesi ve kapsam
   kararları, bilinen sınırlar.

## Zenginleştirme önerileri (kullanıcıya sun — şimdi mi sonra mı)

- **E1 — Sessiz saat/pencere:** grup-başına "bakım penceresinde/mesai dışında e-posta gönderme"
  esnekliği (MaintenanceWindow altyapısıyla akraba — dikkat: alarm ÜRETİMİ değil yalnız teslim).
- **E2 — Grup bazlı önizleme/test maili:** grup formunda "Test maili gönder" (SMTP ayar testi
  emsali) — adres listesi doğru mu bir tıkla kanıtlanır.
- **E3 — Kullanım görünürlüğü:** grup satırında "bu grubu kullanan N izleme" rozeti + tıklayınca
  liste (silmeden önce etki analizi).
- **E4 — Severity yönlendirmesi:** grup-başına minAlertLevel (yalnız CRITICAL'i alan "yönetici
  grubu" gibi) — eskalasyon kişileri deseninin gruba taşınmışı; v2 adayı.
- **E5 — Webhook kanalı genişletmesi (K1b seçilmediyse):** gruba Teams/Slack webhook + Test —
  StatusCake ekranındaki Integrations karşılığı.
- **E6 — Alarm geçmişinde kaynak filtresi:** AlertHistory'de "bildirim grubu" süzgeci.
- **E7 — Haftalık rapor alıcıları:** haftalık takım raporlarının da varsayılan gruba gitmesi
  (Faz 0 listesi kararına göre).

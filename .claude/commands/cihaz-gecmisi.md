---
description: Kullanıcının kendi "Cihaz Geçmişi / Oturum Güvenliği" ekranı (Airbnb cihaz-geçmişi deseninin SiteMonitor'e uyarlanması) — mevcut oturum kartı, hatırlanan cihaz(lar) + satır başına oturum kapatma, IP+konum+cihaz özetli giriş geçmişi, başarısız denemeler, anomali rozetleri, "bu girişi ben yapmadım" akışı. Tek-aktif-oturum modeline dürüst uyarlama, remember-me meta zenginleştirme + token sertleştirme kararları, uçtan uca testler.
argument-hint: [tasarim|backend|frontend|hizli] (opsiyonel — boş bırakılırsa TAM kapsam koşulur)
---

# /cihaz-gecmisi — Cihaz Geçmişi ve Oturum Güvenliği Ekranı

Görevin: her kullanıcının KENDİ hesabı için Airbnb'nin "Cihaz geçmişi" ekranına benzer bir yüzey
kurmak. Referans düzen: liste satırında cihaz ikonu (masaüstü/telefon) · kalın başlık
("Windows 10 · Chrome") · mevcut oturumda "MEVCUT OTURUM" rozeti · alt satırda "İstanbul, TR ·
24 Ağustos 2026 01:15" · sağda "Oturumu kapatın" bağlantı-düğmesi · tanınamayan kayıtta genel
"Oturum" etiketi.

**SiteMonitor'e DÜRÜST uyarlama (kopyalama değil):** SiteMonitor tek-aktif-oturum modelindedir —
kullanıcının aynı anda TEK canlı oturumu olur (yeni login eskisini düşürür) ve bugünkü kodda her
login TÜM remember-me token'larını da geçersizler. Yani Airbnb'deki "hâlâ açık üç oturum" bizde
yapısal olarak yoktur. Ekranın bizdeki doğru karşılığı:

- **Bu cihaz** — mevcut oturum kartı (MEVCUT OTURUM rozeti, cihaz özeti, IP+konum, giriş zamanı,
  son etkinlik/lastSeenAt, bu cihazda "beni hatırla" açık mı + kapatma).
- **Hatırlanan cihaz(lar)** — remember-me token'ları cihaz meta'sıyla listelenir; satır başına
  "Oturumu kapat" = o cihazın kalıcı girişini iptal (K2 kararına göre tek ya da çoklu cihaz).
- **Giriş geçmişi** — audit LOGIN kayıtlarından salt-okunur zaman çizelgesi: cihaz özeti, IP +
  şehir/ülke, zaman, anomali rozetleri (mesai dışı / alışılmadık IP / coğrafi hız).
- **Başarısız denemeler** — LOGIN_FAILED kayıtları ayrı katlanır bölümde (K6).
- **Güvenlik eylemleri** — "Diğer tüm cihazlardan çıkış yap" + K7 "Bu girişi ben yapmadım".

Yüzeysel iş KABUL EDİLMEZ: her faz kanıtla raporlanır; ürün kararları (K1–K8) tahmin edilmez,
seçenek + öneriyle kullanıcıya sorulur.

Kapsam argümanı: `$ARGUMENTS`
- Boş → tüm fazlar (0–7). `tasarim` → Faz 0–1 (karar dosyası, kod yok). `backend` → 0,1,2,3,6,7.
- `frontend` → 0,4,5,6,7 (API hazır varsayılır; değilse raporla). `hizli` → 0–5 (testler asgari).

## Değişmez kurallar (her fazda geçerli)

1. **Gizlilik/güvenlik pazarlık dışı:** API yanıtına ASLA oturum ID'si, token değeri, ham
   `activeSessionId` yazılmaz — yalnız opak kayıt id'leri + meta. Tüm uçlar SELF-scope
   (`session.getAttribute("username")` — `/me/audit`'in `findOwnFiltered` deseni); başka
   kullanıcının verisine giden hiçbir parametre kabul edilmez (IDOR testle pinlenir). Her iptal/
   çıkış eylemi audit'e yazılır (`auditService.recordAction` — IP/UA otomatik).
2. **Tek-aktif-oturum sözleşmesine DOKUNULMAZ:** `AuthInterceptor.isSessionSuperseded`,
   `activeSessionId` (null = grandfathered!), `TERMINATED:` sentineli, login 409/forceLogin akışı
   ve `UserService` tazelik penceresi aynen kalır. Bu ekran o mekanizmanın ÜZERİNE okuma + dar
   eylemler koyar; yeni bypass yolu açmaz (`mustChangePassword` kısıtlı yol listesi genişletilmez).
3. **Şema kuralı:** yeni kolonlar `ddl-auto=update` + `applySchemaPatches()` idempotent patch;
   türetilmiş `deleteBy…` repo metodları `@Transactional` + `int`.
4. **i18n:** tüm anahtarlar TR+EN aynı değişiklikte (`i18n-parity.test.jsx`); `.properties`
   değerlerinde ham Türkçe karakter yok (`\uXXXX`). Tarih aritmetiğinde `setUTC*` (3 saat tuzağı).
5. **Tasarım dili:** mevcut `audit-viewer`/rozet/`ui/` primitifleriyle (Dialog confirm, Toast,
   PaginationBar, SegmentedControl, StatusBlock/empty-state); ikon yalnız `lucide-react`
   (`Monitor`/`Smartphone`/`ShieldAlert`/`LogOut`); Airbnb düzeninin sadeliği korunur ama renk/
   tipografi SiteMonitor standardıdır; dark theme elle doğrulanır. Desen adları: namethatui
   (list-item/meta-row, badge-chip-pill, empty-state, dialog).
6. **UA ayrıştırma bağımlılıksız:** dış kütüphane EKLENMEZ; küçük, tablo-güdümlü ve testle pinli
   bir özetleyici yazılır (K4). Ayrıştırılamayan → ekrandaki gibi genel "Oturum" etiketi — asla
   ham UA dize dökülmez (ham hâli yalnız satır genişletmesinde/tooltip'te).
7. Ortam Windows (`JAVA_HOME=C:\Program Files\Zulu\zulu-25`, Maven
   `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`, Node 24); smoke öncesi
   `mvn package -DskipTests` + `npm run build`. Hiçbir şey commit'lenmez; coverage floor yalnız
   yukarı; `TESTING.md` etkilenirse güncellenir.

## Keşifte doğrulanmış altyapı gerçekleri (2026-08-24 — yeniden keşfetme, DOĞRULA ve kullan)

- **Tek oturum kaydı:** `UserService` — `SESSION_TERMINATED_PREFIX="TERMINATED:"` (46–48),
  `hasLiveSession` = activeSessionId set + sentinel değil + `lastSeenAt` tazelik penceresinde
  (270–276); ping ~15 sn'de `lastSeenAt` tazeler (debounce F3, ~82); açılışta stale temizliği
  (316); admin kick sentinel yazar (324–332). `activeSessionId=null` = grandfathered, kicked değil.
- **Remember-me bugün TEK ve METASIZ:** `RememberMeToken` = token(unique)/username/expiresAt —
  UA/IP/createdAt/lastUsedAt YOK (BOŞLUK 1). `AuthController.login` 204: HER login'de
  `invalidateAllForUser(username)` → sonra `remember_me` işaretliyse TEK yeni token + cookie
  (215–217). Logout kendi cookie token'ını geçersizler (285–288). Yani bugün en fazla BİR
  hatırlanan cihaz olur ve o da SON login'in cihazıdır (K2'nin konusu). `RememberMeService`:
  generate/validate/invalidate/invalidateAllForUser/cleanExpired (saatlik); TTL 7 gün; eski cookie
  adı `cert-monitor-remember` geriye-uyumu.
- **Giriş geçmişinin verisi HAZIR:** `AuditLog` LOGIN/LOGIN_FAILED/LOGOUT satırları — ipAddress +
  geo (ipCountry/ipCity/ipOrg/ipReverseHost, `GeoIpService` 1sa cache), userAgent, sessionId,
  outcome, failureReason, `anomalyFlags` CSV (OFF_HOURS/UNUSUAL_IP/GEO_VELOCITY/BRUTE_FORCE/
  RATE_LIMITED; pencereler `site.monitor.audit.*`). Self-scope okuma emsali:
  `GET /api/me/audit` (AuthController:457) → `auditLogRepo.findOwnFiltered(username, …)`.
  DİKKAT: audit_log retention'a tabidir (`site.monitor.audit.retention-days`) — giriş geçmişi
  ufku bununla sınırlı; UI'da not edilir.
- **Yüzey adayı hazır:** `myactivity` sekmesi (`App.jsx` VALID_TABS ~106, render ~1150
  `<MyAuditLog loginInfo={loginInfo} />`; Nav'da `UserCheck` ikonlu, herkese açık ~87).
  `MyAuditLog.jsx` `audit-viewer` CSS ailesini kullanır, olay/sonuç filtreleri + sayfalama var.
  `LastLoginInfo.jsx` iki parça ihraç eder: `LastLoginNotice` (App girişte) +
  `LastLoginPopoverLines` (Nav kullanıcı popover'ı) — "son giriş" verisi login yanıtından geliyor.
- **UA ayrıştırıcı YOK** (backend/frontend grep'lendi) — K4 gereği yazılacak.
- **/me ailesi:** `/me`, `/me/photo`, `/me/change-password`, `/me/permissions`, `/me/audit`,
  `/session/ping`, `/login`, `/logout` (AuthController 90–457). `mustChangePassword` kısıtlı yol
  listesi CLAUDE.md'de sabit — yeni /me uçları o listeye EKLENMEZ.
- **Emsaller:** admin tekil oturum sonlandırma `POST /api/admin/system/terminate-session`
  (system_health.terminate); `UserActivityService` admin tarafında login serileri/aktif kullanıcı
  listesi üretir (K8 emsali); `LoginIssueReport`/`ClientErrorController` kullanıcı-bildirim
  altyapısı mevcut (K7 köprüsü — sıfırdan yazma, `.claude/commands/sorun-bildir.md` bağlamı).

## Faz 0 — Keşif doğrulaması + karar noktaları

Gerçekleri doğrula (özellikle: login'deki `invalidateAllForUser`'ın tarihçesi/gerekçesi — güvenlik
duruşu mu, tek-oturumun yan ürünü mü). Sonra kararları seçenek + öneriyle sun (`tasarim` modunda
`docs/` altına karar notu):

- **K1 — Yerleşim:** (a) **`myactivity` sayfası iki görünüme ayrılır (ÖNERİLEN):** üstte
  `SegmentedControl` — "Cihaz Geçmişi" (yeni, varsayılan görünüm) / "Denetim Kayıtlarım" (mevcut
  MyAuditLog tablosu aynen). Nav girişi ve sekme anahtarı değişmez, kullanıcı alışkanlığı bozulmaz.
  (b) ayrı `devices` sekmesi (VALID_TABS + Nav — kalabalık). (c) Nav kullanıcı popover'ına mini
  "son 3 giriş" + tam sayfaya bağlantı (E olarak da eklenebilir).
- **K2 — Hatırlanan cihaz politikası (EN KRİTİK KARAR):** (a) **mevcut tek-cihaz politikası
  korunur:** login'deki `invalidateAllForUser` kalır; ekranda en fazla bir "hatırlanan cihaz"
  satırı olur, satır eylemi onu iptal eder. Ekran ağırlıkla GEÇMİŞ gösterir — Airbnb'deki çoklu
  satır-kapatma bizde tarihsel kayıt olarak kalır. (b) **çoklu hatırlanan cihaza genişlet
  (Airbnb'ye tam eşlenik — ÖNERİLEN, onay şart):** `invalidateAllForUser` login'den KALDIRILIR
  (parola değişiminde ve "diğerlerinden çıkış"ta kalır); her cihazın kendi token'ı yaşar, meta'sıyla
  listelenir, satır başına iptal edilir. Tek-AKTİF-oturum yine korunur (remember-me yalnız otomatik
  giriştir; giriş anında diğer oturum yine düşer) — güvenlik duruşu değişimi: ayakta duran kalıcı
  kimlik sayısı artar; hafifleticiler: 7 gün TTL, bu ekrandan görünürlük+iptal, K3 hash, parola
  değişiminde toplu iptal. İkisi de UI'yı değiştirir — önce karar.
- **K3 — Token sertleştirme:** token'lar DB'de HAM duruyor (DB sızıntısı = oturum çalma).
  (a) **SHA-256 hash'le sakla (ÖNERİLEN):** cookie'de ham, DB'de hash; deploy anında eski
  token'lar geçersizleşir (7 gün TTL ile kabul edilebilir — kullanıcı bir kez şifre girer).
  (b) dokunma (bu geliştirmenin dışında tut, borç olarak raporla).
- **K4 — Cihaz özetleyici:** `UserAgentSummary.of(ua)` → `{os, browser, device}` — tablo-güdümlü
  küçük ayrıştırıcı (sıra ÖNEMLİ: Edg→Edge, OPR→Opera, sonra Chrome; Safari en sonda; OS:
  Windows NT x → "Windows", Mac OS X, Android, iPhone/iPad → iOS, Linux; device: mobil ipuçları →
  `Smartphone` ikonu). Bilinmeyen → `{null,null}` → UI "Oturum" genel etiketi. Testle pinlenir;
  hem cihaz satırlarında hem (E) MyAuditLog/AuditLogViewer'ın UA sütununu insancıllaştırmakta
  kullanılabilir — kapsamını sor.
- **K5 — Konum gösterimi:** audit geo alanları; private/RFC1918 IP → "Kurum ağı" etiketi (geo
  boş diye satır bozulmaz); IP'nin kendisi satır genişletmesinde (`CopyButton`). Login kaydında
  geo'nun DOLU geldiğini doğrula (GeoIpService login'de mi çağrılıyor, asenkron audit'te mi —
  boşsa Faz 2'de zenginleştirme noktası).
- **K6 — Geçmiş kapsamı:** giriş geçmişi varsayılan son N kayıt/sayfalı (öneri: 50/sayfa,
  audit-retention ufku notuyla); başarısız denemeler ayrı katlanır bölümde DAHİL (öneri: evet —
  BRUTE_FORCE/RATE_LIMITED rozetleriyle; kullanıcı kendi hesabına saldırıyı görür); LOGOUT
  kayıtları gösterilsin mi (öneri: hayır — gürültü; satır genişletmesine "oturum kapandı" bilgisi).
- **K7 — "Bu girişi ben yapmadım":** şüpheli giriş satırında düğme → mevcut LoginIssueReport
  altyapısına bağlanan bildirim (admin maili + kayıt) + yönlendirme diyaloğu ("Parolanı değiştir" +
  "Diğer cihazlardan çık" kısayolları). Dahil mi (öneri: EVET — ekranın güvenlik değerini ikiye
  katlar, altyapı hazır), yoksa E-listesine mi.
- **K8 — Admin görünümü:** UserManager/UserEditModal'dan seçili kullanıcının cihaz geçmişini aynı
  bileşenle görüntüleme (salt-okunur; `requireAuditAccess`/`audit_log.read` kapısıyla — self-scope
  kuralı DELİNMEZ, ayrı admin ucu). Öneri: sonraki faz (E8) — çekirdek kullanıcı ekranı önce.

## Faz 1 — Veri modeli ve şema

- **`remember_me_tokens` meta kolonları:** `created_at`, `last_used_at`, `ip_address`,
  `ua_summary` (K4 çıktısı kısa dize), `ip_city`/`ip_country` (opsiyonel — geo anlık çözülür,
  cache'li). K3a seçilirse `token` kolonu hash'e döner (kolon adı `token_hash`e geçirilir,
  `applySchemaPatches`'te eski kolon adı için idempotent geçiş + eski satırların temizliği).
  K2b seçilirse unique(username) BENZERİ bir kısıt VARSA kaldırılır (bugün yok — doğrula).
- Patch satırları + indeks (`username`), `cleanExpired` mevcut saatlik temizlik aynen.
- Giriş geçmişi için YENİ TABLO AÇILMAZ — kaynak audit_log'dur (kural: veri tek yerde;
  retention ufku UI notu). `findOwnFiltered` benzeri, LOGIN/LOGIN_FAILED'e daraltılmış self-scope
  sorgu(lar) `AuditLogRepository`'ye eklenir.

## Faz 2 — Backend: okuma

- **`GET /api/me/devices`** → `{ current: {uaSummary, ip, city/country|kurumAğı, loginAt,
  lastSeenAt, rememberedOnThisDevice}, remembered: [{id, uaSummary, ip, geo, createdAt,
  lastUsedAt, isThisDevice}], retentionDays }` — `current` bilgisi session + kullanıcının audit
  LOGIN kaydından; `isThisDevice` eşleşmesi cookie'deki token üzerinden (değer yanıtta YOK).
- **`GET /api/me/devices/logins?page&size&failed=`** → giriş geçmişi satırları: `{at, uaSummary,
  uaRaw(genişletme için), ip, city, country, org, anomalyFlags[], outcome, failureReason}`.
- `RememberMeService.validate` başarılı olduğunda `last_used_at` + IP güncellenir (auto-login'in
  "son kullanım" izi); login'de token üretimi meta ile kaydeder.
- K5 doğrulaması: LOGIN audit satırında geo boş kalıyorsa `AuditService`'in login yolunda
  GeoIpService zenginleştirmesini tamamla (asenkron, best-effort).

## Faz 3 — Backend: eylemler

- **`DELETE /api/me/devices/remembered/{id}`** — yalnız kendi username'ine ait token (aksi 404);
  audit: `REMEMBER_TOKEN_REVOKE`. Mevcut cihazın token'ı iptal edilirse cookie de düşürülür.
- **`POST /api/me/devices/logout-others`** — `invalidateAllForUser` (mevcut cihazın token'ı K2b'de
  hariç tutulur — servise `exceptTokenHash` parametresi) + audit `SESSION_REVOKE_ALL`. Tek-oturum
  modelinde başka CANLI oturum zaten olamayacağı için ek oturum öldürme gerekmez — bu gerçek
  yanıt mesajında da dürüstçe ifade edilir ("hatırlanan girişler iptal edildi").
- (K7) **`POST /api/me/devices/report-login`** — `{auditId}` self-scope doğrulanır →
  LoginIssueReport kaydı + admin bildirimi (mevcut LoginIssue* akışı); audit `LOGIN_DISPUTED`.
- `mustChangePassword` kısıtlı yol listesine bu uçlar EKLENMEZ (kural 2); parola değişimi
  `invalidateAllForUser`'ı zaten çağırıyor mu doğrula — çağırmıyorsa EKLE (bilinen iyi pratik).

## Faz 4 — Frontend: Cihaz Geçmişi görünümü

- **`components/DeviceHistoryPanel.jsx`** (MyAuditLog sayfasının K1 görünümü):
  - **Bölüm 1 — Bu cihaz:** kart — `Monitor/Smartphone` ikonu, "Windows · Chrome" başlığı,
    `MEVCUT OTURUM` rozeti (mevcut rozet/pill sınıfı), "İstanbul, TR · 24 Ağu 2026 01:15" meta
    satırı (`formatDateSec`), son etkinlik, "beni hatırla bu cihazda açık" çipi + kapat.
  - **Bölüm 2 — Hatırlanan cihazlar (K2):** Airbnb satır düzeni birebir: ikon · başlık · meta ·
    sağda "Oturumu kapat" (`Dialog` onayı → `Toast` sonucu; satır listeden düşer). Boş durumda
    empty-state ("Hatırlanan cihaz yok — girişte 'Beni hatırla'yı işaretlersen burada görünür").
  - **Bölüm 3 — Giriş geçmişi:** satırlar aynı görsel dilde, salt-okunur; anomali rozetleri
    (amber "Mesai dışı", kırmızı "Alışılmadık IP"/"Coğrafi hız"); satır genişletmesi: ham UA,
    IP (`CopyButton`), org/reverse-host; (K7) "Bu girişi ben yapmadım" düğmesi; `PaginationBar`;
    başlık yanında retention notu ("Son {N} günün kayıtları").
  - **Bölüm 4 — Başarısız denemeler (K6):** katlanır; kırmızı tonlu satırlar + failureReason.
  - **Güvenlik eylem çubuğu:** "Diğer tüm cihazlardan çıkış yap" (onaylı) + parola değiştirme
    kısayolu (mevcut PasswordChangeModal'a).
- `LastLoginPopoverLines` (Nav popover) altına "Cihaz geçmişini gör →" bağlantısı (myactivity'e).
- UA özetleme SUNUCUDA yapılır (K4) — frontend ikinci parser YAZMAZ; ikon seçimi `device`
  alanından. Savunmacılık: eksik alanlı satır düşürülür, panel çökmez.
- i18n `dev.*` anahtar ailesi TR+EN; dark theme el doğrulaması.

## Faz 5 — Entegrasyon

- `MyAuditLog.jsx` K1a yapısına geçirilir (mevcut tablo davranışı ve URL/filtre alışkanlıkları
  bozulmaz — regresyon testi); `loginInfo` prop'u yeni panelin "Bu cihaz" kartını besleyebilir.
- Login akışı: `remember_me` token üretimi meta ile; `LastLoginNotice` metnine (varsa) "yeni
  cihaz" vurgusu (E1 ile birlikte karar).
- Nav popover bağlantısı; HelpPage/monitorGuides kapsam dışı (monitör değil) ama kullanıcı
  yardım metni `HelpPage`'e kısa bölüm olarak eklenir mi — sor (öneri: evet, 3-4 satır).

## Faz 6 — Testler

- **Backend:** `UserAgentSummaryTest` (tablo-güdümlü: Edge/Chrome sırası, iOS/Android, bilinmeyen
  → null); `/me/devices*` uçları — self-scope IDOR (başka kullanıcı token id'si → 404; audit id'si
  → 404), token değeri/oturum id'sinin YANITTA OLMADIĞI (serileştirme testi), revoke sonrası
  auto-login'in gerçekten düştüğü (RememberMeService entegrasyonu), logout-others'ın mevcut cihazı
  (K2b) koruduğu, `last_used_at` güncellemesi, K3a hash doğrulama + eski ham token'ın reddi;
  parola değişiminde toplu iptal; audit olaylarının yazıldığı. `RepositoryWriteTransactionGuardTest`
  yeşil.
- **Frontend:** `DeviceHistoryPanel.test.jsx` — bölümlerin render'ı, MEVCUT OTURUM rozeti, revoke
  onay akışı (mock api), anomali rozetleri, boş durumlar, bozuk satır düşürme, retention notu;
  MyAuditLog mevcut davranış regresyonu; `i18n-parity` yeşil.
- Güvenlik senaryosu testleri: `mustChangePassword` kullanıcısının bu uçlara ERİŞEMEDİĞİ
  (kısıtlı yol listesi genişlemedi); grandfathered (`activeSessionId=null`) kullanıcıda ekranın
  çökmeden "bilgi yok" gösterdiği.

## Faz 7 — Doğrulama, smoke ve rapor

1. `mvn -B clean verify` → `npm run test` → `npm run build` → `mvn package -DskipTests` →
   `start-local.ps1` → `/health` UP.
2. Smoke: "beni hatırla" ile giriş → panelde bu cihaz + hatırlanan satır; ikinci tarayıcıdan
   giriş → ilk oturumun düştüğü ve geçmişte iki LOGIN satırı; hatırlanan cihazı iptal → o
   tarayıcıda auto-login'in çalışmadığı; yanlış parolayla deneme → başarısız bölümünde göründüğü;
   (K7) "ben yapmadım" → admin tarafına bildirim; dark theme; TR/EN.
3. Rapor: dosya listesi, test çıktıları, K kararları, bilinen sınırlar (giriş geçmişi
   audit-retention ufkuyla sınırlı; geo private IP'de yok).

## Zenginleştirme önerileri (kullanıcıya sun — şimdi mi sonra mı)

- **E1 — Yeni cihaz e-postası:** ilk kez görülen cihaz özeti+IP kombinasyonundan giriş olduğunda
  kullanıcıya bilgi maili (SMTP altyapısı hazır; opt-in ayarı) — "hesabım ele mi geçirildi"
  sorusunun proaktif cevabı.
- **E2 — Nav popover mini listesi:** kullanıcı menüsünde son 3 giriş + "tümünü gör" (K1c'nin
  hafif hâli).
- **E3 — UA insancıllaştırmanın yaygınlaştırılması:** `UserAgentSummary` MyAuditLog ve admin
  AuditLogViewer'ın UA sütunlarında da kullanılır (ham UA tooltip'e iner).
- **E4 — Harita yerine dürüst konum çizgisi:** giriş geçmişinde şehir değişimlerini vurgulayan
  basit "konum değişti" ayracı (harita bağımlılığı almadan coğrafi hikâye).
- **E5 — Oturum süresi istatistiği:** LOGIN→LOGOUT/lastSeen eşleşmesinden "ortalama oturum süren"
  mini istatistik şeridi.
- **E6 — Güvenlik skoru kartı:** parola yaşı (PasswordHistory) + hatırlanan cihaz sayısı +
  anomalisiz gün — küçük bilgilendirici kart (oyunlaştırmadan, iddiasız).
- **E7 — Admin tarafı köprüsü:** AuditLogViewer'da bir LOGIN satırından o kullanıcının cihaz
  geçmişine atlama (K8 ile birlikte).
- **E8 — Admin cihaz görünümü:** K8'in tam hâli — UserEditModal'da salt-okunur DeviceHistoryPanel.

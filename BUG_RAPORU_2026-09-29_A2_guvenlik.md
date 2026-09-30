# BUG RAPORU — Eksen A2 (E2) · Güvenlik: IDOR, yetki mantığı, kapsamlı yönetici, SSRF, gizli veri sızıntısı · 2026-09-29

Kapsam: `D:\site-monitor` main, v20.91.0 + commit edilmemiş "ortam adı ayarı" değişikliği. Salt okuma; kod
değiştirilmedi, test/derleme/sunucu koşturulmadı. Yöntem: 55 controller'ın 485 ucu envanterlendi (yol, yöntem,
kapı çağrıları), kapısız/yalnız-izin görünen her uç kaynakta açılıp doğrulandı; `AuthInterceptor`,
`SessionScope`, `PermissionService/Catalog`, `RememberMeService`, `IdentityMask`, `RequestLoggingFilter`,
`RequestPathFirewallFilter`, `OriginCheckFilter`, `SsrfGuard/SafeRedirect`, dış HTTP istemcileri, SQL Oyun Alanı,
CSV/PDF/görsel uçları, ayar denetleyicileri ve bugünkü ortam-adı diff'i okundu. Baseline (`BUG_RAPORU_6/7`,
`BUG_RAPORU_2026-09-23`, 2026-07 denetimi, 2026-09-28/29 regresyon raporları) satır satır RE-CHECK edildi.

## Genel değerlendirme

Yetki çekirdeği sağlam: tek kapı (`SessionScope.isGlobalAdmin` = rol ADMIN **ve** `viewTeamIds == null`);
dokuz izleme türü `denyIfNotViewable`/`canOperateTeam`/`effectiveTeam` zinciriyle; alarmlar `requireAlertScope`,
olaylar `requireIncidentRead/Write`, bakım pencereleri `requireManageable + requireWindowScope`, dört sır
denetleyicisi `requireNotScopedAdmin`, `GLOBAL_ONLY` anahtarları `AppSettingsService.save`'de tek kapıdan
(`!isGlobalAdminInRequest`) korunuyor; yeni `site.monitor.environment` anahtarı `GLOBAL_ONLY`'ye eklenmiş,
biçimi doğrulanıyor. `Redirect.NORMAL` üretimde yok; 22 dış-istek dosyasının hepsi `SsrfGuard` çağırıyor,
gövdeler tavanlı. Baseline'daki güvenlik maddelerinin **tamamı kapalı** (aşağıdaki RE-CHECK tablosu).

Bu turda sistemik açık yok. İki YÜKSEK bulgu var; ikisi de "kapı doğru ama kapının dışında kalan bir yol"
sınıfından: (1) sentetik izleme (k6) çalıştırma yetkisinin kod-yürütme yüzeyi pod dosya sistemine karşı
sınırlanmamış; (2) hesap durumu değişikliklerinin (pasifleştirme/silme/rol düşürme/parola sıfırlama) canlı
oturuma yansımaması.

| Önem | Adet |
|---|---|
| KRİTİK | 0 |
| YÜKSEK | 2 |
| ORTA | 2 |
| DÜŞÜK | 8 |

---

## Uç envanteri (özet)

485 uç, 55 controller. "Yalnız izin/yardımcı" sütunundaki uçların kapısı bir helper metodun (`requireXxx`)
ya da servis katmanının (`Actor.globalAdmin`, `Scope`) içindedir ve tek tek okunmuştur.

| Controller | Uç | Nesne/takım kapılı | Yalnız izin/yardımcı | Not |
|---|---|---|---|---|
| MonitoringController | 101 | 78 | 15 | `*/history` → `runHistory`/`readDomain` (KAPILI) |
| AdminController | 78 | 77 | 1 | `tour-reset` → `requireTeamScopedAdmin` |
| WeeklyReportController | 35 | 5 | 25 | servis `Actor.globalAdmin`/`isTeamManager`; token uçları PUBLIC (token = yetki) |
| SystemController | 31 | 21 | 9 | `system_health.read` her kademe (ürün kararı); **D3: `GET /api/admin/system` kapısız** |
| CertificateController | 23 | 13 | 6+4 | export `listScope`; durum uçları bilinçli PUBLIC-benzeri |
| AuditController | 16 | 12 | 3+1 | ekip kapsamı `auditReadScope` |
| AuthController | 15 | 2 | 4+9 | login/logout/`/me/*` self-scope |
| IncidentController | 14 | 3 | 11 | `requireView/Manage` + `requireIncidentRead/Write` |
| Settings 4'lü (Ldap/Smtp/Secret/Database) | 11 | — | hepsi | `requireNotScopedAdmin` + matris (çift kapı) |
| Kalan 43 controller | ~161 | ~150 | ~11 | küçük yüzeyler; tümü kapılı |
| **Toplam** | **485** | **311** | **174** | script "kapısız" saydığı 50 ucun 49'u helper/servis/self-scope/PUBLIC ile kapılı; 1 gerçek boşluk (D3) |

---

## YÜKSEK

### Y1 · `ScriptedCheckerService.java:965-983` + `ProcessProbe.java:118-131`, `MonitoringController.java:5019-5028` — sentetik (k6) izleme kod-yürütme yüzeyi pod dosya sistemine karşı sınırlanmamış; JVM ortam sırları okunabilir

Kanıt zinciri: `PermissionCatalog.POLICY_UPGRADES` (`PermissionService.java:220`) `monitoring.scripted`
edit+execute'u **USER varsayılanına** açtı (2026-08-24 kararı, riski bilinçle kabul edildi). Kaydetme öncesi
tek script denetimi `scanScriptOrError` yalnız *sabit-kodlu gizli değer* arıyor (`:5019-5028`), politika
varsayılanı `WARN` (engellemiyor). Script `k6 run … <tempfile>` ile pod içinde, uygulamanın kendi uid'siyle
koşuyor (`Dockerfile:79 USER appuser`, `:100` JVM aynı kullanıcı). `ProcessProbe.run(…, isolatedEnv=true)`
yalnız k6 sürecinin **ortam değişkenlerini** kısıtlıyor; **dosya sistemi** kısıtlanmıyor — k6'nın init
bağlamı dosya okuma yeteneği taşır ve script sonucun bir kısmını `output_tail` olarak geri döndürebilir
(`ScriptedCheck.outputTail`, ekranda `scriptedExitCodes.js` gösteriyor).

**Neden bug:** Ortam TEK pod; JVM'in okuyabildiği her dosya k6'nın da okuyabildiği dosyadır. Helm sırları
`envFrom.secretRef` ile pod ortamına giriyor (`deployment.yaml:80-83`: `DB_PASSWORD`, `SPRING_MAIL_PASSWORD`,
`SITE_MONITOR_SECRET_KEY`, `HTTP_PROXY_PASS`). `SITE_MONITOR_SECRET_KEY`'in ele geçmesi tüm AES-GCM şifreli
sırların (SMTP/LDAP bind parolaları) çözülmesi demektir. `isolatedEnv` k6 **alt sürecinin** ortamını
temizliyor ama JVM süreç ortamı `/proc/1/environ` üzerinden okunabilir kalıyor; ayrıca `application*.properties`,
`frontend/dist`, `data/` de erişilebilir. Kendi takımının bir sentetik izlemesini kurabilen sıradan bir USER
bu yüzeye sahip. Bu, takım-izolasyonundan bağımsız bir yerel-ayrıcalık/sırr-açığa-çıkma yüzeyidir.

**Not:** proje bu riski kısmen ele almış — `--blacklist-ip` (SSRF), `isolatedEnv` (env), `--rps` tavanı, süre
bütçesi, `SecretMask` çıktı maskesi hepsi yerinde ve iyi. Eksik olan tek halka **dosya sistemi**dir.

**Çözüm (öncelik sırası):** (a) `open()` çağrılarını statik tarayıcıda engelle (mevcut `scanScriptOrError`
deseni + `ScriptedTemplateRules` regex ailesi); (b) k6 sürecini bir kısıtlı çalışma dizinine hapset ve
mümkünse `readOnlyRootFilesystem` altında yalnız temp'e izin ver; (c) yapısal çözüm: k6'yı ayrı, sırsız bir
sidecar/konteynerde (kendi uid, boş secret ortamı, salt-okunur mount yok) koştur. En azından (a) yayın öncesi
kapatılmalı; USER'a açık kod yürütme kararı bu haliyle sırların pod'da düz dosya olarak durmasına dayanıyor.

### Y2 · `SystemController.java:311-332` (terminate tek yol), `UserService.updateUser/deleteUser/adminAutoResetPassword` — hesap durumu değişiklikleri canlı oturuma yansımıyor

`AuthInterceptor.preHandle` her `/api/**` isteğinde yalnız iki şeye bakıyor: (1) oturum süperse edilmiş mi
(`isSessionSuperseded` — `activeSessionId` farklı mı), (2) `mustChangePassword`. Kullanıcının **rolü, takım
üyelikleri, `active` bayrağı ve parolası** oturum niteliklerinden (`viewTeamIds`, `systemRole`, `authenticated`)
okunuyor ve bunlar `populateSession` ile YALNIZ girişte dolduruluyor. `AdminController.applyUserUpdate`
(pasifleştirme/rol düşürme/takım daraltma), `deleteUser` ve `adminAutoResetPassword` `activeSessionId`'ye
DOKUNMUYOR; oturumu süperse eden tek yol `terminateActiveSession` ve o da yalnız `POST
/admin/system/terminate-session`'dan (ayrı, elle bir eylem) çağrılıyor.

**Neden bug:** Bir hesabı pasifleştirmek, silmek, ADMIN→USER düşürmek ya da parolasını sıfırlamak, o hesabın
o anda AÇIK olan oturumunu düşürmüyor: kullanıcı 24 saatlik oturum ömrü (`session.timeout=24h`) boyunca eski
rolü/kapsamıyla çalışmaya devam edebiliyor. Güvenlik ihlali şüphesiyle "hesabı kapat/rolü düşür" yapan admin,
oturumu ayrıca elle sonlandırmadıkça saldırganı DIŞARI ATMIYOR. Aynı ilke `changePassword`'da remember-me
için doğru uygulanmış (`invalidateAllForUser`) ve `AuthInterceptor` çerez yolunda `active`/lockout kontrol
ediyor — ama HttpSession yolu bu kontrolleri istek başına tekrarlamıyor.

**Çözüm:** `updateUser` (active=false ya da rol/kapsam daralması), `deleteUser` ve `adminAutoResetPassword`
sonunda hedefin `activeSessionId`'sini `TERMINATED:` sentinel'e çevir ve `invalidateAllForUser` çağır — yani
`SystemController.terminateSession`'ın yaptığını bu yollara da bağla. Alternatif/ek: `AuthInterceptor`'a
istek başına hafif bir `active`/rol tazeliği (mevcut `activeSessionCache` deseniyle debounce'lu) ekle.

---

## ORTA

### O1 · `RequestLoggingFilter.java:67-92` — `bind_password` / `admin_password` gövde maskesinde yok (TRACE açıkken kimlik bilgisi log'a düşer)

`SENSITIVE_FIELD_NAMES` ~60 varyant kapsıyor ama LDAP `bind_password` (LdapSettings kaydetme gövdesi,
`LdapSettingsService.java:116`) ve admin parola-doğrulama `admin_password` (`AuthController` `client.js:983`,
`AdminController.autoResetPassword` gövdesi) listede YOK. `smtp_password`/`ldap_password` var ama telde giden
alan adı `bind_password`. TRACE (`log.isTraceEnabled()`) açıkken bu iki uç gövdesi maskesiz loglanır; loglar
30 gün saklanıyor, log okuyabilen bir operatör LDAP bind parolasını ve bir admin'in parolasını ham görür.

**Neden ORTA (KRİTİK değil):** TRACE varsayılan kapalı (`com.sitemonitor=DEBUG`); yalnız bilinçle açıldığında
tetiklenir. `secret-tools` uçları `BODY_NEVER_LOGGED` ile zaten korunuyor ama bu iki alan farklı uçlarda.

**Çözüm:** `SENSITIVE_FIELD_NAMES`'e `bind_password|bindPassword|admin_password|adminPassword|
current_admin_password` ekle; ya da `/api/admin/ldap/settings` ve `/api/admin/users/*/auto-reset-password`'ü
`BODY_NEVER_LOGGED` listesine al.

### O2 · `IncidentService.java:60-61` + `WeeklyReportService.java:126-127` — olay/haftalık-rapor görsel yüklemesinde `image/gif` kabul (login-help sertliğiyle asimetri)

`ALLOWED_IMAGE_TYPES = {png, jpeg, gif, webp, bmp}` ve doğrulama YALNIZ istemci-verili `Content-Type`'a bakıyor
(`file.getContentType()`), gerçek sihirli-bayt yok. Kardeş yüzey `LoginHelpController` (kimliksiz kullanıcı)
`IMAGE_DATA_URL` regex'iyle yalnız png/jpeg kabul ediyor ve SVG'yi bilinçle dışlıyor. Olay/haftalık görselleri
`/api/incidents/images/{id}` ve `/api/weekly-reports/images/{id}` üzerinden `Content-Type` başlığıyla geri
sunuluyor (`serveImage`), ama iframe/`<img>` bağlamı ve `Cache-Control: private` ile riskin çoğu kapanıyor;
yine de içerik-tipi yalnız istemci beyanına dayandığından yanlış-tipli/çift-format dosya saklanabilir.

**Neden ORTA/DÜŞÜK sınırı:** yükleme `incidents.manage`/`weekly_reports.crud` (oturumlu, takım-kapsamlı)
gerektiriyor — kimliksiz değil. Görsel `Content-Type` sunucunun sakladığı değerle dönüyor, `text/html` gibi
bir tip zaten allow-list'te yok. Bu yüzden ORTA'nın alt sınırı.

**Çözüm:** İçerik-tipini istemci beyanından değil, ilk bayttan (magic number) türet; login-help ile tek bir
`ImageBytes.detect` yardımcısında birleştir. Depolanan `Content-Type` her zaman doğrulanmış değerden yazılsın.

---

## DÜŞÜK

- **D1 · `ScriptedCheckerService.java:591` + `AppSettingsCatalog.java:76`** — `site.monitor.scripted.k6-api-address`
  varsayılanı `127.0.0.1:0` (kapalı, güvenli) ve `GLOBAL_ONLY`. Doğru; ama k6 REST API'si açık bir adrese
  (`0.0.0.0:6565`) ayarlanırsa pod içinden kimliksiz kontrol yüzeyi doğar. Kapı global-admin'de ve varsayılan
  güvenli olduğundan DÜŞÜK; yorumla "asla dış adrese bağlama" notu güçlendirilebilir.

- **D2 · `GeoIpService.java:36`** — `HttpClient.newBuilder()` düz kuruluyor (kurumsal TLS güveni yok) ve hedef
  `http://ip-api.com` (düz HTTP, dış servis). Bu istemci `SsrfGuard`'dan geçmiyor; ama URL yapılandırma-sabiti
  ve giriş IP'siyle format'lanıyor (kullanıcı host'u değil), yani SSRF yüzeyi yok. `ofInputStream` + tavanlı
  okuma doğru. Giden istek yalnızca dış GeoIP servisine gittiği için DÜŞÜK; iç ağa çıkmıyor.

- **D3 · `SystemController.java:50-61` `GET /api/admin/system`** — envanter script'inin gerçekten kapısız bulduğu
  tek uç: yalnız `AuthInterceptor` (oturum) var, `system_health.read` bile sorulmuyor. Döndürdüğü veri sistem
  sağlık özeti (scheduler durumu, DB ms, heartbeat, build sürümü, aktif domain sayısı) — takım verisi/PII yok
  ama kardeş uçların hepsi (`/db-stats`, `/metrics` …) `system_health.read` istiyor. Tutarlılık için aynı izin
  eklenmeli. Düşük etki (özet metrik).

- **D4 · `ClientIpResolver.java` + `AuthController` login rate-limit** — XFF `index=0` (soldan) varsayılanı tek
  sanitize-eden proxy (NetScaler) için doğru; ama yanlış yapılandırılmış bir ortamda istemci XFF'i spoof edip
  IP-bazlı login rate-limit'i (`blockedUntil` haritası) atlayabilir. Per-username DB lockout ikinci katman
  olarak bunu kapatıyor (memory notu da bunu söylüyor). Yapılandırma-bağımlı, DÜŞÜK; `docs/RUNBOOK.md`'de
  "index'i ortamdaki hop sayısına göre ayarla" notu netleştirilebilir.

- **D5 · `PingCheckerService.buildPingArgs` + `MonitoringController.testPing/createPing`** — `host` `ProcessProbe`
  (list-arg, shell yok) ile `ping`'e argüman olarak veriliyor; komut enjeksiyonu yok. Ancak host için
  `DomainNames.validate`/`SsrfGuard` uygulanmıyor (diğer checker'ların aksine): `-`/`--` ile başlayan bir host
  ping bayrağı olarak yorumlanabilir ya da iç ağ adresi ping'lenebilir. `count`/`timeout` tavanlı. `monitoring.crud`
  (USER'a açık) gerektiriyor. ICMP salt erişilebilirlik döndürdüğü ve içerik sızdırmadığı için DÜŞÜK; yine de
  `createPing`/`testPing`'de host'u doğrula (`--` reddet).

- **D6 · `AppUser` PII alanları** — `phone`, `employeeId`, `managerSicil` `@JsonIgnore` DEĞİL; `passwordHash`,
  `photoBase64`, `lastLoginIp`, `activeSessionId` doğru işaretli. `phone`/`employeeId` `GET /api/admin/users`
  ham entity'sinde TEAM_ADMIN'e/kapsamlı müdüre dönebilir. `TeamDirectoryController`/`UserDirectoryController`
  beyaz-liste projeksiyonu bunları dışlıyor (memory kuralı: telefon/sicil ASLA), `SystemController.maskEmployeeIds`
  sicili maskeliyor — ama `/api/admin/users` (users.list, TEAM_ADMIN'de açık) ham entity döndürdüğü için
  `phone` bu yoldan görünür. Kullanıcı kararı "sicil yalnız global admin"; telefon için de aynı ilke geçerliyse
  `phone`/`employeeId`'yi `@JsonIgnore` yapıp ihtiyaç olan yerde beyaz-listeyle döndür. DÜŞÜK (doğrulanmalı:
  telefonun TEAM_ADMIN'e görünmesi kabul mü).

- **D7 · `AuditController` ekip-kapsamlı denetim okuma (`auditReadScope`)** — USER/TEAM_ADMIN kendi ekip
  arkadaşlarının denetim satırlarını IP + konum + tarayıcı ile TAM görüyor (`teamVisible`, `TeamActorScope`).
  Bu 2026-09-25 ürün kararı ("ekip üyeleri görebilsin, tam ayrıntı; PII maskesi yok") — bilinçli, bulgu değil.
  Yalnız kayıt için: `IdentityMask` sistem-sağlık payload'ında IP'yi kapsamlı kullanıcıdan düşürürken, denetim
  logu ekip kapsamında IP'yi gösteriyor; iki yüzeyin PII politikası ayrışık ama ikisi de bilinçli karar.
  Doğrulanmalı sayıldı, YÜKSEK'e taşınmadı.

- **D8 · `WeeklyReportController` token onay uçları (`approve-link/*`, PUBLIC)** — `esc()` ile XSS'e karşı
  kaçış yapılıyor, GET mutasyon yapmıyor, başarısız token denemesi `recordSecurityEvent` ile denetleniyor.
  Token = yetki (login'siz onay bilinçli). Doğru kurulmuş; kayıt için: token'ın entropisi/TTL'i bu turda
  ayrıca ölçülmedi (servis katmanı, kapsam dışı) — `WeeklyReportService.approvalTokenStatus` ile
  doğrulanabilir.

---

## Baseline RE-CHECK (güvenlik maddeleri)

| Kaynak | Madde | Durum |
|---|---|---|
| 2026-07 denetimi | G1 port-monitör SSRF → SsrfGuard | ÇÖZÜLMÜŞ ✓ (22 dosya SsrfGuard, `blacklistCidrs` k6'ya da) |
| 2026-07 | G2 MonitoringController `*/history` BOLA → canView | ÇÖZÜLMÜŞ ✓ (`denyIfNotViewable`/`readDomain`) |
| 2026-07 | RememberMe SHA-256 token | ÇÖZÜLMÜŞ ✓ (`RememberMeService.sha256`, düz saklama yok) |
| 2026-07 | SecretCipher prod fail-fast | ÇÖZÜLMÜŞ ✓ |
| 2026-07 | SqlPlayground FORBIDDEN_FUNCTIONS | ÇÖZÜLMÜŞ ✓ + ReadOnlyDataSource (2026-09-23) ikinci kapı |
| 2026-07 | ClientIpResolver negatif-index/sınır-dışı | ÇÖZÜLMÜŞ ✓ |
| 2026-07 | AÇIK: SQL RO ayrı DB rolü (ops) | AÇIK (ops işi; kod-seviyesi denylist + JDBC read-only yerinde, memory ile uyumlu) |
| BUG_RAPORU_6 K1 | Kapsamlı müdür global ADMIN yaratıyor | ÇÖZÜLMÜŞ ✓ (`requireAssignableRole`+`requireTeamsInManageScope`+`requireCanAdministerTarget`) |
| BUG_RAPORU_6 Y1 | 8 ayar controller'ı sır kapısı | ÇÖZÜLMÜŞ ✓ (`requireNotScopedAdmin` 4 sır + `GLOBAL_ONLY`) |
| BUG_RAPORU_6 Y2/Y3 | WeeklyReport `"ADMIN".equals` + matris eksik | ÇÖZÜLMÜŞ ✓ (`Actor.globalAdmin`, her uçta izin) |
| BUG_RAPORU_6 O3/O4/O5 | Envanter-türevi bayat teamId / check-preview / port dedup | ÇÖZÜLMÜŞ ✓ (`effectiveTeam`) |
| BUG_RAPORU_23 K1 | ScriptedAnomalyGuard kapatmada ters push | (E2 dışı; E5) — güvenlik yüzeyi değil |
| BUG_RAPORU_23 Y1 | PageFetchCore Authorization hop taşıması | ÇÖZÜLMÜŞ ✓ (`PageFetchCore.java:240` `originHost.equalsIgnoreCase(host)` kapısı) |
| BUG_RAPORU_23 Y2 | KeywordChecker düşürme kontrolü | ÇÖZÜLMÜŞ ✓ (`SafeRedirect.isDowngrade`) |
| BUG_RAPORU_23 Y3 | SystemController.userTimeline IP sızıntısı | ÇÖZÜLMÜŞ ✓ (`IdentityMask` yapı-bağımsız, `identityVisible`) |
| BUG_RAPORU_23 O4 | `manager_sicil` yetki kapısız managerId yazıyor | ÇÖZÜLMÜŞ ✓ (`UserService.java:852` `isGlobalAdminInRequest` kapısı) |
| BUG_RAPORU_23 O5 | restoreChange tarihsel takımdan kapı | ÇÖZÜLMÜŞ ✓ (canlı `liveTeamOf` + `canManage`) |
| BUG_RAPORU_23 O6 | `GLOBAL_ONLY` yalnız scoped kontrolü | ÇÖZÜLMÜŞ ✓ (`!isGlobalAdminInRequest`) |
| BUG_RAPORU_23 O7 | Keyword custom_headers düz metin | ÇÖZÜLMÜŞ ✓ (`customHeadersEnc`, eski kolon deprecated) |
| BUG_RAPORU_28c B1 | IdentityMask yüzey kapsamı (top_sources/details/anomalies) | ÇÖZÜLMÜŞ ✓ (yapı-bağımsız özyineli maske) |
| BUG_RAPORU_28c B2 | DbAnalytics SQL metni maskesi | ÇÖZÜLMÜŞ ✓ (`maskSqlText`) |
| BUG_REGRESYON 09-28 F2/F8 | contacts/webhook-status + webhook-test kapsamı | ÇÖZÜLMÜŞ ✓ |
| BUG_REGRESYON 09-28 (UserPush A9/B1) | teslimat/explain takım kapsamı | ÇÖZÜLMÜŞ ✓ (`scopedSearch`, `PushDecisionAccess`) |
| BUG_REGRESYON 09-27 BK1 | Yol normalizasyon baypası | ÇÖZÜLMÜŞ ✓ (`RequestPathFirewallFilter` + `lookupPath`) |
| BUG_RAPORU_6 AÇIK E2#8 | `/confirmations` takım süzgeci | ÇÖZÜLMÜŞ ✓ (`alertKeyOwnership.viewerTeams` + `canView`) |

Tüm baseline güvenlik maddeleri kapalı; hiçbiri yeniden açılmadı. Y1/Y2/O1/O2 **yeni** bulgulardır.

---

## Doğru bulunan (tarandı, temiz)

- **Kimlik doğrulama/oturum:** login oturum-sabitlemeyi kapatıyor (`oldSession.invalidate()`), remember-me
  token'ları girişte/parola değişiminde iptal, çerez yolu `active`+lockout kontrol ediyor, remember-me çerezi
  `SameSite=Strict`+HttpOnly+Secure, `mustChangePassword` 4-uç beyaz-listesi (çerez yolunda da uygulanıyor),
  `activeSessionId=null` = grandfathered (kick değil) doğru, progressive lockout kanonik username'le sayıyor.
- **IDOR:** `existsById` hiçbir yerde yetki kararı için kullanılmıyor; `{id}` uçları `findById`+nesne-yetki;
  toplu/transfer uçları (inventory/alerts/users/teams/incidents/weekly/import/userpush) kayıt başına kapsam +
  hedef-takım kapsamı (`requireTransferTarget`); 404 vs 403 varlık-sızıntısı bilinçli (activity/incident/audit
  detay → 404).
- **Kapsamlı müdür:** `isGlobalAdmin` = rol ADMIN + `viewTeamIds==null` tek kaynak; `requireNotScopedAdmin`
  sır yüzeylerinde + `GLOBAL_ONLY` anahtarlarında; `requireCanAdministerTarget` ADMIN/AUDIT hedefini korur;
  `requireAssignableRole` müdürün ADMIN/AUDIT atamasını engeller.
- **SSRF:** `SafeRedirect.NEVER` + elle hop + her hop `ssrfGuard.validate` (PageFetchCore/Http/Keyword/
  Uptime/Port/Rdap/TrWebWhois/Hsts/Webhook); metadata/link-local/loopback her koşulda blok; k6 `--blacklist-ip`;
  gövde tavanları (`HttpBodies.readCapped/readPreview`, sıkıştırma bombası `MAX_DECODED_BYTES`); kurumsal TLS
  `pinAwareOutboundSslContext`; webhook URL `SsrfGuard`+durum kodu kontrolü+maskeli log.
- **SQL Oyun Alanı:** SELECT/WITH-only + tek statement + FORBIDDEN(DDL/DML) + FORBIDDEN_FUNCTIONS(dosya/dblink/
  lo_/pg_sleep) + JDBC `setReadOnly(true)` + 30 sn timeout (servise özel template) + dış `LIMIT 1000` sarma +
  identifier regex. Global-admin kapılı.
- **CSV/formül enjeksiyonu:** tek kaynak `Csv.cell` (`=/+/-/@/\t/\r` → `'` öneki + tırnaklama), 11 dışa aktarım
  yolu (audit/history/deliveries/retention/deployment/weak-algo/cert/domain/inventory) bunu kullanıyor.
- **Gizli veri:** `RequestLoggingFilter` ~60 alan maskesi + hassas başlıklar + `secret-tools` gövde-loglamaz +
  CRLF strip; LDAP/SMTP `toClientMap` parolayı `*_set` boolean'a indiriyor; push `userpush.headers` AES şifreli
  saklanıyor, okuma yolunda `maskedHeaders`, `AuditDiff` değer-gövdesi maskesi; k6 çıktısı `SecretMask.maskValues`
  + geçici dosya yolu sanitize.
- **XSS:** e-posta gövdeleri `MailKit.esc`; mail önizleme iframe'leri `sandbox` (çoğu `sandbox=""`, birkaçı
  `allow-popups`); frontend URL'leri `safeHref`/`certDetailsModel` ile yalnız http(s); tek uyarı: aşağıda.
- **Girdi doğrulama:** login-help/client-error IP rate-limit + uzunluk + görsel boyut (decode öncesi b64 tavanı);
  diagnostics kullanıcı-başı 10/dk; k6 `--rps`+süre bütçesi+`--iterations 1`; `DomainNames.validate` regex;
  `RequestPathFirewallFilter` matris/kodlu-ayraç/kontrol-karakter reddi; `OriginCheckFilter` CSRF ikinci katmanı.
- **Container:** `runAsNonRoot`+`runAsUser 1000`+`readOnlyRootFilesystem`+`allowPrivilegeEscalation:false`+
  `seccomp`+`cap drop`; `automountServiceAccountToken:false`; sırlar `secretRef` (Y1 bu yüzden pod-içi kalıyor).

---

## Önerilen düzeltme sırası

1. **Yayın öncesi (YÜKSEK):** Y2 — hesap durumu değişikliklerini oturum-sonlandırmaya bağla (`updateUser`
   active=false/rol daralması, `deleteUser`, `adminAutoResetPassword` → `terminateActiveSession` +
   `invalidateAllForUser`). Dar, yerel; deseni `SystemController.terminateSession`'da zaten var.
2. **Yayın öncesi (YÜKSEK):** Y1 — k6 script'inde `open()` statik engeli (asgari); orta vadede sidecar/sırsız
   ortam izolasyonu. Sır pod'da düz dosya olduğu için USER'a açık kod yürütmenin sınırlanması şart.
3. **Ucuz/yüksek-etki (ORTA):** O1 — `bind_password`/`admin_password`'ü log maskesine ya da `BODY_NEVER_LOGGED`'a
   ekle (tek satır). O2 — görsel tipini magic-byte'tan türet, login-help ile birleştir.
4. **Hijyen (DÜŞÜK):** D3 (`/api/admin/system` → `system_health.read`), D5 (ping host `--` reddi), D6 (`phone`/
   `employeeId` `@JsonIgnore` — ürün kararı doğrulanınca), D1/D2/D4 yorum/doküman güçlendirmesi.

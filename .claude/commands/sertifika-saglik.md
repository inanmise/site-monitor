---
description: Dashboard sertifika kartı modalına TrackSSL tarzı "Sertifika Sağlık Kontrol Listesi" — geçerlilik/iptal/zincir/cipher/protokol/PFS/mixed-content/HSTS satırları, kalem başına Durum+Aksiyon+Değer rozeti, üstte geçerlilik + son/sonraki kontrol künyesi. tls_version & cipher_suite kalıcılaştırma, sağlık değerlendirme servisi, takım-scoped endpoint, zengin UI ve testler.
argument-hint: [tasarim|backend|frontend|hizli] (opsiyonel — boş bırakılırsa TAM kapsam koşulur)
---

# /sertifika-saglik — Sertifika Sağlık Kontrol Listesi Sekmesi

Görevin: Dashboard'daki sertifika kartına tıklayınca açılan `CertificateModal`'a, referans
ekrandaki (TrackSSL tarzı) yapının SiteMonitor'e uyarlanmış hâlini kazandırmak. Referans yapı:

- **Üst künye çipleri:** `Valid From` / `Valid Until` / `Days Remaining` + `Last Check` /
  `Next Check` — yatay rozet-çip sırası.
- **Kontrol listesi tablosu:** her satırda (1) yeşil onay / kırmızı çarpı kutusu, (2) kalın
  başlık + altında 1-2 cümle eğitici açıklama, (3) "Action" sütunu ("No action necessary" ya da
  yapılacak iş), (4) "Value" sütunu renkli durum rozeti (Valid / Strong (100%) + cipher adı chip'i /
  Current (TLSv1.3) / Enabled / No mixed content).
- **Satırlar:** süresi geçmemiş · iptal edilmemiş (revoked) · bozuk değil (broken) · uygun cipher
  suite · güncel protokol · PFS açık · mixed content yok.

Bunu SiteMonitor gerçeğine UYARLA (kopyalama): aksiyon sütunu bizim eşiklerimizle konuşur
(örn. 17 gün kaldıysa "İşlem gerekmez" DEĞİL "Yenileme planla — uyarı eşiğinin altında" + Renewal
Guide bağlantısı), UNKNOWN ayrı bir durumdur (kurumsal proxy'de OCSP/CRL erişilemeyebilir —
"Doğrulanamadı" gri rozet, FAIL değil), ve SiteMonitor'ün zaten topladığı zengin veriler
(zincir/ara sertifika/imza algoritması/anahtar boyu) ek satırlar olarak listeye girer.

Yüzeysel iş KABUL EDİLMEZ: her faz kanıtla raporlanır; ürün kararı gerektiren boşluklar (K1–K8)
tahmin edilmez, seçeneklerle kullanıcıya sorulur.

Kapsam argümanı: `$ARGUMENTS`
- Boş → tüm fazlar (0–7). `tasarim` → Faz 0–1 (karar dosyası, kod yok). `backend` → 0,1,2,3,6,7.
- `frontend` → 0,4,5,6,7 (API hazır varsayılır; değilse raporla). `hizli` → 0–5 (testler asgari).

## Değişmez kurallar (her fazda geçerli)

1. **Tek değerlendirme çekirdeği:** satır durumlarını hesaplayan mantık TEK yerde yaşar
   (`CertificateHealthService.evaluate(...)`) — frontend'te if-else'le ikinci bir sağlık mantığı
   YAZILMAZ; UI yalnız sunar. Böylece aynı çekirdek ileride haftalık rapora/maile de servis verir.
2. **UNKNOWN ≠ FAIL:** OCSP/CRL erişilemedi, sayfa çekilemedi, başlık okunamadı → gri
   "Doğrulanamadı" + nedeni; kırmızıya boyamak yanlış alarm üretir (kurumsal proxy gerçeği —
   CLAUDE.md'deki RDAP/PKIX dersleri). Her satır UNKNOWN'ı zarifçe taşıyabilmeli.
3. **Şema kuralı (CLAUDE.md):** yeni kolonlar `ddl-auto=update` ile doğar AMA
   `SchedulerService.applySchemaPatches()`'e idempotent `patch()` satırları da eklenir.
4. **i18n:** tüm satır başlıkları/açıklamaları/aksiyon metinleri TR **ve** EN aynı değişiklikte
   (`i18n-parity.test.jsx`). `.properties` değerlerinde ham Türkçe karakter yok (`\uXXXX`).
5. **Yetki:** modalın mevcut yetki zarfı korunur; sağlık endpoint'i envanter domain'inin takımına
   `SessionScope.canView` uygular. Yeni `resource_key` GEREKMEZ (gerekirse kural: PermissionCatalog'a
   aynı değişiklikte). IDOR → 403/404 + testle pinlenir.
6. **Tasarım dili:** mevcut `App.css` değişkenleri + `ssl-*` sınıf ailesi genişletilerek kurulur
   (SslCheckerPanel'in `ssl-check-row`/`ssl-badge` desenleri temel); ikon = yalnız `lucide-react`
   (yeşil onay `Check`, çarpı `X`, gri `HelpCircle`, uyarı `TriangleAlert`); dark theme
   (`[data-theme="dark"]`) elle doğrulanır. Desen adları: namethatui (badge-chip-pill, description-list,
   empty-state). Rozet renkleri mevcut durum paleti (ok/warn/crit/neutral) — yeni renk icat etme.
7. **Canlı handshake disiplinli:** her modal açılışında zorunlu canlı TLS el sıkışması YAPILMAZ
   (K2); canlı kontrol yalnız kullanıcı isteğiyle ("Şimdi kontrol et") koşar ve SSRF/proxy/timeout
   kuralları mevcut checker yolundan gelir — yeni ağ yolu açılmaz.
8. Ortam Windows (`JAVA_HOME=C:\Program Files\Zulu\zulu-25`, Maven
   `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`, Node 24). Smoke öncesi
   `mvn package -DskipTests` + `npm run build` (start-local.ps1 paketlenmiş jar koşturur).
9. Hiçbir şey commit'lenmez; coverage floor yalnız yukarı; `TESTING.md` etkilenirse güncellenir.
   Davranış değiştiren her karar (K1–K8) uygulanmadan ÖNCE kullanıcıya sorulur.

## Keşifte doğrulanmış altyapı gerçekleri (2026-08-22 — yeniden keşfetme, DOĞRULA ve kullan)

Satır numaraları o günkü çalışma kopyasına aittir; kod kaymışsa deseni adıyla ara.

- **Modal ve sekmeleri:** `CertificateModal.jsx` — `modal-tabs` çubuğu: `ssl` / `details` /
  `history` / `alerts` / `chart` / `inventory` / `notes` (424–476). `ssl` sekmesi
  `SslCheckerPanel data={sslData}` gösterir ve `sslData` HER modal açılışında
  `api.checkDomainPreview(domain)` ile CANLI kontrol koşularak gelir (386–392) — yavaş açılışın
  nedeni bu (K2'nin konusu).
- **`SslCheckerPanel.jsx` zaten yarım bir checker:** `CheckRow` (ok/fail; `ssl-check-row`,
  `ssl-badge`, `ssl-ok/ssl-fail` sınıfları), `CertBlock` zincir blokları (CN/Org/Valid/Serial/Sig/
  Issuer), DNS çözümleme satırı, `ssl-error-state` boş/hata durumu. Referans ekrana en yakın mevcut
  yüzey — üzerine inşa edilecek (K1).
- **Canlı kontrol verisi protokol+cipher İÇERİR ama KALICI DEĞİL:**
  `CertificateCheckerService.check(...)` sonucu `result.put("tls_version", …)` (357) ve
  `result.put("cipher_suite", socket.getSession().getCipherSuite())` (359) koyar;
  `CertificateCheck`/`LatestCheck` entity'lerinde bu iki kolon YOK → sweep verisinden protokol/
  cipher/PFS satırı üretilemiyor (BOŞLUK 1). `certService.saveResult(result)` haritadan entity'ye
  eşliyor (CertificateService:94 civarı `new CertificateCheck()`) — kalıcılaştırma noktası orası.
- **`LatestCheck` sağlık için zengin:** notBefore/notAfter/daysRemaining, `chainStatus`
  (VALID/BROKEN/UNKNOWN), `revocationStatus` (VALID/REVOKED/UNKNOWN — ChainValidationService:
  OCSP birincil 226–229, CRL fallback 249–251), `trustStatus`, `deploymentStatus`,
  `intermediateExpiry` + `intermediateDaysRemaining`, `signatureAlgorithm`, `publicKeyAlgorithm` +
  `publicKeySize`, `keyUsage`/`extKeyUsage`, `san`, `serialNumber`, `ocspUrl`/`crlUrl`, `via`,
  `tlsModeUsed`, `checkedAt`. `chainDetails` de var (TEXT).
- **`check-preview/{domain}` PORT SABİT 443** (CertificateController:137) — manuel check envanter
  portunu kullanırken preview 8443 gibi domainlerde YANLIŞ hedefi okur. Bu geliştirmede düzeltilecek
  bilinen tutarsızlık (aynı dosyada 120–122'deki yorum manuel check için dersi anlatıyor).
- **TLS_MODE=browser tuzağı:** istemci el sıkışması TLS 1.2 + ALPN'e SABİTLENEBİLİR (WAF'lar için;
  CLAUDE.md) → anlaşılan protokol sunucunun maksimumu OLMAYABİLİR. `LatestCheck.tlsModeUsed` hangi
  modun kullanıldığını zaten taşıyor → protokol satırında not olarak kullanılır (K6).
- **Mixed content mantığı HAZIR:** `PageCheckerService.isMixedContent(pageHttps, resourceType, url)`
  (358–376; hyperlink hariç — false-positive önleme yorumu) + Jsoup kaynak çıkarımı. Sertifika
  domain'i için hafif kullanım K3'ün konusu. SSRF: `SsrfGuard` mevcut.
- **HSTS tanılaması HAZIR ama admin kapısında:** `HstsDiagnosticsService.diagnose(domain, port)`
  başlığı okuyup max-age/includeSubDomains/preload ayrıştırıyor; `POST /api/admin/diagnostics/hsts`
  (AdminController:471) admin-only. Sağlık satırı için çekirdek yeniden KULLANILIR, kapı delinmez (K4).
- **Zayıf algoritma raporu ayrı ekran olarak var:** `WeakAlgorithmReport.jsx` →
  `GET /admin/audit/weak-algorithms` (client.js:656) — sağlık satırlarıyla ÇELİŞMEMELİ; sınıflandırma
  eşikleri tek yardımcıda birleştirilir (Faz 2).
- **"Next Check" kaynağı yok:** kodda `CronExpression` kullanımı yok; sertifika süpürmesi saatlik
  cron (`0 0 * * * *`, ayarlardan). `GET /scheduler/status` var (schedulerService.getStatus()) —
  next-run buraya eklenebilir (Spring `CronExpression.parse(...).next(now)`).
- **Takım kapsamı:** envanter `teamId` + `SessionScope.canView/viewTeamIds`; modal zaten takım-süzgeçli
  akışlardan açılıyor. Durum rozetleri/i18n: `i18n/index.jsx` tek dosya TR+EN; `formatDate`/
  `formatDateSec` api/client'tan; tarih aritmetiği `setUTC*` (3 saat kayma tuzağı).

## Faz 0 — Keşif doğrulaması + karar noktaları

Gerçekleri koddan doğrula, sonra şu kararları seçenek + önerinle sun (`tasarim` modunda `docs/`
altına karar notu yaz):

- **K1 — Yüzey:** (a) **mevcut `ssl` sekmesini bu kontrol listesine DÖNÜŞTÜR (ÖNERİLEN):** iki
  benzer sekme kafa karıştırır; SslCheckerPanel'in DNS satırı + `CertBlock` zincir blokları yeni
  ekranın alt bölümü ("Zincir" akordeonu) olarak yaşamaya devam eder, sekme adı "SSL Sağlık" olur.
  (b) `ssl` aynen kalsın, yeni "Sağlık" sekmesi eklensin (8. sekme — kalabalık).
- **K2 — Veri kaynağı:** (a) **kalıcı son kontrol (`LatestCheck`) ANINDA gösterilir (ÖNERİLEN):**
  modal açılışı ağ beklemez; başlıkta "Son kontrol: X" + "Şimdi kontrol et" düğmesi canlı
  `check-preview`'ı koşturup satırları tazeler (mevcut canlı yol korunur). (b) mevcut davranış
  (her açılışta canlı) sürsün — yavaş ve sunucuya el-sıkışma yükü.
- **K3 — Mixed content satırı:** (a) **on-demand + önbellek (ÖNERİLEN):** satırda "Kontrol et"
  düğmesi; sonuç `latest_check`'e (veya ayrı kolona) yazılır, sonraki açılışlarda tarihiyle
  gösterilir; Jsoup + `isMixedContent` çekirdeği + SsrfGuard + timeout. (b) saatlik sweep'e ekle
  (tüm envantere HTML fetch — maliyet/yan etki, önerilmez). (c) satır yalnız PageMonitor'ü olan
  domain'lerde PageMonitor sonucuna bağlanır, olmayanda "Sayfa izlemesi ekleyin" önerisi gösterir.
  (a)+(c) birleşimi de teklif edilebilir: PageMonitor verisi varsa onu kullan, yoksa on-demand.
- **K4 — HSTS satırı:** (a) **hafif başlık denetimi sağlık kontrolüne dahil, `HstsDiagnosticsService`
  çekirdeği paylaşılır (ÖNERİLEN);** sıklık K3'teki kararla aynı desen (on-demand + cache).
  (b) kapsam dışı — yalnız admin tanılamasında kalsın.
- **K5 — Cipher gücü gösterimi:** (a) **kademe: Güçlü / Kabul edilebilir / Zayıf (ÖNERİLEN)** —
  AEAD (GCM/CHACHA20) = güçlü; CBC = kabul edilebilir-uyarı; 3DES/RC4/EXPORT/NULL/anon = zayıf;
  referanstaki "% skoru" uydurma bir metrik, kademe + cipher adı chip'i daha dürüst. (b) ekrandaki
  gibi yüzde skoru (basit puan tablosuyla) — istenirse eklenir ama kaynağı belgelenir.
- **K6 — Protokol satırı ve TLS_MODE:** anlaşılan sürüm gösterilir (TLSv1.3=Güncel, TLSv1.2=Kabul,
  ≤1.1=Eski/FAIL); `tlsModeUsed=browser` ise satır altına bilgi notu: "İstemci TLS1.2'ye sabitli —
  sunucu 1.3 destekliyor olabilir". (a) not yeterli (ÖNERİLEN — ek el sıkışma yok), (b) "Şimdi
  kontrol et" canlı koşumunda `default` modla İKİNCİ probe deneyip gerçek maksimumu yaz.
- **K7 — Sağlık özeti/rozeti:** satırlardan türetilen tek özet (örn. "7/9 kontrol temiz") modal
  başlığına; Dashboard kartına mini rozet bu fazda MI (öneri: modal içi özet bu fazda, kart rozeti
  sonraki faz — kart zaten yoğun).
- **K8 — Ek satır seti:** aşağıdaki SiteMonitor'e özgü satırlardan hangileri v1'e girer:
  imza algoritması (SHA-1 → FAIL) · anahtar boyu (RSA<2048 / EC<256 → FAIL) · ara sertifika süresi
  (`intermediateDaysRemaining` eşikleri) · SAN–domain eşleşmesi (`deploymentStatus`) · güven durumu
  (`trustStatus`) · HSTS (K4) · mixed content (K3). Öneri: HEPSİ — veri zaten var/hazır; liste
  uzuyorsa bölüm başlıklarıyla gruplanır ("Sertifika", "Protokol & Şifreleme", "Uygulama Katmanı").

## Faz 1 — Veri modeli ve şema

- `certificate_checks` + `latest_check` tablolarına kolonlar: `tls_version` (VARCHAR 20),
  `cipher_suite` (VARCHAR 100); (K3/K4 seçimine göre) `mixed_content_status` + `mixed_content_at` +
  `hsts_status` + `hsts_at` (küçük JSON/VARCHAR — kanıt detayı TEXT gerekmez, sayfa URL sayısı gibi
  özet yeter). Entity'ler + `applySchemaPatches()` patch satırları (kural 3).
- `CertificateService.saveResult` eşlemesine `tls_version`/`cipher_suite` eklenir — böylece HER
  sweep'ten itibaren kalıcı; eski satırlarda null → UI "Doğrulanamadı (eski kayıt)" gösterir,
  backfill GEREKMEZ (bir sonraki saatlik sweep doldurur — bu gerçeği rapora yaz).

## Faz 2 — Backend: sağlık değerlendirme çekirdeği

- **`CertificateHealthService.evaluate(LatestCheck lc, CertificateInventory inv)`** → sıralı satır
  listesi: `{key, status: OK|WARN|FAIL|UNKNOWN|NA, valueLabelKey, valueArgs, actionKey, actionArgs,
  evidence{...}}`. Metinler i18n ANAHTARI olarak döner (backend cümle kurmaz — TR/EN frontend'te).
  Satır anahtarları (K8 sonucuna göre): `expiry`, `revocation`, `chain`, `trust`, `cipher`,
  `protocol`, `pfs`, `signature`, `keySize`, `intermediate`, `sanMatch`, `hsts`, `mixedContent`.
- Sınıflandırma yardımcıları TEK yerde, tablo-güdümlü ve birim-testli:
  - `protocolTier(tlsVersion)` — 1.3 OK · 1.2 OK(not) · 1.1/1.0/SSLv3 FAIL · null UNKNOWN.
  - `cipherTier(cipherSuite)` — K5 kademesi; PFS türetimi: TLS1.3 → daima PFS; TLS1.2 → ad
    `ECDHE_`/`DHE_` içeriyorsa PFS. Bilinmeyen ad → UNKNOWN (FAIL değil).
  - `signatureTier` / `keySizeTier` — SHA-1/MD5 FAIL; RSA<2048, EC<256 FAIL. **`WeakAlgorithmReport`
    eşikleriyle AYNI kaynak:** rapor tarafı da bu yardımcıya bakacak şekilde birleştirilir
    (iki ekran farklı hüküm veremez).
  - `expiry`: `daysRemaining` envanter/eşik ayarlarındaki warn/crit değerleriyle konuşur —
    aksiyon metni "İşlem gerekmez" / "Yenileme planla (N gün)" / "ACİL: süresi doldu".
- `revocation`: VALID→OK, REVOKED→FAIL, UNKNOWN→UNKNOWN + kanıt (ocspUrl/crlUrl yokluğu, proxy notu).
  `chain`: BROKEN→FAIL (chainDetails kanıt); `trust`/`deploymentStatus`/`sanMatch` kendi satırları.
- (K3/K4) `checkMixedContent(domain)` / `checkHsts(domain, port)` — mevcut çekirdekler
  (`PageCheckerService.isMixedContent`, `HstsDiagnosticsService`'ten çıkarılan ortak yardımcı)
  yeniden kullanılır; SsrfGuard + timeout + sonuç kalıcılaştırma; ASLA modal açılışında otomatik koşmaz.

## Faz 3 — Backend: API

- `GET /api/certificates/{domain}/health` — envanterden domain bulunur, takımına
  `SessionScope.canView` (yoksa 404/403 + `recordSecurityEvent`); yanıt: üst künye (notBefore/
  notAfter/daysRemaining/checkedAt/**nextCheckAt**) + satır listesi + `tlsModeUsed` notu.
  `nextCheckAt`: `schedulerService.getStatus()`'a Spring `CronExpression.parse(cron).next(now)`
  ile eklenir (tek kaynak; System Health de kullanabilir).
- `POST /api/certificates/{domain}/health/refresh` — "Şimdi kontrol et": mevcut manuel-check yolunu
  (envanter PORTUYLA) çağırır + (K3/K4 açıksa) on-demand satır kontrolleri; rate-limit (mevcut
  manuel tetik cooldown deseni: `pageManualTriggerAt` ConcurrentHashMap örneği). Audit:
  `auditService.recordAction("CERT_HEALTH_REFRESH", session, "CERTIFICATE", domain, null, null)`.
- **`check-preview` port düzeltmesi:** envanter portu kullanılır (443 sabiti kalkar) — ayrı,
  görünür bir düzeltme olarak raporlanır.
- Sözleşme testi: health yanıtındaki satır `key` seti `CertificateHealthService`'teki kanonik
  listeyle reflektif eşitlenir (satır ekleyip endpoint'te unutma build'de yakalansın).

## Faz 4 — Frontend: kontrol listesi paneli

- **`components/CertHealthPanel.jsx`** (SslCheckerPanel'in evrimi — K1a'da onun yerine geçer):
  - **Üst künye:** çip sırası — `Geçerlilik: 11 Ağu 2025 → 8 Eyl 2026` · `Kalan: 17 gün`
    (durum rengiyle) · `Son kontrol` · `Sonraki kontrol` (+"Şimdi kontrol et" düğmesi, koşarken
    Progress/Spinner). Çipler mevcut rozet/pill sınıflarıyla.
  - **Kontrol tablosu:** her satır 4 bölge (grid): durum kutusu (yeşil dolgulu `Check` /
    kırmızı `X` / gri `HelpCircle` / amber `TriangleAlert` — referans ekrandaki büyük onay kutusu
    hissi, `ssl-badge` büyütülmüş varyantı) · başlık + eğitici açıklama (i18n; referanstaki
    açıklama üslubu TR+EN yeniden yazılır, çeviri kokmaz) · Aksiyon sütunu ("İşlem gerekmez" soluk;
    gerçek aksiyon vurgulu + gerekiyorsa bağlantı: yenileme → Renewal Guide sekmesi, mixed content →
    Sayfa İzleme) · Değer rozeti (+ cipher adı gibi ikincil mono chip; kopyalanabilir —
    `CopyButton`).
  - Satır genişlemesi (accordion): kanıt detayı — OCSP/CRL URL'leri, SAN listesi, chainDetails,
    HSTS ham başlığı, mixed content örnek URL'ler; `tlsModeUsed=browser` bilgi notu (K6).
  - Zebra şeritli, hover'lı; UNKNOWN satırlarında aksiyon "Doğrulanamadı — nedeni" üslubuyla.
    Dar ekranda sütunlar alt alta akar (grid'in `minmax` kırılımı) — yatay taşma YASAK.
  - **Alt bölüm:** DNS satırı + `CertBlock` zincir blokları (mevcut SslCheckerPanel'den taşınır,
    "Sertifika Zinciri" başlığı altında).
  - (K7) başlık özeti: "8/9 kontrol temiz" mini rozeti.
- **Savunmacılık:** eksik/null alanlı satır paneli ÇÖKERTMEZ (ResponseTimeChart kuralı); `sslData`
  hata durumu mevcut `ssl-error-state` ile korunur. K2a'da panel `LatestCheck` verisiyle anında
  çizilir; canlı tazeleme sonucu gelince satırlar animasyonsuz güncellenir.
- i18n: `hlth.*` anahtar ailesi (satır başına `title/desc/val*/act*`) TR+EN; parity testi yeşil.

## Faz 5 — Entegrasyon

- `CertificateModal` sekme değişikliği (K1 kararına göre) + sekme adı/i18n; modal açılış akışında
  canlı preview çağrısının kaldırılması/ertelenmesi (K2a: `checkDomainPreview` yalnız düğmeyle).
- Uptime sayfasındaki SSL detay modalı da (varsa aynı `CertificateModal`'ı kullanıyor — doğrula)
  otomatik kazanır; farklı bir yüzey varsa raporla, kapsamına K kararıyla girilir.
- `details` sekmesiyle bilgi TEKRARI gözden geçirilir: sağlık sekmesi hüküm+aksiyon verir, details
  ham veri kalır — çakışan alanlar details'ten ÇIKARILMAZ (kırılganlık yaratma), sadece raporda not et.

## Faz 6 — Testler

- **Backend:** `CertificateHealthServiceTest` — tablo-güdümlü: cipher kademeleri (GCM/CHACHA/CBC/
  3DES/RC4/bilinmeyen), protokol kademeleri, PFS türetimi (TLS1.3 daima; ECDHE adı; bilinmeyen),
  imza/anahtar eşikleri, expiry'nin warn/crit eşikleriyle konuşması, UNKNOWN yolları (null cipher,
  UNKNOWN revocation), REVOKED→FAIL. Endpoint testi: takım-scope IDOR (yabancı takım 403/404),
  satır-anahtar sözleşme testi, refresh rate-limit. `saveResult` eşleme testi (tls_version/cipher
  kalıcı). Patch satırları kaynak-tarama kontrolü.
- **Frontend:** `CertHealthPanel.test.jsx` — OK/FAIL/UNKNOWN/WARN rozetleri, aksiyon metinleri,
  cipher chip + kopyalama, künye çipleri, bozuk satır düşürme, boş/hata durumu; modal sekme
  entegrasyonu (mock api — gerçek fetch kaçmaz); `i18n-parity` yeşil.
- WeakAlgorithmReport ile hüküm tutarlılığı: aynı girdiye iki yüzey farklı sonuç veremez (ortak
  yardımcıya bağlandığını pinleyen test).

## Faz 7 — Doğrulama, smoke ve rapor

1. `mvn -B clean verify` → `npm run test` → `npm run build` → `mvn package -DskipTests` →
   `start-local.ps1` → `/health` UP.
2. Smoke: Dashboard'dan bir kart aç → sağlık sekmesi ANINDA çizildi mi (K2a); "Şimdi kontrol et" →
   satırlar tazelendi + audit kaydı; 8443 portlu bir envanter domain'inde preview'ın doğru porta
   gittiği; UNKNOWN revocation'lı bir domain'de gri rozet; dark theme; TR/EN geçişi.
3. Rapor: dosya listesi, test çıktıları, K kararlarının seçimleri, `check-preview` port düzeltmesinin
   etkisi, bilinen sınırlar (eski kayıtlarda tls/cipher null — ilk sweep sonrası dolar).

## Zenginleştirme önerileri (kullanıcıya sun — şimdi mi sonra mı)

- **E1 — Sağlık geçmişi:** satır durumlarının per-check JSON özeti saklanıp "PFS ne zaman kapandı,
  cipher ne zaman zayıfladı" zaman çizelgesi (İzleme Geçmişi geliştirmesiyle akraba — `/izleme-gecmisi`
  komutundaki timeline diliyle).
- **E2 — Bozulma alarmı:** sağlık satırı OK→FAIL geçişinde (örn. REVOKED, zayıf cipher'a düşüş,
  protokol gerilemesi) mevcut eskalasyon hattından takım uyarısı — bugün yalnız süre/erişim alarmı var.
- **E3 — Filo sağlık raporu:** WeakAlgorithmReport'un genelleştirilmişi — tüm envanterin satır-bazlı
  sağlık matrisi + CSV/PDF (pdfBrand.js markalı), haftalık rapora "sağlık özeti" bölümü.
- **E4 — Dashboard kart rozeti:** K7'nin devamı — kartta "9/9" mini sağlık göstergesi + filtre.
- **E5 — CAA kaydı satırı:** `DnsCheckerService` altyapısıyla domain'in CAA kaydı var mı / veren
  CA ile uyumlu mu (yanlış CA'dan sertifika basımına karşı) — düşük maliyetli, ayırt edici satır.
- **E6 — Sertifika Şeffaflığı (CT):** SCT varlığı denetimi (sertifika uzantısından — ağ gerektirmez).
- **E7 — "Değişen ne?" chip'i:** son iki kontrol arasında değer değiştiren satıra küçük "değişti"
  rozeti (fingerprint/serial değişimi = yenilenmiş sertifika tespiti dahil).
- **E8 — Public status sayfası kırpımı:** takım dışına, salt-okunur mini sağlık kartı
  (PublicStatsController deseni) — istenirse.

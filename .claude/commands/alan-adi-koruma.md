---
description: Alan Adı izlemesine StatusCake Domain formundaki 4 korumanın boşluk-analizli tamamlanması — Kara Liste (DNSBL) izleme YENİ; Transfer Kilidi tespiti STATUS alarmına gömülü olandan görünür/ayrı hâle; Domain Expiration ZATEN tam (dokunulmaz); Rule Triggers karşılığı DOMAINMON_CHANGED'in kapsam doğrulaması + görünürlüğü. Form "Alarm Ayarları" toggle'ları, kayıt sekmesi rozetleri, dürüst UNKNOWN semantiği, kurumsal DNS gerçekleri ve eksiksiz testler.
argument-hint: [tasarim|backend|frontend|hizli] (opsiyonel — boş bırakılırsa TAM kapsam koşulur)
---

# /alan-adi-koruma — Alan Adı İzlemesinde Koruma Özellikleri

Görevin: StatusCake Domain Monitoring formundaki 4 özelliğin (Blacklist Monitoring · Transfer
Lock · Domain Expiration · Rule Triggers) SiteMonitor Alan Adı izlemesindeki karşılıklarını
BOŞLUK ANALİZİNE dayanarak tamamlamak. Keşif ön-analizi (Faz 0'da yeniden kanıtlanacak):

| StatusCake özelliği | SiteMonitor'de durum | Yapılacak |
|---|---|---|
| **Domain Expiration** (30/14/7/1 sabit) | **TAM VAR ve daha zengin:** `thresholdsCsv` (vars. 60,30,14,7,3,1) + warn/crit eşikleri + kartla hizalı severity + yenilenince otomatik kapanış | Kod DEĞİŞMEZ — yalnız karşılaştırma raporda belgelenir |
| **Transfer Lock** (.com/.org/.net) | **VAR ama GÖMÜLÜ:** `no_transfer_lock` tespiti `epp_warn`'a OR'lanıyor (DomainCheckerService:153) → DOMAINMON_STATUS alarmı; mail önerisi hazır (EscalationService:1102). Ayrı anahtar/tip/rozet YOK | K1: ayrı görünürlük (+opsiyonel ayrı alarm tipi) |
| **Rule Triggers** (kayıt kuralı tetiklenince) | **VAR:** `changed` + `changeDetail` karşılaştırması (DomainCheckerService:118–129) → DOMAINMON_CHANGED, HIGH, yapışkan (manuel ack'e kadar açık — hijack sinyali) | K4: kapsam doğrulama + görünürlük; gerekiyorsa alan-bazlı genişletme |
| **Blacklist Monitoring** (e-posta kara listeleri) | **YOK** — projede DNSBL izleme hiç yok (grep kanıtı: yalnız k6 `--blacklist-ip` SSRF, alakasız) | ANA YENİ GELİŞTİRME: DNSBL denetimi + alarm |

Yüzeysel iş KABUL EDİLMEZ: her faz kanıtla raporlanır; karar noktaları (K1–K6) tahmin edilmez,
seçenek + öneriyle kullanıcıya sorulur.

Kapsam argümanı: `$ARGUMENTS`
- Boş → tüm fazlar (0–6). `tasarim` → Faz 0 (karar notu, kod yok). `backend` → 0,1,2,3,5,6.
- `frontend` → 0,4,5,6. `hizli` → 0–4 (testler asgari).

## Değişmez kurallar (her fazda geçerli)

1. **Mevcut alarm aileleri BOZULMAZ:** DOMAINMON_EXPIRY/UNKNOWN/STATUS/CHANGED'ın bugünkü
   davranışı (sweep koşulları SchedulerService ~3999–4019, manuel değerlendirme ~4078–4088,
   CHANGED'ın yapışkanlığı, kartla hizalı severity) aynen korunur; mevcut testler değişmeden
   yeşil. Yeni davranışlar YENİ tip/anahtar olarak eklenir ya da (K1b) mevcut tipin İÇİNDE kalır.
2. **UNKNOWN ≠ FAIL (dürüstlük):** WHOIS statü listesi gelmeyen TLD'de (örn. .tr'nin sınırlı
   çıktısı) transfer kilidi "Doğrulanamadı"dır, "Kapalı" DEĞİL; DNSBL sorgusu timeout/SERVFAIL
   dönerse "Doğrulanamadı"dır, "Temiz" DEĞİL. Spamhaus'un engelli-sorgu yanıt kodları
   (127.255.255.252/254/255 — açık/public resolver reddi) ASLA "listede" sayılmaz; yalnız
   127.0.0.x cevapları listelenme demektir, NXDOMAIN temizdir. Bu kurallar birim testiyle pinlenir.
3. **Nazik dış sorgu:** DNSBL sorguları hafiftir ama sınırlıdır — IP başına liste sayısı ve
   monitör başına toplam sorgu tavanı; günlük domain sweep kadansına bağlanır (K3); WHOIS/RDAP
   tarafına YENİ yük eklenmez (mevcut önbellek/nazik-kullanım politikaları aynen).
4. **Şema kuralı:** yeni kolonlar `ddl-auto=update` + `applySchemaPatches()` idempotent patch;
   yeni monitör alanları `MON_FIELDS`'e girer (audit diff + izleme-geçmişi otomatik);
   `notificationGroupId`/bildirim-grupları zinciri değişmeden çalışır.
5. **Alarm tutarlılığı:** yeni tipler `EscalationService` sabitleri + `isStandaloneMon` setine;
   domain ailesinin `handleDomainSweep`/`domainItem` desenine eklenir; üç bildirim yolu (ilk/
   çözüm/yeniden-gönder) aynı içerik; günlük dedupe korunur; mail metinleri HTML+düz-metin
   PARİTELİ (EmailTemplateBuilder Row deseni).
6. **i18n TR+EN aynı değişiklikte; tasarım dili:** form toggle'ları mevcut form deseniyle
   (StatusCake'teki üç anahtarın karşılığı "Alarm Ayarları" alt bölümü), rozetler badge-chip-pill,
   ikon yalnız `lucide-react` (`ShieldCheck`/`ShieldAlert`/`ListX`); dark theme elle doğrulanır.
   Ortam Windows (Zulu 25 + Maven `D:\portablePrograms\...`); smoke öncesi `mvn package
   -DskipTests` + `npm run build`; hiçbir şey commit'lenmez; coverage floor yalnız yukarı.

## Keşifte doğrulanmış altyapı gerçekleri (2026-08-26 — yeniden keşfetme, DOĞRULA ve kullan)

- **Veri ZATEN zengin:** `DomainCheck` her kontrolde persist ediyor: `statusCodes` (EPP, TEXT),
  `nameservers`, `resolvedIps` (TEXT — DNSBL'in IP kaynağı HAZIR), `hostnames`, `dnssec`,
  `registrar` + `registrarIanaId`, `nsResolves`, expiry/registration/lastChanged, `source`/
  `whoisProvider`. `DomainMonitor`: thresholdsCsv/warningDays/criticalDays/intervalSeconds
  (86400) + createdBy ailesi + notificationGroupId (izleme-geçmişi ve bildirim-grupları uygulanmış).
- **EPP değerlendirmesi:** `EPP_CRITICAL = {redemptionperiod, pendingdelete, serverhold,
  clienthold}` (DomainCheckerService:47), `EPP_WARN = {autorenewperiod, pendingrenew}` (49);
  94–95 anyMatch; **153: `epp_warn = eppWarn || noTransferLock`** — transfer kilidi yokluğu bugün
  STATUS alarmına warn olarak karışıyor; `no_transfer_lock` çıktısı ayrıca üretiliyor (213'te
  hata yolunda false'lanıyor). `noTransferLock`'un TAM tanımını Faz 0'da çıkar (statü listesi BOŞKEN
  ne oluyor — UNKNOWN mu false mu; kural 2 bunun cevabına göre uygulanır).
- **Değişim tespiti:** 118–129 önceki kontrole karşı alan karşılaştırması → `changed` +
  `changeDetail` (hangi alanların kıyaslandığını Faz 0'da listele — beklenen: registrar/NS/
  DNSSEC/statü) → sweep'te yalnız değişimde HIGH, up gönderilmez (yapışkan; 4018 yorumu).
- **Sweep düzeni:** `handleDomainSweep(TYPE, list)` × 4 aile (3985–3988 günlük + 4033–4036 ikinci
  yol) + `domainItem(...)` koşul kurucusu + manuel tek-domain değerlendirme (4078–4088; DİKKAT:
  `default -> true` — yeni tip eklerken bu switch'e de dal ekle). Kadans: domain sweep GÜNLÜK.
- **Mail:** transfer kilidi önerisi metni hazır (EscalationService:1102 —
  "transfer kilidi (clientTransferProhibited) yoksa etkinleştirin"); domain mail içerik üreticisi
  EmailTemplateBuilder domain dalları (registrar/expiry satırları ~641–642 civarı desen).
- **Kayıt sekmesi:** `DomainRegistrationTab.jsx` + `domain.registration.view` izni +
  `GET /domain/{id}/registration` (MonitoringController:3418) — rozetlerin doğal evi.
- **DNS altyapısı:** `DnsCheckerService` DNS sorgu yeteneği mevcut (kütüphane/çözümleyici
  yapılandırmasını Faz 0'da çıkar — DNSBL sorguları aynı altyapıyı/`Resolver` yapılandırmasını
  yeniden kullanır, İKİNCİ bir DNS istemcisi yazılmaz). Kurumsal ağda dış DNS çözümü kısıtlı
  olabilir — kural 2'nin UNKNOWN yolu bunun için.
- Form/sayfa: `DomainMonitorPage.jsx` (form modal + detay modal sekmeleri: control/alerts/chart/
  notes + registration); defaults endpoint'inde `domain` girişi; guide: monitorGuides.js.

## Faz 0 — Keşif doğrulaması + karar noktaları

Tablodaki dört satırı koddan yeniden kanıtla (özellikle `noTransferLock` tanımı ve `changed`
karşılaştırma alan listesi). Sonra kararları seçenek + öneriyle sun (`tasarim` modunda `docs/`
altına karar notu):

- **K1 — Transfer Kilidi görünürlüğü:** (a) **ayrı alarm tipi `TYPE_DOMAINMON_TRANSFER_LOCK` +
  monitör başına opt-in toggle (ÖNERİLEN):** StatusCake eşleniği; STATUS alarmından ayrışır
  (bugün "autoRenewPeriod" ile "kilit yok" aynı alarma karışıyor — operasyonel olarak farklı
  işler); kilit geri açılınca çözüm bildirimi (normal up/down, yapışkan değil); `epp_warn`
  formülünden `noTransferLock` çıkarılır AMA toggle kapalıyken davranışın bugünle birebir
  kalması için geçiş planı yazılır (kural 1 ile çelişmeyecek biçimde — mevcut kurulumda toggle
  varsayılanı K1.1 sorusu: açık mı kapalı mı; öneri: AÇIK = bugünkü fiilî davranışın devamı).
  (b) gömülü kalsın, yalnız UI rozeti + mail satırı netleşsin (kod riski en düşük).
  TLD gerçeği her iki seçenekte: statü listesi yoksa "Doğrulanamadı" rozeti, alarm YOK.
- **K2 — Kara liste kapsamı:** hangi DNSBL'ler? Öneri: varsayılan set **IP-tabanlı**
  `zen.spamhaus.org` + `bl.spamcop.net` ve **domain-tabanlı** `dbl.spamhaus.org`; liste
  `site.monitor.domain.dnsbl-lists` AppSettings anahtarıyla CSV (admin Genel Ayarlar'dan
  değiştirilebilir). Sorgu kaynağı: son kontrolün `resolvedIps`'i (IP başına ters-oktet sorgusu)
  + domain adı (DBL). IP başına ve monitör başına tavan (öneri: ilk 5 IP). Kurumsal resolver
  Spamhaus'u reddederse (kural 2 kodları) sonuç UNKNOWN + kartta "DNSBL sorgusu bu ağdan
  doğrulanamıyor" notu — YANLIŞ ALARM ASLA.
- **K3 — Kara liste kadansı ve yerleşimi:** (a) **günlük domain sweep'inin içinde (ÖNERİLEN):**
  WHOIS turuyla birlikte, ek zamanlayıcı yok; manuel "Şimdi Kontrol Et" de koşturur.
  (b) ayrı daha sık sweep (gereksiz — kara listeye giriş saatlik takip gerektirmez; istenirse
  sonra). Monitör başına opt-in toggle (StatusCake'teki gibi; varsayılan KAPALI — dış sorgu
  üretimi bilinçli açılsın; öneriyi onaylat).
- **K4 — Rule Triggers karşılığı:** `changed` karşılaştırmasının alan listesi çıkınca:
  (a) **mevcut kapsam yeterli — yalnız görünürlük (ÖNERİLEN):** form/detayda "Değişiklik alarmı"
  bilgi satırı + changeDetail'in kayıt sekmesinde son-değişim kartı olarak gösterimi;
  (b) kapsam genişletme (ör. DNSSEC/NS eksikse ekle) + monitör başına aç/kapa toggle'ı.
  DNS kayıt-düzeyi değişimler için DNS izlemesine (TYPE_DNS_CHANGED) çapraz öneri ipucu.
- **K5 — Yeni alarm tiplerinin sözleşmesi:** `TYPE_DOMAINMON_BLACKLIST` (+K1a ise
  `TYPE_DOMAINMON_TRANSFER_LOCK`): severity (öneri: BLACKLIST=HIGH, TRANSFER_LOCK=HIGH),
  listeden çıkınca/kilit açılınca otomatik çözüm, günlük dedupe, `isStandaloneMon` üyeliği,
  mail gövdesinde kanıt (hangi liste, hangi IP; kilitte mevcut 1102 öneri metni).
- **K6 — UI yerleşimi:** form modalında "Alarm Ayarları" alt bölümü — StatusCake'in üç
  toggle'ının karşılığı: Transfer Kilidi alarmı (K1a) · Kara Liste izleme (K2/K3) · Değişiklik
  alarmı (K4b seçilirse); Domain Expiration TOGGLE DEĞİL (bizde eşikli ve zaten var — form bunu
  eşik alanlarıyla gösteriyor, dokunma). Kayıt sekmesine rozetler: "Transfer kilidi:
  Açık/Kapalı/Doğrulanamadı", "Kara liste: Temiz/Listede (N liste)/Doğrulanamadı", "Son değişim:
  changeDetail". Kart metasına yalnız sorunlu durumda mini rozet.

## Faz 1 — Veri modeli ve şema

- `DomainMonitor`: `transferLockAlert` (K1a; Boolean, varsayılan K1.1 kararı),
  `blacklistEnabled` (Boolean, varsayılan false), (K4b ise `changeAlert` Boolean varsayılan true).
  → `MON_FIELDS` + patch'ler.
- `DomainCheck`: `blacklistStatus` (CLEAN/LISTED/UNKNOWN/SKIPPED), `blacklistDetail` (TEXT —
  liste→IP eşleşmeleri JSON/CSV), `transferLock` (TRUE/FALSE/null=bilinmiyor — türetilmiş ama
  sorgulanabilir olsun diye persist). Patch'ler + retention mevcut domain_checks kuralıyla akar.

## Faz 2 — Kontrol motoru

- **`DnsblCheckerService`** (yeni, küçük): `DnsCheckerService`'in çözümleyici yapılandırmasını
  yeniden kullanır; IP'yi ters çevirip liste zone'una A sorgusu + domain'i DBL zone'una sorgu;
  kural 2 kod-yorumlama tablosu (NXDOMAIN=temiz, 127.0.0.x=listede + TXT nedeni dene,
  127.255.255.x=engelli-sorgu→UNKNOWN, timeout/SERVFAIL=UNKNOWN); tavanlar + toplam süre bütçesi;
  sonuç tek özet obje. Tablo-güdümlü, ağsız birim-testlenebilir ayrıştırma (sorgu katmanı mock).
- `DomainCheckerService.check(...)`: (K1a) `noTransferLock` `epp_warn`'dan ayrılır, `out`'a
  `transfer_lock` üçlü değeri; (K2/K3) `blacklistEnabled` ise DNSBL özeti `out`'a + persist.
  Hata yolu (210–213) yeni alanları da güvenli varsayılanlarla doldurur.

## Faz 3 — Sweep ve alarm

- `addDomainSweepItems`'a yeni aileler: TRANSFER_LOCK (up = kilit var VEYA doğrulanamıyor),
  BLACKLIST (up = temiz VEYA doğrulanamıyor/kapalı) — `domainItem` deseniyle; iki
  `handleDomainSweep` çağrı noktasına da (3985+/4033+) ve manuel değerlendirme switch'ine
  (4083+; `default -> true` tuzağı) eklenir. EscalationService: sabitler + `isStandaloneMon` +
  mail içerikleri (kanıt satırları + 1102 öneri metni transfer-lock mailine taşınır) + webhook.
  MonitoringOutageService tip-izolasyon kaydı; ActivityLog gerekmiyor (config değil, alarm ailesi).
- Renkli detay: BLACKLIST mailinde hangi liste + hangi IP + (varsa) TXT nedeni + delist ipucu
  bağlantı şablonu (liste adına göre bilinen delist sayfaları — sabit harita, iddiasız).

## Faz 4 — Frontend

- `DomainMonitorPage` form modalı: K6 "Alarm Ayarları" alt bölümü (toggle'lar + kısa açıklamalar —
  StatusCake tooltip metinlerinin TR/EN uyarlaması `monitorGuides` + alan-altı ipuçları);
  defaults ön-doldurması gerekmiyor (boolean'lar). Detay modal kayıt sekmesi (`DomainRegistrationTab`):
  transfer kilidi + kara liste rozetleri + son-değişim kartı (`changeDetail`); AlertHistory yeni
  tipleri zaten listeler (tip etiketi i18n'e eklenir). Kart metası: yalnız LISTED/kilit-yok
  durumunda mini rozet. i18n TR+EN; dark theme.

## Faz 5 — Testler

- **Birim:** DNSBL yorumlama tablosu (kural 2'nin tüm kodları), ters-oktet kurulumu, tavanlar;
  `noTransferLock` semantiği (statü listesi boş → UNKNOWN, `*transferprohibited` varyantları →
  kilitli); K1a geçişinde STATUS alarmının eski davranışının toggle-varsayılanıyla birebir
  korunduğu (regresyon kanıtı).
- **Sweep:** yeni ailelerin up/down koşulları; manuel switch dalları; günlük dedupe; üç yol
  içerik tutarlılığı; yabancı takım IDOR (mevcut desen).
- **Frontend:** toggle'lar + payload; rozetlerin üç durumu (temiz/listede/doğrulanamadı);
  i18n-parity. Mevcut domain testleri DEĞİŞMEDEN yeşil.

## Faz 6 — Doğrulama, smoke ve rapor

1. `mvn -B clean verify` → `npm run test` → `npm run build` → paket → `start-local.ps1` → smoke:
   bilinen-temiz bir domain'de kara liste AÇ → kontrol → "Temiz"; DNSBL'e erişimi olmayan ağda →
   "Doğrulanamadı" (alarm YOK); transfer kilidi olmayan bir test domain'inde (varsa) rozet +
   (K1a) alarm + kilit gelince çözüm; registration sekmesi rozetleri; TR/EN + dark theme.
2. Rapor: 4 özelliğin son durum matrisi (VAR/EKLENDİ/kapsam), dosya listesi, test çıktıları,
   K kararları, bilinen sınırlar (kurumsal resolver kısıtı; TLD statü kapsamı; DNSBL listeleri
   yapılandırılabilir).

## Zenginleştirme önerileri (kullanıcıya sun — şimdi mi sonra mı)

- **E1 — Envanter domain'lerine kara liste:** aynı DNSBL motoru sertifika envanterindeki
  domain'lere de (uptime kartlarında rozet) — ayrı karar, motor hazır olur.
- **E2 — Haftalık rapora bölüm:** "kara listede olan / transfer kilidi olmayan domainler" özeti.
- **E3 — Registry kilidi ayrımı:** `serverTransferProhibited` (registry lock) ayrı rozet —
  kurumsal domain'lerde daha güçlü koruma sinyali.
- **E4 — DNSSEC değişim vurgusu:** `changed` ailesinde DNSSEC geçişlerine özel etiket/severity.
- **E5 — Delist takibi:** LISTED alarmı açıkken günlük yeniden kontrol sonucu mail dipnotu
  ("hâlâ listede / N listeden çıktı").
- **E6 — Kara liste geçmişi:** DomainCheck'teki blacklistStatus'tan kayıt sekmesinde mini zaman
  çizelgesi ("ne zaman girdi, ne zaman çıktı").

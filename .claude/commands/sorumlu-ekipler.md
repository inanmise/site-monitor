---
description: Sertifika envanterine "Sorumlu Ekipler" bölümü — 4 alan: Servis Yönetimi, Uygulama Geliştirme, IISAdmin Ekibi, WAFAdmin Ekibi. Envanter formunda doldurulur, sertifika modalının Envanter sekmesinde görünür, sertifika hatırlatma maillerine üretimdeki "Sorumlu Ekipler" tablosu olarak eklenir. CLAUDE.md envanter alan-ekleme kontrol listesi eksiksiz işletilir (setter tuzağı dahil), HTML+düz-metin mail paritesi korunur.
argument-hint: [tasarim|backend|frontend|hizli] (opsiyonel — boş bırakılırsa TAM kapsam koşulur)
---

# /sorumlu-ekipler — Envanterde Sorumlu Ekipler Bölümü

Görevin: sertifika envanterine, üretimdeki gerçek sertifika uyarı mailinde görülen **"Sorumlu
Ekipler"** yapısını kazandırmak. Ürün kararı netleşmiş çekirdek:

- Envanter kaydında yeni bir **"Sorumlu Ekipler"** bölümü; içinde 4 alan: **Servis Yönetimi**,
  **Uygulama Geliştirme**, **IISAdmin Ekibi**, **WAFAdmin Ekibi**.
- Yeni sertifika eklerken ve düzenlerken kullanıcı bu bölümü doldurabilir (InventoryFormModal).
- Dashboard'daki sertifika kartından açılan modalın **Envanter sekmesinde** bu bilgiler görünür.
- **Sertifika hatırlatma maillerine** referans maildeki gibi "Sorumlu Ekipler" tablosu eklenir
  (referans düzen: sütun başlıkları ekip adları, altlarında kişi/e-posta — örn. "Ad Soyad -
  ad.soyad@example.com", "ekip@example.com").

Yüzeysel iş KABUL EDİLMEZ: her faz kanıtla raporlanır; karar noktaları (K1–K6) tahmin edilmez,
seçenek + öneriyle kullanıcıya sorulur.

Kapsam argümanı: `$ARGUMENTS`
- Boş → tüm fazlar (0–5). `tasarim` → yalnız Faz 0 (karar notu, kod yok). `backend` → 0,1,2,4,5.
- `frontend` → 0,3,4,5. `hizli` → 0–3 (testler asgari).

## Değişmez kurallar (her fazda geçerli)

1. **CLAUDE.md envanter alan-ekleme kontrol listesi EKSİKSİZ işletilir** (bu listedeki "setter
   unutulur, alan kaydeder gibi görünür ama reload'da kaybolur" tuzağı bu işin bilinen 1 numaralı
   bug'ıdır): entity `@Column` → `applySchemaPatches()` idempotent patch →
   `InventoryFormModal.jsx` EMPTY varsayılanı + save-payload girişi →
   `AdminController.buildInventoryDiff` girişi → `updateInventory` SETTER çağrısı →
   `InventoryDetails.jsx` gösterimi. Dört alanın DÖRDÜ için ayrı ayrı doğrulanır.
   (Bunlar boolean DEĞİL — `INVENTORY_FLAGS` dizisine ve `inventory-flags-sync` testine
   DOKUNULMAZ; yine de test koşulup etkilenmediği kanıtlanır.)
2. **Mail paritesi:** `EmailTemplateBuilder` HTML + düz-metin PARİTELİDİR (`Row(label, html,
   text)` kaydı, ~85; `detailRows` ~636 hem HTML tabloya ~178 hem düz-metne ~293 akar). Sorumlu
   Ekipler tablosu da iki biçimde eklenir; yalnız-HTML ekleme YASAK. E-posta değerleri `esc(...)`
   ile kaçırılır; e-posta görünen değer `mailto:` bağlantısı olur (düz metinde adres aynen).
3. **Mevcut sahiplik modeline DOKUNULMAZ:** `teamId` (SY) / `ugTeamId` (UG) alarm yönlendirmesinin
   temelidir — bu geliştirme YÖNLENDİRMEYİ DEĞİŞTİRMEZ; 4 alan bilgilendirme amaçlıdır (mailde
   gösterim + envanter kaydı). Alıcı listesine ekleme ancak K3'te açıkça seçilirse ve
   /bildirim-gruplari hunisi bozulmadan yapılır.
4. **i18n:** yeni `inv.*` ve mail-dışı UI anahtarları TR+EN aynı değişiklikte
   (`i18n-parity.test.jsx`). Mail metinleri Java tarafında TR'dir (mevcut "Sorumlu Takım" satırı
   emsali) — `.properties`'e ham Türkçe YAZILMAZ (`\uXXXX`; `PropertiesEncodingTest`).
5. **Denetim + geçmiş:** dört alan `buildInventoryDiff`'e girdiği için audit diff'i ve (uygulanmış
   olduğu keşifle doğrulanan) İzleme Geçmişi / `monitorchanges` zaman çizelgesi otomatik kazanır —
   Faz 0'da bu akışın çalıştığı doğrulanır. Alanlar hassas DEĞİL (SecretMask kapsamına girmez).
6. **Tasarım dili:** form `SectionHeader` (`form-section-header`, InventoryFormModal:46) ve
   gösterim `show-section-header` + `ShowField` (InventoryDetails) desenleriyle; ikon yalnız
   `lucide-react`; dark theme elle doğrulanır. Ortam Windows (`JAVA_HOME=C:\Program Files\Zulu\
   zulu-25`, Maven `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`); smoke öncesi
   `mvn package -DskipTests` + `npm run build`. Hiçbir şey commit'lenmez; coverage floor yalnız
   yukarı; `TESTING.md` etkilenirse güncellenir.

## Keşifte doğrulanmış altyapı gerçekleri (2026-08-26 — yeniden keşfetme, DOĞRULA ve kullan)

- **`CertificateInventory` bugün:** `teamId` (SY, satır 52) + `ugTeamId` (UG, 62) + `@Transient
  teamName/ugTeamName` (118–119); `owner` (TEXT, 40) serbest sahip alanı ZATEN VAR (K2'de dört
  alanla ilişkisi netleşecek); `purchasedBy` (90), `tier`, boolean bayraklar (70–82; `wafEnabled`
  78 — K4'ün konusu), `createdBy/updatedBy/createdIp` ve `notificationGroupId` (146) mevcut →
  /izleme-gecmisi ve /bildirim-gruplari uygulanmış; bu komut onların üstüne biner.
- **Form:** `inventory/InventoryFormModal.jsx` — `SectionHeader` bileşeni (46), `inv.sectionBasic`
  bölüm düzeni (241+), zorunlu alan `req-star` deseni, kopya (`isDuplicate`) akışı, kaydet
  toast'ları. Dashboard kartının Edit/Duplicate düğmeleriyle PAYLAŞIMLI — iki giriş yolundan da test.
- **Gösterim:** `inventory/InventoryDetails.jsx` — `ShowField` + `show-section-header` bölümleri
  (Basic 39, Ops 58); `CertificateModal`'ın Envanter sekmesi `InventoryTab` (CertificateModal:11,
  672) ve `inventory.list` izniyle gizlenir (372) — yeni bölüm de aynı zarfın içindedir.
- **Mail:** `EmailTemplateBuilder` — `Row(label, html, text)` (85), `detailRows(m)` (636),
  HTML satır akışı (178) + düz-metin akışı (293); "Sorumlu Takım" satırı `addIf(out, "Sorumlu
  Takım", m.teamName())` (694); "Neden bu e-postayı aldınız?" bloğu (762). Sorumlu Ekipler
  tablosunun ekleneceği yer bu aile; cert alarm mailinin hangi yollarla kurulduğu (ilk uyarı /
  çözüm / reNotify içerik reconstruct'ı) Faz 0'da çıkarılır — üç yol da aynı içeriği göstermeli.
- **Diff/audit:** `AdminController.buildInventoryDiff` (306) + `DOMAIN_ADD` (206) / `DOMAIN_EDIT`
  (300); `updateInventory` setter bölgesi. Haftalık envanter raporu (`CertInventoryReportLog` /
  InventoryHygiene) ayrı akış — K3 kapsam sorusu.

## Faz 0 — Keşif doğrulaması + karar noktaları

Gerçekleri doğrula; cert uyarı mailini kuran çağrı zincirini (ilk uyarı/çözüm/reNotify) çıkar;
`owner` alanının bugün nerede gösterildiğini listele. Sonra kararları sun:

- **K1 — Alan biçimi:** (a) **4 × tek serbest metin alanı (ÖNERİLEN):** her alan "ad - e-posta"
  veya yalnız e-posta/yalnız ad alabilir (referans maildeki gerçek kullanım tam böyle: "Ad Soyad
  - ad.soyad@example.com" da var, "ekip@example.com" da). VARCHAR(300), boş
  bırakılabilir. UI değeri içinde e-posta geçiyorsa mailto/`CopyButton` ile zenginleşir.
  (b) her alan yapılandırılmış ad+e-posta çifti (8 kolon — form kalabalıklaşır, esneklik düşer).
- **K2 — Mevcut alanlarla ilişki:** SY/UG takım sahipliği (teamId/ugTeamId) ve `owner` alanı
  dururken bu 4 alan BAĞIMSIZ mı? (a) **bağımsız bilgilendirme alanları (ÖNERİLEN** — kural 3;
  formda Servis Yönetimi/Uygulama Geliştirme alanlarının yanına "takım sahipliğinden bağımsızdır"
  ipucu); (b) SY/UG alanları boşken mailde ilgili takımın adı/e-postası fallback gösterilsin mi —
  ayrıca sor (öneri: evet, tablo hücresi boş kalmasın; fallback hücre "takım: X" etiketiyle dürüst
  gösterilir). `owner` alanıyla çakışma raporlanır (kaldırma/birleştirme YOK — sadece not).
- **K3 — Mail kapsamı:** tablo hangi maillere girer? (a) **cert süre-uyarı ailesi: ilk uyarı +
  çözüm + yeniden-gönderim (ÖNERİLEN** — üç yol aynı tabloyu göstermeli); (b) + haftalık envanter
  raporu; (c) + zayıf algoritma/diagnostik mailleri (önerilmez — kapsam şişer). Alanlar ALICI
  listesine eklenmez (kural 3) — eklensin isteniyorsa /bildirim-gruplari zinciriyle ayrı karar.
- **K4 — Koşullu satırlar:** WAFAdmin satırı `wafEnabled=false` iken, IISAdmin satırı IIS'siz
  kayıtta nasıl davranır? Öneri: DOLUYSA GÖSTER (bayrağa bağlama — bayrak yanlış işaretli olabilir;
  boşsa satır atlanır, `addIf` deseni zaten bunu yapar). Mail tablosu 4 sütun sabit mi yalnız dolu
  sütunlar mı — sor (öneri: yalnız dolular; hiçbiri yoksa tablo hiç eklenmez).
- **K5 — Doğrulama:** alan içinde e-posta varsa biçim kontrolü (uyarı, engel değil — "Ad - email"
  serbest biçimini bozmamak için), uzunluk tavanı, XSS kaçışı (mail `esc` + React zaten).
- **K6 — Ek yüzeyler:** InventoryManager liste/CSV export'una bu alanlar girer mi; Uptime/SSL
  kartı meta'sında gösterim; arama filtresi. Öneri: v1'de yalnız form + detay + mail; export'a
  ekleme ucuz — sor.

## Faz 1 — Veri modeli ve şema

- Entity: `svcMgmtContact` (`svc_mgmt_contact`), `appDevContact` (`app_dev_contact`),
  `iisAdminContact` (`iis_admin_contact`), `wafAdminContact` (`waf_admin_contact`) —
  VARCHAR(300), nullable (K1a). `applySchemaPatches()`'e 4 idempotent `ADD COLUMN` patch'i.
- `buildInventoryDiff`'e 4 giriş; `updateInventory`'ye 4 SETTER (kural 1'in tuzağı);
  create yolunda (`DOMAIN_ADD`) 4 alanın kaydı.

## Faz 2 — Backend: mail tablosu

- `EmailTemplateBuilder`'a "Sorumlu Ekipler" bölümü: HTML'de referans maildekine benzer kompakt
  tablo (başlık hücreleri: Servis Yönetimi / Uygulama Geliştirme / IISAdmin Ekibi / WAFAdmin
  Ekibi; altlarında değerler, e-postalar `mailto:`; mevcut mail tablo stilleri — inline CSS
  düzeniyle uyumlu), düz metinde `Etiket: değer` satırları (kural 2 paritesi). K4: yalnız dolu
  sütunlar; hepsi boşsa bölüm eklenmez. K2b seçildiyse SY/UG fallback hücreleri.
- `AlertMail` record'una (82) alanların taşınması + cert mail kuran ÜÇ yolun (ilk/çözüm/reNotify)
  aynı veriyi doldurduğunun kanıtı (reNotify content-reconstruct dersi: içerik envanterden
  YENİDEN okunur, bayat kopya taşınmaz).

## Faz 3 — Frontend

- **InventoryFormModal:** `SectionHeader label={t('inv.sectionContacts')}` ("Sorumlu Ekipler")
  bölümü — 4 `Field` (placeholder: "Ad - e-posta veya e-posta"); EMPTY varsayılanları +
  save-payload girişleri; K5 yumuşak doğrulama; kopya (`Duplicate`) akışında alanların devri.
- **InventoryDetails:** `show-section-header` "Sorumlu Ekipler" + 4 `ShowField`; değerde e-posta
  varsa `mailto:` + `CopyButton`; hepsi boşsa bölüm başlığı yerine tek satır boş-durum notu
  ("Sorumlu ekip bilgisi girilmemiş").
- i18n `inv.sectionContacts` + 4 alan etiketi + placeholder + boş-durum TR+EN; dark theme.
- (K6 seçimine göre) InventoryManager/CSV.

## Faz 4 — Testler

- **Backend:** create+update round-trip testi — 4 alan kaydedilir, YENİDEN OKUNUR (setter tuzağını
  yakalar); `buildInventoryDiff` 4 alan için from/to üretir; mail testi — HTML VE düz metinde
  tablo/satırlar var, boş alan atlanıyor, hepsi boşken bölüm yok, `esc` kaçışı (değerde `<`
  denemesi), üç mail yolunda aynı içerik; patch kaynak-tarama.
- **Frontend:** form bölümü render + payload; detay gösterimi + mailto/boş-durum; duplicate devri;
  `i18n-parity` + `inventory-flags-sync` (etkilenmedi kanıtı) yeşil.
- Mevcut envanter/alarm testleri DEĞİŞMEDEN yeşil.

## Faz 5 — Doğrulama, smoke ve rapor

1. `mvn -B clean verify` → `npm run test` → `npm run build` → `mvn package -DskipTests` →
   `start-local.ps1` → `/health` UP.
2. Smoke: envantere kayıt ekle (4 alanı doldur) → kaydet → yeniden aç (alanlar YERİNDE — reload
   tuzağı); dashboard kartı → modal → Envanter sekmesinde bölüm; alanlardan birini düzenle →
   audit/izleme-geçmişi diff'inde görünür; test sertifikasında uyarı maili tetikle → mailde
   Sorumlu Ekipler tablosu (HTML + düz metin); WAFAdmin boşken sütunun düşmesi; TR/EN + dark theme.
3. Rapor: dosya listesi, test çıktıları, K kararları, `owner` alanı çakışma notu, bilinen sınırlar.

## Zenginleştirme önerileri (kullanıcıya sun — şimdi mi sonra mı)

- **E1 — Kullanıcı dizini entegrasyonu:** alanlara yazarken `UserDirectory`/LDAP'tan kişi önerisi
  (ad seçilince "Ad - email" otomatik dolar) — serbest metin yine mümkün kalır.
- **E2 — Değişim Rehberi bağlantıları:** referans maildeki "Sertifika Değişim Rehberi" bağlantı
  bloğunun karşılığı — `GuideLink`/MonitorGuide altyapısıyla yönetilebilir kurumsal bağlantı
  listesi mailin altına eklenir (Netscaler/WAF talimat linkleri gibi).
- **E3 — Alınacak Aksiyonlar tablosu:** referans mailin ikinci tablosu — mevcut boolean bayraklar
  (netscaler/wafEnabled/openshift/externalVendor/jksKeystore/serverUpdate/sslPinning) mailde
  "Platform / Aksiyon Alınacak mı?" Evet-Hayır tablosu olarak zaten üretilebilir (yeni veri
  GEREKMEZ — sadece sunum). Düşük maliyet, yüksek tanıdıklık.
- **E4 — Doluluk hijyeni:** InventoryHygiene raporuna "sorumlu ekip alanı boş kayıtlar" metriği.
- **E5 — Toplu düzenleme:** InventoryManager'dan çoklu seçim + sorumlu ekip alanlarını topluca
  atama (büyük envanterde ilk doldurma işini kolaylaştırır).

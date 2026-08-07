---
description: Kullanıcı tetiklemeli "Sorun Bildir" altyapısını uçtan uca geliştirir — ErrorBoundary'den ve genel menüden tek tıkla bildirim, bilinen bağlamın otomatik toplanması (kullanıcıya sorulmaz), e-postası olmayan kullanıcıdan e-posta isteme, Yönetim > Sorun Bildirimleri yaşam döngüsü ekranı, Sentetik İzleme çökmesinin kalıcı regresyon kilitleri.
argument-hint: [kilit|ui|backend|yonetim] (opsiyonel — boş bırakılırsa TAM kapsam)
---

# /sorun-bildir — Sorun Bildirimi Altyapısı + Çökme Regresyon Kilitleri

**Arka plan (bu komut yazılırken doğrulandı — yeniden inşa ETME, üzerine kur):**
- 2026-08-06 Sentetik İzleme çökmesinin kök nedeni bulundu ve kod düzeltildi:
  `/scripted/{id}/response-series` ham `Object[]` dönüyordu; artık `buildResponseSeries`
  hunisinden geçiyor. `ResponseTimeChart` bozuk kaydı düşüren savunma filtresi aldı.
  Ders notları: proje hafızası `chart_endpoint_dersleri.md`.
- Otomatik çökme bildirimi ZATEN VAR: `ErrorBoundary` → public `/api/client-error-report`
  (`ClientErrorController`; IP başına 5/saat, uzunluk sınırları,
  `site.monitor.client-errors.enabled` bayrağı, referans no döner); kayıt login
  sorun-bildirimleri deposuna (`LoginIssueReport`) düşer + admin maili gider.
- Eksik olan ve bu komutun konusu: **kullanıcının kendisinin başlattığı** bildirim akışı,
  bilinen bağlamın otomatik toplanması, e-posta sorma kuralı ve Yönetim tarafında tam bir
  yaşam döngüsü ekranı.

Kapsam argümanı: `$ARGUMENTS` — boş: tüm fazlar; `kilit`: 0–1, 6–7; `ui`: 0, 2, 5–7;
`backend`: 0, 3, 5–7; `yonetim`: 0, 4–7.

## Değişmez kurallar

1. **Sistem zaten bildiğini kullanıcıya SORMAZ** (kullanıcı kararı — bağlayıcı): kim (oturum),
   ne zaman (sunucu saati), nerede (URL + aktif sekme), nasıl (hata metni + component stack +
   UA/ekran/tema/dil/sürüm) otomatik toplanır. Kullanıcıya yalnız bilinemeyecekler sorulur:
   ne yapıyordu / ne bekliyordu (zorunlu serbest metin), önem algısı (opsiyonel), ekran
   görüntüsü (opsiyonel) ve **profilinde yoksa e-posta**.
2. **Gizlilik:** otomatik bağlamda şifre/token/secret ASLA yer almaz (`SecretMask` desenleri
   URL query ve hata metnine de uygulanır); IP/UA yalnız bu amaçla saklanır ve admin ekranında
   gösterilir; kullanıcı beyanı kimlik alanlarını EZEMEZ (kullanıcı adı oturumdan okunur —
   `ClientErrorController`'daki ilke korunur).
3. Kimliksiz yazma yüzeyleri (login öncesi) mevcut kötüye kullanım önlem desenini birebir taşır:
   IP rate-limit, alan uzunluk sınırları, sabit alıcı, escape'li mail içeriği.
4. Mail'ler BRAND.md §5.1 standart şablonundan çıkar (kart-içi logo lockup'ı); testlerde gerçek
   SMTP'ye bağlanılmaz. Coverage floor'ları düşürülmez; commit atılmaz.
5. Yeni kolonlar için `applySchemaPatches()`'e `patch()` satırı; yeni admin resource_key aynı
   değişiklikte `PermissionCatalog.ALL`'a; yeni i18n anahtarları TR+EN birlikte. Ortam:
   `JAVA_HOME=C:\Program Files\Zulu\zulu-25`, Maven `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`.

## Faz 0 — Ön koşul ve keşif

Baseline: `mvn -B clean verify` + `npm ci && npm run test:coverage` yeşil; `git status --short` kaydet.
Keşfet ve raporla: `ClientErrorController`, `LoginIssueController`/`LoginHelpController`,
`LoginIssueReport(+Image)` modeli, `LoginIssueMailService`, `LoginIssueReports.jsx` (Yönetim
ekranı) bugün tam olarak ne yapabiliyor — statü alanı var mı, görsel eki nasıl akıyor,
hangi endpoint'ler public. Bu envanter Faz 3–4'ün "genişlet mi, yeni mi" kararlarını belirler.

## Faz 1 — Çökmenin kalıcı regresyon kilitleri

- **Sözleşme testi:** 7 `response-series` endpoint'inin TÜMÜ için tek parametrik test —
  dönen `series[*]` elemanları `{ts:String, avg,min,max,p95,count,down}` şeklinde; ham
  `Object[]` şekli görülürse fail. (Yeni monitör türü eklendiğinde test listesine eklemeyi
  zorlayan bir "bilinen endpoint sayısı" assert'i koy — kopyala-yapıştır 8. tür aynı tuzağa
  düşmesin.)
- **Frontend savunma testi:** `ResponseTimeChart`'a `ts`'siz/bozuk kayıt beslendiğinde çökmek
  yerine o kaydı düşürdüğünü ve kalanları çizdiğini pinle.
- `CLAUDE.md` "Things that bite" bölümüne iki satır: series hunisi kuralı + savunmacı chart kuralı.

## Faz 2 — Kullanıcı tarafı: Sorun Bildir UI

- **İki giriş noktası:** (a) `ErrorBoundary` fallback'ine "Sorun Bildir" butonu — otomatik
  bildirim zaten gitti; buton, kullanıcının AYNI kayda bağlam eklemesini sağlar (`reportRef`
  ile ilişkilendir, mükerrer kayıt açma); (b) kalıcı genel giriş — kullanıcı menüsü/Yardım
  alanında "Sorun Bildir" (çökme olmayan sorunlar için: yanlış veri, yavaşlık, görsel bozukluk).
- **`IssueReportModal.jsx` (tek bileşen):**
  - Üstte "otomatik eklenecekler" özeti READONLY gösterilir (şeffaflık: kullanıcı ne
    gönderildiğini görür): kullanıcı adı + takım, zaman, URL + aktif sekme adı, uygulama
    sürümü (`VERSION`), tarayıcı/OS (UA'dan), ekran boyutu, tema + dil, varsa hata metni +
    component stack, varsa son başarısız API çağrıları.
  - Son başarısız API çağrıları için `api/client.js`'e hafif bir halka tamponu ekle (son 5
    başarısız istek: yol + durum kodu + zaman; gövde YOK — gizlilik).
  - Kullanıcıya sorulanlar (kural 1): açıklama (zorunlu), önem (opsiyonel: Engelliyor /
    Rahatsız ediyor / Öneri), ekran görüntüsü (opsiyonel; mevcut `LoginIssueReportImage` +
    `imageDownscale.js` yolu yeniden kullanılır).
  - **E-posta kuralı (kullanıcı kararı):** profil e-postası VARSA readonly gösterilir; YOKSA
    zorunlu e-posta alanı çıkar + "profilime kaydet" onay kutusu (işaretliyse `AppUser.email`
    güncellenir — audit'e SETTING/CRUD event'i düşer).
  - Gönderimde referans no gösterilir; login öncesi ekranda da çalışır (public uç, kural 3).
- Erişilebilirlik + i18n: modal klavye ile gezilebilir; tüm metinler TR+EN.

## Faz 3 — Backend: kayıt modeli ve uçlar

- `LoginIssueReport`'u genel sorun kaydına genişlet (yeni tablo AÇMA — mevcut ekran ve mail
  altyapısı üzerine kur): `source` (`LOGIN` | `CLIENT_ERROR` | `USER_REPORT`), `category`,
  `userEmail`, `appVersion`, `userAgent`, `screenSize`, `tabKey`, `autoContextJson`,
  `linkedReference` (ErrorBoundary referansına bağ), `status` (`NEW` → `IN_REVIEW` →
  `RESOLVED`, + `resolutionNote`, `resolvedBy/At`). Tüm yeni kolonlara `patch()` satırı.
- Uçlar: auth'lu `POST /api/issue-reports` (oturumdan kimlik); login-öncesi public varyant
  mevcut LoginHelp desenine bağlanır. Uzunluk sınırları + rate-limit `ClientErrorController`
  sabitleriyle uyumlu.
- Bildirimler: yeni `USER_REPORT` → Sistem Yöneticisi'ne mail (standart şablon, `warning`
  değil `ok` logosu — bu bir alarm değil); `site.monitor.issue-reports.daily-digest` ayarıyla
  günlük özet opsiyonu. `notification_logs`'a yazım mevcut desenle.

## Faz 4 — Yönetim: Sorun Bildirimleri ekranı

- `LoginIssueReports.jsx`'i **"Sorun Bildirimleri"** ekranına genelleştir: kaynak/kategori/
  statü filtreleri; **imza bazlı gruplama** (aynı hata metni imzası kaç kullanıcıda, kaç kez —
  triage için); detayda otomatik bağlamın tamamı + görsel ekleri; statü geçişleri + çözüm notu.
- **Kullanıcıya dönüş:** e-postası olan bildirimlerde "çözüldü bilgisi gönder" aksiyonu
  (standart mail şablonu, çözüm notunu içerir). E-postasız kayıtta buton devre dışı + neden
  tooltip'i.
- Yeni resource_key (`system.issue-reports` gibi) `PermissionCatalog`'a; erişim mevcut
  login-issues ekranıyla tutarlı (global admin; AUDIT'e salt-okuma değerlendir — mevcut
  `requireSystemRead` desenine bak). Tüm statü değişimleri `AuditService`'e CRUD event'i yazar.

## Faz 5 — Testler

- Modal: e-posta yokken alan zorunlu + "profilime kaydet" akışı; e-posta varken readonly;
  otomatik bağlam alanlarının kullanıcıya SORULMADIĞI (form alanı yok) — kural 1'in testi;
  ekran görüntüsü ekleme; başarılı gönderimde referans gösterimi. (`test-utils.jsx#render`,
  api `vi.mock`.)
- Backend: kimlik oturumdan-okunur kuralı (gövdedeki sahte kullanıcı adı yok sayılır);
  rate-limit; statü yaşam döngüsü geçişleri + geçersiz geçiş reddi; `patch()` idempotentliği;
  mail içeriğinde escape + maskeleme; digest ayarı kapalıyken tekil mail davranışı.
- Faz 1 kilitleri dahil `mvn -B clean verify` + `npm run test:coverage` yeşil; kapılar korunur;
  `TESTING.md` güncellenir.

## Faz 6 — Uçtan uca senaryo doğrulaması

Yerel ortamda (`start-local.ps1` + smoke yeşilken) tam zincir: Sentetik İzleme'de kasıtlı bir
çökme simüle et (test-only koşulla) → otomatik kayıt + referans → aynı ekrandan "Sorun Bildir"
ile kullanıcı bağlamı ekle (e-postasız bir test kullanıcısıyla — e-posta sorusunun geldiğini
gör) → Yönetim > Sorun Bildirimleri'nde grupla/incele → RESOLVED yap + çözüm notu → (SMTP
ayarlıysa ve kullanıcı onaylarsa) dönüş maili. Her adımın ekran çıktısı/kanıtı rapora.

## Faz 7 — Final rapor

Keşif envanteri (neyin üzerine kuruldu); şema/uç değişiklikleri; kural 1–2 uyum kanıtları
(sorulmayan alanlar listesi, maskeleme testleri); test/coverage önce-sonra; kalan öneriler
(ör. Teams/Slack webhook'una triage bildirimi, SLA sayaçları) — commit `feat:` önerisiyle
kullanıcı onayına.

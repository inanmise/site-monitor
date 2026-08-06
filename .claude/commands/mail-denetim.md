---
description: Tüm e-posta bildirimlerini uçtan uca denetler ve Outlook'ta görülen logo hatalı kullanımını (dev boyut, karttan kopuk yerleşim) kalıcı olarak düzeltir — envanter, Outlook-güvenli şablon standardı, kart-içi logo lockup'ı, içerik/subject denetimi, önizleme harness'ı ve regresyon testleri.
argument-hint: [sablon|icerik|test] (opsiyonel — boş bırakılırsa TAM kapsam)
---

# /mail-denetim — E-posta Bildirimleri Uçtan Uca Denetim ve Logo Düzeltmesi

Saha bulgusu (Outlook masaüstü ekran görüntüsü, RE-ALERT maili): logo **dev boyutta** (~320px+),
kartın DIŞINDA sol üstte serbest yüzüyor; logo ile içerik kartı arasında koca boş gri alan var;
kart başlığında "SiteMonitor" yazısı ayrı duruyor. Ayrıca gönderen adı hâlâ **"CertMonitor
Alerts"** (rename kalıntısı) ve subject dağınık: `[RE-ALERT] [SiteMonitor] YÜKSEK ·
http://localhost:8080/health- duplicate · İçerik SSL Sorunu` (çıplak URL + tire artığı).

**Kullanıcı kararı (bağlayıcı):** logo, kart başlık çubuğunda **"Site Monitor" yazısının yanında,
yazı yüksekliğine eş** boyutta konumlanır. Başka hiçbir yerde mail gövdesine logo girmez.

Kapsam argümanı: `$ARGUMENTS` — boş: tüm fazlar; `sablon`: 0–3, 5, 6; `icerik`: 0, 1, 4, 5, 6;
`test`: 0, 5, 6.

## Değişmez kurallar

1. Önce REPRODÜKSİYON, sonra düzeltme: hatayı üretilen HTML üzerinde kanıtlamadan şablona dokunma.
2. Gerçek SMTP'ye/dış host'a test sırasında ASLA bağlanma (JavaMailSender mock); gerçek test maili
   yalnız Faz 6'da ve açık kullanıcı onayıyla atılır.
3. Alarm davranışına dokunma: günlük re-alert dedupe, üç gönderim yolunun (`processConfirmedOutage`
   / `sendResolutionNotification` / `reNotify`) alıcı tutarlılığı, `notification_logs` kayıtları
   aynen kalır — bu bir GÖRÜNÜM/İÇERİK denetimidir, yönlendirme değişikliği değildir.
4. Varyant semantiği korunur: severity → yaprak rengi eşlemesi (ekran görüntüsündeki amber doğru
   çalışıyor). `branding/BRAND.md` güncel e-posta kurallarının tek doğruluk kaynağıdır.
5. Coverage floor'ları düşürülmez; `TESTING.md` geçersizleşirse aynı değişiklikte güncellenir;
   commit atılmaz. Ortam: `JAVA_HOME=C:\Program Files\Zulu\zulu-25`,
   Maven `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`.

## Faz 0 — Ön koşul

`mvn.cmd -f backend/pom.xml -B clean verify` yeşil (baseline); `git status --short` kaydet;
`branding/BRAND.md` §4–§5 e-posta kurallarını oku (revize edilmiş kart-içi lockup spesifikasyonu).

## Faz 1 — Mail envanteri (uçtan uca, TAM liste)

`JavaMailSender` / `MimeMessage` / `MimeMessageHelper` kullanan HER yolu grep'le bul ve matris çıkar
(tür × tetikleyici × şablon kaynağı × severity/varyant × alıcı seçimi × plain-text fallback var mı):

- Alarm ilk bildirimi, RE-ALERT ve çözülme (resolution) — `EmailNotificationService` üç yolu.
- Toplu ağ kesintisi admin maili (`NetworkOutageEvent`).
- Domain expiry / DOMAINMON_* mailleri (içerik `reconstructDomainContext` ile taze kurulur).
- Haftalık rapor hatırlatma (`WeeklyReportReminderService`, Cuma 09:00) ve **e-posta-token onay**
  mailleri (login'siz onay linki — linkin taban URL'i özellikle kritik).
- SMTP ayarları test maili (`SmtpSettingsService`/`SmtpMailService`) ve varsa diğer admin mailleri.

Envanterde şablonu OLMAYAN ya da elle string birleştiren yol varsa işaretle — Faz 3'te tek şablon
altyapısına alınacaklar bunlar.

## Faz 2 — Reprodüksiyon ve kök neden

Her mail türü için mevcut kodla HTML çıktısını üret (mock SMTP; mesaj gövdesini dosyaya yaz:
`backend/target/email-previews/once-<tur>-<severity>.html`) ve tarayıcıda incele. Ekran
görüntüsündeki hatanın kök nedenlerini KANITLA — adaylar:

1. `<img>` genişliği yalnız CSS'te (`style="width:.."` / `max-width`) ya da hiç yok → Outlook
   masaüstü (Word render motoru) CSS genişliğini yok sayar, PNG'yi **doğal boyutunda (320px)**
   basar; Windows %125 DPI ölçeklemesi bunu daha da büyütür.
2. Logo, kart tablosunun DIŞINDA kendi satırında/bloğunda → karttan kopuk serbest görsel.
3. Dış kapsayıcı 600px ortalanmış tablo değil → logo ile kart arasındaki dev boş gri alan.
4. Retina niyetiyle konan 320px varlık + eksik `width`/`height` attribute kombinasyonu.

Bulguları "kök neden → kanıt (HTML satırı) → düzeltme" tablosu olarak rapora yaz.

## Faz 3 — Outlook-güvenli şablon standardı + logo lockup'ı

TÜM mail türleri tek ortak şablon yardımcısından geçer (tek doğruluk kaynağı — tür başına yalnız
içerik bloğu değişir). Standart:

- **Yerleşim:** tablo tabanlı, `width="600"` ortalanmış tek kapsayıcı tablo; `<div>`+CSS float
  YOK; tüm stiller inline; `<style>` bloğuna güvenme (Outlook/Gmail kırpar).
- **Logo lockup (kullanıcı kararı):** koyu başlık çubuğunda tek satır:
  `[logo] Site Monitor            ENTERPRISE`
  ```html
  <td style="background:#2B2B33;padding:12px 16px;">
    <img src="cid:brand-logo" width="32" height="32" border="0" alt="Site Monitor"
         style="display:inline-block;vertical-align:middle;width:32px;height:32px;">
    <span style="font-size:18px;color:#FFFFFF;vertical-align:middle;padding-left:10px;">Site Monitor</span>
  </td>
  ```
  `width`/`height` **HTML attribute olarak ZORUNLU** (Outlook yalnız bunlara güvenilir uyar);
  inline style ikincil destek. Logo yüksekliği yazı satırıyla eş (28–32px). Kart dışında,
  gövdede, footer'da BAŞKA logo yok — serbest yüzen 160px+ header logosu tamamen kaldırılır.
- **Varlık:** `email-assets/email-{v}.png` 320px doğal boyutlu — 32px kullanım için ağır;
  `branding/assets/logo-{v}-192.png` kopyalarıyla değiştir (aynı dosya adlarıyla) → hem hafif hem
  attribute'lar sayesinde her istemcide 32px. `muted` maillerde kullanılmaz (BRAND.md).
- **Varyant matrisi tek metotta kalır:** CRITICAL→`critical`; WARNING→`warning`; YÜKSEK/HIGH gibi
  ara seviyeler dahil HER severity açıkça eşlenir (eşlenmemiş değer → `warning` + WARN logu;
  sessiz düşme yok); resolution→`ok`; rapor/hatırlatma/test→`ok`. Üç gönderim yolu + haftalık
  rapor + admin mailleri hepsi bu metottan geçer.
- Arkaplan görseli, web font, negatif margin, `position` KULLANMA; buton = tablolu bgcolor'lı
  hücre; koyu-mod istemcilerde okunurluğu bozan saf-siyah metin/saf-beyaz zemin ikilisinden kaçın.

## Faz 4 — İçerik ve kimlik denetimi

- **Gönderen adı:** "CertMonitor Alerts" kalıntısı. Kodda `setFrom(adres, kişisel_ad)` ile mi
  geliyor yoksa Gmail hesabının görünen adı mı — tespit et. Koddaysa "Site Monitor Alerts" yap ve
  testle pinle; hesap seviyesindeyse rapora "kullanıcı aksiyonu: Gmail görünen adını değiştir"
  yaz (adres `certmonitor01@gmail.com` hesap seviyesidir, kod değiştiremez — NamingConsistencyTest
  istisna listesine gerekçesiyle ekle).
- **Subject standardı:** `[Site Monitor] <SEVERITY_TR> · <monitör adı> · <kısa sorun>` ;
  RE-ALERT öneki korunur: `[RE-ALERT] [Site Monitor] ...`. Çıplak URL subject'e girmez (monitör
  ADI girer); "health- duplicate" tarzı kesme/birleştirme artıklarının kaynağını bul (ad mı URL'den
  türetiliyor?) ve düzelt. "SiteMonitor" bitişik yazımı her yerde "Site Monitor" olur (subject,
  kart başlığı, footer).
- **Taban URL:** mail içi linkler config'ten gelen taban URL'i kullanmalı (yerel koşuda
  `localhost:8080` doğru; prod'da env ile gelen adres). Hardcode/istekten-türetme varsa düzelt.
- TR/EN tutarlılığı (mail dili tek ve tutarlı — yarı TR yarı EN cümle yok), tarih formatları
  Europe/Istanbul, secret maskeleme mail gövdesinde de geçerli, plain-text fallback her türde var
  ve logo/HTML artığı içermiyor.

## Faz 5 — Önizleme harness'ı + regresyon testleri

- **Önizleme harness'ı (kalıcı):** her mail türü × severity için HTML'i
  `backend/target/email-previews/sonra-*.html` olarak üreten test yardımcısı — gelecekte şablon
  değişikliği gözle doğrulanabilir olsun. `once-*` / `sonra-*` çiftleri rapora girer.
- **Şablon regresyon testleri** (MockitoExtension + AssertJ, mock JavaMailSender):
  - Üretilen HTML'de logo `<img>`'i TAM BİR kez; `width="32"` ve `height="32"` attribute'ları var;
    `src="cid:brand-logo"`; multipart/related içinde `Content-ID: <brand-logo>` eki mevcut.
  - Logo `<img>`, başlık hücresi içinde "Site Monitor" metniyle aynı `<td>`'de (kart-dışı logo
    regresyonunu yakalar).
  - Kapsayıcı tablo `width="600"`; `<style>` bloğu yok; dış URL'li `<img>` yok.
  - Subject format testleri: normal + RE-ALERT + resolution kalıpları; "SiteMonitor" bitişik
    yazımı ve çıplak `http` subject'te YOK.
  - Severity→varyant eşleme testi: bilinen tüm severity değerleri + bilinmeyen değer davranışı.
  - Gönderen adı testi (koddaysa).
- `mvn -B clean verify` + (frontend'e dokunulduysa) `npm run test:coverage` yeşil; kapılar korunur.

## Faz 6 — Doğrulama + rapor

1. Tüm `sonra-*.html` önizlemeleri tarayıcıda gözden geçir: 600px ortalanmış kart, başlıkta
   yazıyla eş boy logo, boş gri alan yok.
2. Kullanıcı ONAY VERİRSE: SMTP ayarlı ortamda kendine tek test maili (her ana türden birer) —
   Outlook masaüstünde gerçek doğrulama (ekran görüntüsündeki istemci buydu; jsdom Outlook değildir).
3. **Final rapor:** mail envanteri matrisi (tür × şablon × varyant × durum ✅/❌→✅); kök neden →
   düzeltme tablosu; önce/sonra önizleme yolları; kalan kullanıcı aksiyonları (Gmail görünen adı
   vb.); test/coverage sayıları. Commit kullanıcı onayına bırakılır — `fix:` conventional prefix
   önerisiyle.

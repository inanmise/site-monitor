---
description: Uygulama içi User Guide'ı (Yardım sekmesi whitepaper'ı) uçtan uca denetler ve modern makale üslubuyla baştan yazar — tüm CertMonitor izlerini kaldırır (yalnız Site Monitor kalır), çift-kopya sapmasını çözer, içeriği güncel özellik setiyle eşitler, kalıcı isim bekçisi testleri ekler.
argument-hint: [isim|icerik|uslup] (opsiyonel — boş bırakılırsa TAM kapsam)
---

# /kilavuz-denetim — User Guide Uçtan Uca Denetim, Modern Yeniden Yazım ve İsim Temizliği

**Tespit edilmiş durum (bu komut yazılırken doğrulandı):**
- User Guide = Yardım sekmesi: `HelpPage.jsx`, `frontend/src/assets/whitepaper.md`'yi (≈86KB)
  `react-markdown` ile render eder; TOC'u `#/##/###` başlıklardan `slugify` ile üretir.
- Kök dizinde İKİNCİ bir kopya var: `WHITEPAPER.md` (≈80KB) — iki kopya **birbirinden sapmış**.
  Ayrıca bayat bir `WHITEPAPER.pdf` (Haziran tarihli) duruyor.
- CertMonitor izleri: `whitepaper.md` 4 eşleşme (satır 3 rename notu; satır 335 + 888
  `cert-monitor.example.com` örnek host; satır 356 `Kullanıcı/DB: certmonitor/certmonitor`),
  kök `WHITEPAPER.md` 4 eşleşme, `CHANGELOG.md` 6 (tarihsel), `docs/` altında 5 dosya (iç
  geliştirme komut dokümanları). `monitorGuides.js`, `README`, `QUICKSTART`, i18n temiz.

Kapsam argümanı: `$ARGUMENTS` — boş: tüm fazlar; `isim`: 0–2, 4–6; `icerik`: 0, 3, 5, 6;
`uslup`: 0, 3 (yalnız üslup dönüşümü), 5, 6.

## Değişmez kurallar

1. **Ürün adı her yerde "Site Monitor"** (bitişik "SiteMonitor" da düzeltilir — BRAND.md §5.1
   ile tutarlı). Eski ada hiçbir biçimde yer verilmez: `CertMonitor`, `certmonitor`,
   `cert-monitor`, `cert_monitor`, "Cert Monitör" (TR yazımı dahil, case-insensitive).
2. **Tarihsel istisnalar (dokunma, gerekçesiyle raporla):** `CHANGELOG.md` girdileri tarih
   kaydıdır — geçmişi yeniden yazmak kaydı tahrif eder; rename'in kendisini anlatan
   `docs/ISIM_DEGISIKLIGI_KOMUTU.md` da doğası gereği eski adı içerir. Bunlar isim bekçisi
   testlerinin istisna listesine gerekçeli girer. Diğer `docs/*.md` iç komut dokümanlarındaki
   izler temizlenir (tarihsel değilse).
3. **Dokümana yalan yazdırma:** yerel dev veritabanı GERÇEKTEN `certmonitor` adını taşıyor
   (`.env`, `start-local.ps1`). Kılavuza `sitemonitor/sitemonitor` yazmak dokümantasyonu bozar.
   Çözüm: kılavuzda ortam-bağımsız placeholder kullan (`<db-kullanıcısı>/<db-adı>` + "değerler
   `.env`'den gelir" notu) — iz sıfırlanır, doküman doğru kalır. Gerçek DB'nin yeniden
   adlandırılması ayrı bir operasyon kararıdır; raporda "öneri" olarak işaretle, bu komutta YAPMA.
4. Kılavuz içeriği mevcut davranışı anlatır — davranış uydurma; emin olmadığın her davranışı
   koddan doğrula (CLAUDE.md + ilgili servis/bileşen).
5. Testler ve kapılar: coverage floor'ları düşürülmez; `TESTING.md` geçersizleşirse aynı
   değişiklikte güncellenir; commit atılmaz, kullanıcı onayına sunulur.

## Faz 0 — Ön koşul

`cd frontend && npm ci && npm run test:coverage` yeşil (baseline); `git status --short` kaydet.
İz sayımını tekrarla ve kaydet (rapordaki "önce" sütunu):
`grep -rniE 'certmonitor|cert[-_ ]monitor|cert monitör' frontend/src/assets/ WHITEPAPER.md docs/ CHANGELOG.md`

## Faz 1 — Tek kaynak kararı (çift-kopya sapması)

- İki kopyayı diff'le; sapmanın ne olduğunu raporla (hangisi hangi bölümlerde ileride).
- **Tek doğruluk kaynağı: `frontend/src/assets/whitepaper.md`** (uygulamanın içinde render
  edilen budur). İçerik birleştirmesinde kök kopyada olup asset kopyada olmayan güncel bölümler
  taşınır — körlemesine üzerine yazma.
- Kök `WHITEPAPER.md`'nin kaderi: içeriği asset'ten üretilen kopya olur; senkron bir testle
  kilitlenir (aşağıda) ya da yerine "kaynak: `frontend/src/assets/whitepaper.md`" işaretli kısa
  bir yönlendirme bırakılır — repo vitrini için üretilen kopyayı öner.
- Bayat `WHITEPAPER.pdf`: yeni içerikten yeniden üretilmeden repo'da KALMAMALI. Üretim
  imkânı varsa (pandoc/md-to-pdf) yeniden üret; yoksa `_to_delete/`'e taşı ve rapora
  "PDF yeniden üretilecek" aksiyonu yaz — satır 3'teki "PDF bir sonraki sürümde" notu da
  böylece kapanır (not kılavuzdan silinir; rename bilgisi zaten CHANGELOG'da).

## Faz 2 — İsim temizliği (kılavuz kapsamı)

Bilinen 4 iz + tarama ile çıkan her yeni iz:
- Satır 3 rename notu → SİL (kılavuz ürünü bugünkü adıyla anlatır; tarihçe CHANGELOG'un işi).
- `cert-monitor.example.com` (2 yer) → `site-monitor.example.com` (örnek host'un kurgu olduğu
  belli olsun; gerçek bir müşteri host'u yazma).
- `Kullanıcı/DB: certmonitor/certmonitor` → kural 3'teki placeholder yaklaşımı.
- `docs/` altındaki tarihsel-olmayan izler → "Site Monitor"a çevir.
- Bitişik "SiteMonitor" yazımları → "Site Monitor" (başlıklar, gövde, diyagram metinleri dahil).
- ASCII diyagramların içindeki izleri unutma (satır 335 bir diyagram içindeydi) — diyagram
  hizalamasını bozmadan değiştir (kutu genişliklerini yeniden dengele).

## Faz 3 — İçerik denetimi + modern makale üslubuyla yeniden yazım

**Kullanıcı kararı (bağlayıcı): kılavuz son derece modern, makale tarzı bir yazım üslubuyla
baştan yazılır.** Üslup rehberi:

- Her ana bölüm 2–3 cümlelik bir giriş paragrafıyla açılır: bu özellik NEDİR, NEDEN vardır,
  kim kullanır. Kuru özellik listesi yerine akan anlatı; madde işareti yalnız gerçekten sayılan
  şeylerde (eşik listesi, izin matrisi gibi).
- Aktif ve doğrudan dil, ikinci çoğul şahıs: "eklersiniz", "görürsünüz" ("eklenir/görülür"
  bürokratik kipinden kaçın). Kısa cümleler; paragraflar 3–5 cümle.
- Her monitör türü bölümünde küçük bir **"Ne zaman kullanmalı?"** paragrafı + gerçekçi bir
  senaryo ("Sertifikanın 7 gün içinde süreceğini varsayalım…") — modern ürün dokümantasyonu
  (Stripe/Linear docs) tonunda.
- Ekran/sekme adları **kalın**, teknik değerler ve env anahtarları `kod stili`; TR dil bütünlüğü
  (yarı EN cümle yok); terimler tek sözlükte sabitlenir (ör. hep "alarm", bir yerde "alert"
  bir yerde "uyarı" değil) — sözlüğü kılavuzun sonuna ekle.
- **HelpPage kısıtları:** başlık hiyerarşisi en fazla `###` (TOC 3 seviye okur); başlık
  metinleri slug çakışması üretmeyecek şekilde benzersiz; başlıklarda `**bold**`/backtick
  kullanma (slugify bozar).

**Kapsam eşitlemesi:** `App.jsx` sekme whitelist'ini çıkar ve her sekme/özelliğin kılavuzda
bölümü olduğunu matrisle doğrula — Dashboard, sertifika yaşam döngüsü, envanter, Uptime/Port/
DNS/Ping/HTTP/Keyword/Sayfa Bütünlüğü/Senaryo (k6)/Domain kaydı izlemeleri, bakım pencereleri,
alarm geçmişi + eskalasyon + RE-ALert/çözülme mailleri (yeni kart-içi logo lockup'ıyla — mail
görselini anlatan bölüm varsa güncelle), haftalık raporlar + onay akışı, incident'lar, zayıf
algoritma raporu, tahmin/forecast, izin matrisi, LDAP, SMTP/branding ayarları, System Health,
audit log, SQL Playground. Eksik bölüm = yaz; bayat bölüm (eski ekran adı, kalkmış davranış) =
düzelt. Sürüm/tarih damgasını `VERSION` ile eşitle.

## Faz 4 — Kalıcı isim bekçileri

- `naming-consistency.test.jsx` kapsamına `assets/whitepaper.md` ve `monitorGuides.js` dahil mi
  kontrol et; değilse ekle — desen seti TR yazımı da içerir: `certmonitor|cert[-_ ]monitor|cert monitör`
  (case-insensitive) + bitişik `SiteMonitor` yazımı için ayrı bir assert (istisna: kod
  tanımlayıcıları değil, kullanıcıya görünen metin).
- Çift-kopya senkron testi: kök `WHITEPAPER.md` üretilen-kopya yaklaşımı seçildiyse içerik
  eşitliğini pinleyen test/script (sapma bir daha sessizce birikemez).
- İstisna listesi (CHANGELOG, ISIM_DEGISIKLIGI_KOMUTU, `_to_delete/`) testin içinde gerekçeli.

## Faz 5 — Doğrulama

- `npm run test:coverage` + `npm run build` yeşil; i18n parity + used-keys testleri dahil.
- İz sayımı SIFIR (istisna listesi hariç): Faz 0'daki grep'i tekrarla, önce/sonra tabloya koy.
- Uygulamada gözle: `npm run build` sonrası Yardım sekmesi — TOC tamam, başlık linkleri
  çalışıyor, koyu temada okunur, uzun bölümlerde scroll-spy aktif başlığı doğru işaretliyor.
- Kılavuzu bir kez de "yeni kullanıcı gözüyle" baştan sona oku: akış makale gibi mi, kopuk
  liste kalmış mı, her bölüm girişi var mı — üslup rehberine uymayan bölümü düzelt.

## Faz 6 — Final rapor

İz temizliği önce/sonra tablosu (dosya × eşleşme); tek-kaynak kararı ve sapma özeti; kapsam
matrisi (sekme × bölüm ✅/yeni yazıldı/güncellendi); üslup dönüşümü örneği (bir bölümün önce/
sonra kısa alıntısı); PDF durumu; kullanıcı kararı bekleyenler (gerçek DB adının yeniden
adlandırılması önerisi — ayrı operasyon işi); commit önerisi (`docs:` prefix) kullanıcı onayına.

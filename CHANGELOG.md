# Changelog

All notable changes to Site Monitor (formerly CertMonitor) are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/).

İşaretler: **⚠ Davranış** = kullanıcıyı ya da operatörü etkileyen varsayılan/davranış değişikliği ·
**Yeni yapılandırma** = yeni ortam değişkeni / ayar anahtarı (varsayılanıyla) · **BREAKING** = uyumsuz değişiklik.
20.55.0 – 20.86.0 girdileri 2026-09-26'da `git log` başlıklarından geriye dönük dolduruldu (o sürümler
yayınlanırken `[Unreleased]` boştu); regresyon turu belgeleri, test ve `chore(release)` commit'leri ayıklandı.
Bu aralıkta BREAKING işaretli (`!:` / `BREAKING CHANGE:`) commit yoktur. Commit bazında tam liste:
Yardım → Yenilikler ya da `docs/releases/index.json`. Prod dağıtımı: `docs/PROD_DEPLOY_CHECKLIST.md`.

---

## [Unreleased]

### Fixed
- **Takip adı / alan adı değişince açık sertifika penceresi eski adda kalıyordu:** pencerenin Düzenle'sinden ad
  değiştirilince Sağlık sekmesi "Sağlık bilgisi yüklenemedi (404)", Kontrol Geçmişi "Domain envanterde bulunamadı
  (404)" gösteriyor, başlık eski adı taşıyordu. Pencere artık kayıttan hemen sonra yeni adla sürer ve kullanıcı
  bulunduğu sekmede kalır. Manuel Sertifikalar ve Envanter listeleri (açık Envanter çekmecesi dâhil) yeni adı
  sayfa yenilenmeden gösterir; Envanter çekmecesi kayıttan sonra eski alan değerlerini de artık göstermez.

## [20.112.0] — 2026-10-08

### Added
- **Site Monitor'e özel 404 sayfası:** bilinmeyen bir adres gerçek HTTP 404 ile markalı sayfayı açar (tüm temalar,
  telefona uygun): istenen adres, "Ana sayfaya dön" / "Geri" / "Yardım" ve sık kullanılan sayfalara kısa yollar.
  Uygulama içinde bilinmeyen bir `?tab=` "Sayfa bulunamadı", rolün açamadığı bir sekme "Erişim yok" paneli gösterir
  (eskiden boş sayfa). Bilinmeyen API adresi JSON 404, eksik dosya düz 404 döner.
- **Her sayfada başlık ve açıklama:** 40 sekmenin, giriş, oturum, bakım, parola değiştirme ve 404 durumlarının tarayıcı
  başlığı ("Sayfa · SiteMonitor") ve meta açıklaması, kullanıcının dilinde.
- **Site Monitor'e özel favicon seti:** turp markasının küçük boyutlarda net okunan sürümü — SVG, ICO (16/32/48), iOS
  ikonu, PWA ikonları (192/512, maskeli) ve uygulama bildirimi (`site.webmanifest`).
- **Manuel yüklemede ilerleme durumu:** dosya okuma → tarayıcıda ayıklama (ZIP'te n / N dosya) → sunucuya gönderme
  (gerçek yüzde) → analiz / kaydetme aşamaları, geçen süre ve "Vazgeç".
- **Hata ayrıntısı:** hata bildirimlerinde açılır "Teknik ayrıntı" (HTTP durumu, hata kodu, istek kimliği); sunucu her
  hatada istek kimliği döndürür ve günlüğe yazar — kullanıcı bildirdiğinde kayıt hemen bulunur.

### Changed
- **Hata mesajları baştan yazıldı:** her hata ne olduğunu, olası nedenini ve ne yapılacağını söyler (TR + EN). Ağ
  kesintisi, zaman aşımı ve her HTTP durumu için açıklayıcı metin; teknik metin (Java sınıf adları, SQL, "Failed to
  fetch") artık ekrana düşmez. Manuel sertifika sihirbazının hata mesajları (yanlış parola, BKS, ZIP sınırı, çakışan ad
  …) somut adımlar içerir. Belirsiz hata metinlerini engelleyen kural testi eklendi.

### Fixed
- Alan Adı İzleme sayfasında telefonda "Diğer işlemler" düğmesi ekranın dışına taşıyordu.
- Eksik bir statik dosyanın 404 yanıtı bir yıllık önbellek başlığıyla dönüyordu.
- Haftalık rapor onay sayfasının alt bilgisinde kurum adı sabit yazılıydı.

## [20.111.0] — 2026-10-07

### Added
- **Manuel sertifikanın takip adı sonradan değiştirilebilir:** envanter düzenleme formunda (ve Manuel Sertifikalar
  listesindeki "Düzenle" eylemiyle) takip adı düzenlenir; takip adı kuralı alanın altında anında denetlenir, çakışan ad
  409 ile reddedilir. Onaydan sonra sürümler, geçmiş değerlendirmeler, alarmlar ve notlar yeni ada taşınır.
- **"Yine de yükle":** yenilemede yüklenen sertifika güncel sürümle aynıysa akış durmaz; "Yine de yükle" ile aynı
  sertifika yeni sürüm olarak kaydedilir (bitiş tarihi değişmez, sürüm "Aynı sertifika yeniden yüklendi" rozetiyle
  görünür). Farklı ve daha eski bir sertifika yine onay ister.
- **Eski sürümler kalıcı olarak silinebilir:** Sürümler sekmesinde güncel olmayan her sürümde "Sil" (yeni sürüm
  yükleyebilen kullanıcılar; güncel sürüm silinemez). Denetim `CERT_MANUAL_VERSION_DELETE`.

### Changed
- **⚠ Davranış — zincir tek sertifikadır:** yüklenen dosyadaki root / intermediate / leaf parçaları artık ayrı ayrı
  takip kaydı olmaz; zincirin ucundaki sertifika TEK kayıt olarak takip edilir, diğerleri onun zinciri olarak gösterilir.
  Birbiriyle ilgisiz sertifikalar (ör. farklı kök CA'lar) yine ayrı ayrı seçilebilir. Zincirin bir parçası tek başına
  seçilirse 400.
- Manuel sertifikanın penceresinde SSL sekmesi geri geldi (varsayılan sekme): saklanan sertifikadan ağsız önizleme —
  ağdan izlenen sertifikalarla AYNI hüküm kartı, sertifika/güven kontrolleri ve zincir kartları; bağlantı kontrolleri ve
  host adı satırı yok. Sihirbazın inceleme adımı da aynı zincir görünümünü kullanır. Manuel kayıtta Grafik sekmesi yok.
- Yeniden adlandırma onayı silme ikonu yerine düzenleme tonuyla açılır.
- **Tarayıcı gibi sertifika hiyerarşisi:** manuel sertifikanın SSL sekmesinde "Zincir | Hiyerarşi (tarayıcı gibi)" geçişi
  ve Sürümler sekmesinde her sürüm için "Görüntüle": kök → ara → yaprak alt alta, girintili ağaç; seçilen sertifikanın
  konu, düzenleyen, geçerlilik, seri numarası, algoritmalar, SAN, anahtar kullanımı, SHA-256/SHA-1 parmak izi ve tek
  sertifikayı PEM olarak indirme. Kök dosyada yoksa açıkça belirtilir.
- **⚠ Davranış — silme KALICIDIR:** sertifika (ağ + manuel) ve izleme silme her durumda kalıcıdır; çöp kutusu, geri
  yükleme, "kalıcı sil", otomatik temizleme ayarı (`site.monitor.inventory.auto-purge-days`) ve `inventory.purge` izni
  kaldırıldı. Sertifika silinince açık alarmları kapatılır; kontrol geçmişi, notlar, yüklenen sürümler, envanterden
  türeyen Port/DNS izlemeleri ve kontrolleri, uptime kayıtları, zayıf algoritma istisnası ve tanılama geçmişi silinir;
  kapalı alarm kayıtları, denetim ve değişiklik geçmişi iz olarak kalır. Bağımsız Port/DNS izlemeleri de artık kalıcı
  silinir (alarmları kapanır). Onay pencereleri silmenin kalıcı olduğunu ve neyin silineceğini söyler.
- **⚠ Mevcut çöp kutusu temizlenir:** yeni sürümün ilk açılışında daha önce silinmiş (çöp kutusundaki) sertifika ve
  Port/DNS kayıtları bir defaya mahsus kalıcı olarak silinir; adlar denetim kaydına (`SYSTEM_DELETED_RECORDS_PURGE`) yazılır.
- Silinen bir sertifikanın / izlemenin adı artık yeni kayıt eklemeyi engellemez ("zaten var" uyarısı çıkmaz).

### Security
- **⚠ Özel anahtar ve keystore parolası tarayıcıdan hiç çıkmaz:** dosyadan sertifika yüklemede PEM, DER, P7B, JKS /
  JCEKS, PFX / P12 ve ZIP tarayıcıda açılır; sunucuya YALNIZ açık sertifikalar gider. PFX/P12 parolası yalnız tarayıcıda
  kullanılır; yanlış parola istek gönderilmeden alanın altında söylenir. Sunucu özel anahtar ya da ham keystore içeren her
  yüklemeyi 400 `PRIVATE_KEY_NOT_ACCEPTED` ile reddeder (API ile PFX/JKS gönderen istemciler için uyumsuz değişiklik).
  BKS/UBER tarayıcıda açılmaz; keytool ile PEM'e çevirme rehberi gösterilir.

### Fixed
- Silme onaylanınca kart / satır hemen kaybolur (eskiden liste yenilenene kadar duruyordu); başka bir sunucudan eski
  liste gelse de silinen kayıt geri gelmez. Pano, Tüm Sertifikalar, Envanter, Manuel Sertifikalar, dokuz izleme sayfası
  (tekil + toplu) ve İzleme Genel Bakış.
- HTTP, Anahtar Kelime, Ping, Alan Adı, Sayfa Bütünlüğü ve Sayfa Hızı düzenleme penceresindeki "Sil" onay sormadan
  siliyordu; artık onay ister.

## [20.110.1] — 2026-10-07

### Changed
- Manuel Sertifikalar sayfasındaki "Hangi dosyayı yüklemeliyim?" rehberi varsayılan olarak KAPALI gelir (liste boşken de);
  açılınca tercih tarayıcıda hatırlanır.

### Fixed
- Sertifika penceresinin "Sürümler" sekmesinden yeni sürüm yüklenince sihirbaz sonuç ekranı yerine boş 1. adıma
  dönüyordu (kayıt yapılmış olsa da başarısız sanılabiliyordu); liste artık arka planda tazelenir.
- Envanter tablosu / kart listesi / ayrıntı çekmecesi dosyadan yüklenen sertifikada "Şimdi kontrol et" yerine diğer
  ekranlardaki gibi "Yeniden değerlendir" der.
- Hata ayrıntısı saklanmadan önceki DNS geçmiş satırları (hata metni de yoktu) "ad çözümlenemedi" diye yanlış tahmin
  ediliyordu; artık "neden bilinmiyor" + uçtan uca tanılamaya yönlendirme gösterilir.

### Added
- Canlı uçtan uca test takımı (`frontend/playwright.live.config.js`, `e2e/live/`): gerçek arayüz + gerçek backend —
  dosyadan sertifika yolculuğu, kontrol geçmişi hata paneli, uçtan uca tanılama pencereleri. Yalnız `E2E_LIVE=1` ile koşar.

## [20.110.0] — 2026-10-06

### Added
- **Dosyadan sertifika takibi (Manuel Sertifikalar):** ağ üzerinden erişilemeyen sertifikalar (OCP keystore/truststore
  JKS'leri, CA'dan gelen PEM'ler, zinciri tamamlanmış PFX'ler) dosya yüklenerek takibe alınır. Desteklenen biçimler:
  PEM / CRT / CER (Base64 ya da DER), DER, P7B / P7C, PFX / P12 (parola), JKS / JCEKS / BKS, ZIP paketi ve yapıştırılan
  PEM metni. Yüklenen dosya analiz edilir (geçerlilik, zincir, güven, zayıf algoritma, CSR / özel anahtar / süresi
  dolmuş uyarıları); truststore'dan birden çok sertifika tek seferde takibe alınabilir.
- **Takip normal sertifikalarla birebir aynı:** aynı eşikler, kritiklik katmanı, alarm / eskalasyon / bildirim, raporlar,
  dashboard, tüm sertifikalar, uyarılar, yenileme tavsiyesi, öngörü ve envanter alanları. Kartlarda ve tablolarda
  "Manuel" rozeti, bağlantı yolu "Yüklenen dosya".
- **Yenileme = yeni sürüm:** yeni sertifika yüklenince takip ona geçer; eski sürümler silinmez, sertifika penceresindeki
  "Sürümler" sekmesinde (parmak izi, geçerlilik, yükleyen, anahtar değişti mi, SAN farkı, PEM indir) görülür.
- **Giriş noktaları:** Dashboard ve Envanter'deki "Domain Ekle" düğmesinde "Dosyadan sertifika ekle" seçeneği; ayrı
  "Manuel Sertifikalar" sayfası ("Hangi dosyayı yüklemeliyim?" rehberi, liste, yükleme sihirbazı).
- Özel anahtarlar ve parolalar hiçbir zaman saklanmaz; yalnız açık sertifika zinciri tutulur. Denetim:
  `CERT_MANUAL_UPLOAD`, `CERT_MANUAL_RENEW`.

### Changed
- Fırtına eşiği paydası dosyadan yüklenen sertifikaları saymaz (fırtına üyesi olamazlar); manuel kayıt yokken sayı aynı.

## [20.109.0] — 2026-10-05

### Added
- **Kontrol geçmişinde hata teşhisi (Ping, Port, DNS, Sayfa Bütünlüğü, Sayfa Hızı, Durum İzleme, Alan Adı, Sertifika):**
  başarısız kontrolün NEDENİ artık kaydedilir (ICMP yok, NXDOMAIN / SERVFAIL / kayıt yok, bağlantı reddedildi, zaman
  aşımı, vekil reddetti / port izinli değil, TLS el sıkışması / güven / ad uyuşmazlığı, HTTP hata kodu, banner
  uyuşmazlığı, kırık / zaman aşımlı / güvensiz kaynak, kayıt sunucusu yanıt vermedi …). Başarısız satırda neden rozeti +
  tek satır açıklama; açılınca "Neden / Etkisi / Ne yapmalı", kayıtlı ayrıntılar (aşama, yol, çözümlenen IP, yanıt kodu,
  zaman aşımı) ve kopyalanabilir ham hata. Eski satırlar hata metninden en yakın nedenle, "ayrıntı kaydedilmemiş" notuyla.
- **DNS geçmişinde başarısız sorgu görünür:** hata metni ilk kez saklanır; satır "Değişiklik Yok" yerine "Sorgu başarısız"
  der, "Başarısız sorgu" sayacı ve süzgeci eklendi.
- **Uçtan uca tanılama — Ping, Port, DNS, Sayfa Bütünlüğü, Sayfa Hızı:** ayrıntı başlığında ve başarısız geçmiş satırında.
  Ping: DNS → ICMP → TCP canlılık (isteğe bağlı traceroute). Port: DNS → TCP → TLS / HTTP / banner / UDP, vekil varsa
  doğrudan ↔ vekil karşılaştırması. DNS: her çözücü ve yetkili ad sunucusu ayrı ayrı (yanıt kodu, cevaplar, TTL, DNSSEC,
  tutarsızlık). Sayfa Bütünlüğü / Sayfa Hızı: HTTP tanılamasının aynısı + kaynak sorunları / aşama süreleri eşiklerle.
  Kontrol kaydı yazmaz, alarm üretmez; `diagnostics.run` + izlemeyi işletme yetkisi; kullanıcı ve izleme başına dakikada
  6, aynı anda 4; geçmiş saklanır; denetim `PING|PORT|DNS|PAGE|PAGESPEED_DIAGNOSTICS_RUN`.

### Changed
- **⚠ Davranış:** Alan Adı "Sorun Tanıla", Durum İzleme ve sertifika penceresindeki tanılama artık rol (yönetici) yerine
  `diagnostics.run` izni + takım yetkisiyle açılır; alan adı tanılaması envanterde olmayan, takımınıza ait bağımsız alan
  adı izlemelerinde de çalışır.
- Sayfa Hızı geçmişinde yapılandırma hatası (CONFIG_ERROR) artık "Down"dan ayrı gösterilir.

## [20.108.0] — 2026-10-05

### Added
- **Temalar:** Açık ve Koyu'ya ek olarak Blueprint, Parchment, Alloy, Obsidian, Slag ve Crucible. Kullanıcı menüsünde ve
  telefonda üst çubukta tema seçici; yalnız açık bırakılan temalar listelenir (renk örneği, açık/koyu ipucu, etkin işareti).
- **Ayarlar → Görünüm → Temalar:** her tema için canlı önizlemeli kart, "Listede göster" (temayı listeden çıkarma),
  varsayılan tema seçimi ("Sistem" = cihazın açık/koyu ayarı dahil) ve yalnız bu sekmede, kaydetmeden önizleme. Kayıt
  yalnız global yöneticiye açık; kapsamlı müdür sayfayı salt okunur görür; denetim kaydı `THEME_SETTINGS_SAVE`.
- **Tema seçimi hesapta saklanır:** sonraki oturumlar (başka cihazda da) aynı temayla açılır. Yönetici seçilen temayı
  kaldırırsa varsayılan uygulanır; tema yeniden açılınca kişinin seçimi geri gelir. Giriş sayfası da tema ayarlarına uyar.

### Changed
- Koyu stiller artık temaya değil renk şemasına (`data-scheme`) bağlı; koyu şemalı temalar tüm koyu stilleri devralır.
- Odak halkası Açık ve Koyu temalarda daha belirgin (WCAG 3:1); sekiz tema için otomatik kontrast kapısı.

## [20.107.0] — 2026-10-04

### Added
- **Anahtar kelime formundaki "Test" de hata teşhisi gösteriyor:** koşul sağlanmazsa kontrol geçmişindeki panelin aynısı
  formun içinde açılır — neden, "Neden / Etkisi / Ne yapmalı", son adres + yönlendirme, içerik türü, boyut, karakter
  kümesi, olası nedenler ve sayfanın görünür metninden maskeli alıntı. Kaydetmeden önce yapılandırmadaki sorun görülür.
- **Sertifika penceresinde "Değişiklikler" sekmesi:** dashboard'daki sertifika kartından açılan pencerede kaydı kimin
  eklediği / en son kimin güncellediği (tarih + saat) ve her değişikliğin kim tarafından, alan alan önce → sonra olarak
  yapıldığı listelenir (envanter değişiklik günlüğü; Envanter çekmecesindekiyle aynı görünüm). Telefonda bölüm
  seçicisinden açılır.
- **Giriş sayfası istatistiklerine sertifika izlemesi:** "Koşum (24 sa)" artık sertifika taramalarını da sayar (alt satır
  kaçının sertifika taraması olduğunu söyler); yeni "İzlenen sertifika" kutucuğu aktif sertifika sayısını, geçerli
  olanları, 30 gün içinde dolacak ve süresi dolmuş sertifikaları geçerlilik çubuğuyla gösterir.

## [20.106.0] — 2026-10-04

### Added
- **Anahtar kelime izlemede hata teşhisi:** kontrol geçmişindeki başarısız satır artık nedenini söylüyor (kelime
  bulunamadı, HTTP hata kodu, zaman aşımı, DNS, TLS, vekil, okuma sınırı …) ve açılınca "Neden / Etkisi / Ne yapmalı",
  son adres + yönlendirme, içerik türü, boyut, karakter kümesi, olası nedenler (giriş sayfası, WAF engeli, JavaScript
  ile çizilen sayfa, harf / boşluk / karakter kümesi farkı, bakım sayfası …) ve sayfanın görünür metninden maskeli bir
  alıntı gösteriyor. Kart "Sayfa okunamadı" yerine kısa nedeni yazıyor.
- **Anahtar kelime uçtan uca tanılama:** detaydan ya da başarısız kontrolden tek tıkla — DNS, bağlantı, vekil, TLS,
  istek/yanıt, yönlendirmeler, curl dökümü; vekil ve doğrudan yol karşılaştırması; anahtar kelime çözümlemesi (eşleşme
  bağlamları, farklı okumalar, ipuçları). Kontrol kaydı yazmaz, alarm üretmez; geçmiş ve denetim kaydı tutulur.
  Kullanım kılavuzu (TR/EN, PDF) güncellendi.

### Fixed
- 255 karakterden uzun bir anahtar kelime kontrol hatası kontrol kaydının yazılmasını engelliyordu; hata metni artık kırpılır.

## [20.105.0] — 2026-10-04

### Added
- **Giriş sayfasında "Kullanım istatistikleri":** sağlıklı izleme (İzleme Panosu ile aynı sayı, "/ N aktif" ve sağlık
  oranı çubuğu), son 24 saatte koşum (başarısız sayısıyla) ve alarm, 7 günlük erişilebilirlik, takım, aktif kullanıcı ve
  şu an çevrimiçi kullanıcı (son 24 saatte giriş yapan sayısıyla). Geniş ekranda tanıtım panelinde "Raporlama"
  sütununun altında, telefonda ve tablette sayfanın en altında (formun altında); her kutucuğun açıklaması dokunarak da
  açılır. Yalnız toplam sayılar — ad / alan adı yok.
- Ayarlar → Marka → "Giriş Sayfası İstatistikleri" anahtarı (`site.monitor.public-stats.usage-enabled`, yalnız global
  yönetici). Kapalıyken giriş sayfası yalnız izlenen hedef sayısını ve erişilebilirliği gösterir.

### Fixed
- Giriş sayfası ile İzleme Panosu arasındaki izleme sayısı farkı (621 / 688): giriş sayfası alarm fırtınası paydasını
  gösteriyordu, artık panonun kendi hesabını kullanıyor.
- Geniş ekranda giriş formu görünüm alanında ortalı ve sabit kalır; kısa dizüstü ekranlarda aşağı kaymaz.
- Takım kapsamlı yönetici Marka sayfasında "Varsayılanlara dön" yaptığında yalnız global yöneticinin değiştirebildiği
  anahtarlar artık gönderilmiyor (eskiden istek 403 ile düşerdi).

### Changed
- Giriş sayfası istatistik belleği 5 dakikadan 60 saniyeye indi (`PUBLIC_STATS_CACHE_MS`).

## [20.104.0] — 2026-10-04

### Added
- **7/24 izleme ekibi takımları:** Ayarlar → 7/24'te bir ya da birden çok takım "7/24 izleme ekibi" olarak işaretlenir
  (yalnız global yönetici, denetlenir, kimlerin operatör olacağı önizlenir; takım listesinde "7/24" rozeti). Bu
  takımların etkin üyeleri rol değişmeden 7/24 operatörü olur: tüm takımların alarm, olay, bakım, durum, fırtına, dokuz
  izleme türü, envanter/sertifika ve genel bakış ekranlarını SALT OKUR; her alarma arama kaydı ve not ekler. Sahiplenme,
  çözme, yeniden bildirim, düzenleme, denetim kaydı ve yönetim ekranları kapalı kalır. Takım çıkarılınca yetki en geç
  30 sn'de, yeniden giriş gerekmeden kalkar.
- **7/24 konsolu (7/24 sekmesi):** açık alarm, 7/24'e giden, aranmayan ve son bir saatte aranan sayıları; takım,
  seviye, tür, "7/24'e gidenler", arandı / aranmadı ve 1 sa / 24 sa / 7 gün süzgeçleri. Satırda kanallar, 7/24'e gidiş
  saati ve son arama; "Ara" alarmın takımının arama listesini (sıra, ad, telefon), müdürünü, eskalasyon kişilerini ve
  talimatları arama kaydı formuyla birlikte açar. Geniş ekranda tablo, telefonda kart.
- **Alarm Geçmişi:** "7/24'e gidenler" süzgeci, kart / satır / ayrıntıda "7/24 · ss:dd" rozeti ve bildirim günlüğünde
  7/24 e-postasının "7/24 ekibine iletildi" işareti — alarmı gören herkese. Kullanım kılavuzu (TR/EN, PDF) güncellendi.

## [20.103.0] — 2026-10-04

### Added
- **Fırtına push'u ↔ alarm bağı:** push fırtınaya devredilse bile alarmın geçmişinde onu kapsayan toplu fırtına push'u
  görünür. Alarm ayrıntısında "Fırtına push'u" bloğu ve zaman çizelgesi olayı ("Fırtına #12 push'u iletildi · 12 kişi ·
  14:05"); açılınca alıcılar durum rozetleriyle listelenir, fırtına numarası fırtına ayrıntısını açar. Henüz gitmediyse
  "henüz fırtına push'u gitmedi" ve sıradaki hatırlatma zamanı yazar. Bu sürümden önceki fırtınalar "tahmini" rozetiyle.
- **Push bildirimlerim:** fırtınaya devredilen alarm satırı size giden fırtına push'unu (zaman + durum) ya da neden
  gelmediğini gösterir; fırtına push'unuz kapsadığı alarmları listeler (başka takımın alarmı yalnız sayı olarak).
- **Webhook Push Gönderim Logu:** fırtına push satırı kapsadığı alarmlara, devir satırı kapsayan fırtına push'larına bağlanır.
- **Fırtına ayrıntısı:** her toplu push'un kaç kişiye iletildiği ve kaç alarmı kapsadığı listelenir.
- Yeni tablo `storm_push_coverage` (açılışta otomatik yamalanır; fırtına ya da alarm silinince temizlenir).

## [20.102.0] — 2026-10-04

### Added
- **Push saat tavanı özeti:** tavana takılan push'lar artık sessizce kaybolmaz — kişiye tek özet push'u gider ("14 bildirim
  gönderilmedi (3 kritik, 11 uyarı). Son: …"). Kişisel sessiz saat / susturma özeti erteler; kapatılmış push, pasif hesap
  ve sistem bakımı iz bırakır. Teslimat günlüğünde özet ↔ özetlenen satır bağı. İsteğe bağlı: kritik alarmlar tavandan muaf.
- **Push geçmişim (Etkinliklerim):** gelen / gelmeyen push'lar, gelmeme nedeni sade dille, KPI çipleri, 7 / 30 gün; takımın
  alarmlarına ait push kararları (ör. fırtınaya devredildi). Başka takımın alarmı görünmez.
- **Bildirim tercihlerim (Etkinliklerim):** susturma (1 sa / 4 sa / yarın 08:00; "kritikler yine gelsin"), en düşük seviye,
  izleme türleri, push dili (Türkçe / English) ve "kendime test push'u". Yönetici kullanıcı ayrıntısından etkin susturmayı
  kaldırabilir.
- **İngilizce push:** tüm push metinlerinin İngilizcesi; Ayarlar → Webhook Bildirimleri şablon düzenleyicisinde TR / EN sekmeleri.
- **Eskalasyon adımı push'u:** gecikmeli eskalasyon kişisi tek bir aktif kullanıcıyla eşleşirse adım push olarak da gider.
- **Yeni ayarlar:** `site.monitor.userpush.overflow-summary-enabled` (true), `…overflow-summary-minutes` (15),
  `…critical-bypass-cap` (false), `site.monitor.escalation.step-push-enabled` (true), `site.monitor.userpush.title.en`,
  `site.monitor.userpush.template.<anahtar>.en`.

### Changed
- **Alarm fırtınasında push yeniden varsayılan olarak fırtınaya devredilir** (kullanıcı kararı): takım tek toplu fırtına
  push'u alır. Her alarmın push'unun tek tek gitmesi için Ayarlar → Alarm Fırtınası → "Push bildirimleri fırtınaya
  devredilmesin" açılmalı (`site.monitor.storm.push-individual`, varsayılan artık `false`).

### Fixed
- Alan adı hatırlatma push'u PostgreSQL'de iz bırakmadan düşüyordu (`push_trigger` 20 → 40 karakter).
- Webhook ayarlarında mesaj / sebep tavanı ile sertifika ve "degraded" şablonları kaydedilmiyordu; "degraded" şablonu artık düzenlenebilir.
- Webhook ayarları sayfası 768 px tablette yatay kayıyordu.

## [20.101.0] — 2026-10-03

### Changed
- **⚠ Davranış — Push bildirimleri alarm fırtınasına devredilmez:** fırtına artık yalnız e-postayı (webhook ve 7/24
  postasıyla birlikte) toplar; her üye alarmın push'u (açılış, seviye artışı, günlük hatırlatma, çözüm) tek tek ve
  alarmların açılış sırasıyla gider. Toplu fırtına push'u gönderilmez. Kişi başına saatlik push tavanı, takım/kişisel
  sessiz saat, sistem bakımı ve hatırlatma ayarı aynen geçerli. Alarm zaman çizelgesi "E-posta fırtınaya devredildi"
  der; fırtına ayrıntısı toplu ve bireysel push sayılarını ayrı gösterir; Fırtına kuralları kartına "Push bildirimi"
  eklendi. Kullanım kılavuzu (TR/EN, PDF) güncellendi.
- **Yeni ayar:** `site.monitor.storm.push-individual` (`STORM_PUSH_INDIVIDUAL`, varsayılan `true`) — Ayarlar → Alarm
  Fırtınası → "Push bildirimleri fırtınaya devredilmesin". Kapatılırsa önceki davranış (toplu fırtına push'u).

## [20.100.0] — 2026-10-03

### Added
- **Giriş Yöntemleri → İstatistikler sekmesi:** kanal başına (LDAP, yerel şifre, push kodu, e-posta kodu, beni hatırla)
  giriş denemesi, başarılı / başarısız sayısı, başarı oranı ve başarısızlık nedenleri; önceki döneme göre değişimli
  KPI'lar, kodla giriş hunisi (istendi → gönderildi → doğrulandı), trend grafiği, 24 sa / 7 / 30 / 90 gün seçimi,
  kullanıcı tablosu (arama, kanal süzgeci, sıralama, CSV) ve kullanıcı başına ayrıntı paneli (son giriş olayları).
  Sistem Sağlığı → Kullanıcı/Oturum'daki giriş geçmişinde kanal etiketleri ve global yöneticiye istatistik bağlantısı.
- **Giriş Yöntemleri → Push mesajı düzenleyicisi:** TR / EN başlık ve metin, `{kod}` / `{sure}` / `{saat}` yer
  tutucuları, telefonda görünecek metnin canlı kilit ekranı önizlemesi, telefonda gösterilemeyecek karakter uyarısı ve
  "Kendime test gönder".

### Changed
- Başarılı ve başarısız giriş denetim kayıtları artık giriş kanalını (`method`) taşır. Bu sürümden önceki şifre
  girişleri istatistikte hesap kaynağına göre tahmini sınıflanır.

### Fixed
- Push ile gelen giriş kodunda koddan sonra "?" görünmesi giderildi: kod push'u da alarm push'ları gibi kanalın
  taşıyabildiği karakterlere çevriliyor ("—" → "-").

## [20.99.0] — 2026-10-03

### Added
- **Kodla girişte kişi bilgisi doğrulaması:** push ile kod isterken kayıtlı cep telefonu, e-posta ile kod isterken
  kayıtlı e-posta adresi de sorulur; kod yalnız kullanıcı adının hesabıyla eşleşirse gönderilir. Eşleşmeme ekrandan
  ayırt edilemez (her durumda aynı yanıt). Telefon her biçimde kabul edilir ("0500 000 00 00", "+90 (500) 000-00-00").
  Girilen bilgi hiçbir yerde saklanmaz ya da loglanmaz; denetimde yalnız sonuç (eşleşti / eşleşmedi) görünür.
- **Ayarlar → Giriş Yöntemleri:** "Telefon numarası da sorulsun" / "E-posta adresi de sorulsun" anahtarları (varsayılan
  açık), 15 dakikada kullanıcı başına eşleşmeyen deneme sınırı (varsayılan 5, aşılınca kod sessizce gönderilmez),
  aktif kullanıcıların kayıtlı telefon / e-posta kapsamı (%80'in altında uyarı), önizlemede yeni alanlar.

### Changed
- Push ile kod alabilmek için hesapta kayıtlı cep telefonu (AD `mobile`) gerekir; telefonu olmayan kullanıcılar
  anahtar kapatılana kadar push kodu alamaz. Kapsamı ayar sayfasından kontrol edin.

## [20.98.0] — 2026-10-03

### Added
- **Kodla giriş (push / e-posta):** giriş ekranında, ana form değişmeden "Push ile kod al" / "E-posta ile kod al";
  6 haneli tek kullanımlık kod (varsayılan 45 sn, geri sayımlı), 3 deneme, yeniden gönderme beklemesi, IP ve kullanıcı
  sınırları. Hesap varlığı sızmaz (her durumda aynı yanıt), kod hiçbir log'a, veritabanına ya da denetime yazılmaz,
  oturum şifre girişiyle aynı kurallardan geçer (tek oturum, bakım, pasif hesap). Global yöneticiler varsayılan olarak
  kodla giremez (ayardan açılır); kurulum `admin` hesabı her zaman şifreyle girer.
- **Ayarlar → Güvenlik → Giriş Yöntemleri (yalnız global yönetici):** push ile kod, e-posta ile kod ve LDAP ile giriş
  ayrı ayrı açılıp kapatılır; kanal başına süre, deneme ve sınır ayarları; giriş ekranının TR/EN önizlemesi; son kodla
  giriş etkinliği. Tüm akış denetim kaydında (kod istendi / gönderildi / doğrulama başarısız / süre doldu / kilit /
  giriş).

## [20.97.0] — 2026-10-02

### Added
- **HTTP izlemede uçtan uca tanılama:** izleme ayrıntısındaki "Uçtan uca tanıla" düğmesi (ve "Hata tanısı"
  penceresindeki "Canlı tanılama çalıştır") isteğin izlediği yolu adım adım gösterir: DNS, vekil/TCP bağlantısı,
  CONNECT tüneli, TLS (sürüm, şifre, SNI, sertifika zinciri, güven, ad eşleşmesi), gönderilen istek satırı ve başlıklar,
  dönen durum satırı, başlıklar, süre dağılımı, yönlendirme adımları, gövdenin ilk 32 KB'ı ve curl -v tarzı döküm.
  Vekil tanımlıysa öteki yol da yan yana denenir ve fark söylenir; izlemenin kendi istemcisiyle de karşılaştırılır.
  Pod, düğüm ve pod IP'si gösterilir. Rapor kopyalanabilir, JSON indirilebilir, geçmiş çalıştırmalar açılabilir.
  Gizli değerler (yetki, çerez, şifreli başlıklar, parolalar) her yerde maskelenir; gövde önizlemesi saklanmaz.
  Yalnız `diagnostics.run` yetkisi olan ve izlemenin takımını işletebilen kullanıcılar görür; dakikada en fazla 6.
  Tanılama kontrol geçmişine yazmaz, alarm açmaz; mevcut kontroller değişmedi.
- **Toplu pasife al (yalnız global yönetici):** Kullanıcılar sayfasında ölçüte göre (tüm admin olmayanlar, seçili
  takımlar, N gündür giriş yapmayanlar, hesap kaynağı, rol) önizlemeli, sayı yazarak onaylanan toplu pasife alma.
  ADMIN rolündeki tüm hesaplar, işlemi yapan kişi ve zaten pasif olanlar her zaman hariç. İşlem geçmişi tutulur ve
  "Bu işlemi geri al" yalnız o işlemle pasife alınan, sonradan değişmemiş kullanıcıları geri açar.
- **Sistem Bakım Modu (Ayarlar → Platform → Sistem Bakımı, yalnız global yönetici):** bakım penceresi planlanır
  (İstanbul saati) ya da geri sayımla hemen başlatılır; uzatılabilir, erken bitirilebilir, düzenlenebilir, iptal
  edilebilir. Bakımda yalnız global yöneticiler girebilir; diğer kullanıcılar önceden duyuru ve uyarı şeridi görür,
  son 60 saniyede kapatılamayan geri sayım penceresiyle çıkış yapar, giriş ekranında ve Durum Sayfası'nda bakım
  saatleri yazar. Kullanıcı ekranlarının TR/EN önizlemesi, sayaçlı bakım geçmişi, denetim kayıtları, isteğe bağlı
  e-posta duyurusu (düzeltme/iptal e-postası dahil) var. İsteğe bağlı "bildirimler sussun" seçeneği (varsayılan
  kapalı): susturulan her bildirim iz bırakır, bakım bitince hâlâ açık alarmlar bir kez bildirilir. Bakım bitince
  kullanıcılar "Planlı bakım tamamlandı" bildirimi alır: kapatılabilir şerit, giriş ekranı ve Durum Sayfası notu
  (bitişten sonra 60 dk) ve isteğe bağlı (varsayılan açık) "bakım tamamlandı" e-postası. Uyarı şeridi ve geri sayım
  penceresi bakımın başlangıç, bitiş ve süresini yazar.
- **Kullanıcı Düzenle yeniden (shadcn, telefonda tam ekran):** Kullanıcılar sayfası ve takım üye kartı artık aynı
  düzenleyiciyi açar; Hesap / Takımlar / Profil / Güvenlik sekmeleri, alan yanında doğrulama ve hatalı sekmede işaret,
  değişiklik özeti ("N değişiklik"), kaydedilmemiş değişiklikte kapatma onayı, yeni kullanıcıda birincil takım seçimi,
  AD kilidini alanın yanında açma (global yönetici), Güvenlik sekmesinde kilit açma, şifre sıfırlama, oturum sonlandırma,
  tur sıfırlama ve cihaz geçmişi. Mevcut tüm korumalar ve gönderilen veri aynı.
- **Çevrimiçi kullanıcı göstergesi:** sol üstte logonun yanında yeşil kullanıcı figürü ve o an çevrimiçi kişi
  sayısı; tıklanınca birincil takıma göre dağılım. Herkes görür, yalnız sayılar gösterilir, 30 saniyede bir yenilenir.

### Changed
- **⚠ Davranış — Pasif hesaplar:** pasif hesap hiçbir yoldan (yerel şifre, LDAP/AD, "beni hatırla") giriş yapamaz;
  şifre doğruysa "Hesabınız pasif" uyarısı görür. Hesap pasife alınınca açık oturumu 10 saniyelik geri sayımla kapanır.
  Pasif kullanıcıya hiçbir alarm, eskalasyon, olay ya da push bildirimi gitmez (gönderilmeyen her bildirim
  `SKIPPED: pasif kullanıcı` izi bırakır). Takım üye listelerinde pasif üyeler gizlenmez, belirgin "Pasif" rozetiyle en
  sonda gösterilir; eskalasyon kişisi listelerinde de "bildirim gitmez" notu çıkar. Pasif sahibinin haftalık rapor onay
  bağlantısı reddedilir.

### Security
- Pasif LDAP/AD hesabının AD şifresiyle giriş yapabilmesi ve pasife alınan kullanıcının açık oturumunun sürmesi kapatıldı.

## [20.96.1] — 2026-10-02

### Changed
- **Varsayılan dil İngilizce** (ürün kararı): dil seçmemiş kullanıcı arayüzü İngilizce görür, seçilen dil kalıcıdır.
  Davranış zaten buydu; karar belgelendi ve testle kilitlendi.

### Fixed
- Docker imajı açılış testi main sürümlerinde de koşuyor: Release, yayınladığı sürüm imajını yayından sonra boş bir
  PostgreSQL'e karşı prod profiliyle açıp denetler (sürümü bloklamaz). 20.96.0 imajı bu testi görmeden yayınlanmıştı.

## [20.96.0] — 2026-10-02

Uçtan uca incelemeden çıkan ve onaylanan 16 öneri. Hepsi mevcut akışı bozmayacak şekilde yapıldı: yeni davranışlar
ya yalnız ekleme ya da varsayılanı kapalı; ayar yapılmazsa bildirimler, ekranlar ve dosyalar bugünkü gibi.

### Added
- **HTTP izlemede gelişmiş istek:** özel başlıklar (şifreli, yalnız global yönetici), Basic kimlik doğrulama, POST
  gövdesi, JSON yanıt doğrulaması ve isteğe bağlı yavaş yanıt alarmı (`HTTP_SLOW`). Alanlar boşken istek ve karar
  bugünküyle bayt bayt aynı.
- **Zamana bağlı eskalasyon:** eskalasyon kişisine "N dakika sahiplenilmezse bildir" gecikmesi verilebilir; boşsa
  bugünkü gibi hemen. Ayrıca izleme türü başına yeniden uyarı sıklığı (dakika; 0 = genel ayar).
- **Alarmdan olay kaydı:** alarm ayrıntısında "Olay kaydı aç"; form alarmın bilgileriyle dolu açılır, kayıttan sonra
  bağlantı görünür. **Runbook notu:** izlemeye rehber yazılmışsa alarm e-postası ve Teams/Slack mesajının sonuna kısa
  "Ne yapılmalı" bölümü eklenir; rehber yoksa bildirim aynen.
- **Sessiz saatler:** takım bazlı sessiz saat penceresi (uyarı seviyesindeki alarmlar pencere bitince tek bir özet
  e-postasında gelir; kritik alarmlar her zaman hemen gider) ve kişi bazlı push sessiz saatleri. Ertelenen her
  bildirim alarm zaman çizelgesinde görünür.
- **Kurum içi durum sayfası** (`Durum Sayfası`): hizmetlerin anlık durumu, açık ve son çözülen olaylar, bakım
  pencereleri, 7 günlük erişilebilirlik. Giriş gerektirir; olay ve bakım ayrıntıları yalnız bugün de görülebilen
  kayıtlar için gösterilir, diğerleri yalnız sayı olarak.
- **Favori izlemeler, açılış sekmesi, kayıtlı görünümler:** kartlarda yıldız, İzleme Panosu'nda "Favoriler"
  görünümü, komut paletinde favoriler; kişisel açılış sekmesi; izleme sayfaları, İzleme Panosu, Alarm Geçmişi ve
  Olay Geçmişi'nde "Görünümler". Tarayıcıdaki tercihler sunucuda saklanır (ilk girişte taşınır), cihazlar arası
  taşınır.
- **Klavye kısayolları:** `?` kısayol listesi, `/` sayfa araması, `g` + harf ile sık sekmeler. Tanıtım turuna yeni
  ekranlar eklendi; turu bitirmiş kullanıcılar yalnız "Yenilikler" kartını görür.
- **Gerçek PostgreSQL entegrasyon testleri** (CI'da, release'i bloklayan iş) ve Docker imajı açılış testi.

### Changed
- İzleme sayfaları geri sayım için artık saniyede bir baştan çizilmiyor; ana veri yoklaması gizli sekmede duruyor.
- **Açılış paketi küçüldü** (gzip 1.666 kB → 720 kB): İngilizce sözlük, grafik kütüphanesi ve sekmeye özel ekranlar
  ayrı parçalarda. Kayıtlı dili İngilizce olan kullanıcı açılışta kısa bir yükleme ekranı görebilir.
- Göreli zaman, CSV indirme ve genişlik ölçümü için ortak yardımcılar (çıktılar birebir aynı).
- Belgeler güncellendi; eski hata raporları `docs/archive/bug-reports/` altına taşındı.

### Security
- İçerik güvenlik politikasından (CSP) satır içi betik izni kaldırıldı.
- Sentetik (k6) betiklerde dosya okuma taraması: varsayılan "Raporla" (kayıtta uyarı, açılışta özet, yönetici
  raporu); "Engelle" seçilirse dosya okuyan betik kaydedilmez ve çalıştırılmaz.

### Fixed
- Push bildirim kuyruğu satır sahiplenmesiyle çalışıyor: birden çok pod aynı bildirimi iki kez gönderemez.
- Şema yamalarının gerçek hataları artık uyarı olarak görünür ve sayılır; yamalar aynı anda tek pod'da koşar.

## [20.95.0] — 2026-10-01

### Changed
- **Haftalık erişilebilirlik e-postası gönderim logu yeniden tasarlandı (Sistem Sağlığı → Entegrasyonlar).** Eski
  işlevler duruyor: son kayıtlar (test dahil), tarih / takım / alıcılar / tür / durum ve satıra tıklayınca gönderilen
  e-postanın kendisi. Yeni: son zamanlanmış çalışmanın özeti (kaç takıma gitti, başarısız, alıcısız, durum çubuğu);
  süzgeç işlevli sayım kutuları; arama, takım ve tür süzgeçleri; 100 / 250 / 500 kayıt seçimi; günlere gruplanmış
  liste ve konu sütunu; CSV; sayfalama. Yükleme hatası artık boş liste gibi görünmüyor, "Tekrar dene" çıkıyor.
  Ayrıntı penceresinde hata nedeni bandı, alıcı çipleri ve kopyalama, masaüstü / telefon önizleme genişliği,
  bağlantıları yeni sekmede açan önizleme ve önceki / sonraki kayıt var. Telefonda kart listesi, geniş ekranda tablo.
- **Webhook push gönderim logu yeniden tasarlandı.** Tüm işlevler korunuyor (aralık, süzgeçler, arama, zaman çizelgesi,
  kırılımlar, sıralama, ayrıntı, CSV, yeniden kuyruğa alma, otomatik yenileme, URL'de süzgeç ve açık kayıt). Yeni:
  teslimat sağlığı kartı (başarı oranı, başarısız alıcı, kuyrukta, son başarılı); her etkin süzgeç ayrı çip; telefonda
  süzgeçler tek düğmenin arkasında; grafik lejantı; kırılımlar tek kartta sekmeler; dar ekranda kart listesi ve sıralama
  seçicisi, geniş ekranda sabit sütunlu tablo (uzun takım / izleme adları artık eylem sütununu dışarı itmiyor); ayrıntı
  sağdan açılan panelde: hata nedeni bandı, teslimat akışı (oluşturuldu → gönderildi, bekleme, deneme), mesaj ve ham
  yanıtı kopyalama, önceki / sonraki kayıt.
- **Alan Adı sayfasında varsayılan sıra: süresi en az kalan önce.** Sayfa ilk açıldığında kartlar kalan güne göre
  artan sıralanır: süresi geçmiş alan adları en üstte, eşit günde alan adına göre, günü bilinmeyenler en sonda. Diğer
  türlerdeki ortak sıra (sorunlu önce, gruba ve ada göre) seçicide ayrı bir seçenek olarak duruyor.
- **İzleme Panosu üst bölümleri akordiyon oldu.** Özet göstergeler (KPI kutuları), filo sağlığı, takım sağlığı ve izleme
  türleri tek kapta, her biri açılır/kapanır bir bölüm. Varsayılan olarak yalnız en üstteki özet göstergeler açık, diğerleri
  kapalı. Kabın üst şeridindeki tek düğme ("Tümünü aç" / "Tümünü kapat") hepsini birden açar ya da kapatır. Kapalı bir
  bölümün başlığı da durumu söyler: tonlu simge kutusu ve özet çipleri ("9 sorunlu", "2 takımda sorun", "1 türde sorun",
  geniş ekranda tür simgeleri tonlarıyla). Açık bölümler adres satırında tutulur (`mo_open`), sayfa yenilense de korunur.
  İçerikler daha sıkı: KPI kutuları ~56 px yüksekliğinde yatay düzende, takım satırları geniş ekranda ızgara. Telefon,
  tablet ve dizüstü boylarında taşma yok; bölüm başlıkları en az 40 px dokunma hedefi. İzleme listesi akordiyonun dışında
  kaldı, davranışı değişmedi.

## [20.94.0] — 2026-10-01

### Changed
- ⚠ Davranış — **İzleme sayfalarında kartların varsayılan sırası (dokuz tür):** önce sorunlu kartlar (kırmızı, sonra sarı:
  yavaş / bozulmuş / uyarı), sonra grup adı olan izlemeler grup adına göre A→Z (aynı gruptakiler art arda, grup içinde
  ada göre), en sonda grubu olmayanlar ada göre. Sıralama Türkçe harf düzeninde, büyük/küçük harf duyarsız, sayılar doğal
  sırada ("web-2" < "web-10"); duraklatılmış izleme sorunlu sayılmaz. Eskiden sunucu sırası kullanılıyordu (ada göre;
  Port/DNS'te bağımsız izlemeler tanımsız sırayla en sonda). Alan Adı sayfasında bu kural seçicinin yeni varsayılanı;
  kalan gün / ad / kayıt kuruluşu / takım / son değişiklik seçenekleri duruyor.

## [20.93.1] — 2026-10-01

### Fixed
- **Alarm Fırtınası sayfa açıklamasında `{0}` görünüyordu** (pencere değeri metne geçirilmiyordu). Açıklama artık
  sunucunun uyguladığı GERÇEK değerlerle kurulur: "bir takımın 5 dakikalık penceresinde 5 farklı hedef düşük olunca…"
  (yüzde biriminde "aktif izlemelerin %N oranı kadar farklı hedef (en az 3)"); veri gelmeden genel metin.

### Added
- **Alarm Fırtınası → "Fırtına kuralları" kartı:** Ayarlar → Alarm Fırtınası'ndaki geçerli değerlerin özeti — koruma
  açık/kapalı, açılma eşiği, sayım penceresi, sessiz pencere (ömür), kapanış tabanı (eşiğin yarısı, en az 2 ya da sessiz
  pencere), 24 saatte bir toplu tekrar ve kapsam (takım / takım + bildirim grubu); her satırda kısa açıklama. Ayar
  değişince kart ve açıklama da değişir. Durum ucu `settings` alanına `per_group`, `re_alert_hours`, `min_threshold`,
  `percent_min_targets` eklendi.

## [20.93.0] — 2026-10-01

### Changed
- ⚠ Davranış — **Fırtına sessiz penceresi varsayılanı 30 → 5 dk** (`site.monitor.storm.quiet-minutes`, `STORM_QUIET_MINUTES`):
  son üye katılımından 5 dk yeni alarm gelmezse fırtına mühürlenir ve kapanır; hazır değerler 5 / 15 / 30 / 60. Ayarı
  değiştirmiş kurulumlar etkilenmez.
- **Alarm Geçmişi → Gürültü analizi yeniden tasarlandı (shadcn, mobil-önce) ve çözüm önerisi motoru eklendi.** Mevcut
  her işlev korundu (katlanır panel, 7/30 gün, başlık özeti, KPI'lar, günlük eğri, en çok alarm üreten hedefler → liste
  süzgeci, gün × saat ısı haritası, flap adayları + Ayarlar, tipe göre dağılım); üstüne 14 günlük pencere, takım seçici
  (`?team=` — "Takımlarım" grubu önde; görülemeyen takım 403), gürültülü hedef / flap / sessiz kapanış / gürültü skoru
  (0–100) KPI'ları, desen rozetli "Gürültü kaynakları" listesi (satırdan Alarmları listele / Monitörü aç), takım kırılımı
  (≥ 768 px tablo, telefonda kart; fırtına sayısı `alert_storms`'tan), saat grafiği (shadcn Chart) ve sunucuda hesaplanan
  "Çözüm önerileri" geldi. Aynı uç `GET /api/admin/alerts/noise` EKLEYİCİ alanlar döner: `top[*].pattern|monitor_type|
  team_id|team_name|median_minutes|flap_count|still_open|last_opened_at`, `by_type[*].silent|monitor_type`, `teams[]`,
  `hours[]`, `noisy_targets`, `flap_targets`, `flap_alerts`, `night`, `night_pct`, `noise_score`, `team_options[]`,
  `my_team_ids`, `default_team_id`, `suggestions[]` (`{code, severity, title_key, target?, team_id?, params[], action{kind,
  tab, params}}`). Öneri kataloğu `AlertNoiseSuggestion` (FLAPPING 5+ alarm & ort ≤ 10 dk · SHORT_OUTAGES 3+ alarm & ortanca
  < 5 dk · REPEAT_SAME_TARGET 7+ alarm · SLOW_THRESHOLD_TIGHT 3+ `*_SLOW` · DUPLICATE_MONITORS aynı host'a ≥ 2 izleme türü /
  takım · SILENT_CLOSES ≥ 3 ve ≥ %20 · OFF_HOURS_NOISE ≥ 10 alarm, gece 22–06 payı ≥ %30 ve gece alarmlarının ≥ %80'i
  sahiplenilmemiş · STORM_PRONE takımda ≥ 2 fırtına); metinler arayüzde (`noise.sug.<KOD>.title|body`, TR + EN), kapı
  `AlertNoiseSuggestionI18nGateTest` kaynak dosyadan doğrular. LEVEL_TOO_LOW/HIGH veri desteklemediği için katalogda yok.
- **Performans (2026-10-01 inceleme):** her kullanıcının dakikada bir çağırdığı açık alarm rozeti (`/api/me/open-alerts`)
  3–4 sorgu + 200 tam alarm varlığı yerine tek gruplu sayım + dar ilk-N sorgusu ve 15 sn kapsam ezberiyle yanıt verir
  (alarm onay/çözümünde ezber atlanır); İzleme Panosu 30 sn, fırtına durum ekranı 10 sn ezberlenir; fırtına ekranı takım
  başına 10 COUNT yerine dakikada TEK gruplu sorgu, tetikleyen / canlı üyeler fırtına başına değil tek sorgu; gürültü
  analizi 90 güne kadar tam alarm yerine 9 sütunlu izdüşüm, haftalık rapor takım bilgisi yalnız aktif kullanıcılar + tüm
  takımların son raporu tek sorgu. Alarm yolunda fırtına açılışı üye başına 2 sorgu + commit yerine tek JDBC batch, kapanış
  tek batch; açılış anlık görüntüsü ayrı UPDATE yerine terfi INSERT'inde (arada koşan yaşam döngüsü turu alanları
  boşaltamaz), sayaç tazelemesi varlık `save` yerine hedefli UPDATE (katılımın zamanı geri sarılmaz). Yeni indeksler
  `alert_events(created_at)`, `alert_storms(created_at)`, `user_push_deliveries(dedupe_key text_pattern_ops)` (büyük
  tablolarda `CONCURRENTLY`); gereksiz `idx_asm_storm` kaldırıldı. Alarm Geçmişi'nin Takım sütunu artık takım ADINA göre
  sıralanır (eskiden kimliğe göre).
- **İzleme Panosu yeniden tasarlandı (shadcn, mobil-önce; mevcut işlevler korunarak):** filo hükmü + durum dağılım
  çubuğu, "Dikkat gerektirenler" (sorunlu / gecikmiş / hiç kontrol edilmemiş, neden satırıyla), satır başına pencere
  başarı oranı ölçeri ve yanıt süresi (son + ortalama, "Yavaş" işareti; ikisi de sıralanabilir), takım sağlığı kartı,
  hızlı görünümler (Tümü / Dikkat gerektiren / Alarmlı / Hata veren / Duraklatılmış), canlı tazelik çipi, CSV dışa aktarma;
  liste kabın genişliğine göre tablo ya da kart. Sorunlu, Kontrolü gecikmiş, Açık alarm ve Duraklatılmış kutuları özet
  penceresi açar (türe / takıma göre dağılım, eylemli liste, "Listede süz"; URL `mo_dlg`). Yenile sunucu ezberini atlar
  (`?fresh=1`, en çok 5 sn'de bir).

### Added
- **Alarm Geçmişi: hızlı dönemler, tarih alanı seçimi, sütun sıralaması ve süzgeçleri, Takım sütunu.** Araç çubuğunda
  "Tüm zamanlar · Son 1 saat · Son 24 saat · Son 7 gün · Son 30 gün · Özel" dönem seçici (URL `from=1h|24h|7d|30d`, istek
  anında göreli hesaplanır — paylaşılan bağlantı hep bugüne göre açılır; günlükler yerel gün sınırından); tarih aralığı artık
  AÇIK görünümde de uygulanır ("son 1 saatte açılanlar"). "Tarih alanı" seçici: Kapalı'da Kapanış (varsayılan) / Açılış,
  Tümü'nde Açılış (varsayılan) / Kapanış / Aktif olduğu dönem (URL `range=opened|resolved|active`). Tablo başlıkları
  sıralar (Seviye · Alarm · Takım · Açıldı · Çözüm; `aria-sort`), araç çubuğunda ve telefonda aynı seçenekli sıralama
  seçicisi; URL `sort=<anahtar>[_asc]`, sunucuya `sort` + `dir` (`GET /api/admin/alerts?sort=opened|resolved|level|team|domain|type&dir=asc|desc`,
  beyaz liste, bilinmeyen anahtar varsayılana düşer; seviye METİN saklandığı için CASE sırası CRITICAL > HIGH > WARNING,
  eşitlikte açılış en yeni önce). Başlıklarda sütun süzgeci menüleri (seviye / tür / takım / sahiplenme — araç çubuğu
  çipleriyle aynı durum). Yeni Takım sütunu (sunucu yanıtına `team_name`: damgalı takım adı) ve telefon kartında takım satırı;
  takım rozetine tıklamak takım süzgecini uygular. Boş dönemde "Bu dönemde alarm yok" + tek tıkla "Dönemi genişlet"
  (1 sa → 24 sa → 7 g → 30 g → tüm zamanlar).
- **Alarm Fırtınası sayfası** (Alarmlar menüsü, `?tab=storms`; kapı `alerts.read`, görüş kapsamı Alarm Geçmişi ile aynı —
  kullanıcı yalnız kendi takımlarını görür): takım kartlarında durum (Fırtına / Eşiğe yakın / İzleniyor / Sakin), pencere
  doluluğu (farklı hedef / eşik), kural metni, son fırtına ve 30 günlük sayı; açık fırtına özeti (mühür geri sayımı, hâlâ
  düşük hedef, kapanış tabanı, tetikleyen alarm) ve "Takımın açık alarmları"; **Geçmiş** sekmesi (takım / tarih / yalnız
  kapanmış süzgeçleri, sayfalama, kapanış nedeni rozeti); **Analiz** sekmesi (günlük fırtına sayısı takıma göre yığılı
  grafik, takım özeti, kapanış nedeni ve kök neden dağılımı, saat dağılımı); **fırtına ayrıntı penceresi** (anlık görüntü,
  zaman çizelgesi, üye listesi: katılım türü / duyuru / ayrılış ve alarma gidiş, bildirim özeti). Ayarlar → Alarm
  Fırtınası'nda aynı verinin kompakt **Canlı durum** paneli. Uçlar `GET /api/monitoring/storm/status|history|analytics|{id}`
  (`StormStatusService`). Telefon/tablet düzeni `e2e/responsive.spec.js` ile ölçülür.
- **Fırtına gözlem verisi:** fırtına satırı açılışta takım, eşik ayarı + etkin eşik, pencere, sessiz pencere, açılıştaki
  hedef sayısı, tetikleyen alarm ve ömür boyu tepe hedef sayısını dondurur; kapanışta nedeni yazar (`FLOOR` / `SEALED` /
  `DISABLED` / `LEGACY_RETIRE`). Yeni `alert_storm_members` tablosu üyeliği kalıcı tutar (katılım türü, duyuru anı,
  ayrılış türü) — kapanışta `storm_id` sıfırlansa da "kim hangi fırtınadaydı" okunur. Eski takım fırtınalarına takım
  kimliği geriye dönük yazılır; üye tablosu fırtına silinince yetim politikasıyla temizlenir.
- **Ayarlar → Haftalık Raporlar → takım görünürlüğü: PO, müdür ve son rapor:** her takım satırında artık takımın
  PO'ları (ad + mailto), Takım Yönetimi ile AYNI kuralla çözülen müdür (`TeamManagerResolver`: elle atama > AD zinciri;
  kaynak rozeti "elle" / "AD") ve en son rapor haftası (ISO hafta `2026-W39` + durum rozeti + gönderim/onay zamanı;
  hiç rapor yoksa "Hiç gönderilmedi") görünür. ≥768 px'te üç ek sütun, telefonda takımın altında etiket:değer
  satırları. `GET /api/weekly-reports/access/teams` satırına `po_users[]`, `manager_user_id` / `manager_display_name`
  / `manager_manual` / `manager_email` ve `last_report` alanları eklendi (`WeeklyReportTeamInfoService`).
- **Takım üyeleri penceresi → Üyeler sekmesi → kullanıcı detayı:** Takım Yönetimi'nde takım adına tıklayınca açılan
  pencerede her üye satırının adı artık bir düğme ve satır sonunda "Kullanıcı detayını aç" ikonu var; ikisi de o
  kullanıcının detay panelini (Kullanıcılar ekranındakiyle aynı `UserDetailPanel`) takım penceresinin ÜSTÜNDE açar.
  Escape ve scrim yalnız üstteki katmanı kapatır, takım penceresi açık kalır. Yalnız takımı yönetebilen görüntüleyici
  (ADMIN / TEAM_ADMIN) görür; kısmi üye satırı için tam kayıt mevcut arama ucundan çekilir (yeni uç yok).
- **LDAP alan kilidi arayüzü ve ucu:** yöneticinin elle düzenlediği AD kaynaklı alanlar (`locked_fields`: görünen ad,
  e-posta, sicil, ad, soyad, ünvan, telefon, departman, seviye, müdürlük, müdür) artık görünür — Kullanıcı Detayı
  başlığında "N alan kilitli" rozeti, "Kilitler ve AD istisnaları" kartında alan başına satır, düzenleme formunda AD
  alanlarının altında "Kilitli: elle düzenlendi" / "AD'den gelir — düzenlersen kilitlenir" ipucu ve "AD ile karşılaştır"
  sekmesinde alan karşılaştırma tablosu (AD ↔ uygulama değeri, Kilitli / Farklı / Aynı; telefonda kart listesi).
  Yeni uç `POST /api/admin/users/{id}/field-unlock` (`{"field": "<anahtar>"}`) kilidi kaldırır — YALNIZ global yönetici
  (kapsamlı müdür 403), denetim olayı `USER_FIELD_UNLOCK`; alan bir sonraki AD eşitlemesinde AD değerine döner.
- **Gürültü analizi — tıklanabilir grafikler:** "Günlük alarm sayısı" çubuğuna tıklayınca Alarm Geçmişi "Tümü"
  görünümünde o günün (açılış tarihi) alarmlarına süzülür; "Gün × saat yoğunluğu" hücresine tıklayınca o gün ve saatte
  (İstanbul, pencere boyunca) açılan alarmlar yan panelde listelenir (en çok 200, en yeni önce; gün / saat seçicileri ve
  önceki / sonraki saat düğmeleriyle panel kapanmadan dolaşılır; satır alarm ayrıntısını açar) —
  `GET /api/admin/alerts/noise/slot?days&dow&hour[&team]`, kapsam gürültü analiziyle aynı. Tarihsiz bozuk seri kaydı
  düşürülür (grafik çökmez); telefonda uzun alan adı artık satırı ekran dışına taşırmaz.
- **İzleme Panosu — sütun süzgeçleri ve sıralama:** tablo başlıklarında durum / tür / takım çoklu seçim (sayılar diğer
  süzgeçler etkinken kalan izleme), son kontrol (son 15 dk / 1 sa / 24 sa / daha eski / hiç), koşum (başarısız / hatasız
  / koşum yok), açık alarm (herhangi / seviye / yok) ve izleme adı süzgeci; her sütuna göre sıralama (`aria-sort`); etkin
  süzgeç çipleri; telefonda aynı süzgeçler ve sıralama "Süzgeçler" panelinde. URL `mo_*` (çoklu değerler virgüllü).

### Fixed
- **İzleme Panosu yanlış "kontrolü gecikmiş" / "sorunlu":** envanterden çıkarılmış host'un eski envanter-türevi Port/DNS
  satırları (tarama atlar, tür sayfası listelemez) aktif sayılıp son kontrolleri eski olduğu için "gecikmiş", son kontrolleri
  başarısızsa "sorunlu" görünüyordu (prod: DNS kartında 49 aktif / 3 gecikmiş, DNS sayfasında 46 izleme; aynı host'un
  bağımsız izlemesi 5 dk'da bir sağlıklı). Artık taramayla BİREBİR aynı kural (büyük/küçük harf duyarlı envanter eşleşmesi):
  bu satırlar "Duraklatılmış · Envanter pasif" sayılır, sorunlu / gecikmiş / başarı oranına girmez, açık alarm bu satıra
  bağlanmaz; aynı hedefin bağımsız ikizi varsa "Asıl kontrol" bağlantısı gösterilir. Tür kartı sayıları tür sayfasıyla eşleşir.
- **İzleme Panosu:** KPI ve tür kartı tıklaması listeyi süzüp liste başlığına kaydırır (eskiden süzgeç ekranın altında
  değişiyor, kullanıcı ne olduğunu göremiyordu). Envanteri pasif olan envanter türevi Port/DNS izlemeleri (taramanın
  atladığı, tür sayfasının listelemediği satırlar) artık "kontrolü gecikmiş" değil "duraklatılmış" sayılır ve "Envanter
  pasif" rozeti taşır; gecikmiş yalnız aktif olup kontrol edilmeyen izlemelerdir.
- **Boş alanla kazara LDAP alan kilidi:** düzenleme formu her kayıtta görünen ad / sicil alanını `''` olarak yolluyor,
  sunucu `''` ile `null`'ı "değişti" sayıp dokunulmamış alanı kilitliyordu (AD eşitlemesi o alanı bir daha yazmıyordu).
  Boş/boşluk değer karşılaştırmadan önce `null`'a indirgenir; değişmeyen alan kilitlenmez.

## [20.92.0] — 2026-09-30

### Fixed
- **Genel Bakış istatistikleri açılırken titreme:** dikey kaydırma çubuğu belirip paneli daraltınca ölçülü sütun tabanı
  yeniden hesaplanıyor, kartlar yeniden akıp çubuğu gizliyor ve döngü titreşiyordu — `html { scrollbar-gutter: stable }` +
  istatistik panelinde 8 px histerezis; `useIsMobile` artık ilk çizimde doğru değeri verir (telefonda masaüstü → mobil
  sıçraması yok, A6-D3).
- **İzleme detay penceresinde sekmeler taşıp yatay kaydırma açıyordu** (Sentetik: 7 sekme): pencere 1140 px, sekme
  listesi sarar; hiçbir genişlikte kaydırma çubuğu yok.
- **HTTP kartında "Beklenen" durum kodu her kartta:** varsayılan aralık (200-399) nötr, elle değiştirilmiş vurgulu —
  eskiden yalnız varsayılandan farklıysa çiziliyor, kullanıcı tutarsızlık sanıyordu.
- **Fırtınaya sessizce bağlanan alarmlar hiçbir kanaldan bildirilmiyordu (üretim olayı 30.09.2026, SY takımının
  sentetik test alarmları #392/#412/#413/#414).** Takımın 3 kalıcı başarısız sentetik testi fırtınayı histerezis
  tabanının üstünde tutuyor, fırtına hiç kapanmıyor ve `StormService.evaluate` takımın her yeni DOWN alarmını
  "aktif fırtınaya bağla" dalıyla yutuyordu: e-posta, kişi push'u, Teams webhook'u, 7/24 postası, bildirim günlüğü
  ve push karar satırı üretilmiyor; ekranda "0 bildirim" ve çözümde "önce bildirim gitmemişti" görünüyordu.
  - **Fırtına ömür sınırı:** son üye katılımından (`alert_storms.last_member_at`) `site.monitor.storm.quiet-minutes`
    (varsayılan 30, 5–1440; Ayarlar → Alarm Fırtınası → Sessiz Pencere) geçince fırtına MÜHÜRLENİR: yeni alarm kabul
    etmez (bireysel bildirilir) ve yaşam döngüsü onu kapatır. Kapanışta fırtına postasında duyurulmuş hâlâ-düşük
    üyeler "bildirildi" sayılır (günlük tekrar kadansı postadan sürer), sonradan katılan duyurulmamış üyeler bireysel
    ilk bildirimini alır.
  - **Her sessiz karar iz bırakır:** fırtına devri (`STORM` tetikli `SKIPPED: fırtına #N` günlük satırı +
    `SKIPPED_STORM` push kararı), sahipsiz kayıt (`SKIPPED: takım yok` + `SKIPPED_NO_TEAM`) ve alıcısız / kanalı
    kapalı e-posta (`SKIPPED: alıcı yok` / `SKIPPED: e-posta kanalı kapalı`) artık `notification_logs`'a yazılır.
    Fırtına postaları (açılış / günlük tekrar / çözüm) üye alarmların günlüğüne `STORM_INITIAL` / `STORM_REALERT` /
    `STORM_RESOLVE` tetiğiyle girer; SMTP günlüğü ve alarm penceresi bunları gösterir.
  - **Ekran:** alarm satırı ve penceresinde "Fırtına #N" rozeti; zaman çizelgesinde "Bildirim fırtınaya devredildi"
    olayı (neden metniyle); Bildirimler bölümünde atlanan e-postalar nedeniyle ("Atlandı — alıcı yok").
  - **Geriye dönük:** tek seferlik yama (`StormSuppressionBackfill`) fırtınaya bağlı olup hiç bildirim satırı
    olmayan eski alarmlara "fırtınaya devredildi (geriye dönük kayıt)" izi yazar.

- **29.09.2026 hata taraması (A1 kimlik / A2 güvenlik / A3 alarm / A5 hesap raporları — `BUG_RAPORU_2026-09-29_*.md`):**
  - Alarm yaşam döngüsü (A3): envanter silme / pasifleştirme yalnız envanterin KENDİ sahip anahtarındaki alarmı kapatır,
    aynı hedefi izleyen başka takımın bağımsız izleme alarmına dokunmaz (Y-1); paylaşılan anahtarda yabancı sahibin
    sağlıklı sonucu kurtarma kanıtı sayılmaz (O-1); DNS_FAILURE / DNS_CHANGED bağlamına izlemenin SEÇİLİ alarm seviyesi
    damgalanır — eskiden hep WARNING açılıp eskalasyon kişileri ve yönetici push'u sessizce atlanıyordu (Y-2); gönderim
    seviyesi olayın seviyesinin altına inmez (D-1); HTTP / İçerik izlemesinin SSL-bitişi ve alan adı-bitişi alt
    alarmlarında "veri yok" sağlıklı sayılmaz — geçici RDAP/TLS hatası açık alarmı kapatmaz (O-2); bakım penceresinde
    gerçekten düzelen alarmın 7/24 (NOC) "ÇÖZÜLDÜ" postası gider (O-3); çift kaynaklı DNS izlemede ikinci sahibin
    DNS_CHANGED teyit zinciri düşmez — anahtara izleme kimliği girer (O-4); bakım penceresinde görülen kenar tetikli
    değişiklik (DNS_CHANGED / DOMAINMON_CHANGED) yutulmaz, bildirimsiz olay olarak kaydedilip pencere bitince ilk
    bildirimi gönderilir (O-5, ürün kararı); yalnız Teams/Slack webhook'uyla teslim edilen alarm artık "kimseye ulaşmadı"
    görünmez — webhook teslimat sayısı ayrı sayılır (O-6); fırtına üyesi elle çözülünce de bireysel ÇÖZÜLDÜ postası
    gitmez ve elle çözüm otomatik kapanışla aynı atomik kapıdan geçer (D-3, D-4); ICMP ölçülemeyen ping ve URL'de host
    olmayan (yapılandırma hatası) sentetik sonuç ne alarm açar ne kapatır — asılı alarm sessizce kapanır (D-5, D-6).
  - Kimlik / kullanıcı yönetimi (A1): üye ekle/çıkar ve toplu işlemler kullanıcının org rolünü silmez — `orgRole` null
    "dokunma", boş dize "temizle" (Y-1); birincil takım mevcut küme içinde kaldıkça korunur, JSON sırasına bağlı değil (Y-2);
    LDAP hesabına "parola sıfırla" / geçici parola uygulanmaz, LDAP satırında yerel parola izi kalmaz (Y-3); AD kaynaklı
    profil alanları ALAN BAŞINA kilitlenebilir (`app_users.locked_fields`, idempotent yama) — yöneticinin elle değiştirdiği
    alan LDAP girişi/eşitlemesinde ezilmez (kilit örtük: yöneticinin düzenlemesiyle konur), LDAP tanı çıktısı kilitli alanları `locked_fields` ile listeler (O-1, ürün kararı);
    AD'de e-posta/ad değişince `user_id` bağlı eskalasyon kişileri de tazelenir (O-2); 7/24 postasındaki takım müdürü
    Takım Yönetimi'yle aynı kuraldan çözülür — `TeamManagerResolver` (O-3); Kullanıcılar ekranı kapsamlı roller için tüm
    görüş kapsamındaki takımların kullanıcılarını listeler (O-7); girişte LDAP üyelik budaması ayara bağlı, tanı ekranı
    aynı ayarı okur (D-1); kullanıcı silinince elle atanmış `teams.manager_id` ve astların `manager_id`'si temizlenir ve
    denetim kaydına yazılır (D-3); üyelik satırı olmayan eski birincil takım karşılaştırmada dikkate alınır (D-6).
  - Güvenlik (A2): telefon yalnız global yönetici tarafından görülebildiği için yalnız onun yazabileceği beyaz listede (D-6).
  - Hesaplamalar (A5): 24 saati aşan tekrarlayan bakım penceresi ikinci günden sonra da aktif sayılır — tek occurrence
    motoru, süre sınırı 30 gün (Y-1); hiç kontrolü olmayan alan "%100 erişilebilir" değil "—" (O-1); haftalık en çok alarm
    alan hedefler alarm olayının anahtarıyla eşleşir — adlı izlemelerde artık 0 değil (O-3); yalnız-tarih WHOIS bitişinde
    "kalan gün" günün büyük kısmında 1 eksik çıkmaz (O-5); Veritabanı / Kullanıcı analitiğinde özet penceresi seri
    kovalarıyla aynı takvimden — özet toplamı = seri toplamı (D-2); Yönetici Özeti'nde "çözülen" pencerede ÇÖZÜLEN
    alarmlardır, açılış tarihinden bağımsız (D-3); sonraki sertifika taraması cron'u her iki dalda UTC'de hesaplanır (D-5);
    haftalık MTTR / gürültü / erişilebilirlik raporlarında sessiz kapanış (izleme silindi / duraklatıldı / envanter pasif /
    tür bildirimi kapalı) "gerçek kurtarma" sayılmaz — `resolved_silently` süzgeci (D-7 / D-c11).

### Added
- **İzleme Panosu** (İzleme menüsünün ilk sırası, `?tab=monitoring`): 9 izleme türünün tek ekranda durumu — KPI şeridi
  (toplam / sağlıklı / sorunlu / kontrolü gecikmiş / duraklatılmış / açık alarm / koşum / pencerede çözülen; tıklanınca
  liste süzülür), tür kartları (aktif-duraklatılmış, sorunlu, gecikmiş, açık alarm, koşum ve başarı oranı, son kontrol,
  silinmiş; "Sayfayı aç"), izleme listesi (≥768 px tablo, telefonda kartlar; arama, tür/durum/takım süzgeçleri, sayfalama;
  satırdan izleme sayfasına ya da açık alarmlarına gidiş). Pencere 24 sa / 7 gün. Veri `GET /api/monitoring/overview`
  (`MonitoringOverviewService`: satırın takımı görüş kapsamında; durum = son kontrol + açık alarm; "gecikmiş" = aralığın
  3 katı / en az 10 dk; pencere sayımları mevcut gruplu sorgulardan).
- **Form doğrulama hataları alanın altında.** Dokuz izleme formunda (HTTP, Ping, Port, DNS, İçerik, Sayfa, Sayfa Hızı,
  Sentetik, Alan Adı) zorunlu alan (hedef, takım, grup, etiket) eksikse tost yerine hata metni ilgili alanın altında
  görünür, sayfa ilk hatalı alana kaydırılıp odaklanır; alan düzenlenince hata silinir (`useFormErrors`, `Field name`,
  `FormSection error`).
- **İzleme menüsünde aktif alarm rozetleri.** Her izleme türünün (HTTP, Ping, Port, DNS, Alan Adı, İçerik, Sayfa, Sayfa
  Hızı, Sentetik) yanında kullanıcının görüş kapsamındaki AÇIK alarm sayısı; ton en yüksek seviyeden (kritik/yüksek/uyarı).
  Rozete tıklayınca Alarm Geçmişi o türe süzülmüş açılır (`?tab=alerthistory&src=<tür>`, yeni `src` kategori süzgeci +
  çip); üzerine gelince yana açılan özet kartı seviye kırılımını, sahiplenilmemiş sayısını ve en yeni beş alarmı gösterir
  (alarma tıklayınca detay açılır). Bölüm kapalıyken / menü daraltılmışken toplam sayı başlıkta görünür. Veri
  `GET /api/me/open-alerts` (dakikada bir, sekme gizliyken durur; Alarm Geçmişi ile aynı kapsam kuralı).
- **Ortam adı Ayarlar → Genel Ayarlar'dan ayarlanabilir** (yalnız global yönetici): değer PostgreSQL'de (`app_settings`,
  anahtar `site.monitor.environment`) saklanır ve yeniden başlatmadan yansır (kaydeden pod'da hemen, diğer pod'larda ayar
  önbelleğiyle ~10 sn). Öncelik: ayar > Helm `APP_ENVIRONMENT` > otomatik (pod'da "Ortam adı yok", dışında "Yerel");
  alan boşaltılınca Helm değerine dönülür. Geçerli değer ve kaynağı ("Ayarlardan" / "Helm" / "Otomatik") rozetle
  gösterilir; dev / staging / prod hazır seçenekleri, `[a-z0-9-]` doğrulaması. Sürüm penceresi, Sürüm & Dağıtım Geçmişi
  ve Sistem Sağlığı kartı yeni adı gösterir.
- Ortam adı değişince çalışan örneğin devreye alma kaydı yeni ada taşınır (notta "Ortam adı değiştirildi" izi kalır, yeni
  satır eklenmez); Prometheus `sitemonitor_build_info` `environment` etiketi yenilenir (Grafana "yeni sürüm"
  anotasyonu bir kez tetiklenir — `docs/GRAFANA_SURUM_ANOTASYON.md`).

## [20.91.0] — 2026-09-29

### Changed
- ⚠ Davranış — **Elle kontrol alarm tetiklemez:** "Şimdi kontrol et" (tekil ve toplu, tüm izleme türleri) artık alarm
  açmaz; eskalasyon, yeniden uyarı ve alarm fırtınası tetiklemez; alan adı eşik hatırlatması göndermez ve sentetik
  izlemenin anomali korumasını (izlemeyi kapatma + KRİTİK bildirim) tetiklemez — ardışık zaman aşımı serisi yalnız
  zamanlanmış koşumları sayar. Yalnız sonucu kaydeder, sağlıklı çıkarsa açık alarmı kapatabilir; yeni alarm sonraki
  zamanlanmış kontrolün normal doğrulama kurallarıyla açılır. (Prod olayı: toplu "Şimdi Kontrol Et" hayalet açık
  alarmları tetikleyip kuruluş geneli fırtına push'u üretti.) Yeni boş bırakılabilir kolonlar `scripted_checks.manual`,
  `dns_records.manual` (idempotent şema yamasıyla; eski satırlar zamanlanmış sayılır).
- ⚠ Davranış — **Alarm fırtınası takım bazında:** eşik (sayı ya da takımın kendi etkin izlemelerinin yüzdesi), sayım,
  kök neden ve bildirim (e-posta, push, webhook) yalnız o takımın kendi izlemelerinden gelir ve yalnız o takıma gider;
  "Tüm monitörler" etiketli kuruluş geneli fırtına bildirimi kaldırıldı, açık eski fırtınalar otomatik dağıtılır. Bir
  takımın fırtınası başka takımın alarmını yutmaz. Fırtına push'u sabit KRİTİK yerine üyelerin en yüksek seviyesiyle gider
  (WARNING fırtınası müdür / bölüm başkanı / C-level kademelerine yayılmaz).
- ⚠ Davranış — **Fırtına eşiği farklı hedef (host) sayısıyla ölçülür:** aynı host'un erişim, port, DNS ve HTTP alarmları tek
  hedef sayılır; yüzde biriminde en az 3 hedef gerekir (küçük takımda tek host arızası artık fırtına sayılıp bireysel
  alarmları bastırmaz). Yükseltmeden kalan eski "Tüm monitörler" fırtınası açılıştan 15 dakika sonra
  (`site.monitor.storm.legacy-retire-grace-minutes`) takım bazında emekliye ayrılır: hâlâ düşük üyeler eşiği aşan takımda
  sessizce takım fırtınasına taşınır, aşmayanlarda "bildirildi" sayılır — üyeler tek tek yeniden bildirim üretmez; çözüm
  bildirimi yalnız sahibi takıma gider (çözüm push'u eski fırtınanın açılış push'unu almış kişilere de ulaşır; 7/24'e yeni
  "FIRTINA" açılış postası gitmez). Fırtına ayarındaki eşik birimi etiketi "Farklı hedef (host) sayısı".
- ⚠ Davranış — Elle sentetik kontroller (tekil ve toplu "Şimdi Kontrol Et" ile bunların başlattığı kurtarma denetimleri)
  k6 havuzunun son iznini kullanmaz (varsayılan havuz 2 → aynı anda 1 elle koşum; tek izinli havuzda elle kontrol yalnız
  havuz boşken çalışır); toplu elle kontrol sırasında zamanlanmış izleme aç kalmaz. Elle kontrol havuzda yer açılmasını en
  çok, ekranın sonucu beklediği süreden 5 sn kısa bekler; yer açılmazsa kontrol yürütülmez ve bu hemen ekranda söylenir
  ("başlatıldı" deyip sessizce atlanmaz). Toplu kontrolde havuz dolu olduğu için atlanan kontrol sayısı ilerleme
  penceresinde gösterilir; sentetik toplu kontrol kontrolleri tek tek koşturur.
- Toplu "Şimdi Kontrol Et (N)" sayısı ve kuyruğu yalnız çalıştırma yetkiniz olan izlemeleri içerir (sunucudan `can_check`);
  seçim penceresi elle kontrolün alarm üretmediğini söyler. Alarm Geçmişi'nde fırtına üyesi alarm "kimseye ulaşmadı"
  yerine "Fırtına bildirimine devredildi" gösterir.

### Fixed
- ⚠ Davranış — **Alan adı değişiklik alarmı yutuluyordu:** alan adının elle "Şimdi kontrol et"i ve Kayıt sekmesinin
  canlı sorgusu yazdıkları satırla zamanlanmış değişiklik tabanını ilerletiyordu (bu sürümdeki elle kontrol değişikliğiyle
  birlikte nameserver / kayıt kuruluşu / EPP / DNSSEC değişikliği hiç alarm olmuyordu). Artık elle satırlar
  (`domain_checks.manual`, boş bırakılabilir, idempotent yama) taban dışıdır; değişikliği sonraki zamanlanmış kontrol
  algılayıp alarmı açar. Kayıt sekmesi açılışta kayıtlı bilgiyi gösterir, canlı sorgu yalnız "Yenile" ile. Elle DNS ve
  alan adı kontrolleri gördükleri değişikliği yalnız ekranda gösterir; geçmişte aynı değişiklik iki kez "değişti" olarak
  görünmez. Kapsamlı yöneticiler çalıştıramadıkları izlemelerde tekil "Şimdi
  kontrol et" düğmesini de görmez; elle kontrol notu sağlıklı sonucun açık alarmı kapatabileceğini belirtir. Alan adı
  izlemesinin teyit / kurtarma yeniden kontrolleri de tabanı ilerletmez (sorgu hatası teyidi sırasında görülen değişiklik
  yutulmaz).
- ⚠ Davranış — **Aynı hedefi izleyen başka takımın düşük izlemesi** artık sizin alarmınızı açık tutmaz ve onun arızası
  için yeniden uyarı size gelmez; o izlemenin alarmı kendi takımına açılır (olay anahtarı alan adı|tür paylaşımında sahip
  ayrımı).
- "Şimdi kontrol et"e art arda basmak alarmı saniyeler içinde kapatmaz (elle sağlıklı sonuç kapanışa ancak kurtarma
  aralığıyla sayılır). Yürütülemeyen ya da veri getirmeyen kontroller (k6 havuzu dolu, düşen koşumun süresi, RDAP / WHOIS /
  kara liste hatası) artık "düzeldi" sayılmaz ve açık alarmı kapatmaz (alan adı alarmlarının kapan-aç döngüsü bitti).
- Hedef düşükken ya da ölçüm alınamadığında (port kapalı, HTTP hatası, ping yanıtsız / taban yetersiz, sayfa yüklenemedi)
  açık yavaşlık alarmları (PORT / KEYWORD / PING / PAGESPEED_SLOW) ve sayfa bütünlüğü alarmları artık "ÇÖZÜLDÜ" diye
  kapanmaz. DNS yavaşlık / beklenmeyen değer ve alan adı yeniden kontrollerinde "ölçülemedi" "düzeldi" sayılmaz; WHOIS'teki
  boş EPP listesi STATUS alarmını kapatmaz.
- **Sayfa Bütünlüğü (site tarama):** derin tarama bulgusu artık alarm açar (önceden teyitte yalnız ana sayfa yeniden
  ölçülüp "geçici" sayılıyordu); teyit ve kurtarma bulgunun kaynak sayfalarını (en çok 5) yeniden ölçer; ana sayfa
  kaynaklı alarm ana sayfa temizlenince kapanır, derin tarama kaynaklı alarmda ana sayfanın temiz olması kanıt sayılmaz.
- Bildirimleri kapalı türün açık alarmları sessizce kapanır (türün bildirimleri kapatıldığında da — ayar kaydından sonra
  arka planda; elle kapanan DNS / alan adı değişiklik alarmlarına dokunmaz); kapanış bildirimi gönderilmez. Fırtına
  çözümü susturulan üyeleri (izleme silindi / duraklatıldı / host değişti, tür bildirimleri kapatıldı) "kurtarıldı"
  saymaz; hepsi susturulduysa toplu çözüm e-postası, push, webhook ve 7/24 bildirimi gitmez. Bakım penceresinde GERÇEKTEN
  düzelen üye ise kurtarılmış sayılır ve toplu "fırtına sona erdi" çözümü gider.
- **DNS değişikliği:** bir alan adında açık DNS_CHANGED alarmı varken başka bir DNS izlemesinin gördüğü değişiklik
  yutulmaz — o izlemenin kendi takımına ayrı bildirim (e-posta / webhook) olarak gider, başka takıma gitmez; aynı takımın
  günlük yeniden uyarısı en yeni değişikliği anlatır. Teyit sırasında ad geçici olarak çözülemezse değişiklik "geçici"
  sayılıp atlanmaz.
- **Seviye sözcüğü tüm kanallarda aynı:** izleme alarm ve çözüm e-postalarında (erişim, port, DNS, içerik, ping, DNS
  değişikliği) rozet ve "Seviye" satırı olayın gerçek seviyesini yazar (UYARI / YÜKSEK / KRİTİK — sabit "KRİTİK"
  yazıyordu); e-posta konusu, 7/24 postası, push ve arayüz de aynı sözlükten ("ORTA" demiyor). Alarm fırtınası e-postası,
  webhook'u ve 7/24 fırtına postası da sabit "KRİTİK" yerine üyelerin en yüksek seviyesini yazar. UYARI seviyeli izleme
  e-postalarında uyarı kutusu kırmızı değil, seviye tonunda. Kapı testi tüm türleri üç seviyede ileti / e-posta / çözüm
  e-postası / push ve fırtına kanalları için denetler.
- Duraklatma, silme ve host değişikliği yalnız o izlemenin açtığı alarmı kapatır (aynı hedefi izleyen başka takımın açık
  alarmına dokunmaz); DNS yavaşlık / beklenmeyen / tutarsızlık / değişiklik alarmları da sahip ayrımına katılır.
- Elle DNS kontrolünün gördüğü kayıt değişikliği, zamanlanmış kontrolün DNS_CHANGED alarmını kalıcı olarak yutuyordu
  (sweep değişiklik tabanı artık son zamanlanmış başarılı kayıt). Fırtına ayarları, yardım metinleri ve beyaz kâğıt
  (TR/EN, PDF) takım yalıtımını ve elle kontrol kuralını anlatır.
- ⚠ Davranış — **İzleme sağlıklıya döndüğü hâlde açık kalan ("hayalet") alarmlar:** toplu kesinti bastırması (turdaki
  hedeflerin ≥ %50'si ve en az 3'ü ağ hatasıyla düşükse) yalnız yeni alarmı değil KURTARMAYI da kesiyordu; türün
  bildirimleri kapalıyken açık alarmlar donuyordu; bellekteki kurtarma zinciri takılırsa sonraki her sağlıklı tur
  "zaten sürüyor" deyip atlıyordu (en az v20.0.0'dan beri). Artık bastırma ve kapalı bildirim yalnız yeni alarmı,
  teyidi ve yeniden uyarıyı durdurur; her sağlıklı kontrol ardışık sayaca girer ve N ardışık sağlıklı tur alarmı kapatır
  (atomik, idempotent; takılı zincir bekçisi); aynı host / adı paylaşan başka izleme hâlâ düşükse alarm açık kalır.
  Birikmiş hayalet alarmlar yükseltmeden sonraki ilk sağlıklı turlarda normal çözüm yoluyla kapanır (çözüm e-postası
  takıma, çözüm push'u yalnız açılış push'unu almış kişilere). Sertifika alarmları ağ kesintisi şüphesinde ve sertifika
  bildirimleri kapalıyken de kapanır. (Kapanış anı `resolved_at` olarak yazıldığından o döneme ait alarm tabanlı
  erişilebilirlik geriye dönük düzelmez.)
- İzleme alarm iletileri seviyeyi sabit yazıyordu (UYARI rozetli alarmda "KRİTİK: … portuna erişilemiyor"); ileti artık
  alarmın gerçek seviyesiyle başlar (UYARI / YÜKSEK / KRİTİK). Alan adı süre-bitişi UYARI iletisi "ORTA:" yerine "UYARI:".
- Alarm Geçmişi → "Neden hâlâ açık?" paneli artık kapanış kuralını da gösterir ("kontroller düzelince kendiliğinden
  kapanır" / "yalnız elle kapanır" — DNS / alan adı değişikliği); "kimseye ulaşmadı" çipi açık kalma nedeni gibi okunmaz.

## [20.90.0] — 2026-09-28

### Added
- **Alarm Geçmişi — tarih aralığı kipi:** "Tümü" görünümünde tarih süzgecine "Aralıkta açılanlar" (varsayılan) ya da
  "Aralıkta aktif olanlar" (aralığın herhangi bir anında açık olan alarmlar, `range=active`) seçimi eklendi. Etkin kip
  süzgeç çipiyle görünür, × ile varsayılana döner; tür sayaçları, yüzey sayıları ve CSV dışa aktarımı aynı kipi uygular.
- **Uyarılar sayfasında 7/24 göstergesi:** her sertifika satırında (tablo, telefon kartı, Kartlar görünümü) Genel Bakış
  kartıyla aynı gösterge (açık / açık · iletilmiyor / kapalı). Düzenleme yetkisi olan "7/24 ayarını düzenle" ile envanter
  formunu 7/24 alanında açar; diğerleri 7/24 Kapsamı'na gider.
- **Sertifika penceresinin başlığında 7/24 göstergesi:** izleme detay pencereleriyle aynı yer ve davranış; kayıt
  kaydedilince hemen güncellenir. Salt okunur pencerede yalnız Kapsam bağlantısı.
- Sertifikalarda 7/24 açıklaması artık gerçek alıcı grupları yazar (`noc_group_ids` sertifika listesinde, uyarı
  satırlarında ve pencerede; ek veritabanı sorgusu yok).

### Changed
- **Sistem Sağlığı — Veritabanı Analitiği yeniden tasarlandı (shadcn, telefon + tablet):** iki gruplu tıklanır özet
  kutucukları ("şu an": DB boyutu, bağlantı doluluğu, önbellek isabeti, ölü satır; "pencere": sorgu, başarısız, ort./p95,
  en yavaş), adet ve süreyi ortak zaman ekseninde gösteren sorgu yükü grafiği (seri aç/kapa, 200 ms eşik, tablo
  görünümü), "Bağlantılar ve sağlık" kartı (durum dağılımı, uzun süren sorgu / işlem içinde boşta / kilit bekleme
  uyarıları, uygulama havuzu), tablo ayrıntısı (boyut, tarama, son VACUUM/ANALYZE, bakım önerisi); 1024 px altında kart
  düzenli listeler. pg_stat_statements kapalıysa etkinleştirme adımları kopyalanabilir komutlarla gösterilir; okunamayan
  kaynak "Bilinmiyor" yazar (yeşil ya da sıfır değil). "Güncellendi" artık sunucunun veriyi hesapladığı an. Uçtaki
  veritabanı sorgu sayısı azaldı (pgss yokken 11 → 7), N+1 yok; yenilemede sekme, arama, sıralama ve sayfa korunur.
- **Sistem Sağlığı — HTTP istekleri yeniden tasarlandı (shadcn, telefon + tablet):** istek hızı, hata oranı, p95/p99
  ve en yavaş uç kutucukları; eşik rozetli hacim / süre / hata grafikleri; "en çok hata veren" ve "en yavaş (p95)" uç
  listeleri — uca dokununca İstek Gezgini o uca odaklı açılır.
- **İstek Gezgini:** yöntem ve durum sınıfı (2xx–5xx) süzgeçleri (telefonda alt panel, etkin süzgeç çipleri), durum
  kodu dağılımı, ortak zaman eksenli hacim + süre grafiği, sıralanır / aranır / sayfalanır uç tablosu (telefonda kart)
  ve CSV, uç ayrıntı paneli, canlı yenileme; yenilemede süzgeç, sıralama ve seçim korunur. HTTP metrikleri artık durum
  kodlarını da kaydeder (`http_metric_minute.status_codes`, boş bırakılabilir kolon — eski satırlar "sınıfsız" sayılır);
  yeni `/api/admin/system/http-metrics/overview` ucu tek akış taramasıyla çalışır (sabit bellek, 31 gün tavanı); uç
  adlarında sorgu dizesi, kimlik, belirteç ve e-posta parçaları şablona çevrilir.
- **Sistem Sağlığı — Kullanıcı Dizini yeniden tasarlandı (shadcn, telefon + tablet):** tıklanabilir özet kutucukları
  (toplam, çevrimiçi, son 7 gün, kilitli/pasif, LDAP/Yerel, turu tamamlayan), faset süzgeçleri (hesap, rol, takım,
  kimlik kaynağı, tur, son giriş) ve kaldırılabilir süzgeç çipleri, sıralanabilir tablo; telefonda ve tablette kart
  listesi ve süzgeç paneli. Yeni kullanıcı ayrıntı paneli: oturum ve zaman aşımı, bağlantı (IP, konum, tarayıcı,
  cihaz — yetkiye göre), hesap ve giriş geçmişi, yetkiye bağlı yönetici işlemleri. 30 sn'lik yenilemede süzgeç, sayfa
  ve açık ayrıntı korunur; CSV görünen (süzgeçlenmiş, sıralanmış) listeyi indirir; arama Türkçe harf ve aksan duyarsız.
- **Sertifika penceresi — "Sertifika Detayları" sekmesi yeniden tasarlandı (shadcn, telefon + tablet):** tepede son
  kontrol hatası; kalan gün ve geçerlilik zaman çizelgesi özeti (bugün işaretli, kullanılan süre yüzdesi); sertifika
  otoritesi / anahtar / alternatif adlar / güven ve zincir karoları; Kimlik · Geçerlilik · Anahtar · Güvenlik · Altyapı ·
  SAN bölümleri. Seri no ve SHA-256 parmak izi okunur gruplarla; DN, seri no, parmak izi ve OCSP/CRL tek dokunuşla
  kopyalanır (kopya ham değer). Anahtar kullanımları anlaşılır adlarla; OCSP/CRL yalnız http(s) ise bağlantı. "N/A"
  yerine soluk "—", boş bölümler gizli. Çok adlı sertifikalarda SAN listesi aranabilir (ilk 12 + Tümünü göster / Daralt,
  tümünü kopyala; alan adıyla eşleşen ad önde ve vurgulu, joker adlar işaretli).
- Sertifika Detayları'nda **TLS sürümü ve şifre takımı** gösterilir; eski protokol (TLS 1.0/1.1, SSL) ya da zayıf şifrede
  Zayıf Algoritma Raporu ile aynı kurala dayanan uyarı rozeti. Alanlar yalnız pencerenin geçmiş ucunda (`/api/history`)
  döner — sertifika listesi yükü değişmedi.
- **Sertifika penceresi — "Kontrol Geçmişi" sekmesi yeniden tasarlandı (shadcn, telefon + tablet):** SSL özet
  kutucukları (kontrol, başarısız — süzgeç; başarı oranı, kalan gün, aralıktaki yenileme sayısı, son hata), yenileme
  anları işaretli kalan gün eğilimi ve "yenileme anına git". Satırlarda durum rozeti, önceki kontrole göre kalan gün
  değişimi ("Yenilendi +N" / "Yeni sertifika") ve tam hata metni ile o kontrolün sertifika alanlarını açan "Ayrıntı"
  paneli; telefonda kart, tablette ve masaüstünde tablo. Liste yüklenemezse kutucuklar "aralıkta hata yok" demez.
- Kontrol Geçmişi (tüm izleme türleri): dokunmatik cihazlarda aralık düğmeleri ve "Yeniden dene" 40 px dokunma hedefinde.
- Envanter çekmecesindeki "Kontroller" sekmesi ve Uptime detayının SSL geçmişi de sertifika penceresinin zengin Kontrol
  Geçmişi'ni (kutucuklar, kalan gün eğilimi, satır ayrıntısı) kullanır; dar sütunda kart listesine geçer.
- **Dokunmatik ekranlarda (tablet dâhil) dokunma hedefleri 40 px:** sayfalama okları ve "Sayfaya git" kutusu (artık
  ekran genişliğine değil giriş türüne bağlı — tablette 24/32 px'ti), tarih / zaman aralığı seçici tetikleri ve tarih
  penceresinin içi (takvim günleri, ay okları, hafta numaraları, saat alanı, Tamam / Uygula, kısayollar, hızlı aralıklar —
  takvim 360 px telefona sığar). Uptime detayındaki HTTP geçmişi dar sütunda yatay kaymak yerine kart listesi. Yoğunluk
  şeridi ve kesinti çizelgesi dokununca hatalı dilimleri / alarmları 40 px'lik satırlar olarak listeler; başlıktaki uyarı
  sayısının açıklaması dokununca açılır.
- **Sertifika penceresi — "Envanter Bilgileri" sekmesi yeniden tasarlandı (shadcn, telefon + tablet):** özet kartı
  (kritiklik, durum, platform, grup, takım ve UG takımı rozetleri, sorumlu sayısı, son güncelleme, eksik alan uyarısı),
  bölüm kartları (Uygulama, Sorumlu Ekipler, Altyapı ve izleme, Sertifika ve yenileme, Operasyonel bilgiler, Notlar),
  e-posta ve alan adı kopyalama, "Envanterde aç" ve (yetki varsa) "Kaydı düzenle". Envanter sayfası çekmecesinin
  "Genel bakış" sekmesi aynı gövdeyi kullanır; platform adı artık katalogdan gelir.
- **Genel Bakış süzgeçleri yeniden tasarlandı (shadcn):** geniş ekranda etiketli süzgeç hapları (Platform, Durum, Kalan
  süre, Takım, Grup, Etiket) ve sağda Sıralama; etkin süzgeçler kaldırılabilir çipler olarak görünür, sonuç sayısı başlıkta
  canlı güncellenir. Telefonda süzgeçler "Süzgeçler (N)" düğmesiyle alttan açılan pencerede toplanır ("Temizle" /
  "Uygula (N sertifika)"); arama tam genişlikte, SSL Checker araç çubuğunun sonunda. Süzgeç anlamları ve URL anahtarları
  değişmedi.
- Alarm Geçmişi süzgeç çiplerinin dokunmatik ekranlardaki dokunma hedefi büyütüldü (çip 44 px, × 40 px).

### Fixed
- **Göreli günler bir gün kayıyordu:** envanter ve sertifika ekranlarında yalnız tarih içeren değerler (planlanan yenileme,
  alan adı bitişi) UTC gece yarısına göre sayılıyordu — bugüne planlı yenileme "1 gün önce", bitiş günü kırmızı "1 gün önce
  doldu" görünüyor, "≤ N gün" süzgeci ve 30 gün eşiği bir gün kayıyordu. Artık yerel takvim günü sayılır; İngilizcede
  "1 day" tekil (envanter, istatistik, vade takvimi, alan adları paneli).
- **Sertifika penceresindeki bağlantılar sayfayı pencerenin ARKASINDA açıyordu:** "7/24 Kapsamı'nda gör", kesinti
  çizelgesi, alarm olay kartı ve takım üyeleri bağlantıları artık pencereyi kapatıp hedef ekranı açar ("Envanterde aç"
  formun arkasında da kalmaz). Derin bağlantıyla / elle açılan pencere dış gezinmede kapanmaz (değişmedi).
- **"Alarm geçmişinde aç" bağlantıları hedef alarmı açmıyordu** (yanlış URL anahtarı; kapalı alarm için görünüm yoktu —
  2026-09-12'den beri): kesinti çizelgesi, kontrol geçmişi, Bugün paneli, sertifika kartı ve SMTP / Push gönderim
  günlüklerindeki 9 bağlantı artık doğru alarmı açık / kapalı görünümde seçili açar; listede olmayan kayıt tekil uçtan
  getirilir.
- "Envanterde aç" / çekmecenin "Tüm Sertifikalar" bağlantısı / Takım Yönetimi → Alarm Geçmişi, Genel Bakış aramasını ya
  da takım süzgecini süzülü bırakmaz; Envanter sayfası zaten açıkken de kaydın panelini açar.
- Uptime detayında sahte "saklama süresi nedeniyle kırpıldı" bandı kalktı; başarı oranları Türkçe biçimde (%97,50);
  "Özel" aralık seçilip uygulanmadan bırakılınca sonsuz dönen gösterge kalktı; salt okunur pencerede boş geçmiş
  açıklaması olmayan "Çalıştır" düğmesine yönlendirmez; telefon süzgeç çekmecelerinin örtüsü yardım / tur öğelerini de
  karartır.
- Envanter aktarımı: silinmiş kayda düz takım / UG aktarımı reddedilir (409); olmayan UG takımına aktarım 400. Mükerrer
  bandındaki "Geri yükle ve aktar" tek istekte yapılır (`/transfer` isteğe bağlı `restore: true`).
- Uptime sayfası ve detay penceresi, saniyelik geri sayım yüzünden geçmiş ve grafik ağacını her saniye yeniden çizmez.
- ⚠ Davranış — **Eskalasyon: alarmlar başka takımların müdürlerine / eskalasyon kişilerine gidiyordu (takımlar arası
  sızıntı):** takımın o seviyede eskalasyon kişisi yoksa "takımsız genel kişilere düş" yolu takım süzgeçsiz sorgu
  kullanıyor ve TÜM takımların etkin kişilerini ekliyordu (ilk bildirim, eskalasyon, günlük yeniden uyarı, çözüm,
  "Tekrar bildir", fırtına postası ve kişi webhook'ları; v3.0.0'dan beri). Artık eskalasyon kişileri YALNIZ alarmın sahibi
  takımlarından gelir: SY ve UG takımlarının her biri yalnız KENDİ kişilerini ekler (aynı adrese tek e-posta), yalnız UG'li
  kayıtta UG'nin kişileri gelir; uygun kişi yoksa alarm yalnız takımın kendi alıcılarına (takım adresi, bildirim grubu,
  push) gider — genel yedek yok. Fırtına postasında da her takımın dağıtımına yalnız kendi kişileri girer (SY'nin kişileri
  UG postasına da ekleniyordu). "Kim alır?" SY ve UG'yi ayrı gösterir, kişisi olmayan takımı adıyla belirtir. Kanıt ve
  etki için prod'da salt okunur tanı rehberi hazırlandı.
- ⚠ Davranış — **Envanterden türeyen Port/DNS izlemelerinin alarmları** artık sertifika alarmı gibi SY ve UG takımına gider
  (her takımın kendi kişileriyle; takım alan adı → envanter üzerinden canlı çözülür). Bağımsız eklenen Port/DNS izlemeleri
  değişmedi. Bu sürümden önce açılmış türev alarmların çözümü yalnız SY'ye gider.
- ⚠ Davranış — **"Taşı ve sil" açık alarmları da taşır:** takım taşınıp silinirken AÇIK alarmları da hedef takıma geçer;
  açık alarmı olan takım taşınmadan silinemez. Eskiden taşınıp silinen takımın açık alarmlarının yeniden uyarı, eskalasyon
  ve çözüm bildirimleri hiçbir alıcıya ulaşmıyordu. Kapanmış alarmların geçmişi değişmez.
- ⚠ Davranış — Bu sürümden önce açılmış envanter türevli Port/DNS alarmları ilk açılışta yeni yönlendirmeye alınır (SY +
  UG, her takım kendi kişisi). Takımı boş bağımsız DNS izlemesinin değişiklik alarmı başka takıma gitmez. SY aktarımından
  sonra UG takımı "ÇÖZÜLDÜ" bildirimini de alır. SY ve UG kişileri aynı Teams/Slack kanalını kullanıyorsa tek mesaj gider.
- ⚠ Davranış — Olmayan (silinmiş) bir takıma envanter kaydı ekleme, kaydın takımını değiştirme ve izleme oluşturma
  reddedilir (400). İçe aktarmada tanınmayan UG takım adı satır hatası verir (eskiden sessizce yok sayılıyordu).
- ⚠ Davranış — **Bağımsız izlemelerin alarmları yalnız kendi takımına:** bağımsız eklenen izlemelerin (HTTP, Keyword,
  Ping, Sayfa, Senaryo, Sayfa Hızı, Alan Adı ve kullanıcının eklediği Port/DNS) açılış, günlük yeniden uyarı, eskalasyon,
  çözüm, "Tekrar bildir" ve fırtına bildirimleri takımı artık hiçbir yolda envanterden almaz. Eskiden bağımsız DNS
  izlemesinin değişiklik alarmının günlük tekrarı, host başka takımın envanterindeyse o takımın UG adresine ve eskalasyon
  kişilerine de gidebiliyordu.
- ⚠ Davranış — **Sahipsiz kayıtlar ve takım bütünlüğü:** takımı olmayan kayıtların alarmı hiçbir kanaldan bildirim
  üretmez (olay kaydı durur, günlüğe WARN). Takımsız eskalasyon kişisi eklenemez (400; ekleme formu takımı artık önceden
  seçmez — eskiden ilk takıma yazıyordu), mevcutlar "Takıma atanmamış — bildirim almaz" rozetiyle görünür. Takımsız
  envanter aktarımı reddedilir (400); SY ya da UG olarak bağlı kaydı / izlemesi olan takım silinemez (409 — önce
  taşıyın; silme etkisi penceresi UG kayıtlarını da listeler) ve takım taşıma UG bağını da taşır (eskiden UG bağı silinen
  takımda kalıyordu). Olmayan takıma aktarım, toplu takım, izleme taşıma ve kişi yazma reddedilir (400). İçe aktarmada
  takımı değişen alan adının türev Port/DNS izlemeleri de yeni takıma geçer (alarm eski takıma gidiyordu).
- **Envanter toplu işlemleri İzleme Değişiklikleri'ne yazmıyordu:** toplu silme ve diğer toplu işlemler (etkinleştir /
  pasifleştir, sorumlu ekip, kademe, takım) artık tekil işlemle aynı geçmiş satırını "toplu …" notuyla yazar; toplu
  silinen kayıtlar "silinmiş" rozetini alır. Tekil takım aktarımı ve UG takımı aktarımı da geçmişe düşer; UG takımı
  değişikliği (içe aktarmayla gelenler dâhil — önceden ham "ugTeamId" + sayı görünüyordu) "UG takımı" etiketi ve takım
  adıyla gösterilir. (Bu sürümden önceki toplu silmeler / aktarımlar için geriye dönük doldurma yok.)
- **Envanter Bilgileri bildirim grubunu numarayla gösteriyordu:** artık adıyla (yalnız görme yetkisi olan, kaydın takımına
  ait etkin grup; tek toplu sorgu). Bulunamazsa numara + açıklama.
- Tarih alanlarındaki temizle (×) düğmesinin dokunmatik dokunma alanı 40×40 px oldu; masaüstünde görünüm değişmedi.
- Telefonda kesinti çizelgesinin sağ ucundaki kesinti çubuğun ~1 px dışına taşıyordu; ekranın o kenarına yapılan dokunuş
  yanlışlıkla Alarm Geçmişi'ni açıyordu.
- ⚠ Davranış (güvenlik) — **Sistem Sağlığı → Kullanıcı / Oturum: kimlik izi yetkisiz kademelere açıktı:** maske yalnız
  dört üst düzey listeye bakıyordu; en çok giriş kaynakları (IP, ters DNS, kuruluş, o IP'nin kullanıcıları), KPI
  ayrıntıları, anomaliler, ısı haritası hücreleri ve zaman çizelgesi ile Kullanıcı Dizini'ndeki son / önceki / başarısız
  giriş IP'leri süzülmüyordu. Artık yükün TAMAMI özyinelemeli maskelenir: giriş IP'si, konum, kuruluş, ters DNS ve
  tarayıcı yalnız global yönetici ve denetçiye (AUDIT) — ve kişinin kendi kaydında — döner; diğer kademeler açık bir
  "Gizli" durumu görür, giriş kaynakları tablosu gönderilmez. Kapı testi gerçek servis yükünde hiçbir IP / tarayıcı izi
  kalmadığını doğrular.
- ⚠ Davranış (güvenlik) — **Değişiklik geçmişlerinde eylemi yapanın IP'si ve tarayıcısı** (Yönetim Paneli takım /
  eskalasyon kişisi geçmişi, bildirim grupları, saklama süreleri, İzleme Değişiklikleri ayrıntı / zaman çizelgesi / CSV)
  ve giriş sorunu bildirimlerinin teknik ayrıntıları artık yalnız global yönetici ve denetçiye döner (kişinin kendi kaydı
  hariç); diğer kademeler "Gizli" görür. Denetim Kaydı ekranları 2026-09-25 kullanıcı kararıyla ekip kapsamında tam
  ayrıntılı kalır (değişmedi).
- ⚠ Davranış (güvenlik) — **Veritabanı Analitiği: SQL Oyun Alanı sorgu metni, hata iletisi ve çalıştıran kullanıcı adı**
  (pg_stat_statements metinleri dâhil) her kademeye açıktı (Oyun Alanı'nın kendi geçmişi yalnız global yöneticiye açık);
  artık yalnız global yönetici ve denetçiye gösterilir. Sayılar, süreler ve hata türü / SQLSTATE herkese açık kalır.
- Kullanıcı Dizini fotoğrafı olmayan her kişi için boşuna fotoğraf isteği atmaz (`has_photo`).
- İstek Gezgini: dakikalık görünüm en çok 48 saatle sınırlı (üstü saatlik); uç ayrıntısı ve `/series` 31 güne kırpılan
  aralığı kullanır; en yavaş / en çok hata veren uç listesi hata anında iş parçacığı biriktirmez (tek pod kesinti riski);
  "Diğer" durum sınıfı grafikte görünür. `http_metric_minute.status_codes` kolonu açık, idempotent şema yamasıyla eklenir;
  metrik yazım hatası artık loglanır.
- Zaman aralığı seçici ters aralığı (başlangıç > bitiş) uygulamaz; HTTP ve Veritabanı Yenile düğmeleri asılı istekte
  kilitlenmez; Veritabanı ekranlarında yüzdeler yerel ondalıkla ("%99,5"); Kullanıcı Dizini'nde eşzamanlı işlemler
  birbirinin göstergesini silmez.
- **Sertifika penceresi — güven durumu hiç görünmüyordu:** pencerenin veri ucu (`/api/history/{domain}`) kontrol
  satırının güven durumunu (`trust_status`) taşımıyordu; bu yüzden güvenilmeyen CA bayrağı (`UNTRUSTED_CA`) da
  pencerede hiç çıkmıyordu.
- **Sertifika penceresi — süresi dolmuş sertifika başlıkta "Hata" yazıyordu:** artık "Süresi doldu".
- **Envanter Bilgileri — yükleme hatası "kayıt yok" görünüyordu:** artık hata iletisi ve "Tekrar dene" gösterilir.
  Açıklama ve notlardaki bağlantılar yalnız http(s) ise bağlantıya dönüşür.
- **Haftalık Erişilebilirlik e-postası — önceki haftadan devreden alarmlar bağlantıda görünmüyordu:** "Alarm Geçmişi"
  bağlantısı artık "Bu aralıkta aktif olanlar" kipinde açılır; listelenen alarmlar e-postadaki sayıyla birebir
  örtüşür. "+N alarm daha" notu yalnız gerçekten var olanı (ek PDF / bağlantı) söyler ve devreden alarm sayısını yazar.
- **İzleme Değişiklikleri — geri yüklenen izleme "silinmiş" görünüyordu:** silinip geri yüklenen (ya da aynı kimlikle
  yeniden oluşturulan / sürdürülen) izleme zaman çizelgesinde, "En çok değişen izlemeler" kartında ve CSV'de "silinmiş"
  rozeti taşıyordu. Hüküm artık kaynağın EN SON geçmiş olayına göre verilir (geriye dönük doldurulmuş eski olaylar
  zamanlarına göre sıralanır). Doğrudan bağlantıyla (`ch_id`) açılan ayrıntı da aynı kuralla "silinmiş" rozetini gösterir
  ve ölü "İzlemeye git" bağlantısı çizmez.

## [20.89.0] — 2026-09-28

### Changed
- ⚠ Davranış — **Takım üyeleri penceresinde fotoğraf ve sistem rolü yeniden görünür** (kullanıcı kararı): üye
  satırlarında, Takım Müdürü / Lideri kartlarında ve 7/24 arama listesinde kişinin AD fotoğrafı (yoksa baş harf),
  üye satırında sistem rolü rozeti. Kurum geneli takım rehberi ucu (`/api/teams/{id}/members`) `system_role` ve
  `has_photo` alanlarını döndürür (fotoğrafı olmayan kişi için boşuna istek atılmaz). Telefon ve sicil numarası
  görünmemeye devam eder.
- **Genel Bakış domain arama alanı genişledi:** geniş ekranda 26rem, tablette 20rem; telefonda tam satır ve 40 px
  dokunma yüksekliği — uzun alan adları kırpılmadan yazılır.
- **Olay & Hata Geçmişi yeniden tasarlandı:** başlık + tıklanabilir özet kutuları (açık / aktif, dönem toplamı, MTTR,
  kritik), süzgeç çipleri ve adrese yazılan süzgeçler (`ih_*`), telefonda ve tablette süzgeç çekmecesi ile kart listesi,
  masaüstünde zengin tablo; iskelet yalnız ilk yüklemede, sonrasında titremesiz geçiş. Günlük trend 7 / 30 / 60 / 90
  gün aralığıyla; bir güne tıklamak listeyi süzer. Olay ayrıntısı önce okunur bir yan panelde açılır (zaman çizelgesi,
  etki, kodlar, işlenmiş kök neden / çözüm; derin bağlantı `ih_id`). Oluştur / düzenle penceresi beş bölüme ayrıldı ve
  boyutu sabit; satır içi doğrulama (ilk hatalı bölüme atlar), Ctrl/⌘+Enter ile kaydetme, kaydedilmemiş değişiklikte
  vazgeç onayı, katlanır e-posta önizlemesi; kayıt hatası formun içinde gösterilir, form kaybolmaz.
- **Olay trendine MTTR:** çözülmüş olayların ortalama süresi ve örneklem sayısı (`mttr_minutes` / `mttr_sample`).
- **Her kartta 7/24 göstergesi:** 9 izleme türünün kartında (Kompakt ve Zengin) ve Genel Bakış sertifika kartında
  izlemenin 7/24 ekibine iletilip iletilmediği görünür — "7/24 açık", "7/24 açık · iletilmiyor" (tür Ayarlar'da kapalı
  ya da kullanılabilir 7/24 grubu yok) ve "7/24 kapalı". Dokununca açılan açıklamada neden, asgari seviye ve alıcı
  gruplar; yetkiliye "7/24 ayarını düzenle" (form doğrudan 7/24 anahtarında açılır), diğerlerine "7/24 Kapsamı'nda gör".
  Detay penceresi başlığı aynı göstergeyi kullanır (eski yalnız-Zengin "7/24" rozeti kaldırıldı). Durum sayfa başına tek
  istekle gelir; okunamazsa yanlış "iletilmiyor" gösterilmez.
- **Aylık Sertifika Envanteri e-postası yeniden tasarlandı:** tek satırlık durum özeti (gelen kutusu önizlemesi) ve
  durum rozeti; 30 / 14 / 7 gün, süresi dolmuş, hata, veri yok ve hijyen özet kutuları (geçen aya göre değişimle); kalan
  süre dağılım çubuğu; aciliyet rozetli "Önümüzdeki 30 gün" tablosu (her alan adı sertifika penceresini doğrudan açar);
  önerili hijyen kartları; takım ve sağlayıcı kırılımı; eklerin ne içerdiği. Telefonda tam uyumlu, Outlook-güvenli;
  e-posta 95 KB'ı aşarsa listeleri kendiliğinden kısaltır (Gmail kırpmaz). Konu satırı süresi dolmuş ve 30 gün içinde
  bitecek sayılarını da içerir.
- ⚠ Davranış — **Aylık raporun PDF eki artık yalnız özet:** özet kutuları, dağılım çubuğu, 90 gün içinde bitenler ve
  hijyen bulgularının TAMAMI (e-postadaki "+N daha" listeleri ekte); her sayfanın altında rapor ayı ve sayfa numarası.
  Kayıt bazındaki detay sayfaları eke girmez (kullanıcı kararı — büyük envanterde ek ~146 sayfaya çıkıyordu); kayıt
  bazında tam liste CSV ekinde. Ekrandaki "Dışa Aktar → PDF" değişmedi.
- **Anahtar Çözümleme yeniden tasarlandı:** riskleri ve güvenceleri anlatan başlık (anahtar yalnız çözme için sunucuya
  gider, saklanmaz; değerler yalnız bu sekmenin belleğinde); göster / gizle, temizle ve uzunluk ipuçlu anahtar kartı;
  sonuç satır içinde (eşleşiyor / yanlış anahtar ve nedeni / kısmi / doğrulanacak değer yok / sunucuya ulaşılamadı).
  Şifreli değerler telefonda kartlarda — ad, kullanıldığı yer, kayıt ve çözüm rozetleri; gizli değer sayfada hiç
  tutulmaz, "Göster" 30 sn sonra kendiliğinden kapanır, sekme arka plana geçince ve bölümden çıkınca her şey silinir;
  satır adlı kopyala ve "Tümünü gizle".
- **İzleme Değişiklikleri yeniden tasarlandı:** dönem özeti kartları (toplam + günlük eğri, işlem dağılımı, en çok
  değişen izlemeler, en aktif kişiler — tıklanınca süzgeç), güne göre gruplanmış zaman çizelgesi ve satır içi alan
  farkı (önce → sonra; açık / kapalı, süre, liste, JSON, script satır farkı); ayrıntı panelinde ham JSON ve
  paylaşılabilir bağlantı. Süzgeçler, sayfa ve açık ayrıntı adreste (`ch_*`); yeni süzgeçler: tek izleme, duraklatma /
  sürdürme. Süzülmüş listenin tamamı CSV olarak indirilebilir (formül enjeksiyonu korumalı); sonradan silinen izleme
  "silinmiş" rozetiyle gösterilir, ölü bağlantı yok. Telefonda süzgeç çekmecesi, önce / sonra alt alta. Yeni uç
  `/api/monitoring/changes/summary` (kapsam ve takım süzgeciyle); sayfa çevirmek toplama sorgularını yeniden koşturmaz.
- **Raporlar → İstatistikler yeniden tasarlandı:** tıklanabilir özet kutuları (toplam, sağlıklı, 30 / 14 / 7 gün,
  süresi dolmuş, hata) tabloyu süzer; kalan süre dağılımı halka grafiği ve "Yaklaşan bitişler" kartı; operasyon özeti
  (sağlık, açık alarm, SLA → ilgili sayfaya; takım sağlığı → süzgeç). Takım × kademe matrisi ısı tonlarıyla (Üretim
  T1–T2 / Tüm kademeler, yapışkan ilk sütun, telefonda takım kartları); seviyeler artık sunucunun kademe eşiklerinden
  (`alert_level`, Genel Bakış ile aynı). Sertifika tablosu: gecikmeli arama, kalan süre ve sağlayıcı süzgeçleri, çipler,
  başlıktan sıralama, CSV dışa aktarma, telefonda kart görünümü; süzgeç, sıralama ve sayfa adreste (`st_*`).
- **İzleme süre grafikleri yeniden tasarlandı** (dokuz izleme türü + sertifika penceresi, ortak bileşen): özet
  kutuları (ortalama / medyan, p95 — dilimli pencerede dürüstçe "tepe" etiketli, en yüksek / en düşük, erişilebilirlik,
  başarısız kontrol, ping paket kaybı, sertifika kalan gün; dokununca açılan açıklamalar), degrade dolgulu ortalama +
  p95 + min–maks bandı, eşik çizgisi (Sayfa Hızı bütçesi; Keyword / Port / Sentetik / DNS yavaş yanıt eşiği), başarısız
  kontrol gölgesi ve işaretleri, veri olmayan dilimde kopan çizgi, kurum saatiyle ipucu. Telefonda aralık seçimi yerel
  seçim kutusu, genişliğe göre seyrekleşen zaman etiketleri; ping paket kaybı ve sertifika kalan gün ikinci y ekseni
  yerine ortak zaman eksenli küçük ikinci grafikte. 24 saate kadar olan aralıkta dakikalık canlı yenileme (sekme
  görünürken); ekran okuyucu için grafik özeti.
- **Haftalık Erişilebilirlik e-postası yeniden tasarlandı:** hüküm rozeti + tek satırlık özet, geçen haftaya göre
  değişimli özet kutuları (erişilebilirlik, kesinti süresi, etkilenen domain, en yakın sertifika; alarm toplamı, açık,
  MTTR), izleme türü başına başarı çubukları, en kötü 10 domain, haftanın en uzun 8 alarmı, yaklaşan sertifika
  bitişleri; her satırda canlı taban adresli derin bağlantı, "Raporu uygulamada aç" ve tam düz metin paritesi. Telefonda
  kutular 2×2, Outlook-güvenli; büyük takımda bile Gmail'in kırpma sınırının altında.
- **Haftalık rapor sorguları toplulaştırıldı:** domain başına erişilebilirlik ve son kontrol sorguları 50'lik parçalarla
  tek sorguya indi (200 domainli takımda ~400 → ~9 sorgu); Haftalık Raporlar özet şeridi de aynı yoldan hızlandı.
- **Ayarlar → Başarısız Login Anomali Uyarısı yeniden tasarlandı:** canlı durum satırı (son 30 günde olay sayısı, son
  olay, açık olay rozeti) ve alanlar değiştikçe güncellenen düz dil kural özeti; kurallar kart ailelerine ayrıldı
  (Sabit eşikler / Görece sıçrama / Zamanlama ve bildirim), her alanda birim, ipucu, satır içi doğrulama ve alanlar arası
  uyarı (ör. geriye dönük tarama sınırı pencereden kısaysa). Uyarı alıcıları doğrulamalı çip listesi (yinelenen / geçersiz
  adres, liste yapıştırma; alıcı yoksa yönetici adresine düşüş açıkça yazılır). Test e-postası başlıktaki açılır
  pencereden. Son olaylar dar alanda kart, geniş ekranda tablo; "Girişleri gör" olayın zaman aralığındaki başarısız
  girişleri Denetim Logu'nda açar. Yapışkan kaydet çubuğu "N kaydedilmemiş değişiklik" + Vazgeç; hatalı alan varken
  kaydetme kapalı. Kapsamlı müdürde olay saklama süresi salt okunur ve açıklamalı; global yöneticide saklamayı kısaltmak
  onay ister.

### Fixed
- **Ayarlar menü aramasına "admin" yazılıyordu:** Anahtar Çözümleme (ve parola alanı olan başka ekranlar) açılınca
  tarayıcı sayfayı giriş formu sanıp kayıtlı kullanıcı adını sol menünün arama kutusuna dolduruyor, "eşleşen bölüm yok"
  görünüyordu — Chrome parola alanında `autocomplete="off"`u yok sayar. Uygulamadaki tüm parola alanları artık niyetini
  açıkça söyler (`new-password` / `current-password`), arama kutusu parola yöneticilerine kapalı; kapı
  `passwordAutocomplete.test.js` her parola alanını denetler.
- **Olay & Hata Geçmişi özet kartları süzmüyordu:** "SLA İhlali" ve "Açık" kartları tek başına tıklandığında liste
  süzülmüyordu; silme, aktarma ve seçenek işlemlerinde ağ hatası artık yakalanıp gösterilir.
- **Sertifikanın 7/24 ayarı değişince Genel Bakış kartı eskiyordu:** 7/24 bildirimi açılıp kapatıldığında (tekli ya da
  toplu) sertifika önbellekleri boşaltılır; kart 5 dakikaya kadar eski durumu göstermez.
- **Aylık rapor PDF'indeki dağılım çubuğu son dilimi gizleyebiliyordu:** küçük dilimlere verilen asgari genişlik
  telafi edilmediği için son dilim ("Tarih yok") eksi genişliğe düşüyordu; artık kalan genişlik büyük dilimlere
  orantılı dağıtılır.
- **Toplu 7/24 işleminde bir kayıt hata verirse sertifika kartları eskiyordu:** önbellek boşaltma her durumda yapılır.
- **Aylık envanter raporu:** PDF üretilemediğinde 0 baytlık ek artık gönderilmiyor; bitiş tarihleri Türkiye saatine
  çevrilerek gösteriliyor (gece yarısına yakın biten sertifikalar bir gün erken görünüyordu).
- **Değişiklik geçmişinde liste / nesne değerleri "[object Object]" görünüyordu:** alan farkları artık listeleri
  virgülle, nesneleri JSON olarak gösterir (izlemenin kendi "Değişiklikler" sekmesi dâhil).
- **Sayfa Bütünlüğü grafiği kırık kaynak sayısını "ms" ekiyle gösteriyordu:** artık sayı olarak ("Kırık kaynak").
- **Sayfa Hızı grafik sekmesindeki metrik seçici telefonda pencereyi yatay taşırıyordu.**
- **İstatistikler ilk açılışta yalancı "sertifika yok" gösteriyordu:** veri gelene kadar iskelet; operasyon özetindeki
  tabloyu süzmeyen yinelenen "30 gün altı" kutusu kaldırıldı.

### Security
- **TRACE istek günlüğü sırları yazıyordu:** istek / yanıt gövdesi günlüğü TRACE seviyesine alındığında Anahtar
  Çözümleme ucu girilen aday anahtarı ve çözülen SMTP / LDAP parolasını düz metin yazıyordu (`key` / `value` alan adları
  maske listesinde yoktu). `/api/admin/secret-tools/` istek ve yanıt gövdeleri artık hiç günlüğe yazılmaz (yol
  varyasyonları dâhil — yüzde kodlanmış yol (`secret%2Dtools`, `%73ecret-tools`) da yakalanır); kapı
  `RequestLoggingFilterTest`.

## [20.88.0] — 2026-09-28

### Added
- **"Kim bilgilendirilir?" — kişi bazlı push kararı:** push kartı artık takımın her üyesini "Alır / Almaz" kararıyla ve
  almayanın nedeniyle gösterir (org rolü yok, grup eşleşmedi ya da kapalı, seviye grubun asgarisinin altında, kişi
  kapattı, pasif hesap, üyelik kaydı yok). Süzgeç (Tümü / Alacaklar / Almayacaklar) ve arama; her "Almaz" satırında
  sonraki adım (Kullanıcıyı aç / Push gruplarını düzenle / Etkinliklerim'i aç ya da kime başvurulacağı). Kanal kapalı,
  adres yok, tür/takım kapsam dışı ve sessiz saat durumları açıkça yazılır; engel varken "kanal engeli kalkınca alır".
  Özet kutucuklarında push alır / almaz sayıları; 7/24 (NOC) notu takımın 7/24 kapsamını özetler ve 7/24 Kapsamı'na
  bağlanır. Kararlar Ayarlar → Webhook → "Kim alır?" ile aynı servisten gelir.
- **Mükerrer alan adı — kayıt hangi ekipte ve ne yapılır:** aynı alan adı eklenmek (ya da kayıt o ada yeniden
  adlandırılmak) istendiğinde form, ham bildirim yerine eylemli bir uyarı bandı gösterir: kaydın sahibi ekip (rozet →
  üyeler), çöp kutusunda olup olmadığı, "Kaydı görüntüle" (salt okunur), yetkiliye "'<seçili takım>' ekibine aktar"
  (çöp kutusundaysa aktar + geri yükle) ve "Çöp kutusundan geri yükle", diğerlerine Sorun Bildir üzerinden
  **"Alan adı aktarımı"** talebi (yeni kategori; Sorun Bildirimleri'nde süzgeç, yönetici e-postasında etiket). Sunucu
  409 yanıtı yapısal (`code: DOMAIN_EXISTS` + sahibi ekip ve yetki bayrakları; iletişim/not/IP gibi alanlar dönmez).
  CSV içe aktarma başka ekibin kaydına çarpan satırı `duplicate_other_team` nedeni ve sahibi ekip rozetiyle gösterir.
- **7/24 Kapsamı → izlemeye doğrudan bağlantı:** her satırın adı ve yeni "İzlemeyi aç" simgesi gerçek bağlantı; izleme
  kartına tıklamakla aynı pencereyi açar (10 tür — Sentetik ve SSL sertifika penceresi dâhil; izleme 2. sayfada,
  duraklatılmış ya da süzgeçle gizli olsa da). Ctrl/⌘/orta tıkla yeni sekmede açılır, Geri 7/24 süzgeçlerini korur.
  "…" menüsünde "Bağlantıyı kopyala" ve (yetkiliye) "7/24 ayarını düzenle" — form, "7/24 izleme ekibine bildir"
  anahtarına kaydırılmış ve odaklanmış açılır. Yeni tek seferlik URL parametresi `open=cert|noc`.
- **E-postadan sertifika penceresi:** sertifika uyarısı / çözüm e-postalarındaki ve 7/24 e-postasındaki bağlantı artık
  Genel Bakış'ı yalnız süzmekle kalmaz, alan adının sertifika penceresini doğrudan açar.
- **Sorun Bildir — "Ne yaşıyorsunuz?":** 12 yaygın durumdan çoklu seçim (giriş, sayfa açılmıyor, yavaşlık, yanlış veri,
  kaydetme hatası, alarm gelmiyor / yanlış alarm, erişim, mobil, rapor/dışa aktarma, özellik isteği, Diğer + kısa
  açıklama) ve kompakt önem seçimi. Seçimler kayda, yönetici e-postasına, Sorun Bildirimleri listesi ve ayrıntısına
  düşer; "Etki" süzgeci eklendi. Eski kayıtlar değişmeden görünür.

### Changed
- ⚠ Davranış — **Olaylar tüm takımlara açık (salt okunur):** Alarmlar → Olaylar'da "Takımımın olayları · Diğer ekiplerin
  olayları · Tümü" süzgeci (sayılarıyla; varsayılan "Takımımın" = bugünkü görünüm). Başka ekibin olayı liste, detay ve
  yorumlarıyla okunur; "Başka takımın kaydı — salt okunur" rozeti ve sahibi ekip görünür, onaylama / çözme / yorum /
  silme yoktur (sunucu 403). Bildirim alıcıları, teslim günlüğü ve 7/24 arama kayıtları olayın ekibinde kalır.
  **Yeni yapılandırma:** `site.monitor.incidents.visible-to-all` (`INCIDENTS_VISIBLE_TO_ALL`, varsayılan `true`,
  yalnız global yönetici).
- ⚠ Davranış — **7/24 ekibi (AUDIT) varsayılan olarak arama kaydı girer:** `noc_calls.write` AUDIT rolüne varsayılan
  verilir; yükseltmede mevcut kurulumlarda açılır, yöneticinin elle kapattığı izne dokunulmaz. AUDIT sahiplenme /
  çözme / yeniden bildirim yapamaz; Alarm Geçmişi'nde bu düğmeler gösterilmez (yerine neden notu).
- **Takım üyeleri penceresi yeniden tasarlandı:** başlıkta Takım Müdürü ve Takım Lideri ayrı kişi kartlarında (müdür üye
  listesine eklenmez), takım e-postası kopyalanabilir; üyelerde Türkçe karakter duyarsız arama, rol / ad / unvana göre
  sıralama ve rol süzgeç çipleri; geniş ekranda sütunlu liste, telefonda tam ekran ve yapışkan arama; kalabalık
  takımlar standart sayfalamayla (varsayılan 50 kişi) hızlı açılır. Eskalasyon kişileri alarm seviyesine göre gruplu (önce kritik) ve
  açıklamalı; yetkisi olana salt okunur "7/24 arama listesi" sekmesi (AD'de telefonu olmayan işaretli, numara
  gösterilmez).
- **Olayı / alarmı onayla ve çöz penceresi yeniden tasarlandı** (Olaylar ve Alarm Geçmişi, tekli ve toplu aynı
  pencere): hedef, önem, tür, açılış, takım, sahiplenen, bildirim ve 7/24 arama sayılarıyla özet kartı; eylemin gerçekte
  ne yaptığını anlatan açıklama ("Onayla ve sahiplen"); sunucu kuralıyla birebir canlı gerekçe kontrol listesi (en az
  10 karakter, 3 kelime) ve karakter sayacı; eyleme özel hazır gerekçe çipleri; Ctrl/⌘+Enter ile gönderim. Sunucu
  hatası (403, not kuralı, ağ) artık pencerenin içinde gösterilir — pencere kapanmaz, not kaybolmaz; yazılmış not
  kapatılırken onay istenir. Telefonda alttan açılır, eylem çubuğu sabit, klavye açıkken not alanı görünür kalır.
  Olaylar listesi bu bağlam için sahiplenen kişi, e-posta / push teslim sayıları ve 7/24 arama sayısını yalnız
  kullanıcının kendi takımının olaylarında döndürür (başka ekiplerin olaylarında yok); sayfa başına kaynak başına tek
  toplu sorgu.
- **Alan Adı izleme detay penceresi yeniden tasarlandı:** kalan gün kahramanı (uzun bitiş tarihi, kayıt dönemi
  çubuğu), yenileme planı bloğu (planlı / gecikmiş / "Yenileme planla"), özet kartı (kayıt kuruluşu + IANA, kaynak,
  son kontrol, sıklık, alarm eşikleri, "Şimdi kontrol et"); bitiş tarihi bilinmiyorsa neden + tam hata metni +
  sonraki adımlar ve "Sorun Tanıla". Telefonda pencere tam ekran. "Domain Kaydı" sekmesi bölümlere ayrıldı: kayıt zaman
  çizelgesi, kayıt kuruluşu, ad sunucuları ve DNS (DNSSEC, IP ↔ PTR), koruma (transfer / güncelleme / silme /
  yenileme kilitleri sade dille, kara liste kanıtı + listeden çıkarma sayfası), açıklamalı tüm EPP kodları,
  hatırlatmalar ve kopyalanabilir kayıt verisi (JSON).
- **Sertifika penceresi → "SSL Kontrol" sekmesi yeniden tasarlandı:** tek bakışta hüküm (her şey yolunda / dikkat /
  sorun var / yeterli bilgi yok) ve düz dille gerekçeleri, gruplu kontroller (Sertifika · Güven ve zincir · Bağlantı;
  HSTS / ileri gizlilik öneri olarak, bilinmeyen gri), sunucu → ara → kök zincir kartları (SAN listesi, seri numarası ve
  SHA-256 parmak izi kopyalama, teknik ayrıntılar), bağlantı hatasında nedene göre açıklama ve "Yeniden kontrol et".
  Canlı önizleme artık sunucu hükmünü taşır (`assessment` + `security_flags`, kart rozeti ve Sağlık sekmesiyle aynı
  kural — `*.example.com` artık `a.b.example.com`'u kapsıyor görünmez).
- **Sertifika penceresi → "Notlar" sekmesi zenginleştirildi:** kategori çipleri, büyüyen metin alanı, Ctrl/⌘+Enter ile
  kaydetme, 5000 karakter sayacı; sayılı kategori süzgeci + arama + "Silinenleri göster"; zaman çizelgesi (baş harf
  avatarı, göreli zaman, "düzenlendi" işareti, katlanır geçmiş), satır içi düzenleme; salt okunur / yetkisiz durumda
  nedenini söyleyen bant. Yazarken Escape artık pencereyi kapatıp taslağı silmiyor.
- ⚠ Davranış — **Takım üyeleri penceresi kişisel veri göstermez:** fotoğraf, telefon, sicil ve sistem rolü artık yönetici
  görünümünde de yok (baş harf avatarı); bu bilgiler Kullanıcılar sekmesinde kalır.

### Fixed
- **Geri yükleme, aktarım ve grup yeniden adlandırma sonrası Genel Bakış eskiyordu:** çöp kutusundan geri yükleme
  (mükerrer alan adı akışı dâhil), SY/UG aktarımı, takım varlık taşıma ve grup yeniden adlandırma önbelleği
  boşaltmıyordu — alan adı 5 dakikaya kadar görünmüyor, takım ve grup adları eski kalıyordu. Envanteri yazan her uç
  artık önbelleği (takım adları dâhil) boşaltır; `CertificateServiceCacheEvictionTest` bunu her uç için denetler.
- **Devredilen alan adının açık sertifika alarmı iki takıma bölünüyordu:** alarm açıkken envanter başka takıma
  devredildiğinde yükseltme / günlük hatırlatma e-postası, eskalasyon kişileri ve 7/24 arama listesi yeni takıma, push
  ve çözüm eski takıma gidiyordu. Artık açılıştaki kaçırılan hatırlatma turu dâhil tüm bildirimler alarmın açıldığı
  takımı kullanır.
- **Geçici zaman aşımı KRİTİK sertifika alarmını UYARI'ya indiriyordu:** günlük hatırlatma vakti geçici bir bağlantı
  hatasına denk gelince müdür alıcılardan düşüyor, gün sayısı kayboluyor ve doğru hatırlatma 24 saat kayıyordu.
  Doğrulanmamış tur artık alarmı değiştirmez; hatırlatma ilk doğrulanmış turda gider ve alıcı seviyesi alarmın
  seviyesinin altına inmez.
- **İlk bildirimi yarıda kalan sertifika alarmı ~24 saat sessiz kalıyordu:** dağıtım / yeniden başlatma sırasında ilk
  bildirimi kesilen alarm bir sonraki taramada ya da açılıştaki tamamlama turunda İLK bildirim olarak bir kez gönderilir
  (gönderim sürerken çift gönderim olmaz).
- **Giriş ve oran sınırı yanıtı yavaşlayabiliyordu:** girişte ve 429 yanıtında coğrafi IP sorgusu ile zaman aşımsız
  ters DNS istek iş parçacığında eşzamanlı çalışıyordu (arka plan işaretlemesi sınıf içi çağrıda devreye girmiyordu).
  Zenginleştirme artık gerçekten arka planda çalışır, ters DNS 1,5 sn ile sınırlıdır.
- **Push yeniden denemeleri beklemiyordu:** push gönderim kuyruğu yeniden denemelerde bekleme süresini (30 sn /
  120 sn) artık gerçekten uygular — bir sonraki deneme zamanı satıra yazılır (`next_attempt_at`, boş olabilir kolon;
  yükseltmede kendiliğinden eklenir), dakikalık tarama ve yeni bildirimler bekleyen denemeyi erken göndermez. Önceden
  ~2 dakikalık bir push API kesintisi o penceredeki bütün bildirimleri kalıcı olarak başarısız yapıyordu.
- **Aynı push her dakika yeniden gidebiliyordu:** push API'si 60 karakterden uzun bir bildirim kimliği döndürdüğünde
  kayıt düşüyor ve bildirim sonsuza dek yeniden gönderiliyordu. Kimlik kolon sınırına kırpılır, başarılı gönderim
  kayıt hatası yüzünden yeniden denenmez, kayıt düşse bile deneme sayacı ilerler.
- ⚠ Davranış — **Alarm fırtınası push'u kanal kurallarına uyar:** takım ve izleme türü anahtarları, izleme bazında push
  kapatma, sessiz saatler ve günlük tekrar ayarı artık fırtına push'unda da geçerli; engellenen gönderimler teslimat
  günlüğünde gerekçesiyle görünür. Fırtına push'u bireysel push gibi yalnız SY takımına gider (UG takımı fırtına
  e-postasını almaya devam eder). Fırtına "çözüldü" push'u açılış bildirimini alan herkese gider ve saatlik tavana
  takılmaz (yöneticiler "N izleme erişilemez"i alıp "çözüldü"yü hiç almıyordu); günlük tekrar push'u ilk günden sonra
  sessizce atlanıyordu, artık her gün gider.
- **HTTP uyarısı e-postasındaki bağlantı yanlış sayfaya gidiyordu:** HTTP izleme uyarılarının "Site Monitor'de
  Görüntüle" bağlantısı Genel Bakış'ı izlemenin URL'siyle süzüyordu (boş sonuç); artık HTTP İzleme'de izlemenin
  kendisini açar. Sertifika penceresini açan `open=cert` yalnız gerçek sertifika uyarılarına eklenir. Düz metin
  e-postadaki bağlantı HTML ile aynı tabanı kullanır (sonda `/` olan adreste `//?tab=` oluşmaz); çözüm e-postasının düz
  metin sürümüne de görüntüleme bağlantısı eklendi.
- **Yeni alan adı kartı tazelemesi oturumdan bağımsızdı:** çıkış yapıldığında ya da oturum düştüğünde kısa aralıklı
  tazeleme durur ve kart "hesaplanıyor" durumunda takılı kalmaz; aynı alan adı yeniden eklenirse eski döngü biter.
- **İzleme derin bağlantıları yavaş listede açılmıyordu (dokuz tür):** e-postadaki ya da kartın "Bağlantıyı kopyala"
  ile paylaşılan `?monitor=` bağlantısı, izleme listesi 300 ms'den geç yüklendiğinde hiçbir şey açmıyordu (sayfanın URL
  eşitlemesi parametreyi liste gelmeden siliyordu). Artık açılır; bulunamayan ya da erişilemeyen izlemede "İzleme
  bulunamadı ya da erişiminiz yok" uyarısı çıkar. Sentetik İzleme'nin kendi kopyası da aynı yola geçti.
- **SSL derin bağlantısı elle açılan formu kapatıyordu:** bağlantının açtığı sertifika penceresi kullanıcı tarafından
  kapatılıp elle başka bir pencere ya da "Düzenle" formu açıldığında, Pano'dan ayrılmak (Geri, 7/24 alanının Ayarlar
  bağlantısı) o formu sessizce kapatıyor ve düzenlemeler kayboluyordu; artık yalnız bağlantının açtığı ve hâlâ açık
  olan pencere kapanır.
- **Alarm Geçmişi toplu işlem yersiz "1 başarısız" veriyordu:** seçili bir uyarı karttan tekli sahiplenilip / çözülüp
  listeden düşünce seçim artık kalanlara budanır; toplu Sahiplen / Çöz yalnız pencerenin gösterdiği uyarıları gönderir.
- **Olaylar süzgeçleri hatalı yüklemeden sonra donuyordu:** sunucu süzgeci değişikliği yüklenemediğinde istemci
  süzgeçleri (önem, takım, Onaylı / Kritik / Atanmamış / Benim kutucukları) eski satırlara anında uygulanır.
- **Sentetik İzleme bekleme süresi uyarısı hiç görünmüyordu:** kayıt sonrası doğrulama koşumu elle çalıştırma bekleme
  süresine (429) takıldığında hata bildirimi yerine "bu izleme az önce çalıştırıldı" bandı gösterilir.
- **Kara liste çipi yanlış sayıyordu:** alan adı kartı ve detay başlığı `;` ayraçlı kara liste kanıtını tek liste
  sayıyordu (3 liste → "1 liste"); artık sunucunun biçimiyle doğru sayar.
- **CSV içe aktarmada harf duyarlı eşleşme:** envanterdeki karışık harfli eski kayıtlar (ör. `Www.Example.com`)
  içe aktarmada eşleşmiyor ve ikinci kayıt oluşuyordu; artık harf duyarsız eşleşir. Port ve DNS izlemelerinin takımlar arası mükerrer iletisi artık kaydın sahibi ekibi adıyla söyler.
- **Sertifika penceresi sekmeler arasında boy değiştiriyordu:** pencere artık sabit boyutta (yapışık sekme şeridi,
  yalnız içerik kayar; telefonda neredeyse tam ekran) — gerçek tarayıcı testi `cert-detail-stability.spec.js`.
  Canlı SSL kontrolü hata aldığında oluşan sonsuz istek döngüsü ve not listesinde alan adı değişirken beliren yalancı
  "Henüz not yok" giderildi.
- **İzleme detay penceresi sekmeler arasında küçülüp büyüyor, titriyordu (dokuz tür):** pencere yüksekliği sekme
  içeriğine göre değişip ortalanan pencere yeniden konumlanıyordu (HTTP'de yükseklik 499–868 px, üst kenar 16–200 px).
  Artık geniş ekranda sabit boyut; başlık ve eylemler sabit, yalnız içerik kayar. Gerçek tarayıcı testi
  `monitor-detail-stability.spec.js` (dokuz türün her sekmesi, oynama ≤ 2 px).
- **Yeni eklenen alan adının kartı bir süre boş kalıyordu:** sağlık, açık alarm, erişilebilirlik ve sorumlu kişi alanları
  ilk kontrol bitene ve 5 dakikalık tazeleme gelene kadar boştu. Artık ekleme (ve aktarım / geri yükleme) sonrası Genel
  Bakış o alan adının verisi gelene dek kısa aralıklarla tazelenir; bu sürede kart "İlk kontrol yapılıyor…" ve
  "hesaplanıyor" gösterir. Envanter uçlarının önbellek temizliği kart eklerini de kapsar (kapı
  `CertificateServiceCacheEvictionTest`, kaynak taraması).
- **Olaylar titremesi:** süzgeç / kapsam / sayfa değişiminde kartlar yerinde kalır (iskelet yalnız ilk yüklemede);
  yavaş yanıtta liste soluklaşır (180 ms gecikmeli gösterge); arama yazarken 250 ms duraklamada tek istek gider.
- **Yeni izleme kartı boş kalıyordu (dokuz tür):** kaydedince kart zamanlayıcının ilk turunu bekliyordu (türüne göre
  30 sn – 1 saat). Artık kaydetmeden hemen sonra kartın kendi "Şimdi kontrol et" yolu çalışır; kart "İlk kontrol
  yapılıyor…" gösterir ve ilk sonuç saniyeler içinde düşer (geçmiş, alarm ve trend normal kontrolle aynı). Düzenlemede
  hedef/kontrol ayarı değiştiyse taze kontrol koşulur; yalnız ad/etiket/grup/bildirim değişikliği ve duraklatılmış
  izleme kontrol başlatmaz. Sentetik İzleme'de kaydetme sonrası doğrulama koşumu (tek koşum) artık karta da işlenir.
  Hiç kontrol edilmemiş Ping ve Sayfa Hızı kartları boş değil, bekleme satırı gösterir.
- **Genel Bakış kartı yenileme planı:** plan kaydedilince ya da kaldırılınca "başarılı" bildirimi çıkıyor ama kart
  60 saniyeye kadar eski durumu ("Yenilemeyi planla" / "Yenileme planlandı") gösteriyordu — kart eklerinin sunucu
  önbelleği sertifika/envanter değişikliğinde boşaltılmıyordu. Artık plan, kaldırma, yenileme onayı ve envanter
  değişikliğinden hemen sonra kart güncel; kapı `CertificateServiceCacheEvictionTest`.
- **Sürüm penceresi** (sol üstteki sürüm bilgisi): commit satırı pencerenin dışına taşıyordu (40 karakterlik SHA
  kırılamıyordu). Artık kısa commit gösterilir, tam SHA üzerine gelince görünür ve kopyalanır; uzun değişiklik
  başlıkları da satır içinde kırılır. Kopyalanabilir referans bileşeni uzun değerleri her ekranda taşırmadan kırar.
  Gerçek tarayıcı testi `nav-brand.spec.js`.
- **Ortam rozeti:** sürüm penceresi, Sürüm & Dağıtım ve Sistem Sağlığı'nda ortam adı verilmemiş kurulumlar ham
  "unknown" gösteriyordu; artık "Ortam adı yok" + nedenini ve çözümünü anlatan ipucu (Helm `config.environmentName`,
  prod dağıtım komutu `environments/master.yaml` ile çalışınca "prod"); yerel kurulum "Yerel".

### Security
- **Kişi bazlı push kararlarının görünürlüğü sunucuda:** global yönetici her takımı, takımı yöneten (TEAM_ADMIN /
  kapsamlı müdür) yalnız yönettiği takımları, üye yalnız kendi satırını görür; göremeyene nedeni ve kime başvuracağı
  yazılır. Ayarlar → Webhook → "Kim alır?" ucu (`/api/admin/user-push/explain`) da aynı kurala bağlandı — kapsamlı
  müdür artık yönetmediği takımın üyelerinin org rolünü ve push tercihini API'den okuyamaz.
- ⚠ Davranış — **Bakım penceresi kapsamı sunucuda:** "Tüm izlemeler" penceresini yalnız global yönetici açabilir;
  takım yöneticisi / kapsamlı müdür yalnız YÖNETTİĞİ takımların izlemelerini susturabilir (aynı hedefi başka takım da
  izliyorsa reddedilir) — oluşturma, hızlı bakım, düzenleme ve sürdürmede. Eskiden takım yöneticisi tek istekle tüm
  kurumun alarmlarını (e-posta, push, 7/24) susturabiliyordu. Süre tavanı: global yönetici 30 gün, diğerleri 7 gün.
  `GET /maintenance/active` global olmayana yalnız kendi takımlarının hedeflerini döner. Bakım penceresi formunda
  "Tüm monitörler" kutusu yalnız global yöneticiye görünür.
- **Olay kaydı takımı doğrulanır:** olay oluştururken ya da düzenlerken takımı değiştirirken hedef takım `/transfer`
  ile aynı kurala tabidir — kullanıcı başka takımın defterine olay açamaz, o takıma bildirim e-postası gönderemez.
- **Eski `/api/admin/system/smtp-logs` yalnız global görüntüleyiciye:** tüm takımların posta günlüğünü (alıcı, konu,
  alarm metni) süzmeden döndürüyordu; 7/24 satırları artık maskeli. Arayüz takım kapsamlı SMTP Gönderim Logu'nu
  kullanmaya devam eder.
- **Canlı teyit zincirleri takım kapsamlı:** `GET /monitoring/confirmations` global olmayana yalnız kendi (UG dâhil)
  takımlarının düşen hedeflerini gösterir.
- **Sertifika sağlığı yazma uçları işlem kapsamı ister:** Sağlık sekmesindeki "Şimdi kontrol et" ve "Planlı
  yenilemeydi" onayı yalnız o takımda işlem yapabilene açık; salt okur AUDIT yenileme onaylayamaz.
- **Eskalasyon kişisi başka takıma yazılmıyor:** AD kaynaklı kapsamlı müdür kişi eklerken `team_id` yok sayılıyor ve
  kişi (e-posta + webhook adresi) alfabetik ilk takıma kaydediliyordu — o takımın eskalasyon e-postalarını ve
  webhook'larını alıyor, müdür onu göremiyor ve silemiyordu. Hedef takım artık gövdeden (yoksa birincil takımdan)
  alınır ve yönetim kapsamında olmalıdır (403); güncellemede de kişi yalnız yönetilen takıma taşınabilir.
- **Webhook teslim durumu ve webhook testi takım kapsamlı:** `/contacts/webhook-status` her kullanıcıya tüm takımların
  webhook alıcı e-postalarını, son durumu ve hata ayrıntısını döndürüyordu; artık yalnız görebildiği kişiler. Kişi
  webhook testi güncelleme / silme ile aynı yönetim kapsamını ister.
- **Kişi bazlı push ayarları kapsamlı:** kapsamlı müdür yalnız yönettiği takımların push kapsamını değiştirebilir;
  izleme türü kapsamı yalnız global yöneticide. Test push'u yalnız yönettiği takımların üyelerine gönderilebilir.
- **Sorun Bildirimleri yönetimi kapsamlı müdüre kapalı:** liste, ayrıntı, yanıt, durum değişikliği ve kalıcı silme
  (IP, tarayıcı bilgisi, ekran görüntüsü içerir) yalnız global yönetim rollerinde; müdür ekranda kendi bildirimlerini
  görür ve yeni bildirim açabilir.
- ⚠ Davranış — **Veri Saklama yalnız global yöneticide:** ayar, yasal saklama, onay, deneme, elle temizlik ve geriye
  doldurma uçları ile tüm saklama anahtarları kapsamlı müdüre kapalı; müdür ekranı salt okunur görür. Değişmeyen
  global-only değeri geri gönderen kayıt artık reddedilmez, yok sayılır (Giriş Anomalisi ve push ayarları müdürde
  kaydedilmeye devam eder).
- **İzleme Rehberi takım kapsamlı:** rehberi yalnız o hedefi izleyen bir izlemede işlem yapabilen düzenler, görebilen
  okur (DNS / Port envanter türevi satırda takım envanterden).
- **Push teslimat günlüğü takım kapsamlı:** Ayarlar → Webhook teslimat günlüğü, CSV dışa aktarımı ve istatistik
  kutucukları kapsamlı müdüre (AD ADMIN) yalnız YÖNETTİĞİ takımların satırlarını gösterir; eskiden her takımın kişi
  adları, teslim durumları (kişinin push'u kapattığı dâhil) ve mesaj metinleri okunabiliyordu. Yönetmediği takım
  süzgeci 403.
- **Olaylar — başka ekibin olayında çözen kişi gizli:** kurum geneli salt okunur görünümde başka ekibin olayını
  çözen kişinin adı artık dönmez (sahiplenen gibi); sistem kapanışları ("Sistem (…)", envanter silindi) görünmeye
  devam eder.

## [20.87.0] — 2026-09-27

### Added
- **shadcn/ui geçişi tamamlandı:** arayüzdeki HER ekran ve öğe shadcn/ui bileşenleriyle çizilir (kalan 1.199 ham
  öğe sıfırlandı). Kalıcı kapılar: `shadcnOnly` (ham kontrol / elle bileşen / `role="button"` ile elle düğme yasak,
  yalnız gerekçeli muafiyetler), `paginationBase`, `globalsBase`, `rowAccessibleNames`; kartlarda/panellerde SOL
  RENK ŞERİDİ yok — durum rozet, tam kenarlık ve ikon rengiyle.
- **Mobil web:** tüm ekranlar telefon (390 px) ve tablette (768 px) yatay kaydırmasız çalışır; tablolar telefonda
  kart listesine döner, dokunma hedefleri ≥ 40 px, süzgeçler telefonda kayar panel (Sheet). Tarayıcı kapısı
  `e2e/responsive.spec.js` her sekmeyi iki genişlikte ölçer. Uygulama kabuğu telefonda 12 px kenar boşluğu.
- **Sol menü yeniden tasarlandı** (shadcn sidebar-07): her bölüm ve sayfa için tutarlı lucide ikonu, tek açık bölüm
  (etkin sayfanın bölümü kendiliğinden açılır), ikon kipinde yan açılır menü + ipuçları, komut paleti tetiği (Ctrl K),
  bildirim sayacı, kullanıcı kartı menüsü (ayarlar, tema, dil, sorun bildir, **Çıkış** en altta), telefonda üst çubuk +
  kayar menü. Türkçe bölüm adı "Uyarılar" → **"Alarmlar"** (sekme adı "Uyarılar" değişmedi).
- **Tek sayfalama standardı:** her listede aynı shadcn sayfalama çubuğu (masaüstü: sayfa numaraları + "Sayfa başına"
  seçici; telefon: "‹ 3 / 42 ›"); ön ayarlar sayfa 50 / panel 25 / pencere 10; `useServerPagination` kancası;
  bağlantıdaki sayfa numarası korunur, son sayfayı aşan sayfa son sayfaya gider, geçersiz `ps` reddedilir.
- **E-postalar yeniden tasarlandı:** 35 e-posta türünün tamamı tek tasarım kitiyle (`MailKit`/`MailDoc`/`MailTokens`,
  shadcn zinc paleti) kurulur; telefonda duyarlı (tek `<style>` + akışkan kart, Outlook için hayalet tablo/VML),
  hepsi HTML + düz metin (multipart), sol şerit/tam genişlik renkli başlık yok. Kapı `EmailResponsiveContractTest`;
  galeri `EmailGalleryTest` + `e2e/email-gallery.spec.js`. `branding/BRAND.md` §5.1 buna göre güncellendi.
- **Kullanıcılar sekmesi** (Yönetim Paneli): tıklanabilir özet kutucukları (tümü / etkin / yönetici / hiç girmemiş /
  90+ gün / kilitli), etkin süzgeç çipleri, takım rozetleri, göreli son giriş (+ 90+ gün uyarısı), kilit rozetleri,
  telefonda kart listesi; **Değişiklik Geçmişi** tablosu (eylem rozeti, "alan eski → yeni" çipleri, telefonda kartlar).
- **Sistem Sağlığı → Veritabanı Analitiği** yeniden tasarlandı: özet kartları, birleşik sorgu yükü grafiği, Tablolar /
  Sorgular / Başarısız / Kullanıcılar sekmeleri (arama, sıralama, sayfalama), SQL kopyala + ayrıntı, yükleme hatasında
  "Tekrar dene".
- **İzleme kartları:** erişilebilirlik tek satır (30 gün + dönem noktaları) ve ayrıntı penceresi (dönem başına
  kontrol/hata + hatalı saatler); duraklatılmış izleme kart ve ayrıntı penceresinden tek tıkla **Sürdür**; Alan Adı
  sayfasında toplu seçim/duraklat/sürdür ve 4 yeni istatistik (NS çözümlenmiyor, DNSSEC kapalı, kara listede, süresi
  dolmuş); alarm seviyesi görünür rozet; USER rolü Port dâhil tüm izlemeleri ekleyebilir (takımı ön seçili, takımsız
  kullanıcıya uyarı).
- **Genel Bakış:** sayfa başlığı + eylem çubuğu (`PageHeader`: başlık, açıklama, "N sertifika · son güncelleme"
  çipleri; sağda **Yenile · Şimdi Kontrol Et · Domain Ekle** — ilerleme düğmenin üstünde; telefonda başlığın altında);
  istatistik kutucukları alanı dengeli ve tam doldurur (1440'ta 5+5, geniş ekranda tek satır, telefonda 2 sütun;
  genişleyen kart yatay düzen) — aynı yerleşim tüm sayım kartlarında; "Sizin için — bugün" İstatistikler'le aynı hizada;
  SSL Checker alanı başlık satırının en sağında ve daha uzun; kart listesi başlığı "Sertifika kartları".
- **Sayfa başlığı standardı:** `ui/PageHeader` (ikon, başlık, açıklama, meta çipleri, eylemler; birincil eylem en
  sağda, telefonda düğmeler başlığın altında, en az 40 px) tüm sayfalarda. 9 izleme sayfası + Durum İzleme ortak başlıkta:
  "N izleme · M düştü · N sn sonra yenilenir" çipleri, Yenile · Şimdi kontrol et (ilerlemeli) · Bağlantıyı kopyala · Nasıl
  doldurulur · Yeni izleme (telefonda ikincil eylemler "Diğer" menüsünde). Tüm Sertifikalar ve Domain Envanteri'nde sayfanın
  üstünde yüzen "Şimdi Kontrol Et + SSL Checker" satırı kaldırıldı; eylemler sayfa başlığında.
- **Bildirimler paneli:** zile bağlı Popover (telefonda tam yükseklik kayar panel), Okunmamış / Tümü / Geçmiş sekmeleri,
  güne göre gruplama (Yaklaşan / Bugün / Dün / Daha önce), satır başına okundu / izlemeye git / kaldır, "Tümünü okundu
  işaretle", "Tümünü temizle" ve "Temizlenenleri göster", ↑/↓ ile satırlar arasında gezinme, göreli zaman + tam zaman.
- **Komut paleti (Ctrl K):** son kullanılanlar, hızlı eylemler (yeni HTTP izleme, alan adı ekle, şimdi kontrol et,
  sorun bildir, tema, dil, yardım, tur), sayfalar (bölüm alt başlığıyla), canlı arama grupları (sertifikalar — durum ve
  kalan gün rozeti, izlemeler — tür ikonu, takımlar, kullanıcılar [yalnız global yönetici]); telefonda tam ekran + üst
  çubukta arama düğmesi.
- **Bakım Pencereleri:** "Şu an susturulan" şeridi (kalan süre + ilerleme, Bitir), özet kutucukları (şu an / 24 saat /
  7 gün / yinelenen / duraklatılmış / geçmiş), Liste / Ajanda / Takvim sekmeleri, düz sözcüklü program ("Her Pzt–Cum
  22:00–23:00"), süre ön ayarlı editör + canlı özet, hızlı pencere.
- **Olaylar:** Pano (Açık → Sahiplenildi → Çözüldü şeritleri) / Liste görünümü, özet kutucukları (açık, sahiplenilmiş,
  kritik, takımsız, takımım, 24 saatte çözülen), fasetli süzgeçler + çipler, detay paneli (durum şeridi, sahiplen / çöz
  [gerekçeli], izlemeye git, bağlantıyı kopyala, zaman çizelgesi: açılış → bildirimler → sahiplenme → yorumlar →
  çözüm, yorum yazma Ctrl+Enter), `?tab=incidents&incident=<id>` derin bağlantı.
- **Yönetim Paneli:** sekme şeridi md+ tek satırda, telefonda gruplu seçim kutusu; özet kartları tam genişlik
  (`KpiCard`), sağlık uyarıları şerit; **Eşik Değerleri** yeniden tasarlandı — gün ekseni ölçeği, seviye başına
  "şu an N alan adı", üç uçlu kaydırıcı + kaydetmeden önce etki önizlemesi, kademe kapsama özeti, yalnız
  kapsamsız kademeleri listeleyen "Kademe eşiği ekle".
- **Takım üyeliği kaynağı ve AD denetimi:** `app_user_team_sources` tablosu (LDAP_GROUP / LDAP_COMPANY / MANUAL /
  TEAM_MOVE); Takımlar → ⋮ → **"AD ile üyelik denetimi"** (üye başına kaynak, AD hâlâ destekliyor mu, kilitsiz
  üyeleri AD'den eşitle), Kullanıcı detayı → **"AD ile karşılaştır" / "AD'den yeniden eşitle"**; üyelik
  kaynağı rozetleri. Yeni uçlar `/api/admin/users/{id}/team-membership|ldap-check|ldap-resync`,
  `/api/admin/teams/{id}/ldap-check|ldap-resync`; denetim olayları `USER_LDAP_SYNC`, `USER_LDAP_RESYNC`,
  `TEAM_LDAP_RESYNC`. Prod tanı rehberi ve onarım adımları raporda.
- **Envanter ve Tüm Sertifikalar kurum geneli görünür:** her kullanıcı hangi alan adının hangi takımda olduğunu
  görür (başka takımın kaydı tam ayrıntıyla, salt-okunur); Durum İzleme de aynı kuralla. Listeler "Takımlarım"
  ile açılır, "Tüm takımlar" tek tık (adreste paylaşılır). Pano, izleme sayfaları, alarmlar ve bildirimler takım
  kapsamlı kalır. **Yeni yapılandırma:** `site.monitor.inventory.visible-to-all` (varsayılan `true`).
- **Yeniden tasarlanan ekranlar:** İzleme Değişiklikleri, Denetim Logu, Etkinliklerim, Sertifika Yenileme Önerileri
  (özet kutucukları, fasetli süzgeçler, .ics/CSV dışa aktarma), Sertifika Değişim Rehberi (artık tam genişlik; adım adım
  rehber, platform sekmeleri, işaretlenebilir kontrol listesi, içindekiler), Bakım Pencereleri, Olaylar, Bildirimler
  paneli, komut paleti (Ctrl K), Tanılama pencereleri, Eşik Değerleri (gün ekseni ölçeği, etki önizlemesi), Yönetim
  Paneli sekme şeridi (tam genişlik), takım penceresi eskalasyon kişileri — hepsi shadcn, telefonda kart/Sheet düzeni.
- **Sorun Bildirimleri kullanıcıya açık:** herkes kendi bildirdiği sorunları görür, durumunu izler, yorum ekler;
  yöneticiler herkese açık yanıt ya da yalnız yöneticilerin gördüğü iç not yazar; yanıt/durum değişikliğinde uygulama
  içi bildirim + kısa e-posta; çözülmüş bildirime kullanıcı yorumu bildirimi yeniden açar (yöneticilere zil bildirimi).
  Yeni uçlar `GET/POST /api/issue-reports/mine[/{id}[/comments]]` (yalnız oturum sahibinin kayıtları; başkasınınki 404),
  `GET/POST /api/admin/login-issues/{id}/comments`; yeni tablo `issue_report_comments` + `login_issue_reports`
  sütunları (`last_activity_at`, `last_admin_activity_at`, `reporter_seen_at`) açılışta kendiliğinden yamalanır; denetim
  olayları `ISSUE_REPORT_COMMENT`, `ISSUE_REPORT_REOPEN`, `LOGIN_ISSUE_COMMENT`; saklama politikası
  `issue-report-comments-orphan`. Yönetim → Sorun Bildirimleri artık herkese görünür ("Bildirimlerim"; yöneticiye
  "Tüm bildirimler | Bildirimlerim").
- **Sistem Sağlığı:** genel durum şeridi (Sağlıklı / Bozulmuş / Kritik — sebepler önem sırasıyla, her biri ilgili
  bölüme atlar), 8 KPI kartı (çalışma süresi, zamanlayıcı, görev yürütücü, DB bağlantıları, HTTP yanıt süresi, hata
  oranı, JVM bellek, entegrasyonlar — kıvılcım grafikleri), duraklat/sürdür otomatik yenileme, entegrasyon kartları
  (SMTP, push, haftalık rapor, RDAP, ağ, LDAP, gece temizliği) + global yöneticiye "Şimdi test et", kalp atışı 24 saat
  sinyal grafiği, HTTP istek gezgini; telefonda yapışkan durum şeridi. Push başarı yüzdesi `0.98765…%` yerine `98.8 %`.
- **Tanılama:** Bağlantı Tanılama penceresi adım boru hattı (DNS → TCP → Vekil CONNECT → TLS → Sertifika → HTTP),
  hüküm şeridi (olası neden + sonraki adım), yol başına zamanlama çubukları, ham ayrıntılar, rapor kopyala, geçmiş +
  önceki koşuyla karşılaştırma, 429'da geri sayım; telefonda tam ekran. Ayarlar → Alan Adı Tanılama: doğrulamalı form,
  son aramalar, özet kartı, "Envanterde aç" ve "Özeti kopyala".
- **Domain Envanteri yeniden tasarlandı:** sayfa başlığı (canlı alan adı / takım sayısı, Yenile · Dışa aktar · İçe aktar ·
  Domain ekle), 11 süzgeç kutucuğu (geçerli, 30 gün altı, süresi dolmuş, hatalı, sorumlusuz, platformsuz, T1 kritik…),
  faset süzgeçleri + "Süzgeçler (n)" paneli + çipler, 1440'ta yatay kaydırmasız tablo (durum "Kalan gün" hücresinde),
  dar ekranda ve tablette kart listesi, detay çekmecesi (Genel / Sertifika / Değişiklikler / Kontroller, sorumlu kartları),
  bölümlü ekleme/düzenleme formu (yeni Açıklama alanı, mükerrer alan adı hatası alanın altında), `?tab=domains&domain=`
  bağlantısı kaydı açar.
- **Sorun Bildirimleri yeniden tasarlandı:** sayfa başlığı + "Sorun bildir", durum kutucukları (kullanıcıya "Yeni
  yanıtlar", yöneticiye "Benim bildirimlerim"), arama / durum / kaynak / önem / tarih süzgeçleri, tablo (telefonda kart),
  okunmamış işareti; detay paneli: durum adımları (Açıldı → İşlemde → Çözüldü, kim/ne zaman, yeniden açılış), ekran
  görüntüsü galerisi (←/→), sohbet görünümü (iç notlar kesikli çerçeve + kilit), sabit yazma alanı (Ctrl+Enter; çözülmüş
  bildirimde "Gönder ve yeniden aç"), yöneticiye durum paneli (çözüm notu zorunlu).
- **"Sorun bildir" formu (tüm girişler — kullanıcı menüsü, Ctrl K, sayfa, hata ekranı, giriş sayfası):** dört bölümlü sakin
  form (sorun, etkisi, ekran görüntüleri — sürükle-bırak / yapıştır / seç, iletişim), eklenecek teknik ayrıntılar ve
  gizlilik notu, hatada yazılan metin korunur, başarıda referans kodu + "Bildirimimi gör"; telefonda tam ekran.
- **Dikkat Gerektiren Sertifikalar:** sayfa başlığı (dikkat gereken / hemen ele alınacak sayısı, CSV, Şimdi kontrol et),
  süzgeç görevi gören neden kutucukları (süresi dolmuş, ≤7 gün, 8–30 gün, zincir/güven sorunu, ulaşılamıyor, zayıf
  algoritma, sessiz alarm, e-posta hatası), aciliyet grupları ("Hemen harekete geç", "Yapılandırma sorunları", "Bu ay
  yenile"), her satırda neden çipleri + önerilen sonraki adım ve tek tık eylemi (planla, bulguları gör, şimdi kontrol et),
  Liste / Kart görünümü, takım ve kademe süzgeçleri, ağ kesintisi geçmişi katlanır bölümde. Sayfa açılırken veri gelmeden
  kısa süre "Uyarı yok" gösterme hatası giderildi.
- **SQL Playground:** sayfa başlığı (salt-okunur kuralları, veritabanı bilgisi, sınırlar), şema gezgini (arama, satır/sütun
  sayısı, PK/FK rozetleri, tıklayınca sorgu), sözdizimi vurgulu editör (satır numaraları, Ctrl/⌘+Enter, yalnız seçimi
  çalıştır), örnek + geçmiş sorgular (süre, satır, hata), sonuç tablosu (sıralama, süzme, sütun gizleme, NULL rozeti,
  CSV/JSON dışa aktarma, kopyalama; telefonda kartlar), hata şeridinde veritabanı mesajı + "Editörde göster", satır
  ayrıntısı (←/→), tablo ayrıntısı sekmeleri; telefonda Editör / Sonuçlar / Şema sekmeleri.
- **Tablo ilişkileri (ER diyagramı):** otomatik katmanlı hiyerarşi (üst tablolar yukarıda), gerçek / çıkarımlı FK
  eğrileri ok ve kaz ayağıyla, yakınlaştırma / kaydırma / sığdırma, mini harita, gösterge, tablo bul (komşularıyla
  vurgulanır), tablo yan paneli, Adlar / Anahtarlar / Tüm sütunlar görünümü, yukarıdan-aşağı / soldan-sağa, PNG ve SVG
  dışa aktarma; telefonda liste görünümü.
- **Sistem Sağlığı → Sürüm ve Dağıtım:** çalışan sürüm kartı (sürüm, ortam, bu sürümde geçen süre, yayından canlıya,
  pod çalışma süresi, commit/imaj kopyalanır, sürüm uyuşmazlığı uyarısı, öne çıkan notlar), 6 gösterge (30/90 günde
  dağıtım, ortalama aralık, 90 günlük geri alma oranı, yeniden başlatmalar, yayından canlıya süre, atlanan sürümler),
  Zaman çizelgesi / Kayıtlar / Sürüm notları / Ortamlar görünümleri, süzgeçler (telefonda panel), doğrulamalı elle kayıt
  formu; yükleme hatası artık "kayıt yok" yerine yeniden dene şeridi gösterir.
- **Kullanıcı Detayı** artık sağdan açılan panel (telefonda tam ekran): profil başlığı (fotoğraf/baş harfler, kullanıcı adı ve
  e-posta kopyalanır, rol / durum / kilit rozetleri — dokununca açıklama, son giriş + "hiç giriş yapmadı" / "90+ gündür yok"
  uyarısı, Düzenle, AD ile karşılaştır) ve sayılı sekmeler: Genel (hesap, kilitler + kilidi aç, iletişim, organizasyon),
  Takımlar (üyelik kaynağı ve kanıtı; müdür ayrı "Raporlama hattı" kartında), Bildirimler, Yetkiler (arama, "yalnız
  verilenler"), Değişiklikler (fark tablosu, sayfalı), Dizin (AD). Her bölüm yüklenemezse hata + "Tekrar dene" gösterir.
- **Haftalık Raporlar yeniden tasarlandı:** başlıkta bu haftanın durumu ("2 / 5 takım gönderdi", geciken, son tarih,
  "Onayımı bekleyen"), Dışa aktar menüsü (liste CSV, yıllık özet yazdır / CSV), "Bu hafta" kutucukları + takım listesi
  (Oluştur / Devam et / Aç), takım × hafta tamamlanma ızgarası (dar ekranda takım kartları), arama + süzgeç çipleri, telefonda
  kartlar. **Yeni Hafta Raporu** artık tam ekran: "Bu hafta / Geçen hafta" kısayolları, o hafta için rapor varsa uyarı +
  "Raporu aç", boş şablon ya da geçen haftanın notlarıyla başlama. **Editör:** kaydetme durumu, bölüm kartları (dolu/boş),
  geniş ekranda sağda ana hat + ayrıntılar + yorumlar, yapışkan eylem çubuğu ("Kontrol edilecek: N", Posta önizleme, Taslak
  kaydet, Onaya gönder).
- **Haftalık rapor "4. Domain Bazlı Kritik İşlerin Durumu":** sekmeler yerine düzenlenebilir tablo (geniş ekranda; satır
  içi domain adı, "Güncellendi / Güncelleme yok" rozeti + özet, satıra tıklayınca Markdown editörü, yukarı/aşağı taşı,
  çoğalt, sil — onaylı), dar ekranda domain kartları; "6 domain · 4 güncellendi · 2 bekliyor" özeti, Enter ile yeni satır,
  boş / yinelenen ad uyarısı, geçen haftanın notu domain başına; okuma görünümünde özet + süzgeç çipleri.
- Haftalık raporda kaydetme sürerken hafta okları başka rapora geçerse eski kaydın sürümü yeni rapora yazılıyor (sahte
  "sürüm çakışması"), oklar kaydedilmemiş değişiklik onayını ve düzenleme kilidini atlıyordu; hızlı takım değişiminde
  önceki takımın raporları görünebiliyordu.
- **Takip linkleri** girilince tıklanabilir çipe dönüşür (Jira anahtarı ya da alan adı › son yol parçası etiketiyle); ham
  adres yalnız ayrıntı penceresinde ve "Linki kopyala" ile; düzenle / kaldır.
- **"1. Proaktif Servis İyileştirme Kayıtları" — Durum dağılımı:** tek "Durum" seçimi yerine Çalışılıyor / Planlandı /
  Beklemede / Tamamlandı sayıları (geçen haftaya göre fark), önem toplamıyla tutmazsa uyarı; haftalık rapor e-postasında,
  CSV'de ve yıllık özette dağılım gösterilir.
- **7/24 İzleme Ekibi (NOC) bildirimleri:** her izleme türünde ve sertifika envanterinde "7/24 izleme ekibine bildir"
  (varsayılan kapalı) ve grup seçimi; Ayarlar'da global 7/24 grupları (çoklu e-posta, varsayılan/aktif, test
  e-postası), tür bazında aç/kapa, en düşük seviye (varsayılan Kritik), çözüldü e-postası ve arama talimatı (yalnız
  global yönetici). 7/24 e-postası: ne oldu, sahibi takım, sıralı ARAMA LİSTESİ (`tel:` bağlantılı, telefon AD'den),
  Takım Müdürü yedeği, eskalasyon kişileri, "Arama kaydı ekle" düğmesi; toplu kesintide tek özet e-posta; posta
  günlüğünde `NOC` kategorisi. "7/24 Kapsamı" panosu (hangi izleme gece 7/24 ekibe gitmiyor ve neden), izleme başına
  ve toplu aç-kapa, takım arama listesi. ⚠ Davranış: 7/24 bildirimi takımın sessiz saatlerinden bağımsız gider (bakım
  penceresi ve global e-posta kapatma onu da durdurur); e-posta bağlantılarında `tel:` şemasına izin verildi.
- **7/24 alanı izleme formlarında:** dokuz izleme formu ve sertifika envanter formu "7/24 izleme ekibine bildir"
  (varsayılan kapalı) taşır; birden çok aktif grup varsa grup seçici (varsayılanlar önseçili, "Varsayılan gruplara
  dön"), tür yönetici tarafından kapatıldıysa uyarı, aktif grup yoksa yol gösteren satır; kopyala ayarı taşır; her
  türün "Nasıl doldurulur?" rehberinde. Zengin kartlarda ve detay başlığında "7/24" rozeti; toplu işlem çubuğunda
  "7/24'e bildir: Aç / Kapat". Alan adı ve envanter CSV dışa aktarımında 7/24 sütunları; envanter içe aktarma tanır.
- **7/24 güvenlik ve güvenilirlik:** posta günlüğündeki 7/24 satırları maskeli (arama listesi telefonlarının yalnız son
  iki hanesi; alıcı alanında grup adı + adres sayısı; 7/24 satırı yeniden gönderilemez); toplu kesinti sürerken
  sonradan katılan 7/24 izlemeleri için en çok 5 dakikada bir "Alarm fırtınası güncellemesi"; takılı kalan gönderim
  10 dakika sonra yeniden denenir; açık alarmın teslim izi saklama temizliğinde silinmez; `tel:` bağlantısı yalnız
  7/24 e-postasında. Uyarı listesindeki e-posta sayısı ve bakımdaki çözüm e-postası kararı 7/24 e-postasını takım
  bildirimi saymaz.
- **7/24 arama kaydı:** 7/24 ekibi uyarı detayından ya da listedeki "Arama kaydet" ile kim arandı (arama listesi
  sırasıyla, Takım Müdürü, diğer üyeler ya da serbest ad), ne zaman ("şimdi / 5 dk / 15 dk önce"), hangi kanaldan,
  sonuç (ulaşıldı, yanıt yok, sesli mesaj, meşgul, yanlış numara, başkasına yönlendirildi) ve not saniyeler içinde
  girer (Ctrl/⌘+Enter); telefonda alttan açılan hızlı giriş; takım ve 7/24 ekibi kayıtları zaman çizelgesinde görür,
  kayıt 15 dk içinde silinebilir; uyarı kartında "3 arama · son: Ulaşıldı 03:12 · Kişi A" göstergesi; 7/24
  e-postasındaki "Arama kaydı ekle" formu odakta açar; Olaylar detayında salt okunur özet. Yeni izin
  `noc_calls.write` (varsayılan yalnız global yönetici; önerilen kurulum: 7/24 operatörlerine AUDIT rolü + bu izin) —
  izin sahibi tüm takımların uyarılarını GÖRÜR, onaylama/çözme kapsamı değişmez. Telefon numarası hiçbir yanıtta yok.
- **Sertifika yenileme planı penceresi** (Genel Bakış kartı, Sertifika Takvimi, Uyarılar, Alan Adı izleme): tarih
  özeti (kayıtlı plan ve kim koydu, "en geç yenile", bitiş — her biri "N gün sonra" rozetiyle), bugün → en geç →
  bitiş zaman çizgisi, hızlı seçimler (en geç tarih, bir hafta, iki hafta — hep iş gününe denk gelir), seçilen günün
  özeti ("Cuma · 11 gün sonra · en geç tarihte"), uyarılar (geçmiş tarih, en geç tarihten sonra, bitişte/sonrasında,
  hafta sonu/tatil + tek tıkla "Cuma 9 Ekim'e al"), not sayacı (500), Ctrl/⌘+Enter ile kaydet, onaylı "Planı
  kaldır", kaydedilmemiş değişiklikte "Değişiklikler atılsın mı?"; telefonda alttan açılan yaprak, düğmeler başparmak
  erişiminde. ⚠ Davranış: yeni planın varsayılan tarihi en geç tarihin iş günü karşılığı (geçmişse boş); değişiklik
  yokken "Kaydet" kapalı; "Planı kaldır" dış çizgili düğme.
- **Genel Bakış sertifika kartları (Kompakt + Zengin):** izleme kartı dili — durum rozeti (kelimeyle), güvensiz ve
  katman rozetleri, alan adı kartın tamamını açan düğme (uzun ad en çok 2 satır, 443 dışı port rozeti, kopyala),
  sağlayıcı + anahtar satırı (zayıf anahtar kırmızı), takım/platform/erişim çipleri; tonlu ana panel (kalan gün, bitiş
  tarihi, KALAN geçerlilik çubuğu "90 günden 5 gün kaldı", yeni yenilendi rozeti ya da yenileme planı çipi); nedenler
  çip olarak (iptal, güvenilmeyen CA, ad uyuşmazlığı, zincir, eski sertifika sunuluyor, ara sertifika, zayıf algoritma —
  dokununca açıklama); sessiz alarm ve e-posta hatası bildirimleri; Zengin kipte Sağlık, Açık alarmlar ve 24 saat
  erişilebilirlik döşemeleri; telefonda "Şimdi kontrol et" + "Diğer işlemler" menüsü, 40 px dokunma hedefleri.
  ⚠ Davranış: süresi dolmuş sertifika "KRİTİK" yerine **"Süresi doldu"** yazar; kontrol edilmemiş kartta da alt satır
  görünür; plan kısayolu süresi dolmuş kartta da çıkar.
- **Alan Adı Süre Bitişi kartları:** sertifika kartı dili — tonlu ana panel (kalan gün, bitiş tarihi, KALAN kayıt
  süresi çubuğu "365 günden 212 gün kaldı"), bitiş bilinmiyorsa nedeniyle açık durum ("Bitiş tarihi bilinmiyor —
  RDAP 404 …"), kayıt kuruluşu satırı, koruma çipleri (transfer kilidi, DNSSEC, kara liste, NS — "2 NS · çözülmüyor"),
  EPP durum kodları kritik olanlar önde ve dokununca sade açıklama, "Değişti" çipi, yenileme planı çipi (kim koydu,
  gecikti) ve uygun olduğunda "Yenilemeyi planla" kısayolu; telefonda "Diğer işlemler" menüsü; göreli "12 dk önce".
  ⚠ Davranış: kayıt çubuğu geçen süre yerine KALAN süreyi gösterir; telefonda Düzenle/Kopyala/Sil menüye taşındı.
- **Kompakt / Zengin kart görünümü tüm izleme sayfalarında** (Genel Bakış'taki seçiciyle aynı): Kompakt = durum, hedef,
  ana ölçü, gerekirse tek satır neden, takım ve eylemler; Zengin = tam kart. İzleme sayfaları her açılışta Zengin başlar;
  Genel Bakış ilk açılışta Zengin, oturum içinde son seçimi hatırlar.
- **Port kartları:** uç nokta çipleri (`:port`, protokol TCP/TLS/HTTP/BANNER/UDP — dokununca açıklama, "genellikle
  PostgreSQL" gibi hizmet adı, IP ailesi, aralık), sonuç paneli (bağlantı kabul ediliyor / reddedildi / filtreli — zaman
  aşımı / DNS hatası / TLS el sıkışması başarısız / beklenmeyen yanıt …), protokole göre süre döşemesi (yavaşlık eşiği,
  24 saat ortalamasına göre fark), "Bağımsız / Envanterden" kaynak rozeti; envanterden gelen kayıtta sil düğmesi "İzlemeyi
  durdur (envanter kaydı kalır)".
- **HTTP / Website kartları:** adres (https gizli, düz http uyarı renginde), metot rozeti ve yalnız varsayılandan farklı
  ayarlar için çipler (şifresiz, "201 bekler", yönlendirme izlenmez, katı TLS, TLS ve bitiş alarmları — dokununca açıklama),
  HTTP durum döşemesi sunucu kararına göre renkli (beklenen 404 yeşil), yanıt süresi döşemesi (zaman aşımı, "N ms sonra
  başarısız", 24 saat ortalamasına göre fark), düşme nedeni satırı (zaman aşımı, DNS, TLS, reddedildi, güvenlik kuralı,
  "503 — beklenen 200-399").
- **Keyword kartları:** kural paneli — kural rozeti ("İçermeli", "İçermemeli", "En az N kez", "Tam bir kez"; dokununca
  ne zaman alarm verdiği), harf duyarlı çipi, anahtar kelime çipi, sonuç satırı (bulundu / bulunamadı / yasaklı kelime
  bulundu / sayfa okunamadı) + eşleşme sayısı, ilk eşleşmenin çevresi kelime vurgulanarak; düşme nedeni (zaman aşımı,
  DNS, TLS, bağlantı reddi, HTTP 4xx/5xx), HTTP durum ve yanıt süresi döşemeleri, "Her zaman vekil üzerinden" / "Her zaman
  doğrudan" çipi.
- **Sentetik İzleme kartları:** k6 / tarayıcı etiketi, hedef alan adı, son koşu paneli ("12 / 14 kontrol geçti" çubuğu,
  k6 çıkış etiketi, başarısız kontrol / zaman aşımı / eşik nedeni satırı), betik sürümü çipi (son koşu eski sürümdeyse
  uyarı), süre ve ortalama istek süresi tonlu, "Sistem tarafından devre dışı" / "Hiç başarılı olmadı" açıklamalı çipleri.
- **Sayfa Bütünlüğü kartları:** adres (https değilse şema uyarı renginde), yapılandırma çipleri (tek sayfa / tarama
  derinliği, aralık, üçüncü taraf, dışlama kuralları), bütünlük paneli ("123 / 132 kaynak sağlam" çubuğu + bozuk, zaman
  aşımı, karışık içerik çipleri), sayfa yüklenemedi / yapılandırma hatası panelleri, HTTP durum ve tarama süresi; sunucunun
  sabit Türkçe sayfa mesajları arayüz dilinde gösterilir.
- **DNS kayıt kartları:** kayıt türü rozeti, "Bağımsız" / "Envanterden" kaynak rozeti, değer paneli (ilk 3 değer
  kopyalanabilir, "+N daha", MX önceliği, uzun TXT tam değeri açılır pencerede), "Değer değişti" uyarısı, "Rotasyon —
  kesinti değil" çipi, beklenen değerlerle eşleşme / beklenmeyen değer, "Yanıt yok" durumu, okunur TTL ("5 dk"), eşiği
  aşan yanıt süresi vurgusu.
- **Sayfa Hızı kartları:** performans paneli — yükleme, TTFB, boyut ve istek sayısı için bütçe göstergeleri (değer,
  bütçeye oran çubuğu, "Bütçe 2,5 sn · %72"; %80'e kadar yeşil, %80–100 sarı, aşımda kırmızı), sunucunun ihlal işaretlediği
  ölçü vurgulanır, "bu hafta N aşım · geçen haftaya göre ▲/▼" çipi, adres alan adı öne çıkarılarak.
- **Ping kartları:** RTT ve paket kaybı büyük, tonlu döşemeler (kayıp %0 yeşil / kısmi sarı / %100 kırmızı; yavaşlık
  alarmı açıksa 24 saatlik ortalamaya göre "Yavaş"), protokol çipleri (ICMP/ICMPv6, IPv4/IPv6, paket sayısı, aralık),
  düşme nedeni satırı (zaman aşımı / DNS / ulaşılamıyor), 24 saatlik ortalama + saatlik aralık, göreli son kontrol.
- **"Kim bilgilendirilir?"** artık Yönetim Paneli → Bildirim ve alarmlar grubunda AYRI sekme: senaryo formu (takım,
  bildirim grubu, seviye, alarm türü; değiştikçe güncellenir, bağlantıyla paylaşılır), e-posta / push / webhook alıcı
  kartları (neden dahil edildiği açıklamasıyla), "Bildirilmeyenler" listesi, kimse bildirilmiyorsa yönlendirme;
  Eskalasyon Kişileri'nden "Kimin bilgilendirileceğini test et →" bağlantısı.
- **Sertifika Değişim Rehberi yeniden düzenlendi:** en üstte Kaynaklar (arama, kategori çipleri, CA portalları ilk),
  ardından 6 adımlık "CA üzerinden satın alma / yenileme" akışı; platform kurulum notları ve "Gelişmiş: kendi anahtarınızı
  ve CSR'nizi üretme" katlanır bölümlere taşındı (varsayılan kapalı); eski bağlantılar çalışır.
- **Yetki Yönetimi:** sayfa başlığı (kaynak/rol sayısı, son değişiklik), rol özet kartları, arama + tür/değişmemiş/hassas
  süzgeçleri, yapışkan başlıklı matris (ok tuşlarıyla hücre gezinme), telefonda rol seçici + kartlar; değişiklikler
  bekleyen listede toplanır, "İncele" paneli (eski → yeni, tek tek geri al) ve ilerlemeli toplu kaydet.
- **İzleme detay penceresi:** sekmeler ikon + sayı rozetiyle (Değişiklikler, Rehber ve Notlar, açık alarm), kontrol
  geçmişi özet kutucukları + tablo (telefonda kart), süre grafiğinde 6 özet kutucuğu, Rehber ve Notlar iki kart (türün
  yerleşik rehberi + takım notları), Değişiklikler sekmesi İzleme Değişiklikleri konsoluyla aynı görünüm (fark tablosu,
  geri yükle); `?monitor=<id>&mtab=changes` bağlantısı artık 9 izleme türünün hepsinde doğru sekmeyi açar.
- **Alarm Geçmişi:** istatistikler en üstte (süzgeç kutucukları), Açık / Kapalı / Tümü sekmeleri, güne göre gruplu
  eylem kartları (onayla / çöz — gerekçeli, yeniden bildir, toplu işlem), kapalı alarmlar özet satırları, detay paneli
  (zaman çizelgesi, e-posta önizleme, push teslimleri), CSV dışa aktarma, telefonda süzgeç paneli.
- **Sertifika Takvimi (Vade Takvimi):** başlık + Dışa aktar menüsü (ICS, CSV, bağlantı, yazdır), süzgeç görevi gören
  6 kutucuk (gecikmiş, ≤7 gün, 8–15, 16–30, yenileme gecikmiş, planlı), arama + faset süzgeçleri + çipler, 90/180/365
  günlük ufuk grafiği (çubuğa tıklayınca o dönem), takvimde gün paneli (sertifikalar + planla / aç / şimdi kontrol et),
  sıralanabilir liste (telefonda kartlar), takım/veren kurum içgörüleri, alan adı bitişleri bölümü; ay atlama seçici.
- **Ayarlar tam sayfa:** sayfa başlığı + yapılandırma sağlığı çipleri; masaüstünde gruplu yapışkan bölüm menüsü
  (Platform / Bildirimler / Güvenlik ve erişim / Veri ve bakım) + bölüm arama + sorunlu bölümde nokta; tablette kayan
  sekme şeridi, telefonda gruplu bölüm seçici; her bölümde aynı başlık düzeni ve tam genişlik form ızgarası; SMTP,
  LDAP, Genel, Marka ve Envanter Raporu'nda yapışkan kaydet çubuğu ("Kaydedilmemiş değişiklikler · Vazgeç / Kaydet");
  bağlantı testi sonuçları kopyalanabilir.
- **Tüm Sertifikalar süzgeç çubuğu:** büyük arama kutusu (Esc temizler) + veren kurum araması, "Takımlarım | Tüm
  takımlar", etiketli seçiciler (sahip takım, bitiş penceresi, kademe, durum, sıralama — sayılarla), Güvensiz / 443
  dışı port geçişleri, etkin süzgeç çipleri + "2 / 8 sertifika" + Tümünü temizle; telefonda "Süzgeçler (n)" paneli.
- **Sertifika penceresi** daha geniş (1200 px); sekmeler telefon dışında her genişlikte TEK satır (≥1280 px etiketli,
  altında ikon + sayı + ipucu), sekmelerde sayı rozetleri; telefonda bölüm seçici.
- **Kullanıcı menüsü:** oturum kartı (fotoğraf/baş harfler, ad, e-posta, rol, takım, son giriş), Hesap / Yardım ve
  destek / Tercihler grupları, Tema ve Dil alt menüleri (etkin seçenek işaretli), "Kenar çubuğunu daralt" (Ctrl B),
  **Çıkış** en altta; telefonda alttan açılan panel.
- Tarih/saat seçicileri shadcn Takvim + Popover (`react-datepicker` kaldırıldı); ürün turu, duyuru şeridi, haftalık
  rapor parçaları, sorun bildir penceresi shadcn.
- **Operasyon belgeleri:** `docs/PROD_DEPLOY_CHECKLIST.md`, `docs/RUNBOOK.md`, `k8s/README.md`; Helm `image.digest`
  ve `imagePullSecrets`.

### Changed
- ⚠ Davranış — **Takım üye listeleri yalnız GERÇEK üyeleri gösterir.** Yönetim Paneli → Takımlar üye penceresi,
  üyelerin müdürlerini (ve onların müdürlerini) "Müdür" rozetiyle üye gibi çiziyordu; takımın üyesi olmayan kişi
  takımın içinde görünüyordu. Takım Müdürü ayrı sütunda kalır (üyeliğe bakar: birincil takım + ek üyelikler; müdür
  süzgeci kimlikle eşleşir).
- ⚠ Davranış — **LDAP:** girişte AD artık desteklemiyorsa YALNIZ AD-kaynaklı üyelikler kaldırılır (elle/eski
  kayıtlar dokunulmaz; `site.monitor.ldap.prune-unsupported-teams`, varsayılan `true`); `manager_id` her zaman
  `manager_sicil` ile tutarlı ya da boş (AD'de müdür yoksa ikisi de temizlenir); grup OU eşlemesi tam yol bileşeni
  (`OU=ScrumGroups`; benzer adlı OU'lar sayılmaz); aynı `cn`'e sahip birden çok AD kaydında bağ kurulmaz; bayat müdür
  kaydı astın girişinde tazelenir. **Yeni yapılandırma:** `site.monitor.ldap.manager-attributes`
  (`extensionAttribute4,manager`), `site.monitor.ldap.manager-refresh-hours` (24).
- ⚠ Davranış — **Sayfa boyutları:** pencere içi listeler (kontrol geçmişi, kullanıcı dizini, olay listesi, takım hücresi,
  push penceresi, gelen kutusu geçmişi) varsayılan 10 (eskiden 25/50); 20'lik varsayılan ve `[10,20,50,100]` listeleri
  kaldırıldı.
- ⚠ Davranış — **Etkinliklerim** tarih süzgeçleri Denetim Logu ile aynı UTC ISO biçimini gönderir; **Etkinlik Günlüğü**
  telefonda ada dokunmak kartı açar, gezinme ayrı "İzlemeye git" düğmesinde; **İzleme Değişiklikleri** istatistik
  kutucukları olay türü süzgeci olarak çalışır.
- ⚠ Davranış — **Ayarlar ekranları tam genişlik** (1100/1200 px tavanları kaldırıldı); Sertifika Değişim Rehberi tam
  genişlik.
- ⚠ Davranış — **Bildirimler paneli:** çoklu seçim kutuları ve "seçilenleri işaretle/temizle" çubuğu kaldırıldı;
  yerine satır başına Okundu / Kaldır ve başlıkta "Tümünü temizle" / "Temizlenenleri göster"; varsayılan sekme
  Okunmamış. Okunma/temizleme durumu korunur (aynı tarayıcı kayıtları).
- ⚠ Davranış — **Bakım penceresi "Bitir":** tek seferlik pencerede süre şu ana kısaltılır (pencere tamamlanır);
  yinelenen pencerede seri DURAKLATILIR (listeden sürdürülür) — sunucuda "şu anki oluşumu bitir" ucu yok.
- ⚠ Davranış — **Olaylar** varsayılan görünüm Pano; "Sahiplenilmiş / Kritik / Takımsız / Takımım" hızlı süzgeçleri
  yüklü sayfaya uygulanır (sunucu süzgeci yok; sayfa bunu belirtir).
- **Komut paleti:** ilk sonuç vurgusu sonuçlar gelirken sabit kalır (Enter yanlış öğeyi açmıyordu).
- ⚠ Davranış — **Haftalık rapor:** "Durum" tek seçimi kaldırıldı, yerine durum dağılımı sayıları (`item1.status_counts`);
  eski raporlar tek durumu göstermeye devam eder, yeni haftalara taşınmaz. "Onaya gönder" artık onay ister. Yeni hafta
  raporu pencere yerine tam ekran. Yıllık özet ve CSV Dışa aktar menüsüne taşındı.
- ⚠ Davranış — **Yetki Yönetimi:** bir izni açıp kapatmak artık ANINDA kaydetmez; değişiklikler bekleyen listede
  toplanır ve "Değişiklikleri kaydet" ile gönderilir (sayfadan ayrılırken kaydedilmemiş değişiklik uyarısı).
- ⚠ Davranış — **Alarm Geçmişi:** tür başına katlanır gruplar kaldırıldı (tür sayıları Tür süzgecinde), ayrı "Bildirim
  geçmişi" penceresi yerine detay paneli; istatistikler artık varsayılan olarak açık.
- ⚠ Davranış — **Yönetim Paneli** özet şeridi kullanıcı etkinliği uyarılarını (hiç giriş yapmamış / uzun süredir
  girmemiş / kilitli) artık göstermez — bunlar Kullanıcılar sekmesinin süzgeç kutucuklarında.
- Yapışkan (sticky) öğeler hiç yapışmıyordu: telefon üst çubuğu, ayarların kaydet çubuğu, yapışkan menüler artık
  kaydırırken yerinde kalır; sağ alttaki yardım düğmesi açılır panellerin üstünde kalıp Kaydet/Uygula düğmelerini
  örtüyordu; düğme görünümlü bağlantılar mavi ve altı çizili görünüyordu; Alarm Geçmişi başlığı iki kez yazıyordu.
- **SQL Playground** hatalı sorguda veritabanının asıl mesajını (hangi sütun / hangi konum) göstermiyordu, yalnız
  "bad SQL grammar" yazıyordu — artık PostgreSQL mesajı ve konumu döner (geçmişe de bu metin yazılır).
- Grafiklerde bir çubuğa tıklayınca tüm çubukların çevresine siyah bir odak çerçevesi çiziliyor, diğer sütunlar da seçilmiş
  gibi görünüyordu (Vade ufku ve tüm recharts grafikleri).
- Saklama koşuları, dağıtım kayıtları ve sürüm notlarında "200 / sayfa" seçilince sunucu 100 satır döndürüyor, sayfa
  etiketleri kayıyor ve kayıtların bir kısmına ulaşılamıyordu — sunucu tavanı 200.
- **DNS listesi** değişen kaydın önceki değerini de döner; kart "önceki → şimdiki" gösterir.
- İzleme kartlarının mini grafiği son saatte ölçüm yokken (düşen izleme) "0ms" yazıyordu; "Kim bilgilendirilir?"de tek
  takımlı kullanıcının takımı ön seçilmiyordu.
- ⚠ Davranış — **Sertifika Takvimi:** sertifikalar takvimde BİTİŞ gününe yerleşir (eskiden yenileme-son-tarihi);
  yenileme son tarihi her satırda yazar. Günlük yoğunluk grafiği, ısı haritası, takım pastası ve 12 aylık takım yük
  grafiği kaldırıldı (ufuk grafiği + takvim + içgörüler karşılıyor); canlı saat yerine otomatik yenileme çipi.
- İpucu içindeki açılır menü / popover / pencere tetiklerinde ipucu çapasını kaybediyordu (React 18 ref zinciri);
  kayar panellerin kapat düğmesi tarayıcı-varsayılanı gri kutu, başlıkları gereğinden büyük çiziliyordu.
- **Docker:** frontend build aşaması Node 20 (EOL 2026-04-30) → **Node 24**; tüm taban imajlar tam sürüm etiketi **ve**
  digest ile sabitlendi.
- ⚠ Davranış — **Helm imaj etiketi:** `image.tag` boşken `v<AppVersion>`; `environments/master.yaml` registry'siz
  `site-monitor:1.0.0` sabitini artık taşımıyor.
- ⚠ Davranış — **Prod kaynakları (`master.yaml`):** bellek limiti 1536Mi → **3Gi**, request 512Mi → **1.5Gi**.
- ⚠ Davranış — **Giriş kilidi:** kalıcı otomatik kilit kaldırıldı; kademeler 30 sn / 2 dk / 10 dk / 30 dk, sonra her
  ihlalde 30 dk; yöneticinin elle kilidi aynen kalır.
- Tüm shadcn tabloları `border-collapse` (hücre araları ve satır kenarlıkları düzgün); varsayılan kenar rengi
  tüm öğelerde tema rengi (data-slot'suz `border` siyah çiziyordu); sınıf taşıyan listelerde tarayıcı madde işareti
  çizilmez (Markdown listeleri korunur).
- Uygulama-geneli context'ler (dil, tema, kenar çubuğu, bildirim, iletişim kutusu, yetki, marka, tur, dizinler) Vite
  HMR çift-modül senaryosuna karşı sabitlendi — geliştirme sırasında "… must be used within … Provider" çökmesi biter.

### Fixed
- **Prod:** takımın üyesi olmayan kişinin takım içinde görünmesi (yukarıdaki gösterim hatası); aynı sicile sahip iki
  kullanıcıda LDAP girişinin sessizce düşmesi ve müdür sicili girilirken 500; `manager_id`/`manager_sicil` ayrışması;
  Takım Müdürü türetmesinin yalnız birincil takıma bakması ve aynı adlı müdürleri karıştırması.
- **Sayfalama:** 4 listede derin bağlantıdaki sayfa numarası açılışta kayboluyordu; iki listede sayfa boyutu
  seçici çalışmıyordu; son sayfayı aşan sayfa "101–100" gösteriyordu; sayfa çubuğunda madde işaretleri ("•").
- **Bildirimler paneli** yüksekliğin yarısında bitiyordu; **marka logosu** Pano'yu açmıyordu; telefonda ürün turunun
  zil/yardım adımları açık menünün altında kalıyordu; kayar paneller (menü, yardım, envanter) kapanırken arka plan
  düzgün kalkmıyordu (`SheetOverlay` ref); menü ve i18n context'leri Vite HMR'da çökmeye karşı sabitlendi.
- **E-posta:** reddedilen haftalık rapor e-postasında satır sonları kayboluyordu; iki konuda `[Site Monitor]` öneki
  eksikti; 11 şablon telefonda yakınlaştırmayı engelliyordu; SMTP test e-postasında tırnak kaçışı eksikti; iki güvenlik
  e-postası marka dışı renkteydi; yönetici/rapor e-postaları yalnız HTML gidiyordu.
- Alan Adı kartında "Kritik" iki kez, DNS kartında "Duraklatıldı" iki kez görünüyordu; duyuru şeridi telefonda kayar
  menünün üstüne çiziliyordu; giriş ekranı karşılama kartı kapanmıyordu; envanter toplu işlem çubuğu koyu temada
  bozuktu; vade takvimi ve envanter satırları telefonda taşıyordu; `wr.channelName` "Alan Adı" yazıyordu.
- ⚠ Davranış — **DNS / Port izleme: duraklatılan bağımsız izlemeler listeden kayboluyordu.** Artık "Duraklatıldı"
  olarak görünür ve karttan sürdürülür. Silme ile duraklatma ayrıldı: silinen izleme hiçbir yerde görünmez ve
  çalışmaz (arama, olaylar, "Bugün" kartı, grup kullanımı dâhil); aynı hedef yeniden eklenebilir; duraklatılmış bir
  izlemenin kopyası eklenemez, mevcut izleme sürdürülmeli. Port'ta envanterden türeyen satırın onay metni artık
  "izlemeyi durdur" der (silinmez).
- ⚠ Davranış — **Yükseltme:** bu sürümden önce pasif olan bağımsız DNS/Port izlemeleri, silinmiş bir izleme geri
  gelmesin diye TEK SEFERLİK silinmiş sayılır (zaten görünmüyorlardı); işlem bir işaret tablosuyla yalnız bir kez çalışır.
- **Durum izleme alarmı kapanmıyordu:** alarm açıkken tek bir kesinti turu yaşanmışsa hedef düzelse bile alarm
  otomatik kapanmıyordu (toparlanma zinciri yeniden başlamıyordu); artık her durumda başlar, eski zincir yenisini
  etkileyemez.
- **Envanter çöp kutusu otomatik boşaltma** gece görevi sessizce hiç çalışmıyordu (işlem dışında silme); artık çalışır.
- **Sayfa Hızı kaynak kırılımı** tek işlemde yazılır; yazma hatasında son sağlam kırılım silinmez.
- **Sertifika taraması:** OCSP/CRL indirmelerine toplam süre sınırı (OCSP 15 sn, CRL 60 sn); yanıtı damla damla
  gönderen uçlar tarama iş parçacıklarını süresiz tutamaz.
- **Etkinlik Günlüğü** 7/24 bildirimi, bitiş hatırlatması, yenileme planı ve yenileme tespiti satırlarında ham anahtar
  gösteriyordu; envanter içe aktarmada bilinmeyen 7/24 grubu nedeni ham anahtardı (iki yeni eşitleme kapısı).
  7/24: "Şimdi" ile girilen aramada zamanı sunucu belirler (cihaz saati kayması hata vermez); Outlook'tan
  "Soyad, Ad" <adres> yapıştırma; grup seçicisi e-postanın gideceği gruplarla aynı seçimi gösterir; 7/24 operatörü
  başka takımın uyarısında yalnız "Arama kaydet" görür; takım müdürü/lideri kendi arama listesine ulaşır.
- **"Kim yaptı" adları:** izleme değişiklik geçmişinde değiştirenin adı, alan adı yenileme planını koyanın adı,
  bildirim grubu ve sentetik şablon oluşturanın/güncelleyenin adı boş ya da kullanıcı adı görünüyordu (oturumda
  hiç yazılmayan bir öznitelik okunuyordu); artık görünen ad. Kapı `SessionAttributeContractTest`.
- **Toplu işlem çubukları** (izleme sayfaları, Tüm Sertifikalar, Envanter) kaydırırken kartların altında kalıyordu;
  telefonda üst çubuğun altına gizleniyordu. Kompakt görünümde dar kartta Alan Adı bitiş tarihi, Sentetik ayraç ve
  Sayfa Bütünlüğü sorun çipi taşmıyor.
- **Geç yanıt yarışları:** Sorun Bildirimleri (liste başka sekmenin verisiyle eziliyor, yükleniyor göstergesi takılı
  kalıyordu), Durum İzleme ve Envanter kapsam değişimi, sekiz izleme sayfasında detay penceresi (A'nın yanıtı açık B
  penceresini değiştiriyor ya da kapalı pencereyi yeniden açıyordu), Sentetik duman testi, değişiklik geçmişi, olay
  özeti, olay trendleri, alan adı tanılama, şablonlar, kullanıcı listesi, Sistem Sağlığı veritabanı paneli (etiket ve
  gruplama artık çizilen veriden).
- **Birden çok izlemeyi aynı anda sürdürme/kontrol:** yalnız son tıklanan kartta gösterge dönüyordu; artık her kartta.
- **Özel tarih aralığı** her saniye sıfırlanıyordu; **Webhook penceresi** sürekli yeniden istek atıyordu; **alarm takım
  hücresi** hata sonrası sayfalar arasında gidip geliyor, kalıcı hatada sonsuz istek döngüsüne giriyordu; **manuel
  dağıtım** formu açıkken sıfırlanıyordu; **kontrol takımı seçimi** takım listesi gelince güncellenmiyordu.
- **Notlar sekmesi:** not formunda ya da takım rehberi düzenleyicisinde Escape tüm pencereyi kapatıp taslağı
  götürüyordu (not formunda artık formu kapatır; rehberde taslak korunur, çıkış "İptal" ile).
- **Sertifika not geçmişi** yüklenemediğinde "geçmiş yok" diyordu; artık hata gösterir.
- **Yenileme planı penceresi** Genel Bakış kartından ve Uyarılar sayfasından açılınca, gece geç saatte biten
  sertifikanın bitişini takvimden bir gün erken gösteriyordu (UTC günü); artık İstanbul günü, kapı `localDayKey.test`.
- **Giriş ekranı** tarayıcı depolaması engelliyken (gizli pencere / site verisi kapalı) açılmıyordu.
- **Erişilebilirlik:** kartlardaki "Bağlantıyı kopyala" düğmeleri hedefin adını taşır (kapı `copyLinkTargetName`);
  şifre takımı kopyalama, haftalık tamamlanma panosu, envanter tablosu ve giriş formu adları; takım rozeti dokunmatikte
  daha geniş dokunma alanı. Dokunmatik ekranda 40 px dokunma alanı: kart seçim kutusu (38 → 42 px), pencere kapatma
  düğmesi, ayarlar kaydet çubuğu, sayfalama sayfa düğmeleri ve sayfa boyutu seçici, yardım (?) simgesi.
- **Renk bozulması (Chrome/Edge):** yarı saydam kenarlık + zeminli yuvarlak öğelerin köşelerinde ters renkli
  pikseller (turuncu köşe turkuaz, mavi/mor köşe sarı). Tailwind paletinin sRGB dışı 89 rengi ekranda zaten görünen
  sRGB karşılığıyla sabitlendi; kapı `paletteGamut.test.js`.
- **Ürün turu:** "Sertifika kartı" adımı kart açılınca ilerlemiyor, "Benim yerime yap" kartı açmıyor, "Sertifika
  penceresi" adımı her zaman atlanıyordu (pencere shadcn'e taşınınca eski sınıf kalkmıştı); tur seçicileri artık
  özniteliğe bağlı ve testle pinli.
- **USER kullanıcı ekleme** akışındaki hatalar; **denetim fallback dosyası** salt-okunur kök dosya sisteminde
  yazılamıyordu (`AUDIT_FALLBACK_FILE`, varsayılan log dizini).

### Security
- **CSRF ikinci katmanı — kaynak doğrulaması:** durum değiştiren `/api/**` istekleri yalnız uygulamanın kendi
  kökeninden kabul edilir; aksi 403. **Yeni yapılandırma:** `ORIGIN_CHECK_ENABLED` (varsayılan `true`).
- **Olay görselleri:** taslak görseller yalnız yükleyen kullanıcı (ya da global görüntüleyici) tarafından
  okunabilir; olay e-postasına yalnız o olayın görselleri gömülür.
- **Denetim dışa aktarma:** takım kapsamı 5.000 / tam kapsam 50.000 satır tavanı, aynı anda en çok 2 dışa aktarma
  (fazlası 429) — bellek tüketimi kapatıldı.
- **İzleme zaman aşımları** 1–120 sn aralığına sıkıştırılır (yazma yolları + eski satırlar patch ile); tek seferde
  bir test çalışır.
- **Toplu atama / kayıt oluşturma:** sunucu yönetimli alanlar istek gövdesinden alınmaz; kayıt yalnız yönetilebilen
  takıma taşınabilir; başka takımın kaydına yazma sunucuda reddedilir (envanter, sertifika, Durum İzleme).
- **Kilit ile hizmet dışı bırakma kapatıldı;** başarısız giriş denetim satırı kanonik kullanıcı adıyla yazılır;
  `User-Agent` 512 karakterle sınırlı.
- **Alıcı e-posta adresleri loglarda maskelenir;** **giden HTTP gövde okumalarına süre tavanı**.
- Yeni AD denetim/yeniden eşitleme uçları yalnız global yönetici; üyelik kaynağı ucu görüş kapsamıyla sınırlı.
- **KRİTİK — yol hilesiyle kimlik doğrulama atlatma kapatıldı:** noktalı virgül (`;`) ya da kodlanmış yol hileleriyle
  (`%2e`, `%61`, `//`, `/../` …) oturum açmadan korumalı API uçlarına erişmek ve CSRF kaynak denetimini atlamak
  mümkündü; bu tür istek yolları artık 400 ile reddedilir, yetki kararı normalleştirilmiş yol üzerinden verilir.
- **Envanter UG takımı:** kayıt ekleme/düzenleme ya da CSV içe aktarma ile UG takımını başka bir takıma çevirmek
  (yöneticiye özel UG aktarımını atlamak) engellendi.
- **Ayarlar:** LDAP müdür niteliği, desteklenmeyen takım budaması ve müdür tazeleme süresi yalnız global yönetici
  tarafından değiştirilebilir (takım kapsamlı yönetici salt okunur görür).
- **E-posta bağlantıları:** e-postadaki bağlantılar yalnız http/https/mailto ise tıklanabilir; `javascript:`/`data:`
  gibi şemalar düz metin olarak gösterilir.
- **Kimlik sızıntısı kapısı** büyük/küçük harfe duyarsız (terimlerin yarısı fiilen devre dışıydı); bir testteki gerçek
  takım e-postası yer tutucuyla değiştirildi.
- **Bağlantı şeması beyaz listesi:** rehber kartı ve duyuru şeridi bağlantıları yalnız güvenli şemalarla (http, https,
  mailto, ağ klasörü için file) tıklanabilir; `javascript:` gibi şemalar düz metin olarak çizilir.
- **SQL Oyun Alanı "TSV kopyala"** formül enjeksiyonuna karşı korunur (CSV ile aynı kural; gerçek sayılar dokunulmaz).
- **Tarayıcıda kişisel veri:** komut paleti ve tanılama geçmişi kullanıcı başına saklanır; çıkışta ve başka kullanıcı
  girişinde temizlenir (aynı kullanıcının taslak yedeği korunur).

## [20.86.0] — 2026-09-25

### Added
- **shadcn/ui arayüz geçişi (aşama 1, 2a–2c):** shadcn/ui + Tailwind v4 varsayılan UI kütüphanesi; tüm düğmeler
  shadcn `Button`; kenar çubuğu Sidebar, gelen kutusu Sheet, komut paleti Dialog + Command, seçim ailesi
  Popover + Command; ~1100 satır legacy CSS silindi.
- **Pano platform süzgeci:** çoklu seçim, sayaçlı, "Belirtilmemiş" seçeneği, `?platform=` derin bağlantısı.

### Changed
- ⚠ Davranış — **Denetim Logu herkese açık (ekip kapsamlı):** admin/AUDIT sistem geneli; diğer roller ekip
  arkadaşlarının kayıtlarını tam ayrıntıyla (IP/konum/cihaz dâhil) görür. ADMIN/AUDIT aktörlerinin eylemleri ekip
  görünümüne girmez; bütünlük, özet istatistik ve cihaz geçmişi admin/AUDIT'te kalır.
- ⚠ Davranış — **İzleme Değişiklikleri** ekip üyelerinin değişikliklerini de gösterir (yalnız takımı boş izlemelerde).
- ⚠ Davranış — **Yetki Yönetimi herkese salt okunur;** değiştirme ve sıfırlama yalnız global admin.
- **ADMIN rolü Haftalık Raporları her zaman görür** (modül takımda kapalı olsa bile).
- Operatör notu: açılışta `audit_log(actor_team_id, event_time)` indeksi kurulur (`CONCURRENTLY` değil; büyük
  tabloda önceden kurulması: `docs/PROD_DEPLOY_CHECKLIST.md`).

### Fixed
- USER "Domain Ekle" formunda takım/grup/etiket/platform seçicileri kapalıydı — USER kayıt açamıyordu.
- Yayın öncesi regresyon taraması (R1–R17): port vekil tünelinde durum satırı 8 KB + toplam süre tavanı, hata
  varken %100 gösteren sparkline, geç yanıt yarışları (Denetim Logu, komut paleti), erişilebilirlik adları.

### Security
- Envanter düzenlemede hedef takım da yetki kapsamında doğrulanır — kapsamlı müdür bir kaydı kapsamı dışındaki
  takıma taşıyamıyor.

## [20.85.0] — 2026-09-24

### Added
- **Port İzleme kurumsal vekil üzerinden** (HTTP CONNECT tüneli) + izinli portlar ve UDP uyarısı.
  **Yeni yapılandırma:** `PROXY_CONNECT_PORTS` (varsayılan `443,8443`).

### Fixed
- Vekil tünel yanıtı bayt bayt okunur — sunucunun ilk baytları (SMTP/SSH bandı) kaybolmuyor.

## [20.84.2] — 2026-09-24

### Fixed
- "Sizin için — bugün": uzun bildirim hatası kartı taşırmıyor; kartlar aynı hizada açılıyor.

## [20.84.1] — 2026-09-24

### Fixed
- Açık alarmlar önem sırasına göre (kritik önce) dizilir.
- "Son 24 saat" şeridinde doğal İngilizce (sayı önde, tekil/çoğul); koyu kenar çubuğunda ince kaydırma çubuğu.

## [20.84.0] — 2026-09-23

### Changed
- Port ve Ping kartlarında izlenen uç nokta / protokol belirgin.

## [20.83.0] — 2026-09-23

### Changed
- İzleme kartında "x hatalı saat" yerine 1/7/15/30 gün etiketleri ve saat dilimi başına hata adedi.

## [20.82.1] — 2026-09-23

### Fixed
- "Sizin için — bugün": sıfır kartlar "Sorun yok" şeridine iner, kartlar boşuna uzamaz.

## [20.82.0] — 2026-09-23

### Added
- "Susturulmuş ve bakımda" kartı, dünden bugüne göstergesi ve son 24 saat şeridi.
  **Yeni yapılandırma:** `TODAY_TREND_SNAPSHOT_MS` (`3600000`), `TODAY_TREND_SNAPSHOT_INITIAL_MS` (`120000`).

## [20.81.0] — 2026-09-23

### Changed
- İzleme menüsüne ara başlıklar; grup açılışındaki sahte kaydırma çubuğu giderildi.

## [20.80.0] — 2026-09-23

### Changed
- Tema, dil ve "Sorun Bildir" kullanıcı menüsüne taşındı; satır kontrollerinde klavye erişimi.
- **Yeni yapılandırma:** kişi-push outbox süpürme aralığı `USERPUSH_OUTBOX_SWEEP_MS` (`60000`),
  `USERPUSH_OUTBOX_SWEEP_INITIAL_MS` (`30000`).

## [20.79.5] — 2026-09-23

### Security
- Zayıf algoritma raporu görüş kapsamına alındı (kapsam dışı takımların kayıtları görünmüyor).
- Gerçek kimlik bilgileri test fixture'larından ve bir üretim varsayılanından temizlendi.

## [20.79.4] — 2026-09-23

### Fixed
- Yayın öncesi regresyon taraması: 20 bulgu (erişilebilirlik, push, denetim, fırtına) ve beş yeni kapı; Detay
  düğmelerinin erişilebilir adı satırları ayırt ediyor; boş listede kırık ipucu cümlesi.

## [20.79.3] — 2026-09-23

### Changed
- Yalnız belge: Core Web Vitals fizibilitesi ve SRE/SLO sekmesi spesifikasyonu.

## [20.79.2] — 2026-09-23

### Fixed
- Kod denetimi turu (51 bulgu): eskalasyon, fırtına, push ve e-posta zincirlerinde düzeltmeler — fırtına bildirimi
  takım başına çözülüyor (push kanalı, mail bastırması, takım izolasyonu); beş alarm tipi telefona yanlışlıkla
  "yanıt vermiyor" diyordu; push outbox kuyruk-sonu tetiği retry backoff'unu etkisizleştiriyordu; çözüm
  e-postasının ölçü satırları eksikti.
- İptal edilmiş recovery zinciri alarmı erken kapatabiliyordu (kuşak yarışı); izlenmeyen watchdog canlı poll'ü
  öldürüyordu; çok tablolu kalıcı silme/üzerine yazma yolları tek transaction'a alındı.
- Haftalık ağırlıklı ortalama kesinti arttıkça yanıt süresini iyi gösteriyordu; saat dilimi, bakım bastırması ve
  HTML kaçışı kardeş yüzeylerde düzeltildi; döngü içi tekil sorgular toplu okumaya çevrildi.

### Security
- ⚠ Davranış — **Keyword izleme özel HTTP başlıkları artık şifreli saklanıyor** ve API düz değeri döndürmüyor
  (şifre çözme `SITE_MONITOR_SECRET_KEY`'e bağlı).
- Yetki kapıları kardeşleriyle hizalandı (ayar anahtarı, müdür bağı, geçmiş geri yükleme); başarısız-giriş görece
  anomali kuralı tamsayı bölmesi yüzünden hiç tetiklenmiyordu; güven hatası kaydı okuması artık yıkıcı değil.

## [20.79.1] — 2026-09-22

### Fixed
- Anomaliyle otomatik kapatılan senaryo izlemesi için telefona "düzeldi" gidiyor, "devre dışı" hiç gitmiyordu.
- HTTP tanı penceresi sekme dönüşünde kendiliğinden açılıyordu (+ erişilebilirlik, ölü modal katmanı).

### Security
- Yönlendirmede kimlik bilgisi sızıntısı: Basic Auth ve özel başlıklar yabancı host'a gidiyordu.
- Kullanıcı aktivite uçları giriş IP'si, konumu ve tarayıcı parmak izini her kademeye açıyordu.
- "Oturumu Sonlandır" kararı pod yeniden başlatmasında sessizce geri alınıyordu.

## [20.79.0] — 2026-09-22

### Added
- **Platform kataloğu:** sitenin nerede koştuğu (IIS / OpenShift / Kubernetes / Linux …), Ayarlar'dan yönetilir.
- Tüm Sertifikalar ve Domain Envanteri tablolarında kolon süzgeç satırı; izleme/envanter formlarında takımın
  mevcut etiketleri aranıp seçilebilir; paylaşılan sertifika penceresi.
- Başarısız HTTP/Website kontrolüne yapısal hata tanısı (evre, kaynak→hedef IP:port, bekleme, istisna zinciri).

### Fixed
- Sertifika/envanter: kapsam sızıntısı, ölü tıklama ve import veri kaybı; sonuçsuz kolon süzgeci tabloyu siliyordu;
  kayıtlı sütun görünümü yeni varsayılan sütunu gizliyordu.
- Kesinti zaman çizelgesi uyarı alarmlarını (HTTP_SSL, *_SLOW, *_EXPIRY …) kesinti saymıyor.

## [20.78.0] — 2026-09-22

### Added
- **Alan adı izleme zenginleştirmesi:** zengin kart, hızlı süzgeçler, kalan-gün trendi, CSV/PDF dışa aktarım,
  yenileme planı (planla/kaldır, gecikmiş rozeti, yenileme görülünce otomatik kapanış); hatırlatma eşikleri
  eşik başına bir kez e-posta + push gönderir.
- Vade Takvimi'ne alan adı bitişleri; haftalık takım raporuna "Alan Adı Bitişleri" bölümü; haftalık erişilebilirlik
  raporu takımın eskalasyon (TECH) kontağına da CC gider.
- Envanter formunda vekil anahtarı; envanter ve dört izleme sayfasında vekil süzgeci.

### Fixed
- `domain_expiry_reminders` tablosuna saklama politikası; yenileme planı uçları denetim izi yazar
  (`MONITOR_RENEWAL_PLANNED` / `_PLAN_CLEARED`); `domain_monitors.renewal_planned_*` için açık şema yaması.

## [20.77.0] — 2026-09-22

### Added
- ⚠ Davranış — **İzleme başına kurumsal vekil tercihi** (HTTP/Keyword/Sayfa: `AUTO` | `ON` | `OFF`); Durum
  (uptime) yoklaması envanterin vekil bayrağını onurlandırır; Page Speed'e vekil kipi (varsayılan Doğrudan).
  Çıkış yolu değişebilir — `NO_PROXY` ve egress kurallarını gözden geçirin.

### Fixed
- Yeniden başlatma sonrası açık kalan süpürme kaynaklı ağ kesintisi kaydı ilk sağlıklı süpürmede kapanır.
- Genel Bakış ilk yüklemede "Sertifika bulunamadı" yerine yükleniyor durumu; dar ekranda nav rayı; kesinti süresi ve
  yüzde biçimi dile bağlı; `use_proxy` için dört tabloya açık şema yaması.

## [20.76.0] — 2026-09-20

### Changed
- Push logu kırılım paneli v2 (oran çubuğu, tıklanır rakamlar, daraltma); Sistem Sağlığı ve simülatör düzenlemeleri.

### Fixed
- Push/SMTP logu kırılım kartları dar kartta harf harf kırılıyordu.

## [20.75.0] — 2026-09-20

### Changed
- Kullanıcı dizini satır düzeni v2 (8 sütun, yatay kaydırma yok); hesap süzgeci Durum sütun başlığında.

### Fixed
- KebabMenu açılır listesi modal perdesinin arkasında kalıyordu.

## [20.74.1] — 2026-09-20

### Fixed
- Oturum detayı dizin ve oturum kaydını birleştirir; Aktivite Logu süzgeçleri sekme değişiminde temizlenir;
  sertifika izleme bağlantısı Genel Bakış'a doğru parametreyle gider.

## [20.74.0] — 2026-09-20

### Added
- Kullanıcı dizini, standart araç çubukları, şema detayı v2, push/aktivite logu, takım toplu işlem; bildirim
  kutusu v2; bildirim grubu geçmişi sayfalı.

## [20.73.0] — 2026-09-20

### Added
- **Değişiklik Geçmişi v2** (olay beyaz listesi, tablo, çipler, sayfalama) ve her yönetim sekmesinde değişiklik geçmişi.
- **Tier bazlı eşikler** + canlı etki önizleme; "Kim bilgilendirilir?" simülatörü + eskalasyon webhook testi.
- Takım satırında varlık sayaçları, üye yönetimi, silmede etki önizleme + taşıma; kullanıcı yönetiminde özet
  şeridi, uyuyan hesap süzgeci, toplu işlem, CSV.

## [20.72.1] — 2026-09-19

### Fixed
- İzleme ve envanter formlarında Kaydet'te alt bar kaymıyor; ilk kontrol alt kaynakları doğrulamıyor.

## [20.72.0] — 2026-09-19

### Added
- Sertifika kartı zengin görünümü ve "şu an" şeridi; SMTP Gönderim Logu v2 (sunucu taraflı arama, hata analizi,
  yeniden gönderim); Webhook Push kartı + gönderim logu sayfası; "Sizin için — bugün" izleme kartları.

### Changed
- ⚠ Davranış — **Envanter erişilebilirlik (HTTP) süpürmesi 5 dk → 60 dk:** `UPTIME_INTERVAL_MS` varsayılanı
  `300000` → `3600000`. Envanter domainlerinde DOWN tespiti en fazla 12 kat gecikebilir; eski sıklık için env'i
  açıkça verin. Kullanıcılara duyurulmalı.
- ⚠ Davranış — **İzleme alarm seviyesi:** varsayılan Uyarı, izlemeden Yüksek/Kritik seçilir; eskalasyon kontakları
  seviyeye bağlı — kimin bilgilendirildiği değişebilir.
- ⚠ Davranış — **Sistem Sağlığı'nın her bölümü her kademeye salt-okunur açık;** sicil sunucuda maskelenir.

## [20.71.1] — 2026-09-18

### Fixed
- Vade Takvimi Günlük Yoğunluk çubukları tıklanır (kümülatif çizgi tıklamayı yutuyordu).

## [20.71.0] — 2026-09-18

### Added
- Yenileme Önerileri yeniden tasarımı; Durum İzleme grup/etiket süzgeci; Vade Takvimi tıklanır hücreler +
  yoğunluk özeti; Olaylar'da takım sütunu; push metni neyin dolduğunu söyler.

## [20.70.0] — 2026-09-18

### Added
- ⚠ Davranış — **USER kendi takımının sertifika kaydını düzenler** (silme/aktarma yönetici kapısında);
  "Domain Ekle" her seviyede.
- Çok takımlı takım seçimi, grup + etiket zorunluluğu, takım kilidi, etiket süzgeci.

## [20.69.0] — 2026-09-17

### Added
- Bakım hedefi seçimi: önce izleme türü, sonra o türün monitörleri.

### Fixed
- "e-posta: 0 alıcı" çipi gerçek gönderim sayısını gösterir.

## [20.68.1] — 2026-09-16

### Fixed
- Alarm Geçmişi takım kırılımı dar ekranda kırpılıyordu; iç içe `<button>` uyarısı.

## [20.68.0] — 2026-09-16

### Added
- Alarm Geçmişi: takım kırılımı, kart imza geçmişi, zenginleştirilmiş gürültü analizi.

## [20.67.0] — 2026-09-16

### Changed
- ⚠ Davranış — **Haftalık Raporlar modülü takım bazlı açılır, varsayılan KAPALI:** açılmamış takımlarda menü ve
  hatırlatmalar görünmez (20.86.0'dan itibaren ADMIN rolü her zaman görür).

### Fixed
- Açık alarm bildirimi alarmın kendisini açar; Ara ve Bildirimler kutuları aynı boyutta.

## [20.66.0] — 2026-09-13

### Changed
- Yapılandırma sağlığı kartı varsayılan kapalı gelir.

## [20.65.0] — 2026-09-13

### Added
- Haftalık rapor onayında takıma ve müdüre push bildirimi + Webhook ayarları.

### Fixed
- PO = müdür olan kişiye çift push gitmiyor; hatırlatma sayaçları sonraki koşunun haftasına bakar.

## [20.64.0] — 2026-09-13

### Added
- Haftalık Raporlar ikinci tur: yorum dizisi, hatırlatma görünürlüğü, yıl özeti, takım kanal şablonu.

## [20.63.0] — 2026-09-13

### Added
- Haftalık Raporlar zenginleştirmesi: bu-hafta şeridi, onay kuyruğu, sistem önerileri, geçen haftadan devam,
  gönderim skoru, derin bağlantı.

## [20.62.1] — 2026-09-13

### Changed
- Kullanım kılavuzuna "14.30 Ürün Turu" bölümü (TR/EN), PDF'ler yeniden üretildi.

## [20.62.0] — 2026-09-13

### Added
- **Ürün turu:** ilk girişte karşılama, 20 adımlık spot ışıklı tur, sayfa turları, başlangıç listesi; durum sunucuda
  (`/me` tour alanı, `POST /me/tour`, yönetici sıfırlama).

## [20.61.1] — 2026-09-13

### Fixed
- Toplu envanter ucunun önbellek temizliği (`@CacheEvict`) yeniden uç metodunda; Tüm Sertifikalar tablosu 10+
  sütunda hücreleri kelime kelime kırıyordu.

## [20.61.0] — 2026-09-13

### Added
- Tüm Sertifikalar zenginleştirmesi (16 madde): alarm seviyesi süzgeci, facet'ler, yeni sıralamalar, CSV, toplu
  tier/takım.

### Fixed
- Sertifika CSV dışa aktarımı ortak `Csv` kuralına (formül enjeksiyonu koruması) bağlandı.

## [20.60.2] — 2026-09-13

### Fixed
- Aynı çubuktaki düğmeler tek boyda; sütun seçici düğmesi Sıfırla ile aynı boyda.

## [20.60.1] — 2026-09-13

### Fixed
- 11 detay modalı Escape ile kapanmıyordu; 375 px'de sertifika tabloları ve akordeon başlıkları taşıyordu.

## [20.60.0] — 2026-09-12

### Added
- **Kullanıcılar / Oturumlar paneli yeniden:** kullanım, trend kartları, oturum yaşam döngüsü, anomali onayı,
  sayfa kullanım takibi, uyuyan hesaplar, kullanıcı zaman çizelgesi, dışa aktarım.
  **Yeni yapılandırma:** `PAGE_USAGE_RETENTION_DAYS` (`90`).

### Fixed
- Yeniden başlatmayı atlatan oturumlar "Aktif Oturum"dan düşüyor ve tek-oturum korumasını kaybediyordu.

## [20.59.1] — 2026-09-12

### Fixed
- Yerel gün yerine UTC günü kullanan yüzeyler (geçmiş gün ayraçları, bakım sonraki koşu, yenile-tarihi,
  takvim kovaları); 375 px taşmaları; içe aktarma önizlemesi yeni satırda takımı göstermiyordu.

## [20.59.0] — 2026-09-12

### Added
- **Vade Takvimi (Expiry Forecast) sayfası** ve `/api/forecast`: gecikenler, kapsam süzgeçleri, yenile-tarihi planı,
  planlı yenilemeler, zamanında yenileme trendi.
- **Domain Envanteri sayfası:** arama/süzgeçler, hijyen bandı, canlı durum, CSV içe aktarma, çöp kutusu otomatik temizliği.
- **Yeni yapılandırma:** `INVENTORY_AUTO_PURGE_DAYS` (`0` = kapalı), `RENEWAL_LEAD_DAYS` (`14`),
  `RENEWAL_LEAD_DAYS_T1` … `_T4` (`30`, `21`, `0`, `0`).

## [20.58.1] — 2026-09-12

### Fixed
- Tarayıcı QA turu (13 bulgu): zaman damgası/yüzde/birim biçimleri dile bağlı, takvim yerel güne göre kovalanıyor,
  "Sizin için — bugün" paneli varsayılan kapalı.

## [20.58.0] — 2026-09-12

### Added
- 25 maddelik zenginleştirme: komut paleti (Ctrl+K), bildirim kutusu, bağlamsal yardım çekmecesi, gerçek boş
  durumlar, yönetici özeti, SLA hedefine karşı 30 günlük erişilebilirlik, sekiz izleme sayfasında toplu işlemler,
  gürültü analizi, yenileme/bakım ay takvimi + ICS, envanterde domain başına kontrol sıklığı
  (saatlik / 6 sa / 12 sa / günlük / haftalık), zayıf algoritma raporu 2030 görünümü, yapılandırma sağlığı kartı.
- **Yeni yapılandırma:** `SLA_TARGET_PCT` (`99.9`), `WR_DEADLINE_DAY` (`FRI`), `WR_DEADLINE_TIME` (`15:00`).

### Changed
- ⚠ Davranış — **Haftalık rapor hatırlatması** `WR_REMINDER_CRON` varsayılanı Cuma 09:00 → **her gün 09:00**
  (teslim günü/saati ayrı anahtarlarda).

## [20.57.1] — 2026-09-12

### Fixed
- Push KPI modalında teslimat satırları sıkışıyordu.

## [20.57.0] — 2026-09-11

### Changed
- Webhook ayarları bölümleri varsayılan kapalı.

## [20.56.0] — 2026-09-11

### Added
- Webhook ayarları bölümleri açılır/kapanır (hatırlanan durum, tümünü daralt/genişlet); 24 sa / 7 g KPI kartları
  ayrıntıyı pencerede sunar.

### Changed
- Push rol grupları = sistemdeki org rolleri, her kart tek rol.

## [20.55.0] — 2026-09-11

### Changed
- ⚠ Davranış — **Push rol grupları org rolüyle eşleşir;** unvan (AD title) alıcı kuralından çıkarıldı — kimin push
  aldığı değişebilir (teşhis: Ayarlar → Webhook → "Kim alır?").

### Fixed
- ESCALATION push'u yeni seviyeyle çözülüyor (terfi push tetiğinden önce kalıcılaşıyor); sürüm popover'ında Türkçe
  süre kısaltmaları.

## [20.54.3] — 2026-09-11

### Changed
- **Yerel geliştirme oturumları kalıcı.** `start-local.ps1` artık `SPRING_SESSION_STORE_TYPE`'ı geçirir; `.env.example` `jdbc` önerir — jar yeniden başlatmalarında oturum düşmez (prod ile aynı depo).
- **Heartbeat zaman çizelgesi son kovayı düşürmüyor.** Kova sayısı yukarı yuvarlanır; son (kısmi) kovanın beklenen değeri kalan dakika kadardır — en yeni heartbeat'ler artık çizelgede görünür.
- **Kimlik sızıntısı kapısı `.gstack/` altını taramaz** (gitignore'lu QA raporları yerel `verify`'ı düşürmesin).

## [20.54.2] — 2026-09-11

### Fixed
- **İzleme modallarında React uyarısı.** Dokuz izleme sayfasında "Devamı için kaydırın" ipucuna hook nesnesi yayılıyor, `ref` fonksiyon bileşenine sızıyordu (`Function components cannot be given refs`); açık prop geçişine çevrildi, kaynak kapısı yayılımı reddeder.
- **Sertifika Sağlığı cipher çipi.** Kopyalama düğmesi satır başlığı `<button>`'unun içinde ikinci bir `<button>` idi (geçersiz DOM); `CopyButton as="span"` (klavye + stopPropagation).

## [20.54.1] — 2026-09-11

### Added
- **"Planlı yenilemeydi" onayı.** Sertifika Sağlığı'nda "sertifika değişti" uyarısının altında onay düğmesi: onay (kim / ne zaman / hangi parmak izi) kaydedilir, satır yeşile döner; parmak izi yeniden değişirse uyarı tekrar çıkar. Sunulan ≠ sabitlenen (araya girme imzası) onaylanamaz (409). Denetim olayı `CERT_RENEWAL_CONFIRMED`.

### Fixed
- **Yayın dizini `--check` sahte "bayat".** `--append` BUILD_TIME yazıyor, `--full` etiket tarihini okuyordu; saniyelik fark bir sonraki sürümü düşürüyordu. Toleranslı karşılaştırma + etiket tarihi BUILD_TIME'a sabitlendi.

## [20.54.0] — 2026-09-11

### Added
- **Sürüm & Dağıtım Geçmişi.** Nav sürüm çipi popover'ı (yayın / devreye alma / gecikme / commit / uptime / helm rev), Yardım → Yenilikler (yayın dizini, "son ziyaretinizden beri"), Sistem Sağlığı → Sürüm & Dağıtım (KPI, zaman çizelgesi, tablo, CSV, denetimden geri doldurma, elle kayıt; `release_history.read/edit`). Uygulama açılış/kapanışını `deployment_history`'ye kendisi yazar; build meta imajdan (`APP_GIT_COMMIT`, `APP_BUILD_TIME`), Helm meta Downward API'den. Metrikler `sitemonitor_build_info`, `sitemonitor_deployment_started_seconds`. Haftalık rapora dağıtım bandı; isteğe bağlı dağıtım e-postası (`site.monitor.deploy.notify.enabled`).
- **Kişi webhook "Kim alır?" paneli.** Takım + seviye seçince her üye için push kararı ve gerekçesi (grup eşleşmedi / seviye altı / opt-out / pasif / üyelik kaydı yok). Teslimat günlüğü test gönderiminden sonra kendini tazeler ve takım rozeti gösterir; 24 s / 7 g KPI kartları günlüğü süzer.
- **Takım Müdürü tek kişi** + Edit Team'de elle müdür; LDAP özyinelemeli müdür kaydı sicil alır.
- **Tanılama yetkisi** TEAM_ADMIN ve USER'a açıldı — yalnız kendi takımının envanter kayıtları için.
- **SQL Playground tablo zamanları:** oluşturma ≈ ilk görülme / son veri değişimi + detayda Zaman & Aktivite.
- **Dokuz izleme modalı:** sabit başlık + kaydırılan gövde + sabit alt bar + "Devamı için kaydırın".

### Fixed
- **Page Integrity:** pod'un SsrfGuard'ının reddettiği / kurumsal DNS'in çözemediği üçüncü-taraf link "Broken" değil "Belirsiz"; zaman aşımında retry yok (kaynak başına 16 s → 4 s).
- **CSV formül enjeksiyonu (CWE-1236):** üç dışa aktarım ortak `Csv` kuralına bağlandı; kaynak-tarayan kapı.
- My Activity "Webhook push istemiyorum" 404; kullanıcı yönetimi tablosu 11 → 7 sütun; üye kartından açılan düzenleme modalı üstte.

## [12.1.0 → 20.53.2] — 2026-05-24 … 2026-09-10 (toplu)

> Bu aralıktaki ~480 sürüm CHANGELOG'a sürüm sürüm işlenmedi; aşağıdaki maddeler o dönemde
> yayınlanan değişikliklerin kürasyonlu özetidir. Sürüm bazında tarih, commit listesi ve bump
> türü için **Yardım → Yenilikler** ekranına ya da `docs/releases/index.json`'a bakın
> (`scripts/gen-release-index.mjs`). 2026-09-10'dan itibaren `[Unreleased]` doluysa her
> release CI tarafından tarihlenir (`scripts/promote-changelog.mjs`).

### Added
- **Sayfa düzeyi "Şimdi Kontrol Et" — dokuz izleme sayfasının hepsinde.** Panodaki toplu
  kontrolün karşılığı: araç çubuğundaki düğme önce takım seçtirir, sonra seçilen izlemeleri
  sınırlı eşzamanlılıkla koşturur ve sonuçları panoyla AYNI akan tabloda gösterir (ilerleme
  çubuğu, başarılı/hatalı sayacı, duvar saati, "Durdur"). Kolonlar türe uyarlanır: HTTP durum
  kodu, yanıt süresi, DNS değeri, RTT/kayıp, kalan gün, kırık kaynak, TTFB/boyut, k6 kontrol
  sayıları. Yeni bir sunucu ucu YOK — koşum, var olan tekil tetikleme uçları üzerinden istemci
  tarafında dağıtılır; kartların kendi "kontrol ediliyor" göstergeleri de yanar. Eşzamanlılık
  tavanı tür başına: sentetikte 2 (k6 süreç havuzuyla aynı), sayfa bütünlüğü/hızında 3, diğerlerinde 6.
  Aday kümesi kullanıcının tek tek çalıştırabildiği satırlarla sınırlı; DNS'te ucın yönetici
  şartı nedeniyle düğme yalnız yöneticiye çizilir. Koşum sırasında sayfanın 60 sn'lik otomatik
  tazelemesi durur (liste altından kaymasın), biter bitmez bir kez tazelenir.
- **Marka logosu (mor turp) uygulama genelinde.** Navbar, sekme ikonu (favicon), login, yardım,
  yükleme ekranı, PDF dışa aktarımları ve dokümanlar — uygulama içinde logo daima nötr yeşildir
  (marka durumla renk değiştirmez; filo sağlığı rozet/sayaçlarda). Alarm e-postaları severity'ye
  uygun (amber/kırmızı yaprak), çözülme e-postaları "yeşile döndü" logosuyla gelir (CID inline).
  Beyaz-etiket `logo-data` override'ı varsayılanın önüne geçebilir. Kurallar: `branding/BRAND.md`.

### Changed
- **Marka yazımı: "Site Monitör" → "SiteMonitor".** Ürün adı tüm görünen yüzeylerde (login, navbar,
  sekme başlığı, e-posta konu/gövdeleri, yardım/whitepaper) tek biçim "SiteMonitor" olarak birleştirildi.
  Beyaz-etiket `app-name` override mekanizması değişmedi.
- **Ürün adı: CertMonitor → Site Monitör.** Uygulama genelinde görünen marka (giriş ekranı wordmark'ı, sekme
  başlığı, e-posta konu/altbilgileri, haftalık raporlar, webhook kartları, yardım/whitepaper dokümanları ve
  açılış konfigürasyon banner'ı) **Site Monitör** (EN: *Site Monitor*) oldu. Alan dili değişmedi: sertifika
  domain terimleri (`CertificateInventory`, `/api/certificates`, DB şeması) ve API yolları aynen korunuyor.
  `CertMonitorLogo` bileşeni `SiteMonitorLogo` olarak yeniden adlandırıldı; whitepaper PDF'leri bir sonraki
  whitepaper güncellemesinde yeni adla yeniden üretilecek. Teknik kimliklerin (paket adı, property önekleri,
  imaj/chart adları) geçişi ayrı adımlarda sürüyor.

### Added
- **Paylaşılabilir URL / derin bağlantı.** İzleme sayfalarında ekranda görünen durumun tamamı artık adres
  çubuğunda yaşıyor: takım/grup filtresi, arama metni, stat kartı filtresi, sıralama, sayfa/sayfa boyutu ve
  açık detay modalı (`/?tab=keyword&group=X&q=example&page=2&monitor=42`). URL'i kopyalayıp paylaşan herkes
  birebir aynı görünümü açar; varsayılan değerler param üretmez (temiz URL), yazım debounce'lu ve tarayıcı
  geçmişini şişirmez. Her izleme sayfasına ve detay modalına tek tıkla **"Bağlantıyı Kopyala"** butonu
  eklendi (pano + bildirim). Kapsam: 8 izleme türü + Uptime + Dashboard sertifika kartları + Olay Geçmişi +
  Alarm Geçmişi + Denetim Kaydı + Envanter. Sekme değişince önceki sayfanın paramları otomatik temizlenir;
  e-postalardaki eski `?monitor=` linkleri çalışmaya devam eder (artık modal açıkken URL'de kalıcı).
- **Tüm liste görünümlerinde standart sayfalama.** 8 izleme türü sayfası (HTTP/Ping/Port/DNS/Alan Adı/Kelime/Sayfa
  Bütünlüğü/Sentetik), Uptime, Dashboard sertifika kartları, Envanter ve Bakım Pencereleri artık sayfalanıyor —
  hiçbir görünüm 200'den fazla kaydı aynı anda render etmiyor. Tek ortak bileşen (`PaginationBar` + `usePagination`):
  sayfa boyutları **25/50/100/200** (varsayılan 50, görünüm bazında kalıcı), «İlk/Önceki/numaralı/Sonraki/Son»
  gezinme, "Sayfa X/Y · A–B / N kayıt" bilgisi, 10+ sayfada "Sayfaya git" girişi. Modal içi kontrol geçmişleri
  compact varyantla sayfalı (Sayfa/Alan Adı/Sentetik'e yeni eklendi); sunucu-taraflı listeler (Tüm Sertifikalar,
  Alarm Geçmişi, Olay Geçmişi, Denetim Kaydı, İstatistik tablosu, Kendi Kayıtlarım, Giriş Sorunları) aynı bileşene
  taşındı ve Denetim Kaydı'nda sayfa boyutu artık seçilebilir. Filtre/arama değişiminde sayfa 1'e döner; veri
  küçülünce sayfa otomatik düzeltilir; 60 sn'lik oto-yenileme sayfa konumunu bozmaz; `?monitor=` derin bağlantıları
  hedef kayıt hangi sayfada olursa olsun çalışır.
- **Senaryo İzleme (Scripted Check / k6) — 10. izleme türü.** Kullanıcı tanımlı k6 scriptleri periyodik olarak tek
  iterasyon çalıştırılır (`--vus 1 --iterations 1`) ve sonucuna göre sağlık kararı üretilir (PASS/FAIL/ERROR/TIMEOUT) —
  çok adımlı akışların (OIDC/Keycloak login, API zincirleri) uçtan uca izlenmesi. k6 GÖMÜLMEZ; imaja `K6_VERSION`
  build-arg'ıyla eklenen binary her kontrolde **kısa ömürlü, sandboxlu alt süreç** olarak koşar (`ProcessProbe` —
  SIGTERM→SIGKILL, çıktı-sınırlı; temp dosyalar her durumda silinir).
  - **Güvenlik:** `SsrfGuard.blacklistCidrs()` → k6 `--blacklist-ip` (iç ağ/loopback/link-local/cloud-metadata engeli,
    tek kaynak); env secret'ları `SecretCipher` ile şifreli saklanır (API/ekranda asla düz metin — yalnız `value_set`);
    çıktı `SecretMask.maskValues` ile maskelenir; script gövdesi sabit-kodlu-secret taraması (`hardcoded-secret-policy`).
  - **Yetki:** yeni `monitoring.scripted` izni (EDIT+EXECUTE) — varsayılan **ADMIN + TEAM_ADMIN (PO)**; USER/AUDIT'e açılmaz.
  - **Alarm:** tek tip `SCRIPTED_FAIL` (N ardışık başarısızlık → CRITICAL); mevcut confirmation/recovery + Storm +
    MaintenanceWindow + EscalationService/Email + ActivityLog zincirinden geçer (PAGE deseniyle aynı). E-postada senaryo
    adı + başarısız check listesi + maskeli çıktı kuyruğu. Micrometer: `scripted.k6.active`/`queued`.
  - **Frontend:** yeni "Senaryo İzleme" sekmesi; JS syntax-highlight editör (react-simple-code-editor + prismjs),
    env-secret (write-only) yönetimi, hazır şablonlar (OIDC/Keycloak, API zinciri, form-login), "Test Çalıştır", detay
    ekranında check-bazlı geçti/kaldı + maskeli stdout/stderr. Tüm metinler TR + EN.
  - **Config/infra:** `cert.monitor.scripted.*` (pool-size, timeout tavanları, output-tail, retention, k6-bin,
    hardcoded-secret-policy) + startup config-log; Dockerfile'a `COPY --from=grafana/k6`; helm/compose bellek limiti
    k6 süreç yüküne göre yükseltildi; `.env.example` + `CERT_MONITOR_SECRET_KEY`. Yeni tablolar `scripted_monitors` /
    `scripted_checks` (LATERAL latest, gün-bazlı retention + gece purge + günlük rollup).
- **Başlangıç "Etkin Konfigürasyon" logu (StartupLogger genişletme).** Uygulama açılışta çalıştığı TÜM etkin ayarları
  (varsayılan / `application.properties` / env-JVM override / DB `app_settings`·SMTP·LDAP birleşik NİHAİ değer) tek
  okunabilir `INFO` bloğu halinde loglar — kategorilere ayrılmış, her satırda kaynak etiketi (`[default]`/`[config]`/
  `[env]`/`[db]`/`[runtime]`). `ApplicationReadyEvent` + `@Order(HIGHEST_PRECEDENCE)` (zamanlanmış işlerden önce).
  - **Merkezî maskeleme** `SecretMask` (yeni, tek kara-liste; `AuditDiff` de buradan besleniyor): parola/secret/token/
    anahtar (`*****`), JDBC URL'e gömülü kimlik ayıklama, segment-farkında eşleşme (`keyword`/`keepalive` yanlış-pozitif
    değil). Şifreli secret'lar asla çözülmez — yalnız `password_set` / `secret-key configured` durumu.
  - **JSON modu** (`STARTUP_CONFIG_LOG_JSON=true`, varsayılan kapalı) log-toplama için; `STARTUP_CONFIG_LOG=false` ile
    kapatılabilir. DB erişilemez açılışta blok yine basılır (DB-bağımlı bölümler "okunamadı"). Gerçek uygulama sürümü
    kök `VERSION` dosyasından (`AppVersion`).
  - **Config:** `cert.monitor.version` (`APP_VERSION`), `cert.monitor.startup.config-log.enabled/json`.
- **Sayfa Bütünlüğü İzleme (Page Integrity Monitor) — 9. izleme türü.** Bir web sayfasının KOD SEVİYESİNDE
  sağlıklı yüklendiğini doğrular: jsoup ile HTML kaynak envanteri (img/CSS/JS/link/iframe/font/favicon) çıkarılır,
  her kaynak sınırlı eşzamanlılıkla doğrulanır (önce HEAD, desteklenmiyorsa GET) ve kırık kaynak / mixed content /
  yavaş kaynak tespit edilir. İki mod: **SINGLE_PAGE** (sık, ana sayfa) ve **SITE_CRAWL** (günlük, same-origin
  derinlik taraması; robots.txt + sitemap.xml uyumlu, tek site anda).
  - **Durum makinesi:** OK → DEGRADED (sayfa döndü ama sorunlu kaynak var) → DOWN (ana sayfa alınamadı). İki ayrı
    alarm tipi (`PAGE_DOWN` CRITICAL, `PAGE_INTEGRITY` HIGH) mevcut confirmation/recovery + Storm + MaintenanceWindow
    + EscalationService/Email zincirinden geçer (KEYWORD'ün çok-alarm-tipli deseniyle aynı).
  - **DEGRADED alarm politikası:** monitör başına `alertThirdParty` (varsayılan **kapalı**) — yalnız birinci-taraf
    (same-origin) kırıklar e-posta üretir; üçüncü-taraf kırıklar yalnız UI'da görünür. Mixed content her zaman alarm.
  - **SSRF:** ana sayfa + parse'tan çıkan HER kaynak + HER redirect adımı `SsrfGuard`'tan geçer (redirect'ler manuel
    izlenir → DNS-rebind/redirect-SSRF kapalı).
  - **Yeni tablolar** `page_monitors` / `page_checks` / `page_resource_issues` (yalnız sorunlu kaynaklar saklanır) —
    owner+timestamp index'leri, gün-bazlı yapılandırılabilir retention + gece batch-purge + günlük rollup baştan dahil.
  - **Frontend:** yeni "Sayfa Bütünlüğü" sekmesi (liste/form/detay); detayda durum kartları, kırık-kaynak zaman
    grafiği, filtrelenebilir sorun tablosu (tür/kaynak/bulunduğu sayfa/sorun/HTTP/süre) + CSV dışa aktarma. Aktivite
    Logu ve haftalık rapor ekosistemine otomatik dahil. Tüm metinler TR + EN.
  - **Config:** `cert.monitor.page.*` (alert-enabled, interval/crawl-interval, resource-concurrency, retention,
    user-agent, per-tip form varsayılanları) — admin UI'dan canlı. `org.jsoup:jsoup` bağımlılığı eklendi.

### Changed
- **Sayfa Bütünlüğü alarm maili yeniden düzenlendi.** "Sorunlu Kaynaklar" artık hizalı, ≤10 satır, karışmayan
  URL'ler içeren Outlook-güvenli tablo (tür etiketi + kısaltılmış URL + HTTP, `… ve N kaynak daha` özeti). Maile
  **Mod** (Tek Sayfa/Site Tarama), **Alarm Kapsamı** (üçüncü-taraf/mixed/zaman-aşımı politikası) ve **Doğrulama**
  (N ardışık kontrolde üretildi) satırları eklendi. "HATA=null" gösterimi düzeltildi (`strCtx` literal "null"/"undefined"
  değerlerini yok sayar).
- **Alarm/recovery e-postaları — StatusCake esintili iyileştirmeler.** Alarm mailine **"Neden bu e-postayı aldınız?"**
  alıcı-şeffaflık bloğu (bildirimin hangi takıma tanımlı olduğu; anti-phishing/güven). Recovery maillerinde kesinti
  süresi StatusCake tarzı kompakt saatle (`HHH:MM:SS`, ör. `000:05:25`) Türkçe metnin yanında; tutarlı **"Toplam
  Kesinti Süresi"** etiketi; net **Kesinti Başlangıcı → Yeniden Ulaşılabilir** aralığı.
- **Recovery e-postaları — şeffaflık bloğu + erişilebilirlik özeti.** "Neden bu e-postayı aldınız?" bloğu artık
  RECOVERY (çözüldü) maillerine de eklendi (`teamNames` tüm çözüm-builder'larına geçirildi). ACCESSIBILITY
  recovery'sinde **son 24s/7g uptime% + kesinti sayısı** özet kartı (`WeeklyAvailabilityReportService.availabilityLastHours`
  yeniden kullanımı; döngüsel bağımlılık olmadan EscalationService katmanında). Uptime verisi olmayan tiplerde kart atlanır.

---

## [12.1.0] — 2026-05-24

### Added
- **Dinamik Alert Seviyeleri (Backend)** — `alert_level` alanı `CertificateDto`'ya eklendi; `critical` / `high` / `warning` / `valid` / `expired` / `error` değerleri `AlertThreshold` tablosundaki eşiklere göre dinamik hesaplanıyor (sabit kodlu 7/15 gün yerine)
- **`high` Alert Seviyesi (Backend)** — İstatistik API'si artık `critical_count`, `high_count` ve `expiring_in_7_days` döndürüyor; `/api/certificates` filtreleme `high` ve `expiring7` değerlerini destekliyor
- **Modal İkon Badge'leri** — Add User (mavi, `UserPlus`/`UserCog`), Add Team (yeşil, `UsersRound`/`PenLine`), Port Monitor Edit (turuncu, `Plug`) modallerine renkli ikon badge'leri eklendi
- **AlertHistory Domain Filtresi** — `AlertHistory` bileşeni opsiyonel `domain` prop alıyor; `CertificateModal`'ın Alerts sekmesinde domain'e özel geçmiş gösteriliyor
- **Dashboard Sertifika Modalı Yeniden Tasarımı** — Koyu navy gradient başlık, durum pill'i, `Globe`/`X` ikonları, sekmeli layout (Detaylar · Uyarılar · Notlar), modal içi scroll

### Changed
- **Dashboard İstatistik Kartları** — Sıra `valid ��� warning → high → critical` olarak düzenlendi; ikonlar alarm seviyesini görsel olarak ifade edecek şekilde güncellendi (`TriangleAlert` → warning, `OctagonAlert` → high, `Siren` → critical)
- **Port Monitor Edit Modal** — `upt-modal` stili yerine uygulama genelindeki `modal-overlay` / `modal-box` / `modal-icon-hdr` standart yapısı kullanılıyor
- **Add User / Add Team Form Hizalaması** — Zorunlu alan `*` işareti ayrı flex item oluşturduğundan label metniyle hizalanamıyordu; `<span>` wrapper ile düzeltildi (6 alan)
- **Nav Menü Grupları** — Logout yapılırken `nav-groups-open` localStorage anahtarı temizleniyor; sonraki login'de MONITORING / LOGS / MANAGEMENT grupları kapalı başlıyor

### Fixed
- **`expiring7` Filtresi** — `STAT_FILTER_FN`'de `expiring7` anahtarı eksikti; tıklandığında filtre uygulanmayıp tüm sertifikalar listeleniyordu; doğru `days_remaining` koşulu eklendi
- **Modal Durum Pill Seviyesi** — Dashboard kartında "High" gösteren sertifika modal'da "Warning" gösteriyordu; `alert_level` alanı `CertificateDto`'dan modal'a prop olarak iletildi
- **Modal Scroll** — Büyük sertifika detay modal'ında scroll sayfa yerine modal içinde çalışıyor

---

## [11.0.0] — 2026-05-22

### Added
- **Soft Delete** — Domain Inventory artık fiziksel silme yapmıyor; `deletedAt` timestamp alanı ile soft delete uygulanıyor
- **Silindi Rozeti** — Soft-delete edilen satırlar tabloda soluk görünüm + "Silindi" rozeti ile gösteriliyor
- **Silinenleri Göster Filtresi** — Admin kullanıcılar için "Silinenleri Göster" toggle'ı ile sadece silinmiş domainler listeleniyor
- **Domain Geri Getirme** — Silinen bir domain "Geri Getir" butonuyla yeniden aktif hale getirilebiliyor (admin only)
- **UG Ekibi Transferi** — `POST /api/admin/inventory/{id}/transfer-ug` endpoint'i ile `ugTeamId` ayrı güncelleniyor
- **SY Ekibi Transferi** — Mevcut transfer endpoint'i `DOMAIN_TRANSFER_SY` audit log kaydı ile güçlendirildi
- **Audit Log** — `DOMAIN_SOFT_DELETE`, `DOMAIN_RESTORE`, `DOMAIN_TRANSFER_SY`, `DOMAIN_TRANSFER_UG` event'leri eklendi
- **Test coverage** — AdminControllerTest soft-delete + listInventory güncellendi; UserServiceTest yeni guard method'u; client.test.js showDeleted param testi

### Changed
- `GET /api/admin/inventory` — `showDeleted` query param eklendi (varsayılan `false`); admin silinenleri, non-admin sadece aktif kayıtları görür
- `DELETE /api/admin/inventory/{id}` — Hard delete → soft delete; `latestCheck` geçmişi korunuyor
- `InventoryManager.jsx` — Transfer butonları "SY Ekibi" ve "UG Ekibi" olarak ikiye ayrıldı; modal `transferType` ile yönetiliyor
- `UserService.deleteTeam` — `existsByTeamIdAndActiveTrueAndDeletedAtIsNull` ile soft-delete edilen kayıtlar team silme guard'ından hariç tutuluyor

---

## [10.5.0] — 2026-05-21

### Added
- **Org Role** — `AppUser` carries an organizational role (`PO`, `TECH`, `MANAGER`, `CLEVEL`); colour-coded badge in Users table and Team member chips
- **User-linked Escalation Contacts** — EscalationContact gains `user_id` FK; Add/Edit Contact form replaces manual name/email entry with a user picker; backend auto-populates name/email from linked user
- **Team Member List** — TeamManager rows expandable (▶/▼ toggle); shows all team members with org role badges
- **SearchableSelect label fix** — trigger button uses `onMouseDown`; fixes dropdown reopen bug when wrapped inside HTML `<label>`
- **AdminPanel default tab** — non-admin users land on Escalation Contacts tab by default
- **Test coverage** — `updateUser` (6 scenarios), `orgRole` edge cases, User CRUD endpoint tests, Contact+userId endpoint tests

### Changed
- `EscalationContacts.jsx` — form uses user dropdown instead of standalone name/email fields
- `AdminController.addContact/updateContact` — `applyContactFields` resolves `userId` → name/email via `AppUserRepository`
- `api.admin.acknowledgeAlert` — accepts `(id, acknowledgedBy)` and sends `{ acknowledged_by }` in POST body

---

## [6.8.0] — 2026-05-19

### Added
- **System Health — Heartbeat** — backend records a DB heartbeat every 10 minutes; alarm triggers if signal is missing for >15 minutes
- **System Health — SMTP Statistics** — 30-day delivery rate (sent/attempted); alarm below 99%; separate `countAttemptedSince` query excludes intentional SKIPPED_DISABLED entries from the denominator
- **System Health — SMTP Failure Modal** — clicking the SMTP card opens a modal listing all non-SENT notifications (FAILED + SKIPPED) with kind badge, error detail, recipient, and trigger
- **System Health — Database Stats** — DB response time (SELECT 1 latency in ms); sortable PostgreSQL table statistics (rows, table size, total size) with client-side column sort
- **System Health — DB Refresh Button** — dedicated refresh button reloads DB stats independently without reloading the full page
- **System Health — Certificate Scan Statistics** — last scan time, duration, total/warning/error counts tracked via atomic fields in SchedulerService
- **System Health — Scan Staleness Alarm** — red alarm banner when no scan has run for ≥2 hours
- **System Health — Alarm Banner** — consolidated red banner at page top when any alarm is active (scan, SMTP, heartbeat)
- **Audit Log — DOMAIN_EDIT event** — every domain edit records a structured JSON diff (`{"field":{"from":old,"to":new}}`) in the `detail` column; covers all 24+ CertificateInventory fields
- **Audit Log — Diff Viewer** — expandable row in Audit Log table shows field-level diff (old value in red, new value in green) for DOMAIN_EDIT entries
- **Audit Log — DOMAIN_EDIT badge** — amber/orange event badge distinct from create (blue) and delete (red)
- **`SystemHeartbeat` entity & repository** — new `system_heartbeat` table, auto-created by Hibernate `ddl-auto=update`
- **`ExtendedHealthService`** — new service encapsulating heartbeat recording, SMTP stats, DB latency, and table stats
- **`/api/admin/system/db-stats` endpoint** — returns PostgreSQL table statistics (row counts + sizes)
- **`/api/admin/system/smtp-logs` endpoint** — returns non-SENT notification records for the last 30 days

### Changed
- **Smart scan polling** — Check Now button polls every 2 s and detects completion via `running: true→false` transition OR `last_run` timestamp change (fixes race condition for fast scans)
- **DB table layout** — switched to `table-layout: fixed` with `<colgroup>` for stable column widths; added raw byte columns (`table_size_bytes`, `total_size_bytes`) for correct numeric sort
- **SMTP rate calculation** — rate now computed as `sent / (sent + failed)`, intentional SKIPPED_DISABLED records excluded from denominator
- **Audit Log table** — expanded from 9 to 10 columns; 10th column shows diff toggle button for DOMAIN_EDIT entries

### Fixed
- SMTP success rate showing 66% when SKIPPED_DISABLED notifications were incorrectly counted as failures
- Certificate Scan card showing "Not yet run" after Check Now due to polling race condition
- DB table column misalignment ("kaymış") resolved with fixed-layout table

---

## [1.0.0] — 2026-05-16

### Added
- **SSL/TLS Certificate Monitoring** — automated checking of certificates from an inventory file and a managed domain list
- **Certificate inventory management** — add/edit/delete monitored domains via admin UI
- **Multi-level alerting** — WARNING / HIGH / CRITICAL thresholds with configurable day counts
- **Escalation contacts** — per-role contacts (PO, TECH, MANAGER, CLEVEL) with email and Teams/Slack webhook delivery
- **Alert lifecycle** — acknowledge, re-notify, and resolve alerts with full audit trail
- **OCSP/CRL revocation checking** via BouncyCastle
- **Chain validation** — detects incomplete or broken certificate chains
- **Fingerprint & Subject pinning** — detects certificate replacements
- **Activity log** — per-run timeline with per-certificate status
- **Notification history** — full log of every email/webhook delivery attempt
- **Dashboard** with clickable stat cards and filter support
- **Dark / Light mode** with system-preference detection and localStorage persistence
- **Internationalisation** — Turkish (default) and English UI, switchable at runtime
- **Lucide React icons** replacing all emoji throughout the UI
- **Kubernetes** deployment manifests (namespace, deployment, service, configmap, secret, ingress, HPA, PDB, ServiceAccount)
- **Helm chart** `helm/cert-monitor` v0.1.0 — fully parameterised via `values.yaml`
- **Multi-stage Docker build** with separate frontend and backend stages
- **Prometheus metrics** endpoint at `/metrics`
- **Remember-me** cookie authentication (7-day token, HttpOnly, SameSite=Strict in prod)
- **PostgreSQL** as the sole database backend

### Security
- All credentials sourced from environment variables — no hardcoded secrets in code
- HTTP security headers: `X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`, `Referrer-Policy`, `Content-Security-Policy`, `Permissions-Policy`
- CORS restricted to explicitly configured allowed origins
- Container runs as non-root user (UID 1000)
- Read-only root filesystem in container
- All capabilities dropped in K8s pod security context
- TLS enforced via Ingress (`ssl-redirect: true`)
- `secret.yaml` excluded from version control (`.gitignore`); `secret.example.yaml` provided as template
- Docker Compose uses `.env` file — `.env.example` provided as template

---

[Unreleased]: https://github.com/inanmise/site-monitor/compare/v20.112.0...HEAD
[20.112.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.112.0
[20.111.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.111.0
[20.110.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.110.1
[20.110.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.110.0
[20.109.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.109.0
[20.108.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.108.0
[20.107.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.107.0
[20.106.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.106.0
[20.105.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.105.0
[20.104.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.104.0
[20.103.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.103.0
[20.102.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.102.0
[20.101.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.101.0
[20.100.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.100.0
[20.99.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.99.0
[20.98.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.98.0
[20.97.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.97.0
[20.96.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.96.1
[20.96.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.96.0
[20.95.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.95.0
[20.94.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.94.0
[20.93.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.93.1
[20.93.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.93.0
[20.92.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.92.0
[20.91.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.91.0
[20.90.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.90.0
[20.89.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.89.0
[20.88.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.88.0
[20.87.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.87.0
[20.86.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.86.0
[20.85.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.85.0
[20.84.2]: https://github.com/inanmise/site-monitor/releases/tag/v20.84.2
[20.84.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.84.1
[20.84.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.84.0
[20.83.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.83.0
[20.82.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.82.1
[20.82.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.82.0
[20.81.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.81.0
[20.80.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.80.0
[20.79.5]: https://github.com/inanmise/site-monitor/releases/tag/v20.79.5
[20.79.4]: https://github.com/inanmise/site-monitor/releases/tag/v20.79.4
[20.79.3]: https://github.com/inanmise/site-monitor/releases/tag/v20.79.3
[20.79.2]: https://github.com/inanmise/site-monitor/releases/tag/v20.79.2
[20.79.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.79.1
[20.79.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.79.0
[20.78.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.78.0
[20.77.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.77.0
[20.76.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.76.0
[20.75.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.75.0
[20.74.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.74.1
[20.74.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.74.0
[20.73.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.73.0
[20.72.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.72.1
[20.72.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.72.0
[20.71.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.71.1
[20.71.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.71.0
[20.70.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.70.0
[20.69.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.69.0
[20.68.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.68.1
[20.68.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.68.0
[20.67.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.67.0
[20.66.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.66.0
[20.65.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.65.0
[20.64.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.64.0
[20.63.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.63.0
[20.62.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.62.1
[20.62.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.62.0
[20.61.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.61.1
[20.61.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.61.0
[20.60.2]: https://github.com/inanmise/site-monitor/releases/tag/v20.60.2
[20.60.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.60.1
[20.60.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.60.0
[20.59.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.59.1
[20.59.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.59.0
[20.58.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.58.1
[20.58.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.58.0
[20.57.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.57.1
[20.57.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.57.0
[20.56.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.56.0
[20.55.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.55.0
[20.54.3]: https://github.com/inanmise/site-monitor/releases/tag/v20.54.3
[20.54.2]: https://github.com/inanmise/site-monitor/releases/tag/v20.54.2
[20.54.1]: https://github.com/inanmise/site-monitor/releases/tag/v20.54.1
[20.54.0]: https://github.com/inanmise/site-monitor/releases/tag/v20.54.0
[12.1.0]: https://github.com/inanmise/site-monitor/releases/tag/v12.1.0
[11.0.0]: https://github.com/inanmise/site-monitor/releases/tag/v11.0.0
[10.5.0]: https://github.com/inanmise/site-monitor/releases/tag/v10.5.0
[6.8.0]: https://github.com/inanmise/site-monitor/releases/tag/v6.8.0
[1.0.0]: https://github.com/inanmise/site-monitor/releases/tag/v1.0.0

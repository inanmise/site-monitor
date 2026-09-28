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

[Unreleased]: https://github.com/inanmise/site-monitor/compare/v20.89.0...HEAD
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

# PROD KAPISI — v20.211.0 · 2026-11-15

> ## HÜKÜM: **KOŞULLU GO**
>
> v20.211.0 prod'a çıkabilir. Kod seviyesinde açık KRİTİK ya da YÜKSEK bulgu yok. Test, kapsam, derleme, e2e ve yük
> kapıları yeşil; bağımlılık taramasında yalnız gerekçesiyle kabul edilen k6 bulguları kaldı (§1 G6).
> Bu kapıda bulunan YÜKSEK bulguların hepsi düzeltildi ve testle sabitlendi; kritik güvenlik açıkları da
> (Tomcat CRITICAL, Alpine paketleri) kapatıldı.
>
> "Koşullu" olmasının üç nedeni var; üçü de kod hatası değil, dağıtım ve işletim koşulu:
>
> 1. **Dağıtım listesinin [ENGEL] maddeleri.** Prod'un şu an hangi sürümde olduğu bilinmiyor. Hangi bir kerelik
>    adımların geçerli olduğu buna bağlı (§5). Ayrıca:
>    - `SITE_MONITOR_SECRET_KEY` aynen taşınmalı;
>    - NetScaler'da gövde sınırı ≥ 12 MB, zaman aşımı ≥ 960 sn olmalı;
>    - çok pod anahtarları kapalı kalmalı;
>    - k6 dosya okuma raporu boş olmalı.
> 2. **k6 ikilisindeki 6 HIGH CVE** (Go standart kütüphanesi ve `x/net`) bu sürümde kalıyor (§4-A1). Düzeltmesi k6
>    2.4.0'da var ama o sürüm üç gün önce çıktı. Prod'dan bir gün önce izleme motorunu değiştirmek bu riskten büyük.
>    İlk yama sürümünde motor sayfasındaki "aday ikili + gölge tarama" ile geçilmeli.
> 3. **OWASP bağımlılık taraması koşulamadı.** NVD API'si 524 döndü, analiz hiç başlamadı. Aynı kapsamı Trivy'nin
>    `app.jar` taraması verdi ve düzeltmeden sonra **0 bulgu** gösteriyor. OWASP iş akışına NVD API anahtarı
>    eklenip yeniden koşulmalı (§7).

| | |
|---|---|
| Denetlenen | main `00c5bc778` = **v20.211.0** |
| Kapı başlangıcı | 20.208.0 (`db8d61da9`) → kapı sırasında 20.209.0, 20.209.1, 20.210.0 ve 20.211.0 yayınlandı |
| Prod'daki sürüm | **Bilinmiyor.** Dağıtımdan önce okunmalı (§5 madde 0) |
| Yöntem | `/prod-kapisi` tam kapı (ayrıntı aşağıda) |
| Değişim | 20.208.0 → 20.211.0: 429 dosya, +26 076 / −1 943 satır; **60 yeni test dosyası** |

**Yöntem:**
- mekanik kapılar;
- 16 eksen paralel tarama;
- her YÜKSEK / ORTA bulgunun kaynakta doğrulanması;
- düzeltme;
- düzeltmelerin bağımsız incelemesi;
- eksik e2e akışlarının yazılması (17 yeni spec).

**Kapsam:** 20.121 → 20.208 arası tüm büyük geliştirmeler. Bunlara ek olarak:
- kapı sırasında biten üç iş: Sayfa Hızı 2c, gerçek AVIF, kaynak analizi önbellek sınıfları;
- kapı sırasında gelen iki istek: 10 yıllık tatil takvimi, haftalık nöbet bildirimi;
- güvenlik yamaları.

---

## 1. Mekanik kapılar

| Kapı | Kapı başı (20.208.0) | Sonuç (v20.211.0) | |
|---|---|---|---|
| G1 Backend `mvn verify` | 13 368 test | **13 624 test, 0 hata** (5 bilinçli atlanan) | 🟢 |
| G1b PostgreSQL IT (gerçek PG 16) | 199 | **209, 0 hata** | 🟢 |
| G1c k6 sözleşmesi (gerçek k6 ikilisi) | 17 | **17** (1 atlanan: eski ikili yok) | 🟢 |
| G2 Backend kapsam (JaCoCo) | taban sağlandı | **All coverage checks have been met** | 🟢 |
| G3 Frontend lint + derleme | yeşil | yeşil; `dist/`'te kaynak haritası yok (0 `.map`) | 🟢 |
| G4 Frontend birim + kapsam | 1 023 dosya / 12 826 test | **1 036 dosya / 13 115 test**; satır kapsamı %95,7 | 🟢 |
| G5 Prod derlemesi | yeşil | yeşil (bilinen büyük parça uyarısı, tembel yüklenen modüller) | 🟢 |
| G6 Bağımlılık CVE | Trivy: **70** (5 CRITICAL, 65 HIGH) | Trivy: **6 HIGH** — yalnız k6 ikilisi (§4-A1); `app.jar` 0, Alpine 0 | 🟡 koşullu |
| G6b npm audit (prod) | 1 high | 1 high: `vite` — yalnız geliştirme sunucusu, imajda yok (§4-A2) | 🟡 gerekçeli |
| G6c OWASP Dependency-Check | — | **koşulamadı** (NVD 524); Trivy jar taraması yerine geçti | 🟡 |
| G7 E2E (Playwright, 4 parça) | 1 101 | **1 374 geçti** (telefon 390 / tablet 768 dokunmatik / masaüstü 1280) | 🟢 |
| G8 Yük dumanı | — | 50 kullanıcı × 30 sn: 5 730 istek, hata **%0**, p95 **31,9 ms** (eşik 500 ms), kontroller %100 | 🟢 |
| G9 Repo hijyeni | — | izlenen log / arşiv / `.env` yok | 🟢 |
| G10 Gizli bilgi taraması | — | izlenen kodda gerçek parola / belirteç yok | 🟢 |
| G11 Docker imajı | — | çok aşamalı, digest sabit, `USER appuser`, HEALTHCHECK; **imaj açılış testi** (prod profili, salt okur kök, 1 GiB): 34 sn'de hazır, şema yamaları 0 hata, k6 sertleştirmesi doğrulandı | 🟢 |
| G12 Sürüm paritesi | — | `VERSION` = CHANGELOG = `Chart.yaml appVersion` = 20.211.0 | 🟢 |
| Helm lint + şablon denetimi | yeşil | 3 ortam + çıplak değerler; ağ geçidi süresi ≥ 960 sn kapısı | 🟢 |

**G8 notu:** k6 makinede kurulu değil. `perf/k6-smoke.js`'in aynısı Node ile koşturuldu: aynı uçlar, aynı eşikler, istek
başına 5 sn zaman aşımı. Hedef, yerel paketli jar'dı (PostgreSQL).

**G11 notu:** bir açılış testi koşusu 1 GiB sınırında bellekten öldürüldü (137), yeniden koşuda geçti. Prod sınırı
3 GiB (`master.yaml`), yani prod'u etkilemez. Test makinesindeki pay dar (§7).

## 2. Eksen karnesi

| Eksen | Bulunan (doğrulanmış) | Durum |
|---|---|---|
| E1 Eşzamanlılık / zamanlayıcı | 1 ORTA (REINDEX soket süresi), 1 CI'da yakalanan yarış (7 günlük erişilebilirlik okuması) | ✔ düzeltildi |
| E2 Güvenlik — IDOR / sızıntı | 1 YÜKSEK (aktivite ayrıntısının "son kontroller" listesi takım süzgeçsizdi), 1 DÜŞÜK (varlık kehaneti) | ✔ düzeltildi |
| E2b Güvenlik — bağımlılık / imaj | Tomcat CRITICAL + Jackson HIGH, 50 Alpine paketi | ✔ düzeltildi; k6 Go CVE'leri §4 |
| E3 Veri katmanı | 2 ORTA (geri test sayfalaması, toplu silme) | ✔ düzeltildi |
| E4 Sayı / zaman | 1 YÜKSEK (SLA hedef kararı yuvarlanmış yüzdeden), 1 ORTA (kesinti varken %100) | ✔ düzeltildi |
| E5 Alarm / bildirim | 1 YÜKSEK (açık sayfa hızı alarmı yanlış bağlam anahtarı okuyordu, kapanmıyordu), 2 ORTA (7/24 postasında maskeli kolon, alarm akışında pasif adres) | ✔ düzeltildi |
| E6 / P7 Ön yüz | 1 YÜKSEK (gövde okuması zaman sınırsızdı), 3 ORTA + 43 arayüz bulgusu (kırpılan ad, 40 px altı hedef, alan hatası yerinde değil) | ✔ düzeltildi |
| P1 Performans | 1 ORTA (erişilebilirlik kutusu her türü sıralı ve sınırsız okuyordu) | ✔ düzeltildi |
| P2 Dayanıklılık / zaman aşımı | kullanıcı kuralı: istemcide zaman aşımsız istek yok, `timeoutMs: 0` yasak, kaynak kapısı | ✔ uygulandı |
| P3 Konfig / deploy | ingress gövde 1m / okuma 60 sn uygulamadan dardı; Route zaman aşımı 30 sn | ✔ 16m / 960 sn + şablon kapısı |
| P4 Gözlemlenebilirlik | izlenen adreslerin sorgu dizesi DEBUG günlüğüne düşüyordu | ✔ maskelendi + kaynak kapısı |
| P5 Girdi / HTTP güvenliği | kimliksiz uçlarda gövde sınırsızdı | ✔ 413 sınırı |
| P6 Veri yaşam döngüsü | yeni tablolar saklama kataloğunda | ✔ |
| P8 Test boşluğu | 17 eksik uçtan uca akış (giriş / oturum, envanter, alarm eylemleri, izleme oluşturma, bakım, haftalık rapor, izin matrisi, sistem sağlığı alt görünümleri …) | ✔ 17 spec + 43 yeni birim / entegrasyon dosyası |
| P9 Bağımlılık / lisans | yeni: `@jsquash/avif` 2.1.1 (Apache-2.0) | ✔ |
| P10 Belge paritesi | CHANGELOG 20.121–20.208 operatör notu yoktu, kontrol listesi eskimişti | ✔ geriye dönük dolduruldu |

## 3. Kapı sırasında bulunan ve DÜZELTİLEN bulgular

**YÜKSEK (5) — hepsi düzeltildi, her biri testle sabit:**
1. **Aktivite ayrıntısında takım sızıntısı.**
   - Sorun: `GET /api/activity/{id}` "son kontroller" listesini takım süzgeci olmadan dönüyordu. Bir izleme başka
     takıma taşınınca eski takım yeni takımın son kontrollerini (hedef, hata, aktör) görüyordu.
   - Düzeltme: liste artık yalnız görülebilen takımların satırlarını gösteriyor.
2. **SLA kararı yuvarlanmış yüzdeden veriliyordu.**
   - Sorun: n = 43 200, 44 hata, hedef %99,9 → kesin oran %99,898. Ekranda "%99,90 · hedefte" (yeşil) görünüyor,
     ihlal sayılmıyordu.
   - Düzeltme: karar kesin orandan veriliyor (sunucu + kart); hata varken hiçbir oran %100 yazılmıyor.
3. **Sayfa hızı alarmı kapanmıyordu.**
   - Sorun: bant karar veremezken açık TTFB / bütçe alarmının sebebi, hiç yazılmayan bir bağlam anahtarından okunuyordu.
     Bu yüzden alarm kapanmıyordu.
   - Düzeltme: sebep artık eşik tablosundan okunuyor.
4. **İstemcide zaman aşımı yalnız başlıklara kadar sürüyordu.**
   - Sorun: yarı açık bir bağlantıda gövde sonsuza dek bekleyebiliyordu. Paylaşılan istekler (çevrim içi sayısı,
     bakım rozeti, 7/24 durumu) sekme ömrü boyunca donabiliyordu.
   - Düzeltme: son tarih gövde okunana kadar geçerli. Paylaşılan istek birleşmeleri 180 sn'de düşüyor.
5. **Tomcat 11.0.24'te CRITICAL CVE** (güvenlik kısıtı atlatma) ve Jackson'da HIGH'lar.
   - Düzeltme: Spring Boot 4.1.2 (aynı ana sürüm) → Tomcat 11.0.27, Jackson 2.21.7 / 3.1.7.

**İmaj güvenliği (kök neden düzeltmesi):**
- Sorun: imajın `apk add` katmanı CI'ın katman önbelleğinden hiç yenilenmiyordu; taban imaj digest'i ve komut
  değişmediği için. Alpine'in yamalanmış paketleri imaja hiç girmedi (50 bulgu; brotli CRITICAL, bind, curl, krb5,
  expat, musl, zlib).
- Düzeltme: `APK_REFRESH` her yayında katmanı yeniliyor ve `apk upgrade` çalışıyor. Trivy'de Alpine 0.

**ORTA (seçme):**
- 7/24 postası dbquery alarmında maskeli kolon değerini taşıyordu.
- Alarm akışının MAILS sütununa pasif-yalnız adresler giriyordu.
- Kimliksiz uçlarda (giriş, OTP, hata raporu) JSON gövdesi sınırsızdı → 413.
- Dışa aktarım / PDF indirmeleri zaman sınırsızdı.
- Kesinti varken "%100" gösteriliyordu.
- dbquery geri testi O(n²) sayfalıyordu ve en yeni satırları atabiliyordu.
- Türetilmiş silme 259 bin satırı tek tek siliyordu.
- REINDEX soket sınırı DDL süresinden kısaydı; her gece kopuyordu.
- İzlenen adreslerin sorgu dizesi günlüğe yazılıyordu.
- Ingress / Route sınırları uygulamanınkinden dardı.

**Kapı sırasında CI'ın yakaladıkları:**
- 7 günlük erişilebilirlik okumasında yarış: tamamlanmış okuma, haritadan çıkarılmadan önce bitiyordu ve hemen
  ardından gelen istek bayat sonucu kullanıyordu. Aralıklı düşen bir test bunu gösterdi.
- Eksikler penceresi, açık açılır menüyü kapatıyordu. Kullanıcı bir menüdeyken pencere açılıyordu; iki e2e testi
  aralıklı düşüyordu. Pencere artık menü açıkken ve kullanıcı etkileşimdeyken açılmıyor.
- DB analizi kutucuğunda SQL önizlemesi Linux yazı tipiyle telefonda kesiliyordu.

**Arayüz (e2e A + B):** 43 bulgu düzeltildi: kırpılan adlar, 40 px altı dokunma hedefleri, sunucu alan hatasının
alanın altında gösterilmesi.

## 4. Bilerek açık bırakılanlar (kabul edilmiş riskler)

| # | Konu | Neden kabul | Ne zaman |
|---|---|---|---|
| A1 | k6 2.3.0 ikilisinde 6 HIGH: Go standart kütüphanesi 1.26.8 (4) + `golang.org/x/net` 0.58.0 (2) | (1) k6 yalnız uygulamanın başlattığı, süre ve kaynak sınırlı, dump edilemez alt süreçte koşar. (2) Hedefi izlenen sitedir, dışarıdan dinlemez. (3) Kötü niyetli bir hedefin yapabileceği en kötü şey o koşuyu düşürmektir (zaman aşımıyla biter). Düzeltmeli k6 2.4.0 üç gün önce çıktı; motoru prod'dan bir gün önce değiştirmek daha büyük risk | İlk yama sürümü: Ayarlar → k6 motoru → aday ikili 2.4.0 → gölge tarama → geçiş |
| A2 | `vite` 5.x'te HIGH (geliştirme sunucusu yol geçişi, Windows launch-editor) | Yalnız `npm run dev` sunucusunu etkiler; prod imajında Vite yok (derlenmiş statik dosyalar). Düzeltmesi ana sürüm geçişi (kullanıcı kararı: ana sürüm geçişi yok) | Ayrı ana sürüm yükseltme işi |
| A3 | `AlarmFeedRows.stripUrl`: parolasında `?` olan userinfo kısmen sızabilir | Yalnız izleme adresine parola gömülmüşse; günlük maskesi ayrı ve doğru | Sonraki tur |
| A4 | Taban çizgisi GET'inde görünmeyen izlemeye 403, olmayana 404 (varlık kehaneti) | Yalnız oturum açmış kullanıcı; kimlik sızmaz | Sonraki tur |
| A5 | İletim push hataları `notification_logs`'a yazılmıyor | Push satırları kendi tablosunda kayıtlı; yalnız SMTP günlüğünde görünmez | Sonraki tur |
| A6 | Açılış CIC soket sınırı | Yalnız ilk açılıştaki ertelenmiş indeks kurulumu; sınırlı yeniden deneme var | Sonraki tur |

## 5. Dağıtım

Tek geçerli kaynak: **[`docs/PROD_DEPLOY_CHECKLIST.md`](docs/PROD_DEPLOY_CHECKLIST.md)** (tag v20.211.0'den okuyun). Bu kapıya
özgü vurgular:

**Öncesi [ENGEL]**
0. **Prod sürümünü okuyun:**
   ```bash
   helm list -A | grep -i monitor
   kubectl exec -n "$NS" "$DEPLOY" -- cat /app/VERSION
   ```
   Listedeki bir kerelik bölümler buna göre seçilir. 20.164 ve öncesindeyse çok pod geçiş bölümleri de geçerlidir.
1. **Yayın sağlığı.** v20.211.0 Release koşusunda `image-boot-test` yeşil olmalı. Trivy raporunda yalnız k6'nın 6 HIGH'ı kalmalı.
2. **Yedek.** DB yedeği + `helm get values` dökümü alınmalı.
3. **`SITE_MONITOR_SECRET_KEY` aynen taşınır.** `--reuse-values` YOK. sha256 karşılaştırmasıyla doğrulayın.
4. **NetScaler.** Uygulamanın sanal sunucusunda gövde ≥ **12 MB**, boşta / yanıt zaman aşımı ≥ **960 sn** olmalı. Elle kurulmuş
   OpenShift Route'a `haproxy.router.openshift.io/timeout=960s` eklenmeli. Daha kısa sınır uzun yönetim işlerini keser;
   sunucu işi bitirir ama arayüz "sonuç bilinmiyor" der.
5. **NetScaler / Route kendi CSP başlığını ekliyorsa** `script-src`'de `'wasm-unsafe-eval'` olmalı. Yoksa görsel aracı AVIF
   üretemez ve WebP'ye düşer. Uygulama bu değeri kendi gönderir.
6. **k6 (prod ≤ 20.177.x ise).** `GET /api/admin/scripted/file-read-report` boş olmalı. Değilse BLOCK varsayılanında o
   senaryolar koşmaz.
7. **Çok pod anahtarları kapalı, `replicaCount: 1`.** Bu sürüm tek pod'la çıkar; 1 → 2 ayrı bir adımdır.
8. **Uzun işlem kontrolü.** Dağıtımdan hemen önce `pg_stat_activity`'de 30 sn'den uzun işlem olmamalı.

**Duyurular**
- **Sayfa hızı izlemelerine iki yeni seçenek geldi**, ikisi de izleme başına:
  - **Genişletilmiş ölçüm:** yeni izlemelerde açık, mevcut izlemelerde kapalı.
  - **Büyüme alarmı:** önceki 7 günün medyanına göre ağırlık / istek artışı.
- **Haftalık nöbet bildirimi** varsayılan kapalı. Takımlar nöbet çizelgesi penceresinden kendisi açar.
- **Tatil takvimi:** 2026–2035 tatilleri tek seferlik işle eklenir. Yalnız hiç günü olmayan yıllar eklenir; elle
  düzenlenmiş yıllara dokunulmaz. Nişan `2026-11-15-holiday-calendar-10y`.
- **Kaynak analizi tüm site taraması** hedef siteye bir koşuda en çok 2 500 sayfa / 150 dk / 7 500 MB / 75 000 nesne
  indirir. Yalnız kullanıcı isteyince çalışır. Güvenlik / ağ ekibine bilgi verin.

**Sonrası**
- **İlk 15 dk:**
  - readiness 200;
  - `Şema yamaları: … 0 başarısız`;
  - `decrypt failed` 0;
  - `[K6] ✅ k6 bulundu — sürüm v2.3.0` ve sertleştirme satırı.
- **Bir kerelik işlerin nişanları:** listedeki sorgu + `2026-11-15-holiday-calendar-10y`.
- **Grafana panosu revizyon 18:** içe aktarın.
- **Kapasite:** Veritabanı büyümesi → Kapasite (GiB) girin.
- **~1 saat sonra:** açık alarm sayılarını karşılaştırın.

## 6. Geri alma

- **Yöntem:** yalnız `helm rollback <REL> <önceki revizyon> -n <NS>` (`kubectl rollout undo` YOK).
- **Ne zaman:**
  - 15 dk içinde readiness yok;
  - e-posta / LDAP / şifre çözme kırmızı;
  - açıklanamayan alarm sıçraması.
- **Hedef < 20.186.3 ve gereksiz indeks nişanı yazıldıysa** [ENGEL]: rollback'ten ÖNCE RUNBOOK → İndeks bakımı → "Geri
  alma" adımlarını uygulayın (15 `CREATE INDEX CONCURRENTLY IF NOT EXISTS`). Yoksa eski sürüm onları açılışta
  KİLİTLEYEREK kurar.
- **Hedef ≤ 20.168.0:** önce `DROP INDEX CONCURRENTLY IF EXISTS ux_audit_seq;`.
- **Bu sürümün eklediği tablolar ve kolonlar** (haftalık nöbet bildirimi, sayfa hızı genişletilmiş ölçüm / büyüme)
  eski sürümde kullanılmaz; veri kaybı yoktur. 24 saat boyunca eski sürümün okuyamayacağı ayarları (sayfa hızı büyüme
  alarmı, haftalık nöbet bildirimi) açmamak geri almayı temiz tutar.
- **Geri alma ile değişmeyenler:** gizli anahtar ve şema.

## 7. Yol haritası

**İlk yama sürümü (1 hafta):**
1. **k6 2.4.0:** aday ikili + gölge tarama → geçiş (A1). Dockerfile `K6_VERSION` + iki sağlama toplamı birlikte.
2. **OWASP Dependency-Check:** NVD API anahtarı (`NVD_API_KEY` secret) ekleyip yeniden koşun. Ayrıca `release.yml`'daki
   Trivy'yi `exit-code: 1` yapın (taban artık yalnız k6; k6 için `.trivyignore` gerekçesiyle).
3. **İmaj açılış testinin bellek payı:** test 1 GiB + `MaxRAMPercentage=75` ile koşuyor ve bir kez 137 ile düştü.
   Testi 1.5 GiB'e çıkarın ya da `MaxRAMPercentage`'ı 70'e indirin.
4. **A3–A6:** maskeleme / kehanet / iletim push izi / CIC soket sınırı.

**Orta vade (1 ay):**
- Bağımlılık PR'larını (#153 commonmark 0.32, jsoup 1.24 …) tek tek değerlendirin; ana sürüm geçişi YOK.
- ddl-auto `ALTER`'larına `lock_timeout` sarmalayıcısı (kapı P3 bulgusu).
- Alarm akışı için metrik ve bekçi kontrolü.
- Lisans dökümü / SBOM: imaj LABEL'ı; ojdbc FUTC, k6 AGPL, jsqlparser LGPL / Apache.

**Uzun vade:**
- Çok pod 1 → 2 geçişi (ayrı commit + `helm upgrade`, ön koşullar P1–P5).
- k6 için sırsız ayrı çalıştırıcı (ayrı kapsayıcı).
- Vite / React ana sürüm geçişleri (ayrı proje, kullanıcı kararıyla).

## 8. Zaman aşımı / sonsuz döngü denetimi

Kullanıcı kuralı: "zaman aşımsız istek ve sonsuz döngü olmamalı." Bu kapıda kod tabanı bu gözle ayrıca tarandı.

- **Arka uç:** bütün dış çağrılarda bağlantı ve okuma zaman aşımı + bayt tavanı var:
  - HttpClient, Socket, DNS, SMTP, LDAP, WHOIS, JDBC;
  - k6 süreci, dbquery sürücüsü, SQL Playground izleyicisi.
- **Arka uç döngüleri:** bütün `while (true)` döngüleri ilerliyor ya da sınırlı; kapanışta ortak `ShutdownDeadline` var.
  Bu kapıda eklenen sınırlar:
  - 7 günlük erişilebilirlik türlerinin okuması ≤ 20 sn;
  - REINDEX soket sınırı DDL süresine göre;
  - havuz dışı oturumlarda 10 sn bağlantı sınırı;
  - haftalık nöbet turu dakikada bir; açan takım yoksa tek sorgu.
- **İstemci:** her istek sınırlı:
  - okuma 90 sn, yazma 120 sn, yükleme 300 sn;
  - uzun uçlar adlandırılmış sabitlerle, en çok 900 sn.
  `timeoutMs: 0` yasak. Ham `fetch` / XHR / dinamik `import()` kaynak kapısından geçiyor (`clientTimeoutGate`; izin
  listesi yalnız küçülebilir, AVIF'in iki 25 sn sınırlı isteği gerekçesiyle eklendi). Gövde okuması da son tarihe bağlı.
  Uçuştaki istek birleşmeleri 180 sn'de düşüyor.
- **Yük altında (G8):** 30 sn boyunca 5 730 istekte zaman aşımı 0.
- **Sonuç:** bilinen zaman aşımsız istek ya da sınırsız döngü yok.

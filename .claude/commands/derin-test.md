---
description: Site Monitor için en titiz uçtan uca test seferberliği — certmonitor→site-monitor kalıntı denetimi, tüm test suite'leri, coverage boşluk analizi, zenginleştirilmiş yeni testlerin yazılması, çalışan uygulama doğrulaması ve final rapor.
argument-hint: [backend|frontend|rename|hizli] (opsiyonel — boş bırakılırsa TAM kapsam koşulur)
---

# /derin-test — Derinlemesine Test Seferberliği

Bu proje **certmonitor** adıyla başladı, sonradan **site-monitor** olarak yeniden adlandırıldı.
Görevin: projeyi en küçük ayrıntısına kadar test etmek — hem kalıntıları denetlemek, hem mevcut
tüm suite'leri koşmak, hem de coverage boşluklarını kapatan **zenginleştirilmiş yeni testler yazmak**.
Yüzeysel "testler geçti" raporu KABUL EDİLMEZ; her fazın kanıtı (komut çıktısı, sayı, dosya yolu) raporda yer alacak.

Kapsam argümanı: `$ARGUMENTS`
- Boş → tüm fazlar (0–8) sırayla.
- `backend` → Faz 0, 1, 2, 3, 5 (backend tarafı), 6, 8.
- `frontend` → Faz 0, 1, 2, 4, 5 (frontend tarafı), 6, 8.
- `rename` → yalnız Faz 0–1 (kalıntı denetimi + statik bütünlük) ve rapor.
- `hizli` → Faz 0, 2, 3, 4 ve rapor (yeni test yazımı ve smoke atlanır).

## Değişmez kurallar (her fazda geçerli)

1. **Hiçbir test gerçek bir dış host'a bağlanmaz.** TLS/sertifika davranışı yerel `HttpsServer` +
   BouncyCastle runtime-cert deseniyle test edilir (`HttpCheckerServiceTest`, `CertificateCheckerServiceTest`
   örnek alınır). Frontend'te gerçek `fetch` asla test runner'a sızmaz (`vi.mock('../api/client', ...)`).
2. **Coverage floor'ları ASLA düşürülmez.** Strateji "measure → floor → ratchet"tir: yeni testlerle
   ölçüm yükseldiyse floor'u YUKARI çek (backend `pom.xml` jacoco:check, frontend `vite.config.js`
   thresholds), asla aşağı değil. Kırmızıya dönen bir kapı, kapıyı gevşeterek değil test yazarak yeşile döner.
3. **Testleri geçirmek için üretim kodunu "uydurma"** — davranış hatalıysa önce kullanıcıya raporla.
   Bilinçli tasarım kararlarını (aşağıda listeli) "düzeltme".
4. Bu ortam Windows'tur: `JAVA_HOME=C:\Program Files\Zulu\zulu-25`, Maven
   `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd` (PATH'te yok), Node 24, PostgreSQL `localhost:5432`.
5. Hiçbir şeyi commit'leme; tüm değişiklikler working tree'de kalır, final raporla kullanıcı onayına sunulur.
6. `TESTING.md`'yi geçersiz kılan her değişiklikte `TESTING.md`'yi aynı anda güncelle.

## Faz 0 — Ortam doğrulama

Başlamadan kanıtla, varsayma:
- `& "C:\Program Files\Zulu\zulu-25\bin\java.exe" -version` → Java 25.
- `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd -v` → Maven 3.9.9.
- `node -v` / `npm -v` → Node 24.
- `Test-NetConnection localhost -Port 5432` → PostgreSQL ayakta mı (yalnız Faz 8 için gerekli; kapalıysa not düş, testler H2 ile yine koşar).
- `k6 version` → varsa Faz 8'de k6 smoke da koşulur; yoksa "atlandı (k6 yok)" diye rapora yaz.
- `git status --short` → kirli dosyaları başta kaydet; faz sonunda SENİN değişikliklerin dışında fark olmadığını doğrula.

## Faz 1 — certmonitor→site-monitor kalıntı denetimi

Depo genelinde (`node_modules`, `target`, `dist`, `coverage`, `.git`, `*.log`, `data/` HARİÇ) şu desenleri ara
(case-insensitive, tam kelime ŞARTI YOK):

```
certmonitor | cert-monitor | cert_monitor | CertMonitor | CERTMONITOR | certMonitor
```

Ek olarak dar kapsamlı türevleri tara: `com.certmonitor` (paket), `certmonitor` (Docker image/Helm chart/k8s
manifest adları, workflow'lar, script'ler, `.env.example`, README/WHITEPAPER/CHANGELOG/docs), `cm.` önekli
anahtarlar (frontend `sessionStorage`/`localStorage` anahtarları, i18n anahtarları).

Her bulguyu ÜÇ sınıftan birine koy ve tabloda raporla (dosya:satır + sınıf + gerekçe):

- **A) Bilinçli / dokunma:** Yerel dev PostgreSQL veritabanı adı-kullanıcısı-şifresi `certmonitor`
  (`.env`, `start-local.ps1`, `run` skill'i buna bağlı) ve `sessionStorage`'daki `cm.session.active` anahtarı
  gibi çalışma anını kıran kalemler. Bunları değiştirmek ortam/oturum kırar → yalnız raporla,
  değişiklik için açıkça kullanıcı onayı iste.
- **B) Bayat / düzelt:** Yorumlar, log mesajları, doküman metinleri, test adları, ölü kod, hata mesajları,
  başlıklar. Bunları bu koşuda düzelt ve düzeltmeyi kilitleyen test/denetim ekle (aşağıda).
- **C) Riskli / onaya sun:** Değişmesi migration ya da dış sistem koordinasyonu isteyen her şey
  (tablo/kolon adları, cookie adları, kalıcı depolama anahtarları, Helm release adları, image adları).

Denetimi **kalıcılaştır**: `backend/src/test/java/...` altına bir `NamingConsistencyTest` (kaynak ağacını
tarayıp sınıf A istisna listesi dışında `certmonitor` geçen satır bulursa fail eden bir JUnit testi) ve
frontend'e eşdeğer bir `naming-consistency.test.jsx` ekle — böylece rename bir daha sessizce geri sızamaz.
İstisna listesi testin içinde açıkça, gerekçeli tutulur.

## Faz 2 — Statik bütünlük denetimi

- Backend derleme: `mvn.cmd -f backend/pom.xml -B clean compile` temiz mi, yeni warning var mı.
- Frontend: `npm ci` → `npm run lint --if-present` → `npm run build` (dist üretimi hatasız mı).
- **i18n paritesi:** `npx vitest run src/test/i18n-parity.test.jsx` — TR↔EN anahtar eşliği, boş değer yok,
  placeholder sayıları eşit. Faz 6'da eklenen her yeni anahtar iki dile birden girer.
- **VERSION tutarlılığı:** `./VERSION` ↔ `frontend/vite.config.js` (build-time okuma) ↔ `helm/site-monitor/Chart.yaml`.
  (`pom.xml` sürümünün bilinçli olarak senkron dışı olduğunu unutma — bug değil.)
- **Konfig paritesi:** `application.properties` / `application-prod.properties` / `application-local-pg.properties`
  anahtarları ile `.env.example` ve `k8s/secret.example.yaml` şablonlarını karşılaştır; kodda okunan ama hiçbir
  şablonda bulunmayan env değişkeni varsa raporla.
- **PermissionCatalog denetimi:** kodda kullanılan her `resource_key` `PermissionCatalog.ALL` içinde mi
  (CLAUDE.md kuralı) — grep ile çapraz kontrol et, eksik varsa Faz 6'da testiyle birlikte ekle.
- Helm lint: `helm lint helm/site-monitor -f helm/site-monitor/environments/<env>.yaml` (üç env dosyası için de).

## Faz 3 — Backend tam suite (CI birebir)

```powershell
$env:JAVA_HOME = "C:\Program Files\Zulu\zulu-25"
D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd -f backend/pom.xml -B clean verify
```

- `verify` hem Surefire'ı hem **jacoco:check kapısını** koşar — ikisi de geçmek zorunda.
- Sonuçları SAYIYLA raporla: koşulan/geçen/atlanan test adedi (~1426 bekleniyor; belirgin düşüş = şüphe),
  `target/site/jacoco/index.html` üzerinden bundle line/instruction yüzdeleri ve risk-kritik sınıfların
  (SsrfGuard, MonitoringOutageService, EscalationService, DomainCheckerService, RdapDomainExpiryService,
  CertificateCheckerService, PageCheckerService) tek tek line coverage'ı.
- Atlanan (skipped/assumption) testleri listele ve NEDENİNİ yaz (ör. k6 binary'si yoksa
  `ScriptedCheckerService` entegrasyonları `assumeTrue` ile atlanır — bu normaldir, gizleme).
- Kırmızı test → önce kök neden analizi (Surefire XML stack trace), sonra düzeltme; asla `@Disabled` ile susturma.

## Faz 4 — Frontend tam suite (CI birebir)

```powershell
cd frontend
npm ci
npm run test:coverage      # Vitest thresholds kapısı da burada zorlanır
npm run build
```

- Sayılarla raporla: test dosyası/test adedi (~282 bekleniyor), statements/branches/functions/lines yüzdeleri,
  `src/utils/incidentMeta.js`'in %100 kilidinin korunduğu.
- Konsola sızan uyarıları (act() uyarısı, unhandled rejection, gerçek fetch denemesi) sıfır tolerans ile ele al.

## Faz 5 — Coverage boşluk analizi (yazılacak testlerin haritası)

JaCoCo HTML/XML ve Vitest coverage raporlarını AÇ ve oku; tahmin etme:
- Backend: line coverage'ı en düşük 10 sınıfı ve risk-kritik sınıflardaki kırmızı branch'leri listele
  (özellikle: hata/exception dalları, retry/timeout yolları, proxy karar mantığı, tarih-sınır aritmetiği).
- Frontend: hiç testi olmayan bileşenleri ve `src/utils` içindeki kapsanmamış fonksiyonları listele.
- Her boşluğa öncelik ver: (1) sertifika/alarm/eskalasyon yolu, (2) auth/oturum/güvenlik, (3) veri bütünlüğü,
  (4) UI davranışı. Faz 6 bu haritayı yukarıdan aşağı tüketir.

## Faz 6 — Zenginleştirilmiş test yazımı (işin kalbi)

Konvansiyonlar — birebir uy:
- **Backend:** paket düzenini `src/test/java` altında aynala; birim izolasyonu için
  `@ExtendWith(MockitoExtension.class)`; `@SpringBootTest`'e yalnız tam context şartsa çık. Sınıf adı `SUT + Test`.
  AssertJ kullan. Ağ davranışı için yerel `HttpsServer` + BouncyCastle deseni.
- **Frontend:** `src/test/` altında `.test.jsx`; HER render `test-utils.jsx#render()` üzerinden (i18n + theme
  provider'ları); API `vi.mock('../api/client', ...)` ile mock'lanır. Yeni i18n anahtarı → TR ve EN birlikte.

En küçük ayrıntı ilkesi — her hedef sınıf/bileşen için asgari şu boyutları kapsa:
- **Sınır değerleri:** tam eşik günü (`days == crit`, `days == warn`), 0, negatif, `null`, boş liste, tek eleman.
- **Saat dilimi tuzağı:** Europe/Istanbul (UTC+3, DST yok) ↔ UTC 3 saatlik kayma; gün-sınırı aritmetiğinde
  `setUTC*`/UTC beklentisini pinleyen testler (23:30 UTC ≈ ertesi gün 02:30 İstanbul vakası).
- **Bilinçli tasarım kararlarını PİNLE (düzeltme, koru):** günlük re-alert dedupe; toplu ağ kesintisinde bireysel
  alarm bastırma (`NETWORK_ERROR_THRESHOLD`/`NETWORK_MIN_ERRORS`); yalnız NETWORK sınıfı hataların 1 kez retry
  edilmesi (SSL/DNS/CERT asla); `activeSessionId == null` = grandfathered (kicked DEĞİL); `TERMINATED:<uuid>`
  sentineli hiçbir gerçek session ile eşleşmez; müdür eskalasyonunun yalnız CRITICAL domain expiry'de eklenmesi;
  progressive lockout basamakları 30s→120s→600s→1800s; login'de ikinci canlı oturum → 409, süperseed edilen
  istek → 401; standalone monitör alarmlarında takımın `AlertEvent.teamId`'den (envanterden DEĞİL) çözülmesi.
- **Güvenlik dalları:** SSRF guard (metadata IP'leri, iç ağ CIDR'ları), IDOR/sahiplik izolasyonu
  (controller'larda başka takımın kaydına erişim → 403/404), secret maskeleme (TR+EN alan adları:
  `password|parola|sifre|token|secret|otp|pin`), `mustChangePassword` iken yalnız 4 izinli path.
- **Hata yolları:** timeout, bağlantı reddi, bozuk yanıt (malformed JSON/RDAP), 429-retry, proxy fallback,
  Assumptions ile ortam-bağımlı atlama.
- **Frontend detayı:** loading/empty/error state'leri, TR ve EN'de render, dark theme'de render,
  401→`/?session=expired` yönlendirme bayrağı, tarih formatlama (Europe/Istanbul).

Her yeni test önce KIRMIZI çalıştırılarak doğrulanır mı sorusunu sor: davranışı gerçekten pinliyor mu, yoksa
her koşulda mı geçiyor? (Mutasyon mantığı: assert'i tersine çevirdiğinde fail etmeyen test, test değildir.)

Faz kapanışı: `mvn -B clean verify` + `npm run test:coverage` TEKRAR koşulur; yeni ölçümlere göre floor'lar
**yukarı** ratchet'lenir (`pom.xml` jacoco limitleri, `vite.config.js` thresholds), `TESTING.md`'deki tablo ve
sayılar güncellenir.

## Faz 7 — Çapraz tutarlılık denetimleri

- `OPERATIONAL_FIELDS` (InventoryManager.jsx) ↔ `AdminController.buildInventoryDiff` ↔ `updateInventory`
  setter'ları: üçlü zincirde eksik halka var mı (CLAUDE.md'deki "field kaydediliyor ama reload'da kayboluyor" bug'ı)?
  Eksikse hem düzelt hem zinciri pinleyen test ekle.
- `EscalationService` üç yolu (`processConfirmedOutage` / `sendResolutionNotification` / `reNotify`) aynı
  `isStandaloneMon` + `includeManagerContacts` kararlarını veriyor mu — üçünü aynı senaryo setiyle test et.
- `applySchemaPatches()`: entity'lerde olup patch'i olmayan yeni kolon var mı (taze DB'de değil, ESKİ şemadan
  yükseltmede kırılır) — kolon listesini patch listesiyle karşılaştır.
- i18n: kodda `useT('...')` ile çağrılan her anahtar dict'lerde var mı (parity testi yalnız TR↔EN bakar,
  kullanılmayan/eksik anahtara bakmaz — bunu da tarayan bir test ekle, yoksa).

## Faz 8 — Çalışan uygulama doğrulaması (smoke)

PostgreSQL ayaktaysa:

```powershell
$env:JAVA_HOME = "C:\Program Files\Zulu\zulu-25"
D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd -f backend/pom.xml package -DskipTests
cd frontend; npm run build; cd ..
.\start-local.ps1                                  # /health = UP bekler; log: backend\app.log
pwsh ./scripts/smoke.ps1 -BaseUrl http://localhost:8080
```

Ek el doğrulamaları (smoke script'inin üstüne):
- `Invoke-RestMethod http://localhost:8080/health` → `status: UP, db: UP`.
- `/` → `<html lang="tr">` servis ediliyor.
- Yanlış kimlik → **401**; doğru kimlik (`user`/`password`, `.env`) → 200; aynı kullanıcıyla ikinci login → **409**.
- `backend\app.log` ve `app-err.log`'da ERROR/stack trace taraması — "uygulama açıldı" yetmez, log temiz olacak.
- k6 kuruluysa: `k6 run -e BASE_URL=http://localhost:8080 ./perf/k6-smoke.js` (hata oranı <%1, p95 <500ms).
- Bitince süreçleri kapat: `Get-Process java | Stop-Process -Force` (start-local.ps1 zaten tüm java'yı öldürür — kullanıcıyı uyar).

## Faz 9 — Final rapor

Tek bir yapılandırılmış rapor üret (sohbete yaz; istenirse `docs/` altına md olarak da bırak):
1. **Özet tablo:** faz × sonuç (✅/⚠️/❌) × kanıt (sayı/yol).
2. **Rename denetimi:** A/B/C sınıflı bulgu tablosu; B'lerin düzeltildiği, C'lerin onay beklediği.
3. **Test envanteri:** önce/sonra test adetleri ve coverage yüzdeleri (backend bundle + risk-kritik sınıflar,
   frontend global + kilitli dosyalar); ratchet'lenen yeni floor'lar.
4. **Yazılan yeni testler:** dosya dosya, her birinin pinlediği davranış tek cümleyle.
5. **Bulunan gerçek hatalar:** davranış hatası vs. test borcu ayrımıyla; düzeltilenler ve kullanıcı kararı bekleyenler.
6. **Kalan boşluklar ve sıradaki adımlar:** TESTING.md yol haritasına (Phase 2/3: Testcontainers `it` profili,
   vitest-axe, Playwright E2E, Cucumber) bağlanmış somut öneriler.

Rapor dili Türkçe; komutlar/yollar/sınıf adları olduğu gibi bırakılır.

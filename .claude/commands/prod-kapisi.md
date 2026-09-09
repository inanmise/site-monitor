---
description: SiteMonitor için YAYIN ÖNCESİ SON KAPI (production gate) — sıfır kod-seviyesi bug hedefiyle, tek turda (1) mekanik kapılar (build/test/lint/kapsam/CVE/repo hijyeni), (2) /bug-denetle'nin 6 ekseni + /bug-regresyon'un 17 imza sınıfı, (3) 10 yeni enterprise ekseni (performans, dayanıklılık/timeout, konfig-secret-deploy, gözlemlenebilirlik, girdi doğrulama/API sözleşmesi/HTTP güvenlik başlıkları, veri yaşam döngüsü/çoklu-replika, frontend yayın hazırlığı, test boşluğu, bağımlılık/lisans, dokümantasyon paritesi) paralel ajanlarla tarar; her YÜKSEK/ORTA bulguyu kaynakta doğrular; GO / KOŞULLU GO / NO-GO hükmü, eksen karnesi, yayın kontrol listesi (öncesi/sırası/sonrası/geri-alma) ve kısa-orta-uzun vadeli sertleştirme yol haritasıyla tek rapor üretir. Kullanıcı "prod'a çıkmadan son kontrol", "release gate", "yayına hazır mıyız", "kusursuz/enterprise seviye son denetim" dediğinde.
argument-hint: [hizli|mekanik|kod|duzelt|eksen:<ad>] (opsiyonel — boş = TAM kapı: mekanik + 16 eksen + imza süpürmesi + hüküm)
---

# /prod-kapisi — Yayın Öncesi Son Kapı (Production Gate)

Görevin: SiteMonitor'ü (`backend/src/main` ~340 Java + `backend/src/test` ~240 Java +
`frontend/src` ~400 JS/JSX; Java 25 · Spring Boot 4.1 · React/Vite · PostgreSQL/H2 · OpenShift)
**yayına çıkmadan önceki son ve en titiz denetimden** geçirip tek bir hükme bağlamak:
**GO / KOŞULLU GO / NO-GO**. Hedef "sıfır kod-seviyesi bug, enterprise kalitede, performanslı"
bir ürün. Bu komut bir **kapı**dır: bulgu listesi üretmekle kalmaz, yayına çıkılıp çıkılamayacağını
kanıtlarıyla söyler ve eksik olan her şeyi somut düzeltme + öncelik + yol haritasıyla verir.

Bu komut `/bug-denetle` (geniş keşif) ve `/bug-regresyon`'un (imza süpürmesi) **birleşimi + üstü**:
onların yaptığını yapar, ÜSTÜNE mekanik kapıları koşturur, 10 yeni enterprise ekseni ekler ve
sonunda karar verir. Kullanıcı ayrıca istemedikçe **kod değiştirmezsin** (`duzelt` argümanı hariç).

Kapsam argümanı: `$ARGUMENTS`
- Boş → **TAM kapı**: Faz 0–5'in tamamı.
- `hizli` → Faz 1 mekanik kapılar + yalnız KRİTİK/YÜKSEK-olasılıklı eksenler (E2 güvenlik, E5 alarm,
  P3 konfig/secret/deploy, P5 girdi/API/HTTP güvenlik) + baseline re-check. Hüküm yine verilir ama
  "hızlı kapı" diye etiketlenir.
- `mekanik` → yalnız Faz 1 (build/test/lint/kapsam/CVE/hijyen). Ajan taraması yok.
- `kod` → yalnız Faz 2–3 (ajan taraması + doğrulama). Mekanik kapılar CI'dan okunur, koşturulmaz.
- `eksen:<ad>` → tek eksen derinlemesine (ör. `eksen:P2`, `eksen:E2`, `eksen:P7`).
- `duzelt` → TAM kapı + Faz 6: rapor sonrası kullanıcı onayıyla KRİTİK/YÜKSEK bulguları fiilen
  düzelt, her düzeltmeye **yeni** test yaz, mevcut testleri değiştirme, `mvn verify`+`npm test`
  yeşil kalsın.

## Değişmez kurallar

1. **Kaynağı konteynere al veya doğrudan mount'ta çalış.** Tercih: `tar czf _review_src.tar.gz
   backend/src backend/pom.xml frontend/src frontend/package.json frontend/vite.config.* helm k8s
   Dockerfile .github .env.example CLAUDE.md` → `device_stage_files` → `/tmp/sm`'e aç; ajanlar
   `/tmp/sm` üzerinde `Grep`/`Read` ile çalışır. Konteyner erişimi yoksa ajanlar doğrudan mount'ta
   çalışır (5. turda böyle yapıldı — kabul edilebilir). **Bitince** `_review_src.tar.gz`'yi hem
   konteynerden hem device'tan kaldır (`_to_delete/`'ye taşı) — 5. tur bulgu 22'yi tekrarlama.
2. **Mekanik kapı başarısızsa hüküm otomatik NO-GO'dur.** Derleme hatası, kırmızı test, lint
   hatası, kapsam tabanı altı, YÜKSEK/KRİTİK CVE (suppress edilmemiş), commit edilmemiş iş,
   VERSION↔CHANGELOG uyumsuzluğu → doğrudan NO-GO. Ajan taraması yine koşar (raporun değeri
   için) ama hüküm değişmez.
3. **Paralel ajanlar, tek mesajda.** Eksenleri bağımsız ajanlara böl; HEPSİNİ tek `Agent` çağrı
   bloğunda başlat. Her ajan prompt'u: eksen tanımı + §Yanlış-pozitif listesi + baseline "tekrarlama,
   düzeltilmiş mi BAK" notu + çıktı formatı + "kodu OKUYARAK doğrula, spekülasyon yok" talimatı +
   en fazla ~12 bulgu. Ağır eksenleri böl (E2a IDOR / E2b SSRF+yetki; P3a konfig / P3b deploy).
4. **Doğrulama zorunlu.** Ana ajan her **KRİTİK/YÜKSEK/ORTA** bulguyu kaynakta açıp okur; dosya:satır,
   çağrı zinciri ve somut senaryo (mümkünse repro adımı / istismar isteği) teyit edilmeden o
   önemle rapora girmez. Teyit edilemeyen → DÜŞÜK + "doğrulanmalı". **Kardeş-karşılaştırma** en
   güçlü kanıttır ("7 uç X yapıyor, bu ikisi yapmıyor").
5. **Baseline'ı RE-CHECK et.** `project_memory_read bug_denetimi_2026_08.md` + en son
   `BUG_RAPORU_*.md` / `DOGRULAMA_*.md` dosyalarını OKU. Her önceki madde: ÇÖZÜLMÜŞ ✓ /
   AÇIK / **REGRESYON ⚠** (geri gelmiş — en yüksek öncelik). Yeni bulgular ayrı işaretlenir.
6. **Önem merdiveni (savunulabilir olmalı):** **KRİTİK** = RCE, kimlik-doğrulama/yetki bypass,
   veri kaybı/bozulması, retention'ın yanlış veriyi silmesi, çoklu-replikada çift-işlem/yarış ile
   yanlış alarm, secret sızıntısı, prod'da açılamama · **YÜKSEK** = takım-izolasyon ihlali/IDOR,
   SSRF→metadata, yetki yükseltme, yanlış/eksik/geç alarm, OOM/DoS/sınırsız kaynak, kullanıcı-
   görünür işlevsel kırılma, timeout'suz dış çağrı (askıda kalma) · **ORTA** = yanlış gösterim/
   hesap, dar yarış, kaynak sızıntısı, eksik doğrulama (etkisi sınırlı), gürültülü/eksik log ·
   **DÜŞÜK** = latent, kozmetik, perf mikro, hijyen, "doğrulanmalı".
7. **Hüküm kuralı:** KRİTİK veya açık YÜKSEK varsa ya da herhangi bir mekanik kapı kırmızıysa →
   **NO-GO**. Yalnız ORTA'lar varsa ve her biri için (a) prod etkisi sınırlı, (b) düzeltme ≤1 gün
   → **KOŞULLU GO** (koşullar listelenir, ilk yama sürümüne bağlanır). Yalnız DÜŞÜK → **GO**.
   "Yeterince iyi" hükmü kanıt ister; şişirme de yumuşatma da yok.
8. **Gizlilik:** rapor/loglarda gerçek sicil/kurum adı yok (CLAUDE.md). Rapor Türkçe; her bulgu
   `dosya:satır — <bug> | Neden bug: <somut senaryo> | Çözüm: <düzeltme> | Test: <hangi test pinler>`.
9. Ortam Windows + Linux mount; kod `duzelt` dışında DEĞİŞTİRİLMEZ. Rapor `D:\site-monitor\`'a
   yazılır (SendUserFile + device_commit_files); bellek güncellenir.

## Faz 0 — Hazırlık ve ön-koşullar

- `git status --short` → commit edilmemiş iş varsa **listele ve NO-GO ön-koşulu** olarak işaretle
  (yayına giden şey commit'lenmiş olan şeydir; taranan şey de o olmalı). `git log --oneline -30`
  ve son tag'den bu yana değişen dosyalar (`git diff --stat <son-tag>..HEAD`) → **yeni-kod yüzeyi**
  listesi; ajanlara "bu dosyalara özel dikkat" notu olarak gömülür.
- Sürüm paritesi: `./VERSION` **tek otoritedir** (CLAUDE.md) — `CHANGELOG.md` en üst girdi ve helm
  `Chart.yaml appVersion` onunla eşleşmeli (`release.yml` ikisini birlikte yazar). `backend/pom.xml`
  bilinçli olarak senkron DIŞI, `frontend/package.json` sürümü de vite `define` ile VERSION'dan okur —
  bunları bulgu YAPMA. Uyumsuzluk → hijyen bulgusu + NO-GO ön-koşulu. VERSION/Chart.yaml'ı elle
  DÜZELTME (release.yml sahibi; elle bump bir sonraki rebase'i çakıştırır).
- Baseline'ı yükle (kural 5). CLAUDE.md "Things that bite" bölümünü oku — orada yazan her tuzak
  bir kontrol maddesidir.
- Kaynağı stage et / mount'ta hazırla (kural 1).

## Faz 1 — Mekanik kapılar (deterministik; sonuçlar tabloya girer)

Her kapıyı koştur, çıktısını `logs/prod-kapisi-<tarih>/` altına yaz (repo köküne dağınık `*.log`
BIRAKMA — kök zaten 60+ yetim log taşıyor, bkz. hijyen kapısı). Koşturulamayan kapı için en son CI
koşusunu (`.github/workflows/ci.yml`, `dependency-check.yml`, `docker-build.yml`) kaynak göster ve
"CI'dan okundu" de. **Dikkat:** CI'da `npm audit` non-blocking ve Trivy report-only — bu kapıda
ikisi de BLOKLAYICI sayılır (CI yeşil olması "geçti" demek değildir). Backend sağlık ucu
`/health`'tir (actuator base-path `/`'a remap'li) — `/actuator/health` arama.

| Kapı | Komut | Geçme koşulu |
|---|---|---|
| G1 Backend derleme+test | `backend/`: `mvn -q -B verify` | 0 hata, 0 kırmızı test, 0 `[WARNING]` yeni uyarı (önceki koşumla karşılaştır) |
| G2 Backend kapsam | jacoco raporu (`target/site/jacoco`) | CLAUDE.md/ci.yml'deki taban altına düşmemiş; **kritik sınıflar** (SessionScope, PermissionService, SsrfGuard, SafeRedirect, SchedulerService dağıtık kilit, MonitoringOutageService, EscalationService, retention) satır kapsamı ≥%80, yoksa bulgu |
| G3 Frontend lint | `frontend/`: `npm run lint` | 0 hata; uyarılar sayılır ve raporlanır |
| G4 Frontend test+kapsam | `npm test` + `npm run test:coverage` + `npm run coverage:floor` | yeşil; taban geçildi |
| G5 Frontend prod build | `npm run build` | 0 hata; chunk boyutu uyarısı (>500 kB) varsa P7 bulgusu; `dist/` içinde sourcemap yayınlanıyor mu (vite `build.sourcemap`) — prod'da kapalı olmalı |
| G6 Bağımlılık CVE | `mvn org.owasp:dependency-check-maven:check` (ağ yoksa CI artefaktı) + `npm audit --omit=dev --audit-level=high` + Trivy imaj raporu (docker-build.yml artefaktı) | KRİTİK/YÜKSEK yok ya da `dependency-check-suppressions.xml`'de **gerekçeli** suppress; suppress gerekçesizse bulgu; CI'da non-blocking olsa da burada bloklayıcı |
| G7 E2E duman | `npm run test:e2e` (Playwright; ortam elverişliyse) | yeşil; koşamıyorsa "koşulmadı" — GO için koşulmuş olmalı |
| G8 Perf duman | `k6 run -e BASE_URL=http://localhost:8080 ./perf/k6-smoke.js` (k6 kuruluysa, yerel stack ayakta ise; varsa `scripts/smoke.ps1` de) | SLA: hata oranı <%1, p95 <500 ms, checks ≥%99 (perf/k6-smoke.js'in kendi eşikleri); koşamıyorsa "koşulmadı" |
| G9 Repo hijyeni | `git ls-files` + kök listesi | repoda `*.log`, `hs_err_pid*.log`, `_review_src.tar.gz`, `_to_delete*`, `.env` (gerçek), `audit-verify.log` vb. **izlenmiyor** olmalı; `.gitignore` bunları kapsamalı; kökte 60+ yetim log → temizlik maddesi |
| G10 Secret taraması + rotasyon | `git grep -nE "(password|passwd|secret|token|apikey|api_key)\s*[:=]\s*['\"][^'\"]{6,}"` + `git log -p --all -S` ile geçmiş + `k8s/secret.yaml`, `.env` içerik kontrolü | gerçek secret **git geçmişinde bile** yoksa geç; varsa KRİTİK + rotasyon. CLAUDE.md'nin açık kuralı: `.env`'deki e-posta uygulama parolası (sohbet geçmişinde geçmiş) prod'dan ÖNCE rotasyonlanmalı — rotasyon kanıtı yoksa NO-GO ön-koşulu |
| G11 Docker imajı | `Dockerfile` statik oku (+ `docker build` mümkünse) | non-root USER, sabitlenmiş base imaj digest/sürüm, çok-aşamalı, `-Xmx`/`MaxRAMPercentage` verilmiş, HEALTHCHECK veya k8s probe |
| G12 Sürüm paritesi | Faz 0 sonucu | `VERSION` = CHANGELOG en üst girdi = `Chart.yaml appVersion`; pom.xml/package.json bilinçli senkron dışı (bulgu değil) |

## Faz 2 — Paralel kod taraması: 16 eksen + imza süpürmesi

### Devralınan eksenler (tanımlar `/bug-denetle`'de; burada yalnız fark notu)
**E1** eşzamanlılık/zamanlayıcı/kaynak sızıntısı · **E2** güvenlik IDOR/SSRF/yetki · **E3** veri
katmanı/bütünlük · **E4** sayısal/zaman-penceresi · **E5** alarm/eskalasyon tutarlılığı · **E6**
React state/efekt/render. Bu eksenlerin ajan prompt'larını `.claude/commands/bug-denetle.md`'den
**aynen** al (Yanlış-pozitif listesi dahil). Ek olarak `bug-regresyon.md`'deki **S1–S17 imzalarını**
ilgili eksene gömülü "önce grep, sonra oku" listesi olarak ver (S1–S4 → E2, S5 → E4, S6/S7/S11/S12 →
E6, S8/S9/S10/S16 → E3, S14/S15/S17 → E5).

### Yeni enterprise eksenleri (bu komuta özgü)

**P1 — Performans ve ölçek.** Liste uçlarında sınırsız `findAll`/sayfalama tavanı (`size`
üst sınırı yok → 100k satır); `enrich*`/döngü içi repo çağrısı (N+1, toplu harita kullanmayan yeni
kod); `applySchemaPatches()`'te eklenen tablolar/kolonlar için **index yokluğu** (FK'ler,
`monitor_id+checked_at` gibi zaman-serisi sorguları, retention `DELETE … WHERE ts <` taramaları);
`@Cacheable` TTL/anahtar tasarımı (takıma göre değişen veri global anahtarla cache'lenmiş mi);
istek thread'inde ağır senkron iş (PDF üretimi, DNS/WHOIS, sertifika zinciri) → async/timeout;
`@Scheduled` sweep'lerin `fixedDelay` ile tek-uçuş garantisi ve iş süresi > aralık durumu;
Caffeine/in-memory harita büyüme tavanları (bkz. `/bellek-denetim` "zaten sınırlı" listesi —
tekrar bulgu yapma); JPA `open-in-view`; büyük JSON yanıtlarında gereksiz alan; frontend: tablo
satır başına ağır hesap (`useMemo` yok), 1000+ satır listede sanallaştırma yok, her poll'da tam
yeniden-render, bundle içinde kullanılmayan büyük bağımlılık (pdfbox-benzeri istemci tarafı),
`recharts` veri noktası tavanı. **Kanıt:** sorgu/çağrı sayısı ile satır büyüklüğü çarpımı; "10
takım × 5k monitör × 90 gün" senaryosunda ne olur.

**P2 — Dayanıklılık, timeout, kapanış.** Her dış çağrının (HttpClient, Socket, DNS, SMTP, LDAP,
webhook, push API, WHOIS, JDBC) **connect + read timeout**u var mı ve makul mü (askıda kalan bir
probe sweep'i kilitler mi); retry sayısı/backoff sınırlı mı; thread havuzu boyutları + kuyruk
tavanı (`newCachedThreadPool` sınırsız); `@PreDestroy` ile graceful shutdown (uçuştaki check
tamamlanıyor mu, outbox flush ediliyor mu); `server.shutdown=graceful` + k8s `terminationGracePeriod`
uyumu; açılış sırası (şema patch'i DB'ye bağlanamazsa ne olur — crash-loop mu, sessiz mi);
readiness/liveness ayrımı (DB düşünce liveness fail edip pod'u öldürüyor mu — yanlış); dağıtık
kilit **fail-open/fail-closed** kararı (DB hatasında `true` dönmek = çift sweep); H2→PostgreSQL
ile davranış farkları (`applySchemaPatches` SQL lehçesi, `LIMIT`, `ON CONFLICT`); "poison
message" — tek bozuk monitör kaydı bütün sweep'i istisnayla düşürüyor mu (döngü içinde
try/catch var mı); saat kayması/DST'de zamanlayıcı (Europe/Istanbul sabit mi, UTC mi — CLAUDE.md
"Timezone").

**P3 — Konfigürasyon, secret, deploy paritesi.** Kodda okunan her `@Value`/`@ConfigurationProperties`/
`env.*` anahtarı ↔ `.env.example` ↔ `helm/site-monitor/values.yaml` ↔ `k8s/configmap.yaml`/
`secret.example.yaml` **üçlü parite** (kodda var, örnekte yok → prod'da sessiz default; örnekte var,
kodda yok → ölü konfig, bkz. N11 LDAP); **dev-eğilimli default'ların prod'a sızması** —
`application.properties` dev CORS origin'leri / in-memory session / `show-sql`, `prod` profilinin
gerçekten aktif olduğu (`SPRING_PROFILES_ACTIVE=prod` deployment'ta var mı), `application-prod`'daki
secure cookie + `same-site=strict` + JDBC session + `/var/log/site-monitor` log yolu; actuator
(base-path `/`, `/health`) dışa açık uçlar (`env`/`heapdump`/`threaddump`/`prometheus` kimliksiz mi);
`mustChangePassword` ve bootstrap `admin` default şifre politikası. NOT: `ddl-auto=update` +
`applySchemaPatches()` bu projenin **bilinçli** şema mekanizmasıdır (Flyway yok, CLAUDE.md) — "ddl-auto
prod'da tehlikeli" diye BULGU YAPMA; idempotency/çoklu-replika riski P6'da denetlenir. Secret'ların
koddan/`.env`'den değil k8s Secret/vault'tan geldiği; `secret.yaml` repoda **gerçek** değer taşıyor mu
(G10 ile çapraz); helm ve k8s/ dizinlerinin **birbirinden sapmış** olması (hangisi kaynak-of-truth);
**helm rename tuzağı**: prod'un bağlı olduğu operasyonel değerler (`HTTP_PROXY_HOST/PORT`, `NO_PROXY`,
WHOIS kapıları, SMTP) `helm/site-monitor/environments/master.yaml`'da mı yoksa yalnız `--set`'te mi
(CLAUDE.md: certmonitor→sitemonitor geçişinde RDAP günlerce öldü) — `docs/DEPLOY_GECIS_PLANI.md` §2.1
listesi yürünmüş mü; `deployment.yaml`: resources requests/limits, `securityContext` (runAsNonRoot
UID 1000, **readOnlyRootFilesystem + `/var/log/site-monitor` yazılabilir volume var mı** — yoksa prod
profili açılışta log dosyası açamaz; `/tmp` emptyDir), probes (`/health`, startup 12×10s / readiness /
liveness — DB düşünce liveness'ın pod'u öldürmemesi), `hpa.yaml` (3–10, CPU70/mem80) + `pdb.yaml`
`minAvailable=2` ↔ replika 3 (bakımda rolling update ilerleyebiliyor mu), `networkpolicy.yaml` egress
(probe hedefleri, RDAP 443, WHOIS 43, SMTP, DNS, proxy — kapalıysa tüm checkler "down"),
`ingress`/`openshift-route` TLS + timeout (uzun PDF/rapor istekleri); `spring-session-jdbc` çoklu-
replika oturumu + tek-aktif-oturum kaydının açılışta temizlenmesi; JVM bayrakları
(`-XX:+UseContainerSupport`, heap ≈%75, `-XX:+ExitOnOutOfMemoryError` var mı), Java 25 + Spring Boot
4.1 sürüm sabitliği (snapshot/milestone yok), Dockerfile base imajlarının (`node:20-alpine`,
`maven:3.9`, `eclipse-temurin:25-jre-alpine`) digest/sürüm sabitliği.

**P4 — Gözlemlenebilirlik ve loglama.** Loglarda **PII/secret** (şifre, token, cookie, `Authorization`
başlığı, LDAP bind parolası, SMTP şifresi, webhook secret, kullanıcı e-postası düz metin);
`RequestLoggingFilter` TRACE kapısı prod'da kapalı mı ve gövde maskeleme; `HttpMetricsInterceptor`
eşleşmeyen istekte **ham URI** etiketi (metrik kardinalite patlaması — bilinen risk, açık mı bak);
yutulan istisnalar (`catch (Exception e) {}` / yalnız `log.debug`) — özellikle alarm/notification
yolunda sessiz kayıp; log seviyesi tutarlılığı (beklenen durum ERROR'a, gerçek arıza DEBUG'a
yazılıyor mu); korelasyon kimliği (MDC request-id) ve alarm olay kimliğinin log satırına
düşmesi; `hs_err`/OOM'da dump alma ayarı; actuator `health` bileşenleri (DB, mail, disk) ve
`prometheus` metriklerinde iş metrikleri (sweep süresi, kuyruk derinliği, bildirim başarısızlığı) —
yoksa "operasyon kör" bulgusu; frontend `console.log/debug` kalıntıları, hata raporlama
(`/api/client-error-report`) PII göndermiyor mu.

**P5 — Girdi doğrulama, API sözleşmesi, HTTP güvenlik yüzeyi.** Her `@RequestBody` DTO'da
`@Valid` + kısıtlar (`@Size`, `@Pattern`, `@Min/@Max`) — özellikle host/URL/cron/regex/e-posta/
şablon alanları; regex'lerde **ReDoS** (kullanıcı-tanımlı `KeywordChecker` deseni `Pattern.compile`
ile sınırsız mı); sayısal parametrelerde sınır (`intervalSec=0`, `size=1000000`, negatif sayfa);
enum parse hataları 500 mü 400 mü; dosya uçları (logo yükleme, PDF, import/export) — uzantı/
MIME/boyut sınırı, path traversal, zip-bomb; hata yanıtlarında stack trace/SQL/iç sınıf adı
sızması (`server.error.include-*`); **HTTP güvenlik başlıkları** (CSP, X-Content-Type-Options,
X-Frame-Options/frame-ancestors, Referrer-Policy, HSTS — ingress ya da uygulama, hangisi);
cookie bayrakları (`HttpOnly`, `Secure`, `SameSite`) — `application-prod`'da secure + `same-site=strict`
tanımlı; prod profilinin gerçekten yüklendiğini ve remember-me cookie'sinin de aynı bayrakları
taşıdığını doğrula — ve **CSRF** stratejisi (Spring Security filter chain YOK, `AuthInterceptor`
custom; `starter-security` eklemeyi ÖNERME — CLAUDE.md yasağı; `SameSite=strict` + `Origin`/`Referer`
kontrolü + JSON content-type zorlaması yeterli mi, e-posta token'lı **login'siz** haftalık-rapor onay
uçları ve `mustChangePassword` kısıtlı-yol listesi genişlemiş mi);
login/şifre-sıfırlama/2FA uçlarında **rate-limit + hesap kilidi**; kullanıcı numaralandırma
(farklı hata mesajı/tempo); şifre politikası ve hash (spring-security-crypto BCrypt/Argon2 iş
faktörü); `SqlPlaygroundService` salt-okunur zorlaması (N1/N5 kapalı mı); CORS allow-list;
i18n mesajlarına gömülen kullanıcı girdisi (XSS — React kaçırır ama `dangerouslySetInnerHTML`
/ commonmark render noktaları); markdown render (commonmark) HTML sanitize.

**P6 — Veri yaşam döngüsü, şema, çoklu-replika.** `applySchemaPatches()` **idempotent** mi
(ikinci açılışta hata vermeden geçiyor mu, `IF NOT EXISTS` / try-catch yutması gerçek hatayı da
saklıyor mu); iki replika aynı anda patch koşarsa (kilit var mı); patch sırası ve geri-alınamazlık
(kolon silme/tip değiştirme var mı → veri kaybı); entity ↔ patch ↔ H2/PG kolon tipi uyumu
(`TEXT` vs `VARCHAR`, `TIMESTAMP WITH TIME ZONE`); **retention**: hangi tablolar kapsanıyor,
hangileri **hiç** silinmiyor (audit, notification_log, http_metrics history, push_outbox, session
tablosu) → sonsuz büyüme; retention `minDays` tabanı ve UTC-ISO leksikografik doğruluğu (bilinen-
doğru, tekrar bulgu yapma); yumuşak-silme ↔ sert-silme tutarlılığı (silinen monitörün olay/
alarm/outbox kayıtları yetim mi); FK'ler `ON DELETE` politikası; **unique kısıt** ile idempotent
yazım (dedupe UNIQUE'leri var mı, eşzamanlı çift POST); yedek/geri-yükleme varsayımı (H2/SQLite dosya
modu prod'da kullanılmıyor mu, `data/` gitignore'da); saat dilimi karışımı (`LocalDateTime` vs
`Instant`) sınır günlerde — backend UTC saklar, frontend Europe/Istanbul biçimler, tarih aritmetiğinde
`setUTC*` yerine yerel `set*` kullanan yer 3 saat kayar (CLAUDE.md tuzağı); `.properties` değerlerinde
ham non-ASCII (ISO-8859-1 okunur → mojibake; `PropertiesEncodingTest` var, `\uXXXX` kaçışı zorunlu).

**P7 — Frontend yayın hazırlığı.** i18n: `i18n-parity.test.jsx` statik anahtar paritesini zaten
zorlar (TEKRAR bulgu yapma) — asıl boşluk **dinamik anahtarlar** (`t('general.lbl.' + it.key)` gibi
birleştirilerek üretilenler: sözlükte karşılığı yok → ham anahtar görünür, test yeşil kalır),
`useT` null-context tuzağı, i18n string'inde ham `*` (req-star kuralı), sabit-kodlanmış Türkçe/
İngilizce metin; `lucide-react` dışı ikon/emoji; erişilebilirlik temelleri (form label, buton `aria-label`, odak tuzağı modal, klavye ile
kapanış, renk-kontrast tokenları — tam WCAG denetimi değil, "enterprise UI'da kabul edilemez"
seviyesi); `ErrorBoundary` kapsamı (route düzeyinde mi, tek global mi — bir widget çökünce tüm
sayfa mı gidiyor); `dangerouslySetInnerHTML` kullanımları; env değişkenleri build-time gömülü mü
(`import.meta.env.VITE_*` içinde secret var mı); API base URL / proxy prod'da nasıl çözülüyor;
`vite.config` `build.sourcemap` prod'da kapalı; büyük chunk'lar için `manualChunks`/lazy route;
"loading/empty/error" üç durumunun her liste sayfasında var olması; oturum düşünce (401) tek
merkezden yönlendirme + yarım kalan fetch'lerin iptali; tarayıcı geri tuşu ile sayfalama/filtre
durumu; **ölü kod** (kullanılmayan bileşen/hook/i18n anahtarı — `knip`/`ts-prune` benzeri kaba
grep ile).

**P8 — Test boşluğu ve test kalitesi.** Kritik yollar için **eksik** test: her `{id}` uçta
"başka takımın kaydı → 403/404" testi var mı; SsrfGuard/SafeRedirect negatif vakalar (loopback,
link-local 169.254, IPv6 `::1`, DNS-rebinding); eskalasyon üç yolu (ilk/çözüm/yeniden-gönder) için
alıcı-çözümü testi; retention "yanlış tabloyu silmez" testi; şema patch idempotency testi
(iki kez koş); `RepositoryWriteTransactionGuardTest` allow-list güncel mi; zaman-bağımlı testler
sabit `Clock` kullanıyor mu (flaky); **assert'siz** testler (`assertDoesNotThrow` tek başına,
mock verify yok); `@Disabled`/`.skip`/`xit` bırakılmış testler ve gerekçesi; frontend: kritik
akış (login → monitör ekle → alarm görüntüle) E2E ile kapalı mı; Vitest'te `act` uyarıları;
kapsam yüksek görünüp **davranış** test etmeyen (snapshot-only) dosyalar. Çıktı: "yayına kadar
yazılması ŞART testler" kısa listesi.

**P9 — Bağımlılık, lisans, tedarik zinciri.** `pom.xml`/`package.json` sürüm sabitliği (aralık
`^`/`~` prod build'i yeniden-üretilebilir mi → `package-lock.json` commit'li ve `npm ci`
kullanılıyor mu); `dependency-check-suppressions.xml` her suppress'in gerekçesi/tarihi; **lisans**
uyumsuzluğu (GPL/AGPL bağımlılık kapalı-kaynak kurumsal üründe); kullanılmayan bağımlılık
(`pdfbox`, `bcpkix`, `commonmark`, `jsoup`, `dnsjava` — hepsi gerçekten kullanılıyor mu);
`@fontsource/*` ve dış CDN çağrısı (kurum ağında dışarı çıkış yok → font/ikon kırılır); Docker base
imaj güncelliği; SBOM üretimi (CycloneDX) var mı — yoksa öneri; `release.yml`/`docker-build.yml`
imaj imzalama/digest sabitleme.

**P10 — Dokümantasyon ve operasyon paritesi.** README/QUICKSTART/WHITEPAPER/TESTING/CLAUDE.md'de
yazan davranış ↔ kod (ör. "retention 90 gün" yazıyor, kod 30; "tek aktif oturum" yazıyor, kod
remember-me ile ikinci oturuma izin veriyor); `docs/DEPLOY_GECIS_PLANI.md` adımları güncel mi;
**runbook** var mı (alarm fırtınası, DB dolması, SMTP düşmesi, sertifika süresi dolması, kilitli
admin hesabı, rollback); `.env.example` açıklamaları; CHANGELOG'da bu sürümün **breaking**
maddeleri işaretli mi; API değişiklikleri (frontend↔backend sözleşme kırılması — eski sekme açık
kullanıcı yeni backend'e istek atınca ne olur; sürüm uyumsuzluğunda yeniden-yükleme tetiği var mı).

## Yanlış-pozitif listesi (RAPOR ETME)

`/bug-denetle.md` §Yanlış-pozitif listesinin TAMAMI geçerli (buildResponseSeries hunisi, ErrorBoundary
raporu, @PreDestroy executor kapanışı, RepositoryWriteTransactionGuardTest, teamNameMap toplu
harita, retention UTC-ISO + minDays, percentile interpolasyon, MonitoringOutageService 3→3 /
recovery 3, MaintenanceService yarı-açık aralık, hook timer temizlikleri, CertificateHealthRules/
PageSpeedRules eşikleri). Ek olarak `/bellek-denetim` "zaten sınırlı" listesi: GeoIpService 10k
tavan, CacheConfig Caffeine 1000/60sn + LONG_TTL, HttpMetricsService history 1440, RequestLoggingFilter
TRACE kapısı + 64KB, ScriptedChecker ERR_* tavanları + temp deleteIfExists, useVisibleInterval,
client.js AbortController + revokeObjectURL. Ve 4./5. turda "doğru bulundu" denenler: SafeRedirect
hop döngüsü, UserPushService TLS + 64KB + anti-loop UNIQUE, teamOnlyRecipients tek kaynak,
ProcessProbe kabuksuz, SecretCipher AES-GCM+IV, LDAP escapeFilter, SQL FORBIDDEN_FUNCTIONS.
Bunları "bug" diye getiren ajan çıktısı ELENİR; ama **regresyon** kontrolü için satır hâlâ
yerinde mi diye BAKILIR (kural 5).

## Faz 3 — Doğrulama, dedupe, derecelendirme

Her KRİTİK/YÜKSEK/ORTA'yı kaynakta aç-oku-teyit et (kural 4). Mümkünse **repro**: bir HTTP isteği
taslağı, bir test iskeleti veya "şu satırı şu değerle çağır" adımı. Baseline re-check sonucu
(ÇÖZÜLMÜŞ/AÇIK/REGRESYON). Aynı kök nedeni birleştir ("aynı kök: X, Y, Z uçları"). Her bulguya
**Test:** alanı — bu bulguyu kalıcı pinleyecek testin adı/iskeleti. Sonra kural 7 ile hüküm.

## Faz 4 — Rapor: `PROD_KAPISI_<tarih>.md`

1. **HÜKÜM KUTUSU** (en üstte): GO / KOŞULLU GO / NO-GO + tek paragraf gerekçe + (KOŞULLU ise)
   koşul listesi + hangi sürüme bağlandığı.
2. **Mekanik kapı tablosu** (G1–G12: geçti/kaldı/koşulmadı + kanıt satırı/log yolu).
3. **Eksen karnesi**: 16 eksen × (taranan dosya sayısı · bulgu sayısı KRİTİK/Y/O/D · durum
   🟢🟡🔴) — kapsamı bir bakışta gösterir.
4. **AÇIK bulgular** önem sırasıyla; her biri `dosya:satır | Neden bug | Çözüm | Test | Etiket [E]
   mevcut / [Y] yeni-kod / [R] regresyon`.
5. **ÇÖZÜLMÜŞ ✓** (baseline'dan kapananlar — ilerleme görünür olsun).
6. **Doğru bulunan** (tarandı, temiz; yanlış-pozitif üretmeme kanıtı ve kapsam görünürlüğü).
7. **Yayın kontrol listesi** — somut, tik atılabilir:
   *Öncesi:* commit temiz · sürüm paritesi (VERSION/Chart) · CHANGELOG · DB yedeği alındı · şema
   patch'leri + `ddl-auto` prod kopyasında denendi (iki kez açılış = idempotent) · secret'lar vault/
   k8s Secret'ta, `.env` e-posta parolası ROTASYONLANDI · operasyonel değerler `environments/master.yaml`'da
   (`--set`'e güvenme) · `SPRING_PROFILES_ACTIVE=prod` · `docs/DEPLOY_GECIS_PLANI.md` §2.1 yürütüldü ·
   kapasite (replika/limit/`EXECUTOR_*` envanter boyutuna göre) · networkpolicy egress hedefleri ·
   bakım penceresi/duyuru · izleme panoları + alarm eşikleri hazır · `pg_stat_statements` preload
   (helm upgrade + DB restart gerekir; yoksa app fallback'te — bilinçli).
   *Sırası:* imaj digest'i · canary/tek replika ile başlat · açılış logunda `applySchemaPatches` +
   `scheduler_lock` + stale-session temizliği satırları, `ShutdownLogger` sessiz · `/health` UP +
   readiness geçti · duman testi (login → 409/forceLogin akışı, liste, "şimdi kontrol et", bir test
   alarmı, mail teslimi CID logolu, webhook, domain registration RDAP proxy üzerinden) · `scripts/
   smoke.ps1` (varsa) + k6 smoke prod-benzeri ortamda.
   *Sonrası (ilk 24 saat):* hata oranı, sweep süresi, kuyruk derinliği, bellek eğrisi, bildirim
   başarısızlığı, 5xx; yetim outbox kaydı; DB boyutu.
   *Geri-alma:* önceki imaj digest'i · şema patch'leri geri-uyumlu mu (eski sürüm yeni kolonla
   açılır mı) · geri-alma sonrası veri kaybı riski · kim karar verir/kaç dakikada.
8. **Zenginleştirilmiş öneriler — sertleştirme yol haritası** (bulgu değil, "enterprise seviyeye
   götüren" işler; her madde: fayda + maliyet + önerilen sürüm):
   *Kısa (yayın öncesi/ilk yama):* projenin mevcut **guard-test desenini** genişlet
   (`RepositoryWriteTransactionGuardTest`, `PropertiesEncodingTest`, `responseSeries_contract_allEndpoints`,
   `inventory-flags-sync`, `i18n-parity`, `brand-default` zaten bu deseni kurmuş) — yeni guard'lar:
   "her `{id}` uç `denyIfNotViewable`/`canView` çağırır", "her dış HttpClient/Socket connect+read
   timeout'lu", "PaginationBar çağıranları 1-tabanlı", "dinamik i18n anahtarları sözlükte var",
   "her `@Column` eklemesinin `patch()` satırı var", "PermissionCatalog ↔ kodda kullanılan resource_key
   paritesi" (ArchUnit ya da reflektif JUnit); HTTP güvenlik başlıkları; login rate-limit; actuator
   kilidi; log maskeleme; kök log temizliği + `.gitignore`; `.env` kimlik bilgisi rotasyonu.
   *Orta (1–2 sürüm):* Flyway/Liquibase'e geçiş ya da patch mekanizmasına kilit+versiyon tablosu;
   OpenTelemetry trace + yapılandırılmış JSON log; SLO/hata bütçesi (sweep gecikmesi, alarm
   teslim süresi) ve buna bağlı alarm; SBOM + imaj imzalama (cosign); k6 yük testi CI'da eşikli;
   mutation testing (PIT) kritik paketlerde; erişilebilirlik lint (eslint-plugin-jsx-a11y);
   Playwright E2E'nin CI'ya bağlanması.
   *Uzun:* çoklu-replika iş dağıtımı (monitör sahipliği/sharding) ve lider seçimi; outbox
   desenini tüm kanallara genelleme; feature flag altyapısı; kaos/dayanıklılık tatbikatı (DB
   kesintisi, SMTP düşmesi); DR planı ve geri-yükleme tatbikatı; tehdit modeli dokümanı;
   bağımsız sızma testi.
9. **Önerilen düzeltme sırası** (güvenlik önce, ucuz/yüksek-etki önce) + birleşik önem tablosu +
   "NO-GO ise GO'ya giden en kısa yol" (hangi N madde kapanınca hüküm değişir).

SendUserFile + device_commit_files `D:\site-monitor\`'a. Konteyner + device arşivlerini temizle.

## Faz 5 — Bellek

`bug_denetimi_2026_08.md`'yi güncelle (yeni bulgular, çözülenler, regresyonlar, satır tazeleme).
Yeni `prod_kapisi_<tarih>.md` proje-bellek dosyası: hüküm, açık koşullar, kapı sonuçları, hangi
kapıların koşulamadığı, bir sonraki kapıda "önce buraya bak" notları. MEMORY.md'ye tek satır.

## Faz 6 — (yalnız `duzelt`) Düzeltme

Rapor kullanıcıya sunulur, **onay alınır**, sonra KRİTİK→YÜKSEK sırasıyla: her düzeltme küçük ve
tek-kök-neden; **yeni** test eklenir, mevcut testler değiştirilmez (CLAUDE.md kuralı); her
düzeltmeden sonra ilgili test sınıfı, sonunda `mvn -q verify` + `npm test` + `npm run lint` +
`npm run build`; CHANGELOG'a "Fixed" girdileri; VERSION bump'ı **release.yml**'e bırakılır (elle
bump yok). Sonunda hüküm yeniden hesaplanır ve rapora "Düzeltme sonrası hüküm" eklenir.

## Ton ve dürüstlük

- Hüküm kanıta dayanır: NO-GO'yu tek bir doğrulanmış KRİTİK/YÜKSEK ya da kırmızı kapı verir; GO'yu
  "her eksen tarandı + her kapı yeşil + baseline kapalı" verir. Arası KOŞULLU GO.
- Kod tabanı gerçekten sağlamsa bunu SÖYLE; bulgu üretmek için DÜŞÜK'leri şişirme. Yol haritası
  bölümü "bulgu yok ama daha iyi olabilir"in doğru yeridir — bulgu bölümüne karıştırma.
- Koşturulamayan kapıyı "geçti" sayma; "koşulmadı" yaz ve GO'yu buna bağla.
- Çözümler somut (kod deseni/parça, test adı); "gözden geçirilmeli" gibi muğlak ifade yok.
- Rapor uzun olabilir; hüküm kutusu ve kapı tablosu ise 30 saniyede okunmalı.

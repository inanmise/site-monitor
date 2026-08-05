# GÖREV: Proje Adı Değişikliği — CertMonitor → Site Monitör (TAM RENAME, dağıtım dahil)

> Bu komutu repo kökünde (D:\cert-monitor) Claude Code'a olduğu gibi verebilirsin.
> Kapsam kararları alınmıştır: TAM rename (kod + dağıtım adları + GitHub repo adı + yerel klasör dahil), iki dilli marka yazımı. Prod/test URL-DNS ayarlarını kullanıcı KENDİSİ yapacak (kapsam dışı).

---

## 0. EN KRİTİK KURAL — Önce bunu oku

Bu görev bir **proje adı** değişikliğidir, **alan dili (domain) değişikliği DEĞİLDİR**. Uygulama SSL sertifikalarını izlemeye devam ediyor; "certificate/cert" kelimesi alan terimi olarak her yerde KALACAK.

**SADECE şu tam token'lar değişir** (büyük/küçük harf duyarlı, tam eşleşme):

| Eski token | Yeni token | Kullanım alanı |
|---|---|---|
| `CertMonitor` | `SiteMonitor` (kodda) / `Site Monitör` (TR metin) / `Site Monitor` (EN metin) | sınıf adları, marka metinleri |
| `certmonitor` | `sitemonitor` | Java paketi, chart adı, birleşik kullanım |
| `cert-monitor` | `site-monitor` | kebab: artifactId, npm adı, imaj, log dosyaları, helm klasörü |
| `cert_monitor` | `site_monitor` | snake kullanımları (varsa) |
| `CERT_MONITOR_` | `SITE_MONITOR_` | env değişken öneki (alias'lı, bkz §4.3) |
| `cert.monitor.` | `site.monitor.` | Spring properties ad alanı (alias'lı, bkz §4.3) |
| `com.certmonitor` | `com.sitemonitor` | Java kök paketi |
| "Cert Monitor" / "Cert Monitör" (metinlerde) | "Site Monitor" / "Site Monitör" | doküman/i18n serbest metin |

**ASLA değişmeyecekler** (bunlara dokunan bir diff HATALIDIR):
- Alan terimleri: `CertificateInventory`, `certificate_inventory`, `CertificateCheck(er)`, `certificate_checks`, `CertificateController`, `CertificateDto`, `CertificateNote*`, `PinnedCa`, `cert` içeren TÜM domain sınıf/tablo/kolon/endpoint adları (`/api/certificates`, `certs`, `cert.domain` alanları…).
- DB şeması: hiçbir tablo/kolon adı değişmez; `applySchemaPatches`'e bu görev için patch EKLENMEZ.
- API endpoint yolları (`/api/...`) — dış tüketiciler kırılmasın.
- `VERSION`, `Chart.yaml` sürüm alanı (CI yönetir), CHANGELOG'un GEÇMİŞ girdileri (tarih yeniden yazılmaz).
- Prod URL/DNS (`certmonitor-prod.akbank.com`) ve `docs/` altındaki tarihsel komut dosyaları (`PAGING_*`, `PAYLASILABILIR_*`, `duplicate-*` — geçmiş kayıttır, güncellenmez).

Kör toplu bul-değiştir YASAK. Her değişiklik yukarıdaki token tablosuna göre, dosya bazında bilinçli yapılır.

### Yazım standardı
- TR arayüz/e-posta metinleri: **Site Monitör**
- EN arayüz metinleri: **Site Monitor**
- Kod kimlikleri daima ASCII: `SiteMonitor`, `sitemonitor`, `site-monitor`, `SITE_MONITOR_`, `site.monitor.` — hiçbir teknik kimlikte `ö` KULLANILMAZ.

---

## 1. Çalışma düzeni

- Ayrı dalda çalış: `feature/rename-site-monitor`.
- Aşağıdaki fazlar **ayrı commit'ler** olsun (geri alınabilirlik): Faz 1 marka/UI/doküman → Faz 2 kod içi teknik adlar → Faz 3 dağıtım adları → Faz 4 doğrulama + geçiş planı dokümanı → Faz 5 GitHub repo adı → Faz 6 yerel klasör (yarı-manuel, en son).
- Her fazdan sonra: backend değiştiyse `mvn -B clean verify`, frontend değiştiyse `npm run test` + `npm run build`. Kırmızıyken sonraki faza GEÇME.
- Bekçi betiği (Faz 0'da ekle): `scripts/check-brand.(ps1|sh)` — `grep -rIn -i -e certmonitor -e cert-monitor -e cert_monitor` çalıştırır (hariç: `.git`, `node_modules`, `dist`, `target`, `logs`, `data`, `*.log`, `*.gz`, `*.jar`, `*.pdf`, `package-lock.json`, `CHANGELOG.md`, `docs/`, alias satırları). Faz 4 sonunda çıktı yalnız bilinçli bırakılan alias/geriye-uyum satırlarını listelemeli.

---

## 2. Faz 1 — Marka, UI, i18n, e-posta, dokümanlar (risk: yok)

### 2.1 Çalışma zamanı marka varsayılanı
- `BrandingController.java` (~satır 98): `settingsService.getString(PREFIX + "app-name", "CertMonitor")` → varsayılan **"Site Monitör"**. NOT: Admin panelden `app-name` özelleştirilmiş kurulumlarda DB'deki değer baskın kalır — bu doğru davranış, dokunma; sadece varsayılan değişiyor.
- Branding'in beslediği her yerin varsayılanı da aynı olsun (frontend `BrandingProvider.jsx` fallback değeri varsa güncelle).

### 2.2 Frontend görünür metinler
- `frontend/index.html`: `<title>CertMonitor</title>` → `Site Monitör` (branding yüklenince zaten dinamik değişiyorsa fallback olarak).
- `frontend/src/i18n/index.jsx`: 18 marka geçişi — TR sözlükte "Site Monitör", EN sözlükte "Site Monitor". Parity testi placeholder sayılarını korur.
- `frontend/src/components/ui/CertMonitorLogo.jsx` → **`SiteMonitorLogo.jsx`** (git mv): bileşen adı, export ve içindeki yazı/monogram; TÜM import eden dosyaları güncelle (`grep -rn "CertMonitorLogo" src/`).
- Sabit marka geçen diğer dosyalar: `Nav.jsx`, `pages/Login.jsx`, `HelpPage.jsx`, `pages/ExpiryForecastPage.jsx`, `monitorGuides.js`, `utils/exportInventory.js` (CSV/PDF başlıkları), `utils/mailPreview.js`, `api/client.js` (yorum/başlıklar), `contexts/BrandingProvider.jsx`, `i18n/theme.jsx`.
- `frontend/src/assets/whitepaper.md`: marka metinleri güncellenir (alan terimleri kalır).

### 2.3 Backend'de kullanıcıya görünen metinler
- E-posta şablonları ve konu satırları: `EmailNotificationService`, `WeeklyReport*` mailleri, `LoginHelp/LoginIssue` mailleri, `EscalationService` metinleri — `grep -rn "CertMonitor" backend/src/main/java` ile string literal'ları bul; TR içerikte "Site Monitör", EN içerikte "Site Monitor".
- Webhook (Teams/Slack) kart başlıkları (`WebhookService`).
- `SecretKeyWarning.jsx`, `SecretTools.jsx`, `SmtpSettings.jsx` gibi admin ekranlarındaki açıklama metinleri.

### 2.4 Dokümanlar
- `README.md`, `QUICKSTART.md`, `TESTING.md`, `WHITEPAPER.md`, `CLAUDE.md`, `.github/copilot-instructions.md`, `docs/db-scaling.md`, `helm/.../docs/openshift-install.md`, `.claude/skills/run/SKILL.md`: marka + komut örnekleri (imaj/klasör adları Faz 3'teki yeni adlarla tutarlı yazılır).
- `WHITEPAPER.pdf` ve `frontend/public/whitepaper.pdf` md'den türetiliyor; yeniden üretilemiyorsa "PDF'ler bir sonraki whitepaper güncellemesinde yenilenecek" notu ile bırak, README'de belirt.
- CHANGELOG: geçmişe dokunma; en üste kullanıcıya dönük yeni madde: "Ürün adı Site Monitör olarak değişti…".

### 2.5 Testler (Faz 1 kapsamı)
- `BrandingSettings.test.jsx`, `i18n.test.jsx`, `Login.test.jsx`, `LoginIssueReports.test.jsx` içindeki "CertMonitor" beklentileri güncellenir.
- Yeni test: branding varsayılanının "Site Monitör" olduğunu doğrulayan küçük assert (backend `BrandingController` testi varsa oraya, yoksa frontend Branding testine).

---

## 3. Faz 2 — Kod içi teknik adlar (risk: orta — alias'larla korunur)

### 3.1 Java kök paketi
- `com.certmonitor` → `com.sitemonitor`: `backend/src/main/java/com/certmonitor` ve `backend/src/test/java/com/certmonitor` dizinleri **git mv** ile taşınır; 385+ dosyada `package`/`import` satırları güncellenir.
- `CertMonitorApplication.java` → `SiteMonitorApplication.java` (sınıf + dosya + testlerdeki referanslar).
- `logback-spring.xml` ve `application*.properties` içindeki logger seviyeleri: `logging.level.com.certmonitor=DEBUG` → `com.sitemonitor`; `RequestLoggingFilter` TRACE örneği dahil (CLAUDE.md'deki örnek de).
- Derleme sonrası eski paket kalıntısı kalmadığını doğrula: `grep -rn "com\.certmonitor" backend/ --include=*.java --include=*.xml --include=*.properties` → 0 sonuç.

### 3.2 Yapı kimlikleri
- `backend/pom.xml`: `<groupId>com.sitemonitor</groupId>`, `<artifactId>site-monitor</artifactId>`, `<name>site-monitor</name>`. Jar adı `site-monitor-<ver>.jar` olur — `start-local.ps1` `target/*.jar` glob'u ile çalışıyor, ama içinde geçen `cert-monitor` metinlerini de güncelle; `Dockerfile`'daki jar kopyalama glob/adını kontrol et.
- `frontend/package.json`: `"name": "site-monitor-frontend"`.
- `.claude/settings.local.json` içindeki yol/ad referanslarını güncelle.

### 3.3 Properties + env alias stratejisi (PROD'U KIRMAMANIN ANAHTARI)
- `application*.properties` içindeki 216 `cert.monitor.*` anahtarının tamamı `site.monitor.*` olur; Java tarafındaki tüm `@Value`/`@ConfigurationProperties` referansları birlikte güncellenir.
- **Dışarıdan (env/ConfigMap/secret) set edilebilen anahtarlar için geriye dönük alias zorunlu.** Önce envanter çıkar: `.env.example`, `helm/*/values.yaml` + `environments/*.yaml`, `k8s/configmap.yaml` + `secret*.yaml`, `docker-compose.yml` içinde geçen tüm `CERT_MONITOR_*` ve `cert.monitor.*` girdileri. Bu küme için properties'te zincirli fallback kur:
  ```properties
  site.monitor.secret-key=${SITE_MONITOR_SECRET_KEY:${CERT_MONITOR_SECRET_KEY:}}
  site.monitor.email.enabled=${SITE_MONITOR_EMAIL_ENABLED:${CERT_MONITOR_EMAIL_ENABLED:false}}
  # ... aynı desen: EMAIL_FROM, USERNAME, PASSWORD ve envanterde çıkan diğer her şey
  ```
  Her alias satırına `# geriye-uyum: eski ad, 2 sürüm sonra kaldırılacak` yorumu ekle (bekçi betiği bu yorumu istisna sayar).
- `.env.example`: yeni `SITE_MONITOR_*` adlarıyla yazılır; en altta "eski `CERT_MONITOR_*` adları geçiş süresince çalışır" notu.
- Uygulama açılışında eski adla set edilmiş env tespit edilirse **bir kez WARN logla** ("CERT_MONITOR_* kullanımdan kalkıyor, SITE_MONITOR_*'a geçin") — küçük bir startup kontrolü (`SiteMonitorApplication` ya da mevcut bir lifecycle listener).

### 3.4 Log dosyaları
- `logback-spring.xml`: `cert-monitor.log` / `cert-monitor-error.log` / `cert-monitor-audit.log` ve rotasyon desenleri → `site-monitor*`. Eski log dosyaları diskte kalır (silme); retention zamanla temizler.
- `application-prod.properties` log yolu `/var/log/cert-monitor` → `/var/log/site-monitor`; Helm/k8s'te bu yola bağlanan volume/mount varsa Faz 3'te birlikte güncellenir. Log toplayıcı (ör. filebeat/fluentd) deseni kullanan ekiplere geçiş planında not düşülür.

### 3.5 Frontend depolama anahtarları (tek seferlik göç)
- `main.jsx`'te bir `migrateStorageKeys()` çağrısı (uygulama başında, try/catch'li):
  - `cert-monitor-remembered-user` → `site-monitor-remembered-user`
  - `cm.` önekli TÜM localStorage anahtarları (`cm.pageSize.*`, `cm.banner.dismissedVersion`) → `sm.` öneki; sessionStorage `cm.session.active` → `sm.session.active`.
  - Kural: yeni anahtar yoksa eskiden kopyala, sonra eskiyi sil. Kod genelinde anahtar sabitlerini güncelle (`grep -rn "cm\.\|cert-monitor-" frontend/src`), `usePagination.js` `LS_PREFIX` dahil. `client.js`'teki `cm.session.active` kullanımını atlama.
- Bu göçe küçük bir vitest yaz (localStorage mock ile eski anahtar → yeni anahtara taşınıyor, ikinci çağrı idempotent).

### 3.6 Diğer
- `perf/k6-smoke.js`, `perf/seed-perf-data.sql`, `scripts/db-health.sql`, `scripts/perf-indexes.sql`, `scripts/smoke.ps1`, `start-*.bat`, `start-local.ps1`: yorum/başlık/çıktı metinlerindeki marka ve dosya adları.
- User-Agent / HTTP başlığı olarak `CertMonitor` gönderen yer var mı: `grep -rn "User-Agent\|USER_AGENT" backend/src/main/java` → varsa `SiteMonitor/<ver>` yap (WAF istisnaları eski UA'ya tanımlıysa geçiş planına not).

---

## 4. Faz 3 — Dağıtım adları (risk: yüksek — geçiş planıyla)

### 4.1 Docker
- İmaj adı `cert-monitor` → `site-monitor`: `scripts/build-image.ps1` + `build-image.sh` (`$ImageName`), `docker-compose.yml` (servis + image), `.github/workflows/docker-build.yml` ve `release.yml` imaj referansları, `Dockerfile` LABEL/başlıklar.
- Eski imajlar registry'de kalır; yeni push'lar yeni ada gider.

### 4.2 Helm
- Klasör `helm/cert-monitor` → `helm/site-monitor` (git mv); `Chart.yaml` `name: certmonitor-chart` → `sitemonitor-chart`; `_helpers.tpl` fullname/label helper'ları; `values.yaml` + `environments/*.yaml` (imaj adı, log path mount'u, env adları — §3.3 envanteriyle tutarlı); `templates/*` içindeki sabit `cert-monitor`/`certmonitor` adları; `NOTES.txt`.
- CI `ci.yml` helm-lint yolları ve `release.yml` chart yayınlama yolu/adı güncellenir.

### 4.3 k8s (ham manifestler)
- `k8s/*.yaml` 13 dosyada kaynak adları/label'lar/namespace: `cert-monitor` → `site-monitor` (`app:` label'ları, deployment/service/ingress/hpa/pdb/sa/networkpolicy/secret adları, `namespace.yaml`). `ingress.yaml`/`openshift-route.yaml` içindeki HOST alanlarına DOKUNMA (URL kapsam dışı) — sadece kaynak adları.

### 4.4 `docs/DEPLOY_GECIS_PLANI.md` (yeni dosya — komutun parçası olarak ÜRET)
Prod canlı olduğu için ad değişikliği "helm upgrade" ile yapılamaz (release ve kaynak adları değişiyor). Plan şunları içermeli:
1. Yeni imajı yeni adla build+push (eski imaj silinmez).
2. Secret/ConfigMap kopyalama: mevcut namespace'te `SITE_MONITOR_*` adlı yeni anahtarlarla secret oluştur (alias sayesinde eski adlar da çalışır — acele yok).
3. Kurulum stratejisi (infra ile seçilecek): (a) aynı namespace'e `helm install site-monitor` → hazır olunca eski `cert-monitor` release'ini uninstall; veya (b) bakım penceresinde uninstall+install. Spring Session JDBC + scheduler_lock DB'de olduğundan pod adları değişse de oturum/kilit korunur; iki release'in AYNI ANDA scheduler çalıştırması `scheduler_lock` sayesinde güvenlidir ama yine de eski release'i replicas=0'a çekerek geçiş önerilir.
4. Ingress/route: kaynak adı yenilenir, host AYNI kalır (URL kapsam dışı).
5. Doğrulama: `/health` UP, login, bir izleme detayı, e-posta testi; log dosyalarının yeni adla yazıldığı; Prometheus scrape hedefinin yeni pod'ları gördüğü.
6. Geri alma: eski release'i tekrar ölçekle/kur (imaj ve DB değişmedi — geri dönüş güvenli).
7. Takip: 2 sürüm sonra `CERT_MONITOR_*` alias'larının kaldırılacağı hatırlatması.

---

## 5. Faz 4 — Doğrulama (hepsi zorunlu)

1. `scripts/check-brand` çıktısı: yalnız bilinçli alias/geriye-uyum satırları + CHANGELOG/docs tarihsel kayıtları. Başka HER kalıntı düzeltilir.
2. Ters kontrol (alan dili bozulmamış): `git diff --stat` incelenir; `certificate_inventory`, `CertificateChecker`, `/api/certificates` gibi domain adlarında DEĞİŞİKLİK OLMADIĞI doğrulanır (`git diff | grep -i "certificate"` gözden geçirmesi).
3. `mvn -B clean verify` yeşil (paket taşıma sonrası Surefire/Jacoco dahil).
4. `npm run test` + `npm run build` yeşil; i18n parity yeşil.
5. `docker build` başarılı; `helm lint` üç environment values dosyasıyla yeşil.
6. Yerel duman: `mvn package -DskipTests` → `start-local.ps1` → `/health` UP → login → dashboard'da "Site Monitör" başlığı → bir keyword monitörü aç → SSL Checker çalışıyor → `backend/logs/site-monitor.log` oluştu.
7. Eski env adıyla çalışma testi: `.env`'de yalnız `CERT_MONITOR_SECRET_KEY` bırakıp başlat → uygulama açılıyor + WARN logu düşüyor (alias kanıtı).
8. CHANGELOG maddesi + `docs/DEPLOY_GECIS_PLANI.md` mevcut.
9. Commit mesajları: Faz 1 `feat(brand): ürün adı Site Monitör oldu`, Faz 2 `refactor(rename): kod içi kimlikler site-monitor`, Faz 3 `feat(deploy)!: dağıtım adları site-monitor` (breaking işareti + gövdede geçiş planına referans). `VERSION`'a elle DOKUNMA.

---

## 6. Faz 5 — GitHub repo adı: `certmonitor` → `site-monitor`

DİKKAT: GitHub'daki mevcut repo adı `cert-monitor` DEĞİL, **`certmonitor`**'dur (`github.com/inanmise/certmonitor`; origin: `https://github.com/inanmise/certmonitor.git`). Hedef ad: **`site-monitor`**.

Sıra önemli — önce Faz 1-4 birleşip main'e girsin, repo adı EN SONA yakın değişsin (açık PR'lar ve CI koşuları rename ortasında kalmasın).

1. **Repo içi URL referanslarını güncelle** (rename'den ÖNCE, ayrı commit):
   - `.github/workflows/release.yml` ~satır 258: `github.com/inanmise/certmonitor/compare/...` sabit URL'i `${{ github.repository }}` bağlam değişkenine çevir (gelecekteki rename'lerde de kırılmaz).
   - `Dockerfile` ~satır 46 LABEL'daki `github.com/inanmise/certmonitor` → `github.com/inanmise/site-monitor`.
   - Helm `Chart.yaml` `home:`/`sources:` alanlarındaki repo URL'leri → yeni ad.
   - README/dokümanlarda repo URL'i veya badge varsa güncelle (`grep -rn "inanmise/certmonitor"` → 0 kalmalı; CHANGELOG'daki tarihsel `your-org/cert-monitor` örneği kalabilir).
2. **Rename işlemi**: `gh` CLI kimlik doğrulamışsa `gh repo rename site-monitor -R inanmise/certmonitor`; değilse GitHub web → Settings → Repository name. (GitHub eski URL'den otomatik yönlendirme kurar; Actions secrets, branch protection, issue/PR geçmişi korunur.)
3. **Yerel remote güncelle**: `git remote set-url origin https://github.com/inanmise/site-monitor.git` → `git fetch` + `git push` ile doğrula. Başka klonlar varsa (iş VDI vb.) aynı komut oralarda da çalıştırılır — kapanış raporuna not.
4. **ghcr.io notu**: `docker-build.yml` registry'yi `ghcr.io/${{ github.repository_owner }}` üzerinden kurduğu için rename'den etkilenmez; imaj adı Faz 3'te zaten `site-monitor` oldu. Eski `certmonitor`/`cert-monitor` paketleri ghcr'de kalır (silme — eski sürümlere geri dönüş için lazım).
5. **Doğrulama**: rename sonrası küçük bir boş commit push'la → CI (`ci.yml`, `docker-build.yml`) yeni repo adı altında yeşil koşuyor mu; `release.yml` compare linki doğru üretiliyor mu kontrol et.

## 7. Faz 6 — Yerel klasör adı: `D:\cert-monitor` → `D:\site-monitor` (yarı-manuel, EN SON adım)

Claude Code kendi çalışma klasörünü yeniden adlandıramaz (içinde çalışıyor); bu fazda Claude Code **hazırlığı** yapar, yeniden adlandırmayı kullanıcı 2 dakikada tamamlar.

**Claude Code'un yapacağı hazırlık** (rename'den önce, son commit):
- Repo içindeki mutlak yol referanslarını yeni yola çevir (tespit edilenler):
  - `.claude/settings.local.json`: `Bash(tee D:/cert-monitor/logs/...)` izin satırları → `D:/site-monitor/...`
  - `.claude/skills/run/SKILL.md`: `cd D:\cert-monitor`, `Set-Location D:\cert-monitor\frontend` ve `Start-Process ... D:\cert-monitor\logs\...` satırları → `D:\site-monitor\...`
  - Son kontrol: `grep -rIn "cert-monitor" .claude/ .vscode/ *.ps1 *.bat` → mutlak yol kalmadı (docs/ tarihsel dosyaları hariç).
- Kullanıcıya aşağıdaki checklist'i kapanış raporunda aynen ver.

**Kullanıcının yapacağı (Claude Code oturumu kapatıldıktan sonra):**
1. Çalışan süreçleri durdur: backend java (`Get-Process java | Stop-Process`), Vite/node (5173 portu), IDE ve `D:\cert-monitor` açık olan tüm terminal/Explorer pencereleri (Windows dosya kilidi rename'i engeller).
2. PowerShell (klasörün DIŞINDAN): `Rename-Item 'D:\cert-monitor' 'site-monitor'`
3. IDE'de projeyi yeni yoldan aç; varsa kayıtlı workspace/launch yapılandırmalarındaki eski yolu düzelt.
4. Cowork/Claude oturumlarında bağlı klasörü kaldırıp `D:\site-monitor` olarak yeniden ekle.
5. Doğrulama: `D:\site-monitor` içinde `start-local.ps1` → `/health` UP; `npm run dev` → :5173 açılıyor; `git status` temiz, `git fetch` çalışıyor.

## 8. Kapsam DIŞI (kullanıcı kendisi ayarlayacak / bilinçli bırakılıyor)

- **Prod ve test DNS/URL ayarları** (`certmonitor-prod.akbank.com` → yeni adres): kullanıcı kendisi yapacak. `docs/DEPLOY_GECIS_PLANI.md`'de ingress/route HOST alanlarının değişmediği, DNS geçişinin kullanıcı tarafından ayrıca yapılacağı bir satırla not edilir.
- Log toplayıcı (filebeat vb.) desen güncellemeleri — geçiş planındaki notla altyapı ekibine.
- Kayıtlı eski logların, eski ghcr imaj/paketlerinin temizliği.

## 9. Kapanış raporu

Bitirince şunu raporla: faz bazında değişen dosya sayıları, alias bırakılan anahtar listesi, check-brand kalıntı listesi (gerekçeleriyle), test/build çıktı özetleri, GitHub rename'in tamamlandığı ve remote'un güncellendiği, Faz 6 kullanıcı checklist'i (yerel klasör) ve geçiş planının infra ekibiyle paylaşılmaya hazır olduğu.

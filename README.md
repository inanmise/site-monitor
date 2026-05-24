# SSL/TLS Sertifika İzleme Sistemi

Kurumunuzdaki SSL/TLS sertifikalarını, port erişilebilirliğini, HTTP/S çalışma süresini ve DNS kayıtlarını merkezi olarak izleyen, uyarı veren ve yöneten tam kapsamlı sistem.

## Özellikler

- **Otomatik Sertifika Kontrolü** — Yapılandırılabilir cron ile tüm envanteri tarar (varsayılan: her saat)
- **Çok Seviyeli Uyarı** — WARNING (30g) / HIGH (15g) / CRITICAL (7g) eşikleri, Admin Panel'den ayarlanabilir
- **Port İzleme** — TCP port erişilebilirlik kontrolü, gecikme ölçümü ve geçmiş grafiği
- **HTTP/S Uptime İzleme** — HTTP durum kodu, yanıt süresi ve SSL bilgisi takibi
- **DNS İzleme** — A/AAAA/CNAME/MX/TXT kayıt değişikliği tespiti
- **Zincir Doğrulama** — Eksik veya geçersiz sertifika zinciri tespiti (BouncyCastle)
- **İptal Kontrolü** — OCSP öncelikli, CRL yedekli iptal doğrulaması
- **Parmak İzi & Konu Sabitleme** — Sertifika değişimini otomatik tespit eder
- **Kullanıcı & Takım Yönetimi** — Çok kullanıcılı, rol tabanlı erişim (USER / AUDIT / ADMIN); takım bazlı envanter sahipliği
- **Eskalasyon Kişileri** — Org rol bazlı kişiler (PO, TECH, MANAGER, CLEVEL); e-posta + Teams/Slack webhook
- **Uyarı Yaşam Döngüsü** — Onayla, yeniden bildir, çöz; tam bildirim geçmişi
- **Sertifika Notları** — Domain başına dahili not ekleme
- **Denetim Günlüğü** — Tüm yönetici işlemleri (envanter, kullanıcı, takım) zaman damgalı kayıt
- **Sistem Sağlığı** — Heartbeat, SMTP istatistikleri, DB latency, tarama istatistikleri, HTTP metrikler
- **Zayıf Algoritma Raporu** — MD5/SHA1 imzalı veya kısa anahtar kullanan sertifikaların tespiti
- **Web Dashboard** — React SPA, koyu/açık mod, Türkçe/İngilizce
- **Admin Paneli** — Envanter, eşikler, kullanıcılar, takımlar, kişiler, uyarı geçmişi, denetim
- **REST API** — Harici sistemlerle entegrasyon
- **Prometheus Metrikleri** — `/api/admin/system/metrics` endpoint
- **Docker & Helm** — Üretim ortamına hazır konteyner ve Kubernetes desteği

## Teknoloji Yığını

| Katman | Teknoloji |
|--------|-----------|
| Backend | Java 21, Spring Boot 3.3.6, Spring Data JPA |
| Veritabanı | PostgreSQL |
| Sertifika | BouncyCastle (OCSP, CRL, zincir doğrulama) |
| Frontend | React 18.3.1, Vite 5, lucide-react |
| Konteyner | Docker (çok aşamalı), Docker Compose |
| K8s | Helm chart (sürüm `VERSION` dosyasından) |
| CI/CD | GitHub Actions (Node.js 24 runtime) |

## Proje Yapısı

```
cert-monitor/
├── backend/
│   └── src/main/java/com/certmonitor/
│       ├── controller/          # REST API (Certificate, Admin, Auth, Monitoring, Audit, System)
│       ├── service/             # İş mantığı (Checker, Escalation, Scheduler, PortChecker, DnsChecker…)
│       ├── model/               # JPA entity'leri
│       ├── repository/          # Spring Data repository'leri
│       └── config/              # WebConfig, GlobalExceptionHandler, AuthInterceptor
├── frontend/
│   └── src/
│       ├── components/          # React bileşenleri (Nav, StatsPanel, CertificatesTable, CertificateModal…)
│       ├── components/admin/    # Admin paneli (Inventory, Thresholds, Contacts, AlertHistory,
│       │                        #   UserManager, TeamManager, AuditLogViewer, SystemHealth,
│       │                        #   WeakAlgorithmReport)
│       ├── components/ui/       # Yeniden kullanılabilir bileşenler (SearchableSelect, Dialog,
│       │                        #   DateTimeRangePicker, CertMonitorLogo)
│       ├── pages/               # ExpiryForecastPage
│       ├── api/client.js        # API istemcisi
│       └── i18n/                # Çok dil & tema yönetimi
├── helm/cert-monitor/           # Helm chart + ortam değerleri
│   └── environments/            # master.yaml, develop.yaml, release.yaml
├── scripts/                     # build-image.sh / build-image.ps1
├── .github/workflows/           # ci.yml, docker-build.yml, release.yml
├── Dockerfile                   # Çok aşamalı build (Node → Maven → JRE 21)
├── docker-compose.yml
└── sertifikaListesi.txt         # Dosya tabanlı domain listesi (isteğe bağlı)
```

## Kurulum

### Gereksinimler

- Java 21 (Azul Zulu / Eclipse Temurin)
- Maven 3.9+
- Node.js 20+

### Yerel Geliştirme

**1. Backend**

```bash
cd backend
mvn spring-boot:run
```

Backend `http://localhost:8080` adresinde çalışır.

**2. Frontend**

```bash
cd frontend
npm install
npm run dev
```

Frontend `http://localhost:5173` adresinde çalışır.

**3. Giriş**

| Ayar | Varsayılan |
|------|-----------|
| Kullanıcı adı | `user` |
| Parola | `password` |

> Ortam değişkenlerini özelleştirmek için `.env.example` dosyasını `.env` olarak kopyalayın.

### Docker Compose ile Başlatma

```bash
cp .env.example .env
# .env içindeki parolaları düzenleyin
docker-compose up -d
```

Uygulama `http://localhost:8080` adresinde çalışır.

### Docker İmajı Oluşturma

```bash
# Linux/Mac
./scripts/build-image.sh

# Windows
.\scripts\build-image.ps1

# Registry'ye push ile
./scripts/build-image.sh --registry ghcr.io/your-org --push
```

İmaj etiketleri branch'e göre otomatik belirlenir:

| Branch | Etiket |
|--------|--------|
| `master` | `{VERSION}`, `latest` |
| `release/*` | `{VERSION}-rc`, `staging` |
| `develop` | `develop-{sha}`, `develop` |
| diğer | `{branch-adı}-{sha}` |

## Yapılandırma

Tüm ayarlar ortam değişkeni ile yönetilir. Varsayılanlar `application.properties` içindedir.

| Değişken | Varsayılan | Açıklama |
|----------|-----------|----------|
| `CERT_MONITOR_USERNAME` | `user` | Dashboard kullanıcı adı |
| `CERT_MONITOR_PASSWORD` | `password` | Dashboard parolası |
| `DB_URL` | `jdbc:postgresql://localhost:5432/certmonitor` | Veritabanı bağlantı URL'i |
| `DB_POOL_MAX` | `10` | Maksimum bağlantı havuzu boyutu |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,...` | İzin verilen CORS adresleri |
| `CERT_MONITOR_EMAIL_ENABLED` | `false` | E-posta bildirimlerini etkinleştir |
| `SPRING_MAIL_HOST` | `smtp.gmail.com` | SMTP sunucusu |
| `SPRING_MAIL_PORT` | `587` | SMTP portu |
| `SPRING_MAIL_USERNAME` | — | SMTP kullanıcı adı |
| `SPRING_MAIL_PASSWORD` | — | SMTP parolası |
| `SCHEDULER_CRON` | `0 0 * * * *` | Sertifika kontrol zamanı (her saat) |
| `PARALLEL_WORKERS` | `20` | Paralel kontrol iş parçacığı sayısı |
| `LOGIN_MAX_ATTEMPTS` | `10` | Ardışık başarısız giriş limiti |
| `COOKIE_SECURE` | `false` | HTTPS ortamında `true` yapın |

Üretim ortamı için ek ayarlar `application-prod.properties` dosyasında (`spring.profiles.active=prod`).

### Zamanlayıcı Ayarı

`SCHEDULER_CRON` ortam değişkeni ile ya da `application.properties` içinde:

```properties
cert.monitor.scheduler.cron=0 0 2 * * *
```

Yukarıdaki örnek her gece 02:00'de çalıştırır. Varsayılan `0 0 * * * *` (her saat başı).

### Uyarı Eşikleri

Admin Paneli → Eşikler sekmesinden arayüz üzerinden ya da `ALERT_DEFAULT_*` değişkenleriyle değiştirilebilir.

Varsayılanlar:

| Seviye | Gün |
|--------|-----|
| WARNING | 30 |
| HIGH | 15 |
| CRITICAL | 7 |

### Domain Listesi

`sertifikaListesi.txt` dosyasına her satıra bir domain yazılır:

```
# Yorum satırı
google.com
github.com:443
api.example.com:8443
internal.app.com:8000
```

Admin Paneli → Envanter üzerinden de domain eklenebilir.

## Kullanım

### Web Dashboard

Giriş yaptıktan sonra:

1. **Dashboard** — Özet istatistikler ve sertifika kartları
2. **Tüm Sertifikalar** — Sayfalanmış, filtrelenebilir tablo
3. **Port İzleme** — TCP port durumu ve geçmişi
4. **Uptime** — HTTP/S erişilebilirlik ve SSL süresi takibi
5. **DNS** — DNS kayıt değişikliği izleme
6. **Yenileme Tavsiyeleri** — Yakında dolacak sertifikalar için öneri listesi
7. **Aktivite** — Son kontrol ve işlem günlüğü
8. **Admin** — Envanter, kullanıcılar, takımlar, eşikler, eskalasyon kişileri, uyarılar, denetim, sistem sağlığı

Herhangi bir sertifikaya tıklayarak detaylar, uyarı geçmişi ve notlar görüntülenebilir.

### Manuel Kontrol

Dashboard'da "Şimdi Kontrol Et" düğmesine basın ya da:

```bash
curl -u user:password -X POST http://localhost:8080/api/scheduler/run
```

Belirli bir domain'i hemen kontrol etmek için:

```bash
curl -u user:password http://localhost:8080/api/check/example.com
```

## API Endpoints

Tüm istekler HTTP Basic Auth veya oturum çerezi gerektirir.

### Sertifikalar

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/certificates` | Tüm son kontrol sonuçları |
| GET | `/api/certificates/list` | Sayfalanmış + filtrelenmiş liste |
| GET | `/api/warnings` | Uyarı gerektiren sertifikalar |
| GET | `/api/history/{domain}` | Domain kontrol geçmişi (son 30) |
| GET | `/api/history/{domain}/alerts` | Domain uyarı olayları |
| GET | `/api/check/{domain}` | Domain'i hemen kontrol et |
| GET | `/api/activity` | Aktivite logu (varsayılan son 24 saat) |
| GET | `/api/stats` | İstatistikler (toplam, uyarı, kritik, vb.) |
| GET | `/api/stats/teams` | Takım bazlı istatistikler |
| GET | `/api/renewal-advice` | Yenileme önerileri |
| GET | `/api/alerts/silent-domains` | Sessiz domain listesi |
| POST | `/api/scheduler/run` | Zamanlayıcıyı hemen çalıştır |
| GET | `/api/scheduler/status` | Zamanlayıcı durumu |

### Admin — Envanter

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/admin/inventory` | Envanter listesi (`?showDeleted=true` ile silinenleri dahil et) |
| POST | `/api/admin/inventory` | Domain ekle |
| PUT | `/api/admin/inventory/{id}` | Domain güncelle |
| DELETE | `/api/admin/inventory/{id}` | Domain'i soft-delete ile işaretle |
| POST | `/api/admin/inventory/{id}/restore` | Silinen domain'i geri getir |
| POST | `/api/admin/inventory/{id}/transfer` | SY ekibi transferi |
| POST | `/api/admin/inventory/{id}/transfer-ug` | UG ekibi transferi |

### Admin — Eşikler & Uyarılar

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/admin/thresholds` | Uyarı eşikleri |
| POST | `/api/admin/thresholds` | Yeni eşik oluştur |
| PUT | `/api/admin/thresholds/{id}` | Eşik güncelle |
| GET | `/api/admin/alerts` | Uyarı olayları |
| POST | `/api/admin/alerts/{id}/acknowledge` | Uyarıyı onayla |
| POST | `/api/admin/alerts/{id}/resolve` | Uyarıyı çöz |
| POST | `/api/admin/alerts/{id}/re-notify` | Yeniden bildir |
| GET | `/api/admin/alerts/{id}/notifications` | Bildirim geçmişi |

### Admin — Kullanıcılar & Takımlar

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/admin/users` | Kullanıcı listesi |
| POST | `/api/admin/users` | Kullanıcı ekle |
| PUT | `/api/admin/users/{id}` | Kullanıcı güncelle |
| POST | `/api/admin/users/{id}/reset-password` | Parola sıfırla |
| POST | `/api/admin/users/{id}/unlock` | Hesap kilidini aç |
| DELETE | `/api/admin/users/{id}` | Kullanıcı sil |
| GET | `/api/admin/teams` | Takım listesi |
| POST | `/api/admin/teams` | Takım ekle |
| PUT | `/api/admin/teams/{id}` | Takım güncelle |
| DELETE | `/api/admin/teams/{id}` | Takım sil |
| GET | `/api/admin/teams/{id}/users` | Takım üyeleri |

### Admin — Kişiler & Notlar

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/admin/contacts` | Aktif eskalasyon kişileri |
| GET | `/api/admin/contacts/all` | Tüm eskalasyon kişileri |
| POST | `/api/admin/contacts` | Kişi ekle |
| PUT | `/api/admin/contacts/{id}` | Kişi güncelle |
| DELETE | `/api/admin/contacts/{id}` | Kişi sil |
| GET | `/api/admin/notes/{domain}` | Domain notları |
| POST | `/api/admin/notes/{domain}` | Not ekle |
| DELETE | `/api/admin/notes/{domain}/{noteId}` | Not sil |

### İzleme (Monitoring)

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/monitoring/uptime/overview` | Tüm domain'lerin uptime özeti |
| GET | `/api/monitoring/uptime/{domain}/history` | Uptime geçmişi |
| GET | `/api/monitoring/uptime/{domain}/http-history` | HTTP kontrol geçmişi |
| GET | `/api/monitoring/uptime/{domain}/ssl-history` | SSL kontrol geçmişi |
| GET | `/api/monitoring/port` | Port monitör listesi |
| POST | `/api/monitoring/port` | Port monitör ekle |
| PUT | `/api/monitoring/port/{id}` | Port monitör güncelle |
| DELETE | `/api/monitoring/port/{id}` | Port monitör sil |
| GET | `/api/monitoring/port/{id}/history` | Port kontrol geçmişi |
| POST | `/api/monitoring/port/{id}/check` | Port'u hemen kontrol et |
| GET | `/api/monitoring/dns` | DNS monitör listesi |
| POST | `/api/monitoring/dns` | DNS monitör ekle |
| PUT | `/api/monitoring/dns/{id}` | DNS monitör güncelle |
| DELETE | `/api/monitoring/dns/{id}` | DNS monitör sil |
| GET | `/api/monitoring/dns/{id}/history` | DNS değişim geçmişi |
| POST | `/api/monitoring/dns/{id}/check` | DNS'i hemen kontrol et |

### Denetim & Sistem

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/audit` | Denetim günlüğü |
| GET | `/api/audit/stats` | Denetim istatistikleri |
| GET | `/api/audit/weak-algorithms` | Zayıf algoritma raporu |
| GET | `/api/admin/system` | Sistem sağlığı özeti |
| GET | `/api/admin/system/smtp-logs` | SMTP gönderim geçmişi |
| GET | `/api/admin/system/db-stats` | PostgreSQL tablo istatistikleri |
| GET | `/api/admin/system/metrics` | Prometheus metrikleri |
| GET | `/api/admin/system/http-metrics` | HTTP istek metrikleri |
| POST | `/api/admin/system/heartbeat` | Heartbeat kaydı |
| DELETE | `/api/admin/system/scheduler-lock` | Zamanlayıcı kilidini serbest bırak |

### Auth

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| POST | `/api/login` | Giriş yap |
| POST | `/api/logout` | Çıkış yap |
| GET | `/api/me` | Mevcut oturum bilgisi |

### Örnek API Yanıtı

```bash
curl -u user:password http://localhost:8080/api/certificates
```

```json
{
  "success": true,
  "data": [
    {
      "domain": "google.com",
      "subject": "google.com",
      "issuer_cn": "WR2",
      "not_before": "2026-03-17T08:22:36",
      "not_after": "2026-06-09T08:22:35",
      "days_remaining": 16,
      "alert_level": "high",
      "warning": true,
      "status": "warning",
      "checked_at": "2026-05-24T02:00:00"
    }
  ],
  "timestamp": "2026-05-24T10:00:00"
}
```

## Veritabanı

PostgreSQL kullanılır. Bağlantı `DB_URL` ortam değişkeniyle yapılandırılır (varsayılan: `jdbc:postgresql://localhost:5432/certmonitor`).

**Tablolar:**

| Tablo | Açıklama |
|-------|----------|
| `certificate_checks` | Tüm kontrol geçmişi |
| `latest_checks` | Domain başına en son sonuç |
| `certificate_inventory` | Yönetilen domain envanteri (soft-delete destekli) |
| `alert_thresholds` | Uyarı eşik tanımları |
| `alert_events` | Uyarı olayları ve durumları |
| `escalation_contacts` | Bildirim alıcıları |
| `notification_logs` | E-posta / webhook gönderim geçmişi |
| `app_users` | Kullanıcı hesapları ve roller |
| `teams` | Takım tanımları |
| `certificate_notes` | Domain başına dahili notlar |
| `port_monitors` | İzlenen TCP port'ları |
| `port_checks` | Port kontrol geçmişi |
| `uptime_checks` | HTTP/S uptime kontrol geçmişi |
| `dns_monitors` | İzlenen DNS kayıtları |
| `dns_records` | DNS değişim geçmişi |
| `audit_logs` | Yönetici işlem günlüğü |
| `system_heartbeats` | Sistem canlılık kayıtları |
| `remember_me_tokens` | "Beni hatırla" oturum token'ları |

## Kubernetes Dağıtımı

```bash
VERSION=$(cat VERSION)
helm upgrade --install cert-monitor ./helm/cert-monitor \
  -f helm/cert-monitor/values.yaml \
  -f helm/cert-monitor/environments/master.yaml \
  --set image.tag=${VERSION} \
  --set secret.adminPassword=$ADMIN_PASSWORD \
  --set secret.dbPassword=$DB_PASSWORD \
  -n cert-monitor --create-namespace
```

Ortam bazlı değer dosyaları:

| Dosya | Ortam | Özellikler |
|-------|-------|-----------|
| `environments/master.yaml` | Üretim | 3 replica, HPA min:3/max:10, PDB |
| `environments/release.yaml` | Staging | 2 replica, HPA min:2/max:5 |
| `environments/develop.yaml` | Geliştirme | 1 replica, HPA kapalı, e-posta kapalı |

## Güvenlik

- Tüm kimlik bilgileri ortam değişkenlerinden okunur — kod içinde hardcoded değer yok
- HTTP güvenlik başlıkları: `X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`, `Referrer-Policy`, `Content-Security-Policy`, `Permissions-Policy`
- CORS yalnızca yapılandırılmış kaynaklara izin verir
- Brute-force koruma: ardışık başarısız girişlerde artan süre kilitleme
- GeoIP tabanlı giriş anomali tespiti (ofis saatleri dışı, coğrafi hız saldırısı)
- Konteyner root olmayan kullanıcı (UID 1000) ile çalışır
- Konteyner kök dosya sistemi salt okunur
- K8s pod güvenlik bağlamında tüm yetenekler düşürülür
- TLS Ingress üzerinden zorunlu (`ssl-redirect: true`)

## Sorun Giderme

### "Port 8080 zaten kullanımda"

`application.properties` içinde veya `SERVER_PORT` ortam değişkeniyle:

```properties
server.port=8081
```

### "Sertifika kontrol edilemiyor"

1. Ağ bağlantısını ve güvenlik duvarı kurallarını kontrol edin
2. `curl -v https://domain.com` ile doğrudan test edin
3. Backend günlüklerini inceleyin: `docker logs cert-monitor`

### "Dashboard boş görünüyor"

1. Tarayıcı konsolunda hata olup olmadığını kontrol edin (F12)
2. `sertifikaListesi.txt` dosyasında veya envanterinde domain olduğunu doğrulayın
3. "Şimdi Kontrol Et" düğmesine basarak ilk kontrolü tetikleyin

### "E-posta gönderilmiyor"

1. `CERT_MONITOR_EMAIL_ENABLED=true` olduğunu doğrulayın
2. SMTP kimlik bilgilerini kontrol edin
3. Admin Paneli → Sistem Sağlığı → SMTP istatistiklerinde hata mesajını inceleyin

### "Zamanlayıcı çalışmıyor"

1. Admin Paneli → Sistem Sağlığı'ndan son tarama zamanını kontrol edin
2. Birden fazla pod varsa dağıtık kilit nedeniyle yalnızca bir pod çalıştırır (bu beklenen davranıştır)
3. Kilidi zorla serbest bırakmak için: `DELETE /api/admin/system/scheduler-lock`

## Performans

- Varsayılan olarak 20 paralel iş parçacığı ile kontrol yapılır (`PARALLEL_WORKERS`)
- Maksimum iş parçacığı havuzu: 50 (`EXECUTOR_MAX_SIZE`)
- Tipik süreler: 50 domain ≈ 5-10s, 200 domain ≈ 15-30s

## Lisans

Dahili kullanım için tasarlanmıştır.

---

**Sürüm:** 15.0.0 — Son Güncelleme: 24 Mayıs 2026

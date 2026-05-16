# SSL/TLS Sertifika İzleme Sistemi

Kurumunuzdaki SSL/TLS sertifikalarını merkezi olarak izleyen, uyarı veren ve yöneten tam kapsamlı sistem.

## Özellikler

- **Otomatik Günlük Kontrol** — Ayarlanabilir saatte (varsayılan 02:00) tüm envanteri kontrol eder
- **Çok Seviyeli Uyarı** — WARNING (30g) / HIGH (15g) / CRITICAL (7g) eşikleri, ayarlanabilir
- **Zincir Doğrulama** — Eksik veya geçersiz sertifika zinciri tespiti (BouncyCastle)
- **İptal Kontrolü** — OCSP öncelikli, CRL yedekli iptal doğrulaması
- **Parmak İzi & Konu Sabitleme** — Sertifika değişimini otomatik tespit eder
- **Eskalasyon Kişileri** — Rol bazlı kişiler (PO, TECH, MANAGER, CLEVEL) e-posta + Teams/Slack webhook
- **Uyarı Yaşam Döngüsü** — Onayla, yeniden bildir, çöz; tam denetim kaydı
- **Web Dashboard** — React SPA, koyu/açık mod, Türkçe/İngilizce
- **Admin Paneli** — Envanter, eşikler, kişiler, uyarı geçmişi
- **REST API** — Harici sistemlerle entegrasyon
- **Prometheus Metrikleri** — `/metrics` endpoint
- **Docker & Helm** — Üretim ortamına hazır konteyner ve Kubernetes desteği

## Teknoloji Yığını

| Katman | Teknoloji |
|--------|-----------|
| Backend | Java 21, Spring Boot 3.3.6, Spring Data JPA |
| Veritabanı | SQLite (geliştirme) / PostgreSQL (üretim) |
| Sertifika | BouncyCastle (OCSP, CRL, zincir doğrulama) |
| Frontend | React 18.3.1, Vite 5, lucide-react |
| Konteyner | Docker (çok aşamalı), Docker Compose |
| K8s | Helm chart v0.1.0 |
| CI/CD | GitHub Actions |

## Proje Yapısı

```
cert-monitor/
├── backend/
│   └── src/main/java/com/certmonitor/
│       ├── controller/          # REST API (Certificate, Admin, Auth)
│       ├── service/             # İş mantığı (Checker, Escalation, Scheduler…)
│       ├── model/               # JPA entity'leri
│       ├── repository/          # Spring Data repository'leri
│       └── config/              # WebConfig, GlobalExceptionHandler, AuthInterceptor
├── frontend/
│   └── src/
│       ├── components/          # React bileşenleri (Nav, StatsPanel, CertificatesTable…)
│       ├── components/admin/    # Admin paneli (Inventory, Thresholds, Contacts, AlertHistory)
│       ├── pages/               # Login
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
| Parola | `changeme-local-dev` |

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
| `CERT_MONITOR_PASSWORD` | `changeme-local-dev` | Dashboard parolası |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,...` | İzin verilen CORS adresleri |
| `CERT_MONITOR_EMAIL_ENABLED` | `false` | E-posta bildirimlerini etkinleştir |
| `SPRING_MAIL_HOST` | `smtp.gmail.com` | SMTP sunucusu |
| `SPRING_MAIL_PORT` | `587` | SMTP portu |
| `SPRING_MAIL_USERNAME` | — | SMTP kullanıcı adı |
| `SPRING_MAIL_PASSWORD` | — | SMTP parolası |

Üretim ortamı için ek ayarlar `application-prod.properties` dosyasında (`spring.profiles.active=prod`).

### Günlük Kontrol Saati

`application.properties` içinde:

```properties
cert.monitor.check.hour=2
cert.monitor.check.minute=0
```

### Uyarı Eşikleri

Admin Paneli → Eşikler sekmesinden arayüz üzerinden ya da doğrudan API ile değiştirilebilir.

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

1. **Dashboard** — Özet istatistikler ve kritik sertifikalar
2. **Uyarılar** — Dikkat gerektiren sertifikalar
3. **Tüm Sertifikalar** — Sayfalanmış, filtrelenebilir tablo
4. **Admin** — Envanter, eşikler, eskalasyon kişileri, uyarı geçmişi

Herhangi bir sertifikaya tıklayarak detay, kontrol geçmişi ve uyarı olaylarını görüntüleyebilirsiniz.

### Manuel Kontrol

Dashboard'da "Şimdi Kontrol Et" düğmesine basın ya da:

```bash
curl -u user:changeme-local-dev -X POST http://localhost:8080/api/scheduler/run
```

Belirli bir domain'i hemen kontrol etmek için:

```bash
curl -u user:changeme-local-dev http://localhost:8080/api/check/example.com
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
| GET | `/api/stats` | İstatistikler |
| GET | `/api/renewal-advice` | Yenileme önerileri |
| POST | `/api/scheduler/run` | Zamanlayıcıyı hemen çalıştır |
| GET | `/api/scheduler/status` | Zamanlayıcı durumu |

### Admin

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/api/admin/inventory` | Envanter listesi |
| POST | `/api/admin/inventory` | Domain ekle |
| PUT | `/api/admin/inventory/{id}` | Domain güncelle |
| DELETE | `/api/admin/inventory/{id}` | Domain sil |
| GET | `/api/admin/thresholds` | Uyarı eşikleri |
| POST | `/api/admin/thresholds` | Yeni eşik oluştur |
| PUT | `/api/admin/thresholds/{id}` | Eşik güncelle |
| GET | `/api/admin/contacts` | Aktif eskalasyon kişileri |
| GET | `/api/admin/contacts/all` | Tüm eskalasyon kişileri |
| POST | `/api/admin/contacts` | Kişi ekle |
| PUT | `/api/admin/contacts/{id}` | Kişi güncelle |
| DELETE | `/api/admin/contacts/{id}` | Kişi sil |
| GET | `/api/admin/alerts` | Uyarı olayları |
| POST | `/api/admin/alerts/{id}/acknowledge` | Uyarıyı onayla |
| POST | `/api/admin/alerts/{id}/resolve` | Uyarıyı çöz |
| POST | `/api/admin/alerts/{id}/re-notify` | Yeniden bildir |
| GET | `/api/admin/alerts/{id}/notifications` | Bildirim geçmişi |

### Auth

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| POST | `/api/login` | Giriş yap |
| POST | `/api/logout` | Çıkış yap |
| GET | `/api/me` | Mevcut oturum bilgisi |

### Örnek API Yanıtı

```bash
curl -u user:changeme-local-dev http://localhost:8080/api/certificates
```

```json
{
  "success": true,
  "data": [
    {
      "domain": "google.com",
      "subject": "google.com",
      "issuerCn": "WR2",
      "notBefore": "2026-03-17T08:22:36",
      "notAfter": "2026-06-09T08:22:35",
      "daysRemaining": 24,
      "warning": true,
      "status": "warning",
      "checkedAt": "2026-05-16T02:00:00"
    }
  ],
  "timestamp": "2026-05-16T10:00:00"
}
```

## Veritabanı

Geliştirmede SQLite (`data/certificates.db`) otomatik oluşturulur.

**Tablolar:**

| Tablo | Açıklama |
|-------|----------|
| `certificate_checks` | Tüm kontrol geçmişi |
| `latest_checks` | Domain başına en son sonuç |
| `certificate_inventory` | Yönetilen domain envanteri |
| `alert_thresholds` | Uyarı eşik tanımları |
| `alert_events` | Uyarı olayları ve durumları |
| `escalation_contacts` | Bildirim alıcıları |
| `notification_logs` | E-posta / webhook gönderim geçmişi |

## Kubernetes Dağıtımı

```bash
helm upgrade --install cert-monitor ./helm/cert-monitor \
  -f helm/cert-monitor/values.yaml \
  -f helm/cert-monitor/environments/master.yaml \
  --set image.tag=1.0.0 \
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
- Konteyner root olmayan kullanıcı (UID 1000) ile çalışır
- Konteyner kök dosya sistemi salt okunur
- K8s pod güvenlik bağlamında tüm yetenekler düşürülür
- TLS Ingress üzerinden zorunlu (`ssl-redirect: true`)

## Sorun Giderme

### "Port 8080 zaten kullanımda"

`application.properties` içinde:

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

### E-posta gönderilmiyor

1. `CERT_MONITOR_EMAIL_ENABLED=true` olduğunu doğrulayın
2. SMTP kimlik bilgilerini kontrol edin
3. Admin Paneli → Uyarı Geçmişi → ilgili uyarı → Bildirim geçmişinde hata mesajını inceleyin

## Performans

- Varsayılan olarak 20 paralel iş parçacığı ile kontrol yapılır
- `cert.monitor.workers=N` ile artırılabilir
- Tipik süreler: 50 domain ≈ 5-10s, 200 domain ≈ 15-30s

## Lisans

Dahili kullanım için tasarlanmıştır.

---

**Sürüm:** 1.0.0 — Son Güncelleme: 16 Mayıs 2026

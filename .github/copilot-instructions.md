# SSL/TLS Sertifika İzleme Sistemi — Geliştirici Rehberi

## Proje Açıklaması

Kurumsal SSL/TLS sertifikalarını otomatik izleyen, çok seviyeli uyarı veren ve yöneten tam yığın bir web uygulamasıdır.

## Teknoloji Yığını

- **Backend:** Java 25, Spring Boot 4.1.0, Spring Data JPA, BouncyCastle
- **Veritabanı:** PostgreSQL
- **Frontend:** React 18.3.1, Vite 5, lucide-react
- **Konteyner:** Docker (çok aşamalı build), Docker Compose
- **K8s:** Helm chart v0.1.0
- **CI/CD:** GitHub Actions

## Proje Yapısı

```
site-monitor/
├── backend/src/main/java/com/sitemonitor/
│   ├── controller/        # CertificateController, AdminController, AuthController
│   ├── service/           # CertificateService, CertificateCheckerService,
│   │                      # ChainValidationService, SchedulerService,
│   │                      # EscalationService, EmailNotificationService,
│   │                      # WebhookService, RememberMeService
│   ├── model/             # CertificateCheck, LatestCheck, CertificateInventory,
│   │                      # AlertThreshold, AlertEvent, EscalationContact,
│   │                      # NotificationLog
│   ├── repository/        # Spring Data JPA repository arayüzleri
│   └── config/            # WebConfig (CORS + güvenlik başlıkları),
│                          # GlobalExceptionHandler, AuthInterceptor
├── frontend/src/
│   ├── components/        # Nav, StatsPanel, CertificatesTable, CertificateCard,
│   │                      # CertificateModal, ActivityLog, RenewalAdvice
│   ├── components/admin/  # AdminPanel, InventoryManager, AlertThresholds,
│   │                      # EscalationContacts, AlertHistory
│   ├── components/ui/     # Dialog
│   ├── pages/             # Login
│   ├── api/client.js      # Fetch tabanlı API istemcisi
│   └── i18n/              # index.jsx (useT hook), theme.jsx (koyu/açık mod)
├── helm/site-monitor/     # Helm chart
│   └── environments/      # master.yaml, develop.yaml, release.yaml
├── scripts/               # build-image.sh, build-image.ps1
└── .github/workflows/     # ci.yml, docker-build.yml, release.yml
```

## API Endpoints

**Temel — `/api`**

- `GET /certificates` — Tüm son kontrol sonuçları
- `GET /certificates/list` — Sayfalanmış, filtrelenmiş liste
- `GET /warnings` — Uyarı gerektiren sertifikalar
- `GET /history/{domain}` — Domain kontrol geçmişi (son 30)
- `GET /history/{domain}/alerts` — Domain uyarı olayları
- `GET /check/{domain}` — Domain'i hemen kontrol et
- `GET /activity` — Aktivite logu
- `GET /stats` — İstatistikler
- `GET /renewal-advice` — Yenileme önerileri
- `POST /scheduler/run` — Zamanlayıcıyı tetikle
- `GET /scheduler/status` — Zamanlayıcı durumu

**Admin — `/api/admin`**

- `GET/POST /inventory` — Domain envanteri
- `PUT/DELETE /inventory/{id}` — Envanter güncelle/sil
- `GET/POST /thresholds` — Uyarı eşikleri
- `PUT /thresholds/{id}` — Eşik güncelle
- `GET/POST /contacts` — Eskalasyon kişileri
- `PUT/DELETE /contacts/{id}` — Kişi güncelle/sil
- `GET /alerts` — Uyarı olayları
- `POST /alerts/{id}/acknowledge` — Uyarıyı onayla
- `POST /alerts/{id}/resolve` — Uyarıyı çöz
- `POST /alerts/{id}/re-notify` — Yeniden bildir
- `GET /alerts/{id}/notifications` — Bildirim geçmişi

**Auth — `/api`**

- `POST /login`, `POST /logout`, `GET /me`

## Veri Modelleri

**CertificateCheck / LatestCheck:** domain, subject, issuer, issuerCn, notBefore, notAfter,
daysRemaining, warning (boolean), status (valid/warning/high/critical/error),
error, san, runId, checkedAt, chainValid, ocspStatus, crlStatus, fingerprint

**CertificateInventory:** domain, port (443), description, owner, tags, active,
expectedFingerprint, expectedSubject, createdAt, updatedAt

**AlertThreshold:** name, warningDays (30), highDays (15), criticalDays (7),
reAlertIntervalHours (24), active

**AlertEvent:** domain, severity (WARNING/HIGH/CRITICAL), status (OPEN/ACKNOWLEDGED/RESOLVED),
message, acknowledgedBy, acknowledgedAt, resolvedBy, resolvedAt, createdAt

**EscalationContact:** name, email, role (PO/TECH/MANAGER/CLEVEL),
minAlertLevel (WARNING/HIGH/CRITICAL), webhookUrl, webhookType (TEAMS/SLACK), active

**NotificationLog:** alertEvent (FK), contact (FK), type (EMAIL/WEBHOOK),
status (SENT/FAILED), error, sentAt

## Frontend Notları

- Tüm ikonlar `lucide-react`'tan — emoji kullanılmaz
- Tema `[data-theme="dark"]` CSS özelliği ile yönetilir; `useTheme()` hook'u ile erişilir
- Çok dil `useT(key)` hook'u ile çalışır; dil localStorage'da saklanır
- API base URL `/api`'dir; geliştirmede Vite proxy 5173 → 8080 yönlendirir
- 401 yanıtı otomatik `null` döndürür; `App.jsx` oturumu sonlandırır

## Güvenlik Kuralları

- Kod içinde hardcoded kimlik bilgisi ekleme — ortam değişkeni kullan
- CORS `WebConfig.java` içinde `${CORS_ALLOWED_ORIGINS}` ile yapılandırılır
- Güvenlik başlıkları `OncePerRequestFilter` ile eklenir (WebConfig içinde)
- `k8s/secret.yaml` `.gitignore` içindedir; template olarak `secret.example.yaml` kullanılır

## Geliştirme Ortamı

Backend: `http://localhost:8080`  
Frontend (dev): `http://localhost:5173`  
Varsayılan kimlik: `user` / `changeme-local-dev`

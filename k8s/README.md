# `k8s/` — LEGACY, bakımı yapılmıyor

> **Prod ve tüm ortamların tek yetkili kaynağı Helm chart'ıdır: [`helm/site-monitor/`](../helm/site-monitor/).**
> Dağıtım komutu: [`docs/PROD_DEPLOY_CHECKLIST.md`](../docs/PROD_DEPLOY_CHECKLIST.md).
> CI yalnız chart'ı doğrular (`ci.yml` → `helm lint`, üç ortam); bu dizindeki manifestler hiçbir kapıdan geçmez.

Bu dizin, chart'tan önceki elle yazılmış manifestlerdir. Yalnız **referans** için duruyor (ör. NetworkPolicy
ve OpenShift Route örnekleri). Kod ve chart ilerledikçe güncellenmedi; **olduğu gibi `kubectl apply`
edilirse uygulama ya hiç açılmaz ya da yanlış yapılandırmayla açılır.**

## Bilinen uyumsuzluklar (2026-09-26 itibarıyla)

| Dosya | Sorun | Sonuç |
|---|---|---|
| `secret.example.yaml` | `SITE_MONITOR_SECRET_KEY` yok | prod profili açılışta durur (SecretCipher fail-fast) |
| `secret.example.yaml` | `SMTP_USER`, `SMTP_PASSWORD`, `EMAIL_TO`, `EMAIL_FROM` — kodda karşılığı yok (doğrusu `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `SITE_MONITOR_EMAIL_FROM`) | SMTP kimliği uygulamaya ulaşmaz |
| `secret.example.yaml` | `HTTP_PROXY_USER` / `HTTP_PROXY_PASS` yok | kimlikli vekil arkasında dış kontroller düşer |
| `configmap.yaml` | `DB_NAME` / `DB_USER` = `certmonitor`, `postgres.yaml` ise `sitemonitor` yaratır | uygulama DB'ye bağlanamaz |
| `configmap.yaml` | `CERT_CHECK_TIMEOUT`, `CERT_WARNING_DAYS`, `CERT_PARALLEL_WORKERS`, `CERT_CHECK_HOUR`, `CERT_CHECK_MINUTE` — kodda karşılığı yok | sessizce yok sayılır |
| `configmap.yaml` | `LOG_LEVEL`, `DB_POOL_MAX`, `HTTP_PROXY_HOST/PORT`, `NO_PROXY`, `APP_BASE_URL`, `CORS_ALLOWED_ORIGINS`, executor ayarları yok | vekil yok, CORS boş, e-posta linkleri yanlış host |
| `deployment.yaml` | imaj `site-monitor:1.0.0` (registry'siz, eski sürüm); release imajı `ghcr.io/<owner>/site-monitor:vX.Y.Z` | `ImagePullBackOff` |
| `deployment.yaml` | limit 8Gi / 5 CPU (chart: prod 3Gi / 2 CPU) | kota aşımı ya da gereksiz rezervasyon |
| `deployment.yaml` | yalnız `/tmp` yazılabilir; `/var/log` emptyDir yok | prod log dizini (`/var/log/site-monitor`) salt-okunur kök FS'te yazılamaz |
| `deployment.yaml` | Downward API env'leri (`APP_ENVIRONMENT`, `HELM_*`, `APP_IMAGE_REF`, `NODE_NAME`, `POD_*`) yok | Sürüm & Dağıtım Geçmişi ortam/revizyon/imaj bilgisini "bilinmiyor" gösterir |
| `postgres.yaml` | `max_connections=150` yorumu "3 replika" varsayar | prod tek pod; boyutlandırma chart'taki `dbPoolMax` ile yapılır |
| `ingress.yaml` / `openshift-route.yaml` | örnek host'lar; Route'ta zaman aşımı anotasyonu yok (HAProxy varsayılanı 30 sn) | uzun manuel kontroller 504 alır |
| `networkpolicy.yaml` | uygulama ingress politikası yorum satırında; chart'ta NetworkPolicy şablonu yok | `/metrics` kümede kısıtlanmaz (bkz. `docs/RUNBOOK.md` → /metrics) |

`secret.yaml` (izlenmeyen, `.gitignore`'da) yerel kopyadır; depoya girmez.

## Ne zaman kullanılır?

- Kullanılmaz. Yeni bir ortam gerekiyorsa `helm/site-monitor/environments/develop.yaml` kopyalanıp uyarlanır.
- Chart'ta olmayan bir parça (ör. NetworkPolicy) gerekiyorsa buradaki örnek **chart'a şablon olarak taşınır**;
  bu dizin güncellenmez.
- Dizin ileride `docs/legacy-k8s/`'e taşınabilir ya da silinebilir (ayrı karar).

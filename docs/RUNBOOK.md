# Site Monitor — Operasyon Runbook'u

> Hedef kitle: prod'u işleten ekip (nöbetçi / platform). Planlı dağıtım adımları
> [`PROD_DEPLOY_CHECKLIST.md`](PROD_DEPLOY_CHECKLIST.md)'dedir; bu dosya **olay anında** ne yapılacağını anlatır.
> Prod **tek pod** çalışır (dikey ölçekleme): pod'un gitmesi = tam kesinti.
>
> Değişkenler checklist'teki gibi: `REL`, `NS`, `DEPLOY`, `SECRET` (`helm list -A`'dan okunur).

| Belirti | Bölüm |
|---|---|
| Yeni sürüm açılmıyor / geri dönmek gerekiyor | [Dağıtım](#dağıtım-rollout) · [Geri alma](#geri-alma-rollback) |
| Aynı anda çok sayıda alarm | [Alarm fırtınası](#alarm-fırtınası) |
| DB diski doluyor, sorgular yavaş | [DB dolması](#db-dolması) |
| E-postalar gitmiyor | [SMTP düştü](#smtp-düştü) |
| Tarayıcı uygulamanın sertifikasına uyarı veriyor | [Uygulamanın kendi sertifikası](#uygulamanın-kendi-sertifikası) |
| Yönetici giriş yapamıyor ("hesap kilitli") | [Kilitli admin hesabı](#kilitli-admin-hesabı) |
| `decrypt failed`, LDAP/SMTP birden bozuldu | [Secret key](#secret-key) |
| "Denetim izinde BOŞLUK" / `AUDIT fallback` logu | [Denetim fallback](#denetim-fallback) |
| Pod `OOMKilled` / sık yeniden başlıyor | [Bellek](#bellek) |
| RDAP/HTTPS `PKIX path building failed` | [Kurumsal vekil CA rotasyonu](#kurumsal-vekil-ca-rotasyonu) |
| `/metrics` dışarıdan açık mı? | [/metrics erişimi](#metrics-erişimi) |

---

## Dağıtım (rollout)

Komut ve ön kontroller: [`PROD_DEPLOY_CHECKLIST.md`](PROD_DEPLOY_CHECKLIST.md) — başka komut kullanmayın.

Rollout'ta olanlar:
- `maxSurge: 1`, `maxUnavailable: 0` → yeni pod hazır olmadan eskisi kapanmaz; kısa süre **iki pod** birlikte
  koşar (DB bağlantıları ve kota iki katı). Zamanlayıcıyı `scheduler_lock` (DB) korur, oturumlar JDBC'de
  olduğu için kullanıcılar oturum kaybetmez.
- Açılışta şema yamaları uygulanır (`SchedulerService.applySchemaPatches`, idempotent `ADD COLUMN` /
  `CREATE TABLE`). Logda `Schema patch applied …` satırları.
- `startupProbe` hazır olmayı en fazla **~300 sn** bekler (30 × 10 sn); aşılırsa pod öldürülür.
  `--atomic` ile helm 10 dk içinde başarısız sayıp önceki revizyona döner.

Takıldığında:
```bash
kubectl get pod -n "$NS" -l app.kubernetes.io/instance="$REL" -o wide
kubectl describe pod -n "$NS" <yeni-pod>          # Events: ImagePullBackOff? Pending (kota)? probe hatası?
kubectl logs -n "$NS" <yeni-pod> --tail=300
```
| Belirti | Olası neden |
|---|---|
| `ImagePullBackOff` | etiket `v` öneksiz verildi (registry'de yalnız `:vX.Y.Z` var) ya da özel registry için `imagePullSecrets` yok |
| `Pending` | namespace kotası / node belleği 3Gi limit + 1.5Gi request'i kaldırmıyor |
| `CrashLoopBackOff`, log'da `site.monitor.secret-key … ZORUNLU` | Secret'ta `SITE_MONITOR_SECRET_KEY` boş — **yeni anahtar üretmeyin**, eski değeri geri koyun ([Secret key](#secret-key)) |
| Readiness hiç yeşil olmuyor, log'da DB hatası | `dbHost/dbName/dbUser` (özel values dosyası) ya da `DB_PASSWORD` yanlış |

## Geri alma (rollback)

```bash
helm history "$REL" -n "$NS"
helm rollback "$REL" <önceki-REVISION> -n "$NS" --wait --timeout 10m
kubectl rollout status "$DEPLOY" -n "$NS"
```

- İmaj **ve** values birlikte döner. `secret.existingSecret` kullanılıyorsa Secret'a dokunulmaz; chart Secret'ı
  render ediyorsa önceki revizyonun Secret içeriği geri yazılır.
- **Önceki imajın digest'i** (tag yeniden basılmış olabilir, `release.yml` var olan tag'i silip yeniden oluşturur):
  ```bash
  # çalışan / çalışmış pod'un gerçek imajı
  kubectl get pod -n "$NS" -l app.kubernetes.io/instance="$REL" -o jsonpath='{..imageID}{"\n"}'
  # geçmiş: Sistem Sağlığı → Sürüm & Dağıtım (deployment_history.image_ref) ya da helm get values --revision N
  ```
  Digest'le sabitlemek için önceki sürümün tag'ini checkout edip dağıtım komutunu
  `--set image.tag="v<önceki>@sha256:<digest>"` ile koşun (her chart sürümünde çalışır; v20.86.0 sonrası
  chart'larda `--set image.digest=sha256:<digest>` de olur).
- **Şema ileri-uyumlu:** yamalar yalnız kolon/tablo ekler, silmez ve yeniden adlandırmaz; `ddl-auto=update`
  eski sürümde de eksik olanı ekler, fazlasını görmezden gelir → **eski sürüm yeni şemada açılır**. Yeni
  kolonlara yazılmış veri eski sürümde görünmez ama kaybolmaz, yeniden ileri gidildiğinde geri gelir.
  İstisnalar (sürüm notunda özellikle yazılır): varsayılansız `NOT NULL` kolon (eski sürümün INSERT'ü kırılır)
  ve veri göçleri (ör. rename sürümündeki `app_settings` anahtar göçü, [`DEPLOY_GECIS_PLANI.md`](DEPLOY_GECIS_PLANI.md) §6).
- Geri alma **secret key değişikliğini** geri almaz; anahtar değiştiyse önceki değeri Secret'a geri koyun.
- Son çare DB yedeğinden dönüş (yedekten sonraki kayıtlar kaybolur) — platform/DBA ile.

## Alarm fırtınası

Çok sayıda izleme kısa sürede birlikte düştüğünde.

1. **Önce kaynağı ayırın — hedefler mi düştü, izleyen mi?** Tüm türler (HTTP, Port, DNS, Ping, RDAP) aynı
   anda kırmızıysa sorun büyük olasılıkla pod'un kendi çıkışıdır (vekil, DNS, firewall, node). Sistem Sağlığı
   ağ durumu kartı / `GET /api/system/network-status`, Tanılama ekranı ve pod'dan:
   ```bash
   kubectl exec -n "$NS" "$DEPLOY" -- sh -c 'nslookup <iç-bir-host>; curl -sS -o /dev/null -w "%{http_code}\n" -x "$HTTP_PROXY_HOST:$HTTP_PROXY_PORT" https://data.iana.org/'
   ```
2. **Yerleşik korumalar:** izlemelerin ≥%50'si aynı anda düşerse toplu bastırma (altyapı kesintisi) devreye
   girer; daha küçük sellerde **fırtına motoru** bireysel alarmları takım başına tek toplu bildirime indirir
   (varsayılan: 5 dk'da 5 kesinti). Olaylar (incident) yine monitör başına kaydedilir; uptime% bozulmaz.
   Eşikler canlı ayarlanır: Ayarlar → Alarm / Storm (`site.monitor.storm.*`).
3. **Gürültüyü kes:**
   - Etkilenen hedefler için **Bakım penceresi** (İzleme → Bakım) — alarmları bastırır, kayıt tutmaya devam eder.
   - E-postayı geçici kapatmak: Ayarlar → SMTP → "etkin" kapalı (DB satırı, canlı). Push/webhook ayrı kanaldır.
   - Pod'u yeniden başlatmak fırtınayı **durdurmaz** (alarmlar DB'de); yalnız son çare.
4. Sonra: Alarm Geçmişi → gürültü analizi (en gürültülü hedefler, flapping adayları) ile eşikleri gözden geçirin.

## DB dolması

1. Ölç: Sistem Sağlığı → Veritabanı (tablo boyutları, büyüme; Prometheus `db_table_bytes`, `db_table_rows`) ya da:
   ```sql
   SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) AS toplam
   FROM pg_catalog.pg_statio_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 15;
   ```
2. Saklama motoru her gece 03:00'te (Europe/Istanbul, tek pod, `nightly-cleanup` kilidi) çalışır — son koşum
   başarılı mı: Ayarlar → Veri Saklama koşum geçmişi (`GET /api/admin/retention/runs`). Başarısızsa önce
   `POST /api/admin/retention/dry-run`, sonra `…/run` ile elle koşun. Politika: [`RETENTION_POLITIKASI.md`](RETENTION_POLITIKASI.md).
3. Silinen satırların yeri Postgres'te hemen dosya sisteminden düşmez: autovacuum durumu, gerekirse
   bakım penceresinde tablo bazında `VACUUM (ANALYZE)`. `VACUUM FULL` tabloyu kilitler — yalnız planlı.
4. **Saklama sürelerini kısaltmak ilk çare DEĞİLDİR:** 2026-08-09 ürün kararı — gösterilen her pencere satır
   düzeyinde tam veri olmalı, özet ham verinin yerine geçmez. Önce disk/tier büyütülür; süre değişikliği
   Ayarlar → Veri Saklama'da onay akışıyla ve ürün sahibinin kararıyla yapılır.
5. Kapasite planı: [`db-scaling.md`](db-scaling.md).

## SMTP düştü

1. Kapsam: Sistem Sağlığı → SMTP / SMTP Gönderim Logu (hata sınıfı: kimlik, TLS, zaman aşımı, reddedildi).
   Bildirim Geçmişi'nde `FAILED` artışı. Push/webhook çalışmaya devam eder — kritik alarmlar oradan gider.
2. Yapılandırma nereden geliyor? Ayarlar → SMTP'de **kayıtlı satır varsa** o kullanılır (parolası
   `SITE_MONITOR_SECRET_KEY` ile şifreli); **yoksa** env: ConfigMap `SPRING_MAIL_HOST/PORT/…` + Secret
   `SPRING_MAIL_USERNAME/PASSWORD`. Bir `helm upgrade` sonrası başladıysa: Secret yer tutucuyla ezilmiş
   (checklist → Secret) ya da `decrypt failed` var ([Secret key](#secret-key)).
3. Ağ: `kubectl exec -n "$NS" "$DEPLOY" -- sh -c 'nc -zv -w 5 <smtp-host> <port>'`; kurumsal relay'e egress açık mı?
4. Düzeltme sonrası Ayarlar → SMTP → **Test e-postası**; SMTP Gönderim Logu'ndan başarısız gönderimleri
   yeniden gönderin.
5. Parola değişimi (ör. uygulama parolası iptal edildi): Ayarlar → SMTP'de yeni parolayı kaydedin (DB satırı) ya da
   Secret'ı güncelleyip `kubectl rollout restart "$DEPLOY" -n "$NS"` (existingSecret kullanılırken Secret
   değişikliği pod'u kendiliğinden yeniden başlatmaz).

## Uygulamanın kendi sertifikası

Kullanıcılar uygulamaya TLS ile gelir; sertifika **iki yerden birinde** sonlanır — hangisi olduğunu bilin:
- **Kurumsal yük dengeleyici (NetScaler)** TLS'i sonlandırıyorsa sertifika oradadır → yenileme ağ ekibinin işi.
- **Ingress** sonlandırıyorsa sertifika `ingress.tls.secretName` Secret'ındadır (prod: `site-monitor-tls`):
  ```bash
  kubectl get secret site-monitor-tls -n "$NS" -o jsonpath='{.data.tls\.crt}' | base64 -d | openssl x509 -noout -enddate -subject
  # yenileme (cert-manager yoksa): yeni zincir + anahtarla Secret'ı yerinde değiştir — pod yeniden başlamaz
  kubectl create secret tls site-monitor-tls -n "$NS" --cert=fullchain.pem --key=privkey.pem \
    --dry-run=client -o yaml | kubectl apply -f -
  ```
- Önleme: uygulamanın kendi host'unu **kendi envanterine** ekleyin (sertifika bitişi alarmı gelir) ve
  dışarıdan ikinci bir kontrol tutun — uygulama düşükken kendi sertifikasını izleyemez.

## Kilitli admin hesabı

Giriş kilidi kademelidir: ihlal başına **30 sn → 2 dk → 10 dk → 30 dk**, sonraki her ihlal yine 30 dk
(`site.monitor.lockout.durations-seconds`). Başarılı girişte sayaç sıfırlanır. Ayrıca IP bazlı kısa blok vardır
(`LOGIN_MAX_ATTEMPTS`=10 başarısız deneme → `LOGIN_BLOCK_SECONDS`=30 sn'den başlayan blok).

- **Bekleyin:** kilit en fazla 30 dk sürer ve kendiliğinden açılır. *Yeni sürümle (CHANGELOG → Unreleased)
  kalıcı otomatik kilit kaldırıldı.* **v20.86.0 ve öncesinde** 5. ihlal hesabı **kalıcı** kilitler
  (`permanent_lock`) ve yalnız bir yönetici açabilir.
- **Başka bir yönetici varsa:** Yönetim → Kullanıcılar → kullanıcı → Kilidi aç (`POST /api/admin/users/{id}/unlock`).
- **Hiç giriş yapabilen yönetici yoksa** (son çare, DB'de; denetim izine düşmez — olay kaydına not edin):
  ```sql
  UPDATE app_users
     SET permanent_lock = false, lockout_until = NULL, failed_block_count = 0, last_lockout_at = NULL
   WHERE username = '<kullanıcı-adı>';
  ```
- Parolayı unutmak ayrı konudur: Secret'taki `ADMIN_PASSWORD` **yalnız ilk açılışta** (boş DB) kullanılır;
  sonradan değiştirmek hiçbir şeyi değiştirmez. Başka bir yönetici "parolayı sıfırla" ile geçici parola üretir
  ve kullanıcının e-postasına gönderir (`POST /api/admin/users/{id}/auto-reset-password`; SMTP çalışıyor olmalı).

## Secret key

`SITE_MONITOR_SECRET_KEY` (eski adı `CERT_MONITOR_SECRET_KEY`) UI'dan kaydedilen sırları AES-GCM ile şifreler:
LDAP bind parolası, SMTP parolası, HTTP izleme Basic Auth parolası ve özel başlıkları, Keyword izleme özel
başlıkları, senaryo (k6) env sırları, kişi-push webhook başlık sırları, PageSpeed API anahtarı.

**Rotasyon DESTEKLENMİYOR.** Anahtar halkası (eski + yeni) ve yeniden şifreleme aracı yok. Anahtar değişirse
uygulama açılır ama bu değerlerin hepsi `null` çözülür; her kullanımda tek satır
`SecretCipher: decrypt failed (wrong key or corrupt value?)` WARN'ı basılır; UI yine "secret key ayarlı" der.
Sonuç: LDAP girişleri, alarm e-postaları, başlık sırrı taşıyan izlemeler (yanlış DOWN), push ve senaryo
kontrolleri bozulur. **Anahtarı dağıtımlarda aynen taşıyın, rotasyon yapmayın.**

Tespit:
```bash
kubectl logs -n "$NS" "$DEPLOY" | grep -c "decrypt failed"   # > 0 = anahtar, şifreli değerlerle uyuşmuyor
```

Anahtar yanlışlıkla değiştiyse:
1. Önceki değeri geri koyun (Secret'ta ya da `prod-values-all-before.yaml` yedeğinde; değer ekrana basılmaz):
   `kubectl edit secret "$SECRET" -n "$NS"` → `kubectl rollout restart "$DEPLOY" -n "$NS"`.
2. Hangi anahtarın doğru olduğundan emin değilseniz: Ayarlar → Secret araçları (bootstrap admin) aday anahtarla
   SMTP ve LDAP parolalarını **deneme-çözer** (`POST /api/admin/secret-tools/decrypt`) — ikisi de `ok` ise
   doğru anahtar odur. Anahtar ve çözülen değer loglanmaz; denetime yalnız sayı yazılır.

Anahtar **kayıpsa ya da sızdıysa** (planlı bakım işi, yayınla birlikte yapılmaz):
1. Yeni rastgele anahtar (≥32 karakter) Secret'a yazılır, pod yeniden başlatılır.
2. Yukarıdaki listedeki **her** sır UI'dan yeniden girilir: Ayarlar → LDAP (bind parolası), Ayarlar → SMTP,
   her HTTP/Keyword izlemesinin Basic Auth/özel başlıkları, her senaryonun env sırları, PageSpeed anahtarı,
   kullanıcıların kişi-push başlık sırları (kullanıcılara duyuru gerekir).
3. `decrypt failed` sayısı 0'a inene kadar izlenir.

## Denetim fallback

Denetim kaydı (`audit_log` tablosu) DB'ye yazılamazsa kayıt bir JSONL dosyasına düşer ve açılışta
`⚠ Denetim izinde BOŞLUK: N kayıt …` uyarısı basılır. Otomatik geri yükleme **yok** — dosya elle incelenir.

- **v20.86.0 ve öncesi:** varsayılan yol göreli `logs/audit-fallback.jsonl` → salt-okunur kök dosya sisteminde
  `/app/logs` yaratılamaz; `AUDIT fallback dosya yazımı DA başarısız — kayıt kaybı` logu = kayıt kayboldu.
- **Yeni sürüm (Unreleased):** varsayılan `${logging.file.path}/audit-fallback.jsonl` (prod:
  `/var/log/site-monitor/`), yazılamazsa `/tmp/site-monitor/`. İkisi de **emptyDir** → pod değişince silinir.
  Env ile değiştirmek: `AUDIT_FALLBACK_FILE`.

Loglarda `AUDIT DB yazımı BAŞARISIZ` görüldüğünde:
```bash
kubectl exec -n "$NS" "$DEPLOY" -- sh -c 'wc -l /var/log/site-monitor/audit-fallback.jsonl /tmp/site-monitor/audit-fallback.jsonl 2>/dev/null'
kubectl cp "$NS/<pod>:/var/log/site-monitor/audit-fallback.jsonl" ./audit-fallback-$(date +%F).jsonl
```
Dosyayı **pod yeniden başlatılmadan / dağıtım yapılmadan önce** dışarı alın, olay kaydına ekleyin; DB sorunu
(disk, bağlantı, kilit) giderilince kayıtlar denetim zincirinde **yoktur** — bu bir denetim boşluğu olarak raporlanır.

## Bellek

Tek pod'da `OOMKilled` = tam kesinti. Prod: limit **3Gi**, request **1.5Gi**, `-XX:MaxRAMPercentage=75`
(heap tavanı ≈2.25Gi) + `-XX:+ExitOnOutOfMemoryError`. Heap dışı pay (~768Mi): metaspace, code cache, thread
yığınları, NIO tamponları ve senaryo izlemesinin k6 alt süreçleri (havuz 2 × en fazla ~256Mi, yumuşak tavan).

```bash
kubectl get pod -n "$NS" -l app.kubernetes.io/instance="$REL" -o jsonpath='{..restartCount} {..lastState.terminated.reason}{"\n"}'
kubectl top pod -n "$NS"
kubectl logs -n "$NS" <pod> --previous | tail -50    # "Terminating due to java.lang.OutOfMemoryError" = heap
```
- `OOMKilled` (çekirdek öldürdü, JVM mesajı yok) → heap değil **RSS** taştı: `config.javaOpts`'ta
  `MaxRAMPercentage=70`'e inin ya da senaryo havuzunu 1'e çekin (Ayarlar → Senaryo İzleme,
  `site.monitor.scripted.pool-size`); kota izin veriyorsa limit ↑ (chart önerisi 3584Mi).
- `OutOfMemoryError` (JVM çıktı) → heap doldu: limit ↑ ya da yük (eşzamanlı kullanıcı, büyük dışa aktarım) incelenir.
- Hedef: çalışma kümesi limitin %85'inin altında.

## Kurumsal vekil CA rotasyonu

SSL-inspection yapan vekilin CA'sı değişince RDAP/dış HTTPS çağrıları `PKIX path building failed` ile düşer.
Yeni zinciri yakalayın (Yönetim → Tanılama → vekil CA zinciri, `/api/admin/diagnostics/proxy-ca-chain`, ya da
`openssl s_client -connect data.iana.org:443 -proxy <vekil:port> -showcerts`) ve PEM'i Ayarlar → Genel →
`site.monitor.trust.ca-bundle-pem` alanına yapıştırın (canlı yüklenir). Helm'deki `config.caBundlePem` yalnız
DB'de değer yoksa kullanılır.

## /metrics erişimi

`/metrics` (Prometheus) 8080'de **kimliksiz** yayınlanır ve Ingress `/` önekiyle dışarıya da açıktır (prod kapısı
P3-6, açık). İçerik: uç envanteri (`http_server_requests{uri}`), tablo boyutları, sürüm/commit — sır/PII yok,
keşif bilgisi. Prometheus pod IP'sinden doğrudan kazır (pod anotasyonları), dolayısıyla kenarda engellemek
kazımayı bozmaz.

Seçenekler (platform ekibi kararı):
1. **Kenarda engelle** — NetScaler'da `/metrics` yoluna dışarıdan gelen isteği reddeden kural (en ucuz, uygulama değişmez).
2. Ingress-nginx snippet (`location = /metrics { deny all; }`) — birçok kümede snippet anotasyonları kapalıdır.
3. Kalıcı: ayrı yönetim portu (`management.server.port`) + Service'te yayınlamama — kod ve chart değişikliği.

Kontrol: kurum ağından `curl -s -o /dev/null -w "%{http_code}\n" https://<host>/metrics` → engellendiyse 403/404.

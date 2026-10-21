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
| MP… (ikinci) hesaplarla girişi kapatmak / ön ek kuralı yüzünden kimse giremiyor | [MP hesaplarıyla giriş kapatma](#mp-hesaplarıyla-giriş-kapatma) |
| `decrypt failed`, LDAP/SMTP birden bozuldu | [Secret key](#secret-key) |
| "Denetim izinde BOŞLUK" / `AUDIT fallback` logu | [Denetim fallback](#denetim-fallback) |
| Pod `OOMKilled` / sık yeniden başlıyor | [Bellek](#bellek) |
| RDAP/HTTPS `PKIX path building failed` | [Kurumsal vekil CA rotasyonu](#kurumsal-vekil-ca-rotasyonu) |
| `/metrics` dışarıdan açık mı? | [/metrics erişimi](#metrics-erişimi) |
| Grafana panosu boş / "veri yok", Prometheus kazıma ayarı | [Grafana panosu ve Prometheus](#grafana-panosu-ve-prometheus) |
| Kullanıcının ilettiği istek kimliğini günlükte bulmak, JSON günlük, iz (trace) | [Günlükler ve izleme](#günlükler-ve-izleme) |
| "Uygulama sorunu: …" e-postası / dış izleyici `/api/public/health/watchdog` 503 | [Kendi kendini izleme](#kendi-kendini-izleme) |

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

## MP hesaplarıyla giriş kapatma

İki hesabı olan kullanıcıların (N… sicil hesabı, MP… ikinci hesap) yalnız izin verilen hesapla girmesi için kullanıcı adı
ön eki kuralı (2026-10-21).

- **Açma:** Ayarlar → Güvenlik → Giriş Yöntemleri → "Kullanıcı adı ön eki" kartı (yalnız global yönetici). İzinli ön ek
  olarak `N` yazın (Enter); **Etki önizlemesi** kaç aktif kullanıcının giremeyeceğini ve ilk 50 adı gösterir. Anahtarı açıp
  Kaydet → onay. Ayar anahtarları: `site.monitor.login.username-prefix.enabled|allowed|exempt` (GLOBAL_ONLY).
- **Ne olur:** izinli ön ekle başlamayan adlar şifre, LDAP, kodla giriş ve beni hatırla yollarının hiçbirinden giremez —
  giriş 403 `USERNAME_PREFIX_NOT_ALLOWED` (parola denenmeden), kodla girişte aynı genel yanıt ve kod gitmez. Açık oturumlar
  sonraki istekte (~15 sn; çok pod'da ayar ≤ 10 sn'de yayılır) kapanır, hatırlanan girişler silinir. Denetim:
  `SESSION_ENDED_USERNAME_PREFIX` ve `LOGIN_FAILED` (BLOCKED, neden `USERNAME_PREFIX` — kilit sayacına girmez).
- **Muaf liste:** ön eke bakılmadan girebilecek tam adlar (servis / acil durum hesabı). Kurulumdaki bootstrap yönetici
  (`site.monitor.username`, prod'da `ADMIN_USERNAME`) HER ZAMAN muaftır; global yöneticiler otomatik muaf DEĞİLDİR.
- **Kendini kilitleme yok:** kaydeden yöneticinin kendi hesabı kurala uymuyorsa kayıt 400 `LOCKOUT_SELF` ile reddedilir.
- **Kilitlendiyseniz (kurtarma):** bootstrap yönetici her zaman girer → kuralı kapatın ya da listeyi düzeltin. Bootstrap
  yöneticiye de erişim yoksa (son çare, DB'de; denetime düşmez — olay kaydına not edin):
  ```sql
  UPDATE app_settings SET setting_value = 'false'
   WHERE setting_key = 'site.monitor.login.username-prefix.enabled';
  ```
  Değer pod belleğinde ≤ 10 sn'de tazelenir; yeniden başlatma gerekmez.

## Secret key

`SITE_MONITOR_SECRET_KEY` (eski adı `CERT_MONITOR_SECRET_KEY`) UI'dan kaydedilen sırları AES-GCM ile şifreler:
LDAP bind parolası, SMTP parolası, HTTP izleme Basic Auth parolası ve özel başlıkları, Keyword izleme özel
başlıkları, senaryo (k6) env sırları, kişi-push webhook başlık sırları, PageSpeed API anahtarı.

**Anahtarı DEĞİŞTİRMEYİN, DÖNDÜRÜN** (aşağıdaki [Anahtar döndürme](#anahtar-döndürme)). Anahtar önceki anahtar
verilmeden değişirse uygulama açılır ama bu değerlerin hepsi `null` çözülür; her kullanımda tek satır
`SecretCipher: decrypt failed (wrong key or corrupt value?)` WARN'ı basılır; UI yine "secret key ayarlı" der.
Sonuç: LDAP girişleri, alarm e-postaları, başlık sırrı taşıyan izlemeler (yanlış DOWN), push ve senaryo
kontrolleri bozulur. **Dağıtımlarda anahtarı aynen taşıyın.**

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

### Anahtar döndürme

2026-10-11'den beri desteklenir. `SITE_MONITOR_SECRET_KEY_PREVIOUS` (Helm `secret.secretKeyPrevious`, `existingSecret`
kullanılıyorsa Secret'a aynı adla anahtar) **yalnız çözme** anahtarıdır: şifreleme her zaman güncel anahtarla, çözme önce
güncel sonra önceki anahtarla yapılır. Saklama biçimi değişmez (`enc:v1:`), yani eski sürüme geri dönüş değerleri okur.
Ekran: Ayarlar → Anahtar Çözümleme → **Anahtar döndürme** (aynı yetki: `settings.secrets/execute`, kapsamlı müdür 403).

1. Yeni anahtar üretin (ekrandaki "Güçlü anahtar üret" ya da `openssl rand -base64 32`), kasada saklayın.
2. Secret'ta `SITE_MONITOR_SECRET_KEY` = **yeni**, `SITE_MONITOR_SECRET_KEY_PREVIOUS` = **eski** anahtar; dağıtın
   (`existingSecret` ise `kubectl rollout restart`). Açılış günlüğü: `site.monitor.secret-key-previous = set (rotation in
   progress, decrypt-only)`. Bu aşamada her şey çalışır; yeni kaydedilen değerler yeni anahtarla yazılır.
3. **Tüm pod'lar** yenilendikten sonra ekranda "Yeniden şifrele"ye basın (`POST /api/admin/secret-tools/rotation/reencrypt`,
   `scheduler_lock` `secret-reencrypt`, denetim `SECRET_REENCRYPT` yalnız sayılarla). Önceki anahtardaki her değer koşullu
   UPDATE ile güncel anahtara taşınır; arada değişen satır ezilmez ("arada değişti" sayılır, yeniden basın). Eski pod
   yeni şifrelenen değeri okuyamaz, bu yüzden dağıtım bitmeden basmayın.
4. Ekran "Tüm değerler güncel anahtarda" dediğinde (`previous_removable: true`) `SITE_MONITOR_SECRET_KEY_PREVIOUS`'ı
   kaldırıp yeniden dağıtın.

Konumlar (tek kaynak `SecretRotationService.LOCATIONS`, kapı `SecretLocationsGateTest`): LDAP bind parolası, SMTP parolası,
HTTP / Sayfa Hızı Basic Auth parolası ve özel başlıkları, Anahtar Kelime özel başlıkları, senaryo env sırları + senaryo sürüm
geçmişi, kişisel push başlık sırları. "Okunamayan" = hiçbir ayarlı anahtarla çözülmeyen değer: eski anahtar elinizdeyse önceki
anahtar olarak verin, yoksa ekrandaki kayıt listesinde değeri yeniden girin. Giriş kodları (OTP) yeniden şifrelenmez: süreleri
≤ 5 dk ve doğrulama önceki anahtarın alt anahtarıyla alınan özeti de kabul eder.

Anahtar **kayıpsa ya da sızdıysa** (planlı bakım işi, yayınla birlikte yapılmaz). Sızdıysa ve eski anahtar elinizdeyse
yukarıdaki döndürmeyi uygulayın (eski anahtar yalnız çözme için kısa süre kullanılır). Kayıpsa:
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

İzleme ölçümleri (`sitemonitor_*`: takım, izleme ve alan adları, hedefler, alarmlar) `/metrics`'te **YOKTUR** — ayrı
`/metrics/sitemonitor` ucunda, yalnız taşıyıcı belirteçle (`METRICS_EXPORT_TOKEN`) yayınlanır (bkz. "Grafana panosu ve
Prometheus"). Yine de `/metrics*` yollarını kenarda engelleyin: belirteçli uç kaba kuvvete karşı hız sınırlıdır ama kurum
ağına açık olması gerekmez; Prometheus pod IP'sinden küme içinden kazımaya devam eder.

## Grafana panosu ve Prometheus

Ayarlar → Platform → **Grafana panosu**: ölçüm aktarıcısının canlı durumu (bu pod), pano ayarları ve **JSON indir**
(global yönetici). Ayrıntı: kılavuz §15.10.

**İki uç (2026-10-18):**

| Uç | İçerik | Kimlik |
|---|---|---|
| `/metrics` | JVM, HTTP, Hikari, sürüm (`sitemonitor_build_info`), şema yaması, saklama / DB büyüme, uç etiketsiz HTTP gecikme histogramı | yok (küme içi) |
| `/metrics/sitemonitor` | İzleme envanteri: izleme durumu, alarmlar, sertifikalar / alan adları, bildirimler / fırtınalar / push, aktarıcının kendi sağlığı (`sitemonitor_*` iş ölçümleri) | `Authorization: Bearer <METRICS_EXPORT_TOKEN>` |

**Belirteç:** ortam değişkeni `METRICS_EXPORT_TOKEN` (Helm `secret.metricsExportToken` ya da `existingSecret`'e
`METRICS_EXPORT_TOKEN` anahtarı). En az 32 rastgele karakter üretin (ör. `openssl rand -hex 32`), git'e yazmayın. Yoksa ya
da 32 karakterden kısaysa `/metrics/sitemonitor` **404** döner ve aktarıcı hiç koşmaz (veritabanı yükü yok); pano yalnız
sürüm / JVM / HTTP panellerini doldurur ve "Veri tazeliği" şeridi "veri yok" der. **Belirteci bilen herkes bütün envanteri
(izleme adları, hedefler, takımlar, sertifikalar, açık alarmlar) okur** — parola gibi saklayın. Grafana takım kapsamı
uygulamaz: panoya / veri kaynağına erişebilen herkes süzgeci değiştirip bütün takımları görür; takıma özel indirilen pano
(varsayılan takım + UID) bir kolaylıktır, erişim denetimi değildir.

Yanlış / eksik belirteç **401** (gövdesiz) döner; aynı IP'den dakikada 10 başarısız denemeden sonra **429** — DOĞRU
belirteçle gelen kazıma o IP engelliyken de geçer (ortak adres / SNAT / mesh arkasında başkasının kötü istekleri
Prometheus'u kilitlemez). Denetim
günlüğünde güvenlik olayı `METRICS_EXPORT_ACCESS_DENIED` IP başına 10 dakikada bir yazılır — Prometheus'un eski / yanlış
belirteçle kazıdığını buradan görürsünüz.

**Döndürme:** yeni değeri Secret'a ve Prometheus'un belirteç dosyasına yazın, dağıtın; geçişte eski belirteçle gelen
kazımalar 401 alır (pano birkaç dakika "veri yok"). Uygulama tek belirteç tutar (önceki belirteç kabul edilmez) ve onu
AÇILIŞTA okur: Secret'ı yerinde düzenlediyseniz (`existingSecret`, `kubectl edit secret`) pod'lar kendiliğinden
yenilenmez — `kubectl rollout restart deploy/<ad> -n <ad-alanı>` çalıştırın. O zamana kadar ESKİ (sızmış olabilecek)
belirteç çalışmaya devam eder. Değeri değiştiren bir `helm upgrade` pod'ları zaten yeniden başlatır (Secret özeti).
Belirteç diğer sırlar gibi pod ortamındadır: k6 senaryoları pod dosyalarını okuyabilir (Ayarlar → k6 dosya okuma politikası,
`site.monitor.scripted.file-read-policy`) — sentetik izleme yazabilen biri ortamı görebilir.

**Prometheus kazıma örneği** (pod anotasyonlarıyla; Helm `podAnnotations` zaten ekler — ServiceMonitor kullanan kümede
ikinci uç için `endpoints: [{port: http, path: /metrics/sitemonitor, authorization: {type: Bearer, credentials: {name: …,
key: …}}}]`):

```yaml
scrape_configs:
  - job_name: site-monitor            # JVM / HTTP / uygulama sağlığı — belirteç gerekmez
    metrics_path: /metrics
    scrape_interval: 30s
    kubernetes_sd_configs:
      - role: pod
        namespaces:
          names: [<site-monitor-namespace>]
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: "true"
      - source_labels: [__meta_kubernetes_namespace]
        target_label: namespace
      - source_labels: [__meta_kubernetes_pod_name]
        target_label: pod
  - job_name: site-monitor-inventory  # izleme envanteri — taşıyıcı belirteç ŞART
    metrics_path: /metrics/sitemonitor
    scrape_interval: 60s
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/secrets/site-monitor/metrics-export-token
    kubernetes_sd_configs:
      - role: pod
        namespaces:
          names: [<site-monitor-namespace>]
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: "true"
      - source_labels: [__meta_kubernetes_namespace]
        target_label: namespace
      - source_labels: [__meta_kubernetes_pod_name]
        target_label: pod
```

Hızlı kontrol (pod içinden): `curl -s -o /dev/null -w '%{http_code}' localhost:8080/metrics/sitemonitor` → 401 (belirteç
tanımlı) ya da 404 (tanımlı değil); `curl -s -H "Authorization: Bearer $METRICS_EXPORT_TOKEN" localhost:8080/metrics/sitemonitor | grep -c '^sitemonitor_'`
sıfırdan büyük olmalı. `/metrics` çıktısında `sitemonitor_monitor` ya da takım adı GÖRÜNMEMELİ.

**Yükseltme / geri alma:** `/metrics` kazıyan mevcut Prometheus işi JVM / HTTP için aynen çalışır; iş verisi için yeni iş +
belirteç gerekir. Bu değişiklikten önceki bir sürüme geri dönmek iş serilerini yeniden kimliksiz `/metrics`'e koyar.

**Prometheus sürümü:** "Sorun yaşayan izlemeler" zaman çizelgesi `@ end()` değiştiricisini kullanır (Prometheus ≥ 2.33,
varsayılan açık).

Aynı Prometheus birden çok ortamı (test + prod) kazıyorsa sayfadaki **Prometheus etiket eşleyicisi** alanına
`namespace="<prod ad alanı>"` yazın; pano her sorguya ekler.

Pano "veri yok" gösteriyorsa sırayla:
1. Kazıma: Prometheus → Status → Targets'ta İKİ işin pod'ları da `UP` mı? `site-monitor-inventory` işi 401 diyorsa
   belirteç dosyası yanlış / eski; 404 diyorsa uygulamada `METRICS_EXPORT_TOKEN` yok (ya da 32 karakterden kısa).
2. Aktarıcı: Ayarlar sayfasındaki canlı kontrol "Çalışıyor" mu? "Belirteç yok" → adım 1; kapalıysa
   (`site.monitor.metrics.export.enabled`, env `METRICS_EXPORT_ENABLED`) açın; "Takılmış olabilir" ise pod günlüğünde
   `İzleme ölçümleri tazelenemedi` arayın — son tur 3 tazeleme aralığından (en az 3 dk) eskiyken uç iş değerlerini
   yayınlamaz (panoda "Veri tazeliği" şeridi kırmızı, iş panelleri "veri yok").
3. Okunamayan kaynak: `sitemonitor_exporter_source_up == 0` olan kaynakların serileri bilerek DÜŞER (sahte 0 yazılmaz) —
   genellikle veritabanı erişimi; Sistem Sağlığı → Veritabanı. İzleme sayıları (Etkin / Sağlıklı / Düşük …) süzgece uyan bir
   izleme türü HİÇBİR pod'da okunamazken de "veri yok" gösterir (`source=~"monitors:.*"` satırları; eksik toplam bütün filo
   gibi görünmesin).
4. Seri tavanı: `sitemonitor_exporter_capped{family} == 1` → aile başına kayıt tavanını artırın ya da izleme başına serileri
   kapatın (Prometheus yükü). Günlükte saatte bir `seri tavanına takıldı` uyarısı.

Sürüm anotasyonu "yeni görülen build etiketi" ile çizilir (`count by (version, commit, environment) (sitemonitor_build_info)
unless … offset 5m`); `changes(sitemonitor_build_info[10m])` kullanan eski elle kurulmuş panolar hiç anotasyon üretmez —
`docs/GRAFANA_SURUM_ANOTASYON.md` düzeltmesine bakın.

Çok pod: her pod kuruluş ölçümlerini aynı değerle yayar; kendi sorgularınızda `max by (<ölçümün etiketleri>)` ile
birleştirin (ör. `sum(max by (type, team, team_id, state) (sitemonitor_monitors{state="down"}))`). Pod'a özgü olanlar:
`sitemonitor_sweep_leader` (toplamı 1 olmalı), `sitemonitor_exporter_*`, `sitemonitor_database_status`,
`sitemonitor_push_circuit_open`.

Örnek Prometheus alarm kuralları:

```yaml
groups:
  - name: sitemonitor
    rules:
      - alert: SiteMonitorMonitorDown
        expr: max by (type, team, monitor) (sitemonitor_monitor_state) == 2
        for: 5m
        labels: { severity: warning }
      - alert: SiteMonitorNoSweepLeader
        expr: sum(sitemonitor_sweep_leader) == 0
        for: 5m
        labels: { severity: critical }
      - alert: SiteMonitorExporterStale
        expr: time() - max(sitemonitor_exporter_last_refresh_timestamp_seconds) > 600
        labels: { severity: warning }
```

## Günlükler ve izleme

2026-10-11'den beri **prod günlüğü JSON** (satır başına bir ECS nesnesi; stdout + `/var/log/site-monitor/*.log`), yerel
çalıştırma düz metin. Geri dönmek için `LOG_FORMAT=text` (Helm `config.logFormat: text`); çeşit `LOG_JSON_FORMAT=ecs|logstash|gelf`.

- **İstek kimliği her satırda:** hata gövdesindeki `request_id` = yanıt başlığı `X-Request-Id` = günlükte `requestId`
  (JSON) / `rid=<id>` (düz metin). Kullanıcı "Teknik ayrıntı"daki kimliği iletir:
  ```bash
  kubectl logs -n "$NS" "$DEPLOY" | grep '"requestId":"<id>"'          # JSON
  kubectl logs -n "$NS" "$DEPLOY" | grep 'rid=<id>'                    # düz metin
  ```
  Aynı isteğin havuza düşen işleri (ör. "Şimdi kontrol et") da aynı kimlikle yazılır.
- **jq ile okuma:** `kubectl logs … | jq -r 'select(."log.level"=="ERROR" or .log.level=="ERROR") | .message'`.
  Eski `grep "decrypt failed"` / `grep 'Şema yamaları'` komutları JSON satırda da çalışır (ileti kaçışsız UTF-8).
- **İz (OpenTelemetry):** her isteğin ve zamanlanmış işin bir `traceId`'si vardır (günlükte `traceId` / `trace=`).
  Örnekleme %10 (`config.otel.samplingProbability`). Span'lar YALNIZ `config.otel.endpoint` (tam OTLP/HTTP iz URL'si,
  ör. `http://otel-collector:4318/v1/traces`) verilince dışa aktarılır; boşken uygulama hiçbir yere bağlanmaz.
  Tamamen kapatmak: `OTEL_SDK_DISABLED=true`. Metrikler Prometheus'ta kalır (OTLP metrik gönderimi yok). İzlenen
  sitelere giden kontrol isteklerine `traceparent` başlığı EKLENMEZ (Boot HTTP istemci kurucuları kullanılmıyor).

## Kendi kendini izleme

2026-10-11'den beri uygulama dakikada bir, kümede tek pod'da (`scheduler_lock` `watchdog`) kendi işleyişini ölçer ve iki
ardışık ölçümde doğrulanan sorunu / düzelmeyi bildirir (e-posta: Ayarlar → Genel → Kendi kendini izleme alıcıları, boşsa
sistem yöneticisi adresi; isteğe bağlı webhook ve doğrudan push). Ekran: Sistem Sağlığı → Kendi kendini izleme. Dış izleyici:
`GET /api/public/health/watchdog` (oturumsuz; UP/DEGRADED 200, kritik sorun ya da 5 dk'dan eski değerlendirme 503).

| Kontrol | Ne zaman sorun | İlk bakılacak yer |
|---|---|---|
| Tarama liderliği (kritik) | Liderlik kirası bir TTL'den uzun süredir dolu, kimse almadı | Pod'lar ayakta mı, `scheduler` günlüğü; Sistem Sağlığı → Zamanlayıcı |
| İzleme taramaları | Bir türde etkin izlemelerin ≥ %50'si zamanında kontrol edilmiyor | Görev havuzu doygunluğu (Zamanlayıcı ve yürütücüler), vekil |
| Sertifika taraması | Son sertifika sonucu 4 saatten eski | `scheduler_lock` `cert-check`, tarama günlüğü |
| Bildirim gönderimi | Son 30 dk'da ≥ 3 e-postanın ≥ %50'si başarısız | [SMTP düştü](#smtp-düştü) |
| Push kuyruğu | ≥ 10 push 10 dk'dan uzun gecikmede ya da devre kesici açık | Push ağ geçidi, Webhook Push Gönderim Logu |
| Veritabanı (DOWN kritik) | Sağlık kontrolü DEGRADED/DOWN | [DB dolması](#db-dolması), `/api/public/health/db` |

Bildirimler `notification_logs`'ta tetik `WATCHDOG` (test `WATCHDOG_TEST`). Hiçbir kanaldan gidemeyen bildirim 15 dk'da
bir yeniden denenir; sistem bakımı "bildirimler sussun" açıkken gönderilmez (iz `SKIPPED: sistem bakımı`). Veritabanı
çökükken yalnız veritabanı kontrolü pod belleğinde değerlendirilir ve e-posta / webhook / push yine gider.

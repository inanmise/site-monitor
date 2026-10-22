# Site Monitor — Operasyon Runbook'u

> Hedef kitle: prod'u işleten ekip (nöbetçi / platform). Planlı dağıtım adımları
> [`PROD_DEPLOY_CHECKLIST.md`](PROD_DEPLOY_CHECKLIST.md)'dedir; bu dosya **olay anında** ne yapılacağını anlatır.
> Prod bugün **tek pod** çalışır (dikey ölçekleme): pod'un gitmesi = tam kesinti. Çok pod sürüm 1'den (2026-10-21) beri
> iki pod desteklenir; geçiş ayrı bir adımdır ve önce ön koşullar doğrulanır — [Çok pod](#çok-pod).
>
> Değişkenler checklist'teki gibi: `REL`, `NS`, `DEPLOY`, `SECRET` (`helm list -A`'dan okunur).
> **Günlükleri her zaman TÜM pod'lardan okuyun:** `kubectl logs "$DEPLOY"` yalnız BİR pod'u okur; birden çok pod
> varken diğerindeki satır görünmez. Bu dosyadaki komutlar `kubectl logs -n "$NS" -l app.kubernetes.io/instance="$REL"
> --prefix --tail=-1` biçimindedir (`--prefix` satırın hangi pod'dan geldiğini yazar; tek pod'da da aynen çalışır).

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
| İki pod: hangi pod izliyor, liderlik el değiştirmiyor, aynı bildirim iki kez geldi, 1 → 2 pod geçişi | [Çok pod](#çok-pod) |

---

## Dağıtım (rollout)

Komut ve ön kontroller: [`PROD_DEPLOY_CHECKLIST.md`](PROD_DEPLOY_CHECKLIST.md) — başka komut kullanmayın.

Rollout'ta olanlar:
- `maxSurge: 1`, `maxUnavailable: 0` → yeni pod hazır olmadan eskisi kapanmaz; kısa süre **replika + 1** pod birlikte
  koşar (DB bağlantıları ve kota buna göre). İşleri tarama liderliği kirası ve `scheduler_lock` (DB) paylaştırır,
  oturumlar JDBC'de olduğu için kullanıcılar oturum kaybetmez ([Çok pod](#çok-pod)).
- Eski pod'un kapanışı (2026-10-21): önce `preStop` 10 sn (pod Service / ingress uç noktalarından düşer, istekler
  yeni pod'a gider), sonra SIGTERM → liderlik kirası hemen bırakılır, push işçisi durdurulur ve bildirim havuzlarıyla birlikte boşaltılır (≤ 20 sn), Spring
  zarif kapanışı (web ≤ 20 sn, yürütücüler ≤ 20 sn, zamanlanmış işler ≤ 20 sn, geri kalan bütün `@PreDestroy`
  beklemeleri birlikte ≤ 10 sn — `ShutdownDeadline`). Hepsi `terminationGracePeriodSeconds: 120` içinde; aşılırsa
  kubelet SIGKILL atar — kira zaten bırakılmıştır, ama süren iş kesilir ve iş kilitleri TTL'lerine (3 dk) kalır.
- **İlk çok pod sürümüne geçiş (20.164 ve öncesi → bu sürüm) BİR KERELİKTİR:** kapanan eski pod kendi eski
  ayarlarıyla kapanır — grace 60 sn, preStop yok, 30 sn'lik aşamalar, liderlik kirası yalnız `@PreDestroy`'da
  bırakılır. Bu geçişte 502 görülebilir, eski pod 60 sn'yi aşarsa izleme ~4 dk durabilir (kira 180 sn) ve eski
  pod'un kuyruğundaki "ÇÖZÜLDÜ" postaları kaybolabilir (eski sürümde kalıcı işaret yok). Sessiz bir saatte dağıtın;
  sonra Sistem Sağlığı → `sweep_leader.owner` ve Bildirim Geçmişi'ne bakın. Yukarıdaki sayılar sonraki dağıtımdan
  itibaren geçerlidir. Ön ve son kontroller: [`PROD_DEPLOY_CHECKLIST.md` → İlk çok pod sürümüne geçiş](PROD_DEPLOY_CHECKLIST.md#ilk-çok-pod-sürümüne-geçiş-20164-ve-öncesi--bu-sürüm--bir-kerelik).
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
- **Çok pod sürüm 1'in iki tekil indeksi geri almada KALIR** (`ux_push_eventless_key_user`, `ux_noe_one_ongoing`) — silmeyin,
  yeniden ileri gidildiğinde kullanılır. Eski sürüm onlarla çalışır: eski push yazımları önce daha GENİŞ bir anahtarla
  "var mı?" diye bakar ve ihlali yakalar, eski kesinti / bastırma yazımları ihlali yakalayıp WARN yazar (işlem dışında);
  yalnız eski bir `@Transactional` çağıranın içindeki gerçek bir yarış o çağıranı geri sarabilir (nadir). Yeni kolonlar
  (`close_state`, `resolution_notify`, `change_state`, posta durum kolonları) eski sürümce yok sayılır.
- Geri alma **secret key değişikliğini** geri almaz; anahtar değiştiyse önceki değeri Secret'a geri koyun.
- Son çare DB yedeğinden dönüş (yedekten sonraki kayıtlar kaybolur) — platform/DBA ile.

## Alarm fırtınası

Çok sayıda izleme kısa sürede birlikte düştüğünde.

1. **Önce kaynağı ayırın — hedefler mi düştü, izleyen mi?** Tüm türler (HTTP, Port, DNS, Ping, RDAP) aynı
   anda kırmızıysa sorun büyük olasılıkla pod'un kendi çıkışıdır (vekil, DNS, firewall, node). Sistem Sağlığı
   ağ durumu kartı / `GET /api/system/network-status`, Tanılama ekranı ve pod'dan (birden çok pod varken izleme
   taramalarını **lider** pod koşar — `"$DEPLOY"` yerine onun adını verin, bkz. [Çok pod](#çok-pod) → "Lider kim?"):
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
  ret parola / AD parolası DOĞRULANDIKTAN sonra gelir: giriş 403 `USERNAME_PREFIX_NOT_ALLOWED`; yanlış parola ve olmayan ad
  kural kapalıyken olduğu gibi genel 401 + kilit merdivenini alır (muaf adlar, bootstrap yönetici adı yanıttan okunamaz).
  Kodla girişte aynı genel yanıt ve kod gitmez. Açık oturumlar sonraki istekte (~15 sn) kapanır, hatırlanan girişler
  silinir. Çok pod'da ayar ≤ 10 sn'de yayılır: kural açıldıktan sonraki ilk ≤ 10 sn içinde eski ayarlı bir pod bir girişi
  kabul edebilir, sonraki istek keser; kesilmiş (mezar taşı) oturum her pod'da aynı uyarıyı alır. Kurala takılan hesap e-posta
  onay bağlantılarıyla (teknik hata raporu PO onayı, haftalık rapor) da karar veremez — sayfa sebebini söyler, bağlantı
  tüketilmez — ve PO onay isteği postası almaz. Denetim: `SESSION_ENDED_USERNAME_PREFIX` ve `LOGIN_FAILED` (BLOCKED, neden
  `USERNAME_PREFIX`, kanal LOCAL / LDAP — kilit sayacına girmez).
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
kubectl logs -n "$NS" -l app.kubernetes.io/instance="$REL" --prefix --tail=-1 | grep -c "decrypt failed"   # > 0 = anahtar, şifreli değerlerle uyuşmuyor
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
  kubectl logs -n "$NS" -l app.kubernetes.io/instance="$REL" --prefix --tail=-1 | grep '"requestId":"<id>"'   # JSON
  kubectl logs -n "$NS" -l app.kubernetes.io/instance="$REL" --prefix --tail=-1 | grep 'rid=<id>'             # düz metin
  ```
  Birden çok pod varken istek herhangi birine düşmüş olabilir — `--prefix` satırın pod'unu gösterir.
  Aynı isteğin havuza düşen işleri (ör. "Şimdi kontrol et") da aynı kimlikle yazılır.
- **jq ile okuma:** `kubectl logs … | jq -r 'select(."log.level"=="ERROR" or .log.level=="ERROR") | .message'`
  (jq'ya giden komutta `--prefix` kullanmayın — önek satırı JSON olmaktan çıkarır; pod başına ayrı okuyun).
  Eski `grep "decrypt failed"` / `grep 'Şema yamaları'` komutları JSON satırda da çalışır (ileti kaçışsız UTF-8).
- **İz (OpenTelemetry):** her isteğin ve zamanlanmış işin bir `traceId`'si vardır (günlükte `traceId` / `trace=`).
  İzi bir toplayıcıya göndermenin **iki yolu** var; ikisini birlikte açmayın (`helm template` hata verir):

  | | A) Uygulamanın kendi gönderimi | B) OpenTelemetry Java ajanı (OpenTelemetry Operator) |
  |---|---|---|
  | Helm | `config.otel.endpoint: http://otel-collector:4318/v1/traces` (TAM OTLP/HTTP iz URL'si) | `config.otel.agent.enabled: true` + `config.otel.agent.instrumentation: <ns>/<Instrumentation adı>` |
  | Ne izlenir | HTTP istekleri ve zamanlanmış işler (Micrometer gözlemleri) | Ajanın enstrümantasyonu: Tomcat/Spring MVC, JDBC/Hibernate, Spring Data, zamanlanmış işler… |
  | Örnekleme | `config.otel.samplingProbability` (%10) | Instrumentation kaynağındaki `sampler` (`samplingProbability` uygulanmaz) |
  | Taşıma | yalnız OTLP/HTTP (4318) — imajda gRPC göndericisi yok | ajan kendi göndericisiyle gRPC (4317) da gönderir |

  **A** boşken uygulama hiçbir yere bağlanmaz; tamamen kapatmak: `OTEL_SDK_DISABLED=true`. Metrikler her iki yolda da
  Prometheus'ta kalır (OTLP metrik gönderimi yok).

  **B — ajan ile (2026-10-21).** Operatör pod'a `JAVA_TOOL_OPTIONS=-javaagent:…` ve `OTEL_*` değişkenleri ekler. Uygulama
  açılışta ajanı algılar (önyükleme sınıfı `io.opentelemetry.javaagent.bootstrap.AgentInitializer`) ve izi ajana bırakır:
  günlükte TEK satır `OpenTelemetry Java ajanı algılandı; uygulamanın kendi OTLP gönderimi kapatıldı, izleri ajan
  gönderiyor`. Boot'un kendi OTLP gönderimi ve SDK'sı kapanır, Boot ajanın `OTEL_*` değişkenlerini kendi ayarına
  eşlemez, Micrometer yeni span açmaz (günlükteki iz kimliği ajanınkidir; Tempo/Jaeger'da aynı iz). Geri almak (iki ayrı
  iz sistemi — önerilmez): `SITE_MONITOR_TRACING_DEFER_TO_AGENT=false`. `config.otel.agent.enabled` kapsayıcıya şu
  ortamı ekler (operatör kapsayıcıda zaten olan değişkeni ezmez; gerekçesiyle):

  | Değişken | Neden |
  |---|---|
  | `OTEL_INSTRUMENTATION_JAVA_HTTP_CLIENT_ENABLED=false`, `OTEL_INSTRUMENTATION_HTTP_URL_CONNECTION_ENABLED=false` | Uygulama izlenen sitelere (HTTP / Keyword / Sayfa / Sayfa hızı, HSTS, OCSP/CRL, RDAP, web-WHOIS) ve webhook / push / GeoIP servislerine `java.net.http` ve `HttpURLConnection` ile gider. Ajan bu istemcileri izlerse her isteğe `traceparent` ekler (izlenen siteye iç iz kimliğimiz gider, bazı WAF'lar tanımadığı başlığı reddedebilir) ve her kontrol için bir istemci span'ı üretir (saatte binlerce). SMTP, LDAP ve DNS ajan tarafından zaten izlenmiyor; ham soket kontrolleri (port, TLS profili, ham HTTP tanılama) da. |
  | `OTEL_METRICS_EXPORTER=none` | Metrikler Prometheus'ta (`/metrics`, `/metrics/sitemonitor`); ajanın OTLP metrikleri ikinci bir kaynak olurdu. |
  | `OTEL_SERVICE_NAME` | `config.otel.agent.serviceName`, boşsa Deployment adı. |
  | `MANAGEMENT_OPENTELEMETRY_MAP_ENVIRONMENT_VARIABLES=false` | Boot ajanın değişkenlerini kendi dışa aktarıcısına eşlemesin (eski sürümlerde açılışı düşüren şey; uygulama bunu kendisi de yapar). |
  | `OTEL_INSTRUMENTATION_COMMON_LOGGING_TRACE_ID_KEY=traceId`, `…_SPAN_ID_KEY=spanId` | JSON günlükte alan adları `traceId` / `spanId` kalır (ajanın varsayılanı `trace_id` / `span_id`; eski ajan sürümleri bu anahtarı tanımaz → `trace_id`, düz metin `trace=` ikisini de okur). |
  | `OTEL_RESOURCE_ATTRIBUTES` | `config.otel.resourceAttributes` doluysa (operatör kendi özniteliklerini ekler). |
  | `OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS=AWSAccessKeyId,Signature,X-Amz-Signature,X-Amz-Credential,X-Amz-Security-Token,sig,X-Goog-Signature,token,access_token,api_key,apiKey,apikey,key,code,password,secret,signature,client_secret,subscription-key,auth,jsessionid,otp` | Ajan sunucu span'larında `url.query`'yi kaydeder; 2.32 yalnız `AWSAccessKeyId`, `Signature`, `X-Amz-*`, `sig`, `X-Goog-Signature`'ı gizler. Haftalık rapor ve teknik hata raporu onay bağlantıları (`GET /api/weekly-reports/approve-link?token=…`, `GET /api/tech-error-reports/approve-link?token=…`) 14 gün geçerli, oturumsuz onay belirteci taşır — gizlenmezse iz arka ucunu okuyan herkes raporu onaylayabilir. Ayar ajanın listesinin YERİNE geçer (genişletmez), o yüzden yerleşik adlar tekrar yazılır; eşleşme büyük/küçük harf duyarlı, kodlanmamış ad üzerinden. Uygulamada sorgu dizesinden kimlik alan başka uç yok (oturum çerezde, giriş / OTP gövdede, metrik belirteci `Authorization` başlığında) ve URL YOLUNDA kimlik taşınmaz (kapı `OtelAgentQueryRedactionGateTest`); listedeki diğer adlar derinlemesine savunma. Liste değişirse tek kaynak `OpenTelemetryAgentProbe.SENSITIVE_QUERY_PARAMETERS`. |

  Ek değişken: `config.otel.agent.extraEnv` (ör. `OTEL_LOGS_EXPORTER: none` — ajan varsayılanda uygulama günlüklerini de
  OTLP ile gönderir; `OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED: "false"` — zamanlanmış iş span'ları). Ek açıklamayı
  `podAnnotations`'a ELLE yazmayın: yukarıdaki ortam eklenmez (uygulama yine açılır ama izlenen sitelere `traceparent`
  gider ve günlükte bunu söyleyen bir UYARI çıkar; `helm install/upgrade` notları da uyarır).

  **Bellek ve açılış:** ajan JVM'e ~100–200 MiB (metaspace + heap) ekler ve açılışı uzatır (sınıfların enstrümante
  edilmesi; ölçümde ~20 sn → ~30 sn). İlk açılışta `kubectl top pod` ve readiness süresine bakın; gerekirse
  `resources.limits.memory`'yi ve `probes.startup.failureThreshold`'u (varsayılan 30 × 10 sn) büyütün.

  **gRPC koruması (ajansız):** `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` (ya da `…otlp.transport=grpc`) verilir ve ajan yoksa
  imajda gRPC göndericisi olmadığından o gönderim KAPATILIR ve günlükte tek UYARI düzeltmeyi söyler
  (`OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf` + 4318, ya da `config.otel.endpoint`). Açılış düşmez.

  **Eski sürüm (≤ 20.163) ajanla düşüyorsa — sürüm beklemeden geçici çözüm:** kapsayıcı ortamına
  `MANAGEMENT_OPENTELEMETRY_MAP_ENVIRONMENT_VARIABLES=false` ekleyin (Boot ajanın `OTEL_*` değişkenlerini eşlemez →
  "No GrpcSenderProvider found on classpath" kalkar), izlenen sitelere `traceparent` gitmesin diye
  `OTEL_INSTRUMENTATION_JAVA_HTTP_CLIENT_ENABLED=false`, `OTEL_INSTRUMENTATION_HTTP_URL_CONNECTION_ENABLED=false` ve onay
  bağlantılarının `?token=` değeri iz arka ucuna gitmesin diye url.query gizleme listesi (yukarıdaki tablo):
  ```bash
  kubectl set env -n "$NS" deploy/"$DEPLOY" -c <kapsayıcı> MANAGEMENT_OPENTELEMETRY_MAP_ENVIRONMENT_VARIABLES=false \
    OTEL_INSTRUMENTATION_JAVA_HTTP_CLIENT_ENABLED=false OTEL_INSTRUMENTATION_HTTP_URL_CONNECTION_ENABLED=false \
    OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS=AWSAccessKeyId,Signature,X-Amz-Signature,X-Amz-Credential,X-Amz-Security-Token,sig,X-Goog-Signature,token,access_token,api_key,apiKey,apikey,key,code,password,secret,signature,client_secret,subscription-key,auth,jsessionid,otp
  ```
  Ek açıklamayı (`instrumentation.opentelemetry.io/inject-java`) elle kullanan HER sürüm için son üç ayar (iki istemci
  enstrümantasyonu + `url.query` listesi) gerekir; yeni sürüm eksik olanı açılışta UYARI ile söyler (`traceparent` /
  `url.query`). `podAnnotations`'ta `inject-java: "false"` (açıkça kapalı) ayarlanmamış sayılır.
  (Bir sonraki `helm upgrade` bunları siler — kalıcı yol `config.otel.agent.enabled`.) Eski sürümde uygulama kendi
  SDK'sıyla da span üretir (dışa aktarmaz); günlükteki `trace=` yine ajanın iziyle aynıdır (ölçüldü).

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
çökükken yalnız veritabanı kontrolü pod belleğinde değerlendirilir ve e-posta / webhook / push yine gider. Birden çok pod
varken bu "veritabanı çöktü" uyarısını yalnız son doğrulanmış lider gönderir (her pod ayrı ayrı göndermez); takipçi ancak
son okuduğu kira yenilenmemiş görünüyorsa ve üstünden bir kira süresi + doğrulama süresi geçmişse duyurur. Düzelme
bildirimi (RECOVER) önce satıra damgalanır — tek pod gönderir. Değerlendirme kilidi `watchdog` 2 dk TTL ile yenilenir.

## Çok pod

> Çok pod sürüm 1 (2026-10-21). Aynı imaj N replikada koşar; kullanıcı istekleri bütün pod'lara dağılır, **izleme işi
> tek pod'da (lider) koşar**. İkinci pod yüksek erişilebilirlik ve arayüz / API kapasitesi getirir, izleme kapasitesi
> getirmez — izleme yükü büyürse önce dikey ölçekleyin (CPU / bellek / `dbPoolMax`). HPA kapalı kalır.

### Kim neyi koşar

| İş | Nerede | Çift koşmayı ne önler |
|---|---|---|
| Dokuz izleme türünün taramaları, envanter erişilebilirlik (HTTP) taraması, doğrulama / kurtarma yeniden kontrolleri, DNS ve alan adı değişiklik tespiti | **Lider** pod | Tarama liderliği kirası (`monitor_check_schedule`, anahtar `lease:sweep-leader`, 180 sn; ayrı iş parçacığında 60 sn'de bir yenilenir) + izleme başına küme vadesi + kirasını kaybetmiş pod'un zamanlanmış sonuçları alarm üretmez |
| TLS profili işi; takipçide basılan "Şimdi kontrol et" sonucunun alarm değerlendirmesi (`monitor_eval_inbox` üzerinden lidere iletilir, ≤ 5 sn); veritabanı çökükken kendi-kendini-izleme uyarısı | Lider | Kira |
| Sertifika taramaları (saatlik, bayat, tam), zamanlanmış raporlar / hatırlatmalar / özetler, gece temizliği, push kuyruğu, fırtına yaşam döngüsü, eskalasyon adımları, kendi-kendini-izleme turu, yetim QUEUED_RETRY / çözüm bildirimi süpürücüleri | Kilidi ya da turu ilk alan **herhangi** bir pod | `scheduler_lock` satırı (uzun iş sürdükçe yenilenir) + küme turu (`round:<ad>`, bir dönemde bir kez) + satır başına koşullu sahiplenme (aynı push / posta iki kez gitmez) |
| Kullanıcı istekleri, "Şimdi kontrol et", tanılama, dışa aktarım | İsteği alan pod | Oturumlar PostgreSQL'de (`spring_session`) — her pod her oturumu tanır |

### Lider kim?

- **Sistem Sağlığı → Zamanlayıcı:** `sweep_leader.owner` = `<pod adı>-<8 hane>`; `held_by_me` = yanıtı veren pod lider mi.
- **Grafana:** `sum(sitemonitor_sweep_leader) == 1` olmalı (0 = kimse taramıyor, 2 = iki lider — olmamalı; "Kendi kendini
  izleme" → Tarama liderliği de bunu ölçer).
- **SQL:**
  ```sql
  SELECT claimed_by AS lider, to_timestamp(next_due_at / 1000.0) AS kira_bitisi
    FROM monitor_check_schedule WHERE monitor_key = 'lease:sweep-leader';
  ```
  Pod adı = `lider` değerinden son `-xxxxxxxx` çıkarılınca kalan. Liderin günlüğü:
  `kubectl logs -n "$NS" <pod adı> --tail=300`.
- **Liderliği başka pod'a taşımak:** lider pod'u silin (`kubectl delete pod -n "$NS" <pod adı>`) — zarif kapanış kirayı
  hemen bırakır, diğer pod ≤ ~35 sn'de alır. Kira satırını elle silmeyin (eski lider bir sonraki yenilemede geri alır).

### Devir süreleri

| Olay | İzlemenin durduğu süre |
|---|---|
| Planlı kapanış (rollout, `kubectl drain`, `delete pod`) | **≤ ~35 sn.** preStop 10 sn boyunca lider taramaya devam eder; SIGTERM'de kira hemen bırakılır, takipçi ≤ 5 sn'de alır, ilk taraması ≤ 30 sn'de başlar. |
| Pod öldürüldü (SIGKILL, `OOMKilled`, node kaybı, grace aşıldı) | **≤ ~4 dk:** kira süresinin (180 sn) dolması + 5 sn + ilk tarama. O pod tam o an bir push batch'i gönderiyorduysa o batch ≤ 10 dk sonra başka bir pod'dan gider (gönderilmekte olan batch'in kirası 10 dk — iki kez gitmesin diye). |
| Lider kilitlendi (pod ayakta, taramalar ilerlemiyor) | 10 dk tarama tıkı yoksa lider kirayı bırakır (WARN, 2 × TTL yeniden almaz) → takipçi devralır; son tıktan 30 dk sonra hâlâ tık yoksa liveness BROKEN → kubelet pod'u yeniden başlatır. |
| Veritabanı bir pod'dan kısa süre okunamıyor (havuz doldu, ağ) | Lider, son doğrulanmış kirası bitmeden 30 sn öncesine kadar lider kalır; takipçi kendini asla lider ilan etmez; küme tur sahiplenmesi iki kez yeniden denenir, olmazsa o tur atlanır (WARN — sonraki tur / yakalama telafi eder; iki kez koşmaktansa bir kez gecikir). |

Her liderlik değişiminde yeni lider alarm kararlarının bellekteki durumunu sıfırdan kurar (doğrulama zincirleri, kurtarma
sayaçları): açılmak / kapanmak üzere olan bir alarm doğrulamasını yeni liderde baştan yapar — birkaç dakika gecikme olur,
çift bildirim olmaz. DNS / alan adı değişikliği (`DNS_CHANGED` / `DOMAINMON_CHANGED`) satırda PENDING olarak kalır; yeni
lider 2 saate kadar yeniden kuyruğa alır, değişiklik bildirimi kaybolmaz.

### Kapanış ve süre bütçesi

`terminationGracePeriodSeconds` kubelet preStop'u başlattığı an sayılır; aşılırsa SIGKILL gelir ve kira dolana dek
(180 sn) izleme duraklar:

| Adım | Süre | Ayar |
|---|---|---|
| preStop `sleep` (pod Service / ingress uç noktalarından düşer) | 10 sn | Helm `preStopSleepSeconds` (0 = kanca yok) |
| SIGTERM → liderlik kirası bırakılır, yeni kilit / tur / liderlik alınmaz (`ContextClosedEvent`, ilk dinleyici) | anında | — |
| Push işçisi durdurulur (yeni tur / batch yok, kalan batch'lerin kirası bırakılır) ve süren push biter; bildirim havuzları boşaltılır (çözüm postaları, yeniden gönderimler, giriş sorunu postaları) — hepsi AYNI süre içinde | ≤ 20 sn | `WebConfig.NOTIFY_AWAIT_SECONDS` |
| Web zarif aşaması (süren istekler biter) | ≤ 20 sn | `SHUTDOWN_PHASE_TIMEOUT` (`spring.lifecycle.timeout-per-shutdown-phase`) |
| Yürütücü aşaması (süren kontroller / bildirimler biter) | ≤ 20 sn | aynı |
| Zamanlanmış işler havuzu (süren işler biter, kesilmez) | ≤ 20 sn | `SCHEDULING_AWAIT_TERMINATION` |
| Geri kalan bütün yok etme beklemeleri (e-posta 421 kuyruğu, push, kod girişi, olay bildirimi, elle kontrol havuzu, bildirim havuzlarının kendisi) | birlikte ≤ 10 sn | `config/ShutdownDeadline.DESTROY_BUDGET_MS` |
| **Toplam** | **≈ 100 sn < 120 sn** | Helm `terminationGracePeriodSeconds` (120) |

Birini büyütürseniz grace'i de büyütün: `preStop + boşaltma + 2 × aşama + zamanlanmış bekleme + 10 < grace` (yeni bir
havuzun `awaitTermination`'ı `ShutdownDeadline.waitMs` kullanmak zorundadır — `ShutdownBudgetGateTest`). İş kilitleri
`@PreDestroy`'da (zamanlayıcı durduktan sonra) bırakılır. `helm template`
preStop ≥ grace'i reddeder; CI (`scripts/helm-template-check.sh`) ve `ShutdownBudgetGateTest` varsayılanları denetler.

### Kilitler

```sql
SELECT name, locked_by, locked_until FROM scheduler_lock ORDER BY name;   -- locked_by = <pod adı>-<8 hane>, UTC
```
- İş kilidini tutan pod onu 30 sn'de bir yeniler (`ClusterLockService`; yenilenen TTL en çok 3 dk, izleme taraması ve
  kendi-kendini-izleme kilitleri 2 dk; sert tavan 3 sa, gece temizliği / ping toparlama 4 sa). Yenileme satırı bulamazsa
  kilit KAYIP sayılır (tek WARN). Kapanışta pod kendi kilitlerini bırakır; ölen pod'un kilidi TTL dolunca düşer, yeni
  pod açılışta kendi ölü örneğinin kilitlerini temizler. Sistem Sağlığı → Zamanlayıcı `held_locks` bu pod'un kilitlerini,
  `round_claim_failures` okunamadığı için atlanan turları gösterir.
- **Takılı kilit:** Sistem Sağlığı → Zamanlayıcı → kilidi bırak (yalnız `cert-check`; `DELETE /api/admin/system/scheduler-lock`,
  denetimde `SCHEDULER_LOCK_RELEASE`). Diğerleri SQL ile: `DELETE FROM scheduler_lock WHERE name = '<ad>';` — kilidi
  tutan pod canlıysa bir sonraki yenilemede kilidi kaybettiğini görür (WARN) ve sertifika taraması sonuçlarını işlemez.
  Önce sahibinin gerçekten ölü ya da takılı olduğundan emin olun (günlükte o işin son satırları).
- **Takılı ağ kesintisi kaydı:** sertifika taramasının "ağ kesintisi" durumu pod belleğinde değil `network_outage_events`
  satırındadır (kaynak başına tek ONGOING satır); kesinti açıkken taramalar alarm işlemez. Gerçekten bitmiş ama kapanmamış
  bir kesintiyi kapatmak (pano bütün pod'larda ≤ 10 sn'de izler; çözüm postası gitmez):
  ```sql
  UPDATE network_outage_events SET status = 'RESOLVED', resolved_at = to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS'),
         resolved_mail_status = 'SKIPPED',
         alert_mail_status = CASE WHEN alert_mail_status IN ('PENDING', 'RETRY', 'SENDING') THEN 'SUPERSEDED' ELSE alert_mail_status END
   WHERE status = 'ONGOING' AND (source IS NULL OR source = 'CERT');
  ```
  Çoğu zaman gerekmez: açık bir kesinti, ağ hatası oranı eşiğin altındaki küçük turlarla 24 saat sonra, bütün etkin
  ağ envanterini kapsayan bir turla hemen kendiliğinden kapanır. Yönetici kesinti postasının her kararı (gönderildi,
  atlandı, başarısız, vazgeçildi, geçersiz kaldı, bilinmiyor) SMTP günlüğünde tetik `NETWORK_OUTAGE` ile görünür.
- **Takılı fırtına kapanışı:** fırtına iki aşamada kapanır (`alert_storms.close_state` `CLOSING` → `DONE`, kurtarma
  postası `recovery_state` ile tek kez). Yarıda kalan (`CLOSING`, sahiplenmesi 5 dk'dan eski) bir kapanışı herhangi bir pod
  bir sonraki fırtına turunda tamamlar; 5 denemede olmazsa yine `DONE` yazılır ve üyelere iz düşer. `recovery_state =
  'UNKNOWN'` = kurtarma postası gitmiş de olabilir, gitmemiş de — `notification_logs`'ta tetik `STORM_RESOLVE`'a bakın;
  yeniden gönderilmez.

### Ön koşullar — 1 → N geçişinden ÖNCE

Ayrıntılı komutlar ve onay kutuları: [`PROD_DEPLOY_CHECKLIST.md` → Çok pod](PROD_DEPLOY_CHECKLIST.md#çok-pod-1--2-replika).

1. **P1 — Her node'dan aynı çıkış (en büyük risk).** Liderlik her rollout'ta node değiştirir ve bütün izlemeyi kira
   sahibi pod koşar. Vekil ACL'i, firewall izin listeleri (doğrudan kontrol edilen iç hedefler), SMTP aktarıcısı,
   LDAP / AD, push ağ geçidi ve RDAP, pod'ların koşabileceği **her** node'dan aynı sonucu vermeli. Node IP'sine bağlı
   bir izin listesi varsa ilk devirde toplu "kesinti" ya da sessiz posta kaybı yaşanır (2026-10-02 node / egress olayı).
   Denetim: uygulamanın kendi imajı ve ConfigMap'iyle (vekil, SMTP) her node'a sabitlenmiş tek kullanımlık bir pod
   (`scripts/multipod-egress-check.sh`; restricted PodSecurity ile uyumlu, iş bitince pod silinir):
   ```bash
   # Ek hedefler: LDAP / AD, push ağ geçidi ve doğrudan (vekilsiz) kontrol edilen birkaç iç hedef (host:port).
   # SMTP ve vekil ConfigMap'ten gelir. Node verilmezse kümedeki bütün node'lar denenir — pod'ların koşabileceği
   # node'ları verin (nodeSelector / taint'ler).
   NS="$NS" REL="$REL" TARGETS="<ldap-host>:636 <push-host>:443 <iç-hedef-1>:443" \
     bash scripts/multipod-egress-check.sh <node-1> <node-2> <node-3>
   ```
   Beklenen: her node'da `rdap 200` (vekil üzerinden) ve bütün hedefler `OK`; çıkış kodu 0. Bir node başarısızsa ya
   kural düzeltilir ya da pod'lar sağlam node'lara sabitlenir (Helm `nodeSelector` / `affinity`). Node havuzu her
   değiştiğinde (yeni node, yükseltme) yeniden koşun.
2. **P2 — Saat (NTP).** Kira ve kilitler pod saatiyle karşılaştırılır; ~120 sn'den büyük kayma iki lider üretir. Egress
   betiğinin her node için yazdığı `saat <epoch>` değerleri birbirinden ve yerel `date -u +%s` /
   `SELECT extract(epoch from now())` (DB) değerinden en çok 1-2 sn farklı olmalı; fazlası platform ekibine (chrony / NTP).
3. **P3 — Veritabanı bağlantıları.** Rollout'ta `replika + 1` pod birlikte açıktır:
   `max_connections ≥ (replika + 1) × dbPoolMax + 25`. `SHOW max_connections;` — 2 replikada `dbPoolMax` 30 için ≥ 115
   (pratikte 120), `max_connections` 100 ise `dbPoolMax: "25"`. Formül ve PgBouncer: [`db-scaling.md`](db-scaling.md) §3.
   **PgBouncer yalnız `session` kipinde** (şema yaması / ertelenmiş indeks oturum düzeyi advisory lock ve
   `SET lock_timeout … RESET` kullanır; `transaction` kipi bunları bozar).
4. **P4 — Kota.** Namespace kotası ve LimitRange `replika + 1` pod'u kabul etmeli (prod: 3 × 1.5Gi / 500m request,
   3 × 3Gi / 2 CPU limit) ve bir node'da yeni pod'a yer olmalı: `kubectl describe resourcequota -n "$NS"`.
5. **P5 — Prometheus pod keşfi.** Kazıma `role: pod` ile her pod'u ayrı hedef olarak okumalı (bkz. "Grafana panosu ve
   Prometheus"); Service üzerinden kazıma pod'a özgü serileri (`sitemonitor_sweep_leader`, JVM) karıştırır. Kontrol:
   `up{job="site-monitor"}` pod başına bir seri.

### 1 → 2 geçişi (ve geri)

- **Kendi `helm upgrade`'i olarak:** önce yeni sürüm `replicaCount: 1` ile dağıtılır ve doğrulanır; sonra AYNI imajla,
  yalnız `environments/master.yaml`'da `replicaCount: 2` + `podDisruptionBudget.enabled: true` (`minAvailable: 1`) +
  P3'e göre `dbPoolMax` değiştirilerek ikinci bir commit / `helm upgrade`. Sürüm değişikliği ile replika artışı aynı
  upgrade'de yapılmaz (iki yeni sürüm pod'u aynı anda açılır). Spread (`topologySpreadConstraints`) yalnız ≥ 3
  zamanlanabilir node varsa, `matchLabelKeys: ["pod-template-hash"]` ile (Kubernetes ≥ 1.27).
- Bellek oturum deposuyla (`config.sessionStoreType` ≠ `jdbc`) birden çok pod `helm template`'te reddedilir.
- **Doğrulama:** Sistem Sağlığı → `session_store` = `jdbc`, `sweep_leader.owner` tek pod; Grafana
  `sum(sitemonitor_sweep_leader) == 1`; Metrikler sayfasında iki canlı pod; iki pod'un günlüğü de okunur (aşağıdaki
  kanaryalar); bir hafta her gün çift-iş dedektörü.
- **Geri (2 → 1):** aynı komut `replicaCount: 1` ve `podDisruptionBudget.enabled: false` ile (tek replikada
  `minAvailable: 1` bir PDB node drain'i süresiz bloke eder) ya da `helm rollback`. Veri / şema değişmez.

### Kanaryalar — her zaman tüm pod'lar

```bash
L="-n $NS -l app.kubernetes.io/instance=$REL --prefix --tail=-1"
kubectl logs $L | grep -c "decrypt failed"                                   # 0 olmalı
kubectl logs $L | grep -cE "Denetim izinde BOŞLUK|AUDIT fallback"            # 0 olmalı
kubectl logs $L | grep "Tarama liderliği" | tail -20                         # liderlik geçişleri / bırakma (pod adıyla)
kubectl get pod -n "$NS" -l app.kubernetes.io/instance="$REL" \
  -o custom-columns=POD:.metadata.name,NODE:.spec.nodeName,RESTART:.status.containerStatuses[0].restartCount,SEBEP:.status.containerStatuses[0].lastState.terminated.reason
```

### Çift iş / kaybolan bildirim denetimi

```bash
psql -h <db-host> -U <kullanıcı> -d <db> -X -f scripts/multipod-dup-check.sql     # salt okur, son 24 saat
PGHOST=… PGDATABASE=… PGUSER=… PGPASSWORD=… bash scripts/multipod-smoke.sh dup-check  # aynısı; > 0 ise çıkış 1
```
`DUP_TOTAL` aynı izlemenin aynı saniyelerde iki kontrolünü, aynı alarm bildiriminin aynı alıcıya 10 dk içinde iki kez
gitmesini, aynı push'un iki SENT satırını, kaynak başına birden çok ONGOING kesinti satırını, aynı hedef + tür için birden
çok açık alarmı, aynı kapsamda birden çok açık fırtınayı ve aynı alan adlarını kontrol eden iki sertifika taramasının
zamanda çakışmasını sayar; `STUCK_TOTAL` 25 dk'dan eski `QUEUED_RETRY` satırlarını. Tek pod'da da birkaç kontrol çifti
görülür (hatadan sonraki doğrulama kontrolü zamanlanmış kontrolle aynı saniyeye düşer) — iki lider ise HER izlemenin
HER kontrolünü ikiler; ayrıntıdaki `cift / toplam_satir` oranına bakın. Docker'lı iki pod duman testi (yeni imaj, staging
öncesi): `SMOKE_IMAGE=<imaj> bash scripts/multipod-smoke.sh`.

### Sürüm geçişi sırasında (eski + yeni pod birlikte)

Rollout'ta (`maxSurge: 1`) tek replikada da bir an eski ve yeni sürüm birlikte koşar; eski pod sürüm 1'in sahiplenmelerini
bilmez. Son eski pod gidene kadar şunlar görülebilir (sonra kendiliğinden biter):

- Eski pod yönetici "ağ kesintisi" / "düzeldi" postasını belleğinden gönderebilir (yinelenen yönetici postası; DB
  indeksi ikinci ONGOING satırı engeller, WARN).
- Eski (20.164.0) lider `monitor_eval_inbox`'u boşaltmaz ve kendini boşaltıcı olarak duyurmaz: yeni takipçiler
  `lease:eval-inbox-drainer` satırının şu anki tarama liderine ait olmadığını görür ve "Şimdi kontrol et"i eskisi gibi
  yerel değerlendirir (geçiş penceresi kalmaz).
- Eski pod fırtınayı eski tek aşamalı yolla kapatabilir: yinelenen bir kurtarma postası mümkün.
- Eski pod'da push kuyruğu, haftalık rapor geçişleri ve haftalık erişilebilirlik postası sahiplenmesizdir: çift tık /
  yarışta yinelenen push ya da posta mümkün (eski davranış).
- **Haftalık erişilebilirlik penceresinde dağıtmayın** (tetik günü, Pazartesi ~10:00–12:00 Istanbul) — bir 20.164.0 pod'u
  hâlâ koşabildiği sürece: eski pod bütün takımları TEK işlemde gönderir; gönderirken öldürülürse kayıtları geri sarılır, yeni
  sürümün telafi turu kaydı olmayan takımları gönderir → o takımlar ikinci posta alır.
- Eski saatlik tarama önce turu alır: kilidi yeni pod'un bayat taraması tutuyorsa o saat atlanır (bir sonraki saat tarar).
- Eski lider DNS / alan adı değişikliğini taban yapar ama PENDING yazmaz; yeni lider 2 sa (DNS) / 6 sa (alan adı) içinde
  yeni-sürüm satırlarını yeniden kuyruğa alır.

### Sürüm 2'ye kadar bilinen pod farkları (zararsız ama kafa karıştırır)

- Sistem Sağlığı'nın bazı kartları yanıtı veren pod'un belleğini gösterir: sertifika taraması "çalışıyor / son koşu",
  yürütücü / bellek / havuz kartları. "Tam kontrolü şimdi çalıştır" ilerlemesi diğer pod yanıt verince erken "bitti"
  diyebilir. Kesin bilgi: `scheduler_lock` `cert-check` satırı (tarama sürüyorsa var).
- Kalp atışı zaman çizelgesi pod sayısı kadar satır sayar ("alınan 2880 / beklenen 1440").
- Sertifika panosu önbelleği pod başınadır (5 dk): bir pod'da yapılan ekleme / düzenleme diğer pod'dan birkaç dakika
  eski görünebilir (silme hemen gizlenir).
- Hız sınırları ve eşzamanlılık izinleri (giriş IP sınırı, tanılama, PDF, test postası) pod başınadır: N pod = N kat.
- Çevrimiçi göstergesi sayfa yenilemesinde ≤ ~15 sn "çevrimdışı" görünebilir.
- Grafana "Okunamayan kaynaklar" paneli bir kaynak her pod'da okunamıyorsa pod sayısı kadar sayar (renk doğrudur).

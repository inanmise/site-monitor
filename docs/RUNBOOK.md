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
- **`kubectl rollout undo` kullanmayın** (çok pod Sürüm 2b): eski Helm revizyon numarasını geri getirir, o pod'lar eski
  ReplicaSet'in artığı sayılır — Sürüm & Dağıtım geçmişine ROLLBACK satırı / bildirim düşmez ve ortam sonraki Helm sürümüne
  dek yeni sürümü gösterir. `helm rollback` yeni revizyon açar; eski sürüm pod'u hâlâ ayaktayken bile ROLLBACK + bildirim üretir.
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
(`LOGIN_MAX_ATTEMPTS`=10 başarısız deneme → `LOGIN_BLOCK_SECONDS`=30 sn'den başlayan blok). Çok pod Sürüm 2a'dan
beri bu IP sayacı veritabanındadır (`rate_limit_counters`, bütün pod'larda ortak) — elle kaldırma:
[Takılı kayıtlar ve sınırlar](#takılı-kayıtlar-ve-sınırlar-çok-pod-sürüm-2a).

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

**Denetim zinciri yeniden başlatıldı (2026-10-23).** Zincirden önce yazılmış (seq'siz) satırları tutan veritabanında
2026-07-28'den beri her denetim satırı `seq = 1` aldı (PostgreSQL NULL'ları azalan sırada öne koyar); bu sürümden
sonra açılıştan ~10 dk sonra bir kez `AUDIT_CHAIN_RESTART` satırı yazılır, pod günlüğünde
`Denetim zinciri YENİDEN BAŞLATILDI` görünür ve "Bütünlüğü doğrula" sağlam zincirde "Zincir <tarih> tarihinden itibaren
doğrulandı — öncesindeki N kayıt onarımdan önce yazıldı, doğrulanamaz" der. Bu bir kurcalama DEĞİL, bilinen hatanın
izidir; eski satırlar yazıldığı gibi durur. Doğrulama hep EN ESKİ yeniden başlatma satırından başlar: ikinci bir
`AUDIT_CHAIN_RESTART` satırı meşru olarak yazılmaz — doğrulamada kırık görünürse elle eklenmiş demektir (olay kaydı açın).
Yeniden başlatma satırı yoksa doğrulama en eski kalan kayıttan başlar; saklama süresi (365 gün) en eski kayıtları
sildiyse şerit "daha eski kayıtlar saklama süresi dolduğu için silindi" der — kırık DEĞİLDİR. `ux_audit_seq` arka planda
YALNIZ bu indeks için kurulur (`grep "ux_audit_seq"`; eski sürüm bir pod son 10 dk'da zincirin bozuk bölümüne yazdıysa
hem açılış kurulumu hem iş bekler, iş bir saat sonra yeniden dener). Bu sürümden önceye GERİ DÖNÜLECEKSE önce
`DROP INDEX CONCURRENTLY IF EXISTS ux_audit_seq;` — yoksa eski kod ikinci denetim yazımından itibaren her kaydı fallback
dosyasına düşürür (`AUDIT DB yazımı BAŞARISIZ`).

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

Çok pod: kuruluş ölçümlerini yayan her pod aynı değeri yayar; kendi sorgularınızda `max by (<ölçümün etiketleri>)` ile
birleştirin (ör. `sum(max by (type, team, team_id, state) (sitemonitor_monitors{state="down"}))`). Pod'a özgü olanlar:
`sitemonitor_sweep_leader` (toplamı 1 olmalı), `sitemonitor_exporter_*`, `sitemonitor_database_status`,
`sitemonitor_push_circuit_open`; çok pod Sürüm 2b'den beri ayrıca `sitemonitor_cluster_lock_lost`,
`sitemonitor_sweep_leader_transitions{reason}`, `sitemonitor_push_lease_renew_failed{reason}`, `sitemonitor_clock_skew_seconds`
(küme geneli olan: `sitemonitor_eval_inbox_depth`). Sağlık bölümünün sonunda çok pod çalışma panelleri (gelen kutusu, pod
saatleri arası fark — `max(sitemonitor_clock_skew_seconds) - min(…)`, kaybedilen kilit, push kira hatası, küme olayları) var;
"Okunamayan kaynaklar" her kaynağı pod sayısından bağımsız bir kez sayar.

Çok pod Sürüm 3-1'den beri kuruluş ölçümlerini (izleme, alarm, sertifika, bildirim … aileleri) tarama lideri değil
TAKİPÇİ yayar: takipçi tam bir kuruluş turundan sonra `cluster_observations` satırı `metrics-org`'u yazar; lider o satırı
2,5 × yenileme aralığı içinde taze görürse yalnız kendi pod ölçülerini yayar (`sitemonitor_exporter_org_families` 0).
Takipçinin aktarıcısı takılır ya da okuyamazsa satır eskir ve lider kendisi yaymaya döner (boşluk yerine kısa örtüşme).
Kapanan pod (dağıtım, tahliye, ölçek küçültme) gözlem YAZMAZ; satır kendisininse yerine bir kapanış işareti yazar ve lider
10 sn içinde kurum ölçülerini yaymaya başlar (lider yalnız kendi pod ölçülerini yayarken satırı 10 sn'de bir denetler;
yayımlayan pod'un öldüğü biliniyorsa satır da yok sayılır). **Sürüm 3-1'den beri iş verisi için de POD BAŞINA kazıma
şarttır:** Service / statik hedef üzerinden yük dengeli kazıma lidere düştüğünde iş serisi bulamaz (seriler gidip gelir,
alarm kuralları kapanıp yeniden açılır); belirteç değişiminde de `kubectl rollout restart` tamamlanmadan pod'lar farklı
belirteç taşıyabilir — her pod'un `up{job=…}` serisi 1 olmalı. Gözlem satırı aktarıcının yaydığını kanıtlar, Prometheus'un
kazıdığını değil: kazımayı `up` ve "Veri tazeliği" şeridiyle denetleyin.
Tek pod, eski (20.167.0) komşu ya da okunamayan satır → lider eskisi gibi yayar. "Kurum ölçülerini yayan pod" paneli 0 ise
iş panelleri boş kalır: takipçinin `sitemonitor_exporter_*` ölçümlerine ve günlüğüne bakın. Yeni pod'a özgü ölçümler:
`sitemonitor_eval_state_mode`, `_pending`, `_write_failures`, `_verify_mismatches{kind}`, `_verify_timestamp_seconds`
(son ikisi yalnız `verify` kipinde YAZAN liderde — `write` kipine geçince son rapor da yayılmaz; bilinmeyen turda
uyuşmazlık serisi yazılmaz — 0 değil, "veri yok"), `sitemonitor_exporter_org_families`. Pano revizyonu 6 ("Veri tazeliği"
şeridi yalnız kurum ölçülerini yayan pod'ların son turunu sayar — iş panelleri boşken "Güncel" demez; sağlık bölümünün
sonunda kalıcı değerlendirme durumu ve kurum ölçüsü yerleşimi panelleri) — yeni JSON'u indirip aynı UID ile içe aktarın.

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
      - alert: SiteMonitorOrgMetricsMissing          # çok pod Sürüm 3-1: hiçbir pod kurum ölçülerini yaymıyor
        expr: sum(sitemonitor_exporter_org_families) == 0
        for: 10m
        labels: { severity: warning }
      - alert: SiteMonitorEvalStateMismatch          # yalnız ayna — karar etkilenmez; 3-2 ölçütünü bozar
        expr: sum(max by (kind) (sitemonitor_eval_state_verify_mismatches{kind=~"recovery|sibling|suppression|round_seen|confirmation"})) > 0
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
  OTLP ile gönderir). Zamanlanmış iş açılımını (`OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED`) KAPATMAYIN: ajanın JDBC
  açılımı ebeveyn istemez, tik span'ı olmadan tikin her SQL deyimi ayrı bir kök iz olur (daha çok iz). İz hacmini
  Instrumentation kaynağının örnekleyicisiyle azaltın. Ek açıklamayı
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
| Push kuyruğu | ≥ 10 push 10 dk'dan uzun gecikmede ya da son 30 dk'da ≥ 3 sonuçtan ≥ %50'si FAILED (ölü ağ geçidi; test gönderimleri sayılmaz) — devre kesicinin açık olması tek başına sorun değil, bilgi olarak gösterilir | Push ağ geçidi, Webhook Push Gönderim Logu |
| Veritabanı (DOWN kritik) | Bağlantı / sorgu / yazılabilirlik DEGRADED/DOWN, ya da HERHANGİ bir canlı pod'un havuzu dolu ya da açılış şema yamalarında hatası var (son sistem metriği örneği; örnek yoksa değerlendiren pod'un kendi havuzu / yaması) | [DB dolması](#db-dolması), `/api/public/health/db`, Sistem Sağlığı → Metrikler (pod başına havuz), `Şema yamaları:` günlük satırı |
| Saat uyumu (2026-10-23) | Canlı pod'ların saatleri ARASINDAKİ fark (en ileri − en geri) > 5 sn (uyarı) / > 30 sn (ciddi); veritabanı saatinin farkı yalnız bilgidir | Düğümün NTP / chrony durumu (ön koşul P2); `system_metric_samples.clock_skew_ms` |

Bildirimler `notification_logs`'ta tetik `WATCHDOG` (test `WATCHDOG_TEST`). Hiçbir kanaldan gidemeyen bildirim 15 dk'da
bir yeniden denenir; sistem bakımı "bildirimler sussun" açıkken gönderilmez (iz `SKIPPED: sistem bakımı`). Veritabanı
çökükken yalnız veritabanı kontrolü pod belleğinde değerlendirilir ve e-posta / webhook / push yine gider. Birden çok pod
varken bu "veritabanı çöktü" uyarısını yalnız son doğrulanmış lider gönderir (her pod ayrı ayrı göndermez); takipçi ancak
son okuduğu kira yenilenmemiş görünüyorsa ve üstünden bir kira süresi + doğrulama süresi geçmişse duyurur. Düzelme
bildirimi (RECOVER) önce satıra damgalanır — tek pod gönderir. Değerlendirme kilidi `watchdog` 2 dk TTL ile yenilenir.

Çok pod Sürüm 2b'den beri kontrollerin durumu yalnız küme genelindeki gerçeklerle değişir (değerlendiren pod'un belleğiyle
değil — turu hangi pod alırsa alsın sonuç aynı). Saat uyumu kontrolü pod başına saat sapmasını (ortancaya göre) gösterir ve
pod'lar arasındaki en büyük farka göre karar verir (kira ve kilitler pod saatlerini karşılaştırır); 5 sn üstü uyarı, 30 sn üstü
ciddi — düğümün NTP / chrony durumunu düzeltin (P2). Veritabanı saatinin pod'lardan farkı (`db_offset_ms`) yalnız bilgidir. Push ve veritabanı kontrollerinde "Değerlendiren pod"
satırı yalnız bilgidir. Sistem metrikleri örneklemesi kapalıysa (`site.monitor.metrics.system.enabled=false`) saat uyumu
"uygulanmaz" görünür.

## Çok pod

> Çok pod sürüm 1 (2026-10-21). Aynı imaj N replikada koşar; kullanıcı istekleri bütün pod'lara dağılır, **izleme işi
> tek pod'da (lider) koşar**. İkinci pod yüksek erişilebilirlik ve arayüz / API kapasitesi getirir, izleme kapasitesi
> getirmez — izleme yükü büyürse önce dikey ölçekleyin (CPU / bellek / `dbPoolMax`). HPA kapalı kalır.
>
> Çok pod Sürüm 2a (2026-10-23): pod'lar arası önbellek yolu (bir pod'daki yazma öteki pod'ların önbelleğini ~2 sn
> içinde boşaltır), küme geneli güvenlik sınırları (giriş IP sınırı, k6 gizli değer gösterimi, oturumsuz giriş yardımı /
> hata bildirimi) ve rapor / hatırlatma gönderim yollarının sahiplenmesi (yarıda kalan gönderim izlerden kapanır).
>
> Çok pod Sürüm 2b (2026-10-23): Sistem Sağlığı kümeyi gösterir (Küme kartı, "Küme" / "Bu pod: <ad>" etiketleri, pod'lar
> kartı), kendi kendini izleme küme genelindeki gerçeklerle karar verir (+ saat uyumu), yuvarlanan dağıtımda boş sayfa
> olmaz (son 5 sürümün sayfa dosyaları imajda, yüklenemeyen paket bir kez yenilenir), alarm kapanışları ve alarm
> yazımları bayat varlığı bütünüyle kaydetmez, sürüm geçişi bildirimi kümede bir kez gider.
>
> Çok pod Sürüm 3-1 (2026-10-23, Sürüm 3'ün ilk aşaması): uzun yan işler (sertifika taramaları, gece temizliği, raporlar,
> TLS profili …) tarama liderinden alınır — lider, başka canlı pod varken cron yan işlerini 20 sn erteler ve tur tabanlı
> ağır işleri takipçiye bırakır; uzun işler her pod'da ayrı bir yan iş koşucusunda koşar; kurum geneli Grafana ölçülerini
> takipçi yayar. Lider alarm değerlendirmesinin bellekteki durumunu dört tabloya AYNALAR ve 5 dakikada bir bellekle
> karşılaştırır — 3-1'de hiçbir karar bu tablolardan okunmaz; bu, Sürüm 3-2'nin (devirde durumu taşımak) kanıtıdır.
> İzleme kontrolleri Sürüm 3-3'e kadar yine liderde koşar.
>
> Chart HPA şablonu 2026-10-23'te düzeltildi (HPA açıkken Deployment `replicas` taşımaz, bellek metriği varsayılan yok,
> `behavior` var) ama HPA her ortamda KAPALI kalır — izleme işi lider pod'da, ek pod izleme kapasitesi eklemez. Açılırsa
> ilk `helm upgrade` Deployment'tan `replicas`'ı kaldırır ve Kubernetes HPA'nın ilk eşitlemesine kadar (~15 sn) 1
> replikaya düşer: mesai dışında ve `minReplicas ≥ 2` ile.

### Kim neyi koşar

| İş | Nerede | Çift koşmayı ne önler |
|---|---|---|
| Dokuz izleme türünün taramaları, envanter erişilebilirlik (HTTP) taraması, doğrulama / kurtarma yeniden kontrolleri, DNS ve alan adı değişiklik tespiti | **Lider** pod | Tarama liderliği kirası (`monitor_check_schedule`, anahtar `lease:sweep-leader`, 180 sn; ayrı iş parçacığında 60 sn'de bir yenilenir) + izleme başına küme vadesi + kirasını kaybetmiş pod'un zamanlanmış sonuçları alarm üretmez |
| Takipçide basılan "Şimdi kontrol et" sonucunun alarm değerlendirmesi (`monitor_eval_inbox` üzerinden lidere iletilir, ≤ 5 sn); veritabanı çökükken kendi-kendini-izleme uyarısı | Lider | Kira |
| Sertifika taramaları (saatlik, bayat, tam), zamanlanmış raporlar / hatırlatmalar / özetler, gece temizliği, push kuyruğu, fırtına yaşam döngüsü, eskalasyon adımları, kendi-kendini-izleme turu, yetim QUEUED_RETRY / çözüm bildirimi süpürücüleri (kanal teknik hata raporu kuyruklarını da sonuçlandırır); Sürüm 2a'dan beri kapatılan türlerin alarm uzlaştırması (`disabled-types-reconcile`), elle saklama temizliği (gece temizliğinin `nightly-cleanup` kilidiyle) ve tek seferlik izleme grubu takım doldurması; Sürüm 3-1'den beri TLS profili işi (kilit `tls-profile` + tur `round:tls-profile`, 10 dk) | Kilidi ya da turu ilk alan **herhangi** bir pod — Sürüm 3-1'den beri başka canlı pod varken tarama lideri uzun / cron yan işlerini ona bırakır ([Yan işler](#yan-işler-ve-lider-ertelemesi-çok-pod-sürüm-3-1)) | `scheduler_lock` satırı (uzun iş sürdükçe yenilenir) + küme turu (`round:<ad>`, bir dönemde bir kez) + satır başına koşullu sahiplenme (aynı push / posta iki kez gitmez) |
| Kullanıcı istekleri, "Şimdi kontrol et", tanılama, dışa aktarım | İsteği alan pod | Oturumlar PostgreSQL'de (`spring_session`) — her pod her oturumu tanır |

### Lider kim?

- **Sistem Sağlığı → Zamanlayıcı ve yürütücüler → Küme kartı:** "Tarama lideri" bölümü KÜMENİN kira satırıdır — hangi pod
  yanıtlarsa yanıtlasın aynı: lider pod adı ve kira bitişi ("süresi doldu" / "Lider yok" = kira boşta; ≈ 35 sn'lik devir
  dışında sürüyorsa izleme taramaları koşmuyor). "Yanıtlayan pod: <ad>" bölümü sayfayı O AN yanıtlayan pod'undur: "Lider mi",
  son tarama tıkı, tıkanma, tuttuğu kilitler, tur kaydı hataları, atlanan turlar, önbellek yolu. Sayfayı birkaç kez yenileyin:
  lider pod adı hep AYNI kalmalı; "Lider mi: Evet" yalnız lider pod yanıtladığında görünür. Ham değer: `GET /api/admin/system`
  → `scheduler.sweep_leader.owner` (`<pod adı>-<8 hane>`) / `owner_pod`, `held_by_me`.
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

### Yan işler ve lider ertelemesi (çok pod Sürüm 3-1)

Sürüm 3-1'den beri tarama lideri izlemeye ayrılır; uzun "her pod" işleri başka canlı pod'a kayar:

- **Başka canlı pod** = son 3 dk içinde heartbeat yazmış, kapanış işareti olmayan, adı dolu (`instance_id`) başka bir pod
  (Sistem Sağlığı → Heartbeat → "Pod'lar" kartında "canlı"). Karar pod başına 30 sn önbelleklenir; okunamazsa "yok" sayılır
  — lider o zaman hiçbir şeyi bırakmaz (tek pod davranışı). 20.166.0 pod'ları (adsız heartbeat) sayılmaz; bu pod'un kirli
  yeniden başlatmadan (OOM, SIGKILL — kapanış işareti yazılamadı) önceki süreci de (aynı pod adı, başka kimlik) sayılmaz.
- **Cron yan işleri** (saatlik sertifika taraması, gece temizliği, alan adı süre yenilemesi, CA sabitleme yenilemesi, haftalık
  erişilebilirlik + telafisi, Cuma haftalık rapor hatırlatması, sorun bildirimi özeti, envanter raporu, aylık yönetici özeti,
  olay aksiyonu ve kanal raporu hatırlatmaları, veri kalitesi anlık görüntüsü): lider tetiği 20 sn erteler
  (`site.monitor.scheduler.leader-yield.cron-defer-ms`, açılışta okunur, en çok 25 sn). O sürede takipçi kilidi + turu aldıysa
  lider hiçbir şey yapmaz; almadıysa (ölü, takılı, kapanıyor) lider AYNI işi 20 sn geç kendisi koşar — cron payı 60 sn,
  tetik kaybolmaz, iş iki kez koşmaz (kilit, tur ve kayıt başına sahiplenme aynen). Günlükte (saatte bir):
  `Tarama lideri '<iş>' tetiğini 20 sn erteledi`.
- **Tur tabanlı ağır işler** (bayat sertifika taraması 5 dk, TLS profili 10 dk, yönetici özeti telafisi 55 dk): turu son 2
  dönemde BAŞKA bir pod BAŞARIYLA TAMAMLADIYSA lider bırakır (`Tarama lideri '<tur>' turunu bıraktı — … tamamladı`; kanıt
  `round:<tur>:done` satırı: turu son bitiren pod ve an). Yalnız sahiplenip her turda hata veren takipçi kanıt değildir;
  bu pod'un kirli yeniden başlatmadan önceki süreci (aynı pod adı) ne canlı pod ne kanıt sayılır. Kanıt yoksa (takipçi yeni
  açıldı, takıldı, her turda hata veriyor, eski sürüm) lider son tamamlanmadan en geç 2 dönem sonra kendisi alır. Tek
  seferlik ping toparlaması: lider en çok 2 saat üst üste bırakır, sonra koşar.
- **Yan iş koşucusu:** uzun işler (saatlik / bayat sertifika taraması, gece temizliği, ping toparlaması, alan adı süre
  yenilemesi, haftalık erişilebilirlik + telafisi, TLS profili, envanter raporu, yönetici özeti + telafisi, veri kalitesi)
  8 iş parçacıklı zamanlayıcı havuzunu artık tutmaz; her pod'da ayrı bir koşucuda koşar. Uzun iş şeridi en az 8 iş
  parçacığıdır (`site.monitor.scheduler.side-jobs.threads`, varsayılan 8, env `SCHEDULER_SIDE_JOBS_THREADS`, açılışta
  okunur; daha küçük değer 8'e, zamanlayıcı havuzu `SCHEDULING_POOL_SIZE` daha büyükse ona yükseltilir, en çok 32; 0 = eskisi
  gibi zamanlayıcı iş parçacığında): bir uzun iş ancak aynı pod'da 8 başka uzun iş koşarken bekler (20.167.0'ın 8 iş
  parçacıklı havuzu o durumda zaten doluydu). Liderin ertelediği kısa cron denemeleri ayrı, 5 iş parçacıklı kısa şeritte
  koşar — uzun işlerin arkasında beklemez. Sabit gecikmeli işler (bayat tarama 5 dk, TLS profili 10 dk, ping toparlaması
  1 sa) bu pod'daki son koşunun BİTİŞİNDEN gecikme kadar sonra başlar (arada "kuyrukta" görünür — normal) — tek pod'da
  kadans 20.167.0 ile aynı. Aynı iş, öncekisi sürerken yeniden tetiklenirse atlanır. Kısa işler (CA sabitleme, Cuma
  hatırlatması, sorun özeti, olay / kanal raporu hatırlatmaları) havuzda kalır, yalnız ertelenir; olay aksiyonu hatırlatması
  da artık tetik başına küme turu alır (`round:incident-action-reminder`): sistem bakımı susturmasında sahip başına günde
  TEK "SKIPPED: sistem bakımı" satırı. Hafif kilit işleri (fırtına, eskalasyon adımı, sistem bakımı, sessiz saat özeti,
  süpürücüler, push, kendi-kendini-izleme turu) ve izleme taramaları DEĞİŞMEDİ. Kapanışta TLS profili turu ~200 ms içinde
  durur (biten yoklamalar kaydedilir, tur tamamlanmaz — sonraki tur sürdürür).
- **Durum:** Sistem Sağlığı → Zamanlayıcı ve yürütücüler → Küme kartı → "Yanıtlayan pod" → **Yan işler** (YANITLAYAN
  pod'un koşucusu): koşan / kuyruktaki işler, açılıştan beri ertelenen / başka pod'a bırakılan sayısı, lider ertelemesi açık /
  kapalı, başka canlı pod var / yok (takipçide "yalnız tarama lideri bakar"; liderde önbellek boşken "son bakışta …
  (N dk önce)" ya da "henüz bakılmadı"), son koşusu hatayla biten ya da kapanışta kesilen işler. Gösterilen iş parçacığı
  sayısı uzun iş şerididir (kısa şerit gösterilmez). Lider pod'da "ertelendi / başka pod'a bırakıldı" sayaçları artar;
  takipçide işler koşar. Kümenin tamamı (turun son sahiplenen pod'u; `scripts/multipod-dup-check.sql` bölüm 0b aynısını
  yazar):
  ```sql
  SELECT monitor_key, claimed_by, to_timestamp(claimed_at / 1000.0) AS sahiplenme
    FROM monitor_check_schedule WHERE monitor_key LIKE 'round:%' ORDER BY monitor_key;
  ```
  `round:<tur>:done` satırları turu SON BAŞARIYLA bitiren pod'u ve anı gösterir (bayat sertifika, TLS profili, yönetici
  özeti telafisi); bu satırlarda `next_due_at` vade değil, bitiş anıdır.
- **Yük yine lidere yığılıyorsa:** takipçinin heartbeat'i yazılıyor mu ("Pod'lar" kartında "canlı"), takipçinin günlüğünde
  `Yan iş '<iş>' başarısız` var mı (`kubectl logs $L | grep "Yan iş"`, [Kanaryalar](#kanaryalar--her-zaman-tüm-podlar)).
  Takipçi 20.167.0 ise de sorun yok: eski pod tetikte işi alır, lider ertelediği denemede turu alınmış bulur.
- **Kapatmak (20.167.0 yerleşimi):** Ayarlar → Genel → Zamanlayıcı → "Yan işleri tarama liderinden uzaklaştır (çok pod)"
  (`site.monitor.scheduler.leader-yield.enabled`) = kapalı — yalnız GLOBAL admin; canlı, ~10 sn içinde bütün pod'larda. Lider
  artık ertelemez / bırakmaz (her pod tetikte yarışır, kilidi kim alırsa); uzun işler yine yan iş koşucusunda koşar. Kalıcı
  varsayılan env ile: `SCHEDULER_LEADER_YIELD_ENABLED=false` (Ayarlar'da kaydedilen değer env'i ezer).
- **Çıkış eşitliği:** TLS profili yoklaması artık herhangi bir pod'dan çıkar — [P1](#ön-koşullar--1--n-geçişinden-önce)
  (her node'dan aynı çıkış) sertifika taramalarıyla aynı hedefler üzerinden onu da kapsar; P1 betiği her TLS hedefine
  doğrudan bir TLS el sıkışması da dener ve sertifikayı vereni yazar (`TLS_TARGETS`).

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
lider 2 saate kadar yeniden kuyruğa alır, değişiklik bildirimi kaybolmaz. Sürüm 3-1'de durum tablolara
yalnız AYNALANIR ([Kalıcı değerlendirme durumu](#kalıcı-değerlendirme-durumu-çok-pod-sürüm-3-1)); Sürüm 3-2'den
beri "Devirde kalıcı değerlendirme durumunu yükle" ayarı AÇIKSA yeni lider temiz devirde kurtarma sayaçlarını, DOWN
kardeşleri, bastırmayı ve tür turlarını, çökmeden sonra yalnız gözlemleri yükler; süren doğrulama zincirleri yine baştan
başlar ([Devirde okuma](#kalıcı-değerlendirme-durumunu-devirde-okuma-çok-pod-sürüm-3-2)). Ayar kapalıyken (varsayılan)
her şey yukarıdaki gibidir.

### Kalıcı değerlendirme durumu (çok pod Sürüm 3-1)

Tarama lideri alarm değerlendirmesinin bellekteki durumunu (kurtarma sayaçları, izleme başına son sonuç, toplu kesinti
bastırması, ilk zamanlanmış tur, süren teyit zincirleri) dört tabloya aynalar: `monitor_eval_state`, `monitor_last_status`,
`monitor_eval_type_state`, `monitor_confirmations` (adlar kalıcı). **3-1'de hiçbir karar bu tablolardan okunmaz** —
kayıt kapalıyken, yazamazken ya da tablolar yokken de her alarm / bildirim kararı aynıdır. Amaç Sürüm 3-2 için kanıt
toplamak: devirde durumu tablodan yüklemek güvenli mi.

- **Kim yazar:** yalnız tarama lideri, veritabanından aldığı bir **kuşak** numarasıyla (saatle değil). Yeni lider yeni kuşak
  alır — kuşak yalnız tarama lideri KİRASINI tutan pod'a verilir (kira denetimi aynı SQL ifadesinde), kirayı kaybetmiş eski
  bir lider kuşak alamaz. Eski lider bir sonraki yazımında daha yeni kuşağı görür ve yazmayı bırakır (iki lider aynı satırı
  ezmez). Lider bir şekilde çitlenmiş kalırsa (ör. kirayı kısa süre tutup kapanan bir pod kuşağı aldıysa), `'*'` satırının
  sahibi 3 dakika nabız atmadığında kendini onarır ve yeni kuşak alır (INFO `… çitten çıkıp yeni kuşak alacak`). `'*'`
  satırı elle silinirse var olan en büyük kuşağın üstünden yeniden başlar. Ham değer:
  ```sql
  SELECT gen AS kusak, writer AS yazan, to_timestamp(updated_ms / 1000.0) AS son_nabiz
    FROM monitor_eval_type_state WHERE alert_type = '*';      -- yazan = lider pod, son_nabiz en çok ~60 sn eski
  ```
  Bir 20.167.0 lider yazmaz: o sırada `son_nabiz` eskir (3-2'nin süreklilik denetimi tam buna bakar — aşağıdaki
  [Devirde okuma](#kalıcı-değerlendirme-durumunu-devirde-okuma-çok-pod-sürüm-3-2) bölümünün teşhis sorgusu).
- **Küme kartı** (Sistem Sağlığı → Zamanlayıcı ve yürütücüler → Küme kartı):
  - **"Kalıcı değerlendirme durumu" — "Küme" etiketli:** kümenin SON doğrulaması (lider 5 dakikada bir belleği tablolarla
    karşılaştırır; sonuç `cluster_observations` satırı `eval-state-verify` — hangi pod yanıtlarsa aynı). Yalnız TAZE
    "bellek ve tablolar uyuşuyor" yeşildir; "bilinmiyor", "eski" (3 turdan / 15 dk'dan eski), "henüz doğrulama yok",
    "kapalı" gridir — bilinmeyen tur asla "0 uyuşmazlık" sayılmaz. Satırlar: doğrulayan pod, kuşak, tür başına uyuşmazlık /
    karşılaştırılan / atlanan (kurtarma sayaçları, kardeş izlemelerin son sonucu, toplu kesinti bastırması, ilk zamanlanmış
    tur, süren teyit zincirleri; "sürdürülemeyecek zincir" ve "bastırma penceresi tohumu" ÖLÇÜMDÜR — kartı kırmızıya
    çekmez), ilk uyuşmaz anahtarlar (yalnız özet — düz anahtar / alan adı yok). Bilinmeyen turun nedeni yazılır:
    "eşitleniyor — sonraki tur karşılaştırır" (kuşağın ilk turu ve 15 dk'dan uzun bir aradan sonraki tur — devirden ya da
    `off` kipinden dönüşten hemen sonra NORMAL), "yazma kuşağı henüz alınmadı", "yazıcı yetişemedi", "kalıcı tablolar
    okunamadı", "bellek görüntüsü alınamadı", "doğrulayıcı beklenmedik bir hatayla durdu", "tur sonucu kaydedilemeyecek
    kadar büyüktü", "tur sırasında tarama liderliği değişti" (kuşak bırakıldı ya da çitlendi — bu tur yayımlanmaz, sonraki
    tur yeniden doğrular), "karşılaştırılacak kayıt yoktu (hepsi atlandı)" (her anahtar tur sırasında yeniden yazıldı ya da
    kirli — sonraki tur yeniden dener). "Hâlâ izleniyor mu" sorgusu hata verirse o anahtar atlanır, uyuşmazlık sayılmaz.
    Taze bir uyuşmazlık kartı uyarıya çeker; liderin günlüğünde turda bir WARN
    `Kalıcı değerlendirme doğrulaması: N uyuşmazlık — …` (karar etkilenmez).
  - **"Yanıtlayan pod" → "Değerlendirme kaydı":** YANITLAYAN pod'un kaydedicisi — kip, "yazıyor (kuşak N)" / "yazmıyor"
    (takipçide "yazmıyor" normaldir), bekleyen yazım, yazma hatası, sınır yüzünden düşen işlem, son yazım, "son yazım
    başarısız — kararlar etkilenmez, yeniden deneniyor" uyarısı (yalnız YAZAN kaydedicide; liderliği kaybeden pod'un eski
    hatası uyarı değildir), çitlenme uyarısı (bu pod lider ama yazma kuşağını başka bir pod almış — yazmıyor; o kuşağın
    sahibi 3 dk sessiz kalınca kendiliğinden devralır; yalnız lider pod yanıtlarken gösterilir — 3 dk'dan uzun sürerse kuşak
    satırını yukarıdaki SQL ile okuyun: `yazan` hangi pod, `son_nabiz` taze mi), kanca hatası sayısı (açılıştan beri; soluk
    satır — ayna eksik kalmış olabilir, kararlar etkilenmez), "tablolar henüz yok" ve **devralınan** satırlar (bu pod lider
    olunca YALNIZ bir önceki kuşaktan sayılan kurtarma sayacı, DOWN kardeş, bastırma, terk edilen zincir — karara GİRMEZ;
    3-2'nin devirde taşıyacağı durumun kanıtı). Lideri görmek için sayfayı lider pod yanıtlayana dek yenileyin ("Lider mi:
    Evet"). Ham değer: `GET /api/admin/system` → `data.eval_state.this_pod` (`flushes`, `write_failures`, `fenced`,
    `hook_failures`, `inherited` …) ve `data.eval_state.verify`.
- **Grafana** (sağlık bölümünün sonu, pano revizyonu 6): "Değerlendirme durumu kipi", "Doğrulama uyuşmazlığı" (0 olmalı;
  "veri yok" = tur bilinmiyor ya da doğrulama kapalı, 0 değil), "Son doğrulama" (≤ 15 dk), "Bekleyen durum yazımı",
  "Durum yazma hatası (24 sa)", "Kurum ölçülerini yayan pod", "Doğrulama uyuşmazlığı (tür)" zaman serisi.
- **Günlük:** `Kalıcı değerlendirme durumu yazılamadı — karar etkilenmez (bellek kaynak)` (en çok dakikada bir WARN) yalnız
  ayna yazımının aksadığını söyler; yazımlar 1 → 30 sn geri çekilerek yeniden denenir. Kuşak alımı (`kuşak N alındı` +
  devralınan sayılar — yalnız bir önceki kuşağın satırları), daha yeni lider yüzünden durma, kuşak sahibi sessiz kalınca
  kendini onarma ve budama INFO'dur; tanınmayan bir kip değeri bir kez WARN `Kalıcı değerlendirme durumu kipi tanınmadı`.
  Tüm pod'lar:
  `kubectl logs $L | grep "Kalıcı değerlendirme"`.
- **Kapatmak:** Ayarlar → Genel → İzleme Alarmları → "Kalıcı değerlendirme durumu (çok pod)"
  (`site.monitor.eval-state.mode`) — yalnız GLOBAL admin, canlı (~10 sn): `off` = hiç kayıt ve yazım yok (veritabanı yazım
  yükü sorun olursa acil kapatma; karar etkilenmez), `write` = yalnız yazım, `verify` (varsayılan) = yazım + 5 dk'da bir
  doğrulama. `off`'a geçince kayıt kuşağı bırakılır; dönüşte yeni kuşak alınır ve ilk doğrulama turu "eşitleniyor" olur
  (eski kuşağın satırları karşılaştırılmaz). Tanınmayan bir değer (yazım hatası) `verify` gibi çalışır ve günlüğe bir WARN
  yazar. Kalıcı varsayılan env ile: `EVAL_STATE_MODE`. Yazım yükü (tahmin): kararlı durumda kabaca (izleme × alarm türü
  kalemi) ÷ 2 upsert / dk (`monitor_last_status`, 120 sn tazeleme) + boşaltma başına bir `'*'` nabzı; bugünkü ~700 izlemede
  günde 0,5 GB WAL'in çok altında, 3 kat ölçekte (≈ 12 bin kalem) ≈ 100 upsert / sn ve checkpoint sayfa görüntüleriyle
  günde 3–5 GB WAL. Sık güncellenen üç tabloda güncellemeler HOT kalır (fillfactor 70). İfadelerin 15 sn sorgu zaman aşımı
  vardır — kilitli bir satır yazıcıyı en çok 15 sn bekletir.
- **3-2'ye geçiş ölçütü = devirde okuma ayarını AÇMA ölçütü** ([Devirde okuma](#kalıcı-değerlendirme-durumunu-devirde-okuma-çok-pod-sürüm-3-2)):
  prod'da 2 pod'la en az 7 gün `verify` kipinde:
  1. Her BİLİNEN doğrulama turunda kurtarma, kardeş, bastırma, ilk tur ve teyit zinciri uyuşmazlıkları 0 ("0 uyuşmazlık" =
     bu beş türün toplamı; bilinmeyen turlar — "eşitleniyor", "yazıcı yetişemedi" … — sayılmaz, ama 7 gün boyunca
     bilinen tur olmalı).
  2. Yazma hataları boşaltmaların %0,1'inden az (lider pod: `data.eval_state.this_pod.write_failures` / `flushes`;
     Grafana "Durum yazma hatası (24 sa)").
  3. "Sürdürülemeyecek zincir" yalnız silinmiş izlemelerde; "bastırma penceresi tohumu" turların %1'inden azında kararı
     değiştirir.
  4. En az bir GERÇEK liderlik değişimi görüldü ve yeni liderin "devralınan" sayılarından biri sıfırdan büyük.

  Nerede görülür: Küme kartının "Kalıcı değerlendirme durumu" bölümü (son tur), Grafana "Doğrulama uyuşmazlığı (tür)"
  zaman serisi (7 günlük aralıkla — geçmiş turlar) ve liderin günlüğündeki `Kalıcı değerlendirme doğrulaması` WARN'ları
  (hiç olmamalı).
- **Geri alma:** tablolar kalır, eski kod kullanmaz (dış anahtar / tetikleyici yok). Tablolar küçüktür (izleme × alarm
  türü); kaydedici kendi budar (son durum 7 gün — duraklatılan / silinen izlemenin DOWN satırı bellek onu budadığı anda;
  kurtarma satırı 7 gün: sıfır olanlar ve önceki kuşaklarınkiler; önceki kuşağın tür satırları 7 gün; zincir 1 saat).

### Kalıcı değerlendirme durumunu devirde okuma (çok pod Sürüm 3-2)

Ayar: Ayarlar → Genel → İzleme Alarmları → **"Devirde kalıcı değerlendirme durumunu yükle (çok pod)"**
(`site.monitor.eval-state.read-enabled`) — yalnız GLOBAL admin, canlı (~10 sn içinde tüm pod'lar), varsayılan **kapalı**,
kalıcı varsayılan env ile `EVAL_STATE_READ_ENABLED`. **Kapalıyken her şey 20.168.1 ile birebir aynıdır** (alarm kararları,
bildirimler, kaydedicinin veritabanı ifadeleri, kapanış sırası ve süresi, teyit listesi, günlük satırları); Sistem Sağlığı
yalnız "Kapalı" der.

- **Ne yapar:** tarama liderliği başka pod'a geçince yeni lider, öncekinin Sürüm 3-1'den beri tablolara aynaladığı alarm
  değerlendirme durumunu yükler:
  - **Tam (temiz devir — zarif kapanış: rollout, `kubectl delete pod`, drain):** kurtarma sayaçları (ardışık sağlıklı
    sonuç sayısı), DOWN kardeş izlemeler, toplu kesinti bastırması, tür turları (yeniden başlatmadan sonraki elle kapanış
    korumasının rahatlaması) ve bastırma penceresi (her tür kendi pencere süresiyle — doğrulamanın "bastırma penceresi
    tohumu" kuralının aynısı). Eski lider kirayı bırakmadan hemen önce (en çok 1 sn: süren değerlendirmeler ve teyit /
    kurtarma zincirlerinin yeniden kontrolleri ≤ 300 ms durulur, sonra ≤ 700 ms boşaltma) bekleyen her yazımı yapar ve bir
    **devir işareti** bırakır. İşaret yazıldıktan sonra o pod kira bırakılana dek tabloya hiçbir şey yazmaz (işareti
    silemez).
  - **Yalnız gözlemler (çökme, `OOMKilled`, SIGKILL, node kaybı, kira süresi doldu):** yalnız DOWN kardeşler ve bastırma
    penceresi — sayaçlar ve bastırma çökmeden önceki son saniyelerde bayatlamış olabilir (erken kapanış riski), taşınmaz.
  - **Yüklenmedi:** diğer her durumda bugünkü gibi sıfırdan (yeniden başlatma semantiği).

  Süren teyit zincirleri her durumda yeniden başlar. Takipçi pod'lar Sayfa izlemesi ayrıntısındaki "Teyit denemesi X/N"
  listesini tablodan gösterir (`monitor_confirmations`; ayar kapalıyken takipçide boş — bugünkü gibi). Kazanç: dağıtımda
  alarm sayaçları kaybolmaz (3 ardışık sağlıklı sonuç isteyen bir alarmın 2'si eski liderde sayıldıysa yeni liderin ilk
  sağlıklı sonucu kapatır), paylaşılan alarm DOWN kardeşi varken erken kapanmaz, toplu kesinti bastırması devirde gidip
  gelmez.
- **Açma ölçütü:** yukarıdaki "3-2'ye geçiş ölçütü" — iki prod pod'unda ≥ 7 gün: Küme kartı doğrulamasında kurtarma,
  kardeş, bastırma, ilk tur ve teyit zinciri uyuşmazlığı 0 (bilinmeyen turlar hariç), yazma hatası < %0,1, "sürdürülemeyecek
  zincir" 0 (silinmiş izlemeler hariç), "bastırma penceresi tohumu" karar farkı < %1, en az bir gerçek liderlik değişiminde
  devralınan sayılar > 0. Kip `verify` kalmalı (kip `off` iken ayar etkisizdir — kart "Kayıt kipi kapalı" der).
- **Açmak:** önce staging, sonra prod; ilk prod dağıtımında aynı kontroller. Sürüm yükseltmesiyle AYNI adımda açmayın.
  **Ayarı ancak TÜM pod'lar 3-2 (ya da üstü) sürümünü çalıştırırken açın** (R-6): 20.168.x bir takipçi, devir aralığında
  gelen bir "Şimdi kontrol et" sonucunu kendi boş belleğinde değerlendirir — DOWN sonucunun sayaç sıfırlaması yeni lidere
  ulaşmaz, yükleyici bunu göremez. `kubectl get pods -n "$NS" -l app.kubernetes.io/instance="$REL" -o
  jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[0].image}{"\n"}{end}'` hepsinde aynı imajı göstermeli.
  1. Ayarı açın. Lider pod yanıtlarken (Küme kartı "Lider mi: Evet"; sayfayı yenileyin) "Yanıtlayan pod" bölümünde
     **"Devir hazırlığı: Hazır — tam devir mümkün"** (birkaç saniye "Hazır değil: eşitleniyor" olabilir; takipçide
     "Hazır değil: bu pod tarama lideri değil — devri yalnız lider yapar" normaldir). Diğer "Hazır değil" nedenleri: pod
     kapanıyor; durum tabloları henüz yok; devir işareti sütunları yok (ilk açılış ya da şema yaması başarısız — sonraki
     açılışta yeniden denenir; `SchemaPatch` günlüğüne bakın); yazma kuşağı başka pod'da (çit); kuşak yok.
  2. `kubectl rollout restart deploy/<ad>` (ya da lider pod'u silin). Yeni lider yanıtlarken Küme kartı →
     **"Devirde yüklenen durum: Tam (temiz devir)"**, "Önceki lider: <eski lider> · … önce yazılmıştı" (yüklenen durumun
     yaşı), sayılar ("N kurtarma sayacı · N DOWN kardeş · …") "Değerlendirme kaydı" → devralınan satırlarıyla uyumlu;
     sonraki doğrulama turu "eşitleniyor", ondan sonraki "bellek ve tablolar uyuşuyor". Kart **"Güncel değil"** (gri)
     derse kümenin son yükleme raporu şimdiki liderden başka bir pod'un raporudur: yeni lider bir yükleme yayımlamadı
     (liderliği aldığı anda ayar ya da kayıt kipi kapalıydı, ya da eski sürüm lider) — kendi başına hata değildir; lider pod
     yanıtlarken "Bu pod" etiketli satıra bakın.
  3. Günlük (tüm pod'lar):
     ```bash
     kubectl logs $L | grep -E "devirde yüklen|devirde okunamadı|Devir öncesi kalıcı durum"
     ```
     Eski liderde `Devir öncesi kalıcı durum boşaltıldı ve işaretlendi (kuşak N, … işlem, … ms)`, yeni liderde
     `Kalıcı değerlendirme durumu devirde yüklendi (tam) — kuşak N (<pod>), … sn önce yazıldı: …`.
  4. Duman testi (Docker, yerel / CI): `SMOKE_EVAL_READ=1 SMOKE_IMAGE=<imaj> bash scripts/multipod-smoke.sh` — SIGTERM
     devirlerinde "full", ardından SIGKILL'de "observations" ister.
- **İzlenecekler:** her dağıtımdan sonra kart "Tam". "Yalnız gözlemler" (neden "Temiz devir yok (çökme ya da kira süresi
  doldu)", "Arada kaydetmeyen bir lider vardı") bir çökmeden / zorla öldürmeden sonra normaldir; "Yükleme anında
  değerlendirilmemiş elle kontrol sonucu vardı" = devir aralığında biri "Şimdi kontrol et"e bastı (ya da eski lider gelen
  kutusunu boşaltamadı) — sayaçlar bilerek taşınmadı (R-8), hata değil. Eski bir temiz devir pratikte "Yalnız gözlemler /
  devir 5 dakikadan eski" değil, "Yüklenmedi: önceki liderin son tam yazımı 5 dakikadan eski" görünür (devir işareti son
  tam yazımla aynı anı taşır).
  Gri (beklenen) "Yüklenmedi" nedenleri: okuma ayarı kapalı, kayıt kipi kapalı, bu pod'da kaydedici yok, pod kapanıyor,
  durum tabloları / devir işareti sütunları henüz yok (ilk açılış), önceki kuşak yok (ilk lider), son kayıtlı kuşak bu
  pod'undu, önceki lider tam durum işareti yazmadı (eski sürüm ya da ayar kapalıydı), yükleme sırasında liderlik değişti.
  **Turuncu** (kartı uyarıya çeker, incelenmeli): durum okunamadı (veritabanı ya da satır eşleme hatası — yükleme anında
  biter, değerlendirmeler 20 sn beklemez; günlükte yalnız istisna sınıfları), yükleme sınırını aşıyor, yükleme 20 saniyede bitmedi,
  süren değerlendirme bitmedi, yükleme bitmeden değerlendirme yapıldı, önceki liderin kaydı eksikti, önceki liderin son tam
  yazımı 5 dakikadan eski, pod saatleri arasındaki fark 30 saniyeden büyük, ayar yükleme sırasında kapatıldı — günlükte WARN
  `Kalıcı değerlendirme durumu devirde yüklenmedi (<neden>) — yeniden başlatma semantiği (bugünkü davranış)`. Hepsinde
  karar güvenli tarafta kalır (bugünkü davranış). Doğrulama uyuşmazlığı > 0 → ayarı kapatın, sonra inceleyin.
- **Devir işaretlenemedi:** eski liderde INFO `Devir öncesi kalıcı durum işaretlenemedi (<sonuç>) — halef yalnız gözlemleri
  ya da hiçbir durumu yüklemez` (Küme kartında "son devir boşaltması: …", yalnız ölen pod yanıt verirken görünür):
  `budget_exceeded` (veritabanı 1 sn'de yetişemedi), `not_quiescent` (süren değerlendirme ya da bir teyit / kurtarma
  zincirinin yeniden kontrolü 300 ms'de bitmedi — zincir sürerken devir bilerek gözlemlere düşer; devir başladıktan sonra
  başlayan zincir adımı belleğe dokunmadan çıkar, yeni lider zinciri yeniden başlatır), `incomplete` (kuşak henüz
  eşitlenmemişti — pod birkaç saniyeden kısa lider kaldı ya da bekleyen işlem sınırı aşıldı; süren zincir kayıtları
  sayılmaz),
  `fenced` / `no_generation` (yazma kuşağı başka pod'daydı), `flush_failed`. Kira yine en çok 1 sn gecikmeyle bırakılır.
- **Elle kontrol ve devir aralığı:** ayar AÇIKKEN lider olmayan her pod (takipçi ve kapanmakta olan lider) "Şimdi kontrol
  et" sonucunu HER ZAMAN gelen kutusuna (`monitor_eval_inbox`) yazar — boşaltıcı ilanı aranmaz, lidersiz devir aralığında
  da sonuç boş takipçi belleğinde değerlendirilmez. Yeni lider, yükleme sürerken kutuyu boşaltmaz (ilanı yine yazar):
  yükleyici kutuda satır görürse sayaç taşımaz (yalnız gözlemler, `pending_manual_results`), satırlar yüklemeden hemen
  sonraki boşaltma turunda değerlendirilir. Ayar kapalıyken 20.168.1 ile aynı (ilan yoksa yerel değerlendirme). **Hiç lider
  yoksa 10 dakikadan uzun** (R-7) iletilen sonuçlar değerlendirilmeden silinir — elle kontrol yalnız alarm kapatabildiğinden
  en kötü ihtimalle o "Şimdi kontrol et" alarmı kapatmaz, yeni liderin bir sonraki zamanlanmış turu kapatır.
- **Kalan riskler (kabul edildi):** R-1 işaretle kira bırakılması arasındaki milisaniyelerde olan değişiklik taşınmaz;
  R-2 gözlemler son kaydedilmemiş saniyedeki DOWN'u kaçırabilir (= bugün); R-3 sessiz ara lider (temiz devirle halefin
  liderliği arasında hiç kuşak kaydetmeden lider olan pod — yazıcı geri çekilmiş, CAS düşmüş, 20.168.x, kip kapalı): hiç
  vade anahtarı sahiplenmediyse, yalnız halefin ilk taramalarının hemen yeniden sahiplendiği anahtarları sahiplendiyse ya da
  sahiplenmeleri veritabanı kesintisinde düştüyse iz bırakmaz → halef TAM yükler (bileşik arıza); R-4 tek pod'un yeniden
  başlaması durumu ancak yeni yazıcı ilk kiradan sonra 20 sn içinde koşarsa taşır; R-5 pencere tohumu şişmesi (elle `M`
  satırları, yeniden kontrol UP satırları, ≤ 120 sn yenileme gecikmesi); R-6 ayarı yalnız tüm pod'lar 3-2'deyken açın
  (yukarıda); R-7 10 dakikadan uzun lidersizlikte iletilen sonuçlar yaşlanıp silinir; R-8 devir aralığındaki bir "Şimdi
  kontrol et" o devri gözlemlere düşürür; R-9 takipçinin teyit listesi aynı liderde kuşak değişince (kayıt kipi kapalı →
  açık, harita taşması, çit iyileşmesi) süren bir zinciri bitene dek (≈ 1–2 dk) göstermez — liderin kendi listesi tamdır;
  20 dakikadır yazılmamış zincir satırı hayalettir, listelenmez.
- **Kapatmak:** ayarı kapatın (≤ 10 sn, anında güvenli): sonraki devir bugünkü gibi sıfırdan başlar; o an yüklenmiş durum
  sıradan bellektir, sıfırlanmaz; takipçinin teyit listesi belleğe döner (boş); nabızlar 3-1 ifadelerine döner. Acil
  veritabanı yükü: kip `off` (ayar o zaman etkisizdir). Geri alma (20.168.x): ayar anahtarı yok sayılır, üç sütun ve
  `eval-state-load` gözlem satırı kalır, kullanılmaz; kapanış 1 sn kısalır.
- **Teşhis SQL:**
  ```sql
  SELECT gen AS kusak, writer AS yazan, to_timestamp(updated_ms / 1000.0) AS son_nabiz, state_gen AS isaret_kusagi,
         to_timestamp(flushed_ms / 1000.0) AS kalici_an, to_timestamp(handover_ms / 1000.0) AS devir,
         handover_ms = updated_ms AS temiz_devir
    FROM monitor_eval_type_state WHERE alert_type = '*';
  SELECT instance_id, to_timestamp(observed_ms / 1000.0), payload FROM cluster_observations WHERE name = 'eval-state-load';
  ```
  `isaret_kusagi = kusak` ve taze `kalici_an` = lider tam kayıt yapıyor (ayar açık); `temiz_devir` true = son lider temiz
  devretti (halef tam katmanı yükleyebilir); `kalici_an` birkaç saniyeden uzun boş = kuşak eksik (düşen işlem / kanca
  hatası — Küme kartında bekleyen / düşen / kanca hatası satırları). `eval-state-load` = kümenin son yükleme raporu (`outcome`
  full | observations | none, `reason`, `from_generation`, sayılar) — `GET /api/admin/system` → `data.eval_state.load` ve
  `data.eval_state.this_pod.last_load` / `handover_ready` / `last_handover`.

### Kapanış ve süre bütçesi

`terminationGracePeriodSeconds` kubelet preStop'u başlattığı an sayılır; aşılırsa SIGKILL gelir ve kira dolana dek
(180 sn) izleme duraklar:

| Adım | Süre | Ayar |
|---|---|---|
| preStop `sleep` (pod Service / ingress uç noktalarından düşer) | 10 sn | Helm `preStopSleepSeconds` (0 = kanca yok) |
| SIGTERM → (Sürüm 3-2: devirde okuma ayarı açık ve kaydedici hazırsa önce süren değerlendirmeler durulur, kalıcı durum boşaltılır ve devir işareti yazılır) liderlik kirası bırakılır, yeni kilit / tur / liderlik alınmaz (`ContextClosedEvent`, ilk dinleyici) | anında (devirle ≤ 1 sn) | `EvalStateSink.HANDOVER_BUDGET_MS` |
| Push işçisi durdurulur (yeni tur / batch yok, kalan batch'lerin kirası bırakılır) ve süren push biter; bildirim havuzları boşaltılır (çözüm postaları, yeniden gönderimler, giriş sorunu postaları) — hepsi AYNI süre içinde | ≤ 20 sn | `WebConfig.NOTIFY_AWAIT_SECONDS` |
| Web zarif aşaması (süren istekler biter) | ≤ 20 sn | `SHUTDOWN_PHASE_TIMEOUT` (`spring.lifecycle.timeout-per-shutdown-phase`) |
| Yürütücü aşaması (süren kontroller / bildirimler biter) | ≤ 20 sn | aynı |
| Zamanlanmış işler havuzu (süren işler biter, kesilmez) | ≤ 20 sn | `SCHEDULING_AWAIT_TERMINATION` |
| Geri kalan bütün yok etme beklemeleri (e-posta 421 kuyruğu, push, kod girişi, olay bildirimi, elle kontrol havuzu, bildirim havuzlarının kendisi, önbellek yolunun son yayını; Sürüm 3-1'den beri yan iş koşucusu — süren uzun iş ≤ 5 sn, kesmeden sonra ≤ 1 sn — ve kalıcı değerlendirme yazıcısı ≤ 1 sn) | birlikte ≤ 10 sn | `config/ShutdownDeadline.DESTROY_BUDGET_MS` |
| **Toplam** | **≈ 101 sn < 120 sn** (uygulama payı 91 sn = `SPRING_BUDGET_S`) | Helm `terminationGracePeriodSeconds` (120) |

Birini büyütürseniz grace'i de büyütün: `preStop + boşaltma + 2 × aşama + zamanlanmış bekleme + 10 + 1 (devir) < grace` (yeni bir
havuzun `awaitTermination`'ı `ShutdownDeadline.waitMs` kullanmak zorundadır — `ShutdownBudgetGateTest`). İş kilitleri
`@PreDestroy`'da (zamanlayıcı durduktan sonra) bırakılır — Sürüm 3-1'den beri yan iş koşucusu durdurulduktan SONRA (süren
uzun işin kilidi o iş bitmeden / kesilmeden bırakılmaz). `helm template`
preStop ≥ grace'i reddeder; CI (`scripts/helm-template-check.sh`) ve `ShutdownBudgetGateTest` varsayılanları denetler.

### Kilitler

```sql
SELECT name, locked_by, locked_until FROM scheduler_lock ORDER BY name;   -- locked_by = <pod adı>-<8 hane>, UTC
```
- İş kilidini tutan pod onu 30 sn'de bir yeniler (`ClusterLockService`; yenilenen TTL en çok 3 dk, izleme taraması ve
  kendi-kendini-izleme kilitleri 2 dk; sert tavan 3 sa, gece temizliği / ping toparlama 4 sa). Yenileme satırı bulamazsa
  kilit KAYIP sayılır (tek WARN). Kapanışta pod kendi kilitlerini bırakır; ölen pod'un kilidi TTL dolunca düşer, yeni
  pod açılışta kendi ölü örneğinin kilitlerini temizler. Sistem Sağlığı → Zamanlayıcı → Küme kartı "Yanıtlayan pod" bölümü
  (`scheduler.held_locks`, `round_claim_failures`, `skipped_rounds`) YALNIZ sayfayı yanıtlayan pod'un kilitlerini ve
  sayaçlarını gösterir — diğer pod'lar kendi sayaçlarını tutar. Kümenin tamamı: yukarıdaki `scheduler_lock` sorgusu ve tüm
  pod'ların günlüğü (`kubectl logs -n "$NS" -l app.kubernetes.io/instance="$REL" --prefix --tail=-1 | grep -E "Cron turu .* ATLANDI|tur kaydı"`).
  "Dağıtık Kilit" kartı (`cert-check`) satırın süresine de bakar: süresi dolmuş satır "Serbest" + "süresi dolmuş" görünür.
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

### Sistem Sağlığı çok podda — ne küme, ne bu pod

- **"Küme" etiketli kartlar (veritabanı; hangi pod yanıtlarsa aynı):** Zamanlayıcı (çalışıyor mu = canlı `cert-check` kilidi,
  koşturan pod, son koşu ve koşturan pod), Dağıtık kilit, Sertifika taraması (`scan_runs`: saatlik / tam / bayat turun son
  koşusu, pod'u, sayıları, son hata), Heartbeat Sinyali, "Pod'lar" kartı, Alan Adı Süre Kaynağı (RDAP — kümedeki en yeni gözlem
  + gözlemleyen pod), Küme kartının "Tarama lideri" ve (Sürüm 3-1) "Kalıcı değerlendirme durumu" bölümleri; üstteki
  KPI'lardan Zamanlayıcı ve Entegrasyonlar.
- **Etiketsiz ama veritabanından okunan kartlar:** SMTP, webhook push, haftalık erişilebilirlik e-postası, ağ erişimi, gece
  temizliği (gönderim / koşu kayıtlarından — hangi pod yanıtlarsa aynı).
- **"Bu pod" etiketli (sayfayı yanıtlayan pod'un belleği):** JVM belleği ve grafikleri, görev yürütücüsü, DB bağlantı havuzu,
  HTTP özeti, Küme kartının "Yanıtlayan pod" bölümü (Sürüm 3-1'den beri "Değerlendirme kaydı" ve "Yan işler" satırlarıyla); üstteki KPI'lardan çalışma süresi, yürütücü, DB bağlantıları, HTTP süre /
  hata ve bellek (KPI'ların altındaki satır pod'un adını söyler; HTTP isteği başka pod'a düştüyse onu da). Genel banttaki bellek /
  yürütücü / havuz / HTTP sebepleri "(pod: <ad>)" taşır. JVM grafikleri ayrı istekle gelir, kendi "Bu pod: <ad>" etiketi vardır;
  bellek KPI'sının kıvılcımı yalnız aynı pod'un grafiğinden çizilir. Pod başına geçmiş: Sistem Sağlığı → Metrikler.
- **Heartbeat "sessiz" pod:** kapanış işareti yazmadan heartbeat göndermeyi bıraktı ve yerine yeni pod gelmedi (çöktü ve
  dönmedi, ya da zamanlayıcısı takıldı) — genel bant uyarı verir. Pod hâlâ çalışıyorsa günlüğüne ve `kubectl describe pod`
  çıktısına bakın. "Yerine yenisi geldi" (sustuktan sonra YENİ adla bir pod açıldı — tahliye / node kaybı), "yeniden başladı"
  (aynı pod adının yeni süreci canlı) ve "durdu" (dağıtım, kapanış işareti) uyarı değildir; satırlar 24 saat sonra düşer.
  Hiç canlı pod yoksa (ör. 20.166.0'dan geçişin ilk dakikaları) "Pod'lar" kartı yeşil değil, nötrdür.
- Tablo okunamazsa (açılışın ilk saniyeleri, yama düştü) tarama kartı "Bilinmiyor" ve "Bu pod" etiketiyle, RDAP kartı "yalnız
  bu pod" notuyla bu pod'un belleğini gösterir; Zamanlayıcı KPI'sı "bilinmiyor".
- Küme kartında bu pod'un "kaybedildi" durumundaki kilidi (başka pod aldı / zorla bırakıldı) ve son başarılı yoklaması 30 sn'den
  eski önbellek yolu kartı uyarıya çeker.

### Önbellek yolu (cache_bus)

Bir pod'daki yazma öteki pod'ların önbelleğini ~2 sn içinde boşaltır (tablo `cache_bus`, iş parçacığı `cache-bus`; çok pod
Sürüm 2a). Sertifika panosu, kart ekleri, nöbet, sessiz saatler, 7/24 ekibi, erişim paylaşımı, bakım pencereleri, sistem
bakımı, pasif / silinmiş hesap kapısı ve bildirim alıcı süzgeci bu yoldan boşalır; kaydeden sekmenin bir sonraki okuması
(`X-Cache-Min-Gen` başlığı) geride kalan pod'u en çok 0,5 sn bekletip tazeler. Açılışta her pod'un günlüğünde bir kez
`Önbellek yolu çalışıyor (yoklama 2000 ms, …)` görünmeli.

- **Belirti:** "kaydettim ama öteki sekmede / pod'da eski görünüyor" 2 sn'den uzun sürüyorsa tüm pod'ların günlüğünde
  `Önbellek yolu` satırlarına bakın:
  ```bash
  kubectl logs -n "$NS" -l app.kubernetes.io/instance="$REL" --prefix --tail=-1 | grep "Önbellek yolu"
  ```
  `yoklanamadı` / `yayını başarısız` / `denemede yayınlanamadı` = veritabanı sorunu (en çok dakikada bir WARN; o arada
  önbellekler TTL ile tazelenir). Tablonun henüz olmaması yalnız ilk açılışın ilk saniyelerinde normaldir (DEBUG düzeyinde
  yazılır; tablo yoksa pod 30 sn'de bir yeniden bakar, açılış yaması kurunca hemen devreye girer).
- **Yayın kilidi:** her yayın önce `_global` satırını kilitler; PostgreSQL'de en çok 0,5 sn bekler (`lock_timeout`), sonra
  geri çekilip yeniden dener. "yayını başarısız … lock timeout" sürekli görünüyorsa o satırı tutan ölü bir oturum vardır:
  ```sql
  SELECT pid, state, xact_start, application_name, left(query, 80) FROM pg_stat_activity
   WHERE state LIKE 'idle in transaction%' ORDER BY xact_start;      -- uzun süredir açık işlemi bulun, gerekirse:
  -- SELECT pg_terminate_backend(<pid>);
  ```
- İstek, yayının yeni kuşağını en çok 0,25 sn bekler; veritabanı yavaşsa yanıt `X-Cache-Gen` başlığı olmadan gider (yayın
  yine yapılır, yalnız "kaydettiğim veriyi öteki pod'da hemen gör" garantisi o istek için düşer — TTL / sonraki yoklama).
  İstek iş parçacığı veritabanına hiç dokunmaz.
- Öteki pod'un boşaltması ucuzdur (veritabanı okumaz; ör. bakım pencereleri yalnız "bayat" işaretlenir, bir sonraki okuma
  bir kez yeniden yükler) ve önceki görüntüyü silmez, yalnız bayat işaretler: yeniden yükleme veritabanı hatasına düşerse pod
  önceki veriyle devam eder (hiçbir pod "7/24 ekibi yok / sessiz saat yok / pasif süzgeci yok" durumuna düşmez). Hata
  veren bir boşaltma o adı uygulanmamış bırakır ve her yoklamada yeniden denenir (günlükte
  `Önbellek yolu: yerel boşaltma başarısız (<ad>)`).
- **Durum:** `SELECT name, generation, updated_at FROM cache_bus ORDER BY updated_at DESC;` — `_global` her yayında artar.
- **Tabloya elle dokunmayın.** `DROP TABLE cache_bus` tabloyu yalnız pod'ların YENİDEN BAŞLATILMASIYLA geri getirir (yama
  yalnız açılışta koşar): o zamana dek yol kapalıdır (önbellekler TTL ile, her pod 30 sn'de bir yeniden bakar) — geri getirmek
  için `kubectl rollout restart deploy/<ad> -n "$NS"`. `TRUNCATE` / satır silme yolu bozmaz (satırlar ilk yayında yeniden
  kurulur, silinen ad her pod'da bir kez boşaltılır), ama kuşak sıfırlandığı için son 30 sn içinde yazan kullanıcıların
  tarayıcıları daha yüksek bir kuşakla gelir: o kullanıcıların GET'leri ≤ 30 sn boyunca pod başına en çok 500 ms bekler
  (sonra sunulur) — kendiliğinden geçer, ek adım gerekmez.
- **Kapatmak:** `site.monitor.cache-bus.enabled=false` (önbellekler yine TTL ile; tek pod davranışı aynı). Yoklama aralığı
  `site.monitor.cache-bus.poll-ms` (2000), okuma bekleme üst sınırı `site.monitor.cache-bus.min-gen-wait-ms` (500) — ikisi
  de yalnız açılışta okunur.
- Ekip üyeliği değişiklikleri (7/24 ekibine / paylaşılan takıma üye ekleme / çıkarma) yine TTL ile (≤ 30 sn) yansır.

### Takılı kayıtlar ve sınırlar (çok pod Sürüm 2a)

- **Bir IP'nin giriş bloğunu elle kaldırma (2026-10-23).** Giriş IP sınırı artık veritabanında, tüm pod'larda ortak:
  ```sql
  SELECT * FROM rate_limit_counters ORDER BY updated_at DESC LIMIT 20;
  DELETE FROM rate_limit_counters WHERE limit_key = 'login-ip:<ip>';   -- uzun değerler login-ip:#<sha256> biçimindedir
  ```
  Satır kendiliğinden en geç 60 sn (pencere) / blok süresi sonra etkisizleşir ve saatlik temizlikte silinir. Veritabanı
  yazılamazken pod kendi belleğindeki eski sınıra döner (WARN `Oran sınırı sayacı yazılamadı — bellek yedeği`). Hesap
  kilidi (kademeli `lockout_until`) ayrı konudur: [Kilitli admin hesabı](#kilitli-admin-hesabı).
- **Takılı yönetici özeti (SENDING).** Çok pod Sürüm 2a'dan itibaren gönderen pod ilk BCC diliminden ÖNCE kayda "gönderim
  başladı" işareti yazar; saatlik telafi, talebi 1 saatten eski SENDING kaydını bu işaret ve dilim izlerine (SMTP günlüğünde
  tetik `EXECUTIVE_SUMMARY`) göre kendisi kapatır: işaret de iz de yoksa hiçbir şey gitmemiştir → FAILED yazılır ve yeniden
  gönderilir; işaret var ve planlanan HER dilimin izi varsa sonuç izlerden yazılır (`SKIPPED: Gönderen pod sonucu yazamadan
  durdu …`); geri kalan her durumda (işaret var ama iz eksik, ya da işaretsiz iz — eski pod) sonuç BİLİNMİYOR: PARTIAL +
  `FAILED: Sonuç bilinmiyor … yeniden gönderilmedi`, yeniden gönderilmez. Telafi penceresi (72 sa) geçmişse, iz
  okunamıyorsa ya da sonuç bilinmiyorsa karar sizin: Yönetici Özeti → Alıcılar ve gönderim → "Şimdi gönder" (1 saatten eski
  talebi devralır; alıcıların bir kısmı postayı ikinci kez alabilir).
- **Alan adı hatırlatması "yarıda kaldı" (INTERRUPTED).** Gönderen pod e-posta / push sırasında durdu; posta gitmiş olabilir,
  yeniden gönderilmez. Gerekirse takıma elle haber verin. (`ux_der_mon_exp_thr` yalnız `claim_token` dolu satırları kapsar —
  eski sürümlerin satırları kurulumu düşüremez.)
- **Elle saklama temizliği 409 "Temizlik şu an çalışıyor".** Gece temizliği ya da başka bir elle koşu `nightly-cleanup`
  kilidini tutuyor; birkaç dakika sonra yeniden deneyin (kilidi tutan pod:
  `SELECT * FROM scheduler_lock WHERE name = 'nightly-cleanup';`). Elle koşu gece işinin başladığı saate (03:30 Istanbul)
  denk gelirse gece işi kilidi 30 sn'de bir, en çok 15 dk yeniden dener ve elle koşu bitince çalışır (toparlama, temizlik,
  budama); 15 dk'da kilit boşalmazsa o gece atlanır (WARN "bu gece atlandı"). **503 `DB_UNAVAILABLE`** = kilit alınamadı ve
  tutan bir satır da yok / okunamadı (geçici veritabanı hatası): hiçbir şey silinmedi; veritabanı düzelince yeniden deneyin.
- **Kanal teknik hata raporu 409 "Önceki gönderim hâlâ posta kuyruğunda" (`SEND_QUEUED`).** Önceki gönderim posta
  sunucusunun yeniden deneme kuyruğunda; sonucu 20 dk içinde gelir (5 dk'da bir arka planda sonuçlandırılır). Bu sürede
  yeniden gönderim reddedilir — iki farklı içerikli kopya gitmesin diye. Gönder penceresi açık kalır, "Gönder" kilitlenir ve
  ekran mevcut kuyruğun sonucunu bekler: teslim edildiyse pencere kapanır (ikinci gönderim yok), gitmediyse "yeniden
  gönderebilirsiniz" bandı çıkar, 10 dk'da sonuç gelmezse yeniden deneme bandı. Sonuç kanalın gönderim günlüğünde de görünür.

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
   # node'ları verin (nodeSelector / taint'ler). TLS_TARGETS verilmezse TARGETS kullanılır; düz metin bir hedefi
   # (ör. SMTP 25) TLS_TARGETS'a koymayın.
   NS="$NS" REL="$REL" TARGETS="<ldap-host>:636 <push-host>:443 <iç-hedef-1>:443" \
     TLS_TARGETS="<ldap-host>:636 <push-host>:443 <envanter-alanı-1>:443 <envanter-alanı-2>:443" \
     bash scripts/multipod-egress-check.sh <node-1> <node-2> <node-3>
   ```
   Beklenen: her node'da `rdap 200` (vekil üzerinden), bütün hedefler `OK`, bütün TLS hedefleri `TLS OK` ve her TLS
   hedefinin `veren` değeri node'dan node'a AYNI; çıkış kodu 0. Bir node başarısızsa ya
   kural düzeltilir ya da pod'lar sağlam node'lara sabitlenir (Helm `nodeSelector` / `affinity`). Node havuzu her
   değiştiğinde (yeni node, yükseltme) yeniden koşun. Sürüm 3-1'den beri TLS profili yoklaması da (sertifika taramaları
   gibi) herhangi bir pod'dan — iki pod'da çoğunlukla takipçiden — çıkar: `TLS_TARGETS`'a envanterden birkaç doğrudan
   kontrol edilen alan adı (`host:443`) ekleyin. TCP açık ama `TLS FAIL` ya da node'a göre farklı `veren` = o node'da
   TLS'i kesen / değiştiren bir ara kutu (TLS sonlandıran vekil, SSL denetimi) var: o node'dan sertifika taramaları ağ
   hatası, TLS profili "başarısız" ya da farklı sürüm desteği görür (A'ya sınırlı not, sahte düşüş bildirimi). Turları
   normal tamamlayan ama farklı sonuç gören bir node'u liderin "bırakma" kuralı yakalamaz (ağ hatası bir sonuçtur) — bunu
   yalnız bu TLS denetimi yakalar.
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
   `up{job="site-monitor"}` pod başına bir seri. Sürüm 3-1'den beri iş ölçümleri (`/metrics/sitemonitor`) için de ŞART:
   kurum aileleri tek pod'da (takipçi) yayılır.

### 1 → 2 geçişi (ve geri)

- **Kendi `helm upgrade`'i olarak:** önce yeni sürüm `replicaCount: 1` ile dağıtılır ve doğrulanır; sonra AYNI imajla,
  yalnız `environments/master.yaml`'da `replicaCount: 2` + `podDisruptionBudget.enabled: true` (`minAvailable: 1`) +
  P3'e göre `dbPoolMax` değiştirilerek ikinci bir commit / `helm upgrade`. Sürüm değişikliği ile replika artışı aynı
  upgrade'de yapılmaz (iki yeni sürüm pod'u aynı anda açılır). Spread (`topologySpreadConstraints`) yalnız ≥ 3
  zamanlanabilir node varsa, `matchLabelKeys: ["pod-template-hash"]` ile (Kubernetes ≥ 1.27; staging bunu taşır). Çok
  bölgeli kümede ek olarak `topologySpreadConstraints.zone.enabled: true` (tercih — `ScheduleAnyway`, tek bölgede de
  zamanlar). Düğüm baskısında önce başka iş yükleri tahliye edilsin istenirse `priorityClassName` (sınıf kümede olmalı).
  PDB için tek kural: `minAvailable` replika sayısından KÜÇÜK olmalı (2 replika → 1) ya da yerine `maxUnavailable: 1`
  (`minAvailable: null` ile); aksi `helm template` reddeder (node drain süresiz bloke olurdu).
- Bellek oturum deposuyla (`config.sessionStoreType` ≠ `jdbc`) birden çok pod `helm template`'te reddedilir.
- **Doğrulama:** Sistem Sağlığı → `session_store` = `jdbc`, Küme kartında tek tarama lideri ("Pod'lar" kartında iki canlı
  pod); Grafana
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
kubectl logs $L | grep -c "Kalıcı değerlendirme doğrulaması"                 # 0 olmalı (Sürüm 3-1: bellek ↔ tablo uyuşmazlığı, karar etkilenmez)
kubectl logs $L | grep "Yan iş .* başarısız" | tail -5                       # boş olmalı (Sürüm 3-1: yan iş koşucusundaki hata)
kubectl logs $L | grep -E "devirde yüklen|Devir öncesi kalıcı durum" | tail -10  # Sürüm 3-2, ayar açıkken: her devirde "yüklendi (tam)" + eski liderde "boşaltıldı ve işaretlendi"
kubectl get pod -n "$NS" -l app.kubernetes.io/instance="$REL" \
  -o custom-columns=POD:.metadata.name,NODE:.spec.nodeName,RESTART:.status.containerStatuses[0].restartCount,SEBEP:.status.containerStatuses[0].lastState.terminated.reason
```

### Geçici disk (ephemeral-storage) ve günlük birimi

Pod `/var/log` emptyDir'ine (logback ≤ ~1,25 GB) ve `/tmp`'ye yazar; chart 2026-10-23'ten beri `ephemeral-storage`
request 1Gi / limit 4Gi ve `/var/log` için `sizeLimit: 2Gi` koyar. Aşılırsa kubelet pod'u TAHLİYE eder (düğüm diski dolmaz):
`kubectl get pod -n "$NS" -l app.kubernetes.io/instance="$REL" -o custom-columns=POD:.metadata.name,SEBEP:.status.reason,ILETI:.status.message`
→ `Evicted` + "ephemeral-storage" / "emptyDir usage exceeds". Neden: `kubectl exec <pod> -- du -sh /var/log /tmp`. Çözüm:
günlük seviyesi (DEBUG hacmi), `storage.varLogSizeLimit` / `resources.limits.ephemeral-storage` yükseltme (kota da kabul etmeli).

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
öncesi): `SMOKE_IMAGE=<imaj> bash scripts/multipod-smoke.sh` — Sürüm 3-1'den beri her liderlik devrinden (lidere SIGTERM,
sonra eski pod yeniden açılıp geri devir; `SMOKE_FLIP_BACK=0` geri devri atlar) sonra yeni liderin kalıcı değerlendirme
durumunu da denetler: kayıt yazıyor, kümenin doğrulaması yeni kuşakta "uyuşuyor" (0 uyuşmazlık; devirden hemen sonraki
"eşitleniyor" turları beklenir) ve "devralınan" satırlar önceki kuşağı sayıyor. Dedektörün 0b / 0c bölümleri bilgi verir
(sayılmaz): turların son sahiplenen pod'u (yan işler takipçide mi) ve kalıcı durumun kuşak satırı (yazan = lider mi, nabız
yaşı). "TLS turu dönem başına bir kez" sayılamaz — tur satırı yalnız son sahiplenmeyi tutar; teklik tur kaydının kendisi
(CAS) + kilitle sağlanır.

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
- **Denetim zinciri (2026-10-23 düzeltmesi):** eski (20.168.0) pod geçiş süresince `seq = 1 / GENESIS` yazmaya devam
  eder; bu satırlar doğrulanan bölümün dışındadır ve "doğrulanamaz" sayısına girer. Yeniden başlatma satırı ilk yeni
  pod'un açılışından ~10 dk sonra yazılır — o ana dek bütünlük denetimi kırık göstermeye devam eder. Eski pod yazdığı
  sürece `ux_audit_seq` kurulmaz (açılış kurulumu da, iş de 10 sessiz dakika bekler).

**Çok pod Sürüm 2a'ya geçişte (20.165.x pod'u + yeni pod):**

- **Önbellek yolu:** eski pod ne yayınlar ne yoklar ve `X-Cache-Min-Gen` başlığını yok sayar. Eski pod'un sunduğu yazma
  yeni pod'a TTL ile ulaşır (sertifika panosu ≤ 5 dk), yeni pod'un yazması eski pod'un önbelleğine hiç ulaşmaz (TTL). Bir
  şey bozulmaz; silinen kart tarayıcıda yine 10 dk gizli kalır.
- **Giriş IP sınırı:** eski pod kendi belleğinde sayar — en kötü durumda eski pod başına 10 + yeni pod'larda küme geneli 10
  deneme. Eski pod'daki başarılı giriş veritabanı sayacını, yeni pod'daki başarılı giriş eski pod'un sayacını temizlemez
  (≤ 60 sn / blok ≤ 30 sn). Onay bağlantısının sınırı eski pod'da hâlâ ingress adresine göredir.
- **Kullanıcı kayıtları:** eski pod ayrık `app_users` varlığını hâlâ birleştirerek kaydeder — eşzamanlı bir pasifleştirmeyi
  geri açabilir. Geçiş bitene kadar (toplu) pasifleştirme yapmayın; yaptıysanız sonra pasif hesapları kontrol edin.
- **Denetim zinciri:** eski pod işlem içindeki denetim kaydını eski yolla yazar; eski ve yeni pod arasında zincir çatallanması
  hâlâ mümkündür (bütünlük denetimi bu pencere için yanlış "kurcalama" diyebilir). Yeni pod'ların satırları korunur.
- **Kanal teknik hata raporu:** eski pod kuyruktaki gönderimin üzerine yeniden göndermeye izin verir (409 `SEND_QUEUED`
  yok) ve kuyrukları arka planda sonuçlandırmaz — geçiş sırasında "Gönder"e ikinci kez basmayın.
- **Kapatılan türler:** eski pod her ayar olayında kilitsiz uzlaştırır ve alarmı tüm varlıkla kaydeder — yeni pod'un
  kapattığı alarmın `resolved_by` değeri yeniden yazılabilir; ikinci ÇÖZÜLDÜ push'unu indeks engeller.
- **Alan adı hatırlatması:** eski pod "var mı → posta → kaydet" sırasıyla çalışır; eski pod postayı gönderip henüz
  kaydetmemişken yeni pod da gönderebilir (bugünkü yarış, daha dar). Yeni pod'un `SENDING` satırı eski pod'u durdurur.
- **Yönetici özeti / envanter raporu:** eski pod küme turu ve planlı tetik denetimi olmadan tetikler; ay satırının tekil
  sahiplenmesi ikinci yönetici özeti postasını yine önler. Eski pod "gönderim başladı" işaretini yazmaz: yeni pod'lar eski
  pod'un yarıda bıraktığı SENDING kaydını, izi VARSA PARTIAL yazıp göndermeden kapatır, izi YOKSA yeniden gönderir (eski pod
  SMTP kabulüyle izi arasında öldüyse ikinci posta mümkün — yalnız geçiş penceresinde).
- **Geri alma:** yeni tablolar (`cache_bus`, `rate_limit_counters`), sütunlar (`audit_log.seq_locked`,
  `tech_error_reminders.inflight_at`, `tls_grade_changes.notify_attempts`, `domain_expiry_reminders.claim_token`,
  `executive_summary_reports` / `executive_summary_team_reports.send_started_claim|send_trace_name`) ve
  indeksler (`ux_audit_seq`, `ux_der_mon_exp_thr`, `idx_lir_ip_reported`) kalır, eski kod yok sayar. Eski arayüz alan adı
  hatırlatmasının `INTERRUPTED` durumunu ham anahtar (`dreg.remStatus_INTERRUPTED`) olarak gösterir.

**Çok pod Sürüm 2b'ye geçişte (20.166.0 pod'u + yeni pod):**

- **Sayfa dosyaları:** bu ilk geçişte bir kullanıcı bir kez boş sayfa görebilir (F5 düzeltir) — ayrıntı ve sonraki
  dağıtımlar: aşağıdaki "Yuvarlanan dağıtımda sayfa dosyaları".
- **Sistem Sağlığı:** eski pod `scan_runs` / `cluster_observations` yazmaz, heartbeat'i pod adı olmadan yazar (şeritte
  sayılır, "Pod'lar" listesinde görünmez); eski pod saatlik taramayı koştuysa tarama kartı daha eski bir koşuyu gösterebilir
  (yeni pod'un açılıştaki tam taraması genellikle ~1 dk içinde satır yazar). Yeni arayüzü eski pod yanıtlarsa "Bu pod"
  etiketleri adsız, Küme kartında önbellek yolu satırı yok — bozulan bir şey yok.
- **Alarm yazımları:** eski pod alarmı hâlâ bütün varlıkla kaydeder — yeni pod'un kapattığı bir alarmı yeniden açabilir,
  `resolved_by`'ı yeniden yazabilir, bir onayı geri alabilir (ikinci ÇÖZÜLDÜ push'unu indeks engeller). Son eski pod gidince biter.
- **Sürüm bildirimi:** iki yeni pod aynı sürümü tek kez duyurur (`schema_patch_markers` `deploy-notice:…`); eski pod kendi
  bildirimini işaretsiz gönderebilir.
- **Geri alma:** yeni tablolar (`scan_runs`, `cluster_observations`) ve sütunlar (`system_heartbeat.instance_id|shutdown_marker`,
  `system_metric_samples.clock_skew_ms`) kalır, eski kod yok sayar; chart değerleri eklemelidir (eski chart yok sayar); eski
  imajda yeni sürümün sayfa dosyaları yoktur — açık sekmeler bir kez yenilenir.

**Çok pod Sürüm 3-1'e geçişte (20.167.0 pod'u + yeni pod):**

- **Yan işler:** yeni lider, eski (20.167.0) takipçinin heartbeat'ini görür ve cron yan işlerini 20 sn erteler; eski takipçi
  tetikte işi alır (kilit + tur), liderin ertelenen denemesi turu alınmış bulur — çift iş yok. Eski pod `round:tls-profile`
  turunu hiç almaz → TLS profilini yeni lider koşar. Eski lider + yeni takipçi: kimse ertelemez; yeni takipçi TLS profilini
  tur kaydıyla, eski lider kilit-yalnız tıklarıyla koşar → geçişte dönem başına en çok bir fazla TLS tıkı (uçların vadesi
  veritabanında, kilit sıralar — aynı uç iki kez üst üste yoklanmaz). Eski pod tamamlanma damgası (`round:<tur>:done`)
  yazmaz → yeni lider bayat sertifika taramasını ve yönetici özeti telafisini ona bırakmaz (20.167.0'daki gibi yarışırlar;
  tur ikinci koşuyu önler). Eski pod `round:incident-action-reminder` turunu almaz → eski takipçi 09:00'da koştuysa liderin
  ertelenen denemesi gövdeyi yeniden koşar: öğe başına sahiplenme ikinci postayı önler, sistem bakımı susturmasının olduğu
  günde sahip başına bir fazla `SKIPPED: sistem bakımı` satırı olabilir (yalnız geçiş penceresinde).
- **Kalıcı değerlendirme durumu:** eski lider yazmaz (yeni takipçiler lider değildir → o sırada kimse yazmaz); yeni lider
  yazar ve doğrular. Yeni → eski → yeni liderlikte ikinci yeni lider YENİ kuşak alır, öncekinin satırları "devralınan"
  sayılır (karara girmez).
- **Kurum ölçüleri (Grafana):** eski pod `metrics-org` gözlemini yazmaz → yeni lider kurum ailelerini de yayar (bugünkü
  gibi; pano `max by` ile birleştirir).
- **Geri alma:** dört tablo (`monitor_eval_state`, `monitor_last_status`, `monitor_eval_type_state`,
  `monitor_confirmations`), `round:tls-profile`, `round:incident-action-reminder` ve `round:<tur>:done` satırları kalır,
  eski kod yok sayar (eski pod'un budaması `round:` satırlarına dokunmaz); `cluster_observations` satırları
  `eval-state-verify` / `metrics-org` okunmaz; iki ayar anahtarı kalır, yok sayılır. Eski sürümde TLS profili yine yalnız
  liderde koşar.

**Çok pod Sürüm 3-2'ye geçişte (20.168.x pod'u + yeni pod):**

- **Varsayılan kapalı:** ayar açılmadıkça hiçbir şey yüklenmez; ilk yeni pod'un şema yaması `monitor_eval_type_state`'e üç
  boş sütun ekler (o ana dek yeni pod 3-1 ifadeleriyle yazar, devirde yükleme `columns_missing`). Ayarı geçiş bitmeden
  AÇMAYIN.
- **Ayar açıksa bile karışık sürüm güvenli:** eski (20.168.x) lider devir işareti yazmaz → yeni halef hiçbir şey yüklemez
  ("Önceki lider tam durum işareti yazmadı"); eski halef hiç yüklemez, kuşak CAS'ı işareti geçersiz kılar. Her birleşim
  bugünkü yeniden başlatma semantiği ya da daha iyisidir — tek istisna eski TAKİPÇİ: devir aralığında gelen "Şimdi kontrol
  et" sonucunu yerel değerlendirir (yeni pod'lar ayar açıkken gelen kutusuna iletir), DOWN'un sayaç sıfırlaması yeni lidere
  ulaşmaz (R-6) — bu yüzden ayarı geçiş bitmeden açmayın. Ayar açıkken yeni takipçi, eski liderin teyit zincirlerini (aynı
  tablo biçimi) listeler; eski takipçi boş liste verir.
- **Kapanış:** yeni pod ayar açık ve hazırken kirayı en çok 1 sn geç bırakır (bütçe 101 < 120 sn); eski pod'da devir adımı
  yoktur.
- **Geri alma:** ayar anahtarı, üç sütun ve `eval-state-load` satırı kalır, eski kod yok sayar.

### Yuvarlanan dağıtımda sayfa dosyaları (çok pod Sürüm 2b)

Eski ve yeni pod birlikte sunarken bir sayfa yüklemesi iki sürümün dosyalarını karıştırabilir (index.html bir poddan,
`/assets/<ad>-<hash>.js` öbüründen). Önlemler, yapışkan oturum (sticky session) OLMADAN:

- **İmaj son 5 sürümün sayfa dosyalarını da taşır** (her sürümün KENDİ dosyaları; prod sürüm atlasa da 5 sürüme kadar geriye;
  yalnız arka uç değişen bir sürüm paketi aynı olduğu için hiçbir şey taşımaz ama geçmişi taşır). Kontrol (yeni pod):
  `kubectl exec -n "$NS" <pod> -- cat /app/frontend/carry-report.json` → `included` dağıtımdan ÖNCE çalışan sürümü
  içermeli (Sistem Sağlığı → Sürüm & Dağıtım) ya da o sürümden beri arayüz değişmemiş olmalı (`carried: 0` o zaman
  doğrudur — hata değil); `reason` `ok` (ya da ilk derlemede `no-previous`; `cap` = en eski sürümler sığmadı). Önceki imaj
  okunamadıysa derleme günlüğünde `::warning::önceki sürüm imajı okunamadı` (taşınan 0).
  Bir taşınan dosya (varsa): `kubectl exec -n "$NS" <pod> -- sh -c 'f=$(head -1 /app/frontend/carried-assets.txt); [ -z "$f" ] || curl -sI http://localhost:8080/assets/$f'`
  → `200` + `Cache-Control: public, max-age=31536000, immutable`. Prod 5'ten çok sürüm atlarsa eski index → yeni pod
  yönü de açılış korumasına (bir yenileme) düşer.
- **Sayfa paketi yüklenemezse sayfa kendini BİR KEZ yeniler** (`/boot-guard.js`, önbelleksiz). İkinci kez de yüklenemezse
  boş sayfa yerine "SiteMonitor güncelleniyor … Yeniden yükle" uyarısı çıkar; dağıtım bitince "Yeniden yükle" düzeltir.
  Sayaç oturum başına: paket başına bir otomatik yenileme, herhangi bir otomatik yenilemeden sonra 60 sn bekleme — döngü olmaz.
  İngilizce sözlük açılışta inmezse de önce bir yenileme; yine inmezse eskisi gibi Türkçe + "dil yüklenemedi" bildirimi.
- **Bilinen sınır:** bu sürümün İLK dağıtımı 20.166.0 pod'larıyla karışır — onlarda `boot-guard.js` ve taşınan dosyalar
  yoktur; o pencerede bir kullanıcı bir kez boş sayfa görebilir (F5 düzeltir). Sonraki dağıtımlarda: eski index → yeni pod
  yönü son 5 sürüm için taşınan dosyalarla çalışır; yeni index → ESKİ pod yönü taşımayla çözülemez — sayfa bir kez
  kendiliğinden yenilenir, yine olmazsa uyarı + "Yeniden yükle" (1 yeni + 2 eski pod'lu pencerede yeni index'li açılışların
  çoğu bu bir yenilemeyi yaşar).
  Geri alma (`helm rollback` önceki imaja): yeni sürümün sayfa dosyaları eski imajda yoktur; açık sekmeler bir kez yenilenir.

### Sürüm 2'ye kadar bilinen pod farkları (zararsız ama kafa karıştırır)

- Sistem Sağlığı kartları çok pod Sürüm 2b'den beri "Küme" ya da "Bu pod: <ad>" etiketi taşır
  ([Sistem Sağlığı çok podda](#sistem-sağlığı-çok-podda--ne-küme-ne-bu-pod)); bellek / yürütücü / havuz / HTTP kartları ve
  Küme kartının "Yanıtlayan pod" bölümü sayfayı yanıtlayan pod'undur, yenilemede başka pod'a geçebilir.
- Önbellekler (sertifika panosu, nöbet, erişim paylaşımı, 7/24 ekibi, bakım pencereleri …) çok pod Sürüm 2a'dan beri
  [önbellek yolu](#önbellek-yolu-cache_bus) ile ~2 sn içinde bütün pod'larda boşalır; yalnız TTL'li ezberler pod başınadır
  (ör. İzleme Panosu / durum sayfası 30 sn, sertifika ağ kesintisi durumu 10 sn).
- Güvenlik sınırları (giriş IP sınırı, k6 gizli değer gösterimi, oturumsuz giriş yardımı / hata bildirimi) Sürüm 2a'dan
  beri küme genelidir; yük sınırları ve eşzamanlılık izinleri (tanılama, PDF, test postası, onay bağlantısı) pod
  başınadır: N pod = N kat.
- Çevrimiçi göstergesi sayfa yenilemesinde ≤ ~15 sn "çevrimdışı" görünebilir.

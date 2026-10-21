# Prod Dağıtım Kontrol Listesi — Site Monitor

> Hedef kitle: prod'a `helm upgrade` yapan operatör. Bu dosya prod için **tek geçerli** dağıtım
> komutunu ve öncesi / sırası / sonrası / geri alma adımlarını tanımlar. README, `environments/master.yaml`
> başlığı ve GitHub Release notu buraya yönlendirir. Olay anı prosedürleri: [`RUNBOOK.md`](RUNBOOK.md).
>
> Kaynak: prod kapısı 2026-09-25 bulguları (P3-1 dağıtım komutları, P3-2 secret key, P3-3 bellek,
> P10-1 CHANGELOG) ve 2026-09-26 kullanıcı kararları (3Gi bellek, bilinçli DEBUG log seviyesi).

## Sabitler — önce bunları doğru bulun

Hiçbirini tahmin etmeyin; hepsi canlı kümeden okunur.

```bash
helm list -A | grep -i monitor               # gerçek release adı + namespace + şu anki APP VERSION

export REL=<helm list'teki NAME>              # README/master.yaml'daki örnek adlara GÜVENMEYİN
export NS=<helm list'teki NAMESPACE>
export VERSION=X.Y.Z                          # yayınlanacak sürüm, ör. 20.87.0 (git tag'i vX.Y.Z)
export IMAGE_REPO=ghcr.io/<owner>/site-monitor   # ya da kurum içi ayna (mirror) — aynı imaj
export PRIVATE_VALUES=/guvenli/yol/prod-private.yaml   # REPO DIŞI, aşağıya bakın

export DEPLOY=$(kubectl get deploy -n "$NS" -l app.kubernetes.io/instance="$REL" -o name)
export SECRET=$(kubectl get deploy -n "$NS" -l app.kubernetes.io/instance="$REL" \
  -o jsonpath='{.items[0].spec.template.spec.containers[0].envFrom[?(@.secretRef)].secretRef.name}')
echo "$DEPLOY / $SECRET"                      # ikisi de dolu olmalı
```

> **Yanlış release adı = ikinci kurulum.** `helm upgrade --install` bulamadığı adla sessizce **yeni**
> bir release kurar (aynı host'a ikinci Ingress, aynı DB'ye ikinci zamanlayıcı). `REL`/`NS` mutlaka
> `helm list` çıktısından alınır.

> **Hangi chart?** Komut chart'ı ve `master.yaml`'ı **dağıtılan tag'in kendisinden** alır. Bu listedeki chart
> düzeltmeleri (`v` önekli etiket varsayılanı, prod bellek 3Gi / 1.5Gi, `image.digest`, `imagePullSecrets`)
> v20.86.0'dan **sonraki** ilk sürümle gelir. v20.86.0 ya da daha eski bir tag'den dağıtılırsa: etiket komutta
> zaten açık verildiği için imaj doğru çekilir, ama bellek eski değerde (1536Mi) kalır — o durumda komuta
> `--set resources.limits.memory=3Gi --set resources.requests.memory=1536Mi` ekleyin; `image.digest` ve
> `imagePullSecrets` o chart'ta yoktur (sessizce yok sayılır).

### İmaj etiketi `v` önekli

`release.yml` imajı yalnız `ghcr.io/<owner>/site-monitor:vX.Y.Z` ve `:latest` olarak basar. Öneksiz
`X.Y.Z` etiketi registry'de **yoktur** → `ImagePullBackOff`. Chart artık `image.tag` boşken
`v<Chart.AppVersion>` üretir; komut yine de etiketi açıkça verir. Yerelde `scripts/build-image.sh` ile
üretilen imajlar öneksizdir — öyle bir imaj dağıtılacaksa etiketi birebir yazın.

## Öncesi (T-1 gün … T-15 dk)

### İlk çok pod sürümüne geçiş (20.164 ve öncesi → bu sürüm) — bir kerelik
Bu bölüm yalnız prod çok pod sürüm 1'i (2026-10-21) İLK kez aldığında geçerlidir; sonraki dağıtımlarda atlanır.

- [ ] **Sessiz bir saat seçildi.** Kapanan eski pod kendi eski ayarlarıyla kapanır: grace 60 sn, preStop yok, 30 sn'lik
      aşamalar, liderlik kirası yalnız `@PreDestroy`'da bırakılır. Beklenen: geçişte birkaç 502; eski pod 60 sn'yi aşarsa
      izleme ~4 dk durur (kira 180 sn dolana dek); eski pod'un kuyruğundaki "ÇÖZÜLDÜ" postaları kaybolabilir. Yeni
      kapanış (preStop 10, grace 120, kira ilk anda bırakılır) bir SONRAKİ dağıtımda devreye girer.
- [ ] **Olaysız push çiftleri yok** (yeni tekil indeks `ux_push_eventless_key_user` bu satırlarla kurulamazdı; her açılışta
      yeniden dener ve düşer) — boş dönmeli:
  ```sql
  SELECT dedupe_key, username, COALESCE(team_id, 0) AS team, count(*)
    FROM user_push_deliveries
   WHERE alert_event_id IS NULL AND push_trigger NOT IN ('TEST', 'OVERFLOW_SUMMARY') AND created_at >= '2026-10-21'
   GROUP BY 1, 2, 3 HAVING count(*) > 1;
  ```
  Satır dönerse geçmişi silmeden giderin — her grubun en eski satırı kalır, sonrakilerin anahtarı tekilleşir:
  ```sql
  UPDATE user_push_deliveries d SET dedupe_key = LEFT(d.dedupe_key, 40) || ':dup' || d.id
   WHERE d.id IN (SELECT id FROM (
           SELECT id, ROW_NUMBER() OVER (PARTITION BY dedupe_key, username, COALESCE(team_id, 0) ORDER BY id) AS rn
             FROM user_push_deliveries
            WHERE alert_event_id IS NULL AND push_trigger NOT IN ('TEST', 'OVERFLOW_SUMMARY')
              AND created_at >= '2026-10-21') x WHERE rn > 1);
  ```
- [ ] **Kaynak başına birden çok ONGOING ağ kesintisi satırı** — bilgi amaçlı; varsa tek seferlik iş
      (`NetworkOutageOngoingDedupe`, açılıştan ~150 sn sonra) en yenisini bırakıp gerisini kapatır, sonra `ux_noe_one_ongoing`'i kurar:
  ```sql
  SELECT COALESCE(source, 'CERT') AS kaynak, count(*) FROM network_outage_events WHERE status = 'ONGOING'
   GROUP BY 1 HAVING count(*) > 1;
  ```
- [ ] Geçiş penceresinde (eski + yeni pod birlikte) beklenen yinelenmeler biliniyor: eski pod'un bellekten yönetici kesinti
      postası, eski yolla kapanan fırtınanın ikinci kurtarma postası, eski pod'da sahiplenmesiz push / haftalık rapor —
      [`RUNBOOK.md` → Sürüm geçişi sırasında](RUNBOOK.md#sürüm-geçişi-sırasında-eski--yeni-pod-birlikte).

### Kapsam ve sürüm
- [ ] Dağıtılan şey **tag'lenmiş sürüm** (`git checkout v$VERSION`); çalışma ağacındaki commit'lenmemiş iş
      bu dağıtıma dahil değildir. GitHub → Actions'ta o commit için `ci.yml` **dört işi de yeşil**.
- [ ] Prod'un şu anki sürümünden `$VERSION`'a kadar [`CHANGELOG.md`](../CHANGELOG.md) okundu; özellikle
      **⚠ Davranış**, **Yeni yapılandırma** ve **Operatör notu** işaretli satırlar. Örnek: v20.72.0'dan beri
      envanter erişilebilirlik (HTTP) süpürmesi **5 dk → 60 dk** — kullanıcılara duyurulmalı.

### Mevcut durumun dökümü (geri dönüş için)
- [ ] Değerler ve nesneler repo DIŞINDA, erişimi kısıtlı bir yere alındı (**sır içerir**):
  ```bash
  helm get values "$REL" -n "$NS" -o yaml      > prod-values-user-before.yaml   # yalnız elle verilenler
  helm get values "$REL" -n "$NS" -a -o yaml   > prod-values-all-before.yaml    # birleşik
  helm history "$REL" -n "$NS"                                                  # geri dönülecek REVISION'ı not edin
  kubectl get deploy,cm -n "$NS" -o yaml       > prod-objects-before.yaml
  kubectl get pod -n "$NS" -l app.kubernetes.io/instance="$REL" \
    -o jsonpath='{..imageID}{"\n"}'                                              # çalışan imajın digest'i (geri alma için)
  ```
- [ ] **Açılışta kurulacak indeks (v20.86.0):** yeni sürüm açılırken
      `CREATE INDEX IF NOT EXISTS idx_audit_actor_team_time ON audit_log(actor_team_id, event_time)` koşar —
      `CONCURRENTLY` değildir: kurulurken `audit_log` yazımları bekler ve büyük tabloda açılış süresi uzar
      (startup bütçesi ~300 sn). `SELECT count(*) FROM audit_log;` milyonlar düzeyindeyse indeksi dağıtımdan
      **önce** kilitsiz kurun; açılıştaki yama o zaman işsiz geçer:
      ```sql
      CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_actor_team_time ON audit_log (actor_team_id, event_time);
      ```
- [ ] **DB yedeği** alındı ve geri yüklenebilir olduğu biliniyor: platformun DB snapshot'ı ya da
      `pg_dump -Fc -h <db-host> -U <db-user> -d <db-adi> -f sitemonitor-$(date +%F).dump`.
      Şema yamaları geriye dönük uyumludur (bkz. Geri alma) ama veri bozan bir hataya karşı tek güvence budur.

### SECRET KEY — değiştirilmeyecek
`SITE_MONITOR_SECRET_KEY` UI'dan kaydedilen sırları (LDAP bind parolası, SMTP parolası, HTTP/Keyword özel
başlık sırları, kişi-push başlık sırları, PageSpeed API anahtarı, senaryo env sırları) şifreler. Değer
değişirse uygulama **açılır ama** bu sırların hepsi `null` çözülür — tek iz `SecretCipher: decrypt failed`
WARN satırıdır. Anahtar yayınla birlikte değiştirilmez; değişim ayrı bir bakım işidir ve **döndürme** ile yapılır
(yeni + önceki anahtar, "Yeniden şifrele", önceki anahtarı kaldır — [`RUNBOOK.md` → Anahtar döndürme](RUNBOOK.md#anahtar-döndürme)).

- [ ] Secret'taki değer ile **çalışan pod'un** kullandığı değer aynı (değer basılmaz, yalnız özet karşılaştırılır):
  ```bash
  live=$(kubectl exec -n "$NS" "$DEPLOY" -- sh -c 'printf %s "$SITE_MONITOR_SECRET_KEY" | sha256sum')
  sec=$(kubectl get secret "$SECRET" -n "$NS" -o jsonpath='{.data.SITE_MONITOR_SECRET_KEY}' | base64 -d | sha256sum)
  [ "${live%% *}" = "${sec%% *}" ] && echo "AYNI - devam" || echo "FARKLI - DUR, RUNBOOK'a bak"
  ```
  Farklıysa biri Secret'ı pod açıldıktan sonra değiştirmiştir; bu yayında **pod'daki** değer esas alınır.
- [ ] Anahtar `REPLACE_…` gibi eski bir yer tutucuysa bile **bu yayında değiştirilmez**
      (`… | base64 -d | grep -c '^REPLACE'`). Değişim ayrı bir bakım işidir.
- [ ] Secret'ta `SITE_MONITOR_SECRET_KEY` yok da eski adla `CERT_MONITOR_SECRET_KEY` varsa: yeni anahtar
      **üretmeyin**, aynı değeri yeni ada kopyalayın.

### Secret ve özel değerler
- [ ] **Tercih edilen:** `secret.existingSecret="$SECRET"` — Secret'a helm hiç dokunmaz. İçinde şu anahtarlar
      bulunmalı (eksik anahtar pod'a env olarak gelmez):
      `ADMIN_USERNAME, ADMIN_PASSWORD, DB_PASSWORD, SPRING_MAIL_USERNAME, SPRING_MAIL_PASSWORD,
      SITE_MONITOR_EMAIL_FROM, SITE_MONITOR_SECRET_KEY, HTTP_PROXY_USER, HTTP_PROXY_PASS`.
      ```bash
      # yalnız anahtar ADLARINI listeler, değer basmaz
      kubectl get secret "$SECRET" -n "$NS" -o go-template='{{range $k, $v := .data}}{{$k}}{{"\n"}}{{end}}'
      ```
  Secret şu an chart tarafından üretiliyorsa (`<release>-sitemonitor-chart-secret`) aynı adı
  `existingSecret` olarak vermek güvenlidir: chart Secret'ı artık render etmez, `helm.sh/resource-policy: keep`
  nesneyi silinmekten korur, pod aynı Secret'ı okumaya devam eder.
- [ ] **existingSecret kullanılmayacaksa** her `secret.*` değeri **şu anki** değeriyle verilmelidir. Verilmeyen
      her anahtar `values.yaml` yer tutucusuna düşer ve helm canlı Secret'ın **üzerine yazar** (`keep` yalnız
      silmeyi engeller, üzerine yazmayı değil):

      | Eksik bırakılan | Yerine yazılan | Sonuç |
      |---|---|---|
      | `secret.secretKey` | `""` | prod profili açılışta durur (CrashLoop, `--atomic` geri alır) |
      | `secret.dbPassword` | `REPLACE_ME` | DB'ye bağlanamaz |
      | `secret.smtpPassword` / `smtpUser` / `emailFrom` | yer tutucu / örnek posta kutusu | pod açılır, SMTP env'den geliyorsa alarm e-postaları **sessizce** FAILED |
      | `secret.httpProxyUser/Pass` | `""` | kimlikli vekil arkasında RDAP/dış kontroller **sessizce** düşer |
      | `secret.adminUsername` | `admin` | bootstrap-admin kimliği (Ayarlar SMTP/LDAP/secret geçidi) başka kullanıcıya kayar |

      Değerleri `--set` ile vermeyin (virgül/ters bölü içeren değerler bölünür); repo dışı, erişimi kısıtlı bir
      `-f prod-secrets.yaml` dosyası kullanın ve dağıtımdan sonra silin.
- [ ] `PRIVATE_VALUES` (repo dışı, commit edilmez) hazır:
  ```yaml
  # prod-private.yaml — REPO DIŞI
  config:
    dbHost: "<prod-db-host>"
    dbPort: "5432"
    dbName: "<canlı değer — rename sonrası da eski DB adı olabilir>"
    dbUser: "<canlı değer>"
    # prod-values-user-before.yaml'da olup master.yaml'da OLMAYAN her config.* anahtarı buraya
    # (ör. smtpHost/smtpPort/smtpSslTrust — verilmezse values.yaml varsayılanına döner).
  # imagePullSecrets:            # registry özel ise (özel GHCR paketi / kurum içi ayna)
  #   - name: <pull-secret-adı>
  ```
  Kural: `prod-values-user-before.yaml`'daki **her** anahtar ya `master.yaml`'da ya `PRIVATE_VALUES`'ta
  ya da komutta olmalı. Olmayan her anahtar bu dağıtımda chart varsayılanına döner.

### master.yaml'ın getireceği sapma kapanışı (drift)
Belgeli komut çalışmadığı için prod bugüne dek `--reuse-values` ile yükseltildi ve `master.yaml` prod'a
ulaşmadı. Bu dağıtım onu **ilk kez** uygular — aşağıdaki değişiklikler bilinçli olmalı:

- [ ] `DB_POOL_MAX` → **30** (son bilinen canlı değer 10 — `prod-values-all-before.yaml` ile doğrulayın). Postgres kapasitesi yeterli:
  ```sql
  SHOW max_connections;
  SELECT count(*) FROM pg_stat_activity;
  ```
  `max_connections` ≥ **(replika + 1) × dbPoolMax + 25** (yedek, yönetim araçları, izleme payı) — `maxSurge: 1`
  yüzünden rollout sırasında eski ve yeni pod havuzları **birlikte** açıktır (tek replikada 2 × 30 + 25 = 85, iki
  replikada 3 × 30 + 25 = 115). PgBouncer varsa yalnız `session` kipinde ([`db-scaling.md`](db-scaling.md) §3).
- [ ] `NO_PROXY` → master.yaml'daki sonek listesi (son bilinen canlı değer boş): listedeki iç hedefler artık vekile
      girmeden **doğrudan** gider. Egress kuralları buna izin veriyor mu?
- [ ] `CORS_ALLOWED_ORIGINS` → master.yaml'daki köken (son bilinen canlı değer boş).
- [ ] Vekil host/port, `whoisEnabled=false`, ingress host/TLS secret adı: `prod-values-all-before.yaml` ile aynı mı?

### Kaynak ve küme
- [ ] Bellek **3Gi limit / 1.5Gi request** (CPU değişmedi: 500m / 2000m). Namespace kotası rollout penceresinde
      **replika + 1** pod'u kaldırmalı (tek replikada eski + yeni = 2, iki replikada 3):
  ```bash
  kubectl describe resourcequota -n "$NS"
  kubectl describe limitrange -n "$NS"
  kubectl get pod -n "$NS" -l app.kubernetes.io/instance="$REL" \
    -o jsonpath='{..lastState.terminated.reason}{"\n"}'     # OOMKilled geçmişi var mı?
  ```
  Kota ya da node yetmezse yeni pod `Pending` kalır ve `--atomic` zaman aşımında geri alır.
- [ ] Registry erişimi: küme `$IMAGE_REPO:v$VERSION`'ı çekebiliyor (özel paket → `imagePullSecrets`).
- [ ] `/var/log` emptyDir'i DEBUG hacmini taşıyabilir: log dosyaları toplam ≈1,2 GB ile sınırlı
      (FILE 500 MB, ERROR 200 MB, AUDIT 500 MB); node'un ephemeral-storage payı buna yetmeli.

### Log seviyesi kararı
- [ ] Prod **DEBUG** ile koşar — bu kullanıcının **bilinçli** kararıdır; komut `--set config.logLevel=DEBUG`
      taşır. Bu bayrak düşerse prod sessizce `master.yaml`'daki INFO'ya döner. Yeniden başlatmasız alternatif:
      Ayarlar → Loglama (DB override env'i ezer).

### Uygulama içi ön kontroller (UI)
- [ ] Ayarlar → SMTP: kayıtlı bir satır var mı? Yoksa mail **env'den** (Secret'taki `SPRING_MAIL_*`) gelir ve
      Secret'ın doğru olması şarttır.
- [ ] Ayarlar → Genel: `site.monitor.system-admin.email` dolu; kurumsal CA paketi
      (`site.monitor.trust.ca-bundle-pem`) DB'de dolu (master.yaml'da `caBundlePem` yok).
- [ ] Yapılandırma sağlığı kartı yeşil.
- [ ] NetScaler / ingress zaman aşımı: manuel sayfa kontrolü 120 sn'ye kadar sürebilir; ingress 60 sn'de keser
      (bilinen, P3-5). Kabul edildi ya da zaman aşımı ≥ 130 sn.

## Sırası — tek komut

```bash
git checkout "v${VERSION}"        # chart + environments/master.yaml bu sürümün kendisi

# 1) Kuru koşu: render edilen image satırını ve ConfigMap'i okuyun (existingSecret ile Secret basılmaz)
helm upgrade --install "$REL" ./helm/site-monitor --namespace "$NS" \
  -f helm/site-monitor/values.yaml \
  -f helm/site-monitor/environments/master.yaml \
  -f "$PRIVATE_VALUES" \
  --set image.repository="$IMAGE_REPO" \
  --set image.tag="v${VERSION}" \
  --set secret.existingSecret="$SECRET" \
  --set config.logLevel=DEBUG \
  --dry-run=server | grep -E 'image:|replicas:|terminationGracePeriodSeconds|sleep [0-9]|DB_POOL_MAX|NO_PROXY|CORS_ALLOWED|LOG_LEVEL|DB_HOST|SPRING_SESSION_STORE_TYPE'

# 2) Gerçek dağıtım — AYNI komut, --dry-run yerine --atomic --wait --timeout
helm upgrade --install "$REL" ./helm/site-monitor --namespace "$NS" \
  -f helm/site-monitor/values.yaml \
  -f helm/site-monitor/environments/master.yaml \
  -f "$PRIVATE_VALUES" \
  --set image.repository="$IMAGE_REPO" \
  --set image.tag="v${VERSION}" \
  --set secret.existingSecret="$SECRET" \
  --set config.logLevel=DEBUG \
  --atomic --wait --timeout 10m
```

- **`--reuse-values` YOK.** Eski değerleri taşımak yerine her değer bir dosyadan gelir; sapma böyle kapanır.
- **`--atomic`**: pod 10 dk içinde hazır olmazsa helm önceki revizyona döner (Helm 4'te adı
  `--rollback-on-failure`; `--atomic` uyarıyla çalışmaya devam eder).
- İçeriği digest'le sabitlemek için: `--set image.digest=sha256:<digest>` (tag korunur, çekim digest'le yapılır;
  eski chart'larda eşdeğeri `--set image.tag="vX.Y.Z@sha256:<digest>"`).
- existingSecret kullanılmıyorsa `--set secret.existingSecret=…` yerine `-f /guvenli/yol/prod-secrets.yaml`
  (tüm `secret.*` anahtarları, **şu anki** değerleriyle).
- Rollout sırasında replika + 1 pod kısa süre birlikte koşar: izleme taramalarını tarama liderliği kirası, diğer
  işleri `scheduler_lock` / küme turları tek pod'a verir. Eski pod preStop (10 sn) + zarif kapanışla (≤ 100 sn, grace 120)
  kapanır ve kirayı hemen bırakır — İLK çok pod sürümüne geçişte değil: o dağıtımda eski pod kendi eski kapanışını kullanır
  (yukarıdaki bir kerelik bölüm). Bu pencerede çift bildirim görülürse not edin ve çift-iş dedektörünü koşun
  ([`RUNBOOK.md` → Çok pod](RUNBOOK.md#çok-pod)).
- **İki ya da daha çok replikada** `--timeout 15m` kullanın: pod'lar sırayla değişir (her biri açılışta ≤ 300 sn
  bekleyebilir); 10 dk yetmezse `--atomic` gereksiz yere geri alır.

## Sonrası

### İlk 15 dakika
> **Günlükler TÜM pod'lardan okunur:** `kubectl logs "$DEPLOY"` yalnız BİR pod'u okur; birden çok pod varken diğerindeki
> hata görünmez. Aşağıdaki komutlar `L` ile çalışır (tek pod'da da aynen geçerli; `--prefix` satırın pod'unu yazar):
> ```bash
> L="-n $NS -l app.kubernetes.io/instance=$REL --prefix --tail=-1"
> ```
- [ ] `kubectl rollout status "$DEPLOY" -n "$NS"` tamam; `/health/readiness` 200.
- [ ] Açılış "ETKİN KONFİGÜRASYON" dökümü (her pod için; `kubectl logs $L | grep -A200 'ETKİN KONFİGÜRASYON'`):
      `maximum-pool-size = 30`, `NO_PROXY` dolu, vekil host dolu, "RDAP istemcisi proxy üzerinden" satırı,
      `site.monitor.secret-key = configured`, `LOG_LEVEL = DEBUG`, sürüm = `$VERSION`.
- [ ] Nav sürüm çipi `$VERSION`; Sistem Sağlığı → Sürüm & Dağıtım'da `env=prod` ve yeni helm revizyonu.
      Ortam adı önceliği: Ayarlar → Genel Ayarlar → **Ortam adı** (DB, boş değilse) > Helm `config.environmentName`
      (`APP_ENVIRONMENT`) > otomatik (pod'da `unknown`); `env=prod` görünmüyorsa önce o alana bakın — oradaki değer
      Helm değerini ezer, boşaltınca Helm değerine döner (yeniden başlatma gerekmez).
- [ ] Şema yamaları: `kubectl logs $L | grep -E 'Schema patch (applied|skipped)|Şema yamaları:'` —
      `applied` bu sürümün eklediklerini listeler; `skipped` satırları (DEBUG'ta görünür) tek tek okunur,
      beklenmeyen bir SQL hatası varsa geri almayı düşünün.
- [ ] Eşzamanlı kurulan indeksler geçerli mi (`CREATE INDEX CONCURRENTLY` — `idx_ae_created_at`, `idx_push_dedupe`; yarıda
      kalan derleme INVALID indeks bırakır ve `IF NOT EXISTS` onu bir daha kurmaz):
      `SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE NOT i.indisvalid;` → boş olmalı.
      Satır dönerse: `DROP INDEX CONCURRENTLY <ad>;` ve pod'u yeniden başlatın (yama indeksi yeniden kurar). **İstisna —
      `ux_…` tekil indeksler:** INVALID / eksik kalmalarının nedeni çift satırdır; düşür-yeniden başlat döngüye girer. Önce
      çiftleri giderin ("İlk çok pod sürümüne geçiş" bölümündeki sorgular), sonra TEK pod'u yeniden başlatın.
- [ ] **Çok pod sürüm 1'in tekil indeksleri** (açılıştan ~5 dk sonra) — ikisi de dolu dönmeli:
      `SELECT to_regclass('ux_push_eventless_key_user'), to_regclass('ux_noe_one_ongoing');`
      `ux_push_eventless_key_user` boşsa: günlükte `kubectl logs $L | grep ux_push_eventless_key_user` (CONCURRENTLY kurulum
      çift satırla düştü) → "İlk çok pod sürümüne geçiş" bölümündeki tekilleştirme UPDATE'i, sonra tek pod'u yeniden
      başlatın (ertelenmiş kurucu açılışta yeniden dener). `ux_noe_one_ongoing` boşsa: `kubectl logs $L | grep -i
      'noe-ongoing\|NetworkOutageOngoingDedupe'` — iş saatlik yeniden dener (pod başına en çok 24 deneme).
- [ ] **Ertelenmiş indeksler (2026-10-08):** filtre indeksleri (`DeferredIndexBuilder.CATALOG` — uptime_checks, audit_log,
      activity_log, alert_events, push / bildirim günlükleri, rollup'lar…) açılışta DEĞİL, pod Ready olduktan sonra arka
      planda `CONCURRENTLY` kurulur; readiness beklemez, büyük tabloda dakikalar sürebilir (sorgular o sırada indekssiz,
      eskisi gibi çalışır). İlerleme: `grep -E 'deferred index built|Ertelenmiş indeks'`; özet satırı
      `Ertelenmiş indeksler: N kuruldu …`. Yarıda kalan (pod öldü) INVALID indeksi bir sonraki açılış kendisi düşürüp
      yeniden kurar — elle müdahale gerekmez. Oturum `application_name = sitemonitor-deferred-index` ile görünür.

### Duman testi (sırları da çözdürür)
- [ ] Yerel admin girişi + bir LDAP kullanıcısı girişi; Ayarlar → LDAP test-bind.
- [ ] Ayarlar → SMTP → test e-postası gerçekten gelir.
- [ ] Özel başlık sırrı taşıyan bir HTTP/Keyword izlemesinde "Şimdi Kontrol Et".
- [ ] Bir senaryo (k6) koşusu; bir alan adı RDAP sorgusu (vekil üzerinden).
- [ ] Bir test alarmı → e-posta + webhook + push üçü de ulaşır.

### Kanaryalar — duman testinden SONRA (sırlar kullanılınca çözülür)
```bash
kubectl logs $L | grep -c "decrypt failed"                                         # 0 OLMALI (bütün pod'lar)
kubectl logs $L | grep -cE "Denetim izinde BOŞLUK|AUDIT fallback"                  # 0 olmalı
kubectl get pod -n "$NS" -l app.kubernetes.io/instance="$REL" \
  -o custom-columns=POD:.metadata.name,RESTART:.status.containerStatuses[0].restartCount,SEBEP:.status.containerStatuses[0].lastState.terminated.reason
                                                                                   # her pod RESTART 0, SEBEP <none> (OOMKilled yok)
```
- `decrypt failed` > 0 → anahtar değişmiştir: **hemen** [Geri alma](#geri-alma) ya da önceki anahtarı Secret'a
  geri koyup `kubectl rollout restart`. Sırları yeniden girmek son çaredir (RUNBOOK).

### İlk 24 saat
- [ ] `restartCount` 0, `OOMKilled` yok; bellek çalışma kümesi < limitin %85'i
      (`kubectl top pod -n "$NS"`); aşıyorsa RUNBOOK → Bellek.
- [ ] 5xx oranı, sweep süreleri (Sistem Sağlığı), Bildirim Geçmişi'nde FAILED artışı yok, push outbox birikmiyor,
      Postgres bağlantı sayısı `max_connections`'ın altında rahat.

## Çok pod (1 → 2 replika)

> Çok pod sürüm 1 (2026-10-21). Prod'u iki pod'a almak **iki ayrı dağıtımdır**: önce yeni sürüm TEK pod'la dağıtılıp
> doğrulanır, sonra AYNI imajla yalnız replika sayısı değişir. Sürüm değişikliği ile replika artışı aynı `helm upgrade`'de
> yapılmaz (iki yeni sürüm pod'u aynı anda açılıp açılış tohumlamasında yarışır). İşleyiş, devir süreleri ve kilitler:
> [`RUNBOOK.md` → Çok pod](RUNBOOK.md#çok-pod).

### 0. Önce staging
- [ ] Staging (`environments/release.yaml`) sabit **2 replika**, HPA kapalı koşuyor; yeni sürüm orada en az 24-48 saat
      koştu ve çift-iş dedektörü her gün temiz (aşağıdaki 4. adım, staging veritabanına karşı).
- [ ] HPA kaldırıldıktan sonra staging gerçekten 2 pod: `kubectl get deploy,hpa -n <staging-ns>` → Deployment `2/2`, HPA
      yok. Eski HPA'nın büyüttüğü sayı kaldıysa (Helm 4 sunucu tarafı uygulamada alan sahipliği HPA'da kalabilir):
      `kubectl scale deploy/<ad> -n <staging-ns> --replicas=2`; HPA nesnesi durduysa `kubectl delete hpa/<ad> -n <staging-ns>`.
- [ ] (Önerilen) Yeni imajla iki pod duman testi koşuldu: `SMOKE_IMAGE=<imaj> bash scripts/multipod-smoke.sh`
      (Docker; tek lider, A'da açılan oturum B'de geçerli, lidere SIGTERM → devir ≤ 40 sn, çift iş yok).

### 1. Ön koşullar (P1-P5) — her biri kanıtıyla
- [ ] **P1 — Her node'dan aynı çıkış.** Pod'ların koşabileceği her node'da vekil (RDAP), SMTP, LDAP / AD, push ağ
      geçidi ve doğrudan kontrol edilen iç hedefler erişilebilir:
      ```bash
      NS="$NS" REL="$REL" TARGETS="<ldap-host>:636 <push-host>:443 <iç-hedef>:443" \
        bash scripts/multipod-egress-check.sh <node-1> <node-2> <node-3>      # çıkış kodu 0 olmalı
      ```
      Bir node başarısızsa: kural düzeltilir ya da pod'lar sağlam node'lara sabitlenir (`master.yaml` → `nodeSelector` /
      `affinity`). Bu yapılmadan ikinci pod açılmaz — ilk devirde toplu "kesinti" alarmı ya da sessiz posta kaybı olur.
- [ ] **P2 — Saat.** Egress betiğinin yazdığı node saatleri (`saat <epoch>`) birbirinden ve DB saatinden
      (`SELECT extract(epoch from now());`) en çok 1-2 sn farklı.
- [ ] **P3 — DB bağlantıları.** `SHOW max_connections;` ≥ `3 × dbPoolMax + 25` (2 replika + rollout'ta 1 ek pod).
      `dbPoolMax` 30 → ≥ 115 (pratikte 120); `max_connections` 100 ise `dbPoolMax: "25"` (3 × 25 + 25 = 100).
      PgBouncer varsa kipi `session` (DBA'ya sorun; `transaction` kipi uyumsuz). Formül: [`db-scaling.md`](db-scaling.md) §3.
- [ ] **P4 — Kota.** `kubectl describe resourcequota -n "$NS"` → 3 pod sığar (prod: 3 × 1.5Gi / 500m request,
      3 × 3Gi / 2 CPU limit); en az bir node'da yeni pod'a yer var.
- [ ] **P5 — Prometheus.** Kazıma `role: pod` ile pod başına (Service üzerinden değil); iki pod'da
      `up{job="site-monitor"}` iki seri; `sum(sitemonitor_sweep_leader) == 1` (iş ölçümleri belirteçli
      `/metrics/sitemonitor` ucundadır — belirteç yoksa liderliği Sistem Sağlığı'ndan okuyun).

### 2. Yeni sürüm TEK pod'la
- [ ] Yukarıdaki "Sırası — tek komut" ile, `replicaCount: 1` (master.yaml'ın bu sürümdeki değeri) dağıtıldı ve
      "Sonrası" kontrolleri temiz. Tek pod davranışı değişmez; yeni olan: grace 120 sn, preStop 10 sn.

### 3. Replika 1 → 2 (AYRI commit + `helm upgrade`, aynı imaj)
- [ ] `environments/master.yaml`: `replicaCount: 2`, `podDisruptionBudget.enabled: true` (`minAvailable: 1`),
      `config.dbPoolMax` P3'e göre. `topologySpreadConstraints` yalnız ≥ 3 zamanlanabilir node varsa açılır
      (`matchLabelKeys: ["pod-template-hash"]`, Kubernetes ≥ 1.27; daha az node'da `whenUnsatisfiable: ScheduleAnyway`).
      `autoscaling.enabled: false` kalır. `config.sessionStoreType` `jdbc` (değilse `helm template` reddeder).
- [ ] Kuru koşu: `--dry-run=server` çıktısında `replicas: 2`, `PodDisruptionBudget`, `SPRING_SESSION_STORE_TYPE: "jdbc"`,
      `terminationGracePeriodSeconds: 120`, preStop `sleep 10`.
- [ ] Aynı komut, aynı `image.tag` (sürüm DEĞİŞMEZ), `--atomic --wait --timeout 15m`.

### 4. Sonrası (iki pod)
- [ ] `kubectl get pod -n "$NS" -l app.kubernetes.io/instance="$REL" -o wide` → iki pod Ready, tercihen farklı node'larda.
- [ ] Sistem Sağlığı → `session_store: jdbc`; Zamanlayıcı → `sweep_leader.owner` TEK pod (sayfayı birkaç kez yenileyin:
      iki pod da aynı sahibi göstermeli; `held_by_me` yalnız birinde true).
- [ ] Grafana: `sum(sitemonitor_sweep_leader) == 1`; Sistem Sağlığı → Metrikler sayfasında iki canlı pod.
- [ ] Giriş yapın, birkaç sayfa gezin: oturum düşmüyor (istekler iki pod'a dağılır).
- [ ] Kanaryalar iki pod'da da temiz (`kubectl logs $L …`, yukarıda); `kubectl logs $L | grep "Tarama liderliği"` →
      bir pod "bu pod'da", diğeri sessiz ya da "başka pod'a geçti".
- [ ] Bir test alarmı → e-posta + push **birer kez** gelir.
- [ ] Bir hafta boyunca her gün çift-iş dedektörü (salt okur):
      ```bash
      PGHOST=<db-host> PGDATABASE=<db> PGUSER=<kullanıcı> PGPASSWORD=… bash scripts/multipod-smoke.sh dup-check
      ```
      `DUP_TOTAL=0` ve `STUCK_TOTAL=0` beklenir; tek tük kontrol çifti (hatadan sonraki doğrulama kontrolü) normaldir —
      satırların anlamı: [`RUNBOOK.md` → Çok pod](RUNBOOK.md#çok-pod).
- [ ] (İsteğe bağlı) Devir provası, düşük trafikli bir saatte: lider pod'u silin (`kubectl delete pod -n "$NS" <lider>`)
      → diğer pod ≤ ~35 sn'de lider olur, izleme kesintisiz devam eder.

### 5. Geri dönüş (2 → 1)
- [ ] `master.yaml`: `replicaCount: 1` **ve** `podDisruptionBudget.enabled: false` (tek replikada `minAvailable: 1`
      node drain'i süresiz bloke eder), aynı komut — ya da `helm rollback "$REL" <2. adımdaki revizyon>`.
      Veri ve şema değişmez; oturumlar JDBC'de kalır.

## Geri alma

**Karar ölçütü:** 15 dk içinde readiness yeşil değil **ya da** e-posta / LDAP / `decrypt failed` kanaryası
kırmızı → geri al. Tartışma sonra.

```bash
helm history "$REL" -n "$NS"                                   # öncekini seçin (Öncesi'nde not edilen)
helm rollback "$REL" <önceki-REVISION> -n "$NS" --wait --timeout 10m
kubectl rollout status "$DEPLOY" -n "$NS"
```

- İmaj ve değerler birlikte döner; `existingSecret` kullanıldıysa Secret'a dokunulmaz.
- Önceki revizyon `--reuse-values` dönemindense eski sapma (pool 10, boş NO_PROXY/CORS) da geri gelir — beklenen.
- Tag'e güvenmiyorsanız (`release.yml` aynı tag'i yeniden basabilir) önceki imajı digest'le sabitleyin:
  önceki sürümün tag'ini checkout edip [Sırası](#sırası--tek-komut)'ndaki komutu
  `--set image.tag="v<önceki>@sha256:<Öncesi'nde kaydedilen imageID digest'i>"` ile koşun. Digest'i etiketin
  içine yazmak **her** chart sürümünde çalışır (`repo:tag@sha256:…`); ayrı `image.digest` değeri yalnız
  v20.86.0'dan sonraki chart'larda vardır.
- **Şema:** yamalar yalnız ekler (`ADD COLUMN` / `CREATE TABLE`, `ddl-auto=update`) → eski sürüm yeni şemada
  açılır; yeni kolonlara yazılan veri eski sürümde görünmez ama kaybolmaz. İstisna: varsayılansız `NOT NULL`
  kolon eski sürümün INSERT'ünü kırar — sürümün CHANGELOG'unda böyle bir şema notu varsa geri almadan önce okuyun.
  Çok pod sürüm 1'in iki tekil indeksi (`ux_push_eventless_key_user`, `ux_noe_one_ongoing`) geri almada kalır ve silinmez:
  eski sürümün yazımları önce "var mı?" diye bakar ve tekil ihlalini yakalar (ayrıntı: [`RUNBOOK.md` → Geri alma](RUNBOOK.md#geri-alma-rollback)).
- **Secret key bu dağıtımda değiştiyse** rollback kurtarmaz: yeni anahtarla şifrelenen girişler eski anahtarla
  çözülemez. Bu yüzden anahtar değişimi hiçbir yayınla birlikte yapılmaz.
- Son çare: DB yedeğinden geri yükleme (veri kaybı: yedekten sonraki kayıtlar) — platform ekibiyle.

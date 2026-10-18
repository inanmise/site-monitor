# Grafana: Sürüm Anotasyonu, Dağıtım Alarmı ve DORA Dağıtım Sıklığı

Site Monitor her pod açılışında kendi build kimliğini Prometheus'a iki ölçümle verir
(`BuildInfoMetrics`). Bu belge o iki ölçümü Grafana'da **"grafikte dikey çizgi olarak hangi sürüm
ne zaman devreye girdi"**, **"sürüm değişince uyar"** ve **"haftada kaç dağıtım yaptık"** sorularına
bağlar. Uygulama tarafında ek ayar gerekmez; ölçümler her zaman açıktır.

> **Hazır pano (2026-10-18):** Ayarlar → Platform → **Grafana panosu** sayfası, bu anotasyonu ve izleme durumu,
> alarmlar, sertifikalar, SiteMonitor sağlığı panellerini içeren içe aktarılabilir pano JSON'unu indirir (kılavuz §15.10,
> çalıştırma kılavuzu "Grafana panosu ve Prometheus"). Bu belge kendi panonuzu elle kurmak içindir.

## 1. Ölçümler

| Ölçüm (Prometheus adı) | Tür | Etiketler | Anlamı |
|---|---|---|---|
| `sitemonitor_build_info` | gauge, sabit `1` | `version`, `commit`, `environment` | Koşan örneğin build kimliği. `version`/`commit` süreç ömrü boyunca **sabittir**; sürüm değişince eski seri kaybolur, yeni seri doğar. `environment` ortam adı ayarını izler (aşağıda). |
| `sitemonitor_deployment_started_seconds` | gauge | — | Bu örneğin JVM başlangıcı (epoch saniye). "Bu pod ne zamandır ayakta" ve dağıtım anı buradan okunur. |

Etiket değerleri boşsa `unknown` yazılır (compose/yerel koşum). `commit` kısa (8 karakter) SHA'dır;
`environment` etkin ortam adıdır (`dev` / `staging` / `prod`). Öncelik: Ayarlar → Genel Ayarlar → **Ortam adı**
(DB, boş değilse) > Helm `APP_ENVIRONMENT` > otomatik (pod'da `unknown`, pod dışı koşumda `local`). Ayar
yeniden başlatmasız değiştirilir; etiket de aynı pod'da hemen, diğer pod'larda ayar önbelleği tazelemesiyle
(~10 sn) yenilenir — eski etiketli seri kaybolur, yeni adla tek seri kalır.

Micrometer adı `sitemonitor.build.info` → Prometheus'ta `sitemonitor_build_info` olur (nokta → alt çizgi).

### Nereden kazınır

Actuator ana portta, kök yolda çalışır (`management.endpoints.web.base-path=/`) ve Prometheus ucu
`/metrics` olarak eşlenmiştir (`management.endpoints.web.path-mapping.prometheus=metrics`). Yani
Prometheus hedefi: `http://<pod-ip>:8080/metrics`. Uç NetworkPolicy ile yalnız küme içine açıktır;
dışarıdan kazınmaz.

Hızlı kontrol (pod içinden):

```sh
curl -s http://localhost:8080/metrics | grep -E '^sitemonitor_(build_info|deployment_started_seconds)'
# sitemonitor_build_info{commit="0123abcd",environment="prod",version="20.54.0"} 1.0
# sitemonitor_deployment_started_seconds 1.757577600E9
```

## 2. Sürüm anotasyonu (grafikte dikey çizgi)

Grafana → Dashboard settings → **Annotations** → *Add annotation query*:

| Alan | Değer |
|---|---|
| Data source | Prometheus |
| Query | `count by (version, commit, environment) (sitemonitor_build_info{environment="prod"}) unless count by (version, commit, environment) (sitemonitor_build_info{environment="prod"} offset 5m)` |
| Title | `Sürüm {{version}}` |
| Text | `{{version}} ({{commit}}) — {{environment}}` |
| Tags | `deploy` |
| Series value as timestamp | kapalı |

Neden `unless … offset 5m`: `sitemonitor_build_info` sabit `1`'dir; değer hiç değişmez, sürüm değişince **yeni etiket
kombinasyonu** ile yeni bir seri başlar. Sorgu "şu an görülen ama 5 dakika önce görülmeyen" sürüm etiketlerini verir —
yeni sürüm ilk 5 dakikasında anotasyon üretir, eski sürüm kaybolduğu için çift anotasyon olmaz, aynı sürümle yeniden
başlatma (etiketler aynı, 5 dakika önce de vardı) anotasyon üretmez. `count by (…)` aynı sürümü birden çok podda doğan
rolling update'i tek anotasyona indirir.

> **Düzeltme (2026-10-18):** Bu belgenin önceki sürümü `changes(sitemonitor_build_info[10m]) > 0` öneriyordu. Bu ifade
> hiç tetiklenmez: `changes()` bir serinin pencere İÇİNDEKİ değer değişimlerini sayar, sabit `1` olan yeni doğmuş bir
> seri için sonuç `0`'dır ("yoktan var olma" değişim sayılmaz). Kendi panonuzda / kuralınızda o ifadeyi kullandıysanız
> aşağıdaki `unless … offset` biçimiyle değiştirin. Hazır pano (Ayarlar → Platform → Grafana panosu) bu biçimi kullanır.

Pencere (`offset 5m`) panonun adımından (step) büyük olmalı: Grafana uzun aralıklarda adımı büyütür (30 günde ≈ 4 dk);
adım pencereden büyükse dağıtım iki örnek arasında kalıp kaçabilir.

## 3. Alarm kuralı: sürüm değişti / beklenmeyen geri alma

Grafana **Alert rule** (veya Prometheus `rules.yml`):

```yaml
groups:
  - name: sitemonitor-deploy
    rules:
      # Bilgi: yeni sürüm devreye girdi (anotasyonla aynı ifade).
      - alert: SiteMonitorVersionChanged
        expr: count by (version, commit, environment) (sitemonitor_build_info) unless count by (version, commit, environment) (sitemonitor_build_info offset 10m)
        for: 0m
        labels:
          severity: info
        annotations:
          summary: "Site Monitor {{ $labels.environment }}: sürüm {{ $labels.version }} ({{ $labels.commit }})"
          description: "Yeni build kimliği görüldü. Ayrıntı: Sistem Sağlığı → Sürüm & Dağıtım."

      # Uyarı: aynı ortamda 30 dakika içinde birden fazla sürüm görüldü (yükseltme + geri alma ya da yarım kalan rollout).
      - alert: SiteMonitorVersionFlapping
        expr: count by (environment) (count by (environment, version) (sitemonitor_build_info)) > 1
        for: 30m
        labels:
          severity: warning
        annotations:
          summary: "Site Monitor {{ $labels.environment }}: 30 dakikadır birden fazla sürüm koşuyor"
          description: "Rolling update takılmış ya da bir pod eski imajda kalmış olabilir. `kubectl get pods -o wide` ile imaj etiketlerini karşılaştırın."

      # Uyarı: pod son 5 dakikada yeniden başladı (sürüm değişmeden). Crash-loop'un erken işareti.
      - alert: SiteMonitorRecentRestart
        expr: time() - sitemonitor_deployment_started_seconds < 300
        for: 0m
        labels:
          severity: warning
        annotations:
          summary: "Site Monitor örneği {{ $labels.instance }} 5 dakikadan yeni"
```

Sürüm değişikliği kendi başına bir arıza değildir; `SiteMonitorVersionChanged` **info** seviyesinde
kalmalı ve sayfalama (paging) yerine sohbet kanalına düşmelidir. Geri alma tespiti (semver küçülmesi)
PromQL ile yapılmaz — uygulama bunu kendisi türetir: Sistem Sağlığı → Sürüm & Dağıtım'da
`ROLLBACK` türü ve (açıksa) `site.monitor.deploy.notify.enabled` e-posta bildirimi.

## 4. DORA "dağıtım sıklığı" paneli

Dağıtım = ortamda **yeni bir build kimliğinin** görülmesi (yeniden başlatma değil). Aynı sürümün
birden çok podda doğması tek dağıtım sayılır; `max by (...)` bunu sağlar.

**Haftalık dağıtım sayısı (Stat / Bar chart, `environment="prod"`):**

```promql
count(
  count by (version, commit) (sitemonitor_build_info{environment="prod"})
  unless count by (version, commit) (sitemonitor_build_info{environment="prod"} offset 10m)
)
```

Paneli **Time series** olarak çizip *Query options → Min interval = 10m* verin; sonra
**Transform → Reduce (Total)** ya da doğrudan `sum_over_time` ile haftalık toplamı alın:

```promql
# Seçili zaman aralığındaki toplam dağıtım (dashboard aralığı = $__range)
sum_over_time(
  (
    count(
      count by (version, commit) (sitemonitor_build_info{environment="prod"})
      unless count by (version, commit) (sitemonitor_build_info{environment="prod"} offset 10m)
    )
  )[$__range:10m]
)
```

**Günde/haftada ortalama (DORA ölçüsü):** yukarıdaki toplamı gün sayısına bölün — Stat panelinde
*Unit = none*, *Decimals = 2*:

```promql
sum_over_time((count(count by (version, commit) (sitemonitor_build_info{environment="prod"}) unless count by (version, commit) (sitemonitor_build_info{environment="prod"} offset 10m)))[$__range:10m])
/ ($__range_s / 86400)
```

**Son dağıtımdan bu yana geçen süre (Stat, *Unit = seconds → dhms*):**

```promql
time() - max(sitemonitor_deployment_started_seconds{environment="prod"})
```

`sitemonitor_deployment_started_seconds` etiket taşımadığı için ortam süzgeci uygulanamıyorsa Prometheus
`job`/`namespace` etiketlerini kullanın (kube-prometheus bunları kazıma sırasında ekler):

```promql
time() - max(sitemonitor_deployment_started_seconds{namespace="site-monitor-prod"})
```

## 5. Sınırlar ve notlar

- Ölçümler **yalnız koşan örneğin** kimliğini verir. Prometheus'un saklama süresinden eski dağıtımlar
  metrikten okunamaz; kalıcı kayıt uygulamadaki `deployment_history` tablosudur (Sistem Sağlığı →
  Sürüm & Dağıtım, CSV dışa aktarma; `/api/admin/deployments`).
- `offset` penceresi (10m) alt sorgu adımına (`[$__range:10m]`) eşit olmalı: yeni sürüm tam 10 dakika "yeni"
  görünür, 10 dakikalık adım onu tam bir kez sayar. Pencereyi adımdan küçük yaparsanız dağıtım kaçar, büyük yaparsanız
  iki kez sayılır. Pod 10 dakikadan uzun kapalı kalıp aynı sürümle dönerse bu bir dağıtım sayılır.
- Pod hiç ayağa kalkamazsa (failed-start) metrik de doğmaz; o durum `kube_pod_container_status_restarts_total`
  ve uygulamanın `SYSTEM_STARTUP` denetim izi ile izlenir.
- Etiket kardinalitesi 1'dir (her sürüm tek seri, eski seri kaybolur) — uzun vadede seri şişmesi yaratmaz.
- Ortam adı Genel Ayarlar'dan değiştirilince yeni `environment` etiketli seri doğar: sürüm anotasyonu ve
  "yeni sürüm" alarmı o an **bir kez** tetiklenir (sürüm değişmediği hâlde). Adlandırmayı bakım penceresinde yapın ya
  da o anotasyonu yok sayın; `environment="…"` ile süzen panoların sorgusunu yeni ada göre güncelleyin.
- Gerçek ana bilgisayar adı, küme adı ya da kurum kimliği bu belgeye ve panolara yazılmaz; örneklerde
  `<pod-ip>` ve `site-monitor-prod` gibi yer tutucular kullanılmıştır.

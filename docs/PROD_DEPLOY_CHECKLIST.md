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
- [ ] **Haftalık erişilebilirlik penceresi DEĞİL** (tetik günü — varsayılan Pazartesi — ~10:00–12:00 Europe/Istanbul;
      Ayarlar → Haftalık Erişilebilirlik'teki cron). Bir 20.164.0 pod'u koşarken bu pencerede dağıtılmaz: eski pod bütün
      takımları tek işlemde gönderir, gönderirken öldürülürse kayıtları geri sarılır ve yeni sürümün telafi turu o takımlara
      postayı İKİNCİ kez gönderir.
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
- [ ] Yeni kapanış sırası biliniyor: SIGTERM'de kira bırakılır, sonra push işçisi durdurulup bildirim havuzlarıyla aynı 20 sn
      içinde boşaltılır. Bir pod push batch'i gönderirken ÇÖKERSE o batch ≤ 10 dk sonra başka pod'dan gider (bilinçli: iki kez
      gitmesindense gecikir) — dağıtım sonrası push geçmişinde 10 dk'lık gecikme hata değildir.
- [ ] Geçiş penceresinde (eski + yeni pod birlikte) beklenen yinelenmeler biliniyor: eski pod'un bellekten yönetici kesinti
      postası, eski yolla kapanan fırtınanın ikinci kurtarma postası, eski pod'da sahiplenmesiz push / haftalık rapor —
      [`RUNBOOK.md` → Sürüm geçişi sırasında](RUNBOOK.md#sürüm-geçişi-sırasında-eski--yeni-pod-birlikte).

### 20.165.1 öncesi kontroller (hata düzeltme sürümü) — bir kerelik
Bu bölüm prod 20.165.0 ya da öncesinden 20.165.1'e ilk geçerken geçerlidir; sonraki dağıtımlarda atlanır. Sorgular salt okur.

- [ ] **Anahtar kelime izlemesi çiftleri yok** — yeni tekil indeks `uq_kwm_team_url_kw` = `(team_id, LOWER(url), LOWER(keyword))`
      (uygulamanın kuralı) bu satırlarla kurulamaz; o durumda bir WARN yazılır ve eski `uq_kwm_team_url` yerinde kalır. Boş dönmeli:
  ```sql
  SELECT team_id, lower(url), lower(keyword), count(*) FROM keyword_monitors GROUP BY 1, 2, 3 HAVING count(*) > 1;
  ```
  Satır dönerse dağıtımdan önce takımla konuşup çiftlerden birini silin ya da anahtar kelimesini değiştirin (ekrandan —
  izleme geçmişi ve değişiklik kaydı korunur).
- [ ] **ECDSA / keyEncipherment'sız RSA güven etkisi (CHECKS-1)** — bugün YANLIŞLIKLA "güvenilmiyor" görünen kayıtlar; dağıtımdan
      sonraki ilk kontrolde TRUSTED olurlar. Önce / sonra karşılaştırmak için sayıları not edin:
  ```sql
  SELECT public_key_algorithm, trust_status, count(*) FROM latest_checks WHERE status <> 'error' GROUP BY 1, 2 ORDER BY 1, 2;
  SELECT count(*) FROM latest_checks WHERE public_key_algorithm = 'RSA' AND trust_status = 'UNTRUSTED'
     AND (key_usage IS NULL OR key_usage NOT LIKE '%Key Encipherment%');
  SELECT count(*) FROM alert_events WHERE alert_type = 'UNTRUSTED_CA' AND resolved = false;
  SELECT grade, count(*) FROM tls_grade_status WHERE reasons LIKE '%CERT_UNTRUSTED%' GROUP BY 1;
  ```
  Beklenen: EC satırlarının UNTRUSTED sayısı ilk saatlik kontrolden sonra düşer; açık `UNTRUSTED_CA` alarmları (yalnız
  `site.monitor.trust.alert-untrusted` açıksa var) normal bir çözüm postasıyla kapanır; TLS notları yükselir (bildirim yok);
  otomatik pin bir kez yeniden pinlenir (hata süresince yenilenmiş host'larda tek bir "sertifika değişti" damgası). Takımlara
  "çok sayıda çözüm postası gelebilir" diye önceden duyurun.
- [ ] **Anahtar boyu kuralı (DATA-13)** — EdDSA / ML-DSA ve boyu bilinmeyen (≤ 0) anahtarlar artık "kısa anahtar" sayılmaz;
      zayıf algoritma listesinden çıkacak satırlar (bilgi amaçlı):
  ```sql
  SELECT public_key_algorithm, public_key_size, count(*) FROM latest_checks
   WHERE public_key_algorithm ILIKE '%dsa%' OR public_key_size < 0 GROUP BY 1, 2;
  ```
- [ ] **Geçiş sırasında elle "Şimdi kontrol et" yok** — eski (20.165) ve yeni pod'un birlikte çalıştığı pencerede eski pod'a düşen
      elle sertifika kontrolü EC / keyEncipherment'sız RSA kaydını yeniden UNTRUSTED yazar; sonraki TLS profili turu bunu A → F
      not DÜŞÜŞÜ sayar ve takıma "TLS notu düştü" postası + push gider. Takımlara dağıtım bitene (tüm pod'lar 20.165.1) kadar
      kartlardan / toplu "Şimdi kontrol et" kullanmamalarını duyurun. Sertifika taramaları lidere bağlı DEĞİLDİR (kilidi alan
      pod koşar): dağıtımı saat başından (`:00` saatlik tarama) uzak bir zamana koyun — taramayı eski pod alırsa etki aynıdır.
- [ ] `SELECT count(*) FROM escalation_contacts;` — `webhook_url` TEXT'e genişler (`ALTER COLUMN … TYPE TEXT`); küçük tablo,
      kilit kaygısı yok (sayı binlerle ölçülmüyorsa ek bir şey gerekmez).
- [ ] Davranış değişiklikleri duyuruldu: (1) bir kullanıcının **sistem rolü** gerçekten değiştirilince (elle, yöneticinin "AD'den
      yeniden eşitle"siyle ya da AD grup → rol eşlemesiyle; ve yöneticinin "otomatik parola sıfırlama"sında) o kişinin oturumu
      kapanır, yeni rolüyle yeniden girer; (2) **silinen kullanıcının** açık oturumu ve
      "beni hatırla" çerezi bir sonraki istekte düşer; (3) kanal teknik hata raporu kanalı / şablonu ya da süren bakım penceresi
      olan **takım silinemez** — önce "Taşı"; (4) başında / sonunda boşluk olan yeni parola reddedilir (girişteki kırpma aynı);
      (5) kapsamlı müdür başka takım için **haftalık rapor** açamaz (400, takım adımının altında gerekçe); (6) 443 dışı portta
      kontrol edilen sertifikanın "HTTP durumu" yalnız port HTTP konuşuyorsa (ALPN h2 / http/1.1 ya da bilinen HTTPS portu)
      okunur — LDAPS / SMTPS / IMAPS satırlarında boş kalır; (7) DNS'te çözülemeyen alan adı (NXDOMAIN) toplu ağ kesintisi
      oranına sayılmaz (sınıfı DNS, alarm seviyesi UYARI).

### Çok pod Sürüm 2a öncesi kontroller — bir kerelik
Bu bölüm prod çok pod Sürüm 2a'yı (pod'lar arası önbellek yolu, küme geneli güvenlik sınırları, rapor / hatırlatma gönderim
yollarının sahiplenmesi) İLK kez aldığında geçerlidir; sonraki dağıtımlarda atlanır. Sorgular salt okur (aksi yazılmadıkça).

- [ ] **20.165.0 tekil indeksi `ux_push_eventless_key_user` kurulabilir mi.** İndeksin `created_at >= '2026-10-21'` kesimi
      tarihe bağlıdır: prod 2026-10-21'den sonra hâlâ eski bir sürümle (20.164 ya da öncesi) koştuysa eski pod'lar bu
      tarihten sonra da olaysız push çifti yazmış olabilir. O zaman arka plandaki kurulum HER açılışta düşer (WARN
      `⚠ Şema yaması BAŞARISIZ (ertelenmiş indeks; sonraki açılış yeniden dener): ux_push_eventless_key_user …`, indeks yok;
      yazma yolundaki ön denetim çiftleri yine engeller, yani push iki kez gitmez). Boş dönmeli:
  ```sql
  SELECT dedupe_key, username, COALESCE(team_id, 0) AS team, count(*), min(id) AS ilk, max(id) AS son
    FROM user_push_deliveries
   WHERE alert_event_id IS NULL AND push_trigger NOT IN ('TEST', 'OVERFLOW_SUMMARY') AND created_at >= '2026-10-21'
   GROUP BY 1, 2, 3 HAVING count(*) > 1;
  ```
  Satır dönerse üç yol: **(a)** fazla satırlar geçmiş kaydıdır (gönderilmiş ya da atlanmış push kararları — hiçbir şey
  yeniden gönderilmez), silinebilir; her grubun en eski satırı kalır (bu sorgu YAZAR — önce "Mevcut durumun dökümü"):
  ```sql
  DELETE FROM user_push_deliveries WHERE id IN (SELECT id FROM (
           SELECT id, ROW_NUMBER() OVER (PARTITION BY dedupe_key, username, COALESCE(team_id, 0) ORDER BY id) AS rn
             FROM user_push_deliveries
            WHERE alert_event_id IS NULL AND push_trigger NOT IN ('TEST', 'OVERFLOW_SUMMARY')
              AND created_at >= '2026-10-21') x WHERE rn > 1);
  ```
  **(b)** geçmişi silmeden anahtarı tekilleştirin — [İlk çok pod sürümüne geçiş](#ilk-çok-pod-sürümüne-geçiş-20164-ve-öncesi--bu-sürüm--bir-kerelik)
  bölümündeki `UPDATE`; **(c)** WARN'ı kabul edin (indeks kurulmaz, her açılışta bir WARN; davranış değişmez). (a) ya da
  (b)'den sonra indeks bir sonraki açılışta kurulur (aşağıda "İlk 15 dakika" → tekil indeksler).
- [ ] **`audit_log`'a sütun eklenir** (`seq_locked`, ddl-auto + şema yaması; `ADD COLUMN … BOOLEAN`, varsayılansız —
      PostgreSQL'de yalnız katalog değişikliği). Kısa ACCESS EXCLUSIVE kilidi `audit_log` üzerindeki uzun bir işlemin arkasında
      kuyruğa girebilir: dağıtım sırasında denetim günlüğünde uzun süren SQL Playground / dışa aktarım sorgusu koşmayın. Aynısı
      `executive_summary_reports` / `executive_summary_team_reports` (`send_started_claim`, `send_trace_name`),
      `tech_error_reminders.inflight_at`, `tls_grade_changes.notify_attempts`, `domain_expiry_reminders.claim_token` için de
      geçerlidir (küçük tablolar).
- [ ] **Bu sürümün yeni tekil indeksleri ön kontrol istemez:** `ux_audit_seq` (`WHERE seq_locked IS NOT NULL`) ve
      `ux_der_mon_exp_thr` (`WHERE claim_token IS NOT NULL`) yalnız yeni kodun yazdığı satırları kapsar — eski sürümlerin
      satırları (olası çiftleri dahil) dışarıda kalır, arka plan kurulumunu düşüremez.
- [ ] **Geçiş penceresinde (eski + yeni pod birlikte) beklenenler biliniyor** — [`RUNBOOK.md` → Sürüm geçişi sırasında](RUNBOOK.md#sürüm-geçişi-sırasında-eski--yeni-pod-birlikte):
      önbellek yolu yalnız yeni pod'lar arasında çalışır (eski pod'un önbelleği TTL ile tazelenir), giriş IP sınırı eski
      pod'da hâlâ pod başınadır, eski pod kuyruktaki kanal raporunun üzerine yeniden göndermeye izin verir (409 `SEND_QUEUED`
      yok), eski pod'un denetim kaydı zinciri yeni pod'unkiyle çatallanabilir (bütünlük denetimi bu pencere için yanlış
      "kurcalama" diyebilir).
- [ ] **Geçiş bitene kadar (toplu) kullanıcı pasifleştirmesi yapılmayacak** (MP-51): eski pod ayrık `app_users` varlığını
      hâlâ birleştirerek kaydeder ve eşzamanlı bir pasifleştirmeyi geri açabilir. Geçiş sırasında yapıldıysa, sonra:
      `SELECT username, active FROM app_users WHERE username IN ('<ad-1>', '<ad-2>');` ile pasifleştirilen hesapların
      hâlâ `active = false` olduğunu doğrulayın (toplu işlemde: işlemin önizlemesindeki / sonucundaki kullanıcı adları).
- [ ] Davranış değişiklikleri duyuruldu: (1) kanal teknik hata raporu, önceki gönderimi posta sunucusunun yeniden deneme
      kuyruğundayken (20 dk) yeniden GÖNDERİLMEZ — 409 "Önceki gönderim hâlâ posta kuyruğunda"; gönder penceresi kilitli
      kalıp mevcut kuyruğun sonucunu bekler (iki farklı içerikli kopya gitmesin diye); (2) Veri Saklama Politikaları → "Şimdi
      temizle", gece temizliği ya da başka bir elle koşu sürerken 409 "Temizlik şu an çalışıyor", kilit durumu okunamazsa 503
      döner (ikisinde de hiçbir şey silinmez); (3) bir pod'da kaydedilen ayar / paylaşım / nöbet değişikliği
      öteki pod'larda ~2 sn içinde geçerli olur (eskiden 30 sn – 5 dk); (4) giriş IP bloğu bütün pod'larda ortaktır — elle
      kaldırma: [`RUNBOOK.md` → Takılı kayıtlar ve sınırlar](RUNBOOK.md#takılı-kayıtlar-ve-sınırlar-çok-pod-sürüm-2a).

### Çok pod Sürüm 2b öncesi kontroller — bir kerelik
Bu bölüm prod çok pod Sürüm 2b'yi (Sistem Sağlığı kümeyi gösterir, kendi kendini izleme küme gerçekleriyle + saat uyumu,
yuvarlanan dağıtımda boş sayfa yok, alarm yazımları hedefli, sürüm bildirimi kümede bir kez, chart sertleştirmesi) İLK kez
aldığında geçerlidir; sonraki dağıtımlarda atlanır.

- [ ] **Kota geçici diski kabul ediyor:** chart artık `ephemeral-storage` request 1Gi / limit 4Gi ister (rollout'ta
      replika + 1 pod). Namespace ResourceQuota `requests.ephemeral-storage` / `limits.ephemeral-storage` tanımlıysa yer
      olmalı; LimitRange daha küçük bir tavan koyuyorsa yeni pod `Pending` / reddedilir ve `--atomic` geri alır:
      `kubectl describe resourcequota -n "$NS"; kubectl describe limitrange -n "$NS"`.
- [ ] **DB boşta bağlantısı düşer:** Helm artık `DB_POOL_MIN=5` yazar (önceden uygulama varsayılanı 10). Değiştirmek
      için `config.dbPoolMin` (0 … dbPoolMax).
- [ ] **PDB değeri:** prod'da PDB açıksa `minAvailable` replika sayısından küçük olmalı (`helm template` aksi hâlde
      reddeder) — tek replikada PDB kapalı kalır (`master.yaml`).
- [ ] **İlk dağıtım penceresi:** 20.166.0 pod'larında `boot-guard.js` ve taşınan sayfa dosyaları yoktur; geçiş
      sırasında bir kullanıcı bir kez boş sayfa görebilir — **F5 düzeltir**; destek / 7/24 ekibine önceden söyleyin.
      Sonraki dağıtımlarda eski sekmeler son 5 sürümün taşınan dosyalarıyla çalışır; yeni sürümün dosyası eski pod'a
      düşerse sayfa kendini bir kez yeniler.
- [ ] **İmaj önceki sürümlerin sayfa dosyalarını taşıyor:** release iş akışının derleme günlüğünde `::warning::önceki sürüm
      imajı okunamadı` YOK (varsa imaj yine çalışır ama taşınan dosya 0 olur — dağıtım penceresinde boş sayfa riski sürer).
      Prod'da çalışan sürüm 5'ten çok sürüm geride ise eski sekmeler bir kez yenilenir (beklenen).
- [ ] **Staging:** `topologySpreadConstraints.matchLabelKeys` Kubernetes ≥ 1.27 ister (1.25–1.26'da alfa / kapalı, ≤ 1.24'te
      Helm doğrulama hatası) — `kubectl version` ile doğrulayın; eskiyse staging için `matchLabelKeys: []` +
      `whenUnsatisfiable: ScheduleAnyway`.
- [ ] **Geri alma yalnız `helm rollback` ile** (`kubectl rollout undo` sürüm geçmişini şaşırtır: ROLLBACK satırı / bildirimi
      düşmez — [`RUNBOOK.md` → Geri alma](RUNBOOK.md#geri-alma-rollback)).
- [ ] Geçiş penceresinde (eski + yeni pod birlikte) beklenenler biliniyor — [`RUNBOOK.md` → Sürüm geçişi sırasında](RUNBOOK.md#sürüm-geçişi-sırasında-eski--yeni-pod-birlikte):
      eski pod alarmı hâlâ bütün varlıkla kaydeder (yeni pod'un kapattığı bir alarmı yeniden açabilir — son eski pod
      gidince biter), eski pod heartbeat'i pod adı olmadan yazar, tarama kartı bir süre eski pod'un koşusunu göstermeyebilir.
- [ ] **Grafana panosu:** revizyon 4 (sağlık bölümünün sonunda çok pod çalışma panelleri) — dağıtımdan sonra Ayarlar →
      Grafana panosu → JSON indir ve aynı UID ile içe aktarın.

### Çok pod Sürüm 3-1 öncesi / sonrası kontroller — bir kerelik
Bu bölüm prod çok pod Sürüm 3-1'i (uzun yan işler tarama liderinden alınır, TLS profili her pod'da, kurum geneli Grafana
ölçülerini takipçi yayar, alarm değerlendirme durumu dört tabloya aynalanır + doğrulanır — kararlar yine bellekten) İLK kez
aldığında geçerlidir; sonraki dağıtımlarda atlanır. Ayrıntı: [`RUNBOOK.md` → Yan işler](RUNBOOK.md#yan-işler-ve-lider-ertelemesi-çok-pod-sürüm-3-1),
[Kalıcı değerlendirme durumu](RUNBOOK.md#kalıcı-değerlendirme-durumu-çok-pod-sürüm-3-1).

**Öncesi**
- [ ] **Şema: dört yeni, boş tablo açılışta kurulur** — ön kontrol / yedek gerekmez, mevcut tablolara sütun eklenmez. İlk yeni
      pod Ready olunca `EvalStateSchemaPatch`: `monitor_eval_state`, `monitor_last_status`, `monitor_eval_type_state`,
      `monitor_confirmations` + `idx_mls_type_domain` (tabloyla birlikte, boşken); PostgreSQL'de üçüne `fillfactor = 70`.
      Uygulama kullanıcısının `CREATE TABLE` hakkı olmalı (öteki şema yamaları gibi).
- [ ] **Veritabanı yazım yükü kabul edilebilir:** lider, değişen değerleri saniyede bir toplu yazar (izleme başına son sonuç
      değişince ya da 2 dk'da bir) — bugünkü ölçeğin 3 katında tahmin ≈ 100 upsert/sn (≈ 12 bin kalem ÷ 120 sn), günde
      3–5 GB WAL; toplu kesintide kısa patlamalar; her ifadenin 15 sn sorgu zaman aşımı var ([`RUNBOOK.md` → Kalıcı
      değerlendirme durumu → Kapatmak](RUNBOOK.md#kalıcı-değerlendirme-durumu-çok-pod-sürüm-3-1)). Sorun
      olursa canlı kapatma: Ayarlar → Genel → İzleme Alarmları → `site.monitor.eval-state.mode` = `off` (yalnız GLOBAL admin;
      alarm kararları etkilenmez).
- [ ] **İki pod'da P1 çıkış eşitliği TLS profilini de kapsar:** TLS profili yoklaması artık herhangi bir pod'dan çıkar
      (sertifika taramalarıyla aynı hedefler); `scripts/multipod-egress-check.sh` her `TLS_TARGETS` hedefine doğrudan TLS el
      sıkışması dener — her node'da `TLS OK` ve aynı `veren`. Tek pod'da etkisi yok.
- [ ] **Geçiş penceresinde beklenenler biliniyor** — [`RUNBOOK.md` → Sürüm geçişi sırasında](RUNBOOK.md#sürüm-geçişi-sırasında-eski--yeni-pod-birlikte):
      eski lider + yeni takipçide dönem başına en çok bir fazla TLS tıkı; eski pod kalıcı durumu yazmaz; yeni lider, eski
      takipçinin heartbeat'ini görünce cron yan işlerini 20 sn erteler (eski takipçi alır — çift iş yok); eski pod tamamlanma
      damgası yazmadığı için yeni lider bayat taramayı / yönetici özeti telafisini ona bırakmaz (20.167.0'daki gibi yarışırlar);
      olay aksiyonu hatırlatmasında susturulmuş bakım gününde sahip başına bir fazla `SKIPPED` satırı olabilir (posta değil).
- [ ] **Ayarlar:** iki yeni GLOBAL_ONLY anahtar — `site.monitor.eval-state.mode` (`verify`; env `EVAL_STATE_MODE`) ve
      `site.monitor.scheduler.leader-yield.enabled` (açık; env `SCHEDULER_LEADER_YIELD_ENABLED`); açılış anahtarları
      `site.monitor.scheduler.side-jobs.threads` (8, env `SCHEDULER_SIDE_JOBS_THREADS`; uzun iş şeridi — daha küçük değer 8'e,
      zamanlayıcı havuzu `SCHEDULING_POOL_SIZE` daha büyükse ona yükseltilir, en çok 32, 0 = satır içi; ayrıca liderin ertelenen
      kısa cron denemeleri için 5 iş parçacıklı sabit kısa şerit) ve `site.monitor.scheduler.leader-yield.cron-defer-ms`
      (20000, en çok 25000). Helm değeri gerekmez (varsayılanlar `application.properties`'te).
- [ ] **Davranış değişiklikleri duyuruldu** (iki pod'da): (1) saatlik sertifika taraması, gece temizliği, raporlar ve
      hatırlatmalar çoğunlukla TAKİPÇİ pod'da koşar (günlükte o pod'un adıyla); tetiğe göre en çok 20 sn gecikebilir (cron payı
      içinde); (2) Sistem Sağlığı → Küme kartında yeni bölümler: "Kalıcı değerlendirme durumu" (Küme), "Yanıtlayan pod" altında
      "Değerlendirme kaydı" ve "Yan işler"; (3) Grafana kurum ölçülerini takipçi yayar (lider yalnız kendi pod ölçülerini);
      tek pod'da bayat tarama / TLS profili kadansı 20.167.0 ile aynı ("bitiş + gecikme").
- [ ] **Grafana panosu:** revizyon 6 (sağlık bölümünün sonunda kalıcı değerlendirme durumu ve kurum ölçüsü yerleşimi
      panelleri; "Veri tazeliği" yalnız kurum ölçülerini yayan pod'ları sayar) — dağıtımdan sonra Ayarlar → Grafana panosu →
      JSON indir ve aynı UID ile içe aktarın.
- [ ] **Prometheus her pod'u ayrı kazıyor** (`role: pod`, Service üzerinden değil) — Sürüm 3-1'den beri iki pod'da iş ölçümleri
      (`/metrics/sitemonitor`) yalnız takipçide; yük dengeli kazımada iş serileri gidip gelir.

**Sonrası** (açılıştan ~15 dk sonra; günlükler TÜM pod'lardan — aşağıdaki "İlk 15 dakika" `L`)
- [ ] Tablolar kuruldu — dördü dolu dönmeli:
      `SELECT to_regclass('monitor_eval_state'), to_regclass('monitor_last_status'), to_regclass('monitor_eval_type_state'), to_regclass('monitor_confirmations');`
      Günlükte her pod'da bir kez `Kalıcı değerlendirme durumu kaydedicisi çalışıyor`, ilk yeni pod'da
      `Kalıcı değerlendirme durumu şeması: N değişiklik uygulandı`, lider pod'da `Kalıcı değerlendirme durumu: kuşak N alındı`
      (`kubectl logs $L | grep "Kalıcı değerlendirme"`).
- [ ] Kuşak satırını lider yazıyor ve nabız taze:
      `SELECT gen, writer, to_timestamp(updated_ms / 1000.0) FROM monitor_eval_type_state WHERE alert_type = '*';` → `writer`
      = Küme kartındaki tarama lideri (`<pod adı>-<8 hane>`), zaman en çok ~60 sn önce.
- [ ] Sistem Sağlığı → Zamanlayıcı ve yürütücüler → Küme kartı → "Kalıcı değerlendirme durumu": ilk ~5 dk "henüz doğrulama
      yok", sonra bir tur "bilinmiyor — eşitleniyor" (normal), ~10-15 dk'da **"bellek ve tablolar uyuşuyor"**. "Yanıtlayan pod"
      lider iken "Değerlendirme kaydı" = "yazıyor (kuşak N)", yazma hatası 0, "son yazım başarısız" uyarısı yok, çitlenme
      uyarısı (lider ama yazma kuşağı başka pod'da) yok, kanca hatası 0 ya da hiç gösterilmiyor.
- [ ] Kanaryalar: `kubectl logs $L | grep -c "Kalıcı değerlendirme doğrulaması"` → 0 (uyuşmazlık WARN'ı);
      `kubectl logs $L | grep -c "Kalıcı değerlendirme durumu yazılamadı"` → 0; `kubectl logs $L | grep "Yan iş .* başarısız"` → boş.
- [ ] Tek pod: "Yan işler" satırı "başka canlı pod yok" (ya da "son bakışta başka canlı pod yoktu" / "henüz bakılmadı"),
      ertelenen / bırakılan 0 (bugünkü yerleşim). İki pod: aşağıdaki
      "Çok pod (1 → 2 replika)" → 4. adım.
- [ ] Bir hafta boyunca günlük: Küme kartı "uyuşuyor", Grafana "Doğrulama uyuşmazlığı (tür)" 0 — 3-2 geçiş ölçütü için
      kayıt tutun ([RUNBOOK → Kalıcı değerlendirme durumu](RUNBOOK.md#kalıcı-değerlendirme-durumu-çok-pod-sürüm-3-1)).

**Geri alma:** `helm rollback` yeterli. Dört tablo, `round:tls-profile` / `round:incident-action-reminder` / `round:<tur>:done`
satırları, `cluster_observations` satırları
`eval-state-verify` / `metrics-org` ve iki ayar anahtarı kalır, eski kod kullanmaz (dış anahtar / tetikleyici yok — silmeyin,
yeniden yükseltmede kaldığı yerden yazılır); eski sürümde TLS profili yine yalnız liderde koşar ve her pod kurum ölçülerini
yayar.

### Çok pod Sürüm 3-2 — bir kerelik
Bu bölüm prod çok pod Sürüm 3-2'yi (yeni tarama lideri devirde kalıcı değerlendirme durumunu YÜKLER — yeni bir ayarın
arkasında, varsayılan KAPALI) İLK kez aldığında geçerlidir; sonraki dağıtımlarda yalnız "Açıldıktan sonra" maddesi kalır.
Ayrıntı: [`RUNBOOK.md` → Kalıcı değerlendirme durumunu devirde okuma](RUNBOOK.md#kalıcı-değerlendirme-durumunu-devirde-okuma-çok-pod-sürüm-3-2).

**Öncesi**
- [ ] **Dağıtım ayar KAPALIYKEN yapılır** (varsayılan: `site.monitor.eval-state.read-enabled` = false, env
      `EVAL_STATE_READ_ENABLED` verilmez). Sürüm değişikliği ile ayarı açmak aynı adımda YAPILMAZ — kapalıyken davranış
      20.168.1 ile birebir aynıdır (kararlar, bildirimler, kaydedicinin SQL ifadeleri, kapanış, teyit listesi, günlük).
- [ ] **Şema:** `monitor_eval_type_state`'e üç boş, NULL olabilir `BIGINT` sütun eklenir (`state_gen`, `flushed_ms`,
      `handover_ms`; `ADD COLUMN IF NOT EXISTS`, 20 sn kilit beklemesi, küçük tablo) — ön kontrol / yedek gerekmez. Eski
      (20.168.x) pod'ların ifadeleri sütun listesi verdiğinden etkilenmez.
- [ ] **Kapanış bütçesi:** Helm `terminationGracePeriodSeconds` 120 değişmedi (bütçe 101 sn = preStop 10 + uygulama 91;
      devir boşaltması yalnız ayar açıkken ve en çok 1 sn).

**Sonrası** (açılıştan ~15 dk sonra; günlükler TÜM pod'lardan — aşağıdaki "İlk 15 dakika" `L`)
- [ ] Sütunlar eklendi: `SELECT column_name FROM information_schema.columns WHERE table_name = 'monitor_eval_type_state'
      AND column_name IN ('state_gen', 'flushed_ms', 'handover_ms');` → üç satır.
- [ ] Ayar kapalıyken iz yok: `kubectl logs $L | grep -cE "devirde yüklen|Devir öncesi kalıcı durum"` → 0; `'*'` satırında
      `state_gen` boş kalır (`SELECT gen, writer, state_gen, flushed_ms, handover_ms FROM monitor_eval_type_state WHERE
      alert_type = '*';`); doğrulama "bellek ve tablolar uyuşuyor" sürer; Küme kartı "Devirde yüklenen durum: Kapalı".
- [ ] Takipçi pod yanıtlarken bir Sayfa izlemesinin "Teyit denemesi X/N" listesi ayar kapalıyken boş kalır (bugünkü davranış)
      — beklenen.

**Açma — ayrı bir adım** (RUNBOOK → Devirde okuma → "Açma ölçütü" + "Açmak")
- [ ] Ölçüt karşılandı: iki prod pod'unda ≥ 7 gün doğrulama 0 uyuşmazlık (bilinmeyen turlar hariç), yazma hatası < %0,1,
      "sürdürülemeyecek zincir" 0, "bastırma penceresi tohumu" karar farkı < %1, en az bir gerçek liderlik değişiminde
      devralınan sayılar > 0; kip `verify`.
- [ ] **TÜM pod'lar 3-2 (ya da üstü) sürümünde** — rollout bitti, eski ReplicaSet'in pod'u kalmadı (`kubectl get pods -n
      "$NS" -l app.kubernetes.io/instance="$REL"` hepsi aynı imaj). 20.168.x bir takipçi devir aralığındaki "Şimdi kontrol
      et" sonucunu yerel değerlendirir; sayaç sıfırlaması yeni lidere ulaşmaz (RUNBOOK → Devirde okuma → R-6).
- [ ] Önce staging: ayar açılır (Ayarlar → Genel → İzleme Alarmları → "Devirde kalıcı değerlendirme durumunu yükle (çok
      pod)", yalnız GLOBAL admin), lider pod yanıtlarken "Devir hazırlığı: Hazır — tam devir mümkün"; zorlanmış devir
      (`kubectl rollout restart deploy/<ad>` ya da lider pod'u silmek) → yeni lider yanıtlarken "Devirde yüklenen durum: Tam
      (temiz devir)", sonraki doğrulama turları "eşitleniyor" → "uyuşuyor"; günlükte eski liderde `Devir öncesi kalıcı durum
      boşaltıldı ve işaretlendi`, yeni liderde `Kalıcı değerlendirme durumu devirde yüklendi (tam)`. Kart "Güncel değil"
      (gri) derse rapor şimdiki liderden başka pod'undur (yeni lider yükleme yayımlamadı — ayar / kip kapalıydı ya da eski
      sürüm lider); lider yanıtlarken "Bu pod" satırına bakın.
- [ ] Sonra prod, aynı kontrollerle. Geri dönüş: ayarı kapatmak anında güvenlidir (sonraki devir bugünkü gibi).

**Açıldıktan sonra — her prod dağıtımında**
- [ ] Yeni lider yanıtlarken Küme kartı "Devirde yüklenen durum: Tam (temiz devir)". "Yalnız gözlemler" bir çökme / zorla
      öldürmeden sonra normaldir — devir sırasında biri "Şimdi kontrol et"e bastıysa da ("Yükleme anında değerlendirilmemiş
      elle kontrol sonucu vardı"); başka bir sonuç (turuncu neden ya da "Yüklenmedi") görülürse nedenini kaydedin ve RUNBOOK →
      Devirde okuma → "İzlenecekler"e bakın. `kubectl logs $L | grep -c "devirde yüklenmedi"` → 0 beklenir.
- [ ] Doğrulama devirden sonra yine "uyuşuyor"; uyuşmazlık > 0 → ayarı kapatın, sonra inceleyin.

**Geri alma:** `helm rollback` yeterli; ayar anahtarı, üç sütun ve `cluster_observations` satırı `eval-state-load` kalır,
eski kod yok sayar (silmeyin); kapanış 1 sn kısalır.

### Çok pod Sürüm 3-3 — bir kerelik
Bu bölüm prod çok pod Sürüm 3-3'ü (dağıtık kontroller — tarama lideri port, ping, HTTP, içerik, sayfa, sayfa hızı, DNS ve
senaryo izlemelerinin zamanlanmış kontrollerini pod'lara dağıtır, sonucu yine kendisi değerlendirir; yeni bir ana anahtarın
arkasında, varsayılan KAPALI) İLK kez aldığında geçerlidir; anahtarı açmak AYRI bir karardır.
Ayrıntı: [`RUNBOOK.md` → Dağıtık kontroller](RUNBOOK.md#dağıtık-kontroller-çok-pod-sürüm-3-3).

**Öncesi**
- [ ] **Dağıtım ana anahtar KAPALIYKEN yapılır** (varsayılan: `site.monitor.checks.distributed.enabled` = false, env
      `DISTRIBUTED_CHECKS_ENABLED` verilmez; `DISTRIBUTED_CHECKS_TYPES`, `CHECK_WORKER_*`, `CHECK_DISPATCH_MS`,
      `CHECK_DRAIN_MS`, `CHECK_RESULT_STALE_MS` de verilmez — varsayılanlar `application.properties`'te, Helm değeri
      gerekmez). Kapalıyken davranış 20.169.0 ile birebir aynıdır (alarm kararları, bildirimler, SQL ifadeleri, iş
      parçacıkları, günlük satırları, kapanış). Sürüm değişikliği ile anahtarı açmak aynı adımda YAPILMAZ.
- [ ] **Şema: iki yeni, boş tablo açılışta kurulur** — ön kontrol / yedek gerekmez, mevcut tablolara dokunulmaz. İlk yeni pod
      Ready olunca `CheckJobSchemaPatch`: `monitor_check_jobs` + `idx_mcj_state`, `monitor_check_workers`; PostgreSQL'de
      ayrıca kısmi tekil `ux_mcj_open` ve kuyruk tablosunun otomatik süpürme ayarı (tabloyla birlikte, boşken). Uygulama
      kullanıcısının `CREATE TABLE` hakkı olmalı (öteki şema yamaları gibi).
- [ ] **Kapanış bütçesi:** Helm `terminationGracePeriodSeconds` 120 değişmedi (bütçe 101 sn = preStop 10 + uygulama 91;
      işçi ve sonuç boşaltıcısı beklemeleri yalnız anahtar açıkken ve ortak 10 sn'lik yok etme süresinin içinde).
- [ ] **Grafana panosu:** revizyon 7 (sağlık bölümünün sonunda dağıtık kontrol panelleri; anahtar kapalıyken "veri yok") —
      dağıtımdan sonra Ayarlar → Grafana panosu → JSON indir ve aynı UID ile içe aktarın.
- [ ] **Kendi kendini izleme:** yeni kontrol "Dağıtık kontrol kuyruğu" (orta önem) — anahtar kapalıyken "Uygulanmaz", posta
      üretmez.

**Sonrası** (açılıştan ~15 dk sonra; günlükler TÜM pod'lardan — aşağıdaki "İlk 15 dakika" `L`)
- [ ] Tablolar kuruldu — ikisi dolu dönmeli (PostgreSQL'de üçü):
      `SELECT to_regclass('monitor_check_jobs'), to_regclass('monitor_check_workers'), to_regclass('ux_mcj_open');`
      İlk yeni pod'da `Dağıtık kontrol kuyruğu şeması: N değişiklik uygulandı`; `Dağıtık kontrol kuyruğu şeması
      uygulanamadı` / `… kurulamadı` satırı olmamalı (`kubectl logs $L | grep "Dağıtık kontrol kuyruğu"`).
- [ ] Anahtar kapalıyken iz yok: `kubectl logs $L | grep -cE "Dağıtık kontroller (açık görüldü|:)"` → 0; kuyruk ve işçi
      tablosu boş (`SELECT COUNT(*) FROM monitor_check_jobs;` → 0, `SELECT COUNT(*) FROM monitor_check_workers;` → 0);
      Sistem Sağlığı → Küme kartı → "Dağıtık kontroller: Kapalı"; Kendi kendini izleme → "Dağıtık kontrol kuyruğu:
      Uygulanmaz"; `bash scripts/multipod-smoke.sh dup-check` bölüm 9'u gösterir, sayısı 0.
- [ ] İzleme taramaları bugünkü gibi liderde (Küme kartı → son tarama tıkı ilerliyor; İzleme Panosu'nda gecikmiş izleme yok).

**Açma — ayrı bir adım** (RUNBOOK → Dağıtık kontroller → "Açmadan önce" + "Tür tür açın")
- [ ] **TÜM pod'lar 3-3 (ya da üstü) sürümünde** — rollout bitti, eski ReplicaSet'in pod'u kalmadı (`kubectl get pods -n
      "$NS" -l app.kubernetes.io/instance="$REL"` hepsi aynı imaj). Eski bir pod işçi olmaz, eski bir lider hiç iş
      göndermez (güvenli ama işler dağılmaz).
- [ ] **P1 çıkış eşitliği her node'da** dağıtılacak türlerin hedefleriyle (`scripts/multipod-egress-check.sh`; `TARGETS` /
      `TLS_TARGETS`'a port ve HTTP / içerik / sayfa izlemelerinden doğrudan hedefler), ping için her pod'da ICMP
      (`kubectl exec -n "$NS" <pod> -- ping -c 1 -W 2 127.0.0.1`), senaryo için k6 (imajda). 3-1 doğrulaması iki pod'da
      "uyuşuyor".
- [ ] Önce staging: `SMOKE_IMAGE=<imaj> SMOKE_DISTRIBUTED=1 bash scripts/multipod-smoke.sh` yeşil; sonra tür listesi
      `port,ping` yazılır ve kaydedilir, SONRA ana anahtar açılır (Ayarlar → Genel → Zamanlayıcı, yalnız GLOBAL admin — liste
      boşsa sekiz türün hepsi dağıtılır). Lider pod yanıtlarken Küme kartı "Dağıtık kontroller: Açık", `port` / `ping`
      "dağıtık".
- [ ] Her adımın geçme ölçütü (en az 3 gün): dedektör bölüm 9 (a)(b) boş, (c) STUCK = 0; sahiplenme p95 < 10 sn; en eski
      değerlendirilmemiş sonuç < 10 sn; kayıp iş < %0,1; "Dağıtık kontrol kuyruğu" Sağlıklı; alarm gürültüsünde yeni bir
      alarm sınıfı farkı yok. Sonraki adımlar: `+http,keyword` → `+page,pagespeed` → `+dns` → `+scripted`; sonra prod, aynı
      sırayla.
- [ ] Geri dönüş anında güvenlidir: ana anahtarı kapatmak (ya da türü listeden çıkarmak) o türü bir sonraki klasik tıkta
      lidere döndürür.

**Geri alma:** önce ana anahtarı kapatın (açıksa), sonra `helm rollback`. İki tablo, iki ayar anahtarı ve kendi kendini
izlemenin `check_queue` satırı kalır, eski kod yok sayar (silmeyin); anahtar açıkken geri alınırsa kuyruktaki sonuçlar
değerlendirilmez (izleme başına en çok bir değerlendirme kaybı, çift kontrol yok).

### Denetim zinciri düzeltmesi öncesi — bir kerelik
- [ ] **Prod etkileniyor mu (salt okunur):**
      `SELECT count(*) FILTER (WHERE seq IS NULL) AS seqsiz_eski, count(*) FILTER (WHERE seq = 1) AS seq_bir,
              count(*) FILTER (WHERE seq = 1 AND seq_locked) AS seq_bir_kilitli,
              min(event_time) FILTER (WHERE seq = 1) AS bozuk_donem_baslangici, to_regclass('ux_audit_seq') AS indeks
       FROM audit_log;`
      `seq_bir > 1` → etkileniyor (beklenen: `seqsiz_eski > 0`, `indeks` boş). Değerleri not edin — dağıtımdan sonra
      yeniden başlatma satırının `rows_before` / `seq1_rows` ayrıntısıyla karşılaştırılır (ayrıntı kilitten hemen önce
      sayılır; birkaç satır fark olabilir). `seq_bir ≤ 1` → yeniden başlatma YAZILMAZ ve işaret (marker) de yazılmaz (beklenen;
      her açılışta iki satırlık bir yoklama yapılır).

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
- [ ] `/var/log` emptyDir'i DEBUG hacmini taşıyabilir: log dosyaları toplam ≈1,2 GB ile sınırlı (FILE 500 MB, ERROR 200 MB,
      AUDIT 500 MB); chart `sizeLimit: 2Gi` ve `ephemeral-storage` 1Gi request / 4Gi limit koyar — düğümün ephemeral-storage
      payı ve namespace kotası buna yetmeli.

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
- [ ] Sayfa dosyaları taşındı (RUNBOOK "Yuvarlanan dağıtımda sayfa dosyaları"):
      `kubectl exec -n "$NS" <pod> -- cat /app/frontend/carry-report.json` → `included` dağıtımdan önce çalışan sürümü
      içeriyor ya da o sürümden beri arayüz değişmedi (`carried: 0` o zaman doğrudur) ve
      `curl -sI https://<host>/boot-guard.js | grep -i cache-control` → `no-store`.
- [ ] **Çok pod Sürüm 3-1** (ilk kez alındıysa): yukarıdaki ["Çok pod Sürüm 3-1 öncesi / sonrası kontroller"](#çok-pod-sürüm-3-1-öncesi--sonrası-kontroller--bir-kerelik)
      → **Sonrası** maddeleri (tablolar, kuşak satırı, Küme kartı doğrulaması, kanaryalar).
- [ ] **Çok pod Sürüm 3-3** (ilk kez alındıysa): yukarıdaki ["Çok pod Sürüm 3-3 — bir kerelik"](#çok-pod-sürüm-3-3--bir-kerelik)
      → **Sonrası** maddeleri (iki tablo kuruldu, ana anahtar kapalıyken iz yok, taramalar liderde).
- [ ] **Çok pod Sürüm 2b:** Sistem Sağlığı → Kendi kendini izleme → "Saat uyumu" Sağlıklı (pod saatleri arası fark < 5 sn); Zamanlayıcı ve
      yürütücüler → Küme kartında tarama lideri dolu; Heartbeat → "Pod'lar" kartında "sessiz" pod yok.
- [ ] **Çok pod Sürüm 2a** (açılıştan ~5 dk sonra): her pod'un günlüğünde bir kez `Önbellek yolu çalışıyor`
      (`kubectl logs $L | grep "Önbellek yolu"` — `yoklanamadı` / `yayını başarısız` satırı olmamalı); yeni tablolar ve
      indeksler dolu dönmeli:
      `SELECT to_regclass('cache_bus'), to_regclass('rate_limit_counters'), to_regclass('ux_audit_seq'), to_regclass('ux_der_mon_exp_thr'), to_regclass('idx_lir_ip_reported');`
      `ux_push_eventless_key_user` bu sürümden sonra da boşsa yukarıdaki "Çok pod Sürüm 2a öncesi kontroller" (a) / (b).
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
- [ ] **Denetim zinciri (açılıştan ~15 dk sonra):** `SELECT id, seq, event_time, detail FROM audit_log WHERE event_type = 'AUDIT_CHAIN_RESTART';`
      → etkilenen ortamda TEK satır (`prev_hash = 'GENESIS'`); TÜM pod'ların günlüğünde bir kez
      `kubectl logs -n "$NS" -l app.kubernetes.io/instance="$REL" --prefix --tail=-1 | grep "Denetim zinciri YENİDEN BAŞLATILDI"`.
      ~1 saat içinde `SELECT to_regclass('ux_audit_seq');` dolu (değilse günlükte `ux_audit_seq` satırına bakın — eski pod
      yazıyorsa ertelenir, 3 denemeden sonra bir sonraki açılış kurar). Denetim Logu → "Bütünlüğü doğrula": "Zincir sağlam"
      + "Zincir … tarihinden itibaren doğrulandı — öncesindeki N kayıt …". Yeni bir denetim yazımının planı (isteğe bağlı,
      salt okunur): `EXPLAIN SELECT * FROM audit_log WHERE seq IS NOT NULL ORDER BY seq DESC LIMIT 1;` → `Index Scan Backward
      using idx_audit_seq`, Sort YOK.

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
      (Docker; tek lider, A'da açılan oturum B'de geçerli, lidere SIGTERM → devir ≤ 40 sn ve geri, Sürüm 3-1'den beri her
      devirden sonra kalıcı değerlendirme durumu doğrulaması 0 uyuşmazlık + devralınan satırlar, çift iş yok).

### 1. Ön koşullar (P1-P5) — her biri kanıtıyla
- [ ] **P1 — Her node'dan aynı çıkış.** Pod'ların koşabileceği her node'da vekil (RDAP), SMTP, LDAP / AD, push ağ
      geçidi ve doğrudan kontrol edilen iç hedefler erişilebilir:
      ```bash
      NS="$NS" REL="$REL" TARGETS="<ldap-host>:636 <push-host>:443 <iç-hedef>:443" \
        TLS_TARGETS="<ldap-host>:636 <push-host>:443 <envanter-alanı>:443" \
        bash scripts/multipod-egress-check.sh <node-1> <node-2> <node-3>      # çıkış kodu 0 olmalı
      ```
      Bir node başarısızsa: kural düzeltilir ya da pod'lar sağlam node'lara sabitlenir (`master.yaml` → `nodeSelector` /
      `affinity`). Bu yapılmadan ikinci pod açılmaz — ilk devirde toplu "kesinti" alarmı ya da sessiz posta kaybı olur.
      Her TLS hedefi her node'da `TLS OK` ve `veren` node'dan node'a aynı olmalı (düz metin hedefleri — ör. SMTP 25 —
      `TLS_TARGETS`'a koymayın; verilmezse `TARGETS` kullanılır).
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
- [ ] Sistem Sağlığı → `session_store: jdbc`; Zamanlayıcı ve yürütücüler → Küme kartı: "Tarama lideri" TEK pod adı (sayfayı
      birkaç kez yenileyin — yanıtlayan pod değişse de lider adı aynı kalmalı; "Lider mi: Evet" yalnız o pod yanıtlayınca).
- [ ] Heartbeat → "Pod'lar" kartı: iki pod "canlı", "sessiz" yok; Sertifika taraması kartı son koşuyu ve koşturan pod'u gösterir
      (hangi pod yanıtlarsa aynı).
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
      → diğer pod ≤ ~35 sn'de lider olur, izleme kesintisiz devam eder. Sürüm 3-1'den beri: yeni lider pod yanıtlarken
      Küme kartı → "Değerlendirme kaydı" → "devralınan (kuşak N)" satırı dolu ve ~10 dk içinde "Kalıcı değerlendirme
      durumu" yeni kuşakta yine "uyuşuyor" (aradaki "eşitleniyor" turu normal).
- [ ] **Çok pod Sürüm 3-1 — yan işler takipçide:** Küme kartı → "Yanıtlayan pod" → "Yan işler": lider pod yanıtlarken
      "başka canlı pod var" (ya da "son bakışta başka canlı pod vardı"), takipçi yanıtlarken "yalnız tarama lideri bakar";
      her iki pod'da "lider erteleme açık"; ilk saat başından sonra lider pod'da "ertelendi" sayacı > 0 ve Sertifika
      taraması kartında saatlik turu koşturan pod çoğunlukla takipçi. Kümenin tamamı: dedektörün bölüm 0b'si (turların son
      sahiplenen pod'u — `round:cert-hourly`, `round:cert-stale`, `round:tls-profile` çoğunlukla takipçide; `round:<tur>:done`
      satırları turu son BAŞARIYLA bitiren pod — lider yalnız buna bakarak bırakır). Grafana "Kurum ölçülerini yayan pod"
      ≥ 1 (lider `sitemonitor_exporter_org_families` 0, takipçi 1).

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
- **Denetim zinciri düzeltmesinden ÖNCEYE dönülüyorsa** (20.168.0 ve öncesi): `helm rollback`'ten önce (ya da hemen
  ardından) `DROP INDEX CONCURRENTLY IF EXISTS ux_audit_seq;` — eski kod her satıra seq 1 yazar; indeks varken ikinci
  yazımdan itibaren denetim kayıtları fallback dosyasına düşer. Yeniden yükseltmede iş çiftleri temizler, indeks yeniden kurulur.
- **Şema:** yamalar yalnız ekler (`ADD COLUMN` / `CREATE TABLE`, `ddl-auto=update`) → eski sürüm yeni şemada
  açılır; yeni kolonlara yazılan veri eski sürümde görünmez ama kaybolmaz. İstisna: varsayılansız `NOT NULL`
  kolon eski sürümün INSERT'ünü kırar — sürümün CHANGELOG'unda böyle bir şema notu varsa geri almadan önce okuyun.
  Çok pod sürüm 1'in iki tekil indeksi (`ux_push_eventless_key_user`, `ux_noe_one_ongoing`) geri almada kalır ve silinmez:
  eski sürümün yazımları önce "var mı?" diye bakar ve tekil ihlalini yakalar (ayrıntı: [`RUNBOOK.md` → Geri alma](RUNBOOK.md#geri-alma-rollback)).
  Çok pod Sürüm 3-1'in dört tablosu (`monitor_eval_state`, `monitor_last_status`, `monitor_eval_type_state`,
  `monitor_confirmations`) ve `round:tls-profile` satırı da kalır; eski sürüm onları hiç okumaz. Çok pod Sürüm 3-3'ün iki
  tablosu (`monitor_check_jobs`, `monitor_check_workers`), iki ayar anahtarı ve `check_queue` kendi kendini izleme satırı da
  kalır — dağıtık kontrol anahtarı açıksa geri almadan ÖNCE kapatın.
- **Secret key bu dağıtımda değiştiyse** rollback kurtarmaz: yeni anahtarla şifrelenen girişler eski anahtarla
  çözülemez. Bu yüzden anahtar değişimi hiçbir yayınla birlikte yapılmaz.
- Son çare: DB yedeğinden geri yükleme (veri kaybı: yedekten sonraki kayıtlar) — platform ekibiyle.

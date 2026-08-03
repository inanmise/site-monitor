// Her izleme türü için "Yeni monitör formu nasıl doldurulur?" how-to dokümanı (TR/EN markdown).
// MonitorGuideButton bunu türe göre yükler. Yeni izleme türü eklenince buraya bir giriş ekle
// (aynı zamanda [[cert-monitor-monitor-howbox-standard]] gereği MonitorHowBox da eklenir).
// İçerik MarkdownEditor (read-only) ile render edilir → başlık (##/###), **kalın**, listeler, tablo çalışır.
// Alan adları/varsayılanları gerçek formlarla hizalıdır; form değişirse burayı da güncelle.

const HTTP_TR = `
## HTTP / Website izlemesi nasıl eklenir?

Sağ üstteki **+ Yeni Monitör** butonuna tıklayın ve formu doldurun.

### Zorunlu alanlar
- **İzlenecek URL** — Tam adres, şema dahil: \`https://www.akbank.com\`. \`http://\`/\`https://\` yoksa istek çalışmaz.
- **Takım** — İzlemenin sahibi ekip; alarm/rapor buraya gider (yönetici birden çok ekip seçebilir).

### Diğer alanlar
- **Metod** — GET / HEAD / POST (varsayılan **GET**).
- **Beklenen durum kodu** — Sağlıklı sayılacak kodlar: \`200\`, \`2xx\`, \`200-399\`, \`200,301,302\`. Boş → varsayılan **200–399**.
- **Yönlendirmeleri takip et** — Varsayılan açık; 301/302 zincirini izler.
- **TLS sertifikasını doğrula** — Varsayılan kapalı (yalnız erişilebilirlik; iç-CA/self-signed sorun olmaz). Açıkken geçersiz sertifika = alarm.
- **İsim** — Panolarda görünen ad. Boş bırakılırsa URL kullanılır.
- **Grup** — İzlemeleri gruplamak için etiket; mevcut gruptan seçin veya yeni yazın.
- **Etiketler** — Serbest etiketler (filtreleme/arama için).
- **Bildirimler** — E-posta (varsayılan açık). SMS / Sesli / Uygulama bildirimi şu an "yakında".
- **Kontrol aralığı** — 30 sn … 24 saat (varsayılan **5 dk**). Sık kontrol = hızlı tespit, daha çok yük.
- **SSL Kontrolleri** — "SSL hatalarını kontrol et"; "SSL bitiş hatırlatmaları" → **Hatırlatma günleri** (ör. \`30,14,7\`); "Domain bitiş hatırlatmaları" (aynı gün listesi).
- **Doğrulama denemesi** — Alarm öncesi ardışık başarısızlık sayısı (0–10, varsayılan 3) + **Deneme aralığı (sn)** (10–600, varsayılan 30).
- **Kurtarma** — "Düzeldi" demek için gereken ardışık başarılı kontrol (1–20, varsayılan 3) + yeniden deneme aralığı.
- **Zaman aşımı (ms)** — Yanıt için üst süre (varsayılan **10000**). Aşılırsa erişilemez sayılır.
- **Aktif** — Kapatılırsa izleme durur ama silinmez.

### İpuçları
- Sadece "ayakta mı" bakıyorsanız TLS doğrulamayı kapalı bırakın; **sertifika geçerliliği** için SSL kontrollerini açın.
- Sayfada belirli bir **metnin bulunup bulunmadığını** kontrol etmek istiyorsanız **Anahtar Kelime** izlemesini kullanın.
`

const HTTP_EN = `
## How to add an HTTP / Website monitor

Click **+ New Monitor** (top right) and fill the form.

### Required
- **URL to monitor** — Full address including scheme: \`https://www.akbank.com\`. Without \`http(s)://\` the request won't run.
- **Team** — The owning team; alerts/reports go to it (admins can pick multiple).

### Other fields
- **Method** — GET / HEAD / POST (default **GET**).
- **Expected status** — Codes treated as healthy: \`200\`, \`2xx\`, \`200-399\`, \`200,301,302\`. Blank → **200–399**.
- **Follow redirects** — On by default; follows the 301/302 chain.
- **Verify TLS certificate** — Off by default (reachability only; internal-CA/self-signed OK). On: invalid cert = alert.
- **Name** — Display name; if blank the URL is used.
- **Group** — A label to group monitors; pick an existing one or type a new one.
- **Tags** — Free tags (for filtering/search).
- **Notifications** — Email (on by default). SMS / Voice / Push are "coming soon".
- **Check interval** — 30s … 24h (default **5m**). More frequent = faster detection, more load.
- **SSL checks** — "Check SSL errors"; "SSL expiry reminders" → **Reminder days** (e.g. \`30,14,7\`); "Domain expiry reminders" (same day list).
- **Confirm attempts** — Consecutive failures before alerting (0–10, default 3) + **Attempt interval (s)** (10–600, default 30).
- **Recovery** — Consecutive successes to declare "recovered" (1–20, default 3) + retry interval.
- **Timeout (ms)** — Max wait for a response (default **10000**). Exceeded = unreachable.
- **Active** — When off the monitor pauses without being deleted.

### Tips
- For pure "is it up" checks leave Verify TLS off; enable SSL checks for **certificate validity**.
- To check whether specific **text is present** on the page, use the **Keyword** monitor.
`

const PORT_TR = `
## Port izlemesi nasıl eklenir?

**+ Yeni Monitör** ile formu açın.

### Zorunlu alanlar
- **Host** — Sunucu adı veya IP (ör. \`smtp.akbank.com\` / \`1.2.3.4\`). URL değil, yalnız host.
- **Port** — 1–65535 (ör. \`25\`, \`443\`, \`8443\`).
- **Takım** — İzlemenin sahibi ekip.

### Diğer alanlar
- **Kontrol Tipi** — **TCP** (Bağlantı: port açık mı), **TLS** (El sıkışması: sunucu sertifika sunuyor mu), **HTTP(S)** (Durum kodu), **BANNER** (Yanıt eşleştirme), **UDP** (Datagram). Varsayılan **TCP**.
- **İstek yolu / Gönderilecek veri** — HTTP'de istek yolu (ör. \`/health\`); diğer tiplerde bağlantı sonrası gönderilecek veri (opsiyonel).
- **Beklenen durum kodu / Beklenen yanıt** — HTTP'de kod (ör. \`200, 2xx\`); diğerlerinde yanıtta aranacak alt-dizge (ör. \`220\`, \`+OK\`, \`SSH-2.0\`).
- **İsim** — Boşsa host kullanılır. **Grup**, **Etiketler**, **Bildirimler (E-posta)** — HTTP'dekiyle aynı.
- **Kontrol Sıklığı** — 30 sn … 24 saat (varsayılan 5 dk).
- **IP sürümü** — Otomatik (IPv4 öncelikli) / IPv4 / IPv6.
- **Gelişmiş ayarlar** (katlanır) — "Yavaş yanıt alarmı" → **Yavaşlık eşiği (ms)** (varsayılan 3000); **Doğrulama denemesi** (0–10, def 3) + aralık; **Kurtarma** (1–20, def 3) + aralık; **Aktif**.

### İpuçları
- Yalnız "port açık mı" için **TCP** yeterli. Sertifika sunumunu da görmek için **TLS** seçin.
- UDP bağlantısız olduğundan sonuçları daha az kesindir; mümkünse TCP/TLS tercih edin.
`

const PORT_EN = `
## How to add a Port monitor

Open the form with **+ New Monitor**.

### Required
- **Host** — Hostname or IP (e.g. \`smtp.akbank.com\` / \`1.2.3.4\`). Host only, not a URL.
- **Port** — 1–65535 (e.g. \`25\`, \`443\`, \`8443\`).
- **Team** — The owning team.

### Other fields
- **Check type** — **TCP** (Connect: is the port open), **TLS** (Handshake: does the server present a cert), **HTTP(S)** (Status code), **BANNER** (Response match), **UDP** (Datagram). Default **TCP**.
- **Request path / Data to send** — For HTTP the request path (e.g. \`/health\`); for other types an optional payload sent after connecting.
- **Expected status / Expected response** — For HTTP the code (e.g. \`200, 2xx\`); otherwise a substring to find in the response (e.g. \`220\`, \`+OK\`, \`SSH-2.0\`).
- **Name** — Falls back to host. **Group**, **Tags**, **Notifications (Email)** — same as HTTP.
- **Check interval** — 30s … 24h (default 5m).
- **IP version** — Auto (IPv4 first) / IPv4 / IPv6.
- **Advanced settings** (collapsible) — "Slow-response alert" → **Slow threshold (ms)** (default 3000); **Confirm attempts** (0–10, def 3) + interval; **Recovery** (1–20, def 3) + interval; **Active**.

### Tips
- For "is the port open" **TCP** is enough. To also see the certificate, pick **TLS**.
- UDP is connectionless so its results are less definitive; prefer TCP/TLS when possible.
`

const DNS_TR = `
## DNS izlemesi nasıl eklenir?

**+ Yeni Monitör** ile formu açın.

### Zorunlu alanlar
- **Domain** — Sorgulanacak ad (ör. \`www.akbank.com\`).
- **Takım** — İzlemenin sahibi ekip.
- **Kayıt Tipi** — Formda: A, AAAA, CNAME, MX, TXT, NS (varsayılan **A**).
- **Kontrol Sıklığı** — 30 sn … 24 saat (varsayılan 5 dk).

### Diğer alanlar
- **İsim** — Boşsa domain kullanılır. **Grup** — mevcut/yeni.
- **Maks. çözümleme süresi (ms)** — Yanıt bunu aşarsa "yavaş" sayılır (opsiyonel; 100–60000).
- **Beklenen değer (kilit)** — Her satıra bir değer (canlı yanıtta olmasını beklediğiniz IP/hedef). Girildiğinde, beklenen kümede OLMAYAN her canlı değer **DNS ele geçirme** olarak işaretlenir. Boş = kilit kapalı (rotasyon serbest). "Şu anki değeri sabitle" ile mevcut canlı değerleri tek tıkla doldurabilir, "Şu anki değeri listeye ekle" ile üzerine yazmadan ekleyebilirsiniz.
- **DNS değişikliği alarmı** — Açıkken kayıt değeri değişince "DNS Değişikliği" alarmı üretilir (otomatik kapanmaz, günlük hatırlatılır). Kapatırsanız değişiklikler yalnız kontrol geçmişine yazılır. Beklenen liste doluysa ve yeni değerlerin TAMAMI listedeyse (ör. iç ↔ dış IP geçişi) alarm üretilmez.
- **Propagation kontrolü (çoklu-resolver)** — Açıkken kayıt birden çok çözümleyicide karşılaştırılır; en az 2'si farklı değer verirse "tutarsız" işaretlenir.
- **Aktif** — Otomatik kontrolü aç/kapat.

### Sorgu nereden, nasıl yapılır?
- Sorgular **izleme sunucusundan (pod)** dnsjava ile doğrudan atılır; ara proxy yoktur.
- Varsayılan hedef, izleme sunucusunun **işletim sistemi DNS zinciridir**: sunucular sırayla denenir (birincil zaman aşımına uğrarsa yedeğe geçilir). Aktif zinciri detay modalındaki **"Çözümleyici Yapılandırması"** bölümünde görebilirsiniz.
- Her kontrol **önbelleksiz taze sorgudur** — yanıt süresi gerçek gidiş-dönüştür. Sorgu zaman aşımı ayarlanabilir (varsayılan 2000 ms, \`cert.monitor.dns.query-timeout-ms\`).
- **Propagation kontrolü** açık monitörlerde ayrıca public çözümleyiciler (varsayılan \`8.8.8.8, 1.1.1.1, 9.9.9.9\` — \`cert.monitor.dns.resolvers\` ayarı) tek tek doğrudan sorgulanır.
- Kurum içi (split-horizon) bölgelerde OS zinciri iç DNS'e işaret ediyorsa **iç IP**, dış çözümleyiciler **dış IP** dönebilir — iki değeri de beklenen listeye ekleyerek bu gidip-gelmeyi sessizleştirin.

### İpuçları
- **Beklenen değer** girmek, sessiz DNS değişikliği/ele geçirmeyi yakalamanın en güçlü yoludur.
- Round-robin'de tüm değerleri beklenen kümeye ekleyin; alt-küme sapma sayılmaz.
- İç/dış IP arasında bilinen bir geçiş varsa iki IP'yi de beklenen listeye ekleyin; "Değişti" satırları geçmişte **"beklenen değerler arasında"** rozetiyle görünür ama alarm üretmez.
`

const DNS_EN = `
## How to add a DNS monitor

Open the form with **+ New Monitor**.

### Required
- **Domain** — The name to query (e.g. \`www.akbank.com\`).
- **Team** — The owning team.
- **Record type** — In the form: A, AAAA, CNAME, MX, TXT, NS (default **A**).
- **Check interval** — 30s … 24h (default 5m).

### Other fields
- **Name** — Falls back to domain. **Group** — existing/new.
- **Max resolution time (ms)** — If the response exceeds this it counts as "slow" (optional; 100–60000).
- **Expected value (lock)** — One value per line (the IP/target you expect live). When set, any live value NOT in the set is flagged as **DNS hijack**. Blank = lock off (rotation allowed). Use "Pin current value" to fill the current live values in one click, or "Add current value to list" to append without overwriting.
- **DNS change alarm** — When on, a "DNS Change" alarm is raised when the record value changes (does not auto-close, re-alerts daily). When off, changes are only written to the check history. If the expected list is set and ALL new values are in it (e.g. internal ↔ external IP flip), no alarm is raised.
- **Propagation check (multi-resolver)** — On compares the record across multiple resolvers; if at least 2 differ it's marked "inconsistent".
- **Active** — Enable/disable automatic checks.

### Where and how are queries made?
- Queries are sent **directly from the monitoring server (pod)** via dnsjava; there is no intermediate proxy.
- The default target is the monitoring server's **OS DNS chain**: servers are tried in order (fallback on primary timeout). See the active chain in the **"Resolver Configuration"** section of the detail modal.
- Every check is a **cache-free fresh query** — the response time is a real round-trip. Query timeout is configurable (default 2000 ms, \`cert.monitor.dns.query-timeout-ms\`).
- Monitors with **propagation check** also query public resolvers directly, one by one (default \`8.8.8.8, 1.1.1.1, 9.9.9.9\` — the \`cert.monitor.dns.resolvers\` setting).
- In split-horizon zones, the OS chain may return the **internal IP** while public resolvers return the **external IP** — add both to the expected list to silence that flip.

### Tips
- Setting an **Expected value** is the strongest way to catch silent DNS changes/hijacks.
- For round-robin add all values to the expected set; a subset is not a deviation.
- If a known internal/external IP flip exists, add both IPs to the expected list; "Changed" rows still appear in history with the **"Within expected values"** badge but raise no alarm.
`

const KEYWORD_TR = `
## Anahtar Kelime (Keyword) izlemesi nasıl eklenir?

**+ Yeni Monitör** ile formu açın.

### Zorunlu alanlar
- **URL** — Tam adres, şema dahil (ör. \`https://www.akbank.com\`).
- **Anahtar Kelime** — Sayfa gövdesinde aranacak metin (ör. \`Bireysel\`).
- **Takım** — İzlemenin sahibi ekip.

### Diğer alanlar
- **Adet koşulu** — En az (≥) / En fazla (≤) / Tam olarak (=) / Şundan fazla (>) / Şundan az (<). Varsayılan **En az**.
- **Adet (kez)** — Koşulun karşılaştırılacağı beklenen eşleşme sayısı (varsayılan 1). Ör. "En az 1" = kelime bulunmalı; "Tam olarak 0" = bulunmamalı.
- **Büyük/küçük harf duyarlı** — Varsayılan kapalı (duyarsız).
- **Özel HTTP Header (cache busting)** — Her satıra bir başlık (ör. \`Cache-Control: no-cache\`). Ayrıca URL'ye \`{timestamp}\` koyarsanız her koşuda güncel saniyeyle değiştirilir.
- **İsim** — Boşsa URL kullanılır. **Grup**, **Etiketler**, **Bildirimler (E-posta)** — standart.
- **Kontrol Sıklığı** — Varsayılan **1 dk**.
- **SSL Kontrolleri** — HTTP izlemesindeki gibi (SSL/Domain bitiş hatırlatmaları).
- **Gelişmiş ayarlar** (katlanır) — Yavaşlık eşiği (ms), Doğrulama denemesi, Kurtarma, Aktif.

### İpuçları
- "Bakımdayız" gibi bir metnin **çıkmaması** gerekiyorsa **Tam olarak 0** kullanın.
- SSL doğrulaması bu türde kapalıdır; iç-CA/self-signed sayfalar da çalışır (sertifika ayrı izlenir).
`

const KEYWORD_EN = `
## How to add a Keyword monitor

Open the form with **+ New Monitor**.

### Required
- **URL** — Full address including scheme (e.g. \`https://www.akbank.com\`).
- **Keyword** — The text to search for in the page body (e.g. \`Personal\`).
- **Team** — The owning team.

### Other fields
- **Count condition** — At least (≥) / At most (≤) / Exactly (=) / More than (>) / Less than (<). Default **At least**.
- **Count** — The expected match count to compare against (default 1). E.g. "At least 1" = must be present; "Exactly 0" = must be absent.
- **Case sensitive** — Off by default.
- **Custom HTTP header (cache busting)** — One header per line (e.g. \`Cache-Control: no-cache\`). Also, \`{timestamp}\` in the URL is replaced with the current second each run.
- **Name** — Falls back to URL. **Group**, **Tags**, **Notifications (Email)** — standard.
- **Check interval** — Default **1m**.
- **SSL checks** — Same as the HTTP monitor (SSL/Domain expiry reminders).
- **Advanced settings** (collapsible) — Slow threshold (ms), Confirm attempts, Recovery, Active.

### Tips
- To ensure a string like "We're under maintenance" is **absent**, use **Exactly 0**.
- SSL verification is off here; internal-CA/self-signed pages work (certificate is monitored separately).
`

const PING_TR = `
## Ping izlemesi nasıl eklenir?

**+ Yeni Monitör** ile formu açın.

### Zorunlu alanlar
- **Host (IPv4/IPv6)** — Sunucu adı veya IP (ör. \`10.0.0.1\`). URL değil, yalnız host.
- **Takım** — İzlemenin sahibi ekip.

### Diğer alanlar
- **IP Sürümü** — Otomatik / IPv4 / IPv6.
- **Paket Sayısı** — Her kontrolde gönderilecek ICMP paketi (1–10, varsayılan 4).
- **İsim** — Boşsa host kullanılır. **Grup** — mevcut/yeni.
- **Kontrol Sıklığı** — Varsayılan **1 dk**.
- **Zaman Aşımı (ms)** — Varsayılan 5000.
- **Doğrulama denemesi** (0–10, def 3) + **Deneme aralığı (sn)**; **Kurtarma** (1–20, def 3) + aralık.
- **Aktif** — Aç/kapat.

### İpuçları
- Ping ICMP echo ile çalışır. Bazı kurumsal ağlar/host'lar ICMP'yi engeller; böyle hedeflerde **Port** izlemesi (TCP) daha güvenilirdir.
- Kilitli ortamda ICMP yetkisi yoksa sonuç hata değil **N/A** olur.
`

const PING_EN = `
## How to add a Ping monitor

Open the form with **+ New Monitor**.

### Required
- **Host (IPv4/IPv6)** — Hostname or IP (e.g. \`10.0.0.1\`). Host only, not a URL.
- **Team** — The owning team.

### Other fields
- **IP version** — Auto / IPv4 / IPv6.
- **Packet count** — ICMP packets sent per check (1–10, default 4).
- **Name** — Falls back to host. **Group** — existing/new.
- **Check interval** — Default **1m**.
- **Timeout (ms)** — Default 5000.
- **Confirm attempts** (0–10, def 3) + **Attempt interval (s)**; **Recovery** (1–20, def 3) + interval.
- **Active** — Enable/disable.

### Tips
- Ping uses ICMP echo. Some networks/hosts block ICMP; for those a **Port** (TCP) monitor is more reliable.
- In locked-down environments without ICMP privilege the result is **N/A**, not a failure.
`

const PAGE_TR = `
## Sayfa Bütünlüğü izlemesi nasıl eklenir?

Bir sayfanın tüm kaynaklarının (görsel, CSS, JS, link, iframe, font) erişilebilirliğini izler.

**+ Yeni Monitör** ile formu açın.

### Zorunlu alanlar
- **URL** — İncelenecek sayfa/site adresi (ör. \`https://www.akbank.com/kampanya\`).
- **Takım** — İzlemenin sahibi ekip.

### Diğer alanlar
- **Ad** — Boşsa URL kullanılır. **Grup** — mevcut/yeni.
- **Mod** — **Tek Sayfa** (yalnız verilen URL) veya **Site Tarama** (linkleri gezerek birden çok sayfa). Varsayılan **Tek Sayfa**.
- **Tarama derinliği** — (Site Tarama) kaç seviye derine inileceği (0–5, varsayılan 2).
- **Azami sayfa** — (Site Tarama) taranacak en çok sayfa (1–500, varsayılan 50).
- **Hariç tutulan desenler** — Taramada atlanacak URL desenleri (her satıra bir desen).
- **Üçüncü-taraf kaynak kırıkları da alarm üretsin** — Varsayılan kapalı (dış CDN gürültüsünü azaltır).
- **Mixed content alarm üretsin** — HTTPS sayfada \`http://\` kaynak için alarm (varsayılan açık).
- **Zaman aşımlarını izle** — Açıkken (varsayılan) yanıt vermeyen kaynak KIRIK sayılır; kapalıysa yalnız "Zaman aşımı" olarak tabloda görünür.
- **Etiketler**, **E-posta bildirimi**, **Kontrol aralığı** (varsayılan 5 dk) — standart.
- **Gelişmiş ayarlar** (katlanır) — Yavaş kaynak eşiği (ms, def 2000), Kaynak eşzamanlılığı (1–20, def 5), Timeout (ms), Teyit/Kurtarma denemeleri, Aktif.

### İpuçları
- 404/5xx dönen veya hiç yanıtlamayan kaynaklar tespit edilir; sorunlu kaynak detay ekranında listelenir.
- Dış analytics/CDN kaynakları izleme noktasından erişilemeyip tarayıcıda çalışabilir; bu gürültüyü "üçüncü-taraf alarmı"nı kapalı ve "zaman aşımlarını izle"yi kapalı tutarak azaltın.
`

const PAGE_EN = `
## How to add a Page Integrity monitor

Monitors that all of a page's resources (images, CSS, JS, links, iframes, fonts) are reachable.

Open the form with **+ New Monitor**.

### Required
- **URL** — The page/site to inspect (e.g. \`https://www.akbank.com/campaign\`).
- **Team** — The owning team.

### Other fields
- **Name** — Falls back to URL. **Group** — existing/new.
- **Mode** — **Single Page** (just the given URL) or **Site Crawl** (follow links across pages). Default **Single Page**.
- **Crawl depth** — (Site Crawl) how many levels deep (0–5, default 2).
- **Max pages** — (Site Crawl) most pages to crawl (1–500, default 50).
- **Exclude patterns** — URL patterns to skip while crawling (one per line).
- **Alert on third-party resource breaks too** — Off by default (reduces external-CDN noise).
- **Alert on mixed content** — Alert on \`http://\` resources on an HTTPS page (default on).
- **Watch timeouts** — On (default) treats non-responding resources as BROKEN; off shows them only as "Timeout" in the table.
- **Tags**, **Email notification**, **Check interval** (default 5m) — standard.
- **Advanced settings** (collapsible) — Slow-resource threshold (ms, def 2000), Resource concurrency (1–20, def 5), Timeout (ms), Confirm/Recovery attempts, Active.

### Tips
- Resources returning 404/5xx or not responding are detected; the faulty resource is listed in the detail view.
- Third-party analytics/CDN resources may be unreachable from the monitor yet work in a browser; reduce this noise by keeping "third-party alert" off and "watch timeouts" off.
`

const DOMAIN_TR = `
## Alan Adı (Domain) izlemesi nasıl eklenir?

Bir alan adının **tescil süresinin (whois/RDAP)** bitişini izler ve yaklaşınca uyarır. (Süre seyrek değiştiği için otomatik kontrol günde birdir — ayrı bir sıklık alanı yoktur.)

**+ Yeni Monitör** ile formu açın.

### Zorunlu alanlar
- **Alan adı** — İzlenecek kayıtlı domain (ör. \`akbank.com\`). Alt alan/URL verseniz de kayıtlı domaine (eTLD+1) indirgenir (\`www.x.com.tr → x.com.tr\`).
- **Takım** — İzlemenin sahibi ekip.

### Diğer alanlar
- **İsim** — Boşsa domain kullanılır. **Grup** — mevcut/yeni.
- **Uyarı eşiği (gün)** — Bitişe bu kadar gün kala "uyarı" durumuna geçer (varsayılan 30).
- **Kritik eşiği (gün)** — Bu kadar gün kala "kritik" olur (varsayılan 7).
- **Hatırlatma eşikleri (gün, CSV)** — Hangi günlerde hatırlatma e-postası gideceği (varsayılan \`60,30,14,7,3,1\`).
- **RDAP kontrol timeout (ms)** — RDAP sorgusu için üst süre (1000–30000; opsiyonel).
- **Aktif** — Aç/kapat.

### İpuçları
- Bitiş tarihi klasik gTLD'lerde RDAP'tan, \`.tr\` gibi TLD'lerde HTTPS web-whois'ten alınır; cevabın hangi kaynaktan geldiği detay/**Tanıla** kartında gösterilir.
- Eşikler ve hatırlatma günleri **her monitör için ayrı** ayarlanır; kritik domainlerde daha erken uyarı verecek şekilde büyütebilirsiniz.
`

const DOMAIN_EN = `
## How to add a Domain monitor

Monitors a domain's **registration expiry (whois/RDAP)** and warns as it approaches. (Expiry changes rarely, so the automatic check runs daily — there is no separate interval field.)

Open the form with **+ New Monitor**.

### Required
- **Domain** — The registrable domain to watch (e.g. \`akbank.com\`). A subdomain/URL is reduced to the registrable domain (eTLD+1): \`www.x.com.tr → x.com.tr\`.
- **Team** — The owning team.

### Other fields
- **Name** — Falls back to domain. **Group** — existing/new.
- **Warning threshold (days)** — Enters "warning" this many days before expiry (default 30).
- **Critical threshold (days)** — Becomes "critical" this many days before (default 7).
- **Reminder thresholds (days, CSV)** — Which days a reminder email is sent (default \`60,30,14,7,3,1\`).
- **RDAP lookup timeout (ms)** — Max time for the RDAP query (1000–30000; optional).
- **Active** — Enable/disable.

### Tips
- Expiry comes from RDAP for classic gTLDs and HTTPS web-whois for TLDs like \`.tr\`; which source answered is shown on the detail/**Diagnose** card.
- Thresholds and reminder days are **per monitor**; raise them for critical domains to get earlier warnings.
`

const SCRIPTED_TR = `
## Senaryo İzleme (k6) nasıl eklenir?

Çok adımlı akışları (OIDC/Keycloak login, API zincirleri) k6 script'i ile uçtan uca izler.

Sağ üstteki **+ Yeni Senaryo** ile formu açın.

### Zorunlu alanlar
- **Ad** — Tanınır bir ad (ör. "İnternet Şubesi login akışı").
- **Takım** ve **Grup** — İzlemenin sahibi ekip ve grubu. **İkisi de zorunludur.** Grup için var olan gruptan seçin veya yeni ad yazın.
- **k6 Script** — Çalıştırılacak JavaScript. Bir **Şablon** seçerek başlamanız önerilir (smoke, OIDC/Keycloak, API zinciri, form login).

### Diğer alanlar
- **Açıklama** — Senaryonun ne doğruladığına dair kısa not.
- **Çalıştırma sıklığı** — İki koşu arası süre (kaydırıcı; varsayılan 5 dk).
- **Süreç timeout (sn)** — k6 koşusunun üst süresi (5–180, varsayılan 60). Aşılırsa süreç sonlandırılır ⇒ TIMEOUT.
- **Ardışık başarısızlık eşiği** — Alarm için gereken üst üste başarısızlık (0–10, varsayılan 3).
- **Kurtarma kontrolü** — "Düzeldi" demek için gereken başarılı koşu (1–10, varsayılan 3).
- **Ortam Değişkenleri** — Script'in \`__ENV\` ile okuduğu değerler. **Değişken Ekle** ile satır ekleyin: AD + değer + **Gizli**. Sırları (parola/secret) **script gövdesine YAZMAYIN**; "Gizli" işaretleyin — şifreli saklanır, çıktı/loglarda maskelenir, geri okunamaz.
- **E-posta bildirimi gönder**, **Aktif** — standart.

### İpuçları
- Kaydetmeden önce **Test Çalıştır** ile script'i anında deneyin; geçen/başarısız check sayısı ve k6 çıktısı görünür.
- İzleme için **ayrı bir servis hesabı** kullanın; gerçek kullanıcı hesabıyla otomatik login yapmayın.
- Sonuç: k6 check'i başarısız/exit 99 ⇒ FAIL, zaman aşımı ⇒ TIMEOUT, diğer hatalar ⇒ ERROR, aksi ⇒ PASS.
`

const SCRIPTED_EN = `
## How to add a Scripted (k6) monitor

Monitors multi-step flows (OIDC/Keycloak login, API chains) end to end via a k6 script.

Open the form with **+ New Scenario** (top right).

### Required
- **Name** — A recognizable name (e.g. "Online banking login flow").
- **Team** and **Group** — The owning team and group. **Both are required.** For Group pick an existing one or type a new name.
- **k6 Script** — The JavaScript to run. Starting from a **Template** (smoke, OIDC/Keycloak, API chain, form login) is recommended.

### Other fields
- **Description** — A short note on what the scenario validates.
- **Run interval** — Time between runs (slider; default 5m).
- **Process timeout (s)** — Max k6 run time (5–180, default 60). Exceeded ⇒ the process is killed ⇒ TIMEOUT.
- **Consecutive-failure threshold** — Failures in a row before alerting (0–10, default 3).
- **Recovery checks** — Successful runs to declare "recovered" (1–10, default 3).
- **Environment variables** — Values the script reads via \`__ENV\`. Use **Add variable** for a row: NAME + value + **Secret**. Do **NOT** put secrets in the script body; mark them "Secret" — stored encrypted, masked in output/logs, never readable back.
- **Send email notification**, **Active** — standard.

### Tips
- Use **Test Run** before saving to try the script instantly; passed/failed checks and k6 output are shown.
- Use a **dedicated service account**; don't automate login with a real user account.
- Result: failed k6 check / exit 99 ⇒ FAIL, timeout ⇒ TIMEOUT, other errors ⇒ ERROR, otherwise ⇒ PASS.
`

export const MONITOR_GUIDES = {
  http:     { tr: HTTP_TR,     en: HTTP_EN },
  port:     { tr: PORT_TR,     en: PORT_EN },
  dns:      { tr: DNS_TR,      en: DNS_EN },
  keyword:  { tr: KEYWORD_TR,  en: KEYWORD_EN },
  ping:     { tr: PING_TR,     en: PING_EN },
  page:     { tr: PAGE_TR,     en: PAGE_EN },
  domain:   { tr: DOMAIN_TR,   en: DOMAIN_EN },
  scripted: { tr: SCRIPTED_TR, en: SCRIPTED_EN },
}

# BUG RAPORU 7 — Kullanıcı bildirimleri (10 madde) · 2026-09-10

Kaynak: kullanıcının yerel örnekte gözlemlediği on bulgu. Her madde üç keşif ajanıyla kaynakta
doğrulandı, ayrı commit'lerle düzeltildi ve testle kapılandı. Ürün kararları (2026-09-10): RESOLVE
push sessiz saat/seviye kapısından **muaf**; havuz **core 20 / max 50**, kuyruk **5000**; takım modalı
kurum-geneli **ad, unvan, birim, müdürlük, org rolü, e-posta** (telefon/sicil/sistem rolü gizli);
dört zenginleştirme (lider/e-posta/eskalasyon sekmesi · alarm-anı/güncel bitiş · retention CSV ·
kuyruk doygunluk uyarısı) bu tura girdi.

| # | Bildirim | Kök neden | Çözüm | Commit |
|---|---|---|---|---|
| 1 | Bildirim geçmişi → Webhook(push) → Resend'de aynı kullanıcı iki kez | Grup anahtarı `trigger|status|message`; RESEND her tıkta rastgele `dedupe_key` ile yeni satır seti yazınca partiler tek karta birleşiyor, liste kişiyi N kez basıyordu | Anahtara `dedupe_key` eklendi (parti = kart); genişletilmiş liste tekil kişi | `fix(alert-history)` |
| 2 | Sertifika yenilenince çözüm maili gidiyor, push gitmiyor ("nobody qualifies… / scope decision") | `enqueueResolve` yedek takım olarak yine `event.teamId` veriyordu; damgasız eski sertifika olaylarında alıcı çözümü boş → `SKIPPED_NO_RECIPIENTS` | RESOLVE alıcıları = açılışta gerçekten SENT olanlar (`resolvePrior`); seviye/grup/takım/sessiz saat kapıları çözümde uygulanmaz; `EscalationService` dört yolda envanter SY takımını yedek verir ve damgayı geri doldurur | `fix(push)` |
| 3 | "37 records" kişi satırı sayıyor | Başlık `pushRows.length` (alıcı+sistem satırı), gövde gruplar | Başlık "N gönderim" (+ "M atlandı") | `fix(alert-history)` |
| 4 | wingscard kapalı alarmda "Expiry was 05/09/2026 00:00" (gerçek 23 Eylül 02:59) | Kart `created_at + days_remaining` ile hesaplıyordu; days_remaining eskalasyonda güncellenip created_at sabit kalınca 18 gün erken, zone'suz parse ile saat ":00" | `AlertEvent.not_after` (patch) damgalanır; eski satırlara LatestCheck yedeği; **zenginleştirme:** yenilenmişse "güncel bitiş" ikinci rozet | `fix(alerts)` |
| 5 | Takım adı tıklanabilir + üye modalı; Teams sekmesindeki kart modalda | Paylaşılan rozet yoktu; yüzeylerin yarısında yalnız `team_name`; üye uçları takım-kapsamlı | `TeamDirectoryController` (`/api/teams/directory`, `/{id}/members`, beyaz-liste), `TeamDirectoryProvider` + `TeamBadge` + `TeamMembersModal` (lider rozeti, mailto, eskalasyon sekmesi), 11 yüzeye yerleşim; Teams sekmesi satır-içi genişletme → modal | `feat(teams)` |
| 6 | Task Queue 0/1000, havuz 1/12 (min 12 max 30) — kapasite artışı | Helm 12/30, kuyruk env Helm'de yoktu, `WebConfig` satır-içi varsayılan 100 | Kuyruk 5000 (properties/WebConfig/Helm/env/docs), prod 20/50; **zenginleştirme:** sayaçlı CallerRuns + kartta %80 doygunluk bandı, dinamik tooltip | `chore(executor)` |
| 7 | Data retention son 10 gündür 0 silme | Onay kapısı YOK; büyük olasılıkla temiz kurulum + 180/365 günlük eşikler (henüz dolmadı) ya da legal hold; UI kalem hata/atlanma sebeplerini hiç çizmiyordu | Koşum satırında "neden 0" açıklaması (hold / opt-in kapalı / uygun kayıt yok + en erken kesim tarihi), kalem hata/atlandı rozetleri | `feat(retention)` |
| 8 | Uyum onayı "bilinmeyen ayar: user push deliveries" | `AppSettingsCatalog`'da `site.monitor.retention.approval.*` girdisi yoktu → her politikanın onayı reddediliyordu | Girdiler `RetentionCatalog.ALL`'dan üretilir | `fix(retention)` |
| 9 | Recent runs sayfalama/arama/filtre/sıralama | Tek seferlik `limit=10` | `GET /runs` page/size/kind/failed/policyId/since/until/q/sort/dir + `total`; `RetentionRunsPanel` (debounce, seq guard, `r_` URL param'ları, PaginationBar, "en yeniye dön"); **zenginleştirme:** CSV dışa aktarım | `feat(retention)` |
| 10 | Failed-Login Anomaly test mailinde logo X | Altı elle kurulmuş MimeMessage gönderici `BrandMailAssets.addInline`'ı atlıyordu | Tek huni `sendFramedHtml`; `EmailBrandCidTest` altı metodu MIME düzeyinde doğrular + huni sayısı sabit | `fix(mail)` |

## Doğrulama

- Backend `mvn clean verify`, frontend `lint → test:coverage → coverage:floor → build`, `helm lint` (3 ortam) — bu turun sonunda koşuldu; sonuçlar sürüm notunda.
- Tarayıcı: takım rozeti/modal, retention listesi, push geçmişi ve System Health kartı yerel örnekte gözle doğrulandı.

## Açık / izlenecek

- Madde 7 için kesin teşhis kullanıcının örneğinde `Recent runs` satırındaki "Neden 0" açıklamasından okunur (hold mu, eşik mi, opt-in mi).
- Prod Helm değerleri 20/50/5000 bir sonraki `helm upgrade` ile devreye girer (master.yaml override etmez; values.yaml otoritatif).

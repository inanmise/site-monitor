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
| 4 | example.com kapalı alarmda "Expiry was 05/09/2026 00:00" (gerçek 23 Eylül 02:59) | Kart `created_at + days_remaining` ile hesaplıyordu; days_remaining eskalasyonda güncellenip created_at sabit kalınca 18 gün erken, zone'suz parse ile saat ":00" | `AlertEvent.not_after` (patch) damgalanır; eski satırlara LatestCheck yedeği; **zenginleştirme:** yenilenmişse "güncel bitiş" ikinci rozet | `fix(alerts)` |
| 5 | Takım adı tıklanabilir + üye modalı; Teams sekmesindeki kart modalda | Paylaşılan rozet yoktu; yüzeylerin yarısında yalnız `team_name`; üye uçları takım-kapsamlı | `TeamDirectoryController` (`/api/teams/directory`, `/{id}/members`, beyaz-liste), `TeamDirectoryProvider` + `TeamBadge` + `TeamMembersModal` (lider rozeti, mailto, eskalasyon sekmesi), 11 yüzeye yerleşim; Teams sekmesi satır-içi genişletme → modal | `feat(teams)` |
| 6 | Task Queue 0/1000, havuz 1/12 (min 12 max 30) — kapasite artışı | Helm 12/30, kuyruk env Helm'de yoktu, `WebConfig` satır-içi varsayılan 100 | Kuyruk 5000 (properties/WebConfig/Helm/env/docs), prod 20/50; **zenginleştirme:** sayaçlı CallerRuns + kartta %80 doygunluk bandı, dinamik tooltip | `chore(executor)` |
| 7 | Data retention son 10 gündür 0 silme | Onay kapısı YOK; büyük olasılıkla temiz kurulum + 180/365 günlük eşikler (henüz dolmadı) ya da legal hold; UI kalem hata/atlanma sebeplerini hiç çizmiyordu | Koşum satırında "neden 0" açıklaması (hold / opt-in kapalı / uygun kayıt yok + en erken kesim tarihi), kalem hata/atlandı rozetleri | `feat(retention)` |
| 8 | Uyum onayı "bilinmeyen ayar: user push deliveries" | `AppSettingsCatalog`'da `site.monitor.retention.approval.*` girdisi yoktu → her politikanın onayı reddediliyordu | Girdiler `RetentionCatalog.ALL`'dan üretilir | `fix(retention)` |
| 9 | Recent runs sayfalama/arama/filtre/sıralama | Tek seferlik `limit=10` | `GET /runs` page/size/kind/failed/policyId/since/until/q/sort/dir + `total`; `RetentionRunsPanel` (debounce, seq guard, `r_` URL param'ları, PaginationBar, "en yeniye dön"); **zenginleştirme:** CSV dışa aktarım | `feat(retention)` |
| 10 | Failed-Login Anomaly test mailinde logo X | Altı elle kurulmuş MimeMessage gönderici `BrandMailAssets.addInline`'ı atlıyordu | Tek huni `sendFramedHtml`; `EmailBrandCidTest` altı metodu MIME düzeyinde doğrular + huni sayısı sabit | `fix(mail)` |

## Doğrulama

- Yerel kapılar: backend `mvn clean verify` (3472 test), frontend `lint → test:coverage → coverage:floor → build` (197 test dosyası), `helm lint` (3 ortam) — hepsi yeşil.
- CI `34422508580` (ad3c8a35): Backend / Frontend / Frontend E2E / Helm Lint dört iş **success**; `release.yml` success → etiket **v20.51.0** (feat commit'leri nedeniyle minor). Yerel main/develop ff-sync, jar yeniden derlendi, `/api/branding app_version = 20.51.0`, açılış logu `core 20 / max 50 / queue 5000`.
- Tarayıcı + API (localhost:5173, v20.51.0, 2026-09-10):
  - (8) `PUT /api/admin/retention/approval` `user-push-deliveries` + "uygundur" → **200**, overview'da onay `by/at/note` göründü (önce "Bilinmeyen ayar").
  - (7) `GET /runs?kind=real`: son beş gerçek koşumun silme sayısı **2440 / 952 / 9637 / 1071 / 831** — yani bu örnekte "10 gündür 0" değil; her koşumda tek atlanan kalem `incident-records` (sebep: `opt-in-kapali`), hold kapalı, hata yok, en erken kesim 2023-09. Kullanıcının gördüğü 0 büyük olasılıkla kalem satırlarındaki (opt-in kapalı politika) sıfırdı; artık satırda "atlandı: opt-in kapalı" rozeti ve "neden 0" açıklaması çiziliyor.
  - (6) System Health → System → Task Queue: `0 / 5000`, havuz `0 / 20`, `min 20 · max 50`, "Overflow tasks (caller-runs) 0", tooltip 50 thread'e göre dinamik; `executor_pool.caller_runs/saturated` API'de.
  - (4) Alert History → Closed → Certificate Expiry → example.com: **"Expiry was: 23/09/2026, 02:59"** (önce 05/09/2026 00:00). API `not_after = 2026-09-22T23:59:59`, `current_not_after` aynı (yenilenmemiş → tek rozet).
  - (5) Dashboard kartındaki takım rozeti → modal "Takım A — Members": lider rozeti, mailto, sekmeler "Members" / "Escalation contacts (1)"; Admin Panel → Teams sekmesinde satır-içi genişletme yok (0 `.team-expand-btn`), rozet modalı açıyor ve kartlar düzenlenebilir. `/api/teams/{id}/members` projeksiyonu yalnız `id, username, display_name, first/last_name, org_role, title, department, mudurluk_name, company_level, email, manager_*` — telefon/sicil/sistem rolü/foto yok.
  - (1/3) Bildirim geçmişi modalı Webhook(push) başlığı "N sends" biçiminde ("0 sends" — bu alarmda push satırı yok); grup/tekilleştirme mantığı `AlertHistoryPushGroups.test.jsx` ile pinli.
  - (2) RESOLVE push sessiz saat muafiyeti ve önceki-SENT alıcı kuralı canlı tetiklenmedi (sertifika yenilenmesi gerekir); `UserPushServiceTest`/`EscalationServiceTest` ile pinli.
  - (10) Logo CID `EmailBrandCidTest` ile altı metotta MIME düzeyinde doğrulandı; canlı test maili gönderilmedi (gerçek posta kutusuna düşer).
  - (9) Retention koşum paneli UI'si `RetentionRunsPanel.test.jsx` ile pinli; Ayarlar sekmesi yalnız `admin` kullanıcı adına açıldığından yerel QA hesabıyla ekran görüntülenemedi (API sayfalama/filtre/`total` doğrulandı).

## Açık / izlenecek

- Madde 7: bu örnekte koşumlar siliyor; kullanıcının kendi örneğinde `Recent runs` satırındaki "neden 0" açıklaması (hold / opt-in kapalı / uygun kayıt yok + en erken kesim) kesin teşhisi verir.
- Prod Helm değerleri 20/50/5000 bir sonraki `helm upgrade` ile devreye girer (master.yaml override etmez; values.yaml otoritatif).
- Ayarlar sekmesi `user === 'admin'` sabit kapısı: müdür/global admin hesapları retention ekranını göremez — ayrı bir karar konusu, bu turda dokunulmadı.

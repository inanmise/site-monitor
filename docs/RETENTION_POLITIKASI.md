# Veri Saklama Politikası

> **Bu dosya ÜRETİLİR — elle düzenlemeyin.** Kaynak: `RetentionCatalog.ALL`.
> Değişiklik için kataloğu güncelleyin; `RetentionDocTest` sapmayı yakalar.

## Nasıl çalışır

- Gece temizliği `0 0 3 * * *` cron'unda (Europe/Istanbul) tek pod'da (`nightly-cleanup` dağıtık kilidi) koşar.
- Ham seriler ÖNCE günlük özete (`monitor_check_daily`) alınır, SONRA silinir —
  ham veri kısalsa da uzun dönem trend korunur.
- Yüksek hacimli tablolar 10.000'lik dilimlerle silinir (`ANALYZE` ile biter):
  tek dev DELETE yerine kısa transaction'lar, bloat ve uzun kilit yok.
- Süreler **canlı** ayardır: Ayarlar → Veri Saklama'dan değiştirilir, ANINDA geçerli olur.
  Her politikanın kodda tanımlı bir **taban (minDays)** değeri vardır; altına inilemez.
- **Legal hold** (`site.monitor.retention.hold-enabled`): açıkken hiçbir satır
  silinmez; her gece uyarı logu ve denetim kaydı yazılır (soruşturma/denetim valfi).

## Uyum onayı

Kişisel veri ve denetim kaydı içeren tablolarda süreyi **kodun varsayılanı değil,
uyum/hukuk biriminin onayı** belirler. Onay bilgisi (kim, ne zaman, hangi dayanak)
Ayarlar → Veri Saklama ekranından politika bazında kaydedilir. KVKK tarafında
saklama-imha politikası ve 6 aylık periyodik imha ritmi, bankacılık tarafında
BDDK/iç denetim süreleri esas alınmalıdır.

## Kişisel Veri

| Tablo | Süre | Taban | Ayar anahtarı | Kural | Gerekçe |
|---|---|---|---|---|---|
| `page_usage_daily` | 90 gün | 7 g | `site.monitor.page-usage.retention-days` | `day < ?` | Günlük sayfa kullanımı özeti (kullanıcı adı + sekme anahtarı + ping sayısı; URL/parametre yok). System Health kullanıcı etkinliği bölümünün 'en çok kullanılan sayfalar / hiç açılmayanlar / takım benimseme' tabloları buradan beslenir. Kişisel veri olduğundan kısa tutulur. |
| `user_push_deliveries` | 1095 gün | 90 g | `site.monitor.userpush.retention-days` | `created_at < ?` | Kişi-bazlı webhook (push) teslimat günlüğü — kime, ne zaman, hangi içerikle, hangi alarm için, sonuç ne + API'nin verdiği notificationId. ÇOK UZUN saklama (3 yıl) bilinçli ürün kararı (2026-08-27): kurum tarafında iz sürme ve denetim için kanıt zinciri. Sicil içerir → PERSONAL. |
| `notification_logs` | 365 gün | 30 g | `site.monitor.notification.retention-days` | `sent_at < ?` | Gönderilen bildirim geçmişi (alıcı adı/e-postası içerir). Kişisel veri saklama süreleri 1 yılda eşitlendi (2026-08 kullanıcı kararı) — denetimde tek bir pencere savunulur. |
| `domain_expiry_reminders` | 365 gün | 30 g | `site.monitor.notification.retention-days` | `sent_at < ?` | Alan adı bitiş hatırlatma izleri (2026-09-22): eşik başına bir kez gönderim kaydı + alıcı e-postaları. Bildirim geçmişiyle AYNI pencere (aynı ayar anahtarı): tekrar-gönderim kilidi bitiş tarihi + eşik çiftine bağlı; 30 gün alt sınırı en gevşek eşiğin (60 gün) yarısıdır, purge sonrası daha sıkı eşik zaten gönderilmiş/kapsanmış olur — ikinci e-posta gitmez. |
| `diagnostic_runs` | 365 gün | 7 g | `site.monitor.diagnostics.retention-days` | `executed_at < ?` | Elle çalıştırılan tanılamalar (çalıştıran kullanıcı ve kaynak IP içerir). Kişisel veri penceresiyle aynı 1 yıl (2026-08 kararı). |
| `alert_events` | 365 gün | 90 g | `site.monitor.alert.retention-days` | `resolved = true AND resolved_at < ?` | Alarm olayları. Yalnız ÇÖZÜLMÜŞ alarmlar silinir — açık/onaylanmış alarmlar ASLA silinmez. |
| `login_issue_report_images` | 365 gün | 90 g | `site.monitor.login-issue.retention-days` | `report_id IN (SELECT id FROM login_issue_reports WHERE status = 'RESOLVED' AND resolved_at < ?)` | Sorun bildirimi ekran görüntüleri (base64, ≤5 adet). Ebeveyn raporun yaşına göre, ondan ÖNCE silinir. |
| `login_issue_reports` | 365 gün | 90 g | `site.monitor.login-issue.retention-days` | `status = 'RESOLVED' AND resolved_at < ?` | Kullanıcı sorun bildirimleri (e-posta, IP, serbest metin). Yalnız ÇÖZÜLMÜŞ olanlar; açık bildirimler durur. |
| `login_issue_mail_logs` | öksüz temizliği | — | — | `report_id NOT IN (SELECT id FROM login_issue_reports)` | Bildirim mail geçmişi — raporu silinince öksüz kalır; mail kaydı raporuyla birlikte ölür. |
| `activity_log` | 365 gün | 1 g | `site.monitor.activity.retention-days` | `activity_time < ?` | Birleşik aktivite akışı — her kontrol +1 satır (en hızlı büyüyen seri). Aktör kullanıcı adı içerir. 2026-08 kullanıcı kararıyla 90 → 365 gün: kişisel veri pencereleri eşitlendi. DİKKAT: satır sayısı ~4 katına çıkar; büyüme Ayarlar → Veri Saklama'dan izlenmeli. |
| `alert_comments` | öksüz temizliği | — | — | `alert_event_id NOT IN (SELECT id FROM alert_events)` | Alarm yorumları. alert_events 1 yılda siliniyor ama yorumlar kalıyordu → kalıcı öksüz. |
| `weekly_report_comments` | öksüz temizliği | — | — | `report_id NOT IN (SELECT id FROM weekly_reports)` | Haftalık rapor yorum dizisi (yazar adı + serbest metin). Raporuyla birlikte silinir; öksüz kalan temizlenir. |
| `weekly_report_mails` | 730 gün | 90 g | `site.monitor.weekly-report.mail-retention-days` | `created_at < ?` | Haftalık rapor mail geçmişi — gönderilen postanın TAM HTML gövdesini saklar (alıcı adresleriyle). |
| `certificate_note_revisions` | 730 gün | 180 g | `site.monitor.cert-note-revision.retention-days` | `edited_at < ?` | Sertifika notu düzeltme geçmişi — her düzenlemede +1 satır, hiç silinmiyordu. Notun kendisi korunur. |
| `spring_session` | harici yönetilir | — | — | — | Spring Session JDBC deposu — Spring'in kendi dakikalık cleanup job'ı süresi dolan oturumları siler (spring.session.timeout=24h). Bu uygulama dokunmaz. |
| `spring_session_attributes` | harici yönetilir | — | — | — | Oturum öznitelikleri — ebeveyn spring_session satırıyla FK CASCADE silinir. |
| `remember_me_tokens` | harici yönetilir | — | — | — | RememberMeService saatlik olarak süresi dolmuş token'ları siler (site.monitor.remember.cleanup-interval-ms). |
| `password_history` | harici yönetilir | — | — | — | UserService her şifre değişiminde kullanıcı başına son N kayda kırpar → kullanıcı başına sınırlı. |

## Denetim ve Güvenlik

| Tablo | Süre | Taban | Ayar anahtarı | Kural | Gerekçe |
|---|---|---|---|---|---|
| `audit_log` | 365 gün | 30 g | `site.monitor.audit.retention-days` | `event_time < ?` | Kullanıcı eylem denetimi. Silmeden ÖNCE JSONL arşivi yazılır; süre uyum birimi onayına tabidir. |
| `monitor_change_log` | 730 gün | 30 g | `site.monitor.monitoring.change-retention-days` | `created_at < ? AND resource_kind <> 'SYSTEM'` | İzleme yapılandırması değişiklik geçmişi (kim/ne zaman/hangi IP/neyi değiştirdi). audit_log'dan UZUN tutulur (730 gün): ayrı tablo olmasının sebeplerinden biri de budur — denetim değeri yüksek, hacim düşük (yapılandırma değişiklikleri kontrol kayıtları gibi akmaz). SYSTEM satırları HARİÇ: geri doldurmanın 'koştu' nişanı orada duruyor; silinirse bir sonraki açılış geçmişi ikinci kez doldurmaya kalkardı. |
| `sql_query_history` | 365 gün | 7 g | `site.monitor.sql-history.retention-days` | `executed_at < ?` | Admin SQL çalışma alanı geçmişi (kullanıcı + serbest SQL metni). Denetim penceresiyle aynı 1 yıl (2026-08 kararı); hacmi düşüktür. |
| `login_anomaly_incident` | 365 gün | 7 g | `site.monitor.failed-login.retention-days` | `resolved = true AND opened_at < ?` | Başarısız giriş anomali olayları. Yalnız ÇÖZÜLMÜŞ olanlar silinir; açık olaylar durur. Güvenlik penceresiyle aynı 1 yıl (2026-08 kararı). |

## Kullanıcı İçeriği

| Tablo | Süre | Taban | Ayar anahtarı | Kural | Gerekçe |
|---|---|---|---|---|---|
| `weekly_report_images` | 730 gün | 30 g | `site.monitor.weekly-report.image-retention-days` | `created_at < ?` | Haftalık rapor görselleri (BYTEA, satır başına MB'lar). Rapor METNİ korunur, yalnız çok eski görsel silinir. |
| `incident_records` | kapalı (opt-in) | 0 g | `site.monitor.incident.retention-days` | `status = 'RESOLVED' AND occurred_at < ?` | Elle girilen SRE olay kayıtları. VARSAYILAN 0 = hiç silinmez (opt-in); >0 verilirse yalnız ÇÖZÜLMÜŞ olaylar. |
| `incident_images` | 730 gün | 90 g | `site.monitor.incident.image-retention-days` | `created_at < ?` | Olay görselleri (BYTEA, ≤5 MB/satır). Olay METNİ korunur — weekly_report_images deseni. Daha önce hiç silinmiyordu: en yüksek satır-boyutu × sonsuz saklama riski. |
| `incident_images` | öksüz temizliği | — | — | `incident_id IS NOT NULL AND incident_id NOT IN (SELECT id FROM incident_records)` | Olayı silinmiş görseller. Olay silme cascade YAPMIYOR → her silinen olay MB'larca öksüz bırakıyordu. |
| `incident_images` | 7 gün | 1 g | `site.monitor.incident.draft-image-retention-days` | `incident_id IS NULL AND created_at < ?` | Kaydedilmeyen taslak yüklemeleri: incident_id kalıcı olarak NULL kalır, hiçbir olaya bağlanmaz. |
| `scripted_drafts` | 30 gün | 1 g | `site.monitor.scripted.draft-retention-days` | `updated_at < ?` | k6 script düzenleme formunun otomatik kaydedilen taslakları. Kaydedilince silinirler; burada kalanlar terk edilmiş oturumlardır (incident-images-draft ile aynı mantık). |
| `scripted_script_versions` | öksüz temizliği | — | — | `monitor_id NOT IN (SELECT id FROM scripted_monitors)` | Monitörü silinmiş script sürümleri. Sürüm geçmişi monitör silinirken BİLİNÇLİ olarak silinmiyor (denetim değeri); öksüz kalan satırlar burada temizlenir. |

## İşletimsel Telemetri

| Tablo | Süre | Taban | Ayar anahtarı | Kural | Gerekçe |
|---|---|---|---|---|---|
| `monitor_check_daily` | 730 gün | 90 g | `site.monitor.rollup.retention-days` | `day < ?` | Günlük özet (uptime/yanıt süresi trendi). Ham seriler kısalsa da 2 yıllık trend korunur. |
| `monitor_check_hourly` | 365 gün | 60 g | `site.monitor.rollup.hourly-retention-days` | `hour_bucket < ?` | Saatlik özet. Ham seri kısaldığında olayın hangi SAATTE olduğu burada kalır (günlük özet bunu kaybeder). Ham serinin ~%1,7'si kadar yer kaplar. |
| `uptime_checks` | 180 gün | 30 g | `site.monitor.series.uptime.retention-days` | `checked_at < ?` | Erişilebilirlik ham serisi. |
| `certificate_checks` | 180 gün | 30 g | `site.monitor.series.certificate.retention-days` | `checked_at < ?` | Sertifika kontrol ham serisi. |
| `port_checks` | 180 gün | 30 g | `site.monitor.series.port.retention-days` | `checked_at < ?` | Port kontrol ham serisi (30 sn kadans). |
| `keyword_results` | 180 gün | 30 g | `site.monitor.series.keyword.retention-days` | `checked_at < ?` | İçerik kontrol ham serisi (sayfa parçacığı içerebilir). |
| `ping_checks` | 180 gün | 30 g | `site.monitor.series.ping.retention-days` | `checked_at < ?` | Ping ham serisi (30 sn kadans). |
| `dns_records` | 180 gün | 30 g | `site.monitor.series.dns.retention-days` | `checked_at < ? AND monitor_id IN (SELECT id FROM dns_monitors) AND id NOT IN (SELECT MAX(id) FROM dns_recor…` | DNS kayıt serisi. Yaşayan her monitörün EN YENİ satırı baseline'dır (değişiklik tespiti ona bakar) → asla silinmez. |
| `login_anomaly_ack` | öksüz temizliği | — | — | `audit_id NOT IN (SELECT id FROM audit_log)` | Denetim satırı budanmış anomali onayı damgası (System Health #3). Ebeveyn audit_log yaş kuralına uyar. |
| `dns_records` | öksüz temizliği | — | — | `monitor_id NOT IN (SELECT id FROM dns_monitors)` | Monitörü kalıcı silinmiş DNS serisi. FK/CASCADE yok; öksüz satırlar hiçbir yaş kuralına takılmıyordu. |
| `http_metric_minute` | 7 gün | 1 g | `site.monitor.metrics.http.retention-days` | `bucket_minute < ?` | Uygulamanın kendi HTTP metrik kovaları (dakikalık). Kısa tutulur; hacmi yüksektir. |
| `http_checks` | 180 gün | 30 g | `site.monitor.series.http.retention-days` | `checked_at < ?` | HTTP kontrol ham serisi (en hızlı büyüyen serilerden). |
| `page_resource_issues` | 90 gün | 1 g | `site.monitor.metrics.page-issues.retention-days` | `checked_at < ?` | Sayfa kaynak sorunları — kontrol başına 0..N satır. Ana kayıttan ÖNCE silinir (çocuk-ebeveyn sırası). |
| `page_checks` | 180 gün | 1 g | `site.monitor.metrics.page.retention-days` | `checked_at < ?` | Sayfa bütünlüğü kontrol serisi. |
| `scripted_checks` | 180 gün | 1 g | `site.monitor.metrics.scripted.retention-days` | `checked_at < ?` | Senaryo (k6) kontrol serisi — çıktı kuyruğu ve kontrol JSON'u içerir. |
| `pagespeed_resources` | 90 gün | 1 g | `site.monitor.metrics.pagespeed-resources.retention-days` | `keep_reason = 'BREACH' AND checked_at < ?` | Sayfa hızı kaynak kırılımı. YALNIZ eşik-ihlali anlarının donmuş delilleri yaşa göre silinir; keep_reason='LATEST' satırları retention DIŞIDIR çünkü her kontrolde zaten üzerine yazılır — onları yaşa göre silmek son ölçümün kırılımını ekrandan kaybettirirdi. |
| `pagespeed_checks` | 180 gün | 1 g | `site.monitor.metrics.pagespeed.retention-days` | `checked_at < ?` | Sayfa hızı ölçüm serisi (süre/TTFB/bayt/istek özeti). |
| `system_heartbeat` | 30 gün | 7 g | `site.monitor.heartbeat.retention-days` | `recorded_at < CAST(? AS timestamp)` | Dakikada bir yazılan canlılık nabzı. recorded_at gerçek TIMESTAMP'tir → parametre CAST edilir. |
| `domain_checks` | 180 gün | 30 g | `site.monitor.series.domain.retention-days` | `checked_at < ? AND id NOT IN (SELECT MAX(id) FROM domain_checks GROUP BY monitor_id) AND id NOT IN (SELECT …` | Alan adı kontrol serisi. İKİ baseline korunur: her monitörün en yeni satırı ve en yeni source<>'NONE' satırı. |
| `network_outage_events` | 365 gün | 30 g | `site.monitor.network-outage.retention-days` | `detected_at < ?` | Ağ kesintisi olayları (nadir). |
| `weekly_availability_log` | 1095 gün | 180 g | `site.monitor.weekly-availability.retention-days` | `created_at < ?` | Haftalık erişilebilirlik gönderim kaydı (takım × hafta). 3 yıl: yıllık karşılaştırma için. |
| `cert_inventory_report_log` | 730 gün | 180 g | `site.monitor.cert-inventory-report.retention-days` | `created_at < ?` | Aylık envanter raporu gönderim kaydı (yıl × ay). Kaç kayıt/kaç bulgu vardı bilgisini taşır; hijyen trendinin yıllar arası karşılaştırması için 2 yıl. |
| `alert_storms` | 365 gün | 90 g | `site.monitor.storm.retention-days` | `resolved = true AND resolved_at < ?` | Alarm fırtınası kayıtları. Yalnız çözülmüş fırtınalar silinir. |
| `retention_run_item` | 180 gün | 30 g | `site.monitor.retention.run-history-retention-days` | `created_at < ?` | Gece temizliği çalışma detayı (tablo başına silinen satır). Ana kayıttan ÖNCE silinir. |
| `retention_run` | 180 gün | 30 g | `site.monitor.retention.run-history-retention-days` | `started_at < ?` | Gece temizliği çalışma özeti — sağlık sinyali ve ekrandaki geçmiş buradan beslenir. |
| `deployment_history` | sınırlı büyür | — | — | — | Sürüm & dağıtım geçmişi (K10, 2026-09-10): her pod açılışı bir satır (sürüm/commit/ortam/helm rev). ASLA silinmez — 'hangi sürüm ne zaman devreye alındı' sorusunun tek kalıcı kaynağı; yalnız elle girilen (MANUAL) satır admin tarafından silinebilir. Büyüme sınırlı (~günde birkaç satır). |
| `schema_table_registry` | sınırlı büyür | — | — | — | Tablo kayıt defteri (SQL Playground, 2026-09-11): tablo başına TEK satır — ilk görülme anı ve son veri değişimi. Tablo sayısı kadar satır; asla silinmez (silinirse 'oluşturma' bilgisi kaybolur). |

## Kapsam güvencesi

`RetentionCoverageTest` her kalıcı tabloyu tarar: politikası olmayan ve gerekçeli
muafiyet listesinde bulunmayan bir tablo varsa **derleme kırmızıya döner**.
Bu, "yeni izleme türü eklendi, temizliği unutuldu" hata sınıfını kalıcı olarak kapatır.

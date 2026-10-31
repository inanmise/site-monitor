package com.sitemonitor.service.retention;

import com.sitemonitor.service.retention.RetentionPolicy.DataClass;
import com.sitemonitor.service.retention.RetentionPolicy.Mode;
import com.sitemonitor.service.retention.RetentionPolicy.TimeKind;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Saklama politikalarının TEK DOĞRULUK KAYNAĞI — {@code PermissionCatalog} ile aynı desen.
 *
 * <p><b>Liste sırası = çalıştırma sırasıdır.</b> Gerçek FK kısıtı olmasa da çocuk tablolar
 * ebeveynlerinden önce, öksüz temizlikleri ebeveyn silindikten SONRA gelmelidir; bu sıra
 * eskiden {@code cleanupOldLogs} gövdesindeki satır sırasıyla korunuyordu, artık burada yaşıyor.
 *
 * <p>{@code where} alanındaki SQL parçaları, dönüşüm öncesi koddan BİREBİR alınmıştır;
 * {@code RetentionSqlIdentityTest} üretilen cümleleri eski metinlerle karşılaştırır (davranış-nötrlük).
 */
public final class RetentionCatalog {

    private RetentionCatalog() { }

    /** Legal hold: açıkken HİÇBİR silme yapılmaz (soruşturma/denetim valfi). */
    public static final String HOLD_KEY = "site.monitor.retention.hold-enabled";
    /** Batch dilim boyutu (mevcut anahtar). */
    public static final String BATCH_KEY = "site.monitor.retention.purge-batch-size";

    /**
     * Gece temizliğinin zamanlaması — TEK doğruluk kaynağı.
     *
     * <p>Bu ifade dört yerde birden yazılıydı ({@code SchedulerService} annotation'ı, admin API'sinin
     * {@code cleanup_cron} alanı, {@link RetentionDocGenerator} ve {@code docs/db-scaling.md}); saat
     * değişince arayüz ile gerçek çalışma zamanı ayrışırdı. Artık hepsi buradan okur.
     *
     * <p>{@code ZONE} zorunlu: konteynerde JVM saat dilimi GMT olduğu için zone'suz bir cron
     * "gece 03:00" yerine 06:00 İstanbul'da koşardı (2026-08-10'da prod'da tam olarak bu oldu).
     */
    public static final String CLEANUP_CRON_KEY = "site.monitor.scheduler.cleanup-cron";
    public static final String CLEANUP_CRON_DEFAULT = "0 0 3 * * *";
    public static final String CLEANUP_ZONE = "Europe/Istanbul";

    private static RetentionPolicy age(String id, String table, String col, String settingKey,
                                       int def, int min, boolean batched, DataClass dc, String why) {
        return new RetentionPolicy(id, table, col, TimeKind.ISO_STRING, settingKey, def, min, false,
                "{t}", Mode.AGE, batched, dc, why);
    }

    private static RetentionPolicy guarded(String id, String table, String col, String settingKey,
                                           int def, int min, String where, DataClass dc, String why) {
        return new RetentionPolicy(id, table, col, TimeKind.ISO_STRING, settingKey, def, min, false,
                where, Mode.AGE, false, dc, why);
    }

    private static RetentionPolicy orphan(String id, String table, String where, DataClass dc, String why) {
        return new RetentionPolicy(id, table, null, TimeKind.ISO_STRING, null, 0, 0, false,
                where, Mode.ORPHAN_ONLY, false, dc, why);
    }

    private static RetentionPolicy info(String id, String table, Mode mode, DataClass dc, String why) {
        return new RetentionPolicy(id, table, null, TimeKind.ISO_STRING, null, 0, 0, false,
                null, mode, false, dc, why);
    }

    /**
     * Çalıştırma sırasına göre tüm politikalar. Ham izleme serileri TÜR BAZINDA ayrı anahtar alır:
     * hepsi 180 gün varsayılanla başlar (eski tek {@code tsCutoff} davranışı), ama ileride tür bazında
     * farklılaştırmak kod değişikliği gerektirmez.
     */
    public static final List<RetentionPolicy> ALL = List.of(

        // ── Rollup (ham seriler silinse de uzun-dönem trend burada yaşar) ──────────────────────
        new RetentionPolicy("rollup-daily", "monitor_check_daily", "day", TimeKind.DATE10,
                "site.monitor.rollup.retention-days", 730, 90, false, "{t}", Mode.AGE, false,
                DataClass.OPERATIONAL,
                "Günlük özet (uptime/yanıt süresi trendi). Ham seriler kısalsa da 2 yıllık trend korunur."),

        new RetentionPolicy("rollup-hourly", "monitor_check_hourly", "hour_bucket", TimeKind.DATE13,
                "site.monitor.rollup.hourly-retention-days", 365, 60, false, "{t}", Mode.AGE, false,
                DataClass.OPERATIONAL,
                "Saatlik özet. Ham seri kısaldığında olayın hangi SAATTE olduğu burada kalır "
                + "(günlük özet bunu kaybeder). Ham serinin ~%1,7'si kadar yer kaplar."),

        // ── Sayfa kullanımı (2026-09-13): kullanıcı×sekme×gün ping sayısı — System Health "kim neyi kullanıyor" ──
        new RetentionPolicy("page-usage-daily", "page_usage_daily", "day", TimeKind.DATE10,
                "site.monitor.page-usage.retention-days", 90, 7, false, "{t}", Mode.AGE, false,
                DataClass.PERSONAL,
                "Günlük sayfa kullanımı özeti (kullanıcı adı + sekme anahtarı + ping sayısı; URL/parametre yok). "
                + "System Health kullanıcı etkinliği bölümünün 'en çok kullanılan sayfalar / hiç açılmayanlar / "
                + "takım benimseme' tabloları buradan beslenir. Kişisel veri olduğundan kısa tutulur."),

        // ── Denetim ve sistem kayıtları ───────────────────────────────────────────────────────
        age("audit-log", "audit_log", "event_time", "site.monitor.audit.retention-days",
                365, 30, true, DataClass.SECURITY_AUDIT,
                "Kullanıcı eylem denetimi. Silmeden ÖNCE JSONL arşivi yazılır; süre uyum birimi onayına tabidir."),
        // batched=false BİLİNÇLİ: batch'li silme büyük seri tablolar için var (id + ANALYZE).
        // Yapılandırma değişiklikleri kontrol kayıtları gibi akmaz — hacim düşük, tek DELETE yeter.
        guarded("monitor-change-log", "monitor_change_log", "created_at",
                "site.monitor.monitoring.change-retention-days", 730, 30,
                "{t} AND resource_kind <> 'SYSTEM'",
                DataClass.SECURITY_AUDIT,
                "İzleme yapılandırması değişiklik geçmişi (kim/ne zaman/hangi IP/neyi değiştirdi). "
                + "audit_log'dan UZUN tutulur (730 gün): ayrı tablo olmasının sebeplerinden biri de budur — "
                + "denetim değeri yüksek, hacim düşük (yapılandırma değişiklikleri kontrol kayıtları gibi akmaz). "
                + "SYSTEM satırları HARİÇ: geri doldurmanın 'koştu' nişanı orada duruyor; silinirse bir "
                + "sonraki açılış geçmişi ikinci kez doldurmaya kalkardı."),
        age("user-push-deliveries", "user_push_deliveries", "created_at",
                "site.monitor.userpush.retention-days",
                1095, 90, true, DataClass.PERSONAL,
                "Kişi-bazlı webhook (push) teslimat günlüğü — kime, ne zaman, hangi içerikle, hangi "
                + "alarm için, sonuç ne + API'nin verdiği notificationId. ÇOK UZUN saklama (3 yıl) "
                + "bilinçli ürün kararı (2026-08-27): kurum tarafında iz sürme ve denetim için "
                + "kanıt zinciri. Sicil içerir → PERSONAL."),
        age("notification-logs", "notification_logs", "sent_at", "site.monitor.notification.retention-days",
                365, 30, true, DataClass.PERSONAL,
                "Gönderilen bildirim geçmişi (alıcı adı/e-postası içerir). Kişisel veri saklama süreleri "
                + "1 yılda eşitlendi (2026-08 kullanıcı kararı) — denetimde tek bir pencere savunulur."),
        age("domain-expiry-reminders", "domain_expiry_reminders", "sent_at", "site.monitor.notification.retention-days",
                365, 30, false, DataClass.PERSONAL,
                "Alan adı bitiş hatırlatma izleri (2026-09-22): eşik başına bir kez gönderim kaydı + alıcı e-postaları. "
                + "Bildirim geçmişiyle AYNI pencere (aynı ayar anahtarı): tekrar-gönderim kilidi bitiş tarihi + eşik "
                + "çiftine bağlı; 30 gün alt sınırı en gevşek eşiğin (60 gün) yarısıdır, purge sonrası daha sıkı eşik "
                + "zaten gönderilmiş/kapsanmış olur — ikinci e-posta gitmez."),
        guarded("noc-deliveries", "noc_deliveries", "created_at", "site.monitor.notification.retention-days",
                365, 30,
                "{t} AND (alert_event_id IS NULL OR alert_event_id NOT IN (SELECT id FROM alert_events WHERE resolved = false)) "
                + "AND (storm_id IS NULL OR storm_id NOT IN (SELECT id FROM alert_storms WHERE resolved = false))",
                DataClass.OPERATIONAL,
                "7/24 İzleme Ekibi (NOC) teslim izi (2026-09-27): alarm/fırtına başına açılış ve çözüm e-postasının "
                + "gidip gitmediği, grup kimlikleri ve alıcı SAYISI (adres yok — gövde ve alıcı notification_logs'ta, "
                + "maskeli). Bildirim geçmişiyle AYNI pencere (aynı ayar anahtarı). HÂLÂ AÇIK alarmın ya da sürmekte olan "
                + "fırtınanın satırı SİLİNMEZ: açılış izi giderse aynı alarm için NOC'a ikinci açılış gider, kapanışta da "
                + "'açılış gitmedi' sanılıp ÇÖZÜLDÜ hiç gitmez."),
        guarded("alert-escalation-steps", "alert_escalation_steps", "sent_at", "site.monitor.notification.retention-days",
                365, 30,
                "{t} AND alert_event_id NOT IN (SELECT id FROM alert_events WHERE resolved = false)",
                DataClass.OPERATIONAL,
                "Zamana bağlı eskalasyon adımı kararları (2026-10-01): alarm × gecikmeli kişi × seviye başına adımın "
                + "gönderildiği / neden atlandığı ve tekilleştirme kilidi (kişi kimliği, adres YOK — alıcı ve gövde "
                + "notification_logs'ta). Bildirim geçmişiyle AYNI pencere (aynı ayar anahtarı). HÂLÂ AÇIK alarmın satırı "
                + "SİLİNMEZ: silinirse aynı alarm için aynı kişiye ikinci adım gider ve kişi alarmın 'döngüsünden' düşer."),
        guarded("quiet-digest-items", "quiet_digest_items", "deferred_at", "site.monitor.notification.retention-days",
                365, 30,
                "{t} AND digest_sent_at IS NOT NULL",
                DataClass.OPERATIONAL,
                "Sessiz saat özetine ertelenen alarmlar (2026-10-01): alarm × takım × pencere başına erteleme kaydı, "
                + "özetin gönderim durumu ve tekilleştirme kilidi (adres YOK — alıcı ve gövde notification_logs'ta). Bildirim "
                + "geçmişiyle AYNI pencere (aynı ayar anahtarı). Özeti HENÜZ GİTMEMİŞ satır SİLİNMEZ: silinirse ertelenen "
                + "bildirim hiç teslim edilmez ve çözümü ayrı posta olarak gider."),
        age("sql-query-history", "sql_query_history", "executed_at", "site.monitor.sql-history.retention-days",
                365, 7, false, DataClass.SECURITY_AUDIT,
                "Admin SQL çalışma alanı geçmişi (kullanıcı + serbest SQL metni). Denetim penceresiyle "
                + "aynı 1 yıl (2026-08 kararı); hacmi düşüktür."),

        // ── Ham izleme serileri (tür bazında ayarlanır; eski ortak 180g davranışı korunur) ─────
        age("series-uptime", "uptime_checks", "checked_at", "site.monitor.series.uptime.retention-days",
                180, 30, true, DataClass.OPERATIONAL, "Erişilebilirlik ham serisi."),
        age("series-certificate", "certificate_checks", "checked_at", "site.monitor.series.certificate.retention-days",
                180, 30, true, DataClass.OPERATIONAL, "Sertifika kontrol ham serisi."),
        age("series-port", "port_checks", "checked_at", "site.monitor.series.port.retention-days",
                180, 30, true, DataClass.OPERATIONAL, "Port kontrol ham serisi (30 sn kadans)."),
        age("series-keyword", "keyword_results", "checked_at", "site.monitor.series.keyword.retention-days",
                180, 30, true, DataClass.OPERATIONAL, "İçerik kontrol ham serisi (sayfa parçacığı içerebilir)."),
        age("series-ping", "ping_checks", "checked_at", "site.monitor.series.ping.retention-days",
                180, 30, true, DataClass.OPERATIONAL, "Ping ham serisi (30 sn kadans)."),
        guarded("series-dns", "dns_records", "checked_at", "site.monitor.series.dns.retention-days",
                180, 30,
                // Baseline koruması YALNIZ yaşayan monitörler için. Eskiden GROUP BY monitor_id
                // silinmiş monitörlerin id'lerini de grupluyordu, dolayısıyla her ÖKSÜZ monitör
                // için en yeni satır sonsuza kadar korunuyordu — koruma, temizlenemeyen bir
                // kalıntıya dönüşmüştü. Öksüzlerin tamamını aşağıdaki dns-records-orphan alır.
                "{t} AND monitor_id IN (SELECT id FROM dns_monitors) "
                + "AND id NOT IN (SELECT MAX(id) FROM dns_records "
                + "WHERE monitor_id IN (SELECT id FROM dns_monitors) GROUP BY monitor_id)",
                DataClass.OPERATIONAL,
                "DNS kayıt serisi. Yaşayan her monitörün EN YENİ satırı baseline'dır (değişiklik tespiti ona bakar) → asla silinmez."),
        orphan("anomaly-ack-orphan", "login_anomaly_ack",
                "audit_id NOT IN (SELECT id FROM audit_log)", DataClass.OPERATIONAL,
                "Denetim satırı budanmış anomali onayı damgası (System Health #3). Ebeveyn audit_log yaş kuralına uyar."),
        orphan("dns-records-orphan", "dns_records",
                "monitor_id NOT IN (SELECT id FROM dns_monitors)", DataClass.OPERATIONAL,
                "Monitörü kalıcı silinmiş DNS serisi. FK/CASCADE yok; öksüz satırlar hiçbir yaş kuralına takılmıyordu."),
        age("http-metric-minute", "http_metric_minute", "bucket_minute", "site.monitor.metrics.http.retention-days",
                7, 1, false, DataClass.OPERATIONAL,
                "Uygulamanın kendi HTTP metrik kovaları (dakikalık). Kısa tutulur; hacmi yüksektir."),
        age("system-metric-samples", "system_metric_samples", "sampled_at", "site.monitor.metrics.system.retention-days",
                7, 1, false, DataClass.OPERATIONAL,
                "SiteMonitor'ün kendi çalışma metrikleri (JVM, havuzlar, HTTP, günlük olayları) — pod başına dakikada bir satır "
                + "(Sistem Sağlığı → Metrikler, 2026-10-13). Kısa tutulur."),
        guarded("login-anomaly", "login_anomaly_incident", "opened_at", "site.monitor.failed-login.retention-days",
                365, 7, "resolved = true AND {t}", DataClass.SECURITY_AUDIT,
                "Başarısız giriş anomali olayları. Yalnız ÇÖZÜLMÜŞ olanlar silinir; açık olaylar durur. "
                + "Güvenlik penceresiyle aynı 1 yıl (2026-08 kararı)."),
        age("series-http", "http_checks", "checked_at", "site.monitor.series.http.retention-days",
                180, 30, true, DataClass.OPERATIONAL, "HTTP kontrol ham serisi (en hızlı büyüyen serilerden)."),

        // ── Sayfa bütünlüğü: ÖNCE çocuk (issues), SONRA ana (checks) ───────────────────────────
        age("page-resource-issues", "page_resource_issues", "checked_at", "site.monitor.metrics.page-issues.retention-days",
                90, 1, true, DataClass.OPERATIONAL,
                "Sayfa kaynak sorunları — kontrol başına 0..N satır. Ana kayıttan ÖNCE silinir (çocuk-ebeveyn sırası)."),
        age("page-checks", "page_checks", "checked_at", "site.monitor.metrics.page.retention-days",
                180, 1, true, DataClass.OPERATIONAL, "Sayfa bütünlüğü kontrol serisi."),
        age("page-resource-scans", "page_resource_scans", "started_at", "site.monitor.page.resource-scan.retention-days",
                365, 30, false, DataClass.OPERATIONAL,
                "Sayfa Bütünlüğü \"Kaynak analizi\" çalıştırmaları (2026-10-31, yalnız kullanıcı isteyince): özet + analiz 1 yıl "
                + "(yükleme süresi grafiği ve geçmiş), nesne listesi izleme başına son 20 çalıştırmada (servis kendisi bırakır). "
                + "Sayfanın nesne adreslerini ve çalıştıranın görünen adını içerir."),
        age("series-scripted", "scripted_checks", "checked_at", "site.monitor.metrics.scripted.retention-days",
                180, 1, true, DataClass.OPERATIONAL,
                "Senaryo (k6) kontrol serisi — çıktı kuyruğu ve kontrol JSON'u içerir."),

        // ── Sayfa hızı: ÖNCE çocuk (kaynak kırılımı), SONRA ana (ölçümler) ────────────────────
        guarded("pagespeed-resources", "pagespeed_resources", "checked_at",
                "site.monitor.metrics.pagespeed-resources.retention-days",
                90, 1, "keep_reason = 'BREACH' AND {t}", DataClass.OPERATIONAL,
                "Sayfa hızı kaynak kırılımı. YALNIZ eşik-ihlali anlarının donmuş delilleri yaşa göre silinir; "
                + "keep_reason='LATEST' satırları retention DIŞIDIR çünkü her kontrolde zaten üzerine yazılır — "
                + "onları yaşa göre silmek son ölçümün kırılımını ekrandan kaybettirirdi."),
        age("pagespeed-checks", "pagespeed_checks", "checked_at", "site.monitor.metrics.pagespeed.retention-days",
                180, 1, true, DataClass.OPERATIONAL,
                "Sayfa hızı ölçüm serisi (süre/TTFB/bayt/istek özeti)."),

        // ── Veritabanı sorgu izlemesi (2026-10-31): alarm anı satırları + çalıştırma serisi ────────────────────
        guarded("dbquery-alert-rows", "dbquery_alert_rows", "captured_at", "site.monitor.dbquery.alert-rows.retention-days",
                90, 1, "{t} AND alert_event_id NOT IN (SELECT id FROM alert_events WHERE resolved = false)", DataClass.PERSONAL,
                "Veritabanı sorgu alarmının ALARM ANINDAKİ sonuç satırları (≤ 50 satır × 20 kolon, maskeli kolonlar yazılmaz): alarm "
                + "maili ve alarm ayrıntısında gösterilir. Sorgu sonucu kişisel / iş verisi içerebilir. HÂLÂ AÇIK alarmın satırı "
                + "SİLİNMEZ (alarm ayrıntısı kanıtını kaybetmesin); izleme silinince aynı işlemde silinir."),
        age("dbquery-checks", "dbquery_checks", "checked_at", "site.monitor.metrics.dbquery.retention-days",
                180, 1, true, DataClass.OPERATIONAL,
                "Veritabanı sorgu izlemesi çalıştırma serisi: durum, süre, satır SAYISI, ölçülen değer, kural sonuçları ve maskeli "
                + "hata metni — satır verisi YOKTUR (izleme bir veri çekme aracı değildir)."),

        new RetentionPolicy("system-heartbeat", "system_heartbeat", "recorded_at", TimeKind.TIMESTAMP,
                "site.monitor.heartbeat.retention-days", 30, 7, false, "{t}", Mode.AGE, false,
                DataClass.OPERATIONAL,
                "Dakikada bir yazılan canlılık nabzı. recorded_at gerçek TIMESTAMP'tir → parametre CAST edilir."),

        guarded("series-domain", "domain_checks", "checked_at", "site.monitor.series.domain.retention-days",
                180, 30,
                // series-dns ile AYNI düzeltme (kardeş süpürmesi): baseline koruması YALNIZ yaşayan
                // monitörler için. GROUP BY monitor_id silinmiş monitörlerin id'lerini de gruplayınca
                // her öksüz monitör için İKİ satır sonsuza kadar korunuyordu ve domain_checks için
                // bir …-orphan kuralı da yoktu — koruma, temizlenemeyen bir kalıntıya dönüşmüştü.
                "{t} AND monitor_id IN (SELECT id FROM domain_monitors) "
                + "AND id NOT IN (SELECT MAX(id) FROM domain_checks "
                + "WHERE monitor_id IN (SELECT id FROM domain_monitors) GROUP BY monitor_id) "
                + "AND id NOT IN (SELECT MAX(id) FROM domain_checks "
                + "WHERE source <> 'NONE' AND monitor_id IN (SELECT id FROM domain_monitors) GROUP BY monitor_id)",
                DataClass.OPERATIONAL,
                "Alan adı kontrol serisi. Yaşayan her monitör için İKİ baseline korunur: en yeni satır ve en yeni source<>'NONE' satırı."),
        orphan("domain-checks-orphan", "domain_checks",
                "monitor_id NOT IN (SELECT id FROM domain_monitors)", DataClass.OPERATIONAL,
                "Monitörü kalıcı silinmiş alan adı kontrol satırları — deleteDomain seriye dokunmuyor, baseline koruması da öksüzleri tutuyordu."),
        age("diagnostic-runs", "diagnostic_runs", "executed_at", "site.monitor.diagnostics.retention-days",
                365, 7, false, DataClass.PERSONAL,
                "Elle çalıştırılan tanılamalar (çalıştıran kullanıcı ve kaynak IP içerir). "
                + "Kişisel veri penceresiyle aynı 1 yıl (2026-08 kararı)."),
        guarded("alert-events", "alert_events", "resolved_at", "site.monitor.alert.retention-days",
                365, 90, "resolved = true AND {t}", DataClass.PERSONAL,
                "Alarm olayları. Yalnız ÇÖZÜLMÜŞ alarmlar silinir — açık/onaylanmış alarmlar ASLA silinmez."),

        // ── Sorun bildirimleri: görseller → rapor → öksüz mail logları ─────────────────────────
        // DİKKAT: "resolved_at" bu tabloda DEĞİL, EBEVEYN tablodadır (login_issue_reports) —
        // AGE_VIA_PARENT modunda zaman kolonu ebeveyne aittir ve WHERE onu alt sorguda kullanır.
        // Silme doğru ({t} kullanılmıyor), ama RetentionService.bounds() kolonu ÇOCUK tabloda
        // arıyordu ve hatayı catch(Exception) ile yutuyordu: Ayarlar → Veri Saklama ekranında bu
        // kural için "en eski/en yeni kayıt" kalıcı boş görünüyor, operatör "bu tabloda veri yok"
        // sanıyordu. Düzeltme bounds() tarafında; kapı RetentionColumnExistsTest.
        new RetentionPolicy("login-issue-images", "login_issue_report_images", "resolved_at", TimeKind.ISO_STRING,
                "site.monitor.login-issue.retention-days", 365, 90, false,
                "report_id IN (SELECT id FROM login_issue_reports WHERE status = 'RESOLVED' AND resolved_at < ?)",
                Mode.AGE_VIA_PARENT, false, DataClass.PERSONAL,
                "Sorun bildirimi ekran görüntüleri (base64, ≤5 adet). Ebeveyn raporun yaşına göre, ondan ÖNCE silinir."),
        guarded("login-issue-reports", "login_issue_reports", "resolved_at", "site.monitor.login-issue.retention-days",
                365, 90, "status = 'RESOLVED' AND {t}", DataClass.PERSONAL,
                "Kullanıcı sorun bildirimleri (e-posta, IP, serbest metin). Yalnız ÇÖZÜLMÜŞ olanlar; açık bildirimler durur."),
        orphan("login-issue-mail-logs", "login_issue_mail_logs",
                "report_id NOT IN (SELECT id FROM login_issue_reports)", DataClass.PERSONAL,
                "Bildirim mail geçmişi — raporu silinince öksüz kalır; mail kaydı raporuyla birlikte ölür."),
        orphan("issue-report-comments-orphan", "issue_report_comments",
                "report_id NOT IN (SELECT id FROM login_issue_reports)", DataClass.PERSONAL,
                "Sorun bildirimi konuşma dizisi (yazar adı + serbest metin + durum geçişleri). Raporuyla birlikte silinir; öksüz kalan temizlenir."),
        // ── Kodla giriş istekleri (2026-10-02) ─────────────────────────────────────────────────
        age("login-otp-challenges", "login_otp_challenges", "created_at", "site.monitor.login.otp.retention-days",
                30, 1, false, DataClass.PERSONAL,
                "Push / e-posta ile kodla giriş istekleri: istek başına TEK satır — kullanıcı adı, kanal, kodun HMAC özeti (kodun "
                + "kendisi YOK; istekte girilen telefon / e-posta da YOK — 2026-10-03, yalnız eşleşme sonucu `delivery_status`'ta), "
                + "süre, deneme sayısı, durum, IP ve tarayıcı. Satır en çok birkaç dakika işe yarar (kod ≤ 300 sn); "
                + "IP / kullanıcı sınırları 15 dakikalık pencereye bakar. Olayların kalıcı izi denetim kaydındadır."),

        // ── İçerik ve olay kayıtları ──────────────────────────────────────────────────────────
        age("weekly-report-images", "weekly_report_images", "created_at", "site.monitor.weekly-report.image-retention-days",
                730, 30, false, DataClass.CONTENT,
                "Haftalık rapor görselleri (BYTEA, satır başına MB'lar). Rapor METNİ korunur, yalnız çok eski görsel silinir."),
        age("activity-log", "activity_log", "activity_time", "site.monitor.activity.retention-days",
                365, 1, true, DataClass.PERSONAL,
                "Birleşik aktivite akışı — her kontrol +1 satır (en hızlı büyüyen seri). Aktör kullanıcı adı içerir. "
                + "2026-08 kullanıcı kararıyla 90 → 365 gün: kişisel veri pencereleri eşitlendi. "
                + "DİKKAT: satır sayısı ~4 katına çıkar; büyüme Ayarlar → Veri Saklama'dan izlenmeli."),
        age("network-outage", "network_outage_events", "detected_at", "site.monitor.network-outage.retention-days",
                365, 30, false, DataClass.OPERATIONAL, "Ağ kesintisi olayları (nadir)."),
        new RetentionPolicy("incident-records", "incident_records", "occurred_at", TimeKind.ISO_STRING,
                "site.monitor.incident.retention-days", 0, 0, true,
                "status = 'RESOLVED' AND {t}", Mode.AGE, false, DataClass.CONTENT,
                "Elle girilen SRE olay kayıtları. VARSAYILAN 0 = hiç silinmez (opt-in); >0 verilirse yalnız ÇÖZÜLMÜŞ olaylar."),

        // ── 2026-08 taramasıyla kapatılan boşluklar (daha önce HİÇ temizlenmiyordu) ────────────
        age("incident-images", "incident_images", "created_at", "site.monitor.incident.image-retention-days",
                730, 90, false, DataClass.CONTENT,
                "Olay görselleri (BYTEA, ≤5 MB/satır). Olay METNİ korunur — weekly_report_images deseni. "
                + "Daha önce hiç silinmiyordu: en yüksek satır-boyutu × sonsuz saklama riski."),
        orphan("incident-images-orphan", "incident_images",
                "incident_id IS NOT NULL AND incident_id NOT IN (SELECT id FROM incident_records)",
                DataClass.CONTENT,
                "Olayı silinmiş görseller. Olay silme cascade YAPMIYOR → her silinen olay MB'larca öksüz bırakıyordu."),
        new RetentionPolicy("incident-images-draft", "incident_images", "created_at", TimeKind.ISO_STRING,
                "site.monitor.incident.draft-image-retention-days", 7, 1, false,
                "incident_id IS NULL AND {t}", Mode.AGE, false, DataClass.CONTENT,
                "Kaydedilmeyen taslak yüklemeleri: incident_id kalıcı olarak NULL kalır, hiçbir olaya bağlanmaz."),
        orphan("page-resource-scans-orphan", "page_resource_scans",
                "monitor_id NOT IN (SELECT id FROM page_monitors)", DataClass.OPERATIONAL,
                "İzlemesi silinmiş kaynak analizleri (2026-10-31). İzleme silinirken servis siler; silme başarısız olursa "
                + "öksüz kalan satır burada temizlenir."),
        orphan("incident-postmortems-orphan", "incident_postmortems",
                "incident_id NOT IN (SELECT id FROM incident_records)", DataClass.CONTENT,
                "Olayı silinmiş postmortem raporları (2026-10-11). Olay silme temizliği başarısız olursa öksüz kalan satır burada silinir."),
        orphan("incident-action-items-orphan", "incident_action_items",
                "incident_id NOT IN (SELECT id FROM incident_records)", DataClass.PERSONAL,
                "Olayı silinmiş postmortem aksiyonları (2026-10-11): sahip adı içerir. Olay yaşadıkça aksiyonlar da yaşar."),
        orphan("alert-comments-orphan", "alert_comments",
                "alert_event_id NOT IN (SELECT id FROM alert_events)", DataClass.PERSONAL,
                "Alarm yorumları. alert_events 1 yılda siliniyor ama yorumlar kalıyordu → kalıcı öksüz."),
        orphan("noc-call-log-orphan", "noc_call_log",
                "alert_id IS NULL OR alert_id NOT IN (SELECT id FROM alert_events)", DataClass.PERSONAL,
                "7/24 arama kayıtları (2026-09-27): aranan kişinin adı + serbest not (telefon YOK). Uyarısıyla yaşar — "
                + "alert_events kendi politikasıyla silinince kayıt öksüz kalır ve burada temizlenir (alarm yorumları deseni)."),
        orphan("weekly-report-comments-orphan", "weekly_report_comments",
                "report_id NOT IN (SELECT id FROM weekly_reports)", DataClass.PERSONAL,
                "Haftalık rapor yorum dizisi (yazar adı + serbest metin). Raporuyla birlikte silinir; öksüz kalan temizlenir."),
        age("weekly-report-mails", "weekly_report_mails", "created_at", "site.monitor.weekly-report.mail-retention-days",
                730, 90, false, DataClass.PERSONAL,
                "Haftalık rapor mail geçmişi — gönderilen postanın TAM HTML gövdesini saklar (alıcı adresleriyle)."),
        age("weekly-availability", "weekly_availability_log", "created_at", "site.monitor.weekly-availability.retention-days",
                1095, 180, false, DataClass.OPERATIONAL,
                "Haftalık erişilebilirlik gönderim kaydı (takım × hafta). 3 yıl: yıllık karşılaştırma için."),
        age("cert-inventory-report", "cert_inventory_report_log", "created_at",
                "site.monitor.cert-inventory-report.retention-days",
                730, 180, false, DataClass.OPERATIONAL,
                "Aylık envanter raporu gönderim kaydı (yıl × ay). Kaç kayıt/kaç bulgu vardı bilgisini "
                + "taşır; hijyen trendinin yıllar arası karşılaştırması için 2 yıl."),
        age("cert-note-revisions", "certificate_note_revisions", "edited_at", "site.monitor.cert-note-revision.retention-days",
                730, 180, false, DataClass.PERSONAL,
                "Sertifika notu düzeltme geçmişi — her düzenlemede +1 satır, hiç silinmiyordu. Notun kendisi korunur."),
        guarded("alert-storms", "alert_storms", "resolved_at", "site.monitor.storm.retention-days",
                365, 90, "resolved = true AND {t}", DataClass.OPERATIONAL,
                "Alarm fırtınası kayıtları. Yalnız çözülmüş fırtınalar silinir."),
        orphan("alert-storm-members-orphan", "alert_storm_members",
                "NOT EXISTS (SELECT 1 FROM alert_storms s WHERE s.id = alert_storm_members.storm_id)", DataClass.OPERATIONAL,
                "Fırtına üyelik kaydı (kim hangi fırtınadaydı — gözlem/analiz ekranı). Fırtına satırı silinince yetim kalır."),
        orphan("storm-push-coverage-orphan", "storm_push_coverage",
                "NOT EXISTS (SELECT 1 FROM alert_storms s WHERE s.id = storm_push_coverage.storm_id) "
                + "OR NOT EXISTS (SELECT 1 FROM alert_events e WHERE e.id = storm_push_coverage.alert_event_id)",
                DataClass.OPERATIONAL,
                "Fırtına push'u ↔ üye alarm bağı (2026-10-04): hangi toplu fırtına push'unun hangi alarmı kapsadığı — alarm "
                + "detayı, push geçmişim ve teslimat günlüğü buradan okur. Kişi verisi yok (anahtar + kimlikler). Fırtına ya da "
                + "alarm kendi politikasıyla silinince yetim kalır ve burada temizlenir."),
        age("scripted-drafts", "scripted_drafts", "updated_at", "site.monitor.scripted.draft-retention-days",
                30, 1, false, DataClass.CONTENT,
                "k6 script düzenleme formunun otomatik kaydedilen taslakları. Kaydedilince silinirler; "
                + "burada kalanlar terk edilmiş oturumlardır (incident-images-draft ile aynı mantık)."),
        orphan("user-preferences-orphan", "user_preferences",
                "user_id NOT IN (SELECT id FROM app_users)", DataClass.PERSONAL,
                "Kişisel arayüz tercihleri (favori izlemeler, açılış sekmesi, kayıtlı görünümler, tarayıcı tercihleri aynası) — "
                + "kullanıcı başına tek satır. Kullanıcı silinince öksüz kalır ve burada temizlenir."),
        orphan("scripted-versions-orphan", "scripted_script_versions",
                "monitor_id NOT IN (SELECT id FROM scripted_monitors)",
                DataClass.CONTENT,
                "Monitörü silinmiş script sürümleri. Sürüm geçmişi monitör silinirken BİLİNÇLİ olarak "
                + "silinmiyor (denetim değeri); öksüz kalan satırlar burada temizlenir."),
        age("k6-compat-scans", "k6_compat_scans", "started_at", "site.monitor.scripted.compat-scan.retention-days",
                180, 7, false, DataClass.PERSONAL,
                "k6 uyumluluk taramaları (2026-10-29, k6 sürüm geçişi — gölge koşu): taramayı başlatan yöneticinin adı, "
                + "aday / etkin ikili sürümü ve sayaçlar. Geçiş bittikten sonra değeri azalır; kişisel veri penceresinin içinde."),
        orphan("k6-compat-results-orphan", "k6_compat_results",
                "scan_id NOT IN (SELECT id FROM k6_compat_scans)", DataClass.CONTENT,
                "Taraması silinmiş k6 uyumluluk sonuçları (izleme adı, durum, maskeli hata metni) — tarama kendi "
                + "politikasıyla silinince yetim kalır ve burada temizlenir."),

        // ── Merkezi izleme alarm akışı (2026-10-30): dış çağrı günlüğü ─────────────────────────────
        age("alarm-feed-requests", "alarm_feed_requests", "requested_at", "site.monitor.alarm-feed.log-retention-days",
                90, 7, true, DataClass.SECURITY_AUDIT,
                "Alarm akışının (GET /api/public/alarm-feed, belirteçsiz) dış çağrı günlüğü: zaman, istemci IP'si, ham "
                + "X-Forwarded-For, user-agent, sonuç, dönen satır sayısı, yanıt özeti. Dakikada bir çağıran istemci için "
                + "günde ≈ 1.440 satır; erişim kanıtı olarak denetim penceresinde tutulur."),
        age("alarm-feed-callers", "alarm_feed_callers", "last_seen_at", "site.monitor.alarm-feed.log-retention-days",
                90, 7, false, DataClass.PERSONAL,
                "Alarm akışını çağıran IP başına tek satır (ilk / son görülme, çağrı sayısı, son user-agent). Çağrı günlüğüyle "
                + "aynı pencere: o süre boyunca hiç çağırmayan IP silinir."),
        age("alarm-feed-blocks", "alarm_feed_blocks", "updated_at", "site.monitor.alarm-feed.log-retention-days",
                90, 7, false, DataClass.SECURITY_AUDIT,
                "Alarm akışının otomatik IP engelleri (IP başına tek satır: son engelin başlangıç / bitişi, tetikleyen "
                + "dakikalık çağrı sayısı, toplam engel sayısı, son user-agent, elle kaldıran). Bitmiş engel geçmiş ve "
                + "tekrarında süreyi katlamak için kalır; çağrı günlüğüyle aynı süre sonra silinir."),
        orphan("alarm-feed-responses-orphan", "alarm_feed_responses",
                "NOT EXISTS (SELECT 1 FROM alarm_feed_requests r WHERE r.response_hash = alarm_feed_responses.hash)",
                DataClass.CONTENT,
                "Alarm akışının döndürdüğü yanıt gövdelerinin birebir kopyası (gzip, özetle tekil; alarm hedefleri, takım "
                + "adları ve e-posta adresleri içerir). Hiçbir çağrı satırı göstermeyen gövde yetimdir ve silinir."),
        age("alarm-feed-responses", "alarm_feed_responses", "last_used_at", "site.monitor.alarm-feed.body-retention-days",
                30, 3, false, DataClass.CONTENT,
                "Alarm akışı yanıt gövdelerinin kopyası — son kullanılmasından (aynı gövde yeniden döndükçe tazelenir) bu "
                + "süre sonra silinir; çağrı satırı (özetiyle) kendi süresince kalır, ekranda 'gövde artık saklanmıyor' görünür."),
        info("alarm-feed-events", "alarm_feed_events", Mode.EXTERNAL, DataClass.OPERATIONAL,
                "Alarm akışının merkezi sisteme AÇIK bildirdiği olaylar (gönderilen anahtar + satırın kopyası: hedef, takım "
                + "adı / adresleri) — anahtar değişince, çözülünce, kapsamdan çıkınca ya da silinince eski olaya 'çözüldü' "
                + "göndermek için. AlarmFeedService saatte bir kendisi siler (teslim edilen kapanış: kapanışı ve ilk teslimi "
                + "pencere + 2 günden eski; hiç teslim edilemeyen: fark edilmesinden 7 gün; akış kapalıyken 30 günden eski "
                + "bütün satırlar)."),

        // ── Temizliğin kendi çalışma geçmişi (öz-referans) ─────────────────────────────────────
        age("retention-run-items", "retention_run_item", "created_at", "site.monitor.retention.run-history-retention-days",
                180, 30, false, DataClass.OPERATIONAL,
                "Gece temizliği çalışma detayı (tablo başına silinen satır). Ana kayıttan ÖNCE silinir."),
        age("retention-runs", "retention_run", "started_at", "site.monitor.retention.run-history-retention-days",
                180, 30, false, DataClass.OPERATIONAL,
                "Gece temizliği çalışma özeti — sağlık sinyali ve ekrandaki geçmiş buradan beslenir."),

        // ── Bilgi satırları: bu uygulama silmez, ama kapsanmış sayılır ─────────────────────────
        info("deployment-history", "deployment_history", Mode.BOUNDED, DataClass.OPERATIONAL,
                "Sürüm & dağıtım geçmişi (K10, 2026-09-10): her pod açılışı bir satır (sürüm/commit/ortam/helm rev). "
                + "ASLA silinmez — 'hangi sürüm ne zaman devreye alındı' sorusunun tek kalıcı kaynağı; yalnız elle "
                + "girilen (MANUAL) satır admin tarafından silinebilir. Büyüme sınırlı (~günde birkaç satır)."),
        info("today-panel-snapshots", "today_panel_snapshots", Mode.BOUNDED, DataClass.OPERATIONAL,
                "Sizin için — bugün paneli dünden bugüne görüntüsü (2026-09-23): saatte bir satır, kartların satır "
                + "KİMLİKLERİ (JSON). TodayPanelService her kayıtta 72 saatten eskileri siler → en fazla ~72 satır."),
        info("user-bulk-operations", "user_bulk_operations", Mode.BOUNDED, DataClass.PERSONAL,
                "Sistem geneli toplu pasife alma kayıtları (2026-10-02): çalıştırma başına TEK satır — yönetici, ölçüt, not, "
                + "pasife alınan kullanıcı kimlikleri. ASLA silinmez: 'kim, ne zaman, kimleri kapattı' sorusunun ve geri "
                + "almanın tek kaynağı (deployment_history ile aynı gerekçe). Büyüme sınırlı (yılda birkaç satır)."),
        info("system-maintenance-windows", "system_maintenance_windows", Mode.BOUNDED, DataClass.PERSONAL,
                "Sistem Bakım Modu pencereleri (2026-10-02): bakım başına TEK satır — plan/gerçek zamanlar, kim planladı/"
                + "başlattı/bitirdi, kapatılan oturum, engellenen giriş, susturulan bildirim sayıları. ASLA silinmez: bakım "
                + "geçmişinin ve denetimin tek kaynağı (user_bulk_operations ile aynı gerekçe). Büyüme sınırlı (yılda birkaç satır)."),
        age("system-maintenance-suppressions", "system_maintenance_suppressions", "first_at",
                "site.monitor.notification.retention-days", 365, 30, false, DataClass.OPERATIONAL,
                "Sistem bakımında susturulan alarm bildirimleri (2026-10-02): bakım × alarm başına TEK satır — bitişteki "
                + "bildirim telafisinin listesi ve kararı. Bildirim günlüğüyle (notification_logs) aynı süre: iz o günlükte, "
                + "bu tablo onun bakım özetidir. Kişisel veri yok (alarm kimliği + tetik + sayaç)."),
        info("executive-summary-reports", "executive_summary_reports", Mode.BOUNDED, DataClass.OPERATIONAL,
                "Aylık yönetici özeti gönderim kaydı (2026-10-10): ay başına TEK satır — durum, alıcı sayısı, dilim sonucu ve "
                + "gönderilen özetin içeriği (sayılar, takım adları; kişisel veri yok). 'Tam bir kez' kapısıdır; yılda 12 satır, "
                + "silinmez (geçmiş ayın ekranı bu kayıttan çizilir)."),
        orphan("executive-summary-team-reports-orphan", "executive_summary_team_reports",
                "NOT EXISTS (SELECT 1 FROM teams t WHERE t.id = executive_summary_team_reports.team_id)",
                DataClass.OPERATIONAL,
                "Takım yönetici özeti gönderim kaydı (2026-10-10): takım × ay başına TEK satır — durum, alıcı sayısı, dilim "
                + "sonucu ve gönderilen takım özetinin içeriği (sayılar, takım adı; kişisel veri yok). Zamanlanmış gönderim her "
                + "koşuda seçilebilir pencereden (24 ay) eski satırları siler; takım silinince yetim satır burada temizlenir."),
        orphan("executive-summary-team-settings-orphan", "executive_summary_team_settings",
                "NOT EXISTS (SELECT 1 FROM teams t WHERE t.id = executive_summary_team_settings.team_id)",
                DataClass.PERSONAL,
                "Takım yönetici özeti ayarı (2026-10-10): takım başına TEK satır — açık/kapalı, alıcı seçenekleri, seçilen "
                + "üyelerin kullanıcı kimlikleri ve ek e-posta adresleri. Takım silinince yetim satır burada temizlenir; aksi "
                + "hâlde takım sayısı kadar satır."),
        orphan("tech-error-channels-orphan", "tech_error_channels",
                "NOT EXISTS (SELECT 1 FROM teams t WHERE t.id = tech_error_channels.team_id)", DataClass.PERSONAL,
                "Kanal teknik hata raporu kanalları (2026-10-13): takım başına birkaç satır — kanal adı, şablon ve onu "
                + "kaydedenin kullanıcı kimliği. Kanal ve raporlar ekip kararıyla silinir (ASLA otomatik değil); takımı "
                + "silinmiş kanal burada temizlenir, raporları ve görselleri sonraki yetim kurallarıyla gider."),
        orphan("tech-error-reports-orphan", "tech_error_reports",
                "channel_id NOT IN (SELECT id FROM tech_error_channels)", DataClass.CONTENT,
                "Kanal teknik hata raporları (2026-10-13): kanal × ay başına TEK satır — ekibin girdiği oranlar, adetler, "
                + "anomali açıklamaları ve gönderim sonucu. Üst yönetime giden belgenin kaydı olduğu için süreyle "
                + "silinmez (kanal başına yılda 12 satır); kanalı silinmiş rapor burada temizlenir."),
        orphan("tech-error-report-images-orphan", "tech_error_report_images",
                "report_id NOT IN (SELECT id FROM tech_error_reports)", DataClass.CONTENT,
                "Teknik hata raporu grafik görselleri (2026-10-13; BYTEA, ≤ 5 MB/satır, rapor başına ≤ 40 MB). Raporuyla "
                + "yaşar (rapor silme aynı işlemde siler); raporu silinmiş öksüz görsel burada temizlenir."),
        orphan("tech-error-report-events-orphan", "tech_error_report_events",
                "report_id NOT IN (SELECT id FROM tech_error_reports)", DataClass.PERSONAL,
                "Teknik hata raporu değişiklik geçmişi (2026-10-14): kim, ne zaman, hangi alanı neyden neye değiştirdi; "
                + "görsel, şablon uygulama ve gönderim olayları (değişikliği yapanın adı ve kullanıcı kimliği). Rapor başına "
                + "en çok 500 satır (eskiler her eklemede budanır); rapor silinince aynı işlemde silinir, öksüz satır burada "
                + "temizlenir."),
        orphan("tech-error-approval-tokens-orphan", "tech_error_approval_tokens",
                "report_id NOT IN (SELECT id FROM tech_error_reports)", DataClass.PERSONAL,
                "Teknik hata raporu PO onayı e-posta bağlantı belirteçleri (2026-10-17): onaylayıcı başına bir satır, yalnız "
                + "SHA-256 özeti ve kullanıcı kimliği; 14 gün geçerli, tek kullanımlık; yeni istek eskileri 'yerine geçti' "
                + "damgalar (eski bağlantı sebebini söyler), süresi de dolmuş yerine geçilmiş satırlar bir sonraki istekte "
                + "budanır. Rapor silinince aynı işlemde silinir; öksüz satır burada temizlenir."),
        orphan("tech-error-reminders-orphan", "tech_error_reminders",
                "channel_id NOT IN (SELECT id FROM tech_error_channels)", DataClass.OPERATIONAL,
                "Teknik hata raporu hatırlatma talepleri (2026-10-17): kanal × dönem × tür (dönem / onay / atlama sebebi) başına "
                + "TEK satır — son hatırlatma günü ve sayacı (iki pod aynı hatırlatmayı iki kez göndermesin; atlama sebebi günlüğe "
                + "bir kez yazılsın). Kişisel veri yok. Hatırlatma işi her koşuda 6 aydır güncellenmemiş satırları siler; kanal "
                + "silinince aynı işlemde silinir, öksüz satır burada temizlenir."),
        orphan("tech-error-template-versions-orphan", "tech_error_template_versions",
                "channel_id NOT IN (SELECT id FROM tech_error_channels)", DataClass.PERSONAL,
                "Kanal şablonu sürüm geçmişi (2026-10-14): her şablon kaydının tam kopyası (≤ 64 KB) ve kaydedenin adı / "
                + "kullanıcı kimliği. Kanal başına en çok 100 sürüm (eskiler budanır); kanal silinince aynı işlemde silinir, "
                + "öksüz satır burada temizlenir."),
        orphan("dbquery-alert-rows-orphan", "dbquery_alert_rows",
                "NOT EXISTS (SELECT 1 FROM alert_events e WHERE e.id = dbquery_alert_rows.alert_event_id) "
                + "OR NOT EXISTS (SELECT 1 FROM dbquery_monitors m WHERE m.id = dbquery_alert_rows.monitor_id)", DataClass.PERSONAL,
                "Alarm anı sorgu sonucu satırları (2026-10-31): alarmı (alert_events kendi politikasıyla) ya da izlemesi silinmiş "
                + "satır öksüz kalır ve burada temizlenir (alarm yorumları deseni)."),
        orphan("db-connection-profiles-orphan", "db_connection_profiles",
                "NOT EXISTS (SELECT 1 FROM teams t WHERE t.id = db_connection_profiles.team_id)", DataClass.PERSONAL,
                "Takımın kayıtlı veritabanı bağlantıları (2026-10-31): sunucu / port / veritabanı / kullanıcı adı, ŞİFRELİ parola ve "
                + "ekleyen / güncelleyenin adı. Ekip kararıyla silinir (ASLA otomatik değil; kullanan izleme varken silinemez). Takım "
                + "silme, bağlantısı olan takımı reddeder (önce taşı); takımı yine de yok olmuş satır burada temizlenir."),
        orphan("dbquery-templates-orphan", "dbquery_templates",
                "NOT EXISTS (SELECT 1 FROM teams t WHERE t.id = dbquery_templates.team_id)", DataClass.PERSONAL,
                "Takımın sorgu şablonları (2026-10-31): takım başına en çok 50 adlandırılmış sorgu + kural kümesi ve ekleyen / "
                + "güncelleyenin adı. Ekip kararıyla silinir (ASLA otomatik değil); takımı silinmiş satır burada temizlenir."),
        orphan("tech-error-team-templates-orphan", "tech_error_team_templates",
                "NOT EXISTS (SELECT 1 FROM teams t WHERE t.id = tech_error_team_templates.team_id)", DataClass.PERSONAL,
                "Takımın teknik hata raporu şablon kütüphanesi (2026-10-18): takım başına en çok 20 adlandırılmış şablon "
                + "(≤ 64 KB), takım varsayılanı işareti ve ekleyen / güncelleyenin adı ve kullanıcı kimliği. Ekip kararıyla "
                + "silinir (ASLA otomatik değil); takımı silinmiş kütüphane satırı burada temizlenir (kanallarla aynı yol)."),
        info("schema-table-registry", "schema_table_registry", Mode.BOUNDED, DataClass.OPERATIONAL,
                "Tablo kayıt defteri (SQL Playground, 2026-09-11): tablo başına TEK satır — ilk görülme anı ve son veri "
                + "değişimi. Tablo sayısı kadar satır; asla silinmez (silinirse 'oluşturma' bilgisi kaybolur)."),
        info("data-quality-daily", "data_quality_daily", Mode.BOUNDED, DataClass.OPERATIONAL,
                "Takım veri kalitesi puanının günlük görüntüsü (2026-10-10): gün × kova (takım / kurum / sahipsiz) başına TEK "
                + "satır — puan, bulgu ve incelenen öğe sayısı (30 günlük eğilim çizgisi). DataQualitySnapshotJob her yazımda "
                + "120 günden eski satırları siler → en fazla ~120 × (takım sayısı + 2) satır. Kişisel veri yok."),
        info("spring-session", "spring_session", Mode.EXTERNAL, DataClass.PERSONAL,
                "Spring Session JDBC deposu — Spring'in kendi dakikalık cleanup job'ı süresi dolan oturumları siler "
                + "(spring.session.timeout=24h). Bu uygulama dokunmaz."),
        info("spring-session-attributes", "spring_session_attributes", Mode.EXTERNAL, DataClass.PERSONAL,
                "Oturum öznitelikleri — ebeveyn spring_session satırıyla FK CASCADE silinir."),
        info("remember-me-tokens", "remember_me_tokens", Mode.EXTERNAL, DataClass.PERSONAL,
                "RememberMeService saatlik olarak süresi dolmuş token'ları siler (site.monitor.remember.cleanup-interval-ms)."),
        info("login-unknown-lockouts", "login_unknown_lockouts", Mode.EXTERNAL, DataClass.PERSONAL,
                "Bilinmeyen kullanıcı adının ilerleyici giriş kilidi (2026-10-09): ad başına TEK satır — kanonik ad, kademe, "
                + "kilit bitişi, son kilit anı (parola / IP yok). Satır yalnız kaba kuvvet eşiğinde doğar. UnknownUserLockoutService "
                + "saatlik siler: son yazımı site.monitor.lockout.unknown-retention-days'ten (365 gün — denetim kaydıyla aynı; "
                + "mevcut hesabın kademesi kendiliğinden sıfırlanmadığından uzun) eski satırlar ve "
                + "site.monitor.lockout.unknown-max-rows (50.000) üstündeki en eskiler."),
        info("rate-limit-counters", "rate_limit_counters", Mode.EXTERNAL, DataClass.PERSONAL,
                "Pod'lar arası ortak oran sınırı sayacı (2026-10-23, çok pod Sürüm 2a): anahtar başına TEK satır — kapsam + değer "
                + "(giriş ucunda istemci IP'si: login-ip:<ip>), sabit pencere başlangıcı, deneme sayısı, blok bitişi. Satır "
                + "expires_at'ten (pencere ve bloğun büyüğü, girişte 60 sn) sonra hiçbir kararı etkilemez; "
                + "RateLimitCounterService saatlik siler (her pod, idempotent). Başarılı oturum o IP'nin satırını hemen siler."),
        // ── TLS yapılandırma notu (2026-10-10) ──────────────────────────────────────────────────
        age("tls-grade-changes", "tls_grade_changes", "changed_at", "site.monitor.series.certificate.retention-days",
                180, 30, false, DataClass.OPERATIONAL,
                "TLS notu değişim günlüğü (önceki → yeni not, neden kodları, alan adı, takım kimliği; kişi verisi yok). "
                + "Sertifika kontrol serisinden türer → aynı pencere (aynı ayar anahtarı). Satır yalnız not DEĞİŞİNCE doğar."),
        orphan("tls-grade-status-orphan", "tls_grade_status",
                "NOT EXISTS (SELECT 1 FROM certificate_inventory i WHERE i.id = tls_grade_status.inventory_id)",
                DataClass.OPERATIONAL,
                "Envanter kaydı başına son bilinen TLS notu (düşüş göstergesinin karşılaştırma tabanı). Kayıt silinince "
                + "yetim kalır ve burada temizlenir; aksi hâlde envanter kadar satır, birikmez."),
        orphan("tls-profiles-orphan", "tls_profiles",
                "NOT EXISTS (SELECT 1 FROM certificate_inventory i WHERE i.domain = tls_profiles.domain)",
                DataClass.OPERATIONAL,
                "Alan adı başına TLS profili (kabul edilen protokol sürümleri, OCSP zımbalama, zayıf takım kabulü — günlük "
                + "yoklama). Envanterden silinen alan adının satırı burada temizlenir; aksi hâlde alan adı kadar satır."),
        info("password-history", "password_history", Mode.EXTERNAL, DataClass.PERSONAL,
                "UserService her şifre değişiminde kullanıcı başına son N kayda kırpar → kullanıcı başına sınırlı."),
        info("monitor-check-schedule", "monitor_check_schedule", Mode.EXTERNAL, DataClass.OPERATIONAL,
                "Küme geneli izleme vadesi ve süpürme turu kaydı (2026-10-09): izleme başına TEK satır (sıradaki vade, son "
                + "sahiplenen pod) + birkaç 'round:' satırı. Zaman serisi değil, birikmez; silinen izlemelerin satırlarını "
                + "SchedulerService gece temizliği canlı izleme kümesiyle budar (tur satırları kalır). Kişisel veri yok."),
        // ── Kalıcı değerlendirme durumu (2026-10-23, çok pod Sürüm 3-1, paket A1) ─────────────────────────────────────
        info("monitor-eval-state", "monitor_eval_state", Mode.EXTERNAL, DataClass.OPERATIONAL,
                "Alarm anahtarı (tür × alan adı) başına kurtarma durumu — tarama liderinin belleğinin aynası (ardışık sağlıklı "
                + "sayısı, son sayım, etkin kurtarma zinciri; 3-1'de karara girmez). İzleme × alarm türü kadar satır, "
                + "zaman serisi değil. EvalStateRecorder günde bir siler: 7 günden eski satırlardan sayaçsız ve zincirsiz "
                + "olanlar ile daha eski bir kuşağın (önceki liderin) olanlar, sayıdan bağımsız. Kişisel veri yok."),
        info("monitor-last-status", "monitor_last_status", Mode.EXTERNAL, DataClass.OPERATIONAL,
                "İzleme kalemi başına son gözlenen sonuç (UP/DOWN, ağ sınıfı, sahiplik kimlikleri; ham hata metni YOK) — "
                + "tarama liderinin kardeş / pencere belleğinin aynası. İzleme × alarm türü kadar satır, durum değişince ya "
                + "da en çok 120 sn'de bir güncellenir; bellek bir kalemin DOWN gözlemini budayınca (izleme duraklatıldı / "
                + "silindi, 26 sa'lik TTL) satırı da silinir. EvalStateRecorder günde bir siler: son gözlemi 7 günden eski "
                + "kalemler. Kişisel veri yok."),
        info("monitor-eval-type-state", "monitor_eval_type_state", Mode.EXTERNAL, DataClass.OPERATIONAL,
                "Alarm türü başına bayraklar (ağ kesintisi bastırması, ilk zamanlanmış tur, tur aralığı) + kuşak / yazan "
                + "nabzı satırı ('*'). Alarm türü kadar satır (≤ 31), güncellenir, birikmez. EvalStateRecorder günde bir "
                + "siler: daha eski bir kuşağın 7 günden eski satırları (artık taranmayan türler); '*' satırı asla. Kişisel "
                + "veri yok."),
        info("monitor-confirmations", "monitor_confirmations", Mode.EXTERNAL, DataClass.OPERATIONAL,
                "Süren teyit zincirleri (tarama liderinin belleğinin aynası; deneme sayısı, bağlam JSON'u ≤ 16 KB). Zincir "
                + "bitince satır silinir; önceki kuşakların zincirleri her yeni kuşakta ABANDONED işaretlenir. "
                + "EvalStateRecorder günde bir siler: ABANDONED ya da 1 saattir güncellenmeyen satırlar. İzleme sayısıyla "
                + "sınırlı, kişisel veri yok."),
        // ── Dağıtık kontroller (2026-10-29, çok pod Sürüm 3-3, paket A) ─────────────────────────────────────────────
        info("monitor-check-jobs", "monitor_check_jobs", Mode.EXTERNAL, DataClass.OPERATIONAL,
                "Dağıtık izleme kontrolü kuyruğu (ana anahtar site.monitor.checks.distributed.enabled açıkken): kontrol işi "
                + "başına satır — tür, izleme kimliği, durum (bekliyor / sahiplenildi / bitti), kira, sonuç JSON'u. İzleme "
                + "başına en çok bir açık iş; tarama lideri biten sonucu değerlendirirken satırı SİLER, 10 dakikadan eski "
                + "biten sonuç değerlendirilmeden silinir, iki kez sahiplenilip bitmeyen iş biçilir. Kuyruk izleme sayısı + "
                + "birkaç saniyelik birikimle sınırlı, birikmez. Ana anahtar kapalıyken boş. Kişisel veri yok."),
        info("monitor-check-workers", "monitor_check_workers", Mode.EXTERNAL, DataClass.OPERATIONAL,
                "Dağıtık kontrol işçileri: pod örneği başına TEK satır (yetenekler, yuva, sayaçlar, son nabız), anahtar "
                + "açıkken 10 sn'de bir güncellenir. Tarama lideri saatte bir, nabzı 1 günden eski satırları siler (biten "
                + "pod'lar). Pod sayısıyla sınırlı, kişisel veri yok."),
        // ── Parçalı sertifika taraması (2026-10-29, çok pod Sürüm 3-4, paket A) ───────────────────────────────────────
        info("cert-sweep-runs", "cert_sweep_runs", Mode.EXTERNAL, DataClass.OPERATIONAL,
                "Parçalı sertifika taraması koşuları (site.monitor.checks.cert-chunks.enabled açıkken): parçalı tur başına "
                + "TEK satır — tür, koordinatör pod, durum (sürüyor / karar / bitti / düştü / bırakıldı), alan adı ve parça "
                + "sayısı, pod başına parça payı. Her parçalı tur başında 7 günden eski kapalı koşular silinir (en çok 1000). "
                + "Saatlik turla günde ≈ 24–300 satır, birikmez. Anahtar kapalıyken boş. Kişisel veri yok."),
        info("cert-sweep-chunks", "cert_sweep_chunks", Mode.EXTERNAL, DataClass.OPERATIONAL,
                "Parçalı sertifika taramasının parçaları: koşu başına ≤ 500 satır (20 alan adı), sahiplenen pod, kira ve "
                + "ölçüm sonucu JSON'u (koordinatör okuyup kaydeder; API'den dönmez, günlüğe yazılmaz). Koşu kapanınca "
                + "koordinatör parçalarını SİLER; ölen koordinatörün parçalarını bir sonraki parçalı tur siler. Tablo en "
                + "çok bir koşunun parçalarını tutar, birikmez. Anahtar kapalıyken boş. Kişisel veri yok.")
    );

    /** Yalnız gerçekten satır silen kurallar (çalıştırma sırasında). */
    public static List<RetentionPolicy> executable() {
        return ALL.stream().filter(RetentionPolicy::deletes).toList();
    }

    /** Ekranda düzenlenebilir kurallar. */
    public static List<RetentionPolicy> configurable() {
        return ALL.stream().filter(RetentionPolicy::configurable).toList();
    }

    public static Optional<RetentionPolicy> byId(String id) {
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Bir tablonun tüm kuralları (incident_images'ın üç kuralı vardır). */
    public static List<RetentionPolicy> byTable(String table) {
        return ALL.stream().filter(p -> p.table().equalsIgnoreCase(table)).toList();
    }

    /** Katalogda adı geçen tüm tablolar — bekçi testi ve DbGrowthMetrics bunu kullanır. */
    public static List<String> tables() {
        return ALL.stream().map(RetentionPolicy::table).distinct().sorted().toList();
    }

    /** Ayar anahtarı → varsayılan gün (AppSettingsCatalog ile senkron olmalı; test kilitler). */
    public static Map<String, Integer> settingDefaults() {
        return configurable().stream()
                .collect(Collectors.toMap(RetentionPolicy::settingKey, RetentionPolicy::defaultDays, (a, b) -> a));
    }

    /**
     * Kontrol Geçmişi ekranındaki "kind" → saklama politikası eşlemesi. Eskiden
     * {@code MonitoringController.historyRetentionDays} bu süreleri elle sabitliyor ve yorumunda
     * "cleanup ile elle senkron tutuluyor" diyordu — artık aynı kaynaktan okunur.
     */
    public static String policyIdForHistoryKind(String kind) {
        return switch (kind == null ? "" : kind) {
            case "ping"      -> "series-ping";
            case "port"      -> "series-port";
            case "keyword"   -> "series-keyword";
            case "http"      -> "series-http";
            case "page"      -> "page-checks";
            case "pagespeed" -> "pagespeed-checks";
            case "dbquery"   -> "dbquery-checks";
            case "scripted"  -> "series-scripted";
            case "dns"       -> "series-dns";
            case "domain"    -> "series-domain";
            case "uptime"    -> "series-uptime";
            case "ssl", "certificate" -> "series-certificate";
            default -> null;
        };
    }
}

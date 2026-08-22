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
        age("notification-logs", "notification_logs", "sent_at", "site.monitor.notification.retention-days",
                365, 30, true, DataClass.PERSONAL,
                "Gönderilen bildirim geçmişi (alıcı adı/e-postası içerir). Kişisel veri saklama süreleri "
                + "1 yılda eşitlendi (2026-08 kullanıcı kararı) — denetimde tek bir pencere savunulur."),
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
                "{t} AND id NOT IN (SELECT MAX(id) FROM dns_records GROUP BY monitor_id)",
                DataClass.OPERATIONAL,
                "DNS kayıt serisi. Her monitörün EN YENİ satırı baseline'dır (değişiklik tespiti ona bakar) → asla silinmez."),
        age("http-metric-minute", "http_metric_minute", "bucket_minute", "site.monitor.metrics.http.retention-days",
                7, 1, false, DataClass.OPERATIONAL,
                "Uygulamanın kendi HTTP metrik kovaları (dakikalık). Kısa tutulur; hacmi yüksektir."),
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
        age("series-scripted", "scripted_checks", "checked_at", "site.monitor.metrics.scripted.retention-days",
                180, 1, true, DataClass.OPERATIONAL,
                "Senaryo (k6) kontrol serisi — çıktı kuyruğu ve kontrol JSON'u içerir."),

        new RetentionPolicy("system-heartbeat", "system_heartbeat", "recorded_at", TimeKind.TIMESTAMP,
                "site.monitor.heartbeat.retention-days", 30, 7, false, "{t}", Mode.AGE, false,
                DataClass.OPERATIONAL,
                "Dakikada bir yazılan canlılık nabzı. recorded_at gerçek TIMESTAMP'tir → parametre CAST edilir."),

        guarded("series-domain", "domain_checks", "checked_at", "site.monitor.series.domain.retention-days",
                180, 30,
                "{t} AND id NOT IN (SELECT MAX(id) FROM domain_checks GROUP BY monitor_id) "
                + "AND id NOT IN (SELECT MAX(id) FROM domain_checks WHERE source <> 'NONE' GROUP BY monitor_id)",
                DataClass.OPERATIONAL,
                "Alan adı kontrol serisi. İKİ baseline korunur: her monitörün en yeni satırı ve en yeni source<>'NONE' satırı."),
        age("diagnostic-runs", "diagnostic_runs", "executed_at", "site.monitor.diagnostics.retention-days",
                365, 7, false, DataClass.PERSONAL,
                "Elle çalıştırılan tanılamalar (çalıştıran kullanıcı ve kaynak IP içerir). "
                + "Kişisel veri penceresiyle aynı 1 yıl (2026-08 kararı)."),
        guarded("alert-events", "alert_events", "resolved_at", "site.monitor.alert.retention-days",
                365, 90, "resolved = true AND {t}", DataClass.PERSONAL,
                "Alarm olayları. Yalnız ÇÖZÜLMÜŞ alarmlar silinir — açık/onaylanmış alarmlar ASLA silinmez."),

        // ── Sorun bildirimleri: görseller → rapor → öksüz mail logları ─────────────────────────
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
        orphan("alert-comments-orphan", "alert_comments",
                "alert_event_id NOT IN (SELECT id FROM alert_events)", DataClass.PERSONAL,
                "Alarm yorumları. alert_events 1 yılda siliniyor ama yorumlar kalıyordu → kalıcı öksüz."),
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
        age("scripted-drafts", "scripted_drafts", "updated_at", "site.monitor.scripted.draft-retention-days",
                30, 1, false, DataClass.CONTENT,
                "k6 script düzenleme formunun otomatik kaydedilen taslakları. Kaydedilince silinirler; "
                + "burada kalanlar terk edilmiş oturumlardır (incident-images-draft ile aynı mantık)."),
        orphan("scripted-versions-orphan", "scripted_script_versions",
                "monitor_id NOT IN (SELECT id FROM scripted_monitors)",
                DataClass.CONTENT,
                "Monitörü silinmiş script sürümleri. Sürüm geçmişi monitör silinirken BİLİNÇLİ olarak "
                + "silinmiyor (denetim değeri); öksüz kalan satırlar burada temizlenir."),

        // ── Temizliğin kendi çalışma geçmişi (öz-referans) ─────────────────────────────────────
        age("retention-run-items", "retention_run_item", "created_at", "site.monitor.retention.run-history-retention-days",
                180, 30, false, DataClass.OPERATIONAL,
                "Gece temizliği çalışma detayı (tablo başına silinen satır). Ana kayıttan ÖNCE silinir."),
        age("retention-runs", "retention_run", "started_at", "site.monitor.retention.run-history-retention-days",
                180, 30, false, DataClass.OPERATIONAL,
                "Gece temizliği çalışma özeti — sağlık sinyali ve ekrandaki geçmiş buradan beslenir."),

        // ── Bilgi satırları: bu uygulama silmez, ama kapsanmış sayılır ─────────────────────────
        info("spring-session", "spring_session", Mode.EXTERNAL, DataClass.PERSONAL,
                "Spring Session JDBC deposu — Spring'in kendi dakikalık cleanup job'ı süresi dolan oturumları siler "
                + "(spring.session.timeout=24h). Bu uygulama dokunmaz."),
        info("spring-session-attributes", "spring_session_attributes", Mode.EXTERNAL, DataClass.PERSONAL,
                "Oturum öznitelikleri — ebeveyn spring_session satırıyla FK CASCADE silinir."),
        info("remember-me-tokens", "remember_me_tokens", Mode.EXTERNAL, DataClass.PERSONAL,
                "RememberMeService saatlik olarak süresi dolmuş token'ları siler (site.monitor.remember.cleanup-interval-ms)."),
        info("password-history", "password_history", Mode.EXTERNAL, DataClass.PERSONAL,
                "UserService her şifre değişiminde kullanıcı başına son N kayda kırpar → kullanıcı başına sınırlı.")
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
            case "scripted"  -> "series-scripted";
            case "dns"       -> "series-dns";
            case "domain"    -> "series-domain";
            case "uptime"    -> "series-uptime";
            case "ssl", "certificate" -> "series-certificate";
            default -> null;
        };
    }
}

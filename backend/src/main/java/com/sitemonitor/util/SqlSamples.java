package com.sitemonitor.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL Playground örnek sorgu kataloğu (salt okunur). 2026-11-13 (D2): 12 → 42 örnek, KATEGORİLİ ve açıklamalı.
 *
 * <p>Her öğe: {@code id} (kararlı), {@code category} (kararlı anahtar: monitoring | alerts | notifications | inventory |
 * teams | system), {@code category_label}, {@code label} ve {@code description} ({@link Msg#t} — istek dilinde),
 * {@code sql}. Eski istemci yalnız {@code label} + {@code sql} okur — aynen duruyor.
 *
 * <p>Kurallar (kapılar): her örnek salt-okunur doğrulamayı ve hassas kolon reddini geçer
 * ({@code SqlSamplesIntegrationTest}, H2) ve gerçek PostgreSQL'de hatasız çalışır
 * ({@code PostgresOnlySqlPostgresTest}); dize içinde kara listedeki kelimeler (ör. reset, set, lock) geçmez; yalnız
 * varlık tabloları (H2 test şeması) — ham DDL tabloları yok.
 */
public final class SqlSamples {

    private SqlSamples() {}

    /** Kararlı kategori anahtarları (sıra = ekrandaki sıra). */
    public static final List<String> CATEGORIES = List.of("monitoring", "alerts", "notifications", "inventory", "teams", "system");

    private static final String DAY = "to_char(now() - interval '1 day','YYYY-MM-DD\"T\"HH24:MI:SS')";
    private static final String WEEK = "to_char(now() - interval '7 days','YYYY-MM-DD')";

    private record Sample(String id, String category, String labelTr, String labelEn, String descTr, String descEn, String sql) {}

    private static final List<Sample> ALL = List.of(
        // ── İzleme durumu ──────────────────────────────────────────────────────────────────────────────────────
        new Sample("dns-records-per-monitor", "monitoring",
            "DNS monitör başına kayıt sayısı", "DNS records per monitor",
            "Her DNS izlemesinin kaç kayıt (kontrol) biriktirdiği — en çok kayıt üreten 20 izleme.",
            "How many records (checks) each DNS monitor has accumulated — the 20 busiest monitors.",
            "SELECT m.domain, COUNT(r.id) AS records "
          + "FROM dns_monitors m LEFT JOIN dns_records r ON r.monitor_id = m.id "
          + "GROUP BY m.domain ORDER BY records DESC LIMIT 20"),
        new Sample("paused-monitors-by-type", "monitoring",
            "Duraklatılmış izlemeler (tür bazında)", "Paused monitors by type",
            "Her izleme türünde duraklatılmış (active = false) izleme sayısı.",
            "Number of paused (active = false) monitors in each monitor type.",
            "SELECT (SELECT COUNT(*) FROM http_monitors WHERE active = false) AS http_paused, "
          + "(SELECT COUNT(*) FROM keyword_monitors WHERE active = false) AS keyword_paused, "
          + "(SELECT COUNT(*) FROM ping_monitors WHERE active = false) AS ping_paused, "
          + "(SELECT COUNT(*) FROM port_monitors WHERE active = false) AS port_paused, "
          + "(SELECT COUNT(*) FROM dns_monitors WHERE active = false) AS dns_paused, "
          + "(SELECT COUNT(*) FROM page_monitors WHERE active = false) AS page_paused, "
          + "(SELECT COUNT(*) FROM scripted_monitors WHERE active = false) AS scripted_paused, "
          + "(SELECT COUNT(*) FROM domain_monitors WHERE active = false) AS domain_paused"),
        new Sample("port-failures-24h", "monitoring",
            "Son 24 saatte en çok kapalı görülen portlar", "Ports most often closed in the last 24 hours",
            "Son 24 saatte bağlantısı kurulamayan (open = false) port kontrolleri, izleme başına.",
            "Port checks that couldn't connect (open = false) in the last 24 hours, per monitor.",
            "SELECT m.name, m.host, m.port, COUNT(*) AS failures "
          + "FROM port_checks c JOIN port_monitors m ON m.id = c.monitor_id "
          + "WHERE c.open = false AND c.checked_at > " + DAY + " "
          + "GROUP BY m.name, m.host, m.port ORDER BY failures DESC LIMIT 20"),
        new Sample("ping-avg-rtt-24h", "monitoring",
            "Ping ortalama gidiş-dönüş süresi (24 saat)", "Ping average round-trip time (24 hours)",
            "Son 24 saatte başarılı ping kontrollerinin ortalama gidiş-dönüş süresi (ms), en yavaştan.",
            "Average round-trip time (ms) of successful ping checks in the last 24 hours, slowest first.",
            "SELECT m.name, m.host, ROUND(AVG(c.rtt_ms)::numeric, 1) AS avg_rtt_ms, COUNT(*) AS checks "
          + "FROM ping_checks c JOIN ping_monitors m ON m.id = c.monitor_id "
          + "WHERE c.up = true AND c.checked_at > " + DAY + " "
          + "GROUP BY m.name, m.host ORDER BY avg_rtt_ms DESC LIMIT 20"),
        new Sample("keyword-misses-24h", "monitoring",
            "Anahtar kelime koşulu tutmayan sayfalar (24 saat)", "Pages failing the keyword condition (24 hours)",
            "Son 24 saatte koşulu sağlanmayan (ok = false) anahtar kelime kontrolleri, izleme başına.",
            "Keyword checks whose condition failed (ok = false) in the last 24 hours, per monitor.",
            "SELECT m.name, m.url, m.keyword, COUNT(*) AS misses "
          + "FROM keyword_results r JOIN keyword_monitors m ON m.id = r.monitor_id "
          + "WHERE r.ok = false AND r.checked_at > " + DAY + " "
          + "GROUP BY m.name, m.url, m.keyword ORDER BY misses DESC LIMIT 20"),
        new Sample("http-slowest-24h", "monitoring",
            "En yavaş HTTP izlemeleri (24 saat)", "Slowest HTTP monitors (24 hours)",
            "Son 24 saatte ortalama ve en yüksek yanıt süresi (ms), en yavaştan.",
            "Average and highest response time (ms) in the last 24 hours, slowest first.",
            "SELECT m.name, m.url, ROUND(AVG(c.response_ms)::numeric, 0) AS avg_ms, MAX(c.response_ms) AS max_ms "
          + "FROM http_checks c JOIN http_monitors m ON m.id = c.monitor_id "
          + "WHERE c.checked_at > " + DAY + " "
          + "GROUP BY m.name, m.url ORDER BY avg_ms DESC LIMIT 20"),
        new Sample("ping-port-per-team", "monitoring",
            "Takım başına ping ve port izlemesi", "Ping and port monitors per team",
            "Her takımın sahip olduğu ping ve port izlemesi sayısı.",
            "How many ping and port monitors each team owns.",
            "SELECT t.name AS team, COUNT(DISTINCT p.id) AS ping_monitors, COUNT(DISTINCT o.id) AS port_monitors "
          + "FROM teams t LEFT JOIN ping_monitors p ON p.team_id = t.id LEFT JOIN port_monitors o ON o.team_id = t.id "
          + "GROUP BY t.name ORDER BY t.name"),
        new Sample("domain-expiring-60d", "monitoring",
            "60 gün içinde süresi dolan alan adları", "Domains expiring within 60 days",
            "Alan adı izlemelerinin en son kontrolüne göre kayıt bitişine 60 gün ya da daha az kalanlar.",
            "Domain monitors whose latest check shows 60 days or less until registration expiry.",
            "SELECT m.domain, d.days_remaining, d.expiry_date, d.registrar "
          + "FROM domain_monitors m JOIN domain_checks d ON d.monitor_id = m.id "
          + "WHERE d.id = (SELECT MAX(x.id) FROM domain_checks x WHERE x.monitor_id = m.id) "
          + "AND d.days_remaining <= 60 ORDER BY d.days_remaining ASC"),

        // ── Alarmlar ───────────────────────────────────────────────────────────────────────────────────────────
        new Sample("open-alerts-by-severity", "alerts",
            "Açık alarmlar (severity'ye göre)", "Open alerts (by severity)",
            "Kapanmamış alarmlar; önce CRITICAL, sonra HIGH, en yeniden eskiye.",
            "Unresolved alerts; CRITICAL first, then HIGH, newest first.",
            "SELECT id, domain, alert_type, alert_level, created_at "
          + "FROM alert_events WHERE resolved = false "
          + "ORDER BY CASE alert_level WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 ELSE 3 END, "
          + "created_at DESC"),
        new Sample("open-alerts-on-deleted", "alerts",
            "Silinmiş ama açık alarmı olan domainler", "Deleted domains that still have open alerts",
            "Envanter kaydı silinmiş olduğu hâlde alarmı açık kalan alan adları (eski silmelerden kalıntı).",
            "Domains whose inventory record was deleted but that still have an open alert (leftovers of old deletes).",
            "SELECT DISTINCT a.domain FROM alert_events a "
          + "JOIN certificate_inventory i ON i.domain = a.domain "
          + "WHERE a.resolved = false AND i.deleted_at IS NOT NULL"),
        new Sample("open-alerts-oldest", "alerts",
            "Açık alarmlar (en eski 50)", "Open alerts (oldest 50)",
            "En uzun süredir açık 50 alarm ve son yeniden bildirim zamanı.",
            "The 50 longest-open alerts and when they were last re-notified.",
            "SELECT domain, alert_type, alert_level, last_re_alert_at FROM alert_events "
          + "WHERE resolved = false ORDER BY created_at ASC LIMIT 50"),
        new Sample("top-alert-targets-7d", "alerts",
            "Son 7 günde en çok alarm üreten hedefler", "Targets with the most alerts in the last 7 days",
            "Son 7 günde açılan alarm sayısına göre ilk 20 hedef — gürültülü izlemeleri bulmak için.",
            "The top 20 targets by alerts opened in the last 7 days — to find noisy monitors.",
            "SELECT domain, COUNT(*) AS alerts FROM alert_events "
          + "WHERE created_at > " + WEEK + " GROUP BY domain ORDER BY alerts DESC LIMIT 20"),
        new Sample("open-alerts-by-type", "alerts",
            "Alarm türü ve seviyesine göre açık alarmlar", "Open alerts by type and level",
            "Kapanmamış alarmların tür × seviye dağılımı.",
            "Distribution of unresolved alerts by type × level.",
            "SELECT alert_type, alert_level, COUNT(*) AS open_alerts FROM alert_events "
          + "WHERE resolved = false GROUP BY alert_type, alert_level ORDER BY open_alerts DESC"),
        new Sample("unacked-critical", "alerts",
            "Sahiplenilmemiş kritik alarmlar", "Unacknowledged critical alerts",
            "Açık, CRITICAL seviyeli ve henüz kimsenin sahiplenmediği alarmlar, en eskiden.",
            "Open CRITICAL alerts that nobody has acknowledged yet, oldest first.",
            "SELECT id, domain, alert_type, created_at FROM alert_events "
          + "WHERE resolved = false AND acknowledged = false AND alert_level = 'CRITICAL' ORDER BY created_at ASC"),
        new Sample("resolved-24h", "alerts",
            "Son 24 saatte kapanan alarmlar", "Alerts resolved in the last 24 hours",
            "Son 24 saatte kapanan alarmlar ve kimin / neyin kapattığı.",
            "Alerts resolved in the last 24 hours and who / what resolved them.",
            "SELECT id, domain, alert_type, alert_level, resolved_at, resolved_by FROM alert_events "
          + "WHERE resolved = true AND resolved_at > " + DAY + " ORDER BY resolved_at DESC"),
        new Sample("alerts-per-day-7d", "alerts",
            "Gün başına açılan alarm (7 gün)", "Alerts opened per day (7 days)",
            "Son 7 günde her gün açılan alarm sayısı (UTC günü).",
            "Number of alerts opened each day in the last 7 days (UTC day).",
            "SELECT LEFT(created_at, 10) AS alert_day, COUNT(*) AS alerts FROM alert_events "
          + "WHERE created_at > " + WEEK + " GROUP BY LEFT(created_at, 10) ORDER BY alert_day"),

        // ── Bildirim günlükleri ────────────────────────────────────────────────────────────────────────────────
        new Sample("smtp-failures-24h", "notifications",
            "Son 24 saat SMTP hataları", "SMTP failures in the last 24 hours",
            "Son 24 saatte gönderilemeyen e-postalar (FAILED…), en yeniden.",
            "E-mails that couldn't be sent in the last 24 hours (FAILED…), newest first.",
            "SELECT sent_at, recipient_email, subject, email_status FROM notification_logs "
          + "WHERE email_status LIKE 'FAILED%' AND sent_at > " + DAY + " ORDER BY sent_at DESC"),
        new Sample("open-alerts-without-mail", "notifications",
            "Bildirim almamış açık alarmlar", "Open alerts that were never mailed",
            "Hiçbir e-postası SENT olmamış açık alarmlar — alıcı / kanal sorunlarını bulmak için.",
            "Open alerts with no SENT e-mail — to find recipient / channel problems.",
            "SELECT a.id, a.domain, a.alert_type, a.created_at FROM alert_events a "
          + "WHERE a.resolved = false AND NOT EXISTS ("
          + "  SELECT 1 FROM notification_logs n WHERE n.alert_event_id = a.id AND n.email_status = 'SENT')"),
        new Sample("mail-status-24h", "notifications",
            "E-posta sonuç dağılımı (24 saat)", "E-mail outcome distribution (24 hours)",
            "Son 24 saatteki bildirim satırlarının e-posta durumuna göre sayısı.",
            "Notification rows of the last 24 hours counted by e-mail status.",
            "SELECT email_status, COUNT(*) AS n FROM notification_logs "
          + "WHERE sent_at > " + DAY + " GROUP BY email_status ORDER BY n DESC"),
        new Sample("mail-queued-retries", "notifications",
            "Yeniden denemede bekleyen e-postalar", "E-mails waiting in the retry queue",
            "Posta sunucusunun geçici reddi (421) sonrası yeniden denemede bekleyen gönderimler.",
            "Sends waiting to be retried after a temporary mail server refusal (421).",
            "SELECT sent_at, recipient_email, subject, email_status FROM notification_logs "
          + "WHERE email_status LIKE 'QUEUED_RETRY%' ORDER BY sent_at DESC LIMIT 50"),
        new Sample("push-status-24h", "notifications",
            "Push teslimat durumları (24 saat)", "Push delivery statuses (24 hours)",
            "Son 24 saatte oluşturulan kişisel push satırlarının durumuna göre sayısı.",
            "Personal push rows created in the last 24 hours counted by status.",
            "SELECT status, COUNT(*) AS n FROM user_push_deliveries "
          + "WHERE created_at > " + DAY + " GROUP BY status ORDER BY n DESC"),
        new Sample("webhook-failures-7d", "notifications",
            "Webhook hataları (7 gün)", "Webhook failures (7 days)",
            "Son 7 günde Teams / Slack webhook'una teslim edilemeyen bildirimler.",
            "Notifications that couldn't be delivered to a Teams / Slack webhook in the last 7 days.",
            "SELECT sent_at, recipient_name, subject, webhook_status FROM notification_logs "
          + "WHERE webhook_status LIKE 'FAILED%' AND sent_at > " + WEEK + " ORDER BY sent_at DESC LIMIT 50"),

        // ── Envanter / sertifika sağlığı ───────────────────────────────────────────────────────────────────────
        new Sample("certs-expiring-30d", "inventory",
            "Yaklaşan sertifikalar (30 gün)", "Certificates expiring (30 days)",
            "Önümüzdeki 30 gün içinde süresi dolacak sertifikalar, en yakından.",
            "Certificates expiring in the next 30 days, soonest first.",
            "SELECT domain, days_remaining, not_after AS expires_at FROM latest_checks "
          + "WHERE days_remaining BETWEEN 0 AND 30 ORDER BY days_remaining ASC"),
        new Sample("certs-expired", "inventory",
            "Süresi dolmuş sertifikalar", "Expired certificates",
            "En son kontrolde süresi dolmuş görünen sertifikalar.",
            "Certificates that were expired at their latest check.",
            "SELECT domain, not_after, days_remaining FROM latest_checks "
          + "WHERE days_remaining < 0 ORDER BY days_remaining ASC"),
        new Sample("certs-by-tier", "inventory",
            "Katman bazında aktif sertifika", "Active certificates by tier",
            "Aktif envanter kayıtlarının katmana (1 müşteri üretimi … 4 geliştirme) göre sayısı; boş = sınıflanmamış.",
            "Active inventory records counted by tier (1 customer production … 4 development); empty = unclassified.",
            "SELECT tier, COUNT(*) AS certificates FROM certificate_inventory "
          + "WHERE active = true AND deleted_at IS NULL GROUP BY tier ORDER BY tier"),
        new Sample("chain-trust-distribution", "inventory",
            "Zincir ve güven durumu dağılımı", "Chain and trust status distribution",
            "En son kontrollerin zincir / güven durumu ikililerine göre sayısı — sorunlu grupları bulmak için.",
            "Latest checks counted by chain / trust status pair — to find problem groups.",
            "SELECT chain_status, trust_status, COUNT(*) AS n FROM latest_checks "
          + "GROUP BY chain_status, trust_status ORDER BY n DESC"),
        new Sample("never-checked", "inventory",
            "Hiç kontrol edilmemiş envanter kayıtları", "Inventory records never checked",
            "Aktif envanterde olup en son kontrol satırı olmayan alan adları.",
            "Active inventory domains that have no latest check row.",
            "SELECT i.domain, i.port, i.created_at FROM certificate_inventory i "
          + "LEFT JOIN latest_checks l ON l.domain = i.domain "
          + "WHERE i.active = true AND i.deleted_at IS NULL AND l.domain IS NULL ORDER BY i.created_at DESC"),
        new Sample("proxy-domains", "inventory",
            "Proxy üzerinden kontrol edilen alan adları", "Domains checked through the proxy",
            "Kurumsal proxy üzerinden kontrol edilmesi işaretlenmiş (use_proxy) aktif kayıtlar.",
            "Active records marked to be checked through the corporate proxy (use_proxy).",
            "SELECT domain, port, tier FROM certificate_inventory "
          + "WHERE use_proxy = true AND active = true ORDER BY domain"),
        new Sample("tls-grade-distribution", "inventory",
            "TLS notu dağılımı", "TLS grade distribution",
            "Envanter kayıtlarının TLS yapılandırma notuna (A+ … F) göre sayısı.",
            "Inventory records counted by TLS configuration grade (A+ … F).",
            "SELECT grade, COUNT(*) AS n FROM tls_grade_status GROUP BY grade ORDER BY grade"),

        // ── Takımlar ve kişiler ────────────────────────────────────────────────────────────────────────────────
        new Sample("active-certs-per-team", "teams",
            "Takım başına aktif sertifika sayısı", "Active certificates per team",
            "Her takımın (SY) sorumlu olduğu aktif sertifika sayısı.",
            "How many active certificates each team (owner) is responsible for.",
            "SELECT t.name AS team, COUNT(c.id) AS cert_count "
          + "FROM teams t LEFT JOIN certificate_inventory c "
          + "ON c.team_id = t.id AND c.deleted_at IS NULL AND c.active = true "
          + "GROUP BY t.name ORDER BY cert_count DESC"),
        new Sample("members-per-team", "teams",
            "Takım başına aktif üye", "Active members per team",
            "Birincil takımı bu olan aktif kullanıcı sayısı.",
            "Number of active users whose primary team is this one.",
            "SELECT t.name AS team, COUNT(u.id) AS members FROM teams t "
          + "LEFT JOIN app_users u ON u.team_id = t.id AND u.active = true "
          + "GROUP BY t.name ORDER BY members DESC"),
        new Sample("inactive-users", "teams",
            "Pasif kullanıcılar", "Passive users",
            "Pasife alınmış (giriş yapamayan) hesaplar ve son giriş zamanları.",
            "Deactivated accounts (they can't sign in) and when they last signed in.",
            "SELECT username, display_name, last_login_at FROM app_users "
          + "WHERE active = false ORDER BY username"),
        new Sample("never-logged-in", "teams",
            "Hiç giriş yapmamış aktif kullanıcılar", "Active users who never signed in",
            "Aktif ama hiç giriş yapmamış hesaplar, en eski oluşturulandan.",
            "Active accounts that have never signed in, oldest first.",
            "SELECT username, display_name, created_at FROM app_users "
          + "WHERE active = true AND last_login_at IS NULL ORDER BY created_at ASC"),
        new Sample("teams-without-contacts", "teams",
            "Eskalasyon kişisi olmayan takımlar", "Teams without escalation contacts",
            "Aktif eskalasyon kişisi tanımlanmamış aktif takımlar — HIGH / CRITICAL alarmlar yalnız takım adresine gider.",
            "Active teams with no active escalation contact — HIGH / CRITICAL alerts go only to the team address.",
            "SELECT t.name AS team FROM teams t WHERE t.active = true AND NOT EXISTS ("
          + "SELECT 1 FROM escalation_contacts c WHERE c.team_id = t.id AND c.active = true) ORDER BY t.name"),
        new Sample("oncall-enabled", "teams",
            "Nöbet çizelgesi açık takımlar", "Teams with an active on-call rotation",
            "Nöbet çizelgesi etkin takımlar, dönüşüm türü ve nöbetçinin alarmda bildirilip bildirilmediği.",
            "Teams with an enabled on-call rotation, its rotation type and whether the on-call person is notified.",
            "SELECT t.name AS team, o.rotation, o.notify_oncall, o.updated_at FROM oncall_schedules o "
          + "JOIN teams t ON t.id = o.team_id WHERE o.enabled = true ORDER BY t.name"),

        // ── Sistem ─────────────────────────────────────────────────────────────────────────────────────────────
        new Sample("audit-today", "system",
            "Bugünkü audit log özeti (event_type bazında)", "Today's audit log summary (by event type)",
            "Bugün (UTC) yazılan denetim kayıtlarının olay türüne göre sayısı.",
            "Audit records written today (UTC) counted by event type.",
            "SELECT event_type, COUNT(*) AS n FROM audit_log "
          + "WHERE event_time LIKE to_char(now(),'YYYY-MM-DD') || '%' "
          + "GROUP BY event_type ORDER BY n DESC"),
        new Sample("user-activity-7d", "system",
            "Son 7 gün kullanıcı aktivitesi (top 10)", "User activity in the last 7 days (top 10)",
            "Son 7 günde en çok denetim kaydı bırakan 10 kişi.",
            "The 10 people with the most audit records in the last 7 days.",
            "SELECT actor, COUNT(*) AS actions FROM audit_log "
          + "WHERE event_time > " + WEEK + " "
          + "GROUP BY actor ORDER BY actions DESC LIMIT 10"),
        new Sample("failed-logins-24h", "system",
            "Başarısız girişler (24 saat)", "Failed sign-ins (24 hours)",
            "Son 24 saatte başarısız giriş denemeleri, kullanıcı adı ve IP başına.",
            "Failed sign-in attempts in the last 24 hours, per user name and IP.",
            "SELECT actor, ip_address, COUNT(*) AS failures FROM audit_log "
          + "WHERE event_type = 'LOGIN_FAILED' AND event_time > " + DAY + " "
          + "GROUP BY actor, ip_address ORDER BY failures DESC LIMIT 20"),
        new Sample("largest-tables", "system",
            "En büyük tablolar (storage)", "Largest tables (storage)",
            "Disk kullanımına göre en büyük 10 tablo (indeksler dahil).",
            "The 10 largest tables by disk usage (indexes included).",
            "SELECT table_name, pg_size_pretty(pg_total_relation_size(quote_ident(table_name)::text)) AS size "
          + "FROM information_schema.tables WHERE table_schema='public' "
          + "ORDER BY pg_total_relation_size(quote_ident(table_name)::text) DESC LIMIT 10"),
        new Sample("table-row-estimates", "system",
            "Tablo satır tahminleri", "Table row estimates",
            "PostgreSQL istatistiklerine göre en çok satırı olan 20 tablo (tahmini canlı satır).",
            "The 20 tables with the most rows according to PostgreSQL statistics (estimated live rows).",
            "SELECT relname AS table_name, n_live_tup AS live_rows FROM pg_stat_user_tables "
          + "ORDER BY n_live_tup DESC LIMIT 20"),
        new Sample("playground-activity-24h", "system",
            "Son 24 saat SQL Playground aktivitesi", "SQL Playground activity in the last 24 hours",
            "Son 24 saatte kim kaç sorgu çalıştırdı ve ortalama süre (ms).",
            "Who ran how many queries in the last 24 hours and the average duration (ms).",
            "SELECT executed_by, COUNT(*) AS queries, AVG(duration_ms)::int AS avg_ms "
          + "FROM sql_query_history "
          + "WHERE executed_at > " + DAY + " "
          + "GROUP BY executed_by ORDER BY queries DESC"),
        new Sample("recent-deployments", "system",
            "Son dağıtımlar", "Recent deployments",
            "Son 20 pod açılışı: sürüm, ortam, pod ve kapanış nedeni.",
            "The last 20 pod starts: version, environment, pod and end reason.",
            "SELECT started_at, version, environment, pod_name, end_reason FROM deployment_history "
          + "ORDER BY started_at DESC LIMIT 20")
    );

    /** Örnekler — istek dilinde etiket / açıklama. */
    public static List<Map<String, String>> list() {
        List<Map<String, String>> out = new ArrayList<>(ALL.size());
        for (Sample s : ALL) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("category", s.category());
            m.put("category_label", categoryLabel(s.category()));
            m.put("label", Msg.t(s.labelTr(), s.labelEn()));
            m.put("description", Msg.t(s.descTr(), s.descEn()));
            m.put("sql", s.sql());
            out.add(m);
        }
        return out;
    }

    static String categoryLabel(String key) {
        return switch (key) {
            case "monitoring" -> Msg.t("İzleme durumu", "Monitoring status");
            case "alerts" -> Msg.t("Alarmlar", "Alerts");
            case "notifications" -> Msg.t("Bildirim günlükleri", "Notification logs");
            case "inventory" -> Msg.t("Envanter / sertifika sağlığı", "Inventory / certificate health");
            case "teams" -> Msg.t("Takımlar ve kişiler", "Teams and people");
            case "system" -> Msg.t("Sistem", "System");
            default -> key;
        };
    }
}

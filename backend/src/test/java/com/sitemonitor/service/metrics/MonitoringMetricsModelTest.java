package com.sitemonitor.service.metrics;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.ActiveStorm;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.DomainDays;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.Families;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.Owners;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.Row;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.TeamDirectory;
import com.sitemonitor.service.metrics.MonitoringMetricsModel.TeamRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sitemonitor.service.metrics.MonitoringMetricNames.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * İzleme ölçümlerinin saf hesabı (2026-10-18): durum kodları, sıfırlı toplamlar, silinmiş / envanterden çıkmış satır kuralı,
 * okunamayan tür → hiç satır, alarm sahipliği (bağımsız vs envanter, SY + UG), sertifika / alan adı kovaları, tavan ve
 * önceliği, bildirim / push sınıfları, kilit katlama, etiket kırpma ve sır maskeleme.
 */
class MonitoringMetricsModelTest {

    static final TeamDirectory DIR = new TeamDirectory(Map.of(
            14L, new TeamRef(14, "Dijital Kanallar", true),
            15L, new TeamRef(15, "Ödeme Sistemleri", true),
            16L, new TeamRef(16, "Pasif Ekip", false)));

    static Map<String, Object> row(String type, long id, String name, String target, Long team, String status) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("id", id);
        r.put("name", name);
        r.put("target", target);
        r.put("team_id", team);
        r.put("active", !"paused".equals(status));
        r.put("deleted", false);
        r.put("inventory_inactive", false);
        r.put("status", status);
        r.put("last_checked_at", "2026-10-18T08:00:00");
        r.put("response_ms", 120L);
        r.put("interval_seconds", 300);
        r.put("open_alerts", "down".equals(status) ? 1 : 0);
        r.put("open_alert_level", "down".equals(status) ? "CRITICAL" : null);
        r.put("open_since", "down".equals(status) ? "2026-10-18T07:30:00" : null);
        r.put("checks_window", 10L);
        r.put("failed_window", "down".equals(status) ? 4L : 0L);
        r.put("avg_response_ms_window", 110L);
        r.put("group_name", "Mobil");
        return r;
    }

    static Map<String, Object> overview(List<Map<String, Object>> rows, List<Map<String, Object>> types) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("types", types);
        o.put("monitors", rows);
        return o;
    }

    static List<Map<String, Object>> types(String... names) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (String n : names) l.add(new LinkedHashMap<>(Map.of("type", n)));
        return l;
    }

    static double value(Families f, String name, Map<String, String> match) {
        for (Row r : f.get(name)) {
            boolean ok = true;
            for (Map.Entry<String, String> e : match.entrySet()) if (!e.getValue().equals(r.labels().get(e.getKey()))) ok = false;
            if (ok) return r.value();
        }
        throw new AssertionError("satır yok: " + name + " " + match + " — " + f.get(name));
    }

    static boolean has(Families f, String name, Map<String, String> match) {
        try { value(f, name, match); return true; } catch (AssertionError e) { return false; }
    }

    @Test
    @DisplayName("izlemeler: durum kodu, up 1/0 (gecikmiş/duraklatılmış yok), yanıt / son kontrol / alarm alanları; toplamlar her durumu sıfırla yazar")
    void monitorRowsAndAggregates() {
        Families f = new Families();
        Map<String, Object> ov = overview(List.of(
                row("http", 1, "Ana sayfa", "https://www.example.com", 14L, "up"),
                row("http", 2, "Ödeme", "https://pay.example.com", 14L, "down"),
                row("http", 3, "Eski", "https://old.example.com", 14L, "stale"),
                row("ping", 4, "Çekirdek", "10.0.0.1", 15L, "paused"),
                row("ping", 5, "Yeni", "10.0.0.2", null, "unknown")), types("http", "ping"));
        Set<String> failed = MonitoringMetricsModel.monitors(f, ov, DIR, true, 100);
        assertThat(failed).isEmpty();
        assertThat(value(f, MONITOR_STATE, Map.of("monitor_id", "1"))).isEqualTo(0);
        assertThat(value(f, MONITOR_STATE, Map.of("monitor_id", "2"))).isEqualTo(2);
        assertThat(value(f, MONITOR_STATE, Map.of("monitor_id", "3"))).isEqualTo(1);
        assertThat(value(f, MONITOR_STATE, Map.of("monitor_id", "4"))).isEqualTo(4);
        assertThat(value(f, MONITOR_STATE, Map.of("monitor_id", "5", "team", "Takımsız", "team_id", "0"))).isEqualTo(3);
        assertThat(value(f, MONITOR_UP, Map.of("monitor_id", "1"))).isEqualTo(1);
        assertThat(value(f, MONITOR_UP, Map.of("monitor_id", "2"))).isEqualTo(0);
        assertThat(has(f, MONITOR_UP, Map.of("monitor_id", "3"))).as("gecikmiş izleme up/down sayılmaz").isFalse();
        assertThat(has(f, MONITOR_UP, Map.of("monitor_id", "4"))).as("duraklatılmış izleme up/down sayılmaz").isFalse();
        assertThat(value(f, MONITOR_ALERT_SEVERITY, Map.of("monitor_id", "2"))).isEqualTo(3);
        assertThat(value(f, MONITOR_ALERT_SEVERITY, Map.of("monitor_id", "1"))).isEqualTo(0);
        assertThat(value(f, MONITOR_OPEN_SINCE, Map.of("monitor_id", "2"))).isEqualTo(Instant.parse("2026-10-18T07:30:00Z").getEpochSecond());
        assertThat(has(f, MONITOR_OPEN_SINCE, Map.of("monitor_id", "1"))).isFalse();
        assertThat(value(f, MONITOR_LAST_CHECK, Map.of("monitor_id", "1"))).isEqualTo(Instant.parse("2026-10-18T08:00:00Z").getEpochSecond());
        assertThat(value(f, MONITOR_RESPONSE_MS, Map.of("monitor_id", "1"))).isEqualTo(120);
        assertThat(value(f, MONITOR_FAILED, Map.of("monitor_id", "2"))).isEqualTo(4);
        assertThat(value(f, MONITOR_INTERVAL, Map.of("monitor_id", "1"))).isEqualTo(300);
        assertThat(f.get(MONITOR_STATE).get(0).labels()).containsEntry("group", "Mobil").containsEntry("team", "Dijital Kanallar");

        // Toplamlar: (tür, takım) başına beş durum, sıfırlar dahil
        assertThat(value(f, MONITORS, Map.of("type", "http", "team_id", "14", "state", "down"))).isEqualTo(1);
        assertThat(value(f, MONITORS, Map.of("type", "http", "team_id", "14", "state", "paused"))).isEqualTo(0);
        assertThat(f.get(MONITORS)).filteredOn(r -> "http".equals(r.labels().get("type"))).hasSize(5);
        assertThat(value(f, MONITORS_CHECKS, Map.of("type", "http", "team_id", "14"))).isEqualTo(30);
        assertThat(value(f, MONITORS_FAILED, Map.of("type", "http", "team_id", "14"))).isEqualTo(4);
    }

    @Test
    @DisplayName("silinmiş izleme yayılmaz; envanterden çıkmış türev satır izleme başına YOK, toplamda duraklatılmış (asla düşük değil)")
    void deletedAndInventoryInactive() {
        Map<String, Object> deleted = row("port", 7, "Silinen", "db.example.com", 14L, "deleted");
        deleted.put("deleted", true);
        Map<String, Object> orphan = row("port", 8, "Eski türev", "gone.example.com", 14L, "down");
        orphan.put("inventory_inactive", true);
        orphan.put("active", false);
        Families f = new Families();
        MonitoringMetricsModel.monitors(f, overview(List.of(deleted, orphan), types("port")), DIR, true, 100);
        assertThat(has(f, MONITOR_STATE, Map.of("monitor_id", "7"))).isFalse();
        assertThat(has(f, MONITOR_STATE, Map.of("monitor_id", "8"))).isFalse();
        assertThat(value(f, MONITORS, Map.of("type", "port", "state", "paused"))).isEqualTo(1);
        assertThat(value(f, MONITORS, Map.of("type", "port", "state", "down"))).isEqualTo(0);
    }

    @Test
    @DisplayName("okunamayan tür (izlemeler ya da son kontroller): o türün HİÇ satırı yok, tür okunamayanlarda döner — sahte 0 / up yok")
    void unreadableTypeDropsRows() {
        List<Map<String, Object>> t = types("http", "ping", "port");
        t.get(0).put("unavailable", true);
        t.get(1).put("checks_unavailable", true);
        t.get(2).put("stats_unavailable", true);
        Families f = new Families();
        Set<String> failed = MonitoringMetricsModel.monitors(f, overview(List.of(row("http", 1, "a", "https://a", 14L, "up"),
                row("ping", 2, "b", "10.0.0.9", 14L, "unknown"), row("port", 3, "c", "10.0.0.8", 14L, "up")), t), DIR, true, 100);
        assertThat(failed).containsExactly("http", "ping", "port");
        assertThat(f.get(MONITORS)).isEmpty();
        assertThat(f.get(MONITOR_STATE)).isEmpty();
        assertThat(MonitoringMetricsModel.monitors(new Families(), null, DIR, true, 100)).isEmpty();
    }

    @Test
    @DisplayName("tavan: sorunlu izlemeler korunur (düşük → gecikmiş → bilinmiyor → sağlıklı → duraklatılmış), aile işaretlenir; toplamlar tam kalır")
    void capKeepsProblemsFirst() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(row("http", 100 + i, "up" + i, "https://u" + i, 14L, "up"));
        rows.add(row("http", 1, "z-down", "https://d", 14L, "down"));
        rows.add(row("http", 2, "z-stale", "https://s", 14L, "stale"));
        Families f = new Families();
        MonitoringMetricsModel.monitors(f, overview(rows, types("http")), DIR, true, 3);
        assertThat(f.get(MONITOR_STATE)).hasSize(3);
        assertThat(f.get(MONITOR_STATE)).extracting(r -> r.labels().get("monitor_id")).startsWith("1", "2");
        assertThat(f.capped()).containsKey("monitors");
        assertThat(value(f, MONITORS, Map.of("state", "up"))).isEqualTo(10);
    }

    @Test
    @DisplayName("izleme başına kapalı: yalnız toplamlar")
    void perEntityOff() {
        Families f = new Families();
        MonitoringMetricsModel.monitors(f, overview(List.of(row("http", 1, "a", "https://a", 14L, "up")), types("http")), DIR, false, 100);
        assertThat(f.get(MONITOR_STATE)).isEmpty();
        assertThat(f.get(MONITORS)).hasSize(5);
    }

    @Test
    @DisplayName("alarmlar: bağımsız izleme alarmı yalnız damgalı takımın; envanter gibi yönlenen alarm SY + UG (ikinci sahip ug_team); seviye toplamı her alarmı bir kez sayar")
    void alertOwnership() {
        List<Object[]> open = List.of(
                // bağımsız HTTP alarmı: envanterde aynı host başka takımın olsa da yalnız damga (15)
                new Object[]{1L, "api.example.com", "HTTP_DOWN", "CRITICAL", false, 15L, "2026-10-18T06:00:00", null},
                // sertifika alarmı: damgasız → envanterin SY (14) + UG (15)
                new Object[]{2L, "api.example.com", "EXPIRY", "HIGH", true, null, "2026-10-18T05:00:00", null},
                // sahipsiz (damga yok, envanter yok) + bilinmeyen seviye
                new Object[]{3L, "lost.example.com", "PORT_DOWN", "BOGUS", false, null, "2026-10-18T07:00:00", "{}"});
        Map<String, Owners> inv = Map.of("api.example.com", new Owners(14L, 15L));
        Families f = new Families();
        MonitoringMetricsModel.alerts(f, open, inv, DIR, true, 100, Instant.parse("2026-10-18T08:00:00Z"));
        assertThat(value(f, ALERTS_OPEN, Map.of("alert_type", "HTTP_DOWN"))).isEqualTo(1);
        assertThat(f.get(ALERTS_OPEN).stream().filter(r -> "HTTP_DOWN".equals(r.labels().get("alert_type"))).findFirst().orElseThrow().labels())
                .containsEntry("team", "Ödeme Sistemleri").containsEntry("ug_team", "").containsEntry("family", "http")
                .containsEntry("level", "CRITICAL").containsEntry("acknowledged", "false");
        assertThat(f.get(ALERTS_OPEN).stream().filter(r -> "EXPIRY".equals(r.labels().get("alert_type"))).findFirst().orElseThrow().labels())
                .containsEntry("team", "Dijital Kanallar").containsEntry("ug_team", "Ödeme Sistemleri").containsEntry("ug_team_id", "15")
                .containsEntry("family", "cert").containsEntry("acknowledged", "true");
        assertThat(f.get(ALERTS_OPEN).stream().filter(r -> "PORT_DOWN".equals(r.labels().get("alert_type"))).findFirst().orElseThrow().labels())
                .containsEntry("team", "Takımsız").containsEntry("team_id", "0").containsEntry("level", "OTHER");
        assertThat(value(f, ALERTS_OPEN_BY_LEVEL, Map.of("level", "CRITICAL"))).isEqualTo(1);
        assertThat(value(f, ALERTS_OPEN_BY_LEVEL, Map.of("level", "HIGH"))).isEqualTo(1);
        assertThat(value(f, ALERTS_OPEN_BY_LEVEL, Map.of("level", "WARNING"))).as("sıfır da yazılır").isEqualTo(0);
        assertThat(value(f, ALERTS_OPEN_BY_LEVEL, Map.of("level", "OTHER"))).isEqualTo(1);
        assertThat(value(f, ALERTS_OLDEST, Map.of("family", "cert"))).isEqualTo(Instant.parse("2026-10-18T05:00:00Z").getEpochSecond());
        // Alarm başına: önce kritik
        assertThat(f.get(ALERT_OPEN)).extracting(r -> r.labels().get("alert_id")).containsExactly("1", "2", "3");
        assertThat(f.get(ALERT_OPEN).get(0).labels()).containsEntry("target", "api.example.com");
    }

    @Test
    @DisplayName("alarm tavanı: kritik ve eski alarmlar korunur; alarm başına kapalıyken yalnız toplamlar")
    void alertCap() {
        List<Object[]> open = new ArrayList<>();
        for (int i = 0; i < 5; i++) open.add(new Object[]{(long) (10 + i), "w" + i + ".example.com", "HTTP_DOWN", "WARNING", false, 14L, "2026-10-18T07:0" + i + ":00", null});
        open.add(new Object[]{99L, "c.example.com", "HTTP_DOWN", "CRITICAL", false, 14L, "2026-10-18T07:59:00", null});
        Families f = new Families();
        MonitoringMetricsModel.alerts(f, open, Map.of(), DIR, true, 2, Instant.now());
        assertThat(f.get(ALERT_OPEN)).extracting(r -> r.labels().get("alert_id")).containsExactly("99", "10");
        assertThat(f.capped()).containsKey("alerts");
        assertThat(value(f, ALERTS_OPEN, Map.of("level", "WARNING"))).isEqualTo(5);
        Families off = new Families();
        MonitoringMetricsModel.alerts(off, open, Map.of(), DIR, false, 2, Instant.now());
        assertThat(off.get(ALERT_OPEN)).isEmpty();
        assertThat(off.get(ALERTS_OPEN_BY_LEVEL)).hasSize(4);
    }

    static CertificateDto cert(String domain, Integer days, String level, String grade, Long team) {
        CertificateDto c = new CertificateDto();
        c.setDomain(domain);
        c.setDaysRemaining(days);
        c.setAlertLevel(level);
        c.setTlsGrade(grade);
        c.setTeamId(team);
        c.setTier(1);
        c.setCheckedAt("2026-10-18T03:00:00");
        return c;
    }

    @Test
    @DisplayName("sertifikalar: durum kodu, kalan gün, TLS not sırası, kovalar (doldu / 7 / 30 / 90 / sonra / bilinmiyor), UG takımı etiketi")
    void certificates() {
        List<CertificateDto> certs = List.of(cert("a.example.com", 200, "valid", "A+", 14L), cert("b.example.com", 5, "critical", "B", 14L),
                cert("c.example.com", -2, "expired", null, 14L), cert("d.example.com", null, "error", "F", 15L));
        Map<String, Owners> inv = Map.of("b.example.com", new Owners(14L, 15L));
        Families f = new Families();
        MonitoringMetricsModel.certificates(f, certs, inv, DIR, true, 100);
        assertThat(value(f, CERT_STATUS, Map.of("domain", "a.example.com"))).isEqualTo(0);
        assertThat(value(f, CERT_STATUS, Map.of("domain", "b.example.com"))).isEqualTo(3);
        assertThat(value(f, CERT_STATUS, Map.of("domain", "c.example.com"))).isEqualTo(4);
        assertThat(value(f, CERT_STATUS, Map.of("domain", "d.example.com"))).isEqualTo(5);
        assertThat(value(f, CERT_DAYS, Map.of("domain", "c.example.com"))).isEqualTo(-2);
        assertThat(has(f, CERT_DAYS, Map.of("domain", "d.example.com"))).as("kalan gün bilinmiyor → seri yok").isFalse();
        assertThat(value(f, CERT_TLS_GRADE, Map.of("domain", "a.example.com"))).isEqualTo(6);
        assertThat(value(f, CERT_TLS_GRADE, Map.of("domain", "d.example.com"))).isEqualTo(1);
        assertThat(has(f, CERT_TLS_GRADE, Map.of("domain", "c.example.com"))).isFalse();
        assertThat(f.get(CERT_DAYS).stream().filter(r -> "b.example.com".equals(r.labels().get("domain"))).findFirst().orElseThrow().labels())
                .containsEntry("ug_team", "Ödeme Sistemleri").containsEntry("ug_team_id", "15").containsEntry("tier", "1").containsEntry("source", "network");
        assertThat(value(f, CERTS_EXPIRY, Map.of("team_id", "14", "ug_team_id", "", "bucket", "expired"))).isEqualTo(1);
        assertThat(value(f, CERTS_EXPIRY, Map.of("team_id", "14", "ug_team_id", "15", "bucket", "d7"))).isEqualTo(1);
        assertThat(value(f, CERTS_EXPIRY, Map.of("team_id", "15", "bucket", "unknown"))).isEqualTo(1);
        assertThat(value(f, CERTS_GRADE, Map.of("team_id", "14", "ug_team_id", "", "grade", "none"))).isEqualTo(1);
        assertThat(value(f, CERTS, Map.of("team_id", "15", "status", "error"))).isEqualTo(1);
        assertThat(MonitoringMetricsModel.bucket(0)).isEqualTo("d7");
        assertThat(MonitoringMetricsModel.bucket(30)).isEqualTo("d30");
        assertThat(MonitoringMetricsModel.bucket(31)).isEqualTo("d90");
        assertThat(MonitoringMetricsModel.bucket(91)).isEqualTo("later");
    }

    @Test
    @DisplayName("alan adı kaydı: yalnız etkin izlemeler; kalan gün izleme kimliğiyle eşlenir, bilinmeyen gün kovada 'unknown'")
    void domains() {
        Map<String, Object> d1 = row("domain", 31, "Kurum", "example.com", 14L, "up");
        Map<String, Object> d2 = row("domain", 32, "Yeni", "example.org", 14L, "unknown");
        Map<String, Object> d3 = row("domain", 33, "Durduruldu", "example.net", 14L, "paused");
        Families f = new Families();
        MonitoringMetricsModel.domains(f, overview(List.of(d1, d2, d3), types("domain")), List.of(new DomainDays(31, 12)), DIR, true, 100);
        assertThat(value(f, DOMAIN_DAYS, Map.of("monitor_id", "31"))).isEqualTo(12);
        assertThat(has(f, DOMAIN_DAYS, Map.of("monitor_id", "32"))).isFalse();
        assertThat(has(f, DOMAIN_DAYS, Map.of("monitor_id", "33"))).isFalse();
        assertThat(value(f, DOMAINS_EXPIRY, Map.of("bucket", "d30"))).isEqualTo(1);
        assertThat(value(f, DOMAINS_EXPIRY, Map.of("bucket", "unknown"))).isEqualTo(1);
    }

    @Test
    @DisplayName("fırtınalar: takım başına sayı + üye; tüm takımlar toplamı sıfır da yazılır")
    void storms() {
        Families f = new Families();
        MonitoringMetricsModel.storms(f, List.of(new ActiveStorm(14L, 7), new ActiveStorm(14L, 3), new ActiveStorm(null, 2)), DIR);
        assertThat(value(f, STORMS_ACTIVE, Map.of("team_id", "14"))).isEqualTo(2);
        assertThat(value(f, STORM_MEMBERS, Map.of("team_id", "14"))).isEqualTo(10);
        assertThat(value(f, STORMS_ACTIVE_ALL, Map.of())).isEqualTo(3);
        Families none = new Families();
        MonitoringMetricsModel.storms(none, List.of(), DIR);
        assertThat(value(none, STORMS_ACTIVE_ALL, Map.of())).isEqualTo(0);
    }

    @Test
    @DisplayName("bildirimler: ham durum sınıflanır (FAILED: … / SKIPPED: fırtına #12 tek etiket), denenmeyen kanal yazılmaz; push sınıfları sıfırla")
    void notificationsAndPush() {
        Families f = new Families();
        MonitoringMetricsModel.notifications(f, List.of(
                new Object[]{"email", "INITIAL", "SENT", 5L},
                new Object[]{"email", "INITIAL", "FAILED", 2L},
                new Object[]{"email", "STORM", "SKIPPED", 3L},
                new Object[]{"webhook", "INITIAL", "NONE", 9L},
                new Object[]{"email", "weird trigger!", "OTHER", 1L}));
        assertThat(value(f, NOTIFICATIONS, Map.of("channel", "email", "trigger", "INITIAL", "status", "sent"))).isEqualTo(5);
        assertThat(value(f, NOTIFICATIONS, Map.of("channel", "email", "trigger", "STORM", "status", "skipped"))).isEqualTo(3);
        assertThat(has(f, NOTIFICATIONS, Map.of("channel", "webhook"))).isFalse();
        assertThat(value(f, NOTIFICATIONS, Map.of("trigger", "WEIRD_TRIGGER_"))).isEqualTo(1);
        assertThat(MonitoringMetricsModel.statusClass("FAILED: SMTP 550")).isEqualTo("failed");
        assertThat(MonitoringMetricsModel.statusClass("SKIPPED: fırtına #12")).isEqualTo("skipped");
        Families p = new Families();
        MonitoringMetricsModel.pushDeliveries(p, List.of(new Object[]{"SENT", 4L}, new Object[]{"SKIPPED_STORM", 2L},
                new Object[]{"SKIPPED_TEAM_QUIET", 1L}, new Object[]{"FAILED", 1L}));
        assertThat(value(p, PUSH_DELIVERIES, Map.of("status", "skipped"))).isEqualTo(3);
        assertThat(value(p, PUSH_DELIVERIES, Map.of("status", "pending"))).isEqualTo(0);
        assertThat(p.get(PUSH_DELIVERIES)).hasSize(6);
    }

    @Test
    @DisplayName("kilitler: süresi dolan yazılmaz, mon-alert:<anahtar> biçimi mon-alert:* olarak katlanır, ad tavanı")
    void schedulerLocks() {
        Instant now = Instant.parse("2026-10-18T08:00:00Z");
        Families f = new Families();
        MonitoringMetricsModel.schedulerLocks(f, List.of(new Object[]{"cert-check", "2026-10-18T08:05:00"},
                new Object[]{"mon-alert:http:1", "2026-10-18T08:01:00"}, new Object[]{"mon-alert:http:2", "2026-10-18T08:01:00"},
                new Object[]{"watchdog", "2026-10-18T07:00:00"}), now, 10);
        assertThat(value(f, SCHEDULER_LOCK, Map.of("name", "cert-check"))).isEqualTo(1);
        assertThat(value(f, SCHEDULER_LOCK, Map.of("name", "mon-alert:*"))).isEqualTo(2);
        assertThat(has(f, SCHEDULER_LOCK, Map.of("name", "watchdog"))).isFalse();
        Families capped = new Families();
        MonitoringMetricsModel.schedulerLocks(capped, List.of(new Object[]{"a", "2026-10-18T09:00:00"}, new Object[]{"b", "2026-10-18T09:00:00"}), now, 1);
        assertThat(capped.get(SCHEDULER_LOCK)).hasSize(1);
        assertThat(capped.capped()).containsKey("scheduler_locks");
    }

    @Test
    @DisplayName("kodlar: kendi kendini izleme, kontrol, bakım, veritabanı; tanınmayan değer yazılmaz (null)")
    void codes() {
        assertThat(MonitoringMetricsModel.watchdogCode("UP")).isZero();
        assertThat(MonitoringMetricsModel.watchdogCode("DOWN")).isEqualTo(2);
        assertThat(MonitoringMetricsModel.watchdogCode("WHAT")).isNull();
        assertThat(MonitoringMetricsModel.watchdogCheckCode(null, null)).isEqualTo(4);
        assertThat(MonitoringMetricsModel.watchdogCheckCode("OK", null)).isZero();
        assertThat(MonitoringMetricsModel.watchdogCheckCode("PROBLEM", "CRITICAL")).isEqualTo(3);
        assertThat(MonitoringMetricsModel.watchdogCheckCode("PROBLEM", "MEDIUM")).isEqualTo(1);
        assertThat(MonitoringMetricsModel.maintenanceCode("active")).isEqualTo(3);
        assertThat(MonitoringMetricsModel.maintenanceCode("mystery")).isNull();
        assertThat(MonitoringMetricsModel.databaseCode("DEGRADED")).isEqualTo(1);
        assertThat(MonitoringMetricsModel.databaseCode(null)).isNull();
    }

    @Test
    @DisplayName("etiket: denetim karakteri boşluk, uzun değer '…' ile kırpılır; hedef: kullanıcı bilgisi atılır, sır parametresi maskelenir")
    void labelsAndTargets() {
        assertThat(MonitoringMetricsModel.label("a\nb")).isEqualTo("a b");
        String longName = "x".repeat(400);
        assertThat(MonitoringMetricsModel.label(longName)).hasSize(MonitoringMetricsModel.LABEL_MAX).endsWith("…");
        assertThat(MonitoringMetricsModel.safeTarget("https://user:pass@api.example.com/path?token=abc&x=1"))
                .isEqualTo("https://api.example.com/path");
        assertThat(MonitoringMetricsModel.safeTarget("10.0.0.1")).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("hedef etiketi: yalnız şema + host + port + yol — ad listesinin kaçırdığı sır parametreleri (key, code, sig, "
            + "X-Amz-Signature, subscription-key) ve parça hiç yazılmaz; kullanıcı bilgisi atılır")
    void safeTargetDropsQueryAndFragment() {
        String[] secrets = {
                "https://api.example.com:8443/v1/items?key=AIzaSyD-secret",
                "https://login.example.com/callback?code=4/0AY0e-g7secret&state=x",
                "https://blob.example.net/c/f.png?sv=2020&sig=abcSECRET%3D",
                "https://bucket.s3.amazonaws.com/o?X-Amz-Algorithm=AWS4&X-Amz-Signature=deadbeefSECRET",
                "https://gw.example.com/api?subscription-key=SECRETKEY",
                "https://app.example.com/#access_token=SECRET",
                "https://u:p@app.example.com:9443/a/b?x=1#frag",
        };
        for (String in : secrets) {
            String out = MonitoringMetricsModel.safeTarget(in);
            assertThat(out).as(in).doesNotContain("?").doesNotContain("#").doesNotContainIgnoringCase("secret")
                    .doesNotContain("u:p").doesNotContain("@");
        }
        assertThat(MonitoringMetricsModel.safeTarget("https://api.example.com:8443/v1/items?key=AIzaSyD-secret"))
                .isEqualTo("https://api.example.com:8443/v1/items");
        assertThat(MonitoringMetricsModel.safeTarget("https://u:p@app.example.com:9443/a/b?x=1#frag")).isEqualTo("https://app.example.com:9443/a/b");
        assertThat(MonitoringMetricsModel.safeTarget("https://app.example.com/#access_token=SECRET")).isEqualTo("https://app.example.com/");
        assertThat(MonitoringMetricsModel.safeTarget("db.example.com:5432")).isEqualTo("db.example.com:5432");
        assertThat(MonitoringMetricsModel.safeTarget("admin@ssh.example.com")).isEqualTo("ssh.example.com");
        assertThat(MonitoringMetricsModel.safeTarget(null)).isEmpty();
        assertThat(MonitoringMetricsModel.safeTarget("https://x.example.com/" + "a".repeat(5000) + "?k=SECRET"))
                .hasSizeLessThanOrEqualTo(MonitoringMetricsModel.LABEL_MAX).doesNotContain("SECRET");
        assertThat(MonitoringMetricsModel.epochSeconds("2026-10-18T08:00:00")).isEqualTo(Instant.parse("2026-10-18T08:00:00Z").getEpochSecond());
        assertThat(MonitoringMetricsModel.epochSeconds("2026-10-18T08:00:00.123Z")).isEqualTo(Instant.parse("2026-10-18T08:00:00Z").getEpochSecond());
        assertThat(MonitoringMetricsModel.epochSeconds("garbage")).isNull();
    }

    @Test
    @DisplayName("aile kabı: bilinmeyen ölçüm ve yanlış etiket kümesi reddedilir; NaN yazılmaz")
    void familiesGuard() {
        Families f = new Families();
        assertThatThrownBy(() -> f.add("sitemonitor.nope", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.add(MONITORS, 1, "type", "http")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.add(STORMS_ACTIVE, 1, "team_id", "1", "team", "x")).isInstanceOf(IllegalArgumentException.class);
        f.add(STORMS_ACTIVE_ALL, Double.NaN);
        assertThat(f.get(STORMS_ACTIVE_ALL)).isEmpty();
        Map<String, Integer> sizes = new HashMap<>();
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) sizes.put(d.name(), f.size(d.name()));
        assertThat(sizes.values()).allMatch(n -> n == 0);
    }

    @Test
    @DisplayName("takım listesi: her takım + Takımsız, etkinlik etiketiyle")
    void teams() {
        Families f = new Families();
        MonitoringMetricsModel.teams(f, DIR);
        assertThat(f.get(MonitoringMetricNames.TEAMS)).hasSize(4);
        assertThat(value(f, MonitoringMetricNames.TEAMS, Map.of("team", "Pasif Ekip"))).isEqualTo(1);
        assertThat(f.get(MonitoringMetricNames.TEAMS).stream().filter(r -> "Pasif Ekip".equals(r.labels().get("team"))).findFirst().orElseThrow().labels())
                .containsEntry("active", "false");
        assertThat(value(f, MonitoringMetricNames.TEAMS, Map.of("team", "Takımsız", "team_id", "0"))).isEqualTo(1);
        assertThat(new TeamDirectory(Map.of()).name(77L)).isEqualTo("Takım #77");
    }
}

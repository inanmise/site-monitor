package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Erişilebilirlik sayım süzgeci (H paketi 2026-11-26, rapor B1 / B2) GERÇEK PostgreSQL'de — {@code mvn -Ppostgres-it test}.
 *
 * <p>Özet SQL'i ({@code ON CONFLICT … DO UPDATE}) H2'de koşmaz; tek seferlik yeniden hesabın "dilimi TEK işlemde sil +
 * yeniden yaz" adımı ve gece rollup'ının yeni süzgeci burada kendi tohum satırlarıyla, paylaşılan veritabanında BAŞKA testin
 * kullanmadığı bir tarih aralığında (2011) doğrulanır. Ayrıca süzgecin aralık sorgusunun indeks yolunu bozmadığı EXPLAIN'le.
 */
@PostgresIntegration
class AvailabilityRollupRecomputePostgresTest {

    private static final Instant SLICE = Instant.parse("2011-03-07T00:00:00Z");

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static long uniqueId() { return ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L); }

    private static void ping(long id, String at, boolean up, Integer loss, String error, String reason, Boolean manual, Boolean recheck) {
        jdbc().update("INSERT INTO ping_checks(monitor_id, up, rtt_ms, packet_loss, error, failure_reason, checked_at, manual, recheck) "
                + "VALUES (?,?,?,?,?,?,?,?,?)", id, up, up ? 12L : null, loss, error, reason, at, manual, recheck);
    }

    private static void http(long id, String at, boolean ok, Boolean manual, Boolean recheck) {
        jdbc().update("INSERT INTO http_checks(monitor_id, ok, response_ms, checked_at, manual, recheck) VALUES (?,?,?,?,?,?)",
                id, ok, 100L, at, manual, recheck);
    }

    private static Map<String, Object> daily(String type, long key, String day) {
        List<Map<String, Object>> rows = jdbc().queryForList("SELECT total_checks, up_checks FROM monitor_check_daily "
                + "WHERE monitor_type = ? AND monitor_key = ? AND day = ?", type, String.valueOf(key), day);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> hourly(String type, long key, String hour) {
        List<Map<String, Object>> rows = jdbc().queryForList("SELECT total_checks, up_checks FROM monitor_check_hourly "
                + "WHERE monitor_type = ? AND monitor_key = ? AND hour_bucket = ?", type, String.valueOf(key), hour);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static long n(Map<String, Object> row, String col) { return ((Number) row.get(col)).longValue(); }

    private static AvailabilityRollupRecompute.Unit unit(String type) {
        return AvailabilityRollupRecompute.units().stream().filter(u -> u.type().equals(type)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Dilim yeniden hesabı: elle / teyit / ICMP'nin ölçemediği satır sayılmaz; yalnız sayılmayan satırı kalan eski kova SİLİNİR; tekrar koşmak aynı sonucu verir")
    void sliceRecomputeCountsOnlyCountedRows() {
        JdbcTemplate jdbc = jdbc();
        long p = uniqueId(), h = uniqueId();
        // ping 2011-03-08T10: 1 ölçülen ayakta + 2 ölçülemeyen (yeni kod + eski metin) + 1 teyit + 1 elle
        ping(p, "2011-03-08T10:00:00", true, 0, null, null, null, null);
        ping(p, "2011-03-08T10:05:00", false, null, "ICMP bu ortamda kullanılamıyor (yetki/binary)", "ICMP_UNAVAILABLE", null, null);
        ping(p, "2011-03-08T10:10:00", false, null, "ICMP bu ortamda kullanılamıyor (yetki/binary)", null, null, null);
        ping(p, "2011-03-08T10:15:00", false, 100, "100% packet loss", "ALL_PACKETS_LOST", null, true);
        ping(p, "2011-03-08T10:20:00", false, 100, "100% packet loss", "ALL_PACKETS_LOST", true, null);
        // ping 2011-03-09T05: YALNIZ ölçülemeyen satır → bu kovada sayılan satır yok
        ping(p, "2011-03-09T05:00:00", false, null, "ICMP bu ortamda kullanılamıyor (yetki/binary)", "ICMP_UNAVAILABLE", null, null);
        // http 2011-03-08T11: 2 planlı sağlıklı + 1 teyit hatası + 1 elle hata + 1 açıkça FALSE bayraklı (sayılır)
        http(h, "2011-03-08T11:00:00", true, null, null);
        http(h, "2011-03-08T11:05:00", true, false, false);
        http(h, "2011-03-08T11:10:00", false, null, true);
        http(h, "2011-03-08T11:15:00", false, true, null);
        // Eski kuralın yazdığı kovalar (her satırı saymış hâli)
        String up = "INSERT INTO monitor_check_daily(monitor_type, monitor_key, day, total_checks, up_checks) VALUES (?,?,?,?,?)";
        jdbc.update(up, "PING", String.valueOf(p), "2011-03-08", 5, 1);
        jdbc.update(up, "PING", String.valueOf(p), "2011-03-09", 1, 0);
        jdbc.update(up, "HTTP", String.valueOf(h), "2011-03-08", 4, 2);
        String uh = "INSERT INTO monitor_check_hourly(monitor_type, monitor_key, hour_bucket, total_checks, up_checks) VALUES (?,?,?,?,?)";
        jdbc.update(uh, "PING", String.valueOf(p), "2011-03-09T05", 1, 0);

        AvailabilityRollupRecompute job = PostgresIt.app().bean(AvailabilityRollupRecompute.class);
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        for (int round = 0; round < 2; round++) {   // idempotent
            job.recomputeSlice(unit("PING"), SLICE, SLICE.plus(7, ChronoUnit.DAYS), today);
            job.recomputeSlice(unit("HTTP"), SLICE, SLICE.plus(7, ChronoUnit.DAYS), today);

            Map<String, Object> pd = daily("PING", p, "2011-03-08");
            assertThat(n(pd, "total_checks")).as("tur %d", round).isEqualTo(1);
            assertThat(n(pd, "up_checks")).isEqualTo(1);
            assertThat(daily("PING", p, "2011-03-09")).as("sayılan satırı kalmayan kova silinir").isNull();
            assertThat(hourly("PING", p, "2011-03-09T05")).isNull();
            Map<String, Object> ph = hourly("PING", p, "2011-03-08T10");
            assertThat(n(ph, "total_checks")).isEqualTo(1);
            Map<String, Object> hd = daily("HTTP", h, "2011-03-08");
            assertThat(n(hd, "total_checks")).isEqualTo(2);
            assertThat(n(hd, "up_checks")).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("inceleme: ham satırı kalmamış (silinmiş) izlemenin eski kovaları SİLİNMEZ — geçmiş SLA kaymaz; ham satırı olan "
            + "komşu izleme yeniden hesaplanır; Durum İzleme alan adı anahtarıyla aynı kural")
    void deletedMonitorBucketsAreKept() {
        JdbcTemplate jdbc = jdbc();
        long gone = uniqueId(), live = uniqueId();
        http(live, "2011-04-05T09:00:00", true, null, null);
        String up = "INSERT INTO monitor_check_daily(monitor_type, monitor_key, day, total_checks, up_checks) VALUES (?,?,?,?,?)";
        String uh = "INSERT INTO monitor_check_hourly(monitor_type, monitor_key, hour_bucket, total_checks, up_checks) VALUES (?,?,?,?,?)";
        jdbc.update(up, "HTTP", String.valueOf(gone), "2011-04-05", 10, 7);    // ham satırı yok (izleme silindi)
        jdbc.update(uh, "HTTP", String.valueOf(gone), "2011-04-05T09", 10, 7);
        jdbc.update(up, "HTTP", String.valueOf(live), "2011-04-05", 9, 1);      // eski kuralın kovası — yeniden hesaplanır
        String dom = "gone" + uniqueId() + ".example.com";
        jdbc.update(up, "UPTIME", dom, "2011-04-05", 24, 20);

        AvailabilityRollupRecompute job = PostgresIt.app().bean(AvailabilityRollupRecompute.class);
        Instant s = Instant.parse("2011-04-04T00:00:00Z");
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        job.recomputeSlice(unit("HTTP"), s, s.plus(7, ChronoUnit.DAYS), today);
        job.recomputeSlice(unit("UPTIME"), s, s.plus(7, ChronoUnit.DAYS), today);

        Map<String, Object> keptDaily = daily("HTTP", gone, "2011-04-05");
        assertThat(keptDaily).as("silinmiş izlemenin günlük kovası kalır").isNotNull();
        assertThat(n(keptDaily, "total_checks")).isEqualTo(10);
        assertThat(n(keptDaily, "up_checks")).isEqualTo(7);
        assertThat(hourly("HTTP", gone, "2011-04-05T09")).as("saatlik kova da kalır").isNotNull();
        Map<String, Object> liveDaily = daily("HTTP", live, "2011-04-05");
        assertThat(n(liveDaily, "total_checks")).isEqualTo(1);
        assertThat(n(liveDaily, "up_checks")).isEqualTo(1);
        List<Map<String, Object>> uptime = jdbc.queryForList("SELECT total_checks FROM monitor_check_daily "
                + "WHERE monitor_type = 'UPTIME' AND monitor_key = ? AND day = '2011-04-05'", dom);
        assertThat(uptime).hasSize(1);
        assertThat(((Number) uptime.get(0).get("total_checks")).longValue()).isEqualTo(24);
    }

    @Test
    @DisplayName("Gece rollup'ı (üretim upsertSql) aynı süzgeci uygular — elle / teyit / ICMP'nin ölçemediği ping paydada değil")
    void nightlyRollupUsesTheFilter() {
        long p = uniqueId();
        ping(p, "2011-05-10T08:00:00", true, 0, null, null, null, null);
        ping(p, "2011-05-10T08:10:00", false, 100, "100% packet loss", "ALL_PACKETS_LOST", null, null);
        ping(p, "2011-05-10T08:20:00", false, null, "ICMP bu ortamda kullanılamıyor (yetki/binary)", "ICMP_UNAVAILABLE", null, null);
        ping(p, "2011-05-10T08:30:00", false, 100, "100% packet loss", "ALL_PACKETS_LOST", null, true);
        jdbc().update(SchedulerService.upsertSql("monitor_check_daily", "day", 10, "PING", "ping_checks", "up", "rtt_ms"),
                "2011-05-10T00:00:00", "2011-05-11T00:00:00");
        Map<String, Object> d = daily("PING", p, "2011-05-10");
        assertThat(n(d, "total_checks")).isEqualTo(2);
        assertThat(n(d, "up_checks")).isEqualTo(1);
    }

    @Test
    @DisplayName("EXPLAIN: sayım süzgeci aralık sorgusunun checked_at indeks koşulunu bozmaz (ek koşul satır süzgecidir)")
    void filterKeepsTheCheckedAtIndexPath() {
        List<String> plans = new ArrayList<>();
        for (String table : List.of("ping_checks", "http_checks", "uptime_checks", "dbquery_checks")) {
            String sql = "EXPLAIN SELECT count(*) FROM " + table + " WHERE checked_at >= '2011-01-01T00:00:00' "
                    + "AND checked_at < '2011-02-01T00:00:00'" + AvailabilityRows.andCounted(table);
            String plan = jdbc().execute((ConnectionCallback<String>) con -> {
                boolean auto = con.getAutoCommit();
                con.setAutoCommit(false);
                try (PreparedStatement off = con.prepareStatement("SET LOCAL enable_seqscan = off")) {
                    off.execute();
                    StringBuilder out = new StringBuilder();
                    try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.append(rs.getString(1)).append('\n');
                    }
                    return out.toString();
                } finally {
                    con.rollback();
                    con.setAutoCommit(auto);
                }
            });
            plans.add(plan);
            assertThat(plan).as(table + ":\n" + plan).doesNotContain("Seq Scan").contains("checked_at");
        }
        assertThat(plans).hasSize(4);
    }
}

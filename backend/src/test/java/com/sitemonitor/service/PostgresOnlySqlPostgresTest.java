package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStormMember;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.util.SqlSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL'e özgü yazma yolları GERÇEK PostgreSQL'de (2026-10-02, onaylı öneri 28) — {@code mvn -Ppostgres-it test}.
 *
 * <p>Buradaki cümlelerin ortak özelliği: üretimde hatalarını YUTARLAR (gözlem kaydı / sahiplenme / rollup try-catch'li)
 * ve H2 onları ya hiç koşturamaz ({@code ON CONFLICT … DO UPDATE}, kısmi indeks çıkarımı) ya da PostgreSQL'den farklı
 * yorumlar. Bozuldukları gün bildirim davranışı sessizce değişir (fırtına açılmaz, iki pod aynı push'u gönderir, trend
 * boş kalır) ve hiçbir birim testi kırmızı olmaz. Bu sınıf onları gerçek motorda, kendi tohum satırlarıyla koşar.
 */
@PostgresIntegration
class PostgresOnlySqlPostgresTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static long uniqueId() { return ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L); }

    private static String now() { return ISO.format(Instant.now()); }

    // ── Fırtına üyeliği ve açılışı ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Fırtına üyeliği: INSERT … ON CONFLICT DO NOTHING tekil + toplu, duyuru ve ayrılış UPDATE'leri")
    void stormMembership_onConflictDoNothing_andLifecycleUpdates() {
        JdbcTemplate jdbc = jdbc();
        long storm = uniqueId(), e1 = uniqueId(), e2 = uniqueId();

        assertThat(jdbc.update(StormService.SQL_MEMBER_INSERT, storm, e1, now(), AlertStormMember.JOIN_TRIGGER)).isEqualTo(1);
        assertThat(jdbc.update(StormService.SQL_MEMBER_INSERT, storm, e1, now(), AlertStormMember.JOIN_ATTACH))
                .as("aynı (fırtına, alarm) ikinci kez → çakışma sessizce yutulur").isZero();

        // Açılış/taşıma yolu: TEK batch — biri çakışan, biri yeni.
        jdbc.batchUpdate(StormService.SQL_MEMBER_INSERT, List.of(
                new Object[]{storm, e1, now(), AlertStormMember.JOIN_PEER},
                new Object[]{storm, e2, now(), AlertStormMember.JOIN_PEER}));
        assertThat(count("SELECT count(*) FROM alert_storm_members WHERE storm_id = ?", storm)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT join_kind FROM alert_storm_members WHERE storm_id = ? AND alert_event_id = ?",
                String.class, storm, e1)).as("ilk katılım türü korunur").isEqualTo(AlertStormMember.JOIN_TRIGGER);

        String announce = StormService.SQL_MEMBER_ANNOUNCED_PREFIX + "?,?)";
        assertThat(jdbc.update(announce, now(), storm, e1, e2)).isEqualTo(2);
        assertThat(jdbc.update(announce, now(), storm, e1, e2)).as("ilk duyuru anı bir kez yazılır").isZero();

        int[] left = jdbc.batchUpdate(StormService.SQL_MEMBER_LEFT,
                List.<Object[]>of(new Object[]{now(), AlertStormMember.LEAVE_RECOVERED, storm, e1}));
        assertThat(left).containsExactly(1);
        assertThat(jdbc.batchUpdate(StormService.SQL_MEMBER_LEFT,
                List.<Object[]>of(new Object[]{now(), AlertStormMember.LEAVE_NOTIFIED, storm, e1})))
                .as("ayrılış bir kez yazılır (left_at IS NULL)").containsExactly(0);
        assertThat(count("SELECT count(*) FROM alert_storm_members WHERE storm_id = ? AND left_at IS NULL", storm)).isEqualTo(1);
    }

    @Test
    @DisplayName("Fırtına terfisi: ON CONFLICT (scope_key) WHERE resolved = false — kapsam başına TEK aktif fırtına")
    void stormPromotion_partialUniqueConflictTarget_allowsOneActivePerScope() throws Exception {
        StormService storm = AopTestUtils.getTargetObject(PostgresIt.app().bean(StormService.class));
        // Cümle özel metodun içinde (StormTeamIsolationTest bağ değişkenlerini konumsal okur); kopyalamak yerine
        // ÜRETİM metodu çağrılır — ad değişirse bu test NoSuchMethodException ile açıkça kırılır.
        Method insert = StormService.class.getDeclaredMethod("insertStormIfAbsent",
                String.class, String.class, List.class, StormService.OpenSnapshot.class);
        insert.setAccessible(true);

        long team = uniqueId();
        String scope = "TEAM:" + team;
        AlertEvent peer = new AlertEvent();
        peer.setId(uniqueId());
        peer.setAlertType(EscalationService.TYPE_HTTP_DOWN);
        StormService.OpenSnapshot snap = new StormService.OpenSnapshot(team, null, 3, 3, peer.getId());

        assertThat((Boolean) insert.invoke(storm, scope, "TEAM", List.of(peer), snap))
                .as("ilk terfi INSERT'i satır yazmalı (false = PostgreSQL cümleyi reddetti, WARN'a bakın)").isTrue();
        assertThat((Boolean) insert.invoke(storm, scope, "TEAM", List.of(peer), snap))
                .as("aynı kapsamda ikinci AKTİF fırtına açılamaz").isFalse();
        assertThat(count("SELECT count(*) FROM alert_storms WHERE scope_key = ? AND resolved = false", scope)).isEqualTo(1);
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT team_id, threshold_effective, targets_at_open, peak_targets, trigger_event_id, root_cause "
              + "FROM alert_storms WHERE scope_key = ? AND resolved = false", scope);
        assertThat(((Number) row.get("team_id")).longValue()).isEqualTo(team);
        assertThat(((Number) row.get("trigger_event_id")).longValue()).isEqualTo(peer.getId());
        assertThat(row.get("root_cause")).isEqualTo(EscalationService.TYPE_HTTP_DOWN);

        jdbc().update("UPDATE alert_storms SET resolved = true, resolved_at = ? WHERE scope_key = ?", now(), scope);
        assertThat((Boolean) insert.invoke(storm, scope, "TEAM", List.of(peer), snap))
                .as("önceki fırtına çözülünce aynı kapsamda yenisi açılabilir (kısmi indeks)").isTrue();
        assertThat(count("SELECT count(*) FROM alert_storms WHERE scope_key = ?", scope)).isEqualTo(2);
    }

    // ── Fırtına push'u ↔ alarm bağı (2026-10-04) ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Fırtına push kapsamı: tek batch ON CONFLICT DO NOTHING + okuma sorguları (IN, LIKE OR =, GROUP BY, DISTINCT, JPQL CAST) PostgreSQL'de")
    void stormPushCoverage_insertAndReadQueries() {
        StormPushCoverageService svc = AopTestUtils.getTargetObject(PostgresIt.app().bean(StormPushCoverageService.class));
        UserPushDeliveryRepository repo = PostgresIt.app().bean(UserPushDeliveryRepository.class);
        long storm = uniqueId(), team = uniqueId(), e1 = uniqueId(), e2 = uniqueId();
        String key = "storm:" + storm + ":INITIAL";
        AlertEvent a = new AlertEvent(); a.setId(e1); a.setTeamId(team); a.setResolved(false);
        AlertEvent b = new AlertEvent(); b.setId(e2); b.setTeamId(team); b.setResolved(false);

        assertThat(svc.record(storm, team, key, "INITIAL", List.of(a, b))).isEqualTo(2);
        assertThat(svc.record(storm, team, key, "INITIAL", List.of(a, b))).as("çakışma yutulur, hata yok").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM storm_push_coverage WHERE push_key = ?", key)).isEqualTo(2);

        jdbc().update(StormService.SQL_MEMBER_INSERT, storm, e1, now(), AlertStormMember.JOIN_TRIGGER);
        UserPushDelivery d = new UserPushDelivery();
        d.setTrigger("STORM"); d.setDedupeKey(key); d.setTeamId(team); d.setUsername("IT_STORM_USER"); d.setStatus("SENT");
        d.setAttempts(1); d.setCreatedAt(now()); d.setSentAt(now());
        repo.save(d);

        StormPushCoverageService.Result r = svc.coverageForAlerts(List.of(a), null);
        assertThat(r.of(e1).pushes()).singleElement().satisfies(p -> {
            assertThat(p.pushKey()).isEqualTo(key);
            assertThat(p.inferred()).isFalse();
        });
        assertThat(svc.alarmsForNotices(List.of(new StormPushCoverageService.NoticeRef(key, team, now())))
                .get(StormPushCoverageService.noticeKey(key, team))).hasSize(2);
        assertThat(repo.findStormNoticeRowsForViewer(java.util.Set.of(key), "it_storm_user")).hasSize(1);
        assertThat(repo.findByDedupeKeyInOrderByIdAsc(java.util.Set.of(key))).hasSize(1);
    }

    // ── Kişi push outbox sahiplenmesi ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Push outbox sahiplenmesi: claimDue + findByIdInAndNextAttemptAt — ilk tur alır, ikinci tur 0")
    void userPushClaim_secondClaimGetsNothing() {
        UserPushDeliveryRepository repo = PostgresIt.app().bean(UserPushDeliveryRepository.class);
        UserPushService push = AopTestUtils.getTargetObject(PostgresIt.app().bean(UserPushService.class));
        String key = "it-claim:" + UUID.randomUUID();
        List<UserPushDelivery> saved = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UserPushDelivery d = new UserPushDelivery();
            d.setAlertEventId(uniqueId());
            d.setTrigger("INITIAL");
            d.setDedupeKey(key);
            d.setUsername("IT_USER_" + i);
            d.setStatus("PENDING");
            d.setAttempts(0);
            d.setCreatedAt(now());
            saved.add(repo.save(d));
        }
        List<Long> ids = saved.stream().map(UserPushDelivery::getId).toList();
        List<UserPushDelivery> due = repo.findAllById(ids);
        assertThat(due).hasSize(3);

        List<UserPushDelivery> first = push.claim(due);
        assertThat(first).as("ilk tur zamanı gelmiş üç satırı da kiralar").hasSize(3);
        String lease = first.get(0).getNextAttemptAt();
        assertThat(lease).as("kira damgası yazılmış olmalı").isNotBlank().isGreaterThan(now());
        assertThat(first).allSatisfy(d -> assertThat(d.getNextAttemptAt()).isEqualTo(lease));

        List<UserPushDelivery> second = push.claim(repo.findAllById(ids));
        assertThat(second).as("kiralanmış satırları ikinci tur (başka pod) ALAMAZ").isEmpty();

        // Repository yolları doğrudan: kira bitene dek ne sahiplenilir ne "zamanı gelmiş" listelenir.
        assertThat(repo.claimDue(ids, now(), "9999-12-31T23:59:59.000000000")).isZero();
        assertThat(repo.findByIdInAndNextAttemptAtOrderByIdAsc(ids, lease)).extracting(UserPushDelivery::getId)
                .containsExactlyElementsOf(ids);
        assertThat(repo.findDuePending(now(), PageRequest.of(0, 500))).extracting(UserPushDelivery::getId)
                .doesNotContainAnyElementsOf(ids);
    }

    // ── Rollup upsert'leri (ON CONFLICT … DO UPDATE) ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Günlük + saatlik rollup upsert'leri (8 tür × 2 kova) koşar; ikinci tur ÜZERİNE yazar")
    void rollupUpserts_runForEveryType_andOverwriteOnConflict() {
        JdbcTemplate jdbc = jdbc();
        long monitor = uniqueId();
        String domain = "it-rollup-" + UUID.randomUUID() + ".invalid";
        String from = "2020-01-01T00:00:00", to = "2020-01-02T00:00:00";
        jdbc.update("INSERT INTO http_checks(monitor_id, ok, response_ms, checked_at) VALUES (?, true, 100, ?)",
                monitor, "2020-01-01T10:05:00");
        jdbc.update("INSERT INTO http_checks(monitor_id, ok, response_ms, checked_at) VALUES (?, false, 300, ?)",
                monitor, "2020-01-01T10:35:00");
        jdbc.update("INSERT INTO uptime_checks(domain, port, status, response_ms, checked_at, maintenance) "
                + "VALUES (?, 443, 'up', 50, ?, false)", domain, "2020-01-01T11:00:00");

        runAllRollups(jdbc, from, to);

        Map<String, Object> daily = jdbc.queryForMap("SELECT total_checks, up_checks, avg_response_ms, max_response_ms "
                + "FROM monitor_check_daily WHERE monitor_type = 'HTTP' AND monitor_key = ? AND day = '2020-01-01'",
                String.valueOf(monitor));
        assertThat(((Number) daily.get("total_checks")).longValue()).isEqualTo(2);
        assertThat(((Number) daily.get("up_checks")).longValue()).isEqualTo(1);
        assertThat(((Number) daily.get("avg_response_ms")).intValue()).isEqualTo(200);
        assertThat(((Number) daily.get("max_response_ms")).intValue()).isEqualTo(300);
        assertThat(count("SELECT count(*) FROM monitor_check_hourly WHERE monitor_type = 'HTTP' AND monitor_key = ? "
                + "AND hour_bucket = '2020-01-01T10'", String.valueOf(monitor))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM monitor_check_daily WHERE monitor_type = 'UPTIME' AND monitor_key = ?",
                domain)).isEqualTo(1);

        // Geriye-doldurma birden çok kez koşabilir: aynı kova ÇAKIŞIR ve yeni toplamla güncellenir (DO NOTHING değil).
        jdbc.update("INSERT INTO http_checks(monitor_id, ok, response_ms, checked_at) VALUES (?, true, 900, ?)",
                monitor, "2020-01-01T10:50:00");
        runAllRollups(jdbc, from, to);
        Map<String, Object> again = jdbc.queryForMap("SELECT total_checks, up_checks, max_response_ms FROM monitor_check_daily "
                + "WHERE monitor_type = 'HTTP' AND monitor_key = ? AND day = '2020-01-01'", String.valueOf(monitor));
        assertThat(((Number) again.get("total_checks")).longValue()).isEqualTo(3);
        assertThat(((Number) again.get("up_checks")).longValue()).isEqualTo(2);
        assertThat(((Number) again.get("max_response_ms")).intValue()).isEqualTo(900);
        assertThat(count("SELECT count(*) FROM monitor_check_daily WHERE monitor_type = 'HTTP' AND monitor_key = ?",
                String.valueOf(monitor))).as("kova başına tek satır").isEqualTo(1);
    }

    /** {@code SchedulerService.rollupAllTypes} ile aynı tür/tablo/kolon listesi — üretim SQL üreticileri kullanılır. */
    private static void runAllRollups(JdbcTemplate jdbc, String from, String to) {
        String[][] types = {
                {"PORT", "port_checks", "open", "response_ms"},
                {"PING", "ping_checks", "up", "rtt_ms"},
                {"KEYWORD", "keyword_results", "ok", "response_ms"},
                {"HTTP", "http_checks", "ok", "response_ms"},
                {"PAGE", "page_checks", "ok", "response_ms"},
                {"SCRIPTED", "scripted_checks", "ok", "duration_ms"},
                {"PAGESPEED", "pagespeed_checks", "ok", "response_ms"}};
        for (String[] t : types) {
            jdbc.update(SchedulerService.upsertSql("monitor_check_daily", "day", 10, t[0], t[1], t[2], t[3]), from, to);
            jdbc.update(SchedulerService.upsertSql(SchedulerService.HOURLY_TABLE, SchedulerService.HOURLY_COL, 13,
                    t[0], t[1], t[2], t[3]), from, to);
        }
        jdbc.update(SchedulerService.uptimeUpsertSql("monitor_check_daily", "day", 10), from, to);
        jdbc.update(SchedulerService.uptimeUpsertSql(SchedulerService.HOURLY_TABLE, SchedulerService.HOURLY_COL, 13), from, to);
    }

    // ── SQL Playground örnekleri ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SQL Playground'un TÜM hazır örnekleri PostgreSQL'de hatasız koşar (H2'deki 'PG'ye özgü' toleransı yok)")
    void everySqlSample_runsCleanOnPostgres() {
        SqlPlaygroundService playground = PostgresIt.app().bean(SqlPlaygroundService.class);
        List<String> failures = new ArrayList<>();
        for (Map<String, String> sample : SqlSamples.list()) {
            Map<String, Object> res = playground.execute(sample.get("sql"), "postgres-it");
            if (!Boolean.TRUE.equals(res.get("ok"))) failures.add(sample.get("label") + " :: " + res.get("error"));
        }
        assertThat(SqlSamples.list()).isNotEmpty();
        assertThat(failures).as("PostgreSQL'de başarısız örnek sorgular").isEmpty();
    }

    private static long count(String sql, Object... args) {
        Long n = jdbc().queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}

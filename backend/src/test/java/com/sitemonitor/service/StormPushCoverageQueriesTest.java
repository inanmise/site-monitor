package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.AlertStormMember;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormMemberRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fırtına push'u ↔ alarm bağı (2026-10-04) — GERÇEK SQL (H2, PostgreSQL modu): kayıt (tek batch, çakışma yutulur, takım
 * yalıtımı, çözüm yalnız kurtulanlar), alarm detayı (kayıtlı bildirim + alıcı durumları, kayıt öncesi bildirimde tahmin,
 * bildirimden sonra katılanın kapsanmaması, "henüz fırtına push'u gitmedi"), bildirim → alarmlar, push geçmişim bağları
 * (kişinin kendi satırı / karar nedeni / alıcı değil; başka takımın alarmı sayı olarak), teslimat günlüğü ve fırtına
 * ayrıntısı push listesi. Yazan sorgular işlem DIŞINDA koşar (StormObservabilityQueriesTest deseni).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:stormpushcov;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class StormPushCoverageQueriesTest {

    static final long TEAM_A = 14L;
    static final long TEAM_B = 15L;
    static final String T0 = "2026-10-04T08:00:00";

    @Autowired AlertStormRepository storms;
    @Autowired AlertStormMemberRepository members;
    @Autowired AlertEventRepository events;
    @Autowired UserPushDeliveryRepository deliveries;
    @Autowired TeamRepository teams;
    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    StormPushCoverageService svc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM storm_push_coverage");
        deliveries.deleteAll();
        members.deleteAll();
        events.deleteAll();
        storms.deleteAll();
        svc = new StormPushCoverageService(jdbc, events, storms, deliveries);
    }

    // ── Kurulum yardımcıları ──────────────────────────────────────────────────────────────────────────────────────

    AlertStorm storm(long team, boolean resolved) {
        AlertStorm s = new AlertStorm();
        s.setScopeKey("TEAM:" + team + "|t" + System.nanoTime());
        s.setScopeType("TEAM");
        s.setTeamId(team);
        s.setResolved(resolved);
        s.setCreatedAt(T0);
        s.setLastReAlertAt(T0);
        return storms.saveAndFlush(s);
    }

    AlertEvent alarm(String host, long team, Long stormId, String createdAt, boolean resolved, String resolvedAt) {
        AlertEvent e = new AlertEvent();
        e.setDomain(host); e.setAlertType("HTTP_DOWN"); e.setAlertLevel("WARNING"); e.setTeamId(team);
        e.setAcknowledged(false); e.setResolved(resolved); e.setResolvedAt(resolvedAt);
        e.setStormId(stormId); e.setCreatedAt(createdAt);
        return events.saveAndFlush(e);
    }

    void member(long stormId, long eventId, String joinedAt, String kind, String leftAt, String leaveKind) {
        AlertStormMember m = new AlertStormMember(stormId, eventId, joinedAt, kind);
        m.setLeftAt(leftAt);
        m.setLeaveKind(leaveKind);
        members.saveAndFlush(m);
    }

    UserPushDelivery push(String key, long team, String user, String status, String createdAt, String sentAt) {
        UserPushDelivery d = new UserPushDelivery();
        d.setTrigger(key.startsWith("storm-resolved:") ? "STORM_RESOLVED" : "STORM");
        d.setDedupeKey(key);
        d.setMonitorType("STORM");
        d.setMonitorName("Alarm fırtınası");
        d.setTeamId(team);
        d.setAlertLevel("WARNING");
        d.setUsername(user);
        d.setDisplayName("-".equals(user) ? "(katman kararı)" : "Kişi " + user);
        d.setStatus(status);
        d.setCreatedAt(createdAt);
        d.setSentAt(sentAt);
        d.setAttempts("SENT".equals(status) ? 1 : 0);
        d.setMessage("-".equals(user) ? null : "3 monitör birden erişilemez — Takım A");
        return deliveries.saveAndFlush(d);
    }

    UserPushDelivery decision(long alertId, long team, String status) {
        UserPushDelivery d = new UserPushDelivery();
        d.setAlertEventId(alertId);
        d.setTrigger("OPEN");
        d.setDedupeKey("OPEN");
        d.setMonitorType("http");
        d.setMonitorName("h");
        d.setTeamId(team);
        d.setUsername("-");
        d.setDisplayName("(katman kararı)");
        d.setStatus(status);
        d.setCreatedAt(T0);
        return deliveries.saveAndFlush(d);
    }

    long coverageRows() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM storm_push_coverage", Long.class);
        return n == null ? 0 : n;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(Map<String, Object> detail) {
        return (List<Map<String, Object>>) detail.get("items");
    }

    // ── Kayıt ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("record: TEK batch; tekrar çakışmayı yutar; TEAM:A bildirimi TEAM:B alarmını kapsamaz; çözüm yalnız KURTULANLARI kapsar")
    void record_batch_conflict_isolation_resolveOnlyRecovered() {
        AlertStorm s = storm(TEAM_A, false);
        AlertEvent a = alarm("a.example.com", TEAM_A, s.getId(), T0, false, null);
        AlertEvent b = alarm("b.example.com", TEAM_A, s.getId(), T0, true, "2026-10-04T09:00:00");
        AlertEvent c = alarm("c.example.com", TEAM_B, s.getId(), T0, false, null);
        String initial = "storm:" + s.getId() + ":INITIAL";

        assertThat(svc.record(s.getId(), TEAM_A, initial, "INITIAL", List.of(a, b, c))).isEqualTo(2);
        assertThat(svc.record(s.getId(), TEAM_A, initial, "INITIAL", List.of(a, b, c))).as("çakışma yutulur").isEqualTo(2);
        assertThat(coverageRows()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT alert_event_id FROM storm_push_coverage WHERE push_key = ? ORDER BY alert_event_id",
                Long.class, initial)).containsExactly(a.getId(), b.getId());

        String resolved = "storm-resolved:" + s.getId();
        assertThat(svc.record(s.getId(), TEAM_A, resolved, "RESOLVE", List.of(a, b))).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT alert_event_id FROM storm_push_coverage WHERE push_key = ?", Long.class, resolved))
                .containsExactly(b.getId());
        assertThat(jdbc.queryForObject("SELECT notice_trigger FROM storm_push_coverage WHERE push_key = ?", String.class, resolved))
                .isEqualTo("RESOLVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM storm_push_coverage WHERE alert_event_id = ?", Long.class, c.getId()))
                .as("başka takımın alarmı").isZero();
        // Geçersiz girdi: yazma yok, istisna yok
        assertThat(svc.record(null, TEAM_A, initial, "INITIAL", List.of(a))).isZero();
        assertThat(svc.record(s.getId(), TEAM_A, initial, "BOGUS", List.of(a))).isZero();
    }

    // ── Alarm detayı ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("alarm detayı: kayıtlı bildirim → alıcı satırları + durum sayıları + ilk/son gönderim; başka takımın aynı anahtarlı satırı girmez")
    void alarmDetail_recorded_recipientsAndCounts() {
        AlertStorm s = storm(TEAM_A, false);
        AlertEvent a = alarm("a.example.com", TEAM_A, s.getId(), T0, false, null);
        member(s.getId(), a.getId(), T0, AlertStormMember.JOIN_TRIGGER, null, null);
        String key = "storm:" + s.getId() + ":INITIAL";
        push(key, TEAM_A, "N00001", "SENT", "2026-10-04T08:00:05", "2026-10-04T08:00:07");
        push(key, TEAM_A, "N00002", "FAILED", "2026-10-04T08:00:05", null);
        push(key, TEAM_A, "N00003", "RATE_LIMITED", "2026-10-04T08:00:05", null);
        push(key, TEAM_B, "N00009", "SENT", "2026-10-04T08:00:05", "2026-10-04T08:00:06");   // başka takım
        svc.record(s.getId(), TEAM_A, key, "INITIAL", List.of(a));

        Map<String, Object> d = svc.alarmDetail(a, null, false);

        assertThat(items(d)).singleElement().satisfies(it -> {
            assertThat(it.get("trigger")).isEqualTo("INITIAL");
            assertThat(it.get("storm_id")).isEqualTo(s.getId());
            assertThat(it.get("inferred")).isEqualTo(false);
            assertThat(it.get("recipient_total")).isEqualTo(3);
            assertThat(it.get("sent")).isEqualTo(1);
            assertThat(it.get("failed")).isEqualTo(1);
            assertThat(it.get("not_sent")).isEqualTo(1);
            assertThat(it.get("first_sent_at")).isEqualTo("2026-10-04T08:00:07");
            assertThat(it.get("outcome")).isEqualTo("sent");
            assertThat(it.get("covered_alarms")).isEqualTo(1L);
            assertThat((Map<String, Integer>) it.get("counts")).containsEntry("SENT", 1).containsEntry("FAILED", 1).containsEntry("RATE_LIMITED", 1);
            assertThat((List<Map<String, Object>>) it.get("recipients")).extracting(r -> r.get("username"))
                    .containsExactly("N00001", "N00002", "N00003");
        });
        assertThat((List<?>) d.get("pending")).isEmpty();
        assertThat((List<Map<String, Object>>) d.get("storms")).singleElement().satisfies(sm -> {
            assertThat(sm.get("active")).isEqualTo(true);
            assertThat(sm.get("covered")).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("kayıt öncesi bildirim: üyelik penceresinden TAHMİN (inferred); bildirimden SONRA katılan kapsanmaz ve devredildiyse 'henüz gitmedi' (pending)")
    void alarmDetail_legacyInferred_lateJoinerPending() {
        AlertStorm s = storm(TEAM_A, false);
        AlertEvent early = alarm("early.example.com", TEAM_A, s.getId(), T0, false, null);
        AlertEvent late = alarm("late.example.com", TEAM_A, s.getId(), "2026-10-04T08:30:00", false, null);
        member(s.getId(), early.getId(), T0, AlertStormMember.JOIN_PEER, null, null);
        member(s.getId(), late.getId(), "2026-10-04T08:30:00", AlertStormMember.JOIN_ATTACH, null, null);
        push("storm:" + s.getId() + ":INITIAL", TEAM_A, "N00001", "SENT", "2026-10-04T08:00:10", "2026-10-04T08:00:12");
        decision(late.getId(), TEAM_A, EscalationService.PUSH_SKIPPED_STORM);

        Map<String, Object> e = svc.alarmDetail(early, null, false);
        assertThat(items(e)).singleElement().satisfies(it -> {
            assertThat(it.get("inferred")).isEqualTo(true);
            assertThat(it.get("covered_alarms")).isNull();
            assertThat(it.get("sent")).isEqualTo(1);
        });
        assertThat((List<?>) e.get("pending")).as("devir satırı yok → pending yok").isEmpty();

        Map<String, Object> l = svc.alarmDetail(late, null, false);
        assertThat(items(l)).as("açılış push'undan sonra katıldı").isEmpty();
        assertThat(l.get("handed_over")).isEqualTo(true);
        assertThat((List<Map<String, Object>>) l.get("pending")).singleElement().satisfies(p -> {
            assertThat(p.get("storm_id")).isEqualTo(s.getId());
            assertThat(p.get("joined_at")).isEqualTo("2026-10-04T08:30:00");
            assertThat(p.get("next_realert_at")).isEqualTo("2026-10-05T08:00:00");
        });
    }

    @Test
    @DisplayName("kayıtlı bildirimde tahmin YOK: kaydı olmayan alarm gerçekten kapsanmamıştır; tahmin yalnız alarmın kendi takımının bildiriminden")
    void recordedNotice_noInference_andInferenceTeamIsolation() {
        AlertStorm s = storm(TEAM_A, true);
        AlertEvent a = alarm("a.example.com", TEAM_A, null, T0, false, null);
        AlertEvent b = alarm("b.example.com", TEAM_A, null, T0, false, null);
        member(s.getId(), a.getId(), T0, AlertStormMember.JOIN_PEER, null, null);
        member(s.getId(), b.getId(), T0, AlertStormMember.JOIN_PEER, null, null);
        String key = "storm:" + s.getId() + ":INITIAL";
        push(key, TEAM_A, "N00001", "SENT", "2026-10-04T08:00:10", "2026-10-04T08:00:12");
        svc.record(s.getId(), TEAM_A, key, "INITIAL", List.of(a));
        assertThat(items(svc.alarmDetail(a, null, false))).hasSize(1);
        assertThat(items(svc.alarmDetail(b, null, false))).as("kayıt esas — b kapsanmadı").isEmpty();

        // Başka takımın (kayıtsız) bildirimi A takımının alarmına tahmin edilmez.
        AlertStorm s2 = storm(TEAM_A, true);
        AlertEvent x = alarm("x.example.com", TEAM_A, null, T0, false, null);
        member(s2.getId(), x.getId(), T0, AlertStormMember.JOIN_PEER, null, null);
        push("storm:" + s2.getId() + ":INITIAL", TEAM_B, "N00009", "SENT", "2026-10-04T08:00:10", "2026-10-04T08:00:12");
        assertThat(items(svc.alarmDetail(x, null, false))).isEmpty();
    }

    @Test
    @DisplayName("tahmin: günlük tekrar o an düşük üyeyi, çözüm yalnız RECOVERED ayrılanı kapsar; tekrardan önce kapanan alarm tekrara girmez")
    void inference_dailyAndResolveWindows() {
        AlertStorm s = storm(TEAM_A, true);
        AlertEvent down = alarm("down.example.com", TEAM_A, null, T0, false, null);
        AlertEvent recovered = alarm("rec.example.com", TEAM_A, null, T0, true, "2026-10-04T10:00:00");
        member(s.getId(), down.getId(), T0, AlertStormMember.JOIN_PEER, "2026-10-05T12:00:00", AlertStormMember.LEAVE_NOTIFIED);
        member(s.getId(), recovered.getId(), T0, AlertStormMember.JOIN_PEER, "2026-10-04T10:00:00", AlertStormMember.LEAVE_RECOVERED);
        String daily = "storm:" + s.getId() + ":DAILY_REALERT:2026-10-05";
        String resolve = "storm-resolved:" + s.getId();
        push(daily, TEAM_A, "N00001", "SENT", "2026-10-05T08:00:00", "2026-10-05T08:00:02");
        push(resolve, TEAM_A, "N00001", "SENT", "2026-10-05T12:00:00", "2026-10-05T12:00:01");

        assertThat(items(svc.alarmDetail(down, null, false))).extracting(i -> i.get("trigger")).containsExactly("DAILY_REALERT");
        assertThat(items(svc.alarmDetail(down, null, false)).get(0).get("day")).isEqualTo("2026-10-05");
        assertThat(items(svc.alarmDetail(recovered, null, false))).extracting(i -> i.get("trigger")).containsExactly("RESOLVE");
    }

    // ── Bildirim → alarmlar ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("alarmsForNotices: kayıtlı bildirim kayıttaki alarmları, kayıtsız (eski) bildirim penceredeki üyeleri (tahmin) döner")
    void alarmsForNotices_recordedAndLegacy() {
        AlertStorm s = storm(TEAM_A, true);
        AlertEvent a = alarm("a.example.com", TEAM_A, null, T0, false, null);
        AlertEvent b = alarm("b.example.com", TEAM_A, null, "2026-10-04T09:00:00", false, null);
        member(s.getId(), a.getId(), T0, AlertStormMember.JOIN_PEER, null, null);
        member(s.getId(), b.getId(), "2026-10-04T09:00:00", AlertStormMember.JOIN_ATTACH, null, null);
        String initial = "storm:" + s.getId() + ":INITIAL";
        String daily = "storm:" + s.getId() + ":DAILY_REALERT:2026-10-05";
        svc.record(s.getId(), TEAM_A, initial, "INITIAL", List.of(a));

        Map<String, List<StormPushCoverageService.CoveredAlarm>> out = svc.alarmsForNotices(List.of(
                new StormPushCoverageService.NoticeRef(initial, TEAM_A, "2026-10-04T08:00:10"),
                new StormPushCoverageService.NoticeRef(daily, TEAM_A, "2026-10-05T08:00:00"),
                new StormPushCoverageService.NoticeRef(daily, TEAM_B, "2026-10-05T08:00:00")));

        assertThat(out.get(StormPushCoverageService.noticeKey(initial, TEAM_A)))
                .containsExactly(new StormPushCoverageService.CoveredAlarm(a.getId(), false));
        assertThat(out.get(StormPushCoverageService.noticeKey(daily, TEAM_A)))
                .containsExactly(new StormPushCoverageService.CoveredAlarm(a.getId(), true), new StormPushCoverageService.CoveredAlarm(b.getId(), true));
        assertThat(out.get(StormPushCoverageService.noticeKey(daily, TEAM_B))).as("TEAM:B bildirimi A alarmlarını kapsamaz").isNull();
    }

    // ── Push geçmişim ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("push geçmişim: devir satırı kişinin ALDIĞI fırtına push'unu (zaman + durum) ya da nedenini; fırtına satırı kapsadığı alarmları (başka takımınki sayı) gösterir")
    void viewerHistory_links() {
        AlertStorm s = storm(TEAM_A, false);
        AlertEvent a = alarm("site-a.example.com", TEAM_A, s.getId(), T0, false, null);
        AlertEvent b = alarm("site-b.example.com", TEAM_A, s.getId(), T0, false, null);
        member(s.getId(), a.getId(), T0, AlertStormMember.JOIN_TRIGGER, null, null);
        member(s.getId(), b.getId(), T0, AlertStormMember.JOIN_PEER, null, null);
        String key = "storm:" + s.getId() + ":INITIAL";
        push(key, TEAM_A, "N00001", "SENT", "2026-10-04T08:00:10", "2026-10-04T08:00:12");
        push(key, TEAM_A, "N00002", "RATE_LIMITED", "2026-10-04T08:00:10", null);
        svc.record(s.getId(), TEAM_A, key, "INITIAL", List.of(a, b));
        decision(a.getId(), TEAM_A, EscalationService.PUSH_SKIPPED_STORM);
        // Kayıtlı ama başka takıma damgalı alarm (eski veri / taşıma): kişi göremez → yalnız sayı.
        AlertEvent foreign = alarm("foreign.example.com", TEAM_B, null, T0, false, null);
        jdbc.update(StormPushCoverageService.SQL_INSERT, s.getId(), TEAM_A, key, "INITIAL", foreign.getId(), T0);

        MyPushHistoryService history = new MyPushHistoryService(deliveries, teams);
        history.setStormPushCoverage(svc);
        Instant now = Instant.parse("2026-10-04T12:00:00Z");

        Map<String, Object> own = history.history("n00001", List.of(TEAM_A), t -> t == TEAM_A, 7, "all", 0, 25, now);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) own.get("data");
        Map<String, Object> dec = rows.stream().filter(r -> "SKIPPED_STORM".equals(r.get("status"))).findFirst().orElseThrow();
        Map<String, Object> sp = (Map<String, Object>) dec.get("storm_push");
        assertThat(sp.get("state")).isEqualTo("received");
        assertThat((List<Map<String, Object>>) sp.get("pushes")).singleElement().satisfies(p -> {
            assertThat(p.get("status")).isEqualTo("SENT");
            assertThat(p.get("at")).isEqualTo("2026-10-04T08:00:12");
            assertThat(p.get("reason")).isNull();
        });
        Map<String, Object> notice = rows.stream().filter(r -> "STORM".equals(r.get("trigger")) && Boolean.TRUE.equals(r.get("own")))
                .findFirst().orElseThrow();
        Map<String, Object> sa = (Map<String, Object>) notice.get("storm_alarms");
        assertThat(sa.get("total")).isEqualTo(3);
        assertThat(sa.get("visible_count")).isEqualTo(2);
        assertThat(sa.get("hidden")).isEqualTo(1);
        assertThat((List<Map<String, Object>>) sa.get("alarms")).extracting(m -> m.get("target"))
                .containsExactly("site-a.example.com", "site-b.example.com");
        assertThat(String.valueOf(sa)).doesNotContain("foreign.example.com");

        // Tavana takılan kişi: devir satırı "iletilmedi: RATE_LIMITED"
        Map<String, Object> limited = history.history("N00002", List.of(TEAM_A), t -> t == TEAM_A, 7, "all", 0, 25, now);
        Map<String, Object> dec2 = ((List<Map<String, Object>>) limited.get("data")).stream()
                .filter(r -> "SKIPPED_STORM".equals(r.get("status"))).findFirst().orElseThrow();
        Map<String, Object> sp2 = (Map<String, Object>) dec2.get("storm_push");
        assertThat(sp2.get("state")).isEqualTo("not_received");
        assertThat(sp2.get("reason")).isEqualTo("RATE_LIMITED");

        // Alıcı olmayan takım üyesi: NOT_RECIPIENT; başka kişinin satırı dönmez
        Map<String, Object> other = history.history("N00077", List.of(TEAM_A), t -> t == TEAM_A, 7, "all", 0, 25, now);
        Map<String, Object> dec3 = ((List<Map<String, Object>>) other.get("data")).stream()
                .filter(r -> "SKIPPED_STORM".equals(r.get("status"))).findFirst().orElseThrow();
        Map<String, Object> sp3 = (Map<String, Object>) dec3.get("storm_push");
        assertThat(sp3.get("reason")).isEqualTo("NOT_RECIPIENT");
        assertThat(String.valueOf(sp3)).doesNotContain("N00001").doesNotContain("N00002");
    }

    @Test
    @DisplayName("push geçmişim: devredilen alarmı henüz hiçbir fırtına push'u kapsamadı → 'waiting'; kanal kapalı bildirim → karar nedeni")
    void viewerHistory_waitingAndDecisionReason() {
        AlertStorm s = storm(TEAM_A, false);
        AlertEvent late = alarm("late.example.com", TEAM_A, s.getId(), "2026-10-04T09:00:00", false, null);
        member(s.getId(), late.getId(), "2026-10-04T09:00:00", AlertStormMember.JOIN_ATTACH, null, null);
        decision(late.getId(), TEAM_A, EscalationService.PUSH_SKIPPED_STORM);
        MyPushHistoryService history = new MyPushHistoryService(deliveries, teams);
        history.setStormPushCoverage(svc);
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        Map<String, Object> dec = ((List<Map<String, Object>>) history.history("N00001", List.of(TEAM_A), t -> true, 7, "all", 0, 25, now)
                .get("data")).get(0);
        assertThat(((Map<String, Object>) dec.get("storm_push")).get("state")).isEqualTo("waiting");

        // Kanal kapalı (takım push'u kapalı) toplu bildirim: kişiye satır yok, karar satırı SKIPPED_TEAM_OFF
        String key = "storm:" + s.getId() + ":DAILY_REALERT:2026-10-05";
        push(key, TEAM_A, "-", "SKIPPED_TEAM_OFF", "2026-10-04T10:00:00", null);
        svc.record(s.getId(), TEAM_A, key, "DAILY_REALERT", List.of(late));
        Map<String, Object> dec2 = ((List<Map<String, Object>>) history.history("N00001", List.of(TEAM_A), t -> true, 7, "all", 0, 25, now)
                .get("data")).stream().filter(r -> "SKIPPED_STORM".equals(r.get("status"))).findFirst().orElseThrow();
        Map<String, Object> sp = (Map<String, Object>) dec2.get("storm_push");
        assertThat(sp.get("state")).isEqualTo("not_received");
        assertThat(sp.get("reason")).isEqualTo("SKIPPED_TEAM_OFF");
    }

    @Test
    @DisplayName("findStormNoticeRowsForViewer: kullanıcı adı büyük/küçük harf duyarsız; yalnız kişinin kendi satırı + karar satırı")
    void repository_viewerRows() {
        push("storm:1:INITIAL", TEAM_A, "N00001", "SENT", T0, T0);
        push("storm:1:INITIAL", TEAM_A, "N00002", "SENT", T0, T0);
        push("storm:1:INITIAL", TEAM_A, "-", "SKIPPED_NO_RECIPIENTS", T0, null);
        push("storm:2:INITIAL", TEAM_A, "N00001", "SENT", T0, T0);
        assertThat(deliveries.findStormNoticeRowsForViewer(Set.of("storm:1:INITIAL"), "n00001"))
                .extracting(UserPushDelivery::getUsername).containsExactly("N00001", "-");
        assertThat(deliveries.findByDedupeKeyInOrderByIdAsc(Set.of("storm:1:INITIAL", "storm:2:INITIAL"))).hasSize(4);
    }

    // ── Teslimat günlüğü + fırtına ayrıntısı ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("teslimat günlüğü: fırtına satırı kapsadığı alarmları (kapsam süzgeciyle), devir satırı kapsayan bildirimin satırlarını verir")
    void pushLog_links() {
        AlertStorm s = storm(TEAM_A, false);
        AlertEvent a = alarm("a.example.com", TEAM_A, s.getId(), T0, false, null);
        AlertEvent foreign = alarm("f.example.com", TEAM_B, null, T0, false, null);
        member(s.getId(), a.getId(), T0, AlertStormMember.JOIN_TRIGGER, null, null);
        String key = "storm:" + s.getId() + ":INITIAL";
        UserPushDelivery row = push(key, TEAM_A, "N00001", "SENT", "2026-10-04T08:00:10", "2026-10-04T08:00:12");
        svc.record(s.getId(), TEAM_A, key, "INITIAL", List.of(a));
        jdbc.update(StormPushCoverageService.SQL_INSERT, s.getId(), TEAM_A, key, "INITIAL", foreign.getId(), T0);

        Map<String, Object> cov = svc.noticeCoverage(row, t -> t != null && t == TEAM_A);
        assertThat(cov.get("total")).isEqualTo(2);
        assertThat(cov.get("hidden")).isEqualTo(1);
        assertThat((List<Map<String, Object>>) cov.get("alarms")).extracting(m -> m.get("domain")).containsExactly("a.example.com");

        StormPushCoverageService.AlarmPushes cp = svc.coveringPushes(a);
        assertThat(cp.pushes()).singleElement().satisfies(p -> {
            assertThat(p.ref().pushKey()).isEqualTo(key);
            assertThat(p.rows()).extracting(UserPushDelivery::getUsername).containsExactly("N00001");
        });
        assertThat(svc.noticeCoverage(decision(a.getId(), TEAM_A, "SKIPPED_STORM"), t -> true)).as("bildirim satırı değil").isNull();
    }

    @Test
    @DisplayName("fırtına ayrıntısı: bildirim başına alıcı/gönderilen + KAPSANAN alarm sayısı (kayıtlı) ve kayıtsız bildirimde tahmin")
    void stormDetail_pushList() {
        AlertStorm s = storm(TEAM_A, true);
        AlertEvent a = alarm("a.example.com", TEAM_A, null, T0, true, "2026-10-04T09:00:00");
        AlertEvent b = alarm("b.example.com", TEAM_A, null, T0, false, null);
        member(s.getId(), a.getId(), T0, AlertStormMember.JOIN_TRIGGER, "2026-10-04T09:00:00", AlertStormMember.LEAVE_RECOVERED);
        member(s.getId(), b.getId(), T0, AlertStormMember.JOIN_PEER, "2026-10-04T09:30:00", AlertStormMember.LEAVE_NOTIFIED);
        String initial = "storm:" + s.getId() + ":INITIAL";
        String resolve = "storm-resolved:" + s.getId();
        push(initial, TEAM_A, "N00001", "SENT", "2026-10-04T08:00:10", "2026-10-04T08:00:12");
        push(initial, TEAM_A, "N00002", "FAILED", "2026-10-04T08:00:10", null);
        svc.record(s.getId(), TEAM_A, initial, "INITIAL", List.of(a, b));
        push(resolve, TEAM_A, "N00001", "SENT", "2026-10-04T09:30:00", "2026-10-04T09:30:01");   // kayıtsız → tahmin

        StormStatusService status = new StormStatusService(null, storms, members, events, teams, jdbc);
        Map<Long, AlertStormMember> byEvent = new LinkedHashMap<>();
        for (AlertStormMember m : members.findByStormIdOrderByJoinedAtAsc(s.getId())) byEvent.put(m.getAlertEventId(), m);
        Map<Long, AlertEvent> evs = new HashMap<>();
        for (AlertEvent e : events.findAllById(byEvent.keySet())) evs.put(e.getId(), e);

        List<Map<String, Object>> list = status.stormPushes(s, new ArrayList<>(byEvent.keySet()), byEvent, evs);
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).containsEntry("trigger", "INITIAL").containsEntry("recipients", 2).containsEntry("sent", 1)
                .containsEntry("covered_alarms", 2L).containsEntry("inferred", false);
        assertThat(list.get(1)).containsEntry("trigger", "RESOLVE").containsEntry("covered_alarms", 1L).containsEntry("inferred", true);
    }
}

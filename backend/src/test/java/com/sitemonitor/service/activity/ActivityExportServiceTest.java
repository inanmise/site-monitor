package com.sitemonitor.service.activity;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.activity.ActivityFilter.Params;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.activity.ActivityRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ActivityExportService} GERÇEK H2 üstünde (2026-11-05, aktivite zenginleştirmesi E): ekranla AYNI süzgeç, tüm
 * sayfalar anahtar kümeli parçalarla (aynı saniyede satır yinelenmez / kaybolmaz), BOM'lu CSV + formül koruması, JSON
 * biçimi, tavan başlıkta ve JSON'da, kişi başına tek / dakikada 6, pod izinleri dolu → EXPORT_BUSY, denetim satırı.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivityExportServiceTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** Sabit "şimdi": varsayılan 30 günlük aralık bu ana göre (tohum satırları 2026-11-05 sabahı). */
    private static final long NOW_MS = java.time.Instant.parse("2026-11-05T12:00:00Z").toEpochMilli();

    @Autowired DataSource dataSource;
    @Autowired TeamRepository teams;

    JdbcTemplate jdbc;
    AuditService audit;
    ActivityExportService svc;
    long teamA, teamB;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        ActivityRows.extendSchema(jdbc);
        jdbc.update("DELETE FROM activity_log");
        teams.deleteAll();
        Team a = new Team();
        a.setName("Ödeme, Kanallar");
        teamA = teams.save(a).getId();
        Team b = new Team();
        b.setName("Başka");
        teamB = teams.save(b).getId();
        audit = mock(AuditService.class);
        svc = new ActivityExportService(jdbc, audit, JSON);
        svc.chunk = 2;                    // parça sınırlarını küçük veriyle de geç
        svc.clock = () -> NOW_MS;
    }

    private static MockHttpSession session(String user) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", user);
        return s;
    }

    private ActivityFilter filter(Params p, List<Long> scope) {
        return ActivityFilter.parse(p, scope != null, scope == null ? List.of() : scope);
    }

    private MockHttpServletResponse export(ActivityFilter f, String format, String user) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ResponseEntity<?> r = svc.export(f, format, false, session(user), new MockHttpServletRequest(), resp);
        assertThat(r).as("akış başladı").isNull();
        return resp;
    }

    private void seed() {
        row("2026-11-05T10:00:00", "HTTP", 1L, "SUCCESS").team(teamA).ms(120).http(200).summary("200 · 120ms").save(jdbc);
        row("2026-11-05T10:01:00", "HTTP", 1L, "ERROR").team(teamA).error("=cmd|' /c calc'!A1").reason("HTTP_STATUS").http(503)
                .request("rid-1").save(jdbc);
        row("2026-11-05T10:01:00", "PORT", 2L, "TIMEOUT").team(teamA).target("b.example.com:443").save(jdbc);   // aynı saniye
        row("2026-11-05T10:01:00", "DNS", 3L, "ERROR").team(teamA).action("ALERT_TRIGGERED").alert(91L).save(jdbc);
        row("2026-11-05T10:02:00", "HTTP", 4L, "ERROR").team(teamB).save(jdbc);                                 // başka takım
    }

    @Test
    @DisplayName("CSV: BOM + başlık, kapsamdaki TÜM satırlar (parça sınırında aynı saniye yinelenmez), İstanbul saati, formül koruması")
    void csvAllRowsAcrossChunks() throws Exception {
        seed();
        MockHttpServletResponse resp = export(filter(Params.legacy(null, null, null, null, null, null), List.of(teamA)), "csv", "u1");
        String body = resp.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿" + String.join(",", ActivityExportService.COLUMNS) + "\r\n");
        String[] lines = body.substring(1).split("\r\n");
        assertThat(lines).hasSize(5);                                  // başlık + 4 satır (B takımı yok)
        assertThat(body).doesNotContain("2026-11-05 13:02:00");
        assertThat(lines[1]).startsWith("2026-11-05 13:01:00,");       // UTC 10:01 = İstanbul 13:01, en yeni üstte
        assertThat(body).contains("\"Ödeme, Kanallar\"").contains("'=cmd|' /c calc'!A1").contains(",HTTP_STATUS,503,")
                .contains(",rid-1,").contains(",91,");
        assertThat(resp.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(resp.getHeader("X-Export-Truncated")).isEqualTo("false");
        assertThat(resp.getHeader("Content-Disposition")).contains("aktivite_");
        long ids = java.util.Arrays.stream(lines).skip(1).map(l -> l.substring(l.lastIndexOf(',') + 1)).distinct().count();
        assertThat(ids).isEqualTo(4);
    }

    @Test
    @DisplayName("süzgeç ekranla aynı: durum ERROR,TIMEOUT + grup check → yalnız iki kontrol satırı")
    void sameFilterAsList() throws Exception {
        seed();
        ActivityFilter f = filter(new Params(null, null, null, "ERROR,TIMEOUT", null, null, null, null, "check", null, null), null);
        String body = export(f, "csv", "u2").getContentAsString(StandardCharsets.UTF_8);
        assertThat(body.substring(1).split("\r\n")).hasSize(4);       // başlık + HTTP:1 + PORT:2 + HTTP:4 (global)
        assertThat(body).doesNotContain("ALERT_TRIGGERED");
    }

    @Test
    @DisplayName("JSON: columns / rows / row_count / max_rows / truncated / filter; tavan aşılınca başlık + truncated")
    void jsonAndTruncation() throws Exception {
        seed();
        MockHttpServletResponse resp = export(filter(Params.legacy(null, null, null, null, null, null), List.of(teamA)), "json", "u3");
        JsonNode root = JSON.readTree(resp.getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("success").asBoolean()).isTrue();
        JsonNode d = root.get("data");
        assertThat(d.get("columns").size()).isEqualTo(ActivityExportService.COLUMNS.size());
        assertThat(d.get("rows").size()).isEqualTo(4);
        assertThat(d.get("row_count").asInt()).isEqualTo(4);
        assertThat(d.get("truncated").asBoolean()).isFalse();
        assertThat(d.get("time_zone").asString()).isEqualTo("Europe/Istanbul");
        assertThat(d.get("range_defaulted").asBoolean()).as("aralık seçilmedi → son 30 gün").isTrue();
        assertThat(d.get("range_default_days").asInt()).isEqualTo(30);
        assertThat(d.get("filter").get("from").asString()).isEqualTo("2026-10-06T12:00:00");
        assertThat(d.get("filter").get("to").asString()).isEqualTo("2026-11-05T12:00:00");
        JsonNode first = d.get("rows").get(0);
        assertThat(first.get(0).asString()).isEqualTo("2026-11-05 13:01:00");
        assertThat(d.get("filter").get("types").isArray()).isTrue();

        // tavan: sınırlı sayım (tavan + 1) ve akış tavanda durur
        assertThat(svc.countCapped(filter(Params.legacy(null, null, null, null, null, null), List.of(teamA)), 2)).isEqualTo(3);
        assertThat(svc.countCapped(filter(Params.legacy(null, null, null, null, null, null), List.of(teamA)), 10)).isEqualTo(4);
        assertThat(svc.stream(filter(Params.legacy(null, null, null, null, null, null), List.of(teamA)), 3, Map.of(), r -> { }))
                .as("tavan kadar satır yazılır").isEqualTo(3);
    }

    @Test
    @DisplayName("denetim: ACTIVITY_EXPORT — biçim, satır sayısı, tavan, süzgeç (satır içeriği yok)")
    void auditRow() throws Exception {
        seed();
        export(filter(new Params("HTTP", null, null, null, null, null, null, null, null, null, "sepet"), List.of(teamA)), "csv", "u4");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).recordAction(eq("ACTIVITY_EXPORT"), any(HttpSession.class), any(HttpServletRequest.class), eq("ACTIVITY"),
                eq("export"), detail.capture());
        assertThat(detail.getValue()).contains("\"format\":\"csv\"").contains("\"rows\":0").contains("\"types\"")
                .contains("sepet").doesNotContain("cmd");
    }

    @Test
    @DisplayName("sınırlar: kişi başına dakikada 6 (7. → 429 RATE_LIMITED + Retry-After); kişi başına tek; pod dolu → EXPORT_BUSY")
    void limits() throws Exception {
        ActivityFilter f = filter(Params.legacy(null, null, null, null, null, null), List.of(teamA));
        for (int i = 0; i < 6; i++) export(f, "csv", "u5");
        ResponseEntity<?> r = svc.export(f, "csv", false, session("u5"), new MockHttpServletRequest(), new MockHttpServletResponse());
        assertThat(r.getStatusCode().value()).isEqualTo(429);
        assertThat(((Map<?, ?>) r.getBody()).get("code")).isEqualTo("RATE_LIMITED");
        assertThat(r.getHeaders().getFirst("Retry-After")).isNotNull();

        svc.running.add("n:u6");                                         // aynı kişinin süren dışa aktarımı
        ResponseEntity<?> busy = svc.export(f, "csv", false, session("u6"), new MockHttpServletRequest(), new MockHttpServletResponse());
        assertThat(((Map<?, ?>) busy.getBody()).get("code")).isEqualTo("EXPORT_BUSY");
        svc.running.remove("n:u6");

        svc.waitMs = 10;
        svc.slots.acquire(ActivityExportService.EXPORT_CONCURRENCY);
        try {
            ResponseEntity<?> full = svc.export(f, "csv", false, session("u7"), new MockHttpServletRequest(), new MockHttpServletResponse());
            assertThat(full.getStatusCode().value()).isEqualTo(429);
            assertThat(((Map<?, ?>) full.getBody()).get("code")).isEqualTo("EXPORT_BUSY");
        } finally {
            svc.slots.release(ActivityExportService.EXPORT_CONCURRENCY);
        }
        // pod dolu reddi dakikalık hakkı yemedi: u7 hâlâ 6 kez dışa aktarabilir
        for (int i = 0; i < 6; i++) export(f, "csv", "u7");
        assertThat(svc.running).isEmpty();
        assertThat(svc.slots.availablePermits()).isEqualTo(ActivityExportService.EXPORT_CONCURRENCY);
    }

    @Test
    @DisplayName("geçersiz biçim → 400 alan format, denetim yazılmaz; kapsamsız oturum → yalnız başlık")
    void formatAndEmptyScope() throws Exception {
        ActivityFilter f = filter(Params.legacy(null, null, null, null, null, null), List.of(teamA));
        assertThatThrownBy(() -> svc.export(f, "xlsx", false, session("u8"), new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(FieldValidationException.class);
        verifyNoInteractions(audit);
        seed();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        svc.export(f, null, true, session("u8"), new MockHttpServletRequest(), resp);
        assertThat(resp.getContentAsString(StandardCharsets.UTF_8).substring(1).split("\r\n")).hasSize(1);
    }

    // ── İnceleme O3 (2026-11-05): aralık sınırı, dizin sıralı anahtar kümeli okuma, takım başına imleç birleştirme ──

    @Test
    @DisplayName("O3: aralık yok → son 30 gün (başlık + 30 günden eski satır yok); açık aralık korunur; 90 günü aşan → 400, hak düşmez")
    void rangeDefaultAndLimit() throws Exception {
        seed();
        row("2026-09-01T10:00:00", "HTTP", 9L, "ERROR").team(teamA).save(jdbc);           // 30 günden eski
        MockHttpServletResponse def = export(filter(Params.legacy(null, null, null, null, null, null), List.of(teamA)), "csv", "r1");
        assertThat(def.getHeader("X-Export-Range-Defaulted")).isEqualTo("true");
        assertThat(def.getHeader("X-Export-From")).isEqualTo("2026-10-06T12:00:00Z");
        assertThat(def.getHeader("X-Export-To")).isEqualTo("2026-11-05T12:00:00Z");
        assertThat(def.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("2026-09-01");

        ActivityFilter explicit = filter(Params.legacy(null, null, null, "2026-08-20", "2026-11-05T12:00:00Z", null), List.of(teamA));
        MockHttpServletResponse ex = export(explicit, "csv", "r1");
        assertThat(ex.getHeader("X-Export-Range-Defaulted")).isEqualTo("false");
        assertThat(ex.getContentAsString(StandardCharsets.UTF_8)).contains("2026-09-01 13:00:00");

        ActivityFilter tooLong = filter(Params.legacy(null, null, null, "2026-01-01", "2026-11-05", null), List.of(teamA));
        assertThatThrownBy(() -> svc.export(tooLong, "csv", false, session("r2"), new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("from"));
        // yalnız bitiş verilmiş: başlangıç bitişten 30 gün önce
        ActivityExportService.Range onlyTo = ActivityExportService.range(
                filter(Params.legacy(null, null, null, null, "2026-10-01T00:00:00Z", null), null), java.time.Instant.ofEpochMilli(NOW_MS));
        assertThat(onlyTo.defaulted()).isTrue();
        assertThat(onlyTo.filter().from()).isEqualTo("2026-09-01T00:00:00");
        // 400 dakikalık hakkı yemedi: r2 hâlâ 6 kez dışa aktarabilir
        for (int i = 0; i < 6; i++) export(filter(Params.legacy(null, null, null, null, null, null), List.of(teamA)), "csv", "r2");
        assertThat(svc.running).isEmpty();
    }

    @Test
    @DisplayName("O3: kapsamlı oturumda takım başına imleç + birleştirme — tek sorgunun sırasıyla birebir (aynı saniye eşitleri dahil), yinelenme / kayıp yok")
    void mergedTeamCursorsKeepGlobalOrder() throws Exception {
        long teamC = teams.save(teamNamed("Üçüncü")).getId();
        String[] times = {"10:00:00", "10:00:00", "10:00:01", "10:00:01", "10:00:01", "10:00:05", "10:01:00", "10:01:00", "10:02:30"};
        long[] owners = {teamA, teamB, teamA, teamB, teamC, teamA, teamC, teamB, teamA};
        for (int i = 0; i < times.length; i++) {
            row("2026-11-05T" + times[i], "HTTP", 100L + i, i % 3 == 0 ? "ERROR" : "SUCCESS").team(owners[i]).save(jdbc);
        }
        row("2026-11-05T10:00:03", "HTTP", 999L, "ERROR").team(null).save(jdbc);         // takımsız: kapsamlıya görünmez
        ActivityFilter scoped = ActivityExportService.range(filter(Params.legacy(null, null, null, null, null, null),
                List.of(teamA, teamB, teamC)), java.time.Instant.ofEpochMilli(NOW_MS)).filter();
        assertThat(ActivityExportService.cursorTeams(scoped)).containsExactly(teamA, teamB, teamC);
        List<Long> expected = jdbc.queryForList("SELECT id FROM activity_log WHERE team_id IN (?, ?, ?)"
                + " ORDER BY activity_time DESC, id DESC", Long.class, teamA, teamB, teamC);
        for (int chunkSize : new int[]{1, 2, 3, 1000}) {
            svc.chunk = chunkSize;
            List<Long> got = new java.util.ArrayList<>();
            assertThat(svc.stream(scoped, 1000, Map.of(), r -> got.add(r.id()))).isEqualTo(expected.size());
            assertThat(got).as("parça %d", chunkSize).containsExactlyElementsOf(expected);
        }
        svc.chunk = 2;
        List<Long> capped = new java.util.ArrayList<>();
        assertThat(svc.stream(scoped, 4, Map.of(), r -> capped.add(r.id()))).isEqualTo(4);
        assertThat(capped).containsExactlyElementsOf(expected.subList(0, 4));

        // teamId kapsamla kesişir: içerideki takım → yalnız o; dışarıdaki → hiç satır (sorgu yok)
        ActivityFilter onlyB = ActivityFilter.parse(new Params(null, null, null, null, scoped.from(), scoped.to(), teamB, null, null, null, null),
                true, List.of(teamA, teamB, teamC));
        assertThat(ActivityExportService.cursorTeams(onlyB)).containsExactly(teamB);
        List<Long> bRows = new java.util.ArrayList<>();
        svc.stream(onlyB, 1000, Map.of(), r -> bRows.add(r.id()));
        assertThat(bRows).hasSize(3);
        ActivityFilter outside = ActivityFilter.parse(new Params(null, null, null, null, scoped.from(), scoped.to(), 424242L,
                null, null, null, null), true, List.of(teamA));
        assertThat(ActivityExportService.cursorTeams(outside)).isEmpty();
        assertThat(svc.stream(outside, 1000, Map.of(), r -> { })).isZero();

        // kapsamsız (global) → tek imleç, takımsız satır dahil
        ActivityFilter global = ActivityExportService.range(filter(Params.legacy(null, null, null, null, null, null), null),
                java.time.Instant.ofEpochMilli(NOW_MS)).filter();
        assertThat(ActivityExportService.cursorTeams(global)).isNull();
        assertThat(svc.stream(global, 1000, Map.of(), r -> { })).isEqualTo(expected.size() + 1);
        // çok takım (> MERGE_MAX_TEAMS) → tek imleç, aynı satırlar
        List<Long> many = new java.util.ArrayList<>(List.of(teamA, teamB, teamC));
        for (long i = 0; i < ActivityExportService.MERGE_MAX_TEAMS; i++) many.add(900_000L + i);
        ActivityFilter wide = ActivityExportService.range(filter(Params.legacy(null, null, null, null, null, null), many),
                java.time.Instant.ofEpochMilli(NOW_MS)).filter();
        assertThat(ActivityExportService.cursorTeams(wide)).isNull();
        List<Long> wideRows = new java.util.ArrayList<>();
        svc.stream(wide, 1000, Map.of(), r -> wideRows.add(r.id()));
        assertThat(wideRows).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("O3: sayım SIRALAMAZ (ORDER BY yok, LIMIT ile kesilir); parça sorgusu zaman sınırını ayrı dizin koşulu olarak taşır")
    void sqlShapes() {
        assertThat(ActivityExportService.countSql("WHERE 1 = 1")).doesNotContain("ORDER BY").endsWith("LIMIT ?) capped");
        ActivityFilter f = filter(Params.legacy(null, null, null, "2026-11-01", null, null), List.of(teamA));
        ActivityFilter.Sql first = ActivityExportService.chunkSql(f, teamA, null, 0, 100);
        assertThat(first.where()).contains("team_id = ?").doesNotContain("id < ?").endsWith("ORDER BY activity_time DESC, id DESC LIMIT ?");
        ActivityFilter.Sql next = ActivityExportService.chunkSql(f, teamA, "2026-11-05T10:00:00", 42L, 100);
        assertThat(next.where()).contains("activity_time <= ? AND (activity_time < ? OR id < ?)");
        assertThat(next.args()).endsWith("2026-11-05T10:00:00", "2026-11-05T10:00:00", 42L, 100);
    }

    private static Team teamNamed(String name) {
        Team t = new Team();
        t.setName(name);
        return t;
    }
}

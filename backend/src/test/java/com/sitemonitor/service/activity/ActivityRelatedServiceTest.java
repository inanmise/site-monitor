package com.sitemonitor.service.activity;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static com.sitemonitor.service.activity.ActivityRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ActivityRelatedService} GERÇEK H2 üstünde (2026-11-05, aktivite zenginleştirmesi F): pencere yalnız görünen
 * kapsamdan (başka takımın satırı / sayısı / adı dönmez), dağılımlar, şüpheli kodlar (yaygın DNS, aynı host, aynı takım,
 * tekil), olgular (ilk hata N gün sonra, 24 sa sayımları, son başarı, toparlanma süresi, sürüyor), eski satırın neden
 * anahtarı ({@code class:}), kapı ve sabit sorgu sayısı.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivityRelatedServiceTest {

    @Autowired DataSource dataSource;
    @Autowired TeamRepository teams;
    @Autowired PingMonitorRepository pings;
    @Autowired HttpMonitorRepository https;

    JdbcTemplate jdbc;
    CountingDataSource counting;
    ActivityRelatedService svc;
    long teamA, teamB;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        ActivityRows.extendSchema(jdbc);
        jdbc.update("DELETE FROM activity_log");
        pings.deleteAll();
        https.deleteAll();
        teams.deleteAll();
        teamA = teams.save(team("Ödeme")).getId();
        teamB = teams.save(team("Gizli Takım")).getId();
        counting = new CountingDataSource(dataSource);
        svc = new ActivityRelatedService(new JdbcTemplate(counting));
        svc.clock = () -> Instant.parse("2026-11-05T12:00:00Z");   // "hâlâ hata veriyor" penceresi bugüne uzanır
    }

    private long ping(Long team, boolean active) {
        PingMonitor m = new PingMonitor();
        m.setName("ping-" + System.nanoTime());
        m.setHost("10.1.1.1");
        m.setTeamId(team);
        m.setActive(active);
        return pings.save(m).getId();
    }

    private long http(Long team, String url) {
        HttpMonitor m = new HttpMonitor();
        m.setName("http-" + System.nanoTime());
        m.setUrl(url);
        m.setTeamId(team);
        m.setActive(true);
        return https.save(m).getId();
    }

    private static Team team(String name) {
        Team t = new Team();
        t.setName(name);
        return t;
    }

    private ActivityRelatedService.Result related(long id, Integer window, Set<Long> visible) {
        return svc.related(id, window, true, List.copyOf(visible), visible::contains);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    private static List<Object> codes(Map<String, Object> body, String key) {
        return list(body, key).stream().map(m -> m.get("code")).toList();
    }

    @Test
    @DisplayName("yaygın DNS: görünen 4 izlemenin 3'ü DNS → DNS_WIDE (+ aynı takım); başka takımın satırı hiçbir alanda yok")
    void dnsWideScopedAndHidden() {
        long anchor = row("2026-11-05T10:00:00", "HTTP", 1L, "ERROR").team(teamA).target("https://a.example.com/x")
                .reason("DNS_NXDOMAIN").save(jdbc);
        row("2026-11-05T10:00:30", "HTTP", 2L, "ERROR").team(teamA).target("https://b.example.com/").reason("DNS_SERVFAIL").save(jdbc);
        row("2026-11-05T09:59:10", "KEYWORD", 3L, "TIMEOUT").team(teamA).target("https://c.example.com/").reason("DNS").save(jdbc);
        row("2026-11-05T10:01:00", "PORT", 4L, "ERROR").team(teamA).target("d.example.com:443").reason("CONNECT_REFUSED").save(jdbc);
        row("2026-11-05T10:00:05", "HTTP", 5L, "ERROR").team(teamB).target("https://gizli.example.com/").reason("DNS_NXDOMAIN").save(jdbc);
        row("2026-11-05T10:00:20", "HTTP", 6L, "SUCCESS").team(teamA).save(jdbc);                                // başarılı: girmez
        row("2026-11-05T10:05:00", "HTTP", 7L, "ERROR").team(teamA).reason("DNS_NXDOMAIN").save(jdbc);           // pencere dışı
        row("2026-11-05T10:00:40", "HTTP", 8L, "ERROR").team(teamA).action("ALERT_TRIGGERED").save(jdbc);        // kontrol değil

        ActivityRelatedService.Result r = related(anchor, 120, Set.of(teamA));
        Map<String, Object> b = r.body();
        assertThat(b.get("total")).as("bu satır HARİÇ").isEqualTo(3);
        assertThat(b.get("monitors")).isEqualTo(3);
        assertThat(b.get("window_total")).isEqualTo(4);
        assertThat(b.get("anchor_in_window")).isEqualTo(true);
        assertThat(codes(b, "suspects")).containsExactly("DNS_WIDE", "SAME_TEAM");
        Map<?, ?> wide = (Map<?, ?>) list(b, "suspects").get(0).get("params");
        assertThat(wide.get("monitors")).isEqualTo(3);
        assertThat(wide.get("share_pct")).isEqualTo(75);
        Map<?, ?> sameTeam = (Map<?, ?>) list(b, "suspects").get(1).get("params");
        assertThat(sameTeam.get("team_name")).isEqualTo("Ödeme");
        assertThat(list(b, "by_team")).hasSize(1);
        assertThat(list(b, "by_team").get(0)).containsEntry("team_id", teamA).containsEntry("count", 3);
        assertThat(String.valueOf(b)).doesNotContain("gizli").doesNotContain("Gizli Takım");
        assertThat(r.items()).extracting(ActivityLog::getMonitorId).containsExactly(2L, 3L, 4L);   // yakınlığa göre, kendisi hariç
        Map<?, ?> anchorMap = (Map<?, ?>) b.get("anchor");
        assertThat(anchorMap.get("host")).isEqualTo("a.example.com");
        assertThat(anchorMap.get("reason_class")).isEqualTo("DNS");
        assertThat(list(b, "by_reason")).extracting(m -> m.get("reason"))
                .containsExactlyInAnyOrder("DNS_SERVFAIL", "DNS", "CONNECT_REFUSED");   // çapanın DNS_NXDOMAIN'i dağılımda yok
    }

    @Test
    @DisplayName("aynı host: farklı tür iki izleme aynı host'ta düşüyor → SAME_HOST; tek başına düşen → SINGLE")
    void sameHostAndSingle() {
        long a = row("2026-11-05T10:00:00", "HTTP", 1L, "ERROR").team(teamA).target("https://shop.example.com/sepet")
                .reason("HTTP_STATUS").save(jdbc);
        row("2026-11-05T10:00:10", "PORT", 2L, "ERROR").team(teamA).target("shop.example.com:443").reason("CONNECT_REFUSED").save(jdbc);
        assertThat(codes(related(a, null, Set.of(teamA)).body(), "suspects")).containsExactly("SAME_HOST");
        Map<?, ?> p = (Map<?, ?>) list(related(a, null, Set.of(teamA)).body(), "suspects").get(0).get("params");
        assertThat(p.get("host")).isEqualTo("shop.example.com");
        assertThat(p.get("monitors")).isEqualTo(2);

        long lone = row("2026-11-05T12:00:00", "PING", 9L, "TIMEOUT").team(teamA).target("10.1.1.1").reason("ICMP_NO_REPLY").save(jdbc);
        Map<String, Object> single = related(lone, null, Set.of(teamA)).body();
        assertThat(codes(single, "suspects")).containsExactly("SINGLE");
        assertThat(single.get("total")).as("'başka hatalı kontrol yok' üretilebilir").isEqualTo(0);
        assertThat(single.get("window_total")).isEqualTo(1);
        assertThat(list(single, "by_reason")).isEmpty();
    }

    @Test
    @DisplayName("olgular: 40 gün sonra ilk hata (en az), 24 sa hata / zaman aşımı sayıları, son başarı, toparlanma süresi")
    void factsRecovered() {
        // 30 gün geriye bakış: önceki hata yok → en eski satıra göre "en az N gün"
        row("2026-10-10T10:00:00", "HTTP", 1L, "SUCCESS").team(teamA).save(jdbc);
        row("2026-11-05T09:50:00", "HTTP", 1L, "SUCCESS").team(teamA).save(jdbc);
        row("2026-11-05T09:55:00", "HTTP", 1L, "TIMEOUT").team(teamA).reason("READ_TIMEOUT").save(jdbc);
        long anchor = row("2026-11-05T10:00:00", "HTTP", 1L, "ERROR").team(teamA).reason("HTTP_STATUS").save(jdbc);
        row("2026-11-05T10:10:00", "HTTP", 1L, "SUCCESS").team(teamA).save(jdbc);
        row("2026-11-05T10:15:00", "HTTP", 1L, "ERROR").team(teamB).save(jdbc);          // başka takım: olgulara girmez
        List<Map<String, Object>> facts = list(related(anchor, 60, Set.of(teamA)).body(), "facts");
        assertThat(facts).extracting(m -> m.get("code"))
                .containsExactly("FIRST_FAILURE_IN_DAYS", "FAILURES_24H", "TIMEOUTS_24H", "LAST_SUCCESS", "RECOVERED_AFTER");
        Map<?, ?> first = (Map<?, ?>) facts.get(0).get("params");
        assertThat(first.get("days")).isEqualTo(25L);
        assertThat(first.get("at_least")).isEqualTo(true);
        assertThat(((Map<?, ?>) facts.get(1).get("params")).get("n")).isEqualTo(2L);
        assertThat(((Map<?, ?>) facts.get(2).get("params")).get("n")).isEqualTo(1L);
        assertThat(((Map<?, ?>) facts.get(3).get("params")).get("at")).isEqualTo("2026-11-05T09:50:00");
        Map<?, ?> rec = (Map<?, ?>) facts.get(4).get("params");
        assertThat(rec.get("seconds")).isEqualTo(900L);       // seri başı 09:55 → 10:10
        assertThat(rec.get("at")).isEqualTo("2026-11-05T10:10:00");
    }

    @Test
    @DisplayName("olgular: önceki hata 3 gün önce → FIRST_FAILURE_IN_DAYS{3, at_least=false}; toparlanmadı → STILL_FAILING")
    void factsStillFailing() {
        long mon = ping(teamA, true);
        row("2026-11-02T08:00:00", "PING", mon, "ERROR").team(teamA).save(jdbc);
        row("2026-11-02T09:00:00", "PING", mon, "SUCCESS").team(teamA).save(jdbc);
        long anchor = row("2026-11-05T10:00:00", "PING", mon, "ERROR").team(teamA).save(jdbc);
        row("2026-11-05T10:05:00", "PING", mon, "ERROR").team(teamA).save(jdbc);
        List<Map<String, Object>> facts = list(related(anchor, null, Set.of(teamA)).body(), "facts");
        assertThat(facts).extracting(m -> m.get("code"))
                .containsExactly("FIRST_FAILURE_IN_DAYS", "FAILURES_24H", "LAST_SUCCESS", "STILL_FAILING");
        Map<?, ?> first = (Map<?, ?>) facts.get(0).get("params");
        assertThat(first.get("days")).isEqualTo(3L);
        assertThat(first.get("at_least")).isEqualTo(false);
        assertThat(((Map<?, ?>) facts.get(3).get("params")).get("since")).isEqualTo("2026-11-05T10:00:00");
    }

    @Test
    @DisplayName("eski satır: neden yok → class:<hata sınıfı> anahtarı ve sınıfı; kimliksiz sertifika satırı olguları hedefle bulur")
    void legacyRow() {
        row("2026-11-05T09:00:00", "CERT", null, "SUCCESS").team(teamA).target("x.example.com").save(jdbc);
        long anchor = row("2026-11-05T10:00:00", "CERT", null, "ERROR").team(teamA).target("x.example.com").errorClass("NETWORK").save(jdbc);
        row("2026-11-05T10:00:30", "CERT", null, "ERROR").team(teamA).target("y.example.com").errorClass("NETWORK").save(jdbc);
        Map<String, Object> b = related(anchor, null, Set.of(teamA)).body();
        Map<?, ?> a = (Map<?, ?>) b.get("anchor");
        assertThat(a.get("reason")).isEqualTo("class:NETWORK");
        assertThat(a.get("reason_class")).isEqualTo("NETWORK");
        assertThat(a.get("failure_reason")).isNull();
        assertThat(list(b, "by_reason").get(0)).containsEntry("reason", "class:NETWORK").containsEntry("count", 1);
        // Hata avı D3: kimliksiz eski satırın izlemesi doğrulanamaz → "hâlâ hata veriyor" denmez (son başarı söylenir)
        assertThat(list(b, "facts")).extracting(m -> m.get("code")).contains("LAST_SUCCESS").doesNotContain("STILL_FAILING");
    }

    @Test
    @DisplayName("inceleme O3: total çapa HARİÇ — başarılı çapa pencerede değil (anchor_in_window=false), hatalı çapa sayılmaz")
    void totalExcludesAnchor() {
        long ok = row("2026-11-05T10:00:00", "HTTP", 1L, "SUCCESS").team(teamA).save(jdbc);
        row("2026-11-05T10:00:10", "HTTP", 2L, "ERROR").team(teamA).reason("DNS_NXDOMAIN").save(jdbc);
        row("2026-11-05T10:00:20", "HTTP", 3L, "ERROR").team(teamA).reason("DNS_NXDOMAIN").save(jdbc);
        Map<String, Object> b = related(ok, null, Set.of(teamA)).body();
        assertThat(b.get("total")).isEqualTo(2);
        assertThat(b.get("window_total")).isEqualTo(2);
        assertThat(b.get("anchor_in_window")).isEqualTo(false);
        long failing = row("2026-11-05T10:00:15", "HTTP", 1L, "ERROR").team(teamA).reason("DNS_NXDOMAIN").save(jdbc);
        Map<String, Object> c = related(failing, null, Set.of(teamA)).body();
        assertThat(c.get("total")).isEqualTo(2);
        assertThat(c.get("window_total")).isEqualTo(3);
        assertThat(c.get("anchor_in_window")).isEqualTo(true);
        assertThat(list(c, "by_reason").get(0)).containsEntry("reason", "DNS_NXDOMAIN").containsEntry("count", 2);
    }

    @Test
    @DisplayName("kapı: görülemeyen satır / bilinmeyen kimlik → 404; pencere 30–900 dışı → 400")
    void gateAndWindow() {
        long hidden = row("2026-11-05T10:00:00", "HTTP", 1L, "ERROR").team(teamB).save(jdbc);
        assertThatThrownBy(() -> related(hidden, null, Set.of(teamA))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> related(99_999_999L, null, Set.of(teamA))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> related(hidden, 29, Set.of(teamB))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> related(hidden, 901, Set.of(teamB))).isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("sorgu sayısı sabit: satır + pencere + olgular (+ seri başı) + takım adları ≤ 5; satır sayısından bağımsız")
    void constantQueryCount() {
        long anchor = row("2026-11-05T10:00:00", "HTTP", 1L, "ERROR").team(teamA).reason("DNS_NXDOMAIN").save(jdbc);
        counting.reset();
        related(anchor, null, Set.of(teamA));
        int few = counting.reset();
        for (int i = 0; i < 40; i++) {
            row("2026-11-05T10:00:" + String.format("%02d", i), "HTTP", 100L + i, "ERROR").team(teamA).reason("DNS_SERVFAIL").save(jdbc);
        }
        counting.reset();
        related(anchor, null, Set.of(teamA));
        // satır + pencere + olgular + seri başı + takım adları + (hâlâ hata adayı) izleme künyesi
        assertThat(counting.reset()).isEqualTo(few).isLessThanOrEqualTo(7);
    }

    @Test
    @DisplayName("Hata avı O1: aynı anda 503 dönen 5 HTTP izlemesi (WARNING, HTTP_STATUS) başarısız sayılır — pencere, aynı host, "
            + "aynı takım, olgular; sayfa hızı / sorgu ihlali / NO_CHECKS uyarısı girmez")
    void httpWarningChecksAreFailing() {
        long a = http(teamA, "https://shop.example.com/sepet");
        long b = http(teamA, "https://shop.example.com/odeme");
        long c = http(teamA, "https://shop.example.com/");
        row("2026-11-05T09:58:00", "HTTP", a, "SUCCESS").team(teamA).target("https://shop.example.com/sepet").save(jdbc);
        long anchor = row("2026-11-05T10:00:00", "HTTP", a, "WARNING").team(teamA).target("https://shop.example.com/sepet")
                .reason("HTTP_STATUS").http(503).save(jdbc);
        row("2026-11-05T10:00:10", "HTTP", b, "WARNING").team(teamA).target("https://shop.example.com/odeme").reason("HTTP_STATUS").http(503).save(jdbc);
        row("2026-11-05T10:00:20", "HTTP", c, "WARNING").team(teamA).target("https://shop.example.com/").reason("HTTP_STATUS").http(503).save(jdbc);
        row("2026-11-05T10:00:30", "KEYWORD", 41L, "WARNING").team(teamA).target("https://kampanya.example.com/").reason("HTTP_STATUS").http(503).save(jdbc);
        row("2026-11-05T10:00:40", "HTTP", 42L, "WARNING").team(teamA).target("https://blog.example.com/").reason("BODY_ASSERTION").http(200).save(jdbc);
        row("2026-11-05T10:00:50", "SCRIPTED", 43L, "WARNING").team(teamA).target("Sepet akışı").reason("SCRIPT_CHECK_FAILED").save(jdbc);
        // Uyarı kalanlar (zaman çizelgesinde de kesinti değil): girmez
        row("2026-11-05T10:00:15", "SCRIPTED", 44L, "WARNING").team(teamA).target("Boş senaryo").reason("SCRIPT_NO_CHECKS").save(jdbc);
        row("2026-11-05T10:00:25", "SCRIPTED", 45L, "WARNING").team(teamA).target("Eski boş").summary("NO_CHECKS · 0✓/0✗").save(jdbc);
        row("2026-11-05T10:00:35", "PAGESPEED", 46L, "WARNING").team(teamA).target("https://shop.example.com/").save(jdbc);
        row("2026-11-05T10:00:45", "DBQUERY", 47L, "WARNING").team(teamA).target("Bekleyen siparişler").save(jdbc);
        row("2026-11-05T10:00:55", "HTTP", 48L, "WARNING").team(teamA).action("ALERT_TRIGGERED").save(jdbc);   // kontrol değil
        row("2026-11-05T10:01:00", "HTTP", a, "WARNING").team(teamA).target("https://shop.example.com/sepet").reason("HTTP_STATUS").http(503).save(jdbc);

        Map<String, Object> body = related(anchor, 120, Set.of(teamA)).body();
        assertThat(body.get("total")).as("çapa HARİÇ başarısız kontroller").isEqualTo(6);
        assertThat(body.get("window_total")).isEqualTo(7);
        assertThat(body.get("anchor_in_window")).isEqualTo(true);
        assertThat(((Map<?, ?>) body.get("anchor")).get("failing")).isEqualTo(true);
        assertThat(codes(body, "suspects")).contains("SAME_HOST", "SAME_TEAM").doesNotContain("SINGLE");
        Map<?, ?> host = (Map<?, ?>) list(body, "suspects").stream().filter(s -> "SAME_HOST".equals(s.get("code"))).findFirst()
                .orElseThrow().get("params");
        assertThat(host.get("host")).isEqualTo("shop.example.com");
        assertThat(host.get("monitors")).isEqualTo(3);   // pagespeed uyarısı aynı host'ta ama sayılmaz
        assertThat(list(body, "facts")).extracting(m -> m.get("code"))
                .containsExactly("FAILURES_24H", "LAST_SUCCESS", "STILL_FAILING");
        assertThat(((Map<?, ?>) list(body, "facts").get(0).get("params")).get("n")).isEqualTo(1L);   // çapa dahil, sonrası değil
    }

    @Test
    @DisplayName("Hata avı O1: kesinti kuralının SQL eşi Java kuralıyla AYNI satırları seçer (doğruluk tablosu, H2)")
    void outageRuleSqlEqualsJava() {
        String[][] cases = {
                // tür, eylem, durum, neden, özet
                {"HTTP", "SCHEDULED_CHECK", "WARNING", "HTTP_STATUS", "503 · 40ms"},
                {"HTTP", "MANUAL_CHECK", "WARNING", null, null},
                {"KEYWORD", "SCHEDULED_CHECK", "WARNING", "KEYWORD_FOUND_FORBIDDEN", "200"},
                {"SCRIPTED", "SCHEDULED_CHECK", "WARNING", "SCRIPT_CHECK_FAILED", "FAIL · 1✓/2✗"},
                {"SCRIPTED", "SCHEDULED_CHECK", "WARNING", null, "FAIL · 1✓/2✗"},
                {"SCRIPTED", "SCHEDULED_CHECK", "WARNING", null, null},
                {"SCRIPTED", "SCHEDULED_CHECK", "WARNING", "SCRIPT_NO_CHECKS", "NO_CHECKS"},
                {"SCRIPTED", "SCHEDULED_CHECK", "WARNING", null, "NO_CHECKS · 0✓/0✗"},
                {"DBQUERY", "SCHEDULED_CHECK", "WARNING", "DB_RULE", null},
                {"PAGE", "SCHEDULED_CHECK", "WARNING", null, "2 kırık"},
                {"PAGESPEED", "SCHEDULED_CHECK", "WARNING", null, null},
                {"DOMAIN", "SCHEDULED_CHECK", "WARNING", null, "20d"},
                {"PING", "SCHEDULED_CHECK", "ERROR", "ICMP_NO_REPLY", null},
                {"PORT", "MANUAL_CHECK", "TIMEOUT", null, null},
                {"PING", "SCHEDULED_CHECK", "UNKNOWN", "ICMP_UNAVAILABLE", null},
                {"HTTP", "SCHEDULED_CHECK", "SUCCESS", null, "200"},
        };
        List<Long> javaIds = new ArrayList<>();
        int i = 0;
        for (String[] c : cases) {
            long id = row("2026-11-05T10:00:" + String.format("%02d", i++), c[0], 100L + i, c[2]).team(teamA).action(c[1])
                    .reason(c[3]).summary(c[4]).save(jdbc);
            if (ActivityTimelineService.isOutage(c[0], c[1], c[2], c[3], c[4])) javaIds.add(id);
        }
        List<Long> sqlIds = jdbc.queryForList("SELECT id FROM activity_log WHERE " + ActivityTimelineService.OUTAGE_SQL
                + " AND action IN ('SCHEDULED_CHECK', 'MANUAL_CHECK') ORDER BY id", Long.class);
        assertThat(sqlIds).containsExactlyElementsOf(javaIds);
        assertThat(javaIds).hasSize(8);   // HTTP / keyword WARNING (3) + senaryo FAIL (3) + ERROR + TIMEOUT
    }

    @Test
    @DisplayName("Hata avı D3: \"hâlâ hata veriyor\" yalnız izleme VAR, ETKİN, görülebilir ve pencere bugüne uzanırken")
    void stillFailingNeedsLiveVisibleMonitor() {
        long mon = ping(teamA, true);
        row("2026-11-05T09:00:00", "PING", mon, "SUCCESS").team(teamA).save(jdbc);
        long anchor = row("2026-11-05T10:00:00", "PING", mon, "ERROR").team(teamA).reason("ICMP_NO_REPLY").save(jdbc);
        assertThat(list(related(anchor, null, Set.of(teamA)).body(), "facts")).extracting(m -> m.get("code")).contains("STILL_FAILING");

        // duraklatıldı (sonrasında satır yok)
        PingMonitor m = pings.findById(mon).orElseThrow();
        m.setActive(false);
        pings.save(m);
        assertThat(list(related(anchor, null, Set.of(teamA)).body(), "facts")).extracting(f -> f.get("code"))
                .contains("LAST_SUCCESS").doesNotContain("STILL_FAILING");

        // görülemeyen takıma taşındı (sonraki satırlar kapsam dışı)
        m.setActive(true);
        m.setTeamId(teamB);
        pings.save(m);
        row("2026-11-05T10:05:00", "PING", mon, "SUCCESS").team(teamB).save(jdbc);
        assertThat(list(related(anchor, null, Set.of(teamA)).body(), "facts")).extracting(f -> f.get("code")).doesNotContain("STILL_FAILING");

        // silindi
        pings.deleteById(mon);
        assertThat(list(related(anchor, null, Set.of(teamA)).body(), "facts")).extracting(f -> f.get("code")).doesNotContain("STILL_FAILING");

        // etkin ve görülebilir ama çapa bakış penceresinden eski (sonraki satırlar okunmadı): söylenmez
        long mon2 = ping(teamA, true);
        long old = row("2026-11-05T10:00:00", "PING", mon2, "ERROR").team(teamA).save(jdbc);
        svc.clock = () -> Instant.parse("2026-12-20T00:00:00Z");
        assertThat(list(related(old, null, Set.of(teamA)).body(), "facts")).extracting(f -> f.get("code")).doesNotContain("STILL_FAILING");
        svc.clock = () -> Instant.parse("2026-11-05T12:00:00Z");
        assertThat(list(related(old, null, Set.of(teamA)).body(), "facts")).extracting(f -> f.get("code")).contains("STILL_FAILING");

        // kural: saf fonksiyon çağıranın kararını uygular
        assertThat(ActivityRelatedService.buildFacts(true, "2026-11-05T10:00:00", 1, 0, null, null, false, null, null, null))
                .extracting(f -> f.get("code")).doesNotContain("STILL_FAILING");
    }

    @Test
    @DisplayName("Hata avı D4: bulunamayan / görülemeyen satır kendi 404 koduyla (eski pod'un NOT_FOUND'undan ayırt edilir)")
    void notFoundCarriesOwnCode() {
        assertThatThrownBy(() -> related(99_999_999L, null, Set.of(teamA)))
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException.class)
                .extracting(e -> ((com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException) e).getCode())
                .isEqualTo("ACTIVITY_NOT_FOUND");
    }

    @Test
    @DisplayName("kod listeleri sabit (ön yüz i18n anahtarları bunlardan)")
    void codeLists() {
        assertThat(ActivityRelatedService.SUSPECT_CODES)
                .containsExactly("DNS_WIDE", "NETWORK_WIDE", "TLS_WIDE", "PROXY_WIDE", "SAME_HOST", "SAME_TEAM", "SINGLE");
        assertThat(ActivityRelatedService.FACT_CODES).containsExactly("FIRST_FAILURE_IN_DAYS", "FAILURES_24H",
                "TIMEOUTS_24H", "LAST_SUCCESS", "RECOVERED_AFTER", "STILL_FAILING");
    }
}

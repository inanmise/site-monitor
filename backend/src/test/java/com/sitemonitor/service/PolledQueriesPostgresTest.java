package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.it.SwallowedSqlErrors;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.AlertStormMember;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DnsRecord;
import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.HttpCheck;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.KeywordResult;
import com.sitemonitor.model.PageCheck;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedCheck;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PingCheck;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortCheck;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.ScriptedCheck;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormMemberRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpCheckRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.KeywordResultRepository;
import com.sitemonitor.repository.PageCheckRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedCheckRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PingCheckRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortCheckRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedCheckRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UptimeCheckRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yoklanan (30 sn'de bir çağrılan) ekranların ağır sorguları GERÇEK PostgreSQL'de (2026-10-02, onaylı öneri 28).
 *
 * <p>İzleme Panosu ({@code LATERAL} en-son kontrol + pencere sayımı, 9 tür), Durum Sayfası olay izdüşümleri, Alarm
 * Gürültüsü, Fırtına durum/geçmiş/analiz, menü rozetleri ve "bugün" paneli. Sorgular iki katmanda koşar:
 * <ol>
 *   <li>repository metodu DOĞRUDAN (hata yutulmaz, sonuç kendi tohum satırlarımızla doğrulanır — {@code SELECT c.*}
 *       yerel sorgularda kolon eşlemesi ancak satır varken denenir, o yüzden her türe satır tohumlanır);</li>
 *   <li>servis girişi — servisler her sorguyu try/catch ile sarıp boş sonuçla devam ettiği için {@link SwallowedSqlErrors}
 *       yutulan SQL hatalarını yakalar.</li>
 * </ol>
 */
@PostgresIntegration
class PolledQueriesPostgresTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @FunctionalInterface
    private interface Stats { List<Object[]> run(Collection<Long> ids, String from, String to); }

    /** Tür başına tohum: izleme kimliği + en-son / pencere sorguları. */
    private record Probe(String type, long monitorId, Supplier<List<?>> latest, Function<Object, Long> latestMonitorId,
                         Stats stats) {}

    private static final List<Probe> PROBES = new ArrayList<>();
    private static String tag;
    private static long teamId;
    private static String teamName;
    private static long openHttpAlertId;
    private static long openPortAlertId;
    private static long activeIncidentId;
    private static long resolvedIncidentId;
    private static long stormId;

    private static <T> T bean(Class<T> type) { return PostgresIt.app().bean(type); }

    private static String iso(Instant i) { return ISO.format(i); }

    @BeforeAll
    static void seed() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        String t1 = iso(now.minus(20, ChronoUnit.MINUTES));
        String t2 = iso(now.minus(5, ChronoUnit.MINUTES));

        Team team = new Team();
        team.setName("IT Takım " + tag);
        team.setActive(true);
        team = bean(TeamRepository.class).save(team);
        teamId = team.getId();
        teamName = team.getName();

        // ── 9 izleme türü: izleme + iki kontrol (biri başarılı, biri başarısız) ──
        HttpMonitor http = new HttpMonitor();
        http.setName("it-http-" + tag); http.setUrl("https://it-" + tag + ".invalid/"); http.setTeamId(teamId); http.setActive(true);
        long httpId = bean(HttpMonitorRepository.class).save(http).getId();
        for (String at : List.of(t1, t2)) {
            HttpCheck c = new HttpCheck(); c.setMonitorId(httpId); c.setOk(at.equals(t1)); c.setResponseMs(120L); c.setHttpStatus(200);
            c.setCheckedAt(at); bean(HttpCheckRepository.class).save(c);
        }
        HttpCheckRepository httpChecks = bean(HttpCheckRepository.class);
        PROBES.add(new Probe("http", httpId, httpChecks::findLatestPerMonitor, o -> ((HttpCheck) o).getMonitorId(),
                httpChecks::weeklyStatsByMonitor));

        PingMonitor ping = new PingMonitor();
        ping.setName("it-ping-" + tag); ping.setHost("it-" + tag + ".invalid"); ping.setTeamId(teamId); ping.setActive(true);
        long pingId = bean(PingMonitorRepository.class).save(ping).getId();
        for (String at : List.of(t1, t2)) {
            PingCheck c = new PingCheck(); c.setMonitorId(pingId); c.setUp(at.equals(t1)); c.setRttMs(12L); c.setCheckedAt(at);
            bean(PingCheckRepository.class).save(c);
        }
        PingCheckRepository pingChecks = bean(PingCheckRepository.class);
        PROBES.add(new Probe("ping", pingId, pingChecks::findLatestPerMonitor, o -> ((PingCheck) o).getMonitorId(),
                pingChecks::weeklyStatsByMonitor));

        PortMonitor port = new PortMonitor();
        port.setName("it-port-" + tag); port.setHost("it-" + tag + ".invalid"); port.setPort(443); port.setProtocol("TCP");
        port.setTeamId(teamId); port.setActive(true); port.setStandalone(true);
        long portId = bean(PortMonitorRepository.class).save(port).getId();
        for (String at : List.of(t1, t2)) {
            PortCheck c = new PortCheck(); c.setMonitorId(portId); c.setOpen(at.equals(t1)); c.setResponseMs(30L); c.setCheckedAt(at);
            bean(PortCheckRepository.class).save(c);
        }
        PortCheckRepository portChecks = bean(PortCheckRepository.class);
        PROBES.add(new Probe("port", portId, portChecks::findLatestPerMonitor, o -> ((PortCheck) o).getMonitorId(),
                portChecks::weeklyStatsByMonitor));

        DnsMonitor dns = new DnsMonitor();
        dns.setName("it-dns-" + tag); dns.setDomain("it-" + tag + ".invalid"); dns.setRecordType("A");
        dns.setTeamId(teamId); dns.setActive(true); dns.setStandalone(true);
        long dnsId = bean(DnsMonitorRepository.class).save(dns).getId();
        for (String at : List.of(t1, t2)) {
            DnsRecord r = new DnsRecord(); r.setMonitorId(dnsId); r.setRecordType("A"); r.setValue("192.0.2.10");
            r.setChanged(false); r.setManual(false); r.setResponseMs(8L); r.setCheckedAt(at);
            bean(DnsRecordRepository.class).save(r);
        }
        DnsRecordRepository dnsRecords = bean(DnsRecordRepository.class);
        PROBES.add(new Probe("dns", dnsId, dnsRecords::findLatestPerMonitor, o -> ((DnsRecord) o).getMonitorId(),
                dnsRecords::weeklyStatsByMonitor));

        DomainMonitor domain = new DomainMonitor();
        domain.setName("it-domain-" + tag); domain.setDomain("it-" + tag + ".invalid"); domain.setTeamId(teamId); domain.setActive(true);
        long domainId = bean(DomainMonitorRepository.class).save(domain).getId();
        for (String at : List.of(t1, t2)) {
            DomainCheck c = new DomainCheck(); c.setMonitorId(domainId); c.setStatus("OK"); c.setDaysRemaining(200);
            c.setManual(false); c.setCheckedAt(at); bean(DomainCheckRepository.class).save(c);
        }
        DomainCheckRepository domainChecks = bean(DomainCheckRepository.class);
        PROBES.add(new Probe("domain", domainId, domainChecks::findLatestPerMonitor, o -> ((DomainCheck) o).getMonitorId(),
                domainChecks::weeklyStatsByMonitor));

        KeywordMonitor kw = new KeywordMonitor();
        kw.setName("it-keyword-" + tag); kw.setUrl("https://it-" + tag + ".invalid/"); kw.setKeyword("ok");
        kw.setTeamId(teamId); kw.setActive(true);
        long kwId = bean(KeywordMonitorRepository.class).save(kw).getId();
        for (String at : List.of(t1, t2)) {
            KeywordResult r = new KeywordResult(); r.setMonitorId(kwId); r.setFound(true); r.setOk(at.equals(t1));
            r.setResponseMs(90L); r.setCheckedAt(at); bean(KeywordResultRepository.class).save(r);
        }
        KeywordResultRepository kwResults = bean(KeywordResultRepository.class);
        PROBES.add(new Probe("keyword", kwId, kwResults::findLatestPerMonitor, o -> ((KeywordResult) o).getMonitorId(),
                kwResults::weeklyStatsByMonitor));

        PageMonitor page = new PageMonitor();
        page.setName("it-page-" + tag); page.setUrl("https://it-" + tag + ".invalid/"); page.setTeamId(teamId); page.setActive(true);
        long pageId = bean(PageMonitorRepository.class).save(page).getId();
        for (String at : List.of(t1, t2)) {
            PageCheck c = new PageCheck(); c.setMonitorId(pageId); c.setOk(at.equals(t1)); c.setResponseMs(400L);
            c.setCheckedAt(at); bean(PageCheckRepository.class).save(c);
        }
        PageCheckRepository pageChecks = bean(PageCheckRepository.class);
        PROBES.add(new Probe("page", pageId, pageChecks::findLatestPerMonitor, o -> ((PageCheck) o).getMonitorId(),
                pageChecks::weeklyStatsByMonitor));

        PageSpeedMonitor ps = new PageSpeedMonitor();
        ps.setName("it-pagespeed-" + tag); ps.setUrl("https://it-" + tag + ".invalid/"); ps.setTeamId(teamId); ps.setActive(true);
        long psId = bean(PageSpeedMonitorRepository.class).save(ps).getId();
        for (String at : List.of(t1, t2)) {
            PageSpeedCheck c = new PageSpeedCheck(); c.setMonitorId(psId); c.setOk(at.equals(t1)); c.setResponseMs(800);
            c.setCapped(false); c.setCheckedAt(at); bean(PageSpeedCheckRepository.class).save(c);
        }
        PageSpeedCheckRepository psChecks = bean(PageSpeedCheckRepository.class);
        PROBES.add(new Probe("pagespeed", psId, psChecks::findLatestPerMonitor, o -> ((PageSpeedCheck) o).getMonitorId(),
                psChecks::weeklyStatsByMonitor));

        ScriptedMonitor sc = new ScriptedMonitor();
        sc.setName("it-scripted-" + tag); sc.setScript("export default function () {}"); sc.setTeamId(teamId); sc.setActive(true);
        long scId = bean(ScriptedMonitorRepository.class).save(sc).getId();
        for (String at : List.of(t1, t2)) {
            ScriptedCheck c = new ScriptedCheck(); c.setMonitorId(scId); c.setOk(at.equals(t1)); c.setStatus(at.equals(t1) ? "OK" : "FAIL");
            c.setDurationMs(1500L); c.setManual(false); c.setCheckedAt(at); bean(ScriptedCheckRepository.class).save(c);
        }
        ScriptedCheckRepository scChecks = bean(ScriptedCheckRepository.class);
        PROBES.add(new Probe("scripted", scId, scChecks::findLatestPerMonitor, o -> ((ScriptedCheck) o).getMonitorId(),
                scChecks::weeklyStatsByMonitor));

        // ── Alarmlar: açık HTTP (fırtına üyesi), açık + sahiplenilmiş PORT, kurtarılarak kapanmış PING ──
        AlertEventRepository alerts = bean(AlertEventRepository.class);
        AlertEvent httpDown = alert("it-" + tag + ".invalid", EscalationService.TYPE_HTTP_DOWN, "CRITICAL", t1);
        AlertEvent portDown = alert("it-" + tag + ".invalid", EscalationService.TYPE_PORT_DOWN, "HIGH", t2);
        portDown.setAcknowledged(true); portDown.setAcknowledgedBy("IT_USER"); portDown.setAcknowledgedAt(t2);
        AlertEvent pingRecovered = alert("it-" + tag + ".invalid", EscalationService.TYPE_PING_DOWN, "HIGH", t1);
        pingRecovered.setResolved(true); pingRecovered.setResolvedAt(t2); pingRecovered.setResolvedSilently(false);
        openHttpAlertId = alerts.save(httpDown).getId();
        openPortAlertId = alerts.save(portDown).getId();
        alerts.save(pingRecovered);

        // ── Açık takım fırtınası + üyeliği ──
        AlertStorm storm = new AlertStorm();
        storm.setScopeKey("TEAM:" + teamId); storm.setScopeType("TEAM"); storm.setResolved(false); storm.setMemberCount(1);
        storm.setRootCause(EscalationService.TYPE_HTTP_DOWN); storm.setCreatedAt(t1); storm.setLastMemberAt(t1);
        storm.setTeamId(teamId); storm.setThresholdUnit("COUNT"); storm.setThresholdValue(3); storm.setThresholdEffective(3);
        storm.setWindowMinutes(5); storm.setQuietMinutes(5); storm.setTargetsAtOpen(1); storm.setPeakTargets(1);
        storm.setTriggerEventId(openHttpAlertId);
        stormId = bean(AlertStormRepository.class).save(storm).getId();
        PostgresIt.app().jdbc().update("UPDATE alert_events SET storm_id = ? WHERE id = ?", stormId, openHttpAlertId);
        PostgresIt.app().jdbc().update(StormService.SQL_MEMBER_INSERT, stormId, openHttpAlertId, t1, AlertStormMember.JOIN_TRIGGER);

        // ── Olaylar: biri açık, biri çözülmüş ──
        IncidentRecordRepository incidents = bean(IncidentRecordRepository.class);
        IncidentRecord active = incident("IT açık olay " + tag, "OPEN", t1);
        active.setTeamId(teamId); active.setTeamName(teamName);
        activeIncidentId = incidents.save(active).getId();
        IncidentRecord resolved = incident("IT çözülmüş olay " + tag, "RESOLVED", t1);
        resolved.setResolvedAt(t2); resolved.setDurationMinutes(15); resolved.setTeamId(teamId); resolved.setTeamName(teamName);
        resolvedIncidentId = incidents.save(resolved).getId();
    }

    private static AlertEvent alert(String domain, String type, String level, String createdAt) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain); e.setAlertType(type); e.setAlertLevel(level); e.setMessage("postgres-it " + tag);
        e.setTeamId(teamId); e.setAcknowledged(false); e.setResolved(false); e.setCreatedAt(createdAt);
        return e;
    }

    private static IncidentRecord incident(String title, String status, String occurredAt) {
        IncidentRecord i = new IncidentRecord();
        i.setTitle(title); i.setStatus(status); i.setSeverity("HIGH"); i.setCategory("OTHER");
        i.setOccurredAt(occurredAt); i.setSlaBreached(false); i.setCreatedAt(occurredAt);
        return i;
    }

    private static String windowFrom() { return iso(Instant.now().minus(24, ChronoUnit.HOURS)); }

    private static String windowTo() { return iso(Instant.now().plus(1, ChronoUnit.MINUTES)); }

    // ── Ağın kendisi (pozitif kontrol) ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Pozitif kontrol: yutulan JdbcTemplate hatası ve Hibernate JDBC hatası SwallowedSqlErrors'a düşer")
    void swallowedSqlNet_catchesBothJdbcTemplateAndHibernateFailures() {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PolledQueriesPostgresTest.class);   // com.sitemonitor.*
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            try {
                PostgresIt.app().jdbc().queryForList("SELECT no_such_column_it FROM alert_events");
            } catch (Exception e) {
                log.debug("deneme sorgusu okunamadı: {}", e.toString());   // servislerin yutma deseni
            }
            jakarta.persistence.EntityManager em = bean(jakarta.persistence.EntityManagerFactory.class).createEntityManager();
            try {
                em.createNativeQuery("SELECT no_such_column_it FROM alert_events").getResultList();
            } catch (Exception ignored) {
                // Hibernate hatayı kendi kategorisinde (org.hibernate.orm.jdbc.error) zaten günlükler
            } finally {
                em.close();
            }
            List<String> errors = sql.errors();
            assertThat(errors).as("yutulan JdbcTemplate hatası (DEBUG)").anyMatch(s -> s.contains("bad SQL grammar"));
            assertThat(errors).as("Hibernate JDBC hata günlüğü").anyMatch(s -> s.contains("org.hibernate.orm.jdbc.error"));
        }
    }

    // ── İzleme Panosu ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("İzleme Panosu: 9 türün LATERAL en-son kontrolü + pencere sayımı tohum satırlarını doğru döndürür")
    void monitoringOverview_latestAndWindowQueries_returnSeededRows() {
        for (Probe p : PROBES) {
            List<?> latest = p.latest().get();
            List<Long> latestIds = latest.stream().map(p.latestMonitorId()).toList();
            assertThat(latestIds).as("%s en-son kontrol (LATERAL) tohum izlemesini içermeli", p.type()).contains(p.monitorId());
            assertThat(latestIds.stream().filter(id -> id == p.monitorId()).count())
                    .as("%s izleme başına TEK en-son satır", p.type()).isEqualTo(1);

            List<Object[]> stats = p.stats().run(List.of(p.monitorId()), windowFrom(), windowTo());
            assertThat(stats).as("%s pencere sayımı", p.type()).hasSize(1);
            assertThat(((Number) stats.get(0)[0]).longValue()).isEqualTo(p.monitorId());
            assertThat(((Number) stats.get(0)[1]).longValue()).as("%s pencere toplamı", p.type()).isEqualTo(2);
        }
        // Envanter türevi uptime en-son (LATERAL certificate_inventory) — boş envanterde de sözdizimi/plan koşar.
        assertThat(bean(UptimeCheckRepository.class).findLatestPerDomainPort()).isNotNull();
        assertThat(bean(CertificateInventoryRepository.class).findActiveDomains()).isNotNull();
    }

    @Test
    @DisplayName("Kart 'son alarm' şeridi (2026-10-09): envanter-sınırlı LATERAL sorgu aktif alanın EN SON alarmını döner; pasif / envanter dışı alan yok")
    void latestAlertForActiveInventoryDomains_returnsLatestPerActiveDomain() {
        String act = "it-card-act-" + tag + ".example.test";
        String off = "it-card-off-" + tag + ".example.test";
        String stray = "it-card-stray-" + tag + ".example.test";
        com.sitemonitor.model.CertificateInventory a = new com.sitemonitor.model.CertificateInventory();
        a.setDomain(act); a.setActive(true);
        com.sitemonitor.model.CertificateInventory o = new com.sitemonitor.model.CertificateInventory();
        o.setDomain(off); o.setActive(false);
        bean(CertificateInventoryRepository.class).save(a);
        bean(CertificateInventoryRepository.class).save(o);
        AlertEventRepository repo = bean(AlertEventRepository.class);
        String t1 = iso(java.time.Instant.now().minus(20, ChronoUnit.MINUTES));
        String t2 = iso(java.time.Instant.now().minus(5, ChronoUnit.MINUTES));
        repo.save(alert(act, "EXPIRY", "WARNING", t1));
        long latest = repo.save(alert(act, "HTTP_DOWN", "HIGH", t2)).getId();
        repo.save(alert(off, "EXPIRY", "WARNING", t2));
        repo.save(alert(stray, "EXPIRY", "WARNING", t2));

        Map<String, Long> byDomain = new java.util.HashMap<>();
        for (AlertEvent e : repo.findLatestForActiveInventoryDomains()) {
            if (e.getDomain() != null && e.getDomain().contains(tag)) byDomain.put(e.getDomain(), e.getId());
        }
        assertThat(byDomain).as("yalnız aktif envanter alanı, en son alarmıyla").containsExactly(java.util.Map.entry(act, latest));
        assertThat(repo.findLatestPerDomain().stream().filter(e -> act.equals(e.getDomain())).map(AlertEvent::getId).toList())
                .as("eski tüm-geçmiş sorgusuyla aynı karar").containsExactly(latest);
    }

    @Test
    @DisplayName("İzleme Panosu servisi PostgreSQL'de yutulan SQL hatası üretmez ve tohum izlemelerini sayar")
    void monitoringOverviewService_hasNoSwallowedSqlErrors() {
        Map<String, Object> out;
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            out = bean(MonitoringOverviewService.class).build(t -> true, true, 24);
            assertThat(sql.errors()).as("İzleme Panosu'nun yuttuğu SQL hataları").isEmpty();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("monitors");
        List<Object> names = rows.stream().map(r -> r.get("name")).toList();
        for (Probe p : PROBES) assertThat(names).as("panodaki %s satırı", p.type()).contains("it-" + p.type() + "-" + tag);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) out.get("totals");
        assertThat(((Number) totals.get("checks_window")).longValue()).isGreaterThanOrEqualTo(18);
        assertThat(((Number) totals.get("open_alerts")).longValue()).isGreaterThanOrEqualTo(1);
    }

    // ── Alarm sorguları (menü rozetleri, gürültü, pano) ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Açık alarm sorguları: önem sıralı liste, rozet sayımı (kapsamlı EXISTS), özet kalemleri, kurtarılan sayımı")
    void alertQueries_onPostgres() {
        AlertEventRepository repo = bean(AlertEventRepository.class);
        assertThat(repo.findAllOpenOrderBySeverity()).extracting(AlertEvent::getId).contains(openHttpAlertId, openPortAlertId);

        List<Object[]> recovered = repo.countRecoveredSinceByTypeAndTeam(windowFrom());
        assertThat(recovered).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo(EscalationService.TYPE_PING_DOWN);
            assertThat(((Number) r[1]).longValue()).isEqualTo(teamId);
        });

        assertThat(repo.countOpenByTypeLevelAck(false, List.of(-1L))).isNotEmpty();
        long scoped = repo.countOpenByTypeLevelAck(true, List.of(teamId)).stream()
                .mapToLong(r -> ((Number) r[3]).longValue()).sum();
        assertThat(scoped).as("takım kapsamlı açık alarm sayısı").isEqualTo(2);

        List<Object[]> items = repo.findOpenSummaryItems(List.of(EscalationService.TYPE_HTTP_DOWN, EscalationService.TYPE_PORT_DOWN),
                true, List.of(teamId), PageRequest.of(0, 10));
        assertThat(items).extracting(r -> ((Number) r[0]).longValue()).contains(openHttpAlertId, openPortAlertId);
        assertThat(items).allSatisfy(r -> assertThat(r[8]).as("LEFT JOIN takım adı").isEqualTo(teamName));

        assertThat(repo.findNoiseRowsSince(windowFrom())).hasSizeGreaterThanOrEqualTo(3);
        assertThat(repo.findNoiseSlotRowsSince(windowFrom())).hasSizeGreaterThanOrEqualTo(3);

        Map<String, Object> badges;
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            badges = bean(OpenAlertsSummaryService.class).build(false, List.of(teamId));
            assertThat(sql.errors()).as("menü rozeti özetinin yuttuğu SQL hataları").isEmpty();
        }
        assertThat(((Number) badges.get("total")).longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("Alarm Gürültüsü (pencere, takım süzgeci, ısı haritası hücresi) yutulan SQL hatası üretmez")
    void alertNoise_hasNoSwallowedSqlErrors() {
        AlertNoiseService noise = bean(AlertNoiseService.class);
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            Map<String, Object> all = noise.build(30, t -> true);
            Map<String, Object> team = noise.build(30, teamId, t -> true);
            Map<String, Object> slot = noise.slot(30, null, t -> true, 1, 10);
            assertThat(sql.errors()).as("gürültü analizinin yuttuğu SQL hataları").isEmpty();
            assertThat(all).containsKeys("top", "heat", "series");
            assertThat(team).isNotNull();
            assertThat(slot).isNotNull();
        }
    }

    // ── Durum Sayfası ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Durum Sayfası olay izdüşümleri (aktif / çözülen + sayımlar) ve servis girişi PostgreSQL'de")
    void statusPage_incidentProjections_onPostgres() {
        IncidentRecordRepository repo = bean(IncidentRecordRepository.class);
        assertThat(repo.statusPageActive(PageRequest.of(0, 50))).extracting(r -> ((Number) r[0]).longValue())
                .contains(activeIncidentId).doesNotContain(resolvedIncidentId);
        assertThat(repo.statusPageActiveCounts()).isNotEmpty();
        assertThat(repo.statusPageResolvedSince(windowFrom(), PageRequest.of(0, 50))).extracting(r -> ((Number) r[0]).longValue())
                .contains(resolvedIncidentId);
        assertThat(repo.statusPageResolvedSinceCounts(windowFrom())).isNotEmpty();

        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            Map<String, Object> view = bean(StatusPageService.class).view("postgres-it-" + tag,
                    StatusPageService.Viewer.all(), true);
            assertThat(sql.errors()).as("Durum Sayfası'nın yuttuğu SQL hataları").isEmpty();
            assertThat(view).isNotEmpty();
        }
    }

    // ── Fırtına durumu ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Fırtına durum / geçmiş / ayrıntı / analiz: repository izdüşümleri ve servisler PostgreSQL'de")
    void stormStatus_onPostgres() {
        AlertStormRepository storms = bean(AlertStormRepository.class);
        assertThat(storms.lastStormPerTeam(windowFrom())).anySatisfy(r ->
                assertThat(((Number) r[0]).longValue()).isEqualTo(teamId));
        assertThat(storms.liveMembers(List.of(stormId))).extracting(r -> ((Number) r[1]).longValue()).containsExactly(openHttpAlertId);
        assertThat(storms.findHistory(true, List.of(-1L), null, null, false, PageRequest.of(0, 20)).getContent())
                .extracting(AlertStorm::getId).contains(stormId);
        assertThat(storms.findHistory(false, List.of(teamId), windowFrom(), windowTo(), false, PageRequest.of(0, 20))
                .getContent()).extracting(AlertStorm::getId).containsExactly(stormId);
        assertThat(bean(AlertStormMemberRepository.class).countByStorms(List.of(stormId))).isNotEmpty();

        StormStatusService svc = bean(StormStatusService.class);
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            Map<String, Object> status = svc.status(t -> true, true);
            Map<String, Object> history = svc.history(t -> true, true, List.of(), null, null, null, false, 0, 20);
            Map<String, Object> detail = svc.detail(stormId, t -> true, true);
            Map<String, Object> analytics = svc.analytics(t -> true, true, null, 30);
            assertThat(sql.errors()).as("fırtına ekranlarının yuttuğu SQL hataları").isEmpty();
            assertThat(status).isNotEmpty();
            assertThat(history).isNotEmpty();
            assertThat(detail).isNotNull();
            assertThat(analytics).isNotEmpty();
        }
    }

    // ── "Sizin için — bugün" ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("'Sizin için — bugün' paneli yutulan SQL hatası üretmez")
    void todayPanel_hasNoSwallowedSqlErrors() {
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            Map<String, Object> out = bean(TodayPanelService.class).build(t -> true, List.of(teamId));
            assertThat(sql.errors()).as("bugün panelinin yuttuğu SQL hataları").isEmpty();
            assertThat(out).isNotEmpty();
        }
    }
}

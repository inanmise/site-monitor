package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.HttpCheck;
import com.sitemonitor.model.HttpMonitor;
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
import com.sitemonitor.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * İZLEME PANOSU (2026-09-30, kullanıcı isteği): İzleme menüsündeki 9 izleme türünün TEK ekranda durumu — tür başına
 * izleme sayıları (toplam / aktif / duraklatılmış / silinmiş), şu an düşük / eski (kontrolü gecikmiş) / hiç kontrol
 * edilmemiş izlemeler, son kontrol zamanı, pencere içi (varsayılan 24 sa) koşum ve başarı sayıları, açık ve pencerede
 * çözülen alarmlar; ayrıca izleme başına satır (ad, hedef, takım, durum, son kontrol, açık alarm) — sayfa süzer/sıralar.
 *
 * <p>Kapsam: {@code canViewTeam} yüklemi (İzleme sayfalarının kendi listeleriyle AYNI: satırın takımı görüş
 * kapsamında olmalı); alarmlar hedef anahtarıyla izlemelere BAĞLANIR (alarm olayının {@code domain}'i sweep anahtarıdır:
 * HTTP/İçerik/Sayfa/Sayfa Hızı → url, Ping/Port → host, DNS/Alan adı → domain, Sentetik → ad) — kapsam izlemelerden
 * miras kalır. Yalnızca okuma; mevcut sorgular (son kontrol haritası + gruplu pencere sayımı) — tür başına 3 sorgu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringOverviewService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** "Eski" (kontrolü gecikmiş) eşiği: aralığın bu katı geçtiyse. */
    static final int STALE_FACTOR = 3;
    /** Aralığı olmayan izlemede eski eşiği (sn). */
    static final long STALE_FALLBACK_SECONDS = 3 * 3600;

    private final HttpMonitorRepository httpMonitorRepo;
    private final PingMonitorRepository pingMonitorRepo;
    private final PortMonitorRepository portMonitorRepo;
    private final DnsMonitorRepository dnsMonitorRepo;
    private final KeywordMonitorRepository keywordMonitorRepo;
    private final PageMonitorRepository pageMonitorRepo;
    private final PageSpeedMonitorRepository pageSpeedMonitorRepo;
    private final ScriptedMonitorRepository scriptedMonitorRepo;
    private final DomainMonitorRepository domainMonitorRepo;

    private final HttpCheckRepository httpCheckRepo;
    private final PingCheckRepository pingCheckRepo;
    private final PortCheckRepository portCheckRepo;
    private final DnsRecordRepository dnsRecordRepo;
    private final KeywordResultRepository keywordResultRepo;
    private final PageCheckRepository pageCheckRepo;
    private final PageSpeedCheckRepository pageSpeedCheckRepo;
    private final ScriptedCheckRepository scriptedCheckRepo;
    private final DomainCheckRepository domainCheckRepo;

    private final AlertEventRepository alertEventRepo;
    private final TeamRepository teamRepo;

    /** Bir izleme türünün panodaki tanımı — satır ve son-kontrol okuyucuları. */
    private record TypeSpec<M, C>(String type,
                                 java.util.function.Supplier<List<M>> monitors,
                                 Function<M, Long> id, Function<M, String> name, Function<M, String> target,
                                 Function<M, Long> team, Function<M, Boolean> active, Function<M, Boolean> deleted,
                                 Function<M, Integer> interval,
                                 java.util.function.Supplier<List<C>> latest, Function<C, Long> checkMonitorId,
                                 Function<C, Boolean> ok, Function<C, String> checkedAt, Function<C, Object> responseMs,
                                 Function<C, String> error,
                                 StatsQuery stats) {}

    @FunctionalInterface
    private interface StatsQuery { List<Object[]> run(Collection<Long> ids, String from, String to); }

    /** Sayfa satırı ve tür özeti — {@code Map} olarak (Jackson snake_case ile doğrudan yanıt). */
    @Transactional(readOnly = true)
    public Map<String, Object> build(Predicate<Long> canViewTeam, boolean seesAllAlerts, int hours) {
        int h = Math.max(1, Math.min(hours, 24 * 30));
        Instant nowI = Instant.now();
        String now = ISO.format(nowI);
        String from = ISO.format(nowI.minus(h, ChronoUnit.HOURS));

        Map<Long, String> teamNames = new HashMap<>();
        try { for (Team t : teamRepo.findAll()) if (t.getId() != null) teamNames.put(t.getId(), t.getName()); }
        catch (Exception e) { log.debug("takım adları okunamadı: {}", e.toString()); }

        // Açık alarmlar — hedef anahtarına göre (izlemeye bağlanır; kapsam izlemeden miras). Tür süzgeci katalogdan.
        Map<String, List<AlertEvent>> openByKey = new HashMap<>();
        try {
            for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
                if (e.getDomain() == null) continue;
                String family = MonitorTypeCatalog.typeOfAlert(e.getAlertType());
                if (family == null || "cert".equals(family)) continue;
                openByKey.computeIfAbsent(family + "|" + e.getDomain().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(e);
            }
        } catch (Exception e) { log.debug("açık alarmlar okunamadı: {}", e.toString()); }

        List<Map<String, Object>> types = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        types.add(summarize(httpSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(pingSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(portSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(dnsSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(domainSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(keywordSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(pageSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(pageSpeedSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));
        types.add(summarize(scriptedSpec(), canViewTeam, teamNames, openByKey, from, now, nowI, rows));

        // Pencerede çözülen alarmlar (tür başına) — kapsam: alarm satırının takımı görüş kapsamında (global → hepsi).
        Map<String, Long> resolvedByType = new HashMap<>();
        try {
            for (AlertEvent e : alertEventRepo.findByResolvedAtGreaterThanEqual(from)) {
                if (!Boolean.TRUE.equals(e.getResolved()) || Boolean.TRUE.equals(e.getResolvedSilently())) continue;
                if (!seesAllAlerts && !(e.getTeamId() != null && canViewTeam.test(e.getTeamId()))) continue;
                String family = MonitorTypeCatalog.typeOfAlert(e.getAlertType());
                if (family == null) continue;
                resolvedByType.merge(family, 1L, Long::sum);
            }
        } catch (Exception e) { log.debug("çözülen alarmlar okunamadı: {}", e.toString()); }
        for (Map<String, Object> t : types) t.put("resolved_window", resolvedByType.getOrDefault(String.valueOf(t.get("type")), 0L));

        Map<String, Object> totals = new LinkedHashMap<>();
        for (String k : List.of("total", "active", "paused", "deleted", "down", "stale", "unknown", "checks_window",
                "failed_window", "open_alerts", "open_critical", "resolved_window")) {
            long sum = 0;
            for (Map<String, Object> t : types) sum += ((Number) t.getOrDefault(k, 0L)).longValue();
            totals.put(k, sum);
        }
        String lastChecked = null;
        for (Map<String, Object> t : types) {
            Object v = t.get("last_checked_at");
            if (v instanceof String s && (lastChecked == null || s.compareTo(lastChecked) > 0)) lastChecked = s;
        }
        totals.put("last_checked_at", lastChecked);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated_at", now);
        out.put("window_hours", h);
        out.put("totals", totals);
        out.put("types", types);
        out.put("monitors", rows);
        return out;
    }

    private <M, C> Map<String, Object> summarize(TypeSpec<M, C> spec, Predicate<Long> canViewTeam, Map<Long, String> teamNames,
                                                 Map<String, List<AlertEvent>> openByKey, String from, String now, Instant nowI,
                                                 List<Map<String, Object>> rows) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", spec.type());
        long total = 0, active = 0, paused = 0, deleted = 0, down = 0, stale = 0, unknown = 0, openAlerts = 0, openCritical = 0;
        long checks = 0, failed = 0;
        String lastChecked = null;

        List<M> monitors;
        try { monitors = spec.monitors().get(); } catch (Exception e) { log.warn("{} izlemeleri okunamadı: {}", spec.type(), e.toString()); monitors = List.of(); }
        List<M> visible = new ArrayList<>();
        for (M m : monitors) {
            if (m == null) continue;
            Long team = spec.team().apply(m);
            if (!canViewTeam.test(team)) continue;
            visible.add(m);
        }
        Map<Long, C> latest = new HashMap<>();
        try {
            for (C c : spec.latest().get()) { Long id = spec.checkMonitorId().apply(c); if (id != null) latest.putIfAbsent(id, c); }
        } catch (Exception e) { log.debug("{} son kontrolleri okunamadı: {}", spec.type(), e.toString()); }

        List<Long> ids = new ArrayList<>();
        for (M m : visible) if (!Boolean.TRUE.equals(spec.deleted().apply(m))) ids.add(spec.id().apply(m));
        Map<Long, long[]> stats = new HashMap<>();
        if (!ids.isEmpty()) {
            try {
                for (Object[] r : spec.stats().run(ids, from, now)) {
                    if (r == null || r.length < 3 || r[0] == null) continue;
                    long tot = r[1] instanceof Number n ? n.longValue() : 0L;
                    long ok = r[2] instanceof Number n ? n.longValue() : 0L;
                    stats.put(((Number) r[0]).longValue(), new long[]{ tot, ok });
                }
            } catch (Exception e) { log.debug("{} pencere sayımı okunamadı: {}", spec.type(), e.toString()); }
        }

        for (M m : visible) {
            Long id = spec.id().apply(m);
            boolean isDeleted = Boolean.TRUE.equals(spec.deleted().apply(m));
            boolean isActive = Boolean.TRUE.equals(spec.active().apply(m));
            String target = spec.target().apply(m);
            C c = latest.get(id);
            String checkedAt = c != null ? spec.checkedAt().apply(c) : null;
            Boolean ok = c != null ? spec.ok().apply(c) : null;
            List<AlertEvent> open = target == null ? List.of()
                    : openByKey.getOrDefault(spec.type() + "|" + target.toLowerCase(Locale.ROOT), List.of());
            String openLevel = null;
            for (AlertEvent e : open) if (rank(e.getAlertLevel()) > rank(openLevel)) openLevel = e.getAlertLevel();

            String status;
            if (isDeleted) status = "deleted";
            else if (!isActive) status = "paused";
            else if (c == null) status = "unknown";
            else if (!open.isEmpty() || Boolean.FALSE.equals(ok)) status = "down";
            else if (isStale(checkedAt, spec.interval().apply(m), nowI)) status = "stale";
            else status = "up";

            total++;
            if (isDeleted) deleted++;
            else if (!isActive) paused++;
            else active++;
            if ("down".equals(status)) down++;
            if ("stale".equals(status)) stale++;
            if ("unknown".equals(status)) unknown++;
            if (!isDeleted) { openAlerts += open.size(); for (AlertEvent e : open) if ("CRITICAL".equalsIgnoreCase(e.getAlertLevel())) openCritical++; }
            long[] st = stats.get(id);
            long rowChecks = st != null ? st[0] : 0L, rowFailed = st != null ? Math.max(0, st[0] - st[1]) : 0L;
            checks += rowChecks; failed += rowFailed;
            if (checkedAt != null && (lastChecked == null || checkedAt.compareTo(lastChecked) > 0)) lastChecked = checkedAt;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", spec.type());
            row.put("id", id);
            row.put("name", spec.name().apply(m));
            row.put("target", target);
            Long team = spec.team().apply(m);
            row.put("team_id", team);
            row.put("team_name", team != null ? teamNames.get(team) : null);
            row.put("active", isActive);
            row.put("deleted", isDeleted);
            row.put("status", status);
            row.put("last_checked_at", checkedAt);
            row.put("last_ok", ok);
            row.put("response_ms", c != null ? spec.responseMs().apply(c) : null);
            row.put("last_error", c != null ? spec.error().apply(c) : null);
            row.put("interval_seconds", spec.interval().apply(m));
            row.put("open_alerts", open.size());
            row.put("open_alert_level", openLevel);
            row.put("checks_window", rowChecks);
            row.put("failed_window", rowFailed);
            rows.add(row);
        }

        t.put("total", total); t.put("active", active); t.put("paused", paused); t.put("deleted", deleted);
        t.put("down", down); t.put("stale", stale); t.put("unknown", unknown);
        t.put("checks_window", checks); t.put("failed_window", failed);
        t.put("success_rate_window", checks > 0 ? Math.round((checks - failed) * 1000.0 / checks) / 10.0 : null);
        t.put("open_alerts", openAlerts); t.put("open_critical", openCritical);
        t.put("last_checked_at", lastChecked);
        return t;
    }

    static boolean isStale(String checkedAt, Integer intervalSeconds, Instant now) {
        if (checkedAt == null) return false;
        try {
            Instant at = java.time.LocalDateTime.parse(checkedAt, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")).toInstant(ZoneOffset.UTC);
            long limit = intervalSeconds != null && intervalSeconds > 0 ? (long) intervalSeconds * STALE_FACTOR : STALE_FALLBACK_SECONDS;
            return at.plusSeconds(Math.max(limit, 600)).isBefore(now);
        } catch (Exception e) {
            return false;
        }
    }

    private static int rank(String level) {
        if (level == null) return 0;
        return switch (level.toUpperCase(Locale.ROOT)) { case "CRITICAL" -> 3; case "HIGH" -> 2; case "WARNING" -> 1; default -> 0; };
    }

    private static Long asLong(Object v) { return v instanceof Number n ? n.longValue() : null; }

    // ── Tür tanımları ─────────────────────────────────────────────────────────

    private TypeSpec<HttpMonitor, HttpCheck> httpSpec() {
        return new TypeSpec<>("http", httpMonitorRepo::findAllByOrderByNameAsc,
                HttpMonitor::getId, m -> nz(m.getName(), m.getUrl()), HttpMonitor::getUrl, HttpMonitor::getTeamId,
                HttpMonitor::getActive, m -> false, HttpMonitor::getIntervalSeconds,
                httpCheckRepo::findLatestPerMonitor, HttpCheck::getMonitorId,
                c -> c.getError() == null && Boolean.TRUE.equals(c.getOk()), HttpCheck::getCheckedAt, HttpCheck::getResponseMs, HttpCheck::getError,
                httpCheckRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<PingMonitor, PingCheck> pingSpec() {
        return new TypeSpec<>("ping", pingMonitorRepo::findAllByOrderByNameAsc,
                PingMonitor::getId, m -> nz(m.getName(), m.getHost()), PingMonitor::getHost, PingMonitor::getTeamId,
                PingMonitor::getActive, m -> false, PingMonitor::getIntervalSeconds,
                pingCheckRepo::findLatestPerMonitor, PingCheck::getMonitorId,
                c -> Boolean.TRUE.equals(c.getUp()), PingCheck::getCheckedAt, PingCheck::getRttMs, PingCheck::getError,
                pingCheckRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<PortMonitor, PortCheck> portSpec() {
        return new TypeSpec<>("port", portMonitorRepo::findAllByOrderByNameAsc,
                PortMonitor::getId, m -> nz(m.getName(), m.getHost() + ":" + m.getPort()), PortMonitor::getHost, PortMonitor::getTeamId,
                PortMonitor::getActive, m -> m.getDeletedAt() != null, PortMonitor::getIntervalSeconds,
                portCheckRepo::findLatestPerMonitor, PortCheck::getMonitorId,
                c -> Boolean.TRUE.equals(c.getOpen()), PortCheck::getCheckedAt, PortCheck::getResponseMs, PortCheck::getError,
                portCheckRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<DnsMonitor, com.sitemonitor.model.DnsRecord> dnsSpec() {
        return new TypeSpec<>("dns", dnsMonitorRepo::findAllByOrderByNameAsc,
                DnsMonitor::getId, m -> nz(m.getName(), m.getDomain()), DnsMonitor::getDomain, DnsMonitor::getTeamId,
                DnsMonitor::getActive, m -> m.getDeletedAt() != null, DnsMonitor::getIntervalSeconds,
                dnsRecordRepo::findLatestPerMonitor, com.sitemonitor.model.DnsRecord::getMonitorId,
                c -> c.getValue() != null && !c.getValue().isBlank(), com.sitemonitor.model.DnsRecord::getCheckedAt,
                com.sitemonitor.model.DnsRecord::getResponseMs, c -> null,
                dnsRecordRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<DomainMonitor, DomainCheck> domainSpec() {
        return new TypeSpec<>("domain", domainMonitorRepo::findAllByOrderByNameAsc,
                DomainMonitor::getId, m -> nz(m.getName(), m.getDomain()), DomainMonitor::getDomain, DomainMonitor::getTeamId,
                DomainMonitor::getActive, m -> false, DomainMonitor::getIntervalSeconds,
                domainCheckRepo::findLatestPerMonitor, DomainCheck::getMonitorId,
                c -> c.getError() == null && !"UNKNOWN".equalsIgnoreCase(c.getStatus()), DomainCheck::getCheckedAt, c -> null, DomainCheck::getError,
                domainCheckRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<KeywordMonitor, KeywordResult> keywordSpec() {
        return new TypeSpec<>("keyword", keywordMonitorRepo::findAllByOrderByNameAsc,
                KeywordMonitor::getId, m -> nz(m.getName(), m.getUrl()), KeywordMonitor::getUrl, KeywordMonitor::getTeamId,
                KeywordMonitor::getActive, m -> false, KeywordMonitor::getIntervalSeconds,
                keywordResultRepo::findLatestPerMonitor, KeywordResult::getMonitorId,
                c -> c.getError() == null && Boolean.TRUE.equals(c.getOk()), KeywordResult::getCheckedAt, KeywordResult::getResponseMs, KeywordResult::getError,
                keywordResultRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<PageMonitor, PageCheck> pageSpec() {
        return new TypeSpec<>("page", pageMonitorRepo::findAllByOrderByNameAsc,
                PageMonitor::getId, m -> nz(m.getName(), m.getUrl()), PageMonitor::getUrl, PageMonitor::getTeamId,
                PageMonitor::getActive, m -> false, PageMonitor::getIntervalSeconds,
                pageCheckRepo::findLatestPerMonitor, PageCheck::getMonitorId,
                c -> c.getError() == null && Boolean.TRUE.equals(c.getOk()), PageCheck::getCheckedAt, PageCheck::getResponseMs, PageCheck::getError,
                pageCheckRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<PageSpeedMonitor, PageSpeedCheck> pageSpeedSpec() {
        return new TypeSpec<>("pagespeed", pageSpeedMonitorRepo::findAllByOrderByNameAsc,
                PageSpeedMonitor::getId, m -> nz(m.getName(), m.getUrl()), PageSpeedMonitor::getUrl, PageSpeedMonitor::getTeamId,
                PageSpeedMonitor::getActive, m -> false, PageSpeedMonitor::getIntervalSeconds,
                pageSpeedCheckRepo::findLatestPerMonitor, PageSpeedCheck::getMonitorId,
                c -> Boolean.TRUE.equals(c.getOk()), PageSpeedCheck::getCheckedAt, PageSpeedCheck::getResponseMs, c -> null,
                pageSpeedCheckRepo::weeklyStatsByMonitor);
    }

    private TypeSpec<ScriptedMonitor, ScriptedCheck> scriptedSpec() {
        return new TypeSpec<>("scripted", scriptedMonitorRepo::findAllByOrderByNameAsc,
                ScriptedMonitor::getId, ScriptedMonitor::getName, ScriptedMonitor::getName, ScriptedMonitor::getTeamId,
                ScriptedMonitor::getActive, m -> false, ScriptedMonitor::getIntervalSeconds,
                scriptedCheckRepo::findLatestPerMonitor, ScriptedCheck::getMonitorId,
                c -> Boolean.TRUE.equals(c.getOk()), ScriptedCheck::getCheckedAt, ScriptedCheck::getDurationMs, ScriptedCheck::getError,
                scriptedCheckRepo::weeklyStatsByMonitor);
    }

    private static String nz(String a, String b) { return a != null && !a.isBlank() ? a : b; }
}

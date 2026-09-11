package com.sitemonitor.service;

import com.sitemonitor.model.DeploymentHistory;
import com.sitemonitor.repository.DeploymentHistoryRepository;
import com.sitemonitor.util.Msg;
import com.sitemonitor.util.Semver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Dağıtım geçmişi — kayıt, geçiş türetimi, yayın↔dağıtım birleşimi (K2/K5/K8, 2026-09-10).
 *
 * <p>Türetim TEK yerde ({@link #deriveKinds}): ortam bazında {@code started_at,id} sıralı kayıtlarda
 * önceki farklı sürümden sonra gelen ilk satır = geçiş (UPGRADE/ROLLBACK), aynı sürüm = RESTART,
 * sürümsüz (BACKFILL) = UNKNOWN ve öncekini ilerletmez (tahmin yok). Ayrıştırılamayan sürüm CHANGED.
 * Kayıt yolu best-effort: hiçbir hata açılışı/kapanışı düşürmez.
 */
@Slf4j
@Service
public class DeploymentHistoryService {

    public enum Kind { FIRST_SEEN, UPGRADE, RESTART, ROLLBACK, CHANGED, UNKNOWN }

    /** Türetilmiş satır: kayıt + tür + önceki sürüm. */
    public record Derived(DeploymentHistory row, Kind kind, String previousVersion) {}

    /** Ortamda koşan sürüm ve o sürümün canlıya geçiş anı. */
    public record Current(String version, String liveSince, Kind liveKind, String previousVersion,
                          int restartsSince, String lastStartedAt, Long rowId) {}

    /** Sürüm geçişi olayı (E3 mail/push dinler). */
    public record TransitionEvent(Kind kind, String environment, String fromVersion, String toVersion, DeploymentHistory row) {}

    public record ManualRequest(String environment, String version, String startedAt, String note, String commit, Integer helmRevision) {}

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern ENV_RE = Pattern.compile("^[a-z0-9-]{1,40}$");
    public static final int MAX_PAGE = 100;
    public static final int CSV_MAX_ROWS = 5000;

    private final DeploymentHistoryRepository repo;
    private final BuildInfo buildInfo;
    private final ReleaseIndexService releaseIndex;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final boolean enabled;

    private volatile Long currentId;

    public DeploymentHistoryService(DeploymentHistoryRepository repo, BuildInfo buildInfo, ReleaseIndexService releaseIndex,
                                    JdbcTemplate jdbc, ApplicationEventPublisher events, Environment env) {
        this.repo = repo;
        this.buildInfo = buildInfo;
        this.releaseIndex = releaseIndex;
        this.jdbc = jdbc;
        this.events = events;
        this.enabled = env.getProperty("site.monitor.deploy.history.enabled", Boolean.class, true);
    }

    // ── Yaşam döngüsü kancaları (SchedulerService.runOnStartup / heartbeat / ShutdownLogger) ─────

    /** Açılış kaydı — applySchemaPatches SONRASI çağrılır (tablo hazır). Hata yutulur. */
    public void recordStartup() {
        if (!enabled) { log.info("Deployment history disabled (site.monitor.deploy.history.enabled=false)"); return; }
        try {
            BuildInfo.Snapshot b = buildInfo.get();
            DeploymentHistory d = new DeploymentHistory();
            d.setStartedAt(b.jvmStartedAt());
            d.setRecordedAt(now());
            d.setEnvironment(b.environment());
            d.setVersion(blankToNull(b.version()));
            d.setImageVersion(blankToNull(b.imageVersion()));
            d.setGitCommit(blankToNull(b.commit()));
            d.setBuildTime(blankToNull(b.buildTime()));
            d.setImageRef(blankToNull(b.imageRef()));
            d.setHelmRelease(blankToNull(b.helmRelease()));
            d.setHelmRevision(b.helmRevision());
            d.setHelmChartVersion(blankToNull(b.helmChartVersion()));
            d.setConfigChecksum(blankToNull(b.configChecksum()));
            d.setInstanceId(b.instanceId());
            d.setHostname(b.hostname());
            d.setPodName(blankToNull(b.podName()));
            d.setNodeName(blankToNull(b.nodeName()));
            d.setJavaVersion(blankToNull(b.javaVersion()));
            d.setLastSeenAt(d.getRecordedAt());
            d.setSource(DeploymentHistory.SOURCE_STARTUP);
            d.setCreatedBy("SYSTEM");
            DeploymentHistory saved = repo.save(d);
            currentId = saved.getId();
            Derived mine = deriveKinds(repo.findByEnvironmentOrderByStartedAtAscIdAsc(b.environment())).stream()
                    .filter(x -> Objects.equals(x.row().getId(), saved.getId())).findFirst().orElse(null);
            Kind kind = mine != null ? mine.kind() : Kind.FIRST_SEEN;
            log.info("Deployment recorded: env={} version={} kind={} prev={} commit={} helmRev={} id={}",
                    b.environment(), b.version(), kind, mine != null ? mine.previousVersion() : null,
                    b.commitShort(), b.helmRevision(), saved.getId());
            if (kind == Kind.UPGRADE || kind == Kind.ROLLBACK || kind == Kind.CHANGED) {
                try {
                    events.publishEvent(new TransitionEvent(kind, b.environment(), mine.previousVersion(), b.version(), saved));
                } catch (Exception e) {
                    log.warn("Deployment transition listeners failed: {}", e.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Deployment history startup record failed (continuing): {}", e.toString());
        }
    }

    public void markReady() {
        Long id = currentId;
        if (id == null) return;
        try { repo.markReady(id, now()); } catch (Exception e) { log.debug("markReady failed: {}", e.toString()); }
    }

    /** Heartbeat tick'inden (60 sn) — hard-kill'de ended_at boş kalır, ekran son görülmeyi kullanır. */
    public void touch() {
        Long id = currentId;
        if (id == null) return;
        try { repo.touch(id, now()); } catch (Exception e) { log.debug("touch failed: {}", e.toString()); }
    }

    /** graceful | crash | failed-start */
    public void recordShutdown(String reason) {
        Long id = currentId;
        if (id == null) return;
        try { repo.markEnded(id, now(), reason == null ? "unknown" : reason); }
        catch (Exception e) { log.debug("markEnded failed: {}", e.toString()); }
    }

    public Long currentId() { return currentId; }

    // ── Türetim ──────────────────────────────────────────────────────────────────────────────

    /** Ortam bazında {@code started_at,id} ARTAN sıralı satırlar → tür. Saf, test edilebilir. */
    public static List<Derived> deriveKinds(List<DeploymentHistory> rowsAsc) {
        List<Derived> out = new ArrayList<>(rowsAsc.size());
        String prev = null;
        for (DeploymentHistory r : rowsAsc) {
            String v = r.getVersion();
            if (v == null || v.isBlank()) { out.add(new Derived(r, Kind.UNKNOWN, prev)); continue; }
            Kind k;
            if (prev == null) k = Kind.FIRST_SEEN;
            else if (v.equals(prev)) k = Kind.RESTART;
            else {
                Optional<Integer> cmp = Semver.compare(v, prev);
                k = cmp.isEmpty() ? Kind.CHANGED : (cmp.get() > 0 ? Kind.UPGRADE : Kind.ROLLBACK);
            }
            out.add(new Derived(r, k, prev));
            prev = v;
        }
        return out;
    }

    public List<Derived> derivedFor(String environment) {
        return deriveKinds(repo.findByEnvironmentOrderByStartedAtAscIdAsc(environment));
    }

    /** Koşan sürüm ve canlıya geçiş anı (aynı sürümün koşusundaki ilk geçiş satırı). */
    public Current current(String environment) {
        List<Derived> all = derivedFor(environment);
        Derived last = null;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).kind() != Kind.UNKNOWN) { last = all.get(i); break; }
        }
        if (last == null) return new Current(null, null, null, null, 0, null, null);
        int restarts = 0;
        Derived transition = last;
        for (int i = all.indexOf(last); i >= 0; i--) {
            Derived d = all.get(i);
            if (d.kind() == Kind.UNKNOWN) continue;
            if (!Objects.equals(d.row().getVersion(), last.row().getVersion())) break;
            transition = d;
            if (d.kind() == Kind.RESTART) restarts++;
        }
        return new Current(last.row().getVersion(), transition.row().getStartedAt(), transition.kind(),
                transition.previousVersion(), restarts, last.row().getStartedAt(), last.row().getId());
    }

    public String currentEnvironment() { return buildInfo.get().environment(); }

    /** Zaman çizelgesi: geçişler (RESTART hariç) AZALAN + sayaçlar. */
    public Map<String, Object> timeline(String environment) {
        List<Derived> all = derivedFor(environment);
        List<Map<String, Object>> transitions = new ArrayList<>();
        int restarts = 0, unknown = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            Derived d = all.get(i);
            if (d.kind() == Kind.RESTART) { restarts++; continue; }
            if (d.kind() == Kind.UNKNOWN) unknown++;
            transitions.add(toMap(d));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("environment", environment);
        m.put("current", currentToMap(current(environment)));
        m.put("transitions", transitions);
        m.put("restartCount", restarts);
        m.put("unknownCount", unknown);
        m.put("total", all.size());
        return m;
    }

    /** Özet şeridi: son 30 gün dağıtım, ort. yayın→devreye alma, geri alma, atlanan sürüm. */
    public Map<String, Object> summary(String environment) {
        List<Derived> all = derivedFor(environment);
        String cutoff30 = ISO.format(Instant.now().minusSeconds(30L * 86400));
        String cutoff7 = ISO.format(Instant.now().minusSeconds(7L * 86400));
        int deployments30 = 0, restarts7 = 0, rollbacks = 0;
        long lagSum = 0; int lagN = 0;
        String previousVersion = null;
        for (Derived d : all) {
            boolean transition = d.kind() == Kind.UPGRADE || d.kind() == Kind.ROLLBACK || d.kind() == Kind.CHANGED || d.kind() == Kind.FIRST_SEEN;
            if (transition && d.row().getStartedAt().compareTo(cutoff30) >= 0) deployments30++;
            if (d.kind() == Kind.RESTART && d.row().getStartedAt().compareTo(cutoff7) >= 0) restarts7++;
            if (d.kind() == Kind.ROLLBACK) rollbacks++;
            if (transition && d.row().getVersion() != null) {
                Long lag = leadTimeSeconds(d.row().getVersion(), d.row().getStartedAt());
                if (lag != null && lag >= 0) { lagSum += lag; lagN++; }
                previousVersion = d.previousVersion();
            }
        }
        Set<String> deployed = new HashSet<>();
        for (Derived d : all) if (d.row().getVersion() != null) deployed.add(d.row().getVersion());
        int skipped = 0;
        for (ReleaseIndexService.Release r : releaseIndex.all()) if (!deployed.contains(r.version())) skipped++;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("environment", environment);
        m.put("current", currentToMap(current(environment)));
        m.put("previousVersion", previousVersion);
        m.put("deploymentsLast30d", deployments30);
        m.put("restartsLast7d", restarts7);
        m.put("rollbacks", rollbacks);
        m.put("avgReleaseLagSeconds", lagN == 0 ? null : lagSum / lagN);
        m.put("skippedReleases", releaseIndex.loaded() ? skipped : null);
        m.put("releaseIndex", releaseIndex.meta());
        return m;
    }

    /** yayın→devreye alma gecikmesi (sn); indekste yoksa null. */
    public Long leadTimeSeconds(String version, String startedAt) {
        if (version == null || startedAt == null) return null;
        Optional<ReleaseIndexService.Release> r = releaseIndex.find(version);
        if (r.isEmpty() || r.get().releasedAt() == null || r.get().releasedAt().isBlank()) return null;
        try {
            return Instant.parse(startedAt).getEpochSecond() - Instant.parse(r.get().releasedAt()).getEpochSecond();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Sayfalı arama + CSV ──────────────────────────────────────────────────────────────────

    /** Sayfa satırları türle zenginleştirilir; {@code kind} süzgeci sayfa öncesi ortam listesinden uygulanır. */
    public Page<DeploymentHistory> search(String env, String source, String since, String until, String q, Pageable pageable) {
        return repo.search(blank(env), blank(source), blank(since), blank(until), blank(q), pageable);
    }

    /** Sayfadaki satırlar için tür haritası (ortam başına bir türetim). */
    public Map<Long, Derived> kindsFor(List<DeploymentHistory> rows) {
        Map<Long, Derived> out = new HashMap<>();
        Set<String> envs = new HashSet<>();
        for (DeploymentHistory r : rows) envs.add(r.getEnvironment());
        for (String e : envs) for (Derived d : derivedFor(e)) out.put(d.row().getId(), d);
        return out;
    }

    public List<String> environments() {
        List<String> envs = new ArrayList<>(repo.findDistinctEnvironments());
        String mine = currentEnvironment();
        if (!envs.contains(mine)) envs.add(0, mine);
        return envs;
    }

    // ── Yayın ↔ dağıtım birleşimi ────────────────────────────────────────────────────────────

    /** Bu ortamda ilk canlıya alınma anı (BACKFILL hariç) sürüm → ISO. */
    public Map<String, String> firstLiveByVersion(String environment) {
        Map<String, String> m = new HashMap<>();
        for (DeploymentHistory r : repo.findByEnvironmentOrderByStartedAtAscIdAsc(environment)) {
            if (r.getVersion() == null || DeploymentHistory.SOURCE_BACKFILL.equals(r.getSource())) continue;
            m.putIfAbsent(r.getVersion(), r.getStartedAt());
        }
        return m;
    }

    /**
     * Yayın listesi (K5.1): {@code density=deployed} → minor/major + bu ortamda dağıtılmış her sürüm;
     * yamalar en yakın ESKİ tutulan sürümün {@code collapsedPatches} listesine katlanır.
     */
    public Map<String, Object> releases(String environment, String density, String type, String q, int page, int size) {
        Map<String, String> firstLive = firstLiveByVersion(environment);
        String needle = q == null ? "" : q.trim().toLowerCase();
        List<Map<String, Object>> kept = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (ReleaseIndexService.Release r : releaseIndex.all()) {
            if (!needle.isEmpty() && !matches(r, needle)) continue;
            if (type != null && !type.isBlank() && !"all".equals(type) && r.counts().getOrDefault(type, 0) == 0) continue;
            boolean keep = !"deployed".equals(density) || !"patch".equals(r.bump()) || firstLive.containsKey(r.version());
            if (keep) {
                Map<String, Object> m = releaseToMap(r);
                m.put("deployedHere", firstLive.get(r.version()));
                m.put("collapsedPatches", new ArrayList<>(pending));
                pending.clear();
                kept.add(m);
            } else {
                pending.add(r.version());
            }
        }
        if (!pending.isEmpty()) {
            if (kept.isEmpty()) {
                for (ReleaseIndexService.Release r : releaseIndex.all()) {
                    if (pending.contains(r.version())) { Map<String, Object> m = releaseToMap(r); m.put("deployedHere", firstLive.get(r.version())); m.put("collapsedPatches", List.of()); kept.add(m); }
                }
            } else {
                @SuppressWarnings("unchecked") List<String> last = (List<String>) kept.get(kept.size() - 1).get("collapsedPatches");
                last.addAll(pending);
            }
        }
        int total = kept.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", from < to ? kept.subList(from, to) : List.of());
        out.put("page", page);
        out.put("size", size);
        out.put("total", total);
        out.put("density", density);
        out.put("currentVersion", buildInfo.get().version());
        out.put("environment", environment);
        out.put("releaseIndex", releaseIndex.meta());
        return out;
    }

    /** E1: {@code since} sürümünden SONRA çıkan sürümler (koşan sürüme kadar), en fazla 50. */
    public List<Map<String, Object>> notesSince(String since) {
        List<Map<String, Object>> out = new ArrayList<>();
        String current = buildInfo.get().version();
        for (ReleaseIndexService.Release r : releaseIndex.all()) {
            if (Semver.compare(r.version(), current).orElse(1) > 0) continue;
            if (since != null && !since.isBlank() && Semver.compare(r.version(), since).orElse(1) <= 0) break;
            out.add(releaseToMap(r));
            if (out.size() >= 50) break;
        }
        return out;
    }

    /** Sürüm × ortam matrisi: ilk canlı anı / hiç dağıtılmadı. */
    public Map<String, Object> matrix(boolean all) {
        List<String> envs = environments();
        Map<String, Map<String, String>> byEnv = new LinkedHashMap<>();
        for (String e : envs) byEnv.put(e, firstLiveByVersion(e));
        List<Map<String, Object>> rows = new ArrayList<>();
        int cap = all ? Integer.MAX_VALUE : 200;
        for (ReleaseIndexService.Release r : releaseIndex.all()) {
            if (rows.size() >= cap) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", r.version());
            m.put("releasedAt", r.releasedAt());
            m.put("bump", r.bump());
            Map<String, String> deployedIn = new LinkedHashMap<>();
            boolean any = false;
            for (String e : envs) { String at = byEnv.get(e).get(r.version()); deployedIn.put(e, at); if (at != null) any = true; }
            m.put("deployedIn", deployedIn);
            m.put("neverDeployed", !any);
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("environments", envs);
        out.put("releases", rows);
        out.put("truncated", !all && releaseIndex.all().size() > 200);
        return out;
    }

    // ── Geri doldurma + elle kayıt ──────────────────────────────────────────────────────────

    /** Denetimdeki SCHEMA_PATCH satırları (her pod açılışında bir tane, sürümsüz) → BACKFILL. İdempotent. */
    public Map<String, Object> backfill(String actor, String environment) {
        String env = validateEnv(environment == null || environment.isBlank() ? currentEnvironment() : environment);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, event_time FROM audit_log WHERE event_type = 'SCHEMA_PATCH' AND resource_type = 'SYSTEM' ORDER BY event_time ASC, id ASC");
        int inserted = 0, skipped = 0;
        for (Map<String, Object> r : rows) {
            Long id = ((Number) r.get("id")).longValue();
            if (repo.existsByAuditRef(id)) { skipped++; continue; }
            DeploymentHistory d = new DeploymentHistory();
            d.setStartedAt(String.valueOf(r.get("event_time")));
            d.setRecordedAt(now());
            d.setEnvironment(env);
            d.setVersion(null);
            d.setSource(DeploymentHistory.SOURCE_BACKFILL);
            d.setAuditRef(id);
            d.setCreatedBy(actor);
            d.setNote("audit SCHEMA_PATCH #" + id);
            repo.save(d);
            inserted++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("environment", env);
        out.put("candidates", rows.size());
        out.put("inserted", inserted);
        out.put("skippedExisting", skipped);
        return out;
    }

    public int backfillPreview() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log a WHERE a.event_type = 'SCHEMA_PATCH' AND a.resource_type = 'SYSTEM' "
                        + "AND NOT EXISTS (SELECT 1 FROM deployment_history d WHERE d.audit_ref = a.id)", Integer.class);
        return n == null ? 0 : n;
    }

    public DeploymentHistory createManual(String actor, ManualRequest req) {
        String env = validateEnv(req.environment());
        String version = req.version() == null ? "" : req.version().trim().replaceFirst("^v", "");
        if (Semver.parse(version).isEmpty())
            throw new IllegalArgumentException(Msg.t("Sürüm X.Y.Z biçiminde olmalı", "Version must be X.Y.Z") + ": " + version);
        String startedAt = req.startedAt() == null ? "" : req.startedAt().trim();
        Instant at;
        try { at = Instant.parse(startedAt); }
        catch (Exception e) { throw new IllegalArgumentException(Msg.t("Tarih ISO-8601 UTC olmalı (örn. 2026-09-10T14:22:00Z)", "Date must be ISO-8601 UTC (e.g. 2026-09-10T14:22:00Z)")); }
        if (at.isAfter(Instant.now().plusSeconds(60)))
            throw new IllegalArgumentException(Msg.t("Gelecek tarihli dağıtım kaydı girilemez", "A deployment cannot be recorded in the future"));
        String note = req.note() == null ? "" : req.note().trim();
        if (note.isEmpty()) throw new IllegalArgumentException(Msg.t("Not zorunlu (kaynak: değişiklik kaydı, bilet vb.)", "A note is required (source: change ticket, etc.)"));
        if (note.length() > 500) throw new IllegalArgumentException(Msg.t("Not en fazla 500 karakter", "Note must be at most 500 characters"));
        DeploymentHistory d = new DeploymentHistory();
        d.setStartedAt(ISO.format(at));
        d.setRecordedAt(now());
        d.setEnvironment(env);
        d.setVersion(version);
        d.setGitCommit(blankToNull(req.commit()));
        d.setHelmRevision(req.helmRevision());
        d.setSource(DeploymentHistory.SOURCE_MANUAL);
        d.setCreatedBy(actor);
        d.setNote(note);
        return repo.save(d);
    }

    /** Yalnız MANUAL satır (K10). Diğerleri 409. */
    public DeploymentHistory deleteManual(long id) {
        DeploymentHistory d = repo.findById(id).orElseThrow(() ->
                new NoSuchElementException(Msg.t("Dağıtım kaydı bulunamadı", "Deployment record not found") + ": " + id));
        if (!DeploymentHistory.SOURCE_MANUAL.equals(d.getSource()))
            throw new IllegalStateException(Msg.t("Yalnız elle girilen kayıt silinebilir", "Only manual entries can be deleted"));
        repo.deleteManual(id);
        return d;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────

    public Map<String, Object> toMap(Derived d) {
        DeploymentHistory r = d.row();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("startedAt", r.getStartedAt());
        m.put("recordedAt", r.getRecordedAt());
        m.put("readyAt", r.getReadyAt());
        m.put("lastSeenAt", r.getLastSeenAt());
        m.put("endedAt", r.getEndedAt());
        m.put("endReason", r.getEndReason());
        m.put("environment", r.getEnvironment());
        m.put("version", r.getVersion());
        m.put("previousVersion", d.previousVersion());
        m.put("kind", d.kind().name());
        m.put("source", r.getSource());
        m.put("commit", r.getGitCommit());
        m.put("commitShort", r.getGitCommit() == null ? null : r.getGitCommit().substring(0, Math.min(8, r.getGitCommit().length())));
        m.put("buildTime", r.getBuildTime());
        m.put("imageVersion", r.getImageVersion());
        m.put("imageRef", r.getImageRef());
        Map<String, Object> helm = new LinkedHashMap<>();
        helm.put("release", r.getHelmRelease());
        helm.put("revision", r.getHelmRevision());
        helm.put("chartVersion", r.getHelmChartVersion());
        m.put("helm", helm);
        m.put("configChecksum", r.getConfigChecksum() == null ? null : r.getConfigChecksum().substring(0, Math.min(16, r.getConfigChecksum().length())));
        m.put("instanceId", r.getInstanceId());
        m.put("hostname", r.getHostname());
        m.put("pod", r.getPodName());
        m.put("node", r.getNodeName());
        m.put("javaVersion", r.getJavaVersion());
        m.put("createdBy", r.getCreatedBy());
        m.put("note", r.getNote());
        m.put("auditRef", r.getAuditRef());
        m.put("current", Objects.equals(r.getId(), currentId));
        m.put("releasedAt", r.getVersion() == null ? null : releaseIndex.find(r.getVersion()).map(ReleaseIndexService.Release::releasedAt).orElse(null));
        m.put("leadTimeSeconds", leadTimeSeconds(r.getVersion(), r.getStartedAt()));
        return m;
    }

    public Map<String, Object> currentToMap(Current c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", c.version());
        m.put("since", c.liveSince());
        m.put("kind", c.liveKind() == null ? null : c.liveKind().name());
        m.put("previousVersion", c.previousVersion());
        m.put("restartsSince", c.restartsSince());
        m.put("lastStartedAt", c.lastStartedAt());
        return m;
    }

    public static Map<String, Object> releaseToMap(ReleaseIndexService.Release r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", r.version());
        m.put("tag", r.tag());
        m.put("releasedAt", r.releasedAt());
        m.put("commit", r.commit());
        m.put("commitShort", r.commit() == null ? null : r.commit().substring(0, Math.min(8, r.commit().length())));
        m.put("prevVersion", r.prevVersion());
        m.put("bump", r.bump());
        m.put("breaking", r.breaking());
        m.put("counts", r.counts());
        List<Map<String, Object>> changes = new ArrayList<>();
        for (ReleaseIndexService.Change c : r.changes()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("type", c.type()); cm.put("scope", c.scope()); cm.put("breaking", c.breaking());
            cm.put("sha", c.sha()); cm.put("subject", c.subject());
            changes.add(cm);
        }
        m.put("changes", changes);
        m.put("truncated", r.truncated());
        m.put("omitted", r.omitted());
        return m;
    }

    private static boolean matches(ReleaseIndexService.Release r, String needle) {
        if (r.version().toLowerCase().contains(needle)) return true;
        for (ReleaseIndexService.Change c : r.changes()) {
            if (c.subject().toLowerCase().contains(needle) || (c.scope() != null && c.scope().toLowerCase().contains(needle))) return true;
        }
        return false;
    }

    static String validateEnv(String env) {
        String e = env == null ? "" : env.trim().toLowerCase();
        if (!ENV_RE.matcher(e).matches())
            throw new IllegalArgumentException(Msg.t("Ortam adı geçersiz (a-z, 0-9, '-'; en fazla 40)", "Invalid environment name (a-z, 0-9, '-'; max 40)"));
        return e;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
    private static String now() { return ISO.format(Instant.now()); }
}

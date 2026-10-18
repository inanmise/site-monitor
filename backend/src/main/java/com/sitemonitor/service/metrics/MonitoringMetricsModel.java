package com.sitemonitor.service.metrics;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.service.AlertOwnership;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.tlsgrade.TlsGradeRules;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * İzleme ölçümlerinin SAF hesabı (2026-10-18) — girdiler (Genel Bakış haritası, açık alarm satırları, sertifika listesi …)
 * → ölçüm ailesi başına satırlar. Bahar bağlamı, veritabanı ve Micrometer YOK: {@link MonitoringMetricsExporter} kaynakları
 * okur, burası satırları üretir, aktarıcı kayıt defterine yazar. Testler girdileri elle kurar.
 *
 * <p><b>Dürüstlük kuralları</b> (CLAUDE.md "Unknown is never green"):
 * <ul>
 *   <li>Okunamayan kaynak hiç satır üretmez — sahte 0 ya da "up" yok; aile yayından düşer, {@code source.up = 0}.</li>
 *   <li>Silinmiş izleme yayılmaz; envanterden çıkmış (taramanın atladığı) envanter-türevi Port/DNS satırı izleme başına
 *       yayılmaz, toplamlarda Genel Bakış gibi {@code paused} sayılır.</li>
 *   <li>Sayaç değil gauge: her pod aynı değeri yayar, pano {@code max by (etiketler)} ile birleştirir.</li>
 * </ul>
 */
public final class MonitoringMetricsModel {

    private MonitoringMetricsModel() {}

    /** Takımsız (sahipsiz) kayıtların takım etiketi; kimliği {@code "0"}. */
    public static final String NO_TEAM = "Takımsız";
    public static final String NO_TEAM_ID = "0";
    /** Etiket değeri üst sınırı (uzun ad / URL) — "…" ile biter. */
    static final int LABEL_MAX = 160;

    /** İzleme durum kodları (sitemonitor_monitor_state). */
    public static final Map<String, Integer> STATE_CODES = Map.of("up", 0, "stale", 1, "down", 2, "unknown", 3, "paused", 4);
    /** Toplamlarda her (tür, takım) için yazılan durumlar — sıfırlar dahil. */
    public static final List<String> STATES = List.of("up", "stale", "down", "unknown", "paused");
    /** Alarm seviyeleri (etiket değeri). */
    public static final List<String> LEVELS = List.of("CRITICAL", "HIGH", "WARNING", "OTHER");
    /** Sertifika durumları ve kodları (sitemonitor_certificate_status). */
    public static final List<String> CERT_STATUSES = List.of("valid", "warning", "high", "critical", "expired", "error");
    /** Bitiş kovaları (kalan gün): expired (&lt;0), d7 (0–7), d30 (8–30), d90 (31–90), later (&gt;90), unknown. */
    public static final List<String> EXPIRY_BUCKETS = List.of("expired", "d7", "d30", "d90", "later", "unknown");
    /** TLS not kovaları — {@code none} = notlanmadı. */
    public static final List<String> GRADE_BUCKETS = List.of("A+", "A", "B", "C", "D", "F", "none");

    // ── Satır ve aile kabı ────────────────────────────────────────────────────────────────────────────────

    /** Bir ölçüm satırı: etiketler (tanımdaki sırayla) + değer. */
    public record Row(Map<String, String> labels, double value) {}

    /**
     * Ölçüm adı → satırlar. Yalnız {@link MonitoringMetricNames#ALL} adları ve tam etiket kümesi kabul edilir (Prometheus
     * kayıt defteri aynı adın satırlarında aynı anahtarları ister — yanlış satır burada patlar, yayında değil).
     */
    public static final class Families {
        private final Map<String, List<Row>> rows = new LinkedHashMap<>();
        private final Map<String, Boolean> capped = new LinkedHashMap<>();

        public void add(String name, double value, String... kv) {
            MonitoringMetricNames.Def def = MonitoringMetricNames.def(name);
            if (def == null) throw new IllegalArgumentException("Bilinmeyen ölçüm: " + name);
            if (kv.length != def.labels().size() * 2) {
                throw new IllegalArgumentException(name + ": etiket sayısı " + def.labels().size() + " olmalı");
            }
            Map<String, String> labels = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                if (!def.labels().get(i / 2).equals(kv[i])) {
                    throw new IllegalArgumentException(name + ": beklenen etiket " + def.labels().get(i / 2) + ", gelen " + kv[i]);
                }
                labels.put(kv[i], label(kv[i + 1]));
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) return;   // sahte değer yazılmaz
            rows.computeIfAbsent(name, k -> new ArrayList<>()).add(new Row(labels, value));
        }

        /** Ailenin satırları ("hiç satır" = boş liste; yayında da boş kaydedilir → eski seriler silinir). */
        public List<Row> get(String name) { return rows.getOrDefault(name, List.of()); }

        public Set<String> names() { return rows.keySet(); }

        public void markCapped(String family) { capped.put(family, true); }

        public Map<String, Boolean> capped() { return capped; }

        public int size(String name) { return get(name).size(); }
    }

    // ── Takım dizini ─────────────────────────────────────────────────────────────────────────────────────

    /** Takım kimliği → ad / etkinlik. Bilinmeyen kimlik "Takım #id", null kimlik {@link #NO_TEAM}. */
    public record TeamRef(long id, String name, boolean active) {}

    public static final class TeamDirectory {
        private final Map<Long, TeamRef> byId;

        public TeamDirectory(Map<Long, TeamRef> byId) { this.byId = byId == null ? Map.of() : byId; }

        public Map<Long, TeamRef> all() { return byId; }

        public String name(Long id) {
            if (id == null || id <= 0) return NO_TEAM;
            TeamRef t = byId.get(id);
            if (t == null || t.name() == null || t.name().isBlank()) return "Takım #" + id;
            return t.name();
        }

        public static String id(Long id) { return id == null || id <= 0 ? NO_TEAM_ID : String.valueOf(id); }
    }

    // ── Takım listesi ────────────────────────────────────────────────────────────────────────────────────

    /** Takım süzgecinin kaynağı: her takım + "Takımsız" (sabit 1). */
    public static void teams(Families f, TeamDirectory teams) {
        List<TeamRef> sorted = new ArrayList<>(teams.all().values());
        sorted.sort(Comparator.comparing(t -> t.name() == null ? "" : t.name().toLowerCase(Locale.ROOT)));
        for (TeamRef t : sorted) {
            f.add(MonitoringMetricNames.TEAMS, 1, "team", teams.name(t.id()), "team_id", String.valueOf(t.id()),
                    "active", String.valueOf(t.active()));
        }
        f.add(MonitoringMetricNames.TEAMS, 1, "team", NO_TEAM, "team_id", NO_TEAM_ID, "active", "true");
    }

    // ── İzlemeler (Genel Bakış haritasından) ─────────────────────────────────────────────────────────────

    /**
     * Genel Bakış kuruluş görünümü ({@code MonitoringOverviewService.build(ALL, …)}) → izleme toplamları ve (açıksa)
     * izleme başına satırlar.
     *
     * @return okunamayan türler (o türlerin hiçbir satırı yazılmaz; aktarıcı {@code source.up{source="monitors:<tür>"}=0})
     */
    @SuppressWarnings("unchecked")
    public static Set<String> monitors(Families f, Map<String, Object> overview, TeamDirectory teams, boolean perEntity,
                                       int maxEntities) {
        Set<String> failed = new LinkedHashSet<>();
        if (overview == null) return failed;
        Set<String> knownTypes = new LinkedHashSet<>();
        if (overview.get("types") instanceof List<?> types) {
            for (Object o : types) {
                if (!(o instanceof Map<?, ?> t)) continue;
                String type = String.valueOf(t.get("type"));
                if (Boolean.TRUE.equals(t.get("unavailable")) || Boolean.TRUE.equals(t.get("checks_unavailable"))) failed.add(type);
                else knownTypes.add(type);
            }
        }
        List<Map<String, Object>> rows = overview.get("monitors") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();

        // (tür, takım) → durum sayıları; (tür, takım) → [koşum, başarısız]
        Map<String, long[]> stateCounts = new TreeMap<>();
        Map<String, long[]> checks = new TreeMap<>();
        Map<String, String[]> keyParts = new HashMap<>();
        List<Map<String, Object>> entities = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String type = str(r.get("type"));
            if (type == null || !knownTypes.contains(type)) continue;
            if (Boolean.TRUE.equals(r.get("deleted"))) continue;
            Long teamId = asLong(r.get("team_id"));
            String key = type + "|" + TeamDirectory.id(teamId);
            keyParts.putIfAbsent(key, new String[]{type, teams.name(teamId), TeamDirectory.id(teamId)});
            boolean inventoryInactive = Boolean.TRUE.equals(r.get("inventory_inactive"));
            String state = inventoryInactive ? "paused" : str(r.get("status"));
            if (!STATE_CODES.containsKey(state)) continue;
            stateCounts.computeIfAbsent(key, k -> new long[STATES.size()])[STATES.indexOf(state)]++;
            long[] c = checks.computeIfAbsent(key, k -> new long[2]);
            c[0] += asLongOr(r.get("checks_window"), 0);
            c[1] += asLongOr(r.get("failed_window"), 0);
            if (!inventoryInactive) entities.add(r);
        }
        for (Map.Entry<String, long[]> e : stateCounts.entrySet()) {
            String[] p = keyParts.get(e.getKey());
            for (int i = 0; i < STATES.size(); i++) {
                f.add(MonitoringMetricNames.MONITORS, e.getValue()[i], "type", p[0], "team", p[1], "team_id", p[2],
                        "state", STATES.get(i));
            }
        }
        for (Map.Entry<String, long[]> e : checks.entrySet()) {
            String[] p = keyParts.get(e.getKey());
            f.add(MonitoringMetricNames.MONITORS_CHECKS, e.getValue()[0], "type", p[0], "team", p[1], "team_id", p[2]);
            f.add(MonitoringMetricNames.MONITORS_FAILED, e.getValue()[1], "type", p[0], "team", p[1], "team_id", p[2]);
        }
        if (!perEntity) return failed;

        // Önemli olan önce (tavan aşılırsa sorunlu izlemeler korunur): düşük → gecikmiş → bilinmiyor → sağlıklı → duraklatılmış
        entities.sort(Comparator.<Map<String, Object>>comparingInt(r -> priority(str(r.get("status"))))
                .thenComparing(r -> String.valueOf(r.get("type")))
                .thenComparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT))
                .thenComparingLong(r -> asLongOr(r.get("id"), 0)));
        int limit = Math.max(0, maxEntities);
        if (entities.size() > limit) {
            f.markCapped("monitors");
            entities = entities.subList(0, limit);
        }
        for (Map<String, Object> r : entities) {
            String state = str(r.get("status"));
            Long teamId = asLong(r.get("team_id"));
            String[] kv = {
                    "type", str(r.get("type")),
                    "team", teams.name(teamId),
                    "team_id", TeamDirectory.id(teamId),
                    "group", str(r.get("group_name")),
                    "monitor", str(r.get("name")),
                    "monitor_id", String.valueOf(asLongOr(r.get("id"), 0)),
                    "target", safeTarget(str(r.get("target")))};
            f.add(MonitoringMetricNames.MONITOR_STATE, STATE_CODES.get(state), kv);
            if ("up".equals(state)) f.add(MonitoringMetricNames.MONITOR_UP, 1, kv);
            else if ("down".equals(state)) f.add(MonitoringMetricNames.MONITOR_UP, 0, kv);
            Double resp = asDouble(r.get("response_ms"));
            if (resp != null && resp >= 0) f.add(MonitoringMetricNames.MONITOR_RESPONSE_MS, resp, kv);
            Double avg = asDouble(r.get("avg_response_ms_window"));
            if (avg != null && avg >= 0) f.add(MonitoringMetricNames.MONITOR_AVG_RESPONSE_MS, avg, kv);
            Long last = epochSeconds(str(r.get("last_checked_at")));
            if (last != null) f.add(MonitoringMetricNames.MONITOR_LAST_CHECK, last, kv);
            f.add(MonitoringMetricNames.MONITOR_CHECKS, asLongOr(r.get("checks_window"), 0), kv);
            f.add(MonitoringMetricNames.MONITOR_FAILED, asLongOr(r.get("failed_window"), 0), kv);
            long open = asLongOr(r.get("open_alerts"), 0);
            f.add(MonitoringMetricNames.MONITOR_OPEN_ALERTS, open, kv);
            f.add(MonitoringMetricNames.MONITOR_ALERT_SEVERITY, open > 0 ? severity(str(r.get("open_alert_level"))) : 0, kv);
            Long since = open > 0 ? epochSeconds(str(r.get("open_since"))) : null;
            if (since != null) f.add(MonitoringMetricNames.MONITOR_OPEN_SINCE, since, kv);
            Long interval = asLong(r.get("interval_seconds"));
            if (interval != null && interval > 0) f.add(MonitoringMetricNames.MONITOR_INTERVAL, interval, kv);
        }
        return failed;
    }

    static int priority(String status) {
        return switch (status == null ? "" : status) {
            case "down" -> 0;
            case "stale" -> 1;
            case "unknown" -> 2;
            case "up" -> 3;
            default -> 4;
        };
    }

    /** Açık alarm seviyesi → 0 yok, 1 uyarı, 2 yüksek, 3 kritik (seviyesi tanınmayan açık alarm = 1). */
    static int severity(String level) {
        return switch (normLevel(level)) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            default -> 1;
        };
    }

    // ── Alarmlar ─────────────────────────────────────────────────────────────────────────────────────────

    /** Envanter alan adı → SY / UG takımı (alarm sahipliği ve sertifika UG etiketi). */
    public record Owners(Long teamId, Long ugTeamId) {}

    /**
     * Açık alarm satırları ({@code AlertEventRepository.findOpenMetricRows}: {@code [id, domain, alertType, alertLevel,
     * acknowledged, teamId, createdAt, contextJson]}) → toplamlar (+ açıksa alarm başına satır). Sahiplik eylem kuralıyla
     * AYNI ({@link AlertOwnership}): bağımsız izleme alarmı yalnız damgalı takımın, envanter gibi yönlenen alarm damga +
     * envanterin SY / UG takımının. İlk sahip {@code team}, ikincisi {@code ug_team} — panonun takım süzgeci ikisini de
     * arar ({@code team=~"$team" or ug_team=~"$team"}), aynı alarm iki kez sayılmaz.
     */
    public static void alerts(Families f, List<Object[]> openRows, Map<String, Owners> inventory, TeamDirectory teams,
                              boolean perEntity, int maxEntities, Instant now) {
        long[] byLevel = new long[LEVELS.size()];
        Map<List<String>, long[]> counts = new LinkedHashMap<>();
        Map<List<String>, Long> oldest = new LinkedHashMap<>();
        List<String[]> entities = new ArrayList<>();
        List<Object[]> sorted = new ArrayList<>(openRows == null ? List.of() : openRows);
        for (Object[] r : sorted) {
            if (r == null || r.length < 8) continue;
            AlertEvent ev = new AlertEvent();
            ev.setId(asLong(r[0]));
            ev.setDomain(str(r[1]));
            ev.setAlertType(str(r[2]));
            ev.setTeamId(asLong(r[5]));
            ev.setContextJson(str(r[7]));
            Owners inv = ev.getDomain() == null || inventory == null ? null : inventory.get(ev.getDomain());
            Set<Long> owners = AlertOwnership.ownerTeamIds(ev, inv == null ? null : inv.teamId(), inv == null ? null : inv.ugTeamId());
            List<Long> o = new ArrayList<>(owners);
            Long primary = o.isEmpty() ? null : o.get(0);
            Long second = o.size() > 1 ? o.get(1) : null;
            String level = normLevel(str(r[3]));
            String family = familyOf(ev.getAlertType());
            String ack = String.valueOf(Boolean.TRUE.equals(r[4]));
            String team = teams.name(primary), teamId = TeamDirectory.id(primary);
            String ug = second == null ? "" : teams.name(second);
            String ugId = second == null ? "" : TeamDirectory.id(second);
            byLevel[LEVELS.indexOf(level)]++;
            counts.computeIfAbsent(List.of(family, label(ev.getAlertType()), team, teamId, ug, ugId, level, ack), k -> new long[1])[0]++;
            Long created = epochSeconds(str(r[6]));
            if (created != null) {
                oldest.merge(List.of(family, team, teamId, ug, ugId, level), created, Math::min);
            }
            if (perEntity && created != null) {
                entities.add(new String[]{String.valueOf(asLongOr(r[0], 0)), family, label(ev.getAlertType()),
                        safeTarget(ev.getDomain()), team, teamId, ug, ugId, level, ack, String.valueOf(created)});
            }
        }
        for (int i = 0; i < LEVELS.size(); i++) f.add(MonitoringMetricNames.ALERTS_OPEN_BY_LEVEL, byLevel[i], "level", LEVELS.get(i));
        for (Map.Entry<List<String>, long[]> e : counts.entrySet()) {
            List<String> k = e.getKey();
            f.add(MonitoringMetricNames.ALERTS_OPEN, e.getValue()[0], "family", k.get(0), "alert_type", k.get(1), "team", k.get(2),
                    "team_id", k.get(3), "ug_team", k.get(4), "ug_team_id", k.get(5), "level", k.get(6), "acknowledged", k.get(7));
        }
        for (Map.Entry<List<String>, Long> e : oldest.entrySet()) {
            List<String> k = e.getKey();
            f.add(MonitoringMetricNames.ALERTS_OLDEST, e.getValue(), "family", k.get(0), "team", k.get(1), "team_id", k.get(2),
                    "ug_team", k.get(3), "ug_team_id", k.get(4), "level", k.get(5));
        }
        if (!perEntity) return;
        // Önce en ağır, sonra en eski: tavan aşılırsa kritik ve eski alarmlar korunur
        entities.sort(Comparator.<String[]>comparingInt(e -> LEVELS.indexOf(e[8])).thenComparingLong(e -> Long.parseLong(e[10]))
                .thenComparing(e -> e[0]));
        if (entities.size() > Math.max(0, maxEntities)) {
            f.markCapped("alerts");
            entities = entities.subList(0, Math.max(0, maxEntities));
        }
        for (String[] e : entities) {
            f.add(MonitoringMetricNames.ALERT_OPEN, Double.parseDouble(e[10]), "alert_id", e[0], "family", e[1], "alert_type", e[2],
                    "target", e[3], "team", e[4], "team_id", e[5], "ug_team", e[6], "ug_team_id", e[7], "level", e[8],
                    "acknowledged", e[9]);
        }
    }

    /** Alarm tipi → izleme ailesi ({@link MonitorTypeCatalog#typeOfAlert}); tanınmayan → {@code other}. */
    static String familyOf(String alertType) {
        String f = alertType == null ? null : MonitorTypeCatalog.typeOfAlert(alertType);
        return f == null ? "other" : f;
    }

    /** Seviye → {@link #LEVELS} değeri (MEDIUM = WARNING; tanınmayan = OTHER) — menü rozetleriyle aynı kural. */
    static String normLevel(String level) {
        String l = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        return switch (l) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH" -> "HIGH";
            case "WARNING", "MEDIUM" -> "WARNING";
            default -> "OTHER";
        };
    }

    // ── Sertifikalar ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Aktif envanterin son sertifika kontrolleri ({@code CertificateService.getAllLatest}) → toplamlar (+ sertifika başına).
     * {@code team} = envanterin SY takımı, {@code ug_team} = UG takımı (farklıysa).
     */
    public static void certificates(Families f, List<CertificateDto> certs, Map<String, Owners> inventory, TeamDirectory teams,
                                    boolean perEntity, int maxEntities) {
        Map<List<String>, long[]> status = new LinkedHashMap<>();
        Map<List<String>, long[]> expiry = new LinkedHashMap<>();
        Map<List<String>, long[]> grade = new LinkedHashMap<>();
        List<CertificateDto> list = new ArrayList<>(certs == null ? List.of() : certs);
        for (CertificateDto c : list) {
            if (c == null || c.getDomain() == null) continue;
            List<String> owner = certOwner(c, inventory, teams);
            status.computeIfAbsent(owner, k -> new long[CERT_STATUSES.size()])[CERT_STATUSES.indexOf(certStatus(c))]++;
            expiry.computeIfAbsent(owner, k -> new long[EXPIRY_BUCKETS.size()])[EXPIRY_BUCKETS.indexOf(bucket(c.getDaysRemaining()))]++;
            String g = TlsGradeRules.isGrade(c.getTlsGrade()) ? c.getTlsGrade() : "none";
            grade.computeIfAbsent(owner, k -> new long[GRADE_BUCKETS.size()])[GRADE_BUCKETS.indexOf(g)]++;
        }
        for (Map.Entry<List<String>, long[]> e : status.entrySet()) {
            for (int i = 0; i < CERT_STATUSES.size(); i++) {
                f.add(MonitoringMetricNames.CERTS, e.getValue()[i], "team", e.getKey().get(0), "team_id", e.getKey().get(1),
                        "ug_team", e.getKey().get(2), "ug_team_id", e.getKey().get(3), "status", CERT_STATUSES.get(i));
            }
        }
        for (Map.Entry<List<String>, long[]> e : expiry.entrySet()) {
            for (int i = 0; i < EXPIRY_BUCKETS.size(); i++) {
                f.add(MonitoringMetricNames.CERTS_EXPIRY, e.getValue()[i], "team", e.getKey().get(0), "team_id", e.getKey().get(1),
                        "ug_team", e.getKey().get(2), "ug_team_id", e.getKey().get(3), "bucket", EXPIRY_BUCKETS.get(i));
            }
        }
        for (Map.Entry<List<String>, long[]> e : grade.entrySet()) {
            for (int i = 0; i < GRADE_BUCKETS.size(); i++) {
                f.add(MonitoringMetricNames.CERTS_GRADE, e.getValue()[i], "team", e.getKey().get(0), "team_id", e.getKey().get(1),
                        "ug_team", e.getKey().get(2), "ug_team_id", e.getKey().get(3), "grade", GRADE_BUCKETS.get(i));
            }
        }
        if (!perEntity) return;
        // Önce en kötü durum, sonra en az kalan gün
        list.removeIf(c -> c == null || c.getDomain() == null);
        list.sort(Comparator.<CertificateDto>comparingInt(c -> -CERT_STATUSES.indexOf(certStatus(c)))
                .thenComparingInt(c -> c.getDaysRemaining() == null ? Integer.MAX_VALUE : c.getDaysRemaining())
                .thenComparing(CertificateDto::getDomain));
        if (list.size() > Math.max(0, maxEntities)) {
            f.markCapped("certificates");
            list = list.subList(0, Math.max(0, maxEntities));
        }
        for (CertificateDto c : list) {
            List<String> owner = certOwner(c, inventory, teams);
            String[] kv = {"domain", c.getDomain(), "team", owner.get(0), "team_id", owner.get(1), "ug_team", owner.get(2),
                    "ug_team_id", owner.get(3), "tier", c.getTier() == null ? "" : String.valueOf(c.getTier()), "group", str(c.getGroupName()),
                    "source", "MANUAL".equalsIgnoreCase(c.getCertSource()) ? "manual" : "network"};
            if (c.getDaysRemaining() != null) f.add(MonitoringMetricNames.CERT_DAYS, c.getDaysRemaining(), kv);
            f.add(MonitoringMetricNames.CERT_STATUS, CERT_STATUSES.indexOf(certStatus(c)), kv);
            if (TlsGradeRules.isGrade(c.getTlsGrade())) f.add(MonitoringMetricNames.CERT_TLS_GRADE, TlsGradeRules.rank(c.getTlsGrade()), kv);
            Long at = epochSeconds(c.getCheckedAt());
            if (at != null) f.add(MonitoringMetricNames.CERT_LAST_CHECK, at, kv);
        }
    }

    private static List<String> certOwner(CertificateDto c, Map<String, Owners> inventory, TeamDirectory teams) {
        Owners o = inventory == null ? null : inventory.get(c.getDomain());
        Long sy = o != null && o.teamId() != null ? o.teamId() : c.getTeamId();
        Long ug = o == null ? null : o.ugTeamId();
        boolean hasUg = ug != null && ug > 0 && !ug.equals(sy);
        return List.of(teams.name(sy), TeamDirectory.id(sy), hasUg ? teams.name(ug) : "", hasUg ? TeamDirectory.id(ug) : "");
    }

    /** Sertifika satırının durumu — kartın {@code alert_level}'i ({@code CertificateService.computeAlertLevel}). */
    static String certStatus(CertificateDto c) {
        String l = c.getAlertLevel() == null ? "" : c.getAlertLevel().toLowerCase(Locale.ROOT);
        if (CERT_STATUSES.contains(l)) return l;
        if ("error".equalsIgnoreCase(c.getStatus())) return "error";
        return "valid";
    }

    /** Kalan gün → kova. */
    public static String bucket(Integer days) {
        if (days == null) return "unknown";
        if (days < 0) return "expired";
        if (days <= 7) return "d7";
        if (days <= 30) return "d30";
        if (days <= 90) return "d90";
        return "later";
    }

    // ── Alan adı kaydı ───────────────────────────────────────────────────────────────────────────────────

    /** Bir alan adı izlemesinin son kalan günü ({@code DomainCheck.daysRemaining}) — izleme kimliğiyle. */
    public record DomainDays(long monitorId, Integer days) {}

    /**
     * Alan adı kaydı izlemeleri: Genel Bakış satırlarından ({@code type=domain}, takım / ad / grup) + son kontrolün kalan
     * günü. Silinmiş ve duraklatılmış izleme yayılmaz (izlenmiyor).
     */
    @SuppressWarnings("unchecked")
    public static void domains(Families f, Map<String, Object> overview, List<DomainDays> latest, TeamDirectory teams,
                               boolean perEntity, int maxEntities) {
        if (overview == null || !(overview.get("monitors") instanceof List<?> l)) return;
        Map<Long, Integer> days = new HashMap<>();
        for (DomainDays d : latest == null ? List.<DomainDays>of() : latest) days.put(d.monitorId(), d.days());
        Map<List<String>, long[]> buckets = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> r = (Map<String, Object>) m;
            if (!"domain".equals(r.get("type")) || Boolean.TRUE.equals(r.get("deleted")) || !Boolean.TRUE.equals(r.get("active"))) continue;
            Long teamId = asLong(r.get("team_id"));
            Integer d = days.get(asLongOr(r.get("id"), -1));
            buckets.computeIfAbsent(List.of(teams.name(teamId), TeamDirectory.id(teamId)), k -> new long[EXPIRY_BUCKETS.size()])
                    [EXPIRY_BUCKETS.indexOf(bucket(d))]++;
            rows.add(r);
        }
        for (Map.Entry<List<String>, long[]> e : buckets.entrySet()) {
            for (int i = 0; i < EXPIRY_BUCKETS.size(); i++) {
                f.add(MonitoringMetricNames.DOMAINS_EXPIRY, e.getValue()[i], "team", e.getKey().get(0), "team_id", e.getKey().get(1),
                        "bucket", EXPIRY_BUCKETS.get(i));
            }
        }
        if (!perEntity) return;
        rows.sort(Comparator.<Map<String, Object>>comparingInt(r -> {
            Integer d = days.get(asLongOr(r.get("id"), -1));
            return d == null ? Integer.MAX_VALUE : d;
        }).thenComparing(r -> String.valueOf(r.get("target"))));
        if (rows.size() > Math.max(0, maxEntities)) {
            f.markCapped("domains");
            rows = rows.subList(0, Math.max(0, maxEntities));
        }
        for (Map<String, Object> r : rows) {
            Integer d = days.get(asLongOr(r.get("id"), -1));
            if (d == null) continue;
            Long teamId = asLong(r.get("team_id"));
            f.add(MonitoringMetricNames.DOMAIN_DAYS, d, "domain", str(r.get("target")), "monitor", str(r.get("name")),
                    "monitor_id", String.valueOf(asLongOr(r.get("id"), 0)), "team", teams.name(teamId),
                    "team_id", TeamDirectory.id(teamId), "group", str(r.get("group_name")));
        }
    }

    // ── Fırtınalar ───────────────────────────────────────────────────────────────────────────────────────

    /** Aktif fırtına: takım + üye sayısı. */
    public record ActiveStorm(Long teamId, Integer members) {}

    public static void storms(Families f, List<ActiveStorm> active, TeamDirectory teams) {
        Map<List<String>, long[]> byTeam = new LinkedHashMap<>();
        long total = 0;
        for (ActiveStorm s : active == null ? List.<ActiveStorm>of() : active) {
            long[] v = byTeam.computeIfAbsent(List.of(teams.name(s.teamId()), TeamDirectory.id(s.teamId())), k -> new long[2]);
            v[0]++;
            v[1] += s.members() == null ? 0 : Math.max(0, s.members());
            total++;
        }
        f.add(MonitoringMetricNames.STORMS_ACTIVE_ALL, total);
        for (Map.Entry<List<String>, long[]> e : byTeam.entrySet()) {
            f.add(MonitoringMetricNames.STORMS_ACTIVE, e.getValue()[0], "team", e.getKey().get(0), "team_id", e.getKey().get(1));
            f.add(MonitoringMetricNames.STORM_MEMBERS, e.getValue()[1], "team", e.getKey().get(0), "team_id", e.getKey().get(1));
        }
    }

    // ── Bildirimler ──────────────────────────────────────────────────────────────────────────────────────

    /** Bildirim günlüğü durumu → sınıf: sent / failed / skipped / queued / none / other (ham metin etiket olmaz). */
    public static String statusClass(String status) {
        if (status == null || status.isBlank()) return "none";
        String s = status.trim().toUpperCase(Locale.ROOT);
        if (s.equals("NONE")) return "none";   // SQL sınıflaması: kanal bu satırda denenmedi
        if (s.startsWith("SENT")) return "sent";
        if (s.startsWith("FAILED")) return "failed";
        if (s.startsWith("SKIPPED")) return "skipped";
        if (s.startsWith("QUEUED")) return "queued";
        return "other";
    }

    /** Push teslim durumu → sınıf: sent / failed / pending / skipped / rate_limited / other. */
    public static String pushClass(String status) {
        if (status == null || status.isBlank()) return "other";
        String s = status.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("SENT")) return "sent";
        if (s.startsWith("FAILED") || s.startsWith("REJECTED")) return "failed";
        if (s.startsWith("PENDING") || s.startsWith("QUEUED")) return "pending";
        if (s.startsWith("SKIPPED")) return "skipped";
        if (s.startsWith("RATE_LIMITED")) return "rate_limited";
        return "other";
    }

    /** Tetik adı → etiket: büyük harf, yalnız [A-Z0-9_], ≤ 40 (bilinmeyen biçim {@code OTHER}). */
    static String triggerLabel(String trigger) {
        if (trigger == null || trigger.isBlank()) return "NONE";
        String t = trigger.trim().toUpperCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length() && b.length() < 40; i++) {
            char c = t.charAt(i);
            b.append((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' ? c : '_');
        }
        return b.length() == 0 ? "OTHER" : b.toString();
    }

    /**
     * Son 24 saatin bildirim kararları — satırlar {@code [kanal, tetik, ham durum, sayı]} (sorgu ham durumu SQL'de sınıflar,
     * burada yine sınıflanır: eski / yeni biçim aynı etiketi alır). Aynı (kanal, tetik, sınıf) toplanır.
     */
    public static void notifications(Families f, List<Object[]> rows) {
        Map<List<String>, long[]> agg = new TreeMap<>(Comparator.comparing(Object::toString));
        for (Object[] r : rows == null ? List.<Object[]>of() : rows) {
            if (r == null || r.length < 4) continue;
            String channel = "webhook".equals(str(r[0])) ? "webhook" : "email";
            String cls = statusClass(str(r[2]));
            if ("none".equals(cls)) continue;   // kanal bu satırda hiç denenmedi (ör. webhook yok)
            agg.computeIfAbsent(List.of(channel, triggerLabel(str(r[1])), cls), k -> new long[1])[0] += asLongOr(r[3], 0);
        }
        for (Map.Entry<List<String>, long[]> e : agg.entrySet()) {
            f.add(MonitoringMetricNames.NOTIFICATIONS, e.getValue()[0], "channel", e.getKey().get(0), "trigger", e.getKey().get(1),
                    "status", e.getKey().get(2));
        }
    }

    /** Push teslimleri — satırlar {@code [ham durum, sayı]}; sınıflar sıfır dahil yazılır. */
    public static void pushDeliveries(Families f, List<Object[]> rows) {
        Map<String, Long> agg = new LinkedHashMap<>();
        for (String c : List.of("sent", "failed", "pending", "skipped", "rate_limited", "other")) agg.put(c, 0L);
        for (Object[] r : rows == null ? List.<Object[]>of() : rows) {
            if (r == null || r.length < 2) continue;
            agg.merge(pushClass(str(r[0])), asLongOr(r[1], 0), Long::sum);
        }
        for (Map.Entry<String, Long> e : agg.entrySet()) f.add(MonitoringMetricNames.PUSH_DELIVERIES, e.getValue(), "status", e.getKey());
    }

    // ── Zamanlayıcı kilitleri ────────────────────────────────────────────────────────────────────────────

    /** Kilit satırları {@code [ad, locked_until]} → tutulan kilitler; {@code ad:anahtar} biçimi {@code ad:*}'a katlanır. */
    public static void schedulerLocks(Families f, List<Object[]> rows, Instant now, int maxNames) {
        Map<String, Long> held = new TreeMap<>();
        for (Object[] r : rows == null ? List.<Object[]>of() : rows) {
            if (r == null || r.length < 2 || r[0] == null) continue;
            Long until = epochSeconds(str(r[1]));
            if (until == null || until <= now.getEpochSecond()) continue;
            String name = str(r[0]);
            int colon = name.indexOf(':');
            if (colon >= 0) name = name.substring(0, colon) + ":*";
            held.merge(label(name), 1L, Long::sum);
        }
        int n = 0;
        for (Map.Entry<String, Long> e : held.entrySet()) {
            if (n++ >= maxNames) { f.markCapped("scheduler_locks"); break; }
            f.add(MonitoringMetricNames.SCHEDULER_LOCK, e.getValue(), "name", e.getKey());
        }
    }

    // ── Kendi kendini izleme ─────────────────────────────────────────────────────────────────────────────

    /** Genel durum metni → kod (0 UP, 1 DEGRADED, 2 DOWN, 3 DISABLED, 4 UNKNOWN); tanınmayan → null (yazılmaz). */
    public static Integer watchdogCode(String overall) {
        if (overall == null) return null;
        return switch (overall) {
            case "UP" -> 0;
            case "DEGRADED" -> 1;
            case "DOWN" -> 2;
            case "DISABLED" -> 3;
            case "UNKNOWN" -> 4;
            default -> null;
        };
    }

    /** Kontrol durumu (+ önem) → kod: 0 OK, 1/2/3 sorun (MEDIUM/HIGH/CRITICAL), 4 hiç değerlendirilmedi. */
    public static int watchdogCheckCode(String status, String severity) {
        if (status == null) return 4;
        if (!"PROBLEM".equals(status)) return 0;
        String s = severity == null ? "" : severity.toUpperCase(Locale.ROOT);
        return switch (s) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            default -> 1;
        };
    }

    /** Sistem bakım bloğunun {@code state} alanı → kod; tanınmayan → null. */
    public static Integer maintenanceCode(String state) {
        if (state == null) return null;
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "none" -> 0;
            case "announced" -> 1;
            case "warning" -> 2;
            case "active" -> 3;
            case "ended" -> 4;
            default -> null;
        };
    }

    /** Veritabanı sağlığı → kod (0 UP, 1 DEGRADED, 2 DOWN); tanınmayan → null. */
    public static Integer databaseCode(String status) {
        if (status == null) return null;
        return switch (status) {
            case "UP" -> 0;
            case "DEGRADED" -> 1;
            case "DOWN" -> 2;
            default -> null;
        };
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────────

    /** Etiket değeri: null → "", denetim karakterleri boşluk, ≤ {@link #LABEL_MAX} (uzunsa "…"). */
    public static String label(String v) {
        if (v == null) return "";
        StringBuilder b = new StringBuilder(Math.min(v.length(), LABEL_MAX + 1));
        for (int i = 0; i < v.length() && b.length() < LABEL_MAX; i++) {
            char c = v.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        String out = b.toString().trim();
        if (v.length() > LABEL_MAX) {
            out = out.substring(0, Math.min(out.length(), LABEL_MAX - 1));
            // Vekil çifti ortadan bölünmesin
            if (!out.isEmpty() && Character.isHighSurrogate(out.charAt(out.length() - 1))) out = out.substring(0, out.length() - 1);
            out = out + "…";
        }
        return out;
    }

    /**
     * Hedef (URL / host) etiketi: kullanıcı bilgisi ({@code //kullanıcı:parola@}) atılır, hassas sorgu parametreleri
     * maskelenir ({@link SecretMask#maskUrlQuery}). {@code /metrics} kimliksizdir — sır asla etikete yazılmaz.
     */
    public static String safeTarget(String target) {
        if (target == null) return "";
        String t = target.length() > 2048 ? target.substring(0, 2048) : target;
        int scheme = t.indexOf("://");
        if (scheme > 0) {
            int hostStart = scheme + 3;
            int end = hostStart;
            while (end < t.length() && "/?#".indexOf(t.charAt(end)) < 0) end++;
            int at = t.lastIndexOf('@', end - 1);
            if (at >= hostStart) t = t.substring(0, hostStart) + t.substring(at + 1);
        }
        return label(SecretMask.maskUrlQuery(t));
    }

    /** ISO zaman (UTC; {@code Z}'li, ofsetli ya da yerel biçim, kesirli olabilir) → epoch saniye; okunamazsa null. */
    public static Long epochSeconds(String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim();
        try { return Instant.parse(s).getEpochSecond(); } catch (Exception ignored) { /* sonraki biçim */ }
        try { return OffsetDateTime.parse(s).toEpochSecond(); } catch (Exception ignored) { /* sonraki biçim */ }
        try {
            String local = s.length() > 19 ? s.substring(0, 19) : s;
            return LocalDateTime.parse(local.replace(' ', 'T')).toEpochSecond(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String str(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        return s;
    }

    static Long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    static long asLongOr(Object v, long fallback) {
        Long l = asLong(v);
        return l == null ? fallback : l;
    }

    static Double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }
}

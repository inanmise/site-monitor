package com.sitemonitor.service.quality;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.service.noc.NocCoverageService;
import com.sitemonitor.service.noc.NocMonitorDirectory;
import com.sitemonitor.service.noc.NocType;
import com.sitemonitor.service.quality.DataQualityScore.Count;
import com.sitemonitor.service.quality.DataQualitySource.Facts;
import com.sitemonitor.service.quality.DataQualitySource.MonitorFact;
import com.sitemonitor.service.quality.DataQualitySource.TeamFact;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Kuralları girdilere uygular — SAF (veritabanı yok, saat dışarıdan): kural doğruluk tablosu birim testlerle pinlenir.
 *
 * <p><b>Kovalar.</b> Her öğe sahibi takımın kovasına düşer. Sahibi olmayan, silinmiş takıma ya da PASİF takıma bağlı
 * öğe "Sahipsiz" kovasındadır (takımın hiçbir üyesi onu kendi listesinde görmez, alarm kimseye gitmez). Kurum
 * kovası her öğeyi sayar. Pasif takım sıralamada yer almaz.
 *
 * <p><b>Uygunluk.</b> Kuralın paydası (hangi öğelere bakıldığı) {@link DataQualityRule} belgesinde; burada:
 * envanter kuralları AKTİF kayıtlara; {@code INV_TIER_SUSPECT} katmanı DOLU kayıtlara; {@code NOC_CRITICAL_UNCOVERED}
 * yalnız 7/24 kullanılabilirken (en az bir aktif adresli grup VE SSL türü açık) katman 1–2 aktif kayıtlara;
 * {@code MON_NO_GROUP} / {@code MON_DUPLICATE} aktif BAĞIMSIZ izlemelere (türev DNS/Port envanterden gelir);
 * {@code MON_PAUSED_LONG} silinmemiş her izleme ve envanter kaydına; takımın adres/eskalasyon kuralları yalnız en az
 * bir aktif izlemesi/kaydı olan takıma (izlemesi olmayan takımdan alarm çıkmaz).
 */
public final class DataQualityEvaluator {

    private DataQualityEvaluator() {}

    /** "Uzun süredir duraklatılmış" eşiği (gün). */
    public static final int PAUSED_LONG_DAYS = 30;
    /** "Tekrar tekrar hata": son 7 günde en az bu kadar hatalı tarama. */
    public static final int REPEATED_ERRORS = 3;
    /** Kural × kova başına yanıtta taşınan en çok öğe (fazlası {@code truncated}). */
    public static final int MAX_ITEMS_PER_RULE = 500;

    /** Sahipsizlik nedenleri. */
    public static final String OWNER_NONE = "NONE";
    public static final String OWNER_MISSING = "TEAM_MISSING";
    public static final String OWNER_INACTIVE = "TEAM_INACTIVE";
    public static final List<String> OWNER_REASONS = List.of(OWNER_NONE, OWNER_MISSING, OWNER_INACTIVE);

    /** Kurum notu kodları (arayüz {@code dq.note.<KOD>}). */
    public static final String NOTE_NOC_NOT_CONFIGURED = "NOC_NOT_CONFIGURED";
    public static final String NOTE_HYGIENE_UNAVAILABLE = "HYGIENE_UNAVAILABLE";
    public static final List<String> NOTE_CODES = List.of(NOTE_NOC_NOT_CONFIGURED, NOTE_HYGIENE_UNAVAILABLE);

    /**
     * Tek düzeltme kalemi. {@code kind}: {@code inventory} | {@code monitor} | {@code team}; {@code type}: izleme türü
     * ({@link NocType} adı; envanter {@code SSL}) ya da {@code TEAM}. {@code detail} kurala özgü, dil bağımsız değerler.
     */
    public record Finding(DataQualityRule rule, String kind, String type, long id, String name, String target,
                          Long teamId, boolean manual, Map<String, Object> detail) {}

    /** Bir kova: takım (ya da Sahipsiz = {@code teamId null}) — kural sayaçları, bulgular, incelenen öğe sayısı. */
    public record Bucket(Long teamId, String teamName, Map<DataQualityRule, Count> counts,
                         Map<DataQualityRule, List<Finding>> findings, int items) {
        public Integer score() { return DataQualityScore.score(counts); }

        public int findingCount() {
            int n = 0;
            for (Count c : counts.values()) n += c.failing();
            return n;
        }
    }

    /** Kurum geneli not (ör. 7/24 yapılandırılmamış). */
    public record Note(String code, Map<String, Object> params) {}

    /** Bütün sonuç. {@code teams}: aktif takımlar (ad sırası); {@code unassigned}: sahipsiz kova. */
    public record Evaluation(Instant at, Bucket org, List<Bucket> teams, Bucket unassigned, List<Note> notes) {
        public Bucket team(Long id) {
            for (Bucket b : teams) if (b.teamId() != null && b.teamId().equals(id)) return b;
            return null;
        }
    }

    // ── Hesap ────────────────────────────────────────────────────────────────────────────────────────

    public static Evaluation evaluate(Facts f, Instant now) {
        Map<Long, TeamFact> teamsById = new HashMap<>();
        for (TeamFact t : f.teams()) teamsById.put(t.id(), t);

        Acc org = new Acc(null, null);
        Acc unassigned = new Acc(null, null);
        Map<Long, Acc> teams = new LinkedHashMap<>();
        List<TeamFact> active = new ArrayList<>();
        for (TeamFact t : f.teams()) if (t.active()) active.add(t);
        active.sort(Comparator.comparing((TeamFact t) -> t.name() == null ? "" : t.name().toLowerCase(Locale.ROOT)));
        for (TeamFact t : active) teams.put(t.id(), new Acc(t.id(), t.name()));

        // ── Envanter ──
        for (CertificateInventory r : f.inventory()) {
            if (r.getId() == null) continue;
            String owner = ownerProblem(r.getTeamId(), teamsById);
            Acc bucket = owner == null ? teams.get(r.getTeamId()) : unassigned;
            boolean isActive = !Boolean.FALSE.equals(r.getActive());
            Item item = Item.inventory(r);
            bucket.items++;
            org.items++;
            if (isActive) {
                bucket.activeItems++;
                ownership(org, unassigned, DataQualityRule.INV_NO_TEAM, item, owner);
                inventoryRules(f, r, item, org, bucket);
            }
            pausedRule(org, bucket, item, !isActive,
                    f.pausedSince().get(NocType.SSL.historyKind + ":" + r.getId()), r.getUpdatedAt(), now);
        }

        // ── İzlemeler ──
        Map<String, Long> firstOfDup = firstOfDuplicateGroups(f.monitors(), teamsById);
        Map<Long, String> monitorNames = new HashMap<>();
        for (MonitorFact m : f.monitors()) {
            String owner = ownerProblem(m.teamId(), teamsById);
            Acc bucket = owner == null ? teams.get(m.teamId()) : unassigned;
            Item item = Item.monitor(m);
            bucket.items++;
            org.items++;
            if (m.active()) bucket.activeItems++;
            if (m.active() && m.standalone()) {
                ownership(org, unassigned, DataQualityRule.MON_NO_TEAM, item, owner);
                boolean noGroup = m.groupName() == null || m.groupName().isBlank();
                count(org, bucket, DataQualityRule.MON_NO_GROUP, noGroup, item, Map.of());
                if (m.dupKey() != null && owner == null) {
                    Long keep = firstOfDup.get(dupGroup(m));
                    boolean dup = keep != null && keep != m.id();
                    Map<String, Object> d = new LinkedHashMap<>();
                    if (dup) {
                        d.put("duplicate_of_id", keep);
                        d.put("duplicate_of_name", monitorName(f.monitors(), m.type(), keep, monitorNames));
                    }
                    count(org, bucket, DataQualityRule.MON_DUPLICATE, dup, item, d);
                }
            }
            pausedRule(org, bucket, item, !m.active(), f.pausedSince().get(m.type().historyKind + ":" + m.id()),
                    m.updatedAt(), now);
        }

        // ── Takımlar ──
        for (TeamFact t : active) {
            Acc bucket = teams.get(t.id());
            Item item = Item.team(t);
            bucket.items++;
            org.items++;
            int members = f.activeMembers().getOrDefault(t.id(), 0);
            count(org, bucket, DataQualityRule.TEAM_NO_MEMBERS, members == 0, item, Map.of());
            boolean lead = t.leaderActive() || t.managerActive();
            Map<String, Object> leadDetail = new LinkedHashMap<>();
            if (!lead) {
                leadDetail.put("leader", t.leaderSet() ? "INACTIVE" : "NONE");
                leadDetail.put("manager", t.managerSet() ? "INACTIVE" : "NONE");
            }
            count(org, bucket, DataQualityRule.TEAM_NO_MANAGER, !lead, item, leadDetail);
            if (bucket.activeItems > 0) {
                boolean address = t.hasEmail() || f.teamsWithGroupAddress().contains(t.id());
                count(org, bucket, DataQualityRule.TEAM_NO_NOTIFY_ADDRESS, !address, item, Map.of());
                DataQualitySource.Escalation e = f.escalation().get(t.id());
                boolean high = e != null && e.high();
                boolean critical = e != null && e.critical();
                List<String> missing = new ArrayList<>();
                if (!high) missing.add("HIGH");
                if (!critical) missing.add("CRITICAL");
                count(org, bucket, DataQualityRule.TEAM_NO_ESCALATION, !missing.isEmpty(), item,
                        missing.isEmpty() ? Map.of() : Map.of("missing", List.copyOf(missing)));
            }
        }

        List<Note> notes = new ArrayList<>();
        if (f.noc() != null && !f.noc().usable()) {
            int critical = 0;
            for (CertificateInventory r : f.inventory()) {
                if (!Boolean.FALSE.equals(r.getActive()) && r.getTier() != null && r.getTier() <= 2) critical++;
            }
            notes.add(new Note(NOTE_NOC_NOT_CONFIGURED, Map.of("reason", String.valueOf(f.noc().reason()), "critical", critical)));
        }
        if (f.hygieneCodes() == null) notes.add(new Note(NOTE_HYGIENE_UNAVAILABLE, Map.of()));

        List<Bucket> teamBuckets = new ArrayList<>();
        for (Acc a : teams.values()) teamBuckets.add(a.freeze());
        return new Evaluation(now, org.freeze(), Collections.unmodifiableList(teamBuckets), unassigned.freeze(),
                List.copyOf(notes));
    }

    private static void inventoryRules(Facts f, CertificateInventory r, Item item, Acc org, Acc bucket) {
        count(org, bucket, DataQualityRule.INV_NO_TIER, r.getTier() == null, item, Map.of());
        if (r.getTier() != null) {
            TierHeuristics.Finding tf = TierHeuristics.evaluate(r);
            Map<String, Object> d = new LinkedHashMap<>();
            if (tf != null) {
                d.put("tier", r.getTier());
                d.put("reason", tf.reason());
                if (tf.token() != null) d.put("token", tf.token());
            }
            count(org, bucket, DataQualityRule.INV_TIER_SUSPECT, tf != null, item, d);
        }
        if (f.hygieneCodes() != null) {
            Set<String> codes = r.getDomain() == null ? Set.of()
                    : f.hygieneCodes().getOrDefault(r.getDomain().toLowerCase(Locale.ROOT), Set.of());
            count(org, bucket, DataQualityRule.INV_NO_CONTACTS, codes.contains("no_contacts"), item, Map.of());
            count(org, bucket, DataQualityRule.INV_NEVER_CHECKED, codes.contains("never_checked"), item, Map.of());
            count(org, bucket, DataQualityRule.INV_STALE_CHECK, codes.contains("stale"), item, Map.of());
            String key = r.getDomain() == null ? "" : r.getDomain().toLowerCase(Locale.ROOT);
            Integer errors = f.errorCounts() == null ? null : f.errorCounts().getOrDefault(key, 0);
            // Hata sayısı okunamadıysa (null) yalnız son durum hata ise kusurlu say — "bilinmiyor" sessiz geçmesin
            boolean failing = codes.contains("error") && (errors == null || errors >= REPEATED_ERRORS);
            Map<String, Object> d = new LinkedHashMap<>();
            if (failing) {
                if (errors != null) d.put("errors_7d", errors);
                String err = f.hygieneErrors() == null ? null : f.hygieneErrors().get(key);
                if (err != null) d.put("error", err);
            }
            count(org, bucket, DataQualityRule.INV_CHECK_FAILING, failing, item, d);
        }
        if (f.noc() != null && f.noc().usable() && r.getTier() != null && r.getTier() <= 2) {
            NocMonitorDirectory.Row row = new NocMonitorDirectory.Row(NocType.SSL, r.getId(), r.getDomain(), r.getDomain(),
                    r.getTeamId(), r.getUgTeamId(), true, Boolean.TRUE.equals(r.getNocNotify()), r.getNocGroupIds(), false);
            String reason = NocCoverageService.reason(row, f.noc().config(), f.noc().anyGroup());
            boolean off = NocCoverageService.MONITOR_OFF.equals(reason);
            count(org, bucket, DataQualityRule.NOC_CRITICAL_UNCOVERED, off, item,
                    off ? Map.of("tier", r.getTier()) : Map.of());
        }
    }

    private static void pausedRule(Acc org, Acc bucket, Item item, boolean paused, String exact, String updatedAt,
                                   Instant now) {
        Long days = null;
        if (paused) {
            Instant at = parse(exact != null ? exact : updatedAt);
            if (at != null) days = Math.max(0, Duration.between(at, now).toDays());
        }
        boolean longPause = days != null && days >= PAUSED_LONG_DAYS;
        Map<String, Object> d = new LinkedHashMap<>();
        if (longPause) {
            d.put("days", days);
            d.put("exact", exact != null);
        }
        count(org, bucket, DataQualityRule.MON_PAUSED_LONG, longPause, item, d);
    }

    /** Sahiplik kuralı: kurum kovasına her zaman sayılır; kusurluysa Sahipsiz kovasına bulgu olarak düşer. */
    private static void ownership(Acc org, Acc unassigned, DataQualityRule rule, Item item, String problem) {
        boolean failing = problem != null;
        org.add(rule, failing, item, Map.of("reason", problem == null ? "" : problem));
        if (failing) unassigned.add(rule, true, item, Map.of("reason", problem));
    }

    private static void count(Acc org, Acc bucket, DataQualityRule rule, boolean failing, Item item,
                              Map<String, Object> detail) {
        org.add(rule, failing, item, detail);
        bucket.add(rule, failing, item, detail);
    }

    /** Sahiplik sorunu: {@code null} = sorun yok (aktif takım). */
    static String ownerProblem(Long teamId, Map<Long, TeamFact> teams) {
        if (teamId == null) return OWNER_NONE;
        TeamFact t = teams.get(teamId);
        if (t == null) return OWNER_MISSING;
        if (!t.active()) return OWNER_INACTIVE;
        return null;
    }

    /** Yinelenen izleme grubu anahtarı: takım + tür + normalize hedef. */
    private static String dupGroup(MonitorFact m) {
        return m.teamId() + "|" + m.type().name() + "|" + m.dupKey();
    }

    /** Grup başına tutulacak (en küçük kimlikli) izleme — yalnız aktif, bağımsız, sahibi aktif takım olanlar. */
    static Map<String, Long> firstOfDuplicateGroups(List<MonitorFact> monitors, Map<Long, TeamFact> teams) {
        Map<String, Long> first = new HashMap<>();
        for (MonitorFact m : monitors) {
            if (!m.active() || !m.standalone() || m.dupKey() == null || ownerProblem(m.teamId(), teams) != null) continue;
            first.merge(dupGroup(m), m.id(), Math::min);
        }
        return first;
    }

    private static String monitorName(List<MonitorFact> monitors, NocType type, long id, Map<Long, String> cache) {
        // Yalnız yinelenen bulgularda çağrılır; tür+kimlik eşleşmesi doğrusal tarama yerine bir kez kurulan haritadan
        if (cache.isEmpty()) {
            for (MonitorFact m : monitors) cache.put(key(m.type(), m.id()), m.name());
        }
        return cache.get(key(type, id));
    }

    private static long key(NocType type, long id) {
        return ((long) type.ordinal() << 48) ^ id;
    }

    /** UTC ISO ({@code yyyy-MM-dd'T'HH:mm:ss}, isteğe bağlı kesir / {@code Z} / ofset) → an; bozuk değer → null. */
    static Instant parse(String iso) {
        if (iso == null) return null;
        String s = iso.trim();
        if (s.length() < 19 || s.length() > 40) return null;
        try { return Instant.parse(s); } catch (Exception ignored) { /* Z'siz biçim */ }
        try { return java.time.OffsetDateTime.parse(s).toInstant(); } catch (Exception ignored) { /* ofsetsiz */ }
        try {
            return LocalDateTime.parse(s.substring(0, 19)).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    // ── İç birikim ───────────────────────────────────────────────────────────────────────────────────

    /** Bulgu taşıyan öğenin kimliği. */
    private record Item(String kind, String type, long id, String name, String target, Long teamId, boolean manual) {
        static Item inventory(CertificateInventory r) {
            String target = r.getPort() == null || r.getPort() == 443 ? r.getDomain() : r.getDomain() + ":" + r.getPort();
            return new Item("inventory", NocType.SSL.name(), r.getId(), r.getDomain(), target, r.getTeamId(),
                    "MANUAL".equalsIgnoreCase(r.getCertSource()));
        }

        static Item monitor(MonitorFact m) {
            return new Item("monitor", m.type().name(), m.id(), m.name(), m.target(), m.teamId(), false);
        }

        static Item team(TeamFact t) {
            return new Item("team", "TEAM", t.id(), t.name(), null, t.id(), false);
        }
    }

    private static final class Acc {
        final Long teamId;
        final String teamName;
        final Map<DataQualityRule, int[]> counts = new EnumMap<>(DataQualityRule.class);
        final Map<DataQualityRule, List<Finding>> findings = new EnumMap<>(DataQualityRule.class);
        int items;
        int activeItems;

        Acc(Long teamId, String teamName) {
            this.teamId = teamId;
            this.teamName = teamName;
        }

        void add(DataQualityRule rule, boolean failing, Item it, Map<String, Object> detail) {
            int[] c = counts.computeIfAbsent(rule, k -> new int[2]);
            c[0]++;
            if (!failing) return;
            c[1]++;
            List<Finding> list = findings.computeIfAbsent(rule, k -> new ArrayList<>());
            Map<String, Object> facts = new LinkedHashMap<>();
            if (detail != null) detail.forEach((k, v) -> { if (k != null && v != null) facts.put(k, v); });   // Map.copyOf null'da patlar
            list.add(new Finding(rule, it.kind(), it.type(), it.id(), it.name(), it.target(), it.teamId(), it.manual(),
                    Collections.unmodifiableMap(facts)));
        }

        Bucket freeze() {
            Map<DataQualityRule, Count> c = new EnumMap<>(DataQualityRule.class);
            counts.forEach((k, v) -> c.put(k, new Count(v[0], v[1])));
            Map<DataQualityRule, List<Finding>> fz = new EnumMap<>(DataQualityRule.class);
            findings.forEach((k, v) -> {
                v.sort(Comparator.comparing((Finding x) -> x.name() == null ? "" : x.name().toLowerCase(Locale.ROOT))
                        .thenComparingLong(Finding::id));
                fz.put(k, List.copyOf(v));
            });
            return new Bucket(teamId, teamName, Collections.unmodifiableMap(c), Collections.unmodifiableMap(fz), items);
        }
    }
}

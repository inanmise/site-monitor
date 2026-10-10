package com.sitemonitor.service.quality;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.NocNotificationGroup;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.MonitorChangeLogRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.service.EscalationContactScope;
import com.sitemonitor.service.noc.NocConfigService;
import com.sitemonitor.service.noc.NocGroupService;
import com.sitemonitor.service.noc.NocType;
import com.sitemonitor.service.report.InventoryHygieneService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Veri kalitesi hesabının GİRDİLERİ — kurum geneli, SABİT sayıda sorguyla (takım ya da satır başına sorgu YOK).
 *
 * <p>Sorgu bütçesi (takım/kayıt sayısından bağımsız):
 * <ol>
 *   <li>takımlar + lider/müdür hesabının aktifliği — 1 (LEFT JOIN)</li>
 *   <li>takım başına aktif üye sayısı (birincil takım ∪ {@code app_user_teams}) — 1 (GROUP BY)</li>
 *   <li>aktif eskalasyon kişileri (takım kümesi) — 1; seviye kuralı {@link EscalationContactScope#matchesLevel}</li>
 *   <li>aktif bildirim grupları (takım kümesi) — 1</li>
 *   <li>envanter (silinmemiş) — 1</li>
 *   <li>envanter hijyeni ({@link InventoryHygieneService#analyze}; canlı sonuç önbellekli + zayıf algoritma) — ≤ 2</li>
 *   <li>son 7 gün sertifika tarama hata sayısı — 1 (GROUP BY)</li>
 *   <li>dokuz izleme türü — 9 (tür başına hafif kolonlar; sentetik script / sayfa hızı gövdeleri ÇEKİLMEZ)</li>
 *   <li>duraklatılanların "ne zamandır" bilgisi (değişiklik geçmişi) — en çok {@value #MAX_PAUSED_LOOKUP_CHUNKS}
 *       parça × {@value #PAUSED_CHUNK} kimlik</li>
 *   <li>7/24 yapılandırması + grupları — 2</li>
 * </ol>
 * Her adım kendi try/catch'inde: bir tablonun okunamaması (yükseltme sırasında eksik kolon) bütün ekranı düşürmez —
 * o adımın kuralı "bilinmiyor" sayılır ve günlüğe yazılır.
 */
@Slf4j
@Service
public class DataQualitySource {

    static final int PAUSED_CHUNK = 500;
    static final int MAX_PAUSED_LOOKUP_CHUNKS = 4;
    /** "Tekrar tekrar hata" penceresi (gün). */
    static final int ERROR_WINDOW_DAYS = 7;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final CertificateInventoryRepository inventoryRepo;
    private final EscalationContactRepository contactRepo;
    private final NotificationGroupRepository groupRepo;
    private final MonitorChangeLogRepository changeLogRepo;
    private final InventoryHygieneService hygiene;
    private final NocConfigService nocConfig;
    private final NocGroupService nocGroups;

    public DataQualitySource(JdbcTemplate jdbc, CertificateInventoryRepository inventoryRepo,
                             EscalationContactRepository contactRepo, NotificationGroupRepository groupRepo,
                             MonitorChangeLogRepository changeLogRepo, InventoryHygieneService hygiene,
                             NocConfigService nocConfig, NocGroupService nocGroups) {
        this.jdbc = jdbc;
        this.inventoryRepo = inventoryRepo;
        this.contactRepo = contactRepo;
        this.groupRepo = groupRepo;
        this.changeLogRepo = changeLogRepo;
        this.hygiene = hygiene;
        this.nocConfig = nocConfig;
        this.nocGroups = nocGroups;
    }

    // ── Girdi modeli ─────────────────────────────────────────────────────────────────────────────────

    /** Takım: lider / elle atanmış müdür kimliği varsa hesabın aktifliği ile. */
    public record TeamFact(long id, String name, boolean active, boolean hasEmail,
                           boolean leaderSet, boolean leaderActive, boolean managerSet, boolean managerActive) {}

    /** Takımın eskalasyon kapsamı: YÜKSEK / KRİTİK alarmı alan aktif kişi var mı. */
    public record Escalation(boolean high, boolean critical) {}

    /**
     * İzleme satırı (dokuz tür). {@code teamId} ETKİN takım (türev DNS/Port'ta envanterden). {@code dupKey} yinelenen
     * izleme anahtarı (tür içinde normalize hedef) — anlamlı olmayan türde {@code null}.
     */
    public record MonitorFact(NocType type, long id, String name, String target, Long teamId, boolean active,
                              boolean standalone, String groupName, String dupKey, String updatedAt) {}

    /** 7/24 durumu: kural uygulanabilir mi ({@code usable}); değilse neden. */
    public record NocState(boolean usable, String reason, NocConfigService.Config config, boolean anyGroup) {
        public static NocState unknown() { return new NocState(false, "UNKNOWN", null, false); }
    }

    /** Hesabın bütün girdileri. {@code errorCounts}/{@code hygieneCodes} okunamadıysa {@code null}. */
    public record Facts(List<TeamFact> teams, Map<Long, Integer> activeMembers, Map<Long, Escalation> escalation,
                        Set<Long> teamsWithGroupAddress, List<CertificateInventory> inventory,
                        Map<String, Set<String>> hygieneCodes, Map<String, String> hygieneErrors,
                        Map<String, Integer> errorCounts, List<MonitorFact> monitors,
                        Map<String, String> pausedSince, NocState noc) {}

    // ── Yükleme ──────────────────────────────────────────────────────────────────────────────────────

    public Facts load(Instant now) {
        List<TeamFact> teams = safe("takımlar", this::teams, List.of());
        List<Long> teamIds = teams.stream().map(TeamFact::id).toList();
        Map<Long, Integer> members = safe("üyeler", this::activeMembers, Map.of());
        Map<Long, Escalation> escalation = safe("eskalasyon", () -> escalation(teamIds), Map.of());
        Set<Long> groupAddress = safe("bildirim grupları", () -> groupAddress(teamIds), Set.of());
        List<CertificateInventory> inventory = safe("envanter", inventoryRepo::findByDeletedAtIsNullOrderByDomainAsc, List.of());

        Map<String, Set<String>> hygieneCodes = null;
        Map<String, String> hygieneErrors = new HashMap<>();
        try {
            hygieneCodes = hygieneCodes(hygiene.analyze(inventory, Integer.MAX_VALUE), hygieneErrors);
        } catch (Exception e) {
            log.warn("Veri kalitesi: envanter hijyeni okunamadı: {}", e.getMessage());
        }
        Map<String, Integer> errorCounts = safe("tarama hata sayıları", () -> errorCounts(now), null);
        List<MonitorFact> monitors = monitors(inventory);
        Map<String, String> paused = safe("duraklatma geçmişi", () -> pausedSince(inventory, monitors), Map.of());
        NocState noc = safe("7/24 yapılandırması", this::noc, NocState.unknown());
        return new Facts(teams, members, escalation, groupAddress, inventory, hygieneCodes, hygieneErrors,
                errorCounts, monitors, paused, noc);
    }

    static final String SQL_TEAMS = "SELECT t.id, t.name, t.active, t.email, t.leader_id, lu.active, t.manager_id, mu.active "
            + "FROM teams t LEFT JOIN app_users lu ON lu.id = t.leader_id LEFT JOIN app_users mu ON mu.id = t.manager_id";

    List<TeamFact> teams() {
        List<TeamFact> out = new ArrayList<>();
        jdbc.query(SQL_TEAMS, rs -> {
            Long leader = longOrNull(rs, 5);
            Long manager = longOrNull(rs, 7);
            String email = rs.getString(4);
            out.add(new TeamFact(rs.getLong(1), rs.getString(2), bool(rs, 3), email != null && !email.isBlank(),
                    leader != null, leader != null && bool(rs, 6), manager != null, manager != null && bool(rs, 8)));
        });
        return out;
    }

    /** Aktif üye = aktif hesap VE (birincil takım VEYA çoklu üyelik) — {@code SessionScope.memberTeamIds} ile aynı küme. */
    static final String SQL_MEMBERS = "SELECT x.team_id, COUNT(DISTINCT x.user_id) FROM ("
            + "SELECT u.id AS user_id, u.team_id AS team_id FROM app_users u WHERE u.active = TRUE AND u.team_id IS NOT NULL "
            + "UNION "
            + "SELECT m.user_id AS user_id, m.team_id AS team_id FROM app_user_teams m JOIN app_users u ON u.id = m.user_id "
            + "WHERE u.active = TRUE AND m.team_id IS NOT NULL"
            + ") x GROUP BY x.team_id";

    Map<Long, Integer> activeMembers() {
        Map<Long, Integer> out = new HashMap<>();
        jdbc.query(SQL_MEMBERS, rs -> { out.put(rs.getLong(1), rs.getInt(2)); });
        return out;
    }

    Map<Long, Escalation> escalation(List<Long> teamIds) {
        if (teamIds.isEmpty()) return Map.of();
        Map<Long, boolean[]> acc = new HashMap<>();
        for (EscalationContact c : contactRepo.findByTeamIdInAndActiveTrueOrderByRoleAsc(teamIds)) {
            if (c.getTeamId() == null) continue;
            boolean reachable = (c.getEmail() != null && !c.getEmail().isBlank())
                    || (c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank());
            if (!reachable) continue;   // adresi de webhook'u da olmayan kişi alıcı değildir
            boolean[] a = acc.computeIfAbsent(c.getTeamId(), k -> new boolean[2]);
            if (EscalationContactScope.matchesLevel(c, "HIGH")) a[0] = true;
            if (EscalationContactScope.matchesLevel(c, "CRITICAL")) a[1] = true;
        }
        Map<Long, Escalation> out = new HashMap<>();
        acc.forEach((k, v) -> out.put(k, new Escalation(v[0], v[1])));
        return out;
    }

    Set<Long> groupAddress(List<Long> teamIds) {
        if (teamIds.isEmpty()) return Set.of();
        Set<Long> out = new HashSet<>();
        for (NotificationGroup g : groupRepo.findByTeamIdInAndActiveTrueOrderByTeamIdAscNameAsc(teamIds)) {
            if (g.getTeamId() != null && g.getEmails() != null && !g.getEmails().isBlank()) out.add(g.getTeamId());
        }
        return out;
    }

    /** Hijyen bulguları → alan adı (küçük harf) → kod kümesi; hata metinleri ayrıca. */
    static Map<String, Set<String>> hygieneCodes(InventoryHygieneService.Result r, Map<String, String> errors) {
        Map<String, Set<String>> out = new HashMap<>();
        if (r == null) return out;
        for (InventoryHygieneService.Group g : r.groups()) {
            for (InventoryHygieneService.Finding f : g.samples()) {
                if (f.domain() == null) continue;
                String key = f.domain().toLowerCase(Locale.ROOT);
                out.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(f.codes());
                if (f.codes().contains("error") && f.detail() != null) errors.put(key, clip(f.detail(), 200));
            }
        }
        return out;
    }

    static final String SQL_ERROR_COUNTS = "SELECT LOWER(domain), COUNT(*) FROM certificate_checks "
            + "WHERE status = 'error' AND checked_at >= ? GROUP BY LOWER(domain)";

    Map<String, Integer> errorCounts(Instant now) {
        String since = ISO.format(now.minus(ERROR_WINDOW_DAYS, ChronoUnit.DAYS));
        Map<String, Integer> out = new HashMap<>();
        jdbc.query(SQL_ERROR_COUNTS, rs -> {
            String d = rs.getString(1);
            if (d != null) out.put(d, rs.getInt(2));
        }, since);
        return out;
    }

    // ── İzlemeler ────────────────────────────────────────────────────────────────────────────────────

    /** Türev DNS/Port için envanter: alan adı (küçük harf) → [takım, port]; yalnız AKTİF kayıt (süpürme ile aynı kural). */
    record Inv(Long teamId, int port) {}

    List<MonitorFact> monitors(List<CertificateInventory> inventory) {
        Map<String, Inv> live = new HashMap<>();
        for (CertificateInventory r : inventory) {
            if (r.getDomain() == null || Boolean.FALSE.equals(r.getActive())) continue;
            live.put(r.getDomain().toLowerCase(Locale.ROOT), new Inv(r.getTeamId(), r.getPort() == null ? 443 : r.getPort()));
        }
        List<MonitorFact> out = new ArrayList<>();
        for (NocType t : NocType.values()) {
            if (t == NocType.SSL) continue;   // envanter ayrı okunur
            try {
                load(t, live, out);
            } catch (Exception e) {
                log.warn("Veri kalitesi: {} izlemeleri okunamadı: {}", t, e.getMessage());
            }
        }
        return out;
    }

    /** Tek türün satırları — SQL hatası FIRLATIR (çağıran yakalar; test her türün sorgusunu doğrudan koşar). */
    void load(NocType t, Map<String, Inv> live, List<MonitorFact> out) {
        switch (t) {
            case HTTP -> jdbc.query("SELECT id, name, url, team_id, active, group_name, updated_at, method FROM http_monitors", rs -> {
                String url = rs.getString(3);
                String method = rs.getString(8);
                out.add(simple(t, rs, url, url == null ? null
                        : (method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT)) + " " + normUrl(url)));
            });
            case KEYWORD -> jdbc.query("SELECT id, name, url, team_id, active, group_name, updated_at, keyword FROM keyword_monitors", rs -> {
                String url = rs.getString(3);
                String kw = rs.getString(8);
                out.add(simple(t, rs, url, url == null ? null
                        : normUrl(url) + "|" + (kw == null ? "" : kw.trim().toLowerCase(Locale.ROOT))));
            });
            case PAGESPEED -> jdbc.query("SELECT id, name, url, team_id, active, group_name, updated_at FROM pagespeed_monitors", rs -> {
                String url = rs.getString(3);
                out.add(simple(t, rs, url, url == null ? null : normUrl(url)));
            });
            // Sayfa bütünlüğü ve sentetik: aynı hedefte farklı kurallar meşrudur → yinelenen sayılmaz (dupKey null)
            case PAGE -> jdbc.query("SELECT id, name, url, team_id, active, group_name, updated_at FROM page_monitors",
                    rs -> { out.add(simple(t, rs, rs.getString(3), null)); });
            case SCRIPTED -> jdbc.query("SELECT id, name, name, team_id, active, group_name, updated_at FROM scripted_monitors",
                    rs -> { out.add(simple(t, rs, null, null)); });
            case PING -> jdbc.query("SELECT id, name, host, team_id, active, group_name, updated_at FROM ping_monitors", rs -> {
                String host = rs.getString(3);
                out.add(simple(t, rs, host, lower(host)));
            });
            case DOMAIN -> jdbc.query("SELECT id, name, domain, team_id, active, group_name, updated_at FROM domain_monitors", rs -> {
                String d = rs.getString(3);
                out.add(simple(t, rs, d, lower(d)));
            });
            case DNS -> jdbc.query("SELECT id, name, domain, record_type, team_id, active, group_name, updated_at, standalone "
                    + "FROM dns_monitors WHERE deleted_at IS NULL", rs -> {
                String domain = rs.getString(3);
                String rt = rs.getString(4);
                boolean standalone = bool(rs, 9);
                Long stored = longOrNull(rs, 5);
                Inv inv = standalone || domain == null ? null : live.get(domain.toLowerCase(Locale.ROOT));
                if (!standalone && inv == null) return;   // türev ama envanteri canlı değil → süpürülmez, listelenmez
                Long team = standalone ? stored : (inv.teamId() != null ? inv.teamId() : stored);
                String target = rt == null ? domain : domain + " (" + rt + ")";
                String name = rs.getString(2);
                out.add(new MonitorFact(t, rs.getLong(1), name == null || name.isBlank() ? target : name, target, team,
                        bool(rs, 6), standalone, rs.getString(7),
                        domain == null ? null : lower(domain) + "|" + (rt == null ? "" : rt.trim().toUpperCase(Locale.ROOT)),
                        rs.getString(8)));
            });
            case PORT -> jdbc.query("SELECT id, name, host, port, team_id, active, group_name, updated_at, standalone "
                    + "FROM port_monitors WHERE deleted_at IS NULL", rs -> {
                String host = rs.getString(3);
                int port = rs.getInt(4);
                boolean standalone = bool(rs, 9);
                Long stored = longOrNull(rs, 5);
                Inv inv = standalone || host == null ? null : live.get(host.toLowerCase(Locale.ROOT));
                if (!standalone && (inv == null || inv.port() != port)) return;
                Long team = standalone ? stored : (inv.teamId() != null ? inv.teamId() : stored);
                String target = host + ":" + port;
                String name = rs.getString(2);
                out.add(new MonitorFact(t, rs.getLong(1), name == null || name.isBlank() ? target : name, target, team,
                        bool(rs, 6), standalone, rs.getString(7), host == null ? null : lower(host) + ":" + port,
                        rs.getString(8)));
            });
            default -> { /* SSL ayrı */ }
        }
    }

    private static MonitorFact simple(NocType t, ResultSet rs, String target, String dupKey) throws SQLException {
        String name = rs.getString(2);
        return new MonitorFact(t, rs.getLong(1), name == null || name.isBlank() ? target : name, target,
                longOrNull(rs, 4), bool(rs, 5), true, rs.getString(6), dupKey, rs.getString(7));
    }

    /**
     * URL normalizasyonu — yalnız şema ve ana makine küçük harfe; yol/sorgu olduğu gibi (yol büyük-küçük harf
     * duyarlıdır). Tek başına "/" yol ve boş yol aynıdır. Doğrusal tarama (regex yok).
     */
    static String normUrl(String url) {
        if (url == null) return null;
        String s = url.trim();
        if (s.length() > 2048) s = s.substring(0, 2048);
        int scheme = s.indexOf("://");
        if (scheme < 0) return s;
        int pathStart = s.indexOf('/', scheme + 3);
        String head = (pathStart < 0 ? s : s.substring(0, pathStart)).toLowerCase(Locale.ROOT);
        String rest = pathStart < 0 ? "" : s.substring(pathStart);
        if ("/".equals(rest)) rest = "";
        return head + rest;
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    // ── Duraklatma geçmişi ───────────────────────────────────────────────────────────────────────────

    /**
     * Duraklatılmış izleme/envanter → {@code active}'in en son değiştiği an ("TÜR:kimlik" → ISO). Kimlikler parça
     * parça ({@value #PAUSED_CHUNK}) sorulur ve en çok {@value #MAX_PAUSED_LOOKUP_CHUNKS} parça — kalan (aşırı büyük
     * kurulum) son güncelleme zamanına düşer.
     */
    Map<String, String> pausedSince(List<CertificateInventory> inventory, List<MonitorFact> monitors) {
        Set<Long> ids = new LinkedHashSet<>();
        for (CertificateInventory r : inventory) if (Boolean.FALSE.equals(r.getActive()) && r.getId() != null) ids.add(r.getId());
        for (MonitorFact m : monitors) if (!m.active()) ids.add(m.id());
        if (ids.isEmpty()) return Map.of();
        List<Long> all = new ArrayList<>(ids);
        Map<String, String> out = new HashMap<>();
        for (int i = 0, chunk = 0; i < all.size() && chunk < MAX_PAUSED_LOOKUP_CHUNKS; i += PAUSED_CHUNK, chunk++) {
            List<Long> part = all.subList(i, Math.min(all.size(), i + PAUSED_CHUNK));
            for (Object[] row : changeLogRepo.lastActiveChange(part)) {
                if (row == null || row.length < 3 || row[0] == null || row[1] == null || row[2] == null) continue;
                out.put(row[0] + ":" + ((Number) row[1]).longValue(), String.valueOf(row[2]));
            }
        }
        return out;
    }

    // ── 7/24 ─────────────────────────────────────────────────────────────────────────────────────────

    NocState noc() {
        NocConfigService.Config cfg = nocConfig.get();
        List<NocNotificationGroup> groups = nocGroups.list();
        boolean anyGroup = NocGroupService.anyUsable(groups);
        if (!anyGroup) return new NocState(false, "NO_ACTIVE_GROUP", cfg, false);
        if (cfg != null && !cfg.typeEnabled(NocType.SSL)) return new NocState(false, "TYPE_DISABLED", cfg, true);
        return new NocState(true, null, cfg, true);
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private interface Step<T> { T get() throws Exception; }

    private static <T> T safe(String what, Step<T> step, T fallback) {
        try {
            return step.get();
        } catch (Exception e) {
            log.warn("Veri kalitesi: {} okunamadı: {}", what, e.getMessage());
            return fallback;
        }
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static Long longOrNull(ResultSet rs, int col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static boolean bool(ResultSet rs, int col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return !rs.wasNull() && v;
    }
}

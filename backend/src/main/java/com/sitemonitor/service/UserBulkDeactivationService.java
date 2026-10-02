package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.UserBulkOperation;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.UserBulkOperationRepository;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SİSTEM GENELİ toplu pasife alma (2026-10-02, kullanıcı kararı: "admin sistemdeki kullanıcıları toplu pasife alabilsin;
 * admin kullanıcılar hariç"). Yalnız GLOBAL yönetici çağırır (kapı {@code UserBulkDeactivationController}'da).
 *
 * <h3>Ölçüt (VE ile birleşir)</h3>
 * <ul>
 *   <li>{@code scope}: {@code all} (admin olmayan tüm kullanıcılar, varsayılan) | {@code teams} — kullanıcı seçili
 *       takımlardan birinin ÜYESİYSE eşleşir: birincil takım ({@code app_users.team_id}) YA DA çoklu üyelik
 *       ({@code app_user_teams}).</li>
 *   <li>{@code inactive_days} (isteğe bağlı, 1–3650): son girişi N günden ESKİ olanlar. Hiç giriş yapmamış hesap
 *       {@code include_never_logged_in} (varsayılan AÇIK) ile dâhildir — ama YALNIZ hesap en az N gündür kayıtlıysa
 *       ({@code created_at}; damga yoksa eski hesap sayılır). Dün açılmış, henüz girmemiş hesap kapanmaz.</li>
 *   <li>{@code auth_source}: {@code ALL} | {@code LDAP} | {@code LOCAL} (LDAP olmayan her şey yereldir).</li>
 *   <li>{@code system_role} (isteğe bağlı): {@code USER} | {@code TEAM_ADMIN} | {@code AUDIT}. {@code ADMIN} seçilemez.</li>
 * </ul>
 *
 * <h3>Her zaman hariç (sunucuda, seçilemez)</h3>
 * Ölçüte uyan kullanıcılar sırayla ayrılır: (1) işlemi yapan yöneticinin kendisi → {@code self}; (2) {@code systemRole =
 * ADMIN} olan HER hesap (global ve kapsamlı müdür) → {@code admins}; (3) zaten pasif olanlar → {@code already_inactive}.
 * Kalanlar hedeftir. Önizleme her grubu sayısıyla raporlar.
 *
 * <h3>Uygulama</h3>
 * Hedef kümesi uygulama anında YENİDEN hesaplanır; boyutu önizlemede onaylanan sayıdan farklıysa 409
 * ({@link #CODE_LIST_CHANGED}). Her kullanıcı tek tek {@link UserService#updateUser} (active=false) yolundan geçer —
 * tekil/seçimli pasifleştirmeyle AYNI yol: TERMINATED sentinel, remember-me token'larının silinmesi, pasif kapısı ve
 * bildirim süzgecinin önbellek tazelemesi. Tek dev transaction YOK: her kullanıcı kendi transaction'ında, 200'lük
 * dilimlerle; bir kullanıcının hatası işlemi durdurmaz. Kullanıcı başına dışlama uygulama anında yeniden denetlenir
 * (bu arada ADMIN olmuş / pasife alınmış hesap atlanır). Üst sınır {@link #MAX_TARGETS}.
 *
 * <h3>Geri alma kuralı</h3>
 * Bir işlemin pasife aldığı kullanıcı, geri almada YALNIZ şu koşulların hepsi sağlanırsa yeniden aktifleşir:
 * hâlâ pasif; hesabın tek-oturum kaydında hâlâ BU işlemin yazdığı TERMINATED işareti duruyor (sonradan tekil ya da
 * toplu her pasifleştirme yeni bir işaret yazar, yani "bu arada başkası yeniden pasife aldı mı?" sorusunun kesin
 * cevabı); daha sonra başlamış ve geri alınmamış bir toplu işlemin listesinde değil; rolü bu arada ADMIN olmamış
 * (pasif bir admin hesabını açmak elle verilecek bir karardır). Geri alma idempotenttir: işlem atomik olarak sahiplenilir,
 * ikinci çağrı 409.
 */
@Slf4j
@Service
public class UserBulkDeactivationService {

    /** Tek işlemde pasife alınabilecek azami kullanıcı (sunucuyu korur; daha fazlası ölçüt daraltılarak bölünür). */
    public static final int MAX_TARGETS = 5000;
    /** Dilim boyutu — işlem kaydı her dilimden sonra güncellenir (pod ölse de o ana dek yapılan geri alınabilir). */
    static final int CHUNK = 200;
    public static final int MAX_NOTE = 500;
    public static final int MAX_INACTIVE_DAYS = 3650;
    public static final int MAX_TEAMS = 500;
    /** Önizlemeden sonra hedef listesi değişti → 409 gövdesindeki {@code code}. */
    public static final String CODE_LIST_CHANGED = "BULK_LIST_CHANGED";
    public static final Set<String> FILTERABLE_ROLES = Set.of("USER", "TEAM_ADMIN", "AUDIT");

    /** Uygulama / geri alma atlama nedenleri (arayüz {@code ubd.skip.<KOD>} ile çevirir). */
    public static final String SKIP_SELF = "SELF";
    public static final String SKIP_NOW_ADMIN = "NOW_ADMIN";
    public static final String SKIP_ALREADY_INACTIVE = "ALREADY_INACTIVE";
    public static final String SKIP_NOT_FOUND = "NOT_FOUND";
    public static final String SKIP_ALREADY_ACTIVE = "ALREADY_ACTIVE";
    public static final String SKIP_CHANGED_SINCE = "CHANGED_SINCE";
    public static final String SKIP_LATER_OPERATION = "LATER_OPERATION";

    /** Kullanıcı başına denetim farkı — {@code AdminController.applyUserUpdate} ile aynı alanlar. */
    private static final String[] AUDIT_FIELDS =
            {"systemRole", "teamId", "teamIds", "active", "orgRole", "displayName", "email", "employeeId", "managerId"};

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ISO_Z =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final AppUserRepository userRepo;
    private final UserBulkOperationRepository opRepo;
    private final UserService userService;
    private final AuditService auditService;

    /** Bu pod'da aynı anda tek toplu pasifleştirme (iki yöneticinin çakışan çalıştırması sunucuyu ikiye katlamasın). */
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Clock clock = Clock.systemUTC();

    public UserBulkDeactivationService(AppUserRepository userRepo, UserBulkOperationRepository opRepo,
                                       UserService userService, AuditService auditService) {
        this.userRepo = userRepo;
        this.opRepo = opRepo;
        this.userService = userService;
        this.auditService = auditService;
    }

    /** Test kancası. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ── Ölçüt ────────────────────────────────────────────────────────────────

    /** Normalize edilmiş ölçüt. {@code teamIds} yalnız {@code scope=teams} iken dolu; {@code inactiveDays} null = süzgeç yok. */
    public record Criteria(String scope, List<Long> teamIds, Integer inactiveDays, boolean includeNeverLoggedIn,
                           String authSource, String systemRole) {

        public boolean teamsScope() {
            return "teams".equals(scope);
        }

        /** İstek gövdesinden ölçüt — {@code {criteria:{…}}} ya da doğrudan ölçüt nesnesi. Geçersiz değer → 400. */
        @SuppressWarnings("unchecked")
        public static Criteria from(Map<String, Object> body) {
            Map<String, Object> c = body == null ? Map.of()
                    : body.get("criteria") instanceof Map<?, ?> m ? (Map<String, Object>) m : body;
            String scope = str(c.get("scope"));
            scope = scope == null ? "all" : scope.toLowerCase(Locale.ROOT);
            if (!scope.equals("all") && !scope.equals("teams")) {
                throw new IllegalArgumentException(Msg.t("Geçersiz kapsam: ", "Invalid scope: ") + scope);
            }
            List<Long> teams = new ArrayList<>();
            if (scope.equals("teams")) {
                if (c.get("team_ids") instanceof Collection<?> col) {
                    for (Object o : col) {
                        Long v = toLong(o);
                        if (v != null && !teams.contains(v)) teams.add(v);
                    }
                }
                if (teams.isEmpty()) {
                    throw new IllegalArgumentException(Msg.t("En az bir takım seçin.", "Select at least one team."));
                }
                if (teams.size() > MAX_TEAMS) {
                    throw new IllegalArgumentException(Msg.t("En fazla " + MAX_TEAMS + " takım seçilebilir.",
                            "You can select at most " + MAX_TEAMS + " teams."));
                }
            }
            Integer days = null;
            Object rawDays = c.get("inactive_days");
            if (rawDays != null && !(rawDays instanceof String s && s.isBlank())) {
                Long d = toLong(rawDays);
                if (d == null || d < 1 || d > MAX_INACTIVE_DAYS) {
                    throw new IllegalArgumentException(Msg.t(
                            "Gün sayısı 1 ile " + MAX_INACTIVE_DAYS + " arasında olmalı.",
                            "The number of days must be between 1 and " + MAX_INACTIVE_DAYS + "."));
                }
                days = d.intValue();
            }
            Object never = c.get("include_never_logged_in");
            boolean includeNever = never == null || Boolean.TRUE.equals(never) || "true".equalsIgnoreCase(String.valueOf(never));
            String source = str(c.get("auth_source"));
            source = source == null ? "ALL" : source.toUpperCase(Locale.ROOT);
            if (!Set.of("ALL", "LDAP", "LOCAL").contains(source)) {
                throw new IllegalArgumentException(Msg.t("Geçersiz hesap kaynağı: ", "Invalid account source: ") + source);
            }
            String role = str(c.get("system_role"));
            role = role == null ? null : role.toUpperCase(Locale.ROOT);
            if ("ADMIN".equals(role)) {
                throw new IllegalArgumentException(Msg.t(
                        "Admin hesapları toplu pasife alınamaz.", "Admin accounts cannot be bulk-deactivated."));
            }
            if (role != null && !FILTERABLE_ROLES.contains(role)) {
                throw new IllegalArgumentException(Msg.t("Geçersiz rol: ", "Invalid role: ") + role);
            }
            return new Criteria(scope, List.copyOf(teams), days, includeNever, source, role);
        }

        /** Tel biçimi (önizleme yanıtı + işlem kaydı). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scope", scope);
            m.put("team_ids", teamIds);
            m.put("inactive_days", inactiveDays);
            m.put("include_never_logged_in", includeNeverLoggedIn);
            m.put("auth_source", authSource);
            m.put("system_role", systemRole);
            return m;
        }
    }

    /** Projeksiyon satırından aday. */
    record Candidate(Long id, String username, String displayName, String email, String systemRole, String authSource,
                     String lastLoginAt, String createdAt, boolean active, List<Long> teamIds, String employeeId) {

        boolean admin() {
            return "ADMIN".equalsIgnoreCase(systemRole);
        }

        boolean ldap() {
            return "LDAP".equalsIgnoreCase(authSource);
        }

        String sortKey() {
            String n = displayName != null && !displayName.isBlank() ? displayName : username;
            return n == null ? "" : n.toLowerCase(Locale.ROOT);
        }
    }

    /** Ölçüt + dışlama sonucu. {@code targets} sıralı ve tam (önizleme kırpmayı kendisi yapar). */
    record Plan(List<Candidate> targets, int admins, int self, int alreadyInactive, Instant cutoff) {
        int total() {
            return targets.size();
        }
    }

    // ── Hesap ────────────────────────────────────────────────────────────────

    Plan plan(Criteria c, Long operatorId) {
        Instant now = clock.instant();
        Instant cutoff = c.inactiveDays() == null ? null : now.minus(c.inactiveDays(), ChronoUnit.DAYS);
        Set<Long> scopeTeams = c.teamsScope() ? new HashSet<>(c.teamIds()) : Set.of();
        List<Candidate> targets = new ArrayList<>();
        int admins = 0, self = 0, inactive = 0;
        for (Candidate u : candidates()) {
            if (!matches(u, c, scopeTeams, cutoff)) continue;
            if (operatorId != null && operatorId.equals(u.id())) { self++; continue; }
            if (u.admin()) { admins++; continue; }
            if (!u.active()) { inactive++; continue; }
            targets.add(u);
        }
        targets.sort(Comparator.comparing(Candidate::sortKey).thenComparing(Candidate::id));
        return new Plan(targets, admins, self, inactive, cutoff);
    }

    private List<Candidate> candidates() {
        Map<Long, LinkedHashSet<Long>> memberships = new HashMap<>();
        List<Object[]> pairs = userRepo.findAllTeamMembershipPairs();
        if (pairs != null) {
            for (Object[] p : pairs) {
                if (p == null || p.length < 2) continue;
                Long uid = toLong(p[0]);
                Long tid = toLong(p[1]);
                if (uid != null && tid != null) memberships.computeIfAbsent(uid, k -> new LinkedHashSet<>()).add(tid);
            }
        }
        List<Candidate> out = new ArrayList<>();
        List<Object[]> rows = userRepo.findBulkCandidateRows();
        if (rows == null) return out;
        for (Object[] r : rows) {
            if (r == null || r.length < 11) continue;
            Long id = toLong(r[0]);
            if (id == null) continue;
            Long primary = toLong(r[9]);
            LinkedHashSet<Long> teams = new LinkedHashSet<>();
            if (primary != null) teams.add(primary);
            LinkedHashSet<Long> extra = memberships.get(id);
            if (extra != null) teams.addAll(extra);
            out.add(new Candidate(id, str(r[1]), str(r[2]), str(r[3]), str(r[4]), str(r[5]), str(r[6]), str(r[7]),
                    Boolean.TRUE.equals(r[8]), List.copyOf(teams), str(r[10])));
        }
        return out;
    }

    static boolean matches(Candidate u, Criteria c, Set<Long> scopeTeams, Instant cutoff) {
        if (c.teamsScope() && u.teamIds().stream().noneMatch(scopeTeams::contains)) return false;
        if ("LDAP".equals(c.authSource()) && !u.ldap()) return false;
        if ("LOCAL".equals(c.authSource()) && u.ldap()) return false;
        if (c.systemRole() != null && !c.systemRole().equalsIgnoreCase(u.systemRole())) return false;
        if (cutoff != null) {
            if (u.lastLoginAt() == null || u.lastLoginAt().isBlank()) {
                if (!c.includeNeverLoggedIn()) return false;
                // Hiç girmemiş: hesap en az N gündür kayıtlı olmalı (yeni açılmış hesap kapanmasın). Damga yok = eski hesap.
                Instant created = parseStamp(u.createdAt());
                if (created != null && !created.isBefore(cutoff)) return false;
            } else {
                Instant last = parseStamp(u.lastLoginAt());
                // Okunamayan damga = bilinmiyor → güvenli taraf: eşleşmez (yanlış veriyle hesap kapatılmaz).
                if (last == null || !last.isBefore(cutoff)) return false;
            }
        }
        return true;
    }

    // ── Önizleme ─────────────────────────────────────────────────────────────

    public Map<String, Object> preview(Criteria c, Long operatorId) {
        Plan p = plan(c, operatorId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", p.total());
        out.put("max", MAX_TARGETS);
        out.put("over_limit", p.total() > MAX_TARGETS);
        List<Map<String, Object>> rows = new ArrayList<>(Math.min(p.total(), MAX_TARGETS));
        for (Candidate u : p.targets()) {
            if (rows.size() >= MAX_TARGETS) break;
            rows.add(row(u));
        }
        out.put("targets", rows);
        out.put("targets_truncated", p.total() > MAX_TARGETS);
        Map<String, Object> excluded = new LinkedHashMap<>();
        excluded.put("admins", p.admins());
        excluded.put("self", p.self());
        excluded.put("already_inactive", p.alreadyInactive());
        out.put("excluded", excluded);
        out.put("criteria", c.toMap());
        out.put("inactive_cutoff", p.cutoff() == null ? null : ISO_Z.format(p.cutoff()));
        return out;
    }

    private static Map<String, Object> row(Candidate u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("username", u.username());
        m.put("display_name", u.displayName());
        m.put("email", u.email());
        m.put("employee_id", u.employeeId());
        m.put("system_role", u.systemRole());
        m.put("auth_source", u.ldap() ? "LDAP" : "LOCAL");
        m.put("team_ids", u.teamIds());
        m.put("last_login_at", u.lastLoginAt());
        return m;
    }

    // ── Uygulama ─────────────────────────────────────────────────────────────

    /** Önizlemeden sonra hedef sayısı değişti → 409 + {@link #CODE_LIST_CHANGED}. */
    public static class ListChangedException extends IllegalStateException {
        private final int currentCount;

        public ListChangedException(String message, int currentCount) {
            super(message);
            this.currentCount = currentCount;
        }

        public int currentCount() {
            return currentCount;
        }
    }

    public Map<String, Object> apply(Criteria c, Integer expectedCount, String note, HttpSession session) {
        if (expectedCount == null || expectedCount < 0) {
            throw new IllegalArgumentException(Msg.t("Onaylanan kullanıcı sayısı (expected_count) zorunlu.",
                    "The confirmed user count (expected_count) is required."));
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > MAX_NOTE) {
            throw new IllegalArgumentException(Msg.t("Not en fazla " + MAX_NOTE + " karakter olabilir.",
                    "The note can be at most " + MAX_NOTE + " characters."));
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException(Msg.t("Başka bir toplu pasife alma işlemi sürüyor; bitince yeniden deneyin.",
                    "Another bulk deactivation is running; try again when it finishes."));
        }
        Map<String, Object> out;
        try {
            out = doApply(c, expectedCount, cleanNote, session);
        } finally {
            running.set(false);
        }
        // Özet denetim satırı — kullanıcı başına USER_UPDATE satırları doApply içinde yazıldı.
        auditService.recordAction("USER_BULK_DEACTIVATE", session, "USER_BULK_OPERATION", String.valueOf(out.get("operation_id")),
                AuditDetail.of("operation_id", out.get("operation_id"), "criteria", c.toMap(), "requested", out.get("total"),
                        "ok", out.get("ok"), "failed", out.get("failed"), "skipped", out.get("skipped"), "note", cleanNote), null);
        return out;
    }

    private Map<String, Object> doApply(Criteria c, int expectedCount, String note, HttpSession session) {
        Long operatorId = longAttr(session, "userId");
        String actor = strAttr(session, "username");
        Plan p = plan(c, operatorId);
        if (p.total() > MAX_TARGETS) {
            throw new IllegalArgumentException(Msg.t(
                    "Tek seferde en fazla " + MAX_TARGETS + " kullanıcı pasife alınabilir; ölçütü daraltın.",
                    "At most " + MAX_TARGETS + " users can be deactivated at once; narrow the criteria."));
        }
        if (p.total() != expectedCount) {
            throw new ListChangedException(Msg.t(
                    "Liste değişti, önizlemeyi yenileyin (şimdi " + p.total() + " kullanıcı, onaylanan " + expectedCount + ").",
                    "The list has changed, refresh the preview (now " + p.total() + " users, confirmed " + expectedCount + ")."),
                    p.total());
        }
        if (p.total() == 0) {
            throw new IllegalArgumentException(Msg.t("Ölçüte uyan, pasife alınacak kullanıcı yok.",
                    "No users match the criteria to deactivate."));
        }

        UserBulkOperation op = new UserBulkOperation();
        op.setKind(UserBulkOperation.KIND_DEACTIVATE);
        op.setStatus(UserBulkOperation.STATUS_RUNNING);
        op.setCreatedAt(ISO_Z.format(clock.instant()));
        op.setActor(actor);
        op.setActorId(operatorId);
        op.setCriteriaJson(toJson(c.toMap()));
        op.setNote(note);
        op.setTargetCount(p.total());
        op.setOkCount(0);
        op.setFailedCount(0);
        op.setSkippedCount(0);
        op.setUserIds("[]");
        op.setMarkers("{}");
        op = opRepo.save(op);
        Long opId = op.getId();
        log.info("Toplu pasife alma başladı: işlem={} yönetici={} hedef={} ölçüt={}", opId, actor, p.total(), c.toMap());

        List<Long> okIds = new ArrayList<>();
        Map<String, String> markers = new LinkedHashMap<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Candidate> targets = p.targets();
        boolean finished = false;
        try {
            for (int from = 0; from < targets.size(); from += CHUNK) {
                for (Candidate cand : targets.subList(from, Math.min(targets.size(), from + CHUNK))) {
                    deactivateOne(cand, operatorId, opId, session, okIds, markers, failures, skipped);
                }
                stamp(op, okIds, markers, failures, skipped);
                op = opRepo.save(op);
            }
            finished = true;
        } finally {
            // Yarıda kesilse de (kayıt yazılamadı vb.) o ana dek pasife alınanlar DONE satırında kalır → geri alınabilir.
            stamp(op, okIds, markers, failures, skipped);
            op.setStatus(UserBulkOperation.STATUS_DONE);
            op.setFinishedAt(ISO_Z.format(clock.instant()));
            try {
                op = opRepo.save(op);
            } catch (RuntimeException e) {
                if (finished) throw e;
                log.warn("Toplu pasife alma kaydı kapatılamadı (işlem={}): {}", opId, e.toString());
            }
        }
        log.info("Toplu pasife alma bitti: işlem={} başarılı={} başarısız={} atlanan={}",
                opId, okIds.size(), failures.size(), skipped.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operation_id", opId);
        out.put("total", p.total());
        out.put("ok", okIds.size());
        out.put("failed", failures.size());
        out.put("skipped", skipped.size());
        out.put("failures", failures);
        out.put("skipped_rows", skipped);
        out.put("operation", summary(op));
        return out;
    }

    private static void stamp(UserBulkOperation op, List<Long> okIds, Map<String, String> markers,
                              List<Map<String, Object>> failures, List<Map<String, Object>> skipped) {
        op.setUserIds(toJson(okIds));
        op.setMarkers(toJson(markers));
        op.setOkCount(okIds.size());
        op.setFailedCount(failures.size());
        op.setSkippedCount(skipped.size());
    }

    /** Tek kullanıcı: dışlamaları TAZE kayıtta yeniden denetle, sonra tekil düzenlemeyle aynı yoldan pasife al. */
    private void deactivateOne(Candidate cand, Long operatorId, Long opId, HttpSession session, List<Long> okIds,
                               Map<String, String> markers, List<Map<String, Object>> failures,
                               List<Map<String, Object>> skipped) {
        Long id = cand.id();
        try {
            AppUser u = userRepo.findById(id).orElse(null);
            if (u == null) { skipped.add(skipRow(id, cand.username(), SKIP_NOT_FOUND)); return; }
            if (operatorId != null && operatorId.equals(id)) { skipped.add(skipRow(id, u.getUsername(), SKIP_SELF)); return; }
            if ("ADMIN".equalsIgnoreCase(u.getSystemRole())) {
                log.warn("Toplu pasife alma: kullanıcı bu arada ADMIN olmuş, atlandı (işlem={} user={})", opId, u.getUsername());
                skipped.add(skipRow(id, u.getUsername(), SKIP_NOW_ADMIN));
                return;
            }
            if (!Boolean.TRUE.equals(u.getActive())) { skipped.add(skipRow(id, u.getUsername(), SKIP_ALREADY_INACTIVE)); return; }
            Map<String, Object> before = AuditDiff.snapshot(u, AUDIT_FIELDS);
            AppUser saved = userService.updateUser(id, null, null, null, null, null, false, null);
            okIds.add(id);
            markers.put(String.valueOf(id), saved.getActiveSessionId());
            auditService.recordAction("USER_UPDATE", session, "USER", String.valueOf(id),
                    AuditDetail.of("username", saved.getUsername(), "via", "bulk_deactivate", "operation_id", opId),
                    AuditDiff.diff(before, AuditDiff.snapshot(saved, AUDIT_FIELDS)));
        } catch (RuntimeException e) {
            log.warn("Toplu pasife alma: kullanıcı pasife alınamadı (işlem={} id={}): {}", opId, id, e.toString());
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", id);
            f.put("username", cand.username());
            f.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            failures.add(f);
        }
    }

    private static Map<String, Object> skipRow(Long id, String username, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("username", username);
        m.put("reason", reason);
        return m;
    }

    // ── Geri alma ────────────────────────────────────────────────────────────

    public Map<String, Object> undo(Long opId, HttpSession session) {
        UserBulkOperation op = opRepo.findById(opId).orElseThrow(() -> new NoSuchElementException(
                Msg.t("Toplu işlem bulunamadı: #", "Bulk operation not found: #") + opId));
        if (op.getUndoneAt() != null) {
            throw new IllegalStateException(Msg.t("Bu işlem zaten geri alındı.", "This operation has already been undone."));
        }
        if (!UserBulkOperation.STATUS_DONE.equals(op.getStatus())) {
            throw new IllegalStateException(Msg.t("İşlem henüz bitmedi; geri alınamaz.", "The operation hasn't finished; it can't be undone yet."));
        }
        Long actorId = longAttr(session, "userId");
        String actor = strAttr(session, "username");
        if (opRepo.markUndone(opId, ISO_Z.format(clock.instant()), actor, actorId) == 0) {
            throw new IllegalStateException(Msg.t("Bu işlem zaten geri alındı.", "This operation has already been undone."));
        }
        List<Long> ids = parseIds(op.getUserIds());
        Map<String, String> markers = parseMarkers(op.getMarkers());
        Set<Long> inLaterOps = new HashSet<>();
        for (UserBulkOperation later : opRepo.findByKindAndIdGreaterThanAndUndoneAtIsNull(op.getKind(), opId)) {
            inLaterOps.addAll(parseIds(later.getUserIds()));
        }

        int ok = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            for (Long id : ids.subList(from, Math.min(ids.size(), from + CHUNK))) {
                String username = null;
                try {
                    AppUser u = userRepo.findById(id).orElse(null);
                    if (u == null) { skipped.add(skipRow(id, null, SKIP_NOT_FOUND)); continue; }
                    username = u.getUsername();
                    if (Boolean.TRUE.equals(u.getActive())) { skipped.add(skipRow(id, username, SKIP_ALREADY_ACTIVE)); continue; }
                    if ("ADMIN".equalsIgnoreCase(u.getSystemRole())) { skipped.add(skipRow(id, username, SKIP_NOW_ADMIN)); continue; }
                    if (inLaterOps.contains(id)) { skipped.add(skipRow(id, username, SKIP_LATER_OPERATION)); continue; }
                    String marker = markers.get(String.valueOf(id));
                    if (marker != null && !marker.equals(u.getActiveSessionId())) {
                        skipped.add(skipRow(id, username, SKIP_CHANGED_SINCE));
                        continue;
                    }
                    Map<String, Object> before = AuditDiff.snapshot(u, AUDIT_FIELDS);
                    AppUser saved = userService.updateUser(id, null, null, null, null, null, true, null);
                    ok++;
                    auditService.recordAction("USER_UPDATE", session, "USER", String.valueOf(id),
                            AuditDetail.of("username", saved.getUsername(), "via", "bulk_deactivate_undo", "operation_id", opId),
                            AuditDiff.diff(before, AuditDiff.snapshot(saved, AUDIT_FIELDS)));
                } catch (RuntimeException e) {
                    log.warn("Toplu pasife alma geri alınırken kullanıcı açılamadı (işlem={} id={}): {}", opId, id, e.toString());
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("id", id);
                    f.put("username", username);
                    f.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    failures.add(f);
                }
            }
        }
        opRepo.recordUndoResult(opId, ok, skipped.size(), failures.size());
        auditService.recordAction("USER_BULK_DEACTIVATE_UNDO", session, "USER_BULK_OPERATION", String.valueOf(opId),
                AuditDetail.of("operation_id", opId, "users", ids.size(), "reactivated", ok,
                        "skipped", skipped.size(), "failed", failures.size()), null);
        log.info("Toplu pasife alma geri alındı: işlem={} yönetici={} açılan={} atlanan={} başarısız={}",
                opId, actor, ok, skipped.size(), failures.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operation_id", opId);
        out.put("ok", ok);
        out.put("skipped", skipped.size());
        out.put("failed", failures.size());
        out.put("failures", failures);
        out.put("skipped_rows", skipped);
        return out;
    }

    // ── Geçmiş ───────────────────────────────────────────────────────────────

    public List<Map<String, Object>> history() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserBulkOperation op : opRepo.findTop20ByOrderByIdDesc()) out.add(summary(op));
        return out;
    }

    Map<String, Object> summary(UserBulkOperation op) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", op.getId());
        m.put("kind", op.getKind());
        m.put("status", op.getStatus());
        m.put("created_at", op.getCreatedAt());
        m.put("finished_at", op.getFinishedAt());
        m.put("actor", op.getActor());
        m.put("note", op.getNote());
        m.put("criteria", parseMap(op.getCriteriaJson()));
        m.put("target_count", op.getTargetCount());
        m.put("ok_count", op.getOkCount());
        m.put("failed_count", op.getFailedCount());
        m.put("skipped_count", op.getSkippedCount());
        m.put("undone_at", op.getUndoneAt());
        m.put("undone_by", op.getUndoneBy());
        m.put("undo_ok_count", op.getUndoOkCount());
        m.put("undo_skipped_count", op.getUndoSkippedCount());
        m.put("undo_failed_count", op.getUndoFailedCount());
        m.put("can_undo", UserBulkOperation.STATUS_DONE.equals(op.getStatus()) && op.getUndoneAt() == null
                && op.getOkCount() != null && op.getOkCount() > 0);
        return m;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    /** ISO damga → Instant; {@code Z}'li/Z'siz, kesirli, ofsetli ya da yalnız tarih biçimleri. Okunamazsa null. */
    static Instant parseStamp(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim().replace(' ', 'T');
        try { return Instant.parse(v); } catch (Exception ignored) { /* sonraki biçim */ }
        try { return OffsetDateTime.parse(v).toInstant(); } catch (Exception ignored) { /* sonraki biçim */ }
        try { return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC); } catch (Exception ignored) { /* sonraki biçim */ }
        try { return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v).atStartOfDay(ZoneOffset.UTC).toInstant(); }
        catch (Exception ignored) { return null; }
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON yazılamadı: " + e.getMessage(), e);
        }
    }

    static List<Long> parseIds(String json) {
        List<Long> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            List<Object> raw = JSON.readValue(json, new TypeReference<List<Object>>() { });
            for (Object o : raw) {
                Long v = toLong(o);
                if (v != null) out.add(v);
            }
        } catch (Exception e) {
            log.warn("Toplu işlem kullanıcı listesi okunamadı: {}", e.toString());
        }
        return out;
    }

    private static Map<String, String> parseMarkers(String json) {
        Map<String, String> out = new HashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            Map<String, Object> raw = JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
            raw.forEach((k, v) -> { if (v != null) out.put(k, v.toString()); });
        } catch (Exception e) {
            log.warn("Toplu işlem işaretleri okunamadı: {}", e.toString());
        }
        return out;
    }

    private static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString().trim()); } catch (Exception e) { return null; }
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Long longAttr(HttpSession session, String name) {
        Object v = session == null ? null : session.getAttribute(name);
        return toLong(v);
    }

    private static String strAttr(HttpSession session, String name) {
        Object v = session == null ? null : session.getAttribute(name);
        return v == null ? null : v.toString();
    }
}

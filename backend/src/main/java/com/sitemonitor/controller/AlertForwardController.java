package com.sitemonitor.controller;

import com.sitemonitor.config.CorrelationIdFilter;
import com.sitemonitor.model.AlertForward;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TeamAccessGrant;
import com.sitemonitor.repository.AlertForwardRepository;
import com.sitemonitor.repository.TeamAccessGrantRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.access.TeamAccessGrantService;
import com.sitemonitor.service.forward.AlertForwardInfoNotifier;
import com.sitemonitor.service.forward.AlertForwardService;
import com.sitemonitor.service.noc.NocMonitorDirectory;
import com.sitemonitor.service.noc.NocType;
import com.sitemonitor.service.userref.UserPublicIds;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Alarm iletimi uçları (2026-11-05, kullanıcı isteği). Yönetim → Bildirim &amp; Alarmlar → "Alarm iletimi".
 * <ul>
 *   <li>{@code GET /api/alert-forwards[?team=]} — {@code items}: sahip takımı görüntüleyicinin KENDİ kapsamında olan iletimler
 *       ({@link SessionScope#canView} — erişim paylaşımı ve 7/24 genişletmesi YOK); {@code incoming}: görüntüleyicinin takımına
 *       (üye / yönetici / kendi kapsamı) ya da kendisine iletilenler, salt okunur. {@code alerts.forwards/view}.</li>
 *   <li>{@code POST}, {@code PUT /{id}}, {@code DELETE /{id}} — {@code alerts.forwards/edit} + sahip takımı YÖNETMEK
 *       ({@link SessionScope#canManage}). Denetim {@code ALERT_FORWARD_CREATE|UPDATE|DELETE}. PUT yalnız gönderilen alanları
 *       değiştirir; sahip takım değişmez. {@code grant_view}: alıcıya ayrıca VIEW erişim paylaşımı ({@code teams.sharing/edit}).</li>
 *   <li>{@code GET /monitors?team=} — sahip takımın izlemeleri (iletim penceresinin seçicisi).</li>
 *   <li>{@code GET /for-monitor?type=&id=} (cert: {@code domain=}) — izlemeye uygulanan iletimler (detay penceresi rozeti);
 *       görüntüleyici sahip takımı kendi kapsamında görmüyorsa {@code {hidden:true}}.</li>
 * </ul>
 * Kullanıcı kimlikleri opak ({@code user_id} anahtarı yanıt tavsiyesince çevrilir; istekte {@link UserPublicIds#resolve}).
 * Adres LİSTESİ dönmez — yalnız sayı.
 */
@Slf4j
@RestController
@RequestMapping("/api/alert-forwards")
public class AlertForwardController {

    static final String RESOURCE = "alerts.forwards";
    static final String GRANT_NOTE = "Alarm iletimiyle birlikte verildi";

    private final AlertForwardService forwards;
    private final AlertForwardRepository repo;
    private final TeamRepository teamRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;

    @Autowired(required = false) private AlertForwardInfoNotifier notifier;
    @Autowired(required = false) private TeamAccessGrantService grants;
    @Autowired(required = false) private TeamAccessGrantRepository grantRepo;
    @Autowired(required = false) private NocMonitorDirectory directory;
    @Autowired(required = false) private UserPublicIds userPublicIds;

    public AlertForwardController(AlertForwardService forwards, AlertForwardRepository repo, TeamRepository teamRepo,
                                  PermissionService permissionService, AuditService auditService) {
        this.forwards = forwards;
        this.repo = repo;
        this.teamRepo = teamRepo;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    void setNotifier(AlertForwardInfoNotifier n) { this.notifier = n; }
    void setGrants(TeamAccessGrantService g, TeamAccessGrantRepository r) { this.grants = g; this.grantRepo = r; }
    void setDirectory(NocMonitorDirectory d) { this.directory = d; }
    void setUserPublicIds(UserPublicIds u) { this.userPublicIds = u; }

    // ── Liste ────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(name = "team", required = false) String team,
                                                    HttpSession session) {
        permissionService.require(session, RESOURCE, "view");
        Long teamFilter = team == null || team.isBlank() || "all".equalsIgnoreCase(team.trim()) ? null : parseLong(team);
        boolean canEdit = permissionService.allows(session, RESOURCE, "edit");
        List<AlertForward> all = repo.findAllByOrderByIdAsc();
        List<AlertForward> items = new ArrayList<>();
        List<AlertForward> incoming = new ArrayList<>();
        Set<Long> mine = incomingTeams(session);
        Long uid = userId(session);
        for (AlertForward f : all) {
            if (SessionScope.canView(session, f.getOwnerTeamId())) {
                if (teamFilter == null || teamFilter.equals(f.getOwnerTeamId())) items.add(f);
            } else if (mine.contains(f.getRecipientTeamId())
                    || (uid != null && AlertForward.RECIPIENT_MEMBERS.equals(f.getRecipientKind())
                        && AlertForwardService.userIdsOf(f).contains(uid))) {
                incoming.add(f);
            }
        }
        List<AlertForward> both = new ArrayList<>(items);
        both.addAll(incoming);
        Ctx ctx = ctx(both);
        List<Map<String, Object>> itemRows = new ArrayList<>();
        for (AlertForward f : items) itemRows.add(row(f, ctx, canEdit && SessionScope.canManage(session, f.getOwnerTeamId())));
        List<Map<String, Object>> incomingRows = new ArrayList<>();
        for (AlertForward f : incoming) incomingRows.add(row(f, ctx, false));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", itemRows);
        data.put("incoming", incomingRows);
        data.put("manageable_teams", canEdit ? manageableTeams(session) : List.of());
        data.put("can_grant", permissionService.allows(session, "teams.sharing", "edit"));
        data.put("limits", limits());
        return ok(data);
    }

    // ── Yazma ────────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpSession session,
                                                      HttpServletRequest request) {
        permissionService.require(session, RESOURCE, "edit");
        Map<String, Object> b = body == null ? Map.of() : body;
        Long owner = AlertForwardService.longOf(b.get("owner_team_id"));
        if (owner == null) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("owner_team_id",
                    Msg.t("İletimi ekleyen (sahip) takımı seçin.", "Choose the owner team that adds the forward."));
        }
        requireManage(session, owner);
        boolean grantView = Boolean.TRUE.equals(b.get("grant_view"));
        if (grantView) {
            ResponseEntity<Map<String, Object>> refused = grantRefusal(session, owner);
            if (refused != null) return refused;
        }
        List<Long> userIds = b.containsKey("recipient_user_ids") ? resolveUsers(b.get("recipient_user_ids"), session) : null;
        AlertForwardService.Saved saved = forwards.create(b, userIds, actor(session));
        AlertForward f = saved.forward();
        Names names = names(List.of(f));
        auditService.recordAction("ALERT_FORWARD_CREATE", session, request, "TEAM", String.valueOf(f.getOwnerTeamId()),
                AuditDetail.ofMap(auditBase(f, names)));
        Map<String, Object> grant = grantView ? grantView(session, request, f, saved.newWholeTeam(), saved.newMemberIds()) : null;
        AlertForwardInfoNotifier.Info info = notifier == null ? AlertForwardInfoNotifier.Info.NONE
                : notifier.notifyAdded(f, saved.newWholeTeam(), saved.newMemberIds(), displayName(session));
        return okWrite(row(f, ctx(List.of(f)), true), grant, info);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                      HttpSession session, HttpServletRequest request) {
        permissionService.require(session, RESOURCE, "edit");
        AlertForward current = findVisible(id, session);
        requireManage(session, current.getOwnerTeamId());
        Map<String, Object> b = body == null ? Map.of() : body;
        boolean grantView = Boolean.TRUE.equals(b.get("grant_view"));
        if (grantView) {
            ResponseEntity<Map<String, Object>> refused = grantRefusal(session, current.getOwnerTeamId());
            if (refused != null) return refused;
        }
        List<Long> userIds = b.containsKey("recipient_user_ids") ? resolveUsers(b.get("recipient_user_ids"), session) : null;
        AlertForwardService.Saved saved = forwards.update(id, b, userIds, actor(session));
        AlertForward f = saved.forward();
        Map<String, Object> grant = null;
        AlertForwardInfoNotifier.Info info = AlertForwardInfoNotifier.Info.NONE;
        if (saved.changed()) {
            Names names = names(List.of(f, saved.before()));
            Map<String, Object> detail = auditBase(f, names);
            detail.put("changes", changes(auditState(saved.before()), auditState(f)));
            auditService.recordAction("ALERT_FORWARD_UPDATE", session, request, "TEAM", String.valueOf(f.getOwnerTeamId()),
                    AuditDetail.ofMap(detail));
            if (grantView) grant = grantView(session, request, f, saved.newWholeTeam(), saved.newMemberIds());
            if (notifier != null) info = notifier.notifyAdded(f, saved.newWholeTeam(), saved.newMemberIds(), displayName(session));
        } else if (grantView) {
            grant = grantResult(0, 0, 0, null);
        }
        return okWrite(row(f, ctx(List.of(f)), true), grant, info);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        permissionService.require(session, RESOURCE, "edit");
        AlertForward f = findVisible(id, session);
        requireManage(session, f.getOwnerTeamId());
        Names names = names(List.of(f));
        forwards.delete(id);
        auditService.recordAction("ALERT_FORWARD_DELETE", session, request, "TEAM", String.valueOf(f.getOwnerTeamId()),
                AuditDetail.ofMap(auditBase(f, names)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("deleted", true);
        return ok(data);
    }

    // ── İzleme seçicisi ──────────────────────────────────────────────────────

    @GetMapping("/monitors")
    public ResponseEntity<Map<String, Object>> monitors(@RequestParam(name = "team") Long team, HttpSession session) {
        permissionService.require(session, RESOURCE, "view");
        if (team == null || !SessionScope.canView(session, team)) {
            throw new SecurityException(Msg.t("Bu takımın izlemelerini görme yetkiniz yok.",
                    "You don't have access to this team's monitors."));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();
        if (directory == null) {
            data.put("items", items);
            data.put("unavailable", true);
            return ok(data);
        }
        List<NocMonitorDirectory.Row> rows = new ArrayList<>();
        for (NocMonitorDirectory.Row r : directory.all()) {
            if (Objects.equals(team, r.teamId()) || Objects.equals(team, r.ugTeamId())) rows.add(r);
        }
        rows.sort((a, c) -> {
            int t = Integer.compare(AlertForwardService.TYPE_KEYS.indexOf(AlertForwardService.keyOf(a.type())),
                    AlertForwardService.TYPE_KEYS.indexOf(AlertForwardService.keyOf(c.type())));
            return t != 0 ? t : TR.compare(nz(a.name()), nz(c.name()));
        });
        for (NocMonitorDirectory.Row r : rows) items.add(monitorItem(r));
        data.put("items", items);
        return ok(data);
    }

    @GetMapping("/for-monitor")
    public ResponseEntity<Map<String, Object>> forMonitor(@RequestParam(name = "type") String type,
                                                          @RequestParam(name = "id", required = false) Long id,
                                                          @RequestParam(name = "domain", required = false) String domain,
                                                          HttpSession session) {
        permissionService.require(session, RESOURCE, "view");
        NocType t = AlertForwardService.nocTypeOf(type);
        if (t == null) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("type",
                    Msg.t("Bilinmeyen izleme türü.", "Unknown monitor type."));
        }
        NocMonitorDirectory.Row row = null;
        if (directory != null) {
            if (id != null) row = directory.find(t, id);
            else if (t == NocType.SSL && domain != null && !domain.isBlank() && domain.length() <= 300) {
                row = directory.forAlert(NocType.SSL, domain, null);
            }
        }
        if (row == null) {
            throw new NoSuchElementException(Msg.t("İzleme bulunamadı; silinmiş olabilir.", "The monitor wasn't found; it may have been deleted."));
        }
        Set<Long> owners = new LinkedHashSet<>();
        if (row.teamId() != null) owners.add(row.teamId());
        if (row.ugTeamId() != null) owners.add(row.ugTeamId());
        boolean visible = SessionScope.isGlobalViewer(session);
        for (Long o : owners) if (SessionScope.canView(session, o)) visible = true;
        Map<String, Object> data = new LinkedHashMap<>();
        if (!visible) {
            data.put("hidden", true);
            return ok(data);
        }
        String key = AlertForwardService.keyOf(t);
        List<AlertForward> applied = new ArrayList<>();
        if (!owners.isEmpty()) {
            for (AlertForward f : repo.findByOwnerTeamIdInOrderByIdAsc(owners)) {
                if (applies(f, key, row.id())) applied.add(f);
            }
        }
        boolean canEdit = permissionService.allows(session, RESOURCE, "edit");
        Ctx ctx = ctx(applied);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AlertForward f : applied) items.add(row(f, ctx, canEdit && SessionScope.canManage(session, f.getOwnerTeamId())));
        boolean canAdd = false;
        List<Map<String, Object>> ownerTeams = new ArrayList<>();
        Map<Long, Team> teams = new HashMap<>();
        for (Team tm : teamRepo.findAllById(owners)) teams.put(tm.getId(), tm);
        for (Long o : owners) {
            if (canEdit && SessionScope.canManage(session, o)) canAdd = true;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o);
            m.put("name", teams.get(o) == null ? null : teams.get(o).getName());
            ownerTeams.add(m);
        }
        Map<String, Object> monitor = new LinkedHashMap<>();
        monitor.put("type", key);
        monitor.put("id", row.id());
        monitor.put("name", row.name());
        data.put("items", items);
        data.put("can_add", canAdd);
        data.put("owner_teams", ownerTeams);
        data.put("monitor", monitor);
        return ok(data);
    }

    /** İletim bu izlemeye uygulanıyor mu: ALL, TYPES ∋ tür, MONITORS ∋ (tür, kimlik). */
    static boolean applies(AlertForward f, String typeKey, long monitorId) {
        if (AlertForward.SCOPE_ALL.equals(f.getScopeKind())) return true;
        if (AlertForward.SCOPE_TYPES.equals(f.getScopeKind())) return AlertForwardService.typesOf(f).contains(typeKey);
        if (AlertForward.SCOPE_MONITORS.equals(f.getScopeKind())) {
            for (AlertForwardService.MonitorRef r : AlertForwardService.monitorsOf(f)) {
                if (r.type().equals(typeKey) && r.id() == monitorId) return true;
            }
        }
        return false;
    }

    // ── Satır ────────────────────────────────────────────────────────────────

    /** Satırların takım / kişi adları, izleme dizini ve adres sayıları — istek başına sabit sayıda okuma. */
    private record Ctx(Names names, Map<Long, AlertForwardService.Member> members,
                       Map<String, NocMonitorDirectory.Row> monitors, boolean monitorsReadable) { }

    private Ctx ctx(Collection<AlertForward> rows) {
        Names names = names(rows);
        Set<Long> userIds = new LinkedHashSet<>();
        boolean anyMonitors = false;
        for (AlertForward f : rows) {
            if (AlertForward.RECIPIENT_MEMBERS.equals(f.getRecipientKind())) userIds.addAll(AlertForwardService.userIdsOf(f));
            if (AlertForward.SCOPE_MONITORS.equals(f.getScopeKind())) anyMonitors = true;
        }
        Map<Long, AlertForwardService.Member> members = userIds.isEmpty() ? Map.of() : forwards.members(userIds);
        Map<String, NocMonitorDirectory.Row> monitors = new HashMap<>();
        boolean readable = true;
        if (anyMonitors) {
            if (directory == null) {
                readable = false;
            } else {
                try {
                    for (NocMonitorDirectory.Row r : directory.all()) monitors.put(AlertForwardService.keyOf(r.type()) + ":" + r.id(), r);
                } catch (RuntimeException e) {
                    log.warn("Alarm iletimi: izleme dizini okunamadı: {}", e.toString());
                    readable = false;
                }
            }
        }
        return new Ctx(names, members, monitors, readable);
    }

    Map<String, Object> row(AlertForward f, Ctx ctx, boolean canEdit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("version", f.getVersion());
        m.put("owner_team_id", f.getOwnerTeamId());
        m.put("owner_team_name", ctx.names().team(f.getOwnerTeamId()));
        m.put("owner_team_active", ctx.names().teamActive(f.getOwnerTeamId()));
        m.put("scope_kind", f.getScopeKind());
        m.put("scope_types", AlertForward.SCOPE_TYPES.equals(f.getScopeKind()) ? AlertForwardService.typesOf(f) : List.of());
        List<Map<String, Object>> mons = new ArrayList<>();
        int missing = 0;
        List<AlertForwardService.MonitorRef> refs = AlertForward.SCOPE_MONITORS.equals(f.getScopeKind())
                ? AlertForwardService.monitorsOf(f) : List.of();
        for (AlertForwardService.MonitorRef r : refs) {
            Map<String, Object> mm = new LinkedHashMap<>();
            NocMonitorDirectory.Row row = ctx.monitors().get(r.key());
            mm.put("type", r.type());
            mm.put("id", r.id());
            mm.put("name", row == null ? null : row.name());
            mm.put("target", row == null ? null : row.target());
            mm.put("active", row == null ? null : row.active());
            // Dizin okunamadıysa "silinmiş" denmez (bilinmiyor = null)
            mm.put("missing", ctx.monitorsReadable() ? row == null : null);
            if (ctx.monitorsReadable() && row == null) missing++;
            mons.add(mm);
        }
        m.put("scope_monitors", mons);
        m.put("recipient_kind", f.getRecipientKind());
        m.put("recipient_team_id", f.getRecipientTeamId());
        m.put("recipient_team_name", ctx.names().team(f.getRecipientTeamId()));
        boolean recipientActive = ctx.names().teamActive(f.getRecipientTeamId());
        m.put("recipient_team_active", recipientActive);
        List<Map<String, Object>> members = new ArrayList<>();
        int liveMembers = 0;
        if (AlertForward.RECIPIENT_MEMBERS.equals(f.getRecipientKind())) {
            for (Long uid : AlertForwardService.userIdsOf(f)) {
                AlertForwardService.Member mem = ctx.members().get(uid);
                boolean active = mem != null && mem.active();
                boolean inTeam = mem != null && mem.inTeam(f.getRecipientTeamId());
                if (active && inTeam) liveMembers++;
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("user_id", uid);   // UserRefWire anahtarı → global yönetici dışında opak kimlik
                mm.put("display_name", mem == null ? null : mem.name());
                mm.put("active", active);
                mm.put("in_team", inTeam);
                members.add(mm);
            }
        }
        m.put("recipient_members", members);
        m.put("recipient_address_count", forwards.addressCount(f.getId()));
        m.put("enabled", f.isEnabled());
        m.put("expires_on", TeamAccessGrantService.expiresOn(f.getExpiresAt()));
        boolean expired = forwards.expired(f);
        String state;
        if (!f.isEnabled()) state = "paused";
        else if (expired) state = "expired";
        else if (!recipientActive) state = "recipient_inactive";
        else if (AlertForward.SCOPE_MONITORS.equals(f.getScopeKind()) && ctx.monitorsReadable() && !refs.isEmpty()
                && missing == refs.size()) state = "no_targets";
        else if (AlertForward.SCOPE_MONITORS.equals(f.getScopeKind()) && refs.isEmpty()) state = "no_targets";
        else if (AlertForward.RECIPIENT_MEMBERS.equals(f.getRecipientKind()) && liveMembers == 0) state = "no_recipients";
        else state = "active";
        m.put("state", state);
        m.put("note", f.getNote());
        m.put("created_at", f.getCreatedAt());
        m.put("created_by", f.getCreatedBy());
        m.put("updated_at", f.getUpdatedAt());
        m.put("updated_by", f.getUpdatedBy());
        m.put("can_edit", canEdit);
        return m;
    }

    private static Map<String, Object> monitorItem(NocMonitorDirectory.Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", AlertForwardService.keyOf(r.type()));
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("target", r.target());
        m.put("active", r.active());
        return m;
    }

    // ── Görüntüleme erişimi (grant_view) ─────────────────────────────────────

    /** {@code grant_view} istendi ama çağıran erişim paylaşımı yapamaz → 403 + {@code field: grant_view} (iletim kaydedilmez). */
    private ResponseEntity<Map<String, Object>> grantRefusal(HttpSession session, Long owner) {
        if (grants != null && grantRepo != null && permissionService.allows(session, "teams.sharing", "edit")
                && owner != null && SessionScope.canManage(session, owner)) {
            return null;
        }
        String msg = Msg.t("Görüntüleme erişimi verme yetkiniz yok (erişim paylaşımı izni gerekir) — kutuyu kaldırıp yeniden kaydedin.",
                "You can't give view access (it needs the access sharing permission) — untick the box and save again.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "FORBIDDEN");
        body.put("error", msg);
        body.put("field", "grant_view");
        body.put("fields", Map.of("grant_view", msg));
        String rid = CorrelationIdFilter.current();
        if (rid != null) body.put("request_id", rid);
        return ResponseEntity.status(403).body(body);
    }

    /**
     * Yeni alıcılara VIEW erişim paylaşımı: TEAM → {@code T:<alıcı>}; MEMBERS → her yeni üyeye {@code U:<id>}. Zaten ≥ VIEW
     * paylaşımı olan alıcıya dokunulmaz ({@code existing}); süre = iletimin bitişi. Tavan / hata → {@code failed} + ileti —
     * iletim yine kaydedilmiştir.
     */
    private Map<String, Object> grantView(HttpSession session, HttpServletRequest request, AlertForward f,
                                          boolean newWhole, List<Long> newMembers) {
        int created = 0, existing = 0, failed = 0;
        String message = null;
        Long owner = f.getOwnerTeamId();
        String expiresOn = TeamAccessGrantService.expiresOn(f.getExpiresAt());
        List<String[]> targets = new ArrayList<>();   // {type, teamId, userId}
        if (newWhole) targets.add(new String[]{TeamAccessGrant.TYPE_TEAM, String.valueOf(f.getRecipientTeamId()), null});
        for (Long u : newMembers == null ? List.<Long>of() : newMembers) {
            targets.add(new String[]{TeamAccessGrant.TYPE_USER, null, String.valueOf(u)});
        }
        for (String[] t : targets) {
            Long teamId = t[1] == null ? null : Long.valueOf(t[1]);
            Long userId = t[2] == null ? null : Long.valueOf(t[2]);
            String key = TeamAccessGrant.keyOf(t[0], teamId, userId);
            try {
                Optional<TeamAccessGrant> have = grantRepo.findByOwnerTeamIdAndGranteeKey(owner, key);
                if (have.isPresent()) {
                    if (grants.expired(have.get())) {
                        failed++;
                        message = Msg.t("Alıcının süresi dolmuş bir erişim paylaşımı var — Erişim paylaşımı ekranından yenileyin.",
                                "The recipient has an expired access share — renew it on the Access sharing screen.");
                    } else {
                        existing++;
                    }
                    continue;
                }
                if (userId != null && grants.levelsFor(userId).containsKey(owner)) {   // takımı üzerinden zaten görüyor
                    existing++;
                    continue;
                }
                TeamAccessGrantService.Input in = grants.validate(owner, t[0], teamId, userId, "VIEW", expiresOn, GRANT_NOTE);
                TeamAccessGrant saved = grants.create(in, actor(session));
                auditService.recordAction("TEAM_ACCESS_GRANT_CREATE", session, request, "TEAM", String.valueOf(owner),
                        AuditDetail.of("grant_id", saved.getId(), "grantee_type", saved.getGranteeType(),
                                "grantee_team_id", saved.getGranteeTeamId(), "grantee_user_id", saved.getGranteeUserId(),
                                "level", saved.getLevel(), "expires_at", saved.getExpiresAt(), "via", "alert_forward",
                                "forward_id", f.getId()));
                created++;
            } catch (RuntimeException e) {
                failed++;
                message = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage()
                        : Msg.t("Görüntüleme erişimi verilemedi.", "View access couldn't be given.");
                log.info("Alarm iletimi {}: görüntüleme erişimi verilemedi ({}): {}", f.getId(), key, e.toString());
            }
        }
        return grantResult(created, existing, failed, message);
    }

    private static Map<String, Object> grantResult(int created, int existing, int failed, String message) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("requested", true);
        g.put("created", created);
        g.put("existing", existing);
        g.put("failed", failed);
        if (message != null) g.put("message", message);
        return g;
    }

    // ── Denetim ──────────────────────────────────────────────────────────────

    private static Map<String, Object> auditBase(AlertForward f, Names names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("forward_id", f.getId());
        m.put("owner_team_id", f.getOwnerTeamId());
        m.put("owner_team", names.team(f.getOwnerTeamId()));
        m.put("recipient_team_id", f.getRecipientTeamId());
        m.put("recipient_team", names.team(f.getRecipientTeamId()));
        m.put("scope_kind", f.getScopeKind());
        if (AlertForward.SCOPE_TYPES.equals(f.getScopeKind())) m.put("scope_types", String.join(",", AlertForwardService.typesOf(f)));
        if (AlertForward.SCOPE_MONITORS.equals(f.getScopeKind())) m.put("scope_monitor_count", AlertForwardService.monitorsOf(f).size());
        m.put("recipient_kind", f.getRecipientKind());
        if (AlertForward.RECIPIENT_MEMBERS.equals(f.getRecipientKind())) m.put("member_count", AlertForwardService.userIdsOf(f).size());
        m.put("enabled", f.isEnabled());
        m.put("expires_at", f.getExpiresAt());
        return m;
    }

    /** Değişiklik karşılaştırması için durum (üye ADLARI yok — yalnız sayı; not metni yok — yalnız değişti bilgisi). */
    private static Map<String, Object> auditState(AlertForward f) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (f == null) return m;
        m.put("scope_kind", f.getScopeKind());
        m.put("scope_types", f.getScopeTypes());
        m.put("scope_monitor_count", AlertForwardService.monitorsOf(f).size());
        m.put("recipient_kind", f.getRecipientKind());
        m.put("recipient_team_id", f.getRecipientTeamId());
        m.put("member_count", AlertForwardService.userIdsOf(f).size());
        m.put("enabled", f.isEnabled());
        m.put("expires_at", f.getExpiresAt());
        m.put("note_hash", f.getNote() == null ? null : Integer.toHexString(f.getNote().hashCode()));
        return m;
    }

    /** {alan: {from, to}} — yalnız değişenler ({@code AuditDiff} biçimi; arayüz "değişiklik" olarak çizer). */
    static Map<String, Object> changes(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            Object from = before.get(k), to = after.get(k);
            if (Objects.equals(from, to)) continue;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("from", from);
            d.put("to", to);
            out.put(k, d);
        }
        return out;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    /**
     * Görüntüleyicinin "bize iletilenler" takımları: üye olduğu, yönettiği ve kendi kapsamında gördüğü takımlar (erişim
     * paylaşımı / 7/24 genişletmesi YOK).
     */
    private static Set<Long> incomingTeams(HttpSession session) {
        Set<Long> s = new HashSet<>(SessionScope.memberTeamIds(session));
        List<Long> manage = SessionScope.manageTeamIds(session);
        if (manage != null) s.addAll(manage);
        List<Long> view = SessionScope.viewTeamIds(session);
        if (view != null) s.addAll(view);
        return s;
    }

    /**
     * İletimi yükler; çağıranın sahip takımı KENDİ kapsamında görmediği satır yok sayılır → 404 (başka takımların iletim
     * kimlikleri 403 / 404 farkıyla taranamaz; erişim paylaşımı ile görülmez). Görülen ama yönetilemeyen satır 403 alır.
     */
    private AlertForward findVisible(Long id, HttpSession session) {
        AlertForward f = repo.findById(id).orElseThrow(AlertForwardController::notFound);
        if (!SessionScope.canView(session, f.getOwnerTeamId())) throw notFound();
        return f;
    }

    private void requireManage(HttpSession session, Long ownerTeamId) {
        if (ownerTeamId == null || !SessionScope.canManage(session, ownerTeamId)) {
            throw new SecurityException(Msg.t(
                    "Bu takımın alarm iletimlerini yalnız takımı yöneten kişi (takım yöneticisi, müdür ya da global yönetici) değiştirebilir.",
                    "Only someone who manages this team (team admin, manager or global admin) can change its alarm forwards."));
        }
    }

    /** İletim ekleyebileceği takımlar: global admin → tüm aktif takımlar; diğerleri yönettikleri aktif takımlar. */
    private List<Map<String, Object>> manageableTeams(HttpSession session) {
        List<Team> teams;
        if (SessionScope.isGlobalAdmin(session)) teams = teamRepo.findAll();
        else {
            List<Long> m = SessionScope.manageTeamIds(session);
            teams = m == null || m.isEmpty() ? List.of() : teamRepo.findAllById(m);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Team t : teams) {
            if (t == null || Boolean.FALSE.equals(t.getActive())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            out.add(m);
        }
        out.sort((a, b) -> TR.compare(nz(String.valueOf(a.get("name"))), nz(String.valueOf(b.get("name")))));
        return out;
    }

    private static Map<String, Object> limits() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("per_team", AlertForwardService.MAX_PER_TEAM);
        m.put("members", AlertForwardService.MAX_MEMBERS);
        m.put("monitors", AlertForwardService.MAX_MONITORS);
        m.put("note", AlertForwardService.NOTE_MAX);
        return m;
    }

    private static final java.text.Collator TR = java.text.Collator.getInstance(Locale.forLanguageTag("tr"));

    /** Opak kullanıcı kimlikleri → iç kimlik (çözülemeyen null kalır → servis 400 {@code recipient_user_ids}). */
    private List<Long> resolveUsers(Object raw, HttpSession session) {
        if (raw == null) return List.of();
        if (!(raw instanceof Collection<?> c)) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("recipient_user_ids",
                    Msg.t("Seçilen üyeler bir liste olmalı.", "The selected members must be a list."));
        }
        if (c.size() > 1000) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("recipient_user_ids",
                    Msg.t("Takımdan en çok " + AlertForwardService.MAX_MEMBERS + " üye seçin.",
                            "Choose at most " + AlertForwardService.MAX_MEMBERS + " members of the team."));
        }
        List<Long> out = new ArrayList<>();
        for (Object o : c) out.add(UserPublicIds.resolve(userPublicIds, o, session));
        return out;
    }

    /** Satırlardaki takım adları — tek toplu sorgu. */
    private record Names(Map<Long, Team> teams) {
        String team(Long id) {
            Team t = id == null ? null : teams.get(id);
            return t == null ? null : t.getName();
        }
        boolean teamActive(Long id) {
            Team t = id == null ? null : teams.get(id);
            return t != null && !Boolean.FALSE.equals(t.getActive());
        }
    }

    private Names names(Collection<AlertForward> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AlertForward f : rows) {
            if (f == null) continue;
            if (f.getOwnerTeamId() != null) ids.add(f.getOwnerTeamId());
            if (f.getRecipientTeamId() != null) ids.add(f.getRecipientTeamId());
        }
        Map<Long, Team> t = new HashMap<>();
        if (!ids.isEmpty()) for (Team x : teamRepo.findAllById(ids)) t.put(x.getId(), x);
        return new Names(t);
    }

    private static NoSuchElementException notFound() {
        return new NoSuchElementException(Msg.t("Alarm iletimi bulunamadı; silinmiş olabilir. Listeyi yenileyip yeniden deneyin.",
                "The alarm forward wasn't found; it may have been deleted. Refresh the list and try again."));
    }

    private static Long userId(HttpSession session) {
        Object uid = session == null ? null : session.getAttribute("userId");
        return uid instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }

    private static String displayName(HttpSession session) {
        Object d = session != null ? session.getAttribute("displayName") : null;
        if (d != null && !d.toString().isBlank()) return d.toString();
        return actor(session);
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank() || s.length() > 19) return null;
        try { return Long.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static ResponseEntity<Map<String, Object>> okWrite(Map<String, Object> row, Map<String, Object> grant,
                                                               AlertForwardInfoNotifier.Info info) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", row);
        body.put("grant", grant);
        body.put("info", (info == null ? AlertForwardInfoNotifier.Info.NONE : info).toMap());
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}

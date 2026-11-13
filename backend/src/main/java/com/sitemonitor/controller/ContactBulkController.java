package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.userref.UserPublicIds;
import com.sitemonitor.util.ErrorTexts;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Eskalasyon kişileri — TOPLU işlemler (2026-11-13, yönetim zenginleştirmesi (d)/(h)).
 *
 * <h2>Uçlar</h2>
 * <ul>
 *   <li>{@code POST /api/admin/contacts/bulk} — seçili kişilerde {@code activate | deactivate | set_level | set_team}.</li>
 *   <li>{@code POST /api/admin/contacts/bulk-create} — bir kişiyi (kullanıcı + rol + eşik) seçili takımların HER birine
 *       eskalasyon kişisi olarak ekler (Takımlar → toplu düzeltme: "eskalasyon kişisi yok / kritik boşluk").</li>
 * </ul>
 *
 * <h2>Kapsam (tekil uçlarla AYNI kural, satır satır)</h2>
 * Rol kapısı {@code AdminController.requireAdminOrTeamAdmin} + {@code contacts.crud/edit}; her satır
 * {@code canManage(satırın takımı)} — global yönetici her takım, kapsamlı müdür / TEAM_ADMIN yalnız YÖNETTİĞİ takımlar.
 * Kapsam dışı satır SESSİZCE yok sayılmaz: sonuçta {@code skipped / out_of_scope} döner; görüş kapsamında bile olmayan
 * satırın varlığı sızmasın diye {@code not_found} (tekil uçlardaki 404 / 403 ayrımının karşılığı). Takıma taşıma (set_team)
 * hedefi tüm satırlar için aynıdır: hedef yönetim kapsamı dışındaysa istek TÜMDEN 403 (TEAM_ADMIN takım değiştiremez —
 * tekil düzenlemede gövdedeki takımı yok sayılır, burada açıkça reddedilir). Takımsız kişi kabul edilmez (2026-09-28).
 *
 * <h2>Denetim</h2>
 * Her işlenen satır tekil uçla AYNI olayı yazar ({@code CONTACT_UPDATE} farkı / {@code CONTACT_CREATE}) — kişinin geçmişi
 * ve değişiklik akışı aynı kalır; ayrıca tek özet ({@code CONTACT_BULK_UPDATE} / {@code CONTACT_BULK_CREATE}) işlenen
 * kimliklerle. Değişmeyen (zaten aktif / aynı seviye / aynı takım) satır yazılmaz, denetlenmez ({@code unchanged}).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/contacts")
@RequiredArgsConstructor
public class ContactBulkController {

    /** Tek istekte en çok satır (takım toplu işlemiyle aynı tavan). */
    static final int MAX_ITEMS = 200;
    static final List<String> LEVELS = List.of("WARNING", "HIGH", "CRITICAL");
    static final List<String> ROLES = List.of("PO", "TECH", "MANAGER", "CLEVEL");
    static final Set<String> ACTIONS = Set.of("activate", "deactivate", "set_level", "set_team");

    /** Satır sonuç nedenleri — arayüz {@code adm.bulk.reason.<kod>} ile çevirir (kodlar kalıcı). */
    static final String DONE = "done";
    static final String NOT_FOUND = "not_found";
    static final String OUT_OF_SCOPE = "out_of_scope";
    static final String UNCHANGED = "unchanged";
    static final String NO_TEAM = "no_team";
    static final String ALREADY_EXISTS = "already_exists";
    static final String ERROR = "error";

    /** {@code AdminController.updateContact} denetim alanlarıyla AYNI liste — fark ekranı iki yolda aynı okunur. */
    private static final String[] AUDIT_FIELDS = {
            "name", "email", "role", "minAlertLevel", "webhookType", "active", "teamId", "userId", "delayMinutes" };

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final EscalationContactRepository contactRepo;
    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;
    private final AuditService auditService;
    private final PermissionService permissionService;

    /** Opak kullanıcı kimliği çözücüsü — bean'siz birim testinde null (sayısal ayrıştırma). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserPublicIds userPublicIds;

    // ── Toplu güncelleme ─────────────────────────────────────────────────────────────────────

    @PostMapping("/bulk")
    public ResponseEntity<Map<String, Object>> bulk(@RequestBody Map<String, Object> body, HttpSession session,
                                                    HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        permissionService.require(session, "contacts.crud", "edit");
        String action = body.get("action") == null ? "" : body.get("action").toString().trim().toLowerCase(java.util.Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new FieldValidationException("action", Msg.t(
                    "Geçersiz toplu işlem. Etkinleştir, pasifleştir, seviye ata ya da takıma ata seçin.",
                    "Invalid bulk action. Choose activate, deactivate, set level or assign to a team."));
        }
        List<Long> ids = parseIds(body.get("ids"), "ids");

        String level = null;
        if ("set_level".equals(action)) {
            level = upper(body.get("min_alert_level"));
            if (!LEVELS.contains(level)) throw new FieldValidationException("min_alert_level", Msg.t(
                    "Geçersiz alarm seviyesi — Uyarı, Yüksek ya da Kritik seçin.",
                    "Invalid alert level — choose Warning, High or Critical."));
        }
        Long targetTeam = null;
        String targetTeamName = null;
        if ("set_team".equals(action)) {
            targetTeam = toLong(body.get("team_id"));
            if (targetTeam == null) throw new FieldValidationException("team_id", Msg.t(
                    "Kişilerin taşınacağı takımı seçin.", "Choose the team to move the contacts to."));
            Team tm = teamRepo.findById(targetTeam).orElse(null);
            if (tm == null) throw new FieldValidationException("team_id", Msg.t(
                    "Seçilen takım bulunamadı — geçerli bir takım seçin.", "The selected team doesn't exist — choose a valid team."));
            targetTeamName = tm.getName();
            // TEAM_ADMIN kişiyi başka takıma taşıyamaz (tekil düzenlemede gövdedeki takım yok sayılır). Müdür yalnız YÖNETTİĞİ
            // takıma taşır — hedef herkes için aynı olduğundan istek TÜMDEN reddedilir (satır satır "kapsam dışı" yanıltırdı).
            if (isTeamAdmin(session) || !SessionScope.canManage(session, targetTeam)) {
                log.warn("Bulk contact move to unmanaged team by user={} team={}", actor(session), targetTeam);
                throw new SecurityException(Msg.t(
                        "Kişileri yalnız yönettiğiniz bir takıma taşıyabilirsiniz.",
                        "You can only move contacts to a team you manage."));
            }
        }

        Map<Long, EscalationContact> byId = new HashMap<>();
        for (EscalationContact c : contactRepo.findAllById(ids)) byId.put(c.getId(), c);
        Map<Long, String> teamNames = teamNames();

        List<Map<String, Object>> results = new ArrayList<>();
        List<Long> doneIds = new ArrayList<>();
        int done = 0, skipped = 0, failed = 0;
        for (Long id : ids) {
            EscalationContact c = byId.get(id);
            if (c == null || !SessionScope.canView(session, c.getTeamId())) {
                results.add(row(id, null, null, null, "skipped", NOT_FOUND)); skipped++; continue;
            }
            String name = c.getName() != null && !c.getName().isBlank() ? c.getName() : c.getEmail();
            if (!SessionScope.canManage(session, c.getTeamId())) {
                results.add(row(id, name, c.getTeamId(), teamNames.get(c.getTeamId()), "skipped", OUT_OF_SCOPE)); skipped++; continue;
            }
            try {
                Map<String, Object> before = AuditDiff.snapshot(c, AUDIT_FIELDS);
                String reason = apply(c, action, level, targetTeam);
                if (!DONE.equals(reason)) {
                    results.add(row(id, name, c.getTeamId(), teamNames.get(c.getTeamId()), "skipped", reason)); skipped++; continue;
                }
                EscalationContact saved = contactRepo.save(c);
                auditService.recordAction("CONTACT_UPDATE", session, "ESCALATION_CONTACT", String.valueOf(id), saved.getName(),
                        AuditDiff.diff(before, AuditDiff.snapshot(saved, AUDIT_FIELDS)));
                results.add(row(id, name, saved.getTeamId(), teamNames.get(saved.getTeamId()), "done", DONE));
                doneIds.add(id);
                done++;
            } catch (RuntimeException e) {
                log.warn("Bulk contact {} failed for id={}: {}", action, id, e.toString());
                Map<String, Object> r = row(id, name, c.getTeamId(), teamNames.get(c.getTeamId()), "failed", ERROR);
                r.put("message", ErrorTexts.safeMessage(e, "Kişi güncellenemedi.", "The contact couldn't be updated."));
                results.add(r);
                failed++;
            }
        }
        auditService.recordAction("CONTACT_BULK_UPDATE", session, request, "ESCALATION_CONTACT", "bulk",
                AuditDetail.of("action", action, "level", level, "team_id", targetTeam, "team", targetTeamName,
                        "requested", ids.size(), "done", done, "skipped", skipped, "failed", failed, "ids", joinIds(doneIds)));
        return ok(summary(action, done, skipped, failed, results));
    }

    /** Satırı eyleme göre değiştirir; {@code DONE} ya da atlama nedeni döner (yazma çağıranda). */
    private static String apply(EscalationContact c, String action, String level, Long targetTeam) {
        switch (action) {
            case "activate" -> {
                if (Boolean.TRUE.equals(c.getActive())) return UNCHANGED;
                // Takımsız kişi hiçbir bildirim almaz (2026-09-28) — etkinleştirmek bir şey düzeltmez; önce takım atanır.
                if (c.getTeamId() == null) return NO_TEAM;
                c.setActive(true);
            }
            case "deactivate" -> {
                if (Boolean.FALSE.equals(c.getActive())) return UNCHANGED;
                c.setActive(false);
            }
            case "set_level" -> {
                if (level.equals(c.getMinAlertLevel())) return UNCHANGED;
                c.setMinAlertLevel(level);
            }
            case "set_team" -> {
                if (Objects.equals(targetTeam, c.getTeamId())) return UNCHANGED;
                c.setTeamId(targetTeam);
            }
            default -> throw new IllegalStateException("action");
        }
        return DONE;
    }

    // ── Takımlara toplu kişi ekleme ──────────────────────────────────────────────────────────

    @PostMapping("/bulk-create")
    public ResponseEntity<Map<String, Object>> bulkCreate(@RequestBody Map<String, Object> body, HttpSession session,
                                                          HttpServletRequest request) {
        requireAdminOrTeamAdmin(session);
        permissionService.require(session, "contacts.crud", "edit");
        List<Long> teamIds = parseIds(body.get("team_ids"), "team_ids");
        String role = body.get("role") == null ? "TECH" : upper(body.get("role"));
        if (!ROLES.contains(role)) throw new FieldValidationException("role", Msg.t(
                "Geçersiz rol — PO, Teknik, Müdür ya da Üst yönetim seçin.", "Invalid role — choose PO, Technical, Manager or C-level."));
        String level = body.get("min_alert_level") == null ? "WARNING" : upper(body.get("min_alert_level"));
        if (!LEVELS.contains(level)) throw new FieldValidationException("min_alert_level", Msg.t(
                "Geçersiz alarm seviyesi — Uyarı, Yüksek ya da Kritik seçin.",
                "Invalid alert level — choose Warning, High or Critical."));
        AppUser user = requireUser(body.get("user_id"), session);
        String userName = user.getDisplayName() != null && !user.getDisplayName().isBlank() ? user.getDisplayName() : user.getUsername();

        Map<Long, Team> teams = new HashMap<>();
        for (Team t : teamRepo.findAllById(teamIds)) teams.put(t.getId(), t);
        // Aynı kullanıcının mevcut kişi kayıtları (takım → kayıt) — ikinci kayıt açılmaz, mevcut olan rapora yazılır.
        Map<Long, EscalationContact> existing = new HashMap<>();
        for (EscalationContact c : contactRepo.findByUserId(user.getId())) {
            if (c.getTeamId() != null) existing.putIfAbsent(c.getTeamId(), c);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        List<Long> doneTeams = new ArrayList<>();
        int done = 0, skipped = 0, failed = 0;
        String ts = ISO.format(Instant.now());
        for (Long teamId : teamIds) {
            Team team = teams.get(teamId);
            if (team == null || !SessionScope.canView(session, teamId)) {
                results.add(row(teamId, null, teamId, null, "skipped", NOT_FOUND)); skipped++; continue;
            }
            if (!SessionScope.canManage(session, teamId)) {
                results.add(row(teamId, team.getName(), teamId, team.getName(), "skipped", OUT_OF_SCOPE)); skipped++; continue;
            }
            EscalationContact prior = existing.get(teamId);
            if (prior != null) {
                Map<String, Object> r = row(teamId, team.getName(), teamId, team.getName(), "skipped", ALREADY_EXISTS);
                r.put("contact_id", prior.getId());
                r.put("existing_level", prior.getMinAlertLevel());
                r.put("existing_active", Boolean.TRUE.equals(prior.getActive()));
                results.add(r); skipped++; continue;
            }
            try {
                EscalationContact c = new EscalationContact();
                c.setUserId(user.getId());
                c.setName(userName);
                c.setEmail(user.getEmail());
                c.setRole(role);
                c.setMinAlertLevel(level);
                c.setTeamId(teamId);
                c.setActive(true);
                c.setCreatedAt(ts);
                EscalationContact saved = contactRepo.save(c);
                auditService.recordAction("CONTACT_CREATE", session, "ESCALATION_CONTACT", String.valueOf(saved.getId()), saved.getName(), null);
                Map<String, Object> r = row(teamId, team.getName(), teamId, team.getName(), "done", DONE);
                r.put("contact_id", saved.getId());
                results.add(r);
                doneTeams.add(teamId);
                done++;
            } catch (RuntimeException e) {
                log.warn("Bulk contact create failed for team={}: {}", teamId, e.toString());
                Map<String, Object> r = row(teamId, team.getName(), teamId, team.getName(), "failed", ERROR);
                r.put("message", ErrorTexts.safeMessage(e, "Kişi eklenemedi.", "The contact couldn't be added."));
                results.add(r);
                failed++;
            }
        }
        auditService.recordAction("CONTACT_BULK_CREATE", session, request, "ESCALATION_CONTACT", "bulk",
                AuditDetail.of("user", user.getUsername(), "name", userName, "role", role, "level", level,
                        "requested", teamIds.size(), "done", done, "skipped", skipped, "failed", failed, "team_ids", joinIds(doneTeams)));
        Map<String, Object> data = summary("create", done, skipped, failed, results);
        data.put("contact_name", userName);
        return ok(data);
    }

    /**
     * Kişiye bağlanacak kullanıcı: zorunlu, var, AKTİF (pasif kullanıcı bildirim almaz — seçici de yalnız aktifleri sunar)
     * ve global olmayan yazar için GÖRÜŞ kapsamında (tekil eklemeyle aynı kural: {@code AdminController.applyContactFields}).
     */
    private AppUser requireUser(Object raw, HttpSession session) {
        boolean has = raw != null && !String.valueOf(raw).isBlank();
        Long userId = has ? UserPublicIds.resolve(userPublicIds, raw, session) : null;
        AppUser u = userId == null ? null : userRepo.findById(userId).orElse(null);
        String notFound = Msg.t(
                "Seçilen kullanıcı bulunamadı ya da yönetim kapsamınızda değil. Listeden kendi takımlarınızdaki bir kullanıcıyı seçin; liste eskiyse sayfayı yenileyin.",
                "The selected user wasn’t found or isn’t in your scope. Pick a user from your own teams in the list; reload the page if the list is out of date.");
        if (!has) throw new FieldValidationException("user_id", Msg.t(
                "Eskalasyon kişisi olacak kullanıcıyı seçin.", "Choose the user who will be the escalation contact."));
        if (u == null) throw new FieldValidationException("user_id", notFound);
        if (!SessionScope.isGlobalAdmin(session)) {
            List<Long> scope = SessionScope.viewTeamIds(session);
            boolean visible = scope != null && ((u.getTeamId() != null && scope.contains(u.getTeamId()))
                    || (u.getTeamIds() != null && u.getTeamIds().stream().anyMatch(scope::contains)));
            if (!visible) {
                log.warn("Bulk contact user outside scope by user={} targetUser={}", actor(session), userId);
                throw new FieldValidationException("user_id", notFound);
            }
        }
        if (!Boolean.TRUE.equals(u.getActive())) throw new FieldValidationException("user_id", Msg.t(
                "Seçilen kullanıcı pasif — pasif hesap bildirim almaz. Aktif bir kullanıcı seçin.",
                "The selected user is inactive — inactive accounts receive no notifications. Choose an active user."));
        if (u.getEmail() == null || u.getEmail().isBlank()) throw new FieldValidationException("user_id", Msg.t(
                "Seçilen kullanıcının e-posta adresi yok — eskalasyon e-postası gidemez. Adresi olan bir kullanıcı seçin.",
                "The selected user has no e-mail address — escalation e-mails can't be sent. Choose a user with an address."));
        return u;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────

    /** {@code AdminController.requireAdminOrTeamAdmin} ile aynı: global yönetici ya da yönettiği takım olan. */
    private static void requireAdminOrTeamAdmin(HttpSession session) {
        if (SessionScope.isGlobalAdmin(session)) return;
        List<Long> m = SessionScope.manageTeamIds(session);
        if (m != null && !m.isEmpty()) return;
        log.warn("Non-admin/team-admin bulk contact attempt by user={}", actor(session));
        throw new SecurityException("Admin or team-admin required");
    }

    /** {@code AdminController.isTeamAdmin} ile aynı ölçüt (rol değil izin). */
    private boolean isTeamAdmin(HttpSession session) {
        return permissionService.allows(session, "system.team_admin", "execute")
                && !permissionService.allows(session, "system.global_admin", "execute");
    }

    private Map<Long, String> teamNames() {
        Map<Long, String> m = new HashMap<>();
        for (Team t : teamRepo.findAll()) m.put(t.getId(), t.getName());
        return m;
    }

    static List<Long> parseIds(Object raw, String field) {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        if (raw instanceof java.util.Collection<?> c) {
            if (c.size() > MAX_ITEMS * 4) throw tooMany(field, c.size());
            for (Object o : c) { Long v = toLong(o); if (v != null) out.add(v); }
        }
        if (out.isEmpty()) throw new FieldValidationException(field, Msg.t(
                "En az bir kayıt seçin.", "Select at least one record."));
        if (out.size() > MAX_ITEMS) throw tooMany(field, out.size());
        return new ArrayList<>(out);
    }

    private static FieldValidationException tooMany(String field, int n) {
        return new FieldValidationException(field, Msg.t(
                "Tek seferde en çok " + MAX_ITEMS + " kayıt işlenebilir (seçilen: " + n + "). Seçimi küçültüp işlemi parçalar hâlinde tekrarlayın.",
                "At most " + MAX_ITEMS + " records can be processed at once (selected: " + n + "). Select fewer records and repeat the action in parts."));
    }

    static Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String upper(Object o) {
        return o == null ? "" : o.toString().trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) { if (sb.length() > 0) sb.append(','); sb.append(id); }
        return sb.toString();
    }

    private static Map<String, Object> row(Long id, String name, Long teamId, String teamName, String status, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("team_id", teamId);
        m.put("team_name", teamName);
        m.put("status", status);
        m.put("reason", reason);
        return m;
    }

    private static Map<String, Object> summary(String action, int done, int skipped, int failed, List<Map<String, Object>> results) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("action", action);
        d.put("done", done);
        d.put("skipped", skipped);
        d.put("failed", failed);
        d.put("results", results);
        return d;
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(body);
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }
}

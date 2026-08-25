package com.sitemonitor.controller;

import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.NotificationGroupService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Takım Bildirim Grupları — alarm e-postalarının gideceği adlandırılmış alıcı listeleri.
 *
 * <h2>İki yetki ilkesi ({@code ScriptedTemplateController} emsali)</h2>
 * <ul>
 *   <li><b>Okuma:</b> {@code SessionScope.canView} — takım görüş kapsamında olmalı. Grubun
 *       {@code teamId}'si ASLA null olamaz, yani "Genel şablon" istisnasının karşılığı yoktur.</li>
 *   <li><b>Yazma:</b> {@code canManage} YETMEZ — USER'ın manage kapsamı boştur, oysa alarmı
 *       kimin alacağı takımın nöbet düzenidir, yönetici işlemi değil (K2). Bu yüzden
 *       {@code isMemberOf} de kabul edilir.</li>
 * </ul>
 *
 * <h2>Durum kodu değişmezi</h2>
 * Okunamayan grup her fiil için <b>404</b> (id'ler küçük ve tahmin edilebilir; başka takımın
 * grubunun VAR OLDUĞU bilgisi bile sızmamalı). Okunabilir ama yazılamazsa <b>403</b>.
 * İkisi de {@code GlobalExceptionHandler} üzerinden güvenlik olayı olarak denetlenir.
 */
@Slf4j
@RestController
@RequestMapping("/api/notification-groups")
@RequiredArgsConstructor
public class NotificationGroupController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String PERM = "notification.groups";
    private static final String NOT_FOUND_MSG = "Bildirim grubu bulunamadı";

    /** Denetim diff'ine giren alanlar — adres listesi DAHİL: "alarmı kim alıyor" sorusunun cevabı. */
    private static final String[] FIELDS = { "name", "emails", "isDefault", "active", "teamId" };

    private final NotificationGroupRepository groupRepo;
    private final NotificationGroupService groupService;
    private final TeamRepository teamRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;

    // ── Yanıt yardımcıları ───────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", ISO.format(Instant.now())));
    }

    private ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("success", false, "error", NOT_FOUND_MSG));
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }

    private static String actorName(HttpSession session) {
        Object n = session != null ? session.getAttribute("fullName") : null;
        return n != null && !n.toString().isBlank() ? n.toString() : actor(session);
    }

    // ── Yetki ────────────────────────────────────────────────────────────────

    /** Bu takıma grup yazılabilir mi? Üyelik VEYA yönetim yetkisi yeter (K2). */
    private static boolean canWriteTeam(HttpSession session, Long teamId) {
        if (teamId == null) return false;
        return SessionScope.isGlobalAdmin(session)
                || SessionScope.canManage(session, teamId)
                || SessionScope.isMemberOf(session, teamId);
    }

    /**
     * Kullanıcının grup yazabildiği takımlar — arayüz takım seçicisini BUNDAN doldurur.
     *
     * <p>{@link #canWriteTeam} ile AYNI cevabı vermek zorundadır: global admin orada her takıma
     * yazabiliyor, burada listelenmezse admin'e "yazabileceğin takım yok" denirdi (şablon
     * kütüphanesinde yaşanan kusurun aynısı).
     */
    private List<Long> writableTeamIds(HttpSession session) {
        LinkedHashSet<Long> out = new LinkedHashSet<>(SessionScope.memberTeamIds(session));
        List<Long> manage = SessionScope.manageTeamIds(session);
        if (manage != null) out.addAll(manage);
        if (SessionScope.isGlobalAdmin(session)) {
            teamRepo.findAll().forEach(t -> out.add(t.getId()));
        }
        return new ArrayList<>(out);
    }

    /** Görülebilir takımlar — global görücü hepsini, diğerleri görüş kapsamını. */
    private List<Long> readableTeamIds(HttpSession session) {
        if (SessionScope.isGlobalViewer(session)) {
            return teamRepo.findAll().stream().map(Team::getId).toList();
        }
        List<Long> view = SessionScope.viewTeamIds(session);
        return view == null ? List.of() : view;
    }

    // ── Listeleme ────────────────────────────────────────────────────────────

    /**
     * @param teamId yalnız bu takımın grupları (monitör formundaki seçici bunu kullanır)
     * @param includeInactive silinmiş grupları da döndür — kayıtlı seçimi "silinmiş" rozetiyle
     *                        gösterebilmek için; varsayılan {@code false}
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long teamId,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            HttpSession session) {
        permissionService.require(session, PERM, "view");

        List<NotificationGroup> rows;
        if (teamId != null) {
            if (!SessionScope.canView(session, teamId)) return notFound();
            rows = includeInactive
                    ? groupRepo.findByTeamIdOrderByNameAsc(teamId)
                    : groupRepo.findByTeamIdAndActiveTrueOrderByNameAsc(teamId);
        } else {
            // Boş koleksiyonla IN sorgusu çalıştırmayız — takım başına dönülür, kapsam
            // zaten küçüktür (kullanıcı en fazla birkaç takımın üyesidir).
            rows = new ArrayList<>();
            for (Long id : readableTeamIds(session)) {
                rows.addAll(includeInactive
                        ? groupRepo.findByTeamIdOrderByNameAsc(id)
                        : groupRepo.findByTeamIdAndActiveTrueOrderByNameAsc(id));
            }
        }

        Map<Long, String> teamNames = new LinkedHashMap<>();
        teamRepo.findAll().forEach(t -> teamNames.put(t.getId(), t.getName()));
        Map<Long, String> teamEmails = new LinkedHashMap<>();
        teamRepo.findAll().forEach(t -> teamEmails.put(t.getId(), t.getEmail()));

        List<Map<String, Object>> items = rows.stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>(groupService.toDto(g));
            m.put("team_name", teamNames.get(g.getTeamId()));
            m.put("can_write", canWriteTeam(session, g.getTeamId()));
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", items);
        out.put("writable_team_ids", writableTeamIds(session));
        out.put("team_names", teamNames);
        // DÜRÜST BOŞ DURUM için: grup yoksa arayüz "alarmlar şu adrese gidiyor" diyebilsin.
        out.put("team_emails", teamEmails);
        return ok(out);
    }

    // ── Oluşturma ────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, PERM, "edit");
        Long teamId = resolveTeamId(body, session);
        if (!canWriteTeam(session, teamId))
            throw new SecurityException("Bu takımda bildirim grubu oluşturamazsınız");

        var in = groupService.validate(str(body.get("name")), emails(body.get("emails")), bool(body.get("is_default")));
        NotificationGroup saved = groupService.create(teamId, in, actor(session), actorName(session));

        auditService.recordAction("NOTIFICATION_GROUP_CREATE", session, "NOTIFICATION_GROUP",
                String.valueOf(saved.getId()), saved.getName(), null);
        return ok(groupService.toDto(saved));
    }

    /** POST'un kapsam çözümü: açıkça verilen takım, yoksa yazılabilir ilk takım. */
    private Long resolveTeamId(Map<String, Object> body, HttpSession session) {
        Long explicit = toLong(body.get("team_id"));
        if (explicit != null) return explicit;
        List<Long> writable = writableTeamIds(session);
        if (writable.isEmpty())
            throw new IllegalArgumentException("Grup oluşturabileceğiniz bir takım yok");
        return writable.get(0);
    }

    // ── Güncelleme ───────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                      @RequestBody Map<String, Object> body,
                                                      HttpSession session) {
        permissionService.require(session, PERM, "edit");
        NotificationGroup g = groupRepo.findById(id).orElse(null);
        if (g == null || !SessionScope.canView(session, g.getTeamId())) return notFound();
        if (!canWriteTeam(session, g.getTeamId()))
            throw new SecurityException("Bu grubu düzenleme yetkiniz yok");

        Map<String, Object> before = AuditDiff.snapshot(g, FIELDS);
        var in = groupService.validate(str(body.get("name")), emails(body.get("emails")), bool(body.get("is_default")));
        NotificationGroup saved = groupService.update(g, in, actor(session), actorName(session));

        auditService.recordAction("NOTIFICATION_GROUP_UPDATE", session, "NOTIFICATION_GROUP",
                String.valueOf(id), saved.getName(),
                AuditDiff.diff(before, AuditDiff.snapshot(saved, FIELDS)));
        return ok(groupService.toDto(saved));
    }

    /** Ayrı uç: "varsayılan yap" tek tıklık bir işlemdir, formun tamamını yeniden göndermez. */
    @PostMapping("/{id}/make-default")
    public ResponseEntity<Map<String, Object>> makeDefault(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, PERM, "edit");
        NotificationGroup g = groupRepo.findById(id).orElse(null);
        if (g == null || !SessionScope.canView(session, g.getTeamId())) return notFound();
        if (!canWriteTeam(session, g.getTeamId()))
            throw new SecurityException("Bu grubu düzenleme yetkiniz yok");
        if (!Boolean.TRUE.equals(g.getActive()))
            throw new IllegalArgumentException("Silinmiş grup varsayılan yapılamaz");

        Map<String, Object> before = AuditDiff.snapshot(g, FIELDS);
        NotificationGroup saved = groupService.makeDefault(g);
        auditService.recordAction("NOTIFICATION_GROUP_DEFAULT", session, "NOTIFICATION_GROUP",
                String.valueOf(id), saved.getName(),
                AuditDiff.diff(before, AuditDiff.snapshot(saved, FIELDS)));
        return ok(groupService.toDto(saved));
    }

    // ── Silme (yumuşak) ──────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, PERM, "edit");
        NotificationGroup g = groupRepo.findById(id).orElse(null);
        if (g == null || !SessionScope.canView(session, g.getTeamId())) return notFound();
        if (!canWriteTeam(session, g.getTeamId()))
            throw new SecurityException("Bu grubu silme yetkiniz yok");

        Map<String, Object> before = AuditDiff.snapshot(g, FIELDS);
        NotificationGroup saved = groupService.softDelete(g, actor(session), actorName(session));
        auditService.recordAction("NOTIFICATION_GROUP_DELETE", session, "NOTIFICATION_GROUP",
                String.valueOf(id), saved.getName(),
                AuditDiff.diff(before, AuditDiff.snapshot(saved, FIELDS)));
        return ok(Map.of("message", "Grup silindi", "id", id));
    }

    // ── Gövde ayrıştırma ─────────────────────────────────────────────────────

    private static String str(Object o) { return o != null ? o.toString() : null; }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** Adresler dizi ya da virgüllü tek dize olarak gelebilir; ikisi de kabul edilir. */
    private static List<String> emails(Object o) {
        if (o instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        if (o instanceof String s) return List.of(s);
        return List.of();
    }
}

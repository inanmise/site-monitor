package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.oncallroutine.OnCallRoutineCalendar.Period;
import com.sitemonitor.service.oncallroutine.OnCallRoutineCodes;
import com.sitemonitor.service.oncallroutine.OnCallRoutineErrors;
import com.sitemonitor.service.oncallroutine.OnCallRoutineSampleTemplate;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.Actor;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.Completion;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.Perms;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.Responsible;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.TeamCtx;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.TodaySummary;
import com.sitemonitor.service.oncallroutine.OnCallRoutineSettings;
import com.sitemonitor.service.oncallroutine.OnCallRoutineSignals;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStatsService;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.DayRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.ImageMeta;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.ProblemRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.TeamRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate;
import com.sitemonitor.service.oncallroutine.OnCallRoutineVisibility;
import com.sitemonitor.service.oncallroutine.OnCallRoutineWire;
import com.sitemonitor.service.techerror.TechErrorImageValidator;
import com.sitemonitor.service.userref.UserPublicIds;
import com.sitemonitor.util.ErrorTexts;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;

/**
 * Nöbet rutinleri uçları (2026-11-26, {@code /api/oncall-routines}). Kapsam kuralları bu sınıfın statik yardımcılarında —
 * TEK kaynak (§3.2): okuma = görüş + yönetim + üyelik takımları (kurum geneli görüntüleyici hepsi; paylaşım ve 7/24
 * operatörlüğü ASLA açmaz), çalışma / şablon = takımın üyesi, yöneticisi ya da global admin + matris izni + modül açık.
 * Görülemeyen ya da bilinmeyen takım 404 {@code TEAM_NOT_FOUND}; görülüp yazılamayan 403. Modül takım için kapalıysa (kurum
 * geneli görüntüleyici dışında) 403 {@code ROUTINE_MODULE_HIDDEN}; yazma her oturumda modülün açık olmasını ister.
 */
@Slf4j
@RestController
@RequestMapping("/api/oncall-routines")
public class OnCallRoutineController {

    static final String R_READ = "oncall_routines.read";
    static final String R_WORK = "oncall_routines.work";
    static final String R_MANAGE = "oncall_routines.manage";

    static final int UPLOADS_PER_MINUTE = 30;
    static final int EXPORTS_PER_MINUTE = 6;
    static final long WINDOW_MS = 60_000L;
    static final int ITEM_NOTE_MAX = 1000;
    static final int DAY_NOTE_MAX = 4000;
    /** Devralma notu sınırı = kolon ({@code note} VARCHAR(200)) — 200 üstü 400 {@code note} (sessiz kesme yok, inceleme #6). */
    static final int ASSIGN_NOTE_MAX = 200;
    static final int MAX_BULK_ITEMS = 200;
    static final int OVERVIEW_SCAN = 1000;
    /** Genel bakışta en çok gösterilen takım (ada göre ilkleri; fazlası {@code truncated: true}). */
    static final int OVERVIEW_MAX = 200;
    static final int MAX_EVENTS = 200;
    static final int MAX_PROBLEM_PAGE = 100;
    /** Görüntülenebilen gün aralığı (bugünden geri / ileri) — sınırsız tarih hesabı yapılmaz. */
    static final int VIEW_BACK_DAYS = 3 * 366;
    static final int VIEW_AHEAD_DAYS = 366;
    static final int CALENDAR_AHEAD_DAYS = 31;

    private final OnCallRoutineService svc;
    private final OnCallRoutineStatsService stats;
    private final PermissionService permissionService;
    private final AuditService auditService;

    @Autowired(required = false)
    private UserPublicIds userPublicIds;
    @Autowired(required = false)
    private OnCallRoutineSignals signals;
    @Autowired(required = false)
    private AppSettingsService appSettings;
    @Autowired(required = false)
    private SystemMaintenanceService maintenance;

    /** Kişi başı yük sınırlarının saati (testlerde sabitlenir). */
    LongSupplier clock = System::currentTimeMillis;
    final SlidingLimiter uploads = new SlidingLimiter(UPLOADS_PER_MINUTE, WINDOW_MS);
    final SlidingLimiter exports = new SlidingLimiter(EXPORTS_PER_MINUTE, WINDOW_MS);
    final Set<String> exportsRunning = ConcurrentHashMap.newKeySet();

    public OnCallRoutineController(OnCallRoutineService svc, OnCallRoutineStatsService stats,
                                   PermissionService permissionService, AuditService auditService) {
        this.svc = svc;
        this.stats = stats;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Kapsam kuralları (§3.2) — tek kaynak
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Okunabilir takımlar; {@code null} = hepsi (kurum geneli görüntüleyici). Paylaşım ve 7/24 EKLENMEZ. */
    public static Set<Long> readableTeams(HttpSession session) {
        if (SessionScope.isGlobalViewer(session)) return null;
        return OnCallRoutineVisibility.ownTeamIds(session);
    }

    static boolean inScope(HttpSession session, Long teamId) {
        if (teamId == null) return false;
        Set<Long> r = readableTeams(session);
        return r == null || r.contains(teamId);
    }

    /** Takımın kendi kapsamı: global admin, takımı yöneten ya da takımın üyesi. */
    static boolean ownTeam(HttpSession session, Long teamId) {
        return SessionScope.isGlobalAdmin(session) || SessionScope.canManage(session, teamId)
                || SessionScope.isMemberOf(session, teamId);
    }

    public static boolean canRead(HttpSession session, PermissionService ps, Long teamId) {
        return inScope(session, teamId) && ps.allows(session, R_READ, "view")
                && (SessionScope.isGlobalViewer(session) || OnCallRoutineVisibility.visible(teamId));
    }

    public static boolean canWork(HttpSession session, PermissionService ps, Long teamId) {
        return canRead(session, ps, teamId) && OnCallRoutineVisibility.visible(teamId)
                && ps.allows(session, R_WORK, "edit") && ownTeam(session, teamId);
    }

    public static boolean canEditTemplate(HttpSession session, PermissionService ps, Long teamId) {
        return canRead(session, ps, teamId) && OnCallRoutineVisibility.visible(teamId)
                && ps.allows(session, R_MANAGE, "edit") && ownTeam(session, teamId);
    }

    /** Başkasını sorumlu yapmak: yönetici ya da takımın ETKİN nöbet çizelgesi yok. */
    public static boolean canAssignOther(HttpSession session, Perms p, TeamCtx c) {
        return p.canWork() && (SessionScope.canManage(session, c.teamId()) || !c.scheduleEnabled());
    }

    /** Kendini sorumlu yapmak: takımın üyesi. */
    public static boolean canSelfAssign(HttpSession session, Perms p, Long teamId) {
        return p.canWork() && SessionScope.isMemberOf(session, teamId);
    }

    /** Oturumun takımdaki yetkileri. */
    Perms perms(HttpSession session, TeamCtx c) {
        Long t = c.teamId();
        boolean visible = OnCallRoutineVisibility.visible(t);
        boolean own = ownTeam(session, t);
        boolean work = visible && own && permissionService.allows(session, R_READ, "view")
                && permissionService.allows(session, R_WORK, "edit");
        boolean edit = visible && own && permissionService.allows(session, R_READ, "view")
                && permissionService.allows(session, R_MANAGE, "edit");
        boolean incidentView = permissionService.allows(session, "incidents.view", "view");
        BiPredicate<Long, Long> incidentReadable = (team, by) -> incidentView
                && IncidentController.canReadIncident(session, team, by);
        return new Perms(work, edit, SessionScope.canManage(session, t), SessionScope.isMemberOf(session, t),
                userId(session), incidentReadable);
    }

    /** Okuma kapısı: izin → kapsam (404) → modül (403) → bağlam. */
    TeamCtx readable(Long teamId, HttpSession session) {
        permissionService.require(session, R_READ, "view");
        if (!inScope(session, teamId)) throw OnCallRoutineErrors.teamNotFound();
        if (!SessionScope.isGlobalViewer(session) && !OnCallRoutineVisibility.visible(teamId)) {
            throw OnCallRoutineErrors.moduleHidden();
        }
        return svc.ctx(teamId);
    }

    static void requireWork(TeamCtx c, Perms p) {
        if (!OnCallRoutineVisibility.visible(c.teamId())) throw OnCallRoutineErrors.moduleHidden();
        if (!p.canWork()) {
            throw OnCallRoutineErrors.forbidden(
                    "Bu takımın nöbet rutininde işlem yapma yetkiniz yok (yalnız görüntüleme). Takımın üyesi ya da yöneticisi olmanız gerekir.",
                    "You can't act on this team's on-call routine (view only). You need to be a member or a manager of the team.");
        }
    }

    static void requireEdit(TeamCtx c, Perms p) {
        if (!OnCallRoutineVisibility.visible(c.teamId())) throw OnCallRoutineErrors.moduleHidden();
        if (!p.canEditTemplate()) {
            throw OnCallRoutineErrors.forbidden(
                    "Bu takımın rutin şablonunu ve ayarlarını değiştirme yetkiniz yok. Takımın üyesi ya da yöneticisi olmanız gerekir.",
                    "You can't change this team's routine template and settings. You need to be a member or a manager of the team.");
        }
    }

    static void requireConfigured(TeamCtx c) {
        if (!c.configured()) throw OnCallRoutineErrors.notConfigured();
    }

    private record ProblemCtx(ProblemRow pr, TeamCtx c, Perms p) { }

    /** Sorun kapısı: görülemeyen takımın sorunu da {@code PROBLEM_NOT_FOUND} (varlık sızmaz). */
    ProblemCtx problemCtx(Long id, HttpSession session, boolean write) {
        permissionService.require(session, R_READ, "view");
        ProblemRow pr = svc.problemOrThrow(id);
        if (!inScope(session, pr.teamId())) throw OnCallRoutineErrors.problemNotFound();
        if (!SessionScope.isGlobalViewer(session) && !OnCallRoutineVisibility.visible(pr.teamId())) {
            throw OnCallRoutineErrors.moduleHidden();
        }
        TeamCtx c = svc.ctx(pr.teamId());
        Perms p = perms(session, c);
        if (write) {
            requireWork(c, p);
            requireConfigured(c);
        }
        return new ProblemCtx(pr, c, p);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Okuma uçları (§4.1)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(HttpSession session) {
        permissionService.require(session, R_READ, "view");
        boolean global = SessionScope.isGlobalViewer(session);
        Set<Long> ids = new LinkedHashSet<>();
        if (global) {
            for (TeamRow r : svc.store().configuredTeams(OVERVIEW_SCAN)) ids.add(r.teamId());
            ids.addAll(SessionScope.memberTeamIds(session));
        } else {
            for (Long t : OnCallRoutineVisibility.ownTeamIds(session)) {
                if (OnCallRoutineVisibility.visible(t)) ids.add(t);
            }
        }
        ids.remove(null);
        // Ada göre sıralayıp keser, sonra yalnız gösterilecek takımların şablonunu okur (genel görüntüleyicide ≤ 1000 aday)
        OnCallRoutineService.CtxPage page = svc.ctxsByName(ids, OVERVIEW_MAX);
        List<TeamCtx> ctxs = page.ctxs();
        boolean truncated = page.truncated();
        Map<Long, TodaySummary> today = svc.todaySummaries(ctxs);
        Long me = userId(session);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TeamCtx c : ctxs) {
            Perms p = perms(session, c);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", c.teamId());
            m.put("team_name", c.teamName());
            m.put("member", p.isMember());
            m.put("can_work", p.canWork());
            m.put("can_edit_template", p.canEditTemplate());
            m.put("can_manage", p.canManage());
            m.put("configured", c.configured());
            m.put("enabled", c.enabled());
            m.put("tasks_active", c.row() == null ? 0 : c.row().tasksActive());
            m.put("module_visible", OnCallRoutineVisibility.visible(c.teamId()));
            TodaySummary s = today.get(c.teamId());
            if (s == null) {
                m.put("today", null);
                m.put("responsible", null);
            } else {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("date", s.date().toString());
                t.put("workday", s.workday());
                t.put("status", s.status());
                t.put("required", s.progress().required());
                t.put("resolved", s.progress().resolved());
                t.put("overdue", s.progress().overdue());
                t.put("problems_open", s.problemsOpen());
                m.put("today", t);
                Responsible r = s.responsible();
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("user_id", r.userId());
                rm.put("name", r.name());
                rm.put("is_me", r.userId() != null && r.userId().equals(me));
                rm.put("active", r.userId() != null && r.active());
                rm.put("source", r.source());
                m.put("responsible", rm);
            }
            rows.add(m);
        }
        Long primary = SessionScope.primaryTeamId(session);
        Long def = null;
        for (Map<String, Object> r : rows) if (r.get("team_id").equals(primary)) def = primary;
        if (def == null) {
            for (Map<String, Object> r : rows) {
                if (Boolean.TRUE.equals(r.get("member"))) {
                    def = (Long) r.get("team_id");
                    break;
                }
            }
        }
        if (def == null && !rows.isEmpty()) def = (Long) rows.get(0).get("team_id");
        OnCallRoutineVisibility vis = OnCallRoutineVisibility.current();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("module_visible", vis == null || vis.sessionSees(session));
        data.put("default_team_id", def);
        data.put("teams", rows);
        data.put("truncated", truncated);
        return ok(data);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        permissionService.require(session, R_READ, "view");
        Set<Long> ids = new LinkedHashSet<>(SessionScope.memberTeamIds(session));
        List<Long> manage = SessionScope.manageTeamIds(session);
        if (manage != null) ids.addAll(manage);
        ids.remove(null);
        ids.removeIf(t -> !OnCallRoutineVisibility.visible(t));
        List<TeamCtx> ctxs = new ArrayList<>(svc.ctxs(ids).stream().filter(c -> c.configured() && c.enabled()).toList());
        Collator coll = Collator.getInstance(Locale.forLanguageTag("tr"));
        ctxs.sort(Comparator.comparing((TeamCtx c) -> c.teamName() == null ? "" : c.teamName(), coll)
                .thenComparing(TeamCtx::teamId));
        if (ctxs.size() > 20) ctxs = new ArrayList<>(ctxs.subList(0, 20));
        Map<Long, TodaySummary> sums = svc.todaySummaries(ctxs);
        Long me = userId(session);
        LocalTime nowLocal = LocalDateTime.ofInstant(svc.now(), OnCallRoutineCodes.TZ).toLocalTime();
        int count = 0;
        boolean critical = false;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TeamCtx c : ctxs) {
            TodaySummary s = sums.get(c.teamId());
            if (s == null) continue;
            boolean mine = s.responsible().userId() != null && s.responsible().userId().equals(me);
            int pending = s.progress().pending();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", c.teamId());
            m.put("team_name", c.teamName());
            m.put("date", s.date().toString());
            m.put("workday", s.workday());
            m.put("status", s.status());
            m.put("required", s.progress().required());
            m.put("resolved", s.progress().resolved());
            m.put("pending", pending);
            m.put("overdue", s.progress().overdue());
            m.put("problems_open", s.problemsOpen());
            m.put("responsible_me", mine);
            m.put("responsible_name", s.responsible().name());
            m.put("next_reminder", svc.nextReminder(c, s));
            m.put("handover_pending_ack", s.handoverPendingAck());
            rows.add(m);
            boolean open = OnCallRoutineCodes.DAY_OPEN.equals(s.status()) || OnCallRoutineCodes.DAY_NONE.equals(s.status());
            if (mine && s.workday() && open && pending > 0) {
                count += pending;
                List<String> times = c.settings().reminders().times();
                boolean afterFirst = !times.isEmpty() && !nowLocal.isBefore(LocalTime.parse(times.get(0)));
                if (s.progress().overdue() > 0 || afterFirst) critical = true;
            }
        }
        Map<String, Object> badge = new LinkedHashMap<>();
        badge.put("count", count);
        badge.put("level", count == 0 ? "none" : critical ? "critical" : "warning");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generated_at", svc.now().toString());
        data.put("badge", badge);
        data.put("teams", rows);
        return ok(data);
    }

    @GetMapping("/teams/{teamId}/day")
    public ResponseEntity<Map<String, Object>> day(@PathVariable Long teamId,
                                                   @RequestParam(value = "date", required = false) String date,
                                                   HttpSession session) {
        TeamCtx c = readable(teamId, session);
        LocalDate d = viewDate(date, "date");
        return ok(svc.day(c, perms(session, c), d));
    }

    @GetMapping("/teams/{teamId}/week")
    public ResponseEntity<Map<String, Object>> week(@PathVariable Long teamId,
                                                    @RequestParam(value = "week", required = false) String week,
                                                    HttpSession session) {
        TeamCtx c = readable(teamId, session);
        LocalDate d = viewDate(week, "week");
        LocalDate monday = d.with(DayOfWeek.MONDAY);
        return ok(svc.week(c, perms(session, c), monday));
    }

    @GetMapping("/teams/{teamId}/template")
    public ResponseEntity<Map<String, Object>> template(@PathVariable Long teamId, HttpSession session) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        return ok(svc.templateView(c, p.canEditTemplate(), (r, a) -> permissionService.allows(session, r, a)));
    }

    @GetMapping("/teams/{teamId}/template/versions")
    public ResponseEntity<Map<String, Object>> versions(@PathVariable Long teamId, HttpSession session) {
        TeamCtx c = readable(teamId, session);
        return okList(svc.versions(c.teamId()));
    }

    @GetMapping("/teams/{teamId}/template/versions/{version}")
    public ResponseEntity<Map<String, Object>> version(@PathVariable Long teamId, @PathVariable int version,
                                                       HttpSession session) {
        TeamCtx c = readable(teamId, session);
        return ok(svc.version(c.teamId(), version));
    }

    @GetMapping("/sample-template")
    public ResponseEntity<Map<String, Object>> sampleTemplate(@RequestParam(value = "lang", required = false) String lang,
                                                              HttpSession session) {
        permissionService.require(session, R_READ, "view");
        String l = "en".equals(lang) ? "en" : "tr";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("template", OnCallRoutineTemplate.toWire(OnCallRoutineSampleTemplate.build(l)));
        return ok(data);
    }

    @GetMapping("/teams/{teamId}/people")
    public ResponseEntity<Map<String, Object>> people(@PathVariable Long teamId, HttpSession session) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireWork(c, p);
        return okList(svc.people(c.teamId(), p.me()));
    }

    @GetMapping("/teams/{teamId}/signals")
    public ResponseEntity<Map<String, Object>> signals(@PathVariable Long teamId,
                                                       @RequestParam(value = "fresh", required = false) String fresh,
                                                       HttpSession session) {
        TeamCtx c = readable(teamId, session);
        Set<String> wanted = new LinkedHashSet<>();
        for (OnCallRoutineTemplate.Section s : c.template().sections()) {
            for (OnCallRoutineTemplate.Task t : s.tasks()) if (t.active()) wanted.addAll(t.signals());
        }
        wanted.retainAll(OnCallRoutineSignals.KEYS);
        boolean isFresh = "1".equals(fresh) || "true".equalsIgnoreCase(fresh);
        if (signals == null) {
            Map<String, Object> sig = new LinkedHashMap<>();
            for (String k : OnCallRoutineSignals.KEYS) {
                if (!wanted.contains(k)) continue;
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("value", null);
                v.put("tone", "neutral");
                v.put("partial", false);
                v.put("hidden", false);
                v.put("error", "unavailable");
                v.put("link", null);
                sig.put(k, v);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("team_id", c.teamId());
            data.put("generated_at", svc.now().toString());
            data.put("signals", sig);
            return ok(data);
        }
        return ok(signals.compute(c.teamId(), wanted, (r, a) -> permissionService.allows(session, r, a),
                SessionScope.isGlobalViewer(session), isFresh, svc.today()));
    }

    @GetMapping("/teams/{teamId}/problems")
    public ResponseEntity<Map<String, Object>> problems(@PathVariable Long teamId,
                                                        @RequestParam(value = "status", required = false) String status,
                                                        @RequestParam(value = "page", required = false) Integer page,
                                                        @RequestParam(value = "size", required = false) Integer size,
                                                        HttpSession session) {
        TeamCtx c = readable(teamId, session);
        String st = status == null || status.isBlank() ? "open" : status;
        if (!List.of("open", "resolved", "all").contains(st)) {
            throw OnCallRoutineErrors.field("status", "Durum open, resolved ya da all olmalı.",
                    "The status must be open, resolved or all.");
        }
        int pg = page == null ? 0 : page;
        int sz = size == null ? 25 : size;
        if (pg < 0 || pg > 10_000) throw OnCallRoutineErrors.field("page", "Sayfa 0 ile 10000 arasında olmalı.",
                "The page must be between 0 and 10000.");
        if (sz < 1 || sz > MAX_PROBLEM_PAGE) {
            throw OnCallRoutineErrors.field("size", "Sayfa boyu 1 ile " + MAX_PROBLEM_PAGE + " arasında olmalı.",
                    "The page size must be between 1 and " + MAX_PROBLEM_PAGE + ".");
        }
        return ok(svc.problemList(c, perms(session, c), st, pg, sz));
    }

    @GetMapping("/teams/{teamId}/events")
    public ResponseEntity<Map<String, Object>> events(@PathVariable Long teamId,
                                                      @RequestParam(value = "date", required = false) String date,
                                                      @RequestParam(value = "before", required = false) Long before,
                                                      @RequestParam(value = "limit", required = false) Integer limit,
                                                      HttpSession session) {
        TeamCtx c = readable(teamId, session);
        String day = date == null || date.isBlank() ? null : OnCallRoutineService.parseDate(date, "date").toString();
        int lim = limit == null ? 100 : Math.max(1, Math.min(MAX_EVENTS, limit));
        return ok(svc.events(c.teamId(), day, before, lim));
    }

    @GetMapping("/teams/{teamId}/calendar")
    public ResponseEntity<Map<String, Object>> calendar(@PathVariable Long teamId,
                                                        @RequestParam(value = "from", required = false) String from,
                                                        @RequestParam(value = "to", required = false) String to,
                                                        HttpSession session) {
        TeamCtx c = readable(teamId, session);
        LocalDate today = svc.today();
        LocalDate[] r = range(from, to, today.minusDays(364), today, today.plusDays(CALENDAR_AHEAD_DAYS));
        return ok(svc.calendar(c, r[0], r[1]));
    }

    @GetMapping("/teams/{teamId}/stats")
    public ResponseEntity<Map<String, Object>> stats(@PathVariable Long teamId,
                                                     @RequestParam(value = "from", required = false) String from,
                                                     @RequestParam(value = "to", required = false) String to,
                                                     HttpSession session) {
        TeamCtx c = readable(teamId, session);
        LocalDate today = svc.today();
        LocalDate[] r = range(from, to, today.minusDays(29), today, today);
        return ok(stats.stats(c, r[0], r[1]));
    }

    @GetMapping("/teams/{teamId}/export")
    public ResponseEntity<Map<String, Object>> export(@PathVariable Long teamId,
                                                      @RequestParam(value = "from", required = false) String from,
                                                      @RequestParam(value = "to", required = false) String to,
                                                      HttpSession session, HttpServletRequest request) {
        TeamCtx c = readable(teamId, session);
        LocalDate today = svc.today();
        LocalDate[] r = range(from, to, today.minusDays(29), today, today);
        String key = userKey(session);
        // Yük sınırı kapsam ve doğrulamadan SONRA (reddedilen istek hak yemez)
        if (!exportsRunning.add(key)) {
            return tooMany("EXPORT_BUSY", Msg.t(
                    "Bir dışa aktarımınız zaten hazırlanıyor. Bitmesini bekleyip yeniden deneyin.",
                    "One of your exports is already being prepared. Wait for it to finish and try again."), 5);
        }
        try {
            long now = clock.getAsLong();
            Long retry = exports.reserve(key, now);
            if (retry != null) {
                return tooMany("RATE_LIMITED", Msg.t(
                        "Dışa aktarım sınırına ulaşıldı: kişi başına dakikada en çok " + EXPORTS_PER_MINUTE + " dışa aktarım. "
                                + retry + " saniye sonra yeniden deneyin.",
                        "Export limit reached: at most " + EXPORTS_PER_MINUTE + " exports per person per minute. Try again in "
                                + retry + " seconds."), retry);
            }
            Map<String, Object> data;
            try {
                data = stats.export(c, perms(session, c), r[0], r[1]);
            } catch (FieldValidationException e) {
                exports.refund(key, now);
                throw e;
            }
            auditService.recordAction(OnCallRoutineCodes.AUDIT_EXPORT, session, request, "TEAM", String.valueOf(c.teamId()),
                    AuditDetail.of("team_id", c.teamId(), "from", r[0].toString(), "to", r[1].toString(),
                            "days", sizeOf(data.get("days")), "items", sizeOf(data.get("items")),
                            "problems", sizeOf(data.get("problems"))));
            return ok(data);
        } finally {
            exportsRunning.remove(key);
        }
    }

    @GetMapping("/problems/{id}/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable Long id, @PathVariable Long imageId,
                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                        HttpSession session) {
        ProblemCtx pc;
        try {
            pc = problemCtx(id, session, false);
        } catch (CodedNotFoundException e) {
            throw OnCallRoutineErrors.imageNotFound();
        }
        ImageMeta meta = svc.imageMeta(imageId);
        if (meta == null || !id.equals(meta.problemId())) throw OnCallRoutineErrors.imageNotFound();
        String etag = "\"or-img-" + meta.id() + "-" + meta.sizeBytes() + "\"";
        if (etagMatches(ifNoneMatch, etag)) {
            return ResponseEntity.status(304)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                    .header("X-Content-Type-Options", "nosniff")
                    .build();
        }
        byte[] bytes = svc.imageBytes(imageId);
        if (bytes == null) throw OnCallRoutineErrors.imageNotFound();
        String name = meta.fileName() == null || meta.fileName().isBlank() ? "gorsel-" + meta.id() : meta.fileName();
        return ResponseEntity.ok()
                .contentType(servedType(meta.contentType()))
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Yazma uçları (§4.2)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @PutMapping("/teams/{teamId}/template")
    public ResponseEntity<Map<String, Object>> saveTemplate(@PathVariable Long teamId, @RequestBody Map<String, Object> body,
                                                            HttpSession session, HttpServletRequest request) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireEdit(c, p);
        // 512 KB: bildirilen gövde uzunluğu (biliniyorsa) — servis kanonik JSON'u da ölçer
        if (request != null) OnCallRoutineTemplate.checkSize(request.getContentLengthLong());
        Integer version = intField(body.get("version"), "version", true);
        if (!body.containsKey("template")) {
            throw OnCallRoutineErrors.field("template", "Şablon gönderilmedi.", "The template is missing.");
        }
        String source = body.get("source") instanceof String s ? s : null;
        OnCallRoutineService.SaveResult res = svc.saveTemplate(c.teamId(), version, body.get("template"), source,
                actor(session));
        if (res.changed() && res.audit() != null) {
            auditService.recordAction(OnCallRoutineCodes.AUDIT_TEMPLATE_SAVE, session, request, "TEAM",
                    String.valueOf(c.teamId()), AuditDetail.ofMap(res.audit()));
        }
        return ok(res.data());
    }

    @PutMapping("/teams/{teamId}/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(@PathVariable Long teamId, @RequestBody Map<String, Object> body,
                                                            HttpSession session, HttpServletRequest request) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireEdit(c, p);
        Integer version = intField(body.get("version"), "version", true);
        if (!(body.get("settings") instanceof Map<?, ?> patch)) {
            throw OnCallRoutineErrors.field("settings", "Ayarlar bir nesne olarak gönderilmeli.",
                    "The settings must be sent as an object.");
        }
        OnCallRoutineService.SaveResult res = svc.saveSettings(c.teamId(), version, patch, actor(session));
        if (res.changed() && res.audit() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> before = (Map<String, Object>) res.audit().get("before");
            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) res.audit().get("after");
            auditService.recordAction(OnCallRoutineCodes.AUDIT_SETTINGS_SAVE, session, request, "TEAM",
                    String.valueOf(c.teamId()), AuditDetail.of("team_id", c.teamId()), AuditDiff.diff(before, after));
        }
        return ok(res.data());
    }

    @PostMapping("/teams/{teamId}/items/state")
    public ResponseEntity<Map<String, Object>> setItemState(@PathVariable Long teamId, @RequestBody Map<String, Object> body,
                                                            HttpSession session) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireWork(c, p);
        requireConfigured(c);
        LocalDate d = OnCallRoutineService.parseDate(body.get("date"), "date");
        ItemRef ref = itemRef(body);
        String state = stateField(body.get("state"), List.of(OnCallRoutineCodes.DONE, OnCallRoutineCodes.PENDING,
                OnCallRoutineCodes.SKIPPED));
        String expected = expectedState(body.get("expected_state"));
        String reason = OnCallRoutineCodes.SKIPPED.equals(state)
                ? OnCallRoutineService.skipReason(body.get("skip_reason"), "skip_reason") : null;
        OnCallRoutineService.StateResult res = svc.applyState(c, d, ref.taskId(), ref.kind(), ref.key(), state, expected,
                reason, actor(session), true);
        Map<String, Object> data = svc.afterWrite(c, p, d, ref.kind(), ref.key(), ref.taskId());
        data.put("changed", !"unchanged".equals(res.status()));
        return ok(data);
    }

    @PostMapping("/teams/{teamId}/items/bulk-state")
    public ResponseEntity<Map<String, Object>> bulkItemState(@PathVariable Long teamId, @RequestBody Map<String, Object> body,
                                                             HttpSession session) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireWork(c, p);
        requireConfigured(c);
        LocalDate d = OnCallRoutineService.parseDate(body.get("date"), "date");
        String state = stateField(body.get("state"), List.of(OnCallRoutineCodes.DONE, OnCallRoutineCodes.PENDING));
        if (!(body.get("items") instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAX_BULK_ITEMS) {
            throw OnCallRoutineErrors.field("items", "1 ile " + MAX_BULK_ITEMS + " arasında görev gönderin.",
                    "Send between 1 and " + MAX_BULK_ITEMS + " tasks.");
        }
        List<BulkItem> items = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) {
                throw OnCallRoutineErrors.field("items", "Her görev bir nesne olmalı.", "Each task must be an object.");
            }
            items.add(new BulkItem(itemRef(m), expectedState(m.get("expected_state"))));
        }
        items.sort(Comparator.comparing(i -> i.ref().kind() + ":" + i.ref().key() + ":" + i.ref().taskId()));
        // İstek düzeyi retler ÖNCE ve bütün olarak (hiçbir şey yazılmaz)
        LocalDate today = svc.today();
        svc.checkDayWrite(c, d, today);
        DayRow dayRow = svc.dayRow(c.teamId(), d);
        if (dayRow != null && OnCallRoutineCodes.completed(dayRow.status())
                && items.stream().anyMatch(i -> OnCallRoutineCodes.KIND_DAY.equals(i.ref().kind()))) {
            throw OnCallRoutineErrors.dayCompleted(OnCallRoutineService.dayBrief(dayRow));
        }
        Actor a = actor(session);
        List<Map<String, Object>> results = new ArrayList<>();
        int changed = 0;
        for (BulkItem it : items) {
            ItemRef ref = it.ref();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task_id", ref.taskId());
            row.put("period_kind", ref.kind());
            row.put("period_key", ref.key());
            String status;
            String message = null;
            try {
                OnCallRoutineService.StateResult res = svc.applyState(c, d, ref.taskId(), ref.kind(), ref.key(), state,
                        it.expected(), null, a, false);
                status = "unchanged".equals(res.status()) ? "unchanged" : "done";
                if ("done".equals(status)) changed++;
            } catch (CodedConflictException e) {
                if ("ITEM_CHANGED".equals(e.getCode())) {
                    status = "changed_meanwhile";
                } else {
                    status = "failed";
                    message = e.getMessage();
                }
            } catch (CodedNotFoundException | FieldValidationException e) {
                status = "failed";
                message = e.getMessage();
            } catch (RuntimeException e) {
                log.warn("Nöbet rutini toplu işaretinde bir görev yazılamadı (takım {}, {}): {}", c.teamId(), ref.taskId(),
                        e.getClass().getSimpleName());
                status = "failed";
                message = ErrorTexts.safeMessage(e, "Görev işaretlenemedi; sayfayı yenileyip yeniden deneyin.",
                        "The task couldn't be ticked; reload the page and try again.");
            }
            row.put("status", status);
            row.put("item", safeItem(c, p, d, ref));
            if (message != null) row.put("message", message);
            results.add(row);
        }
        if (changed > 0) svc.sectionEvent(c.teamId(), d, state, changed, a);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("results", results);
        Map<String, Object> after = svc.afterWrite(c, p, d, null, null, null);
        data.put("progress", after.get("progress"));
        data.put("day", after.get("day"));
        return ok(data);
    }

    @PutMapping("/teams/{teamId}/items/note")
    public ResponseEntity<Map<String, Object>> saveItemNote(@PathVariable Long teamId, @RequestBody Map<String, Object> body,
                                                            HttpSession session) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireWork(c, p);
        requireConfigured(c);
        LocalDate d = OnCallRoutineService.parseDate(body.get("date"), "date");
        ItemRef ref = itemRef(body);
        String note = OnCallRoutineService.freeNote(body.get("note"), ITEM_NOTE_MAX, "note");
        int nv = intField(body.get("note_version"), "note_version", true);
        OnCallRoutineService.NoteResult res = svc.saveItemNote(c, d, ref.taskId(), ref.kind(), ref.key(), note, nv,
                actor(session));
        Map<String, Object> data = new LinkedHashMap<>(res.current());
        data.put("changed", res.changed());
        data.put("item", svc.itemOnSheet(c, p, d, ref.kind(), ref.key(), ref.taskId()));
        return ok(data);
    }

    @PostMapping("/teams/{teamId}/problems")
    public ResponseEntity<Map<String, Object>> createProblem(@PathVariable Long teamId, @RequestBody Map<String, Object> body,
                                                             HttpSession session) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireWork(c, p);
        requireConfigured(c);
        LocalDate d = OnCallRoutineService.parseDate(body.get("date"), "date");
        ItemRef ref = body.get("task_id") == null ? null : itemRef(body);
        String severity = OnCallRoutineService.severityOf(body.get("severity"), "MEDIUM");
        String note = OnCallRoutineService.problemNote(body.get("note"));
        boolean markDone = !Boolean.FALSE.equals(body.get("mark_done"));
        OnCallRoutineService.ProblemCreated res = svc.createProblem(c, d, ref == null ? null : ref.taskId(),
                ref == null ? null : ref.kind(), ref == null ? null : ref.key(), severity, note, markDone, actor(session));
        Map<String, Object> after = svc.afterWrite(c, p, d, ref == null ? null : ref.kind(), ref == null ? null : ref.key(),
                ref == null ? null : ref.taskId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("problem", svc.problemWire(c, p, res.problem()));
        if (ref != null) data.put("item", after.get("item"));
        data.put("progress", after.get("progress"));
        data.put("day", after.get("day"));
        return ok(data);
    }

    @PatchMapping("/problems/{id}")
    public ResponseEntity<Map<String, Object>> updateProblem(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                             HttpSession session) {
        ProblemCtx pc = problemCtx(id, session, true);
        int version = intField(body.get("version"), "version", true);
        ProblemRow updated = svc.updateProblem(pc.pr(), version, body, actor(session), pc.c(), pc.p());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("problem", svc.problemWire(pc.c(), pc.p(), updated));
        return ok(data);
    }

    @DeleteMapping("/problems/{id}")
    public ResponseEntity<Map<String, Object>> deleteProblem(@PathVariable Long id, HttpSession session,
                                                             HttpServletRequest request) {
        ProblemCtx pc = problemCtx(id, session, true);
        if (!OnCallRoutineService.canDeleteProblem(pc.p(), pc.pr())) {
            throw OnCallRoutineErrors.forbidden(
                    "Bu sorunu yalnız açan kişi ya da takımın yöneticisi silebilir. Gerekirse çözüldü olarak işaretleyin.",
                    "Only the person who opened this problem or a team manager can delete it. Mark it resolved instead if needed.");
        }
        svc.deleteProblem(pc.pr(), actor(session));
        auditService.recordAction(OnCallRoutineCodes.AUDIT_PROBLEM_DELETE, session, request, "TEAM",
                String.valueOf(pc.c().teamId()), AuditDetail.of("team_id", pc.c().teamId(), "problem_id", pc.pr().id(),
                        "day", pc.pr().day(), "severity", pc.pr().severity()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", true);
        return ok(data);
    }

    @PostMapping("/problems/{id}/images")
    public ResponseEntity<Map<String, Object>> uploadProblemImage(@PathVariable Long id,
                                                                  @RequestParam("file") MultipartFile file,
                                                                  HttpSession session) throws IOException {
        ProblemCtx pc = problemCtx(id, session, true);
        if (file == null || file.isEmpty()) {
            throw OnCallRoutineErrors.field("file", "Dosya seçilmedi ya da boş.", "No file was chosen, or it's empty.");
        }
        if (file.getSize() > TechErrorImageValidator.MAX_BYTES) {
            throw OnCallRoutineErrors.field("file",
                    "Görsel en çok 5 MB olabilir. Ekran görüntüsünü küçültüp ya da JPEG olarak kaydedip yeniden yükleyin.",
                    "An image can be at most 5 MB. Make the screenshot smaller or save it as JPEG, then upload it again.");
        }
        long now = clock.getAsLong();
        String key = userKey(session);
        Long retry = uploads.reserve(key, now);
        if (retry != null) {
            return tooMany("RATE_LIMITED", Msg.t(
                    "Görsel yükleme sınırına ulaşıldı: kişi başına dakikada en çok " + UPLOADS_PER_MINUTE + " görsel. "
                            + retry + " saniye sonra yeniden deneyin; bu deneme sayılmadı.",
                    "Image upload limit reached: at most " + UPLOADS_PER_MINUTE + " images per person per minute. Try again in "
                            + retry + " seconds; this attempt wasn't counted."), retry);
        }
        ImageMeta meta;
        try {
            meta = svc.uploadImage(pc.pr(), file.getBytes(), file.getOriginalFilename(), actor(session));
        } catch (IllegalArgumentException | IllegalStateException | java.util.NoSuchElementException e) {
            // Doğrulamada (tür, boyut, piksel) ya da sınırda (görsel sayısı, takım toplamı) reddedilen ve hiçbir şey yazmayan
            // deneme dakika hakkını harcamaz (inceleme #8) — kişi düzeltip hemen yeniden deneyebilir.
            uploads.refund(key, now);
            throw e;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("image", OnCallRoutineWire.image(meta));
        return ok(data);
    }

    @DeleteMapping("/problems/{id}/images/{imageId}")
    public ResponseEntity<Map<String, Object>> deleteProblemImage(@PathVariable Long id, @PathVariable Long imageId,
                                                                  HttpSession session) {
        ProblemCtx pc = problemCtx(id, session, true);
        svc.deleteImage(pc.pr(), imageId, actor(session));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", true);
        return ok(data);
    }

    @PutMapping("/problems/{id}/incident")
    public ResponseEntity<Map<String, Object>> linkIncident(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                            HttpSession session, HttpServletRequest request) {
        ProblemCtx pc = problemCtx(id, session, true);
        int version = intField(body.get("version"), "version", true);
        Long incidentId = longField(body.get("incident_id"), "incident_id");
        if (incidentId != null) {
            IncidentRecord rec = svc.incident(incidentId);
            if (rec == null || !pc.p().incidentReadable().test(rec.getTeamId(), rec.getCreatedByTeamId())) {
                throw OnCallRoutineErrors.incidentNotFound();
            }
        }
        ProblemRow updated = svc.linkIncident(pc.pr(), version, incidentId, actor(session), pc.c(), pc.p());
        auditService.recordAction(OnCallRoutineCodes.AUDIT_INCIDENT_LINK, session, request, "TEAM",
                String.valueOf(pc.c().teamId()), AuditDetail.of("team_id", pc.c().teamId(), "problem_id", pc.pr().id(),
                        "incident_id", incidentId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("problem", svc.problemWire(pc.c(), pc.p(), updated));
        return ok(data);
    }

    @PostMapping("/teams/{teamId}/days/{date}/complete")
    public ResponseEntity<Map<String, Object>> completeDay(@PathVariable Long teamId, @PathVariable String date,
                                                           @RequestBody(required = false) Map<String, Object> body,
                                                           HttpSession session, HttpServletRequest request) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireWork(c, p);
        requireConfigured(c);
        Map<String, Object> b = body == null ? Map.of() : body;
        LocalDate d = OnCallRoutineService.parseDate(date, "date");
        Integer version = intField(b.get("version"), "version", true);
        String skipReason = null;
        Object sp = b.get("skip_pending");
        if (sp != null) {
            if (!(sp instanceof Map<?, ?> spm)) {
                throw OnCallRoutineErrors.field("skip_pending.reason", "Atlama nedeni gönderilmeli.",
                        "A skip reason must be sent.");
            }
            skipReason = OnCallRoutineService.skipReason(spm.get("reason"), "skip_pending.reason");
        }
        String note = b.get("note") == null ? null : OnCallRoutineService.freeNote(b.get("note"), DAY_NOTE_MAX, "note");
        Completion done = svc.completeDay(c, d, version, skipReason, note, actor(session));
        auditService.recordAction(OnCallRoutineCodes.AUDIT_DAY_COMPLETE, session, request, "TEAM",
                String.valueOf(c.teamId()), AuditDetail.ofMap(done.audit()));
        svc.kickNotices();
        int problemsOpen = done.audit().get("problems_open") instanceof Number n ? n.intValue() : 0;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("day", OnCallRoutineWire.day(done.day(), done.status()));
        data.put("progress", OnCallRoutineWire.progress(done.progress(), problemsOpen));
        data.put("notice", noticeInfo(c));
        return ok(data);
    }

    /** Tamamlama bildiriminin durumu (yanıt için; gönderim arka planda). */
    Map<String, Object> noticeInfo(TeamCtx c) {
        OnCallRoutineSettings.Notice n = c.settings().completionNotice();
        boolean adminOn = OnCallRoutineVisibility.switchOn(appSettings, OnCallRoutineVisibility.KEY_COMPLETION_NOTICE);
        boolean enabled = adminOn && n.enabled() && (n.mail() || n.push());
        boolean muted = false;
        try {
            muted = maintenance != null && maintenance.notificationsMuted();
        } catch (RuntimeException e) {
            log.debug("Sistem bakımı durumu okunamadı (bildirim yanıtı): {}", e.toString());
        }
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("mail", enabled && n.mail());
        ch.put("push", enabled && n.push());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("channels", ch);
        m.put("reason", !enabled ? "DISABLED" : muted ? "MAINTENANCE" : null);
        return m;
    }

    @PostMapping("/teams/{teamId}/days/{date}/reopen")
    public ResponseEntity<Map<String, Object>> reopenDay(@PathVariable Long teamId, @PathVariable String date,
                                                         @RequestBody(required = false) Map<String, Object> body,
                                                         HttpSession session, HttpServletRequest request) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireWork(c, p);
        requireConfigured(c);
        LocalDate d = OnCallRoutineService.parseDate(date, "date");
        Integer version = intField(body == null ? null : body.get("version"), "version", true);
        DayRow row = svc.dayRow(c.teamId(), d);
        if (row != null && OnCallRoutineCodes.completed(row.status())
                && !OnCallRoutineService.canReopen(p, row, svc.responsible(c, d))) {
            throw OnCallRoutineErrors.forbidden(
                    "Günü yalnız tamamlayan kişi, günün sorumlusu ya da takımın yöneticisi yeniden açabilir.",
                    "Only the person who completed the day, the day's responsible person or a team manager can reopen it.");
        }
        DayRow reopened = svc.reopenDay(c, d, version, actor(session));
        auditService.recordAction(OnCallRoutineCodes.AUDIT_DAY_REOPEN, session, request, "TEAM", String.valueOf(c.teamId()),
                AuditDetail.of("team_id", c.teamId(), "date", d.toString(), "from", row == null ? null : row.status()));
        Map<String, Object> after = svc.afterWrite(c, p, d, null, null, null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("day", OnCallRoutineWire.day(reopened, svc.wireStatus(c, d, reopened, svc.today())));
        data.put("progress", after.get("progress"));
        return ok(data);
    }

    @PutMapping("/teams/{teamId}/days/{date}/note")
    public ResponseEntity<Map<String, Object>> saveDayNote(@PathVariable Long teamId, @PathVariable String date,
                                                           @RequestBody Map<String, Object> body, HttpSession session) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireWork(c, p);
        requireConfigured(c);
        LocalDate d = OnCallRoutineService.parseDate(date, "date");
        String note = OnCallRoutineService.freeNote(body.get("note"), DAY_NOTE_MAX, "note");
        int nv = intField(body.get("note_version"), "note_version", true);
        OnCallRoutineService.NoteResult res = svc.saveDayNote(c, d, note, nv, actor(session));
        Map<String, Object> data = new LinkedHashMap<>(res.current());
        data.put("changed", res.changed());
        return ok(data);
    }

    @PutMapping("/teams/{teamId}/handovers/{periodKey}/note")
    public ResponseEntity<Map<String, Object>> saveHandoverNote(@PathVariable Long teamId, @PathVariable String periodKey,
                                                                @RequestBody Map<String, Object> body, HttpSession session) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireWork(c, p);
        requireConfigured(c);
        Period period = svc.periodForKey(c, periodKey, true);
        String note = OnCallRoutineService.freeNote(body.get("note"), DAY_NOTE_MAX, "note");
        int nv = intField(body.get("note_version"), "note_version", true);
        OnCallRoutineService.NoteResult res = svc.saveHandoverNote(c, period, note, nv, actor(session));
        Map<String, Object> data = new LinkedHashMap<>(res.current());
        data.put("changed", res.changed());
        return ok(data);
    }

    @PostMapping("/teams/{teamId}/handovers/{periodKey}/ack")
    public ResponseEntity<Map<String, Object>> ackHandover(@PathVariable Long teamId, @PathVariable String periodKey,
                                                           HttpSession session, HttpServletRequest request) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireWork(c, p);
        requireConfigured(c);
        Period period = svc.periodForKey(c, periodKey, true);
        OnCallRoutineService.AckResult res = svc.ackHandover(c, period, actor(session));
        if (res.changed()) {
            auditService.recordAction(OnCallRoutineCodes.AUDIT_HANDOVER_ACK, session, request, "TEAM",
                    String.valueOf(c.teamId()), AuditDetail.of("team_id", c.teamId(), "period_key", periodKey));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("changed", res.changed());
        data.put("acknowledged", res.row() != null && res.row().ackAt() != null);
        data.put("ack_by_name", res.row() == null ? null : res.row().ackByName());
        data.put("ack_at", res.row() == null ? null : OnCallRoutineWire.z(res.row().ackAt()));
        return ok(data);
    }

    @PostMapping("/teams/{teamId}/assignments")
    public ResponseEntity<Map<String, Object>> assign(@PathVariable Long teamId, @RequestBody Map<String, Object> body,
                                                      HttpSession session, HttpServletRequest request) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireWork(c, p);
        requireConfigured(c);
        String scope = body.get("scope") instanceof String s ? s : null;
        String key = body.get("key") instanceof String s ? s : null;
        LocalDate first = svc.assignmentFirstDay(c, scope, key);
        Long assignee = UserPublicIds.resolve(userPublicIds, body.get("user_id"), session);
        if (assignee == null) throw OnCallRoutineErrors.assigneeNotMember();
        boolean self = assignee.equals(p.me());
        if (self ? !canSelfAssign(session, p, c.teamId()) : !canAssignOther(session, p, c)) {
            throw OnCallRoutineErrors.assignNotAllowed();
        }
        if (!svc.activeMember(c.teamId(), assignee)) throw OnCallRoutineErrors.assigneeNotMember();
        String note = body.get("note") == null ? "" : OnCallRoutineService.freeNote(body.get("note"), ASSIGN_NOTE_MAX, "note");
        String name = svc.personName(assignee);
        Responsible r = svc.assign(c, scope, key, first, assignee, name, note.isEmpty() ? null : note, actor(session));
        auditService.recordAction(OnCallRoutineCodes.AUDIT_TAKEOVER, session, request, "TEAM", String.valueOf(c.teamId()),
                AuditDetail.of("team_id", c.teamId(), "scope", scope, "key", key, "assignee_name", name));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("responsible", svc.responsibleWire(c, r, p.me()));
        return ok(data);
    }

    // Yol değişkeni adı "key" değil: OtelAgentQueryRedactionGateTest kimlik benzeri yol değişkeni adını reddeder (URL aynı)
    @DeleteMapping("/teams/{teamId}/assignments/{scope}/{scopeKey}")
    public ResponseEntity<Map<String, Object>> clearAssignment(@PathVariable Long teamId, @PathVariable String scope,
                                                               @PathVariable("scopeKey") String key, HttpSession session,
                                                               HttpServletRequest request) {
        TeamCtx c = readable(teamId, session);
        Perms p = perms(session, c);
        requireWork(c, p);
        requireConfigured(c);
        LocalDate first = svc.assignmentFirstDay(c, scope, key);
        var existing = svc.assignment(c.teamId(), scope, key);
        boolean deleted = false;
        if (existing != null) {
            boolean self = existing.userId() != null && existing.userId().equals(p.me());
            if (self ? !canSelfAssign(session, p, c.teamId()) : !canAssignOther(session, p, c)) {
                throw OnCallRoutineErrors.assignNotAllowed();
            }
            deleted = svc.clearAssignment(c, scope, key, actor(session));
            if (deleted) {
                auditService.recordAction(OnCallRoutineCodes.AUDIT_TAKEOVER_CLEAR, session, request, "TEAM",
                        String.valueOf(c.teamId()), AuditDetail.of("team_id", c.teamId(), "scope", scope, "key", key,
                                "assignee_name", existing.userName()));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", deleted);
        data.put("responsible", svc.responsibleWire(c, svc.responsible(c, first), p.me()));
        return ok(data);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Yardımcılar
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    record ItemRef(String taskId, String kind, String key) { }

    private record BulkItem(ItemRef ref, String expected) { }

    static ItemRef itemRef(Map<?, ?> body) {
        Object t = body.get("task_id");
        if (!(t instanceof String task) || !OnCallRoutineTemplate.ID.matcher(task).matches()) {
            throw OnCallRoutineErrors.field("task_id", "Görev kimliği geçersiz. Sayfayı yenileyip yeniden deneyin.",
                    "The task id is invalid. Reload the page and try again.");
        }
        Object k = body.get("period_kind");
        if (!(k instanceof String kind) || !OnCallRoutineCodes.PERIOD_KINDS.contains(kind)) {
            throw OnCallRoutineErrors.field("period_kind", "Dönem türü DAY, WEEK ya da MONTH olmalı.",
                    "The period kind must be DAY, WEEK or MONTH.");
        }
        Object pk = body.get("period_key");
        if (!(pk instanceof String key) || key.isEmpty() || key.length() > 10) {
            throw OnCallRoutineErrors.periodMismatch();
        }
        return new ItemRef(task, kind, key);
    }

    static String stateField(Object raw, List<String> allowed) {
        if (raw instanceof String s && allowed.contains(s)) return s;
        throw OnCallRoutineErrors.field("state", "Durum " + String.join(", ", allowed) + " değerlerinden biri olmalı.",
                "The state must be one of " + String.join(", ", allowed) + ".");
    }

    static String expectedState(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String s && OnCallRoutineCodes.ITEM_STATES.contains(s)) return s;
        throw OnCallRoutineErrors.field("expected_state", "Beklenen durum geçersiz.", "The expected state is invalid.");
    }

    static Integer intField(Object raw, String field, boolean required) {
        if (raw == null) {
            if (!required) return null;
            throw OnCallRoutineErrors.field(field, "Sürüm bilgisi eksik. Sayfayı yenileyip yeniden deneyin.",
                    "The version is missing. Reload the page and try again.");
        }
        if (raw instanceof Integer i) return i;
        if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && Math.abs(n.doubleValue()) <= Integer.MAX_VALUE) {
            return n.intValue();
        }
        throw OnCallRoutineErrors.field(field, "Tam sayı olmalı.", "Must be a whole number.");
    }

    static Long longField(Object raw, String field) {
        if (raw == null) return null;
        if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && n.longValue() > 0) {
            return n.longValue();
        }
        if (raw instanceof String s && !s.isEmpty() && s.length() <= 18 && s.chars().allMatch(Character::isDigit)) {
            long v = Long.parseLong(s);
            if (v > 0) return v;
        }
        throw OnCallRoutineErrors.field(field, "Kimlik geçersiz.", "The id is invalid.");
    }

    /** Görüntülenecek gün: boş = bugün; [bugün − 3 yıl, bugün + 1 yıl] dışı alan hatası. */
    LocalDate viewDate(String raw, String field) {
        LocalDate today = svc.today();
        if (raw == null || raw.isBlank()) return today;
        LocalDate d = OnCallRoutineService.parseDate(raw, field);
        if (d.isBefore(today.minusDays(VIEW_BACK_DAYS)) || d.isAfter(today.plusDays(VIEW_AHEAD_DAYS))) {
            throw OnCallRoutineErrors.field(field, "Tarih en çok 3 yıl geriye ve 1 yıl ileriye gidebilir.",
                    "The date can go back at most 3 years and forward at most 1 year.");
        }
        return d;
    }

    /** Tarih aralığı: varsayılanlar, {@code to ≤ maxTo}, {@code from ≤ to}, ≤ 366 gün. */
    static LocalDate[] range(String from, String to, LocalDate defFrom, LocalDate defTo, LocalDate maxTo) {
        LocalDate t = to == null || to.isBlank() ? defTo : OnCallRoutineService.parseDate(to, "to");
        LocalDate f = from == null || from.isBlank() ? defFrom : OnCallRoutineService.parseDate(from, "from");
        if (t.isAfter(maxTo)) {
            throw OnCallRoutineErrors.field("to", "Bitiş tarihi en çok " + maxTo + " olabilir.",
                    "The end date can be at most " + maxTo + ".");
        }
        if (f.isAfter(t)) {
            throw OnCallRoutineErrors.field("from", "Başlangıç tarihi bitişten sonra olamaz.",
                    "The start date can't be after the end date.");
        }
        if (ChronoUnit.DAYS.between(f, t) + 1 > OnCallRoutineCodes.MAX_RANGE_DAYS) {
            throw OnCallRoutineErrors.field("from", "Aralık en çok " + OnCallRoutineCodes.MAX_RANGE_DAYS + " gün olabilir.",
                    "The range can be at most " + OnCallRoutineCodes.MAX_RANGE_DAYS + " days.");
        }
        return new LocalDate[] {f, t};
    }

    private Map<String, Object> safeItem(TeamCtx c, Perms p, LocalDate d, ItemRef ref) {
        try {
            return svc.itemOnSheet(c, p, d, ref.kind(), ref.key(), ref.taskId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int sizeOf(Object o) {
        return o instanceof List<?> l ? l.size() : 0;
    }

    /** {@code If-None-Match} eşleşmesi: liste, {@code *}, zayıf önek; sınırlı (≤ 1024 karakter, ≤ 32 parça). */
    static boolean etagMatches(String header, String etag) {
        if (header == null || header.isBlank() || header.length() > 1024) return false;
        String[] parts = header.split(",", 33);
        for (int i = 0; i < Math.min(parts.length, 32); i++) {
            String p = parts[i].trim();
            if ("*".equals(p)) return true;
            if (p.startsWith("W/")) p = p.substring(2);
            if (p.equals(etag)) return true;
        }
        return false;
    }

    /** Yalnız saklanan iki tür sunulur; beklenmeyen bir değer tarayıcıya "çalıştırılabilir" tür olarak gitmez. */
    static MediaType servedType(String stored) {
        if ("image/png".equals(stored)) return MediaType.IMAGE_PNG;
        if ("image/jpeg".equals(stored)) return MediaType.IMAGE_JPEG;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    static Long userId(HttpSession session) {
        Object v = session == null ? null : session.getAttribute("userId");
        if (v instanceof Number n) return n.longValue();
        try {
            return v == null ? null : Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Actor actor(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        Object dn = session == null ? null : session.getAttribute("displayName");
        String username = u == null ? null : String.valueOf(u);
        String name = dn instanceof String s && !s.isBlank() ? s : username;
        return new Actor(userId(session), name, username);
    }

    static String userKey(HttpSession session) {
        Long uid = userId(session);
        if (uid != null) return "u:" + uid;
        Object u = session.getAttribute("username");
        return u != null ? "n:" + u : "s:" + session.getId();
    }

    static ResponseEntity<Map<String, Object>> tooMany(String code, String message, long retryAfter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", message);
        String rid = MDC.get("requestId");
        if (rid != null) body.put("request_id", rid);
        body.put("retry_after_seconds", retryAfter);
        return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter)).body(body);
    }

    static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.ok(body);
    }

    static ResponseEntity<Map<String, Object>> okList(List<?> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.ok(body);
    }

    /** Kişi başı kayan pencere sınırı (pod belleği; yük sınırı). Reddedilen istek sayılmaz; sınırlı harita. */
    static final class SlidingLimiter {
        private final int max;
        private final long windowMs;
        private final Map<String, Deque<Long>> calls = new HashMap<>();

        SlidingLimiter(int max, long windowMs) {
            this.max = max;
            this.windowMs = windowMs;
        }

        /** Hak varsa ayırır ({@code null}); yoksa kaç saniye sonra deneneceği. */
        synchronized Long reserve(String key, long now) {
            if (calls.size() > 1_000) {
                calls.values().removeIf(q -> {
                    while (!q.isEmpty() && now - q.peekFirst() >= windowMs) q.pollFirst();
                    return q.isEmpty();
                });
                if (calls.size() > 10_000) calls.clear();
            }
            Deque<Long> q = calls.computeIfAbsent(key, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= windowMs) q.pollFirst();
            if (q.size() >= max) {
                long ms = q.peekFirst() + windowMs - now;
                return Math.max(1L, (ms + 999) / 1000);
            }
            q.addLast(now);
            return null;
        }

        /** Sayılmaması gereken bir denemeyi geri verir (aynı damga). */
        synchronized void refund(String key, long stamp) {
            Deque<Long> q = calls.get(key);
            if (q != null) q.removeLastOccurrence(stamp);
        }
    }
}

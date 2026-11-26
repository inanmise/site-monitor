package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.dbquery.HolidayCalendarService;
import com.sitemonitor.service.oncall.OnCallRotation;
import com.sitemonitor.service.oncall.OnCallService;
import com.sitemonitor.service.oncallroutine.OnCallRoutineCalendar.Period;
import com.sitemonitor.service.oncallroutine.OnCallRoutinePlan.Item;
import com.sitemonitor.service.oncallroutine.OnCallRoutinePlan.Progress;
import com.sitemonitor.service.oncallroutine.OnCallRoutinePlan.Row;
import com.sitemonitor.service.oncallroutine.OnCallRoutinePlan.Sheet;
import com.sitemonitor.service.oncallroutine.OnCallRoutineSettings.Settings;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.AssignmentRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.DayRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.HandoverRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.ImageMeta;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.ProblemRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.TeamRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorImageValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Nöbet rutinleri iş mantığı (2026-11-26): okuma (genel bakış, gün sayfası, hafta, şablon, sürümler, sorunlar, geçmiş) ve
 * yazma (şablon / ayar CAS, işaret, toplu işaret, not, sorun + görsel + olay bağı, gün tamamla / yeniden aç, gün / devir
 * notu, devir onayı, devralma) ve kapanış. Yazmalar {@link TransactionTemplate} ile dar işlemde; kilit sırası takım → gün →
 * görev. Kapsam / izin kararı denetleyicide ({@link Perms} ile gelir); bu sınıf yalnız kuralları uygular.
 */
@Slf4j
@Service
public class OnCallRoutineService {

    static final int MAX_OVERVIEW_TEAMS = 200;
    static final int MAX_ME_TEAMS = 20;
    static final int MAX_OPEN_PROBLEMS = 50;
    static final int MAX_PEOPLE = 300;

    private final OnCallRoutineStore store;
    private final TeamRepository teams;
    private final AppUserRepository users;
    private final TransactionTemplate tx;

    @Autowired(required = false) OnCallService onCall;
    @Autowired(required = false) HolidayCalendarService holidays;
    @Autowired(required = false) IncidentRecordRepository incidents;
    @Autowired(required = false) ObjectProvider<OnCallRoutineJob> job;

    /** Saat (testlerde sabitlenir). */
    Clock clock = Clock.systemUTC();

    public OnCallRoutineService(OnCallRoutineStore store, TeamRepository teams, AppUserRepository users,
                                PlatformTransactionManager txm) {
        this.store = store;
        this.teams = teams;
        this.users = users;
        this.tx = new TransactionTemplate(txm);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Bağlam
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** İşlemi yapan kişi (oturumdan). */
    public record Actor(Long id, String name, String username) {
        public static Actor system() {
            return new Actor(null, "SiteMonitor", null);
        }
    }

    /** Oturumun bu takımdaki yetkileri (denetleyici hesaplar). */
    public record Perms(boolean canWork, boolean canEditTemplate, boolean canManage, boolean isMember, Long me,
                        BiPredicate<Long, Long> incidentReadable) {
        public static Perms readOnly(Long me) {
            return new Perms(false, false, false, false, me, (a, b) -> false);
        }
    }

    /** Takım bağlamı: takım satırı, şablon, ayarlar, tatiller, çizelge. */
    public record TeamCtx(Long teamId, String teamName, boolean teamActive, TeamRow row, Template template,
                          boolean templateUnreadable, Settings settings, Set<LocalDate> holidays,
                          Map<LocalDate, String> holidayNames, OnCallRotation.Config rotation) {

        public boolean configured() {
            return row != null && row.configured();
        }

        public boolean enabled() {
            return row != null && row.enabled();
        }

        public LocalTime dayStart() {
            return settings.dayStartTime();
        }

        public boolean workday(LocalDate d) {
            return OnCallRoutineCalendar.isWorkday(d, settings, holidays);
        }

        /** Rutin bu gün duraklatılmış mı (ayar kapalı ve gün duraklatma tarihinden sonra). */
        public boolean paused(LocalDate d) {
            if (settings.enabled()) return false;
            String since = row == null ? null : row.pausedSince();
            return since == null || d.toString().compareTo(since) >= 0;
        }

        public int templateVersion() {
            return row == null ? 0 : row.templateVersion();
        }

        public Period period(LocalDate d) {
            return OnCallRoutineCalendar.handoverPeriod(rotation, dayStart(), d);
        }

        public boolean scheduleConfigured() {
            return rotation != null;
        }

        public boolean scheduleEnabled() {
            return rotation != null && rotation.enabled();
        }
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return OnCallRoutineCalendar.localDate(now());
    }

    LocalTime nowLocal() {
        return LocalDateTime.ofInstant(now(), OnCallRoutineCodes.TZ).toLocalTime();
    }

    String stamp() {
        return OnCallRoutineStore.ts(now());
    }

    public Team teamOrNull(Long teamId) {
        return teamId == null ? null : teams.findById(teamId).orElse(null);
    }

    public TeamCtx ctx(Long teamId) {
        Team t = teamOrNull(teamId);
        if (t == null) throw OnCallRoutineErrors.teamNotFound();
        TeamRow row = store.team(teamId);
        OnCallRotation.Config rot = onCall == null ? null : onCall.rotationConfig(teamId).orElse(null);
        return build(t, row, rot);
    }

    /** Toplu bağlam (sabit sorgu sayısı: takımlar, rutin satırları, çizelgeler). Bilinmeyen takım atlanır. */
    public List<TeamCtx> ctxs(Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) return List.of();
        Map<Long, Team> byId = new HashMap<>();
        for (Team t : teams.findAllById(teamIds)) byId.put(t.getId(), t);
        Map<Long, TeamRow> rows = new HashMap<>();
        for (TeamRow r : store.teams(teamIds)) rows.put(r.teamId(), r);
        Map<Long, OnCallRotation.Config> rots = onCall == null ? Map.of() : onCall.rotationConfigs(teamIds);
        List<TeamCtx> out = new ArrayList<>();
        for (Long id : teamIds) {
            Team t = byId.get(id);
            if (t == null) continue;
            out.add(build(t, rows.get(id), rots.get(id)));
        }
        return out;
    }

    /**
     * Ada göre sıralı (Türkçe harmanlama, sonra kimlik) ilk {@code limit} takımın bağlamı — sıralama ve kesme şablonlar
     * AYRIŞTIRILMADAN önce yapılır (genel görüntüleyicide 1000 takımın şablonu okunmasın). Sabit sorgu sayısı.
     */
    public record CtxPage(List<TeamCtx> ctxs, int total) {
        public boolean truncated() {
            return total > ctxs.size();
        }
    }

    public CtxPage ctxsByName(Collection<Long> teamIds, int limit) {
        if (teamIds == null || teamIds.isEmpty() || limit <= 0) return new CtxPage(List.of(), 0);
        List<Team> found = new ArrayList<>();
        for (Team t : teams.findAllById(teamIds)) if (t != null && t.getId() != null) found.add(t);
        int total = found.size();
        java.text.Collator coll = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("tr"));
        found.sort(Comparator.comparing((Team t) -> t.getName() == null ? "" : t.getName(), coll).thenComparing(Team::getId));
        if (found.size() > limit) found = new ArrayList<>(found.subList(0, limit));
        List<Long> ids = found.stream().map(Team::getId).toList();
        Map<Long, TeamRow> rows = new HashMap<>();
        for (TeamRow r : store.teams(ids)) rows.put(r.teamId(), r);
        Map<Long, OnCallRotation.Config> rots = onCall == null ? Map.of() : onCall.rotationConfigs(ids);
        List<TeamCtx> out = new ArrayList<>(found.size());
        for (Team t : found) out.add(build(t, rows.get(t.getId()), rots.get(t.getId())));
        return new CtxPage(out, total);
    }

    private TeamCtx build(Team t, TeamRow row, OnCallRotation.Config rot) {
        OnCallRoutineTemplate.Read read = row == null ? new OnCallRoutineTemplate.Read(Template.empty(), false)
                : OnCallRoutineTemplate.readStored(row.templateJson());
        Settings s = row == null ? OnCallRoutineSettings.defaults() : OnCallRoutineSettings.read(row.settingsJson());
        Set<LocalDate> hol = Set.of();
        Map<LocalDate, String> names = Map.of();
        if (holidays != null) {
            try {
                hol = holidays.days();
                names = holidays.names();
            } catch (RuntimeException e) {
                log.debug("Tatil takvimi okunamadı (nöbet rutini tatilsiz hesaplar): {}", e.toString());
            }
        }
        return new TeamCtx(t.getId(), t.getName(), !Boolean.FALSE.equals(t.getActive()), row, read.template(),
                read.unreadable(), s, hol, names, rot);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Sorumlu (§1.4)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * {@code ignored} = bu gün için kaydı olan ama devralanı artık takımın ETKİN üyesi olmadığı için yok sayılan devralma
     * (takımdan çıkarılmış ya da pasif kişi; inceleme 2026-11-26 #9) — sorumlu bir alt kaynağa (haftalık devralma / çizelge)
     * düşer, sayfa "devralan takımdan ayrıldı" der.
     */
    public record Responsible(Long userId, String name, String username, boolean active, String source,
                              AssignmentRow assignment, AssignmentRow ignored) {
        public Responsible(Long userId, String name, String username, boolean active, String source,
                           AssignmentRow assignment) {
            this(userId, name, username, active, source, assignment, null);
        }

        public static Responsible none() {
            return new Responsible(null, null, null, false, OnCallRoutineCodes.RESP_NONE, null, null);
        }

        Responsible withIgnored(AssignmentRow a) {
            return a == null || ignored != null ? this
                    : new Responsible(userId, name, username, active, source, assignment, a);
        }
    }

    record Person(Long id, String username, String name, String email, boolean active) { }

    public Responsible responsible(TeamCtx c, LocalDate d) {
        return responsibles(List.of(c), d).getOrDefault(c.teamId(), Responsible.none());
    }

    /** Takımların {@code d} günündeki sorumlusu (sabit sorgu: devralmalar + nöbet dilimleri + kişiler). */
    public Map<Long, Responsible> responsibles(List<TeamCtx> ctxs, LocalDate d) {
        Map<Long, Responsible> out = new HashMap<>();
        if (ctxs.isEmpty()) return out;
        Map<Long, String> periodKey = new HashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.add(d.toString());
        for (TeamCtx c : ctxs) {
            String k = c.period(d).key().toString();
            periodKey.put(c.teamId(), k);
            keys.add(k);
        }
        List<Long> ids = ctxs.stream().map(TeamCtx::teamId).toList();
        Map<Long, AssignmentRow> dayAssign = new HashMap<>();
        Map<Long, AssignmentRow> weekAssign = new HashMap<>();
        for (AssignmentRow a : store.assignmentsOf(ids, keys)) {
            if ("DAY".equals(a.scope()) && d.toString().equals(a.scopeKey())) dayAssign.put(a.teamId(), a);
            if ("WEEK".equals(a.scope()) && a.scopeKey().equals(periodKey.get(a.teamId()))) weekAssign.put(a.teamId(), a);
        }
        Set<Long> userIds = new HashSet<>();
        for (AssignmentRow a : dayAssign.values()) userIds.add(a.userId());
        for (AssignmentRow a : weekAssign.values()) userIds.add(a.userId());
        Map<Long, Person> people = persons(userIds);
        Set<Long> assignTeams = new HashSet<>(dayAssign.keySet());
        assignTeams.addAll(weekAssign.keySet());
        Map<Long, Set<Long>> memberOf = memberships(assignTeams);
        Map<Long, AssignmentRow> ignored = new HashMap<>();
        Map<Long, Instant> needSlot = new LinkedHashMap<>();
        for (TeamCtx c : ctxs) {
            AssignmentRow a = dayAssign.get(c.teamId());
            if (a != null) {
                if (takeoverValid(a, people, memberOf)) {
                    out.put(c.teamId(), fromPerson(people.get(a.userId()), OnCallRoutineCodes.RESP_TAKEOVER_DAY, a));
                    continue;
                }
                ignored.putIfAbsent(c.teamId(), a);
            }
            a = weekAssign.get(c.teamId());
            if (a != null) {
                if (takeoverValid(a, people, memberOf)) {
                    out.put(c.teamId(), fromPerson(people.get(a.userId()), OnCallRoutineCodes.RESP_TAKEOVER_WEEK, a)
                            .withIgnored(ignored.get(c.teamId())));
                    continue;
                }
                ignored.putIfAbsent(c.teamId(), a);
            }
            if (c.scheduleEnabled()) needSlot.put(c.teamId(), OnCallRoutineCalendar.at(d, c.dayStart()));
        }
        if (!needSlot.isEmpty() && onCall != null) {
            try {
                Map<Long, OnCallService.OnCallPerson> slots = onCall.slotsAt(needSlot);
                for (Map.Entry<Long, OnCallService.OnCallPerson> e : slots.entrySet()) {
                    OnCallService.OnCallPerson p = e.getValue();
                    String name = p.displayName() == null || p.displayName().isBlank() ? p.username() : p.displayName();
                    out.put(e.getKey(), new Responsible(p.userId(), name, p.username(), p.active(),
                            p.override() ? OnCallRoutineCodes.RESP_OVERRIDE : OnCallRoutineCodes.RESP_SCHEDULE, null));
                }
            } catch (RuntimeException ex) {
                log.debug("Nöbet dilimi okunamadı (rutin sorumlusu atanmamış görünür): {}", ex.toString());
            }
        }
        for (TeamCtx c : ctxs) {
            out.putIfAbsent(c.teamId(), Responsible.none());
            AssignmentRow ig = ignored.get(c.teamId());
            if (ig != null) out.computeIfPresent(c.teamId(), (k, r) -> r.withIgnored(ig));
        }
        return out;
    }

    /**
     * Devralma geçerli mi: kişi var VE (üyelik okunamadıysa) ya da takımın etkin üyesi. Üyelik okunamazsa (null) devralma
     * KORUNUR — okuma hatası kimsenin sorumluluğunu sessizce düşürmez.
     */
    private static boolean takeoverValid(AssignmentRow a, Map<Long, Person> people, Map<Long, Set<Long>> memberOf) {
        Person p = people.get(a.userId());
        if (p == null) return false;
        if (memberOf == null) return true;
        Set<Long> teams = memberOf.get(a.userId());
        return p.active() && teams != null && teams.contains(a.teamId());
    }

    /**
     * Kullanıcı → üyesi olduğu takımlar ({@code teamIds} ile kesişen; birincil takım VEYA çoklu üyelik) — TEK toplu sorgu.
     * Boş küme → boş harita (sorgu yok); okuma hatası → {@code null} (bilinmiyor).
     */
    Map<Long, Set<Long>> memberships(Set<Long> teamIds) {
        Map<Long, Set<Long>> out = new HashMap<>();
        if (teamIds.isEmpty()) return out;
        try {
            for (Object[] r : users.findActiveMemberRowsOfTeams(teamIds)) {
                if (r == null || r.length < 7 || !(r[0] instanceof Number id)) continue;
                Set<Long> s = out.computeIfAbsent(id.longValue(), k -> new HashSet<>());
                if (r[5] instanceof Number t && teamIds.contains(t.longValue())) s.add(t.longValue());
                if (r[6] instanceof Number t && teamIds.contains(t.longValue())) s.add(t.longValue());
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("Takım üyelikleri okunamadı (devralmalar korunur): {}", e.toString());
            return null;
        }
    }

    private static Responsible fromPerson(Person p, String source, AssignmentRow a) {
        return new Responsible(p.id(), p.name(), p.username(), p.active(), source, a);
    }

    Map<Long, Person> persons(Collection<Long> ids) {
        Map<Long, Person> out = new HashMap<>();
        Set<Long> wanted = new HashSet<>();
        for (Long id : ids) if (id != null) wanted.add(id);
        if (wanted.isEmpty()) return out;
        for (Object[] r : users.findOnCallPersonRows(wanted)) {
            if (r == null || r.length < 5 || !(r[0] instanceof Number id)) continue;
            String username = (String) r[1];
            String display = (String) r[2];
            String name = display == null || display.isBlank() ? username : display;
            out.put(id.longValue(), new Person(id.longValue(), username, name, (String) r[3], !Boolean.FALSE.equals(r[4])));
        }
        return out;
    }

    public Map<String, Object> responsibleWire(TeamCtx c, Responsible r, Long me) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", r.userId());
        m.put("name", r.name());
        m.put("username", r.username());
        m.put("active", r.userId() != null && r.active());
        m.put("is_me", r.userId() != null && r.userId().equals(me));
        m.put("source", r.source());
        m.put("schedule_configured", c.scheduleConfigured());
        m.put("schedule_enabled", c.scheduleEnabled());
        Map<String, Object> a = null;
        if (r.assignment() != null) {
            a = new LinkedHashMap<>();
            a.put("scope", r.assignment().scope());
            a.put("key", r.assignment().scopeKey());
            a.put("note", r.assignment().note());
            a.put("created_by_name", r.assignment().createdByName());
            a.put("created_at", OnCallRoutineWire.z(r.assignment().createdAt()));
        }
        m.put("assignment", a);
        Map<String, Object> ig = null;
        if (r.ignored() != null) {
            ig = new LinkedHashMap<>();
            ig.put("scope", r.ignored().scope());
            ig.put("key", r.ignored().scopeKey());
            ig.put("name", r.ignored().userName());
            ig.put("reason", "not_member");
        }
        m.put("ignored_assignment", ig);
        return m;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Gün sayfası (planı + durum)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Telde gün durumu (§1.6): satırdaki son durum önce, sonra gelecek / iş günü değil / duraklatılmış / satırsız. */
    public String wireStatus(TeamCtx c, LocalDate d, DayRow row, LocalDate today) {
        if (row != null && !OnCallRoutineCodes.DAY_OPEN.equals(row.status())) return row.status();
        if (d.isAfter(today)) return OnCallRoutineCodes.DAY_FUTURE;
        if (!c.workday(d)) return OnCallRoutineCodes.DAY_NOT_WORKDAY;
        if (c.paused(d)) return OnCallRoutineCodes.DAY_PAUSED;
        if (row != null) return row.status();
        return OnCallRoutineCodes.DAY_NONE;
    }

    /** Görünüm donmuş mu: satır tamamlanmış / kapanmış ya da gün düzenleme penceresinin dışında. */
    boolean frozen(DayRow row, LocalDate d, LocalDate today) {
        return (row != null && !OnCallRoutineCodes.DAY_OPEN.equals(row.status()))
                || d.isBefore(today.minusDays(OnCallRoutineCodes.EDIT_WINDOW_DAYS));
    }

    public Sheet sheet(TeamCtx c, LocalDate d, DayRow row, List<Row> rows, LocalDate today) {
        return OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), d, rows, frozen(row, d, today));
    }

    /** Günün sayfası — tam tel biçimi (§4.1 {@code /day}). */
    public Map<String, Object> day(TeamCtx c, Perms p, LocalDate d) {
        LocalDate today = today();
        LocalTime nowLocal = nowLocal();
        Instant now = now();
        DayRow row = store.day(c.teamId(), d.toString());
        List<Row> rows = store.items(c.teamId(), OnCallRoutinePlan.periodsSeenFrom(d));
        boolean frozen = frozen(row, d, today);
        Sheet sheet = OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), d, rows, frozen);
        Map<Long, Integer> byItem = store.openProblemsByItem(c.teamId());
        int problemsOpen = store.countProblems(c.teamId(), OnCallRoutineCodes.PROBLEM_OPEN);
        Progress prog = OnCallRoutinePlan.progress(sheet, today, nowLocal);
        String status = wireStatus(c, d, row, today);
        Responsible resp = responsible(c, d);

        String locked = lockedReason(c, p, d, row, today);
        boolean recorded = row != null || !d.isBefore(today.minusDays(OnCallRoutineCodes.EDIT_WINDOW_DAYS))
                || rows.stream().anyMatch(r -> OnCallRoutineCodes.KIND_DAY.equals(r.periodKind()));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("team_id", c.teamId());
        m.put("team_name", c.teamName());
        m.put("configured", c.configured());
        m.put("enabled", c.enabled());
        m.put("template_unreadable", c.templateUnreadable());
        m.put("date", d.toString());
        m.put("today", today.toString());
        m.put("now", now.toString());
        m.put("weekday", d.getDayOfWeek().getValue());
        boolean workday = c.workday(d);
        m.put("workday", workday);
        m.put("non_workday_reason", workday ? null : (c.settings().skipHolidays() && c.holidays().contains(d)
                && c.settings().workdays().contains(d.getDayOfWeek().getValue()) ? "HOLIDAY" : "WEEKEND"));
        m.put("holiday_name", c.holidayNames().get(d));
        m.put("recorded", recorded);
        m.put("editable", locked == null);
        m.put("locked_reason", locked);
        m.put("can_work", p.canWork());
        m.put("can_edit_template", p.canEditTemplate());
        m.put("can_manage", p.canManage());
        m.put("can_reopen", canReopen(p, row, resp) && OnCallRoutineCodes.completed(row == null ? null : row.status())
                && inWindow(d, today));
        m.put("template_version", c.templateVersion());
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("day_start", c.settings().dayStart());
        Map<String, Object> rem = new LinkedHashMap<>();
        rem.put("times", new ArrayList<>(c.settings().reminders().times()));
        st.put("reminders", rem);
        // Tamamlama penceresinin önizlemesi şablonun tamamını okumasın: /template yanıtındaki nesnenin aynısı
        st.put("completion_notice", OnCallRoutineSettings.noticeWire(c.settings().completionNotice()));
        m.put("settings", st);
        m.put("responsible", responsibleWire(c, resp, p.me()));
        m.put("day", OnCallRoutineWire.day(row, status));
        m.put("progress", recorded ? OnCallRoutineWire.progress(prog, problemsOpen) : OnCallRoutineWire.progress(
                new Progress(0, 0, 0, 0, 0, 0), problemsOpen));
        m.put("week", weekBlock(c, d, today, nowLocal));
        m.put("groups", groups(c, sheet, d, today, nowLocal, p.me(), byItem));
        m.put("previous_workday", previousWorkday(c, d, today, nowLocal));
        List<ProblemRow> open = store.openProblems(c.teamId(), MAX_OPEN_PROBLEMS);
        m.put("open_problems", problemsWire(c, p, open, today));
        m.put("open_problems_total", problemsOpen);
        Period period = c.period(d);
        m.put("handover", handoverPanel(c, p, d, period, today, now));
        m.put("handover_current", handoverCurrent(c, p, period));
        return m;
    }

    String lockedReason(TeamCtx c, Perms p, LocalDate d, DayRow row, LocalDate today) {
        if (!c.configured()) return "NOT_CONFIGURED";
        if (!p.canWork()) return "NO_PERMISSION";
        if (d.isAfter(today)) return "FUTURE";
        if (d.isBefore(today.minusDays(OnCallRoutineCodes.EDIT_WINDOW_DAYS))) return "TOO_OLD";
        if (!c.workday(d)) return "NOT_WORKDAY";
        if (c.paused(d)) return "PAUSED";
        if (row != null && OnCallRoutineCodes.completed(row.status())) return "COMPLETED";
        return null;
    }

    static boolean inWindow(LocalDate d, LocalDate today) {
        return !d.isAfter(today) && !d.isBefore(today.minusDays(OnCallRoutineCodes.EDIT_WINDOW_DAYS));
    }

    public static boolean canReopen(Perms p, DayRow row, Responsible resp) {
        if (!p.canWork() || row == null) return false;
        if (p.canManage()) return true;
        if (p.me() != null && p.me().equals(row.completedById())) return true;
        Long r = row.responsibleUserId() != null ? row.responsibleUserId() : resp == null ? null : resp.userId();
        return p.me() != null && p.me().equals(r);
    }

    /**
     * Görev grupları (DAY / WEEK / MONTH; boş türler yok; bölümler şablon sırasında). Bölüm adı / penceresi güncel
     * şablondan; şablonda olmayan (kaldırılmış) bölüm satırın anlık görüntüsündeki adla gelir.
     */
    List<Map<String, Object>> groups(TeamCtx c, Sheet sheet, LocalDate d, LocalDate today, LocalTime nowLocal, Long me,
                                     Map<Long, Integer> byItem) {
        Map<String, OnCallRoutineTemplate.Section> tplSections = new HashMap<>();
        for (OnCallRoutineTemplate.Section s : c.template().sections()) tplSections.put(s.id(), s);
        Map<String, Map<String, List<Item>>> byKind = new LinkedHashMap<>();
        for (String k : OnCallRoutineCodes.PERIOD_KINDS) byKind.put(k, new LinkedHashMap<>());
        for (Item i : sheet.items()) {
            String sid = i.sectionId() == null ? "_" : i.sectionId();
            Map<String, List<Item>> kind = byKind.get(i.periodKind());
            if (kind != null) kind.computeIfAbsent(sid, k -> new ArrayList<>()).add(i);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Item>>> g : byKind.entrySet()) {
            if (g.getValue().isEmpty()) continue;
            String kind = g.getKey();
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("kind", kind);
            gm.put("period_key", switch (kind) {
                case OnCallRoutineCodes.KIND_WEEK -> OnCallRoutineCalendar.weekKey(d);
                case OnCallRoutineCodes.KIND_MONTH -> OnCallRoutineCalendar.monthKey(d);
                default -> d.toString();
            });
            List<Map<String, Object>> secs = new ArrayList<>();
            for (Map.Entry<String, List<Item>> s : g.getValue().entrySet()) {
                List<Item> items = s.getValue();
                Item head = items.get(0);
                OnCallRoutineTemplate.Section tsec = head.sectionId() == null ? null : tplSections.get(head.sectionId());
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("id", head.sectionId());
                sm.put("title", tsec != null ? tsec.title() : head.sectionTitle());
                sm.put("window_from", tsec != null ? tsec.windowFrom() : null);
                sm.put("window_to", tsec != null ? tsec.windowTo() : null);
                boolean overdue = false;
                int pending = 0;
                List<Map<String, Object>> iw = new ArrayList<>();
                for (Item i : items) {
                    if (!i.removed() && !i.resolved()) pending++;
                    if (i.overdue(today, nowLocal)) overdue = true;
                    iw.add(OnCallRoutineWire.item(i, d, today, nowLocal, me, byItem));
                }
                sm.put("overdue", overdue);
                sm.put("pending", pending);
                sm.put("items", iw);
                secs.add(sm);
            }
            gm.put("sections", secs);
            out.add(gm);
        }
        return out;
    }

    // ── Hafta şeridi ─────────────────────────────────────────────────────────────────────────────

    public record DayCell(LocalDate date, boolean workday, String holidayName, String status, Integer required,
                          Integer resolved, Integer done, Integer skipped, Integer problemsOpened, String responsibleName,
                          String completedByName, String completedAt, boolean completedLate, boolean today) { }

    /** Haftanın günleri (Pzt–Paz) — durum + sayılar. Satırı açık / olmayan pencere içi günler canlı hesaplanır. */
    public List<DayCell> weekCells(TeamCtx c, LocalDate monday, LocalDate today, LocalTime nowLocal) {
        LocalDate sunday = monday.plusDays(6);
        return cells(c, monday, sunday, today, nowLocal);
    }

    /** Aralığın günleri (en çok 366). Pencere içindeki açık günler canlı; diğerleri anlık görüntü / tel durumu. */
    public List<DayCell> cells(TeamCtx c, LocalDate from, LocalDate to, LocalDate today, LocalTime nowLocal) {
        Map<String, DayRow> rowByDay = new HashMap<>();
        for (DayRow r : store.days(c.teamId(), from.toString(), to.toString())) rowByDay.put(r.day(), r);
        Map<String, Integer> probs = new HashMap<>();
        for (ProblemRow pr : store.problemsOnDays(c.teamId(), from.toString(), to.toString(), 5000)) {
            probs.merge(pr.day(), 1, Integer::sum);
        }
        // Canlı hesap gereken günler (pencere içi, satırı açık ya da yok, iş günü, duraklatılmamış)
        LocalDate liveFrom = today.minusDays(OnCallRoutineCodes.EDIT_WINDOW_DAYS);
        List<LocalDate> live = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DayRow r = rowByDay.get(d.toString());
            if (!d.isBefore(liveFrom) && !d.isAfter(today) && c.workday(d) && !c.paused(d) && c.configured()
                    && (r == null || OnCallRoutineCodes.DAY_OPEN.equals(r.status()))) {
                live.add(d);
            }
        }
        List<Row> rows = List.of();
        if (!live.isEmpty()) {
            Set<String> seen = new HashSet<>();
            List<String[]> periods = new ArrayList<>();
            for (LocalDate d : live) {
                for (String[] pk : OnCallRoutinePlan.periodsSeenFrom(d)) {
                    if (seen.add(pk[0] + "|" + pk[1])) periods.add(pk);
                }
            }
            rows = store.items(c.teamId(), periods);
        }
        List<DayCell> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DayRow r = rowByDay.get(d.toString());
            String status = wireStatus(c, d, r, today);
            Integer req = null, res = null, done = null, skipped = null;
            if (r != null && !OnCallRoutineCodes.DAY_OPEN.equals(r.status())) {
                req = r.requiredCount();
                done = r.doneCount();
                skipped = r.skippedCount();
                res = (done == null ? 0 : done) + (skipped == null ? 0 : skipped);
            } else if (live.contains(d)) {
                Sheet s = OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), d, rows, false);
                Progress pg = OnCallRoutinePlan.progress(s, today, nowLocal);
                req = pg.required();
                done = pg.done();
                skipped = pg.skipped();
                res = pg.resolved();
            }
            Integer po = r != null && r.problemsOpened() != null && !OnCallRoutineCodes.DAY_OPEN.equals(r.status())
                    ? r.problemsOpened() : probs.getOrDefault(d.toString(), 0);
            out.add(new DayCell(d, c.workday(d), c.holidayNames().get(d), status, req, res, done, skipped, po,
                    r == null ? null : r.responsibleName(), r == null ? null : r.completedByName(),
                    r == null ? null : OnCallRoutineWire.z(r.completedAt()),
                    r != null && r.completedLate() && OnCallRoutineCodes.completed(r.status()), d.equals(today)));
        }
        return out;
    }

    Map<String, Object> weekBlock(TeamCtx c, LocalDate d, LocalDate today, LocalTime nowLocal) {
        LocalDate mon = OnCallRoutineCalendar.monday(d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", mon.toString());
        m.put("from", mon.toString());
        m.put("to", mon.plusDays(6).toString());
        List<Map<String, Object>> days = new ArrayList<>();
        for (DayCell cell : weekCells(c, mon, today, nowLocal)) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("date", cell.date().toString());
            dm.put("workday", cell.workday());
            dm.put("holiday_name", cell.holidayName());
            dm.put("status", cell.status());
            dm.put("required", cell.required());
            dm.put("resolved", cell.resolved());
            dm.put("problems_opened", cell.problemsOpened());
            dm.put("today", cell.today());
            days.add(dm);
        }
        m.put("days", days);
        return m;
    }

    // ── Önceki iş günü ───────────────────────────────────────────────────────────────────────────

    Map<String, Object> previousWorkday(TeamCtx c, LocalDate d, LocalDate today, LocalTime nowLocal) {
        if (!c.configured()) return null;
        LocalDate p = null;
        for (int i = 1; i <= 14; i++) {
            LocalDate x = d.minusDays(i);
            if (c.workday(x)) {
                p = x;
                break;
            }
        }
        if (p == null) return null;
        DayRow row = store.day(c.teamId(), p.toString());
        List<Row> rows = store.items(c.teamId(), OnCallRoutinePlan.periodsSeenFrom(p));
        Sheet s = sheet(c, p, row, rows, today);
        List<Item> pending = s.pendingRequired();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", p.toString());
        m.put("status", wireStatus(c, p, row, today));
        m.put("pending_total", pending.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Item i : pending) {
            if (list.size() >= 10) break;
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("task_id", i.taskId());
            im.put("title", i.title());
            list.add(im);
        }
        m.put("pending", list);
        return m;
    }

    // ── Sorunlar (tel) ───────────────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> problemsWire(TeamCtx c, Perms p, List<ProblemRow> list, LocalDate today) {
        if (list.isEmpty()) return new ArrayList<>();
        Map<Long, List<ImageMeta>> imgs = store.imagesOf(list.stream().map(ProblemRow::id).toList());
        Map<Long, IncidentRecord> inc = incidentsOf(list);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProblemRow pr : list) {
            out.add(OnCallRoutineWire.problem(pr, imgs.getOrDefault(pr.id(), List.of()), incidentWire(pr, inc, p), today,
                    p.canWork(), canDeleteProblem(p, pr)));
        }
        return out;
    }

    public static boolean canDeleteProblem(Perms p, ProblemRow pr) {
        return p.canWork() && (p.canManage() || (p.me() != null && p.me().equals(pr.createdById())));
    }

    private Map<Long, IncidentRecord> incidentsOf(List<ProblemRow> list) {
        Set<Long> ids = new HashSet<>();
        for (ProblemRow pr : list) if (pr.incidentRecordId() != null) ids.add(pr.incidentRecordId());
        Map<Long, IncidentRecord> out = new HashMap<>();
        if (ids.isEmpty() || incidents == null) return out;
        try {
            for (IncidentRecord r : incidents.findAllById(ids)) out.put(r.getId(), r);
        } catch (RuntimeException e) {
            log.debug("Olay kayıtları okunamadı (rutin sorunlarında gizli gösterilir): {}", e.toString());
        }
        return out;
    }

    private static Map<String, Object> incidentWire(ProblemRow pr, Map<Long, IncidentRecord> inc, Perms p) {
        if (pr.incidentRecordId() == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pr.incidentRecordId());
        IncidentRecord r = inc.get(pr.incidentRecordId());
        if (r == null || p.incidentReadable() == null || !p.incidentReadable().test(r.getTeamId(), r.getCreatedByTeamId())) {
            m.put("hidden", true);
            return m;
        }
        m.put("title", r.getTitle());
        m.put("status", r.getStatus());
        return m;
    }

    // ── Devir paneli ─────────────────────────────────────────────────────────────────────────────

    Map<String, Object> handoverCurrent(TeamCtx c, Perms p, Period period) {
        HandoverRow h = store.handover(c.teamId(), period.key().toString());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period_key", period.key().toString());
        m.put("from", period.first().toString());
        m.put("to", period.last().toString());
        m.put("note", h == null || h.note() == null ? "" : h.note());
        m.put("note_version", h == null ? 0 : h.noteVersion());
        m.put("note_by_name", h == null ? null : h.noteByName());
        m.put("note_at", h == null ? null : OnCallRoutineWire.z(h.noteAt()));
        m.put("writable", handoverNoteWritable(c, p, period.key().toString()));
        return m;
    }

    /** Önceki dönemin devir paneli: yeni dönemin ilk 3 iş gününde (dönem bittiyse); aksi halde null. */
    Map<String, Object> handoverPanel(TeamCtx c, Perms p, LocalDate d, Period current, LocalDate today, Instant now) {
        if (!c.configured() || d.isAfter(today)) return null;
        int idx = 0;
        boolean within = false;
        for (LocalDate x : current.days()) {
            if (!c.workday(x)) continue;
            idx++;
            if (x.equals(d)) {
                within = idx <= OnCallRoutineCodes.HANDOVER_SHOW_WORKDAYS;
                break;
            }
        }
        if (!within) return null;
        Period prev = OnCallRoutineCalendar.previous(c.rotation(), c.dayStart(), current);
        if (prev.end().isAfter(now)) return null;
        return handoverWire(c, p, prev, now);
    }

    public Map<String, Object> handoverWire(TeamCtx c, Perms p, Period prev, Instant now) {
        HandoverRow h = store.handover(c.teamId(), prev.key().toString());
        Map<String, Object> summary;
        boolean ready;
        if (h != null && h.summaryJson() != null) {
            summary = OnCallRoutineWire.readMap(h.summaryJson());
            ready = true;
        } else {
            summary = handoverSummary(c, prev).toMap();
            ready = false;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period_key", prev.key().toString());
        m.put("from", prev.first().toString());
        m.put("to", prev.last().toString());
        m.put("show", true);
        m.put("responsible_names", responsibleNames(c, prev));
        m.put("note", h == null ? null : h.note());
        m.put("note_by_name", h == null ? null : h.noteByName());
        m.put("note_at", h == null ? null : OnCallRoutineWire.z(h.noteAt()));
        m.put("summary", summary);
        m.put("summary_ready", ready);
        boolean acked = h != null && h.ackAt() != null;
        m.put("acknowledged", acked);
        m.put("ack_by_name", h == null ? null : h.ackByName());
        m.put("ack_at", h == null ? null : OnCallRoutineWire.z(h.ackAt()));
        m.put("can_ack", p.canWork() && !acked && !prev.end().isAfter(now));
        return m;
    }

    List<String> responsibleNames(TeamCtx c, Period prev) {
        Set<String> names = new LinkedHashSet<>();
        for (DayRow r : store.days(c.teamId(), prev.first().toString(), prev.last().toString())) {
            if (r.responsibleName() != null && !r.responsibleName().isBlank()) names.add(r.responsibleName());
        }
        if (names.isEmpty()) {
            Responsible r = responsible(c, prev.first());
            if (r.name() != null) names.add(r.name());
        }
        return new ArrayList<>(names);
    }

    /** Devir özeti (§5.5) — gün durumları, atlanan / kaçırılan görev, sorunlar, gün notları. */
    public record HandoverSummary(int workdays, int completed, int partial, int incomplete, int missed, int skippedItems,
                                  int missedItems, int problemsOpened, int problemsOpen, List<Map<String, Object>> dayNotes,
                                  int openDays) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workdays", workdays);
            m.put("completed", completed);
            m.put("partial", partial);
            m.put("incomplete", incomplete);
            m.put("missed", missed);
            m.put("skipped_items", skippedItems);
            m.put("missed_items", missedItems);
            m.put("problems_opened", problemsOpened);
            m.put("problems_open", problemsOpen);
            m.put("day_notes", dayNotes);
            m.put("open_days", openDays);
            return m;
        }
    }

    /**
     * Gün devir özetine girer mi: tamamlanmış ya da kapanma zamanı (ertesi gün 00:30, İstanbul) gelmiş. Devir saati gün
     * başlangıcından geç olan rotasyonda devir günü önceki döneme aittir ama devir anında hâlâ sürer — özete girmez
     * ({@code open_days} sayılır), işaretlenmeye devam eder.
     */
    public static boolean daySettled(DayRow r, LocalDate d, Instant now) {
        if (r != null && OnCallRoutineCodes.completed(r.status())) return true;
        return !now.isBefore(OnCallRoutineCalendar.at(d.plusDays(1), OnCallRoutineJob.CLOSE_AFTER));
    }

    public HandoverSummary handoverSummary(TeamCtx c, Period prev) {
        Instant now = now();
        Map<String, DayRow> byDay = new HashMap<>();
        for (DayRow r : store.days(c.teamId(), prev.first().toString(), prev.last().toString())) byDay.put(r.day(), r);
        int workdays = 0, openDays = 0;
        List<DayRow> settled = new ArrayList<>();
        for (LocalDate x : prev.days()) {
            if (!c.workday(x)) continue;
            DayRow r = byDay.get(x.toString());
            if (!daySettled(r, x, now)) {
                openDays++;
                continue;
            }
            workdays++;
            if (r != null) settled.add(r);
        }
        int completed = 0, partial = 0, incomplete = 0, missed = 0, skippedItems = 0, missedItems = 0;
        List<Map<String, Object>> notes = new ArrayList<>();
        for (DayRow r : settled) {
            switch (r.status()) {
                case OnCallRoutineCodes.DAY_COMPLETED -> completed++;
                case OnCallRoutineCodes.DAY_COMPLETED_PARTIAL -> partial++;
                case OnCallRoutineCodes.DAY_INCOMPLETE -> incomplete++;
                case OnCallRoutineCodes.DAY_MISSED -> missed++;
                default -> { }
            }
            if (r.skippedCount() != null) skippedItems += r.skippedCount();
            if ((OnCallRoutineCodes.DAY_INCOMPLETE.equals(r.status()) || OnCallRoutineCodes.DAY_MISSED.equals(r.status()))
                    && r.pendingCount() != null) {
                missedItems += r.pendingCount();
            }
            if (r.note() != null && !r.note().isBlank() && notes.size() < 7) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("date", r.day());
                n.put("note", r.note().length() > 500 ? r.note().substring(0, 499) + "…" : r.note());
                n.put("by", r.noteByName());
                notes.add(n);
            }
        }
        int opened = store.problemsOnDays(c.teamId(), prev.first().toString(), prev.last().toString(), 10_000).size();
        int open = store.countProblems(c.teamId(), OnCallRoutineCodes.PROBLEM_OPEN);
        return new HandoverSummary(workdays, completed, partial, incomplete, missed, skippedItems, missedItems, opened, open, notes,
                openDays);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Hafta görünümü / takvim
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    static Map<String, Object> cellWire(DayCell cell, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", cell.date().toString());
        m.put("workday", cell.workday());
        m.put("holiday_name", cell.holidayName());
        m.put("status", cell.status());
        m.put("required", cell.required());
        if (full) m.put("resolved", cell.resolved());
        m.put("done", cell.done());
        m.put("skipped", cell.skipped());
        m.put("problems_opened", cell.problemsOpened());
        m.put("responsible_name", cell.responsibleName());
        if (full) m.put("completed_by_name", cell.completedByName());
        m.put("completed_at", cell.completedAt());
        m.put("completed_late", cell.completedLate());
        if (full) m.put("today", cell.today());
        return m;
    }

    /** Hafta görünümü (§4.1 {@code /week}): günler, haftalık / aylık bantlar, devir, günlük sorumlular. */
    public Map<String, Object> week(TeamCtx c, Perms p, LocalDate monday) {
        LocalDate today = today();
        LocalTime nowLocal = nowLocal();
        Instant now = now();
        LocalDate sunday = monday.plusDays(6);
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> wk = new LinkedHashMap<>();
        wk.put("key", monday.toString());
        wk.put("from", monday.toString());
        wk.put("to", sunday.toString());
        m.put("week", wk);
        List<Map<String, Object>> days = new ArrayList<>();
        for (DayCell cell : cells(c, monday, sunday, today, nowLocal)) days.add(cellWire(cell, true));
        m.put("days", days);
        // Bantlar: haftanın bugüne en yakın iş günü bağlamında (yoksa Pazartesi) — yalnız WEEK / MONTH görevleri
        LocalDate ctx = monday;
        LocalDate upTo = today.isBefore(sunday) ? today : sunday;
        for (LocalDate x = upTo; !x.isBefore(monday); x = x.minusDays(1)) {
            if (c.workday(x)) {
                ctx = x;
                break;
            }
        }
        List<Map<String, Object>> groups = List.of();
        if (c.configured()) {
            List<Row> rows = store.items(c.teamId(), OnCallRoutinePlan.periodsSeenFrom(ctx));
            boolean fz = ctx.isBefore(today.minusDays(OnCallRoutineCodes.EDIT_WINDOW_DAYS));
            Sheet all = OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), ctx, rows, fz);
            Sheet bands = new Sheet(all.items().stream()
                    .filter(i -> !OnCallRoutineCodes.KIND_DAY.equals(i.periodKind())).toList());
            groups = groups(c, bands, ctx, today, nowLocal, p.me(), store.openProblemsByItem(c.teamId()));
        }
        m.put("groups", groups);
        Period period = c.period(ctx);
        m.put("handover_current", handoverCurrent(c, p, period));
        Period prev = OnCallRoutineCalendar.previous(c.rotation(), c.dayStart(), period);
        m.put("handover_previous", c.configured() && !prev.end().isAfter(now) ? handoverWire(c, p, prev, now) : null);
        Map<String, DayRow> rowByDay = new HashMap<>();
        for (DayRow r : store.days(c.teamId(), monday.toString(), sunday.toString())) rowByDay.put(r.day(), r);
        Map<String, Object> byDay = new LinkedHashMap<>();
        for (LocalDate x = monday; !x.isAfter(sunday); x = x.plusDays(1)) {
            DayRow r = rowByDay.get(x.toString());
            Map<String, Object> rm = new LinkedHashMap<>();
            if (r != null && r.responsibleSource() != null) {
                rm.put("name", r.responsibleName());
                rm.put("source", r.responsibleSource());
                rm.put("is_me", r.responsibleUserId() != null && r.responsibleUserId().equals(p.me()));
            } else if (c.workday(x)) {
                Responsible rs = responsible(c, x);
                rm.put("name", rs.name());
                rm.put("source", rs.source());
                rm.put("is_me", rs.userId() != null && rs.userId().equals(p.me()));
            } else {
                continue;
            }
            byDay.put(x.toString(), rm);
        }
        m.put("responsible_by_day", byDay);
        m.put("can_work", p.canWork());
        return m;
    }

    /** Takvim (§4.1 {@code /calendar}). */
    public Map<String, Object> calendar(TeamCtx c, LocalDate from, LocalDate to) {
        LocalDate today = today();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from.toString());
        m.put("to", to.toString());
        m.put("today", today.toString());
        m.put("routine_since", c.row() == null ? null : c.row().enabledSince());
        List<Map<String, Object>> days = new ArrayList<>();
        for (DayCell cell : cells(c, from, to, today, nowLocal())) days.add(cellWire(cell, false));
        m.put("days", days);
        return m;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Genel bakış / kişisel özet
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public record TodaySummary(LocalDate date, boolean workday, String status, Progress progress, int problemsOpen,
                               Responsible responsible, boolean handoverPendingAck) { }

    /** Takımların bugünkü özeti (sabit sorgu sayısı). Yalnız yapılandırılmış + etkin takımlar. */
    public Map<Long, TodaySummary> todaySummaries(List<TeamCtx> ctxs) {
        Map<Long, TodaySummary> out = new HashMap<>();
        List<TeamCtx> active = ctxs.stream().filter(c -> c.configured() && c.enabled()).toList();
        if (active.isEmpty()) return out;
        LocalDate today = today();
        LocalTime nowLocal = nowLocal();
        Instant now = now();
        List<Long> ids = active.stream().map(TeamCtx::teamId).toList();
        Map<Long, DayRow> days = new HashMap<>();
        for (DayRow r : store.daysOfTeamsOn(ids, today.toString())) days.put(r.teamId(), r);
        Map<Long, List<Row>> rows = store.itemsOfTeams(ids, OnCallRoutinePlan.periodsSeenFrom(today));
        Map<Long, Integer> problems = store.openProblemCounts(ids);
        Map<Long, Responsible> resp = responsibles(active, today);
        // Devir onayı bekleyen önceki dönemler (tek sorgu)
        Map<Long, String> prevKey = new HashMap<>();
        for (TeamCtx c : active) {
            Period cur = c.period(today);
            if (!showsHandover(c, cur, today)) continue;
            Period prev = OnCallRoutineCalendar.previous(c.rotation(), c.dayStart(), cur);
            if (!prev.end().isAfter(now)) prevKey.put(c.teamId(), prev.key().toString());
        }
        Map<Long, HandoverRow> hand = new HashMap<>();
        if (!prevKey.isEmpty()) {
            for (HandoverRow h : store.handoversOf(prevKey.keySet(), new HashSet<>(prevKey.values()))) {
                if (h.periodKey().equals(prevKey.get(h.teamId()))) hand.put(h.teamId(), h);
            }
        }
        for (TeamCtx c : active) {
            DayRow row = days.get(c.teamId());
            Sheet s = sheet(c, today, row, rows.getOrDefault(c.teamId(), List.of()), today);
            Progress pg = OnCallRoutinePlan.progress(s, today, nowLocal);
            HandoverRow h = hand.get(c.teamId());
            boolean pendingAck = prevKey.containsKey(c.teamId()) && (h == null || h.ackAt() == null);
            out.put(c.teamId(), new TodaySummary(today, c.workday(today), wireStatus(c, today, row, today), pg,
                    problems.getOrDefault(c.teamId(), 0), resp.getOrDefault(c.teamId(), Responsible.none()), pendingAck));
        }
        return out;
    }

    boolean showsHandover(TeamCtx c, Period current, LocalDate d) {
        int idx = 0;
        for (LocalDate x : current.days()) {
            if (!c.workday(x)) continue;
            idx++;
            if (x.equals(d)) return idx <= OnCallRoutineCodes.HANDOVER_SHOW_WORKDAYS;
        }
        return false;
    }

    /** Sonraki hatırlatma saati (bugün, gün tamamlanmamışsa; yoksa null). */
    public String nextReminder(TeamCtx c, TodaySummary s) {
        if (s == null || !s.workday() || OnCallRoutineCodes.completed(s.status()) || !c.settings().reminders().enabled()) return null;
        LocalTime now = nowLocal();
        for (String t : c.settings().reminders().times()) {
            if (LocalTime.parse(t).isAfter(now)) return t;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Şablon / ayarlar
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public Map<String, Object> templateView(TeamCtx c, boolean canEdit, BiPredicate<String, String> allows) {
        Map<String, Object> m = new LinkedHashMap<>();
        TeamRow r = c.row();
        boolean saved = r != null && r.templateVersion() > 0;
        m.put("configured", c.configured());
        m.put("template", saved ? OnCallRoutineTemplate.toWire(c.template()) : null);
        m.put("template_version", r == null ? 0 : r.templateVersion());
        m.put("template_updated_by_name", r == null ? null : r.templateUpdatedByName());
        m.put("template_updated_at", r == null ? null : OnCallRoutineWire.z(r.templateUpdatedAt()));
        m.put("template_unreadable", c.templateUnreadable());
        m.put("settings", OnCallRoutineSettings.toWire(c.settings()));
        m.put("settings_version", r == null ? 0 : r.settingsVersion());
        m.put("settings_updated_by_name", r == null ? null : r.settingsUpdatedByName());
        m.put("settings_updated_at", r == null ? null : OnCallRoutineWire.z(r.settingsUpdatedAt()));
        m.put("can_edit_template", canEdit);
        m.put("limits", OnCallRoutineTemplate.limits());
        m.put("signal_defs", OnCallRoutineSignals.defs(allows));
        return m;
    }

    public record SaveResult(boolean changed, Map<String, Object> data, Map<String, Object> audit) { }

    public SaveResult saveTemplate(Long teamId, Integer expectedVersion, Object templateRaw, String source, Actor actor) {
        Template t = OnCallRoutineTemplate.parseStrict(templateRaw);
        String json = OnCallRoutineTemplate.toJson(t);
        OnCallRoutineTemplate.checkSize(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        String src = source != null && OnCallRoutineCodes.TEMPLATE_SOURCES.contains(source) ? source : "EDITOR";
        Instant now = now();
        String at = OnCallRoutineStore.ts(now);
        String today = today().toString();
        return tx.execute(st -> {
            store.ensureTeamRow(teamId, now);
            TeamRow row = store.lockTeam(teamId);
            OnCallRoutineTemplate.Read old = OnCallRoutineTemplate.readStored(row.templateJson());
            if (row.templateJson() != null && !old.unreadable() && OnCallRoutineTemplate.toJson(old.template()).equals(json)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("template", OnCallRoutineTemplate.toWire(old.template()));
                d.put("template_version", row.templateVersion());
                d.put("template_updated_by_name", row.templateUpdatedByName());
                d.put("template_updated_at", OnCallRoutineWire.z(row.templateUpdatedAt()));
                d.put("changed", false);
                return new SaveResult(false, d, null);
            }
            if (expectedVersion == null || row.templateVersion() != expectedVersion) {
                throw templateConflict(row.templateVersion(), row.templateUpdatedByName(), row.templateUpdatedAt());
            }
            int active = t.activeCount();
            Settings s = OnCallRoutineSettings.read(row.settingsJson());
            boolean enabled = s.enabled() && active > 0;
            String[] since = transitions(row, enabled, today);
            int n = store.saveTemplate(teamId, row.templateVersion(), json, active, at, actor.id(), actor.name(), enabled,
                    since[0], since[1]);
            if (n != 1) throw templateConflict(row.templateVersion(), row.templateUpdatedByName(), row.templateUpdatedAt());
            int version = row.templateVersion() + 1;
            OnCallRoutineTemplateDiff.Summary sum = OnCallRoutineTemplateDiff.summary(old.template(), t);
            store.insertTemplateVersion(teamId, version, json, OnCallRoutineWire.json(sum.toMap()), src, at, actor.name());
            store.pruneTemplateVersions(teamId, OnCallRoutineCodes.MAX_TEMPLATE_VERSIONS);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("template", OnCallRoutineTemplate.toWire(t));
            d.put("template_version", version);
            d.put("template_updated_by_name", actor.name());
            d.put("template_updated_at", OnCallRoutineWire.z(at));
            d.put("summary", sum.toMap());
            d.put("changed", true);
            Map<String, Object> audit = new LinkedHashMap<>(sum.toMap());
            audit.put("version", version);
            audit.put("source", src);
            return new SaveResult(true, d, audit);
        });
    }

    private static com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException templateConflict(int v, String by, String at) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("current_version", v);
        extra.put("updated_by_name", by);
        extra.put("updated_at", OnCallRoutineWire.z(at));
        return OnCallRoutineErrors.concurrent(extra);
    }

    /** {@code enabled} geçişi: false→true {@code enabled_since} = bugün, true→false {@code paused_since} = bugün. */
    static String[] transitions(TeamRow row, boolean enabled, String today) {
        String enabledSince = row.enabledSince();
        String pausedSince = row.pausedSince();
        if (!row.enabled() && enabled) enabledSince = today;
        if (row.enabled() && !enabled) pausedSince = today;
        return new String[] {enabledSince, pausedSince};
    }

    public SaveResult saveSettings(Long teamId, Integer expectedVersion, Map<?, ?> patch, Actor actor) {
        Instant now = now();
        String at = OnCallRoutineStore.ts(now);
        String today = today().toString();
        return tx.execute(st -> {
            store.ensureTeamRow(teamId, now);
            TeamRow row = store.lockTeam(teamId);
            Settings stored = OnCallRoutineSettings.read(row.settingsJson());
            Settings next = OnCallRoutineSettings.apply(stored, patch);
            if (next.equals(stored)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("settings", OnCallRoutineSettings.toWire(stored));
                d.put("settings_version", row.settingsVersion());
                d.put("settings_updated_by_name", row.settingsUpdatedByName());
                d.put("settings_updated_at", OnCallRoutineWire.z(row.settingsUpdatedAt()));
                d.put("changed", false);
                return new SaveResult(false, d, null);
            }
            if (expectedVersion == null || row.settingsVersion() != expectedVersion) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("current_version", row.settingsVersion());
                extra.put("updated_by_name", row.settingsUpdatedByName());
                extra.put("updated_at", OnCallRoutineWire.z(row.settingsUpdatedAt()));
                throw OnCallRoutineErrors.concurrent(extra);
            }
            boolean enabled = next.enabled() && row.tasksActive() > 0;
            String[] since = transitions(row, enabled, today);
            int n = store.saveSettings(teamId, row.settingsVersion(), OnCallRoutineSettings.toJson(next), at, actor.name(),
                    enabled, since[0], since[1]);
            if (n != 1) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("current_version", row.settingsVersion());
                throw OnCallRoutineErrors.concurrent(extra);
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("settings", OnCallRoutineSettings.toWire(next));
            d.put("settings_version", row.settingsVersion() + 1);
            d.put("settings_updated_by_name", actor.name());
            d.put("settings_updated_at", OnCallRoutineWire.z(at));
            d.put("changed", true);
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("before", OnCallRoutineSettings.toWire(stored));
            audit.put("after", OnCallRoutineSettings.toWire(next));
            return new SaveResult(true, d, audit);
        });
    }

    public List<Map<String, Object>> versions(Long teamId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OnCallRoutineStore.VersionRow v : store.versions(teamId, OnCallRoutineCodes.MAX_TEMPLATE_VERSIONS)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", v.version());
            m.put("saved_at", OnCallRoutineWire.z(v.savedAt()));
            m.put("saved_by_name", v.savedByName());
            m.put("source", v.source());
            m.put("summary", OnCallRoutineWire.readMap(v.summaryJson()));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> version(Long teamId, int v) {
        OnCallRoutineStore.VersionRow row = store.version(teamId, v);
        if (row == null) throw OnCallRoutineErrors.versionNotFound();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", row.version());
        m.put("template", OnCallRoutineTemplate.toWire(OnCallRoutineTemplate.readStored(row.templateJson()).template()));
        m.put("saved_at", OnCallRoutineWire.z(row.savedAt()));
        m.put("saved_by_name", row.savedByName());
        m.put("source", row.source());
        return m;
    }

    /** Takımın etkin üyeleri (devralma seçicisi) — ad sırası (Türkçe). */
    public List<Map<String, Object>> people(Long teamId, Long me) {
        Map<Long, Object[]> byId = new LinkedHashMap<>();
        for (Object[] r : users.findActiveMemberRowsOfTeams(List.of(teamId))) {
            if (r == null || !(r[0] instanceof Number id)) continue;
            byId.putIfAbsent(id.longValue(), r);
        }
        java.text.Collator coll = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("tr"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : byId.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            Long id = ((Number) r[0]).longValue();
            String username = (String) r[1];
            m.put("user_id", id);
            m.put("name", displayNameOf(username, (String) r[2], (String) r[3], (String) r[4]));
            m.put("username", username);
            m.put("is_me", id.equals(me));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("name")), coll));
        return out.size() > MAX_PEOPLE ? new ArrayList<>(out.subList(0, MAX_PEOPLE)) : out;
    }

    public boolean activeMember(Long teamId, Long userId) {
        if (userId == null) return false;
        for (Object[] r : users.findActiveMemberRowsOfTeams(List.of(teamId))) {
            if (r != null && r[0] instanceof Number id && id.longValue() == userId) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Yazma kuralları — ortak
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public static LocalDate parseDate(Object raw, String field) {
        if (raw instanceof String s && s.length() == 10) {
            try {
                return LocalDate.parse(s);
            } catch (RuntimeException ignored) {
                // aşağıda alan hatası
            }
        }
        throw OnCallRoutineErrors.field(field, "Tarih yyyy-AA-gg biçiminde olmalı (ör. 2026-11-26).",
                "The date must be yyyy-MM-dd (e.g. 2026-11-26).");
    }

    /** Gün yazımı ön koşulları: yapılandırılmış, gelecekte değil, pencere içinde, duraklatılmamış. */
    public void checkDayWrite(TeamCtx c, LocalDate d, LocalDate today) {
        if (!c.configured()) throw OnCallRoutineErrors.notConfigured();
        if (d.isAfter(today)) throw OnCallRoutineErrors.dayInFuture();
        if (d.isBefore(today.minusDays(OnCallRoutineCodes.EDIT_WINDOW_DAYS))) throw OnCallRoutineErrors.dayLocked();
        if (c.paused(d)) throw OnCallRoutineErrors.paused();
    }

    /**
     * Görevin bu günde planlanan örneği (sanal; eklenecek) — maddeleşmiş satır varsa null (satır kullanılır). Kapanmış /
     * tamamlanmış günün sayfası donmuştur: o güne ait olmayan yeni günlük görev eklenmez. Kural dışıysa kodlu hata.
     */
    Item plannedItem(TeamCtx c, LocalDate d, String taskId, String kind, String periodKey, Row existing) {
        if (existing != null) {
            if (!OnCallRoutinePlan.rowVisibleOn(existing, d)) throw OnCallRoutineErrors.periodMismatch();
            return null;
        }
        DayRow pre = store.day(c.teamId(), d.toString());
        if (OnCallRoutineCodes.KIND_DAY.equals(kind) && pre != null && !OnCallRoutineCodes.DAY_OPEN.equals(pre.status())) {
            throw OnCallRoutineErrors.taskNotFound();
        }
        OnCallRoutineTemplate.Located loc = c.template().find(taskId);
        if (loc != null && loc.task().active()) {
            if (!c.workday(d)) throw OnCallRoutineErrors.notWorkday();
            Sheet s = OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), d, List.of(), false);
            Item i = s.find(kind, periodKey, taskId);
            if (i == null) throw OnCallRoutineErrors.periodMismatch();
            return i;
        }
        throw OnCallRoutineErrors.taskNotFound();
    }

    /**
     * Gün satırı yoksa sorumlu anlık görüntüsüyle ekler (sorumlu yalnız satır yokken hesaplanır). İş günü OLMAYAN güne satır
     * eklenmez (inceleme 2026-11-26 #5: hafta sonu haftalık / aylık görev işareti ya da genel sorun boş bir OPEN gün
     * satırı bırakıyordu — istatistiğe ve kapanışa girmesin); o yazımlar gün satırı olmadan yürür (görev / sorun satırı
     * kendi kilidiyle yazılır).
     */
    void ensureDayRow(TeamCtx c, LocalDate d, Instant now) {
        if (!c.workday(d)) return;
        if (store.day(c.teamId(), d.toString()) != null) return;
        Responsible r = responsible(c, d);
        store.ensureDay(c.teamId(), d.toString(), OnCallRoutineCalendar.weekKey(d), c.period(d).key().toString(),
                r.userId(), r.name(), r.source(), now);
    }

    public static Map<String, Object> dayBrief(DayRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", row == null ? OnCallRoutineCodes.DAY_NONE : row.status());
        m.put("version", row == null ? 0 : row.version());
        m.put("completed_by_name", row == null ? null : row.completedByName());
        m.put("completed_at", row == null ? null : OnCallRoutineWire.z(row.completedAt()));
        return m;
    }

    /** Alan geçmişi satırı — değişikliğin KENDİ işleminde (geri alınan değişiklik iz bırakmaz). */
    void event(Long teamId, String day, String kind, String taskId, Long problemId, Actor a, String at, Map<String, ?> detail) {
        store.insertEvent(teamId, day, kind, taskId, problemId, a == null ? null : a.id(), a == null ? null : a.name(), at,
                OnCallRoutineWire.detail(detail));
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // İşaretleme
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public record StateResult(String status, Row row) { }

    /**
     * Görevin durumunu değiştirir (tek işlem; kilit sırası gün → görev). {@code writeEvent} false → olay yazılmaz (toplu işaret
     * tek bir {@code SECTION_DONE} yazar). Aynı durum → {@code unchanged}; beklenen durum tutmazsa {@code ITEM_CHANGED}.
     */
    public StateResult applyState(TeamCtx c, LocalDate d, String taskId, String kind, String periodKey, String state,
                                  String expected, String skipReason, Actor a, boolean writeEvent) {
        checkDayWrite(c, d, today());
        Instant now = now();
        String at = OnCallRoutineStore.ts(now);
        Row existing = store.item(c.teamId(), kind, periodKey, taskId);
        Item planned = plannedItem(c, d, taskId, kind, periodKey, existing);
        ensureDayRow(c, d, now);
        return tx.execute(st -> {
            DayRow day = store.lockDay(c.teamId(), d.toString());
            if (OnCallRoutineCodes.KIND_DAY.equals(kind) && day != null && OnCallRoutineCodes.completed(day.status())) {
                throw OnCallRoutineErrors.dayCompleted(dayBrief(day));
            }
            if (planned != null) {
                store.insertItemIfAbsent(c.teamId(), planned, OnCallRoutineCodes.PENDING, OnCallRoutineCodes.SRC_TOUCH,
                        c.templateVersion(), now);
            }
            Row row = store.lockItem(c.teamId(), kind, periodKey, taskId);
            if (row == null) throw OnCallRoutineErrors.taskNotFound();
            if (row.state().equals(state)) return new StateResult("unchanged", row);
            if (expected != null && !expected.equals(row.state())) {
                throw OnCallRoutineErrors.itemChanged(itemWireOf(c, d, row));
            }
            String reason = OnCallRoutineCodes.SKIPPED.equals(state) ? skipReason : null;
            int n = store.setItemState(row.id(), row.version(), state, a.id(), a.name(), at, reason);
            if (n != 1) throw OnCallRoutineErrors.itemChanged(itemWireOf(c, d, row));
            if (writeEvent) {
                Map<String, Object> det = new LinkedHashMap<>();
                det.put("title", row.title());
                det.put("period_kind", kind);
                det.put("from", row.state());
                if (reason != null) det.put("reason", reason);
                event(c.teamId(), d.toString(), switch (state) {
                    case OnCallRoutineCodes.DONE -> OnCallRoutineCodes.EV_ITEM_DONE;
                    case OnCallRoutineCodes.SKIPPED -> OnCallRoutineCodes.EV_ITEM_SKIPPED;
                    default -> OnCallRoutineCodes.EV_ITEM_PENDING;
                }, taskId, null, a, at, det);
            }
            return new StateResult("done", store.lockItem(c.teamId(), kind, periodKey, taskId));
        });
    }

    /** Tek satırın tel biçimi (çakışma yanıtı ve işaret yanıtı için). */
    Map<String, Object> itemWireOf(TeamCtx c, LocalDate d, Row row) {
        LocalDate today = today();
        LocalTime nowLocal = nowLocal();
        Sheet s = OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), d, List.of(row), false);
        Item i = s.find(row.periodKind(), row.periodKey(), row.taskId());
        if (i == null) {
            Sheet frozen = OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), d, List.of(row), true);
            i = frozen.find(row.periodKind(), row.periodKey(), row.taskId());
        }
        if (i == null) return Map.of("task_id", row.taskId(), "state", row.state(), "version", row.version());
        Map<Long, Integer> byItem = new HashMap<>();
        return OnCallRoutineWire.item(i, d, today, nowLocal, null, byItem);
    }

    /** İşaret sonrası yanıt parçaları: güncel görev (sayfadan), ilerleme, gün özeti. */
    public Map<String, Object> afterWrite(TeamCtx c, Perms p, LocalDate d, String kind, String periodKey, String taskId) {
        LocalDate today = today();
        LocalTime nowLocal = nowLocal();
        DayRow row = store.day(c.teamId(), d.toString());
        List<Row> rows = store.items(c.teamId(), OnCallRoutinePlan.periodsSeenFrom(d));
        Sheet s = sheet(c, d, row, rows, today);
        Map<Long, Integer> byItem = store.openProblemsByItem(c.teamId());
        Map<String, Object> m = new LinkedHashMap<>();
        if (taskId != null) {
            Item i = s.find(kind, periodKey, taskId);
            m.put("item", i == null ? null : OnCallRoutineWire.item(i, d, today, nowLocal, p.me(), byItem));
        }
        m.put("progress", OnCallRoutineWire.progress(OnCallRoutinePlan.progress(s, today, nowLocal),
                store.countProblems(c.teamId(), OnCallRoutineCodes.PROBLEM_OPEN)));
        Map<String, Object> dm = new LinkedHashMap<>();
        dm.put("status", wireStatus(c, d, row, today));
        dm.put("version", row == null ? 0 : row.version());
        m.put("day", dm);
        return m;
    }

    /** Sayfadan bir görevin tel biçimi (toplu sonuç satırları için). */
    public Map<String, Object> itemOnSheet(TeamCtx c, Perms p, LocalDate d, String kind, String periodKey, String taskId) {
        LocalDate today = today();
        DayRow row = store.day(c.teamId(), d.toString());
        Sheet s = sheet(c, d, row, store.items(c.teamId(), OnCallRoutinePlan.periodsSeenFrom(d)), today);
        Item i = s.find(kind, periodKey, taskId);
        return i == null ? null : OnCallRoutineWire.item(i, d, today, nowLocal(), p.me(), store.openProblemsByItem(c.teamId()));
    }

    public void sectionEvent(Long teamId, LocalDate d, String state, int count, Actor a) {
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("state", state);
        det.put("count", count);
        event(teamId, d.toString(), OnCallRoutineCodes.EV_SECTION_DONE, null, null, a, stamp(), det);
    }

    // ── Görev notu ───────────────────────────────────────────────────────────────────────────────

    public record NoteResult(boolean changed, Map<String, Object> current) { }

    public NoteResult saveItemNote(TeamCtx c, LocalDate d, String taskId, String kind, String periodKey, String note,
                                   int noteVersion, Actor a) {
        checkDayWrite(c, d, today());
        Instant now = now();
        String at = OnCallRoutineStore.ts(now);
        Row existing = store.item(c.teamId(), kind, periodKey, taskId);
        Item planned = plannedItem(c, d, taskId, kind, periodKey, existing);
        ensureDayRow(c, d, now);
        String value = note == null || note.isEmpty() ? null : note;
        return tx.execute(st -> {
            DayRow day = store.lockDay(c.teamId(), d.toString());
            if (OnCallRoutineCodes.KIND_DAY.equals(kind) && day != null && OnCallRoutineCodes.completed(day.status())) {
                throw OnCallRoutineErrors.dayCompleted(dayBrief(day));
            }
            if (planned != null) {
                store.insertItemIfAbsent(c.teamId(), planned, OnCallRoutineCodes.PENDING, OnCallRoutineCodes.SRC_TOUCH,
                        c.templateVersion(), now);
            }
            Row row = store.lockItem(c.teamId(), kind, periodKey, taskId);
            if (row == null) throw OnCallRoutineErrors.taskNotFound();
            if (Objects.equals(row.note(), value)) return new NoteResult(false, noteWire(row));
            if (row.noteVersion() != noteVersion) throw OnCallRoutineErrors.noteChanged(noteWire(row));
            int n = store.setItemNote(row.id(), row.noteVersion(), value, a.name(), at);
            if (n != 1) throw OnCallRoutineErrors.noteChanged(noteWire(store.item(c.teamId(), kind, periodKey, taskId)));
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("title", row.title());
            det.put("length", value == null ? 0 : value.length());
            event(c.teamId(), d.toString(), OnCallRoutineCodes.EV_ITEM_NOTE, taskId, null, a, at, det);
            return new NoteResult(true, noteWire(store.lockItem(c.teamId(), kind, periodKey, taskId)));
        });
    }

    static Map<String, Object> noteWire(Row row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("note", row == null || row.note() == null ? "" : row.note());
        m.put("note_version", row == null ? 0 : row.noteVersion());
        m.put("note_by_name", row == null ? null : row.noteByName());
        m.put("note_at", row == null ? null : OnCallRoutineWire.z(row.noteAt()));
        return m;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Sorunlar
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public record ProblemCreated(ProblemRow problem, boolean itemTouched) { }

    public ProblemCreated createProblem(TeamCtx c, LocalDate d, String taskId, String kind, String periodKey, String severity,
                                        String note, boolean markDone, Actor a) {
        checkDayWrite(c, d, today());
        Instant now = now();
        String at = OnCallRoutineStore.ts(now);
        Row existing = taskId == null ? null : store.item(c.teamId(), kind, periodKey, taskId);
        Item planned = taskId == null ? null : plannedItem(c, d, taskId, kind, periodKey, existing);
        ensureDayRow(c, d, now);
        return tx.execute(st -> {
            DayRow day = store.lockDay(c.teamId(), d.toString());
            Long itemId = null;
            String taskTitle = null, sectionTitle = null;
            boolean touched = false;
            if (taskId != null) {
                if (planned != null) {
                    store.insertItemIfAbsent(c.teamId(), planned, OnCallRoutineCodes.PENDING, OnCallRoutineCodes.SRC_TOUCH,
                            c.templateVersion(), now);
                }
                Row row = store.lockItem(c.teamId(), kind, periodKey, taskId);
                if (row == null) throw OnCallRoutineErrors.taskNotFound();
                itemId = row.id();
                taskTitle = row.title();
                sectionTitle = row.sectionTitle();
                // "Sorun var" = işaretli + sorun. Tamamlanmış günün günlük görevi değiştirilmez (sorun yine kaydedilir).
                boolean dayLocked = OnCallRoutineCodes.KIND_DAY.equals(kind) && day != null
                        && OnCallRoutineCodes.completed(day.status());
                if (markDone && !dayLocked && OnCallRoutineCodes.PENDING.equals(row.state())) {
                    store.setItemState(row.id(), row.version(), OnCallRoutineCodes.DONE, a.id(), a.name(), at, null);
                    touched = true;
                }
            }
            Long id = store.insertProblem(c.teamId(), d.toString(), itemId, taskId, taskTitle, sectionTitle, severity, note,
                    a.id(), a.name(), at);
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("severity", severity);
            if (taskTitle != null) det.put("title", taskTitle);
            if (touched) det.put("marked_done", true);
            event(c.teamId(), d.toString(), OnCallRoutineCodes.EV_PROBLEM_OPEN, taskId, id, a, at, det);
            return new ProblemCreated(store.problem(id), touched);
        });
    }

    public ProblemRow problemOrThrow(Long id) {
        ProblemRow p = id == null ? null : store.problem(id);
        if (p == null) throw OnCallRoutineErrors.problemNotFound();
        return p;
    }

    public Map<String, Object> problemWire(TeamCtx c, Perms p, ProblemRow pr) {
        return problemsWire(c, p, List.of(pr), today()).get(0);
    }

    public ProblemRow updateProblem(ProblemRow current, int version, Map<?, ?> body, Actor a, TeamCtx c, Perms p) {
        String at = stamp();
        String today = today().toString();
        return tx.execute(st -> {
            ProblemRow pr = store.lockProblem(current.id());
            if (pr == null) throw OnCallRoutineErrors.problemNotFound();
            if (pr.version() != version) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("current", problemsWire(c, p, List.of(pr), today()).get(0));
                throw OnCallRoutineErrors.concurrent(extra);
            }
            String severity = body.containsKey("severity") ? severityOf(body.get("severity"), pr.severity()) : pr.severity();
            String note = body.containsKey("note") ? problemNote(body.get("note")) : pr.note();
            String status = pr.status();
            if (body.containsKey("status")) {
                Object s = body.get("status");
                if (!(s instanceof String ss) || !OnCallRoutineCodes.PROBLEM_STATUSES.contains(ss)) {
                    throw OnCallRoutineErrors.field("status", "Durum OPEN ya da RESOLVED olmalı.", "The status must be OPEN or RESOLVED.");
                }
                status = ss;
            }
            String resolution = pr.resolutionNote();
            if (body.containsKey("resolution_note")) resolution = resolutionNote(body.get("resolution_note"));
            Long rById = pr.resolvedById();
            String rByName = pr.resolvedByName(), rAt = pr.resolvedAt(), rDay = pr.resolvedDay();
            String kind = OnCallRoutineCodes.EV_PROBLEM_UPDATE;
            if (!status.equals(pr.status())) {
                if (OnCallRoutineCodes.PROBLEM_RESOLVED.equals(status)) {
                    rById = a.id();
                    rByName = a.name();
                    rAt = at;
                    rDay = today;
                    kind = OnCallRoutineCodes.EV_PROBLEM_RESOLVE;
                } else {
                    rById = null;
                    rByName = null;
                    rAt = null;
                    rDay = null;
                    resolution = body.containsKey("resolution_note") ? resolution : null;
                    kind = OnCallRoutineCodes.EV_PROBLEM_REOPEN;
                }
            }
            if (Objects.equals(severity, pr.severity()) && Objects.equals(note, pr.note()) && status.equals(pr.status())
                    && Objects.equals(resolution, pr.resolutionNote())) {
                return pr;
            }
            int n = store.updateProblem(pr.id(), pr.version(), severity, note, status, resolution, a.name(), at, rById, rByName,
                    rAt, rDay);
            if (n != 1) throw OnCallRoutineErrors.concurrent(new LinkedHashMap<>());
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("severity", severity);
            if (pr.taskTitle() != null) det.put("title", pr.taskTitle());
            event(pr.teamId(), pr.day(), kind, pr.taskId(), pr.id(), a, at, det);
            return store.problem(pr.id());
        });
    }

    public void deleteProblem(ProblemRow pr, Actor a) {
        String at = stamp();
        tx.executeWithoutResult(st -> {
            ProblemRow locked = store.lockProblem(pr.id());
            if (locked == null) throw OnCallRoutineErrors.problemNotFound();
            store.deleteProblem(pr.id());
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("severity", pr.severity());
            if (pr.taskTitle() != null) det.put("title", pr.taskTitle());
            event(pr.teamId(), pr.day(), OnCallRoutineCodes.EV_PROBLEM_DELETE, pr.taskId(), pr.id(), a, at, det);
        });
    }

    public ImageMeta uploadImage(ProblemRow pr, byte[] data, String fileName, Actor a) {
        TechErrorImageValidator.Checked chk = TechErrorImageValidator.check(data);
        String at = stamp();
        return tx.execute(st -> {
            // Takımın toplam görsel sınırı takım satırı kilidiyle (aynı takımın iki sorununa eşzamanlı yükleme sınırı aşamaz);
            // sıra takım → sorun — sorun kilidini tutup takımı kilitleyen yol yok
            store.lockTeam(pr.teamId());
            ProblemRow locked = store.lockProblem(pr.id());
            if (locked == null) throw OnCallRoutineErrors.problemNotFound();
            if (store.countImages(pr.id()) >= OnCallRoutineCodes.MAX_IMAGES_PER_PROBLEM
                    || store.teamImageBytes(pr.teamId()) + data.length > OnCallRoutineCodes.MAX_TEAM_IMAGE_BYTES) {
                throw OnCallRoutineErrors.imageLimit();
            }
            Long id = store.insertImage(pr.teamId(), pr.id(), chk.contentType(), safeName(fileName), data.length, chk.width(),
                    chk.height(), data, a.name(), at);
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("size_bytes", data.length);
            event(pr.teamId(), pr.day(), OnCallRoutineCodes.EV_PROBLEM_IMAGE, pr.taskId(), pr.id(), a, at, det);
            return store.imageMeta(id);
        });
    }

    /** Görseli siler (sorun satırı önce kilitlenir); alan geçmişine {@code PROBLEM_IMAGE} + {@code removed: true}. */
    public void deleteImage(ProblemRow pr, Long imageId, Actor a) {
        String at = stamp();
        tx.executeWithoutResult(st -> {
            ProblemRow locked = store.lockProblem(pr.id());
            if (locked == null) throw OnCallRoutineErrors.problemNotFound();
            int n = store.deleteImage(imageId, pr.id());
            if (n != 1) throw OnCallRoutineErrors.imageNotFound();
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("removed", true);
            event(pr.teamId(), pr.day(), OnCallRoutineCodes.EV_PROBLEM_IMAGE, pr.taskId(), pr.id(), a, at, det);
        });
    }

    public ImageMeta imageMeta(Long imageId) {
        return store.imageMeta(imageId);
    }

    public byte[] imageBytes(Long imageId) {
        return store.imageBytes(imageId);
    }

    public ProblemRow linkIncident(ProblemRow current, int version, Long incidentId, Actor a, TeamCtx c, Perms p) {
        String at = stamp();
        return tx.execute(st -> {
            ProblemRow pr = store.lockProblem(current.id());
            if (pr == null) throw OnCallRoutineErrors.problemNotFound();
            if (pr.version() != version) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("current", problemsWire(c, p, List.of(pr), today()).get(0));
                throw OnCallRoutineErrors.concurrent(extra);
            }
            if (Objects.equals(pr.incidentRecordId(), incidentId)) return pr;
            store.linkIncident(pr.id(), pr.version(), incidentId, a.name(), at);
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("incident_id", incidentId);
            event(pr.teamId(), pr.day(), OnCallRoutineCodes.EV_INCIDENT_LINK, pr.taskId(), pr.id(), a, at, det);
            return store.problem(pr.id());
        });
    }

    public IncidentRecord incident(Long id) {
        return id == null || incidents == null ? null : incidents.findById(id).orElse(null);
    }

    public static String severityOf(Object raw, String def) {
        if (raw == null) return def;
        if (raw instanceof String s && OnCallRoutineCodes.SEVERITIES.contains(s)) return s;
        throw OnCallRoutineErrors.field("severity", "Önem LOW, MEDIUM ya da HIGH olmalı.", "The severity must be LOW, MEDIUM or HIGH.");
    }

    public static String problemNote(Object raw) {
        String s = raw instanceof String str ? OnCallRoutineTemplate.multiLine(str) : null;
        if (s == null || s.length() < 3 || s.length() > 4000) {
            throw OnCallRoutineErrors.field("note", "Sorun notu 3–4000 karakter olmalı. Ne gördüğünüzü kısaca yazın.",
                    "The problem note must be 3–4000 characters. Briefly write what you saw.");
        }
        return s;
    }

    static String resolutionNote(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof String str)) throw OnCallRoutineErrors.field("resolution_note", "Çözüm notu metin olmalı.",
                "The resolution note must be text.");
        String s = OnCallRoutineTemplate.multiLine(str);
        if (s == null || s.isEmpty()) return null;
        if (s.length() > 2000) throw OnCallRoutineErrors.field("resolution_note", "Çözüm notu en çok 2000 karakter olabilir.",
                "The resolution note can be at most 2000 characters.");
        return s;
    }

    /**
     * Serbest not (görev / gün / devir): CRLF / CR → LF, NUL atılır, kenar boşlukları kırpılır; {@code null} / boş = temizle
     * ({@code ""}). Metin değilse ya da {@code max} karakteri aşarsa alan hatası.
     */
    public static String freeNote(Object raw, int max, String field) {
        if (raw == null) return "";
        if (!(raw instanceof String str)) throw OnCallRoutineErrors.field(field, "Not metin olmalı.", "The note must be text.");
        if (str.length() > max * 2 + 16) {
            throw OnCallRoutineErrors.field(field, "Not en çok " + max + " karakter olabilir.",
                    "The note can be at most " + max + " characters.");
        }
        String s = OnCallRoutineTemplate.multiLine(str);
        if (s == null) return "";
        if (s.length() > max) {
            throw OnCallRoutineErrors.field(field, "Not en çok " + max + " karakter olabilir. Kısaltıp yeniden kaydedin.",
                    "The note can be at most " + max + " characters. Shorten it and save again.");
        }
        return s;
    }

    /** Atlama nedeni: 3–300 karakter, tek satıra indirgenmez (çok satır kabul). */
    public static String skipReason(Object raw, String field) {
        String s = raw instanceof String str && str.length() <= 1000 ? OnCallRoutineTemplate.multiLine(str) : null;
        if (s == null || s.length() < 3 || s.length() > 300) {
            throw OnCallRoutineErrors.field(field, "Atlama nedeni 3–300 karakter olmalı. Neden yapılmadığını kısaca yazın.",
                    "The skip reason must be 3–300 characters. Briefly write why it wasn't done.");
        }
        return s;
    }

    static String safeName(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[\\\\/\\r\\n\\t\\u0000]", "_").trim();
        return s.isEmpty() ? null : (s.length() > 200 ? s.substring(0, 200) : s);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Gün tamamla / yeniden aç / not
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public record Completion(DayRow day, Progress progress, int seq, String status, Map<String, Object> audit) { }

    /**
     * Günü tamamlar. Zorunlu küme bekliyorsa ve atlama nedeni yoksa {@code DAY_NOT_READY}; nedenle kalan zorunlular SKIPPED
     * olur (COMPLETED_PARTIAL). Sayfanın tüm görevleri maddeleşir; sayaçlar, sorumlu ve {@code completion_seq + 1} yazılır.
     */
    public Completion completeDay(TeamCtx c, LocalDate d, Integer version, String skipReason, String note, Actor a) {
        LocalDate today = today();
        checkDayWrite(c, d, today);
        if (!c.workday(d)) throw OnCallRoutineErrors.notWorkday();
        Instant now = now();
        String at = OnCallRoutineStore.ts(now);
        Responsible resp = responsible(c, d);
        ensureDayRow(c, d, now);
        return tx.execute(st -> {
            DayRow day = store.lockDay(c.teamId(), d.toString());
            if (OnCallRoutineCodes.completed(day.status())) throw OnCallRoutineErrors.dayCompleted(dayBrief(day));
            if (version == null || day.version() != version) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("current_version", day.version());
                extra.put("completed_by_name", day.completedByName());
                extra.put("completed_at", OnCallRoutineWire.z(day.completedAt()));
                throw OnCallRoutineErrors.concurrent(extra);
            }
            // Açık gün: sayfanın görevlerini maddeleştir (açık görünüm kuralıyla). Kapanmış gün (INCOMPLETE / MISSED, geç
            // tamamlama): sayfa donmuştur — kapanışın maddeleştirdiği satırlar kullanılır, yeni görev eklenmez.
            boolean fz = !OnCallRoutineCodes.DAY_OPEN.equals(day.status());
            List<Row> rows = store.items(c.teamId(), OnCallRoutinePlan.periodsSeenFrom(d));
            Sheet sheet = OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), d, rows, fz);
            if (!fz) {
                List<Item> toInsert = new ArrayList<>(sheet.items().stream().filter(i -> i.row() == null && !i.removed()).toList());
                toInsert.sort(Comparator.comparing(Item::key));
                for (Item i : toInsert) {
                    store.insertItemIfAbsent(c.teamId(), i, OnCallRoutineCodes.PENDING, OnCallRoutineCodes.SRC_COMPLETE,
                            c.templateVersion(), now);
                }
                rows = store.items(c.teamId(), OnCallRoutinePlan.periodsSeenFrom(d));
            }
            Set<Long> ids = new java.util.TreeSet<>();
            for (Row r : rows) if (OnCallRoutinePlan.rowVisibleOn(r, d)) ids.add(r.id());
            rows = store.lockItemsById(ids);
            sheet = OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), d, rows, fz);
            List<Item> pending = sheet.pendingRequired();
            if (!pending.isEmpty() && skipReason == null) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Item i : pending) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("task_id", i.taskId());
                    m.put("period_kind", i.periodKind());
                    m.put("period_key", i.periodKey());
                    m.put("title", i.title());
                    m.put("section_title", i.sectionTitle());
                    list.add(m);
                }
                throw OnCallRoutineErrors.dayNotReady(list);
            }
            for (Item i : pending) {
                Row r = i.row();
                if (r == null) continue;
                store.setItemState(r.id(), r.version(), OnCallRoutineCodes.SKIPPED, a.id(), a.name(), at, skipReason);
            }
            if (note != null) store.replaceDayNote(day.id(), note.isEmpty() ? null : note, a.name(), at);
            rows = store.items(c.teamId(), OnCallRoutinePlan.periodsSeenFrom(d));
            sheet = OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), d, rows, fz);
            Progress pg = OnCallRoutinePlan.progress(sheet, today, nowLocal());
            boolean partial = pg.skipped() > 0;
            String status = partial ? OnCallRoutineCodes.DAY_COMPLETED_PARTIAL : OnCallRoutineCodes.DAY_COMPLETED;
            int probs = store.countProblemsOnDay(c.teamId(), d.toString());
            boolean late = d.isBefore(today) || OnCallRoutineCodes.DAY_INCOMPLETE.equals(day.status())
                    || OnCallRoutineCodes.DAY_MISSED.equals(day.status());
            DayRow fresh = store.lockDay(c.teamId(), d.toString());
            // Geçmiş gün: satırdaki sorumlu anlık görüntüsü korunur (bugünün çözümlemesiyle ezilmez — inceleme #11); bugün
            // tamamlama anında yeniden çözülür (§1.4).
            boolean keepSnapshot = d.isBefore(today) && fresh.responsibleUserId() != null;
            Long respId = keepSnapshot ? fresh.responsibleUserId() : resp.userId();
            String respName = keepSnapshot ? fresh.responsibleName() : resp.name();
            String respSource = keepSnapshot ? fresh.responsibleSource() : resp.source();
            int n = store.completeDay(fresh.id(), fresh.version(), status,
                    new OnCallRoutineStore.Counts(pg.required(), pg.done(), pg.skipped(), pg.pending(), probs),
                    respId, respName, respSource, a.id(), a.name(), at, late, c.templateVersion());
            if (n != 1) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("current_version", fresh.version());
                throw OnCallRoutineErrors.concurrent(extra);
            }
            DayRow done = store.day(c.teamId(), d.toString());
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("status", status);
            det.put("required", pg.required());
            det.put("done", pg.done());
            det.put("skipped", pg.skipped());
            det.put("late", late);
            det.put("seq", done.completionSeq());
            if (!pending.isEmpty()) det.put("skipped_now", pending.size());
            event(c.teamId(), d.toString(), OnCallRoutineCodes.EV_DAY_COMPLETE, null, null, a, at, det);
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("date", d.toString());
            audit.put("status", status);
            audit.put("required", pg.required());
            audit.put("done", pg.done());
            audit.put("skipped", pg.skipped());
            audit.put("problems_open", store.countProblems(c.teamId(), OnCallRoutineCodes.PROBLEM_OPEN));
            audit.put("late", late);
            audit.put("seq", done.completionSeq());
            return new Completion(done, pg, done.completionSeq(), status, audit);
        });
    }

    /** Tamamlama kaydı commit edildikten sonra bildirimi hemen dener (kilitsiz yan iş; yoksa sonraki tur ≤ 60 sn). */
    public void kickNotices() {
        try {
            OnCallRoutineJob j = job == null ? null : job.getIfAvailable();
            if (j != null) j.kick();
        } catch (RuntimeException e) {
            log.debug("Nöbet rutini bildirimi hemen tetiklenemedi (sonraki tur gönderir): {}", e.toString());
        }
    }

    public DayRow reopenDay(TeamCtx c, LocalDate d, Integer version, Actor a) {
        LocalDate today = today();
        if (!c.configured()) throw OnCallRoutineErrors.notConfigured();
        if (d.isAfter(today)) throw OnCallRoutineErrors.dayInFuture();
        if (d.isBefore(today.minusDays(OnCallRoutineCodes.EDIT_WINDOW_DAYS))) throw OnCallRoutineErrors.dayLocked();
        String at = stamp();
        return tx.execute(st -> {
            DayRow day = store.lockDay(c.teamId(), d.toString());
            if (day == null || !OnCallRoutineCodes.completed(day.status())) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("current_version", day == null ? 0 : day.version());
                throw OnCallRoutineErrors.concurrent(extra);
            }
            if (version == null || day.version() != version) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("current_version", day.version());
                extra.put("completed_by_name", day.completedByName());
                extra.put("completed_at", OnCallRoutineWire.z(day.completedAt()));
                throw OnCallRoutineErrors.concurrent(extra);
            }
            if (store.reopenDay(day.id(), day.version(), at) != 1) {
                throw OnCallRoutineErrors.concurrent(new LinkedHashMap<>(Map.of("current_version", day.version())));
            }
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("from", day.status());
            event(c.teamId(), d.toString(), OnCallRoutineCodes.EV_DAY_REOPEN, null, null, a, at, det);
            return store.day(c.teamId(), d.toString());
        });
    }

    public DayRow dayRow(Long teamId, LocalDate d) {
        return store.day(teamId, d.toString());
    }

    public NoteResult saveDayNote(TeamCtx c, LocalDate d, String note, int noteVersion, Actor a) {
        LocalDate today = today();
        checkDayWrite(c, d, today);
        // Gün notu gün sayfasına aittir; iş günü olmayan günün sayfası yok (satır da açılmaz — #5).
        if (!c.workday(d)) throw OnCallRoutineErrors.notWorkday();
        Instant now = now();
        String at = OnCallRoutineStore.ts(now);
        ensureDayRow(c, d, now);
        String value = note == null || note.isEmpty() ? null : note;
        return tx.execute(st -> {
            DayRow day = store.lockDay(c.teamId(), d.toString());
            if (Objects.equals(day.note(), value)) return new NoteResult(false, dayNoteWire(day));
            if (day.noteVersion() != noteVersion) throw OnCallRoutineErrors.noteChanged(dayNoteWire(day));
            if (store.saveDayNote(day.id(), day.noteVersion(), value, a.name(), at) != 1) {
                throw OnCallRoutineErrors.noteChanged(dayNoteWire(store.day(c.teamId(), d.toString())));
            }
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("length", value == null ? 0 : value.length());
            event(c.teamId(), d.toString(), OnCallRoutineCodes.EV_DAY_NOTE, null, null, a, at, det);
            return new NoteResult(true, dayNoteWire(store.day(c.teamId(), d.toString())));
        });
    }

    static Map<String, Object> dayNoteWire(DayRow d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("note", d == null || d.note() == null ? "" : d.note());
        m.put("note_version", d == null ? 0 : d.noteVersion());
        m.put("note_by_name", d == null ? null : d.noteByName());
        m.put("note_at", d == null ? null : OnCallRoutineWire.z(d.noteAt()));
        return m;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Devir notu / onayı
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Anahtarın geçerli ya da önceki dönem olması gerekir; değilse alan hatası. */
    public Period periodForKey(TeamCtx c, String key, boolean allowCurrent) {
        Period p = writablePeriod(c, key, allowCurrent);
        if (p != null) return p;
        throw OnCallRoutineErrors.field("period_key", "Devir dönemi geçerli ya da bir önceki dönem olmalı. Sayfayı yenileyin.",
                "The handover period must be the current or the previous one. Reload the page.");
    }

    /**
     * Devir yazma yollarının (not, onay) dönem kuralı — tek kaynak: BUGÜNÜN dönemi ({@code allowCurrent}) ya da bir önceki
     * dönem; daha eski ya da gelecek dönem null.
     */
    Period writablePeriod(TeamCtx c, String key, boolean allowCurrent) {
        if (key == null) return null;
        Period cur = c.period(today());
        if (allowCurrent && cur.key().toString().equals(key)) return cur;
        Period prev = OnCallRoutineCalendar.previous(c.rotation(), c.dayStart(), cur);
        if (prev.key().toString().equals(key)) return prev;
        return null;
    }

    /**
     * Devir notu yazılabilir mi — {@code PUT /handovers/{key}/note} yolunun denediği kuralın aynısı: işlem yetkisi
     * ({@link Perms#canWork()}: modül görünür + kendi takımı + izin), rutin kurulu ve dönem {@link #writablePeriod}.
     */
    boolean handoverNoteWritable(TeamCtx c, Perms p, String key) {
        return p.canWork() && c.configured() && writablePeriod(c, key, true) != null;
    }

    public NoteResult saveHandoverNote(TeamCtx c, Period period, String note, int noteVersion, Actor a) {
        if (!c.configured()) throw OnCallRoutineErrors.notConfigured();
        Instant now = now();
        String at = OnCallRoutineStore.ts(now);
        String key = period.key().toString();
        String value = note == null || note.isEmpty() ? null : note;
        return tx.execute(st -> {
            store.ensureHandover(c.teamId(), key, OnCallRoutineStore.ts(period.start()), OnCallRoutineStore.ts(period.end()), now);
            HandoverRow h = store.handover(c.teamId(), key);
            if (Objects.equals(h.note(), value)) return new NoteResult(false, handoverNoteWire(h));
            if (h.noteVersion() != noteVersion) throw OnCallRoutineErrors.noteChanged(handoverNoteWire(h));
            if (store.saveHandoverNote(c.teamId(), key, h.noteVersion(), value, a.name(), at) != 1) {
                throw OnCallRoutineErrors.noteChanged(handoverNoteWire(store.handover(c.teamId(), key)));
            }
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("period_key", key);
            det.put("length", value == null ? 0 : value.length());
            event(c.teamId(), today().toString(), OnCallRoutineCodes.EV_HANDOVER_NOTE, null, null, a, at, det);
            return new NoteResult(true, handoverNoteWire(store.handover(c.teamId(), key)));
        });
    }

    static Map<String, Object> handoverNoteWire(HandoverRow h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("note", h == null || h.note() == null ? "" : h.note());
        m.put("note_version", h == null ? 0 : h.noteVersion());
        m.put("note_by_name", h == null ? null : h.noteByName());
        m.put("note_at", h == null ? null : OnCallRoutineWire.z(h.noteAt()));
        return m;
    }

    public record AckResult(boolean changed, HandoverRow row) { }

    public AckResult ackHandover(TeamCtx c, Period period, Actor a) {
        if (!c.configured()) throw OnCallRoutineErrors.notConfigured();
        Instant now = now();
        if (period.end().isAfter(now)) throw OnCallRoutineErrors.handoverNotDue();
        String at = OnCallRoutineStore.ts(now);
        String key = period.key().toString();
        return tx.execute(st -> {
            store.ensureHandover(c.teamId(), key, OnCallRoutineStore.ts(period.start()), OnCallRoutineStore.ts(period.end()), now);
            int n = store.ackHandover(c.teamId(), key, a.id(), a.name(), at);
            HandoverRow h = store.handover(c.teamId(), key);
            if (n == 1) {
                Map<String, Object> det = new LinkedHashMap<>();
                det.put("period_key", key);
                event(c.teamId(), today().toString(), OnCallRoutineCodes.EV_HANDOVER_ACK, null, null, a, at, det);
            }
            return new AckResult(n == 1, h);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Devralma
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Anahtar doğrulaması: DAY = tarih, WEEK = devir dönemi anahtarı; [bugün − 7, bugün + 56]. İlk günü döner. */
    public LocalDate assignmentFirstDay(TeamCtx c, String scope, String key) {
        LocalDate d;
        try {
            d = LocalDate.parse(key == null ? "" : key);
        } catch (RuntimeException e) {
            throw OnCallRoutineErrors.field("key", "Anahtar yyyy-AA-gg biçiminde bir tarih olmalı.", "The key must be a yyyy-MM-dd date.");
        }
        LocalDate today = today();
        if (d.isBefore(today.minusDays(OnCallRoutineCodes.EDIT_WINDOW_DAYS))
                || d.isAfter(today.plusDays(OnCallRoutineCodes.ASSIGN_AHEAD_DAYS))) {
            throw OnCallRoutineErrors.field("key", "Devralma en çok 7 gün öncesi ile 56 gün sonrası arasında yapılabilir.",
                    "A take-over can be made between 7 days back and 56 days ahead.");
        }
        if ("WEEK".equals(scope)) {
            Period p = c.period(d);
            if (!p.key().equals(d)) {
                throw OnCallRoutineErrors.field("key", "Haftalık devralmada anahtar devir döneminin ilk günü olmalı ("
                        + p.key() + ").", "For a week take-over the key must be the first day of the handover period ("
                        + p.key() + ").");
            }
        } else if (!"DAY".equals(scope)) {
            throw OnCallRoutineErrors.field("scope", "Kapsam DAY ya da WEEK olmalı.", "The scope must be DAY or WEEK.");
        }
        return d;
    }

    public AssignmentRow assignment(Long teamId, String scope, String key) {
        return store.assignment(teamId, scope, key);
    }

    public Responsible assign(TeamCtx c, String scope, String key, LocalDate firstDay, Long userId, String userName,
                              String note, Actor a) {
        String at = stamp();
        tx.executeWithoutResult(st -> {
            store.upsertAssignment(c.teamId(), scope, key, userId, userName, note, a.id(), a.name(), at);
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("scope", scope);
            det.put("key", key);
            det.put("assignee_name", userName);
            event(c.teamId(), today().toString(), OnCallRoutineCodes.EV_TAKEOVER, null, null, a, at, det);
        });
        return responsible(c, firstDay);
    }

    public boolean clearAssignment(TeamCtx c, String scope, String key, Actor a) {
        String at = stamp();
        Boolean done = tx.execute(st -> {
            int n = store.deleteAssignment(c.teamId(), scope, key);
            if (n > 0) {
                Map<String, Object> det = new LinkedHashMap<>();
                det.put("scope", scope);
                det.put("key", key);
                event(c.teamId(), today().toString(), OnCallRoutineCodes.EV_TAKEOVER_CLEAR, null, null, a, at, det);
            }
            return n > 0;
        });
        return Boolean.TRUE.equals(done);
    }

    public String personName(Long userId) {
        Person p = persons(List.of(userId)).get(userId);
        return p == null ? null : p.name();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Geçmiş / sorun listesi
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public Map<String, Object> events(Long teamId, String day, Long before, int limit) {
        List<OnCallRoutineStore.EventRow> rows = store.events(teamId, day, before, limit + 1);
        boolean more = rows.size() > limit;
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            OnCallRoutineStore.EventRow e = rows.get(i);
            Map<String, Object> det = OnCallRoutineWire.parseDetail(e.detailJson());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("kind", e.kind());
            m.put("at", OnCallRoutineWire.z(e.at()));
            m.put("actor_name", e.actorName());
            m.put("task_id", e.taskId());
            m.put("task_title", det.get("title") instanceof String s ? s : null);
            m.put("problem_id", e.problemId());
            m.put("detail", det);
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("has_more", more);
        return out;
    }

    public Map<String, Object> problemList(TeamCtx c, Perms p, String status, int page, int size) {
        String st = "open".equals(status) ? OnCallRoutineCodes.PROBLEM_OPEN
                : "resolved".equals(status) ? OnCallRoutineCodes.PROBLEM_RESOLVED : null;
        int total = store.countProblems(c.teamId(), st);
        List<ProblemRow> rows = store.problems(c.teamId(), st, page * size, size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", problemsWire(c, p, rows, today()));
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Kapanış (iş — §5.4)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Gün kapanışının sonucu: kapandı, kapatılacak bir şey yok (zorunlu günlük görev yok, satır yok), dokunulmadı. */
    public enum CloseResult { CLOSED, NOTHING_DUE, SKIPPED }

    /** Günü kapatır (iş); kural {@link #closeDayResult}. true = kapandı. */
    public boolean closeDay(TeamCtx c, LocalDate d) {
        return closeDayResult(c, d) == CloseResult.CLOSED;
    }

    /**
     * Zorunlu günlük küme R(D) = GÜN SONUNDAKİ şablonun planladığı + satırı olan DAY görevleri, kaldırılmış / pasif görevler
     * hariç (açık sayfanın kuralı: {@link OnCallRoutinePlan#sheet}). Kural (tasarım §1.6):
     * <ul>
     *   <li>R(D) içinde bekleyen var → hiç yapılmamışsa {@code MISSED}, yoksa {@code INCOMPLETE} (bekleyenler MISSED).</li>
     *   <li>R(D)'nin hepsi DONE / SKIPPED ama gün tamamlanmamış → iş günü kendiliğinden TAMAMLAR ({@code COMPLETED} ya da
     *   atlanan varsa {@code COMPLETED_PARTIAL}; tamamlayan "SiteMonitor" (kimliksiz), bildirim YOK — {@code notified_seq}
     *   aynı güncellemede ilerler). §1.6 bu durumu tanımlamıyor: INCOMPLETE "≥ 1 bekleyen", MISSED "hiç yapılmamış" der;
     *   yapılmış bir günü kırmızı göstermemek için tamamlandı sayılır (geç değil — kapanış ertesi gün 00:30'da, tüm görevler
     *   günün içinde çözülmüş; yeniden açılmış bir günün pencere sonu kapanışı ise geç sayılır).</li>
     *   <li>R(D) boş (yalnız haftalık / aylık ya da yalnız isteğe bağlı günlük görevli şablon): satır yoksa hiçbir şey yazılmaz
     *   ({@link CloseResult#NOTHING_DUE}; gün tel biçiminde NONE kalır, kırmızı değil); satır varsa (isteğe bağlı bir görev
     *   işaretlendi, not yazıldı) yukarıdaki kural gibi kendiliğinden tamamlanır (0 / 0).</li>
     * </ul>
     */
    public CloseResult closeDayResult(TeamCtx c, LocalDate d) {
        String endStamp = OnCallRoutineStore.ts(OnCallRoutineCalendar.at(d, LocalTime.of(23, 59, 59)));
        OnCallRoutineStore.VersionRow v = store.templateAt(c.teamId(), endStamp);
        if (v == null) return CloseResult.SKIPPED;
        Template tpl = OnCallRoutineTemplate.readStored(v.templateJson()).template();
        Instant now = now();
        String at = OnCallRoutineStore.ts(now);
        DayRow existing = store.day(c.teamId(), d.toString());
        if (existing != null && !OnCallRoutineCodes.DAY_OPEN.equals(existing.status())) return CloseResult.SKIPPED;
        if (existing == null) {
            List<Row> before = store.itemsOfPeriod(c.teamId(), OnCallRoutineCodes.KIND_DAY, d.toString());
            Sheet probe = OnCallRoutinePlan.sheet(tpl, c.settings(), c.holidays(), d, before, false);
            if (requiredDay(probe).isEmpty()) return CloseResult.NOTHING_DUE;
        }
        Responsible resp = existing != null && existing.responsibleSource() != null
                ? new Responsible(existing.responsibleUserId(), existing.responsibleName(), null, true,
                existing.responsibleSource(), null)
                : responsible(c, d);
        if (existing == null) {
            store.ensureDay(c.teamId(), d.toString(), OnCallRoutineCalendar.weekKey(d), c.period(d).key().toString(),
                    resp.userId(), resp.name(), resp.source(), now);
        }
        boolean reopened = existing != null && existing.completionSeq() > 0;
        Boolean closed = tx.execute(st -> {
            DayRow day = store.lockDay(c.teamId(), d.toString());
            if (day == null || !OnCallRoutineCodes.DAY_OPEN.equals(day.status())) return false;
            // Gün satırı kilitliyken (tikler gün → görev sırasıyla kilitler) R(D)'nin durumu okunur
            DayCounts open = dayCounts(OnCallRoutinePlan.sheet(tpl, c.settings(), c.holidays(), d,
                    store.itemsOfPeriod(c.teamId(), OnCallRoutineCodes.KIND_DAY, d.toString()), false));
            int probs = store.countProblemsOnDay(c.teamId(), d.toString());
            Map<String, Object> det = new LinkedHashMap<>();
            String status;
            if (open.pending() == 0) {
                // §1.6'da tanımsız durum: zorunluların hepsi çözülmüş ama "Günü tamamla" basılmamış → kendiliğinden tamamlanır
                status = open.skipped() > 0 ? OnCallRoutineCodes.DAY_COMPLETED_PARTIAL : OnCallRoutineCodes.DAY_COMPLETED;
                int n = store.autoCompleteDay(day.id(), day.version(), status,
                        new OnCallRoutineStore.Counts(open.required(), open.done(), open.skipped(), 0, probs), resp.userId(),
                        resp.name(), resp.source(), Actor.system().name(), at, reopened, v.version());
                if (n != 1) return false;
                det.put("auto_completed", true);
                det.put("missed", 0);
            } else {
                List<Item> planned = OnCallRoutinePlan.periodItems(tpl, c.settings(), c.holidays(), OnCallRoutineCodes.KIND_DAY,
                        d.toString());
                List<Item> sorted = new ArrayList<>(planned);
                sorted.sort(Comparator.comparing(Item::key));
                for (Item i : sorted) {
                    store.insertItemIfAbsent(c.teamId(), i, OnCallRoutineCodes.MISSED, OnCallRoutineCodes.SRC_CLOSE, v.version(), now);
                }
                store.missPending(c.teamId(), OnCallRoutineCodes.KIND_DAY, d.toString(), at);
                DayCounts after = dayCounts(OnCallRoutinePlan.sheet(tpl, c.settings(), c.holidays(), d,
                        store.itemsOfPeriod(c.teamId(), OnCallRoutineCodes.KIND_DAY, d.toString()), false));
                // §1.6: hiç zorunlu görev yapılmadıysa MISSED, yoksa INCOMPLETE (≥ 1 yapılmış, ≥ 1 kaçırılmış)
                status = after.done() == 0 ? OnCallRoutineCodes.DAY_MISSED : OnCallRoutineCodes.DAY_INCOMPLETE;
                int n = store.closeDay(day.id(), day.version(), status,
                        new OnCallRoutineStore.Counts(after.required(), after.done(), after.skipped(), after.pending(), probs),
                        resp.userId(), resp.name(), resp.source(), v.version(), at);
                if (n != 1) return false;
                det.put("missed", after.pending());
                open = after;
            }
            det.put("status", status);
            det.put("required", open.required());
            det.put("done", open.done());
            event(c.teamId(), d.toString(), OnCallRoutineCodes.EV_DAY_CLOSE, null, null, Actor.system(), at, det);
            return true;
        });
        return Boolean.TRUE.equals(closed) ? CloseResult.CLOSED : CloseResult.SKIPPED;
    }

    /** Sayfanın zorunlu GÜNLÜK kümesi R(D) — kaldırılmış / pasif görevler hariç. */
    static List<Item> requiredDay(Sheet sheet) {
        List<Item> out = new ArrayList<>();
        for (Item i : sheet.items()) {
            if (i.removed() || !i.required() || !OnCallRoutineCodes.KIND_DAY.equals(i.periodKind())) continue;
            out.add(i);
        }
        return out;
    }

    /** R(D) sayıları: DONE, SKIPPED, kalanı (PENDING / MISSED / satırsız) bekleyen. */
    static DayCounts dayCounts(Sheet sheet) {
        int req = 0, done = 0, skipped = 0, pending = 0;
        for (Item i : requiredDay(sheet)) {
            req++;
            String st = i.state();
            if (OnCallRoutineCodes.DONE.equals(st)) done++;
            else if (OnCallRoutineCodes.SKIPPED.equals(st)) skipped++;
            else pending++;
        }
        return new DayCounts(req, done, skipped, pending);
    }

    record DayCounts(int required, int done, int skipped, int pending) { }

    /**
     * Hafta / ay kapanışı: dönem sonundaki şablonla planlı görevleri MISSED maddeleştirir, kalan PENDING'leri MISSED yapar.
     * Hedef günü rutinin etkinleştiği günden ({@code enabled_since}) önce kalan ya da duraklatılmış güne düşen görev
     * OLUŞTURULMAZ (ilk dönemde sahte "kaçırıldı" olmasın). Kalıcı işaret ({@code week_closed_through} /
     * {@code month_closed_through}): bu dönem ya da daha yenisi kapatıldıysa hiçbir şey yazılmaz — yeniden başlatma / başka pod
     * kapanıştan sonra PENDING'e çekilen görevi yeniden MISSED yapmaz. Çağıran dönemleri en eskiden yeniye işler.
     */
    public int closePeriod(TeamCtx c, String kind, String key, LocalDate lastDay) {
        String endStamp = OnCallRoutineStore.ts(OnCallRoutineCalendar.at(lastDay, LocalTime.of(23, 59, 59)));
        OnCallRoutineStore.VersionRow v = store.templateAt(c.teamId(), endStamp);
        if (v == null) return 0;
        Template tpl = OnCallRoutineTemplate.readStored(v.templateJson()).template();
        Instant now = now();
        String at = OnCallRoutineStore.ts(now);
        String since = c.row() == null ? null : c.row().enabledSince();
        Integer n = tx.execute(st -> {
            String through = store.periodClosedThrough(c.teamId(), kind);
            if (through != null && key.compareTo(through) <= 0) return 0;   // bu dönem kapatılmış
            List<Item> planned = OnCallRoutinePlan.periodItems(tpl, c.settings(), c.holidays(), kind, key);
            List<Item> sorted = new ArrayList<>(planned);
            sorted.sort(Comparator.comparing(Item::key));
            int inserted = 0;
            for (Item i : sorted) {
                if (!periodItemDue(i, since, c)) continue;
                inserted += store.insertItemIfAbsent(c.teamId(), i, OnCallRoutineCodes.MISSED, OnCallRoutineCodes.SRC_CLOSE,
                        v.version(), now);
            }
            int missed = store.missPending(c.teamId(), kind, key, at);
            store.markPeriodClosed(c.teamId(), kind, key);
            return inserted + missed;
        });
        return n == null ? 0 : n;
    }

    /** Dönem görevi kapanışta maddeleşmeli mi: hedef günü rutin başladıktan sonra ve duraklatılmamış bir günde. */
    static boolean periodItemDue(Item i, String enabledSince, TeamCtx c) {
        LocalDate due = i.dueDay();
        if (due == null) return true;
        if (enabledSince != null && due.toString().compareTo(enabledSince) < 0) return false;
        return !c.paused(due);
    }

    /** Devir özetini saklar (bir kez) — dönemin günleri önce kapanmış olmalı (çağıran sağlar). */
    public boolean storeHandover(TeamCtx c, Period prev) {
        Instant now = now();
        String key = prev.key().toString();
        // Önce oku: iş 24 saat boyunca her turda buraya gelir — özet saklandıysa yazma denemesi yok
        HandoverRow h = store.handover(c.teamId(), key);
        if (h != null && h.summaryJson() != null) return false;
        if (h == null) {
            store.ensureHandover(c.teamId(), key, OnCallRoutineStore.ts(prev.start()), OnCallRoutineStore.ts(prev.end()), now);
        }
        HandoverSummary s = handoverSummary(c, prev);
        return store.storeHandoverSummary(c.teamId(), key, OnCallRoutineWire.json(s.toMap()), OnCallRoutineStore.ts(now)) == 1;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Yaşam döngüsü
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Takım silinince (AdminController.deleteTeam) — kendi denemesinde; hata WARN (yetim kuralı temizler). */
    public void deleteForTeam(Long teamId) {
        if (teamId == null) return;
        ownTransaction(() -> store.deleteForTeam(teamId),
                "Nöbet rutini satırları takımla silinemedi (takım " + teamId + ") — yetim saklama kuralı temizler");
    }

    /** Kullanıcı silinince devralma satırları (ad anlık görüntüleri kalır). */
    public void deleteUser(Long userId) {
        if (userId == null) return;
        ownTransaction(() -> store.deleteAssignmentsOfUser(userId),
                "Nöbet rutini devralmaları kullanıcıyla silinemedi (kullanıcı " + userId + ")");
    }

    /**
     * Temizliği ÇAĞIRANIN işleminden bağımsız koşar: etkin bir işlem varsa onaylandıktan SONRA (geri alınan silmede hiçbir
     * şey silinmez) kendi işleminde ({@code REQUIRES_NEW} — afterCommit içinde REQUIRED eski, bitmiş işleme katılırdı),
     * yoksa hemen kendi işleminde. Hata yalnız WARN'dır: PostgreSQL'de başarısız bir ifade çağıranın işlemini zehirleyip
     * (25P02) takım / kullanıcı silmesini geri aldırmasın; kalan satırları yetim saklama kuralı temizler.
     */
    private void ownTransaction(Runnable work, String failure) {
        Runnable run = () -> {
            try {
                TransactionTemplate own = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
                own.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                own.executeWithoutResult(st -> work.run());
            } catch (RuntimeException e) {
                log.warn("{}: {}", failure, e.getClass().getSimpleName());
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            run.run();
                        }
                    });
        } else {
            run.run();
        }
    }

    public Map<String, Object> impact(Long teamId) {
        return store.impact(teamId);
    }

    public OnCallRoutineStore store() {
        return store;
    }

    /** Yönetici genel bakışı: takım × son 30 gün durum sayıları (tek gruplu sorgu). */
    public Map<Long, Map<String, Integer>> last30(LocalDate today) {
        Map<Long, Map<String, Integer>> out = new HashMap<>();
        for (Object[] r : store.dayStatusCounts(today.minusDays(30).toString(), today.minusDays(1).toString())) {
            Long team = (Long) r[0];
            out.computeIfAbsent(team, k -> new HashMap<>()).merge((String) r[1], (Integer) r[2], Integer::sum);
        }
        return out;
    }

    /** Görünen ad: görünen ad → ad soyad → kullanıcı adı. */
    static String displayNameOf(String username, String display, String first, String last) {
        if (display != null && !display.isBlank()) return display;
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return full.isEmpty() ? username : full;
    }
}

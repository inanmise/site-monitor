package com.sitemonitor.service.report.executive;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.ExecutiveReportRow;
import com.sitemonitor.model.ExecutiveSummaryTeamReport;
import com.sitemonitor.model.ExecutiveSummaryTeamSettings;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.ExecutiveSummaryTeamReportRepository;
import com.sitemonitor.repository.ExecutiveSummaryTeamSettingsRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.InactiveRecipientGuard;
import com.sitemonitor.service.UserService;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * TAKIM YÖNETİCİ ÖZETİ AYARLARI ve ALICILARI (2026-10-10, kullanıcı isteği: "takım bazlı yönetici ayarlaması yapabilmemiz
 * lazım").
 *
 * <h2>Alıcılar</h2>
 * Birleşim, küçük harf tekil; pasif kullanıcıya ait adres düşer ({@link InactiveRecipientGuard}):
 * <ol>
 *   <li><b>Takım müdürü</b> — Takım Yönetimi'nde atanmış {@code teams.manager_id} (aktif ve adresi varsa).</li>
 *   <li><b>Takımı yöneten müdürler</b> — AD kaynaklı kapsamlı yöneticiler (rol ADMIN, LDAP) arasında yönetim kapsamında
 *       ({@link UserService#computeManageTeamIds}) bu takım olanlar. Global yöneticiler bu kümeye girmez (kurum özetinin
 *       alıcısıdırlar; isterlerse üye ya da ek adres olarak eklenir).</li>
 *   <li><b>Seçilen üyeler</b> — takımın AKTİF üyeleri arasından seçilenler; üye takımdan ayrılınca gönderimde düşer.</li>
 *   <li><b>Ek adresler</b> — en fazla {@value #MAX_EXTRA_EMAILS}.</li>
 * </ol>
 *
 * <h2>Maliyet</h2>
 * Ayar listesi (tüm takımlar) SABİT sayıda sorgu: ayar satırları, takımlar, kapsamdaki takımların üyeleri (tek sorgu),
 * AD yöneticileri + yönetim kapsamları (yönetici başına bir ast sorgusu — yönetici sayısıyla sınırlı), seçilen ayın
 * kayıtları. Takım başına sorgu yok.
 *
 * <p>Bu sınıf YETKİ kararı vermez — kim hangi takımın ayarını görür / değiştirir denetleyicide.
 */
@Slf4j
@Service
public class ExecutiveSummaryTeamService {

    public static final int MAX_EXTRA_EMAILS = 200;
    public static final int MAX_SELECTED_USERS = 500;
    /** Önizlemede gösterilen adres sayısı. */
    static final int PREVIEW = 20;

    static final boolean DEFAULT_INCLUDE_MANAGER = true;
    static final boolean DEFAULT_INCLUDE_TEAM_ADMINS = true;

    private final ExecutiveSummaryTeamSettingsRepository settingsRepo;
    private final ExecutiveSummaryTeamReportRepository reportRepo;
    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;
    private final UserService userService;

    @Autowired(required = false)
    private InactiveRecipientGuard inactiveGuard;

    private Supplier<Instant> clock = Instant::now;

    public ExecutiveSummaryTeamService(ExecutiveSummaryTeamSettingsRepository settingsRepo,
                                       ExecutiveSummaryTeamReportRepository reportRepo, TeamRepository teamRepo,
                                       AppUserRepository userRepo, @Lazy UserService userService) {
        this.settingsRepo = settingsRepo;
        this.reportRepo = reportRepo;
        this.teamRepo = teamRepo;
        this.userRepo = userRepo;
        this.userService = userService;
    }

    void setInactiveGuard(InactiveRecipientGuard g) { this.inactiveGuard = g; }

    void setClock(Supplier<Instant> clock) { this.clock = clock; }

    // ── Ayar okuma ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** Takımın etkin ayarı (satır yoksa varsayılanlar; kapalı). */
    public record Settings(long teamId, boolean enabled, boolean includeManager, boolean includeTeamAdmins,
                           List<Long> userIds, List<String> extraEmails, String updatedAt, String updatedBy) { }

    public Settings settingsOf(Long teamId) {
        ExecutiveSummaryTeamSettings row = teamId == null ? null : settingsRepo.findById(teamId).orElse(null);
        return toSettings(teamId == null ? 0L : teamId, row);
    }

    static Settings toSettings(long teamId, ExecutiveSummaryTeamSettings row) {
        if (row == null) {
            return new Settings(teamId, false, DEFAULT_INCLUDE_MANAGER, DEFAULT_INCLUDE_TEAM_ADMINS, List.of(), List.of(),
                    null, null);
        }
        return new Settings(teamId, Boolean.TRUE.equals(row.getEnabled()),
                row.getIncludeManager() == null ? DEFAULT_INCLUDE_MANAGER : row.getIncludeManager(),
                row.getIncludeTeamAdmins() == null ? DEFAULT_INCLUDE_TEAM_ADMINS : row.getIncludeTeamAdmins(),
                parseIds(row.getRecipientUserIdsCsv()), ExecutiveSummarySettings.parseEmails(row.getExtraEmails()),
                row.getUpdatedAt(), row.getUpdatedBy());
    }

    /** Gönderimi açık takımlar (zamanlanmış koşu). */
    public List<Long> enabledTeamIds() {
        try {
            return settingsRepo.findByEnabledTrueOrderByTeamIdAsc().stream().map(ExecutiveSummaryTeamSettings::getTeamId)
                    .filter(Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("Takım yönetici özeti: açık takımlar okunamadı: {}", e.toString());
            return List.of();
        }
    }

    /** Açık takım var mı (saatlik telafi kapısı — tek sayım). */
    public boolean anyEnabled() {
        try {
            return settingsRepo.existsByEnabledTrue();
        } catch (Exception e) {
            return false;
        }
    }

    // ── Alıcılar ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Çözülmüş alıcılar: adresler + kaynak başına sayılar + pasif olduğu için düşenler + uyarı kodları. */
    public record Recipients(List<String> emails, int managerCount, int adminCount, int memberCount, int extraCount,
                             int droppedInactive, List<String> notes) {
        public boolean isEmpty() { return emails.isEmpty(); }
    }

    /** Uyarı kodları (arayüz {@code exec.team.note.<KOD>}). */
    public static final String NOTE_NO_MANAGER = "NO_MANAGER";
    public static final String NOTE_MANAGER_NO_EMAIL = "MANAGER_NO_EMAIL";
    public static final String NOTE_MANAGER_INACTIVE = "MANAGER_INACTIVE";
    public static final String NOTE_NO_TEAM_ADMINS = "NO_TEAM_ADMINS";
    public static final String NOTE_LEFT_MEMBERS = "LEFT_MEMBERS";

    /** Tek takımın alıcıları (gönderim yolu). */
    public Recipients recipients(Long teamId) {
        Team team = teamId == null ? null : teamRepo.findById(teamId).orElse(null);
        if (team == null) return new Recipients(List.of(), 0, 0, 0, 0, 0, List.of());
        Snapshot snap = snapshot(List.of(teamId));
        return resolve(settingsOf(teamId), team, snap);
    }

    /**
     * Birden çok takımın alıcıları TEK okumayla (zamanlanmış koşu: N açık takım için üyeler / müdürler bir kez okunur).
     * Silinmiş ya da PASİF takım sonuçta yer almaz.
     */
    public Map<Long, Recipients> recipientsFor(Collection<Long> teamIds) {
        Map<Long, Recipients> out = new LinkedHashMap<>();
        List<Team> teams = teams(teamIds);
        teams.removeIf(t -> Boolean.FALSE.equals(t.getActive()));
        if (teams.isEmpty()) return out;
        List<Long> ids = teams.stream().map(Team::getId).toList();
        Map<Long, ExecutiveSummaryTeamSettings> rows = new HashMap<>();
        try {
            for (ExecutiveSummaryTeamSettings r : settingsRepo.findAllById(ids)) rows.put(r.getTeamId(), r);
        } catch (Exception e) {
            log.warn("Takım yönetici özeti: ayarlar okunamadı: {}", e.toString());
        }
        Snapshot snap = snapshot(ids);
        for (Team t : teams) out.put(t.getId(), resolve(toSettings(t.getId(), rows.get(t.getId())), t, snap));
        return out;
    }

    /** Takım adı (silinmiş takım → null). */
    public String teamName(Long teamId) {
        if (teamId == null) return null;
        try {
            return teamRepo.findById(teamId).map(Team::getName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Ayar ekranı / liste için toplu okunmuş kullanıcı verisi (takım başına sorgu yok). */
    record Snapshot(Map<Long, AppUser> usersById, Map<Long, List<AppUser>> membersByTeam,
                    Map<Long, List<AppUser>> adminsByTeam) { }

    Snapshot snapshot(Collection<Long> teamIds) {
        Map<Long, AppUser> byId = new HashMap<>();
        Map<Long, List<AppUser>> members = new HashMap<>();
        Map<Long, List<AppUser>> admins = new HashMap<>();
        Set<Long> scope = new LinkedHashSet<>(teamIds == null ? List.of() : teamIds);
        if (scope.isEmpty()) return new Snapshot(byId, members, admins);
        try {
            for (AppUser u : userRepo.findMembersOfTeams(scope)) {
                if (u == null || u.getId() == null) continue;
                byId.put(u.getId(), u);
                for (Long t : teamsOf(u)) if (scope.contains(t)) members.computeIfAbsent(t, k -> new ArrayList<>()).add(u);
            }
        } catch (Exception e) {
            log.warn("Takım yönetici özeti: takım üyeleri okunamadı: {}", e.toString());
        }
        try {
            for (AppUser u : userRepo.findByActiveTrueOrderByUsernameAsc()) {
                if (u == null || u.getId() == null) continue;
                byId.putIfAbsent(u.getId(), u);
                if (!"ADMIN".equals(u.getSystemRole()) || !"LDAP".equalsIgnoreCase(u.getAuthSource())) continue;
                List<Long> manage;
                try {
                    manage = userService.computeManageTeamIds(u);
                } catch (Exception e) {
                    manage = null;
                }
                if (manage == null) continue;           // global → kurum özetinin alıcısı, takım müdürü sayılmaz
                for (Long t : manage) if (t != null && scope.contains(t)) admins.computeIfAbsent(t, k -> new ArrayList<>()).add(u);
            }
        } catch (Exception e) {
            log.warn("Takım yönetici özeti: müdürler okunamadı: {}", e.toString());
        }
        return new Snapshot(byId, members, admins);
    }

    private static List<Long> teamsOf(AppUser u) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (u.getTeamIds() != null) for (Long t : u.getTeamIds()) if (t != null) ids.add(t);
        if (u.getTeamId() != null) ids.add(u.getTeamId());
        return new ArrayList<>(ids);
    }

    Recipients resolve(Settings s, Team team, Snapshot snap) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        int manager = 0, admins = 0, members = 0, extra = 0, dropped = 0;
        if (s.includeManager()) {
            Long mid = team.getManagerId();
            AppUser m = mid == null ? null : userOf(mid, snap);
            if (mid == null) notes.add(NOTE_NO_MANAGER);
            else if (m == null || !Boolean.TRUE.equals(m.getActive())) notes.add(NOTE_MANAGER_INACTIVE);
            else {
                String e = emailOf(m);
                if (e == null) notes.add(NOTE_MANAGER_NO_EMAIL);
                else if (out.putIfAbsent(e.toLowerCase(Locale.ROOT), e) == null) manager++;
            }
        }
        if (s.includeTeamAdmins()) {
            List<AppUser> list = snap.adminsByTeam().getOrDefault(team.getId(), List.of());
            if (list.isEmpty()) notes.add(NOTE_NO_TEAM_ADMINS);
            for (AppUser a : list) {
                String e = emailOf(a);
                if (e != null && Boolean.TRUE.equals(a.getActive()) && out.putIfAbsent(e.toLowerCase(Locale.ROOT), e) == null) admins++;
            }
        }
        if (!s.userIds().isEmpty()) {
            Set<Long> current = new java.util.HashSet<>();
            for (AppUser u : snap.membersByTeam().getOrDefault(team.getId(), List.of())) {
                if (Boolean.TRUE.equals(u.getActive())) current.add(u.getId());
            }
            int left = 0;
            for (Long id : s.userIds()) {
                if (!current.contains(id)) { left++; continue; }
                String e = emailOf(snap.usersById().get(id));
                if (e != null && out.putIfAbsent(e.toLowerCase(Locale.ROOT), e) == null) members++;
            }
            if (left > 0) notes.add(NOTE_LEFT_MEMBERS);
        }
        for (String e : s.extraEmails()) {
            if (isInactiveOnly(e)) { dropped++; continue; }
            if (out.putIfAbsent(e.toLowerCase(Locale.ROOT), e) == null) extra++;
        }
        return new Recipients(List.copyOf(out.values()), manager, admins, members, extra, dropped, List.copyOf(notes));
    }

    private AppUser userOf(Long id, Snapshot snap) {
        AppUser u = snap.usersById().get(id);
        if (u != null) return u;
        try {
            return userRepo.findById(id).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String emailOf(AppUser u) {
        if (u == null || u.getEmail() == null) return null;
        String e = u.getEmail().trim();
        return e.isEmpty() || !e.contains("@") ? null : e;
    }

    private boolean isInactiveOnly(String email) {
        try {
            return inactiveGuard != null && inactiveGuard.isInactiveOnlyEmail(email);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Görünümler ──────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Ayar listesi — verilen takımlar (çağıranın görebildikleri), A→Z. Satır: takım, açık mı, alıcı sayısı, uyarı kodları,
     * {@code month} ayının gönderim durumu.
     */
    public List<Map<String, Object>> overview(Collection<Long> teamIds, YearMonth month) {
        List<Team> teams = teams(teamIds);
        List<Long> ids = teams.stream().map(Team::getId).toList();
        Map<Long, ExecutiveSummaryTeamSettings> rows = new HashMap<>();
        try {
            for (ExecutiveSummaryTeamSettings r : settingsRepo.findAllById(ids)) rows.put(r.getTeamId(), r);
        } catch (Exception e) {
            log.warn("Takım yönetici özeti: ayarlar okunamadı: {}", e.toString());
        }
        Map<Long, ExecutiveSummaryTeamReport> reports = new HashMap<>();
        if (month != null && !ids.isEmpty()) {
            try {
                for (ExecutiveSummaryTeamReport r : reportRepo.findByReportYearAndReportMonthAndTeamIdIn(month.getYear(),
                        month.getMonthValue(), ids)) reports.put(r.getTeamId(), r);
            } catch (Exception e) {
                log.debug("Takım yönetici özeti: ay kayıtları okunamadı: {}", e.toString());
            }
        }
        Snapshot snap = snapshot(ids);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Team t : teams) {
            Settings s = toSettings(t.getId(), rows.get(t.getId()));
            Recipients rc = resolve(s, t, snap);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", t.getId());
            m.put("team_name", t.getName());
            m.put("team_active", !Boolean.FALSE.equals(t.getActive()));
            m.put("enabled", s.enabled());
            m.put("recipient_count", rc.emails().size());
            m.put("notes", rc.notes());
            ExecutiveSummaryTeamReport r = reports.get(t.getId());
            m.put("last_status", r == null ? null : r.getStatus());
            m.put("last_sent_at", r == null ? null : r.getSentAt());
            m.put("updated_at", s.updatedAt());
            out.add(m);
        }
        return out;
    }

    /** Tek takımın ayar ekranı: ayar + müdür + yöneten müdürler + seçilebilir üyeler + alıcı önizlemesi + geçmiş. */
    public Map<String, Object> detail(Long teamId) {
        Team team = teamRepo.findById(teamId).orElse(null);
        if (team == null) return null;
        Settings s = settingsOf(teamId);
        Snapshot snap = snapshot(List.of(teamId));
        Recipients rc = resolve(s, team, snap);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("team_id", team.getId());
        m.put("team_name", team.getName());
        m.put("team_active", !Boolean.FALSE.equals(team.getActive()));
        m.put("enabled", s.enabled());
        m.put("include_manager", s.includeManager());
        m.put("include_team_admins", s.includeTeamAdmins());
        m.put("extra_emails", String.join(", ", s.extraEmails()));
        m.put("updated_at", s.updatedAt());
        m.put("updated_by", s.updatedBy());

        AppUser mgr = team.getManagerId() == null ? null : userOf(team.getManagerId(), snap);
        m.put("manager", mgr == null ? null : person(mgr, null));
        List<Map<String, Object>> admins = new ArrayList<>();
        for (AppUser a : sorted(snap.adminsByTeam().getOrDefault(teamId, List.of()))) admins.add(person(a, null));
        m.put("team_admins", admins);
        Set<Long> selected = new LinkedHashSet<>(s.userIds());
        List<Map<String, Object>> members = new ArrayList<>();
        for (AppUser u : sorted(snap.membersByTeam().getOrDefault(teamId, List.of()))) {
            if (!Boolean.TRUE.equals(u.getActive())) continue;
            members.add(person(u, selected.contains(u.getId())));
        }
        m.put("members", members);

        m.put("recipient_count", rc.emails().size());
        m.put("recipient_preview", rc.emails().subList(0, Math.min(PREVIEW, rc.emails().size())));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("manager", rc.managerCount());
        counts.put("team_admins", rc.adminCount());
        counts.put("members", rc.memberCount());
        counts.put("extra", rc.extraCount());
        counts.put("dropped_inactive", rc.droppedInactive());
        m.put("recipient_counts", counts);
        m.put("notes", rc.notes());
        m.put("history", history(teamId, 12));
        return m;
    }

    /** Kişi satırı — {@code user_id} anahtarı {@code UserRefWire} kapısından geçer (global olmayana opak kimlik). */
    private static Map<String, Object> person(AppUser u, Boolean selected) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("user_id", u.getId());
        p.put("name", displayName(u));
        p.put("title", u.getTitle());
        p.put("email", emailOf(u));
        p.put("active", Boolean.TRUE.equals(u.getActive()));
        if (selected != null) p.put("selected", selected);
        return p;
    }

    private static String displayName(AppUser u) {
        String d = u.getDisplayName();
        return d == null || d.isBlank() ? u.getUsername() : d.trim();
    }

    private static List<AppUser> sorted(List<AppUser> in) {
        Collator c = Collator.getInstance(Locale.forLanguageTag("tr"));
        c.setStrength(Collator.PRIMARY);
        List<AppUser> out = new ArrayList<>(in);
        out.sort(Comparator.comparing(ExecutiveSummaryTeamService::displayName, Comparator.nullsLast(c)));
        return out;
    }

    private List<Team> teams(Collection<Long> teamIds) {
        List<Team> out = new ArrayList<>();
        try {
            if (teamIds == null) out.addAll(teamRepo.findAll());
            else if (!teamIds.isEmpty()) out.addAll(teamRepo.findAllById(new LinkedHashSet<>(teamIds)));
        } catch (Exception e) {
            log.warn("Takım yönetici özeti: takımlar okunamadı: {}", e.toString());
        }
        Collator c = Collator.getInstance(Locale.forLanguageTag("tr"));
        c.setStrength(Collator.PRIMARY);
        out.removeIf(t -> t == null || t.getId() == null);
        out.sort(Comparator.comparing(Team::getName, Comparator.nullsLast(c)));
        return out;
    }

    /** Takımın gönderim geçmişi — en yeni ay önce. */
    public List<Map<String, Object>> history(Long teamId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (ExecutiveSummaryTeamReport r : reportRepo.findByTeamIdOrderByReportYearDescReportMonthDesc(teamId,
                    PageRequest.of(0, Math.max(1, Math.min(limit, 36))))) {
                out.add(historyRow(r));
            }
        } catch (Exception e) {
            log.debug("Takım yönetici özeti geçmişi okunamadı: {}", e.toString());
        }
        return out;
    }

    static Map<String, Object> historyRow(ExecutiveReportRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("month", r.getReportYear() == null || r.getReportMonth() == null ? null
                : YearMonth.of(r.getReportYear(), r.getReportMonth()).toString());
        m.put("status", r.getStatus());
        m.put("summary_status", r.getSummaryStatus());
        m.put("trigger", r.getTriggerKind());
        m.put("attempts", r.getAttempts());
        m.put("recipients", r.getRecipientCount());
        m.put("chunks", r.getChunkCount());
        m.put("detail", r.getDetail());
        m.put("sent_at", r.getSentAt());
        return m;
    }

    // ── Kaydetme ────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Gövdeyi doğrular ve takımın satırını yazar. Alanlar: {@code enabled}, {@code include_manager},
     * {@code include_team_admins}, {@code extra_emails} (metin), seçilen üyeler ({@code userIds} — çağıran opak/sayısal
     * kimlikleri çözmüş olarak verir; null = gövdede yok, değişmez). Yalnız gövdede olan alanlar değişir. Seçilen üye
     * takımın AKTİF üyesi değilse 400 {@code field=user_ids}.
     *
     * @return değişen alan adları
     */
    public Set<String> save(Long teamId, Map<String, Object> body, List<Long> userIds, int unresolvedUserIds, String actor) {
        Team team = teamRepo.findById(teamId).orElse(null);
        if (team == null) throw new IllegalArgumentException("team");
        Map<String, Object> in = body == null ? Map.of() : body;
        ExecutiveSummaryTeamSettings row = settingsRepo.findById(teamId).orElseGet(() -> {
            ExecutiveSummaryTeamSettings r = new ExecutiveSummaryTeamSettings();
            r.setTeamId(teamId);
            r.setEnabled(false);
            r.setIncludeManager(DEFAULT_INCLUDE_MANAGER);
            r.setIncludeTeamAdmins(DEFAULT_INCLUDE_TEAM_ADMINS);
            return r;
        });
        Settings before = toSettings(teamId, row);
        Set<String> changed = new LinkedHashSet<>();
        if (in.containsKey("enabled")) {
            boolean v = bool(in.get("enabled"));
            if (v != before.enabled()) changed.add("enabled");
            row.setEnabled(v);
        }
        if (in.containsKey("include_manager")) {
            boolean v = bool(in.get("include_manager"));
            if (v != before.includeManager()) changed.add("include_manager");
            row.setIncludeManager(v);
        }
        if (in.containsKey("include_team_admins")) {
            boolean v = bool(in.get("include_team_admins"));
            if (v != before.includeTeamAdmins()) changed.add("include_team_admins");
            row.setIncludeTeamAdmins(v);
        }
        if (in.containsKey("extra_emails")) {
            String raw = in.get("extra_emails") == null ? "" : String.valueOf(in.get("extra_emails")).trim();
            validateEmails(raw);
            List<String> parsed = ExecutiveSummarySettings.parseEmails(raw);
            if (!parsed.equals(before.extraEmails())) changed.add("extra_emails");
            row.setExtraEmails(String.join(", ", parsed));
        }
        if (userIds != null) {
            if (unresolvedUserIds > 0) {
                throw new FieldValidationException("user_ids", Msg.t(
                        "Seçilen kişilerden " + unresolvedUserIds + " tanesi bulunamadı. Sayfayı yenileyip listeden yeniden seçin.",
                        unresolvedUserIds + " of the selected people could not be found. Refresh the page and pick them again from the list."));
            }
            List<Long> unique = new ArrayList<>(new LinkedHashSet<>(userIds));
            if (unique.size() > MAX_SELECTED_USERS) {
                throw new FieldValidationException("user_ids", Msg.t(
                        "En fazla " + MAX_SELECTED_USERS + " üye seçilebilir. Uzun listeler için bir dağıtım listesi adresi ekleyin.",
                        "You can select at most " + MAX_SELECTED_USERS + " members. Add a distribution list address for long lists."));
            }
            Set<Long> members = new java.util.HashSet<>();
            for (AppUser u : snapshot(List.of(teamId)).membersByTeam().getOrDefault(teamId, List.of())) {
                if (Boolean.TRUE.equals(u.getActive())) members.add(u.getId());
            }
            for (Long id : unique) {
                if (!members.contains(id)) {
                    throw new FieldValidationException("user_ids", Msg.t(
                            "Yalnız takımın aktif üyeleri seçilebilir. Takımda olmayan biri için \"Ek adresler\" alanını kullanın.",
                            "Only active members of the team can be selected. Use \"Extra addresses\" for someone outside the team."));
                }
            }
            if (!unique.equals(before.userIds())) changed.add("user_ids");
            row.setRecipientUserIdsCsv(unique.isEmpty() ? null : joinIds(unique));
        }
        if (!changed.isEmpty()) {
            row.setUpdatedAt(ExecutiveSummaryContext.UTC_ISO.format(clock.get()));
            row.setUpdatedBy(actor);
            settingsRepo.save(row);
        }
        return changed;
    }

    static void validateEmails(String raw) {
        if (raw == null || raw.isBlank()) return;
        List<String> bad = new ArrayList<>();
        int ok = 0;
        for (String part : raw.split("[,;\\s]+")) {
            String e = part.trim();
            if (e.isEmpty()) continue;
            if (e.length() > 254 || ExecutiveSummarySettings.parseEmails(e).isEmpty()) {
                bad.add(e.length() > 60 ? e.substring(0, 60) + "…" : e);
            } else {
                ok++;
            }
        }
        if (!bad.isEmpty()) {
            throw new FieldValidationException("extra_emails", Msg.t(
                    "Geçersiz e-posta adresi: " + String.join(", ", bad) + ". Adresleri virgülle ayırarak tam biçimde yazın (ad@alan.com).",
                    "Invalid e-mail address: " + String.join(", ", bad) + ". Write full addresses separated by commas (name@domain.com)."));
        }
        if (ok > MAX_EXTRA_EMAILS) {
            throw new FieldValidationException("extra_emails", Msg.t(
                    "En fazla " + MAX_EXTRA_EMAILS + " ek adres girilebilir. Uzun listeler için bir dağıtım listesi adresi kullanın.",
                    "You can enter at most " + MAX_EXTRA_EMAILS + " extra addresses. Use a distribution list address for long lists."));
        }
    }

    static List<Long> parseIds(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String p : csv.split(",")) {
            String s = p.trim();
            if (s.isEmpty() || s.length() > 19 || !s.chars().allMatch(Character::isDigit)) continue;
            try {
                Long id = Long.valueOf(s);
                if (!out.contains(id)) out.add(id);
            } catch (NumberFormatException ignored) { /* atla */ }
        }
        return out;
    }

    private static String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (id == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    private static boolean bool(Object v) {
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }
}

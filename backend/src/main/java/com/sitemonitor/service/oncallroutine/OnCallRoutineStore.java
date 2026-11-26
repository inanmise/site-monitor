package com.sitemonitor.service.oncallroutine;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Nöbet rutinleri veri erişimi (2026-11-26) — TEK yer, düz SQL. Kurallar: yeni satır {@code INSERT … ON CONFLICT DO NOTHING}
 * (çakışma hedefi yok — PostgreSQL ve H2 PostgreSQL kipinde taşınabilir); var olan satırın her güncellemesi hedefli KOŞULLU
 * {@code UPDATE} (sürüm / durum CAS'ı); okunan nesne asla geri yazılmaz. Kilit sırası: takım → gün → görev (ORDER BY id).
 * İşlem sınırını çağıran ({@code OnCallRoutineService}) çizer; bu sınıf yalnız ifadeleri koşar.
 */
@Component
public class OnCallRoutineStore {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;

    public OnCallRoutineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String ts(Instant i) {
        return i == null ? null : ISO.format(i);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Takım satırı (şablon + ayarlar)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public record TeamRow(Long id, Long teamId, String templateJson, int templateVersion, int tasksActive,
                          String templateUpdatedAt, String templateUpdatedByName, String settingsJson, int settingsVersion,
                          String settingsUpdatedAt, String settingsUpdatedByName, boolean enabled, String enabledSince,
                          String pausedSince) {
        /** Şablon kaydedilmiş VE en az bir etkin görev var. */
        public boolean configured() {
            return templateVersion > 0 && tasksActive > 0;
        }
    }

    private static final String TEAM_COLS = "id, team_id, template_json, template_version, tasks_active, template_updated_at, "
            + "template_updated_by_name, settings_json, settings_version, settings_updated_at, settings_updated_by_name, "
            + "enabled, enabled_since, paused_since";

    private static final RowMapper<TeamRow> TEAM = (rs, n) -> new TeamRow(lng(rs, "id"), lng(rs, "team_id"),
            rs.getString("template_json"), num(rs, "template_version"), num(rs, "tasks_active"),
            rs.getString("template_updated_at"), rs.getString("template_updated_by_name"), rs.getString("settings_json"),
            num(rs, "settings_version"), rs.getString("settings_updated_at"), rs.getString("settings_updated_by_name"),
            rs.getBoolean("enabled"), rs.getString("enabled_since"), rs.getString("paused_since"));

    public TeamRow team(Long teamId) {
        List<TeamRow> l = jdbc.query("SELECT " + TEAM_COLS + " FROM oncall_routine_teams WHERE team_id = ?", TEAM, teamId);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<TeamRow> teams(Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) return List.of();
        return jdbc.query("SELECT " + TEAM_COLS + " FROM oncall_routine_teams WHERE team_id IN (" + marks(teamIds.size()) + ")",
                TEAM, teamIds.toArray());
    }

    /** Şablonu olan takımlar (kurum geneli görüntüleyicinin listesi). */
    public List<TeamRow> configuredTeams(int limit) {
        return jdbc.query("SELECT " + TEAM_COLS + " FROM oncall_routine_teams WHERE template_version > 0 AND tasks_active > 0 "
                + "ORDER BY team_id LIMIT ?", TEAM, limit);
    }

    public List<TeamRow> enabledTeams(int limit) {
        return jdbc.query("SELECT " + TEAM_COLS + " FROM oncall_routine_teams WHERE enabled = TRUE ORDER BY team_id LIMIT ?",
                TEAM, limit);
    }

    public boolean anyEnabled() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT 1 FROM oncall_routine_teams WHERE enabled = TRUE LIMIT 1) x",
                Integer.class);
        return n != null && n > 0;
    }

    public void ensureTeamRow(Long teamId, Instant now) {
        jdbc.update("INSERT INTO oncall_routine_teams (team_id, template_version, tasks_active, settings_version, enabled, created_at) "
                + "VALUES (?, 0, 0, 0, FALSE, ?) ON CONFLICT DO NOTHING", teamId, ts(now));
    }

    public TeamRow lockTeam(Long teamId) {
        List<TeamRow> l = jdbc.query("SELECT " + TEAM_COLS + " FROM oncall_routine_teams WHERE team_id = ? FOR UPDATE", TEAM, teamId);
        return l.isEmpty() ? null : l.get(0);
    }

    public int saveTemplate(Long teamId, int expectedVersion, String json, int tasksActive, String at, Long byId, String byName,
                            boolean enabled, String enabledSince, String pausedSince) {
        return jdbc.update("UPDATE oncall_routine_teams SET template_json = ?, template_version = template_version + 1, "
                + "tasks_active = ?, template_updated_at = ?, template_updated_by_id = ?, template_updated_by_name = ?, "
                + "enabled = ?, enabled_since = ?, paused_since = ? WHERE team_id = ? AND template_version = ?",
                json, tasksActive, at, byId, byName, enabled, enabledSince, pausedSince, teamId, expectedVersion);
    }

    public int saveSettings(Long teamId, int expectedVersion, String json, String at, String byName, boolean enabled,
                            String enabledSince, String pausedSince) {
        return jdbc.update("UPDATE oncall_routine_teams SET settings_json = ?, settings_version = settings_version + 1, "
                + "settings_updated_at = ?, settings_updated_by_name = ?, enabled = ?, enabled_since = ?, paused_since = ? "
                + "WHERE team_id = ? AND settings_version = ?",
                json, at, byName, enabled, enabledSince, pausedSince, teamId, expectedVersion);
    }

    // ── Dönem kapanış işareti (kalıcı) ───────────────────────────────────────────────────────────

    private static String closedThroughColumn(String kind) {
        return OnCallRoutineCodes.KIND_MONTH.equals(kind) ? "month_closed_through" : "week_closed_through";
    }

    /** Takımın kapatılmış son haftası / ayı (yoksa null). */
    public String periodClosedThrough(Long teamId, String kind) {
        List<String> l = jdbc.query("SELECT " + closedThroughColumn(kind) + " FROM oncall_routine_teams WHERE team_id = ?",
                (rs, n) -> rs.getString(1), teamId);
        return l.isEmpty() ? null : l.get(0);
    }

    /** İşareti ileri alır (tekdüze: yalnız daha büyük anahtar yazılır). 1 = yazıldı. */
    public int markPeriodClosed(Long teamId, String kind, String key) {
        String col = closedThroughColumn(kind);
        return jdbc.update("UPDATE oncall_routine_teams SET " + col + " = ? WHERE team_id = ? AND (" + col + " IS NULL OR "
                + col + " < ?)", key, teamId, key);
    }

    // ── Şablon sürümleri ─────────────────────────────────────────────────────────────────────────

    public record VersionRow(int version, String templateJson, String summaryJson, String source, String savedAt,
                             String savedByName) { }

    public void insertTemplateVersion(Long teamId, int version, String json, String summaryJson, String source, String at,
                                      String byName) {
        jdbc.update("INSERT INTO oncall_routine_template_versions (team_id, version, template_json, summary_json, source, saved_at, "
                + "saved_by_name) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                teamId, version, json, fit(summaryJson, 2000), source, at, fit(byName, 200));
    }

    /** En yeni {@code keep} sürüm dışındakileri siler (tek toplu DELETE). */
    public int pruneTemplateVersions(Long teamId, int keep) {
        Integer cut = jdbc.query("SELECT version FROM oncall_routine_template_versions WHERE team_id = ? "
                        + "ORDER BY version DESC LIMIT 1 OFFSET ?",
                rs -> rs.next() ? rs.getInt(1) : null, teamId, keep - 1);
        if (cut == null) return 0;
        return jdbc.update("DELETE FROM oncall_routine_template_versions WHERE team_id = ? AND version < ?", teamId, cut);
    }

    private static final RowMapper<VersionRow> VERSION = (rs, n) -> new VersionRow(num(rs, "version"),
            rs.getString("template_json"), rs.getString("summary_json"), rs.getString("source"), rs.getString("saved_at"),
            rs.getString("saved_by_name"));

    /** O anda geçerli sürüm: kayıt anı ≤ {@code atStamp} olan en yeni satır. */
    public VersionRow templateAt(Long teamId, String atStamp) {
        List<VersionRow> l = jdbc.query("SELECT version, template_json, summary_json, source, saved_at, saved_by_name "
                + "FROM oncall_routine_template_versions WHERE team_id = ? AND saved_at <= ? "
                + "ORDER BY saved_at DESC, version DESC LIMIT 1", VERSION, teamId, atStamp);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<VersionRow> versions(Long teamId, int limit) {
        return jdbc.query("SELECT version, NULL AS template_json, summary_json, source, saved_at, saved_by_name "
                + "FROM oncall_routine_template_versions WHERE team_id = ? ORDER BY version DESC LIMIT ?", VERSION, teamId, limit);
    }

    public VersionRow version(Long teamId, int version) {
        List<VersionRow> l = jdbc.query("SELECT version, template_json, summary_json, source, saved_at, saved_by_name "
                + "FROM oncall_routine_template_versions WHERE team_id = ? AND version = ?", VERSION, teamId, version);
        return l.isEmpty() ? null : l.get(0);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Gün satırı
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public record DayRow(Long id, Long teamId, String day, String weekKey, String handoverKey, String status,
                         Long responsibleUserId, String responsibleName, String responsibleSource, Integer requiredCount,
                         Integer doneCount, Integer skippedCount, Integer pendingCount, Integer problemsOpened,
                         Long completedById, String completedByName, String completedAt, boolean completedLate,
                         int completionSeq, int notifiedSeq, String note, int noteVersion, String noteByName, String noteAt,
                         Integer templateVersion, String closedAt, int version) { }

    private static final String DAY_COLS = "id, team_id, routine_day, week_key, handover_key, status, responsible_user_id, "
            + "responsible_name, responsible_source, required_count, done_count, skipped_count, pending_count, problems_opened, "
            + "completed_by_id, completed_by_name, completed_at, completed_late, completion_seq, notified_seq, note, note_version, "
            + "note_by_name, note_at, template_version, closed_at, version";

    private static final RowMapper<DayRow> DAY = (rs, n) -> new DayRow(lng(rs, "id"), lng(rs, "team_id"),
            rs.getString("routine_day"), rs.getString("week_key"), rs.getString("handover_key"), rs.getString("status"),
            lng(rs, "responsible_user_id"), rs.getString("responsible_name"), rs.getString("responsible_source"),
            intOrNull(rs, "required_count"), intOrNull(rs, "done_count"), intOrNull(rs, "skipped_count"),
            intOrNull(rs, "pending_count"), intOrNull(rs, "problems_opened"), lng(rs, "completed_by_id"),
            rs.getString("completed_by_name"), rs.getString("completed_at"), rs.getBoolean("completed_late"),
            num(rs, "completion_seq"), num(rs, "notified_seq"), rs.getString("note"), num(rs, "note_version"),
            rs.getString("note_by_name"), rs.getString("note_at"), intOrNull(rs, "template_version"),
            rs.getString("closed_at"), num(rs, "version"));

    public DayRow day(Long teamId, String day) {
        List<DayRow> l = jdbc.query("SELECT " + DAY_COLS + " FROM oncall_routine_days WHERE team_id = ? AND routine_day = ?",
                DAY, teamId, day);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<DayRow> days(Long teamId, String from, String to) {
        return jdbc.query("SELECT " + DAY_COLS + " FROM oncall_routine_days WHERE team_id = ? AND routine_day >= ? "
                + "AND routine_day <= ? ORDER BY routine_day", DAY, teamId, from, to);
    }

    public List<DayRow> daysOfTeamsOn(Collection<Long> teamIds, String day) {
        if (teamIds == null || teamIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(teamIds);
        args.add(day);
        return jdbc.query("SELECT " + DAY_COLS + " FROM oncall_routine_days WHERE team_id IN (" + marks(teamIds.size())
                + ") AND routine_day = ?", DAY, args.toArray());
    }

    public List<DayRow> daysOfTeamsBetween(Collection<Long> teamIds, String from, String to) {
        if (teamIds == null || teamIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(teamIds);
        args.add(from);
        args.add(to);
        return jdbc.query("SELECT " + DAY_COLS + " FROM oncall_routine_days WHERE team_id IN (" + marks(teamIds.size())
                + ") AND routine_day >= ? AND routine_day <= ?", DAY, args.toArray());
    }

    /** Gün satırı yoksa ekler (durum OPEN, sorumlu anlık görüntüsüyle). */
    public void ensureDay(Long teamId, String day, String weekKey, String handoverKey, Long respId, String respName,
                          String respSource, Instant now) {
        jdbc.update("INSERT INTO oncall_routine_days (team_id, routine_day, week_key, handover_key, status, responsible_user_id, "
                + "responsible_name, responsible_source, completion_seq, notified_seq, note_version, version, completed_late, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?, 0, 0, 0, 0, FALSE, ?, ?) ON CONFLICT DO NOTHING",
                teamId, day, weekKey, handoverKey, respId, fit(respName, 200), respSource, ts(now), ts(now));
    }

    public DayRow lockDay(Long teamId, String day) {
        List<DayRow> l = jdbc.query("SELECT " + DAY_COLS + " FROM oncall_routine_days WHERE team_id = ? AND routine_day = ? FOR UPDATE",
                DAY, teamId, day);
        return l.isEmpty() ? null : l.get(0);
    }

    public record Counts(int required, int done, int skipped, int pending, int problemsOpened) { }

    public int completeDay(Long id, int expectedVersion, String status, Counts c, Long respId, String respName, String respSource,
                           Long byId, String byName, String at, boolean late, Integer templateVersion) {
        return jdbc.update("UPDATE oncall_routine_days SET status = ?, required_count = ?, done_count = ?, skipped_count = ?, "
                + "pending_count = ?, problems_opened = ?, responsible_user_id = ?, responsible_name = ?, responsible_source = ?, "
                + "completed_by_id = ?, completed_by_name = ?, completed_at = ?, completed_late = ?, "
                + "completion_seq = completion_seq + 1, template_version = ?, version = version + 1, updated_at = ? "
                + "WHERE id = ? AND version = ? AND status NOT IN ('COMPLETED', 'COMPLETED_PARTIAL')",
                status, c.required(), c.done(), c.skipped(), c.pending(), c.problemsOpened(), respId, fit(respName, 200),
                respSource, byId, fit(byName, 200), at, late, templateVersion, at, id, expectedVersion);
    }

    public int reopenDay(Long id, int expectedVersion, String at) {
        return jdbc.update("UPDATE oncall_routine_days SET status = 'OPEN', version = version + 1, updated_at = ? "
                + "WHERE id = ? AND version = ? AND status IN ('COMPLETED', 'COMPLETED_PARTIAL')", at, id, expectedVersion);
    }

    /**
     * İşin kendiliğinden tamamlaması (zorunlular çözülmüş, gün tamamlanmamış): tamamlayan kimliksiz ({@code byName}), sıra
     * +1 ve bildirim sırası AYNI değere çekilir (tamamlama postası gitmez); yalnız hâlâ OPEN ve okunan sürümdeyse.
     */
    public int autoCompleteDay(Long id, int expectedVersion, String status, Counts c, Long respId, String respName,
                               String respSource, String byName, String at, boolean late, Integer templateVersion) {
        return jdbc.update("UPDATE oncall_routine_days SET status = ?, required_count = ?, done_count = ?, skipped_count = ?, "
                + "pending_count = ?, problems_opened = ?, responsible_user_id = ?, responsible_name = ?, responsible_source = ?, "
                + "completed_by_id = NULL, completed_by_name = ?, completed_at = ?, completed_late = ?, "
                + "completion_seq = completion_seq + 1, notified_seq = completion_seq + 1, template_version = ?, closed_at = ?, "
                + "version = version + 1, updated_at = ? WHERE id = ? AND version = ? AND status = 'OPEN'",
                status, c.required(), c.done(), c.skipped(), c.pending(), c.problemsOpened(), respId, fit(respName, 200),
                respSource, fit(byName, 200), at, late, templateVersion, at, at, id, expectedVersion);
    }

    /** Kapanış: yalnız hâlâ OPEN ve okunan sürümdeyse. */
    public int closeDay(Long id, int expectedVersion, String status, Counts c, Long respId, String respName, String respSource,
                        Integer templateVersion, String at) {
        return jdbc.update("UPDATE oncall_routine_days SET status = ?, required_count = ?, done_count = ?, skipped_count = ?, "
                + "pending_count = ?, problems_opened = ?, responsible_user_id = ?, responsible_name = ?, responsible_source = ?, "
                + "template_version = ?, closed_at = ?, version = version + 1, updated_at = ? "
                + "WHERE id = ? AND version = ? AND status = 'OPEN'",
                status, c.required(), c.done(), c.skipped(), c.pending(), c.problemsOpened(), respId, fit(respName, 200),
                respSource, templateVersion, at, at, id, expectedVersion);
    }

    public int saveDayNote(Long id, int expectedNoteVersion, String note, String byName, String at) {
        return jdbc.update("UPDATE oncall_routine_days SET note = ?, note_version = note_version + 1, note_by_name = ?, "
                + "note_at = ?, updated_at = ? WHERE id = ? AND note_version = ?", note, fit(byName, 200), at, at, id,
                expectedNoteVersion);
    }

    /** Tamamlama çağrısındaki not (sürümsüz — gün kilidi altında). */
    public int replaceDayNote(Long id, String note, String byName, String at) {
        return jdbc.update("UPDATE oncall_routine_days SET note = ?, note_version = note_version + 1, note_by_name = ?, "
                + "note_at = ? WHERE id = ?", note, fit(byName, 200), at, id);
    }

    public int markNotified(Long id, int seq) {
        return jdbc.update("UPDATE oncall_routine_days SET notified_seq = ? WHERE id = ? AND notified_seq < ?", seq, id, seq);
    }

    /** Bildirilmemiş tamamlamalar (son 24 saat; tamamlanma zamanı indeksli). */
    public List<DayRow> pendingCompletions(String since, int limit) {
        return jdbc.query("SELECT " + DAY_COLS + " FROM oncall_routine_days WHERE completed_at >= ? "
                + "AND completion_seq > notified_seq ORDER BY completed_at LIMIT ?", DAY, since, limit);
    }

    public boolean existsPendingNotice(String since) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT 1 FROM oncall_routine_days WHERE completed_at >= ? "
                + "AND completion_seq > notified_seq LIMIT 1) x", Integer.class, since);
        return n != null && n > 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Görev satırları
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private static final String ITEM_COLS = "id, team_id, task_id, section_id, period_kind, period_key, due_day, title, "
            + "section_title, description, links_json, signals, required, frequency, window_from, window_to, state, state_by_id, "
            + "state_by_name, state_at, skip_reason, note, note_version, note_by_name, note_at, template_version, version";

    private static final RowMapper<OnCallRoutinePlan.Row> ITEM = (rs, n) -> new OnCallRoutinePlan.Row(lng(rs, "id"),
            rs.getString("task_id"), rs.getString("section_id"), rs.getString("period_kind"), rs.getString("period_key"),
            rs.getString("due_day"), rs.getString("title"), rs.getString("section_title"), rs.getString("description"),
            rs.getString("links_json"), rs.getString("signals"), rs.getBoolean("required"), rs.getString("frequency"),
            rs.getString("window_from"), rs.getString("window_to"), rs.getString("state"), lng(rs, "state_by_id"),
            rs.getString("state_by_name"), rs.getString("state_at"), rs.getString("skip_reason"), rs.getString("note"),
            num(rs, "note_version"), rs.getString("note_by_name"), rs.getString("note_at"), intOrNull(rs, "template_version"),
            num(rs, "version"));

    /** Takımın verilen dönemlerdeki satırları. */
    public List<OnCallRoutinePlan.Row> items(Long teamId, List<String[]> periods) {
        Map<Long, List<OnCallRoutinePlan.Row>> m = itemsOfTeams(List.of(teamId), periods);
        return m.getOrDefault(teamId, List.of());
    }

    /** Takımların verilen dönemlerdeki satırları (tek sorgu). */
    public Map<Long, List<OnCallRoutinePlan.Row>> itemsOfTeams(Collection<Long> teamIds, List<String[]> periods) {
        Map<Long, List<OnCallRoutinePlan.Row>> out = new HashMap<>();
        if (teamIds == null || teamIds.isEmpty() || periods == null || periods.isEmpty()) return out;
        StringJoiner or = new StringJoiner(" OR ");
        List<Object> args = new ArrayList<>(teamIds);
        for (String[] p : periods) {
            or.add("(period_kind = ? AND period_key = ?)");
            args.add(p[0]);
            args.add(p[1]);
        }
        jdbc.query("SELECT " + ITEM_COLS + " FROM oncall_routine_items WHERE team_id IN (" + marks(teamIds.size()) + ") AND ("
                + or + ") ORDER BY id", (ResultSet rs) -> {
                    Long team = lng(rs, "team_id");
                    out.computeIfAbsent(team, k -> new ArrayList<>()).add(ITEM.mapRow(rs, 0));
                }, args.toArray());
        return out;
    }

    /** Takımın tek türdeki dönem satırları (kapanış). */
    public List<OnCallRoutinePlan.Row> itemsOfPeriod(Long teamId, String kind, String key) {
        return jdbc.query("SELECT " + ITEM_COLS + " FROM oncall_routine_items WHERE team_id = ? AND period_kind = ? "
                + "AND period_key = ? ORDER BY id", ITEM, teamId, kind, key);
    }

    /** Görev satırı yoksa ekler (anlık görüntü; durum verilen). 1 = eklendi. */
    public int insertItemIfAbsent(Long teamId, OnCallRoutinePlan.Item i, String state, String source, Integer templateVersion,
                                  Instant now) {
        return jdbc.update("INSERT INTO oncall_routine_items (team_id, task_id, section_id, period_kind, period_key, due_day, "
                        + "title, section_title, description, links_json, signals, required, frequency, window_from, window_to, "
                        + "state, note_version, source, template_version, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 0, ?, ?) ON CONFLICT DO NOTHING",
                teamId, i.taskId(), i.sectionId(), i.periodKind(), i.periodKey(), i.dueDay().toString(), fit(i.title(), 200),
                fit(i.sectionTitle(), 200), i.description(), OnCallRoutineTemplate.linksJson(i.links()),
                OnCallRoutinePlan.csv(i.signals()), i.requiredFlag(), i.frequency(), i.windowFrom(), i.windowTo(), state, source,
                templateVersion, ts(now), ts(now));
    }

    public OnCallRoutinePlan.Row lockItem(Long teamId, String kind, String key, String taskId) {
        List<OnCallRoutinePlan.Row> l = jdbc.query("SELECT " + ITEM_COLS + " FROM oncall_routine_items WHERE team_id = ? "
                + "AND task_id = ? AND period_kind = ? AND period_key = ? FOR UPDATE", ITEM, teamId, taskId, kind, key);
        return l.isEmpty() ? null : l.get(0);
    }

    public OnCallRoutinePlan.Row item(Long teamId, String kind, String key, String taskId) {
        List<OnCallRoutinePlan.Row> l = jdbc.query("SELECT " + ITEM_COLS + " FROM oncall_routine_items WHERE team_id = ? "
                + "AND task_id = ? AND period_kind = ? AND period_key = ?", ITEM, teamId, taskId, kind, key);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Satırları kimlik sırasıyla kilitler (gün tamamlama — kilitlenme yok: herkes aynı sırayla). */
    public List<OnCallRoutinePlan.Row> lockItemsById(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return jdbc.query("SELECT " + ITEM_COLS + " FROM oncall_routine_items WHERE id IN (" + marks(ids.size())
                + ") ORDER BY id FOR UPDATE", ITEM, ids.toArray());
    }

    public int setItemState(Long id, int expectedVersion, String state, Long byId, String byName, String at, String skipReason) {
        return jdbc.update("UPDATE oncall_routine_items SET state = ?, state_by_id = ?, state_by_name = ?, state_at = ?, "
                + "skip_reason = ?, version = version + 1, updated_at = ? WHERE id = ? AND version = ?",
                state, byId, fit(byName, 200), at, fit(skipReason, 300), at, id, expectedVersion);
    }

    public int setItemNote(Long id, int expectedNoteVersion, String note, String byName, String at) {
        return jdbc.update("UPDATE oncall_routine_items SET note = ?, note_version = note_version + 1, note_by_name = ?, "
                + "note_at = ?, version = version + 1, updated_at = ? WHERE id = ? AND note_version = ?",
                note, fit(byName, 200), at, at, id, expectedNoteVersion);
    }

    /** Dönemin hâlâ PENDING satırlarını MISSED yapar (kapanış). */
    public int missPending(Long teamId, String kind, String key, String at) {
        return jdbc.update("UPDATE oncall_routine_items SET state = 'MISSED', version = version + 1, updated_at = ? "
                + "WHERE team_id = ? AND period_kind = ? AND period_key = ? AND state = 'PENDING'", at, teamId, kind, key);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Sorunlar + görseller
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public record ProblemRow(Long id, Long teamId, String day, Long itemId, String taskId, String taskTitle, String sectionTitle,
                             String severity, String status, String note, String resolutionNote, Long incidentRecordId,
                             Long createdById, String createdByName, String createdAt, String updatedByName, String updatedAt,
                             Long resolvedById, String resolvedByName, String resolvedAt, String resolvedDay, int version) { }

    private static final String PROBLEM_COLS = "id, team_id, routine_day, item_id, task_id, task_title, section_title, severity, "
            + "status, note, resolution_note, incident_record_id, created_by_id, created_by_name, created_at, updated_by_name, "
            + "updated_at, resolved_by_id, resolved_by_name, resolved_at, resolved_day, version";

    private static final RowMapper<ProblemRow> PROBLEM = (rs, n) -> new ProblemRow(lng(rs, "id"), lng(rs, "team_id"),
            rs.getString("routine_day"), lng(rs, "item_id"), rs.getString("task_id"), rs.getString("task_title"),
            rs.getString("section_title"), rs.getString("severity"), rs.getString("status"), rs.getString("note"),
            rs.getString("resolution_note"), lng(rs, "incident_record_id"), lng(rs, "created_by_id"),
            rs.getString("created_by_name"), rs.getString("created_at"), rs.getString("updated_by_name"),
            rs.getString("updated_at"), lng(rs, "resolved_by_id"), rs.getString("resolved_by_name"), rs.getString("resolved_at"),
            rs.getString("resolved_day"), num(rs, "version"));

    public Long insertProblem(Long teamId, String day, Long itemId, String taskId, String taskTitle, String sectionTitle,
                              String severity, String note, Long byId, String byName, String at) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO oncall_routine_problems (team_id, routine_day, item_id, task_id, "
                    + "task_title, section_title, severity, status, note, created_by_id, created_by_name, created_at, version) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, 0)", new String[] {"id"});
            ps.setLong(1, teamId);
            ps.setString(2, day);
            ps.setObject(3, itemId);
            ps.setString(4, taskId);
            ps.setString(5, fit(taskTitle, 200));
            ps.setString(6, fit(sectionTitle, 200));
            ps.setString(7, severity);
            ps.setString(8, note);
            ps.setObject(9, byId);
            ps.setString(10, fit(byName, 200));
            ps.setString(11, at);
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? null : key.longValue();
    }

    public ProblemRow problem(Long id) {
        List<ProblemRow> l = jdbc.query("SELECT " + PROBLEM_COLS + " FROM oncall_routine_problems WHERE id = ?", PROBLEM, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public ProblemRow lockProblem(Long id) {
        List<ProblemRow> l = jdbc.query("SELECT " + PROBLEM_COLS + " FROM oncall_routine_problems WHERE id = ? FOR UPDATE", PROBLEM, id);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Açık sorunlar: önce YÜKSEK, sonra en yeni. */
    public List<ProblemRow> openProblems(Long teamId, int limit) {
        return jdbc.query("SELECT " + PROBLEM_COLS + " FROM oncall_routine_problems WHERE team_id = ? AND status = 'OPEN' "
                + "ORDER BY CASE severity WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, id DESC LIMIT ?",
                PROBLEM, teamId, limit);
    }

    public List<ProblemRow> problems(Long teamId, String status, int offset, int limit) {
        if (status == null) {
            return jdbc.query("SELECT " + PROBLEM_COLS + " FROM oncall_routine_problems WHERE team_id = ? "
                    + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", PROBLEM, teamId, limit, offset);
        }
        return jdbc.query("SELECT " + PROBLEM_COLS + " FROM oncall_routine_problems WHERE team_id = ? AND status = ? "
                + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", PROBLEM, teamId, status, limit, offset);
    }

    public int countProblems(Long teamId, String status) {
        Integer n = status == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM oncall_routine_problems WHERE team_id = ?", Integer.class, teamId)
                : jdbc.queryForObject("SELECT COUNT(*) FROM oncall_routine_problems WHERE team_id = ? AND status = ?",
                        Integer.class, teamId, status);
        return n == null ? 0 : n;
    }

    /** Takım → açık sorun sayısı (tek gruplu sorgu). */
    public Map<Long, Integer> openProblemCounts(Collection<Long> teamIds) {
        Map<Long, Integer> out = new HashMap<>();
        if (teamIds == null || teamIds.isEmpty()) return out;
        jdbc.query("SELECT team_id, COUNT(*) AS n FROM oncall_routine_problems WHERE status = 'OPEN' AND team_id IN ("
                + marks(teamIds.size()) + ") GROUP BY team_id", (ResultSet rs) -> {
                    out.put(lng(rs, "team_id"), rs.getInt("n"));
                }, teamIds.toArray());
        return out;
    }

    /** Görev satırı → açık sorun sayısı. */
    public Map<Long, Integer> openProblemsByItem(Long teamId) {
        Map<Long, Integer> out = new HashMap<>();
        jdbc.query("SELECT item_id, COUNT(*) AS n FROM oncall_routine_problems WHERE team_id = ? AND status = 'OPEN' "
                + "AND item_id IS NOT NULL GROUP BY item_id", (ResultSet rs) -> {
                    out.put(lng(rs, "item_id"), rs.getInt("n"));
                }, teamId);
        return out;
    }

    public List<ProblemRow> problemsOnDays(Long teamId, String from, String to, int limit) {
        return jdbc.query("SELECT " + PROBLEM_COLS + " FROM oncall_routine_problems WHERE team_id = ? AND routine_day >= ? "
                + "AND routine_day <= ? ORDER BY id DESC LIMIT ?", PROBLEM, teamId, from, to, limit);
    }

    public int countProblemsOnDay(Long teamId, String day) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM oncall_routine_problems WHERE team_id = ? AND routine_day = ?",
                Integer.class, teamId, day);
        return n == null ? 0 : n;
    }

    public int updateProblem(Long id, int expectedVersion, String severity, String note, String status, String resolutionNote,
                             String byName, String at, Long resolvedById, String resolvedByName, String resolvedAt,
                             String resolvedDay) {
        return jdbc.update("UPDATE oncall_routine_problems SET severity = ?, note = ?, status = ?, resolution_note = ?, "
                + "updated_by_name = ?, updated_at = ?, resolved_by_id = ?, resolved_by_name = ?, resolved_at = ?, resolved_day = ?, "
                + "version = version + 1 WHERE id = ? AND version = ?", severity, note, status, resolutionNote, fit(byName, 200),
                at, resolvedById, fit(resolvedByName, 200), resolvedAt, resolvedDay, id, expectedVersion);
    }

    public int linkIncident(Long id, int expectedVersion, Long incidentId, String byName, String at) {
        return jdbc.update("UPDATE oncall_routine_problems SET incident_record_id = ?, updated_by_name = ?, updated_at = ?, "
                + "version = version + 1 WHERE id = ? AND version = ?", incidentId, fit(byName, 200), at, id, expectedVersion);
    }

    public int deleteProblem(Long id) {
        jdbc.update("DELETE FROM oncall_routine_problem_images WHERE problem_id = ?", id);
        return jdbc.update("DELETE FROM oncall_routine_problems WHERE id = ?", id);
    }

    public record ImageMeta(Long id, Long problemId, Long teamId, String contentType, String fileName, long sizeBytes,
                            int width, int height, String createdAt) { }

    private static final RowMapper<ImageMeta> IMAGE = (rs, n) -> new ImageMeta(lng(rs, "id"), lng(rs, "problem_id"),
            lng(rs, "team_id"), rs.getString("content_type"), rs.getString("file_name"), rs.getLong("size_bytes"),
            rs.getInt("width"), rs.getInt("height"), rs.getString("created_at"));

    private static final String IMAGE_COLS = "id, problem_id, team_id, content_type, file_name, size_bytes, width, height, created_at";

    public Map<Long, List<ImageMeta>> imagesOf(Collection<Long> problemIds) {
        Map<Long, List<ImageMeta>> out = new LinkedHashMap<>();
        if (problemIds == null || problemIds.isEmpty()) return out;
        jdbc.query("SELECT " + IMAGE_COLS + " FROM oncall_routine_problem_images WHERE problem_id IN (" + marks(problemIds.size())
                + ") ORDER BY id", (ResultSet rs) -> {
                    ImageMeta m = IMAGE.mapRow(rs, 0);
                    out.computeIfAbsent(m.problemId(), k -> new ArrayList<>()).add(m);
                }, problemIds.toArray());
        return out;
    }

    public ImageMeta imageMeta(Long imageId) {
        List<ImageMeta> l = jdbc.query("SELECT " + IMAGE_COLS + " FROM oncall_routine_problem_images WHERE id = ?", IMAGE, imageId);
        return l.isEmpty() ? null : l.get(0);
    }

    public byte[] imageBytes(Long imageId) {
        List<byte[]> l = jdbc.query("SELECT data FROM oncall_routine_problem_images WHERE id = ?", (rs, n) -> rs.getBytes(1), imageId);
        return l.isEmpty() ? null : l.get(0);
    }

    public int countImages(Long problemId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM oncall_routine_problem_images WHERE problem_id = ?", Integer.class,
                problemId);
        return n == null ? 0 : n;
    }

    public long teamImageBytes(Long teamId) {
        Long n = jdbc.queryForObject("SELECT COALESCE(SUM(size_bytes), 0) FROM oncall_routine_problem_images WHERE team_id = ?",
                Long.class, teamId);
        return n == null ? 0 : n;
    }

    public Long insertImage(Long teamId, Long problemId, String contentType, String fileName, long size, int width, int height,
                            byte[] data, String byName, String at) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO oncall_routine_problem_images (team_id, problem_id, content_type, "
                    + "file_name, size_bytes, width, height, data, created_by_name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setLong(1, teamId);
            ps.setLong(2, problemId);
            ps.setString(3, contentType);
            ps.setString(4, fit(fileName, 200));
            ps.setLong(5, size);
            ps.setInt(6, width);
            ps.setInt(7, height);
            ps.setBytes(8, data);
            ps.setString(9, fit(byName, 200));
            ps.setString(10, at);
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? null : key.longValue();
    }

    public int deleteImage(Long imageId, Long problemId) {
        return jdbc.update("DELETE FROM oncall_routine_problem_images WHERE id = ? AND problem_id = ?", imageId, problemId);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Devralma
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public record AssignmentRow(Long id, Long teamId, String scope, String scopeKey, Long userId, String userName, String note,
                                String createdByName, String createdAt) { }

    private static final RowMapper<AssignmentRow> ASSIGNMENT = (rs, n) -> new AssignmentRow(lng(rs, "id"), lng(rs, "team_id"),
            rs.getString("scope"), rs.getString("scope_key"), lng(rs, "user_id"), rs.getString("user_name"), rs.getString("note"),
            rs.getString("created_by_name"), rs.getString("created_at"));

    private static final String ASSIGN_COLS = "id, team_id, scope, scope_key, user_id, user_name, note, created_by_name, created_at";

    public AssignmentRow assignment(Long teamId, String scope, String key) {
        List<AssignmentRow> l = jdbc.query("SELECT " + ASSIGN_COLS + " FROM oncall_routine_assignments WHERE team_id = ? "
                + "AND scope = ? AND scope_key = ?", ASSIGNMENT, teamId, scope, key);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Takımların verilen anahtarlardaki devralmaları (tek sorgu). */
    public List<AssignmentRow> assignmentsOf(Collection<Long> teamIds, Collection<String> keys) {
        if (teamIds == null || teamIds.isEmpty() || keys == null || keys.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(teamIds);
        args.addAll(keys);
        return jdbc.query("SELECT " + ASSIGN_COLS + " FROM oncall_routine_assignments WHERE team_id IN (" + marks(teamIds.size())
                + ") AND scope_key IN (" + marks(keys.size()) + ")", ASSIGNMENT, args.toArray());
    }

    public List<AssignmentRow> assignmentsBetween(Long teamId, String from, String to) {
        return jdbc.query("SELECT " + ASSIGN_COLS + " FROM oncall_routine_assignments WHERE team_id = ? AND scope_key >= ? "
                + "AND scope_key <= ?", ASSIGNMENT, teamId, from, to);
    }

    /** Ekle; varsa yerine yaz (koşullu UPDATE). */
    public void upsertAssignment(Long teamId, String scope, String key, Long userId, String userName, String note, Long byId,
                                 String byName, String at) {
        int n = jdbc.update("INSERT INTO oncall_routine_assignments (team_id, scope, scope_key, user_id, user_name, note, "
                + "created_by_id, created_by_name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                teamId, scope, key, userId, fit(userName, 200), fit(note, 200), byId, fit(byName, 200), at);
        if (n == 0) {
            jdbc.update("UPDATE oncall_routine_assignments SET user_id = ?, user_name = ?, note = ?, created_by_id = ?, "
                    + "created_by_name = ?, created_at = ? WHERE team_id = ? AND scope = ? AND scope_key = ?",
                    userId, fit(userName, 200), fit(note, 200), byId, fit(byName, 200), at, teamId, scope, key);
        }
    }

    public int deleteAssignment(Long teamId, String scope, String key) {
        return jdbc.update("DELETE FROM oncall_routine_assignments WHERE team_id = ? AND scope = ? AND scope_key = ?",
                teamId, scope, key);
    }

    public int deleteAssignmentsOfUser(Long userId) {
        return jdbc.update("DELETE FROM oncall_routine_assignments WHERE user_id = ?", userId);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Devir
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public record HandoverRow(Long id, Long teamId, String periodKey, String periodStart, String periodEnd, String note,
                              int noteVersion, String noteByName, String noteAt, String summaryJson, String closedAt,
                              Long ackById, String ackByName, String ackAt) { }

    private static final RowMapper<HandoverRow> HANDOVER = (rs, n) -> new HandoverRow(lng(rs, "id"), lng(rs, "team_id"),
            rs.getString("period_key"), rs.getString("period_start"), rs.getString("period_end"), rs.getString("note"),
            num(rs, "note_version"), rs.getString("note_by_name"), rs.getString("note_at"), rs.getString("summary_json"),
            rs.getString("closed_at"), lng(rs, "ack_by_id"), rs.getString("ack_by_name"), rs.getString("ack_at"));

    private static final String HANDOVER_COLS = "id, team_id, period_key, period_start, period_end, note, note_version, "
            + "note_by_name, note_at, summary_json, closed_at, ack_by_id, ack_by_name, ack_at";

    public HandoverRow handover(Long teamId, String periodKey) {
        List<HandoverRow> l = jdbc.query("SELECT " + HANDOVER_COLS + " FROM oncall_routine_handovers WHERE team_id = ? "
                + "AND period_key = ?", HANDOVER, teamId, periodKey);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<HandoverRow> handoversOf(Collection<Long> teamIds, Collection<String> keys) {
        if (teamIds == null || teamIds.isEmpty() || keys == null || keys.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(teamIds);
        args.addAll(keys);
        return jdbc.query("SELECT " + HANDOVER_COLS + " FROM oncall_routine_handovers WHERE team_id IN (" + marks(teamIds.size())
                + ") AND period_key IN (" + marks(keys.size()) + ")", HANDOVER, args.toArray());
    }

    public void ensureHandover(Long teamId, String key, String start, String end, Instant now) {
        jdbc.update("INSERT INTO oncall_routine_handovers (team_id, period_key, period_start, period_end, note_version, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, 0, ?, ?) ON CONFLICT DO NOTHING", teamId, key, start, end, ts(now), ts(now));
    }

    public int saveHandoverNote(Long teamId, String key, int expectedNoteVersion, String note, String byName, String at) {
        return jdbc.update("UPDATE oncall_routine_handovers SET note = ?, note_version = note_version + 1, note_by_name = ?, "
                + "note_at = ?, updated_at = ? WHERE team_id = ? AND period_key = ? AND note_version = ?",
                note, fit(byName, 200), at, at, teamId, key, expectedNoteVersion);
    }

    /** Özet bir kez yazılır (yarışta ikinci yazım 0 satır). */
    public int storeHandoverSummary(Long teamId, String key, String json, String at) {
        return jdbc.update("UPDATE oncall_routine_handovers SET summary_json = ?, closed_at = ?, updated_at = ? "
                + "WHERE team_id = ? AND period_key = ? AND summary_json IS NULL", json, at, at, teamId, key);
    }

    public int ackHandover(Long teamId, String key, Long byId, String byName, String at) {
        return jdbc.update("UPDATE oncall_routine_handovers SET ack_by_id = ?, ack_by_name = ?, ack_at = ?, updated_at = ? "
                + "WHERE team_id = ? AND period_key = ? AND ack_at IS NULL", byId, fit(byName, 200), at, at, teamId, key);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Alan geçmişi
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public record EventRow(Long id, String day, String kind, String taskId, Long problemId, String actorName, String at,
                           String detailJson) { }

    public void insertEvent(Long teamId, String day, String kind, String taskId, Long problemId, Long actorId, String actorName,
                            String at, String detailJson) {
        jdbc.update("INSERT INTO oncall_routine_events (team_id, routine_day, kind, task_id, problem_id, actor_id, actor_name, at, "
                + "detail_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", teamId, day, kind, taskId, problemId, actorId,
                fit(actorName, 200), at, fit(detailJson, 4000));
    }

    public List<EventRow> events(Long teamId, String day, Long beforeId, int limit) {
        RowMapper<EventRow> m = (rs, n) -> new EventRow(lng(rs, "id"), rs.getString("routine_day"), rs.getString("kind"),
                rs.getString("task_id"), lng(rs, "problem_id"), rs.getString("actor_name"), rs.getString("at"),
                rs.getString("detail_json"));
        String cols = "id, routine_day, kind, task_id, problem_id, actor_name, at, detail_json";
        if (day == null) {
            return beforeId == null
                    ? jdbc.query("SELECT " + cols + " FROM oncall_routine_events WHERE team_id = ? ORDER BY id DESC LIMIT ?",
                            m, teamId, limit)
                    : jdbc.query("SELECT " + cols + " FROM oncall_routine_events WHERE team_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                            m, teamId, beforeId, limit);
        }
        return beforeId == null
                ? jdbc.query("SELECT " + cols + " FROM oncall_routine_events WHERE team_id = ? AND routine_day = ? "
                        + "ORDER BY id DESC LIMIT ?", m, teamId, day, limit)
                : jdbc.query("SELECT " + cols + " FROM oncall_routine_events WHERE team_id = ? AND routine_day = ? AND id < ? "
                        + "ORDER BY id DESC LIMIT ?", m, teamId, day, beforeId, limit);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Bildirim sahiplenmesi
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    public record NoticeRow(Long id, Long teamId, String kind, String refKey, String status, String reason, Long recipientUserId,
                            String mailStatus, String pushStatus, Integer pushCount, int attempts, String claimedAt,
                            String claimedBy, String finishedAt, Long mailLogId, String createdAt) { }

    private static final RowMapper<NoticeRow> NOTICE = (rs, n) -> new NoticeRow(lng(rs, "id"), lng(rs, "team_id"),
            rs.getString("kind"), rs.getString("ref_key"), rs.getString("status"), rs.getString("reason"),
            lng(rs, "recipient_user_id"), rs.getString("mail_status"), rs.getString("push_status"), intOrNull(rs, "push_count"),
            num(rs, "attempts"), rs.getString("claimed_at"), rs.getString("claimed_by"), rs.getString("finished_at"),
            lng(rs, "mail_log_id"), rs.getString("created_at"));

    private static final String NOTICE_COLS = "id, team_id, kind, ref_key, status, reason, recipient_user_id, mail_status, "
            + "push_status, push_count, attempts, claimed_at, claimed_by, finished_at, mail_log_id, created_at";

    public NoticeRow notice(Long teamId, String kind, String ref) {
        List<NoticeRow> l = jdbc.query("SELECT " + NOTICE_COLS + " FROM oncall_routine_notices WHERE team_id = ? AND kind = ? "
                + "AND ref_key = ?", NOTICE, teamId, kind, ref);
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * Sahiplenme: satırı ekleyen kazanır (benzersiz takım × tür × anahtar). {@code status} SENDING (gönderilecek) ya da
     * doğrudan bir son durum (karar satırı). Kazanılırsa satır kimliği, kaybedilirse null.
     */
    public Long claim(Long teamId, String kind, String ref, String status, String reason, Long recipientUserId, String at,
                      String by) {
        int n;
        try {
            n = jdbc.update("INSERT INTO oncall_routine_notices (team_id, kind, ref_key, status, reason, recipient_user_id, "
                    + "attempts, claimed_at, claimed_by, finished_at, created_at) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?) "
                    + "ON CONFLICT DO NOTHING", teamId, kind, fit(ref, 80), status, reason, recipientUserId, at, fit(by, 120),
                    OnCallRoutineCodes.N_SENDING.equals(status) ? null : at, at);
        } catch (DataIntegrityViolationException e) {
            return null;
        }
        if (n != 1) return null;
        NoticeRow r = notice(teamId, kind, fit(ref, 80));
        return r == null ? null : r.id();
    }

    public int finishNotice(Long id, String claimedAt, String status, String reason, String mailStatus, String pushStatus,
                            Integer pushCount, String errorClass, String finishedAt, Long mailLogId) {
        return jdbc.update("UPDATE oncall_routine_notices SET status = ?, reason = ?, mail_status = ?, push_status = ?, "
                + "push_count = ?, error_class = ?, finished_at = ?, mail_log_id = ? "
                + "WHERE id = ? AND status = 'SENDING' AND claimed_at = ?", status, reason, fit(mailStatus, 255), pushStatus,
                pushCount, fit(errorClass, 120), finishedAt, mailLogId, id, claimedAt);
    }

    public int markUnknown(Long id, String claimedAt, String at) {
        return jdbc.update("UPDATE oncall_routine_notices SET status = 'UNKNOWN', reason = 'INTERRUPTED', finished_at = ? "
                + "WHERE id = ? AND status = 'SENDING' AND claimed_at = ?", at, id, claimedAt);
    }

    public List<NoticeRow> staleSending(String before, int limit) {
        return jdbc.query("SELECT " + NOTICE_COLS + " FROM oncall_routine_notices WHERE status = 'SENDING' AND claimed_at < ? "
                + "ORDER BY id LIMIT ?", NOTICE, before, limit);
    }

    public boolean existsStaleSending(String before) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT 1 FROM oncall_routine_notices WHERE status = 'SENDING' "
                + "AND claimed_at < ? LIMIT 1) x", Integer.class, before);
        return n != null && n > 0;
    }

    /**
     * Yeniden denenebilir e-posta bildirimlerinin (FAILED ya da e-postası iletilemeyen PARTIAL) bir sayfası — deneme hakkı bitmemiş satırlar, {@code afterId}'den sonrası (anahtar
     * kümesi). Deneme hakkı SQL'de süzülür ve sayfa sayfa okunur: hakkı biten ya da yeniden denenmeyecek eski satırlar
     * yenilerinin önünü tıkamaz.
     */
    public List<NoticeRow> retryable(String createdSince, long afterId, int maxAttempts, int limit) {
        // PARTIAL yalnız e-postası ULAŞMAMIŞSA (Notifier.mailOutcomeOf FAILED: dolu ve SENT / QUEUED / SKIPPED değil)
        return jdbc.query("SELECT " + NOTICE_COLS + " FROM oncall_routine_notices WHERE kind IN ('COMPLETE', 'HANDOVER') "
                + "AND (status = 'FAILED' OR (status = 'PARTIAL' AND mail_status IS NOT NULL AND mail_status NOT LIKE 'SENT%' "
                + "AND mail_status NOT LIKE 'QUEUED%' AND mail_status NOT LIKE 'SKIPPED%')) "
                + "AND attempts < ? AND created_at >= ? AND id > ? ORDER BY id LIMIT ?",
                NOTICE, maxAttempts, createdSince, afterId, limit);
    }

    /**
     * Yeniden denemeden vazgeçer: deneme sayısı üst sınıra çekilir (durum ve sonuç aynen kalır). Koşullu — okunan durum ve
     * deneme sayısı hâlâ yerindeyse; 1 = bu pod vazgeçti (izi o yazar).
     */
    public int giveUpRetry(Long id, String seenStatus, int seenAttempts, int maxAttempts) {
        return jdbc.update("UPDATE oncall_routine_notices SET attempts = ? WHERE id = ? AND status = ? AND attempts = ? "
                + "AND attempts < ?", maxAttempts, id, seenStatus, seenAttempts, maxAttempts);
    }

    public int reclaim(Long id, String seenStatus, int seenAttempts, String at, String by) {
        return jdbc.update("UPDATE oncall_routine_notices SET status = 'SENDING', claimed_at = ?, claimed_by = ?, "
                + "attempts = attempts + 1, finished_at = NULL WHERE id = ? AND status = ? AND attempts = ?",
                at, fit(by, 120), id, seenStatus, seenAttempts);
    }

    /** Kuyruktaki (421) e-postanın sonucu — okunan durum + posta durumu hâlâ yerindeyse. */
    public int settleQueuedMail(Long id, String seenStatus, String seenMail, String status, String reason, String mailStatus) {
        return jdbc.update("UPDATE oncall_routine_notices SET status = ?, reason = ?, mail_status = ? "
                + "WHERE id = ? AND status = ? AND mail_status = ?", status, reason, fit(mailStatus, 255), id, seenStatus, seenMail);
    }

    public List<NoticeRow> queuedMails(String createdSince, int limit) {
        return jdbc.query("SELECT " + NOTICE_COLS + " FROM oncall_routine_notices WHERE kind IN ('COMPLETE', 'HANDOVER') "
                + "AND mail_status LIKE 'QUEUED%' AND mail_log_id IS NOT NULL AND created_at >= ? ORDER BY id LIMIT ?",
                NOTICE, createdSince, limit);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // İstatistik (gruplu SQL)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Aralıktaki DAY görevleri: görev × durum sayısı. Satır: [task_id, state, count]. */
    public List<Object[]> taskStateCounts(Long teamId, String from, String to) {
        return jdbc.query("SELECT task_id, state, COUNT(*) AS n FROM oncall_routine_items WHERE team_id = ? AND due_day >= ? "
                + "AND due_day <= ? GROUP BY task_id, state", (rs, n) -> new Object[] {rs.getString(1), rs.getString(2),
                rs.getInt(3)}, teamId, from, to);
    }

    /** Aralıktaki görevlerin son başlık / bölüm / sıklık anlık görüntüsü (görev başına en yeni satır). */
    public List<Object[]> taskTitles(Long teamId, String from, String to) {
        return jdbc.query("SELECT i.task_id, i.title, i.section_title, i.frequency FROM oncall_routine_items i "
                + "WHERE i.team_id = ? AND i.id IN (SELECT MAX(x.id) FROM oncall_routine_items x WHERE x.team_id = ? "
                + "AND x.due_day >= ? AND x.due_day <= ? GROUP BY x.task_id)",
                (rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                teamId, teamId, from, to);
    }

    /** DONE satırları: [task_id, state_at, state_by_id, state_by_name] — en çok {@code limit}, en yeni önce. */
    public List<Object[]> doneRows(Long teamId, String from, String to, int limit) {
        return jdbc.query("SELECT task_id, state_at, state_by_id, state_by_name FROM oncall_routine_items WHERE team_id = ? "
                + "AND due_day >= ? AND due_day <= ? AND state = 'DONE' ORDER BY state_at DESC, id DESC LIMIT ?",
                (rs, n) -> new Object[] {rs.getString(1), rs.getString(2), lng(rs, "state_by_id"), rs.getString(4)},
                teamId, from, to, limit);
    }

    /** Kişi başına işaretlenen görev: [state_by_id, state_by_name, count]. */
    public List<Object[]> itemsDoneByPerson(Long teamId, String from, String to) {
        return jdbc.query("SELECT state_by_id, MAX(state_by_name) AS nm, COUNT(*) AS n FROM oncall_routine_items WHERE team_id = ? "
                + "AND due_day >= ? AND due_day <= ? AND state = 'DONE' AND state_by_id IS NOT NULL GROUP BY state_by_id",
                (rs, n) -> new Object[] {lng(rs, "state_by_id"), rs.getString("nm"), rs.getInt("n")}, teamId, from, to);
    }

    /** Görev başına sorun sayısı ve kişi başına açılan sorun. */
    public List<Object[]> problemCountsByTask(Long teamId, String from, String to) {
        return jdbc.query("SELECT task_id, COUNT(*) AS n FROM oncall_routine_problems WHERE team_id = ? AND routine_day >= ? "
                + "AND routine_day <= ? AND task_id IS NOT NULL GROUP BY task_id",
                (rs, n) -> new Object[] {rs.getString(1), rs.getInt(2)}, teamId, from, to);
    }

    public List<Object[]> problemsByCreator(Long teamId, String from, String to) {
        return jdbc.query("SELECT created_by_id, COUNT(*) AS n FROM oncall_routine_problems WHERE team_id = ? AND routine_day >= ? "
                + "AND routine_day <= ? AND created_by_id IS NOT NULL GROUP BY created_by_id",
                (rs, n) -> new Object[] {lng(rs, "created_by_id"), rs.getInt(2)}, teamId, from, to);
    }

    /** Dışa aktarım: aralıktaki görev satırları (en çok {@code limit}). */
    public List<Object[]> exportItems(Long teamId, String from, String to, int limit) {
        return jdbc.query("SELECT due_day, period_kind, period_key, section_title, title, state, state_by_name, state_at, "
                + "skip_reason, note FROM oncall_routine_items WHERE team_id = ? AND due_day >= ? AND due_day <= ? "
                + "ORDER BY due_day, id LIMIT ?", (rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10)}, teamId, from, to, limit);
    }

    public int countItems(Long teamId, String from, String to) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM oncall_routine_items WHERE team_id = ? AND due_day >= ? "
                + "AND due_day <= ?", Integer.class, teamId, from, to);
        return n == null ? 0 : n;
    }

    /** Yönetici özeti: takım × durum sayısı (aralık). Satır: [team_id, status, count]. */
    public List<Object[]> dayStatusCounts(String from, String to) {
        return jdbc.query("SELECT team_id, status, COUNT(*) AS n FROM oncall_routine_days WHERE routine_day >= ? AND routine_day <= ? "
                + "GROUP BY team_id, status", (rs, n) -> new Object[] {lng(rs, "team_id"), rs.getString(2), rs.getInt(3)}, from, to);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Yaşam döngüsü
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Takım silinince tüm rutin satırları (sıra: görseller → sorunlar → … → takım satırı). */
    public void deleteForTeam(Long teamId) {
        jdbc.update("DELETE FROM oncall_routine_problem_images WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM oncall_routine_problems WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM oncall_routine_items WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM oncall_routine_events WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM oncall_routine_notices WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM oncall_routine_handovers WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM oncall_routine_assignments WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM oncall_routine_days WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM oncall_routine_template_versions WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM oncall_routine_teams WHERE team_id = ?", teamId);
    }

    /** Takım silme etkisi: [configured, tasks_active, days, open_problems]. */
    public Map<String, Object> impact(Long teamId) {
        Map<String, Object> m = new LinkedHashMap<>();
        TeamRow t = team(teamId);
        m.put("configured", t != null && t.configured());
        m.put("tasks_active", t == null ? 0 : t.tasksActive());
        Integer days = jdbc.queryForObject("SELECT COUNT(*) FROM oncall_routine_days WHERE team_id = ?", Integer.class, teamId);
        m.put("days", days == null ? 0 : days);
        m.put("open_problems", countProblems(teamId, OnCallRoutineCodes.PROBLEM_OPEN));
        return m;
    }

    // ── yardımcılar ──────────────────────────────────────────────────────────────────────────────

    static String marks(int n) {
        StringJoiner j = new StringJoiner(", ");
        for (int i = 0; i < n; i++) j.add("?");
        return j.toString();
    }

    static String fit(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    static Long lng(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        return o instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    static int num(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        return o instanceof Number n ? n.intValue() : 0;
    }

    static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        return o instanceof Number n ? Integer.valueOf(n.intValue()) : null;
    }
}

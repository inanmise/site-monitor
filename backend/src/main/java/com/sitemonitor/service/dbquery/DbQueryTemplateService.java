package com.sitemonitor.service.dbquery;

import com.sitemonitor.controller.DbQueryApiException;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.model.DbQueryTemplate;
import com.sitemonitor.repository.DbQueryTemplateRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Takım sorgu şablonları (2026-10-31, veritabanı sorgu izlemesi — dbquery-api.md §5): sık kullanılan sorgu + kural kümesi
 * adıyla saklanır; yeni izleme "şablondan başla" ile doldurulur. Takım belgesidir — kapsam kuralı bağlantılarla AYNI
 * ({@link DbConnectionProfileService#canRead} / {@link DbConnectionProfileService#canWrite}; paylaşım ve 7/24 açmaz).
 *
 * <p>Kayıt kuralları: takımda en çok {@value #MAX_PER_TEAM} şablon (409 {@code TEMPLATE_LIBRARY_LIMIT}), ad takımda tekil
 * (409 {@code DUPLICATE_RECORD}), sorgu {@link DbQuerySqlGuard} denetiminden geçer (400 {@code DBQUERY_SQL_REJECTED}; tür
 * seçilmemişse PostgreSQL kurallarıyla — izlemeye uygulanınca bağlantının türüyle yeniden denetlenir), kurallar
 * {@link DbQueryRules#fromApi} ile doğrulanır. Sınır ve ad kararı takım satırı kilidi altında verilir (iki eşzamanlı ekleme
 * 51. şablonu ya da aynı adı kaçıramaz).
 */
@Slf4j
@Service
public class DbQueryTemplateService {

    public static final int MAX_PER_TEAM = 50, MAX_NAME = 120, MAX_DESCRIPTION = 500, MAX_PURPOSE = 2000;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DbQueryTemplateRepository repo;
    private final TeamRepository teamRepo;

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    public DbQueryTemplateService(DbQueryTemplateRepository repo, TeamRepository teamRepo) {
        this.repo = repo;
        this.teamRepo = teamRepo;
    }

    void setJdbc(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<DbQueryTemplate> visible(HttpSession session, Long teamId) {
        if (teamId != null) return DbConnectionProfileService.canRead(session, teamId) ? repo.findByTeamIdOrderByNameAsc(teamId) : List.of();
        if (com.sitemonitor.controller.SessionScope.isGlobalViewer(session)) return repo.findAll().stream()
                .sorted(java.util.Comparator.comparing(DbQueryTemplate::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        Set<Long> teams = new LinkedHashSet<>();
        var v = com.sitemonitor.controller.SessionScope.viewTeamIds(session);
        if (v != null) teams.addAll(v);
        var mg = com.sitemonitor.controller.SessionScope.manageTeamIds(session);
        if (mg != null) teams.addAll(mg);
        teams.addAll(com.sitemonitor.controller.SessionScope.memberTeamIds(session));
        Long primary = com.sitemonitor.controller.SessionScope.primaryTeamId(session);
        if (primary != null) teams.add(primary);
        teams.removeIf(Objects::isNull);
        return teams.isEmpty() ? List.of() : repo.findByTeamIdInOrderByNameAsc(teams);
    }

    @Transactional
    public DbQueryTemplate create(Map<String, Object> body, HttpSession session, String actorName) {
        Long teamId = DbConnectionProfileService.longOf(body.get("team_id"));
        if (teamId == null) throw DbQueryApiException.field(null, "team_id",
                Msg.t("Takım seçin — şablon bir takımın belgesidir.", "Choose a team — a template belongs to a team."));
        if (!DbConnectionProfileService.canWrite(session, teamId)) throw new SecurityException(Msg.t(
                "Bu takıma şablon ekleme yetkiniz yok.", "You can't add a template to this team."));
        if (!teamRepo.existsById(teamId)) throw DbQueryApiException.field(null, "team_id",
                Msg.t("Seçilen takım bulunamadı — geçerli bir takım seçin.", "The selected team doesn't exist — choose a valid team."));
        DbQueryTemplate t = new DbQueryTemplate();
        t.setTeamId(teamId);
        apply(t, body, true);
        lockTeam(teamId);
        if (repo.countByTeamId(teamId) >= MAX_PER_TEAM) {
            throw DbQueryApiException.conflict("TEMPLATE_LIBRARY_LIMIT", null, Msg.t(
                    "Takımın şablon kitaplığı dolu (en çok " + MAX_PER_TEAM + "). Kullanılmayan şablonları silip yeniden deneyin.",
                    "The team's template library is full (at most " + MAX_PER_TEAM + "). Delete unused templates and try again."), null);
        }
        if (repo.existsDuplicate(t.getName(), teamId, null)) throw duplicateName();
        String now = ISO.format(Instant.now());
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        t.setCreatedByName(actorName);
        t.setUpdatedByName(actorName);
        return repo.save(t);
    }

    @Transactional
    public DbQueryTemplate update(Long id, Map<String, Object> body, HttpSession session, String actorName) {
        DbQueryTemplate t = requireWritable(id, session);
        if (body.containsKey("team_id")) {
            Long requested = DbConnectionProfileService.longOf(body.get("team_id"));
            if (requested != null && !requested.equals(t.getTeamId())) {
                throw DbQueryApiException.field(null, "team_id", Msg.t(
                        "Şablonun takımı değiştirilemez; hedef takımda yeni şablon olarak kaydedin.",
                        "A template's team can't change; save it as a new template in the target team."));
            }
        }
        String oldName = t.getName();
        apply(t, body, false);
        lockTeam(t.getTeamId());
        if (!t.getName().equalsIgnoreCase(oldName) && repo.existsDuplicate(t.getName(), t.getTeamId(), id)) throw duplicateName();
        t.setUpdatedAt(ISO.format(Instant.now()));
        t.setUpdatedByName(actorName);
        return repo.save(t);
    }

    @Transactional
    public DbQueryTemplate delete(Long id, HttpSession session) {
        DbQueryTemplate t = requireWritable(id, session);
        repo.delete(t);
        return t;
    }

    private DbQueryTemplate requireWritable(Long id, HttpSession session) {
        DbQueryTemplate t = id == null ? null : repo.findById(id).orElse(null);
        if (t == null || !DbConnectionProfileService.canRead(session, t.getTeamId())) {
            throw new java.util.NoSuchElementException(Msg.t(
                    "Şablon bulunamadı; silinmiş olabilir ya da görme yetkiniz yok. Listeyi yenileyin.",
                    "The template wasn't found; it may have been deleted or you can't see it. Refresh the list."));
        }
        if (!DbConnectionProfileService.canWrite(session, t.getTeamId())) throw new SecurityException(Msg.t(
                "Bu takımın şablonunu değiştirme yetkiniz yok.", "You can't change this team's template."));
        return t;
    }

    /** Gelen alanları yazar ve BİRLEŞİK kaydı doğrular (alan hataları toplanır; sorgu reddi ayrı kodla). */
    void apply(DbQueryTemplate t, Map<String, Object> body, boolean create) {
        Map<String, String> errs = new LinkedHashMap<>();
        if (body.containsKey("name") || create) t.setName(trimOrNull(body.get("name")));
        if (body.containsKey("db_type")) {
            String raw = trimOrNull(body.get("db_type"));
            DbType type = DbType.parse(raw);
            if (raw != null && type == null) errs.put("db_type", Msg.t("Veritabanı türü Oracle, MSSQL ya da PostgreSQL olmalı.",
                    "The database type must be Oracle, MSSQL or PostgreSQL."));
            t.setDbType(type == null ? null : type.name());
        }
        if (body.containsKey("description")) t.setDescription(trimOrNull(body.get("description")));
        if (body.containsKey("purpose")) t.setPurpose(trimOrNull(body.get("purpose")));
        boolean sqlSent = body.containsKey("sql_text") || create;
        if (sqlSent) t.setSqlText(body.get("sql_text") == null ? null : body.get("sql_text").toString());
        if (body.containsKey("rule_logic") || create) {
            String logic = trimOrNull(body.get("rule_logic"));
            logic = logic == null ? DbQueryMonitor.LOGIC_ANY : logic.toUpperCase(Locale.ROOT);
            if (!DbQueryMonitor.LOGIC_ANY.equals(logic) && !DbQueryMonitor.LOGIC_ALL.equals(logic)) {
                errs.put("rule_logic", Msg.t("Kural birleşimi ANY ya da ALL olmalı.", "The rule logic must be ANY or ALL."));
            } else {
                t.setRuleLogic(logic);
            }
        }
        if (body.containsKey("rules") || create) {
            Object raw = body.get("rules");
            DbQueryRules.Parsed parsed = DbQueryRules.fromApi(raw == null ? List.of() : raw);
            if (parsed.errors() != null && !parsed.errors().isEmpty()) {
                for (DbQueryRules.FieldError fe : parsed.errors()) errs.putIfAbsent(fe.field(), fe.message());
            } else {
                t.setRulesJson(DbQueryRules.toJson(parsed.rules() == null ? List.of() : parsed.rules()));
            }
        }
        if (t.getName() == null || t.getName().isBlank()) {
            errs.put("name", Msg.t("Şablona bir ad verin.", "Give the template a name."));
        } else if (t.getName().length() > MAX_NAME) {
            errs.put("name", Msg.t("Ad en çok " + MAX_NAME + " karakter olabilir.", "The name can be at most " + MAX_NAME + " characters."));
        }
        if (t.getDescription() != null && t.getDescription().length() > MAX_DESCRIPTION) {
            errs.put("description", Msg.t("Açıklama en çok " + MAX_DESCRIPTION + " karakter olabilir.",
                    "The description can be at most " + MAX_DESCRIPTION + " characters."));
        }
        if (t.getPurpose() != null && t.getPurpose().length() > MAX_PURPOSE) {
            errs.put("purpose", Msg.t("Amaç metni en çok " + MAX_PURPOSE + " karakter olabilir.",
                    "The purpose can be at most " + MAX_PURPOSE + " characters."));
        }
        if (t.getSqlText() == null || t.getSqlText().isBlank()) {
            errs.put("sql_text", Msg.t("Şablonun sorgusunu yazın.", "Write the template's query."));
        }
        if (!errs.isEmpty()) {
            throw DbQueryApiException.validation(Msg.t("Şablon kaydedilemedi: işaretli alanları düzeltin.",
                    "The template couldn't be saved: fix the marked fields."), errs);
        }
        if (sqlSent || body.containsKey("db_type")) {
            DbType type = DbType.parse(t.getDbType());
            DbQuerySqlGuard.Result g = DbQuerySqlGuard.check(t.getSqlText(), type == null ? DbType.POSTGRESQL : type);
            if (g == null || !g.ok()) throw sqlRejected(g);
        }
    }

    /** 400 DBQUERY_SQL_REJECTED + ihlaller (izleme kaydıyla aynı biçim). */
    public static DbQueryApiException sqlRejected(DbQuerySqlGuard.Result g) {
        List<Map<String, Object>> v = violations(g);
        String first = v.isEmpty() ? null : String.valueOf(v.get(0).get("message"));
        String msg = Msg.t("Sorgu güvenlik denetiminden geçmedi" + (first == null ? "." : ": " + first),
                "The query didn't pass the safety check" + (first == null ? "." : ": " + first));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("sql_text", msg);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("violations", v);
        return new DbQueryApiException(400, "DBQUERY_SQL_REJECTED", msg, fields, extra);
    }

    /** Violation → API ({@code code, token, message, line, column}). */
    public static List<Map<String, Object>> violations(DbQuerySqlGuard.Result g) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (g == null || g.violations() == null) return out;
        for (DbQuerySqlGuard.Violation x : g.violations()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", x.code());
            m.put("token", x.token());
            m.put("message", x.message());
            m.put("line", x.line());
            m.put("column", x.column());
            out.add(m);
        }
        return out;
    }

    /** API satırı (dbquery-api.md §5). */
    public static Map<String, Object> toApi(DbQueryTemplate t, String teamName, boolean canEdit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("team_id", t.getTeamId());
        m.put("team_name", teamName);
        m.put("name", t.getName());
        m.put("db_type", t.getDbType());
        m.put("description", t.getDescription());
        m.put("sql_text", t.getSqlText());
        m.put("purpose", t.getPurpose());
        m.put("rules", rulesApi(t.getRulesJson()));
        m.put("rule_logic", t.getRuleLogic() == null ? DbQueryMonitor.LOGIC_ANY : t.getRuleLogic());
        m.put("created_at", t.getCreatedAt());
        m.put("created_by_name", t.getCreatedByName());
        m.put("updated_at", t.getUpdatedAt());
        m.put("updated_by_name", t.getUpdatedByName());
        m.put("can_edit", canEdit);
        return m;
    }

    /** Saklı kural JSON'u → API biçimi; motor okuyamazsa (bozuk satır) ham JSON gevşek okunur — liste asla düşmez. */
    public static List<Map<String, Object>> rulesApi(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return DbQueryRules.toApi(DbQueryRules.fromJson(json));
        } catch (RuntimeException e) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> raw = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
                return raw == null ? List.of() : raw;
            } catch (Exception ignored) {
                return List.of();
            }
        }
    }

    private void lockTeam(Long teamId) {
        if (jdbc == null || teamId == null) return;
        try {
            jdbc.queryForList("SELECT id FROM teams WHERE id = ? FOR UPDATE", Long.class, teamId);
        } catch (RuntimeException e) {
            log.debug("Şablon yazımı: takım satırı kilitlenemedi: {}", e.getClass().getSimpleName());
        }
    }

    private static DbQueryApiException duplicateName() {
        return DbQueryApiException.conflict("DUPLICATE_RECORD", "name", Msg.t(
                "Bu takımda aynı adlı bir şablon zaten var; başka bir ad seçin.",
                "This team already has a template with that name; choose another name."), null);
    }

    private static String trimOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}

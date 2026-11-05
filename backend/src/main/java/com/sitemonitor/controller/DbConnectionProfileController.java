package com.sitemonitor.controller;

import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbQueryCheckRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.dbquery.DbAccountPrivileges;
import com.sitemonitor.service.dbquery.DbConnectionProfileService;
import com.sitemonitor.service.dbquery.DbConnectionSteps;
import com.sitemonitor.service.dbquery.DbQueryCheckerService;
import com.sitemonitor.service.dbquery.DbQueryRunner;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Kayıtlı veritabanı bağlantıları (2026-10-31, veritabanı sorgu izlemesi — dbquery-api.md §2).
 *
 * <p>Bağlantı TAKIM belgesidir: okuma = {@code monitoring.read/view} + kendi takım kapsamı; erişim paylaşımı ve 7/24
 * operatörlüğü açmaz. Yazma ve deneme = {@code monitoring.crud/edit} + takımı kendi kapsamıyla işletebilmek. Parola asla
 * dönmez ({@code password_set}); denetim ayrıntısına girmez. Deneme uçları satır VERİSİ döndürmez — yalnız bağlantı sonucu.
 */
@RestController
@RequestMapping("/api/monitoring/dbquery/connections")
@RequiredArgsConstructor
public class DbConnectionProfileController {

    /** Oturum başına deneme beklemesi (sn) — art arda tıklama hedef veritabanını yormasın. */
    static final int TEST_COOLDOWN_SECONDS = 5;
    /** Oturum özniteliği (JDK türü: Long — SessionAttributeTypesGateTest). */
    static final String TEST_AT = "dbqConnTestAt";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DbConnectionProfileService profiles;
    private final DbQueryMonitorRepository monitorRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final CertificateService certificateService;

    /** Bağlantı denemesi — motor paketinin servisi; dilimli testte yoksa deneme 503. */
    @Autowired(required = false)
    private DbQueryCheckerService checker;

    /** Sağlık kartının çalıştırma sayımları (dbq2 A4) — dilimli testte yoksa sağlık ucu 503. */
    @Autowired(required = false)
    private DbQueryCheckRepository checkRepo;

    /**
     * Kullanıcı başına KÜME saatlik deneme sınırı (dbq2 inceleme D5): bağlantı denemesi de sayılır — TCP / oturum adımları iç
     * ağda net bir "port açık mı" kâhinidir; oturum beklemesi tek başına pod ve oturum başınaydı. Bean yoksa (dilimli testler)
     * sınır yok.
     */
    @Autowired(required = false)
    private com.sitemonitor.service.dbquery.DbQueryTestQuota testQuota;

    /** Denemeyi YÜRÜTMEYEN sonuçlar — saatlik hak geri verilir (motor meşgul, parola çözülemedi, bağlantı yok). */
    static final java.util.Set<String> NOT_EXECUTED = java.util.Set.of("DB_BUSY", "DB_SECRET_UNREADABLE", "DB_PROFILE_MISSING");

    @ExceptionHandler(DbQueryApiException.class)
    public ResponseEntity<Map<String, Object>> onApiError(DbQueryApiException e) {
        return e.toResponse();
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(name = "team_id", required = false) Long teamId,
                                                    HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        if (teamId != null && !DbConnectionProfileService.canRead(session, teamId)) {
            throw new SecurityException(Msg.t("Bu takımın bağlantılarını görme yetkiniz yok.", "You can't see this team's connections."));
        }
        List<DbConnectionProfile> rows = profiles.visible(session, teamId);
        Map<Long, List<DbQueryMonitor>> users = usersByProfile(rows);
        Map<Long, String> teams = certificateService.teamNamesById();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DbConnectionProfile p : rows) {
            List<DbQueryMonitor> u = users.getOrDefault(p.getId(), List.of());
            out.add(DbConnectionProfileService.toApi(p, teams.get(p.getTeamId()),
                    DbConnectionProfileService.canWrite(session, p.getTeamId()), u, DbConnectionProfileService.tablesUsed(p, u)));
        }
        return ok(out);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        DbConnectionProfile saved = profiles.create(nonNull(body), session, actor(session), actorName(session));
        auditService.recordAction("DBQUERY_CONNECTION_CREATE", session, "DBQUERY_CONNECTION", String.valueOf(saved.getId()),
                AuditDetail.of("name", saved.getName(), "team_id", saved.getTeamId(), "db_type", saved.getDbType(),
                        "host", saved.getHost(), "port", saved.getPort(), "password_set", saved.getPasswordEnc() != null), null);
        return ok(row(saved, session));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                      HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        Map<String, Object> b = nonNull(body);
        DbConnectionProfileService.Updated r = profiles.update(id, b, session, actor(session), actorName(session));
        Map<String, Object> before = DbConnectionProfileService.auditView(r.before());
        Map<String, Object> after = DbConnectionProfileService.auditView(r.saved());
        boolean pwChanged = !java.util.Objects.equals(r.before().getPasswordEnc(), r.saved().getPasswordEnc());
        auditService.recordAction("DBQUERY_CONNECTION_UPDATE", session, "DBQUERY_CONNECTION", String.valueOf(id),
                AuditDetail.of("name", r.saved().getName(), "team_id", r.saved().getTeamId(), "password_changed", pwChanged),
                com.sitemonitor.service.AuditDiff.diff(before, after));
        return ok(row(r.saved(), session));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        DbConnectionProfile gone = profiles.delete(id, session);
        auditService.recordAction("DBQUERY_CONNECTION_DELETE", session, "DBQUERY_CONNECTION", String.valueOf(id),
                AuditDetail.of("name", gone.getName(), "team_id", gone.getTeamId(), "db_type", gone.getDbType(),
                        "host", gone.getHost()), null);
        return ok(Map.of("deleted", true));
    }

    /** Kayıtlı bağlantıyı dener; sonuç sağlık kolonlarına yazılır (motor servisi). */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testSaved(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        DbConnectionProfile p = profiles.requireUsable(id, session);
        DbQueryCheckerService c = requireChecker();
        cooldown(session);
        var ticket = hourlyQuota(session);
        DbQueryRunner.ConnectionTest t = c.testConnection(p, null);
        refundIfNotExecuted(ticket, t);
        auditService.recordAction("DBQUERY_CONNECTION_TEST", session, "DBQUERY_CONNECTION", String.valueOf(id),
                AuditDetail.of("name", p.getName(), "ok", t != null && t.ok(), "failure_reason", t == null ? null : t.failureReason()), null);
        return ok(testResult(t));
    }

    /** Kaydetmeden deneme (formdaki değerler; düzenlemede {@code id} + yeni parola). Sağlık kolonlarına YAZMAZ. */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testDraft(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        DbConnectionProfileService.Draft d = profiles.draft(nonNull(body), session);
        DbQueryCheckerService c = requireChecker();
        cooldown(session);
        var ticket = hourlyQuota(session);
        DbQueryRunner.ConnectionTest t = c.testConnection(d.profile(), d.passwordOverride());
        refundIfNotExecuted(ticket, t);
        auditService.recordAction("DBQUERY_CONNECTION_TEST", session, "DBQUERY_CONNECTION",
                body.get("id") == null ? "draft" : String.valueOf(body.get("id")),
                AuditDetail.of("kind", "draft", "team_id", d.profile().getTeamId(), "db_type", d.profile().getDbType(),
                        "host", d.profile().getHost(), "ok", t != null && t.ok(),
                        "failure_reason", t == null ? null : t.failureReason()), null);
        return ok(testResult(t));
    }

    /**
     * Bağlantı sağlık kartı (2026-11-05, dbq2 A4): kullanan izlemeler + son durumları, son 24 saatin çalıştırma özeti, son
     * başarı / hata. Kapı = bağlantıyı GÖREBİLMEK (kendi takım kapsamı; erişim paylaşımı ve 7/24 AÇMAZ) — görülemeyen 404.
     * Salt okuma, sabit sorgu sayısı ({@link DbConnectionProfileService#healthReport}).
     */
    @GetMapping("/{id}/health")
    public ResponseEntity<Map<String, Object>> health(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        DbConnectionProfile p = profiles.requireReadable(id, session);
        if (checkRepo == null) throw new DbQueryApiException(503, "DBQUERY_UNAVAILABLE", Msg.t(
                "Veritabanı sorgu izlemesi bu sunucuda hazır değil; birkaç dakika sonra yeniden deneyin.",
                "Database query monitoring isn't ready on this server; try again in a few minutes."));
        return ok(DbConnectionProfileService.healthReport(p, monitorRepo.findByProfileId(id), checkRepo, Instant.now()));
    }

    // ── İç ───────────────────────────────────────────────────────────────────

    private Map<String, Object> row(DbConnectionProfile p, HttpSession session) {
        List<DbQueryMonitor> u = p.getId() == null ? List.of() : monitorRepo.findByProfileId(p.getId());
        return DbConnectionProfileService.toApi(p, certificateService.teamNamesById().get(p.getTeamId()),
                DbConnectionProfileService.canWrite(session, p.getTeamId()), u, DbConnectionProfileService.tablesUsed(p, u));
    }

    /** Bağlantı → onu kullanan izlemeler (tek sorgu; liste başına N+1 yok). */
    private Map<Long, List<DbQueryMonitor>> usersByProfile(List<DbConnectionProfile> rows) {
        if (rows.isEmpty()) return Map.of();
        java.util.Set<Long> ids = rows.stream().map(DbConnectionProfile::getId).collect(Collectors.toSet());
        Map<Long, List<DbQueryMonitor>> out = new LinkedHashMap<>();
        for (DbQueryMonitor m : monitorRepo.findAllByOrderByNameAsc()) {
            if (m.getProfileId() != null && ids.contains(m.getProfileId())) {
                out.computeIfAbsent(m.getProfileId(), k -> new ArrayList<>()).add(m);
            }
        }
        return out;
    }

    private Map<String, Object> testResult(DbQueryRunner.ConnectionTest t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", t != null && t.ok());
        out.put("duration_ms", t == null ? null : t.durationMs());
        out.put("server_version", t == null ? null : t.serverVersion());
        out.put("error", t == null ? Msg.t("Deneme sonucu alınamadı.", "No test result was returned.") : t.error());
        out.put("failure_reason", t == null ? "DB_DRIVER" : t.failureReason());
        // r177 (Ö-1): hesabın yazma / yönetici yetkileri — {checked, read_only, findings: [{code, detail}]}; ayar kipi
        // (OFF | WARN | BLOCK) ön yüzün uyarı / ret dilini seçmesi için
        DbAccountPrivileges.Result priv = t == null || t.privileges() == null ? DbAccountPrivileges.Result.NOT_CHECKED : t.privileges();
        out.put("privileges", priv.toApi());
        String mode = checker == null ? DbQueryCheckerService.WRITABLE_WARN : checker.writableMode();
        out.put("writable_account_mode", mode);
        out.put("account_blocked", DbQueryCheckerService.WRITABLE_BLOCK.equals(mode) && priv.writable());
        // dbq2 A3: adım adım teşhis — her zaman altı adım; deneme çalıştırıcıya ulaşmadıysa (meşgul, parola çözülemedi …)
        // hepsi "atlandı", neden yukarıdaki hata alanlarında
        out.put("steps", DbConnectionSteps.toApi(t == null ? null : t.steps()));
        return out;
    }

    /**
     * Oturum başına bekleme — 429 RATE_LIMITED + retry_after_seconds. Oku-yaz ATOMİK (oturum kilidi; dbq2 inceleme D5): aynı
     * oturumdan eşzamanlı iki istekten yalnız biri geçer. Pod'lar arası sınır saatlik küme kotasıdır ({@link #hourlyQuota}).
     */
    static void cooldown(HttpSession session) {
        synchronized (org.springframework.web.util.WebUtils.getSessionMutex(session)) {
            long now = System.currentTimeMillis();
            Object prev = session.getAttribute(TEST_AT);
            long windowMs = TEST_COOLDOWN_SECONDS * 1000L;
            if (prev instanceof Long p && now - p < windowMs) {
                long wait = (windowMs - (now - p) + 999) / 1000;
                throw DbQueryApiException.tooMany("RATE_LIMITED", Msg.t(
                        "Çok sık deneme; " + wait + " sn sonra yeniden deneyin.",
                        "Too many tests; try again in " + wait + " s."), wait);
            }
            session.setAttribute(TEST_AT, Long.valueOf(now));
        }
    }

    /**
     * Saatlik küme kotası (sorgu denemesi / maliyet denetimi / elle kontrolle ORTAK sayaç). Aşımda 429 {@code RATE_LIMITED};
     * o zaman oturum damgası geri alınır (bir sonraki tıklama saatlik iletiyi görsün). Bean yoksa null.
     */
    private com.sitemonitor.service.dbquery.DbQueryTestQuota.Ticket hourlyQuota(HttpSession session) {
        if (testQuota == null) return null;
        try {
            return testQuota.consume(session);
        } catch (DbQueryApiException e) {
            session.removeAttribute(TEST_AT);
            throw e;
        }
    }

    /** Deneme çalıştırıcıya ulaşmadıysa saatlik hak geri verilir (yapılmayan deneme sayılmasın). */
    private void refundIfNotExecuted(com.sitemonitor.service.dbquery.DbQueryTestQuota.Ticket ticket, DbQueryRunner.ConnectionTest t) {
        if (testQuota == null || ticket == null || t == null || !NOT_EXECUTED.contains(t.failureReason())) return;
        try {
            testQuota.refund(ticket);
        } catch (RuntimeException e) {
            // hak geri verilemedi — deneme yine yanıtlanır
        }
    }

    private DbQueryCheckerService requireChecker() {
        if (checker == null) throw new DbQueryApiException(503, "DBQUERY_UNAVAILABLE", Msg.t(
                "Veritabanı sorgu izlemesi bu sunucuda hazır değil; birkaç dakika sonra yeniden deneyin.",
                "Database query monitoring isn't ready on this server; try again in a few minutes."));
        return checker;
    }

    private static Map<String, Object> nonNull(Map<String, Object> body) {
        return body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }

    private static String actorName(HttpSession session) {
        Object d = session != null ? session.getAttribute("displayName") : null;
        return d != null && !d.toString().isBlank() ? d.toString() : actor(session);
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", ISO.format(Instant.now())));
    }
}

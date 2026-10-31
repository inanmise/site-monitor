package com.sitemonitor.service.dbquery;

import com.sitemonitor.controller.DbQueryApiException;
import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbConnectionProfileRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Kayıtlı veritabanı bağlantıları (2026-10-31, veritabanı sorgu izlemesi) — doğrulama, kalıcılık ve API biçimi.
 *
 * <p><b>Takım belgesi.</b> Okuma: kendi takım kapsamı ({@link SessionScope#canView} — üyelik / yönetim / görüş, global
 * görüntüleyici) ya da takımı işletebilme; erişim paylaşımı ve 7/24 operatörlüğü bağlantıyı AÇMAZ (bir takımın bağlantısını
 * başka takım göremez). Yazma: takımı KENDİ kapsamıyla işletebilmek ({@link SessionScope#canOperateTeamOwn}; kullanıcı
 * kararı — izleme düzenleyebilen takım üyesi yönetir); izin ({@code monitoring.crud/edit}) denetleyicide sorulur.
 *
 * <p><b>Parola.</b> Yalnız değişince yazılır ({@code SecretCipher}, {@code enc:v1:}); boş / gelmeyen parola mevcut değeri
 * korur, {@code password_clear: true} siler. API'den asla dönmez ({@code password_set}); denetim ayrıntısına girmez.
 *
 * <p><b>Bağlantı adresi.</b> JDBC URL kullanıcıdan alınmaz; alanlar biçimce doğrulanır (ad / IP — şema, yol, boşluk, ';'
 * yok) ve {@link SsrfGuard} ile çözülüp politika denetiminden geçer (meta veri / döngü / iç ağ kuralları ayardan).
 *
 * <p><b>Kullanılan bağlantı.</b> Silinemez (409 {@code DBQUERY_CONNECTION_IN_USE} + izlemeler); takımı değiştirilemez
 * (izleme ve bağlantı aynı takımda kalmalı); türü değişirse onu kullanan her izlemenin sorgusu yeni türle yeniden denetlenir.
 */
@Slf4j
@Service
public class DbConnectionProfileService {

    public static final int MAX_NAME = 120, MAX_HOST = 253, MAX_DATABASE = 128, MAX_USERNAME = 128,
            MAX_PASSWORD = 512, MAX_DESCRIPTION = 500, MAX_INSTANCE = 64;
    public static final int MIN_CONNECT_MS = 1_000, MAX_CONNECT_MS = 30_000, DEFAULT_CONNECT_MS = 10_000;
    public static final List<String> PG_SSL_MODES = List.of("disable", "prefer", "require", "verify-full");
    /** "Kullanılan tablolar" listesinin tavanı (izlemelerin sorgularından birleşik). */
    static final int MAX_TABLES_USED = 100;

    private static final Pattern HOSTNAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_.$#-]{1,128}");
    private static final Pattern INSTANCE = Pattern.compile("[A-Za-z0-9_$#-]{1,64}");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DbConnectionProfileRepository repo;
    private final DbQueryMonitorRepository monitorRepo;
    private final TeamRepository teamRepo;
    private final SecretCipher secretCipher;
    private final SsrfGuard ssrfGuard;

    /** Takım satırı kilidi (ad tekilliği yarışı) — yokken (elle kurulan testler) kilitsiz. */
    @Autowired(required = false)
    private JdbcTemplate jdbc;

    public DbConnectionProfileService(DbConnectionProfileRepository repo, DbQueryMonitorRepository monitorRepo,
                                      TeamRepository teamRepo, SecretCipher secretCipher, SsrfGuard ssrfGuard) {
        this.repo = repo;
        this.monitorRepo = monitorRepo;
        this.teamRepo = teamRepo;
        this.secretCipher = secretCipher;
        this.ssrfGuard = ssrfGuard;
    }

    void setJdbc(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ── Kapsam ───────────────────────────────────────────────────────────────

    /** Bağlantıyı GÖREBİLİR mi — kendi kapsamı ya da takımı işletebilme; paylaşım ve 7/24 AÇMAZ. */
    public static boolean canRead(HttpSession session, Long teamId) {
        return teamId != null && (SessionScope.canView(session, teamId) || SessionScope.canOperateTeamOwn(session, teamId));
    }

    /** Bağlantıyı YAZABİLİR / KULLANABİLİR mi — takımı kendi kapsamıyla işletebilmek. */
    public static boolean canWrite(HttpSession session, Long teamId) {
        return teamId != null && SessionScope.canOperateTeamOwn(session, teamId);
    }

    /** Oturumun görebildiği bağlantılar; {@code teamId} verilirse yalnız o takım (görülemiyorsa boş — çağıran 403 der). */
    public List<DbConnectionProfile> visible(HttpSession session, Long teamId) {
        if (teamId != null) return canRead(session, teamId) ? repo.findByTeamIdOrderByNameAsc(teamId) : List.of();
        if (SessionScope.isGlobalViewer(session)) return repo.findAllByOrderByNameAsc();
        Set<Long> teams = new LinkedHashSet<>();
        if (SessionScope.viewTeamIds(session) != null) teams.addAll(SessionScope.viewTeamIds(session));
        if (SessionScope.manageTeamIds(session) != null) teams.addAll(SessionScope.manageTeamIds(session));
        teams.addAll(SessionScope.memberTeamIds(session));
        Long primary = SessionScope.primaryTeamId(session);
        if (primary != null) teams.add(primary);
        teams.removeIf(Objects::isNull);
        return teams.isEmpty() ? List.of() : repo.findByTeamIdInOrderByNameAsc(teams);
    }

    // ── Yazma ────────────────────────────────────────────────────────────────

    /** Oluşturur; yetki (takım) burada, izin denetleyicide. */
    @Transactional
    public DbConnectionProfile create(Map<String, Object> body, HttpSession session, String actor, String actorName) {
        Long teamId = longOf(body.get("team_id"));
        if (teamId == null) throw DbQueryApiException.field(null, "team_id",
                Msg.t("Takım seçin — bağlantı bir takımın belgesidir.", "Choose a team — a connection belongs to a team."));
        if (!canWrite(session, teamId)) throw new SecurityException(Msg.t(
                "Bu takıma bağlantı ekleme yetkiniz yok.", "You can't add a connection to this team."));
        if (!teamRepo.existsById(teamId)) throw DbQueryApiException.field(null, "team_id",
                Msg.t("Seçilen takım bulunamadı — geçerli bir takım seçin.", "The selected team doesn't exist — choose a valid team."));
        DbConnectionProfile p = new DbConnectionProfile();
        p.setTeamId(teamId);
        apply(p, body, true);
        validate(p, body, true, null);
        lockTeam(teamId);
        if (repo.existsDuplicate(p.getName(), teamId, null)) throw duplicateName();
        String now = ISO.format(Instant.now());
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        p.setCreatedBy(actor);
        p.setCreatedByName(actorName);
        p.setUpdatedBy(actor);
        p.setUpdatedByName(actorName);
        return repo.save(p);
    }

    /**
     * Gövdede GELEN alanları uygular (kısmi PUT). Değişmeyen alan yeniden doğrulanmaz değil — birleşik kayıt BÜTÜN olarak
     * doğrulanır (tür / adres / kimlik birlikte anlamlı); yalnız host değişince ağ politikası (SSRF) yeniden sorulur.
     */
    @Transactional
    public Updated update(Long id, Map<String, Object> body, HttpSession session, String actor, String actorName) {
        DbConnectionProfile p = repo.findById(id).orElseThrow(() -> new java.util.NoSuchElementException(
                Msg.t("Bağlantı bulunamadı; silinmiş olabilir. Listeyi yenileyin.", "The connection wasn't found; it may have been deleted. Refresh the list.")));
        if (!canWrite(session, p.getTeamId())) {
            if (!canRead(session, p.getTeamId())) throw new java.util.NoSuchElementException(
                    Msg.t("Bağlantı bulunamadı; silinmiş olabilir. Listeyi yenileyin.", "The connection wasn't found; it may have been deleted. Refresh the list."));
            throw new SecurityException(Msg.t("Bu takımın bağlantısını düzenleme yetkiniz yok.", "You can't edit this team's connection."));
        }
        DbConnectionProfile before = copyOf(p);
        DbConnectionProfile next = copyOf(p);
        Long newTeam = body.containsKey("team_id") ? longOf(body.get("team_id")) : p.getTeamId();
        if (newTeam == null) newTeam = p.getTeamId();
        List<DbQueryMonitor> users = monitorRepo.findByProfileId(id);
        if (!newTeam.equals(p.getTeamId())) {
            if (!users.isEmpty()) throw inUse(users, Msg.t(
                    "Bu bağlantıyı kullanan izlemeler var; takımı değiştirilemez (izleme ve bağlantı aynı takımda kalmalı). Önce izlemeleri başka bağlantıya geçirin.",
                    "Monitors use this connection, so its team can't change (the monitor and its connection must stay in the same team). Move the monitors to another connection first."));
            if (!canWrite(session, newTeam)) throw new SecurityException(Msg.t(
                    "Bağlantıyı bu takıma taşıma yetkiniz yok.", "You can't move the connection to this team."));
            if (!teamRepo.existsById(newTeam)) throw DbQueryApiException.field(null, "team_id",
                    Msg.t("Seçilen takım bulunamadı — geçerli bir takım seçin.", "The selected team doesn't exist — choose a valid team."));
            next.setTeamId(newTeam);
        }
        apply(next, body, false);
        validate(next, body, false, before);
        requirePasswordForNewTarget(before, next, body);
        if (!Objects.equals(before.getDbType(), next.getDbType()) && !users.isEmpty()) {
            requireMonitorsStillValid(users, DbType.parse(next.getDbType()));
        }
        lockTeam(next.getTeamId());
        if ((!next.getName().equalsIgnoreCase(before.getName()) || !newTeam.equals(before.getTeamId()))
                && repo.existsDuplicate(next.getName(), next.getTeamId(), id)) throw duplicateName();
        copyEditable(next, p);
        p.setUpdatedAt(ISO.format(Instant.now()));
        p.setUpdatedBy(actor);
        p.setUpdatedByName(actorName);
        DbConnectionProfile saved = repo.save(p);
        // Parola varlık kaydıyla YAZILMAZ (updatable = false, inceleme 2026-10-31 D4) — yalnız gerçekten değiştiyse (yeni değer ya
        // da temizleme) hedefli UPDATE; değişmediyse araya giren yeniden şifreleme (anahtar döndürme) korunur.
        if (!Objects.equals(before.getPasswordEnc(), next.getPasswordEnc())) repo.updatePasswordEnc(id, next.getPasswordEnc());
        // r177 (Ö-1): hesap / hedef değişti — eski hesabın yetki özeti bu bağlantıyı TANIMLAMAZ (yazabilen hesap kipi onu okur);
        // bir sonraki "Bağlantıyı dene" yeniden yazar
        if (accountChanged(before, next) && before.getLastPrivilegesJson() != null) {
            repo.recordPrivileges(id, null);
            saved.setLastPrivilegesJson(null);
        }
        return new Updated(before, saved);
    }

    /** Güncelleme sonucu: önceki hâl (denetim farkı) + kaydedilen. */
    public record Updated(DbConnectionProfile before, DbConnectionProfile saved) {}

    /** Kalıcı siler; kullanan izleme varsa 409. Dönüş: silinen kayıt (denetim için). */
    @Transactional
    public DbConnectionProfile delete(Long id, HttpSession session) {
        DbConnectionProfile p = repo.findById(id).orElseThrow(() -> new java.util.NoSuchElementException(
                Msg.t("Bağlantı bulunamadı; silinmiş olabilir. Listeyi yenileyin.", "The connection wasn't found; it may have been deleted. Refresh the list.")));
        if (!canWrite(session, p.getTeamId())) {
            if (!canRead(session, p.getTeamId())) throw new java.util.NoSuchElementException(
                    Msg.t("Bağlantı bulunamadı; silinmiş olabilir. Listeyi yenileyin.", "The connection wasn't found; it may have been deleted. Refresh the list."));
            throw new SecurityException(Msg.t("Bu takımın bağlantısını silme yetkiniz yok.", "You can't delete this team's connection."));
        }
        List<DbQueryMonitor> users = monitorRepo.findByProfileId(id);
        if (!users.isEmpty()) throw inUse(users, Msg.t(
                "Bu bağlantıyı kullanan " + users.size() + " izleme var; önce izlemeleri başka bağlantıya geçirin ya da silin.",
                users.size() + " monitor(s) use this connection; move them to another connection or delete them first."));
        repo.delete(p);
        return p;
    }

    /** Kaydedilmiş bağlantıyı okur (kapsam kontrolüyle); görülemiyorsa 404. */
    public DbConnectionProfile requireReadable(Long id, HttpSession session) {
        DbConnectionProfile p = id == null ? null : repo.findById(id).orElse(null);
        if (p == null || !canRead(session, p.getTeamId())) throw new java.util.NoSuchElementException(
                Msg.t("Bağlantı bulunamadı; silinmiş olabilir ya da görme yetkiniz yok. Listeyi yenileyin.",
                        "The connection wasn't found; it may have been deleted or you can't see it. Refresh the list."));
        return p;
    }

    /** Kaydedilmiş bağlantıyı KULLANMAK (denemek) için okur — yazma kapsamı; görülemiyorsa 404, görülüp kullanılamıyorsa 403. */
    public DbConnectionProfile requireUsable(Long id, HttpSession session) {
        DbConnectionProfile p = requireReadable(id, session);
        if (!canWrite(session, p.getTeamId())) throw new SecurityException(Msg.t(
                "Bu bağlantıyı kullanma yetkiniz yok — yalnız bağlantının takımı kullanabilir.",
                "You can't use this connection — only the connection's team can."));
        return p;
    }

    /**
     * Kaydetmeden deneme taslağı. {@code id} gelirse kayıtlı bağlantı (yazma kapsamı) temel alınır ve gövde üstüne yazılır;
     * parola yalnız gövdede doluysa geçersiz kılınır (yoksa kayıtlı parola). Dönen kopyanın kimliği YOK — deneme sağlık
     * kolonlarına yazmaz (taslağın adresi kayıtlıdan farklı olabilir).
     */
    public Draft draft(Map<String, Object> body, HttpSession session) {
        Long id = longOf(body.get("id"));
        DbConnectionProfile base;
        if (id != null) {
            base = copyOf(requireUsable(id, session));
        } else {
            Long teamId = longOf(body.get("team_id"));
            if (teamId == null) throw DbQueryApiException.field(null, "team_id",
                    Msg.t("Takım seçin — bağlantı bir takımın belgesidir.", "Choose a team — a connection belongs to a team."));
            if (!canWrite(session, teamId)) throw new SecurityException(Msg.t(
                    "Bu takımın adına bağlantı deneme yetkiniz yok.", "You can't test a connection for this team."));
            base = new DbConnectionProfile();
            base.setTeamId(teamId);
        }
        DbConnectionProfile before = id != null ? copyOf(base) : null;
        apply(base, body, id == null);
        if (base.getName() == null || base.getName().isBlank()) base.setName("draft");
        validate(base, body, id == null, before);
        if (before != null) requirePasswordForNewTarget(before, base, body);
        String override = null;
        Object pw = body.get("password");
        if (pw != null && !pw.toString().isEmpty()) override = pw.toString();
        else if (Boolean.TRUE.equals(body.get("password_clear"))) override = "";
        base.setId(null);
        return new Draft(base, override);
    }

    /** Deneme taslağı: kimliksiz bağlantı + (varsa) geçersiz kılan parola. {@code toString} parolayı yazmaz. */
    public record Draft(DbConnectionProfile profile, String passwordOverride) {
        @Override public String toString() { return "Draft[" + (profile == null ? null : profile.getName()) + "]"; }
    }

    // ── API biçimi ───────────────────────────────────────────────────────────

    /** Profile satırı (dbquery-api.md §2) — parola YOK, yalnız {@code password_set}. */
    public static Map<String, Object> toApi(DbConnectionProfile p, String teamName, boolean canEdit,
                                            List<DbQueryMonitor> usedBy, Collection<String> tablesUsed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("team_id", p.getTeamId());
        m.put("team_name", teamName);
        m.put("name", p.getName());
        m.put("db_type", p.getDbType());
        m.put("host", p.getHost());
        m.put("port", p.getPort());
        m.put("database_name", p.getDatabaseName());
        m.put("oracle_mode", p.getOracleMode());
        m.put("mssql_instance", p.getMssqlInstance());
        m.put("username", p.getUsername());
        m.put("password_set", p.getPasswordEnc() != null && !p.getPasswordEnc().isBlank());
        m.put("ssl_mode", p.getSslMode());
        m.put("mssql_encrypt", p.getMssqlEncrypt());
        m.put("mssql_trust_server_certificate", p.getMssqlTrustServerCertificate());
        m.put("oracle_tcps", DbType.ORACLE.name().equals(p.getDbType()) ? Boolean.TRUE.equals(p.getOracleTcps()) : null);
        m.put("connect_timeout_ms", p.getConnectTimeoutMs());
        m.put("description", p.getDescription());
        // r177: bağlantı güvenliği uyarıları (kod listesi kalıcı — ön yüz dbq.conn.sec.<KOD>)
        m.put("security_warnings", securityWarnings(p));
        m.put("created_at", p.getCreatedAt());
        m.put("created_by_name", p.getCreatedByName());
        m.put("updated_at", p.getUpdatedAt());
        m.put("updated_by_name", p.getUpdatedByName());
        m.put("can_edit", canEdit);
        List<DbQueryMonitor> users = usedBy == null ? List.of() : usedBy;
        m.put("health", health(p, !users.isEmpty()));
        List<Map<String, Object>> refs = new ArrayList<>();
        for (DbQueryMonitor u : users) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", u.getId());
            r.put("name", u.getName());
            r.put("active", !Boolean.FALSE.equals(u.getActive()));
            refs.add(r);
        }
        m.put("used_by", refs);
        m.put("tables_used", tablesUsed == null ? List.of() : List.copyOf(tablesUsed));
        return m;
    }

    /**
     * Sağlık özeti. {@code state}: en yeni sinyal (izleme kullanımı ya da elle deneme) başarısızsa {@code failing}; başarılıysa
     * izleme bağı varsa {@code ok}, yoksa {@code unused}; hiç sinyal yoksa {@code untested}. "Bilinmiyor" asla "ok" değildir.
     */
    public static Map<String, Object> health(DbConnectionProfile p, boolean used) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("last_test_at", p.getLastTestAt());
        h.put("last_test_ok", p.getLastTestOk());
        h.put("last_test_ms", p.getLastTestMs());
        h.put("last_test_error", p.getLastTestError());
        h.put("server_version", p.getServerVersion());
        h.put("last_used_at", p.getLastUsedAt());
        h.put("last_ok_at", p.getLastOkAt());
        h.put("last_error_at", p.getLastErrorAt());
        h.put("last_error", p.getLastError());
        h.put("last_duration_ms", p.getLastDurationMs());
        h.put("state", healthState(p, used));
        // r177 (Ö-1): son bağlantı denemesinin hesap yetkisi özeti — {checked, read_only, findings, checked_at}; yoksa null
        h.put("privileges", DbAccountPrivileges.fromJson(p.getLastPrivilegesJson()));
        return h;
    }

    /** Bağlantı güvenliği uyarı kodları (r177, H-3) — kalıcı. */
    public static final String SEC_SSL_PREFER = "SSL_PREFER_DOWNGRADE", SEC_SSL_DISABLED = "SSL_DISABLED",
            SEC_SSL_NOT_VERIFIED = "SSL_NOT_VERIFIED", SEC_MSSQL_UNENCRYPTED = "MSSQL_UNENCRYPTED",
            SEC_MSSQL_TRUST_SERVER_CERT = "MSSQL_TRUST_SERVER_CERT";
    public static final List<String> SECURITY_WARNING_CODES = List.of(SEC_SSL_PREFER, SEC_SSL_DISABLED, SEC_SSL_NOT_VERIFIED,
            SEC_MSSQL_UNENCRYPTED, SEC_MSSQL_TRUST_SERVER_CERT);

    /**
     * Bağlantının TLS zayıflıkları (r177): PostgreSQL {@code prefer} / {@code allow} (ya da boş — eski kayıtların varsayılanı)
     * şifresiz bağlantıya DÜŞEBİLİR; {@code disable} hiç şifrelemez; {@code require} şifreler ama sunucuyu doğrulamaz.
     * MSSQL şifreleme kapalı / sunucu sertifikasına körü körüne güven. Oracle yerel ağ şifrelemesi sunucuda ayarlanır —
     * uyarı üretilmez.
     */
    public static List<String> securityWarnings(DbConnectionProfile p) {
        List<String> out = new ArrayList<>();
        DbType t = DbType.parse(p.getDbType());
        if (t == DbType.POSTGRESQL) {
            String ssl = p.getSslMode() == null ? "prefer" : p.getSslMode().toLowerCase(Locale.ROOT);
            switch (ssl) {
                case "prefer", "allow" -> out.add(SEC_SSL_PREFER);
                case "disable" -> out.add(SEC_SSL_DISABLED);
                case "require" -> out.add(SEC_SSL_NOT_VERIFIED);
                default -> { }
            }
        } else if (t == DbType.MSSQL) {
            if (Boolean.FALSE.equals(p.getMssqlEncrypt())) out.add(SEC_MSSQL_UNENCRYPTED);
            else if (Boolean.TRUE.equals(p.getMssqlTrustServerCertificate())) out.add(SEC_MSSQL_TRUST_SERVER_CERT);
        }
        return out;
    }

    static String healthState(DbConnectionProfile p, boolean used) {
        String okAt = p.getLastOkAt();
        String errAt = p.getLastErrorAt();
        String testAt = p.getLastTestAt();
        // En yeni sinyal: [zaman, başarılı mı]
        String latest = null;
        Boolean latestOk = null;
        if (okAt != null) { latest = okAt; latestOk = true; }
        if (errAt != null && (latest == null || errAt.compareTo(latest) > 0)) { latest = errAt; latestOk = false; }
        if (testAt != null && p.getLastTestOk() != null && (latest == null || testAt.compareTo(latest) > 0)) {
            latest = testAt;
            latestOk = p.getLastTestOk();
        }
        if (latest == null) return "untested";
        if (!latestOk) return "failing";
        return used ? "ok" : "unused";
    }

    /** İzlemelerin sorgularından okunan tablolar (guard AST'si) — birleşik, sıralı, ≤ 100; çözülemeyen sorgu atlanır. */
    public static List<String> tablesUsed(DbConnectionProfile p, List<DbQueryMonitor> users) {
        DbType type = DbType.parse(p.getDbType());
        Set<String> out = new TreeSet<>();
        if (type == null || users == null) return List.of();
        for (DbQueryMonitor m : users) {
            if (m.getSqlText() == null || m.getSqlText().isBlank()) continue;
            try {
                DbQuerySqlGuard.Result r = DbQuerySqlGuard.check(m.getSqlText(), type);
                if (r != null && r.tables() != null) {
                    for (String t : r.tables()) {
                        if (t != null && !t.isBlank()) out.add(t);
                        if (out.size() >= MAX_TABLES_USED) break;
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Bağlantı tablo özeti: izleme {} sorgusu çözümlenemedi: {}", m.getId(), e.getClass().getSimpleName());
            }
            if (out.size() >= MAX_TABLES_USED) break;
        }
        return List.copyOf(out);
    }

    // ── İç ───────────────────────────────────────────────────────────────────

    /** Gövdeyi kayda yazar (yalnız GELEN anahtarlar; oluşturmada varsayılanlar). Doğrulama {@link #validate}'te. */
    void apply(DbConnectionProfile p, Map<String, Object> body, boolean create) {
        if (body.containsKey("name")) p.setName(trimOrNull(body.get("name")));
        if (body.containsKey("db_type")) {
            DbType t = DbType.parse(str(body.get("db_type")));
            p.setDbType(t == null ? str(body.get("db_type")) : t.name());
        }
        if (body.containsKey("host")) p.setHost(hostOf(body.get("host")));
        if (body.containsKey("port")) {
            Object v = body.get("port");
            p.setPort(v == null || v.toString().isBlank() ? null : intOf(v));
        }
        if (body.containsKey("database_name")) p.setDatabaseName(trimOrNull(body.get("database_name")));
        if (body.containsKey("oracle_mode")) p.setOracleMode(upperOrNull(body.get("oracle_mode")));
        if (body.containsKey("mssql_instance")) p.setMssqlInstance(trimOrNull(body.get("mssql_instance")));
        if (body.containsKey("username")) p.setUsername(trimOrNull(body.get("username")));
        if (body.containsKey("ssl_mode")) {
            String s = trimOrNull(body.get("ssl_mode"));
            p.setSslMode(s == null ? null : s.toLowerCase(Locale.ROOT));
        }
        if (body.get("mssql_encrypt") instanceof Boolean b) p.setMssqlEncrypt(b);
        if (body.get("mssql_trust_server_certificate") instanceof Boolean b) p.setMssqlTrustServerCertificate(b);
        boolean tcpsSent = body.get("oracle_tcps") instanceof Boolean;
        if (body.get("oracle_tcps") instanceof Boolean b) p.setOracleTcps(b);
        if (body.containsKey("connect_timeout_ms")) {
            Object v = body.get("connect_timeout_ms");
            p.setConnectTimeoutMs(v == null || v.toString().isBlank() ? null : intOf(v));
        }
        if (body.containsKey("description")) p.setDescription(trimOrNull(body.get("description")));

        DbType type = DbType.parse(p.getDbType());
        if (type != null) {
            // Oracle TCPS (r177): varsayılan port 2484 — port gönderilmediyse / boşsa ve TCPS açıldıysa
            boolean tcps = type == DbType.ORACLE && Boolean.TRUE.equals(p.getOracleTcps());
            if (p.getPort() == null && (create || body.containsKey("port")))
                p.setPort(tcps ? DbJdbcUrls.ORACLE_TCPS_PORT : type.defaultPort());
            else if (tcps && tcpsSent && !body.containsKey("port") && Integer.valueOf(type.defaultPort()).equals(p.getPort()))
                p.setPort(DbJdbcUrls.ORACLE_TCPS_PORT);
            if (p.getConnectTimeoutMs() == null) p.setConnectTimeoutMs(DEFAULT_CONNECT_MS);
            // Türe ait olmayan alanlar temizlenir: başka türün ayarı bağlantıya sızmasın (ör. Oracle kipi PG kaydında).
            if (type != DbType.ORACLE) p.setOracleMode(null);
            else if (p.getOracleMode() == null) p.setOracleMode(DbConnectionProfile.ORACLE_SERVICE);
            if (type != DbType.MSSQL) {
                p.setMssqlInstance(null);
                p.setMssqlEncrypt(null);
                p.setMssqlTrustServerCertificate(null);
            } else {
                if (p.getMssqlEncrypt() == null) p.setMssqlEncrypt(true);
                if (p.getMssqlTrustServerCertificate() == null) p.setMssqlTrustServerCertificate(false);
            }
            if (type != DbType.ORACLE) p.setOracleTcps(null);
            else if (p.getOracleTcps() == null) p.setOracleTcps(false);
            if (type != DbType.POSTGRESQL) p.setSslMode(null);
            // r177 (H-3): YENİ bağlantının varsayılanı "require" (şifresiz olmaz); var olan "prefer" kayıtları korunur
            // (sağlık panelinde "şifresiz bağlantıya düşebilir" uyarısı — securityWarnings)
            else if (p.getSslMode() == null) p.setSslMode(create ? "require" : "prefer");
        }
        // Parola — yalnız değişince (boş / gelmeyen = korunur); temizleme açıkça istenir.
        Object pw = body.get("password");
        if (pw != null && !pw.toString().isEmpty()) {
            String plain = pw.toString();
            if (plain.length() > MAX_PASSWORD || plain.indexOf('\0') >= 0) {
                throw DbQueryApiException.field(null, "password", Msg.t(
                        "Parola en çok " + MAX_PASSWORD + " karakter olabilir ve görünmez denetim karakteri içeremez.",
                        "The password can be at most " + MAX_PASSWORD + " characters and can't contain control characters."));
            }
            p.setPasswordEnc(secretCipher.encrypt(plain));
        } else if (Boolean.TRUE.equals(body.get("password_clear"))) {
            p.setPasswordEnc(null);
        }
    }

    /** Birleşik kaydı BÜTÜN olarak doğrular; hatalar alan alan toplanır. {@code before} null = oluşturma (SSRF hep sorulur). */
    void validate(DbConnectionProfile p, Map<String, Object> body, boolean create, DbConnectionProfile before) {
        Map<String, String> errs = new LinkedHashMap<>();
        if (p.getName() == null || p.getName().isBlank()) {
            errs.put("name", Msg.t("Bağlantıya bir ad verin.", "Give the connection a name."));
        } else if (p.getName().length() > MAX_NAME || hasControl(p.getName())) {
            errs.put("name", Msg.t("Ad en çok " + MAX_NAME + " karakter olabilir ve denetim karakteri içeremez.",
                    "The name can be at most " + MAX_NAME + " characters and can't contain control characters."));
        }
        DbType type = DbType.parse(p.getDbType());
        if (type == null) {
            errs.put("db_type", Msg.t("Veritabanı türü seçin: Oracle, Microsoft SQL Server ya da PostgreSQL.",
                    "Choose a database type: Oracle, Microsoft SQL Server or PostgreSQL."));
        }
        String host = p.getHost();
        if (host == null || host.isBlank()) {
            errs.put("host", Msg.t("Sunucu adını ya da IP adresini girin.", "Enter the server name or IP address."));
        } else if (!validHostSyntax(host)) {
            errs.put("host", Msg.t("Sunucu yalnız ad (ör. db01.sirket.local) ya da IP adresi olabilir — şema, yol, port, boşluk ya da ';' olmadan.",
                    "The server can only be a name (e.g. db01.company.local) or an IP address — without a scheme, path, port, spaces or ';'."));
        } else if (before == null || !host.equalsIgnoreCase(before.getHost())) {
            String blocked = ssrfBlock(host);
            if (blocked != null) errs.put("host", blocked);
        }
        if (p.getPort() == null || p.getPort() < 1 || p.getPort() > 65535) {
            errs.put("port", Msg.t("Port 1–65535 arasında bir sayı olmalı.", "The port must be a number between 1 and 65535."));
        }
        String db = p.getDatabaseName();
        if (db == null || db.isBlank()) {
            if (type == DbType.ORACLE) errs.put("database_name", Msg.t("Oracle servis adını ya da SID'yi girin.", "Enter the Oracle service name or SID."));
            else if (type == DbType.POSTGRESQL) errs.put("database_name", Msg.t("Veritabanı adını girin.", "Enter the database name."));
        } else if (!DATABASE.matcher(db).matches()) {
            errs.put("database_name", Msg.t("Veritabanı adı yalnız harf, rakam ve _ . $ # - içerebilir (en çok 128 karakter).",
                    "The database name can only contain letters, digits and _ . $ # - (at most 128 characters)."));
        }
        if (type == DbType.ORACLE && p.getOracleMode() != null
                && !DbConnectionProfile.ORACLE_SERVICE.equals(p.getOracleMode()) && !DbConnectionProfile.ORACLE_SID.equals(p.getOracleMode())) {
            errs.put("oracle_mode", Msg.t("Oracle bağlantı kipi SERVICE ya da SID olmalı.", "The Oracle connection mode must be SERVICE or SID."));
        }
        if (type == DbType.MSSQL && p.getMssqlInstance() != null && !INSTANCE.matcher(p.getMssqlInstance()).matches()) {
            errs.put("mssql_instance", Msg.t("Örnek adı yalnız harf, rakam ve _ $ # - içerebilir (en çok 64 karakter).",
                    "The instance name can only contain letters, digits and _ $ # - (at most 64 characters)."));
        }
        String user = p.getUsername();
        if (user == null || user.isBlank()) {
            errs.put("username", Msg.t("Kullanıcı adını girin (yalnız okuma yetkili bir hesap önerilir).",
                    "Enter the user name (an account with read-only rights is recommended)."));
        } else if (user.length() > MAX_USERNAME || hasControl(user) || user.indexOf(';') >= 0
                || user.indexOf('{') >= 0 || user.indexOf('}') >= 0) {
            errs.put("username", Msg.t("Kullanıcı adı en çok " + MAX_USERNAME + " karakter olabilir; ';', '{', '}' ve denetim karakteri içeremez.",
                    "The user name can be at most " + MAX_USERNAME + " characters and can't contain ';', '{', '}' or control characters."));
        }
        if (type == DbType.POSTGRESQL && p.getSslMode() != null && !PG_SSL_MODES.contains(p.getSslMode())) {
            errs.put("ssl_mode", Msg.t("SSL kipi disable, prefer, require ya da verify-full olmalı.",
                    "The SSL mode must be disable, prefer, require or verify-full."));
        }
        Integer ct = p.getConnectTimeoutMs();
        if (ct == null || ct < MIN_CONNECT_MS || ct > MAX_CONNECT_MS) {
            errs.put("connect_timeout_ms", Msg.t("Bağlanma zaman aşımı 1000–30000 ms arasında olmalı.",
                    "The connect timeout must be between 1000 and 30000 ms."));
        }
        if (p.getDescription() != null && p.getDescription().length() > MAX_DESCRIPTION) {
            errs.put("description", Msg.t("Açıklama en çok " + MAX_DESCRIPTION + " karakter olabilir.",
                    "The description can be at most " + MAX_DESCRIPTION + " characters."));
        }
        if (!errs.isEmpty()) {
            throw DbQueryApiException.validation(Msg.t("Bağlantı kaydedilemedi: işaretli alanları düzeltin.",
                    "The connection couldn't be saved: fix the marked fields."), errs);
        }
    }

    /**
     * Kayıtlı parola YENİ bir hedefe gitmez (güvenlik incelemesi 2026-10-31, YÜKSEK): bağlantının hedefini belirleyen alanlardan
     * biri ({@link #targetChanges}) ya da güvenliği gevşeten bir ayar değiştiyse ve gövdede YENİ parola yoksa 400 (alan
     * {@code password}) — kayıt yapılmaz, deneme çalışmaz. Aksi hâlde düzenleme yetkili biri sunucu adını kendi makinesine
     * çevirip "Bağlantıyı dene" diyerek arayüzün hiç göstermediği kayıtlı parolayı o makineye gönderebilirdi. Takım değişimi de
     * aynı kuraldadır (kayıtlı parola yeni takımın üyelerine açılmaz). Parolasız bağlantıda, {@code password_clear: true} ile
     * parola silinirken ve yalnız ad / açıklama / zaman aşımı değişince kural tetiklenmez.
     */
    static void requirePasswordForNewTarget(DbConnectionProfile before, DbConnectionProfile next, Map<String, Object> body) {
        if (before == null || before.getPasswordEnc() == null || before.getPasswordEnc().isBlank()) return;
        Object pw = body.get("password");
        if (pw != null && !pw.toString().isEmpty()) return;          // yeni parola girildi — o kullanılır
        if (Boolean.TRUE.equals(body.get("password_clear"))) return; // parola siliniyor — kayıtlı parola gitmez
        List<String> changed = targetChanges(before, next);
        // Takım değişimi de yeni bir KİTLE (inceleme 2026-10-31, D3): iki takımın da üyesi olan biri A'nın kimliğini (parolasını
        // bilmeden) B'nin tüm üyelerinin kullanımına açabiliyordu — kayıtlı parola yeni takıma taşınmaz, yeniden girilir.
        boolean teamChanged = !Objects.equals(before.getTeamId(), next.getTeamId());
        if (changed.isEmpty() && !teamChanged) return;
        String msg = changed.isEmpty() ? Msg.t(
                "Bağlantı başka bir takıma taşınıyor — güvenlik için parolayı yeniden girin (kayıtlı parola yeni takımın kullanımına açılmaz).",
                "The connection is moving to another team — re-enter the password for security (the saved password isn't handed to the new team).")
                : Msg.t(
                "Bağlantının adresi / kimliği değişti — güvenlik için parolayı yeniden girin (kayıtlı parola yeni hedefe gönderilmez).",
                "The connection's address / identity changed — re-enter the password for security (the saved password isn't sent to a new target).");
        Map<String, String> f = new LinkedHashMap<>();
        f.put("password", msg);
        throw DbQueryApiException.validation(msg, f);
    }

    /**
     * Hesabın / hedefin kimliği değişti mi (r177): tür, sunucu (büyük-küçük harf duyarsız), port, veritabanı, kullanıcı adı ya
     * da parola. Saklı yetki özeti yalnız aynı hesap için geçerlidir.
     */
    static boolean accountChanged(DbConnectionProfile before, DbConnectionProfile next) {
        return !Objects.equals(before.getDbType(), next.getDbType())
                || !String.valueOf(before.getHost()).equalsIgnoreCase(String.valueOf(next.getHost()))
                || !Objects.equals(before.getPort(), next.getPort())
                || !Objects.equals(before.getDatabaseName(), next.getDatabaseName())
                || !Objects.equals(before.getUsername(), next.getUsername())
                || !Objects.equals(before.getPasswordEnc(), next.getPasswordEnc());
    }

    /**
     * Hedefi belirleyen ya da güvenliği gevşeten alanlardan kayıtlı değerden farklı olanlar (adları; boş = değişiklik yok).
     * Hedef: tür, sunucu (büyük-küçük harf duyarsız), port, veritabanı, Oracle kipi, MSSQL örneği, kullanıcı adı. Gevşetme:
     * PostgreSQL SSL kipinin daha zayıfa inmesi, MSSQL şifrelemenin kapanması, sunucu sertifikasına güvenin açılması.
     */
    public static List<String> targetChanges(DbConnectionProfile before, DbConnectionProfile next) {
        List<String> out = new ArrayList<>();
        if (!Objects.equals(before.getDbType(), next.getDbType())) out.add("db_type");
        if (!(before.getHost() == null ? next.getHost() == null : before.getHost().equalsIgnoreCase(next.getHost()))) out.add("host");
        if (!Objects.equals(before.getPort(), next.getPort())) out.add("port");
        if (!Objects.equals(before.getDatabaseName(), next.getDatabaseName())) out.add("database_name");
        if (!Objects.equals(before.getOracleMode(), next.getOracleMode())) out.add("oracle_mode");
        if (!Objects.equals(before.getMssqlInstance(), next.getMssqlInstance())) out.add("mssql_instance");
        if (!Objects.equals(before.getUsername(), next.getUsername())) out.add("username");
        if (sslRank(next.getSslMode()) < sslRank(before.getSslMode())) out.add("ssl_mode");
        if (!Boolean.FALSE.equals(before.getMssqlEncrypt()) && Boolean.FALSE.equals(next.getMssqlEncrypt())) out.add("mssql_encrypt");
        if (!Boolean.TRUE.equals(before.getMssqlTrustServerCertificate()) && Boolean.TRUE.equals(next.getMssqlTrustServerCertificate())) {
            out.add("mssql_trust_server_certificate");
        }
        // Oracle TCPS kapatıldı (r177): şifreli bağlantı düz TCP'ye iniyor — kayıtlı parola düz metin hatta gönderilmez
        if (Boolean.TRUE.equals(before.getOracleTcps()) && !Boolean.TRUE.equals(next.getOracleTcps())) out.add("oracle_tcps");
        return out;
    }

    /** PostgreSQL SSL kipinin gücü (disable 0 < prefer 1 < require 2 < verify-full 3); boş / bilinmeyen = prefer (varsayılan). */
    static int sslRank(String mode) {
        if (mode == null) return 1;
        int i = PG_SSL_MODES.indexOf(mode.toLowerCase(Locale.ROOT));
        return i < 0 ? 1 : i;
    }

    /** Tür değişince kullanan her izlemenin sorgusu yeni türle geçerli olmalı (ör. MSSQL'de NOLOCK zorunlu). */
    private void requireMonitorsStillValid(List<DbQueryMonitor> users, DbType type) {
        List<String> broken = new ArrayList<>();
        for (DbQueryMonitor m : users) {
            DbQuerySqlGuard.Result r = DbQuerySqlGuard.check(m.getSqlText(), type);
            if (r == null || !r.ok()) broken.add(m.getName());
        }
        if (!broken.isEmpty()) {
            String head = String.join(", ", broken.subList(0, Math.min(5, broken.size()))) + (broken.size() > 5 ? " …" : "");
            throw DbQueryApiException.field(null, "db_type", Msg.t(
                    "Veritabanı türü değiştirilemez: şu izlemelerin sorgusu yeni türün güvenlik kurallarından geçmiyor: " + head
                            + ". Önce sorguları düzeltin ya da izlemeleri başka bağlantıya geçirin.",
                    "The database type can't change: these monitors' queries don't pass the new type's safety rules: " + head
                            + ". Fix the queries or move the monitors to another connection first."));
        }
    }

    /** Ağ politikası reddi metni (null = izinli). Çözülemeyen ad da reddedilir: bağlantı bu pod'dan kurulamaz. */
    private String ssrfBlock(String host) {
        try {
            ssrfGuard.validate(host);
            return null;
        } catch (SsrfGuard.UnresolvableHostException e) {
            return Msg.t("Sunucu adı çözümlenemedi (DNS): '" + host + "'. Adı denetleyin ya da IP adresi girin.",
                    "The server name couldn't be resolved (DNS): '" + host + "'. Check the name or enter an IP address.");
        } catch (SsrfGuard.BlockedException e) {
            return Msg.t("Bu sunucuya bağlanmaya izin verilmiyor (ağ politikası: bulut meta veri, döngü ya da yasak ağ). Başka bir sunucu girin ya da bir global yöneticiye başvurun.",
                    "Connecting to this server isn't allowed (network policy: cloud metadata, loopback or a blocked network). Enter another server or ask a global administrator.");
        }
    }

    static boolean validHostSyntax(String host) {
        if (host == null || host.isEmpty() || host.length() > MAX_HOST) return false;
        if (host.indexOf(':') >= 0) return IPV6.matcher(host).matches();
        return HOSTNAME.matcher(host).matches() && !host.contains("..");
    }

    /** Köşeli parantezli IPv6 girdisi düz yazılır; boşluk kırpılır. */
    private static String hostOf(Object raw) {
        String s = trimOrNull(raw);
        if (s == null) return null;
        if (s.length() > 2 && s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        return s;
    }

    private void lockTeam(Long teamId) {
        if (jdbc == null || teamId == null) return;
        try {
            jdbc.queryForList("SELECT id FROM teams WHERE id = ? FOR UPDATE", Long.class, teamId);
        } catch (RuntimeException e) {
            log.debug("Bağlantı yazımı: takım satırı kilitlenemedi: {}", e.getClass().getSimpleName());
        }
    }

    private static DbQueryApiException duplicateName() {
        return DbQueryApiException.conflict("DUPLICATE_RECORD", "name", Msg.t(
                "Bu takımda aynı adlı bir bağlantı zaten var; başka bir ad seçin.",
                "This team already has a connection with that name; choose another name."), null);
    }

    private static DbQueryApiException inUse(List<DbQueryMonitor> users, String message) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (DbQueryMonitor u : users) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", u.getId());
            r.put("name", u.getName());
            refs.add(r);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("monitors", refs);
        return DbQueryApiException.conflict("DBQUERY_CONNECTION_IN_USE", null, message, extra);
    }

    static DbConnectionProfile copyOf(DbConnectionProfile p) {
        DbConnectionProfile c = new DbConnectionProfile();
        BeanUtils.copyProperties(p, c);
        return c;
    }

    /**
     * Düzenlenebilir kolonları yönetilen kayda aktarır (sağlık ve künye kolonlarına dokunulmaz). Parola da aktarılır (yanıt
     * {@code password_set} doğru olsun) ama varlık kaydıyla YAZILMAZ ({@code updatable = false}); değişimi {@link #update} hedefli
     * yazar.
     */
    private static void copyEditable(DbConnectionProfile from, DbConnectionProfile to) {
        to.setTeamId(from.getTeamId());
        to.setName(from.getName());
        to.setDbType(from.getDbType());
        to.setHost(from.getHost());
        to.setPort(from.getPort());
        to.setDatabaseName(from.getDatabaseName());
        to.setOracleMode(from.getOracleMode());
        to.setMssqlInstance(from.getMssqlInstance());
        to.setUsername(from.getUsername());
        to.setPasswordEnc(from.getPasswordEnc());
        to.setSslMode(from.getSslMode());
        to.setMssqlEncrypt(from.getMssqlEncrypt());
        to.setMssqlTrustServerCertificate(from.getMssqlTrustServerCertificate());
        to.setOracleTcps(from.getOracleTcps());
        to.setConnectTimeoutMs(from.getConnectTimeoutMs());
        to.setDescription(from.getDescription());
    }

    /** Denetim ayrıntısı için parolasız özet (parola ASLA — yalnız "değişti" bilgisi). */
    public static Map<String, Object> auditView(DbConnectionProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (p == null) return m;
        m.put("name", p.getName());
        m.put("team_id", p.getTeamId());
        m.put("db_type", p.getDbType());
        m.put("host", p.getHost());
        m.put("port", p.getPort());
        m.put("database_name", p.getDatabaseName());
        m.put("oracle_mode", p.getOracleMode());
        m.put("mssql_instance", p.getMssqlInstance());
        m.put("username", p.getUsername());
        m.put("ssl_mode", p.getSslMode());
        m.put("mssql_encrypt", p.getMssqlEncrypt());
        m.put("mssql_trust_server_certificate", p.getMssqlTrustServerCertificate());
        m.put("oracle_tcps", p.getOracleTcps());
        m.put("connect_timeout_ms", p.getConnectTimeoutMs());
        m.put("description", p.getDescription());
        return m;
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) return true;
        }
        return false;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static String trimOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String upperOrNull(Object o) {
        String s = trimOrNull(o);
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    static Long longOf(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return null;
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return -1;   // aralık denetimi yakalar
        }
    }
}

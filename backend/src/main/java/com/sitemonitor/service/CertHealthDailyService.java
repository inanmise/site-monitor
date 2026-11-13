package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Sertifika sağlığının 90 günlük serisi (2026-11-13, İstatistikler #12) — {@code cert_health_daily}'yi yazar ve okur.
 *
 * <p>Yazım ({@link CertHealthSnapshotJob}, saatlik tık; günün kurum satırı varsa iş yok): aktif envanter (tek dar sorgu) ×
 * son kontroller (Pano ile paylaşılan {@code getAllLatest} önbelleği) {@link CertHealthCounts} kuralıyla sayılır — kurum
 * ({@code team_key = 0}) her sertifikayı bir kez, takım kovası sertifikayı hem SY hem UG takımında sayar. {@value #KEEP_DAYS}
 * günden eski satır her yazımda silinir.
 *
 * <p>Okuma: kurum serisi yalnız tüm izlemeyi gören kullanıcıya; kapsamlı kullanıcı görebildiği tek bir takımın serisini görür
 * (takımlar asla toplanmaz). Veri dağıtımdan itibaren birikir — yanıt {@code collecting_since} (o kovanın ilk günü) taşır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertHealthDailyService {

    public static final int KEEP_DAYS = 120;
    public static final int MAX_DAYS = 90;
    public static final long ORG_KEY = 0L;
    static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    static final String SQL_EXISTS = "SELECT COUNT(*) FROM cert_health_daily WHERE snap_day = ? AND team_key = 0";
    static final String SQL_INSERT = "INSERT INTO cert_health_daily(snap_day, team_key, total, evaluated, ok, under30, under7, "
            + "expired, error_count, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    static final String SQL_PRUNE = "DELETE FROM cert_health_daily WHERE snap_day < ?";
    static final String SQL_SERIES = "SELECT snap_day, total, evaluated, ok, under30, under7, expired, error_count "
            + "FROM cert_health_daily WHERE team_key = ? AND snap_day >= ? ORDER BY snap_day";
    static final String SQL_FIRST = "SELECT MIN(snap_day) FROM cert_health_daily WHERE team_key = ?";

    private final JdbcTemplate jdbc;
    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final TeamRepository teamRepo;

    /** Son kontroller — Pano önbelleği; yoksa (dilimli test) {@code latest_checks}. */
    @Autowired(required = false)
    private CertificateService certService;

    // ── Yazım ────────────────────────────────────────────────────────────────────────────────────

    /** Bugünün kurum satırı yazılmış mı (tek küçük sorgu; iş kilit almadan çıkar). */
    public boolean snapshotWritten(Instant now) {
        Integer n = jdbc.queryForObject(SQL_EXISTS, Integer.class, LocalDate.ofInstant(now, IST).toString());
        return n != null && n > 0;
    }

    /** Bugünün görüntüsünü yaz (gün × kova tek satır; ikinci yazım no-op) ve eskiyi sil. Dönüş: yazılmaya çalışılan satır. */
    public int writeDailySnapshot(Instant now) {
        String day = LocalDate.ofInstant(now, IST).toString();
        Map<Long, CertHealthCounts> buckets = counts();
        String created = ISO.format(now);
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<Long, CertHealthCounts> e : buckets.entrySet()) {
            CertHealthCounts c = e.getValue();
            rows.add(new Object[]{day, e.getKey(), c.total(), c.evaluated(), c.ok(), c.under30(), c.under7(), c.expired(), c.error(), created});
        }
        jdbc.batchUpdate(SQL_INSERT, rows);
        int pruned = jdbc.update(SQL_PRUNE, LocalDate.ofInstant(now, IST).minusDays(KEEP_DAYS).toString());
        log.info("Sertifika sağlığı günlük görüntüsü: {} kova yazıldı ({}), {} eski satır silindi", rows.size(), day, pruned);
        return rows.size();
    }

    /** Kova → sayaç: 0 = kurum (her sertifika bir kez), takım = SY ∪ UG (aynı takım bir kez). */
    Map<Long, CertHealthCounts> counts() {
        Map<String, Object[]> latest = new HashMap<>();   // alan → [status, days]
        if (certService != null) {
            for (com.sitemonitor.dto.CertificateDto c : certService.getAllLatest()) {
                if (c.getDomain() != null) latest.put(c.getDomain(), new Object[]{c.getStatus(), c.getDaysRemaining()});
            }
        } else {
            latestCheckRepo.findAll().forEach(c -> { if (c.getDomain() != null) latest.put(c.getDomain(), new Object[]{c.getStatus(), c.getDaysRemaining()}); });
        }
        Map<Long, CertHealthCounts> out = new LinkedHashMap<>();
        CertHealthCounts org = new CertHealthCounts();
        out.put(ORG_KEY, org);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object[] r : inventoryRepo.findActiveDomainTeams()) {
            if (r == null || r.length < 2 || r[0] == null) continue;
            String d = String.valueOf(r[0]);
            if (!seen.add(d)) continue;
            Long sy = r[1] instanceof Number n ? Long.valueOf(n.longValue()) : null;
            Long ug = r.length > 2 && r[2] instanceof Number n2 ? Long.valueOf(n2.longValue()) : null;
            Object[] lc = latest.get(d);
            boolean has = lc != null;
            String status = has ? (String) lc[0] : null;
            Integer days = has ? (Integer) lc[1] : null;
            org.add(has, status, days);
            if (sy != null) out.computeIfAbsent(sy, k -> new CertHealthCounts()).add(has, status, days);
            if (ug != null && !ug.equals(sy)) out.computeIfAbsent(ug, k -> new CertHealthCounts()).add(has, status, days);
        }
        return out;
    }

    // ── Okuma ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Seri. {@code teamId} null → tüm izlemeyi görene kurum, kapsamlıya ilk görünür (ada göre) takım. Çağıran {@code teamId}'nin
     * görülebilirliğini denetlemiş olmalı. Okuma hatası fırlar (arayüz "okunamadı" der, asla boş seri).
     */
    public Map<String, Object> series(int days, Long teamId, boolean global, Predicate<Long> canViewTeam) {
        int d = Math.max(7, Math.min(MAX_DAYS, days));
        List<Map<String, Object>> teams = new ArrayList<>();
        for (Team t : teamRepo.findByActiveTrueOrderByNameAsc()) {
            if (t.getId() == null || !(global || canViewTeam.test(t.getId()))) continue;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", t.getId()); o.put("name", t.getName());
            teams.add(o);
        }
        Long key;
        if (teamId != null) key = teamId;
        else if (global) key = ORG_KEY;
        else key = teams.isEmpty() ? null : (Long) teams.get(0).get("id");
        Map<String, Object> out = new LinkedHashMap<>();
        String today = LocalDate.now(IST).toString();
        out.put("days", d);
        out.put("org_allowed", global);
        out.put("team_id", key == null || key == ORG_KEY ? null : key);
        out.put("scope", key == null ? "none" : key == ORG_KEY ? "org" : "team");
        out.put("teams", teams);
        out.put("today", today);
        List<Map<String, Object>> points = new ArrayList<>();
        String since = null;
        if (key != null) {
            String from = LocalDate.now(IST).minusDays(d - 1L).toString();
            jdbc.query(SQL_SERIES, rs -> {
                Map<String, Object> p = new LinkedHashMap<>();
                int total = rs.getInt(2), evaluated = rs.getInt(3), ok = rs.getInt(4);
                p.put("day", rs.getString(1));
                p.put("total", total); p.put("evaluated", evaluated); p.put("ok", ok);
                p.put("under30", rs.getInt(5)); p.put("under7", rs.getInt(6)); p.put("expired", rs.getInt(7)); p.put("error", rs.getInt(8));
                p.put("health_pct", evaluated == 0 ? null : Math.round(1000.0 * ok / evaluated) / 10.0);
                points.add(p);
            }, key, from);
            since = jdbc.queryForObject(SQL_FIRST, String.class, key);
        }
        out.put("points", points);
        out.put("collecting_since", since);
        return out;
    }
}

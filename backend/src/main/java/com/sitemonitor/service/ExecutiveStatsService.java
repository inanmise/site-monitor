package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;

/**
 * İstatistik sayfası yönetici özeti (2026-09-12, zenginleştirme #20): dört KPI (filo sağlığı %,
 * 30 gün altı, açık alarm, SLA ihlali) + takım karşılaştırması + son 30 gün / önceki 30 gün delta
 * (açılan alarm). Kapsam çağıranın predicate'i. Her blok try/catch'li; düşen blok yalnız {@code error_msg} taşır
 * (arayüz "okunamadı" der, asla 0). 2026-11-13: bellek (kapsam × alarm izni), takım satırı SY ∪ UG, alarm bloğu yalnız
 * {@code alerts.read} ile, SLA ortak önbellekten ({@code availabilityAll} + {@code subset}) + en kötü 5'in adı ve takım kırılımı.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutiveStatsService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final int WINDOW_DAYS = CertHealthCounts.WINDOW_DAYS;

    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final AlertEventRepository alertEventRepo;
    private final TeamRepository teamRepo;
    private final MonitorSparklineService sparklineService;
    private final AppSettingsService appSettings;
    private final com.sitemonitor.repository.CertificateCheckRepository certificateCheckRepo;

    /**
     * "Son N günde ne değişti" belleği (2026-10-09, performans): Pano bu ucu her kullanıcı için 5 dakikada bir yokluyor ve
     * her çağrı tüm envanteri + 7 günlük kontrol/alarm satırlarını (yüz binlerce satır) tarıyordu. Görüş kapsamı başına
     * {@code site.monitor.stats-changes.cache-ms}; 0 → kapalı (birim testinde {@code new} ile kurulunca varsayılan).
     */
    @org.springframework.beans.factory.annotation.Value("${site.monitor.stats-changes.cache-ms:120000}")
    long changesCacheMs;
    private final com.sitemonitor.util.TtlMemo<Map<String, Object>> changesMemo = new com.sitemonitor.util.TtlMemo<>(500);

    /**
     * "Silinen alan" sayacının kaynağı (2026-10-07): envanter silmesi KALICI — satır gidiyor, {@code deleted_at} ile
     * sayılamaz; ürün geçmişindeki {@code INVENTORY/DELETE} satırlarından sayılır. Alan enjeksiyonu (kurucu değişmesin);
     * yokken (dilimli test) sayaç 0.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.MonitorChangeLogRepository changeLogRepo;

    /**
     * Yönetici özeti belleği (2026-11-13, performans): istatistik sayfası her kullanıcı için 5 dakikada bir ve her "Yenile"de
     * yokluyor; özet tüm envanteri, açık alarmları ve 9 türün SLA'sını okuyor. Görüş kapsamı + alarm izni başına
     * {@code site.monitor.stats-executive.cache-ms}; {@code fresh} en fazla {@value #FRESH_MIN_MS} ms'de bir yeniden hesaplar.
     * 0 → kapalı (birim testinde {@code new} ile kurulunca varsayılan).
     */
    @org.springframework.beans.factory.annotation.Value("${site.monitor.stats-executive.cache-ms:60000}")
    long execCacheMs;
    static final long FRESH_MIN_MS = 5_000L;
    private final com.sitemonitor.util.TtlMemo<Map<String, Object>> execMemo = new com.sitemonitor.util.TtlMemo<>(500);

    /**
     * Son kontrollerin kaynağı (2026-11-13, performans): {@code getAllLatest} önbelleği (Pano ile paylaşılan, ısıtılan liste) —
     * her çağrıda {@code latest_checks.findAll()} okunmaz. Alan enjeksiyonu; yokken (dilimli test) eski okuma.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CertificateService certService;

    /** Eski imza — bellek yok, alarm bloğu dahil (testler ve iç çağıranlar). */
    public Map<String, Object> build(Predicate<Long> canViewTeam) {
        return compute(canViewTeam, true);
    }

    /**
     * Bellekli sürüm. {@code scopeKey} görüş kapsamını TAM belirler (aynı kapsamı gören kullanıcılar aynı sonucu paylaşır);
     * {@code alertsVisible} = {@code alerts.read/view} izni — yoksa alarm bloğu hiç okunmaz ({@code alerts.visible=false}).
     */
    public Map<String, Object> build(Predicate<Long> canViewTeam, boolean alertsVisible, String scopeKey, boolean fresh) {
        long ttl = fresh && execCacheMs > 0 ? Math.min(execCacheMs, FRESH_MIN_MS) : execCacheMs;
        String key = scopeKey == null ? null : scopeKey + "|a" + (alertsVisible ? 1 : 0);
        return execMemo.get(key, ttl, false, () -> compute(canViewTeam, alertsVisible));
    }

    /** Son kontrol özeti — alan adı → (durum, kalan gün). */
    private record Latest(String status, Integer days) { }

    private Map<String, Latest> latestByDomain() {
        Map<String, Latest> out = new HashMap<>();
        if (certService != null) {
            for (com.sitemonitor.dto.CertificateDto c : certService.getAllLatest()) {
                if (c.getDomain() != null) out.put(c.getDomain(), new Latest(c.getStatus(), c.getDaysRemaining()));
            }
            return out;
        }
        latestCheckRepo.findAll().forEach(c -> { if (c.getDomain() != null) out.put(c.getDomain(), new Latest(c.getStatus(), c.getDaysRemaining())); });
        return out;
    }

    /** Takım satırı biriktiricisi: sertifika sayacı + açık alarm. */
    private static final class TeamAcc {
        final CertHealthCounts certs = new CertHealthCounts();
        int openAlerts;
    }

    private Map<String, Object> compute(Predicate<Long> canViewTeam, boolean alertsVisible) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<Long, String> teamNames = new LinkedHashMap<>();
        try { teamRepo.findAll().forEach(t -> teamNames.put(t.getId(), t.getName())); } catch (Exception ignored) { }

        // ── Sertifikalar ─────────────────────────────────────────────────────────────────────
        // Takım satırı SY ∪ UG (2026-11-13): sertifika hem sorumlu hem uygulama takımının satırında sayılır — tablonun takım
        // süzgeci (statsModel.resolveTeam) ile aynı kural. Kurum toplamı her sertifikayı BİR kez sayar.
        Map<String, Object> certs = new LinkedHashMap<>();
        Map<Long, TeamAcc> perTeam = new LinkedHashMap<>();   // anahtar takım; -1 = takımsız
        Set<String> visibleDomains = new HashSet<>();
        Map<String, Long[]> domainTeams = new HashMap<>();   // alan → [SY, UG]
        try {
            Map<String, Latest> lc = latestByDomain();
            CertHealthCounts org = new CertHealthCounts();
            for (Object[] r : inventoryRepo.findActiveDomainTeams()) {
                if (r == null || r.length < 2 || r[0] == null) continue;
                String domain = String.valueOf(r[0]);
                Long sy = r[1] instanceof Number n ? Long.valueOf(n.longValue()) : null;
                Long ug = r.length > 2 && r[2] instanceof Number n2 ? Long.valueOf(n2.longValue()) : null;
                // SY ya da UG takımı görülebilen kayıt (2026-10-22, ALERTS-3 / DATA-5)
                if (!(canViewTeam.test(sy) || (ug != null && canViewTeam.test(ug)))) continue;
                if (!visibleDomains.add(domain)) continue;
                domainTeams.put(domain, new Long[]{sy, ug});
                Latest c = lc.get(domain);
                boolean has = c != null;
                String status = has ? c.status() : null;
                Integer days = has ? c.days() : null;
                org.add(has, status, days);
                for (Long key : teamKeys(sy, ug, canViewTeam)) perTeam.computeIfAbsent(key, k -> new TeamAcc()).certs.add(has, status, days);
            }
            certs.putAll(org.toMap());
        } catch (Exception e) { log.debug("executive: sertifika bloğu düştü: {}", e.toString()); certs.put("error_msg", e.getClass().getSimpleName()); }
        out.put("certs", certs);

        // ── Alarmlar: açık + son 30 gün / önceki 30 gün açılan (yalnız alerts.read) ───────────────
        Map<String, Object> alerts = new LinkedHashMap<>();
        if (!alertsVisible) {
            alerts.put("visible", false);
        } else {
            try {
                int open = 0, critical = 0;
                for (Object[] r : alertEventRepo.findOpenStatRows()) {
                    Long team = r[0] instanceof Number n ? Long.valueOf(n.longValue()) : null;
                    String domain = r[1] == null ? null : String.valueOf(r[1]);
                    if (!visibleAlert(team, domain, canViewTeam, visibleDomains)) continue;
                    open++;
                    if ("CRITICAL".equalsIgnoreCase(r[2] == null ? null : String.valueOf(r[2]))) critical++;
                    // Sahip takım(lar): damga; damga yoksa envanterin SY + UG'si (alarm sahipliği kuralı)
                    Long[] inv = domain == null ? null : domainTeams.get(domain);
                    Set<Long> owners = new java.util.LinkedHashSet<>();
                    if (team != null) owners.add(team);
                    else if (inv != null) owners.addAll(teamKeys(inv[0], inv[1], canViewTeam));
                    else owners.add(-1L);
                    for (Long o : owners) {
                        if (o != -1L && !canViewTeam.test(o)) continue;
                        // Sertifikası olmayan takımın alarmı da satırına girer (2026-11-13)
                        perTeam.computeIfAbsent(o, k -> new TeamAcc()).openAlerts++;
                    }
                }
                Instant now = Instant.now();
                String since60 = ISO.format(now.minus(2L * WINDOW_DAYS, ChronoUnit.DAYS));
                String since30 = ISO.format(now.minus(WINDOW_DAYS, ChronoUnit.DAYS));
                int last30 = 0, prev30 = 0;
                for (Object[] r : alertEventRepo.findCreatedStatRowsSince(since60)) {
                    Long team = r[0] instanceof Number n ? Long.valueOf(n.longValue()) : null;
                    String domain = r[1] == null ? null : String.valueOf(r[1]);
                    String created = r[2] == null ? null : String.valueOf(r[2]);
                    if (created == null || !visibleAlert(team, domain, canViewTeam, visibleDomains)) continue;
                    if (created.compareTo(since30) >= 0) last30++; else prev30++;
                }
                alerts.put("visible", true);
                alerts.put("open", open); alerts.put("critical", critical);
                alerts.put("opened_last30", last30); alerts.put("opened_prev30", prev30);
                alerts.put("delta", last30 - prev30);
            } catch (Exception e) { log.debug("executive: alarm bloğu düştü: {}", e.toString()); alerts.put("error_msg", e.getClass().getSimpleName()); }
        }
        out.put("alerts", alerts);

        // ── SLA: hedef altı monitör sayısı (9 tür) — ortak (tüm filo) SLA önbelleğinin görünür alt kümesi ─────────────
        Map<String, Object> sla = new LinkedHashMap<>();
        try {
            double target = appSettings.getDouble("site.monitor.sla.target-pct", 99.9);
            int monitors = 0, breaches = 0;
            List<Map<String, Object>> worst = new ArrayList<>();
            Map<Long, int[]> slaByTeam = new LinkedHashMap<>();   // takım → [monitör, ihlal]; -1 = takımsız / türev
            for (String type : MonitorSparklineService.KINDS.keySet()) {
                Map<Long, Long> teams = sparklineService.monitorTeams(type);
                Set<Long> ids = new HashSet<>();
                teams.forEach((id, tid) -> { if (canViewTeam.test(tid)) ids.add(id); });
                if (ids.isEmpty()) continue;
                Map<Long, Map<String, Object>> avail = MonitorSparklineService.subset(sparklineService.availabilityAll(type, WINDOW_DAYS), ids);
                for (Map.Entry<Long, Map<String, Object>> en : avail.entrySet()) {
                    Object up = en.getValue().get("up_pct");
                    if (!(up instanceof Double d)) continue;
                    monitors++;
                    Long tid = teams.get(en.getKey());
                    int[] bt = slaByTeam.computeIfAbsent(tid == null ? -1L : tid, k -> new int[2]);
                    bt[0]++;
                    if (d < target) {
                        breaches++; bt[1]++;
                        Map<String, Object> w = new LinkedHashMap<>();
                        w.put("type", type); w.put("id", en.getKey()); w.put("up_pct", d); w.put("bad_hours", en.getValue().get("bad_hours"));
                        w.put("team_id", tid);
                        worst.add(w);
                    }
                }
            }
            worst.sort(Comparator.comparingDouble((Map<String, Object> m) -> (Double) m.get("up_pct"))
                    .thenComparing(m -> String.valueOf(m.get("type"))).thenComparing(m -> (Long) m.get("id")));
            List<Map<String, Object>> top = new ArrayList<>(worst.subList(0, Math.min(5, worst.size())));
            nameWorst(top);
            sla.put("target_pct", target); sla.put("monitors", monitors); sla.put("breaches", breaches);
            sla.put("worst", top);
            List<Map<String, Object>> byTeam = new ArrayList<>();
            slaByTeam.forEach((tid, c) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("team_id", tid == -1L ? null : tid); m.put("monitors", c[0]); m.put("breaches", c[1]);
                byTeam.add(m);
            });
            sla.put("by_team", byTeam);
        } catch (Exception e) { log.debug("executive: SLA bloğu düştü: {}", e.toString()); sla.put("error_msg", e.getClass().getSimpleName()); }
        out.put("sla", sla);

        // ── Takım karşılaştırması ─────────────────────────────────────────────────────────────
        List<Map<String, Object>> teams = new ArrayList<>();
        boolean alertsRead = alertsVisible && !alerts.containsKey("error_msg");
        for (Map.Entry<Long, TeamAcc> e : perTeam.entrySet()) {
            CertHealthCounts c = e.getValue().certs;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", e.getKey() == -1L ? null : e.getKey());
            m.put("team_name", e.getKey() == -1L ? null : teamNames.getOrDefault(e.getKey(), "#" + e.getKey()));
            m.put("total", c.total()); m.put("ok", c.ok()); m.put("under30", c.under30()); m.put("expired", c.expired());
            m.put("error", c.error()); m.put("unchecked", c.unchecked()); m.put("evaluated", c.evaluated());
            // Alarm okunamadı / izin yok → bilinmiyor (null), asla 0
            m.put("open_alerts", alertsRead ? e.getValue().openAlerts : null);
            m.put("health_pct", c.healthPct());
            teams.add(m);
        }
        teams.sort((a, b) -> {
            Double ha = (Double) a.get("health_pct"), hb = (Double) b.get("health_pct");
            if (ha == null || hb == null) return ha == null ? (hb == null ? 0 : 1) : -1;
            return Double.compare(ha, hb);   // en kötü üstte
        });
        out.put("teams", teams);
        out.put("window_days", WINDOW_DAYS);
        out.put("generated_at", ISO.format(Instant.now()));
        return out;
    }

    /** Görünür takım anahtarları (SY, sonra UG; aynıysa bir kez; görülemeyen atlanır; hiçbiri yoksa takımsız -1). */
    private static List<Long> teamKeys(Long sy, Long ug, Predicate<Long> canViewTeam) {
        List<Long> keys = new ArrayList<>(2);
        if (sy != null && canViewTeam.test(sy)) keys.add(sy);
        if (ug != null && !ug.equals(sy) && canViewTeam.test(ug)) keys.add(ug);
        if (sy == null && ug == null && canViewTeam.test(null)) keys.add(-1L);
        return keys;
    }

    private static boolean visibleAlert(Long team, String domain, Predicate<Long> canViewTeam, Set<String> domains) {
        return (team != null && canViewTeam.test(team)) || (domain != null && domains.contains(domain));
    }

    /** En kötü SLA satırlarına izleme adı (tür başına tek dar sorgu; okunamazsa ad yazılmaz — arayüz "#id"). */
    private void nameWorst(List<Map<String, Object>> rows) {
        Map<String, List<Long>> byType = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) byType.computeIfAbsent((String) r.get("type"), k -> new ArrayList<>()).add((Long) r.get("id"));
        for (Map.Entry<String, List<Long>> e : byType.entrySet()) {
            Map<Long, String> names;
            try { names = sparklineService.monitorNames(e.getKey(), e.getValue()); }
            catch (Exception ex) { log.debug("executive: SLA izleme adları okunamadı: {}", ex.toString()); continue; }
            if (names == null) continue;
            for (Map<String, Object> r : rows) if (e.getKey().equals(r.get("type"))) r.put("name", names.get((Long) r.get("id")));
        }
    }

    /**
     * "Son N günde ne değişti" satırı (2026-09-12, #7): yeni alan, silinen alan, yenilenen sertifika (parmak izi
     * değişimi), açılan / çözülen alarm. Dashboard istatistik şeridinin altında tek satır.
     */
    /** Kapsam anahtarlı (bellekli) sürüm — {@code scopeKey} aynı kapsamı gören kullanıcılar için aynı sonucu paylaşır. */
    public Map<String, Object> recentChanges(int days, Predicate<Long> canViewTeam, String scopeKey) {
        return changesMemo.get(scopeKey == null ? null : scopeKey + "|d" + days, changesCacheMs, false,
                () -> recentChanges(days, canViewTeam));
    }

    public Map<String, Object> recentChanges(int days, Predicate<Long> canViewTeam) {
        int d = Math.max(1, Math.min(90, days));
        String since = ISO.format(Instant.now().minus(d, ChronoUnit.DAYS));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        Set<String> visibleDomains = new HashSet<>();
        Set<Long> liveIds = new HashSet<>();
        int added = 0, removed = 0;
        try {
            for (CertificateInventory i : inventoryRepo.findAllByOrderByDomainAsc()) {
                if (i.getDeletedAt() != null) continue;   // eski sürümden kalmış çöp satırı (tek seferlik temizlik siler)
                liveIds.add(i.getId());
                if (!visibleInventory(i, canViewTeam)) continue;
                visibleDomains.add(i.getDomain());
                if (i.getCreatedAt() != null && i.getCreatedAt().compareTo(since) >= 0) added++;
            }
        } catch (Exception e) { log.debug("changes: envanter düştü: {}", e.toString()); }
        // Silinen (2026-10-07, silme KALICI): pencerede DELETE satırı olan ve BUGÜN envanterde olmayan kayıtlar — kimlik başına
        // bir kez; takım kapsamı satırın (silme anındaki) takımıyla. Eski çöp kutusundan geri yüklenmiş kayıt hâlâ canlıdır →
        // sayılmaz. Tek gruplu sorgu (pencere ≤ 90 gün).
        if (changeLogRepo != null) {
            try {
                Set<Long> counted = new HashSet<>();
                for (Object[] row : changeLogRepo.findInventoryDeletesSince(since)) {
                    Long id = row[0] instanceof Number n ? Long.valueOf(n.longValue()) : null;
                    Long team = row[1] instanceof Number n ? Long.valueOf(n.longValue()) : null;
                    if (id == null || liveIds.contains(id) || !canViewTeam.test(team)) continue;
                    if (counted.add(id)) removed++;
                }
            } catch (Exception e) { log.debug("changes: silinen kayıt sorgusu düştü: {}", e.toString()); }
        }
        int renewed = 0;
        try { for (String dom : certificateCheckRepo.domainsWithFingerprintChangeSince(since)) if (visibleDomains.contains(dom)) renewed++; }
        catch (Exception e) { log.debug("changes: parmak izi sorgusu düştü: {}", e.toString()); }
        int opened = 0, resolved = 0;
        try {
            for (AlertEvent e : alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(since)) {
                if (!visible(e, canViewTeam, visibleDomains) || e.getCreatedAt() == null || e.getCreatedAt().compareTo(since) < 0) continue;
                opened++;
            }
        } catch (Exception e) { log.debug("changes: alarm sorgusu düştü: {}", e.toString()); }
        // A5 D-3 (2026-09-29): "çözülen" pencerede ÇÖZÜLEN alarmlardır — açılış tarihinden bağımsız (10 gün önce açılıp dün
        // çözülen alarm sayılır). Sessiz kapanış (D-7: silindi/duraklatıldı/envanter pasif) kurtarma değildir, sayılmaz.
        try {
            for (AlertEvent e : alertEventRepo.findByResolvedAtGreaterThanEqual(since)) {
                if (!visible(e, canViewTeam, visibleDomains) || !Boolean.TRUE.equals(e.getResolved())) continue;
                if (Boolean.TRUE.equals(e.getResolvedSilently())) continue;
                resolved++;
            }
        } catch (Exception e) { log.debug("changes: çözülen alarm sorgusu düştü: {}", e.toString()); }
        out.put("added", added); out.put("removed", removed); out.put("renewed", renewed);
        out.put("alerts_opened", opened); out.put("alerts_resolved", resolved);
        return out;
    }

    /** Envanter kaydı görülebilir mi: SY ya da UG takımı (Alarm Geçmişi listesiyle aynı kural). */
    static boolean visibleInventory(CertificateInventory i, Predicate<Long> canViewTeam) {
        return canViewTeam.test(i.getTeamId()) || (i.getUgTeamId() != null && canViewTeam.test(i.getUgTeamId()));
    }

    private static boolean visible(AlertEvent e, Predicate<Long> canViewTeam, Set<String> domains) {
        return (e.getTeamId() != null && canViewTeam.test(e.getTeamId())) || (e.getDomain() != null && domains.contains(e.getDomain()));
    }
}

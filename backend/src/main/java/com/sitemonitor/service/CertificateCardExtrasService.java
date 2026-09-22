package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.UptimeCheck;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.UptimeCheckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Genel Bakış sertifika kartı zenginleştirmeleri (2026-09-19, kullanıcı seçimi 8/8): sağlık bulguları · açık alarm ·
 * 24 sa erişilebilirlik + sparkline · sertifika değişimi/pin · yenileme planı · paylaşılan sertifika + SAN ·
 * bakım penceresi · sorumlu kişiler. Ana liste ({@code /api/certificates}) DOKUNULMADAN ayrı uçtan gelir; kart
 * alan adıyla birleştirir.
 *
 * <p>Hesap TÜM aktif envanter için bir kez yapılır ve 60 sn önbellekte durur ({@code card-extras}); takım kapsamı
 * çağıran tarafta alan adı kümesiyle süzülür. Kaynaklar tek geçişli: latest_checks (1 tarama), açık alarmlar (1),
 * uptime son 24 sa saatlik kova (1 grup sorgusu) + son kontrol (1), bakım pencereleri (1), sağlık kuralları CPU.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CertificateCardExtrasService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    static final int CHANGE_NOTICE_DAYS = 7, SHARED_LIST_CAP = 10;
    /** Thread-safe ve kurulumu pahalı — alan başına yeniden kurulmaz (HttpFailureDiagnostics ile aynı desen). */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final List<String> LEVEL_ORDER = List.of("CRITICAL", "HIGH", "WARNING", "MEDIUM", "LOW", "INFO");

    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final AlertEventRepository alertEventRepo;
    private final UptimeCheckRepository uptimeCheckRepo;
    private final MaintenanceService maintenanceService;
    /** Takım adı çözümü (2026-09-22): CertificateInventory.teamName @Transient — DB'den gelmez, burada eşlenir. */
    private final com.sitemonitor.repository.TeamRepository teamRepo;
    private final CertificateHealthService healthService;
    private final @Nullable CacheManager cacheManager;

    /** Alan adı → zenginleştirme haritası (tüm aktif envanter). */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> all() {
        Cache cache = cacheManager == null ? null : cacheManager.getCache("card-extras");
        if (cache == null) return compute(Instant.now());
        Map<String, Map<String, Object>> v = cache.get("all", () -> compute(Instant.now()));
        return v == null ? Map.of() : v;
    }

    /**
     * Kapsam süzgeci: yalnız verilen alanlar (null = hepsi).
     * <p>Kapsam doluysa {@code shared.domains} listesi DE süzülür (2026-09-22): parmak izi haritası
     * {@link #compute} içinde TÜM envanterden kurulduğu için, üst seviye anahtarları süzmek kapsam dışı
     * takımların alan ADLARINI çipin ipucunda açıkta bırakıyordu — {@code GET /api/certificates/shared}
     * penceresi aynı adları özellikle gizlerken. Varlık gizlenmez ({@code count} toplam eş sayısı kalır,
     * pencereyle aynı sözleşme: "varlık gizlenmez, ayrıntı sızmaz"); çip zaten {@code count > domains.size()}
     * olunca "…" gösterir. Önbellekteki blok tüm kullanıcılarca paylaşıldığı için KOPYALANARAK yazılır —
     * yerinde değiştirmek önbelleği bozar ve sızıntıyı kalıcılaştırırdı.
     */
    public Map<String, Map<String, Object>> forDomains(@Nullable Set<String> domains) {
        Map<String, Map<String, Object>> all = all();
        if (domains == null) return all;
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String d : domains) { Map<String, Object> m = all.get(d); if (m != null) out.put(d, scopeShared(m, domains)); }
        return out;
    }

    /** {@code shared.domains}'i kapsama göre süzen KOPYA; süzülecek ad yoksa özgün harita döner. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> scopeShared(Map<String, Object> m, Set<String> scope) {
        if (!(m.get("shared") instanceof Map<?, ?> raw)) return m;
        Map<String, Object> shared = (Map<String, Object>) raw;
        if (!(shared.get("domains") instanceof List<?> names) || names.isEmpty()) return m;
        List<?> visible = names.stream().filter(scope::contains).toList();
        if (visible.size() == names.size()) return m;
        Map<String, Object> scopedShared = new LinkedHashMap<>(shared);
        scopedShared.put("domains", visible);
        Map<String, Object> copy = new LinkedHashMap<>(m);
        copy.put("shared", scopedShared);
        return copy;
    }

    Map<String, Map<String, Object>> compute(Instant now) {
        List<CertificateInventory> inventory = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        Map<String, LatestCheck> latest = new HashMap<>();
        try { for (LatestCheck lc : latestCheckRepo.findAll()) if (lc.getDomain() != null) latest.put(lc.getDomain(), lc); }
        catch (Exception e) { log.debug("card-extras: latest_checks okunamadı: {}", e.toString()); }

        Map<String, Map<String, Object>> alerts = safe(this::openAlertsByDomain, "alarm");
        Map<String, Map<String, Object>> lastAlerts = safe(this::lastAlertByDomain, "son alarm");
        Map<String, Map<String, Object>> uptime = safe(() -> uptimeByDomain(now), "uptime");
        Map<String, Map<String, Object>> maint = safe(() -> maintenanceService.windowInfoByTarget(now), "bakım");
        Map<String, List<String>> byFingerprint = new HashMap<>();
        for (CertificateInventory inv : inventory) {
            LatestCheck lc = latest.get(inv.getDomain());
            if (lc != null && lc.getFingerprint() != null && !lc.getFingerprint().isBlank())
                byFingerprint.computeIfAbsent(lc.getFingerprint().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(inv.getDomain());
        }
        // Tier bazlı eşik (2026-09-20): çözüm BİR kez, alan başına tier'ıyla.
        ThresholdResolution thRes; try { thRes = healthService.thresholdResolution(); } catch (Exception e) { thRes = ThresholdResolution.fixed(null); }
        String today = LocalDate.now(IST).toString();

        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (CertificateInventory inv : inventory) {
            String d = inv.getDomain();
            LatestCheck lc = latest.get(d);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("health", health(lc, inv, CertificateHealthService.thresholdDays(thRes, inv.getTier())));
            m.put("alerts", alerts.get(d));
            m.put("last_alert", lastAlerts.get(d));
            m.put("uptime", uptime.get(d));
            m.put("change", change(lc, now));
            m.put("renewal", renewal(inv, lc, today));
            m.put("shared", shared(d, lc, byFingerprint));
            Map<String, Object> mw = maint.get(d) != null ? maint.get(d) : maint.get("*");
            m.put("maintenance", mw);
            m.put("contacts", contacts(inv));
            out.put(d, m);
        }
        return out;
    }

    private interface Block<T> { T run(); }
    private static <T> Map<String, T> safe(Block<Map<String, T>> b, String what) {
        try { return b.run(); } catch (Exception e) { log.debug("card-extras: {} bloğu atlandı: {}", what, e.toString()); return Map.of(); }
    }

    // ── 1) Sağlık bulguları ────────────────────────────────────────────────────────────────
    /** FAIL satırlar (expiry hariç — kartın kalan gün göstergesi zaten var); "ok/evaluated" özeti. */
    private Map<String, Object> health(LatestCheck lc, CertificateInventory inv, int[] th) {
        if (lc == null) return null;
        try {
            CertificateHealthService.HealthResult r = healthService.evaluate(lc, inv, false, th[0], th[1]);
            List<String> failed = new ArrayList<>();
            for (CertificateHealthService.HealthRow row : r.rows())
                if (row.status() == CertificateHealthRules.Status.FAIL && !"expiry".equals(row.key())) failed.add(row.key());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", r.okCount()); m.put("evaluated", r.evaluatedCount()); m.put("failed", failed);
            return m;
        } catch (Exception e) { return null; }
    }

    // ── 2) Açık alarmlar ──────────────────────────────────────────────────────────────────
    private Map<String, Map<String, Object>> openAlertsByDomain() {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (e.getDomain() == null) continue;
            Map<String, Object> m = out.computeIfAbsent(e.getDomain(), k -> {
                Map<String, Object> x = new LinkedHashMap<>(); x.put("count", 0); x.put("level", null); x.put("all_acked", true); x.put("first_id", null); x.put("types", new ArrayList<String>()); return x;
            });
            m.put("count", (Integer) m.get("count") + 1);
            String lvl = e.getAlertLevel() == null ? "WARNING" : e.getAlertLevel().toUpperCase(Locale.ROOT);
            if (m.get("level") == null || rank(lvl) < rank((String) m.get("level"))) { m.put("level", lvl); m.put("first_id", e.getId()); }
            if (!Boolean.TRUE.equals(e.getAcknowledged())) m.put("all_acked", false);
            @SuppressWarnings("unchecked") List<String> types = (List<String>) m.get("types");
            if (e.getAlertType() != null && !types.contains(e.getAlertType()) && types.size() < 5) types.add(e.getAlertType());
        }
        return out;
    }
    private static int rank(String level) { int i = LEVEL_ORDER.indexOf(level); return i < 0 ? LEVEL_ORDER.size() : i; }

    /** "Şu an" şeridi: alanın en son alarm olayı — açıksa seviyesi, kapalıysa ne zaman çözüldüğü. */
    private Map<String, Map<String, Object>> lastAlertByDomain() {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (AlertEvent e : alertEventRepo.findLatestPerDomain()) {
            if (e.getDomain() == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId()); m.put("level", e.getAlertLevel()); m.put("type", e.getAlertType());
            m.put("resolved", Boolean.TRUE.equals(e.getResolved())); m.put("at", e.getCreatedAt()); m.put("resolved_at", e.getResolvedAt());
            m.put("acknowledged", Boolean.TRUE.equals(e.getAcknowledged()));
            out.put(e.getDomain(), m);
        }
        return out;
    }

    // ── 3) Erişilebilirlik ────────────────────────────────────────────────────────────────
    private Map<String, Map<String, Object>> uptimeByDomain(Instant now) {
        String since = ISO.format(now.minus(Duration.ofHours(24)));
        Map<String, long[]> totals = new HashMap<>();                 // domain → {total, up}
        Map<String, Map<String, long[]>> hourly = new HashMap<>();    // domain → hourKey → {total, up}
        for (Object[] r : uptimeCheckRepo.hourlyHttpOkSince(since)) {
            String d = (String) r[0], hour = (String) r[1];
            long total = ((Number) r[2]).longValue(), up = r[3] == null ? 0 : ((Number) r[3]).longValue();
            long[] t = totals.computeIfAbsent(d, k -> new long[2]); t[0] += total; t[1] += up;
            hourly.computeIfAbsent(d, k -> new HashMap<>()).put(hour, new long[]{total, up});
        }
        Map<String, UptimeCheck> last = new HashMap<>();
        try { for (UptimeCheck u : uptimeCheckRepo.findLatestPerDomainPort()) if (u.getDomain() != null) last.putIfAbsent(u.getDomain(), u); }
        catch (Exception e) { log.debug("card-extras: son uptime okunamadı: {}", e.toString()); }
        // 24 saatlik kova anahtarları (boş saat = null → sparkline boşluk)
        List<String> hours = new ArrayList<>(24);
        Instant cur = now.minus(Duration.ofHours(23)).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        for (int i = 0; i < 24; i++) { hours.add(ISO.format(cur).substring(0, 13)); cur = cur.plus(Duration.ofHours(1)); }
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (String d : new java.util.HashSet<>(union(totals.keySet(), last.keySet()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            long[] t = totals.get(d);
            m.put("pct24", t == null || t[0] == 0 ? null : Math.round(t[1] * 1000.0 / t[0]) / 10.0);
            m.put("checks24", t == null ? 0 : t[0]);
            UptimeCheck u = last.get(d);
            m.put("last_status", u == null ? null : u.getStatus()); m.put("last_ms", u == null ? null : u.getResponseMs()); m.put("last_at", u == null ? null : u.getCheckedAt());
            List<Object> points = new ArrayList<>(24);
            Map<String, long[]> h = hourly.getOrDefault(d, Map.of());
            for (String hk : hours) { long[] v = h.get(hk); points.add(v == null || v[0] == 0 ? null : Math.round(v[1] * 100.0 / v[0])); }
            m.put("points", points);
            out.put(d, m);
        }
        return out;
    }
    private static Set<String> union(Set<String> a, Set<String> b) { Set<String> s = new java.util.HashSet<>(a); s.addAll(b); return s; }

    // ── 4) Sertifika değişimi / pin ───────────────────────────────────────────────────────
    private Map<String, Object> change(LatestCheck lc, Instant now) {
        if (lc == null) return null;
        String pinned = lc.getPinnedFingerprint(), served = lc.getFingerprint();
        boolean mismatch = pinned != null && !pinned.isBlank() && served != null && !served.isBlank() && !pinned.equalsIgnoreCase(served);
        Instant changedAt = SmtpLogQueryService.parse(lc.getFingerprintChangedAt());
        boolean recent = changedAt != null && !changedAt.isBefore(now.minus(Duration.ofDays(CHANGE_NOTICE_DAYS)));
        boolean acked = lc.getFingerprintAckFingerprint() != null && pinned != null && lc.getFingerprintAckFingerprint().equalsIgnoreCase(pinned);
        if (!mismatch && !(recent && !acked)) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mismatch", mismatch); m.put("changed_at", lc.getFingerprintChangedAt()); m.put("acked", acked);
        m.put("previous", lc.getPreviousFingerprint());
        return m;
    }

    // ── 5) Yenileme planı ─────────────────────────────────────────────────────────────────
    /** Plan varsa {planned_at, by, note, overdue}: plan tarihi geçmiş ve sertifika plandan SONRA verilmemişse gecikmiş. */
    private Map<String, Object> renewal(CertificateInventory inv, LatestCheck lc, String today) {
        if (inv.getRenewalPlannedAt() == null || inv.getRenewalPlannedAt().isBlank()) return null;
        String plannedAt = inv.getRenewalPlannedAt();
        boolean past = plannedAt.compareTo(today) < 0;
        String notBefore = lc == null || lc.getNotBefore() == null ? null : lc.getNotBefore().substring(0, Math.min(10, lc.getNotBefore().length()));
        boolean renewedSincePlan = notBefore != null && notBefore.compareTo(plannedAt) >= 0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planned_at", plannedAt); m.put("by", inv.getRenewalPlannedByName() != null ? inv.getRenewalPlannedByName() : inv.getRenewalPlannedBy());
        m.put("note", inv.getRenewalPlannedNote()); m.put("overdue", past && !renewedSincePlan); m.put("done", renewedSincePlan);
        return m;
    }

    /**
     * Paylaşılan sertifika penceresi (2026-09-22): {@code domain}'in parmak izini taşıyan TÜM alanlar, karar için gereken
     * bağlamla. {@code scope} null = global görüş; doluysa kapsam dışı eşler listeye girmez ama {@code hidden} sayısında
     * görünür (varlık gizlenmez, ayrıntı sızmaz). Alan yoksa/parmak izi yoksa boş sonuç.
     */
    public Map<String, Object> sharedDetail(String domain, java.util.Set<String> scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", domain);
        LatestCheck self = domain == null ? null : latestCheckRepo.findById(domain).orElse(null);
        String fp = self == null ? null : self.getFingerprint();
        out.put("fingerprint", fp);
        out.put("subject", self == null ? null : self.getSubject());
        out.put("issuer", self == null ? null : (self.getIssuerCn() != null ? self.getIssuerCn() : self.getIssuer()));
        out.put("not_after", self == null ? null : self.getNotAfter());
        out.put("days_remaining", self == null ? null : self.getDaysRemaining());
        out.put("san", self == null ? List.of() : sanList(self.getSan()));
        List<Map<String, Object>> peers = new java.util.ArrayList<>();
        int hidden = 0;
        if (fp != null && !fp.isBlank()) {
            Map<Long, String> teamNames = new java.util.HashMap<>();
            for (com.sitemonitor.model.Team tm : teamRepo.findAll()) if (tm.getId() != null) teamNames.put(tm.getId(), tm.getName());
            // Tek pod / 200-1000+ alan: bu uç her çip tıklamasında çağrılıyor ve önbelleksiz. Parmak izini DB'de süz,
            // envanteri yalnız eş alanlar için çek — eskiden latest_checks + certificate_inventory TAM tarama + tam
            // hidrasyondu (kardeş `card-extras` ucunun önbelleği tam bu maliyetten kaçınmak için var). (2026-09-22)
            List<LatestCheck> peerChecks = latestCheckRepo.findByFingerprintIgnoreCase(fp).stream()
                    .filter(lc -> lc.getDomain() != null)
                    .sorted(java.util.Comparator.comparing(LatestCheck::getDomain))
                    .toList();
            Map<String, CertificateInventory> invByDomain = new java.util.HashMap<>();
            if (!peerChecks.isEmpty()) {
                for (CertificateInventory inv : inventoryRepo.findByDomainIn(peerChecks.stream().map(LatestCheck::getDomain).toList()))
                    if (inv.getDomain() != null && inv.getDeletedAt() == null) invByDomain.putIfAbsent(inv.getDomain(), inv);
            }
            for (LatestCheck lc : peerChecks) {
                if (scope != null && !scope.contains(lc.getDomain())) { hidden++; continue; }
                CertificateInventory inv = invByDomain.get(lc.getDomain());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("domain", lc.getDomain());
                m.put("self", lc.getDomain() != null && lc.getDomain().equals(domain));
                m.put("status", lc.getStatus());
                m.put("days_remaining", lc.getDaysRemaining());
                m.put("not_after", lc.getNotAfter());
                m.put("checked_at", lc.getCheckedAt());
                m.put("port", inv == null ? null : inv.getPort());
                m.put("tier", inv == null ? null : inv.getTier());
                m.put("team_id", inv == null ? null : inv.getTeamId());
                m.put("team_name", inv == null || inv.getTeamId() == null ? null : teamNames.get(inv.getTeamId()));
                m.put("platform", inv == null ? null : inv.getPlatform());
                m.put("platform_detail", inv == null ? null : inv.getPlatformDetail());
                m.put("group_name", inv == null ? null : inv.getGroupName());
                m.put("in_inventory", inv != null);
                peers.add(m);
            }
        }
        out.put("peers", peers);
        out.put("hidden", hidden);
        out.put("count", peers.size());
        return out;
    }

    /** SAN JSON'u → liste (bozuksa virgül/boşlukla ayırma yedeği; boşsa boş liste). */
    static List<String> sanList(String san) {
        if (san == null || san.isBlank()) return List.of();
        try {
            List<?> l = JSON.readValue(san, List.class);
            return l.stream().map(String::valueOf).filter(x -> !x.isBlank()).toList();
        } catch (Exception ignore) {
            return java.util.Arrays.stream(san.split("[,;\\s]+")).filter(x -> !x.isBlank()).toList();
        }
    }

    // ── 6) Paylaşılan sertifika + SAN ─────────────────────────────────────────────────────
    private Map<String, Object> shared(String domain, LatestCheck lc, Map<String, List<String>> byFingerprint) {
        if (lc == null) return null;
        List<String> peers = lc.getFingerprint() == null ? List.of() : byFingerprint.getOrDefault(lc.getFingerprint().toLowerCase(Locale.ROOT), List.of())
                .stream().filter(x -> !x.equals(domain)).toList();
        int san = sanCount(lc.getSan());
        if (peers.isEmpty() && san <= 1) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", peers.size()); m.put("domains", peers.subList(0, Math.min(SHARED_LIST_CAP, peers.size()))); m.put("san_count", san);
        return m;
    }
    static int sanCount(String san) {
        if (san == null || san.isBlank()) return 0;
        try {
            List<?> l = JSON.readValue(san, List.class);
            return l.size();
        } catch (Exception e) {
            return (int) java.util.Arrays.stream(san.split("[,;\\s]+")).filter(x -> !x.isBlank()).count();
        }
    }

    // ── 8) Sorumlu kişiler ────────────────────────────────────────────────────────────────
    private static Map<String, Object> contacts(CertificateInventory inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app_dev", blankToNull(inv.getAppDevContact())); m.put("iis_admin", blankToNull(inv.getIisAdminContact()));
        m.put("svc_mgmt", blankToNull(inv.getSvcMgmtContact())); m.put("waf_admin", blankToNull(inv.getWafAdminContact()));
        m.put("missing", m.values().stream().allMatch(v -> v == null));
        return m;
    }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}

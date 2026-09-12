package com.sitemonitor.service;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;

/**
 * Vade Takvimi (Expiry Forecast) tek yanıt servisi (2026-09-12, takvim zenginleştirme #3/#5/#10/#14).
 *
 * <p>Sayfa eskiden üç uçtan (certificates / stats / stats-teams) tam nesneler çekip her şeyi istemcide
 * türetiyordu. Bu servis sayfanın gerçekten kullandığı alanları tek gövdede verir:
 * <ul>
 *   <li>{@code certs} — kırpılmış sertifika satırları (+ takım/tier/parmak izi/planlanan yenileme)</li>
 *   <li>{@code thresholds} — etkin alarm eşiği (uyarı/yüksek/kritik gün) → sayfa alarmla AYNI kovaları kullanır</li>
 *   <li>{@code lead_days} — yenileme öncesi süre: tier başına ayar, boşsa genel varsayılan</li>
 *   <li>{@code renewals} — son N günde tespit edilen yenilemeler (parmak izi değişimi) ve "zamanında mı"
 *       (yenileme, önceki bitiş − lead'den önce olduysa) — aylık kırılım</li>
 * </ul>
 * Takım kapsamı çağıranda: {@code canView} yüklemi satırları süzer (SessionScope.canView).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RenewalForecastService {

    public static final String KEY_LEAD_DEFAULT = "site.monitor.renewal.lead-days";
    public static final String KEY_LEAD_T1 = "site.monitor.renewal.lead-days-t1";
    public static final String KEY_LEAD_T2 = "site.monitor.renewal.lead-days-t2";
    public static final String KEY_LEAD_T3 = "site.monitor.renewal.lead-days-t3";
    public static final String KEY_LEAD_T4 = "site.monitor.renewal.lead-days-t4";
    /** Yenileme geçmişi penceresi (gün) — parmak izi değişimleri bu kadar geriye bakılarak bulunur. */
    public static final int RENEWAL_WINDOW_DAYS = 90;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final CertificateService certificateService;
    private final CertificateInventoryRepository inventoryRepo;
    private final AlertThresholdRepository thresholdRepo;
    private final AppSettingsService appSettings;
    private final JdbcTemplate jdbc;

    /** Tier → lead gün. Ayar boş/0 ise genel varsayılana (o da boşsa 14) düşer. */
    public Map<String, Integer> leadDays() {
        int def = Math.max(0, appSettings.getInt(KEY_LEAD_DEFAULT, 14));
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("default", def);
        m.put("t1", tierLead(KEY_LEAD_T1, def));
        m.put("t2", tierLead(KEY_LEAD_T2, def));
        m.put("t3", tierLead(KEY_LEAD_T3, def));
        m.put("t4", tierLead(KEY_LEAD_T4, def));
        return m;
    }
    private int tierLead(String key, int def) { int v = appSettings.getInt(key, 0); return v > 0 ? v : def; }
    public int leadFor(Integer tier, Map<String, Integer> lead) {
        if (tier == null) return lead.get("default");
        return lead.getOrDefault("t" + tier, lead.get("default"));
    }

    /** Etkin alarm eşiği; kayıt yoksa 30/15/7. */
    public Map<String, Integer> thresholds() {
        Map<String, Integer> t = new LinkedHashMap<>();
        AlertThreshold a = thresholdRepo.findAll().stream().filter(x -> !Boolean.FALSE.equals(x.getActive())).findFirst().orElse(null);
        t.put("warning", a != null && a.getWarningDays() != null ? a.getWarningDays() : 30);
        t.put("high", a != null && a.getHighDays() != null ? a.getHighDays() : 15);
        t.put("critical", a != null && a.getCriticalDays() != null ? a.getCriticalDays() : 7);
        return t;
    }

    /**
     * @param viewTeamIds görünür takımlar (null = hepsi)
     * @param canView     satır bazlı kapsam yüklemi (takımsız kayıtlar: global görüntüleyici görür)
     */
    public Map<String, Object> build(Collection<Long> viewTeamIds, Predicate<Long> canView) {
        Map<String, Integer> lead = leadDays();
        Map<String, Integer> th = thresholds();
        List<CertificateDto> latest = certificateService.getAllLatestForTeams(viewTeamIds);
        Map<String, CertificateInventory> inv = new HashMap<>();
        for (CertificateInventory i : inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()) inv.put(i.getDomain(), i);

        List<Map<String, Object>> certs = new ArrayList<>();
        String dataAsOf = null;
        for (CertificateDto d : latest) {
            if (d.getDomain() == null) continue;
            CertificateInventory i = inv.get(d.getDomain());
            Long teamId = d.getTeamId() != null ? d.getTeamId() : (i != null ? i.getTeamId() : null);
            if (!canView.test(teamId)) continue;
            Integer tier = d.getTier() != null ? d.getTier() : (i != null ? i.getTier() : null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", d.getDomain());
            m.put("not_after", d.getNotAfter());
            m.put("days_remaining", d.getDaysRemaining());
            m.put("status", d.getStatus());
            m.put("error", d.getError());
            m.put("checked_at", d.getCheckedAt());
            m.put("team_id", teamId);
            m.put("team_name", d.getTeamName());
            m.put("ug_team_id", i != null ? i.getUgTeamId() : null);
            m.put("tier", tier);
            m.put("group_name", i != null ? i.getGroupName() : null);
            m.put("issuer_cn", d.getIssuerCn());
            m.put("fingerprint", d.getFingerprint());
            m.put("serial_number", d.getSerialNumber());
            m.put("lead_days", leadFor(tier, lead));
            m.put("renew_by", renewBy(d.getNotAfter(), leadFor(tier, lead)));
            m.put("renewal_planned_at", i != null ? i.getRenewalPlannedAt() : null);
            m.put("renewal_planned_by", i != null ? i.getRenewalPlannedByName() : null);
            m.put("renewal_planned_note", i != null ? i.getRenewalPlannedNote() : null);
            // Plan durumu: planned → yeni sertifika (not_before) plan tarihinden sonra görüldüyse done (plan yerine geldi)
            String planned = i != null ? i.getRenewalPlannedAt() : null;
            String nb = localDay(d.getNotBefore(), ZONE);
            m.put("renewal_plan_state", planned == null ? "none" : (nb != null && nb.compareTo(planned) >= 0 ? "done" : "planned"));
            certs.add(m);
            if (d.getCheckedAt() != null && (dataAsOf == null || d.getCheckedAt().compareTo(dataAsOf) > 0)) dataAsOf = d.getCheckedAt();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("certs", certs);
        out.put("thresholds", th);
        out.put("lead_days", lead);
        out.put("data_as_of", dataAsOf);
        out.put("generated_at", ISO.format(Instant.now()));
        out.put("renewals", renewals(certs.stream().map(c -> (String) c.get("domain")).toList(), inv, lead));
        return out;
    }

    /**
     * Takvim günü dilimi: zaman damgaları UTC saklanır, istemci ise yerel günü gösterir (23:59:59Z biten
     * sertifika ekranda ertesi gün). Çıplak 'YYYY-MM-DD' alanlar bu yüzden sunucu dilimine göre üretilir —
     * servisler Europe/Istanbul'da koşar, kullanıcılar da oradadır (ISSUE-007, 2026-09-12).
     */
    static final ZoneId ZONE = ZoneId.systemDefault();

    /** Bitiş − lead gün ('YYYY-MM-DD', {@link #ZONE} günü). */
    static String renewBy(String notAfter, int leadDays) { return renewBy(notAfter, leadDays, ZONE); }

    static String renewBy(String notAfter, int leadDays, ZoneId zone) {
        Instant t = parseUtc(notAfter);
        return t == null ? null : t.minus(leadDays, ChronoUnit.DAYS).atZone(zone).toLocalDate().toString();
    }

    /** UTC zaman damgası → dilimdeki takvim günü ('YYYY-MM-DD'); parse edilemezse ilk 10 karakter. */
    static String localDay(String utcTs, ZoneId zone) {
        Instant t = parseUtc(utcTs);
        if (t != null) return t.atZone(zone).toLocalDate().toString();
        return utcTs == null ? null : utcTs.substring(0, Math.min(10, utcTs.length()));
    }

    private static Instant parseUtc(String ts) {
        if (ts == null || ts.isBlank()) return null;
        try {
            String s = ts.trim();
            return s.matches("^\\d{4}-\\d{2}-\\d{2}$") ? Instant.parse(s + "T00:00:00Z")
                    : (s.endsWith("Z") || s.matches(".*[+-]\\d{2}:?\\d{2}$")) ? OffsetDateTime.parse(s).toInstant() : Instant.parse(s + "Z");
        } catch (Exception e) { return null; }
    }

    /**
     * Son {@value #RENEWAL_WINDOW_DAYS} günün yenilemeleri: certificate_checks'te domain×parmak izi grupları
     * (DB'de toplanır — ham satır taşınmaz), ardışık gruplar arasındaki geçiş = yenileme; "zamanında" =
     * geçiş anı, önceki sertifikanın bitişi − tier lead'inden önce.
     */
    public Map<String, Object> renewals(Collection<String> domains, Map<String, CertificateInventory> inv, Map<String, Integer> lead) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_days", RENEWAL_WINDOW_DAYS);
        List<Map<String, Object>> events = new ArrayList<>();
        int onTime = 0, late = 0;
        Map<String, int[]> months = new TreeMap<>();
        if (!domains.isEmpty()) {
            try {
                String since = ISO.format(Instant.now().minus(RENEWAL_WINDOW_DAYS + 400, ChronoUnit.DAYS));   // önceki sertifika da görünsün
                Set<String> wanted = new HashSet<>(domains);
                Map<String, List<Object[]>> byDomain = new HashMap<>();
                jdbc.query("SELECT domain, fingerprint, MIN(checked_at) AS first_seen, MAX(not_after) AS not_after "
                        + "FROM certificate_checks WHERE checked_at >= ? AND fingerprint IS NOT NULL AND fingerprint <> '' "
                        + "GROUP BY domain, fingerprint", rs -> {
                    String dom = rs.getString("domain");
                    if (!wanted.contains(dom)) return;
                    byDomain.computeIfAbsent(dom, k -> new ArrayList<>()).add(new Object[]{rs.getString("fingerprint"), rs.getString("first_seen"), rs.getString("not_after")});
                }, since);
                String windowStart = ISO.format(Instant.now().minus(RENEWAL_WINDOW_DAYS, ChronoUnit.DAYS));
                for (Map.Entry<String, List<Object[]>> e : byDomain.entrySet()) {
                    List<Object[]> groups = e.getValue();
                    groups.sort(Comparator.comparing(g -> String.valueOf(g[1])));
                    CertificateInventory i = inv.get(e.getKey());
                    int leadD = leadFor(i != null ? i.getTier() : null, lead);
                    for (int k = 1; k < groups.size(); k++) {
                        String renewedAt = String.valueOf(groups.get(k)[1]);
                        if (renewedAt.compareTo(windowStart) < 0) continue;
                        String prevNotAfter = groups.get(k - 1)[2] == null ? null : String.valueOf(groups.get(k - 1)[2]);
                        String renewByPrev = renewBy(prevNotAfter, leadD);
                        String renewedDay = localDay(renewedAt, ZONE);
                        boolean ok = renewByPrev == null || renewedDay.compareTo(renewByPrev) <= 0;
                        Map<String, Object> ev = new LinkedHashMap<>();
                        ev.put("domain", e.getKey()); ev.put("renewed_at", renewedAt); ev.put("prev_not_after", prevNotAfter);
                        ev.put("renew_by", renewByPrev); ev.put("on_time", ok); ev.put("tier", i != null ? i.getTier() : null);
                        events.add(ev);
                        if (ok) onTime++; else late++;
                        String month = renewedDay.substring(0, 7);
                        months.computeIfAbsent(month, x -> new int[2])[ok ? 0 : 1]++;
                    }
                }
                events.sort((a, b) -> String.valueOf(b.get("renewed_at")).compareTo(String.valueOf(a.get("renewed_at"))));
            } catch (Exception ex) {
                log.debug("Yenileme geçmişi okunamadı: {}", ex.toString());
            }
        }
        out.put("on_time", onTime); out.put("late", late);
        out.put("events", events.size() > 50 ? events.subList(0, 50) : events);
        List<Map<String, Object>> ms = new ArrayList<>();
        for (Map.Entry<String, int[]> e : months.entrySet()) ms.add(Map.of("month", e.getKey(), "on_time", e.getValue()[0], "late", e.getValue()[1]));
        out.put("months", ms);
        return out;
    }
}

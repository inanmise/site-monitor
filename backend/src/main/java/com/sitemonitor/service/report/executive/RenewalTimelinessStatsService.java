package com.sitemonitor.service.report.executive;

import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.RenewalForecastService;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;
import com.sitemonitor.service.retention.RetentionService;
import com.sitemonitor.util.TtlMemo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * İstatistikler — zamanında yenileme oranı (2026-11-13, zenginleştirme #11). Aylık yönetici özetinin kuralı
 * ({@link RenewalTimelinessSection}): <b>Uyum %</b> = süresi DOLMADAN yapılan yenilemeler ÷ (yenilemeler + yenilenmeden
 * süresi dolanlar); son 3 / 6 / 12 ay (İstanbul takvimiyle şimdiden geriye), pencere sonu = şimdi. Yenileme tespiti
 * ({@link RenewalTimelinessSection#detect}) ve yenilenmeden dolanlar ({@link RenewalTimelinessSection#lapses}) aynı statik
 * kodla — burada ikinci bir kural yok.
 *
 * <p>Maliyet: parmak izi grupları Vade Takvimi'nin kurum geneli belleğinden ({@code RenewalForecastService.sharedRenewalGroups},
 * ek sorgu yok); kapsam süzgeci (SY ya da UG takımı görülebilen AKTİF envanter alan adları) her çağrıda uygulanır. Sonuç
 * kapsam anahtarı başına {@code site.monitor.stats-renewal.cache-ms} (varsayılan 5 dk) bellekte.
 *
 * <p><b>Veri saklama sınırı (inceleme 2026-11-13):</b> parmak izi grupları {@code certificate_checks}'ten gelir ve o tablo
 * {@code site.monitor.series.certificate.retention-days} (varsayılan 180, en az 30) gün tutulur. Daha eski bir pencere
 * yenilemelerin ESKİ parmak izini göremez (yenileme sayılmaz) ama yenilenmeden dolanları görür → oran sistematik düşük
 * çıkardı. Her pencere saklama süresiyle sınırlanır: etkin başlangıç = max(pencere başı, şimdi − saklama günü); yanıt
 * {@code effective_days}, {@code effective_from}, {@code clamped} taşır, arayüz kısalan pencereyi açıkça yazar. Saklama
 * günü CANLI okunur (RetentionService.effectiveDays — gece temizliğinin kullandığı değer); bellek anahtarında da durur.
 */
@Service
@RequiredArgsConstructor
public class RenewalTimelinessStatsService {

    /** Pencereler (ay). */
    public static final int[] WINDOWS = {3, 6, 12};
    static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final RenewalForecastService forecast;
    private final CertificateInventoryRepository inventoryRepo;

    @Value("${site.monitor.stats-renewal.cache-ms:300000}")
    long cacheMs;
    private final TtlMemo<Map<String, Object>> memo = new TtlMemo<>(500);

    /** Saklama gününün kaynağı — yoksa (elle kurulan testler) politikanın varsayılanı. */
    @Autowired(required = false)
    RetentionService retentionService;

    public Map<String, Object> build(Predicate<Long> canViewTeam, String scopeKey) {
        int retention = retentionDays();
        return memo.get(scopeKey + "|r" + retention, cacheMs, false, () -> compute(canViewTeam, Instant.now(), retention));
    }

    /** {@code certificate_checks} için ETKİN saklama günü (gece temizliğinin değeri). Bilinmiyorsa politika varsayılanı. */
    int retentionDays() {
        RetentionPolicy p = RetentionCatalog.byId("series-certificate").orElse(null);
        if (p == null) return 180;
        try {
            if (retentionService != null) {
                int d = retentionService.effectiveDays(p);
                if (d > 0) return d;
            }
        } catch (RuntimeException e) {
            // Okunamadı → varsayılan (en kısa makul pencere değil ama ayarlanmamış ortamın gerçek değeri)
        }
        return p.defaultDays();
    }

    /** Test edilebilir çekirdek — {@code now} enjekte edilir. Okuma hatası fırlar (arayüz "okunamadı" der, asla %0). */
    Map<String, Object> compute(Predicate<Long> canViewTeam, Instant now) {
        return compute(canViewTeam, now, retentionDays());
    }

    Map<String, Object> compute(Predicate<Long> canViewTeam, Instant now, int retentionDays) {
        Set<String> domains = new HashSet<>();
        for (Object[] r : inventoryRepo.findActiveDomainTeams()) {
            if (r == null || r.length < 2 || r[0] == null) continue;
            Long sy = r[1] instanceof Number n ? Long.valueOf(n.longValue()) : null;
            Long ug = r.length > 2 && r[2] instanceof Number n2 ? Long.valueOf(n2.longValue()) : null;
            if (canViewTeam.test(sy) || (ug != null && canViewTeam.test(ug))) domains.add(String.valueOf(r[0]));
        }
        Map<String, List<RenewalTimelinessSection.Group>> groups = new HashMap<>();
        Map<String, List<Object[]>> raw = forecast.sharedRenewalGroups();
        for (String d : domains) {
            List<Object[]> gs = raw == null ? null : raw.get(d);
            if (gs == null) continue;
            List<RenewalTimelinessSection.Group> out = new ArrayList<>(gs.size());
            for (Object[] g : gs) {
                out.add(new RenewalTimelinessSection.Group(str(g[0]), str(g[1]), str(g[2])));
            }
            groups.put(d, out);
        }
        List<Map<String, Object>> windows = new ArrayList<>();
        for (int months : WINDOWS) {
            Instant nominal = now.atZone(IST).minusMonths(months).toInstant();
            Instant oldest = now.minus(retentionDays, ChronoUnit.DAYS);
            boolean clamped = oldest.isAfter(nominal);
            Instant from = clamped ? oldest : nominal;
            // Hedef süre burada sınıf için kullanılmaz (oran yalnız "dolmadan" ayrımına bakar) → 0
            List<RenewalTimelinessSection.Renewal> rs = RenewalTimelinessSection.detect(groups, from, now, d -> 0, null);
            List<RenewalTimelinessSection.Lapse> ls = RenewalTimelinessSection.lapses(groups, from, now, now, null);
            int before = 0, after = 0, lastMinute = 0;
            for (RenewalTimelinessSection.Renewal r : rs) {
                if (r.leadDays() < 0) after++;
                else {
                    before++;
                    if (r.leadDays() < RenewalTimelinessSection.LAST_MINUTE_DAYS) lastMinute++;
                }
            }
            int denom = rs.size() + ls.size();
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("months", months);
            w.put("from", ISO.format(from));
            w.put("nominal_from", ISO.format(nominal));
            w.put("effective_days", ChronoUnit.DAYS.between(from, now));
            w.put("clamped", clamped);
            w.put("renewals", rs.size());
            w.put("before_expiry", before);
            w.put("after_expiry", after);
            w.put("last_minute", lastMinute);
            w.put("lapses", ls.size());
            w.put("on_time_pct", denom == 0 ? null : Math.round(1000.0 * before / denom) / 10.0);
            windows.add(w);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windows", windows);
        out.put("domains", domains.size());
        out.put("retention_days", retentionDays);
        out.put("generated_at", ISO.format(now));
        return out;
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
}

package com.sitemonitor.service;

import com.sitemonitor.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Başarısız-login ANOMALİ detektörü — katmanlı kurallar. Tek kaynak {@code audit_log}
 * ({@code LOGIN_FAILED} satırları); ikinci bir ham-olay tablosu AÇILMAZ. Bu sınıf yalnız
 * DEĞERLENDİRME yapar (saf, yan-etkisiz): bir zaman penceresi verilir, {@link AnomalyReport}
 * döner. Zamanlama/kilit, pencere-kaçırmama (lastScanAt), incident lifecycle, e-posta ve
 * cooldown orkestrasyonu {@code FailedLoginAnomalyScheduler} + incident servisinin işidir.
 *
 * <p>Kurallar (her biri bağımsız tetiklenir; hepsi AppSettings ile canlı eşikli):
 * <ul>
 *   <li>R1 GLOBAL_VOLUME — pencere toplam başarısız ≥ eşik</li>
 *   <li>R2 ACCOUNT_TARGETED — tek hesaba ≥ eşik (hedefli saldırı)</li>
 *   <li>R3 IP_BRUTE_FORCE — tek IP'den ≥ eşik</li>
 *   <li>R4 IP_CREDENTIAL_STUFFING — tek IP ≥ eşik FARKLI kullanıcı (credential stuffing / enumeration)</li>
 *   <li>R5 DISTRIBUTED — tek hesap ≥ eşik FARKLI IP (dağıtık saldırı)</li>
 *   <li>R6 RELATIVE_SPIKE — pencere ≥ çarpan × taban-ortalama VE ≥ zemin (görece anomali)</li>
 * </ul>
 * Parola değeri hiçbir sorguda/raporda yer almaz (audit_log zaten saklamıyor).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailedLoginAnomalyService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Bilinen failure-reason makine kodları (dağılım için); gerisi OTHER. */
    private static final List<String> KNOWN_REASON_CODES =
            List.of("BAD_PASSWORD", "UNKNOWN_USER", "TEMP_PASSWORD_EXPIRED", "INVALID_TOKEN");

    private final AuditLogRepository auditLogRepo;
    private final AppSettingsService appSettings;

    @Value("${site.monitor.failed-login.window-minutes:10}")                        int windowMinutesDefault;
    @Value("${site.monitor.failed-login.threshold-total:20}")                       int thresholdTotalDefault;
    @Value("${site.monitor.failed-login.threshold-per-account:5}")                  int thresholdPerAccountDefault;
    @Value("${site.monitor.failed-login.threshold-per-ip:15}")                      int thresholdPerIpDefault;
    @Value("${site.monitor.failed-login.threshold-distinct-users-per-ip:5}")        int thresholdDistinctUsersPerIpDefault;
    @Value("${site.monitor.failed-login.threshold-distinct-ips-per-account:5}")     int thresholdDistinctIpsPerAccountDefault;
    @Value("${site.monitor.failed-login.relative-multiplier:3.0}")                  double relativeMultiplierDefault;
    @Value("${site.monitor.failed-login.baseline-hours:24}")                        int baselineHoursDefault;
    @Value("${site.monitor.failed-login.relative-floor:8}")                         int relativeFloorDefault;

    // ── Sonuç modelleri ───────────────────────────────────────────────────────
    public record KV(String key, long count) {}
    public record RuleHit(String code, long actual, long threshold, String detail) {}

    public record AnomalyReport(
            String windowStart, String windowEnd, int windowMinutes,
            long total, long baselineAvgPerWindow,
            List<RuleHit> hits,
            List<KV> topAccounts, List<KV> topIps, List<KV> stuffingIps, List<KV> distributedAccounts,
            Map<String, Long> reasonDistribution) {

        public boolean anomalous() { return hits != null && !hits.isEmpty(); }

        /** Kural setinin kararlı imzası — cooldown/escalation "aynı anomali mi" kararında kullanılır. */
        public String rulesSignature() {
            List<String> codes = new ArrayList<>();
            if (hits != null) for (RuleHit h : hits) codes.add(h.code());
            codes.sort(String::compareTo);
            return String.join(",", codes);
        }
    }

    // ── Değerlendirme ───────────────────────────────────────────────────────────

    public boolean isEnabled() {
        return appSettings.getBoolean("site.monitor.failed-login.enabled", true);
    }

    /** Şimdiki zamandan geriye nominal pencereyle değerlendir (çağrı kolaylığı). */
    public AnomalyReport evaluateNow() {
        int windowMinutes = appSettings.getInt("site.monitor.failed-login.window-minutes", windowMinutesDefault);
        Instant now = Instant.now();
        String end = ISO.format(now);
        String start = ISO.format(now.minusSeconds(windowMinutes * 60L));
        return evaluate(start, end, windowMinutes);
    }

    /**
     * (windowStart, windowEnd] penceresini değerlendir. {@code nominalWindowMinutes} taban-ortalama
     * kova boyutu için kullanılır (görece kural). Catch-up'ta pencere nominalden uzun olabilir —
     * eşikler olduğu gibi uygulanır (uzun pencerede biraz daha hassas; güvenlik lehine, dokümante).
     */
    public AnomalyReport evaluate(String windowStart, String windowEnd, int nominalWindowMinutes) {
        long total = auditLogRepo.countFailedLoginsBetween(windowStart, windowEnd);

        List<KV> topAccounts        = kv(auditLogRepo.countFailedByActorSince(windowStart));
        List<KV> topIps             = kv(auditLogRepo.countFailedByIpSince(windowStart));
        List<KV> stuffingIps        = kv(auditLogRepo.countDistinctUsersPerIpSince(windowStart));
        List<KV> distributedAccounts= kv(auditLogRepo.countDistinctIpsPerActorSince(windowStart));
        Map<String, Long> reasons   = reasonDistribution(windowStart, windowEnd, total);
        long baselineAvg            = computeBaselineAvgPerWindow(windowStart, nominalWindowMinutes);

        // Eşikler — canlı (AppSettings), @Value fallback.
        long tTotal   = appSettings.getInt("site.monitor.failed-login.threshold-total", thresholdTotalDefault);
        long tAcc     = appSettings.getInt("site.monitor.failed-login.threshold-per-account", thresholdPerAccountDefault);
        long tIp      = appSettings.getInt("site.monitor.failed-login.threshold-per-ip", thresholdPerIpDefault);
        long tStuff   = appSettings.getInt("site.monitor.failed-login.threshold-distinct-users-per-ip", thresholdDistinctUsersPerIpDefault);
        long tDist    = appSettings.getInt("site.monitor.failed-login.threshold-distinct-ips-per-account", thresholdDistinctIpsPerAccountDefault);
        double relMul = appSettings.getDouble("site.monitor.failed-login.relative-multiplier", relativeMultiplierDefault);
        long relFloor = appSettings.getInt("site.monitor.failed-login.relative-floor", relativeFloorDefault);

        List<RuleHit> hits = new ArrayList<>();

        // R1 — genel hacim
        if (total >= tTotal)
            hits.add(new RuleHit("GLOBAL_VOLUME", total, tTotal, total + " başarısız login"));

        // R2 — hesap odaklı
        KV topAcc = first(topAccounts);
        if (topAcc != null && topAcc.count() >= tAcc)
            hits.add(new RuleHit("ACCOUNT_TARGETED", topAcc.count(), tAcc, "'" + topAcc.key() + "'"));

        // R3 — IP odaklı (brute force)
        KV topIp = first(topIps);
        if (topIp != null && topIp.count() >= tIp)
            hits.add(new RuleHit("IP_BRUTE_FORCE", topIp.count(), tIp, topIp.key()));

        // R4 — IP başına farklı kullanıcı (credential stuffing / enumeration)
        KV topStuff = first(stuffingIps);
        if (topStuff != null && topStuff.count() >= tStuff)
            hits.add(new RuleHit("IP_CREDENTIAL_STUFFING", topStuff.count(), tStuff,
                    topStuff.key() + " → " + topStuff.count() + " farklı kullanıcı"));

        // R5 — dağıtık desen (hesap başına farklı IP)
        KV topDist = first(distributedAccounts);
        if (topDist != null && topDist.count() >= tDist)
            hits.add(new RuleHit("DISTRIBUTED", topDist.count(), tDist,
                    "'" + topDist.key() + "' → " + topDist.count() + " farklı IP"));

        // R6 — görece anomali (sabit eşiklere takılmasa da tabana göre sıçrama)
        if (baselineAvg > 0 && total >= relFloor && total >= relMul * baselineAvg) {
            long effThreshold = (long) Math.ceil(relMul * baselineAvg);
            hits.add(new RuleHit("RELATIVE_SPIKE", total, effThreshold,
                    "taban ort. " + baselineAvg + "/pencere, çarpan " + relMul));
        }

        return new AnomalyReport(windowStart, windowEnd, nominalWindowMinutes, total, baselineAvg,
                hits, topAccounts, topIps, stuffingIps, distributedAccounts, reasons);
    }

    /** Taban = önceki [windowStart-baselineHours, windowStart] aralığının pencere başına ortalaması. */
    private long computeBaselineAvgPerWindow(String windowStart, int windowMinutes) {
        int baselineHours = appSettings.getInt("site.monitor.failed-login.baseline-hours", baselineHoursDefault);
        if (baselineHours <= 0 || windowMinutes <= 0) return 0;
        Instant startInstant;
        try {
            startInstant = Instant.from(ISO.parse(windowStart));
        } catch (Exception e) {
            return 0;   // parse edilemezse görece kuralı devre dışı
        }
        String baselineStart = ISO.format(startInstant.minusSeconds(baselineHours * 3600L));
        long baselineTotal = auditLogRepo.countFailedLoginsBetween(baselineStart, windowStart);
        long buckets = Math.max(1L, (baselineHours * 60L) / windowMinutes);
        return baselineTotal / buckets;
    }

    /** Failure-reason önek dağılımı — satır çekmeden (BAD_PASSWORD / UNKNOWN_USER / … / OTHER). */
    private Map<String, Long> reasonDistribution(String from, String to, long total) {
        Map<String, Long> dist = new LinkedHashMap<>();
        long accounted = 0;
        for (String code : KNOWN_REASON_CODES) {
            long c = auditLogRepo.countFailedByReasonLikeBetween(code + ":%", from, to);
            if (c > 0) { dist.put(code, c); accounted += c; }
        }
        long other = total - accounted;
        if (other > 0) dist.put("OTHER", other);
        return dist;
    }

    private static List<KV> kv(List<Object[]> rows) {
        List<KV> out = new ArrayList<>();
        if (rows != null) for (Object[] r : rows) {
            String key = r[0] == null ? "" : r[0].toString();
            long count = ((Number) r[1]).longValue();
            out.add(new KV(key, count));
        }
        return out;
    }

    private static KV first(List<KV> list) { return (list == null || list.isEmpty()) ? null : list.get(0); }
}

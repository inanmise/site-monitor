package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bölüm hesabının girdisi: AY SINIRLARI (Europe/Istanbul takvimi), üretim anı, hedefler ve bölümler arasında
 * PAYLAŞILAN tembel veri (envanter + son sertifika durumu — her biri özet başına en fazla BİR kez okunur).
 *
 * <p><b>Saat dilimi tuzağı (CLAUDE.md):</b> zaman damgaları UTC saklanır. "Eylül 2026" İstanbul'da 1 Eylül 00:00'da
 * başlar = 31 Ağustos 21:00 UTC. Bütün sorgular {@link #from()} / {@link #to()} anlarını kullanır; günlük UTC
 * kovası değil.
 */
public final class ExecutiveSummaryContext {

    public static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    /** Kayıtlı damgaların biçimi ({@code alert_events.created_at} vb.) — UTC, bölge eki yok. */
    public static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Saatlik rollup kovası ({@code monitor_check_hourly.hour_bucket}, 13 karakter, UTC). */
    public static final DateTimeFormatter HOUR_BUCKET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);

    /** Envanter satırının özetin kullandığı alanları. */
    public record InventoryRow(String domain, Long teamId, Long ugTeamId, Integer tier, String groupName,
                               String renewalPlannedAt) { }

    private final YearMonth month;
    private final Instant from;
    private final Instant to;
    private final Instant prevFrom;
    private final Instant now;
    private final double availabilityTarget;
    private final int renewalTargetDays;
    private final Supplier<List<CertificateDto>> latestCerts;
    private final Supplier<Map<String, InventoryRow>> inventory;
    private final Supplier<Map<Long, String>> teamNames;

    public ExecutiveSummaryContext(YearMonth month, Instant now, double availabilityTarget, int renewalTargetDays,
                                   Supplier<List<CertificateDto>> latestCerts,
                                   Supplier<Map<String, InventoryRow>> inventory,
                                   Supplier<Map<Long, String>> teamNames) {
        this.month = month;
        this.now = now;
        this.from = monthStart(month);
        this.to = monthStart(month.plusMonths(1));
        this.prevFrom = monthStart(month.minusMonths(1));
        this.availabilityTarget = availabilityTarget;
        this.renewalTargetDays = renewalTargetDays;
        this.latestCerts = memo(latestCerts);
        this.inventory = memo(inventory);
        this.teamNames = memo(teamNames);
    }

    /** Ayın İstanbul'daki ilk anı (UTC Instant). */
    public static Instant monthStart(YearMonth m) {
        return m.atDay(1).atStartOfDay(IST).toInstant();
    }

    public YearMonth month() { return month; }
    /** Ayın başı (dahil). */
    public Instant from() { return from; }
    /** Ayın sonu = sonraki ayın başı (hariç). */
    public Instant to() { return to; }
    /** Önceki ayın başı (önceki ay = [prevFrom, from)). */
    public Instant prevFrom() { return prevFrom; }
    public Instant now() { return now; }
    /** Ay bitti mi? */
    public boolean complete() { return !now.isBefore(to); }
    /** Ölçümün bittiği an: ay bittiyse ay sonu, değilse şimdi. */
    public Instant measuredUntil() { return complete() ? to : (now.isBefore(from) ? from : now); }
    /** Ayın şimdiye dek geçen (ya da tamamı) dakika sayısı. */
    public long elapsedMinutes() { return Math.max(0, Duration.between(from, measuredUntil()).toMinutes()); }
    /** Ayın gün sayısı (tam ay) ya da şimdiye dek başlamış gün sayısı (devam eden ay). */
    public int elapsedDays() {
        if (complete()) return month.lengthOfMonth();
        long d = Duration.between(from, measuredUntil()).toDays() + 1;
        return (int) Math.max(1, Math.min(month.lengthOfMonth(), d));
    }
    public double availabilityTarget() { return availabilityTarget; }
    /** Yenileme hedef süresi (gün); 0 = Vade Takvimi'nin tier bazlı yenileme süresi. */
    public int renewalTargetDays() { return renewalTargetDays; }
    public List<CertificateDto> latestCerts() { return latestCerts.get(); }
    public Map<String, InventoryRow> inventory() { return inventory.get(); }
    public Map<Long, String> teamNames() { return teamNames.get(); }

    public String fromIso() { return UTC_ISO.format(from); }
    public String toIso() { return UTC_ISO.format(to); }
    public String prevFromIso() { return UTC_ISO.format(prevFrom); }
    public String nowIso() { return UTC_ISO.format(now); }

    /** Tek seferlik tembel değer — bölümler aynı veriyi ikinci kez okumasın. */
    private static <T> Supplier<T> memo(Supplier<T> s) {
        return new Supplier<>() {
            private boolean done;
            private T value;
            @Override
            public synchronized T get() {
                if (!done) {
                    value = s == null ? null : s.get();
                    done = true;
                }
                return value;
            }
        };
    }
}

package com.sitemonitor.service.lockout;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * İlerleyici giriş kilidinin MERDİVENİ — tek kaynak (2026-10-09, kullanıcı adı numaralandırması).
 *
 * <p>Mevcut hesap ({@code UserService}, {@code app_users.failed_block_count / lockout_until / last_lockout_at}) ve
 * BİLİNMEYEN kullanıcı adı ({@link UnknownUserLockoutService}, {@code login_unknown_lockouts}) AYNI hesabı buradan yapar:
 * kademe → bir sonraki kilit için gereken taze hata sayısı, ihlal → kilit süresi, kilit anı / bitişi (saniyeye kırpılmış
 * UTC ISO, {@code Z}'siz — denetimin {@code event_time} biçimi) ve kalan süre. İki yol ayrı ayrı hesaplasaydı en küçük
 * sapma bile ("bilinmeyen ad hep 1. kademe") kilit yanıtından "bu hesap var mı" sorusunu yanıtlardı.
 *
 * <p>Kurallar ({@code site.monitor.lockout.*}): {@code failures-needed[k]} = k. kademedeyken kilidi tetikleyen hata sayısı
 * (listenin dışında 1); {@code durations-seconds[n-1]} = n. ihlalin süresi, son kademeden sonra SON süre yinelenir
 * (kalıcı kilide yükseltme yok — 2026-09-26 kararı). Liste boş / null ise varsayılanlar (30,120,600,1800 / 5,3,2,1).
 */
public final class LockoutLadder {

    /** Kilit damgalarının biçimi — {@code app_users} ve denetim kaydıyla AYNI (sözlüksel kıyas kronolojiktir). */
    public static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    static final List<Long> DEFAULT_DURATIONS = List.of(30L, 120L, 600L, 1800L);
    static final List<Integer> DEFAULT_FAILURES_NEEDED = List.of(5, 3, 2, 1);

    private final List<Long> durationsSecs;
    private final List<Integer> failuresNeeded;

    public LockoutLadder(List<Long> durationsSecs, List<Integer> failuresNeeded) {
        this.durationsSecs = durationsSecs == null || durationsSecs.isEmpty() ? DEFAULT_DURATIONS : durationsSecs;
        this.failuresNeeded = failuresNeeded == null ? DEFAULT_FAILURES_NEEDED : failuresNeeded;
    }

    /** Kilidin bir ihlali: yeni kademe, süre (sn), kilit anı ve bitişi (ISO, {@code Z}'siz UTC). */
    public record Step(int level, long seconds, String lockedAt, String lockoutUntil) {}

    /** Bu kademedeyken ({@code null} = 0) bir sonraki kilidi tetikleyen taze hata sayısı. */
    public int failuresNeeded(Integer level) {
        int l = level == null ? 0 : level;
        return l < failuresNeeded.size() ? failuresNeeded.get(l) : 1;
    }

    /** {@code offense}. ihlalin (1'den başlar) kilit süresi; son kademeden sonra son süre yinelenir. */
    public long durationSeconds(int offense) {
        return durationsSecs.get(Math.min(Math.max(offense, 1), durationsSecs.size()) - 1);
    }

    /** Tanımlı kademe sayısı (log için). */
    public int levels() {
        return durationsSecs.size();
    }

    /** Bir sonraki ihlal: kademe +1, süre o kademenin süresi, kilit {@code now}'da başlar. */
    public Step escalate(Integer currentLevel, Instant now) {
        int offense = (currentLevel == null ? 0 : currentLevel) + 1;
        long secs = durationSeconds(offense);
        return new Step(offense, secs, ISO.format(now), ISO.format(now.plusSeconds(secs)));
    }

    /**
     * {@code lockoutUntil}'e kalan TAM saniye ({@link Duration#getSeconds()} — aşağı yuvarlanır); boş, bozuk ya da dolmuş
     * kilitte 0. {@code UserService.checkLockout}'un eski satır içi hesabıyla birebir.
     */
    public static long remainingSeconds(String lockoutUntil, Instant now) {
        if (lockoutUntil == null || lockoutUntil.isBlank()) return 0L;
        try {
            long remaining = Duration.between(now, Instant.parse(lockoutUntil + "Z")).getSeconds();
            return Math.max(0L, remaining);
        } catch (Exception e) {
            return 0L;
        }
    }
}

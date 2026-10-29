package com.sitemonitor.service.checks;

import com.sitemonitor.service.evalstate.EvalStateSql;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Veritabanı-çapalı saat — {@link DbClock} uygulaması (2026-10-29, çok pod Sürüm 3-3 paket A; tasarım R3-3 §3.3).
 *
 * <p><b>Çapa:</b> {@value #SQL_DB_NOW} ({@code SystemMetricSampler.SQL_DB_NOW} ile aynı deyim), en çok {@link #ANCHOR_MS}'de
 * bir; gidiş-dönüşün ortası çapa anıdır, gidiş-dönüş {@link #MAX_ANCHOR_RTT_MS}'i aşarsa ölçüm atılır.
 * {@code nowMs() = çapaDb + (nanoTime − çapaNano) / 1e6} — ek sorgu yok.
 *
 * <p><b>Tembel (K-1):</b> kurucu hiçbir sorgu çalıştırmaz; İLK {@link #nowMs()} çağrısı çapalar. Saatin tek kullanıcıları
 * kuyruk / işçi kaydı / boşaltıcı / gönderme tıkıdır ve hepsi ana anahtar açıkken çağrılır — anahtar açılıştan beri kapalıysa
 * bu deyim hiç koşmaz. Yenileme çağıran iş parçacığında yapılır ama yarışsız: yalnız bir iş parçacığı yeniler
 * ({@code tryLock}), diğerleri eski çapadan ilerler; başarısız deneme {@link #ANCHOR_MS} boyunca tekrarlanmaz (veritabanı
 * kesintisinde her çağrı bekletilmez). Deyimin zaman aşımı {@link #QUERY_TIMEOUT_SECONDS} sn.
 *
 * <p><b>Kesinti:</b> çapa okunamazsa son başarılı çapadan {@link #MAX_EXTRAPOLATE_MS}'e (5 dk) dek ilerlenir; sonra pod
 * saatine düşülür ve {@link #stale()} true döner (Küme kartı {@code db_clock_stale}). Hiç çapalanmadıysa da pod saati + stale.
 * Asla fırlatmaz.
 */
@Slf4j
@Component
public class AnchoredDbClock implements DbClock {

    /** Örnekleyiciyle aynı deyim. */
    public static final String SQL_DB_NOW = "SELECT CURRENT_TIMESTAMP";
    /** Çapa gidiş-dönüşü bundan uzunsa ölçüm atılır (ms) — çapa hatası gidiş-dönüşün yarısıyla sınırlı kalsın. */
    public static final long MAX_ANCHOR_RTT_MS = 2_000L;
    /** Çapa deyiminin sorgu zaman aşımı (sn). */
    public static final int QUERY_TIMEOUT_SECONDS = 3;

    /** Çapa okuyucusu: veritabanı saatini epoch ms döner; okunamazsa fırlatır / null döner. */
    @FunctionalInterface
    public interface DbNow {
        Long read() throws Exception;
    }

    private final DbNow dbNow;
    private final LongSupplier nanoClock;
    private final LongSupplier wallClock;
    private final ReentrantLock anchorLock = new ReentrantLock();

    /** Son başarılı çapa: DB anı (ms) + o anın monoton saati (ns). {@code anchorNano == Long.MIN_VALUE} = hiç yok. */
    private volatile long anchorDbMs;
    private volatile long anchorNano = Long.MIN_VALUE;
    /** Son çapa DENEMESİ (ns; başarılı ya da değil) — yeniden deneme sıklığı. */
    private volatile long lastAttemptNano = Long.MIN_VALUE;
    private volatile boolean warned;
    private volatile long anchors;
    private volatile long anchorFailures;

    /** Spring kurucusu — kendi sorgu zaman aşımlı şablonuyla okur. */
    @Autowired
    public AnchoredDbClock(JdbcTemplate jdbc) {
        this(reader(EvalStateSql.withQueryTimeout(jdbc, QUERY_TIMEOUT_SECONDS)), System::nanoTime, System::currentTimeMillis);
    }

    /** Test: okuyucu ve saatler verilir. */
    public AnchoredDbClock(DbNow dbNow, LongSupplier nanoClock, LongSupplier wallClock) {
        this.dbNow = dbNow;
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
        this.wallClock = wallClock == null ? System::currentTimeMillis : wallClock;
    }

    @Override
    public long nowMs() {
        maybeAnchor(nanoClock.getAsLong());
        long nano = nanoClock.getAsLong();   // çapa ölçümü sürmüş olabilir — güncel an
        long an = anchorNano;
        if (an == Long.MIN_VALUE) return wallClock.getAsLong();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(nano - an);
        if (elapsedMs > MAX_EXTRAPOLATE_MS) return wallClock.getAsLong();
        return anchorDbMs + Math.max(0L, elapsedMs);
    }

    @Override
    public boolean stale() {
        long an = anchorNano;
        if (an == Long.MIN_VALUE) return true;
        return TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - an) > MAX_EXTRAPOLATE_MS;
    }

    /** Başarılı çapa sayısı (System Health / testler). */
    public long anchors() {
        return anchors;
    }

    /** Başarısız çapa sayısı. */
    public long anchorFailures() {
        return anchorFailures;
    }

    private void maybeAnchor(long nano) {
        long last = lastAttemptNano;
        if (last != Long.MIN_VALUE && TimeUnit.NANOSECONDS.toMillis(nano - last) < ANCHOR_MS) return;
        if (!anchorLock.tryLock()) return;
        try {
            last = lastAttemptNano;
            if (last != Long.MIN_VALUE && TimeUnit.NANOSECONDS.toMillis(nano - last) < ANCHOR_MS) return;
            lastAttemptNano = nano;
            anchor();
        } finally {
            anchorLock.unlock();
        }
    }

    /** Çapa ölçümü (kilit altında). */
    private void anchor() {
        try {
            long t0 = nanoClock.getAsLong();
            Long db = dbNow == null ? null : dbNow.read();
            long t1 = nanoClock.getAsLong();
            if (db == null || t1 < t0 || TimeUnit.NANOSECONDS.toMillis(t1 - t0) > MAX_ANCHOR_RTT_MS) {
                failed(null);
                return;
            }
            anchorDbMs = db;
            anchorNano = t0 + (t1 - t0) / 2;
            anchors++;
            if (warned) {
                warned = false;
                log.info("Dağıtık kontrol saati yeniden veritabanına çapalandı");
            }
        } catch (Exception e) {
            failed(e);
        }
    }

    private void failed(Exception e) {
        anchorFailures++;
        if (!warned && stale()) {
            warned = true;
            log.warn("Dağıtık kontrol saati veritabanına çapalanamadı — pod saati kullanılıyor (kira kararları pod saatine dayanır): {}",
                    e == null ? "ölçülemedi" : e.getClass().getSimpleName());
        } else if (e != null) {
            log.debug("Dağıtık kontrol saati çapası okunamadı (eski çapadan ilerleniyor): {}", e.getClass().getSimpleName());
        }
    }

    private static DbNow reader(JdbcTemplate jdbc) {
        if (jdbc == null) return () -> null;
        return () -> jdbc.query(SQL_DB_NOW, rs -> rs.next() ? epochMs(rs) : null);
    }

    /** Sürücünün döndürdüğü zaman damgası → epoch ms (PostgreSQL: Timestamp, H2: OffsetDateTime). */
    static Long epochMs(ResultSet rs) throws SQLException {
        Object o = rs.getObject(1);
        if (o instanceof OffsetDateTime odt) return odt.toInstant().toEpochMilli();
        if (o instanceof ZonedDateTime zdt) return zdt.toInstant().toEpochMilli();
        if (o instanceof Instant i) return i.toEpochMilli();
        if (o instanceof Timestamp ts) return ts.getTime();
        Timestamp ts = rs.getTimestamp(1);
        return ts == null ? null : ts.getTime();
    }
}

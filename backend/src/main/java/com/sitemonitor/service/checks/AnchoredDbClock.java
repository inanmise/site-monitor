package com.sitemonitor.service.checks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Veritabanı-çapalı saat — {@link DbClock} uygulaması (2026-10-29, çok pod Sürüm 3-3 paket A; tasarım R3-3 §3.3).
 *
 * <p><b>Çapa:</b> PostgreSQL'de {@value #SQL_DB_NOW_PG} (DUVAR saati — {@code CURRENT_TIMESTAMP} bir işlem içinde işlemin
 * BAŞLANGICINI döner), diğer türde {@value #SQL_DB_NOW}; en çok {@link #ANCHOR_MS}'de bir. Deyim havuzdan alınan KENDİ
 * bağlantısında (otomatik commit) koşar — çağıranın Spring işlemine ASLA katılmaz ({@code NOT_SUPPORTED} eşdeğeri, inceleme
 * QW-L3). Önce bağlantı alınır, YALNIZ deyim (yürütme + okuma) ölçülür (inceleme QW-L4: havuz beklemesi ölçümü bozmasın — havuz
 * doyduğunda çapa atılıp pod saatine düşülmesin); deyimin gidiş-dönüşünün ortası çapa anıdır, deyim
 * {@link #MAX_ANCHOR_RTT_MS}'i aşarsa ölçüm atılır. {@code nowMs() = çapaDb + (nanoTime − çapaNano) / 1e6} — ek sorgu yok.
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

    /** Örnekleyiciyle aynı deyim (PostgreSQL dışı / tür bilinmiyor). */
    public static final String SQL_DB_NOW = "SELECT CURRENT_TIMESTAMP";
    /** PostgreSQL: işlem başlangıcı değil gerçek an. */
    public static final String SQL_DB_NOW_PG = "SELECT clock_timestamp()";
    /** Çapa DEYİMİNİN gidiş-dönüşü bundan uzunsa ölçüm atılır (ms) — çapa hatası gidiş-dönüşün yarısıyla sınırlı kalsın. */
    public static final long MAX_ANCHOR_RTT_MS = 2_000L;
    /** Çapa deyiminin sorgu zaman aşımı (sn). */
    public static final int QUERY_TIMEOUT_SECONDS = 3;

    /** Çapa okuyucusu: veritabanı saatini epoch ms döner; okunamazsa fırlatır / null döner (ölçüm çağrının çevresinde). */
    @FunctionalInterface
    public interface DbNow {
        Long read() throws Exception;
    }

    /**
     * Ölçümlü okuma: DB anı + yalnız DEYİMİN başlangıç / bitiş monoton anı (bağlantı beklemesi dahil değil).
     *
     * @param dbMs      veritabanı saati (epoch ms; okunamadıysa null)
     * @param startNano deyim yürütülmeden hemen önce
     * @param endNano   sonuç okunduktan hemen sonra
     */
    public record Sample(Long dbMs, long startNano, long endNano) {}

    /** Ölçümlü çapa okuyucusu. */
    @FunctionalInterface
    public interface TimedDbNow {
        Sample read() throws Exception;
    }

    private final TimedDbNow dbNow;
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

    /** Spring kurucusu — şablonun veri kaynağından KENDİ bağlantısıyla okur (çağıranın işlemine katılmaz). */
    @Autowired
    public AnchoredDbClock(JdbcTemplate jdbc) {
        this(jdbc == null ? null : jdbc.getDataSource(), System::nanoTime, System::currentTimeMillis);
    }

    /** Veri kaynağı + saatler (test / elle kurulum): bağlantı önce, yalnız deyim ölçülür. */
    public AnchoredDbClock(DataSource dataSource, LongSupplier nanoClock, LongSupplier wallClock) {
        this(connectionReader(dataSource, nanoClock == null ? System::nanoTime : nanoClock), nanoClock, wallClock, true);
    }

    /** Test: okuyucu ve saatler verilir (ölçüm okuyucu çağrısının çevresinde). */
    public AnchoredDbClock(DbNow dbNow, LongSupplier nanoClock, LongSupplier wallClock) {
        this(timedAround(dbNow, nanoClock == null ? System::nanoTime : nanoClock), nanoClock, wallClock, true);
    }

    private AnchoredDbClock(TimedDbNow dbNow, LongSupplier nanoClock, LongSupplier wallClock, boolean timed) {
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
            Sample s = dbNow == null ? null : dbNow.read();
            Long db = s == null ? null : s.dbMs();
            long t0 = s == null ? 0L : s.startNano();
            long t1 = s == null ? 0L : s.endNano();
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

    /** Eski okuyucu sözleşmesi: ölçüm okuyucu çağrısının çevresinde. */
    private static TimedDbNow timedAround(DbNow dbNow, LongSupplier nano) {
        if (dbNow == null) return () -> null;
        return () -> {
            long t0 = nano.getAsLong();
            Long db = dbNow.read();
            long t1 = nano.getAsLong();
            return new Sample(db, t0, t1);
        };
    }

    /**
     * Veri kaynağından okuyucu: havuzdan KENDİ bağlantısı ({@code DataSource.getConnection()} — Spring'in işleme bağlı
     * bağlantısı DEĞİL, otomatik commit), tür bağlantının üst verisinden (bilinen cevap ezberlenir), deyim zaman aşımı
     * {@link #QUERY_TIMEOUT_SECONDS} sn; ölçüm yalnız deyimin yürütülmesi + okunması.
     */
    static TimedDbNow connectionReader(DataSource ds, LongSupplier nano) {
        if (ds == null) return () -> null;
        AtomicReference<Boolean> postgres = new AtomicReference<>();
        return () -> {
            try (Connection c = ds.getConnection()) {   // havuz beklemesi ölçüme GİRMEZ
                Boolean pg = postgres.get();
                if (pg == null) {
                    pg = isPostgres(c);
                    if (pg != null) postgres.set(pg);
                }
                String sql = Boolean.TRUE.equals(pg) ? SQL_DB_NOW_PG : SQL_DB_NOW;
                try (Statement st = c.createStatement()) {
                    st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    long t0 = nano.getAsLong();
                    try (ResultSet rs = st.executeQuery(sql)) {
                        Long v = rs.next() ? epochMs(rs) : null;
                        long t1 = nano.getAsLong();
                        return new Sample(v, t0, t1);
                    }
                }
            }
        };
    }

    private static Boolean isPostgres(Connection c) {
        try {
            String p = c.getMetaData().getDatabaseProductName();
            return p == null ? null : p.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (Exception e) {
            return null;
        }
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

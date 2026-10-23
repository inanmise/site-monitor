package com.sitemonitor.service.schedule;

import com.sitemonitor.service.ExtendedHealthService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.health.ClusterHealthStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * "Kümede BAŞKA canlı pod var mı?" (2026-10-23, çok pod Sürüm 3-1 3e; tasarım R3-1 §3.2). Tarama lideri cron yan işlerini
 * ancak başka bir canlı pod varken erteler / tur tabanlı ağır işleri ona bırakır ({@link SideJobPlacement}); tek pod'da
 * bu sınıf "yok" der ve her şey bugünkü gibi koşar (değişmez I-3).
 *
 * <p><b>Kaynak:</b> R2b'nin heartbeat satırları ({@code system_heartbeat}: {@code instance_id}, {@code shutdown_marker}) —
 * Sistem Sağlığı pod listesinin ({@code ExtendedHealthService.podStates}) "canlı" kuralının AYNISI: {@code instance_id} dolu,
 * bu pod değil ({@code SchedulerService.instanceId()}), kapanış işareti yok ve son heartbeat ≥ şimdi −
 * {@value ExtendedHealthService#POD_LIVE_MINUTES} dk. 20.166.0 pod'ları (NULL {@code instance_id}) görünmez = sayılmaz
 * (güvenli: lider bugünkü gibi koşar). Aynı POD ADININ başka kimliği ({@code ClusterHealthStore.podOf}) de sayılmaz: bu
 * pod'un kirli yeniden başlatmadan önceki süreci (FX-SIDE #3 — tek pod'da 3 dk boyunca "başka canlı pod" sanılıyordu).
 *
 * <p><b>Önbellek:</b> karar pod başına {@value #CACHE_MS} ms tutulur (dakikada en çok iki küçük sorgu). Okunamazsa (tablo /
 * kolon yok, bağlantı hatası) sonuç BİLİNMİYOR = "başka canlı pod yok" sayılır — bilinmeyende asla ertelenmez / bırakılmaz;
 * bu karar da aynı süre önbelleğe alınır (veritabanı darboğazında her tıkta yeniden sorgu atılmasın). Hiç fırlatmaz.
 */
@Slf4j
@Component
public class PodLiveness {

    /** Kararın önbellek süresi (ms). */
    public static final long CACHE_MS = 30_000L;

    /**
     * Pencere içinde instance başına kapanış işareti sayısı. {@code recorded_at >= şimdi − canlılık penceresi} ile satır varsa
     * son heartbeat zaten pencere içindedir (R2b {@code podStates}: {@code !last.isBefore(liveSince)}).
     */
    static final String SQL_LIVE = "SELECT instance_id, SUM(CASE WHEN shutdown_marker = TRUE THEN 1 ELSE 0 END) "
            + "FROM system_heartbeat WHERE recorded_at >= ? AND instance_id IS NOT NULL GROUP BY instance_id";

    /** Önbellekteki son karar: an (ms), başka canlı pod sayısı (null = bilinmiyor) ve adları. */
    private record Snapshot(long atMs, Integer others, List<String> pods) {}

    private final JdbcTemplate jdbc;
    private final Supplier<String> self;
    private final LongSupplier clock;
    private volatile Snapshot snapshot;
    private final Object refreshLock = new Object();

    @Autowired
    public PodLiveness(JdbcTemplate jdbc) {
        this(jdbc, SchedulerService::instanceId, System::currentTimeMillis);
    }

    /** Testler: aynı veritabanını paylaşan iki "pod" kendi kimliği ve ortak saatle. */
    public PodLiveness(JdbcTemplate jdbc, String self, LongSupplier clock) {
        this(jdbc, () -> self, clock);
    }

    PodLiveness(JdbcTemplate jdbc, Supplier<String> self, LongSupplier clock) {
        this.jdbc = jdbc;
        this.self = self;
        this.clock = clock;
    }

    /**
     * Bu pod DIŞINDAKİ canlı pod sayısı; {@code Optional.empty()} = bilinmiyor (okunamadı). En çok {@value #CACHE_MS} ms
     * eski olabilir. Hiç fırlatmaz.
     */
    public Optional<Integer> otherLivePods() {
        long now = clock.getAsLong();
        Snapshot s = snapshot;
        if (fresh(s, now)) return Optional.ofNullable(s.others());
        synchronized (refreshLock) {
            s = snapshot;
            if (fresh(s, now)) return Optional.ofNullable(s.others());
            Snapshot next = read(now);
            snapshot = next;
            return Optional.ofNullable(next.others());
        }
    }

    /** Başka canlı pod KESİN var mı (bilinmiyorsa false — erteleme / bırakma yapılmaz). */
    public boolean anotherLivePod() {
        return otherLivePods().map(n -> n > 0).orElse(false);
    }

    /**
     * Veritabanına GİTMEDEN son karar (Sistem Sağlığı satırı): null = hiç okunmadı, okunamadı ya da karar
     * {@code 2 × CACHE_MS}'ten eski.
     */
    public Boolean cachedAnotherLivePod() {
        Snapshot s = snapshot;
        if (s == null || s.others() == null) return null;
        long age = clock.getAsLong() - s.atMs();
        if (age < 0 || age > 2 * CACHE_MS) return null;
        return s.others() > 0;
    }

    /** Son okumada görülen başka canlı pod'ların örnek kimlikleri (veritabanına gitmez; okunmadıysa boş). */
    public List<String> cachedOtherPods() {
        Snapshot s = snapshot;
        return s == null || s.pods() == null ? List.of() : s.pods();
    }

    /** Önbelleği düşürür (sonraki soru veritabanını okur) — testler ve yönetici "yenile" için. */
    public void invalidate() {
        snapshot = null;
    }

    private static boolean fresh(Snapshot s, long now) {
        if (s == null) return false;
        long age = now - s.atMs();
        return age >= 0 && age < CACHE_MS;              // saat geri gittiyse yeniden oku
    }

    private Snapshot read(long now) {
        String me = self.get();
        String myPod = ClusterHealthStore.podOf(me);
        LocalDateTime since = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC)
                .minusMinutes(ExtendedHealthService.POD_LIVE_MINUTES);
        try {
            List<String> live = new ArrayList<>();
            jdbc.query(SQL_LIVE, rs -> {
                String id = rs.getString(1);
                long markers = rs.getLong(2);
                if (id == null || id.isBlank() || id.equals(me) || markers > 0) return;
                // FX-SIDE #3: aynı pod ADININ başka kimliği = bu pod'un ÖNCEKİ süreci (kirli yeniden başlatma: OOM / SIGKILL,
                // kapanış işareti yok) — "başka canlı pod" değil (bir host'ta tek uygulama: clearStaleLocksForThisHost ile aynı)
                if (myPod != null && myPod.equals(ClusterHealthStore.podOf(id))) return;
                live.add(id);
            }, since);
            live.sort(String::compareTo);
            return new Snapshot(now, live.size(), List.copyOf(live));
        } catch (Exception e) {
            log.debug("Canlı pod listesi okunamadı (heartbeat) — bilinmiyor sayılıyor, erteleme yapılmaz: {}", e.toString());
            return new Snapshot(now, null, List.of());
        }
    }
}

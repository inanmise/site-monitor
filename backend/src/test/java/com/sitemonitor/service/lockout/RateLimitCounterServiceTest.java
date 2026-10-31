package com.sitemonitor.service.lockout;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.lockout.RateLimitCounterService.Limit;
import com.sitemonitor.service.lockout.RateLimitCounterService.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pod'lar arası ORTAK oran sınırı (2026-10-23, çok pod Sürüm 2a — MP-31) — iki "pod" (iki servis örneği, ayrı bellek)
 * AYNI H2 veritabanında ve AYNI saatte ({@link MultiPodTestSupport}). Giriş ucunun sınırı: 60 sn pencerede 10 hata → 30 sn
 * blok (20.165.1'in bellek içi algoritmasıyla aynı). H2'de taşınabilir yol (koşullu UPDATE → INSERT … ON CONFLICT DO
 * NOTHING → UPDATE); PostgreSQL'in tek ifadelik upsert'i {@code RateLimitCounterPostgresTest}'te.
 */
class RateLimitCounterServiceTest {

    static final Limit LOGIN = new Limit(60_000L, 10, 30_000L);
    static final String KEY = RateLimitCounterService.key("login-ip", "10.1.2.3");

    MultiPodTestSupport mp;
    RateLimitCounterService podA;
    RateLimitCounterService podB;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        mp.jdbc().execute(RateLimitCounterSchemaPatch.DDL);
        podA = new RateLimitCounterService(mp.jdbc(), mp.clockSupplier());
        podB = new RateLimitCounterService(mp.jdbc(), mp.clockSupplier());
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    @Test
    @DisplayName("5 hata pod A'da + 5 hata pod B'de → 11. istek HANGİ pod'a düşerse düşsün blok; kalan süre iki pod'da aynı")
    void fivePlusFive_blocksOnEitherPod() {
        for (int i = 0; i < 5; i++) podA.hit(KEY, LOGIN);
        assertThat(podA.blockedSeconds(KEY)).isZero();
        assertThat(podB.blockedSeconds(KEY)).isZero();
        for (int i = 0; i < 4; i++) podB.hit(KEY, LOGIN);
        assertThat(podB.blockedSeconds(KEY)).as("9 hata — henüz blok yok").isZero();
        State tenth = podB.hit(KEY, LOGIN);
        assertThat(tenth.attempts()).isEqualTo(10);
        assertThat(tenth.memory()).isFalse();
        assertThat(podA.blockedSeconds(KEY)).isEqualTo(30L);
        assertThat(podB.blockedSeconds(KEY)).isEqualTo(30L);
        mp.advance(12_400L);
        assertThat(podA.blockedSeconds(KEY)).isEqualTo(18L);   // yukarı yuvarlanır (17,6 sn → 18)
        assertThat(podB.blockedSeconds(KEY)).isEqualTo(18L);
    }

    @Test
    @DisplayName("Pencere (60 sn) dolunca sayım sıfırdan başlar — 9 hata + 61 sn + 9 hata blok DEĞİL")
    void windowExpiry_resetsCount() {
        for (int i = 0; i < 9; i++) (i % 2 == 0 ? podA : podB).hit(KEY, LOGIN);
        mp.advance(60_001L);
        State s = podA.hit(KEY, LOGIN);
        assertThat(s.attempts()).isEqualTo(1);
        for (int i = 0; i < 8; i++) podB.hit(KEY, LOGIN);
        assertThat(podA.blockedSeconds(KEY)).isZero();
        assertThat(podB.hit(KEY, LOGIN).blockedUntil()).isGreaterThan(mp.now());   // pencerenin 10.'su
    }

    @Test
    @DisplayName("Blok dolduktan sonraki ilk hata YENİ pencere açar (eski 10 sayılmaz)")
    void blockExpiry_newWindow() {
        for (int i = 0; i < 10; i++) podA.hit(KEY, LOGIN);
        assertThat(podB.blockedSeconds(KEY)).isEqualTo(30L);
        mp.advance(30_000L);
        assertThat(podB.blockedSeconds(KEY)).isZero();
        State s = podB.hit(KEY, LOGIN);
        assertThat(s.attempts()).isEqualTo(1);
        assertThat(s.blockedUntil()).isZero();
        assertThat(podA.blockedSeconds(KEY)).isZero();
    }

    @Test
    @DisplayName("Gerçek oturum pod A'da kuruldu (clear) → pod B'deki sayım da sıfır (20.165.1'de yalnız A sıfırlanıyordu)")
    void clearOnA_resetsB() {
        for (int i = 0; i < 9; i++) podB.hit(KEY, LOGIN);
        podA.clear(KEY);
        State s = podB.hit(KEY, LOGIN);
        assertThat(s.attempts()).isEqualTo(1);
        assertThat(podB.blockedSeconds(KEY)).isZero();
        assertThat(mp.jdbc().queryForObject("SELECT count(*) FROM rate_limit_counters", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("Farklı IP'ler ayrı kova; anahtar sütuna sığmayan istemci verili değer SHA-256'ya iner (deterministik)")
    void keys() {
        for (int i = 0; i < 10; i++) podA.hit(KEY, LOGIN);
        String other = RateLimitCounterService.key("login-ip", "10.9.9.9");
        assertThat(podB.blockedSeconds(other)).isZero();
        String huge = "1.2.3.4" + "x".repeat(5_000);
        String k1 = RateLimitCounterService.key("login-ip", huge);
        assertThat(k1).startsWith("login-ip:#").hasSizeLessThanOrEqualTo(RateLimitCounterService.KEY_MAX);
        assertThat(RateLimitCounterService.key("login-ip", huge)).isEqualTo(k1);
        assertThat(RateLimitCounterService.key("login-ip", "  10.1.2.3 ")).isEqualTo(KEY);
        assertThat(RateLimitCounterService.key("login-ip", null)).isEqualTo("login-ip:?");
        podA.hit(k1, LOGIN);   // uzun anahtar da yazılır
        assertThat(mp.jdbc().queryForObject("SELECT attempts FROM rate_limit_counters WHERE limit_key = ?", Integer.class, k1))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Eşzamanlı 12 hata (iki pod, aynı IP, aynı an) → sayım KAYIPSIZ 12, blok yazılı")
    void concurrentHits_noLostUpdate() {
        List<Callable<State>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            RateLimitCounterService pod = i % 2 == 0 ? podA : podB;
            tasks.add(() -> pod.hit(KEY, LOGIN));
        }
        List<State> out = mp.race(tasks);
        assertThat(out).allMatch(s -> !s.memory());
        assertThat(mp.jdbc().queryForObject("SELECT attempts FROM rate_limit_counters WHERE limit_key = ?", Integer.class, KEY))
                .isEqualTo(12);
        assertThat(podA.blockedSeconds(KEY)).isEqualTo(30L);
    }

    @Test
    @DisplayName("Veritabanı yok / tablo yok → BELLEK yedeği (20.165.1): 10 hata → blok, okuma hatası girişi düşürmez")
    void dbDown_memoryFallback() {
        DriverManagerDataSource broken = new DriverManagerDataSource("jdbc:h2:mem:yok-" + System.nanoTime() + ";MODE=PostgreSQL", "sa", "");
        RateLimitCounterService pod = new RateLimitCounterService(new JdbcTemplate(broken), mp.clockSupplier());   // tablo YOK
        for (int i = 0; i < 9; i++) assertThat(pod.hit(KEY, LOGIN).memory()).isTrue();
        assertThat(pod.blockedSeconds(KEY)).isZero();
        pod.hit(KEY, LOGIN);
        assertThat(pod.blockedSeconds(KEY)).isEqualTo(30L);
        pod.clear(KEY);
        assertThat(pod.blockedSeconds(KEY)).isZero();
    }

    @Test
    @DisplayName("Veritabanısız örnek (bean'siz dilimli bağlam) = 20.165.1 bellek algoritması: 10 hata → 30 sn, blok bitince yeni pencere")
    void memoryOnly_mirrorsOldAlgorithm() {
        RateLimitCounterService memOnly = new RateLimitCounterService(null, mp.clockSupplier());
        for (int i = 0; i < 10; i++) memOnly.hit(KEY, LOGIN);
        assertThat(memOnly.blockedSeconds(KEY)).isEqualTo(30L);
        mp.advance(10_000L);
        assertThat(memOnly.blockedSeconds(KEY)).isEqualTo(20L);
        mp.advance(20_000L);
        assertThat(memOnly.blockedSeconds(KEY)).isZero();
        assertThat(memOnly.hit(KEY, LOGIN).attempts()).as("blok bitti → sayım yeniden").isEqualTo(1);
    }

    @Test
    @DisplayName("Saatlik temizlik: süresi dolan satır silinir, canlı (pencere / blok süren) satır kalır")
    void purge_dropsExpiredOnly() {
        String old = RateLimitCounterService.key("login-ip", "10.0.0.1");
        podA.hit(old, LOGIN);
        mp.advance(59_000L);
        podA.hit(KEY, LOGIN);
        mp.advance(2_000L);   // old: 61 sn (süresi doldu), KEY: 2 sn
        assertThat(podB.purge()).isEqualTo(1);
        assertThat(mp.jdbc().queryForList("SELECT limit_key FROM rate_limit_counters", String.class)).containsExactly(KEY);
    }

    @Test
    @DisplayName("remainingSeconds: yukarı yuvarlar, 0 / geçmiş → 0 (20.165.1 ile aynı)")
    void remaining() {
        assertThat(RateLimitCounterService.remainingSeconds(0L, 100L)).isZero();
        assertThat(RateLimitCounterService.remainingSeconds(100L, 100L)).isZero();
        assertThat(RateLimitCounterService.remainingSeconds(101L, 100L)).isEqualTo(1L);
        assertThat(RateLimitCounterService.remainingSeconds(30_100L, 100L)).isEqualTo(30L);
        assertThat(RateLimitCounterService.remainingSeconds(50L, 100L)).isZero();
    }

    @Test
    @DisplayName("release (2026-10-31, dbquery D-5): bloğu yazan deneme pod B'de geri verilince sayım 9'a iner, blok kalkar — iki pod'da")
    void release_undoesOneAttemptAndLiftsBlock() {
        for (int i = 0; i < 9; i++) podA.hit(KEY, LOGIN);
        State tenth = podB.hit(KEY, LOGIN);
        assertThat(podA.blockedSeconds(KEY)).isEqualTo(30L);
        podB.release(KEY, LOGIN, tenth);
        assertThat(podA.blockedSeconds(KEY)).as("geri verilen deneme bloğu yazmıştı → blok kalkar").isZero();
        assertThat(mp.jdbc().queryForObject("SELECT attempts FROM rate_limit_counters WHERE limit_key = ?", Integer.class, KEY))
                .isEqualTo(9);
        // Eşik altında geri verme: yalnız sayım düşer
        State s = podA.hit(KEY, LOGIN);
        assertThat(s.attempts()).isEqualTo(10);
        podA.release(KEY, LOGIN, podA.hit(KEY, LOGIN));   // 11. → geri → 10, blok sürer (10 ≥ eşik)
        assertThat(podB.blockedSeconds(KEY)).isEqualTo(30L);
        // null / sayım 0 → hiçbir şey (sayım eksiye inmez)
        podA.release(KEY, LOGIN, null);
        String other = RateLimitCounterService.key("login-ip", "10.7.7.7");
        podA.release(other, LOGIN, new State(1, 0L, false));
        assertThat(mp.jdbc().queryForObject("SELECT count(*) FROM rate_limit_counters WHERE limit_key = ?", Integer.class, other))
                .isZero();
    }

    @Test
    @DisplayName("release: bellek yedeğine sayılan deneme bellekten geri verilir (veritabanına dokunulmaz)")
    void release_memoryFallback() {
        DriverManagerDataSource broken = new DriverManagerDataSource("jdbc:h2:mem:yok-rel-" + System.nanoTime() + ";MODE=PostgreSQL", "sa", "");
        RateLimitCounterService pod = new RateLimitCounterService(new JdbcTemplate(broken), mp.clockSupplier());   // tablo YOK
        State last = null;
        for (int i = 0; i < 10; i++) last = pod.hit(KEY, LOGIN);
        assertThat(last.memory()).isTrue();
        assertThat(pod.blockedSeconds(KEY)).isEqualTo(30L);
        pod.release(KEY, LOGIN, last);
        assertThat(pod.blockedSeconds(KEY)).isZero();
        assertThat(pod.hit(KEY, LOGIN).attempts()).isEqualTo(10);
    }
}

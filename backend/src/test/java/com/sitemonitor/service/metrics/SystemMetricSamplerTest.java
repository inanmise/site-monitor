package com.sitemonitor.service.metrics;

import com.sitemonitor.model.SystemMetricSample;
import com.sitemonitor.repository.SystemMetricSampleRepository;
import com.sitemonitor.service.HttpMetricsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sistem metrik örnekleyicisi (2026-10-13): ilk örnek taban kurar (delta null, ömür boyu birikim tek dakikaya yazılmaz),
 * aynı dakika iki kez yazılmaz, sayaç geri sararsa artış yazılmaz, HTTP dakikaları tüketilen son {@code ts}'den sonrası
 * toplanır (iki zamanlayıcı kayarsa aynı dakika iki kez sayılmaz), yazım hatası akışı kırmaz, kapalıyken yazmaz.
 */
class SystemMetricSamplerTest {

    private final SystemMetricSampleRepository repo = mock(SystemMetricSampleRepository.class);
    private final HttpMetricsService http = mock(HttpMetricsService.class);

    private SystemMetricSampler sampler() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();   // başlatılmamış: okunamayan alanlar null kalır
        return new SystemMetricSampler(repo, mock(DataSource.class), exec, http);
    }

    private static Map<String, Object> minute(String ts, long count, long errors, long sumMs, long p95, long max) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", ts);
        m.put("count", count);
        m.put("errors", errors);
        m.put("sum_ms", sumMs);
        m.put("p95_ms", p95);
        m.put("max_ms", max);
        return m;
    }

    @Test
    @DisplayName("ilk örnek taban kurar (delta null), sonraki dakika artışı yazar, aynı dakika ikinci kez null")
    void deltasAndMinuteDedupe() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        Counter warn = Counter.builder("logback.events").tag("level", "warn").register(reg);
        Counter err = Counter.builder("logback.events").tag("level", "error").register(reg);
        warn.increment(40);
        CaffeineCacheManager caches = new CaffeineCacheManager();
        caches.setCaffeine(com.github.benmanes.caffeine.cache.Caffeine.newBuilder().recordStats());
        caches.setCacheNames(List.of("a"));
        SystemMetricSampler s = sampler();
        s.registry = reg;
        s.cacheManager = caches;

        SystemMetricSample first = s.capture(Instant.parse("2031-03-01T10:00:20Z"));
        assertThat(first).isNotNull();
        assertThat(first.getSampledAt()).isEqualTo("2031-03-01T10:00:00");
        assertThat(first.getInstanceId()).isNotBlank();
        assertThat(first.getLogWarnDelta()).as("ilk örnek: ömür boyu 40 uyarı tek dakikaya yazılmaz").isNull();
        assertThat(first.getGcCountDelta()).isNull();
        assertThat(first.getCacheHitsDelta()).isNull();
        assertThat(first.getHeapUsedMb()).isNotNull();
        assertThat(first.getThreads()).isPositive();
        assertThat(first.getDbActive()).as("Hikari değilse havuz bilinmiyor").isNull();

        assertThat(s.capture(Instant.parse("2031-03-01T10:00:50Z"))).as("aynı dakika").isNull();

        warn.increment(3);
        err.increment(1);
        caches.getCache("a").get("k");                 // ıska
        caches.getCache("a").put("k", "v");
        caches.getCache("a").get("k");                 // isabet
        SystemMetricSample second = s.capture(Instant.parse("2031-03-01T10:01:05Z"));
        assertThat(second.getLogWarnDelta()).isEqualTo(3L);
        assertThat(second.getLogErrorDelta()).as("hata sayacı ilk kez görüldüğü örnekte taban kurmuştu").isEqualTo(1L);
        assertThat(second.getCacheHitsDelta()).isEqualTo(1L);
        assertThat(second.getCacheMissesDelta()).isEqualTo(1L);
        assertThat(second.getGcCountDelta()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("sayaç geri sararsa (yeniden başlatma) negatif artış yazılmaz")
    void counterRollbackIsUnknown() {
        assertThat(SystemMetricSampler.delta(5, null)).isNull();
        assertThat(SystemMetricSampler.delta(5, 9L)).isNull();
        assertThat(SystemMetricSampler.delta(9, 5L)).isEqualTo(4L);
        assertThat(SystemMetricSampler.delta(5, 5L)).isZero();
    }

    @Test
    @DisplayName("HTTP: ilk örnek yalnız son biten dakika; sonra tüketilen ts'den sonrası TOPLANIR, p95/en uzun en kötüsü")
    void httpMinutesSummedOnce() {
        when(http.completedMinutesAfter(isNull(), anyInt()))
                .thenReturn(List.of(minute("2031-03-01T09:59:00", 10, 1, 500, 80, 120)));
        when(http.completedMinutesAfter(eq("2031-03-01T09:59:00"), anyInt()))
                .thenReturn(List.of(minute("2031-03-01T10:00:00", 4, 0, 40, 30, 50),
                        minute("2031-03-01T10:01:00", 6, 2, 60, 90, 70)));
        when(http.completedMinutesAfter(eq("2031-03-01T10:01:00"), anyInt())).thenReturn(List.of());
        SystemMetricSampler s = sampler();

        SystemMetricSample a = s.capture(Instant.parse("2031-03-01T10:00:05Z"));
        assertThat(a.getHttpRequests()).isEqualTo(10L);
        assertThat(a.getHttpErrors()).isEqualTo(1L);
        assertThat(a.getHttpMinutes()).isEqualTo(1);
        assertThat(a.getDeltaMinutes()).as("taban örnek").isNull();

        SystemMetricSample b = s.capture(Instant.parse("2031-03-01T10:02:05Z"));
        assertThat(b.getHttpRequests()).isEqualTo(10L);
        assertThat(b.getHttpErrors()).isEqualTo(2L);
        assertThat(b.getHttpSumMs()).isEqualTo(100L);
        assertThat(b.getHttpP95Ms()).isEqualTo(90L);
        assertThat(b.getHttpMaxMs()).isEqualTo(70L);
        assertThat(b.getHttpMinutes()).isEqualTo(2);
        assertThat(b.getDeltaMinutes()).as("10:00 → 10:02").isEqualTo(2);

        SystemMetricSample c = s.capture(Instant.parse("2031-03-01T10:03:05Z"));
        assertThat(c.getHttpRequests()).as("yeni biten dakika yok → bilinmiyor, 0 uydurulmaz").isNull();
        assertThat(c.getHttpMinutes()).isNull();
        assertThat(c.getDeltaMinutes()).isEqualTo(1);
        verify(http).completedMinutesAfter(eq("2031-03-01T10:01:00"), eq(SystemMetricSampler.HTTP_MAX_MINUTES + 1));
    }

    @Test
    @DisplayName("yazım hatası akışı kırmaz; kapalıyken hiç yazmaz")
    void tickNeverThrowsAndHonoursSwitch() {
        when(http.completedMinutesAfter(any(), anyInt())).thenReturn(List.of());
        when(repo.save(any())).thenThrow(new IllegalStateException("db yok"));
        SystemMetricSampler s = sampler();
        s.tick();
        verify(repo).save(any());

        SystemMetricSampler off = sampler();
        off.enabled = false;
        off.tick();
        verify(repo, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("logback.events yoksa bir kez bağlanır (FunctionCounter) ve örnek WARN artışını okur; varsa dokunulmaz")
    void bindsLogbackCounterOnlyWhenMissing() {
        ch.qos.logback.classic.LoggerContext ctx = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        java.util.function.IntSupplier metricFilters = () -> (int) ctx.getTurboFilterList().stream()
                .filter((f) -> f.getClass().getSimpleName().equals("MetricsTurboFilter")).count();
        int filtersBefore = metricFilters.getAsInt();
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        SystemMetricSampler s = sampler();
        s.registry = reg;
        s.bindLogEvents();
        s.bindLogEvents();
        assertThat(metricFilters.getAsInt()).as("iki çağrıda TEK filtre eklenir (çift filtre her satırı iki kez sayardı)")
                .isEqualTo(filtersBefore + 1);
        try {
            // Micrometer LogbackMetrics FunctionCounter kaydeder — find().counter() null döner (2026-10-13'te yakalanan hata)
            FunctionCounter warn = reg.find("logback.events").tag("level", "warn").functionCounter();
            assertThat(warn).isNotNull();
            assertThat(reg.find("logback.events").tag("level", "warn").meters()).as("tek kayıt").hasSize(1);
            double before = warn.count();
            org.slf4j.Logger logger = LoggerFactory.getLogger("sms-test");
            logger.warn("örnek uyarı");
            assertThat(warn.count() - before).as("en az bu satır sayıldı (başka iş parçacığının uyarısı da eklenebilir)").isGreaterThanOrEqualTo(1.0);

            SystemMetricSample base = s.capture(Instant.parse("2031-05-01T10:00:07Z"));
            assertThat(base.getLogWarnDelta()).as("ilk örnek taban").isNull();
            logger.warn("bir");
            logger.warn("iki");
            SystemMetricSample next = s.capture(Instant.parse("2031-05-01T10:01:07Z"));
            assertThat(next.getLogWarnDelta()).as("örnekleyici FunctionCounter'ı okur").isGreaterThanOrEqualTo(2L);
        } finally {
            s.unbindLogEvents();
        }
        assertThat(metricFilters.getAsInt()).as("çözülünce filtre kalkar").isEqualTo(filtersBefore);

        SimpleMeterRegistry existing = new SimpleMeterRegistry();
        FunctionCounter theirs = FunctionCounter.builder("logback.events", new java.util.concurrent.atomic.AtomicLong(5),
                java.util.concurrent.atomic.AtomicLong::get).tag("level", "warn").register(existing);
        SystemMetricSampler t = sampler();
        t.registry = existing;
        t.bindLogEvents();
        LoggerFactory.getLogger("sms-test").warn("ikinci uyarı");
        assertThat(existing.find("logback.events").tag("level", "warn").meters()).as("var olan sayaca ikinci filtre eklenmez")
                .containsExactly(theirs);
        assertThat(existing.find("logback.events").tag("level", "error").meters()).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("HTTP: kovası oluşmamış sessiz dakikalar da kapsanan dakikaya sayılır (istek/dk şişmez); tavanda alınan aralık")
    void httpMinutesSpanQuietGaps() {
        when(http.completedMinutesAfter(isNull(), anyInt())).thenReturn(List.of(minute("2031-03-01T10:01:00", 3, 0, 30, 10, 10)));
        // 10:02 ve 10:03'te istek yoktu ve döndürme gecikti → o dakikaların kovası yok; 10:04'te 6 istek
        when(http.completedMinutesAfter(eq("2031-03-01T10:01:00"), anyInt())).thenReturn(List.of(minute("2031-03-01T10:04:00", 6, 0, 60, 10, 10)));
        SystemMetricSampler s = sampler();
        s.capture(Instant.parse("2031-03-01T10:02:07Z"));
        SystemMetricSample b = s.capture(Instant.parse("2031-03-01T10:05:07Z"));
        assertThat(b.getHttpMinutes()).as("10:01 → 10:04 = 3 dakika").isEqualTo(3);
        assertThat(SystemMetricSampler.minutesBetween("bozuk", "2031-03-01T10:04:00")).isZero();
        assertThat(SystemMetricSampler.minutesBetween("2031-03-01T10:04:00", "2031-03-01T10:04:00")).isZero();
    }

    @Test
    @DisplayName("kapanış: işaret satırı yazılır (yalnız kimlik + dakika + işaret); kapalıyken yazılmaz; hata akışı kırmaz")
    void shutdownWritesMarker() {
        SystemMetricSampler s = sampler();
        s.shutdown();
        org.mockito.ArgumentCaptor<SystemMetricSample> cap = org.mockito.ArgumentCaptor.forClass(SystemMetricSample.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getShutdownMarker()).isTrue();
        assertThat(cap.getValue().getInstanceId()).isNotBlank();
        assertThat(cap.getValue().getHeapUsedMb()).as("ölçüm alanları boş — toplamlara karışmaz").isNull();
        assertThat(cap.getValue().getHttpRequests()).isNull();

        SystemMetricSampler off = sampler();
        off.enabled = false;
        off.shutdown();
        verify(repo, org.mockito.Mockito.times(1)).save(any());

        when(repo.save(any())).thenThrow(new IllegalStateException("db kapandı"));
        sampler().shutdown();
    }

    @Test
    @DisplayName("HTTP tavanı: tam 10 dakika kırpılma sayılmaz (önündeki sessiz boşluk sayılır); 11 gelirse en yeni 10 alınır")
    void httpCapDetectsRealTruncation() {
        List<Map<String, Object>> ten = new java.util.ArrayList<>();
        for (int m = 5; m < 15; m++) ten.add(minute(String.format("2031-03-01T10:%02d:00", m), 1, 0, 1, 1, 1));
        when(http.completedMinutesAfter(isNull(), anyInt())).thenReturn(List.of(minute("2031-03-01T10:00:00", 1, 0, 1, 1, 1)));
        when(http.completedMinutesAfter(eq("2031-03-01T10:00:00"), anyInt())).thenReturn(ten);
        SystemMetricSampler s = sampler();
        s.capture(Instant.parse("2031-03-01T10:01:07Z"));
        SystemMetricSample a = s.capture(Instant.parse("2031-03-01T10:15:07Z"));
        assertThat(a.getHttpRequests()).isEqualTo(10L);
        assertThat(a.getHttpMinutes()).as("10:00 → 10:14 = 14 dakika (10:01–10:04 sessiz)").isEqualTo(14);

        List<Map<String, Object>> eleven = new java.util.ArrayList<>();
        for (int m = 16; m < 27; m++) eleven.add(minute(String.format("2031-03-01T10:%02d:00", m), 1, 0, 1, 1, 1));
        when(http.completedMinutesAfter(eq("2031-03-01T10:14:00"), anyInt())).thenReturn(eleven);
        SystemMetricSample b = s.capture(Instant.parse("2031-03-01T10:27:07Z"));
        assertThat(b.getHttpRequests()).as("kırpıldı: en yeni 10 dakika").isEqualTo(10L);
        assertThat(b.getHttpMinutes()).as("alınan aralık 10:17 → 10:26").isEqualTo(10);
    }

    @Test
    @DisplayName("saat farkı (MP-37c): pod − veritabanı, gerçek H2 CURRENT_TIMESTAMP; kaymış pod saati farkı verir; "
            + "veritabanı yok / uzun gidiş-dönüş → null (0 uydurulmaz)")
    void clockSkewMeasuredAgainstDatabase() {
        com.sitemonitor.it.MultiPodTestSupport mp = com.sitemonitor.it.MultiPodTestSupport.create();
        try {
            SystemMetricSampler s = sampler();
            assertThat(s.measureClockSkew()).as("JdbcTemplate yok").isNull();
            s.jdbc = mp.jdbc();
            Long aligned = s.measureClockSkew();
            assertThat(aligned).isNotNull();
            assertThat(Math.abs(aligned)).as("aynı JVM saati").isLessThan(1_500L);

            s.wallClock = () -> System.currentTimeMillis() + 45_000L;   // pod saati 45 sn ileride
            s.schemaFailedPatches = () -> 3;                              // inceleme W-3: açılış yaması sonucu örnekte
            Long ahead = s.measureClockSkew();
            assertThat(ahead).isBetween(43_500L, 46_500L);
            SystemMetricSample sample = s.capture(Instant.parse("2031-03-01T11:00:07Z"));
            assertThat(sample.getClockSkewMs()).isBetween(43_500L, 46_500L);
            assertThat(sample.getSchemaFailedPatches()).isEqualTo(3);
            assertThat(s.lastClockSkewMs()).isBetween(43_500L, 46_500L);

            long[] ticks = {0L};
            s.wallClock = () -> { ticks[0] += 3_000L; return System.currentTimeMillis() + ticks[0]; };   // gidiş-dönüş 3 sn
            assertThat(s.measureClockSkew()).as("uzun gidiş-dönüş — belirsiz").isNull();

            s.jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class, inv -> { throw new IllegalStateException("db down"); });
            s.wallClock = System::currentTimeMillis;
            SystemMetricSample down = s.capture(Instant.parse("2031-03-01T11:01:07Z"));
            assertThat(down.getClockSkewMs()).isNull();
            assertThat(s.lastClockSkewMs()).as("son ölçüm başarısız → yayılmaz").isNull();

            s.enabled = false;
            assertThat(s.lastClockSkewMs()).isNull();
        } finally {
            mp.close();
        }
    }
}

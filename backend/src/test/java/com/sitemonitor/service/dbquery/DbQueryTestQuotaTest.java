package com.sitemonitor.service.dbquery;

import com.sitemonitor.controller.DbQueryApiException;
import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.lockout.RateLimitCounterSchemaPatch;
import com.sitemonitor.service.lockout.RateLimitCounterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Sorguyu dene" + "Maliyeti denetle" saatlik KÜME sınırı (inceleme 2026-10-31, O3 / K3) — iki "pod" (iki servis örneği, ayrı
 * bellek) AYNI H2 veritabanında: sayım pod'lar arası ortak, aşım 429 {@code RATE_LIMITED} + bekleme, reddedilen istek sayılmaz,
 * başka kullanıcı etkilenmez, ayar sınırlara kırpılır.
 */
class DbQueryTestQuotaTest {

    MultiPodTestSupport mp;
    AppSettingsService settings;
    DbQueryTestQuota podA;
    DbQueryTestQuota podB;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        mp.jdbc().execute(RateLimitCounterSchemaPatch.DDL);
        settings = mock(AppSettingsService.class);
        when(settings.getInt(eq(DbQueryTestQuota.SETTING), anyInt())).thenReturn(5);
        podA = new DbQueryTestQuota(new RateLimitCounterService(mp.jdbc()), settings);
        podB = new DbQueryTestQuota(new RateLimitCounterService(mp.jdbc()), settings);
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    private static MockHttpSession user(String name) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", name);
        return s;
    }

    @Test
    @DisplayName("3 deneme pod A + 2 deneme pod B = 5/saat → 6. deneme HANGİ pod'a düşerse düşsün 429; bekleme ≈ 1 saat; reddedilen sayılmaz")
    void countsAcrossPods() {
        MockHttpSession ayse = user("Ayse");
        for (int i = 0; i < 3; i++) podA.consume(ayse);
        podB.consume(user("ayse"));   // aynı kullanıcı (büyük-küçük harf) — aynı kova
        podB.consume(ayse);

        for (DbQueryTestQuota pod : new DbQueryTestQuota[]{podA, podB}) {
            assertThatThrownBy(() -> pod.consume(ayse))
                    .isInstanceOfSatisfying(DbQueryApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(429);
                        assertThat(e.code()).isEqualTo("RATE_LIMITED");
                        assertThat(e.getMessage()).contains("Saatlik deneme sınırına ulaşıldı (5/saat)").contains("dk sonra");
                        long wait = ((Number) e.extra().get("retry_after_seconds")).longValue();
                        assertThat(wait).isBetween(3_500L, 3_600L);
                    });
        }
        // Reddedilen istekler sayaca yazılmaz: sayaç 5'te kalır (blok uzamaz)
        Integer attempts = mp.jdbc().queryForObject("SELECT attempts FROM rate_limit_counters WHERE limit_key = ?",
                Integer.class, RateLimitCounterService.key(DbQueryTestQuota.SCOPE, "ayse"));
        assertThat(attempts).isEqualTo(5);

        // Başka kullanıcı etkilenmez
        podA.consume(user("mehmet"));
        podB.consume(user("mehmet"));
    }

    @Test
    @DisplayName("Ayar sınırlara kırpılır: 0 / okunamaz → 60, 1 → 5, 10 000 → 600")
    void perHourClamped() {
        RateLimitCounterService counters = new RateLimitCounterService(mp.jdbc());
        AppSettingsService s = mock(AppSettingsService.class);
        DbQueryTestQuota q = new DbQueryTestQuota(counters, s);
        when(s.getInt(eq(DbQueryTestQuota.SETTING), anyInt())).thenReturn(0);
        assertThat(q.perHour()).isEqualTo(DbQueryTestQuota.DEFAULT_PER_HOUR);
        when(s.getInt(eq(DbQueryTestQuota.SETTING), anyInt())).thenReturn(1);
        assertThat(q.perHour()).isEqualTo(DbQueryTestQuota.MIN_PER_HOUR);
        when(s.getInt(eq(DbQueryTestQuota.SETTING), anyInt())).thenReturn(10_000);
        assertThat(q.perHour()).isEqualTo(DbQueryTestQuota.MAX_PER_HOUR);
        when(s.getInt(eq(DbQueryTestQuota.SETTING), anyInt())).thenThrow(new IllegalStateException("ayar okunamadı"));
        assertThat(q.perHour()).isEqualTo(DbQueryTestQuota.DEFAULT_PER_HOUR);
    }

    @Test
    @DisplayName("İleti: dakika yukarı yuvarlanır, en az 1 dk; bekleme saniyesi ekte")
    void messageRounding() {
        assertThat(DbQueryTestQuota.limited(60, 61).getMessage()).contains("2 dk");
        assertThat(DbQueryTestQuota.limited(60, 5).getMessage()).contains("1 dk");
        assertThat(DbQueryTestQuota.limited(60, 61).extra()).containsEntry("retry_after_seconds", 61L);
    }
}

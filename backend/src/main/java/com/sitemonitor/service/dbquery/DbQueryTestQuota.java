package com.sitemonitor.service.dbquery;

import com.sitemonitor.controller.DbQueryApiException;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.lockout.RateLimitCounterService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * "Sorguyu dene" + "Maliyeti denetle" için kullanıcı başına KÜME çapında saatlik deneme sınırı (inceleme 2026-10-31, K3).
 *
 * <p><b>Neden.</b> Deneme yanıtı satır verisi döndürmez ama her kuralın ölçtüğü değeri ({@code actual}) döndürür: deneme başına
 * en çok {@value DbQueryRules#MAX_RULES} değer. Yalnız oturum beklemesi (10 sn, yeniden girişle sıfırlanır) dakikada ~60 değer
 * çekmeye izin veriyordu ve pod belleğindeki bir sayaç N pod'da N kat gevşerdi. Sayım veritabanında
 * ({@link RateLimitCounterService}, tablo {@code rate_limit_counters}, kapsam {@value #SCOPE} — KALICI ad): hangi pod'a
 * düşerse düşsün aynı sayı. İki uç TEK sayaç.
 *
 * <p><b>Kural.</b> Sabit pencere (ilk denemeyle başlar, {@value #WINDOW_MS} ms): pencere içinde
 * {@code site.monitor.dbquery.test-per-hour} (varsayılan {@value #DEFAULT_PER_HOUR}, {@value #MIN_PER_HOUR}–{@value #MAX_PER_HOUR})
 * denemeye ulaşınca bir saat bekleme; aşım 429 {@code RATE_LIMITED} + {@code retry_after_seconds} / {@code Retry-After}.
 * Reddedilen istek sayılmaz (beklemeyi uzatmaz). Veritabanı hatası bu pod'un bellek yedeğine sayılır (servisin kendi kuralı —
 * asla deneme düşürmez). Sınır bir YÜK / veri çekme sınırıdır; eşzamanlı iki isteğin aşımı eşzamanlılıkla sınırlıdır.
 */
@Service
public class DbQueryTestQuota {

    /** Sayaç kapsamı — KALICI (anahtar {@code dbquery-test:<kullanıcı>}). */
    public static final String SCOPE = "dbquery-test";
    public static final String SETTING = "site.monitor.dbquery.test-per-hour";
    public static final int DEFAULT_PER_HOUR = 60, MIN_PER_HOUR = 5, MAX_PER_HOUR = 600;
    static final long WINDOW_MS = 3_600_000L;

    private final RateLimitCounterService counters;
    private final AppSettingsService settings;

    public DbQueryTestQuota(RateLimitCounterService counters, AppSettingsService settings) {
        this.counters = counters;
        this.settings = settings;
    }

    /** Canlı sınır (ayar okunamazsa / aralık dışıysa varsayılan ya da sınıra kırpılmış). */
    public int perHour() {
        int v;
        try {
            v = settings == null ? DEFAULT_PER_HOUR : settings.getInt(SETTING, DEFAULT_PER_HOUR);
        } catch (RuntimeException e) {
            v = DEFAULT_PER_HOUR;
        }
        if (v <= 0) v = DEFAULT_PER_HOUR;
        return Math.max(MIN_PER_HOUR, Math.min(MAX_PER_HOUR, v));
    }

    /** Oturumun sayaç anahtarı — kullanıcı adı (küçük harf); adsız oturum (olmamalı) tek ortak kova. */
    static String keyOf(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        String name = u == null ? "?" : u.toString().trim().toLowerCase(Locale.ROOT);
        return RateLimitCounterService.key(SCOPE, name.isEmpty() ? "?" : name);
    }

    /**
     * Bir deneme sayar; sınır doluysa sayMADAN 429 fırlatır.
     *
     * @throws DbQueryApiException 429 {@code RATE_LIMITED} (bekleme saniyesi ekte)
     */
    public void consume(HttpSession session) {
        String key = keyOf(session);
        int limit = perHour();
        long wait = counters.blockedSeconds(key);
        if (wait > 0) throw limited(limit, wait);
        counters.hit(key, new RateLimitCounterService.Limit(WINDOW_MS, limit, WINDOW_MS));
    }

    static DbQueryApiException limited(int limit, long waitSeconds) {
        long minutes = Math.max(1L, (waitSeconds + 59) / 60);
        return DbQueryApiException.tooMany("RATE_LIMITED", Msg.t(
                "Saatlik deneme sınırına ulaşıldı (" + limit + "/saat) — " + minutes + " dk sonra yeniden deneyin.",
                "The hourly test limit was reached (" + limit + "/hour) — try again in " + minutes + " min."), waitSeconds);
    }
}

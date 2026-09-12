package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Sayfa kullanımı (2026-09-13, System Health "Kullanıcı / Oturum" #1): kullanıcılar giriş yaptıktan
 * sonra NEYİ kullanıyor? Kaynak: oturum ping'i (~15 sn) sekme görünürken {@code ?tab=} taşır.
 *
 * <p>Bellekte (gün, kullanıcı, sekme) → ping sayısı biriktirilir, dakikada bir {@code page_usage_daily}'ye
 * yazılır (update-then-insert: PG ve H2'de aynı; tek pod). Kişisel veri: yalnız kullanıcı adı ve sekme
 * anahtarı — URL parametreleri, alan adı, arama metni ASLA kaydedilmez. Sekme anahtarı beyaz listeye
 * uyar (küçük harf/rakam/tire, ≤40) — keyfi dize yazılmaz.
 *
 * <p>Gün {@link #ZONE} (Europe/Istanbul) gününüdür — panel de aynı dilimde gösterir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageUsageService {

    public static final String RETENTION_KEY = "site.monitor.page-usage.retention-days";
    /** Ping aralığı (sn) — dakika tahmini için: dakika ≈ ping × 15 / 60. */
    public static final int PING_SECONDS = 15;
    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Pattern TAB_OK = Pattern.compile("^[a-z0-9-]{1,40}$");
    private static final int BUFFER_MAX = 20_000;

    private final JdbcTemplate jdbc;

    /** Anahtar: (gün, kullanıcı, sekme) — değer: [ping sayısı, ilk görülme, son görülme]. */
    record Key(String day, String username, String tab) { }
    static final class Acc { long pings; String first; String last; }

    private final ConcurrentHashMap<Key, Acc> buffer = new ConcurrentHashMap<>();
    /** Kullanıcının en son görüldüğü sekme (bellek — yeniden başlatmada bugünün satırından türetilir). */
    private final ConcurrentHashMap<String, String[]> lastTab = new ConcurrentHashMap<>();

    /** Ping'ten çağrılır. Geçersiz sekme anahtarı sessizce yok sayılır. */
    public void record(String username, String tab) {
        if (username == null || tab == null) return;
        String t = tab.trim().toLowerCase(Locale.ROOT);
        if (!TAB_OK.matcher(t).matches()) return;
        String now = ISO.format(Instant.now());
        String day = Instant.now().atZone(ZONE).toLocalDate().toString();
        String user = username.trim().toLowerCase(Locale.ROOT);
        if (buffer.size() > BUFFER_MAX) flush();
        Acc a = buffer.computeIfAbsent(new Key(day, user, t), k -> new Acc());
        synchronized (a) {
            a.pings++;
            if (a.first == null) a.first = now;
            a.last = now;
        }
        lastTab.put(user, new String[]{t, now});
    }

    /** Kullanıcının son görüldüğü sekme + zamanı, yoksa null (bellek; yeniden başlatma sonrası bugünün satırından). */
    public String[] lastTabOf(String username) {
        if (username == null) return null;
        return lastTab.get(username.trim().toLowerCase(Locale.ROOT));
    }

    /** Dakikada bir: biriken sayaçları tabloya yaz. Hata olursa biriken veri bir sonraki tura kalır. */
    @Scheduled(fixedDelayString = "${site.monitor.page-usage.flush-ms:60000}", initialDelayString = "${site.monitor.page-usage.flush-ms:60000}")
    public synchronized void flush() {
        if (buffer.isEmpty()) return;
        Map<Key, Acc> snap = new HashMap<>();
        for (Map.Entry<Key, Acc> e : buffer.entrySet()) {
            Acc a = e.getValue();
            synchronized (a) { Acc c = new Acc(); c.pings = a.pings; c.first = a.first; c.last = a.last; snap.put(e.getKey(), c); }
        }
        try {
            for (Map.Entry<Key, Acc> e : snap.entrySet()) {
                Key k = e.getKey(); Acc a = e.getValue();
                int n = jdbc.update("UPDATE page_usage_daily SET pings = pings + ?, last_seen = ?, first_seen = COALESCE(first_seen, ?) WHERE day = ? AND username = ? AND tab = ?",
                        a.pings, a.last, a.first, k.day(), k.username(), k.tab());
                if (n == 0) jdbc.update("INSERT INTO page_usage_daily(day, username, tab, pings, first_seen, last_seen) VALUES (?,?,?,?,?,?)",
                        k.day(), k.username(), k.tab(), a.pings, a.first, a.last);
            }
            // Yazılanı düş — yazma sırasında gelen ping'ler kalır (fark alınır)
            for (Map.Entry<Key, Acc> e : snap.entrySet()) {
                Acc live = buffer.get(e.getKey());
                if (live == null) continue;
                synchronized (live) {
                    live.pings -= e.getValue().pings;
                    if (live.pings <= 0) buffer.remove(e.getKey());
                    else live.first = null;
                }
            }
        } catch (Exception ex) {
            log.debug("page_usage_daily yazılamadı, sonraki tura kaldı: {}", ex.toString());
        }
    }

    /** Son N günün satırları (panel için). */
    public List<Map<String, Object>> rowsSince(int days) {
        String since = Instant.now().atZone(ZONE).toLocalDate().minusDays(Math.max(0, days - 1)).toString();
        try {
            return jdbc.queryForList("SELECT day, username, tab, pings, first_seen, last_seen FROM page_usage_daily WHERE day >= ? ORDER BY day", since);
        } catch (Exception ex) {
            log.debug("page_usage_daily okunamadı: {}", ex.toString());
            return List.of();
        }
    }

    /** Test/bakım: bellekteki biriken kayıt sayısı. */
    int buffered() { return buffer.size(); }
}

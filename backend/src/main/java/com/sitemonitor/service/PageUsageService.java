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
 * yazılır (yarışa dayanıklı ekle-ya-da-topla; her pod kendi biriktirdiğini ekler). Kişisel veri: yalnız kullanıcı adı ve sekme
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

    /**
     * Dakikada bir: biriken sayaçları tabloya yaz. Hata olursa yazılamayan anahtarlar bir sonraki tura kalır.
     *
     * <p><b>Çok pod (2026-10-23, R2a · D2, MP-46).</b> Aynı kullanıcı iki pod'a ping atar (oturum JDBC deposunda paylaşılır):
     * günün ilk yazımında iki pod aynı (gün, kullanıcı, sekme) satırını "yok → ekle" ile yazmaya kalkınca ikinci INSERT birincil
     * anahtarda düşüyor, tüm tur iptal oluyordu — ama O ANA KADAR yazılmış anahtarlar bellekte DÜŞÜLMEDİĞİNDEN sonraki turda
     * yeniden yazılıp İKİ KEZ sayılıyordu. Artık her anahtar kendi yazımından HEMEN sonra düşülür (bir anahtar düşerse tur orada
     * durur, kalanlar sonraki tura); yazım yarışa dayanıklıdır: PostgreSQL'de tek {@code INSERT … ON CONFLICT DO UPDATE},
     * diğerlerinde (H2) UPDATE → INSERT → tekil ihlalde yeniden UPDATE. {@code last_seen} geriye gitmez (iki pod'un sırası).
     */
    @Scheduled(fixedDelayString = "${site.monitor.page-usage.flush-ms:60000}", initialDelayString = "${site.monitor.page-usage.flush-ms:60000}")
    public synchronized void flush() {
        if (buffer.isEmpty()) return;
        Map<Key, Acc> snap = new HashMap<>();
        for (Map.Entry<Key, Acc> e : buffer.entrySet()) {
            Acc a = e.getValue();
            synchronized (a) { Acc c = new Acc(); c.pings = a.pings; c.first = a.first; c.last = a.last; snap.put(e.getKey(), c); }
        }
        boolean pg = Boolean.TRUE.equals(postgres());
        for (Map.Entry<Key, Acc> e : snap.entrySet()) {
            Key k = e.getKey(); Acc a = e.getValue();
            try {
                write(k, a, pg);
            } catch (Exception ex) {
                log.debug("page_usage_daily yazılamadı, kalan anahtarlar sonraki tura kaldı: {}", ex.toString());
                return;
            }
            // Yazılanı HEMEN düş — yazma sırasında gelen ping'ler kalır (fark alınır).
            // ATOMİK-KOŞULLU kaldırma. Eskiden `synchronized (live) { ...; buffer.remove(key) }`
            // idi ve kilit haritadaki VARLIK üzerinde değil Acc NESNESİ üzerindeydi:
            // record() computeIfAbsent'ten döndükten SONRA ama synchronized'a girmeden önce
            // preempt edilirse, flush aynı Acc'i sıfırlayıp haritadan düşürüyor, sonra record()
            // kilidi alıp artık haritada olmayan YETİM nesneye pings++ yazıyordu — o ping DB'ye
            // hiç gitmiyordu. computeIfPresent silme ile eşzamanlı computeIfAbsent'i aynı kova
            // kilidinde serileştirir: yeni ping ya eski Acc'e ya taze bir Acc'e gider, ikisi de
            // haritada kalır.
            buffer.computeIfPresent(k, (key, live) -> {
                synchronized (live) {
                    live.pings -= a.pings;
                    if (live.pings <= 0) return null;   // haritadan düş (atomik)
                    live.first = null;
                    return live;
                }
            });
        }
    }

    static final String SQL_UPSERT_PG = "INSERT INTO page_usage_daily(day, username, tab, pings, first_seen, last_seen) "
            + "VALUES (?,?,?,?,?,?) ON CONFLICT (day, username, tab) DO UPDATE SET "
            + "pings = page_usage_daily.pings + EXCLUDED.pings, "
            + "first_seen = COALESCE(page_usage_daily.first_seen, EXCLUDED.first_seen), "
            + "last_seen = GREATEST(page_usage_daily.last_seen, EXCLUDED.last_seen)";
    static final String SQL_UPDATE = "UPDATE page_usage_daily SET pings = pings + ?, "
            + "last_seen = CASE WHEN last_seen IS NULL OR last_seen < ? THEN ? ELSE last_seen END, "
            + "first_seen = COALESCE(first_seen, ?) WHERE day = ? AND username = ? AND tab = ?";
    static final String SQL_INSERT = "INSERT INTO page_usage_daily(day, username, tab, pings, first_seen, last_seen) VALUES (?,?,?,?,?,?)";

    private void write(Key k, Acc a, boolean pg) {
        if (pg) {
            jdbc.update(SQL_UPSERT_PG, k.day(), k.username(), k.tab(), a.pings, a.first, a.last);
            return;
        }
        if (update(k, a) > 0) return;
        try {
            jdbc.update(SQL_INSERT, k.day(), k.username(), k.tab(), a.pings, a.first, a.last);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Başka pod aynı anda ilk satırı ekledi: bizimkini onun üstüne topla.
            if (update(k, a) == 0) throw dup;
        }
    }

    private int update(Key k, Acc a) {
        return jdbc.update(SQL_UPDATE, a.pings, a.last, a.last, a.first, k.day(), k.username(), k.tab());
    }

    /** Veritabanı PostgreSQL mi — yalnız BİLİNEN yanıt saklanır (okunamazsa bu tur taşınabilir yol, sonraki tur yeniden sorar). */
    private volatile Boolean postgres;

    private Boolean postgres() {
        Boolean p = postgres;
        if (p != null) return p;
        p = com.sitemonitor.service.schema.DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource());
        if (p != null) postgres = p;
        return p;
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

package com.sitemonitor.util;

import java.util.Collection;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Sunucu-içi kısa ömürlü sonuç belleği (2026-10-01, performans düzeltmesi H1/M6) — {@code PublicStatsController.cacheMs}
 * deseninin anahtarlı hâli: her kullanıcının dakikada bir yokladığı uçlar (menü alarm rozetleri, İzleme Panosu) aynı
 * görüş kapsamındaki kullanıcılar için DB'ye tekrar tekrar binmesin. HTTP yanıtı yine {@code no-store}'dur (NetScaler
 * bayat kopya tutmasın — CLAUDE.md "Things that bite"); bellek pod yeniden başlayınca sıfırdan dolar.
 *
 * <ul>
 *   <li>Anahtar = görüş kapsamı ({@link #scopeKey}) + çağıranın eklediği boyutlar (ör. pencere saati).</li>
 *   <li>{@code ttlMs ≤ 0} → bellek KAPALI (her çağrı hesaplar) — birim testlerinin varsayılanı.</li>
 *   <li>{@code fresh = true} → belleği atlar, hesaplar ve sonucu YAZAR (eylem sonrası tazeleme, sonraki yoklamalar da
 *       taze veriyi görür).</li>
 *   <li>Sınırlı: anahtar sayısı {@code maxKeys}'e ulaşınca önce süresi dolanlar atılır, yine doluysa tümü temizlenir
 *       (kapsam kombinasyonu sayısı kullanıcı sayısıyla sınırlı; bellek büyümesin).</li>
 * </ul>
 * Aynı anda iki ıskalama iki kez hesaplar (kilit yok, bilinçli — PublicStats ile aynı); {@code null} sonuç saklanmaz.
 * Saklanan değer ÇAĞIRANLAR ARASINDA PAYLAŞILIR — değiştirilmemelidir (çağıran üst düzeyi kopyalayıp ekler).
 */
public final class TtlMemo<V> {

    private record Entry<V>(V value, long at) {}

    private final ConcurrentHashMap<String, Entry<V>> map = new ConcurrentHashMap<>();
    private final int maxKeys;
    private final LongSupplier clock;

    public TtlMemo(int maxKeys) {
        this(maxKeys, System::currentTimeMillis);
    }

    /** Test kancası: saat enjekte edilir. */
    public TtlMemo(int maxKeys, LongSupplier clock) {
        this.maxKeys = Math.max(1, maxKeys);
        this.clock = clock;
    }

    public V get(String key, long ttlMs, boolean fresh, Supplier<V> compute) {
        if (ttlMs <= 0 || key == null) return compute.get();
        long now = clock.getAsLong();
        if (!fresh) {
            Entry<V> e = map.get(key);
            if (e != null && now - e.at() < ttlMs) return e.value();
        }
        V v = compute.get();
        if (v != null) {
            if (map.size() >= maxKeys && !map.containsKey(key)) evict(now, ttlMs);
            map.put(key, new Entry<>(v, now));
        }
        return v;
    }

    private void evict(long now, long ttlMs) {
        map.entrySet().removeIf(en -> now - en.getValue().at() >= ttlMs);
        if (map.size() >= maxKeys) map.clear();
    }

    public int size() { return map.size(); }

    public void clear() { map.clear(); }

    /**
     * Görüş kapsamı anahtarı: {@code all} → {@code "ALL"}; kapsam listesi yok → {@code "NONE"}; aksi halde SIRALI,
     * tekilleştirilmiş takım id'leri ({@code "T:3,14"}) — aynı takım kümesini gören iki kullanıcı aynı anahtarı paylaşır,
     * oturumdaki liste sırası anahtarı bölmez.
     */
    public static String scopeKey(boolean all, Collection<Long> teamIds) {
        if (all) return "ALL";
        if (teamIds == null) return "NONE";
        TreeSet<Long> ids = new TreeSet<>();
        for (Long id : teamIds) if (id != null) ids.add(id);
        StringJoiner j = new StringJoiner(",", "T:", "");
        for (Long id : ids) j.add(String.valueOf(id));
        return j.toString();
    }
}

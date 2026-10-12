package com.sitemonitor.util;

import java.util.Collection;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
 * Süre dolumunda aynı anahtar için eşzamanlı ıskalamalar TEK hesabı paylaşır (2026-10-09, performans: İzleme Panosu ~31
 * sorgu — 09:00 giriş dalgasında ya da dağıtımdan sonra N istek N kez hesaplıyordu); sahibi hata alırsa bekleyen kendi
 * hesabını yapar. {@code fresh} istekler beklemez (eylem sonrası tazeleme). {@code null} sonuç saklanmaz.
 * Saklanan değer ÇAĞIRANLAR ARASINDA PAYLAŞILIR — değiştirilmemelidir (çağıran üst düzeyi kopyalayıp ekler).
 */
public final class TtlMemo<V> {

    /** {@code maxAge}: değere özgü üst ömür (ör. hatalı sonuç kısa tutulur); varsayılan sınırsız (okumadaki ttl geçerli). */
    private record Entry<V>(V value, long at, long maxAge) {}

    private final ConcurrentHashMap<String, Entry<V>> map = new ConcurrentHashMap<>();
    /** Şu an hesaplanan anahtarlar — bekleyenler aynı sonucu alır. */
    private final ConcurrentHashMap<String, CompletableFuture<V>> inflight = new ConcurrentHashMap<>();
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
        return get(key, ttlMs, fresh, compute, null);
    }

    /**
     * {@code maxAgeFor}: hesaplanan değere göre üst ömür (ms) — okumadaki {@code ttlMs}'ten kısaysa o geçerli (2026-10-12:
     * hesaplanamayan bölümlü yönetici özeti biten ayın 6 saatlik belleğinde kalmasın). null → yalnız {@code ttlMs}.
     */
    public V get(String key, long ttlMs, boolean fresh, Supplier<V> compute, java.util.function.ToLongFunction<V> maxAgeFor) {
        if (ttlMs <= 0 || key == null) return compute.get();
        long now = clock.getAsLong();
        if (fresh) {
            V v = compute.get();
            store(key, v, clock.getAsLong(), ttlMs, maxAgeFor);
            return v;
        }
        Entry<V> e = map.get(key);
        if (e != null && now - e.at() < Math.min(ttlMs, e.maxAge())) return e.value();
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = inflight.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return running.join();
            } catch (CompletionException | java.util.concurrent.CancellationException ex) {
                return compute.get();   // sahibi başarısız: bekleyen kendi hesabını yapar (bugünkü davranış)
            }
        }
        try {
            V v = compute.get();
            store(key, v, clock.getAsLong(), ttlMs, maxAgeFor);
            mine.complete(v);
            return v;
        } catch (RuntimeException | Error ex) {
            mine.completeExceptionally(ex);
            throw ex;
        } finally {
            inflight.remove(key, mine);
        }
    }

    private void store(String key, V v, long now, long ttlMs, java.util.function.ToLongFunction<V> maxAgeFor) {
        if (v == null) return;
        if (map.size() >= maxKeys && !map.containsKey(key)) evict(now, ttlMs);
        long maxAge = maxAgeFor == null ? Long.MAX_VALUE : Math.max(0L, maxAgeFor.applyAsLong(v));
        map.put(key, new Entry<>(v, now, maxAge));
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

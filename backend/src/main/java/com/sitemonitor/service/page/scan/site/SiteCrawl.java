package com.sitemonitor.service.page.scan.site;

import com.sitemonitor.service.page.scan.PageResourceDiscovery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tüm site kaynak analizi (2026-11-15, kullanıcı isteği: "tüm site taranmalı, eksik analiz edilen sayfa olmamalı"; karar:
 * en çok 1000 sayfa / 60 dk, site haritası + her derinlikte iç bağlantılar) — tarama KUYRUĞU, saf (ağ yok).
 *
 * <p>Genişlik öncelikli: ana sayfanın bağlantıları (DOM sırası) → site haritası adresleri → taranan her sayfanın kendi
 * bağlantıları sırayla kuyruğa girer. Aday kuralı {@link SitePages#select} ile AYNI: ana sayfayla aynı şema + host + bağlantı
 * noktası, sayfa uzantısı ({@link SitePages#PAGE_EXT}), izlemenin hariç tutma desenleri, sondaki {@code /} farkı olan kopyalar
 * tek. Bellek sınırlı: bilinen (kuyrukta + taranmış) adres {@link #MAX_KNOWN} — aşan adres kuyruğa girmez, yalnız sayılır
 * ({@link #dropped()}); sayfa başına en çok {@link #MAX_LINKS_PER_PAGE} bağlantı değerlendirilir.
 */
public final class SiteCrawl {

    /** Bilinen adres tavanı (kuyrukta bekleyen + taranan). */
    public static final int MAX_KNOWN = 20_000;
    /** Tek sayfadan değerlendirilen en çok bağlantı. */
    public static final int MAX_LINKS_PER_PAGE = 2_000;
    /** Taşma sayacının tekilleştirme tavanı (64 bit özet kümesi); aşılınca sayaç "en az" olur ({@link #droppedAtLeast()}). */
    public static final int DROPPED_TRACK_MAX = 100_000;
    /** Kaynak etiketi (tel değeri): ana sayfa / iç sayfa bağlantısı, site haritası, önceki taramadan devralınan. */
    public static final String SOURCE_LINK = "link", SOURCE_SITEMAP = "sitemap", SOURCE_RESUME = "resume";

    /** Kuyruktaki adres ve nereden bulunduğu. */
    public record Entry(String url, String source) {}

    private final SitePages.Origin origin;
    private final Predicate<String> exclude;
    private final Set<String> known = new HashSet<>();
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private int dropped;
    /** Taşan adreslerin 64 bit özetleri — aynı adres birden çok sayfada geçse de bir kez sayılır. */
    private final Set<Long> droppedSeen = new HashSet<>();
    private boolean droppedAtLeast;
    private int fromSitemap;
    private int fromLinks;

    /**
     * @param mainUrl  izlemenin adresi (kendisi aday olmaz)
     * @param finalUrl ana sayfanın yönlendirme sonrası adresi (host / şema bundan; null = yok)
     * @param exclude  izlemenin hariç tutma yüklemi (null = yok)
     * @param skip     aday olmayacak adresler (ana sayfanın kırık bağlantıları, önceki taramada taranmış sayfalar)
     */
    public SiteCrawl(String mainUrl, String finalUrl, Predicate<String> exclude, Collection<String> skip) {
        this.origin = SitePages.Origin.of(finalUrl != null && !finalUrl.isBlank() ? finalUrl : mainUrl);
        this.exclude = exclude;
        markKnown(mainUrl);
        markKnown(finalUrl);
        if (skip != null) for (String s : skip) markKnown(s);
    }

    /** Adres artık aday olmaz (taranmış sayılır); kuyruk tavanına sayılır. */
    public void markKnown(String url) {
        if (url == null || url.isBlank()) return;
        String n = SitePages.normalized(PageResourceDiscovery.key(url));
        if (n != null) known.add(n);
    }

    /**
     * Adresleri sırayla değerlendirir, uygun olanları kuyruğun SONUNA ekler. En çok {@link #MAX_LINKS_PER_PAGE} adres
     * değerlendirilir. Dönen değer: eklenen sayı.
     */
    public int offer(Collection<String> urls, String source) {
        if (urls == null || origin == null) return 0;
        int added = 0;
        int seen = 0;
        for (String u : urls) {
            if (++seen > MAX_LINKS_PER_PAGE) break;
            String k = accept(u);
            if (k == null) continue;
            String n = SitePages.normalized(k);
            if (n == null || known.contains(n)) continue;
            if (known.size() >= MAX_KNOWN) { noteDropped(n); continue; }
            known.add(n);
            queue.addLast(new Entry(k, source));
            added++;
            if (SOURCE_SITEMAP.equals(source)) fromSitemap++;
            else if (SOURCE_LINK.equals(source)) fromLinks++;
        }
        return added;
    }

    /** Tavan dolu mu (yeni adres artık kuyruğa giremez). */
    public boolean full() {
        return known.size() >= MAX_KNOWN;
    }

    /** {@link #dropped()} alt sınır mı (tekilleştirme kümesi doldu; daha fazla adres de olabilir). */
    public boolean droppedAtLeast() {
        return droppedAtLeast;
    }

    /** Kalan adresler sayılamadan bırakıldı (ör. site haritası evresi tavan dolunca kesildi): sayaç alt sınırdır. */
    public void markDroppedAtLeast() {
        droppedAtLeast = true;
    }

    private void noteDropped(String n) {
        if (droppedSeen.size() >= DROPPED_TRACK_MAX) { droppedAtLeast = true; return; }
        if (droppedSeen.add(fnv64(n))) dropped++;
    }

    /** FNV-1a 64 bit (yalnız tekilleştirme; çakışma sayacı en kötü birkaç eksik gösterir). */
    static long fnv64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Kuyruğun önündeki adres (null = boş). */
    public Entry poll() {
        return queue.pollFirst();
    }

    /** Taranamayan adresi kuyruğun ÖNÜNE geri koyar (bütçe dolunca "taranmadı" listesinin başında kalsın). */
    public void pushFront(Entry e) {
        if (e != null) queue.addFirst(e);
    }

    /** Kuyrukta bekleyen adres sayısı. */
    public int queued() {
        return queue.size();
    }

    /** Bilinen adres tavanı yüzünden kuyruğa giremeyen TEKİL adres sayısı (bkz. {@link #droppedAtLeast()}). */
    public int dropped() {
        return dropped;
    }

    public int fromSitemap() {
        return fromSitemap;
    }

    public int fromLinks() {
        return fromLinks;
    }

    /** Kuyruğun anlık kopyası (sırayla), en çok {@code max} kayıt. */
    public List<Entry> remaining(int max) {
        List<Entry> out = new ArrayList<>(Math.min(Math.max(0, max), queue.size()));
        for (Entry e : queue) {
            if (out.size() >= max) break;
            out.add(e);
        }
        return out;
    }

    /** Aday adresin anahtarı (parçasız) ya da null — {@link SitePages#select} ile aynı kurallar. */
    String accept(String u) {
        if (u == null || u.isBlank() || u.length() > SitePages.MAX_URL_CHARS) return null;
        String k = PageResourceDiscovery.key(u);
        SitePages.Origin o = SitePages.Origin.of(k);
        if (o == null || !o.sameAs(origin)) return null;
        if (!SitePages.PAGE_EXT.contains(PageResourceDiscovery.ext(k))) return null;
        if (exclude != null && SitePages.safeTest(exclude, k)) return null;
        return k;
    }
}

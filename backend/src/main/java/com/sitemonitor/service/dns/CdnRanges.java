package com.sitemonitor.service.dns;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * CDN sağlayıcılarının YAYIMLADIĞI uç (edge) adres aralıkları (2026-11-18). DNS değişiklik tespiti, eski ve yeni değerlerin
 * TAMAMI aynı sağlayıcının aralığındaysa bunu "rotasyon" sayar: Cloudflare gibi bir vekil, aynı alan adı için soran
 * çözümleyiciye ve zamana göre havuzundan farklı adres çifti döndürür (ör. 188.114.96.3 → 188.114.96.5). Bu bir kayıt
 * değişikliği değildir; eskiden kümeler ayrık olduğu için her seferinde DNS_CHANGED alarmı açılıyordu.
 *
 * <p>Listeler sağlayıcının kendi yayınından ALINDIĞI tarihte koda yazılır ({@link #RETRIEVED}); çalışma anında dışarıya
 * çıkılmaz (kurum ağı / vekil). Sağlayıcı yeni bir aralık eklerse o aralıktaki adrese geçiş eskisi gibi "değişti"
 * sayılır (güvenli yön: alarm fazla, kayıp yok) — liste güncellenir. Yayımlanmamış ya da çok sık değişen ağlar
 * (Akamai, CloudFront…) burada YOK: onlar için izlemenin "beklenen değer" listesine CIDR aralığı yazılır.
 *
 * <p>Kurallar: yalnız sayısal IP değerleri sayılır (CNAME / MX / TXT değerleri hiçbir sağlayıcıya ait değildir); TEK bir
 * değer bile aralık dışındaysa ya da değerler iki ayrı sağlayıcıya dağılıyorsa sonuç {@code null}.
 */
public final class CdnRanges {

    private CdnRanges() {}

    /** Listelerin sağlayıcı yayınlarından alındığı gün. */
    public static final String RETRIEVED = "2026-11-18";

    /** Bir sağlayıcıyı tanımak için okunacak en çok değer (DNS kümeleri küçüktür; aşırı büyük girdi tanınmaz). */
    public static final int MAX_VALUES = 64;

    /** Sağlayıcı: görünen ad, yayın adresi, aralıklar. */
    public record Provider(String name, String source, List<IpRanges.Range> ranges) {
        boolean covers(byte[] address) {
            for (IpRanges.Range r : ranges) if (r.contains(address)) return true;
            return false;
        }
    }

    /** Cloudflare — https://www.cloudflare.com/ips-v4 ve /ips-v6. */
    static final List<String> CLOUDFLARE = List.of(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
            "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
            "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
            "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32",
            "2a06:98c0::/29", "2c0f:f248::/32");

    /** Fastly — https://api.fastly.com/public-ip-list. */
    static final List<String> FASTLY = List.of(
            "23.235.32.0/20", "43.249.72.0/22", "90.98.0.0/17", "90.98.128.0/18", "103.244.50.0/24",
            "103.245.222.0/23", "103.245.224.0/24", "104.156.80.0/20", "140.248.64.0/18", "140.248.128.0/17",
            "146.75.0.0/17", "151.101.0.0/16", "157.52.64.0/18", "167.82.0.0/17", "167.82.128.0/20",
            "167.82.160.0/20", "167.82.224.0/20", "172.111.64.0/18", "185.31.16.0/22", "199.27.72.0/21",
            "199.232.0.0/16",
            "2a04:4e40::/32", "2a04:4e42::/31", "2a04:4e44::/32");

    /** Bilinen sağlayıcılar (sıra önemsiz — aralıklar çakışmaz). */
    public static final List<Provider> PROVIDERS = List.of(
            provider("Cloudflare", "https://www.cloudflare.com/ips/", CLOUDFLARE),
            provider("Fastly", "https://api.fastly.com/public-ip-list", FASTLY));

    private static Provider provider(String name, String source, List<String> cidrs) {
        List<IpRanges.Range> out = new ArrayList<>(cidrs.size());
        for (String c : cidrs) {
            IpRanges.Range r = IpRanges.parse(c);
            if (r == null) throw new IllegalStateException("CDN aralığı okunamadı: " + name + " " + c);
            out.add(r);
        }
        return new Provider(name, source, List.copyOf(out));
    }

    /** Tek bir değerin sağlayıcısı; IP değilse ya da hiçbir aralıkta değilse null. */
    public static String providerOf(String value) {
        byte[] a = IpRanges.address(value);
        if (a == null) return null;
        for (Provider p : PROVIDERS) if (p.covers(a)) return p.name();
        return null;
    }

    /**
     * Değerlerin TAMAMI aynı sağlayıcının aralığındaysa onun adı; boş liste, IP olmayan değer, aralık dışı değer, iki
     * sağlayıcı ya da {@link #MAX_VALUES}'tan fazla değer → null.
     */
    public static String sameProvider(Collection<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_VALUES) return null;
        String found = null;
        for (String v : values) {
            String p = providerOf(v);
            if (p == null) return null;
            if (found == null) found = p;
            else if (!found.equals(p)) return null;
        }
        return found;
    }

    /** {@code "\n"} ile birleştirilmiş iki DNS değerinin BİRLİKTE tek sağlayıcıya ait olup olmadığı (boş satırlar atlanır). */
    public static String sameProviderOfLines(String a, String b) {
        List<String> all = new ArrayList<>();
        addLines(all, a);
        addLines(all, b);
        return sameProvider(all);
    }

    private static void addLines(List<String> out, String joined) {
        if (joined == null || joined.isBlank()) return;
        for (String s : joined.split("\n")) {
            String t = s.strip();
            if (!t.isEmpty()) out.add(t);
            if (out.size() > MAX_VALUES) return;
        }
    }
}

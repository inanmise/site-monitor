package com.sitemonitor.service.dns;

import java.util.Arrays;

/**
 * Sayısal IP adresi ve CIDR aralığı yardımcıları (2026-11-18, DNS beklenen-değer aralığı + CDN rotasyonu) — saf, DNS
 * sorgusu YAPMAZ, doğrusal, girdi uzunluğu sınırlı ({@link #MAX_TEXT}).
 *
 * <p>Ayrıştırıcı JDK'ya ({@code InetAddress}) bırakılmaz: kuralları JS ikiziyle ({@code
 * frontend/src/components/dns/ipRanges.js}) bire bir aynı olsun diye elle yazılmıştır; tek doğruluk tablosu
 * {@code IpRangesTest.PARSE_TABLE} / {@code MATCH_TABLE} (JS testi Java kaynağından okur).
 * <ul>
 *   <li>IPv4 yalnız dört bölümlü ondalık yazımla ({@code 010} gibi baştaki sıfır yok — sekizlik mi ondalık mı belirsiz).</li>
 *   <li>IPv6: 1–4 onaltılık basamaklı 8 grup; {@code ::} en çok bir kez; gömülü IPv4 yalnız son grupta; kapsam kimliği
 *       ({@code %eth0}) kabul edilmez.</li>
 *   <li>IPv4'e eşlenmiş IPv6 ({@code ::ffff:a.b.c.d}) IPv4 sayılır; IPv4 adresi IPv6 aralığıyla (ve tersi) eşleşmez.</li>
 *   <li>Aralık: {@code adres/önek}; önek 0–32 (IPv4) / 0–128 (IPv6), yalnız ASCII rakam. Ağ adresinin önekten sonraki
 *       bitleri yok sayılır ({@code 188.114.96.3/20} = {@code 188.114.96.0/20}).</li>
 * </ul>
 */
public final class IpRanges {

    private IpRanges() {}

    /** Kabul edilen en uzun girdi (adres + "/" + önek). */
    public static final int MAX_TEXT = 64;

    /** Bir CIDR aralığı: ağ baytları (4 ya da 16) + önek uzunluğu. */
    public record Range(byte[] network, int prefix) {
        /** IPv4 mü (4 bayt)? */
        public boolean v4() { return network.length == 4; }

        /** {@code address} bu aralığın içinde mi? Aile farklıysa (IPv4 ↔ IPv6) false. */
        public boolean contains(byte[] address) {
            if (address == null || address.length != network.length) return false;
            int full = prefix / 8;
            for (int i = 0; i < full; i++) if (network[i] != address[i]) return false;
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (network[full] & mask) == (address[full] & mask);
        }
    }

    /**
     * Kırpılan görünmez karakter mi? Java {@code strip()} ile JS {@code trim()} AYNI kümeyi kırpmaz (JS bölünmez boşluğu ve
     * BOM'u kırpar, Java kırpmaz; Java U+001F'yi kırpar, JS kırpmaz) — web / Excel'den yapıştırılan satırda iki taraf ayrışıyordu.
     * Tek küme, iki tarafta aynı ({@code ipRanges.js isTrimCode}): C0 denetim + boşluk (≤ 0x20), NEL, bölünmez boşluk, Unicode
     * boşluk ayırıcıları (Zs / Zl / Zp), BOM.
     */
    public static boolean isTrimChar(char c) {
        return c <= 0x20 || c == 0x85 || c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000 || c == 0xFEFF;
    }

    /** Baştaki / sondaki {@link #isTrimChar} karakterlerini kırpar (null → null). */
    public static String clean(String s) {
        if (s == null) return null;
        int a = 0;
        int b = s.length();
        while (a < b && isTrimChar(s.charAt(a))) a++;
        while (b > a && isTrimChar(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    /** Sayısal adres → baytlar (IPv4 4, IPv6 16; IPv4'e eşlenmiş IPv6 → 4 bayt). Geçersiz / ad / kapsamlı → null. */
    public static byte[] address(String s) {
        if (s == null) return null;
        String t = clean(s);
        if (t.isEmpty() || t.length() > MAX_TEXT) return null;
        if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1);
        if (t.isEmpty()) return null;
        boolean colon = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = ascii(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.' || c == ':';
            if (!ok) return null;
            if (c == ':') colon = true;
        }
        if (!colon) return v4Bytes(t);
        byte[] b = v6Bytes(t);
        if (b == null) return null;
        for (int i = 0; i < 10; i++) if (b[i] != 0) return b;
        if ((b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) return new byte[]{b[12], b[13], b[14], b[15]};
        return b;
    }

    /** {@code adres/önek} → aralık; önek yoksa ya da geçersizse null (tek adres için {@link #address}). */
    public static Range parse(String s) {
        if (s == null) return null;
        String t = clean(s);
        if (t.isEmpty() || t.length() > MAX_TEXT) return null;
        int slash = t.indexOf('/');
        if (slash <= 0 || slash != t.lastIndexOf('/')) return null;
        String p = t.substring(slash + 1);
        if (p.isEmpty() || p.length() > 3) return null;
        for (int i = 0; i < p.length(); i++) if (!ascii(p.charAt(i))) return null;
        byte[] net = address(t.substring(0, slash));
        if (net == null) return null;
        int prefix = Integer.parseInt(p);
        if (prefix > net.length * 8) return null;
        return new Range(net, prefix);
    }

    /** {@code value} (tek adres) {@code range} (CIDR metni) içinde mi? Biri okunamazsa false. */
    public static boolean contains(String range, String value) {
        Range r = parse(range);
        return r != null && r.contains(address(value));
    }

    /** İki metin AYNI adresi mi gösteriyor (farklı yazım dahil: {@code 2a06::3} = {@code 2a06:0:0::3})? */
    public static boolean sameAddress(String a, String b) {
        byte[] x = address(a);
        byte[] y = address(b);
        return x != null && y != null && Arrays.equals(x, y);
    }

    /** Dört bölümlü ondalık IPv4 mü (her bölüm 1–3 ASCII rakam, 0–255, baştaki sıfır yok)? */
    static boolean dottedQuad(String t) {
        return v4Bytes(t) != null;
    }

    private static boolean ascii(char c) {
        return c >= '0' && c <= '9';
    }

    /** Dört bölümlü ondalık IPv4 → 4 bayt; değilse null. */
    private static byte[] v4Bytes(String t) {
        byte[] out = new byte[4];
        int parts = 0;
        int start = 0;
        for (int i = 0; i <= t.length(); i++) {
            if (i < t.length() && t.charAt(i) != '.') {
                if (!ascii(t.charAt(i))) return null;
                continue;
            }
            int len = i - start;
            if (len < 1 || len > 3 || parts == 4) return null;
            if (len > 1 && t.charAt(start) == '0') return null;
            int n = Integer.parseInt(t, start, i, 10);
            if (n > 255) return null;
            out[parts++] = (byte) n;
            start = i + 1;
        }
        return parts == 4 ? out : null;
    }

    /** IPv6 metni → 16 bayt; değilse null ({@code ::} en çok bir kez, gömülü IPv4 yalnız son grupta). */
    private static byte[] v6Bytes(String t) {
        int dbl = t.indexOf("::");
        if (dbl >= 0 && t.indexOf("::", dbl + 1) >= 0) return null;
        String head = dbl >= 0 ? t.substring(0, dbl) : t;
        String tail = dbl >= 0 ? t.substring(dbl + 2) : "";
        if (dbl >= 0 && head.indexOf('.') >= 0) return null;
        int[] h = words(head);
        int[] tl = words(tail);
        if (h == null || tl == null) return null;
        int total = h.length + tl.length;
        if (dbl >= 0 ? total > 7 : total != 8) return null;
        int[] all = new int[8];
        System.arraycopy(h, 0, all, 0, h.length);
        System.arraycopy(tl, 0, all, 8 - tl.length, tl.length);
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[2 * i] = (byte) ((all[i] >> 8) & 0xFF);
            out[2 * i + 1] = (byte) (all[i] & 0xFF);
        }
        return out;
    }

    /** {@code a:b:c} → 16 bitlik kelimeler (son grup gömülü IPv4 olabilir → 2 kelime); boş metin → boş dizi. */
    private static int[] words(String s) {
        if (s.isEmpty()) return new int[0];
        String[] groups = s.split(":", -1);
        if (groups.length > 8) return null;
        int[] out = new int[groups.length + 1];
        int n = 0;
        for (int i = 0; i < groups.length; i++) {
            String g = groups[i];
            if (i == groups.length - 1 && g.indexOf('.') >= 0) {
                byte[] b = v4Bytes(g);
                if (b == null) return null;
                out[n++] = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
                out[n++] = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
                continue;
            }
            if (g.isEmpty() || g.length() > 4) return null;
            int w = 0;
            for (int k = 0; k < g.length(); k++) {
                int d = Character.digit(g.charAt(k), 16);
                if (d < 0) return null;
                w = (w << 4) | d;
            }
            out[n++] = w;
        }
        return Arrays.copyOf(out, n);
    }
}

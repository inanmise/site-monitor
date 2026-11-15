package com.sitemonitor.service.page.scan.site;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Tüm site kaynak analizi (2026-11-15) — site haritası okuma, SAF kurallar (ağ yok; tarama motoru dosyaları indirip buraya
 * verir).
 *
 * <p><b>XML ayrıştırıcı KULLANILMAZ</b> (XXE / dış varlık / milyar gülüş riski yok): {@code <loc>} öğeleri doğrusal bir
 * taramayla okunur — ad alanı öneki ({@code <sm:loc>}) ve büyük / küçük harf fark etmez, CDATA desteklenir, XML karakter
 * varlıkları ({@code &amp;} …) çözülür. Her döngü girdinin uzunluğuyla sınırlı (imleç hep ilerler); {@code <loc>} sayısı
 * {@link #MAX_LOCS_PER_FILE}, tek adres {@link #MAX_LOC_CHARS} ile sınırlı. Gzip'li dosya (.xml.gz) {@link #MAX_INFLATED_BYTES}
 * tavanıyla açılır (sıkıştırma bombası yok).
 */
public final class SitemapReader {

    /** robots.txt'ten alınan en çok site haritası adresi. */
    public static final int MAX_ROBOTS_SITEMAPS = 20;
    /** Tek dosyadan okunan en çok {@code <loc>}. */
    public static final int MAX_LOCS_PER_FILE = 50_000;
    /** Tek adresin en uzun hali (keşifle aynı tavan). */
    public static final int MAX_LOC_CHARS = 4096;
    /** Açılmış (gzip sonrası) en büyük dosya. */
    public static final long MAX_INFLATED_BYTES = 50L * 1024 * 1024;
    /** Kök öğenin arandığı baş kısım (site haritası dizini mi?). */
    static final int ROOT_SCAN_CHARS = 4096;

    private SitemapReader() {}

    /** Okunan dosyanın sonucu: dizin mi (çocukları site haritası), adresler, tavan yüzünden kesildi mi. */
    public record Parsed(boolean index, List<String> locs, boolean truncated) {}

    /**
     * robots.txt → {@code Sitemap:} satırları (sırayla, tekil, en çok {@link #MAX_ROBOTS_SITEMAPS}; yalnız mutlak http(s)
     * adresler; göreli adres taban adrese göre çözülür).
     */
    public static List<String> robotsSitemaps(String robots, String baseUrl) {
        Set<String> out = new LinkedHashSet<>();
        if (robots == null || robots.isEmpty()) return new ArrayList<>(out);
        int i = 0;
        int n = robots.length();
        while (i < n && out.size() < MAX_ROBOTS_SITEMAPS) {
            int end = robots.indexOf('\n', i);
            if (end < 0) end = n;
            String line = robots.substring(i, end).trim();
            i = end + 1;
            if (line.length() < 9 || !line.regionMatches(true, 0, "sitemap:", 0, 8)) continue;
            String v = line.substring(8).trim();
            int hash = v.indexOf(" #");
            if (hash >= 0) v = v.substring(0, hash).trim();
            String abs = absolute(v, baseUrl);
            if (abs != null) out.add(abs);
        }
        return new ArrayList<>(out);
    }

    /** Gövde gzip ise açar (≤ {@link #MAX_INFLATED_BYTES}), değilse UTF-8 metin. Açılamazsa {@link IOException}. */
    public static String decode(byte[] body) throws IOException {
        if (body == null || body.length == 0) return "";
        if (body.length >= 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(body.length * 4, 1 << 20));
                byte[] buf = new byte[8192];
                long total = 0;
                int r;
                while ((r = in.read(buf)) > 0) {
                    total += r;
                    if (total > MAX_INFLATED_BYTES) throw new IOException("site haritası açılınca çok büyük");
                    bos.write(buf, 0, r);
                }
                return bos.toString(StandardCharsets.UTF_8);
            }
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /** XML metninden {@code <loc>} değerleri (sırayla; boş / çok uzun olanlar atlanır). */
    public static Parsed parse(String xml) {
        List<String> locs = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return new Parsed(false, locs, false);
        boolean index = isIndex(xml);
        boolean truncated = false;
        int n = xml.length();
        int i = 0;
        while (i < n) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) break;
            int gt = xml.indexOf('>', lt + 1);
            if (gt < 0) break;
            if (isLocOpen(xml, lt + 1, gt)) {
                int close = closeOf(xml, gt + 1);
                if (close < 0) break;
                if (locs.size() >= MAX_LOCS_PER_FILE) { truncated = true; break; }
                String v = text(xml, gt + 1, close);
                if (v != null) locs.add(v);
                i = close + 1;
            } else {
                i = gt + 1;
            }
        }
        return new Parsed(index, locs, truncated);
    }

    /** Kök öğe {@code sitemapindex} mi (baş kısımda, önekli olabilir). */
    static boolean isIndex(String xml) {
        int lim = Math.min(xml.length(), ROOT_SCAN_CHARS);
        String head = xml.substring(0, lim).toLowerCase(Locale.ROOT);
        return head.contains("sitemapindex");
    }

    /** {@code <loc>} / {@code <ns:loc>} / {@code <loc ...>} açılış etiketi mi ({@code from}..{@code gt} arası). */
    private static boolean isLocOpen(String s, int from, int gt) {
        if (from >= gt) return false;
        char c0 = s.charAt(from);
        if (c0 == '/' || c0 == '!' || c0 == '?') return false;
        int end = from;
        while (end < gt && !Character.isWhitespace(s.charAt(end)) && s.charAt(end) != '/') end++;
        int len = end - from;
        if (len < 3) return false;
        if (!s.regionMatches(true, end - 3, "loc", 0, 3)) return false;
        return len == 3 || s.charAt(end - 4) == ':';
    }

    /**
     * {@code <loc>} metninin bittiği yer (kapanış etiketinin {@code <} konumu); CDATA içindeki {@code <} atlanır. Bulunamazsa -1.
     */
    private static int closeOf(String s, int from) {
        int i = from;
        int n = s.length();
        while (i < n) {
            int lt = s.indexOf('<', i);
            if (lt < 0) return -1;
            if (s.startsWith("<![CDATA[", lt)) {
                int end = s.indexOf("]]>", lt + 9);
                if (end < 0) return -1;
                i = end + 3;
                continue;
            }
            return lt;
        }
        return -1;
    }

    /** Öğe metni: CDATA soyulur, varlıklar çözülür, kırpılır; boş / çok uzun → null. */
    private static String text(String s, int from, int to) {
        if (to - from > MAX_LOC_CHARS * 2) return null;
        String raw = s.substring(from, to);
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            if (raw.startsWith("<![CDATA[", i)) {
                int end = raw.indexOf("]]>", i + 9);
                if (end < 0) break;
                sb.append(raw, i + 9, end);
                i = end + 3;
            } else {
                int next = raw.indexOf("<![CDATA[", i);
                String part = next < 0 ? raw.substring(i) : raw.substring(i, next);
                sb.append(unescape(part));
                i = next < 0 ? raw.length() : next;
            }
        }
        String v = sb.toString().trim();
        if (v.isEmpty() || v.length() > MAX_LOC_CHARS) return null;
        return v;
    }

    /** XML karakter varlıkları: {@code &amp; &lt; &gt; &quot; &apos; &#n; &#xh;}; tanınmayan varlık olduğu gibi kalır. */
    static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') { sb.append(c); i++; continue; }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 10) { sb.append(c); i++; continue; }
            String ent = s.substring(i + 1, semi);
            String rep = switch (ent) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                default -> numeric(ent);
            };
            if (rep == null) { sb.append(c); i++; continue; }
            sb.append(rep);
            i = semi + 1;
        }
        return sb.toString();
    }

    private static String numeric(String ent) {
        try {
            int cp;
            if (ent.startsWith("#x") || ent.startsWith("#X")) cp = Integer.parseInt(ent.substring(2), 16);
            else if (ent.startsWith("#")) cp = Integer.parseInt(ent.substring(1));
            else return null;
            if (cp <= 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return null;
            return new String(Character.toChars(cp));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Mutlak http(s) adres (göreli → taban adrese göre); geçersiz / başka şema → null. */
    static String absolute(String v, String baseUrl) {
        if (v == null || v.isBlank() || v.length() > MAX_LOC_CHARS) return null;
        try {
            URI u = baseUrl == null ? URI.create(v) : URI.create(baseUrl).resolve(v);
            String s = u.getScheme();
            if (s == null || u.getHost() == null) return null;
            s = s.toLowerCase(Locale.ROOT);
            if (!s.equals("http") && !s.equals("https")) return null;
            return u.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

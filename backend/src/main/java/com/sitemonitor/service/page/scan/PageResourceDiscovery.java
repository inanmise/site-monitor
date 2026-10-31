package com.sitemonitor.service.page.scan;

import com.sitemonitor.service.page.PageFetchCore;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Sayfa nesnelerinin keşfi (2026-10-31, Sayfa Bütünlüğü "Kaynak analizi") — SAF: HTML gövdesinden ve CSS metninden aday
 * nesneleri çıkarır, ağ işi yapmaz.
 *
 * <p>Kapsam (kullanıcı kararı "sayfa + tüm bağlantılar"): sayfanın YÜKLEDİKLERİ (stil, betik, görsel, yazı tipi, medya,
 * iframe, simge, CSS'in {@code url()} / {@code @import} ettikleri), sayfanın bağlantı verdiği BELGELER (pdf, ofis, arşiv,
 * medya dosyası…) ve diğer BAĞLANTILAR ({@code a[href]}). Belgeler ve bağlantılar ölçülür ama sayfa boyutuna, istek
 * sayısına ve nota KATILMAZ ({@code pageLoad=false}). JavaScript'in sonradan eklediği nesneler görünmez — ölçüm sunucudan
 * yapılır, betik çalıştırılmaz.
 *
 * <p>Tarayıcı gibi: görsel başına TEK aday ({@code src}, yoksa {@code srcset}'in ilki), {@code loading="lazy"} ve
 * otomatik oynatılmayan medya "geç yüklenir" olarak işaretlenir (sayfa boyutuna girer, yükleme süresi tahminine girmez).
 * Döngüler girdiyle sınırlıdır (CSS taraması doğrusal, düzenli ifade yok).
 */
public final class PageResourceDiscovery {

    public static final String HTML = "HTML", CSS = "CSS", JS = "JS", IMAGE = "IMAGE", FONT = "FONT", MEDIA = "MEDIA",
            IFRAME = "IFRAME", FAVICON = "FAVICON", DOCUMENT = "DOCUMENT", LINK = "LINK", OTHER = "OTHER";

    /** Sabit kategori sırası — arayüz ve analiz bu sırayla gösterir. */
    public static final List<String> CATEGORIES =
            List.of(HTML, CSS, JS, IMAGE, FONT, MEDIA, IFRAME, FAVICON, DOCUMENT, LINK, OTHER);

    /** Bir CSS dosyasından alınacak azami başvuru (dev bir CSS envanteri şişirmesin). */
    static final int MAX_CSS_REFS = 500;
    /** Taranacak azami CSS metni (karakter) — gövde zaten {@link PageFetchCore#MAX_BODY_BYTES} ile sınırlı. */
    static final int MAX_CSS_CHARS = 2_000_000;

    private static final Set<String> FONT_EXT = Set.of("woff", "woff2", "ttf", "otf", "eot");
    private static final Set<String> IMAGE_EXT =
            Set.of("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "bmp", "ico", "tif", "tiff", "jfif", "heic");
    private static final Set<String> MEDIA_EXT =
            Set.of("mp4", "webm", "ogg", "ogv", "mp3", "wav", "m4a", "m4v", "mov", "avi", "mkv", "flac", "aac", "m3u8");
    /** a[href] ile bağlantı verilen DOSYA uzantıları → BELGE (bağlantılı dosya). */
    private static final Set<String> DOCUMENT_EXT = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "xlsm", "ppt", "pptx", "pps", "ppsx", "odt", "ods", "odp", "rtf", "csv",
            "txt", "zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz", "exe", "msi", "dmg", "apk", "iso", "epub", "xml",
            "json");

    /**
     * Keşfedilen aday nesne.
     *
     * @param category  ilk tahmin (etiket / uzantı); ölçümde içerik türüne göre incelebilir
     * @param pageLoad  sayfa boyutuna / nota katılır mı (belge ve bağlantı için false)
     * @param lazy      geç yüklenir (loading=lazy, otomatik oynatılmayan medya) — yükleme süresi tahminine girmez
     * @param fromCss   bir stil metninden ({@code url()}, {@code @import}) bulundu
     */
    public record Candidate(String url, String category, boolean pageLoad, boolean lazy, boolean fromCss) {}

    /** Bir HTML gövdesinin keşif sonucu. */
    public record Found(List<Candidate> pageLoad, List<Candidate> documents, List<Candidate> links,
                        List<String> inlineCss, int excluded) {}

    private PageResourceDiscovery() {}

    /**
     * HTML gövdesindeki aday nesneler (sıra korunur, aynı URL bir kez). Hariç-tutma yüklemi (izlemenin desenleri)
     * uygulanır; hariç tutulanların sayısı döner. Ayrıştırma hatası boş sonuç verir — fırlatmaz.
     */
    public static Found discover(byte[] html, String pageUrl, Predicate<String> exclude) {
        Document doc;
        try {
            doc = Jsoup.parse(new java.io.ByteArrayInputStream(html == null ? new byte[0] : html), null, pageUrl);
        } catch (Exception e) {
            return new Found(List.of(), List.of(), List.of(), List.of(), 0);
        }
        String self = key(pageUrl);
        Map<String, Candidate> page = new LinkedHashMap<>();
        Map<String, Candidate> docs = new LinkedHashMap<>();
        Map<String, Candidate> links = new LinkedHashMap<>();
        int[] excluded = {0};

        // Sıra: önce oluşturmayı engelleyenler (stil, betik), sonra görseller, medya, çerçeveler — tarayıcının
        // isteme sırasına yakın; yükleme tahmini bu sırayla bağlantılara dağıtır.
        for (Element el : doc.select("link[href]")) {
            Set<String> rel = tokens(el.attr("rel"));
            String as = el.attr("as").trim().toLowerCase(Locale.ROOT);
            String cat = null;
            if (rel.contains("stylesheet")) cat = CSS;
            else if (rel.contains("modulepreload")) cat = JS;
            else if (rel.contains("preload")) cat = switch (as) {
                case "style" -> CSS;
                case "script" -> JS;
                case "font" -> FONT;
                case "image" -> IMAGE;
                case "video", "audio", "track" -> MEDIA;
                default -> OTHER;
            };
            else if (rel.stream().anyMatch(r -> r.contains("icon"))) cat = FAVICON;
            else if (rel.contains("manifest")) cat = OTHER;
            if (cat == null) continue;   // prefetch / preconnect / dns-prefetch / canonical / alternate: indirme değil
            add(page, abs(el, "href"), cat, true, false, false, self, exclude, excluded);
        }
        for (Element el : doc.select("script[src]")) add(page, abs(el, "src"), JS, true, false, false, self, exclude, excluded);
        for (Element el : doc.select("img")) {
            String u = abs(el, "src");
            if (u.isBlank()) u = firstSrcset(el);
            boolean lazy = "lazy".equalsIgnoreCase(el.attr("loading").trim());
            add(page, u, IMAGE, true, lazy, false, self, exclude, excluded);
        }
        for (Element el : doc.select("input[type=image][src]")) add(page, abs(el, "src"), IMAGE, true, false, false, self, exclude, excluded);
        for (Element el : doc.select("video[poster]")) add(page, abs(el, "poster"), IMAGE, true, false, false, self, exclude, excluded);
        for (Element el : doc.select("video, audio")) {
            boolean lazy = !el.hasAttr("autoplay");
            add(page, abs(el, "src"), MEDIA, true, lazy, false, self, exclude, excluded);
            for (Element s : el.select("source[src]")) add(page, abs(s, "src"), MEDIA, true, lazy, false, self, exclude, excluded);
            for (Element t : el.select("track[src]")) add(page, abs(t, "src"), OTHER, true, lazy, false, self, exclude, excluded);
        }
        for (Element el : doc.select("iframe[src], frame[src]")) {
            boolean lazy = "lazy".equalsIgnoreCase(el.attr("loading").trim());
            add(page, abs(el, "src"), IFRAME, true, lazy, false, self, exclude, excluded);
        }
        for (Element el : doc.select("embed[src]")) add(page, abs(el, "src"), OTHER, true, false, false, self, exclude, excluded);
        for (Element el : doc.select("object[data]")) add(page, abs(el, "data"), OTHER, true, false, false, self, exclude, excluded);

        // Satır içi stiller (<style> blokları ve style="…" öznitelikleri) — url() başvuruları ayrıca çağırana döner
        List<String> inline = new ArrayList<>();
        for (Element el : doc.select("style")) inline.add(el.data());
        for (Element el : doc.select("[style]")) {
            String st = el.attr("style");
            if (st.toLowerCase(Locale.ROOT).contains("url(")) inline.add(st);
        }

        for (Element el : doc.select("a[href], area[href]")) {
            String raw = el.attr("href").trim();
            String low = raw.toLowerCase(Locale.ROOT);
            if (raw.isEmpty() || raw.startsWith("#") || low.startsWith("javascript:") || low.startsWith("mailto:")
                    || low.startsWith("tel:") || low.startsWith("data:") || low.startsWith("sms:")) continue;
            String u = abs(el, "href");
            if (u.isBlank()) continue;
            String k = key(u);
            if (page.containsKey(k)) continue;   // sayfanın zaten yüklediği bir nesne — sayfa nesnesi olarak kalır
            boolean isDoc = DOCUMENT_EXT.contains(ext(u)) || IMAGE_EXT.contains(ext(u)) || MEDIA_EXT.contains(ext(u))
                    || FONT_EXT.contains(ext(u));
            add(isDoc ? docs : links, u, isDoc ? DOCUMENT : LINK, false, false, false, self, exclude, excluded);
        }
        // Bir URL hem belge hem bağlantı listesinde olamaz (anahtar aynı) — yukarıdaki ayrım uzantıya göre tekildir.
        return new Found(List.copyOf(page.values()), List.copyOf(docs.values()), List.copyOf(links.values()),
                inline, excluded[0]);
    }

    /**
     * CSS metnindeki {@code url(...)} ve {@code @import} başvuruları — DOĞRUSAL tarama (düzenli ifade yok), en çok
     * {@value #MAX_CSS_REFS}. {@code data:} / {@code #} başvuruları atlanır; göreli adresler {@code baseUrl}'e göre çözülür.
     * Kategori uzantıdan: yazı tipi → FONT, görsel → IMAGE, {@code @import} ya da .css → CSS, diğerleri OTHER.
     */
    public static List<Candidate> cssRefs(String css, String baseUrl) {
        List<Candidate> out = new ArrayList<>();
        if (css == null || css.isEmpty()) return out;
        String s = css.length() > MAX_CSS_CHARS ? css.substring(0, MAX_CSS_CHARS) : css;
        String low = s.toLowerCase(Locale.ROOT);
        Map<String, Candidate> seen = new LinkedHashMap<>();
        int i = 0;
        int n = s.length();
        // Sonraki "url(" / "@import" konumları önbellekte: her turda baştan aramak kötü girdide karesel olurdu
        // ("@import@import…" ya da "url(url(…"). Bir konum yalnız tüketildiğinde (i onu geçince) yeniden aranır.
        int u = low.indexOf("url(");
        int imp = low.indexOf("@import");
        while (i < n && seen.size() < MAX_CSS_REFS) {
            if (u >= 0 && u < i) u = low.indexOf("url(", i);
            if (imp >= 0 && imp < i) imp = low.indexOf("@import", i);
            if (u < 0 && imp < 0) break;
            boolean isImport = imp >= 0 && (u < 0 || imp < u);
            int at = isImport ? imp : u;
            if (isImport) {
                // @import "x.css"; | @import url(x.css); | @import 'x.css' screen;
                int j = skipWs(s, at + 7);
                if (j < n && low.startsWith("url(", j)) {
                    Ref r = readUrlFn(s, j + 4);
                    put(seen, r.value(), baseUrl, CSS);
                    i = Math.max(r.end(), j + 4);
                } else if (j < n && (s.charAt(j) == '"' || s.charAt(j) == '\'')) {
                    Ref r = readQuoted(s, j);
                    put(seen, r.value(), baseUrl, CSS);
                    i = Math.max(r.end(), j + 1);
                } else {
                    i = at + 7;
                }
                continue;
            }
            Ref r = readUrlFn(s, u + 4);
            String v = r.value();
            if (v != null) {
                String e = ext(v);
                String cat = FONT_EXT.contains(e) ? FONT : IMAGE_EXT.contains(e) ? IMAGE : "css".equals(e) ? CSS : OTHER;
                put(seen, v, baseUrl, cat);
            }
            i = Math.max(r.end(), u + 4);
        }
        out.addAll(seen.values());
        return out;
    }

    /** {@code url(} sonrasından değeri okur: tırnaklı ya da çıplak, kapanış parantezine kadar. */
    private static Ref readUrlFn(String s, int from) {
        int n = s.length();
        int j = skipWs(s, from);
        if (j >= n) return new Ref(null, n);
        char c = s.charAt(j);
        if (c == '"' || c == '\'') {
            Ref q = readQuoted(s, j);
            int close = s.indexOf(')', q.end());
            // Kapanış yoksa metnin geri kalanı bozuktur: sonuna atla (her turda yeniden aramak karesel olurdu)
            return new Ref(q.value(), close < 0 ? n : close + 1);
        }
        int close = s.indexOf(')', j);
        if (close < 0) return new Ref(null, n);
        String v = s.substring(j, close).trim();
        return new Ref(v.isEmpty() ? null : v, close + 1);
    }

    private static Ref readQuoted(String s, int quoteAt) {
        char q = s.charAt(quoteAt);
        int end = s.indexOf(q, quoteAt + 1);
        if (end < 0) return new Ref(null, s.length());
        String v = s.substring(quoteAt + 1, end).trim();
        return new Ref(v.isEmpty() ? null : v, end + 1);
    }

    private record Ref(String value, int end) {}

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static void put(Map<String, Candidate> seen, String raw, String base, String cat) {
        if (raw == null) return;
        String low = raw.toLowerCase(Locale.ROOT);
        if (low.startsWith("data:") || low.startsWith("#") || low.startsWith("about:") || low.startsWith("javascript:")) return;
        String u = PageFetchCore.normalizeUrl(PageFetchCore.resolve(base, PageFetchCore.normalizeUrl(raw)));
        if (!PageFetchCore.isHttp(u)) return;
        seen.putIfAbsent(key(u), new Candidate(u, cat, true, false, true));
    }

    private static void add(Map<String, Candidate> out, String url, String cat, boolean pageLoad, boolean lazy,
                            boolean fromCss, String self, Predicate<String> exclude, int[] excluded) {
        if (url == null || url.isBlank()) return;
        String u = PageFetchCore.normalizeUrl(url);
        if (!PageFetchCore.isHttp(u)) return;
        String k = key(u);
        if (k.equalsIgnoreCase(self)) return;   // sayfanın kendisi (HTML satırı ayrıca eklenir)
        if (out.containsKey(k)) return;
        if (exclude != null && safeTest(exclude, u)) { excluded[0]++; return; }
        out.put(k, new Candidate(u, cat, pageLoad, lazy, fromCss));
    }

    private static boolean safeTest(Predicate<String> p, String u) {
        try { return p.test(u); } catch (RuntimeException e) { return false; }
    }

    private static String abs(Element el, String attr) {
        String a = el.absUrl(attr);
        return a == null ? "" : a.trim();
    }

    private static String firstSrcset(Element el) {
        String set = el.attr("srcset");
        if (set.isBlank()) return "";
        String first = set.split(",", 2)[0].trim();
        int sp = first.indexOf(' ');
        String u = sp > 0 ? first.substring(0, sp) : first;
        if (u.isBlank()) return "";
        return PageFetchCore.resolve(el.baseUri(), u);
    }

    private static Set<String> tokens(String rel) {
        Set<String> out = new java.util.HashSet<>();
        for (String t : rel.trim().toLowerCase(Locale.ROOT).split("\\s+")) if (!t.isBlank()) out.add(t);
        return out;
    }

    /** Tekilleştirme anahtarı: parçasız URL. */
    public static String key(String url) {
        return url == null ? "" : PageFetchCore.stripFragment(url);
    }

    /** URL yolunun uzantısı (küçük harf, yoksa boş). */
    public static String ext(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null) return "";
            int slash = path.lastIndexOf('/');
            String last = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = last.lastIndexOf('.');
            return dot >= 0 && dot < last.length() - 1 ? last.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        } catch (Exception e) {
            String p = url.split("[?#]", 2)[0];
            int dot = p.lastIndexOf('.');
            int slash = p.lastIndexOf('/');
            return dot > slash && dot < p.length() - 1 ? p.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        }
    }
}

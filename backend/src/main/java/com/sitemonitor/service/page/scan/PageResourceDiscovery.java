package com.sitemonitor.service.page.scan;

import com.sitemonitor.service.page.PageFetchCore;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
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
 * <p><b>Tarayıcı gibi (algoritma 2, 2026-11 — inceleme H2/H3/H4/H5/H6/H8/H12):</b>
 * <ul>
 *   <li>{@code <noscript>} ve {@code <template>} içi YOK (JS açık tarayıcı onları istemez; izleme pikselleri tetiklenmez).</li>
 *   <li>görsel başına TEK aday ({@code src}, yoksa {@code srcset}'in ilki); {@code <picture>}'da ilk desteklenen türdeki
 *       {@code <source srcset>} ({@code max-width} medya sorgulu olanlar masaüstü bakışında sona kalır), yoksa {@code img}.</li>
 *   <li>{@code <video>/<audio>} yalnız {@code autoplay} ya da {@code preload="auto"} ise indirilir, yalnız İLK uygun
 *       kaynak; diğerleri "indirilmedi" ({@link Candidate#notLoaded()}) olarak listelenir — boyut / istek / nota girmez.</li>
 *   <li>{@code <script nomodule>} sayılmaz (modern tarayıcı modül sürümünü alır, {@code nomodule}'ü hiç istemez);
 *       {@code apple-touch-icon} / {@code mask-icon} masaüstü tarayıcıda indirilmez.</li>
 *   <li>CSS yorumları atlanır; {@code @font-face}'te yalnız SON {@code src} bildiriminin İLK desteklenen biçimi
 *       (woff2 / woff / ttf / otf; eot / svg yalnız başka yoksa) — kategori {@code FONT}.</li>
 *   <li>satır içi stiller belgenin {@code <base href>}'iyle çözülür ({@link Found#baseUrl()}).</li>
 * </ul>
 * {@code loading="lazy"} görsel / iframe "geç yüklenir" olarak işaretlenir (sayfa boyutuna girer, yükleme tahminine girmez).
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
    /**
     * Taranacak azami CSS metni (karakter) — tarama stil gövdesini de bu kadar AÇAR (bellek tepesi, inceleme H15); gövde
     * zaten {@link PageFetchCore#MAX_BODY_BYTES} ile sınırlı.
     */
    public static final int MAX_CSS_CHARS = 2_000_000;
    /** Daha uzun adresler atlanır (gerçek nesne adresi değil; bellek ve satır boyutu korunur). */
    static final int MAX_URL_CHARS = 4096;

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
    /** Tarayıcının çalıştırdığı betik türleri (boş = klasik); {@code text/template} gibi türler indirilmez. */
    private static final Set<String> SCRIPT_TYPES = Set.of("", "text/javascript", "application/javascript",
            "application/ecmascript", "text/ecmascript", "application/x-javascript", "text/jscript", "module");
    /** {@code <picture><source type>} için desteklenen görsel türleri (boş = tür verilmemiş → desteklenir). */
    private static final Set<String> PICTURE_TYPES = Set.of("", "image/avif", "image/webp", "image/jpeg", "image/jpg",
            "image/png", "image/apng", "image/gif", "image/svg+xml", "image/bmp", "image/x-icon", "image/vnd.microsoft.icon");
    /** Masaüstü tarayıcının indirmediği simge türleri. */
    private static final Set<String> MOBILE_ICON_RELS = Set.of("apple-touch-icon", "apple-touch-icon-precomposed", "mask-icon");

    /**
     * Keşfedilen aday nesne.
     *
     * @param category  ilk tahmin (etiket / uzantı); ölçümde içerik türüne göre incelebilir
     * @param pageLoad  sayfa boyutuna / nota katılır mı (belge ve bağlantı için false)
     * @param lazy      geç yüklenir (loading=lazy, otomatik oynatılmayan medya) — yükleme süresi tahminine girmez
     * @param fromCss   bir stil metninden ({@code url()}, {@code @import}) bulundu
     * @param notLoaded tarayıcı İNDİRMEZ (ör. {@code preload="none"} medya) — listelenir, ölçülmez
     */
    public record Candidate(String url, String category, boolean pageLoad, boolean lazy, boolean fromCss, boolean notLoaded) {
        /** Geriye uyum: indirilen aday. */
        public Candidate(String url, String category, boolean pageLoad, boolean lazy, boolean fromCss) {
            this(url, category, pageLoad, lazy, fromCss, false);
        }
    }

    /**
     * Bir HTML gövdesinin keşif sonucu.
     *
     * @param baseUrl belgenin taban adresi ({@code <base href>} varsa o) — satır içi stilleri bununla çözün
     */
    public record Found(List<Candidate> pageLoad, List<Candidate> documents, List<Candidate> links,
                        List<String> inlineCss, int excluded, String baseUrl) {
        /** Geriye uyum: taban adres yok (çağıran sayfanın adresini kullanır). */
        public Found(List<Candidate> pageLoad, List<Candidate> documents, List<Candidate> links,
                     List<String> inlineCss, int excluded) {
            this(pageLoad, documents, links, inlineCss, excluded, null);
        }
    }

    private PageResourceDiscovery() {}

    /**
     * HTML gövdesindeki aday nesneler (sıra korunur, aynı URL bir kez). Hariç-tutma yüklemi (izlemenin desenleri)
     * uygulanır; hariç tutulanların sayısı döner. Ayrıştırma hatası boş sonuç verir — fırlatmaz.
     */
    public static Found discover(byte[] html, String pageUrl, Predicate<String> exclude) {
        Document doc;
        try {
            doc = Jsoup.parse(new java.io.ByteArrayInputStream(html == null ? new byte[0] : html), null, pageUrl);
            // JS açık tarayıcı bunları hiç istemez (GTM / piksel noscript'leri, şablon içerikleri)
            doc.select("noscript, template").remove();
        } catch (Exception e) {
            return new Found(List.of(), List.of(), List.of(), List.of(), 0, pageUrl);
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
            else if (rel.stream().anyMatch(MOBILE_ICON_RELS::contains)) cat = null;   // masaüstünde indirilmez
            else if (rel.stream().anyMatch(r -> r.contains("icon"))) cat = FAVICON;
            else if (rel.contains("manifest")) cat = OTHER;
            if (cat == null) continue;   // prefetch / preconnect / dns-prefetch / canonical / alternate: indirme değil
            add(page, abs(el, "href"), cat, true, false, false, false, self, exclude, excluded);
        }
        for (Element el : doc.select("script[src]")) {
            if (el.hasAttr("nomodule")) continue;   // modern tarayıcı indirmez (modül sürümü yüklenir)
            String type = el.attr("type").trim().toLowerCase(Locale.ROOT);
            int semi = type.indexOf(';');
            if (semi >= 0) type = type.substring(0, semi).trim();
            if (!SCRIPT_TYPES.contains(type)) continue;   // text/template vb. çalıştırılmaz, indirilmez
            add(page, abs(el, "src"), JS, true, false, false, false, self, exclude, excluded);
        }
        // <picture>: ilk desteklenen kaynak; img yedeği yalnız uygun kaynak yoksa
        Set<Element> pictureImgs = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Element pic : doc.select("picture")) {
            Element img = null;
            List<Element> sources = new ArrayList<>();
            for (Element ch : pic.children()) {
                if ("source".equals(ch.normalName()) && !ch.attr("srcset").isBlank()) sources.add(ch);
                else if (img == null && "img".equals(ch.normalName())) img = ch;
            }
            if (img != null) pictureImgs.add(img);
            boolean lazy = img != null && "lazy".equalsIgnoreCase(img.attr("loading").trim());
            Element chosen = pickPictureSource(sources);
            String u = chosen != null ? firstSrcset(chosen) : "";
            if (u.isBlank() && img != null) {
                u = abs(img, "src");
                if (u.isBlank()) u = firstSrcset(img);
            }
            add(page, u, IMAGE, true, lazy, false, false, self, exclude, excluded);
        }
        for (Element el : doc.select("img")) {
            if (pictureImgs.contains(el)) continue;
            String u = abs(el, "src");
            if (u.isBlank()) u = firstSrcset(el);
            boolean lazy = "lazy".equalsIgnoreCase(el.attr("loading").trim());
            add(page, u, IMAGE, true, lazy, false, false, self, exclude, excluded);
        }
        for (Element el : doc.select("input[type=image][src]")) {
            add(page, abs(el, "src"), IMAGE, true, false, false, false, self, exclude, excluded);
        }
        // Afiş görseli preload'tan bağımsız istenir
        for (Element el : doc.select("video[poster]")) add(page, abs(el, "poster"), IMAGE, true, false, false, false, self, exclude, excluded);
        for (Element el : doc.select("video, audio")) {
            boolean autoplay = el.hasAttr("autoplay");
            String preload = el.hasAttr("preload") ? el.attr("preload").trim().toLowerCase(Locale.ROOT) : null;
            // HTML: boş değerli preload = "auto"; değer yoksa / none / metadata → gövde indirilmez
            boolean download = autoplay || (preload != null && (preload.isEmpty() || "auto".equals(preload)));
            String src = abs(el, "src");
            if (src.isBlank()) {
                for (Element s : el.select("source[src]")) {
                    String type = s.attr("type").trim().toLowerCase(Locale.ROOT);
                    if (!type.isEmpty() && !type.startsWith("video/") && !type.startsWith("audio/")) continue;
                    src = abs(s, "src");
                    if (!src.isBlank()) break;
                }
            }
            if (src.isBlank()) continue;
            if (download) {
                // otomatik oynatılan medya yükleme tahmine girer; preload=auto olan oynatılmayan medya geç yüklenir
                add(page, src, MEDIA, true, !autoplay, false, false, self, exclude, excluded);
                for (Element t : el.select("track[src][default]")) {
                    add(page, abs(t, "src"), OTHER, true, true, false, false, self, exclude, excluded);
                }
            } else {
                add(page, src, MEDIA, false, true, false, true, self, exclude, excluded);
            }
        }
        for (Element el : doc.select("iframe[src], frame[src]")) {
            boolean lazy = "lazy".equalsIgnoreCase(el.attr("loading").trim());
            add(page, abs(el, "src"), IFRAME, true, lazy, false, false, self, exclude, excluded);
        }
        for (Element el : doc.select("embed[src]")) add(page, abs(el, "src"), OTHER, true, false, false, false, self, exclude, excluded);
        for (Element el : doc.select("object[data]")) add(page, abs(el, "data"), OTHER, true, false, false, false, self, exclude, excluded);

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
            add(isDoc ? docs : links, u, isDoc ? DOCUMENT : LINK, false, false, false, false, self, exclude, excluded);
        }
        String base = doc.baseUri();
        // Bir URL hem belge hem bağlantı listesinde olamaz (anahtar aynı) — yukarıdaki ayrım uzantıya göre tekildir.
        return new Found(List.copyOf(page.values()), List.copyOf(docs.values()), List.copyOf(links.values()),
                inline, excluded[0], base == null || base.isBlank() ? pageUrl : base);
    }

    /** {@code <picture>}'ın kaynakları içinde ilk desteklenen tür; {@code max-width} medyalı olanlar (mobil) sona kalır. */
    private static Element pickPictureSource(List<Element> sources) {
        Element fallback = null;
        for (Element s : sources) {
            String type = s.attr("type").trim().toLowerCase(Locale.ROOT);
            int semi = type.indexOf(';');
            if (semi >= 0) type = type.substring(0, semi).trim();
            if (!PICTURE_TYPES.contains(type)) continue;
            if (s.attr("media").toLowerCase(Locale.ROOT).contains("max-width")) {
                if (fallback == null) fallback = s;
                continue;
            }
            return s;
        }
        return fallback;
    }

    /**
     * CSS metnindeki {@code url(...)} ve {@code @import} başvuruları — DOĞRUSAL tarama (düzenli ifade yok), en çok
     * {@value #MAX_CSS_REFS}. Yorumlar ({@code /* … *\/}) atlanır; {@code @font-face} bloğunda yalnız tarayıcının
     * indireceği TEK yazı tipi ({@link #chooseFont}); {@code data:} / {@code #} başvuruları atlanır; göreli adresler
     * {@code baseUrl}'e göre çözülür. Kategori uzantıdan: yazı tipi → FONT, görsel → IMAGE, {@code @import} ya da .css
     * → CSS, diğerleri OTHER.
     */
    public static List<Candidate> cssRefs(String css, String baseUrl) {
        List<Candidate> out = new ArrayList<>();
        if (css == null || css.isEmpty()) return out;
        String s = stripComments(css.length() > MAX_CSS_CHARS ? css.substring(0, MAX_CSS_CHARS) : css);
        String low = s.toLowerCase(Locale.ROOT);
        Map<String, Candidate> seen = new LinkedHashMap<>();
        int i = 0;
        int n = s.length();
        // Sonraki "url(" / "@import" / "@font-face" konumları önbellekte: her turda baştan aramak kötü girdide karesel
        // olurdu ("@import@import…" ya da "url(url(…"). Bir konum yalnız tüketildiğinde (i onu geçince) yeniden aranır.
        int u = low.indexOf("url(");
        int imp = low.indexOf("@import");
        int ff = low.indexOf("@font-face");
        while (i < n && seen.size() < MAX_CSS_REFS) {
            if (u >= 0 && u < i) u = low.indexOf("url(", i);
            if (imp >= 0 && imp < i) imp = low.indexOf("@import", i);
            if (ff >= 0 && ff < i) ff = low.indexOf("@font-face", i);
            if (u < 0 && imp < 0 && ff < 0) break;
            int at = min3(u, imp, ff);
            if (at == ff) {
                // @font-face { … } — blok iç içe parantez almaz: ilk '}' sonu
                int open = s.indexOf('{', ff + 10);
                if (open < 0) break;
                int close = s.indexOf('}', open + 1);
                int end = close < 0 ? n : close;
                String chosen = chooseFont(s, low, open + 1, end);
                if (chosen != null) put(seen, chosen, baseUrl, FONT);
                i = close < 0 ? n : close + 1;
                continue;
            }
            if (at == imp) {
                // @import "x.css"; | @import url(x.css); | @import 'x.css' screen;
                int j = skipWs(s, imp + 7);
                if (j < n && low.startsWith("url(", j)) {
                    Ref r = readUrlFn(s, j + 4);
                    put(seen, r.value(), baseUrl, CSS);
                    i = Math.max(r.end(), j + 4);
                } else if (j < n && (s.charAt(j) == '"' || s.charAt(j) == '\'')) {
                    Ref r = readQuoted(s, j);
                    put(seen, r.value(), baseUrl, CSS);
                    i = Math.max(r.end(), j + 1);
                } else {
                    i = imp + 7;
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

    private static int min3(int a, int b, int c) {
        int m = Integer.MAX_VALUE;
        if (a >= 0) m = Math.min(m, a);
        if (b >= 0) m = Math.min(m, b);
        if (c >= 0) m = Math.min(m, c);
        return m;
    }

    /**
     * CSS yorumlarını atar — DOĞRUSAL: tırnaklı dizgi içindeki {@code /*} yorum değildir (dizgi satır sonunda biter, kaçışlı
     * karakter atlanır); kapanmayan yorum metnin sonuna kadar sürer (tarayıcı gibi).
     */
    static String stripComments(String s) {
        if (s.indexOf("/*") < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        char q = 0;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (q != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < n) { out.append(s.charAt(i + 1)); i += 2; continue; }
                if (c == q || c == '\n') q = 0;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') { q = c; out.append(c); i++; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) break;
                out.append(' ');
                i = end + 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * {@code @font-face} bloğunun [from, to) aralığından tarayıcının indireceği yazı tipi: SON {@code src} bildirimi geçerlidir
     * (CSS'te sonraki bildirim öncekini ezer); listede sırayla ilk DESTEKLENEN biçim (woff2 / woff / ttf / otf, ipucu
     * yoksa uzantı; bilinmeyen {@code format()} atlanır), yoksa ilk eot / svg. {@code local()} atlanır (makinede olup
     * olmadığı bilinmez). Doğrusal; bulunamazsa null.
     */
    static String chooseFont(String s, String low, int from, int to) {
        int decl = -1;
        int p = from;
        while (p < to) {
            int k = low.indexOf("src", p);
            if (k < 0 || k >= to) break;
            char before = k == 0 ? '{' : s.charAt(k - 1);
            int colon = skipWs(s, k + 3);
            if ((before == '{' || before == ';' || Character.isWhitespace(before)) && colon < to && s.charAt(colon) == ':') {
                decl = colon + 1;
            }
            p = k + 3;
        }
        if (decl < 0) return null;
        // Değer: üst düzey ';' ya da blok sonuna kadar; girdiler üst düzey virgülle ayrılır (parantez / tırnak içi hariç)
        List<String> entries = new ArrayList<>();
        int depth = 0;
        char q = 0;
        int start = decl;
        int i = decl;
        for (; i < to; i++) {
            char c = s.charAt(i);
            if (q != 0) {
                if (c == '\\') { i++; continue; }
                if (c == q) q = 0;
                continue;
            }
            if (c == '"' || c == '\'') q = c;
            else if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (depth == 0 && (c == ',' || c == ';')) {
                entries.add(s.substring(start, i));
                start = i + 1;
                if (c == ';') break;
            }
        }
        if (i >= to && start < to) entries.add(s.substring(start, to));
        String legacy = null;
        for (String raw : entries) {
            String e = raw.trim();
            String el = e.toLowerCase(Locale.ROOT);
            int up = el.indexOf("url(");
            if (up < 0) continue;   // local(…) ya da bozuk
            Ref r = readUrlFn(e, up + 4);
            if (r.value() == null) continue;
            String fmt = null;
            int fp = el.indexOf("format(", r.end() > up ? Math.min(r.end(), el.length()) : up);
            if (fp >= 0) {
                int fc = el.indexOf(')', fp + 7);
                fmt = el.substring(fp + 7, fc < 0 ? el.length() : fc).replace("\"", "").replace("'", "").trim();
            }
            int support = fontSupport(fmt, ext(r.value()));
            if (support == 2) return r.value();
            if (support == 1 && legacy == null) legacy = r.value();
        }
        return legacy;
    }

    /** 2 = modern tarayıcı destekler, 1 = eski biçim (eot / svg), 0 = desteklenmez (bilinmeyen format ipucu). */
    private static int fontSupport(String fmt, String ext) {
        if (fmt != null && !fmt.isEmpty()) {
            // embedded-opentype (eot) "opentype" içerir — önce eski biçimler
            if (fmt.contains("embedded-opentype") || fmt.equals("eot") || fmt.contains("svg")) return 1;
            if (fmt.contains("woff") || fmt.contains("truetype") || fmt.contains("opentype") || fmt.contains("collection")
                    || fmt.equals("ttf") || fmt.equals("otf")) return 2;
            return 0;
        }
        if ("eot".equals(ext) || "svg".equals(ext)) return 1;
        return 2;   // ipucu yok: tarayıcı indirip dener
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
        if (raw.length() > MAX_URL_CHARS) return;
        String u = PageFetchCore.normalizeUrl(PageFetchCore.resolve(base, PageFetchCore.normalizeUrl(raw)));
        if (!PageFetchCore.isHttp(u) || u.length() > MAX_URL_CHARS) return;
        String k = key(u);   // istenen adres parçasız (tarayıcı #… göndermez; font.eot?#iefix = font.eot)
        seen.putIfAbsent(k, new Candidate(k, cat, true, false, true));
    }

    private static void add(Map<String, Candidate> out, String url, String cat, boolean pageLoad, boolean lazy,
                            boolean fromCss, boolean notLoaded, String self, Predicate<String> exclude, int[] excluded) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_CHARS) return;
        String u = PageFetchCore.normalizeUrl(url);
        if (!PageFetchCore.isHttp(u) || u.length() > MAX_URL_CHARS) return;
        String k = key(u);
        if (k.equalsIgnoreCase(self)) return;   // sayfanın kendisi (HTML satırı ayrıca eklenir)
        if (out.containsKey(k)) return;
        if (exclude != null && safeTest(exclude, u)) { excluded[0]++; return; }
        out.put(k, new Candidate(k, cat, pageLoad, lazy, fromCss, notLoaded));   // istenen adres parçasız
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

    /** Tekilleştirme anahtarı: parçasız URL; sonda kalan boş {@code ?} atılır ({@code font.eot?#iefix} = {@code font.eot}). */
    public static String key(String url) {
        if (url == null) return "";
        String k = PageFetchCore.stripFragment(url);
        return k.endsWith("?") ? k.substring(0, k.length() - 1) : k;
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

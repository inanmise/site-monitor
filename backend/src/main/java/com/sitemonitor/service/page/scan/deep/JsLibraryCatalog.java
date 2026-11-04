package com.sitemonitor.service.page.scan.deep;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JS kütüphanesi tespiti ve bilinen açıklar (2026-11, kaynak analizi algoritma 3) — SAF, ağ yok, düzenli ifade yok.
 *
 * <p><b>Katalog yalnız EMİN olunan, CVE kimlikli girdileri taşır</b> (jQuery, jQuery UI, Bootstrap, AngularJS, lodash,
 * moment, Handlebars, Underscore). Tarih {@value #CATALOG_DATE}: bu tarihten sonra yayımlanan açıklar listede yoktur — arayüz
 * tarihi gösterir. Sürüm dosya adından / yoldan ({@code jquery-3.4.1.min.js}, {@code /ajax/libs/jquery/3.4.1/…},
 * {@code jquery@3.4.1}) ya da gövdenin ilk baytlarındaki başlık yorumundan ({@code /*! jQuery v3.4.1}) okunur; başlık
 * varsa o kazanır. Sürümü okunamayan kütüphane açık listesiyle eşleşmez (uydurma yok).
 */
public final class JsLibraryCatalog {

    public static final String CATALOG_DATE = "2026-11-04";

    public static final String JQUERY = "jquery", JQUERY_UI = "jquery-ui", BOOTSTRAP = "bootstrap", ANGULARJS = "angularjs",
            LODASH = "lodash", MOMENT = "moment", HANDLEBARS = "handlebars", UNDERSCORE = "underscore";

    public static final String XSS = "xss", PROTOTYPE_POLLUTION = "prototype_pollution", REDOS = "redos",
            CODE_INJECTION = "code_injection";

    /** Başlık yorumunun arandığı baş (karakter). */
    static final int BANNER_CHARS = 2048;

    /** Tespit edilen kütüphane; sürüm okunamadıysa {@code version} null. */
    public record Library(String name, String version) {}

    /** Açık: {@code [from, to)} sürüm aralıkları (from null = başından beri). */
    public record Vuln(String id, String severity, String kind, String summary, List<Range> ranges) {
        boolean affects(String version) {
            for (Range r : ranges) {
                if ((r.from() == null || compare(version, r.from()) >= 0) && (r.to() == null || compare(version, r.to()) < 0)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Etkilenen sürüm aralığı {@code [from, to)}; null = sınırsız. */
    public record Range(String from, String to) {}

    private static Range r(String from, String to) {
        return new Range(from, to);
    }

    /** Katalog — her girdi yayımlanmış CVE kaydının etkilenen sürüm aralığıyla. */
    static final Map<String, List<Vuln>> CATALOG = Map.of(
            JQUERY, List.of(
                    new Vuln("CVE-2020-11022", "medium", XSS,
                            "XSS when passing HTML from untrusted sources to DOM manipulation methods", List.of(r("1.2", "3.5.0"))),
                    new Vuln("CVE-2020-11023", "medium", XSS,
                            "XSS when passing HTML containing <option> elements to DOM manipulation methods", List.of(r("1.0.3", "3.5.0"))),
                    new Vuln("CVE-2019-11358", "medium", PROTOTYPE_POLLUTION,
                            "Object.prototype pollution via jQuery.extend(true, ...)", List.of(r(null, "3.4.0"))),
                    new Vuln("CVE-2015-9251", "medium", XSS,
                            "XSS via cross-domain Ajax requests without the dataType option", List.of(r(null, "3.0.0"))),
                    new Vuln("CVE-2012-6708", "medium", XSS,
                            "XSS: jQuery(strInput) does not reliably tell selectors from HTML", List.of(r(null, "1.9.0"))),
                    new Vuln("CVE-2011-4969", "medium", XSS,
                            "XSS when location.hash is used to select elements", List.of(r(null, "1.6.3")))),
            JQUERY_UI, List.of(
                    new Vuln("CVE-2022-31160", "medium", XSS,
                            "XSS in the checkboxradio widget label", List.of(r(null, "1.13.2"))),
                    new Vuln("CVE-2021-41182", "medium", XSS,
                            "XSS in the altField option of the Datepicker widget", List.of(r(null, "1.13.0"))),
                    new Vuln("CVE-2021-41183", "medium", XSS,
                            "XSS in the *Text options of the Datepicker widget", List.of(r(null, "1.13.0"))),
                    new Vuln("CVE-2021-41184", "medium", XSS,
                            "XSS in the 'of' option of the .position() util", List.of(r(null, "1.13.0"))),
                    new Vuln("CVE-2016-7103", "medium", XSS,
                            "XSS in the closeText parameter of the dialog widget", List.of(r(null, "1.12.0")))),
            BOOTSTRAP, List.of(
                    new Vuln("CVE-2019-8331", "medium", XSS,
                            "XSS in the tooltip or popover data-template attribute", List.of(r(null, "3.4.1"), r("4.0.0", "4.3.1"))),
                    new Vuln("CVE-2018-14040", "medium", XSS,
                            "XSS in the collapse data-parent attribute", List.of(r(null, "3.4.0"), r("4.0.0", "4.1.2"))),
                    new Vuln("CVE-2018-14041", "medium", XSS,
                            "XSS in the data-target property of scrollspy", List.of(r(null, "3.4.0"), r("4.0.0", "4.1.2"))),
                    new Vuln("CVE-2018-14042", "medium", XSS,
                            "XSS in the data-container property of tooltip", List.of(r(null, "3.4.0"), r("4.0.0", "4.1.2")))),
            ANGULARJS, List.of(
                    new Vuln("CVE-2022-25869", "medium", XSS,
                            "XSS due to insecure page caching in Internet Explorer (all versions; AngularJS is end of life)",
                            List.of(r(null, null))),
                    new Vuln("CVE-2020-7676", "medium", XSS,
                            "XSS via <option> elements inside <select>", List.of(r(null, "1.8.0"))),
                    new Vuln("CVE-2019-10768", "high", PROTOTYPE_POLLUTION,
                            "Object.prototype pollution via merge()", List.of(r(null, "1.7.9")))),
            LODASH, List.of(
                    new Vuln("CVE-2021-23337", "high", CODE_INJECTION,
                            "Command injection via the template function", List.of(r(null, "4.17.21"))),
                    new Vuln("CVE-2020-28500", "medium", REDOS,
                            "ReDoS in the toNumber, trim and trimEnd functions", List.of(r(null, "4.17.21"))),
                    new Vuln("CVE-2019-10744", "critical", PROTOTYPE_POLLUTION,
                            "Prototype pollution via defaultsDeep", List.of(r(null, "4.17.12"))),
                    new Vuln("CVE-2018-16487", "critical", PROTOTYPE_POLLUTION,
                            "Prototype pollution via merge, mergeWith and defaultsDeep", List.of(r(null, "4.17.11")))),
            MOMENT, List.of(
                    new Vuln("CVE-2022-31129", "high", REDOS,
                            "ReDoS in RFC 2822 date parsing", List.of(r("2.18.0", "2.29.4"))),
                    new Vuln("CVE-2017-18214", "high", REDOS,
                            "ReDoS via a crafted date string", List.of(r(null, "2.19.3")))),
            HANDLEBARS, List.of(
                    new Vuln("CVE-2021-23369", "critical", CODE_INJECTION,
                            "Remote code execution when compiling templates from an untrusted source", List.of(r(null, "4.7.7"))),
                    new Vuln("CVE-2021-23383", "critical", PROTOTYPE_POLLUTION,
                            "Prototype pollution when compiling templates with certain options", List.of(r(null, "4.7.7"))),
                    new Vuln("CVE-2019-19919", "critical", PROTOTYPE_POLLUTION,
                            "Prototype pollution leading to remote code execution", List.of(r(null, "4.3.0")))),
            UNDERSCORE, List.of(
                    new Vuln("CVE-2021-23358", "high", CODE_INJECTION,
                            "Arbitrary code injection via the template function", List.of(r("1.3.2", "1.12.1")))));

    private JsLibraryCatalog() {}

    /** Bilinen açıklar (sürüm yoksa boş). */
    public static List<Vuln> vulnsOf(Library lib) {
        if (lib == null || lib.version() == null) return List.of();
        List<Vuln> out = new ArrayList<>();
        for (Vuln v : CATALOG.getOrDefault(lib.name(), List.of())) if (v.affects(lib.version())) out.add(v);
        return out;
    }

    /**
     * Tespit: önce başlık yorumu (gövdenin ilk {@value #BANNER_CHARS} karakteri), yoksa dosya adı + yol. Hiçbiri yoksa null.
     *
     * @param banner gövdenin başı (çözülmüş metin); null olabilir
     */
    public static Library detect(String url, String banner) {
        Library fromBanner = fromBanner(banner);
        Library fromUrl = fromUrl(url);
        if (fromBanner != null) {
            if (fromBanner.version() == null && fromUrl != null && fromUrl.name().equals(fromBanner.name())) return fromUrl;
            return fromBanner;
        }
        return fromUrl;
    }

    // ── Başlık yorumu ─────────────────────────────────────────────────────────────────────

    static Library fromBanner(String text) {
        if (text == null || text.isEmpty()) return null;
        String t = text.length() > BANNER_CHARS ? text.substring(0, BANNER_CHARS) : text;
        Library l;
        if ((l = after(t, "jQuery UI - v", JQUERY_UI)) != null) return l;
        if ((l = after(t, "jQuery UI v", JQUERY_UI)) != null) return l;
        if ((l = after(t, "jQuery JavaScript Library v", JQUERY)) != null) return l;
        if ((l = after(t, "jQuery v", JQUERY)) != null) return l;
        if ((l = after(t, "Bootstrap v", BOOTSTRAP)) != null) return l;
        if ((l = after(t, "AngularJS v", ANGULARJS)) != null) return l;
        if ((l = after(t, "handlebars v", HANDLEBARS)) != null) return l;
        if (t.contains("Lodash <https://lodash.com/>") || t.contains("Lodash lodash.com/license")) {
            String v = versionAfterKey(text, "VERSION");
            return new Library(LODASH, v);
        }
        if (t.contains("moment.js") && t.contains("version")) {
            int i = t.indexOf("version");
            int c = t.indexOf(':', i);
            if (c > i && c - i < 12) return new Library(MOMENT, version(t, skipSpaces(t, c + 1)));
        }
        if ((l = after(t, "Underscore.js ", UNDERSCORE)) != null) return l;
        return null;
    }

    /** {@code marker} sonrası sürüm; sürüm okunamazsa null (yanlış eşleşmeyi önlemek için ad da dönmez). */
    private static Library after(String t, String marker, String name) {
        int i = t.indexOf(marker);
        if (i < 0) return null;
        String v = version(t, i + marker.length());
        return v == null ? null : new Library(name, v);
    }

    /** {@code VERSION = '4.17.21'} / {@code VERSION="4.17.21"} (lodash) — tüm örnekte; okunamazsa null. */
    private static String versionAfterKey(String text, String key) {
        int from = 0;
        for (int guard = 0; guard < 50; guard++) {
            int i = text.indexOf(key, from);
            if (i < 0) return null;
            int j = skipSpaces(text, i + key.length());
            if (j < text.length() && text.charAt(j) == '=') {
                j = skipSpaces(text, j + 1);
                if (j < text.length() && (text.charAt(j) == '\'' || text.charAt(j) == '"')) {
                    String v = version(text, j + 1);
                    if (v != null) return v;
                }
            }
            from = i + key.length();
        }
        return null;
    }

    // ── Adres ─────────────────────────────────────────────────────────────────────────────

    /** Dosya adı + yol (CDN klasörü, npm {@code ad@sürüm}). */
    static Library fromUrl(String url) {
        if (url == null) return null;
        String path = DeepUrls.path(url).toLowerCase(Locale.ROOT);
        String file = path.substring(path.lastIndexOf('/') + 1);
        if (!file.endsWith(".js")) return null;
        String stem = file.substring(0, file.length() - 3);
        String[] lib = libraryOfStem(stem);
        if (lib == null) return null;
        String version = lib[1];
        if (version == null) version = versionFromPath(path, lib[0]);
        return new Library(lib[0], version);
    }

    /** Dosya kökünden {ad, sürüm|null}; tanınmayan / eklenti dosyası ({@code jquery.validate}) null. */
    static String[] libraryOfStem(String stem) {
        String s = stem;
        for (String suffix : new String[]{".min", "-min", ".slim", "-slim", ".bundle", "-bundle"}) {
            if (s.endsWith(suffix)) s = s.substring(0, s.length() - suffix.length());
        }
        for (String suffix : new String[]{".min", "-min", ".slim", "-slim", ".bundle", "-bundle"}) {
            if (s.endsWith(suffix)) s = s.substring(0, s.length() - suffix.length());
        }
        String[][] prefixes = {{"jquery-ui", JQUERY_UI}, {"jquery.ui", JQUERY_UI}, {"jqueryui", JQUERY_UI},
                {"jquery", JQUERY}, {"bootstrap", BOOTSTRAP}, {"angular", ANGULARJS}, {"lodash", LODASH},
                {"moment-with-locales", MOMENT}, {"moment", MOMENT}, {"handlebars", HANDLEBARS}, {"underscore", UNDERSCORE}};
        for (String[] p : prefixes) {
            if (!s.startsWith(p[0])) continue;
            String rest = s.substring(p[0].length());
            if (rest.isEmpty()) return new String[]{p[1], null};
            char sep = rest.charAt(0);
            if (sep != '-' && sep != '.' && sep != '@') return null;   // jquery2x / bootstrapper
            String v = version(rest, 1);
            if (v != null && v.length() == rest.length() - 1) return new String[]{p[1], v};
            return null;                                              // jquery.validate / jquery-migrate / angular-route
        }
        return null;
    }

    /** Kütüphanenin CDN klasör adları ({@code /ajax/libs/jquery/3.4.1/}, {@code /npm/bootstrap@4.3.1/}). */
    private static final Map<String, List<String>> DIRS = Map.of(
            JQUERY, List.of("jquery"),
            JQUERY_UI, List.of("jqueryui", "jquery-ui", "ui"),
            BOOTSTRAP, List.of("bootstrap", "twitter-bootstrap"),
            ANGULARJS, List.of("angularjs", "angular.js", "angular"),
            LODASH, List.of("lodash", "lodash.js"),
            MOMENT, List.of("moment", "moment.js"),
            HANDLEBARS, List.of("handlebars", "handlebars.js"),
            UNDERSCORE, List.of("underscore", "underscore.js"));

    /**
     * Yolda sürüm — YALNIZ kütüphanenin kendi klasörüne bağlı: {@code <klasör>@1.2.3} parçası ya da {@code <klasör>/1.2.3}
     * (ya da {@code v1.2.3}). Sitenin kendi sürüm klasörü ({@code /static/v2.1/js/jquery.min.js}) kütüphane sürümü SAYILMAZ.
     */
    static String versionFromPath(String path, String name) {
        List<String> dirs = DIRS.getOrDefault(name, List.of());
        String[] segs = path.split("/", 64);
        String found = null;
        for (int i = 0; i < segs.length - 1; i++) {
            String seg = segs[i];
            int at = seg.lastIndexOf('@');
            if (at > 0) {
                if (!dirs.contains(seg.substring(0, at))) continue;
                String v = version(seg, at + 1);
                if (v != null && v.length() == seg.length() - at - 1) found = v;
                continue;
            }
            if (i == 0 || !dirs.contains(segs[i - 1])) continue;
            int start = seg.startsWith("v") ? 1 : 0;
            String v = version(seg, start);
            if (v != null && v.length() == seg.length() - start && v.indexOf('.') > 0) found = v;
        }
        return found;
    }

    // ── Sürüm ─────────────────────────────────────────────────────────────────────────────

    /** {@code from}'dan başlayan sayısal sürüm (1–4 parça, en az bir nokta ya da tek sayı); yoksa null. */
    static String version(String s, int from) {
        int i = from;
        int n = s.length();
        int parts = 0;
        StringBuilder out = new StringBuilder();
        while (i < n && parts < 4) {
            int start = i;
            while (i < n && i - start < 6 && Character.isDigit(s.charAt(i))) i++;
            if (i == start) break;
            if (out.length() > 0) out.append('.');
            out.append(s, start, i);
            parts++;
            if (i < n && s.charAt(i) == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) { i++; continue; }
            break;
        }
        return parts == 0 ? null : out.toString();
    }

    /** Sayısal karşılaştırma (eksik parça 0). */
    public static int compare(String a, String b) {
        String[] x = a.split("\\.", 5), y = b.split("\\.", 5);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return Long.compare(p, q);
        }
        return 0;
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }

    /** Önem sırası (büyük = ağır). */
    public static int severityRank(String s) {
        return switch (s == null ? "" : s) {
            case "critical" -> 4;
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }
}

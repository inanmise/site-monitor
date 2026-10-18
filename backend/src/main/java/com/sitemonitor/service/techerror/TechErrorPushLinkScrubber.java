package com.sitemonitor.service.techerror;

import java.util.Set;

/**
 * Push'a giden serbest metinden (teknik hata raporu red notu) bağlantıları, e-posta adreslerini ve sunucu adlarını ayıklar
 * (inceleme 2026-10-18: push uygulaması kurum ağının DIŞINDA — "push metninde bağlantı yok" kuralı; iç adres / sunucu adı
 * sızmasın). Kurallar, son ek ve uzantı listeleri TEK yerde: burada.
 *
 * <p><b>Doğrusal, düzenli ifade yok</b> ("No unbounded loop, regex…"): metin boşluk benzeri karakterlerle (NBSP dahil)
 * jetonlara ayrılır; URL olmayan jeton {@code , ;} ve tirelerle (— – ―) parçalara bölünür; her parça baştaki / sondaki
 * noktalama soyulup, Türkçe ek kesesi ({@code '} / {@code ’} sonrası: "srv01.corp.local'a") ayrılıp BİR kez sınanır.
 * En çok {@link #MAX_INPUT} girdi karakterine bakılır; sınıra taşan jeton hiç yazılmaz (yarım adres sızmaz).
 *
 * <p><b>Bağlantı sayılan:</b> {@code scheme://…}, {@code www.…}, {@code mailto:}, UNC {@code \\host}, {@code ad@alan},
 * IPv4 (bağlantı noktasıyla da), IPv6, {@code localhost} / {@code intranet} + yol ya da port, rakamlı tek ad + port
 * ({@code srv01:8080}), son eki {@link #HOST_SUFFIXES}'te olan noktalı ad, son eki bilinmeyen noktalı ad + yol / port
 * ({@code portal.kurum/abc}). <b>Bağlantı sayılmayan:</b> {@link #FILE_EXTENSIONS} uzantılı dosya adı ({@code rapor.v2.pdf}),
 * cümle sınırı ({@code ediyor.Test} — küçük harfle biten sözcük + büyük harfle başlayan sözcük), ilk etiketi harfsiz ad
 * ({@code 2.test}, {@code 1.250.000}), sürüm ({@code v1.2}), bilinmeyen son ek ({@code main.co}), saat ({@code 10:30}).
 * Büyük / küçük harf yalnız ASCII'de çevrilir ({@code İNTRANET.KURUM.LOCAL}: {@code toLowerCase(ROOT)} "İ"ye birleşik nokta
 * ekleyip etiketi bozuyordu).
 */
final class TechErrorPushLinkScrubber {

    private TechErrorPushLinkScrubber() { }

    /** En çok bu kadar GİRDİ karakterine bakılır (not en çok 1000 karakter; push'a ilk 120 girer). */
    static final int MAX_INPUT = 2000;

    /** Bağlantı sayılan son ekler (TEK kaynak): kurum içi + genel; iki etiketli son ekler ({@code com.tr}) dahil. */
    static final Set<String> HOST_SUFFIXES = Set.of(
            // kurum içi
            "local", "localdomain", "lan", "intra", "intranet", "internal", "corp", "priv", "private",
            // genel
            "com", "net", "org", "edu", "gov", "mil", "info", "biz", "io", "eu", "tr",
            "com.tr", "net.tr", "org.tr", "gov.tr", "edu.tr", "bel.tr", "k12.tr", "gen.tr", "web.tr", "av.tr", "co.uk", "org.uk");

    /** Tek etiketli iç adlar — yol ya da bağlantı noktasıyla bağlantı ({@code intranet/abc}, {@code localhost:8080}). */
    static final Set<String> INTERNAL_HOSTS = Set.of("localhost", "intranet", "intra");

    /** Dosya adı uzantıları — son etiketi bunlardan biri olan noktalı ad sunucu adı değildir ({@code config.app.json}). */
    static final Set<String> FILE_EXTENSIONS = Set.of("pdf", "json", "xlsx", "xls", "csv", "txt", "docx", "doc", "pptx", "ppt",
            "png", "jpg", "jpeg", "gif", "svg", "zip", "log", "xml", "yaml", "yml", "md", "sql", "htm", "html", "conf", "cfg",
            "ini", "properties", "java", "js", "ts", "py", "sh", "jar", "war");

    /** Harfsiz tek ad için de bağlantı sayılan yaygın web bağlantı noktaları ({@code portal:8443}; "Toplam:120" değil). */
    static final Set<Integer> COMMON_PORTS = Set.of(80, 443, 3000, 5000, 8000, 8008, 8080, 8081, 8443, 8888, 9000, 9090, 9443);

    private static final String LEAD_PUNCT = "([{<\"'«‘“";
    private static final String TRAIL_PUNCT = ")]}>\"'».,;:!?…’”";
    /** URL olmayan jetonun içindeki parça ayraçları (virgülle bitişik adlar, tire sonrası metin). */
    private static final String PART_SEPS = ",;—–―‒";

    static String scrub(String s, String placeholder) {
        if (s == null || s.isEmpty()) return "";
        int n = s.length();
        int end = Math.min(n, MAX_INPUT);
        StringBuilder out = new StringBuilder(end + 16);
        int i = 0;
        while (i < end) {
            char ch = s.charAt(i);
            if (space(ch)) {
                out.append(ch);
                i++;
                continue;
            }
            int j = i;
            while (j < end && !space(s.charAt(j))) j++;
            if (j == end && end < n && !space(s.charAt(end))) break;   // sınıra taşan jeton: hiç yazılmaz
            token(out, s, i, j, placeholder);
            i = j;
        }
        return out.toString();
    }

    private static boolean space(char ch) {
        return Character.isWhitespace(ch) || Character.isSpaceChar(ch);   // NBSP (U+00A0, U+202F, U+2007) dahil
    }

    /** Boşlukla ayrılmış jeton: URL ise bütünü, değilse ayraçlarla bölünmüş her parça ayrı sınanır. */
    private static void token(StringBuilder out, String s, int i, int j, String ph) {
        int a = i;
        int b = j;
        while (a < b && LEAD_PUNCT.indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && TRAIL_PUNCT.indexOf(s.charAt(b - 1)) >= 0) b--;
        if (a >= b) {
            out.append(s, i, j);
            return;
        }
        String core = s.substring(a, b);
        // Köşeli parantezli IPv6 ("[2001:db8::1]:8443" / "[fe80::1]"): parantezler de adresin parçası — birlikte değişir
        if (a > i && s.charAt(a - 1) == '[' && ipv6(asciiLower(core))) {
            int to = b;
            if (core.indexOf(']') < 0 && to < j && s.charAt(to) == ']') to++;
            out.append(s, i, a - 1).append(ph).append(s, to, j);
            return;
        }
        out.append(s, i, a);
        if (urlish(core)) {
            int k = apostrophe(core);
            out.append(ph);
            if (k >= 0) out.append(core, k, core.length());
        } else {
            int start = 0;
            for (int k = 0; k < core.length(); k++) {
                if (PART_SEPS.indexOf(core.charAt(k)) < 0) continue;
                part(out, core.substring(start, k), ph);
                out.append(core.charAt(k));
                start = k + 1;
            }
            part(out, core.substring(start), ph);
        }
        out.append(s, b, j);
    }

    private static void part(StringBuilder out, String p, String ph) {
        int x = 0;
        int y = p.length();
        while (x < y && LEAD_PUNCT.indexOf(p.charAt(x)) >= 0) x++;
        while (y > x && TRAIL_PUNCT.indexOf(p.charAt(y - 1)) >= 0) y--;
        if (x >= y) {
            out.append(p);
            return;
        }
        String core = p.substring(x, y);
        int k = apostrophe(core);
        String host = k >= 0 ? core.substring(0, k) : core;
        if (!host.isEmpty() && (urlish(host) || linkLike(host))) {
            out.append(p, 0, x).append(ph);
            if (k >= 0) out.append(core, k, core.length());
            out.append(p, y, p.length());
        } else {
            out.append(p);
        }
    }

    /** Türkçe ek kesesi: ilk {@code '} ya da {@code ’} (yoksa -1). */
    private static int apostrophe(String t) {
        for (int k = 0; k < t.length(); k++) {
            char ch = t.charAt(k);
            if (ch == '\'' || ch == '’') return k;
        }
        return -1;
    }

    private static boolean urlish(String t) {
        String low = asciiLower(t);
        return low.contains("://") || low.startsWith("www.") || low.startsWith("\\\\") || low.startsWith("mailto:");
    }

    /** Şemasız parça bağlantı / adres / sunucu adı mı — yalnız indeks taramaları (parça boyunda doğrusal). */
    static boolean linkLike(String t) {
        String low = asciiLower(t);
        int at = low.indexOf('@');
        if (at > 0 && at < low.length() - 1 && Character.isLetterOrDigit(low.charAt(at + 1))) return true;   // ad@alan (iç adres de)
        if (ipv6(low)) return true;
        int cut = low.length();
        for (int k = 0; k < low.length(); k++) {
            char ch = low.charAt(k);
            if (ch == '/' || ch == '?' || ch == '#') { cut = k; break; }
        }
        boolean path = cut < low.length();
        String hp = low.substring(0, cut);
        int port = -1;
        int colon = hp.lastIndexOf(':');
        if (colon > 0 && colon < hp.length() - 1 && hp.length() - colon - 1 <= 5 && allDigits(hp, colon + 1, hp.length())) {
            port = Integer.parseInt(hp.substring(colon + 1));
            hp = hp.substring(0, colon);
        }
        if (hp.isEmpty() || port > 65535) return false;
        if (hp.indexOf('.') < 0) {
            if (!label(hp, 0, hp.length()) || !hasLetter(hp)) return false;
            if (INTERNAL_HOSTS.contains(hp)) return path || port >= 0;
            return port >= 0 && (hasDigit(hp) || COMMON_PORTS.contains(port));   // srv01:8080 — "Toplam:120" değil
        }
        return dottedHost(t, hp, path || port >= 0);
    }

    /**
     * Noktalı ad: IPv4; ya da geçerli etiketler + harfli son ek — dosya uzantısı ve cümle sınırı hariç; son ek bilinen
     * ({@link #HOST_SUFFIXES}) ya da ardında yol / port var. {@code orig}: büyük / küçük harfi korunmuş metin (cümle sınırı
     * için; {@code hp} onun ASCII küçük harfli ÖNEKİ — indeksler aynı).
     */
    private static boolean dottedHost(String orig, String hp, boolean tail) {
        int len = hp.length();
        if (hp.charAt(0) == '.' || hp.charAt(len - 1) == '.') return false;
        int labels = 0;
        boolean allNumeric = true;
        boolean firstHasLetter = false;
        boolean boundary = false;
        int start = 0;
        int lastStart = 0;
        int prevStart = -1;
        for (int k = 0; k <= len; k++) {
            if (k < len && hp.charAt(k) != '.') continue;
            if (!label(hp, start, k)) return false;
            boolean digits = allDigits(hp, start, k);
            if (!digits || k - start > 3) allNumeric = false;
            if (labels == 0) firstHasLetter = hasLetter(hp.substring(start, k));
            // Cümle sınırı: önceki etiket küçük harfle biter, bu etiket büyük harfle başlar ("ediyor.Test", "Kapandı.Site")
            if (labels > 0 && Character.isLowerCase(orig.charAt(start - 2)) && Character.isUpperCase(orig.charAt(start))) boundary = true;
            prevStart = lastStart;
            lastStart = start;
            labels++;
            start = k + 1;
        }
        if (labels < 2) return false;
        if (allNumeric) return labels == 4;                                         // IPv4
        if (!firstHasLetter || boundary) return false;                              // 2.test · ediyor.Test
        String last = hp.substring(lastStart);
        for (int m = 0; m < last.length(); m++) if (!Character.isLetter(last.charAt(m))) return false;   // v1.2
        if (last.length() < 2 || FILE_EXTENSIONS.contains(last)) return false;   // rapor.v2.pdf
        if (HOST_SUFFIXES.contains(last)) return true;
        if (labels >= 2 && prevStart >= 0 && HOST_SUFFIXES.contains(hp.substring(prevStart))) return true;   // com.tr
        return tail;                                                                // portal.kurum/abc
    }

    /** IPv6 (köşeli parantezli / bağlantı noktalı / bölgeli de): onaltılık + iki nokta; "::" ya da 7 iki nokta (10:30:00 değil). */
    private static boolean ipv6(String low) {
        String v = low;
        if (v.startsWith("[")) v = v.substring(1);
        int rb = v.indexOf(']');
        if (rb >= 0) v = v.substring(0, rb);
        int colons = 0;
        boolean hex = false;
        for (int k = 0; k < v.length(); k++) {
            char ch = v.charAt(k);
            if (ch == ':') colons++;
            else if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')) hex = true;
            else if (ch == '%') break;                                              // bölge (fe80::1%eth0)
            else if (ch != '.') return false;                                       // gömülü IPv4 noktaları dışında harf yok
        }
        return hex && colons >= 2 && (v.contains("::") || colons >= 7);
    }

    /** Etiket: 1–63 karakter, harf / rakam / tire, tireyle başlayıp bitmez. */
    private static boolean label(String s, int from, int to) {
        int len = to - from;
        if (len < 1 || len > 63 || s.charAt(from) == '-' || s.charAt(to - 1) == '-') return false;
        for (int k = from; k < to; k++) {
            char ch = s.charAt(k);
            if (!Character.isLetterOrDigit(ch) && ch != '-') return false;
        }
        return true;
    }

    private static boolean hasLetter(String s) {
        for (int k = 0; k < s.length(); k++) if (Character.isLetter(s.charAt(k))) return true;
        return false;
    }

    private static boolean hasDigit(String s) {
        for (int k = 0; k < s.length(); k++) if (Character.isDigit(s.charAt(k))) return true;
        return false;
    }

    private static boolean allDigits(String s, int from, int to) {
        if (from >= to) return false;
        for (int k = from; k < to; k++) {
            char ch = s.charAt(k);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    /** Yalnız ASCII A–Z küçültülür (uzunluk ve indeksler korunur; "İ" olduğu gibi kalır). */
    static String asciiLower(String t) {
        char[] c = null;
        for (int k = 0; k < t.length(); k++) {
            char ch = t.charAt(k);
            if (ch >= 'A' && ch <= 'Z') {
                if (c == null) c = t.toCharArray();
                c[k] = (char) (ch + 32);
            }
        }
        return c == null ? t : new String(c);
    }
}

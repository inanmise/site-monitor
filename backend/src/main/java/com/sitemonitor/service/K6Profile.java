package com.sitemonitor.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bir k6 ikilisinin YOKLANMIŞ sürüm profili (2026-10-29, k6 0.49 → 2.3 geçişi).
 *
 * <p>Neden profil: etkin / yedek / aday ikili bir ayarla değişir; komut ve kayda yazılan sürüm "ayarda ne yazıyor" değil
 * "o ikili gerçekten hangi sürüm" sorusuna göre kurulur. 2026-10-30'dan (Yayın B + C) beri imajda tek ikili var
 * ({@code /usr/bin/k6} = 2.3) ve uygulama yalnız {@link #MIN_SUPPORTED_MAJOR} ve üstünü koşar: 2.0'dan eski k6 her
 * koşumda {@code localhost:6565}'te REST API açıyordu (eşzamanlı koşumlar portta çakışır) ve hata çıktısı (Babel)
 * farklıydı — bu yollar kaldırıldı. Daha eski bir ikili yoklanınca {@link #unsupported} döner: bulundu ama KOŞMAZ.
 *
 * @param bin         yoklanan ikili yolu (ayardaki değer, ör. {@code k6})
 * @param available   {@code k6 version} başarıyla döndü VE sürüm destekleniyor
 * @param version     görünen sürüm metni ({@code v2.3.0}); okunamadıysa çıktının kısa hâli ya da null
 * @param major       sürümün büyük numarası; okunamadıysa -1
 * @param probedAtMs  yoklama anı (epoch ms)
 * @param goVersion   ikilinin derlendiği Go sürümü ({@code go1.26.11}; 2026-11-16, k6 2.3 → 2.4 geçişi) — aynı
 *                    {@code k6 version} çıktısından okunur; okunamazsa null. GODEBUG kaçışlarının bu motorda geçerli
 *                    olup olmadığı buna bağlıdır ({@link K6EngineSettings#ignoredGodebugKeys}).
 */
public record K6Profile(String bin, boolean available, String version, int major, int minor, int patch,
                        long probedAtMs, String goVersion) {

    /** Go sürümsüz profil (Go sürümü bilinmiyor) — 2026-11-16 öncesi imza; kaynak uyumluluğu için. */
    public K6Profile(String bin, boolean available, String version, int major, int minor, int patch, long probedAtMs) {
        this(bin, available, version, major, minor, patch, probedAtMs, null);
    }

    /**
     * {@code k6 version} satırı: program adı (ilk sözcük — {@code k6}, {@code k6.exe} ya da yeniden adlandırılmış bir ikili)
     * ve HEMEN ardından {@code v}'li sürüm: {@code k6 v2.3.0 (commit/…, go1.26.8, linux/amd64)}. Satır başına bağlıdır
     * (inceleme 2026-10-30, L3): eski desen ({@code v?} + serbest arama) bir geliştirme derlemesinin
     * {@code k6 (devel) (go1.26.8, …)} satırında Go sürümünü (1.26.8) k6 sürümü sanıyor → "desteklenmiyor" → bütün senaryo
     * izlemesi kapanıyordu. Önce gelen günlük satırları ({@code time=… level=…}) eşleşmez.
     */
    private static final Pattern VERSION =
            Pattern.compile("(?m)^[ \\t]*\\S+[ \\t]+v(\\d+)\\.(\\d+)\\.(\\d+)");

    /**
     * Desteklenen en eski büyük sürüm (k6 v2.0.0): REST API sunucusu varsayılan olarak KAPALI (efemer {@code --address}
     * gerekmez), betik Babel olmadan modern JavaScript olarak derlenir. Eski bir ikili {@link #unsupported} sayılır.
     */
    public static final int MIN_SUPPORTED_MAJOR = 2;
    /** {@link #MIN_SUPPORTED_MAJOR}'un görünen hâli (ekran ve hata metinleri). */
    public static final String MIN_SUPPORTED_VERSION = "v2.0.0";

    /** k6 bulunamadı / {@code version} başarısız. */
    public static K6Profile unavailable(String bin, long now) {
        return new K6Profile(bin, false, null, -1, -1, -1, now, null);
    }

    /**
     * {@code k6 version} çıktısından profil. Sürüm OKUNAMAZSA (geliştirme derlemesi, beklenmeyen biçim) ikili yine
     * kullanılabilir sayılır ({@code available} true, numaralar -1, görünen sürüm çıktının ilk 40 karakteri) — Yayın A ile
     * aynı kural: çalışan bir ikiliyi tanınmayan bir metin yüzünden "desteklenmiyor" sayıp bütün senaryo izlemesini
     * durdurmak, eski bir sürümü kaçırmaktan daha pahalı. "Desteklenmiyor" yalnız OKUNMUŞ ve 2.0'dan eski bir sürümdür.
     */
    public static K6Profile fromVersionOutput(String bin, String output, long now) {
        if (output == null) return unavailable(bin, now);
        Matcher m = VERSION.matcher(output);
        if (!m.find()) {
            String s = output.strip();
            // Geliştirme derlemesi ({@code k6 (devel) (go1.26.8, …)}): k6 sürümü yok ama Go sürümü okunabilir.
            return new K6Profile(bin, true, s.length() > 40 ? s.substring(0, 40) : s, -1, -1, -1, now,
                    goVersionIn(output, 0, false));
        }
        K6Profile p = new K6Profile(bin, true, "v" + m.group(1) + "." + m.group(2) + "." + m.group(3),
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), now,
                goVersionIn(output, m.start(), true));
        return p.tooOld() ? p.asUnsupported() : p;
    }

    /** Go sürümü taramasının üst sınırı (karakter) — yoklama çıktısı zaten 4 KB ile sınırlı; yine de sabit tavan. */
    static final int GO_SCAN_MAX_CHARS = 4096;
    /** Okunan Go sürümü metninin üst sınırı ({@code go1.26.11rc1} gibi). */
    static final int GO_VERSION_MAX_CHARS = 24;

    /**
     * {@code k6 version} çıktısında {@code from} konumundaki satırdan başlayarak İLK Go sürümü belirtecini okur:
     * {@code go<büyük>.<küçük>[.<yama>][<ek>]} — ör. {@code (commit/dd9395906d, go1.26.11, linux/amd64)}. Belirteç önünde
     * harf / rakam olmamalı ({@code argo1.2} sayılmaz). DOĞRUSAL tarama, düzenli ifade yok; en çok {@link #GO_SCAN_MAX_CHARS}
     * karakter okunur. {@code lineOnly}: sürüm satırı bulunduysa yalnız O satır taranır (önceki / sonraki günlük
     * satırlarındaki bir metin Go sürümü sanılmaz); geliştirme derlemesinde (sürüm satırı yok) çıktının başından taranır.
     * Bulunamazsa null.
     */
    static String goVersionIn(String output, int from, boolean lineOnly) {
        if (output == null || from < 0 || from >= output.length()) return null;
        int end = Math.min(output.length(), from + GO_SCAN_MAX_CHARS);
        if (lineOnly) {
            int nl = output.indexOf('\n', from);
            if (nl >= 0 && nl < end) end = nl;   // sürüm satırı bulundu → yalnız o satır
        }
        for (int i = from; i + 3 < end; i++) {
            if (output.charAt(i) != 'g' || output.charAt(i + 1) != 'o') continue;
            if (i > from && Character.isLetterOrDigit(output.charAt(i - 1))) continue;
            String v = readGoToken(output, i + 2, end);
            if (v != null) return "go" + v;
        }
        return null;
    }

    /** {@code <rakam>+.<rakam>+[.<rakam>+][<harf/rakam>…]} — geçerliyse belirteç (go'suz), değilse null. Sınırlı. */
    private static String readGoToken(String s, int start, int end) {
        int i = start;
        int dots = 0;
        int digitsInPart = 0;
        StringBuilder sb = new StringBuilder();
        while (i < end && sb.length() < GO_VERSION_MAX_CHARS) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digitsInPart++;
            } else if (c == '.' && digitsInPart > 0 && dots < 2) {
                dots++;
                digitsInPart = 0;
            } else {
                break;
            }
            sb.append(c);
            i++;
        }
        if (dots < 1 || digitsInPart == 0) return null;   // en az go<a>.<b>
        // Ön sürüm eki (rc1, beta2): yalnız harf + rakam, belirteç sınırı içinde.
        while (i < end && sb.length() < GO_VERSION_MAX_CHARS && Character.isLetterOrDigit(s.charAt(i)) && s.charAt(i) < 0x80) {
            sb.append(s.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /**
     * Go sürüm metninden {@code {büyük, küçük}} ({@code go1.26.11} → {@code {1, 26}}); okunamazsa null. Yalnız
     * {@code go<a>.<b>} başı okunur, ek ({@code rc1}) yok sayılır.
     */
    public static int[] goMajorMinor(String goVersion) {
        if (goVersion == null || !goVersion.startsWith("go") || goVersion.length() > GO_VERSION_MAX_CHARS) return null;
        int i = 2;
        int major = 0;
        int minor = 0;
        int n = 0;
        while (i < goVersion.length() && Character.isDigit(goVersion.charAt(i)) && n < 4) {
            major = major * 10 + (goVersion.charAt(i++) - '0');
            n++;
        }
        if (n == 0 || i >= goVersion.length() || goVersion.charAt(i) != '.') return null;
        i++;
        n = 0;
        while (i < goVersion.length() && Character.isDigit(goVersion.charAt(i)) && n < 4) {
            minor = minor * 10 + (goVersion.charAt(i++) - '0');
            n++;
        }
        if (n == 0) return null;
        return new int[]{major, minor};
    }

    /** Sürüm numarası okunabildi mi. */
    public boolean versionKnown() {
        return major >= 0;
    }

    /**
     * Bu ikili bulundu ama sürümü desteklenmiyor mu ({@link #MIN_SUPPORTED_MAJOR}'dan eski). Böyle bir profil
     * {@code available=false} taşır; sürüm metni ekranda "desteklenmeyen sürüm" demek için korunur.
     */
    public boolean unsupported() {
        return !available && tooOld();
    }

    private boolean tooOld() {
        return versionKnown() && major < MIN_SUPPORTED_MAJOR;
    }

    private K6Profile asUnsupported() {
        return new K6Profile(bin, false, version, major, minor, patch, probedAtMs, goVersion);
    }
}

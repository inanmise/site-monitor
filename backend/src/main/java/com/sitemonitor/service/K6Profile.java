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
 */
public record K6Profile(String bin, boolean available, String version, int major, int minor, int patch,
                        long probedAtMs) {

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
        return new K6Profile(bin, false, null, -1, -1, -1, now);
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
            return new K6Profile(bin, true, s.length() > 40 ? s.substring(0, 40) : s, -1, -1, -1, now);
        }
        K6Profile p = new K6Profile(bin, true, "v" + m.group(1) + "." + m.group(2) + "." + m.group(3),
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), now);
        return p.tooOld() ? p.asUnsupported() : p;
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
        return new K6Profile(bin, false, version, major, minor, patch, probedAtMs);
    }
}

package com.sitemonitor.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bir k6 ikilisinin YOKLANMIŞ sürüm profili (2026-10-29, k6 0.49 → 2.3 geçişi, Yayın A).
 *
 * <p>Neden profil: k6'nın komut satırı ve davranışı sürüme göre değişiyor — 2.0'dan itibaren REST API sunucusu varsayılan
 * olarak KAPALI ve {@code --address} vermek onu AÇIYOR; 0.53'ten itibaren modern JavaScript (Babel yok). Geçiş süresince
 * imajda iki ikili var ({@code /usr/bin/k6} = 0.49, {@code /opt/k6-next/k6} = 2.3) ve hangisinin koşacağı bir ayarla
 * değişiyor; komut, KOŞACAK ikilinin profiline göre kurulur — "ayarda ne yazıyor" değil "o ikili gerçekten hangi sürüm".
 *
 * @param bin         yoklanan ikili yolu (ayardaki değer, ör. {@code k6} ya da {@code /opt/k6-next/k6})
 * @param available   {@code k6 version} başarıyla döndü mü
 * @param version     görünen sürüm metni ({@code v2.3.0}); okunamadıysa çıktının kısa hâli ya da null
 * @param major       sürümün büyük numarası; okunamadıysa -1
 * @param probedAtMs  yoklama anı (epoch ms)
 */
public record K6Profile(String bin, boolean available, String version, int major, int minor, int patch,
                        long probedAtMs) {

    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");

    /** Modern sözdizimini (?. ?? {...}) ayrıştıran ilk sürüm — {@link ScriptedTemplateRules} ile aynı eşik. */
    static final int[] MODERN_SYNTAX_SINCE = { 0, 53, 0 };
    /** REST API sunucusunun varsayılan olarak KAPALI olduğu ilk büyük sürüm (k6 v2.0.0). */
    static final int API_SERVER_OFF_SINCE_MAJOR = 2;

    /** k6 bulunamadı / {@code version} başarısız. */
    public static K6Profile unavailable(String bin, long now) {
        return new K6Profile(bin, false, null, -1, -1, -1, now);
    }

    /** {@code k6 version} çıktısından profil — sürüm okunamazsa {@code available} yine true, numaralar -1. */
    public static K6Profile fromVersionOutput(String bin, String output, long now) {
        if (output == null) return unavailable(bin, now);
        Matcher m = VERSION.matcher(output);
        if (!m.find()) {
            String s = output.strip();
            return new K6Profile(bin, true, s.length() > 40 ? s.substring(0, 40) : s, -1, -1, -1, now);
        }
        return new K6Profile(bin, true, "v" + m.group(1) + "." + m.group(2) + "." + m.group(3),
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), now);
    }

    /** Sürüm numarası okunabildi mi. */
    public boolean versionKnown() {
        return available && major >= 0;
    }

    /**
     * Komuta efemer {@code --address} verilmeli mi?
     *
     * <p>2.0'dan ESKİ k6 her koşumda {@code localhost:6565} üzerinde REST API açar; eşzamanlı koşumlar portta çakışıp
     * "Teknik detay"a gürültü basıyordu → {@code 127.0.0.1:0} veriliyordu. 2.0+ API'yi zaten açmaz ve {@code --address}
     * onu AÇAR (gereksiz bir dinleyen port) — bu yüzden 2.0+ için verilmez. Sürüm okunamadıysa eski davranış (verilir).
     */
    public boolean needsApiAddress() {
        return !versionKnown() || major < API_SERVER_OFF_SINCE_MAJOR;
    }

    /** Bu ikili modern sözdizimini ayrıştırabilir mi; sürüm okunamadıysa null (bilgisizlik). */
    public Boolean modernSyntax() {
        if (!versionKnown()) return null;
        return compare(new int[] { major, minor, patch }, MODERN_SYNTAX_SINCE) >= 0;
    }

    /** Sürüm karşılaştırması: a &lt; b → negatif. İkisi de bilinmeli (çağıran {@link #versionKnown()} denetler). */
    static int compareVersions(K6Profile a, K6Profile b) {
        return compare(new int[] { a.major, a.minor, a.patch }, new int[] { b.major, b.minor, b.patch });
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }
}

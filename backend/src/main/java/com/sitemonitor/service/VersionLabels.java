package com.sitemonitor.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semver benzeri sürüm etiketi üretimi — {@code 1.0.2} → bump türüne göre bir sonraki.
 *
 * <p><b>Neden ayrı sınıf.</b> Bu mantık {@code MonitoringController} içinde doğdu (k6 script
 * sürümleri için) ve sözleşmesi {@code ScriptedVersioningTest} ile pinlendi. Şablon kütüphanesi
 * AYNI sözleşmeyi kullanıyor; ikinci bir kopya üretmek iki sürümleme davranışının zamanla
 * ayrışmasını garanti ederdi. Controller'daki metot artık buraya delege eder, böylece mevcut
 * test dosyası TEK SATIR değişmeden aynı sözleşmeyi korumaya devam eder.
 *
 * <p>Sözleşme (testte pinli): ilk kayıt {@code 1.0.0} · varsayılan artış YAMA ·
 * {@code minor}/{@code major} alt haneleri sıfırlar · bump türü büyük/küçük harf ve boşluğa
 * duyarsız · ayrıştırılamayan etiket {@code 1.0.0}'a düşer (elle bozulmuş veri sürüm zincirini
 * KİLİTLEMESİN) · {@code v1.4.2} gibi önekli/sonekli etiketler toleranslı okunur.
 */
public final class VersionLabels {

    private VersionLabels() {}

    public static final String FIRST_VERSION = "1.0.0";

    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    public static String nextVersion(String current, String bumpType) {
        int[] p = {1, 0, 0};
        if (current != null) {
            Matcher m = SEMVER.matcher(current);
            if (m.find()) {
                p[0] = Integer.parseInt(m.group(1));
                p[1] = Integer.parseInt(m.group(2));
                p[2] = Integer.parseInt(m.group(3));
            } else {
                return FIRST_VERSION;
            }
        } else {
            return FIRST_VERSION;
        }
        String bump = bumpType == null ? "" : bumpType.trim().toLowerCase(Locale.ROOT);
        return switch (bump) {
            case "major" -> (p[0] + 1) + ".0.0";
            case "minor" -> p[0] + "." + (p[1] + 1) + ".0";
            default      -> p[0] + "." + p[1] + "." + (p[2] + 1);   // yama = varsayılan
        };
    }
}

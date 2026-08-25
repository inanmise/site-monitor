package com.sitemonitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** URL giriş normalizasyonu — şemasız girdi sahte kesinti alarmı üretmesin (2026-08-04). */
class MonitorUrlsTest {

    @Test
    @DisplayName("normalize: şemasız girdiye https:// eklenir, mevcut şema KORUNUR")
    void normalize_addsHttpsOnlyWhenMissing() {
        assertThat(MonitorUrls.normalize("www.axess.com.tr")).isEqualTo("https://www.axess.com.tr");
        assertThat(MonitorUrls.normalize("  www.axess.com.tr  ")).isEqualTo("https://www.axess.com.tr");
        assertThat(MonitorUrls.normalize("x.com/a?b=1")).isEqualTo("https://x.com/a?b=1");
        // Kullanıcının bilinçli http:// tercihi asla https'e çevrilmez (iç servis 443'te olmayabilir).
        assertThat(MonitorUrls.normalize("http://internal.host:8080/health")).isEqualTo("http://internal.host:8080/health");
        assertThat(MonitorUrls.normalize("HTTPS://X.COM")).isEqualTo("HTTPS://X.COM");
        assertThat(MonitorUrls.normalize("ftp://x.com")).isEqualTo("ftp://x.com");
        assertThat(MonitorUrls.normalize("//x.com/a")).isEqualTo("https://x.com/a");     // protokol-relatif
        assertThat(MonitorUrls.normalize("")).isEqualTo("");
        assertThat(MonitorUrls.normalize("   ")).isEqualTo("");
        assertThat(MonitorUrls.normalize(null)).isNull();
    }

    @Test
    @DisplayName("hostOrNull: http(s) + host varsa host; şema yok / host yok / kontrol edilemez şema → null")
    void hostOrNull_requiresHttpSchemeAndHost() {
        assertThat(MonitorUrls.hostOrNull("https://www.example.com/kampanya")).isEqualTo("www.example.com");
        assertThat(MonitorUrls.hostOrNull("http://user:pw@x.com:8080/a")).isEqualTo("x.com");
        assertThat(MonitorUrls.hostOrNull("https://X.COM.")).isEqualTo("x.com");          // trailing dot + case
        assertThat(MonitorUrls.hostOrNull("https://[::1]:8080/a")).isEqualTo("[::1]");    // IPv6 literal
        assertThat(MonitorUrls.hostOrNull("https://x.com?q=1")).isEqualTo("x.com");       // path'siz query
        assertThat(MonitorUrls.hostOrNull("www.axess.com.tr")).isNull();                  // şemasız → kontrol edilemez
        assertThat(MonitorUrls.hostOrNull("https://")).isNull();                          // host yok
        assertThat(MonitorUrls.hostOrNull("https:///path")).isNull();
        assertThat(MonitorUrls.hostOrNull("mailto:a@b.com")).isNull();
        assertThat(MonitorUrls.hostOrNull("ftp://x.com")).isNull();
        assertThat(MonitorUrls.hostOrNull(null)).isNull();
    }

    @Test
    @DisplayName("isCheckable: {timestamp} yer tutucusu URL'i geçersiz kılmaz (URI.create kullanılmıyor)")
    void isCheckable_toleratesKeywordTimestampPlaceholder() {
        assertThat(MonitorUrls.isCheckable("https://x.com/a?t={timestamp}")).isTrue();
        assertThat(MonitorUrls.normalize("x.com:8080/a?t={timestamp}")).isEqualTo("https://x.com:8080/a?t={timestamp}");
        assertThat(MonitorUrls.isCheckable(MonitorUrls.normalize("x.com:8080/a?t={timestamp}"))).isTrue();
        assertThat(MonitorUrls.isCheckable("www.axess.com.tr")).isFalse();
        assertThat(MonitorUrls.isCheckable("")).isFalse();
    }

    /**
     * İKİ AYLIK SESSİZ HATA — 2026-06-22'de kaydedilen bir Kelime izlemesinin URL'inde BOŞLUK vardı
     * ({@code "http://localhost:8080/health- Orjinal"}). Doğrulama yalnız HOST'a baktığı için
     * ("localhost" geçerli) kayıt kabul edildi; her kontrol {@code Illegal character in path at
     * index 29} ile düştü. 12 000+ başarısız kontrol üretti, sıradan bir "DOWN" gibi göründü,
     * kimse fark etmedi — üstelik alarm bastırma oranını da şişirip başka monitörlerin gerçek
     * kesintisini maskeledi.
     */
    @Test
    @DisplayName("SAHA VAKASI: URL'de boşluk/kontrol karakteri varsa kontrol edilemez sayılır")
    void isCheckable_rejectsWhitespaceInUrl() {
        assertThat(MonitorUrls.isCheckable("http://localhost:8080/health- Orjinal")).isFalse();
        assertThat(MonitorUrls.isCheckable("https://x.com/a b")).isFalse();
        assertThat(MonitorUrls.isCheckable("https://x.com/a\tb")).isFalse();
        assertThat(MonitorUrls.isCheckable("https://x.com/a\nb")).isFalse();

        // Baş/son boşluk normalize ile kırpılır → kayıt REDDEDİLMEZ (kullanıcıyı boş yere engelleme)
        assertThat(MonitorUrls.isCheckable(MonitorUrls.normalize("  https://x.com/a  "))).isTrue();

        // Yer tutucu ve normal URL'ler etkilenmez
        assertThat(MonitorUrls.isCheckable("https://x.com/a?t={timestamp}")).isTrue();
        assertThat(MonitorUrls.isCheckable("https://x.com/a%20b")).isTrue();   // kodlanmış boşluk MEŞRU
    }

    @Test
    @DisplayName("hasIllegalWhitespace boşluğu KODLAMAZ — sessizce başka hedefe istek atılmaz")
    void whitespaceIsReportedNotRepaired() {
        String bad = "http://x.com/a b";
        assertThat(MonitorUrls.hasIllegalWhitespace(bad)).isTrue();
        // normalize yalnız şema ekler/kırpar; iç boşluğu düzeltmeye ÇALIŞMAZ
        assertThat(MonitorUrls.normalize(bad)).isEqualTo(bad);
        assertThat(MonitorUrls.hasIllegalWhitespace("http://x.com/ab")).isFalse();
        assertThat(MonitorUrls.hasIllegalWhitespace(null)).isFalse();
    }
}

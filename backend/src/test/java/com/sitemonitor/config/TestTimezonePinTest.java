package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI: test JVM'inin saat dilimi UTC'ye SABİT olmalı.
 *
 * <p>Sabitleme {@code pom.xml} surefire {@code argLine}'ında ({@code -Duser.timezone=UTC}) ve
 * {@code .github/workflows/ci.yml}'de ({@code TZ: UTC}) yapılıyor. Bu test, sabitlemenin
 * kazara kaldırılmasını ANINDA ve açık bir mesajla yakalar.
 *
 * <p><b>Neden gerekiyor.</b> CI runner'ı UTC, geliştirici makinesi Europe/Istanbul. Sabitleme
 * olmadan testlerdeki çıplak {@code now()} çağrıları iki ortamda FARKLI gün/hafta hesaplar:
 * yerelde yeşil olan bir test CI'da kırmızı olur (ya da tersi) ve sebebi kodda görünmez.
 * 2026-09-07'de bu sınıftan üç ayrı zaman bombası aynı gün patladı — ikisi backend'de
 * (sabit ISO hafta + 8 haftalık pencere), biri frontend'de (sabit tarih + 30 günlük pencere).
 *
 * <p>Bu kapı olmasaydı, {@code argLine} bir refaktörde silindiğinde hiçbir şey kırılmaz;
 * sorun aylar sonra, alakasız bir değişiklikte "CI'da kırmızı, yerelde yeşil" olarak geri gelirdi.
 *
 * <p>UYGULAMA DAVRANIŞI BU BAYRAKTAN ETKİLENMEZ: servisler kendi zaman dilimlerini açıkça
 * seçiyor ({@code ZoneId.of("Europe/Istanbul")}). Etkilenen tek şey testlerin çıplak
 * {@code now()} çağrılarıdır — kasıt da tam olarak budur.
 */
class TestTimezonePinTest {

    @Test
    @DisplayName("SOZLESME: test JVM'i UTC — pom surefire argLine'indaki -Duser.timezone=UTC silinmemis")
    void jvmDefaultTimezone_isUtc() {
        assertThat(TimeZone.getDefault().getID())
                .as("Test JVM'i UTC degil. pom.xml surefire <argLine> icindeki "
                    + "-Duser.timezone=UTC silinmis olabilir. Sabitleme kaldirilirsa testlerdeki "
                    + "ciplak now() cagrilari yerelde (Europe/Istanbul) ve CI'da (UTC) FARKLI "
                    + "gun/hafta hesaplar; yerelde yesil olan test CI'da kirmizi olur.")
                .isEqualTo("UTC");
    }

    @Test
    @DisplayName("UTC'de ofset SIFIR — 'UTC' adi tasiyip kaymali bir dilim gelmesin")
    void utcHasZeroOffset() {
        assertThat(TimeZone.getDefault().getRawOffset()).isZero();
        assertThat(TimeZone.getDefault().useDaylightTime())
                .as("yaz saati uygulayan bir dilim gun sinirinda kayma uretir")
                .isFalse();
    }
}

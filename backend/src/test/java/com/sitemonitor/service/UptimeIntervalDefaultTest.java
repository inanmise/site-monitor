package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Envanter erişilebilirlik (HTTP uptime) süpürme aralığının varsayılanı İKİ yerde yazılı ve ikisi
 * AYNI olmalı: {@code application.properties} çalışan uygulamanın değeri, {@code SchedulerService}
 * üstündeki {@code @Scheduled} yedeği ise özellik dosyası yüklenmediğinde devreye girer.
 *
 * <p><b>Neden bir de DEĞERİ pinliyoruz:</b> 2026-09-19'da kullanıcı isteğiyle 5 dk'dan 1 saate
 * çıkarıldı — Durum İzleme kartındaki HTTP Kontrol Geçmişi, saat başı koşan SSL kontrolüyle aynı
 * sıklıkta dolsun diye. Kazara 5 dk'ya dönmesi (ya da SSL cron'undan ayrışması) fark edilmeden
 * olmamalı. Testin kırılması "yanlış yaptın" demez, "bu sıklığı gerçekten değiştirmek istediğine
 * emin misin" der.
 */
class UptimeIntervalDefaultTest {

    private static final long EXPECTED_MS = 3_600_000L;   // 1 saat

    private static final Path PROPS = Path.of("src/main/resources/application.properties");
    private static final Path SERVICE = Path.of("src/main/java/com/sitemonitor/service/SchedulerService.java");

    private static long matchLong(Path file, String regex) throws Exception {
        Matcher m = Pattern.compile(regex).matcher(Files.readString(file));
        assertThat(m.find()).as("desen bulunamadı: %s (%s)", regex, file).isTrue();
        return Long.parseLong(m.group(1));
    }

    @Test
    @DisplayName("Uptime süpürme aralığı varsayılanı 1 saat (SSL kontrolüyle aynı sıklık — kazara değişmesin)")
    void defaultIsOneHour() throws Exception {
        long props = matchLong(PROPS, "site\\.monitor\\.uptime\\.interval-ms=\\$\\{UPTIME_INTERVAL_MS:(\\d+)\\}");
        long code = matchLong(SERVICE, "\\$\\{site\\.monitor\\.uptime\\.interval-ms:(\\d+)\\}");
        assertThat(props).as("application.properties").isEqualTo(EXPECTED_MS);
        assertThat(code).as("SchedulerService @Scheduled yedeği").isEqualTo(EXPECTED_MS);
    }

    @Test
    @DisplayName("SSL süpürme cron'u saat başı kalır (0 0 * * * *) — HTTP ile aynı sıklık varsayımı")
    void sslSweepStaysHourly() throws Exception {
        String service = Files.readString(SERVICE);
        assertThat(service).contains("${site.monitor.scheduler.cron:0 0 * * * *}");
    }
}

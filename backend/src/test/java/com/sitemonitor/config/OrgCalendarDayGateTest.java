package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI: ÜRETİM kodunda takvim günü kararı UTC ile verilmez.
 *
 * <p><b>Ne yakalar.</b> Zone'suz bir {@code LocalDate} "şimdi" çağrısı "bugün hangi gün?"
 * sorusudur; UTC ile sorulunca
 * kurum saatine (Europe/Istanbul, UTC+3) göre her gece <b>00:00–03:00 arasında bir gün geride</b>
 * cevap verir. Sonuç sessizdir: süresi dolmuş bir istisna raporda üç saat daha "kabul edildi"
 * görünür, biten bir alan adı pencereye girmez, geçmiş bir tarih "geçmişte değil" sayılır.
 * Test yeşil kalır çünkü gün sınırına denk gelmeyen her koşumda iki zon aynı cevabı verir.
 *
 * <p><b>Geçmişi.</b> 2026-09-23 taraması aynı kusuru iki serviste, üç çağrı yerinde buldu:
 * {@code WeakAlgorithmReportService} (istisna geçerliliği + {@code until_past} doğrulaması) ve
 * {@code WeeklyAvailabilityReportService} (alan adı bitiş penceresi). Daha öncesinde
 * {@code MonitoringController} aynı sebeple {@code ORG_ZONE}'a çekilmişti — yani düzeltme örneği
 * kapatılmış, SINIF açık kalmıştı. Bu kapı sınıfı kapatır.
 *
 * <p><b>Karıştırılmaması gereken:</b> {@code LocalDateTime.now(ZoneOffset.UTC)} bir ZAMAN
 * DAMGASIDIR (saklama/karşılaştırma) ve doğrudur — kapı ona dokunmaz. Yasak yalnız
 * {@code LocalDate.now(...)}, yani takvim günü kararı.
 *
 * <p>Kardeş kapı: {@link NoBareLocalNowInTestsTest} (test kaynakları). DESEN NOTU: regex
 * kaçışsız karakter sınıflarıyla yazıldı ({@code [.]}, {@code [(]}) — ters-bölü taşıyan kaynak
 * üretim/düzenleme araçlarından geçerken sessizce bozulabiliyor.
 */
class OrgCalendarDayGateTest {

    /** Zone'suz ya da UTC'li takvim-günü çağrısı (desen, kardeş kapıya takılmasın diye parçalı anlatıldı). */
    private static final Pattern UTC_OR_BARE_DAY =
            Pattern.compile("LocalDate[.]now[(][ ]*[)]|LocalDate[.]now[(][^)]*(ZoneOffset[.]UTC|UTC[)])");

    /** Satır bir yorum/javadoc satırı mı (kapı, kendisini anlatan yorumlara takılmasın). */
    private static final Pattern COMMENT_LINE = Pattern.compile("^[ \t]*([*]|//|/[*])");

    /** Gerekçeli muafiyet: dosya yolu → NEDEN UTC günü doğru. Cırcır yalnız küçülür. */
    private static final Map<String, String> EXEMPT = Map.of();

    private static Path mainRoot() {
        Path p = Path.of("src/main/java");
        return Files.isDirectory(p) ? p : Path.of("backend/src/main/java");
    }

    private static String rel(Path root, Path f) {
        return root.relativize(f).toString().replace(File.separatorChar, '/');
    }

    private static List<String> offendingLines(Path f) throws IOException {
        List<String> hits = new ArrayList<>();
        int no = 0;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            no++;
            if (COMMENT_LINE.matcher(line).find()) continue;
            Matcher m = UTC_OR_BARE_DAY.matcher(line);
            if (m.find()) hits.add(no + ": " + line.trim());
        }
        return hits;
    }

    @Test
    @DisplayName("Tarama vakum DEĞİL — üretim kök dizini bulunuyor ve kayda değer sayıda dosya okunuyor")
    void scanIsNotVacuous() throws IOException {
        Path root = mainRoot();
        assertThat(Files.isDirectory(root)).as("üretim kök dizini bulunamadı: %s", root).isTrue();
        try (Stream<Path> walk = Files.walk(root)) {
            long n = walk.filter(f -> f.toString().endsWith(".java")).count();
            assertThat(n).as("hiç üretim dosyası okunmadı — kapı sessizce yeşil kalırdı").isGreaterThan(200);
        }
    }

    @Test
    @DisplayName("KAPI: üretim kodunda takvim günü zone'suz ya da UTC ile sorulmaz (kurum saati)")
    void calendarDayIsOrgZoned() throws IOException {
        Path root = mainRoot();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                String r = rel(root, f);
                if (EXEMPT.containsKey(r)) continue;
                for (String hit : offendingLines(f)) offenders.add(r + ":" + hit);
            }
        }
        assertThat(offenders)
                .as("Takvim günü UTC ile hesaplanıyor: kurum saatine göre her gece 00:00–03:00 "
                  + "arasında BİR GÜN geri cevap verir ve kusur sessizdir (süresi dolmuş istisna "
                  + "'kabul edildi' kalır, biten alan adı pencereye girmez). Kurum dilimini "
                  + "kullanın: LocalDate.now(ZoneId.of(\"Europe/Istanbul\")). Zaman DAMGASI "
                  + "gerekiyorsa LocalDateTime.now(ZoneOffset.UTC) doğrudur ve bu kapıya takılmaz.")
                .isEmpty();
    }

    @Test
    @DisplayName("Muafiyet listesi ÖLÜ kayıt taşımaz — cırcırı yalnızca küçülür")
    void exemptionsAreAllAlive() throws IOException {
        Path root = mainRoot();
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, String> e : EXEMPT.entrySet()) {
            Path f = root.resolve(e.getKey());
            if (!Files.exists(f) || offendingLines(f).isEmpty()) dead.add(e.getKey());
        }
        assertThat(dead).as("Bu dosyalarda artık UTC takvim günü yok — muafiyet DÜŞMELİ").isEmpty();
    }
}

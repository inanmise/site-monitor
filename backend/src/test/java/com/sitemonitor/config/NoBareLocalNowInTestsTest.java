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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI: test kaynaklarında ZONE'SUZ {@code LocalDate.now()} / {@code LocalDateTime.now()} yok.
 *
 * <p><b>Neden ayrı bir kapı.</b> {@link TestTimezonePinTest} JVM'i UTC'ye sabitleyip CI ile
 * yereli hizalıyor, ama o sabitleme yalnız surefire {@code argLine} içinde geçerli: IDE'den ya
 * da başka bir runner'dan koşan AYNI test yine yerel dilimi görür. Asıl dayanıklılık fixture'ın
 * zone'u AÇIKÇA seçmesidir.
 *
 * <p><b>Ne yakalar.</b> 2026-09-23 denetimi {@code DomainCheckerServiceTest}'te yedi çıplak
 * {@code LocalDate.now()} buldu. Servis ({@code DomainCheckerService.daysUntil}) beklenen tarihi
 * {@code atStartOfDay(ZoneOffset.UTC)} ile UTC gece yarısına sabitleyip {@code Instant.now()} ile
 * farkı {@code floorDiv} ediyor; fixture zone'suz kalınca {@code plusDays(N)} için hesaplanan
 * kalan gün CI'da (UTC) <b>N-1</b>, yerelde (Europe/Istanbul, 00:00–03:00) <b>N</b> çıkıyordu.
 * İddialar eşiklerden uzak olduğu için henüz ısırmıyordu — eşiklerin ya da fixture'ların bir adım
 * kayması yeterliydi ve sonuç "yerelde yeşil, CI'da kırmızı" olurdu.
 *
 * <p><b>Nasıl düzeltilir.</b> Servisin kendi zaman tabanını seçin: UTC gece yarısına göre
 * hesaplayan kod için {@code LocalDate.now(ZoneOffset.UTC)}, kurum gününe göre hesaplayan kod
 * için {@code LocalDate.now(ZoneId.of("Europe/Istanbul"))}.
 *
 * <p>DESEN NOTU: regex kaçışsız karakter sınıflarıyla yazıldı ({@code [.]}, {@code [(]}) —
 * ters-bölü taşıyan kaynak, üretim/düzenleme araçlarından geçerken sessizce bozulabiliyor.
 */
class NoBareLocalNowInTestsTest {

    /** {@code LocalDate.now()} / {@code LocalDateTime.now()} — argümansız çağrı. */
    private static final Pattern BARE_NOW =
            Pattern.compile("(LocalDate|LocalDateTime)[.]now[(][ ]*[)]");

    /** Gerekçeli muafiyet: dosya yolu → NEDEN çıplak now() bilinçli. Cırcır yalnız küçülür. */
    private static final Map<String, String> EXEMPT = Map.of();

    private static Path testRoot() {
        Path p = Path.of("src/test/java");
        return Files.isDirectory(p) ? p : Path.of("backend/src/test/java");
    }

    private static String rel(Path root, Path f) {
        return root.relativize(f).toString().replace(File.separatorChar, '/');
    }

    @Test
    @DisplayName("Tarama vakum DEGIL — test kok dizini bulunuyor ve kayda deger sayida dosya okunuyor")
    void scanIsNotVacuous() throws IOException {
        Path root = testRoot();
        assertThat(Files.isDirectory(root)).as("test kok dizini bulunamadi: %s", root).isTrue();
        try (Stream<Path> walk = Files.walk(root)) {
            long n = walk.filter(f -> f.toString().endsWith(".java")).count();
            assertThat(n).as("hic test dosyasi okunmadi — kapi sessizce yesil kalirdi").isGreaterThan(100);
        }
    }

    @Test
    @DisplayName("KAPI: test kaynaklarinda zone'suz LocalDate.now()/LocalDateTime.now() YOK")
    void noBareLocalNowInTestSources() throws IOException {
        Path root = testRoot();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                String r = rel(root, f);
                if (EXEMPT.containsKey(r)) continue;
                if (r.endsWith("NoBareLocalNowInTestsTest.java")) continue;   // kapinin kendi regex'i
                if (BARE_NOW.matcher(Files.readString(f, StandardCharsets.UTF_8)).find()) offenders.add(r);
            }
        }
        assertThat(offenders)
                .as("Zone'suz LocalDate.now()/LocalDateTime.now(): fixture yerelde Europe/Istanbul, "
                  + "CI'da UTC okur ve gun sinirinda BIR GUN kayar (yerelde yesil, CI'da kirmizi). "
                  + "Servisin kendi zaman tabanini acikca secin: UTC gece yarisina gore hesaplayan "
                  + "kod icin LocalDate.now(ZoneOffset.UTC), kurum gunune gore hesaplayan kod icin "
                  + "LocalDate.now(ZoneId.of(\"Europe/Istanbul\")). Zorunlu bir kalinti ise "
                  + "NoBareLocalNowInTestsTest.EXEMPT'e GEREKCESIYLE ekleyin.")
                .isEmpty();
    }

    @Test
    @DisplayName("Muafiyet listesi OLU kayit tasimaz — circiri yalnizca kuculur")
    void exemptionsAreAllAlive() throws IOException {
        Path root = testRoot();
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, String> e : EXEMPT.entrySet()) {
            Path f = root.resolve(e.getKey());
            if (!Files.exists(f) || !BARE_NOW.matcher(Files.readString(f, StandardCharsets.UTF_8)).find())
                dead.add(e.getKey());
        }
        assertThat(dead).as("Bu dosyalarda artik ciplak now() yok — muafiyet DUSMELI").isEmpty();
    }
}

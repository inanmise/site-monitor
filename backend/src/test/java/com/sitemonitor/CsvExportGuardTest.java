package com.sitemonitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV dışa aktarımlarının TEK kuralı {@link com.sitemonitor.util.Csv} — bu kapı onu kaynak düzeyinde kilitler.
 *
 * <p>2026-08-23 denetimi formül enjeksiyonu (CWE-1236) korumasını ortak sınıfa taşırken şunu yazmıştı:
 * "Kural tek yerde durmazsa bir sonraki dışa aktarım yine korumasız yazılır." 2026-09-11 regresyon
 * taramasında tam bu oldu: {@code RetentionAdminController} kendi {@code csvRow}'unu yazmıştı, yeni
 * {@code DeploymentHistoryController} onu kopyaladı, {@code InventoryExportService.csvEscape} de aynı
 * eksikle duruyordu — üçünde de {@code =}/{@code +}/{@code -}/{@code @} ile başlayan hücre nötrlenmiyordu.
 * Kod okuması bunu üçüncü örnekte yakaladı; bu test dördüncüyü CI'da yakalar.
 *
 * <p>İki kural: (1) {@code text/csv} üreten her kaynak dosya {@code Csv.} kullanır (ya da CSV gövdesini
 * kullanan bir sınıfa devrettiği için muafiyet listesindedir); (2) hiçbir dosya tırnak ikilemesini
 * ({@code replace("\"", "\"\"")}) kendi eliyle yapmaz — bu, elle yazılmış CSV kaçışının parmak izidir.
 */
class CsvExportGuardTest {

    /** text/csv yazıyor ama gövdeyi Csv kullanan başka sınıf üretiyor — gerekçesiyle. */
    private static final Map<String, String> DELEGATES = Map.of(
            "service/report/CertificateInventoryReportService.java",
            "CSV gövdesi InventoryExportService.csv() → Csv.cell; burası yalnız MIME türünü ekliyor",
            "controller/CertificateController.java",
            "CSV gövdesi CertificateService.exportCsv() → Csv.row; controller yalnız MIME türü + denetim kaydı ekliyor");

    private static final String HAND_ROLLED_QUOTING = "replace(\"\\\"\", \"\\\"\\\"\")";

    @Test
    @DisplayName("Tarama vakum DEĞİL — main kaynakları bulunuyor, birden çok CSV üreticisi var")
    void scanIsNotVacuous() {
        List<Path> files = mainSources();
        assertThat(files).hasSizeGreaterThan(100);
        long csvWriters = files.stream().filter(p -> read(p).contains("text/csv")).count();
        assertThat(csvWriters).as("text/csv üreten dosya sayısı").isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("text/csv üreten her dosya Csv.cell/Csv.row kullanır (devreden sınıflar gerekçeli muaf)")
    void everyCsvWriterUsesSharedRule() {
        List<String> offenders = new ArrayList<>();
        for (Path p : mainSources()) {
            String src = read(p);
            if (!src.contains("text/csv")) continue;
            String r = rel(p);
            if (DELEGATES.containsKey(r)) continue;
            if (!src.contains("Csv.")) offenders.add(r + " — text/csv yazıyor ama com.sitemonitor.util.Csv kullanmıyor");
        }
        assertThat(offenders).as("Csv kuralı dışında CSV üreten dosyalar").isEmpty();
    }

    @Test
    @DisplayName("Elle tırnak ikileme (replace(\"\\\"\", \"\\\"\\\"\")) yalnız util/Csv.java'da olabilir")
    void noHandRolledCsvEscaping() {
        List<String> offenders = new ArrayList<>();
        for (Path p : mainSources()) {
            String r = rel(p);
            if (r.equals("util/Csv.java")) continue;
            if (read(p).contains(HAND_ROLLED_QUOTING))
                offenders.add(r + " — kendi CSV kaçışını yazıyor; Csv.cell/Csv.row kullan (formül nötrlemesi yok)");
        }
        assertThat(offenders).as("elle yazılmış CSV kaçışı").isEmpty();
    }

    @Test
    @DisplayName("Muafiyet listesi bayat DEĞİL — listedeki her dosya hâlâ var ve hâlâ text/csv yazıyor")
    void delegateListIsCurrent() {
        for (String r : DELEGATES.keySet()) {
            Path p = mainRoot().resolve(r);
            assertThat(Files.isRegularFile(p)).as("muaf dosya silinmiş: %s", r).isTrue();
            assertThat(read(p)).as("muaf dosya artık text/csv yazmıyor, listeden çıkar: %s", r).contains("text/csv");
        }
    }

    // ── yardımcılar ───────────────────────────────────────────────────────────

    private static Path mainRoot() {
        return Path.of("src/main/java/com/sitemonitor").toAbsolutePath().normalize();
    }

    private static String rel(Path p) {
        return mainRoot().relativize(p).toString().replace('\\', '/');
    }

    private static List<Path> mainSources() {
        try (Stream<Path> walk = Files.walk(mainRoot())) {
            return walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

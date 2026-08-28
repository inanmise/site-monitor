package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code applySchemaPatches()} içindeki HER tablo adı gerçek bir entity tablosu olmalı.
 *
 * <p>Yakalanan hata (D1): altı patch {@code page_speed_checks}'e yazıyordu — entity ise
 * {@code pagespeed_checks} (fazladan alt çizgi). {@code patch()} istisnayı bilinçli olarak
 * yutuyor (var olan kolon gürültü çıkarmasın diye), bu yüzden altı satır SESSİZ no-op'tu:
 * hata yok, log yok, kolon yok. {@code ddl-auto=update} maskeliyordu ama güvenlik ağının
 * kendisi kırıktı — asıl işini görmesi gereken senaryoda (ddl-auto kapalı / kolon eksik)
 * hiçbir şey yapmayacaktı.
 *
 * <p>Yazım hatası sessiz kaldığı için ancak kaynak taramasıyla yakalanır.
 */
class PatchTableNamesTest {

    private static final Path SCHEDULER =
            Path.of("src/main/java/com/sitemonitor/service/SchedulerService.java");
    private static final Path MODEL_DIR = Path.of("src/main/java/com/sitemonitor/model");

    /** patch("ALTER TABLE x …") / patch("CREATE TABLE IF NOT EXISTS x …") → tablo adları. */
    private static Set<String> patchedTables() throws Exception {
        String src = Files.readString(SCHEDULER);
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile(
                "patch[(][\"](?:ALTER|CREATE)[ ]+TABLE[ ]+(?:IF[ ]+NOT[ ]+EXISTS[ ]+)?([a-z_]+)",
                Pattern.CASE_INSENSITIVE).matcher(src);
        while (m.find()) out.add(m.group(1).toLowerCase());
        return out;
    }

    /** Entity'lerdeki @Table(name = "…") + Spring Session'ın kendi tabloları. */
    private static Set<String> knownTables() throws Exception {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MODEL_DIR)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = Pattern.compile("@Table[(][^)]*?name[ ]*=[ ]*[\"]([a-z_]+)[\"]",
                        Pattern.DOTALL).matcher(Files.readString(p));
                while (m.find()) out.add(m.group(1).toLowerCase());
            }
        }
        // Entity'si olmayan, doğrudan SQL ile yönetilen tablolar (bilinçli).
        out.addAll(List.of("scheduler_lock", "spring_session", "spring_session_attributes",
                "monitor_check_daily", "monitor_check_hourly"));
        return out;
    }

    @Test
    @DisplayName("Şema yamalarındaki her tablo adı gerçek bir tabloya karşılık gelir")
    void everyPatchedTableExists() throws Exception {
        Set<String> patched = patchedTables();
        Set<String> known = knownTables();

        assertThat(patched).as("patch taraması boş — desen ya da dosya yolu değişmiş")
                .hasSizeGreaterThan(10);
        assertThat(known).as("entity taraması boş — model dizini değişmiş")
                .hasSizeGreaterThan(10);

        Set<String> unknown = new LinkedHashSet<>(patched);
        unknown.removeAll(known);
        assertThat(unknown)
                .as("Şema yaması var OLMAYAN tabloya yazıyor — patch() istisnayı yuttuğu için "
                        + "SESSİZ no-op olur (D1: page_speed_checks vs pagespeed_checks). "
                        + "Entity tablo adıyla eşleştirin ya da bu testin bilinen-tablo listesine ekleyin.")
                .isEmpty();
    }
}

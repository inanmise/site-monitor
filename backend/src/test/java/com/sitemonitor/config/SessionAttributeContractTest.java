package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI — oturumdan OKUNAN her öznitelik adı, üretim kodunda bir yerde YAZILIR (2026-09-27).
 *
 * <p>Neden: dört yer {@code "fullName"} okuyordu (değişiklik geçmişi "kim değiştirdi", yenileme planını koyanın adı,
 * bildirim grubu / sentetik şablon oluşturanın adı) ama giriş oturuma yalnız {@code "displayName"} yazıyordu → üretimde
 * ad hep boştu ya da kullanıcı adına düşüyordu. Birim testleri bu hatayı GİZLEDİ: sahte oturuma {@code "fullName"}
 * elle konuyordu. Kural kaynağı metin düzeyinde tarar: {@code session.getAttribute("x")} ve
 * {@code strAttr/longAttr/str/attr(session, "x")} okumalarının her adı için {@code setAttribute("x", …)} bulunmalı.
 */
class SessionAttributeContractTest {

    private static final Pattern READ_GET = Pattern.compile("session\\s*\\.\\s*getAttribute\\(\\s*\"([A-Za-z_.]+)\"");
    private static final Pattern READ_HELPER = Pattern.compile("(?:strAttr|longAttr|str|attr)\\(\\s*session\\s*,\\s*\"([A-Za-z_.]+)\"");
    private static final Pattern WRITE = Pattern.compile("setAttribute\\(\\s*\"([A-Za-z_.]+)\"");

    private static Path mainRoot() {
        Path p = Path.of("src/main/java");
        return Files.isDirectory(p) ? p : Path.of("backend/src/main/java");
    }

    @Test
    @DisplayName("Okunan her oturum özniteliği bir yerde yazılır (\"fullName\" sınıfı hata)")
    void everyReadSessionAttributeIsWritten() throws IOException {
        Map<String, Set<String>> reads = new TreeMap<>();
        Set<String> writes = new TreeSet<>();
        try (Stream<Path> files = Files.walk(mainRoot())) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                collect(READ_GET, src, f, reads);
                collect(READ_HELPER, src, f, reads);
                Matcher w = WRITE.matcher(src);
                while (w.find()) writes.add(w.group(1));
            }
        }
        assertThat(reads).as("tarama hiçbir okuma bulmadı — desen bozuk").isNotEmpty();
        assertThat(writes).as("tarama hiçbir yazma bulmadı — desen bozuk").contains("username", "displayName");

        Map<String, Set<String>> orphans = new TreeMap<>();
        reads.forEach((name, where) -> { if (!writes.contains(name)) orphans.put(name, where); });
        assertThat(orphans)
                .as("Oturumdan okunan ama hiçbir yerde yazılmayan öznitelik — üretimde hep null döner "
                        + "(testte sahte oturuma elle konmuş olabilir). Giriş displayName yazar, fullName DEĞİL.")
                .isEmpty();
    }

    private static void collect(Pattern p, String src, Path f, Map<String, Set<String>> into) {
        Matcher m = p.matcher(src);
        while (m.find()) into.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(f.getFileName().toString());
    }
}

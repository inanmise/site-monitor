package com.sitemonitor.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kaynak kapısı (performans 2026-11-06): "izleme başına son kontrol" LATERAL sorguları
 * {@code ORDER BY <zaman> DESC LIMIT 1} biçiminde YAZILMAZ. Bu biçimde PostgreSQL tekil {@code checked_at} indexini geri
 * tarayıp {@code monitor_id} süzgeciyle satır eliyordu (yerel 2,3 M satırlık port_checks'te 1,9 sn). Sağlam biçim:
 * {@code … AND r.checked_at = (SELECT MAX(x.checked_at) … ) ORDER BY r.id DESC LIMIT 1} — MAX bileşik indexte index-only
 * taramayla bulunur.
 */
class LatestPerMonitorQueryGateTest {

    private static final Path REPO_DIR = Path.of("src/main/java/com/sitemonitor/repository");
    /** LATERAL alt sorgusunun gövdesi: Java'da "…" + "…" ile bölünmüş olabilir — birleştirilmiş metinde aranır. */
    private static final Pattern LATERAL = Pattern.compile("CROSS JOIN LATERAL\\s*\\((.{0,600}?)\\)\\s*[a-z]\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern OLD_FORM = Pattern.compile("ORDER BY\\s+\\w+\\.checked_at\\s+DESC\\s+LIMIT\\s+1",
            Pattern.CASE_INSENSITIVE);

    /** Java dize birleştirmelerini ({@code " + "}) kaldırır — sorgu metni tek parça okunur. */
    static String joinedSql(String source) {
        return source.replaceAll("\"\\s*\\+\\s*\"", "");
    }

    @Test
    void latestPerMonitorQueriesUseTheMaxForm() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(REPO_DIR)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String sql = joinedSql(Files.readString(f, StandardCharsets.UTF_8));
                Matcher m = LATERAL.matcher(sql);
                while (m.find()) {
                    String body = m.group(1);
                    if (!body.toLowerCase().contains("checked_at")) continue;
                    checked++;
                    if (OLD_FORM.matcher(body).find() || !body.toUpperCase().contains("MAX(")) {
                        offenders.add(f.getFileName() + ": " + body.replaceAll("\\s+", " "));
                    }
                }
            }
        }
        assertThat(checked).as("LATERAL son-kontrol sorgusu bulunamadı — kapı yanlış dizini mi okuyor?").isGreaterThanOrEqualTo(11);
        assertThat(offenders).as("ORDER BY checked_at DESC LIMIT 1 yerine MAX(checked_at) eşitliği kullanın").isEmpty();
    }

    @Test
    void gateDetectsTheOldForm() {
        String old = joinedSql("\"SELECT c.* FROM port_monitors m CROSS JOIN LATERAL \"\n + \"(SELECT * FROM port_checks pc "
                + "WHERE pc.monitor_id = m.id ORDER BY pc.checked_at DESC LIMIT 1) c\"");
        Matcher m = LATERAL.matcher(old);
        assertThat(m.find()).isTrue();
        assertThat(OLD_FORM.matcher(m.group(1)).find()).isTrue();
    }
}

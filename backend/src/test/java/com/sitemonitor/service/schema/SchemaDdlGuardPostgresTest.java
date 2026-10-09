package com.sitemonitor.service.schema;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Şema yaması kilit güvenliği GERÇEK PostgreSQL'de (2026-10-09) — {@code mvn -Ppostgres-it test}.
 * <ul>
 *   <li>katalog denetimleri gerçek değişikliği doğru tanır (tip, NOT NULL, kısıt, reloptions, indeks);</li>
 *   <li>başka oturum tabloyu tutarken yama süresiz BEKLEMEZ: lock_timeout dolar (55P03), oturum ayarı sıfırlanır;</li>
 *   <li>KAPI: açılıştan sonra her açılışta koşan (koşulsuz) yamaların HEPSİ "zaten yerinde" — normal açılış tabloya
 *       kilit almaz. Yeni bir koşulsuz yama biçimi eklenirse ya tanıyıcı eklenmeli ya da yama koşullu yazılmalı.</li>
 * </ul>
 */
@PostgresIntegration
class SchemaDdlGuardPostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    @Test
    @DisplayName("Katalog denetimleri: tip / NOT NULL / kısıt / reloptions / indeks — önce değil, değişiklikten sonra yerinde")
    void inPlaceChecks_realCatalog() {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("DROP TABLE IF EXISTS it_guard_probe");
        jdbc.execute("CREATE TABLE it_guard_probe (a VARCHAR(10) NOT NULL, b TEXT, CONSTRAINT it_guard_uk UNIQUE (a))");
        try {
            SchemaDdlGuard g = new SchemaDdlGuard(jdbc);
            assertThat(g.alreadyInPlace("ALTER TABLE it_guard_probe ALTER COLUMN b TYPE TEXT")).isTrue();
            assertThat(g.alreadyInPlace("ALTER TABLE it_guard_probe ALTER COLUMN a TYPE VARCHAR(10)")).isTrue();
            assertThat(g.alreadyInPlace("ALTER TABLE it_guard_probe ALTER COLUMN a TYPE VARCHAR(40)")).isFalse();
            assertThat(g.alreadyInPlace("ALTER TABLE it_guard_probe ALTER COLUMN a TYPE TEXT")).isFalse();

            String dropNn = "ALTER TABLE it_guard_probe ALTER COLUMN a DROP NOT NULL";
            assertThat(g.alreadyInPlace(dropNn)).isFalse();
            g.executeBounded(dropNn, false);
            assertThat(g.alreadyInPlace(dropNn)).isTrue();

            String dropUk = "ALTER TABLE it_guard_probe DROP CONSTRAINT IF EXISTS it_guard_uk";
            assertThat(g.alreadyInPlace(dropUk)).isFalse();
            g.executeBounded(dropUk, false);
            assertThat(g.alreadyInPlace(dropUk)).isTrue();

            String vac = "ALTER TABLE it_guard_probe SET (autovacuum_vacuum_scale_factor = 0.02, autovacuum_vacuum_threshold = 5000)";
            assertThat(g.alreadyInPlace(vac)).isFalse();
            g.executeBounded(vac, false);
            assertThat(g.alreadyInPlace(vac)).isTrue();

            String idx = "CREATE INDEX IF NOT EXISTS it_guard_idx ON it_guard_probe(b)";
            assertThat(g.alreadyInPlace(idx)).isFalse();
            g.executeBounded(idx, false);
            assertThat(g.alreadyInPlace(idx)).isTrue();

            assertThat(g.alreadyInPlace("ALTER TABLE it_guard_missing ALTER COLUMN x DROP NOT NULL"))
                    .as("tablo yok → eski yolda da sessiz no-op").isTrue();
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS it_guard_probe");
        }
    }

    @Test
    @DisplayName("KAPI: başka oturum tabloyu tutarken yama süresiz beklemez — lock_timeout dolar (55P03), ayar sıfırlanır, koşucu sürer")
    void lockTimeout_realLock_isBounded_andReset() throws Exception {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("DROP TABLE IF EXISTS it_guard_lock");
        jdbc.execute("CREATE TABLE it_guard_lock (a VARCHAR(10) NOT NULL)");
        try (Connection holder = PostgresIt.app().newSession();
             Connection mine = PostgresIt.app().newSession()) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.execute("SELECT count(*) FROM it_guard_lock");          // ACCESS SHARE, işlem açık kalır
            }
            JdbcTemplate single = new JdbcTemplate(new SingleConnectionDataSource(mine, true));
            SchemaDdlGuard g = new SchemaDdlGuard(single, 1);
            String ddl = "ALTER TABLE it_guard_lock ALTER COLUMN a DROP NOT NULL";
            assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                    assertThatThrownBy(() -> g.executeBounded(ddl, false))
                            .satisfies(e -> assertThat(SchemaDdlGuard.isLockTimeout(e)).isTrue()));
            assertThat(single.queryForObject("SHOW lock_timeout", String.class)).as("oturum ayarı havuza sızmamalı").isEqualTo("0");

            SchemaPatchRunner runner = new SchemaPatchRunner(single, 10, g);
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> runner.patch(ddl));
            SchemaPatchRunner.Summary s = runner.finish();
            assertThat(s.failed()).isEqualTo(1);
            assertThat(s.failures()).singleElement().asString().contains("kilit 1 sn");
            holder.rollback();
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS it_guard_lock");
        }
    }

    /** Kaynaktaki tek-literal {@code patch("…")} çağrıları ve NOC yamalarının indeks dizeleri. */
    private static List<String> everyBootStatements() throws IOException {
        List<String> out = new ArrayList<>();
        Pattern literalPatch = Pattern.compile("patch\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)");
        Pattern ddlString = Pattern.compile("\"(CREATE (?:UNIQUE )?INDEX IF NOT EXISTS [^\"]+)\"");
        String scheduler = Files.readString(Path.of("src/main/java/com/sitemonitor/service/SchedulerService.java"), StandardCharsets.UTF_8);
        Matcher m = literalPatch.matcher(scheduler);
        while (m.find()) out.add(m.group(1));
        for (String f : List.of("src/main/java/com/sitemonitor/service/noc/NocSchemaPatches.java",
                "src/main/java/com/sitemonitor/service/noc/NocCallLogSchemaPatch.java")) {
            Matcher d = ddlString.matcher(Files.readString(Path.of(f), StandardCharsets.UTF_8));
            while (d.find()) out.add(d.group(1));
        }
        // Döngüde birleştirilen autovacuum yaması: tablo listesinden biri yeter (aynı ifade).
        out.add("ALTER TABLE port_checks SET (autovacuum_vacuum_scale_factor = 0.02, autovacuum_vacuum_threshold = 5000, "
                + "autovacuum_analyze_scale_factor = 0.02, autovacuum_analyze_threshold = 5000)");
        return out.stream().filter(SchemaDdlGuardPostgresTest::runsOnEveryBoot).toList();
    }

    /** Değişiklik yerinde olsa bile tabloya kilit alan biçimler (kolon ekleme / tablo oluşturma zaten koşullu). */
    private static boolean runsOnEveryBoot(String ddl) {
        String u = ddl.trim().toUpperCase(Locale.ROOT);
        if (u.startsWith("ALTER TABLE")) return !u.contains(" ADD COLUMN ");
        return u.startsWith("CREATE INDEX") || u.startsWith("CREATE UNIQUE INDEX");
    }

    @Test
    @DisplayName("KAPI: açılıştan sonra her açılışta koşan yamaların HEPSİ 'zaten yerinde' — normal açılış tabloya kilit almaz")
    void everyUnconditionalPatch_isInPlaceAfterBoot() throws IOException {
        PostgresIt.app().secondBoot();   // şema iki açılışla tam kurulu
        SchemaDdlGuard g = new SchemaDdlGuard(jdbc());
        List<String> stmts = everyBootStatements();
        assertThat(stmts).as("kaynak taraması koşulsuz yamaları bulmalı").hasSizeGreaterThan(50);
        List<String> notInPlace = stmts.stream().filter(d -> !g.alreadyInPlace(d)).toList();
        assertThat(notInPlace)
                .as("Bu yamalar her açılışta tabloya kilit alır: SchemaDdlGuard'a tanıyıcı ekleyin ya da yamayı koşullu yazın")
                .isEmpty();
    }
}

package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kaynak kapısı (2026-10-11): SecretCipher ile yazılan her kalıcı değer {@link SecretRotationService#LOCATIONS}'ta
 * olmalı — yoksa anahtar döndürmede o değer eski anahtarda kalır ve önceki anahtar kaldırılınca sessizce okunamaz olur.
 *
 * <ol>
 *   <li>Modeldeki her {@code @Column(name = "…_enc")} kolonu (tablo adıyla) bir konumdur.</li>
 *   <li>{@code encrypt(} çağıran her üretim dosyası aşağıdaki listededir. Yeni bir dosya şifreli değer yazıyorsa önce
 *       {@code LOCATIONS}'a konumunu ekleyin, sonra dosyayı buraya ekleyin.</li>
 * </ol>
 */
class SecretLocationsGateTest {

    private static final Path MAIN = Path.of("src/main/java/com/sitemonitor");

    /** Şifreli değer YAZAN dosyalar → yazdıkları konumlar (LOCATIONS id'leri). */
    private static final Set<String> ENCRYPT_WRITERS = Set.of(
            "controller/MonitoringController.java",     // http_basic_auth, http_headers, pagespeed_*, scripted_env (+ sürüm kopyası)
            "controller/UserPushController.java",       // push_headers
            "service/KeywordHeaderSecrets.java",        // keyword_headers
            "service/LdapSettingsService.java",         // ldap_bind_password
            "service/SmtpSettingsService.java",         // smtp_password
            "service/SecretCipher.java",                // tanım
            "service/SecretRotationService.java");      // döndürmenin kendisi

    private static final Pattern TABLE = Pattern.compile("@Table\\(\\s*name\\s*=\\s*\"([a-z_]+)\"");
    private static final Pattern ENC_COLUMN = Pattern.compile("@Column\\(\\s*name\\s*=\\s*\"([a-z_]+_enc)\"");
    private static final Pattern ENCRYPT_CALL = Pattern.compile("\\b(?:secretCipher|cipher)\\.encrypt\\(");

    @Test
    @DisplayName("her *_enc kolonu döndürme konumlarında")
    void everyEncColumnIsALocation() throws IOException {
        Set<String> known = new TreeSet<>();
        for (SecretRotationService.Location l : SecretRotationService.LOCATIONS) known.add(l.table() + "." + l.column());
        List<String> missing = new ArrayList<>();
        int seen = 0;
        try (Stream<Path> files = Files.list(MAIN.resolve("model"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Matcher t = TABLE.matcher(src);
                if (!t.find()) continue;
                Matcher c = ENC_COLUMN.matcher(src);
                while (c.find()) {
                    seen++;
                    String key = t.group(1) + "." + c.group(1);
                    if (!known.contains(key)) missing.add(key);
                }
            }
        }
        assertThat(seen).as("model taraması *_enc kolonu bulmalı").isGreaterThanOrEqualTo(7);
        assertThat(missing).as("SecretRotationService.LOCATIONS'a eklenmemiş şifreli kolonlar").isEmpty();
    }

    @Test
    @DisplayName("encrypt( çağıran her dosya bilinen bir konuma yazar")
    void everyEncryptCallerIsKnown() throws IOException {
        List<String> unknown = new ArrayList<>();
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (!ENCRYPT_CALL.matcher(src).find()) continue;
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                found.add(rel);
                if (!ENCRYPT_WRITERS.contains(rel)) unknown.add(rel);
            }
        }
        assertThat(found).as("tarama en az bilinen yazarları bulmalı").contains("service/LdapSettingsService.java",
                "controller/UserPushController.java");
        assertThat(unknown).as("şifreli değer yazan yeni dosya: konumunu SecretRotationService.LOCATIONS'a ekleyin").isEmpty();
    }
}

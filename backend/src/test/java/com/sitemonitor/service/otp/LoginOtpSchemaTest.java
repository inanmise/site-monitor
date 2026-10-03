package com.sitemonitor.service.otp;

import com.sitemonitor.model.LoginOtpChallenge;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kodla giriş şeması (2026-10-02): {@code applySchemaPatches} tabloyu AÇIKÇA kurar (ddl-auto'ya tek başına güvenilmez),
 * her varlık kolonu yamada yer alır (yeni kolon eklenip yama unutulursa kırmızı), sınır sorgularının dizinleri vardır ve
 * tablo saklama kataloğundadır ({@code created_at} yaşı, ayarlanabilir süre).
 */
class LoginOtpSchemaTest {

    private static String scheduler() throws IOException {
        return Files.readString(Path.of("src", "main", "java", "com", "sitemonitor", "service", "SchedulerService.java"),
                StandardCharsets.UTF_8);
    }

    private static String createTable() throws IOException {
        Matcher m = Pattern.compile("CREATE TABLE IF NOT EXISTS login_otp_challenges\\((.*?)\\n\\s*\\)\\s*\\n", Pattern.DOTALL)
                .matcher(scheduler());
        assertThat(m.find()).as("applySchemaPatches login_otp_challenges tablosunu kurmalı").isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("login_otp_challenges: her varlık kolonu yamada; kimlik VARCHAR(36) PK; KOD kolonu YOK (yalnız code_hmac)")
    void tableMatchesEntity() throws IOException {
        String ddl = createTable();
        assertThat(ddl).contains("id VARCHAR(36) PRIMARY KEY").contains("code_hmac VARCHAR(64) NOT NULL");
        for (Field f : LoginOtpChallenge.class.getDeclaredFields()) {
            Column c = f.getAnnotation(Column.class);
            if (c == null || c.name().isEmpty()) continue;
            assertThat(ddl).as("%s kolonu yamada yok", c.name()).containsPattern("(?m)^\\s*" + c.name() + "\\s");
            assertThat(c.name()).as("düz kod kolonu olamaz").isNotEqualTo("code");
        }
    }

    @Test
    @DisplayName("sınır sorgularının dizinleri: (ip, created_at), (username, created_at), (username, last_failed_at), created_at")
    void indexes() throws IOException {
        String src = scheduler();
        assertThat(src).contains("ON login_otp_challenges(ip, created_at)")
                .contains("ON login_otp_challenges(username, created_at)")
                .contains("ON login_otp_challenges(username, last_failed_at)")
                .contains("ON login_otp_challenges(created_at)");
        assertThat(src.indexOf("idx_otp_ip_created")).isGreaterThan(src.indexOf("CREATE TABLE IF NOT EXISTS login_otp_challenges("));
    }

    @Test
    @DisplayName("saklama: login-otp-challenges politikası created_at yaşıyla, ayarlanabilir (vars. 30 gün)")
    void retentionPolicy() {
        RetentionPolicy p = RetentionCatalog.byId("login-otp-challenges").orElseThrow();
        assertThat(p.table()).isEqualTo("login_otp_challenges");
        assertThat(p.settingKey()).isEqualTo("site.monitor.login.otp.retention-days");
        assertThat(p.deleteSql()).contains("DELETE FROM login_otp_challenges").contains("created_at < ?");
        assertThat(RetentionCatalog.settingDefaults()).containsEntry("site.monitor.login.otp.retention-days", 30);
    }
}

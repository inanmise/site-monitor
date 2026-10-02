package com.sitemonitor.service;

import com.sitemonitor.model.SystemMaintenanceSuppression;
import com.sitemonitor.model.SystemMaintenanceWindow;
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
 * Sistem Bakım Modu şema yaması (2026-10-02) — {@code applySchemaPatches} iki tabloyu AÇIKÇA kurar (ddl-auto'ya tek
 * başına güvenilmez) ve her varlık kolonu yamadaki CREATE TABLE'da yer alır (sürüklenme kapısı: yeni kolon eklenip yama
 * unutulursa kırmızı). Telafi listesinin tekilliği UNIQUE kısıtıyla.
 */
class SystemMaintenanceSchemaPatchTest {

    private static String createTable(String table) throws IOException {
        String src = Files.readString(Path.of("src", "main", "java", "com", "sitemonitor", "service", "SchedulerService.java"),
                StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("CREATE TABLE IF NOT EXISTS " + table + "\\((.*?)\\n\\s*\\)\\s*\\n", Pattern.DOTALL).matcher(src);
        assertThat(m.find()).as("applySchemaPatches %s tablosunu kurmalı", table).isTrue();
        return m.group(1);
    }

    private static void assertColumns(Class<?> entity, String ddl) {
        for (Field f : entity.getDeclaredFields()) {
            Column c = f.getAnnotation(Column.class);
            if (c == null || c.name().isEmpty()) continue;
            assertThat(ddl).as("%s.%s kolonu yamada yok", entity.getSimpleName(), c.name())
                    .containsPattern("(?m)^\\s*" + c.name() + "\\s");
        }
    }

    @Test
    @DisplayName("system_maintenance_windows: her varlık kolonu yamada")
    void windowsTable() throws IOException {
        String ddl = createTable("system_maintenance_windows");
        assertThat(ddl).contains("id BIGSERIAL PRIMARY KEY");
        assertColumns(SystemMaintenanceWindow.class, ddl);
    }

    @Test
    @DisplayName("bitiş e-postası kolonları (2026-10-02): eski veritabanı için ALTER yamaları; email_on_end varsayılanı TRUE")
    void endMailColumns_alterPatches() throws IOException {
        String src = Files.readString(Path.of("src", "main", "java", "com", "sitemonitor", "service", "SchedulerService.java"),
                StandardCharsets.UTF_8);
        assertThat(src).contains("patch(\"ALTER TABLE system_maintenance_windows ADD COLUMN email_on_end BOOLEAN DEFAULT TRUE\")")
                .contains("patch(\"ALTER TABLE system_maintenance_windows ADD COLUMN end_mail_at VARCHAR(30)\")")
                .contains("patch(\"ALTER TABLE system_maintenance_windows ADD COLUMN end_mail_status VARCHAR(200)\")")
                .contains("patch(\"ALTER TABLE system_maintenance_windows ADD COLUMN end_mail_count INTEGER\")");
        // ALTER'lar tabloyu kuran yamadan SONRA koşar (yoksa yeni kurulumda "tablo yok" hatası)
        assertThat(src.indexOf("ADD COLUMN email_on_end"))
                .isGreaterThan(src.indexOf("CREATE TABLE IF NOT EXISTS system_maintenance_windows("));
        assertThat(createTable("system_maintenance_windows")).contains("email_on_end BOOLEAN DEFAULT TRUE");
    }

    @Test
    @DisplayName("system_maintenance_suppressions: her varlık kolonu yamada + (bakım, alarm) UNIQUE")
    void suppressionsTable() throws IOException {
        String ddl = createTable("system_maintenance_suppressions");
        assertColumns(SystemMaintenanceSuppression.class, ddl);
        assertThat(ddl).contains("CONSTRAINT uq_sms_window_alert UNIQUE (window_id, alert_event_id)");
    }
}

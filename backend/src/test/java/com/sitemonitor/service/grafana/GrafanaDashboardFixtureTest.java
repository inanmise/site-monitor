package com.sitemonitor.service.grafana;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ön yüz e2e örneği güncel (2026-10-18): {@code frontend/e2e/fixtures/grafana-dashboard.sample.json} — Playwright'ın
 * "JSON indir" testi bu dosyayı indirme yanıtı olarak sunar ve İNEN dosyayı ayrıştırır ({@code __inputs}, takım / tür
 * değişkenleri, panel sayısı, boş başlık yok). Dosya üreticinin GERÇEK çıktısıdır; üretici değişince yeniden yazılmalı:
 * {@code mvn -o test -Dtest=GrafanaDashboardFixtureTest -Dgrafana.writeFixture=true} (aynı değişiklikte
 * {@code DASHBOARD_REVISION} ve içerik özeti de güncellenir — {@link GrafanaDashboardRevisionGateTest}).
 */
class GrafanaDashboardFixtureTest {

    static final Path FIXTURE = Path.of("..", "frontend", "e2e", "fixtures", "grafana-dashboard.sample.json");

    @Test
    @DisplayName("e2e pano örneği üreticinin güncel çıktısı")
    void fixtureIsCurrent() throws IOException {
        byte[] json = GrafanaDashboardGenerator.toJson(
                GrafanaDashboardGenerator.generate(GrafanaDashboardRevisionGateTest.fixture()).dashboard(), true);
        String expected = new String(json, StandardCharsets.UTF_8).replace("\r\n", "\n") + "\n";
        if (Boolean.getBoolean("grafana.writeFixture")) {
            Files.createDirectories(FIXTURE.getParent());
            Files.writeString(FIXTURE, expected, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(FIXTURE)).as("örnek yok: " + FIXTURE + " — -Dgrafana.writeFixture=true ile üretin").isTrue();
        String actual = Files.readString(FIXTURE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(actual)
                .as("e2e pano örneği bayat — mvn -o test -Dtest=GrafanaDashboardFixtureTest -Dgrafana.writeFixture=true ile yeniden yazın")
                .isEqualTo(expected);
    }
}

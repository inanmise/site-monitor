package com.sitemonitor.service.grafana;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * KAPI — Grafana panosu içeriği revizyonsuz değişemez (2026-10-18, kullanıcı: "her yeni sürümde panoda ekleme / değişiklik
 * gerekiyorsa Grafana JSON'unu doğru güncelle, asla unutma").
 *
 * <p>Sabit girdilerle (tüm bölümler, izleme başına açık, TR, örnek adres + eşleyici, sabit sürüm) üretilen panonun SHA-256
 * özeti {@code src/test/resources/grafana/dashboard-content.sha256} dosyasındaki {@code revision=N sha256=…} satırıyla
 * karşılaştırılır. Panel / sorgu / değişken değişip {@link GrafanaDashboardGenerator#DASHBOARD_REVISION} artırılmadıysa
 * kırmızıdır — içe aktarılan panolar hangi revizyonda olduklarını açıklamalarından söyler, ekipler yeni JSON'u ancak revizyon
 * değişince yeniden içe aktarmaları gerektiğini anlar.
 *
 * <p>Ne yapmalı (mesaj da söyler): içerik bilerek değiştiyse {@code DASHBOARD_REVISION}'ı bir artır ve dosyaya mesajdaki yeni
 * satırı yaz; değişmemesi gerekiyorsa üreticideki istenmeyen farkı geri al.
 */
class GrafanaDashboardRevisionGateTest {

    static final Path FILE = Path.of("src", "test", "resources", "grafana", "dashboard-content.sha256");
    static final Pattern LINE = Pattern.compile("revision=(\\d+)\\s+sha256=([0-9a-f]{64})");

    /** Özetin girdisi — SiteMonitor sürümü sabit (her sürümde değişen açıklama özeti oynatmasın). */
    static GrafanaDashboardOptions fixture() {
        return new GrafanaDashboardOptions("", GrafanaDashboardSettings.DEFAULT_UID, "https://sitemonitor.example.org",
                "namespace=\"site-monitor-prod\"", GrafanaDashboardSettings.DEFAULT_TIME_FROM, GrafanaDashboardSettings.DEFAULT_REFRESH,
                "tr", GrafanaDashboardGenerator.SECTIONS, true, "", GrafanaDashboardSettings.DEFAULT_TOP_N, "0.0.0-gate");
    }

    @Test
    @DisplayName("pano içeriği değiştiyse DASHBOARD_REVISION artırılmış ve özet dosyası güncellenmiş olmalı")
    void contentChangeRequiresRevisionBump() throws IOException {
        String hash = GrafanaDashboardGenerator.contentHash(GrafanaDashboardGenerator.generate(fixture()).dashboard());
        int revision = GrafanaDashboardGenerator.DASHBOARD_REVISION;
        String expectedLine = "revision=" + revision + " sha256=" + hash;
        if (!Files.exists(FILE)) {
            fail("Özet dosyası yok: " + FILE + " — içine şu satırı yazın:\n" + expectedLine);
        }
        String stored = Files.readString(FILE, StandardCharsets.UTF_8).trim();
        Matcher m = LINE.matcher(stored);
        if (!m.find()) fail("Özet dosyası biçimi bozuk (beklenen 'revision=N sha256=<64 hex>'): " + stored);
        int storedRevision = Integer.parseInt(m.group(1));
        String storedHash = m.group(2);
        if (storedHash.equals(hash) && storedRevision == revision) return;
        if (!storedHash.equals(hash) && storedRevision == revision) {
            fail("Grafana panosunun içeriği değişti ama DASHBOARD_REVISION (" + revision + ") artırılmadı.\n"
                    + "Yapılacak: GrafanaDashboardGenerator.DASHBOARD_REVISION = " + (revision + 1) + " yapın, sonra bu testi yeniden "
                    + "koşup verdiği satırı " + FILE + " dosyasına yazın. Ekipler yeni JSON'u Ayarlar → Platform → Grafana panosu'ndan "
                    + "indirip Grafana'ya yeniden içe aktarmalı (aynı uid → üzerine yazar). Değişiklik istenmeden olduysa üreticideki farkı "
                    + "geri alın.");
        }
        fail("Özet dosyası güncel değil — " + FILE + " içeriğini şu satırla değiştirin:\n" + expectedLine
                + "\n(kayıtlı: " + stored + ")");
    }

    @Test
    @DisplayName("özet kararlı: aynı girdi iki kez aynı özeti verir; SiteMonitor sürümü dışında her ayar özeti değiştirir")
    void hashIsStableAndSensitive() {
        String a = GrafanaDashboardGenerator.contentHash(GrafanaDashboardGenerator.generate(fixture()).dashboard());
        String b = GrafanaDashboardGenerator.contentHash(GrafanaDashboardGenerator.generate(fixture()).dashboard());
        assertThat(a).isEqualTo(b);
        GrafanaDashboardOptions other = new GrafanaDashboardOptions("", GrafanaDashboardSettings.DEFAULT_UID, "https://sitemonitor.example.org",
                "namespace=\"site-monitor-prod\"", "now-7d", GrafanaDashboardSettings.DEFAULT_REFRESH, "tr",
                GrafanaDashboardGenerator.SECTIONS, true, "", GrafanaDashboardSettings.DEFAULT_TOP_N, "0.0.0-gate");
        assertThat(GrafanaDashboardGenerator.contentHash(GrafanaDashboardGenerator.generate(other).dashboard())).isNotEqualTo(a);
    }
}

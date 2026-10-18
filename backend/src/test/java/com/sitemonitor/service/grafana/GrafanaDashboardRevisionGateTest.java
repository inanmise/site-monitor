package com.sitemonitor.service.grafana;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * KAPI — Grafana panosu içeriği revizyonsuz değişemez (2026-10-18, kullanıcı: "her yeni sürümde panoda ekleme / değişiklik
 * gerekiyorsa Grafana JSON'unu doğru güncelle, asla unutma").
 *
 * <p>{@code src/test/resources/grafana/dashboard-content.sha256} bir EKLEMELİ günlüktür: her satır bir revizyonun içerik
 * özetlerini sabitler ({@code revision=N tr=… en=… tr_no_monitor=…} — Türkçe ve İngilizce pano, izleme başına kapalı
 * değişke). Kurallar:
 * <ul>
 *   <li>Son satırın revizyonu {@link GrafanaDashboardGenerator#DASHBOARD_REVISION}'a eşittir ve özetleri bugünkü üretimle aynıdır.</li>
 *   <li>İçerik değişip revizyon aynı kaldıysa kırmızı — mesaj YENİ ÖZET YAZDIRMAZ, yalnız "revizyonu artır" der (2026-10-18,
 *       bağımsız inceleme: eski kapı özeti yazdırıyordu; satırı yeni özetle değiştirip revizyonu artırmadan yeşile dönmek
 *       mümkündü). Revizyon artırılınca kapı EKLENECEK satırı yazdırır.</li>
 *   <li>Revizyonlar artan sırada ve tekil; revizyon artıp içerik aynı kaldıysa kırmızı (gereksiz yeniden içe aktarma).</li>
 * </ul>
 */
class GrafanaDashboardRevisionGateTest {

    static final Path FILE = Path.of("src", "test", "resources", "grafana", "dashboard-content.sha256");
    static final Pattern LINE = Pattern.compile("^revision=(\\d+)((?:\\s+[a-z_]+=[0-9a-f]{64})+)\\s*$");
    static final Pattern PAIR = Pattern.compile("([a-z_]+)=([0-9a-f]{64})");

    /** Özetin girdisi — SiteMonitor sürümü sabit (her sürümde değişen açıklama özeti oynatmasın). */
    static GrafanaDashboardOptions fixture() {
        return fixture("tr", true);
    }

    static GrafanaDashboardOptions fixture(String lang, boolean perMonitor) {
        return new GrafanaDashboardOptions("", GrafanaDashboardSettings.DEFAULT_UID, "https://sitemonitor.example.org",
                "namespace=\"site-monitor-prod\"", GrafanaDashboardSettings.DEFAULT_TIME_FROM, GrafanaDashboardSettings.DEFAULT_REFRESH,
                lang, GrafanaDashboardGenerator.SECTIONS, perMonitor, "", GrafanaDashboardSettings.DEFAULT_TOP_N, "0.0.0-gate");
    }

    /** Bugünkü üretimin değişke → özet haritası. */
    static Map<String, String> currentHashes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tr", hash(fixture("tr", true)));
        m.put("en", hash(fixture("en", true)));
        m.put("tr_no_monitor", hash(fixture("tr", false)));
        return m;
    }

    static String hash(GrafanaDashboardOptions o) {
        return GrafanaDashboardGenerator.contentHash(GrafanaDashboardGenerator.generate(o).dashboard());
    }

    record Entry(int revision, Map<String, String> hashes) {}

    static List<Entry> stored() throws IOException {
        List<Entry> out = new ArrayList<>();
        if (!Files.exists(FILE)) return out;
        for (String raw : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) fail("Özet dosyasında bozuk satır (beklenen 'revision=N ad=<64 hex> …'): " + line);
            Map<String, String> h = new LinkedHashMap<>();
            Matcher p = PAIR.matcher(m.group(2));
            while (p.find()) h.put(p.group(1), p.group(2));
            out.add(new Entry(Integer.parseInt(m.group(1)), h));
        }
        return out;
    }

    static String lineFor(int revision, Map<String, String> hashes) {
        StringBuilder b = new StringBuilder("revision=").append(revision);
        for (Map.Entry<String, String> e : hashes.entrySet()) b.append(' ').append(e.getKey()).append('=').append(e.getValue());
        return b.toString();
    }

    /** Kapı kararı: sorun yoksa null, varsa kullanıcıya gösterilecek ileti. */
    static String problem(int revision, Map<String, String> now, List<Entry> log) {
        if (log.isEmpty()) return "Özet günlüğü yok ya da boş: " + FILE + " — şu satırı EKLEYİN:\n" + lineFor(revision, now);
        for (int i = 1; i < log.size(); i++) {
            if (log.get(i).revision() <= log.get(i - 1).revision()) {
                return "Özet günlüğünde revizyonlar artan sırada ve tekil olmalı (" + log.get(i - 1).revision() + " → " + log.get(i).revision() + ").";
            }
        }
        Entry last = log.get(log.size() - 1);
        if (revision < last.revision()) {
            return "DASHBOARD_REVISION (" + revision + ") günlükteki son revizyondan (" + last.revision() + ") küçük — revizyon geri alınamaz.";
        }
        if (revision > last.revision()) {
            if (last.hashes().equals(now)) {
                return "DASHBOARD_REVISION " + revision + "'e artırıldı ama pano içeriği revizyon " + last.revision()
                        + " ile aynı — gereksiz artış (ekipler boşuna yeniden içe aktarır). Artışı geri alın.";
            }
            return "Revizyon " + revision + " için özet satırı yok. " + FILE + " dosyasının SONUNA şu satırı EKLEYİN (var olan satırları "
                    + "değiştirmeyin):\n" + lineFor(revision, now)
                    + "\n(e2e örneği: -Dtest=GrafanaDashboardFixtureTest -Dgrafana.writeFixture=true). Ekipler yeni JSON'u Ayarlar → "
                    + "Platform → Grafana panosu'ndan indirip aynı UID ile yeniden içe aktarmalı.";
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : now.entrySet()) {
            String stored = last.hashes().get(e.getKey());
            if (stored == null) {
                return "Revizyon " + revision + " satırında '" + e.getKey() + "' değişkesi yok — satır bu sürümün kapısıyla yazılmamış. "
                        + "DASHBOARD_REVISION'ı " + (revision + 1) + " yapın; kapı eklenecek satırı yazar.";
            }
            if (!stored.equals(e.getValue())) changed.add(e.getKey());
        }
        if (!changed.isEmpty()) {
            // Yeni özet BİLEREK yazdırılmaz: tek doğru yol revizyonu artırmaktır.
            return "Grafana panosunun içeriği değişti " + changed + " ama DASHBOARD_REVISION (" + revision + ") artırılmadı. "
                    + "GrafanaDashboardGenerator.DASHBOARD_REVISION = " + (revision + 1) + " yapın ve testi yeniden koşun — kapı "
                    + FILE + " dosyasına EKLENECEK satırı yazar. Değişiklik istenmeden olduysa üreticideki farkı geri alın.";
        }
        return null;
    }

    @Test
    @DisplayName("pano içeriği değiştiyse DASHBOARD_REVISION artırılmış ve EKLEMELİ özet günlüğüne yeni satır yazılmış olmalı")
    void contentChangeRequiresRevisionBump() throws IOException {
        String p = problem(GrafanaDashboardGenerator.DASHBOARD_REVISION, currentHashes(), stored());
        if (p != null) fail(p);
    }

    @Test
    @DisplayName("içerik değişip revizyon aynı kalırsa ileti yeni özeti YAZDIRMAZ (satırı yeni özetle değiştirip geçmek mümkün olmasın); "
            + "revizyon artınca eklenecek satırı yazar; gereksiz artış ve geri alma kırmızı")
    void decisionRules() {
        Map<String, String> oldH = Map.of("tr", "a".repeat(64), "en", "b".repeat(64), "tr_no_monitor", "c".repeat(64));
        Map<String, String> newH = new LinkedHashMap<>();
        newH.put("tr", "d".repeat(64));
        newH.put("en", "b".repeat(64));
        newH.put("tr_no_monitor", "c".repeat(64));
        List<Entry> log = List.of(new Entry(1, Map.of("tr", "f".repeat(64))), new Entry(2, oldH));
        String sameRevision = problem(2, newH, log);
        assertThat(sameRevision).contains("artırılmadı").contains("[tr]").doesNotContain("d".repeat(64));
        assertThat(problem(3, newH, log)).contains("revision=3 tr=" + "d".repeat(64)).contains("EKLEYİN");
        assertThat(problem(3, oldH, log)).contains("gereksiz artış");
        assertThat(problem(1, newH, log)).contains("geri alınamaz");
        assertThat(problem(2, oldH, log)).isNull();
        assertThat(problem(2, oldH, List.of(new Entry(2, oldH), new Entry(2, oldH)))).contains("artan sırada");
        assertThat(problem(2, newH, List.of(new Entry(2, Map.of("tr", "d".repeat(64)))))).contains("değişkesi yok");
    }

    @Test
    @DisplayName("özet kararlı ve duyarlı: aynı girdi aynı özet; dil, izleme başına ve her ayar özeti değiştirir")
    void hashIsStableAndSensitive() {
        Map<String, String> a = currentHashes();
        assertThat(currentHashes()).isEqualTo(a);
        assertThat(a.values()).doesNotHaveDuplicates();
        GrafanaDashboardOptions other = new GrafanaDashboardOptions("", GrafanaDashboardSettings.DEFAULT_UID, "https://sitemonitor.example.org",
                "namespace=\"site-monitor-prod\"", "now-7d", GrafanaDashboardSettings.DEFAULT_REFRESH, "tr",
                GrafanaDashboardGenerator.SECTIONS, true, "", GrafanaDashboardSettings.DEFAULT_TOP_N, "0.0.0-gate");
        assertThat(hash(other)).isNotEqualTo(a.get("tr"));
    }

    @Test
    @DisplayName("günlük biçimi: yorum satırları ve eski tek değişkeli satırlar okunur; bozuk satır kırmızı")
    void logFormat() {
        assertThat(LINE.matcher("revision=1 tr=" + "a".repeat(64)).matches()).isTrue();
        assertThat(LINE.matcher("revision=2 tr=" + "a".repeat(64) + " en=" + "b".repeat(64)).matches()).isTrue();
        assertThat(LINE.matcher("revision=2 sha256=" + "a".repeat(63)).matches()).isFalse();
        assertThat(LINE.matcher("revision=x tr=" + "a".repeat(64)).matches()).isFalse();
    }
}

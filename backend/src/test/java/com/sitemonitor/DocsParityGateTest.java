package com.sitemonitor;

import com.sitemonitor.service.grafana.GrafanaDashboardGenerator;
import com.sitemonitor.service.schema.RedundantIndexCleanup;
import com.sitemonitor.service.watchdog.WatchdogCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dokümantasyon eşliği kapısı (kapı incelemesi 2026-11-15): operatörün okuduğu belgeler koddaki kalıcı sabitlerin gerisinde
 * kalmasın. Belgeler deponun kökünde ({@code ../docs}, surefire çalışma dizini {@code backend/}).
 * <ul>
 *   <li>RUNBOOK "Kendi kendini izleme" tablosu {@link WatchdogCheck}'in HER anahtarını bir satırda adlandırır (yeni kontrol =
 *       yeni satır: ne zaman sorun, ilk bakılacak yer).</li>
 *   <li>Yayın kontrol listesi Grafana panosunun güncel revizyonunu ({@link GrafanaDashboardGenerator#DASHBOARD_REVISION})
 *       anar — operatör panoyu yeniden içe aktarması gerektiğini oradan öğrenir.</li>
 *   <li>Yayın kontrol listesinin geri alma bölümü gereksiz indeks temizliğini ({@link RedundantIndexCleanup}) anar — geri
 *       dönülen eski sürüm düşürülen indeksleri kilitleyen {@code CREATE INDEX} ile kurar.</li>
 * </ul>
 */
class DocsParityGateTest {

    private static final Path DOCS = Path.of("..", "docs");

    private static String read(String name) throws IOException {
        return Files.readString(DOCS.resolve(name), StandardCharsets.UTF_8);
    }

    /** Başlıktan bir sonraki aynı düzey başlığa kadar olan bölüm. */
    private static String section(String doc, String heading) {
        int a = doc.indexOf("\n" + heading + "\n");
        assertThat(a).as("bölüm bulunamadı: " + heading).isGreaterThanOrEqualTo(0);
        String level = heading.substring(0, heading.indexOf(' ') + 1);
        int b = doc.indexOf("\n" + level, a + heading.length() + 1);
        return b < 0 ? doc.substring(a) : doc.substring(a, b);
    }

    @Test
    @DisplayName("RUNBOOK 'Kendi kendini izleme' tablosu her WatchdogCheck anahtarı için bir satır içerir")
    void runbookWatchdogTableCoversEveryCheck() throws IOException {
        String sec = section(read("RUNBOOK.md"), "## Kendi kendini izleme");
        List<String> rows = sec.lines().filter(l -> l.startsWith("| ") && !l.startsWith("|---")).toList();
        List<String> missing = new ArrayList<>();
        for (WatchdogCheck c : WatchdogCheck.values()) {
            String tag = "`" + c.key() + "`";
            if (rows.stream().noneMatch(r -> r.contains(tag))) missing.add(c.key());
        }
        assertThat(missing).as("RUNBOOK → Kendi kendini izleme tablosunda satırı olmayan kontroller").isEmpty();
        assertThat(sec).as("giriş cümlesindeki kontrol sayısı").contains(WatchdogCheck.values().length + " kontrol");
    }

    @Test
    @DisplayName("PROD_DEPLOY_CHECKLIST Grafana panosunun güncel revizyonunu anar")
    void checklistNamesCurrentDashboardRevision() throws IOException {
        String doc = read("PROD_DEPLOY_CHECKLIST.md");
        int rev = GrafanaDashboardGenerator.DASHBOARD_REVISION;
        assertThat(doc.contains("revizyon " + rev) || doc.contains("revision " + rev))
                .as("kontrol listesi Grafana panosu revizyon %d'i anmıyor (DASHBOARD_REVISION)", rev).isTrue();
    }

    @Test
    @DisplayName("PROD_DEPLOY_CHECKLIST geri alma bölümü gereksiz indeks temizliğini (RedundantIndexCleanup) anar")
    void checklistRollbackMentionsRedundantIndexCleanup() throws IOException {
        String rollback = section(read("PROD_DEPLOY_CHECKLIST.md"), "## Geri alma");
        boolean named = rollback.contains("RedundantIndexCleanup") || rollback.contains("İndeks bakımı")
                || RedundantIndexCleanup.TARGETS.stream().anyMatch(t -> rollback.contains("`" + t.index() + "`"));
        assertThat(named).as("geri alma bölümü düşürülen indeksleri önce kurmayı anmıyor").isTrue();
        assertThat(rollback).as("işin işareti (geri almadan önce denetlenir)").contains(RedundantIndexCleanup.MARKER);
    }
}

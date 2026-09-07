package com.sitemonitor.service.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI: SİLİNECEK bir kayıt dosyaya yazılmaz.
 *
 * <p><b>Neden var.</b> Gece temizliği, {@code audit_log} satırlarını DB'den silmeden önce
 * {@code logs/audit-archive/*.jsonl} altına arşivliyordu. Bu dizin {@code /app} içindeydi; pod'da
 * mount edilmiş volume ise yalnız {@code /tmp} ve {@code /var/log} ve ikisi de {@code emptyDir}.
 * Yani arşiv dosyaları konteynerin yazılabilir katmanında duruyor ve bir sonraki yeniden
 * başlatmada yok oluyordu. Bu proje günde birkaç kez sürüm çıkarıyor (her rollout = yeni pod),
 * dolayısıyla arşiv pratikte hiç yaşamıyordu — ama DB'den silme KALICIYDI.
 *
 * <p>Sonuç, "arşivlendi" diyen bir adımın ardından kaydın gerçekten kaybolmasıydı: koruma
 * görüntüsü veren, korumayan bir mekanizma. 2026-09'da kullanıcı kararıyla kaldırıldı —
 * saklama süresi artık YALNIZ {@code site.monitor.audit.retention-days} ile yönetiliyor.
 *
 * <p><b>Bu kapı neyi engelliyor.</b> Aynı fikrin geri gelmesini: temizlik/saklama yolunda dosya
 * yazımı. Kalıcı bir arşiv gerekiyorsa çözüm kalıcı bir hedeftir (kalıcı disk, log toplayıcı ya
 * da ayrı bir tablo) — konteyner diski değil. Bu test kırılıyorsa önce o soruyu cevaplayın.
 *
 * <p>Kapsam bilinçli olarak dar: {@code SchedulerService} (gece temizliğinin evi) ve saklama
 * paketi. {@code AuditService.fallback-file} KAPSAM DIŞIDIR ve olması gerektiği gibi çalışır —
 * o, DB yazımı BAŞARISIZ olduğunda kaydı kurtarır; silinecek bir kaydı kopyalamaz.
 */
class RetentionNoFileArchiveTest {

    /** Dosyaya yazan API'ler — biri temizlik yolunda görünürse kapı kırılır. */
    private static final List<String> FILE_WRITERS = List.of(
            "newBufferedWriter", "Files.writeString", "Files.write(",
            "FileWriter", "FileOutputStream", "PrintWriter");

    private static final List<Path> SCOPE = List.of(
            Path.of("src/main/java/com/sitemonitor/service/SchedulerService.java"),
            Path.of("src/main/java/com/sitemonitor/service/retention"));

    @Test
    @DisplayName("SOZLESME: temizlik/saklama yolunda dosyaya kayit YAZILMAZ")
    void retentionPath_writesNoRecordsToDisk() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path root : SCOPE) {
            assertThat(Files.exists(root)).as("kapsam yolu bulunamadi: " + root).isTrue();
            List<Path> files;
            if (Files.isDirectory(root)) {
                try (Stream<Path> s = Files.walk(root)) {
                    files = s.filter(p -> p.toString().endsWith(".java")).toList();
                }
            } else {
                files = List.of(root);
            }

            for (Path f : files) {
                for (String line : Files.readString(f, StandardCharsets.UTF_8).split("\\R")) {
                    String t = line.trim();
                    // Yorumlar sayilmaz: kaldirma gerekcesi bu dosyalarda ANLATILIYOR.
                    if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
                    for (String w : FILE_WRITERS) {
                        if (t.contains(w)) offenders.add(f.getFileName() + ": " + t);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("Temizlik/saklama yolunda dosya yazimi. Silinecek kaydi diske kopyalamak, "
                  + "konteyner diski kalici olmadigi icin koruma DEGIL kayiptir; kalici bir hedef "
                  + "gerekiyorsa kalici disk/log toplayici/ayri tablo secilmeli.")
                .isEmpty();
    }

    @Test
    @DisplayName("Kaldirilan arsiv ayarlari geri gelmemis (katalogda audit.archive-* yok)")
    void archiveSettingsStayRemoved() {
        assertThat(com.sitemonitor.service.AppSettingsCatalog.ALL)
                .extracting(com.sitemonitor.service.AppSettingsCatalog.Setting::key)
                .as("silme oncesi arsivleme kaldirildi; ayarlari da geri gelmemeli")
                .noneMatch(k -> k.startsWith("site.monitor.audit.archive"));
    }
}

package com.sitemonitor.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kaynak kapısı (kapı incelemesi 2026-11-15): türetilmiş {@code deleteBy…} / {@code removeBy…} Spring Data'da HER satırı
 * varlık olarak yükleyip tek tek siler ({@code @Modifying} bunu değiştirmez). Kontrol / seri tablolarında (on binlerce satır)
 * bu, istek işleminde dev bellek ve satır başına bir {@code DELETE} demekti. Yeni bir silme metodu {@code @Query("DELETE …")}
 * (toplu) yazılır; yalnız küçük, üst kayda bağlı tablolar (birkaç satır) gerekçesiyle izin listesindedir. Liste yalnız
 * KÜÇÜLÜR.
 */
class CheckRepositoryBulkDeleteGateTest {

    private static final Path REPO_DIR = Path.of("src/main/java/com/sitemonitor/repository");

    /** Bildirim: dönüş türü + silme adı (çağrıyı değil bildirimi yakalar). */
    private static final Pattern DECL = Pattern.compile(
            "\\b(?:int|long|void|Integer|Long)\\s+((?:delete|remove)By\\w*)\\s*\\(");

    /** Bilinçli türetilmiş silmeler: dosya#metot → gerekçe (küçük, üst kayda bağlı tablolar). */
    private static final Map<String, String> ALLOWED = Map.ofEntries(
            Map.entry("IssueReportCommentRepository#deleteByReportId", "bildirim başına birkaç yorum"),
            Map.entry("LoginIssueMailLogRepository#deleteByReportId", "bildirim başına birkaç posta izi"),
            Map.entry("LoginIssueReportImageRepository#deleteByReportId", "bildirim başına en çok 5 görsel"),
            Map.entry("ManualCertificateVersionRepository#deleteByIdAndInventoryIdAndCurrentFalse", "tek satır"),
            Map.entry("MonitorBaselineConfigRepository#deleteByMonitorTypeAndMonitorId", "izleme başına tek satır"),
            Map.entry("NocTeamCallEntryRepository#deleteByTeamId", "takım başına arama listesi (onlarca)"),
            Map.entry("RememberMeTokenRepository#deleteByToken", "tek satır"),
            Map.entry("RememberMeTokenRepository#deleteByUsername", "kullanıcı başına birkaç belirteç"),
            Map.entry("RememberMeTokenRepository#deleteByIdAndUsername", "tek satır"),
            Map.entry("ScriptedDraftRepository#deleteByOwnerAndMonitorKey", "tek taslak"),
            Map.entry("ScriptedDraftRepository#deleteByMonitorId", "izleme başına birkaç taslak"),
            Map.entry("ScriptedTemplateVersionRepository#deleteByTemplateId", "şablon başına sürümler (sınırlı)"),
            Map.entry("UserTeamSourceRepository#deleteByUserId", "kullanıcı başına birkaç takım"),
            Map.entry("UserTeamSourceRepository#deleteByUserIdAndTeamIdIn", "kullanıcı başına birkaç takım"),
            Map.entry("WeeklyReportCommentRepository#deleteByReportId", "rapor başına yorumlar"),
            Map.entry("WeeklyReportImageRepository#deleteByReportId", "rapor başına görseller"),
            Map.entry("WeeklyReportMailRepository#deleteByReportId", "rapor başına posta izleri"));

    static List<String> derivedDeletes(String file, String src) {
        List<String> out = new ArrayList<>();
        Matcher m = DECL.matcher(src);
        while (m.find()) {
            int start = Math.max(src.lastIndexOf(';', m.start()), src.lastIndexOf("*/", m.start()));
            String head = src.substring(Math.max(0, start), m.start());
            if (!head.contains("Query(")) out.add(file.replace(".java", "") + "#" + m.group(1));
        }
        return out;
    }

    @Test
    @DisplayName("türetilmiş silme yalnız izin listesindeki küçük tablolarda; kontrol / seri depoları toplu @Query DELETE kullanır")
    void derivedDeletesOnlyOnSmallTables() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(REPO_DIR)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                found.addAll(derivedDeletes(f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        assertThat(found).as("türetilmiş deleteBy (her satırı yükler) — @Query(\"DELETE …\") yazın ya da gerekçeyle izin verin")
                .allMatch(ALLOWED::containsKey);
        assertThat(ALLOWED.keySet()).as("izin listesinde artık türetilmiş olmayan girdi — listeden çıkarın")
                .allMatch(found::contains);
        for (String k : List.of("DbQueryCheckRepository#deleteByMonitorId", "PageSpeedCheckRepository#deleteByMonitorId",
                "PageSpeedResourceRepository#deleteByMonitorId", "PageSpeedResourceRepository#deleteByMonitorIdAndKeepReason",
                "DbQueryAlertRowsRepository#deleteByMonitorId", "MonitorChangeLogRepository#deleteByCreatedAtBefore",
                "UserPushDeliveryRepository#deleteByCreatedAtBefore")) {
            assertThat(found).as(k).doesNotContain(k);
        }
    }

    @Test
    @DisplayName("kapı türetilmiş silmeyi yakalar, @Query'li toplu silmeyi ve varsayılan metottaki çağrıyı yakalamaz")
    void gateSelfTest() {
        String src = """
                public interface X extends JpaRepository<PortCheck, Long> {
                    /** {@code açıklama}. */
                    @Transactional
                    @Modifying
                    int deleteByMonitorId(Long monitorId);

                    @Transactional
                    @Modifying
                    @Query("DELETE FROM PortCheck c WHERE c.checkedAt < :cutoff")
                    int deleteByCheckedAtBefore(@Param("cutoff") String cutoff);

                    @Transactional
                    default void replace(Long id) {
                        deleteByMonitorId(id);
                    }
                }
                """;
        assertThat(derivedDeletes("X.java", src)).containsExactly("X#deleteByMonitorId");
    }
}

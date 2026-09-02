package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditEventCatalog} — olay türlerinin kanonik listesi ve KAPISI.
 *
 * <p>En kritik test {@link #everyEventTypeInCodeIsCatalogued()}: kodda yazılan ama katalogda
 * olmayan bir tür, arayüzde filtrelenemez ve TR/EN etiketi olmadan ham {@code SNAKE_CASE} olarak
 * görünür. Bu sürüklenme zaten yaşanmıştı — filtre listesi 158 türün yalnız 32'sini tanıyordu.
 */
class AuditEventCatalogTest {

    /** {@code recordAction("X", …)} / {@code recordSecurityEvent("X", …)} / … ilk argümanı. */
    private static final Pattern RECORD_LITERAL = Pattern.compile(
            "record(?:Action|SecurityEvent|SystemEvent|TokenAction)\\(\\s*\"([A-Z][A-Z0-9_]{2,})\"");

    private static final Path MAIN = Path.of("src", "main", "java", "com", "sitemonitor");

    @Test
    @DisplayName("katalog boş değil ve tekil (aynı tür iki kez yazılmamış)")
    void catalogIsNonEmptyAndUnique() {
        assertThat(AuditEventCatalog.TYPES).hasSizeGreaterThan(100);
        assertThat(AuditEventCatalog.TYPES).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("KAPI: kodda yazılan HER olay türü katalogda olmalı")
    void everyEventTypeInCodeIsCatalogued() throws IOException {
        Set<String> inCode = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = RECORD_LITERAL.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) inCode.add(m.group(1));
            }
        }

        assertThat(inCode).as("tarama hiçbir olay türü bulamadı — desen bozulmuş olabilir").isNotEmpty();

        List<String> missing = inCode.stream().filter(t -> !AuditEventCatalog.contains(t)).sorted().toList();
        assertThat(missing)
                .as("Katalogda OLMAYAN olay türleri yazılıyor. AuditEventCatalog.TYPES'a ekleyin; "
                  + "aksi halde bu türler arayüzde filtrelenemez ve etiketsiz kalır:%n%s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("kategori kuralı: özel önek genel önekten ÖNCE gelir")
    void categoryOf_specificBeforeGeneral() {
        // USER_PUSH_* bir entegrasyon ayarıdır, kullanıcı yönetimi değil.
        assertThat(AuditEventCatalog.categoryOf("USER_PUSH_SETTINGS")).isEqualTo(AuditEventCatalog.INTEGRATION);
        assertThat(AuditEventCatalog.categoryOf("USER_DELETE")).isEqualTo(AuditEventCatalog.USER);
        // CERT_INVENTORY_REPORT_* bir rapordur, sertifika olayı değil.
        assertThat(AuditEventCatalog.categoryOf("CERT_INVENTORY_REPORT_RUN")).isEqualTo(AuditEventCatalog.REPORT);
        assertThat(AuditEventCatalog.categoryOf("CERT_NOTE_ADD")).isEqualTo(AuditEventCatalog.CERTIFICATE);
    }

    @Test
    @DisplayName("güvenlik reddi kendi kategorisinde — nötr bir olay gibi görünmemeli")
    void categoryOf_deniedIsSecurity() {
        assertThat(AuditEventCatalog.categoryOf("ACCESS_DENIED")).isEqualTo(AuditEventCatalog.SECURITY);
        assertThat(AuditEventCatalog.categoryOf("CHANGE_LOG_DENIED")).isEqualTo(AuditEventCatalog.SECURITY);
        assertThat(AuditEventCatalog.categoryOf("CERT_HEALTH_DENIED")).isEqualTo(AuditEventCatalog.SECURITY);
    }

    @Test
    @DisplayName("bilinmeyen tür OTHER'a düşer — bilinen bir kovaya SESSİZCE gizlenmez")
    void categoryOf_unknownIsOther() {
        assertThat(AuditEventCatalog.categoryOf("BRAND_NEW_THING")).isEqualTo("OTHER");
        assertThat(AuditEventCatalog.categoryOf(null)).isEqualTo("OTHER");
        assertThat(AuditEventCatalog.categoryOf("")).isEqualTo("OTHER");
    }

    @Test
    @DisplayName("katalogdaki hiçbir tür OTHER'da kalmamalı (kural her türü tanımalı)")
    void everyCatalogTypeHasRealCategory() {
        List<String> uncategorised = AuditEventCatalog.TYPES.stream()
                .filter(t -> "OTHER".equals(AuditEventCatalog.categoryOf(t)))
                .sorted().toList();

        assertThat(uncategorised)
                .as("Bu türler hiçbir kategori kuralına uymuyor — categoryOf'a kural ekleyin:%n%s", uncategorised)
                .isEmpty();
    }

    @Test
    @DisplayName("all(): her tür kategorisiyle döner, kategori sırası tanımlı")
    void all_pairsTypeWithCategory() {
        List<AuditEventCatalog.Event> all = AuditEventCatalog.all();
        assertThat(all).hasSameSizeAs(AuditEventCatalog.TYPES);
        assertThat(all).allSatisfy(e -> assertThat(e.category()).isNotBlank());
        assertThat(AuditEventCatalog.CATEGORY_ORDER).contains(AuditEventCatalog.AUTH, AuditEventCatalog.SECURITY);
    }
}

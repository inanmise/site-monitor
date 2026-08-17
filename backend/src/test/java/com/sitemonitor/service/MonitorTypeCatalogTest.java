package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YENİ İZLEME TÜRÜ EKLEYENİ DURDURAN KAPI (backend tarafı).
 *
 * <p>Kanonik alarm tipi listesi elle yazılsaydı koruma, korumayı kuran kişinin dikkatine bağlı
 * olurdu — tam da kapatmaya çalıştığı hata sınıfı. Bu yüzden liste {@code EscalationService.java}
 * KAYNAĞINDAN ayrıştırılıyor: yeni bir {@code TYPE_*} sabiti eklendiği anda süit, tip katalogda
 * bir türe bağlanana kadar KIRMIZI kalır ve eksik tipin ADINI söyler.
 *
 * <p>Bu kapı yazılmadan önce iki tür sessizce düşmüştü ve ikisi de hiç hata vermiyordu:
 * <b>page</b> (PAGE_DOWN / PAGE_INTEGRITY) haftalık göstergelerde hiç sayılmıyordu, <b>scripted</b>
 * ise haftalık e-postada ham anahtarıyla yazılıyordu. Aynı hatanın üçüncü kez olmaması için.
 *
 * <p>Frontend'deki ikizi: {@code frontend/src/test/alertTypeMeta.test.jsx}.
 */
class MonitorTypeCatalogTest {

    private static final Path ESCALATION =
            Path.of("src/main/java/com/sitemonitor/service/EscalationService.java");

    /** Sertifika tipleri EscalationService'te sabit DEĞİL — kod içinde string literal. Donmuş küme. */
    private static final Set<String> CERT_TYPES = Set.of("EXPIRY", "CHAIN_BROKEN", "REVOKED", "MISMATCH");

    private static Set<String> canonicalTypesFromSource() throws IOException {
        String src = new String(Files.readAllBytes(ESCALATION), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("TYPE_\\w+\\s*=\\s*\"(\\w+)\"").matcher(src);
        Set<String> types = new LinkedHashSet<>();
        while (m.find()) types.add(m.group(1));
        assertThat(types)
                .as("EscalationService okundu ama TYPE_* bulunamadı — regex ya da yol bozulmuş, "
                        + "kapı sessizce devre dışı kalmış olurdu")
                .isNotEmpty();
        return types;
    }

    @Test
    @DisplayName("EscalationService'teki HER alarm tipi katalogda bir izleme türüne bağlı olmalı")
    void everyAlertTypeIsMappedToAMonitorType() throws IOException {
        List<String> unmapped = new ArrayList<>();
        for (String type : canonicalTypesFromSource()) {
            if (MonitorTypeCatalog.typeOfAlert(type) == null) unmapped.add(type);
        }
        assertThat(unmapped)
                .as("Katalogda bir türe bağlanmamış alarm tipleri: %s — MonitorTypeCatalog.ALERT_TYPES'a "
                        + "ekleyin, yoksa bu alarmlar haftalık göstergelerde ve kesinti PDF'inde SESSİZCE "
                        + "kaybolur", unmapped)
                .isEmpty();
    }

    @Test
    @DisplayName("Sertifika alarm tipleri katalogda 'cert' türüne bağlı")
    void certTypesAreMapped() {
        for (String type : CERT_TYPES) {
            assertThat(MonitorTypeCatalog.typeOfAlert(type))
                    .as("sertifika alarm tipi %s", type)
                    .isEqualTo("cert");
        }
    }

    @Test
    @DisplayName("Her türün etiketi ve sırası var — ham anahtar sızmaz")
    void everyTypeHasLabelAndOrder() {
        for (String type : MonitorTypeCatalog.ALERT_TYPES.keySet()) {
            assertThat(MonitorTypeCatalog.ORDER)
                    .as("tür %s sıralamada yok — PDF/e-postada hiç çizilmez", type)
                    .contains(type);
            assertThat(MonitorTypeCatalog.LABELS_TR)
                    .as("tür %s için Türkçe etiket yok — ham anahtar görünürdü", type)
                    .containsKey(type);
        }
        assertThat(MonitorTypeCatalog.ORDER)
                .as("sıralamada katalogda olmayan tür var")
                .allMatch(MonitorTypeCatalog.ALERT_TYPES::containsKey);
    }

    @Test
    @DisplayName("Bilinmeyen alarm tipi çökertmez, null döner (çağıran 'Diğer'e düşürür)")
    void unknownTypeDoesNotThrow() {
        assertThat(MonitorTypeCatalog.typeOfAlert("BOYLE_BIR_TIP_YOK")).isNull();
        assertThat(MonitorTypeCatalog.typeOfAlert(null)).isNull();
        assertThat(MonitorTypeCatalog.label("bilinmeyen")).isEqualTo("bilinmeyen");
    }
}

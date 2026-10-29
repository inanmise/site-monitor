package com.sitemonitor.service.checks;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.service.monitoring.ManualEvalInbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Çok pod Sürüm 3-3 P0 — dağıtık kontrol iş türleri ({@link CheckJobTypes}): adlar KALICI ve elle kontrol gelen kutusunun
 * {@code TYPE_*} değerleri + küme vade anahtarlarının öneki ile AYNI; hoşgörülü okuma ve kayıt denetimi; kira sabitleri.
 */
class CheckJobTypesTest {

    @Test
    @DisplayName("sekiz tür, kanonik sırada, ManualEvalInbox.TYPE_* ile birebir; liderde kalan türler (domain, uptime) listede YOK")
    void namesArePermanentAndEqualInboxTypes() {
        assertThat(CheckJobTypes.ALL).containsExactly("port", "ping", "http", "keyword", "page", "pagespeed", "dns", "scripted");
        assertThat(CheckJobTypes.ALL).containsExactly(ManualEvalInbox.TYPE_PORT, ManualEvalInbox.TYPE_PING,
                ManualEvalInbox.TYPE_HTTP, ManualEvalInbox.TYPE_KEYWORD, ManualEvalInbox.TYPE_PAGE,
                ManualEvalInbox.TYPE_PAGESPEED, ManualEvalInbox.TYPE_DNS, ManualEvalInbox.TYPE_SCRIPTED);
        assertThat(CheckJobTypes.DEFAULT_CSV).isEqualTo("port,ping,http,keyword,page,pagespeed,dns,scripted");
        assertThat(CheckJobTypes.isKnown(ManualEvalInbox.TYPE_DOMAIN)).as("alan adı kaydı liderde (U-5)").isFalse();
        assertThat(CheckJobTypes.isKnown("uptime")).isFalse();
        assertThat(CheckJobTypes.isKnown(null)).isFalse();
        assertThat(CheckJobTypes.isKnown("PORT")).as("birebir küçük harf").isFalse();
    }

    @Test
    @DisplayName("tür adları SchedulerService'in klasik vade anahtarlarının öneki (checkDue(\"<tür>\", …)) — iş ve vade aynı adı taşır")
    void typesAreTheScheduleKeyPrefixes() throws IOException {
        String ss = Files.readString(Path.of("src/main/java/com/sitemonitor/service/SchedulerService.java"),
                StandardCharsets.UTF_8);
        for (String t : CheckJobTypes.ALL) {
            // R3-3 FX: klasik gövdeler vade kapısını classicCheckDue ile geçer (checkDue + bekletilen dönem + klasik damga)
            assertThat(ss).as("klasik tarama vade anahtarı: " + t).contains("classicCheckDue(\"" + t + "\"");
        }
    }

    @Test
    @DisplayName("hoşgörülü okuma: kırpar, küçük harfe çevirir, bilinmeyeni / boşu atlar, tekrarı birleştirir, kanonik sıra; null / boş / çok uzun → boş")
    void parseLenient() {
        assertThat(CheckJobTypes.parseLenient(" DNS , port,,ping ,bilinmeyen, port ")).containsExactly("port", "ping", "dns");
        assertThat(CheckJobTypes.parseLenient(CheckJobTypes.DEFAULT_CSV)).containsExactlyElementsOf(CheckJobTypes.ALL);
        assertThat(CheckJobTypes.parseLenient(null)).isEmpty();
        assertThat(CheckJobTypes.parseLenient("  ")).isEmpty();
        assertThat(CheckJobTypes.parseLenient("domain,uptime")).isEmpty();
        assertThat(CheckJobTypes.parseLenient("port," + "x".repeat(CheckJobTypes.MAX_CSV_CHARS))).isEmpty();
        Set<String> s = CheckJobTypes.parseLenient("port");
        assertThatThrownBy(() -> s.add("ping")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(CheckJobTypes.canonical(List.of("scripted", "port", "x"))).containsExactly("port", "scripted");
    }

    @Test
    @DisplayName("kayıt denetimi: bilinen adlar (harf / boşluk serbest) geçer; bilinmeyen ad, boş liste, çok uzun metin → alan distributed_types")
    void validateSetting() {
        assertThatCode(() -> CheckJobTypes.validateSetting("port,ping")).doesNotThrowAnyException();
        assertThatCode(() -> CheckJobTypes.validateSetting(" PORT , Ping ,")).doesNotThrowAnyException();
        assertThatCode(() -> CheckJobTypes.validateSetting(CheckJobTypes.DEFAULT_CSV)).doesNotThrowAnyException();
        assertThatCode(() -> CheckJobTypes.validateSetting("")).as("boş = varsayılana dön").doesNotThrowAnyException();
        assertThatCode(() -> CheckJobTypes.validateSetting(null)).doesNotThrowAnyException();

        assertThatThrownBy(() -> CheckJobTypes.validateSetting("port,uptime"))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class)
                .hasMessageContaining("uptime")
                .satisfies(e -> assertThat(((GlobalExceptionHandler.FieldValidationException) e).getFields())
                        .containsOnlyKeys(CheckJobTypes.FIELD));
        assertThatThrownBy(() -> CheckJobTypes.validateSetting("domain"))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        assertThatThrownBy(() -> CheckJobTypes.validateSetting(" , ,"))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class)
                .satisfies(e -> assertThat(((GlobalExceptionHandler.FieldValidationException) e).getFields())
                        .containsOnlyKeys("distributed_types"));
        assertThatThrownBy(() -> CheckJobTypes.validateSetting("port," + "a".repeat(CheckJobTypes.MAX_CSV_CHARS)))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        // uzun bilinmeyen ad iletide kırpılır (ileti şişmez)
        assertThatThrownBy(() -> CheckJobTypes.validateSetting("x".repeat(200)))
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(400));
    }

    @Test
    @DisplayName("kira sabitleri (§3.4): 150 sn / k6 210 sn, işçi kesmesi kira − 10 sn, en çok 2 sahiplenme")
    void leaseConstants() {
        for (String t : CheckJobTypes.ALL) {
            long expected = CheckJobTypes.SCRIPTED.equals(t) ? 210_000L : 150_000L;
            assertThat(CheckJobTypes.leaseMs(t)).as(t).isEqualTo(expected);
            assertThat(CheckJobTypes.workerTimeoutMs(t)).as(t).isEqualTo(expected - 10_000L);
        }
        assertThat(CheckJobTypes.leaseMs("bilinmeyen")).isEqualTo(CheckJobTypes.LEASE_MS_DEFAULT);
        assertThat(CheckJobTypes.MAX_ATTEMPTS).isEqualTo(2);
        CheckJobRow row = new CheckJobRow(7L, "port", 42L, 1_000L, 1, 150_000L, "tok", 3_500L, 153_500L);
        assertThat(row.claimLagMs()).isEqualTo(2_500L);
        assertThat(row.workerTimeoutMs()).isEqualTo(140_000L);
        assertThat(new CheckJobRow(1L, "port", 1L, 5L, 1, 2_000L, "t", 1L, 2L).claimLagMs()).as("negatif değil").isZero();
        assertThat(new CheckJobRow(1L, "port", 1L, 5L, 1, 2_000L, "t", 1L, 2L).workerTimeoutMs()).isEqualTo(1_000L);
    }
}

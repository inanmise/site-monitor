package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuditDetail} — denetim {@code detail} alanının tek üreticisi.
 *
 * <p>Sözleşme tek cümle: <b>çıktı daima geçerli bir JSON nesnesidir.</b> Ekran tarafı buna
 * güvenerek ayrıştırıyor; bugüne kadar düz metin/bozuk JSON yazan çağrı yerleri yüzünden yazılan
 * ayrıntı kullanıcıya hiç ulaşmıyordu.
 */
class AuditDetailTest {

    @Test
    @DisplayName("anahtar/değer çiftleri JSON nesnesine çevrilir; tipler korunur")
    void of_buildsJsonObject() {
        String json = AuditDetail.of("host", "db-01", "port", 5432, "send_data", true);
        assertThat(json).isEqualTo("{\"host\":\"db-01\",\"port\":5432,\"send_data\":true}");
    }

    @Test
    @DisplayName("BOZUK JSON KAPISI: değerdeki tırnak kaçırılır (elle birleştirmenin ürettiği hata)")
    void of_escapesQuotes() {
        // AdminController:1686 elle birleştiriyordu: adında tırnak olan takım geçersiz JSON üretiyordu.
        String json = AuditDetail.of("name", "Takım \"A\"");
        assertThat(json).isEqualTo("{\"name\":\"Takım \\\"A\\\"\"}");
    }

    @Test
    @DisplayName("null değer YAZILIR — 'baktık, boştu' ile 'hiç bakmadık' aynı şey değildir")
    void of_keepsNulls() {
        assertThat(AuditDetail.of("locked_until_before", null))
                .isEqualTo("{\"locked_until_before\":null}");
    }

    @Test
    @DisplayName("hassas anahtar AuditDiff ile AYNI kuralla maskelenir (ikinci kara-liste yok)")
    void of_masksSensitiveKeys() {
        String json = AuditDetail.of("username", "alice", "password", "Hunter2");
        assertThat(json).contains("alice").doesNotContain("Hunter2").contains(AuditDiff.MASK);
    }

    @Test
    @DisplayName("değer gövdesindeki kimlik bilgisi de temizlenir")
    void of_scrubsCredentialBodies() {
        String json = AuditDetail.of("url", "https://hook.example.com/x?token=SECRET123");
        assertThat(json).doesNotContain("SECRET123").contains("hook.example.com");
    }

    @Test
    @DisplayName("argümansız çağrı geçerli boş nesne verir (asla null/çöp değil)")
    void of_emptyIsValidObject() {
        assertThat(AuditDetail.of()).isEqualTo("{}");
        assertThat(AuditDetail.ofMap(null)).isEqualTo("{}");
        assertThat(AuditDetail.ofMap(new LinkedHashMap<>())).isEqualTo("{}");
    }

    @Test
    @DisplayName("tek sayıda argüman PROGRAMLAMA hatasıdır — sessiz bozuk detay üretmez")
    void of_oddArgsThrows() {
        assertThatThrownBy(() -> AuditDetail.of("a", 1, "b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("çiftleri");
    }

    @Test
    @DisplayName("String olmayan anahtar reddedilir (dizilim kayması yakalanır)")
    void of_nonStringKeyThrows() {
        assertThatThrownBy(() -> AuditDetail.of(42, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ofMap: iç içe harita ve liste değerleri de JSON'a çevrilir")
    void ofMap_nestedValues() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("counts", Map.of("sent", 3));
        m.put("excluded", java.util.List.of("a@example.com", "b@example.com"));
        String json = AuditDetail.ofMap(m);

        assertThat(json).contains("\"counts\":{\"sent\":3}");
        assertThat(json).contains("\"excluded\":[\"a@example.com\",\"b@example.com\"]");
    }

    @Test
    @DisplayName("note(): serbest metin ayrıştırılabilir kabuğa sarılır")
    void note_wrapsFreeText() {
        assertThat(AuditDetail.note("test → ops@example.com"))
                .isEqualTo("{\"note\":\"test → ops@example.com\"}");
    }
}

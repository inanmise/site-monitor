package com.sitemonitor.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Denetim satırı JSON'a çevrildiğinde OTURUM KİMLİĞİ ÇIKMAMALI.
 *
 * <p>{@code /api/me/audit} ve admin denetim ucu satırları ENTITY olarak serileştiriyor. Hiçbir
 * arayüz {@code sessionId}'yi okumuyordu ama ağ yükünde taşınıyordu — kullanıcıya açık bir
 * sayfada gereksiz bir risk, üstelik üründe ekran görüntüsü yakalayan bir "sorun bildir" akışı
 * var ve hata bildirimleri bu yükü taşıyabilir.
 *
 * <p>Bu kapı olmadan biri {@code @JsonIgnore}'u kaldırır ve sızıntı sessizce geri gelir.
 */
class AuditLogSerializationTest {

    /**
     * URETIMDEKI ayarla AYNI mapper: Spring global olarak SNAKE_CASE kullaniyor
     * ({@code spring.jackson.property-naming-strategy}). Ciplak bir ObjectMapper camelCase
     * uretir ve test, gercekte gonderilen yuku DOGRULAMAMIS olurdu.
     */
    private static ObjectMapper mapper() {
        return new ObjectMapper().setPropertyNamingStrategy(
                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    }

    @Test
    @DisplayName("sessionId JSON'a GIRMEZ; tasinmasi gereken alanlar aynen kalir")
    void sessionIdIsNeverSerialised() throws Exception {
        AuditLog log = new AuditLog();
        log.setSessionId("GIZLI-OTURUM-KIMLIGI");
        log.setActor("n68753");
        log.setEventType("LOGIN");
        log.setIpAddress("10.1.2.3");
        log.setUserAgent("Mozilla/5.0 (Windows NT 10.0) Chrome/120");

        String json = mapper().writeValueAsString(log);

        assertThat(json).doesNotContain("GIZLI-OTURUM-KIMLIGI");
        assertThat(json).doesNotContain("sessionId");
        // Ekranin gercekten ihtiyac duydugu alanlar kaybolmadi.
        assertThat(json).contains("n68753").contains("LOGIN").contains("10.1.2.3");
    }

    @Test
    @DisplayName("ua_summary TUREV alan olarak yanita girer (arayuz ikinci ayristirici yazmasin)")
    void exposesDerivedUaSummary() throws Exception {
        AuditLog log = new AuditLog();
        log.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36");

        String json = mapper().writeValueAsString(log);

        assertThat(json).contains("ua_summary").contains("Windows · Chrome");
        // Ham UA da KALIR — tooltip ve satir genisletmesi onu gosteriyor.
        assertThat(json).contains("Mozilla");
    }

    @Test
    @DisplayName("Taninmayan UA'da ozet NULL — arayuz genel etiket kullanir, ham dize DOKMEZ")
    void unknownUaYieldsNullSummary() throws Exception {
        AuditLog log = new AuditLog();
        log.setUserAgent("curl/8.4.0");

        String json = mapper().writeValueAsString(log);

        assertThat(json).contains("\"ua_summary\":null");
    }
}

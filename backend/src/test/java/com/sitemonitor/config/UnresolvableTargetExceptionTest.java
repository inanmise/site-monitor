package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çözümlenemeyen tanılama hedefi — mesaj DEĞİL, YAPISAL alan.
 *
 * <p>Kullanıcı bir alan adını tanıladı ve "İzin verilmeyen tanılama hedefi" mesajını alınca
 * aracın kendisini engellediğini sandı. Oysa host DNS'te yoktu (apex'in A kaydı yayınlanmamış,
 * yalnız {@code www} var — kurumsal alan adlarında çok yaygın). İki durumun ÇÖZÜMÜ farklıdır:
 * biri host adını düzeltmek, diğeri ayar/yetki işi.
 *
 * <p>Öneri neden yapısal alanla taşınıyor: arayüz "Bunu deneyin: ..." cümlesini AYRIŞTIRSAYDI
 * TR/EN arasında ve mesaj her düzenlendiğinde sessizce kırılırdı — düğme kaybolur, kimse fark
 * etmezdi.
 */
class UnresolvableTargetExceptionTest {

    // Denetim servisi bu yolda HIC kullanilmiyor (yalniz 400 govdesi uretiliyor); bos saglayici yeter.
    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(new org.springframework.beans.factory.ObjectProvider<>() {
                @Override public com.sitemonitor.service.AuditService getObject() { return null; }
                @Override public com.sitemonitor.service.AuditService getObject(Object... args) { return null; }
                @Override public com.sitemonitor.service.AuditService getIfAvailable() { return null; }
                @Override public com.sitemonitor.service.AuditService getIfUnique() { return null; }
            });

    @Test
    @DisplayName("Öneri VARSA yanıt suggested_host taşır (arayüz tek tıkla yeniden koşturur)")
    void carriesSuggestionAsStructuredField() {
        var ex = new GlobalExceptionHandler.UnresolvableTargetException(
                "Çözümlenemeyen host: example.com — DNS'te A/AAAA kaydı yok. Bunu deneyin: www.example.com",
                "example.com", "www.example.com");

        ResponseEntity<Map<String, Object>> res = handler.handleBadRequest(ex);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).containsEntry("success", false)
                                 .containsEntry("unresolvable_host", "example.com")
                                 .containsEntry("suggested_host", "www.example.com");
        assertThat(String.valueOf(res.getBody().get("error"))).contains("A/AAAA");
    }

    @Test
    @DisplayName("Öneri YOKSA suggested_host HİÇ konmaz — arayüz olmayan bir hedefi önermesin")
    void withoutSuggestionNoField() {
        var ex = new GlobalExceptionHandler.UnresolvableTargetException(
                "Çözümlenemeyen host: yok.example — DNS'te A/AAAA kaydı yok.", "yok.example", null);

        ResponseEntity<Map<String, Object>> res = handler.handleBadRequest(ex);

        // "www" uydurup çözülmeyen bir hedefe koşturmak, aynı hatayı ikinci kez göstermekten
        // başka işe yaramazdı.
        assertThat(res.getBody()).doesNotContainKey("suggested_host");
    }

    @Test
    @DisplayName("Sıradan IllegalArgumentException davranışı DEĞİŞMEZ")
    void plainBadRequestUnchanged() {
        ResponseEntity<Map<String, Object>> res =
                handler.handleBadRequest(new IllegalArgumentException("domain zorunlu"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).containsEntry("error", "domain zorunlu")
                                 .doesNotContainKey("suggested_host");
    }
}

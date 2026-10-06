package com.sitemonitor.service.manualcert;

import com.sitemonitor.model.CertificateInventory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Manuel takip adı kuralı (2026-10-06) ve kaynak duyarlı envanter anahtarı kısıtı. */
class ManualCertificateKeysTest {

    @Test
    @DisplayName("geçerli takip adları kabul edilir; kırpılır + küçük harfe indirilir")
    void valid() {
        assertThat(ManualCertificateKeys.validate("  Api.Example.Test ")).isEqualTo("api.example.test");
        for (String k : List.of("a", "ocp_truststore-2026", "*.example.test", "root-ca.v2", "a..b", "x--y")) {
            assertThat(ManualCertificateKeys.isValid(k)).as(k).isTrue();
        }
    }

    @Test
    @DisplayName("geçersiz: boş, boşluk, : / @, büyük harf (doğrulamadan önce), baş/son nokta-tire, 253 üstü")
    void invalid() {
        for (String k : List.of("a b", "a:b", "a/b", "a@b", ".a", "a-", "_a", "a.", "Bü", "x".repeat(254))) {
            assertThat(ManualCertificateKeys.isValid(k)).as(k).isFalse();
        }
        assertThatThrownBy(() -> ManualCertificateKeys.validate(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManualCertificateKeys.validate("https://a.example.test")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("öneri tabanı: CN → anahtar biçimi; yoksa ilk SAN; hiçbiri yoksa 'sertifika'")
    void baseSuggestion() {
        assertThat(ManualCertificateKeys.baseSuggestion("My Service CA (2026)", List.of())).isEqualTo("my-service-ca-2026");
        assertThat(ManualCertificateKeys.baseSuggestion("*.Example.Test", List.of())).isEqualTo("*.example.test");
        assertThat(ManualCertificateKeys.baseSuggestion("Unknown", List.of("San.Example.Test"))).isEqualTo("san.example.test");
        assertThat(ManualCertificateKeys.baseSuggestion(null, List.of())).isEqualTo("sertifika");
        assertThat(ManualCertificateKeys.baseSuggestion("ğüşöç", List.of())).isEqualTo("sertifika");
        assertThat(ManualCertificateKeys.candidates("a", 3)).containsExactly("a", "a-manuel", "a-manuel-2", "a-manuel-3");
    }

    @Test
    @DisplayName("envanter kısıtı: ağ kaydı ESKİ @Pattern kuralını korur, manuel kayıt kendi kuralını kullanır")
    void entityConstraintIsSourceAware() {
        Validator v = Validation.buildDefaultValidatorFactory().getValidator();
        CertificateInventory net = new CertificateInventory();
        net.setDomain("ocp_truststore");               // alt çizgi ağ kaydında geçersiz (eski kural)
        assertThat(v.validate(net)).anySatisfy(cv -> {
            assertThat(cv.getPropertyPath().toString()).isEqualTo("domain");
            assertThat(cv.getMessage()).isEqualTo("Geçersiz domain formatı");
        });
        net.setDomain("a--b.example.test");            // çift tire: eski kural reddediyordu
        assertThat(v.validate(net)).anySatisfy(cv -> assertThat(cv.getPropertyPath().toString()).isEqualTo("domain"));
        net.setDomain("Api.Example.Test");             // büyük harf ağ kaydında serbestti
        assertThat(v.validate(net)).isEmpty();

        CertificateInventory manual = new CertificateInventory();
        manual.setCertSource(CertificateInventory.SOURCE_MANUAL);
        manual.setDomain("ocp_truststore");
        assertThat(v.validate(manual)).isEmpty();
        manual.setDomain("Api.Example.Test");          // manuel anahtar küçük harf olmalı
        assertThat(v.validate(manual)).anySatisfy(cv -> assertThat(cv.getMessage()).isEqualTo("Geçersiz takip adı"));
    }
}

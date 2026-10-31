package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Hassas kolon adı tahmini (r177, Ö-6): Türkçe katlama, tam parça belirteç, birleşik ad kökü, yanlış eşleşme yok. */
class DbQuerySensitiveColumnsTest {

    @Test
    void sensitiveNamesMatch() {
        for (String n : List.of("TCKN", "tc_kimlik_no", "TcKimlikNo", "musteri_telefonu", "TELEFON", "gsm_no", "cep_tel",
                "IBAN", "kart_no", "KartNumarasi", "card_number", "PAN", "cvv2", "email", "E_POSTA", "eposta_adresi",
                "user_password", "Şifre", "SIFRE_HASH", "api_key", "apiKey", "secret_token", "MAAS", "salary",
                "dogum_tarihi", "birthDate", "adres", "home_address", "msisdn", "vkn", "PIN"))
            assertThat(DbQuerySensitiveColumns.matches(n)).as(n).isTrue();
    }

    @Test
    void ordinaryNamesDoNotMatch() {
        for (String n : List.of("id", "company", "cardinality", "status", "tutar", "adet", "created_at", "islem_tarihi",
                "kanal", "durum", "pending", "telemetry", "panel", "spin", "mailbox_count_total", "", "   "))
            assertThat(DbQuerySensitiveColumns.matches(n)).as(n).isFalse();
        assertThat(DbQuerySensitiveColumns.matches(null)).isFalse();
        // "tel" ve "mail" TAM parça: alt çizgiyle ayrıldıklarında eşleşir (tahmin muhafazakâr — öneri, engel değil)
        assertThat(DbQuerySensitiveColumns.matches("tel_aviv_branch_id_x")).isTrue();
        // uzun kök birleşik adın içinde de aranır
        assertThat(DbQuerySensitiveColumns.matches("eskitckimlikalani")).isTrue();
    }

    @Test
    void turkishFoldingAndParts() {
        assertThat(DbQuerySensitiveColumns.parts("MüşteriTelefonu")).containsExactly("musteri", "telefonu");
        assertThat(DbQuerySensitiveColumns.parts("DOĞUM_TARİHİ")).containsExactly("dogum", "tarihi");
        assertThat(DbQuerySensitiveColumns.parts("a.b-c d")).containsExactly("a", "b", "c", "d");
        assertThat(DbQuerySensitiveColumns.parts("kart2No")).containsExactly("kart2", "no");
    }

    @Test
    void filterKeepsOrderAndIsUnique() {
        assertThat(DbQuerySensitiveColumns.filter(List.of("id", "TCKN", "tutar", "email", "TCKN")))
                .containsExactly("TCKN", "email");
        assertThat(DbQuerySensitiveColumns.filter(null)).isEmpty();
    }

    @Test
    void longNamesAreBounded() {
        String n = "x".repeat(10_000) + "_telefon";
        long t0 = System.nanoTime();
        assertThat(DbQuerySensitiveColumns.matches(n)).isFalse();   // 128 karakterden sonrası okunmaz
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(500);
    }
}

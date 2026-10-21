package com.sitemonitor.service.loginpolicy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kullanıcı adı ön eki kuralının doğruluk tablosu (2026-10-21) — giriş, remember-me, kodla giriş, oturum kapısı ve
 * önizleme bu TEK kararı kullanır.
 */
class UsernamePrefixRuleTest {

    static final UsernamePrefixRule N_ONLY = UsernamePrefixRule.of(true, "N", "SERVIS.HESAP, ops_bot", "admin");

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(delimiter = '|', value = {
            // izinli ön ek, harf duyarsız, baş/son boşluk
            "N12345            | true",
            "n12345            | true",
            "'  n12345  '      | true",
            "N                 | true",
            // başka ön ek / harf
            "MP12345           | false",
            "mp12345           | false",
            "X1                | false",
            "1N                | false",
            // muaf liste (tam eşleşme, harf duyarsız) + bootstrap yönetici
            "servis.hesap      | true",
            "OPS_BOT           | true",
            "admin             | true",
            "ADMIN             | true",
            "admin2            | false",
            // alan biçimleri: yazılan ad VE alanı atılmış ad ikisi de uymalı (temkinli)
            "n123@corp.local   | true",
            "mp123@corp.local  | false",
            "CORP\\n123        | false",
            "n\\mp123          | false",
            "N\\N123           | true",
    })
    void truthTable(String name, boolean expected) {
        assertThat(N_ONLY.allows(name)).as(name).isEqualTo(expected);
    }

    @Test
    @DisplayName("Türkçe i tuzağı yok: ı / i / İ hepsi I sayılır; Türkçe yerel ayar sonucu değiştirmez")
    void turkishDottedI() {
        Locale prev = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            UsernamePrefixRule r = UsernamePrefixRule.of(true, "i", "", "admin");
            assertThat(r.prefixes()).containsExactly("I");
            assertThat(r.allows("i123")).isTrue();
            assertThat(r.allows("ı123")).isTrue();
            assertThat(r.allows("İ123")).isTrue();
            assertThat(r.allows("I123")).isTrue();
            assertThat(r.allows("j123")).isFalse();
            UsernamePrefixRule exempt = UsernamePrefixRule.of(true, "N", "kişi", "admin");
            assertThat(exempt.allows("KİŞİ")).isTrue();
            assertThat(exempt.allows("KIŞI")).isTrue();
            assertThat(exempt.allows("kisi")).as("ş ≠ s").isFalse();
        } finally {
            Locale.setDefault(prev);
        }
    }

    @Test
    @DisplayName("kapalı kural, kullanılabilir ön eki olmayan açık kural ve boş ad → izin (kimse kilitlenmez)")
    void inactiveAllowsEveryone() {
        assertThat(UsernamePrefixRule.disabled("admin").allows("MP1")).isTrue();
        assertThat(UsernamePrefixRule.of(false, "N", "", "admin").allows("MP1")).isTrue();
        UsernamePrefixRule broken = UsernamePrefixRule.of(true, "Ç, @@, " + "X".repeat(11), "", "admin");
        assertThat(broken.active()).isFalse();
        assertThat(broken.allows("MP1")).isTrue();
        assertThat(N_ONLY.allows("")).isTrue();
        assertThat(N_ONLY.allows(null)).isTrue();
    }

    @Test
    @DisplayName("birden çok ön ek: N ya da MP; tekrar ve sıra korunur; okumada geçersiz öğe atlanır")
    void multiplePrefixes() {
        UsernamePrefixRule r = UsernamePrefixRule.of(true, "n, MP ,n;Ç\nT2", "", "admin");
        assertThat(r.prefixes()).containsExactly("N", "MP", "T2");
        assertThat(r.allows("MP9")).isTrue();
        assertThat(r.allows("T29")).isTrue();
        assertThat(r.allows("X9")).isFalse();
    }

    @Test
    @DisplayName("normalizePrefixes: liste ya da CSV → büyük harf tekil CSV; geçersiz öğe adıyla reddedilir; sınırlar")
    void normalizePrefixes() {
        assertThat(UsernamePrefixRule.normalizePrefixes("n, mp , N")).isEqualTo("N,MP");
        assertThat(UsernamePrefixRule.normalizePrefixes(List.of(" n ", "Mp"))).isEqualTo("N,MP");
        assertThat(UsernamePrefixRule.normalizePrefixes(null)).isEmpty();
        assertThat(UsernamePrefixRule.normalizePrefixes("  ")).isEmpty();
        assertThatThrownBy(() -> UsernamePrefixRule.normalizePrefixes("N, Ç1"))
                .isInstanceOf(UsernamePrefixRule.InvalidValue.class).hasMessageContaining("Ç1")
                .satisfies(e -> assertThat(((UsernamePrefixRule.InvalidValue) e).problem().field()).isEqualTo("username_prefix_allowed"));
        assertThatThrownBy(() -> UsernamePrefixRule.normalizePrefixes("ABCDEFGHIJK"))
                .isInstanceOf(UsernamePrefixRule.InvalidValue.class);
        assertThatThrownBy(() -> UsernamePrefixRule.normalizePrefixes("A B"))
                .isInstanceOf(UsernamePrefixRule.InvalidValue.class);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 21; i++) many.append("P").append(i).append(',');
        assertThatThrownBy(() -> UsernamePrefixRule.normalizePrefixes(many.toString()))
                .isInstanceOf(UsernamePrefixRule.InvalidValue.class);
        assertThatThrownBy(() -> UsernamePrefixRule.normalizePrefixes("N".repeat(6001)))
                .as("uzunluk ayrıştırmadan ÖNCE").isInstanceOf(UsernamePrefixRule.InvalidValue.class);
    }

    @Test
    @DisplayName("normalizeExempt: büyük harf tekil; boşluk / denetim karakteri / 100+ karakter reddedilir; en çok 50")
    void normalizeExempt() {
        assertThat(UsernamePrefixRule.normalizeExempt("servis.hesap, SERVIS.HESAP\nops_bot")).isEqualTo("SERVIS.HESAP,OPS_BOT");
        assertThatThrownBy(() -> UsernamePrefixRule.normalizeExempt(List.of("iki kelime")))
                .isInstanceOf(UsernamePrefixRule.InvalidValue.class)
                .satisfies(e -> assertThat(((UsernamePrefixRule.InvalidValue) e).problem().field()).isEqualTo("username_prefix_exempt"));
        assertThatThrownBy(() -> UsernamePrefixRule.normalizeExempt("a".repeat(101)))
                .isInstanceOf(UsernamePrefixRule.InvalidValue.class);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 51; i++) many.append("u").append(i).append(',');
        assertThatThrownBy(() -> UsernamePrefixRule.normalizeExempt(many.toString()))
                .isInstanceOf(UsernamePrefixRule.InvalidValue.class);
    }

    @Test
    @DisplayName("validateSave: açıkken boş liste → hata; kaydeden kendini kilitleyemez (LOCKOUT_SELF); muaf / bootstrap geçer; kapalı → serbest")
    void validateSave() {
        var empty = UsernamePrefixRule.validateSave(true, "", "", "N1", "admin");
        assertThat(empty).isNotNull();
        assertThat(empty.field()).isEqualTo("username_prefix_allowed");
        assertThat(empty.code()).isEqualTo("VALIDATION_FAILED");

        var self = UsernamePrefixRule.validateSave(true, "N", "", "MP77", "admin");
        assertThat(self).isNotNull();
        assertThat(self.code()).isEqualTo("LOCKOUT_SELF");
        assertThat(self.tr()).contains("MP77");
        assertThat(self.en()).contains("MP77");

        assertThat(UsernamePrefixRule.validateSave(true, "N", "MP77", "MP77", "admin")).isNull();
        assertThat(UsernamePrefixRule.validateSave(true, "N", "", "admin", "admin")).isNull();
        assertThat(UsernamePrefixRule.validateSave(true, "N", "", "N5", "admin")).isNull();
        assertThat(UsernamePrefixRule.validateSave(true, "N", "", null, "admin")).as("istek bağlamı yok").isNull();
        assertThat(UsernamePrefixRule.validateSave(false, "", "", "MP77", "admin")).isNull();
    }

    @Test
    @DisplayName("ret gövdesi: kod, error_code, izinli ön ekler ve onları adlandıran ileti")
    void deniedBody() {
        Map<String, Object> b = UsernamePrefixRule.deniedBody(List.of("N", "T2"));
        assertThat(b).containsEntry("success", false).containsEntry("code", "USERNAME_PREFIX_NOT_ALLOWED")
                .containsEntry("error_code", "USERNAME_PREFIX_NOT_ALLOWED").containsEntry("allowed_prefixes", List.of("N", "T2"));
        assertThat(String.valueOf(b.get("error"))).contains("N, T2");
    }

    @Test
    @DisplayName("isExempt kural kapalıyken de yanıtlar; muaf listesi okumada 50 ile sınırlı")
    void exemptBounded() {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < 80; i++) csv.append("u").append(i).append(',');
        UsernamePrefixRule r = UsernamePrefixRule.of(true, "N", csv.toString(), "admin");
        assertThat(r.exempt()).hasSize(50);
        assertThat(UsernamePrefixRule.disabled("admin").isExempt("ADMIN")).isTrue();
    }
}

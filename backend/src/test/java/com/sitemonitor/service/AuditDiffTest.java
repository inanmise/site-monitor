package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditDiff} — yalnız değişen alanları {from,to} JSON'a çevirir; hassas alanlar *** maskelenir.
 */
class AuditDiffTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("yalnız değişen alan diff'e girer; değişmeyen atlanır")
    void diff_onlyChangedFields() {
        String json = AuditDiff.diff(map("a", 1, "b", 2), map("a", 1, "b", 3));
        assertThat(json).contains("\"b\":{\"from\":2,\"to\":3}");
        assertThat(json).doesNotContain("\"a\"");
    }

    @Test
    @DisplayName("değişiklik yoksa null döner")
    void diff_noChange_returnsNull() {
        assertThat(AuditDiff.diff(map("a", 1), map("a", 1))).isNull();
    }

    @Test
    @DisplayName("eklenen/kaldırılan alan (null↔değer) diff'e girer")
    void diff_addedRemoved() {
        assertThat(AuditDiff.diff(map("a", 1), map("a", 1, "b", 9))).contains("\"b\":{\"from\":null,\"to\":9}");
        assertThat(AuditDiff.diff(map("a", 1, "b", 9), map("a", 1))).contains("\"b\":{\"from\":9,\"to\":null}");
    }

    @Test
    @DisplayName("hassas alanlar (password/token/secret/apiKey) from/to'da *** maskelenir — düz metin YOK")
    void diff_masksSensitiveFields() {
        String pw = AuditDiff.diff(map("password", "oldpass"), map("password", "newpass"));
        assertThat(pw).contains("\"password\":{\"from\":\"***\",\"to\":\"***\"}");
        assertThat(pw).doesNotContain("oldpass").doesNotContain("newpass");

        assertThat(AuditDiff.diff(map("apiToken", "AAA"), map("apiToken", "BBB")))
                .contains("\"***\"").doesNotContain("AAA").doesNotContain("BBB");
        assertThat(AuditDiff.diff(map("clientSecret", "S1"), map("clientSecret", "S2")))
                .doesNotContain("S1").doesNotContain("S2");
    }

    @Test
    @DisplayName("null harita girişleri güvenli (NPE yok)")
    void diff_nullMaps_safe() {
        assertThat(AuditDiff.diff(null, map("a", 1))).contains("\"a\"");
        assertThat(AuditDiff.diff(new HashMap<>(), new HashMap<>())).isNull();
    }

    @Test
    @DisplayName("isSensitive: parola/token/secret anahtarlarını tanır, normal alanları değil")
    void isSensitive_recognizesKeys() {
        assertThat(AuditDiff.isSensitive("passwordHash")).isTrue();
        assertThat(AuditDiff.isSensitive("api_key")).isTrue();
        assertThat(AuditDiff.isSensitive("teamId")).isFalse();
        assertThat(AuditDiff.isSensitive("name")).isFalse();
    }

    // ── Değer sertleştirmesi (2026-09) ──────────────────────────────────────
    //
    // Anahtar adına bakan maskeleme tek başına yetmiyordu: adı masum ama değeri kimlik bilgisi
    // taşıyan alanlar, ikili içerik ve sınırsız uzunluk üç ayrı sızıntı/şişme yüzeyiydi.

    @Test
    @DisplayName("SIZINTI KAPISI: adı masum ama DEĞERİ kimlik taşıyan alan gövdesinden temizlenir")
    void diff_scrubsCredentialsInValueBody() {
        // "webhook-url" hassas anahtar DEĞİL (kara listede 'url' yok) — koruma değerin kendisinden gelmeli.
        String json = AuditDiff.diff(
                map("webhook-url", "https://push.example.com/send?token=OLD-SECRET"),
                map("webhook-url", "https://push.example.com/send?token=NEW-SECRET"));

        assertThat(json).as("token değeri düz metin yazılmış: %s", json)
                .doesNotContain("OLD-SECRET").doesNotContain("NEW-SECRET");
        assertThat(json).contains("push.example.com");   // tanı değeri korunur
    }

    @Test
    @DisplayName("SIZINTI KAPISI: URL'e gömülü kullanıcı:parola maskelenir")
    void diff_scrubsUserInfoInUrl() {
        String json = AuditDiff.diff(map("endpoint", "https://old.example.com/h"),
                                     map("endpoint", "https://admin:Hunter2@relay.example.com/h"));
        assertThat(json).doesNotContain("Hunter2");
        assertThat(json).contains("relay.example.com");
    }

    @Test
    @DisplayName("ikili içerik (logo) BOYUTA daralır — base64 gövdesi denetime akmaz")
    void diff_collapsesBinaryValues() {
        String big = "data:image/png;base64," + "A".repeat(5000);
        String json = AuditDiff.diff(map("logo-data", ""), map("logo-data", big));
        assertThat(json).contains("\"bytes\":").doesNotContain("AAAA");
        assertThat(json.length()).isLessThan(200);
    }

    @Test
    @DisplayName("uzun değer GÖRÜNÜR biçimde kırpılır (sessiz kesme denetimde yanılgı üretir)")
    void diff_truncatesLongValues() {
        String json = AuditDiff.diff(map("note", "x"), map("note", "y".repeat(2000)));
        assertThat(json).contains("…(+");
        assertThat(json.length()).isLessThan(AuditDiff.MAX_VALUE_LEN + 200);
    }

    @Test
    @DisplayName("koleksiyon değeri diziye çevrilir; tavanı aşan öğeler '+N daha' ile özetlenir")
    void diff_listValuesAreCappedVisibly() {
        java.util.List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) many.add("kisi" + i + "@example.com");
        String json = AuditDiff.diff(map("excluded", java.util.List.of()), map("excluded", many));

        assertThat(json).contains("kisi0@example.com").contains("+10 daha");
        assertThat(json).doesNotContain("kisi25@example.com");
    }

    @Test
    @DisplayName("snapshotJson da aynı sertleştirmeden geçer (silme kaydı sır sızdırmasın)")
    void snapshotJson_hardenedToo() {
        String json = AuditDiff.snapshotJson(map(
                "name", "Takım A",
                "hook", "https://x.example.com/p?apiKey=TOPSECRET",
                "logo", "data:image/png;base64," + "B".repeat(3000)));

        assertThat(json).contains("Takım A");
        assertThat(json).doesNotContain("TOPSECRET").doesNotContain("BBBB");
    }
}

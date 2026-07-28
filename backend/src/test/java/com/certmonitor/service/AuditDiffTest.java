package com.certmonitor.service;

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
}

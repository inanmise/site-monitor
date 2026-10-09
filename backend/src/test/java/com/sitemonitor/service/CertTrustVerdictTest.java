package com.sitemonitor.service;

import com.sitemonitor.dto.CertificateDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Güven hükmü (2026-10-09): arayüz {@code certTableModel.js#trustOf} ile BİREBİR aynı doğruluk tablosu — aynı satırlar
 * {@code frontend/src/test/certTableModel.test.js} "trustOf ↔ CertTrustVerdict" testinde de var. Biri değişirse ikisi.
 */
class CertTrustVerdictTest {

    private static CertificateDto dto(String chain, String trust, String rev) {
        CertificateDto d = new CertificateDto();
        d.setChainStatus(blank(chain)); d.setTrustStatus(blank(trust)); d.setRevocationStatus(blank(rev));
        return d;
    }
    private static String blank(String s) { return s == null || s.isEmpty() || "-".equals(s) ? null : s; }

    @ParameterizedTest(name = "{0}/{1}/{2} → {3} [{4}]")
    @CsvSource({
            "VALID,   TRUSTED,   GOOD,    ok,      ''",
            "VALID,   TRUSTED,   UNKNOWN, partial, ''",
            "VALID,   -,         -,       partial, ''",
            "-,       TRUSTED,   -,       partial, ''",
            "-,       -,         -,       unknown, ''",
            "UNKNOWN, UNKNOWN,   UNKNOWN, unknown, ''",
            "BROKEN,  TRUSTED,   GOOD,    bad,     chain",
            "VALID,   UNTRUSTED, GOOD,    bad,     untrusted",
            "VALID,   TRUSTED,   REVOKED, bad,     revoked",
            "BROKEN,  UNTRUSTED, REVOKED, bad,     chain|untrusted|revoked",
            "broken,  trusted,   unknown, bad,     chain",
            "valid,   trusted,   good,    ok,      ''",
    })
    void truthTable(String chain, String trust, String rev, String tone, String issues) {
        CertificateDto d = dto(chain, trust, rev);
        assertThat(CertTrustVerdict.tone(d)).isEqualTo(tone);
        List<String> want = issues.isEmpty() ? List.of() : Arrays.asList(issues.split("\\|"));
        assertThat(CertTrustVerdict.issues(d)).isEqualTo(want);
    }

    @Test
    @DisplayName("süzgeç: hüküm değerleri tonla, sorun türleri sorun listesiyle eşleşir; boş/bilinmeyen değer süzmez")
    void matches() {
        CertificateDto partial = dto("VALID", "TRUSTED", "UNKNOWN");
        CertificateDto twoIssues = dto("BROKEN", "UNTRUSTED", "GOOD");
        assertThat(CertTrustVerdict.matches(partial, "partial")).isTrue();
        assertThat(CertTrustVerdict.matches(partial, "ok")).isFalse();
        assertThat(CertTrustVerdict.matches(partial, "bad")).isFalse();
        assertThat(CertTrustVerdict.matches(twoIssues, "bad")).isTrue();
        assertThat(CertTrustVerdict.matches(twoIssues, "chain")).isTrue();
        assertThat(CertTrustVerdict.matches(twoIssues, "untrusted")).isTrue();
        assertThat(CertTrustVerdict.matches(twoIssues, "revoked")).isFalse();
        assertThat(CertTrustVerdict.matches(partial, "")).isTrue();
        assertThat(CertTrustVerdict.matches(partial, null)).isTrue();
        assertThat(CertTrustVerdict.matches(partial, "uydurma")).isTrue();   // bozuk bağlantı listeyi boşaltmaz
        assertThat(CertTrustVerdict.matches(partial, " Partial ")).isTrue();
        assertThat(CertTrustVerdict.isKnownFilter("revoked")).isTrue();
        assertThat(CertTrustVerdict.isKnownFilter("insecure")).isFalse();
        assertThat(CertTrustVerdict.tone(null)).isEqualTo("unknown");
    }
}

package com.sitemonitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sürüm karşılaştırma yardımcısı — dağıtım geçişi türetiminin (UPGRADE/ROLLBACK/CHANGED) tek
 * dayanağı. "v" öneki ve "-rc" son eki tolere edilir; ayrıştırılamayan sürüm KİLİTLEMEZ (empty).
 */
class SemverTest {

    @Test
    @DisplayName("parse: düz, v önekli ve son ekli sürümler aynı üçlüye çözülür")
    void parse_prefixAndSuffixTolerated() {
        assertThat(Semver.parse("20.53.2")).contains(new Semver(20, 53, 2));
        assertThat(Semver.parse("v20.53.2")).contains(new Semver(20, 53, 2));
        assertThat(Semver.parse("  v1.2.3-rc1 ")).contains(new Semver(1, 2, 3));
        assertThat(Semver.parse("1.2.3+build.7")).contains(new Semver(1, 2, 3));
    }

    @Test
    @DisplayName("parse: eksik/bozuk/null sürüm empty (istisna yok)")
    void parse_invalidIsEmpty() {
        assertThat(Semver.parse(null)).isEmpty();
        assertThat(Semver.parse("")).isEmpty();
        assertThat(Semver.parse("unknown")).isEmpty();
        assertThat(Semver.parse("1.2")).isEmpty();
        assertThat(Semver.parse("x.y.z")).isEmpty();
        // int taşması: NumberFormatException yutulur
        assertThat(Semver.parse("99999999999.0.0")).isEmpty();
    }

    @Test
    @DisplayName("compare: major > minor > patch; eşit sürüm 0; biri ayrıştırılamıyorsa empty")
    void compare_ordering() {
        assertThat(Semver.compare("20.54.0", "20.53.9")).contains(1);
        assertThat(Semver.compare("20.53.2", "20.53.10")).hasValueSatisfying(v -> assertThat(v).isNegative());
        assertThat(Semver.compare("21.0.0", "20.99.99")).hasValueSatisfying(v -> assertThat(v).isPositive());
        assertThat(Semver.compare("v1.0.0", "1.0.0")).contains(0);
        assertThat(Semver.compare("1.0.0", "unknown")).isEmpty();
        assertThat(Semver.compare(null, "1.0.0")).isEmpty();
    }

    @Test
    @DisplayName("bumpKind: major | minor | patch | same | unknown")
    void bumpKind() {
        assertThat(Semver.bumpKind("20.53.2", "21.0.0")).isEqualTo("major");
        assertThat(Semver.bumpKind("20.53.2", "20.54.0")).isEqualTo("minor");
        assertThat(Semver.bumpKind("20.53.2", "20.53.3")).isEqualTo("patch");
        assertThat(Semver.bumpKind("20.53.2", "v20.53.2")).isEqualTo("same");
        assertThat(Semver.bumpKind("20.53.2", "bogus")).isEqualTo("unknown");
        assertThat(Semver.bumpKind(null, "1.0.0")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("Comparable + toString: normalize edilmiş üçlü")
    void comparableAndToString() {
        Optional<Semver> a = Semver.parse("v1.2.3-rc");
        assertThat(a).isPresent();
        assertThat(a.get().toString()).isEqualTo("1.2.3");
        assertThat(a.get().compareTo(new Semver(1, 2, 4))).isNegative();
        assertThat(a.get().compareTo(new Semver(1, 2, 3))).isZero();
    }
}

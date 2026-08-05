package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RdapDomainExpiryService} saf statik yardımcıları — domain süre-bitişi gün hesabı + host/registrable
 * ayıklama. Süre bitişi alarmlarını besleyen çekirdek matematik; daha önce hiç unit-test edilmemişti.
 */
class RdapDomainExpiryServiceTest {

    // ── daysUntil ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("daysUntil_null_returnsNull")
    void daysUntil_null_returnsNull() {
        assertThat(RdapDomainExpiryService.daysUntil(null)).isNull();
    }

    @Test
    @DisplayName("daysUntil_blank_returnsNull")
    void daysUntil_blank_returnsNull() {
        assertThat(RdapDomainExpiryService.daysUntil("   ")).isNull();
    }

    @Test
    @DisplayName("daysUntil_unparseable_returnsNull")
    void daysUntil_unparseable_returnsNull() {
        assertThat(RdapDomainExpiryService.daysUntil("not-a-date")).isNull();
    }

    @Test
    @DisplayName("daysUntil_farFuture_returnsLargePositive")
    void daysUntil_farFuture_returnsLargePositive() {
        assertThat(RdapDomainExpiryService.daysUntil("2099-01-01T00:00:00Z")).isGreaterThan(20_000);
    }

    @Test
    @DisplayName("daysUntil_alreadyExpired_returnsNegative")
    void daysUntil_alreadyExpired_returnsNegative() {
        assertThat(RdapDomainExpiryService.daysUntil("2000-01-01T00:00:00Z")).isLessThan(0);
    }

    @Test
    @DisplayName("daysUntil_offsetFormat_parsed")
    void daysUntil_offsetFormat_parsed() {
        assertThat(RdapDomainExpiryService.daysUntil("2099-01-01T00:00:00+03:00")).isGreaterThan(20_000);
    }

    @Test
    @DisplayName("daysUntil_dateOnly_parsedViaSubstring")
    void daysUntil_dateOnly_parsedViaSubstring() {
        assertThat(RdapDomainExpiryService.daysUntil("2099-01-01")).isGreaterThan(20_000);
    }

    @Test
    @DisplayName("daysUntil_thirtyDaysFromNow_returnsAboutThirty")
    void daysUntil_thirtyDaysFromNow_returnsAboutThirty() {
        String iso = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plus(30, ChronoUnit.DAYS));
        // Truncation + test'in geçen mikrosaniyeleri → 29-30 arası (flaky değil).
        assertThat(RdapDomainExpiryService.daysUntil(iso)).isBetween(28, 30);
    }

    // ── extractHost ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("extractHost_fullUrl_stripsSchemePathQuery")
    void extractHost_fullUrl_stripsSchemePathQuery() {
        assertThat(RdapDomainExpiryService.extractHost("https://www.example.com/path?q=1")).isEqualTo("www.example.com");
    }

    @Test
    @DisplayName("extractHost_hostWithPort_stripsPort")
    void extractHost_hostWithPort_stripsPort() {
        assertThat(RdapDomainExpiryService.extractHost("example.com:443")).isEqualTo("example.com");
    }

    @Test
    @DisplayName("extractHost_userInfo_stripped")
    void extractHost_userInfo_stripped() {
        assertThat(RdapDomainExpiryService.extractHost("user@example.com")).isEqualTo("example.com");
    }

    @Test
    @DisplayName("extractHost_uppercaseTrailingDot_normalized")
    void extractHost_uppercaseTrailingDot_normalized() {
        assertThat(RdapDomainExpiryService.extractHost("EXAMPLE.COM.")).isEqualTo("example.com");
    }

    @Test
    @DisplayName("extractHost_null_returnsNull")
    void extractHost_null_returnsNull() {
        assertThat(RdapDomainExpiryService.extractHost(null)).isNull();
    }

    // ── registrableDomain ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("registrableDomain_twoLevelTld_takesThreeLabels")
    void registrableDomain_twoLevelTld_takesThreeLabels() {
        assertThat(RdapDomainExpiryService.registrableDomain("www.akbank.com.tr")).isEqualTo("akbank.com.tr");
    }

    @Test
    @DisplayName("registrableDomain_deepSubdomain_reducesToEtldPlusOne")
    void registrableDomain_deepSubdomain_reducesToEtldPlusOne() {
        assertThat(RdapDomainExpiryService.registrableDomain("a.b.example.com")).isEqualTo("example.com");
    }

    @Test
    @DisplayName("registrableDomain_coUk_takesThreeLabels")
    void registrableDomain_coUk_takesThreeLabels() {
        assertThat(RdapDomainExpiryService.registrableDomain("shop.example.co.uk")).isEqualTo("example.co.uk");
    }

    @Test
    @DisplayName("registrableDomain_alreadyRegistrable_unchanged")
    void registrableDomain_alreadyRegistrable_unchanged() {
        assertThat(RdapDomainExpiryService.registrableDomain("example.com")).isEqualTo("example.com");
    }

    @Test
    @DisplayName("registrableDomain_null_returnsNull")
    void registrableDomain_null_returnsNull() {
        assertThat(RdapDomainExpiryService.registrableDomain(null)).isNull();
    }
}

package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kara liste (DNSBL) yorumlaması — bu tablonun tek bir dalı yanlış olursa ya koruma sessizce
 * kapanır ya da tüm envanter sahte alarma boğulur.
 *
 * <p><b>En kritik dal 127.255.255.x:</b> Spamhaus açık/public resolver'lardan gelen sorguları
 * REDDEDER ve bunu bir "listede" cevabı biçiminde bildirir. Bu kodları listelenme saymak,
 * kurumsal DNS'i public bir resolver'a düşmüş her kurulumda BÜTÜN domainleri kara listede
 * göstermek olurdu.
 *
 * <p>Sorgu katmanı ({@link DnsCheckerService}) mock'lu: tablo saf, ağ yok.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DnsblCheckerServiceTest {

    @Mock DnsCheckerService dns;
    @Mock AppSettingsService appSettings;

    private DnsblCheckerService service() {
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        return new DnsblCheckerService(dns, appSettings);
    }

    private static Map<String, Object> answer(String... values) {
        return Map.of("success", true, "values", List.of((Object[]) values));
    }

    private static Map<String, Object> rcode(String code) {
        return Map.of("success", false, "values", List.of(), "error", code);
    }

    // ── Yorumlama tablosu ────────────────────────────────────────────────────

    @Test
    @DisplayName("NXDOMAIN = TEMİZ — listede olmadığının TEK kanıtı budur")
    void nxdomainIsClean() {
        assertThat(DnsblCheckerService.interpret(rcode("NXDOMAIN"))).isEqualTo(DnsblCheckerService.CLEAN);
    }

    @ParameterizedTest(name = "{0} → LISTEDE")
    @CsvSource({ "127.0.0.2", "127.0.0.3", "127.0.0.4", "127.0.0.10", "127.0.1.2" })
    void listedCodes(String value) {
        assertThat(DnsblCheckerService.interpret(answer(value))).isEqualTo(DnsblCheckerService.LISTED);
    }

    /**
     * Spamhaus'un "sorgun reddedildi" kodları. Bunları listelenme saymak, kurumsal DNS public
     * resolver'a düştüğü anda tüm envanteri sahte alarma boğardı.
     */
    @ParameterizedTest(name = "{0} → DOĞRULANAMADI (asla listede değil)")
    @CsvSource({ "127.255.255.252", "127.255.255.254", "127.255.255.255" })
    void blockedQueryCodesAreUnknown(String value) {
        assertThat(DnsblCheckerService.interpret(answer(value))).isEqualTo(DnsblCheckerService.UNKNOWN);
    }

    @ParameterizedTest(name = "{0} → DOĞRULANAMADI")
    @CsvSource({ "SERVFAIL", "REFUSED", "TIMEOUT", "'no answer'" })
    void failuresAreUnknown(String code) {
        assertThat(DnsblCheckerService.interpret(rcode(code))).isEqualTo(DnsblCheckerService.UNKNOWN);
    }

    @Test
    @DisplayName("Beklenmedik A kaydı (127. dışı) iddia ÜRETMEZ")
    void unexpectedAnswerIsUnknown() {
        assertThat(DnsblCheckerService.interpret(answer("10.1.2.3"))).isEqualTo(DnsblCheckerService.UNKNOWN);
        assertThat(DnsblCheckerService.interpret(null)).isEqualTo(DnsblCheckerService.UNKNOWN);
    }

    // ── Ters oktet ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ters oktet: 1.2.3.4 → 4.3.2.1; IPv6 ve bozuk girdi ELENIR")
    void reverseOctets() {
        assertThat(DnsblCheckerService.reverse("1.2.3.4")).isEqualTo("4.3.2.1");
        assertThat(DnsblCheckerService.reverse("203.0.113.9")).isEqualTo("9.113.0.203");
        // IPv6 ters-oktet sorgusu tamamen farklı bir biçimdir (nibble); desteklemiyoruz —
        // yanlış bir sorgu üretmektense hiç sormamak doğru.
        assertThat(DnsblCheckerService.reverse("2001:db8::1")).isEmpty();
        assertThat(DnsblCheckerService.reverse("1.2.3")).isEmpty();
        assertThat(DnsblCheckerService.reverse("a.b.c.d")).isEmpty();
        assertThat(DnsblCheckerService.reverse(null)).isEmpty();
    }

    // ── Uçtan uca ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Tüm listeler NXDOMAIN → TEMİZ")
    void allCleanIsClean() {
        when(dns.check(anyString(), eq("A"))).thenReturn(rcode("NXDOMAIN"));

        var r = service().check("example.com", List.of("1.2.3.4"));

        assertThat(r.status()).isEqualTo(DnsblCheckerService.CLEAN);
        assertThat(r.hits()).isZero();
    }

    @Test
    @DisplayName("Bir liste eşleşirse LISTEDE + KANIT (hangi liste, hangi IP)")
    void listedCarriesEvidence() {
        when(dns.check(anyString(), eq("A"))).thenReturn(rcode("NXDOMAIN"));
        when(dns.check(eq("4.3.2.1.zen.spamhaus.org"), eq("A"))).thenReturn(answer("127.0.0.2"));

        var r = service().check("example.com", List.of("1.2.3.4"));

        assertThat(r.status()).isEqualTo(DnsblCheckerService.LISTED);
        assertThat(r.hits()).isEqualTo(1);
        // Kanıt olmadan alarmı alan kişi hiçbir şey yapamaz.
        assertThat(r.detail()).contains("zen.spamhaus.org").contains("1.2.3.4");
    }

    /**
     * KISMİ cevapsızlık "temiz" DEĞİLDİR. Bir liste cevap verip temiz dese bile diğeri
     * cevapsızsa domain o listede olabilir — "temiz" demek korumanın çalıştığı yanılsamasıdır.
     */
    @Test
    @DisplayName("Listelerden biri cevapsızsa sonuç DOĞRULANAMADI (temiz DEĞİL)")
    void partialFailureIsUnknown() {
        when(dns.check(anyString(), eq("A"))).thenReturn(rcode("NXDOMAIN"));
        when(dns.check(eq("4.3.2.1.bl.spamcop.net"), eq("A"))).thenReturn(rcode("SERVFAIL"));

        var r = service().check("example.com", List.of("1.2.3.4"));

        assertThat(r.status()).isEqualTo(DnsblCheckerService.UNKNOWN);
    }

    @Test
    @DisplayName("Hiçbir sorgu cevaplanmazsa DOĞRULANAMADI — sessizce 'temiz' YOK")
    void nothingAnsweredIsUnknown() {
        when(dns.check(anyString(), eq("A"))).thenReturn(rcode("SERVFAIL"));

        assertThat(service().check("example.com", List.of("1.2.3.4")).status())
                .isEqualTo(DnsblCheckerService.UNKNOWN);
    }

    @Test
    @DisplayName("IP tavanı: yalnız ilk N adres sorgulanır")
    void ipsAreCapped() {
        when(appSettings.getString(anyString(), any())).thenReturn("zen.spamhaus.org");
        when(appSettings.getInt(eq("site.monitor.domain.dnsbl-max-ips"), anyInt())).thenReturn(2);
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i ->
                "site.monitor.domain.dnsbl-max-ips".equals(i.getArgument(0)) ? 2 : i.getArgument(1));
        when(dns.check(anyString(), eq("A"))).thenReturn(rcode("NXDOMAIN"));

        new DnsblCheckerService(dns, appSettings)
                .check("example.com", List.of("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4"));

        // Tavan aşan IP'ler HİÇ sorgulanmamalı: tek domain onlarca dış sorgu üretmesin.
        verify(dns, never()).check(eq("3.3.3.3.zen.spamhaus.org".replace("3.3.3.3", "3.3.3.3")), eq("A"));
        verify(dns, never()).check(eq("4.4.4.4.zen.spamhaus.org"), eq("A"));
    }

    @Test
    @DisplayName("Domain zone'u (DBL) IP DEĞİL alan adını sorgular")
    void domainZoneQueriesTheDomain() {
        when(appSettings.getString(anyString(), any())).thenReturn("dbl.spamhaus.org");
        when(dns.check(anyString(), eq("A"))).thenReturn(rcode("NXDOMAIN"));

        new DnsblCheckerService(dns, appSettings).check("example.com", List.of("1.2.3.4"));

        verify(dns).check(eq("example.com.dbl.spamhaus.org"), eq("A"));
        verify(dns, never()).check(eq("4.3.2.1.dbl.spamhaus.org"), eq("A"));
    }

    @Test
    @DisplayName("Liste yapılandırması boşsa izleme ATLANIR (sorgu yok)")
    void emptyListSkips() {
        when(appSettings.getString(anyString(), any())).thenReturn("  ");

        var r = new DnsblCheckerService(dns, appSettings).check("example.com", List.of("1.2.3.4"));

        assertThat(r.status()).isEqualTo(DnsblCheckerService.SKIPPED);
        verify(dns, never()).check(anyString(), anyString());
    }

    @Test
    @DisplayName("Kanıt ayrıştırma + delist bağlantısı (mail ve arayüz AYNI ayrıştırıcıyı kullanır)")
    void detailParsingAndDelistUrl() {
        var parsed = DnsblCheckerService.parseDetail("zen.spamhaus.org=1.2.3.4; bl.spamcop.net=5.6.7.8");

        assertThat(parsed).containsEntry("zen.spamhaus.org", "1.2.3.4")
                          .containsEntry("bl.spamcop.net", "5.6.7.8");
        assertThat(DnsblCheckerService.delistUrl("zen.spamhaus.org")).contains("spamhaus");
        assertThat(DnsblCheckerService.delistUrl("bl.spamcop.net")).contains("spamcop");
        // Bilinmeyen liste için bağlantı UYDURULMAZ.
        assertThat(DnsblCheckerService.delistUrl("baska.liste.example.com")).isNull();
        assertThat(DnsblCheckerService.parseDetail(null)).isEmpty();
    }
}

package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link WhoisDomainClient} — TLD→sunucu çözümü, env-gate ve parser bağlama (özellikle .tr HTTPS web-whois)
 * mantığı. Ham port-43 soketi ({@code query}) gerçek ağ olduğundan test edilmez; parser seçimi + kaynak
 * damgalama + hata/guard yolları mock'lu kenarlarla doğrulanır. Dış host'a bağımlılık yok.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WhoisDomainClientTest {

    @Mock AppSettingsService appSettings;
    @Mock PublicSuffixService psl;
    @Mock TrWebWhoisClient trWebWhois;

    WhoisDomainClient client;

    @BeforeEach
    void setUp() {
        client = new WhoisDomainClient(appSettings, psl, trWebWhois);
    }

    @Test
    @DisplayName("serverFor: config CSV override gömülü varsayılanı ezer")
    void serverFor_configOverride_winsOverBuiltin() {
        when(appSettings.getCsv("site.monitor.domain.whois-servers", ""))
                .thenReturn(List.of("tr=whois.custom.tr"));
        assertThat(client.serverFor("tr")).isEqualTo("whois.custom.tr");
    }

    @Test
    @DisplayName("serverFor: override yoksa gömülü varsayılana düşer (com → verisign)")
    void serverFor_noOverride_fallsBackToBuiltin() {
        when(appSettings.getCsv("site.monitor.domain.whois-servers", "")).thenReturn(List.of());
        assertThat(client.serverFor("com")).isEqualTo("whois.verisign-grs.com");
    }

    @Test
    @DisplayName("serverFor: bilinmeyen TLD → null; null TLD → null")
    void serverFor_unknownOrNull_returnsNull() {
        when(appSettings.getCsv("site.monitor.domain.whois-servers", "")).thenReturn(List.of());
        assertThat(client.serverFor("nonexistenttld")).isNull();
        assertThat(client.serverFor(null)).isNull();
    }

    @Test
    @DisplayName("enabled: site.monitor.domain.whois-enabled bayrağını yansıtır")
    void enabled_reflectsFlag() {
        when(appSettings.getBoolean("site.monitor.domain.whois-enabled", false)).thenReturn(true);
        assertThat(client.enabled()).isTrue();
    }

    @Test
    @DisplayName("anySourceEnabled: socket KAPALI + .tr web-whois AÇIK → true (2026-08 prod: .tr UNKNOWN regresyonu)")
    void anySourceEnabled_trWebOnly_isTrue() {
        // DOMAIN_WHOIS_ENABLED=false (port-43 proxy'den geçemez — prod chart kararı) ama .tr web-whois açık:
        // zamanlanmış tarama gate'i yine de WHOIS fallback'ine girmeli — aksi halde .tr domainleri
        // Sorun Tanıla'da veri bulunurken planlı kontrolde UNKNOWN kalıyordu.
        when(appSettings.getBoolean("site.monitor.domain.whois-enabled", false)).thenReturn(false);
        when(trWebWhois.enabled()).thenReturn(true);
        assertThat(client.anySourceEnabled()).isTrue();
    }

    @Test
    @DisplayName("anySourceEnabled: iki kaynak da kapalı → false; yalnız socket açık → true")
    void anySourceEnabled_reflectsBothFlags() {
        when(appSettings.getBoolean("site.monitor.domain.whois-enabled", false)).thenReturn(false);
        when(trWebWhois.enabled()).thenReturn(false);
        assertThat(client.anySourceEnabled()).isFalse();

        when(appSettings.getBoolean("site.monitor.domain.whois-enabled", false)).thenReturn(true);
        assertThat(client.anySourceEnabled()).isTrue();
    }

    @Test
    @DisplayName("lookup: boş/null domain → source=NONE + hata (guard)")
    void lookup_blankDomain_returnsNoneError() {
        Map<String, Object> r = client.lookup("  ");
        assertThat(r.get("source")).isEqualTo("NONE");
        assertThat(r.get("error")).isNotNull();
    }

    @Test
    @DisplayName("lookup: .tr değil + WHOIS kapalı → 'whois disabled' hatası (source=NONE)")
    void lookup_whoisDisabled_returnsNoneError() {
        when(psl.tldOf("example.com")).thenReturn("com");
        when(appSettings.getBoolean("site.monitor.domain.whois-enabled", false)).thenReturn(false);

        Map<String, Object> r = client.lookup("example.com");

        assertThat(r.get("source")).isEqualTo("NONE");
        assertThat((String) r.get("error")).contains("disabled");
    }

    @Test
    @DisplayName("lookup: .tr + web-whois açık → fetched raw TrWhoisParser'a beslenir, source=WHOIS + provider damgalanır")
    void lookup_trWebWhois_parsesAndStampsProviderAndExpiry() {
        String raw = "** Domain Name: example.com.tr\n"
                + "** Additional Info:\n"
                + "Created on..............: 2006-Oct-27.\n"
                + "Expires on..............: 2029-Oct-26.\n";
        when(psl.tldOf("example.com.tr")).thenReturn("tr");
        when(trWebWhois.enabled()).thenReturn(true);
        when(trWebWhois.fetch(eq("example.com.tr")))
                .thenReturn(new TrWebWhoisClient.Fetched(raw, "isimtescil"));

        Map<String, Object> r = client.lookup("example.com.tr");

        assertThat(r.get("source")).isEqualTo("WHOIS");
        assertThat(r.get("whois_provider")).isEqualTo("isimtescil");
        assertThat(r.get("expiry_date")).isEqualTo("2029-10-26");
        assertThat(r).doesNotContainKey("error");   // expiry ayrıştırıldı → hata yok
    }

    @Test
    @DisplayName("lookup: .tr + web-whois boş yanıt → hata (source=NONE), parser çağrılmaz")
    void lookup_trWebWhoisEmpty_returnsNoneError() {
        when(psl.tldOf("bos.com.tr")).thenReturn("tr");
        when(trWebWhois.enabled()).thenReturn(true);
        when(trWebWhois.fetch(eq("bos.com.tr"))).thenReturn(new TrWebWhoisClient.Fetched("  ", "isimtescil"));

        Map<String, Object> r = client.lookup("bos.com.tr");

        assertThat(r.get("source")).isEqualTo("NONE");
        assertThat(r.get("error")).isNotNull();
    }
}

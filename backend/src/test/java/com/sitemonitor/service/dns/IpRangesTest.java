package com.sitemonitor.service.dns;

import com.sitemonitor.service.DnsCheckerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * IP / CIDR yardımcıları + beklenen-değer aralığı kuralı (2026-11-18). Tablolar JS ikizi tarafından da okunur
 * ({@code frontend/src/test/dnsIpRanges.test.js}) — iki taraf ayrışamaz; tabloya satır eklerken JS testini de koşun.
 */
class IpRangesTest {

    /** { metin, "range" | "address" | "none" } — {@link IpRanges#parse} / {@link IpRanges#address} hangisini kabul eder. */
    static final String[][] PARSE_TABLE = {
            { "188.114.96.0/20", "range" },
            { "188.114.96.3", "address" },
            { "10.0.0.0/8", "range" },
            { "0.0.0.0/0", "range" },
            { "1.2.3.4/32", "range" },
            { "1.2.3.4/33", "none" },
            { "1.2.3.4/", "none" },
            { "/24", "none" },
            { "1.2.3.4/2a", "none" },
            { "1.2.3.4/24/8", "none" },
            { "010.1.1.1", "none" },
            { "256.1.1.1", "none" },
            { "1.2.3", "none" },
            { "1.2.3.4.5", "none" },
            { "1.2.3.", "none" },
            { "example.com", "none" },
            { "2a06:98c0::/29", "range" },
            { "2a06:98c1:3120::3", "address" },
            { "2606:4700::/32", "range" },
            { "::", "address" },
            { "::1", "address" },
            { "::ffff:188.114.96.3", "address" },
            { "[2001:db8::1]", "address" },
            { "2001:db8::1/129", "none" },
            { "2001:db8::1%eth0", "none" },
            { "2001:db8:::1", "none" },
            { "1:2:3:4:5:6:7:8:9", "none" },
            { "12345::1", "none" },
            { " 188.114.96.3 ", "address" },
            { "188.114.96.3\u00A0", "address" },
            { "\uFEFF188.114.96.0/20", "range" },
            { "188.114.96.0/20\u001F", "range" },
            { "\u2007188.114.96.3\u202F", "address" },
            { "1.2.3.4\u0085", "address" },
            { "188.114.96\u00A0.3", "none" },
            { "::1.2.3.4", "address" },
            { "1.2.3.4::", "none" },
            { "1::2::3", "none" },
            { "::ffff:010.1.1.1", "none" },
            { "1:2:3:4:5:6:7::", "address" },
            { "1:2:3:4:5:6:7:8::", "none" },
            { "1:2:3:4:5:6:1.2.3.4", "address" },
            { ":1::2", "none" },
            { "1::2:", "none" },
            { "[]", "none" },
            { "1.2.3.4/٢٤", "none" },
            { "１.2.3.4", "none" },
            { "", "none" },
    };

    /** { aralık, değer, "true" | "false" } — {@link IpRanges#contains}. */
    static final String[][] MATCH_TABLE = {
            { "188.114.96.0/20", "188.114.96.3", "true" },
            { "188.114.96.0/20", "188.114.97.5", "true" },
            { "188.114.96.0/20", "188.114.111.255", "true" },
            { "188.114.96.0/20", "188.114.112.0", "false" },
            { "188.114.96.0/20", "188.114.95.255", "false" },
            { "188.114.96.3/20", "188.114.99.1", "true" },
            { "10.0.0.0/8", "10.255.255.255", "true" },
            { "10.0.0.0/8", "11.0.0.0", "false" },
            { "1.2.3.4/32", "1.2.3.4", "true" },
            { "1.2.3.4/32", "1.2.3.5", "false" },
            { "0.0.0.0/0", "203.0.113.9", "true" },
            { "188.114.96.0/20", "2a06:98c1:3120::3", "false" },
            { "2a06:98c0::/29", "2a06:98c1:3120::3", "true" },
            { "2a06:98c0::/29", "2a06:98c7:ffff::1", "true" },
            { "2a06:98c0::/29", "2a06:98c8::1", "false" },
            { "2a06:98c0::/29", "188.114.96.3", "false" },
            { "188.114.96.0/20", "::ffff:188.114.96.3", "true" },
            { "2606:4700::/32", "2606:4700:3030::6815:1", "true" },
            { "188.114.96.0/20", "example.com", "false" },
            { "188.114.96.0/20", "", "false" },
            { "bozuk", "188.114.96.3", "false" },
    };

    /** { beklenen liste (satırlar \n), kayıt tipi, sorun kodu ya da null } — {@link DnsExpectedValues#check}. */
    static final String[][] EXPECTED_TABLE = {
            { "188.114.96.0/20", "A", null },
            { "188.114.96.0/20\n188.114.97.3", "A", null },
            { "188.114.96.3\n188.114.97.3", "A", null },
            { "2a06:98c0::/29", "AAAA", null },
            { "188.114.96.0/20", "AAAA", "CIDR_FAMILY" },
            { "2a06:98c0::/29", "A", "CIDR_FAMILY" },
            { "188.114.96.0/7", "A", "CIDR_TOO_BROAD" },
            { "188.114.96.0/8", "A", null },
            { "2a06::/15", "AAAA", "CIDR_TOO_BROAD" },
            { "2a06::/16", "AAAA", null },
            { "188.114.96.0/33", "A", "CIDR_INVALID" },
            { "188.114.96/20", "A", "CIDR_INVALID" },
            { "a/b", "A", "CIDR_INVALID" },
            { "10.0.0.0/8", "MX", "CIDR_RECORD_TYPE" },
            { "10.0.0.0/8", "cname", "CIDR_RECORD_TYPE" },
            { "\"v=spf1 ip4:10.0.0.0/8 -all\"", "TXT", null },
            { "10 mx1.example.com.", "MX", null },
            { "", "A", null },
            { "\n  \n", "A", null },
            { "188.114.96.3", "a", null },
            { "188.114.96.0/20\u00A0", "A", null },
            { "\uFEFF10.0.0.0/4", "A", "CIDR_TOO_BROAD" },
    };

    /** { beklenen liste, canlı değerler (\n), beklenmeyenler (\n) } — {@link DnsCheckerService#unexpectedValues}. */
    static final String[][] UNEXPECTED_TABLE = {
            { "188.114.96.0/20", "188.114.96.3\n188.114.97.3", "" },
            { "188.114.96.0/20", "188.114.96.5\n203.0.113.9", "203.0.113.9" },
            { "188.114.96.3\n188.114.97.3", "188.114.96.5", "188.114.96.5" },
            { "2a06:98c1:3120::3", "2a06:98c1:3120:0:0:0:0:3", "" },
            { "2a06:98c0::/29", "2a06:98c1:3121::3", "" },
            { "188.114.96.0/20\n2a06:98c0::/29", "188.114.97.0\n2a06:98c1:3120::3", "" },
            { "mx1.example.com.", "mx1.example.com.\nmx2.example.com.", "mx2.example.com." },
            { "", "188.114.96.3", "" },
            { "188.114.96.0/20", "", "" },
            { "bozuk/aralik", "188.114.96.3", "188.114.96.3" },
            { "188.114.96.3\u00A0", "188.114.96.3", "" },
            { "\u00A0", "188.114.96.3", "" },
            { "188.114.96.0/20", "\uFEFF188.114.96.9", "" },
    };

    private static List<String> lines(String joined) {
        List<String> out = new ArrayList<>();
        for (String s : joined.split("\n")) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    @Test
    @DisplayName("ayrıştırma tablosu: aralık / tek adres / reddedilen biçimler")
    void parseTable() {
        for (String[] r : PARSE_TABLE) {
            String kind = IpRanges.parse(r[0]) != null ? "range" : IpRanges.address(r[0]) != null ? "address" : "none";
            assertThat(kind).as("'%s'", r[0]).isEqualTo(r[1]);
        }
    }

    @Test
    @DisplayName("içerme tablosu: önek sonrası bitler yok sayılır, aileler karışmaz, eşlenmiş IPv6 = IPv4")
    void matchTable() {
        for (String[] r : MATCH_TABLE) {
            assertThat(IpRanges.contains(r[0], r[1])).as("%s ∋ %s", r[0], r[1]).isEqualTo(Boolean.parseBoolean(r[2]));
        }
    }

    @Test
    @DisplayName("beklenen-değer kuralı: aralık yalnız A / AAAA, doğru aile, yeterince dar; değer satırları denetlenmez")
    void expectedTable() {
        for (String[] r : EXPECTED_TABLE) {
            DnsExpectedValues.Problem p = DnsExpectedValues.check(r[0], r[1]);
            assertThat(p == null ? null : p.code()).as("%s (%s)", r[0], r[1]).isEqualTo(r[2]);
        }
    }

    @Test
    @DisplayName("beklenmeyen değer: aralıktaki adres beklenen, aynı adresin başka yazımı beklenen, aralık dışı raporlanır")
    void unexpectedTable() {
        for (String[] r : UNEXPECTED_TABLE) {
            assertThat(DnsCheckerService.unexpectedValues(r[0], lines(r[1])))
                    .as("beklenen=%s canlı=%s", r[0], r[1]).containsExactlyElementsOf(lines(r[2]));
        }
    }

    @Test
    @DisplayName("withinExpected aralıkla: CDN adresleri değişse de aralıktaysa değişiklik alarmı bastırılır")
    void withinExpectedUsesRanges() {
        assertThat(DnsCheckerService.withinExpected("188.114.96.0/20", List.of("188.114.96.5", "188.114.97.5"))).isTrue();
        assertThat(DnsCheckerService.withinExpected("188.114.96.0/20", List.of("188.114.96.5", "203.0.113.9"))).isFalse();
        assertThat(DnsCheckerService.withinExpected("188.114.96.3", List.of("188.114.96.5"))).isFalse();
        assertThat(DnsCheckerService.withinExpected("\u00A0", List.of("188.114.96.5"))).as("yalnız görünmez karakterli liste = kilit kapalı").isFalse();
    }

    @Test
    @DisplayName("sorun satırı kısaltılmadan döner (ileti sunucuda kısaltılır)")
    void problemCarriesLine() {
        DnsExpectedValues.Problem p = DnsExpectedValues.check("188.114.96.3\n10.0.0.0/4", "A");
        assertThat(p).isEqualTo(new DnsExpectedValues.Problem(DnsExpectedValues.CODE_TOO_BROAD, "10.0.0.0/4"));
    }

    @Test
    @DisplayName("doğrusal ve sınırlı: uzun / kötü niyetli girdi hızlıca reddedilir")
    void bounded() {
        String huge = "1".repeat(200_000) + "/8";
        String many = String.join("\n", java.util.Collections.nCopies(20_000, "188.114.96.0/20"));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            assertThat(IpRanges.parse(huge)).isNull();
            assertThat(IpRanges.address(":".repeat(100_000))).isNull();
            assertThat(DnsExpectedValues.check(many, "A")).isNull();
            assertThat(DnsCheckerService.unexpectedValues(many, List.of("188.114.96.3"))).isEmpty();
        });
    }

    @Test
    @DisplayName("aralık nesnesi: içerme yalnız önek bitlerine bakar; eşlenmiş IPv6 ve farklı yazımlar aynı adres")
    void rangeRecord() {
        IpRanges.Range r = IpRanges.parse("188.114.96.0/20");
        assertThat(r.v4()).isTrue();
        assertThat(r.prefix()).isEqualTo(20);
        assertThat(r.contains(IpRanges.address("188.114.103.200"))).isTrue();
        assertThat(r.contains(null)).isFalse();
        assertThat(Arrays.equals(IpRanges.address("::ffff:1.2.3.4"), IpRanges.address("1.2.3.4"))).isTrue();
        assertThat(IpRanges.sameAddress("2a06:98c1:3120::3", "2a06:98c1:3120:0::3")).isTrue();
        assertThat(IpRanges.sameAddress("1.2.3.4", "1.2.3.5")).isFalse();
    }
}

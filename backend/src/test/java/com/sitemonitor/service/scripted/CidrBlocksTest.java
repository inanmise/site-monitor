package com.sitemonitor.service.scripted;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Kara liste aralığından yalnız vekil adresini açma (2026-10-31, k6 inceleme A6). */
class CidrBlocksTest {

    private static byte[] ip(String s) throws Exception {
        return InetAddress.getByName(s).getAddress();
    }

    private static boolean coveredBy(List<String> cidrs, String addr) throws Exception {
        byte[] a = ip(addr);
        for (String c : cidrs) if (CidrBlocks.contains(CidrBlocks.parse(c), a)) return true;
        return false;
    }

    @Test
    void proxyInside10Slash8_onlyProxyAddressOpens() throws Exception {
        List<String> out = CidrBlocks.excludeAll(List.of("10.0.0.0/8", "127.0.0.0/8"), List.of(ip("10.1.2.3")));
        // vekilin kendisi açık
        assertThat(coveredBy(out, "10.1.2.3")).isFalse();
        // iç ağın geri kalanı HÂLÂ kapalı (eski davranış bütün /8'i açıyordu)
        for (String other : List.of("10.0.0.1", "10.1.2.2", "10.1.2.4", "10.255.255.255", "10.1.3.3", "10.0.0.0")) {
            assertThat(coveredBy(out, other)).as(other).isTrue();
        }
        assertThat(coveredBy(out, "127.0.0.1")).isTrue();
        // /8 → 24 blok + dokunulmayan 127.0.0.0/8 özgün yazımıyla
        assertThat(out).hasSize(25).contains("127.0.0.0/8").doesNotContain("10.0.0.0/8");
    }

    @Test
    void blocksDoNotOverlapAndCoverEverythingButTheAddress() throws Exception {
        CidrBlocks.Cidr range = CidrBlocks.parse("192.168.0.0/16");
        byte[] proxy = ip("192.168.10.20");
        List<CidrBlocks.Cidr> parts = CidrBlocks.exclude(range, proxy);
        assertThat(parts).hasSize(16);
        // toplam adres sayısı = 2^16 - 1
        long total = 0;
        for (CidrBlocks.Cidr c : parts) total += 1L << (32 - c.prefix());
        assertThat(total).isEqualTo((1L << 16) - 1);
        for (CidrBlocks.Cidr a : parts) {
            for (CidrBlocks.Cidr b : parts) {
                if (a == b) continue;
                assertThat(CidrBlocks.contains(a, b.network()) || CidrBlocks.contains(b, a.network())).isFalse();
            }
        }
    }

    @Test
    void addressOutsideRange_rangeUnchanged_originalTextKept() throws Exception {
        List<String> in = List.of("10.0.0.0/8", "172.16.0.0/12");
        assertThat(CidrBlocks.excludeAll(in, List.of(ip("8.8.8.8")))).isEqualTo(in);
        assertThat(CidrBlocks.excludeAll(in, List.of())).isEqualTo(in);
    }

    @Test
    void singleHostRangeEqualToProxyVanishes() throws Exception {
        assertThat(CidrBlocks.excludeAll(List.of("10.1.2.3/32"), List.of(ip("10.1.2.3")))).isEmpty();
    }

    @Test
    void ipv6AndMixedFamilies() throws Exception {
        List<String> out = CidrBlocks.excludeAll(List.of("fc00::/7", "10.0.0.0/8"), List.of(ip("fd00::1")));
        assertThat(coveredBy(out, "fd00::1")).isFalse();
        assertThat(coveredBy(out, "fd00::2")).isTrue();
        assertThat(coveredBy(out, "fc00::1")).isTrue();
        assertThat(out).contains("10.0.0.0/8");             // IPv4 aralığına IPv6 adresi dokunmaz
        assertThat(out).hasSize(121 + 1);                    // /7 → 121 blok
    }

    @Test
    void twoProxyAddresses() throws Exception {
        List<String> out = CidrBlocks.excludeAll(List.of("10.0.0.0/8"), List.of(ip("10.1.2.3"), ip("10.200.0.1")));
        assertThat(coveredBy(out, "10.1.2.3")).isFalse();
        assertThat(coveredBy(out, "10.200.0.1")).isFalse();
        assertThat(coveredBy(out, "10.200.0.2")).isTrue();
        assertThat(coveredBy(out, "10.1.2.4")).isTrue();
    }

    @Test
    void unparsableEntriesKeptAsIs_parseRules() {
        assertThat(CidrBlocks.excludeAll(List.of("garip", "host.example/8"), List.of(new byte[]{10, 1, 2, 3})))
                .containsExactly("garip", "host.example/8");
        assertThat(CidrBlocks.parse(null)).isNull();
        assertThat(CidrBlocks.parse("10.0.0.0")).isNull();
        assertThat(CidrBlocks.parse("10.0.0.0/33")).isNull();
        assertThat(CidrBlocks.parse("10.0.0.0/x")).isNull();
        assertThat(CidrBlocks.parse("10.1.2.3/8").toString()).isEqualTo("10.0.0.0/8");   // önek dışı bitler sıfırlanır
        assertThat(CidrBlocks.contains(null, new byte[4])).isFalse();
    }
}

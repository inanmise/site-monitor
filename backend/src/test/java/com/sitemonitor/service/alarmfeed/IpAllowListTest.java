package com.sitemonitor.service.alarmfeed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Alarm akışı IP izin listesi — saf kurallar (DNS yok, kapalı başarısızlık). */
class IpAllowListTest {

    @Test
    @DisplayName("Girdi doğrulama: tek adres, CIDR, IPv6; ad / eski IPv4 biçimi / taşan önek reddedilir")
    void normalizeEntry() {
        assertThat(IpAllowList.normalizeEntry("10.20.30.40")).isEqualTo("10.20.30.40");
        assertThat(IpAllowList.normalizeEntry(" 10.0.0.0/8 ")).isEqualTo("10.0.0.0/8");
        assertThat(IpAllowList.normalizeEntry("10.20.30.40/32")).isEqualTo("10.20.30.40");
        assertThat(IpAllowList.normalizeEntry("2001:db8::/32")).isEqualTo("2001:db8:0:0:0:0:0:0/32");
        assertThat(IpAllowList.normalizeEntry("2001:DB8::1")).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(IpAllowList.normalizeEntry("localhost")).isNull();          // ad: DNS'e hiç gidilmez
        assertThat(IpAllowList.normalizeEntry("intranet.example.com")).isNull();
        assertThat(IpAllowList.normalizeEntry("10")).isNull();                 // eski tek parça IPv4 biçimi
        assertThat(IpAllowList.normalizeEntry("10.1.2")).isNull();
        assertThat(IpAllowList.normalizeEntry("10.0.0.0/33")).isNull();
        assertThat(IpAllowList.normalizeEntry("10.0.0.0/")).isNull();
        assertThat(IpAllowList.normalizeEntry("10.0.0.0/-1")).isNull();
        assertThat(IpAllowList.normalizeEntry("2001:db8::/129")).isNull();
        assertThat(IpAllowList.normalizeEntry("256.1.1.1")).isNull();
        assertThat(IpAllowList.normalizeEntry("010.0.0.1")).isNull();         // baştaki sıfır (ekran kuralıyla aynı)
        assertThat(IpAllowList.normalizeEntry("10.0.0.01")).isNull();
        assertThat(IpAllowList.normalizeEntry("0.0.0.0/0")).isEqualTo("0.0.0.0/0");
        assertThat(IpAllowList.normalizeEntry("fe80::1%eth0")).isNull();       // kapsam kimliği kabul edilmez
        assertThat(IpAllowList.normalizeEntry("::ffff:10.1.2.3")).isEqualTo("10.1.2.3");
        assertThat(IpAllowList.normalizeEntry("")).isNull();
        assertThat(IpAllowList.normalizeEntry(null)).isNull();
        assertThat(IpAllowList.normalizeEntry("1".repeat(80))).isNull();
    }

    @Test
    @DisplayName("Eşleşme: boş liste herkes; CIDR sınırı; IPv4'e eşlenmiş IPv6; aile karışmaz; okunamayan istemci geçemez")
    void allowed() {
        assertThat(IpAllowList.allowed(List.of(), "1.2.3.4")).isTrue();
        assertThat(IpAllowList.allowed(null, "garbage")).isTrue();
        List<String> l = List.of("10.0.0.0/8", "192.168.1.20", "2001:db8::/32");
        assertThat(IpAllowList.allowed(l, "10.255.1.2")).isTrue();
        assertThat(IpAllowList.allowed(l, "11.0.0.1")).isFalse();
        assertThat(IpAllowList.allowed(l, "192.168.1.20")).isTrue();
        assertThat(IpAllowList.allowed(l, "192.168.1.21")).isFalse();
        assertThat(IpAllowList.allowed(l, "::ffff:10.1.1.1")).isTrue();      // eşlenmiş → IPv4
        assertThat(IpAllowList.allowed(l, "2001:db8:abcd::5")).isTrue();
        assertThat(IpAllowList.allowed(l, "2001:db9::5")).isFalse();
        assertThat(IpAllowList.allowed(l, "unknown")).isFalse();
        assertThat(IpAllowList.allowed(l, null)).isFalse();
        assertThat(IpAllowList.allowed(List.of("172.16.0.0/12"), "172.31.255.255")).isTrue();
        assertThat(IpAllowList.allowed(List.of("172.16.0.0/12"), "172.32.0.0")).isFalse();
        assertThat(IpAllowList.allowed(List.of("0.0.0.0/0"), "8.8.8.8")).isTrue();
    }

    @Test
    @DisplayName("Kayıtlı liste dolu ama okunamaz → hiç kimse geçemez (izin listesi 'herkese açık'a dönüşmez)")
    void unreadableStoredListFailsClosed() {
        List<String> l = AlarmFeedSettings.ipsLenient("not-an-ip, also/bad");
        assertThat(l).containsExactly(IpAllowList.NONE);
        assertThat(IpAllowList.allowed(l, "10.0.0.1")).isFalse();
        assertThat(AlarmFeedSettings.ipsLenient("")).isEmpty();
        assertThat(AlarmFeedSettings.ipsLenient("bad, 10.0.0.1")).containsExactly("10.0.0.1");
    }
}

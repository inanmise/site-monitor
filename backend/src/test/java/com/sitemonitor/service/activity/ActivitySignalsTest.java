package com.sitemonitor.service.activity;

import com.sitemonitor.service.failure.CheckFailureReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ActivitySignals} — neden anahtarı, kaba neden sınıfı ve hedef host'u (2026-11-05, aktivite zenginleştirmesi D + F). */
class ActivitySignalsTest {

    @Test
    @DisplayName("neden anahtarı: kod; yoksa class:<SINIF> (büyük harf); ikisi de yoksa null")
    void reasonKey() {
        assertThat(ActivitySignals.reasonKey("DNS_NXDOMAIN", "NETWORK")).isEqualTo("DNS_NXDOMAIN");
        assertThat(ActivitySignals.reasonKey(null, "network")).isEqualTo("class:NETWORK");
        assertThat(ActivitySignals.reasonKey(" ", null)).isNull();
    }

    @Test
    @DisplayName("sınıf: izleme / keyword / veritabanı kodları ve eski hata sınıfı aynı dört sınıfa iner")
    void classOf() {
        assertThat(ActivitySignals.classOf("DNS_SERVFAIL", null)).isEqualTo("DNS");
        assertThat(ActivitySignals.classOf("DNS", null)).as("keyword DNS kodu").isEqualTo("DNS");
        assertThat(ActivitySignals.classOf("DB_DNS", null)).isEqualTo("DNS");
        assertThat(ActivitySignals.classOf("CONNECT_TIMEOUT", null)).isEqualTo("NETWORK");
        assertThat(ActivitySignals.classOf("READ_TIMEOUT", null)).isEqualTo("NETWORK");
        assertThat(ActivitySignals.classOf("ICMP_NO_REPLY", null)).isEqualTo("NETWORK");
        assertThat(ActivitySignals.classOf("TIMEOUT_CONNECT", null)).isEqualTo("NETWORK");
        assertThat(ActivitySignals.classOf("DB_UNREACHABLE", null)).isEqualTo("NETWORK");
        assertThat(ActivitySignals.classOf("PROXY_ERROR", null)).isEqualTo("PROXY");
        assertThat(ActivitySignals.classOf("PROXY", null)).isEqualTo("PROXY");
        assertThat(ActivitySignals.classOf("TLS_TRUST", null)).isEqualTo("TLS");
        assertThat(ActivitySignals.classOf("TLS_CERT", null)).isEqualTo("TLS");
        assertThat(ActivitySignals.classOf("HTTP_STATUS", "NETWORK")).as("kod sınıftan önce gelir").isEqualTo("OTHER");
        assertThat(ActivitySignals.classOf(null, "SSL")).isEqualTo("TLS");
        assertThat(ActivitySignals.classOf(null, "NETWORK")).isEqualTo("NETWORK");
        assertThat(ActivitySignals.classOf(null, "BLOCKED")).isEqualTo("OTHER");
        assertThat(ActivitySignals.classOf(null, null)).isEqualTo("OTHER");
        // Katalogdaki her kod bir sınıfa iner (istisna yok)
        for (CheckFailureReason r : CheckFailureReason.values()) assertThat(ActivitySignals.classOf(r.name(), null)).isNotNull();
    }

    @Test
    @DisplayName("host: URL, host:port, [IPv6]:port, DNS 'alan_adı TİP', düz alan adı; ad anahtarlı türler null")
    void hostOf() {
        assertThat(ActivitySignals.hostOf("HTTP", "https://Shop.Example.com:8443/sepet?a=1")).isEqualTo("shop.example.com");
        assertThat(ActivitySignals.hostOf("KEYWORD", "https://u:p@a.example.com/x y")).isEqualTo("a.example.com");
        assertThat(ActivitySignals.hostOf("PORT", "b.example.com:443")).isEqualTo("b.example.com");
        assertThat(ActivitySignals.hostOf("UPTIME", "[2001:db8::1]:443")).isEqualTo("2001:db8::1");
        assertThat(ActivitySignals.hostOf("PING", "2001:db8::1")).as("çıplak IPv6 kesilmez").isEqualTo("2001:db8::1");
        assertThat(ActivitySignals.hostOf("DNS", "c.example.com. A")).isEqualTo("c.example.com");
        assertThat(ActivitySignals.hostOf("CERT", "d.example.com")).isEqualTo("d.example.com");
        assertThat(ActivitySignals.hostOf("SCRIPTED", "Sepet akışı")).isNull();
        assertThat(ActivitySignals.hostOf("DBQUERY", "Sipariş sayısı")).isNull();
        assertThat(ActivitySignals.hostOf("HTTP", null)).isNull();
        assertThat(ActivitySignals.hostOf("HTTP", "x".repeat(10_000))).hasSizeLessThanOrEqualTo(500);
    }
}

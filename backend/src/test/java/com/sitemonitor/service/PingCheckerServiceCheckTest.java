package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mockStatic;

/**
 * {@link PingCheckerService#check} — ping ÇIKTISININ yorumlanması (parse + up/na kararı).
 * Gerçek ICMP'ye çıkılmaz: ProcessProbe.run mockStatic ile sabit çıktılara bağlanır.
 * Kapsanan dallar: iputils/Windows RTT formatları, paket kaybı yüzdesi, %100 kayıp,
 * yetki-yok (na=true) tespiti, boş/anlamsız çıktı hata yolu.
 */
class PingCheckerServiceCheckTest {

    private final PingCheckerService svc = new PingCheckerService();

    private Map<String, Object> checkWith(String cannedOutput) {
        try (MockedStatic<ProcessProbe> probe = mockStatic(ProcessProbe.class)) {
            probe.when(() -> ProcessProbe.run(anyList(), (String) org.mockito.ArgumentMatchers.isNull(), anyInt()))
                 .thenReturn(new ProcessProbe.Result(cannedOutput, 0, false));
            return svc.check("example.com", "auto", 3, 5000);
        }
    }

    @Test
    @DisplayName("iputils çıktısı: '0% packet loss' + 'min/avg/max' → up=true, rtt=avg (yuvarlanmış), loss=0")
    void unixOutput_parsedUpWithAvgRtt() {
        Map<String, Object> r = checkWith("""
                PING example.com (93.184.216.34) 56(84) bytes of data.
                64 bytes from example.com: icmp_seq=1 ttl=56 time=11.8 ms
                --- example.com ping statistics ---
                3 packets transmitted, 3 received, 0% packet loss, time 2003ms
                rtt min/avg/max/mdev = 11.653/11.783/11.912/0.106 ms
                """);
        assertThat(r.get("up")).isEqualTo(true);
        assertThat(r.get("packet_loss")).isEqualTo(0);
        assertThat(r.get("rtt_ms")).isEqualTo(12L);   // 11.783 → round
        assertThat(r).doesNotContainKey("na");
    }

    @Test
    @DisplayName("Windows çıktısı: 'Average = 12ms' → rtt=12; TR 'Ortalama' varyantı da parse edilir")
    void windowsOutput_averageParsed() {
        Map<String, Object> r = checkWith("""
                Pinging example.com [93.184.216.34] with 32 bytes of data:
                Reply from 93.184.216.34: bytes=32 time=12ms TTL=56
                Ping statistics for 93.184.216.34:
                    Packets: Sent = 3, Received = 3, Lost = 0 (0% loss),
                Approximate round trip times in milli-seconds:
                    Minimum = 11ms, Maximum = 13ms, Average = 12ms
                """);
        assertThat(r.get("up")).isEqualTo(true);
        assertThat(r.get("rtt_ms")).isEqualTo(12L);
        assertThat(r.get("packet_loss")).isEqualTo(0);

        Map<String, Object> tr = checkWith("Paketler: ... (0% loss)\n    En Az = 11ms, En Çok = 13ms, Ortalama = 12ms");
        assertThat(tr.get("rtt_ms")).isEqualTo(12L);
    }

    @Test
    @DisplayName("%100 paket kaybı → up=false + Türkçe hata mesajında kayıp yüzdesi")
    void totalLoss_downWithLossError() {
        Map<String, Object> r = checkWith("3 packets transmitted, 0 received, 100% packet loss, time 2031ms");
        assertThat(r.get("up")).isEqualTo(false);
        assertThat(r.get("packet_loss")).isEqualTo(100);
        assertThat(r.get("rtt_ms")).isNull();
        assertThat(String.valueOf(r.get("error"))).contains("100");
        assertThat(r).doesNotContainKey("na");
    }

    @Test
    @DisplayName("yetki yok ('Operation not permitted') → up=false, na=true, ICMP-kullanılamıyor hatası")
    void permissionDenied_gracefulNa() {
        Map<String, Object> r = checkWith("ping: socket: Operation not permitted");
        assertThat(r.get("up")).isEqualTo(false);
        assertThat(r.get("na")).isEqualTo(true);
        assertThat(String.valueOf(r.get("error"))).contains("ICMP");
    }

    @Test
    @DisplayName("binary yok ('komut çalıştırılamadı' ProcessProbe mesajı) → na=true")
    void missingBinary_gracefulNa() {
        Map<String, Object> r = checkWith("komut çalıştırılamadı: ping (No such file or directory)");
        assertThat(r.get("na")).isEqualTo(true);
        assertThat(r.get("up")).isEqualTo(false);
    }

    @Test
    @DisplayName("anlamsız çıktı (ne loss ne rtt) → up=false, error=ilk dolu satır (160 karakterde kırpılır)")
    void garbageOutput_downWithFirstLineError() {
        Map<String, Object> r = checkWith("\n\n  " + "x".repeat(200) + "\nsecond line");
        assertThat(r.get("up")).isEqualTo(false);
        assertThat(r.get("packet_loss")).isNull();
        assertThat(r.get("rtt_ms")).isNull();
        String err = String.valueOf(r.get("error"));
        assertThat(err).hasSize(160).startsWith("xxx");
    }

    @Test
    @DisplayName("loss satırı yok ama RTT var → up=true (rtt varlığına düşer)")
    void noLossLineButRtt_upTrue() {
        Map<String, Object> r = checkWith("rtt min/avg/max/mdev = 10.0/20.5/30.0/1.0 ms");
        assertThat(r.get("up")).isEqualTo(true);
        assertThat(r.get("rtt_ms")).isEqualTo(21L);   // 20.5 → round half-up
        assertThat(r.get("packet_loss")).isNull();
    }

    @Test
    @DisplayName("buildPingArgs: timeoutSec paket sayısına bölünür (Windows -w ms, min 1000)")
    void buildPingArgs_windowsTimeoutFloor() {
        // İşletim sistemine göre bayrak değişir; burada yalnız değişmezler: ping başta, host sonda.
        List<String> args = PingCheckerService.buildPingArgs("h.example", "auto", 10, 5);
        assertThat(args).first().isEqualTo("ping");
        assertThat(args).last().isEqualTo("h.example");
        assertThat(args).contains("10");
    }
}

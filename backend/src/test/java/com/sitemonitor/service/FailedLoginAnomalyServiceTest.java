package com.sitemonitor.service;

import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.service.FailedLoginAnomalyService.AnomalyReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Katmanlı anomali kurallarının birim testleri — mock repo (kontrollü sayımlar) + mock AppSettings
 * (açık eşikler). Her kural bağımsız izole edilir (diğer sorgular boş döner) → eşik altı/üstü sınırları.
 */
@ExtendWith(MockitoExtension.class)
class FailedLoginAnomalyServiceTest {

    @Mock AuditLogRepository repo;
    @Mock AppSettingsService appSettings;
    @InjectMocks FailedLoginAnomalyService service;

    private static final String WS = "2026-07-28T10:00:00";
    private static final String WE = "2026-07-28T10:10:00";
    private static final String BASELINE_START = "2026-07-27T10:00:00";   // WS - 24h

    private static Object[] row(String k, long c) { return new Object[]{ k, c }; }

    @BeforeEach
    void setup() {
        // Eşikler — açık (varsayılan değerlerle).
        lenient().when(appSettings.getInt(eq("site.monitor.failed-login.window-minutes"), anyInt())).thenReturn(10);
        lenient().when(appSettings.getInt(eq("site.monitor.failed-login.threshold-total"), anyInt())).thenReturn(20);
        lenient().when(appSettings.getInt(eq("site.monitor.failed-login.threshold-per-account"), anyInt())).thenReturn(5);
        lenient().when(appSettings.getInt(eq("site.monitor.failed-login.threshold-per-ip"), anyInt())).thenReturn(15);
        lenient().when(appSettings.getInt(eq("site.monitor.failed-login.threshold-distinct-users-per-ip"), anyInt())).thenReturn(5);
        lenient().when(appSettings.getInt(eq("site.monitor.failed-login.threshold-distinct-ips-per-account"), anyInt())).thenReturn(5);
        lenient().when(appSettings.getInt(eq("site.monitor.failed-login.baseline-hours"), anyInt())).thenReturn(24);
        lenient().when(appSettings.getInt(eq("site.monitor.failed-login.relative-floor"), anyInt())).thenReturn(8);
        lenient().when(appSettings.getDouble(eq("site.monitor.failed-login.relative-multiplier"), anyDouble())).thenReturn(3.0);

        // Sessiz repo — her test kendi sinyalini ekler.
        lenient().when(repo.countFailedLoginsBetween(anyString(), anyString())).thenReturn(0L);
        lenient().when(repo.countFailedByActorSince(anyString())).thenReturn(List.of());
        lenient().when(repo.countFailedByIpSince(anyString())).thenReturn(List.of());
        lenient().when(repo.countDistinctUsersPerIpSince(anyString())).thenReturn(List.of());
        lenient().when(repo.countDistinctIpsPerActorSince(anyString())).thenReturn(List.of());
        lenient().when(repo.countFailedByReasonLikeBetween(anyString(), anyString(), anyString())).thenReturn(0L);
    }

    private List<String> codes(AnomalyReport r) { return r.hits().stream().map(FailedLoginAnomalyService.RuleHit::code).toList(); }

    @Test
    @DisplayName("boş veri → anomali yok")
    void emptyData_noAnomaly() {
        AnomalyReport r = service.evaluate(WS, WE, 10);
        assertThat(r.anomalous()).isFalse();
        assertThat(r.total()).isZero();
    }

    @Test
    @DisplayName("R1 GLOBAL_VOLUME: eşikte (20) tetiklenir, altında (19) tetiklenmez")
    void globalVolume_boundary() {
        when(repo.countFailedLoginsBetween(WS, WE)).thenReturn(20L);
        assertThat(codes(service.evaluate(WS, WE, 10))).contains("GLOBAL_VOLUME");

        when(repo.countFailedLoginsBetween(WS, WE)).thenReturn(19L);
        assertThat(codes(service.evaluate(WS, WE, 10))).doesNotContain("GLOBAL_VOLUME");
    }

    @Test
    @DisplayName("R2 ACCOUNT_TARGETED: tek hesaba 5 tetiklenir, 4 tetiklenmez")
    void accountTargeted_boundary() {
        when(repo.countFailedByActorSince(WS)).thenReturn(List.<Object[]>of(row("alice", 5)));
        assertThat(codes(service.evaluate(WS, WE, 10))).contains("ACCOUNT_TARGETED");

        when(repo.countFailedByActorSince(WS)).thenReturn(List.<Object[]>of(row("alice", 4)));
        assertThat(codes(service.evaluate(WS, WE, 10))).doesNotContain("ACCOUNT_TARGETED");
    }

    @Test
    @DisplayName("R3 IP_BRUTE_FORCE: tek IP'den 15 tetiklenir, 14 tetiklenmez")
    void ipBruteForce_boundary() {
        when(repo.countFailedByIpSince(WS)).thenReturn(List.<Object[]>of(row("1.2.3.4", 15)));
        assertThat(codes(service.evaluate(WS, WE, 10))).contains("IP_BRUTE_FORCE");

        when(repo.countFailedByIpSince(WS)).thenReturn(List.<Object[]>of(row("1.2.3.4", 14)));
        assertThat(codes(service.evaluate(WS, WE, 10))).doesNotContain("IP_BRUTE_FORCE");
    }

    @Test
    @DisplayName("R4 IP_CREDENTIAL_STUFFING: tek IP 5 farklı kullanıcı tetiklenir")
    void credentialStuffing() {
        when(repo.countDistinctUsersPerIpSince(WS)).thenReturn(List.<Object[]>of(row("9.9.9.9", 5)));
        assertThat(codes(service.evaluate(WS, WE, 10))).contains("IP_CREDENTIAL_STUFFING");
    }

    @Test
    @DisplayName("R5 DISTRIBUTED: tek hesap 5 farklı IP tetiklenir")
    void distributed() {
        when(repo.countDistinctIpsPerActorSince(WS)).thenReturn(List.<Object[]>of(row("bob", 5)));
        assertThat(codes(service.evaluate(WS, WE, 10))).contains("DISTRIBUTED");
    }

    @Test
    @DisplayName("R6 RELATIVE_SPIKE: tabanın 3 katı + zemin üstü tetiklenir; zemin altında tetiklenmez")
    void relativeSpike() {
        // taban: 288 / (24*60/10=144 kova) = 2/pencere. total=8 → 8>=floor(8) ve 8>=3*2=6 → HIT
        when(repo.countFailedLoginsBetween(BASELINE_START, WS)).thenReturn(288L);
        when(repo.countFailedLoginsBetween(WS, WE)).thenReturn(8L);
        AnomalyReport hit = service.evaluate(WS, WE, 10);
        assertThat(hit.baselineAvgPerWindow()).isEqualTo(2.0);
        assertThat(codes(hit)).contains("RELATIVE_SPIKE");

        // zemin altı: total=5 (< floor 8) → tetiklenmez (küçük sayı gürültüsü)
        when(repo.countFailedLoginsBetween(WS, WE)).thenReturn(5L);
        assertThat(codes(service.evaluate(WS, WE, 10))).doesNotContain("RELATIVE_SPIKE");
    }

    @Test
    @DisplayName("R6 taban 1 KOVADAN AZ olduğunda da yaşar — tamsayı bölmesi kuralı ölü koda çeviriyordu")
    void relativeSpikeSurvivesSubBucketBaseline() {
        // Y6 (2026-09-23): varsayılanlarda kova sayısı 144. Taban 143 iken eski kod 143/144 = 0
        // hesaplıyor, `baselineAvg > 0` kapısı kapanıyor ve R6 HİÇ tetiklenmiyordu — yani kural
        // ancak günde 144+ başarısız giriş varken canlanıyordu. Ondalıkla taban 0,993 çıkar.
        when(repo.countFailedLoginsBetween(BASELINE_START, WS)).thenReturn(143L);
        when(repo.countFailedLoginsBetween(WS, WE)).thenReturn(8L);          // zemin 8, çarpan 3
        AnomalyReport hit = service.evaluate(WS, WE, 10);
        assertThat(hit.baselineAvgPerWindow()).isCloseTo(0.993, within(0.001));
        assertThat(codes(hit)).contains("RELATIVE_SPIKE");
    }

    @Test
    @DisplayName("R6 eşiği AŞAĞI yuvarlamıyor — taban 6,94 iken eşik 3×6 değil 3×6,94")
    void relativeSpikeThresholdIsNotFloored() {
        // 1000 / 144 = 6,944 → efektif eşik ceil(3 × 6,944) = 21. Eski tamsayı tabanı 6 verip
        // eşiği 18'e düşürüyordu, yani %14 erken tetikliyordu.
        when(repo.countFailedLoginsBetween(BASELINE_START, WS)).thenReturn(1000L);
        when(repo.countFailedLoginsBetween(WS, WE)).thenReturn(19L);
        assertThat(codes(service.evaluate(WS, WE, 10))).doesNotContain("RELATIVE_SPIKE");
        when(repo.countFailedLoginsBetween(WS, WE)).thenReturn(21L);
        assertThat(codes(service.evaluate(WS, WE, 10))).contains("RELATIVE_SPIKE");
    }

    @Test
    @DisplayName("rulesSignature: tetiklenen kod setinin sıralı kararlı imzası")
    void rulesSignature_sorted() {
        when(repo.countFailedLoginsBetween(WS, WE)).thenReturn(30L);                 // GLOBAL_VOLUME
        when(repo.countFailedByActorSince(WS)).thenReturn(List.<Object[]>of(row("alice", 9)));   // ACCOUNT_TARGETED
        AnomalyReport r = service.evaluate(WS, WE, 10);
        assertThat(r.anomalous()).isTrue();
        assertThat(r.rulesSignature()).isEqualTo("ACCOUNT_TARGETED,GLOBAL_VOLUME");
    }

    @Test
    @DisplayName("reason dağılımı: makine-kod önekleri sayılır, kalan OTHER")
    void reasonDistribution() {
        when(repo.countFailedLoginsBetween(WS, WE)).thenReturn(10L);
        when(repo.countFailedByReasonLikeBetween(eq("BAD_PASSWORD:%"), anyString(), anyString())).thenReturn(6L);
        when(repo.countFailedByReasonLikeBetween(eq("UNKNOWN_USER:%"), anyString(), anyString())).thenReturn(3L);
        AnomalyReport r = service.evaluate(WS, WE, 10);
        assertThat(r.reasonDistribution()).containsEntry("BAD_PASSWORD", 6L)
                .containsEntry("UNKNOWN_USER", 3L)
                .containsEntry("OTHER", 1L);
    }
}

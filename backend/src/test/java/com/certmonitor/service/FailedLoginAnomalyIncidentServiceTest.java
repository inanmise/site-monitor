package com.certmonitor.service;

import com.certmonitor.model.LoginAnomalyIncident;
import com.certmonitor.repository.LoginAnomalyIncidentRepository;
import com.certmonitor.service.FailedLoginAnomalyService.AnomalyReport;
import com.certmonitor.service.FailedLoginAnomalyService.RuleHit;
import com.certmonitor.service.FailedLoginAnomalyIncidentService.Window;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Incident lifecycle: yeni/escalation/realert/bastırma/resolved + pencere catch-up + cooldown. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FailedLoginAnomalyIncidentServiceTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mock FailedLoginAnomalyService detector;
    @Mock LoginAnomalyIncidentRepository incidentRepo;
    @Mock SecurityMailDispatcher dispatcher;
    @Mock AppSettingsService appSettings;
    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks FailedLoginAnomalyIncidentService service;

    private static final Instant NOW = Instant.parse("2026-07-28T10:30:00Z");
    private static final String NOW_ISO = "2026-07-28T10:30:00";

    @BeforeEach
    void setup() {
        when(appSettings.getInt(eq("cert.monitor.failed-login.window-minutes"), anyInt())).thenReturn(10);
        when(appSettings.getInt(eq("cert.monitor.failed-login.catchup-cap-minutes"), anyInt())).thenReturn(60);
        when(appSettings.getInt(eq("cert.monitor.failed-login.cooldown-minutes"), anyInt())).thenReturn(60);
        when(appSettings.getBoolean(eq("cert.monitor.failed-login.resolved-email-enabled"), anyBoolean())).thenReturn(true);
        when(appSettings.getCsv(eq("cert.monitor.failed-login.alert-recipients"), anyString())).thenReturn(List.of());
        when(appSettings.getString(eq("cert.monitor.system-admin.email"), any())).thenReturn("admin@x");
        when(incidentRepo.save(any())).thenAnswer(i -> {
            LoginAnomalyIncident inc = i.getArgument(0);
            if (inc.getId() == null) inc.setId(1L);
            return inc;
        });
    }

    private static AnomalyReport report(long total, String... ruleCodes) {
        List<RuleHit> hits = Arrays.stream(ruleCodes).map(c -> new RuleHit(c, total, 1, "")).toList();
        return new AnomalyReport("2026-07-28T10:20:00", NOW_ISO, 10, total, 0,
                hits, List.of(), List.of(), List.of(), List.of(), Map.of());
    }
    private static AnomalyReport quiet() {
        return new AnomalyReport("2026-07-28T10:20:00", NOW_ISO, 10, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
    }
    private static LoginAnomalyIncident openIncident(String signature, long peak, String lastAlertAt) {
        LoginAnomalyIncident inc = new LoginAnomalyIncident();
        inc.setId(7L); inc.setResolved(false); inc.setOpenedAt("2026-07-28T09:00:00");
        inc.setRulesSignature(signature); inc.setPeakTotal(peak); inc.setLastAlertAt(lastAlertAt);
        return inc;
    }

    @Test
    @DisplayName("yeni anomali → incident açılır + INITIAL mail")
    void newAnomaly_opensIncident_sendsInitial() {
        when(incidentRepo.findFirstByResolvedFalseOrderByOpenedAtDesc()).thenReturn(Optional.empty());
        service.handle(report(20, "GLOBAL_VOLUME"), NOW);
        verify(incidentRepo).save(any());
        verify(dispatcher).dispatchAlert(any(), any(), eq("INITIAL"), any());
    }

    @Test
    @DisplayName("süren anomali + cooldown içinde + aynı imza + hacim katlanmadı → BASTIR (mail yok)")
    void ongoing_withinCooldown_suppressed() {
        String recent = ISO.format(NOW.minusSeconds(10 * 60));   // 10 dk önce → cooldown(60) dolmadı
        when(incidentRepo.findFirstByResolvedFalseOrderByOpenedAtDesc())
                .thenReturn(Optional.of(openIncident("GLOBAL_VOLUME", 20, recent)));
        service.handle(report(20, "GLOBAL_VOLUME"), NOW);
        verify(dispatcher, never()).dispatchAlert(any(), any(), any(), any());
        verify(incidentRepo).save(any());   // durum yine de güncellenir
    }

    @Test
    @DisplayName("süren anomali + YENİ kural → ESCALATION (bastırma delinir)")
    void ongoing_newRule_escalates() {
        String recent = ISO.format(NOW.minusSeconds(10 * 60));
        when(incidentRepo.findFirstByResolvedFalseOrderByOpenedAtDesc())
                .thenReturn(Optional.of(openIncident("GLOBAL_VOLUME", 20, recent)));
        service.handle(report(20, "GLOBAL_VOLUME", "ACCOUNT_TARGETED"), NOW);
        verify(dispatcher).dispatchAlert(any(), any(), eq("ESCALATION"), any());
    }

    @Test
    @DisplayName("süren anomali + hacim ≥ 2× zirve → ESCALATION")
    void ongoing_volumeDoubled_escalates() {
        String recent = ISO.format(NOW.minusSeconds(10 * 60));
        when(incidentRepo.findFirstByResolvedFalseOrderByOpenedAtDesc())
                .thenReturn(Optional.of(openIncident("GLOBAL_VOLUME", 20, recent)));
        service.handle(report(40, "GLOBAL_VOLUME"), NOW);    // 40 >= 2*20
        verify(dispatcher).dispatchAlert(any(), any(), eq("ESCALATION"), any());
    }

    @Test
    @DisplayName("süren anomali + cooldown doldu + aynı imza → REALERT")
    void ongoing_cooldownElapsed_realerts() {
        String old = ISO.format(NOW.minusSeconds(61 * 60));   // 61 dk önce → cooldown(60) doldu
        when(incidentRepo.findFirstByResolvedFalseOrderByOpenedAtDesc())
                .thenReturn(Optional.of(openIncident("GLOBAL_VOLUME", 20, old)));
        service.handle(report(20, "GLOBAL_VOLUME"), NOW);
        verify(dispatcher).dispatchAlert(any(), any(), eq("REALERT"), any());
    }

    @Test
    @DisplayName("anomali bitti + açık incident → resolve + RESOLVED mail")
    void anomalyEnds_resolvesAndSendsResolved() {
        when(incidentRepo.findFirstByResolvedFalseOrderByOpenedAtDesc())
                .thenReturn(Optional.of(openIncident("GLOBAL_VOLUME", 20, NOW_ISO)));
        service.handle(quiet(), NOW);
        verify(incidentRepo).save(argThat(LoginAnomalyIncident::isResolved));
        verify(dispatcher).dispatchResolved(any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("anomali bitti + resolved-email kapalı → resolve ama mail YOK")
    void anomalyEnds_resolvedEmailDisabled_noEmail() {
        when(appSettings.getBoolean(eq("cert.monitor.failed-login.resolved-email-enabled"), anyBoolean())).thenReturn(false);
        when(incidentRepo.findFirstByResolvedFalseOrderByOpenedAtDesc())
                .thenReturn(Optional.of(openIncident("GLOBAL_VOLUME", 20, NOW_ISO)));
        service.handle(quiet(), NOW);
        verify(incidentRepo).save(argThat(LoginAnomalyIncident::isResolved));
        verify(dispatcher, never()).dispatchResolved(any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("anomali yok + açık incident yok → hiçbir şey yapılmaz")
    void noAnomaly_noIncident_noop() {
        when(incidentRepo.findFirstByResolvedFalseOrderByOpenedAtDesc()).thenReturn(Optional.empty());
        service.handle(quiet(), NOW);
        verify(incidentRepo, never()).save(any());
        verifyNoInteractions(dispatcher);
    }

    // ── Pencere kaçırmama (lastScanAt catch-up) ─────────────────────────────────
    @Test
    @DisplayName("computeWindow: lastScanAt nominal başlangıçtan önce → geriye uzatılır (catch-up)")
    void window_catchUp() {
        Window w = service.computeWindow("2026-07-28T10:00:00", NOW);   // 30 dk önce
        assertThat(w.start()).isEqualTo("2026-07-28T10:00:00");
        assertThat(w.end()).isEqualTo(NOW_ISO);
    }

    @Test
    @DisplayName("computeWindow: çok eski lastScanAt → catchup-cap ile sınırlanır")
    void window_cap() {
        Window w = service.computeWindow("2026-07-28T09:00:00", NOW);   // 90 dk önce, cap 60
        assertThat(w.start()).isEqualTo("2026-07-28T09:30:00");         // now - 60 dk
    }

    @Test
    @DisplayName("computeWindow: lastScanAt yok → nominal pencere (now - window)")
    void window_nominal() {
        Window w = service.computeWindow(null, NOW);
        assertThat(w.start()).isEqualTo("2026-07-28T10:20:00");         // now - 10 dk
    }
}

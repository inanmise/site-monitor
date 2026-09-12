package com.sitemonitor.service;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
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
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Vade takvimi gövdesi (2026-09-12): eşik/lead ayarları, renew_by, kapsam yüklemi, plan durumu, yenileme geçmişi. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RenewalForecastServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mock CertificateService certificateService;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock AlertThresholdRepository thresholdRepo;
    @Mock AppSettingsService appSettings;
    @Mock JdbcTemplate jdbc;
    @InjectMocks RenewalForecastService service;

    private static CertificateDto dto(String domain, String notAfter, Integer days, Long team, Integer tier) {
        CertificateDto d = new CertificateDto();
        d.setDomain(domain); d.setNotAfter(notAfter); d.setDaysRemaining(days); d.setTeamId(team); d.setTier(tier);
        d.setStatus("valid"); d.setCheckedAt("2026-09-12T10:00:00"); d.setFingerprint("AA");
        return d;
    }

    @BeforeEach
    void setUp() {
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(appSettings.getInt(RenewalForecastService.KEY_LEAD_DEFAULT, 14)).thenReturn(14);
        when(appSettings.getInt(RenewalForecastService.KEY_LEAD_T1, 0)).thenReturn(30);
        AlertThreshold t = new AlertThreshold(); t.setWarningDays(45); t.setHighDays(20); t.setCriticalDays(10); t.setActive(true);
        when(thresholdRepo.findAll()).thenReturn(List.of(t));
        when(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()).thenReturn(List.of());
    }

    @Test
    @DisplayName("eşikler etkin alarm eşiğinden; lead tier başına (T1=30, diğerleri genel 14); renew_by = bitiş − lead")
    void thresholdsAndLead() {
        assertThat(service.thresholds()).containsEntry("warning", 45).containsEntry("high", 20).containsEntry("critical", 10);
        Map<String, Integer> lead = service.leadDays();
        assertThat(lead).containsEntry("default", 14).containsEntry("t1", 30).containsEntry("t2", 14);
        assertThat(service.leadFor(1, lead)).isEqualTo(30);
        assertThat(service.leadFor(null, lead)).isEqualTo(14);
        assertThat(RenewalForecastService.renewBy("2026-10-23T23:59:59", 30)).isEqualTo("2026-09-23");
        assertThat(RenewalForecastService.renewBy("2026-10-23", 14)).isEqualTo("2026-10-09");
        assertThat(RenewalForecastService.renewBy(null, 14)).isNull();
    }

    @Test
    @DisplayName("build: kapsam dışı takım satırı düşer; süresi dolmuş satır KALIR (sayfa artık overdue gösterir); plan durumu none/planned/done")
    void buildScopeAndPlan() {
        CertificateInventory planned = new CertificateInventory(); planned.setDomain("p.example.com"); planned.setTeamId(5L); planned.setRenewalPlannedAt("2026-09-20");
        CertificateInventory done = new CertificateInventory(); done.setDomain("d.example.com"); done.setTeamId(5L); done.setRenewalPlannedAt("2026-09-01");
        when(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()).thenReturn(List.of(planned, done));
        CertificateDto dd = dto("d.example.com", "2027-09-01T00:00:00", 354, 5L, 2); dd.setNotBefore("2026-09-05T00:00:00");
        when(certificateService.getAllLatestForTeams(any())).thenReturn(List.of(
                dto("a.example.com", "2026-10-23T23:59:59", 41, 5L, 1),
                dto("expired.example.com", "2026-09-01T00:00:00", -11, 5L, null),
                dto("foreign.example.com", "2026-10-01T00:00:00", 19, 9L, 1),
                dto("p.example.com", "2026-10-05T00:00:00", 23, 5L, null),
                dd));
        Map<String, Object> out = service.build(List.of(5L), t -> t != null && t == 5L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> certs = (List<Map<String, Object>>) out.get("certs");
        assertThat(certs).extracting(c -> c.get("domain")).containsExactly("a.example.com", "expired.example.com", "p.example.com", "d.example.com");
        Map<String, Object> a = certs.get(0);
        assertThat(a.get("lead_days")).isEqualTo(30);
        assertThat(a.get("renew_by")).isEqualTo("2026-09-23");
        assertThat(a.get("renewal_plan_state")).isEqualTo("none");
        assertThat(certs.get(2).get("renewal_plan_state")).isEqualTo("planned");
        assertThat(certs.get(3).get("renewal_plan_state")).isEqualTo("done");
        assertThat(out.get("data_as_of")).isEqualTo("2026-09-12T10:00:00");
        assertThat(((Map<?, ?>) out.get("thresholds")).get("critical")).isEqualTo(10);
    }

    @Test
    @DisplayName("renewals: parmak izi grupları → geçiş = yenileme; önceki bitiş − lead öncesiyse zamanında; aylık kırılım; pencere dışı sayılmaz")
    void renewalsFromFingerprintGroups() {
        String recent = ISO.format(Instant.now().minus(10, ChronoUnit.DAYS));
        String old = ISO.format(Instant.now().minus(200, ChronoUnit.DAYS));
        String oldPrev = ISO.format(Instant.now().minus(300, ChronoUnit.DAYS));
        doAnswer(inv -> {
            RowCallbackHandler h = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            // a: eski sertifika bitişi 40 gün sonra, yeni sertifika 10 gün önce görüldü, lead 14 → zamanında (10 gün önce ≤ bitiş−14)
            String prevNotAfterA = ISO.format(Instant.now().plus(40, ChronoUnit.DAYS));
            // b: eski sertifika bitişi 3 gün sonra, yenileme 10 gün önce, lead 14 → geç (renew_by 11 gün önceydi)
            String prevNotAfterB = ISO.format(Instant.now().plus(3, ChronoUnit.DAYS));
            String[][] rows = {
                {"a.example.com", "F1", oldPrev, prevNotAfterA}, {"a.example.com", "F2", recent, "2027-01-01T00:00:00"},
                {"b.example.com", "G1", oldPrev, prevNotAfterB}, {"b.example.com", "G2", recent, "2027-01-01T00:00:00"},
                {"c.example.com", "H1", oldPrev, "2026-01-01T00:00:00"}, {"c.example.com", "H2", old, "2027-01-01T00:00:00"},   // pencere dışı
                {"zz.example.com", "Z1", recent, "2027-01-01T00:00:00"},   // istenen listede değil
            };
            for (String[] r : rows) {
                when(rs.getString("domain")).thenReturn(r[0]); when(rs.getString("fingerprint")).thenReturn(r[1]);
                when(rs.getString("first_seen")).thenReturn(r[2]); when(rs.getString("not_after")).thenReturn(r[3]);
                h.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        Map<String, Object> r = service.renewals(List.of("a.example.com", "b.example.com", "c.example.com"), Map.of(), Map.of("default", 14));
        assertThat(r.get("on_time")).isEqualTo(1);
        assertThat(r.get("late")).isEqualTo(1);
        @SuppressWarnings("unchecked") List<Map<String, Object>> ev = (List<Map<String, Object>>) r.get("events");
        assertThat(ev).extracting(e -> e.get("domain")).containsExactlyInAnyOrder("a.example.com", "b.example.com");
        @SuppressWarnings("unchecked") List<Map<String, Object>> months = (List<Map<String, Object>>) r.get("months");
        assertThat(months).hasSize(1);
        assertThat(months.get(0).get("on_time")).isEqualTo(1);
        assertThat(months.get(0).get("late")).isEqualTo(1);
    }
}

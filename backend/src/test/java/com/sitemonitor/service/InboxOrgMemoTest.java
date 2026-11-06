package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeakAlgorithmException;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeakAlgorithmExceptionRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import com.sitemonitor.util.TtlMemo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bildirim kutusu kurum geneli bellek (performans 2026-11-06, PF2 #1): kullanıcıdan BAĞIMSIZ ham veri pod başına kısa süre
 * paylaşılır, kapsam süzgeci HER çağrıda bellekte uygulanır. Kanıtlar: (1) iki farklı kapsam TTL içinde tek kurum okuması,
 * (2) çıktı belleksiz hesapla birebir, (3) kullanıcılar arası sızıntı yok (önce geniş kapsam doldursa da dar kapsam yalnız
 * kendisininkini görür), (4) eksik (bir bloğu okunamayan) görüntü saklanmaz, (5) kullanıcıya özgü bloklar (haftalık rapor)
 * bellekte değil, (6) 24 saat penceresi çağrı anından.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InboxOrgMemoTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    @Mock AlertEventRepository alertEventRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock MaintenanceWindowRepository maintenanceRepo;
    @Mock MaintenanceService maintenanceService;
    @Mock WeakAlgorithmExceptionRepository exceptionRepo;
    @Mock WeeklyReportRepository weeklyReportRepo;
    @Mock AppSettingsService appSettings;
    @Mock TeamRepository teamRepo;
    @Mock MonitorRefResolver resolver;
    long[] clock = {1_000_000L};

    private static AlertEvent ev(long id, String domain, Long team, String level, boolean resolved, int resolvedHoursAgo) {
        AlertEvent e = new AlertEvent(); e.setId(id); e.setDomain(domain); e.setTeamId(team); e.setAlertLevel(level); e.setAlertType("HTTP_DOWN");
        e.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS))); e.setResolved(resolved);
        if (resolved) e.setResolvedAt(ISO.format(Instant.now().minus(resolvedHoursAgo, ChronoUnit.HOURS)));
        return e;
    }

    private InboxService service(long cacheMs) {
        InboxService s = new InboxService(alertEventRepo, inventoryRepo, maintenanceRepo, maintenanceService, exceptionRepo, weeklyReportRepo, appSettings);
        ReflectionTestUtils.setField(s, "teamRepo", teamRepo);
        ReflectionTestUtils.setField(s, "monitorRefResolver", resolver);
        ReflectionTestUtils.setField(s, "cacheMs", cacheMs);
        ReflectionTestUtils.setField(s, "orgMemo", new TtlMemo<InboxService.OrgInbox>(4, () -> clock[0]));
        return s;
    }

    @BeforeEach
    void setUp() {
        // a: takım 1 (SY) · b: takım 2 (SY) · ug: SY 2, UG 3 · teamless.example.com envanterde yok
        when(inventoryRepo.findActiveDomainTeams()).thenReturn(List.<Object[]>of(
                new Object[]{"a.example.com", 1L, null}, new Object[]{"b.example.com", 2L, null}, new Object[]{"ug.example.com", 2L, 3L}));
        Team t1 = new Team(); t1.setId(1L); t1.setName("Takım A");
        Team t2 = new Team(); t2.setId(2L); t2.setName("Takım B");
        when(teamRepo.findAll()).thenReturn(List.of(t1, t2));
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(
                ev(1, "a.example.com", 1L, "CRITICAL", false, 0), ev(2, "b.example.com", 2L, "WARNING", false, 0),
                ev(3, "ug.example.com", 2L, "HIGH", false, 0), ev(4, "teamless.example.com", null, "WARNING", false, 0)));
        when(alertEventRepo.findByResolvedAtGreaterThanEqual(anyString())).thenReturn(List.of(
                ev(5, "a.example.com", 1L, "WARNING", true, 3), ev(6, "b.example.com", 2L, "WARNING", true, 5)));
        when(resolver.resolve(any())).thenAnswer(inv -> {
            Map<Long, MonitorRefResolver.Ref> m = new java.util.HashMap<>();
            for (AlertEvent e : (List<AlertEvent>) inv.getArgument(0)) m.put(e.getId(), new MonitorRefResolver.Ref("İzleme " + e.getId(), "http", "http", e.getId() * 10));
            return m;
        });
        MaintenanceWindow w1 = new MaintenanceWindow(); w1.setId(7L); w1.setName("Takım A bakımı"); w1.setTeamId(1L); w1.setActive(true);
        w1.setStartAt(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        MaintenanceWindow w2 = new MaintenanceWindow(); w2.setId(8L); w2.setName("Takım B bakımı"); w2.setTeamId(2L); w2.setActive(true);
        w2.setStartAt(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        when(maintenanceRepo.findByActiveTrue()).thenReturn(List.of(w1, w2));
        when(maintenanceService.isActiveAt(any(), any())).thenReturn(true);
        WeakAlgorithmException ex = new WeakAlgorithmException(); ex.setDomain("b.example.com"); ex.setUntil(LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()); ex.setReason("plan");
        when(exceptionRepo.findAll()).thenReturn(List.of(ex));
        String todayCode = LocalDate.now(ZoneId.of("Europe/Istanbul")).getDayOfWeek().name().substring(0, 3);
        when(appSettings.getString(eq(WeeklyReportDeadline.KEY_DAY), any())).thenReturn(todayCode);
        when(appSettings.getString(eq(WeeklyReportDeadline.KEY_TIME), any())).thenReturn("15:00");
        when(weeklyReportRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());
    }

    private static final Predicate<Long> TEAM1 = t -> t != null && t == 1L;
    private static final Predicate<Long> TEAM3 = t -> t != null && t == 3L;
    private static final Predicate<Long> ALL = t -> true;

    @Test
    @DisplayName("iki farklı kapsam TTL içinde: kurum verisi TEK kez okunur; çıktı belleksiz hesapla birebir")
    void sharedOrgReadAndIdenticalOutput() {
        InboxService cached = service(15_000L);
        List<InboxService.Item> a1 = cached.build(TEAM1, List.of(1L));
        List<InboxService.Item> a3 = cached.build(TEAM3, List.of(3L));
        List<InboxService.Item> all = cached.build(ALL, List.of());
        verify(alertEventRepo, times(1)).findAllOpenOrderBySeverity();
        verify(alertEventRepo, times(1)).findByResolvedAtGreaterThanEqual(anyString());
        verify(inventoryRepo, times(1)).findActiveDomainTeams();
        verify(teamRepo, times(1)).findAll();
        verify(resolver, times(1)).resolve(any());
        verify(maintenanceRepo, times(1)).findByActiveTrue();
        verify(exceptionRepo, times(1)).findAll();

        InboxService plain = service(0L);   // kâhin: bellek kapalı
        assertThat(a1).isEqualTo(plain.build(TEAM1, List.of(1L)));
        assertThat(a3).isEqualTo(plain.build(TEAM3, List.of(3L)));
        assertThat(all).isEqualTo(plain.build(ALL, List.of()));
    }

    @Test
    @DisplayName("sızıntı yok: önce global kullanıcı doldursa da kapsamlı kullanıcı yalnız kendi takımının / UG envanterinin satırlarını görür")
    void noCrossUserLeak() {
        InboxService s = service(15_000L);
        assertThat(s.build(ALL, List.of())).extracting(InboxService.Item::key)
                .contains("alert:1", "alert:2", "alert:3", "maint:8:active")
                .doesNotContain("alert:4");   // takımsız + envanter dışı alarm kimseye görünmez (eski kural)
        List<InboxService.Item> t1 = s.build(TEAM1, List.of(1L));
        assertThat(t1).extracting(InboxService.Item::key)
                .contains("alert:1", "maint:7:active").anyMatch(k -> k.startsWith("resolved:5:"))
                .doesNotContain("alert:2", "alert:3", "alert:4", "maint:8:active")
                .noneMatch(k -> k.startsWith("resolved:6:") || k.startsWith("exception:"));
        // UG takımı (3): yalnız UG olduğu envanterin alarmı; takım 2'nin kendi alarmı / bakımı / istisnası YOK
        List<InboxService.Item> t3 = s.build(TEAM3, List.of(3L));
        assertThat(t3).extracting(InboxService.Item::key).contains("alert:3")
                .doesNotContain("alert:1", "alert:2", "alert:4", "maint:7:active", "maint:8:active")
                .noneMatch(k -> k.startsWith("exception:"));
        // Haftalık rapor satırı kullanıcının KENDİ takımlarından (bellekte değil)
        assertThat(t1).extracting(InboxService.Item::key).anyMatch(k -> k.startsWith("weekly:1:"));
        assertThat(t3).extracting(InboxService.Item::key).anyMatch(k -> k.startsWith("weekly:3:")).noneMatch(k -> k.startsWith("weekly:1:"));
        verify(alertEventRepo, times(1)).findAllOpenOrderBySeverity();
    }

    @Test
    @DisplayName("TTL dolunca yeniden okunur; bir bloğu okunamayan görüntü SAKLANMAZ (sonraki çağrı yeniden dener)")
    void ttlAndIncompleteSnapshotNotStored() {
        InboxService s = service(15_000L);
        s.build(TEAM1, List.of(1L));
        clock[0] += 16_000;
        s.build(TEAM1, List.of(1L));
        verify(alertEventRepo, times(2)).findAllOpenOrderBySeverity();

        when(exceptionRepo.findAll()).thenThrow(new IllegalStateException("db"));
        clock[0] += 16_000;
        List<InboxService.Item> degraded = s.build(TEAM1, List.of(1L));
        assertThat(degraded).extracting(InboxService.Item::key).contains("alert:1");   // diğer bloklar yine var
        s.build(TEAM1, List.of(1L));                                                     // TTL içinde ama eksik → yeniden
        verify(alertEventRepo, times(4)).findAllOpenOrderBySeverity();
    }

    @Test
    @DisplayName("24 saat penceresi ÇAĞRI anından: bellekteki listede pencere dışına düşmüş çözülen alarm gösterilmez")
    void resolvedWindowAppliedAtCallTime() {
        InboxService s = service(15_000L);
        AlertEvent stale = ev(9, "a.example.com", 1L, "WARNING", true, 0);
        stale.setResolvedAt(ISO.format(Instant.now().minus(25, ChronoUnit.HOURS)));   // pencere dışı (bellekteki eski kesimle okunmuş gibi)
        InboxService.Item item = new InboxService.Item("resolved:9:" + stale.getResolvedAt(), "alert_resolved", "OK", "a.example.com",
                "HTTP_DOWN", stale.getResolvedAt(), "alerthistory", Map.of());
        InboxService.Item fresh = new InboxService.Item("resolved:5:x", "alert_resolved", "OK", "a.example.com",
                "HTTP_DOWN", ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)), "alerthistory", Map.of());
        InboxService.OrgInbox org = new InboxService.OrgInbox(List.<Object[]>of(new Object[]{"a.example.com", 1L, null}), Map.of(),
                List.of(), List.of(new InboxService.AlertRow(item, 1L, "a.example.com", stale.getResolvedAt()),
                        new InboxService.AlertRow(fresh, 1L, "a.example.com", fresh.at())),
                List.of(), List.of(), true);
        @SuppressWarnings("unchecked") TtlMemo<InboxService.OrgInbox> memo = (TtlMemo<InboxService.OrgInbox>) ReflectionTestUtils.getField(s, "orgMemo");
        memo.get("org", 15_000L, true, () -> org);   // bellek elle dolduruldu
        assertThat(s.build(TEAM1, List.of())).extracting(InboxService.Item::key).contains("resolved:5:x").doesNotContain(item.key());
        verify(alertEventRepo, times(0)).findAllOpenOrderBySeverity();
    }
}

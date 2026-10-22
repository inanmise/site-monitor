package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.model.OnCallOverride;
import com.sitemonitor.model.WeakAlgorithmException;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.repository.NocSettingsRepository;
import com.sitemonitor.repository.OnCallOverrideRepository;
import com.sitemonitor.repository.OnCallScheduleRepository;
import com.sitemonitor.repository.QuietDigestItemRepository;
import com.sitemonitor.repository.SystemMaintenanceSuppressionRepository;
import com.sitemonitor.repository.SystemMaintenanceWindowRepository;
import com.sitemonitor.repository.TeamAccessGrantRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.repository.WeakAlgorithmExceptionRepository;
import com.sitemonitor.service.access.TeamAccessGrantService;
import com.sitemonitor.service.cache.CacheBus;
import com.sitemonitor.service.cache.CacheBusNames;
import com.sitemonitor.service.noc.NocOperatorService;
import com.sitemonitor.service.oncall.OnCallService;
import com.sitemonitor.service.techerror.TechErrorAnalyticsService;
import com.sitemonitor.util.TtlMemo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Her bellek-içi görüntü önbellek veri yoluna KAYITLI (2026-10-22, çok pod R2-A, MP-30): pod A'daki yazma (servisin kendi
 * boşaltma yolu) → A yayınlar → pod B bir yoklamada kendi belleğini düşürür → sonraki okuma DB'ye iner (taze veri). Yoklama
 * olmadan B eski görüntüyü sunmaya devam eder (bellek gerçekten var — testin anlamı). B'nin boşaltması yeniden yayın yapmaz.
 */
class CacheBusRegistrationTest {

    MultiPodTestSupport mp;
    DataSourceTransactionManager txm;
    final List<CacheBus> buses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        mp.jdbc().execute(CacheBus.DDL);
        txm = new DataSourceTransactionManager(mp.dataSource());
    }

    @AfterEach
    void tearDown() {
        for (CacheBus b : buses) ReflectionTestUtils.invokeMethod(b, "stop", false);
        mp.close();
    }

    CacheBus bus() {
        CacheBus b = new CacheBus(mp.jdbc(), txm);
        buses.add(b);
        b.pollNow();   // taban
        return b;
    }

    long gen(String name) {
        List<Long> l = mp.jdbc().queryForList("SELECT generation FROM cache_bus WHERE name = ?", Long.class, name);
        return l.isEmpty() ? 0 : l.get(0);
    }

    /** A yazdı → A yayınlar; B yoklamadan önce eski, sonra taze; B yeniden yayın yapmaz. */
    void propagate(CacheBus a, CacheBus b, String name, Runnable writeOnA, Runnable readOnB, Runnable verifyReads) {
        writeOnA.run();
        a.flushPending();
        assertThat(gen(name)).as(name + " yayınlandı").isEqualTo(1);
        b.pollNow();
        readOnB.run();
        verifyReads.run();
        assertThat(gen(name)).as(name + ": B yeniden yayın yapmadı").isEqualTo(1);
    }

    static void bind(Object service, CacheBus bus) {
        ReflectionTestUtils.invokeMethod(service, "setCacheBus", bus);
    }

    @Test
    @DisplayName("Nöbetçi (oncall): pod A'da yer değiştirme silindi → pod B bildirim hedefini yeniden okur (ESKİ nöbetçiye alarm yok)")
    void oncall() {
        OnCallScheduleRepository sa = mock(OnCallScheduleRepository.class), sb = mock(OnCallScheduleRepository.class);
        OnCallOverrideRepository oa = mock(OnCallOverrideRepository.class), ob = mock(OnCallOverrideRepository.class);
        when(sb.findByTeamIdIn(anyCollection())).thenReturn(List.of());
        OnCallService a = new OnCallService(sa, oa, mock(AppUserRepository.class));
        OnCallService b = new OnCallService(sb, ob, mock(AppUserRepository.class));
        CacheBus busA = bus(), busB = bus();
        bind(a, busA);
        bind(b, busB);
        b.notifyTargets(List.of(5L));
        b.notifyTargets(List.of(5L));
        verify(sb, times(1)).findByTeamIdIn(anyCollection());
        propagate(busA, busB, CacheBusNames.ONCALL, () -> a.deleteOverride(new OnCallOverride()),
                () -> b.notifyTargets(List.of(5L)), () -> verify(sb, times(2)).findByTeamIdIn(anyCollection()));
    }

    @Test
    @DisplayName("Sessiz saat: pod A'da pencere değişti → pod B ayarları yeniden okur")
    void quietHours() {
        TeamRepository ta = mock(TeamRepository.class), tb = mock(TeamRepository.class);
        when(tb.findQuietConfigured()).thenReturn(List.of());
        TeamQuietHoursService a = new TeamQuietHoursService(ta, mock(QuietDigestItemRepository.class));
        TeamQuietHoursService b = new TeamQuietHoursService(tb, mock(QuietDigestItemRepository.class));
        CacheBus busA = bus(), busB = bus();
        bind(a, busA);
        bind(b, busB);
        b.anyConfigured();
        b.anyConfigured();
        verify(tb, times(1)).findQuietConfigured();
        propagate(busA, busB, CacheBusNames.QUIET_HOURS, a::invalidate, b::anyConfigured,
                () -> verify(tb, times(2)).findQuietConfigured());
    }

    @Test
    @DisplayName("Pasif alıcı süzgeci: pod A'da hesap pasife alındı → pod B listeyi yeniden okur (pasif kişiye posta yok)")
    void inactiveRecipients() {
        AppUserRepository ua = mock(AppUserRepository.class), ub = mock(AppUserRepository.class);
        when(ub.findInactiveIdsAndEmails()).thenReturn(List.of());
        InactiveRecipientGuard a = new InactiveRecipientGuard(ua), b = new InactiveRecipientGuard(ub);
        CacheBus busA = bus(), busB = bus();
        bind(a, busA);
        bind(b, busB);
        b.snapshot();
        b.snapshot();
        verify(ub, times(1)).findInactiveIdsAndEmails();
        propagate(busA, busB, CacheBusNames.INACTIVE_RECIPIENTS, a::evict, b::snapshot,
                () -> verify(ub, times(2)).findInactiveIdsAndEmails());
    }

    @Test
    @DisplayName("7/24 operatörleri: pod A'da takım listesi kaydedildi → pod B görüntüyü yeniden kurar (okuma yetkisi geri alınır)")
    void nocOperators() {
        NocSettingsRepository ra = mock(NocSettingsRepository.class), rb = mock(NocSettingsRepository.class);
        when(rb.findById(any())).thenReturn(Optional.empty());
        NocOperatorService a = new NocOperatorService(ra, mock(TeamRepository.class), mock(AppUserRepository.class));
        NocOperatorService b = new NocOperatorService(rb, mock(TeamRepository.class), mock(AppUserRepository.class));
        CacheBus busA = bus(), busB = bus();
        bind(a, busA);
        bind(b, busB);
        b.teamIds();
        b.teamIds();
        verify(rb, times(1)).findById(any());
        propagate(busA, busB, CacheBusNames.NOC_OPERATORS, a::invalidate, b::teamIds, () -> verify(rb, times(2)).findById(any()));
    }

    @Test
    @DisplayName("Erişim paylaşımı: pod A'da paylaşım geri alındı → pod B görüntüyü yeniden kurar (düzenleme yetkisi kalmaz)")
    void teamGrants() {
        TeamAccessGrantRepository ra = mock(TeamAccessGrantRepository.class), rb = mock(TeamAccessGrantRepository.class);
        when(rb.findAllByOrderByIdAsc()).thenReturn(List.of());
        TeamAccessGrantService a = new TeamAccessGrantService(ra, mock(TeamRepository.class), mock(AppUserRepository.class));
        TeamAccessGrantService b = new TeamAccessGrantService(rb, mock(TeamRepository.class), mock(AppUserRepository.class));
        CacheBus busA = bus(), busB = bus();
        bind(a, busA);
        bind(b, busB);
        b.levelsFor(7L);
        b.levelsFor(7L);
        verify(rb, times(1)).findAllByOrderByIdAsc();
        propagate(busA, busB, CacheBusNames.TEAM_GRANTS, a::invalidate, () -> b.levelsFor(7L),
                () -> verify(rb, times(2)).findAllByOrderByIdAsc());
    }

    @Test
    @DisplayName("Bakım penceresi: pod A'da 'şimdi başlat' → pod B (tarama lideri) bir yoklamada bakımda görür; yoklama DB okumaz (tembel)")
    void maintenanceWindows() {
        MaintenanceWindowRepository ra = mock(MaintenanceWindowRepository.class), rb = mock(MaintenanceWindowRepository.class);
        when(rb.findByActiveTrue()).thenReturn(List.of());
        MaintenanceService a = new MaintenanceService(ra), b = new MaintenanceService(rb);
        CacheBus busA = bus(), busB = bus();
        bind(a, busA);
        bind(b, busB);
        b.reload();
        assertThat(b.isUnderMaintenance("api.example.com")).isFalse();
        MaintenanceWindow w = new MaintenanceWindow();
        w.setAllMonitors(true);
        w.setActive(true);
        w.setStartAt(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)
                .format(Instant.now().minusSeconds(600)));
        w.setDurationMinutes(60);
        when(ra.findByActiveTrue()).thenReturn(List.of(w));
        when(rb.findByActiveTrue()).thenReturn(List.of(w));
        assertThat(b.isUnderMaintenance("api.example.com")).as("yoklama olmadan B eski görüntüde").isFalse();

        a.refresh();
        assertThat(a.isUnderMaintenance("api.example.com")).as("A kendi yazımını hemen görür").isTrue();
        busA.flushPending();
        assertThat(gen(CacheBusNames.MAINTENANCE_WINDOWS)).isEqualTo(1);
        busB.pollNow();
        verify(rb, times(1)).findByActiveTrue();   // boşaltıcı ucuz: veri yolu iş parçacığı veritabanına gitmedi
        assertThat(b.isUnderMaintenance("api.example.com")).as("sonraki okuma bir kez yeniden yükler").isTrue();
        assertThat(b.isUnderMaintenance("x")).isTrue();
        verify(rb, times(2)).findByActiveTrue();
        assertThat(gen(CacheBusNames.MAINTENANCE_WINDOWS)).as("B yeniden yayın yapmadı").isEqualTo(1);
    }

    // ── Kuşak koruması: boşaltmadan ÖNCE başlamış yapım eski görüntüyü saklamaz (inceleme bulgusu 8) ─────────────────────

    @Test
    @DisplayName("Yarış: yükleme sürerken öteki pod boşaltırsa eski görüntü saklanmaz — pasif süzgeç, 7/24, sessiz saat, sistem bakımı, nöbetçi")
    void evictDuringBuild_isNotStored() {
        AppUserRepository ur = mock(AppUserRepository.class);
        InactiveRecipientGuard guard = new InactiveRecipientGuard(ur);
        AtomicInteger n1 = new AtomicInteger();
        when(ur.findInactiveIdsAndEmails()).thenAnswer(i -> { if (n1.incrementAndGet() == 1) guard.evictLocal(); return List.of(); });
        guard.snapshot();
        guard.snapshot();
        assertThat(n1.get()).as("yarışan yükleme saklanmadı → ikinci okuma yeniden yükler").isEqualTo(2);
        guard.snapshot();
        assertThat(n1.get()).as("yarışsız yükleme saklandı").isEqualTo(2);

        NocSettingsRepository nr = mock(NocSettingsRepository.class);
        NocOperatorService noc = new NocOperatorService(nr, mock(TeamRepository.class), mock(AppUserRepository.class));
        AtomicInteger n2 = new AtomicInteger();
        when(nr.findById(any())).thenAnswer(i -> {
            if (n2.incrementAndGet() == 1) ReflectionTestUtils.invokeMethod(noc, "invalidateLocal");
            return Optional.empty();
        });
        noc.teamIds();
        noc.teamIds();
        assertThat(n2.get()).isEqualTo(2);

        TeamRepository tr = mock(TeamRepository.class);
        TeamQuietHoursService quiet = new TeamQuietHoursService(tr, mock(QuietDigestItemRepository.class));
        AtomicInteger n3 = new AtomicInteger();
        when(tr.findQuietConfigured()).thenAnswer(i -> { if (n3.incrementAndGet() == 1) quiet.invalidateLocal(); return List.of(); });
        quiet.anyConfigured();
        quiet.anyConfigured();
        assertThat(n3.get()).isEqualTo(2);

        SystemMaintenanceWindowRepository sr = mock(SystemMaintenanceWindowRepository.class);
        SystemMaintenanceService sys = new SystemMaintenanceService(sr, mock(SystemMaintenanceSuppressionRepository.class),
                mock(AuditService.class), mock(AppUserRepository.class), mock(UserService.class), mock(TeamRepository.class));
        AtomicInteger n4 = new AtomicInteger();
        when(sr.findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(anyString())).thenAnswer(i -> {
            if (n4.incrementAndGet() == 1) sys.invalidateLocal();
            return List.of();
        });
        sys.live();
        sys.live();
        assertThat(n4.get()).isEqualTo(2);

        OnCallScheduleRepository oc = mock(OnCallScheduleRepository.class);
        OnCallService oncall = new OnCallService(oc, mock(OnCallOverrideRepository.class), mock(AppUserRepository.class));
        AtomicInteger n5 = new AtomicInteger();
        when(oc.findByTeamIdIn(anyCollection())).thenAnswer(i -> {
            if (n5.incrementAndGet() == 1) ReflectionTestUtils.invokeMethod(oncall, "memoClearLocal");
            return List.of();
        });
        oncall.notifyTargets(List.of(5L));
        oncall.notifyTargets(List.of(5L));
        assertThat(n5.get()).as("eski nöbetçi hesabı eski kuşağın anahtarına yazıldı").isEqualTo(2);
        oncall.notifyTargets(List.of(5L));
        assertThat(n5.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("C-2: uzak boşaltmadan sonra yeniden okuma veritabanı hatası verirse ÖNCEKİ görüntü kullanılır (boş değil)")
    void remoteEvictThenDbError_keepsPreviousSnapshot() {
        // Pasif alıcı süzgeci
        AppUserRepository ur = mock(AppUserRepository.class);
        when(ur.findInactiveIdsAndEmails()).thenReturn(List.<Object[]>of(new Object[] { 9L, "pasif@example.com" }))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        InactiveRecipientGuard guard = new InactiveRecipientGuard(ur);
        assertThat(guard.snapshot().ids()).containsExactly(9L);
        guard.evictLocal();
        assertThat(guard.snapshot().ids()).as("süzgeç hıçkırıkta kalkmaz").containsExactly(9L);

        // 7/24 operatör takımları
        NocSettingsRepository nr = mock(NocSettingsRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        com.sitemonitor.model.NocSettings ns = new com.sitemonitor.model.NocSettings();
        ns.setOperatorTeamIds("5");
        com.sitemonitor.model.Team t5 = new com.sitemonitor.model.Team();
        t5.setId(5L);
        t5.setActive(true);
        when(nr.findById(any())).thenReturn(Optional.of(ns)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        when(teams.findAllById(any())).thenReturn(List.of(t5));
        NocOperatorService noc = new NocOperatorService(nr, teams, mock(AppUserRepository.class));
        assertThat(noc.teamIds()).containsExactly(5L);
        ReflectionTestUtils.invokeMethod(noc, "invalidateLocal");
        assertThat(noc.teamIds()).as("7/24 takımları hıçkırıkta kaybolmaz").containsExactly(5L);

        // Sessiz saatler
        TeamRepository qr = mock(TeamRepository.class);
        com.sitemonitor.model.Team q = new com.sitemonitor.model.Team();
        q.setId(7L);
        q.setQuietStart("22:00");
        q.setQuietEnd("07:00");
        when(qr.findQuietConfigured()).thenReturn(List.of(q)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        TeamQuietHoursService quiet = new TeamQuietHoursService(qr, mock(QuietDigestItemRepository.class));
        assertThat(quiet.isConfigured(7L)).isTrue();
        quiet.invalidateLocal();
        assertThat(quiet.isConfigured(7L)).as("sessiz saat hıçkırıkta kalkmaz").isTrue();

        // Sistem bakımı
        SystemMaintenanceWindowRepository sr = mock(SystemMaintenanceWindowRepository.class);
        when(sr.findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(anyString()))
                .thenReturn(List.of(new com.sitemonitor.model.SystemMaintenanceWindow()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        SystemMaintenanceService sys = new SystemMaintenanceService(sr, mock(SystemMaintenanceSuppressionRepository.class),
                mock(AuditService.class), mock(AppUserRepository.class), mock(UserService.class), mock(TeamRepository.class));
        assertThat(sys.live()).hasSize(1);
        sys.invalidateLocal();
        assertThat(sys.live()).as("bakım görüntüsü hıçkırıkta kaybolmaz").hasSize(1);
    }

    @Test
    @DisplayName("Yarış: zayıf algoritma raporu hesaplanırken boşaltılırsa eski rapor okunmayan anahtara yazılır")
    void weakAlgo_evictDuringBuild_isNotStored() {
        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        WeakAlgorithmReportService svc = new WeakAlgorithmReportService(mock(LatestCheckRepository.class), inv,
                mock(TeamRepository.class), mock(CertificateCheckRepository.class), mock(WeakAlgorithmExceptionRepository.class));
        ReflectionTestUtils.setField(svc, "cacheMs", 120_000L);
        AtomicInteger n = new AtomicInteger();
        when(inv.findByActiveTrueOrderByDomainAsc()).thenAnswer(i -> {
            if (n.incrementAndGet() == 1) ReflectionTestUtils.invokeMethod(svc, "memoClearLocal");
            return List.of();
        });
        svc.build(null);
        svc.build(null);
        assertThat(n.get()).isEqualTo(2);
        svc.build(null);
        assertThat(n.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Sistem bakımı: pod A'da pencere kaydedildi → pod B görüntüyü yeniden okur")
    void systemMaintenance() {
        SystemMaintenanceWindowRepository ra = mock(SystemMaintenanceWindowRepository.class), rb = mock(SystemMaintenanceWindowRepository.class);
        when(rb.findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(anyString())).thenReturn(List.of());
        Function<SystemMaintenanceWindowRepository, SystemMaintenanceService> make = r -> new SystemMaintenanceService(r,
                mock(SystemMaintenanceSuppressionRepository.class), mock(AuditService.class), mock(AppUserRepository.class),
                mock(UserService.class), mock(TeamRepository.class));
        SystemMaintenanceService a = make.apply(ra), b = make.apply(rb);
        CacheBus busA = bus(), busB = bus();
        bind(a, busA);
        bind(b, busB);
        b.live();
        b.live();
        verify(rb, times(1)).findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(anyString());
        propagate(busA, busB, CacheBusNames.SYSTEM_MAINTENANCE, a::invalidate, b::live,
                () -> verify(rb, times(2)).findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(anyString()));
    }

    @Test
    @DisplayName("Zayıf algoritma raporu: pod A'da istisna kaldırıldı → pod B'nin rapor belleği boşalır")
    @SuppressWarnings("unchecked")
    void weakAlgo() {
        WeakAlgorithmExceptionRepository ea = mock(WeakAlgorithmExceptionRepository.class);
        WeakAlgorithmException ex = new WeakAlgorithmException();
        ex.setId(3L);
        when(ea.findByDomain("d.example.com")).thenReturn(Optional.of(ex));
        Function<WeakAlgorithmExceptionRepository, WeakAlgorithmReportService> make = r -> new WeakAlgorithmReportService(
                mock(LatestCheckRepository.class), mock(CertificateInventoryRepository.class), mock(TeamRepository.class),
                mock(CertificateCheckRepository.class), r);
        WeakAlgorithmReportService a = make.apply(ea), b = make.apply(mock(WeakAlgorithmExceptionRepository.class));
        CacheBus busA = bus(), busB = bus();
        bind(a, busA);
        bind(b, busB);
        TtlMemo<Object> memoB = (TtlMemo<Object>) ReflectionTestUtils.getField(b, "memo");
        assertThat(memoB.get("k", 60_000, false, () -> "eski")).isEqualTo("eski");
        assertThat(memoB.get("k", 60_000, false, () -> "taze")).as("bellek var").isEqualTo("eski");
        propagate(busA, busB, CacheBusNames.WEAK_ALGO, () -> assertThat(a.clearException("d.example.com")).isTrue(), () -> { },
                () -> assertThat(memoB.get("k", 60_000, false, () -> "taze")).isEqualTo("taze"));
    }

    @Test
    @DisplayName("Teknik hata analizi: pod A'da değer kaydedildi (invalidate) → pod B'nin kuşağı artar, belleği boşalır")
    void techErrorAnalytics() {
        TechErrorAnalyticsService a = new TechErrorAnalyticsService(mock(TechErrorChannelRepository.class), mock(TechErrorReportRepository.class));
        TechErrorAnalyticsService b = new TechErrorAnalyticsService(mock(TechErrorChannelRepository.class), mock(TechErrorReportRepository.class));
        CacheBus busA = bus(), busB = bus();
        bind(a, busA);
        bind(b, busB);
        AtomicLong genB = (AtomicLong) ReflectionTestUtils.getField(b, "generation");
        long before = genB.get();
        propagate(busA, busB, CacheBusNames.TECH_ERROR_ANALYTICS, a::invalidate, () -> { },
                () -> assertThat(genB.get()).isEqualTo(before + 1));
    }

    @Test
    @DisplayName("Kullanıcı kapısı: pod A'da hesap silindi / pasife alındı → pod B pasif kapısını DB'den yeniden okur (5 sn beklemez)")
    void userGate() {
        AppUserRepository ua = mock(AppUserRepository.class), ub = mock(AppUserRepository.class);
        when(ub.findActiveFlagByUsername("ayse")).thenReturn(Optional.of(Boolean.TRUE));
        UserService a = new UserService(ua, mock(TeamRepository.class), mock(CertificateInventoryRepository.class),
                mock(com.sitemonitor.repository.EscalationContactRepository.class),
                mock(com.sitemonitor.repository.PasswordHistoryRepository.class));
        UserService b = new UserService(ub, mock(TeamRepository.class), mock(CertificateInventoryRepository.class),
                mock(com.sitemonitor.repository.EscalationContactRepository.class),
                mock(com.sitemonitor.repository.PasswordHistoryRepository.class));
        ReflectionTestUtils.setField(a, "inactiveCacheMs", 60_000L);
        ReflectionTestUtils.setField(b, "inactiveCacheMs", 60_000L);
        CacheBus busA = bus(), busB = bus();
        bind(a, busA);
        bind(b, busB);
        b.isAccountInactive("ayse");
        b.isAccountInactive("ayse");
        verify(ub, times(1)).findActiveFlagByUsername("ayse");
        propagate(busA, busB, CacheBusNames.USER_GATE, () -> a.forgetUserCaches("ayse"),
                () -> b.isAccountInactive("ayse"), () -> verify(ub, times(2)).findActiveFlagByUsername("ayse"));
    }

    @Test
    @DisplayName("Veri yolu yoksa (birim testi / eski kurulum) her servis yalnız kendi belleğini boşaltır — istisna yok")
    void withoutBus_localOnly() {
        new TeamQuietHoursService(mock(TeamRepository.class), mock(QuietDigestItemRepository.class)).invalidate();
        new InactiveRecipientGuard(mock(AppUserRepository.class)).evict();
        new NocOperatorService(mock(NocSettingsRepository.class), mock(TeamRepository.class), mock(AppUserRepository.class)).invalidate();
        new TeamAccessGrantService(mock(TeamAccessGrantRepository.class), mock(TeamRepository.class), mock(AppUserRepository.class)).invalidate();
        new TechErrorAnalyticsService(mock(TechErrorChannelRepository.class), mock(TechErrorReportRepository.class)).invalidate();
        MaintenanceWindowRepository r = mock(MaintenanceWindowRepository.class);
        when(r.findByActiveTrue()).thenReturn(List.of());
        new MaintenanceService(r).refresh();
        assertThat(gen(CacheBus.GLOBAL)).isZero();
    }
}

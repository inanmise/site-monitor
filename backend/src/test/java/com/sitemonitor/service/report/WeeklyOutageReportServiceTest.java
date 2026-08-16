package com.sitemonitor.service.report;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.EmailNotificationService.AvailabilityRow;
import com.sitemonitor.service.MaintenanceService;
import com.sitemonitor.service.MonitoringWeeklyStatsService;
import com.sitemonitor.service.WeeklyAvailabilityReportService.Window;
import com.sitemonitor.service.report.WeeklyOutageReportService.OutageRow;
import com.sitemonitor.service.report.WeeklyOutageReportService.TypeGroup;
import com.sitemonitor.service.report.WeeklyOutageReportService.WeeklyOutageData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Haftalık kesinti raporunun VERİ katmanı — türetilen alanların hepsi burada sınanır.
 *
 * <p>Pencere: 15–21 Haziran 2026 (Pzt–Paz), Europe/Istanbul. Saat dilimi bilinçli olarak sabit
 * seçildi: gün/saat kovaları IST'e göre hesaplanıyor ve UTC damgalarla test edilirse CI (UTC)
 * ile yerel makine farklı sonuç verirdi.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyOutageReportServiceTest {

    @Mock AlertEventRepository alertEventRepo;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock MaintenanceWindowRepository maintenanceRepo;
    @Mock MaintenanceService maintenanceService;
    @Mock MonitoringWeeklyStatsService weeklyStatsService;

    private WeeklyOutageReportService service;

    /** 15 Haziran 2026 Pzt 00:00 IST = 14 Haziran 21:00 UTC; 21 Haziran 23:59:59 IST = 20:59:59 UTC. */
    private static final Window W = new Window(
            "2026-06-14T21:00:00", "2026-06-21T20:59:59",
            Instant.parse("2026-06-21T20:59:59Z"), 2026, 25, "15–21 Haziran 2026");

    private static final Team TEAM = team();

    private static Team team() {
        Team t = new Team();
        t.setId(5L);
        t.setName("Dijital SY");
        return t;
    }

    @BeforeEach
    void setUp() {
        service = new WeeklyOutageReportService(alertEventRepo, notificationLogRepo, inventoryRepo,
                latestCheckRepo, maintenanceRepo, maintenanceService, weeklyStatsService);
        when(maintenanceRepo.findAll()).thenReturn(List.of());
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(anyLong()))
                .thenReturn(List.of());
        when(notificationLogRepo.countByAlertIds(any())).thenReturn(List.of());
        when(alertEventRepo.countFilteredByType(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of());
        when(weeklyStatsService.compute(anyLong(), anyInt(), anyInt())).thenReturn(null);
        stubAlarms(List.of(), List.of(), List.of());
    }

    /** Üç kapsam sorgusunu ayrı ayrı besler: hafta içi açılanlar / devreden açıklar / devreden çözülenler. */
    private void stubAlarms(List<AlertEvent> opened, List<AlertEvent> carriedOpen, List<AlertEvent> carriedResolved) {
        when(alertEventRepo.findFiltered(isNull(), eq(W.fromUtc()), eq(W.toUtc()),
                isNull(), isNull(), isNull(), isNull(), eq(true), any(), any()))
                .thenReturn(new PageImpl<>(opened));
        when(alertEventRepo.findFiltered(eq(false), isNull(), eq(W.fromUtc()),
                isNull(), isNull(), isNull(), isNull(), eq(true), any(), any()))
                .thenReturn(new PageImpl<>(carriedOpen));
        when(alertEventRepo.findFiltered(eq(true), isNull(), eq(W.fromUtc()),
                eq(W.fromUtc()), isNull(), isNull(), isNull(), eq(true), any(), any()))
                .thenReturn(new PageImpl<>(carriedResolved));
    }

    private static AlertEvent alarm(long id, String domain, String type, String createdAt) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setCreatedAt(createdAt);
        e.setResolved(false);
        return e;
    }

    private static AlertEvent resolved(long id, String domain, String type, String from, String to) {
        AlertEvent e = alarm(id, domain, type, from);
        e.setResolved(true);
        e.setResolvedAt(to);
        return e;
    }

    private WeeklyOutageData collect() {
        return service.collect(TEAM, W, List.of());
    }

    // ── Süre hesabı ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Çözülmüş alarmın süresi açılış→çözülüş; açık alarmın süresi PENCERE SONUNA kadar")
    void duration_closedUsesResolvedAt_openUsesWindowEnd() {
        stubAlarms(List.of(
                resolved(1, "a.com", "HTTP_DOWN", "2026-06-16T09:00:00", "2026-06-16T11:30:00"),
                alarm(2, "b.com", "HTTP_DOWN", "2026-06-21T18:59:59")), List.of(), List.of());

        Map<String, OutageRow> byTarget = index(collect());

        assertThat(byTarget.get("a.com").durationMin()).isEqualTo(150);   // 2 sa 30 dk
        // Açık alarm pencere sonuna (21 Haz 20:59:59Z) kadar = 2 saat. "Şu ana kadar" deseydik
        // geçmiş bir haftanın raporu her üretimde farklı süre gösterirdi.
        assertThat(byTarget.get("b.com").durationMin()).isEqualTo(120);
        assertThat(byTarget.get("b.com").stillOpen()).isTrue();
    }

    @Test
    @DisplayName("humanDuration: ham dakika yerine okunur süre")
    void humanDuration_readable() {
        assertThat(WeeklyOutageReportService.humanDuration(0)).isEqualTo("< 1 dk");
        assertThat(WeeklyOutageReportService.humanDuration(45)).isEqualTo("45dk");
        assertThat(WeeklyOutageReportService.humanDuration(150)).isEqualTo("2sa 30dk");
        assertThat(WeeklyOutageReportService.humanDuration(1440)).isEqualTo("1g");
        assertThat(WeeklyOutageReportService.humanDuration(3075)).isEqualTo("2g 3sa 15dk");
    }

    @Test
    @DisplayName("Bozuk/eksik zaman damgası süreyi negatif ya da NaN yapmaz")
    void duration_brokenStampsAreSafe() {
        AlertEvent broken = alarm(1, "a.com", "HTTP_DOWN", "bozuk-damga");
        AlertEvent backwards = resolved(2, "b.com", "HTTP_DOWN",
                "2026-06-16T12:00:00", "2026-06-16T09:00:00");   // çözülüş açılıştan ÖNCE
        stubAlarms(List.of(broken, backwards), List.of(), List.of());

        Map<String, OutageRow> byTarget = index(collect());
        assertThat(byTarget.get("a.com").durationMin()).isZero();
        assertThat(byTarget.get("b.com").durationMin()).isZero();
    }

    // ── Devreden kesintiler ──────────────────────────────────────────────────

    @Test
    @DisplayName("ÖNCEKİ HAFTADAN DEVREDEN alarmlar da rapora girer ve «devreden» işaretlenir")
    void carriedOverAlarmsAreIncludedAndFlagged() {
        // Hafta başlamadan açılmış, hâlâ açık — haftanın en uzun kesintisi bu olabilir.
        AlertEvent carried = alarm(9, "eski.com", "PORT_DOWN", "2026-06-10T08:00:00");
        stubAlarms(List.of(alarm(1, "yeni.com", "HTTP_DOWN", "2026-06-16T09:00:00")),
                List.of(carried), List.of());

        WeeklyOutageData d = collect();

        assertThat(d.totalAlarms()).isEqualTo(2);
        assertThat(d.carriedOverCount()).isEqualTo(1);
        Map<String, OutageRow> byTarget = index(d);
        assertThat(byTarget.get("eski.com").carriedOver()).isTrue();
        assertThat(byTarget.get("yeni.com").carriedOver()).isFalse();
    }

    @Test
    @DisplayName("Üç kapsam sorgusunun kesişimi ID ile TEKİLLEŞTİRİLİR — alarm iki kez sayılmaz")
    void overlappingQueriesAreDeduplicatedById() {
        AlertEvent same = alarm(7, "a.com", "HTTP_DOWN", "2026-06-16T09:00:00");
        stubAlarms(List.of(same), List.of(same), List.of(same));

        assertThat(collect().totalAlarms()).isEqualTo(1);
    }

    // ── Bildirim boşluğu ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Hiç BAŞARILI bildirim yoksa alarm «bildirim ulaşmadı» listesine girer")
    void notifyGapListsAlarmsThatReachedNobody() {
        stubAlarms(List.of(
                alarm(1, "ulasti.com", "HTTP_DOWN", "2026-06-16T09:00:00"),
                alarm(2, "ulasmadi.com", "HTTP_DOWN", "2026-06-16T10:00:00"),
                alarm(3, "hickayit.com", "HTTP_DOWN", "2026-06-16T11:00:00")), List.of(), List.of());
        when(notificationLogRepo.countByAlertIds(any())).thenReturn(List.<Object[]>of(
                new Object[]{ 1L, 2L, 0L },      // 2 gönderildi
                new Object[]{ 2L, 0L, 3L }));    // 0 gönderildi, 3 başarısız; 3 numaralı hiç kayıtsız

        WeeklyOutageData d = collect();

        assertThat(d.notifyGaps()).extracting(OutageRow::target)
                .containsExactlyInAnyOrder("ulasmadi.com", "hickayit.com");
        Map<String, OutageRow> byTarget = index(d);
        assertThat(byTarget.get("ulasti.com").notifySent()).isEqualTo(2);
        assertThat(byTarget.get("ulasmadi.com").notifyFailed()).isEqualTo(3);
    }

    // ── Bakım işareti ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bakım penceresine denk gelen alarm işaretlenir; hedefi kapsamayan pencere işaretlemez")
    void maintenanceOverlapMarksOnlyCoveredTargets() {
        MaintenanceWindow win = new MaintenanceWindow();
        win.setId(1L);
        when(maintenanceRepo.findAll()).thenReturn(List.of(win));
        when(maintenanceService.isActiveAt(eq(win), any())).thenReturn(true);
        when(maintenanceService.targetsOf(win)).thenReturn(List.of("bakimda.com"));
        stubAlarms(List.of(
                alarm(1, "bakimda.com", "HTTP_DOWN", "2026-06-16T02:00:00"),
                alarm(2, "baska.com", "HTTP_DOWN", "2026-06-16T02:00:00")), List.of(), List.of());

        Map<String, OutageRow> byTarget = index(collect());

        assertThat(byTarget.get("bakimda.com").maintenanceOverlap()).isTrue();
        assertThat(byTarget.get("baska.com").maintenanceOverlap()).isFalse();
    }

    @Test
    @DisplayName("allMonitors penceresi HER hedefi kapsar")
    void maintenanceAllMonitorsCoversEveryTarget() {
        MaintenanceWindow win = new MaintenanceWindow();
        win.setAllMonitors(true);
        when(maintenanceRepo.findAll()).thenReturn(List.of(win));
        when(maintenanceService.isActiveAt(eq(win), any())).thenReturn(true);
        stubAlarms(List.of(alarm(1, "her.com", "HTTP_DOWN", "2026-06-16T02:00:00")), List.of(), List.of());

        assertThat(index(collect()).get("her.com").maintenanceOverlap()).isTrue();
    }

    @Test
    @DisplayName("Bakım pencereleri okunamazsa rapor ÇÖKMEZ, yalnız bakım işareti çizilmez")
    void maintenanceReadFailureDoesNotBreakReport() {
        when(maintenanceRepo.findAll()).thenThrow(new RuntimeException("db down"));
        stubAlarms(List.of(alarm(1, "a.com", "HTTP_DOWN", "2026-06-16T09:00:00")), List.of(), List.of());

        WeeklyOutageData d = collect();
        assertThat(d.totalAlarms()).isEqualTo(1);
        assertThat(d.maintenanceOverlapCount()).isZero();
    }

    // ── Gruplama ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Alarmlar izleme türüne göre gruplanır; BİLİNMEYEN tip «Diğer»e düşer, kaybolmaz")
    void groupsByMonitorType_unknownFallsToOther() {
        stubAlarms(List.of(
                alarm(1, "a.com", "HTTP_DOWN", "2026-06-16T09:00:00"),
                alarm(2, "b.com", "PORT_DOWN", "2026-06-16T10:00:00"),
                alarm(3, "c.com", "PAGE_INTEGRITY", "2026-06-16T11:00:00"),
                alarm(4, "d.com", "BOYLE_BIR_TIP_YOK", "2026-06-16T12:00:00")), List.of(), List.of());

        List<TypeGroup> groups = collect().groups();

        assertThat(groups).extracting(TypeGroup::label)
                .containsExactly("HTTP/Website", "Port", "Sayfa Bütünlüğü", "Diğer");
        // Hiçbir alarm düşmedi diye kaybolmasın: toplam satır sayısı korunur.
        assertThat(groups.stream().mapToInt(g -> g.rows().size()).sum()).isEqualTo(4);
    }

    @Test
    @DisplayName("Sayfa bütünlüğü alarmları artık kendi türüne düşüyor (eskiden hiçbir kovaya girmiyordu)")
    void pageIntegrityAlarmsAreClassified() {
        stubAlarms(List.of(alarm(1, "a.com", "PAGE_DOWN", "2026-06-16T09:00:00")), List.of(), List.of());
        assertThat(index(collect()).get("a.com").monitorType()).isEqualTo("page");
    }

    // ── Tekrar edenler ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Aynı hedef+tip için ≥2 alarm «tekrar eden» sayılır; tek seferlikler listelenmez")
    void repeatsOnlyCountTwoOrMore() {
        stubAlarms(List.of(
                alarm(1, "kronik.com", "HTTP_DOWN", "2026-06-16T09:00:00"),
                alarm(2, "kronik.com", "HTTP_DOWN", "2026-06-17T09:00:00"),
                alarm(3, "kronik.com", "HTTP_DOWN", "2026-06-18T09:00:00"),
                alarm(4, "tekil.com", "HTTP_DOWN", "2026-06-16T09:00:00"),
                // aynı hedef ama FARKLI tip → ayrı sayılır, tekrar değil
                alarm(5, "kronik.com", "PORT_DOWN", "2026-06-16T09:00:00")), List.of(), List.of());

        assertThat(collect().repeats())
                .extracting(r -> r.target() + "/" + r.alertType() + "=" + r.count())
                .containsExactly("kronik.com/HTTP_DOWN=3");
    }

    // ── Gün / saat dağılımı ──────────────────────────────────────────────────

    @Test
    @DisplayName("Gün ve saat kovaları Europe/Istanbul'a göre — UTC'ye göre olsaydı gün kayardı")
    void bucketsUseIstanbulTime() {
        // 16 Haziran 2026 Salı, 22:30 UTC = 17 Haziran Çarşamba 01:30 IST.
        stubAlarms(List.of(alarm(1, "a.com", "HTTP_DOWN", "2026-06-16T22:30:00")), List.of(), List.of());

        WeeklyOutageData d = collect();

        assertThat(d.byDay()).filteredOn(b -> b.count() > 0)
                .extracting(WeeklyOutageReportService.Bucket::label)
                .containsExactly("Çarşamba");
        assertThat(d.byHour()).extracting(WeeklyOutageReportService.Bucket::label).containsExactly("01:00");
    }

    @Test
    @DisplayName("Alarm düşmeyen saatler listelenmez — sıfır satırlar deseni boğardı")
    void hourBucketsOmitEmptyHours() {
        stubAlarms(List.of(
                alarm(1, "a.com", "HTTP_DOWN", "2026-06-16T06:00:00"),
                alarm(2, "b.com", "HTTP_DOWN", "2026-06-16T06:30:00")), List.of(), List.of());

        assertThat(collect().byHour()).hasSize(1);
        assertThat(collect().byDay()).hasSize(7);   // günler tam hafta gösterilir
    }

    // ── Özet ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Özet: toplam/açık/etkilenen hedef/toplam süre; aynı hedefin iki alarmı hedefi bir kez sayar")
    void summaryCounts() {
        stubAlarms(List.of(
                resolved(1, "a.com", "HTTP_DOWN", "2026-06-16T09:00:00", "2026-06-16T10:00:00"),
                resolved(2, "a.com", "PORT_DOWN", "2026-06-17T09:00:00", "2026-06-17T09:30:00"),
                alarm(3, "b.com", "HTTP_DOWN", "2026-06-21T19:59:59")), List.of(), List.of());

        WeeklyOutageData d = collect();

        assertThat(d.totalAlarms()).isEqualTo(3);
        assertThat(d.stillOpenCount()).isEqualTo(1);
        assertThat(d.affectedTargets()).isEqualTo(2);          // a.com iki alarm ama TEK hedef
        assertThat(d.totalDowntimeMin()).isEqualTo(60 + 30 + 60);
        assertThat(d.quietWeek()).isFalse();
    }

    @Test
    @DisplayName("TOPLAM kesinti süresi haftaya KIRPILIR — devreden alarm haftalık raporu şişirmez")
    void totalDowntimeIsClippedToTheWeek() {
        // 5 Haziran'da açılmış, hâlâ açık: gerçek süresi ~16 gün. Ham süre toplansaydı bir
        // haftalık rapor "16 gün kesinti" derdi. Gerçek veride bu 254 GÜN olarak görünmüştü.
        stubAlarms(List.of(), List.of(alarm(9, "eski.com", "PORT_DOWN", "2026-06-05T08:00:00")), List.of());

        WeeklyOutageData d = collect();
        OutageRow r = index(d).get("eski.com");

        // Detay satırı GERÇEK süreyi gösterir (alarmın kendisi hakkındaki doğru bilgi)...
        assertThat(r.durationMin()).isGreaterThan(20_000);
        // ...ama özet yalnız haftaya düşen payı sayar: tam hafta = 7×1440 - 1 dk (23:59:59 sınırı).
        assertThat(r.weekDurationMin()).isEqualTo(10_080);
        assertThat(d.totalDowntimeMin()).isEqualTo(10_080);
    }

    @Test
    @DisplayName("Haftadan SONRA çözülen alarmın payı da hafta sonunda kesilir")
    void weekShareIsClippedAtWindowEnd() {
        // Hafta içinde açıldı, hafta bittikten çok sonra çözüldü.
        stubAlarms(List.of(resolved(1, "a.com", "HTTP_DOWN",
                "2026-06-21T19:59:59", "2026-06-30T12:00:00")), List.of(), List.of());

        OutageRow r = index(collect()).get("a.com");
        assertThat(r.weekDurationMin()).isEqualTo(60);            // yalnız 20:59:59'a kadar
        assertThat(r.durationMin()).isGreaterThan(10_000);        // gerçek süre çok daha uzun
    }

    @Test
    @DisplayName("Hiç alarm yoksa quietWeek — PDF yine üretilir (kesintisiz haftada da ek gider)")
    void quietWeek() {
        WeeklyOutageData d = collect();
        assertThat(d.quietWeek()).isTrue();
        assertThat(d.totalAlarms()).isZero();
        assertThat(d.groups()).isEmpty();
    }

    @Test
    @DisplayName("Ortalama erişilebilirlik: bir domain bile %100 değilse 100,00 GÖSTERİLMEZ")
    void avgAvailabilityNeverFakes100() {
        List<AvailabilityRow> rows = List.of(
                new AvailabilityRow("a.com", 100.0, 0, 0, 0, 100L, 120L, 40),
                new AvailabilityRow("b.com", 99.995, 1, 1, 1, 100L, 120L, 40));

        WeeklyOutageData d = service.collect(TEAM, W, rows);

        // Yuvarlama 100,00'e çekerdi; haftalık e-postanın summarize'ı ile AYNI kural uygulanır,
        // yoksa gövde ile ek çelişirdi.
        assertThat(d.avgAvailabilityPct()).isEqualTo(99.99);
    }

    @Test
    @DisplayName("Tür istatistikleri alınamazsa kesinti detayı yine üretilir")
    void typeStatsFailureDoesNotBreakReport() {
        when(weeklyStatsService.compute(anyLong(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("cache patladı"));
        stubAlarms(List.of(alarm(1, "a.com", "HTTP_DOWN", "2026-06-16T09:00:00")), List.of(), List.of());

        WeeklyOutageData d = collect();
        assertThat(d.typeStats()).isEmpty();
        assertThat(d.totalAlarms()).isEqualTo(1);
    }

    // ── Kapsam (IDOR) ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Alarm sorguları TAKIM KAPSAMLI koşar — rapor başka takımın alarmına açılamaz")
    void alarmQueriesAreTeamScoped() {
        collect();

        // Üç kapsam sorgusunun ÜÇÜ de scoped=true ve scope=[takım] ile çağrılmalı. Biri bile
        // scoped=false gitse PDF tüm kurumun alarmlarını takıma göndermiş olurdu.
        org.mockito.Mockito.verify(alertEventRepo, org.mockito.Mockito.times(3)).findFiltered(
                any(), any(), any(), any(), any(), any(), any(),
                eq(true), eq(List.of(5L)), any());
        org.mockito.Mockito.verify(alertEventRepo, org.mockito.Mockito.never()).findFiltered(
                any(), any(), any(), any(), any(), any(), any(),
                eq(false), any(), any());
    }

    // ── Dosya adı ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Dosya adı: Türkçe/boşluklu takım adı dosya adına güvenli hâle gelir")
    void fileNameIsFilesystemSafe() {
        assertThat(WeeklyOutageReportService.fileName("Dijital SY", W))
                .isEqualTo("haftalik-kesinti-raporu_dijital-sy_2026-W25.pdf");
        assertThat(WeeklyOutageReportService.fileName("Ağ & Güvenlik / Ops", W))
                .isEqualTo("haftalik-kesinti-raporu_ağ-güvenlik-ops_2026-W25.pdf");
        assertThat(WeeklyOutageReportService.fileName(null, W)).contains("takim");
    }

    // ── Yardımcı ─────────────────────────────────────────────────────────────

    private static Map<String, OutageRow> index(WeeklyOutageData d) {
        return d.groups().stream().flatMap(g -> g.rows().stream())
                .collect(java.util.stream.Collectors.toMap(OutageRow::target, r -> r, (a, b) -> a));
    }
}

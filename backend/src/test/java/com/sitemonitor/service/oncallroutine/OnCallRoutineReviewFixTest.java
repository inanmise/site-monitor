package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.oncall.OnCallRotation;
import com.sitemonitor.service.oncallroutine.OnCallRoutineCalendar.Period;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.CloseResult;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.Responsible;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.TeamCtx;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.DayRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.A;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.B;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.MON_0900;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.TEAM;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.THU_0930;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nöbet rutini bağımsız arka uç incelemesinin (2026-11-26) düzeltmeleri — GERÇEK SQL (H2, PostgreSQL kipi). Her test,
 * düzeltilen kuralı kaldırınca düşecek biçimde yazıldı:
 * <ul>
 *   <li>#1 devir saati gün başlangıcından geç rotasyonda devir günü devir anında KAPATILMAZ, özete girmez, sonra geç
 *   sayılmadan tamamlanır;</li>
 *   <li>#2 gün kapanışı R(D) ile sayar: zorunlu günlük görevi olmayan gün satır açmaz (NOTHING_DUE), zorunlularının hepsi
 *   çözülmüş gün kendiliğinden tamamlanır (bildirim yok), kaldırılmış görevin satırı zorunlu sayılmaz;</li>
 *   <li>#3 dönem kapanışı rutin başlamadan önceki hedef günü için görev oluşturmaz;</li>
 *   <li>#5 iş günü olmayan güne (hafta sonu, tatil) gün satırı açılmaz, gün notu yazılamaz;</li>
 *   <li>#9 takımdan çıkarılmış devralan yok sayılır, sorumlu alt kaynağa düşer;</li>
 *   <li>#11 geçmiş günün tamamlanması sorumlu anlık görüntüsünü ezmez;</li>
 *   <li>#13 dönem kapanışı kalıcı işaretle bir kez — yeni pod / yeniden başlatma kapanıştan sonra PENDING'e çekileni
 *   yeniden MISSED yapmaz;</li>
 *   <li>şablon / ayar kaydı yanıtı düzenleyen adı ve zamanını taşır.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OnCallRoutineReviewFixTest {

    static final LocalDate MON = LocalDate.parse("2026-11-23");
    static final LocalDate TUE = LocalDate.parse("2026-11-24");
    static final LocalDate WED = LocalDate.parse("2026-11-25");
    static final LocalDate THU = LocalDate.parse("2026-11-26");
    static final LocalDate FRI = LocalDate.parse("2026-11-27");
    static final LocalDate SAT = LocalDate.parse("2026-11-28");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired NotificationLogRepository logs;

    OnCallRoutineTestRig rig;
    OnCallRoutineService svc;
    OnCallRoutineStore store;
    final EscalationService escalation = mock(EscalationService.class);
    final EmailNotificationService email = mock(EmailNotificationService.class);
    final UserPushService push = mock(UserPushService.class);
    final SystemMaintenanceService maintenance = mock(SystemMaintenanceService.class);

    @BeforeEach
    void setUp() {
        rig = new OnCallRoutineTestRig(jdbc, txm);
        clean();
        svc = rig.svc;
        store = rig.store;
        when(escalation.teamEmailsForMonitor(eq(TEAM), any())).thenReturn(List.of("odeme@example.com"));
        when(email.getEmailFrom()).thenReturn("noreply@example.com");
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), eq(false), any())).thenReturn("SENT");
        when(push.messageCharLimit()).thenReturn(200);
        when(push.enqueueTeamNoticeLocalized(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 3));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    void clean() {
        rig.clean();
        List<NotificationLog> ours = new ArrayList<>();
        for (NotificationLog l : logs.findAll()) {
            if (l.getTrigger() != null && l.getTrigger().startsWith("ONCALL_ROUTINE")) ours.add(l);
        }
        logs.deleteAll(ours);
    }

    OnCallRoutineNotifier notifier(Instant now) {
        OnCallRoutineNotifier n = new OnCallRoutineNotifier(store, svc, rig.teams, rig.users, logs);
        n.escalation = escalation;
        n.email = email;
        n.push = push;
        n.systemMaintenance = maintenance;
        n.clock = Clock.fixed(now, ZoneOffset.UTC);
        return n;
    }

    static String codeOf(Throwable e) {
        if (e instanceof CodedConflictException c) return c.getCode();
        if (e instanceof CodedNotFoundException n) return n.getCode();
        if (e instanceof FieldValidationException f) return f.getCode();
        return e.getClass().getSimpleName();
    }

    static TeamCtx withRotation(TeamCtx c, OnCallRotation.Config rot) {
        return new TeamCtx(c.teamId(), c.teamName(), c.teamActive(), c.row(), c.template(), c.templateUnreadable(), c.settings(),
                c.holidays(), c.holidayNames(), rot);
    }

    static TeamCtx withHolidays(TeamCtx c, Set<LocalDate> holidays) {
        return new TeamCtx(c.teamId(), c.teamName(), c.teamActive(), c.row(), c.template(), c.templateUnreadable(), c.settings(),
                holidays, Map.of(), c.rotation());
    }

    /** Sabah bölümü + Pazartesi ve Cuma haftalık görevleri. */
    static Map<String, Object> templateMonFri() {
        return Map.of("sections", List.of(
                Map.of("id", "s1", "title", "Sabah kontrolleri", "tasks", List.of(
                        Map.of("id", "d1", "title", "Kuyruklar"))),
                Map.of("id", "s2", "title", "Haftalık", "tasks", List.of(
                        Map.of("id", "w1", "title", "Pazartesi kontrolü", "frequency", "WEEKLY", "weekday", 1),
                        Map.of("id", "w2", "title", "Cuma kontrolü", "frequency", "WEEKLY", "weekday", 5)))));
    }

    // ── #1 Devir: rotasyonlu devir günü devir anında kapanmaz ────────────────────────────────────

    @Test
    @DisplayName("#1 devir Pazartesi 10:00, gün 09:00: devir günü (Pzt) devir anında KAPANMAZ, özete girmez (open_days 1); sonraki tamamlama geç değil")
    void handoverLaterThanDayStartKeepsHandoverDayOpen() {
        rig.at(Instant.parse("2026-11-16T06:00:00Z"));   // Pzt 16 Kasım 09:00
        rig.saveTemplate(OnCallRoutineTestRig.template());
        OnCallRotation.Config rot = new OnCallRotation.Config(true, 7, LocalDateTime.parse("2026-11-16T10:00"), List.of(10L));
        Instant mon1030 = Instant.parse("2026-11-23T07:30:00Z");
        rig.at(mon1030);
        TeamCtx c = withRotation(rig.ctx(), rot);
        Period prev = OnCallRoutineCalendar.previous(rot, c.dayStart(), OnCallRoutineCalendar.periodAt(rot, c.dayStart(), mon1030));
        assertThat(prev.key()).isEqualTo(LocalDate.parse("2026-11-17"));
        assertThat(prev.days()).contains(MON);   // devir günü önceki döneme ait

        OnCallRoutineJob job = new OnCallRoutineJob(store, svc, notifier(mon1030));
        assertThat(job.handover(c, mon1030, new int[] {50})).isTrue();
        for (String d : List.of("2026-11-17", "2026-11-18", "2026-11-19", "2026-11-20")) {
            assertThat(store.day(TEAM, d).status()).as(d).isEqualTo("MISSED");
        }
        assertThat(store.day(TEAM, "2026-11-23")).as("devir günü kapanmadı (satır yok)").isNull();
        assertThat(store.itemsOfPeriod(TEAM, "DAY", "2026-11-23")).as("görevleri MISSED olmadı").isEmpty();
        String summary = store.handover(TEAM, "2026-11-17").summaryJson();
        assertThat(summary).contains("\"workdays\":4").contains("\"missed\":4").contains("\"open_days\":1");

        // aynı gün işaretlenip tamamlanır — geç SAYILMAZ, durum COMPLETED
        rig.at(Instant.parse("2026-11-23T08:00:00Z"));
        TeamCtx later = withRotation(rig.ctx(), rot);
        svc.applyState(later, MON, "d1", "DAY", "2026-11-23", "DONE", null, null, A, true);
        svc.applyState(later, MON, "d2", "DAY", "2026-11-23", "DONE", null, null, A, true);
        var done = svc.completeDay(later, MON, store.day(TEAM, "2026-11-23").version(), null, null, A);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(store.day(TEAM, "2026-11-23").completedLate()).isFalse();
        // devir ikinci kez bildirim göndermez
        assertThat(new OnCallRoutineJob(store, svc, notifier(mon1030)).handover(later, mon1030, new int[] {50})).isFalse();
    }

    // ── #2 Gün kapanışı R(D) ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#2(a) yalnız haftalık görevli şablon: geçmiş gün kapanışı satır AÇMAZ (NOTHING_DUE); iş aynı günü bellekten bir daha sormaz")
    void weeklyOnlyTemplateNothingDue() {
        rig.at(MON_0900);
        rig.saveTemplate(Map.of("sections", List.of(Map.of("id", "s2", "title", "Haftalık", "tasks", List.of(
                Map.of("id", "w1", "title", "Yedek deneme", "frequency", "WEEKLY", "weekday", 5))))));
        rig.at(THU_0930);
        TeamCtx c = rig.ctx();
        assertThat(svc.closeDayResult(c, TUE)).isEqualTo(CloseResult.NOTHING_DUE);
        assertThat(store.day(TEAM, "2026-11-24")).isNull();
        assertThat(rig.countEvents("DAY_CLOSE")).isZero();
        OnCallRoutineJob job = new OnCallRoutineJob(store, svc, notifier(THU_0930));
        assertThat(job.closeDays(c, THU_0930, new int[] {50})).isZero();
        assertThat(store.day(TEAM, "2026-11-23")).isNull();
        assertThat(store.day(TEAM, "2026-11-25")).isNull();
        assertThat(svc.wireStatus(c, TUE, null, THU)).isEqualTo("NONE");
    }

    @Test
    @DisplayName("#2(a) yalnız isteğe bağlı günlük görev: satırsız gün NOTHING_DUE; isteğe bağlı işaretlenmiş gün 0 / 0 kendiliğinden tamamlanır")
    void optionalOnlyDailyTemplate() {
        rig.at(MON_0900);
        rig.saveTemplate(Map.of("sections", List.of(Map.of("id", "s1", "title", "Sabah", "tasks", List.of(
                Map.of("id", "opt", "title", "İsteğe bağlı bakış", "required", false))))));
        rig.at(Instant.parse("2026-11-24T07:00:00Z"));   // Sal 10:00
        svc.applyState(rig.ctx(), TUE, "opt", "DAY", "2026-11-24", "DONE", null, null, A, true);
        rig.at(THU_0930);
        TeamCtx c = rig.ctx();
        assertThat(svc.closeDayResult(c, MON)).isEqualTo(CloseResult.NOTHING_DUE);
        assertThat(store.day(TEAM, "2026-11-23")).isNull();
        assertThat(svc.closeDayResult(c, TUE)).isEqualTo(CloseResult.CLOSED);
        DayRow tue = store.day(TEAM, "2026-11-24");
        assertThat(tue.status()).isEqualTo("COMPLETED");
        assertThat(tue.requiredCount()).isZero();
        assertThat(tue.completedByName()).isEqualTo("SiteMonitor");
    }

    @Test
    @DisplayName("#2(b) zorunluların hepsi çözülmüş ama tamamlanmamış gün: kapanış COMPLETED / COMPLETED_PARTIAL (SiteMonitor), geç değil, bildirim yok")
    void allResolvedButNotCompletedAutoCompletes() {
        rig.at(MON_0900);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        rig.at(Instant.parse("2026-11-24T07:00:00Z"));
        TeamCtx tueCtx = rig.ctx();
        svc.applyState(tueCtx, TUE, "d1", "DAY", "2026-11-24", "DONE", null, null, A, true);
        svc.applyState(tueCtx, TUE, "d2", "DAY", "2026-11-24", "DONE", null, null, A, true);
        rig.at(Instant.parse("2026-11-25T07:00:00Z"));
        TeamCtx wedCtx = rig.ctx();
        svc.applyState(wedCtx, WED, "d1", "DAY", "2026-11-25", "DONE", null, null, B, true);
        svc.applyState(wedCtx, WED, "d2", "DAY", "2026-11-25", "SKIPPED", null, "Erişim yoktu", B, true);

        rig.at(THU_0930);
        TeamCtx c = rig.ctx();
        assertThat(svc.closeDayResult(c, TUE)).isEqualTo(CloseResult.CLOSED);
        DayRow tue = store.day(TEAM, "2026-11-24");
        assertThat(tue.status()).isEqualTo("COMPLETED");
        assertThat(tue.requiredCount()).isEqualTo(2);
        assertThat(tue.doneCount()).isEqualTo(2);
        assertThat(tue.pendingCount()).isZero();
        assertThat(tue.completedByName()).isEqualTo("SiteMonitor");
        assertThat(tue.completedById()).isNull();
        assertThat(tue.completedLate()).isFalse();
        assertThat(tue.completionSeq()).isEqualTo(1);
        assertThat(tue.notifiedSeq()).as("kendiliğinden tamamlama bildirim göndermez").isEqualTo(tue.completionSeq());
        assertThat(store.itemsOfPeriod(TEAM, "DAY", "2026-11-24")).as("kendiliğinden tamamlama görevleri MISSED yapmaz")
                .extracting(OnCallRoutinePlan.Row::state).doesNotContain("MISSED");
        assertThat(jdbc.queryForObject("SELECT detail_json FROM oncall_routine_events WHERE team_id = ? AND kind = 'DAY_CLOSE' "
                + "AND routine_day = '2026-11-24'", String.class, TEAM)).contains("auto_completed");

        assertThat(svc.closeDayResult(c, WED)).isEqualTo(CloseResult.CLOSED);
        DayRow wed = store.day(TEAM, "2026-11-25");
        assertThat(wed.status()).isEqualTo("COMPLETED_PARTIAL");
        assertThat(wed.skippedCount()).isEqualTo(1);

        // bildirim işi bu günler için gönderim yapmaz
        assertThat(notifier(THU_0930).sendPendingCompletions(THU_0930, () -> false)).isZero();
        assertThat(store.notice(TEAM, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-24#1")).isNull();
        assertThat(svc.closeDayResult(c, TUE)).as("bir kez").isEqualTo(CloseResult.SKIPPED);
    }

    @Test
    @DisplayName("#2(c) gün sonunda şablondan KALDIRILMIŞ görevin bekleyen satırı zorunlu sayılmaz — gün INCOMPLETE değil COMPLETED")
    void removedTaskRowIsNotRequired() {
        rig.at(MON_0900);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        rig.at(Instant.parse("2026-11-24T07:00:00Z"));   // Sal 10:00
        TeamCtx tue = rig.ctx();
        svc.applyState(tue, TUE, "d1", "DAY", "2026-11-24", "DONE", null, null, A, true);
        svc.saveItemNote(tue, TUE, "d2", "DAY", "2026-11-24", "Bakılacak", 0, A);   // d2 satırı PENDING
        assertThat(store.item(TEAM, "DAY", "2026-11-24", "d2").state()).isEqualTo("PENDING");
        rig.at(Instant.parse("2026-11-24T08:00:00Z"));
        rig.saveTemplate(Map.of("sections", List.of(
                Map.of("id", "s1", "title", "Sabah kontrolleri", "tasks", List.of(
                        Map.of("id", "d1", "title", "Kuyruklar"),
                        Map.of("id", "opt", "title", "İsteğe bağlı bakış", "required", false))),
                Map.of("id", "s2", "title", "Haftalık", "tasks", List.of(
                        Map.of("id", "w1", "title", "Yedek geri yükleme denemesi", "frequency", "WEEKLY", "weekday", 5))))));
        rig.at(THU_0930);
        assertThat(svc.closeDayResult(rig.ctx(), TUE)).isEqualTo(CloseResult.CLOSED);
        DayRow row = store.day(TEAM, "2026-11-24");
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.requiredCount()).isEqualTo(1);
        assertThat(row.pendingCount()).isZero();
    }

    // ── #3 + #13 Dönem kapanışı ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#3 + #13 dönem kapanışı: etkinleşmeden önceki hedef günü görev OLUŞTURMAZ; kalıcı işaret — yeni pod kapanıştan sonra PENDING'e çekileni yeniden MISSED yapmaz")
    void periodCloseFirstPeriodAndPersistentMarker() {
        rig.at(Instant.parse("2026-11-25T06:00:00Z"));   // Çar 09:00 — rutin bugün başlar
        rig.saveTemplate(templateMonFri());
        Instant tue1201 = Instant.parse("2026-12-01T06:30:00Z");
        rig.at(tue1201);
        TeamCtx c = rig.ctx();
        assertThat(store.periodClosedThrough(TEAM, "WEEK")).isNull();
        OnCallRoutineJob pod1 = new OnCallRoutineJob(store, svc, notifier(tue1201));
        assertThat(pod1.closePeriods(c, tue1201)).isEqualTo(1);
        assertThat(store.item(TEAM, "WEEK", "2026-11-23", "w1")).as("Pazartesi hedefi rutin başlamadan önce").isNull();
        assertThat(store.item(TEAM, "WEEK", "2026-11-23", "w2").state()).isEqualTo("MISSED");
        assertThat(store.periodClosedThrough(TEAM, "WEEK")).isEqualTo("2026-11-23");

        // Cuma'nın görevi düzenleme penceresinde geri alınır (PENDING)
        svc.applyState(c, FRI, "w2", "WEEK", "2026-11-23", "PENDING", null, null, A, true);
        assertThat(store.item(TEAM, "WEEK", "2026-11-23", "w2").state()).isEqualTo("PENDING");
        // başka pod / yeniden başlatma (bellek boş): dönem yeniden kapanmaz
        OnCallRoutineJob pod2 = new OnCallRoutineJob(store, svc, notifier(tue1201));
        int[] budget = {50};
        assertThat(pod2.closePeriods(c, tue1201, budget)).isZero();
        assertThat(budget[0]).as("kapanmış dönem bütçe harcamaz").isEqualTo(50);
        assertThat(svc.closePeriod(c, "WEEK", "2026-11-23", LocalDate.parse("2026-11-29"))).isZero();
        assertThat(store.item(TEAM, "WEEK", "2026-11-23", "w2").state()).isEqualTo("PENDING");
        // işaret yalnız ileri gider
        assertThat(store.markPeriodClosed(TEAM, "WEEK", "2026-11-16")).isZero();
        assertThat(store.markPeriodClosed(TEAM, "WEEK", "2026-11-30")).isEqualTo(1);
        assertThat(store.periodClosedThrough(TEAM, "MONTH")).isNull();
    }

    // ── #5 İş günü olmayan gün ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#5 hafta sonu / tatil: haftalık görev işareti ve genel sorun gün satırı AÇMAZ; gün notu NOT_WORKDAY")
    void nonWorkdayWritesOpenNoDayRow() {
        rig.at(MON_0900);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        rig.at(Instant.parse("2026-11-27T07:00:00Z"));   // Cuma 10:00 — haftalık görev satırı oluşur
        svc.saveItemNote(rig.ctx(), FRI, "w1", "WEEK", "2026-11-23", "Pazartesi bakılacak", 0, A);
        assertThat(store.day(TEAM, "2026-11-27")).isNotNull();

        rig.at(Instant.parse("2026-11-28T07:00:00Z"));   // Cumartesi 10:00
        TeamCtx sat = rig.ctx();
        assertThat(svc.applyState(sat, SAT, "w1", "WEEK", "2026-11-23", "DONE", null, null, A, true).status()).isEqualTo("done");
        assertThat(store.day(TEAM, "2026-11-28")).as("hafta sonuna gün satırı açılmaz").isNull();
        svc.createProblem(sat, SAT, null, null, null, "LOW", "Hafta sonu gözlemi", false, A);
        assertThat(store.day(TEAM, "2026-11-28")).isNull();
        assertThatThrownBy(() -> svc.saveDayNote(sat, SAT, "Not", 0, A))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("NOT_WORKDAY"));
        assertThat(svc.wireStatus(sat, SAT, null, SAT)).isEqualTo("NOT_WORKDAY");

        // tatil (iş günü kuralı tatili atlar): Cuma tatil sayılırsa yeni satır yok, gün notu reddedilir
        rig.clean();
        rig.at(MON_0900);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        rig.at(Instant.parse("2026-11-26T07:00:00Z"));   // Perşembe 10:00 (normal iş günü) — haftalık satır oluşur
        svc.saveItemNote(rig.ctx(), THU, "w1", "WEEK", "2026-11-23", "Bakılacak", 0, A);
        rig.at(Instant.parse("2026-11-27T07:00:00Z"));
        TeamCtx holiday = withHolidays(rig.ctx(), Set.of(FRI));
        svc.applyState(holiday, FRI, "w1", "WEEK", "2026-11-23", "DONE", null, null, A, true);
        assertThat(store.day(TEAM, "2026-11-27")).isNull();
        assertThatThrownBy(() -> svc.saveDayNote(holiday, FRI, "Not", 0, A))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("NOT_WORKDAY"));
    }

    // ── #9 Takımdan çıkarılan devralan ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("#9 takımdan çıkarılan devralan yok sayılır: gün devri → hafta devrine, o da yoksa NONE; tel 'ignored_assignment'; üyelik okunamazsa korunur")
    void removedAssigneeFallsBack() {
        rig.saveTemplate(OnCallRoutineTestRig.template());
        TeamCtx c = rig.ctx();
        rig.person(21L, "N21", "Zeynep Kaya", true, true);
        rig.person(22L, "N22", "Ali Veli", true, true);
        svc.assign(c, "WEEK", "2026-11-23", MON, 21L, "Zeynep Kaya", null, A);
        svc.assign(c, "DAY", "2026-11-26", THU, 22L, "Ali Veli", null, A);
        assertThat(svc.responsible(c, THU).userId()).isEqualTo(22L);

        rig.members.put(22L, false);   // Ali takımdan çıkarıldı
        Responsible r = svc.responsible(c, THU);
        assertThat(r.userId()).isEqualTo(21L);
        assertThat(r.source()).isEqualTo("TAKEOVER_WEEK");
        @SuppressWarnings("unchecked")
        Map<String, Object> ig = (Map<String, Object>) svc.responsibleWire(c, r, 10L).get("ignored_assignment");
        assertThat(ig).containsEntry("scope", "DAY").containsEntry("key", "2026-11-26").containsEntry("name", "Ali Veli")
                .containsEntry("reason", "not_member");

        rig.members.put(21L, false);   // Zeynep de çıkarıldı → çizelge yok → NONE
        Responsible none = svc.responsible(c, THU);
        assertThat(none.source()).isEqualTo("NONE");
        assertThat(none.userId()).isNull();
        assertThat(svc.responsibleWire(c, none, 10L).get("ignored_assignment")).isNotNull();
        // devralmasız gün: alan null
        assertThat(svc.responsibleWire(c, svc.responsible(c, LocalDate.parse("2026-12-07")), 10L))
                .containsEntry("ignored_assignment", null);

        // üyelik okunamazsa devralma KORUNUR (okuma hatası sorumluyu sessizce düşürmez)
        when(rig.users.findActiveMemberRowsOfTeams(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        assertThat(svc.responsible(c, THU).userId()).isEqualTo(22L);
    }

    // ── #11 Geçmiş günün sorumlusu ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#11 geçmiş günün geç tamamlanması satırdaki sorumluyu korur; bugün tamamlanırken sorumlu yeniden çözülür")
    void pastCompletionKeepsResponsibleSnapshot() {
        rig.at(MON_0900);
        rig.saveTemplate(OnCallRoutineTestRig.template());
        rig.person(21L, "N21", "Zeynep Kaya", true, true);
        rig.person(22L, "N22", "Ali Veli", true, true);
        rig.at(Instant.parse("2026-11-25T07:00:00Z"));   // Çar 10:00
        TeamCtx wed = rig.ctx();
        svc.assign(wed, "DAY", "2026-11-25", WED, 21L, "Zeynep Kaya", null, A);
        svc.applyState(wed, WED, "d1", "DAY", "2026-11-25", "DONE", null, null, A, true);
        assertThat(store.day(TEAM, "2026-11-25").responsibleUserId()).isEqualTo(21L);

        rig.at(THU_0930);
        TeamCtx c = rig.ctx();
        svc.clearAssignment(c, "DAY", "2026-11-25", A);
        svc.applyState(c, WED, "d2", "DAY", "2026-11-25", "DONE", null, null, A, true);
        svc.completeDay(c, WED, store.day(TEAM, "2026-11-25").version(), null, null, A);
        DayRow done = store.day(TEAM, "2026-11-25");
        assertThat(done.completedLate()).isTrue();
        assertThat(done.responsibleUserId()).as("anlık görüntü ezilmedi").isEqualTo(21L);
        assertThat(done.responsibleName()).isEqualTo("Zeynep Kaya");
        assertThat(done.responsibleSource()).isEqualTo("TAKEOVER_DAY");

        // bugün: satır sorumlusuz açıldı, tamamlarken o anki devralan yazılır
        svc.applyState(c, THU, "d1", "DAY", "2026-11-26", "DONE", null, null, A, true);
        svc.applyState(c, THU, "d2", "DAY", "2026-11-26", "DONE", null, null, A, true);
        svc.assign(c, "DAY", "2026-11-26", THU, 22L, "Ali Veli", null, A);
        svc.completeDay(c, THU, store.day(TEAM, "2026-11-26").version(), null, null, A);
        assertThat(store.day(TEAM, "2026-11-26").responsibleUserId()).isEqualTo(22L);
    }

    // ── Kayıt yanıtı düzenleyeni taşır (ön yüz incelemesi) ───────────────────────────────────────

    @Test
    @DisplayName("şablon / ayar kaydı yanıtı GET ile aynı anahtarlarla düzenleyen adı + zamanı taşır; değişmeyen kayıt saklı değeri döner")
    void saveResponsesCarryEditor() {
        var first = svc.saveTemplate(TEAM, 0, OnCallRoutineTestRig.template(), "EDITOR", A);
        assertThat(first.data()).containsEntry("template_updated_by_name", "Kişi A")
                .containsEntry("template_updated_at", "2026-11-26T06:30:00Z");
        rig.at(THU_0930.plusSeconds(600));
        var same = svc.saveTemplate(TEAM, 1, OnCallRoutineTestRig.template(), "EDITOR", B);
        assertThat(same.changed()).isFalse();
        assertThat(same.data()).containsEntry("template_updated_by_name", "Kişi A")
                .containsEntry("template_updated_at", "2026-11-26T06:30:00Z");
        Map<String, Object> view = svc.templateView(rig.ctx(), true, (a, b) -> true);
        assertThat(view.get("template_updated_by_name")).isEqualTo(first.data().get("template_updated_by_name"));
        assertThat(view.get("template_updated_at")).isEqualTo(first.data().get("template_updated_at"));

        var settings = svc.saveSettings(TEAM, 0, Map.of("workdays", List.of(1, 2, 3, 4)), B);
        assertThat(settings.data()).containsEntry("settings_updated_by_name", "Kişi B")
                .containsEntry("settings_updated_at", "2026-11-26T06:40:00Z");
        rig.at(THU_0930.plusSeconds(1200));
        var noop = svc.saveSettings(TEAM, 1, Map.of("workdays", List.of(1, 2, 3, 4)), A);
        assertThat(noop.changed()).isFalse();
        assertThat(noop.data()).containsEntry("settings_updated_by_name", "Kişi B")
                .containsEntry("settings_updated_at", "2026-11-26T06:40:00Z");
    }
}

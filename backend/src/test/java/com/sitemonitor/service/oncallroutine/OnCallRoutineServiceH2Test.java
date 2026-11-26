package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.oncallroutine.OnCallRoutineCalendar.Period;
import com.sitemonitor.service.oncallroutine.OnCallRoutinePlan.Row;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.Perms;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.Responsible;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.TeamCtx;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.DayRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.ProblemRow;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.A;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.B;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.MON_0900;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.TEAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nöbet rutini servisi — GERÇEK SQL (H2, PostgreSQL kipi; {@code ON CONFLICT}, {@code FOR UPDATE}). Testler commit eder
 * ({@code NOT_SUPPORTED}); her testin öncesinde ve sonrasında rutin tabloları temizlenir.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)   // MODE=PostgreSQL (ON CONFLICT)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OnCallRoutineServiceH2Test {

    static final LocalDate TUE = LocalDate.parse("2026-11-24");
    static final LocalDate WED = LocalDate.parse("2026-11-25");
    static final LocalDate THU = LocalDate.parse("2026-11-26");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;

    OnCallRoutineTestRig rig;
    OnCallRoutineService svc;
    OnCallRoutineStore store;

    @BeforeEach
    void setUp() {
        rig = new OnCallRoutineTestRig(jdbc, txm);
        rig.clean();
        svc = rig.svc;
        store = rig.store;
    }

    @AfterEach
    void tearDown() {
        rig.clean();
    }

    static String codeOf(Throwable e) {
        if (e instanceof CodedConflictException c) return c.getCode();
        if (e instanceof CodedNotFoundException n) return n.getCode();
        if (e instanceof FieldValidationException f) return f.getCode();
        return e.getClass().getSimpleName();
    }

    TeamCtx configured() {
        rig.saveTemplate(OnCallRoutineTestRig.template());
        return rig.ctx();
    }

    // ── Şablon / ayar CAS ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("şablon: ilk kayıt v1 + etkin; aynı içerik değişmez (eski sürümle bile); farklı içerik eski sürümle 409")
    void templateCas() {
        var first = svc.saveTemplate(TEAM, 0, OnCallRoutineTestRig.template(), "EDITOR", A);
        assertThat(first.changed()).isTrue();
        assertThat(first.data().get("template_version")).isEqualTo(1);
        assertThat(first.audit()).containsEntry("version", 1).containsEntry("added", 4);
        assertThat(store.team(TEAM).enabled()).isTrue();
        assertThat(store.team(TEAM).enabledSince()).isEqualTo("2026-11-26");
        assertThat(rig.ctx().configured()).isTrue();

        var same = svc.saveTemplate(TEAM, 0, OnCallRoutineTestRig.template(), "EDITOR", B);
        assertThat(same.changed()).isFalse();
        assertThat(same.audit()).isNull();

        assertThatThrownBy(() -> svc.saveTemplate(TEAM, 0, OnCallRoutineTestRig.templateWithD3(), "EDITOR", B))
                .isInstanceOf(CodedConflictException.class)
                .satisfies(e -> {
                    CodedConflictException c = (CodedConflictException) e;
                    assertThat(c.getCode()).isEqualTo("CONCURRENT_MODIFICATION");
                    assertThat(c.getExtra()).containsEntry("current_version", 1).containsEntry("updated_by_name", "Kişi A");
                });
        assertThatThrownBy(() -> svc.saveTemplate(TEAM, null, OnCallRoutineTestRig.templateWithD3(), "EDITOR", B))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("CONCURRENT_MODIFICATION"));

        var second = svc.saveTemplate(TEAM, 1, OnCallRoutineTestRig.templateWithD3(), "SAMPLE", B);
        assertThat(second.data().get("template_version")).isEqualTo(2);
        assertThat(second.audit()).containsEntry("added", 1).containsEntry("source", "SAMPLE");
        assertThat(svc.versions(TEAM)).extracting(m -> m.get("version")).containsExactly(2, 1);
        assertThat(svc.version(TEAM, 1).get("saved_by_name")).isEqualTo("Kişi A");
        assertThatThrownBy(() -> svc.version(TEAM, 9)).satisfies(e -> assertThat(codeOf(e)).isEqualTo("VERSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("sürüm budaması: en yeni N tutulur (tek toplu DELETE)")
    void pruneVersions() {
        for (int v = 1; v <= 5; v++) store.insertTemplateVersion(TEAM, v, "{}", "{}", "EDITOR", "2026-11-2" + v + "T06:00:00", "x");
        assertThat(store.pruneTemplateVersions(TEAM, 3)).isEqualTo(2);
        assertThat(store.versions(TEAM, 10)).extracting(OnCallRoutineStore.VersionRow::version).containsExactly(5, 4, 3);
        // aynı sürüm iki kez eklenmez
        store.insertTemplateVersion(TEAM, 5, "{}", "{}", "EDITOR", "2026-11-30T06:00:00", "y");
        assertThat(store.versions(TEAM, 10)).hasSize(3);
        // o anda geçerli sürüm: kayıt anı ≤ verilen an
        assertThat(store.templateAt(TEAM, "2026-11-24T23:59:59").version()).isEqualTo(4);
        assertThat(store.templateAt(TEAM, "2026-11-20T00:00:00")).isNull();
    }

    @Test
    @DisplayName("ayarlar: CAS, aynı değer no-op, kapatma paused_since = bugün yazar; duraklatılmış güne yazılamaz, önceki güne yazılır")
    void settingsCasAndPause() {
        configured();
        var r = svc.saveSettings(TEAM, 0, Map.of("day_start", "08:30"), A);
        assertThat(r.changed()).isTrue();
        assertThat(r.data().get("settings_version")).isEqualTo(1);
        assertThat(svc.saveSettings(TEAM, 0, Map.of("day_start", "08:30"), B).changed()).isFalse();
        assertThatThrownBy(() -> svc.saveSettings(TEAM, 0, Map.of("day_start", "07:00"), B))
                .satisfies(e -> assertThat(((CodedConflictException) e).getExtra()).containsEntry("current_version", 1));

        svc.saveSettings(TEAM, 1, Map.of("enabled", false), A);
        assertThat(store.team(TEAM).enabled()).isFalse();
        assertThat(store.team(TEAM).pausedSince()).isEqualTo("2026-11-26");
        TeamCtx paused = rig.ctx();
        assertThatThrownBy(() -> svc.applyState(paused, THU, "d1", "DAY", "2026-11-26", "DONE", null, null, A, true))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("ROUTINE_PAUSED"));
        assertThat(svc.applyState(paused, WED, "d1", "DAY", "2026-11-25", "DONE", null, null, A, true).status()).isEqualTo("done");
    }

    // ── İşaretleme ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("işaret: done → aynı durum unchanged; beklenen durum tutmazsa ITEM_CHANGED; tek ITEM_DONE olayı")
    void applyState() {
        TeamCtx c = configured();
        var r = svc.applyState(c, THU, "d1", "DAY", "2026-11-26", "DONE", "PENDING", null, A, true);
        assertThat(r.status()).isEqualTo("done");
        assertThat(r.row().state()).isEqualTo("DONE");
        assertThat(r.row().stateByName()).isEqualTo("Kişi A");
        assertThat(svc.applyState(c, THU, "d1", "DAY", "2026-11-26", "DONE", "PENDING", null, B, true).status())
                .isEqualTo("unchanged");
        assertThatThrownBy(() -> svc.applyState(c, THU, "d1", "DAY", "2026-11-26", "PENDING", "SKIPPED", null, B, true))
                .isInstanceOf(CodedConflictException.class)
                .satisfies(e -> {
                    assertThat(codeOf(e)).isEqualTo("ITEM_CHANGED");
                    assertThat(((CodedConflictException) e).getExtra().toString()).contains("DONE");
                });
        assertThat(rig.countEvents("ITEM_DONE")).isEqualTo(1);
        // atlama nedeni yazılır, geri alma nedeni siler
        var s = svc.applyState(c, THU, "d2", "DAY", "2026-11-26", "SKIPPED", null, "Sistem kapalıydı", A, true);
        assertThat(s.row().skipReason()).isEqualTo("Sistem kapalıydı");
        assertThat(svc.applyState(c, THU, "d2", "DAY", "2026-11-26", "PENDING", null, null, A, true).row().skipReason()).isNull();
        // toplu işaret olay yazmaz (tek SECTION_DONE yazılır)
        svc.applyState(c, THU, "opt", "DAY", "2026-11-26", "DONE", null, null, A, false);
        assertThat(rig.countEvents("ITEM_DONE")).isEqualTo(1);
    }

    @Test
    @DisplayName("iki iş parçacığı aynı görevi aynı anda işaretler: biri done, öbürü unchanged; tek olay")
    void concurrentTick() throws Exception {
        TeamCtx c = configured();
        svc.ensureDayRow(c, THU, svc.now());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier go = new CyclicBarrier(2);
            List<Future<String>> fs = new ArrayList<>();
            for (var who : List.of(A, B)) {
                fs.add(pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    return svc.applyState(c, THU, "d1", "DAY", "2026-11-26", "DONE", "PENDING", null, who, true).status();
                }));
            }
            List<String> out = new ArrayList<>();
            for (Future<String> f : fs) out.add(f.get(30, TimeUnit.SECONDS));
            assertThat(out).containsExactlyInAnyOrder("done", "unchanged");
        } finally {
            pool.shutdownNow();
        }
        assertThat(rig.countEvents("ITEM_DONE")).isEqualTo(1);
        assertThat(rig.count("oncall_routine_items")).isEqualTo(1);
    }

    @Test
    @DisplayName("yazma penceresi: gelecek gün, 7 günden eski, yapılandırılmamış takım, iş günü olmayan gün, şablonda olmayan görev")
    void writeWindow() {
        TeamCtx c = configured();
        assertThatThrownBy(() -> svc.applyState(c, THU.plusDays(1), "d1", "DAY", "2026-11-27", "DONE", null, null, A, true))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("DAY_IN_FUTURE"));
        assertThatThrownBy(() -> svc.applyState(c, THU.minusDays(8), "d1", "DAY", "2026-11-18", "DONE", null, null, A, true))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("DAY_LOCKED"));
        assertThatThrownBy(() -> svc.applyState(c, LocalDate.parse("2026-11-22"), "d1", "DAY", "2026-11-22", "DONE", null, null, A, true))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("NOT_WORKDAY"));
        assertThatThrownBy(() -> svc.applyState(c, THU, "nope", "DAY", "2026-11-26", "DONE", null, null, A, true))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("TASK_NOT_FOUND"));
        // hafta görevi gün anahtarıyla istenirse dönem uyuşmazlığı
        assertThatThrownBy(() -> svc.applyState(c, THU, "w1", "DAY", "2026-11-26", "DONE", null, null, A, true))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("PERIOD_MISMATCH"));
        // haftalık görev son gününden önce işaretlenebilir
        assertThat(svc.applyState(c, THU, "w1", "WEEK", "2026-11-23", "DONE", null, null, A, true).status()).isEqualTo("done");
        rig.team(8L, "Boş Takım", true);
        TeamCtx empty = svc.ctx(8L);
        assertThatThrownBy(() -> svc.applyState(empty, THU, "d1", "DAY", "2026-11-26", "DONE", null, null, A, true))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("ROUTINE_NOT_CONFIGURED"));
        assertThatThrownBy(() -> svc.ctx(99L)).satisfies(e -> assertThat(codeOf(e)).isEqualTo("TEAM_NOT_FOUND"));
    }

    // ── Notlar ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("görev / gün notu: not_version CAS; aynı metin no-op; eski sürüm NOTE_CHANGED + güncel not")
    void notesCas() {
        TeamCtx c = configured();
        var n1 = svc.saveItemNote(c, THU, "d1", "DAY", "2026-11-26", "Kuyruk 12'de", 0, A);
        assertThat(n1.changed()).isTrue();
        assertThat(n1.current()).containsEntry("note", "Kuyruk 12'de").containsEntry("note_version", 1);
        assertThat(svc.saveItemNote(c, THU, "d1", "DAY", "2026-11-26", "Kuyruk 12'de", 0, B).changed()).isFalse();
        assertThatThrownBy(() -> svc.saveItemNote(c, THU, "d1", "DAY", "2026-11-26", "Başka", 0, B))
                .satisfies(e -> {
                    assertThat(codeOf(e)).isEqualTo("NOTE_CHANGED");
                    assertThat(((CodedConflictException) e).getExtra().toString()).contains("Kuyruk 12'de");
                });
        assertThat(svc.saveItemNote(c, THU, "d1", "DAY", "2026-11-26", "", 1, B).current()).containsEntry("note", "");

        var d1 = svc.saveDayNote(c, THU, "Sakin gün", 0, A);
        assertThat(d1.current()).containsEntry("note_version", 1);
        assertThatThrownBy(() -> svc.saveDayNote(c, THU, "Yoğun gün", 0, B))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("NOTE_CHANGED"));
        assertThat(rig.countEvents("ITEM_NOTE")).isEqualTo(2);
        assertThat(rig.countEvents("DAY_NOTE")).isEqualTo(1);
    }

    // ── Gün tamamla / yeniden aç ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tamamla: bekleyen zorunlu → DAY_NOT_READY (hiçbir şey yazılmaz); nedenle → COMPLETED_PARTIAL; sürüm CAS; yeniden aç → seq 2")
    void completeAndReopen() {
        TeamCtx c = configured();
        svc.applyState(c, THU, "d1", "DAY", "2026-11-26", "DONE", null, null, A, true);
        assertThatThrownBy(() -> svc.completeDay(c, THU, 0, null, null, A))
                .satisfies(e -> {
                    assertThat(codeOf(e)).isEqualTo("DAY_NOT_READY");
                    assertThat(((CodedConflictException) e).getExtra().toString()).contains("d2").doesNotContain("opt");
                });
        assertThat(store.day(TEAM, "2026-11-26").status()).isEqualTo("OPEN");
        assertThat(store.item(TEAM, "DAY", "2026-11-26", "d2")).isNull();   // geri alındı
        assertThatThrownBy(() -> svc.completeDay(c, THU, 7, "Erişim yoktu", null, A))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("CONCURRENT_MODIFICATION"));

        var done = svc.completeDay(c, THU, 0, "Erişim yoktu", "Gün notu", A);
        assertThat(done.status()).isEqualTo("COMPLETED_PARTIAL");
        assertThat(done.seq()).isEqualTo(1);
        assertThat(done.audit()).containsEntry("late", false).containsEntry("skipped", 1).containsEntry("done", 1);
        DayRow day = store.day(TEAM, "2026-11-26");
        assertThat(day.completedByName()).isEqualTo("Kişi A");
        assertThat(day.note()).isEqualTo("Gün notu");
        assertThat(store.item(TEAM, "DAY", "2026-11-26", "d2").state()).isEqualTo("SKIPPED");
        assertThat(store.item(TEAM, "DAY", "2026-11-26", "d2").skipReason()).isEqualTo("Erişim yoktu");
        assertThat(store.item(TEAM, "DAY", "2026-11-26", "opt").state()).isEqualTo("PENDING");   // isteğe bağlı atlanmaz
        assertThatThrownBy(() -> svc.completeDay(c, THU, day.version(), null, null, A))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("DAY_COMPLETED"));
        assertThatThrownBy(() -> svc.applyState(c, THU, "d2", "DAY", "2026-11-26", "DONE", null, null, A, true))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("DAY_COMPLETED"));
        // haftalık görev tamamlanmış günde de işaretlenebilir
        assertThat(svc.applyState(c, THU, "w1", "WEEK", "2026-11-23", "DONE", null, null, A, true).status()).isEqualTo("done");

        assertThatThrownBy(() -> svc.reopenDay(c, THU, day.version() + 1, B))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("CONCURRENT_MODIFICATION"));
        DayRow open = svc.reopenDay(c, THU, day.version(), B);
        assertThat(open.status()).isEqualTo("OPEN");
        svc.applyState(c, THU, "d2", "DAY", "2026-11-26", "DONE", null, null, B, true);
        var again = svc.completeDay(c, THU, open.version(), null, null, B);
        assertThat(again.status()).isEqualTo("COMPLETED");
        assertThat(again.seq()).isEqualTo(2);
        assertThat(rig.countEvents("DAY_COMPLETE")).isEqualTo(2);
        assertThat(rig.countEvents("DAY_REOPEN")).isEqualTo(1);
    }

    @Test
    @DisplayName("geçmiş günü tamamlamak geç sayılır; iş günü olmayan gün tamamlanamaz")
    void lateCompletion() {
        TeamCtx c = configured();
        svc.applyState(c, WED, "d1", "DAY", "2026-11-25", "DONE", null, null, A, true);
        svc.applyState(c, WED, "d2", "DAY", "2026-11-25", "DONE", null, null, A, true);
        var r = svc.completeDay(c, WED, store.day(TEAM, "2026-11-25").version(), null, null, A);
        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(store.day(TEAM, "2026-11-25").completedLate()).isTrue();
        assertThatThrownBy(() -> svc.completeDay(c, LocalDate.parse("2026-11-22"), 0, null, null, A))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("NOT_WORKDAY"));
    }

    // ── Kapanış ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("gün kapanışı GÜN SONUNDAKİ şablon sürümüyle: sonradan eklenen görev eski güne girmez; MISSED / INCOMPLETE; bir kez")
    void closeDayUsesTemplateAtDayEnd() {
        rig.at(MON_0900);
        rig.saveTemplate(OnCallRoutineTestRig.template());               // v1 Pazartesi
        rig.at(OnCallRoutineTestRig.THU_0930);
        rig.saveTemplate(OnCallRoutineTestRig.templateWithD3());         // v2 Perşembe (d3 eklendi)
        TeamCtx c = rig.ctx();
        svc.applyState(c, TUE, "d1", "DAY", "2026-11-24", "DONE", null, null, A, true);

        assertThat(svc.closeDay(c, WED)).isTrue();
        DayRow wed = store.day(TEAM, "2026-11-25");
        assertThat(wed.status()).isEqualTo("MISSED");
        assertThat(wed.templateVersion()).isEqualTo(1);
        assertThat(wed.requiredCount()).isEqualTo(2);
        assertThat(wed.pendingCount()).isEqualTo(2);
        assertThat(store.itemsOfPeriod(TEAM, "DAY", "2026-11-25")).extracting(Row::taskId)
                .containsExactlyInAnyOrder("d1", "d2", "opt").doesNotContain("d3");
        assertThat(store.item(TEAM, "DAY", "2026-11-25", "d1").state()).isEqualTo("MISSED");

        assertThat(svc.closeDay(c, TUE)).isTrue();
        DayRow tue = store.day(TEAM, "2026-11-24");
        assertThat(tue.status()).isEqualTo("INCOMPLETE");
        assertThat(tue.doneCount()).isEqualTo(1);
        assertThat(svc.closeDay(c, TUE)).isFalse();                       // bir kez
        assertThat(rig.countEvents("DAY_CLOSE")).isEqualTo(2);

        // kapanmış gün geç tamamlanabilir: donmuş sayfa, kaçırılan görev nedenle atlanır, d3 eklenmez
        assertThatThrownBy(() -> svc.completeDay(c, TUE, tue.version(), null, null, A))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("DAY_NOT_READY"));
        var late = svc.completeDay(c, TUE, tue.version(), "Gün kaçtı", null, A);
        assertThat(late.status()).isEqualTo("COMPLETED_PARTIAL");
        assertThat(store.day(TEAM, "2026-11-24").completedLate()).isTrue();
        assertThat(store.itemsOfPeriod(TEAM, "DAY", "2026-11-24")).extracting(Row::taskId).doesNotContain("d3");
    }

    @Test
    @DisplayName("gün sonunda şablon yoksa kapanış dokunmaz")
    void closeDayWithoutTemplateAtDayEnd() {
        TeamCtx c = configured();   // Perşembe kaydedildi
        assertThat(svc.closeDay(c, WED)).isFalse();
        assertThat(store.day(TEAM, "2026-11-25")).isNull();
    }

    // ── Sorunlar ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sorun: 'sorun var' görevi işaretler; tamamlanmış günde görev değişmez ama sorun yazılır; görsel sınırı 4; çöz / sürüm CAS; sil")
    void problems() {
        TeamCtx c = configured();
        var p1 = svc.createProblem(c, THU, "d1", "DAY", "2026-11-26", "HIGH", "Kuyruk 500'ü geçti", true, A);
        assertThat(p1.itemTouched()).isTrue();
        assertThat(store.item(TEAM, "DAY", "2026-11-26", "d1").state()).isEqualTo("DONE");
        assertThat(p1.problem().taskTitle()).isEqualTo("Kuyruklar");
        var general = svc.createProblem(c, THU, null, null, null, "LOW", "Genel bir gözlem", false, A);
        assertThat(general.problem().itemId()).isNull();

        svc.completeDay(c, THU, store.day(TEAM, "2026-11-26").version(), "Zaman yetmedi", null, A);
        var p2 = svc.createProblem(c, THU, "d2", "DAY", "2026-11-26", "MEDIUM", "Yedek yarım kaldı", true, A);
        assertThat(p2.itemTouched()).isFalse();
        assertThat(store.item(TEAM, "DAY", "2026-11-26", "d2").state()).isEqualTo("SKIPPED");

        byte[] img = OnCallRoutineTestRig.png(20);
        for (int i = 0; i < 4; i++) svc.uploadImage(p1.problem(), img, "ekran-" + i + ".png", A);
        assertThatThrownBy(() -> svc.uploadImage(p1.problem(), img, "fazla.png", A))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("PROBLEM_IMAGE_LIMIT"));
        assertThat(store.countImages(p1.problem().id())).isEqualTo(4);

        Perms perms = Perms.readOnly(10L);
        ProblemRow cur = svc.problemOrThrow(p1.problem().id());
        ProblemRow resolved = svc.updateProblem(cur, cur.version(),
                Map.of("status", "RESOLVED", "resolution_note", "Kuyruk boşaltıldı"), B, c, perms);
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolvedByName()).isEqualTo("Kişi B");
        assertThat(resolved.resolvedDay()).isEqualTo("2026-11-26");
        assertThatThrownBy(() -> svc.updateProblem(cur, cur.version(), Map.of("note", "Yeni not var"), B, c, perms))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("CONCURRENT_MODIFICATION"));
        assertThat(rig.countEvents("PROBLEM_RESOLVE")).isEqualTo(1);

        svc.deleteProblem(svc.problemOrThrow(p1.problem().id()), A);
        assertThat(store.problem(p1.problem().id())).isNull();
        assertThat(store.countImages(p1.problem().id())).isZero();
        assertThatThrownBy(() -> svc.problemOrThrow(p1.problem().id()))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("PROBLEM_NOT_FOUND"));
    }

    // ── Devralma / sorumlu ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("devralma: gün devri hafta devrinin önündedir; anahtar kuralları; silinen kullanıcının devralmaları gider")
    void assignmentsPrecedence() {
        TeamCtx c = configured();
        rig.person(21L, "N21", "Zeynep Kaya", true, true);
        rig.person(22L, "N22", "Ali Veli", true, true);
        assertThat(svc.responsible(c, THU).source()).isEqualTo("NONE");

        assertThat(svc.assignmentFirstDay(c, "WEEK", "2026-11-23")).isEqualTo(LocalDate.parse("2026-11-23"));
        assertThatThrownBy(() -> svc.assignmentFirstDay(c, "WEEK", "2026-11-24"))
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("key"));
        assertThatThrownBy(() -> svc.assignmentFirstDay(c, "DAY", "2026-11-10"))
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("key"));
        assertThatThrownBy(() -> svc.assignmentFirstDay(c, "DAY", "2027-02-01"))
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("key"));
        assertThatThrownBy(() -> svc.assignmentFirstDay(c, "MONTH", "2026-11-26"))
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("scope"));
        assertThatThrownBy(() -> svc.assignmentFirstDay(c, "DAY", "26.11.2026"))
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("key"));

        Responsible week = svc.assign(c, "WEEK", "2026-11-23", LocalDate.parse("2026-11-23"), 21L, "Zeynep Kaya", null, A);
        assertThat(week.userId()).isEqualTo(21L);
        assertThat(week.source()).isEqualTo("TAKEOVER_WEEK");
        assertThat(svc.responsible(c, THU).userId()).isEqualTo(21L);
        Responsible day = svc.assign(c, "DAY", "2026-11-26", THU, 22L, "Ali Veli", "Toplantı", A);
        assertThat(day.userId()).isEqualTo(22L);
        assertThat(day.source()).isEqualTo("TAKEOVER_DAY");
        assertThat(svc.responsible(c, WED).userId()).isEqualTo(21L);   // gün devri yalnız o gün

        assertThat(svc.activeMember(TEAM, 21L)).isTrue();
        assertThat(svc.activeMember(TEAM, 999L)).isFalse();

        assertThat(svc.clearAssignment(c, "DAY", "2026-11-26", A)).isTrue();
        assertThat(svc.clearAssignment(c, "DAY", "2026-11-26", A)).isFalse();
        assertThat(svc.responsible(c, THU).userId()).isEqualTo(21L);
        svc.deleteUser(21L);   // işlem yok → hemen
        assertThat(svc.responsible(c, THU).source()).isEqualTo("NONE");
        assertThat(rig.countEvents("TAKEOVER")).isEqualTo(2);
        assertThat(rig.countEvents("TAKEOVER_CLEAR")).isEqualTo(1);
    }

    // ── Devir notu / onayı ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("devir: geçerli dönem onaylanamaz (HANDOVER_NOT_DUE); önceki dönem bir kez onaylanır; not CAS; anahtar kuralı")
    void handoverNoteAndAck() {
        TeamCtx c = configured();
        Period cur = svc.periodForKey(c, "2026-11-23", true);
        assertThat(cur.key()).isEqualTo(LocalDate.parse("2026-11-23"));
        assertThatThrownBy(() -> svc.ackHandover(c, cur, A)).satisfies(e -> assertThat(codeOf(e)).isEqualTo("HANDOVER_NOT_DUE"));
        Period prev = svc.periodForKey(c, "2026-11-16", false);
        assertThatThrownBy(() -> svc.periodForKey(c, "2026-11-23", false))
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("period_key"));
        assertThatThrownBy(() -> svc.periodForKey(c, "2026-11-09", true))
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("period_key"));

        var ack = svc.ackHandover(c, prev, B);
        assertThat(ack.changed()).isTrue();
        assertThat(ack.row().ackByName()).isEqualTo("Kişi B");
        assertThat(svc.ackHandover(c, prev, A).changed()).isFalse();
        assertThat(store.handover(TEAM, "2026-11-16").ackByName()).isEqualTo("Kişi B");

        assertThat(svc.saveHandoverNote(c, cur, "Pazartesi bakımı var", 0, A).changed()).isTrue();
        assertThatThrownBy(() -> svc.saveHandoverNote(c, cur, "Başka not", 0, B))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("NOTE_CHANGED"));
        assertThat(rig.countEvents("HANDOVER_ACK")).isEqualTo(1);
    }

    static final Perms WORKER = new Perms(true, true, false, true, 10L, (a, b) -> false);

    @SuppressWarnings("unchecked")
    static Map<String, Object> handoverCurrentOf(Map<String, Object> sheet) {
        return (Map<String, Object>) sheet.get("handover_current");
    }

    @Test
    @DisplayName("gün sayfası handover_current.writable = yazma yolunun kuralı: geçerli / önceki dönem + işlem yetkisi + kurulu rutin")
    void handoverCurrentWritableFollowsWriteRule() {
        TeamCtx bare = rig.ctx();   // rutin kurulmamış
        assertThat(svc.handoverNoteWritable(bare, WORKER, "2026-11-23")).isFalse();
        TeamCtx c = configured();
        // sayfa tarihi → dönem anahtarı → yazılabilir mi (bugün 26 Kasım: geçerli 23 Kasım, önceki 16 Kasım)
        Map<LocalDate, Boolean> expect = new java.util.LinkedHashMap<>();
        expect.put(THU, true);                                // geçerli dönem
        expect.put(LocalDate.parse("2026-11-18"), true);      // önceki dönem
        expect.put(LocalDate.parse("2026-11-11"), false);     // iki dönem önce
        expect.put(LocalDate.parse("2026-12-01"), false);     // gelecek dönem
        for (Map.Entry<LocalDate, Boolean> e : expect.entrySet()) {
            Map<String, Object> hc = handoverCurrentOf(svc.day(c, WORKER, e.getKey()));
            String key = (String) hc.get("period_key");
            assertThat(hc.get("writable")).as(e.getKey() + " → " + key).isEqualTo(e.getValue());
            // aynı kural: yazma yolu (periodForKey) yalnız yazılabilir anahtarı kabul eder
            boolean accepted;
            try {
                svc.periodForKey(c, key, true);
                accepted = true;
            } catch (FieldValidationException ex) {
                accepted = false;
            }
            assertThat(accepted).as("yazma yolu " + key).isEqualTo(e.getValue());
        }
        // yetkisiz (yalnız görüntüleme) → yazılamaz; hafta görünümü de aynı alanı taşır
        assertThat(handoverCurrentOf(svc.day(c, Perms.readOnly(10L), THU)).get("writable")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> weekHc = (Map<String, Object>) svc.week(c, WORKER, LocalDate.parse("2026-11-23")).get("handover_current");
        assertThat(weekHc.get("writable")).isEqualTo(true);
    }

    @Test
    @DisplayName("gün sayfası settings.completion_notice = /template yanıtındaki tamamlama bildirimi nesnesinin aynısı")
    void daySheetCarriesCompletionNotice() {
        configured();
        svc.saveSettings(TEAM, 0, Map.of("completion_notice", Map.of("push", false)), A);
        TeamCtx c = rig.ctx();
        @SuppressWarnings("unchecked")
        Map<String, Object> st = (Map<String, Object>) svc.day(c, WORKER, THU).get("settings");
        Object expected = OnCallRoutineSettings.toWire(c.settings()).get("completion_notice");
        assertThat(st.get("completion_notice")).isEqualTo(expected);
        assertThat(st.get("completion_notice")).isEqualTo(Map.of("enabled", true, "mail", true, "push", false));
        assertThat(st).containsKeys("day_start", "reminders");   // eski alanlar yerinde
    }

    // ── Yaşam döngüsü ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("takım silme: geri alınan işlemde hiçbir şey silinmez; onaylanan işlemden SONRA tüm rutin satırları silinir")
    void deleteForTeamAfterCommit() {
        TeamCtx c = configured();
        svc.applyState(c, THU, "d1", "DAY", "2026-11-26", "DONE", null, null, A, true);
        svc.createProblem(c, THU, "d1", "DAY", "2026-11-26", "LOW", "Not var", false, A);
        rig.person(21L, "N21", "Zeynep Kaya", true, true);
        svc.assign(c, "DAY", "2026-11-26", THU, 21L, "Zeynep Kaya", null, A);
        svc.saveHandoverNote(c, svc.periodForKey(c, "2026-11-23", true), "Devir notu", 0, A);
        assertThat(svc.impact(TEAM)).containsEntry("configured", true).containsEntry("days", 1).containsEntry("open_problems", 1);

        TransactionTemplate tt = new TransactionTemplate(txm);
        tt.executeWithoutResult(st -> {
            svc.deleteForTeam(TEAM);
            st.setRollbackOnly();
        });
        for (String t : OnCallRoutineTestRig.TABLES) {
            if (!t.equals("oncall_routine_problem_images") && !t.equals("oncall_routine_notices")) {
                assertThat(rig.count(t)).as(t).isPositive();
            }
        }
        tt.executeWithoutResult(st -> svc.deleteForTeam(TEAM));
        for (String t : OnCallRoutineTestRig.TABLES) assertThat(rig.count(t)).as(t).isZero();
    }

    @Test
    @DisplayName("yönetici özeti: son 30 gün takım × durum sayıları (bugün hariç)")
    void last30() {
        TeamCtx c = configured();
        svc.applyState(c, WED, "d1", "DAY", "2026-11-25", "DONE", null, null, A, true);
        svc.applyState(c, WED, "d2", "DAY", "2026-11-25", "DONE", null, null, A, true);
        svc.completeDay(c, WED, store.day(TEAM, "2026-11-25").version(), null, null, A);
        svc.ensureDayRow(c, THU, svc.now());
        Map<Long, Map<String, Integer>> m = svc.last30(THU);
        assertThat(m.get(TEAM)).containsEntry("COMPLETED", 1).doesNotContainKey("OPEN");
    }

    @Test
    @DisplayName("takım silme etkisi: sözleşmedeki dört anahtar (configured, tasks_active, days, open_problems); rutinsiz takım boş")
    void impactKeys() {
        Map<String, Object> none = svc.impact(TEAM);
        assertThat(none.keySet()).containsExactly("configured", "tasks_active", "days", "open_problems");
        assertThat(none).containsEntry("configured", false).containsEntry("tasks_active", 0).containsEntry("days", 0)
                .containsEntry("open_problems", 0);
        TeamCtx c = configured();
        svc.ensureDayRow(c, THU, svc.now());
        svc.createProblem(c, THU, null, null, null, "LOW", "Genel", false, A);
        Map<String, Object> m = svc.impact(TEAM);
        assertThat(m.keySet()).containsExactly("configured", "tasks_active", "days", "open_problems");
        assertThat(m).containsEntry("configured", true).containsEntry("tasks_active", 4).containsEntry("days", 1)
                .containsEntry("open_problems", 1);
    }

    @Test
    @DisplayName("genel bakış bağlamı: Türkçe ada göre sıralar, şablon okumadan önce keser; bilinmeyen takım atlanır, toplam doğru")
    void ctxsByNameSortsAndCutsFirst() {
        rig.team(8L, "Ağ Yönetimi", true);
        rig.team(9L, "Çağrı Merkezi", true);
        rig.team(12L, "Zeta", true);
        rig.team(13L, "Bulut", true);
        OnCallRoutineService.CtxPage two = svc.ctxsByName(List.of(12L, TEAM, 9L, 999L, 8L, 13L), 2);
        assertThat(two.total()).isEqualTo(5);
        assertThat(two.truncated()).isTrue();
        assertThat(two.ctxs()).extracting(TeamCtx::teamName).containsExactly("Ağ Yönetimi", "Bulut");
        OnCallRoutineService.CtxPage all = svc.ctxsByName(List.of(12L, TEAM, 9L, 999L, 8L, 13L), 10);
        assertThat(all.truncated()).isFalse();
        assertThat(all.ctxs()).extracting(TeamCtx::teamName)
                .containsExactly("Ağ Yönetimi", "Bulut", "Çağrı Merkezi", "Ödeme Sistemleri", "Zeta");
        assertThat(svc.ctxsByName(List.of(), 5).ctxs()).isEmpty();
    }
}

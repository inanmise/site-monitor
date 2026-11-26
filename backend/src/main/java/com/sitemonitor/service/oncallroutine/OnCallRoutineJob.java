package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.oncallroutine.OnCallRoutineCalendar.Period;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.TeamCtx;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.DayRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.TeamRow;
import com.sitemonitor.service.schedule.SideJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nöbet rutini işi (2026-11-26, §5.1): yarıda kalan bildirimler, tamamlama bildirimleri, hatırlatmalar, gün / hafta / ay
 * kapanışı ve devir özeti. Ucuz kapı (≤ 3 {@code exists}, kilitsiz) → gövde pod'un YAN İŞ koşucusunda
 * ({@code SideJob.fixedDelay}) → küme kilidi {@code oncall-routine}. Kapanışta yeni adım başlamaz ({@code closing} ya da
 * {@code sideJobsStopping}). Her takım kendi denemesinde; tur asla fırlatmaz. Sınırlar: ≤ 500 takım, ≤ 50 gün kapanışı / tur.
 */
@Slf4j
@Component
public class OnCallRoutineJob {

    static final int MAX_TEAMS = 500;
    static final int MAX_DAY_CLOSES = 50;
    static final int MAX_PERIOD_CLOSES = 100;
    static final int WEEKS_BACK = 5;
    static final int MONTHS_BACK = 2;
    /** Gün kapanışı ertesi gün bu saatten sonra (İstanbul). */
    static final LocalTime CLOSE_AFTER = LocalTime.of(0, 30);

    private final OnCallRoutineStore store;
    private final OnCallRoutineService svc;
    private final OnCallRoutineNotifier notifier;

    @Autowired(required = false) SchedulerService scheduler;

    @Value("${site.monitor.oncall-routine.tick-ms:60000}")
    long tickMs = 60_000;

    Clock clock = Clock.systemUTC();

    private volatile boolean closing;
    /** Bu pod'da kapatılmış hafta / ay dönemleri (yeniden başlatmada bir kez daha koşar — işlem eşgüçlü). */
    private final Set<String> closedPeriods = ConcurrentHashMap.newKeySet();

    public OnCallRoutineJob(OnCallRoutineStore store, OnCallRoutineService svc, OnCallRoutineNotifier notifier) {
        this.store = store;
        this.svc = svc;
        this.notifier = notifier;
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        closing = true;
    }

    boolean stopping() {
        if (closing) return true;
        try {
            return scheduler != null && scheduler.sideJobsStopping();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${site.monitor.oncall-routine.tick-ms:60000}",
               initialDelayString = "${site.monitor.oncall-routine.initial-delay-ms:90000}")
    public void scheduledTick() {
        if (closing) return;
        if (!anyWork(clock.instant())) return;
        SchedulerService s = scheduler;
        if (s == null) {
            tickAndLog();
            return;
        }
        Runnable body = () -> {
            if (!closing) s.runWithSchedulerLock(OnCallRoutineCodes.LOCK_NAME, this::tickAndLog);
        };
        if (!s.offloadSideJob(SideJob.fixedDelay(OnCallRoutineCodes.LOCK_NAME, tickMs, body))) body.run();
    }

    /** Ucuz kapı: etkin takım VAR ya da yarıda kalmış sahiplenme ya da bildirilmemiş tamamlama. */
    boolean anyWork(Instant now) {
        try {
            if (store.anyEnabled()) return true;
            if (store.existsStaleSending(OnCallRoutineStore.ts(now.minus(OnCallRoutineCodes.STALE_SENDING)))) return true;
            return store.existsPendingNotice(OnCallRoutineStore.ts(now.minus(Duration.ofHours(OnCallRoutineCodes.NOTICE_WINDOW_HOURS))));
        } catch (RuntimeException e) {
            log.debug("Nöbet rutini ön kapısı okunamadı (tablo henüz yok?): {}", e.toString());
            return false;
        }
    }

    /**
     * Tamamlama sonrası bildirimi hemen dener — kilitsiz yan iş (her bildirim sahiplenmeyle korunur; ada göre birleştirilir).
     * Koşucu yoksa / üstlenmezse sonraki tur gönderir (≤ 60 sn); isteği asla bekletmez.
     */
    public void kick() {
        if (closing) return;
        SchedulerService s = scheduler;
        if (s == null) return;
        try {
            s.offloadSideJob(SideJob.heavy(OnCallRoutineCodes.NOTIFY_JOB_NAME, () -> {
                if (!closing) notifier.sendPendingCompletions(clock.instant(), this::stopping);
            }));
        } catch (RuntimeException e) {
            log.debug("Nöbet rutini bildirim tetiği kuyruğa alınamadı (sonraki tur gönderir): {}", e.toString());
        }
    }

    public record TickResult(int stale, int completions, int reminders, int closedDays, int closedPeriods, int handovers) { }

    private void tickAndLog() {
        TickResult r = tick(clock.instant());
        if (r.stale() + r.completions() + r.reminders() + r.closedDays() + r.handovers() > 0) {
            log.info("Nöbet rutini turu: {} yarıda kalmış, {} tamamlama bildirimi, {} hatırlatma, {} gün kapanışı, {} dönem, {} devir",
                    r.stale(), r.completions(), r.reminders(), r.closedDays(), r.closedPeriods(), r.handovers());
        }
    }

    /** Tek tur (kilit çağıranda). Asla fırlatmaz. */
    public TickResult tick(Instant now) {
        int stale = 0, completions = 0, reminders = 0, closedDays = 0, periods = 0, handovers = 0;
        try {
            stale = notifier.sweepStale(now);
            if (stopping()) return new TickResult(stale, 0, 0, 0, 0, 0);
            completions = notifier.sendPendingCompletions(now, this::stopping);
            if (stopping()) return new TickResult(stale, completions, 0, 0, 0, 0);
            List<TeamRow> enabled = store.enabledTeams(MAX_TEAMS);
            if (enabled.isEmpty()) return new TickResult(stale, completions, 0, 0, 0, 0);
            List<TeamCtx> ctxs = svc.ctxs(enabled.stream().map(TeamRow::teamId).toList());
            // 3) Hatırlatmalar
            for (TeamCtx c : ctxs) {
                if (stopping()) break;
                try {
                    reminders += notifier.reminders(c, now);
                } catch (RuntimeException e) {
                    log.warn("Nöbet rutini hatırlatması değerlendirilemedi (takım {}): {}", c.teamId(), e.toString());
                }
            }
            // 4) Gün kapanışı
            int[] budget = {MAX_DAY_CLOSES};
            for (TeamCtx c : ctxs) {
                if (stopping() || budget[0] <= 0) break;
                try {
                    closedDays += closeDays(c, now, budget);
                } catch (RuntimeException e) {
                    log.warn("Nöbet rutini gün kapanışı yapılamadı (takım {}): {}", c.teamId(), e.toString());
                }
            }
            // 5) Hafta / ay kapanışı (açılışta 500 takım × 7 dönem birden koşmasın — tur başına sınırlı)
            int[] periodBudget = {MAX_PERIOD_CLOSES};
            for (TeamCtx c : ctxs) {
                if (stopping() || periodBudget[0] <= 0) break;
                try {
                    periods += closePeriods(c, now, periodBudget);
                } catch (RuntimeException e) {
                    log.warn("Nöbet rutini dönem kapanışı yapılamadı (takım {}): {}", c.teamId(), e.toString());
                }
            }
            // 6) Devir özeti + bildirimi
            for (TeamCtx c : ctxs) {
                if (stopping()) break;
                try {
                    if (handover(c, now, budget)) handovers++;
                } catch (RuntimeException e) {
                    log.warn("Nöbet devri değerlendirilemedi (takım {}): {}", c.teamId(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Nöbet rutini turu tamamlanamadı (sonraki tur yeniden dener): {}", e.toString());
        }
        return new TickResult(stale, completions, reminders, closedDays, periods, handovers);
    }

    /**
     * Gün kapanışı (§5.4): [bugün − 8, bugün − 1], etkinleşme gününden sonra, iş günü, duraklatılmamış, ertesi gün 00:30
     * sonrası. Bir gün fazladan geriye bakılır: kullanıcının yeniden açtığı geçmiş gün düzenleme penceresinden ÇIKTIĞI gün
     * kapanır ({@link #closeDue}).
     */
    int closeDays(TeamCtx c, Instant now, int[] budget) {
        if (!c.configured()) return 0;
        LocalDate today = OnCallRoutineCalendar.localDate(now);
        String since = c.row() == null ? null : c.row().enabledSince();
        LocalDate from = today.minusDays(OnCallRoutineCodes.EDIT_WINDOW_DAYS + 1L);
        Map<String, DayRow> rows = new HashMap<>();
        for (DayRow r : store.days(c.teamId(), from.toString(), today.minusDays(1).toString())) rows.put(r.day(), r);
        int n = 0;
        for (LocalDate d = from; d.isBefore(today); d = d.plusDays(1)) {
            if (budget[0] <= 0 || stopping()) break;
            DayRow row = rows.get(d.toString());
            if (!closeDue(c, d, since, now, row)) continue;
            if (row == null && nothingDue.contains(c.teamId() + "|" + d)) continue;   // zorunlu günlük görevi yok (bu pod biliyor)
            budget[0]--;
            if (closeOne(c, d)) n++;
        }
        return n;
    }

    /**
     * Bu pod'da "kapatılacak bir şey yok" denmiş günler ({@code takım|gün}): zorunlu günlük görevi olmayan şablonda (yalnız
     * haftalık / aylık) her iş günü her turda yeniden değerlendirilip kapanış bütçesini tüketmesin. Yeniden başlatmada bir kez
     * daha değerlendirilir (yazma yok, eşgüçlü).
     */
    private final Set<String> nothingDue = ConcurrentHashMap.newKeySet();

    private boolean closeOne(TeamCtx c, LocalDate d) {
        OnCallRoutineService.CloseResult r = svc.closeDayResult(c, d);
        if (r == OnCallRoutineService.CloseResult.NOTHING_DUE) {
            if (nothingDue.size() > 50_000) nothingDue.clear();   // sınırlı bellek
            nothingDue.add(c.teamId() + "|" + d);
        }
        return r == OnCallRoutineService.CloseResult.CLOSED;
    }

    /**
     * Gün kapatılmalı mı (saf kural). Kullanıcının YENİDEN AÇTIĞI geçmiş gün ({@code completion_seq > 0} ve durum OPEN)
     * düzenleme penceresi içindeyken kapatılmaz — yoksa iş bir dakika içinde onu INCOMPLETE yapar, açılan işaretlenmemiş
     * görevler MISSED olurdu; penceresinden çıktığı gün kapanır.
     */
    static boolean closeDue(TeamCtx c, LocalDate d, String enabledSince, Instant now, DayRow row) {
        if (enabledSince == null || d.toString().compareTo(enabledSince) < 0) return false;
        if (!c.workday(d) || c.paused(d)) return false;
        if (now.isBefore(OnCallRoutineCalendar.at(d.plusDays(1), CLOSE_AFTER))) return false;
        if (row != null && !OnCallRoutineCodes.DAY_OPEN.equals(row.status())) return false;
        return !reopenedAndEditable(row, d, OnCallRoutineCalendar.localDate(now));
    }

    /** Yeniden açılmış (bir kez tamamlanmış, şimdi OPEN) ve hâlâ düzenlenebilir geçmiş gün. */
    static boolean reopenedAndEditable(DayRow row, LocalDate d, LocalDate today) {
        return row != null && row.completionSeq() > 0 && OnCallRoutineCodes.DAY_OPEN.equals(row.status())
                && !d.isBefore(today.minusDays(OnCallRoutineCodes.EDIT_WINDOW_DAYS));
    }

    /**
     * Biten haftalar (≤ 5 geri, bittikten ≥ 1 gün sonra) ve aylar (≤ 2 geri). Bu pod'da bir kez. {@code budget} = bu turda
     * en çok kaç dönem işlemi (her biri bir işlem); bitince kalan dönemler sonraki turda (işaretlenmez).
     */
    int closePeriods(TeamCtx c, Instant now, int[] budget) {
        if (!c.configured()) return 0;
        LocalDate today = OnCallRoutineCalendar.localDate(now);
        String since = c.row() == null ? null : c.row().enabledSince();
        if (since == null) return 0;
        int n = 0;
        LocalDate thisMonday = OnCallRoutineCalendar.monday(today);
        // Kalıcı işaret (week_closed_through): başka pod / yeniden başlatma kapanmış dönemi bütçe harcamadan atlar — işaret
        // yalnız bellekte olmayan ilk aday için okunur (tik başına en çok bir sorgu).
        String weekThrough = null;
        boolean weekRead = false;
        for (int w = WEEKS_BACK; w >= 1; w--) {          // en eskiden yeniye (kalıcı işaret tekdüze ilerler)
            if (stopping() || budget[0] <= 0) return n;
            LocalDate mon = thisMonday.minusWeeks(w);
            LocalDate sun = mon.plusDays(6);
            if (!today.isAfter(sun.plusDays(1))) continue;          // bittikten ≥ 1 gün sonra
            if (sun.toString().compareTo(since) < 0) continue;       // rutin başlamadan biten dönem
            String key = c.teamId() + "|WEEK|" + mon;
            if (closedPeriods.contains(key)) continue;
            if (!weekRead) {
                weekRead = true;
                weekThrough = markerOrNull(c.teamId(), OnCallRoutineCodes.KIND_WEEK);
            }
            if (weekThrough != null && mon.toString().compareTo(weekThrough) <= 0) {
                closedPeriods.add(key);
                continue;
            }
            budget[0]--;
            n += svc.closePeriod(c, OnCallRoutineCodes.KIND_WEEK, mon.toString(), sun);
            closedPeriods.add(key);
        }
        YearMonth cur = YearMonth.from(today);
        String monthThrough = null;
        boolean monthRead = false;
        for (int m = MONTHS_BACK; m >= 1; m--) {
            if (stopping() || budget[0] <= 0) return n;
            YearMonth ym = cur.minusMonths(m);
            LocalDate last = ym.atEndOfMonth();
            if (!today.isAfter(last.plusDays(1))) continue;
            if (last.toString().compareTo(since) < 0) continue;
            String key = c.teamId() + "|MONTH|" + ym;
            if (closedPeriods.contains(key)) continue;
            if (!monthRead) {
                monthRead = true;
                monthThrough = markerOrNull(c.teamId(), OnCallRoutineCodes.KIND_MONTH);
            }
            if (monthThrough != null && ym.toString().compareTo(monthThrough) <= 0) {
                closedPeriods.add(key);
                continue;
            }
            budget[0]--;
            n += svc.closePeriod(c, OnCallRoutineCodes.KIND_MONTH, ym.toString(), last);
            closedPeriods.add(key);
        }
        if (closedPeriods.size() > 50_000) closedPeriods.clear();   // sınırlı bellek
        return n;
    }

    /** Kalıcı dönem işareti; okunamazsa null (kapanış servisi işaretini işlem içinde yine okur — çift kapanış olmaz). */
    private String markerOrNull(Long teamId, String kind) {
        try {
            return store.periodClosedThrough(teamId, kind);
        } catch (RuntimeException e) {
            log.debug("Nöbet rutini dönem işareti okunamadı (takım {} {}): {}", teamId, kind, e.toString());
            return null;
        }
    }

    int closePeriods(TeamCtx c, Instant now) {
        return closePeriods(c, now, new int[] {Integer.MAX_VALUE});
    }

    /**
     * Önceki devir dönemi son 24 saatte bittiyse: kapanma zamanı GELMİŞ günlerini kapat, özeti sakla, bildirimi gönder. Gün
     * YALNIZ {@link #closeDue} doğruysa kapanır (ertesi gün 00:30 sonrası): devir saati gün başlangıcından geç olan rotasyonda
     * (gün 09:00, devir Pazartesi 10:00) devir günü önceki döneme aittir ve devir anında hâlâ BUGÜN'dür — öğlen kapatılmaz,
     * görevleri MISSED olmaz, sonraki tamamlaması geç sayılmaz. Bitmemiş gün özete girmez ({@code OnCallRoutineService
     * .daySettled}); gün kapanışı onu ertesi gece kapatır.
     */
    boolean handover(TeamCtx c, Instant now, int[] budget) {
        if (!c.configured()) return false;
        Period current = OnCallRoutineCalendar.periodAt(c.rotation(), c.dayStart(), now);
        Period prev = OnCallRoutineCalendar.previous(c.rotation(), c.dayStart(), current);
        if (prev.end().isAfter(now) || !now.isBefore(prev.end().plus(Duration.ofHours(OnCallRoutineCodes.NOTICE_WINDOW_HOURS)))) {
            return false;
        }
        String since = c.row() == null ? null : c.row().enabledSince();
        if (since == null || prev.last().toString().compareTo(since) < 0) return false;
        // Dönemin kapanma zamanı gelmiş günleri önce kapanır (özet bitmiş günlerden kurulur)
        List<LocalDate> toClose = new ArrayList<>();
        Map<String, DayRow> rows = new HashMap<>();
        for (DayRow r : store.days(c.teamId(), prev.first().toString(), prev.last().toString())) rows.put(r.day(), r);
        for (LocalDate d : prev.days()) {
            DayRow r = rows.get(d.toString());
            if (!closeDue(c, d, since, now, r)) continue;
            if (r == null && nothingDue.contains(c.teamId() + "|" + d)) continue;
            toClose.add(d);
        }
        for (LocalDate d : toClose) {
            if (budget[0] <= 0 || stopping()) return false;   // sonraki tur devam eder
            budget[0]--;
            closeOne(c, d);
        }
        return notifier.handover(c, prev, now);
    }
}

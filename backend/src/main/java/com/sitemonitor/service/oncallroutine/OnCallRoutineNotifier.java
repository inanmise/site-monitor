package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushRecipientResolver;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.OnCallRoutineMail;
import com.sitemonitor.service.oncallroutine.OnCallRoutineCalendar.Period;
import com.sitemonitor.service.oncallroutine.OnCallRoutinePlan.Item;
import com.sitemonitor.service.oncallroutine.OnCallRoutinePlan.Row;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.Responsible;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.TeamCtx;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.DayRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.HandoverRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.NoticeRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.ProblemRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Nöbet rutini bildirimleri (2026-11-26, §5.2 – §5.5): tamamlama, hatırlatma push'ları ve devir. Her bildirim
 * GÖNDERİLMEDEN ÖNCE sahiplenilir ({@code oncall_routine_notices} benzersiz takım × tür × anahtar — iki pod aynı bildirimi
 * göndermez); SENDING kalan sahiplenme 15 dk sonra UNKNOWN olur ve yeniden gönderilmez. E-postalı her karar TEK
 * {@code notification_logs} satırı bırakır. Hatırlatmalar yalnız SORUMLUYA push'tur (e-posta / günlük satırı yok — push
 * teslimat satırları + sahiplenme satırı iz olur). Sistem bakımı susturması sahiplenmeden ÖNCE sorulur. Asla fırlatmaz.
 */
@Slf4j
@Component
public class OnCallRoutineNotifier {

    static final String SKIP_TEAM_OFF = "SKIPPED: takım ayarı kapalı";
    static final String SKIP_ADMIN_OFF = "SKIPPED: yönetici ayarı kapalı";
    static final String SKIP_MODULE_HIDDEN = "SKIPPED: modül bu takım için kapalı";
    static final String SKIP_NO_TEAM = "SKIPPED: takım yok";
    static final String SKIP_TEAM_INACTIVE = "SKIPPED: takım pasif";
    static final String SKIP_NO_ADDRESS = "SKIPPED: takımın e-posta adresi yok";
    static final String SKIP_MAINTENANCE = "SKIPPED: sistem bakımı";
    static final String FAILED_RECIPIENTS = "FAILED: alıcılar okunamadı";
    static final String FAILED_UNKNOWN = "FAILED: sonuç bilinmiyor — gönderen pod durdu; yeniden gönderilmedi";
    static final String MAIL_OFF = "SKIPPED: e-posta kanalı kapalı (takım ayarı)";
    static final String PUSH_DELIVERED = "delivered";
    static final String PUSH_SKIPPED = "skipped";
    static final String PUSH_FAILED = "failed";
    static final String PUSH_OFF = "off";
    static final int LOG_FIELD_MAX = 255;
    static final int MAX_CANDIDATES = 200;
    /** Bakım susarken pencerenin kapandığı sayılan pay (son tur). */
    static final Duration WINDOW_END_SLACK = Duration.ofMinutes(5);

    private final OnCallRoutineStore store;
    private final OnCallRoutineService svc;
    private final TeamRepository teams;
    private final AppUserRepository users;
    private final NotificationLogRepository notificationLogs;

    @Autowired(required = false) EscalationService escalation;
    @Autowired(required = false) EmailNotificationService email;
    @Autowired(required = false) UserPushService push;
    @Autowired(required = false) SystemMaintenanceService systemMaintenance;
    @Autowired(required = false) AppSettingsService appSettings;
    @Autowired(required = false) OnCallRoutineVisibility visibility;

    Clock clock = Clock.systemUTC();

    public OnCallRoutineNotifier(OnCallRoutineStore store, OnCallRoutineService svc, TeamRepository teams, AppUserRepository users,
                                 NotificationLogRepository notificationLogs) {
        this.store = store;
        this.svc = svc;
        this.teams = teams;
        this.users = users;
        this.notificationLogs = notificationLogs;
    }

    enum Outcome { DELIVERED, SKIPPED, FAILED, OFF }

    /** Açık her kanal ulaştı → SENT; biri ulaştı → PARTIAL; hiçbiri ulaşmadı + hata → FAILED; yoksa SKIPPED. */
    static String finalStatus(Outcome mail, Outcome push) {
        boolean delivered = mail == Outcome.DELIVERED || push == Outcome.DELIVERED;
        boolean failed = mail == Outcome.FAILED || push == Outcome.FAILED;
        boolean missing = (mail != Outcome.OFF && mail != Outcome.DELIVERED) || (push != Outcome.OFF && push != Outcome.DELIVERED);
        if (delivered) return missing ? OnCallRoutineCodes.N_PARTIAL : OnCallRoutineCodes.N_SENT;
        return failed ? OnCallRoutineCodes.N_FAILED : OnCallRoutineCodes.N_SKIPPED;
    }

    static String pushClass(Outcome o) {
        return switch (o) {
            case DELIVERED -> PUSH_DELIVERED;
            case FAILED -> PUSH_FAILED;
            case OFF -> PUSH_OFF;
            default -> PUSH_SKIPPED;
        };
    }

    static Outcome pushOutcomeOf(String s) {
        if (PUSH_DELIVERED.equals(s)) return Outcome.DELIVERED;
        if (PUSH_OFF.equals(s)) return Outcome.OFF;
        if (PUSH_FAILED.equals(s)) return Outcome.FAILED;
        return Outcome.SKIPPED;
    }

    static Outcome mailOutcomeOf(String mailStatus) {
        if (mailStatus == null) return Outcome.SKIPPED;
        if (mailStatus.startsWith(MAIL_OFF)) return Outcome.OFF;
        if (mailStatus.startsWith("SENT") || mailStatus.startsWith("QUEUED")) return Outcome.DELIVERED;
        if (mailStatus.startsWith("SKIPPED")) return Outcome.SKIPPED;
        return Outcome.FAILED;
    }

    Instant now() {
        return clock.instant();
    }

    boolean muted() {
        try {
            return systemMaintenance != null && systemMaintenance.notificationsMuted();
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean adminOn(String key) {
        return appSettings == null || OnCallRoutineVisibility.switchOn(appSettings, key);
    }

    boolean visible(Long teamId) {
        try {
            return visibility == null || visibility.teamVisible(teamId);
        } catch (RuntimeException e) {
            return true;
        }
    }

    String baseUrl() {
        try {
            return appSettings == null ? "" : appSettings.getString("site.monitor.app.base-url", "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    static Instant parseStamp(String s) {
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String clip(String v, int max) {
        if (v == null || v.length() <= max) return v;
        return v.substring(0, Math.max(0, max - 1)) + "…";
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Yarıda kalan sahiplenmeler
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** SENDING'de 15 dk'dan uzun kalan satırlar → UNKNOWN (yeniden gönderilmez); e-postalı türlerde TEK iz. */
    public int sweepStale(Instant now) {
        int n = 0;
        try {
            String cutoff = OnCallRoutineStore.ts(now.minus(OnCallRoutineCodes.STALE_SENDING));
            for (NoticeRow r : store.staleSending(cutoff, MAX_CANDIDATES)) {
                if (store.markUnknown(r.id(), r.claimedAt(), OnCallRoutineStore.ts(now)) != 1) continue;
                n++;
                log.warn("Nöbet rutini bildirimi yarıda kalmış (takım {} tür {} anahtar {} damga {}) — yeniden gönderilmedi",
                        r.teamId(), r.kind(), r.refKey(), r.claimedAt());
                String subject = subjectOf(r);
                if (subject == null) continue;
                String trigger = OnCallRoutineCodes.NOTICE_HANDOVER.equals(r.kind()) ? OnCallRoutineCodes.TRIGGER_HANDOVER
                        : OnCallRoutineCodes.TRIGGER_DONE;
                boolean traced = false;
                for (String s : subjectVariants(r, subject)) {
                    if (tracedSince(trigger, s, r.claimedAt())) {
                        traced = true;
                        break;
                    }
                }
                if (!traced) saveLog(trigger, teamName(r.teamId()), List.of(), subject, FAILED_UNKNOWN, null);
            }
        } catch (RuntimeException e) {
            log.debug("Nöbet rutini: yarıda kalan bildirim satırları okunamadı: {}", e.toString());
        }
        return n;
    }

    /** E-postalı bildirimin konusu (iz için); e-postasız tür ya da okunamazsa null. */
    String subjectOf(NoticeRow r) {
        try {
            if (OnCallRoutineCodes.NOTICE_COMPLETE.equals(r.kind())) {
                String[] p = r.refKey().split("#", 2);
                DayRow d = store.day(r.teamId(), p[0]);
                int seq = p.length > 1 ? Integer.parseInt(p[1]) : 1;
                LocalDate date = LocalDate.parse(p[0]);
                return OnCallRoutineMail.completionSubject(completionStub(teamName(r.teamId()), date, d, seq), false);
            }
            if (OnCallRoutineCodes.NOTICE_HANDOVER.equals(r.kind())) {
                HandoverRow h = store.handover(r.teamId(), r.refKey());
                if (h == null) return null;
                TeamCtx c = svc.ctx(r.teamId());
                Instant start = parseStamp(h.periodStart());
                if (start == null) return null;
                Period p = OnCallRoutineCalendar.periodAt(c.rotation(), c.dayStart(), start);
                return OnCallRoutineMail.handoverSubject(c.teamName(), p.first(), p.last(), false);
            }
        } catch (RuntimeException e) {
            log.debug("Nöbet rutini: bildirim konusu çözülemedi ({}): {}", r.refKey(), e.toString());
        }
        return null;
    }

    private OnCallRoutineMail.Completion completionStub(String teamName, LocalDate date, DayRow d, int seq) {
        return completionStub(teamName, date, seq, d != null && d.completedLate(),
                d != null && OnCallRoutineCodes.DAY_COMPLETED_PARTIAL.equals(d.status()));
    }

    private static OnCallRoutineMail.Completion completionStub(String teamName, LocalDate date, int seq, boolean late, boolean partial) {
        return new OnCallRoutineMail.Completion(teamName, null, date, null, null, null, 0, 0, 0, 0, 0, seq, late, partial,
                null, null, null, null, null, null, null);
    }

    /**
     * İz araması için konunun olası biçimleri. Tamamlama konusundaki "geç" / "eksiklerle" işaretleri GÜNCEL gün satırından
     * okunur; gönderimden sonra gün yeniden açılıp başka biçimde tamamlandıysa gönderilen konu farklıdır — dört biçim de
     * aranır (yarıda kalan gönderimin izi varken ikinci "sonuç bilinmiyor" satırı yazılmasın). En çok dört sorgu, nadir yol.
     */
    List<String> subjectVariants(NoticeRow r, String current) {
        List<String> out = new ArrayList<>();
        out.add(current);
        if (!OnCallRoutineCodes.NOTICE_COMPLETE.equals(r.kind())) return out;
        try {
            String[] p = r.refKey().split("#", 2);
            int seq = p.length > 1 ? Integer.parseInt(p[1]) : 1;
            LocalDate date = LocalDate.parse(p[0]);
            String team = teamName(r.teamId());
            for (boolean late : new boolean[] {false, true}) {
                for (boolean partial : new boolean[] {false, true}) {
                    String s = OnCallRoutineMail.completionSubject(completionStub(team, date, seq, late, partial), false);
                    if (!out.contains(s)) out.add(s);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Nöbet rutini: konu biçimleri çözülemedi ({}): {}", r.refKey(), e.toString());
        }
        return out;
    }

    private boolean tracedSince(String trigger, String subject, String claimedAt) {
        if (claimedAt == null) return false;
        try {
            return notificationLogs.existsByTriggerAndSubjectAndSentAtGreaterThanEqual(trigger, subject, claimedAt);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Tamamlama bildirimi (§5.2)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Bildirilmemiş tamamlamaları gönderir (iş turu ve tamamlama sonrası tetik). Dönen: işlenen aday sayısı. */
    public int sendPendingCompletions(Instant now, BooleanSupplier stop) {
        int n = 0;
        try {
            settleQueuedMails(now);
            String since = OnCallRoutineStore.ts(now.minus(Duration.ofHours(OnCallRoutineCodes.NOTICE_WINDOW_HOURS)));
            for (DayRow d : store.pendingCompletions(since, MAX_CANDIDATES)) {
                if (stop.getAsBoolean()) break;
                try {
                    handleCompletion(d, now);
                    n++;
                } catch (RuntimeException e) {
                    log.warn("Nöbet rutini tamamlama bildirimi değerlendirilemedi (takım {} gün {}): {}", d.teamId(), d.day(), e.toString());
                }
            }
            retryFailed(now, stop);
        } catch (RuntimeException e) {
            log.warn("Nöbet rutini tamamlama bildirimleri okunamadı (sonraki tur yeniden dener): {}", e.toString());
        }
        return n;
    }

    void handleCompletion(DayRow d, Instant now) {
        int seq = d.completionSeq();
        String ref = d.day() + "#" + seq;
        Long teamId = d.teamId();
        NoticeRow existing = store.notice(teamId, OnCallRoutineCodes.NOTICE_COMPLETE, ref);
        if (existing != null) {
            // Karar zaten verilmiş (önceki tur notified_seq'i yazamadan durdu) — sonuçlanmışsa yalnız işaret
            if (!OnCallRoutineCodes.N_SENDING.equals(existing.status())) store.markNotified(d.id(), seq);
            return;
        }
        String at = OnCallRoutineStore.ts(now);
        Team team = teams.findById(teamId).orElse(null);
        String teamName = team == null || team.getName() == null || team.getName().isBlank() ? "Takım #" + teamId : team.getName();
        LocalDate date = LocalDate.parse(d.day());
        String subject = OnCallRoutineMail.completionSubject(completionStub(teamName, date, d, seq), false);
        if (team == null) {
            finalDecision(d, ref, OnCallRoutineCodes.NOTICE_COMPLETE, "NO_TEAM", teamName, subject, SKIP_NO_TEAM,
                    OnCallRoutineCodes.TRIGGER_DONE, at);
            return;
        }
        if (Boolean.FALSE.equals(team.getActive())) {
            finalDecision(d, ref, OnCallRoutineCodes.NOTICE_COMPLETE, "TEAM_INACTIVE", teamName, subject, SKIP_TEAM_INACTIVE,
                    OnCallRoutineCodes.TRIGGER_DONE, at);
            return;
        }
        if (!visible(teamId)) {
            finalDecision(d, ref, OnCallRoutineCodes.NOTICE_COMPLETE, "MODULE_HIDDEN", teamName, subject, SKIP_MODULE_HIDDEN,
                    OnCallRoutineCodes.TRIGGER_DONE, at);
            return;
        }
        TeamCtx c = svc.ctx(teamId);
        OnCallRoutineSettings.Notice cfg = c.settings().completionNotice();
        if (!adminOn(OnCallRoutineVisibility.KEY_COMPLETION_NOTICE)) {
            finalDecision(d, ref, OnCallRoutineCodes.NOTICE_COMPLETE, "DISABLED", teamName, subject, SKIP_ADMIN_OFF,
                    OnCallRoutineCodes.TRIGGER_DONE, at);
            return;
        }
        if (!cfg.enabled() || (!cfg.mail() && !cfg.push())) {
            finalDecision(d, ref, OnCallRoutineCodes.NOTICE_COMPLETE, "DISABLED", teamName, subject, SKIP_TEAM_OFF,
                    OnCallRoutineCodes.TRIGGER_DONE, at);
            return;
        }
        if (muted()) {
            Instant completed = parseStamp(d.completedAt());
            Instant windowEnd = completed == null ? now : completed.plus(Duration.ofHours(OnCallRoutineCodes.NOTICE_WINDOW_HOURS));
            if (!now.isBefore(windowEnd.minus(WINDOW_END_SLACK))) {
                finalDecision(d, ref, OnCallRoutineCodes.NOTICE_COMPLETE, "MAINTENANCE", teamName, subject, SKIP_MAINTENANCE,
                        OnCallRoutineCodes.TRIGGER_DONE, at);
            }
            return;   // pencere içinde bakım bitince yeniden denenir
        }
        Long id = store.claim(teamId, OnCallRoutineCodes.NOTICE_COMPLETE, ref, OnCallRoutineCodes.N_SENDING, null, null, at,
                SchedulerService.instanceId());
        if (id == null) return;   // başka pod sahiplendi
        sendCompletion(c, team, d, id, at, false);
        store.markNotified(d.id(), seq);
    }

    /** Karar satırı (doğrudan son durum) + günlük izi + gün işareti. Ekleyen pod iz yazar. */
    private void finalDecision(DayRow d, String ref, String kind, String reason, String teamName, String subject, String trace,
                               String trigger, String at) {
        Long id = store.claim(d.teamId(), kind, ref, OnCallRoutineCodes.N_SKIPPED, reason, null, at, SchedulerService.instanceId());
        if (id != null) saveLog(trigger, teamName, List.of(), subject, trace, null);
        store.markNotified(d.id(), d.completionSeq());
    }

    /** Sahiplenilmiş tamamlama bildirimini gönderir ve satırı kapatır. {@code mailOnly} = önceki denemede push ulaştı. */
    String sendCompletion(TeamCtx c, Team team, DayRow d, Long noticeId, String claimedAt, boolean mailOnly) {
        String teamName = c.teamName();
        String subject = OnCallRoutineMail.completionSubject(completionStub(teamName, LocalDate.parse(d.day()), d, d.completionSeq()), false);
        Sent mail = null;
        Outcome pushOut = null;
        Long traceId = null;
        try {
            final OnCallRoutineMail.Completion info = completionInfo(c, d);
            subject = OnCallRoutineMail.completionSubject(info, false);
            OnCallRoutineSettings.Notice cfg = c.settings().completionNotice();
            mail = sendMail(cfg.mail(), c.teamId(), teamName, subject, () -> OnCallRoutineMail.completion(info, false));
            int queued = 0;
            String pushNote;
            if (mailOnly) {
                pushOut = Outcome.DELIVERED;
                pushNote = " · push: önceki denemede (yeniden gönderilmedi)";
            } else if (!cfg.push()) {
                pushOut = Outcome.OFF;
                pushNote = " · push: kapalı (takım ayarı)";
            } else if (push == null) {
                pushOut = Outcome.SKIPPED;
                pushNote = " · push: yok";
            } else {
                try {
                    int limit = push.messageCharLimit();
                    Set<String> exclude = new HashSet<>();
                    String completer = usernameOf(d.completedById());
                    if (completer != null) exclude.add(completer);
                    Map<String, Object> pr = push.enqueueTeamNoticeLocalized(c.teamId(), OnCallRoutineCodes.TRIGGER_DONE, "WARNING",
                            OnCallRoutineCodes.PUSH_TYPE, teamName, new UserPushService.LocalizedText(
                                    OnCallRoutineMail.pushDone(teamName, info.done(), info.required(), info.skipped(), info.problemsOpen(), false, limit),
                                    OnCallRoutineMail.pushDone(teamName, info.done(), info.required(), info.skipped(), info.problemsOpen(), true, limit)),
                            "or-done:" + c.teamId() + ":" + d.day() + ":" + d.completionSeq(), exclude);
                    queued = pr.get("queued") instanceof Number q ? q.intValue() : 0;
                    pushOut = queued > 0 ? Outcome.DELIVERED : Outcome.SKIPPED;
                    pushNote = queued > 0 ? " · push " + queued
                            : " · push: " + (pr.get("reason") == null ? "alıcı yok" : String.valueOf(pr.get("reason")));
                } catch (RuntimeException e) {
                    pushOut = Outcome.FAILED;
                    pushNote = " · push: FAILED (" + e.getClass().getSimpleName() + ")";
                    log.warn("Nöbet rutini tamamlama push'u kuyruğa alınamadı (takım {}): {}", teamName, e.toString());
                }
            }
            String status = finalStatus(mail.outcome(), pushOut);
            String reason = OnCallRoutineCodes.N_SKIPPED.equals(status) ? "NO_RECIPIENT" : null;
            traceId = saveLog(OnCallRoutineCodes.TRIGGER_DONE, teamName, mail.to(), subject, mail.status() + pushNote, mail.html());
            store.finishNotice(noticeId, claimedAt, status, reason, clip(mail.status(), LOG_FIELD_MAX), pushClass(pushOut), queued, null,
                    OnCallRoutineStore.ts(now()), traceId);
            return status;
        } catch (RuntimeException e) {
            return failAfterClaim(OnCallRoutineCodes.TRIGGER_DONE, teamName, subject, noticeId, claimedAt, mail, pushOut, mailOnly,
                    traceId != null,
                    "FAILED: nöbet rutini bildirimi hazırlanamadı (", "tamamlama gün " + d.day(), e);
        }
    }

    /**
     * Sahiplenilmiş bildirimin hata yolu (inceleme #12). Hiçbir şey e-posta servisine / push kuyruğuna verilmediyse TEK
     * {@code FAILED} izi + satır FAILED (yeniden deneme kuralları). Bir kanal ULAŞTIYSA (sonra örn. satır sonucu yazılamadı)
     * ikinci, çelişen bir FAILED izi yazılmaz ve satır FAILED yapılmaz (yeniden deneme ikinci e-posta gönderirdi): satır
     * SENDING kalır, yarıda kalan satır kuralı 15 dk sonra UNKNOWN yapar (yeniden gönderilmez; e-posta izi varsa ek iz yok).
     * Bu denemenin izi ({@code traced}) zaten yazıldıysa ikinci iz yazılmaz — her karar TEK satır.
     */
    String failAfterClaim(String trigger, String teamName, String subject, Long noticeId, String claimedAt, Sent mail,
                          Outcome pushOut, boolean mailOnly, boolean traced, String tracePrefix, String what,
                          RuntimeException e) {
        boolean handed = (mail != null && mail.outcome() == Outcome.DELIVERED)
                || (!mailOnly && pushOut == Outcome.DELIVERED);
        if (handed) {
            log.warn("Nöbet rutini bildirimi gönderildi ama sonucu yazılamadı (takım {} · {}): {} — satır yarıda kalan kuralıyla "
                    + "UNKNOWN olur, yeniden gönderilmez", teamName, what, e.toString());
            return OnCallRoutineCodes.N_UNKNOWN;
        }
        log.warn("Nöbet rutini bildirimi hazırlanamadı (takım {} · {}): {}", teamName, what, e.toString());
        if (!traced) saveLog(trigger, teamName, List.of(), subject, tracePrefix + e.getClass().getSimpleName() + ")", null);
        try {
            store.finishNotice(noticeId, claimedAt, OnCallRoutineCodes.N_FAILED, "ERROR", null, null, 0, e.getClass().getSimpleName(),
                    OnCallRoutineStore.ts(now()), null);
        } catch (RuntimeException fin) {
            log.warn("Nöbet rutini bildirim satırı FAILED yazılamadı (takım {} · {}) — yarıda kalan kuralı kapatır: {}", teamName,
                    what, fin.toString());
        }
        return OnCallRoutineCodes.N_FAILED;
    }

    /** Tamamlama e-postasının içeriği (gün satırı + donmuş sayfa + sorunlar). */
    OnCallRoutineMail.Completion completionInfo(TeamCtx c, DayRow d) {
        LocalDate date = LocalDate.parse(d.day());
        List<Row> rows = store.items(c.teamId(), OnCallRoutinePlan.periodsSeenFrom(date));
        OnCallRoutinePlan.Sheet sheet = OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), date, rows, true);
        List<OnCallRoutineMail.Skipped> skipped = new ArrayList<>();
        for (Item i : sheet.items()) {
            if (OnCallRoutineCodes.SKIPPED.equals(i.state())) {
                skipped.add(new OnCallRoutineMail.Skipped(i.sectionTitle(), i.title(), i.row() == null ? null : i.row().skipReason()));
            }
        }
        List<OnCallRoutineMail.Problem> today = new ArrayList<>();
        for (ProblemRow p : store.problemsOnDays(c.teamId(), d.day(), d.day(), 51)) {
            today.add(new OnCallRoutineMail.Problem(p.severity(), p.taskTitle(), p.day(), p.note()));
        }
        List<OnCallRoutineMail.Problem> older = new ArrayList<>();
        for (ProblemRow p : store.openProblems(c.teamId(), 51)) {
            if (!d.day().equals(p.day())) older.add(new OnCallRoutineMail.Problem(p.severity(), p.taskTitle(), p.day(), p.note()));
        }
        Integer weekDone = null, weekTotal = null;
        List<Row> week = store.itemsOfPeriod(c.teamId(), OnCallRoutineCodes.KIND_WEEK, OnCallRoutineCalendar.weekKey(date));
        if (!week.isEmpty()) {
            weekTotal = week.size();
            int done = 0;
            for (Row r : week) if (OnCallRoutineCodes.DONE.equals(r.state())) done++;
            weekDone = done;
        }
        int open = store.countProblems(c.teamId(), OnCallRoutineCodes.PROBLEM_OPEN);
        return new OnCallRoutineMail.Completion(c.teamName(), c.teamId(), date, d.completedByName(), parseStamp(d.completedAt()),
                d.responsibleName(), nz(d.requiredCount()), nz(d.doneCount()), nz(d.skippedCount()), today.size(), open,
                d.completionSeq(), d.completedLate(), OnCallRoutineCodes.DAY_COMPLETED_PARTIAL.equals(d.status()), skipped, today,
                older, d.note(), weekDone, weekTotal, baseUrl());
    }

    static int nz(Integer i) {
        return i == null ? 0 : i;
    }

    record Sent(Outcome outcome, String status, List<String> to, String html) { }

    interface MailBuilder {
        MailDoc.Mail build();
    }

    /** Takımın alarm adresine e-posta (varsayılan bildirim grubu, yoksa takım e-postası). */
    Sent sendMail(boolean enabled, Long teamId, String teamName, String subject, MailBuilder builder) {
        if (!enabled) return new Sent(Outcome.OFF, MAIL_OFF, List.of(), null);
        List<String> recipients = null;
        try {
            recipients = escalation == null ? List.of() : escalation.teamEmailsForMonitor(teamId, null);
        } catch (RuntimeException e) {
            log.warn("Nöbet rutini bildirimi: takım {} alıcıları okunamadı: {}", teamName, e.toString());
        }
        if (recipients == null) return new Sent(Outcome.FAILED, FAILED_RECIPIENTS, List.of(), null);
        if (recipients.isEmpty() || email == null) return new Sent(Outcome.SKIPPED, SKIP_NO_ADDRESS, List.of(), null);
        MailDoc.Mail m = builder.build();
        String status;
        try {
            status = email.sendHtml(recipients.toArray(new String[0]), null, subject, m.html(), m.text(), null, false, null);
        } catch (RuntimeException e) {
            status = "FAILED: " + e.getClass().getSimpleName();
            log.warn("Nöbet rutini e-postası gönderilemedi (takım {}): {}", teamName, e.toString());
        }
        String st = status == null ? "FAILED: sonuç yok" : status;
        log.info("Nöbet rutini e-postası: takım={} alıcı={} durum={}", teamName, SecretMask.maskEmails(recipients), st);
        return new Sent(mailOutcomeOf(st), st, recipients, m.html());
    }

    String usernameOf(Long userId) {
        if (userId == null) return null;
        try {
            for (Object[] r : users.findOnCallPersonRows(List.of(userId))) {
                if (r != null && r.length > 1 && r[1] instanceof String u) return u.trim();
            }
        } catch (RuntimeException e) {
            log.debug("Nöbet rutini: kullanıcı adı okunamadı: {}", e.toString());
        }
        return null;
    }

    // ── Yeniden deneme + kuyruktaki e-posta ─────────────────────────────────────────────────

    /** Yeniden denemenin bir sayfası ve tur başına en çok sayfa (sınırlı; hakkı bitenler SQL'de süzülür). */
    static final int RETRY_PAGE = 50;
    static final int RETRY_PAGES = 10;
    static final String SKIP_RETRY_PREFIX = "SKIPPED: yeniden deneme yapılmadı — ";

    /**
     * FAILED ve e-postası ulaşmamış PARTIAL bildirimler: ≥ 10 dk arayla, en çok 3 deneme, 24 saat içinde. Her denemeden
     * önce ilk gönderimin kapıları YENİDEN sorulur (takım var / etkin, modül görünür, yönetici + takım anahtarı açık, gün
     * hâlâ aynı tamamlama) — kapanmışsa yeniden denemeden vazgeçilir ({@code attempts} üst sınıra) ve TEK iz satırı yazılır.
     * Satırlar anahtar kümesiyle sayfa sayfa okunur (≤ {@value #RETRY_PAGES} × {@value #RETRY_PAGE}).
     */
    void retryFailed(Instant now, BooleanSupplier stop) {
        String since = OnCallRoutineStore.ts(now.minus(Duration.ofHours(OnCallRoutineCodes.NOTICE_WINDOW_HOURS)));
        long after = 0L;
        for (int page = 0; page < RETRY_PAGES; page++) {
            List<NoticeRow> rows = store.retryable(since, after, OnCallRoutineCodes.MAX_ATTEMPTS, RETRY_PAGE);
            for (NoticeRow r : rows) {
                after = Math.max(after, r.id());
                if (stop.getAsBoolean()) return;
                try {
                    if (!retryOne(r, now)) return;   // sistem bakımı: tur durur
                } catch (RuntimeException e) {
                    log.warn("Nöbet rutini bildirimi yeniden denenemedi (satır {}): {}", r.id(), e.toString());
                }
            }
            if (rows.size() < RETRY_PAGE) return;
        }
    }

    /** Tek satırın yeniden denemesi. false = sistem bakımı susturuyor (tur durur, satır sonra yeniden denenir). */
    boolean retryOne(NoticeRow r, Instant now) {
        if (r.attempts() >= OnCallRoutineCodes.MAX_ATTEMPTS) return true;
        boolean mailOnly = OnCallRoutineCodes.N_PARTIAL.equals(r.status());
        if (mailOnly && mailOutcomeOf(r.mailStatus()) != Outcome.FAILED) return true;
        Instant last = parseStamp(r.finishedAt() != null ? r.finishedAt() : r.claimedAt());
        if (last != null && now.isBefore(last.plus(OnCallRoutineCodes.RETRY_AFTER))) return true;
        if (muted()) return false;
        boolean complete = OnCallRoutineCodes.NOTICE_COMPLETE.equals(r.kind());
        if (!complete && !OnCallRoutineCodes.NOTICE_HANDOVER.equals(r.kind())) return true;
        Long teamId = r.teamId();
        Team team = teams.findById(teamId).orElse(null);
        String stopped = null;
        TeamCtx c = null;
        if (team == null) {
            stopped = "takım yok";
        } else if (Boolean.FALSE.equals(team.getActive())) {
            stopped = "takım pasif";
        } else if (!visible(teamId)) {
            stopped = "modül bu takım için kapalı";
        } else if (!adminOn(complete ? OnCallRoutineVisibility.KEY_COMPLETION_NOTICE : OnCallRoutineVisibility.KEY_HANDOVER_NOTICE)) {
            stopped = "yönetici ayarı kapalı";
        } else {
            c = svc.ctx(teamId);
            OnCallRoutineSettings.Notice cfg = complete ? c.settings().completionNotice() : c.settings().handoverNotice();
            // e-posta yeniden denemesi (PARTIAL) e-posta kanalı ister; FAILED en az bir kanal
            boolean channelLeft = mailOnly ? cfg.mail() : (cfg.mail() || cfg.push());
            if (!cfg.enabled() || !channelLeft) stopped = "takım ayarı kapalı";
        }
        String at = OnCallRoutineStore.ts(now);
        if (complete) {
            String[] p = r.refKey().split("#", 2);
            DayRow d = store.day(teamId, p[0]);
            int seq = p.length > 1 ? Integer.parseInt(p[1]) : 1;
            // Gün yeniden açıldı ya da yeniden tamamlandı → bu bildirim eskidi (yenisi ayrı gider)
            if (stopped == null && (d == null || d.completionSeq() != seq || !OnCallRoutineCodes.completed(d.status()))) {
                stopped = "gün yeniden açıldı ya da yeniden tamamlandı";
            }
            if (stopped != null) {
                giveUpRetry(r, stopped);
                return true;
            }
            if (store.reclaim(r.id(), r.status(), r.attempts(), at, SchedulerService.instanceId()) != 1) return true;
            sendCompletion(c, team, d, r.id(), at, mailOnly);
            return true;
        }
        HandoverRow h = store.handover(teamId, r.refKey());
        Instant start = h == null ? null : parseStamp(h.periodStart());
        if (stopped == null && start == null) stopped = "devir özeti bulunamadı";
        if (stopped != null) {
            giveUpRetry(r, stopped);
            return true;
        }
        if (store.reclaim(r.id(), r.status(), r.attempts(), at, SchedulerService.instanceId()) != 1) return true;
        sendHandover(c, OnCallRoutineCalendar.periodAt(c.rotation(), c.dayStart(), start), r.id(), at, mailOnly);
        return true;
    }

    /** Yeniden denemeden vazgeç (koşullu; kazanan pod TEK iz satırı yazar). Satırın durum ve sonucu aynen kalır. */
    private void giveUpRetry(NoticeRow r, String why) {
        if (store.giveUpRetry(r.id(), r.status(), r.attempts(), OnCallRoutineCodes.MAX_ATTEMPTS) != 1) return;
        log.info("Nöbet rutini bildirimi yeniden denenmeyecek (takım {} tür {} anahtar {}): {}", r.teamId(), r.kind(), r.refKey(), why);
        String trigger = OnCallRoutineCodes.NOTICE_HANDOVER.equals(r.kind()) ? OnCallRoutineCodes.TRIGGER_HANDOVER
                : OnCallRoutineCodes.TRIGGER_DONE;
        String subject = subjectOf(r);
        saveLog(trigger, teamName(r.teamId()), List.of(), subject == null ? "[Site Monitor] Nöbet rutini bildirimi" : subject,
                SKIP_RETRY_PREFIX + why, null);
    }

    /** Kuyruktaki (421) e-postaların gerçek sonucunu satıra yazar (koşullu; asla fırlatmaz). */
    void settleQueuedMails(Instant now) {
        try {
            String since = OnCallRoutineStore.ts(now.minus(Duration.ofHours(OnCallRoutineCodes.NOTICE_WINDOW_HOURS + 1)));
            for (NoticeRow r : store.queuedMails(since, 100)) {
                if (OnCallRoutineCodes.N_SENDING.equals(r.status())) continue;
                String logged = notificationLogs.findEmailStatusById(r.mailLogId()).orElse(null);
                if (logged == null || logged.isBlank() || logged.startsWith("QUEUED")) continue;
                Outcome mail = logged.startsWith("SENT") ? Outcome.DELIVERED
                        : logged.startsWith("SKIPPED") ? Outcome.SKIPPED : Outcome.FAILED;
                String status = finalStatus(mail, pushOutcomeOf(r.pushStatus()));
                String reason = OnCallRoutineCodes.N_SKIPPED.equals(status) ? "NO_RECIPIENT" : r.reason();
                String mailStatus = clip(mail == Outcome.DELIVERED ? "SENT" : logged, LOG_FIELD_MAX);
                if (store.settleQueuedMail(r.id(), r.status(), r.mailStatus(), status, reason, mailStatus) == 1) {
                    log.info("Nöbet rutini bildirimi: takım {} {} kuyruktaki e-postanın sonucu geldi ({}) — durum {} → {}",
                            r.teamId(), r.refKey(), mail, r.status(), status);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Nöbet rutini: kuyruktaki e-postaların sonucu okunamadı: {}", e.toString());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Hatırlatmalar (§5.3) — YALNIZ sorumluya push
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Bir takımın bugünkü hatırlatmaları. Dönen: kuyruğa alınan push sayısı. */
    public int reminders(TeamCtx c, Instant now) {
        if (!adminOn(OnCallRoutineVisibility.KEY_PUSH_REMINDERS)) return 0;
        OnCallRoutineSettings.Reminders rem = c.settings().reminders();
        // Pasif takıma hatırlatma yok (tamamlama / devir bildirimleri de pasif takımda SKIPPED)
        if (!rem.enabled() || !c.enabled() || !c.configured() || !c.teamActive()) return 0;
        LocalDate today = OnCallRoutineCalendar.localDate(now);
        if (!c.workday(today) || c.paused(today) || !visible(c.teamId())) return 0;
        DayRow day = store.day(c.teamId(), today.toString());
        if (day != null && OnCallRoutineCodes.completed(day.status())) return 0;

        // Hangi yuvalar şu an açık?
        List<Slot> due = new ArrayList<>();
        List<String> times = rem.times();
        for (int i = 0; i < times.size(); i++) {
            Instant slot = at(today, times.get(i));
            if (slot != null && inWindow(now, slot, OnCallRoutineCodes.REMINDER_CATCHUP_MIN)) {
                due.add(new Slot(OnCallRoutineCodes.NOTICE_REMINDER, today + "|" + times.get(i), times.get(i), null, i == 0));
            }
        }
        if (rem.sectionDeadlines()) {
            for (OnCallRoutineTemplate.Section s : c.template().sections()) {
                if (s.windowTo() == null) continue;
                Instant slot = at(today, s.windowTo());
                if (slot != null && inWindow(now, slot, OnCallRoutineCodes.REMINDER_CATCHUP_MIN)) {
                    due.add(new Slot(OnCallRoutineCodes.NOTICE_SECTION, today + "|" + s.id(), s.windowTo(), s.id(), false));
                }
            }
        }
        if (rem.dueMorning()) {
            Instant slot = OnCallRoutineCalendar.at(today, c.dayStart());
            if (inWindow(now, slot, OnCallRoutineCodes.DUE_CATCHUP_MIN)) {
                due.add(new Slot(OnCallRoutineCodes.NOTICE_DUE, today.toString(), null, null, false));
            }
        }
        if (due.isEmpty()) return 0;
        // Daha önce karar verilmiş yuvalar atlanır (ucuz: takım × tür × anahtar benzersiz satırı)
        List<Slot> open = new ArrayList<>();
        for (Slot s : due) if (store.notice(c.teamId(), s.kind(), s.ref()) == null) open.add(s);
        if (open.isEmpty()) return 0;
        if (muted()) {
            log.info("Nöbet rutini hatırlatması sistem bakımı nedeniyle atlandı (takım {}, {} yuva)", c.teamName(), open.size());
            return 0;
        }

        // Sayfa (bugün, açık görünüm) — bekleyen sayılar
        List<Row> rows = store.items(c.teamId(), OnCallRoutinePlan.periodsSeenFrom(today));
        OnCallRoutinePlan.Sheet sheet = OnCallRoutinePlan.sheet(c.template(), c.settings(), c.holidays(), today, rows, false);
        Responsible resp = null;
        AppUser user = null;
        int queued = 0;
        String at = OnCallRoutineStore.ts(now);
        for (Slot s : open) {
            int n = pendingFor(sheet, s, today);
            if (n <= 0) continue;   // bekleyen yok → push da satır da yok (yuva sessiz geçer)
            if (resp == null) {
                resp = svc.responsible(c, today);
                user = resp.userId() == null ? null : users.findById(resp.userId()).orElse(null);
            }
            if (resp.userId() == null || user == null) {
                store.claim(c.teamId(), s.kind(), s.ref(), OnCallRoutineCodes.N_SKIPPED, "NO_RESPONSIBLE", null, at,
                        SchedulerService.instanceId());
                continue;
            }
            if (Boolean.FALSE.equals(user.getActive())) {
                store.claim(c.teamId(), s.kind(), s.ref(), OnCallRoutineCodes.N_SKIPPED, "RESPONSIBLE_INACTIVE", user.getId(), at,
                        SchedulerService.instanceId());
                continue;
            }
            Long id = store.claim(c.teamId(), s.kind(), s.ref(), OnCallRoutineCodes.N_SENDING, null, user.getId(), at,
                    SchedulerService.instanceId());
            if (id == null) continue;
            queued += sendReminder(c, s, n, user, id, at, now);
        }
        return queued;
    }

    record Slot(String kind, String ref, String hhmm, String sectionId, boolean firstSlot) { }

    static Instant at(LocalDate d, String hhmm) {
        try {
            return OnCallRoutineCalendar.at(d, LocalTime.parse(hhmm));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean inWindow(Instant now, Instant slot, int catchupMin) {
        return !now.isBefore(slot) && now.isBefore(slot.plus(Duration.ofMinutes(catchupMin)));
    }

    /** Yuvanın koşulu: REMINDER = bekleyen zorunlu; SECTION = bölümün bekleyen zorunlu günlükleri; DUE = bugün son günü bekleyen. */
    static int pendingFor(OnCallRoutinePlan.Sheet sheet, Slot s, LocalDate today) {
        int n = 0;
        for (Item i : sheet.items()) {
            if (i.removed() || i.resolved()) continue;
            switch (s.kind()) {
                case OnCallRoutineCodes.NOTICE_REMINDER -> {
                    if (i.required()) n++;
                }
                case OnCallRoutineCodes.NOTICE_SECTION -> {
                    if (i.required() && OnCallRoutineCodes.KIND_DAY.equals(i.periodKind()) && s.sectionId().equals(i.sectionId())) n++;
                }
                case OnCallRoutineCodes.NOTICE_DUE -> {
                    if (!OnCallRoutineCodes.KIND_DAY.equals(i.periodKind()) && today.equals(i.dueDay())
                            && OnCallRoutineCodes.PENDING.equals(i.state())) n++;
                }
                default -> { }
            }
        }
        return n;
    }

    int sendReminder(TeamCtx c, Slot s, int n, AppUser u, Long id, String claimedAt, Instant now) {
        String team = c.teamName();
        try {
            if (push == null) {
                store.finishNotice(id, claimedAt, OnCallRoutineCodes.N_SKIPPED, "NO_RECIPIENT", null, PUSH_SKIPPED, 0, null,
                        OnCallRoutineStore.ts(now()), null);
                return 0;
            }
            int limit = push.messageCharLimit();
            String tr, en, key;
            String hhmm = s.hhmm() == null ? "" : s.hhmm().replace(":", "");
            switch (s.kind()) {
                case OnCallRoutineCodes.NOTICE_SECTION -> {
                    tr = OnCallRoutineMail.pushSection(team, s.hhmm(), n, false, limit);
                    en = OnCallRoutineMail.pushSection(team, s.hhmm(), n, true, limit);
                    key = "or-sec:" + c.teamId() + ":" + s.ref().split("\\|", 2)[0] + ":" + s.sectionId();
                }
                case OnCallRoutineCodes.NOTICE_DUE -> {
                    tr = OnCallRoutineMail.pushDue(team, n, false, limit);
                    en = OnCallRoutineMail.pushDue(team, n, true, limit);
                    key = "or-due:" + c.teamId() + ":" + s.ref();
                }
                default -> {
                    tr = OnCallRoutineMail.pushReminder(team, n, s.firstSlot(), false, limit);
                    en = OnCallRoutineMail.pushReminder(team, n, s.firstSlot(), true, limit);
                    key = "or-rem:" + c.teamId() + ":" + s.ref().split("\\|", 2)[0] + ":" + hhmm;
                }
            }
            String dn = u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getUsername();
            String skip = UserPushRecipientResolver.preferenceSkip(u, "WARNING", null, now);
            UserPushService.DirectRecipient r = new UserPushService.DirectRecipient(u.getUsername().trim(), dn,
                    Boolean.TRUE.equals(u.getPushOptOut()), u.getPushLang(), skip);
            Map<String, Object> res = push.enqueueDirectLocalized(List.of(r), c.teamId(), OnCallRoutineCodes.TRIGGER_REMINDER,
                    "WARNING", OnCallRoutineCodes.PUSH_TYPE, team, new UserPushService.LocalizedText(tr, en), key);
            int q = res != null && res.get("queued") instanceof Number num ? num.intValue() : 0;
            store.finishNotice(id, claimedAt, q > 0 ? OnCallRoutineCodes.N_SENT : OnCallRoutineCodes.N_SKIPPED,
                    q > 0 ? null : "NO_RECIPIENT", null, q > 0 ? PUSH_DELIVERED : PUSH_SKIPPED, q, null,
                    OnCallRoutineStore.ts(now()), null);
            return q;
        } catch (RuntimeException e) {
            log.warn("Nöbet rutini hatırlatma push'u kuyruğa alınamadı (takım {}): {}", team, e.toString());
            store.finishNotice(id, claimedAt, OnCallRoutineCodes.N_FAILED, "ERROR", null, PUSH_FAILED, 0, e.getClass().getSimpleName(),
                    OnCallRoutineStore.ts(now()), null);
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // Devir bildirimi (§5.5)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Önceki dönem 24 saat içinde bittiyse özet saklanır ve bildirim gönderilir (bir kez). true = bu turda karar verildi. */
    public boolean handover(TeamCtx c, Period prev, Instant now) {
        Long teamId = c.teamId();
        String key = prev.key().toString();
        svc.storeHandover(c, prev);
        if (store.notice(teamId, OnCallRoutineCodes.NOTICE_HANDOVER, key) != null) return false;
        String at = OnCallRoutineStore.ts(now);
        String subject = OnCallRoutineMail.handoverSubject(c.teamName(), prev.first(), prev.last(), false);
        if (!c.teamActive()) {
            handoverDecision(c, key, "TEAM_INACTIVE", subject, SKIP_TEAM_INACTIVE, at);
            return true;
        }
        if (!visible(teamId)) {
            handoverDecision(c, key, "MODULE_HIDDEN", subject, SKIP_MODULE_HIDDEN, at);
            return true;
        }
        OnCallRoutineSettings.Notice cfg = c.settings().handoverNotice();
        if (!adminOn(OnCallRoutineVisibility.KEY_HANDOVER_NOTICE)) {
            handoverDecision(c, key, "DISABLED", subject, SKIP_ADMIN_OFF, at);
            return true;
        }
        if (!cfg.enabled() || (!cfg.mail() && !cfg.push())) {
            handoverDecision(c, key, "DISABLED", subject, SKIP_TEAM_OFF, at);
            return true;
        }
        if (muted()) {
            Instant windowEnd = prev.end().plus(Duration.ofHours(OnCallRoutineCodes.NOTICE_WINDOW_HOURS));
            if (!now.isBefore(windowEnd.minus(WINDOW_END_SLACK))) {
                handoverDecision(c, key, "MAINTENANCE", subject, SKIP_MAINTENANCE, at);
                return true;
            }
            return false;   // pencere içinde bakım bitince yeniden denenir
        }
        Long id = store.claim(teamId, OnCallRoutineCodes.NOTICE_HANDOVER, key, OnCallRoutineCodes.N_SENDING, null, null, at,
                SchedulerService.instanceId());
        if (id == null) return false;
        sendHandover(c, prev, id, at, false);
        return true;
    }

    private void handoverDecision(TeamCtx c, String key, String reason, String subject, String trace, String at) {
        Long id = store.claim(c.teamId(), OnCallRoutineCodes.NOTICE_HANDOVER, key, OnCallRoutineCodes.N_SKIPPED, reason, null, at,
                SchedulerService.instanceId());
        if (id != null) saveLog(OnCallRoutineCodes.TRIGGER_HANDOVER, c.teamName(), List.of(), subject, trace, null);
    }

    String sendHandover(TeamCtx c, Period prev, Long noticeId, String claimedAt, boolean mailOnly) {
        String teamName = c.teamName();
        String subject = OnCallRoutineMail.handoverSubject(teamName, prev.first(), prev.last(), false);
        Sent mail = null;
        Outcome pushOut = null;
        Long traceId = null;
        try {
            OnCallRoutineMail.Handover info = handoverInfo(c, prev);
            OnCallRoutineSettings.Notice cfg = c.settings().handoverNotice();
            mail = sendMail(cfg.mail(), c.teamId(), teamName, subject, () -> OnCallRoutineMail.handover(info, false));
            int queued = 0;
            String pushNote;
            if (mailOnly) {
                pushOut = Outcome.DELIVERED;
                pushNote = " · push: önceki denemede (yeniden gönderilmedi)";
            } else if (!cfg.push()) {
                pushOut = Outcome.OFF;
                pushNote = " · push: kapalı (takım ayarı)";
            } else if (push == null) {
                pushOut = Outcome.SKIPPED;
                pushNote = " · push: yok";
            } else {
                try {
                    int limit = push.messageCharLimit();
                    Map<String, Object> pr = push.enqueueTeamNoticeLocalized(c.teamId(), OnCallRoutineCodes.TRIGGER_HANDOVER, "WARNING",
                            OnCallRoutineCodes.PUSH_TYPE, teamName, new UserPushService.LocalizedText(
                                    OnCallRoutineMail.pushHandover(teamName, info.completedDays(), info.workdays(), info.problemsOpen(), false, limit),
                                    OnCallRoutineMail.pushHandover(teamName, info.completedDays(), info.workdays(), info.problemsOpen(), true, limit)),
                            "or-hand:" + c.teamId() + ":" + prev.key(), Set.of());
                    queued = pr.get("queued") instanceof Number q ? q.intValue() : 0;
                    pushOut = queued > 0 ? Outcome.DELIVERED : Outcome.SKIPPED;
                    pushNote = queued > 0 ? " · push " + queued
                            : " · push: " + (pr.get("reason") == null ? "alıcı yok" : String.valueOf(pr.get("reason")));
                } catch (RuntimeException e) {
                    pushOut = Outcome.FAILED;
                    pushNote = " · push: FAILED (" + e.getClass().getSimpleName() + ")";
                    log.warn("Nöbet devri push'u kuyruğa alınamadı (takım {}): {}", teamName, e.toString());
                }
            }
            String status = finalStatus(mail.outcome(), pushOut);
            String reason = OnCallRoutineCodes.N_SKIPPED.equals(status) ? "NO_RECIPIENT" : null;
            traceId = saveLog(OnCallRoutineCodes.TRIGGER_HANDOVER, teamName, mail.to(), subject, mail.status() + pushNote, mail.html());
            store.finishNotice(noticeId, claimedAt, status, reason, clip(mail.status(), LOG_FIELD_MAX), pushClass(pushOut), queued, null,
                    OnCallRoutineStore.ts(now()), traceId);
            return status;
        } catch (RuntimeException e) {
            return failAfterClaim(OnCallRoutineCodes.TRIGGER_HANDOVER, teamName, subject, noticeId, claimedAt, mail, pushOut, mailOnly,
                    traceId != null,
                    "FAILED: nöbet devri bildirimi hazırlanamadı (", "devir " + prev.key(), e);
        }
    }

    OnCallRoutineMail.Handover handoverInfo(TeamCtx c, Period prev) {
        String f = prev.first().toString();
        String t = prev.last().toString();
        List<OnCallRoutineMail.HandoverDay> days = new ArrayList<>();
        int workdays = 0, completed = 0, skippedItems = 0, missedItems = 0;
        java.util.Map<String, DayRow> byDay = new java.util.HashMap<>();
        for (DayRow r : store.days(c.teamId(), f, t)) byDay.put(r.day(), r);
        java.util.Map<String, Integer> probs = new java.util.HashMap<>();
        for (ProblemRow p : store.problemsOnDays(c.teamId(), f, t, 5000)) probs.merge(p.day(), 1, Integer::sum);
        Instant settledAt = now();
        for (LocalDate d : prev.days()) {
            if (!c.workday(d)) continue;
            DayRow r = byDay.get(d.toString());
            // Bitmemiş gün (devir günü hâlâ sürüyor) özete girmez — yarım günden "kaçırıldı" sayılmasın
            if (!OnCallRoutineService.daySettled(r, d, settledAt)) continue;
            workdays++;
            String status = r == null ? OnCallRoutineCodes.DAY_NONE : r.status();
            if (r != null && OnCallRoutineCodes.completed(r.status())) completed++;
            if (r != null && r.skippedCount() != null) skippedItems += r.skippedCount();
            if (r != null && (OnCallRoutineCodes.DAY_INCOMPLETE.equals(r.status()) || OnCallRoutineCodes.DAY_MISSED.equals(r.status()))
                    && r.pendingCount() != null) {
                missedItems += r.pendingCount();
            }
            days.add(new OnCallRoutineMail.HandoverDay(d, status, r == null ? null : r.doneCount(), r == null ? null : r.requiredCount(),
                    r == null ? null : r.skippedCount(), probs.getOrDefault(d.toString(), 0), r == null ? null : r.completedByName()));
        }
        Set<String> weekKeys = new LinkedHashSet<>();
        for (LocalDate d : prev.days()) weekKeys.add(OnCallRoutineCalendar.weekKey(d));
        List<OnCallRoutineMail.PeriodItem> items = new ArrayList<>();
        for (String wk : weekKeys) {
            for (Row r : store.itemsOfPeriod(c.teamId(), OnCallRoutineCodes.KIND_WEEK, wk)) {
                items.add(new OnCallRoutineMail.PeriodItem(r.title(), r.state()));
            }
        }
        Set<String> monthKeys = new LinkedHashSet<>();
        for (LocalDate d : prev.days()) monthKeys.add(OnCallRoutineCalendar.monthKey(d));
        for (String mk : monthKeys) {
            for (Row r : store.itemsOfPeriod(c.teamId(), OnCallRoutineCodes.KIND_MONTH, mk)) {
                if (r.dueDay() != null && r.dueDay().compareTo(f) >= 0 && r.dueDay().compareTo(t) <= 0) {
                    items.add(new OnCallRoutineMail.PeriodItem(r.title(), r.state()));
                }
            }
        }
        List<OnCallRoutineMail.Problem> open = new ArrayList<>();
        for (ProblemRow p : store.openProblems(c.teamId(), 51)) {
            open.add(new OnCallRoutineMail.Problem(p.severity(), p.taskTitle(), p.day(), p.note()));
        }
        int openTotal = store.countProblems(c.teamId(), OnCallRoutineCodes.PROBLEM_OPEN);
        HandoverRow h = store.handover(c.teamId(), prev.key().toString());
        List<String[]> notes = new ArrayList<>();
        for (DayRow r : byDay.values()) {
            if (r.note() != null && !r.note().isBlank()) notes.add(new String[] {r.day(), r.note(), r.noteByName()});
        }
        notes.sort((a, b) -> a[0].compareTo(b[0]));
        Period current = OnCallRoutineCalendar.periodAt(c.rotation(), c.dayStart(), prev.end());
        Responsible next = svc.responsible(c, current.first());
        return new OnCallRoutineMail.Handover(c.teamName(), c.teamId(), prev.first(), prev.last(), svc.responsibleNames(c, prev),
                next.name(), workdays, completed, skippedItems, missedItems, openTotal, days, items, open,
                h == null ? null : h.note(), notes, baseUrl());
    }

    // ── Günlük ───────────────────────────────────────────────────────────────────────────────

    private String teamName(Long teamId) {
        try {
            return teams.findById(teamId).map(Team::getName).filter(n -> n != null && !n.isBlank()).orElse("Takım #" + teamId);
        } catch (RuntimeException e) {
            return "Takım #" + teamId;
        }
    }

    /** Günlük satırını yazar; kimliğini döner (yazılamazsa null). Sütunlar merkezde sığdırılır. */
    Long saveLog(String trigger, String teamName, List<String> recipients, String subject, String status, String html) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);
            n.setSentAt(OnCallRoutineStore.ts(now()));
            n.setRecipientName(teamName);
            n.setRecipientEmail(String.join(", ", recipients == null ? List.of() : recipients));
            n.setRecipientRole("TEAM");
            n.setSubject(subject);
            n.setMessage(html);
            n.setEmailStatus(status);
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(trigger);
            if (email != null) n.setEmailFrom(email.getEmailFrom());
            NotificationLog saved = notificationLogs.save(n);
            return saved == null ? null : saved.getId();
        } catch (RuntimeException e) {
            log.warn("Nöbet rutini bildirim günlüğü yazılamadı: {}", e.toString());
            return null;
        }
    }
}

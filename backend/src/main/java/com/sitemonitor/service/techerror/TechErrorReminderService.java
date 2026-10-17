package com.sitemonitor.service.techerror;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorApprovalToken;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReminder;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TechErrorApprovalTokenRepository;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReminderRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.TechErrorApprovalMail;
import com.sitemonitor.service.mail.TechErrorReminderMail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kanal teknik hata raporu HATIRLATMALARI (2026-10-17, 12. sürüm — kullanıcı onayı C9 / C10: "ayın N. günü raporu
 * açılmamış / gönderilmemiş kanal için takım postası + push; takım başına açılır" ve "onay bekleyen rapor hatırlatması").
 * Her gün 09:15 (İstanbul) {@code scheduler_lock} {@value #LOCK} altında; iki iş birden:
 *
 * <ol>
 *   <li><b>Dönem hatırlatması</b> — yalnız Ayarlar'da hatırlatmaya AÇIK takımlar (boş liste = herkes için KAPALI).
 *   Dönem = içinde bulunulan ayın bir öncesi (raporun kapsadığı ay); ayın {@code reminder-day}. gününden itibaren, rapor
 *   gönderilene kadar her {@code reminder-repeat-days} günde bir (0 = bir kez). Dönem iki ay eskiyince (yeni ay
 *   başlayınca) hatırlatılmaz. "Gönderilmedi" = dönem raporu YOK ya da hiç gönderilmedi ({@code last_sent_at} boş);
 *   gönderilip sonra değişen rapor hatırlatılmaz, kuyruktaki / şu an gönderilen rapor da. Onay bekleyen rapor listede
 *   "PO onayı bekliyor" cümlesiyle durur. Takım başına TEK posta (kanallar tabloda) + istenirse takım push'u.</li>
 *   <li><b>Onay hatırlatması</b> — bekleyen ve eskimemiş ({@code approval_rev = COALESCE(content_rev, 0)}) onay isteği
 *   {@code approval-reminder-days} gündür karar beklemişse onaylayıcılara kısa posta (yeni bağlantı ÜRETİLMEZ); rapor ×
 *   istek başına en çok o kadar günde bir.</li>
 * </ol>
 *
 * <p><b>Tam bir kez:</b> hatırlatma gönderilmeden ÖNCE {@code tech_error_reminders} satırı koşullu sahiplenilir (kanal ×
 * dönem × tür) — iki pod aynı günü iki kez göndermez. Hiçbir kanaldan ulaşılamayan hatırlatmanın (posta FAILED, push
 * yok) talebi geri bırakılır; ertesi gün yeniden denenir. <b>Her karar</b> bir {@code notification_logs} satırı
 * (tetikleyici {@value #TRIGGER} / {@value #TRIGGER_APPROVAL}): gönderildi / başarısız / takım adresi yok / modül takıma
 * kapalı / pasif takım / onaylayıcı adresi yok / sistem bakımı (bakım susturmasında talep YAPILMAZ — ertesi gün yeniden
 * denenir). Pasif kullanıcı adresleri posta hunisinde düşer; push pasif hesaplara gitmez.
 */
@Slf4j
@Service
public class TechErrorReminderService {

    public static final String TRIGGER = "TECH_ERROR_REMINDER";
    public static final String TRIGGER_APPROVAL = "TECH_ERROR_APPROVAL_REMINDER";
    public static final String PUSH_MONITOR_TYPE = "TECH_ERROR_REPORT";
    static final String LOCK = "tech-error-reminder";
    /** Bu kadar aydan eski dönemlerin talep satırları her koşuda silinir. */
    static final int KEEP_MONTHS = 6;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TechErrorReportService service;
    private final TechErrorReminderRepository reminders;
    private final TechErrorReportRepository reports;
    private final TechErrorChannelRepository channels;
    private final TechErrorApprovalService approvals;
    private final TechErrorApprovalTokenRepository tokens;
    private final TeamRepository teams;
    private final EmailNotificationService email;
    private final NotificationLogRepository logs;
    private final AppSettingsService settings;
    private final TechErrorVisibilityService visibility;

    @Autowired(required = false) @Lazy private SchedulerService scheduler;
    @Autowired(required = false) @Lazy private SystemMaintenanceService maintenance;
    @Autowired(required = false) @Lazy private UserPushService push;

    @Value("${site.monitor.tech-error-report.reminder-enabled:true}")
    private boolean enabled = true;

    public TechErrorReminderService(TechErrorReportService service, TechErrorReminderRepository reminders,
                                    TechErrorReportRepository reports, TechErrorChannelRepository channels,
                                    TechErrorApprovalService approvals, TechErrorApprovalTokenRepository tokens, TeamRepository teams,
                                    EmailNotificationService email, NotificationLogRepository logs, AppSettingsService settings,
                                    TechErrorVisibilityService visibility) {
        this.service = service;
        this.reminders = reminders;
        this.reports = reports;
        this.channels = channels;
        this.approvals = approvals;
        this.tokens = tokens;
        this.teams = teams;
        this.email = email;
        this.logs = logs;
        this.settings = settings;
        this.visibility = visibility;
    }

    void setEnabled(boolean v) { this.enabled = v; }
    void setMaintenance(SystemMaintenanceService m) { this.maintenance = m; }
    void setPush(UserPushService p) { this.push = p; }

    /**
     * Koşu özeti: dönem hatırlatmasında posta / push alan takım sayısı, onay hatırlatmasında posta alan rapor sayısı,
     * atlanan (günlüğe SKIPPED yazılan) karar sayısı.
     */
    public record Result(int teamsReminded, int pushTeams, int approvalReports, int skipped) { }

    @Scheduled(cron = "${site.monitor.tech-error-report.reminder-cron:0 15 9 * * *}", zone = "Europe/Istanbul")
    public void scheduled() {
        if (!enabled) return;
        try {
            if (scheduler == null) run();
            else scheduler.callWithSchedulerLock(LOCK, this::run);
        } catch (Exception e) {
            log.error("Teknik hata raporu hatırlatması başarısız: {}", e.getMessage(), e);
        }
    }

    public Result run() {
        Instant now = service.now();
        LocalDate today = LocalDate.ofInstant(now, TechErrorReportService.ZONE);
        int[] counts = new int[4];
        try {
            YearMonth cut = YearMonth.from(today).minusMonths(KEEP_MONTHS);
            reminders.deleteOlderThan(cut.getYear() * 12 + cut.getMonthValue());
        } catch (RuntimeException e) {
            log.debug("Teknik hata raporu hatırlatma satırları budanamadı: {}", e.toString());
        }
        boolean muted = muted();
        String base = baseUrl();
        try {
            periodReminders(now, today, muted, base, counts);
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu dönem hatırlatması yarıda kaldı: {}", e.toString(), e);
        }
        try {
            approvalReminders(now, today, muted, base, counts);
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu onay hatırlatması yarıda kaldı: {}", e.toString(), e);
        }
        Result r = new Result(counts[0], counts[1], counts[2], counts[3]);
        if (r.teamsReminded() + r.approvalReports() + r.skipped() > 0) {
            log.info("Teknik hata raporu hatırlatması: {} takım, {} takım push'u, {} onay bekleyen rapor, {} atlandı",
                    r.teamsReminded(), r.pushTeams(), r.approvalReports(), r.skipped());
        }
        return r;
    }

    // ── Talep ────────────────────────────────────────────────────────────────────────────────────────

    /** Sahiplenilmiş talep: geri bırakmak için önceki gün / anahtar ve yeni sürüm. */
    record Claim(Long id, int rev, String prevDay, String prevRef) { }

    /**
     * Talep: satır yoksa INSERT (benzersizlik ihlali → başka pod sahiplendi), varsa sürüm eşleşmesiyle UPDATE. Sahiplenilemezse
     * null. {@code ref}: APPROVAL için istek anahtarı, PERIOD için boş metin.
     */
    Claim claim(Long channelId, YearMonth p, String kind, TechErrorReminder existing, String today, String ref) {
        String now = ISO.format(Instant.now());
        if (existing == null) {
            TechErrorReminder row = new TechErrorReminder();
            row.setChannelId(channelId);
            row.setReportYear(p.getYear());
            row.setReportMonth(p.getMonthValue());
            row.setKind(kind);
            row.setRefKey(ref);
            row.setLastSentOn(today);
            row.setSendCount(1);
            row.setClaimRev(1);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            try {
                TechErrorReminder saved = reminders.saveAndFlush(row);
                return new Claim(saved.getId(), saved.getClaimRev(), null, null);
            } catch (org.springframework.dao.DataIntegrityViolationException race) {
                log.debug("Teknik hata raporu hatırlatması başka pod'da sahiplenildi: kanal {} {} {}", channelId, p, kind);
                return null;
            }
        }
        if (reminders.claim(existing.getId(), existing.getClaimRev(), today, ref, now) != 1) return null;
        return new Claim(existing.getId(), existing.getClaimRev() + 1, existing.getLastSentOn(), existing.getRefKey());
    }

    void release(Claim c) {
        if (c == null) return;
        try {
            reminders.release(c.id(), c.rev(), c.prevDay(), c.prevRef(), ISO.format(Instant.now()));
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu hatırlatma talebi bırakılamadı (#{}): {}", c.id(), e.toString());
        }
    }

    /** Talep satırı bugün için vadeli mi: yok / bırakılmış → evet; {@code everyDays} 0 → bir kez; aksi hâlde son günden bu yana ≥ N gün. */
    static boolean due(TechErrorReminder r, LocalDate today, int everyDays) {
        if (r == null || r.getLastSentOn() == null || r.getLastSentOn().isBlank()) return true;
        if (everyDays <= 0) return false;
        try {
            return !LocalDate.parse(r.getLastSentOn()).plusDays(everyDays).isAfter(today);
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ── 1. Dönem hatırlatması ────────────────────────────────────────────────────────────────────────

    /** Kanalın hatırlatılacak durumu; null = hatırlatılmaz (gönderildi, kuyrukta ya da şu an gönderiliyor). */
    static TechErrorReminderMail.State stateOf(TechErrorReportRepository.Row r, Instant now) {
        if (r == null) return TechErrorReminderMail.State.MISSING;
        if (r.getLastSentAt() != null) return null;
        if ("QUEUED".equals(r.getSendState())) return null;
        if ("SENDING".equals(r.getSendState()) && r.getSendClaimedAt() != null
                && r.getSendClaimedAt().isAfter(now.minus(TechErrorReportSender.STALE_CLAIM))) return null;
        String a = r.getApprovalState();
        long content = r.getContentRev() == null ? 0L : r.getContentRev();
        boolean stale = r.getApprovalRev() != null && content > r.getApprovalRev();
        if ("PENDING".equals(a)) return stale ? TechErrorReminderMail.State.STALE : TechErrorReminderMail.State.PENDING;
        if ("REJECTED".equals(a)) return TechErrorReminderMail.State.REJECTED;
        if ("APPROVED".equals(a)) return stale ? TechErrorReminderMail.State.STALE : TechErrorReminderMail.State.APPROVED_UNSENT;
        return TechErrorReminderMail.State.DRAFT;
    }

    private record Due(TechErrorChannel channel, TechErrorReportRepository.Row row, TechErrorReminder claimRow,
                       TechErrorReminderMail.State state) { }

    private void periodReminders(Instant now, LocalDate today, boolean muted, String base, int[] counts) {
        Set<Long> optIn = TechErrorReportSettings.reminderTeamIds(settings);
        if (optIn.isEmpty()) return;                                   // opt-in: kimse seçilmediyse KAPALI
        int day = TechErrorReportSettings.effectiveInt(settings, TechErrorReportSettings.REMINDER_DAY);
        if (today.getDayOfMonth() < day) return;
        int repeat = TechErrorReportSettings.effectiveInt(settings, TechErrorReportSettings.REMINDER_REPEAT_DAYS);
        boolean pushOn = TechErrorReportSettings.reminderPush(settings);
        YearMonth period = YearMonth.from(today).minusMonths(1);        // raporun kapsadığı ay (defaultPeriod ile aynı kural)

        List<TechErrorChannel> chs = new ArrayList<>();
        for (TechErrorChannel ch : service.channels(optIn)) if (ch.isActive()) chs.add(ch);
        if (chs.isEmpty()) return;
        List<Long> ids = chs.stream().map(TechErrorChannel::getId).toList();
        Map<Long, TechErrorReportRepository.Row> rows = service.periodRows(ids, period);
        Map<Long, TechErrorReminder> claimRows = new HashMap<>();
        for (TechErrorReminder r : reminders.findByKindAndReportYearAndReportMonthAndChannelIdIn(TechErrorReminder.KIND_PERIOD,
                period.getYear(), period.getMonthValue(), ids)) {
            claimRows.put(r.getChannelId(), r);
        }
        Map<Long, List<Due>> byTeam = new LinkedHashMap<>();
        for (TechErrorChannel ch : chs) {
            TechErrorReportRepository.Row row = rows.get(ch.getId());
            TechErrorReminderMail.State st = stateOf(row, now);
            if (st == null) continue;
            TechErrorReminder c = claimRows.get(ch.getId());
            if (!due(c, today, repeat)) continue;
            byTeam.computeIfAbsent(ch.getTeamId(), k -> new ArrayList<>()).add(new Due(ch, row, c, st));
        }
        if (byTeam.isEmpty()) return;
        Map<Long, Team> teamMap = new HashMap<>();
        for (Team t : teams.findAllById(byTeam.keySet())) teamMap.put(t.getId(), t);
        String todayStr = today.toString();

        for (Map.Entry<Long, List<Due>> en : byTeam.entrySet()) {
            Team team = teamMap.get(en.getKey());
            if (team == null) continue;                                // takımı silinmiş kanal: saklama kuralı temizler
            boolean enTpl = en.getValue().stream().allMatch(d -> "en".equals(langOf(d)));
            String subject = TechErrorReminderMail.teamSubject(period.getYear(), period.getMonthValue(), enTpl);
            List<String> to = TechErrorReportSender.recipients(team);
            if (muted) {
                saveLog(TRIGGER, team.getName(), to, "TEAM", subject, "SKIPPED: sistem bakımı", channelSummary(en.getValue(), enTpl));
                counts[3]++;
                continue;                                              // talep YOK: bakım bitince ertesi gün yeniden denenir
            }
            List<Due> mine = new ArrayList<>();
            List<Claim> claims = new ArrayList<>();
            for (Due d : en.getValue()) {
                Claim c = claim(d.channel().getId(), period, TechErrorReminder.KIND_PERIOD, d.claimRow(), todayStr, "");
                if (c == null) continue;                               // başka pod sahiplendi
                mine.add(d);
                claims.add(c);
            }
            if (mine.isEmpty()) continue;
            try {
                remindTeam(team, period, mine, claims, enTpl, subject, to, base, day, repeat, pushOn, todayStr, counts);
            } catch (RuntimeException e) {
                // Beklenmeyen hata bir takımda kalır (diğerleri sürer): talep bırakılır, iz FAILED — ertesi gün yeniden
                log.warn("Teknik hata raporu hatırlatması takım {} için yarıda kaldı: {}", team.getId(), e.toString(), e);
                for (Claim c : claims) release(c);
                saveLog(TRIGGER, team.getName(), to, "TEAM", subject, "FAILED: " + clip(e.getClass().getSimpleName(), 120),
                        "");
                counts[3]++;
            }
        }
    }

    /** Sahiplenilmiş kanallarla bir takımın hatırlatması: kapı kararları, posta, push, günlük. */
    private void remindTeam(Team team, YearMonth period, List<Due> mine, List<Claim> claims, boolean enTpl, String subject,
                            List<String> to, String base, int day, int repeat, boolean pushOn, String todayStr, int[] counts) {
        String summary = channelSummary(mine, enTpl);
        if (!visibility.teamVisible(team.getId())) {
            saveLog(TRIGGER, team.getName(), to, "TEAM", subject, "SKIPPED: modül bu takıma kapalı", summary);
            counts[3]++;
            return;
        }
        if (Boolean.FALSE.equals(team.getActive())) {
            saveLog(TRIGGER, team.getName(), to, "TEAM", subject, "SKIPPED: pasif takım", summary);
            counts[3]++;
            return;
        }
        List<TechErrorReminderMail.Item> items = new ArrayList<>();
        for (Due d : mine) {
            items.add(new TechErrorReminderMail.Item(d.channel().getName(), d.state(), TechErrorApprovalService.approvalRequired(d.channel())));
        }
        String status;
        boolean delivered = false;
        if (to.isEmpty()) {
            status = "SKIPPED: takımın e-posta adresi yok";
        } else {
            try {
                MailDoc.Mail mail = TechErrorReminderMail.team(team.getName(), team.getId(), period.getYear(), period.getMonthValue(),
                        items, enTpl, base, day, repeat);
                String st = email.sendHtml(to.toArray(new String[0]), null, subject, mail.html(), mail.text(), null, false, null);
                status = st == null ? "FAILED: sonuç yok" : st;
            } catch (RuntimeException e) {
                status = "FAILED: " + clip(e.getClass().getSimpleName(), 120);
                log.warn("Teknik hata raporu hatırlatması gönderilemedi (takım {}): {}", team.getId(), e.toString());
            }
            delivered = status.startsWith("SENT") || status.startsWith("QUEUED");
        }
        int queued = 0;
        String pushNote = "";
        if (pushOn && push != null) {
            try {
                String p = TechErrorReminderMail.period(period.getYear(), period.getMonthValue(), false);
                String pEn = TechErrorReminderMail.period(period.getYear(), period.getMonthValue(), true);
                String msg = p + " teknik hata raporu " + mine.size() + " kanalda henüz gönderilmedi: " + names(mine)
                        + ". Raporları Site Monitor'de tamamlayıp gönderin.";
                String msgEn = "The " + pEn + " technical error report has not been sent yet for " + mine.size() + " channel(s): " + names(mine)
                        + ". Complete and send them in Site Monitor.";
                Map<String, Object> pr = push.enqueueTeamNoticeLocalized(team.getId(), TRIGGER, "WARNING", PUSH_MONITOR_TYPE,
                        team.getName(), new UserPushService.LocalizedText(msg, msgEn),
                        "te-remind:" + team.getId() + ":" + period + ":" + todayStr, Set.of());
                queued = pr.get("queued") instanceof Number n ? n.intValue() : 0;
                pushNote = queued > 0 ? " · push " + queued : " · push: " + (pr.get("reason") == null ? "alıcı yok" : pr.get("reason"));
            } catch (RuntimeException e) {
                pushNote = " · push: FAILED";
                log.warn("Teknik hata raporu hatırlatma push'u kuyruğa alınamadı (takım {}): {}", team.getId(), e.toString());
            }
        }
        if (queued > 0) counts[1]++;
        // SKIPPED (posta kapalı / adres yok) son karardır — tekrar penceresine kadar yeniden denenmez; posta BAŞARISIZ ve
        // push da ulaşmadıysa talep geri bırakılır (hatırlatma kaybolmasın: ertesi gün yeniden)
        boolean finalSkip = status.startsWith("SKIPPED");
        if (!delivered && queued == 0 && !finalSkip) {
            for (Claim c : claims) release(c);
        }
        if (delivered || queued > 0) counts[0]++;
        else counts[3]++;
        saveLog(TRIGGER, team.getName(), to, "TEAM", subject, status + pushNote, summary);
    }

    private static String langOf(Due d) {
        if (d.row() != null && d.row().getTemplateLang() != null) return d.row().getTemplateLang();
        try {
            return TechErrorTemplate.fromStored(d.channel().getTemplateJson()).lang();
        } catch (RuntimeException e) {
            return "tr";
        }
    }

    private static String names(List<Due> list) {
        List<String> n = new ArrayList<>();
        for (Due d : list) {
            if (n.size() == 5) { n.add("+" + (list.size() - 5)); break; }
            n.add(d.channel().getName());
        }
        return String.join(", ", n);
    }

    /** Günlük satırının ileti alanı: kanal ve durum (kimin neyi aldığı izlenebilsin). */
    private static String channelSummary(List<Due> list, boolean en) {
        StringBuilder sb = new StringBuilder();
        for (Due d : list) {
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(d.channel().getName()).append(" — ")
                    .append(TechErrorReminderMail.stateText(new TechErrorReminderMail.Item(d.channel().getName(), d.state(),
                            TechErrorApprovalService.approvalRequired(d.channel())), en));
        }
        return sb.toString();
    }

    // ── 2. Onay hatırlatması ─────────────────────────────────────────────────────────────────────────

    private void approvalReminders(Instant now, LocalDate today, boolean muted, String base, int[] counts) {
        int every = TechErrorReportSettings.effectiveInt(settings, TechErrorReportSettings.APPROVAL_REMINDER_DAYS);
        List<TechErrorReportRepository.Row> pending = reports.findByApprovalStateAndApprovalRequestedAtLessThanEqual("PENDING",
                now.minus(Duration.ofDays(every)));
        if (pending.isEmpty()) return;
        String todayStr = today.toString();
        Map<Long, TechErrorChannel> chMap = new HashMap<>();
        for (TechErrorChannel ch : channels.findAllById(pending.stream().map(TechErrorReportRepository.Row::getChannelId).distinct().toList())) {
            chMap.put(ch.getId(), ch);
        }
        Map<Long, Team> teamMap = new HashMap<>();
        for (Team t : teams.findAllById(pending.stream().map(TechErrorReportRepository.Row::getTeamId).distinct().toList())) {
            teamMap.put(t.getId(), t);
        }
        for (TechErrorReportRepository.Row r : pending) {
            long content = r.getContentRev() == null ? 0L : r.getContentRev();
            if (r.getApprovalRev() == null || r.getApprovalRev() != content || r.getApprovalRequestedAt() == null) continue;   // eskimiş istek
            TechErrorChannel ch = chMap.get(r.getChannelId());
            Team team = teamMap.get(r.getTeamId());
            if (ch == null || team == null || !ch.isActive()) continue;
            YearMonth p = YearMonth.of(r.getReportYear(), r.getReportMonth());
            String ref = String.valueOf(r.getApprovalRequestedAt().toEpochMilli());
            TechErrorReminder existing = reminders.findByChannelIdAndReportYearAndReportMonthAndKind(ch.getId(), p.getYear(),
                    p.getMonthValue(), TechErrorReminder.KIND_APPROVAL).orElse(null);
            boolean fresh = existing == null || !ref.equals(existing.getRefKey());
            if (!fresh && !due(existing, today, every)) continue;
            String lang = r.getTemplateLang() == null ? "tr" : r.getTemplateLang();
            boolean en = "en".equals(lang);
            String title = TechErrorTemplate.resolve(titlePattern(r, ch), ch.getName(), r.getReportYear(), r.getReportMonth(), lang);
            String subject = TechErrorReminderMail.approvalSubject(title, en);
            if (muted) {
                saveLog(TRIGGER_APPROVAL, team.getName(), List.of(), "PO", subject, "SKIPPED: sistem bakımı", title);
                counts[3]++;
                continue;
            }
            Claim c = claim(ch.getId(), p, TechErrorReminder.KIND_APPROVAL, existing, todayStr, ref);
            if (c == null) continue;
            try {
                if (!visibility.teamVisible(team.getId())) {
                    saveLog(TRIGGER_APPROVAL, team.getName(), List.of(), "PO", subject, "SKIPPED: modül bu takıma kapalı", title);
                    counts[3]++;
                    continue;
                }
                List<TechErrorApprovalService.Approver> apps = new ArrayList<>();
                for (TechErrorApprovalService.Approver a : approvals.approvers(team.getId())) if (a.email() != null) apps.add(a);
                if (apps.isEmpty()) {
                    saveLog(TRIGGER_APPROVAL, team.getName(), List.of(), "PO", subject, "SKIPPED: onaylayıcı e-postası yok", title);
                    counts[3]++;
                    continue;
                }
                Map<Long, Instant> linkUntil = new HashMap<>();
                try {
                    for (TechErrorApprovalToken t : tokens.findByReportIdOrderByIdAsc(r.getId())) {
                        if (t.getUsedAt() == null && t.getExpiresAt() != null && t.getExpiresAt().isAfter(now)
                                && java.util.Objects.equals(t.getApprovalRev(), r.getApprovalRev())) {
                            linkUntil.put(t.getUserId(), t.getExpiresAt());
                        }
                    }
                } catch (RuntimeException e) {
                    log.debug("Onay bağlantıları okunamadı (rapor {}): {}", r.getId(), e.toString());
                }
                String requester = service.displayName(r.getApprovalRequestedById());
                long waiting = Math.max(0, ChronoUnit.DAYS.between(r.getApprovalRequestedAt(), now));
                String url = TechErrorApprovalMail.reportUrl(base, ch.getId(), r.getId());
                int reached = 0, finalSkips = 0;
                for (TechErrorApprovalService.Approver a : apps) {
                    String status;
                    try {
                        MailDoc.Mail mail = TechErrorReminderMail.approval(title, en, a.name(), requester, r.getApprovalRequestedAt(), waiting,
                                linkUntil.get(a.id()), url, team.getName(), every);
                        String st = email.sendHtml(new String[] { a.email() }, null, subject, mail.html(), mail.text(), null, false, null);
                        status = st == null ? "FAILED: sonuç yok" : st;
                    } catch (RuntimeException e) {
                        status = "FAILED: " + clip(e.getClass().getSimpleName(), 120);
                        log.warn("Onay hatırlatması gönderilemedi (rapor {}): {}", r.getId(), e.toString());
                    }
                    if (status.startsWith("SENT") || status.startsWith("QUEUED")) reached++;
                    else if (status.startsWith("SKIPPED")) finalSkips++;   // posta kapalı / pasif: son karar, her gün denenmez
                    saveLog(TRIGGER_APPROVAL, a.name() + " · " + team.getName(), List.of(a.email()), "PO", subject, status, title);
                }
                if (reached > 0) counts[2]++;
                else {
                    counts[3]++;
                    if (finalSkips == 0) release(c);                       // hiçbir PO'ya ulaşmadı (FAILED) → ertesi gün yeniden
                }
            } catch (RuntimeException e) {
                // Beklenmeyen hata bir raporda kalır: talep bırakılır, iz FAILED — ertesi gün yeniden
                log.warn("Onay hatırlatması rapor {} için yarıda kaldı: {}", r.getId(), e.toString(), e);
                release(c);
                saveLog(TRIGGER_APPROVAL, team.getName(), List.of(), "PO", subject, "FAILED: " + clip(e.getClass().getSimpleName(), 120), title);
                counts[3]++;
            }
        }
    }

    private static String titlePattern(TechErrorReportRepository.Row r, TechErrorChannel ch) {
        if (r.getTitlePattern() != null) return r.getTitlePattern();
        try {
            return TechErrorTemplate.fromStored(ch.getTemplateJson()).title();
        } catch (RuntimeException e) {
            return "{kanal}";
        }
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private boolean muted() {
        try {
            return maintenance != null && maintenance.notificationsMuted();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String baseUrl() {
        try {
            String url = settings.getString("site.monitor.app.base-url", "");
            return url == null || url.isBlank() ? null : url.trim().replaceAll("/+$", "");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void saveLog(String trigger, String name, List<String> to, String role, String subject, String status, String message) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);   // alarm kaynaklı değil (kolon NOT NULL)
            n.setSentAt(ISO.format(Instant.now()));
            n.setRecipientName(name == null ? "" : name);           // varlık kolona sığdırır (NotificationLog.fitColumns)
            n.setRecipientEmail(String.join(", ", to == null ? List.of() : to));
            n.setRecipientRole(role);
            n.setSubject(clip(subject, 500));
            n.setMessage(clip(message, 4000));
            n.setEmailStatus(status);
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(trigger);
            n.setEmailFrom(email.fromAddress());
            logs.save(n);
        } catch (Exception e) {
            log.warn("Teknik hata raporu hatırlatma günlüğü yazılamadı: {}", e.getMessage());
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

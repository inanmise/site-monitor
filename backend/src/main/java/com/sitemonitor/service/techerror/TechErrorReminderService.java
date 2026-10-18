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
import com.sitemonitor.service.schedule.ClusterScheduleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kanal teknik hata raporu HATIRLATMALARI (2026-10-17, 12. sürüm — kullanıcı onayı C9 / C10: "ayın N. günü raporu
 * açılmamış / gönderilmemiş kanal için takım postası + push; takım başına açılır" ve "onay bekleyen rapor hatırlatması").
 * Her gün 09:15 (İstanbul) {@code scheduler_lock} {@value #LOCK} altında, küme genelinde GÜNDE BİR tur ({@link #ROUND} tur
 * kaydı — kilit tur bitince bırakıldığı için geç tetiklenen ikinci pod aynı günü yeniden koşturamaz); iki iş birden:
 *
 * <ol>
 *   <li><b>Dönem hatırlatması</b> — yalnız Ayarlar'da hatırlatmaya AÇIK takımlar (boş liste = herkes için KAPALI).
 *   Dönem = içinde bulunulan ayın bir öncesi (raporun kapsadığı ay); ayın {@code reminder-day}. gününden itibaren, rapor
 *   gönderilene kadar her {@code reminder-repeat-days} günde bir (0 = bir kez). Dönem iki ay eskiyince (yeni ay
 *   başlayınca) hatırlatılmaz; dönem BİTTİKTEN sonra açılan kanal o dönem için hatırlatılmaz. "Gönderilmedi" = dönem raporu
 *   YOK ya da hiç gönderilmedi ({@code last_sent_at} boş); gönderilip sonra değişen rapor hatırlatılmaz, şu an gönderilen
 *   ve sonucu hâlâ gelebilecek kuyruktaki rapor da ({@link TechErrorReportSender#QUEUED_RESULT_WINDOW}); sonucu artık
 *   gelemeyecek kuyruk "gönderim sonucu alınamadı" cümlesiyle listededir (koşu başında kuyruk sonuçlandırılır). Onay
 *   bekleyen rapor "PO onayı bekliyor" cümlesiyle durur. Takım başına TEK posta (kanallar tabloda) + istenirse takım push'u.</li>
 *   <li><b>Onay hatırlatması</b> — bekleyen ve eskimemiş ({@code approval_rev = COALESCE(content_rev, 0)}) onay isteği
 *   {@code approval-reminder-days} gündür karar beklemişse onaylayıcılara kısa posta (yeni bağlantı ÜRETİLMEZ; özgün
 *   bağlantı yalnız o PO'ya gerçekten ulaştıysa — {@code mailed_at} — ve hâlâ geçerliyse anılır); rapor × istek başına en
 *   çok o kadar günde bir. Pasif takım, onay akışı kaldırılmış kanal ve modülün kapalı olduğu takım hatırlatılmaz.</li>
 * </ol>
 *
 * <p><b>Tam bir kez:</b> hatırlatma gönderilmeden ÖNCE {@code tech_error_reminders} satırı koşullu sahiplenilir (kanal ×
 * dönem × tür) — iki pod aynı günü iki kez göndermez. Hiçbir kanaldan ulaşılamayan hatırlatmanın (posta FAILED, push
 * yok) talebi geri bırakılır; ertesi gün yeniden denenir. <b>Kapılar talepten ÖNCE</b> (inceleme 2026-10-18): modül
 * takıma kapalı / pasif takım / onay akışı yok reddi talebi HARCAMAZ — kapı satırı ({@code SKIP:…} / {@code ASKIP:…})
 * SKIPPED günlüğünü kanal × dönem × sebep başına bir kez yazdırır, kapı açılınca hatırlatma normal gider. <b>Her karar</b>
 * bir {@code notification_logs} satırı (tetikleyici {@value #TRIGGER} / {@value #TRIGGER_APPROVAL}): gönderildi /
 * başarısız / takım adresi yok / modül takıma kapalı / pasif takım / onay akışı kaldırıldı / onaylayıcı adresi yok /
 * sistem bakımı (bakım susturmasında talep YAPILMAZ — ertesi gün yeniden denenir). Pasif kullanıcı adresleri posta
 * hunisinde düşer; push pasif hesaplara gitmez. Bilinçli sınır: talep ile posta arasında ölen pod o günün hatırlatmasını
 * kaybeder (en çok bir kez, çift posta yok).
 */
@Slf4j
@Service
public class TechErrorReminderService {

    public static final String TRIGGER = "TECH_ERROR_REMINDER";
    public static final String TRIGGER_APPROVAL = "TECH_ERROR_APPROVAL_REMINDER";
    public static final String PUSH_MONITOR_TYPE = "TECH_ERROR_REPORT";
    static final String LOCK = "tech-error-reminder";
    /** Küme geneli günlük tur kaydı ({@code monitor_check_schedule}; vade = ertesi İstanbul gece yarısı, satır bir tane). */
    static final String ROUND = ClusterScheduleService.ROUND_PREFIX + LOCK;
    static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** Bu kadar aydır GÜNCELLENMEMİŞ talep / kapı satırları her koşuda silinir (satır yaşı — dönem yaşı değil). */
    static final int KEEP_MONTHS = 6;

    /** Kapı satırı türleri (≤ 20 karakter): dönem hatırlatması. */
    static final String SKIP_HIDDEN = "SKIP:HIDDEN";
    static final String SKIP_PASSIVE = "SKIP:PASSIVE";
    /** Kapı satırı türleri: onay hatırlatması. */
    static final String ASKIP_HIDDEN = "ASKIP:HIDDEN";
    static final String ASKIP_PASSIVE = "ASKIP:PASSIVE";
    static final String ASKIP_NOFLOW = "ASKIP:NOFLOW";

    static final String ST_HIDDEN = "SKIPPED: modül bu takıma kapalı";
    static final String ST_PASSIVE = "SKIPPED: pasif takım";
    static final String ST_NOFLOW = "SKIPPED: kanalın onay akışı kaldırıldı";
    /** Sistem bakımı susturması (genel iz metni — {@link TechErrorSendLogService#reasonOf} bu sabitle eşler). */
    static final String ST_MAINTENANCE = SystemMaintenanceService.STATUS_SKIPPED;
    /** Onay hatırlatması: takımın hiçbir PO'sunun e-postası yok ({@link TechErrorSendLogService#reasonOf} → NO_RECIPIENT). */
    static final String ST_NO_APPROVER_EMAIL = "SKIPPED: onaylayıcı e-postası yok";

    private static final List<String> PERIOD_KINDS = List.of(TechErrorReminder.KIND_PERIOD, SKIP_HIDDEN, SKIP_PASSIVE);
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
    @Autowired(required = false) @Lazy private ClusterScheduleService clusterSchedule;

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
    void setClusterSchedule(ClusterScheduleService c) { this.clusterSchedule = c; }

    /**
     * Koşu özeti: dönem hatırlatmasında posta / push alan takım sayısı, onay hatırlatmasında posta alan rapor sayısı,
     * atlanan (günlüğe SKIPPED yazılan) karar sayısı.
     */
    public record Result(int teamsReminded, int pushTeams, int approvalReports, int skipped) {
        static final Result NONE = new Result(0, 0, 0, 0);
    }

    @Scheduled(cron = "${site.monitor.tech-error-report.reminder-cron:0 15 9 * * *}", zone = "Europe/Istanbul")
    public void scheduled() {
        if (!enabled) return;
        try {
            if (scheduler == null) runRound();
            else scheduler.callWithSchedulerLock(LOCK, this::runRound);
        } catch (Exception e) {
            log.error("Teknik hata raporu hatırlatması başarısız: {}", e.getMessage(), e);
        }
    }

    /**
     * Zamanlanmış tur: bugünün turu küme genelinde henüz koşmadıysa {@link #run}. Kilit yalnız çakışmayı önler ve tur
     * bitince bırakılır; tur kaydı bırakılmaz → geç tetiklenen (dolu zamanlayıcı havuzu, saat kayması) ikinci pod aynı günü
     * yeniden koşturamaz (talep satırları çift postayı zaten önler; bu, günlük kapı / budama / sorgu işini de bire indirir).
     */
    public Result runRound() {
        if (!roundDue(service.now())) {
            log.debug("Teknik hata raporu hatırlatması: bugünün turu başka bir pod'da koştu");
            return Result.NONE;
        }
        return run();
    }

    /**
     * Günlük tur kaydı: satır yoksa ya da vadesi (bir sonraki İstanbul gece yarısı) geldiyse bu pod sahiplenir. Bean yoksa
     * (dilim testleri / manuel kurulum) ya da kayıt okunamazsa tur koşar — talep satırları yine en çok bir kez gönderir.
     */
    boolean roundDue(Instant now) {
        if (clusterSchedule == null) return true;
        long nowMs = now.toEpochMilli();
        long nextDay = LocalDate.ofInstant(now, TechErrorReportService.ZONE).plusDays(1)
                .atStartOfDay(TechErrorReportService.ZONE).toInstant().toEpochMilli();
        try {
            return clusterSchedule.claim(ROUND, nowMs, DAY_MS, null, prev -> nextDay).due();
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu hatırlatma tur kaydı okunamadı; tur kayıtsız koşuyor (talepler çift postayı önler): {}",
                    e.toString());
            return true;
        }
    }

    /** Hatırlatma koşusu (tur kapısı yok — testler ve tur içinden). */
    public Result run() {
        Instant now = service.now();
        LocalDate today = LocalDate.ofInstant(now, TechErrorReportService.ZONE);
        int[] counts = new int[4];
        try {
            Instant cut = now.atZone(TechErrorReportService.ZONE).minusMonths(KEEP_MONTHS).toInstant();
            reminders.deleteNotUpdatedSince(ISO.format(cut));
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
        String now = ISO.format(service.now());
        if (existing == null) {
            TechErrorReminder saved = insert(channelId, p, kind, today, ref, now);
            return saved == null ? null : new Claim(saved.getId(), saved.getClaimRev(), null, null);
        }
        if (reminders.claim(existing.getId(), existing.getClaimRev(), today, ref, now) != 1) return null;
        return new Claim(existing.getId(), existing.getClaimRev() + 1, existing.getLastSentOn(), existing.getRefKey());
    }

    /** İlk satır (talep ya da kapı satırı); başka pod önce eklediyse null. */
    private TechErrorReminder insert(Long channelId, YearMonth p, String kind, String today, String ref, String now) {
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
            return reminders.saveAndFlush(row);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            log.debug("Teknik hata raporu hatırlatması başka pod'da sahiplenildi: kanal {} {} {}", channelId, p, kind);
            return null;
        }
    }

    void release(Claim c) {
        if (c == null) return;
        try {
            reminders.release(c.id(), c.rev(), c.prevDay(), c.prevRef(), ISO.format(service.now()));
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

    /**
     * Onay isteği İLK hatırlatma için vadeli mi: isteğin İstanbul günü + {@code everyDays} ≤ bugün (tekrarlarla aynı gün
     * kuralı — saat değil). İstek zamanı yoksa hayır.
     */
    static boolean firstDue(Instant requestedAt, LocalDate today, int everyDays) {
        if (requestedAt == null) return false;
        LocalDate asked = LocalDate.ofInstant(requestedAt, TechErrorReportService.ZONE);
        return !asked.plusDays(Math.max(0, everyDays)).isAfter(today);
    }

    /**
     * Kapı reddi: kanalların henüz bu sebeple işaretlenmemiş olanlarına kapı satırı yazar; en az biri YENİYSE bir SKIPPED
     * günlük satırı (o kanallarla). Aynı sebep ertesi gün yeni satır yazdırmaz; normal talep satırına dokunulmaz.
     *
     * @return yeni işaretlenen kanallar (boş = bu sebep zaten günlükteydi)
     */
    List<TechErrorChannel> markSkipped(List<TechErrorChannel> chs, YearMonth p, String kind, Map<Long, Set<String>> known,
                                       String today) {
        String now = ISO.format(service.now());
        List<TechErrorChannel> fresh = new ArrayList<>();
        for (TechErrorChannel ch : chs) {
            Set<String> kinds = known == null ? null : known.get(ch.getId());
            if (kinds != null && kinds.contains(kind)) continue;
            if (known == null && reminders.findByChannelIdAndReportYearAndReportMonthAndKind(ch.getId(), p.getYear(),
                    p.getMonthValue(), kind).isPresent()) continue;
            if (insert(ch.getId(), p, kind, today, "", now) != null) fresh.add(ch);
        }
        return fresh;
    }

    // ── 1. Dönem hatırlatması ────────────────────────────────────────────────────────────────────────

    /**
     * Kanalın hatırlatılacak durumu; null = hatırlatılmaz (gönderildi, şu an gönderiliyor ya da kuyruktaki gönderimin
     * sonucu hâlâ gelebilir). Sonucu artık gelemeyecek kuyruk ({@link TechErrorReportSender#QUEUED_RESULT_WINDOW} geçti; 421
     * yeniden denemeleri tek pod'un belleğinde yaşar) "gönderim sonucu alınamadı" — gönderilmemiş sayılır.
     *
     * <p>{@code approvalFlow} = kanalın BUGÜN onay akışı var mı ({@link TechErrorApprovalService#approvalRequired}; inceleme
     * 2026-10-18): yönetici alıcıları kaldırılmış kanalda raporun eski onay durumu (bekliyor / reddedildi / onaylandı) bir
     * anlam taşımaz — ekip raporu takım adresine doğrudan gönderebilir; durum TASLAK ("gönderilmedi") sayılır. Aksi hâlde
     * takım postası "PO onayı bekliyor, sizin işiniz yok" derken PO hatırlatması da akış olmadığı için atlanırdı.
     */
    static TechErrorReminderMail.State stateOf(TechErrorReportRepository.Row r, Instant now, boolean approvalFlow) {
        if (r == null) return TechErrorReminderMail.State.MISSING;
        if (r.getLastSentAt() != null) return null;
        if ("QUEUED".equals(r.getSendState())) {
            boolean mayLand = r.getSendClaimedAt() != null
                    && r.getSendClaimedAt().isAfter(now.minus(TechErrorReportSender.QUEUED_RESULT_WINDOW));
            return mayLand ? null : TechErrorReminderMail.State.QUEUED_UNKNOWN;
        }
        if ("SENDING".equals(r.getSendState()) && r.getSendClaimedAt() != null
                && r.getSendClaimedAt().isAfter(now.minus(TechErrorReportSender.STALE_CLAIM))) return null;
        if (!approvalFlow) return TechErrorReminderMail.State.DRAFT;     // akış yok: onay durumu geçersiz, doğrudan gönderilir
        String a = r.getApprovalState();
        long content = r.getContentRev() == null ? 0L : r.getContentRev();
        boolean stale = r.getApprovalRev() != null && content > r.getApprovalRev();
        if ("PENDING".equals(a)) return stale ? TechErrorReminderMail.State.STALE : TechErrorReminderMail.State.PENDING;
        if ("REJECTED".equals(a)) return TechErrorReminderMail.State.REJECTED;
        if ("APPROVED".equals(a)) return stale ? TechErrorReminderMail.State.STALE : TechErrorReminderMail.State.APPROVED_UNSENT;
        return TechErrorReminderMail.State.DRAFT;
    }

    /** Dönemin bitişi (İstanbul): izleyen ayın ilk günü 00:00 — bundan sonra açılan kanal o dönem için hatırlatılmaz. */
    static Instant periodEnd(YearMonth period) {
        return period.plusMonths(1).atDay(1).atStartOfDay(TechErrorReportService.ZONE).toInstant();
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
        Instant end = periodEnd(period);

        List<TechErrorChannel> chs = new ArrayList<>();
        for (TechErrorChannel ch : service.channels(optIn)) {
            if (!ch.isActive()) continue;
            // Dönem bittikten SONRA açılan kanal o dönemin raporunu beklemez (inceleme 2026-10-18: "rapor açılmadı" yanlış)
            if (ch.getCreatedAt() != null && !ch.getCreatedAt().isBefore(end)) continue;
            chs.add(ch);
        }
        if (chs.isEmpty()) return;
        List<Long> ids = chs.stream().map(TechErrorChannel::getId).toList();
        // Kuyruktaki gönderimler önce sonuçlandırılır (posta servisinin geri yazdığı SENT / FAILED) — okuma uçlarıyla aynı
        try {
            service.reconcileQueued(ids);
        } catch (RuntimeException e) {
            log.debug("Teknik hata raporu kuyruk sonuçları okunamadı (hatırlatma): {}", e.toString());
        }
        Map<Long, TechErrorReportRepository.Row> rows = service.periodRows(ids, period);
        Map<Long, TechErrorReminder> claimRows = new HashMap<>();
        Map<Long, Set<String>> gateRows = new HashMap<>();
        for (TechErrorReminder r : reminders.findByKindInAndReportYearAndReportMonthAndChannelIdIn(PERIOD_KINDS,
                period.getYear(), period.getMonthValue(), ids)) {
            if (TechErrorReminder.KIND_PERIOD.equals(r.getKind())) claimRows.put(r.getChannelId(), r);
            else gateRows.computeIfAbsent(r.getChannelId(), k -> new HashSet<>()).add(r.getKind());
        }
        Map<Long, List<Due>> byTeam = new LinkedHashMap<>();
        for (TechErrorChannel ch : chs) {
            TechErrorReportRepository.Row row = rows.get(ch.getId());
            TechErrorReminderMail.State st = stateOf(row, now, TechErrorApprovalService.approvalRequired(ch));
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
            String subject = TechErrorReminderMail.teamSubject(period.getYear(), period.getMonthValue(), enTpl, team.getName());
            // Takım adresi en çok 20 alıcıya gider (rapor gönderimiyle aynı sınır); fazlası SESSİZ düşmez — günlük satırının
            // iletisi "+N adres sınır dışında" der ve WARN yazılır (inceleme 2026-10-18)
            TechErrorReportSender.Addresses addr = TechErrorReportSender.parseAddressesDetailed(team.getEmail());
            List<String> to = addr.addresses();
            if (addr.dropped() > 0) {
                log.warn("Teknik hata raporu hatırlatması: takım {} adresinde {} alıcı {} sınırının dışında kaldı", team.getId(),
                        addr.dropped(), TechErrorReportSender.MAX_RECIPIENTS);
            }
            String overflow = overflowNote(addr.dropped());
            // Kapılar TALEPTEN ÖNCE (inceleme 2026-10-18): red talebi harcamaz; sebebin günlüğü kanal × dönem başına bir kez
            String gate;
            try {
                gate = periodGate(team);
            } catch (RuntimeException e) {
                log.warn("Teknik hata raporu hatırlatması takım {} için yarıda kaldı: {}", team.getId(), e.toString(), e);
                saveLog(TRIGGER, team.getName(), to, "TEAM", subject, "FAILED: " + clip(e.getClass().getSimpleName(), 120), "");
                counts[3]++;
                continue;                                              // talep YOK: ertesi gün yeniden
            }
            if (gate != null) {
                List<TechErrorChannel> marked = markSkipped(en.getValue().stream().map(Due::channel).toList(), period, gate,
                        gateRows, todayStr);
                if (!marked.isEmpty()) {
                    List<Due> listed = en.getValue().stream().filter(d -> marked.contains(d.channel())).toList();
                    saveLog(TRIGGER, team.getName(), to, "TEAM", subject, SKIP_HIDDEN.equals(gate) ? ST_HIDDEN : ST_PASSIVE,
                            channelSummary(listed, enTpl));
                    counts[3]++;
                }
                continue;
            }
            if (muted) {
                saveLog(TRIGGER, team.getName(), to, "TEAM", subject, ST_MAINTENANCE, channelSummary(en.getValue(), enTpl));
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
                remindTeam(team, period, mine, claims, enTpl, subject, to, overflow, base, day, repeat, pushOn, todayStr, counts);
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

    /** Dönem hatırlatmasının takım kapısı: null = açık; aksi hâlde kapı satırı türü. */
    private String periodGate(Team team) {
        if (!visibility.teamVisible(team.getId())) return SKIP_HIDDEN;
        if (Boolean.FALSE.equals(team.getActive())) return SKIP_PASSIVE;
        return null;
    }

    /**
     * Sahiplenilmiş kanallarla bir takımın hatırlatması: posta, push, günlük. Günlük satırının {@code email_status}'ü YALNIZ
     * posta servisinin cevabıdır (inceleme 2026-10-18: "SENT · push 2" SMTP günlüğünde SENT değil UNKNOWN sayılıyor,
     * SENT sayaçları / kendi kendini izleme onu kaçırıyordu); push notu ve sınır dışı adres notu {@code message}'a yazılır.
     */
    private void remindTeam(Team team, YearMonth period, List<Due> mine, List<Claim> claims, boolean enTpl, String subject,
                            List<String> to, String overflow, String base, int day, int repeat, boolean pushOn, String todayStr,
                            int[] counts) {
        String summary = channelSummary(mine, enTpl);
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
                pushNote = pushNote(pr, queued);
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
        saveLog(TRIGGER, team.getName(), to, "TEAM", subject, status, withNote(summary, pushNote + overflow));
    }

    /** Sınırın (20) dışında kalan takım adresi notu ({@code message}); yoksa boş. */
    static String overflowNote(int dropped) {
        return dropped <= 0 ? "" : " · +" + dropped + " adres sınır dışında (en çok " + TechErrorReportSender.MAX_RECIPIENTS + " alıcı)";
    }

    /** İleti + not: not ASLA kırpılmaz (ileti 4000 karaktere sığdırılırken önce özet kısalır). */
    static String withNote(String message, String note) {
        String m = message == null ? "" : message;
        String n = note == null ? "" : note;
        if (n.isEmpty()) return m;
        int room = Math.max(0, 4000 - n.length());
        return (m.length() <= room ? m : m.substring(0, room)) + n;
    }

    /**
     * Push'un günlük notu: kuyruğa alınan sayısı; yoksa GERÇEK sebep — kanal kapalı / takım yok / alıcı yok (servisin
     * {@code reason}'ı) ya da alıcılar vardı ama hepsi atlandı (kişisel tercih, saat sınırı, tekrar — {@code skipped} sayısı).
     */
    static String pushNote(Map<String, Object> pr, int queued) {
        if (queued > 0) return " · push " + queued;
        Object reason = pr == null ? null : pr.get("reason");
        if (reason != null) return " · push: " + reason;
        int skipped = pr != null && pr.get("skipped") instanceof Number n ? n.intValue() : 0;
        if (skipped > 0) return " · push: " + skipped + " alıcı atlandı (tercih / sınır / tekrar)";
        return " · push: alıcı yok";
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
        // İLK hatırlatma da İSTANBUL GÜNLERİYLE (inceleme 2026-10-18): istek günü + N gün ≤ bugün — tekrarlar da günle sayılır
        // ({@link #due}). Eski kural "now − N×24 saat" 09:15'ten SONRA yapılan isteği bir gün geç hatırlatıyordu. Sorgu üst
        // sınırı = bugünden N−1 gün önceki günün başı (o güne kadar istenenler adaydır); kesin karar {@link #firstDue}.
        Instant cutoff = today.minusDays(Math.max(0, every - 1L)).atStartOfDay(TechErrorReportService.ZONE).toInstant();
        List<TechErrorReportRepository.Row> pending = new ArrayList<>();
        for (TechErrorReportRepository.Row r : reports.findByApprovalStateAndApprovalRequestedAtLessThanEqual("PENDING", cutoff)) {
            if (firstDue(r.getApprovalRequestedAt(), today, every)) pending.add(r);
        }
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
            String lang = r.getTemplateLang() == null ? "tr" : r.getTemplateLang();
            boolean en = "en".equals(lang);
            String title = TechErrorTemplate.resolve(titlePattern(r, ch), ch.getName(), r.getReportYear(), r.getReportMonth(), lang);
            // Konu rapora özgü (yeniden deneme sonucu konuyla eşlenir): özel başlık kanalı / dönemi taşımıyorsa eklenir
            String subject = TechErrorReminderMail.approvalSubject(TechErrorReminderMail.uniqueTitle(title, ch.getName(),
                    TechErrorReminderMail.period(r.getReportYear(), r.getReportMonth(), en)), en);
            // Kapılar TALEPTEN ÖNCE (inceleme 2026-10-18): pasif takım, kaldırılmış onay akışı, modülün kapalı olduğu takım
            String gate;
            try {
                gate = approvalGate(ch, team);
            } catch (RuntimeException e) {
                log.warn("Onay hatırlatması rapor {} için yarıda kaldı: {}", r.getId(), e.toString(), e);
                saveLogFor(r.getChannelId(), r.getId(), TRIGGER_APPROVAL, team.getName(), List.of(), "PO", subject, "FAILED: " + clip(e.getClass().getSimpleName(), 120), title);
                counts[3]++;
                continue;
            }
            if (gate != null) {
                if (!markSkipped(List.of(ch), p, gate, null, todayStr).isEmpty()) {
                    saveLogFor(r.getChannelId(), r.getId(), TRIGGER_APPROVAL, team.getName(), List.of(), "PO", subject, approvalGateStatus(gate), title);
                    counts[3]++;
                }
                continue;
            }
            TechErrorReminder existing = reminders.findByChannelIdAndReportYearAndReportMonthAndKind(ch.getId(), p.getYear(),
                    p.getMonthValue(), TechErrorReminder.KIND_APPROVAL).orElse(null);
            boolean fresh = existing == null || !ref.equals(existing.getRefKey());
            if (!fresh && !due(existing, today, every)) continue;
            if (muted) {
                saveLogFor(r.getChannelId(), r.getId(), TRIGGER_APPROVAL, team.getName(), List.of(), "PO", subject, ST_MAINTENANCE, title);
                counts[3]++;
                continue;
            }
            Claim c = claim(ch.getId(), p, TechErrorReminder.KIND_APPROVAL, existing, todayStr, ref);
            if (c == null) continue;
            try {
                List<TechErrorApprovalService.Approver> apps = new ArrayList<>();
                for (TechErrorApprovalService.Approver a : approvals.approvers(team.getId())) if (a.email() != null) apps.add(a);
                if (apps.isEmpty()) {
                    saveLogFor(r.getChannelId(), r.getId(), TRIGGER_APPROVAL, team.getName(), List.of(), "PO", subject, ST_NO_APPROVER_EMAIL, title);
                    counts[3]++;
                    continue;
                }
                Links links = links(r, now);
                Map<Long, Instant> linkUntil = links.live();
                String requester = service.displayName(r.getApprovalRequestedById());
                long waiting = Math.max(0, ChronoUnit.DAYS.between(r.getApprovalRequestedAt(), now));
                String url = TechErrorApprovalMail.reportUrl(base, ch.getId(), r.getId());
                int reached = 0, finalSkips = 0;
                for (TechErrorApprovalService.Approver a : apps) {
                    String status;
                    try {
                        MailDoc.Mail mail = TechErrorReminderMail.approval(title, en, a.name(), requester, r.getApprovalRequestedAt(), waiting,
                                linkUntil.get(a.id()), links.unconfirmed().contains(a.id()), url, team.getName(), every);
                        String st = email.sendHtml(new String[] { a.email() }, null, subject, mail.html(), mail.text(), null, false, null);
                        status = st == null ? "FAILED: sonuç yok" : st;
                    } catch (RuntimeException e) {
                        status = "FAILED: " + clip(e.getClass().getSimpleName(), 120);
                        log.warn("Onay hatırlatması gönderilemedi (rapor {}): {}", r.getId(), e.toString());
                    }
                    if (status.startsWith("SENT") || status.startsWith("QUEUED")) reached++;
                    else if (status.startsWith("SKIPPED")) finalSkips++;   // posta kapalı / pasif: son karar, her gün denenmez
                    saveLogFor(r.getChannelId(), r.getId(), TRIGGER_APPROVAL, a.name() + " · " + team.getName(), List.of(a.email()), "PO", subject, status, title);
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
                saveLogFor(r.getChannelId(), r.getId(), TRIGGER_APPROVAL, team.getName(), List.of(), "PO", subject, "FAILED: " + clip(e.getClass().getSimpleName(), 120), title);
                counts[3]++;
            }
        }
    }

    /** Onay hatırlatmasının kapısı: null = açık; aksi hâlde kapı satırı türü. */
    private String approvalGate(TechErrorChannel ch, Team team) {
        if (!TechErrorApprovalService.approvalRequired(ch)) return ASKIP_NOFLOW;   // yönetici alıcıları kaldırıldı: onay gerekmez
        if (Boolean.FALSE.equals(team.getActive())) return ASKIP_PASSIVE;
        if (!visibility.teamVisible(team.getId())) return ASKIP_HIDDEN;
        return null;
    }

    private static String approvalGateStatus(String gate) {
        return switch (gate) {
            case ASKIP_NOFLOW -> ST_NOFLOW;
            case ASKIP_PASSIVE -> ST_PASSIVE;
            default -> ST_HIDDEN;
        };
    }

    /**
     * Bağlantı durumu: {@code live} = onaylayıcı → özgün bağlantısının bitişi, YALNIZ canlı bağlantılar: kullanılmamış, yerine
     * geçilmemiş, süresi dolmamış, bu isteğin ({@code approval_rev}) ve bağlantılı postası o PO'ya gerçekten ulaşmış
     * ({@code mailed_at}) — PDF sırası dolu / posta kapalı / başarısız / uygulama adresi boşken bağlantı ona gitmedi,
     * hatırlatma "bağlantınız geçerli" demez. {@code unconfirmed} = geçerli ama damgasız ve damgadan ({@link #MAILED_STAMP_SINCE})
     * ÖNCE üretilmiş belirteçlerin sahipleri: postanın ulaşıp ulaşmadığı bilinmez — hatırlatma "süresi doldu / size
     * gönderilmedi" diyemez, yalnız "Site Monitor'de açın" der (inceleme 2026-10-18).
     */
    record Links(Map<Long, Instant> live, Set<Long> unconfirmed) { }

    /**
     * {@code mailed_at} damgası 20.153.0 ile geldi (yayın 2026-10-18T00:32Z); dağıtım payıyla bu andan ÖNCE üretilen
     * belirteçte damganın boş olması "posta ulaşmadı" demek değildir. Sonrakilerde boş damga gerçekten ulaşmadı demektir
     * (eski metin doğru). Belirteç ömrü 14 gün: 2026-11-02'den sonra bu ayrım kendiliğinden düşer.
     */
    static final Instant MAILED_STAMP_SINCE = Instant.parse("2026-10-19T00:00:00Z");

    Links links(TechErrorReportRepository.Row r, Instant now) {
        Map<Long, Instant> live = new HashMap<>();
        Set<Long> unconfirmed = new HashSet<>();
        try {
            for (TechErrorApprovalToken t : tokens.findByReportIdOrderByIdAsc(r.getId())) {
                boolean valid = t.getUsedAt() == null && t.getSupersededAt() == null && t.getExpiresAt() != null && t.getExpiresAt().isAfter(now)
                        && java.util.Objects.equals(t.getApprovalRev(), r.getApprovalRev());
                if (!valid) continue;
                if (t.getMailedAt() != null) live.put(t.getUserId(), t.getExpiresAt());
                else if (t.getCreatedAt() == null || t.getCreatedAt().isBefore(MAILED_STAMP_SINCE)) unconfirmed.add(t.getUserId());
            }
        } catch (RuntimeException e) {
            log.debug("Onay bağlantıları okunamadı (rapor {}): {}", r.getId(), e.toString());
        }
        unconfirmed.removeAll(live.keySet());
        return new Links(live, unconfirmed);
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
        saveLogFor(null, null, trigger, name, to, role, subject, status, message);
    }

    /**
     * Günlük satırı; rapora özgü satır (onay hatırlatması) kanal + rapora bağlanır ({@link TechErrorSendLogService#link}) —
     * kanal gönderim günlüğünün "Hatırlatma" türü onu gösterir. Takım düzeyindeki dönem hatırlatması birden çok kanalı
     * kapsadığından bağsız kalır (SMTP günlüğünde görünür).
     */
    private void saveLogFor(Long channelId, Long reportId, String trigger, String name, List<String> to, String role, String subject,
                            String status, String message) {
        try {
            NotificationLog n = new NotificationLog();
            if (channelId != null) TechErrorSendLogService.link(n, channelId, reportId);
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

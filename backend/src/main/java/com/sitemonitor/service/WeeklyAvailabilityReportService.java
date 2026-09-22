package com.sitemonitor.service;

import com.sitemonitor.model.*;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.EmailNotificationService.AvailabilityRow;
import com.sitemonitor.service.EmailNotificationService.AvailabilitySummary;
import com.sitemonitor.service.report.WeeklyOutageReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.*;

/**
 * Haftalık erişilebilirlik (availability) e-postası — sertifika sahibi (CertificateInventory.team_id)
 * her AKTİF takıma, sahip olduğu domainlerin GEÇEN TAM HAFTAYA (Pzt 00:00 – Paz 23:59:59, Europe/Istanbul)
 * ait erişilebilirlik özetini executive bir mailde gönderir. Kesinti olsun olmasın gönderilir;
 * kesinti yaşayanlar ayrıca vurgulanır. Tetikleme + HA lock {@link SchedulerService}'tedir; bu servis
 * saf hesap + mail oluşturma/gönderme yapar (test edilebilir). Idempotency: weekly_availability_log.
 */
@Slf4j
@Service
public class WeeklyAvailabilityReportService {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String[] MONTHS_TR = {"Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
            "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"};

    private final TeamRepository teamRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final UptimeCheckRepository uptimeCheckRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final EscalationContactRepository contactRepo;
    private final AppUserRepository userRepo;
    private final EmailNotificationService emailService;
    private final NotificationLogRepository notificationLogRepo;
    private final WeeklyAvailabilityLogRepository walRepo;
    private final AppSettingsService appSettings;
    private final WeeklyOutageReportService outageReportService;
    /** Sayfa Hızı bölümü (K10) — SERVİS değil DEPO enjekte edilir. Bu sınıfa bir SERVİS eklemek
     *  dairesel referans üretiyor ve uygulama hiç açılmıyor; depolar o zincire girmez. */
    private final com.sitemonitor.repository.PageSpeedMonitorRepository pageSpeedMonitorRepo;
    private final com.sitemonitor.repository.PageSpeedCheckRepository pageSpeedCheckRepo;
    /** Alan adı bitişleri bölümü (2026-09-22, madde G) — DEPO enjeksiyonu, isteğe bağlı (yapıcı büyümez, eski test kurulumları kırılmaz). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.DomainMonitorRepository domainMonitorRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.DomainCheckRepository domainCheckRepo;
    /** "Sürüm & Dağıtım" satırı (E2). @Lazy — aşağıdaki yapıcı notuna bak. */
    private final DeploymentHistoryService deploymentHistory;

    /**
     * Yapıcı ELLE yazıldı ({@code @RequiredArgsConstructor} değil): {@code deploymentHistory}
     * parametresi {@code @Lazy} olmak zorunda ve Lombok bu ek açıklamayı yapıcı parametresine
     * KOPYALAMAZ. Bu sınıfa bir SERVİS eklemek daha önce dairesel referans üretip uygulamayı hiç
     * açılmaz hale getirdi (WeeklyOutageReportService'teki aynı desen); {@code @Lazy} proxy'si
     * zinciri kurulum anında keser, ilk çağrıda gerçek bean çözülür.
     */
    public WeeklyAvailabilityReportService(
            TeamRepository teamRepo,
            CertificateInventoryRepository inventoryRepo,
            UptimeCheckRepository uptimeCheckRepo,
            LatestCheckRepository latestCheckRepo,
            EscalationContactRepository contactRepo,
            AppUserRepository userRepo,
            EmailNotificationService emailService,
            NotificationLogRepository notificationLogRepo,
            WeeklyAvailabilityLogRepository walRepo,
            AppSettingsService appSettings,
            WeeklyOutageReportService outageReportService,
            com.sitemonitor.repository.PageSpeedMonitorRepository pageSpeedMonitorRepo,
            com.sitemonitor.repository.PageSpeedCheckRepository pageSpeedCheckRepo,
            @org.springframework.context.annotation.Lazy DeploymentHistoryService deploymentHistory) {
        this.teamRepo = teamRepo;
        this.inventoryRepo = inventoryRepo;
        this.uptimeCheckRepo = uptimeCheckRepo;
        this.latestCheckRepo = latestCheckRepo;
        this.contactRepo = contactRepo;
        this.userRepo = userRepo;
        this.emailService = emailService;
        this.notificationLogRepo = notificationLogRepo;
        this.walRepo = walRepo;
        this.appSettings = appSettings;
        this.outageReportService = outageReportService;
        this.pageSpeedMonitorRepo = pageSpeedMonitorRepo;
        this.pageSpeedCheckRepo = pageSpeedCheckRepo;
        this.deploymentHistory = deploymentHistory;
    }

    @Value("${site.monitor.weekly-availability.enabled:true}")
    private boolean enabledDefault;

    @Value("${site.monitor.weekly-availability.cron:0 0 10 ? * MON}")
    private String cronExpr;

    private static final String ENABLED_KEY = "site.monitor.weekly-availability.enabled";

    public record SendResult(int teams, int sent, int skippedNoDomains, int skippedNoRecipient, int skippedDone,
                             int skippedDisabled) {}

    /** Geçen tam ISO hafta penceresi (Europe/Istanbul → UTC ISO sınırlar). */
    public record Window(String fromUtc, String toUtc, Instant windowEnd, int year, int week, String weekLabel) {}

    /**
     * Tek takımlık rapor (gönderilmeden önce hazır): satırlar, özet, konu, HTML, çözülmüş alıcılar
     * ve kesinti eki verisi.
     *
     * <p>{@code outage} burada taşınıyor çünkü gövdedeki "ek" bandının ekin İÇERİĞİNİ (kaç alarm,
     * kaçı açık) yazması gerekiyor; yani veri HTML'den ÖNCE toplanmak zorunda. Aynı veri sonra
     * PDF'i çizmek için yeniden kullanılıyor — iki kez toplansaydı bütün alarm sorguları tek podda
     * iki kez koşardı. Toplama başarısızsa {@code null} olur ve mail eksiz gider.
     */
    public record TeamReport(List<AvailabilityRow> rows, AvailabilitySummary summary,
                             String subject, String html, String[] to, String[] cc,
                             WeeklyOutageReportService.WeeklyOutageData outage) {}

    /** Önizleme yanıtı (göndermeden). */
    public record PreviewResult(String html, String teamName, String weekLabel,
                                List<String> to, List<String> cc, int domainCount, boolean noRecipients) {}

    /** Önizleme hafta seçici için bir seçenek (offset = kaç hafta öncesi). */
    public record WeekOption(int offset, String label, boolean current, boolean emailed) {}

    /** Durum sayfası: genel anahtar + cron + raporlanan hafta + mail kitlesi + önizleme hafta seçenekleri. */
    public record StatusResult(boolean enabled, String cron, String weekLabel,
                               List<TeamStatus> teams, List<WeekOption> weeks) {}
    public record TeamStatus(Long id, String name, int domainCount, List<String> to, List<String> cc,
                             String lastStatus, String lastSentAt) {}

    /** Arşivlenmiş giden mail satırı (liste — HTML gövdesi YOK, hafif). */
    public record ArchivedMail(Long id, String sentAt, String team, String to, String cc,
                               String subject, String status, String trigger) {}
    /** Arşivlenmiş giden mailin tam içeriği (önizleme — HTML dahil). */
    public record ArchivedMailDetail(Long id, String sentAt, String team, String to, String cc,
                                     String subject, String status, String trigger, String html) {}

    private static final List<String> ARCHIVE_TRIGGERS =
            List.of("WEEKLY_AVAILABILITY", "WEEKLY_AVAILABILITY_TEST");

    /** force=true → idempotency atlanır (manuel tetik/test). */
    @Transactional
    public SendResult sendWeeklyReports(boolean force) {
        if (!isEnabled()) {
            log.info("Haftalık erişilebilirlik raporu devre dışı (weekly-availability.enabled=false) — atlandı");
            return new SendResult(0, 0, 0, 0, 0, 0);
        }

        Window w = lastFullWeekWindow();
        List<Team> teams = teamRepo.findByActiveTrueOrderByNameAsc();
        int sent = 0, skipNoDomains = 0, skipNoRecipient = 0, skipDone = 0, skipDisabled = 0;

        for (Team team : teams) {
            // Takım başına opt-in (Takım Yönetimi'ndeki anahtar). Filtre repo sorgusuna DEĞİL buraya konur —
            // findByActiveTrueOrderByNameAsc'i başka akışlar da kullanıyor.
            if (!Boolean.TRUE.equals(team.getWeeklyAvailabilityEnabled())) { skipDisabled++; continue; }

            List<CertificateInventory> domains =
                    inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(team.getId());
            if (domains.isEmpty()) { skipNoDomains++; continue; }

            if (!force) {
                Optional<WeeklyAvailabilityLog> prev =
                        walRepo.findByTeamIdAndReportYearAndWeekNo(team.getId(), w.year(), w.week());
                if (prev.isPresent() && "SENT".equals(prev.get().getStatus())) { skipDone++; continue; }
            }

            TeamReport report = buildTeamReport(team, w, domains);
            if (report.to().length == 0) {
                skipNoRecipient++;
                record(team.getId(), w.year(), w.week(), "NO_RECIPIENT");
                log.warn("Haftalık erişilebilirlik atlandı (alıcı yok): team={} ({})", team.getName(), team.getId());
                continue;
            }

            String[] cc = report.cc();
            List<EmailNotificationService.MailAttachment> attachments = outageAttachment(team, w, report);
            String status = emailService.sendHtmlWithAttachments(report.to(), cc.length > 0 ? cc : null,
                    report.subject(), report.html(), null, attachments);
            saveNotificationLog(team, report.to(), cc, report.subject(), report.html(), status, "WEEKLY_AVAILABILITY");
            record(team.getId(), w.year(), w.week(), status != null && status.startsWith("FAILED") ? "FAILED" : "SENT");
            sent++;
            log.info("Haftalık erişilebilirlik: team={} week={} domains={} to={} cc={} status={}",
                    team.getName(), w.weekLabel(), domains.size(), Arrays.toString(report.to()), Arrays.toString(cc), status);
        }

        SendResult result = new SendResult(teams.size(), sent, skipNoDomains, skipNoRecipient, skipDone, skipDisabled);
        log.info("Haftalık erişilebilirlik raporu tamamlandı: {} takım → gönderilen={}, domain'siz={}, alıcısız={}, "
                + "zaten gönderilmiş={}, rapor kapalı={}",
                result.teams(), result.sent(), result.skippedNoDomains(), result.skippedNoRecipient(),
                result.skippedDone(), result.skippedDisabled());
        return result;
    }

    public boolean isEnabled() {
        return appSettings.getBoolean(ENABLED_KEY, enabledDefault);
    }

    /** Genel aç/kapa anahtarını kalıcı yaz (Genel Ayarlar ile aynı kalıcılık + canlı cache refresh). */
    public void setEnabled(boolean enabled, String actor) {
        appSettings.save(Map.of("values", Map.of(ENABLED_KEY, String.valueOf(enabled))), actor);
        log.info("Haftalık erişilebilirlik anahtarı {} → {}", enabled ? "AÇILDI" : "KAPATILDI", actor);
    }

    // ── Pencere + tek takım rapor üretimi ────────────────────────────────────────

    /** Geçen tam ISO hafta (Pzt 00:00 – Paz 23:59:59, Europe/Istanbul) → gerçek e-postanın raporladığı pencere. */
    Window lastFullWeekWindow() {
        return windowForOffset(1);
    }

    /**
     * {@code offset} hafta öncesinin penceresi (Europe/Istanbul → UTC ISO sınırlar).
     * offset==0 → İÇİNDE BULUNULAN hafta: Pzt 00:00'dan ŞU ANA kadar (kısmi veri; yalnız önizleme).
     * offset>=1 → o kadar hafta önceki TAM hafta (Pzt 00:00 – Paz 23:59:59). offset=1 = gerçek e-postanın haftası.
     */
    Window windowForOffset(int offset) {
        LocalDate thisMonday = LocalDate.now(IST).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate monday = thisMonday.minusWeeks(offset);
        LocalDate sunday = monday.plusDays(6);
        ZonedDateTime fromZ = monday.atStartOfDay(IST);
        Instant end = (offset == 0)
                ? Instant.now()                                       // bu hafta: yalnız şu ana kadarki veri
                : sunday.atTime(23, 59, 59).atZone(IST).toInstant();  // tamamlanmış hafta
        int year = monday.get(WeekFields.ISO.weekBasedYear());
        int week = monday.get(WeekFields.ISO.weekOfWeekBasedYear());
        // Availability 7/24 izlenir → etiket TAM haftayı (Pzt–Paz) gösterir; haftalık RAPOR modülünün
        // Pzt–Cum (iş haftası) etiketini KULLANMA — yoksa "15–21 veri" ama "15–19 etiket" tutarsızlığı olur.
        return new Window(UTC_ISO.format(fromZ.toInstant()), UTC_ISO.format(end),
                end, year, week, weekRangeLabel(monday, sunday));
    }

    /** Önizleme hafta seçici seçenekleri: offset 0 (bu hafta, kısmi) … 8 (son 8 tam hafta). */
    List<WeekOption> weekOptions() {
        List<WeekOption> opts = new ArrayList<>();
        for (int offset = 0; offset <= 8; offset++) {
            Window w = windowForOffset(offset);
            opts.add(new WeekOption(offset, w.weekLabel(), offset == 0, offset == 1));
        }
        return opts;
    }

    // ── KPI şeridi (WeeklyReportKpiService) için — belirli ISO (yıl, hafta) penceresi + takım uptime%'i ──

    /** Belirli bir ISO (yıl, hafta)'nın Pazartesi'si. LocalDate.of(y,1,4) daima ISO 1. haftadadır → yıl sınırı normalize olur. */
    public static LocalDate mondayOfIsoWeek(int isoYear, int isoWeek) {
        return LocalDate.of(isoYear, 1, 4)
                .with(WeekFields.ISO.weekOfWeekBasedYear(), isoWeek)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Verilen Pazartesi'den TAM hafta penceresi (Pzt 00:00 – Paz 23:59:59, Europe/Istanbul → UTC ISO sınırlar);
     *  sınırlar {@code uptime_checks.checked_at} / {@code alert_events.created_at} string formatıyla hizalı. */
    public Window windowForMonday(LocalDate monday) {
        LocalDate sunday = monday.plusDays(6);
        Instant from = monday.atStartOfDay(IST).toInstant();
        Instant end = sunday.atTime(23, 59, 59).atZone(IST).toInstant();
        int year = monday.get(WeekFields.ISO.weekBasedYear());
        int week = monday.get(WeekFields.ISO.weekOfWeekBasedYear());
        return new Window(UTC_ISO.format(from), UTC_ISO.format(end), end, year, week, weekRangeLabel(monday, sunday));
    }

    /** KPI: verilen pencerede takımın (SY team_id) ortalama uptime %'i + özeti — buildTeamReport'un hesap çekirdeği,
     *  HTML/alıcı üretmeden (KPI şeridi + hafta-üstü delta için). Uptime kaynağı = HTTP uptime_checks (port/DNS hariç). */
    public AvailabilitySummary weeklyUptime(Long teamId, Window w) {
        return weeklyUptime(teamId, w, null);
    }

    /** {@code tierOnly} verilirse yalnız o tier'daki (CertificateInventory.tier) domainler dahil — sağlık skoru
     *  tier-1 uptime'ı için. null → tüm aktif domainler. computeRow/summarize çekirdeği aynen kullanılır. */
    public AvailabilitySummary weeklyUptime(Long teamId, Window w, Integer tierOnly) {
        List<CertificateInventory> domains =
                inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(teamId);
        List<AvailabilityRow> rows = new ArrayList<>();
        for (CertificateInventory inv : domains) {
            if (tierOnly != null && !tierOnly.equals(inv.getTier())) continue;
            int port = inv.getPort() != null ? inv.getPort() : 443;
            List<UptimeCheck> checks = uptimeCheckRepo
                    .findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(inv.getDomain(), port, w.fromUtc(), w.toUtc());
            Integer certDays = latestCheckRepo.findById(inv.getDomain()).map(LatestCheck::getDaysRemaining).orElse(null);
            rows.add(computeRow(inv.getDomain(), checks, w.windowEnd(), certDays));
        }
        return summarize(rows);
    }

    /** Tek domain için son {@code hours} saatteki erişilebilirlik satırı (recovery e-postası özeti). Kaynak = HTTP
     *  uptime_checks (port/DNS hariç); örnek yoksa availabilityPct=null döner. computeRow çekirdeği aynen kullanılır
     *  (bakım-farkında). EscalationService bunu çağırır → döngüsel bağımlılık yok (WeeklyAvail EscalationService'e bağlı değil). */
    public AvailabilityRow availabilityLastHours(String domain, long hours) {
        Instant now = Instant.now();
        String fromUtc = UTC_ISO.format(now.minus(Duration.ofHours(hours)));
        String toUtc = UTC_ISO.format(now);
        int port = inventoryRepo.findByDomain(domain).map(CertificateInventory::getPort).orElse(443);
        List<UptimeCheck> checks = uptimeCheckRepo
                .findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(domain, port, fromUtc, toUtc);
        Integer certDays = latestCheckRepo.findById(domain).map(LatestCheck::getDaysRemaining).orElse(null);
        return computeRow(domain, checks, now, certDays);
    }

    /** "15–21 Haziran 2026" / "29 Haziran – 5 Temmuz 2026" / "29 Aralık 2025 – 4 Ocak 2026" (Pzt–Paz, dahil). */
    static String weekRangeLabel(LocalDate from, LocalDate to) {
        String mFrom = MONTHS_TR[from.getMonthValue() - 1];
        String mTo = MONTHS_TR[to.getMonthValue() - 1];
        if (from.getYear() != to.getYear()) {
            return from.getDayOfMonth() + " " + mFrom + " " + from.getYear() + " – "
                    + to.getDayOfMonth() + " " + mTo + " " + to.getYear();
        }
        if (from.getMonthValue() != to.getMonthValue()) {
            return from.getDayOfMonth() + " " + mFrom + " – "
                    + to.getDayOfMonth() + " " + mTo + " " + to.getYear();
        }
        return from.getDayOfMonth() + "–" + to.getDayOfMonth() + " " + mTo + " " + to.getYear();
    }

    /** Tek takım için availability satırlarını hesaplar, HTML + konu + çözülmüş alıcıları kurar (GÖNDERMEZ). */
    TeamReport buildTeamReport(Team team, Window w, List<CertificateInventory> domains) {
        List<AvailabilityRow> rows = new ArrayList<>();
        for (CertificateInventory inv : domains) {
            int port = inv.getPort() != null ? inv.getPort() : 443;
            List<UptimeCheck> checks = uptimeCheckRepo
                    .findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(inv.getDomain(), port, w.fromUtc(), w.toUtc());
            Integer certDays = latestCheckRepo.findById(inv.getDomain())
                    .map(LatestCheck::getDaysRemaining).orElse(null);
            rows.add(computeRow(inv.getDomain(), checks, w.windowEnd(), certDays));
        }
        // En kötü availability üstte (null = veri yok, en sona)
        rows.sort(Comparator.comparing(r -> r.availabilityPct() == null ? Double.MAX_VALUE : r.availabilityPct()));

        AvailabilitySummary summary = summarize(rows);
        String[] to = resolveTo(team);
        String[] cc = resolveCc(team.getId(), to);
        String subject = "[Site Monitor] " + team.getName() + " — Haftalık Erişilebilirlik (" + w.weekLabel() + ")";

        // Kesinti verisi HTML'den ÖNCE toplanır: gövdedeki ek bandı ekin içeriğini yazıyor.
        WeeklyOutageReportService.WeeklyOutageData outage = collectOutage(team, w, rows);
        EmailNotificationService.AttachmentInfo att = outage == null ? null
                : new EmailNotificationService.AttachmentInfo(
                        WeeklyOutageReportService.fileName(team.getName(), w),
                        outage.totalAlarms(), outage.stillOpenCount(), outage.affectedTargets(),
                        MonitorTypeCatalog.ORDER.size());

        EmailNotificationService.PageSpeedWeekly pageSpeed = collectPageSpeed(team, w);
        EmailNotificationService.DeploymentWeekly deployments = collectDeployments(w);
        EmailNotificationService.WeakAlgoWeekly weak = collectWeakAlgo(domains);
        EmailNotificationService.DomainExpiryWeekly domExp = collectDomainExpiry(team);
        String html = emailService.buildWeeklyAvailabilityHtml(
                team.getName(), w.weekLabel(), rows, summary, att, pageSpeed, deployments, weak, domExp);
        return new TeamReport(rows, summary, subject, html, to, cc, outage);
    }

    /**
     * Rapor penceresindeki dağıtım özeti (E2): koşan ortamın türetilmiş geçiş listesinden
     * {@code started_at ∈ [from, to]} satırlar sayılır. Dağıtım = UPGRADE/ROLLBACK/CHANGED/FIRST_SEEN,
     * yeniden başlatma = RESTART, geri alma = ROLLBACK (dağıtıma da dahil). Sürüm aralığı haftanın
     * ilk geçişinin ÖNCEKİ sürümünden son geçişin sürümüne. Zaman damgaları ISO-UTC dize; pencere
     * sınırları 'Z' taşımaz, satırlar taşır — karşılaştırma öncesi 'Z' soyulur.
     *
     * <p>Patlarsa {@code null} döner ve rapor bu satır OLMADAN gider (sayfa hızı / ek ile aynı ilke).
     */
    /** Alan adı bitişleri (2026-09-22, madde G): takımın aktif alan adı izlemeleri, 90 gün içinde bitenler + kilitsizler.
     *  Depo yoksa ya da patlarsa {@code null} → rapor bu bölüm OLMADAN gider (sayfa hızı ile aynı ilke). */
    EmailNotificationService.DomainExpiryWeekly collectDomainExpiry(Team team) {
        if (domainMonitorRepo == null || domainCheckRepo == null || team == null) return null;
        try {
            final int window = 90;
            Map<Long, com.sitemonitor.model.DomainCheck> latest = new HashMap<>();
            for (com.sitemonitor.model.DomainCheck c : domainCheckRepo.findLatestPerMonitor())
                if (c.getMonitorId() != null) latest.putIfAbsent(c.getMonitorId(), c);
            String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
            List<EmailNotificationService.DomainExpiryWeeklyRow> rows = new ArrayList<>();
            int count = 0, unlocked = 0;
            for (com.sitemonitor.model.DomainMonitor m : domainMonitorRepo.findByActiveTrue()) {
                if (m.getTeamId() == null || !m.getTeamId().equals(team.getId())) continue;
                count++;
                com.sitemonitor.model.DomainCheck c = latest.get(m.getId());
                if (c != null && "NONE".equals(c.getTransferLock())) unlocked++;
                Integer d = c != null ? c.getDaysRemaining() : null;
                boolean overdue = m.getRenewalPlannedAt() != null && m.getRenewalPlannedAt().compareTo(today) < 0;
                if ((d != null && d <= window) || overdue)
                    rows.add(new EmailNotificationService.DomainExpiryWeeklyRow(m.getDomain(), d, c != null ? c.getExpiryDate() : null,
                            c != null ? c.getRegistrar() : null, c != null ? c.getTransferLock() : null, m.getRenewalPlannedAt(), overdue));
            }
            rows.sort(Comparator.comparing((EmailNotificationService.DomainExpiryWeeklyRow r) -> r.daysRemaining() == null ? Integer.MAX_VALUE : r.daysRemaining()));
            if (rows.size() > 12) rows = new ArrayList<>(rows.subList(0, 12));
            return new EmailNotificationService.DomainExpiryWeekly(rows, count, window, unlocked);
        } catch (Exception e) {
            log.warn("Haftalık rapor alan adı bölümü üretilemedi: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Zayıf algoritma satırı (2026-09-12) — DEPO ile hesaplanır (WeakAlgorithmReportService enjekte
     * edilmez: bu sınıfa servis eklemek dairesel referans üretir). Hüküm ortak yardımcıdan.
     */
    EmailNotificationService.WeakAlgoWeekly collectWeakAlgo(List<CertificateInventory> domains) {
        try {
            List<String> names = domains.stream().map(CertificateInventory::getDomain).filter(Objects::nonNull).toList();
            Map<String, LatestCheck> latest = new HashMap<>();
            for (String d : names) latestCheckRepo.findById(d).ifPresent(lc -> latest.put(d, lc));
            int[] ws = WeakAlgorithmReportService.weakAndScannedFor(names, latestCheckRepo.findWeakAlgorithmCandidates(), latest);
            return new EmailNotificationService.WeakAlgoWeekly(ws[0], ws[1]);
        } catch (Exception e) {
            log.warn("Haftalık rapor zayıf-algoritma satırı hesaplanamadı (bant atlanır): {}", e.toString());
            return null;
        }
    }

    EmailNotificationService.DeploymentWeekly collectDeployments(Window w) {
        try {
            String env = deploymentHistory.currentEnvironment();
            List<DeploymentHistoryService.Derived> all = deploymentHistory.derivedFor(env);
            if (all == null) all = List.of();
            String from = stripZ(w.fromUtc()), to = stripZ(w.toUtc());
            int deployments = 0, restarts = 0, rollbacks = 0;
            String fromVersion = null, toVersion = null;
            for (DeploymentHistoryService.Derived d : all) {
                String at = stripZ(d.row().getStartedAt());
                if (at == null || at.compareTo(from) < 0 || at.compareTo(to) > 0) continue;
                switch (d.kind()) {
                    case RESTART -> restarts++;
                    case UNKNOWN -> { /* sürümsüz backfill satırı — sayılmaz */ }
                    default -> {
                        deployments++;
                        if (d.kind() == DeploymentHistoryService.Kind.ROLLBACK) rollbacks++;
                        if (fromVersion == null) fromVersion = d.previousVersion();
                        toVersion = d.row().getVersion();
                    }
                }
            }
            return new EmailNotificationService.DeploymentWeekly(deployments, fromVersion, toVersion, restarts, rollbacks);
        } catch (Exception e) {
            log.warn("Haftalık rapor dağıtım satırı toplanamadı (rapor satırsız gider): {}", e.toString());
            return null;
        }
    }

    private static String stripZ(String iso) {
        if (iso == null) return null;
        return iso.endsWith("Z") ? iso.substring(0, iso.length() - 1) : iso;
    }

    /** Haftalık rapordaki en yavaş sayfa sayısı — tablo okunur kalsın diye kısa tutulur. */
    private static final int PAGESPEED_TOP_N = 5;

    /**
     * Takımın Sayfa Hızı özeti: en yavaş {@value #PAGESPEED_TOP_N} sayfa + geçen haftaya göre değişim.
     *
     * <p>Patlarsa {@code null} döner ve rapor bu bölüm OLMADAN tam olarak gider — haftalık rapor bir
     * yan bölüm yüzünden hiç gitmemezlik etmemeli (kesinti ekiyle aynı ilke).
     */
    private EmailNotificationService.PageSpeedWeekly collectPageSpeed(Team team, Window w) {
        try {
            List<com.sitemonitor.model.PageSpeedMonitor> monitors = pageSpeedMonitorRepo.findByActiveTrue().stream()
                    .filter(m -> team.getId().equals(m.getTeamId()))
                    .toList();
            if (monitors.isEmpty()) return null;
            List<Long> ids = monitors.stream().map(com.sitemonitor.model.PageSpeedMonitor::getId).toList();

            Map<Long, Object[]> cur = summaryById(ids, w.fromUtc(), w.toUtc());
            // Geçen hafta: aynı uzunlukta, bir hafta geriye kaydırılmış pencere.
            String prevFrom = shiftWeek(w.fromUtc());
            String prevTo = shiftWeek(w.toUtc());
            Map<Long, Object[]> prev = summaryById(ids, prevFrom, prevTo);

            List<EmailNotificationService.PageSpeedWeeklyRow> rows = new ArrayList<>();
            int breachedMonitors = 0;
            for (com.sitemonitor.model.PageSpeedMonitor m : monitors) {
                Object[] c = cur.get(m.getId());
                if (c == null) continue;                       // bu hafta hiç ölçüm yok → satır yazma
                Long avg = asLongRounded(c[2]);
                long breaches = c[3] instanceof Number n ? n.longValue() : 0L;
                if (breaches > 0) breachedMonitors++;
                Object[] pr = prev.get(m.getId());
                rows.add(new EmailNotificationService.PageSpeedWeeklyRow(
                        m.getName(), m.getUrl(), avg, pr == null ? null : asLongRounded(pr[2]), breaches));
            }
            if (rows.isEmpty()) return null;
            rows.sort((a, b) -> Long.compare(b.avgLoadMs() == null ? -1 : b.avgLoadMs(),
                                             a.avgLoadMs() == null ? -1 : a.avgLoadMs()));
            List<EmailNotificationService.PageSpeedWeeklyRow> top =
                    rows.subList(0, Math.min(rows.size(), PAGESPEED_TOP_N));
            return new EmailNotificationService.PageSpeedWeekly(
                    List.copyOf(top), monitors.size(), breachedMonitors);
        } catch (Exception e) {
            log.warn("Haftalık sayfa hızı bölümü toplanamadı (team={} week={}) — rapor bu bölüm OLMADAN gidiyor: {}",
                    team.getName(), w.weekLabel(), e.toString());
            return null;
        }
    }

    /** [monitorId, sayı, ort ms, ihlal sayısı] satırlarını id'ye göre indeksler. */
    private Map<Long, Object[]> summaryById(List<Long> ids, String from, String to) {
        Map<Long, Object[]> out = new java.util.HashMap<>();
        for (Object[] r : pageSpeedCheckRepo.weeklySummary(ids, from, to)) {
            if (r[0] instanceof Number n) out.put(n.longValue(), r);
        }
        return out;
    }

    private static Long asLongRounded(Object v) {
        return v instanceof Number n ? Math.round(n.doubleValue()) : null;
    }

    /**
     * ISO-UTC damgasını tam BİR HAFTA geriye kaydırır (pencere uzunluğu korunur).
     *
     * <p>Biçim AÇIKÇA verilir: {@code LocalDateTime.toString()} saniye sıfırsa onu DÜŞÜRÜR
     * ({@code 2026-08-10T00:00}). Kontrol damgaları veritabanında hep saniyeli saklandığı ve
     * karşılaştırma METİNSEL yapıldığı için bu, geçen-hafta penceresini sessizce kaydırırdı. */
    private static final java.time.format.DateTimeFormatter WINDOW_ISO =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    static String shiftWeek(String isoUtc) {
        return java.time.LocalDateTime.parse(isoUtc).minusWeeks(1).format(WINDOW_ISO);
    }

    /** Kesinti verisini toplar; patlarsa {@code null} döner ve rapor eksiz ama TAM olarak gider. */
    private WeeklyOutageReportService.WeeklyOutageData collectOutage(
            Team team, Window w, List<AvailabilityRow> rows) {
        try {
            return outageReportService.collect(team, w, rows);
        } catch (Exception e) {
            log.warn("Haftalık kesinti verisi toplanamadı (team={} week={}) — mail EK OLMADAN gidiyor: {}",
                    team.getName(), w.weekLabel(), e.toString(), e);
            return null;
        }
    }

    /**
     * Haftanın kesinti detayı PDF'i — mail eki.
     *
     * <p>Kesinti YAŞANMAYAN haftalarda da eklenir (kullanıcı kararı): rapor arşivi her hafta bir
     * dosya içerecek şekilde tutarlı kalsın ve "ek gelmedi, unutuldu mu?" sorusu doğmasın.
     *
     * <p>Erişilebilirlik satırları PARAMETRE olarak geçiliyor: {@code buildTeamReport} onları
     * zaten hesapladı; toplayıcının yeniden hesaplaması tüm uptime sorgularını ikinci kez
     * koşturmak olurdu (tek pod, 200-1000+ domain).
     *
     * <p>Toplama ya da çizim herhangi bir sebeple patlarsa boş liste döner ve <b>mail ek olmadan
     * yine gider</b>. Haftalık rapor bir ek hatası yüzünden hiç gitmemezlik etmemeli.
     */
    private List<EmailNotificationService.MailAttachment> outageAttachment(Team team, Window w, TeamReport report) {
        if (report.outage() == null) return List.of();   // toplama düşmüş, gövde zaten ekten söz etmiyor
        try {
            byte[] pdf = outageReportService.pdf(report.outage());
            if (pdf.length == 0) return List.of();
            return List.of(new EmailNotificationService.MailAttachment(
                    WeeklyOutageReportService.fileName(team.getName(), w), pdf, "application/pdf"));
        } catch (Exception e) {
            log.warn("Haftalık kesinti eki hazırlanamadı (team={} week={}) — mail EK OLMADAN gidiyor: {}",
                    team.getName(), w.weekLabel(), e.toString(), e);
            return List.of();
        }
    }

    // ── Önizleme / test / durum (Ayarlar sayfası) ────────────────────────────────

    /** Geriye uyumlu: hafta belirtilmezse geçen tam hafta (e-posta ile giden). */
    public PreviewResult preview(Long teamId) {
        return preview(teamId, null);
    }

    /**
     * Tek takımın haftalık raporunu GÖNDERMEDEN üretir (Ayarlar → önizleme).
     * weekOffset: 0 = bu hafta (kısmi), 1 = geçen tam hafta (e-posta ile giden), null → 1. [0,8] aralığına kırpılır.
     */
    public PreviewResult preview(Long teamId, Integer weekOffset) {
        Team team = teamRepo.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Takım bulunamadı: " + teamId));
        int offset = weekOffset != null ? Math.max(0, Math.min(weekOffset, 8)) : 1;
        Window w = windowForOffset(offset);
        List<CertificateInventory> domains =
                inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(teamId);
        TeamReport report = buildTeamReport(team, w, domains);
        return new PreviewResult(report.html(), team.getName(), w.weekLabel(),
                List.of(report.to()), List.of(report.cc()), domains.size(), report.to().length == 0);
    }

    /** Seçilen takımın raporunu YALNIZ verilen test adresine gönderir (cc yok; idempotency log'una yazmaz). */
    public String sendTest(Long teamId, String email) {
        Team team = teamRepo.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Takım bulunamadı: " + teamId));
        Window w = lastFullWeekWindow();
        List<CertificateInventory> domains =
                inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(teamId);
        TeamReport report = buildTeamReport(team, w, domains);
        String subject = "[Site Monitor][TEST] " + team.getName() + " — Haftalık Erişilebilirlik (" + w.weekLabel() + ")";
        String[] to = { email };
        // Test maili gerçek mailin AYNISI olmalı — eki taşımasaydı PDF ancak pazartesi sabahı
        // görülebilirdi, yani göndermeden önce doğrulanamazdı.
        String status = emailService.sendHtmlWithAttachments(to, null, subject, report.html(), null,
                outageAttachment(team, w, report));
        saveNotificationLog(team, to, null, subject, report.html(), status, "WEEKLY_AVAILABILITY_TEST");
        log.info("Haftalık erişilebilirlik TEST maili: team={} week={} to={} status={}",
                team.getName(), w.weekLabel(), email, status);
        return status;
    }

    /**
     * Kesinti PDF'ini GÖNDERMEDEN üretir (Ayarlar → önizleme; ekin indirilebilir hâli).
     *
     * <p>Neden ayrı bir yol var: eki doğrulamanın tek yolu mail göndermek olsaydı, PDF'e bakmak
     * için ya pazartesiyi beklemek ya da gerçek bir adrese test maili atmak gerekirdi. Önizleme
     * ikisini de gereksiz kılar.
     *
     * @param weekOffset 0 = bu hafta (kısmi), 1 = geçen tam hafta (e-posta ile giden); [0,8] aralığına kırpılır
     * @return PDF baytları; üretim düşerse boş dizi (çağıran 503/uyarı döndürür)
     */
    public byte[] outagePdf(Long teamId, Integer weekOffset) {
        Team team = teamRepo.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Takım bulunamadı: " + teamId));
        int offset = weekOffset != null ? Math.max(0, Math.min(weekOffset, 8)) : 1;
        Window w = windowForOffset(offset);
        List<CertificateInventory> domains =
                inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(teamId);
        TeamReport report = buildTeamReport(team, w, domains);
        // buildTeamReport veriyi ZATEN topladı (gövdedeki ek bandı için) — yeniden toplamak
        // bütün alarm sorgularını ikinci kez koşturmak olurdu.
        return report.outage() == null ? new byte[0] : outageReportService.pdf(report.outage());
    }

    /** Önizleme/indirme için ek dosya adı — mailde gidenle AYNI ad. */
    public String outagePdfFileName(Long teamId, Integer weekOffset) {
        Team team = teamRepo.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Takım bulunamadı: " + teamId));
        int offset = weekOffset != null ? Math.max(0, Math.min(weekOffset, 8)) : 1;
        return WeeklyOutageReportService.fileName(team.getName(), windowForOffset(offset));
    }

    /** Ayarlar durum kartı: genel anahtar + cron + raporlanan hafta + mail kitlesindeki takımlar (≥1 domain). */
    public StatusResult status() {
        Window w = lastFullWeekWindow();
        List<TeamStatus> teamStatuses = new ArrayList<>();
        for (Team team : teamRepo.findByActiveTrueOrderByNameAsc()) {
            // Kart "gerçek mail kitlesi"ni gösterir: takım anahtarı kapalıysa o takıma mail gitmiyor.
            if (!Boolean.TRUE.equals(team.getWeeklyAvailabilityEnabled())) continue;
            List<CertificateInventory> domains =
                    inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(team.getId());
            if (domains.isEmpty()) continue; // gerçek mail kitlesi
            String[] to = resolveTo(team);
            String[] cc = resolveCc(team.getId(), to);
            Optional<WeeklyAvailabilityLog> prev =
                    walRepo.findByTeamIdAndReportYearAndWeekNo(team.getId(), w.year(), w.week());
            teamStatuses.add(new TeamStatus(team.getId(), team.getName(), domains.size(),
                    List.of(to), List.of(cc),
                    prev.map(WeeklyAvailabilityLog::getStatus).orElse(null),
                    prev.map(WeeklyAvailabilityLog::getSentAt).orElse(null)));
        }
        return new StatusResult(isEnabled(), cronExpr, w.weekLabel(), teamStatuses, weekOptions());
    }

    /** Arşiv listesi: gönderilmiş haftalık erişilebilirlik mailleri (en yeni üstte). includeTest → test maillerini de getir. */
    public List<ArchivedMail> history(int limit, boolean includeTest) {
        List<String> triggers = includeTest ? ARCHIVE_TRIGGERS : List.of("WEEKLY_AVAILABILITY");
        int cap = Math.max(1, Math.min(limit, 500));
        return notificationLogRepo.findByTriggerInOrderBySentAtDesc(triggers, PageRequest.of(0, cap))
                .stream()
                .map(n -> new ArchivedMail(n.getId(), n.getSentAt(), n.getRecipientName(),
                        n.getRecipientEmail(), n.getCc(), n.getSubject(), n.getEmailStatus(), n.getTrigger()))
                .toList();
    }

    /**
     * System Health kartı: scheduler işinin durumu — açık/duraklatılmış, cron, sıradaki çalışma,
     * ve son zamanlanmış çalışmanın özeti (weekly_availability_log'tan; restart/multi-pod dayanıklı).
     */
    public Map<String, Object> schedulerHealth() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", isEnabled());
        m.put("cron", cronExpr);
        // Sıradaki çalışma — cron'dan (Europe/Istanbul) → UTC ISO
        try {
            ZonedDateTime next = CronExpression.parse(cronExpr).next(ZonedDateTime.now(IST));
            m.put("next_run", next != null ? UTC_ISO.format(next.toInstant()) : null);
        } catch (Exception e) {
            m.put("next_run", null);
        }
        // Son çalışma — en yeni weekly_availability_log kaydı + o (yıl,hafta) için özet
        Optional<WeeklyAvailabilityLog> last = walRepo.findTopByOrderBySentAtDesc();
        if (last.isPresent()) {
            WeeklyAvailabilityLog l = last.get();
            List<WeeklyAvailabilityLog> group = walRepo.findByReportYearAndWeekNo(l.getReportYear(), l.getWeekNo());
            long sent     = group.stream().filter(x -> "SENT".equals(x.getStatus())).count();
            long failed   = group.stream().filter(x -> x.getStatus() != null && x.getStatus().startsWith("FAIL")).count();
            long noRecip  = group.stream().filter(x -> "NO_RECIPIENT".equals(x.getStatus())).count();
            LocalDate lrMon = LocalDate.of(l.getReportYear(), 1, 4)
                    .with(WeekFields.ISO.weekOfWeekBasedYear(), l.getWeekNo())
                    .with(DayOfWeek.MONDAY);
            m.put("last_run_at", l.getSentAt());
            m.put("last_run_week", weekRangeLabel(lrMon, lrMon.plusDays(6)));
            m.put("last_run_year", l.getReportYear());
            m.put("last_run_week_no", l.getWeekNo());
            m.put("last_run_sent", sent);
            m.put("last_run_failed", failed);
            m.put("last_run_no_recipient", noRecip);
            m.put("last_run_teams", group.size());
        } else {
            m.put("last_run_at", null);
        }
        return m;
    }

    /** Tek bir arşiv kaydının tam içeriği (HTML gövdesi dahil). Yalnız haftalık erişilebilirlik trigger'ları. */
    public ArchivedMailDetail historyItem(Long id) {
        NotificationLog n = notificationLogRepo.findById(id)
                .filter(x -> ARCHIVE_TRIGGERS.contains(x.getTrigger()))
                .orElseThrow(() -> new IllegalArgumentException("Arşiv kaydı bulunamadı: " + id));
        return new ArchivedMailDetail(n.getId(), n.getSentAt(), n.getRecipientName(), n.getRecipientEmail(),
                n.getCc(), n.getSubject(), n.getEmailStatus(), n.getTrigger(), n.getMessage());
    }

    // ── Hesaplama ───────────────────────────────────────────────────────────────

    AvailabilityRow computeRow(String domain, List<UptimeCheck> ordered, Instant windowEnd, Integer certDays) {
        // Bakım (maintenance=true) satırları uptime %'sinden + yanıt sürelerinden HARİÇ tutulur (nonMaint).
        List<UptimeCheck> nonMaint = ordered.stream().filter(c -> !Boolean.TRUE.equals(c.getMaintenance())).toList();
        int total = nonMaint.size();
        if (total == 0) {
            return new AvailabilityRow(domain, null, 0, 0, 0, null, null, certDays);
        }
        long up = nonMaint.stream().filter(WeeklyAvailabilityReportService::isUp).count();
        // 2 ondalık hassasiyet; hiç down örnek varsa (up<total) asla 100.00 gösterme — yuvarlama
        // tek bir kesinti örneğini (örn. 2015/2016 = %99.95) yanıltıcı şekilde %100'e çekmesin.
        double pct = Math.round(up * 10000.0 / total) / 100.0;
        if (pct >= 100.0 && up < total) pct = 99.99;

        // Yanıt süreleri (yalnız up + responseMs dolu) — bakım hariç
        List<Long> resp = nonMaint.stream()
                .filter(WeeklyAvailabilityReportService::isUp)
                .map(UptimeCheck::getResponseMs)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        Long avg = resp.isEmpty() ? null : Math.round(resp.stream().mapToLong(Long::longValue).average().orElse(0));
        Long p95 = percentile(resp, 95);

        // Kesinti blokları (ardışık down)
        int outageCount = 0;
        long totalDownMs = 0, longestMs = 0;
        boolean inDown = false;
        boolean outagePaused = false;   // down iken bakıma girdi, henüz kurtarılmadı — bkz. M2-COUNT
        Instant downStart = null;
        // Kesinti blokları TÜM satırlar üzerinde yürünür. Bir bakım satırı, sürmekte olan kesintinin
        // SÜRESİNİ dondurur (bakım dakikaları downMin/longest'e girmez, M2) ama kesintiyi KAPATMAZ:
        // bakımdan sonra hâlâ down ise bu AYNI kesintinin devamıdır, yeni bir kesinti değil (M2-COUNT).
        // Yalnız araya gerçek bir kurtarma (up) girdiğinde kesinti biter → sonraki down yeni kesintidir.
        for (UptimeCheck c : ordered) {
            Instant t = parse(c.getCheckedAt());
            if (Boolean.TRUE.equals(c.getMaintenance())) {
                if (inDown) {   // süreyi bakım başında dondur; kesinti kimliğini koru (kapatma)
                    long d = durationMs(downStart, t);
                    totalDownMs += d; longestMs = Math.max(longestMs, d);
                    inDown = false; downStart = null; outagePaused = true;
                }
                continue;   // bakım süresi kesintiye sayılmaz
            }
            if (!isUp(c)) {
                if (!inDown) {
                    inDown = true; downStart = t;
                    if (outagePaused) outagePaused = false;   // bakım sonrası devam → aynı kesinti, TEKRAR sayma
                    else outageCount++;                       // yeni kesinti
                }
            } else {   // up örneği — sürmekte/beklemekte olan kesinti (varsa) çözüldü
                if (inDown) {
                    long d = durationMs(downStart, t);
                    totalDownMs += d; longestMs = Math.max(longestMs, d);
                    inDown = false; downStart = null;
                }
                outagePaused = false;   // bakımda beklerken kurtarma geldi → kesinti kapandı
            }
        }
        if (inDown) { // pencere sonuna kadar sürüyor
            long d = durationMs(downStart, windowEnd);
            totalDownMs += d; longestMs = Math.max(longestMs, d);
        }
        long downMin = Math.round(totalDownMs / 60000.0);
        long longestMin = Math.round(longestMs / 60000.0);
        return new AvailabilityRow(domain, pct, outageCount, downMin, longestMin, avg, p95, certDays);
    }

    private AvailabilitySummary summarize(List<AvailabilityRow> rows) {
        List<AvailabilityRow> withData = rows.stream().filter(r -> r.availabilityPct() != null).toList();
        Double avg = withData.isEmpty() ? null
                : Math.round(withData.stream().mapToDouble(AvailabilityRow::availabilityPct).average().orElse(0) * 100.0) / 100.0;
        // Domainlerden biri bile %100 değilse ortalama da yanıltıcı şekilde 100.00 gösterilmesin.
        if (avg != null && avg >= 100.0 && withData.stream().anyMatch(r -> r.availabilityPct() < 100.0)) avg = 99.99;
        AvailabilityRow best = withData.stream().max(Comparator.comparingDouble(AvailabilityRow::availabilityPct)).orElse(null);
        AvailabilityRow worst = withData.stream().min(Comparator.comparingDouble(AvailabilityRow::availabilityPct)).orElse(null);
        int downCount = (int) rows.stream().filter(r -> r.outageCount() > 0).count();
        Integer nearestCert = rows.stream().map(AvailabilityRow::certDaysRemaining)
                .filter(Objects::nonNull).min(Integer::compareTo).orElse(null);
        return new AvailabilitySummary(rows.size(), withData.size(), avg,
                best != null ? best.domain() : null, best != null ? best.availabilityPct() : null,
                worst != null ? worst.domain() : null, worst != null ? worst.availabilityPct() : null,
                downCount, nearestCert);
    }

    // ── Alıcı çözümü ──────────────────────────────────────────────────────────────

    private String[] resolveTo(Team team) {
        String email = team.getEmail() != null ? team.getEmail().trim() : "";
        if (!email.isBlank()) return new String[]{email};
        // Takım kutusu yoksa: CC adaylarından ilkini TO yap (aşağıda resolveCc bunu dışlamaz —
        // hiç alıcı kalmasın diye burada erkenden ele alıyoruz).
        List<String> cc = collectCc(team.getId());
        return cc.isEmpty() ? new String[0] : new String[]{cc.get(0)};
    }

    private String[] resolveCc(Long teamId, String[] to) {
        Set<String> exclude = new HashSet<>();
        for (String t : to) exclude.add(t.toLowerCase());
        List<String> cc = new ArrayList<>();
        for (String e : collectCc(teamId)) {
            if (exclude.add(e.toLowerCase())) cc.add(e);
        }
        return cc.toArray(new String[0]);
    }

    /** Haftalık raporun CC rolleri: PO → TECH (takımın eskalasyon kontağı, 2026-09-22) → MANAGER. CLEVEL bilinçli DIŞARIDA
     *  (yalnız kritik eskalasyonda devreye girer; rutin haftalık özet üst kademeye gitmez). */
    static final List<String> CC_ROLES = List.of("PO", "TECH", "MANAGER");

    /** Takımın PO + TECH + MANAGER eskalasyon kontak e-postaları (dedup); hiç kontak yoksa orgRole=PO kullanıcılar. */
    private List<String> collectCc(Long teamId) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String role : CC_ROLES) {
            for (EscalationContact c : contactRepo.findByTeamIdAndRoleAndActiveTrue(teamId, role)) {
                addEmail(out, seen, c.getEmail());
            }
        }
        if (out.isEmpty()) {
            for (AppUser u : userRepo.findByTeamIdAndOrgRoleAndActiveTrue(teamId, "PO")) {
                addEmail(out, seen, u.getEmail());
            }
        }
        return out;
    }

    private static void addEmail(List<String> out, Set<String> seen, String email) {
        if (email == null) return;
        String e = email.trim();
        if (!e.isBlank() && seen.add(e.toLowerCase())) out.add(e);
    }

    // ── Kayıt ─────────────────────────────────────────────────────────────────────

    private void saveNotificationLog(Team team, String[] to, String[] cc, String subject, String html, String status, String trigger) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L); // sentinel: alert kaynaklı DEĞİL (kolon NOT NULL) — aksi halde insert düşer, arşiv oluşmaz
            n.setSentAt(UTC_ISO.format(Instant.now()));
            n.setRecipientName(team.getName());
            n.setRecipientEmail(String.join(", ", to));
            n.setRecipientRole("COMBINED");
            n.setSubject(subject);
            n.setMessage(html);
            n.setEmailStatus(status);
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(trigger);
            n.setEmailFrom(emailService.getEmailFrom());
            if (cc != null && cc.length > 0) n.setCc(String.join(", ", cc));
            notificationLogRepo.save(n);
        } catch (Exception e) {
            log.warn("Haftalık erişilebilirlik NotificationLog kaydı başarısız: {}", e.getMessage());
        }
    }

    private void record(Long teamId, int year, int week, String status) {
        try {
            WeeklyAvailabilityLog rec = walRepo.findByTeamIdAndReportYearAndWeekNo(teamId, year, week)
                    .orElseGet(WeeklyAvailabilityLog::new);
            if (rec.getCreatedAt() == null) rec.setCreatedAt(UTC_ISO.format(Instant.now()));
            rec.setTeamId(teamId);
            rec.setReportYear(year);
            rec.setWeekNo(week);
            rec.setStatus(status);
            rec.setSentAt(UTC_ISO.format(Instant.now()));
            walRepo.save(rec);
        } catch (Exception e) {
            log.warn("weekly_availability_log kaydı başarısız (team={} {}-W{}): {}", teamId, year, week, e.getMessage());
        }
    }

    // ── Yardımcılar ────────────────────────────────────────────────────────────────

    private static boolean isUp(UptimeCheck c) { return "up".equalsIgnoreCase(c.getStatus()); }

    private static Long percentile(List<Long> sortedAsc, double p) {
        if (sortedAsc.isEmpty()) return null;
        int idx = (int) Math.ceil(p / 100.0 * sortedAsc.size()) - 1;
        idx = Math.max(0, Math.min(idx, sortedAsc.size() - 1));
        return sortedAsc.get(idx);
    }

    private static long durationMs(Instant from, Instant to) {
        if (from == null || to == null) return 0;
        long d = to.toEpochMilli() - from.toEpochMilli();
        return Math.max(0, d);
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Instant.parse(iso.endsWith("Z") ? iso : iso + "Z"); }
        catch (Exception e) { return null; }
    }
}

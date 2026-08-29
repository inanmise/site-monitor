package com.sitemonitor.service.report;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertInventoryReportLog;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.CertInventoryReportLogRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.EmailNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AYLIK sertifika envanteri raporu — ayın SON CUMA günü 10:00'da (Europe/Istanbul) sertifika
 * ekibine gider. Gövdede kalan süre tablosu + Aktif/Pasif/Silinmiş sayıları + envanter hijyen
 * bulguları; ekte envanterin tamamı CSV ve PDF olarak.
 *
 * <p>{@code WeeklyAvailabilityReportService} deseninin aylık eşi: aynı enabled/status/preview/
 * sendTest/history yüzeyi, aynı idempotency + notification_log arşivi yaklaşımı.
 * Cron ifadesi {@code SchedulerService}'te tanımlıdır ve dağıtık kilitle korunur.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateInventoryReportService {

    public static final String ENABLED_KEY = "site.monitor.cert-inventory-report.enabled";
    /** Sahibi takımların ADRESLERİNE EK olarak eklenecek adresler (ör. PKI ekibi). */
    public static final String EXTRA_TO_KEY = "site.monitor.cert-inventory-report.recipients";
    public static final String CC_KEY = "site.monitor.cert-inventory-report.cc";
    /** Zamanlama — ayarlar sayfasından CANLI değiştirilebilir (dinamik tetikleyici okur). */
    public static final String CRON_KEY = "site.monitor.cert-inventory-report.cron";
    /** Gövdedeki kalan süre tablosunda en fazla kaç domain listelenir (tamamı ekte). */
    private static final int MAX_TABLE_ROWS = 40;

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String[] MONTHS_TR = {
            "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
            "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık" };

    private final CertificateInventoryRepository inventoryRepo;
    private final com.sitemonitor.repository.TeamRepository teamRepo;
    private final CertInventoryReportLogRepository logRepo;
    private final NotificationLogRepository notificationLogRepo;
    private final InventoryExportService exportService;
    private final InventoryHygieneService hygieneService;
    private final CertificateService certificateService;
    private final EmailNotificationService emailService;
    private final AppSettingsService appSettings;

    @Value("${site.monitor.cert-inventory-report.enabled:true}")
    private boolean enabledDefault;

    @Value("${site.monitor.cert-inventory-report.cron:0 0 10 * * FRIL}")
    private String cronExpr;

    /** Gönderim sonucu — controller ve testler bunu okur. */
    public record SendResult(String status, int rows, int findings, String[] to, String subject) { }

    // ── Ayarlar ──────────────────────────────────────────────────────────────

    public boolean isEnabled() {
        return appSettings.getBoolean(ENABLED_KEY, enabledDefault);
    }

    public void setEnabled(boolean on, String actor) {
        appSettings.save(Map.of("values", Map.of(ENABLED_KEY, String.valueOf(on))), actor);
    }

    /**
     * Alıcılar OTOMATİK: envanterde sertifika sahibi olan TÜM takımların e-posta adresleri.
     *
     * <p>Rapor kişiye değil, <b>sahipliğe</b> gider: 100 sertifikanın sahibi 10 takımsa, tek bir
     * mail bu 10 takımın hepsine gider ve içinde envanterin TAMAMI vardır. Ekranda kullanıcı
     * yalnız kendi takımının kayıtlarını görür; rapor ise bütünü paylaşır ki eksik/yanlış kayıtlar
     * sahipleri tarafından fark edilip düzeltilsin.
     *
     * <p>Hem sorumlu takım (teamId) hem uygulama geliştirici takımı (ugTeamId) sahiptir.
     * {@link #EXTRA_TO_KEY} ile elle ek adres tanımlanabilir (ör. PKI ekibi); silinmiş kayıtların
     * takımları dahil EDİLMEZ.
     */
    public String[] recipients() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String e : ownerTeamEmails()) out.add(e);
        for (String e : split(appSettings.getString(EXTRA_TO_KEY, ""))) out.add(e);
        return out.toArray(String[]::new);
    }

    /** Sertifika sahibi takımların e-postaları (adres tanımsız takım sessizce atlanır). */
    public List<String> ownerTeamEmails() {
        var teamIds = new java.util.LinkedHashSet<Long>();
        for (CertificateInventory r : inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()) {
            if (r.getTeamId() != null) teamIds.add(r.getTeamId());
            if (r.getUgTeamId() != null) teamIds.add(r.getUgTeamId());
        }
        if (teamIds.isEmpty()) return List.of();
        return teamRepo.findAllById(teamIds).stream()
                .map(com.sitemonitor.model.Team::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * Sahip takım → adres eşlemesi, ayarlar sayfasında gösterilmek üzere.
     * Çıplak adres listesi "bu adresler nereye yazılı?" sorusunu doğuruyordu; takım adıyla
     * birlikte gösterilince kaynağın envanterdeki sahiplik olduğu bakar bakmaz anlaşılır.
     */
    public List<Map<String, Object>> ownerTeamRecipients() {
        var teamIds = new java.util.LinkedHashSet<Long>();
        for (CertificateInventory r : inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()) {
            if (r.getTeamId() != null) teamIds.add(r.getTeamId());
            if (r.getUgTeamId() != null) teamIds.add(r.getUgTeamId());
        }
        if (teamIds.isEmpty()) return List.of();
        return teamRepo.findAllById(teamIds).stream()
                .filter(t -> t.getEmail() != null && !t.getEmail().isBlank())
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("team", t.getName());
                    m.put("email", t.getEmail().trim());
                    return m;
                })
                .toList();
    }

    /** Sahibi olup e-posta adresi TANIMSIZ takımlar — ayarlar sayfasında uyarı olarak gösterilir. */
    public List<String> ownerTeamsWithoutEmail() {
        var teamIds = new java.util.LinkedHashSet<Long>();
        for (CertificateInventory r : inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()) {
            if (r.getTeamId() != null) teamIds.add(r.getTeamId());
            if (r.getUgTeamId() != null) teamIds.add(r.getUgTeamId());
        }
        if (teamIds.isEmpty()) return List.of();
        return teamRepo.findAllById(teamIds).stream()
                .filter(t -> t.getEmail() == null || t.getEmail().isBlank())
                .map(com.sitemonitor.model.Team::getName)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public String[] ccRecipients() { return split(appSettings.getString(CC_KEY, "")); }

    private static String[] split(String csv) {
        if (csv == null || csv.isBlank()) return new String[0];
        return Arrays.stream(csv.split("[,;]"))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toArray(String[]::new);
    }

    // ── Rapor üretimi ────────────────────────────────────────────────────────

    /** Rapor gövdesi + ekleri (önizleme ve gönderim aynı üreticiyi kullanır → önizleme sadıktır). */
    public Built build(LocalDate reportDate) {
        // Kapsam: SİLİNMEMİŞ kayıtlar. Silinmişler raporda hiç yer almaz — ne satır ne sayaç
        // olarak; sahibinden bir aksiyon beklenmeyen kayıtlar raporu gürültülendiriyordu.
        List<CertificateInventory> rows = exportService.reportRows();
        Map<Long, String> teams = exportService.teamNames(rows);
        Map<String, Integer> counts = hygieneService.counts(rows);

        Map<String, CertificateDto> latest;
        try {
            latest = certificateService.getAllLatest().stream()
                    .filter(d -> d.getDomain() != null)
                    .collect(Collectors.toMap(CertificateDto::getDomain, d -> d, (a, b) -> a));
        } catch (Exception e) {
            log.warn("Aylık rapor: canlı kontrol sonuçları okunamadı: {}", e.getMessage());
            latest = Map.of();
        }

        // Kalan süresi en az olan üstte — okur önce riskli olanı görsün.
        final Map<String, CertificateDto> lat = latest;
        List<EmailNotificationService.InventoryReportRow> tableRows = rows.stream()
                .filter(r -> !Boolean.FALSE.equals(r.getActive()))
                .map(r -> {
                    CertificateDto d = lat.get(r.getDomain());
                    return new EmailNotificationService.InventoryReportRow(
                            r.getDomain(),
                            r.getTeamId() == null ? null : teams.get(r.getTeamId()),
                            r.getTier(),
                            d == null ? null : d.getDaysRemaining(),
                            d == null ? null : shortDate(d.getNotAfter()),
                            d == null ? "kontrol edilmemiş" : statusText(d));
                })
                .sorted(Comparator.comparing(
                        (EmailNotificationService.InventoryReportRow r) -> r.daysRemaining() == null
                                ? Integer.MAX_VALUE : r.daysRemaining()))
                .limit(MAX_TABLE_ROWS)
                .toList();

        InventoryHygieneService.Result hygiene = hygieneService.analyze(rows);
        List<EmailNotificationService.InventoryFindingGroup> findings = hygiene.groups().stream()
                .map(g -> new EmailNotificationService.InventoryFindingGroup(
                        g.title(), g.total(),
                        g.samples().stream().map(f -> new String[]{ f.domain(), f.detail() }).toList(),
                        g.hidden()))
                .toList();

        int year = reportDate.getYear(), month = reportDate.getMonthValue();
        Map<String, String> names = InventoryExportService.fileNames(year, month);
        String monthLabel = MONTHS_TR[month - 1] + " " + year;

        String html = emailService.buildCertInventoryReportHtml(
                monthLabel, counts, tableRows, findings, List.of(names.get("csv"), names.get("pdf")));

        List<EmailNotificationService.MailAttachment> attachments = List.of(
                new EmailNotificationService.MailAttachment(
                        names.get("csv"), exportService.csv(rows, teams), "text/csv"),
                new EmailNotificationService.MailAttachment(
                        names.get("pdf"), exportService.pdf(rows, teams), "application/pdf"));

        String subject = "[Site Monitor] Sertifika Envanteri Raporu · " + monthLabel
                + " · " + counts.getOrDefault("active", 0) + " aktif"
                + (hygiene.clean() ? "" : " · " + hygiene.totalFindings() + " bulgu");

        return new Built(subject, html, attachments, rows.size(), hygiene.totalFindings(), monthLabel);
    }

    public record Built(String subject, String html,
                        List<EmailNotificationService.MailAttachment> attachments,
                        int rowCount, int findingCount, String monthLabel) { }

    // ── Gönderim ─────────────────────────────────────────────────────────────

    /**
     * @param force true → "etkin mi" ve idempotency kontrollerini atlar (elle tetikleme)
     */
    @Transactional
    public SendResult sendMonthlyReport(boolean force) {
        if (!force && !isEnabled()) {
            log.info("Aylık envanter raporu kapalı ({}=false) — atlanıyor", ENABLED_KEY);
            return new SendResult("DISABLED", 0, 0, new String[0], null);
        }
        LocalDate today = LocalDate.now(IST);
        int year = today.getYear(), month = today.getMonthValue();

        // İdempotenslik AY değil GÜN bazlıdır. Guard'ın amacı çok-pod'da kilit kaçarsa ikinci
        // mailin gitmemesi (aynı dakikada iki tetik = aynı gün); "bu ay bir kez" DEĞİL.
        // Ay bazlı olduğu sürece ay içinde yapılan MANUEL bir gönderim, ayın SON CUMA'sındaki
        // planlı gönderimi sessizce iptal ediyordu — oysa son cuma raporu ayın kapanış hâlidir
        // ve her koşulda gitmelidir. Tek satırlık (yıl, ay) şeması korunur; ayrım sentAt'ten gelir.
        if (!force && sentToday(year, month)) {
            log.info("Aylık envanter raporu {}-{} BUGÜN zaten gönderilmiş — atlanıyor", year, month);
            return new SendResult("ALREADY_SENT", 0, 0, new String[0], null);
        }

        String[] to = recipients();
        Built built = build(today);
        if (to.length == 0) {
            log.warn("Aylık envanter raporu: sertifika sahibi takımlarda e-posta adresi yok "
                    + "ve ek alıcı ({}) tanımlı değil — gönderilmedi", EXTRA_TO_KEY);
            record(year, month, "NO_RECIPIENT", built, "");
            return new SendResult("NO_RECIPIENT", built.rowCount(), built.findingCount(), to, built.subject());
        }

        String[] cc = ccRecipients();
        String status = emailService.sendHtmlWithAttachments(to, cc.length > 0 ? cc : null,
                built.subject(), built.html(), null, built.attachments());

        archive(built, to, cc, status, "CERT_INVENTORY_REPORT");
        record(year, month, status.startsWith("FAILED") ? "FAILED" : "SENT", built, String.join(", ", to));
        log.info("Aylık envanter raporu gönderildi: {} → {} ({} kayıt, {} bulgu)",
                built.monthLabel(), Arrays.toString(to), built.rowCount(), built.findingCount());
        return new SendResult(status, built.rowCount(), built.findingCount(), to, built.subject());
    }

    /** Ayarlar sayfasındaki "şimdi test gönder" — idempotency kaydı YAZILMAZ. */
    public SendResult sendTest(String email) {
        String[] to = { email };
        Built built = build(LocalDate.now(IST));
        String status = emailService.sendHtmlWithAttachments(to, null,
                "[TEST] " + built.subject(), built.html(), null, built.attachments());
        archive(built, to, new String[0], status, "CERT_INVENTORY_REPORT_TEST");
        return new SendResult(status, built.rowCount(), built.findingCount(), to, built.subject());
    }

    /** Önizleme — gönderimle AYNI üreticiden HTML (sadık önizleme). */
    public String preview() {
        return build(LocalDate.now(IST)).html();
    }

    // ── Durum / geçmiş ───────────────────────────────────────────────────────

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", isEnabled());
        m.put("owner_emails", ownerTeamEmails());                 // otomatik alıcılar (salt-okunur)
        m.put("owner_recipients", ownerTeamRecipients());         // takım adıyla birlikte (kaynağı görünsün)
        m.put("teams_without_email", ownerTeamsWithoutEmail());   // uyarı: bu takımlara ulaşılamıyor
        m.put("extra_recipients", appSettings.getString(EXTRA_TO_KEY, ""));
        m.put("recipients", String.join(", ", recipients()));
        m.put("cc", String.join(", ", ccRecipients()));
        m.put("cron", cron());
        m.put("next_run", nextRun());
        m.put("next_runs", nextRuns(3));
        m.put("inventory_total", inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc().size());
        m.put("last_run", logRepo.findFirstByStatusOrderBySentAtDesc("SENT").map(this::toMap).orElse(null));
        return m;
    }

    /** CANLI cron — ayarlar sayfasından değiştirilebilir; properties yalnız ilk varsayılan. */
    public String cron() {
        String v = appSettings.getString(CRON_KEY, cronExpr);
        return (v == null || v.isBlank()) ? cronExpr : v.trim();
    }

    /**
     * Zamanlamayı kaydeder. Geçersiz ifade REDDEDİLİR — kabul edilseydi tetikleyici sessizce
     * hiç çalışmaz ve rapor aylarca gitmezdi (fark edilmesi zor bir arıza).
     */
    public void setCron(String expr, String actor) {
        String v = expr == null ? "" : expr.trim();
        if (v.isEmpty()) throw new IllegalArgumentException("Zamanlama boş olamaz");
        try {
            CronExpression.parse(v);
        } catch (Exception e) {
            throw new IllegalArgumentException("Geçersiz zamanlama ifadesi: " + e.getMessage());
        }
        appSettings.save(Map.of("values", Map.of(CRON_KEY, v)), actor);
        log.info("Aylık envanter raporu zamanlaması değişti ({}): {}", actor, v);
    }

    /** Sonraki çalışma zamanı — CANLI cron'dan hesaplanır. */
    public String nextRun() {
        try {
            ZonedDateTime next = CronExpression.parse(cron()).next(ZonedDateTime.now(IST));
            return next == null ? null : next.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        } catch (Exception e) {
            log.warn("Cron ifadesi çözümlenemedi ({}): {}", cron(), e.getMessage());
            return null;
        }
    }

    public List<Map<String, Object>> history(int limit) {
        return logRepo.findAllByOrderBySentAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
                .stream().map(this::toMap).collect(Collectors.toList());
    }

    private Map<String, Object> toMap(CertInventoryReportLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("year", l.getReportYear());
        m.put("month", l.getMonthNo());
        m.put("month_label", l.getMonthNo() != null && l.getMonthNo() >= 1 && l.getMonthNo() <= 12
                ? MONTHS_TR[l.getMonthNo() - 1] + " " + l.getReportYear() : null);
        m.put("status", l.getStatus());
        m.put("rows", l.getRowCount());
        m.put("findings", l.getFindingCount());
        m.put("recipients", l.getRecipients());
        m.put("sent_at", l.getSentAt());
        return m;
    }

    // ── Kayıt ────────────────────────────────────────────────────────────────

    /**
     * Bu ayın rapor satırı BUGÜN (Europe/Istanbul) mü damgalanmış?
     *
     * <p>{@code sentAt} UTC yazılıyor ({@code UTC_ISO}, ofset son eki YOK) ama cron IST'e göre
     * koşuyor; karşılaştırma IST gününde yapılmazsa gece yarısına yakın gönderimler yanlış güne
     * düşer. Ayrıştırılamayan bir damga gönderimi ENGELLEMEZ — guard bir kolaylık, kapı değil.
     */
    private boolean sentToday(int year, int month) {
        return logRepo.findByReportYearAndMonthNo(year, month)
                .map(CertInventoryReportLog::getSentAt)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> {
                    try {
                        return java.time.LocalDateTime.parse(v)
                                .atZone(ZoneOffset.UTC).withZoneSameInstant(IST).toLocalDate()
                                .equals(LocalDate.now(IST));
                    } catch (Exception e) {
                        return false;
                    }
                })
                .orElse(false);
    }

    private void record(int year, int month, String status, Built built, String recipients) {
        try {
            CertInventoryReportLog l = logRepo.findByReportYearAndMonthNo(year, month)
                    .orElseGet(CertInventoryReportLog::new);
            l.setReportYear(year);
            l.setMonthNo(month);
            l.setStatus(status);
            l.setRowCount(built.rowCount());
            l.setFindingCount(built.findingCount());
            l.setRecipients(recipients);
            l.setSentAt(UTC_ISO.format(Instant.now()));
            if (l.getCreatedAt() == null) l.setCreatedAt(l.getSentAt());
            logRepo.save(l);
        } catch (Exception e) {
            log.warn("Aylık rapor kaydı yazılamadı: {}", e.getMessage());
        }
    }

    /** Gönderilen mailin gövdesini notification_log'a arşivler (Bildirim Geçmişi'nde görünür). */
    private void archive(Built built, String[] to, String[] cc, String status, String trigger) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);                 // sentinel — NOT NULL kolon, rapor maillerinin alarmı yok
            n.setRecipientEmail(String.join(", ", to));
            n.setRecipientName("Sertifika Ekibi");
            n.setRecipientRole("REPORT");
            n.setCc(cc.length > 0 ? String.join(", ", cc) : null);
            n.setSubject(built.subject());
            n.setMessage(built.html());
            n.setEmailStatus(status);
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(trigger);
            n.setEmailFrom(emailService.fromAddress());
            n.setSentAt(UTC_ISO.format(Instant.now()));
            notificationLogRepo.save(n);
        } catch (Exception e) {
            log.warn("Aylık rapor arşivi yazılamadı: {}", e.getMessage());
        }
    }

    // ── küçük yardımcılar ────────────────────────────────────────────────────

    private static String shortDate(String iso) {
        if (iso == null || iso.length() < 10) return null;
        return iso.substring(8, 10) + "." + iso.substring(5, 7) + "." + iso.substring(0, 4);
    }

    private static String statusText(CertificateDto d) {
        if ("error".equalsIgnoreCase(d.getStatus())) return "hata";
        if (d.getDaysRemaining() != null && d.getDaysRemaining() < 0) return "süresi dolmuş";
        if ("REVOKED".equalsIgnoreCase(d.getRevocationStatus())) return "iptal";
        if ("BROKEN".equalsIgnoreCase(d.getChainStatus())) return "zincir kırık";
        if ("INCOMPLETE".equalsIgnoreCase(d.getDeploymentStatus())) return "dağıtım eksik";
        return "geçerli";
    }

    /** Locale bağımsız küçük harf (Türkçe İ tuzağı) — dışarıdan gelen karşılaştırmalar için. */
    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    /** Test/tanı: cron ifadesinin sıradaki N çalışmasını döner. */
    public List<String> nextRuns(int n) {
        List<String> out = new ArrayList<>();
        try {
            CronExpression cron = CronExpression.parse(cronExpr);
            ZonedDateTime cursor = ZonedDateTime.now(IST);
            for (int i = 0; i < n; i++) {
                cursor = cron.next(cursor);
                if (cursor == null) break;
                out.add(cursor.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm (EEEE)", new Locale("tr", "TR"))));
            }
        } catch (Exception ignored) { /* geçersiz cron → boş liste */ }
        return out;
    }
}

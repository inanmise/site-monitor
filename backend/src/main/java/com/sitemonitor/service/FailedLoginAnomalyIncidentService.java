package com.sitemonitor.service;

import com.sitemonitor.model.LoginAnomalyIncident;
import com.sitemonitor.repository.LoginAnomalyIncidentRepository;
import com.sitemonitor.service.FailedLoginAnomalyService.AnomalyReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Başarısız-login anomali ORKESTRASYONU: pencere kurulumu (lastScanAt catch-up), detektörü çağırma,
 * incident lifecycle (aç / escalate / realert / resolve) ve cooldown/bastırma. E-posta gönderimi
 * asenkron {@link SecurityMailDispatcher}'a delege edilir (scheduler thread bloke olmaz).
 *
 * <p>Bombardıman önleme: aynı anda TEK açık incident. Anomali sürerken cooldown içinde YENİ mail
 * atılmaz; ancak (a) yeni kural tetiklenirse veya (b) hacim zirvenin ≥2 katına çıkarsa bastırma delinir
 * (ESCALATION). Cooldown dolduysa REALERT. Anomali bitince RESOLVED (config ile).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailedLoginAnomalyIncidentService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final FailedLoginAnomalyService detector;
    private final LoginAnomalyIncidentRepository incidentRepo;
    private final SecurityMailDispatcher dispatcher;
    private final AppSettingsService appSettings;
    private final JdbcTemplate jdbcTemplate;

    @Value("${site.monitor.failed-login.window-minutes:10}")      int windowMinutesDefault;
    @Value("${site.monitor.failed-login.catchup-cap-minutes:60}") int catchupCapDefault;
    @Value("${site.monitor.failed-login.cooldown-minutes:60}")    int cooldownMinutesDefault;
    @Value("${site.monitor.system-admin.email:}")                 String systemAdminEmail;
    /** Tarama aralığı (zamanlayıcıyla aynı anahtar) — "yakın zamanda tarandı" kapısı için; 0 / eksi = kapı yok. */
    @Value("${site.monitor.failed-login.scan-ms:600000}")         long scanMs;

    /**
     * Uyarı gönderim DENEMESİ koruması (2026-10-21, çok pod R1 / MP-10): son deneme bundan gençse REALERT gönderilmez —
     * ilk posta hâlâ yolda (eşzamansız dağıtıcı, yavaş SMTP / 421) ya da az önce düştü. Düşen posta bir sonraki
     * taramada (≥ 10 dk) yeniden denenir — eski davranışla aynı kadans.
     */
    static final java.time.Duration ATTEMPT_GUARD = java.time.Duration.ofMinutes(10);
    /** "Yakın zamanda tarandı" kapısının payı: aralıktan bu kadar erken gelen tur da başka pod'un turunun tekrarı sayılır. */
    static final long RECENT_SCAN_SLACK_MS = 5_000L;

    public record Window(String start, String end, int nominalMinutes) {}

    // ── Ana giriş (scheduler'dan çağrılır) ───────────────────────────────────────
    public void scan() {
        if (!detector.isEnabled()) return;
        Instant now = Instant.now();
        String lastScanAt = readLastScanAt();
        // ÇOK POD (2026-10-21, R1 / MP-10): zamanlayıcı her pod'da fixedDelay ile koşar ve kilit tarama bitince bırakılır —
        // N pod aralık başına N tarama ve üst üste binen pencereler demekti (uçuştaki İLK postanın arkasından REALERT).
        // Kilidin İÇİNDE okunan ortak damga: başka bir pod bu aralıkta zaten taradıysa bu tur atlanır.
        if (recentlyScanned(lastScanAt, now)) {
            log.debug("Başarısız-login anomali taraması atlandı — son tarama {} (aralık içinde, başka pod taramış)", lastScanAt);
            return;
        }
        Window w = computeWindow(lastScanAt, now);
        AnomalyReport report = detector.evaluate(w.start(), w.end(), w.nominalMinutes());
        handle(report, now);
        writeLastScanAt(w.end());
    }

    /** Son tarama bu aralığın içinde mi ({@code last > now − scanMs + pay})? Damga okunamazsa / kapı kapalıysa false. */
    boolean recentlyScanned(String lastScanAt, Instant now) {
        if (scanMs <= 0 || lastScanAt == null || lastScanAt.isBlank()) return false;
        try {
            Instant last = Instant.from(ISO.parse(lastScanAt));
            return last.isAfter(now.minusMillis(scanMs - RECENT_SCAN_SLACK_MS));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Değerlendirme penceresi = (windowStart, now]. Pencere-kaçırmama: lastScanAt nominal başlangıçtan
     * önceyse geriye uzat (uygulama kapalıyken biriken olaylar da değerlendirilir); catchup-cap ile sınırla.
     */
    Window computeWindow(String lastScanAt, Instant now) {
        int windowMinutes = appSettings.getInt("site.monitor.failed-login.window-minutes", windowMinutesDefault);
        int catchupCap    = appSettings.getInt("site.monitor.failed-login.catchup-cap-minutes", catchupCapDefault);
        Instant start = now.minusSeconds(windowMinutes * 60L);
        if (lastScanAt != null && !lastScanAt.isBlank()) {
            try {
                Instant last = Instant.from(ISO.parse(lastScanAt));
                if (last.isBefore(start)) start = last;                       // catch-up
            } catch (Exception ignore) { /* parse edilemezse nominal pencere */ }
        }
        Instant capFloor = now.minusSeconds(catchupCap * 60L);
        if (start.isBefore(capFloor)) start = capFloor;                       // cap
        return new Window(ISO.format(start), ISO.format(now), windowMinutes);
    }

    /** Incident lifecycle + e-posta kararı. */
    void handle(AnomalyReport report, Instant now) {
        String nowIso = ISO.format(now);
        Optional<LoginAnomalyIncident> openOpt = incidentRepo.findFirstByResolvedFalseOrderByOpenedAtDesc();

        if (report.anomalous()) {
            String[] recipients = resolveRecipients();
            if (openOpt.isEmpty()) {
                LoginAnomalyIncident inc = new LoginAnomalyIncident();
                inc.setOpenedAt(nowIso);
                inc.setResolved(false);
                inc.setPeakTotal(report.total());
                inc.setRulesSignature(report.rulesSignature());
                inc.setRuleCount(report.hits().size());
                inc.setLastWindowStart(report.windowStart());
                inc.setLastWindowEnd(report.windowEnd());
                inc.setSummary(summarize(report));
                inc.setRealertCount(0);
                inc.setLastAlertAttemptAt(nowIso);   // gönderim denemesi dağıtımdan ÖNCE damgalanır (MP-10)
                LoginAnomalyIncident saved = incidentRepo.save(inc);
                dispatcher.dispatchAlert(recipients, report, "INITIAL", saved.getId());
            } else {
                LoginAnomalyIncident inc = openOpt.get();
                boolean newRule       = signatureGrew(inc.getRulesSignature(), report.rulesSignature());
                boolean volumeDoubled = report.total() >= 2L * Math.max(1L, inc.getPeakTotal());
                inc.setPeakTotal(Math.max(inc.getPeakTotal(), report.total()));
                inc.setRulesSignature(mergeSignatures(inc.getRulesSignature(), report.rulesSignature()));
                inc.setRuleCount(report.hits().size());
                inc.setLastWindowStart(report.windowStart());
                inc.setLastWindowEnd(report.windowEnd());
                inc.setSummary(summarize(report));

                if (newRule || volumeDoubled) {
                    inc.setRealertCount(inc.getRealertCount() + 1);
                    inc.setLastAlertAttemptAt(nowIso);
                    incidentRepo.save(inc);
                    dispatcher.dispatchAlert(recipients, report, "ESCALATION", inc.getId());
                } else if (cooldownElapsed(inc.getLastAlertAt(), nowIso)
                        && !attemptRecent(inc.getLastAlertAttemptAt(), nowIso)) {
                    inc.setRealertCount(inc.getRealertCount() + 1);
                    inc.setLastAlertAttemptAt(nowIso);
                    incidentRepo.save(inc);
                    dispatcher.dispatchAlert(recipients, report, "REALERT", inc.getId());
                } else {
                    incidentRepo.save(inc);   // bastır — yalnız durumu güncelle, mail YOK
                }
            }
        } else if (openOpt.isPresent()) {
            LoginAnomalyIncident inc = openOpt.get();
            inc.setResolved(true);
            inc.setResolvedAt(nowIso);
            incidentRepo.save(inc);
            if (appSettings.getBoolean("site.monitor.failed-login.resolved-email-enabled", true)) {
                dispatcher.dispatchResolved(resolveRecipients(), inc.getOpenedAt(), nowIso, inc.getPeakTotal(), inc.getId());
            }
        }
    }

    /** Alıcılar — canlı CSV; boşsa sistem-admin adresine düşer. */
    String[] resolveRecipients() {
        List<String> clean = appSettings.getCsv("site.monitor.failed-login.alert-recipients", "").stream()
                .map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        if (clean.isEmpty()) {
            String admin = appSettings.getString("site.monitor.system-admin.email", systemAdminEmail);
            if (admin != null && !admin.isBlank()) clean = List.of(admin.trim());
        }
        return clean.toArray(new String[0]);
    }

    boolean cooldownElapsed(String lastAlertAt, String nowIso) {
        if (lastAlertAt == null || lastAlertAt.isBlank()) return true;   // henüz başarılı mail yok → izin ver
        int cooldownMinutes = appSettings.getInt("site.monitor.failed-login.cooldown-minutes", cooldownMinutesDefault);
        try {
            Instant last = Instant.from(ISO.parse(lastAlertAt));
            Instant now  = Instant.from(ISO.parse(nowIso));
            return !now.isBefore(last.plusSeconds(cooldownMinutes * 60L));
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Son gönderim DENEMESİ {@link #ATTEMPT_GUARD}'dan genç mi (MP-10)? Başarılı damga ({@code lastAlertAt}) yalnız gönderim
     * BİTİNCE yazılır; eskiden ilk posta yoldayken / az önce düşmüşken {@code cooldownElapsed(null)} true dönüyor ve
     * ikinci tarama hemen REALERT gönderiyordu. Okunamazsa false (eski davranış).
     */
    boolean attemptRecent(String lastAttemptAt, String nowIso) {
        if (lastAttemptAt == null || lastAttemptAt.isBlank()) return false;
        try {
            Instant last = Instant.from(ISO.parse(lastAttemptAt));
            Instant now  = Instant.from(ISO.parse(nowIso));
            return now.isBefore(last.plus(ATTEMPT_GUARD));
        } catch (Exception e) {
            return false;
        }
    }

    private static String summarize(AnomalyReport r) {
        return r.total() + " başarısız login / " + r.windowMinutes() + "dk · " + r.rulesSignature();
    }

    private static Set<String> codeSet(String sig) {
        Set<String> s = new TreeSet<>();
        if (sig != null) for (String c : sig.split(",")) if (!c.isBlank()) s.add(c.trim());
        return s;
    }
    private static boolean signatureGrew(String oldSig, String newSig) {
        return !codeSet(oldSig).containsAll(codeSet(newSig));
    }
    private static String mergeSignatures(String a, String b) {
        Set<String> s = codeSet(a); s.addAll(codeSet(b));
        return String.join(",", s);
    }

    // ── lastScanAt kalıcılığı (login_anomaly_state, tek satır id=1) ──────────────
    private String readLastScanAt() {
        try {
            List<String> rows = jdbcTemplate.query(
                    "SELECT last_scan_at FROM login_anomaly_state WHERE id = 1",
                    (rs, i) -> rs.getString(1));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }
    private void writeLastScanAt(String iso) {
        try {
            int updated = jdbcTemplate.update("UPDATE login_anomaly_state SET last_scan_at = ? WHERE id = 1", iso);
            if (updated == 0)
                jdbcTemplate.update("INSERT INTO login_anomaly_state(id, last_scan_at) VALUES (1, ?)", iso);
        } catch (Exception e) {
            log.warn("login_anomaly_state yazılamadı: {}", e.getMessage());
        }
    }
}

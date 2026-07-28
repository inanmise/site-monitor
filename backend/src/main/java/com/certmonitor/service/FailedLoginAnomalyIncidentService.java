package com.certmonitor.service;

import com.certmonitor.model.LoginAnomalyIncident;
import com.certmonitor.repository.LoginAnomalyIncidentRepository;
import com.certmonitor.service.FailedLoginAnomalyService.AnomalyReport;
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

    @Value("${cert.monitor.failed-login.window-minutes:10}")      int windowMinutesDefault;
    @Value("${cert.monitor.failed-login.catchup-cap-minutes:60}") int catchupCapDefault;
    @Value("${cert.monitor.failed-login.cooldown-minutes:60}")    int cooldownMinutesDefault;
    @Value("${cert.monitor.system-admin.email:}")                 String systemAdminEmail;

    public record Window(String start, String end, int nominalMinutes) {}

    // ── Ana giriş (scheduler'dan çağrılır) ───────────────────────────────────────
    public void scan() {
        if (!detector.isEnabled()) return;
        Instant now = Instant.now();
        Window w = computeWindow(readLastScanAt(), now);
        AnomalyReport report = detector.evaluate(w.start(), w.end(), w.nominalMinutes());
        handle(report, now);
        writeLastScanAt(w.end());
    }

    /**
     * Değerlendirme penceresi = (windowStart, now]. Pencere-kaçırmama: lastScanAt nominal başlangıçtan
     * önceyse geriye uzat (uygulama kapalıyken biriken olaylar da değerlendirilir); catchup-cap ile sınırla.
     */
    Window computeWindow(String lastScanAt, Instant now) {
        int windowMinutes = appSettings.getInt("cert.monitor.failed-login.window-minutes", windowMinutesDefault);
        int catchupCap    = appSettings.getInt("cert.monitor.failed-login.catchup-cap-minutes", catchupCapDefault);
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
                    incidentRepo.save(inc);
                    dispatcher.dispatchAlert(recipients, report, "ESCALATION", inc.getId());
                } else if (cooldownElapsed(inc.getLastAlertAt(), nowIso)) {
                    inc.setRealertCount(inc.getRealertCount() + 1);
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
            if (appSettings.getBoolean("cert.monitor.failed-login.resolved-email-enabled", true)) {
                dispatcher.dispatchResolved(resolveRecipients(), inc.getOpenedAt(), nowIso, inc.getPeakTotal(), inc.getId());
            }
        }
    }

    /** Alıcılar — canlı CSV; boşsa sistem-admin adresine düşer. */
    String[] resolveRecipients() {
        List<String> clean = appSettings.getCsv("cert.monitor.failed-login.alert-recipients", "").stream()
                .map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        if (clean.isEmpty()) {
            String admin = appSettings.getString("cert.monitor.system-admin.email", systemAdminEmail);
            if (admin != null && !admin.isBlank()) clean = List.of(admin.trim());
        }
        return clean.toArray(new String[0]);
    }

    boolean cooldownElapsed(String lastAlertAt, String nowIso) {
        if (lastAlertAt == null || lastAlertAt.isBlank()) return true;   // henüz başarılı mail yok → izin ver
        int cooldownMinutes = appSettings.getInt("cert.monitor.failed-login.cooldown-minutes", cooldownMinutesDefault);
        try {
            Instant last = Instant.from(ISO.parse(lastAlertAt));
            Instant now  = Instant.from(ISO.parse(nowIso));
            return !now.isBefore(last.plusSeconds(cooldownMinutes * 60L));
        } catch (Exception e) {
            return true;
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

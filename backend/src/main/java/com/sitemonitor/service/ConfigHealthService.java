package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * "Yapılandırma sağlığı" kartı (2026-09-12, zenginleştirme #25): Ayarlar 12 sekmeye dağılmış — kırmızı
 * olan ne, tek kartta. Her kontrol {@code {key, status: ok|warn|bad|off, detail, tab}} döner; {@code tab}
 * Ayarlar menüsündeki bölüm kimliğidir (arayüz doğrudan oraya götürür). Her kontrol try/catch'li: biri
 * düşerse kart yine çizilir (o satır {@code bad} + hata metni).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfigHealthService {

    private final SmtpSettingsService smtpSettings;
    private final LdapSettingsService ldapSettings;
    private final UserPushService userPushService;
    private final AppSettingsService appSettings;
    private final AuditLogRepository auditLogRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final SchedulerService schedulerService;

    @Value("${site.monitor.weekly-report.reminder-cron:0 0 9 * * *}")
    private String reminderCron;
    @Value("${site.monitor.weekly-report.reminder-enabled:true}")
    private boolean reminderEnabled;

    public record Check(String key, String status, String detail, String tab) {}

    public Map<String, Object> build() {
        List<Check> checks = new ArrayList<>();
        checks.add(safe("smtp", "smtp", this::smtp));
        checks.add(safe("ldap", "ldap", this::ldap));
        checks.add(safe("push", "userpush", this::push));
        checks.add(safe("reminder", "general", this::reminder));
        checks.add(safe("base_url", "general", this::baseUrl));
        checks.add(safe("admin_email", "general", this::adminEmail));
        checks.add(safe("unowned", "inventory", this::unowned));
        checks.add(safe("weak_algo", "weakalgo", this::weakAlgo));
        checks.add(safe("scheduler", "health", this::scheduler));

        long bad = checks.stream().filter(c -> "bad".equals(c.status())).count();
        long warn = checks.stream().filter(c -> "warn".equals(c.status())).count();
        long ok = checks.stream().filter(c -> "ok".equals(c.status())).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c.key()); m.put("status", c.status()); m.put("detail", c.detail()); m.put("tab", c.tab());
            return m;
        }).toList());
        out.put("bad", bad); out.put("warn", warn); out.put("ok", ok);
        out.put("overall", bad > 0 ? "bad" : warn > 0 ? "warn" : "ok");
        return out;
    }

    private interface Probe { Check run(); }

    private Check safe(String key, String tab, Probe p) {
        try { return p.run(); }
        catch (Exception e) {
            log.debug("config-health {} düştü: {}", key, e.toString());
            return new Check(key, "bad", "error:" + e.getClass().getSimpleName(), tab);
        }
    }

    // ── Kontroller ─────────────────────────────────────────────────────────────────────────

    private Check smtp() {
        var s = smtpSettings.getOrDefaults();
        if (!Boolean.TRUE.equals(s.getEnabled())) return new Check("smtp", "off", "disabled", "smtp");
        if (isBlank(s.getHost())) return new Check("smtp", "bad", "no_host", "smtp");
        Optional<AuditLog> last = auditLogRepo.findTopByEventTypeOrderByEventTimeDesc("SMTP_TEST");
        if (last.isEmpty()) return new Check("smtp", "warn", "never_tested", "smtp");
        boolean okTest = last.get().getOutcome() == null || !last.get().getOutcome().toUpperCase(Locale.ROOT).contains("FAIL");
        return new Check("smtp", okTest ? "ok" : "bad", (okTest ? "tested_ok:" : "tested_fail:") + last.get().getEventTime(), "smtp");
    }

    private Check ldap() {
        var s = ldapSettings.getOrDefaults();
        if (!Boolean.TRUE.equals(s.getEnabled())) return new Check("ldap", "off", "disabled", "ldap");
        if (isBlank(s.getHost())) return new Check("ldap", "bad", "no_host", "ldap");
        Optional<AuditLog> last = auditLogRepo.findTopByEventTypeOrderByEventTimeDesc("LDAP_TEST");
        if (last.isEmpty()) return new Check("ldap", "warn", "never_tested", "ldap");
        boolean okTest = last.get().getOutcome() == null || !last.get().getOutcome().toUpperCase(Locale.ROOT).contains("FAIL");
        return new Check("ldap", okTest ? "ok" : "bad", (okTest ? "tested_ok:" : "tested_fail:") + last.get().getEventTime(), "ldap");
    }

    private Check push() {
        Map<String, Object> h = userPushService.healthSnapshot();
        if (!Boolean.TRUE.equals(h.get("enabled"))) return new Check("push", "off", "disabled", "userpush");
        if (isBlank(appSettings.getString("site.monitor.userpush.url", null))) return new Check("push", "bad", "no_url", "userpush");
        if (Boolean.TRUE.equals(h.get("circuit_open"))) return new Check("push", "bad", "circuit_open", "userpush");
        Object cf = h.get("consecutive_failures");
        if (cf instanceof Number n && n.intValue() > 0) return new Check("push", "warn", "failures:" + n.intValue(), "userpush");
        return new Check("push", "ok", "ready", "userpush");
    }

    /** Hatırlatma cron'unun gün alanı son giriş gününü kapsıyor mu (bugün eklenen tuzak). */
    private Check reminder() {
        if (!reminderEnabled) return new Check("reminder", "off", "disabled", "general");
        WeeklyReportDeadline d = WeeklyReportDeadline.resolve(appSettings);
        if (!d.valid()) return new Check("reminder", "warn", "deadline_invalid", "general");
        if (!cronCoversDay(reminderCron, d.day())) return new Check("reminder", "bad", "cron_misses_deadline:" + reminderCron + "|" + d.dayCode(), "general");
        return new Check("reminder", "ok", d.dayCode() + " " + d.timeText(), "general");
    }

    /** Spring cron (6 alan): gün-of-week alanı '*' / '?' / gün adı ya da numarası (0-7, MON-SUN) — deadline gününü kapsıyor mu. */
    static boolean cronCoversDay(String cron, DayOfWeek day) {
        if (cron == null) return true;
        String[] f = cron.trim().split("\\s+");
        if (f.length < 6) return true;
        String dow = f[5].toUpperCase(Locale.ROOT);
        if (dow.equals("*") || dow.equals("?")) return true;
        String code = day.name().substring(0, 3);
        int num = day.getValue() % 7;   // Spring: 0/7 = SUN, 1 = MON … 6 = SAT
        for (String part : dow.split(",")) {
            String p = part.trim();
            if (p.contains("-")) {
                String[] r = p.split("-");
                Integer a = dowNum(r[0]), b = dowNum(r[1]);
                if (a != null && b != null && a <= num && num <= b) return true;
            } else if (p.contains("/")) {
                return true;   // adım ifadesi — kapsam kabul (yanlış alarm üretme)
            } else {
                Integer n = dowNum(p);
                if (n != null && n == num) return true;
                if (p.equals(code)) return true;
            }
        }
        return false;
    }

    private static Integer dowNum(String s) {
        String t = s.trim().toUpperCase(Locale.ROOT);
        String[] names = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
        for (int i = 0; i < names.length; i++) if (names[i].equals(t)) return i;
        try { int n = Integer.parseInt(t); return n == 7 ? 0 : n; } catch (NumberFormatException e) { return null; }
    }

    private Check baseUrl() {
        String url = appSettings.getString("site.monitor.app.base-url", null);
        if (isBlank(url)) return new Check("base_url", "bad", "missing", "general");
        if (url.contains("localhost") || url.contains("127.0.0.1")) return new Check("base_url", "warn", "localhost", "general");
        return new Check("base_url", "ok", url, "general");
    }

    private Check adminEmail() {
        String mail = appSettings.getString("site.monitor.system-admin.email", null);
        return isBlank(mail) ? new Check("admin_email", "warn", "missing", "general") : new Check("admin_email", "ok", mail, "general");
    }

    private Check unowned() {
        long n = inventoryRepo.countByActiveTrueAndTeamIdIsNull();
        return new Check("unowned", n == 0 ? "ok" : "warn", String.valueOf(n), "inventory");
    }

    private Check weakAlgo() {
        long n = latestCheckRepo.findWeakAlgorithmCandidates().stream()
                .filter(lc -> CertificateHealthRules.classifyWeakness(lc.getSignatureAlgorithm(), lc.getPublicKeyAlgorithm(),
                        lc.getPublicKeySize(), new ArrayList<>()) != null).count();
        return new Check("weak_algo", n == 0 ? "ok" : "warn", String.valueOf(n), "weakalgo");
    }

    private Check scheduler() {
        Map<String, Object> st = schedulerService.getStatus();
        Object lr = st.get("last_run");
        if (lr == null || "never".equals(String.valueOf(lr))) return new Check("scheduler", "warn", "never", "health");
        try {
            LocalDateTime t = LocalDateTime.parse(String.valueOf(lr));
            Duration age = Duration.between(t.toInstant(ZoneOffset.UTC), Instant.now());
            if (age.toHours() >= 2) return new Check("scheduler", "bad", "stale:" + age.toMinutes() + "m", "health");
            return new Check("scheduler", "ok", "last_run:" + lr, "health");
        } catch (Exception e) {
            return new Check("scheduler", "warn", "unparsable", "health");
        }
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}

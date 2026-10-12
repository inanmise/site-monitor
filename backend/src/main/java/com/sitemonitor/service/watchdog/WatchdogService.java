package com.sitemonitor.service.watchdog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.WatchdogState;
import com.sitemonitor.repository.WatchdogStateRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.mail.WatchdogMail;
import com.sitemonitor.service.watchdog.WatchdogProbes.Probe;
import com.sitemonitor.service.watchdog.WatchdogTransition.Action;
import com.sitemonitor.service.watchdog.WatchdogTransition.Result;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kendi kendini izleme (watchdog, 2026-10-11, kullanıcı isteği). Dakikada bir, kümede TEK pod'da ({@code scheduler_lock}
 * {@value #LOCK}) uygulamanın kendi işleyişini ölçer ({@link WatchdogProbes}): tarama liderliği, izleme taramaları,
 * sertifika taraması, bildirim gönderimi, push kuyruğu, veritabanı. Durum {@code watchdog_state}'te (tüm pod'lar ve dış
 * uç aynı durumu görür); geçişler {@link WatchdogTransition} ile onaylanır ve bir turun tüm geçişleri TEK bildirimle
 * ({@link WatchdogNotifier}) yöneticilere gider.
 *
 * <p><b>Veritabanı çökerse:</b> kilit ve durum tablosu okunamaz; o zaman yalnız veritabanı kontrolü pod başına bellekte
 * değerlendirilir ve bildirimi e-posta / webhook / push ile yine gider (DB'ye yazılamayan günlük satırı WARN olur).
 * Dış uç ({@code /api/public/health/watchdog}) son değerlendirme {@link #STALE_FACTOR} turdan eskiyse "değerlendirme
 * koşmuyor" der — izleyicinin kendisi durduysa da görünür.
 */
@Slf4j
@Service
public class WatchdogService {

    public static final String LOCK = "watchdog";
    static final int STALE_FACTOR = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WatchdogStateRepository repo;
    private final WatchdogProbes probes;
    private final WatchdogNotifier notifier;
    private final AppSettingsService settings;
    private SchedulerService scheduler;

    /** Veritabanına yazılamayan turlarda (DB çöküşü) pod başına durum. */
    private final Map<String, WatchdogState> memory = new ConcurrentHashMap<>();

    /**
     * Kesintide bu pod'un DUYURDUĞU ama kayıtlı satırı henüz "sağlıklı" görünmeyen sorunlar (inceleme 6): kayıtlı satır
     * durum OK ama son ölçüm doğrulanmamış hata / bilinmiyor iken "düzeldi" erken gönderilmez, ama bellek budanınca da
     * kaybolmaz — her turda yeniden bakılır: satır sağlıklıysa RECOVER, satır PROBLEM'e döndüyse sorun artık o satırın
     * (öbür pod onu duyurur ve kapatır), başka pod düzelmeyi duyurduysa düşer. Kontrol anahtarı başına bir giriş (sınırlı).
     */
    private final Map<String, WatchdogState> pendingRecoveries = new ConcurrentHashMap<>();

    @Value("${site.monitor.watchdog.interval-ms:60000}")
    private long intervalMs = 60_000;

    public WatchdogService(WatchdogStateRepository repo, WatchdogProbes probes, WatchdogNotifier notifier,
                           AppSettingsService settings) {
        this.repo = repo;
        this.probes = probes;
        this.notifier = notifier;
        this.settings = settings;
    }

    @Autowired(required = false)
    @Lazy
    public void setScheduler(SchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    /** Bir turun özeti. */
    public record Round(int evaluated, int alerts, int reminders, int recoveries, String outcome, boolean local) { }

    @Scheduled(fixedDelayString = "${site.monitor.watchdog.interval-ms:60000}",
               initialDelayString = "${site.monitor.watchdog.initial-delay-ms:120000}")
    public void tick() {
        if (!WatchdogSettings.enabled(settings)) return;
        try {
            Instant now = Instant.now();
            Optional<Round> r = scheduler == null ? Optional.of(evaluate(now, false))
                    : scheduler.callWithSchedulerLock(LOCK, () -> evaluate(now, false));
            if (r.isEmpty() && databaseDown(now)) evaluateLocalDatabase(now);   // kilit tablosu okunamıyor → yerel kip
        } catch (Exception e) {
            log.warn("Kendi kendini izleme turu tamamlanamadı: {}", e.toString());
        }
    }

    /**
     * Elle "Şimdi değerlendir" (2026-10-12): zamanlanmış turla AYNI kilit altında — iki pod aynı durumu okuyup aynı sorunu
     * iki kez bildiremez. Kilit başka pod'daysa (o an bir tur sürüyor) boş döner; kapalıyken hiç değerlendirmez.
     */
    public Optional<Round> evaluateManually(Instant now) {
        if (!WatchdogSettings.enabled(settings)) return Optional.empty();
        return scheduler == null ? Optional.of(evaluate(now, true)) : scheduler.callWithSchedulerLock(LOCK, () -> evaluate(now, true));
    }

    /** Kapalı mı (uç 409 döner, düğme gizlenir). */
    public boolean enabled() {
        return WatchdogSettings.enabled(settings);
    }

    /** Elle değerlendirme — testler ve kilitsiz eski çağıranlar için; kümeye açık yol {@link #evaluateManually}. */
    public Round evaluate(Instant now) {
        return evaluate(now, true);
    }

    /**
     * Tüm kontrolleri ölçer, durumları günceller, geçişleri TEK bildirimde gönderir.
     *
     * <p><b>Küme genelinde aralık başına bir tur (2026-10-12):</b> kilit iş bitince bırakıldığı için her pod kendi turunu
     * atardı (N pod → onay sayacı dakikalar yerine saniyelerle dolar, pod'a özgü ölçümler sayaçları sıfırlar). Zamanlanmış
     * tur, başka bir pod son aralık içinde değerlendirdiyse ATLANIR ({@code RECENT}); elle değerlendirme atlanmaz.
     *
     * <p><b>DB kesintisi sonrası:</b> kesinti boyunca yalnız pod belleğinde tutulan durum (ör. veritabanı sorunu açıldı ve
     * bildirildi) okuma yeniden çalışınca kayıtlı satırdan daha YENİYSE onun yerine geçer — böylece "düzeldi" bildirimi
     * kaybolmaz.
     */
    synchronized Round evaluate(Instant now, boolean manual) {
        Map<String, WatchdogState> states = new HashMap<>();
        boolean persisted = true;
        try {
            for (WatchdogState s : repo.findAll()) states.put(s.getCheckKey(), s);
        } catch (Exception e) {
            persisted = false;
            states.putAll(memory);
        }
        boolean muted = notifier.muted();
        if (persisted) {
            // Kayıtlı satırdan YENİ olmayan bellek durumu artık geçersizdir (başka pod DB dönünce değerlendirip yazdı) — silinir.
            // RECENT ile sürekli atlayan bir pod bayat "sorun" durumunu sonraki DB kesintisinde yeniden kullanmasın (2026-10-12).
            // Bu pod'un kesintide bellekte açıp BİLDİRDİĞİ sorun, kayıtlı satır sorunsuzsa "düzeldi" bildirimiyle kapanır
            // (2026-10-12; eskiden budanıp kayboluyordu — yöneticiler "veritabanı çöktü" alıp "düzeldi"yi hiç almıyordu).
            List<WatchdogState> orphans = new ArrayList<>();
            memory.entrySet().removeIf(en -> {
                WatchdogState stored = states.get(en.getKey());
                if (stored == null || newer(en.getValue().getEvaluatedAt(), stored.getEvaluatedAt())) return false;
                // "Düzeldi" yalnız kayıtlı satır SAĞLIKLIYKEN (OK; NA da OK yazılır) — durumsuz / bilinmeyen satır iyileşme
                // sayılmaz (inceleme 4: eskiden PROBLEM olmayan her şey, ör. hiç değerlendirilmemiş satır, RECOVER gönderiyordu).
                // Henüz sağlıklı görünmüyorsa bekleme listesine alınır (inceleme 6) — budanınca kaybolmaz.
                if (announcedProblem(en.getValue()) && !recoveryAnnounced(stored, en.getValue())) {
                    pendingRecoveries.put(en.getKey(), en.getValue());
                }
                return true;
            });
            for (java.util.Iterator<Map.Entry<String, WatchdogState>> it = pendingRecoveries.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, WatchdogState> en = it.next();
                WatchdogState stored = states.get(en.getKey());
                if (stored == null) continue;                                   // satır yok — bekler
                if ("PROBLEM".equals(stored.getStatus()) || recoveryAnnounced(stored, en.getValue())) {
                    it.remove();                                                // sorun artık kayıtlı satırın / düzelme duyuruldu
                } else if (storedHealthy(stored)) {
                    orphans.add(stored);
                    it.remove();
                }
            }
            if (!orphans.isEmpty()) recoverOrphans(orphans, now);
            // Yalnız KAYITLI satırlara bakılır: bu pod'un DB kesintisindeki yerel değerlendirmesi "yakın tur" sayılmaz.
            if (!manual && evaluatedRecently(states.values(), now)) {
                return new Round(states.size(), 0, 0, 0, "RECENT", false);
            }
            for (WatchdogState m : memory.values()) {
                WatchdogState stored = states.get(m.getCheckKey());
                if (stored == null || newer(m.getEvaluatedAt(), stored.getEvaluatedAt())) states.put(m.getCheckKey(), m);
            }
        }
        int confirm = WatchdogSettings.confirm(settings);
        int reminder = WatchdogSettings.reminderHours(settings);
        List<WatchdogMail.Item> items = new ArrayList<>();
        List<Map.Entry<WatchdogState, Action>> acted = new ArrayList<>();
        int alerts = 0, reminders = 0, recoveries = 0;
        for (Probe p : probes.runAll(now)) {
            WatchdogState st = states.computeIfAbsent(p.check().key(), k -> {
                WatchdogState n = new WatchdogState();
                n.setCheckKey(k);
                return n;
            });
            Action a = WatchdogTransition.apply(st, p.result(), p.severity(), json(p.facts()), now, confirm, reminder, muted);
            if (a != Action.NONE) {
                items.add(new WatchdogMail.Item(a.name(), p.check().labelTr(), st.getSeverity() == null ? p.check().severity().name() : st.getSeverity(),
                        WatchdogMessages.detail(p.check(), p.facts(), false), p.check().labelEn(), WatchdogMessages.detail(p.check(), p.facts(), true)));
                acted.add(Map.entry(st, a));
                switch (a) {
                    case ALERT -> alerts++;
                    case REMIND -> reminders++;
                    case RECOVER -> recoveries++;
                    default -> { }
                }
            }
        }
        String outcome = "NONE";
        if (!items.isEmpty()) {
            WatchdogNotifier.Report rep = notifier.send(items, false);
            outcome = rep.outcome().name();
            String suffix = suffix(rep.outcome());
            for (Map.Entry<WatchdogState, Action> e : acted) WatchdogTransition.markNotified(e.getKey(), e.getValue(), suffix, now);
            log.info("Kendi kendini izleme: {} sorun açıldı, {} hatırlatma, {} düzeldi → bildirim {}", alerts, reminders, recoveries, outcome);
        }
        if (persisted) {
            try {
                repo.saveAll(states.values());
                memory.clear();
            } catch (Exception e) {
                persisted = false;
                log.warn("Kendi kendini izleme durumu yazılamadı (bellekte tutuluyor): {}", e.toString());
            }
        }
        if (!persisted) memory.putAll(states);
        return new Round(states.size(), alerts, reminders, recoveries, outcome, !persisted);
    }

    /** Teslim sonucu → bildirim türü eki ({@link WatchdogTransition#markNotified(WatchdogState, Action, String, Instant)}). */
    static String suffix(WatchdogNotifier.Outcome o) {
        return switch (o) {
            case FAILED -> WatchdogTransition.FAILED;
            case MUTED -> WatchdogTransition.MUTED;
            case NO_CHANNEL -> WatchdogTransition.NO_CHANNEL;
            default -> "";
        };
    }

    /**
     * Sorun açık ve DUYURULMUŞ mu: teslim edilmiş açılış ya da hatırlatma. Teslim edilemeyen / susturulan HATIRLATMA da
     * sayılır (hatırlatma ancak duyurulmuş bir açılıştan sonra gelir); kanalsız hatırlatma sayılmaz.
     */
    static boolean announcedProblem(WatchdogState s) {
        String k = s.getNotifiedKind();
        if (!"PROBLEM".equals(s.getStatus()) || k == null) return false;
        return "ALERT".equals(k) || "REMIND".equals(k) || "REMIND_FAILED".equals(k) || "REMIND_MUTED".equals(k);
    }

    /**
     * Kayıtlı satır SAĞLIKLI mı (inceleme 5): durum OK VE son ölçüm OK / NA. Durum OK ama son ölçüm PROBLEM (henüz
     * doğrulanmamış hata) ya da UNKNOWN ise "düzeldi" duyurulmaz — öbür pod bir sonraki turda sorunu doğrularsa
     * ALARM → DÜZELDİ → ALARM dalgalanması olurdu.
     */
    static boolean storedHealthy(WatchdogState stored) {
        if (!"OK".equals(stored.getStatus())) return false;
        String r = stored.getLastResult();
        return "OK".equals(r) || "NA".equals(r);
    }

    /** Kayıtlı satır bu sorunun düzelmesini zaten duyurmuş mu (başka pod RECOVER göndermiş) — ikinci "düzeldi" gitmesin. */
    static boolean recoveryAnnounced(WatchdogState stored, WatchdogState memory) {
        String k = stored.getNotifiedKind();
        return k != null && k.startsWith("RECOVER") && !newer(memory.getNotifiedAt(), stored.getNotifiedAt());
    }

    /** Budanan bellek sorunlarının "düzeldi" bildirimi — kayıtlı (güncel) satırın olgularıyla, tek bildirimde. */
    private void recoverOrphans(List<WatchdogState> stored, Instant now) {
        List<WatchdogMail.Item> items = new ArrayList<>();
        for (WatchdogState s : stored) {
            WatchdogCheck c = WatchdogCheck.byKey(s.getCheckKey());
            if (c == null) continue;
            Map<String, Object> facts = parse(s.getFacts());
            items.add(new WatchdogMail.Item(Action.RECOVER.name(), c.labelTr(), c.severity().name(),
                    WatchdogMessages.detail(c, facts, false), c.labelEn(), WatchdogMessages.detail(c, facts, true)));
        }
        if (items.isEmpty()) return;
        try {
            WatchdogNotifier.Report rep = notifier.send(items, false);
            log.info("Kendi kendini izleme: kesintide bu pod'da açılan {} sorun düzeldi → bildirim {}", items.size(), rep.outcome());
        } catch (Exception e) {
            log.warn("Kendi kendini izleme: düzelme bildirimi gönderilemedi: {}", e.toString());
        }
    }

    /** {@code a} anı {@code b}'den sonra mı (ISO yyyy-MM-ddTHH:mm:ss metinleri; null = hiç). */
    static boolean newer(String a, String b) {
        if (a == null) return false;
        if (b == null) return true;
        return a.compareTo(b) > 0;
    }

    /** Başka bir pod (ya da bu pod) bu aralık içinde değerlendirdi mi — 5 sn pay, saat kayması/gecikme için. */
    boolean evaluatedRecently(java.util.Collection<WatchdogState> states, Instant now) {
        String limit = WatchdogTransition.ISO.format(now.minusMillis(Math.max(1_000L, intervalMs - 5_000L)));
        for (WatchdogState s : states) {
            if (s.getEvaluatedAt() != null && s.getEvaluatedAt().compareTo(limit) > 0) return true;
        }
        return false;
    }

    /** DB çökükken: yalnız veritabanı kontrolü, pod belleğinde (her pod kendisi bildirir — tek pod prod'da tek bildirim). */
    synchronized void evaluateLocalDatabase(Instant now) {
        Probe p = probes.run(WatchdogCheck.DATABASE, now);
        WatchdogState st = memory.computeIfAbsent(WatchdogCheck.DATABASE.key(), k -> {
            WatchdogState n = new WatchdogState();
            n.setCheckKey(k);
            return n;
        });
        Action a = WatchdogTransition.apply(st, p.result(), p.severity(), json(p.facts()), now,
                WatchdogSettings.confirm(settings), WatchdogSettings.reminderHours(settings), notifier.muted());
        if (a == Action.NONE) return;
        WatchdogNotifier.Report rep = notifier.send(List.of(new WatchdogMail.Item(a.name(), WatchdogCheck.DATABASE.labelTr(),
                st.getSeverity(), WatchdogMessages.detail(WatchdogCheck.DATABASE, p.facts(), false),
                WatchdogCheck.DATABASE.labelEn(), WatchdogMessages.detail(WatchdogCheck.DATABASE, p.facts(), true))), false);
        WatchdogTransition.markNotified(st, a, suffix(rep.outcome()), now);
    }

    private boolean databaseDown(Instant now) {
        Probe p = probes.run(WatchdogCheck.DATABASE, now);
        return p.result() == Result.PROBLEM && WatchdogCheck.Severity.CRITICAL.name().equals(p.severity());
    }

    // ── Okuma ─────────────────────────────────────────────────────────────────────────────────

    /** Kayıtlı (ya da DB çökükken bellekteki) durumlar, katalog sırasıyla. */
    List<WatchdogState> currentStates() {
        Map<String, WatchdogState> byKey = new HashMap<>(memory);
        try {
            for (WatchdogState s : repo.findAll()) byKey.putIfAbsent(s.getCheckKey(), s);
        } catch (Exception e) {
            log.debug("watchdog durumu okunamadı: {}", e.toString());
        }
        List<WatchdogState> out = new ArrayList<>();
        for (WatchdogCheck c : WatchdogCheck.values()) {
            WatchdogState s = byKey.get(c.key());
            if (s != null) out.add(s);
        }
        return out;
    }

    /**
     * Genel durum: DOWN (kritik sorun ya da değerlendirme durmuş) | DEGRADED (sorun var) | UP | DISABLED | UNKNOWN (hiç
     * değerlendirilmedi).
     */
    public String overall(List<WatchdogState> states, Instant now) {
        if (!WatchdogSettings.enabled(settings)) return "DISABLED";
        if (states.isEmpty()) return "UNKNOWN";
        if (stale(states, now)) return "DOWN";
        boolean critical = false, problem = false;
        for (WatchdogState s : states) {
            if (!"PROBLEM".equals(s.getStatus())) continue;
            problem = true;
            if (WatchdogCheck.Severity.CRITICAL.name().equals(s.getSeverity())) critical = true;
        }
        return critical ? "DOWN" : problem ? "DEGRADED" : "UP";
    }

    boolean stale(List<WatchdogState> states, Instant now) {
        Instant newest = Instant.EPOCH;
        for (WatchdogState s : states) {
            if (s.getEvaluatedAt() == null) continue;
            Instant at = WatchdogTransition.parse(s.getEvaluatedAt());
            if (at.isAfter(newest)) newest = at;
        }
        long limitMs = Math.max(5 * 60_000L, STALE_FACTOR * intervalMs);
        return Duration.between(newest, now).toMillis() > limitMs;
    }

    /** Dış izleyici gövdesi — yalnız durum, kontrol anahtarları ve anlar (ad / sayı / adres yok). */
    public Map<String, Object> publicStatus(Instant now) {
        List<WatchdogState> states = currentStates();
        Map<String, Object> out = new LinkedHashMap<>();
        String overall = overall(states, now);
        out.put("status", overall);
        out.put("component", "watchdog");
        String last = states.stream().map(WatchdogState::getEvaluatedAt).filter(java.util.Objects::nonNull).max(String::compareTo).orElse(null);
        out.put("evaluated_at", last);
        if (!"DISABLED".equals(overall) && !states.isEmpty() && stale(states, now)) out.put("reason", "evaluation_stale");
        List<Map<String, Object>> checks = new ArrayList<>();
        for (WatchdogState s : states) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.getCheckKey());
            m.put("status", s.getStatus());
            if ("PROBLEM".equals(s.getStatus())) m.put("severity", s.getSeverity());
            m.put("since", s.getSince());
            checks.add(m);
        }
        out.put("checks", checks);
        return out;
    }

    /** Yönetici ekranı: kontroller (ad, durum, ayrıntı cümlesi, olgular, son bildirim) + ayar özeti. */
    public Map<String, Object> adminStatus(Instant now) {
        List<WatchdogState> states = currentStates();
        Map<String, WatchdogState> byKey = new HashMap<>();
        for (WatchdogState s : states) byKey.put(s.getCheckKey(), s);
        boolean en = "en".equals(Msg.lang());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", overall(states, now));
        out.put("enabled", WatchdogSettings.enabled(settings));
        out.put("stale", !states.isEmpty() && stale(states, now));
        out.put("interval_seconds", intervalMs / 1000);
        out.put("confirm_checks", WatchdogSettings.confirm(settings));
        List<Map<String, Object>> checks = new ArrayList<>();
        for (WatchdogCheck c : WatchdogCheck.values()) {
            WatchdogState s = byKey.get(c.key());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c.key());
            m.put("label", en ? c.labelEn() : c.labelTr());
            m.put("problem_text", en ? c.problemEn() : c.problemTr());
            m.put("default_severity", c.severity().name());
            if (s == null) {
                m.put("status", "PENDING");
            } else {
                Map<String, Object> facts = parse(s.getFacts());
                m.put("status", s.getStatus());
                m.put("last_result", s.getLastResult());
                m.put("severity", s.getSeverity());
                m.put("since", s.getSince());
                m.put("evaluated_at", s.getEvaluatedAt());
                m.put("notified_at", s.getNotifiedAt());
                m.put("notified_kind", s.getNotifiedKind());
                m.put("detail", WatchdogMessages.detail(c, facts, en));
                m.put("facts", facts);
            }
            checks.add(m);
        }
        out.put("checks", checks);
        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("email_recipients", WatchdogSettings.recipients(settings, "").size());
        channels.put("push_users", WatchdogSettings.pushUsers(settings).size());
        channels.put("webhook", !WatchdogSettings.webhookUrl(settings).isBlank());
        channels.put("reminder_hours", WatchdogSettings.reminderHours(settings));
        channels.put("confirm_checks", WatchdogSettings.confirm(settings));
        out.put("channels", channels);
        return out;
    }

    /** Test bildirimi — tüm kanallardan, durum değiştirmeden. */
    public WatchdogNotifier.Report sendTest() {
        return notifier.send(List.of(new WatchdogMail.Item("TEST", "Kendi kendini izleme", "INFO",
                "Bu bir test bildirimidir; bir sorun yok.")), true);
    }

    private static String json(Map<String, Object> facts) {
        if (facts == null) return null;
        try {
            return MAPPER.writeValueAsString(facts);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}

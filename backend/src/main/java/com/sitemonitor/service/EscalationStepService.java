package com.sitemonitor.service;

import com.sitemonitor.model.AlertEscalationStep;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.NetworkOutageEvent;
import com.sitemonitor.repository.AlertEscalationStepRepository;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Zamana bağlı eskalasyon adımları (2026-10-01, opt-in) — "Adım tanımlanmadıkça kimseye yeni bildirim gitmez."
 *
 * <p><b>Ne yapar.</b> {@code escalation_contacts.delay_minutes} dolu (1–1440) kişi alarmın ANLIK bildirimlerine girmez
 * ({@link EscalationDelay}). Bu iş dakikada bir açık alarmlara bakar: alarm AÇIK, ONAYSIZ (sahiplenilmemiş) ve saat çapasından
 * bu yana kişinin gecikmesi dolmuşsa o kişiye BİR "eskalasyon adımı" gönderir ({@link EscalationService#sendEscalationStep}
 * — kişinin e-postası + varsa Teams/Slack webhook'u). Saat çapası alarmın SON açılış/seviye-artışı duyurusudur
 * ({@code notification_logs} INITIAL / ESCALATION; yoksa {@code created_at}): seviye artışı onayı düşürdüğü için saat yeniden
 * başlar, bakımda ertelenmiş ilk bildirim gittiği andan sayılır, yeniden uyarılar saati OYNATMAZ.
 *
 * <p><b>Kurallar anlık yolla aynı.</b> Yalnız alarmın SAHİP takımlarının (SY/UG) kişileri ({@link EscalationContactScope},
 * bellek-içi eşi), seviye eşiği ({@code minAlertLevel}), UYARI seviyeli bağımsız izleme alarmında kişi yok
 * ({@link EscalationService#teamOnlyRecipients(AlertEvent)}), sahipsiz alarm hiçbir şey göndermez; başka takımın kişisi ya da
 * global yedek YOK. Fırtına üyesi, bakım penceresindeki ve toplu kesinti bastırmasındaki alarm eskale OLMAZ — atlanan her
 * adım {@code notification_logs}'a {@code ESCALATION_STEP / SKIPPED: <neden>} satırı bırakır. Elle kontrol alarm açmadığı
 * için buraya hiç alarm getirmez.
 *
 * <p><b>Tekillik.</b> {@code alert_escalation_steps} UNIQUE(alarm, kişi, seviye): karar gönderimden ÖNCE sahiplenilir
 * ({@code saveAndFlush}); yarışı kaybeden pod vazgeçer, yeniden başlatma ikinci adım üretmez (en fazla bir kez). İş
 * {@code scheduler_lock} ("escalation-steps") altında koşar; gecikme tanımlı kişi yoksa tek ucuz sorguyla kilit almadan biter.
 *
 * <p><b>Maliyet.</b> Tur başına sabit sayıda TOPLU sorgu (açık alarmlar, envanter, sahip takımların kişileri, kararlar,
 * saat çapaları, aktif fırtınalar, açık kesintiler — kimlik listeleri {@value #CHUNK}'lük dilimlerle); alarm başına sorgu
 * yok. Yalnız VAKTİ GELMİŞ adım için tek tazeleme okuması + gönderim + günlük yazılır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscalationStepService {

    /** {@code scheduler_lock} anahtarı — diğer süpürmelerden ayrı. */
    public static final String LOCK_NAME = "escalation-steps";
    /** Toplu IN sorgusunun dilim boyutu. */
    static final int CHUNK = 500;
    /** ONGOING toplu kesinti kaydı bundan eskiyse bayat sayılır (kapanmamış eski satır adımları süresiz susturmasın). */
    static final Duration OUTAGE_FRESHNESS = Duration.ofHours(24);
    /**
     * Saat çapası: alarmın açılış ve seviye-artışı duyuruları. Yeniden uyarı / elle gönderim saati oynatmaz. Sessiz saat
     * özeti (2026-10-01) ertelenmiş açılışın GERÇEK duyurusudur → saat özet anından yeniden başlar (takım haber almadan
     * adım "onaylanmadı" sayılmasın). Sessiz saat kurulmamışsa bu tetikte satır hiç yoktur (davranış aynı).
     */
    static final List<String> ANCHOR_TRIGGERS = List.of("INITIAL", "ESCALATION", EscalationService.TRIGGER_QUIET_DIGEST);

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EscalationContactRepository contactRepo;
    private final AlertEventRepository alertEventRepo;
    private final AlertEscalationStepRepository stepRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final NotificationLogRepository notificationLogRepo;
    private final AlertStormRepository stormRepo;
    private final NetworkOutageEventRepository outageRepo;
    private final MaintenanceService maintenanceService;
    private final EscalationService escalationService;
    private final SchedulerService schedulerService;

    /** Test kancası (paket-özel). */
    Clock clock = Clock.systemUTC();

    /**
     * Takım sessiz saatleri (2026-10-01, onaylı öneri 15) — alan enjeksiyonu + isteğe bağlı: testler servisi elle kuruyor;
     * yokken ya da hiçbir takımda pencere yokken adım kararı bugünküyle aynıdır (sorgu da yok). Vakti gelen adım, kişinin
     * takımı penceredeyse (seviye ertelenebilir) ya da alarmın o takım için bekleyen özeti varsa BEKLETİLİR.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TeamQuietHoursService quietHours;

    void setQuietHours(TeamQuietHoursService quietHours) {
        this.quietHours = quietHours;
    }

    /** Tur özeti (log + test). */
    public record SweepResult(int candidates, int sent, int skipped, int prior) {
        static final SweepResult EMPTY = new SweepResult(0, 0, 0, 0);
    }

    @Scheduled(fixedDelayString = "${site.monitor.escalation.step-interval-ms:60000}",
               initialDelayString = "${site.monitor.escalation.step-initial-delay-ms:90000}")
    public void scheduledSweep() {
        // Bugünkü kurulum (hiçbir kişide gecikme yok): tek boolean sorgu, kilit yok, açık alarm okunmaz.
        if (!anyDelayedContact()) return;
        schedulerService.runWithSchedulerLock(LOCK_NAME, () -> {
            SweepResult r = sweep(Instant.now(clock));
            if (r.sent() > 0 || r.skipped() > 0) {
                log.info("Eskalasyon adımı turu: {} aday, {} gönderildi, {} atlandı, {} zaten bilgilendirilmiş",
                        r.candidates(), r.sent(), r.skipped(), r.prior());
            }
        });
    }

    boolean anyDelayedContact() {
        try {
            return contactRepo.existsByActiveTrueAndDelayMinutesGreaterThan(0);
        } catch (Exception e) {
            log.debug("Eskalasyon adımı ön kapısı okunamadı (kolon henüz yok?): {}", e.getMessage());
            return false;
        }
    }

    /** Tek tur — çağıran kilidi tutar. Paket-özel: test doğrudan çağırır. */
    SweepResult sweep(Instant now) {
        List<AlertEvent> open = alertEventRepo.findAllOpenOrderBySeverity();
        if (open == null || open.isEmpty()) return SweepResult.EMPTY;

        // 1) Sahip takımlar — envanter TOPLU (bağımsız izleme olayı envanterden takım almaz).
        Set<String> invDomains = new LinkedHashSet<>();
        for (AlertEvent e : open) {
            if (e.getDomain() != null && !EscalationService.isStandaloneEvent(e)) invDomains.add(e.getDomain());
        }
        Map<String, CertificateInventory> invByDomain = new HashMap<>();
        for (List<String> chunk : chunks(invDomains)) {
            for (CertificateInventory inv : inventoryRepo.findByDomainIn(chunk)) {
                if (inv.getDomain() != null) invByDomain.putIfAbsent(inv.getDomain(), inv);
            }
        }
        Map<Long, EscalationService.StepOwners> owners = new HashMap<>();
        Set<Long> teams = new LinkedHashSet<>();
        for (AlertEvent e : open) {
            if (e.getId() == null || Boolean.TRUE.equals(e.getResolved())) continue;
            // UYARI seviyeli bağımsız izleme alarmı yalnız takıma gider — kişi (gecikmeli ya da değil) asla.
            if (EscalationService.teamOnlyRecipients(e)) continue;
            EscalationService.StepOwners o = escalationService.stepOwners(e, invByDomain.get(e.getDomain()));
            if (o.syTeamId() == null && o.ugTeamId() == null) continue;   // sahipsiz: hiçbir kanal
            owners.put(e.getId(), o);
            if (o.syTeamId() != null) teams.add(o.syTeamId());
            if (o.ugTeamId() != null) teams.add(o.ugTeamId());
        }
        if (teams.isEmpty()) return SweepResult.EMPTY;

        // 2) Kişiler — YALNIZ sahip takımların etkin kişileri, tek (dilimli) takım-kapsamlı sorgu; gecikmeliler tutulur.
        Map<Long, List<EscalationContact>> delayedByTeam = new HashMap<>();
        for (List<Long> chunk : chunks(teams)) {
            for (EscalationContact c : contactRepo.findByTeamIdInAndActiveTrueOrderByRoleAsc(chunk)) {
                if (EscalationDelay.isDelayed(c) && c.getId() != null && c.getTeamId() != null)
                    delayedByTeam.computeIfAbsent(c.getTeamId(), k -> new ArrayList<>()).add(c);
            }
        }
        if (delayedByTeam.isEmpty()) return SweepResult.EMPTY;

        // 3) Aday (alarm, kişi) çiftleri — kapsam + seviye kuralı anlık yolla AYNI (EscalationContactScope).
        Map<Long, AlertEvent> events = new LinkedHashMap<>();
        Map<Long, List<EscalationContact>> candidates = new LinkedHashMap<>();
        for (AlertEvent e : open) {
            EscalationService.StepOwners o = owners.get(e.getId());
            if (o == null) continue;
            List<EscalationContact> cs = EscalationContactScope.forOwners(delayedByTeam, e.getAlertLevel(),
                    o.syTeamId(), o.ugTeamId());
            if (cs.isEmpty()) continue;
            events.put(e.getId(), e);
            candidates.put(e.getId(), cs);
        }
        if (candidates.isEmpty()) return SweepResult.EMPTY;

        // 4) Toplu durum: mevcut kararlar + saat çapaları. Fırtına ve kesinti yalnız gerekince (tembel, tur başına bir kez).
        Map<Long, List<AlertEscalationStep>> steps = new HashMap<>();
        Map<Long, Instant> anchors = new HashMap<>();
        for (List<Long> chunk : chunks(candidates.keySet())) {
            for (AlertEscalationStep s : stepRepo.findByAlertEventIdIn(chunk))
                steps.computeIfAbsent(s.getAlertEventId(), k -> new ArrayList<>()).add(s);
            for (Object[] row : notificationLogRepo.latestSentAtByAlertIds(chunk, ANCHOR_TRIGGERS)) {
                if (row == null || row.length < 2 || !(row[0] instanceof Number id)) continue;
                Instant at = parseUtc(row[1] == null ? null : row[1].toString());
                if (at != null) anchors.put(id.longValue(), at);
            }
        }
        SweepState state = new SweepState();
        state.candidateIds = candidates.keySet();

        int sent = 0, skipped = 0, prior = 0, considered = 0;
        for (Map.Entry<Long, List<EscalationContact>> entry : candidates.entrySet()) {
            AlertEvent e = events.get(entry.getKey());
            EscalationService.StepOwners o = owners.get(e.getId());
            List<AlertEscalationStep> evSteps = steps.getOrDefault(e.getId(), List.of());
            for (EscalationContact c : entry.getValue()) {
                considered++;
                try {
                    switch (decide(e, c, evSteps, anchors.get(e.getId()), o, now, state)) {
                        case SENT -> sent++;
                        case SKIPPED -> skipped++;
                        case PRIOR -> prior++;
                        default -> { /* henüz vakti gelmedi / zaten karara bağlı */ }
                    }
                } catch (Exception ex) {
                    // Zehirli kayıt yalıtımı: tek (alarm, kişi) çiftinin hatası turun kalanını düşürmez.
                    log.warn("Eskalasyon adımı değerlendirilemedi (olay #{} kişi #{}): {}", e.getId(), c.getId(), ex.toString());
                }
            }
        }
        return new SweepResult(considered, sent, skipped, prior);
    }

    private enum Decision { NONE, SENT, SKIPPED, PRIOR }

    /** Tur içi tembel toplu okumalar (fırtına / kesinti) — ihtiyaç olursa tur başına BİR kez. */
    private final class SweepState {
        Set<Long> activeStorms;
        Set<String> outageSources;
        boolean certOutage;
        Collection<Long> candidateIds = List.of();
        Map<Long, Set<Long>> pendingQuiet;

        /** Alarmın bekleyen sessiz saat özeti olan takımları — tur başına TEK (dilimli) toplu okuma; pencere tanımsızsa sorgu yok. */
        Set<Long> pendingQuietTeams(Long eventId) {
            if (pendingQuiet == null) {
                pendingQuiet = new HashMap<>();
                if (quietHours != null && quietHours.anyConfigured()) {
                    for (List<Long> chunk : chunks(candidateIds)) {
                        quietHours.pendingTeamsByEvent(chunk).forEach((k, v) ->
                                pendingQuiet.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
                    }
                }
            }
            return pendingQuiet.getOrDefault(eventId, Set.of());
        }

        void markPendingQuiet(Long eventId, Long teamId) {
            if (pendingQuiet == null) pendingQuietTeams(eventId);
            pendingQuiet.computeIfAbsent(eventId, x -> new HashSet<>()).add(teamId);
        }

        boolean stormActive(Long stormId) {
            if (stormId == null) return false;
            if (activeStorms == null) {
                activeStorms = new HashSet<>();
                for (AlertStorm s : stormRepo.findByResolvedFalse()) if (s.getId() != null) activeStorms.add(s.getId());
            }
            return activeStorms.contains(stormId);
        }

        boolean outageSuppressed(String alertType, Instant now) {
            if (outageSources == null) {
                outageSources = new HashSet<>();
                Instant fresh = now.minus(OUTAGE_FRESHNESS);
                for (NetworkOutageEvent ev : outageRepo.findByStatus("ONGOING")) {
                    Instant at = parseUtc(ev.getDetectedAt());
                    if (at == null || at.isBefore(fresh)) continue;   // bayat kayıt bastırma sayılmaz
                    if (ev.getSource() == null || "CERT".equals(ev.getSource())) certOutage = true;
                    else outageSources.add(ev.getSource());
                }
            }
            if (alertType == null) return false;
            return EscalationService.CERT_ALERT_TYPES.contains(alertType) ? certOutage : outageSources.contains(alertType);
        }
    }

    private Decision decide(AlertEvent e, EscalationContact c, List<AlertEscalationStep> evSteps, Instant logAnchor,
                            EscalationService.StepOwners o, Instant now, SweepState state) {
        String level = levelKey(e.getAlertLevel());
        // Kişi bu alarmın döngüsünde (adımı gitmiş / alarmı zaten almış) → normal alıcıdır, adım yok.
        // Bu seviye için karar zaten verilmiş (atlandı / gönderiliyor) → tekrar yok.
        for (AlertEscalationStep s : evSteps) {
            if (!Objects.equals(s.getContactId(), c.getId())) continue;
            if (!AlertEscalationStep.SKIPPED.equals(s.getOutcome())) return Decision.NONE;
            if (level.equals(s.getAlertLevel())) return Decision.NONE;
        }
        int delay = c.getDelayMinutes();
        // Kişi bu alarmı gecikme tanımlanmadan önce ANLIK almıştı (notifiedContacts) → adım gereksiz; döngüye alınır.
        if (alreadyNotified(e, c)) {
            return claim(e, c, AlertEscalationStep.PRIOR, "alarmı gecikme tanımlanmadan önce aldı", now) != null
                    ? Decision.PRIOR : Decision.NONE;
        }
        Instant anchor = anchorOf(e, logAnchor);
        if (anchor == null || now.isBefore(anchor.plus(Duration.ofMinutes(delay)))) return Decision.NONE;

        String skip = skipReason(e, now, state);
        if (skip != null) {
            if (claim(e, c, AlertEscalationStep.SKIPPED, skip, now) == null) return Decision.NONE;
            escalationService.recordEscalationStepSkipped(e, c, delay, skip);
            return Decision.SKIPPED;
        }
        // Sessiz saat: karar SAHİPLENMEDEN beklet — pencere ve özet bitince adım (özet anından itibaren gecikmesiyle) gider.
        if (quietHold(e, c, level, delay, now, state)) return Decision.NONE;
        AlertEscalationStep claimed = claim(e, c, AlertEscalationStep.SENDING, null, now);
        if (claimed == null) return Decision.NONE;   // başka pod sahiplendi

        // Taze okuma: tur başındaki listeden bu yana onaylanmış / çözülmüş / seviye değiştirmiş olabilir.
        AlertEvent fresh = alertEventRepo.findById(e.getId()).orElse(null);
        String late = freshSkipReason(fresh, level);
        if (late != null) {
            finish(claimed, AlertEscalationStep.SKIPPED, late);
            escalationService.recordEscalationStepSkipped(fresh != null ? fresh : e, c, delay, late);
            return Decision.SKIPPED;
        }
        escalationService.sendEscalationStep(fresh, c, delay, o.syTeamId(), o.ugTeamId());
        finish(claimed, AlertEscalationStep.SENT, null);
        return Decision.SENT;
    }

    /**
     * Sessiz saat beklemesi (2026-10-01): kişinin takımı için alarmın bekleyen özeti varsa ya da takım ŞİMDİ penceredeyse ve
     * seviye ertelenebiliyorsa adım bekler. İlk beklemede alarm takımın özetine yazılır ve günlüğe BİR iz satırı düşer
     * (sonraki turlarda kayıt zaten var → satır yok). Hata = bekletme yok (adım bugünkü gibi gider).
     */
    private boolean quietHold(AlertEvent e, EscalationContact c, String level, int delay, Instant now, SweepState state) {
        if (quietHours == null || c.getTeamId() == null) return false;
        try {
            if (state.pendingQuietTeams(e.getId()).contains(c.getTeamId())) return true;   // özet henüz gitmedi
            QuietHours.Occurrence occ = quietHours.deferral(c.getTeamId(), level, now);
            if (occ == null) return false;
            if (quietHours.recordDeferral(e.getId(), c.getTeamId(), occ, level, TeamQuietHoursService.STEP_TRIGGER, now)) {
                escalationService.recordQuietStepHold(e, c, delay, occ);
            }
            state.markPendingQuiet(e.getId(), c.getTeamId());
            return true;
        } catch (Exception ex) {
            log.warn("Sessiz saat adım kararı verilemedi (olay #{} kişi #{}) — adım normal değerlendiriliyor: {}",
                    e.getId(), c.getId(), ex.toString());
            return false;
        }
    }

    /** Vakti gelmiş adımı neden göndermiyoruz? null = gönder. */
    private String skipReason(AlertEvent e, Instant now, SweepState state) {
        if (Boolean.TRUE.equals(e.getAcknowledged())) {
            return "alarm onaylandı" + (e.getAcknowledgedBy() != null && !e.getAcknowledgedBy().isBlank()
                    ? " (" + e.getAcknowledgedBy() + ")" : "");
        }
        if (e.getStormId() != null && state.stormActive(e.getStormId())) {
            return "fırtına #" + e.getStormId() + " üyesi — bireysel bildirim fırtınaya devredildi";
        }
        if (maintenanceService.isUnderMaintenance(e.getDomain())) return "bakım penceresi";
        if (state.outageSuppressed(e.getAlertType(), now)) return "toplu kesinti bastırması (ağ kesintisi şüphesi)";
        return null;
    }

    /** Sahiplenmeden sonra taze olayla son kontrol. */
    private static String freshSkipReason(AlertEvent fresh, String claimedLevel) {
        if (fresh == null || Boolean.TRUE.equals(fresh.getResolved())) return "alarm çözüldü";
        if (Boolean.TRUE.equals(fresh.getAcknowledged())) {
            return "alarm onaylandı" + (fresh.getAcknowledgedBy() != null && !fresh.getAcknowledgedBy().isBlank()
                    ? " (" + fresh.getAcknowledgedBy() + ")" : "");
        }
        if (!claimedLevel.equals(levelKey(fresh.getAlertLevel()))) {
            return "alarm seviyesi değişti — yeni seviyede yeniden değerlendirilir";
        }
        return null;
    }

    /** Saat çapası: son INITIAL/ESCALATION duyurusu; yoksa (ilk bildirim gitmişse) açılış anı; hiç duyurulmadıysa null. */
    static Instant anchorOf(AlertEvent e, Instant logAnchor) {
        if (logAnchor != null) return logAnchor;
        if (e.getLastReAlertAt() == null) return null;   // ilk bildirim henüz gitmedi (bakımda ertelendi / yarıda kaldı)
        return parseUtc(e.getCreatedAt());
    }

    /** Kişinin e-postası olayın "kime bildirildi" izinde var mı (gecikme tanımlanmadan önce açılmış alarm). */
    static boolean alreadyNotified(AlertEvent e, EscalationContact c) {
        String json = e.getNotifiedContacts();
        String email = c.getEmail() == null ? "" : c.getEmail().trim().toLowerCase(Locale.ROOT);
        if (json == null || json.isBlank() || email.isEmpty() || !json.toLowerCase(Locale.ROOT).contains(email)) return false;
        try {
            for (Object row : JSON.readValue(json, List.class)) {
                if (row instanceof Map<?, ?> m && m.get("email") != null
                        && email.equals(String.valueOf(m.get("email")).trim().toLowerCase(Locale.ROOT))) return true;
            }
        } catch (Exception ignore) { /* bozuk iz: bilgilendirilmemiş say (adım gider) */ }
        return false;
    }

    private AlertEscalationStep claim(AlertEvent e, EscalationContact c, String outcome, String reason, Instant now) {
        AlertEscalationStep s = new AlertEscalationStep();
        s.setAlertEventId(e.getId());
        s.setContactId(c.getId());
        s.setAlertLevel(levelKey(e.getAlertLevel()));
        s.setDelayMinutes(c.getDelayMinutes());
        s.setTeamId(c.getTeamId());
        s.setOutcome(outcome);
        s.setReason(clip(reason));
        s.setSentAt(ISO.format(now));
        try {
            AlertEscalationStep saved = stepRepo.saveAndFlush(s);
            return saved != null ? saved : s;
        } catch (DataIntegrityViolationException race) {
            log.debug("Eskalasyon adımı başka iş parçacığında/pod'da karara bağlandı: olay #{} kişi #{}", e.getId(), c.getId());
            return null;
        }
    }

    private void finish(AlertEscalationStep s, String outcome, String reason) {
        try {
            s.setOutcome(outcome);
            s.setReason(clip(reason));
            stepRepo.save(s);
        } catch (Exception ex) {
            log.warn("Eskalasyon adımı sonucu yazılamadı (#{}): {}", s.getId(), ex.getMessage());
        }
    }

    private static String levelKey(String level) {
        return level == null || level.isBlank() ? "WARNING" : level.trim().toUpperCase(Locale.ROOT);
    }

    private static String clip(String s) {
        return s == null ? null : (s.length() > 300 ? s.substring(0, 300) : s);
    }

    static Instant parseUtc(String iso) {
        if (iso == null || iso.length() < 19) return null;
        try {
            return LocalDateTime.parse(iso.substring(0, 19)).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> List<List<T>> chunks(Collection<T> items) {
        List<T> all = new ArrayList<>(items);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) out.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        return out;
    }
}

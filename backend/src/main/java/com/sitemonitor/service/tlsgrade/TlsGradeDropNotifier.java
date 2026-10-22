package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TlsGradeChange;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TlsGradeChangeRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.TlsGradeDropMail;
import com.sitemonitor.service.SecretMask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TLS yapılandırma notu DÜŞÜŞÜ → takıma bildirim (2026-10-11, kullanıcı isteği: "TLS notu düştüğünde yalnız uygulama
 * içinde görünüyor").
 *
 * <ul>
 *   <li><b>Kaynak:</b> {@code tls_grade_changes} satırları {@code notify_state=PENDING} doğar (yalnız DROP; bilgi değişimi
 *       ve yükseliş bildirilmez; eski satırlar null — geriye dönük gönderim yok).</li>
 *   <li><b>Bir kez:</b> satırlar koşullu UPDATE ile talep edilir (PENDING → SENDING) — iki pod / iki tur aynı düşüşü
 *       iki kez bildirmez; sonuç SENT ya da "SKIPPED: …" yazılır.</li>
 *   <li><b>Alıcı:</b> kaydın sorumlu (SY) ve UG takımları, her takım YALNIZ kendi satırlarını görür — takım posta kutusu /
 *       varsayılan bildirim grubu ({@link EscalationService#teamEmailsForMonitor}) + takım üyelerine push. Takımsız satır
 *       bildirilmez ("SKIPPED: takım yok").</li>
 *   <li><b>Eşik:</b> {@code site.monitor.tls-grade.drop-notify-min-grade} — yeni not bu nottan YÜKSEKSE bildirilmez
 *       (varsayılan A: A+ dışındaki her düşüş). {@code site.monitor.tls-grade.drop-notify=false} → hepsi "SKIPPED: bildirim
 *       kapalı" (sonradan açılınca eski düşüşler yağmur gibi gelmez).</li>
 *   <li><b>Sistem bakımı</b> susturması açıksa "SKIPPED: sistem bakımı".</li>
 *   <li><b>İz:</b> her takım gönderimi {@code notification_logs} (tetik {@value #TRIGGER}, alarm kimliği 0) — gönderilmeyen
 *       karar da gerekçesiyle yazılır. Hata turu düşürmez.</li>
 * </ul>
 * Zamanlanmış TLS işi ({@link TlsProfileJobService}) her turda, notları karşılaştırdıktan SONRA çağırır (lider +
 * {@code scheduler_lock}); elle yeniden tarama notu karşılaştırmaz — düşüşü bir sonraki tur yazar ve bildirir (2026-10-12).
 *
 * <p><b>Pod ölümü (çok pod R2a, MP-43, 2026-10-22):</b> talep {@code notified_at}'e TALEP anını yazar (yarıda kalan talep
 * düşüşün zamanına göre değil bu damgaya göre yaşlanır); bir satır, sahip takımlarının HEPSİ bitince hemen sonuçlandırılır
 * (eskiden tüm takımlar bitince — ortada ölen pod henüz denenmemiş takımları da "yarıda kaldı" ile kaybettiriyordu). Yarıda
 * kalan (SENDING) satır: hiçbir sahip takımına iz ({@code notification_logs}) yazılmamışsa PENDING'e döner ve yeniden
 * denenir (yalnız uçuştaki takım için en az bir kez); iz varsa "yarıda kaldı" (o takıma gitmiş olabilir — yeniden
 * gönderilmez). Yeniden deneme sayacı DB'de ({@code notify_attempts}; pod belleğinde değil — yeniden başlama sıfırlamaz).
 */
@Slf4j
@Service
public class TlsGradeDropNotifier {

    public static final String TRIGGER = "TLS_GRADE_DROP";
    public static final String KEY_ENABLED = "site.monitor.tls-grade.drop-notify";
    public static final String KEY_MIN_GRADE = "site.monitor.tls-grade.drop-notify-min-grade";
    static final int BATCH = 500;
    static final String SKIP_OFF = "SKIPPED: bildirim kapalı";
    static final String SKIP_BELOW = "SKIPPED: eşik üstü not";
    static final String SKIP_MAINT = "SKIPPED: sistem bakımı";
    static final String SKIP_NO_TEAM = "SKIPPED: takım yok";
    static final String SKIP_NO_RECIPIENT = "SKIPPED: alıcı yok";
    /** Alıcı vardı ama hiçbir kanal teslim edemedi (SMTP hatası, push kuyruğa girmedi) — 2026-10-12'ye kadar "alıcı yok" yazılırdı. */
    static final String NOTIFY_FAILED = "FAILED: gönderilemedi";
    /** Takımın alıcı listesi OKUNAMADI (inceleme 4): "alıcı yok" değildir — günlük satırı bunu, düşüş satırı "gönderilemedi" der. */
    static final String RECIPIENTS_UNREADABLE = "FAILED: alıcılar okunamadı";
    /**
     * Talep edildi ama sonuç yazılamadan tur kesildi (istisna / pod kapandı) — 2026-10-12'ye kadar satır SENDING'de sonsuza
     * dek "sırada" görünürdü. Kısmen gönderilmiş olabileceği için YENİDEN GÖNDERİLMEZ (çift bildirim olmasın).
     */
    static final String NOTIFY_INTERRUPTED = "FAILED: yarıda kaldı";
    /** Bu süreden eski SENDING satırı önceki (yarıda kalmış) bir turdan kalmıştır. */
    static final java.time.Duration STALE_SENDING = java.time.Duration.ofHours(1);
    /** notification_logs.recipient_email / email_status uzunluğu (VARCHAR(255)). */
    private static final int LOG_FIELD_MAX = 255;

    /** Takıma gönderimin sonucu. */
    enum Delivery { DELIVERED, FAILED, NONE, RETRY }

    /**
     * Alıcılar / takım OKUNAMADI ve hiçbir kanal ulaşmadı (inceleme 5): düşüş kalıcı "gönderilemedi" ile kaybolmaz, sonraki
     * turda (10 dk) yeniden denenir — en çok bu kadar kez, sonra FAILED. Sayaç DB'de ({@code notify_attempts}, R2a MP-43):
     * hiçbir takıma ulaşmadan yarıda kalan talebin yeniden denemesi de aynı sayaçtan düşer.
     */
    static final int READ_RETRY_MAX = 6;

    /**
     * Ekran kodu ("Son düşüşler" satırı): sent | pending | off | below | maintenance | no_team | no_recipient; özellikten
     * önceki satır (durum yok) → null. Türkçe durum metni yalnız günlükte kalır, istemci bu kodu çevirir.
     */
    public static String uiCode(String notifyState) {
        if (notifyState == null || notifyState.isBlank()) return null;
        return switch (notifyState) {
            case TlsGradeChange.NOTIFY_SENT -> "sent";
            case TlsGradeChange.NOTIFY_PENDING, TlsGradeChange.NOTIFY_SENDING -> "pending";
            case SKIP_OFF -> "off";
            case SKIP_BELOW -> "below";
            case SKIP_MAINT -> "maintenance";
            case SKIP_NO_TEAM -> "no_team";
            case SKIP_NO_RECIPIENT -> "no_recipient";
            case NOTIFY_FAILED -> "failed";
            case NOTIFY_INTERRUPTED -> "interrupted";
            default -> null;
        };
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TlsGradeChangeRepository changeRepo;
    private final AppSettingsService appSettings;
    private final EscalationService escalation;
    private final EmailNotificationService email;
    private final NotificationLogRepository notificationLogRepo;
    private final TeamRepository teamRepo;

    @Autowired(required = false) private UserPushService push;
    @Autowired(required = false) private SystemMaintenanceService systemMaintenance;
    /** Yarıda kalan talebin izi ({@code notification_logs}) okunur (R2a, MP-43); yoksa yarıda kalan her satır "yarıda kaldı". */
    @Autowired(required = false) private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public TlsGradeDropNotifier(TlsGradeChangeRepository changeRepo, AppSettingsService appSettings, EscalationService escalation,
                                EmailNotificationService email, NotificationLogRepository notificationLogRepo, TeamRepository teamRepo) {
        this.changeRepo = changeRepo;
        this.appSettings = appSettings;
        this.escalation = escalation;
        this.email = email;
        this.notificationLogRepo = notificationLogRepo;
        this.teamRepo = teamRepo;
    }

    void setPush(UserPushService p) { this.push = p; }
    void setSystemMaintenance(SystemMaintenanceService s) { this.systemMaintenance = s; }
    void setJdbc(org.springframework.jdbc.core.JdbcTemplate j) { this.jdbc = j; }

    /** Tur özeti (log + test). */
    public record Result(int claimed, int sent, int skipped, int teams) { }

    /** Bekleyen düşüşleri bildirir. Asla fırlatmaz. */
    public Result notifyPending() {
        try {
            return run();
        } catch (Exception e) {
            log.warn("TLS notu düşüş bildirimi turu tamamlanamadı (sonraki tur yeniden dener): {}", e.toString());
            return new Result(0, 0, 0, 0);
        }
    }

    private Result run() {
        Instant at = Instant.now();
        sweepStaleSending(at);
        List<TlsGradeChange> pending = changeRepo.findByNotifyStateOrderByIdAsc(TlsGradeChange.NOTIFY_PENDING, PageRequest.of(0, BATCH));
        if (pending.isEmpty()) return new Result(0, 0, 0, 0);
        List<Long> ids = pending.stream().map(TlsGradeChange::getId).toList();
        // Talep + damga TEK koşullu UPDATE'te (R2a, MP-43): yarıda kalırsa bu damgaya göre yaşlanır
        String stamp = ISO.format(at);
        int claimed = changeRepo.claimNotify(ids, stamp);
        if (claimed <= 0) return new Result(0, 0, 0, 0);
        // Başka tur bir kısmını aldıysa yalnız bizim talep ettiklerimiz: yeniden okumak yerine durum + damga filtresi
        List<TlsGradeChange> mine = new ArrayList<>();
        for (TlsGradeChange c : changeRepo.findAllById(ids)) {
            if (TlsGradeChange.NOTIFY_SENDING.equals(c.getNotifyState()) && stamp.equals(c.getNotifiedAt())) mine.add(c);
        }
        Set<Long> attempted = new LinkedHashSet<>();
        try {
            return deliver(mine, attempted);
        } catch (RuntimeException e) {
            // Talep edilen satır SENDING'de KALMAZ (R2a, MP-43): hiçbir sahip takımı denenmemiş satır PENDING'e döner (kimseye
            // gitmedi — çift bildirim olamaz; DB sayacıyla sınırlı), denenmiş olan "yarıda kaldı" olur (yeniden gönderilmez)
            List<TlsGradeChange> open = new ArrayList<>();
            for (TlsGradeChange c : changeRepo.findAllById(mine.stream().map(TlsGradeChange::getId).toList())) {
                if (TlsGradeChange.NOTIFY_SENDING.equals(c.getNotifyState())) open.add(c);
            }
            List<TlsGradeChange> interrupted = new ArrayList<>();
            List<TlsGradeChange> untouched = new ArrayList<>();
            for (TlsGradeChange c : open) (attempted.contains(c.getId()) ? interrupted : untouched).add(c);
            retryOrFail(untouched, NOTIFY_INTERRUPTED, ISO.format(Instant.now()));
            finish(interrupted, NOTIFY_INTERRUPTED, ISO.format(Instant.now()));
            throw e;
        }
    }

    /**
     * Önceki turdan SENDING'de kalmış satırlar (pod tur ortasında kapandı / öldü). Yaş TALEP damgasına göre ({@code notified_at};
     * damgasız eski satırda düşüş zamanı) — {@link #STALE_SENDING}'den eskiyse (R2a, MP-43): sahip takımlarından HİÇBİRİNE
     * talepten bu yana iz yazılmamışsa PENDING'e döner (kimseye gitmedi; DB sayacıyla sınırlı), iz varsa "yarıda kaldı"
     * (gitmiş olabilir — yeniden gönderilmez). İz okunamazsa "yarıda kaldı" (bugünkü güvenli karar).
     */
    void sweepStaleSending(Instant now) {
        try {
            String cutoff = ISO.format(now.minus(STALE_SENDING));
            List<TlsGradeChange> stale = new ArrayList<>();
            for (TlsGradeChange c : changeRepo.findByNotifyStateOrderByIdAsc(TlsGradeChange.NOTIFY_SENDING, PageRequest.of(0, BATCH))) {
                String age = c.getNotifiedAt() != null ? c.getNotifiedAt() : c.getChangedAt();
                if (age != null && age.compareTo(cutoff) < 0) stale.add(c);
            }
            if (stale.isEmpty()) return;
            List<TlsGradeChange> untraced = new ArrayList<>();
            List<TlsGradeChange> traced = new ArrayList<>();
            List<String[]> traces = tracesSince(stale);
            for (TlsGradeChange c : stale) (traces != null && !hasTrace(c, traces) ? untraced : traced).add(c);
            // Koşullu: yalnız hâlâ SENDING olan satır (bu arada sonuçlanan SENT / SKIPPED ezilmez)
            List<Long> ids = traced.stream().map(TlsGradeChange::getId).toList();
            for (int i = 0; i < ids.size(); i += BATCH) {
                changeRepo.finishNotifyIfState(ids.subList(i, Math.min(ids.size(), i + BATCH)), TlsGradeChange.NOTIFY_SENDING,
                        clip(NOTIFY_INTERRUPTED), ISO.format(now));
            }
            retryOrFail(untraced, NOTIFY_INTERRUPTED, ISO.format(now));
            log.warn("TLS notu düşüş bildirimi: önceki turda yarıda kalan {} düşüş — {} 'yarıda kaldı' (gitmiş olabilir, yeniden "
                    + "gönderilmez), {} hiçbir takıma gitmemiş, yeniden denenecek", stale.size(), traced.size(), untraced.size());
        } catch (Exception e) {
            log.debug("TLS notu düşüş bildirimi: yarıda kalan satırlar okunamadı: {}", e.toString());
        }
    }

    /**
     * Yarıda kalan satırların en eski talep damgasından bu yana yazılmış {@value #TRIGGER} izleri ({@code [alıcı adı, zaman]});
     * okunamazsa {@code null} (karar: "yarıda kaldı").
     */
    private List<String[]> tracesSince(List<TlsGradeChange> rows) {
        if (jdbc == null) return null;
        String from = null;
        for (TlsGradeChange c : rows) {
            String st = c.getNotifiedAt() != null ? c.getNotifiedAt() : c.getChangedAt();
            if (st != null && (from == null || st.compareTo(from) < 0)) from = st;
        }
        if (from == null) return null;
        try {
            return jdbc.query("SELECT recipient_name, sent_at FROM notification_logs WHERE trigger = ? AND sent_at >= ?",
                    (rs, i) -> new String[] { rs.getString(1), rs.getString(2) }, TRIGGER, from);
        } catch (RuntimeException e) {
            log.debug("TLS notu düşüş bildirimi izleri okunamadı: {}", e.toString());
            return null;
        }
    }

    /**
     * Talebin turuna ait izin aranacağı süre: tur en çok birkaç dakika sürer (iş turu 8 dk bütçeli, 10 dk arayla) — sonraki
     * turların aynı takıma yazdığı iz bu satırın gönderildiğini göstermez.
     */
    static final java.time.Duration TRACE_WINDOW = java.time.Duration.ofMinutes(10);

    /**
     * Satırın sahip takımlarından birine talep damgasından sonraki {@link #TRACE_WINDOW} içinde iz yazılmış mı (takım adı ya da
     * "Takım #id"). Damga okunamazsa {@code true} (güvenli karar: "yarıda kaldı").
     */
    private boolean hasTrace(TlsGradeChange c, List<String[]> traces) {
        String since = c.getNotifiedAt() != null ? c.getNotifiedAt() : c.getChangedAt();
        String until;
        try {
            until = ISO.format(java.time.LocalDateTime.parse(since).toInstant(ZoneOffset.UTC).plus(TRACE_WINDOW));
        } catch (RuntimeException e) {
            return true;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Long t : owners(c)) {
            names.add("Takım #" + t);
            try {
                teamRepo.findById(t).map(Team::getName).ifPresent(names::add);
            } catch (RuntimeException e) {
                return true;                                            // takım okunamadı: güvenli karar ("yarıda kaldı")
            }
        }
        for (String[] tr : traces) {
            if (tr[0] != null && names.contains(tr[0]) && tr[1] != null && tr[1].compareTo(since) >= 0 && tr[1].compareTo(until) < 0) {
                return true;
            }
        }
        return false;
    }

    private static Set<Long> owners(TlsGradeChange c) {
        Set<Long> o = new LinkedHashSet<>();
        if (c.getTeamId() != null) o.add(c.getTeamId());
        if (c.getUgTeamId() != null) o.add(c.getUgTeamId());
        return o;
    }

    /**
     * Yeniden denenecek satırlar: DB sayacı tavanın altındaysa PENDING'e döner (sayaç +1), doluysa {@code finalState}. Geçiş
     * hatası yutulur (satır SENDING kalır; bir saat sonra süpürme karar verir).
     */
    private void retryOrFail(List<TlsGradeChange> rows, String finalState, String now) {
        if (rows == null || rows.isEmpty()) return;
        List<Long> back = new ArrayList<>();
        List<TlsGradeChange> capped = new ArrayList<>();
        for (TlsGradeChange c : rows) {
            int n = (c.getNotifyAttempts() == null ? 0 : c.getNotifyAttempts()) + 1;
            if (n < READ_RETRY_MAX) back.add(c.getId());
            else capped.add(c);
        }
        try {
            if (!capped.isEmpty()) {
                List<Long> ids = capped.stream().map(TlsGradeChange::getId).toList();
                changeRepo.finishNotifyIfState(ids, TlsGradeChange.NOTIFY_SENDING, clip(finalState), now);
            }
            if (!back.isEmpty()) changeRepo.retryNotify(back);
        } catch (RuntimeException e) {
            log.warn("TLS notu düşüş bildirimi: {} düşüş yeniden denemeye bırakılamadı (yarıda kalan satır süpürmesi karar verir): {}",
                    rows.size(), e.toString());
        }
    }

    private Result deliver(List<TlsGradeChange> mine, Set<Long> attempted) {
        String now = ISO.format(Instant.now());

        if (!appSettings.getBoolean(KEY_ENABLED, true)) {
            finish(mine, SKIP_OFF, now);
            return new Result(mine.size(), 0, mine.size(), 0);
        }
        if (systemMaintenance != null && safeMuted()) {
            finish(mine, SKIP_MAINT, now);
            log.info("TLS notu düşüş bildirimi: {} düşüş sistem bakımı susturmasıyla gönderilmedi", mine.size());
            return new Result(mine.size(), 0, mine.size(), 0);
        }
        String minGrade = normalizeMinGrade(appSettings.getString(KEY_MIN_GRADE, "A"));
        List<TlsGradeChange> eligible = new ArrayList<>();
        List<TlsGradeChange> below = new ArrayList<>();
        List<TlsGradeChange> teamless = new ArrayList<>();
        for (TlsGradeChange c : mine) {
            if (TlsGradeRules.rank(c.getToGrade()) > TlsGradeRules.rank(minGrade)) below.add(c);
            else if (c.getTeamId() == null && c.getUgTeamId() == null) teamless.add(c);
            else eligible.add(c);
        }
        finish(below, SKIP_BELOW, now);
        finish(teamless, SKIP_NO_TEAM, now);

        // Takım başına satırlar (SY + UG; aynı takım iki kez gelirse tek satır)
        Map<Long, List<TlsGradeChange>> byTeam = new LinkedHashMap<>();
        Map<Long, Set<Long>> remaining = new LinkedHashMap<>();
        for (TlsGradeChange c : eligible) {
            Set<Long> owners = owners(c);
            remaining.put(c.getId(), new LinkedHashSet<>(owners));
            for (Long t : owners) byTeam.computeIfAbsent(t, k -> new ArrayList<>()).add(c);
        }
        Set<Long> delivered = new LinkedHashSet<>();
        Set<Long> failed = new LinkedHashSet<>();
        Set<Long> retry = new LinkedHashSet<>();
        int[] tally = new int[3];                                       // gönderildi, gönderilemedi, alıcı yok
        int teamsDelivered = 0;
        String appUrl = appSettings.getString("site.monitor.app.base-url", "");
        for (Map.Entry<Long, List<TlsGradeChange>> e : byTeam.entrySet()) {
            for (TlsGradeChange c : e.getValue()) attempted.add(c.getId());
            Delivery d;
            try {
                d = sendToTeam(e.getKey(), e.getValue(), appUrl);
            } catch (RuntimeException ex) {
                // Bir takımın hatası ötekileri DURDURMAZ (2026-10-12): o takım "gönderilemedi", sıradaki takım sürer. Karar
                // iz bırakır (inceleme 5: eskiden bu yolda notification_logs satırı yazılmıyordu).
                log.warn("TLS notu düşüş bildirimi: takım {} gönderilemedi: {}", e.getKey(), ex.toString());
                saveLog("Takım #" + e.getKey(), List.of(), "TLS yapılandırma notu düştü",
                        NOTIFY_FAILED + " (" + ex.getClass().getSimpleName() + ")");
                d = Delivery.FAILED;
            }
            if (d == Delivery.DELIVERED) teamsDelivered++;
            // Sahip takımlarının HEPSİ biten satır HEMEN sonuçlanır (R2a, MP-43): ortada ölen pod yalnız uçuştaki takımın
            // satırlarını SENDING'de bırakır, bitmiş takımların sonucu yazılmıştır
            List<TlsGradeChange> done = new ArrayList<>();
            for (TlsGradeChange c : e.getValue()) {
                if (d == Delivery.DELIVERED) delivered.add(c.getId());
                else if (d == Delivery.FAILED) failed.add(c.getId());
                else if (d == Delivery.RETRY) retry.add(c.getId());
                Set<Long> left = remaining.get(c.getId());
                if (left != null && left.remove(e.getKey()) && left.isEmpty()) done.add(c);
            }
            settle(done, delivered, failed, retry, now, tally);
        }
        log.info("TLS notu düşüş bildirimi: {} düşüş · {}/{} takıma ulaştı · {} düşüş gönderilemedi · {} atlandı", mine.size(),
                teamsDelivered, byTeam.size(), tally[1], below.size() + teamless.size() + tally[2]);
        return new Result(mine.size(), tally[0], mine.size() - tally[0], byTeam.size());
    }

    /**
     * Sahip takımlarının hepsi biten satırların sonucu: en az bir takıma ulaştıysa SENT; hiçbirine ulaşamadı ama denendiyse
     * FAILED; yalnız okuma hatası → yeniden deneme (DB sayacı, tavanda FAILED); hiç alıcı yoksa "alıcı yok". Yeniden
     * denenecekler PENDING'e EN SON döner (inceleme 6): bu geçiş hata verirse teslim edilmiş satırların sonucu zaten yazılmıştır.
     */
    private void settle(List<TlsGradeChange> done, Set<Long> delivered, Set<Long> failed, Set<Long> retry, String now, int[] tally) {
        if (done.isEmpty()) return;
        List<TlsGradeChange> sent = new ArrayList<>();
        List<TlsGradeChange> notDelivered = new ArrayList<>();
        List<TlsGradeChange> noRecipient = new ArrayList<>();
        List<TlsGradeChange> again = new ArrayList<>();
        for (TlsGradeChange c : done) {
            Long id = c.getId();
            if (delivered.contains(id)) sent.add(c);
            else if (failed.contains(id)) notDelivered.add(c);
            else if (retry.contains(id)) again.add(c);
            else noRecipient.add(c);
        }
        finish(sent, TlsGradeChange.NOTIFY_SENT, now);
        finish(notDelivered, NOTIFY_FAILED, now);
        finish(noRecipient, SKIP_NO_RECIPIENT, now);
        tally[0] += sent.size();
        tally[2] += noRecipient.size();
        int capped = 0;
        for (TlsGradeChange c : again) if ((c.getNotifyAttempts() == null ? 0 : c.getNotifyAttempts()) + 1 >= READ_RETRY_MAX) capped++;
        tally[1] += notDelivered.size() + capped;
        if (again.isEmpty()) return;
        // Tavanı dolan "gönderilemedi", gerisi PENDING (sayaç +1) — geçiş hatası teslim edilmiş satırlara dokunmaz
        List<Long> back = new ArrayList<>();
        List<TlsGradeChange> cappedRows = new ArrayList<>();
        for (TlsGradeChange c : again) {
            if ((c.getNotifyAttempts() == null ? 0 : c.getNotifyAttempts()) + 1 < READ_RETRY_MAX) back.add(c.getId());
            else cappedRows.add(c);
        }
        finish(cappedRows, NOTIFY_FAILED, now);
        if (back.isEmpty()) return;
        try {
            changeRepo.retryNotify(back);
        } catch (RuntimeException e) {
            // Geçiş yazılamadı: satır SENDING'de kalmaz — "yarıda kaldı" (izi var: alıcılar okunamadı); sıradaki takım sürer
            log.warn("TLS notu düşüş bildirimi: {} düşüş yeniden denemeye bırakılamadı — 'yarıda kaldı': {}", back.size(), e.toString());
            try {
                changeRepo.finishNotifyIfState(back, TlsGradeChange.NOTIFY_SENDING, clip(NOTIFY_INTERRUPTED), now);
            } catch (RuntimeException ignored) {
                // bir saat sonra yarıda kalan satır süpürmesi karar verir
            }
        }
    }

    /**
     * Takıma tek özet: e-posta (alıcı varsa) + üyelere push. En az bir kanal ulaştıysa (e-posta SENT / kuyrukta, ya da push
     * kuyruğa girdi) DELIVERED; bir kanal denendi ama hiçbiri ulaşmadıysa FAILED; hiç alıcı yoksa NONE.
     */
    private Delivery sendToTeam(Long teamId, List<TlsGradeChange> rows, String appUrl) {
        String teamName;
        try {
            teamName = teamRepo.findById(teamId).map(Team::getName).orElse("Takım #" + teamId);
        } catch (RuntimeException ex) {
            // Takım okunamadı: geçici okuma hatası — yeniden denenir (inceleme 5), iz bırakılır
            log.warn("TLS notu düşüş bildirimi: takım {} okunamadı: {}", teamId, ex.toString());
            saveLog("Takım #" + teamId, List.of(), "TLS yapılandırma notu düştü", RECIPIENTS_UNREADABLE);
            return Delivery.RETRY;
        }
        List<TlsGradeDropMail.Row> mailRows = rows.stream()
                .map(c -> new TlsGradeDropMail.Row(c.getDomain(), c.getFromGrade(), c.getToGrade(), c.getReasons(), c.getChangedAt()))
                .toList();
        boolean delivered = false;
        boolean attempted = false;
        List<String> recipients;
        boolean unreadable = false;
        try {
            recipients = escalation.teamEmailsForMonitor(teamId, null);
        } catch (Exception ex) {
            // Okuma hatası "alıcı yok" DEĞİLDİR (inceleme 4, 2026-10-12): eskiden düşüş kalıcı "SKIPPED: alıcı yok" ile
            // kapanıyordu — ekranda "takımın alıcısı yok" yanlışı. Şimdi denenmiş-ama-ulaşılamamış (FAILED) sayılır;
            // push yine denenir, ulaşırsa düşüş SENT olur.
            log.warn("TLS notu düşüş bildirimi: takım {} alıcıları okunamadı: {}", teamId, ex.toString());
            recipients = List.of();
            unreadable = true;
        }
        if (recipients == null) recipients = List.of();
        String subject = TlsGradeDropMail.subject(teamName, mailRows);
        String status;
        if (unreadable) {
            status = RECIPIENTS_UNREADABLE;
            attempted = true;
        } else if (recipients.isEmpty()) {
            status = SKIP_NO_RECIPIENT;
        } else {
            MailDoc.Mail mail = TlsGradeDropMail.build(teamName, mailRows, appUrl);
            status = email.sendHtml(recipients.toArray(new String[0]), null, subject, mail.html(), null);
            // QUEUED_RETRY (SMTP 421 vb.) teslim kuyruğunda — gönderilmiş sayılır; SKIPPED… (kanal kapalı / pasif) deneme değildir
            delivered = status != null && (status.startsWith("SENT") || status.startsWith("QUEUED"));
            attempted = status == null || !status.startsWith("SKIPPED");
            log.info("TLS notu düşüş e-postası: takım={} sertifika={} alıcı={} durum={}", teamName, rows.size(),
                    SecretMask.maskEmails(recipients), status);
        }
        int queued = 0;
        if (push != null) {
            try {
                String worst = TlsGradeDropMail.worstGrade(mailRows);
                String level = TlsGradeRules.rank(worst) <= TlsGradeRules.rank("F") ? "CRITICAL"
                        : TlsGradeRules.rank(worst) <= TlsGradeRules.rank("C") ? "HIGH" : "WARNING";
                TlsGradeChange first = rows.get(0);
                String msg = rows.size() == 1
                        ? first.getDomain() + " TLS yapılandırma notu " + first.getFromGrade() + " → " + first.getToGrade() + " düştü"
                        : rows.size() + " sertifikanın TLS yapılandırma notu düştü (en düşük " + worst + ")";
                String msgEn = rows.size() == 1
                        ? "TLS configuration grade of " + first.getDomain() + " dropped " + first.getFromGrade() + " → " + first.getToGrade()
                        : "The TLS configuration grade dropped on " + rows.size() + " certificates (lowest " + worst + ")";
                // Anahtar TÜM satır kimliklerini taşır; kolona (60) push servisi sığdırır (önek + tüm anahtarın özeti —
                // UserPushDelivery.fitDedupeKey). Eskiden 180'e kırpılıyordu: 60'ı aşan anahtar INSERT'i düşürüyor, push
                // sessizce kayboluyordu (ALERTS-12); kırpma da farklı düşüş kümelerini aynı anahtara indirebilirdi.
                String dedupe = "tls-drop:" + teamId + ":" + rows.stream().map(c -> String.valueOf(c.getId())).reduce((a, b) -> a + "," + b).orElse("");
                Map<String, Object> pr = push.enqueueTeamNoticeLocalized(teamId, TRIGGER, level, "CERT",
                        rows.size() == 1 ? first.getDomain() : teamName, new UserPushService.LocalizedText(msg, msgEn), dedupe, Set.of());
                queued = pr.get("queued") instanceof Number n ? n.intValue() : 0;
                if (queued > 0) delivered = true;
            } catch (Exception ex) {
                log.warn("TLS notu düşüş push'u kuyruğa alınamadı (takım {}): {}", teamId, ex.toString());
            }
        }
        saveLog(teamName, recipients, subject, status + (queued > 0 ? " · push " + queued : ""));
        if (delivered) return Delivery.DELIVERED;
        if (unreadable) return Delivery.RETRY;   // alıcılar okunamadı ve push da ulaşmadı → kalıcı hata değil, yeniden dene
        return attempted ? Delivery.FAILED : Delivery.NONE;
    }

    private void saveLog(String teamName, List<String> recipients, String subject, String status) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);   // alarm kaynaklı değil (kolon NOT NULL) — haftalık rapor deseni
            n.setSentAt(ISO.format(Instant.now()));
            n.setRecipientName(teamName);
            n.setRecipientEmail(clipTo(String.join(", ", recipients), LOG_FIELD_MAX));
            n.setRecipientRole("COMBINED");
            n.setSubject(subject);
            n.setEmailStatus(clipTo(status, LOG_FIELD_MAX));
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(TRIGGER);
            n.setEmailFrom(email.getEmailFrom());
            notificationLogRepo.save(n);
        } catch (Exception e) {
            log.warn("TLS notu düşüş bildirimi günlük kaydı yazılamadı: {}", e.getMessage());
        }
    }

    /** Sütun uzunluğuna kırp (uzun alıcı listesi / hata metni INSERT'i düşürmesin — tek iz kaybolurdu). */
    static String clipTo(String v, int max) {
        if (v == null || v.length() <= max) return v;
        return v.substring(0, Math.max(0, max - 1)) + "…";
    }

    private void finish(List<TlsGradeChange> rows, String state, String now) {
        if (rows == null || rows.isEmpty()) return;
        List<Long> ids = rows.stream().map(TlsGradeChange::getId).toList();
        for (int i = 0; i < ids.size(); i += BATCH) {
            changeRepo.finishNotify(ids.subList(i, Math.min(ids.size(), i + BATCH)), clip(state), now);
        }
    }

    private boolean safeMuted() {
        try { return systemMaintenance.notificationsMuted(); } catch (Exception e) { return false; }
    }

    /** Geçerli not (A+…F) ya da varsayılan A. */
    static String normalizeMinGrade(String raw) {
        String g = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        return TlsGradeRules.isGrade(g) ? g : "A";
    }

    private static String clip(String s) {
        return s == null ? null : s.length() <= 60 ? s : s.substring(0, 60);
    }
}

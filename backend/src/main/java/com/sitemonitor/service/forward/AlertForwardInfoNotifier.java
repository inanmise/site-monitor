package com.sitemonitor.service.forward;

import com.sitemonitor.model.AlertForward;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushRecipientResolver;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.access.TeamAccessGrantService;
import com.sitemonitor.service.mail.AlertForwardInfoMail;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.noc.NocMonitorDirectory;
import com.sitemonitor.service.noc.NocType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Alarm iletimi bilgi bildirimi (2026-11-05, kullanıcı kararı 8): iletim eklenince (yeni alıcı takım / yeni üye) alıcılara
 * TEK SEFERLİK bilgi e-postası ({@link AlertForwardInfoMail}) + push. Kaldırılınca bildirim yok.
 *
 * <p>Alıcılar: TEAM → alıcı takımın alarm adresi (yönlendirmenin kullandığı liste) + takım push'u (takımın push kuralları);
 * MEMBERS → yeni üyelerin e-postası + doğrudan push. Her karar bir {@code notification_logs} satırı (tetik
 * {@value #TRIGGER}, alertEventId 0, rol {@value #ROLE}, alıcı adı alıcı takım). Sistem bakımı susturması bilgi postasını
 * da susturur ({@code SKIPPED: sistem bakımı}). Push metnine bağlantı YAZILMAZ. Hiçbir hata çağıranı bozmaz (sayaçlar 0).
 */
@Slf4j
@Service
public class AlertForwardInfoNotifier {

    public static final String TRIGGER = "ALERT_FORWARD_ADDED";
    public static final String PUSH_TYPE = "ALERT_FORWARD";
    public static final String ROLE = "FORWARD";
    static final String LEVEL = "WARNING";
    static final String NO_RECIPIENT = "SKIPPED: alıcı yok";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AlertForwardService forwards;
    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;

    @Autowired(required = false) private EmailNotificationService email;
    @Autowired(required = false) private NotificationLogRepository logs;
    @Autowired(required = false) private UserPushService push;
    @Autowired(required = false) private SystemMaintenanceService maintenance;
    @Autowired(required = false) private AppSettingsService appSettings;
    @Autowired(required = false) private NocMonitorDirectory directory;

    public AlertForwardInfoNotifier(AlertForwardService forwards, TeamRepository teamRepo, AppUserRepository userRepo) {
        this.forwards = forwards;
        this.teamRepo = teamRepo;
        this.userRepo = userRepo;
    }

    void setEmail(EmailNotificationService e) { this.email = e; }
    void setLogs(NotificationLogRepository l) { this.logs = l; }
    void setPush(UserPushService p) { this.push = p; }
    void setMaintenance(SystemMaintenanceService m) { this.maintenance = m; }
    void setDirectory(NocMonitorDirectory d) { this.directory = d; }

    /**
     * Yanıttaki {@code info} (2026-11-06 biçimi, ön yüzle ortak): {@code requested} = bu kayıtta bilgi bildirimi gerekti mi
     * (yeni alıcı ya da etkinleştirme — iletim etkin); {@code emails} = bilgi postasının verildiği adres sayısı; {@code push} =
     * kuyruğa alınan push sayısı; {@code muted} = sistem bakımı susturdu (posta / push gitmedi, nedeni bu). Ön yüz "bilgi
     * gitmedi" uyarısını yalnız {@code requested && !muted && emails + push == 0} iken gösterir.
     */
    public record Info(boolean requested, int emails, int push, boolean muted) {
        public static final Info NONE = new Info(false, 0, 0, false);

        /** Gönderim denendi (requested) — sayılarla. */
        public Info(int emails, int push) {
            this(true, emails, push, false);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requested", requested);
            m.put("emails", emails);
            m.put("push", push);
            m.put("muted", muted);
            return m;
        }
    }

    /**
     * @param f            kaydedilmiş iletim
     * @param wholeTeam    alıcı takımın tamamı YENİ alıcı (oluşturma ya da takım değişti)
     * @param newMemberIds YENİ eklenen seçili üyeler
     * @param addedBy      ekleyenin görünen adı
     */
    public Info notifyAdded(AlertForward f, boolean wholeTeam, List<Long> newMemberIds, String addedBy) {
        if (f == null || f.getRecipientTeamId() == null) return Info.NONE;
        List<Long> members = newMemberIds == null ? List.of() : newMemberIds;
        if (!wholeTeam && members.isEmpty()) return Info.NONE;
        if (!f.isEnabled()) return Info.NONE;   // duraklatılmış iletim bilgi göndermez — etkinleştirilince gider (2026-11-06)
        try {
            return send(f, wholeTeam, members, addedBy);
        } catch (Exception e) {
            log.warn("Alarm iletimi bilgi bildirimi gönderilemedi (iletim {}): {}", f.getId(), e.toString());
            return new Info(0, 0);
        }
    }

    private Info send(AlertForward f, boolean wholeTeam, List<Long> newMemberIds, String addedBy) {
        Team owner = teamRepo.findById(f.getOwnerTeamId()).orElse(null);
        Team recipient = teamRepo.findById(f.getRecipientTeamId()).orElse(null);
        String ownerName = owner != null && owner.getName() != null ? owner.getName() : "Takım #" + f.getOwnerTeamId();
        String recipientName = recipient != null && recipient.getName() != null ? recipient.getName() : "Takım #" + f.getRecipientTeamId();
        List<AppUser> users = new ArrayList<>();
        if (!wholeTeam) {
            // Push alıcıları da e-posta gibi yalnız alıcı takımda HÂLÂ üye olanlar (2026-11-06, hata avı H2#6): etkinleştirmede
            // bilgi kayıtlı TÜM üyelere gider — takımdan ayrılmış üye ("takımda değil") push almasın. Üyelik projeksiyonla
            // (birincil + ek) okunur; okunamazsa push'a kimse eklenmez (e-posta zaten aynı kuralla süzülür).
            Map<Long, AlertForwardService.Member> membership;
            try {
                membership = forwards.members(newMemberIds);
            } catch (RuntimeException e) {
                log.debug("Alarm iletimi bilgi push'u: üyelikler okunamadı (iletim {}): {}", f.getId(), e.toString());
                membership = Map.of();
            }
            for (AppUser u : userRepo.findAllById(newMemberIds)) {
                if (u == null || Boolean.FALSE.equals(u.getActive()) || u.getUsername() == null || u.getUsername().isBlank()) continue;
                AlertForwardService.Member m = membership.get(u.getId());
                if (m == null || !m.inTeam(f.getRecipientTeamId())) continue;
                users.add(u);
            }
        }
        List<String> addresses = wholeTeam ? forwards.teamAlarmEmails(f.getRecipientTeamId())
                : forwards.memberEmails(f.getRecipientTeamId(), newMemberIds);
        List<String> scopeItems = scopeItems(f);
        String subject = AlertForwardInfoMail.subject(ownerName);

        if (muted()) {
            saveLog(recipientName, addresses, subject, SystemMaintenanceService.STATUS_SKIPPED);
            return new Info(true, 0, 0, true);
        }

        String status;
        int mailed = 0;
        if (addresses.isEmpty() || email == null) {
            status = NO_RECIPIENT;
        } else {
            List<String> names = new ArrayList<>();
            for (AppUser u : users) names.add(displayName(u));
            String appUrl = appSettings == null ? "" : appSettings.getString("site.monitor.app.base-url", "");
            MailDoc.Mail mail = AlertForwardInfoMail.build(new AlertForwardInfoMail.Info(ownerName, recipientName, wholeTeam,
                    names, f.getScopeKind(), scopeItems, addedBy, f.getNote(),
                    TeamAccessGrantService.expiresOn(f.getExpiresAt()), appUrl));
            status = email.sendHtml(addresses.toArray(new String[0]), null, subject, mail.html(), mail.text(), null, false, null);
            boolean delivered = status != null && (status.startsWith("SENT") || status.startsWith("QUEUED"));
            if (delivered) mailed = addresses.size();
            log.info("Alarm iletimi bilgi postası: iletim={} sahip={} alıcı={} adres={} durum={}", f.getId(), ownerName,
                    recipientName, SecretMask.maskEmails(addresses), status);
        }

        int queued = 0;
        if (push != null) {
            try {
                UserPushService.LocalizedText text = pushText(ownerName, f.getScopeKind(), scopeItems.size());
                String key = "fwd-add:" + f.getId() + ":" + (f.getVersion() == null ? 0 : f.getVersion());
                Map<String, Object> res;
                if (wholeTeam) {
                    res = push.enqueueTeamNoticeLocalized(f.getRecipientTeamId(), TRIGGER, LEVEL, PUSH_TYPE, ownerName, text, key, Set.of());
                } else {
                    List<UserPushService.DirectRecipient> direct = new ArrayList<>();
                    Instant now = Instant.now();
                    for (AppUser u : users) {
                        String skip = UserPushRecipientResolver.preferenceSkip(u, LEVEL, null, now);
                        direct.add(new UserPushService.DirectRecipient(u.getUsername().trim(), displayName(u),
                                Boolean.TRUE.equals(u.getPushOptOut()), u.getPushLang(), skip));
                    }
                    res = push.enqueueDirectLocalized(direct, f.getRecipientTeamId(), TRIGGER, LEVEL, PUSH_TYPE, ownerName, text, key);
                }
                queued = res != null && res.get("queued") instanceof Number n ? n.intValue() : 0;
            } catch (Exception e) {
                log.warn("Alarm iletimi bilgi push'u kuyruğa alınamadı (iletim {}): {}", f.getId(), e.toString());
            }
        }
        saveLog(recipientName, addresses, subject, status + (queued > 0 ? " · push " + queued : ""));
        return new Info(mailed, queued);
    }

    /** Push metni — iki dilli, bağlantısız. */
    static UserPushService.LocalizedText pushText(String owner, String scopeKind, int items) {
        String tr;
        String en;
        if ("TYPES".equals(scopeKind)) {
            tr = items + " izleme türü";
            en = items + (items == 1 ? " monitor type" : " monitor types");
        } else if ("MONITORS".equals(scopeKind)) {
            tr = items + " izleme";
            en = items + (items == 1 ? " monitor" : " monitors");
        } else {
            tr = "tüm izlemeler";
            en = "all monitors";
        }
        return new UserPushService.LocalizedText(owner + " takımı alarmlarını size iletiyor (" + tr + ").",
                "Team " + owner + " is forwarding its alerts to you (" + en + ").");
    }

    /** Kapsam öğeleri: tür adları (TYPES) ya da izleme adları (MONITORS; tür başına tek okuma, silinmiş → atlanır). */
    private List<String> scopeItems(AlertForward f) {
        List<String> out = new ArrayList<>();
        if (AlertForward.SCOPE_TYPES.equals(f.getScopeKind())) {
            for (String k : AlertForwardService.typesOf(f)) out.add(MonitorTypeCatalog.label(k));
        } else if (AlertForward.SCOPE_MONITORS.equals(f.getScopeKind())) {
            List<AlertForwardService.MonitorRef> refs = AlertForwardService.monitorsOf(f);
            Map<String, Map<Long, NocMonitorDirectory.Row>> byType = new HashMap<>();
            for (AlertForwardService.MonitorRef r : refs) {
                Map<Long, NocMonitorDirectory.Row> rows = byType.computeIfAbsent(r.type(), this::rowsOf);
                NocMonitorDirectory.Row row = rows.get(r.id());
                String label = MonitorTypeCatalog.label(r.type());
                out.add(row == null ? label + " #" + r.id() : label + ": " + row.name());
            }
        }
        return out;
    }

    private Map<Long, NocMonitorDirectory.Row> rowsOf(String type) {
        Map<Long, NocMonitorDirectory.Row> m = new HashMap<>();
        NocType t = AlertForwardService.nocTypeOf(type);
        if (directory == null || t == null) return m;
        try {
            for (NocMonitorDirectory.Row r : directory.ofType(t)) m.put(r.id(), r);
        } catch (RuntimeException e) {
            log.debug("Alarm iletimi bilgi postası: {} izlemeleri okunamadı: {}", t, e.toString());
        }
        return m;
    }

    private boolean muted() {
        try {
            return maintenance != null && maintenance.notificationsMuted();
        } catch (Exception e) {
            return false;
        }
    }

    private static String displayName(AppUser u) {
        return u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getUsername();
    }

    private void saveLog(String recipientName, List<String> addresses, String subject, String status) {
        if (logs == null) return;
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);   // alarm kaynaklı değil (kolon NOT NULL)
            n.setSentAt(ISO.format(Instant.now()));
            n.setRecipientName(recipientName);
            n.setRecipientEmail(String.join(", ", addresses));
            n.setRecipientRole(ROLE);
            n.setSubject(subject);
            n.setEmailStatus(status);
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(TRIGGER);
            if (email != null) n.setEmailFrom(email.getEmailFrom());
            logs.save(n);
        } catch (Exception e) {
            log.warn("Alarm iletimi bilgi bildirimi günlük kaydı yazılamadı: {}", e.getMessage());
        }
    }
}

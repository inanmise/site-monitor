package com.sitemonitor.service;

import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Yönetim Paneli özet şeridi (2026-09-20): sayaçlar + sağlık uyarıları. Yönetici sekmeleri tek tek gezmeden
 * "neyin eksik" olduğunu görsün: lidersiz takım, adresi olmayan grup, pasif eskalasyon kişisi, kilitli kullanıcı,
 * tek aktif ADMIN, 90+ gündür girmeyen hesaplar. Kapsamlı kullanıcı (müdür/PO) yalnız görüş alanındaki takımlar
 * üzerinden sayar; kullanıcı sayıları da o takımların üyeleridir.
 */
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    static final int DORMANT_DAYS = 90;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;
    private final EscalationContactRepository contactRepo;
    private final NotificationGroupRepository groupRepo;
    private final AlertThresholdRepository thresholdRepo;

    /** @param scope görülebilir takımlar; {@code null} = sınırsız (global görücü) */
    public Map<String, Object> overview(Collection<Long> scope) {
        boolean unrestricted = scope == null;
        Set<Long> allowed = unrestricted ? Set.of() : new HashSet<>(scope);
        String dormantCut = ISO.format(Instant.now().minus(Duration.ofDays(DORMANT_DAYS)));

        // Takımlar
        int teams = 0, teamsActive = 0, teamsNoLeader = 0, teamsNoEmail = 0;
        for (Team t : teamRepo.findAll()) {
            if (!unrestricted && !allowed.contains(t.getId())) continue;
            teams++;
            boolean active = !Boolean.FALSE.equals(t.getActive());
            if (active) teamsActive++;
            if (active && t.getLeaderId() == null) teamsNoLeader++;
            if (active && (t.getEmail() == null || t.getEmail().isBlank())) teamsNoEmail++;
        }
        // Kullanıcılar — dar satırlar (2026-11-17 perf): findAll() her kullanıcının fotoğrafını ve takım koleksiyonunu yüklüyordu.
        // Satır: [id, teamId, active, systemRole, permanentLock, lastLoginAt]; ek takımlar yalnız kapsamlı görünümde okunur.
        Set<Long> extraMember = new HashSet<>();
        if (!unrestricted) {
            for (Object[] pr : userRepo.findAllTeamMembershipPairs()) {
                if (pr != null && pr.length >= 2 && pr[0] instanceof Number id && pr[1] instanceof Number t
                        && allowed.contains(t.longValue())) extraMember.add(id.longValue());
            }
        }
        int users = 0, usersActive = 0, admins = 0, locked = 0, never = 0, dormant = 0;
        for (Object[] r : userRepo.findCounterRows()) {
            if (r == null || r.length < 6) continue;
            Long id = r[0] instanceof Number n ? n.longValue() : null;
            Long teamId = r[1] instanceof Number n ? n.longValue() : null;
            if (!unrestricted) {
                boolean member = (teamId != null && allowed.contains(teamId)) || (id != null && extraMember.contains(id));
                if (!member) continue;
            }
            users++;
            boolean active = Boolean.TRUE.equals(r[2]);
            String lastLoginAt = (String) r[5];
            if (active) usersActive++;
            if (active && "ADMIN".equals(r[3])) admins++;
            if (Boolean.TRUE.equals(r[4])) locked++;
            if (active) {
                if (lastLoginAt == null || lastLoginAt.isBlank()) never++;
                else if (lastLoginAt.compareTo(dormantCut) < 0) dormant++;
            }
        }
        // Eskalasyon kişileri
        int contacts = 0, contactsInactive = 0, contactsWebhook = 0;
        for (EscalationContact c : contactRepo.findAll()) {
            if (!unrestricted && (c.getTeamId() == null || !allowed.contains(c.getTeamId()))) continue;
            contacts++;
            if (!Boolean.TRUE.equals(c.getActive())) contactsInactive++;
            else if (c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank()) contactsWebhook++;
        }
        // Bildirim grupları
        int groups = 0, groupsEmpty = 0;
        for (NotificationGroup g : groupRepo.findAll()) {
            if (Boolean.FALSE.equals(g.getActive())) continue;
            if (!unrestricted && (g.getTeamId() == null || !allowed.contains(g.getTeamId()))) continue;
            groups++;
            if (g.getEmails() == null || g.getEmails().isBlank()) groupsEmpty++;
        }
        // Eşikler (global; kapsamlı kullanıcı bu sekmeyi zaten görmez)
        int tierRows = 0;
        AlertThreshold def = null;
        if (unrestricted) {
            for (AlertThreshold t : thresholdRepo.findAll()) {
                if (Boolean.FALSE.equals(t.getActive())) continue;
                if (t.getTier() == null) { if (def == null) def = t; } else tierRows++;
            }
        }

        // GLOBAL yöneticiler (H paketi B8, 2026-11-26): "tek / hiç yönetici" uyarısı yalnız bunları sayar. Kapsamlı müdür (AD
        // ADMIN — viewTeamIds dolu, UserService.computeViewTeamIds) de ADMIN rolündedir ama kapsamı dışını yönetemez; tek
        // global yönetici + birkaç müdür olan kurulumda uyarı eskiden hiç çıkmıyordu. Okunamazsa bilinmiyor → uyarı YOK.
        Long globalAdmins = null;
        if (unrestricted) {
            try {
                globalAdmins = userRepo.countActiveGlobalAdmins();
            } catch (RuntimeException e) {
                globalAdmins = null;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("teams", teams); counts.put("teams_active", teamsActive);
        counts.put("users", users); counts.put("users_active", usersActive); counts.put("admins", admins);
        if (unrestricted) counts.put("global_admins", globalAdmins);
        counts.put("contacts", contacts); counts.put("contacts_webhook", contactsWebhook);
        counts.put("groups", groups);
        if (unrestricted) {
            counts.put("threshold_tiers", tierRows);
            counts.put("threshold_default", def == null ? null : Map.of(
                    "warning", def.getWarningDays() != null ? def.getWarningDays() : 30,
                    "high", def.getHighDays() != null ? def.getHighDays() : 15,
                    "critical", def.getCriticalDays() != null ? def.getCriticalDays() : 7));
        }
        out.put("counts", counts);

        List<Map<String, Object>> warnings = new java.util.ArrayList<>();
        if (teamsNoLeader > 0) warnings.add(warn("TEAM_NO_LEADER", teamsNoLeader, "teams"));
        if (teamsNoEmail > 0) warnings.add(warn("TEAM_NO_EMAIL", teamsNoEmail, "teams"));
        if (groupsEmpty > 0) warnings.add(warn("GROUP_NO_EMAILS", groupsEmpty, "notifyGroups"));
        if (contactsInactive > 0) warnings.add(warn("CONTACT_INACTIVE", contactsInactive, "contacts"));
        if (locked > 0) warnings.add(warn("USER_LOCKED", locked, "users"));
        if (never > 0) warnings.add(warn("USER_NEVER_LOGGED_IN", never, "users"));
        if (dormant > 0) warnings.add(warn("USER_DORMANT", dormant, "users"));
        if (unrestricted && globalAdmins != null && globalAdmins == 1L) warnings.add(warn("SINGLE_ADMIN", 1, "users"));
        if (unrestricted && globalAdmins != null && globalAdmins == 0L) warnings.add(warn("NO_ADMIN", 0, "users"));
        out.put("warnings", warnings);
        out.put("dormant_days", DORMANT_DAYS);
        return out;
    }

    private static Map<String, Object> warn(String code, int count, String tab) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code); m.put("count", count); m.put("tab", tab);
        return m;
    }
}

package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Olay/Hata kaydı oluşturulduğunda veya güncellendiğinde, olayın kayıtlı olduğu
 * takımın e-posta adresine ve takım müdürüne (Team.leaderId) executive bir bildirim
 * gönderir. Best-effort + asenkron: HTTP yanıtını bloklamaz, hata kaydı kaydını
 * etkilemez (mail patlasa bile olay kaydedilmiştir). Controller'dan çağrılır
 * (IncidentService'e bağımlılık eklemez → @DataJpaTest izolasyonu korunur).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentNotificationService {

    private final EmailNotificationService emailService;
    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;

    @Value("${cert.monitor.app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "incident-mail");
        t.setDaemon(true);
        return t;
    });

    /** dto = controller'ın ürettiği snake_case olay haritası. kind = NEW | UPDATED | RESOLVED. */
    public void notifyIncident(Map<String, Object> dto, String kind) {
        if (dto == null) return;
        exec.submit(() -> {
            try { doNotify(dto, kind); }
            catch (Exception e) { log.warn("Incident notification failed: {}", e.getMessage()); }
        });
    }

    private void doNotify(Map<String, Object> dto, String kind) {
        Object teamIdObj = dto.get("team_id");
        if (!(teamIdObj instanceof Number tid)) {
            log.debug("Incident notify skipped — no team_id on incident '{}'", dto.get("title"));
            return;
        }
        Team team = teamRepo.findById(tid.longValue()).orElse(null);
        if (team == null) { log.warn("Incident notify: team {} not found", teamIdObj); return; }

        List<String> recipients = new ArrayList<>();
        if (team.getEmail() != null && !team.getEmail().isBlank()) recipients.add(team.getEmail().trim());

        String managerName = null;
        if (team.getLeaderId() != null) {
            AppUser mgr = userRepo.findById(team.getLeaderId()).orElse(null);
            if (mgr != null) {
                managerName = (mgr.getDisplayName() != null && !mgr.getDisplayName().isBlank())
                        ? mgr.getDisplayName() : mgr.getUsername();
                String me = mgr.getEmail();
                if (me != null && !me.isBlank() && !recipients.contains(me.trim())) recipients.add(me.trim());
            }
        }
        if (recipients.isEmpty()) {
            log.warn("Incident notify: team {} has no email and no manager email — skipped", team.getId());
            return;
        }

        String base = appBaseUrl != null ? appBaseUrl.replaceAll("/+$", "") : "";
        String ctaUrl = base + "/?tab=incident-history";
        String html = emailService.buildIncidentNotificationHtml(dto, managerName, kind, ctaUrl);
        String prefix = "RESOLVED".equals(kind) ? "Olay Çözüldü"
                      : "NEW".equals(kind)      ? "Yeni Olay"
                                                : "Olay Güncellendi";
        String subject = "[CertMonitor] " + prefix + " — " + dto.get("title") + " (" + team.getName() + ")";

        String status = emailService.sendHtml(recipients.toArray(new String[0]), null, subject, html, null);
        log.info("Incident notification ({}) team={} to={} status={}", kind, team.getId(), recipients, status);
    }
}

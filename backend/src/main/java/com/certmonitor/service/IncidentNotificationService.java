package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.IncidentImageRepository;
import com.certmonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final IncidentImageRepository imageRepo;
    private final AppSettingsService appSettings;

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

    /** E-posta deep-link'leri için dış base URL — CANLI okunur (Genel Ayarlar'dan değişebilir);
     *  @Value yalnız fallback. Haftalık reminder/approve linkleriyle AYNI kaynak (cert.monitor.app.base-url). */
    private String baseUrl() {
        String url = appSettings.getString("cert.monitor.app.base-url", appBaseUrl);
        return (url != null && !url.isBlank()) ? url.replaceAll("/+$", "") : "";
    }

    /* package-private (test): senkron çözüm + alıcı/konu doğrulaması için. */
    void doNotify(Map<String, Object> dto, String kind) {
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

        String base = baseUrl();
        Object incId = dto.get("id");
        // Spesifik olaya deep-link: frontend ?incident=<id>'yi okuyup detay modalını açar.
        String ctaUrl = base + "/?tab=incident-history" + (incId != null ? "&incident=" + incId : "");
        String html = emailService.buildIncidentNotificationHtml(dto, managerName, kind, ctaUrl);
        String prefix = "RESOLVED".equals(kind) ? "Olay Çözüldü"
                      : "NEW".equals(kind)      ? "Yeni Olay"
                                                : "Olay Güncellendi";
        String subject = "[CertMonitor] " + prefix + " — " + dto.get("title") + " (" + team.getName() + ")";

        List<EmailNotificationService.InlineImage> inline = collectInlineImages(dto);
        String status = emailService.sendHtml(recipients.toArray(new String[0]), null, subject, html,
                inline.isEmpty() ? null : inline);
        log.info("Incident notification ({}) team={} to={} status={} images={}",
                kind, team.getId(), recipients, status, inline.size());
    }

    /** Önizleme için olay bildirim HTML'i — KAYDETMEZ/GÖNDERMEZ. Görseller /api/incidents/images/{id} URL'siyle
     *  kalır (iframe oturum çerezi ile yükler). team_name'i team_id'den, müdür adını takım leader'ından çözer.
     *  Alıcı/team-mail gerektirmez (yalnız HTML üretir). */
    public String previewHtml(Map<String, Object> dto, String kind) {
        if (dto == null) return "";
        Map<String, Object> inc = new java.util.LinkedHashMap<>(dto);
        String managerName = null;
        if (dto.get("team_id") instanceof Number tid) {
            Team team = teamRepo.findById(tid.longValue()).orElse(null);
            if (team != null) {
                inc.put("team_name", team.getName());   // form'daki team_id'ye göre güncel takım adı
                if (team.getLeaderId() != null) {
                    AppUser mgr = userRepo.findById(team.getLeaderId()).orElse(null);
                    if (mgr != null) managerName = (mgr.getDisplayName() != null && !mgr.getDisplayName().isBlank())
                            ? mgr.getDisplayName() : mgr.getUsername();
                }
            }
        }
        String base = baseUrl();
        Object incId = dto.get("id");
        String ctaUrl = base + "/?tab=incident-history" + (incId != null ? "&incident=" + incId : "");
        String norm = ("NEW".equals(kind) || "RESOLVED".equals(kind)) ? kind : "UPDATED";
        return emailService.buildIncidentNotificationHtml(inc, managerName, norm, ctaUrl, false);
    }

    private static final Pattern INC_IMG_ID = Pattern.compile("/api/incidents/images/(\\d+)");

    /** Olayın markdown alanlarındaki /api/incidents/images/{id} ref'lerini tarar, görselleri yükleyip
     *  CID inline ekleri (cid:incimg{id}) döner — buildIncidentNotificationHtml'in gömdüğü CID img'lerle eşleşir.
     *  Eskiden mail görselleri hiç eklemiyordu (textBlock siliyor + inline=null) → mailde görsel görünmüyordu. */
    private List<EmailNotificationService.InlineImage> collectInlineImages(Map<String, Object> dto) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String field : List.of("rca_summary", "description", "resolution_steps", "business_impact")) {
            Object v = dto.get(field);
            if (v == null) continue;
            Matcher m = INC_IMG_ID.matcher(v.toString());
            while (m.find()) ids.add(Long.parseLong(m.group(1)));
        }
        List<EmailNotificationService.InlineImage> out = new ArrayList<>();
        for (Long id : ids) {
            imageRepo.findById(id).ifPresent(img -> out.add(new EmailNotificationService.InlineImage(
                    "incimg" + id, img.getData(), img.getContentType())));
        }
        return out;
    }
}
